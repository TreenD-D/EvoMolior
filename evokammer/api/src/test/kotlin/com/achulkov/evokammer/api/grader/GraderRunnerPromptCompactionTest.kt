package com.achulkov.evokammer.api.grader

import com.achulkov.evokammer.competition.grader.GraderGroupManager
import com.achulkov.evokammer.core.model.Role
import com.achulkov.evokammer.core.model.ScoringDimension
import com.achulkov.evokammer.substrate.SubstrateDatabaseFactory
import com.achulkov.evokammer.substrate.db.entity.StagingSubmissionEntity
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GraderRunnerPromptCompactionTest {

    @Test
    fun `judging phase keeps only latest criteria entries and compact snapshots`() = runTest {
        val dataRoot = createTempDirectory("grader-prompt-compaction-test").toFile()
        val factory = SubstrateDatabaseFactory(dataRoot)
        val runId = "run-1"
        val graderGroupId = "grader-0"
        val competingGroupId = "group-0"
        val graderDb = factory.getGrader(runId, graderGroupId)
        val competingDb = factory.get(runId, competingGroupId)

        graderDb.stagingDao().insert(
            StagingSubmissionEntity(
                id = "researcher-old",
                agentId = "criteria-agent-1",
                role = Role.GraderResearcher.name,
                type = "PLAN_UPDATE",
                content = longContent("OLD_RESEARCHER_TAIL"),
                budgetSignature = "budget:grader-criteria",
                status = "promoted",
                generation = 1,
                groupId = graderGroupId,
                runId = runId,
                submittedAtMs = 1_000L,
                reviewedAtMs = 1_100L
            )
        )
        graderDb.stagingDao().insert(
            StagingSubmissionEntity(
                id = "researcher-new",
                agentId = "criteria-agent-2",
                role = Role.GraderResearcher.name,
                type = "PLAN_UPDATE",
                content = "NEW_RESEARCHER_TAIL",
                budgetSignature = "budget:grader-criteria",
                status = "promoted",
                generation = 1,
                groupId = graderGroupId,
                runId = runId,
                submittedAtMs = 2_000L,
                reviewedAtMs = 2_100L
            )
        )
        graderDb.stagingDao().insert(
            StagingSubmissionEntity(
                id = "builder-old",
                agentId = "criteria-agent-3",
                role = Role.GraderBuilder.name,
                type = "PLAN_UPDATE",
                content = longContent("OLD_BUILDER_TAIL"),
                budgetSignature = "budget:grader-criteria",
                status = "promoted",
                generation = 1,
                groupId = graderGroupId,
                runId = runId,
                submittedAtMs = 1_500L,
                reviewedAtMs = 1_600L
            )
        )
        graderDb.stagingDao().insert(
            StagingSubmissionEntity(
                id = "builder-new",
                agentId = "criteria-agent-4",
                role = Role.GraderBuilder.name,
                type = "PLAN_UPDATE",
                content = "NEW_BUILDER_TAIL",
                budgetSignature = "budget:grader-criteria",
                status = "promoted",
                generation = 1,
                groupId = graderGroupId,
                runId = runId,
                submittedAtMs = 2_500L,
                reviewedAtMs = 2_600L
            )
        )

        competingDb.stagingDao().insert(
            StagingSubmissionEntity(
                id = "group-doc-1",
                agentId = "agent-1",
                role = Role.Researcher.name,
                type = "DOC",
                content = longContent("SNAPSHOT_TAIL"),
                budgetSignature = "budget:group-0",
                status = "promoted",
                generation = 1,
                groupId = competingGroupId,
                runId = runId,
                submittedAtMs = 3_000L,
                reviewedAtMs = 3_100L
            )
        )

        val scoringDimensions = listOf(
            ScoringDimension(
                id = "accuracy",
                name = "Accuracy",
                description = "Reward evidence-based evaluation.",
                weight = 1.0
            )
        )

        var capturedPromptText = ""
        val runner = GraderRunner(
            openRouterApiKey = "sk-test",
            substrateFactory = factory,
            localAgentRunner = { context ->
                capturedPromptText = context.systemPrompt
                ""
            },
            graderAgentTimeoutMs = 1_000L
        )

        runner.runJudgingPhase(
            runId = runId,
            graderGroupIds = listOf(graderGroupId),
            competingGroupIds = listOf(competingGroupId),
            roundIndex = 0,
            runObjective = "evaluate the run objectively",
            scoringDimensions = scoringDimensions,
            graderGroupManager = GraderGroupManager(scoringDimensions)
        )

        assertContains(capturedPromptText, "COMPACT CRITERIA DIGEST")
        assertContains(capturedPromptText, "NEW_RESEARCHER_TAIL")
        assertContains(capturedPromptText, "NEW_BUILDER_TAIL")
        assertFalse(capturedPromptText.contains("OLD_RESEARCHER_TAIL"))
        assertFalse(capturedPromptText.contains("OLD_BUILDER_TAIL"))
        assertFalse(capturedPromptText.contains("SNAPSHOT_TAIL"))
        assertTrue(capturedPromptText.contains("[truncated]"))
    }

    private fun longContent(marker: String): String = buildString {
        append("prefix-")
        repeat(900) { append('x') }
        append('-')
        append(marker)
    }
}
