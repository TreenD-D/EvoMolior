package com.achulkov.evomolior.presentation.screens.subscriptions

import com.achulkov.evomolior.domain.model.Subscription

data class SubscriptionsUiState(
    val isLoading: Boolean = false,
    val showUpgradePremiumBanner: Boolean = true,
    val currentPlan: Subscription? = null
)

sealed class SubscriptionsUiEvent {

}