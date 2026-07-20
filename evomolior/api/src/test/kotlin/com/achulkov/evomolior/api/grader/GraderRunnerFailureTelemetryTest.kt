package com.achulkov.evomolior.api.grader

import com.achulkov.evomolior.competition.grader.GraderGroupManager
import com.achulkov.evomolior.competition.grader.CanonSnapshotExporter
import com.achulkov.evomolior.core.model.ScoringDimension
import com.achulkov.evomolior.core.model.SubmissionType
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies that anonymized failure telemetry is both stored in and read back into the grader path.
 */
class GraderRunnerFailureTelemetryTest {

    @Test
    fun `failure telemetry is anonymized and reused in judging phase prompt context`() = runTest {
        val dataRoot = createTempDirectory("grader-telemetry-readback-test").toFile()
        val factory = SubstrateDatabaseFactory(dataRoot)
        val runId = "run-telemetry"
        val graderGroupId = "grader-0"
        val competingGroupId = "group-0"
        val graderDb = factory.getGrader(runId, graderGroupId)
        val competingDb = factory.get(runId, competingGroupId)

        graderDb.stagingDao().insert(
            StagingSubmissionEntity(
                id = "failure-telemetry-1",
                agentId = "system",
                role = "ScoringEngine",
                type = SubmissionType.FAILURE_TELEMETRY.name,
                content = """
                    Failure telemetry
                    Round: 2
                    Failure modes: missing_tests
                    Automated tests failed: 3
                    Raw score: 0.250
                    Weighted score: 0.100
                    Grading sheets: sheet-a
                """.trimIndent(),
                budgetSignature = "system:failure-telemetry",
                status = "promoted",
                generation = 2,
                groupId = graderGroupId,
                runId = runId,
                submittedAtMs = 2_000L,
                reviewedAtMs = 2_000L
            )
        )

        competingDb.stagingDao().insert(
            StagingSubmissionEntity(
                id = "group-0-doc-1",
                agentId = "agent-0",
                role = "Researcher",
                type = "DOC",
                content = "CANON-SNAPSHOT-0: group-0 promoted document",
                budgetSignature = "budget:group-0",
                status = "promoted",
                generation = 2,
                groupId = competingGroupId,
                runId = runId,
                submittedAtMs = 3_000L,
                reviewedAtMs = 3_100L
            )
        )

        var capturedPromptText = ""
        val runner = GraderRunner(
            openRouterApiKey = "sk-test",
            substrateFactory = factory,
            localAgentRunner = { context ->
                if (capturedPromptText.isBlank()) capturedPromptText = context.systemPrompt
                ""
            },
            graderAgentTimeoutMs = 1_000L
        )

        val telemetryContext = runner.readFailureTelemetryContext(graderDb, runId, graderGroupId)
        val expectedTelemetryBlock = """
            [Failure telemetry]
            Failure telemetry
            Round: 2
            Failure modes: missing_tests
            Automated tests failed: 3
            Raw score: 0.250
            Weighted score: 0.100
            Grading sheets: sheet-a
        """.trimIndent()
        assertEquals(expectedTelemetryBlock, telemetryContext.orEmpty().trim())
        assertContains(telemetryContext.orEmpty(), "Failure telemetry")
        assertContains(telemetryContext.orEmpty(), "Round: 2")
        assertTrue(
            telemetryContext.orEmpty().contains("missing_tests"),
            "Telemetry payload should remain available for grader prompt reuse"
        )
        assertTrue(
            !telemetryContext.orEmpty().contains("Target group:", ignoreCase = true),
            "Telemetry payload should not leak a competing group identity"
        )

        val expectedCriteriaContext = buildString {
            appendLine("=== RECENT FAILURE TELEMETRY ===")
            appendLine(telemetryContext.orEmpty())
            appendLine()
            append("(no criteria developed — evaluate based on dimension descriptions and your own judgment)")
        }
        val expectedPrompt = runner.buildRoundJudgePrompt(
            runObjective = "evaluate the run objectively",
            scoringDimensions = listOf(
                ScoringDimension(
                    id = "accuracy",
                    name = "Accuracy",
                    description = "Reward evidence-based evaluation.",
                    weight = 1.0
                )
            ),
            criteriaContext = expectedCriteriaContext,
            allGroupSnapshots = mapOf(
                competingGroupId to CanonSnapshotExporter.export(competingDb, runId, competingGroupId, 2)
            ),
            roundIndex = 2
        )

        runner.runJudgingPhase(
            runId = runId,
            graderGroupIds = listOf(graderGroupId),
            competingGroupIds = listOf(competingGroupId),
            roundIndex = 2,
            runObjective = "evaluate the run objectively",
            scoringDimensions = listOf(
                ScoringDimension(
                    id = "accuracy",
                    name = "Accuracy",
                    description = "Reward evidence-based evaluation.",
                    weight = 1.0
                )
            ),
            graderGroupManager = GraderGroupManager(
                listOf(
                    ScoringDimension(
                        id = "accuracy",
                        name = "Accuracy",
                        description = "Reward evidence-based evaluation.",
                        weight = 1.0
                    )
                )
            )
        )

        assertEquals(expectedPrompt, capturedPromptText)
        assertContains(capturedPromptText, expectedTelemetryBlock)
        assertContains(capturedPromptText, "SNAPSHOT-0")
        assertTrue(
            !capturedPromptText.contains("Target group:", ignoreCase = true),
            "RoundJudge prompt should only contain anonymized telemetry"
        )
    }

}
