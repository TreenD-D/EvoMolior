package com.achulkov.evomolior.agents.tools

import com.achulkov.evomolior.core.atlas.NoveltyAssessmentStatus
import com.achulkov.evomolior.core.atlas.NoveltyGraderAssessment
import com.achulkov.evomolior.core.budget.BudgetConfig
import com.achulkov.evomolior.core.budget.BudgetLedger
import com.achulkov.evomolior.core.model.GroupProfile
import com.achulkov.evomolior.core.model.GroupProfileType
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SubmitNoveltyAssessmentToolTest {
    @Test
    fun `valid assessment is bound to proposal and grader authority`() = runTest {
        val assessments = mutableListOf<NoveltyGraderAssessment>()
        val tool = tool(assessments)

        val result = tool.execute(validArgs())

        assertTrue(result.startsWith("Novelty assessment recorded"))
        val assessment = assessments.single()
        assertEquals("proposal-hash", assessment.proposalHash)
        assertEquals("grader-1", assessment.graderGroupId)
        assertEquals(NoveltyAssessmentStatus.VALID, assessment.status)
        assertTrue(assessment.hasValidHash())
    }

    @Test
    fun `malformed status and score combinations fail without callback`() = runTest {
        val assessments = mutableListOf<NoveltyGraderAssessment>()
        val tool = tool(assessments)

        assertTrue(
            tool.execute(validArgs().copy(costDiscipline = null))
                .startsWith("Error: VALID")
        )
        assertTrue(
            tool.execute(
                validArgs().copy(
                    status = NoveltyAssessmentStatus.ABSTAINED
                )
            ).startsWith("Error: non-VALID")
        )
        assertTrue(
            tool.execute(validArgs().copy(novelty = 1.2))
                .startsWith("Error:")
        )
        assertTrue(assessments.isEmpty())
    }

    private fun tool(
        assessments: MutableList<NoveltyGraderAssessment>
    ): SubmitNoveltyAssessmentTool {
        val config = BudgetConfig("grader-1", GroupProfile.default(GroupProfileType.Library))
        return SubmitNoveltyAssessmentTool(
            budgetLedger = BudgetLedger("assessor-1", 50.0, 1.0, config),
            proposalHash = "proposal-hash",
            graderGroupId = "grader-1",
            onAssessmentSubmitted = assessments::add
        )
    }

    private fun validArgs() = SubmitNoveltyAssessmentTool.Args(
        status = NoveltyAssessmentStatus.VALID,
        novelty = 0.9,
        receiverRelevance = 0.8,
        testability = 0.85,
        plausibility = 0.7,
        expectedImpact = 0.75,
        costDiscipline = 0.65,
        rationale = "The proposal is connected, falsifiable, and materially relevant."
    )
}
