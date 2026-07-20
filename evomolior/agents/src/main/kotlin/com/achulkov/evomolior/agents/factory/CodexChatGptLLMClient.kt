package com.achulkov.evomolior.agents.factory

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.utils.time.KoogClock
import com.achulkov.evomolior.core.config.LlmBackend
import com.achulkov.evomolior.core.config.LlmTimeoutPolicy
import io.github.aakira.napier.Napier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

private const val CODEX_TMPDIR_ENV = "EVOMOLIOR_CODEX_TMPDIR"
private const val CODEX_MODEL_REASONING_EFFORT = "medium"
private const val CODEX_CAPACITY_RETRY_BUDGET = 2
private const val CODEX_CAPACITY_RETRY_DELAY_MS = 15_000L
internal const val CODEX_OUTPUT_SCHEMA_MAX_BYTES = 1024 * 1024
private const val CODEX_CAPACITY_MESSAGE =
    "Selected model is at capacity. Please try a different model."
internal const val CODEX_NATIVE_SEARCH_REQUIRED_MARKER = "CODEX_NATIVE_SEARCH_REQUIRED"

/**
 * Koog client adapter around the local OpenAI Codex CLI authenticated with ChatGPT.
 *
 * GPT-5.6 Luna is executed in Codex code mode. Agent-scoped EvoMolior tools are exposed through an
 * isolated filesystem bridge, allowing model-generated shell code to invoke the real typed tools
 * without granting the Codex workspace access to the repository or substrate files.
 *
 * @param cliPath Authenticated Codex CLI executable.
 * @param onSearchEvents Receives auditable native-search events.
 * @param codeModeToolRuntime Optional typed-tool bridge for code mode.
 * @param nativeSearchEnabled Whether the Codex process may use native web search.
 * @param executionTimeout Finite positive maximum for every process attempt.
 * @param structuredOutputDiagnosticRoot Optional owner-only root for bounded invalid-response
 *   evidence. Prompts, credentials, environment variables, stdout, and stderr are never retained.
 * @param processRunner Typed process seam used by deterministic tests.
 * @param capacityRetryDelay Suspends between structured model-capacity retries. The attempt number
 * starts at one.
 */
