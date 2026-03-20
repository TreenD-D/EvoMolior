package com.achulkov.evomolior.presentation.screens.signin

import androidx.compose.runtime.Composable
import com.achulkov.evomolior.root.LocalNavigator
import com.achulkov.evomolior.util.ScreenRoute
import kotlinx.serialization.Serializable

@Serializable
class SignInScreenRoute(private val isSignIn: Boolean = false) : ScreenRoute {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        SignInScreen(
            isSignIn = isSignIn,
            onSuccessfulSignIn = {
                navigator.popBackStack()
            },
            onNavigateBack = {
                navigator.popBackStack()
            },
            onNavigateSignIn = {
                navigator.navigate(SignInScreenRoute(isSignIn = true)) {
                    popUpTo(SignInScreenRoute()) {
                        inclusive = true
                    }
                }
            }
        )
    }
}