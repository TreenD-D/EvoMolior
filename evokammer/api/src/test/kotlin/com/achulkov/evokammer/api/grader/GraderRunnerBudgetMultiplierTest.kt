package com.achulkov.evokammer.api.grader

import com.achulkov.evokammer.competition.grader.GraderGroupManager
import com.achulkov.evokammer.core.model.GroupProfileType
import com.achulkov.evokammer.core.model.Role
import com.achulkov.evokammer.core.model.ScoringDimension
import com.achulkov.evokammer.substrate.SubstrateDatabaseFactory
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class GraderRunnerBudgetMultiplierTest {

    @Test
    fun `grader budget multiplier raises the critic initial budget`() = runTest {
        val dataRoot = createTempDirectory("grader-budget-multiplier-").toFile()
        val runId = "run-grader-budget"
        val initialBudgets = mutableListOf<Double>()

        val runner = GraderRunner(
            openRouterApiKey = "sk-test",
            substrateFactory = SubstrateDatabaseFactory(dataRoot),
            localAgentRunner = { context ->
                if (context.role == Role.GraderCritic) {
                    initialBudgets += context.budgetLedger.snapshot().initialBudget
                }
                "ok"
            },
            graderBudgetMultiplier = 1.5
        )

        runner.runGraderRound(
            runId = runId,
            graderGroupIds = listOf("grader-0"),
            competingGroupIds = listOf("group-0"),
            roundIndex = 0,
            runObjective = "evaluate critic budget scaling",
            scoringDimensions = listOf(
                ScoringDimension(
                    id = "clarity",
                    name = "Clarity",
                    weight = 1.0,
                    description = "Checks whether the evaluation is clear."
                )
            ),
            graderGroupManager = GraderGroupManager(
                dimensions = listOf(
                    ScoringDimension(
                        id = "clarity",
                        name = "Clarity",
                        weight = 1.0,
                        description = "Checks whether the evaluation is clear."
                    )
                )
            )
        )

        assertTrue(initialBudgets.isNotEmpty())
        assertTrue(initialBudgets.all { it > 40.0 })
    }
}
