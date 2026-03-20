package com.achulkov.evomolior.subscription.adapty

import com.achulkov.evomolior.subscription.api.SubscriptionProviderFactory


val SubscriptionProviderFactory.Companion.Adapty: SubscriptionProviderFactory
    get() = subscriptionProviderFactory

internal expect val subscriptionProviderFactory: SubscriptionProviderFactory