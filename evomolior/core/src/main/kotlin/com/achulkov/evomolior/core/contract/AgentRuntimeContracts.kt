@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.core.contract

import com.achulkov.evomolior.core.atlas.AtlasAgentId
import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.AtlasHashing
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** Monotonic phases of one registered agent's runtime lifecycle. */
@Serializable
enum class AgentRuntimePhase {
    REGISTERED,
    RUNNING,
    TERMINATION_REQUESTED,
    EXIT_FROZEN,
    EXIT_PERSISTED,
    EXECUTION_SETTLED,
    CLEANUP_COMPLETE
}

/** Intent that caused one atomic runtime-state transition. */
@Serializable
enum class AgentRuntimeTransitionIntent {
    REGISTER_AGENT,
    START_EXECUTION,
    ATTACH_BUDGET_LEDGER,
    OBSERVE_BUDGET,
    RECORD_TTL_CHECKPOINT,
    REQUEST_TERMINATION,
    FREEZE_EXIT,
    CONFIRM_EXIT_PERSISTED,
    MARK_EXECUTION_SETTLED,
    MARK_AUTHORITY_CLEANUP_COMPLETE,
    MARK_COST_SETTLED,
    MARK_DEATH_CALLBACK_EMITTED,
    CONFIRM_CLEANUP_COMPLETE,
    RECONCILE_FROM_PERSISTENCE,
    MARK_ARTIFACT_WORKSPACE_USED
}

/**
 * Immutable reducer state for one live agent.
 *
 * Runtime resources such as jobs, VM handles, ledgers, and callbacks deliberately remain outside
 * this serializable authority. [revision] increases exactly once for every accepted transition.
 */
@Serializable
data class AgentRuntimeState(
    val runId: String,
    val groupId: AtlasGroupId,
    val agentId: AtlasAgentId,
    val generation: Int,
    val phase: AgentRuntimePhase,
    val revision: Long,
    val budgetRemaining: Double,
    val registrationRecorded: Boolean = false,
    val budgetLedgerAttached: Boolean = false,
    val ttlCheckpoints: Set<TtlCheckpointKind> = emptySet(),
    val killRequested: Boolean = false,
    val exitPersisted: Boolean = false,
    val executionSettled: Boolean = false,
    val authorityCleanupComplete: Boolean = false,
    val costSettled: Boolean = false,
    val deathCallbackEmitted: Boolean = false,
    val cleanupComplete: Boolean = false,
    val exitRecord: AgentExitRecord? = null,
    val exitRecordHash: String? = null,
    val stateHash: String = "",
    val artifactWorkspaceStarted: Boolean = false,
    val artifactWorkspaceId: String? = null,
    val artifactWorkspaceSourceHash: String? = null
) {
    init {
        require(runId.isNotBlank() && groupId.value.isNotBlank() && agentId.value.isNotBlank()) {
            "Agent runtime-state identity must not be blank"
        }
        require(generation >= 0) { "Agent runtime-state generation must not be negative" }
        require(revision >= 0) { "Agent runtime-state revision must not be negative" }
        require(budgetRemaining >= 0.0 && budgetRemaining.isFinite()) {
            "Agent runtime-state budget must be finite and non-negative"
        }
        require((exitRecord == null) == (exitRecordHash == null)) {
            "Agent runtime-state exit authority and hash must be present together"
        }
        require(
            (artifactWorkspaceStarted &&
                artifactWorkspaceId != null &&
                artifactWorkspaceSourceHash != null) ||
                (!artifactWorkspaceStarted &&
                    artifactWorkspaceId == null &&
                    artifactWorkspaceSourceHash == null)
        ) {
            "Artifact workspace execution evidence must be all-or-nothing"
        }
        require(artifactWorkspaceId == null || artifactWorkspaceId.isNotBlank()) {
            "Artifact workspace evidence must not contain a blank workspace id"
        }
        require(
            artifactWorkspaceSourceHash == null || artifactWorkspaceSourceHash.isNotBlank()
        ) {
            "Artifact workspace evidence must not contain a blank source hash"
        }
        exitRecord?.let { exit ->
            require(
                exit.runId == runId &&
                    exit.groupId == groupId &&
                    exit.agentId == agentId &&
                    exit.round == generation
            ) {
                "Agent runtime-state exit authority must match the registered lifecycle"
            }
            require(
                AtlasHashing.constantTimeEquals(
                    requireNotNull(exitRecordHash),
                    AtlasHashing.hashAgentExitRecord(exit)
                )
            ) {
                "Agent runtime-state exit authority hash is invalid"
            }
        }
        require(!executionSettled || phase >= AgentRuntimePhase.EXECUTION_SETTLED) {
            "Settled execution requires an execution-settled runtime phase"
        }
        require(phase == AgentRuntimePhase.REGISTERED || registrationRecorded) {
            "Runtime execution cannot advance before registration is recorded"
        }
        require(!exitPersisted || exitRecord != null) {
            "Persisted exit state requires frozen exit authority"
        }
        require(!authorityCleanupComplete || exitPersisted) {
            "Lease/WIP cleanup requires durable exit authority"
        }
        require(!cleanupComplete || exitPersisted && executionSettled && authorityCleanupComplete) {
            "Completed cleanup requires durable exit, authority cleanup, and settled execution"
        }
        require(!costSettled || exitPersisted && executionSettled) {
            "Cost settlement requires durable exit authority and settled execution"
        }
        require(!deathCallbackEmitted || costSettled) {
            "Death callback emission requires completed cost settlement"
        }
        require(!cleanupComplete || deathCallbackEmitted) {
            "Completed cleanup requires the once-only death callback emission"
        }
        require(!cleanupComplete || phase == AgentRuntimePhase.CLEANUP_COMPLETE) {
            "Completed cleanup requires the cleanup-complete runtime phase"
        }
        require(exitRecord == null || phase >= AgentRuntimePhase.EXIT_FROZEN) {
            "Frozen exit authority requires an exit-frozen runtime phase"
        }
    }

    /** Returns this state with its deterministic commitment attached. */
    fun withComputedHash(): AgentRuntimeState =
        copy(stateHash = AtlasHashing.hashAgentRuntimeState(this))

    /** Verifies the exact runtime-state commitment. */
    fun hasValidHash(): Boolean =
        AtlasHashing.constantTimeEquals(stateHash, AtlasHashing.hashAgentRuntimeState(this))
}

