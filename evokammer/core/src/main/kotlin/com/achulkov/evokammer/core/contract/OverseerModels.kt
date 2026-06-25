package com.achulkov.evokammer.core.contract

import kotlinx.serialization.Serializable

/**
 * Structured hypothesis proposal used by the recursive self-improvement kernel.
 *
 * @param id Stable hypothesis identifier.
 * @param title Human-readable title.
 * @param origin Source classification and evidence links.
 * @param targetScope Paths and mutability classification for the proposed change.
 * @param rationale Why the hypothesis is worth testing.
 * @param expectedEffect Expected metrics and invariants.
 * @param experimentPlanId Optional linked experiment plan identifier.
 * @param status Current lifecycle state.
 * @param notes Free-form operator notes.
 */
@Serializable
data class OverseerHypothesis(
    val id: String,
    val title: String,
    val origin: OverseerOrigin,
    val targetScope: OverseerTargetScope,
    val rationale: String,
    val expectedEffect: OverseerExpectedEffect,
    val experimentPlanId: String? = null,
    val status: OverseerHypothesisStatus = OverseerHypothesisStatus.IDEA,
    val notes: String = ""
)

/**
 * Evidence source classification for a hypothesis.
 *
 * @param type Origin category.
 * @param sources Supporting references such as files, logs, or papers.
 */
@Serializable
data class OverseerOrigin(
    val type: OverseerOriginType,
    val sources: List<String> = emptyList()
)

/**
 * Origin category for a hypothesis.
 */
@Serializable
enum class OverseerOriginType {
    INTERNAL_METRICS,
    INTERNAL_LOGS,
    EXTERNAL_PAPER,
    HYBRID
}

/**
 * Targeted paths and mutability level for a hypothesis.
 *
 * @param paths Repository paths or configuration files the hypothesis may touch.
 * @param mutabilityLevel Whether the change targets the semi-mutable governance layer or the
 *   fully mutable harness layer.
 */
@Serializable
data class OverseerTargetScope(
    val paths: List<String>,
    val mutabilityLevel: OverseerMutabilityLevel
)

/**
 * Mutability class for a proposed change.
 */
@Serializable
enum class OverseerMutabilityLevel {
    SEMI_MUTABLE,
    FULLY_MUTABLE
}

/**
 * Expected measurable effect of a hypothesis.
 *
 * @param metricsUp Metrics expected to improve.
 * @param metricsDown Metrics expected to decrease.
 * @param invariantsHold Invariants that must remain true.
 */
@Serializable
data class OverseerExpectedEffect(
    val metricsUp: List<String> = emptyList(),
    val metricsDown: List<String> = emptyList(),
    val invariantsHold: List<String> = emptyList()
)

/**
 * Lifecycle state of a hypothesis.
 */
@Serializable
enum class OverseerHypothesisStatus {
    IDEA,
    FORMALIZED,
    UNDER_EXPERIMENT,
    ACCEPTED,
    INTEGRATED,
    REJECTED,
    DEAD_END
}

/**
 * Deterministic experiment plan bound to a hypothesis.
 *
 * @param id Stable experiment identifier.
 * @param hypothesisId Hypothesis being tested.
 * @param runCount Number of runs to execute.
 * @param generationsPerRun Number of generations per run.
 * @param variationSpec Declarative changes to apply during the experiment.
 * @param metrics Metrics the kernel should track.
 * @param acceptanceCriteria Boolean predicates that define success.
 * @param maxCost Cost ceiling for the experiment.
 */
@Serializable
data class OverseerExperimentPlan(
    val id: String,
    val hypothesisId: String,
    val runCount: Int,
    val generationsPerRun: Int,
    val variationSpec: OverseerVariationSpec,
    val metrics: OverseerExperimentMetrics,
    val acceptanceCriteria: OverseerAcceptanceCriteria,
    val maxCost: Double
)

/**
 * Structured outcome of an Overseer experiment execution.
 *
 * @param experimentPlanId Experiment plan that was executed.
 * @param hypothesisId Hypothesis evaluated by the experiment.
 * @param outcome Deterministic verdict for the experiment.
 * @param metricValues Observed metric values keyed by metric name.
 * @param note Optional human-readable outcome note.
 * @param counterexamplePaths Evidence paths for a rejected experiment that proved a failure mode.
 * @param recordedAtMs Epoch milliseconds when the outcome was recorded.
 */
@Serializable
data class OverseerExperimentResult(
    val experimentPlanId: String,
    val hypothesisId: String,
    val outcome: OverseerExperimentOutcome,
    val metricValues: Map<String, Double> = emptyMap(),
    val note: String = "",
    val counterexamplePaths: List<String> = emptyList(),
    val recordedAtMs: Long = System.currentTimeMillis()
)

/**
 * Deterministic experiment verdict.
 */
@Serializable
enum class OverseerExperimentOutcome {
    ACCEPTED,
    REJECTED
}

/**
 * Deterministic launch intent for a registered experiment plan.
 *
 * The schedule is intentionally pure and restart-safe: it captures the derived run IDs and
 * the generation budget for each run, but it does not pretend the runs have already started.
 *
 * @param experimentPlanId Experiment plan being scheduled.
 * @param hypothesisId Hypothesis associated with the experiment plan.
 * @param runCount Number of runs queued for launch.
 * @param generationsPerRun Generation budget assigned to each queued run.
 * @param scheduledRunIds Stable run identifiers derived from the plan ID and run index.
 */
