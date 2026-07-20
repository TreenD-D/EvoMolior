package com.achulkov.evomolior.competition.dispatch

import kotlin.math.max
import kotlin.math.min

/**
 * Maintains trust scores for inter-group dispatch sources.
 *
 * Trust is tracked per receiving group so the same source can be evaluated differently
 * by different neighbours.
 */
class DispatchTrustScoring {
    private val scores = mutableMapOf<String, MutableMap<String, Double>>()

    private companion object {
        const val GLOBAL_SCOPE = "__global__"
    }

    /**
     * Returns the current trust score for [sourceGroupId] in the default scope.
     *
     * @param sourceGroupId Source group identifier.
     */
    fun scoreOf(sourceGroupId: String): Double = scoreOf(sourceGroupId, GLOBAL_SCOPE)

    /**
     * Returns the current trust score for [sourceGroupId] as seen by [targetGroupId].
     *
     * @param sourceGroupId Source group identifier.
     * @param targetGroupId Receiving group identifier.
     */
    fun scoreOf(sourceGroupId: String, targetGroupId: String): Double =
        scores[targetGroupId]?.get(sourceGroupId) ?: 0.5

    /**
     * Applies feedback from receiver-side critics for a dispatch source.
     *
     * @param sourceGroupId Source group identifier.
     * @param accepted True when receiver accepted/promoted payload, false when rejected.
     * @param magnitude Optional multiplier for stronger feedback.
     */
    fun applyFeedback(
        sourceGroupId: String,
        accepted: Boolean,
        magnitude: Double = 1.0
    ) {
        applyFeedback(sourceGroupId, accepted, magnitude, GLOBAL_SCOPE)
    }

    /**
     * Applies feedback from a specific receiving group.
     *
     * @param sourceGroupId Source group identifier.
     * @param accepted True when receiver accepted/promoted payload, false when rejected.
     * @param magnitude Optional multiplier for stronger feedback.
     * @param targetGroupId Receiving group identifier.
     */
    fun applyFeedback(
        sourceGroupId: String,
        accepted: Boolean,
        magnitude: Double,
        targetGroupId: String
    ) {
        val delta = if (accepted) 0.05 else -0.08
        val current = scoreOf(sourceGroupId, targetGroupId)
        val updated = current + (delta * magnitude)
        val scopedScores = scores.getOrPut(targetGroupId) { mutableMapOf() }
        scopedScores[sourceGroupId] = min(1.0, max(0.0, updated))
    }
}