class CodexChatGptLLMClient(
    private val cliPath: String = CodexCliLocator.locate(),
    private val onSearchEvents: suspend (List<CodexSearchEvent>) -> Unit = {},
    private val codeModeToolRuntime: CodexCodeModeToolRuntime? = null,
    private val nativeSearchEnabled: Boolean = true,
    private val executionTimeout: Duration = LlmTimeoutPolicy.DEFAULT_CODEX_EXECUTION,
    structuredOutputDiagnosticRoot: Path? = null,
    private val processRunner: CodexProcessRunner = CodexExecProcess,
    private val capacityRetryDelay: suspend (attempt: Int) -> Unit = { attempt ->
        delay(CODEX_CAPACITY_RETRY_DELAY_MS * attempt)
    }
) : LLMClient() {
    private val structuredOutputDiagnostics =
        structuredOutputDiagnosticRoot?.let(::CodexStructuredOutputDiagnosticRecorder)

    init {
        LlmTimeoutPolicy.requireFinitePositive("Codex execution timeout", executionTimeout)
    }

    override suspend fun execute(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>
    ): Message.Assistant = withContext(Dispatchers.IO) {
        val outputPath = CodexTempFiles.createTempFile("evomolior-codex-", ".txt")
        var outputSchemaPath: Path? = null
        var codeModeSession: CodexCodeModeSession? = null
        try {
            val outputSchema = prompt.params.schema?.let(CodexOutputSchemaDocument::from)
            outputSchemaPath = outputSchema?.materialize()
            val structuredOutputOnly = outputSchema != null
            val session = CodexCodeModeSession.create(
                codeModeToolRuntime.takeUnless { structuredOutputOnly }
            )
            codeModeSession = session
            val renderedPrompt = renderPrompt(
                prompt = prompt,
                codeModeInstructions = session.instructions().takeUnless {
                    structuredOutputOnly
                }
            )
            val nativeSearchRequired = CODEX_NATIVE_SEARCH_REQUIRED_MARKER in renderedPrompt
            require(!structuredOutputOnly || !nativeSearchRequired) {
                "Codex structured output cannot be combined with mandatory native search"
            }
            suspend fun executeProcess(promptText: String): CodexProcessResult =
                processRunner.run(
                    CodexProcessRequest(
                        cliPath = cliPath,
                        modelId = model.id,
                        outputPath = outputPath,
                        outputSchemaPath = outputSchemaPath,
                        prompt = promptText,
                        codeModeSession = session,
                        nativeSearchEnabled =
                            nativeSearchEnabled && !structuredOutputOnly,
                        structuredOutputOnly = structuredOutputOnly,
                        executionTimeout = executionTimeout
                    )
                )
            suspend fun executeWithCapacityRetry(promptText: String): CodexProcessResult {
                var retry = 0
                while (true) {
                    val result = executeProcess(promptText)
                    val failure = CodexExecProcess.parseFailure(result)
                    if (result.exitCode == 0 ||
                        failure?.kind != CodexProcessFailureKind.MODEL_CAPACITY ||
                        retry >= CODEX_CAPACITY_RETRY_BUDGET
                    ) {
                        return result
                    }
                    retry += 1
                    Napier.w(
                        "Codex/ChatGPT model capacity unavailable; retrying " +
                            "$retry/$CODEX_CAPACITY_RETRY_BUDGET after bounded backoff"
                    )
                    capacityRetryDelay(retry)
                }
            }
            var result = executeWithCapacityRetry(renderedPrompt)
            if (
                result.exitCode == 0 &&
                result.searchEvents.isEmpty() &&
                nativeSearchRequired
            ) {
                Napier.w("Codex/ChatGPT completion omitted mandatory native search; retrying once")
                result = executeWithCapacityRetry(
                    """
                        MANDATORY RECOVERY: The previous completion used no native web search.
                        Before drafting any answer, execute at least two distinct native web-search
                        queries, inspect the results, and preserve the exact public URLs used.

                        $renderedPrompt
                    """.trimIndent()
                )
            }
            if (result.exitCode != 0) {
                error(
                    "Codex/ChatGPT backend failed with exit code ${result.exitCode}: " +
                        result.stderr.ifBlank { result.stdout }.trim()
                )
            }
            if (nativeSearchRequired && result.searchEvents.isEmpty()) {
                throw CodexNativeSearchRequiredException(modelId = model.id)
            }
            if (result.searchEvents.isNotEmpty()) {
                onSearchEvents(result.searchEvents)
            }
            val content = outputPath.takeIf { it.exists() }?.readText(Charsets.UTF_8)
                ?.takeIf { it.isNotBlank() }
                ?: result.stdout.trim()
            outputSchema?.let { schema ->
                val responseBytes = content.toByteArray(Charsets.UTF_8)
                val violations = CodexStructuredOutputValidator.validate(schema, responseBytes)
                if (violations.isNotEmpty()) {
                    val record = runCatching {
                        structuredOutputDiagnostics?.record(
                            promptId = prompt.id,
                            schema = schema,
                            response = responseBytes,
                            violations = violations
                        )
                    }.onFailure { failure ->
                        Napier.e(failure) {
                            "Could not retain bounded Codex structured-output diagnostics"
                        }
                    }.getOrNull()
                    Napier.w(
                        "Codex structured output violated emitted schema " +
                            "schemaSha256=${schema.sha256} " +
                            "responseSha256=${CodexStructuredOutputValidator.sha256(responseBytes)} " +
                            "violations=${violations.size}" +
                            (record?.root?.let { " diagnosticRoot=$it" } ?: "")
                    )
                }
            }
            Message.Assistant(
                content = content,
                metaInfo = ResponseMetaInfo.create(KoogClock.System, modelId = model.id)
            )
        } finally {
            try {
                codeModeSession?.close()
            } finally {
                try {
                    outputPath.deleteIfExists()
                } finally {
                    outputSchemaPath?.deleteIfExists()
                }
            }
        }
    }

    override fun executeStreaming(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>
    ): Flow<StreamFrame> = emptyFlow()

    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
        ModerationResult(isHarmful = false, categories = emptyMap())

    override suspend fun models(): List<LLModel> = listOf(
        CodexChatGptModelResolver.resolve(com.achulkov.evomolior.core.config.ModelSelectionConfig.CODEX_CHATGPT_GPT56_LUNA_MODEL)
    )

    override fun llmProvider(): LLMProvider = LLMProvider.OpenAI

    override fun close() = Unit

    private fun renderPrompt(prompt: Prompt, codeModeInstructions: String?): String = buildString {
        codeModeInstructions?.let(::appendLine)
        appendLine("EvoMolior prompt id: ${prompt.id}")
        prompt.messages.forEach { message ->
            appendLine()
            appendLine("=== ${message.role.name.uppercase()} ===")
            appendLine(message.textContent())
        }
    }.trim()

}

