@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.core.benchmark

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class ComparativeBenchmarkTest {
    @Test
    fun `plan requires multiple seeds every arm matched model family and independent evaluator`() {
        assertFailsWith<IllegalArgumentException> {
            plan(seeds = listOf(1L))
        }
        assertFailsWith<IllegalArgumentException> {
            plan(
                arms = requiredArms().filterNot {
                    it.arm == ComparativeBenchmarkArm.FULL_SOCIETY
                }
            )
        }
        assertFailsWith<IllegalArgumentException> {
            plan(
                arms = requiredArms().map {
                    if (it.arm == ComparativeBenchmarkArm.ONE_GROUP_SOCIETY) {
                        it.copy(workerModelFamily = "other-family")
                    } else {
                        it
                    }
                }
            )
        }
        assertFailsWith<IllegalArgumentException> {
            plan(
                evaluator = evaluator().copy(independent = false)
            )
        }
        assertFailsWith<IllegalArgumentException> {
            plan(
                evaluator = evaluator().copy(modelFamily = "worker-family")
            )
        }
    }

    @Test
    fun `constraint policies reject unknown iso cost and absent iso latency cap`() {
        assertFailsWith<IllegalArgumentException> {
            ComparativeConstraintPolicy(ComparativeConstraintMode.ISO_COST)
        }
        assertFailsWith<IllegalArgumentException> {
            ComparativeConstraintPolicy(ComparativeConstraintMode.ISO_LATENCY)
        }
    }

    @Test
    fun `assignment matrix is complete hash valid and namespace isolated`() {
        val plan = plan()
        val assignments = plan.assignments()

        assertEquals(12, assignments.size)
        assertTrue(assignments.all { it.hasValidHash() })
        assertEquals(12, assignments.map { it.artifactNamespace }.distinct().size)
        assertTrue(
            assignments.all {
                it.artifactNamespace.endsWith(it.arm.id) &&
                    "seed-${it.seed}" in it.artifactNamespace
            }
        )
    }

    @Test
    fun `validator rejects missing cells changed assignments unknown cost and exceeded latency`() {
        val costPlan = plan()
        val costResults = successfulResults(costPlan)
        ComparativeExperimentValidator.validate(costPlan, costResults)

        assertFailsWith<IllegalArgumentException> {
            ComparativeExperimentValidator.validate(costPlan, costResults.dropLast(1))
        }
        val changedAssignment = costResults.first().assignment.copy(
            commitmentsHash = "changed"
        ).withComputedHash()
        assertFailsWith<IllegalArgumentException> {
            ComparativeExperimentValidator.validate(
                costPlan,
                listOf(
                    costResults.first().copy(
                        assignment = changedAssignment,
                        resultHash = ""
                    ).withComputedHash()
                ) + costResults.drop(1)
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ComparativeExperimentValidator.validate(
                costPlan,
                listOf(
                    costResults.first().copy(
                        observedCommitments = costPlan.commitments.copy(atlasHash = "mutated"),
                        resultHash = ""
                    ).withComputedHash()
                ) + costResults.drop(1)
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ComparativeExperimentValidator.validate(
                costPlan,
                listOf(
                    costResults.first().copy(costUsd = null, resultHash = "").withComputedHash()
                ) + costResults.drop(1)
            )
        }

        val latencyPlan = plan(
            policy = ComparativeConstraintPolicy(
                mode = ComparativeConstraintMode.ISO_LATENCY,
                maximumLatencyMillis = 1_000L
            )
        )
        val latencyResults = successfulResults(latencyPlan)
        assertFailsWith<IllegalArgumentException> {
            ComparativeExperimentValidator.validate(
                latencyPlan,
                listOf(
                    latencyResults.first().copy(
                        latencyMillis = 1_001L,
                        resultHash = ""
                    ).withComputedHash()
                ) + latencyResults.drop(1)
            )
        }
    }

    @Test
    fun `validator rejects overlapping artifact namespaces`() {
        val assignments = plan().assignments()
        val overlapping = assignments[1].copy(
            artifactNamespace = assignments.first().artifactNamespace + "/child"
        ).withComputedHash()

        assertFailsWith<IllegalArgumentException> {
            ComparativeExperimentValidator.requireDisjointNamespaces(
                listOf(assignments.first(), overlapping)
            )
        }
    }

    @Test
    fun `paired report retains failures abstentions ties and confidence intervals`() {
        val plan = plan()
        val assignments = plan.assignments()
        val results = assignments.mapIndexed { index, assignment ->
            when {
                assignment.taskId == "task-b" &&
                    assignment.seed == 22L &&
                    assignment.arm == ComparativeBenchmarkArm.ONE_GROUP_SOCIETY ->
                    nonSuccessResult(
                        assignment,
                        ComparativeCellStatus.FAILED,
                        "runtime failed"
                    )
                assignment.taskId == "task-a" &&
                    assignment.seed == 11L &&
                    assignment.arm == ComparativeBenchmarkArm.FULL_SOCIETY ->
                    nonSuccessResult(
                        assignment,
                        ComparativeCellStatus.ABSTAINED,
                        "evaluator abstained"
                    )
                else -> successfulResult(
                    assignment = assignment,
                    quality = when (assignment.arm) {
                        ComparativeBenchmarkArm.ITERATIVE_SINGLE_AGENT -> 0.50
                        ComparativeBenchmarkArm.ONE_GROUP_SOCIETY -> 0.60
                        ComparativeBenchmarkArm.FULL_SOCIETY -> if (index % 2 == 0) 0.50 else 0.70
                    }
                )
            }
        }

        val report = ComparativeExperimentReportBuilder.build(
            plan,
            results,
            Instant.parse("2026-07-12T10:00:00Z")
        )

        assertTrue(report.hasValidHash())
        assertEquals(8, report.pairedCells.size)
        assertEquals(
            1,
            report.pairedCells.count {
                it.status == PairedCellStatus.COMPARATOR_NON_SUCCESS &&
                    it.comparatorStatus == ComparativeCellStatus.FAILED
            }
        )
        assertEquals(
            1,
            report.pairedCells.count {
                it.status == PairedCellStatus.COMPARATOR_NON_SUCCESS &&
                    it.comparatorStatus == ComparativeCellStatus.ABSTAINED
            }
        )
        val oneGroupMetrics = report.armMetrics.single {
            it.arm == ComparativeBenchmarkArm.ONE_GROUP_SOCIETY
        }
        assertEquals(4, oneGroupMetrics.cellCount)
        assertEquals(3, oneGroupMetrics.successCount)
        assertEquals(1, oneGroupMetrics.failureCount)
        val oneGroupConfidence = report.confidenceByComparator.getValue(
            ComparativeBenchmarkArm.ONE_GROUP_SOCIETY
        )
        assertEquals(3, oneGroupConfidence.sampleCount)
        assertEquals(0.1, requireNotNull(oneGroupConfidence.meanDifference), 1e-9)
        val meanDifference = requireNotNull(oneGroupConfidence.meanDifference)
        assertTrue(requireNotNull(oneGroupConfidence.lower95) <= meanDifference)
        assertTrue(requireNotNull(oneGroupConfidence.upper95) >= meanDifference)
        assertNull(
            report.pairedCells.single {
                it.taskId == "task-b" &&
                    it.seed == 22L &&
                    it.comparatorArm == ComparativeBenchmarkArm.ONE_GROUP_SOCIETY
            }.qualityDifference
        )
    }

    private fun plan(
        seeds: List<Long> = listOf(11L, 22L),
        arms: List<ComparativeArmDefinition> = requiredArms(),
        evaluator: ComparativeEvaluatorContract = evaluator(),
        policy: ComparativeConstraintPolicy = ComparativeConstraintPolicy(
            mode = ComparativeConstraintMode.ISO_COST,
            maximumCostUsd = 10.0
        )
    ): ComparativeExperimentPlan = ComparativeExperimentPlan(
        experimentId = "experiment-1",
        experimentVersion = 1,
        benchmarkId = "tier0",
        benchmarkVersion = 1,
        tasks = listOf(
            ComparativeTaskCommitment("task-a", "task-a-hash"),
            ComparativeTaskCommitment("task-b", "task-b-hash")
        ),
        seeds = seeds,
        arms = arms,
        commitments = ComparativeInputCommitments(
            sourceBundleHash = "sources-hash",
            toolBundleHash = "tools-hash",
            atlasHash = "atlas-hash",
            outputContractHash = "output-hash",
            workerModelFamily = "worker-family"
        ),
        constraintPolicy = policy,
        evaluator = evaluator,
        artifactRoot = "comparison",
        createdAt = Instant.parse("2026-07-12T09:00:00Z")
    ).withComputedHash()

    private fun requiredArms(): List<ComparativeArmDefinition> = listOf(
        ComparativeArmDefinition(
            ComparativeBenchmarkArm.ITERATIVE_SINGLE_AGENT,
            workerGroupCount = 0,
            graderGroupCount = 0,
            workerModelFamily = "worker-family"
        ),
        ComparativeArmDefinition(
            ComparativeBenchmarkArm.ONE_GROUP_SOCIETY,
            workerGroupCount = 1,
            graderGroupCount = 1,
            workerModelFamily = "worker-family"
        ),
        ComparativeArmDefinition(
            ComparativeBenchmarkArm.FULL_SOCIETY,
            workerGroupCount = 2,
            graderGroupCount = 2,
            workerModelFamily = "worker-family"
        )
    )

    private fun evaluator() = ComparativeEvaluatorContract(
        evaluatorId = "independent-evaluator",
        evaluatorVersion = 1,
        evaluatorContractHash = "evaluator-hash",
        modelFamily = "evaluator-family",
        independent = true,
        calibrated = true
    )

    private fun successfulResults(
        plan: ComparativeExperimentPlan
    ): List<ComparativeExperimentResult> =
        plan.assignments().map { assignment -> successfulResult(assignment, 0.75) }

    private fun successfulResult(
        assignment: ComparativeExperimentAssignment,
        quality: Double
    ): ComparativeExperimentResult = ComparativeExperimentResult(
        resultId = "result-${assignment.assignmentId}",
        assignment = assignment,
        status = ComparativeCellStatus.SUCCEEDED,
        evaluation = ComparativeEvaluation(
            evaluatorContractHash = "evaluator-hash",
            scoreComponents = mapOf("quality" to quality),
            overallScore = quality,
            maximumScore = 1.0
        ),
        costUsd = 2.0,
        latencyMillis = 900L,
        finalDeliverableReferenceId = assignment.arm
            .takeUnless { it == ComparativeBenchmarkArm.ITERATIVE_SINGLE_AGENT }
            ?.let { "final-reference-${assignment.assignmentId}" },
        finalDeliverableReferenceHash = assignment.arm
            .takeUnless { it == ComparativeBenchmarkArm.ITERATIVE_SINGLE_AGENT }
            ?.let { "final-reference-hash-${assignment.assignmentId}" },
        observedCommitments = commitments(),
        observedWorkerGroupCount = workerGroups(assignment.arm),
        observedGraderGroupCount = graderGroups(assignment.arm),
        completedAt = Instant.parse("2026-07-12T09:30:00Z")
    ).withComputedHash()

    private fun nonSuccessResult(
        assignment: ComparativeExperimentAssignment,
        status: ComparativeCellStatus,
        reason: String
    ): ComparativeExperimentResult = ComparativeExperimentResult(
        resultId = "result-${assignment.assignmentId}",
        assignment = assignment,
        status = status,
        costUsd = 2.0,
        latencyMillis = 900L,
        failureReason = reason,
        observedCommitments = commitments(),
        observedWorkerGroupCount = workerGroups(assignment.arm),
        observedGraderGroupCount = graderGroups(assignment.arm),
        completedAt = Instant.parse("2026-07-12T09:30:00Z")
    ).withComputedHash()

    private fun commitments() = ComparativeInputCommitments(
        sourceBundleHash = "sources-hash",
        toolBundleHash = "tools-hash",
        atlasHash = "atlas-hash",
        outputContractHash = "output-hash",
        workerModelFamily = "worker-family"
    )

    private fun workerGroups(arm: ComparativeBenchmarkArm): Int = when (arm) {
        ComparativeBenchmarkArm.ITERATIVE_SINGLE_AGENT -> 0
        ComparativeBenchmarkArm.ONE_GROUP_SOCIETY -> 1
        ComparativeBenchmarkArm.FULL_SOCIETY -> 2
    }

    private fun graderGroups(arm: ComparativeBenchmarkArm): Int = when (arm) {
        ComparativeBenchmarkArm.ITERATIVE_SINGLE_AGENT -> 0
        ComparativeBenchmarkArm.ONE_GROUP_SOCIETY -> 1
        ComparativeBenchmarkArm.FULL_SOCIETY -> 2
    }
}
