package com.achulkov.evokammer.core.contract

import com.achulkov.evokammer.core.model.Role
import kotlinx.serialization.Serializable

/**
 * Persistent, compact structure describing the evolving deliverable for one group.
 *
 * The deliverable skeleton is separate from KB/KG content: it tracks semantic structure,
 * ownership, trust, and grading state while storing no heavy artefact payloads.
 *
 * @param runId Run that owns this tree.
 * @param groupId Group that owns this tree.
 * @param rootNodeId Synthetic root node identifier.
 * @param nodes All nodes in the tree, including the root.
 * @param version Monotonic tree version.
 * @param createdAtMs Epoch ms when the tree was created.
 * @param updatedAtMs Epoch ms when the tree was last mutated.
 */
@Serializable
data class DeliverableSkeletonTree(
    val runId: String,
    val groupId: String,
    val rootNodeId: String = DEFAULT_ROOT_NODE_ID,
    val nodes: List<DeliverableSkeletonNode>,
    val version: Int = 1,
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis()
) {
    /**
     * Returns a compact summary suitable for selector heuristics and prompt injection.
     */
    fun summarize(): DeliverableSkeletonTreeSummary {
        val statusCounts = DeliverableSkeletonNodeStatus.entries.associateWith { status ->
            nodes.count { it.status == status }
        }
        val openGaps = nodes.sumOf { it.openGaps.size }
        val trustValues = nodes.map { it.trustScore }
        val owningRoles = nodes.mapNotNull { it.owningRole }
        val owningRoleCounts = owningRoles.groupingBy { it }.eachCount()
        val averageTrust = if (trustValues.isEmpty()) 0.0 else trustValues.average()
        val ownershipClarityScore = if (nodes.isEmpty()) 0.0 else owningRoles.size.toDouble() / nodes.size.toDouble()
        return DeliverableSkeletonTreeSummary(
            runId = runId,
            groupId = groupId,
            totalNodes = nodes.size,
            statusCounts = statusCounts,
            openGapCount = openGaps,
            contestedNodeCount = nodes.count { it.status == DeliverableSkeletonNodeStatus.CONTESTED },
            deadEndCount = nodes.count { it.status == DeliverableSkeletonNodeStatus.DEAD_END },
            unownedNodeCount = nodes.count { it.owningRole == null },
            owningRoleCounts = owningRoleCounts,
            ownershipClarityScore = ownershipClarityScore,
            averageTrustScore = averageTrust,
            maxComplexityLevel = nodes.maxOfOrNull { it.graderComplexityLevel } ?: 0
        )
    }

    /**
     * Returns a copy of this tree with the given node replaced and the version incremented.
     *
     * @param updatedNode Replacement node.
     * @return Tree copy containing the replacement.
     */
    fun replaceNode(updatedNode: DeliverableSkeletonNode): DeliverableSkeletonTree = copy(
        nodes = nodes.map { node -> if (node.nodeId == updatedNode.nodeId) updatedNode else node },
        version = version + 1,
        updatedAtMs = System.currentTimeMillis()
    )

    companion object {
        const val DEFAULT_ROOT_NODE_ID: String = "dst:root"

        /**
         * Builds a minimal tree from a raw node-graph JSON seed.
         *
         * The fallback tree uses a synthetic root plus one child per node-graph entry.
         */
        fun fromNodeGraph(
            runId: String,
            groupId: String,
            nodeGraphJson: String,
            objective: String = ""
        ): DeliverableSkeletonTree {
            val entries = DeliverableSkeletonTreeJson.decodeNodeGraph(nodeGraphJson)
            val root = DeliverableSkeletonNode(
                nodeId = DEFAULT_ROOT_NODE_ID,
                parentId = null,
                children = entries.map { it.nodeId },
                nodeType = DeliverableSkeletonNodeType.ROOT,
                title = if (objective.isBlank()) "Deliverable Root" else objective,
                pathHint = null,
                status = DeliverableSkeletonNodeStatus.STUB,
                currentArtifactRef = null,
                artifactVersion = 0,
                artifactType = DeliverableArtifactType.OTHER,
                owningGeneration = 0,
                owningAgentId = null,
                owningRole = defaultOwningRoleForNodeType(DeliverableSkeletonNodeType.ROOT),
                trustScore = 0.0,
                graderComplexityLevel = 1,
                gradingHistory = emptyList(),
                openGaps = emptyList(),
                deadEndRefs = emptyList(),
                citationAgentChain = emptyList(),
                createdAtGeneration = 0,
                updatedAtGeneration = 0
            )

            val nodes = buildList {
                add(root)
                entries.forEachIndexed { index, entry ->
                    val nodeType = DeliverableSkeletonTreeJson.parseNodeType(entry.nodeType)
                    add(
                        DeliverableSkeletonNode(
                            nodeId = entry.nodeId,
                            parentId = DEFAULT_ROOT_NODE_ID,
                            children = emptyList(),
                            nodeType = nodeType,
                            title = entry.title.ifBlank { entry.nodeId },
                            pathHint = entry.pathHint,
                            status = DeliverableSkeletonNodeStatus.STUB,
                            currentArtifactRef = null,
                            artifactVersion = 0,
                            artifactType = DeliverableArtifactType.OTHER,
                            owningGeneration = 0,
                            owningAgentId = null,
                            owningRole = defaultOwningRoleForNodeType(nodeType),
                            trustScore = 0.0,
                            graderComplexityLevel = entry.expectedComplexityLevel ?: (index % 3) + 1,
                            gradingHistory = emptyList(),
                            openGaps = emptyList(),
                            deadEndRefs = emptyList(),
                            citationAgentChain = emptyList(),
                            createdAtGeneration = 0,
                            updatedAtGeneration = 0
                        )
                    )
                }
            }

            return DeliverableSkeletonTree(
                runId = runId,
                groupId = groupId,
                nodes = nodes
            )
        }

        /**
         * Returns the default owner role for a given node type.
         *
         * This keeps the deliverable skeleton explicitly owned even before agents start
         * claiming work, which improves role clarity for decomposition-heavy tasks.
         */
        fun defaultOwningRoleForNodeType(nodeType: DeliverableSkeletonNodeType): Role? = when (nodeType) {
            DeliverableSkeletonNodeType.ROOT -> Role.Planner
            DeliverableSkeletonNodeType.DOCUMENT_SECTION -> Role.Researcher
            DeliverableSkeletonNodeType.SOURCE_FILE -> Role.Builder
            DeliverableSkeletonNodeType.TOOL -> Role.Builder
            DeliverableSkeletonNodeType.TEST_SUITE -> Role.Critic
            DeliverableSkeletonNodeType.API_SPEC -> Role.Builder
            DeliverableSkeletonNodeType.ARTIFACT_BUNDLE -> Role.Planner
            DeliverableSkeletonNodeType.DATASET -> Role.Researcher
            DeliverableSkeletonNodeType.OTHER -> Role.Planner
        }
    }
}

