package com.achulkov.evomolior.evomolior.screens

import com.achulkov.evomolior.api.events.EvoMoliorEvent
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class EvoMoliorDashboardScreenTest {

    @Test
    fun `grader card status advances across criteria judge and completion phases`() {
        val graderId = "grader-0"
        val roundStarted = EvoMoliorEvent.GraderRoundStarted(
            runId = "run-1",
            graderGroupId = graderId,
            roundIndex = 2,
            competingGroupIds = listOf("group-0", "group-1")
        )
        val criteriaStart = EvoMoliorEvent.EngineProgress(
            runId = "run-1",
            phase = "grader_criteria_start",
            message = "[$graderId] criteria phase starting"
        )
        val judgeResult = EvoMoliorEvent.EngineProgress(
            runId = "run-1",
            phase = "grader_judge_result",
            message = "Round 2 RoundJudge output: [$graderId] score 91.0"
        )
        val judgeStart = EvoMoliorEvent.EngineProgress(
            runId = "run-1",
            phase = "grader_judge_start",
            message = "[$graderId] RoundJudge starting for round 2"
        )
        val roundCompleted = EvoMoliorEvent.GraderRoundCompleted(
            runId = "run-1",
            graderGroupId = graderId,
            roundIndex = 2,
            scoresPerGroup = mapOf("group-0" to "accuracy=91.0"),
            totalScoresCollected = 8
        )

        val criteriaStatus = buildGraderCardStatus(graderId, listOf(roundStarted, criteriaStart))
        assertContains(criteriaStatus.statusText, "criteria start")
        assertEquals("criteria start", graderPhaseLabel(criteriaStatus.lastPhase?.phase.orEmpty()))

        val judgeResultStatus = buildGraderCardStatus(graderId, listOf(roundStarted, criteriaStart, judgeResult))
        assertContains(judgeResultStatus.statusText, "judge result")
        assertEquals("judge result", graderPhaseLabel(judgeResultStatus.lastPhase?.phase.orEmpty()))

        val judgeStatus = buildGraderCardStatus(graderId, listOf(roundStarted, criteriaStart, judgeResult, judgeStart))
        assertContains(judgeStatus.statusText, "judge start")
        assertEquals("judge start", graderPhaseLabel(judgeStatus.lastPhase?.phase.orEmpty()))

        val completedStatus = buildGraderCardStatus(graderId, listOf(roundStarted, criteriaStart, judgeResult, judgeStart, roundCompleted))
        assertEquals("R2 done — 8 scores", completedStatus.statusText)
        assertNotNull(completedStatus.lastPhase)
        assertEquals("judge start", graderPhaseLabel(completedStatus.lastPhase!!.phase))
    }
}
