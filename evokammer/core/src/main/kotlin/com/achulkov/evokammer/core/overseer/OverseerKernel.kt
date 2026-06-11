package com.achulkov.evokammer.core.overseer

import com.achulkov.evokammer.core.contract.OverseerDeadEnd
import com.achulkov.evokammer.core.contract.OverseerDeadEndHistoryEntry
import com.achulkov.evokammer.core.contract.OverseerDeadEndHistorySource
import com.achulkov.evokammer.core.contract.OverseerExperimentOutcome
import com.achulkov.evokammer.core.contract.OverseerExperimentPlan
import com.achulkov.evokammer.core.contract.OverseerExperimentPlanHistoryActionType
import com.achulkov.evokammer.core.contract.OverseerExperimentPlanHistoryEntry
import com.achulkov.evokammer.core.contract.OverseerExperimentPlanEligibility
import com.achulkov.evokammer.core.contract.OverseerExperimentResult
import com.achulkov.evokammer.core.contract.OverseerExperimentSchedule
import com.achulkov.evokammer.core.contract.OverseerHypothesis
import com.achulkov.evokammer.core.contract.OverseerHypothesisStatus
import com.achulkov.evokammer.core.contract.OverseerKernelEligibility
import com.achulkov.evokammer.core.contract.OverseerKernelAuditActionType
import com.achulkov.evokammer.core.contract.OverseerKernelState
import com.achulkov.evokammer.core.contract.OverseerMutabilityLevel

/**
 * Deterministic rule engine for recursive self-improvement proposals.
 *
 * The kernel is intentionally LLM-free. It only validates structured hypotheses and updates
 * immutable state snapshots.
 *
 * @param immutablePathPrefixes Repository path prefixes that are never eligible for automatic
 *   modification by self-improvement experiments.
 */
