package com.achulkov.evokammer.api.service

import com.achulkov.evokammer.api.events.EvoKammerEvent
import com.achulkov.evokammer.api.model.RunConfig
import io.github.aakira.napier.Napier
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
                    val body = call.receiveText()
                    val config = json.decodeFromString<RunConfig>(body)
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
