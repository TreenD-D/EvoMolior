package com.achulkov.evokammer.api

import com.achulkov.evokammer.api.events.EvoKammerEvent
import com.achulkov.evokammer.api.service.EvoKammerRunService
import com.achulkov.evokammer.competition.coordinator.GroupCoordinator
import com.achulkov.evokammer.competition.resourcemap.ResourceMap
import com.achulkov.evokammer.competition.resourcemap.ResourceMapEngine
import com.achulkov.evokammer.competition.resourcemap.ResourceNode
import com.achulkov.evokammer.competition.resourcemap.ResourceType
import com.achulkov.evokammer.competition.grader.GraderGroupManager
import com.achulkov.evokammer.orchestration.metaplanner.MetaPlannerRunner
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.reflect.jvm.isAccessible
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies the resource-node bridge emits claim events from seeded node graphs.
 */
class EvoKammerRunServiceResourceNodeBridgeTest {

    @Test
    fun `publishResourceNodeClaims emits resource node claim events from seeded node graph`() = runTest {
        val substrateFactory = com.achulkov.evokammer.substrate.SubstrateDatabaseFactory(
            kotlin.io.path.createTempDirectory("resource-bridge-test").toFile()
        )
        val service = EvoKammerRunService(
            lifecycleManagerProvider = { _, _, _, _, _ ->
                throw IllegalStateException("LifecycleManager should not be constructed in this bridge test")
            },
            groupCoordinatorProvider = { groupIds, dimensions -> GroupCoordinator(groupIds, dimensions) },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = substrateFactory,
            metaPlannerRunner = MetaPlannerRunner()
        )

        val resourceMapEngine = ResourceMapEngine(
            resourceMap = ResourceMap(
                listOf(
                    ResourceNode(
                        id = "node-a",
                        name = "node a",
                        resourceType = ResourceType.TOOL_CACHE,
                        yieldMultiplier = 1.10
                    ),
                    ResourceNode(
                        id = "node-b",
                        name = "node b",
                        resourceType = ResourceType.GPU_BURST,
                        yieldMultiplier = 1.20
                    )
                )
            )
        )

        val engineField = service.javaClass.getDeclaredField("activeResourceMapEngine").apply {
            isAccessible = true
        }
        engineField.set(service, resourceMapEngine)

        val claimDeferred = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(5_000L) {
                service.events.first { it is EvoKammerEvent.ResourceNodeClaimed }
            }
        }

        service.publishResourceNodeClaims(
            runId = "run-resource-test",
            completedWipTasksByGroup = mapOf(
                "group-a" to listOf("node-a"),
                "group-b" to listOf("node-b")
            ),
            round = 0
        )

        val event = claimDeferred.await() as EvoKammerEvent.ResourceNodeClaimed
        assertEquals("run-resource-test", event.runId)
        assertTrue(event.nodeId in setOf("node-a", "node-b"))
        assertTrue(event.groupId in setOf("group-a", "group-b"))
    }

    @Test
    fun `processMetaPlannerSignals resets stasis after completion-driven bridge`() = runTest {
        val metaPlannerRunner = MetaPlannerRunner(stasisGenerationThreshold = 2)

        val spawned = mutableListOf<String>()
        metaPlannerRunner.onGenerationTick("group-a") { groupId, role ->
            spawned += "$groupId:${role.name}"
        }
        metaPlannerRunner.onWipCompleted("group-a")

        metaPlannerRunner.onGenerationTick("group-a") { groupId, role ->
            spawned += "$groupId:${role.name}"
        }

        assertTrue(spawned.isEmpty(), "WiP completion should reset the MetaPlanner stasis counter before the next tick")
    }
}
