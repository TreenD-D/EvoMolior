package com.achulkov.evomolior.evokammer

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
import com.achulkov.evokammer.api.model.RunConfig
import com.achulkov.evokammer.core.model.Role
import com.achulkov.evomolior.evokammer.screens.EvoKammerAgentDetailScreen
import com.achulkov.evomolior.evokammer.screens.EvoKammerDashboardScreen
import com.achulkov.evomolior.evokammer.screens.EvoKammerLogViewerScreen
import com.achulkov.evomolior.evokammer.screens.EvoKammerRunMonitorScreen
import com.achulkov.evomolior.evokammer.screens.EvoKammerRunSetupScreen
import com.achulkov.evomolior.evokammer.screens.EvoKammerScoringDashboardScreen
import com.achulkov.evomolior.evokammer.screens.EvoKammerPostRunAnalysisScreen
import com.achulkov.evomolior.evokammer.screens.EvoKammerSubstrateBrowserScreen
import com.achulkov.evomolior.evokammer.screens.EvoKammerTestRunSetupScreen
import com.achulkov.evomolior.evokammer.viewmodel.EvoKammerViewModel
import com.achulkov.evomolior.evokammer.viewmodel.StubEvoKammerEngineService

/**
 * Navigation routes for the EvoKammer control panel.
 */
sealed class EvoKammerRoute {
    data object Dashboard : EvoKammerRoute()
    data object RunSetup : EvoKammerRoute()
    /** Minimal single-group diagnostic run setup — bypasses Genesis Council. */
    data object TestRunSetup : EvoKammerRoute()
    data class RunMonitor(val runId: String) : EvoKammerRoute()
    data class LogViewer(val runId: String? = null) : EvoKammerRoute()
    data object ScoringDashboard : EvoKammerRoute()
    data class SubstrateBrowser(val runId: String? = null, val groupId: String? = null) : EvoKammerRoute()
    data class AgentDetail(val agentId: String, val groupId: String, val role: Role) : EvoKammerRoute()
    data class PostRunAnalysis(val runId: String) : EvoKammerRoute()
}

/**
 * CompositionLocal providing the navigation function throughout the EvoKammer UI tree.
 * Screens call `LocalEvoKammerNavigator.current(route)` to navigate.
 */
val LocalEvoKammerNavigator = compositionLocalOf<(EvoKammerRoute) -> Unit> {
    error("No EvoKammerNavigator provided")
}

/**
 * Top-level EvoKammer Compose Desktop window content.
 *
 * Provides simple in-memory navigation between all EvoKammer control panel screens.
 * Screens navigate by calling [LocalEvoKammerNavigator.current].
 *
 * @param viewModel Optional shared ViewModel — defaults to stub for previews/standalone launch.
 */
@Composable
fun EvoKammerApp(
    viewModel: EvoKammerViewModel = remember { EvoKammerViewModel(StubEvoKammerEngineService()) },
    autoReplayConfig: RunConfig? = null,
    onAutoReplayCompleted: (() -> Unit)? = null
) {
    var currentRoute by remember { mutableStateOf<EvoKammerRoute>(EvoKammerRoute.Dashboard) }
    var autoReplayCompleted by remember(autoReplayConfig?.runId) { mutableStateOf(false) }

    LaunchedEffect(autoReplayConfig?.runId) {
        val config = autoReplayConfig ?: return@LaunchedEffect
        println("AUTO_REPLAY_START runId=${config.runId}")
        io.github.aakira.napier.Napier.i("EvoKammerApp: auto replay enabled runId=${config.runId}")
        viewModel.startRun(config)
        viewModel.engineState.collect { state ->
            val terminalRunFinished =
                state.activeRunId == null &&
                    (state.status.name == "COMPLETED" || state.status.name == "IDLE")
            if (!autoReplayCompleted && terminalRunFinished) {
                autoReplayCompleted = true
                println("AUTO_REPLAY_COMPLETE runId=${config.runId}")
                io.github.aakira.napier.Napier.i("EvoKammerApp: auto replay completed runId=${config.runId}")
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
                LocalEvoKammerNavigator provides { route -> currentRoute = route }
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    when (val route = currentRoute) {
                        is EvoKammerRoute.Dashboard ->
                            EvoKammerDashboardScreen(viewModel = viewModel)
                        is EvoKammerRoute.RunSetup ->
                            EvoKammerRunSetupScreen(viewModel = viewModel)
                        is EvoKammerRoute.TestRunSetup ->
                            EvoKammerTestRunSetupScreen(viewModel = viewModel)
                        is EvoKammerRoute.RunMonitor ->
                            EvoKammerRunMonitorScreen(runId = route.runId, viewModel = viewModel)
                        is EvoKammerRoute.LogViewer ->
                            EvoKammerLogViewerScreen(
                                viewModel = viewModel,
                                archivedRunId = route.runId
                            )
                        is EvoKammerRoute.ScoringDashboard ->
                            EvoKammerScoringDashboardScreen(
                                viewModel = viewModel,
                                onBack = { currentRoute = EvoKammerRoute.Dashboard }
                            )
                        is EvoKammerRoute.SubstrateBrowser ->
                            EvoKammerSubstrateBrowserScreen(
                                viewModel = viewModel,
                                initialRunId = route.runId,
                                initialGroupId = route.groupId,
                                onBack = { currentRoute = EvoKammerRoute.Dashboard }
                            )
                        is EvoKammerRoute.AgentDetail ->
                            EvoKammerAgentDetailScreen(
                                agentId = route.agentId,
                                groupId = route.groupId,
                                role = route.role,
                                viewModel = viewModel
                            )
                        is EvoKammerRoute.PostRunAnalysis ->
                            EvoKammerPostRunAnalysisScreen(
                                viewModel = viewModel,
                                runId = route.runId
                            )
                    }
                }
            }
        }
    }
}

/** Standalone desktop entry point for the EvoKammer control panel. */
fun launchEvoKammerWindow() = application {
    val state = rememberWindowState()
    Window(
        onCloseRequest = ::exitApplication,
        title = "EvoKammer — Civilisational AI Control Panel",
        state = state
    ) {
        EvoKammerApp()
    }
}
