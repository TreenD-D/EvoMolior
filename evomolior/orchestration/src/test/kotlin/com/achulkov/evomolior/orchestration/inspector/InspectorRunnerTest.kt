package com.achulkov.evomolior.orchestration.inspector

import com.achulkov.evomolior.core.model.Role
import com.achulkov.evomolior.orchestration.watchdog.Watchdog
import com.achulkov.evomolior.orchestration.watchdog.WatchdogSignal
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.full.callSuspend
import kotlin.reflect.jvm.isAccessible
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first

class InspectorRunnerTest {

    @Test
    fun `turn-taking inspector events request a PerspectiveTaker`() = runTest {
        val watchdog = Watchdog()
        val runner = InspectorRunner(watchdog = watchdog, checkIntervalMs = 1)

        val signalDeferred = async {
            watchdog.signals.first {
                it is WatchdogSignal.InspectorFlag && it.suggestedRole == Role.PerspectiveTaker
            }
        }
        yield()

        val analyseEvents = InspectorRunner::class.declaredFunctions
            .first { it.name == "analyseEvents" }
            .apply { isAccessible = true }

        analyseEvents.callSuspend(
            runner,
            "group-0",
            listOf(
                InspectorObservation(
                    eventId = "turn-taking-1",
                    kind = InspectorObservationKind.TURN_TAKING,
                    outcome = InspectorObservationOutcome.OBSERVED
                )
            )
        )

        val signal = withTimeout(5_000) { signalDeferred.await() }

        val flag = signal as WatchdogSignal.InspectorFlag
        assertEquals(Role.PerspectiveTaker, flag.suggestedRole)
        assertTrue(flag.description.contains("turn-taking", ignoreCase = true))
    }

    @Test
    fun `blocked egress events remain distinct and do not become incidents on repeated scans`() = runTest {
        val watchdog = Watchdog()
        val runner = InspectorRunner(watchdog = watchdog, checkIntervalMs = 1)
        val signals = mutableListOf<WatchdogSignal>()
        val collector = launch { watchdog.signals.toList(signals) }
        yield()
        val analyseEvents = InspectorRunner::class.declaredFunctions
            .first { it.name == "analyseEvents" }
            .apply { isAccessible = true }
        val blockedEvents = listOf(
            InspectorObservation(
                eventId = "egress-block-1",
                kind = InspectorObservationKind.CONSTITUTIONAL_ENFORCEMENT,
                outcome = InspectorObservationOutcome.BLOCKED,
                actionType = "NETWORK_EGRESS"
            ),
            InspectorObservation(
                eventId = "egress-block-2",
                kind = InspectorObservationKind.CONSTITUTIONAL_ENFORCEMENT,
                outcome = InspectorObservationOutcome.BLOCKED,
                actionType = "NETWORK_EGRESS"
            )
        )

        analyseEvents.callSuspend(runner, "group-0", blockedEvents)
        analyseEvents.callSuspend(runner, "group-0", blockedEvents)
        yield()

        assertFalse(signals.any { it is WatchdogSignal.InspectorFlag })
        val seenField = runner.javaClass.getDeclaredField("seenObservationIds").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val seen = seenField.get(runner) as Map<String, Set<String>>
        assertEquals(setOf("egress-block-1", "egress-block-2"), seen["group-0"])
        collector.cancel()
    }

    @Test
    fun `confirmed violation emits only once when the same window is rescanned`() = runTest {
        val watchdog = Watchdog()
        val runner = InspectorRunner(watchdog = watchdog, checkIntervalMs = 1)
        val signals = mutableListOf<WatchdogSignal>()
        val collector = launch { watchdog.signals.toList(signals) }
        yield()
        val analyseEvents = InspectorRunner::class.declaredFunctions
            .first { it.name == "analyseEvents" }
            .apply { isAccessible = true }
        val events = listOf(
            InspectorObservation(
                eventId = "confirmed-1",
                kind = InspectorObservationKind.CONSTITUTIONAL_ENFORCEMENT,
                outcome = InspectorObservationOutcome.CONFIRMED_VIOLATION
            )
        )

        analyseEvents.callSuspend(runner, "group-0", events)
        analyseEvents.callSuspend(runner, "group-0", events)
        yield()

        assertEquals(1, signals.count { it is WatchdogSignal.InspectorFlag })
        collector.cancel()
    }
}
