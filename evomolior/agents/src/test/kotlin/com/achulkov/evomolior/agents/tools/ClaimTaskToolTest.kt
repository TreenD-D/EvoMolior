package com.achulkov.evomolior.agents.tools

import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.db.entity.WipTaskEntity
import com.achulkov.evomolior.sandbox.enforcer.ActionEventType
import com.achulkov.evomolior.sandbox.enforcer.AgentAction
import com.achulkov.evomolior.sandbox.enforcer.ConstitutionalActionGate
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class ClaimTaskToolTest {
    @Test
    fun `constitutional gate intercepts claim before task mutation`() = runTest {
        val database = SubstrateDatabaseFactory(
            createTempDirectory("claim-task-tool-gate-").toFile()
        ).get("run-1", "group-0")
        val dao = database.wipTaskDao()
        dao.insertAll(
            listOf(
                WipTaskEntity(
                    nodeId = "task-1",
                    runId = "run-1",
                    groupId = "group-0",
                    description = "Validate the constitutional claim boundary.",
                    lastChangedGeneration = 0
                )
            )
        )
        var observedAction: AgentAction? = null
        val tool = ClaimTaskTool(
            wipTaskDao = dao,
            runId = "run-1",
            groupId = "group-0",
            agentId = "agent-1",
            generation = 1,
            actionGate = ConstitutionalActionGate { action ->
                observedAction = action
                error("blocked by test gate")
            }
        )

        assertFailsWith<IllegalStateException> {
            tool.execute(ClaimTaskTool.Args(nodeId = "task-1"))
        }

        assertEquals(
            AgentAction(
                type = ActionEventType.WIP_TASK_CLAIM,
                agentId = "agent-1",
                detail = "task-1"
            ),
            observedAction
        )
        val unchanged = assertNotNull(dao.getByNodeId("task-1", "run-1", "group-0"))
        assertEquals("unclaimed", unchanged.status)
        assertEquals(null, unchanged.claimedByAgentId)
    }
}