/** Append-only audit authority for one accepted runtime-state transition. */
@Serializable
data class AgentRuntimeStateEvent(
    val eventId: String,
    val runId: String,
    val groupId: AtlasGroupId,
    val agentId: AtlasAgentId,
    val generation: Int,
    val revision: Long,
    val intent: AgentRuntimeTransitionIntent,
    val previousStateHash: String?,
    val state: AgentRuntimeState,
    val occurredAt: Instant,
    val eventHash: String = ""
) {
    init {
        require(eventId.isNotBlank()) { "Agent runtime-state event identity must not be blank" }
        require(
            state.runId == runId &&
                state.groupId == groupId &&
                state.agentId == agentId &&
                state.generation == generation &&
                state.revision == revision &&
                state.hasValidHash()
        ) {
            "Agent runtime-state event must contain its exact committed state"
        }
        require(revision > 0) { "Agent runtime-state event revision must be positive" }
        require(previousStateHash == null || previousStateHash.isNotBlank()) {
            "Previous runtime-state hash must be null or non-blank"
        }
    }

    /** Returns this event with its deterministic commitment attached. */
    fun withComputedHash(): AgentRuntimeStateEvent =
        copy(eventHash = AtlasHashing.hashAgentRuntimeStateEvent(this))

    /** Verifies the exact transition-event commitment. */
    fun hasValidHash(): Boolean =
        AtlasHashing.constantTimeEquals(eventHash, AtlasHashing.hashAgentRuntimeStateEvent(this))
}

/** Typed result of attempting to append one immutable agent exit authority. */
@Serializable
sealed interface PersistExitResult {
    val exitRecordHash: String

    /** The attempted exit became the durable authority. */
    @Serializable
    data class Inserted(
        override val exitRecordHash: String
    ) : PersistExitResult

    /** The exact same exit authority was already durable. */
    @Serializable
    data class AlreadyPersisted(
        override val exitRecordHash: String
    ) : PersistExitResult

    /** A different immutable exit authority already owns this registered agent identity. */
    @Serializable
    data class Conflict(
        override val exitRecordHash: String,
        val persistedExitRecordHash: String
    ) : PersistExitResult
}
