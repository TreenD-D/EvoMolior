package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.db.entity.WipTaskEntity
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AtlasWipProjectionReconcilerTest {
    @Test
    fun `selected prior-generation completion becomes claimable without reopening unrelated work`() = runTest {
        val factory = SubstrateDatabaseFactory(createTempDirectory("atlas-wip-reconcile-").toFile())
        val dao = factory.get(RUN_ID, GROUP_ID).wipTaskDao()
        dao.insertAll(
            listOf(
                task("selected-completed", "completed", generation = 0, owner = "agent-0"),
                task("selected-open", "unclaimed", generation = 0),
                task("unrelated-completed", "completed", generation = 0, owner = "agent-other")
            )
        )

        val result = AtlasWipProjectionReconciler(factory).reconcile(
            runId = RUN_ID,
            groupId = GROUP_ID,
            nodeIds = listOf(NodeId("selected-open"), NodeId("selected-completed")),
            generation = 1
        )

        assertEquals(
            listOf(NodeId("selected-completed"), NodeId("selected-open")),
            result.selectedNodeIds
        )
        assertEquals(listOf(NodeId("selected-completed")), result.reactivatedNodeIds)
        assertEquals(result.selectedNodeIds, result.claimableNodeIds)
        assertEquals(emptyList(), result.blockedNodeIds)
        assertEquals(
            "completed",
            dao.getByNodeId("unrelated-completed", RUN_ID, GROUP_ID)?.status
        )
    }

    @Test
    fun `same-generation completion remains blocked`() = runTest {
        val factory = SubstrateDatabaseFactory(createTempDirectory("atlas-wip-same-gen-").toFile())
        factory.get(RUN_ID, GROUP_ID).wipTaskDao().insertAll(
            listOf(task("selected", "completed", generation = 1, owner = "agent-1"))
        )

        val result = AtlasWipProjectionReconciler(factory).reconcile(
            runId = RUN_ID,
            groupId = GROUP_ID,
            nodeIds = listOf(NodeId("selected")),
            generation = 1
        )

        assertEquals(emptyList(), result.reactivatedNodeIds)
        assertEquals(emptyList(), result.claimableNodeIds)
        assertEquals(listOf(NodeId("selected")), result.blockedNodeIds)
    }

    @Test
    fun `missing selected WIP projection fails closed`() = runTest {
        val factory = SubstrateDatabaseFactory(createTempDirectory("atlas-wip-missing-").toFile())

        assertFailsWith<IllegalStateException> {
            AtlasWipProjectionReconciler(factory).reconcile(
                runId = RUN_ID,
                groupId = GROUP_ID,
                nodeIds = listOf(NodeId("missing")),
                generation = 1
            )
        }
    }

    private fun task(
        nodeId: String,
        status: String,
        generation: Int,
        owner: String? = null
    ): WipTaskEntity = WipTaskEntity(
        nodeId = nodeId,
        runId = RUN_ID,
        groupId = GROUP_ID,
        description = nodeId,
        claimedByAgentId = owner,
        status = status,
        lastChangedGeneration = generation,
        linkedNodeId = nodeId,
        dstNodeRef = nodeId
    )

    private companion object {
        const val RUN_ID = "run-atlas-wip"
        const val GROUP_ID = "group-atlas-wip"
    }
}
