package com.achulkov.evokammer.api.service

import com.achulkov.evokammer.api.events.EvoKammerEvent
import com.achulkov.evokammer.api.model.RunConfig
import com.achulkov.evokammer.api.model.RunInputArtifactUpload
import com.achulkov.evokammer.core.model.DimensionScore
import com.achulkov.evokammer.core.model.GradingResult
import com.achulkov.evokammer.core.model.GroupProfileType
import com.achulkov.evokammer.core.model.RoundScore
import com.achulkov.evokammer.core.model.ScoringDimension
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
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Smoke test for the client-facing IPC bridge.
 *
 * Verifies that a caller can start a run over HTTP, observe lifecycle events over WebSocket,
 * and retrieve both live state and the completed-run archive summary through HTTP.
 */
class EvoKammerIpcServerSmokeTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private var server: EvoKammerIpcServer? = null

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
        server = EvoKammerIpcServer(engineService = engineService, port = port).also { it.start() }

        waitForServer(port)

        val runStarted = CompletableDeferred<EvoKammerEvent.RunStarted>()
        val roundCompleted = CompletableDeferred<EvoKammerEvent.RoundCompleted>()
        val runCompleted = CompletableDeferred<EvoKammerEvent.RunCompleted>()
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
                            val event = json.decodeFromString<EvoKammerEvent>(data.toString())
                            if (event is EvoKammerEvent.RunStarted) {
                                runStarted.complete(event)
                            } else if (event is EvoKammerEvent.RoundCompleted) {
                                roundCompleted.complete(event)
                            } else if (event is EvoKammerEvent.RunCompleted) {
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
    fun `multipart run start bridges uploaded artifacts into the run config`() = runBlocking {
        val port = freePort()
        val engineService = FakeEngineService()
        server = EvoKammerIpcServer(engineService = engineService, port = port).also { it.start() }

        waitForServer(port)

        val boundary = "----evokammer-boundary"
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
        server = EvoKammerIpcServer(engineService = engineService, port = port).also { it.start() }

        waitForServer(port)

        val boundary = "----evokammer-large-boundary"
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

    private class FakeEngineService : EvoKammerEngineService {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val eventsFlow = MutableSharedFlow<EvoKammerEvent>(extraBufferCapacity = 16)

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
            eventsFlow.emit(EvoKammerEvent.RunStarted(config.runId, config.objective))
            scope.launch {
                delay(150)
                eventsFlow.emit(EvoKammerEvent.RoundStarted(config.runId, 0))
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
                eventsFlow.emit(EvoKammerEvent.RoundCompleted(config.runId, gradingResult))
                summary = MetaArchiveSummary(
                    total = 3,
                    byType = mapOf(
                        "STARTING_FIELD" to 1,
                        "ROUND_RESULT" to 1,
                        "RUN_COMPLETED" to 1
                    )
                )
                state = state.copy(status = EngineStatus.COMPLETED)
                eventsFlow.emit(EvoKammerEvent.RunCompleted(config.runId, gradingResult.winnerId))
            }
            return config.runId
        }

        override suspend fun pauseRun(runId: String) = Unit

        override suspend fun resumeRun(runId: String) = Unit

        override suspend fun abortRun(runId: String, reason: String) = Unit

        override suspend fun getState(): EngineState = state

        override suspend fun getMetaArchiveSummary(runId: String): MetaArchiveSummary? = summary

        override suspend fun getMetaArchiveEntries(runId: String, entryType: String?): List<MetaArchiveEntryRecord> = emptyList()

        override suspend fun getResultsBundle(runId: String): ByteArray? = null
    }

}
