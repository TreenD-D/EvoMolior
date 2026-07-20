package com.achulkov.evomolior.substrate.atlas

import com.achulkov.evomolior.core.atlas.DisclosureStage
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.contract.DeliverableSkeletonTree
import kotlinx.serialization.Serializable

/** Legacy-compatible views derived within an Atlas transaction. */
@Serializable
data class AtlasProjectionSet(
    val wip: List<WipProjection>,
    val deliverables: List<DeliverableSkeletonTree>,
    val resources: ResourceProjection,
    val disclosure: DisclosureProjection,
    val mappings: List<LegacyNodeMapping>
)

/**
 * Claimable work projection for one competing group.
 */
@Serializable
data class WipProjection(
    val runId: String,
    val groupId: String,
    val tasks: List<WipProjectionTask>
)

/**
 * Lossless projection of a legacy WIP row.
 */
@Serializable
data class WipProjectionTask(
    val taskId: String,
    val atlasNodeId: NodeId,
    val description: String,
    val claimedByAgentId: String? = null,
    val status: String = "unclaimed",
    val lastChangedGeneration: Int,
    val abandonCount: Int = 0,
    val linkedNodeId: String? = null,
    val deliverableNodeRef: String? = null
)

/**
 * Run-global capture and yield projection.
 */
@Serializable
data class ResourceProjection(
    val runId: String,
    val nodes: List<ResourceProjectionNode>
)

/**
 * Lossless resource-map node view bound to one Atlas node.
 */
@Serializable
data class ResourceProjectionNode(
    val resourceNodeId: String,
    val atlasNodeId: NodeId,
    val name: String,
    val resourceType: String,
    val yieldMultiplier: Double,
    val deliverableNodeRef: String? = null,
    val scoringDimensionIds: List<String> = emptyList(),
    val qualityThreshold: Double = 0.0,
    val floorGroupId: String? = null,
    val claimedByGroupId: String? = null
)

/**
 * Group-local progressive-disclosure view.
 */
@Serializable
data class DisclosureProjection(
    val runId: String,
    val stages: List<DisclosureStage>,
    val groupStageIndices: Map<String, Int>,
    val groupLeakMultipliers: Map<String, Double> = emptyMap(),
    val legacyStages: List<LegacyDisclosureStageProjection> = emptyList()
)

/**
 * Exact legacy stage content retained while competition consumers migrate to node payload views.
 */
@Serializable
data class LegacyDisclosureStageProjection(
    val stageIndex: Int,
    val title: String,
    val constraintFramedContent: String,
    val goalFramedContent: String,
    val unlockCondition: String,
    val isInitial: Boolean
)
/** Origin of a legacy node identity mapped into the Atlas. */
@Serializable
enum class LegacyNodeSource {
    WIP,
    DELIVERABLE,
    RESOURCE
}

/**
 * Auditable mapping from one legacy node identity to exactly one Atlas identity.
 */
@Serializable
data class LegacyNodeMapping(
    val source: LegacyNodeSource,
    val sourceNodeId: String,
    val scopeId: String,
    val atlasNodeId: NodeId
)

/** Projection categories persisted by the Atlas authority store. */
enum class AtlasProjectionType {
    WIP,
    DELIVERABLE,
    RESOURCE,
    DISCLOSURE,
    MIGRATION_MAPPING
}
