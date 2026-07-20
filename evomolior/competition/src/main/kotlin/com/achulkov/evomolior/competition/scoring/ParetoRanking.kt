package com.achulkov.evomolior.competition.scoring

import com.achulkov.evomolior.core.model.ParetoFront
import com.achulkov.evomolior.core.model.DimensionScore
import com.achulkov.evomolior.core.model.RoundScore
import com.achulkov.evomolior.core.model.ScoringDimension

/**
 * Computes non-dominated Pareto fronts for round scores without collapsing dimensions.
 *
 * @param dimensions Enabled dimensions for the current run.
 */
class ParetoRanking(private val dimensions: List<ScoringDimension>) {
    /**
     * Assigns Pareto front metadata and deterministic compatibility ranks.
     *
     * @param scores Round scores to rank.
     * @return Ranked scores ordered by front and scalar tie-break.
     */
    fun rank(scores: List<RoundScore>): ParetoRankingResult {
        val dominatedBy = scores.associate { candidate ->
            candidate.groupId to scores
                .filter { other -> other.groupId != candidate.groupId && dominates(other, candidate) }
                .map { it.groupId }
                .sorted()
        }
        val remaining = scores.map { it.groupId }.toMutableSet()
        val byGroupId = scores.associateBy { it.groupId }
        val fronts = mutableListOf<ParetoFront>()
        val frontByGroupId = mutableMapOf<String, Int>()
        var frontIndex = 1

        while (remaining.isNotEmpty()) {
            val frontGroupIds = remaining
                .filter { groupId -> dominatedBy.getValue(groupId).none { it in remaining } }
                .sortedWith(compareByDescending<String> { byGroupId.getValue(it).weightedTotal }.thenBy { it })
            val selected = frontGroupIds.ifEmpty {
                // Cycles should not occur with Pareto dominance, but this keeps corrupted input finite.
                listOf(remaining.minWith(compareByDescending<String> { byGroupId.getValue(it).weightedTotal }.thenBy { it }))
            }
            selected.forEach { groupId ->
                frontByGroupId[groupId] = frontIndex
                remaining.remove(groupId)
            }
            fronts += ParetoFront(frontIndex, selected)
            frontIndex += 1
        }

        val ranked = scores
            .sortedWith(compareBy<RoundScore> { frontByGroupId.getValue(it.groupId) }
                .thenByDescending { it.weightedTotal }
                .thenBy { it.groupId })
            .mapIndexed { index, score ->
                score.copy(
                    rank = index + 1,
                    paretoFront = frontByGroupId.getValue(score.groupId),
                    dominatedByGroupIds = dominatedBy.getValue(score.groupId)
                )
            }

        return ParetoRankingResult(scores = ranked, fronts = fronts)
    }

    private fun dominates(left: RoundScore, right: RoundScore): Boolean {
        val leftScores = left.dimensionScores.associateBy { it.dimensionId }
        val rightScores = right.dimensionScores.associateBy { it.dimensionId }

        val noWorse = dimensions.all { dimension ->
            val leftValue = normalizedScore(rawScoreFor(leftScores, dimension), dimension)
            val rightValue = normalizedScore(rawScoreFor(rightScores, dimension), dimension)
            leftValue >= rightValue
        }
        val betterSomewhere = dimensions.any { dimension ->
            val leftValue = normalizedScore(rawScoreFor(leftScores, dimension), dimension)
            val rightValue = normalizedScore(rawScoreFor(rightScores, dimension), dimension)
            leftValue > rightValue
        }
        return noWorse && betterSomewhere
    }

    private fun rawScoreFor(scores: Map<String, DimensionScore>, dimension: ScoringDimension): Double =
        scores[dimension.id]?.rawScore ?: dimension.missingRawScore()

    private fun normalizedScore(rawScore: Double, dimension: ScoringDimension): Double {
        val bounded = rawScore.coerceIn(0.0, dimension.maxScore) / dimension.maxScore
        return if (dimension.higherIsBetter) bounded else 1.0 - bounded
    }

    private fun ScoringDimension.missingRawScore(): Double = if (higherIsBetter) 0.0 else maxScore
}

/**
 * Result of Pareto ranking.
 *
 * @param scores Scores annotated with front/rank metadata.
 * @param fronts Ordered fronts.
 */
data class ParetoRankingResult(
    val scores: List<RoundScore>,
    val fronts: List<ParetoFront>
)
