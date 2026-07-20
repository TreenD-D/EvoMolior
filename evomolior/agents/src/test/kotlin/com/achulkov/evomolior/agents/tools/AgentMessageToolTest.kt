package com.achulkov.evomolior.agents.tools

import com.achulkov.evomolior.core.budget.BudgetConfig
import com.achulkov.evomolior.core.budget.BudgetLedger
import com.achulkov.evomolior.core.model.GroupProfile
import com.achulkov.evomolior.core.model.GroupProfileType
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentMessageToolTest {

    @Test
    fun `read messages returns direct and broadcast messages for the receiving agent`() = runTest {
        val dataRoot = createTempDirectory("agent-message-tool-test").toFile()
        val db = SubstrateDatabaseFactory(dataRoot).get("run-1", "group-0")
        val ledger = BudgetLedger(
            agentId = "agent-sender",
            initialBudget = 100.0,
            floor = 1.0,
            config = BudgetConfig(
                groupId = "group-0",
                profile = GroupProfile.default(GroupProfileType.Commune)
            )
        )
        val sender = SendMessageTool(
            budgetLedger = ledger,
            eventLogDao = db.eventLogDao(),
            runId = "run-1",
            groupId = "group-0",
            agentId = "agent-sender",
            generation = 2
        )
        val receiver = ReadMessagesTool(
            eventLogDao = db.eventLogDao(),
            runId = "run-1",
            groupId = "group-0",
            agentId = "agent-receiver"
        )

        sender.execute(
            SendMessageTool.Args(
                recipientAgentId = "agent-receiver",
                subject = "handoff",
                message = "Use the latest validated KG facts."
            )
        )
        sender.execute(
            SendMessageTool.Args(
                recipientAgentId = "broadcast",
                subject = "warning",
                message = "Avoid the stale competitor matrix."
            )
        )
        sender.execute(
            SendMessageTool.Args(
                recipientAgentId = "agent-other",
                subject = "private",
                message = "This should stay private."
            )
        )

        val output = receiver.execute(ReadMessagesTool.Args())

        assertContains(output, "Use the latest validated KG facts.")
        assertContains(output, "Avoid the stale competitor matrix.")
        assertFalse(output.contains("This should stay private."))
    }

    @Test
    fun `read messages can ignore broadcast messages`() = runTest {
        val dataRoot = createTempDirectory("agent-message-tool-no-broadcast-test").toFile()
        val db = SubstrateDatabaseFactory(dataRoot).get("run-1", "group-0")
        val ledger = BudgetLedger(
            agentId = "agent-sender",
            initialBudget = 100.0,
            floor = 1.0,
            config = BudgetConfig(
                groupId = "group-0",
                profile = GroupProfile.default(GroupProfileType.Commune)
            )
        )
        val sender = SendMessageTool(
            budgetLedger = ledger,
            eventLogDao = db.eventLogDao(),
            runId = "run-1",
            groupId = "group-0",
            agentId = "agent-sender",
            generation = 2
        )
        val receiver = ReadMessagesTool(
            eventLogDao = db.eventLogDao(),
            runId = "run-1",
            groupId = "group-0",
            agentId = "agent-receiver"
        )

        sender.execute(
            SendMessageTool.Args(
                recipientAgentId = "broadcast",
                subject = "warning",
                message = "Broadcast-only message."
            )
        )

        val output = receiver.execute(ReadMessagesTool.Args(includeBroadcast = false))

        assertTrue(output == "[]")
    }
}
