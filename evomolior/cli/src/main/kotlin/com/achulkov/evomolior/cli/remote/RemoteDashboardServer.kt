package com.achulkov.evomolior.cli.remote

import com.achulkov.evomolior.core.config.LlmBackend
import io.ktor.http.Cookie
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.OutputStream
import java.net.URLEncoder
import java.nio.channels.Channels
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

private const val SESSION_COOKIE = "evomolior_dashboard_session"
private const val MAX_PROMPT_BYTES = 512_000L
private const val SESSION_TTL_SECONDS = 60 * 60 * 12
private const val MAX_SESSIONS = 1_000
private const val SSE_REFRESH_MILLIS = 1_500L
private const val SSE_WRITE_TIMEOUT_MILLIS = 10_000L
private const val MAX_SSE_CONNECTIONS_PER_SESSION = 4
private const val SNAPSHOT_CACHE_MILLIS = 1_000L

private val dashboardJson = Json { prettyPrint = true; encodeDefaults = true }
private val dashboardCompactJson = Json { encodeDefaults = true }

/**
 * Authenticated Ktor dashboard that launches EvoMolior CLI commands as subprocesses.
 *
 * @param config Deployment dashboard configuration.
 * @param sessionTtlSeconds Session lifetime used by cookies and live-stream authorization checks.
 * @param sseRefreshMillis Delay between realtime snapshots.
 * @param now Clock used for session creation and expiry.
 */
