package com.achulkov.evomolior.cli

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CliRunShutdownTest {
    @Test
    fun `normal terminal return removes hook without aborting`() = runBlocking {
        var removed = 0
        assertEquals("complete", withCliRunShutdown(
            abort = { error("Not an active failure") },
            registerHook = {},
            removeHook = { removed++ },
        ) { "complete" })
        assertEquals(1, removed)
    }

    @Test
    fun `cancellation waits for startup unwind before aborting`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var startupSettled = false
        var aborted = false
        val job = async {
            withCliRunShutdown(
                abort = { assertTrue(startupSettled); aborted = true },
                registerHook = {}, removeHook = {},
            ) {
                try { started.complete(Unit); awaitCancellation() }
                finally { withContext(NonCancellable) { delay(20); startupSettled = true } }
            }
        }
        started.await()
        job.cancelAndJoin()
        assertTrue(aborted)
    }

    @Test
    fun `shutdown hook cancels owned stage and waits for drain`() = runBlocking {
        val hookReady = CompletableDeferred<Thread>()
        var aborted = false
        val stage = async {
            withCliRunShutdown(
                abort = { delay(20); aborted = true },
                registerHook = { hookReady.complete(it) }, removeHook = {},
            ) { awaitCancellation() }
        }
        val hook = hookReady.await()
        hook.start()
        assertFailsWith<CancellationException> { stage.await() }
        hook.join(5_000)
        assertTrue(aborted)
        assertTrue(!hook.isAlive)
    }
}
