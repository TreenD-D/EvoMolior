package com.achulkov.evomolior.subscription.api

data class PurchaseError(
    val message: String,
    val code: String? = null,
)
