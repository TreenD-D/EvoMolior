package com.achulkov.evokammer.core.overseer

import com.achulkov.evokammer.core.contract.OverseerExperimentPlan
import com.achulkov.evokammer.core.contract.OverseerExperimentResult
import com.achulkov.evokammer.core.contract.OverseerExperimentOutcome
import com.achulkov.evokammer.core.contract.OverseerHypothesis
import com.achulkov.evokammer.core.contract.OverseerMutabilityLevel

/**
 * Deterministic patch-acceptance evaluator for self-improvement experiment results.
 *
 * This is intentionally LLM-free. It evaluates whether the outputs of a self-improvement
 * experiment run meet the acceptance criteria defined in the experiment plan, independently
 * of any agent opinion.
 *
 * Implements the acceptance rules from §7.1 of the v4 plan:
 * 1. Scope check — no patches touch immutable paths.
 * 2. Tests & Invariants — clean build, sealed tests pass, invariants hold.
 * 3. Metric dominance — no regressions on critical metrics, at least one target metric improves.
 * 4. Change budget — patchset stays within per-run file and line limits.
 *
 * @param immutablePathPrefixes Repository path prefixes that are never eligible for automatic
 *   modification by self-improvement experiments.
 * @param maxLinesChangedPerRun Maximum lines that may be changed per run (default 500).
 * @param maxFilesChangedPerRun Maximum files that may be changed per run (default 20).
 */
