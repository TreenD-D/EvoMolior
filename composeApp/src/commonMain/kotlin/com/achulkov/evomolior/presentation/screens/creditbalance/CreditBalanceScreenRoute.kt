package com.achulkov.evomolior.presentation.screens.creditbalance

import androidx.compose.runtime.Composable
import com.achulkov.evomolior.presentation.screens.paywall.PaywallScreenRoute
import com.achulkov.evomolior.root.LocalNavigator
import com.achulkov.evomolior.util.ScreenRoute
import com.achulkov.evomolior.util.uiStateHolder
import kotlinx.serialization.Serializable

@Serializable
class CreditBalanceScreenRoute : ScreenRoute {

    @Composable
    override fun Content() {
        val uiStateHolder = uiStateHolder<CreditBalanceUiStateHolder>()
        val navigator = LocalNavigator.current
        CreditBalanceScreen(
            uiStateHolder = uiStateHolder,
            onPurchaseRequired = {
                navigator.navigate(PaywallScreenRoute())
            },
            onClickBack = {
                navigator.popBackStack()
            }
        )
    }
}