package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.api.model.RunConfig
import com.achulkov.evomolior.core.model.GroupProfileType
import com.achulkov.evomolior.core.model.ScoringDimension
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Focused tests for production round-budget mode selection. */
class EvoMoliorRunBudgetModeTest {

    @Test
    fun `nonpositive production duration selects budget-led mode only without override`() {
        assertTrue(isBudgetLedProductionRound(config(roundDurationMinutes = 0), null))
        assertTrue(isBudgetLedProductionRound(config(roundDurationMinutes = -1), null))
        assertFalse(isBudgetLedProductionRound(config(roundDurationMinutes = 0), 1_000L))
    }

    @Test
    fun `test runs and positive durations retain fixed-duration mode`() {
        assertFalse(
            isBudgetLedProductionRound(
                config(roundDurationMinutes = 0, isTestRun = true),
                null
            )
        )
        assertFalse(isBudgetLedProductionRound(config(roundDurationMinutes = 1), null))
    }

    private fun config(
        roundDurationMinutes: Int,
        isTestRun: Boolean = false
    ): RunConfig = RunConfig(
        runId = "budget-mode-test",
        objective = "test",
        groupCount = 2,
        groupProfiles = listOf(GroupProfileType.Library, GroupProfileType.Factory),
        roundDurationMinutes = roundDurationMinutes,
        scoringDimensions = listOf(
            ScoringDimension(
                id = "progress",
                name = "Progress",
                description = "Progress",
                weight = 1.0
            )
        ),
        openRouterApiKey = "sk-test",
        isTestRun = isTestRun
    )
}
