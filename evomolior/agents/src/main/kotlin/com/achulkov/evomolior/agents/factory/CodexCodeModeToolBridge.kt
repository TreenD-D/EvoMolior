package com.achulkov.evomolior.agents.factory

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.InternalAgentToolsApi
import ai.koog.serialization.kotlinx.KotlinxSerializer
import com.achulkov.evomolior.sandbox.enforcer.ActionEventType
import com.achulkov.evomolior.sandbox.enforcer.AgentAction
import com.achulkov.evomolior.sandbox.enforcer.ConstitutionalActionGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.writeText

private const val CODE_MODE_BRIDGE_SCRIPT = "evomolior-tool"
private const val CODE_MODE_REQUESTS_DIRECTORY = "requests"
private const val DEFAULT_TOOL_REQUEST_TIMEOUT_MILLIS = 300_000L

/**
 * Executes registered EvoMolior tools for Codex code-mode requests.
 *
 * @param toolRegistry Agent-scoped tool registry.
 * @param actionGate Constitutional boundary applied before every bridged tool call.
 * @param agentId Agent identity used in typed tool-call events.
 */
class CodexCodeModeToolRuntime(
    private val toolRegistry: ToolRegistry,
    private val actionGate: ConstitutionalActionGate,
    private val agentId: String
) {
    private val serializer = KotlinxSerializer(
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    )

    /** Descriptors exposed to the code-mode prompt. */
    val descriptors get() = toolRegistry.tools.map { it.descriptor }

    /**
     * Executes one tool request.
     *
     * @param toolName Registered tool name.
     * @param rawArgs JSON object accepted by the tool.
     * @return Tool result rendered for the model.
     */
    @OptIn(InternalAgentToolsApi::class)
    suspend fun execute(toolName: String, rawArgs: String): String {
        val tool = toolRegistry.getTool(toolName)
        actionGate.intercept(
            AgentAction(
                type = ActionEventType.TOOL_CALL,
                agentId = agentId,
                detail = toolName
            )
        )
        val decodedArgs: Any? = serializer.decodeFromString(rawArgs, tool.argsType)
        val result = tool.executeUnsafe(decodedArgs)
        return tool.encodeResultToStringUnsafe(result, serializer)
    }
}

/**
 * Isolated filesystem RPC session used by the Codex code-mode shell.
 *
 * Codex receives write access only to this temporary workspace. The generated helper command
 * atomically publishes a request directory and waits for the parent JVM to execute the matching
 * agent-scoped tool.
 */
class CodexCodeModeSession private constructor(
    val workspace: Path,
    private val requestRoot: Path,
    private val runtime: CodexCodeModeToolRuntime?,
    private val toolRequestTimeoutMillis: Long
) : AutoCloseable {
    val bridgeCommand: Path = workspace.resolve(CODE_MODE_BRIDGE_SCRIPT)

    /** Prompt instructions and typed tool descriptions for the current agent. */
    fun instructions(): String = buildString {
        appendLine("=== EVOMOLIOR CODE MODE ===")
        appendLine("Use Codex code mode for computation and tool orchestration.")
        if (runtime == null || runtime.descriptors.isEmpty()) {
            appendLine("No EvoMolior substrate tools are available in this invocation.")
            appendLine("Return the requested result directly.")
            return@buildString
        }
        appendLine("Call an EvoMolior tool from shell code with:")
        appendLine("${bridgeCommand.toAbsolutePath()} <tool-name> '<json-arguments>'")
        appendLine("The command prints the real tool result. Use it instead of describing a tool call.")
        appendLine("Available typed APIs:")
        runtime.descriptors.forEach { descriptor ->
            appendLine()
            appendLine(descriptor.toString())
        }
    }.trim()

    /** Executes every atomically published request that does not yet have a response. */
    suspend fun processPendingRequests() {
        val activeRuntime = runtime ?: return
        if (!requestRoot.exists()) return
        val requestDirectories = Files.list(requestRoot).use { paths ->
            paths.filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }.toList()
        }
        for (requestDirectory in requestDirectories) {
            val ready = requestDirectory.resolve("ready")
            val response = requestDirectory.resolve("response.txt")
            if (!Files.isRegularFile(ready, LinkOption.NOFOLLOW_LINKS) || response.exists()) continue
            val toolName = readRegularFile(requestDirectory.resolve("tool.txt")).trim()
            val rawArgs = readRegularFile(requestDirectory.resolve("args.json"))
            val result = try {
                withTimeoutOrNull(toolRequestTimeoutMillis) {
                    activeRuntime.execute(toolName, rawArgs)
                } ?: "ERROR: EvoMolior tool '$toolName' timed out after ${toolRequestTimeoutMillis}ms"
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                "ERROR: ${error.message ?: error::class.simpleName}"
            }
            val temporaryResponse = requestDirectory.resolve("response.tmp")
            Files.newBufferedWriter(
                temporaryResponse,
                Charsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS
            ).use { writer -> writer.write(result) }
            Files.move(
                temporaryResponse,
                response,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }

    private fun readRegularFile(path: Path): String {
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            "Invalid Codex code-mode request field: $path"
        }
        return Files.newInputStream(
            path,
            StandardOpenOption.READ,
            LinkOption.NOFOLLOW_LINKS
        ).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    override fun close() {
        workspace.toFile().deleteRecursively()
    }

    companion object {
        /** Creates one isolated code-mode workspace. */
        fun create(
            runtime: CodexCodeModeToolRuntime?,
            toolRequestTimeoutMillis: Long = DEFAULT_TOOL_REQUEST_TIMEOUT_MILLIS
        ): CodexCodeModeSession {
            require(toolRequestTimeoutMillis > 0) { "Tool request timeout must be positive" }
            val workspace = CodexTempFiles.createTempDirectory("evomolior-codex-workspace-")
            val requestRoot = workspace.resolve(CODE_MODE_REQUESTS_DIRECTORY).createDirectories()
            val script = workspace.resolve(CODE_MODE_BRIDGE_SCRIPT)
            script.writeText(
                """
                #!/bin/zsh
                set -eu
                if [[ ${'$'}# -ne 2 ]]; then
                  print -u2 "usage: evomolior-tool <tool-name> '<json-arguments>'"
                  exit 64
                fi
                script_dir=${'$'}{0:A:h}
                request_id=${'$'}(uuidgen | tr '[:upper:]' '[:lower:]')
                request_dir="${'$'}script_dir/$CODE_MODE_REQUESTS_DIRECTORY/${'$'}request_id"
                mkdir -p "${'$'}request_dir"
                printf '%s' "${'$'}1" > "${'$'}request_dir/tool.txt"
                printf '%s' "${'$'}2" > "${'$'}request_dir/args.json"
                : > "${'$'}request_dir/ready"
                for _ in {1..6000}; do
                  if [[ -f "${'$'}request_dir/response.txt" ]]; then
                    cat "${'$'}request_dir/response.txt"
                    exit 0
                  fi
                  sleep 0.1
                done
                print -u2 "EvoMolior tool bridge timed out"
                exit 70
                """.trimIndent()
            )
            runCatching {
                Files.setPosixFilePermissions(
                    script,
                    setOf(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE
                    )
                )
            }.onFailure {
                check(script.toFile().setExecutable(true, true)) {
                    "Could not make Codex code-mode bridge executable: $script"
                }
            }
            return CodexCodeModeSession(
                workspace = workspace,
                requestRoot = requestRoot,
                runtime = runtime,
                toolRequestTimeoutMillis = toolRequestTimeoutMillis
            )
        }
    }
}
