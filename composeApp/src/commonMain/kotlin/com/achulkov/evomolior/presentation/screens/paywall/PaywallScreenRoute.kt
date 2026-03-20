package com.achulkov.evomolior.presentation.screens.paywall

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.achulkov.evomolior.data.source.featureflag.FeatureFlagManager
import com.achulkov.evomolior.presentation.screens.paywall.remotepaywall.RemotePaywallScreen
import com.achulkov.evomolior.presentation.screens.signin.SignInScreenRoute
import com.achulkov.evomolior.root.LocalNavigator
import com.achulkov.evomolior.util.ScreenRoute
import com.achulkov.evomolior.util.uiStateHolder
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

@Serializable
class PaywallScreenRoute : ScreenRoute {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val featureFlagManager = koinInject<FeatureFlagManager>()
        val uiStateHolder = uiStateHolder<PaywallUiStateHolder>()
        if (featureFlagManager.getBoolean(FeatureFlagManager.Keys.SHOW_REMOTE_PAYWALL)) {
            Box(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                RemotePaywallScreen(
                    uiStateHolder = uiStateHolder,
                    onDismiss = {
                        uiStateHolder.onPaywallDismissActionHandled()
                        navigator.popBackStack()
                    },
                    onSignInRequired = {
                        navigator.navigate(SignInScreenRoute())
                    }
                )
            }
        } else {
            PaywallScreen(
                uiStateHolder = uiStateHolder,
                onDismiss = {
                    uiStateHolder.onPaywallDismissActionHandled()
                    navigator.popBackStack()
                },
                onSignInRequired = {
                    navigator.navigate(SignInScreenRoute())
                }
            )
        }
    }

}