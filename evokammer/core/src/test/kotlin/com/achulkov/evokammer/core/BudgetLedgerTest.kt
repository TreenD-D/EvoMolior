package com.achulkov.evokammer.core

import com.achulkov.evokammer.core.budget.BudgetConfig
import com.achulkov.evokammer.core.budget.BudgetDeductResult
import com.achulkov.evokammer.core.budget.BudgetLedger
import com.achulkov.evokammer.core.model.ActionType
import com.achulkov.evokammer.core.model.GroupProfile
import com.achulkov.evokammer.core.model.GroupProfileType
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BudgetLedgerTest {

    private fun defaultConfig(groupId: String = "g0") = BudgetConfig(
        groupId = groupId,
        profile = GroupProfile.default(GroupProfileType.Factory)
    )

    @Test
    fun `initial balance equals initial budget`() {
        val ledger = BudgetLedger("agent-1", 1000.0, 50.0, defaultConfig())
        assertEquals(1000.0, ledger.balance)
    }

    @Test
    fun `deduct returns OK when above floor`() = runTest {
        val ledger = BudgetLedger("agent-1", 1000.0, 50.0, defaultConfig())
        val result = ledger.deduct(ActionType.SEARCH_KB)
        assertEquals(BudgetDeductResult.OK, result)
        assertTrue(ledger.balance < 1000.0, "Balance should have decreased")
    }

    @Test
    fun `always-free actions do not reduce balance`() = runTest {
        val ledger = BudgetLedger("agent-1", 1000.0, 50.0, defaultConfig())
        val balanceBefore = ledger.balance
        val result = ledger.deduct(ActionType.WRITE_DEATH_NOTE)
        assertEquals(BudgetDeductResult.OK, result)
        assertEquals(balanceBefore, ledger.balance, "Always-free actions must not reduce balance")
    }

    @Test
    fun `deduct returns FLOOR_REACHED when balance crosses floor`() = runTest {
        // Start just above floor with a cost that will push it below
        val floor = 50.0
        val config = BudgetConfig(
            groupId = "g0",
            profile = GroupProfile.default(GroupProfileType.Factory),
            customCosts = mapOf(ActionType.WRITE_STAGING.name to 100.0)
        )
        val ledger = BudgetLedger("agent-1", 110.0, floor, config)
        val result = ledger.deduct(ActionType.WRITE_STAGING)
        assertEquals(BudgetDeductResult.FLOOR_REACHED, result)
        assertTrue(ledger.balance <= floor)
    }

    @Test
    fun `deduct returns BELOW_FLOOR when already at floor`() = runTest {
        val floor = 50.0
        val config = BudgetConfig(
            groupId = "g0",
            profile = GroupProfile.default(GroupProfileType.Factory),
            customCosts = mapOf(ActionType.WRITE_STAGING.name to 100.0)
        )
        val ledger = BudgetLedger("agent-1", 110.0, floor, config)
        ledger.deduct(ActionType.WRITE_STAGING) // FLOOR_REACHED
        val result = ledger.deduct(ActionType.SEARCH_KB)
        assertEquals(BudgetDeductResult.BELOW_FLOOR, result, "Should return BELOW_FLOOR when already at floor")
    }

    @Test
    fun `action log records all deductions`() = runTest {
        val ledger = BudgetLedger("agent-1", 1000.0, 50.0, defaultConfig())
        ledger.deduct(ActionType.SEARCH_KB)
        ledger.deduct(ActionType.WEB_FETCH)
        ledger.deduct(ActionType.WRITE_DEATH_NOTE) // always-free
        assertEquals(3, ledger.actionLog.size)
    }

    @Test
    fun `generateStagingToken returns non-blank string`() = runTest {
        val ledger = BudgetLedger("agent-1", 1000.0, 50.0, defaultConfig())
        val token = ledger.generateStagingToken("DOCUMENT")
        assertTrue(token.isNotBlank())
    }

    @Test
    fun `snapshot reflects current state`() = runTest {
        val ledger = BudgetLedger("agent-1", 1000.0, 50.0, defaultConfig())
        ledger.deduct(ActionType.SEARCH_KB)
        val snap = ledger.snapshot()
        assertEquals("agent-1", snap.agentId)
        assertEquals(1000.0, snap.initialBudget)
        assertEquals(ledger.balance, snap.currentBalance)
        assertEquals(1, snap.actionLog.size)
    }

    @Test
    fun `retry tracker penalises repeated tool calls`() = runTest {
        val config = BudgetConfig(
            groupId = "g0",
            profile = GroupProfile.default(GroupProfileType.Factory)
        )
        val ledger = BudgetLedger("agent-1", 1000.0, 50.0, config)
        val toolKey = "web_fetch:abc123"

        ledger.deduct(ActionType.TOOL_EXEC_SUCCESS, toolKey = toolKey)
        val balanceAfterFirst = ledger.balance

        // Second call with same key should be retry (higher cost)
        ledger.deduct(ActionType.TOOL_EXEC_SUCCESS, toolKey = toolKey)
        val balanceAfterSecond = ledger.balance

        val firstDeductionCost = 1000.0 - balanceAfterFirst
        val secondDeductionCost = balanceAfterFirst - balanceAfterSecond
        // Retry cost is 4× tool_exec_success, success is 1× — so second should cost more
        assertTrue(
            secondDeductionCost > firstDeductionCost,
            "Retry should cost more than first call: first=$firstDeductionCost second=$secondDeductionCost"
        )
    }

    @Test
    fun `tool execution cost ratios are success failure and retry`() = runTest {
        val config = defaultConfig()
        val successCost = config.costFor(ActionType.TOOL_EXEC_SUCCESS)
        val failureCost = config.costFor(ActionType.TOOL_EXEC_FAILURE)
        val retryCost = config.costFor(ActionType.TOOL_EXEC_RETRY)

        assertEquals(successCost * 2.0, failureCost, 1e-9)
        assertEquals(successCost * 4.0, retryCost, 1e-9)
    }

    @Test
    fun `deductions remain atomic under concurrent pressure`() = runTest {
        val config = BudgetConfig(
            groupId = "g0",
            profile = GroupProfile.default(GroupProfileType.Factory),
            customCosts = mapOf(ActionType.WRITE_STAGING.name to 1.0)
        )
        val ledger = BudgetLedger("agent-1", 1_000.0, 0.0, config)

        coroutineScope {
            val jobs = List(100) {
                async { ledger.deduct(ActionType.WRITE_STAGING) }
            }
            jobs.awaitAll()
        }

        assertEquals(900.0, ledger.balance, 1e-9)
        assertEquals(100, ledger.actionLog.count { it.actionType == ActionType.WRITE_STAGING })
    }
}
