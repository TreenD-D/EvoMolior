package com.achulkov.evokammer.agents.factory

import ai.koog.prompt.executor.clients.openrouter.OpenRouterModels
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel

/**
 * Shared OpenRouter model resolver for EvoKammer runtime components.
 *
 * Only exact, supported OpenRouter IDs are accepted here. If a caller supplies an unsupported
 * model ID, the resolver fails explicitly instead of silently remapping it to a different model.
 */
object OpenRouterModelResolver {
    private val standardCapabilities: List<LLMCapability> = listOf(
        LLMCapability.Temperature,
        LLMCapability.Speculation,
        LLMCapability.Tools,
        LLMCapability.Completion,
    )

    private val multimodalCapabilities: List<LLMCapability> = standardCapabilities + LLMCapability.Vision.Image

    /**
     * Resolves a model string to a Koog [LLModel].
     *
     * @param modelId Supported OpenRouter model identifier.
     * @return The corresponding Koog model instance.
     * @throws IllegalArgumentException if [modelId] is not part of the supported runtime set.
     */
    fun resolve(modelId: String): LLModel = when (modelId) {
        "microsoft/phi-4-reasoning:free" -> OpenRouterModels.Phi4Reasoning

        "anthropic/claude-3-haiku" -> OpenRouterModels.Claude3Haiku
        "anthropic/claude-3.5-sonnet" -> OpenRouterModels.Claude3_5Sonnet
        "anthropic/claude-3.7-sonnet" -> OpenRouterModels.Claude3_7Sonnet
        "anthropic/claude-sonnet-4" -> OpenRouterModels.Claude4Sonnet
        "anthropic/claude-opus-4.1" -> OpenRouterModels.Claude4_1Opus
        "anthropic/claude-haiku-4.5" -> OpenRouterModels.Claude4_5Haiku
        "anthropic/claude-sonnet-4.5" -> OpenRouterModels.Claude4_5Sonnet
        "anthropic/claude-opus-4.5" -> OpenRouterModels.Claude4_5Opus
        "anthropic/claude-opus-4.6" -> OpenRouterModels.Claude4_6Opus

        "openai/gpt-4" -> OpenRouterModels.GPT4
        "openai/gpt-4o" -> OpenRouterModels.GPT4o
        "openai/gpt-4o-mini" -> OpenRouterModels.GPT4oMini
        "openai/gpt-5" -> OpenRouterModels.GPT5
        "openai/gpt-5-chat" -> OpenRouterModels.GPT5Chat
        "openai/gpt-5-mini" -> gpt5Mini()
        "openai/gpt-5-nano" -> OpenRouterModels.GPT5Nano
        "openai/gpt-5.2" -> OpenRouterModels.GPT5_2
        "openai/gpt-5.2-pro" -> OpenRouterModels.GPT5_2Pro
        "openai/gpt-oss-120b" -> OpenRouterModels.GPT_OSS_120b
        "openai/gpt-oss-20b" -> gptOss20b()
        "openai/gpt-5.4-mini" -> openAiGpt54Mini()
        "openai/gpt-5.4-nano" -> openAiGpt54Nano()

        "google/gemini-2.5-flash-lite" -> OpenRouterModels.Gemini2_5FlashLite
        "google/gemini-2.5-flash" -> OpenRouterModels.Gemini2_5Flash
        "google/gemini-2.5-pro" -> OpenRouterModels.Gemini2_5Pro
        "qwen/qwen3.5-flash-02-23" -> qwen3_5Flash0223()
        "qwen/qwen3-next-80b-a3b-instruct" -> qwen3Next80bA3bInstruct()

        "deepseek/deepseek-chat-v3-0324" -> OpenRouterModels.DeepSeekV30324
        "deepseek/deepseek-v4-flash" -> deepSeekV4Flash()
        "deepseek/deepseek-v3.2" -> deepSeekV32()
        "deepseek/deepseek-v4-pro" -> deepSeekV4Pro()
        "z-ai/glm-4.7-flash" -> zAiGlm47Flash()
        "nvidia/nemotron-3-nano-30b-a3b" -> nemotron3Nano30bA3b()
        "mistralai/mistral-small-creative" -> mistralSmallCreative()
        "minimax/minimax-m2.5" -> miniMaxM25()
        "moonshotai/kimi-k2.5" -> kimiK25()
        "perplexity/sonar" -> sonar()
        "x-ai/grok-4.3" -> grok43()

        else -> error("Unsupported OpenRouter model id: $modelId")
    }

    private fun openAiGpt54Mini(): LLModel = LLModel(
        provider = LLMProvider.OpenRouter,
        id = "openai/gpt-5.4-mini",
        capabilities = multimodalCapabilities + listOf(
            LLMCapability.Schema.JSON.Standard,
            LLMCapability.ToolChoice,
            LLMCapability.Thinking
        ),
        contextLength = 400_000,
        maxOutputTokens = 128_000
    )

    private fun openAiGpt54Nano(): LLModel = LLModel(
        provider = LLMProvider.OpenRouter,
        id = "openai/gpt-5.4-nano",
        capabilities = multimodalCapabilities + listOf(
            LLMCapability.Schema.JSON.Standard,
            LLMCapability.ToolChoice,
            LLMCapability.Thinking
        ),
        contextLength = 400_000,
        maxOutputTokens = 128_000
    )

