package com.achulkov.evomolior.api.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FinalWriterSettlementTest {
    @Test
    fun `writer has original allowance to correct source report before natural completion`() = runTest {
        var candidate = "first DOC"
        val settled = CompletableDeferred<Unit>()
        launch { delay(50); candidate = "corrected DOC"; delay(50); settled.complete(Unit) }
        val disposition = settleFinalWriter(
            200,
            awaitSettlement = { settled.await() },
            requestStagedStop = { error("Natural completion must not stop writer") },
            drainAfterStop = { error("No stop needs draining") },
        )
        assertEquals("corrected DOC", candidate)
        assertEquals(FinalWriterStopDisposition.NATURAL_COMPLETION, disposition)
    }

    @Test
    fun `elapsed original allowance requests explicit stop then drains before validation`() = runTest {
        val calls = mutableListOf<String>()
        val disposition = settleFinalWriter(
            50,
            awaitSettlement = { calls += "await"; awaitCancellation() },
            requestStagedStop = { calls += "stop" },
            drainAfterStop = { calls += "drain" },
        )
        assertEquals(listOf("await", "stop", "drain"), calls)
        assertEquals(FinalWriterStopDisposition.CANDIDATE_STAGED_STOP, disposition)
    }

    @Test
    fun `drain failure does not expose settled candidate`() = runTest {
        var stopped = false
        assertFailsWith<IllegalStateException> {
            settleFinalWriter(
                0,
                awaitSettlement = { error("Allowance elapsed") },
                requestStagedStop = { stopped = true },
                drainAfterStop = { error("Execution still owned") },
            )
        }
        assertTrue(stopped)
    }

    @Test
    fun `caller cancellation propagates without becoming controlled candidate stop`() = runTest {
        val started = CompletableDeferred<Unit>()
        var stopped = false
        val work = launch {
            settleFinalWriter(
                1000,
                awaitSettlement = { started.complete(Unit); awaitCancellation() },
                requestStagedStop = { stopped = true },
                drainAfterStop = {},
            )
        }
        started.await()
        work.cancelAndJoin()
        assertTrue(work.isCancelled)
        assertFalse(stopped)
    }
}
