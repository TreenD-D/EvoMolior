package com.achulkov.evomolior.sandbox.shuru

import io.github.aakira.napier.Napier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Manages Shuru microVM lifecycle by wrapping the Shuru CLI.
 *
 * Shuru CLI documentation: https://github.com/superhq-ai/shuru
 *
 * All operations are suspend functions dispatched on [Dispatchers.IO] since they
 * invoke external processes and may block.
 *
 * ### PATH note
 * JVM processes launched from a macOS app bundle or IDE do **not** inherit the user's
 * shell PATH, so `/opt/homebrew/bin` (Apple Silicon) and `/usr/local/bin` (Intel) are
 * invisible even after a successful `brew install`. [resolveCliPath] probes these
 * well-known locations so we can use the absolute path instead of relying on PATH.
 *
 * @param shuruCliPath Initial path/name for the `shuru` executable. May be overwritten
 *   to an absolute path by [ensureInstalled] after a successful auto-install.
 * @param vmConfigDir Directory to write temporary VM config JSON files.
 */
class ShuruVmManager(
    initialCliPath: String = "shuru",
    private val vmConfigDir: File = File(System.getProperty("java.io.tmpdir"), "evomolior-vm-configs")
) {
    /**
     * Resolved absolute path (or bare name) of the `shuru` binary.
     * Updated by [ensureInstalled] when an absolute path is found post-install.
     */
    @Volatile
    private var shuruCliPath: String = initialCliPath
    init {
        vmConfigDir.mkdirs()
    }

    /**
     * Checks whether the Shuru CLI is available on this machine.
     *
     * If not found, attempts to install it via Homebrew (macOS) or a wget/curl install
     * script for Linux. Returns a [ShuruCheckResult] describing the outcome.
     */
    suspend fun ensureInstalled(): ShuruCheckResult = withContext(Dispatchers.IO) {
        // Step 1: check if already available
        Napier.i { "ShuruVmManager: checking Shuru availability at '$shuruCliPath'" }
        // Shuru uses flag syntax: `shuru --version`, not the subcommand `shuru version`.
        val versionResult = runCatching { runShuruCommand("--version", timeoutSec = 10) }
        if (versionResult.isSuccess) {
            val version = versionResult.getOrThrow().stdout.trim()
                .ifBlank { versionResult.getOrThrow().stderr.trim() } // some CLIs write to stderr
            Napier.i { "ShuruVmManager: Shuru is available — $version" }
            return@withContext ShuruCheckResult(available = true, version = version, message = "Shuru is installed: $version")
        }

        val checkError = versionResult.exceptionOrNull()
        Napier.w { "ShuruVmManager: Shuru not found ($checkError), attempting installation…" }

        // Step 2: attempt Homebrew install (macOS)
        val os = System.getProperty("os.name", "").lowercase()
        val installResult: Result<String> = if (os.contains("mac")) {
            runCatching {
                Napier.i { "ShuruVmManager: attempting brew install superhq-ai/tap/shuru" }
                val result = runArbitraryCommand(
                    args = listOf("brew", "install", "superhq-ai/tap/shuru"),
                    timeoutSec = 300
                )
                Napier.i { "ShuruVmManager: brew install stdout=${result.stdout}" }
                result.stdout
            }
        } else {
            // Linux fallback: try the official install script
            Napier.i { "ShuruVmManager: attempting curl-based Shuru install (Linux)" }
            runCatching {
                val result = runArbitraryCommand(
                    args = listOf("bash", "-c", "curl -sSL https://install.shuru.dev | bash"),
                    timeoutSec = 300
                )
                Napier.i { "ShuruVmManager: install script stdout=${result.stdout}" }
                result.stdout
            }
        }

        if (installResult.isFailure) {
            val err = installResult.exceptionOrNull()?.message ?: "unknown error"
            Napier.e { "ShuruVmManager: auto-install failed: $err" }
            return@withContext ShuruCheckResult(
                available = false,
                version = null,
                message = "Shuru not found and auto-install failed: $err. Please install Shuru manually: https://github.com/superhq-ai/shuru"
            )
        }

        // Step 3: resolve the absolute CLI path — JVM processes on macOS don't inherit the
        // shell PATH, so Homebrew-installed binaries in /opt/homebrew/bin or /usr/local/bin
        // are invisible even when install succeeded. Probe well-known locations first.
        val resolvedPath = resolveCliPath()
        if (resolvedPath != null) {
            shuruCliPath = resolvedPath
            Napier.i { "ShuruVmManager: resolved Shuru CLI path to '$resolvedPath'" }
        }

        val recheck = runCatching { runShuruCommand("--version", timeoutSec = 10) }
        return@withContext if (recheck.isSuccess) {
            val version = recheck.getOrThrow().stdout.trim()
                .ifBlank { recheck.getOrThrow().stderr.trim() }
            Napier.i { "ShuruVmManager: Shuru successfully installed — $version (path=$shuruCliPath)" }
            ShuruCheckResult(available = true, version = version, message = "Shuru installed successfully: $version")
        } else {
            val err = recheck.exceptionOrNull()?.message ?: "unknown error"
            Napier.e { "ShuruVmManager: Shuru installed but CLI still not runnable (path=$shuruCliPath): $err" }
            ShuruCheckResult(
                available = false,
                version = null,
                message = "Shuru install succeeded but CLI is not executable at '$shuruCliPath': $err. " +
                    "Try restarting the app or setting SHURU_PATH env var."
            )
        }
    }

    /**
     * Probes well-known absolute paths for the `shuru` binary.
     *
     * This is needed because JVM processes launched from macOS app bundles or IDEs do not
     * inherit the user's shell PATH, making Homebrew-managed binaries invisible by name.
     *
     * Probe order (first match wins):
     * 1. `/opt/homebrew/bin/shuru` — Apple Silicon Homebrew
     * 2. `/usr/local/bin/shuru` — Intel macOS Homebrew
     * 3. `~/.local/bin/shuru` — Linux curl install default
     * 4. `/usr/bin/shuru` — system-wide Linux install
     * 5. Current [shuruCliPath] value (may already be absolute or in PATH)
     *
     * @return The first path where `shuru --version` exits 0, or null if none work.
     */
    private fun resolveCliPath(): String? {
        val home = System.getProperty("user.home", "")
        val candidates = listOf(
            "/opt/homebrew/bin/shuru",   // Apple Silicon macOS (Homebrew default)
            "/usr/local/bin/shuru",      // Intel macOS (Homebrew default)
            "$home/.local/bin/shuru",    // Linux curl-based install default
            "/usr/bin/shuru",            // Linux system install
            shuruCliPath                 // Caller-supplied path or bare "shuru"
        )
        for (candidate in candidates) {
            val file = File(candidate)
            // For absolute paths: skip fast if the file doesn't exist on disk.
            // For bare names (e.g. "shuru"): always try — PATH resolution happens inside exec.
            if (file.isAbsolute && !file.exists()) continue

            val works = runCatching {
                runCommandInternal(listOf(candidate, "--version"), timeoutSec = 5)
            }.isSuccess
            if (works) {
                Napier.d { "ShuruVmManager: resolved CLI path = $candidate" }
                return candidate
            }
        }
        return null
    }

    /**
     * Spawns a new Shuru microVM for the given [config].
     *
     * Writes a temporary VM config JSON, invokes `shuru run`, and parses the VM ID and PID
     * from the output.
     *
     * @param config The VM configuration.
     * @return A [VmHandle] for the spawned VM.
     * @throws ShuruException if the VM fails to start.
     */
    suspend fun spawnVm(config: VmConfig): VmHandle = withContext(Dispatchers.IO) {
        val configFile = writeVmConfigFile(config)
        Napier.i { "ShuruVmManager: spawning VM agentId=${config.agentId} groupId=${config.groupId} runId=${config.runId}" }
        try {
            val result = runShuruCommand(
                "run",
                "--config", configFile.absolutePath,
                "--name", config.agentId,
                "--json-output"
            )
            Napier.d { "ShuruVmManager: spawnVm stdout=${result.stdout}" }

            val json = Json.parseToJsonElement(result.stdout).jsonObject
            val shuruVmId = json["vm_id"]?.jsonPrimitive?.content
                ?: throw ShuruException("Shuru did not return vm_id: ${result.stdout}")
            val pid = json["pid"]?.jsonPrimitive?.content?.toLongOrNull()
                ?: throw ShuruException("Shuru did not return pid: ${result.stdout}")

            Napier.i { "ShuruVmManager: VM spawned agentId=${config.agentId} shuruVmId=$shuruVmId pid=$pid" }

            VmHandle(
                shuruVmId = shuruVmId,
                agentId = config.agentId,
                groupId = config.groupId,
                runId = config.runId,
                ipcPort = config.ipcPort,
                pid = pid
            )
        } catch (e: Exception) {
            Napier.e(e) { "ShuruVmManager: failed to spawn VM for agent ${config.agentId}: ${e.message}" }
            throw ShuruException("Failed to spawn VM for agent ${config.agentId}: ${e.message}", e)
        } finally {
            configFile.delete()
        }
    }

    /**
     * Hard-kills a running VM.
     *
     * @param handle The VM to kill.
     */
    suspend fun killVm(handle: VmHandle): Unit = withContext(Dispatchers.IO) {
        Napier.i { "ShuruVmManager: killing VM agentId=${handle.agentId} shuruVmId=${handle.shuruVmId}" }
        try {
            runShuruCommand("kill", handle.shuruVmId)
            Napier.i { "ShuruVmManager: VM killed agentId=${handle.agentId}" }
        } catch (e: Exception) {
            Napier.w(e) { "ShuruVmManager: failed to kill VM ${handle.shuruVmId} via CLI — falling back to process kill" }
            try {
                ProcessHandle.of(handle.pid).ifPresent { it.destroyForcibly() }
                Napier.i { "ShuruVmManager: fallback process kill succeeded for pid=${handle.pid}" }
            } catch (fallbackEx: Exception) {
                Napier.e(fallbackEx) { "ShuruVmManager: fallback kill also failed for VM ${handle.shuruVmId}" }
            }
        }
    }

    /**
     * Sends a UNIX signal to a running VM.
     *
     * Used by the LifecycleManager to send SIGTERM for graceful Death Note triggering.
     *
     * @param handle The target VM.
     * @param signal The signal name (e.g., "SIGTERM", "SIGKILL").
     */
    suspend fun signal(handle: VmHandle, signal: String): Unit = withContext(Dispatchers.IO) {
        Napier.i { "ShuruVmManager: sending $signal to VM agentId=${handle.agentId}" }
        try {
            runShuruCommand("signal", handle.shuruVmId, signal)
        } catch (e: Exception) {
            Napier.w(e) { "ShuruVmManager: failed to send $signal to VM ${handle.shuruVmId}: ${e.message}" }
            throw ShuruException("Failed to send $signal to VM ${handle.shuruVmId}: ${e.message}", e)
        }
    }

    /**
     * Queries the status of a VM.
     *
     * @param handle The VM to query.
     * @return The status string ("running", "stopped", "unknown").
     */
    suspend fun status(handle: VmHandle): String = withContext(Dispatchers.IO) {
        try {
            val result = runShuruCommand("status", handle.shuruVmId, "--json-output")
            val json = Json.parseToJsonElement(result.stdout).jsonObject
            val s = json["status"]?.jsonPrimitive?.content ?: "unknown"
            Napier.d { "ShuruVmManager: VM status agentId=${handle.agentId} status=$s" }
            s
        } catch (e: Exception) {
            Napier.w(e) { "ShuruVmManager: failed to query status of VM ${handle.shuruVmId}" }
            "unknown"
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun writeVmConfigFile(config: VmConfig): File {
        val configJson = buildVmConfigJson(config)
        val file = File(vmConfigDir, "vm-${config.agentId}.json")
        file.writeText(configJson)
        return file
    }

    private fun buildVmConfigJson(config: VmConfig): String {
        // Escape the boot context JSON for embedding in a JSON string value.
        // All backslashes and double-quotes must be escaped so the outer JSON remains valid.
        val escapedBootContext = config.bootContextJson
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")

        return """
            {
              "name": "${config.agentId}",
              "vcpu_count": ${config.cpuCount},
              "mem_size_mib": ${config.memoryMb},
              "disk_size_gib": ${config.diskGb},
              "mounts": [
                { "host": "${config.mounts.kbMountHostPath}", "guest": "/kb", "read_only": true },
                { "host": "${config.mounts.toolsMountHostPath}", "guest": "/tools", "read_only": true },
                { "host": "${config.mounts.stagingMountHostPath}", "guest": "/staging", "read_only": false, "write_only": true }${
                    config.mounts.artifactsMountHostPath?.let {
                        """,
                { "host": "$it", "guest": "/artifacts", "read_only": true }"""
                    } ?: ""
                }
              ],
              "network": {
                "allowed_domains": ${config.network.allowedDomains.joinToString(",", "[", "]") { "\"$it\"" }},
                "rate_limit_rpm": ${config.network.rateLimit},
                "ipc_target_host": "${config.network.ipcTargetHost}",
                "ipc_port": ${config.ipcPort}
              },
              "boot": {
                "command": ["java", "-jar", "${config.agentJarPath}"],
                "env": {
                  "EVOMOLIOR_BOOT_CONTEXT": "$escapedBootContext",
                  "EVOMOLIOR_LLM_BACKEND": "${config.llmBackend.id}",
                  "OPENROUTER_API_KEY": "${config.openRouterApiKey}",
                  "${config.llmBackend.apiKeyEnvVar}": "${config.openRouterApiKey}",
                  "EVOMOLIOR_GROUP_PROFILE": "${config.groupProfile}"
                }
              }
            }
        """.trimIndent()
    }

    private data class CliResult(val stdout: String, val stderr: String, val exitCode: Int)

    /**
     * Runs a Shuru CLI command with a default 60-second timeout.
     *
     * **Important**: stdout and stderr are read in background threads before [Process.waitFor]
     * to avoid the deadlock where the child process blocks on a full pipe buffer.
     */
    private fun runShuruCommand(vararg args: String, timeoutSec: Long = 60): CliResult {
        val command = listOf(shuruCliPath) + args.toList()
        Napier.d { "ShuruVmManager: running command: ${command.joinToString(" ")}" }
        return runCommandInternal(command, timeoutSec)
    }

    /**
     * Runs an arbitrary command (e.g., brew or curl for installation).
     */
    @Suppress("SameParameterValue")
    private fun runArbitraryCommand(args: List<String>, timeoutSec: Long = 60): CliResult {
        Napier.d { "ShuruVmManager: running command: ${args.joinToString(" ")}" }
        return runCommandInternal(args, timeoutSec)
    }

    private fun runCommandInternal(command: List<String>, timeoutSec: Long): CliResult {
        val process = ProcessBuilder(command)
            .redirectErrorStream(false)
            .start()

        // Read stdout and stderr in separate background threads to avoid pipe-buffer deadlock.
        val executor = Executors.newFixedThreadPool(2)
        val stdoutFuture: Future<String> = executor.submit<String> { process.inputStream.bufferedReader().readText() }
        val stderrFuture: Future<String> = executor.submit<String> { process.errorStream.bufferedReader().readText() }

        val finished = process.waitFor(timeoutSec, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            executor.shutdownNow()
            throw ShuruException("Command timed out after ${timeoutSec}s: ${command.joinToString(" ")}")
        }

        val stdout = runCatching { stdoutFuture.get(5, TimeUnit.SECONDS) }.getOrDefault("")
        val stderr = runCatching { stderrFuture.get(5, TimeUnit.SECONDS) }.getOrDefault("")
        executor.shutdown()

        val exitCode = process.exitValue()
        Napier.d { "ShuruVmManager: command exit=$exitCode stderr=$stderr" }

        if (exitCode != 0) {
            throw ShuruException(
                "Command returned exit code $exitCode: ${command.joinToString(" ")}\nstderr: $stderr"
            )
        }

        return CliResult(stdout = stdout.trim(), stderr = stderr.trim(), exitCode = exitCode)
    }
}

/**
 * Result of a Shuru installation/availability check.
 *
 * @param available Whether Shuru is available and functional.
 * @param version Version string from `shuru --version`, or null if not available.
 * @param message Human-readable status message for display in the UI.
 */
data class ShuruCheckResult(
    val available: Boolean,
    val version: String?,
    val message: String
)

/** Exception thrown when Shuru CLI operations fail. */
class ShuruException(message: String, cause: Throwable? = null) : Exception(message, cause)
