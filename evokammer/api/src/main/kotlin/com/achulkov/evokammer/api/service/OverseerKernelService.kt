package com.achulkov.evokammer.api.service

import com.achulkov.evokammer.core.contract.OverseerDeadEnd
import com.achulkov.evokammer.core.contract.OverseerDeadEndHistoryEntry
import com.achulkov.evokammer.core.contract.OverseerExperimentOutcome
import com.achulkov.evokammer.core.contract.OverseerExperimentPlan
import com.achulkov.evokammer.core.contract.OverseerExperimentPlanEligibility
import com.achulkov.evokammer.core.contract.OverseerExperimentPlanHistoryEntry
import com.achulkov.evokammer.core.contract.OverseerExperimentResult
import com.achulkov.evokammer.core.contract.OverseerHypothesis
import com.achulkov.evokammer.core.contract.OverseerKernelAuditActionType
import com.achulkov.evokammer.core.contract.OverseerKernelAuditEntry
import com.achulkov.evokammer.core.contract.OverseerKernelEligibility
import com.achulkov.evokammer.core.contract.OverseerKernelState
import com.achulkov.evokammer.core.contract.OverseerKernelArchive
import com.achulkov.evokammer.core.contract.OverseerKernelRetentionPolicy
import com.achulkov.evokammer.core.overseer.OverseerKernel
import com.achulkov.evokammer.substrate.OverseerKernelAuditStore
import com.achulkov.evokammer.substrate.OverseerKernelStateStore
import com.achulkov.evokammer.substrate.SubstrateDatabaseFactory
import java.util.UUID

/**
 * Deterministic service façade for the recursive self-improvement kernel.
 *
 * This service is deliberately non-LLM and stateful only through the durable kernel store.
 * It is the likely future host for API and IPC exposure once the surrounding runtime wiring
 * needs to consume hypotheses and experiment plans.
 *
 * @param stateStore Durable state store for the Overseer kernel.
 * @param kernel Pure validation and mutation engine.
 */
