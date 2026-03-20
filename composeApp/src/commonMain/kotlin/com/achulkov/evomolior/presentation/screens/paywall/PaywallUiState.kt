package com.achulkov.evomolior.presentation.screens.paywall

import com.achulkov.evomolior.domain.model.Subscription
import com.achulkov.evomolior.subscription.api.PurchasePackage
import com.achulkov.evomolior.util.UiMessage

data class PaywallUiState(
    val title: String = "Go Premium",
    val buyButtonText: String = "Continue",
    val features: List<String> = emptyList(),
    val isLoading: Boolean = true,
    val isDismissRequired: Boolean = false,
    val errorMessage: UiMessage? = null,
    val selectedPackage: PurchasePackage? = null,
    val buyButtonEnabled: Boolean = false,
    val packages: List<PurchasePackage> = emptyList(),
    val successfulSubscription: Subscription? = null,
    val signInActionRequired: Boolean = false,
    val currentPlacementId: String? = null,
)

sealed class PaywallUiEvent {
    data object OnClickBuy : PaywallUiEvent()
    data object OnClickRestore : PaywallUiEvent()
    data class OnSelectPackage(val purchasePackage: PurchasePackage) : PaywallUiEvent()
}

