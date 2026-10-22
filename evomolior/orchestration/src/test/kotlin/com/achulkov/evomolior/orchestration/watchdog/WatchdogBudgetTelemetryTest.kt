package com.achulkov.evomolior.orchestration.watchdog

import com.achulkov.evomolior.orchestration.lifecycle.LifecycleBudgetTelemetry
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals

/** Verifies generation-scoped Watchdog budget threshold crossings. */
class WatchdogBudgetTelemetryTest {

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `settled unused credits and empty fleet do not signal depletion`() = runTest {
        val watchdog = Watchdog()
        val signals = mutableListOf<WatchdogSignal>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            watchdog.signals.collect { signals += it }
        }
        watchdog.updateBudget("group", telemetry(live = 30.0, revision = 1))
        watchdog.updateBudget(
            "group",
            telemetry(live = 0.0, revision = 2).copy(
                liveAllocatedCredits = 0.0, liveAgentCount = 0,
                spentCredits = 7.5, returnedCredits = 30.0
            )
        )
        assertEquals(emptyList(), signals)
        // A delayed pre-exit observation cannot resurrect a depletion alarm.
        watchdog.updateBudget("group", telemetry(live = 1.0, revision = 1))
        assertEquals(emptyList(), signals)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `live allocation denominator excludes completed peers and threshold resets next generation`() = runTest {
        val watchdog = Watchdog()
        val signals = mutableListOf<WatchdogSignal.BudgetCritical>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            watchdog.signals.collect { if (it is WatchdogSignal.BudgetCritical) signals += it }
        }
        // 2/37.5 would alarm, but this agent still has 40% of its own live allocation.
        val partial = telemetry(live = 2.0, revision = 1).copy(
            liveAllocatedCredits = 5.0, returnedCredits = 30.0, spentCredits = 5.5
        )
        watchdog.updateBudget("group", partial)
        assertEquals(0, signals.size)
        watchdog.updateBudget("group", partial.copy(liveCredits = 0.5, revision = 2))
        watchdog.updateBudget("group", partial.copy(liveCredits = 0.4, revision = 3))
        assertEquals(1, signals.size)
        assertEquals(5.0, signals.single().generationBudget)
        assertEquals(0.5, signals.single().remainingBudget)
        watchdog.updateBudget("group", telemetry(live = 1.0, revision = 4).copy(generation = 2))
        watchdog.updateBudget("group", telemetry(live = 0.0, revision = 5))
        assertEquals(2, signals.size)
        assertEquals(2, signals.last().generation)
    }

    private fun telemetry(live: Double, revision: Long) = LifecycleBudgetTelemetry(
        generation = 1,
        allocatedCredits = 37.5,
        liveAllocatedCredits = 37.5,
        liveCredits = live,
        spentCredits = 37.5 - live,
        returnedCredits = 0.0,
        liveAgentCount = 1,
        revision = revision
    )

    @Test
    fun `budget critical carries the authoritative generation allocation`() = runTest {
        val watchdog = Watchdog()
        val signal = async {
            watchdog.signals.first { it is WatchdogSignal.BudgetCritical } as WatchdogSignal.BudgetCritical
        }
        yield()

        watchdog.beginGenerationBudget(groupId = "group-0", totalBudget = 100.0)
        watchdog.updateBudget(
            groupId = "group-0",
            remainingBudget = 10.0,
            totalBudget = 100.0,
            generation = 3
        )

        val critical = signal.await()
        assertEquals("group-0", critical.groupId)
        assertEquals(10.0, critical.remainingBudget)
        assertEquals(100.0, critical.generationBudget)
        assertEquals(3, critical.generation)
    }
}
