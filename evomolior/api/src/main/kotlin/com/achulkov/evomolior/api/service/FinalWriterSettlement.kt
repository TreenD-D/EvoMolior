package com.achulkov.evomolior.api.service

import kotlinx.coroutines.withTimeoutOrNull

/** Whether a staged writer finished naturally or needed an explicitly requested bounded stop. */
internal enum class FinalWriterStopDisposition { NATURAL_COMPLETION, CANDIDATE_STAGED_STOP }

/**
 * Gives a staged writer its remaining original allowance for source corrections and finalization.
 * Candidate validation occurs only after this function returns; cleanup failure retains ownership.
 *
 * @param remainingAttemptMs Remaining original writer allowance, not a new timeout allocation.
 * @param awaitSettlement Cancellable wait for actual execution and lifecycle settlement.
 * @param requestStagedStop Targeted, explicitly expected stop of this writer only.
 * @param drainAfterStop Bounded strict cleanup after cancellation.
 */
internal suspend fun settleFinalWriter(
    remainingAttemptMs: Long,
    awaitSettlement: suspend () -> Unit,
    requestStagedStop: suspend () -> Unit,
    drainAfterStop: suspend () -> Unit,
): FinalWriterStopDisposition {
    val completed = if (remainingAttemptMs > 0) {
        withTimeoutOrNull(remainingAttemptMs) { awaitSettlement(); true } ?: false
    } else false
    if (completed) return FinalWriterStopDisposition.NATURAL_COMPLETION
    requestStagedStop()
    drainAfterStop()
    return FinalWriterStopDisposition.CANDIDATE_STAGED_STOP
}
