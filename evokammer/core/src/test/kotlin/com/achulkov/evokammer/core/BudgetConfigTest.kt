package com.achulkov.evokammer.core

import com.achulkov.evokammer.core.budget.BudgetConfig
import com.achulkov.evokammer.core.model.ActionType
import com.achulkov.evokammer.core.model.GroupProfile
import com.achulkov.evokammer.core.model.GroupProfileType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BudgetConfigTest {

    @Test
    fun `always-free actions cost zero regardless of profile`() {
        val profiles = GroupProfileType.entries.map { GroupProfile.default(it) }
        profiles.forEach { profile ->
            val config = BudgetConfig("g", profile)
            assertEquals(0.0, config.costFor(ActionType.WRITE_DEATH_NOTE),
                "WRITE_DEATH_NOTE should always be free for profile ${profile.profileType}")
            assertEquals(0.0, config.costFor(ActionType.READ_KB),
                "READ_KB should always be free")
        }
    }

    @Test
    fun `custom cost overrides default for non-LLM action`() {
        val config = BudgetConfig(
            groupId = "g0",
            profile = GroupProfile.default(GroupProfileType.Library),
            customCosts = mapOf(ActionType.WEB_FETCH.name to 99.0)
        )
        assertEquals(99.0, config.costFor(ActionType.WEB_FETCH))
    }

    @Test
    fun `LLM_CALL cost scales with token count`() {
        val config = BudgetConfig(
            groupId = "g0",
            profile = GroupProfile.default(GroupProfileType.Factory)
        )
        val cost100 = config.costFor(ActionType.LLM_CALL, tokenCount = 100)
        val cost200 = config.costFor(ActionType.LLM_CALL, tokenCount = 200)
        assertTrue(cost200 > cost100, "LLM cost should scale with token count")
        assertEquals(cost100 * 2, cost200, 1e-9)
    }

    @Test
    fun `floor is a ratio of initial budget`() {
        val config = BudgetConfig(
            groupId = "g0",
            profile = GroupProfile.default(GroupProfileType.Commune),
            budgetFloorRatio = 0.1
        )
        assertEquals(100.0, config.floorFor(1000.0), 1e-9)
        assertEquals(50.0, config.floorFor(500.0), 1e-9)
    }

    @Test
    fun `tool retry costs more than tool success`() {
        val config = BudgetConfig(
            groupId = "g0",
            profile = GroupProfile.default(GroupProfileType.Factory)
        )
        val successCost = config.costFor(ActionType.TOOL_EXEC_SUCCESS)
        val retryCost = config.costFor(ActionType.TOOL_EXEC_RETRY)
        assertTrue(retryCost > successCost, "Retry cost ($retryCost) should exceed success cost ($successCost)")
    }

    @Test
    fun `default max iterations are tuned for bounded agent loops`() {
        val config = BudgetConfig(
            groupId = "g0",
            profile = GroupProfile.default(GroupProfileType.Library)
        )

        assertEquals(40, config.maxIterations)
    }
}
