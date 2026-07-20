package com.achulkov.evomolior.api.grader

import com.achulkov.evomolior.competition.grader.GraderGroupManager
import com.achulkov.evomolior.core.config.LlmBackend
import com.achulkov.evomolior.core.config.ModelSelectionConfig
import com.achulkov.evomolior.core.contract.GraderAutomatedTestDraft
import com.achulkov.evomolior.core.contract.GraderFailureModeDraft
import com.achulkov.evomolior.core.contract.GraderMethodologyDraft
import com.achulkov.evomolior.core.contract.GraderRubricDraft
import com.achulkov.evomolior.core.model.ScoringDimension
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class GraderAuthoredMethodologyTest {
    @Test
    fun `mandatory grader authored test forces complete authoritative zeros`() = runTest {
        val factory = SubstrateDatabaseFactory(createTempDirectory("grader-methodology-").toFile())
        val runId = "run-1"
        val graderGroupId = "grader-0"
        val targetGroupId = "group-0"
        val dimension = ScoringDimension(
            id = "accuracy",
            name = "Accuracy",
            description = "Evidence-grounded correctness.",
            weight = 1.0
        )
        val draft = GraderMethodologyDraft(
            rubricDimensions = listOf(
                GraderRubricDraft("accuracy", "Require the mandatory evidence marker.")
            ),
            automatedTests = listOf(
                GraderAutomatedTestDraft(
                    testId = "required-evidence",
                    description = "Canon must contain a required evidence marker.",
                    expectedOutputPattern = "REQUIRED_EVIDENCE_MARKER",
                    isMandatory = true
                )
            ),
            failureModes = listOf(
                GraderFailureModeDraft("missing-evidence", "Evidence marker is absent.")
            )
        )
        factory.getGrader(runId, graderGroupId).stagingDao().insert(
            StagingSubmissionEntity(
                id = "authored-sheet-1",
                agentId = "grader-builder-1",
                role = "GraderBuilder",
                type = "PLAN_UPDATE",
                content = Json.encodeToString(GraderMethodologyDraft.serializer(), draft),
                budgetSignature = "GRADING_SHEET_DRAFT:grader-builder-1:1",
                status = "promoted",
                generation = 1,
                groupId = graderGroupId,
                runId = runId,
                submittedAtMs = 1L,
                reviewedAtMs = 2L,
                initialTrustScore = 0.9
            )
        )
        factory.get(runId, targetGroupId).stagingDao().insert(
            StagingSubmissionEntity(
                id = "target-doc-1",
                agentId = "worker-1",
                role = "Researcher",
                type = "DOC",
                content = "A polished result without the required marker.",
                budgetSignature = "worker:test",
                status = "promoted",
                generation = 1,
                groupId = targetGroupId,
                runId = runId,
                submittedAtMs = 1L,
                reviewedAtMs = 2L
            )
        )
        var judgePrompt = ""
        val runner = GraderRunner(
            openRouterApiKey = "sk-test",
            llmBackend = LlmBackend.CODEX_CHATGPT,
            substrateFactory = factory,
            localAgentRunner = { context ->
                judgePrompt = context.systemPrompt
                """{"scores":[{"targetGroupId":"group-0","dimensionId":"accuracy","rawScore":82.0,"rationale":"Strong prose."}]}"""
            },
            graderAgentTimeoutMs = 1_000L,
            graderModelOverride = ModelSelectionConfig.CODEX_CHATGPT_GPT56_LUNA_MODEL
        )
        val manager = GraderGroupManager(listOf(dimension))

        val outputs = runner.runJudgingPhase(
            runId = runId,
            graderGroupIds = listOf(graderGroupId),
            competingGroupIds = listOf(targetGroupId),
            roundIndex = 0,
            runObjective = "Evaluate evidence.",
            scoringDimensions = listOf(dimension),
            graderGroupManager = manager
        )

        assertTrue(
            manager.hasCompleteRoundScores(listOf(targetGroupId), 0),
            "Expected complete scores; judge outputs were $outputs"
        )
        val result = manager.computeRoundResult(listOf(targetGroupId), 0)
        assertEquals(0.0, result.scores.single().dimensionScores.single().rawScore)
        assertTrue(
            result.scores.single().dimensionScores.single().justification
                .contains("Mandatory grader-authored automated test failed")
        )
        assertTrue(judgePrompt.contains("required-evidence: FAIL"))
    }

    @Test
    fun `authored sheet history stays grader local and becomes visible only in its generation`() = runTest {
        val factory = SubstrateDatabaseFactory(createTempDirectory("grader-sheet-history-").toFile())
        val runId = "run-history"
        val graderGroupId = "grader-0"
        val targetGroupId = "group-0"
        val graderDb = factory.getGrader(runId, graderGroupId)
        val targetDb = factory.get(runId, targetGroupId)
        val dimension = ScoringDimension("accuracy", "Accuracy", "Evidence correctness.", 1.0)
        fun draft(marker: String, id: String) = GraderMethodologyDraft(
            rubricDimensions = listOf(GraderRubricDraft("accuracy", "Require $marker.")),
            automatedTests = listOf(
                GraderAutomatedTestDraft(
                    testId = id,
                    description = "Canon must contain $marker.",
                    expectedOutputPattern = marker,
                    isMandatory = true
                )
            ),
            failureModes = emptyList()
        )
        suspend fun insertDraft(generation: Int, marker: String) {
            graderDb.stagingDao().insert(
                StagingSubmissionEntity(
                    id = "sheet-generation-$generation",
                    agentId = "builder-$generation",
                    role = "GraderBuilder",
                    type = "PLAN_UPDATE",
                    content = Json.encodeToString(
                        GraderMethodologyDraft.serializer(),
                        draft(marker, "test-generation-$generation")
                    ),
                    budgetSignature = "GRADING_SHEET_DRAFT:builder-$generation:$generation",
                    status = "promoted",
                    generation = generation,
                    groupId = graderGroupId,
                    runId = runId,
                    submittedAtMs = generation.toLong(),
                    reviewedAtMs = generation.toLong()
                )
            )
        }
        insertDraft(1, "GENERATION_ONE_MARKER")
        insertDraft(2, "GENERATION_TWO_MARKER")
        targetDb.stagingDao().insert(
            StagingSubmissionEntity(
                id = "target-history-doc",
                agentId = "worker-1",
                role = "Researcher",
                type = "DOC",
                content = "The canon contains GENERATION_ONE_MARKER only.",
                budgetSignature = "worker:test",
                status = "promoted",
                generation = 1,
                groupId = targetGroupId,
                runId = runId,
                submittedAtMs = 1L,
                reviewedAtMs = 1L
            )
        )
        val prompts = mutableListOf<String>()
        val runner = GraderRunner(
            openRouterApiKey = "sk-test",
            llmBackend = LlmBackend.CODEX_CHATGPT,
            substrateFactory = factory,
            localAgentRunner = { context ->
                prompts += context.systemPrompt
                """{"scores":[{"targetGroupId":"group-0","dimensionId":"accuracy","rawScore":80.0,"rationale":"Evidence."}]}"""
            },
            graderAgentTimeoutMs = 1_000L,
            graderModelOverride = ModelSelectionConfig.CODEX_CHATGPT_GPT56_LUNA_MODEL
        )

        val roundZeroManager = GraderGroupManager(listOf(dimension))
        runner.runJudgingPhase(
            runId,
            listOf(graderGroupId),
            listOf(targetGroupId),
            0,
            "Evaluate evidence.",
            listOf(dimension),
            roundZeroManager
        )
        val roundZero = roundZeroManager.computeRoundResult(listOf(targetGroupId), 0)
        assertEquals(80.0, roundZero.scores.single().dimensionScores.single().rawScore)
        assertTrue(prompts.last().contains("test-generation-1: PASS"))
        assertFalse(prompts.last().contains("test-generation-2"))

        val roundOneManager = GraderGroupManager(listOf(dimension))
        runner.runJudgingPhase(
            runId,
            listOf(graderGroupId),
            listOf(targetGroupId),
            1,
            "Evaluate evidence.",
            listOf(dimension),
            roundOneManager
        )
        val roundOne = roundOneManager.computeRoundResult(listOf(targetGroupId), 1)
        assertEquals(0.0, roundOne.scores.single().dimensionScores.single().rawScore)
        assertTrue(prompts.last().contains("test-generation-2: FAIL"))

        assertTrue(targetDb.stagingDao().getAll(runId, targetGroupId).none {
            it.budgetSignature.startsWith("GRADING_SHEET_DRAFT:")
        })
        assertEquals(2, graderDb.stagingDao().getAll(runId, graderGroupId).count {
            it.budgetSignature.startsWith("GRADING_SHEET_DRAFT:")
        })
    }
}
