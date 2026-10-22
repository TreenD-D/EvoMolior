package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.api.model.RunConfig
import com.achulkov.evomolior.api.model.FinalOutputSpec
import com.achulkov.evomolior.api.model.FinalOutputValidationContract
import com.achulkov.evomolior.core.config.LlmBackend
import com.achulkov.evomolior.core.config.CodexRuntimePreset
import com.achulkov.evomolior.core.config.AcceptanceProfile
import com.achulkov.evomolior.core.config.ModelSelectionConfig
import com.achulkov.evomolior.core.council.InclusiveIntRange
import com.achulkov.evomolior.core.model.GroupProfileType
import com.achulkov.evomolior.core.model.ScoringDimension
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CouncilExecutionEnvelopeFactoryTest {
    @Test
    fun `exact report controls remain part of sealed Council output authority`() {
        val envelope = CouncilExecutionEnvelopeFactory.create(config(LlmBackend.CODEX_CHATGPT).copy(
            finalOutputSpec = FinalOutputSpec(
                "review.md", "text/markdown",
                validation = FinalOutputValidationContract(
                    maximumWordCount = 900,
                    exactMarkdownHeadings = listOf("Summary", "Evidence"),
                    requiredTextStatements = listOf("These tests were not executed"),
                ),
            ),
        ))
        assertEquals(900, envelope.outputSpec.maximumWordCount)
        assertEquals(listOf("Summary", "Evidence"), envelope.outputSpec.exactMarkdownHeadings)
        assertEquals(listOf("These tests were not executed"), envelope.outputSpec.requiredTextStatements)
    }

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

    @Test
    fun `Codex presets freeze only their explicit models without changing budgets`() {
        val base = config(backend = LlmBackend.CODEX_CHATGPT)
        val legacy = CouncilExecutionEnvelopeFactory.create(base)
        for (preset in CodexRuntimePreset.entries) {
            val envelope = CouncilExecutionEnvelopeFactory.create(base.copy(codexRuntimePreset = preset))
            assertEquals(preset, envelope.codexRuntimePreset)
            assertEquals(preset.supportedModelIds, envelope.availableModels.map { it.modelId }.toSet())
            assertEquals(preset.id, envelope.operatorOverrides["codexRuntimePreset"])
            assertEquals(legacy.maxAgentsPerGroupPerRound, envelope.maxAgentsPerGroupPerRound)
            assertEquals(legacy.maxRounds, envelope.maxRounds)
            assertEquals(legacy.cumulativeCostCap, envelope.cumulativeCostCap)
            assertEquals(legacy.enforceProviderMonetaryCostCap, envelope.enforceProviderMonetaryCostCap)
        }
    }

    @Test
    fun `full Luna cannot silently admit another model or another backend`() {
        assertFailsWith<IllegalArgumentException> {
            config(backend = LlmBackend.CODEX_CHATGPT).copy(
                codexRuntimePreset = CodexRuntimePreset.FULL_LUNA,
                modelOverrides = mapOf("Outlier" to CodexRuntimePreset.ASTRA),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            config(backend = LlmBackend.OPENROUTER).copy(
                codexRuntimePreset = CodexRuntimePreset.MIXED_FAMILIES,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            config(backend = LlmBackend.CODEX_CHATGPT).copy(
                codexRuntimePreset = CodexRuntimePreset.MIXED_FAMILIES,
                acceptanceProfile = AcceptanceProfile.APPENDIX_B_FULL,
            )
        }
    }

    @Test
    fun `normal production runs may choose one to three grader groups`() {
        assertEquals(
            InclusiveIntRange(3, 3),
            CouncilExecutionEnvelopeFactory.create(config(LlmBackend.OPENROUTER)).requiredGraderGroupCount
        )
        listOf(1, 2, 3).forEach { count ->
            val envelope = CouncilExecutionEnvelopeFactory.create(
                config(LlmBackend.OPENROUTER).copy(graderGroupCountOverride = count)
            )
            assertEquals(InclusiveIntRange(count, count), envelope.requiredGraderGroupCount)
        }
        assertFailsWith<IllegalArgumentException> {
            config(LlmBackend.OPENROUTER).copy(graderGroupCountOverride = 4)
        }
        assertFailsWith<IllegalArgumentException> {
            config(LlmBackend.OPENROUTER).copy(graderGroupCountOverride = 0)
        }
    }

    @Test
    fun `ordinary agent ttl is an explicit operator override and absent by default`() {
        val default = CouncilExecutionEnvelopeFactory.create(config(LlmBackend.OPENROUTER))
        assertTrue(ORDINARY_AGENT_TTL_OVERRIDE_KEY !in default.operatorOverrides)

        val configured = CouncilExecutionEnvelopeFactory.create(
            config(LlmBackend.OPENROUTER).copy(ordinaryAgentTtlSeconds = 900)
        )
        assertEquals("900", configured.operatorOverrides[ORDINARY_AGENT_TTL_OVERRIDE_KEY])
        // The TTL is a time bound only; credit policy and caps are unchanged.
        assertEquals(default.perGenerationCreditPolicy, configured.perGenerationCreditPolicy)
        assertEquals(default.cumulativeCostCap, configured.cumulativeCostCap)

        assertFailsWith<IllegalArgumentException> {
            config(LlmBackend.OPENROUTER).copy(ordinaryAgentTtlSeconds = 30)
        }
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
