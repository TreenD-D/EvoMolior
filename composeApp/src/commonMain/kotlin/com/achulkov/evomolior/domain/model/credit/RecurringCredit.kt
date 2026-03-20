package com.achulkov.evomolior.domain.model.credit

import kotlin.time.Duration

data class RecurringCredit(
    val id: String,
    val total: Int, // total credits for this cycle
    val remaining: Int,
    val renewableDuration: Duration,
    val nextRenewalTime: Long? = null // epoch millis
) {
    val used: Int
        get() = (total - remaining).coerceAtLeast(0)

    val progress: Float
        get() = if (total > 0) used.toFloat() / total else 0f
}
