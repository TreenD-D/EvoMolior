package com.achulkov.evomolior.competition.scoring

import com.achulkov.evomolior.core.model.RoundScore
import com.achulkov.evomolior.core.model.ScoringResolution
import kotlin.math.abs

private const val SCORE_EQUALITY_TOLERANCE = 1e-9

/**
 * Resolves a substantive round winner without treating deterministic compatibility ordering as
 * evidence when every aggregate score is equal.
 */
object ScoringRoundResolver {
    /**
     * Resolves the outcome represented by ranked [scores].
     *
     * Pareto fronts and compatibility ranks remain available to legacy consumers, but an
     * all-equal or all-zero round deliberately has no winner.
     *
     * @param scores Ranked round scores.
     * @return Winner and explicit resolution state.
     */
    fun resolve(scores: List<RoundScore>): ScoringRoundOutcome {
        if (scores.isEmpty() || scores.all { it.weightedTotal.isEffectivelyZero() }) {
            return ScoringRoundOutcome(
                winnerId = null,
                resolution = ScoringResolution.ALL_ZERO_DEGENERACY
            )
        }

        val firstTotal = scores.first().weightedTotal
        if (scores.size > 1 && scores.all { it.weightedTotal.isEffectivelyEqualTo(firstTotal) }) {
            return ScoringRoundOutcome(
                winnerId = null,
                resolution = ScoringResolution.ALL_EQUAL_TIE
            )
        }

        return ScoringRoundOutcome(
            winnerId = scores.minByOrNull { it.rank }?.groupId,
            resolution = ScoringResolution.RESOLVED
        )
    }

    private fun Double.isEffectivelyZero(): Boolean = abs(this) <= SCORE_EQUALITY_TOLERANCE

    private fun Double.isEffectivelyEqualTo(other: Double): Boolean =
        abs(this - other) <= SCORE_EQUALITY_TOLERANCE
}

/**
 * Winner resolution produced from a ranked scoring round.
 *
 * @param winnerId Substantive winner, or null for an unresolved round.
 * @param resolution Explicit reason the round was or was not resolved.
 */
data class ScoringRoundOutcome(
    val winnerId: String?,
    val resolution: ScoringResolution
)
