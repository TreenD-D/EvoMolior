package com.achulkov.evomolior.agents.factory

import ai.koog.prompt.dsl.prompt as koogPrompt
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import com.achulkov.evomolior.core.config.LlmBackend
import com.achulkov.evomolior.core.config.ModelSelectionConfig
import com.achulkov.evomolior.core.model.Role
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.io.path.createTempFile
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class LlmExecutorFactoryTest {
    @Test
    fun `resolves qwen cloud models as Alibaba provider models`() {
        val qwen = LlmExecutorFactory.resolveModel(
            LlmBackend.QWEN_CLOUD,
            ModelSelectionConfig.QWEN_CLOUD_QWEN_PLUS_MODEL
        )
        val qwenFast = LlmExecutorFactory.resolveModel(
            LlmBackend.QWEN_CLOUD,
            ModelSelectionConfig.QWEN_CLOUD_QWEN_FAST_MODEL
        )
        val deepseek = LlmExecutorFactory.resolveModel(
            LlmBackend.QWEN_CLOUD,
            ModelSelectionConfig.QWEN_CLOUD_DEEPSEEK_FLASH_MODEL
        )

        assertEquals(LLMProvider.Alibaba, qwen.provider)
        assertEquals("qwen3.7-plus", qwen.id)
        assertTrue(qwen.supports(LLMCapability.Tools))
        assertTrue(qwen.supports(LLMCapability.Schema.JSON.Standard))

        assertEquals(LLMProvider.Alibaba, qwenFast.provider)
        assertEquals("qwen3.6-flash", qwenFast.id)
        assertTrue(qwenFast.supports(LLMCapability.Tools))
        assertTrue(qwenFast.supports(LLMCapability.ToolChoice))

        assertEquals(LLMProvider.Alibaba, deepseek.provider)
        assertEquals("deepseek-v4-flash", deepseek.id)
        assertTrue(deepseek.supports(LLMCapability.Tools))
        assertTrue(deepseek.supports(LLMCapability.ToolChoice))
    }

    @Test
    fun `qwen cloud executor constructs when Dashscope client is present`() {
        val executor = LlmExecutorFactory.create(LlmBackend.QWEN_CLOUD, "test-key")

        assertNotNull(executor)
    }

    @Test
    fun `qwen cloud model resolver rejects OpenRouter style model ids`() {
        val failure = assertFailsWith<IllegalStateException> {
            LlmExecutorFactory.resolveModel(LlmBackend.QWEN_CLOUD, ModelSelectionConfig.DEFAULT_GRADER_MODEL)
        }

        assertTrue(failure.message.orEmpty().contains(ModelSelectionConfig.DEFAULT_GRADER_MODEL))
    }

    @Test
    fun `resolves codex chatgpt gpt56 luna as OpenAI text model`() {
        val model = LlmExecutorFactory.resolveModel(
            LlmBackend.CODEX_CHATGPT,
            ModelSelectionConfig.CODEX_CHATGPT_GPT56_LUNA_MODEL
        )

        assertEquals(LLMProvider.OpenAI, model.provider)
        assertEquals("gpt-5.6-luna", model.id)
        assertTrue(model.supports(LLMCapability.Completion))
        assertTrue(model.supports(LLMCapability.Schema.JSON.Standard))
        assertFalse(model.supports(LLMCapability.Tools))
    }

    @Test
    fun `codex exec command uses isolated code mode flags`() {
        val command = CodexExecProcess.command(
            cliPath = "/tmp/codex",
            modelId = ModelSelectionConfig.CODEX_CHATGPT_GPT56_LUNA_MODEL,
            outputPath = java.nio.file.Path.of("/tmp/last-message.txt"),
            workspace = java.nio.file.Path.of("/tmp/code-mode-workspace"),
            reasoningEffort = CodexReasoningEffort.MEDIUM
        )

        assertEquals(
            listOf(
                "/tmp/codex",
                "--search",
                "exec",
                "--json",
                "-m",
                "gpt-5.6-luna",
                "-c",
                "model_reasoning_effort=\"medium\"",
                "-c",
                "features.code_mode=true",
                "-c",
                "features.code_mode_only=true",
                "--ephemeral",
                "--skip-git-repo-check",
                "--ignore-rules",
                "-C",
                "/tmp/code-mode-workspace",
                "-s",
                "workspace-write",
                "-o",
                "/tmp/last-message.txt",
                "-"
            ),
            command
        )
    }

    @Test
    fun `codex reasoning effort resolves validated overrides with high default`() {
        assertEquals(CodexReasoningEffort.HIGH, CodexReasoningEffort.resolve(null))
        assertEquals(CodexReasoningEffort.LOW, CodexReasoningEffort.resolve("low"))
        assertEquals(CodexReasoningEffort.MEDIUM, CodexReasoningEffort.resolve("medium"))
        assertEquals(CodexReasoningEffort.HIGH, CodexReasoningEffort.resolve("high"))

        val failure = assertFailsWith<IllegalArgumentException> {
            CodexReasoningEffort.resolve("maximum")
        }
        assertTrue(failure.message.orEmpty().contains(CodexReasoningEffort.ENV_VAR))
        assertTrue(failure.message.orEmpty().contains("low, medium, or high"))
    }

    @Test
    fun `codex exec command emits explicit high reasoning effort`() {
        val command = CodexExecProcess.command(
            cliPath = "/tmp/codex",
            modelId = ModelSelectionConfig.CODEX_CHATGPT_GPT56_LUNA_MODEL,
            outputPath = java.nio.file.Path.of("/tmp/last-message.txt"),
            workspace = java.nio.file.Path.of("/tmp/code-mode-workspace"),
            reasoningEffort = CodexReasoningEffort.HIGH
        )

        assertEquals(
            "model_reasoning_effort=\"high\"",
            command[command.indexOf("-c") + 1]
        )
    }

    @Test
    fun `codex exec command can disable native search for matched tool experiments`() {
        val command = CodexExecProcess.command(
            cliPath = "/tmp/codex",
            modelId = ModelSelectionConfig.CODEX_CHATGPT_GPT56_LUNA_MODEL,
            outputPath = java.nio.file.Path.of("/tmp/last-message.txt"),
            workspace = java.nio.file.Path.of("/tmp/code-mode-workspace"),
            nativeSearchEnabled = false,
            reasoningEffort = CodexReasoningEffort.MEDIUM
        )

        assertFalse("--search" in command)
        assertEquals("exec", command[1])
    }

    @Test
    fun `codex exec command carries an output schema only when supplied`() {
        val schemaPath = java.nio.file.Path.of("/tmp/council-output-schema.json")
        val command = CodexExecProcess.command(
            cliPath = "/tmp/codex",
            modelId = ModelSelectionConfig.CODEX_CHATGPT_GPT56_LUNA_MODEL,
            outputPath = java.nio.file.Path.of("/tmp/last-message.txt"),
            workspace = java.nio.file.Path.of("/tmp/code-mode-workspace"),
            outputSchemaPath = schemaPath,
            nativeSearchEnabled = false,
            structuredOutputOnly = true,
            reasoningEffort = CodexReasoningEffort.MEDIUM
        )

        val schemaFlagIndex = command.indexOf("--output-schema")
        assertTrue(schemaFlagIndex >= 0)
        assertEquals(schemaPath.toString(), command[schemaFlagIndex + 1])
        assertTrue("--ignore-user-config" in command)
        assertFalse("--search" in command)
        assertFalse("features.code_mode=true" in command)
        assertFalse("features.code_mode_only=true" in command)
        assertEquals(
            listOf("shell_tool", "unified_exec", "code_mode", "code_mode_only"),
            command.withIndex()
                .filter { it.value == "--disable" }
                .map { command[it.index + 1] }
        )
        assertEquals("read-only", command[command.indexOf("-s") + 1])
    }

    @Test
    fun `codex exec rejects output schema without tool-free transport`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            CodexExecProcess.command(
                cliPath = "/tmp/codex",
                modelId = ModelSelectionConfig.CODEX_CHATGPT_GPT56_LUNA_MODEL,
                outputPath = java.nio.file.Path.of("/tmp/last-message.txt"),
                workspace = java.nio.file.Path.of("/tmp/code-mode-workspace"),
                outputSchemaPath = java.nio.file.Path.of("/tmp/council-output-schema.json"),
                reasoningEffort = CodexReasoningEffort.MEDIUM
            )
        }

        assertTrue(failure.message.orEmpty().contains("tool-free structured mode"))
    }

    @Test
    fun `codex jsonl parser preserves completed native web searches`() {
        val events = CodexExecProcess.parseSearchEvents(
            """
            {"type":"thread.started","thread_id":"thread-1"}
            {"type":"item.started","item":{"type":"web_search","query":""}}
            {"type":"item.completed","item":{"id":"item-1","type":"web_search","query":"Kotlin official homepage","action":{"type":"search","query":"Kotlin official homepage","queries":["Kotlin official homepage","site:kotlinlang.org Kotlin"]}}}
            {"type":"item.completed","item":{"id":"item-2","type":"agent_message","text":"Done"}}
            """.trimIndent()
        )

        assertEquals(1, events.size)
        assertEquals("Kotlin official homepage", events.single().query)
        assertEquals(
            listOf("Kotlin official homepage", "site:kotlinlang.org Kotlin"),
            events.single().queries
        )
        assertTrue(events.single().rawJson.contains("\"web_search\""))
    }

    @Test
    fun `codex process with pipe-sized prompt is cancelled promptly by coroutine timeout`() = runBlocking {
        val fakeCodex = createTempFile("cancellable-codex-", ".sh")
        fakeCodex.writeText("#!/bin/sh\nexec sleep 30\n")
        fakeCodex.toFile().setExecutable(true)
        val outputPath = createTempFile("cancellable-codex-output-", ".txt")
        val startedAt = System.nanoTime()
        val codeModeSession = CodexCodeModeSession.create(runtime = null)

        try {
            assertFailsWith<TimeoutCancellationException> {
                withTimeout(250L) {
                    CodexExecProcess.run(
                        CodexProcessRequest(
                            cliPath = fakeCodex.toString(),
                            modelId = ModelSelectionConfig.CODEX_CHATGPT_GPT56_LUNA_MODEL,
                            outputPath = outputPath,
                            // A child that does not consume stdin used to block the parent before it
                            // reached the cancellable waitFor call once this exceeded pipe capacity.
                            prompt = "x".repeat(2 * 1024 * 1024),
                            codeModeSession = codeModeSession,
                            reasoningEffort = CodexReasoningEffort.MEDIUM
                        )
                    )
                }
            }
        } finally {
            codeModeSession.close()
        }

        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        assertTrue(elapsedMs < 5_000, "Cancellation took ${elapsedMs}ms")
    }

    @Test
    fun `codex process enforces configured inner timeout and terminates its child`() = runBlocking {
        val fakeCodex = createTempFile("inner-timeout-codex-", ".sh")
        val pidPath = createTempFile("inner-timeout-codex-", ".pid")
        fakeCodex.writeText(
            """
            #!/bin/sh
            printf '%s' "${'$'}${'$'}" > "${pidPath}"
            exec sleep 30
            """.trimIndent()
        )
        fakeCodex.toFile().setExecutable(true)
        val outputPath = createTempFile("inner-timeout-codex-output-", ".txt")
        val codeModeSession = CodexCodeModeSession.create(runtime = null)

        val result = try {
            CodexExecProcess.run(
                CodexProcessRequest(
                    cliPath = fakeCodex.toString(),
                    modelId = ModelSelectionConfig.CODEX_CHATGPT_GPT56_LUNA_MODEL,
                    outputPath = outputPath,
                    prompt = "bounded request",
                    codeModeSession = codeModeSession,
                    reasoningEffort = CodexReasoningEffort.MEDIUM,
                    executionTimeout = 1_000.milliseconds
                )
            )
        } finally {
            codeModeSession.close()
        }

        assertEquals(-1, result.exitCode)
        assertTrue(result.stderr.contains("1000 milliseconds"))
        val pidText = pidPath.toFile().readText().trim()
        assertTrue(pidText.isNotEmpty(), "Fake Codex process did not publish its PID")
        val pid = pidText.toLong()
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false))
    }

    @Test
    fun `codex council call with pipe-sized context honors coroutine deadline`() = runBlocking {
        val fakeCodex = createTempFile("cancellable-council-codex-", ".sh")
        fakeCodex.writeText("#!/bin/sh\nexec sleep 30\n")
        fakeCodex.toFile().setExecutable(true)
        val runner = CouncilRunner(
            openRouterApiKey = fakeCodex.toString(),
            executionEnvelope = councilTestExecutionEnvelope(),
            councilModelId = ModelSelectionConfig.CODEX_CHATGPT_GPT56_LUNA_MODEL,
            llmBackend = LlmBackend.CODEX_CHATGPT
        )
        val startedAt = System.nanoTime()

        assertFailsWith<TimeoutCancellationException> {
            withTimeout(250L) {
                runner.runCouncilAgent(
                    role = Role.CouncilTaskAnatomist,
                    contextJson = """{"largeContext":"${"x".repeat(2 * 1024 * 1024)}"}"""
                )
            }
        }

        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        assertTrue(elapsedMs < 5_000, "Council cancellation took ${elapsedMs}ms")
    }

    @Test
    fun `codex executor invokes provided cli path as backend credential`() = runBlocking {
        val fakeCodex = createTempFile("explicit-codex-", ".sh")
        fakeCodex.writeText(
            """
            #!/bin/sh
            output_file=""
            while [ "$#" -gt 0 ]; do
              if [ "$1" = "-o" ]; then
                shift
                output_file="$1"
              fi
              shift
            done
            printf 'FAKE_CODEX_RESPONSE' > "${'$'}output_file"
            """.trimIndent()
        )
        fakeCodex.toFile().setExecutable(true)

        val executor = LlmExecutorFactory.create(LlmBackend.CODEX_CHATGPT, fakeCodex.toString())
        val response = executor.execute(
            prompt = koogPrompt("codex-explicit-cli-path") {
                user("Use the fake Codex executable.")
            },
            model = LlmExecutorFactory.resolveModel(
                LlmBackend.CODEX_CHATGPT,
                ModelSelectionConfig.CODEX_CHATGPT_GPT56_LUNA_MODEL
            ),
            tools = emptyList()
        )

        assertEquals("FAKE_CODEX_RESPONSE", response.textContent())
    }
}
