package com.achulkov.evomolior.cli

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

private const val DAEMON_HOST = "127.0.0.1"
private const val DAEMON_DEFAULT_PORT = 1466
private const val DAEMON_DISABLE_ENV = "EVOMOLIOR_DISABLE_DAEMON"
private const val DAEMON_TOKEN_HEADER = "X-EvoMolior-Daemon-Token"

@Serializable
internal data class DaemonRunRequest(val args: List<String>)

@Serializable
internal data class DaemonRunResponse(
    val completedSuccessfully: Boolean,
    val message: String
)

@Serializable
internal data class DaemonStatusResponse(
    val running: Boolean,
    val port: Int,
    val pid: Long
)

internal data class LocalRunDaemonResult(val completedSuccessfully: Boolean)

internal object LocalRunDaemonPaths {
    private val home: Path = Path.of(System.getProperty("user.home"))
    val stateDir: Path = home.resolve(".evomolior").resolve("daemon")
    val tokenPath: Path = stateDir.resolve("token")

    fun token(): String {
        Files.createDirectories(stateDir)
        if (tokenPath.exists()) return tokenPath.readText().trim()
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).also { tokenPath.writeText(it) }
    }
}

internal object LocalRunDaemonConfig {
    fun port(env: Map<String, String> = System.getenv()): Int =
        env["EVOMOLIOR_DAEMON_PORT"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: DAEMON_DEFAULT_PORT

    fun baseUri(port: Int = port()): URI = URI.create("http://$DAEMON_HOST:$port")
}

internal object LocalRunDaemonClient {
    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .build()

    fun tryRun(args: Array<String>): LocalRunDaemonResult? {
        if (!shouldDelegate(args)) return null
        val response = runCatching {
            val requestBody = json.encodeToString(
                DaemonRunRequest.serializer(),
                DaemonRunRequest(normalizePathArguments(args).toList())
            )
            val response = httpClient.send(
                request("/runs")
                    .timeout(Duration.ofDays(7))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            )
            if (response.statusCode() != 200) return null
            json.decodeFromString(DaemonRunResponse.serializer(), response.body())
        }.getOrNull() ?: return null
        println(response.message)
        return LocalRunDaemonResult(response.completedSuccessfully)
    }

    fun status(): DaemonStatusResponse? = runCatching {
        val response = httpClient.send(
            request("/health")
                .timeout(Duration.ofSeconds(2))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString()
        )
        if (response.statusCode() != 200) return null
        json.decodeFromString(DaemonStatusResponse.serializer(), response.body())
    }.getOrNull()

    fun stop(): Boolean = runCatching {
        val response = httpClient.send(
            request("/shutdown")
                .timeout(Duration.ofSeconds(2))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.discarding()
        )
        response.statusCode() == 200
    }.getOrDefault(false)

    internal fun shouldDelegate(args: Array<String>, env: Map<String, String> = System.getenv()): Boolean {
        if (env[DAEMON_DISABLE_ENV] == "1") return false
        if (args.any { it == "-h" || it == "--help" }) return false
        if (LocalRunDaemonRouting.hasPipelineConfigOption(args)) return false
        if (AcceptanceInvocationDetector.requestsAcceptance(args)) return false
        return when (args.firstOrNull()) {
            null, "daemon", "self-improve", "benchmark", "gptauth", "serve" -> false
            "run" -> true
            else -> args.any { it == "-prompt" || it == "-promptfile" }
        }
    }

    internal fun normalizePathArguments(args: Array<String>): Array<String> {
        val pathOptions = setOf("-input", "-output", "-promptfile", "-pipeline-config")
        val baseDir = Path.of("").toAbsolutePath().normalize()
        val normalized = args.toMutableList()
        var index = 0
        while (index < normalized.size) {
            if (normalized[index] in pathOptions && index + 1 < normalized.size) {
                val path = Path.of(normalized[index + 1])
                normalized[index + 1] = if (path.isAbsolute) {
                    path.normalize().toString()
                } else {
                    baseDir.resolve(path).normalize().toString()
                }
                index += 2
            } else {
                index += 1
            }
        }
        return normalized.toTypedArray()
    }

