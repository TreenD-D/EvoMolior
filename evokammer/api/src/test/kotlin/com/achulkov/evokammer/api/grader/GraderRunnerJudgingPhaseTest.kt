package com.achulkov.evokammer.api.grader

import com.achulkov.evokammer.competition.grader.GraderGroupManager
import com.achulkov.evokammer.core.model.DimensionScore
import com.achulkov.evokammer.core.model.Role
import com.achulkov.evokammer.core.model.ScoringDimension
import com.achulkov.evokammer.substrate.SubstrateDatabaseFactory
import com.achulkov.evokammer.substrate.db.entity.StagingSubmissionEntity
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.reflect.full.callSuspend

/**
 * Verifies that the judging phase reads grader criteria from the grader substrate and
 * combines them with competing-group canon snapshots before the judge agent is launched.
 */
class GraderRunnerJudgingPhaseTest {

    @Test
    fun `judging phase feeds grader criteria and competing snapshots into the round judge`() = runTest {
        val dataRoot = createTempDirectory("grader-judging-phase-test").toFile()
        val factory = SubstrateDatabaseFactory(dataRoot)
        val runId = "run-1"
        val graderGroupId = "grader-0"
        val competingGroupIds = listOf("group-0", "group-1")

        val graderDb = factory.getGrader(runId, graderGroupId)
        val competingDb0 = factory.get(runId, competingGroupIds[0])
        val competingDb1 = factory.get(runId, competingGroupIds[1])

        graderDb.stagingDao().insert(
            StagingSubmissionEntity(
                id = "grader-criteria-1",
                agentId = "criteria-agent",
                role = Role.GraderResearcher.name,
                type = "PLAN_UPDATE",
                content = "CRITERION-ALPHA: reward reproducible evidence and calibrated scoring.",
                budgetSignature = "budget:grader-criteria",
                status = "promoted",
                generation = 1,
                groupId = graderGroupId,
                runId = runId,
                submittedAtMs = 1_000L,
                reviewedAtMs = 1_500L
            )
        )

        competingDb0.stagingDao().insert(
            StagingSubmissionEntity(
                id = "group-0-snapshot-1",
                agentId = "agent-0",
                role = "Researcher",
                type = "DOC",
                content = "SNAPSHOT-0: group-0 has a promoted document that should appear in canon export.",
                budgetSignature = "budget:group-0",
                status = "promoted",
                generation = 1,
                groupId = competingGroupIds[0],
                runId = runId,
                submittedAtMs = 2_000L,
                reviewedAtMs = 2_100L
            )
        )

        competingDb1.stagingDao().insert(
            StagingSubmissionEntity(
                id = "group-1-snapshot-1",
                agentId = "agent-1",
                role = "Builder",
                type = "DOC",
                content = "SNAPSHOT-1: group-1 has a promoted document that should appear in canon export.",
                budgetSignature = "budget:group-1",
                status = "promoted",
                generation = 1,
                groupId = competingGroupIds[1],
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
        val graderGroupManager = GraderGroupManager(scoringDimensions)

        var capturedPromptText = ""
        var capturedRole: Role? = null
        var capturedTargetGroupId: String? = null
        var capturedTaskPrompt: String? = null
        val runner = GraderRunner(
            openRouterApiKey = "sk-test",
            substrateFactory = factory,
            localAgentRunner = { context ->
                capturedRole = context.role
                capturedTargetGroupId = context.targetGroupId
                capturedTaskPrompt = context.taskPrompt
                graderGroupManager.submitScores(
                    graderGroupId = graderGroupId,
                    targetGroupId = competingGroupIds[0],
                    roundIndex = 0,
                    scores = listOf(
                        DimensionScore(
                            dimensionId = "accuracy",
                            rawScore = 88.0,
                            justification = "Prompt contained the grader criteria and group-0 snapshot."
                        )
                    )
                )
                graderGroupManager.submitScores(
                    graderGroupId = graderGroupId,
                    targetGroupId = competingGroupIds[1],
                    roundIndex = 0,
                    scores = listOf(
                        DimensionScore(
                            dimensionId = "accuracy",
                            rawScore = 72.0,
                            justification = "Prompt contained the grader criteria and group-1 snapshot."
                        )
                    )
                )
                ""
            },
            graderAgentTimeoutMs = 1_000L
        )

        runner.runJudgingPhase(
            runId = runId,
            graderGroupIds = listOf(graderGroupId),
            competingGroupIds = competingGroupIds,
            roundIndex = 0,
            runObjective = "evaluate the run objectively",
            scoringDimensions = scoringDimensions,
            graderGroupManager = graderGroupManager
        )

        assertEquals(Role.GraderCritic, capturedRole)
        assertEquals("all-groups", capturedTargetGroupId)
        assertNotNull(capturedTaskPrompt)
        assertContains(capturedTaskPrompt!!, "submit_grading_score")

        capturedPromptText = runner.buildRoundJudgePrompt(
            runObjective = "evaluate the run objectively",
            scoringDimensions = scoringDimensions,
            criteriaContext = "=== RECENT FAILURE TELEMETRY ===\nignored\n\n[Criteria by GraderResearcher]\nCRITERION-ALPHA: reward reproducible evidence and calibrated scoring.",
            allGroupSnapshots = mapOf(
                competingGroupIds[0] to "=== CANON SNAPSHOT: Group 'group-0' (Round 0) ===\nSNAPSHOT-0",
                competingGroupIds[1] to "=== CANON SNAPSHOT: Group 'group-1' (Round 0) ===\nSNAPSHOT-1"
            ),
            roundIndex = 0
        )

        assertContains(capturedPromptText, "CRITERION-ALPHA")
        assertContains(capturedPromptText, "SNAPSHOT-0")
        assertContains(capturedPromptText, "SNAPSHOT-1")
        assertContains(capturedPromptText, "=== GRADING CRITERIA (developed by criteria agents this round) ===")
        assertContains(capturedPromptText, "=== COMPETING GROUP CANON SNAPSHOTS (Round 0) ===")

        val roundScores = graderGroupManager.group(graderGroupId)
            .computeRoundScores(competingGroupIds, 0)
            .associateBy { it.groupId }

        assertEquals(2, roundScores.size)
        assertEquals(88.0, roundScores.getValue(competingGroupIds[0]).dimensionScores.single().rawScore)
        assertEquals(72.0, roundScores.getValue(competingGroupIds[1]).dimensionScores.single().rawScore)

        val graderRows = graderDb.stagingDao().getAll(runId, graderGroupId)
        val competingRows0 = competingDb0.stagingDao().getAll(runId, competingGroupIds[0])
        val competingRows1 = competingDb1.stagingDao().getAll(runId, competingGroupIds[1])

        assertTrue(graderRows.any { it.content.contains("CRITERION-ALPHA") })
        assertTrue(competingRows0.any { it.content.contains("SNAPSHOT-0") })
        assertTrue(competingRows1.any { it.content.contains("SNAPSHOT-1") })
    }

    @Test
    fun `round judge can execute submit_grading_score from its live tool registry`() = runTest {
        val dataRoot = createTempDirectory("grader-judging-live-tool-test").toFile()
        val factory = SubstrateDatabaseFactory(dataRoot)
        val runId = "run-live-tool"
        val graderGroupId = "grader-0"
        val competingGroupId = "group-0"
        val graderDb = factory.getGrader(runId, graderGroupId)
        val competingDb = factory.get(runId, competingGroupId)

        graderDb.stagingDao().insert(
            StagingSubmissionEntity(
                id = "grader-criteria-live-1",
                agentId = "criteria-agent",
                role = Role.GraderResearcher.name,
                type = "PLAN_UPDATE",
                content = "CRITERION-LIVE: reward reproducible evidence and calibrated scoring.",
                budgetSignature = "budget:grader-criteria",
                status = "promoted",
                generation = 1,
                groupId = graderGroupId,
                runId = runId,
                submittedAtMs = 1_000L,
                reviewedAtMs = 1_500L
            )
        )
        competingDb.stagingDao().insert(
            StagingSubmissionEntity(
                id = "group-0-snapshot-live-1",
                agentId = "agent-0",
                role = "Builder",
                type = "DOC",
                content = "SNAPSHOT-LIVE-0: group-0 promoted document.",
                budgetSignature = "budget:group-0",
                status = "promoted",
                generation = 1,
                groupId = competingGroupId,
                runId = runId,
                submittedAtMs = 2_000L,
                reviewedAtMs = 2_100L
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
        val graderGroupManager = GraderGroupManager(scoringDimensions)
        var capturedToolName = ""

        val runner = GraderRunner(
            openRouterApiKey = "sk-test",
            substrateFactory = factory,
            localAgentRunner = { context ->
                val agent = context.javaClass.getMethod("getAgent").invoke(context)
                val toolRegistry = agent.javaClass.getMethod("getToolRegistry").invoke(agent)
                val getTools = toolRegistry.javaClass.methods.first { it.name == "getTools" }
                val tools = getTools.invoke(toolRegistry) as List<*>
                val submitTool = tools.single { it?.javaClass?.simpleName == "SubmitGradingScoreTool" }!!
                capturedToolName = submitTool.javaClass.simpleName

                val argsClass = submitTool.javaClass.declaredClasses.single { it.simpleName == "Args" }
                val args = argsClass.getDeclaredConstructor(
                    String::class.java,
                    String::class.java,
                    Double::class.javaPrimitiveType,
                    String::class.java
                ).newInstance(
                    competingGroupId,
                    "accuracy",
                    91.0,
                    "Live RoundJudge tool invocation."
                )

                submitTool::class.members.first { it.name == "execute" }
                    .callSuspend(submitTool, args)
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
            graderGroupManager = graderGroupManager
        )

        assertEquals("SubmitGradingScoreTool", capturedToolName)
        val roundScores = graderGroupManager.group(graderGroupId)
            .computeRoundScores(listOf(competingGroupId), 0)
            .single()
        assertEquals(91.0, roundScores.dimensionScores.single().rawScore)
        assertTrue(roundScores.dimensionScores.single().justification.contains("Live RoundJudge"))
    }

}
