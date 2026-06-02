package com.achulkov.evokammer.core.config

import com.achulkov.evokammer.core.model.Role
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelSelectionConfigTest {
    @Test
    fun `default runtime assignments are sampled from the configured role pools`() {
        val defaults = ModelSelectionConfig.defaultRuntimeAssignments(Random(1))
        val pools = ModelSelectionConfig.defaultRuntimeModelPools()

        assertTrue(defaults.isNotEmpty())
        assertTrue(defaults.values.none(ModelSelectionConfig::isExplicitOptInModel))
        assertEquals(
            listOf(
                "qwen/qwen3-next-80b-a3b-instruct",
                "deepseek/deepseek-v3.2",
                "deepseek/deepseek-v4-flash"
            ),
            pools[Role.Planner.name]
        )
        assertEquals(
            listOf(
                "deepseek/deepseek-v4-flash",
                "deepseek/deepseek-v3.2",
                "openai/gpt-oss-20b"
            ),
            pools[Role.Researcher.name]
        )
        assertEquals(
            listOf(
                "deepseek/deepseek-v4-flash",
                "deepseek/deepseek-v4-flash",
                "openai/gpt-oss-20b"
            ),
            pools[Role.Outlier.name]
        )
        assertTrue(defaults[Role.Researcher.name] in pools[Role.Researcher.name]!!)
        assertTrue(defaults[Role.Builder.name] in pools[Role.Builder.name]!!)
        assertTrue(defaults[Role.Critic.name] in pools[Role.Critic.name]!!)
        assertTrue(defaults[Role.Archivist.name] in pools[Role.Archivist.name]!!)
        assertTrue(defaults[Role.Outlier.name] in pools[Role.Outlier.name]!!)
        assertTrue(defaults[Role.MetaPlanner.name] in pools[Role.MetaPlanner.name]!!)
    }

    @Test
    fun `explicit opt-in models are marked as such`() {
        assertFalse(ModelSelectionConfig.isExplicitOptInModel("anthropic/claude-sonnet-4.6"))
        assertTrue(ModelSelectionConfig.isExplicitOptInModel("openai/gpt-5"))
        assertFalse(ModelSelectionConfig.isExplicitOptInModel("openai/gpt-oss-120b"))
        assertFalse(ModelSelectionConfig.isExplicitOptInModel("openai/gpt-5-mini"))
    }

    @Test
    fun `runtime defaults use the configured council and utility models`() {
        assertEquals("x-ai/grok-4.3", ModelSelectionConfig.DEFAULT_COUNCIL_MODEL)
        assertEquals("deepseek/deepseek-v4-flash", ModelSelectionConfig.DEFAULT_GRADER_MODEL)
        assertEquals("openai/gpt-oss-20b", ModelSelectionConfig.DEFAULT_RUNTIME_FALLBACK_MODEL)
        assertEquals("openai/gpt-oss-20b", ModelSelectionConfig.DEFAULT_LEAK_TRANSFORM_MODEL)
        assertEquals("deepseek/deepseek-v4-flash", ModelSelectionConfig.DEFAULT_RUNTIME_CRITIC_MODEL)
        assertTrue(ModelSelectionConfig.runtimeModelPoolFor(Role.Researcher).contains("deepseek/deepseek-v4-flash"))
        assertTrue(ModelSelectionConfig.runtimeModelPoolFor(Role.Planner).contains("deepseek/deepseek-v3.2"))
        assertTrue(ModelSelectionConfig.runtimeModelPoolFor(Role.MetaPlanner).contains("deepseek/deepseek-v4-flash"))
        assertTrue(ModelSelectionConfig.defaultRuntimeModelPools().values.none { pool ->
            pool.contains("anthropic/claude-sonnet-4.6") ||
                pool.contains("x-ai/grok-4-fast") ||
                pool.contains("nvidia/nemotron-3-nano-30b-a3b") ||
                pool.contains("mistralai/mistral-small-creative")
        })
    }
}