internal class RemoteDashboardServer(
    private val config: RemoteDashboardConfig,
    private val sessionTtlSeconds: Int = SESSION_TTL_SECONDS,
    private val sseRefreshMillis: Long = SSE_REFRESH_MILLIS,
    private val now: () -> Instant = Instant::now
) {
    private val registry = RemoteRunRegistry(config.dataDir)
    private val observability = RemoteRunObservability(registry)
    private val sessions = ConcurrentHashMap<String, Instant>()
    private val sseConnectionsBySession = ConcurrentHashMap<String, AtomicInteger>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val random = SecureRandom()
    private val processSupervisor = RemoteProcessSupervisor()
    private val runPermits = Semaphore(config.maxConcurrentRuns)
    private val ssePermits = Semaphore(config.maxSseConnections)
    private val snapshotMutex = Mutex()
    @Volatile
    private var overviewCache: CachedSnapshot<RemoteDashboardOverview>? = null
    private val detailCache = ConcurrentHashMap<String, CachedSnapshot<RemoteRunDetail>>()

    fun start() {
        embeddedServer(Netty, host = config.host, port = config.port) {
            configure(this)
        }.start(wait = true)
    }

    internal fun configure(application: Application) {
        val loadReport = kotlinx.coroutines.runBlocking { registry.load() }
        loadReport.warnings.forEach { warning ->
            System.err.println("EvoMolior dashboard registry: $warning")
        }
        application.install(ContentNegotiation) { json(dashboardJson) }
        application.routing {
            get("/login") {
                if (call.isAuthenticated()) {
                    call.respondRedirect("/")
                } else {
                    call.respondHtml(renderRemoteLogin(error = call.request.queryParameters["error"] == "1"))
                }
            }
            post("/login") {
                val form = call.receiveParameters()
                val username = form["username"].orEmpty()
                val password = form["password"].orEmpty()
                if (constantTimeEquals(username, config.username) && constantTimeEquals(password, config.password)) {
                    pruneSessions()
                    val token = newToken()
                    sessions[token] = now()
                    call.response.cookies.append(
                        Cookie(
                            name = SESSION_COOKIE,
                            value = token,
                            httpOnly = true,
                            secure = config.secureCookie,
                            path = "/",
                            maxAge = sessionTtlSeconds,
                            extensions = mapOf("SameSite" to "Lax")
                        )
                    )
                    call.respondRedirect("/")
                } else {
                    call.respondRedirect("/login?error=1")
                }
            }
            post("/logout") {
                val token = call.request.cookies[SESSION_COOKIE]
                if (token != null) sessions.remove(token)
                call.response.cookies.append(
                    Cookie(
                        name = SESSION_COOKIE,
                        value = "",
                        httpOnly = true,
                        secure = config.secureCookie,
                        path = "/",
                        maxAge = 0,
                        extensions = mapOf("SameSite" to "Lax")
                    )
                )
                call.respondRedirect("/login")
            }
            get("/") {
                if (!call.requirePageAuth()) return@get
                call.respondHtml(renderRemoteDashboard(overviewSnapshot()))
            }
            post("/runs") {
                if (!call.requirePageAuth()) return@post
                val launch = try {
                    RunLaunchRequest.from(call.receiveParameters())
                } catch (error: RunLaunchValidationException) {
                    call.respondHtml(renderRemoteBadRequest(error.message.orEmpty()), HttpStatusCode.BadRequest)
                    return@post
                }
                val record = withContext(Dispatchers.IO) { launchRun(launch) }
                if (record == null) {
                    call.respondHtml(
                        renderRemoteBadRequest(
                            "Run capacity is full. Wait for an active run to finish before launching another."
                        ),
                        HttpStatusCode.TooManyRequests
                    )
                    return@post
                }
                call.respondRedirect("/runs/${url(record.id)}")
            }
            get("/runs/{id}") {
                if (!call.requirePageAuth()) return@get
                val id = call.parameters["id"].orEmpty()
                val record = registry.get(id)
                if (record == null) {
                    call.respondText("Run not found", status = HttpStatusCode.NotFound)
                } else {
                    call.respondHtml(renderRemoteRunDetail(detailSnapshot(record)))
                }
            }
            get("/api/runs") {
                if (!call.requireApiAuth()) return@get
                call.respond(registry.list().map(observability::publicRecord))
            }
            get("/api/overview") {
                if (!call.requireApiAuth()) return@get
                call.respond(overviewSnapshot())
            }
            get("/api/runs/{id}") {
                if (!call.requireApiAuth()) return@get
                val id = call.parameters["id"].orEmpty()
                val record = registry.get(id)
                if (record == null) {
                    call.respondText("Run not found", status = HttpStatusCode.NotFound)
                } else {
                    call.respond(detailSnapshot(record))
                }
            }
            get("/api/runs/{id}/preview") {
                if (!call.requireApiAuth()) return@get
                val id = call.parameters["id"].orEmpty()
                val relativePath = call.request.queryParameters["path"].orEmpty()
                val record = registry.get(id)
                val preview = record?.let { run ->
                    withContext(Dispatchers.IO) {
                        registry.openPreviewFile(run, relativePath)?.use { opened ->
                            observability.preview(relativePath, opened)
                        }
                    }
                }
                if (preview == null) {
                    call.respondText("Artifact preview not available", status = HttpStatusCode.NotFound)
                } else {
                    call.respond(preview)
                }
            }
            get("/api/events") {
                if (!call.requireApiAuth()) return@get
                val lease = call.acquireSseLease()
                if (lease == null) {
                    call.respondText(
                        "Realtime connection capacity is full",
                        status = HttpStatusCode.TooManyRequests
                    )
                    return@get
                }
                val once = call.request.queryParameters["once"] == "true"
                call.response.headers.append(HttpHeaders.CacheControl, "no-cache, no-transform")
                call.response.headers.append("X-Accel-Buffering", "no")
                try {
                    call.respondBytesWriter(ContentType.Text.EventStream) {
                        try {
                            do {
                                if (!call.isAuthenticated()) break
                                val overview = overviewSnapshot()
                                withTimeout(SSE_WRITE_TIMEOUT_MILLIS) {
                                    writeStringUtf8(
                                        ssePayload("overview", dashboardCompactJson.encodeToString(overview))
                                    )
                                    flush()
                                }
                                if (!once) delay(sseRefreshMillis)
                            } while (!once)
                        } finally {
                            lease.close()
                        }
                    }
                } catch (error: Throwable) {
                    lease.close()
                    throw error
                }
            }
            get("/api/runs/{id}/events") {
                if (!call.requireApiAuth()) return@get
                val id = call.parameters["id"].orEmpty()
                if (registry.get(id) == null) {
                    call.respondText("Run not found", status = HttpStatusCode.NotFound)
                    return@get
                }
                val lease = call.acquireSseLease()
                if (lease == null) {
                    call.respondText(
                        "Realtime connection capacity is full",
                        status = HttpStatusCode.TooManyRequests
                    )
                    return@get
                }
                val once = call.request.queryParameters["once"] == "true"
                call.response.headers.append(HttpHeaders.CacheControl, "no-cache, no-transform")
                call.response.headers.append("X-Accel-Buffering", "no")
                try {
                    call.respondBytesWriter(ContentType.Text.EventStream) {
                        try {
                            do {
                                if (!call.isAuthenticated()) break
                                val record = registry.get(id) ?: break
                                val detail = detailSnapshot(record)
                                withTimeout(SSE_WRITE_TIMEOUT_MILLIS) {
                                    writeStringUtf8(
                                        ssePayload("run", dashboardCompactJson.encodeToString(detail))
                                    )
                                    flush()
                                }
                                if (!once) delay(sseRefreshMillis)
                            } while (!once)
                        } finally {
                            lease.close()
                        }
                    }
                } catch (error: Throwable) {
                    lease.close()
                    throw error
                }
            }
            get("/outputs/{id}/{path...}") {
                if (!call.requireApiAuth()) return@get
                val id = call.parameters["id"].orEmpty()
                val relativePath = call.parameters.getAll("path").orEmpty().joinToString("/")
                val record = registry.get(id)
                val opened = withContext(Dispatchers.IO) {
                    record?.let { registry.openOutputFile(it, relativePath) }
                }
                if (opened == null) {
                    call.respondText("Output file not found", status = HttpStatusCode.NotFound)
                } else {
                    call.response.headers.append(
                        HttpHeaders.ContentDisposition,
                        "attachment; filename*=UTF-8''${url(opened.fileName)}"
                    )
                    call.response.headers.append("X-Content-Type-Options", "nosniff")
                    call.response.headers.append(
                        "Content-Security-Policy",
                        "sandbox; default-src 'none'; script-src 'none'; object-src 'none'"
                    )
                    call.response.headers.append(HttpHeaders.CacheControl, "private, no-store")
                    try {
                        call.respondOutputStream(
                            contentType = ContentType.Application.OctetStream,
                            contentLength = opened.sizeBytes
                        ) {
                            transferOpenedOutput(opened, this)
                        }
                    } catch (error: Throwable) {
                        runCatching { opened.close() }
                        throw error
                    }
                }
            }
            get("/logs/{id}") {
                if (!call.requireApiAuth()) return@get
                val id = call.parameters["id"].orEmpty()
                val record = registry.get(id)
                val hasLog = withContext(Dispatchers.IO) {
                    record?.let { registry.openLog(it)?.use { true } } == true
                }
                if (record == null || !hasLog) {
                    call.respondText("Run log not found", status = HttpStatusCode.NotFound)
                } else {
                    val log = withContext(Dispatchers.IO) { observability.log(record) }
                    call.respondText(log.lines.joinToString("\n"), ContentType.Text.Plain)
                }
            }
        }
    }

    private suspend fun launchRun(request: RunLaunchRequest): RemoteRunRecord? {
        if (!runPermits.tryAcquire()) return null
        try {
        val id = newRemoteRunId()
        val runRoot = registry.runsDir.resolve(id)
        val inputDir = runRoot.resolve("input")
        val outputDir = runRoot.resolve("output")
        inputDir.createDirectories()
        outputDir.createDirectories()
        val logPath = runRoot.resolve("run.log")
        val command = request.toCommand(config.cliExecutable, inputDir, outputDir)
        val record = registry.create(
            id = id,
            type = request.type,
            command = command.masked(),
            outputDir = outputDir,
            logPath = logPath,
            launch = request.toLaunchMetadata()
        )
        val promptFile = inputDir.resolve("prompt.txt")
        if (request.type == "run") {
            promptFile.writeText(request.prompt)
            inputDir.resolve("README.md").writeText("Dashboard-created input directory for run ${record.id}.\n")
        }
        invalidateSnapshots(record.id)
        scope.launch {
            execute(record.id, command, logPath, runRoot)
        }
        return record
        } catch (error: Exception) {
            runPermits.release()
            throw error
        }
    }

    private suspend fun execute(id: String, command: List<String>, logPath: Path, workingDir: Path) {
        var process: Process? = null
        try {
            logPath.writeText("Started at ${Instant.now()}\nCommand: ${command.masked().joinToString(" ")}\n\n")
            val pendingProcess = processSupervisor.start(command, workingDir, logPath)
            val startedProcess = pendingProcess.process
            process = startedProcess
            val processHandle = startedProcess.toHandle()
            val processStartedAt = processHandle.info().startInstant().orElse(null)
                ?: run {
                    terminateProcessTree(processHandle)
                    error("CLI process start identity is unavailable")
                }
            try {
                registry.markRunning(id, startedProcess.pid(), processStartedAt)
            } catch (error: Throwable) {
                terminateProcessTree(processHandle)
                throw error
            }
            pendingProcess.authorize()
            invalidateSnapshots(id)
            val exitCode = startedProcess.waitFor()
            if (exitCode == 0) {
                registry.markSucceeded(id, exitCode)
            } else {
                val failure = when (exitCode) {
                    SUPERVISOR_CHILD_START_FAILURE_EXIT -> "CLI process could not start"
                    SUPERVISOR_AUTHORIZATION_TIMEOUT_EXIT -> "CLI launch authorization timed out"
                    SUPERVISOR_AUTHORIZATION_REJECTED_EXIT -> "CLI launch authorization was rejected"
                    else -> "CLI process exited with code $exitCode"
                }
                registry.markFailed(id, failure, exitCode)
            }
        } catch (e: Exception) {
            runCatching {
                Files.writeString(
                    logPath,
                    "\nLaunch failed at ${Instant.now()}: ${e::class.simpleName}: ${e.message}\n",
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND
                )
            }
            process?.toHandle()?.takeIf(ProcessHandle::isAlive)?.let { handle ->
                runCatching { terminateProcessTree(handle) }
            }
            runCatching { registry.markFailed(id, e.message ?: "CLI process failed to start") }
        } finally {
            invalidateSnapshots(id)
            runPermits.release()
        }
    }

    private suspend fun overviewSnapshot(): RemoteDashboardOverview {
        val cached = overviewCache
        if (cached != null && cached.isFresh()) return cached.value
        return snapshotMutex.withLock {
            val rechecked = overviewCache
            if (rechecked != null && rechecked.isFresh()) return@withLock rechecked.value
            withContext(Dispatchers.IO) { observability.overview(registry.list()) }.also { snapshot ->
                overviewCache = CachedSnapshot(snapshot)
            }
        }
    }

    private suspend fun detailSnapshot(record: RemoteRunRecord): RemoteRunDetail {
        val cached = detailCache[record.id]
        if (cached != null && cached.isFresh()) return cached.value
        return snapshotMutex.withLock {
            val rechecked = detailCache[record.id]
            if (rechecked != null && rechecked.isFresh()) return@withLock rechecked.value
            withContext(Dispatchers.IO) { observability.detail(record) }.also { snapshot ->
                detailCache[record.id] = CachedSnapshot(snapshot)
            }
        }
    }

    private fun invalidateSnapshots(runId: String) {
        overviewCache = null
        detailCache.remove(runId)
    }

    private fun RunLaunchRequest.toCommand(cliExecutable: String, inputDir: Path, outputDir: Path): List<String> =
        when (type) {
            "benchmark" -> listOf(
                cliExecutable,
                "benchmark",
                "-benchmark",
                benchmark,
                "-limit",
                limit.toString(),
                "-offset",
                offset.toString(),
                "-output",
                outputDir.toString(),
                "-llm-backend",
                backend.id,
                "-runner",
                runner
            ) + if (fast) listOf("-fast") else listOf("-no-fast")
            else -> listOf(
                cliExecutable,
                "run",
                "-promptfile",
                inputDir.resolve("prompt.txt").toString(),
                "-input",
                inputDir.toString(),
                "-output",
                outputDir.toString(),
                "-llm-backend",
                backend.id,
                "-run-id",
                "dashboard-${Instant.now().toEpochMilli()}",
                "-debug"
            ) + if (fast) listOf("-fast") else emptyList()
        }

    private fun RunLaunchRequest.toLaunchMetadata(): RemoteRunLaunch = RemoteRunLaunch(
        backend = backend.id,
        runner = runner.takeIf { type == "benchmark" },
        fast = fast,
        benchmark = benchmark.takeIf { type == "benchmark" },
        limit = limit.takeIf { type == "benchmark" },
        offset = offset.takeIf { type == "benchmark" }
    )

    private fun List<String>.masked(): List<String> = mapIndexed { index, value ->
        if (index > 0 && get(index - 1).contains("api-key", ignoreCase = true)) "****" else value
    }

    private fun newToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun ApplicationCall.isAuthenticated(): Boolean =
        request.cookies[SESSION_COOKIE]?.let(::isAuthenticatedToken) == true

    private fun isAuthenticatedToken(token: String): Boolean {
        val createdAt = sessions[token] ?: return false
        val valid = createdAt.plusSeconds(sessionTtlSeconds.toLong()).isAfter(now())
        if (!valid) sessions.remove(token)
        return valid
    }

    private fun ApplicationCall.acquireSseLease(): SseLease? {
        val token = request.cookies[SESSION_COOKIE] ?: return null
        if (!isAuthenticatedToken(token) || !ssePermits.tryAcquire()) return null
        val sessionConnections = sseConnectionsBySession.computeIfAbsent(token) { AtomicInteger() }
        if (sessionConnections.incrementAndGet() > MAX_SSE_CONNECTIONS_PER_SESSION) {
            if (sessionConnections.decrementAndGet() == 0) {
                sseConnectionsBySession.remove(token, sessionConnections)
            }
            ssePermits.release()
            return null
        }
        return SseLease {
            if (sessionConnections.decrementAndGet() == 0) {
                sseConnectionsBySession.remove(token, sessionConnections)
            }
            ssePermits.release()
        }
    }

    private suspend fun ApplicationCall.requirePageAuth(): Boolean {
        if (isAuthenticated()) return true
        respondRedirect("/login")
        return false
    }

    private suspend fun ApplicationCall.requireApiAuth(): Boolean {
        if (isAuthenticated()) return true
        respondText("Authentication required", status = HttpStatusCode.Unauthorized)
        return false
    }

    private fun pruneSessions() {
        val cutoff = now().minusSeconds(sessionTtlSeconds.toLong())
        sessions.entries.removeIf { it.value.isBefore(cutoff) }
        if (sessions.size >= MAX_SESSIONS) {
            sessions.entries.sortedBy { it.value }.take(sessions.size - MAX_SESSIONS + 1).forEach {
                sessions.remove(it.key, it.value)
            }
        }
    }
}

