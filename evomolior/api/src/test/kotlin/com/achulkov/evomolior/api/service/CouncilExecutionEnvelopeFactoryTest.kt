package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.api.model.RunConfig
import com.achulkov.evomolior.core.config.LlmBackend
import com.achulkov.evomolior.core.config.ModelSelectionConfig
import com.achulkov.evomolior.core.model.GroupProfileType
import com.achulkov.evomolior.core.model.ScoringDimension
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CouncilExecutionEnvelopeFactoryTest {
    @Test
    fun `OpenRouter envelope is deterministic and includes exact operator override`() {
        val config = config(
            backend = LlmBackend.OPENROUTER,
            overrides = mapOf("Researcher" to "operator/custom-model")
        )

        val first = CouncilExecutionEnvelopeFactory.create(config)
        val second = CouncilExecutionEnvelopeFactory.create(config)

        assertEquals(first, second)
        assertEquals(2, first.requiredWorkerGroupCount.minimum)
        assertEquals(2, first.requiredWorkerGroupCount.maximum)
        assertEquals(3, first.requiredGraderGroupCount.minimum)
        assertEquals(
            "operator/custom-model",
            first.operatorOverrides.getValue("model.Researcher")
        )
        assertTrue(first.availableModels.any { it.modelId == "operator/custom-model" })
        assertTrue(first.availableModels.any {
            it.modelId == ModelSelectionConfig.DEFAULT_GRADER_MODEL
        })
        assertTrue(first.availableModels.all { it.providerId == LlmBackend.OPENROUTER.id })
    }

    @Test
    fun `Qwen envelope exposes only resolver-supported models`() {
        val envelope = CouncilExecutionEnvelopeFactory.create(
            config(backend = LlmBackend.QWEN_CLOUD)
        )

        assertEquals(
            setOf(
                ModelSelectionConfig.QWEN_CLOUD_QWEN_PLUS_MODEL,
                ModelSelectionConfig.QWEN_CLOUD_QWEN_FAST_MODEL,
                ModelSelectionConfig.QWEN_CLOUD_DEEPSEEK_FLASH_MODEL
            ),
            envelope.availableModels.map { it.modelId }.toSet()
        )
        assertFailsWith<IllegalArgumentException> {
            CouncilExecutionEnvelopeFactory.create(
                config(
                    backend = LlmBackend.QWEN_CLOUD,
                    overrides = mapOf("Researcher" to "unsupported/model")
                )
            )
        }
    }

    @Test
    fun `Codex envelope uses one executable family and code mode capabilities`() {
        val envelope = CouncilExecutionEnvelopeFactory.create(
            config(backend = LlmBackend.CODEX_CHATGPT)
        )

        val card = envelope.availableModels.single()
        assertEquals(ModelSelectionConfig.CODEX_CHATGPT_GPT56_LUNA_MODEL, card.modelId)
        assertEquals("openai-codex", card.familyId)
        assertEquals(LlmBackend.CODEX_CHATGPT.id, card.providerId)
        assertTrue("code-mode" in card.capabilities)
        assertTrue("native-search" in card.capabilities)
    }

    private fun config(
        backend: LlmBackend,
        overrides: Map<String, String> = emptyMap()
    ) = RunConfig(
        runId = "envelope-run",
        objective = "Produce an evidence-backed result.",
        groupCount = 2,
        groupProfiles = listOf(GroupProfileType.Library, GroupProfileType.Factory),
        rounds = 4,
        maxAgentsPerGroup = 5,
        scoringDimensions = listOf(
            ScoringDimension(
                id = "quality",
                name = "Quality",
                description = "Receiver quality",
                weight = 1.0
            )
        ),
        openRouterApiKey = "test-key",
        llmBackend = backend,
        modelOverrides = overrides
    )
}
