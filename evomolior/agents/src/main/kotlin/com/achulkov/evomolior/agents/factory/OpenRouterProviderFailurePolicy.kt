package com.achulkov.evomolior.agents.factory

import com.achulkov.evomolior.core.contract.BlockingAgentAbortException
import io.github.aakira.napier.Napier
import kotlinx.coroutines.delay

private const val OPENROUTER_PROVIDER_502_NEEDLE = "Provider returned error (code: 502)"
private const val OPENROUTER_GENERIC_502_NEEDLE = "(code: 502)"
private const val OPENROUTER_PROVIDER_PARSE_NEEDLE = "JsonLiteral is not a JsonObject"
private const val OPENROUTER_PROVIDER_TOOL_PARSE_NEEDLE = "toolCallPartsOrNull"
private const val OPENROUTER_ALIBABA_INVALID_PARAMETER_NEEDLE = "InternalError.Algo.InvalidParameter"
private const val DEFAULT_PROVIDER_RETRY_BUDGET = 2
private const val DEFAULT_PROVIDER_RETRY_DELAY_MS = 500L

/**
 * Runs a suspending agent call with a small retry budget for transient OpenRouter 502s.
 *
 * After the retry budget is exhausted, the failure is escalated as a
 * [BlockingAgentAbortException] so the lifecycle can stop the stuck agent cleanly instead of
 * letting the round linger until a timeout.
 *
 * @param agentLabel Human-readable label used in logs and exception messages.
 * @param maxRetries How many retries to permit after the initial failure.
 * @param retryDelayMs Base delay between retries.
 * @param block The suspending call to execute.
 * @return The block result, if it succeeds within the retry budget.
 */
suspend fun <T> runWithOpenRouterProviderRetry(
    agentLabel: String,
    maxRetries: Int = DEFAULT_PROVIDER_RETRY_BUDGET,
    retryDelayMs: Long = DEFAULT_PROVIDER_RETRY_DELAY_MS,
    block: suspend () -> T
): T {
    var attempt = 0

    while (true) {
        try {
            return block()
        } catch (e: Exception) {
            if (!isOpenRouterProviderFailure(e)) {
                throw e
            }

            if (attempt >= maxRetries) {
                throw BlockingAgentAbortException(
                    message = "$agentLabel aborted after ${attempt + 1} OpenRouter provider failure(s): ${e.message}",
                    cause = e
                )
            }

            attempt++
            Napier.w(e) {
                "$agentLabel OpenRouter provider failure (attempt $attempt/$maxRetries); retrying"
            }
            delay(retryDelayMs * attempt)
        }
    }
}

internal fun isOpenRouterProviderFailure(throwable: Throwable): Boolean {
    return generateSequence(throwable) { it.cause }
        .any { candidate ->
            candidate.message?.contains(OPENROUTER_PROVIDER_502_NEEDLE) == true ||
                candidate.message?.contains("OpenRouter API error: Provider returned error (code: 502)") == true ||
                candidate.message?.contains(OPENROUTER_GENERIC_502_NEEDLE) == true ||
                candidate.message?.contains(OPENROUTER_ALIBABA_INVALID_PARAMETER_NEEDLE) == true ||
                candidate.message?.contains(OPENROUTER_PROVIDER_PARSE_NEEDLE) == true ||
                candidate.message?.contains(OPENROUTER_PROVIDER_TOOL_PARSE_NEEDLE) == true
        }
}
