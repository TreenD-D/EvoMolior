package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.core.repair.ToolRepairCode
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StrictAcceptanceScenarioV1Test {
    @Test
    fun `scenario contract preserves the exact six round topology`() {
        val scenario = StrictAcceptanceScenarioV1(
            scenarioId = "strict-success-v1",
            injectLifecycleRace = true,
            firstToolFailure = ToolRepairCode.POWER_TO_ENERGY_UNIT_CONVERSION,
            requireLaterAgentUse = true,
            requireSimulationInfluence = true,
            terminalPolicy = ScenarioTerminalPolicy.ACCEPTED,
        )

        assertEquals(6, scenario.rounds)
        assertEquals(2, scenario.workerGroups)
        assertEquals(3, scenario.graderGroups)
        assertEquals(
            scenario,
            Json.decodeFromString(
                StrictAcceptanceScenarioV1.serializer(),
                Json.encodeToString(StrictAcceptanceScenarioV1.serializer(), scenario),
            )
        )
    }

    @Test
    fun `scenario contract rejects missing execution topology`() {
        assertFailsWith<IllegalArgumentException> {
            StrictAcceptanceScenarioV1(
                scenarioId = " ",
                rounds = 0,
                workerGroups = 0,
                graderGroups = 0,
                injectLifecycleRace = false,
                firstToolFailure = ToolRepairCode.PRIVATE_TEST_FAILED_UNCLASSIFIED,
                requireLaterAgentUse = false,
                requireSimulationInfluence = false,
                terminalPolicy = ScenarioTerminalPolicy.PROVISIONAL_NO_CANDIDATE,
            )
        }
    }
}