/**
 * Signals that a mandatory Codex native-search prompt omitted search on both execution attempts.
 *
 * @param modelId Codex model that violated the mandatory search contract.
 */
class CodexNativeSearchRequiredException(
    val modelId: String
) : IllegalStateException(
    "Codex/ChatGPT model '$modelId' omitted mandatory native web search after one retry."
)

object CodexCliLocator {
    fun locate(): String = LlmBackend.resolveCodexCliPath()
        ?: error(
            "Codex CLI was not found. Set EVOMOLIOR_CODEX_CLI or ${LlmBackend.CODEX_CLI_ENV_VAR}, " +
                "install the Codex app at ${LlmBackend.DEFAULT_CODEX_APP_CLI_PATH}, or put 'codex' on PATH."
        )
}

data class CodexProcessResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val searchEvents: List<CodexSearchEvent> = emptyList()
)

/**
 * Immutable input for one cancellable Codex process attempt.
 *
 * @param cliPath Codex CLI executable.
 * @param modelId Exact model identifier.
 * @param outputPath Last-message output file.
 * @param outputSchemaPath Optional provider-native JSON Schema file.
 * @param prompt Rendered prompt supplied over standard input.
 * @param codeModeSession Required isolated code-mode session.
 * @param nativeSearchEnabled Whether to expose native Codex search.
 * @param structuredOutputOnly Whether the command must expose no model tools.
 * @param executionTimeout Finite positive maximum for this attempt.
 */
data class CodexProcessRequest(
    val cliPath: String,
    val modelId: String,
    val outputPath: Path,
    val outputSchemaPath: Path? = null,
    val prompt: String,
    val codeModeSession: CodexCodeModeSession?,
    val nativeSearchEnabled: Boolean = true,
    val structuredOutputOnly: Boolean = false,
    val executionTimeout: Duration = LlmTimeoutPolicy.DEFAULT_CODEX_EXECUTION
) {
    init {
        LlmTimeoutPolicy.requireFinitePositive("Codex process request timeout", executionTimeout)
        require((outputSchemaPath != null) == structuredOutputOnly) {
            "Codex output schema and tool-free structured mode must be selected together"
        }
        require(!structuredOutputOnly || !nativeSearchEnabled) {
            "Codex tool-free structured mode cannot expose native search"
        }
    }
}

/**
 * Cancellable execution boundary for one [CodexProcessRequest].
 */
fun interface CodexProcessRunner {
    /**
     * Executes one request.
     *
     * @param request Complete typed process request.
     * @return Process result and captured search events.
     */
    suspend fun run(request: CodexProcessRequest): CodexProcessResult
}

/**
 * Auditable native Codex web-search event extracted from JSONL process output.
 *
 * @param query Primary search query.
 * @param queries Expanded query list reported by Codex.
 * @param rawJson Original completed-item JSONL record.
 */
data class CodexSearchEvent(
    val query: String,
    val queries: List<String>,
    val rawJson: String
)

