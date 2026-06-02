package com.achulkov.evokammer.competition.grader

import com.achulkov.evokammer.competition.scoring.Scorer
import com.achulkov.evokammer.core.model.DimensionScore
import com.achulkov.evokammer.core.model.GradingResult
import com.achulkov.evokammer.core.model.RoundScore
import com.achulkov.evokammer.core.model.ScoringDimension
import io.github.aakira.napier.Napier

/**
 * Represents the Grader Group — a separate civilisation that evaluates all competing groups.
 *
 * The Grader Group has its own KB, KG, tools, and budget but NO access to competing groups'
 * substrates. It receives only the exported "canon snapshot" (read-only) of each group's KB
 * at the end of each evaluation round.
 *
 * Grader agents produce [DimensionScore] lists which the [Scorer] aggregates.
 *
 * @param dimensions The scoring dimensions for this run (from Council output).
 */
class GraderGroup(dimensions: List<ScoringDimension>) {

    private val scorer = Scorer(dimensions)

    /** All raw submissions from Grader agents, keyed by "${groupId}::${roundIndex}". */
    private val submissions = mutableMapOf<String, MutableList<DimensionScore>>()

    /**
     * Records a batch of [DimensionScore]s submitted by a Grader agent for [groupId] in [roundIndex].
     */
    @Synchronized
    fun submit(groupId: String, roundIndex: Int, scores: List<DimensionScore>) {
        val key = "${groupId}::${roundIndex}"
        submissions.getOrPut(key) { mutableListOf() }.addAll(scores)
        Napier.d("GraderGroup: received ${scores.size} dimension scores for $groupId round=$roundIndex")
    }

    /**
     * Computes final [RoundScore]s for all groups in [roundIndex].
     *
     * @param groupIds All competing group IDs (NOT including the grader group itself).
     */
    @Synchronized
    fun computeRoundScores(groupIds: List<String>, roundIndex: Int): List<RoundScore> {
        val raw = groupIds.map { groupId ->
            val key = "${groupId}::${roundIndex}"
            val subs = submissions[key] ?: emptyList()
            if (subs.isEmpty()) {
                Napier.w("GraderGroup: no submissions for $groupId round=$roundIndex — defaulting to 0")
            }
            scorer.compute(groupId, roundIndex, subs)
        }
        return scorer.assignRanks(raw)
    }

    /**
     * Computes a [GradingResult] for [roundIndex].
     */
    @Synchronized
    fun computeGradingResult(groupIds: List<String>, roundIndex: Int): GradingResult {
        val scores = computeRoundScores(groupIds, roundIndex)
        val winner = scores.minByOrNull { it.rank }?.groupId ?: groupIds.first()
        return GradingResult(roundIndex, scores, winner)
    }

    /** Clears submissions for a completed round (memory management). */
    @Synchronized
    fun clearRound(roundIndex: Int) {
        val keysToRemove = submissions.keys.filter { it.endsWith("::$roundIndex") }
        keysToRemove.forEach { submissions.remove(it) }
    }
}
