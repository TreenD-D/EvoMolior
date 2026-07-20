package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory

/**
 * Result of reconciling one group's selected Atlas portfolio into its WIP projection.
 *
 * @param selectedNodeIds Exact selected portfolio node IDs.
 * @param reactivatedNodeIds Prior-generation completed rows reopened for this generation.
 * @param claimableNodeIds Selected rows available for atomic worker claims.
 * @param blockedNodeIds Selected rows intentionally left non-claimable.
 */
data class AtlasWipProjectionReconciliation(
    val selectedNodeIds: List<NodeId>,
    val reactivatedNodeIds: List<NodeId>,
    val claimableNodeIds: List<NodeId>,
    val blockedNodeIds: List<NodeId>
)

/**
 * Reconciles the authoritative current-round Atlas portfolio with group-local WIP state.
 *
 * WIP completion records a successful generation-local work attempt, not accepted Atlas capture.
 * A selected node can therefore be reopened in a later generation while same-generation
 * completion and all unrelated WIP history remain closed.
 *
 * @param substrateFactory Factory resolving the exact group-local WIP projection.
 */
class AtlasWipProjectionReconciler(
    private val substrateFactory: SubstrateDatabaseFactory
) {
    /**
     * Reactivates exact selected prior-generation completions and verifies projection coverage.
     *
     * @param runId Active run identifier.
     * @param groupId Group receiving the selected portfolio.
     * @param nodeIds Exact selected Atlas node IDs.
     * @param generation Current generation.
     * @return Exact reconciliation result.
     */
    suspend fun reconcile(
        runId: String,
        groupId: String,
        nodeIds: List<NodeId>,
        generation: Int
    ): AtlasWipProjectionReconciliation {
        require(generation >= 0) { "WIP projection generation must be non-negative" }
        val selected = nodeIds.distinct().sortedBy(NodeId::value)
        if (selected.isEmpty()) {
            return AtlasWipProjectionReconciliation(
                selectedNodeIds = emptyList(),
                reactivatedNodeIds = emptyList(),
                claimableNodeIds = emptyList(),
                blockedNodeIds = emptyList()
            )
        }

        val selectedValues = selected.map(NodeId::value)
        val dao = substrateFactory.get(runId, groupId).wipTaskDao()
        val beforeByNodeId = dao.getByNodeIds(runId, groupId, selectedValues)
            .associateBy { row -> row.nodeId }
        val missingBefore = selectedValues.filterNot(beforeByNodeId::containsKey)
        check(missingBefore.isEmpty()) {
            "Atlas portfolio has no group-local WIP projection for run=$runId group=$groupId " +
                "nodes=${missingBefore.joinToString(",")}"
        }

        val expectedReactivatedValues = selectedValues.filter { nodeId ->
            val row = beforeByNodeId.getValue(nodeId)
            row.status == "completed" && row.lastChangedGeneration < generation
        }
        val reactivatedCount = dao.reactivateCompletedTasksForProjection(
            runId = runId,
            groupId = groupId,
            nodeIds = selectedValues,
            generation = generation
        )
        check(reactivatedCount == expectedReactivatedValues.size) {
            "Atlas WIP projection changed concurrently for run=$runId group=$groupId: " +
                "expected=${expectedReactivatedValues.size}, actual=$reactivatedCount"
        }

        val afterByNodeId = dao.getByNodeIds(runId, groupId, selectedValues)
            .associateBy { row -> row.nodeId }
        val missingAfter = selectedValues.filterNot(afterByNodeId::containsKey)
        check(missingAfter.isEmpty()) {
            "Atlas WIP projection disappeared for run=$runId group=$groupId " +
                "nodes=${missingAfter.joinToString(",")}"
        }
        val claimable = selectedValues.filter { nodeId ->
            afterByNodeId.getValue(nodeId).status == "unclaimed"
        }
        val blocked = selectedValues.filterNot(claimable::contains)

        return AtlasWipProjectionReconciliation(
            selectedNodeIds = selected,
            reactivatedNodeIds = expectedReactivatedValues.map(::NodeId),
            claimableNodeIds = claimable.map(::NodeId),
            blockedNodeIds = blocked.map(::NodeId)
        )
    }
}
