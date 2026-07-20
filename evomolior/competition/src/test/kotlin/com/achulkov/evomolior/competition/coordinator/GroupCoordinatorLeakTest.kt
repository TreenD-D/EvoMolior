package com.achulkov.evomolior.competition.coordinator

import com.achulkov.evomolior.competition.leaks.LeakOracleConfig
import com.achulkov.evomolior.core.contract.LeakPacket
import com.achulkov.evomolior.core.model.ScoringDimension
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression coverage for the leak-oracle wiring in [GroupCoordinator].
 */
class GroupCoordinatorLeakTest {

    @Test
    fun `patched migration ceiling limits leak allowance`() = runTest {
        val coordinator = GroupCoordinator(
            groupIds = listOf("group-0", "group-1"),
            dimensions = listOf(
                ScoringDimension(
                    id = "accuracy",
                    name = "Accuracy",
                    description = "Score accuracy",
                    weight = 1.0
                )
            ),
            leakConfig = LeakOracleConfig(
                maxLeaksPerGroupPerRound = 3,
                corruptionProbability = 0.0,
                allowedDomains = setOf("general")
            )
        )

        coordinator.updateMigrationCeiling("group-0", 1)

        val packet = LeakPacket(
            leakId = "leak-1",
            content = "shared knowledge",
            domain = "general",
            recipientGroupId = "group-1",
            deliveredAtGeneration = 0,
            runId = "run-leak-test"
        )

        assertTrue(coordinator.leakOracle.process("group-0", "group-1", packet) != null)
        assertNull(coordinator.leakOracle.process("group-0", "group-1", packet))
    }
}
