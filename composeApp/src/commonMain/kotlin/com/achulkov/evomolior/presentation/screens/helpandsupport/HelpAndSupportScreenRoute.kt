package com.achulkov.evomolior.presentation.screens.helpandsupport

import androidx.compose.runtime.Composable
import com.achulkov.evomolior.root.LocalNavigator
import com.achulkov.evomolior.util.ScreenRoute
import kotlinx.serialization.Serializable

@Serializable
class HelpAndSupportScreenRoute : ScreenRoute {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        HelpAndSupportScreen(onNavigateBack = { navigator.popBackStack() })
    }

}