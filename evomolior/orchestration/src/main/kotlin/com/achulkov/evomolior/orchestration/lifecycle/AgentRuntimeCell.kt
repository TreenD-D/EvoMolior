@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.orchestration.lifecycle

import com.achulkov.evomolior.core.atlas.AtlasAgentId
import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.budget.BudgetLedger
import com.achulkov.evomolior.core.contract.AgentExitRecord
import com.achulkov.evomolior.core.contract.AgentLifecycleClassification
import com.achulkov.evomolior.core.contract.AgentLifecycleDuty
import com.achulkov.evomolior.core.contract.AgentRuntimePhase
import com.achulkov.evomolior.core.contract.AgentRuntimeState
import com.achulkov.evomolior.core.contract.AgentRuntimeStateEvent
import com.achulkov.evomolior.core.contract.AgentRuntimeTransitionIntent
import com.achulkov.evomolior.core.contract.TtlCheckpointKind
import com.achulkov.evomolior.core.contract.WipTaskClaim
import com.achulkov.evomolior.core.model.Role
import com.achulkov.evomolior.sandbox.shuru.VmHandle
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Job
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Stable per-agent runtime identity with atomic, intent-specific state reducers.
 *
 * Jobs, VM handles, ledgers, and lifecycle callbacks are stable resources owned by this cell.
 * Reducer state is immutable and never replaced through a stale whole-entry map write.
 */
