package com.achulkov.evomolior.competition

import com.achulkov.evomolior.agents.factory.CouncilRunner
import com.achulkov.evomolior.competition.leaks.DifferentialPrivacyTransform
import com.achulkov.evomolior.core.config.ModelSelectionConfig
import com.achulkov.evomolior.core.atlas.FinalOutputSpec
import com.achulkov.evomolior.core.contract.GroupTopologyKind
import com.achulkov.evomolior.core.council.ExecutionEnvelope
import com.achulkov.evomolior.core.council.GenerationCreditPolicy
import com.achulkov.evomolior.core.council.InclusiveIntRange
import com.achulkov.evomolior.core.council.ModelCapabilityCard
import com.achulkov.evomolior.core.council.SandboxCapabilitySummary
import com.achulkov.evomolior.core.council.SourceAccessPolicySummary
import com.achulkov.evomolior.core.model.GroupProfileType
import kotlin.test.Test
import kotlin.test.assertEquals

class ModelSelectionConfigRuntimeDefaultsTest {
    @Test
    fun `runtime constructors use shared model defaults`() {
        val councilRunner = CouncilRunner(
            openRouterApiKey = "test-api-key",
            executionEnvelope = testEnvelope()
        )
        assertEquals(
            ModelSelectionConfig.DEFAULT_COUNCIL_MODEL,
            councilRunner.readPrivateString("councilModelId")
        )

        val leakTransform = DifferentialPrivacyTransform(openRouterApiKey = "test-api-key")
        assertEquals(
            ModelSelectionConfig.DEFAULT_LEAK_TRANSFORM_MODEL,
            leakTransform.readPrivateString("modelId")
        )

        assertEquals(
            "deepseek/deepseek-v4-flash",
            ModelSelectionConfig.DEFAULT_CRITIC_PIPELINE_MODEL
        )
    }

    private fun Any.readPrivateString(fieldName: String): String {
        val field = javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(this) as String
    }

    private fun testEnvelope() = ExecutionEnvelope(
        requiredWorkerGroupCount = InclusiveIntRange(2, 2),
        requiredGraderGroupCount = InclusiveIntRange(2, 2),
        permittedProfiles = setOf(GroupProfileType.Library),
        availableModels = listOf(
            ModelCapabilityCard(
                modelId = "test/model",
                familyId = "test",
                structuredOutputReliability = 1.0,
                toolCallReliability = 1.0,
                codeBuildReliability = 1.0,
                longContextSynthesisReliability = 1.0
            )
        ),
        maxAgentsPerGroupPerRound = 1,
        maxRounds = 1,
        perGenerationCreditPolicy = GenerationCreditPolicy(1.0, 2.0),
        allowedTopologyKinds = setOf(GroupTopologyKind.RING),
        sourceAccessPolicy = SourceAccessPolicySummary(false),
        sandboxCapabilities = SandboxCapabilitySummary(
            maximumToolRuntimeSeconds = 1,
            maximumWorkspaceBytes = 1
        ),
        outputSpec = FinalOutputSpec("result.md", "text/markdown"),
        atomicNodeEffortBound = 1
    )
}
