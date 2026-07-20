package com.achulkov.evomolior.core.atlas

import kotlinx.serialization.Serializable

/**
 * Append-only metadata for a persisted Atlas version.
 *
 * @param atlasId Stable Atlas ID.
 * @param version Version number.
 * @param atlasHash Hash of the stored Atlas.
 * @param parentAtlasHash Immediate parent hash.
 * @param amendmentIds Amendments incorporated by this version.
 * @param createdAtEpochMillis Creation timestamp.
 */
@Serializable
data class AtlasVersion(
    val atlasId: String,
    val version: Int,
    val atlasHash: String,
    val parentAtlasHash: String? = null,
    val amendmentIds: List<String> = emptyList(),
    val createdAtEpochMillis: Long
)

/**
 * Append-only proposal for changing a later Atlas version.
 *
 * @param amendmentId Stable amendment ID.
 * @param parentAtlasHash Atlas hash against which the proposal was made.
 * @param proposedBy Proposing agent.
 * @param proposalType Allowed amendment category.
 * @param addedNodes New immutable nodes.
 * @param addedEdges New graph edges.
 * @param justificationArtifactIds Supporting artifacts.
 * @param graderDecision Grader authority decision.
 * @param effectiveRound Earliest effective round.
 */
@Serializable
data class AtlasAmendment(
    val amendmentId: String,
    val parentAtlasHash: String,
    val proposedBy: AtlasAgentId,
    val proposalType: AmendmentType,
    val addedNodes: List<LimitationNode> = emptyList(),
    val addedEdges: List<NodeEdge> = emptyList(),
    val justificationArtifactIds: List<String>,
    val graderDecision: AmendmentDecision = AmendmentDecision.PENDING,
    val effectiveRound: Int
)

/** Permitted Atlas amendment categories. */
@Serializable
enum class AmendmentType { ALTERNATIVE, VALIDATION, MISSING_LAYER, TOOL_OPPORTUNITY }

/** Grader authority decision on an Atlas amendment. */
@Serializable
enum class AmendmentDecision { PENDING, ACCEPTED, REJECTED }

/**
 * Directed edge added by an amendment.
 *
 * @param fromNodeId Edge source.
 * @param toNodeId Edge target.
 * @param kind Edge semantics.
 */
@Serializable
data class NodeEdge(
    val fromNodeId: NodeId,
    val toNodeId: NodeId,
    val kind: NodeEdgeKind
)

/** Semantics of an Atlas node edge. */
@Serializable
enum class NodeEdgeKind { PARENT, PREREQUISITE, ALTERNATIVE }
