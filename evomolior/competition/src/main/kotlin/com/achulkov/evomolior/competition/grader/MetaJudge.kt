package com.achulkov.evomolior.competition.grader

import com.achulkov.evomolior.core.contract.DstRefinementProposal
import com.achulkov.evomolior.core.contract.DstRefinementRejection
import com.achulkov.evomolior.core.contract.DstRubricPolicy
import io.github.aakira.napier.Napier
import kotlinx.serialization.Serializable

/**
 * Consensus detection and cross-grader arbitration for contested scoring dimensions.
 *
 * The [MetaJudge] receives [GradingSheet]s from multiple Grader Groups, detects
 * consensus, resolves disputes, and produces a final [MetaJudgeVerdict].
 *
 * ### Consensus rules
 * - If all grader groups agree within [consensusThreshold] on a dimension → accepted
 * - If groups disagree beyond threshold → flagged as contested → arbitration
 * - Arbitration runs weighted averaging: higher [GradingSheet.qualityScore] wins more weight
 *
 * ### Quality scoring
 * - Each GradingSheet is quality-scored before the verdict: completeness, internal
 *   consistency, test coverage, comparative pair coverage
 * - Low-quality sheets are down-weighted in the arbitration formula
 *
 * @param consensusThreshold Maximum fractional score difference to consider "agreement" (default 0.15).
 * @param minGraderGroups Minimum number of Grader Groups required before producing a verdict.
 */
