package com.achulkov.evomolior.competition.dispatch

import com.achulkov.evomolior.competition.leaks.LeakOracle
import com.achulkov.evomolior.competition.leaks.LeakOracleConfig
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression coverage proving relay trust feedback is tracked on the dispatch path.
 */
class DispatchRelayTrustScoringTest {

    @Test
    fun `relay exposes and updates source trust scores`() {
        val relay = DispatchRelay(
            groupIds = listOf("group-0", "group-1"),
            leakOracle = LeakOracle(
                LeakOracleConfig(
                    maxLeaksPerGroupPerRound = 3,
                    maxMigrationPerPairPerRound = 3,
                    corruptionProbability = 0.0,
                    allowedDomains = setOf("general")
                )
            )
        )

        assertEquals(0.5, relay.trustScoreOf("group-0"))
        relay.recordFeedback("group-0", accepted = false)
        assertEquals(0.42, relay.trustScoreOf("group-0"))
    }
}
