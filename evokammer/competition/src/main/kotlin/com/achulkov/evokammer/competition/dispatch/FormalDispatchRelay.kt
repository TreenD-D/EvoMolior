package com.achulkov.evokammer.competition.dispatch
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
    fun enqueue(sourceGroupId: String, message: DispatchMessage, generation: Int) {
        pending += PendingDispatch(
            sourceGroupId = sourceGroupId,
            message = message,
            releaseGeneration = generation + lagGenerations
        )
    }
    /**
     * Releases all due dispatches for [currentGeneration].
     *
     * @param currentGeneration Current run generation.
     * @return Number of released dispatches.
     */
    suspend fun flushDue(currentGeneration: Int): Int {
        val due = pending.filter { it.releaseGeneration <= currentGeneration }
        if (due.isEmpty()) return 0
        pending.removeAll(due.toSet())
        due.forEach { relay.dispatch(it.sourceGroupId, it.message) }
        return due.size
    }
    private data class PendingDispatch(
        val sourceGroupId: String,
        val message: DispatchMessage,
        val releaseGeneration: Int
    )
}