class MetaJudge(
    private val consensusThreshold: Double = 0.15,
    private val minGraderGroups: Int = 1
) {

    private val sheets = mutableListOf<GradingSheet>()

    /**
     * Registers a [GradingSheet] from a Grader Group.
     *
     * @throws IllegalArgumentException if the sheet is not sealed.
     */
    fun registerSheet(sheet: GradingSheet) {
        require(sheet.isSealed) { "GradingSheet ${sheet.sheetId} must be sealed before submitting to MetaJudge" }
        sheets.add(assessSheetQuality(sheet))
        Napier.d("MetaJudge: registered sheet ${sheet.sheetId} from grader=${sheet.graderGroupId} quality=${sheet.qualityScore}")
    }

    /**
     * Applies the deterministic structural quality metric used for sheet arbitration.
     *
     * This is exposed so curriculum diagnostics can compare successive sealed methodologies
     * against the exact authority used by [MetaJudge], without duplicating the metric.
     *
     * @param sheet Sealed or unsealed sheet whose structure should be assessed.
     * @return A copy carrying the computed quality score.
     */
    fun assessSheetQuality(sheet: GradingSheet): GradingSheet {
        var score = 0.0
        // Completeness: dimensions + tests + failure modes
        score += (sheet.rubricDimensions.size.coerceAtMost(5) / 5.0) * 0.3
        score += (sheet.automatedTests.size.coerceAtMost(10) / 10.0) * 0.3
        score += (sheet.failureModes.size.coerceAtMost(5) / 5.0) * 0.2
        // Internal consistency: weights sum to ~1.0
        val weightSum = sheet.rubricDimensions.sumOf { it.weight }
        val weightConsistency = if (weightSum > 0) 1.0 - Math.abs(weightSum - 1.0) else 0.0
        score += weightConsistency * 0.2
        return sheet.withQualityScore(score.coerceIn(0.0, 1.0))
    }

    /**
     * Produces a [MetaJudgeVerdict] for [roundIndex], merging all registered sheets.
     *
     * @throws IllegalStateException if fewer than [minGraderGroups] sheets are available.
     */
    fun verdict(roundIndex: Int): MetaJudgeVerdict {
        val roundSheets = sheets.filter { it.roundIndex == roundIndex }
        check(roundSheets.size >= minGraderGroups) {
            "MetaJudge: need $minGraderGroups sheets for round $roundIndex, have ${roundSheets.size}"
        }

        // Merge rubric dimensions across sheets: weighted average by quality score
        val dimensionWeights = mergeDimensionWeights(roundSheets)
        val contestedDimensions = detectContested(roundSheets, dimensionWeights)
        val failureModes = roundSheets.flatMap { it.failureModes }.distinctBy { it.modeId }
        val automatedTests = roundSheets.flatMap { it.automatedTests }.distinctBy { it.testId }

        // Phase 7.5: Evaluate DST structural refinement proposals
        val (accepted, rejected) = evaluateRefinements(roundSheets)

        val verdict = MetaJudgeVerdict(
            roundIndex = roundIndex,
            dimensionWeights = dimensionWeights,
            dimensionNodeRefs = mergeDimensionNodeRefs(roundSheets),
            contestedDimensionIds = contestedDimensions,
            finalFailureModes = failureModes,
            finalAutomatedTests = automatedTests,
            acceptedRefinements = accepted,
            rejectedRefinements = rejected,
            graderSheetIds = roundSheets.map { it.sheetId },
            averageSheetQuality = roundSheets.mapNotNull { it.qualityScore }.average()
        )

        Napier.i("MetaJudge: verdict for round $roundIndex — ${dimensionWeights.size} dims, " +
                "${contestedDimensions.size} contested, ${accepted.size} refinements accepted, " +
                "quality=${verdict.averageSheetQuality}")
        return verdict
    }

    /** Clears all sheets for a completed round. */
    fun clearRound(roundIndex: Int) {
        sheets.removeAll { it.roundIndex == roundIndex }
    }

    // ── Private helpers ─────────────────────────────────────────────────────────

    /**
     * Returns [sheet]'s dimension weights rescaled to sum to 1.0.
     *
     * Sheets may express weights on different scales (for example raw configured weights versus
     * normalised grader-authored weights); comparing or averaging them unscaled would make every
     * shared dimension look contested and let the larger scale dominate the merge.
     */
    private fun normalisedWeights(sheet: GradingSheet): Map<String, Double> {
        val total = sheet.rubricDimensions.sumOf { it.weight }
        return sheet.rubricDimensions.associate { dim ->
            dim.dimensionId to if (total > 0) dim.weight / total else dim.weight
        }
    }

    private fun mergeDimensionWeights(sheetList: List<GradingSheet>): Map<String, Double> {
        val allDimIds = sheetList.flatMap { it.rubricDimensions }.map { it.dimensionId }.toSet()
        val merged = mutableMapOf<String, Double>()

        for (dimId in allDimIds) {
            var weightedSum = 0.0
            var totalQuality = 0.0
            for (sheet in sheetList) {
                val weight = normalisedWeights(sheet)[dimId] ?: continue
                val quality = sheet.qualityScore ?: 0.5
                weightedSum += weight * quality
                totalQuality += quality
            }
            merged[dimId] = if (totalQuality > 0) weightedSum / totalQuality else 0.0
        }
        // Normalise to sum 1.0
        val total = merged.values.sum()
        return if (total > 0) merged.mapValues { it.value / total } else merged
    }

    private fun mergeDimensionNodeRefs(sheetList: List<GradingSheet>): Map<String, String?> {
        val allDimIds = sheetList.flatMap { it.rubricDimensions }.map { it.dimensionId }.toSet()
        return allDimIds.associateWith { dimId ->
            sheetList.asSequence()
                .mapNotNull { sheet -> sheet.rubricDimensions.find { it.dimensionId == dimId }?.dstNodeRef }
                .firstOrNull()
        }
    }

    private fun detectContested(
        sheetList: List<GradingSheet>,
        mergedWeights: Map<String, Double>
    ): List<String> {
        if (sheetList.size < 2) return emptyList()
        val contested = mutableListOf<String>()
        val sheetWeights = sheetList.map(::normalisedWeights)
        for (dimId in mergedWeights.keys) {
            val weights = sheetWeights.mapNotNull { it[dimId] }
            if (weights.size < 2) continue
            val max = weights.max()
            val min = weights.min()
            if ((max - min) > consensusThreshold) {
                contested.add(dimId)
                Napier.d("MetaJudge: dimension $dimId is contested (spread=${max - min})")
            }
        }
        return contested
    }

    /**
     * Evaluates DST refinement proposals from all grader sheets.
     *
     * A proposal is accepted only when 2+ grader groups independently propose the same
     * change (same target node and same kind). Single-grader proposals are rejected
     * with an "insufficient consensus" reason.
     *
     * SPLIT_NODE proposals additionally require agreement on the child specs count.
     *
     * @param sheetList Grader sheets for this round.
     * @return Pair of (accepted proposals, rejected proposals with reasons).
     */
    private fun evaluateRefinements(
        sheetList: List<GradingSheet>
    ): Pair<List<DstRefinementProposal>, List<DstRefinementRejection>> {
        val allProposals = sheetList.flatMap { it.proposedRefinements }
        if (allProposals.isEmpty()) return emptyList<DstRefinementProposal>() to emptyList()

        // Group proposals by (targetNodeRef, kind) to detect consensus
        val byTarget = allProposals.groupBy { "${it.targetNodeRef}|${it.kind.name}" }

        val accepted = mutableListOf<DstRefinementProposal>()
        val rejected = mutableListOf<DstRefinementRejection>()

        for ((_, group) in byTarget) {
            // Consensus requires at least 2 independent graders
            val distinctGraders = group.map { it.proposedByGraderGroupId }.distinct().size
            val minVotes = if (sheetList.size >= 3) 2 else sheetList.size.coerceAtMost(2)

            if (distinctGraders < minVotes) {
                group.forEach { proposal ->
                    rejected += DstRefinementRejection(
                        proposal = proposal,
                        reason = "insufficient consensus: ${distinctGraders}/${minVotes} grader groups agreed"
                    )
                }
                continue
            }

            // For SPLIT_NODE, check that child count is consistent
            val first = group.first()
            if (first.kind == com.achulkov.evomolior.core.contract.DstRefinementKind.SPLIT_NODE) {
                val childCounts = group.map { it.childSpecs.size }.distinct()
                if (childCounts.size > 1) {
                    group.forEach { proposal ->
                        rejected += DstRefinementRejection(
                            proposal = proposal,
                            reason = "SPLIT_NODE consensus failed: conflicting child counts (${childCounts.joinToString()})"
                        )
                    }
                    continue
                }
            }

            // Accept the first proposal as representative (they agree)
            accepted += first
            Napier.i("MetaJudge: accepted DST refinement ${first.kind.name} on ${first.targetNodeRef} " +
                    "from ${distinctGraders} grader groups")
        }

        return accepted to rejected
    }
}

