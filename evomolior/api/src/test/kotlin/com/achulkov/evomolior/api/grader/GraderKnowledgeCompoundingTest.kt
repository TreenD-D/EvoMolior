package com.achulkov.evomolior.api.grader

import com.achulkov.evomolior.competition.grader.GraderGroupManager
import com.achulkov.evomolior.core.model.Role
import com.achulkov.evomolior.core.model.ScoringDimension
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.db.entity.KnowledgeBaseEntryEntity
import com.achulkov.evomolior.substrate.db.entity.KnowledgeGraphFactEntity
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertTrue

class GraderKnowledgeCompoundingTest {

    @Test
    fun `generation two criteria and judging consume generation one grader KB and KG`() = runTest {
        val dataRoot = createTempDirectory("grader-knowledge-compounding-").toFile()
        val substrateFactory = SubstrateDatabaseFactory(dataRoot)
        val runId = "run-grader-knowledge-compounding"
        val graderGroupId = "grader-0"
        val targetGroupId = "group-0"
        val graderDb = substrateFactory.getGrader(runId, graderGroupId)
        val criteriaPrompts = mutableListOf<String>()
        val judgePrompts = mutableListOf<String>()
        val dimensions = listOf(
            ScoringDimension(
                id = "accuracy",
                name = "Accuracy",
                description = "Reward evidence-backed conclusions.",
                weight = 1.0
            )
        )

        graderDb.knowledgeBaseDao().insert(
            KnowledgeBaseEntryEntity(
                id = "grader-kb-generation-1",
                content = "GENERATION_ONE_KB_MARKER: require source-specific evidence in every rubric.",
                sourceAgentId = "grader-researcher-generation-1",
                generation = 1,
                trustScore = 0.95,
                citationCount = 3,
                domain = "evaluation",
                status = "active",
                groupId = graderGroupId,
                runId = runId,
                createdAtMs = 1_000L
            )
        )
        graderDb.knowledgeGraphFactDao().insert(
            KnowledgeGraphFactEntity(
                id = "grader-kg-generation-1",
                subject = "GENERATION_ONE_KG_MARKER",
                relation = "REQUIRES",
                objectNode = "claim-level provenance checks",
                confidence = 0.94,
                sourceAgentId = "grader-builder-generation-1",
                generation = 1,
                trustScore = 0.93,
                provenanceJson = """["grader-kb-generation-1"]""",
                status = "active",
                speculative = false,
                groupId = graderGroupId,
                runId = runId,
                createdAtMs = 1_100L
            )
        )

        val runner = GraderRunner(
            openRouterApiKey = "sk-test",
            substrateFactory = substrateFactory,
            localAgentRunner = { context ->
                if (context.role == Role.GraderCritic) {
                    judgePrompts += context.systemPrompt
                    """{"scores":[{"targetGroupId":"$targetGroupId","dimensionId":"accuracy","rawScore":88,"rationale":"Applied compounded grader knowledge."}]}"""
                } else {
                    criteriaPrompts += context.systemPrompt
                    "Generation-two criteria explicitly apply the prior validated grader substrate."
                }
            },
            graderAgentTimeoutMs = 1_000L
        )

        runner.runCriteriaPhase(
            runId = runId,
            graderGroupIds = listOf(graderGroupId),
            roundIndex = 1,
            runObjective = "Evaluate a grounded report.",
            scoringDimensions = dimensions
        )
        runner.runJudgingPhase(
            runId = runId,
            graderGroupIds = listOf(graderGroupId),
            competingGroupIds = listOf(targetGroupId),
            roundIndex = 1,
            runObjective = "Evaluate a grounded report.",
            scoringDimensions = dimensions,
            graderGroupManager = GraderGroupManager(dimensions)
        )

        assertTrue(criteriaPrompts.isNotEmpty())
        assertTrue(criteriaPrompts.all { it.contains("GENERATION_ONE_KB_MARKER") })
        assertTrue(criteriaPrompts.all { it.contains("GENERATION_ONE_KG_MARKER") })
        assertTrue(judgePrompts.isNotEmpty())
        assertTrue(judgePrompts.all { it.contains("GENERATION_ONE_KB_MARKER") })
        assertTrue(judgePrompts.all { it.contains("GENERATION_ONE_KG_MARKER") })
    }
}
