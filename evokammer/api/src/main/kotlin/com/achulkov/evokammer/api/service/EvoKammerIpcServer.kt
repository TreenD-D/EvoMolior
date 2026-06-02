package com.achulkov.evokammer.api.service

import com.achulkov.evokammer.api.events.EvoKammerEvent
import com.achulkov.evokammer.api.model.RunConfig
import com.achulkov.evokammer.api.model.RunInputArtifactUpload
import io.github.aakira.napier.Napier
import io.ktor.http.ContentType
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Base64
import io.ktor.utils.io.readRemaining
import io.ktor.utils.io.core.readBytes

private const val MAX_RUN_ARTIFACT_UPLOAD_BYTES = 50L * 1024L * 1024L

/**
 * Ktor Netty WebSocket server that bridges the in-process [EvoKammerEngineService]
 * to the Compose Desktop UI (or any external client).
 *
 * Protocol:
 * - `GET /ws/events` — WebSocket: server pushes [EvoKammerEvent] JSON frames
 * - `POST /run/start` — starts a run (body: [RunConfig] JSON)
 * - `POST /run/pause` — pauses the active run (body: `{"runId": "..."}`)
 * - `POST /run/resume` — resumes the active run
 * - `POST /run/abort` — aborts the active run (body: `{"runId": "...", "reason": "..."}`)
 * - `GET /state` — returns current [EngineState] JSON
 * - `GET /runs/{runId}/meta-archive-summary` — returns completed-run [MetaArchiveSummary] JSON
 * - `GET /runs/{runId}/meta-archive-entries?type=...` — returns archived entry records
 *
 * @param engineService The in-process [EvoKammerEngineService] implementation.
 * @param port HTTP/WS listen port (default: 7400).
 */