private data class CachedSnapshot<T>(
    val value: T,
    val createdAtNanos: Long = System.nanoTime()
) {
    fun isFresh(): Boolean =
        System.nanoTime() - createdAtNanos <= SNAPSHOT_CACHE_MILLIS * 1_000_000
}

private class SseLease(private val release: () -> Unit) : AutoCloseable {
    private val closed = AtomicBoolean()

    override fun close() {
        if (closed.compareAndSet(false, true)) release()
    }
}

internal fun safeOutputPathForTest(registry: RemoteRunRegistry, record: RemoteRunRecord, path: String): Path? =
    registry.resolveOutputFile(record, path)

private data class RunLaunchRequest(
    val type: String,
    val prompt: String,
    val backend: LlmBackend,
    val fast: Boolean,
    val benchmark: String,
    val limit: Int,
    val offset: Int,
    val runner: String
) {
    companion object {
        fun from(form: ParametersLike): RunLaunchRequest {
            val errors = mutableListOf<String>()
            val type = form["type"] ?: "run"
            if (type !in RUN_TYPES) errors += "type must be one of: ${RUN_TYPES.joinToString()}"
            val backendValue = form["backend"] ?: LlmBackend.QWEN_CLOUD.id
            val backend = runCatching { LlmBackend.parse(backendValue) }.getOrElse {
                errors += "backend must be one of: ${LlmBackend.entries.joinToString { entry -> entry.id }}"
                LlmBackend.QWEN_CLOUD
            }
            val prompt = form["prompt"].orEmpty()
            if (type == "run" && prompt.isBlank()) errors += "prompt is required for a normal run"
            if (prompt.toByteArray(StandardCharsets.UTF_8).size > MAX_PROMPT_BYTES) {
                errors += "prompt must not exceed ${MAX_PROMPT_BYTES / 1024} KiB"
            }
            val benchmark = form["benchmark"] ?: "frames"
            if (type == "benchmark" && benchmark !in BENCHMARKS) {
                errors += "benchmark must be one of: ${BENCHMARKS.joinToString()}"
            }
            val limitValue = form["limit"] ?: "1"
            val limit = limitValue.toIntOrNull()
            if (type == "benchmark" && (limit == null || limit !in 1..50)) {
                errors += "limit must be an integer between 1 and 50"
            }
            val offsetValue = form["offset"] ?: "0"
            val offset = offsetValue.toIntOrNull()
            if (type == "benchmark" && (offset == null || offset < 0)) {
                errors += "offset must be a non-negative integer"
            }
            val runner = form["runner"] ?: "evomolior-society"
            if (type == "benchmark" && runner !in RUNNERS) {
                errors += "runner must be one of: ${RUNNERS.joinToString()}"
            }
            if (errors.isNotEmpty()) throw RunLaunchValidationException(errors.joinToString(". "))
            return RunLaunchRequest(
                type = type,
                prompt = prompt,
                backend = backend,
                fast = form["fast"] != null,
                benchmark = benchmark,
                limit = limit ?: 1,
                offset = offset ?: 0,
                runner = runner
            )
        }

        private val RUN_TYPES = linkedSetOf("run", "benchmark")
        private val RUNNERS = linkedSetOf("evomolior-society", "single-agent")
        private val BENCHMARKS = linkedSetOf(
            "frames",
            "litqa2",
            "scholarqa",
            "scholarqabench",
            "scholarqabench-pubmed",
            "scholarqabench-scifact",
            "scholarqabench-cs",
            "ai2-scholar-qa",
            "autoresearchbench"
        )
    }
}

