package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.api.model.RunAutoCompletionPolicy
import kotlinx.serialization.Serializable

/**
 * Exact fail-closed evidence used to decide whether one round advances convergence.
 *
 * @param roundIndex Zero-based evaluated round.
 * @param winnerId Exact winner accepted by both authoritative grader scoring and blind reveal.
 * @param exactGraderAuthorityValid Whether exact grader bundles and snapshots are valid.
 * @param mandatoryTestsPassed Whether deterministic and critical grader tests passed.
 * @param finalOutputValidated Whether the winner's round output satisfies the final-output contract.
 * @param rejectionReasons Machine-readable reasons for a non-accepted round.
 */
@Serializable
data class RunAutoCompletionRoundEvidence(
    val roundIndex: Int,
    val winnerId: String?,
    val exactGraderAuthorityValid: Boolean,
    val mandatoryTestsPassed: Boolean,
    val finalOutputValidated: Boolean,
    val rejectionReasons: Set<String> = emptySet()
) {
    /** True only when every completion predicate is present and accepted. */
    val accepted: Boolean
        get() = winnerId != null &&
            exactGraderAuthorityValid &&
            mandatoryTestsPassed &&
            finalOutputValidated &&
            rejectionReasons.isEmpty()
}

/**
 * Persistable convergence state after evaluating one round.
 *
 * @param candidateWinnerId Winner whose consecutive accepted streak is active.
 * @param acceptedRoundStreak Number of consecutive accepted rounds for [candidateWinnerId].
 * @param complete Whether the configured stability window has been reached.
 */
data class RunAutoCompletionProgress(
    val candidateWinnerId: String?,
    val acceptedRoundStreak: Int,
    val complete: Boolean
)

/** Pure authority transition for consecutive-round auto-completion. */
object RunAutoCompletionAuthority {
    /**
     * Advances or resets convergence from one exact round decision.
     *
     * @param policy Configured stability policy.
     * @param previousWinnerId Previously accepted candidate winner.
     * @param previousStreak Previously accepted consecutive round count.
     * @param evidence Current round evidence.
     * @return New convergence state.
     */
    fun advance(
        policy: RunAutoCompletionPolicy,
        previousWinnerId: String?,
        previousStreak: Int,
        evidence: RunAutoCompletionRoundEvidence
    ): RunAutoCompletionProgress {
        require(previousStreak >= 0) { "previousStreak must be non-negative" }
        if (!evidence.accepted) {
            return RunAutoCompletionProgress(
                candidateWinnerId = null,
                acceptedRoundStreak = 0,
                complete = false
            )
        }
        val winnerId = requireNotNull(evidence.winnerId)
        val streak = if (winnerId == previousWinnerId) previousStreak + 1 else 1
        return RunAutoCompletionProgress(
            candidateWinnerId = winnerId,
            acceptedRoundStreak = streak,
            complete = streak >= policy.consecutiveAcceptedRounds
        )
    }
}

/** Exact terminal round-loop decision used by completion persistence and archives. */
internal data class RunCompletionDecision(
    val completedRoundCount: Int,
    val reason: RunCompletionReason
)

/**
 * Resolves terminal metadata from the last executed round rather than the configured ceiling.
 */
internal fun resolveRunCompletionDecision(
    finalRoundIndex: Int,
    autoCompletionReached: Boolean
): RunCompletionDecision {
    require(finalRoundIndex >= 0) { "finalRoundIndex must identify an executed round" }
    return RunCompletionDecision(
        completedRoundCount = finalRoundIndex + 1,
        reason = if (autoCompletionReached) {
            RunCompletionReason.GRADER_ACCEPTED_STABILITY
        } else {
            RunCompletionReason.ROUND_LIMIT_REACHED
        }
    )
}

/** Durable reason the engine left its round loop. */
@Serializable
enum class RunCompletionReason {
    ROUND_LIMIT_REACHED,
    GRADER_ACCEPTED_STABILITY
}
