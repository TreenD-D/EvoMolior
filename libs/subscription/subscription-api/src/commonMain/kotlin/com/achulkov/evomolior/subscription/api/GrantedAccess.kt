package com.achulkov.evomolior.subscription.api

data class GrantedAccess(
    val id: String,
    val expirationDateMillis: Long?,
    val willRenew: Boolean,
    val isLifetime: Boolean = false,
    val productIdentifier: String,
    val details: Details? = null
) {
    data class Details(
        val title: String,
        val price: Price,
        val durationType: DurationType,
    )

    enum class DurationType {
        MONTHLY,
        WEEKLY,
        YEARLY,
        LIFETIME,
        UNKNOWN
    }
}