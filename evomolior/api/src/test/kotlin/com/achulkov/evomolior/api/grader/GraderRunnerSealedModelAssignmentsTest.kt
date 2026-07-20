package com.achulkov.evomolior.api.grader

import com.achulkov.evomolior.agents.factory.GraderAgentContext
import com.achulkov.evomolior.agents.factory.LlmBackendToolSupportPolicy
import com.achulkov.evomolior.competition.grader.GraderGroupManager
import com.achulkov.evomolior.core.atlas.AtlasAgentId
import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.atlas.NoveltyType
import com.achulkov.evomolior.core.atlas.NoveltyVectorProposal
import com.achulkov.evomolior.core.config.LlmBackend
import com.achulkov.evomolior.core.config.ModelSelectionConfig
import com.achulkov.evomolior.core.model.Role
import com.achulkov.evomolior.core.model.ScoringDimension
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GraderRunnerSealedModelAssignmentsTest {

    @Test
    fun `sealed authority resolves exact role models and ignores runtime override`() {
        val runner = runner(
            graderModelOverride = "runtime-override",
            sealedAssignments = sealedAssignments()
        )

        assertEquals("sealed-researcher", runner.modelOverrideFor(Role.GraderResearcher))
        assertEquals("sealed-builder", runner.modelOverrideFor(Role.GraderBuilder))
        assertEquals("sealed-critic", runner.modelOverrideFor(Role.GraderCritic))
    }

    @Test
    fun `missing sealed grader role fails closed at construction`() {
        assertFailsWith<IllegalArgumentException> {
            runner(
                sealedAssignments = mapOf(
                    Role.GraderResearcher.name to "sealed-researcher",
                    Role.GraderBuilder.name to "sealed-builder"
                )
            )
        }
    }

    @Test
    fun `legacy callers retain runtime override when sealed authority is absent`() {
        assertEquals(
            "runtime-override",
            runner(graderModelOverride = "runtime-override").modelOverrideFor(Role.GraderCritic)
        )
    }

    @Test
    fun `criteria judging and novelty contexts use their sealed role assignments`() = runTest {
        val previousToolSupport = System.getProperty(
            LlmBackendToolSupportPolicy.QWEN_TOOL_SUPPORT_PROPERTY
        )
        System.setProperty(LlmBackendToolSupportPolicy.QWEN_TOOL_SUPPORT_PROPERTY, "false")
        val contexts = mutableListOf<GraderAgentContext>()
        val runner = runner(
            graderModelOverride = ModelSelectionConfig.QWEN_CLOUD_QWEN_PLUS_MODEL,
            sealedAssignments = mapOf(
                Role.GraderResearcher.name to ModelSelectionConfig.QWEN_CLOUD_QWEN_PLUS_MODEL,
                Role.GraderBuilder.name to ModelSelectionConfig.QWEN_CLOUD_QWEN_FAST_MODEL,
                Role.GraderCritic.name to ModelSelectionConfig.QWEN_CLOUD_DEEPSEEK_FLASH_MODEL
            ),
            localAgentRunner = { context ->
                contexts += context
                when (context.role) {
                    Role.GraderCritic -> if (context.targetGroupId == "redacted") {
                        """{"status":"VALID","novelty":0.9,"receiverRelevance":0.8,"testability":0.8,"plausibility":0.8,"expectedImpact":0.8,"costDiscipline":0.8,"rationale":"Exact."}"""
                    } else {
                        """{"scores":[{"targetGroupId":"group-0","dimensionId":"quality","rawScore":80.0,"rationale":"Exact."}]}"""
                    }
                    else -> "Criteria."
                }
            }
        )
        val dimension = ScoringDimension("quality", "Quality", "Evidence quality.", 1.0)

        try {
            runner.runCriteriaPhase(
                runId = "sealed-models",
                graderGroupIds = listOf("grader-0"),
                roundIndex = 0,
                runObjective = "Assess a bounded artifact.",
                scoringDimensions = listOf(dimension)
            )
            runner.runJudgingPhase(
                runId = "sealed-models",
                graderGroupIds = listOf("grader-0"),
                competingGroupIds = listOf("group-0"),
                roundIndex = 0,
                runObjective = "Assess a bounded artifact.",
                scoringDimensions = listOf(dimension),
                graderGroupManager = GraderGroupManager(listOf(dimension))
            )
            runner.runNoveltyAssessmentPhase(
                runId = "sealed-models",
                graderGroupIds = setOf("grader-0"),
                roundIndex = 0,
                runObjective = "Assess a bounded artifact.",
                proposals = listOf(proposal())
            )

            val criteriaContexts = contexts.filter {
                it.targetGroupId == "criteria-phase"
            }.associateBy(GraderAgentContext::role)
            assertEquals(
                ModelSelectionConfig.QWEN_CLOUD_QWEN_PLUS_MODEL,
                criteriaContexts.getValue(Role.GraderResearcher).agent.agentConfig.model.id
            )
            assertEquals(
                ModelSelectionConfig.QWEN_CLOUD_QWEN_FAST_MODEL,
                criteriaContexts.getValue(Role.GraderBuilder).agent.agentConfig.model.id
            )
            assertEquals(
                ModelSelectionConfig.QWEN_CLOUD_DEEPSEEK_FLASH_MODEL,
                contexts.single { it.targetGroupId == "group-0" }.agent.agentConfig.model.id
            )
            assertEquals(
                ModelSelectionConfig.QWEN_CLOUD_DEEPSEEK_FLASH_MODEL,
                contexts.single { it.targetGroupId == "redacted" }.agent.agentConfig.model.id
            )
        } finally {
            if (previousToolSupport == null) {
                System.clearProperty(LlmBackendToolSupportPolicy.QWEN_TOOL_SUPPORT_PROPERTY)
            } else {
                System.setProperty(
                    LlmBackendToolSupportPolicy.QWEN_TOOL_SUPPORT_PROPERTY,
                    previousToolSupport
                )
            }
        }
    }

    private fun runner(
        graderModelOverride: String? = null,
        sealedAssignments: Map<String, String>? = null,
        localAgentRunner: suspend (GraderAgentContext) -> String = { "" }
    ) = GraderRunner(
        openRouterApiKey = "test",
        llmBackend = LlmBackend.QWEN_CLOUD,
        substrateFactory = SubstrateDatabaseFactory(
            createTempDirectory("sealed-grader-models-").toFile()
        ),
        localAgentRunner = localAgentRunner,
        graderAgentTimeoutMs = 1_000L,
        graderModelOverride = graderModelOverride,
        sealedGraderModelAssignments = sealedAssignments
    )

    private fun sealedAssignments() = mapOf(
        Role.GraderResearcher.name to "sealed-researcher",
        Role.GraderBuilder.name to "sealed-builder",
        Role.GraderCritic.name to "sealed-critic"
    )

    private fun proposal() = NoveltyVectorProposal(
        proposalId = "proposal-1",
        runId = "sealed-models",
        atlasHash = "atlas-hash",
        groupId = AtlasGroupId("worker-secret"),
        round = 0,
        proposerAgentId = AtlasAgentId("outlier-secret"),
        noveltyType = NoveltyType.MISSING_INTEGRATION_DEPENDENCY,
        affectedVisibleNodeIds = setOf(NodeId("visible-node")),
        missingLayerOrAssumption = "Repairability is absent.",
        proposedVector = "Test repair cost.",
        whyCurrentMapMayMissIt = "The map tests only construction.",
        falsifiablePrediction = "Modularity lowers repair cost.",
        minimalTestOrArtifact = "Run a blind comparison.",
        expectedReceiverImpact = "Improves reliability.",
        expectedCost = 3.0,
        noveltyFingerprint = "repairability"
    ).withComputedHash()
}