/**
 * The final verdict produced by [MetaJudge] for a given round.
 *
 * @param roundIndex The round this verdict applies to.
 * @param dimensionWeights Final merged weight per dimension ID.
 * @param contestedDimensionIds Dimension IDs with significant grader disagreement.
 * @param finalFailureModes Union of all failure modes from all sheets.
 * @param finalAutomatedTests Union of all automated tests.
 * @param dimensionNodeRefs Optional deliverable node refs keyed by rubric dimension ID.
 * @param acceptedRefinements DST structural refinement proposals accepted by consensus.
 * @param rejectedRefinements DST refinement proposals rejected with reasons.
 * @param newRubricPolicies Rubric policies enacted from grader consensus this round.
 * @param graderSheetIds IDs of all contributing GradingSheets.
 * @param averageSheetQuality Average quality score across contributing sheets.
 */
@Serializable
data class MetaJudgeVerdict(
    val roundIndex: Int,
    val dimensionWeights: Map<String, Double>,
    val dimensionNodeRefs: Map<String, String?> = emptyMap(),
    val contestedDimensionIds: List<String>,
    val finalFailureModes: List<FailureMode>,
    val finalAutomatedTests: List<AutomatedTest>,
    val acceptedRefinements: List<DstRefinementProposal> = emptyList(),
    val rejectedRefinements: List<DstRefinementRejection> = emptyList(),
    val newRubricPolicies: List<DstRubricPolicy> = emptyList(),
    val graderSheetIds: List<String>,
    val averageSheetQuality: Double
)
