package com.achulkov.evomolior.agents.factory

import com.achulkov.evomolior.core.contract.BlockingAgentAbortException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OpenRouterProviderFailurePolicyTest {

    @Test
    fun `retry budget allows transient 502s to recover`() = runTest {
        var attempts = 0

        val result = runWithOpenRouterProviderRetry(
            agentLabel = "TestAgent",
            maxRetries = 2,
            retryDelayMs = 1L
        ) {
            attempts++
            if (attempts < 3) {
                error("OpenRouter API error: Provider returned error (code: 502)")
            }
            "ok"
        }

        assertEquals("ok", result)
        assertEquals(3, attempts)
    }

    @Test
    fun `retry budget escalates repeated 502s into a blocking abort`() = runTest {
        var attempts = 0

        val ex = assertFailsWith<BlockingAgentAbortException> {
            runWithOpenRouterProviderRetry(
                agentLabel = "TestAgent",
                maxRetries = 1,
                retryDelayMs = 1L
            ) {
                attempts++
                error("OpenRouter API error: Provider returned error (code: 502)")
            }
        }

        assertEquals(2, attempts)
        assertEquals(true, ex.message?.contains("TestAgent aborted") == true)
    }

    @Test
    fun `alibaba shaped 502 is treated as retryable provider failure`() = runTest {
        var attempts = 0

        val result = runWithOpenRouterProviderRetry(
            agentLabel = "TestAgent",
            maxRetries = 2,
            retryDelayMs = 1L
        ) {
            attempts++
            if (attempts < 2) {
                error(
                    "OpenRouter API error: Upstream error from Alibaba: <400> " +
                        "InternalError.Algo.InvalidParameter: The \"function.arguments\" parameter " +
                        "of the code model must be in JSON format. (code: 502)"
                )
            }
            "ok"
        }

        assertEquals("ok", result)
        assertEquals(2, attempts)
    }
}