/**
 * Compact summary of a deliverable skeleton tree.
 *
 * @param runId Run that owns the tree.
 * @param groupId Group that owns the tree.
 * @param totalNodes Total number of tree nodes.
 * @param statusCounts Count by node status.
 * @param openGapCount Total open gaps across all nodes.
 * @param contestedNodeCount Number of contested nodes.
 * @param deadEndCount Number of dead-end nodes.
 * @param unownedNodeCount Number of nodes without an explicit owning role.
 * @param owningRoleCounts Count of nodes by owning role.
 * @param ownershipClarityScore Fraction of nodes with an explicit owning role.
 * @param averageTrustScore Average trust score across nodes.
 * @param maxComplexityLevel Highest rubric complexity seen in the tree.
 */
@Serializable
data class DeliverableSkeletonTreeSummary(
    val runId: String,
    val groupId: String,
    val totalNodes: Int,
    val statusCounts: Map<DeliverableSkeletonNodeStatus, Int>,
    val openGapCount: Int,
    val contestedNodeCount: Int,
    val deadEndCount: Int,
    val unownedNodeCount: Int,
    val owningRoleCounts: Map<Role, Int>,
    val ownershipClarityScore: Double,
    val averageTrustScore: Double,
    val maxComplexityLevel: Int
)

/**
 * A single node in the deliverable skeleton tree.
 *
 * @param nodeId Stable node identifier.
 * @param parentId Parent node identifier, or null for the root.
 * @param children Direct child node IDs.
 * @param nodeType Semantic node type.
 * @param title Human-readable node title.
 * @param pathHint Optional path/anchor hint for artefact synthesis.
 * @param status Current node status.
 * @param currentArtifactRef Canonical artefact pointer, if any.
 * @param artifactVersion Semantic version number for the current artefact.
 * @param artifactType Type of the current artefact.
 * @param owningGeneration Generation that most recently claimed this node.
 * @param owningAgentId Agent that most recently claimed this node.
 * @param owningRole Role primarily responsible for this node.
 * @param trustScore Current trust score.
 * @param graderComplexityLevel Current rubric complexity level.
 * @param gradingHistory Recent grading history, newest last.
 * @param openGaps Outstanding work items for this node.
 * @param deadEndRefs References to documented dead ends for this node.
 * @param citationAgentChain Agent chain that contributed to this node.
 * @param createdAtGeneration Generation the node was created.
 * @param updatedAtGeneration Generation the node was last updated.
 */
