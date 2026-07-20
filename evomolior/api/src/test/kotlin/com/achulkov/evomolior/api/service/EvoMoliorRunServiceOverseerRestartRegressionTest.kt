package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.core.contract.OverseerAcceptanceCriteria
import com.achulkov.evomolior.core.contract.OverseerDeadEnd
import com.achulkov.evomolior.core.contract.OverseerExperimentMetrics
import com.achulkov.evomolior.core.contract.OverseerExperimentPlan
import com.achulkov.evomolior.core.contract.OverseerExpectedEffect
import com.achulkov.evomolior.core.contract.OverseerHypothesis
import com.achulkov.evomolior.core.contract.OverseerHypothesisStatus
import com.achulkov.evomolior.core.contract.OverseerKernelAuditActionType
import com.achulkov.evomolior.core.contract.OverseerMutabilityLevel
import com.achulkov.evomolior.core.contract.OverseerOrigin
import com.achulkov.evomolior.core.contract.OverseerOriginType
import com.achulkov.evomolior.core.contract.OverseerTargetScope
import com.achulkov.evomolior.core.contract.OverseerVariationSpec
import com.achulkov.evomolior.competition.coordinator.GroupCoordinator
import com.achulkov.evomolior.competition.grader.GraderGroupManager
import com.achulkov.evomolior.orchestration.metaplanner.MetaPlannerRunner
import com.achulkov.evomolior.orchestration.lifecycle.LifecycleManager
import com.achulkov.evomolior.sandbox.shuru.ShuruVmManager
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.net.ServerSocket
import java.net.http.HttpClient

/**
 * Verifies the real run service persists Overseer state and audit entries across a full IPC restart.
 */
class EvoMoliorRunServiceOverseerRestartRegressionTest {
    private var server: EvoMoliorIpcServer? = null

    @AfterTest
    fun tearDown() {
        server?.stop()
        server = null
    }

    @Test
    fun `real run service overseer state survives ipc restart through the typed client`() = runBlocking {
        val factory = SubstrateDatabaseFactory(createTempDirectory("overseer-run-service-restart-").toFile())
        val port = freePort()
        val client = OverseerKernelClient(baseUrl = "http://localhost:$port", httpClient = HttpClient.newHttpClient())

        server = EvoMoliorIpcServer(realRunService(factory), port).also { it.start() }
        waitForServer(port)

        val hypothesis = hypothesis()
        val experiment = experimentPlan(hypothesis.id)

        val formalized = client.formalizeHypothesis(hypothesis)
        assertEquals(OverseerHypothesisStatus.FORMALIZED, formalized.hypotheses.getValue(hypothesis.id).status)
        client.registerExperimentPlan(experiment)
        client.recordDeadEnd(OverseerDeadEnd(hypothesisId = hypothesis.id, reason = "immutable core remains off-limits"))

        val auditBeforeRestart = client.getAuditEntries()
        assertTrue(auditBeforeRestart.size >= 3)

        server?.stop()
        server = EvoMoliorIpcServer(realRunService(factory), port).also { it.start() }
        waitForServer(port)

        val reloaded = client.getState()
        assertTrue(reloaded.hypotheses.containsKey(hypothesis.id))
        assertTrue(reloaded.experimentPlans.containsKey(experiment.id))
        assertTrue(reloaded.deadEnds.containsKey(hypothesis.id))

        val auditAfterRestart = client.getAuditEntries()
        assertTrue(auditAfterRestart.size >= auditBeforeRestart.size)
        assertTrue(auditAfterRestart.any { it.actionType == OverseerKernelAuditActionType.RECORD_DEAD_END })

        val archiveDb = factory.get("__overseer_kernel__", "__meta_archive__")
        val archiveEntries = archiveDb.metaArchiveDao().getByType(RunArchiveService.ENTRY_OVERSEER_AUDIT)
        assertEquals(3, archiveEntries.size)
        assertTrue(archiveEntries.any { it.contentJson.contains(hypothesis.id) })
    }

    private fun realRunService(factory: SubstrateDatabaseFactory): EvoMoliorRunService = EvoMoliorRunService(
        lifecycleManagerProvider = { runId, groupId, _, _, groupProfile ->
            LifecycleManager(
                runId = runId,
                groupId = groupId,
                shuruVmManager = ShuruVmManager(),
                substrateDatabaseFactory = factory,
                agentJarPath = "/fake/agent.jar",
                openRouterApiKey = "sk-test",
                groupProfile = groupProfile,
                localAgentRunner = { _, _ -> error("LifecycleManager should not be constructed in this restart regression") }
            )
        },
        groupCoordinatorProvider = { groupIds, dimensions -> GroupCoordinator(groupIds, dimensions) },
        graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
        substrateFactory = factory,
        metaPlannerRunner = MetaPlannerRunner(),
        roundHeartbeatIntervalMs = 1L,
        inspectorCheckIntervalMs = 1L
    )

    private fun hypothesis(): OverseerHypothesis = OverseerHypothesis(
        id = "HYP-production-restart-1",
        title = "Production restart regression hypothesis",
        origin = OverseerOrigin(
            type = OverseerOriginType.INTERNAL_LOGS,
            sources = listOf("documentation/EvoMolior_Current_Session_Handoff.md")
        ),
        targetScope = OverseerTargetScope(
            paths = listOf("evomolior/core/src/main/resources/overseer/policy.json"),
            mutabilityLevel = OverseerMutabilityLevel.SEMI_MUTABLE
        ),
        rationale = "Prove the real runtime service persists Overseer control-plane state.",
        expectedEffect = OverseerExpectedEffect(metricsUp = listOf("restart_survivability"))
    )

    private fun experimentPlan(hypothesisId: String): OverseerExperimentPlan = OverseerExperimentPlan(
        id = "EXP-production-restart-1",
        hypothesisId = hypothesisId,
        runCount = 1,
        generationsPerRun = 1,
        variationSpec = OverseerVariationSpec(configChanges = listOf("raise selector budget")),
        metrics = OverseerExperimentMetrics(primary = listOf("restart_survivability")),
        acceptanceCriteria = OverseerAcceptanceCriteria(predicates = listOf("restart_survivability > 0.5")),
        maxCost = 1.0
    )

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private suspend fun waitForServer(port: Int) {
        repeat(50) {
            try {
                HttpClient.newHttpClient().send(
                    java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create("http://localhost:$port/state"))
                        .GET()
                        .build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString()
                )
                return
            } catch (_: Exception) {
                delay(50)
            }
        }
        error("Server on port $port did not become ready")
    }
}
