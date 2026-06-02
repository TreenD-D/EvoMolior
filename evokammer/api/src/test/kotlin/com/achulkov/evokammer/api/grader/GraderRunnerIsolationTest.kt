package com.achulkov.evokammer.api.grader

import com.achulkov.evokammer.core.model.ScoringDimension
import com.achulkov.evokammer.substrate.SubstrateDatabaseFactory
import com.achulkov.evokammer.substrate.db.entity.StagingSubmissionEntity
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies that the grader runner keeps its writes confined to the grader substrate root.
 */
class GraderRunnerIsolationTest {

    @Test
    fun `criteria phase writes only to grader substrate not competing substrate`() = runTest {
        val dataRoot = createTempDirectory("grader-runner-isolation-test").toFile()
        val factory = SubstrateDatabaseFactory(dataRoot)
        val runId = "run-1"
        val graderGroupId = "grader-0"
        val competingGroupId = "group-0"
        val graderDb = factory.getGrader(runId, graderGroupId)
        val competingDb = factory.get(runId, competingGroupId)

        val runner = GraderRunner(
            openRouterApiKey = "sk-test",
            substrateFactory = factory,
            localAgentRunner = { context ->
                graderDb.stagingDao().insert(
                    StagingSubmissionEntity(
                        id = "criteria-${context.role.name.lowercase()}",
                        agentId = context.agentId,
                        role = context.role.name,
                        type = "PLAN_UPDATE",
                        content = "grader criteria for ${context.targetGroupId}",
                        budgetSignature = "system:grader-criteria",
                        status = "promoted",
                        generation = context.roundIndex,
                        groupId = graderGroupId,
                        runId = runId,
                        submittedAtMs = 1_000L,
                        reviewedAtMs = 1_000L
                    )
                )
                ""
            },
            graderAgentTimeoutMs = 1_000L
        )

        runner.runCriteriaPhase(
            runId = runId,
            graderGroupIds = listOf(graderGroupId),
            roundIndex = 0,
            runObjective = "test objective",
            scoringDimensions = listOf(
                ScoringDimension(
                    id = "accuracy",
                    name = "Accuracy",
                    description = "Measure how closely the grader follows the rubric",
                    weight = 1.0
                )
            )
        )

        val graderRows = graderDb.stagingDao().getAll(runId, graderGroupId)
        val competingRows = competingDb.stagingDao().getAll(runId, competingGroupId)

        assertEquals(2, graderRows.count { it.type == "PLAN_UPDATE" })
        assertTrue(
            competingRows.none { it.type == "PLAN_UPDATE" },
            "Criteria-phase writes must not leak into the competing group's substrate"
        )
    }
}