    private fun request(path: String): HttpRequest.Builder =
        HttpRequest.newBuilder(LocalRunDaemonConfig.baseUri().resolve(path))
            .header("Content-Type", "application/json")
            .header(DAEMON_TOKEN_HEADER, LocalRunDaemonPaths.token())
}

internal object LocalRunDaemonCommand {
    fun run(args: Array<String>): Int {
        return when (args.firstOrNull() ?: "status") {
            "start" -> {
                AppendixBAcceptanceLockProbe.requireAvailableForDaemonStart()
                if (LocalRunDaemonClient.status() != null) {
                    println("EvoMolior daemon is already running on ${LocalRunDaemonConfig.baseUri()}")
                    0
                } else {
                    LocalRunDaemonServer(LocalRunDaemonConfig.port()).start()
                    0
                }
            }
            "status" -> {
                val status = LocalRunDaemonClient.status()
                if (status == null) {
                    println("EvoMolior daemon is not running.")
                    1
                } else {
                    println("EvoMolior daemon running on ${LocalRunDaemonConfig.baseUri(status.port)} pid=${status.pid}")
                    0
                }
            }
            "stop" -> {
                if (LocalRunDaemonClient.stop()) {
                    println("EvoMolior daemon stop requested.")
                    0
                } else {
                    println("EvoMolior daemon is not running.")
                    1
                }
            }
            "-h", "--help" -> {
                println(usage())
                0
            }
            else -> throw CliUsageException("Unknown daemon argument: ${args.first()}")
        }
    }

    fun usage(): String = """
        EvoMolior local run daemon

        Usage:
          evomolior daemon start
          evomolior daemon status
          evomolior daemon stop

        The daemon listens only on 127.0.0.1 and executes concurrent task-run requests from
        local `evomolior run ...` CLI instances. Set EVOMOLIOR_DISABLE_DAEMON=1 to force inline
        execution for a single CLI process.
    """.trimIndent()
}

internal class LocalRunDaemonServer(private val port: Int) {
    private val json = Json { ignoreUnknownKeys = true }
    private val shutdown = CountDownLatch(1)
    private val server = HttpServer.create(InetSocketAddress(DAEMON_HOST, port), 0)

    fun start() {
        Files.createDirectories(LocalRunDaemonPaths.stateDir)
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/health", ::handleHealth)
        server.createContext("/runs", ::handleRun)
        server.createContext("/shutdown", ::handleShutdown)
        server.start()
        println("EvoMolior daemon listening on ${LocalRunDaemonConfig.baseUri(port)}")
        shutdown.await()
    }

    private fun handleHealth(exchange: HttpExchange) {
        if (!authorized(exchange)) return
        writeJson(exchange, 200, DaemonStatusResponse(true, port, ProcessHandle.current().pid()))
    }

    private fun handleRun(exchange: HttpExchange) {
        if (!authorized(exchange)) return
        if (exchange.requestMethod != "POST") {
            writeText(exchange, 405, "Method not allowed")
            return
        }
        val args = json.decodeFromString(
            DaemonRunRequest.serializer(),
            exchange.requestBody.bufferedReader(Charsets.UTF_8).readText()
        ).args.toTypedArray()
        LocalRunDaemonRouting.daemonRunRejection(args)?.let { rejection ->
            writeJson(exchange, rejection.status, rejection.response)
            return
        }
        if (AcceptanceInvocationDetector.requestsAcceptance(args)) {
            writeJson(
                exchange,
                409,
                DaemonRunResponse(
                    completedSuccessfully = false,
                    message = "Appendix-B acceptance runs must execute inline from the immutable distribution."
                )
            )
            return
        }
        val routedArgs = if (args.firstOrNull() == "run") args.drop(1).toTypedArray() else args
        val response = runCatching {
            val options = CliArguments.parse(routedArgs)
                ?: throw CliUsageException("Run arguments are incomplete")
            val result = runCli(options)
            DaemonRunResponse(
                completedSuccessfully = result.completedSuccessfully,
                message = if (result.completedSuccessfully) {
                    "EvoMolior daemon run completed successfully."
                } else {
                    "EvoMolior daemon run failed."
                }
            )
        }.getOrElse { error ->
            DaemonRunResponse(false, "EvoMolior daemon run failed: ${error.message}")
        }
        writeJson(exchange, 200, response)
    }

    private fun handleShutdown(exchange: HttpExchange) {
        if (!authorized(exchange)) return
        writeText(exchange, 200, "Stopping EvoMolior daemon")
        Thread {
            server.stop(1)
            shutdown.countDown()
        }.start()
    }

    private fun authorized(exchange: HttpExchange): Boolean {
        val expected = LocalRunDaemonPaths.token()
        val actual = exchange.requestHeaders[DAEMON_TOKEN_HEADER]?.firstOrNull()
        if (actual == expected) return true
        writeText(exchange, 401, "Unauthorized")
        return false
    }

    private fun writeJson(exchange: HttpExchange, status: Int, value: DaemonStatusResponse) {
        writeText(exchange, status, json.encodeToString(DaemonStatusResponse.serializer(), value))
    }

