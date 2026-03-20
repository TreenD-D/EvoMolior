package com.achulkov.evomolior.subscription.revenuecat

import com.achulkov.evomolior.subscription.api.SubscriptionProvider
import com.achulkov.evomolior.subscription.api.SubscriptionProviderFactory
import com.achulkov.evomolior.subscription.api.SubscriptionProviderUi

internal actual val subscriptionProviderFactory: SubscriptionProviderFactory
    get() =  object : SubscriptionProviderFactory {
        override fun createProvider(): SubscriptionProvider {
            return RevenueCatSubscriptionProvider()
        }

        override fun createProviderUi(): SubscriptionProviderUi {
            return RevenueCatSubscriptionProviderUi()
        }
    }