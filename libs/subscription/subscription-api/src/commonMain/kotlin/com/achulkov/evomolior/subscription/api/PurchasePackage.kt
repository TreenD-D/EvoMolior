package com.achulkov.evomolior.subscription.api

import kotlin.jvm.JvmInline


@JvmInline
value class PurchasePackageId(val value: String)
data class PurchasePackage(
    val id: PurchasePackageId,
    val price: Price,
    val title: String,
    val description: String = "",
)