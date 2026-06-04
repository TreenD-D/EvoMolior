package com.achulkov.evokammer.api.grader

import com.achulkov.evokammer.core.model.Role
import com.achulkov.evokammer.core.model.ScoringDimension
import com.achulkov.evokammer.substrate.SubstrateDatabaseFactory
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GraderRunnerCriteriaFallbackTest {

    @Test
    fun `criteria phase persists synthetic criteria when agent writes none`() = runTest {
        val dataRoot = createTempDirectory("grader-criteria-fallback-test").toFile()
        val factory = SubstrateDatabaseFactory(dataRoot)
        val runId = "run-fallback"
        val graderGroupId = "grader-0"

        val runner = GraderRunner(
            openRouterApiKey = "sk-test",
            substrateFactory = factory,
            localAgentRunner = { context ->
                "Recovered output for ${context.role.name} without any tool writes."
            },
            graderAgentTimeoutMs = 1_000L
        )

        runner.runCriteriaPhase(
            runId = runId,
            graderGroupIds = listOf(graderGroupId),
            roundIndex = 0,
            runObjective = "judge worker groups rigorously",
            scoringDimensions = listOf(
                ScoringDimension(
                    id = "accuracy",
                    name = "Accuracy",
                    description = "Reward evidence-based work.",
                    weight = 1.0
                )
            )
        )

        val graderDb = factory.getGrader(runId, graderGroupId)
        val criteriaEntries = graderDb.stagingDao().getAll(runId, graderGroupId)
            .filter { it.type == "PLAN_UPDATE" }

        assertEquals(2, criteriaEntries.size)
        assertTrue(criteriaEntries.all { it.status == "promoted" })
        assertTrue(criteriaEntries.any { it.role == Role.GraderResearcher.name && it.content.contains("SYNTHETIC GRADER CRITERIA FALLBACK") })
        assertTrue(criteriaEntries.any { it.role == Role.GraderBuilder.name && it.content.contains("Hard-to-fake signal:") })
    }
}
