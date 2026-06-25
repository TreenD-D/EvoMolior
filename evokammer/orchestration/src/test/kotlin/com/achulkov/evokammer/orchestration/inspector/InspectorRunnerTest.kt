package com.achulkov.evokammer.orchestration.inspector

import com.achulkov.evokammer.core.model.Role
import com.achulkov.evokammer.orchestration.watchdog.Watchdog
import com.achulkov.evokammer.orchestration.watchdog.WatchdogSignal
import kotlinx.coroutines.async
import kotlinx.coroutines.yield
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.full.callSuspend
import kotlin.reflect.jvm.isAccessible
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
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
            listOf("""INSPECTOR_FLAG:{"category":"turn_taking","description":"Role budget dominance detected"}""")
        )

        val signal = withTimeout(5_000) { signalDeferred.await() }

        val flag = signal as WatchdogSignal.InspectorFlag
        assertEquals(Role.PerspectiveTaker, flag.suggestedRole)
        assertTrue(flag.description.contains("turn-taking", ignoreCase = true))
    }
}
