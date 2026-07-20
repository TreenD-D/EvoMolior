package com.achulkov.evomolior.competition.coordinator

import com.achulkov.evomolior.competition.dispatch.DispatchMessage
import com.achulkov.evomolior.competition.leaks.LeakOracleConfig
import com.achulkov.evomolior.core.model.ScoringDimension
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression coverage proving lagged dispatches are queued until the configured lag elapses.
 */
class GroupCoordinatorDispatchLagTest {

    @Test
    fun `lagged dispatches remain queued until flushed`() = runTest {
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
            ),
            dispatchLagGenerations = 1
        )

        coordinator.onRoundStart(0)

        coordinator.dispatchLeak(
            sourceGroupId = "group-0",
            message = DispatchMessage.Text("delayed-dispatch")
        )

        val beforeFlush = withTimeoutOrNull(100) { coordinator.dispatchInboxFor("group-1").first() }
        assertNull(beforeFlush)

        val released = coordinator.flushPendingDispatches(1)
        assertEquals(1, released)

        val delivered = withTimeoutOrNull(1_000) { coordinator.dispatchInboxFor("group-1").first() }
        assertTrue(delivered is DispatchMessage.Text)
        assertEquals("delayed-dispatch", (delivered as DispatchMessage.Text).content)
    }
}
