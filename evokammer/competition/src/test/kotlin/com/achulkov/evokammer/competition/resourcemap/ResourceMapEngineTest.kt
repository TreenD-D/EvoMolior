package com.achulkov.evokammer.competition.resourcemap

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies resource-node claim and recapture behavior at the competition layer.
 */
class ResourceMapEngineTest {

    @Test
    fun `evaluateClaims claims unowned nodes and recaptures on stronger scores`() = runTest {
        val engine = ResourceMapEngine(
            resourceMap = ResourceMap(
                listOf(
                    ResourceNode(
                        id = "node-a",
                        name = "tool cache node",
                        resourceType = ResourceType.TOOL_CACHE,
                        yieldMultiplier = 1.10
                    ),
                    ResourceNode(
                        id = "node-b",
                        name = "gpu burst node",
                        resourceType = ResourceType.GPU_BURST,
                        yieldMultiplier = 1.20,
                        claimedByGroupId = "group-a"
                    )
                )
            )
        )

        val firstPass = engine.evaluateClaims(
            qualityScores = mapOf(
                "group-a" to mapOf(
                    ResourceType.TOOL_CACHE to 0.4,
                    ResourceType.GPU_BURST to 0.5
                ),
                "group-b" to mapOf(
                    ResourceType.TOOL_CACHE to 0.8,
                    ResourceType.GPU_BURST to 0.7
                )
            )
        )

        assertEquals(2, firstPass.size)
        assertTrue(firstPass.any { it.nodeId == "node-a" && it.newOwnerId == "group-b" })
        assertTrue(firstPass.any { it.nodeId == "node-b" && it.newOwnerId == "group-b" })

        val secondPass = engine.evaluateClaims(
            qualityScores = mapOf(
                "group-a" to mapOf(
                    ResourceType.TOOL_CACHE to 0.95,
                    ResourceType.GPU_BURST to 0.95
                ),
                "group-b" to mapOf(
                    ResourceType.TOOL_CACHE to 0.1,
                    ResourceType.GPU_BURST to 0.1
                )
            )
        )

        assertTrue(secondPass.any { it.nodeId == "node-a" && it.newOwnerId == "group-a" })
        assertTrue(secondPass.any { it.nodeId == "node-b" && it.newOwnerId == "group-a" })
    }

    @Test
    fun `applyWipCompletions claims nodes directly from completion stream`() = runTest {
        val engine = ResourceMapEngine(
            resourceMap = ResourceMap(
                listOf(
                    ResourceNode(
                        id = "node-c",
                        name = "staging node",
                        resourceType = ResourceType.STAGING_SHORTCUT,
                        yieldMultiplier = 1.05
                    ),
                    ResourceNode(
                        id = "node-d",
                        name = "relay node",
                        resourceType = ResourceType.GOSSIP_RELAY,
                        yieldMultiplier = 1.10,
                        claimedByGroupId = "group-a"
                    )
                )
            )
        )

        val events = engine.applyWipCompletions(
            mapOf(
                "group-b" to listOf("node-c"),
                "group-c" to listOf("node-d")
            )
        )

        assertEquals(2, events.size)
        assertTrue(events.any { it.nodeId == "node-c" && it.newOwnerId == "group-b" })
        assertTrue(events.any { it.nodeId == "node-d" && it.newOwnerId == "group-c" })
        assertEquals("group-b", engine.snapshotClaims().first { it.id == "node-c" }.claimedByGroupId)
        assertEquals("group-c", engine.snapshotClaims().first { it.id == "node-d" }.claimedByGroupId)
    }
}
