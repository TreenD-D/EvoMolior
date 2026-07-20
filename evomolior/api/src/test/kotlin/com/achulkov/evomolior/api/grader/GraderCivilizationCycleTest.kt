package com.achulkov.evomolior.api.grader

import com.achulkov.evomolior.api.service.EvoMoliorRunService
import com.achulkov.evomolior.competition.coordinator.GroupCoordinator
import com.achulkov.evomolior.competition.grader.GraderGroupManager
import com.achulkov.evomolior.competition.grader.GradingSheet
import com.achulkov.evomolior.competition.grader.RubricDimension
import com.achulkov.evomolior.core.model.Role
import com.achulkov.evomolior.core.model.ScoringDimension
import com.achulkov.evomolior.core.model.SubmissionType
import com.achulkov.evomolior.orchestration.metaplanner.MetaPlannerRunner
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.reflect.full.callSuspend
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end regression for the grader civilisation cycle wiring.
 */
class GraderCivilizationCycleTest {

    @Test
    fun `criteria phase review judging submission and verdict happen in order`() = runTest {
        val dataRoot = createTempDirectory("grader-civilization-cycle-test").toFile()
        val factory = SubstrateDatabaseFactory(dataRoot)
        val runId = "run-grader-cycle"
        val graderGroupId = "grader-0"
        val competingGroupId = "group-0"
        val order = mutableListOf<String>()

        val graderDb = factory.getGrader(runId, graderGroupId)
        factory.get(runId, competingGroupId).stagingDao().insert(
            StagingSubmissionEntity(
                id = "group-0-doc-1",
                agentId = "agent-0",
                role = Role.Researcher.name,
                type = SubmissionType.DOC.name,
                content = "CANON-SNAPSHOT-0: group-0 promoted document.",
                budgetSignature = "budget:group-0",
                status = "promoted",
                generation = 1,
                groupId = competingGroupId,
                runId = runId,
                submittedAtMs = 1_000L,
                reviewedAtMs = 1_100L
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

        val service = EvoMoliorRunService(
            lifecycleManagerProvider = { _, _, _, _, _ ->
                throw IllegalStateException("LifecycleManager should not be constructed in this test")
            },
            groupCoordinatorProvider = { groupIds, dimensions ->
                GroupCoordinator(groupIds, dimensions)
            },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = factory,
            metaPlannerRunner = MetaPlannerRunner(),
            graderCriteriaReviewRunner = { reviewRunId, _, reviewerGroupIds, roundIndex, onProgress ->
                order += "criteria-review"
                assertEquals(runId, reviewRunId)
                assertEquals(listOf(graderGroupId), reviewerGroupIds)
                assertEquals(0, roundIndex)
                val pendingCriteria = graderDb.stagingDao().getAll(runId, graderGroupId)
                    .filter { it.type == SubmissionType.PLAN_UPDATE.name && it.status == "pending" }
                pendingCriteria.forEach { entry ->
                    graderDb.stagingDao().updateReview(
                        id = entry.id,
                        status = "promoted",
                        criticId = "critic-test",
                        trustScore = 0.9,
                        reason = null,
                        reviewedAtMs = 2_000L
                    )
                }
                onProgress?.invoke("grader criteria review complete")
            }
        )

        val graderRunner = GraderRunner(
            openRouterApiKey = "sk-test",
            substrateFactory = factory,
            localAgentRunner = { context ->
                if (context.role == Role.GraderResearcher || context.role == Role.GraderBuilder) {
                    if (order.none { it == "criteria-phase" }) {
                        order += "criteria-phase"
                    }
                    graderDb.stagingDao().insert(
                        StagingSubmissionEntity(
                            id = "criteria-${context.role.name.lowercase()}",
                            agentId = context.agentId,
                            role = context.role.name,
                            type = SubmissionType.PLAN_UPDATE.name,
                            content = "criteria draft for ${context.targetGroupId}",
                            budgetSignature = "system:grader-criteria",
                            status = "pending",
                            generation = context.roundIndex + 1,
                            groupId = graderGroupId,
                            runId = runId,
                            submittedAtMs = 1_500L,
                            reviewedAtMs = null
                        )
                    )
                    ""
                } else {
                    order += "judging-phase"
                    val agent = context.javaClass.getMethod("getAgent").invoke(context)
                    val toolRegistry = agent.javaClass.getMethod("getToolRegistry").invoke(agent)
                    val getTools = toolRegistry.javaClass.methods.first { it.name == "getTools" }
                    val tools = getTools.invoke(toolRegistry) as List<*>
                    val submitTool = tools.single { it?.javaClass?.simpleName == "SubmitGradingScoreTool" }!!
                    val argsClass = submitTool.javaClass.declaredClasses.single { it.simpleName == "Args" }
                    val execute = submitTool::class.members.first { it.name == "execute" }
                    val scoreArgs = argsClass.getDeclaredConstructor(
                        String::class.java,
                        String::class.java,
                        Double::class.javaPrimitiveType,
                        String::class.java
                    )
                    val args = scoreArgs.newInstance(
                        competingGroupId,
                        "accuracy",
                        91.0,
                        "Production-style e2e grading cycle."
                    )
                    execute.callSuspend(submitTool, args)
                    order += "submit-grading-score"
                    ""
                }
            },
            graderAgentTimeoutMs = 1_000L
        )

        graderRunner.runCriteriaPhase(
            runId = runId,
            graderGroupIds = listOf(graderGroupId),
            roundIndex = 0,
            runObjective = "evaluate the run objectively",
            scoringDimensions = scoringDimensions
        )

        service.reviewGraderCriteriaForRound(
            runId = runId,
            openRouterApiKey = "sk-test",
            graderGroupIds = listOf(graderGroupId),
            roundIndex = 0
        )

        graderRunner.runJudgingPhase(
            runId = runId,
            graderGroupIds = listOf(graderGroupId),
            competingGroupIds = listOf(competingGroupId),
            roundIndex = 0,
            runObjective = "evaluate the run objectively",
            scoringDimensions = scoringDimensions,
            graderGroupManager = graderGroupManager
        )

        graderGroupManager.registerSheet(
            GradingSheet(
                sheetId = "sheet-0",
                runId = runId,
                roundIndex = 0,
                graderGroupId = graderGroupId,
                rubricDimensions = listOf(
                    RubricDimension(
                        dimensionId = "accuracy",
                        name = "Accuracy",
                        weight = 1.0,
                        scoringGuidance = "Reward evidence-based evaluation.",
                        maxScore = 100.0
                    )
                ),
                automatedTests = emptyList(),
                failureModes = emptyList(),
                comparativePairs = emptyList(),
                sealedAt = 1L
            )
        )

        val verdict = graderGroupManager.computeRoundResult(listOf(competingGroupId), 0)
        order += "verdict"

        assertTrue(order.indexOf("criteria-phase") < order.indexOf("criteria-review"))
        assertTrue(order.indexOf("criteria-review") < order.indexOf("judging-phase"))
        assertTrue(order.indexOf("judging-phase") < order.indexOf("submit-grading-score"))
        assertTrue(order.indexOf("submit-grading-score") < order.indexOf("verdict"))
        assertEquals("group-0", verdict.winnerId)
        assertEquals(91.0, verdict.scores.single().dimensionScores.single().rawScore)
    }
}
