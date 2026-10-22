package com.achulkov.evomolior.core.contract

import kotlinx.coroutines.CancellationException

/**
 * Explicit lifecycle intent to stop an agent after its bounded work has been preserved.
 *
 * Only controlled lifecycle stops should use this marker; ordinary cancellation and execution
 * timeouts must retain their original exception types.
 *
 * @param reason Stable lifecycle reason recorded in termination telemetry.
 */
class ExpectedAgentStopCancellationException(
    val reason: String
) : CancellationException(reason)
