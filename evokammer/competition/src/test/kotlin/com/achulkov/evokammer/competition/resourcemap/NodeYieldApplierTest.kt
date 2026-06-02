package com.achulkov.evokammer.competition.resourcemap

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies that held resource nodes compound into a single budget multiplier.
 */
class NodeYieldApplierTest {

    @Test
    fun `budget multiplier compounds all owned node yields`() {
        val resourceMap = ResourceMap(
            listOf(
                ResourceNode(
                    id = "n1",
                    name = "tool cache",
                    resourceType = ResourceType.TOOL_CACHE,
                    yieldMultiplier = 1.10,
                    claimedByGroupId = "group-a"
                ),
                ResourceNode(
                    id = "n2",
                    name = "gpu burst",
                    resourceType = ResourceType.GPU_BURST,
                    yieldMultiplier = 1.25,
                    claimedByGroupId = "group-a"
                ),
                ResourceNode(
                    id = "n3",
                    name = "staging shortcut",
                    resourceType = ResourceType.STAGING_SHORTCUT,
                    yieldMultiplier = 1.50,
                    claimedByGroupId = "group-b"
                )
            )
        )

        val applier = NodeYieldApplier(resourceMap)

        assertEquals(1.375, applier.budgetMultiplierFor("group-a"), 0.0001)
        assertEquals(1.50, applier.budgetMultiplierFor("group-b"), 0.0001)
        assertEquals(1.0, applier.budgetMultiplierFor("group-c"), 0.0001)
    }
}
