package com.achulkov.evomolior.competition.scoring

import com.achulkov.evomolior.competition.grader.GradingSheet
import com.achulkov.evomolior.competition.grader.MetaJudge
import com.achulkov.evomolior.competition.grader.MetaJudgeVerdict
import com.achulkov.evomolior.core.contract.DeliverableArtifactType
import com.achulkov.evomolior.core.contract.DeliverableSkeletonNodeUpdate
import com.achulkov.evomolior.core.model.DimensionScore
import com.achulkov.evomolior.core.model.GradingResult
import com.achulkov.evomolior.core.model.RoundScore
import com.achulkov.evomolior.core.model.ScoringDimension
import com.achulkov.evomolior.core.model.ScoringDimensionCatalog
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.Serializable

/**
 * Isolated scoring engine that applies [MetaJudgeVerdict]s to [GroupRoundResult]s.
 *
 * ### Isolation guarantee
 * The [ScoringEngine] is wired to the [BlindSubmissionGate] — it only processes results
 * after the gate closes. It has NO access to any group's live substrate; it only sees
 * the submitted [GroupRoundResult] snapshots.
 *
 * ### Flow
 * 1. [BlindSubmissionGate.revealedScores] fires → [processRevealedScores] is called
 * 2. MetaJudge provides the final weighted dimensions for the round
 * 3. Scores are computed: weighted dimension sum - failure mode penalties
 * 4. [GradingResult] is emitted on [gradingResults]
 * 5. Failure telemetry is routed back to Grader Group KB via [failureTelemetry]
 *
 * @param metaJudge The [MetaJudge] holding GradingSheets for this run.
 * @param blindGate The [BlindSubmissionGate] this engine subscribes to.
 */