object CodexExecProcess : CodexProcessRunner {
    /**
     * Builds one isolated `codex exec` command.
     *
     * @param cliPath Codex CLI executable.
     * @param modelId Exact model identifier.
     * @param outputPath Last-message output file.
     * @param workspace Isolated code-mode workspace.
     * @param outputSchemaPath Optional provider-native JSON Schema file.
     * @param nativeSearchEnabled Whether to expose native search.
     * @param structuredOutputOnly Whether to disable every Codex tool surface and ignore user
     *   tool configuration.
     * @return Ordered process arguments.
     */
    fun command(
        cliPath: String,
        modelId: String,
        outputPath: Path,
        workspace: Path,
        outputSchemaPath: Path? = null,
        nativeSearchEnabled: Boolean = true,
        structuredOutputOnly: Boolean = false
    ): List<String> {
        require((outputSchemaPath != null) == structuredOutputOnly) {
            "Codex output schema and tool-free structured mode must be selected together"
        }
        require(!structuredOutputOnly || !nativeSearchEnabled) {
            "Codex tool-free structured mode cannot expose native search"
        }
        return buildList {
            add(cliPath)
            if (nativeSearchEnabled) add("--search")
            add("exec")
            add("--json")
            if (structuredOutputOnly) {
                add("--ignore-user-config")
                listOf(
                    "shell_tool",
                    "unified_exec",
                    "code_mode",
                    "code_mode_only"
                ).forEach { feature ->
                    add("--disable")
                    add(feature)
                }
            }
            outputSchemaPath?.let { path ->
                add("--output-schema")
                add(path.toString())
            }
            add("-m")
            add(modelId)
            add("-c")
            add("model_reasoning_effort=\"$CODEX_MODEL_REASONING_EFFORT\"")
            if (!structuredOutputOnly) {
                add("-c")
                add("features.code_mode=true")
                add("-c")
                add("features.code_mode_only=true")
            }
            add("--ephemeral")
            add("--skip-git-repo-check")
            add("--ignore-rules")
            add("-C")
            add(workspace.toString())
            add("-s")
            add(if (structuredOutputOnly) "read-only" else "workspace-write")
            add("-o")
            add(outputPath.toString())
            add("-")
        }
    }

    /**
     * Executes one cancellable Codex process while servicing code-mode tool requests.
     *
     * @param cliPath Codex CLI executable.
     * @param modelId Exact model identifier.
     * @param outputPath Last-message output file.
     * @param outputSchemaPath Optional provider-native JSON Schema file.
     * @param prompt Rendered prompt supplied over standard input.
     * @param codeModeSession Required isolated code-mode session.
     * @param nativeSearchEnabled Whether to expose native search.
     * @return Process result and captured search events.
     */
    override suspend fun run(request: CodexProcessRequest): CodexProcessResult {
        val stdoutPath = CodexTempFiles.createTempFile("evomolior-codex-stdout-", ".jsonl")
        val stderrPath = CodexTempFiles.createTempFile("evomolior-codex-stderr-", ".txt")
        val stdinPath = CodexTempFiles.createTempFile("evomolior-codex-stdin-", ".txt")
        var process: Process? = null
        try {
            stdinPath.writeText(request.prompt, Charsets.UTF_8)
            val session = requireNotNull(request.codeModeSession) { "Codex code-mode session is required" }
            val launchedProcess = ProcessBuilder(
                command(
                    request.cliPath,
                    request.modelId,
                    request.outputPath,
                    session.workspace,
                    request.outputSchemaPath,
                    request.nativeSearchEnabled,
                    request.structuredOutputOnly
                )
            )
                .redirectInput(stdinPath.toFile())
                .redirectError(stderrPath.toFile())
                .redirectOutput(stdoutPath.toFile())
                .start()
            process = launchedProcess
            val startedAtNanos = System.nanoTime()
            val timeoutNanos = request.executionTimeout.inWholeNanoseconds.coerceAtLeast(1L)
            var completed = false
            while (
                !completed &&
                timeoutNanos - (System.nanoTime() - startedAtNanos) > 0L
            ) {
                currentCoroutineContext().ensureActive()
                session.processPendingRequests()
                completed = launchedProcess.waitFor(100, TimeUnit.MILLISECONDS)
            }
            session.processPendingRequests()
            if (!completed) {
                launchedProcess.destroyForcibly()
                launchedProcess.waitFor(5, TimeUnit.SECONDS)
            }
            val stdout = stdoutPath.readText(Charsets.UTF_8)
            val stderr = stderrPath.readText(Charsets.UTF_8)
            if (!completed) {
                return CodexProcessResult(
                    exitCode = -1,
                    stdout = stdout,
                    stderr = stderr.ifBlank {
                        "Codex exec timed out after ${formatTimeout(request.executionTimeout)}."
                    },
                    searchEvents = parseSearchEvents(stdout)
                )
            }
            return CodexProcessResult(
                exitCode = launchedProcess.exitValue(),
                stdout = stdout,
                stderr = stderr,
                searchEvents = parseSearchEvents(stdout)
            )
        } finally {
            process?.takeIf { it.isAlive }?.let { activeProcess ->
                activeProcess.destroy()
                if (!activeProcess.waitFor(1, TimeUnit.SECONDS)) {
                    activeProcess.destroyForcibly()
                    activeProcess.waitFor(5, TimeUnit.SECONDS)
                }
            }
            stdoutPath.deleteIfExists()
            stderrPath.deleteIfExists()
            stdinPath.deleteIfExists()
        }
    }

