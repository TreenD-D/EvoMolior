package com.achulkov.evomolior.competition.grader

import com.achulkov.evomolior.competition.scoring.Scorer
import com.achulkov.evomolior.competition.scoring.ScoringRoundResolver
import com.achulkov.evomolior.core.model.DimensionScore
import com.achulkov.evomolior.core.model.GradingResult
import com.achulkov.evomolior.core.model.ParetoFront
import com.achulkov.evomolior.core.model.RoundScore
import com.achulkov.evomolior.core.model.ScoringDimension
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

    private val requiredDimensionIds = dimensions.mapTo(linkedSetOf()) { it.id }
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
        return computeRoundRanking(groupIds, roundIndex).first
    }

    /**
     * Returns the scoring-dimension IDs submitted for [groupId] in [roundIndex].
     *
     * The manager uses this coverage view to distinguish an explicit zero score from a missing
     * grader submission. That distinction is required before grader output can become authoritative.
     */
    @Synchronized
    fun submittedDimensionIds(groupId: String, roundIndex: Int): Set<String> =
        submissions["${groupId}::${roundIndex}"]
            .orEmpty()
            .mapTo(linkedSetOf()) { it.dimensionId }

    private fun computeRoundRanking(groupIds: List<String>, roundIndex: Int): Pair<List<RoundScore>, List<ParetoFront>> {
        val incomplete = groupIds.mapNotNull { groupId ->
            val missing = requiredDimensionIds - submittedDimensionIds(groupId, roundIndex)
            missing.takeIf { it.isNotEmpty() }?.let { groupId to it }
        }.toMap()
        if (incomplete.isNotEmpty()) {
            throw IncompleteGraderCoverageException(roundIndex, incomplete)
        }
        val raw = groupIds.map { groupId ->
            val key = "${groupId}::${roundIndex}"
            val subs = submissions.getValue(key)
            scorer.compute(groupId, roundIndex, subs)
        }
        val ranking = scorer.assignRankingResult(raw)
        return ranking.scores to ranking.fronts
    }

    /**
     * Computes a [GradingResult] for [roundIndex].
     */
    @Synchronized
    fun computeGradingResult(groupIds: List<String>, roundIndex: Int): GradingResult {
        val (scores, paretoFronts) = computeRoundRanking(groupIds, roundIndex)
        val outcome = ScoringRoundResolver.resolve(scores)
        return GradingResult(
            roundIndex = roundIndex,
            scores = scores,
            winnerId = outcome.winnerId,
            resolution = outcome.resolution,
            paretoFronts = paretoFronts
        )
    }

    /** Clears submissions for a completed round (memory management). */
    @Synchronized
    fun clearRound(roundIndex: Int) {
        val keysToRemove = submissions.keys.filter { it.endsWith("::$roundIndex") }
        keysToRemove.forEach { submissions.remove(it) }
    }
}

/**
 * Raised when a grading result is requested without a complete submitted dimension bundle.
 *
 * Explicit zero values are valid submissions. This exception represents absent dimensions only.
 *
 * @param roundIndex Round whose evidence is incomplete.
 * @param missingDimensionIdsByGroup Missing configured dimension IDs keyed by target group.
 */
class IncompleteGraderCoverageException(
    val roundIndex: Int,
    val missingDimensionIdsByGroup: Map<String, Set<String>>
) : IllegalStateException(
    "Incomplete grader coverage for round=$roundIndex: " +
        missingDimensionIdsByGroup.entries.joinToString { (groupId, dimensions) ->
            "$groupId missing=${dimensions.sorted()}"
        }
)
