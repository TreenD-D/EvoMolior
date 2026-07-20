package com.achulkov.evomolior.agents.factory

import ai.koog.http.client.KoogHttpClient
import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.openrouter.OpenRouterLLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.agents.core.tools.ToolRegistry
import com.achulkov.evomolior.core.config.LlmBackend
import com.achulkov.evomolior.core.config.LlmTimeoutPolicy
import com.achulkov.evomolior.core.config.ModelSelectionConfig
import com.achulkov.evomolior.sandbox.enforcer.ConstitutionalActionGate
import java.nio.file.Path
import kotlin.time.Duration

/**
 * Provider-neutral Koog executor and model resolver.
 *
 * OpenRouter remains the default path. Qwen Cloud is wired through Koog's Dashscope client behind
 * a narrow reflection boundary because the available Dashscope artifact is currently a preview
 * release while the rest of Koog is pinned to the stable runtime.
 */
object LlmExecutorFactory {
    fun create(
        backend: LlmBackend,
        apiKey: String,
        onCodexSearchEvents: suspend (List<CodexSearchEvent>) -> Unit = {},
        codexToolRegistry: ToolRegistry = ToolRegistry.EMPTY,
        codexActionGate: ConstitutionalActionGate = ConstitutionalActionGate.NONE,
        codexAgentId: String = "codex-agent",
        codexNativeSearchEnabled: Boolean = true,
        codexExecutionTimeout: Duration = LlmTimeoutPolicy.DEFAULT_CODEX_EXECUTION,
        codexStructuredOutputDiagnosticRoot: Path? = null
    ): MultiLLMPromptExecutor = when (backend) {
        LlmBackend.OPENROUTER -> createOpenRouterPromptExecutor(apiKey)
        LlmBackend.QWEN_CLOUD -> createQwenCloudPromptExecutor(apiKey)
        LlmBackend.CODEX_CHATGPT -> MultiLLMPromptExecutor(
            LLMProvider.OpenAI to CodexChatGptLLMClient(
                cliPath = apiKey,
                onSearchEvents = onCodexSearchEvents,
                nativeSearchEnabled = codexNativeSearchEnabled,
                executionTimeout = codexExecutionTimeout,
                structuredOutputDiagnosticRoot = codexStructuredOutputDiagnosticRoot,
                codeModeToolRuntime = CodexCodeModeToolRuntime(
                    toolRegistry = codexToolRegistry,
                    actionGate = codexActionGate,
                    agentId = codexAgentId
                )
            )
        )
    }

    fun resolveModel(
        backend: LlmBackend,
        modelId: String
    ): LLModel = when (backend) {
        LlmBackend.OPENROUTER -> OpenRouterModelResolver.resolve(modelId)
        LlmBackend.QWEN_CLOUD -> QwenCloudModelResolver.resolve(modelId)
        LlmBackend.CODEX_CHATGPT -> CodexChatGptModelResolver.resolve(modelId)
    }

    private fun createQwenCloudPromptExecutor(apiKey: String): MultiLLMPromptExecutor {
        val clientClass = runCatching {
            Class.forName(LlmBackend.DASHSCOPE_CLIENT_CLASS_NAME)
        }.getOrElse { error(missingDashscopeMessage()) }

        val client = runCatching {
            clientClass
                .getConstructor(String::class.java, KoogHttpClient.Factory::class.java)
                .newInstance(apiKey, KtorKoogHttpClient.Factory()) as LLMClient
        }.getOrElse { cause ->
            error("Failed to construct Koog DashscopeLLMClient for Qwen Cloud backend: ${cause.message}")
        }

        return MultiLLMPromptExecutor(
            LLMProvider.Alibaba to DashscopeToolCompatibleLLMClient(
                apiKey = apiKey,
                delegate = client
            )
        )
    }

    private fun missingDashscopeMessage(): String =
        "Qwen Cloud backend requires Koog Dashscope client on the classpath " +
            "(${LlmBackend.DASHSCOPE_CLIENT_CLASS_NAME}, added by Koog PR #687). " +
            "OpenRouter remains available; add the prompt-executor-dashscope-client artifact " +
            "for the pinned Koog version before running with -llm-backend qwen-cloud."
}

object QwenCloudModelResolver {
    private val standardCapabilities: List<LLMCapability> = listOf(
        LLMCapability.Temperature,
        LLMCapability.Tools,
        LLMCapability.ToolChoice,
        LLMCapability.Completion,
        LLMCapability.Schema.JSON.Standard
    )

    fun resolve(modelId: String): LLModel = when (modelId) {
        ModelSelectionConfig.QWEN_CLOUD_QWEN_PLUS_MODEL -> qwen37Plus()
        ModelSelectionConfig.QWEN_CLOUD_QWEN_FAST_MODEL -> qwen36Fast()
        ModelSelectionConfig.QWEN_CLOUD_DEEPSEEK_FLASH_MODEL -> deepSeekV4Flash()
        else -> error("Unsupported Qwen Cloud model id: $modelId")
    }

    private fun qwen37Plus(): LLModel = LLModel(
        provider = LLMProvider.Alibaba,
        id = ModelSelectionConfig.QWEN_CLOUD_QWEN_PLUS_MODEL,
        capabilities = standardCapabilities + LLMCapability.Thinking,
        contextLength = 262_144,
        maxOutputTokens = 65_536
    )

    private fun qwen36Fast(): LLModel = LLModel(
        provider = LLMProvider.Alibaba,
        id = ModelSelectionConfig.QWEN_CLOUD_QWEN_FAST_MODEL,
        capabilities = standardCapabilities + LLMCapability.Thinking,
        contextLength = 262_144,
        maxOutputTokens = 32_768
    )

    private fun deepSeekV4Flash(): LLModel = LLModel(
        provider = LLMProvider.Alibaba,
        id = ModelSelectionConfig.QWEN_CLOUD_DEEPSEEK_FLASH_MODEL,
        capabilities = standardCapabilities + LLMCapability.Thinking,
        contextLength = 1_050_000,
        maxOutputTokens = 16_000
    )
}

object CodexChatGptModelResolver {
    private val textCapabilities: List<LLMCapability> = listOf(
        LLMCapability.Temperature,
        LLMCapability.Completion,
        LLMCapability.Schema.JSON.Standard
    )

    fun resolve(modelId: String): LLModel = when (modelId) {
        ModelSelectionConfig.CODEX_CHATGPT_GPT56_LUNA_MODEL -> gpt56Luna()
        else -> error("Unsupported Codex/ChatGPT model id: $modelId")
    }

    private fun gpt56Luna(): LLModel = LLModel(
        provider = LLMProvider.OpenAI,
        id = ModelSelectionConfig.CODEX_CHATGPT_GPT56_LUNA_MODEL,
        capabilities = textCapabilities,
        contextLength = 400_000,
        maxOutputTokens = 128_000
    )
}
