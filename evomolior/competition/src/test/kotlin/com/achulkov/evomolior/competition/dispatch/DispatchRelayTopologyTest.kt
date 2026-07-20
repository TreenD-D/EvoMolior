package com.achulkov.evomolior.competition.dispatch

import com.achulkov.evomolior.competition.leaks.LeakOracle
import com.achulkov.evomolior.competition.leaks.LeakOracleConfig
import com.achulkov.evomolior.core.contract.GroupTopologyKind
import kotlin.test.Test
import kotlin.test.assertEquals

class DispatchRelayTopologyTest {

    @Test
    fun `ring topology keeps adjacent neighbours`() {
        val relay = relay(GroupTopologyKind.RING, listOf("group-0", "group-1", "group-2", "group-3"))

        assertEquals(listOf("group-3", "group-1"), relay.neighboursOf("group-0"))
        assertEquals(listOf("group-0", "group-2"), relay.neighboursOf("group-1"))
    }

    @Test
    fun `single group diagnostic ring has no neighbours`() {
        val relay = relay(GroupTopologyKind.RING, listOf("group-0"))

        assertEquals(emptyList(), relay.neighboursOf("group-0"))
    }

    @Test
    fun `star topology routes through the hub`() {
        val relay = relay(GroupTopologyKind.STAR, listOf("group-0", "group-1", "group-2", "group-3"))

        assertEquals(listOf("group-1", "group-2", "group-3"), relay.neighboursOf("group-0"))
        assertEquals(listOf("group-0"), relay.neighboursOf("group-2"))
    }

    @Test
    fun `small world topology adds a long range chord`() {
        val relay = relay(GroupTopologyKind.SMALL_WORLD, listOf("group-0", "group-1", "group-2", "group-3", "group-4"))

        assertEquals(listOf("group-4", "group-1", "group-2"), relay.neighboursOf("group-0"))
        assertEquals(listOf("group-0", "group-2", "group-3"), relay.neighboursOf("group-1"))
    }

    @Test
    fun `refreshing without adaptation keeps the base topology`() {
        val relay = relay(GroupTopologyKind.RING, listOf("group-0", "group-1", "group-2"))

        relay.refreshTopology()

        assertEquals(listOf("group-2", "group-1"), relay.neighboursOf("group-0"))
    }

    private fun relay(topologyKind: GroupTopologyKind, groupIds: List<String>) = DispatchRelay(
        groupIds = groupIds,
        leakOracle = LeakOracle(
            LeakOracleConfig(
                maxLeaksPerGroupPerRound = 3,
                maxMigrationPerPairPerRound = 3,
                corruptionProbability = 0.0,
                allowedDomains = setOf("general")
            )
        ),
        topologyKind = topologyKind
    )
}
