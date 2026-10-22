package com.achulkov.evomolior.orchestration.selector

import com.achulkov.evomolior.core.contract.RoleAllocationEntry
import com.achulkov.evomolior.core.model.GroupProfile
import com.achulkov.evomolior.core.model.GroupProfileType
import com.achulkov.evomolior.core.model.Role
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EvolutionarySelectorSmallFleetTest {
    @Test
    fun `small fleets reserve productive owners within actual slots and profile credits`() = runTest {
        val factory = SubstrateDatabaseFactory(createTempDirectory("selector-small-fleet-").toFile())
        val runId = "run-small-fleet"
        val groupId = "group-0"
        try {
            for (type in GroupProfileType.entries) {
                val profile = GroupProfile.default(type)
                val selector = EvolutionarySelector(factory.get(runId, groupId), profile, runId, groupId)
                for (slots in listOf(1, 2, 3, profile.maxFleetSize, profile.maxFleetSize + 2)) {
                    for (multiple in listOf(0.0, 0.25, 1.0, 1.99, 2.0, 2.49, 2.5, 3.0, 10.0)) {
                        for (artifact in listOf(false, true)) {
                            // Includes normal and stasis Outlier cadence, plus a non-cadence round.
                            for ((generation, stasis) in listOf(0 to false, 1 to false, 3 to true)) {
                                val ceiling = profile.spawnBudget * multiple
                                val allocation = selector.computeAllocation(
                                    generation = generation,
                                    globalBudgetCeiling = ceiling,
                                    stasisDetected = stasis,
                                    formalDispatchDue = true,
                                    artifactWorkRequired = artifact,
                                    maximumAgents = slots,
                                )
                                val context = "$type slots=$slots ceiling=$ceiling artifact=$artifact gen=$generation"
                                assertTrue(allocation.totalAgentCount <= minOf(slots, profile.maxFleetSize), context)
                                assertTrue(allocation.totalBudget <= ceiling, context)
                                if (ceiling == 0.0) {
                                    assertEquals(0, allocation.totalAgentCount, context)
                                    continue
                                }
                                val owner = if (artifact) Role.Builder else Role.Researcher
                                assertTrue(allocation.entries.any { it.role == owner && it.count > 0 }, context)
                                if (slots == 1 || multiple < 2.0) {
                                    assertEquals(1, allocation.totalAgentCount, context)
                                    assertEquals(owner, allocation.entries.single().role, context)
                                    assertEquals(minOf(ceiling, profile.spawnBudget), allocation.totalBudget, context)
                                } else {
                                    assertTrue(allocation.entries.any { it.role == Role.Critic }, context)
                                    if (slots == 2) assertEquals(2, allocation.totalAgentCount, context)
                                }
                            }
                        }
                    }
                }
            }
        } finally {
            factory.close(runId, groupId)
        }
    }

    @Test
    fun `default fleet cap and zero slots are respected`() = runTest {
        val factory = SubstrateDatabaseFactory(createTempDirectory("selector-slot-default-").toFile())
        val runId = "run-default"
        val groupId = "group-0"
        try {
            val profile = GroupProfile.Library.copy(maxFleetSize = 1)
            val selector = EvolutionarySelector(factory.get(runId, groupId), profile, runId, groupId)
            val defaultAllocation = selector.computeAllocation(0, 500.0)
            assertEquals(Role.Researcher, defaultAllocation.entries.single().role)
            assertEquals(1, defaultAllocation.totalAgentCount)
            assertEquals(0, selector.computeAllocation(0, 500.0, maximumAgents = 0).totalAgentCount)
            assertFailsWith<IllegalArgumentException> {
                selector.computeAllocation(0, 500.0, maximumAgents = -1)
            }
        } finally {
            factory.close(runId, groupId)
        }
    }

    @Test
    fun `diversity uses actual planner cost and does not insert extra slots`() {
        val factory = SubstrateDatabaseFactory(createTempDirectory("selector-diversity-bounds-").toFile())
        val runId = "run-diversity-bounds"
        val groupId = "group-0"
        try {
            val selector = EvolutionarySelector(factory.get(runId, groupId), GroupProfile.Library, runId, groupId)
            val pair = listOf(
                RoleAllocationEntry(Role.Researcher, 1, 50.0),
                RoleAllocationEntry(Role.Critic, 1, 50.0),
            )
            assertEquals(pair, selector.enforceBoundedHeterogeneity(pair, 500.0, maximumAgents = 2))
            assertEquals(pair, selector.enforceBoundedHeterogeneity(pair, 150.0, maximumAgents = 3))
            val expanded = selector.enforceBoundedHeterogeneity(pair, 175.0, maximumAgents = 3)
            assertEquals(3, expanded.sumOf { it.count })
            assertEquals(175.0, expanded.sumOf { it.count * it.budgetPerAgent })
            val balanced = selector.enforceTurnTakingBalance(expanded, 175.0)
            assertEquals(3, balanced.sumOf { it.count })
            assertEquals(175.0, balanced.sumOf { it.count * it.budgetPerAgent })

            val unequalPair = listOf(
                RoleAllocationEntry(Role.Builder, 1, 100.0),
                RoleAllocationEntry(Role.Critic, 1, 50.0),
            )
            assertEquals(unequalPair, selector.enforceTurnTakingBalance(unequalPair, 150.0))
        } finally {
            factory.close(runId, groupId)
        }
    }

    @Test
    fun `three affordable slots retain outlier cadence without losing productive or critic roles`() = runTest {
        val factory = SubstrateDatabaseFactory(createTempDirectory("selector-small-outlier-").toFile())
        val runId = "run-small-outlier"
        val groupId = "group-0"
        try {
            val selector = EvolutionarySelector(factory.get(runId, groupId), GroupProfile.Library, runId, groupId)
            for ((generation, stasis) in listOf(0 to false, 5 to false, 3 to true)) {
                val allocation = selector.computeAllocation(
                    generation, 150.0, stasisDetected = stasis, maximumAgents = 3
                )
                assertEquals(setOf(Role.Critic, Role.Researcher, Role.Outlier), allocation.entries.map { it.role }.toSet())
                assertTrue(allocation.entries.single { it.role == Role.Outlier }.forceOutlier)
            }
            val artifactFleet = selector.computeAllocation(
                0, 200.0, artifactWorkRequired = true, maximumAgents = 4
            )
            assertEquals(
                setOf(Role.Critic, Role.Researcher, Role.Builder, Role.Outlier),
                artifactFleet.entries.map { it.role }.toSet(),
            )
        } finally {
            factory.close(runId, groupId)
        }
    }
}
