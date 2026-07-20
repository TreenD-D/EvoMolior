@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.core.benchmark

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class ComparativeBenchmarkHarnessTest {
    @Test
    fun `harness executes every arm in sealed assignment order`() = kotlinx.coroutines.test.runTest {
        val plan = plan()
        val seen = mutableListOf<ComparativeExperimentAssignment>()
        val executor = ComparativeArmExecutor { _, assignment ->
            seen += assignment
            result(assignment)
        }
        val harness = ComparativeBenchmarkHarness(
            executors = ComparativeBenchmarkArm.entries.associateWith { executor },
            clock = FixedClock(Instant.parse("2026-07-12T11:00:00Z"))
        )

        val execution = harness.run(plan)

        assertEquals(plan.assignments(), seen)
        assertEquals(6, execution.results.size)
        assertTrue(execution.report.hasValidHash())
        assertEquals(
            Instant.parse("2026-07-12T11:00:00Z"),
            execution.report.generatedAt
        )
    }

    @Test
    fun `harness rejects missing adapters and mismatched assignment results`() =
        kotlinx.coroutines.test.runTest {
            val plan = plan()
            assertFailsWith<IllegalArgumentException> {
                ComparativeBenchmarkHarness(
                    mapOf(
                        ComparativeBenchmarkArm.ITERATIVE_SINGLE_AGENT to
                            ComparativeArmExecutor { _, assignment -> result(assignment) }
                    )
                )
            }
            val harness = ComparativeBenchmarkHarness(
                ComparativeBenchmarkArm.entries.associateWith {
                    ComparativeArmExecutor { _, assignment ->
                        result(
                            assignment.copy(taskId = "other-task").withComputedHash()
                        )
                    }
                }
            )
            assertFailsWith<IllegalArgumentException> {
                harness.run(plan)
            }
        }

    private fun plan(): ComparativeExperimentPlan = ComparativeExperimentPlan(
        experimentId = "harness-experiment",
        experimentVersion = 1,
        benchmarkId = "tier0",
        benchmarkVersion = 1,
        tasks = listOf(ComparativeTaskCommitment("task-a", "task-a-hash")),
        seeds = listOf(1L, 2L),
        arms = listOf(
            ComparativeArmDefinition(
                ComparativeBenchmarkArm.ITERATIVE_SINGLE_AGENT,
                0,
                0,
                "worker-family"
            ),
            ComparativeArmDefinition(
                ComparativeBenchmarkArm.ONE_GROUP_SOCIETY,
                1,
                1,
                "worker-family"
            ),
            ComparativeArmDefinition(
                ComparativeBenchmarkArm.FULL_SOCIETY,
                2,
                2,
                "worker-family"
            )
        ),
        commitments = ComparativeInputCommitments(
            "sources",
            "tools",
            "atlas",
            "output",
            "worker-family"
        ),
        constraintPolicy = ComparativeConstraintPolicy(
            ComparativeConstraintMode.ISO_LATENCY,
            maximumLatencyMillis = 2_000L
        ),
        evaluator = ComparativeEvaluatorContract(
            "evaluator",
            1,
            "evaluator-hash",
            "evaluator-family",
            independent = true,
            calibrated = true
        ),
        artifactRoot = "comparison",
        createdAt = Instant.parse("2026-07-12T10:00:00Z")
    ).withComputedHash()

    private fun result(
        assignment: ComparativeExperimentAssignment
    ): ComparativeExperimentResult = ComparativeExperimentResult(
        resultId = "result-${assignment.assignmentId}",
        assignment = assignment,
        status = ComparativeCellStatus.SUCCEEDED,
        evaluation = ComparativeEvaluation(
            "evaluator-hash",
            mapOf("quality" to 0.8),
            0.8,
            1.0
        ),
        costUsd = 1.0,
        latencyMillis = 1_000L,
        finalDeliverableReferenceId = assignment.arm
            .takeUnless { it == ComparativeBenchmarkArm.ITERATIVE_SINGLE_AGENT }
            ?.let { "reference-${assignment.assignmentId}" },
        finalDeliverableReferenceHash = assignment.arm
            .takeUnless { it == ComparativeBenchmarkArm.ITERATIVE_SINGLE_AGENT }
            ?.let { "reference-hash-${assignment.assignmentId}" },
        observedCommitments = plan().commitments,
        observedWorkerGroupCount = when (assignment.arm) {
            ComparativeBenchmarkArm.ITERATIVE_SINGLE_AGENT -> 0
            ComparativeBenchmarkArm.ONE_GROUP_SOCIETY -> 1
            ComparativeBenchmarkArm.FULL_SOCIETY -> 2
        },
        observedGraderGroupCount = when (assignment.arm) {
            ComparativeBenchmarkArm.ITERATIVE_SINGLE_AGENT -> 0
            ComparativeBenchmarkArm.ONE_GROUP_SOCIETY -> 1
            ComparativeBenchmarkArm.FULL_SOCIETY -> 2
        },
        completedAt = Instant.parse("2026-07-12T10:30:00Z")
    ).withComputedHash()

    private class FixedClock(private val instant: Instant) : Clock {
        override fun now(): Instant = instant
    }
}