class ScoringEngine(
    private val metaJudge: MetaJudge,
    private val blindGate: BlindSubmissionGate
) {
    private companion object {
        /** Deliverable scores use the canonical declared 0-100 scoring scale. */
        const val DELIVERABLE_PASS_THRESHOLD = 60.0
    }

    private val _gradingResults = MutableSharedFlow<GradingResult>(extraBufferCapacity = 64)

    /** Emits final [GradingResult]s after each blind reveal. */
    val gradingResults: SharedFlow<GradingResult> = _gradingResults.asSharedFlow()

    private val _failureTelemetry = MutableSharedFlow<FailureTelemetry>(extraBufferCapacity = 256)

    /**
     * Emits per-group failure telemetry after scoring.
     * Grader Group KBs should subscribe to this to learn from failures.
     */
    val failureTelemetry: SharedFlow<FailureTelemetry> = _failureTelemetry.asSharedFlow()

    private val _deliverableNodeUpdates = MutableSharedFlow<List<DeliverableSkeletonNodeUpdate>>(extraBufferCapacity = 64)

    /** Emits deliverable-skeleton node updates synthesized from round scoring. */
    val deliverableNodeUpdates: SharedFlow<List<DeliverableSkeletonNodeUpdate>> = _deliverableNodeUpdates.asSharedFlow()

    /**
     * Processes [RevealedScores] from the blind gate.
     *
     * This should be called by the Watchdog each time [BlindSubmissionGate.revealedScores] emits.
     */
    suspend fun processRevealedScores(revealed: RevealedScores): GradingResult? {
        return processRevealedScoresDetailed(revealed)?.result
    }

    /**
     * Processes a reveal and returns every final post-reveal output synchronously.
     *
     * @param revealed Complete blind-gate reveal.
     * @return Final scoring output, or null when no MetaJudge verdict exists.
     */
    suspend fun processRevealedScoresDetailed(revealed: RevealedScores): ScoringOutcome? {
        val verdict = try {
            metaJudge.verdict(revealed.roundIndex)
        } catch (e: Exception) {
            Napier.e("ScoringEngine: MetaJudge could not produce verdict for round ${revealed.roundIndex}: ${e.message}")
            return null
        }
        val requiredDimensionIds = verdict.dimensionWeights.keys
        val incompleteGroups = revealed.scores.mapNotNull { (groupId, result) ->
            val missing = requiredDimensionIds - result.dimensionScores.keys
            missing.takeIf { it.isNotEmpty() }?.let { groupId to it }
        }.toMap()
        if (revealed.missingGroupIds.isNotEmpty() || incompleteGroups.isNotEmpty()) {
            throw IncompleteScoreEvidenceException(
                roundIndex = revealed.roundIndex,
                missingGroupIds = revealed.missingGroupIds,
                missingDimensionIdsByGroup = incompleteGroups
            )
        }

        val ranked = computeRoundScores(revealed, verdict)
        val roundScores = ranked.scores
        val outcome = ScoringRoundResolver.resolve(roundScores)

        val result = GradingResult(
            roundIndex = revealed.roundIndex,
            scores = roundScores,
            winnerId = outcome.winnerId,
            resolution = outcome.resolution,
            acceptedRefinements = verdict.acceptedRefinements,
            newRubricPolicies = verdict.newRubricPolicies,
            paretoFronts = ranked.fronts,
            dimensionNodeRefs = verdict.dimensionNodeRefs.mapNotNull { (dimensionId, nodeRef) ->
                nodeRef?.takeIf { it.isNotBlank() }?.let { dimensionId to it }
            }.toMap()
        )

        _gradingResults.emit(result)
        Napier.i("ScoringEngine: round ${revealed.roundIndex} scored — winner=${outcome.winnerId} " +
                "resolution=${outcome.resolution} " +
                "(forced=${revealed.wasForced}, missing=${revealed.missingGroupIds})")

        val deliverableUpdates = synthesiseDeliverableNodeUpdates(revealed, verdict, roundScores)
        if (deliverableUpdates.isNotEmpty()) {
            _deliverableNodeUpdates.emit(deliverableUpdates)
        }

        val telemetry = revealed.scores.mapNotNull { (groupId, groupResult) ->
            if (groupResult.failureModesTriggered.isNotEmpty() || groupResult.automatedTestsFailed > 0) {
                FailureTelemetry(
                    groupId = groupId,
                    roundIndex = revealed.roundIndex,
                    failureModeIds = groupResult.failureModesTriggered,
                    automatedTestsFailed = groupResult.automatedTestsFailed,
                    gradingSheetIds = verdict.graderSheetIds,
                    rawScore = groupResult.totalRaw,
                    weightedScore = roundScores.find { it.groupId == groupId }?.weightedTotal ?: 0.0
                )
            } else null
        }
        telemetry.forEach { _failureTelemetry.emit(it) }

        return ScoringOutcome(
            result = result,
            failureTelemetry = telemetry,
            deliverableNodeUpdates = deliverableUpdates
        )
    }

    // ── Private helpers ─────────────────────────────────────────────────────────

    private fun computeRoundScores(
        revealed: RevealedScores,
        verdict: MetaJudgeVerdict
    ): ParetoRankingResult {
        val dimensions = verdict.dimensionWeights.map { (dimId, weight) ->
            ScoringDimensionCatalog.byId(dimId)?.copy(weight = weight)
                ?: ScoringDimension(
                    id = dimId,
                    name = dimId,
                    description = "Meta-judge dimension $dimId",
                    weight = weight
                )
        }
        val rawScores = revealed.scores.values.map { groupResult ->
            val dimScore = dimensions.sumOf { dimension ->
                val raw = groupResult.dimensionScores[dimension.id] ?: dimension.missingRawScore()
                val normalized = raw.coerceIn(0.0, dimension.maxScore) / dimension.maxScore
                val directional = if (dimension.higherIsBetter) normalized else 1.0 - normalized
                directional * dimension.weight * 100.0
            }

            // Penalty for triggered failure modes
            val penalty = groupResult.failureModesTriggered.sumOf { modeId ->
                verdict.finalFailureModes.find { it.modeId == modeId }?.penaltyWeight ?: 0.0
            }

            // Penalty for failed mandatory tests
            val mandatoryFailPenalty = verdict.finalAutomatedTests
                .filter { it.isMandatory }
                .sumOf { it.penaltyOnFailure } *
                    (groupResult.automatedTestsFailed.toDouble() /
                            (groupResult.automatedTestsPassed + groupResult.automatedTestsFailed + 1))

            val weighted = (dimScore - penalty - mandatoryFailPenalty).coerceAtLeast(0.0)

            groupResult.groupId to weighted
        }

        val totalWeight = dimensions.sumOf { it.weight }.takeIf { it > 0.0 } ?: 1.0
        val unranked = rawScores.map { (groupId, weightedScore) ->
            val groupResult = revealed.scores.getValue(groupId)
            val dimScoreList = dimensions.map { dimension ->
                val submittedRawScore = groupResult.dimensionScores[dimension.id]
                DimensionScore(
                    dimensionId = dimension.id,
                    rawScore = submittedRawScore ?: dimension.missingRawScore(),
                    justification = if (submittedRawScore == null) {
                        "Missing evidence: the sealed submission supplied no ${dimension.id} score " +
                            "or grader-authored artifact rationale."
                    } else {
                        groupResult.dimensionJustifications[dimension.id]
                            ?.takeIf { it.isNotBlank() }
                            ?: "Sealed submission reported ${dimension.id}=$submittedRawScore/${dimension.maxScore}. " +
                                "Missing evidence: no grader-authored artifact citation or dimension-specific " +
                                "rationale accompanied this numeric score."
                    }
                )
            }
            RoundScore(
                groupId = groupId,
                roundIndex = revealed.roundIndex,
                dimensionScores = dimScoreList,
                weightedTotal = (weightedScore / totalWeight).coerceIn(0.0, 100.0)
            )
        }
        return ParetoRanking(dimensions).rank(unranked)
    }

    private fun ScoringDimension.missingRawScore(): Double = if (higherIsBetter) 0.0 else maxScore

    private fun synthesiseDeliverableNodeUpdates(
        revealed: RevealedScores,
        verdict: MetaJudgeVerdict,
        roundScores: List<RoundScore>
    ): List<DeliverableSkeletonNodeUpdate> {
        val scoreByGroup = roundScores.associateBy { it.groupId }
        return revealed.scores.flatMap { (groupId, groupResult) ->
            val refScores = mutableMapOf<String, MutableMap<String, Double>>()
            verdict.dimensionNodeRefs.forEach { (dimId, nodeRef) ->
                if (nodeRef.isNullOrBlank()) return@forEach
                val score = groupResult.dimensionScores[dimId] ?: return@forEach
                refScores.getOrPut(nodeRef) { mutableMapOf() }[dimId] = score
            }

            refScores.map { (nodeRef, dimScores) ->
                val weightedTotal = scoreByGroup[groupId]?.weightedTotal ?: 0.0
                val passedThreshold = weightedTotal >= DELIVERABLE_PASS_THRESHOLD
                DeliverableSkeletonNodeUpdate(
                    dstNodeRef = nodeRef,
                    groupId = groupId,
                    checkinId = "round-${revealed.roundIndex}",
                    dimensionScores = dimScores.toMap(),
                    graderIds = verdict.graderSheetIds,
                    rubricVersion = "meta-judge-round-${revealed.roundIndex}",
                    graderNotes = if (passedThreshold) {
                        emptyList()
                    } else {
                        dimScores.mapNotNull { (dimensionId, rawScore) ->
                            failingDimensionNote(dimensionId, rawScore)
                        }
                    },
                    passedThreshold = passedThreshold,
                    artifactRef = "group:$groupId:round:${revealed.roundIndex}",
                    artifactType = DeliverableArtifactType.REPO_SNAPSHOT,
                    trustScore = weightedTotal / 100.0,
                    owningGeneration = revealed.roundIndex
                )
            }
        }
    }

    private fun failingDimensionNote(dimensionId: String, rawScore: Double): String? {
        val dimension = ScoringDimensionCatalog.byId(dimensionId) ?: ScoringDimension(
            id = dimensionId,
            name = dimensionId,
            description = "Meta-judge dimension $dimensionId",
            weight = 1.0
        )
        val boundedRawScore = rawScore.coerceIn(0.0, dimension.maxScore)
        val directionalScore = if (dimension.higherIsBetter) {
            boundedRawScore / dimension.maxScore * 100.0
        } else {
            (1.0 - boundedRawScore / dimension.maxScore) * 100.0
        }
        if (directionalScore >= DELIVERABLE_PASS_THRESHOLD) return null

        return if (dimension.higherIsBetter) {
            "Improve ${dimension.name} to at least $DELIVERABLE_PASS_THRESHOLD/100.0 " +
                "(current $boundedRawScore/${dimension.maxScore})."
        } else {
            val maximumPassingRawScore =
                dimension.maxScore * (1.0 - DELIVERABLE_PASS_THRESHOLD / 100.0)
            "Reduce ${dimension.name} to $maximumPassingRawScore/${dimension.maxScore} or lower " +
                "(current $boundedRawScore/${dimension.maxScore})."
        }
    }

    /**
     * Registers a [GradingSheet] with the underlying [MetaJudge].
     * Sheets must be sealed before registration.
     */
    fun registerGradingSheet(sheet: GradingSheet) {
        metaJudge.registerSheet(sheet)
        Napier.d("ScoringEngine: registered grading sheet ${sheet.sheetId} for round ${sheet.roundIndex}")
    }
}