internal class AgentRuntimeCell(
    val runId: String,
    val groupId: String,
    val agentId: String,
    val generation: Int,
    val role: Role,
    val allocatedBudget: Double,
    val wipTaskClaim: WipTaskClaim?,
    val workerAuthority: WorkerAuthorityAssignment?,
    val classification: AgentLifecycleClassification,
    val spawnedAtMs: Long,
    private val transitionSink: (AgentRuntimeStateEvent) -> Unit,
    private val now: () -> Instant = { Clock.System.now() },
    val duty: AgentLifecycleDuty = AgentLifecycleDuty.NONE,
    val workspaceId: String? = null,
    val workspaceSourceHash: String? = null
) {
    init {
        require(duty != AgentLifecycleDuty.ARTIFACT_OWNER || role == Role.Builder) {
            "ArtifactOwner duty is reserved for Builder runtime cells"
        }
    }

    private val state = AtomicReference(
        AgentRuntimeState(
            runId = runId,
            groupId = AtlasGroupId(groupId),
            agentId = AtlasAgentId(agentId),
            generation = generation,
            phase = AgentRuntimePhase.REGISTERED,
            revision = 0,
            budgetRemaining = allocatedBudget
        ).withComputedHash()
    )
    private val job = AtomicReference<Job?>(null)
    private val handle = AtomicReference<VmHandle?>(null)
    private val budgetLedger = AtomicReference<BudgetLedger?>(null)

    /** Returns the latest immutable reducer state. */
    fun snapshot(): AgentRuntimeState = state.get()

    /** Returns the in-process execution job, when this cell owns one. */
    fun executionJob(): Job? = job.get()

    /** Returns the Shuru VM handle, when this cell owns one. */
    fun vmHandle(): VmHandle? = handle.get()

    /** Returns the live budget ledger registered by an in-process runner. */
    fun ledger(): BudgetLedger? = budgetLedger.get()

    /** Records the durable lifecycle registration as this cell's first transition. */
    fun confirmRegistration() {
        transition(AgentRuntimeTransitionIntent.REGISTER_AGENT) { current ->
            if (current.registrationRecorded) current else current.copy(registrationRecorded = true)
        }
    }

    /** Attaches the one in-process execution job to this stable cell. */
    fun attachExecutionJob(value: Job) {
        check(job.compareAndSet(null, value) || job.get() === value) {
            "Agent runtime cell already owns a different execution job"
        }
        transition(AgentRuntimeTransitionIntent.START_EXECUTION) { current ->
            check(current.registrationRecorded) {
                "Agent runtime execution cannot start before durable registration"
            }
            current.copy(phase = maxPhase(current.phase, AgentRuntimePhase.RUNNING))
        }
    }

    /** Attaches the one Shuru VM handle to this stable cell. */
    fun attachVmHandle(value: VmHandle) {
        check(handle.compareAndSet(null, value) || handle.get() == value) {
            "Agent runtime cell already owns a different VM handle"
        }
        transition(AgentRuntimeTransitionIntent.START_EXECUTION) { current ->
            check(current.registrationRecorded) {
                "Agent runtime execution cannot start before durable registration"
            }
            current.copy(phase = maxPhase(current.phase, AgentRuntimePhase.RUNNING))
        }
    }

    /**
     * Records that this artifact owner actually entered its assigned workspace.
     *
     * Allocation, registration, and a Builder role are deliberately insufficient evidence. The
     * caller must provide the concrete workspace identity and source commitment observed at
     * execution time; the immutable runtime event then carries that lineage into persistence.
     *
     * @param workspaceId Concrete group workspace identity.
     * @param workspaceSourceHash Hash of the source snapshot from which work started.
     * @return The resulting runtime authority state.
     */
    fun markArtifactWorkspaceStarted(
        workspaceId: String,
        workspaceSourceHash: String
    ): AgentRuntimeState = markArtifactWorkspaceUsed(workspaceId, workspaceSourceHash)

    /**
     * Records one idempotent actual-workspace execution observation.
     *
     * @param workspaceId Concrete group workspace identity.
     * @param workspaceSourceHash Hash of the source snapshot from which work started.
     * @return The resulting runtime authority state.
     */
    fun markArtifactWorkspaceUsed(
        workspaceId: String,
        workspaceSourceHash: String
    ): AgentRuntimeState {
        check(role == Role.Builder && duty == AgentLifecycleDuty.ARTIFACT_OWNER) {
            "Only the ArtifactOwner duty may mark artifact workspace execution"
        }
        require(workspaceId.isNotBlank()) {
            "Artifact workspace execution requires a non-blank workspace id"
        }
        require(workspaceSourceHash.isNotBlank()) {
            "Artifact workspace execution requires a non-blank source hash"
        }
        this.workspaceId?.let {
            require(it == workspaceId) {
                "Artifact workspace execution does not match the registered workspace id"
            }
        }
        this.workspaceSourceHash?.let {
            require(it == workspaceSourceHash) {
                "Artifact workspace execution does not match the registered source hash"
            }
        }
        transition(AgentRuntimeTransitionIntent.MARK_ARTIFACT_WORKSPACE_USED) { current ->
            check(current.registrationRecorded) {
                "Artifact workspace execution cannot precede durable registration"
            }
            check(current.phase >= AgentRuntimePhase.RUNNING) {
                "Artifact workspace execution cannot precede runtime start"
            }
            if (current.artifactWorkspaceStarted) {
                check(
                    current.artifactWorkspaceId == workspaceId &&
                        current.artifactWorkspaceSourceHash == workspaceSourceHash
                ) {
                    "Artifact workspace execution identity is immutable"
                }
                current
            } else {
                current.copy(
                    artifactWorkspaceStarted = true,
                    artifactWorkspaceId = workspaceId,
                    artifactWorkspaceSourceHash = workspaceSourceHash
                )
            }
        }
        return state.get()
    }

    /** Registers the live ledger without replacing any concurrent runtime state. */
    fun attachBudgetLedger(value: BudgetLedger) {
        check(budgetLedger.compareAndSet(null, value) || budgetLedger.get() === value) {
            "Agent runtime cell already owns a different budget ledger"
        }
        transition(AgentRuntimeTransitionIntent.ATTACH_BUDGET_LEDGER) { current ->
            current.copy(
                budgetRemaining = value.balance,
                budgetLedgerAttached = true
            )
        }
    }

    /** Publishes a current budget observation through the atomic reducer. */
    fun observeBudget(
        balance: Double,
        intent: AgentRuntimeTransitionIntent = AgentRuntimeTransitionIntent.OBSERVE_BUDGET
    ): Boolean {
        require(balance >= 0.0 && balance.isFinite()) {
            "Observed agent budget must be finite and non-negative"
        }
        return transition(intent) { current ->
            if (current.budgetRemaining == balance) current else current.copy(budgetRemaining = balance)
        } != null
    }

    /** Records one TTL checkpoint in the monotonic checkpoint set. */
    fun recordTtlCheckpoint(checkpoint: TtlCheckpointKind): Boolean =
        transition(AgentRuntimeTransitionIntent.RECORD_TTL_CHECKPOINT) { current ->
            if (checkpoint in current.ttlCheckpoints) {
                current
            } else {
                current.copy(ttlCheckpoints = current.ttlCheckpoints + checkpoint)
            }
        } != null

    /** Atomically claims termination ownership for the first caller. */
    fun requestTermination(): Boolean =
        transition(AgentRuntimeTransitionIntent.REQUEST_TERMINATION) { current ->
            if (current.killRequested || current.exitRecord != null) {
                current
            } else {
                current.copy(
                    phase = maxPhase(current.phase, AgentRuntimePhase.TERMINATION_REQUESTED),
                    killRequested = true
                )
            }
        } != null

    /**
     * Freezes the first exit authority and returns the immutable authority owned by this cell.
     *
     * Later callers always receive the already-frozen record, regardless of their candidate.
     */
    fun freezeExit(candidate: AgentExitRecord): AgentExitRecord {
        transition(AgentRuntimeTransitionIntent.FREEZE_EXIT) { current ->
            if (current.exitRecord != null) {
                current
            } else {
                val frozen = candidate.copy(wasKilled = candidate.wasKilled || current.killRequested)
                current.copy(
                    phase = maxPhase(current.phase, AgentRuntimePhase.EXIT_FROZEN),
                    exitRecord = frozen,
                    exitRecordHash = AtlasHashing.hashAgentExitRecord(frozen)
                )
            }
        }
        return requireNotNull(state.get().exitRecord)
    }

    /** Records that the frozen exit authority is durable. */
    fun confirmExitPersisted() {
        transition(AgentRuntimeTransitionIntent.CONFIRM_EXIT_PERSISTED) { current ->
            check(current.exitRecord != null) { "Cannot persist an unfrozen agent exit" }
            current.copy(
                phase = maxPhase(current.phase, AgentRuntimePhase.EXIT_PERSISTED),
                exitPersisted = true
            )
        }
    }

    /** Marks the underlying runner or VM execution as actually settled. */
    fun markExecutionSettled(): AgentRuntimeState {
        transition(AgentRuntimeTransitionIntent.MARK_EXECUTION_SETTLED) { current ->
            current.copy(
                phase = maxPhase(current.phase, AgentRuntimePhase.EXECUTION_SETTLED),
                executionSettled = true
            )
        }
        return state.get()
    }

    /** Marks frozen-record lease and WIP cleanup complete exactly once. */
    fun markAuthorityCleanupComplete() {
        transition(AgentRuntimeTransitionIntent.MARK_AUTHORITY_CLEANUP_COMPLETE) { current ->
            check(current.exitPersisted) {
                "Lease/WIP cleanup requires durable exit authority"
            }
            if (current.authorityCleanupComplete) {
                current
            } else {
                current.copy(authorityCleanupComplete = true)
            }
        }
    }

    /** Marks non-cancellable lifecycle cost/refund settlement complete exactly once. */
    fun markCostSettled() {
        transition(AgentRuntimeTransitionIntent.MARK_COST_SETTLED) { current ->
            check(current.exitPersisted) { "Cost settlement requires durable exit authority" }
            check(current.executionSettled) { "Cost settlement requires settled execution" }
            if (current.costSettled) current else current.copy(costSettled = true)
        }
    }

    /** Claims the once-only post-settlement death callback emission. */
    fun claimDeathCallbackEmission(): Boolean =
        transition(AgentRuntimeTransitionIntent.MARK_DEATH_CALLBACK_EMITTED) { current ->
            check(current.costSettled) { "Death callback requires completed cost settlement" }
            if (current.deathCallbackEmitted) {
                current
            } else {
                current.copy(deathCallbackEmitted = true)
            }
        } != null

    /** Marks all required lifecycle cleanup complete immediately before registry removal. */
    fun confirmCleanupComplete() {
        transition(AgentRuntimeTransitionIntent.CONFIRM_CLEANUP_COMPLETE) { current ->
            check(current.exitRecord != null) { "Cleanup cannot complete before exit authority freezes" }
            check(current.exitPersisted) { "Cleanup cannot complete before exit authority persists" }
            check(current.executionSettled) { "Cleanup cannot complete before execution settles" }
            check(current.authorityCleanupComplete) {
                "Cleanup cannot complete before frozen-record lease/WIP cleanup"
            }
            check(current.costSettled) { "Cleanup cannot complete before cost settlement" }
            check(current.deathCallbackEmitted) {
                "Cleanup cannot complete before the once-only death callback"
            }
            current.copy(
                phase = AgentRuntimePhase.CLEANUP_COMPLETE,
                cleanupComplete = true
            )
        }
    }

    private fun transition(
        intent: AgentRuntimeTransitionIntent,
        reducer: (AgentRuntimeState) -> AgentRuntimeState
    ): AgentRuntimeStateEvent? {
        while (true) {
            val previous = state.get()
            if (previous.cleanupComplete) return null
            val reduced = reducer(previous)
            if (reduced == previous) return null
            check(reduced.phase >= previous.phase) {
                "Agent runtime phase cannot move backward: ${previous.phase} -> ${reduced.phase}"
            }
            check(reduced.killRequested || !previous.killRequested) {
                "Agent termination intent cannot be cleared"
            }
            check(reduced.exitRecord == previous.exitRecord || previous.exitRecord == null) {
                "Frozen agent exit authority cannot change"
            }
            check(reduced.ttlCheckpoints.containsAll(previous.ttlCheckpoints)) {
                "Agent TTL checkpoints cannot be removed"
            }
            check(reduced.authorityCleanupComplete || !previous.authorityCleanupComplete) {
                "Agent authority cleanup cannot be cleared"
            }
            check(reduced.costSettled || !previous.costSettled) {
                "Agent cost settlement cannot be cleared"
            }
            check(reduced.deathCallbackEmitted || !previous.deathCallbackEmitted) {
                "Agent death callback authority cannot be cleared"
            }
            val next = reduced.copy(
                revision = previous.revision + 1,
                stateHash = ""
            ).withComputedHash()
            if (!state.compareAndSet(previous, next)) continue
            val event = AgentRuntimeStateEvent(
                eventId = "agent-runtime:$runId:$groupId:$agentId:${next.revision}",
                runId = runId,
                groupId = AtlasGroupId(groupId),
                agentId = AtlasAgentId(agentId),
                generation = generation,
                revision = next.revision,
                intent = intent,
                previousStateHash = if (next.revision == 1L) null else previous.stateHash,
                state = next,
                occurredAt = now()
            ).withComputedHash()
            transitionSink(event)
            return event
        }
    }

    private fun maxPhase(
        current: AgentRuntimePhase,
        requested: AgentRuntimePhase
    ): AgentRuntimePhase = if (current >= requested) current else requested
}
