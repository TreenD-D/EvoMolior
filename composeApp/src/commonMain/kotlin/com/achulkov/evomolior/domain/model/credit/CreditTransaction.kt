package com.achulkov.evomolior.domain.model.credit

import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
data class CreditTransaction(
    val id: String,
    val type: Type,
    val amount: Int, // Positive for credits granted, negative for credits used
    val description: String? = null,
    val createdAt: Long = Clock.System.now().toEpochMilliseconds(),
) {
    enum class Type {
        PURCHASE,
        RECURRING,
        DEDUCTION,
        BONUS,
        ADMIN_ADJUSTMENT
    }
}
