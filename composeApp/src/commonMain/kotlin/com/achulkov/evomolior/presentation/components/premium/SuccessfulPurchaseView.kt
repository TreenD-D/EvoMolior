package com.achulkov.evomolior.presentation.components.premium

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import com.achulkov.evomolior.designsystem.components.premium.PremiumFeatureUiState
import com.achulkov.evomolior.designsystem.components.premium.SuccessfulPurchaseContent
import com.achulkov.evomolior.util.Constants.subscriptionUrl
import com.achulkov.evomolior.util.inappreview.rememberInAppReviewTrigger
import com.achulkov.evomolior.util.logging.AppLogger
import com.achulkov.evomolior.util.logging.logSuccessfulPurchase

@Composable
fun SuccessfulPurchaseView(
    features: List<PremiumFeatureUiState> = emptyList(),
    expirationDate: String? = null,
    isLifetime: Boolean = false,
    isRecurring: Boolean = true,
    modifier: Modifier = Modifier,
    onContinue: () -> Unit = {}
) {

    val inAppReviewTrigger = rememberInAppReviewTrigger()
    LaunchedEffect(Unit) {
        AppLogger.logSuccessfulPurchase()
        inAppReviewTrigger.triggerAfterSuccessfulPurchase()
    }

    SuccessfulPurchaseContent(
        subscriptionUrl = subscriptionUrl,
        features = features,
        expirationDate = expirationDate,
        isLifetime = isLifetime,
        isRecurring = isRecurring,
        modifier = modifier,
        onContinue = onContinue
    )
}

