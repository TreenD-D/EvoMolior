package com.achulkov.evomolior.presentation.screens.paywall

import com.achulkov.evomolior.data.repository.CreditRepository
import com.achulkov.evomolior.data.repository.SubscriptionRepository
import com.achulkov.evomolior.data.repository.UserRepository
import com.achulkov.evomolior.presentation.components.premium.PremiumFeatureFactory
import com.achulkov.evomolior.root.AppGlobalUiState
import com.achulkov.evomolior.subscription.api.PurchaseError
import com.achulkov.evomolior.subscription.api.PurchaseEventsListener
import com.achulkov.evomolior.subscription.api.SubscriptionProviderUser
import com.achulkov.evomolior.util.Constants.CREDIT_PACK_PRODUCT_ID_PREFIX
import com.achulkov.evomolior.util.UiMessage
import com.achulkov.evomolior.util.UiStateHolder
import com.achulkov.evomolior.util.logging.AppLogger
import com.achulkov.evomolior.util.uiStateHolderScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch


class PaywallUiStateHolder(
    private val subscriptionRepository: SubscriptionRepository,
    private val creditRepository: CreditRepository,
    private val userRepository: UserRepository
) :
    UiStateHolder() {
    private val _uiState =
        MutableStateFlow(PaywallUiState(currentPlacementId = subscriptionRepository.currentPlacementId))
    val uiState: StateFlow<PaywallUiState> = _uiState.asStateFlow()

    val remotePaywallPurchaseEventsListener: PurchaseEventsListener =
        object : PurchaseEventsListener {
            override fun onDismiss() {
                _uiState.update { it.copy(isDismissRequired = true) }
            }

            override fun onLoadingStateChanged(isLoading: Boolean) {
                _uiState.update { it.copy(isLoading = isLoading) }
            }

            override fun onUnknownError(error: Exception) {
                AppLogger.e("Unknown error occurred on paywall", error)
                _uiState.update {
                    it.copy(
                        errorMessage = UiMessage.Message(error.message),
                        isLoading = false
                    )
                }
            }

            override fun onPurchaseSuccess(
                info: SubscriptionProviderUser,
                productIds: List<String>
            ) {
                successfulPurchase(info, productIds)
            }

            override fun onRestoreSuccess(info: SubscriptionProviderUser) {
                successfulRestore(info)
            }

            override fun onPurchaseFailure(error: PurchaseError) {
                failedPurchase(error = Throwable(error.message))
            }

            override fun onRestoreFailure(error: PurchaseError) {
                failedRestore(error = Throwable(error.message))
            }
        }


    fun onMessageShown() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun onSuccessfulPurchaseHandled() = uiStateHolderScope.launch {
        _uiState.update { it.copy(successfulSubscription = null) }
    }

    fun onSignInActionHandled() = uiStateHolderScope.launch {
        _uiState.update { it.copy(signInActionRequired = false) }
    }

    fun onPaywallDismissActionHandled() {
        subscriptionRepository.onPaywallDismissed()
        _uiState.update { it.copy(currentPlacementId = null, isDismissRequired = false) }
    }

    //@param refreshPackages is only needed for custom Paywall screens, not for remote paywall
    fun updatePlacementIdIfNecessary(refreshPackages: Boolean) = uiStateHolderScope.launch {
        _uiState.update { it.copy(currentPlacementId = subscriptionRepository.currentPlacementId, isLoading = false) }
        if (refreshPackages) getPackages()
    }


    private fun getPackages() = uiStateHolderScope.launch {
        _uiState.update { it.copy(isLoading = true) }
        subscriptionRepository.getPackageList(placementId = subscriptionRepository.currentPlacementId)
            .onSuccess { packages ->
                val packagesWithFormattedTexts = packages.map { purchasePackage ->
                    purchasePackage.copy(
                        title = purchasePackage.title.substringBefore("("),
                        description = "${purchasePackage.description} just for ${purchasePackage.price.localizedString}",
                    )
                }
                val defaultSelectedPackageIndex = 0
                val selectedPackage =
                    packagesWithFormattedTexts.getOrNull(defaultSelectedPackageIndex)
                // You can change features dynamically based on selected package
                val features = PremiumFeatureFactory.defaultPremiumFeatures.map { it.text }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        features = features,
                        packages = packagesWithFormattedTexts,
                        selectedPackage = selectedPackage,
                        buyButtonEnabled = selectedPackage != null,
                    )
                }
            }

            .onFailure { error ->
                AppLogger.e("Error getting packages: $error")
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = UiMessage.Message(error.message),
                        buyButtonEnabled = false
                    )
                }
            }
    }

    fun onUiEvent(event: PaywallUiEvent) {
        when (event) {
            PaywallUiEvent.OnClickBuy -> {
                buyPackage()
            }

            PaywallUiEvent.OnClickRestore -> {
                restorePayment()
            }

            is PaywallUiEvent.OnSelectPackage -> {
                _uiState.update {
                    it.copy(
                        selectedPackage = event.purchasePackage,
                        buyButtonEnabled = true,
                    )
                }
            }
        }
    }

    private fun restorePayment() = uiStateHolderScope.launch {
        if (userCanDoPaymentAction().not()) {
            AppGlobalUiState.showUiMessage(UiMessage.Message("You need to sign in first"))
            _uiState.update { it.copy(signInActionRequired = true) }
            return@launch
        }

        _uiState.update { it.copy(isLoading = true) }
        subscriptionRepository.restorePurchase()
            .onSuccess { purchaserInfo -> successfulRestore(purchaserInfo) }
            .onFailure { error -> failedRestore(error) }
    }

    private fun buyPackage() = uiStateHolderScope.launch {
        if (userCanDoPaymentAction().not()) {
            AppGlobalUiState.showUiMessage(UiMessage.Message("You need to sign in first"))
            _uiState.update { it.copy(signInActionRequired = true) }
            return@launch
        }
        val selectedPackage = uiState.value.selectedPackage
        if (selectedPackage != null) {
            _uiState.update { it.copy(buyButtonEnabled = false) }
            subscriptionRepository.purchase(selectedPackage.id)
                .onSuccess { purchaserInfo ->
                    successfulPurchase(
                        subscriptionProviderUser = purchaserInfo,
                        productIds = listOf(selectedPackage.id.value)
                    )
                }
                .onFailure { error -> failedPurchase(error) }
        }
    }

    private fun successfulPurchase(
        subscriptionProviderUser: SubscriptionProviderUser,
        productIds: List<String>
    ) = uiStateHolderScope.launch {
        AppLogger.d("Successful payment, onPurchaseCompleted")
        _uiState.update { it.copy(isLoading = true) }
        val productId = productIds.firstOrNull()
        val isCreditPack = isCreditPack(productId)

        if (isCreditPack && productId != null) {
            onSuccessfulCreditPack(productId)
            _uiState.update { it.copy(isDismissRequired = true, isLoading = false) }
        }

        val premiumSubscription = with(subscriptionRepository) {
            subscriptionProviderUser.asPremiumSubscription()
        }
        _uiState.update {
            it.copy(
                buyButtonEnabled = true,
                isLoading = false,
                successfulSubscription = premiumSubscription
            )
        }
    }

    private fun successfulRestore(subscriptionProviderUser: SubscriptionProviderUser) =
        uiStateHolderScope.launch {
            AppLogger.d("Successful restoring purchase: $subscriptionProviderUser")
            _uiState.update { it.copy(isLoading = true) }

            val premiumSubscription =
                with(subscriptionRepository) { subscriptionProviderUser.asPremiumSubscription() }
            _uiState.update { state ->
                state.copy(
                    isLoading = false,
                    successfulSubscription = premiumSubscription
                )
            }
        }

    private fun failedPurchase(error: Throwable) = uiStateHolderScope.launch {
        AppLogger.e("There was an error with purchase: $error")
        _uiState.update {
            it.copy(
                buyButtonEnabled = true,
                errorMessage = UiMessage.Message(error.message)
            )
        }
    }

    private fun failedRestore(error: Throwable) = uiStateHolderScope.launch {
        AppLogger.e("Error restoring purchases: $error")
        _uiState.update { state ->
            state.copy(
                errorMessage = UiMessage.Message(error.message),
                isLoading = false
            )
        }
    }


    private fun isCreditPack(productId: String?): Boolean {
        return productId?.startsWith(CREDIT_PACK_PRODUCT_ID_PREFIX) ?: false
    }

    private suspend fun onSuccessfulCreditPack(productId: String) {
        AppLogger.d("Successful credit pack is purchased: $productId")

        val amountPart =
            productId.substringAfter(CREDIT_PACK_PRODUCT_ID_PREFIX, missingDelimiterValue = "")
                .takeWhile { it.isDigit() } // read until a non-digit (e.g. "_v2")
                .toIntOrNull()

        if (amountPart == null) {
            AppLogger.e(
                "Invalid credit pack product id: $productId. " +
                        "Must start with $CREDIT_PACK_PRODUCT_ID_PREFIX and contain a number. Example: credit_pack_50"
            )
        }

        amountPart?.let {
            AppLogger.d("Successful credit is added, amount: $amountPart")
            creditRepository.addCredits(amountPart)
            AppGlobalUiState.showUiMessage(UiMessage.Message("$amountPart credits added to your account successfully."))
        }
    }

    private suspend fun userCanDoPaymentAction(): Boolean {
        //You can for example check if user is authenticated here
        val currentUser = userRepository.currentUser.first().getOrNull()
        return currentUser != null
    }

}

