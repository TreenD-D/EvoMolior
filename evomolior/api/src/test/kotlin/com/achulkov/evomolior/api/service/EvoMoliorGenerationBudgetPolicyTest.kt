package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.core.model.GroupProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EvoMoliorGenerationBudgetPolicyTest {
    @Test
    fun `explicit run credit share bounds grants including live and rewarded credits`() {
        val perGroupRoundShare = 75.0 / 2 / 1
        val ceiling = computeGenerationSpawnBudgetCeiling(
            profile = GroupProfile.Library,
            maxAgentsPerGroup = 1,
            observedLiveRemainingBudget = 100.0,
            nodeSpawnCredits = 25.0,
            maximumCreditAllocation = perGroupRoundShare,
        )
        assertEquals(37.5, ceiling)
        assertEquals(75.0, ceiling * 2)
    }

    @Test
    fun `invalid explicit credit shares are rejected`() {
        for (invalid in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> {
                computeGenerationSpawnBudgetCeiling(
                    GroupProfile.Library, 1, null, 0.0, invalid,
                )
            }
        }
    }

    @Test
    fun `generation spawn budget does not collapse when live remaining telemetry is zero`() {
        val ceiling = computeGenerationSpawnBudgetCeiling(
            profile = GroupProfile.Library,
            maxAgentsPerGroup = 4,
            observedLiveRemainingBudget = 0.0,
            nodeSpawnCredits = 0.0
        )

        assertEquals(200.0, ceiling)
    }

    @Test
    fun `generation spawn budget adds applied delayed node credits`() {
        val ceiling = computeGenerationSpawnBudgetCeiling(
            profile = GroupProfile.Library,
            maxAgentsPerGroup = 3,
            observedLiveRemainingBudget = null,
            nodeSpawnCredits = 25.0
        )

        assertEquals(175.0, ceiling)
    }

    @Test
    fun `generation spawn budget can preserve explicit larger live budget grants`() {
        val ceiling = computeGenerationSpawnBudgetCeiling(
            profile = GroupProfile.Library,
            maxAgentsPerGroup = 3,
            observedLiveRemainingBudget = 250.0,
            nodeSpawnCredits = 0.0
        )

        assertEquals(250.0, ceiling)
    }
}
