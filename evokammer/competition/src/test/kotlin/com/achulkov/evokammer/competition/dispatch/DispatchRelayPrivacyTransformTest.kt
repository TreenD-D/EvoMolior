package com.achulkov.evokammer.competition.dispatch

import com.achulkov.evokammer.competition.leaks.LeakOracle
import com.achulkov.evokammer.competition.leaks.LeakOracleConfig
import com.achulkov.evokammer.core.contract.LeakPacket
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression coverage proving leak packets are transformed before delivery when a
 * privacy transform is configured on the dispatch relay.
 */
class DispatchRelayPrivacyTransformTest {

    @Test
    fun `leak packets are transformed before reaching the target inbox`() = runTest {
        val relay = DispatchRelay(
            groupIds = listOf("group-0", "group-1"),
            leakOracle = LeakOracle(
                LeakOracleConfig(
                    maxLeaksPerGroupPerRound = 3,
                    maxMigrationPerPairPerRound = 3,
                    corruptionProbability = 0.0,
                    allowedDomains = setOf("general")
                )
            ),
            leakTransformer = { packet ->
                packet.copy(content = "TRANSFORMED:${packet.content}")
            }
        )

        val packet = LeakPacket(
            leakId = "leak-privacy-1",
            content = "sensitive source content",
            domain = "general",
            recipientGroupId = "group-1",
            deliveredAtGeneration = 0,
            runId = "run-privacy-test"
        )

        relay.dispatch("group-0", DispatchMessage.Leak(packet))

        val delivered = relay.inboundFor("group-1").first()
        assertTrue(delivered is DispatchMessage.Leak)
        assertEquals(
            "TRANSFORMED:sensitive source content",
            (delivered as DispatchMessage.Leak).packet.content
        )
    }
}
