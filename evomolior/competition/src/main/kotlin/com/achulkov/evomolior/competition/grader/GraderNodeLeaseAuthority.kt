package com.achulkov.evomolior.competition.grader

import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.grader.GraderAtlas
import com.achulkov.evomolior.core.grader.GraderNodeExecutionState
import com.achulkov.evomolior.core.grader.GraderNodeId
import com.achulkov.evomolior.core.grader.GraderNodeLease
import com.achulkov.evomolior.core.grader.GraderNodeLeaseStatus
import com.achulkov.evomolior.core.grader.GraderNodeStatus
import com.achulkov.evomolior.core.grader.GraderView
import com.achulkov.evomolior.core.grader.GraderViewNode
import com.achulkov.evomolior.substrate.grader.GraderAuthorityRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes competitive claims and append-only lifecycle transitions for Grader Atlas nodes.
 *
 * One authority instance must be shared by the run coordinator. Repository revision constraints
 * remain the durable optimistic-concurrency boundary for accidental competing authorities.
 */
class GraderNodeLeaseAuthority(
    private val repository: GraderAuthorityRepository,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val mutex = Mutex()

    /**
     * Retains source compatibility for coordinators that initialize before iterating groups.
     *
     * Group-scoped state is initialized lazily by [view] and [acquireNext].
     */
    suspend fun initialize(atlas: GraderAtlas) = mutex.withLock {
        require(atlas.hasValidHash()) { "Grader Atlas hash is invalid" }
    }

    /** Initializes exactly one revision-zero state per node for [graderGroupId]. */
    suspend fun initialize(atlas: GraderAtlas, graderGroupId: String) = mutex.withLock {
        initializeGroup(atlas, graderGroupId)
    }

    private suspend fun initializeGroup(atlas: GraderAtlas, graderGroupId: String) {
        require(graderGroupId.isNotBlank()) { "Grader group ID must not be blank" }
        atlas.nodes.values.sortedBy { it.nodeId.value }.forEach { node ->
            if (repository.currentState(
                    atlas.runId,
                    atlas.atlasHash,
                    graderGroupId,
                    node.nodeId
                ) == null
            ) {
                repository.appendState(
                    GraderNodeExecutionState(
                        nodeId = node.nodeId,
                        runId = atlas.runId,
                        graderAtlasHash = atlas.atlasHash,
                        graderGroupId = graderGroupId,
                        revision = 0,
                        status = if (node.prerequisiteNodeIds.isEmpty()) {
                            GraderNodeStatus.OPEN
                        } else {
                            GraderNodeStatus.LOCKED
                        }
                    ).withComputedHash()
                )
            }
        }
    }

    /**
     * Returns a redacted view containing only roots, unlocked nodes, and this group's active work.
     */
    suspend fun view(
        atlas: GraderAtlas,
        graderGroupId: String,
        round: Int
    ): GraderView = mutex.withLock {
        initializeGroup(atlas, graderGroupId)
        expireDueLeases(atlas, graderGroupId)
        val states = atlas.nodes.keys.associateWith { nodeId ->
            requireNotNull(
                repository.currentState(atlas.runId, atlas.atlasHash, graderGroupId, nodeId)
            ) {
                "Grader Atlas node state is not initialized: ${nodeId.value}"
            }
        }
        val activeLeasesByNode = mutableMapOf<GraderNodeId, GraderNodeLease>()
        for ((nodeId, state) in states) {
            if (state.status == GraderNodeStatus.LEASED) {
                val leaseId = requireNotNull(state.leaseId) {
                    "Leased Grader Atlas node has no lease identity: ${nodeId.value}"
                }
                val lease = requireNotNull(repository.leaseById(atlas.runId, leaseId)) {
                    "Leased Grader Atlas node references an unknown lease: $leaseId"
                }
                require(
                    lease.status == GraderNodeLeaseStatus.ACTIVE &&
                        lease.nodeId == nodeId &&
                        lease.graderGroupId == graderGroupId &&
                        lease.graderAtlasHash == atlas.atlasHash
                ) {
                    "Active grader lease does not match its group-scoped frontier state"
                }
                activeLeasesByNode[nodeId] = lease
            }
        }
        GraderView(
            runId = atlas.runId,
            graderAtlasHash = atlas.atlasHash,
            graderGroupId = graderGroupId,
            round = round,
            nodes = atlas.nodes.values
                .filter { node ->
                    node.prerequisiteNodeIds.isEmpty() ||
                        node.prerequisiteNodeIds.all { prerequisite ->
                            states.getValue(prerequisite).status.isCompleted()
                        } ||
                        states.getValue(node.nodeId).leasedByGraderGroupId == graderGroupId
                }
                .sortedBy { it.nodeId.value }
                .map { node ->
                    val state = states.getValue(node.nodeId)
                    val activeLease = activeLeasesByNode[node.nodeId]
                    GraderViewNode(
                        nodeId = node.nodeId,
                        kind = node.kind,
                        title = node.title,
                        evaluationQuestion = node.evaluationQuestion,
                        receiverObligationRefs = node.receiverObligationRefs,
                        mappedWorkerNodeIds = node.mappedWorkerNodeIds,
                        status = state.status,
                        leasedByThisGroup = state.leasedByGraderGroupId == graderGroupId,
                        activeLeaseId = activeLease?.leaseId,
                        activeLeaseHash = activeLease?.leaseHash
                    )
                }
        )
    }

    /** Deterministically acquires one revealable open frontier node for [graderGroupId]. */
    suspend fun acquireNext(
        atlas: GraderAtlas,
        graderGroupId: String,
        round: Int,
        ttlMillis: Long
    ): GraderNodeLease = mutex.withLock {
        require(ttlMillis > 0) { "Grader node lease TTL must be positive" }
        initializeGroup(atlas, graderGroupId)
        expireDueLeases(atlas, graderGroupId)
        unlockSatisfiedNodes(atlas, graderGroupId)
        val candidates = atlas.nodes.values.filter { node ->
            repository.currentState(
                atlas.runId,
                atlas.atlasHash,
                graderGroupId,
                node.nodeId
            )?.status ==
                GraderNodeStatus.OPEN
        }
        val selected = candidates.minWithOrNull(
            compareBy(
                { AtlasHashing.hashText("$graderGroupId|$round|${it.nodeId.value}") },
                { it.nodeId.value }
            )
        ) ?: error("No revealable Grader Atlas node is available")
        acquire(atlas, selected.nodeId, graderGroupId, round, ttlMillis)
    }

    /** Commits a lease after the node's minimum requirement cardinality is satisfied. */
    suspend fun commit(
        atlas: GraderAtlas,
        lease: GraderNodeLease,
        committedRequirementHashes: Set<String>
    ): GraderNodeExecutionState = mutex.withLock {
        val state = requireActiveState(atlas, lease)
        require(now() < lease.expiresAtEpochMillis) { "Grader node lease has expired" }
        require(
            committedRequirementHashes.size >= atlas.nodes.getValue(lease.nodeId)
                .requiredRequirementCount
        ) {
            "Committed grader requirements do not satisfy node cardinality"
        }
        appendTerminalLease(lease, GraderNodeLeaseStatus.COMMITTED)
        repository.appendState(
            state.copy(
                revision = state.revision + 1,
                status = GraderNodeStatus.REQUIREMENTS_COMMITTED,
                leaseId = null,
                leasedByGraderGroupId = null,
                leaseExpiresAtEpochMillis = null,
                committedRequirementHashes = committedRequirementHashes,
                stateHash = ""
            ).withComputedHash()
        ).also {
            unlockSatisfiedNodes(atlas, lease.graderGroupId)
        }
    }

    /** Abandons an active lease without committing requirement authority. */
    suspend fun abandon(
        atlas: GraderAtlas,
        lease: GraderNodeLease
    ): GraderNodeExecutionState = mutex.withLock {
        val state = requireActiveState(atlas, lease)
        appendTerminalLease(lease, GraderNodeLeaseStatus.ABANDONED)
        repository.appendState(
            state.copy(
                revision = state.revision + 1,
                status = GraderNodeStatus.OPEN,
                leaseId = null,
                leasedByGraderGroupId = null,
                leaseExpiresAtEpochMillis = null,
                stateHash = ""
            ).withComputedHash()
        )
    }

    private suspend fun acquire(
        atlas: GraderAtlas,
        nodeId: GraderNodeId,
        graderGroupId: String,
        round: Int,
        ttlMillis: Long
    ): GraderNodeLease {
        val state = requireNotNull(
            repository.currentState(atlas.runId, atlas.atlasHash, graderGroupId, nodeId)
        )
        require(state.status == GraderNodeStatus.OPEN) {
            "Grader Atlas node is not open: ${nodeId.value}"
        }
        val acquiredAt = now()
        val lease = GraderNodeLease(
            leaseId = AtlasHashing.hashText(
                "grader-node-lease|${atlas.runId}|${atlas.atlasHash}|${nodeId.value}|" +
                    "$graderGroupId|$round|${state.revision}|$acquiredAt"
            ),
            runId = atlas.runId,
            graderAtlasHash = atlas.atlasHash,
            nodeId = nodeId,
            graderGroupId = graderGroupId,
            acquiredAtEpochMillis = acquiredAt,
            expiresAtEpochMillis = acquiredAt + ttlMillis
        ).withComputedHash()
        repository.appendLease(lease)
        repository.appendState(
            state.copy(
                revision = state.revision + 1,
                status = GraderNodeStatus.LEASED,
                leaseId = lease.leaseId,
                leasedByGraderGroupId = graderGroupId,
                leaseExpiresAtEpochMillis = lease.expiresAtEpochMillis,
                stateHash = ""
            ).withComputedHash()
        )
        return lease
    }

    private suspend fun requireActiveState(
        atlas: GraderAtlas,
        lease: GraderNodeLease
    ): GraderNodeExecutionState {
        require(lease.runId == atlas.runId && lease.graderAtlasHash == atlas.atlasHash) {
            "Lease does not belong to this Grader Atlas"
        }
        val state = requireNotNull(
            repository.currentState(
                atlas.runId,
                atlas.atlasHash,
                lease.graderGroupId,
                lease.nodeId
            )
        )
        require(
            state.status == GraderNodeStatus.LEASED &&
                state.leaseId == lease.leaseId &&
                state.leasedByGraderGroupId == lease.graderGroupId
        ) {
            "Lease is not the active node authority"
        }
        return state
    }

    private suspend fun expireDueLeases(atlas: GraderAtlas, graderGroupId: String) {
        atlas.nodes.keys.forEach { nodeId ->
            val state = repository.currentState(
                atlas.runId,
                atlas.atlasHash,
                graderGroupId,
                nodeId
            )
                ?: return@forEach
            val expiresAt = state.leaseExpiresAtEpochMillis
            if (state.status == GraderNodeStatus.LEASED &&
                expiresAt != null &&
                now() >= expiresAt
            ) {
                val lease = requireNotNull(
                    repository.leaseById(atlas.runId, requireNotNull(state.leaseId))
                ) {
                    "Active grader node state references an unknown lease"
                }
                appendTerminalLease(lease, GraderNodeLeaseStatus.EXPIRED)
                repository.appendState(
                    state.copy(
                        revision = state.revision + 1,
                        status = GraderNodeStatus.OPEN,
                        leaseId = null,
                        leasedByGraderGroupId = null,
                        leaseExpiresAtEpochMillis = null,
                        stateHash = ""
                    ).withComputedHash()
                )
            }
        }
    }

    private suspend fun unlockSatisfiedNodes(atlas: GraderAtlas, graderGroupId: String) {
        atlas.nodes.values.sortedBy { it.nodeId.value }.forEach { node ->
            val state = requireNotNull(
                repository.currentState(
                    atlas.runId,
                    atlas.atlasHash,
                    graderGroupId,
                    node.nodeId
                )
            )
            if (state.status == GraderNodeStatus.LOCKED &&
                node.prerequisiteNodeIds.all { prerequisite ->
                    repository.currentState(
                        atlas.runId,
                        atlas.atlasHash,
                        graderGroupId,
                        prerequisite
                    )
                        ?.status
                        .isCompleted()
                }
            ) {
                repository.appendState(
                    state.copy(
                        revision = state.revision + 1,
                        status = GraderNodeStatus.OPEN,
                        stateHash = ""
                    ).withComputedHash()
                )
            }
        }
    }

    private suspend fun appendTerminalLease(
        lease: GraderNodeLease,
        status: GraderNodeLeaseStatus
    ) {
        repository.appendLease(
            lease.copy(
                leaseId = "${lease.leaseId}:${status.name.lowercase()}",
                status = status,
                leaseHash = ""
            ).withComputedHash()
        )
    }

    private fun GraderNodeStatus?.isCompleted(): Boolean =
        this == GraderNodeStatus.REQUIREMENTS_COMMITTED ||
            this == GraderNodeStatus.VALIDATED
}
