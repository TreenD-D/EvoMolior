package com.achulkov.evomolior.substrate

import com.achulkov.evomolior.substrate.db.entity.WipTaskEntity
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Verifies WiP task lifecycle transitions at the substrate layer.
 */
class WipTaskDaoTest {

    @Test
    fun `reactivateCompletedTasksForProjection reopens selected prior-generation rows and preserves untouched rows`() = runTest {
        val dataRoot = createTempDirectory("wip-task-dao-test").toFile()
        val factory = SubstrateDatabaseFactory(dataRoot)
        val db = factory.get("run-1", "group-0")

        db.wipTaskDao().insertAll(
            listOf(
                task(
                    nodeId = "node-shared",
                    runId = "run-1",
                    groupId = "group-0",
                    status = "completed",
                    claimedByAgentId = "agent-target",
                    lastChangedGeneration = 8,
                    abandonCount = 2
                ),
                task(
                    nodeId = "node-same-generation",
                    runId = "run-1",
                    groupId = "group-0",
                    status = "completed",
                    claimedByAgentId = "agent-current",
                    lastChangedGeneration = 10,
                    abandonCount = 7
                ),
                task(
                    nodeId = "node-open",
                    runId = "run-1",
                    groupId = "group-0",
                    status = "claimed",
                    claimedByAgentId = "agent-open",
                    lastChangedGeneration = 6,
                    abandonCount = 1
                ),
                task(
                    nodeId = "node-unselected",
                    runId = "run-1",
                    groupId = "group-0",
                    status = "completed",
                    claimedByAgentId = "agent-unselected",
                    lastChangedGeneration = 9,
                    abandonCount = 4
                ),
                task(
                    nodeId = "node-shared",
                    runId = "run-2",
                    groupId = "group-0",
                    status = "completed",
                    claimedByAgentId = "agent-other-run",
                    lastChangedGeneration = 8,
                    abandonCount = 5
                ),
                task(
                    nodeId = "node-shared",
                    runId = "run-1",
                    groupId = "group-9",
                    status = "completed",
                    claimedByAgentId = "agent-other-group",
                    lastChangedGeneration = 8,
                    abandonCount = 6
                )
            )
        )

        val reopenedCount = db.wipTaskDao().reactivateCompletedTasksForProjection(
            runId = "run-1",
            groupId = "group-0",
            nodeIds = listOf("node-shared", "node-same-generation", "node-open"),
            generation = 10
        )
        assertEquals(1, reopenedCount)

        val selectedRows = db.wipTaskDao().getByNodeIds(
            runId = "run-1",
            groupId = "group-0",
            nodeIds = listOf("node-shared", "node-same-generation", "node-open", "node-unselected")
        )
        assertEquals(4, selectedRows.size)

        val reopenedTask = db.wipTaskDao().getByNodeId("node-shared", "run-1", "group-0")
        assertNotNull(reopenedTask)
        assertEquals("unclaimed", reopenedTask.status)
        assertEquals(null, reopenedTask.claimedByAgentId)
        assertEquals(10, reopenedTask.lastChangedGeneration)
        assertEquals(2, reopenedTask.abandonCount)

        val sameGenerationTask = db.wipTaskDao().getByNodeId("node-same-generation", "run-1", "group-0")
        assertNotNull(sameGenerationTask)
        assertEquals("completed", sameGenerationTask.status)
        assertEquals("agent-current", sameGenerationTask.claimedByAgentId)
        assertEquals(10, sameGenerationTask.lastChangedGeneration)
        assertEquals(7, sameGenerationTask.abandonCount)

        val openTask = db.wipTaskDao().getByNodeId("node-open", "run-1", "group-0")
        assertNotNull(openTask)
        assertEquals("claimed", openTask.status)
        assertEquals("agent-open", openTask.claimedByAgentId)
        assertEquals(6, openTask.lastChangedGeneration)
        assertEquals(1, openTask.abandonCount)

        val unselectedTask = db.wipTaskDao().getByNodeId("node-unselected", "run-1", "group-0")
        assertNotNull(unselectedTask)
        assertEquals("completed", unselectedTask.status)
        assertEquals("agent-unselected", unselectedTask.claimedByAgentId)
        assertEquals(9, unselectedTask.lastChangedGeneration)
        assertEquals(4, unselectedTask.abandonCount)
    }