private typealias ParametersLike = io.ktor.http.Parameters

private class RunLaunchValidationException(message: String) : IllegalArgumentException(message)

internal suspend fun transferOpenedOutput(
    opened: RemoteOpenedOutput,
    output: OutputStream,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    withContext(ioDispatcher) {
        opened.use {
            Channels.newInputStream(it.channel).copyTo(output)
        }
    }
}

private suspend fun ApplicationCall.respondHtml(
    html: String,
    status: HttpStatusCode = HttpStatusCode.OK
) {
    response.headers.append("X-Content-Type-Options", "nosniff")
    response.headers.append("Referrer-Policy", "no-referrer")
    response.headers.append("X-Frame-Options", "DENY")
    response.headers.append(
        "Content-Security-Policy",
        "default-src 'self'; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'; " +
            "connect-src 'self'; img-src 'self' data:; object-src 'none'; base-uri 'none'; frame-ancestors 'none'"
    )
    respondText(html, ContentType.Text.Html, status)
}

private fun ssePayload(event: String, data: String): String = buildString {
    append("event: ")
    append(event)
    append('\n')
    data.lineSequence().forEach { line ->
        append("data: ")
        append(line)
        append('\n')
    }
    append('\n')
}

private fun constantTimeEquals(left: String, right: String): Boolean {
    val a = left.toByteArray(StandardCharsets.UTF_8)
    val b = right.toByteArray(StandardCharsets.UTF_8)
    return MessageDigest.isEqual(a, b)
}

private fun url(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
