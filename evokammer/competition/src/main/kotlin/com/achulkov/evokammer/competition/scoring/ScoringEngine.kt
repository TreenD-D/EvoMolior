package com.achulkov.evokammer.competition.scoring

import com.achulkov.evokammer.competition.grader.GradingSheet
import com.achulkov.evokammer.competition.grader.MetaJudge
import com.achulkov.evokammer.competition.grader.MetaJudgeVerdict
import com.achulkov.evokammer.core.contract.DeliverableArtifactType
import com.achulkov.evokammer.core.contract.DeliverableSkeletonNodeUpdate
import com.achulkov.evokammer.core.model.DimensionScore
import com.achulkov.evokammer.core.model.GradingResult
import com.achulkov.evokammer.core.model.RoundScore
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

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
        val verdict = try {
            metaJudge.verdict(revealed.roundIndex)
        } catch (e: Exception) {
            Napier.e("ScoringEngine: MetaJudge could not produce verdict for round ${revealed.roundIndex}: ${e.message}")
            return null
        }

        val roundScores = computeRoundScores(revealed, verdict)
        val winner = roundScores.minByOrNull { it.rank }?.groupId
            ?: revealed.scores.keys.firstOrNull() ?: "unknown"

        val result = GradingResult(
            roundIndex = revealed.roundIndex,
            scores = roundScores,
            winnerId = winner,
            acceptedRefinements = verdict.acceptedRefinements,
            newRubricPolicies = verdict.newRubricPolicies
        )

        _gradingResults.emit(result)
        Napier.i("ScoringEngine: round ${revealed.roundIndex} scored — winner=$winner " +
                "(forced=${revealed.wasForced}, missing=${revealed.missingGroupIds})")

        val deliverableUpdates = synthesiseDeliverableNodeUpdates(revealed, verdict, roundScores)
        if (deliverableUpdates.isNotEmpty()) {
            _deliverableNodeUpdates.emit(deliverableUpdates)
        }

        // Emit telemetry for each group
        for ((groupId, groupResult) in revealed.scores) {
            if (groupResult.failureModesTriggered.isNotEmpty() || groupResult.automatedTestsFailed > 0) {
                val telemetry = FailureTelemetry(
                    groupId = groupId,
                    roundIndex = revealed.roundIndex,
                    failureModeIds = groupResult.failureModesTriggered,
                    automatedTestsFailed = groupResult.automatedTestsFailed,
                    gradingSheetIds = verdict.graderSheetIds,
                    rawScore = groupResult.totalRaw,
                    weightedScore = roundScores.find { it.groupId == groupId }?.weightedTotal ?: 0.0
                )
                _failureTelemetry.emit(telemetry)
            }
        }

        return result
    }

    // ── Private helpers ─────────────────────────────────────────────────────────

    private fun computeRoundScores(
        revealed: RevealedScores,
        verdict: MetaJudgeVerdict
    ): List<RoundScore> {
        val rawScores = revealed.scores.values.map { groupResult ->
            val dimScore = verdict.dimensionWeights.entries.sumOf { (dimId, weight) ->
                val raw = groupResult.dimensionScores[dimId] ?: 0.0
                raw * weight
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

        // Rank: lower rank = higher score (rank 1 = winner)
        val sorted = rawScores.sortedByDescending { it.second }
        return sorted.mapIndexed { idx, (groupId, weightedScore) ->
            val dimScoreList = verdict.dimensionWeights.keys.map { dimId ->
                DimensionScore(
                    dimensionId = dimId,
                    rawScore = revealed.scores[groupId]?.dimensionScores?.get(dimId) ?: 0.0,
                    justification = "Auto-computed by ScoringEngine"
                )
            }
            RoundScore(
                groupId = groupId,
                roundIndex = revealed.roundIndex,
                dimensionScores = dimScoreList,
                weightedTotal = weightedScore,
                rank = idx + 1
            )
        }
    }

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
                DeliverableSkeletonNodeUpdate(
                    dstNodeRef = nodeRef,
                    groupId = groupId,
                    checkinId = "round-${revealed.roundIndex}",
                    dimensionScores = dimScores.toMap(),
                    graderIds = verdict.graderSheetIds,
                    rubricVersion = "meta-judge-round-${revealed.roundIndex}",
                    graderNotes = listOf("Synthesised from round scoring for group $groupId"),
                    passedThreshold = (scoreByGroup[groupId]?.weightedTotal ?: 0.0) > 0.6,
                    artifactRef = "group:$groupId:round:${revealed.roundIndex}",
                    artifactType = DeliverableArtifactType.REPO_SNAPSHOT,
                    trustScore = (scoreByGroup[groupId]?.weightedTotal ?: 0.0) / 100.0,
                    owningGeneration = revealed.roundIndex
                )
            }
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
data class FailureTelemetry(
    val groupId: String,
    val roundIndex: Int,
    val failureModeIds: List<String>,
    val automatedTestsFailed: Int,
    val gradingSheetIds: List<String>,
    val rawScore: Double,
    val weightedScore: Double
)
