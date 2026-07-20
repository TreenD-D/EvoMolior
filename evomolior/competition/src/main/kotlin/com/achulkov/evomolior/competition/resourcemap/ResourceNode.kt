package com.achulkov.evomolior.competition.resourcemap

import kotlinx.serialization.Serializable

/**
 * A resource node in the EvoMolior shared resource map.
 *
 * Resource nodes represent shared computational/data assets that groups compete to
 * "own" for a round, unlocking cost discounts for their action types.
 *
 * Nodes are contested: the group with the most [ActionType.TOOL_EXEC_SUCCESS] actions
 * at a node's [resourceType] at check-in time claims it for the next interval.
 *
 * @param id Unique node identifier.
 * @param name Human-readable label.
 * @param resourceType The type of resource this node provides (e.g. "WEB_CACHE", "GPU_BURST").
 * @param yieldMultiplier Discount multiplier applied to the owner group's relevant action cost.
 * @param deliverableNodeRef Deliverable part whose grades determine ownership.
 * @param scoringDimensionIds Dimension IDs used to compute the node-specific grade.
 * @param qualityThreshold Minimum normalized score required to claim an unowned node.
 * @param floorGroupId Optional immutable home owner.
 * @param claimedByGroupId The group that currently owns this node, or null if unclaimed.
 */
@Serializable
data class ResourceNode(
    val id: String,
    val name: String,
    val resourceType: ResourceType,
    val yieldMultiplier: Double,
    val deliverableNodeRef: String? = null,
    val scoringDimensionIds: List<String> = emptyList(),
    val qualityThreshold: Double = 0.0,
    val floorGroupId: String? = null,
    val claimedByGroupId: String? = null
)

/**
 * Types of resources available in the EvoMolior resource map.
 */
@Serializable
enum class ResourceType {
    /** Reduces WEB_FETCH costs for the owning group. */
    WEB_CACHE,

    /** Reduces LLM_CALL costs for the owning group (burst GPU access). */
    GPU_BURST,

    /** Reduces TOOL_EXEC cost for the owning group. */
    TOOL_CACHE,

    /** Reduces WRITE_STAGING cost for the owning group. */
    STAGING_SHORTCUT,

    /** Reduces INTER_AGENT_MESSAGE cost for the owning group. */
    GOSSIP_RELAY
}

/**
 * Tracks which group has claimed each [ResourceNode] and for how long.
 */
class ResourceMap(initialNodes: List<ResourceNode>) {

    private val nodes = initialNodes.associateBy { it.id }.toMutableMap()

    /**
     * Registers resource nodes before competition begins.
     *
     * Existing identifiers must describe the same immutable node definition. This keeps
     * coordinator yield calculation and ownership mutation on one shared map without
     * allowing startup wiring to silently replace a node.
     *
     * @param additionalNodes Nodes to add to the map.
     */
    fun register(additionalNodes: Collection<ResourceNode>) {
        additionalNodes.forEach { node ->
            val existing = nodes[node.id]
            require(existing == null || existing == node) {
                "Conflicting resource node registration: ${node.id}"
            }
            if (existing == null) {
                nodes[node.id] = node
            }
        }
    }

    /** Returns all nodes. */
    fun all(): List<ResourceNode> = nodes.values.toList()

    /** Returns nodes currently claimed by [groupId]. */
    fun claimedBy(groupId: String): List<ResourceNode> =
        nodes.values.filter { it.claimedByGroupId == groupId }

    /** Claims [nodeId] for [groupId], releasing any previous claim. */
    internal fun claim(nodeId: String, groupId: String) {
        val node = nodes[nodeId] ?: error("Unknown resource node: $nodeId")
        nodes[nodeId] = node.copy(claimedByGroupId = groupId)
    }

    /** Releases the claim on [nodeId]. */
    internal fun release(nodeId: String) {
        val node = nodes[nodeId] ?: return
        nodes[nodeId] = node.copy(claimedByGroupId = null)
    }

    /** Refreshes only ownership from an Atlas-derived projection. */
    internal fun restoreClaims(projectedNodes: Collection<ResourceNode>) {
        val projectedById = projectedNodes.associateBy(ResourceNode::id)
        require(projectedById.keys == nodes.keys) {
            "Atlas resource projection must cover every registered resource node"
        }
        nodes.replaceAll { nodeId, current ->
            val projected = projectedById.getValue(nodeId)
            require(
                current.copy(claimedByGroupId = null) ==
                    projected.copy(claimedByGroupId = null)
            ) {
                "Atlas resource projection changed immutable node $nodeId"
            }
            projected
        }
    }

    /**
     * Returns the effective cost multiplier for a [groupId] and [resourceType].
     * If the group owns a node of this type, returns its [ResourceNode.yieldMultiplier],
     * otherwise returns 1.0 (no discount).
     */
    fun multiplierFor(groupId: String, resourceType: ResourceType): Double {
        return claimedBy(groupId)
            .firstOrNull { it.resourceType == resourceType }
            ?.yieldMultiplier
            ?: 1.0
    }
}
