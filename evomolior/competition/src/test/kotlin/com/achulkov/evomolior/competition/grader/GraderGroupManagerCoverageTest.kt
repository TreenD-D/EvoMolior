package com.achulkov.evomolior.competition.grader

import com.achulkov.evomolior.core.model.DimensionScore
import com.achulkov.evomolior.core.model.ScoringDimension
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GraderGroupManagerCoverageTest {
    private val dimensions = listOf(
        ScoringDimension("accuracy", "Accuracy", "Accuracy", 1.0, maxScore = 10.0),
        ScoringDimension("evidence", "Evidence", "Evidence", 1.0, maxScore = 10.0)
    )

    @Test
    fun `complete explicit zero scores remain authoritative`() {
        val manager = GraderGroupManager(dimensions)
        listOf("group-0", "group-1").forEach { groupId ->
            manager.submitScores(
                graderGroupId = "grader-0",
                targetGroupId = groupId,
                roundIndex = 0,
                scores = dimensions.map { dimension ->
                    DimensionScore(dimension.id, 0.0, "Explicit zero from RoundJudge")
                }
            )
        }

        assertTrue(manager.hasCompleteRoundScores(listOf("group-0", "group-1"), 0))
    }

    @Test
    fun `missing target or dimension requires fallback`() {
        val manager = GraderGroupManager(dimensions)
        manager.submitScores(
            graderGroupId = "grader-0",
            targetGroupId = "group-0",
            roundIndex = 0,
            scores = listOf(DimensionScore("accuracy", 8.0, "Only one parsed dimension"))
        )

        assertFalse(manager.hasCompleteRoundScores(listOf("group-0", "group-1"), 0))
        assertFailsWith<IllegalStateException> {
            manager.computeRoundResult(listOf("group-0", "group-1"), 0)
        }
    }

    @Test
    fun `grader fragments cannot combine into authoritative coverage`() {
        val manager = GraderGroupManager(dimensions)
        manager.submitScores(
            graderGroupId = "grader-0",
            targetGroupId = "group-0",
            roundIndex = 0,
            scores = listOf(DimensionScore("accuracy", 8.0, "Accuracy only"))
        )
        manager.submitScores(
            graderGroupId = "grader-1",
            targetGroupId = "group-0",
            roundIndex = 0,
            scores = listOf(DimensionScore("evidence", 8.0, "Evidence only"))
        )

        assertFalse(manager.hasCompleteRoundScores(listOf("group-0"), 0))
    }
}