    private fun writeJson(exchange: HttpExchange, status: Int, value: DaemonRunResponse) {
        writeText(exchange, status, json.encodeToString(DaemonRunResponse.serializer(), value))
    }

    private fun writeText(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}

/**
 * Keeps pipeline-config execution in the invoking CLI process.
 *
 * Pipeline configurations participate in admission and must therefore never cross the daemon
 * boundary, regardless of whether the config is valid, present, or readable.
 */
internal object LocalRunDaemonRouting {
    private val pipelineConfigOptions = setOf("-pipelineconfig", "-pipeline-config", "--pipeline-config")

    fun hasPipelineConfigOption(args: Array<String>): Boolean = args.any { it in pipelineConfigOptions }

    fun isPipelineConfigOption(argument: String): Boolean = argument in pipelineConfigOptions

    /**
     * Returns the daemon endpoint response for invocations that must remain inline.
     *
     * @param args decoded `/runs` request arguments.
     */
    fun daemonRunRejection(args: Array<String>): DaemonRunRejection? =
        if (hasPipelineConfigOption(args)) {
            DaemonRunRejection(
                status = 409,
                response = DaemonRunResponse(
                    completedSuccessfully = false,
                    message = "Pipeline-config runs must execute inline so admission and execution remain in one process."
                )
            )
        } else {
            null
        }
}

internal data class DaemonRunRejection(
    val status: Int,
    val response: DaemonRunResponse
)

internal object AcceptanceInvocationDetector {
    private val profileOptions = setOf("-acceptance-profile", "--acceptance-profile")

    fun requestsAcceptance(args: Array<String>): Boolean {
        var index = 0
        while (index < args.size) {
            when {
                args[index] in profileOptions -> {
                    val value = args.getOrNull(index + 1)
                    if (value != null && !value.equals("normal", ignoreCase = true)) return true
                    index += 2
                }
                LocalRunDaemonRouting.isPipelineConfigOption(args[index]) -> {
                    val configured = args.getOrNull(index + 1)?.let(::resolveInvocationPath)
                    if (configured != null && Files.isRegularFile(configured)) {
                        val text = runCatching { Files.readString(configured) }.getOrDefault("")
                        if (
                            Regex(
                                """"acceptanceProfile"\s*:\s*"(?!normal")[^"]+"""",
                                RegexOption.IGNORE_CASE
                            ).containsMatchIn(text)
                        ) {
                            return true
                        }
                    }
                    index += 2
                }
                else -> index += 1
            }
        }
        return false
    }

    private fun resolveInvocationPath(value: String): Path {
        val path = Path.of(value)
        return if (path.isAbsolute) path.normalize() else Path.of("").toAbsolutePath().resolve(path).normalize()
    }
}

internal object AppendixBAcceptanceLockProbe {
    const val RELATIVE_PATH = ".evomolior-locks/appendix-b-acceptance.lock"

    fun requireAvailableForDaemonStart(
        repositoryRoot: Path? = AcceptanceRepositoryRoot.resolveOrNull()
    ) {
        val resolvedRepositoryRoot = repositoryRoot ?: return
        val lockPath = resolvedRepositoryRoot.resolve(RELATIVE_PATH)
        Files.createDirectories(lockPath.parent)
        runCatching {
            Files.setPosixFilePermissions(
                lockPath.parent,
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE
                )
            )
        }
        FileChannel.open(
            lockPath,
            StandardOpenOption.CREATE,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE
        ).use { channel ->
            runCatching {
                Files.setPosixFilePermissions(
                    lockPath,
                    setOf(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE
                    )
                )
            }
            val lock = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            }
            if (lock == null) {
                val owner = runCatching { Files.readString(lockPath).take(2_048) }
                    .getOrDefault("<owner record unavailable>")
                throw CliUsageException(
                    "Cannot start the EvoMolior daemon while Appendix-B acceptance lock is held: $owner"
                )
            }
            lock.release()
        }
    }
}

internal object AcceptanceRepositoryRoot {
    fun resolveOrNull(
        env: Map<String, String> = System.getenv(),
        start: Path = Path.of("").toAbsolutePath().normalize()
    ): Path? {
        env["EVOMOLIOR_REPOSITORY_ROOT"]
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let(Path::of)
            ?.toAbsolutePath()
            ?.normalize()
            ?.let { return it }
        return generateSequence(start) { it.parent }
            .firstOrNull { candidate ->
                Files.exists(candidate.resolve("settings.gradle.kts")) &&
                    Files.isDirectory(candidate.resolve("evomolior"))
            }
    }
}
