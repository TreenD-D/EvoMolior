package com.achulkov.evomolior.subscription.api

interface SubscriptionProviderFactory {
    companion object {}

    fun createProvider(): SubscriptionProvider
    fun createProviderUi(): SubscriptionProviderUi
}