package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.core.model.GroupProfile
import kotlin.test.Test
import kotlin.test.assertEquals

class EvoMoliorGenerationBudgetPolicyTest {
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
