@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.substrate.atlas

import com.achulkov.evomolior.core.atlas.AtlasAgentId
import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.GradeVerdict
import com.achulkov.evomolior.core.atlas.LeaseState
import com.achulkov.evomolior.core.atlas.NodeExecutionState
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.atlas.NodeLease
import com.achulkov.evomolior.core.atlas.ReplicationMode
import com.achulkov.evomolior.core.council.CouncilDeterministicIds
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Instant

/**
 * Optimistic Atlas transaction authority for node lease acquisition, expiry, and finalization.
 *
 * Lease finalization never grades, captures, or closes a node. Those remain later authoritative
 * transitions driven by accepted evidence and grading.
 */
class AtlasLeaseAuthority(
    private val repository: AtlasTransactionRepository,
    private val now: () -> Instant = { kotlin.time.Clock.System.now() }
) {
    private val mutationMutex = Mutex()

    /** Acquires one deterministic lease on a revealed frontier node. */
    suspend fun acquire(
        runId: String,
        atlasId: String,
        groupId: AtlasGroupId,
        agentId: AtlasAgentId,
        nodeId: NodeId,
        round: Int,
        expiresAt: Instant,
        replicationSlot: Int? = null
    ): NodeLease = mutationMutex.withLock {
        val acquiredAt = now()
        require(expiresAt > acquiredAt) { "Node lease must expire in the future" }
        require(round >= 0) { "Node lease round must be non-negative" }
        require(replicationSlot == null || replicationSlot >= 0) {
            "replicationSlot must be non-negative"
        }
        val atlas = requireNotNull(repository.currentAtlas(runId, atlasId)) {
            "Atlas $atlasId is not initialized"
        }
        val node = requireNotNull(atlas.nodes[nodeId]) { "Unknown Atlas node ${nodeId.value}" }
        val states = repository.currentStates(runId, atlasId)
        val state = requireNotNull(states[nodeId]) { "Missing execution state for ${nodeId.value}" }
        require(groupId in state.revealedToGroups) { "Node ${nodeId.value} is not revealed to ${groupId.value}" }
        require(node.prerequisiteIds.all { prerequisiteId ->
            states.getValue(prerequisiteId).grades.any { it.verdict == GradeVerdict.VALID_PASS }
        }) {
            "Node ${nodeId.value} has incomplete prerequisites"
        }
        val allowedReplicationSlots = when (node.replicationPolicy.mode) {
            ReplicationMode.REQUIRED, ReplicationMode.ADVERSARIAL ->
                node.replicationPolicy.requiredCount
            ReplicationMode.NONE, ReplicationMode.OPTIONAL -> 1
        }
        val activeSlots = state.leases.asSequence()
            .filter {
                it.round == round && it.state == LeaseState.ACTIVE &&
                    it.expiresAt > acquiredAt
            }
            .map(NodeLease::replicationSlot)
            .toSet()
        val resolvedReplicationSlot = replicationSlot
            ?: (0 until allowedReplicationSlots).firstOrNull { it !in activeSlots }
            ?: throw IllegalArgumentException(
                "Node ${nodeId.value} has no replication capacity remaining in round $round"
            )
        require(resolvedReplicationSlot < allowedReplicationSlots) {
            "Replication slot $resolvedReplicationSlot exceeds the policy for ${nodeId.value}"
        }
        require(state.leases.count {
            it.round == round && it.state == LeaseState.ACTIVE && it.expiresAt > acquiredAt
        } < allowedReplicationSlots) {
            "Node ${nodeId.value} has no replication capacity remaining in round $round"
        }
        require(state.leases.none {
            it.groupId == groupId && it.round == round &&
                it.state == LeaseState.ACTIVE && it.expiresAt > acquiredAt
        }) {
            "Group ${groupId.value} already has an active lease for ${nodeId.value} in round $round"
        }
        require(state.leases.none {
            it.round == round && it.replicationSlot == resolvedReplicationSlot &&
                it.state == LeaseState.ACTIVE && it.expiresAt > acquiredAt
        }) {
            "Replication slot $resolvedReplicationSlot is already active for ${nodeId.value} in round $round"
        }

        val lease = NodeLease(
            leaseId = CouncilDeterministicIds.create(
                "node-lease",
                runId,
                atlasId,
                nodeId.value,
                groupId.value,
                agentId.value,
                round.toString(),
                resolvedReplicationSlot.toString()
            ),
            nodeId = nodeId,
            groupId = groupId,
            agentId = agentId,
            round = round,
            expiresAt = expiresAt,
            replicationSlot = resolvedReplicationSlot,
            state = LeaseState.ACTIVE
        )
        commitChangedState(
            runId = runId,
            atlasId = atlasId,
            atlasHash = atlas.atlasHash,
            currentState = state,
            nextState = state.copy(round = maxOf(state.round, round), leases = state.leases + lease),
            mutationType = "LEASE_ACQUIRED"
        )
        lease
    }

    /** Finalizes an active lease as committed or abandoned without closing the node. */
    suspend fun finalize(
        runId: String,
        atlasId: String,
        leaseId: String,
        committed: Boolean
    ): NodeLease = mutationMutex.withLock {
        val atlas = requireNotNull(repository.currentAtlas(runId, atlasId)) {
            "Atlas $atlasId is not initialized"
        }
        val states = repository.currentStates(runId, atlasId)
        val state = states.values.firstOrNull { candidate ->
            candidate.leases.any { it.leaseId == leaseId }
        } ?: error("Unknown node lease $leaseId")
        val lease = state.leases.first { it.leaseId == leaseId }
        val targetState = if (committed) LeaseState.COMMITTED else LeaseState.ABANDONED
        if (lease.state == targetState) {
            return@withLock lease
        }
        require(lease.state == LeaseState.ACTIVE) { "Only an active lease can be finalized" }
        val finalized = lease.copy(state = targetState)
        commitChangedState(
            runId = runId,
            atlasId = atlasId,
            atlasHash = atlas.atlasHash,
            currentState = state,
            nextState = state.copy(
                leases = state.leases.map { if (it.leaseId == leaseId) finalized else it }
            ),
            mutationType = if (committed) "LEASE_COMMITTED" else "LEASE_ABANDONED"
        )
        finalized
    }

    /** Expires every elapsed active lease and returns the transitioned lease records. */
    suspend fun expireElapsed(runId: String, atlasId: String): List<NodeLease> =
        mutationMutex.withLock {
        val instant = now()
        val atlas = requireNotNull(repository.currentAtlas(runId, atlasId)) {
            "Atlas $atlasId is not initialized"
        }
        val states = repository.currentStates(runId, atlasId)
        val changedStates = states.values.mapNotNull { state ->
            val elapsed = state.leases.filter {
                it.state == LeaseState.ACTIVE && it.expiresAt <= instant
            }
            if (elapsed.isEmpty()) {
                null
            } else {
                val elapsedIds = elapsed.map(NodeLease::leaseId).toSet()
                state.copy(
                    leases = state.leases.map {
                        if (it.leaseId in elapsedIds) it.copy(state = LeaseState.EXPIRED) else it
                    }
                )
            }
        }
        if (changedStates.isEmpty()) return@withLock emptyList()
        val projections = requireNotNull(repository.currentProjections(runId, atlasId)) {
            "Atlas projections are unavailable for $atlasId"
        }
        repository.commitExecution(
            AtlasExecutionCommitRequest(
                runId = runId,
                atlasId = atlasId,
                expectedAtlasHash = atlas.atlasHash,
                expectedStateHashes = changedStates.associate {
                    it.nodeId to AtlasHashing.hashExecutionState(states.getValue(it.nodeId))
                },
                changedStates = changedStates,
                projections = projections,
                mutationType = "LEASES_EXPIRED"
            )
        )
        changedStates.flatMap { changed ->
            val previous = states.getValue(changed.nodeId).leases.associateBy(NodeLease::leaseId)
            changed.leases.filter { lease ->
                lease.state == LeaseState.EXPIRED &&
                    previous[lease.leaseId]?.state == LeaseState.ACTIVE
            }
        }
    }

    private suspend fun commitChangedState(
        runId: String,
        atlasId: String,
        atlasHash: String,
        currentState: NodeExecutionState,
        nextState: NodeExecutionState,
        mutationType: String
    ) {
        val projections = requireNotNull(repository.currentProjections(runId, atlasId)) {
            "Atlas projections are unavailable for $atlasId"
        }
        repository.commitExecution(
            AtlasExecutionCommitRequest(
                runId = runId,
                atlasId = atlasId,
                expectedAtlasHash = atlasHash,
                expectedStateHashes = mapOf(
                    currentState.nodeId to AtlasHashing.hashExecutionState(currentState)
                ),
                changedStates = listOf(nextState),
                projections = projections,
                mutationType = mutationType
            )
        )
    }
}
