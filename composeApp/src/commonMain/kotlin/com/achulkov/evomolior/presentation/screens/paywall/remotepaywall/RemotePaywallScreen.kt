package com.achulkov.evomolior.presentation.screens.paywall.remotepaywall

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.achulkov.evomolior.designsystem.components.LoadingProgress
import com.achulkov.evomolior.designsystem.components.LoadingProgressMode
import com.achulkov.evomolior.designsystem.components.modals.AppDialog
import com.achulkov.evomolior.designsystem.components.modals.DialogType
import com.achulkov.evomolior.presentation.components.premium.PremiumFeatureFactory
import com.achulkov.evomolior.presentation.components.premium.SuccessfulPurchaseView
import com.achulkov.evomolior.presentation.screens.paywall.PaywallUiStateHolder
import com.achulkov.evomolior.subscription.api.SubscriptionProviderUi
import com.achulkov.evomolior.util.extensions.asFormattedDate
import org.koin.compose.koinInject


@Composable
fun RemotePaywallScreen(
    onDismiss: () -> Unit,
    onSignInRequired: () -> Unit,
    uiStateHolder: PaywallUiStateHolder,
) {

    val subscriptionProviderUi = koinInject<SubscriptionProviderUi>()
    val uiState by uiStateHolder.uiState.collectAsStateWithLifecycle()
    var isPaywallVisible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        uiStateHolder.updatePlacementIdIfNecessary(refreshPackages = false)
        isPaywallVisible = true
    }

    LaunchedEffect(uiState.isDismissRequired) {
        if (uiState.isDismissRequired) {
            onDismiss()
        }
    }

    LaunchedEffect(uiState.signInActionRequired) {
        if (uiState.signInActionRequired) {
            onSignInRequired()
            uiStateHolder.onSignInActionHandled()
        }
    }

    if (uiState.errorMessage?.value.isNullOrEmpty().not()){
        AppDialog(
            type = DialogType.ERROR,
            text = uiState.errorMessage?.value,
            onConfirm = {
                uiStateHolder.onMessageShown()
                onDismiss()
            }
        )
    }

    uiState.successfulSubscription?.let { subscription ->
        SuccessfulPurchaseView(
            modifier = Modifier.fillMaxSize(),
            features = PremiumFeatureFactory.ofSubscription(subscription),
            isRecurring = subscription.willRenew,
            isLifetime = subscription.isLifetime,
            expirationDate = subscription.expirationDateInMillis?.asFormattedDate(),
            onContinue = {
                onDismiss()
                uiStateHolder.onSuccessfulPurchaseHandled()
            }
        )
    }

    if (isPaywallVisible) {
        subscriptionProviderUi.RemotePaywall(
            listener = uiStateHolder.remotePaywallPurchaseEventsListener,
            placementId = uiState.currentPlacementId
        )
    }

    if (uiState.isLoading || !isPaywallVisible) LoadingProgress(mode = LoadingProgressMode.FULLSCREEN)
}