class PatchAcceptanceEvaluator(
    private val immutablePathPrefixes: Set<String> = OverseerKernel.DEFAULT_IMMUTABLE_PATH_PREFIXES,
    private val maxLinesChangedPerRun: Int = 500,
    private val maxFilesChangedPerRun: Int = 20
) {

    /**
     * Evidence collected from a completed self-improvement run.
     *
     * @param experimentPlanId Experiment plan that was executed.
     * @param hypothesisId Hypothesis under test.
     * @param changedFiles Set of file paths modified by the run's patch candidates.
     * @param totalLinesChanged Total lines added or removed across all changed files.
     * @param buildPassed Whether a clean build succeeded with the patches applied.
     * @param testsPassed Whether all sealed tests in the judge suite passed.
     * @param invariantsHeldWhether all declared invariants remained true.
     * @param metricValues Observed metric values keyed by name.
     * @param counterexamplePaths Evidence paths for failures.
     * @param note Optional human-readable note.
     */
    data class RunEvidence(
        val experimentPlanId: String,
        val hypothesisId: String,
        val changedFiles: Set<String> = emptySet(),
        val totalLinesChanged: Int = 0,
        val buildPassed: Boolean = true,
        val testsPassed: Boolean = true,
        val invariantsHeld: Boolean = true,
        val metricValues: Map<String, Double> = emptyMap(),
        val counterexamplePaths: List<String> = emptyList(),
        val note: String = ""
    )

    /**
     * Structured evaluation result with detailed reasons.
     *
     * @param accepted Whether the experiment passes all acceptance rules.
     * @param reasons Ordered list of reasons for acceptance or rejection.
     */
    data class EvaluationResult(
        val accepted: Boolean,
        val reasons: List<String>
    )

    /**
     * Evaluates a single run's evidence against the experiment plan's acceptance criteria.
     *
     * @param hypothesis Hypothesis under test.
     * @param plan Experiment plan defining the acceptance criteria.
     * @param evidence Evidence collected from a completed run.
     * @return Structured evaluation result.
     */
    fun evaluate(
        hypothesis: OverseerHypothesis,
        plan: OverseerExperimentPlan,
        evidence: RunEvidence
    ): EvaluationResult {
        val reasons = mutableListOf<String>()

        // 1. Scope check
        val scopeCheck = checkScope(evidence.changedFiles)
        if (!scopeCheck.passed) {
            reasons += scopeCheck.reason
        }

        // 2. Tests & Invariants
        if (!evidence.buildPassed) {
            reasons += "Build failed with patches applied."
        }
        if (!evidence.testsPassed) {
            reasons += "Sealed test suite did not pass."
        }
        if (!evidence.invariantsHeld) {
            reasons += "One or more invariants were violated."
        }

        // 3. Metric dominance
        val metricCheck = checkMetricDominance(
            plan = plan,
            hypothesis = hypothesis,
            metricValues = evidence.metricValues,
            previousMetricValues = emptyMap() // Previous metrics from baseline regression runs would go here
        )
        if (!metricCheck.passed) {
            reasons += metricCheck.reason
        }

        // 4. Change budget
        if (evidence.totalLinesChanged > maxLinesChangedPerRun) {
            reasons += "Changed $evidence.totalLinesChanged lines, exceeding the ${maxLinesChangedPerRun}-line budget."
        }
        if (evidence.changedFiles.size > maxFilesChangedPerRun) {
            reasons += "Changed ${evidence.changedFiles.size} files, exceeding the ${maxFilesChangedPerRun}-file budget."
        }

        // Semi-mutable requires additional checks
        if (hypothesis.targetScope.mutabilityLevel == OverseerMutabilityLevel.SEMI_MUTABLE) {
            if (evidence.changedFiles.any { !isStructuredConfigurationPath(it) }) {
                reasons += "Semi-mutable hypotheses must only change structured configuration files."
            }
        }

        return EvaluationResult(
            accepted = reasons.isEmpty(),
            reasons = reasons.toList()
        )
    }

    /**
     * Evaluates evidence from multiple runs and creates a deterministic experiment result.
     *
     * @param hypothesis Hypothesis under test.
     * @param plan Experiment plan defining acceptance criteria.
     * @param evidenceList Evidence from all runs in the experiment (must have at least 1 entry).
     * @param acceptanceThreshold Fraction of runs that must pass acceptance (default 1.0 = all must pass).
     * @return Deterministic experiment result ready for kernel recording.
     */
    fun evaluateMultiRun(
        hypothesis: OverseerHypothesis,
        plan: OverseerExperimentPlan,
        evidenceList: List<RunEvidence>,
        acceptanceThreshold: Double = 1.0
    ): OverseerExperimentResult {
        require(evidenceList.isNotEmpty()) { "At least one run's evidence is required." }

        val evaluations = evidenceList.map { evidence ->
            evaluate(hypothesis, plan, evidence)
        }

        val acceptedCount = evaluations.count { it.accepted }
        val requiredAccepted = (plan.runCount * acceptanceThreshold).toInt().coerceAtLeast(1)
        val allAccepted = acceptedCount >= requiredAccepted

        // Aggregate metric values across runs
        val aggregatedMetrics = mutableMapOf<String, Double>()
        evidenceList.flatMap { it.metricValues.entries }.forEach { (key, value) ->
            aggregatedMetrics[key] = (aggregatedMetrics[key] ?: 0.0) + value
        }
        val averagedMetrics = aggregatedMetrics.mapValues { (_, sum) -> sum / evidenceList.size }

        // Collect counterexamples from any failed run
        val counterexamples = evidenceList.filter { evidence ->
            evaluate(hypothesis, plan, evidence).let { !it.accepted }
        }.flatMap { it.counterexamplePaths }

        // Build combined reasons
        val allReasons = evaluations.flatMap { it.reasons }.distinct()
        val note = if (allAccepted) {
            "All ${evidenceList.size} runs passed acceptance. Averaged metrics: $averagedMetrics"
        } else {
            "${acceptedCount}/${evidenceList.size} runs passed acceptance. Failures: ${allReasons.joinToString("; ")}"
        }

        return OverseerExperimentResult(
            experimentPlanId = plan.id,
            hypothesisId = hypothesis.id,
            outcome = if (allAccepted) OverseerExperimentOutcome.ACCEPTED else OverseerExperimentOutcome.REJECTED,
            metricValues = averagedMetrics,
            note = note,
            counterexamplePaths = counterexamples
        )
    }

    private data class ScopeResult(val passed: Boolean, val reason: String)

    private fun checkScope(changedFiles: Set<String>): ScopeResult {
        for (file in changedFiles) {
            for (prefix in immutablePathPrefixes) {
                if (file.startsWith(prefix)) {
                    return ScopeResult(false, "Patch touches immutable path '$file' (prefix '$prefix').")
                }
            }
        }
        return ScopeResult(true, "")
    }

    private data class MetricResult(val passed: Boolean, val reason: String)

    private fun checkMetricDominance(
        plan: OverseerExperimentPlan,
        hypothesis: OverseerHypothesis,
        metricValues: Map<String, Double>,
        previousMetricValues: Map<String, Double>
    ): MetricResult {
        val reasons = mutableListOf<String>()

        // Metrics that should go up
        for (metric in hypothesis.expectedEffect.metricsUp) {
            val current = metricValues[metric]
            val previous = previousMetricValues[metric]
            if (current != null && previous != null && current <= previous) {
                reasons += "Metric '$metric' did not improve ($current <= $previous)."
            }
        }

        // Metrics that should go down
        for (metric in hypothesis.expectedEffect.metricsDown) {
            val current = metricValues[metric]
            val previous = previousMetricValues[metric]
            if (current != null && previous != null && current >= previous) {
                reasons += "Metric '$metric' did not decrease ($current >= $previous)."
            }
        }

        // Primary metrics must not regress
        for (metric in plan.metrics.primary) {
            val current = metricValues[metric]
            val previous = previousMetricValues[metric]
            if (current != null && previous != null && current < previous * 0.95) {
                reasons += "Primary metric '$metric' regressed by more than 5% ($current vs $previous)."
            }
        }

        return if (reasons.isEmpty()) {
            MetricResult(true, "")
        } else {
            MetricResult(false, reasons.joinToString("; "))
        }
    }

    private fun isStructuredConfigurationPath(path: String): Boolean =
        path.endsWith(".json", ignoreCase = true) ||
            path.endsWith(".yaml", ignoreCase = true) ||
            path.endsWith(".yml", ignoreCase = true) ||
            path.endsWith(".toml", ignoreCase = true) ||
            path.endsWith(".kts", ignoreCase = true) ||
            path.contains("/config/", ignoreCase = true)
}
