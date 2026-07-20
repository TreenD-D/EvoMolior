package com.achulkov.evomolior.sandbox.toolsandbox

import io.github.aakira.napier.Napier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.concurrent.Callable
import java.util.concurrent.Executors
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
            executeBlocking(toolExecutablePath, stdinData)
        }

    /**
     * Executes a tool synchronously through the same bounded subprocess semantics as [execute].
     *
     * Council field compilation uses this entry point before sealing executable reference tests,
     * while coroutine-based runtime callers should normally use [execute].
     *
     * @param toolExecutablePath Path to the executable.
     * @param stdinData JSON-serialised input passed through stdin.
     * @return The bounded execution result.
     */
    fun executeBlocking(toolExecutablePath: String, stdinData: String): ExecutionResult {
        val toolWorkspace = File(workspaceDir, File(toolExecutablePath).nameWithoutExtension)
        toolWorkspace.mkdirs()
        return runProcess(toolExecutablePath, stdinData, toolWorkspace)
    }

    private fun runProcess(toolPath: String, stdin: String, workdir: File): ExecutionResult {
        val process = ProcessBuilder(toolPath)
            .directory(workdir)
            .redirectErrorStream(false)
            .start()
        val readers = Executors.newFixedThreadPool(2) { runnable ->
            Thread(runnable, "evomolior-tool-output").apply { isDaemon = true }
        }
        return try {
            val stdoutFuture = readers.submit(Callable {
                process.inputStream.readBounded(maxOutputBytes, keepTail = false)
            })
            val stderrFuture = readers.submit(Callable {
                process.errorStream.readBounded(STDERR_LIMIT_BYTES, keepTail = true)
            })
            process.outputStream.use { it.write(stdin.toByteArray()) }

            val completed = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!completed) {
                process.destroyForcibly()
                process.waitFor(PROCESS_DESTROY_GRACE_MS, TimeUnit.MILLISECONDS)
                Napier.w { "Tool execution timed out: $toolPath (${timeoutMs}ms)" }
            }
            val stdoutCapture = stdoutFuture.get(STREAM_DRAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            val stderrCapture = stderrFuture.get(STREAM_DRAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            val exitCode = if (completed) process.exitValue() else -1
            val stdout = buildString {
                append(stdoutCapture.text)
                if (stdoutCapture.truncated) {
                    append("\n[OUTPUT_TRUNCATED: ${stdoutCapture.totalBytes}+ bytes total]")
                }
            }
            ExecutionResult(
                stdout = stdout,
                stderr = if (completed) {
                    stderrCapture.text
                } else {
                    "[TIMEOUT: execution exceeded ${timeoutMs}ms] ${stderrCapture.text}".trim()
                },
                exitCode = exitCode,
                truncated = stdoutCapture.truncated,
                timedOut = !completed
            )
        } finally {
            process.destroyForcibly()
            readers.shutdownNow()
        }
    }

    private fun InputStream.readBounded(limit: Int, keepTail: Boolean): StreamCapture {
        val retained = ByteArray(limit)
        val buffer = ByteArray(4_096)
        var retainedSize = 0
        var totalBytes = 0L
        use { stream ->
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                totalBytes += count
                if (keepTail) {
                    val combinedSize = retainedSize + count
                    if (combinedSize <= limit) {
                        buffer.copyInto(retained, retainedSize, 0, count)
                        retainedSize = combinedSize
                    } else if (count >= limit) {
                        buffer.copyInto(retained, 0, count - limit, count)
                        retainedSize = limit
                    } else {
                        val retainedFromOld = limit - count
                        retained.copyInto(
                            destination = retained,
                            destinationOffset = 0,
                            startIndex = retainedSize - retainedFromOld,
                            endIndex = retainedSize
                        )
                        buffer.copyInto(retained, retainedFromOld, 0, count)
                        retainedSize = limit
                    }
                } else if (retainedSize < limit) {
                    val copied = minOf(count, limit - retainedSize)
                    buffer.copyInto(retained, retainedSize, 0, copied)
                    retainedSize += copied
                }
            }
        }
        return StreamCapture(
            text = retained.copyOf(retainedSize).decodeToString(),
            totalBytes = totalBytes,
            truncated = totalBytes > limit
        )
    }

    private data class StreamCapture(
        val text: String,
        val totalBytes: Long,
        val truncated: Boolean
    )

    private companion object {
        const val STDERR_LIMIT_BYTES = 1_024
        const val PROCESS_DESTROY_GRACE_MS = 1_000L
        const val STREAM_DRAIN_TIMEOUT_MS = 2_000L
    }
}
