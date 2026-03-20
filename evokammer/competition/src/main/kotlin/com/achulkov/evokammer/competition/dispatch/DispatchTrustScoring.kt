package com.achulkov.evokammer.competition.dispatch
import kotlin.math.max
import kotlin.math.min
/**
 * Maintains trust scores for inter-group dispatch sources.
 *
 * Higher trust means dispatches are more likely to be considered by recipients;
 * lower trust reflects repeated low-quality or adversarial messages.
 */
class DispatchTrustScoring {
    private val scores = mutableMapOf<String, Double>()
    /**
     * Returns the current trust score for [groupId], defaulting to 0.5.
     *
     * @param groupId Source group identifier.
     */
    fun scoreOf(groupId: String): Double = scores[groupId] ?: 0.5
    /**
     * Applies feedback from receiver-side critics for a dispatch source.
     *
     * @param sourceGroupId Source group identifier.
     * @param accepted True when receiver accepted/promoted payload, false when rejected.
     * @param magnitude Optional multiplier for stronger feedback.
     */
    fun applyFeedback(sourceGroupId: String, accepted: Boolean, magnitude: Double = 1.0) {
        val delta = if (accepted) 0.05 else -0.08
        val updated = scoreOf(sourceGroupId) + (delta * magnitude)
        scores[sourceGroupId] = min(1.0, max(0.0, updated))
    }
}