class OverseerKernelService(
    substrateFactory: SubstrateDatabaseFactory,
    private val stateStore: OverseerKernelStateStore = OverseerKernelStateStore(substrateFactory),
    private val auditStore: OverseerKernelAuditStore = OverseerKernelAuditStore(substrateFactory),
    private val kernel: OverseerKernel = OverseerKernel()
) {
    /**
     * Mutable state transition and the exact audit entry it appended.
     *
     * The audit object is reused by runtime mirrors to keep JSONL and meta-archive records
     * identical.
     *
     * @param state Updated deterministic kernel snapshot.
     * @param auditEntry Exact audit entry appended for the mutation.
     */
    data class MutationResult(
        val state: OverseerKernelState,
        val auditEntry: OverseerKernelAuditEntry
    )

    /**
     * Returns the current kernel snapshot, creating an empty one if none exists yet.
     *
     * @return Current durable Overseer state.
     */
    fun currentState(): OverseerKernelState = stateStore.read() ?: OverseerKernelState()

    /**
     * Returns the deterministic preflight verdict for a hypothesis without mutating state.
     *
     * @param hypothesis Hypothesis to validate.
     * @return Structured allow/deny verdict with stable reasons.
     */
    fun preflightHypothesis(hypothesis: OverseerHypothesis): OverseerKernelEligibility =
        kernel.validateHypothesisEligibility(hypothesis)

    /**
     * Returns the deterministic preflight verdict for an experiment plan without mutating state.
     *
     * @param experimentPlan Experiment plan to validate.
     * @return Structured allow/deny verdict with stable reasons.
     */
    fun preflightExperimentPlan(experimentPlan: OverseerExperimentPlan): OverseerExperimentPlanEligibility =
        kernel.validateExperimentPlanEligibility(currentState(), experimentPlan)

    /**
     * Formalizes a hypothesis and persists the updated kernel state.
     *
     * @param hypothesis Hypothesis to register.
     * @return Updated kernel snapshot.
     */
    fun formalizeHypothesis(hypothesis: OverseerHypothesis): OverseerKernelState =
        formalizeHypothesisWithAudit(hypothesis).state

    /**
     * Formalizes a hypothesis and returns the state plus the exact appended audit entry.
     *
     * @param hypothesis Hypothesis to register.
     * @return Updated kernel snapshot and exact audit entry.
     */
    fun formalizeHypothesisWithAudit(hypothesis: OverseerHypothesis): MutationResult =
        mutate { current ->
            val updated = kernel.formalizeHypothesis(current, hypothesis)
            updated.auditEntry(
                actionType = OverseerKernelAuditActionType.FORMALIZE_HYPOTHESIS,
                hypothesisId = hypothesis.id
            ).let { auditEntry -> MutationResult(updated, auditEntry) }
        }

    /**
     * Registers an experiment plan against a formalized hypothesis.
     *
     * @param experimentPlan Experiment plan to store.
     * @return Updated kernel snapshot.
     */
    fun registerExperimentPlan(experimentPlan: OverseerExperimentPlan): OverseerKernelState =
        registerExperimentPlanWithAudit(experimentPlan).state

    /**
     * Registers an experiment plan and returns the state plus the exact appended audit entry.
     *
     * @param experimentPlan Experiment plan to store.
     * @return Updated kernel snapshot and exact audit entry.
     */
    fun registerExperimentPlanWithAudit(experimentPlan: OverseerExperimentPlan): MutationResult =
        mutate { current ->
            val updated = kernel.registerExperimentPlan(current, experimentPlan)
            updated.auditEntry(
                actionType = OverseerKernelAuditActionType.REGISTER_EXPERIMENT_PLAN,
                experimentPlanId = experimentPlan.id
            ).let { auditEntry -> MutationResult(updated, auditEntry) }
        }

    fun scheduleEligibleExperimentPlans(): OverseerKernelState =
        scheduleEligibleExperimentPlansWithAudit().state

    /**
     * Schedules all eligible experiment plans and returns the updated state plus the audit entry.
     *
     * @return Updated kernel snapshot and exact audit entry.
     */
    fun scheduleEligibleExperimentPlansWithAudit(): MutationResult =
        mutate { current ->
            val updated = kernel.scheduleExperimentPlans(current)
            val newlyScheduledPlanIds = (updated.experimentSchedules.keys - current.experimentSchedules.keys)
                .sorted()
            updated.auditEntry(
                actionType = OverseerKernelAuditActionType.SCHEDULE_EXPERIMENT_PLANS,
                note = if (newlyScheduledPlanIds.isEmpty()) {
                    "No eligible experiment plans were scheduled."
                } else {
                    "Scheduled experiment plans: ${newlyScheduledPlanIds.joinToString(", ")}"
                }
            ).let { auditEntry -> MutationResult(updated, auditEntry) }
        }

    /**
     * Marks a scheduled experiment run as launched and persists the updated kernel state.
     *
     * @param experimentPlanId Experiment plan whose schedule is being consumed.
     * @param scheduledRunId Scheduled run identifier to consume.
     * @return Updated kernel snapshot.
     */
    fun launchExperimentRun(
        experimentPlanId: String,
        scheduledRunId: String
    ): OverseerKernelState =
        launchExperimentRunWithAudit(experimentPlanId, scheduledRunId).state

    /**
     * Marks a scheduled experiment run as launched and returns the updated state plus audit entry.
     *
     * @param experimentPlanId Experiment plan whose schedule is being consumed.
     * @param scheduledRunId Scheduled run identifier to consume.
     * @return Updated kernel snapshot and exact audit entry.
     */
    fun launchExperimentRunWithAudit(
        experimentPlanId: String,
        scheduledRunId: String
    ): MutationResult =
        mutate { current ->
            val updated = kernel.launchExperimentRun(current, experimentPlanId, scheduledRunId)
            updated.auditEntry(
                actionType = OverseerKernelAuditActionType.LAUNCH_EXPERIMENT_RUN,
                experimentPlanId = experimentPlanId,
                note = scheduledRunId
            ).let { auditEntry -> MutationResult(updated, auditEntry) }
        }

    /**
     * Records the deterministic outcome of an experiment and persists the updated kernel state.
     *
     * @param experimentResult Experiment result to store.
     * @return Updated kernel snapshot.
     */
    fun recordExperimentResult(experimentResult: OverseerExperimentResult): OverseerKernelState =
        recordExperimentResultWithAudit(experimentResult).state

    /**
     * Records the deterministic outcome of an experiment and returns the updated state plus audit entry.
     *
     * @param experimentResult Experiment result to store.
     * @return Updated kernel snapshot and exact audit entry.
     */
    fun recordExperimentResultWithAudit(experimentResult: OverseerExperimentResult): MutationResult =
        mutate { current ->
            val updated = kernel.recordExperimentResult(current, experimentResult)
            updated.auditEntry(
                actionType = OverseerKernelAuditActionType.RECORD_EXPERIMENT_RESULT,
                hypothesisId = experimentResult.hypothesisId,
                experimentPlanId = experimentResult.experimentPlanId,
                note = experimentResult.note.ifBlank { experimentResult.outcome.name }
            ).let { auditEntry -> MutationResult(updated, auditEntry) }
        }

    /**
     * Records a dead end and persists the updated kernel state.
     *
     * @param deadEnd Dead-end evidence to store.
     * @return Updated kernel snapshot.
     */
    fun recordDeadEnd(deadEnd: OverseerDeadEnd): OverseerKernelState =
        recordDeadEndWithAudit(deadEnd).state

    /**
     * Records a dead end and returns the state plus the exact appended audit entry.
     *
     * @param deadEnd Dead-end evidence to store.
     * @return Updated kernel snapshot and exact audit entry.
     */
    fun recordDeadEndWithAudit(deadEnd: OverseerDeadEnd): MutationResult =
        mutate { current ->
            val updated = kernel.recordDeadEnd(current, deadEnd)
            updated.auditEntry(
                actionType = OverseerKernelAuditActionType.RECORD_DEAD_END,
                deadEndHypothesisId = deadEnd.hypothesisId,
                note = deadEnd.reason
            ).let { auditEntry -> MutationResult(updated, auditEntry) }
        }

    /**
     * Applies the recurrent-loop forget gate to stale terminal entries.
     *
     * @param retentionPolicy Forget-gate policy for terminal hypotheses.
     * @return Updated kernel snapshot plus audit entry when pruning occurred, or null otherwise.
     */
    fun compactTerminalState(
        retentionPolicy: OverseerKernelRetentionPolicy = OverseerKernelRetentionPolicy()
    ): MutationResult? {
        synchronized(this) {
            val current = currentState()
            val result = kernel.compactTerminalState(current, retentionPolicy = retentionPolicy)
            if (result.prunedHypothesisIds.isEmpty() && result.prunedExperimentPlanIds.isEmpty()) {
                return null
            }

            val note = buildString {
                append("Pruned terminal hypotheses: ")
                append(result.prunedHypothesisIds.sorted().joinToString(", "))
                if (result.prunedExperimentPlanIds.isNotEmpty()) {
                    append("; pruned experiment plans: ")
                    append(result.prunedExperimentPlanIds.sorted().joinToString(", "))
                }
            }

            val auditEntry = result.state.auditEntry(
                actionType = OverseerKernelAuditActionType.COMPACT_KERNEL_STATE,
                note = note
            )
            auditStore.append(auditEntry)
            stateStore.write(result.state)
            return MutationResult(result.state, auditEntry)
        }
    }

    /**
     * Returns the durable Overseer audit trail.
     */
    fun getAuditEntries(): List<OverseerKernelAuditEntry> = auditStore.readAll()

    /**
     * Returns the lifecycle history for a specific experiment plan.
     *
     * @param experimentPlanId Experiment plan identifier.
     */
    fun getExperimentPlanHistory(experimentPlanId: String): List<OverseerExperimentPlanHistoryEntry> =
        currentState().experimentPlanHistory[experimentPlanId].orEmpty()

    /**
     * Returns the dead-end history for a specific hypothesis.
     *
     * @param hypothesisId Hypothesis identifier.
     */
    fun getDeadEndHistory(hypothesisId: String): List<OverseerDeadEndHistoryEntry> =
        currentState().deadEndHistory[hypothesisId].orEmpty()

    private fun mutate(block: (OverseerKernelState) -> MutationResult): MutationResult {
        val result = block(currentState())
        auditStore.append(result.auditEntry)
        stateStore.write(result.state)
        return result
    }

    private fun OverseerKernelState.auditEntry(
        actionType: OverseerKernelAuditActionType,
        hypothesisId: String? = null,
        experimentPlanId: String? = null,
        deadEndHypothesisId: String? = null,
        note: String? = null
    ): OverseerKernelAuditEntry = OverseerKernelAuditEntry(
        entryId = UUID.randomUUID().toString(),
        timestampMs = System.currentTimeMillis(),
        actionType = actionType,
        hypothesisId = hypothesisId,
        experimentPlanId = experimentPlanId,
        deadEndHypothesisId = deadEndHypothesisId,
        hypothesisCount = hypotheses.size,
        experimentPlanCount = experimentPlans.size,
        deadEndCount = deadEnds.size,
        note = note
    )
}
