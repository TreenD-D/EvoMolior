package com.achulkov.evokammer.competition.scoring

import com.achulkov.evokammer.core.model.DimensionScore
import com.achulkov.evokammer.core.model.RoundScore
import com.achulkov.evokammer.core.model.ScoringDimension
import io.github.aakira.napier.Napier

/**
 * Aggregates [DimensionScore] lists from Grader agents into final [RoundScore]s.
 *
 * The Scorer is called by the [com.achulkov.evokammer.competition.coordinator.GroupCoordinator]
 * after all Grader agents have submitted their evaluations for a round.
 */
class Scorer(private val dimensions: List<ScoringDimension>) {

    init {
        require(dimensions.isNotEmpty()) { "At least one scoring dimension is required" }
    }

    private val totalWeight = dimensions.sumOf { it.weight }

    /**
     * Computes a [RoundScore] for [groupId] from raw dimension scores produced by Graders.
     *
     * Multiple Grader submissions for the same dimension are averaged.
     *
     * @param groupId The group being scored.
     * @param roundIndex 0-based round index.
     * @param submissions All [DimensionScore] submissions from Grader agents.
     * @return A [RoundScore] with a weighted total.
     */
    fun compute(
        groupId: String,
        roundIndex: Int,
        submissions: List<DimensionScore>
    ): RoundScore {
        val byDimension = submissions.groupBy { it.dimensionId }

        val aggregated = dimensions.map { dim ->
            val scores = byDimension[dim.id]
            val avg = if (!scores.isNullOrEmpty()) scores.map { it.rawScore }.average() else 0.0
            val capped = avg.coerceIn(0.0, dim.maxScore)
            Napier.d("Scorer: dim=${dim.id} avg=$avg capped=$capped group=$groupId round=$roundIndex")
            DimensionScore(
                dimensionId = dim.id,
                rawScore = capped,
                justification = scores?.firstOrNull()?.justification ?: "No grader submission"
            )
        }

        val weightedTotal = aggregated.sumOf { ds ->
            val dim = dimensions.first { it.id == ds.dimensionId }
            (ds.rawScore / dim.maxScore) * (dim.weight / totalWeight) * 100.0
        }

        return RoundScore(
            groupId = groupId,
            roundIndex = roundIndex,
            dimensionScores = aggregated,
            weightedTotal = weightedTotal.coerceIn(0.0, 100.0)
        )
    }

    /**
     * Assigns ranks to a list of [RoundScore]s for the same round.
     * Rank 1 = highest weighted total.
     */
    fun assignRanks(scores: List<RoundScore>): List<RoundScore> {
        val sorted = scores.sortedByDescending { it.weightedTotal }
        return sorted.mapIndexed { index, score -> score.copy(rank = index + 1) }
    }
}