@Serializable
data class OverseerExperimentSchedule(
    val experimentPlanId: String,
    val hypothesisId: String,
    val runCount: Int,
    val generationsPerRun: Int,
    val scheduledRunIds: List<String>,
    val launchedAtMs: Long? = null
)

/**
 * Deterministic history entry for an experiment plan's lifecycle.
 *
 * @param experimentPlanId Experiment plan identifier.
 * @param hypothesisId Hypothesis identifier associated with the plan.
 * @param actionType Lifecycle action that produced the entry.
 * @param scheduledRunId Scheduled run identifier, if the action touched a run launch.
 * @param note Optional human-readable note.
 * @param recordedAtMs Epoch milliseconds when the action was recorded.
 */
@Serializable
data class OverseerExperimentPlanHistoryEntry(
    val experimentPlanId: String,
    val hypothesisId: String,
    val actionType: OverseerExperimentPlanHistoryActionType,
    val scheduledRunId: String? = null,
    val note: String = "",
    val recordedAtMs: Long = System.currentTimeMillis()
)

/**
 * Deterministic lifecycle action for experiment-plan history.
 */
@Serializable
enum class OverseerExperimentPlanHistoryActionType {
    REGISTERED,
    SCHEDULED,
    LAUNCHED,
    RESULT_RECORDED
}

/**
 * Deterministic history entry for a dead-end record.
 *
 * @param hypothesisId Hypothesis that reached the dead end.
 * @param reason Human-readable failure reason.
 * @param counterexamplePaths Evidence paths backing the failure.
 * @param source Provenance of the dead-end record.
 * @param recordedAtMs Epoch milliseconds when the record was captured.
 */
@Serializable
data class OverseerDeadEndHistoryEntry(
    val hypothesisId: String,
    val reason: String,
    val counterexamplePaths: List<String> = emptyList(),
    val source: OverseerDeadEndHistorySource,
    val recordedAtMs: Long = System.currentTimeMillis()
)

/**
 * Source category for dead-end history.
 */
@Serializable
enum class OverseerDeadEndHistorySource {
    DIRECT_RECORD,
    EXPERIMENT_RESULT
}

/**
 * Declarative variation payload for an experiment plan.
 *
 * @param configChanges Structured config changes.
 * @param toolOrPromptChanges Tool or prompt changes.
 */
@Serializable
data class OverseerVariationSpec(
    val configChanges: List<String> = emptyList(),
    val toolOrPromptChanges: List<String> = emptyList()
)

/**
 * Metric sets tracked for an experiment plan.
 *
 * @param primary Primary metrics.
 * @param secondary Secondary metrics.
 */
@Serializable
data class OverseerExperimentMetrics(
    val primary: List<String> = emptyList(),
    val secondary: List<String> = emptyList()
)

/**
 * Acceptance rules for an experiment plan.
 *
 * @param predicates Boolean expressions the kernel evaluates externally.
 */
@Serializable
data class OverseerAcceptanceCriteria(
    val predicates: List<String> = emptyList()
)

/**
 * Dead-end record used to prevent naïve re-proposal of a failed hypothesis.
 *
 * @param hypothesisId Hypothesis that failed.
 * @param reason Short failure summary.
 * @param counterexamplePaths Evidence paths showing the failure mode.
 * @param recordedAtMs Epoch milliseconds when the dead end was recorded.
 */
@Serializable
data class OverseerDeadEnd(
    val hypothesisId: String,
    val reason: String,
    val counterexamplePaths: List<String> = emptyList(),
    val recordedAtMs: Long = System.currentTimeMillis()
)

/**
 * Immutable snapshot of the Overseer kernel state.
 *
 * @param hypotheses All registered hypotheses by identifier.
 * @param experimentPlans All registered experiment plans by identifier.
 * @param deadEnds All dead-end records by hypothesis identifier.
 */
@Serializable
data class OverseerKernelState(
    val hypotheses: Map<String, OverseerHypothesis> = emptyMap(),
    val experimentPlans: Map<String, OverseerExperimentPlan> = emptyMap(),
    val experimentResults: Map<String, OverseerExperimentResult> = emptyMap(),
    val experimentSchedules: Map<String, OverseerExperimentSchedule> = emptyMap(),
    val deadEnds: Map<String, OverseerDeadEnd> = emptyMap(),
    val experimentPlanHistory: Map<String, List<OverseerExperimentPlanHistoryEntry>> = emptyMap(),
    val deadEndHistory: Map<String, List<OverseerDeadEndHistoryEntry>> = emptyMap()
)

/**
 * Forget-gate policy for the recursive self-improvement loop.
 *
 * Terminal entries that have stayed stale beyond [terminalRetentionMs] are removed from the
 * active kernel snapshot, while their history remains archived in the audit trail.
 *
 * @param terminalRetentionMs How long terminal hypotheses may stay active before culling.
 * @param maxRetainedTerminalHypotheses Hard cap for the number of terminal hypotheses to keep.
 */
@Serializable
data class OverseerKernelRetentionPolicy(
    val terminalRetentionMs: Long = 7L * 24L * 60L * 60L * 1000L,
    val maxRetainedTerminalHypotheses: Int = 12
)