class OverseerKernel(
    private val immutablePathPrefixes: Set<String> = DEFAULT_IMMUTABLE_PATH_PREFIXES
) {
    /**
     * Formalizes a hypothesis into the kernel state after validating its scope.
     *
     * @param state Current kernel snapshot.
     * @param hypothesis Hypothesis to register.
     * @return Updated kernel snapshot.
     */
    fun formalizeHypothesis(
        state: OverseerKernelState,
        hypothesis: OverseerHypothesis
    ): OverseerKernelState {
        validateHypothesisEligibility(hypothesis).also { eligibility ->
            require(eligibility.allowed) {
                eligibility.reasons.firstOrNull() ?: "Hypothesis is not eligible for formalization."
            }
        }
        val nextHypothesis = hypothesis.copy(status = OverseerHypothesisStatus.FORMALIZED)
        return state.copy(
            hypotheses = state.hypotheses + (nextHypothesis.id to nextHypothesis),
            deadEnds = state.deadEnds - nextHypothesis.id
        )
    }

    /**
     * Registers an experiment plan for an existing hypothesis.
     *
     * @param state Current kernel snapshot.
     * @param experimentPlan Experiment plan to store.
     * @return Updated kernel snapshot.
     */
    fun registerExperimentPlan(
        state: OverseerKernelState,
        experimentPlan: OverseerExperimentPlan
    ): OverseerKernelState {
        validateExperimentPlanEligibility(state, experimentPlan).also { eligibility ->
            require(eligibility.allowed) {
                eligibility.reasons.firstOrNull() ?: "Experiment plan is not eligible for registration."
            }
        }
        return state.copy(
            hypotheses = state.hypotheses + (
                experimentPlan.hypothesisId to state.hypotheses.getValue(experimentPlan.hypothesisId).copy(
                    experimentPlanId = experimentPlan.id,
                    status = OverseerHypothesisStatus.UNDER_EXPERIMENT
                )
            ),
            experimentPlans = state.experimentPlans + (experimentPlan.id to experimentPlan),
            experimentPlanHistory = state.experimentPlanHistory.appendExperimentPlanHistory(
                plan = experimentPlan,
                actionType = OverseerExperimentPlanHistoryActionType.REGISTERED
            )
        )
    }

    /**
     * Schedules all eligible experiment plans as deterministic launch intents.
     *
     * @param state Current kernel snapshot.
     * @return Updated kernel snapshot.
     */
    fun scheduleExperimentPlans(state: OverseerKernelState): OverseerKernelState {
        val scheduledPlans = state.experimentPlans.values
            .sortedBy { it.id }
            .filter { state.experimentSchedules[it.id] == null }
            .filter { plan ->
                val hypothesis = state.hypotheses[plan.hypothesisId]
                hypothesis != null &&
                    hypothesis.status in setOf(OverseerHypothesisStatus.FORMALIZED, OverseerHypothesisStatus.UNDER_EXPERIMENT) &&
                    validateExperimentPlanEligibility(state, plan).allowed
            }
            .associate { plan -> plan.id to plan.toSchedule() }

        return if (scheduledPlans.isEmpty()) {
            state
        } else {
            state.copy(
                experimentSchedules = state.experimentSchedules + scheduledPlans,
                experimentPlanHistory = state.experimentPlanHistory.appendExperimentPlanHistoryFromSchedules(
                    schedules = scheduledPlans.values,
                    actionType = OverseerExperimentPlanHistoryActionType.SCHEDULED
                )
            )
        }
    }

    private fun OverseerExperimentPlan.toSchedule(): OverseerExperimentSchedule = OverseerExperimentSchedule(
        experimentPlanId = id,
        hypothesisId = hypothesisId,
        runCount = runCount,
        generationsPerRun = generationsPerRun,
        scheduledRunIds = (1..runCount).map { index -> "${id}-run-${index.toString().padStart(2, '0')}" }
    )

    /**
     * Marks a scheduled experiment run as launched exactly once.
     *
     * @param state Current kernel snapshot.
     * @param experimentPlanId Experiment plan whose schedule is being consumed.
     * @param scheduledRunId Scheduled run identifier to consume.
     * @param launchedAtMs Launch timestamp.
     * @return Updated kernel snapshot.
     */
    fun launchExperimentRun(
        state: OverseerKernelState,
        experimentPlanId: String,
        scheduledRunId: String,
        launchedAtMs: Long = System.currentTimeMillis()
    ): OverseerKernelState {
        val schedule = state.experimentSchedules[experimentPlanId]
            ?: error("Unknown experiment plan '$experimentPlanId'.")
        require(schedule.scheduledRunIds.contains(scheduledRunId)) {
            "Scheduled run '$scheduledRunId' is not registered for experiment plan '$experimentPlanId'."
        }
        require(schedule.launchedAtMs == null) {
            "Experiment plan '$experimentPlanId' has already been launched."
        }

        return state.copy(
            experimentSchedules = state.experimentSchedules + (
                experimentPlanId to schedule.copy(launchedAtMs = launchedAtMs)
            ),
            experimentPlanHistory = state.experimentPlanHistory.appendExperimentPlanHistory(
                plan = state.experimentPlans.getValue(experimentPlanId),
                actionType = OverseerExperimentPlanHistoryActionType.LAUNCHED,
                scheduledRunId = scheduledRunId,
                note = "Launched at $launchedAtMs"
            )
        )
    }

    /**
     * Records a dead end and marks the associated hypothesis as terminal.
     *
     * @param state Current kernel snapshot.
     * @param deadEnd Dead-end record to store.
     * @return Updated kernel snapshot.
     */
    fun recordDeadEnd(
        state: OverseerKernelState,
        deadEnd: OverseerDeadEnd
    ): OverseerKernelState {
        val updatedHypotheses = state.hypotheses[deadEnd.hypothesisId]?.let { hypothesis ->
            state.hypotheses + (deadEnd.hypothesisId to hypothesis.copy(status = OverseerHypothesisStatus.DEAD_END))
        } ?: state.hypotheses

        return state.copy(
            hypotheses = updatedHypotheses,
            deadEnds = state.deadEnds + (deadEnd.hypothesisId to deadEnd),
            deadEndHistory = state.deadEndHistory.appendDeadEndHistory(
                deadEnd = deadEnd,
                source = OverseerDeadEndHistorySource.DIRECT_RECORD
            )
        )
    }

    /**
     * Records the deterministic outcome of an experiment and updates the hypothesis lifecycle.
     *
     * @param state Current kernel snapshot.
     * @param experimentResult Experiment outcome to store.
     * @return Updated kernel snapshot.
     */
    fun recordExperimentResult(
        state: OverseerKernelState,
        experimentResult: OverseerExperimentResult
    ): OverseerKernelState {
        val plan = state.experimentPlans[experimentResult.experimentPlanId]
            ?: error("Unknown experiment plan '${experimentResult.experimentPlanId}'.")
        require(plan.hypothesisId == experimentResult.hypothesisId) {
            "Experiment result hypothesis '${experimentResult.hypothesisId}' does not match plan '${plan.id}'."
        }

        val nextStatus = when (experimentResult.outcome) {
            OverseerExperimentOutcome.ACCEPTED -> OverseerHypothesisStatus.ACCEPTED
            OverseerExperimentOutcome.REJECTED -> if (experimentResult.counterexamplePaths.isNotEmpty()) {
                OverseerHypothesisStatus.DEAD_END
            } else {
                OverseerHypothesisStatus.REJECTED
            }
        }

        val updatedHypotheses = state.hypotheses[experimentResult.hypothesisId]?.let { hypothesis ->
            state.hypotheses + (
                experimentResult.hypothesisId to hypothesis.copy(
                    status = nextStatus
                )
            )
        } ?: state.hypotheses

        val updatedDeadEnds = if (nextStatus == OverseerHypothesisStatus.DEAD_END) {
            state.deadEnds + (
                experimentResult.hypothesisId to OverseerDeadEnd(
                    hypothesisId = experimentResult.hypothesisId,
                    reason = experimentResult.note.ifBlank {
                        "Experiment '${experimentResult.experimentPlanId}' produced a terminal failure."
                    },
                    counterexamplePaths = experimentResult.counterexamplePaths,
                    recordedAtMs = experimentResult.recordedAtMs
                )
            )
        } else {
            state.deadEnds
        }

        val updatedDeadEndHistory = if (nextStatus == OverseerHypothesisStatus.DEAD_END) {
            state.deadEndHistory.appendDeadEndHistory(
                deadEnd = OverseerDeadEnd(
                    hypothesisId = experimentResult.hypothesisId,
                    reason = experimentResult.note.ifBlank {
                        "Experiment '${experimentResult.experimentPlanId}' produced a terminal failure."
                    },
                    counterexamplePaths = experimentResult.counterexamplePaths,
                    recordedAtMs = experimentResult.recordedAtMs
                ),
                source = OverseerDeadEndHistorySource.EXPERIMENT_RESULT
            )
        } else {
            state.deadEndHistory
        }

        return state.copy(
            hypotheses = updatedHypotheses,
            experimentResults = state.experimentResults + (experimentResult.experimentPlanId to experimentResult),
            deadEnds = updatedDeadEnds,
            experimentPlanHistory = state.experimentPlanHistory.appendExperimentPlanHistory(
                plan = plan,
                actionType = OverseerExperimentPlanHistoryActionType.RESULT_RECORDED,
                note = experimentResult.outcome.name
            ),
            deadEndHistory = updatedDeadEndHistory
        )
    }

    /**
     * Explains whether a hypothesis is eligible for formalization without mutating state.
     *
     * @param hypothesis Candidate hypothesis.
     * @return Structured allow/deny decision with reasons.
     */
    fun validateHypothesisEligibility(hypothesis: OverseerHypothesis): OverseerKernelEligibility {
        val reasons = mutableListOf<String>()

        if (hypothesis.id.isBlank()) reasons += "Hypothesis id must not be blank."
        if (hypothesis.title.isBlank()) reasons += "Hypothesis title must not be blank."
        if (hypothesis.rationale.isBlank()) reasons += "Hypothesis rationale must not be blank."
        if (hypothesis.targetScope.paths.isEmpty()) reasons += "Hypothesis must target at least one path."

        hypothesis.targetScope.paths.forEach { path ->
            if (path.isBlank()) {
                reasons += "Hypothesis target paths must not be blank."
            }
            if (immutablePathPrefixes.any { prefix -> path.startsWith(prefix) }) {
                reasons += "Hypothesis targets immutable path '$path'."
            }
        }

        if (hypothesis.targetScope.mutabilityLevel == OverseerMutabilityLevel.SEMI_MUTABLE &&
            hypothesis.targetScope.paths.isNotEmpty() &&
            hypothesis.targetScope.paths.any { !isStructuredConfigurationPath(it) }
        ) {
            reasons += "Semi-mutable hypotheses must target structured configuration files."
        }

        return OverseerKernelEligibility(
            allowed = reasons.isEmpty(),
            reasons = reasons.distinct()
        )
    }

    /**
     * Explains whether an experiment plan is eligible for registration without mutating state.
     *
     * @param state Current kernel snapshot used to resolve the referenced hypothesis.
     * @param experimentPlan Candidate experiment plan.
     * @return Structured allow/deny verdict with stable reasons.
     */
    fun validateExperimentPlanEligibility(
        state: OverseerKernelState,
        experimentPlan: OverseerExperimentPlan
    ): OverseerExperimentPlanEligibility {
        val reasons = mutableListOf<String>()

        if (experimentPlan.id.isBlank()) reasons += "Experiment plan id must not be blank."
        if (experimentPlan.hypothesisId.isBlank()) reasons += "Experiment plan hypothesis id must not be blank."
        if (experimentPlan.runCount <= 0) reasons += "Experiment plans must run at least once."
        if (experimentPlan.generationsPerRun <= 0) reasons += "Experiment plans must span at least one generation."
        if (experimentPlan.maxCost < 0.0) reasons += "Experiment plans cannot have negative cost."
        if (experimentPlan.metrics.primary.isEmpty()) reasons += "Experiment plans must declare at least one primary metric."
        if (experimentPlan.acceptanceCriteria.predicates.isEmpty()) {
            reasons += "Experiment plans must declare at least one acceptance predicate."
        }
        if (experimentPlan.variationSpec.configChanges.isEmpty() &&
            experimentPlan.variationSpec.toolOrPromptChanges.isEmpty()
        ) {
            reasons += "Experiment plans must declare at least one variation."
        }

        val hypothesis = state.hypotheses[experimentPlan.hypothesisId]
        if (hypothesis == null) {
            reasons += "Unknown hypothesis '${experimentPlan.hypothesisId}'."
        } else if (hypothesis.status == OverseerHypothesisStatus.DEAD_END ||
            hypothesis.status == OverseerHypothesisStatus.REJECTED ||
            hypothesis.status == OverseerHypothesisStatus.IDEA
        ) {
            reasons += "Hypothesis '${experimentPlan.hypothesisId}' must be formalized before experiment registration."
        }

        return OverseerExperimentPlanEligibility(
            allowed = reasons.isEmpty(),
            reasons = reasons.distinct()
        )
    }

    private fun isStructuredConfigurationPath(path: String): Boolean =
        path.endsWith(".json", ignoreCase = true) ||
            path.endsWith(".yaml", ignoreCase = true) ||
            path.endsWith(".yml", ignoreCase = true) ||
            path.endsWith(".toml", ignoreCase = true) ||
            path.endsWith(".kts", ignoreCase = true) ||
            path.contains("/config/", ignoreCase = true)

    private fun Map<String, List<OverseerExperimentPlanHistoryEntry>>.appendExperimentPlanHistory(
        plan: OverseerExperimentPlan,
        actionType: OverseerExperimentPlanHistoryActionType,
        scheduledRunId: String? = null,
        note: String = ""
    ): Map<String, List<OverseerExperimentPlanHistoryEntry>> = appendExperimentPlanHistory(
        plans = listOf(plan),
        actionType = actionType,
        scheduledRunId = scheduledRunId,
        note = note
    )

    private fun Map<String, List<OverseerExperimentPlanHistoryEntry>>.appendExperimentPlanHistory(
        plans: Collection<OverseerExperimentPlan>,
        actionType: OverseerExperimentPlanHistoryActionType,
        scheduledRunId: String? = null,
        note: String = ""
    ): Map<String, List<OverseerExperimentPlanHistoryEntry>> = plans.fold(this) { acc, plan ->
        acc + (
            plan.id to (
                acc[plan.id].orEmpty() + OverseerExperimentPlanHistoryEntry(
                    experimentPlanId = plan.id,
                    hypothesisId = plan.hypothesisId,
                    actionType = actionType,
                    scheduledRunId = scheduledRunId,
                    note = note
                )
            )
        )
    }

    private fun Map<String, List<OverseerExperimentPlanHistoryEntry>>.appendExperimentPlanHistoryFromSchedules(
        schedules: Collection<OverseerExperimentSchedule>,
        actionType: OverseerExperimentPlanHistoryActionType,
        scheduledRunId: String? = null,
        note: String = ""
    ): Map<String, List<OverseerExperimentPlanHistoryEntry>> = schedules.fold(this) { acc, schedule ->
        acc + (
            schedule.experimentPlanId to (
                acc[schedule.experimentPlanId].orEmpty() + OverseerExperimentPlanHistoryEntry(
                    experimentPlanId = schedule.experimentPlanId,
                    hypothesisId = schedule.hypothesisId,
                    actionType = actionType,
                    scheduledRunId = scheduledRunId,
                    note = note
                )
            )
        )
    }

    private fun Map<String, List<OverseerDeadEndHistoryEntry>>.appendDeadEndHistory(
        deadEnd: OverseerDeadEnd,
        source: OverseerDeadEndHistorySource
    ): Map<String, List<OverseerDeadEndHistoryEntry>> = this + (
        deadEnd.hypothesisId to (
            this[deadEnd.hypothesisId].orEmpty() + OverseerDeadEndHistoryEntry(
                hypothesisId = deadEnd.hypothesisId,
                reason = deadEnd.reason,
                counterexamplePaths = deadEnd.counterexamplePaths,
                source = source,
                recordedAtMs = deadEnd.recordedAtMs
            )
        )
    )

    companion object {
        val DEFAULT_IMMUTABLE_PATH_PREFIXES: Set<String> = setOf(
            "constitution/",
            "watchdog/",
            "scoring_engine/",
            "blind_tournament/",
            "contracts/",
            "evokammer/core/src/main/kotlin/com/achulkov/evokammer/core/config/",
            "evokammer/core/src/main/kotlin/com/achulkov/evokammer/core/config/ModelSelectionConfig.kt"
        )
    }
}
