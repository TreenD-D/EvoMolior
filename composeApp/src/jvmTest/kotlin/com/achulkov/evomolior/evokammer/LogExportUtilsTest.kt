package com.achulkov.evomolior.evokammer

import com.achulkov.evokammer.api.events.EvoKammerEvent
import kotlin.test.Test
import kotlin.test.assertTrue

class LogExportUtilsTest {

    @Test
    fun `grader phase engine progress is exported distinctly`() {
        val criteriaLine = EvoKammerEvent.EngineProgress(
            runId = "run-1",
            phase = "grader_criteria_start",
            message = "[grader-0] criteria phase starting"
        ).toLogLine()
        val judgeLine = EvoKammerEvent.EngineProgress(
            runId = "run-1",
            phase = "grader_judge",
            message = "[grader-0] judge running"
        ).toLogLine()
        val finalAnswerLine = EvoKammerEvent.EngineProgress(
            runId = "run-1",
            phase = "final_answer",
            message = "Round 4 final answer: winner=group-0"
        ).toLogLine()
        val startedLine = EvoKammerEvent.GraderRoundStarted(
            runId = "run-1",
            graderGroupId = "grader-0",
            roundIndex = 1,
            competingGroupIds = listOf("group-0", "group-1")
        ).toLogLine()
        val completedLine = EvoKammerEvent.GraderRoundCompleted(
            runId = "run-1",
            graderGroupId = "grader-0",
            roundIndex = 1,
            scoresPerGroup = mapOf("group-0" to "kb_density=45.0"),
            totalScoresCollected = 4
        ).toLogLine()

        assertTrue(criteriaLine.contains("[GRADER GRADER_CRITERIA_START]"))
        assertTrue(judgeLine.contains("[GRADER GRADER_JUDGE]"))
        assertTrue(finalAnswerLine.contains("[FINAL ANSWER]"))
        assertTrue(startedLine.contains("[GRADER grader-0] R1 started"))
        assertTrue(completedLine.contains("R1 complete"))
    }
}
