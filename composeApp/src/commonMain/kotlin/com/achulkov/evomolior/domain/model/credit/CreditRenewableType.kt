package com.achulkov.evomolior.domain.model.credit

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

sealed class CreditRenewableType(open val duration: Duration) {
    object None : CreditRenewableType(duration = 0.days)
    object Daily : CreditRenewableType(duration = 1.days)
    object Weekly : CreditRenewableType(duration = 7.days)
    object Monthly : CreditRenewableType(duration = 30.days)
    object Yearly : CreditRenewableType(duration = 365.days)
    data class Custom(override val duration: Duration) : CreditRenewableType(duration = duration)
}
