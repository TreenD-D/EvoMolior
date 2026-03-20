package com.achulkov.evomolior.presentation.screens.subscriptions

import androidx.compose.runtime.Composable
import com.achulkov.evomolior.presentation.screens.paywall.PaywallScreenRoute
import com.achulkov.evomolior.root.LocalNavigator
import com.achulkov.evomolior.util.ScreenRoute
import com.achulkov.evomolior.util.uiStateHolder
import kotlinx.serialization.Serializable

@Serializable
class SubscriptionsScreenRoute : ScreenRoute {

    @Composable
    override fun Content() {
        val uiStateHolder = uiStateHolder<SubscriptionsUiStateHolder>()
        val navigator = LocalNavigator.current
        SubscriptionsScreen(
            uiStateHolder = uiStateHolder,
            onClickBack = { navigator.popBackStack() },
            onNavigatePaywall = {
                navigator.navigate(PaywallScreenRoute())
            })
    }
}