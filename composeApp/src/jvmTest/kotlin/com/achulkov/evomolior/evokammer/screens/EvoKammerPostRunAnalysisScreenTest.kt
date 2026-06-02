package com.achulkov.evomolior.evokammer.screens

import com.achulkov.evokammer.api.service.MetaArchiveEntryRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EvoKammerPostRunAnalysisScreenTest {

    @Test
    fun `final answer card prefers durable completion archive and preserves judge outputs and submission summaries`() {
        val roundSummaryEntry = archiveEntry(
            entryType = "ROUND_SUMMARY",
            contentJson = """
                {
                  "runId": "run-1",
                  "roundIndex": 3,
                  "roundWinnerId": "group-round",
                  "judgeOutputsByGrader": {
                    "grader-round": "round summary judge output"
                  },
                  "groupSummaries": [
                    {
                      "groupId": "group-round",
                      "submissionSummary": "round summary submission"
                    }
                  ]
                }
            """.trimIndent()
        )
        val completionEntry = archiveEntry(
            entryType = "RUN_COMPLETED",
            contentJson = """
                {
                  "runId": "run-1",
                  "winnerId": "group-completion",
                  "finalAnswerSummary": "final answer summary",
                  "judgeOutputsByGrader": {
                    "grader-final": "archived judge output"
                  },
                  "submissionSummariesByGroup": {
                    "group-completion": "archived submission summary"
                  }
                }
            """.trimIndent()
        )

        val state = buildFinalAnswerCardState(
            roundSummaryEntries = listOf(roundSummaryEntry),
            runCompletionEntries = listOf(completionEntry)
        )

        assertNotNull(state)
        assertEquals("group-completion", state.winnerId)
        assertEquals("final answer summary", state.finalAnswerSummary)
        assertEquals("archived judge output", state.judgeOutputsByGrader["grader-final"])
        assertEquals("archived submission summary", state.submissionSummariesByGroup["group-completion"])
        assertTrue("round summary judge output" !in state.judgeOutputsByGrader.values)
    }

    @Test
    fun `final answer card falls back to latest round summary when completion archive is absent`() {
        val roundSummaryEntry = archiveEntry(
            entryType = "ROUND_SUMMARY",
            contentJson = """
                {
                  "runId": "run-2",
                  "roundIndex": 1,
                  "roundWinnerId": "group-round",
                  "judgeOutputsByGrader": {
                    "grader-round": "round summary judge output"
                  },
                  "groupSummaries": [
                    {
                      "groupId": "group-round",
                      "submissionSummary": "round summary submission"
                    }
                  ]
                }
            """.trimIndent()
        )

        val state = buildFinalAnswerCardState(
            roundSummaryEntries = listOf(roundSummaryEntry),
            runCompletionEntries = emptyList()
        )

        assertNotNull(state)
        assertEquals("group-round", state.winnerId)
        assertEquals("", state.finalAnswerSummary)
        assertEquals("round summary judge output", state.judgeOutputsByGrader["grader-round"])
        assertEquals("round summary submission", state.submissionSummariesByGroup["group-round"])
    }

    @Test
    fun `archived grading history rebuilds rounds in order from archived round summaries`() {
        val roundThree = archiveEntry(
            entryType = "ROUND_SUMMARY",
            contentJson = """
                {
                  "runId": "run-3",
                  "roundIndex": 3,
                  "roundWinnerId": "group-beta",
                  "groupSummaries": [
                    {
                      "groupId": "group-alpha",
                      "blindScore": {
                        "weightedTotal": 12.5,
                        "rank": 2,
                        "dimensionScores": {
                          "kb_density": 4.0,
                          "kg_coherence": 8.5
                        }
                      }
                    },
                    {
                      "groupId": "group-beta",
                      "blindScore": {
                        "weightedTotal": 18.0,
                        "rank": 1,
                        "dimensionScores": {
                          "kb_density": 6.0,
                          "kg_coherence": 12.0
                        }
                      }
                    }
                  ]
                }
            """.trimIndent()
        )
        val roundOne = archiveEntry(
            entryType = "ROUND_SUMMARY",
            contentJson = """
                {
                  "runId": "run-3",
                  "roundIndex": 1,
                  "roundWinnerId": "group-alpha",
                  "groupSummaries": [
                    {
                      "groupId": "group-alpha",
                      "blindScore": {
                        "weightedTotal": 9.0,
                        "rank": 1,
                        "dimensionScores": {
                          "kb_density": 3.0,
                          "kg_coherence": 6.0
                        }
                      }
                    }
                  ]
                }
            """.trimIndent()
        )

        val history = buildArchivedGradingHistory(listOf(roundThree, roundOne))

        assertEquals(2, history.size)
        assertEquals(1, history.first().roundIndex)
        assertEquals("group-alpha", history.first().winnerId)
        assertEquals(9.0, history.first().scores.first().weightedTotal)
        assertEquals(3, history.last().roundIndex)
        assertEquals("group-beta", history.last().winnerId)
        assertEquals(18.0, history.last().scores.first { it.groupId == "group-beta" }.weightedTotal)
    }

    private fun archiveEntry(
        entryType: String,
        contentJson: String
    ): MetaArchiveEntryRecord = MetaArchiveEntryRecord(
        id = "entry-$entryType",
        runId = "run-1",
        entryType = entryType,
        groupId = null,
        label = entryType,
        contentJson = contentJson,
        runObjective = "objective",
        finalScore = null,
        isFromWinner = false,
        generation = 0,
        chunkIndex = 0,
        totalChunks = 1,
        archivedAtMs = 1L
    )
}
