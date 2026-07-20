package com.achulkov.evomolior.core.benchmark

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Tier0BenchmarkHarnessTest {

    @Test
    fun `harness runs supported tasks and aggregates score`() = runTest {
        val harness = Tier0BenchmarkHarness(
            runner = Tier0TaskRunner { task, objective ->
                assertTrue(objective.contains("evidence_path"))
                Tier0BenchmarkPrediction(
                    taskId = task.id,
                    finalAnswer = task.acceptedAnswers.single(),
                    evidencePath = listOf(Tier0EvidenceStep("source", "claim")),
                    totalCost = 2.0
                )
            }
        )
        val tasks = listOf(
            Tier0BenchmarkTask("frames-1", Tier0BenchmarkKind.FRAMES, "Q1", listOf("A")),
            Tier0BenchmarkTask("browse-1", Tier0BenchmarkKind.BROWSECOMP, "Q2", listOf("B"))
        )

        val report = harness.run(tasks)

        assertEquals(2, report.executedCount)
        assertEquals(0, report.skippedCount)
        assertEquals(2, report.batchScore.exactCorrectCount)
        assertEquals(4.0, report.batchScore.measuredCostTotal)
        assertEquals(2.0, report.batchScore.costPerCorrect)
    }

    @Test
    fun `harness skips multimodal GAIA tasks unless enabled`() = runTest {
        var executed = false
        val harness = Tier0BenchmarkHarness(
            runner = Tier0TaskRunner { task, _ ->
                executed = true
                Tier0BenchmarkPrediction(taskId = task.id, finalAnswer = "unused")
            },
            policy = Tier0BenchmarkRunPolicy(includeMultimodal = false)
        )

        val report = harness.run(
            listOf(
                Tier0BenchmarkTask(
                    id = "gaia-mm",
                    benchmark = Tier0BenchmarkKind.GAIA,
                    question = "Read the image and answer.",
                    acceptedAnswers = listOf("answer"),
                    requiresMultimodal = true
                )
            )
        )

        assertEquals(0, report.executedCount)
        assertEquals(1, report.skippedCount)
        assertEquals("multimodal_not_enabled", report.results.single().skipReason)
        assertTrue(!executed)
    }
}