    @Test
    fun `reactivateCompletedTasksForProjection respects exact run group scoping`() = runTest {
        val dataRoot = createTempDirectory("wip-task-dao-scope-test").toFile()
        val factory = SubstrateDatabaseFactory(dataRoot)
        val db = factory.get("run-1", "group-0")

        db.wipTaskDao().insertAll(
            listOf(
                task(
                    nodeId = "node-shared-scope",
                    runId = "run-1",
                    groupId = "group-0",
                    status = "completed",
                    claimedByAgentId = "agent-target",
                    lastChangedGeneration = 3,
                    abandonCount = 2
                ),
                task(
                    nodeId = "node-shared-scope",
                    runId = "run-1",
                    groupId = "group-1",
                    status = "completed",
                    claimedByAgentId = "agent-other-group",
                    lastChangedGeneration = 3,
                    abandonCount = 4
                ),
                task(
                    nodeId = "node-shared-scope",
                    runId = "run-2",
                    groupId = "group-0",
                    status = "completed",
                    claimedByAgentId = "agent-other-run",
                    lastChangedGeneration = 3,
                    abandonCount = 6
                )
            )
        )

        val reopenedCount = db.wipTaskDao().reactivateCompletedTasksForProjection(
            runId = "run-1",
            groupId = "group-0",
            nodeIds = listOf("node-shared-scope"),
            generation = 4
        )
        assertEquals(1, reopenedCount)

        val targetTask = db.wipTaskDao().getByNodeId("node-shared-scope", "run-1", "group-0")
        assertNotNull(targetTask)
        assertEquals("unclaimed", targetTask.status)
        assertEquals(null, targetTask.claimedByAgentId)
        assertEquals(4, targetTask.lastChangedGeneration)
        assertEquals(2, targetTask.abandonCount)

        val otherGroupTask = db.wipTaskDao().getByNodeId("node-shared-scope", "run-1", "group-1")
        assertNotNull(otherGroupTask)
        assertEquals("completed", otherGroupTask.status)
        assertEquals("agent-other-group", otherGroupTask.claimedByAgentId)
        assertEquals(3, otherGroupTask.lastChangedGeneration)
        assertEquals(4, otherGroupTask.abandonCount)

        val otherRunTask = db.wipTaskDao().getByNodeId("node-shared-scope", "run-2", "group-0")
        assertNotNull(otherRunTask)
        assertEquals("completed", otherRunTask.status)
        assertEquals("agent-other-run", otherRunTask.claimedByAgentId)
        assertEquals(3, otherRunTask.lastChangedGeneration)
        assertEquals(6, otherRunTask.abandonCount)
    }

    @Test
    fun `completeTask and abandonTask transition task state atomically`() = runTest {
        val dataRoot = createTempDirectory("wip-task-dao-lifecycle-test").toFile()
        val factory = SubstrateDatabaseFactory(dataRoot)
        val db = factory.get("run-1", "group-0")

        db.wipTaskDao().insertAll(
            listOf(
                task(
                    nodeId = "node-complete",
                    runId = "run-1",
                    groupId = "group-0",
                    status = "claimed",
                    claimedByAgentId = "agent-1",
                    lastChangedGeneration = 1
                ),
                task(
                    nodeId = "node-abandon",
                    runId = "run-1",
                    groupId = "group-0",
                    status = "claimed",
                    claimedByAgentId = "agent-2",
                    lastChangedGeneration = 1
                )
            )
        )

        val completed = db.wipTaskDao().completeTask(
            nodeId = "node-complete",
            runId = "run-1",
            groupId = "group-0",
            agentId = "agent-1",
            generation = 2
        )
        assertEquals(1, completed)

        val completedTask = db.wipTaskDao().getByNodeId("node-complete", "run-1", "group-0")
        assertNotNull(completedTask)
        assertEquals("completed", completedTask.status)
        assertEquals(0, completedTask.abandonCount)

        val duplicateCompletion = db.wipTaskDao().completeTask(
            nodeId = "node-complete",
            runId = "run-1",
            groupId = "group-0",
            agentId = "agent-1",
            generation = 3
        )
        assertEquals(0, duplicateCompletion, "Completion must be idempotent")

        val abandoned = db.wipTaskDao().abandonTask(
            nodeId = "node-abandon",
            runId = "run-1",
            groupId = "group-0",
            agentId = "agent-2",
            generation = 2
        )
        assertEquals(1, abandoned)

        val abandonedTask = db.wipTaskDao().getByNodeId("node-abandon", "run-1", "group-0")
        assertNotNull(abandonedTask)
        assertEquals("unclaimed", abandonedTask.status)
        assertEquals(1, abandonedTask.abandonCount)

        val reopenAttempt = db.wipTaskDao().abandonTask(
            nodeId = "node-complete",
            runId = "run-1",
            groupId = "group-0",
            agentId = "agent-1",
            generation = 3
        )
        assertEquals(0, reopenAttempt)

        val stillCompleted = db.wipTaskDao().getByNodeId("node-complete", "run-1", "group-0")
        assertNotNull(stillCompleted)
        assertEquals("completed", stillCompleted.status)
        assertEquals(0, stillCompleted.abandonCount)
    }

    private fun task(
        nodeId: String,
        runId: String,
        groupId: String,
        status: String,
        claimedByAgentId: String? = null,
        lastChangedGeneration: Int,
        abandonCount: Int = 0
    ): WipTaskEntity {
        return WipTaskEntity(
            nodeId = nodeId,
            runId = runId,
            groupId = groupId,
            description = "task-$nodeId",
            claimedByAgentId = claimedByAgentId,
            status = status,
            lastChangedGeneration = lastChangedGeneration,
            abandonCount = abandonCount
        )
    }
}