@Serializable
data class DeliverableSkeletonNode(
    val nodeId: String,
    val parentId: String? = null,
    val children: List<String> = emptyList(),
    val nodeType: DeliverableSkeletonNodeType = DeliverableSkeletonNodeType.OTHER,
    val title: String,
    val pathHint: String? = null,
    val status: DeliverableSkeletonNodeStatus = DeliverableSkeletonNodeStatus.STUB,
    val currentArtifactRef: String? = null,
    val artifactVersion: Int = 0,
    val artifactType: DeliverableArtifactType = DeliverableArtifactType.OTHER,
    val owningGeneration: Int = 0,
    val owningAgentId: String? = null,
    val owningRole: Role? = null,
    val trustScore: Double = 0.0,
    val graderComplexityLevel: Int = 1,
    val gradingHistory: List<DeliverableNodeGradeHistoryEntry> = emptyList(),
    val openGaps: List<String> = emptyList(),
    val deadEndRefs: List<String> = emptyList(),
    val citationAgentChain: List<String> = emptyList(),
    val createdAtGeneration: Int = 0,
    val updatedAtGeneration: Int = 0
)

/**
 * A recent grading event attached to a deliverable node.
 *
 * @param checkinId Check-in that produced the grading result.
 * @param dimensionScores Per-dimension scores keyed by rubric dimension ID.
 * @param graderIds Grader agents that contributed to the signal.
 * @param rubricVersion Rubric version used for the grading pass.
 * @param graderNotes Free-text notes from graders.
 * @param passedThreshold Whether the node met the current threshold.
 * @param recordedAtMs Epoch ms when the entry was recorded.
 */
@Serializable
data class DeliverableNodeGradeHistoryEntry(
    val checkinId: String,
    val dimensionScores: Map<String, Double>,
    val graderIds: List<String>,
    val rubricVersion: String,
    val graderNotes: List<String> = emptyList(),
    val passedThreshold: Boolean,
    val recordedAtMs: Long = System.currentTimeMillis()
)

/**
 * A node update synthesized from grader results or critic promotion signals.
 *
 * @param dstNodeRef Node ID to update.
 * @param groupId Group that owns the node.
 * @param checkinId Optional check-in that generated the update.
 * @param dimensionScores Per-dimension scores.
 * @param graderIds Grader contributors.
 * @param rubricVersion Rubric version.
 * @param graderNotes Notes to convert into open gaps.
 * @param passedThreshold Whether the node passed the grading threshold.
 * @param artifactRef Optional promoted artefact reference.
 * @param artifactType Optional promoted artefact type.
 * @param trustScore Optional updated trust score.
 * @param owningGeneration Optional updated generation.
 * @param owningAgentId Optional updated agent.
 * @param owningRole Optional updated owning role.
 */
@Serializable
data class DeliverableSkeletonNodeUpdate(
    val dstNodeRef: String,
    val groupId: String? = null,
    val checkinId: String? = null,
    val dimensionScores: Map<String, Double> = emptyMap(),
    val graderIds: List<String> = emptyList(),
    val rubricVersion: String? = null,
    val graderNotes: List<String> = emptyList(),
    val passedThreshold: Boolean? = null,
    val artifactRef: String? = null,
    val artifactType: DeliverableArtifactType? = null,
    val trustScore: Double? = null,
    val owningGeneration: Int? = null,
    val owningAgentId: String? = null,
    val owningRole: Role? = null
)

/** Compact node type used in the skeleton tree. */
@Serializable
enum class DeliverableSkeletonNodeType {
    ROOT,
    DOCUMENT_SECTION,
    SOURCE_FILE,
    TOOL,
    TEST_SUITE,
    API_SPEC,
    ARTIFACT_BUNDLE,
    DATASET,
    OTHER
}

/** Lifecycle status for a deliverable node. */
@Serializable
enum class DeliverableSkeletonNodeStatus {
    STUB,
    IN_PROGRESS,
    COMPLETE,
    CONTESTED,
    DEAD_END
}

/** Type of artefact currently attached to a node. */
@Serializable
enum class DeliverableArtifactType {
    KB_DOC,
    TOOL,
    REPO_SNAPSHOT,
    TEST_SUITE,
    OTHER
}

// ── Phase 7.5: DST Refinement (Grader-driven structural evolution) ──────────

/** Kinds of structural refinement that graders can propose. */
@Serializable
enum class DstRefinementKind {
    /** Split one node into multiple children. */
    SPLIT_NODE,
    /** Merge multiple sibling nodes into one. */
    MERGE_NODES,
    /** Change the node type (e.g. DOCUMENT_SECTION → API_SPEC). */
    RETYPE_NODE
}

