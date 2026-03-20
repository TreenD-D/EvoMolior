package com.achulkov.evomolior.presentation.screens.account

import androidx.compose.runtime.Composable
import com.achulkov.evomolior.presentation.screens.helpandsupport.HelpAndSupportScreenRoute
import com.achulkov.evomolior.presentation.screens.paywall.PaywallScreenRoute
import com.achulkov.evomolior.presentation.screens.profile.ProfileScreenRoute
import com.achulkov.evomolior.presentation.screens.signin.SignInScreenRoute
import com.achulkov.evomolior.root.LocalNavigator
import com.achulkov.evomolior.presentation.screens.subscriptions.SubscriptionsScreenRoute
import com.achulkov.evomolior.util.ScreenRoute
import com.achulkov.evomolior.util.uiStateHolder
import kotlinx.serialization.Serializable

@Serializable
class AccountScreenRoute : ScreenRoute {

    @Composable
    override fun Content() {
        val uiStateHolder = uiStateHolder<AccountUiStateHolder>()
        val navigator = LocalNavigator.current
        AccountScreen(
            uiStateHolder = uiStateHolder,
            onNavigateHelpAndSupport = {
                navigator.navigate(HelpAndSupportScreenRoute())
            },
            onNavigatePaywall = {
                navigator.navigate(PaywallScreenRoute())
            },
            onNavigateSignIn = {
                navigator.navigate(SignInScreenRoute())
            },
            onNavigateProfile = {
                navigator.navigate(ProfileScreenRoute())
            },
            onNavigateSubscriptions = {
                navigator.navigate(SubscriptionsScreenRoute())
            }
        )
    }

}