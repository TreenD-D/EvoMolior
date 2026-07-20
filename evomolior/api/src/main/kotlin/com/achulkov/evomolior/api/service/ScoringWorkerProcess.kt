package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.api.model.ScoringWorkerHealth
import com.achulkov.evomolior.api.model.ScoringWorkerRequest
import com.achulkov.evomolior.api.model.ScoringWorkerResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Owns one run-scoped scoring child process and its authenticated loopback client.
 *
 * @param startupTimeoutMs Maximum worker startup duration.
 * @param requestTimeoutMs Maximum duration of one final scoring operation.
 * @param shutdownTimeoutMs Maximum graceful shutdown duration.
 * @param workerMainClass Child-JVM entry point, replaceable for startup-failure verification.
 */
internal class ScoringWorkerProcess(
    private val startupTimeoutMs: Long = 15_000,
    private val requestTimeoutMs: Long = 60_000,
    private val shutdownTimeoutMs: Long = 3_000,
    private val workerMainClass: String =
        "com.achulkov.evomolior.api.service.ScoringWorkerServerKt"
) {
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
    private var process: Process? = null
    private var baseUri: URI? = null
    private var token: String? = null
    private var diagnosticDirectory: Path? = null
    private var readyFile: Path? = null
    private var stdoutFile: Path? = null
    private var stderrFile: Path? = null

    suspend fun start(groupIds: List<String>) {
        check(process == null) { "Scoring worker is already running" }
        require(groupIds.isNotEmpty()) { "Scoring worker requires at least one group" }
        val workerToken = randomToken()
        val javaBinary = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val diagnostics = Files.createTempDirectory("evomolior-scoring-worker-")
        val ready = diagnostics.resolve("ready.port")
        val stdout = diagnostics.resolve("stdout.log")
        val stderr = diagnostics.resolve("stderr.log")
        val child = ProcessBuilder(
            javaBinary,
            "-cp",
            System.getProperty("java.class.path"),
            workerMainClass,
            "0",
            ready.toString(),
            *groupIds.toTypedArray()
        ).apply {
            environment()["EVOMOLIOR_SCORING_WORKER_TOKEN"] = workerToken
            redirectOutput(stdout.toFile())
            redirectError(stderr.toFile())
        }.start()
        process = child
        token = workerToken
        diagnosticDirectory = diagnostics
        readyFile = ready
        stdoutFile = stdout
        stderrFile = stderr

        try {
            withTimeout(startupTimeoutMs) {
                while (true) {
                    check(child.isAlive) {
                        "Scoring worker exited during startup with code ${child.exitValue()}"
                    }
                    if (baseUri == null && Files.isRegularFile(ready)) {
                        val port = Files.readString(ready).trim().toIntOrNull()
                        if (port != null) baseUri = URI.create("http://127.0.0.1:$port")
                    }
                    if (baseUri == null) {
                        delay(50)
                        continue
                    }
                    val health = runCatching { getHealth() }.getOrNull()
                    if (health?.ready == true) return@withTimeout
                    delay(50)
                }
            }
        } catch (error: Exception) {
            val diagnosticsText = startupDiagnostics(child)
            stop()
            throw IllegalStateException(
                "Scoring worker failed to start. $diagnosticsText",
                error
            )
        }
    }

    suspend fun score(request: ScoringWorkerRequest): ScoringWorkerResponse {
        val child = checkNotNull(process) { "Scoring worker is not running" }
        check(child.isAlive) { "Scoring worker process is not alive" }
        val response = withContext(Dispatchers.IO) {
            withTimeout(requestTimeoutMs) {
                send(
                    path = "/score",
                    body = json.encodeToString(request),
                    responseHandler = HttpResponse.BodyHandlers.ofString()
                )
            }
        }
        check(response.statusCode() == 200) {
            "Scoring worker rejected request with HTTP ${response.statusCode()}: " +
                "${response.body().ifBlank { "<empty body>" }}; " +
                "stdout=${readTail(stdoutFile)}; stderr=${readTail(stderrFile)}"
        }
        return runCatching { json.decodeFromString<ScoringWorkerResponse>(response.body()) }
            .getOrElse { throw IllegalStateException("Scoring worker returned an invalid response", it) }
    }

    suspend fun stop() {
        val child = process ?: return
        if (child.isAlive) {
            runCatching {
                withTimeout(shutdownTimeoutMs) {
                    send("/shutdown", "", HttpResponse.BodyHandlers.discarding())
                }
            }
            withContext(Dispatchers.IO) {
                if (!child.waitFor(shutdownTimeoutMs, TimeUnit.MILLISECONDS)) {
                    child.destroy()
                    if (!child.waitFor(shutdownTimeoutMs, TimeUnit.MILLISECONDS)) child.destroyForcibly()
                }
            }
        }
        process = null
        baseUri = null
        token = null
        cleanupDiagnostics()
    }

    internal fun isAlive(): Boolean = process?.isAlive == true

    internal fun terminateForTest() {
        process?.destroyForcibly()
    }

    private suspend fun getHealth(): ScoringWorkerHealth {
        val response = send("/health", null, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200)
        return json.decodeFromString(response.body())
    }

    private suspend fun <T> send(
        path: String,
        body: String?,
        responseHandler: HttpResponse.BodyHandler<T>
    ): HttpResponse<T> = withContext(Dispatchers.IO) {
        val request = HttpRequest.newBuilder(checkNotNull(baseUri).resolve(path))
            .header(SCORING_WORKER_TOKEN_HEADER, checkNotNull(token))
            .timeout(Duration.ofMillis(requestTimeoutMs))
            .apply {
                if (body == null) GET() else {
                    header("Content-Type", "application/json")
                    POST(HttpRequest.BodyPublishers.ofString(body))
                }
            }
            .build()
        client.send(request, responseHandler)
    }

    private fun randomToken(): String = ByteArray(32).also(SecureRandom()::nextBytes).let {
        Base64.getUrlEncoder().withoutPadding().encodeToString(it)
    }

    private fun startupDiagnostics(child: Process): String {
        val exit = if (child.isAlive) "alive" else child.exitValue().toString()
        return "exit=$exit, stdout=${readTail(stdoutFile)}, stderr=${readTail(stderrFile)}"
    }

    private fun readTail(path: Path?): String {
        if (path == null || !Files.isRegularFile(path)) return "<empty>"
        val content = runCatching { Files.readString(path) }.getOrDefault("")
        return content.takeLast(MAX_DIAGNOSTIC_CHARS).ifBlank { "<empty>" }
    }

    private fun cleanupDiagnostics() {
        listOf(readyFile, stdoutFile, stderrFile).forEach { path ->
            path?.let { runCatching { Files.deleteIfExists(it) } }
        }
        diagnosticDirectory?.let { runCatching { Files.deleteIfExists(it) } }
        diagnosticDirectory = null
        readyFile = null
        stdoutFile = null
        stderrFile = null
    }

    private companion object {
        const val MAX_DIAGNOSTIC_CHARS = 4_096
    }
}
