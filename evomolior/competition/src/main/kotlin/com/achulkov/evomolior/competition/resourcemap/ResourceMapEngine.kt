package com.achulkov.evomolior.competition.resourcemap

import com.achulkov.evomolior.core.model.ActionType
import io.github.aakira.napier.Napier
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/** Explicit compatibility capability for pre-Atlas capture mechanics. */
enum class LegacyResourceCaptureMode {
    TEST_COMPATIBILITY_ONLY
}

/**
 * Phase 4 — Resource Map Engine.
 *
 * Manages the shared resource node graph where competing groups compete to claim nodes
 * in exchange for action-cost discounts (yield multipliers). Claim mechanics are driven
 * by check-in quality scores: the group with the highest weighted score for a node's domain
 * at each check-in wins the node for the next interval.
 *
 * - Each group has **guaranteed floor nodes** (immutable home territory) that can never
 *   be captured by other groups, but yield at a reduced fixed rate.
 * - Contested nodes are recaptured if the challenger's quality score exceeds the current
 *   holder's by [recaptureThreshold] percentage points.
 * - [ResourceMap] state is mutated atomically under a [Mutex].
 *
 * @param resourceMap The shared mutable resource node map.
 * @param floorNodes Map of groupId → list of node IDs guaranteed to that group (immutable).
 * @param recaptureThreshold Minimum quality delta (0.0–1.0) required to recapture a node.
 * @param ownershipAuthority Transaction boundary invoked before local projection state changes.
 */
class ResourceMapEngine(
    private val resourceMap: ResourceMap,
    private val floorNodes: Map<String, List<String>> = emptyMap(),
    private val recaptureThreshold: Double = 0.05,
    private val ownershipAuthority: ResourceOwnershipAuthority
) {
    private val mutex = Mutex()

    /**
     * Runs the node claim evaluation at check-in time.
     *
     * Each contested node is awarded to the group with the highest [qualityScores] value
     * for that node's [ResourceType]. Floor nodes are always returned to their owner group.
     *
     * @param qualityScores Map of groupId → per-domain quality score (0.0–1.0).
     * @return List of [NodeClaimEvent] representing all ownership changes.
     */
    suspend fun evaluateClaims(
        qualityScores: Map<String, Map<ResourceType, Double>>,
        legacyMode: LegacyResourceCaptureMode
    ): List<NodeClaimEvent> {
        val nodeScores = qualityScores.mapValues { (_, scoresByType) ->
            resourceMap.all().associate { node ->
                node.id to (scoresByType[node.resourceType] ?: 0.0)
            }
        }
        return evaluateNodeClaims(nodeScores, legacyMode)
    }

    /**
     * Awards each capture node from grades for its explicitly mapped deliverable part.
     *
     * @param qualityScores Map of group ID to resource-node ID to normalized score.
     * @return Ownership changes produced by this check-in.
     */
    suspend fun evaluateNodeClaims(
        qualityScores: Map<String, Map<String, Double>>,
        legacyMode: LegacyResourceCaptureMode
    ): List<NodeClaimEvent> {
        require(legacyMode == LegacyResourceCaptureMode.TEST_COMPATIBILITY_ONLY)
        return mutex.withLock {
            val events = mutableListOf<NodeClaimEvent>()

            for (node in resourceMap.all()) {
                // Floor nodes: always return to their assigned group
                val floorOwner = node.floorGroupId
                    ?: floorNodes.entries.firstOrNull { (_, nodeIds) -> node.id in nodeIds }?.key
                if (floorOwner != null) {
                    if (node.claimedByGroupId != floorOwner) {
                        events += NodeClaimEvent(
                            nodeId = node.id,
                            resourceType = node.resourceType,
                            previousOwnerId = node.claimedByGroupId,
                            newOwnerId = floorOwner,
                            isFloorReclaim = true,
                            winnerScore = 1.0,
                            runnerUpScore = 0.0
                        )
                    }
                    continue
                }

                // Contested node: find the group with the highest score for this resource type
                val scores: Map<String, Double> = qualityScores.mapValues { (_, nodeScores) ->
                    nodeScores[node.id] ?: 0.0
                }

                if (scores.isEmpty()) continue

                val sorted = scores.entries.sortedWith(
                    compareByDescending<Map.Entry<String, Double>> { it.value }
                        .thenBy { it.key }
                )
                val challenger = sorted.first()
                val runnerUp = sorted.getOrNull(1)
                if (challenger.value < node.qualityThreshold) continue

                val currentOwner = node.claimedByGroupId

                val shouldClaim = when {
                    // Unclaimed node: any group can claim it
                    currentOwner == null -> true
                    // Current owner retains unless challenger exceeds threshold
                    challenger.key == currentOwner -> false
                    else -> {
                        val ownerScore = scores[currentOwner] ?: 0.0
                        challenger.value - ownerScore >= recaptureThreshold
                    }
                }

                if (shouldClaim && challenger.key != currentOwner) {
                    Napier.d("ResourceMapEngine: ${challenger.key} claims node '${node.name}' (${node.resourceType}) " +
                            "score=${challenger.value} prev=${currentOwner ?: "none"}")
                    events += NodeClaimEvent(
                        nodeId = node.id,
                        resourceType = node.resourceType,
                        previousOwnerId = currentOwner,
                        newOwnerId = challenger.key,
                        isFloorReclaim = false,
                        winnerScore = challenger.value,
                        runnerUpScore = runnerUp?.value ?: 0.0
                    )
                }
            }

            commitOwnershipEvents(events)
            events
        }
    }

    /**
     * Applies explicit WiP completion claims to the shared resource map.
     *
     * This is the canonical ownership path for completed work. A task-linked submission
     * has been promoted, so the corresponding node is now claimed by the submitting group.
     *
     * @param completedNodeIds Map of groupId → completed task/node IDs.
     * @return List of [NodeClaimEvent] emitted for ownership changes.
     */
    suspend fun applyWipCompletions(
        completedNodeIds: Map<String, Collection<String>>,
        legacyMode: LegacyResourceCaptureMode
    ): List<NodeClaimEvent> {
        require(legacyMode == LegacyResourceCaptureMode.TEST_COMPATIBILITY_ONLY)
        return mutex.withLock {
            val events = mutableListOf<NodeClaimEvent>()

            completedNodeIds.forEach { (groupId, nodeIds) ->
                nodeIds.distinct().forEach { nodeId ->
                    val node = resourceMap.all().firstOrNull { it.id == nodeId }
                    if (node == null) {
                        Napier.w("ResourceMapEngine: completion references unknown resource node '$nodeId' for $groupId")
                        return@forEach
                    }
                    val floorOwner = node.floorGroupId
                        ?: floorNodes.entries.firstOrNull { (_, nodeIdsForGroup) -> node.id in nodeIdsForGroup }?.key
                    val targetOwner = floorOwner ?: groupId
                    val previousOwner = node.claimedByGroupId
                    if (previousOwner == targetOwner) return@forEach

                    Napier.d("ResourceMapEngine: $targetOwner claims completed node '${node.name}' (${node.resourceType}) prev=${previousOwner ?: "none"}")
                    events += NodeClaimEvent(
                        nodeId = node.id,
                        resourceType = node.resourceType,
                        previousOwnerId = previousOwner,
                        newOwnerId = targetOwner,
                        isFloorReclaim = floorOwner != null,
                        winnerScore = 1.0,
                        runnerUpScore = 0.0
                    )
                }
            }

            commitOwnershipEvents(events)
            events
        }
    }

    /**
     * Returns the cost multiplier for a [groupId] performing [actionType].
     *
     * Checks all nodes claimed by the group and returns the minimum multiplier
     * (i.e., the best applicable discount). If no relevant node is held, returns 1.0.
     *
     * Called by [BudgetLedger] before deducting action costs.
     */
    fun costMultiplierFor(groupId: String, actionType: ActionType): Double {
        val relevantResourceType = actionType.toResourceType() ?: return 1.0
        return resourceMap.multiplierFor(groupId, relevantResourceType)
    }

    /** Returns all current node claims (for dashboard display). */
    fun snapshotClaims(): List<ResourceNode> = resourceMap.all()

    /** Refreshes the compatibility view after an authoritative Atlas commit. */
    suspend fun restoreAtlasProjection(nodes: Collection<ResourceNode>) {
        mutex.withLock {
            resourceMap.restoreClaims(nodes)
        }
    }

    /** Manually release all nodes (e.g. at run end). */
    suspend fun releaseAll() {
        mutex.withLock {
            val releasable = resourceMap.all().filter { node ->
                // Don't release floor nodes
                val isFloor = node.floorGroupId != null || floorNodes.values.any { node.id in it }
                !isFloor && node.claimedByGroupId != null
            }
            if (releasable.isNotEmpty()) {
                ownershipAuthority.commit(
                    releasable.map { node ->
                        ResourceOwnershipChange(
                            nodeId = node.id,
                            previousOwnerId = node.claimedByGroupId,
                            newOwnerId = null
                        )
                    }
                )
                releasable.forEach { node -> resourceMap.release(node.id) }
            }
        }
    }

    private suspend fun commitOwnershipEvents(events: List<NodeClaimEvent>) {
        if (events.isEmpty()) return
        ownershipAuthority.commit(
            events.map { event ->
                ResourceOwnershipChange(
                    nodeId = event.nodeId,
                    previousOwnerId = event.previousOwnerId,
                    newOwnerId = event.newOwnerId
                )
            }
        )
        events.forEach { event -> resourceMap.claim(event.nodeId, event.newOwnerId) }
    }
}

