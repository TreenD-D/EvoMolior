package com.achulkov.evomolior.competition.scoring

import com.achulkov.evomolior.core.model.DimensionScore
import com.achulkov.evomolior.core.model.RoundScore
import com.achulkov.evomolior.core.model.ScoringDimension
import io.github.aakira.napier.Napier

/**
 * Aggregates [DimensionScore] lists from Grader agents into final [RoundScore]s.
 *
 * The Scorer is called by the [com.achulkov.evomolior.competition.coordinator.GroupCoordinator]
 * after all Grader agents have submitted their evaluations for a round.
 */
class Scorer(private val dimensions: List<ScoringDimension>) {

    init {
        require(dimensions.isNotEmpty()) { "At least one scoring dimension is required" }
    }

    private val totalWeight = dimensions.sumOf { it.weight }
    private val paretoRanking = ParetoRanking(dimensions)

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
            val avg = if (!scores.isNullOrEmpty()) {
                scores.map { it.rawScore }.average()
            } else {
                dim.missingRawScore()
            }
            val capped = avg.coerceIn(0.0, dim.maxScore)
            Napier.d("Scorer: dim=${dim.id} avg=$avg capped=$capped group=$groupId round=$roundIndex")
            DimensionScore(
                dimensionId = dim.id,
                rawScore = capped,
                justification = scores?.firstOrNull()?.justification
                    ?: "Missing evidence: no grader submission supplied a ${dim.id} score, " +
                        "artifact citation, or dimension-specific rationale."
            )
        }

        val weightedTotal = aggregated.sumOf { ds ->
            val dim = dimensions.first { it.id == ds.dimensionId }
            val normalized = ds.rawScore.coerceIn(0.0, dim.maxScore) / dim.maxScore
            val directional = if (dim.higherIsBetter) normalized else 1.0 - normalized
            directional * (dim.weight / totalWeight) * 100.0
        }

        return RoundScore(
            groupId = groupId,
            roundIndex = roundIndex,
            dimensionScores = aggregated,
            weightedTotal = weightedTotal.coerceIn(0.0, 100.0)
        )
    }

    /**
     * Assigns Pareto front metadata to a list of [RoundScore]s for the same round.
     * Rank 1 is retained as a compatibility tie-break within Pareto fronts.
     */
    fun assignRanks(scores: List<RoundScore>): List<RoundScore> {
        return assignRankingResult(scores).scores
    }

    /**
     * Assigns Pareto front metadata and returns both ranked scores and explicit fronts.
     *
     * @param scores Scores to rank.
     * @return Full Pareto ranking result.
     */
    fun assignRankingResult(scores: List<RoundScore>): ParetoRankingResult = paretoRanking.rank(scores)

    private fun ScoringDimension.missingRawScore(): Double = if (higherIsBetter) 0.0 else maxScore
}
