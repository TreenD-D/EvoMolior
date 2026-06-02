package com.achulkov.evokammer.agents.factory

import ai.koog.agents.features.eventHandler.feature.EventHandlerConfig
import com.achulkov.evokammer.core.contract.BlockingAgentAbortException
import io.github.aakira.napier.Napier

/**
 * Installs a fail-fast policy for blocking Koog tool/runtime errors.
 *
 * Tool validation or tool-call failures are not recoverable in this product:
 * we abort the agent immediately, preserve the remaining group budget, and let
 * the lifecycle manager surface the exit as a kill rather than a normal finish.
 *
 * @param agentLabel Human-readable agent label used in logs and exception text.
 */
fun EventHandlerConfig.installBlockingFailurePolicy(agentLabel: String) {
    onToolValidationFailed { ctx ->
        abortBlockingAgent(agentLabel, "tool validation failed: ${ctx.message}")
    }
    onToolCallFailed { ctx ->
        abortBlockingAgent(agentLabel, "tool call failed: ${ctx.message}")
    }
}

private fun abortBlockingAgent(agentLabel: String, reason: String): Nothing {
    Napier.w("[$agentLabel] aborting agent due to blocking runtime error: $reason")
    throw BlockingAgentAbortException("[$agentLabel] $reason")
}
