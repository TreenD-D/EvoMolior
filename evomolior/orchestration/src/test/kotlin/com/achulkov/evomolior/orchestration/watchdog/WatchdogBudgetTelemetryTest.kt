package com.achulkov.evomolior.orchestration.watchdog

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals

/** Verifies generation-scoped Watchdog budget threshold crossings. */
class WatchdogBudgetTelemetryTest {

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
