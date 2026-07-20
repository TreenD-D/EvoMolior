package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.core.contract.DeliverableSkeletonNode
import com.achulkov.evomolior.core.contract.DeliverableSkeletonNodeStatus
import com.achulkov.evomolior.core.contract.DeliverableSkeletonNodeType
import com.achulkov.evomolior.core.contract.DeliverableSkeletonTree
import com.achulkov.evomolior.substrate.DeliverableSkeletonTreeStore
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class DeliverableWipReconcilerTest {
    @Test
    fun `reconciliation creates claimable tasks idempotently without replacing completion history`() = runTest {
        val factory = SubstrateDatabaseFactory(createTempDirectory("dst-wip-reconcile-").toFile())
        val store = DeliverableSkeletonTreeStore(factory)
        val reconciler = DeliverableWipReconciler(store, factory)
        val runId = "run-dst-wip"
        val groupId = "group-0"

        store.write(
            DeliverableSkeletonTree(
                runId = runId,
                groupId = groupId,
                nodes = listOf(
                    DeliverableSkeletonNode(
                        nodeId = DeliverableSkeletonTree.DEFAULT_ROOT_NODE_ID,
                        nodeType = DeliverableSkeletonNodeType.ROOT,
                        title = "Root",
                        children = listOf("dst:section:market")
                    ),
                    DeliverableSkeletonNode(
                        nodeId = "dst:section:market",
                        parentId = DeliverableSkeletonTree.DEFAULT_ROOT_NODE_ID,
                        nodeType = DeliverableSkeletonNodeType.DOCUMENT_SECTION,
                        title = "Market evidence",
                        status = DeliverableSkeletonNodeStatus.CONTESTED,
                        openGaps = listOf("Add two independent primary sources")
                    )
                )
            )
        )

        assertEquals(1, reconciler.reconcile(runId, groupId, generation = 3))
        assertEquals(0, reconciler.reconcile(runId, groupId, generation = 4))

        val dao = factory.get(runId, groupId).wipTaskDao()
        val task = dao.getNextUnclaimed(runId, groupId)
        assertNotNull(task)
        assertEquals("dst:section:market", task.dstNodeRef)
        assertEquals("dst:section:market", task.linkedNodeId)
        assertEquals("Market evidence: Add two independent primary sources", task.description)
        assertEquals(1, dao.claimTask(task.nodeId, runId, groupId, "agent-1", 4))
        assertEquals(1, dao.completeTask(task.nodeId, runId, groupId, "agent-1", 4))

        assertEquals(0, reconciler.reconcile(runId, groupId, generation = 5))
        assertEquals("completed", dao.getByNodeId(task.nodeId, runId, groupId)?.status)
        assertEquals(1, dao.countAll(runId, groupId))
    }

    @Test
    fun `new unfinished refinement leaf becomes claimable without a synthetic gap`() = runTest {
        val factory = SubstrateDatabaseFactory(createTempDirectory("dst-wip-new-node-").toFile())
        val store = DeliverableSkeletonTreeStore(factory)
        val runId = "run-new-node"
        val groupId = "group-0"
        store.write(
            DeliverableSkeletonTree(
                runId = runId,
                groupId = groupId,
                nodes = listOf(
                    DeliverableSkeletonNode(
                        nodeId = "dst:root",
                        nodeType = DeliverableSkeletonNodeType.ROOT,
                        title = "Root",
                        children = listOf("dst:new")
                    ),
                    DeliverableSkeletonNode(
                        nodeId = "dst:new",
                        parentId = "dst:root",
                        nodeType = DeliverableSkeletonNodeType.DATASET,
                        title = "New comparison dataset"
                    )
                )
            )
        )

        assertEquals(1, DeliverableWipReconciler(store, factory).reconcile(runId, groupId, 2))
        val task = factory.get(runId, groupId).wipTaskDao().getNextUnclaimed(runId, groupId)
        assertNotNull(task)
        assertEquals("Complete deliverable node: New comparison dataset", task.description)
    }
}