/**
 * A grader-proposed structural change to the deliverable skeleton tree.
 *
 * Proposals originate from Grader Group GradingSheets and are evaluated by the
 * Meta-Judge. Only proposals with multi-grader consensus are accepted.
 *
 * @param kind What kind of structural change.
 * @param targetNodeRef The node to split, retype, or one of the nodes to merge.
 * @param mergeNodeRefs Additional node refs merged with [targetNodeRef] (MERGE_NODES only).
 * @param childSpecs New child node specifications (SPLIT_NODE only). Each entry has a
 *   nodeId, title, pathHint, and nodeType.
 * @param newType Target node type (RETYPE_NODE only).
 * @param newTitle Optional new title for the resulting node (MERGE_NODES, RETYPE_NODE).
 * @param rationale Human-readable justification from the grader.
 * @param proposedByGraderGroupId Grader group that authored the proposal.
 */
@Serializable
data class DstRefinementProposal(
    val kind: DstRefinementKind,
    val targetNodeRef: String,
    val mergeNodeRefs: List<String> = emptyList(),
    val childSpecs: List<DstChildSpec> = emptyList(),
    val newType: DeliverableSkeletonNodeType? = null,
    val newTitle: String? = null,
    val rationale: String = "",
    val proposedByGraderGroupId: String = ""
)

/** Specification for a new child node created during SPLIT_NODE. */
@Serializable
data class DstChildSpec(
    val nodeId: String,
    val title: String,
    val pathHint: String? = null,
    val nodeType: DeliverableSkeletonNodeType = DeliverableSkeletonNodeType.OTHER,
    val graderComplexityLevel: Int = 1
)

/**
 * The result of applying one or more refinement proposals.
 *
 * @param acceptedProposals Proposals the Meta-Judge accepted and the refiner applied.
 * @param rejectedProposals Proposals the Meta-Judge rejected with reasons.
 * @param treeVersionAfter Version of the tree after all accepted changes.
 * @param structuralChangesCount Total accepted proposals applied.
 */
@Serializable
data class DstRefinementResult(
    val acceptedProposals: List<DstRefinementProposal> = emptyList(),
    val rejectedProposals: List<DstRefinementRejection> = emptyList(),
    val treeVersionAfter: Int = 0,
    val structuralChangesCount: Int = 0
)

/** A rejected refinement proposal with the rejection reason. */
@Serializable
data class DstRefinementRejection(
    val proposal: DstRefinementProposal,
    val reason: String
)

// ── Phase 7.5: Rubric Policy (per-node-type grading rules) ───────────────────

/**
 * A global rubric policy that applies to all DST nodes of a given type.
 *
 * Stored in a `dst_rubric_policies` logical table. The DST Update Service uses
 * these policies when computing grader complexity levels and open gaps.
 *
 * @param policyId Unique policy identifier.
 * @param nodeType Node type this policy targets.
 * @param description Human-readable policy description.
 * @param requiredDimensions Rubric dimension IDs that become mandatory for nodes of this type.
 * @param minComplexityLevel Minimum grader complexity level for nodes of this type.
 * @param enactedAtRound Round index when this policy was enacted.
 * @param enactedByGraderGroupId Grader group that proposed and Meta-Judge accepted this policy.
 */
@Serializable
data class DstRubricPolicy(
    val policyId: String,
    val nodeType: DeliverableSkeletonNodeType,
    val description: String,
    val requiredDimensions: List<String> = emptyList(),
    val minComplexityLevel: Int = 2,
    val enactedAtRound: Int = 1,
    val enactedByGraderGroupId: String = ""
)

@Serializable
private data class DeliverableSkeletonTreeNodeGraphEntry(
    val nodeId: String,
    val title: String = "",
    val description: String = "",
    val pathHint: String? = null,
    val nodeType: String? = null,
    val expectedComplexityLevel: Int? = null
)

private object DeliverableSkeletonTreeJson {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }

    fun decodeNodeGraph(raw: String): List<DeliverableSkeletonTreeNodeGraphEntry> {
        if (raw.isBlank() || raw.trim() == "{}") return emptyList()
        return runCatching {
            when {
                raw.trimStart().startsWith("[") -> json.decodeFromString(
                    kotlinx.serialization.builtins.ListSerializer(DeliverableSkeletonTreeNodeGraphEntry.serializer()),
                    raw
                )
                else -> emptyList()
            }
        }.getOrDefault(emptyList())
    }

    fun parseNodeType(raw: String?): DeliverableSkeletonNodeType =
        runCatching { raw?.let { DeliverableSkeletonNodeType.valueOf(it.trim().uppercase()) } }
            .getOrNull() ?: DeliverableSkeletonNodeType.OTHER
}
