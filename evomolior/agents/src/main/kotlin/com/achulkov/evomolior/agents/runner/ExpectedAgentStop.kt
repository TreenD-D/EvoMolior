package com.achulkov.evomolior.agents.runner

import com.achulkov.evomolior.core.contract.ExpectedAgentStopCancellationException
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Finds explicit lifecycle stop intent through framework exception wrappers.
 *
 * Ordinary cancellation, timeout, or a matching message is not proof of an expected stop.
 *
 * @param error Execution failure, possibly wrapping the lifecycle cancellation.
 * @return The explicitly requested stop reason, or null for an unexpected failure.
 */
fun expectedAgentStopReason(error: Throwable): String? {
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    var current: Throwable? = error
    while (current != null && seen.add(current)) {
        if (current is ExpectedAgentStopCancellationException) return current.reason
        current = current.cause
    }
    return null
}
