package com.achulkov.evomolior.competition.coordinator

import com.achulkov.evomolior.competition.dispatch.DispatchTopologyAdaptationConfig
import com.achulkov.evomolior.competition.dispatch.DispatchMessage
import com.achulkov.evomolior.competition.leaks.LeakOracleConfig
import com.achulkov.evomolior.core.model.ScoringDimension
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression coverage proving the coordinator refreshes adaptive graph edges at round boundaries.
 */
class GroupCoordinatorAdaptiveTopologyTest {

    @Test
    fun `adaptive topology is refreshed when a new round starts`() {
        runTest {
            val coordinator = GroupCoordinator(
                groupIds = listOf("group-0", "group-1", "group-2", "group-3"),
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
                    maxMigrationPerPairPerRound = 3,
                    corruptionProbability = 0.0,
                    allowedDomains = setOf("general")
                ),
                initialDispatchTopologyAdaptationConfig = DispatchTopologyAdaptationConfig(
                    minimumTrustToRetain = 0.55,
                    maxRewiredNeighbours = 1
                )
            )

            assertEquals(listOf("group-3", "group-1"), coordinator.neighboursOf("group-0"))

            coordinator.recordDispatchFeedback(
                targetGroupId = "group-1",
                sourceGroupId = "group-0",
                accepted = false
            )

            assertEquals(listOf("group-3", "group-1"), coordinator.neighboursOf("group-0"))

            coordinator.onRoundStart(1)

            assertEquals(listOf("group-2", "group-3"), coordinator.neighboursOf("group-0"))
        }
    }

    @Test
    fun `rewired edges carry messages after round boundary refresh`() {
        runTest {
            val coordinator = GroupCoordinator(
                groupIds = listOf("group-0", "group-1", "group-2", "group-3"),
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
                    maxMigrationPerPairPerRound = 3,
                    corruptionProbability = 0.0,
                    allowedDomains = setOf("general")
                ),
                initialDispatchTopologyAdaptationConfig = DispatchTopologyAdaptationConfig(
                    minimumTrustToRetain = 0.55,
                    maxRewiredNeighbours = 1
                )
            )

            coordinator.recordDispatchFeedback(
                targetGroupId = "group-1",
                sourceGroupId = "group-0",
                accepted = false
            )
            coordinator.onRoundStart(1)

            coordinator.dispatchLeak(
                sourceGroupId = "group-0",
                message = DispatchMessage.Text("round-boundary-rewire")
            )

            assertEquals(
                "round-boundary-rewire",
                (coordinator.dispatchInboxFor("group-2").first() as DispatchMessage.Text).content
            )
        }
    }
}
