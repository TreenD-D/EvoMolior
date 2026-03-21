package com.achulkov.evomolior

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.achulkov.evokammer.api.service.EvoKammerRunService
import com.achulkov.evokammer.competition.coordinator.GroupCoordinator
import com.achulkov.evokammer.competition.grader.GraderGroupManager
import com.achulkov.evokammer.orchestration.lifecycle.LifecycleManager
import com.achulkov.evokammer.sandbox.shuru.ShuruVmManager
import com.achulkov.evokammer.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.evokammer.EvoKammerApp
import com.achulkov.evomolior.evokammer.viewmodel.EvoKammerViewModel
import com.achulkov.evomolior.root.AppInitializer
import io.github.aakira.napier.Napier
import java.io.File

/**
 * Desktop application entry point.
 *
 * Wires the real [EvoKammerRunService] in-process for the JVM desktop target,
 * connecting all engine subsystems (substrate, orchestration, competition, sandbox).
 */
fun main() = application {
    AppInitializer.initialize {}

    // ── EvoKammer engine wiring ──────────────────────────────────────────────
    // Data root: ~/.evokammer (created on first run)
    val dataRootDir = File(System.getProperty("user.home"), ".evokammer").also { it.mkdirs() }
    Napier.i("EvoKammer: data root = ${dataRootDir.absolutePath}")

    val substrateFactory = SubstrateDatabaseFactory(dataRootDir)
    val shuruVmManager = ShuruVmManager()

    val engineService = EvoKammerRunService(
        lifecycleManagerProvider = { runId, groupId, openRouterApiKey, agentJarPath ->
            Napier.i("EvoKammer: creating LifecycleManager for runId=$runId groupId=$groupId")
            LifecycleManager(
                runId = runId,
                groupId = groupId,
                shuruVmManager = shuruVmManager,
                substrateDatabaseFactory = substrateFactory,
                agentJarPath = agentJarPath,
                openRouterApiKey = openRouterApiKey
            )
        },
        groupCoordinatorProvider = { groupIds, dimensions ->
            Napier.i("EvoKammer: creating GroupCoordinator for groups=$groupIds")
            GroupCoordinator(groupIds = groupIds, dimensions = dimensions)
        },
        graderGroupManagerProvider = { dimensions ->
            Napier.i("EvoKammer: creating GraderGroupManager with ${dimensions.size} dimensions")
            GraderGroupManager(dimensions = dimensions)
        },
        substrateFactory = substrateFactory
    )

    val viewModel = EvoKammerViewModel(engineService)

    val windowState = rememberWindowState(size = DpSize(1400.dp, 900.dp))
    Window(
        onCloseRequest = ::exitApplication,
        title = "EvoKammer — Civilisational AI Control Panel",
        state = windowState
    ) {
        EvoKammerApp(viewModel = viewModel)
    }
}
