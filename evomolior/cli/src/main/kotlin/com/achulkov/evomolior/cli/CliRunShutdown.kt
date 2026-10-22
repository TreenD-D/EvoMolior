package com.achulkov.evomolior.cli

import io.github.aakira.napier.Napier
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Owns stage startup and waiting across coroutine cancellation and JVM shutdown.
 *
 * @param abort Abort/drain the stage if it still owns a live engine run.
 * @param registerHook Shutdown registration, injectable for provider-free tests.
 * @param removeHook Shutdown deregistration, injectable for provider-free tests.
 * @param block Stage startup and terminal wait, in the owned cancellable scope.
 */
internal suspend fun <T> withCliRunShutdown(
    abort: suspend () -> Unit,
    registerHook: (Thread) -> Unit = Runtime.getRuntime()::addShutdownHook,
    removeHook: (Thread) -> Unit = { Runtime.getRuntime().removeShutdownHook(it) },
    block: suspend () -> T,
): T = coroutineScope {
    val settled = CountDownLatch(1)
    val stageScope = this
    val hook = Thread({
        stageScope.cancel(CancellationException("CLI process shutdown requested"))
        if (!settled.await(55, TimeUnit.SECONDS)) {
            Napier.e("CLI shutdown cleanup did not settle within 55 seconds; no completion is claimed")
        }
    }, "evomolior-cli-run-shutdown")
    registerHook(hook)
    var failure: Throwable? = null
    try {
        block()
    } catch (error: Throwable) {
        failure = error
        throw error
    } finally {
        try {
            if (failure != null || !isActive) withContext(NonCancellable) { abort() }
        } catch (cleanup: Throwable) {
            val original = failure
            if (original == null) throw cleanup
            original.addSuppressed(cleanup)
        } finally {
            settled.countDown()
            try {
                removeHook(hook)
            } catch (_: IllegalStateException) {
                // The JVM owns the hook once shutdown starts; it cannot then be deregistered.
            }
        }
    }
}
