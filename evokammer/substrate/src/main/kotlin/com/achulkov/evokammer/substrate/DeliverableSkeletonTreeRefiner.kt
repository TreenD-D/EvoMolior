package com.achulkov.evokammer.substrate

import com.achulkov.evokammer.core.contract.DeliverableSkeletonNode
import com.achulkov.evokammer.core.contract.DeliverableSkeletonNodeStatus
import com.achulkov.evokammer.core.contract.DeliverableSkeletonNodeType
import com.achulkov.evokammer.core.contract.DeliverableSkeletonTree
import com.achulkov.evokammer.core.contract.DstRefinementKind
import com.achulkov.evokammer.core.contract.DstRefinementProposal
import com.achulkov.evokammer.core.contract.DstRefinementResult

/**
 * Applies grader-proposed structural changes to a deliverable skeleton tree.
 *
 * The refiner executes SPLIT_NODE, MERGE_NODES, and RETYPE_NODE operations that
 * have been accepted by the Meta-Judge. All changes are applied transactionally
 * (the tree is updated as a whole, then persisted via [DeliverableSkeletonTreeStore]).
 *
 * @param store The tree store used for reading and writing per-group trees.
 * @param runId Active run identifier.
 * @param groupId Group whose tree is being refined.
 */
class DeliverableSkeletonTreeRefiner(
    private val store: DeliverableSkeletonTreeStore,
    private val runId: String,
    private val groupId: String
) {

    /**
     * Applies all accepted refinement proposals and persists the resulting tree.
     *
     * @param proposals Refinement proposals accepted by the Meta-Judge.
     * @param generation Current generation (used for node creation timestamps).
     * @return Result describing which proposals were applied.
     */
    fun applyRefinements(
        proposals: List<DstRefinementProposal>,
        generation: Int
    ): DstRefinementResult {
        if (proposals.isEmpty()) return DstRefinementResult()

        var tree = store.read(runId, groupId)
            ?: return DstRefinementResult().also {
                System.err.println("DeliverableSkeletonTreeRefiner: no tree found for $runId/$groupId")
            }

        val applied = mutableListOf<DstRefinementProposal>()
        val failed = mutableListOf<com.achulkov.evokammer.core.contract.DstRefinementRejection>()

        // Process SPLIT_NODE first (they create new children), then MERGE_NODES, then RETYPE_NODE
        val splitFirst = proposals.sortedBy { p ->
            when (p.kind) {
                DstRefinementKind.SPLIT_NODE -> 0
                DstRefinementKind.MERGE_NODES -> 1
                DstRefinementKind.RETYPE_NODE -> 2
            }
        }

        for (proposal in splitFirst) {
            val result = when (proposal.kind) {
                DstRefinementKind.SPLIT_NODE -> splitNode(tree, proposal, generation)
                DstRefinementKind.MERGE_NODES -> mergeNodes(tree, proposal, generation)
                DstRefinementKind.RETYPE_NODE -> retypeNode(tree, proposal, generation)
            }

            result.fold(
                onSuccess = { newTree ->
                    tree = newTree
                    applied += proposal
                },
                onFailure = { error ->
                    failed += com.achulkov.evokammer.core.contract.DstRefinementRejection(
                        proposal = proposal,
                        reason = error.message ?: "unknown refinement error"
                    )
                    System.err.println("DeliverableSkeletonTreeRefiner: failed ${proposal.kind} on ${proposal.targetNodeRef}: ${error.message}")
                }
            )
        }

        if (applied.isNotEmpty()) {
            store.write(tree)
        }

        return DstRefinementResult(
            acceptedProposals = applied,
            rejectedProposals = failed,
            treeVersionAfter = tree.version,
            structuralChangesCount = applied.size
        )
    }

    // ── Private operations ──────────────────────────────────────────────────────

    /**
     * Splits a single node into multiple children.
     *
     * The original node is marked CONTESTED (it no longer directly holds content).
     * Each child spec becomes a new STUB node under the original parent, with the
     * original node's artifact reference chain preserved.
     */
    private fun splitNode(
        tree: DeliverableSkeletonTree,
        proposal: DstRefinementProposal,
        generation: Int
    ): Result<DeliverableSkeletonTree> {
        val targetNode = tree.nodes.firstOrNull { it.nodeId == proposal.targetNodeRef }
            ?: return Result.failure(IllegalArgumentException("Unknown node: ${proposal.targetNodeRef}"))

        if (proposal.childSpecs.isEmpty()) {
            return Result.failure(IllegalArgumentException("SPLIT_NODE requires at least one child spec"))
        }

        val parentId = targetNode.parentId
        val siblingChildren = if (parentId != null) {
            // Find siblings and replace the target with the new children
            val parent = tree.nodes.firstOrNull { it.nodeId == parentId }
            (parent?.children ?: emptyList()).map { if (it == proposal.targetNodeRef) proposal.childSpecs.map { c -> c.nodeId } else listOf(it) }.flatten()
        } else {
            proposal.childSpecs.map { it.nodeId }
        }

        // Mark the original as CONTESTED (split pending)
        val contestedTarget = targetNode.copy(
            status = DeliverableSkeletonNodeStatus.CONTESTED,
            updatedAtGeneration = generation
        )

        // Create new child nodes
        val newNodes = proposal.childSpecs.map { spec ->
            DeliverableSkeletonNode(
                nodeId = spec.nodeId,
                parentId = parentId,
                children = emptyList(),
                nodeType = spec.nodeType,
                title = spec.title,
                pathHint = spec.pathHint,
                status = DeliverableSkeletonNodeStatus.STUB,
                currentArtifactRef = null,
                artifactVersion = 0,
                artifactType = targetNode.artifactType,
                owningGeneration = 0,
                owningAgentId = null,
                trustScore = 0.0,
                graderComplexityLevel = spec.graderComplexityLevel,
                gradingHistory = emptyList(),
                openGaps = emptyList(),
                deadEndRefs = targetNode.deadEndRefs,
                citationAgentChain = targetNode.citationAgentChain,
                createdAtGeneration = generation,
                updatedAtGeneration = generation
            )
        }

        // Update the parent to point to new children
        val updatedTree = tree.copy(
            nodes = tree.nodes.map { node ->
                when (node.nodeId) {
                    proposal.targetNodeRef -> contestedTarget
                    parentId -> node.copy(children = siblingChildren, updatedAtGeneration = generation)
                    else -> node
                }
            } + newNodes,
            version = tree.version + 1,
            updatedAtMs = System.currentTimeMillis()
        )

        println("DeliverableSkeletonTreeRefiner: split ${proposal.targetNodeRef} into ${newNodes.size} children")
        return Result.success(updatedTree)
    }

    /**
     * Merges multiple sibling nodes into one.
     *
     * All source nodes must share the same parent. The merged node inherits the
     * artifact reference with the highest trust score. Source nodes are marked
     * CONTESTED (their content is subsumed by the merged node).
     */
    private fun mergeNodes(
        tree: DeliverableSkeletonTree,
        proposal: DstRefinementProposal,
        generation: Int
    ): Result<DeliverableSkeletonTree> {
        val allRefs = listOf(proposal.targetNodeRef) + proposal.mergeNodeRefs
        val sourceNodes = allRefs.mapNotNull { ref -> tree.nodes.firstOrNull { it.nodeId == ref } }

        if (sourceNodes.size < 2) {
            return Result.failure(IllegalArgumentException(
                "MERGE_NODES requires at least 2 nodes; found ${sourceNodes.size}"
            ))
        }

        // All nodes must share the same parent
        val parents = sourceNodes.map { it.parentId }.distinct()
        if (parents.size != 1) {
            return Result.failure(IllegalArgumentException(
                "MERGE_NODES requires all nodes to share a single parent; found parents: $parents"
            ))
        }

        val parentId = parents.first()

        // The merged node inherits the best artifact
        val bestSource = sourceNodes.maxByOrNull { it.trustScore } ?: sourceNodes.first()

        val mergedNodeId = proposal.targetNodeRef
        val mergedTitle = proposal.newTitle ?: sourceNodes.joinToString(" + ") { it.title }

        val mergedNode = DeliverableSkeletonNode(
            nodeId = mergedNodeId,
            parentId = parentId,
            children = sourceNodes.flatMap { it.children }.distinct(),
            nodeType = proposal.newType ?: bestSource.nodeType,
            title = mergedTitle,
            pathHint = bestSource.pathHint,
            status = DeliverableSkeletonNodeStatus.IN_PROGRESS,
            currentArtifactRef = bestSource.currentArtifactRef,
            artifactVersion = bestSource.artifactVersion + 1,
            artifactType = bestSource.artifactType,
            owningGeneration = generation,
            owningAgentId = bestSource.owningAgentId,
            trustScore = bestSource.trustScore,
            graderComplexityLevel = sourceNodes.maxOf { it.graderComplexityLevel },
            gradingHistory = sourceNodes.flatMap { it.gradingHistory }.distinct(),
            openGaps = sourceNodes.flatMap { it.openGaps }.distinct(),
            deadEndRefs = sourceNodes.flatMap { it.deadEndRefs }.distinct(),
            citationAgentChain = sourceNodes.flatMap { it.citationAgentChain }.distinct(),
            createdAtGeneration = generation,
            updatedAtGeneration = generation
        )

        // Mark source nodes as CONTESTED (except the first which becomes the merged node)
        val otherRefs = proposal.mergeNodeRefs.toSet()
        val updatedNodes = tree.nodes.map { node ->
            when {
                node.nodeId == mergedNodeId -> mergedNode
                node.nodeId in otherRefs -> node.copy(
                    status = DeliverableSkeletonNodeStatus.CONTESTED,
                    updatedAtGeneration = generation
                )
                else -> node
            }
        }

        // Update parent's children list
        val parentNode = tree.nodes.firstOrNull { it.nodeId == parentId }
        val updatedParent = parentNode?.let { parent ->
            val newChildren = parent.children
                .filterNot { it in otherRefs }
                .distinct()
            parent.copy(children = newChildren, updatedAtGeneration = generation)
        }

        val finalNodes = updatedNodes.map { node ->
            if (node.nodeId == parentId && updatedParent != null) updatedParent else node
        }

        val updatedTree = tree.copy(
            nodes = finalNodes,
            version = tree.version + 1,
            updatedAtMs = System.currentTimeMillis()
        )

        println("DeliverableSkeletonTreeRefiner: merged ${sourceNodes.size} nodes into $mergedNodeId")
        return Result.success(updatedTree)
    }

    /**
     * Changes the node type of a single node.
     *
     * This is the simplest operation — it just updates the nodeType. The resolution
     * complexity (graderComplexityLevel) is optionally bumped if the new type has
     * different grading expectations.
     */
    private fun retypeNode(
        tree: DeliverableSkeletonTree,
        proposal: DstRefinementProposal,
        generation: Int
    ): Result<DeliverableSkeletonTree> {
        val targetNode = tree.nodes.firstOrNull { it.nodeId == proposal.targetNodeRef }
            ?: return Result.failure(IllegalArgumentException("Unknown node: ${proposal.targetNodeRef}"))

        val newType = proposal.newType
            ?: return Result.failure(IllegalArgumentException("RETYPE_NODE requires newType"))

        val updatedNode = targetNode.copy(
            nodeType = newType,
            title = proposal.newTitle ?: targetNode.title,
            graderComplexityLevel = (targetNode.graderComplexityLevel + 1).coerceAtMost(10),
            updatedAtGeneration = generation
        )

        val updatedTree = tree.replaceNode(updatedNode)
        println("DeliverableSkeletonTreeRefiner: retyped ${proposal.targetNodeRef} ${targetNode.nodeType} → $newType")
        return Result.success(updatedTree)
    }
}