    private fun formatTimeout(timeout: Duration): String = when {
        timeout.inWholeMinutes.minutes == timeout -> "${timeout.inWholeMinutes} minutes"
        timeout.inWholeMilliseconds.milliseconds == timeout ->
            "${timeout.inWholeMilliseconds} milliseconds"
        else -> timeout.toString()
    }

    internal fun parseSearchEvents(stdout: String): List<CodexSearchEvent> {
        val json = Json { ignoreUnknownKeys = true }
        return stdout.lineSequence().mapNotNull { line ->
            val root = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull()
                ?: return@mapNotNull null
            if (root["type"]?.jsonPrimitive?.content != "item.completed") return@mapNotNull null
            val item = root["item"] as? JsonObject ?: return@mapNotNull null
            if (item["type"]?.jsonPrimitive?.content != "web_search") return@mapNotNull null
            val action = item["action"] as? JsonObject
            val query = item["query"]?.jsonPrimitive?.content.orEmpty()
                .ifBlank { action?.get("query")?.jsonPrimitive?.content.orEmpty() }
            val queries = (action?.get("queries") as? JsonArray)
                ?.mapNotNull { element -> runCatching { element.jsonPrimitive.content }.getOrNull() }
                .orEmpty()
                .ifEmpty { listOfNotNull(query.takeIf { it.isNotBlank() }) }
            CodexSearchEvent(query = query, queries = queries, rawJson = line)
        }.toList()
    }

    internal fun parseFailure(result: CodexProcessResult): CodexProcessFailure? {
        if (result.exitCode == 0) return null
        val json = Json { ignoreUnknownKeys = true }
        val messages = sequenceOf(result.stdout, result.stderr)
            .flatMap(String::lineSequence)
            .mapNotNull { line ->
                val root = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull()
                    ?: return@mapNotNull null
                when (root["type"]?.jsonPrimitive?.content) {
                    "turn.failed" -> (root["error"] as? JsonObject)
                        ?.get("message")
                        ?.jsonPrimitive
                        ?.content
                    "error" -> root["message"]?.jsonPrimitive?.content
                    else -> null
                }
            }
            .distinct()
            .toList()
        if (messages.isEmpty()) return null
        return CodexProcessFailure(
            kind = if (CODEX_CAPACITY_MESSAGE in messages) {
                CodexProcessFailureKind.MODEL_CAPACITY
            } else {
                CodexProcessFailureKind.OTHER
            },
            messages = messages
        )
    }
}

internal enum class CodexProcessFailureKind {
    MODEL_CAPACITY,
    OTHER
}

internal data class CodexProcessFailure(
    val kind: CodexProcessFailureKind,
    val messages: List<String>
)

object CodexTempFiles {
    fun createTempFile(prefix: String, suffix: String): Path {
        val configuredDir = System.getenv(CODEX_TMPDIR_ENV)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let(Path::of)
        val directory = configuredDir ?: Path.of(System.getProperty("java.io.tmpdir"))
        Files.createDirectories(directory)
        return Files.createTempFile(directory, prefix, suffix)
    }

    fun createTempDirectory(prefix: String): Path {
        val directory = System.getenv(CODEX_TMPDIR_ENV)
            ?.takeIf { it.isNotBlank() }
            ?.let(Path::of)
            ?.also(Files::createDirectories)
        return if (directory == null) {
            Files.createTempDirectory(prefix)
        } else {
            Files.createTempDirectory(directory, prefix)
        }
    }
}