/**
 * Maps an [ActionType] to the [ResourceType] that can discount it.
 * Returns null if no resource node affects this action type.
 */
private fun ActionType.toResourceType(): ResourceType? = when (this) {
    ActionType.TOOL_EXEC_SUCCESS,
    ActionType.TOOL_EXEC_FAILURE,
    ActionType.TOOL_EXEC_RETRY -> ResourceType.TOOL_CACHE

    ActionType.LLM_CALL -> ResourceType.GPU_BURST
    ActionType.WRITE_STAGING -> ResourceType.STAGING_SHORTCUT
    ActionType.INTER_AGENT_MESSAGE -> ResourceType.GOSSIP_RELAY
    else -> null
}

/** Records a node ownership change at a check-in boundary. */
@Serializable
data class NodeClaimEvent(
    val nodeId: String,
    val resourceType: ResourceType,
    val previousOwnerId: String?,
    val newOwnerId: String,
    val isFloorReclaim: Boolean,
    val winnerScore: Double,
    val runnerUpScore: Double
)

/**
 * Ownership transition that must commit before the mutable ResourceMap projection changes.
 */
data class ResourceOwnershipChange(
    val nodeId: String,
    val previousOwnerId: String?,
    val newOwnerId: String?
)

/**
 * Transaction authority for resource ownership changes.
 */
fun interface ResourceOwnershipAuthority {
    /**
     * Commits [changes] or throws, in which case the local ResourceMap remains unchanged.
     */
    suspend fun commit(changes: List<ResourceOwnershipChange>)
}

/**
 * Explicit non-authoritative adapter for isolated legacy unit tests only.
 *
 * Production composition must use the run's Atlas-backed authority.
 */
object LocalProjectionResourceOwnershipAuthority : ResourceOwnershipAuthority {
    override suspend fun commit(changes: List<ResourceOwnershipChange>) = Unit
}
