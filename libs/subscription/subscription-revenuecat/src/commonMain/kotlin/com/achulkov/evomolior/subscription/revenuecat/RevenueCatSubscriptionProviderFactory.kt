package com.achulkov.evomolior.subscription.revenuecat

import com.achulkov.evomolior.subscription.api.SubscriptionProviderFactory


val SubscriptionProviderFactory.Companion.RevenueCat: SubscriptionProviderFactory
    get() = subscriptionProviderFactory

internal expect val subscriptionProviderFactory: SubscriptionProviderFactory