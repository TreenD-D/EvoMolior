package com.achulkov.evomolior.presentation.screens.profile

import androidx.compose.runtime.Composable
import com.achulkov.evomolior.presentation.screens.signin.SignInScreenRoute
import com.achulkov.evomolior.root.LocalNavigator
import com.achulkov.evomolior.util.ScreenRoute
import com.achulkov.evomolior.util.navigatorUiStateHolder
import kotlinx.serialization.Serializable

@Serializable
class ProfileScreenRoute : ScreenRoute {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        /*
            Use  as below for scoping uiStateHolder to the screen instead of navigation
            val uiStateHolder = uiStateHolder<ProfileUiStateHolder>()
         */
        val uiStateHolder = navigator.navigatorUiStateHolder<ProfileUiStateHolder>()
        ProfileScreen(
            uiStateHolder = uiStateHolder,
            onSignInRequired = {
                navigator.navigate(SignInScreenRoute()) {
                    popUpTo(ProfileScreenRoute()) {
                        inclusive = true
                    }
                }
            },
            onNavigateToBack = { navigator.popBackStack() }
        )
    }
}