package com.achulkov.evomolior.api.grader

import com.achulkov.evomolior.core.atlas.AtlasAgentId
import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.atlas.NoveltyAssessmentStatus
import com.achulkov.evomolior.core.atlas.NoveltyType
import com.achulkov.evomolior.core.atlas.NoveltyVectorProposal
import com.achulkov.evomolior.agents.factory.LlmBackendToolSupportPolicy
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.outlier.OutlierNoveltyRepository
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GraderRunnerNoveltyAssessmentTest {
    @Test
    fun `text-only novelty phase persists exactly one assessment per isolated grader`() = runTest {
        val previousToolSupport = System.getProperty(
            LlmBackendToolSupportPolicy.QWEN_TOOL_SUPPORT_PROPERTY
        )
        System.setProperty(LlmBackendToolSupportPolicy.QWEN_TOOL_SUPPORT_PROPERTY, "false")
        val factory = SubstrateDatabaseFactory(
            createTempDirectory("novelty-grader-phase-").toFile()
        )
        val prompts = mutableListOf<String>()
        val runner = GraderRunner(
            openRouterApiKey = "test",
            llmBackend = com.achulkov.evomolior.core.config.LlmBackend.QWEN_CLOUD,
            substrateFactory = factory,
            localAgentRunner = { context ->
                prompts += context.systemPrompt
                """
                {"status":"VALID","novelty":0.9,"receiverRelevance":0.8,"testability":0.85,"plausibility":0.75,"expectedImpact":0.8,"costDiscipline":0.7,"rationale":"Connected and falsifiable."}
                """.trimIndent()
            },
            graderAgentTimeoutMs = 1_000L,
            graderModelOverride =
                com.achulkov.evomolior.core.config.ModelSelectionConfig.QWEN_CLOUD_QWEN_PLUS_MODEL
        )
        val proposal = proposal()

        try {
            val assessments = runner.runNoveltyAssessmentPhase(
                runId = proposal.runId,
                graderGroupIds = setOf("grader-a", "grader-b"),
                roundIndex = proposal.round,
                runObjective = "Build a reliable receiver artifact.",
                proposals = listOf(proposal)
            )

            assertEquals(2, assessments.size)
            assertEquals(
                setOf("grader-a", "grader-b"),
                assessments.map { it.graderGroupId }.toSet()
            )
            for (graderId in setOf("grader-a", "grader-b")) {
                val persisted =
                    OutlierNoveltyRepository(factory.getGrader(proposal.runId, graderId))
                        .assessmentsForProposal(proposal.proposalHash)
                assertEquals(1, persisted.size)
                assertEquals(NoveltyAssessmentStatus.VALID, persisted.single().status)
            }
            assertTrue(prompts.all { it.contains(proposal.proposalHash) })
            assertTrue(prompts.all { it.contains(proposal.proposedVector) })
            assertTrue(prompts.all { !it.contains(proposal.groupId.value) })
            assertTrue(prompts.all { !it.contains(proposal.proposerAgentId.value) })
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

    @Test
    fun `text-only parser rejects malformed or score-bearing abstentions`() {
        val runner = GraderRunner(
            openRouterApiKey = "test",
            substrateFactory = SubstrateDatabaseFactory(
                createTempDirectory("novelty-grader-parser-").toFile()
            ),
            localAgentRunner = { "" }
        )
        assertNull(
            runner.parseTextOnlyNoveltyAssessment(
                """{"status":"ABSTAINED","novelty":0.5,"rationale":"No evidence."}""",
                "proposal",
                "grader"
            )
        )
        val abstained = runner.parseTextOnlyNoveltyAssessment(
            """{"status":"ABSTAINED","rationale":"Insufficient evidence."}""",
            "proposal",
            "grader"
        )
        assertNotNull(abstained)
        assertEquals(NoveltyAssessmentStatus.ABSTAINED, abstained.status)
        assertTrue(abstained.hasValidHash())
        assertNull(
            runner.parseTextOnlyNoveltyAssessment(
                """{"status":"VALID","novelty":0.9,"rationale":"Incomplete."}""",
                "proposal",
                "grader"
            )
        )
    }

    private fun proposal() = NoveltyVectorProposal(
        proposalId = "proposal-1",
        runId = "run-novelty",
        atlasHash = "atlas-hash",
        groupId = AtlasGroupId("worker-secret"),
        round = 2,
        proposerAgentId = AtlasAgentId("outlier-secret"),
        noveltyType = NoveltyType.MISSING_INTEGRATION_DEPENDENCY,
        affectedVisibleNodeIds = setOf(NodeId("visible-node")),
        missingLayerOrAssumption = "Repairability is absent.",
        proposedVector = "Test repair cost under distributed maintenance.",
        whyCurrentMapMayMissIt = "The visible map tests only initial construction.",
        falsifiablePrediction = "Modularity lowers correction cost.",
        minimalTestOrArtifact = "Run a blinded repair comparison.",
        expectedReceiverImpact = "Improves long-term reliability.",
        expectedCost = 3.0,
        noveltyFingerprint = "repairability"
    ).withComputedHash()
}