/**
 * Complete post-reveal scoring output.
 *
 * @param result Final grading result.
 * @param failureTelemetry Grader-learning telemetry.
 * @param deliverableNodeUpdates Deliverable updates derived from final scores.
 */
data class ScoringOutcome(
    val result: GradingResult,
    val failureTelemetry: List<FailureTelemetry>,
    val deliverableNodeUpdates: List<DeliverableSkeletonNodeUpdate>
)

/**
 * Raised when sealed scoring evidence lacks an expected group or rubric dimension.
 *
 * @param roundIndex Incomplete round.
 * @param missingGroupIds Expected groups absent from the reveal.
 * @param missingDimensionIdsByGroup Missing rubric dimensions keyed by submitted group.
 */
class IncompleteScoreEvidenceException(
    val roundIndex: Int,
    val missingGroupIds: List<String>,
    val missingDimensionIdsByGroup: Map<String, Set<String>>
) : IllegalStateException(
    "Incomplete score evidence for round=$roundIndex; missingGroups=${missingGroupIds.sorted()} " +
        "missingDimensions=${missingDimensionIdsByGroup.mapValues { it.value.sorted() }}"
)

/**
 * Per-group failure telemetry emitted after scoring.
 * Routed to Grader Group KBs to improve future GradingSheets.
 *
 * @param groupId The group that experienced failures.
 * @param roundIndex The round this telemetry is from.
 * @param failureModeIds Failure mode IDs triggered by this group.
 * @param automatedTestsFailed Count of automated tests that failed.
 * @param gradingSheetIds The GradingSheet IDs that flagged these failures.
 * @param rawScore The group's raw (unweighted) score.
 * @param weightedScore The group's final weighted score.
 */
@Serializable
data class FailureTelemetry(
    val groupId: String,
    val roundIndex: Int,
    val failureModeIds: List<String>,
    val automatedTestsFailed: Int,
    val gradingSheetIds: List<String>,
    val rawScore: Double,
    val weightedScore: Double
)
