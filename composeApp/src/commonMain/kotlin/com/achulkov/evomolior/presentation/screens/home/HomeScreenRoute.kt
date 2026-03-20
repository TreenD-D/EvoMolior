package com.achulkov.evomolior.presentation.screens.home

import androidx.compose.runtime.Composable
import com.achulkov.evomolior.presentation.screens.creditbalance.CreditBalanceScreenRoute
import com.achulkov.evomolior.root.LocalNavigator
import com.achulkov.evomolior.util.ScreenRoute
import com.achulkov.evomolior.util.uiStateHolder
import kotlinx.serialization.Serializable

@Serializable
class HomeScreenRoute : ScreenRoute {

    @Composable
    override fun Content() {
        val uiStateHolder = uiStateHolder<HomeUiStateHolder>()
        val navigator = LocalNavigator.current
        HomeScreen(
            uiStateHolder = uiStateHolder,
            onMoreCreditsNeeded = {
                navigator.navigate(CreditBalanceScreenRoute())
            }
        )
    }
}