class EvoKammerIpcServer(
    private val engineService: EvoKammerEngineService,
    private val port: Int = 7400
) {

    private val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private var server: EmbeddedServer<*, *>? = null

    fun start() {
        val svc = engineService  // capture to avoid Ktor DSL shadowing
        server = embeddedServer(Netty, port = port) {
            install(ContentNegotiation) { json(json) }
            install(WebSockets)
            routing {
                webSocket("/ws/events") {
                    Napier.i("IpcServer: new WebSocket client connected")
                    val wsSession = this
                    val job = serverScope.launch {
                        svc.events.collect { event ->
                            val frame = json.encodeToString(event)
                            wsSession.send(Frame.Text(frame))
                        }
                    }
                    for (frame in incoming) {
                        // Consume pings / ignore client messages on events channel
                    }
                    job.cancel()
                    Napier.i("IpcServer: WebSocket client disconnected")
                }

                post("/run/start") {
                    val config = when {
                        call.request.contentType().match(ContentType.MultiPart.FormData) -> {
                            val multipart = call.receiveMultipart()
                            var parsedConfig: RunConfig? = null
                            val uploads = mutableListOf<RunInputArtifactUpload>()

                            multipart.forEachPart { part ->
                                when (part) {
                                    is PartData.FormItem -> {
                                        if (part.name == "config") {
                                            parsedConfig = json.decodeFromString<RunConfig>(part.value)
                                        }
                                    }
                                    is PartData.FileItem -> {
                                        val relativePath = part.headers["X-Relative-Path"]
                                            ?: part.originalFileName
                                            ?: error("Uploaded artifact is missing a relative path")
                                        val mimeType = part.contentType?.toString() ?: "application/octet-stream"
                                        val bytes = part.provider().readRemaining().readBytes()
                                        require(bytes.size.toLong() <= MAX_RUN_ARTIFACT_UPLOAD_BYTES) {
                                            "Uploaded artifact '$relativePath' exceeds the ${MAX_RUN_ARTIFACT_UPLOAD_BYTES / (1024 * 1024)} MiB limit"
                                        }
                                        uploads += RunInputArtifactUpload(
                                            relativePath = relativePath,
                                            sourceName = part.originalFileName ?: relativePath,
                                            mimeType = mimeType,
                                            contentBase64 = Base64.getEncoder().encodeToString(bytes),
                                            kind = inferArtifactKind(mimeType, relativePath)
                                        )
                                    }
                                    else -> Unit
                                }
                            }

                            val baseConfig = parsedConfig ?: error("Multipart run start is missing a config part")
                            baseConfig.copy(contextArtifacts = baseConfig.contextArtifacts + uploads)
                        }
                        else -> json.decodeFromString<RunConfig>(call.receiveText())
                    }

                    val runId = svc.startRun(config)
                    call.respondText("""{"runId":"$runId"}""")
                }

                post("/run/pause") {
                    val body = call.receiveText()
                    val runId = json.decodeFromString<Map<String, String>>(body)["runId"]!!
                    svc.pauseRun(runId)
                    call.respondText("""{"ok":true}""")
                }

                post("/run/resume") {
                    val body = call.receiveText()
                    val runId = json.decodeFromString<Map<String, String>>(body)["runId"]!!
                    svc.resumeRun(runId)
                    call.respondText("""{"ok":true}""")
                }

                post("/run/abort") {
                    val body = call.receiveText()
                    val map = json.decodeFromString<Map<String, String>>(body)
                    svc.abortRun(map["runId"]!!, map["reason"] ?: "user_requested")
                    call.respondText("""{"ok":true}""")
                }

                get("/state") {
                    val state = svc.getState()
                    call.respondText(json.encodeToString(state))
                }

                get("/runs/{runId}/meta-archive-summary") {
                    val runId = call.parameters["runId"]
                        ?: error("runId path parameter is required")
                    val summary = svc.getMetaArchiveSummary(runId)
                    if (summary != null) {
                        call.respondText(json.encodeToString(summary))
                    } else {
                        call.respondText("""{"error":"summary_unavailable"}""", status = io.ktor.http.HttpStatusCode.NotFound)
                    }
                }

                get("/runs/{runId}/meta-archive-entries") {
                    val runId = call.parameters["runId"]
                        ?: error("runId path parameter is required")
                    val entryType = call.request.queryParameters["type"]
                    val entries = svc.getMetaArchiveEntries(runId, entryType)
                    call.respondText(json.encodeToString(entries))
                }

                get("/runs/{runId}/results/download") {
                    val runId = call.parameters["runId"] ?: error("runId path parameter is required")
                    val bytes = svc.getResultsBundle(runId)
                    if (bytes != null) {
                        call.response.header(
                            io.ktor.http.HttpHeaders.ContentDisposition,
                            "attachment; filename=\"results-$runId.zip\""
                        )
                        call.respondBytes(bytes, ContentType.Application.Zip)
                    } else {
                        call.respondText("""{"error":"bundle_unavailable"}""", status = io.ktor.http.HttpStatusCode.NotFound)
                    }
                }
            }
        }.start(wait = false)
        Napier.i("EvoKammerIpcServer started on ws://localhost:$port")
    }

    fun stop() {
        server?.stop(1000, 5000)
        serverScope.cancel()
        Napier.i("EvoKammerIpcServer stopped")
    }
}

private fun inferArtifactKind(mimeType: String, relativePath: String): com.achulkov.evokammer.core.contract.RunInputArtifactKind = when {
    mimeType.startsWith("text/") || mimeType == "application/json" || relativePath.endsWith(".md") ||
        relativePath.endsWith(".txt") || relativePath.endsWith(".json") || relativePath.endsWith(".yaml") ||
        relativePath.endsWith(".yml") -> com.achulkov.evokammer.core.contract.RunInputArtifactKind.TEXT
    mimeType.startsWith("image/") -> com.achulkov.evokammer.core.contract.RunInputArtifactKind.IMAGE
    else -> com.achulkov.evokammer.core.contract.RunInputArtifactKind.BINARY
}
