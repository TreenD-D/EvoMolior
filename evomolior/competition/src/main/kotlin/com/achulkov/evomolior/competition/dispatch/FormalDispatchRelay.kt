package com.achulkov.evomolior.competition.dispatch

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
/**
 * Plan-aligned wrapper around [DispatchRelay] that applies generation-based lag.
 *
 * Dispatches are enqueued and released only when `currentGeneration >= enqueuedGeneration + lag`.
 *
 * @param relay Backing ring relay.
 * @param lagGenerations Number of generations to delay outgoing dispatches.
 */
class FormalDispatchRelay(
    private val relay: DispatchRelay,
    private val lagGenerations: Int
) {
    private val pending = mutableListOf<PendingDispatch>()
    private val pendingMutex = Mutex()
    init {
        require(lagGenerations >= 0) { "lagGenerations must be >= 0" }
    }
    /**
     * Enqueues a dispatch to be released later.
     *
     * @param sourceGroupId Source group.
     * @param message Message to relay.
     * @param generation Current generation at enqueue time.
     */
    suspend fun enqueue(sourceGroupId: String, message: DispatchMessage, generation: Int) {
        pendingMutex.withLock {
            pending += PendingDispatch(
                sourceGroupId = sourceGroupId,
                message = message,
                releaseGeneration = generation + lagGenerations
            )
        }
    }
    /**
     * Releases all due dispatches for [currentGeneration].
     *
     * @param currentGeneration Current run generation.
     * @return Number of released dispatches.
     */
    suspend fun flushDue(currentGeneration: Int): Int {
        val due = pendingMutex.withLock {
            pending.filter { it.releaseGeneration <= currentGeneration }.also { released ->
                pending.removeAll(released.toSet())
            }
        }
        if (due.isEmpty()) return 0
        due.forEach { relay.dispatch(it.sourceGroupId, it.message) }
        return due.size
    }
    private data class PendingDispatch(
        val sourceGroupId: String,
        val message: DispatchMessage,
        val releaseGeneration: Int
    )
}
