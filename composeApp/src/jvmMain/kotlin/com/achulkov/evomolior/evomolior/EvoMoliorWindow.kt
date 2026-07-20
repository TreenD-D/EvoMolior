package com.achulkov.evomolior.evomolior

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.achulkov.evomolior.designsystem.theme.AppTheme
import com.achulkov.evomolior.api.model.RunConfig
import com.achulkov.evomolior.core.model.Role
import com.achulkov.evomolior.evomolior.screens.EvoMoliorAgentDetailScreen
import com.achulkov.evomolior.evomolior.screens.EvoMoliorDashboardScreen
import com.achulkov.evomolior.evomolior.screens.EvoMoliorLogViewerScreen
import com.achulkov.evomolior.evomolior.screens.EvoMoliorRunMonitorScreen
import com.achulkov.evomolior.evomolior.screens.EvoMoliorRunSetupScreen
import com.achulkov.evomolior.evomolior.screens.EvoMoliorScoringDashboardScreen
import com.achulkov.evomolior.evomolior.screens.EvoMoliorPostRunAnalysisScreen
import com.achulkov.evomolior.evomolior.screens.EvoMoliorSubstrateBrowserScreen
import com.achulkov.evomolior.evomolior.screens.EvoMoliorTestRunSetupScreen
import com.achulkov.evomolior.evomolior.viewmodel.EvoMoliorViewModel
import com.achulkov.evomolior.evomolior.viewmodel.StubEvoMoliorEngineService

/**
 * Navigation routes for the EvoMolior control panel.
 */
sealed class EvoMoliorRoute {
    data object Dashboard : EvoMoliorRoute()
    data object RunSetup : EvoMoliorRoute()
    /** Minimal single-group diagnostic run setup — bypasses Genesis Council. */
    data object TestRunSetup : EvoMoliorRoute()
    data class RunMonitor(val runId: String) : EvoMoliorRoute()
    data class LogViewer(val runId: String? = null) : EvoMoliorRoute()
    data object ScoringDashboard : EvoMoliorRoute()
    data class SubstrateBrowser(val runId: String? = null, val groupId: String? = null) : EvoMoliorRoute()
    data class AgentDetail(val agentId: String, val groupId: String, val role: Role) : EvoMoliorRoute()
    data class PostRunAnalysis(val runId: String) : EvoMoliorRoute()
}

/**
 * CompositionLocal providing the navigation function throughout the EvoMolior UI tree.
 * Screens call `LocalEvoMoliorNavigator.current(route)` to navigate.
 */
val LocalEvoMoliorNavigator = compositionLocalOf<(EvoMoliorRoute) -> Unit> {
    error("No EvoMoliorNavigator provided")
}

/**
 * Top-level EvoMolior Compose Desktop window content.
 *
 * Provides simple in-memory navigation between all EvoMolior control panel screens.
 * Screens navigate by calling [LocalEvoMoliorNavigator.current].
 *
 * @param viewModel Optional shared ViewModel — defaults to stub for previews/standalone launch.
 */
@Composable
fun EvoMoliorApp(
    viewModel: EvoMoliorViewModel = remember { EvoMoliorViewModel(StubEvoMoliorEngineService()) },
    autoReplayConfig: RunConfig? = null,
    onAutoReplayCompleted: (() -> Unit)? = null
) {
    var currentRoute by remember { mutableStateOf<EvoMoliorRoute>(EvoMoliorRoute.Dashboard) }
    var autoReplayCompleted by remember(autoReplayConfig?.runId) { mutableStateOf(false) }

    LaunchedEffect(autoReplayConfig?.runId) {
        val config = autoReplayConfig ?: return@LaunchedEffect
        println("AUTO_REPLAY_START runId=${config.runId}")
        io.github.aakira.napier.Napier.i("EvoMoliorApp: auto replay enabled runId=${config.runId}")
        viewModel.startRun(config)
        viewModel.engineState.collect { state ->
            val terminalRunFinished =
                state.activeRunId == null &&
                    (state.status.name == "COMPLETED" || state.status.name == "IDLE")
            if (!autoReplayCompleted && terminalRunFinished) {
                autoReplayCompleted = true
                println("AUTO_REPLAY_COMPLETE runId=${config.runId}")
                io.github.aakira.napier.Napier.i("EvoMoliorApp: auto replay completed runId=${config.runId}")
                onAutoReplayCompleted?.invoke()
            }
        }
    }

    AppTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            CompositionLocalProvider(
                LocalEvoMoliorNavigator provides { route -> currentRoute = route }
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    when (val route = currentRoute) {
                        is EvoMoliorRoute.Dashboard ->
                            EvoMoliorDashboardScreen(viewModel = viewModel)
                        is EvoMoliorRoute.RunSetup ->
                            EvoMoliorRunSetupScreen(viewModel = viewModel)
                        is EvoMoliorRoute.TestRunSetup ->
                            EvoMoliorTestRunSetupScreen(viewModel = viewModel)
                        is EvoMoliorRoute.RunMonitor ->
                            EvoMoliorRunMonitorScreen(runId = route.runId, viewModel = viewModel)
                        is EvoMoliorRoute.LogViewer ->
                            EvoMoliorLogViewerScreen(
                                viewModel = viewModel,
                                archivedRunId = route.runId
                            )
                        is EvoMoliorRoute.ScoringDashboard ->
                            EvoMoliorScoringDashboardScreen(
                                viewModel = viewModel,
                                onBack = { currentRoute = EvoMoliorRoute.Dashboard }
                            )
                        is EvoMoliorRoute.SubstrateBrowser ->
                            EvoMoliorSubstrateBrowserScreen(
                                viewModel = viewModel,
                                initialRunId = route.runId,
                                initialGroupId = route.groupId,
                                onBack = { currentRoute = EvoMoliorRoute.Dashboard }
                            )
                        is EvoMoliorRoute.AgentDetail ->
                            EvoMoliorAgentDetailScreen(
                                agentId = route.agentId,
                                groupId = route.groupId,
                                role = route.role,
                                viewModel = viewModel
                            )
                        is EvoMoliorRoute.PostRunAnalysis ->
                            EvoMoliorPostRunAnalysisScreen(
                                viewModel = viewModel,
                                runId = route.runId
                            )
                    }
                }
            }
        }
    }
}

/** Standalone desktop entry point for the EvoMolior control panel. */
fun launchEvoMoliorWindow() = application {
    val state = rememberWindowState()
    Window(
        onCloseRequest = ::exitApplication,
        title = "EvoMolior — Civilisational AI Control Panel",
        state = state
    ) {
        EvoMoliorApp()
    }
}