    private fun gpt5Mini(): LLModel = LLModel(
        provider = LLMProvider.OpenRouter,
        id = "openai/gpt-5-mini",
        capabilities = multimodalCapabilities + listOf(
            LLMCapability.Schema.JSON.Standard,
            LLMCapability.ToolChoice,
            LLMCapability.Thinking
        ),
        contextLength = 400_000,
        maxOutputTokens = 128_000
    )

    private fun gptOss20b(): LLModel = LLModel(
        provider = LLMProvider.OpenRouter,
        id = "openai/gpt-oss-20b",
        capabilities = standardCapabilities + listOf(
            LLMCapability.Schema.JSON.Standard,
            LLMCapability.ToolChoice,
            LLMCapability.Thinking
        ),
        contextLength = 131_072
    )

    private fun qwen3Next80bA3bInstruct(): LLModel = LLModel(
        provider = LLMProvider.OpenRouter,
        id = "qwen/qwen3-next-80b-a3b-instruct",
        capabilities = standardCapabilities + listOf(
            LLMCapability.Schema.JSON.Standard,
            LLMCapability.ToolChoice,
            LLMCapability.Thinking
        ),
        contextLength = 262_144
    )

    private fun qwen3_5Flash0223(): LLModel = LLModel(
        provider = LLMProvider.OpenRouter,
        id = "qwen/qwen3.5-flash-02-23",
        capabilities = standardCapabilities + listOf(
            LLMCapability.Schema.JSON.Standard,
            LLMCapability.ToolChoice,
            LLMCapability.Thinking
        ),
        contextLength = 262_144,
        maxOutputTokens = 32_768
    )

    private fun zAiGlm47Flash(): LLModel = LLModel(
        provider = LLMProvider.OpenRouter,
        id = "z-ai/glm-4.7-flash",
        capabilities = standardCapabilities + listOf(
            LLMCapability.Schema.JSON.Standard,
            LLMCapability.ToolChoice,
            LLMCapability.Thinking
        ),
        contextLength = 262_144,
        maxOutputTokens = 32_768
    )

    private fun nemotron3Nano30bA3b(): LLModel = LLModel(
        provider = LLMProvider.OpenRouter,
        id = "nvidia/nemotron-3-nano-30b-a3b",
        capabilities = standardCapabilities + listOf(
            LLMCapability.Schema.JSON.Standard,
            LLMCapability.ToolChoice,
            LLMCapability.Thinking
        ),
        contextLength = 262_144
    )

    private fun mistralSmallCreative(): LLModel = LLModel(
        provider = LLMProvider.OpenRouter,
        id = "mistralai/mistral-small-creative",
        capabilities = standardCapabilities + listOf(
            LLMCapability.Schema.JSON.Standard,
            LLMCapability.ToolChoice,
            LLMCapability.Thinking
        ),
        contextLength = 32_768
    )

    private fun deepSeekV4Flash(): LLModel = LLModel(
        provider = LLMProvider.OpenRouter,
        id = "deepseek/deepseek-v4-flash",
        capabilities = standardCapabilities + listOf(
            LLMCapability.Schema.JSON.Standard,
            LLMCapability.ToolChoice,
            LLMCapability.Thinking
        ),
        contextLength = 1_050_000,
        maxOutputTokens = 16_000
    )

    private fun deepSeekV32(): LLModel = LLModel(
        provider = LLMProvider.OpenRouter,
        id = "deepseek/deepseek-v3.2",
        capabilities = standardCapabilities + listOf(
            LLMCapability.Schema.JSON.Standard,
            LLMCapability.ToolChoice,
            LLMCapability.Thinking
        ),
        contextLength = 131_072,
        maxOutputTokens = 65_536
    )

    private fun deepSeekV4Pro(): LLModel = LLModel(
        provider = LLMProvider.OpenRouter,
        id = "deepseek/deepseek-v4-pro",
        capabilities = standardCapabilities + listOf(
            LLMCapability.Schema.JSON.Standard,
            LLMCapability.ToolChoice,
            LLMCapability.Thinking
        ),
        contextLength = 1_050_000,
        maxOutputTokens = 384_000
    )

    private fun miniMaxM25(): LLModel = LLModel(
        provider = LLMProvider.OpenRouter,
        id = "minimax/minimax-m2.5",
        capabilities = standardCapabilities + listOf(
            LLMCapability.Schema.JSON.Standard,
            LLMCapability.ToolChoice,
            LLMCapability.Thinking
        ),
        contextLength = 205_000,
        maxOutputTokens = 197_000
    )

    private fun kimiK25(): LLModel = LLModel(
        provider = LLMProvider.OpenRouter,
        id = "moonshotai/kimi-k2.5",
        capabilities = multimodalCapabilities + listOf(
            LLMCapability.Schema.JSON.Standard,
            LLMCapability.ToolChoice,
            LLMCapability.Thinking
        ),
        contextLength = 262_000,
        maxOutputTokens = 262_000
    )

    private fun sonar(): LLModel = LLModel(
        provider = LLMProvider.OpenRouter,
        id = "perplexity/sonar",
        capabilities = standardCapabilities,
        contextLength = 127_000
    )

    private fun grok43(): LLModel = LLModel(
        provider = LLMProvider.OpenRouter,
        id = "x-ai/grok-4.3",
        capabilities = multimodalCapabilities + listOf(
            LLMCapability.Schema.JSON.Standard,
            LLMCapability.ToolChoice,
            LLMCapability.Thinking
        ),
        contextLength = 1_000_000,
        maxOutputTokens = null
    )
}
