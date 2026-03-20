package com.achulkov.evomolior.subscription.adapty

import com.achulkov.evomolior.subscription.api.NoOpSubscriptionProvider
import com.achulkov.evomolior.subscription.api.NoOpSubscriptionProviderUi
import com.achulkov.evomolior.subscription.api.SubscriptionProvider
import com.achulkov.evomolior.subscription.api.SubscriptionProviderFactory
import com.achulkov.evomolior.subscription.api.SubscriptionProviderUi

internal actual val subscriptionProviderFactory: SubscriptionProviderFactory
    get() = object : SubscriptionProviderFactory {
        override fun createProvider(): SubscriptionProvider {
            return NoOpSubscriptionProvider
        }

        override fun createProviderUi(): SubscriptionProviderUi {
            return NoOpSubscriptionProviderUi
        }
    }