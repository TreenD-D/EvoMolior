package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.api.events.EvoMoliorEvent
import com.achulkov.evomolior.api.model.OverseerExperimentLaunchRequest
import com.achulkov.evomolior.api.model.OverseerExperimentLaunchResponse
import com.achulkov.evomolior.api.model.RunConfig
import com.achulkov.evomolior.api.model.RunInputArtifactUpload
import com.achulkov.evomolior.core.contract.OverseerAcceptanceCriteria
import com.achulkov.evomolior.core.contract.OverseerDeadEnd
import com.achulkov.evomolior.core.contract.OverseerExperimentPlanHistoryActionType
import com.achulkov.evomolior.core.contract.OverseerExpectedEffect
import com.achulkov.evomolior.core.contract.OverseerExperimentMetrics
import com.achulkov.evomolior.core.contract.OverseerExperimentOutcome
import com.achulkov.evomolior.core.contract.OverseerExperimentPlan
import com.achulkov.evomolior.core.contract.OverseerExperimentResult
import com.achulkov.evomolior.core.contract.OverseerExperimentSchedule
import com.achulkov.evomolior.core.contract.OverseerExperimentPlanEligibility
import com.achulkov.evomolior.core.contract.OverseerHypothesis
import com.achulkov.evomolior.core.contract.OverseerHypothesisStatus
import com.achulkov.evomolior.core.contract.OverseerKernelEligibility
import com.achulkov.evomolior.core.contract.OverseerKernelState
import com.achulkov.evomolior.core.contract.OverseerMutabilityLevel
import com.achulkov.evomolior.core.contract.OverseerOrigin
import com.achulkov.evomolior.core.contract.OverseerOriginType
import com.achulkov.evomolior.core.contract.OverseerTargetScope
import com.achulkov.evomolior.core.contract.OverseerVariationSpec
import com.achulkov.evomolior.core.model.DimensionScore
import com.achulkov.evomolior.core.model.GradingResult
import com.achulkov.evomolior.core.model.GroupProfileType
import com.achulkov.evomolior.core.model.RoundScore
import com.achulkov.evomolior.core.model.ScoringDimension
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Smoke test for the client-facing IPC bridge.
 *
 * Verifies that a caller can start a run over HTTP, observe lifecycle events over WebSocket,
 * and retrieve both live state and the completed-run archive summary through HTTP.
 */
class EvoMoliorIpcServerSmokeTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private var server: EvoMoliorIpcServer? = null

    @AfterTest
    fun tearDown() {
        server?.stop()
        server = null
    }

    @Test
    fun `http and websocket endpoints expose the same run lifecycle`() = runBlocking {
        val port = freePort()
        val runId = "run-ipc-smoke"

        val engineService = FakeEngineService()
        server = EvoMoliorIpcServer(engineService = engineService, port = port).also { it.start() }

        waitForServer(port)

        val runStarted = CompletableDeferred<EvoMoliorEvent.RunStarted>()
        val roundCompleted = CompletableDeferred<EvoMoliorEvent.RoundCompleted>()
        val runCompleted = CompletableDeferred<EvoMoliorEvent.RunCompleted>()
        val webSocketOpen = CountDownLatch(1)
        val webSocket = HttpClient.newHttpClient()
            .newWebSocketBuilder()
            .buildAsync(
                URI.create("ws://localhost:$port/ws/events"),
                object : WebSocket.Listener {
                    override fun onOpen(webSocket: WebSocket) {
                        webSocketOpen.countDown()
                        webSocket.request(1)
                    }

                    override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletableFuture<*> {
                        if (last) {
                            val event = json.decodeFromString<EvoMoliorEvent>(data.toString())
                            if (event is EvoMoliorEvent.RunStarted) {
                                runStarted.complete(event)
                            } else if (event is EvoMoliorEvent.RoundCompleted) {
                                roundCompleted.complete(event)
                            } else if (event is EvoMoliorEvent.RunCompleted) {
                                runCompleted.complete(event)
                            }
                        }
                        webSocket.request(1)
                        return CompletableFuture.completedFuture(null)
                    }
                }
            )
            .join()

        assertTrue(webSocketOpen.await(5, TimeUnit.SECONDS), "WebSocket client should connect")

        val httpClient = HttpClient.newHttpClient()
        val startResponse = httpClient.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$port/run/start"))
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        json.encodeToString(
                            RunConfig(
                                runId = runId,
                                objective = "ipc smoke test",
                                groupCount = 2,
                                groupProfiles = listOf(GroupProfileType.Library, GroupProfileType.Factory),
                                rounds = 1,
                                roundDurationMinutes = 0,
                                maxAgentsPerGroup = 1,
                                scoringDimensions = listOf(
                                    ScoringDimension(
                                        id = "accuracy",
                                        name = "Accuracy",
                                        description = "Reward accurate outputs.",
                                        weight = 1.0
                                    )
                                ),
                                openRouterApiKey = "sk-test",
                                isTestRun = true
                            )
                        ),
                        StandardCharsets.UTF_8
                    )
                )
                .build(),
            HttpResponse.BodyHandlers.ofString()
        )

        assertEquals(200, startResponse.statusCode())
        assertTrue(startResponse.body().contains(runId), "The start response should include the run ID")

        val liveEvent = withTimeout(5_000) { runStarted.await() }
        assertEquals(runId, liveEvent.runId)
        assertEquals("ipc smoke test", liveEvent.objective)

        val runningStateResponse = httpClient.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$port/state"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString()
        )

        assertEquals(200, runningStateResponse.statusCode())
        val runningState = json.decodeFromString<EngineState>(runningStateResponse.body())
        assertEquals(EngineStatus.RUNNING, runningState.status)
        assertEquals(runId, runningState.activeRunId)
        assertNotNull(runningState.currentRound)

        val completedRound = withTimeout(5_000) { roundCompleted.await() }
        assertEquals(runId, completedRound.runId)
        assertEquals(0, completedRound.result.roundIndex)

        val completedRun = withTimeout(5_000) { runCompleted.await() }
        assertEquals(runId, completedRun.runId)
        assertEquals("group-0", completedRun.winnerId)

        val completedStateResponse = httpClient.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$port/state"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString()
        )

        assertEquals(200, completedStateResponse.statusCode())
        val completedState = json.decodeFromString<EngineState>(completedStateResponse.body())
        assertTrue(
            completedState.status == EngineStatus.COMPLETED || completedState.status == EngineStatus.IDLE,
            "The completed run should report a terminal state"
        )

        val summaryResponse = httpClient.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$port/runs/$runId/meta-archive-summary"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString()
        )

        assertEquals(200, summaryResponse.statusCode())
        val summary = json.decodeFromString<MetaArchiveSummary>(summaryResponse.body())
        assertTrue(summary.total > 0, "The completed run should expose archive contents through the IPC bridge")
        assertTrue(summary.byType.isNotEmpty(), "The archive summary should include at least one entry type")

        webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join()
    }

    @Test
    fun `results download endpoint returns the configured json output bundle`() = runBlocking {
        val port = freePort()
        val runId = "run-ipc-results-download"
        val engineService = FakeEngineService(
            resultsBundleBytes = zipBytes(
                "manifest.json" to """{"runId":"$runId"}""",
                "outputs/briefs/evidence-brief.json" to """{"headline":"IPC Evidence Brief"}"""
            )
        )
        server = EvoMoliorIpcServer(engineService = engineService, port = port).also { it.start() }

        waitForServer(port)

        val httpClient = HttpClient.newHttpClient()
        httpClient.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$port/run/start"))
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        json.encodeToString(
                            RunConfig(
                                runId = runId,
                                objective = "ipc results download test",
                                groupCount = 2,
                                groupProfiles = listOf(GroupProfileType.Library, GroupProfileType.Factory),
                                rounds = 1,
                                roundDurationMinutes = 0,
                                maxAgentsPerGroup = 1,
                                scoringDimensions = listOf(
                                    ScoringDimension(
                                        id = "accuracy",
                                        name = "Accuracy",
                                        description = "Reward accurate outputs.",
                                        weight = 1.0
                                    )
                                ),
                                openRouterApiKey = "sk-test",
                                finalOutputSpec = com.achulkov.evomolior.api.model.FinalOutputSpec(
                                    relativePath = "briefs/evidence-brief.json",
                                    mediaType = "application/json",
                                    description = "Machine-readable evidence brief"
                                ),
                                isTestRun = true
                            )
                        ),
                        StandardCharsets.UTF_8
                    )
                )
                .build(),
            HttpResponse.BodyHandlers.ofString()
        )

        val response = httpClient.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$port/runs/$runId/results/download"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofByteArray()
        )

        assertEquals(200, response.statusCode())
        assertEquals("attachment; filename=\"results-$runId.zip\"", response.headers().firstValue("Content-Disposition").orElse(null))
        assertEquals("application/zip", response.headers().firstValue("Content-Type").orElse(null))
        val entries = unzipEntries(response.body())
        assertEquals("""{"headline":"IPC Evidence Brief"}""", entries["outputs/briefs/evidence-brief.json"])
    }

    @Test
    fun `overseer endpoints expose durable kernel state through ipc`() = runBlocking {
        val port = freePort()
        val engineService = FakeEngineService()
        server = EvoMoliorIpcServer(engineService = engineService, port = port).also { it.start() }

        waitForServer(port)

        val httpClient = HttpClient.newHttpClient()

        val initialStateResponse = httpClient.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$port/overseer/state"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString()
        )
        assertEquals(200, initialStateResponse.statusCode())
        val initialState = json.decodeFromString<OverseerKernelState>(initialStateResponse.body())
        assertTrue(initialState.hypotheses.isEmpty())

        val planPreflightResponse = httpClient.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$port/overseer/experiment-plans/preflight"))
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        json.encodeToString(
                            OverseerExperimentPlan(
                                id = "EXP-ipc-1",
                                hypothesisId = "HYP-ipc-1",
                                runCount = 1,
                                generationsPerRun = 1,
                                variationSpec = OverseerVariationSpec(configChanges = listOf("raise selector budget")),
                                metrics = OverseerExperimentMetrics(primary = listOf("visibility")),
                                acceptanceCriteria = OverseerAcceptanceCriteria(predicates = listOf("visibility > 0.5")),
                                maxCost = 1.0
                            )
                        )
                    )
                )
                .build(),
            HttpResponse.BodyHandlers.ofString()
        )
        assertEquals(200, planPreflightResponse.statusCode())
        val planEligibility = json.decodeFromString<OverseerExperimentPlanEligibility>(planPreflightResponse.body())
        assertTrue(planEligibility.allowed)

        val hypothesisResponse = httpClient.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$port/overseer/hypotheses"))
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        json.encodeToString(
                            OverseerHypothesis(
                                id = "HYP-ipc-1",
                                title = "IPC hypothesis",
                                origin = OverseerOrigin(
                                    type = OverseerOriginType.INTERNAL_METRICS,
                                    sources = listOf("documentation/EvoMolior_Recursive_Self_Improvement_Plan_v4.md")
                                ),
                                targetScope = OverseerTargetScope(
                                    paths = listOf("evomolior/core/src/main/resources/overseer/policy.json"),
                                    mutabilityLevel = OverseerMutabilityLevel.SEMI_MUTABLE
                                ),
                                rationale = "Surface deterministic kernel state over IPC.",
                                expectedEffect = OverseerExpectedEffect(
                                    metricsUp = listOf("visibility"),
                                    invariantsHold = listOf("immutable_core_unchanged")
                                )
                            )
                        )
                    )
                )
                .build(),
            HttpResponse.BodyHandlers.ofString()
        )
        assertEquals(200, hypothesisResponse.statusCode())
        val formalizedState = json.decodeFromString<OverseerKernelState>(hypothesisResponse.body())
        assertTrue(formalizedState.hypotheses.containsKey("HYP-ipc-1"))
        assertEquals(OverseerHypothesisStatus.FORMALIZED, formalizedState.hypotheses.getValue("HYP-ipc-1").status)

        val experimentPlanResponse = httpClient.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$port/overseer/experiment-plans"))
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        json.encodeToString(
                            OverseerExperimentPlan(
                                id = "EXP-ipc-1",
                                hypothesisId = "HYP-ipc-1",
                                runCount = 1,
                                generationsPerRun = 1,
                                variationSpec = OverseerVariationSpec(configChanges = listOf("raise selector budget")),
                                metrics = OverseerExperimentMetrics(primary = listOf("visibility")),
                                acceptanceCriteria = OverseerAcceptanceCriteria(predicates = listOf("visibility > 0.5")),
                                maxCost = 1.0
                            )
                        )
                    )
                )
                .build(),
            HttpResponse.BodyHandlers.ofString()
        )
        assertEquals(200, experimentPlanResponse.statusCode())
        val plannedState = json.decodeFromString<OverseerKernelState>(experimentPlanResponse.body())
        assertTrue(plannedState.experimentPlans.containsKey("EXP-ipc-1"))

        val scheduleResponse = httpClient.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$port/overseer/experiment-plans/schedule"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString()
        )
        assertEquals(200, scheduleResponse.statusCode())
        val scheduledState = json.decodeFromString<OverseerKernelState>(scheduleResponse.body())
        assertTrue(scheduledState.experimentSchedules.containsKey("EXP-ipc-1"))
        assertEquals(
            listOf("EXP-ipc-1-run-01"),
            scheduledState.experimentSchedules.getValue("EXP-ipc-1").scheduledRunIds
        )

        val overseerClient = OverseerKernelClient(baseUrl = "http://localhost:$port", httpClient = httpClient)
        val launchResult = overseerClient.launchExperimentRun(
            experimentPlanId = "EXP-ipc-1",
            launchRequest = OverseerExperimentLaunchRequest(
                scheduledRunId = "EXP-ipc-1-run-01",
                runConfig = RunConfig(
                    runId = "EXP-ipc-1-run-01",
                    objective = "ipc overseer launch test",
                    groupCount = 2,
                    groupProfiles = listOf(GroupProfileType.Library, GroupProfileType.Factory),
                    rounds = 1,
                    roundDurationMinutes = 0,
                    maxAgentsPerGroup = 1,
                    scoringDimensions = listOf(
                        ScoringDimension(
                            id = "accuracy",
                            name = "Accuracy",
                            description = "Reward accurate outputs.",
                            weight = 1.0
                        )
                    ),
                    openRouterApiKey = "sk-test",
                    isTestRun = true
                )
            )
        )
        assertEquals("EXP-ipc-1", launchResult.experimentPlanId)
        assertEquals("EXP-ipc-1-run-01", launchResult.scheduledRunId)
        assertEquals("EXP-ipc-1-run-01", launchResult.runId)
        assertEquals("EXP-ipc-1-run-01", engineService.capturedConfig?.runId)

        val launchedState = overseerClient.getState()
        assertNotNull(launchedState.experimentSchedules.getValue("EXP-ipc-1").launchedAtMs)

        assertFailsWith<IllegalArgumentException> {
            overseerClient.launchExperimentRun(
                experimentPlanId = "EXP-ipc-1",
                launchRequest = OverseerExperimentLaunchRequest(
                    scheduledRunId = "EXP-ipc-1-run-01",
                    runConfig = RunConfig(
                        runId = "EXP-ipc-1-run-01",
                        objective = "ipc overseer launch test",
                        groupCount = 2,
                        groupProfiles = listOf(GroupProfileType.Library, GroupProfileType.Factory),
                        rounds = 1,
                        roundDurationMinutes = 0,
                        maxAgentsPerGroup = 1,
                        scoringDimensions = listOf(
                            ScoringDimension(
                                id = "accuracy",
                                name = "Accuracy",
                                description = "Reward accurate outputs.",
                                weight = 1.0
                            )
                        ),
                        openRouterApiKey = "sk-test",
                        isTestRun = true
                    )
                )
            )
        }

        val experimentResultResponse = httpClient.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$port/overseer/experiment-results"))
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        json.encodeToString(
                            OverseerExperimentResult(
                                experimentPlanId = "EXP-ipc-1",
                                hypothesisId = "HYP-ipc-1",
                                outcome = OverseerExperimentOutcome.ACCEPTED,
                                metricValues = mapOf("visibility" to 0.91)
                            )
                        )
                    )
                )
                .build(),
            HttpResponse.BodyHandlers.ofString()
        )
        assertEquals(200, experimentResultResponse.statusCode())
        val resultState = json.decodeFromString<OverseerKernelState>(experimentResultResponse.body())
        assertEquals(OverseerHypothesisStatus.ACCEPTED, resultState.hypotheses.getValue("HYP-ipc-1").status)

        val deadEndResponse = httpClient.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$port/overseer/dead-ends"))
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        json.encodeToString(
                            OverseerDeadEnd(
                                hypothesisId = "HYP-ipc-1",
                                reason = "Captured for regression coverage."
                            )
                        )
                    )
                )
                .build(),
            HttpResponse.BodyHandlers.ofString()
        )
        assertEquals(200, deadEndResponse.statusCode())
        val deadEndedState = json.decodeFromString<OverseerKernelState>(deadEndResponse.body())
        assertTrue(deadEndedState.deadEnds.containsKey("HYP-ipc-1"))
        val planHistory = overseerClient.getExperimentPlanHistory("EXP-ipc-1")
        assertTrue(planHistory.any { it.actionType == OverseerExperimentPlanHistoryActionType.REGISTERED })
        assertTrue(planHistory.any { it.actionType == OverseerExperimentPlanHistoryActionType.SCHEDULED })
        assertTrue(planHistory.any { it.actionType == OverseerExperimentPlanHistoryActionType.LAUNCHED })
        assertTrue(planHistory.any { it.actionType == OverseerExperimentPlanHistoryActionType.RESULT_RECORDED })
        assertTrue(overseerClient.getDeadEndHistory("HYP-ipc-1").isNotEmpty())
    }

    @Test
    fun `overseer launch endpoint rejects invalid launch requests without starting a run`() = runBlocking {
        val port = freePort()
        val engineService = FakeEngineService()
        server = EvoMoliorIpcServer(engineService = engineService, port = port).also { it.start() }

        waitForServer(port)

        val httpClient = HttpClient.newHttpClient()
        val client = OverseerKernelClient(baseUrl = "http://localhost:$port", httpClient = httpClient)

        val hypothesis = OverseerHypothesis(
            id = "HYP-launch-invalid",
            title = "Invalid launch hypothesis",
            origin = OverseerOrigin(
                type = OverseerOriginType.INTERNAL_LOGS,
                sources = listOf("documentation/EvoMolior_Current_Session_Handoff.md")
            ),
            targetScope = OverseerTargetScope(
                paths = listOf("evomolior/core/src/main/resources/overseer/policy.json"),
                mutabilityLevel = OverseerMutabilityLevel.SEMI_MUTABLE
            ),
            rationale = "Exercise launch validation.",
            expectedEffect = OverseerExpectedEffect(metricsUp = listOf("visibility"))
        )
        client.formalizeHypothesis(hypothesis)
        client.registerExperimentPlan(
            OverseerExperimentPlan(
                id = "EXP-launch-invalid",
                hypothesisId = hypothesis.id,
                runCount = 1,
                generationsPerRun = 1,
                variationSpec = OverseerVariationSpec(configChanges = listOf("raise selector budget")),
                metrics = OverseerExperimentMetrics(primary = listOf("visibility")),
                acceptanceCriteria = OverseerAcceptanceCriteria(predicates = listOf("visibility > 0.5")),
                maxCost = 1.0
            )
        )
        client.scheduleExperimentPlans()

        assertFailsWith<IllegalArgumentException> {
            client.launchExperimentRun(
                experimentPlanId = "missing-plan",
                launchRequest = OverseerExperimentLaunchRequest(
                    scheduledRunId = "missing-run",
                    runConfig = RunConfig(
                        runId = "missing-run",
                        objective = "invalid launch",
                        groupCount = 2,
                        groupProfiles = listOf(GroupProfileType.Library, GroupProfileType.Factory),
                        rounds = 1,
                        roundDurationMinutes = 0,
                        maxAgentsPerGroup = 1,
                        scoringDimensions = listOf(
                            ScoringDimension(
                                id = "accuracy",
                                name = "Accuracy",
                                description = "Reward accurate outputs.",
                                weight = 1.0
                            )
                        ),
                        openRouterApiKey = "sk-test",
                        isTestRun = true
                    )
                )
            )
        }

        assertFailsWith<IllegalArgumentException> {
            client.launchExperimentRun(
                experimentPlanId = "EXP-launch-invalid",
                launchRequest = OverseerExperimentLaunchRequest(
                    scheduledRunId = "EXP-launch-invalid-run-01",
                    runConfig = RunConfig(
                        runId = "EXP-launch-invalid-run-99",
                        objective = "invalid launch",
                        groupCount = 2,
                        groupProfiles = listOf(GroupProfileType.Library, GroupProfileType.Factory),
                        rounds = 1,
                        roundDurationMinutes = 0,
                        maxAgentsPerGroup = 1,
                        scoringDimensions = listOf(
                            ScoringDimension(
                                id = "accuracy",
                                name = "Accuracy",
                                description = "Reward accurate outputs.",
                                weight = 1.0
                            )
                        ),
                        openRouterApiKey = "sk-test",
                        isTestRun = true
                    )
                )
            )
        }

        assertFailsWith<IllegalArgumentException> {
            client.launchExperimentRun(
                experimentPlanId = "EXP-launch-invalid",
                launchRequest = OverseerExperimentLaunchRequest(
                    scheduledRunId = "EXP-launch-invalid-run-99",
                    runConfig = RunConfig(
                        runId = "EXP-launch-invalid-run-99",
                        objective = "invalid launch",
                        groupCount = 2,
                        groupProfiles = listOf(GroupProfileType.Library, GroupProfileType.Factory),
                        rounds = 1,
                        roundDurationMinutes = 0,
                        maxAgentsPerGroup = 1,
                        scoringDimensions = listOf(
                            ScoringDimension(
                                id = "accuracy",
                                name = "Accuracy",
                                description = "Reward accurate outputs.",
                                weight = 1.0
                            )
                        ),
                        openRouterApiKey = "sk-test",
                        isTestRun = true
                    )
                )
            )
        }

        assertEquals(null, engineService.capturedConfig)
    }

    @Test
    fun `multipart run start bridges uploaded artifacts into the run config`() = runBlocking {
        val port = freePort()
        val engineService = FakeEngineService()
        server = EvoMoliorIpcServer(engineService = engineService, port = port).also { it.start() }

        waitForServer(port)

        val boundary = "----evomolior-boundary"
        val configJson = json.encodeToString(
            RunConfig(
                runId = "run-multipart",
                objective = "multipart upload test",
                groupCount = 2,
                groupProfiles = listOf(GroupProfileType.Library, GroupProfileType.Factory),
                rounds = 1,
                roundDurationMinutes = 0,
                maxAgentsPerGroup = 1,
                scoringDimensions = listOf(
                    ScoringDimension(
                        id = "accuracy",
                        name = "Accuracy",
                        description = "Reward accurate outputs.",
                        weight = 1.0
                    )
                ),
                openRouterApiKey = "sk-test"
            )
        )

        val multipartBody = buildString {
            append("--$boundary\r\n")
            append("""Content-Disposition: form-data; name="config"""")
            append("\r\n")
            append("Content-Type: application/json\r\n\r\n")
            append(configJson)
            append("\r\n")
            append("--$boundary\r\n")
            append("""Content-Disposition: form-data; name="artifact"; filename="docs/context.txt"""")
            append("\r\n")
            append("X-Relative-Path: docs/context.txt\r\n")
            append("Content-Type: text/plain\r\n\r\n")
            append("hello multipart bridge")
            append("\r\n--$boundary--\r\n")
        }.toByteArray(StandardCharsets.UTF_8)

        val response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$port/run/start"))
                .header("Content-Type", "multipart/form-data; boundary=$boundary")
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipartBody))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        )

        assertEquals(200, response.statusCode())
        assertTrue(response.body().contains("run-multipart"))

        withTimeout(5_000) {
            while (engineService.capturedConfig == null) {
                kotlinx.coroutines.delay(25)
            }
        }

        val captured = engineService.capturedConfig!!
        assertEquals(1, captured.contextArtifacts.size)
        assertEquals("docs/context.txt", captured.contextArtifacts.single().relativePath)
        assertEquals("hello multipart bridge", String(Base64.getDecoder().decode(captured.contextArtifacts.single().contentBase64)))
    }

    @Test
    fun `multipart run start accepts artifacts larger than the previous 10 MiB cap`() = runBlocking {
        val port = freePort()
        val engineService = FakeEngineService()
        server = EvoMoliorIpcServer(engineService = engineService, port = port).also { it.start() }

        waitForServer(port)

        val boundary = "----evomolior-large-boundary"
        val largePayload = "a".repeat(11 * 1024 * 1024)
        val configJson = json.encodeToString(
            RunConfig(
                runId = "run-large-upload",
                objective = "large upload test",
                groupCount = 2,
                groupProfiles = listOf(GroupProfileType.Library, GroupProfileType.Factory),
                rounds = 1,
                roundDurationMinutes = 0,
                maxAgentsPerGroup = 1,
                scoringDimensions = listOf(
                    ScoringDimension(
                        id = "accuracy",
                        name = "Accuracy",
                        description = "Reward accurate outputs.",
                        weight = 1.0
                    )
                ),
                openRouterApiKey = "sk-test"
            )
        )

        val multipartBody = buildString {
            append("--$boundary\r\n")
            append("""Content-Disposition: form-data; name="config"""")
            append("\r\n")
            append("Content-Type: application/json\r\n\r\n")
            append(configJson)
            append("\r\n")
            append("--$boundary\r\n")
            append("""Content-Disposition: form-data; name="artifact"; filename="docs/large.txt"""")
            append("\r\n")
            append("X-Relative-Path: docs/large.txt\r\n")
            append("Content-Type: text/plain\r\n\r\n")
            append(largePayload)
            append("\r\n--$boundary--\r\n")
        }.toByteArray(StandardCharsets.UTF_8)

        val response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$port/run/start"))
                .header("Content-Type", "multipart/form-data; boundary=$boundary")
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipartBody))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        )

        assertEquals(200, response.statusCode())
        assertTrue(response.body().contains("run-large-upload"))

        withTimeout(5_000) {
            while (engineService.capturedConfig == null) {
                kotlinx.coroutines.delay(25)
            }
        }

        val captured = engineService.capturedConfig!!
        assertEquals(1, captured.contextArtifacts.size)
        assertEquals("docs/large.txt", captured.contextArtifacts.single().relativePath)
        assertEquals(largePayload.length, String(Base64.getDecoder().decode(captured.contextArtifacts.single().contentBase64)).length)
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private suspend fun waitForServer(port: Int) {
        val client = HttpClient.newHttpClient()
        withTimeout(5_000) {
            while (true) {
                val response = runCatching {
                    client.send(
                        HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:$port/state"))
                            .GET()
                            .build(),
                        HttpResponse.BodyHandlers.ofString()
                    )
                }

                if (response.getOrNull()?.statusCode() == 200) return@withTimeout
                delay(50)
            }
        }
    }

    private class FakeEngineService(
        private val resultsBundleBytes: ByteArray? = null
    ) : EvoMoliorEngineService {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val eventsFlow = MutableSharedFlow<EvoMoliorEvent>(extraBufferCapacity = 16)
        private var overseerKernelState = OverseerKernelState()

        @Volatile
        private var state: EngineState = EngineState(
            status = EngineStatus.IDLE,
            activeRunId = null,
            currentRound = null,
            groupSummaries = emptyList()
        )

        @Volatile
        private var summary: MetaArchiveSummary? = null

        @Volatile
        var capturedConfig: RunConfig? = null

        override val events = eventsFlow.asSharedFlow()

        override suspend fun startRun(config: RunConfig): String {
            capturedConfig = config
            state = EngineState(
                status = EngineStatus.RUNNING,
                activeRunId = config.runId,
                currentRound = 0,
                groupSummaries = emptyList()
            )
            eventsFlow.emit(EvoMoliorEvent.RunStarted(config.runId, config.objective))
            scope.launch {
                delay(150)
                eventsFlow.emit(EvoMoliorEvent.RoundStarted(config.runId, 0))
                delay(150)
                val gradingResult = GradingResult(
                    roundIndex = 0,
                    scores = listOf(
                        RoundScore(
                            groupId = "group-0",
                            roundIndex = 0,
                            dimensionScores = listOf(
                                DimensionScore(
                                    dimensionId = "accuracy",
                                    rawScore = 88.0,
                                    justification = "Good enough for a smoke test."
                                )
                            ),
                            weightedTotal = 88.0,
                            rank = 1
                        ),
                        RoundScore(
                            groupId = "group-1",
                            roundIndex = 0,
                            dimensionScores = listOf(
                                DimensionScore(
                                    dimensionId = "accuracy",
                                    rawScore = 72.0,
                                    justification = "Runner-up."
                                )
                            ),
                            weightedTotal = 72.0,
                            rank = 2
                        )
                    ),
                    winnerId = "group-0"
                )
                eventsFlow.emit(EvoMoliorEvent.RoundCompleted(config.runId, gradingResult))
                summary = MetaArchiveSummary(
                    total = 3,
                    byType = mapOf(
                        "STARTING_FIELD" to 1,
                        "ROUND_RESULT" to 1,
                        "RUN_COMPLETED" to 1
                    )
                )
                state = state.copy(status = EngineStatus.COMPLETED)
                eventsFlow.emit(EvoMoliorEvent.RunCompleted(config.runId, gradingResult.winnerId))
            }
            return config.runId
        }

        override suspend fun pauseRun(runId: String) = Unit

        override suspend fun resumeRun(runId: String) = Unit

        override suspend fun abortRun(runId: String, reason: String) = Unit

        override suspend fun getState(): EngineState = state

        override suspend fun getMetaArchiveSummary(runId: String): MetaArchiveSummary? = summary

        override suspend fun getMetaArchiveEntries(runId: String, entryType: String?): List<MetaArchiveEntryRecord> = emptyList()

        override suspend fun getResultsBundle(runId: String): ByteArray? = resultsBundleBytes

        override suspend fun getOverseerKernelState(): OverseerKernelState = overseerKernelState

        override suspend fun preflightOverseerHypothesis(hypothesis: OverseerHypothesis): OverseerKernelEligibility =
            OverseerKernelEligibility(allowed = true)

        override suspend fun preflightOverseerExperimentPlan(experimentPlan: OverseerExperimentPlan): OverseerExperimentPlanEligibility =
            OverseerExperimentPlanEligibility(allowed = true)

        override suspend fun formalizeOverseerHypothesis(hypothesis: OverseerHypothesis): OverseerKernelState {
            overseerKernelState = overseerKernelState.copy(
                hypotheses = overseerKernelState.hypotheses + (
                    hypothesis.id to hypothesis.copy(
                        status = OverseerHypothesisStatus.FORMALIZED
                    )
                )
            )
            return overseerKernelState
        }

        override suspend fun registerOverseerExperimentPlan(experimentPlan: OverseerExperimentPlan): OverseerKernelState {
            overseerKernelState = overseerKernelState.copy(
                experimentPlans = overseerKernelState.experimentPlans + (experimentPlan.id to experimentPlan),
                hypotheses = overseerKernelState.hypotheses + (
                    experimentPlan.hypothesisId to overseerKernelState.hypotheses.getValue(experimentPlan.hypothesisId).copy(
                        experimentPlanId = experimentPlan.id,
                        status = OverseerHypothesisStatus.UNDER_EXPERIMENT
                    )
                ),
                experimentPlanHistory = overseerKernelState.experimentPlanHistory + (
                    experimentPlan.id to (
                        overseerKernelState.experimentPlanHistory[experimentPlan.id].orEmpty() +
                            com.achulkov.evomolior.core.contract.OverseerExperimentPlanHistoryEntry(
                                experimentPlanId = experimentPlan.id,
                                hypothesisId = experimentPlan.hypothesisId,
                                actionType = com.achulkov.evomolior.core.contract.OverseerExperimentPlanHistoryActionType.REGISTERED
                            )
                    )
                )
            )
            return overseerKernelState
        }

        override suspend fun scheduleOverseerExperimentPlans(): OverseerKernelState {
            val scheduled = overseerKernelState.experimentPlans.values
                .sortedBy { it.id }
                .filter { overseerKernelState.experimentSchedules[it.id] == null }
                .filter { plan ->
                    val hypothesis = overseerKernelState.hypotheses[plan.hypothesisId]
                    hypothesis != null &&
                        hypothesis.status in setOf(OverseerHypothesisStatus.FORMALIZED, OverseerHypothesisStatus.UNDER_EXPERIMENT)
                }
                .associate { plan ->
                    plan.id to com.achulkov.evomolior.core.contract.OverseerExperimentSchedule(
                        experimentPlanId = plan.id,
                        hypothesisId = plan.hypothesisId,
                        runCount = plan.runCount,
                        generationsPerRun = plan.generationsPerRun,
                        scheduledRunIds = (1..plan.runCount).map { index -> "${plan.id}-run-${index.toString().padStart(2, '0')}" }
                    )
                }
            overseerKernelState = if (scheduled.isEmpty()) {
                overseerKernelState
            } else {
                overseerKernelState.copy(
                    experimentSchedules = overseerKernelState.experimentSchedules + scheduled,
                    experimentPlanHistory = scheduled.values.fold(overseerKernelState.experimentPlanHistory) { acc, plan ->
                        acc + (
                            plan.experimentPlanId to (
                                acc[plan.experimentPlanId].orEmpty() +
                                    com.achulkov.evomolior.core.contract.OverseerExperimentPlanHistoryEntry(
                                        experimentPlanId = plan.experimentPlanId,
                                        hypothesisId = plan.hypothesisId,
                                        actionType = com.achulkov.evomolior.core.contract.OverseerExperimentPlanHistoryActionType.SCHEDULED,
                                        note = "scheduled"
                                    )
                            )
                        )
                    }
                )
            }
            return overseerKernelState
        }

        override suspend fun launchOverseerExperimentRun(
            experimentPlanId: String,
            launchRequest: OverseerExperimentLaunchRequest
        ): OverseerExperimentLaunchResponse {
            val schedule = overseerKernelState.experimentSchedules.getValue(experimentPlanId)
            check(launchRequest.scheduledRunId in schedule.scheduledRunIds) {
                "Scheduled run '${launchRequest.scheduledRunId}' is not registered for experiment plan '$experimentPlanId'."
            }
            check(launchRequest.runConfig.runId == launchRequest.scheduledRunId)
            check(schedule.launchedAtMs == null) {
                "Experiment plan '$experimentPlanId' has already been launched."
            }
            overseerKernelState = overseerKernelState.copy(
                experimentSchedules = overseerKernelState.experimentSchedules + (
                    experimentPlanId to schedule.copy(launchedAtMs = System.currentTimeMillis())
                ),
                experimentPlanHistory = overseerKernelState.experimentPlanHistory + (
                    experimentPlanId to (
                        overseerKernelState.experimentPlanHistory[experimentPlanId].orEmpty() +
                            com.achulkov.evomolior.core.contract.OverseerExperimentPlanHistoryEntry(
                                experimentPlanId = experimentPlanId,
                                hypothesisId = schedule.hypothesisId,
                                actionType = com.achulkov.evomolior.core.contract.OverseerExperimentPlanHistoryActionType.LAUNCHED,
                                scheduledRunId = launchRequest.scheduledRunId,
                                note = "Launched at ${System.currentTimeMillis()}"
                            )
                    )
                )
            )
            return OverseerExperimentLaunchResponse(
                experimentPlanId = experimentPlanId,
                scheduledRunId = launchRequest.scheduledRunId,
                runId = startRun(launchRequest.runConfig)
            )
        }

        override suspend fun recordOverseerExperimentResult(experimentResult: OverseerExperimentResult): OverseerKernelState {
            overseerKernelState = overseerKernelState.copy(
                experimentResults = overseerKernelState.experimentResults + (experimentResult.experimentPlanId to experimentResult),
                hypotheses = overseerKernelState.hypotheses + (
                    experimentResult.hypothesisId to overseerKernelState.hypotheses.getValue(experimentResult.hypothesisId).copy(
                        status = when (experimentResult.outcome) {
                            OverseerExperimentOutcome.ACCEPTED -> OverseerHypothesisStatus.ACCEPTED
                            OverseerExperimentOutcome.REJECTED -> if (experimentResult.counterexamplePaths.isNotEmpty()) {
                                OverseerHypothesisStatus.DEAD_END
                            } else {
                                OverseerHypothesisStatus.REJECTED
                            }
                        }
                    )
                ),
                deadEnds = if (experimentResult.outcome == OverseerExperimentOutcome.REJECTED &&
                    experimentResult.counterexamplePaths.isNotEmpty()
                ) {
                    overseerKernelState.deadEnds + (
                        experimentResult.hypothesisId to OverseerDeadEnd(
                            hypothesisId = experimentResult.hypothesisId,
                            reason = experimentResult.note.ifBlank { "Captured for regression coverage." },
                            counterexamplePaths = experimentResult.counterexamplePaths
                        )
                    )
                } else {
                    overseerKernelState.deadEnds
                },
                experimentPlanHistory = overseerKernelState.experimentPlanHistory + (
                    experimentResult.experimentPlanId to (
                        overseerKernelState.experimentPlanHistory[experimentResult.experimentPlanId].orEmpty() +
                            com.achulkov.evomolior.core.contract.OverseerExperimentPlanHistoryEntry(
                                experimentPlanId = experimentResult.experimentPlanId,
                                hypothesisId = experimentResult.hypothesisId,
                                actionType = com.achulkov.evomolior.core.contract.OverseerExperimentPlanHistoryActionType.RESULT_RECORDED,
                                note = experimentResult.outcome.name
                            )
                    )
                ),
                deadEndHistory = if (experimentResult.outcome == OverseerExperimentOutcome.REJECTED &&
                    experimentResult.counterexamplePaths.isNotEmpty()
                ) {
                    overseerKernelState.deadEndHistory + (
                        experimentResult.hypothesisId to (
                            overseerKernelState.deadEndHistory[experimentResult.hypothesisId].orEmpty() +
                                com.achulkov.evomolior.core.contract.OverseerDeadEndHistoryEntry(
                                    hypothesisId = experimentResult.hypothesisId,
                                    reason = experimentResult.note.ifBlank { "Captured for regression coverage." },
                                    counterexamplePaths = experimentResult.counterexamplePaths,
                                    source = com.achulkov.evomolior.core.contract.OverseerDeadEndHistorySource.EXPERIMENT_RESULT
                                )
                        )
                    )
                } else {
                    overseerKernelState.deadEndHistory
                }
            )
            return overseerKernelState
        }

        override suspend fun recordOverseerDeadEnd(deadEnd: OverseerDeadEnd): OverseerKernelState {
            overseerKernelState = overseerKernelState.copy(
                deadEnds = overseerKernelState.deadEnds + (deadEnd.hypothesisId to deadEnd),
                deadEndHistory = overseerKernelState.deadEndHistory + (
                    deadEnd.hypothesisId to (
                        overseerKernelState.deadEndHistory[deadEnd.hypothesisId].orEmpty() +
                            com.achulkov.evomolior.core.contract.OverseerDeadEndHistoryEntry(
                                hypothesisId = deadEnd.hypothesisId,
                                reason = deadEnd.reason,
                                counterexamplePaths = deadEnd.counterexamplePaths,
                                source = com.achulkov.evomolior.core.contract.OverseerDeadEndHistorySource.DIRECT_RECORD,
                                recordedAtMs = deadEnd.recordedAtMs
                            )
                    )
                )
            )
            return overseerKernelState
        }

        override suspend fun getOverseerAuditEntries(): List<com.achulkov.evomolior.core.contract.OverseerKernelAuditEntry> =
            emptyList()

        override suspend fun getOverseerExperimentPlanHistory(experimentPlanId: String): List<com.achulkov.evomolior.core.contract.OverseerExperimentPlanHistoryEntry> =
            overseerKernelState.experimentPlanHistory[experimentPlanId].orEmpty()

        override suspend fun getOverseerDeadEndHistory(hypothesisId: String): List<com.achulkov.evomolior.core.contract.OverseerDeadEndHistoryEntry> =
            overseerKernelState.deadEndHistory[hypothesisId].orEmpty()

        override suspend fun getOverseerAuditArchiveEntries(): List<MetaArchiveEntryRecord> =
            emptyList()
    }

    private fun zipBytes(vararg entries: Pair<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (path, content) ->
                zip.putNextEntry(java.util.zip.ZipEntry(path))
                zip.write(content.toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun unzipEntries(bytes: ByteArray): Map<String, String> {
        val result = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entry ->
                result[entry.name] = zip.readBytes().toString(StandardCharsets.UTF_8)
                zip.closeEntry()
            }
        }
        return result
    }

}
