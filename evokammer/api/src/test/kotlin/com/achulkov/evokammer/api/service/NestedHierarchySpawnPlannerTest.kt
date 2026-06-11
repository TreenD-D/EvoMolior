package com.achulkov.evokammer.api.service

import com.achulkov.evokammer.core.model.GroupProfile
import com.achulkov.evokammer.core.model.GroupProfileType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NestedHierarchySpawnPlannerTest {

    private val planner = NestedHierarchySpawnPlanner()
    private val profile = GroupProfile.default(GroupProfileType.Library)

    @Test
    fun `plan builds nested config from a stalled multi-task backlog`() {
        val plan = planner.plan(
            NestedHierarchySpawnRequest(
                runId = "run-1",
                outerGroupId = "group-0",
                round = 3,
                runObjective = "Produce a security hardening plan for a cloud platform",
                profileType = GroupProfileType.Library,
                profile = profile,
                remainingBudget = 120.0,
                openTaskDescriptions = listOf(
                    "Map identity attack surface",
                    "Assess data plane controls",
                    "Review application boundary hardening"
                ),
                openRouterApiKey = "sk-test",
                agentJarPath = "/fake/agent.jar",
                modelAssignment = mapOf("Researcher" to "test-model"),
                modelPools = mapOf("Researcher" to listOf("test-model", "fallback-model"))
            )
        )

        assertNotNull(plan)
        assertEquals("nested-group-0-r4", plan.config.nestedGroupId)
        assertEquals("group-0", plan.config.outerGroupId)
        assertEquals("run-1", plan.config.outerRunId)
        assertEquals(42.0, plan.config.budgetAllocation)
        assertEquals(NESTED_GROUP_MAX_GENERATIONS, plan.config.maxGenerations)
        assertEquals(mapOf("Researcher" to "test-model"), plan.config.modelAssignment)
        assertTrue(plan.config.objective.contains("Map identity attack surface"))
        assertTrue(plan.config.objective.contains("Assess data plane controls"))
        assertTrue(plan.progressMessage.contains("Spawning nested group nested-group-0-r4"))
    }

    @Test
    fun `plan returns null when fewer than two open tasks remain`() {
        val plan = planner.plan(
            NestedHierarchySpawnRequest(
                runId = "run-1",
                outerGroupId = "group-0",
                round = 0,
                runObjective = "objective",
                profileType = GroupProfileType.Library,
                profile = profile,
                remainingBudget = 120.0,
                openTaskDescriptions = listOf("Only one task"),
                openRouterApiKey = "sk-test",
                agentJarPath = "/fake/agent.jar",
                modelAssignment = emptyMap(),
                modelPools = emptyMap()
            )
        )

        assertNull(plan)
    }

    @Test
    fun `plan returns null when remaining budget cannot fund a nested slice`() {
        val plan = planner.plan(
            NestedHierarchySpawnRequest(
                runId = "run-1",
                outerGroupId = "group-0",
                round = 0,
                runObjective = "objective",
                profileType = GroupProfileType.Library,
                profile = profile.copy(spawnBudget = 8.0),
                remainingBudget = 20.0,
                openTaskDescriptions = listOf("Task A", "Task B"),
                openRouterApiKey = "sk-test",
                agentJarPath = "/fake/agent.jar",
                modelAssignment = emptyMap(),
                modelPools = emptyMap()
            )
        )

        assertNull(plan)
    }
}
