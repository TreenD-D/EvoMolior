package com.achulkov.evomolior.presentation.screens.favorite

import androidx.compose.runtime.Composable
import com.achulkov.evomolior.presentation.screens.paywall.PaywallScreenRoute
import com.achulkov.evomolior.root.LocalNavigator
import com.achulkov.evomolior.util.ScreenRoute
import com.achulkov.evomolior.util.uiStateHolder
import kotlinx.serialization.Serializable

@Serializable
class FavoriteScreenRoute : ScreenRoute {

    @Composable
    override fun Content() {
        val uiStateHolder = uiStateHolder<FavoriteUiStateHolder>()
        val navigator = LocalNavigator.current
        FavoriteScreen(
            uiStateHolder = uiStateHolder,
            onPaymentRequired = {
                navigator.navigate(
                    PaywallScreenRoute()
                )
            }
        )
    }
}