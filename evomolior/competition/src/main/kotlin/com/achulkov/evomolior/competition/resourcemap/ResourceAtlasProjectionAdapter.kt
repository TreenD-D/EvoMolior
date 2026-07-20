package com.achulkov.evomolior.competition.resourcemap

import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.substrate.atlas.ResourceProjection
import com.achulkov.evomolior.substrate.atlas.ResourceProjectionNode

/**
 * Converts the legacy resource-map model to and from its Atlas-derived projection.
 */
object ResourceAtlasProjectionAdapter {

    /** Builds the exact resource projection used during Atlas migration. */
    fun toProjection(runId: String, nodes: List<ResourceNode>): ResourceProjection =
        ResourceProjection(
            runId = runId,
            nodes = nodes.map { node ->
                ResourceProjectionNode(
                    resourceNodeId = node.id,
                    atlasNodeId = NodeId(node.deliverableNodeRef ?: node.id),
                    name = node.name,
                    resourceType = node.resourceType.name,
                    yieldMultiplier = node.yieldMultiplier,
                    deliverableNodeRef = node.deliverableNodeRef,
                    scoringDimensionIds = node.scoringDimensionIds,
                    qualityThreshold = node.qualityThreshold,
                    floorGroupId = node.floorGroupId,
                    claimedByGroupId = node.claimedByGroupId
                )
            }
        )

    /** Restores ResourceMap consumer records from an Atlas projection. */
    fun fromProjection(projection: ResourceProjection): List<ResourceNode> =
        projection.nodes.map { node ->
            ResourceNode(
                id = node.resourceNodeId,
                name = node.name,
                resourceType = ResourceType.valueOf(node.resourceType),
                yieldMultiplier = node.yieldMultiplier,
                deliverableNodeRef = node.deliverableNodeRef,
                scoringDimensionIds = node.scoringDimensionIds,
                qualityThreshold = node.qualityThreshold,
                floorGroupId = node.floorGroupId,
                claimedByGroupId = node.claimedByGroupId
            )
        }
}
