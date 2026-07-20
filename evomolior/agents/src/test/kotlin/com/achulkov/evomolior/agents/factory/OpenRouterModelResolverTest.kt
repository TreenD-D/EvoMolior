package com.achulkov.evomolior.agents.factory

import com.achulkov.evomolior.core.config.ModelSelectionConfig
import com.achulkov.evomolior.core.model.Role
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OpenRouterModelResolverTest {
    @Test
    fun `resolves supported OpenRouter models without remapping`() {
        assertModel(
            expectedId = "deepseek/deepseek-v4-flash",
            model = OpenRouterModelResolver.resolve(ModelSelectionConfig.DEFAULT_RUNTIME_RESEARCHER_MODEL),
            expectedContextLength = 1_050_000L,
            expectedMaxOutputTokens = 16_000L,
            expectedCapabilities = setOf(
                LLMCapability.Schema.JSON.Standard,
                LLMCapability.ToolChoice,
                LLMCapability.Thinking
            )
        )
        assertModel(
            expectedId = "deepseek/deepseek-v4-flash",
            model = OpenRouterModelResolver.resolve(ModelSelectionConfig.DEFAULT_RUNTIME_BUILDER_MODEL),
            expectedContextLength = 1_050_000L,
            expectedMaxOutputTokens = 16_000L,
            expectedCapabilities = setOf(
                LLMCapability.Schema.JSON.Standard,
                LLMCapability.ToolChoice,
                LLMCapability.Thinking
            )
        )
        assertModel(
            expectedId = "qwen/qwen3-next-80b-a3b-instruct",
            model = OpenRouterModelResolver.resolve(ModelSelectionConfig.DEFAULT_RUNTIME_PLANNER_MODEL),
            expectedContextLength = 262_144L,
            expectedCapabilities = setOf(LLMCapability.ToolChoice, LLMCapability.Thinking)
        )
        assertModel(
            expectedId = "deepseek/deepseek-v4-flash",
            model = OpenRouterModelResolver.resolve(ModelSelectionConfig.DEFAULT_RUNTIME_CRITIC_MODEL),
            expectedContextLength = 1_050_000L,
            expectedMaxOutputTokens = 16_000L,
            expectedCapabilities = setOf(
                LLMCapability.Schema.JSON.Standard,
                LLMCapability.ToolChoice,
                LLMCapability.Thinking
            )
        )
        assertModel(
            expectedId = "perplexity/sonar",
            model = OpenRouterModelResolver.resolve(ModelSelectionConfig.DEFAULT_RUNTIME_ARCHIVIST_MODEL),
            expectedContextLength = 127_000L,
            expectedCapabilities = setOf(LLMCapability.Tools, LLMCapability.Completion, LLMCapability.Temperature, LLMCapability.Speculation)
        )
        assertModel(
            expectedId = "deepseek/deepseek-v4-flash",
            model = OpenRouterModelResolver.resolve(ModelSelectionConfig.DEFAULT_RUNTIME_OUTLIER_MODEL),
            expectedContextLength = 1_050_000L,
            expectedMaxOutputTokens = 16_000L,
            expectedCapabilities = setOf(
                LLMCapability.Schema.JSON.Standard,
                LLMCapability.ToolChoice,
                LLMCapability.Thinking
            )
        )
        assertModel(
            expectedId = "openai/gpt-5-mini",
            model = OpenRouterModelResolver.resolve(ModelSelectionConfig.DEFAULT_RUNTIME_METAPLANNER_MODEL),
            expectedContextLength = 400_000L,
            expectedMaxOutputTokens = 128_000L,
            expectedCapabilities = setOf(
                LLMCapability.Vision.Image,
                LLMCapability.ToolChoice,
                LLMCapability.Thinking
            )
        )
        assertModel(
            expectedId = "openai/gpt-oss-20b",
            model = OpenRouterModelResolver.resolve(ModelSelectionConfig.DEFAULT_RUNTIME_FALLBACK_MODEL),
            expectedContextLength = 131_072L,
            expectedCapabilities = setOf(LLMCapability.ToolChoice, LLMCapability.Thinking)
        )
        assertModel(
            expectedId = "deepseek/deepseek-v3.2",
            model = OpenRouterModelResolver.resolve("deepseek/deepseek-v3.2"),
            expectedContextLength = 131_072L,
            expectedMaxOutputTokens = 65_536L,
            expectedCapabilities = setOf(
                LLMCapability.Schema.JSON.Standard,
                LLMCapability.ToolChoice,
                LLMCapability.Thinking
            )
        )
        assertModel(
            expectedId = "deepseek/deepseek-v4-flash",
            model = OpenRouterModelResolver.resolve("deepseek/deepseek-v4-flash"),
            expectedContextLength = 1_050_000L,
            expectedMaxOutputTokens = 16_000L,
            expectedCapabilities = setOf(
                LLMCapability.Schema.JSON.Standard,
                LLMCapability.ToolChoice,
                LLMCapability.Thinking
            )
        )
        assertModel(
            expectedId = "deepseek/deepseek-v4-pro",
            model = OpenRouterModelResolver.resolve("deepseek/deepseek-v4-pro"),
            expectedContextLength = 1_050_000L,
            expectedMaxOutputTokens = 384_000L,
            expectedCapabilities = setOf(
                LLMCapability.Schema.JSON.Standard,
                LLMCapability.ToolChoice,
                LLMCapability.Thinking
            )
        )
        assertModel(
            expectedId = "x-ai/grok-4.3",
            model = OpenRouterModelResolver.resolve(ModelSelectionConfig.DEFAULT_COUNCIL_MODEL),
            expectedContextLength = 1_000_000L,
            expectedMaxOutputTokens = null,
            expectedCapabilities = setOf(
                LLMCapability.Vision.Image,
                LLMCapability.ToolChoice,
                LLMCapability.Thinking
            )
        )
    }

    @Test
    fun `default runtime assignments stay within the configured role pools`() {
        val pools = ModelSelectionConfig.defaultRuntimeModelPools()

        ModelSelectionConfig.defaultRuntimeAssignments(Random(1)).forEach { (roleName, modelId) ->
            val pool = pools[roleName] ?: error("Missing pool for role $roleName")
            assertTrue(modelId in pool, "Sampled model $modelId must come from the configured pool for $roleName")
            assertTrue(
                !ModelSelectionConfig.isExplicitOptInModel(modelId),
                "Sampled model $modelId for $roleName must not require explicit opt-in"
            )
            assertEquals(modelId, OpenRouterModelResolver.resolve(modelId).id)
        }
    }

    @Test
    fun `unsupported models fail explicitly`() {
        val error = assertFailsWith<IllegalStateException> {
            OpenRouterModelResolver.resolve("custom/test-model")
        }

        assertTrue(
            error.message?.contains("Unsupported OpenRouter model id") == true,
            "Error should mention that unsupported model IDs fail explicitly"
        )
    }

    @Test
    fun `deprecated runtime models are rejected explicitly`() {
        assertFailsWith<IllegalStateException> {
            OpenRouterModelResolver.resolve("x-ai/grok-4-fast")
        }
        assertFailsWith<IllegalStateException> {
            OpenRouterModelResolver.resolve("anthropic/claude-sonnet-4.6")
        }
    }

    private fun assertModel(
        expectedId: String,
        model: ai.koog.prompt.llm.LLModel,
        expectedContextLength: Long? = null,
        expectedMaxOutputTokens: Long? = null,
        expectedCapabilities: Set<LLMCapability>
    ) {
        assertEquals(LLMProvider.OpenRouter, model.provider)
        assertEquals(expectedId, model.id)
        if (expectedContextLength != null) {
            assertEquals(expectedContextLength, model.contextLength)
        }
        assertEquals(expectedMaxOutputTokens, model.maxOutputTokens)
        expectedCapabilities.forEach { capability ->
            assertTrue(model.supports(capability), "Expected $expectedId to support $capability")
        }
    }

}
