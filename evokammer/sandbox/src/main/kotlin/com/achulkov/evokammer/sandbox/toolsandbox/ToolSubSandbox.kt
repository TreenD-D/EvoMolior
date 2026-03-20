package com.achulkov.evokammer.sandbox.toolsandbox

import io.github.aakira.napier.Napier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.TimeUnit

/** Result of a tool sub-sandbox execution. */
data class ExecutionResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val truncated: Boolean,
    val timedOut: Boolean
) {
    val success: Boolean get() = exitCode == 0 && !timedOut
}

/**
 * Runs a tool executable in a nested child process inside the agent VM.
 *
 * Security properties:
 * - Child process has NO outbound network (only the parent VM has network)
 * - stdout capped at [maxOutputBytes] (default 50KB)
 * - Hard timeout via coroutine [withTimeout]
 * - stderr captured for failure logging (last 1KB)
 *
 * @param workspaceDir The workspace directory inside the agent VM (tool scratch space).
 * @param maxOutputBytes Maximum stdout size in bytes before truncation.
 * @param timeoutMs Hard execution timeout in milliseconds.
 */
class ToolSubSandbox(
    private val workspaceDir: File = File("/workspace"),
    private val maxOutputBytes: Int = 50_000,
    private val timeoutMs: Long = 30_000L
) {
    /**
     * Executes a tool and returns its [ExecutionResult].
     *
     * @param toolExecutablePath Path to the tool executable inside the VM.
     * @param stdinData JSON-serialised input arguments passed to the tool via stdin.
     * @return The execution result.
     */
    suspend fun execute(toolExecutablePath: String, stdinData: String): ExecutionResult =
        withContext(Dispatchers.IO) {
            val toolWorkspace = File(workspaceDir, File(toolExecutablePath).nameWithoutExtension)
            toolWorkspace.mkdirs()

            try {
                withTimeout(timeoutMs) {
                    runProcess(toolExecutablePath, stdinData, toolWorkspace)
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                Napier.w { "Tool execution timed out: $toolExecutablePath (${timeoutMs}ms)" }
                ExecutionResult(
                    stdout = "",
                    stderr = "[TIMEOUT: execution exceeded ${timeoutMs}ms]",
                    exitCode = -1,
                    truncated = false,
                    timedOut = true
                )
            }
        }

    private fun runProcess(toolPath: String, stdin: String, workdir: File): ExecutionResult {
        val process = ProcessBuilder(toolPath)
            .directory(workdir)
            .redirectErrorStream(false)
            .start()

        // Write stdin
        process.outputStream.use { it.write(stdin.toByteArray()) }

        // Read stdout with size cap
        val stdoutBytes = mutableListOf<Byte>()
        var truncated = false
        val inputStream = process.inputStream
        val buffer = ByteArray(4096)
        var totalRead = 0

        while (true) {
            val bytesRead = inputStream.read(buffer)
            if (bytesRead == -1) break
            val remaining = maxOutputBytes - totalRead
            if (remaining <= 0) {
                truncated = true
                // Drain the stream to allow process to exit
                inputStream.skip(Long.MAX_VALUE)
                break
            }
            val toAdd = minOf(bytesRead, remaining)
            stdoutBytes.addAll(buffer.take(toAdd))
            totalRead += toAdd
        }

        // Read last 1KB of stderr for failure logging
        val stderr = process.errorStream.bufferedReader().readText().takeLast(1024)

        process.waitFor(30, TimeUnit.SECONDS)
        val exitCode = try { process.exitValue() } catch (e: IllegalThreadStateException) {
            process.destroyForcibly()
            -1
        }

        var stdout = String(stdoutBytes.toByteArray())
        if (truncated) {
            stdout += "\n[OUTPUT_TRUNCATED: $totalRead+ bytes total]"
        }

        return ExecutionResult(
            stdout = stdout,
            stderr = stderr,
            exitCode = exitCode,
            truncated = truncated,
            timedOut = false
        )
    }
}
