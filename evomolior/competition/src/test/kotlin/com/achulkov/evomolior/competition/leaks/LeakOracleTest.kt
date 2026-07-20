package com.achulkov.evomolior.competition.leaks

import com.achulkov.evomolior.core.contract.LeakPacket
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression coverage for the Leak Oracle's dynamic ceiling behavior.
 */
class LeakOracleTest {

    @Test
    fun `dynamic group ceiling blocks leaks after the patched limit`() = kotlinx.coroutines.test.runTest {
        val oracle = LeakOracle(
            LeakOracleConfig(
                maxLeaksPerGroupPerRound = 3,
                corruptionProbability = 0.0,
                allowedDomains = setOf("general")
            )
        )

        oracle.patchGroupCeiling("group-0", 1)

        val packet = LeakPacket(
            leakId = "leak-1",
            content = "shared knowledge",
            domain = "general",
            recipientGroupId = "group-1",
            deliveredAtGeneration = 0,
            runId = "run-leak-test"
        )

        assertTrue(oracle.process("group-0", "group-1", packet) != null)
        assertNull(oracle.process("group-0", "group-1", packet))
    }

    @Test
    fun `pair-specific ceiling blocks repeated leaks to the same target`() = kotlinx.coroutines.test.runTest {
        val oracle = LeakOracle(
            LeakOracleConfig(
                maxLeaksPerGroupPerRound = 10,
                maxMigrationPerPairPerRound = 1,
                corruptionProbability = 0.0,
                allowedDomains = setOf("general")
            )
        )

        val packet = LeakPacket(
            leakId = "leak-pair-1",
            content = "shared knowledge",
            domain = "general",
            recipientGroupId = "group-1",
            deliveredAtGeneration = 0,
            runId = "run-leak-test"
        )

        assertTrue(oracle.process("group-0", "group-1", packet) != null)
        assertNull(oracle.process("group-0", "group-1", packet))
        assertTrue(
            oracle.process(
                "group-0",
                "group-2",
                packet.copy(leakId = "leak-pair-2", recipientGroupId = "group-2")
            ) != null
        )
    }
}
