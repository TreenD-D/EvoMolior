package com.achulkov.evokammer.api.grader

import com.achulkov.evokammer.agents.factory.GraderAgentContext
import com.achulkov.evokammer.competition.grader.GraderGroupManager
import com.achulkov.evokammer.core.model.Role
import com.achulkov.evokammer.core.model.ScoringDimension
import com.achulkov.evokammer.substrate.SubstrateDatabaseFactory
import com.achulkov.evokammer.substrate.db.entity.StagingSubmissionEntity
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        val capturedTargetGroupIds = mutableListOf<String>()
        var capturedTaskPrompt: String? = null
        val runner = GraderRunner(
            openRouterApiKey = "sk-test",
            substrateFactory = factory,
            localAgentRunner = { context ->
                capturedRole = context.role
                capturedTargetGroupIds += context.targetGroupId
                capturedTaskPrompt = context.taskPrompt
                submitScoreThroughJudgeTool(
                    context = context,
                    targetGroupId = context.targetGroupId,
                    dimensionId = "accuracy",
                    rawScore = if (context.targetGroupId == competingGroupIds[0]) 88.0 else 72.0,
                    rationale = "Prompt contained the grader criteria and ${context.targetGroupId} snapshot."
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
        assertEquals(competingGroupIds, capturedTargetGroupIds)
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
    fun `judging phase retries once when round judge emits no scores`() = runTest {
        val dataRoot = createTempDirectory("grader-judging-retry-test").toFile()
        val factory = SubstrateDatabaseFactory(dataRoot)
        val runId = "run-retry"
        val graderGroupId = "grader-0"
        val competingGroupId = "group-0"

        val graderDb = factory.getGrader(runId, graderGroupId)
        val competingDb = factory.get(runId, competingGroupId)

        graderDb.stagingDao().insert(
            StagingSubmissionEntity(
                id = "grader-criteria-retry-1",
                agentId = "criteria-agent",
                role = Role.GraderResearcher.name,
                type = "PLAN_UPDATE",
                content = "CRITERION-RETRY: score the concrete deliverable evidence.",
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
                id = "group-0-snapshot-retry-1",
                agentId = "agent-0",
                role = "Builder",
                type = "DOC",
                content = "SNAPSHOT-RETRY-0: group-0 promoted document.",
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
        var attempts = 0
        var retryPrompt = ""

        val runner = GraderRunner(
            openRouterApiKey = "sk-test",
            substrateFactory = factory,
            localAgentRunner = { context ->
                attempts += 1
                if (attempts == 2) {
                    retryPrompt = context.systemPrompt
                    submitScoreThroughJudgeTool(
                        context = context,
                        targetGroupId = context.targetGroupId,
                        dimensionId = "accuracy",
                        rawScore = 83.0,
                        rationale = "Retry supplied the required score."
                    )
                }
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

        assertEquals(2, attempts)
        assertContains(retryPrompt, "RETRY REQUIRED: MISSING GRADING SCORES")
        assertContains(retryPrompt, "accuracy")

        val roundScores = graderGroupManager.group(graderGroupId)
            .computeRoundScores(listOf(competingGroupId), 0)
            .single()

        assertEquals(83.0, roundScores.dimensionScores.single().rawScore)
    }

    @Test
    fun `judging phase submits neutral fallback scores when retry still emits no scores`() = runTest {
        val dataRoot = createTempDirectory("grader-judging-fallback-score-test").toFile()
        val factory = SubstrateDatabaseFactory(dataRoot)
        val runId = "run-fallback-score"
        val graderGroupId = "grader-0"
        val competingGroupId = "group-0"

        val graderDb = factory.getGrader(runId, graderGroupId)
        val competingDb = factory.get(runId, competingGroupId)

        graderDb.stagingDao().insert(
            StagingSubmissionEntity(
                id = "grader-criteria-fallback-score-1",
                agentId = "criteria-agent",
                role = Role.GraderResearcher.name,
                type = "PLAN_UPDATE",
                content = "CRITERION-FALLBACK: score the deliverable evidence.",
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
                id = "group-0-snapshot-fallback-score-1",
                agentId = "agent-0",
                role = "Builder",
                type = "DOC",
                content = "SNAPSHOT-FALLBACK-SCORE-0: group-0 promoted document.",
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
        var attempts = 0

        val runner = GraderRunner(
            openRouterApiKey = "sk-test",
            substrateFactory = factory,
            localAgentRunner = {
                attempts += 1
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

        assertEquals(2, attempts)
        val fallbackScore = graderGroupManager.group(graderGroupId)
            .computeRoundScores(listOf(competingGroupId), 0)
            .single()
            .dimensionScores
            .single()

        assertEquals("accuracy", fallbackScore.dimensionId)
        assertEquals(50.0, fallbackScore.rawScore)
        assertContains(fallbackScore.justification, "Neutral fallback")
    }

    @Test
    fun `judging phase retries only missing dimensions after partial score submission`() = runTest {
        val dataRoot = createTempDirectory("grader-judging-partial-retry-test").toFile()
        val factory = SubstrateDatabaseFactory(dataRoot)
        val runId = "run-partial-retry"
        val graderGroupId = "grader-0"
        val competingGroupId = "group-0"

        val graderDb = factory.getGrader(runId, graderGroupId)
        val competingDb = factory.get(runId, competingGroupId)

        graderDb.stagingDao().insert(
            StagingSubmissionEntity(
                id = "grader-criteria-partial-retry-1",
                agentId = "criteria-agent",
                role = Role.GraderResearcher.name,
                type = "PLAN_UPDATE",
                content = "CRITERION-PARTIAL: score accuracy and usability separately.",
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
                id = "group-0-snapshot-partial-retry-1",
                agentId = "agent-0",
                role = "Builder",
                type = "DOC",
                content = "SNAPSHOT-PARTIAL-RETRY-0: group-0 promoted document.",
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
                weight = 0.6
            ),
            ScoringDimension(
                id = "usability",
                name = "Usability",
                description = "Reward usable deliverables.",
                weight = 0.4
            )
        )
        val graderGroupManager = GraderGroupManager(scoringDimensions)
        var attempts = 0
        var retryPrompt = ""

        val runner = GraderRunner(
            openRouterApiKey = "sk-test",
            substrateFactory = factory,
            localAgentRunner = { context ->
                attempts += 1
                if (attempts == 1) {
                    submitScoreThroughJudgeTool(
                        context = context,
                        targetGroupId = context.targetGroupId,
                        dimensionId = "accuracy",
                        rawScore = 88.0,
                        rationale = "First attempt only scored accuracy."
                    )
                } else {
                    retryPrompt = context.systemPrompt
                    submitScoreThroughJudgeTool(
                        context = context,
                        targetGroupId = context.targetGroupId,
                        dimensionId = "usability",
                        rawScore = 76.0,
                        rationale = "Retry supplied the missing usability score."
                    )
                }
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

        assertEquals(2, attempts)
        assertContains(retryPrompt, "RETRY REQUIRED: MISSING GRADING SCORES")
        assertContains(retryPrompt, "usability")
        assertFalse(retryPrompt.contains("accuracy, usability"))

        val scoresByDimension = graderGroupManager.group(graderGroupId)
            .computeRoundScores(listOf(competingGroupId), 0)
            .single()
            .dimensionScores
            .associateBy { it.dimensionId }

        assertEquals(88.0, scoresByDimension.getValue("accuracy").rawScore)
        assertEquals(76.0, scoresByDimension.getValue("usability").rawScore)
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

    private suspend fun submitScoreThroughJudgeTool(
        context: GraderAgentContext,
        targetGroupId: String,
        dimensionId: String,
        rawScore: Double,
        rationale: String
    ) {
        val agent = context.javaClass.getMethod("getAgent").invoke(context)
        val toolRegistry = agent.javaClass.getMethod("getToolRegistry").invoke(agent)
        val getTools = toolRegistry.javaClass.methods.first { it.name == "getTools" }
        val tools = getTools.invoke(toolRegistry) as List<*>
        val submitTool = tools.single { it?.javaClass?.simpleName == "SubmitGradingScoreTool" }!!

        val argsClass = submitTool.javaClass.declaredClasses.single { it.simpleName == "Args" }
        val args = argsClass.getDeclaredConstructor(
            String::class.java,
            String::class.java,
            Double::class.javaPrimitiveType,
            String::class.java
        ).newInstance(
            targetGroupId,
            dimensionId,
            rawScore,
            rationale
        )

        submitTool::class.members.first { it.name == "execute" }
            .callSuspend(submitTool, args)
    }

}
