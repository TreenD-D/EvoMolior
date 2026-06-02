package com.achulkov.evokammer.competition.grader
import com.achulkov.evokammer.core.model.DimensionScore
import com.achulkov.evokammer.core.model.GradingResult
import com.achulkov.evokammer.core.model.ScoringDimension
import io.github.aakira.napier.Napier
/**
 * Coordinates multiple Grader Group instances for a single run.
 *
 * Each grader group is modeled as an isolated [GraderGroup] instance keyed by its group ID.
 * This manager aggregates their [GradingSheet]s through [MetaJudge] and computes round-level
 * scoring outcomes from all registered grader submissions.
 *
 * @param dimensions Scoring dimensions shared by all grader groups for the run.
 * @param metaJudge Meta-judge used to reconcile grading sheets across grader groups.
 */
class GraderGroupManager(
    private val dimensions: List<ScoringDimension>,
    private val metaJudge: MetaJudge = MetaJudge()
) {
    private val graderGroups = mutableMapOf<String, GraderGroup>()
    /**
     * Returns an existing grader group by ID, creating one if absent.
     *
     * @param graderGroupId Grader civilisation identifier.
     * @return Backing [GraderGroup] instance.
     */
    @Synchronized
    fun group(graderGroupId: String): GraderGroup =
        graderGroups.getOrPut(graderGroupId) { GraderGroup(dimensions) }
    /**
     * Records a scorer submission into a specific grader group.
     *
     * @param graderGroupId Grader civilisation ID.
     * @param targetGroupId Competing group being evaluated.
     * @param roundIndex Round index.
     * @param scores Dimension scores produced by grader agents.
     */
    @Synchronized
    fun submitScores(
        graderGroupId: String,
        targetGroupId: String,
        roundIndex: Int,
        scores: List<DimensionScore>
    ) {
        group(graderGroupId).submit(targetGroupId, roundIndex, scores)
    }
    /**
     * Registers a sealed grading sheet with the [MetaJudge].
     *
     * @param sheet Grading sheet to register.
     */
    @Synchronized
    fun registerSheet(sheet: GradingSheet) {
        metaJudge.registerSheet(sheet)
    }
    /**
     * Computes consolidated grading result from all grader groups for a round.
     *
     * @param competingGroupIds IDs of competing (non-grader) groups.
     * @param roundIndex Round index.
     * @return Final [GradingResult] for the round.
     */
    @Synchronized
    fun computeRoundResult(competingGroupIds: List<String>, roundIndex: Int): GradingResult {
        // Ensure a verdict exists (consensus/arbitration side effects + validation).
        val verdict = metaJudge.verdict(roundIndex)
        Napier.d(
            "GraderGroupManager: verdict ready for round=$roundIndex " +
                "sheets=${verdict.graderSheetIds.size}"
        )
        // Aggregate all dimension submissions from all grader groups per competing group.
        val merged = competingGroupIds.associateWith { groupId ->
            graderGroups.values.flatMap { grader ->
                // Reuse GraderGroup pipeline by asking each group for round scores, then
                // collapsing to dimension entries for this group.
                grader.computeRoundScores(listOf(groupId), roundIndex)
                    .flatMap { it.dimensionScores }
            }
        }
        val mergedGrader = GraderGroup(dimensions)
        merged.forEach { (groupId, scores) ->
            mergedGrader.submit(groupId, roundIndex, scores)
        }
        return mergedGrader.computeGradingResult(competingGroupIds, roundIndex)
    }
    /**
     * Clears all round-scoped grader submissions and meta-judge sheets for a round.
     *
     * @param roundIndex Round index to clear.
     */
    @Synchronized
    fun clearRound(roundIndex: Int) {
        graderGroups.values.forEach { it.clearRound(roundIndex) }
        metaJudge.clearRound(roundIndex)
    }
}
