package com.achulkov.evomolior.presentation.screens.onboarding

import androidx.compose.runtime.Composable
import com.achulkov.evomolior.presentation.screens.main.MainScreenRoute
import com.achulkov.evomolior.presentation.screens.paywall.PaywallScreenRoute
import com.achulkov.evomolior.root.LocalNavigator
import com.achulkov.evomolior.util.ScreenRoute
import com.achulkov.evomolior.util.uiStateHolder
import kotlinx.serialization.Serializable

@Serializable
class OnBoardingScreenRoute : ScreenRoute {

    @Composable
    override fun Content() {
        val uiStateHolder = uiStateHolder<OnBoardingUiStateHolder>()
        val navigator = LocalNavigator.current
        OnBoardingScreen(
            style = OnBoardingScreenStyle.STYLE1,
            uiStateHolder = uiStateHolder,
            onNavigateMain = {
                navigator.navigate(MainScreenRoute()) {
                    popUpTo(OnBoardingScreenRoute()) {
                        inclusive = true
                    }
                }
            },
            onNavigatePaywall = {
                navigator.navigate(PaywallScreenRoute())
            }
        )
    }

}


