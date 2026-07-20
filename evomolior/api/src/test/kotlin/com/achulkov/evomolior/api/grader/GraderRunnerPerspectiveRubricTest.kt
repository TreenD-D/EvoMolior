package com.achulkov.evomolior.api.grader

import com.achulkov.evomolior.core.model.ScoringDimension
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains

class GraderRunnerPerspectiveRubricTest {

    @Test
    fun `criteria phase grader prompt includes the perspective coverage rubric`() = runTest {
        val dataRoot = createTempDirectory("grader-perspective-rubric-test").toFile()
        val factory = SubstrateDatabaseFactory(dataRoot)
        val runId = "run-perspective-rubric"
        val graderGroupId = "grader-0"
        var capturedPrompt = ""

        val runner = GraderRunner(
            openRouterApiKey = "sk-test",
            substrateFactory = factory,
            localAgentRunner = { context ->
                capturedPrompt = context.systemPrompt
                ""
            },
            graderAgentTimeoutMs = 1_000L
        )

        runner.runCriteriaPhase(
            runId = runId,
            graderGroupIds = listOf(graderGroupId),
            roundIndex = 0,
            runObjective = "evaluate stakeholder-aware reasoning",
            scoringDimensions = listOf(
                ScoringDimension(
                    id = "accuracy",
                    name = "Accuracy",
                    description = "Reward evidence-based evaluation.",
                    weight = 1.0
                )
            )
        )

        assertContains(capturedPrompt, "=== PERSPECTIVE COVERAGE RUBRIC ===")
        assertContains(capturedPrompt, "stakeholder perspectives")
        assertContains(capturedPrompt, "trade-off reconciliation")
    }
}
