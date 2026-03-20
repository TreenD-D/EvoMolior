package com.achulkov.evokammer.competition.resourcemap

import com.achulkov.evokammer.core.model.ActionType
import io.github.aakira.napier.Napier
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

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
 */
class ResourceMapEngine(
    private val resourceMap: ResourceMap,
    private val floorNodes: Map<String, List<String>> = emptyMap(),
    private val recaptureThreshold: Double = 0.05
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
    suspend fun evaluateClaims(qualityScores: Map<String, Map<ResourceType, Double>>): List<NodeClaimEvent> {
        return mutex.withLock {
            val events = mutableListOf<NodeClaimEvent>()

            for (node in resourceMap.all()) {
                // Floor nodes: always return to their assigned group
                val floorOwner = floorNodes.entries.firstOrNull { (_, nodeIds) -> node.id in nodeIds }?.key
                if (floorOwner != null) {
                    if (node.claimedByGroupId != floorOwner) {
                        resourceMap.claim(node.id, floorOwner)
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
                val scores: Map<String, Double> = qualityScores.mapValues { (_, domainScores) ->
                    domainScores[node.resourceType] ?: 0.0
                }

                if (scores.isEmpty()) continue

                val sorted = scores.entries.sortedByDescending { it.value }
                val challenger = sorted.first()
                val runnerUp = sorted.getOrNull(1)

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
                    resourceMap.claim(node.id, challenger.key)
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

    /** Manually release all nodes (e.g. at run end). */
    suspend fun releaseAll() {
        mutex.withLock {
            resourceMap.all().forEach { node ->
                // Don't release floor nodes
                val isFloor = floorNodes.values.any { node.id in it }
                if (!isFloor) resourceMap.release(node.id)
            }
        }
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
