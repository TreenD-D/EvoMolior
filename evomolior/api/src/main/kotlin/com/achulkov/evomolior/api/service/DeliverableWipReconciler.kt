package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.core.contract.DeliverableSkeletonNode
import com.achulkov.evomolior.core.contract.DeliverableSkeletonNodeStatus
import com.achulkov.evomolior.core.contract.DeliverableSkeletonNodeType
import com.achulkov.evomolior.substrate.DeliverableSkeletonTreeStore
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.db.entity.WipTaskEntity
import java.security.MessageDigest
import java.util.Locale

/**
 * Projects evolved deliverable-tree work into claimable, durable WiP tasks.
 *
 * Deterministic task identities make reconciliation idempotent while conflict-ignore insertion
 * preserves completed or currently claimed task history.
 *
 * @param treeStore Store containing the current per-group deliverable tree.
 * @param substrateFactory Factory resolving the group's WiP DAO.
 */
class DeliverableWipReconciler(
    private val treeStore: DeliverableSkeletonTreeStore,
    private val substrateFactory: SubstrateDatabaseFactory
) {
    /**
     * Creates tasks for actionable gaps and newly introduced unfinished leaf nodes.
     *
     * @param runId Active run identifier.
     * @param groupId Group whose tree should be reconciled.
     * @param generation Generation that produced the tree changes.
     * @return Number of newly inserted tasks.
     */
    suspend fun reconcile(runId: String, groupId: String, generation: Int): Int {
        val tree = treeStore.read(runId, groupId) ?: return 0
        val dao = substrateFactory.get(runId, groupId).wipTaskDao()

        val specs = tree.nodes
            .asSequence()
            .filter(::requiresWork)
            .flatMap { node -> taskSpecs(node).asSequence() }
            .toList()
        var inserted = 0
        for (spec in specs) {
            val rowId = dao.insertIfAbsent(
                    WipTaskEntity(
                        nodeId = taskId(spec.node.nodeId, spec.gapIdentity),
                        runId = runId,
                        groupId = groupId,
                        description = spec.description,
                        lastChangedGeneration = generation,
                        linkedNodeId = spec.node.nodeId,
                        dstNodeRef = spec.node.nodeId
                    )
                )
            if (rowId != -1L) inserted += 1
        }
        return inserted
    }

    private fun requiresWork(node: DeliverableSkeletonNode): Boolean =
        node.nodeType != DeliverableSkeletonNodeType.ROOT &&
            node.status != DeliverableSkeletonNodeStatus.COMPLETE &&
            node.status != DeliverableSkeletonNodeStatus.DEAD_END

    private fun taskSpecs(node: DeliverableSkeletonNode): List<TaskSpec> {
        val actionableGaps = node.openGaps
            .mapNotNull(::normaliseGap)
            .distinctBy(NormalisedGap::identity)

        if (actionableGaps.isNotEmpty()) {
            return actionableGaps.map { gap ->
                TaskSpec(
                    node = node,
                    gapIdentity = gap.identity,
                    description = "${node.title}: ${gap.display}"
                )
            }
        }

        if (node.children.isNotEmpty()) return emptyList()
        return listOf(
            TaskSpec(
                node = node,
                gapIdentity = NEW_NODE_GAP_IDENTITY,
                description = "Complete deliverable node: ${node.title}"
            )
        )
    }

    private fun normaliseGap(value: String): NormalisedGap? {
        val display = value.trim().replace(Regex("\\s+"), " ")
        if (display.isBlank()) return null
        return NormalisedGap(
            identity = display.lowercase(Locale.ROOT),
            display = display
        )
    }

    private fun taskId(nodeId: String, gapIdentity: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$nodeId\u0000$gapIdentity".toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        return "dst-wip-${digest.take(TASK_HASH_LENGTH)}"
    }

    private data class TaskSpec(
        val node: DeliverableSkeletonNode,
        val gapIdentity: String,
        val description: String
    )

    private data class NormalisedGap(
        val identity: String,
        val display: String
    )

    private companion object {
        const val NEW_NODE_GAP_IDENTITY = "new-unfinished-node"
        const val TASK_HASH_LENGTH = 24
    }
}
