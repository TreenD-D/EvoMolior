package com.achulkov.evomolior

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.achulkov.evomolior.root.App
import com.achulkov.evomolior.root.AppInitializer

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    AppInitializer.initialize {}
    ComposeViewport {
        App()
    }
}