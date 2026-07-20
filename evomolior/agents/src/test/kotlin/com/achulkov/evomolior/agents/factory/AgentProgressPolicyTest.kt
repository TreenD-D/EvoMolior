package com.achulkov.evomolior.agents.factory

import com.achulkov.evomolior.core.budget.BudgetConfig
import com.achulkov.evomolior.core.budget.BudgetDeduction
import com.achulkov.evomolior.core.budget.BudgetLedger
import com.achulkov.evomolior.core.model.ActionType
import com.achulkov.evomolior.core.model.GroupProfile
import com.achulkov.evomolior.core.model.GroupProfileType
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression coverage for the no-progress completion policy.
 */
class AgentProgressPolicyTest {

    @Test
    fun `task-bearing agent with zero actions is allowed but logged`() {
        val ledger = BudgetLedger(
            agentId = "agent-1",
            initialBudget = 10.0,
            floor = 0.5,
            config = BudgetConfig(groupId = "g0", profile = GroupProfile.default(GroupProfileType.Library))
        )

        assertEquals(
            "I think this is fine.",
            requireProductiveAgentOutcome(
                agentLabel = "AgentRunner[agent-1]",
                result = "I think this is fine.",
                budgetLedger = ledger
            )
        )
    }

    @Test
    fun `recovery text at the end of a run is preserved as a warning-only placeholder`() {
        val ledger = BudgetLedger(
            agentId = "agent-2",
            initialBudget = 10.0,
            floor = 0.5,
            config = BudgetConfig(groupId = "g0", profile = GroupProfile.default(GroupProfileType.Library))
        )

        ledger.snapshot() // establish a stable snapshot path for coverage

        assertEquals(
            "[no-progress completion: empty-assistant recovery]",
            requireProductiveAgentOutcome(
                agentLabel = "AgentRunner[agent-2]",
                result = EMPTY_ASSISTANT_RECOVERY_TEXT,
                budgetLedger = ledger
            )
        )
    }

    @Test
    fun `blank output at the end of a run is preserved as a warning-only placeholder`() {
        val ledger = BudgetLedger(
            agentId = "agent-3",
            initialBudget = 10.0,
            floor = 0.5,
            config = BudgetConfig(groupId = "g0", profile = GroupProfile.default(GroupProfileType.Library))
        )

        assertEquals(
            "[no-progress completion: blank output]",
            requireProductiveAgentOutcome(
                agentLabel = "AgentRunner[agent-3]",
                result = "   ",
                budgetLedger = ledger
            )
        )
    }

    @Test
    fun `llm accounting entries do not count as productive agent actions`() {
        assertEquals(
            0,
            countProductiveAgentActions(
                listOf(BudgetDeduction(ActionType.LLM_CALL, cost = 0.03, balanceAfter = 49.97))
            )
        )
        assertEquals(
            1,
            countProductiveAgentActions(
                listOf(
                    BudgetDeduction(ActionType.LLM_CALL, cost = 0.03, balanceAfter = 49.97),
                    BudgetDeduction(ActionType.WRITE_STAGING, cost = 1.0, balanceAfter = 48.97)
                )
            )
        )
    }
}
