package com.achulkov.evokammer.agents.tools

import com.achulkov.evokammer.core.budget.BudgetConfig
import com.achulkov.evokammer.core.budget.BudgetLedger
import com.achulkov.evokammer.core.model.GroupProfile
import com.achulkov.evokammer.core.model.GroupProfileType
import com.achulkov.evokammer.substrate.SubstrateDatabaseFactory
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies that write tools carry the claimed WiP task into staging when the model omits it.
 */
class WipTaskReferencePropagationTest {

    private fun ledger(groupId: String = "group-0") = BudgetLedger(
        agentId = "agent-1",
        initialBudget = 100.0,
        floor = 1.0,
        config = BudgetConfig(
            groupId = groupId,
            profile = GroupProfile.default(GroupProfileType.Library)
        )
    )

    @Test
    fun `write staging tool defaults wipTaskRef from the assigned task`() = runTest {
        val dataRoot = createTempDirectory("write-staging-wip-ref-test").toFile()
        val db = SubstrateDatabaseFactory(dataRoot).get("run-1", "group-0")
        val tool = WriteStagingTool(
            stagingDao = db.stagingDao(),
            budgetLedger = ledger(),
            runId = "run-1",
            groupId = "group-0",
            agentId = "agent-1",
            role = "Researcher",
            generation = 1,
            defaultWipTaskRef = "node-42",
            isOutlier = false
        )

        val result = tool.execute(
            WriteStagingTool.Args(
                type = "DOC",
                content = "task-linked doc",
                speculative = false
            )
        )

        assertTrue(result.contains("Submission accepted"))
        val stored = db.stagingDao().getByAgent("run-1", "group-0", "agent-1").single()
        assertEquals("node-42", stored.wipTaskRef)
    }

    @Test
    fun `explicit write staging wipTaskRef overrides the assigned task`() = runTest {
        val dataRoot = createTempDirectory("write-staging-wip-override-test").toFile()
        val db = SubstrateDatabaseFactory(dataRoot).get("run-1b", "group-0")
        val tool = WriteStagingTool(
            stagingDao = db.stagingDao(),
            budgetLedger = ledger(),
            runId = "run-1b",
            groupId = "group-0",
            agentId = "agent-1",
            role = "Researcher",
            generation = 1,
            defaultWipTaskRef = "node-42",
            isOutlier = false
        )

        tool.execute(
            WriteStagingTool.Args(
                type = "DOC",
                content = "explicit task doc",
                wipTaskRef = "node-explicit",
                speculative = false
            )
        )

        val stored = db.stagingDao().getByAgent("run-1b", "group-0", "agent-1").single()
        assertEquals("node-explicit", stored.wipTaskRef)
    }

    @Test
    fun `emit fact tool defaults wipTaskRef from the assigned task`() = runTest {
        val dataRoot = createTempDirectory("emit-fact-wip-ref-test").toFile()
        val db = SubstrateDatabaseFactory(dataRoot).get("run-2", "group-0")
        val tool = EmitFactTool(
            stagingDao = db.stagingDao(),
            budgetLedger = ledger(),
            runId = "run-2",
            groupId = "group-0",
            agentId = "agent-1",
            role = "Researcher",
            generation = 1,
            defaultWipTaskRef = "node-99"
        )

        val result = tool.execute(
            EmitFactTool.Args(
                subject = "A",
                relation = "RELATES_TO",
                obj = "B",
                confidence = 0.8,
                domain = "general"
            )
        )

        assertTrue(result.contains("KG fact submitted"))
        val stored = db.stagingDao().getByAgent("run-2", "group-0", "agent-1").single()
        assertEquals("node-99", stored.wipTaskRef)
    }

    @Test
    fun `explicit emit fact wipTaskRef overrides the assigned task`() = runTest {
        val dataRoot = createTempDirectory("emit-fact-wip-override-test").toFile()
        val db = SubstrateDatabaseFactory(dataRoot).get("run-3", "group-0")
        val tool = EmitFactTool(
            stagingDao = db.stagingDao(),
            budgetLedger = ledger(),
            runId = "run-3",
            groupId = "group-0",
            agentId = "agent-1",
            role = "Researcher",
            generation = 1,
            defaultWipTaskRef = "node-99"
        )

        tool.execute(
            EmitFactTool.Args(
                subject = "A",
                relation = "RELATES_TO",
                obj = "B",
                confidence = 0.8,
                domain = "general",
                wipTaskRef = "node-explicit"
            )
        )

        val stored = db.stagingDao().getByAgent("run-3", "group-0", "agent-1").single()
        assertEquals("node-explicit", stored.wipTaskRef)
    }

    @Test
    fun `emit constraint tool defaults wipTaskRef from the assigned task`() = runTest {
        val dataRoot = createTempDirectory("emit-constraint-wip-ref-test").toFile()
        val db = SubstrateDatabaseFactory(dataRoot).get("run-4", "group-0")
        val tool = EmitConstraintTool(
            stagingDao = db.stagingDao(),
            budgetLedger = ledger(),
            runId = "run-4",
            groupId = "group-0",
            agentId = "agent-1",
            role = "Researcher",
            generation = 1,
            defaultWipTaskRef = "node-77"
        )

        val result = tool.execute(
            EmitConstraintTool.Args(
                constraintId = "constraint-1",
                description = "must cite one validated tool",
                appliesToType = "KG_FACT",
                condition = "facts require a validated tool citation",
                violationPenalty = 0.3
            )
        )

        assertTrue(result.contains("KG soft constraint"))
        val stored = db.stagingDao().getByAgent("run-4", "group-0", "agent-1").single()
        assertEquals("node-77", stored.wipTaskRef)
    }

    @Test
    fun `explicit emit constraint wipTaskRef overrides the assigned task`() = runTest {
        val dataRoot = createTempDirectory("emit-constraint-wip-override-test").toFile()
        val db = SubstrateDatabaseFactory(dataRoot).get("run-5", "group-0")
        val tool = EmitConstraintTool(
            stagingDao = db.stagingDao(),
            budgetLedger = ledger(),
            runId = "run-5",
            groupId = "group-0",
            agentId = "agent-1",
            role = "Researcher",
            generation = 1,
            defaultWipTaskRef = "node-77"
        )

        tool.execute(
            EmitConstraintTool.Args(
                constraintId = "constraint-2",
                description = "must cite one validated tool",
                appliesToType = "KG_FACT",
                condition = "facts require a validated tool citation",
                violationPenalty = 0.3,
                wipTaskRef = "node-explicit"
            )
        )

        val stored = db.stagingDao().getByAgent("run-5", "group-0", "agent-1").single()
        assertEquals("node-explicit", stored.wipTaskRef)
    }
}
