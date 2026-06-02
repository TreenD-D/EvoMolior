package com.achulkov.evokammer.orchestration.metaplanner

import com.achulkov.evokammer.core.model.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Verifies the stasis-threshold behavior of [MetaPlannerRunner].
 */
class MetaPlannerRunnerTest {

    @Test
    fun `MetaPlannerRunner triggers once at threshold and resets after spawning`() = runTest {
        val runner = MetaPlannerRunner(stasisGenerationThreshold = 3)
        val triggers = mutableListOf<Pair<String, Role>>()

        repeat(2) {
            runner.onGenerationTick("group-0") { groupId, role ->
                triggers += groupId to role
            }
        }

        assertTrue(triggers.isEmpty(), "MetaPlanner should not spawn before the threshold is reached")

        runner.onGenerationTick("group-0") { groupId, role ->
            triggers += groupId to role
        }

        assertEquals(1, triggers.size, "MetaPlanner should spawn exactly once at the threshold")
        assertEquals("group-0", triggers.single().first)
        assertEquals(Role.MetaPlanner, triggers.single().second)

        repeat(2) {
            runner.onGenerationTick("group-0") { groupId, role ->
                triggers += groupId to role
            }
        }

        assertEquals(1, triggers.size, "Counter should reset after spawning, so two more ticks are not enough to spawn again")
    }
}
