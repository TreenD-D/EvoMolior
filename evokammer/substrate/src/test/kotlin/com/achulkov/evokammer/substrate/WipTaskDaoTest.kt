package com.achulkov.evokammer.substrate

import com.achulkov.evokammer.substrate.db.entity.WipTaskEntity
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
    fun `completeTask and abandonTask transition task state atomically`() = runTest {
        val dataRoot = createTempDirectory("wip-task-dao-test").toFile()
        val factory = SubstrateDatabaseFactory(dataRoot)
        val db = factory.get("run-1", "group-0")

        db.wipTaskDao().insertAll(
            listOf(
                WipTaskEntity(
                    nodeId = "node-complete",
                    runId = "run-1",
                    groupId = "group-0",
                    description = "complete me",
                    claimedByAgentId = "agent-1",
                    status = "claimed",
                    lastChangedGeneration = 1
                ),
                WipTaskEntity(
                    nodeId = "node-abandon",
                    runId = "run-1",
                    groupId = "group-0",
                    description = "abandon me",
                    claimedByAgentId = "agent-2",
                    status = "claimed",
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
}
