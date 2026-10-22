package com.achulkov.evomolior.core.repair

import java.io.IOException

/**
 * Recognises local storage exhaustion anywhere in an exception's cause chain.
 *
 * A full temp or data volume makes every subsequent provider attempt fail locally before dispatch
 * (Codex cannot even create its prompt file). In the October 2026 mixed run, 138 of 422 ledger
 * attempts came after the first "No space left on device", spread over Critic loops, methodology
 * retries and repair cycles. Retrying such failures cannot succeed, so loops stop on this signal.
 */
object StorageExhaustion {
    private val markers = listOf("No space left on device", "ENOSPC", "Disk quota exceeded")

    /**
     * True when [error] or any of its causes is an I/O failure caused by a full volume.
     *
     * @param error Failure to inspect.
     */
    fun isCause(error: Throwable): Boolean =
        generateSequence(error) { current -> current.cause?.takeIf { it !== current } }
            .take(MAX_CAUSE_DEPTH)
            .any { current ->
                current is IOException && markers.any { marker -> current.message.orEmpty().contains(marker) }
            }

    private const val MAX_CAUSE_DEPTH = 16
}
