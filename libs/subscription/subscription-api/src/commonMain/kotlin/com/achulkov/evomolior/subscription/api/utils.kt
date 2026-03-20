package com.achulkov.evomolior.subscription.api

import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

suspend inline fun <R> runCatchingSuspend(block: suspend () -> R): Result<R> {
    return try {
        Result.success(block())
    } catch (e: Throwable) {
        coroutineContext.ensureActive()
        Result.failure(e)
    }
}