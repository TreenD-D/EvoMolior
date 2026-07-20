package com.achulkov.evomolior.competition.resourcemap

import com.achulkov.evomolior.competition.coordinator.GroupCoordinator
import com.achulkov.evomolior.core.model.ScoringDimension
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Verifies resource-node claim and recapture behavior at the competition layer.
 */
class ResourceMapEngineTest {

    @Test
    fun `authority failure leaves resource ownership projection unchanged`() = runTest {
        val map = ResourceMap(
            listOf(
                ResourceNode(
                    id = "node-authority",
                    name = "Authority guarded",
                    resourceType = ResourceType.WEB_CACHE,
                    yieldMultiplier = 1.1,
                    qualityThreshold = 0.5
                )
            )
        )
        val engine = ResourceMapEngine(
            resourceMap = map,
            ownershipAuthority = ResourceOwnershipAuthority {
                error("Atlas transaction rejected")
            }
        )

        assertFailsWith<IllegalStateException> {
            engine.evaluateNodeClaims(
                mapOf("group-a" to mapOf("node-authority" to 0.9)),
                LegacyResourceCaptureMode.TEST_COMPATIBILITY_ONLY
            )
        }
        assertEquals(null, engine.snapshotClaims().single().claimedByGroupId)
    }

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
            ),
            ownershipAuthority = LocalProjectionResourceOwnershipAuthority
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
            ),
            legacyMode = LegacyResourceCaptureMode.TEST_COMPATIBILITY_ONLY
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
            ),
            LegacyResourceCaptureMode.TEST_COMPATIBILITY_ONLY
        )

        assertTrue(secondPass.any { it.nodeId == "node-a" && it.newOwnerId == "group-a" })
        assertTrue(secondPass.any { it.nodeId == "node-b" && it.newOwnerId == "group-a" })
    }

    @Test
    fun `node-specific grades enforce thresholds recapture and immutable floor ownership`() = runTest {
        val engine = ResourceMapEngine(
            resourceMap = ResourceMap(
                listOf(
                    ResourceNode(
                        id = "market-node",
                        name = "Market evidence",
                        resourceType = ResourceType.WEB_CACHE,
                        yieldMultiplier = 1.15,
                        deliverableNodeRef = "dst:market",
                        scoringDimensionIds = listOf("market-quality"),
                        qualityThreshold = 0.6
                    ),
                    ResourceNode(
                        id = "home-node",
                        name = "Home territory",
                        resourceType = ResourceType.GPU_BURST,
                        yieldMultiplier = 1.05,
                        deliverableNodeRef = "dst:home",
                        scoringDimensionIds = listOf("home-quality"),
                        qualityThreshold = 0.6,
                        floorGroupId = "group-a"
                    )
                )
            ),
            ownershipAuthority = LocalProjectionResourceOwnershipAuthority
        )

        val first = engine.evaluateNodeClaims(
            mapOf(
                "group-a" to mapOf("market-node" to 0.58, "home-node" to 0.1),
                "group-b" to mapOf("market-node" to 0.75, "home-node" to 0.95)
            ),
            LegacyResourceCaptureMode.TEST_COMPATIBILITY_ONLY
        )
        assertTrue(first.any { it.nodeId == "market-node" && it.newOwnerId == "group-b" })
        assertTrue(first.any { it.nodeId == "home-node" && it.newOwnerId == "group-a" && it.isFloorReclaim })

        val second = engine.evaluateNodeClaims(
            mapOf(
                "group-a" to mapOf("market-node" to 0.85, "home-node" to 0.1),
                "group-b" to mapOf("market-node" to 0.7, "home-node" to 1.0)
            ),
            LegacyResourceCaptureMode.TEST_COMPATIBILITY_ONLY
        )
        assertTrue(second.any {
            it.nodeId == "market-node" &&
                it.previousOwnerId == "group-b" &&
                it.newOwnerId == "group-a"
        })
        assertEquals("group-a", engine.snapshotClaims().first { it.id == "home-node" }.claimedByGroupId)
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
            ),
            ownershipAuthority = LocalProjectionResourceOwnershipAuthority
        )

        val events = engine.applyWipCompletions(
            mapOf(
                "group-b" to listOf("node-c"),
                "group-c" to listOf("node-d")
            ),
            LegacyResourceCaptureMode.TEST_COMPATIBILITY_ONLY
        )

        assertEquals(2, events.size)
        assertTrue(events.any { it.nodeId == "node-c" && it.newOwnerId == "group-b" })
        assertTrue(events.any { it.nodeId == "node-d" && it.newOwnerId == "group-c" })
        assertEquals("group-b", engine.snapshotClaims().first { it.id == "node-c" }.claimedByGroupId)
        assertEquals("group-c", engine.snapshotClaims().first { it.id == "node-d" }.claimedByGroupId)
    }

    @Test
    fun `coordinator budget yield uses the same map mutated by completion claims`() = runTest {
        val coordinator = GroupCoordinator(
            groupIds = listOf("group-a"),
            dimensions = listOf(
                ScoringDimension(
                    id = "correctness",
                    name = "Correctness",
                    description = "Correctness",
                    weight = 1.0
                )
            )
        )
        coordinator.registerResourceNodes(
            listOf(
                ResourceNode(
                    id = "node-e",
                    name = "yield node",
                    resourceType = ResourceType.GPU_BURST,
                    yieldMultiplier = 1.20
                )
            )
        )
        val engine = ResourceMapEngine(
            resourceMap = coordinator.resourceMap,
            ownershipAuthority = LocalProjectionResourceOwnershipAuthority
        )

        engine.applyWipCompletions(
            mapOf("group-a" to listOf("node-e")),
            LegacyResourceCaptureMode.TEST_COMPATIBILITY_ONLY
        )

        assertEquals(1.20, coordinator.legacyOwnedNodeBudgetMultiplierFor("group-a"), 0.0001)
    }
}
