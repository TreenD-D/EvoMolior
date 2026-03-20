package com.achulkov.evokammer.sandbox.shuru

import io.github.aakira.napier.Napier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Manages Shuru microVM lifecycle by wrapping the Shuru CLI.
 *
 * Shuru CLI documentation: https://github.com/superhq-ai/shuru
 *
 * All operations are suspend functions dispatched on [Dispatchers.IO] since they
 * invoke external processes and may block.
 *
 * @param shuruCliPath Path to the `shuru` executable. Defaults to searching PATH.
 * @param vmConfigDir Directory to write temporary VM config JSON files.
 */
class ShuruVmManager(
    private val shuruCliPath: String = "shuru",
    private val vmConfigDir: File = File(System.getProperty("java.io.tmpdir"), "evokammer-vm-configs")
) {
    init {
        vmConfigDir.mkdirs()
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
        try {
            val result = runShuruCommand(
                "run",
                "--config", configFile.absolutePath,
                "--name", config.agentId,
                "--json-output"
            )

            val json = Json.parseToJsonElement(result.stdout).jsonObject
            val shuruVmId = json["vm_id"]?.jsonPrimitive?.content
                ?: throw ShuruException("Shuru did not return vm_id: ${result.stdout}")
            val pid = json["pid"]?.jsonPrimitive?.content?.toLongOrNull()
                ?: throw ShuruException("Shuru did not return pid: ${result.stdout}")

            Napier.i { "VM spawned: agentId=${config.agentId} shuruVmId=$shuruVmId pid=$pid" }

            VmHandle(
                shuruVmId = shuruVmId,
                agentId = config.agentId,
                groupId = config.groupId,
                runId = config.runId,
                ipcPort = config.ipcPort,
                pid = pid
            )
        } catch (e: Exception) {
            Napier.e(e) { "Failed to spawn VM for agent ${config.agentId}" }
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
        try {
            runShuruCommand("kill", handle.shuruVmId)
            Napier.i { "VM killed: agentId=${handle.agentId} shuruVmId=${handle.shuruVmId}" }
        } catch (e: Exception) {
            Napier.w(e) { "Failed to kill VM ${handle.shuruVmId} via Shuru CLI — falling back to process kill" }
            // Fallback: kill the host process directly
            try {
                ProcessHandle.of(handle.pid).ifPresent { it.destroyForcibly() }
            } catch (fallbackEx: Exception) {
                Napier.e(fallbackEx) { "Fallback kill also failed for VM ${handle.shuruVmId}" }
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
        try {
            runShuruCommand("signal", handle.shuruVmId, signal)
        } catch (e: Exception) {
            Napier.w(e) { "Failed to send $signal to VM ${handle.shuruVmId}" }
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
            json["status"]?.jsonPrimitive?.content ?: "unknown"
        } catch (e: Exception) {
            Napier.w(e) { "Failed to query status of VM ${handle.shuruVmId}" }
            "unknown"
        }
    }

    // ---- Private helpers ----

    private fun writeVmConfigFile(config: VmConfig): File {
        val configJson = buildVmConfigJson(config)
        val file = File(vmConfigDir, "vm-${config.agentId}.json")
        file.writeText(configJson)
        return file
    }

    private fun buildVmConfigJson(config: VmConfig): String {
        // Shuru VM config JSON format
        return """
            {
              "name": "${config.agentId}",
              "vcpu_count": ${config.cpuCount},
              "mem_size_mib": ${config.memoryMb},
              "disk_size_gib": ${config.diskGb},
              "mounts": [
                { "host": "${config.mounts.kbMountHostPath}", "guest": "/kb", "read_only": true },
                { "host": "${config.mounts.toolsMountHostPath}", "guest": "/tools", "read_only": true },
                { "host": "${config.mounts.stagingMountHostPath}", "guest": "/staging", "read_only": false, "write_only": true }
              ],
              "network": {
                "allowed_domains": ${config.network.allowedDomains.joinToString(",", "[", "]") { "\"$it\"" }},
                "rate_limit_rpm": ${config.network.rateLimit},
                "ipc_target_host": "${config.network.ipcTargetHost}",
                "ipc_port": ${config.ipcPort}
              },
              "boot": {
                "command": ["java", "-jar", "${config.agentJarPath}"],
                "env": {},
                "stdin": "${config.bootContextJson.replace("\"", "\\\"")}"
              }
            }
        """.trimIndent()
    }

    private data class CliResult(val stdout: String, val stderr: String, val exitCode: Int)

    private fun runShuruCommand(vararg args: String): CliResult {
        val command = listOf(shuruCliPath) + args.toList()
        val process = ProcessBuilder(command)
            .redirectErrorStream(false)
            .start()

        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()

        val finished = process.waitFor(60, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            throw ShuruException("Shuru CLI command timed out: ${command.joinToString(" ")}")
        }

        val exitCode = process.exitValue()
        if (exitCode != 0) {
            throw ShuruException(
                "Shuru CLI returned exit code $exitCode for command: ${command.joinToString(" ")}\nstderr: $stderr"
            )
        }

        return CliResult(stdout = stdout.trim(), stderr = stderr.trim(), exitCode = exitCode)
    }
}

/** Exception thrown when Shuru CLI operations fail. */
class ShuruException(message: String, cause: Throwable? = null) : Exception(message, cause)
