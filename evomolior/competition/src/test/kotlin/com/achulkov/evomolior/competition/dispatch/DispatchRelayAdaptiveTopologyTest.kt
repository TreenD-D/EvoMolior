package com.achulkov.evomolior.competition.dispatch

import com.achulkov.evomolior.competition.leaks.LeakOracle
import com.achulkov.evomolior.competition.leaks.LeakOracleConfig
import com.achulkov.evomolior.core.contract.GroupTopologyKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression coverage proving trust-aware topology adaptation can prune and rewire edges.
 */
class DispatchRelayAdaptiveTopologyTest {

    @Test
    fun `adaptive ring topology rewires low trust edges to higher trust alternatives`() {
        val relay = DispatchRelay(
            groupIds = listOf("group-0", "group-1", "group-2", "group-3"),
            leakOracle = LeakOracle(
                LeakOracleConfig(
                    maxLeaksPerGroupPerRound = 3,
                    maxMigrationPerPairPerRound = 3,
                    corruptionProbability = 0.0,
                    allowedDomains = setOf("general")
                )
            ),
            topologyKind = GroupTopologyKind.RING,
            topologyAdaptationConfig = DispatchTopologyAdaptationConfig(
                minimumTrustToRetain = 0.55,
                maxRewiredNeighbours = 1
            )
        )

        assertEquals(listOf("group-3", "group-1"), relay.neighboursOf("group-0"))

        relay.recordFeedback(
            sourceGroupId = "group-0",
            targetGroupId = "group-1",
            accepted = false
        )
        relay.refreshTopology()

        assertEquals(listOf("group-2", "group-3"), relay.neighboursOf("group-0"))
    }

    @Test
    fun `adaptive topology delivery reaches the rewired neighbour after refresh`() = runTest {
        val relay = DispatchRelay(
            groupIds = listOf("group-0", "group-1", "group-2", "group-3"),
            leakOracle = LeakOracle(
                LeakOracleConfig(
                    maxLeaksPerGroupPerRound = 3,
                    maxMigrationPerPairPerRound = 3,
                    corruptionProbability = 0.0,
                    allowedDomains = setOf("general")
                )
            ),
            topologyKind = GroupTopologyKind.RING,
            topologyAdaptationConfig = DispatchTopologyAdaptationConfig(
                minimumTrustToRetain = 0.55,
                maxRewiredNeighbours = 1
            )
        )

        relay.recordFeedback(
            sourceGroupId = "group-0",
            targetGroupId = "group-1",
            accepted = false
        )
        relay.refreshTopology()

        relay.dispatch(
            "group-0",
            DispatchMessage.Text(content = "rewired-delivery")
        )

        assertEquals("rewired-delivery", (relay.inboundFor("group-2").first() as DispatchMessage.Text).content)
    }
}
