@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.core.benchmark

import kotlinx.serialization.Serializable
import kotlin.time.Clock

/** Executes one exact matched experiment assignment. */
fun interface ComparativeArmExecutor {
    /**
     * Executes [assignment] under [plan].
     *
     * The returned result must preserve the exact assignment rather than reconstructing it.
     */
    suspend fun execute(
        plan: ComparativeExperimentPlan,
        assignment: ComparativeExperimentAssignment
    ): ComparativeExperimentResult
}

/** Complete result matrix and paired report produced by one harness run. */
@Serializable
data class ComparativeExperimentExecution(
    val plan: ComparativeExperimentPlan,
    val results: List<ComparativeExperimentResult>,
    val report: ComparativeExperimentReport
) {
    init {
        require(plan.hasValidHash() && report.hasValidHash()) {
            "Comparative execution requires hash-valid plan and report authority"
        }
        require(report.planHash == plan.planHash) {
            "Comparative report must bind the executed plan"
        }
    }
}

/** Sequential deterministic harness for the complete task, seed, and arm matrix. */
class ComparativeBenchmarkHarness(
    private val executors: Map<ComparativeBenchmarkArm, ComparativeArmExecutor>,
    private val clock: Clock = Clock.System
) {
    init {
        require(executors.keys.containsAll(ComparativeBenchmarkArm.entries)) {
            "Comparative harness requires an executor for every required arm"
        }
    }

    /** Executes every sealed assignment and returns a validated paired report. */
    suspend fun run(plan: ComparativeExperimentPlan): ComparativeExperimentExecution {
        require(plan.hasValidHash()) { "Comparative experiment plan hash is invalid" }
        val results = plan.assignments().map { assignment ->
            val result = requireNotNull(executors[assignment.arm]).execute(plan, assignment)
            require(result.assignment == assignment) {
                "Comparative arm executor returned a result for a different assignment"
            }
            require(result.hasValidHash()) {
                "Comparative arm executor returned an invalid result hash"
            }
            result
        }
        val report = ComparativeExperimentReportBuilder.build(
            plan = plan,
            results = results,
            generatedAt = clock.now()
        )
        return ComparativeExperimentExecution(plan, results, report)
    }
}
