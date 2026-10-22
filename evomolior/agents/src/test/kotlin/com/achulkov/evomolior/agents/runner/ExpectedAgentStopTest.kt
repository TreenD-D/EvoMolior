package com.achulkov.evomolior.agents.runner

import com.achulkov.evomolior.core.contract.ExpectedAgentStopCancellationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExpectedAgentStopTest {
    @Test
    fun `explicit lifecycle stop is recognized directly and through framework wrappers`() {
        val stop = ExpectedAgentStopCancellationException("candidate_staged")
        assertEquals("candidate_staged", expectedAgentStopReason(stop))
        assertEquals("candidate_staged", expectedAgentStopReason(IllegalStateException("agent failed", stop)))
    }

    @Test
    fun `ordinary cancellation and matching failure text remain unexpected`() {
        assertNull(expectedAgentStopReason(CancellationException("candidate_staged")))
        assertNull(expectedAgentStopReason(IllegalStateException("candidate_staged")))
        assertNull(expectedAgentStopReason(IllegalStateException("provider failed")))
    }

    @Test
    fun `timeout remains unexpected`() = runBlocking {
        val timeout = runCatching {
            withTimeout(1) { awaitCancellation() }
        }.exceptionOrNull()!!
        assertNull(expectedAgentStopReason(timeout))
    }

    @Test
    fun `cyclic exception causes terminate without classifying failure as expected`() {
        val first = IllegalStateException("first")
        val second = IllegalStateException("second", first)
        first.initCause(second)
        assertNull(expectedAgentStopReason(first))
    }
}
