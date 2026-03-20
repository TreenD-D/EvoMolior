package com.achulkov.evomolior

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.achulkov.evomolior.evokammer.EvoKammerApp
import com.achulkov.evomolior.root.App
import com.achulkov.evomolior.root.AppInitializer

fun main() = application {
    AppInitializer.initialize {}

    // EvoKammer Civilisational AI Control Panel (primary desktop window)
    val evoKammerState = rememberWindowState(size = DpSize(1400.dp, 900.dp))
    Window(
        onCloseRequest = ::exitApplication,
        title = "EvoKammer — Civilisational AI Control Panel",
        state = evoKammerState
    ) {
        EvoKammerApp()
    }

    // Original app window (for reference/mobile preview — can be closed independently)
    val appState = rememberWindowState(size = DpSize(420.dp, 820.dp))
    Window(
        onCloseRequest = { /* keep running even if this is closed */ },
        title = "EvoMolior (Mobile Preview)",
        state = appState
    ) {
        App()
    }
}
