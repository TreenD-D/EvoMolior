package com.achulkov.evokammer.orchestration.selector

import com.achulkov.evokammer.core.contract.RoleAllocationEntry
import com.achulkov.evokammer.core.model.GroupProfile
import com.achulkov.evokammer.core.model.GroupProfileType
import com.achulkov.evokammer.core.model.Role
import com.achulkov.evokammer.orchestration.lifecycle.LifecycleManager
import com.achulkov.evokammer.sandbox.shuru.ShuruVmManager
import com.achulkov.evokammer.substrate.SubstrateDatabaseFactory
import com.achulkov.evokammer.substrate.db.entity.EventTypes
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EvolutionarySelectorDiversityTest {

    @Test
    fun `role diversity metrics are higher for balanced allocations than concentrated ones`() {
        val selector = EvolutionarySelector(
            db = SubstrateDatabaseFactory(createTempDirectory("selector-diversity-").toFile()).get("run-diversity", "group-0"),
            profile = GroupProfile.default(GroupProfileType.Library),
            runId = "run-diversity",
            groupId = "group-0"
        )

        val balanced = selector.roleDiversityMetrics(
            listOf(
                RoleAllocationEntry(Role.Researcher, 1, 50.0),
                RoleAllocationEntry(Role.Builder, 1, 50.0),
                RoleAllocationEntry(Role.Critic, 1, 50.0),
                RoleAllocationEntry(Role.Planner, 1, 50.0)
            )
        )
        val concentrated = selector.roleDiversityMetrics(
            listOf(
                RoleAllocationEntry(Role.Researcher, 3, 50.0),
                RoleAllocationEntry(Role.Builder, 1, 50.0)
            )
        )

        assertNotNull(balanced)
        assertNotNull(concentrated)
        assertTrue(balanced.normalizedEntropy > concentrated.normalizedEntropy)
        assertTrue(balanced.distinctRoleCount > concentrated.distinctRoleCount)
    }

    @Test
    fun `bounded heterogeneity policy caps outliers and preserves a minimum role floor`() {
        val selector = EvolutionarySelector(
            db = SubstrateDatabaseFactory(createTempDirectory("selector-heterogeneity-").toFile()).get("run-heterogeneity", "group-0"),
            profile = GroupProfile.default(GroupProfileType.Library),
            runId = "run-heterogeneity",
            groupId = "group-0"
        )

        val normalized = selector.enforceBoundedHeterogeneity(
            entries = listOf(
                RoleAllocationEntry(Role.Researcher, 1, 50.0),
                RoleAllocationEntry(Role.Builder, 1, 50.0),
                RoleAllocationEntry(Role.Outlier, 1, 50.0),
                RoleAllocationEntry(Role.Outlier, 1, 50.0)
            ),
            budgetCeiling = 400.0
        )

        assertEquals(1, normalized.count { it.role == Role.Outlier })
        assertTrue(normalized.map { it.role }.toSet().size >= 3)
    }

    @Test
    fun `turn taking balance swaps dominant budget into a complementary role`() {
        val selector = EvolutionarySelector(
            db = SubstrateDatabaseFactory(createTempDirectory("selector-turn-taking-").toFile()).get("run-turn-taking", "group-0"),
            profile = GroupProfile.default(GroupProfileType.Library),
            runId = "run-turn-taking",
            groupId = "group-0"
        )

        val balanced = selector.enforceTurnTakingBalance(
            entries = listOf(
                RoleAllocationEntry(Role.Researcher, 2, 100.0),
                RoleAllocationEntry(Role.Builder, 1, 50.0),
                RoleAllocationEntry(Role.Critic, 1, 50.0)
            ),
            budgetCeiling = 400.0
        )

        val budgetsByRole = balanced
            .groupBy { it.role }
            .mapValues { (_, roleEntries) -> roleEntries.sumOf { it.count * it.budgetPerAgent } }
        val dominantShare = budgetsByRole.values.max() / budgetsByRole.values.sum().toDouble()

        assertTrue(dominantShare <= 0.55)
        assertTrue(balanced.any { it.role == Role.Planner || it.role == Role.Archivist || it.role == Role.Connector || it.role == Role.Educator })
    }

    @Test
    fun `turn taking balance leaves already balanced allocations unchanged`() {
        val selector = EvolutionarySelector(
            db = SubstrateDatabaseFactory(createTempDirectory("selector-turn-taking-noop-").toFile()).get("run-turn-taking-noop", "group-0"),
            profile = GroupProfile.default(GroupProfileType.Library),
            runId = "run-turn-taking-noop",
            groupId = "group-0"
        )

        val original = listOf(
            RoleAllocationEntry(Role.Researcher, 1, 100.0),
            RoleAllocationEntry(Role.Builder, 1, 100.0),
            RoleAllocationEntry(Role.Critic, 1, 100.0),
            RoleAllocationEntry(Role.Planner, 1, 100.0)
        )

        val balanced = selector.enforceTurnTakingBalance(original, budgetCeiling = 400.0)

        assertEquals(original, balanced)
    }

    @Test
    fun `turn taking dominance signal still fires when a role remains above the threshold`() {
        val manager = LifecycleManager(
            runId = "run-turn-taking-signal",
            groupId = "group-0",
            shuruVmManager = ShuruVmManager(),
            substrateDatabaseFactory = SubstrateDatabaseFactory(createTempDirectory("selector-turn-taking-signal-").toFile()),
            agentJarPath = "/fake/agent.jar",
            openRouterApiKey = "sk-test",
            groupProfile = GroupProfile.default(GroupProfileType.Library),
            localAgentRunner = null
        )

        val signal = manager.dominantTurnTakingSignal(
            valuesByRole = mapOf(
                Role.Researcher to 180.0,
                Role.Builder to 50.0,
                Role.Critic to 20.0
            ),
            signalKind = "allocated",
            minimumDistinctRoles = 2
        )

        assertNotNull(signal)
        assertEquals(Role.Researcher, signal.dominantRole)
    }

    @Test
    fun `selector decision payload includes diversity metrics fields`() = runTest {
        val dataRoot = createTempDirectory("selector-payload-").toFile()
        val runId = "run-selector-payload"
        val groupId = "group-0"
        val db = SubstrateDatabaseFactory(dataRoot).get(runId, groupId)
        val selector = EvolutionarySelector(
            db = db,
            profile = GroupProfile.default(GroupProfileType.Library),
            runId = runId,
            groupId = groupId
        )

        selector.computeAllocation(
            generation = 1,
            globalBudgetCeiling = 500.0,
            stasisDetected = false
        )

        val payload = db.eventLogDao().getLatestByType(runId, groupId, EventTypes.SELECTOR_DECISION, 1).single().payloadJson
        assertContains(payload, "roleDiversityEntropy")
        assertContains(payload, "roleDiversityNormalized")
        assertContains(payload, "distinctRoleCount")
        assertContains(payload, "outlierShare")
    }
}
