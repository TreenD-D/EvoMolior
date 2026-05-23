package com.achulkov.evokammer.orchestration.lifecycle

import com.achulkov.evokammer.core.budget.BudgetLedger
import com.achulkov.evokammer.core.budget.BudgetConfig
import com.achulkov.evokammer.core.contract.AgentBootContext
import com.achulkov.evokammer.core.contract.RoleAllocation
import com.achulkov.evokammer.core.contract.RoleAllocationEntry
import com.achulkov.evokammer.core.model.GroupProfile
import com.achulkov.evokammer.core.model.GroupProfileType
import com.achulkov.evokammer.core.model.Role
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Integration-style tests for [LifecycleManager] using the in-process agent runner.
 *
 * No Shuru CLI, Room DB, or LLM is required.
 * Tests verify the spawn/kill/budget-poll lifecycle using a fake runner.
 */
class LifecycleManagerTest {

    // ── helpers ────────────────────────────────────────────────────────────────

    private val profile = GroupProfile.default(GroupProfileType.Library)
    private val budgetConfig = BudgetConfig(groupId = "group-0", profile = profile)

    /**
     * Creates a [LifecycleManager] in in-process mode with the given [runner].
     *
     * A fake [com.achulkov.evokammer.substrate.SubstrateDatabaseFactory] and
     * [com.achulkov.evokammer.sandbox.shuru.ShuruVmManager] are provided; they
     * will not be invoked in in-process mode.
     */
    private fun makeManager(
        runner: suspend (AgentBootContext, ((BudgetLedger) -> Unit)?) -> Unit
    ): LifecycleManager {
        val tmpDir = createTempDir("lm-test-")
        val substrateFactory = com.achulkov.evokammer.substrate.SubstrateDatabaseFactory(tmpDir)
        val shuruVmManager = com.achulkov.evokammer.sandbox.shuru.ShuruVmManager()

        return LifecycleManager(
            runId = "run-test",
            groupId = "group-0",
            shuruVmManager = shuruVmManager,
            substrateDatabaseFactory = substrateFactory,
            agentJarPath = "/fake/agent.jar",
            openRouterApiKey = "sk-test",
            groupProfile = profile,
            localAgentRunner = runner,
            gracePeriodSeconds = 1,
            budgetPollingIntervalMs = 100L
        )
    }

    private fun singleResearcherAllocation(budgetPerAgent: Double = 50.0) = RoleAllocation(
        runId = "run-test",
        groupId = "group-0",
        generation = 1,
        entries = listOf(RoleAllocationEntry(Role.Researcher, 1, budgetPerAgent)),
        totalBudgetCeiling = budgetPerAgent,
        rationale = "test"
    )

    // ── tests ──────────────────────────────────────────────────────────────────

    @Test
    fun `usesInProcessMode is true when localAgentRunner is provided`() {
        val manager = makeManager { _, _ -> }
        assertTrue(manager.usesInProcessMode,
            "Manager with localAgentRunner must report usesInProcessMode=true")
    }

    @Test
    fun `spawnGeneration launches an in-process agent and reports spawned callback`() = runTest {
        val spawnedAgents = mutableListOf<String>()
        val manager = makeManager { _, onBudgetLedger ->
            // Register a simple ledger
            val ledger = BudgetLedger("auto", 50.0, 2.5, budgetConfig)
            onBudgetLedger?.invoke(ledger)
            // Agent completes immediately
        }
        manager.onAgentSpawned = { agentId, _, _ -> spawnedAgents.add(agentId) }

        manager.spawnGeneration(
            allocation = singleResearcherAllocation(),
            stageDisclosure = "Stage 1",
            modelAssignments = mapOf(Role.Researcher.name to "openai/gpt-4o-mini"),
            rolePrompts = mapOf(Role.Researcher.name to "You are a researcher."),
            scope = this
        )

        // Give the coroutine a chance to run
        testScheduler.advanceUntilIdle()

        assertEquals(1, spawnedAgents.size, "Exactly one agent should have been spawned")
    }

    @Test
    fun `allocatedBudget in bootContext matches budgetPerAgent from allocation`() = runTest {
        var receivedBudget = 0.0
        val manager = makeManager { bootContext, onBudgetLedger ->
            receivedBudget = bootContext.allocatedBudget
            val ledger = BudgetLedger(bootContext.agentId, bootContext.allocatedBudget, 2.5, budgetConfig)
            onBudgetLedger?.invoke(ledger)
        }

        val allocationBudget = 75.0
        manager.spawnGeneration(
            allocation = singleResearcherAllocation(budgetPerAgent = allocationBudget),
            stageDisclosure = "",
            modelAssignments = emptyMap(),
            rolePrompts = emptyMap(),
            scope = this
        )
        testScheduler.advanceUntilIdle()

        assertEquals(allocationBudget, receivedBudget, 0.001,
            "bootContext.allocatedBudget must equal the RoleAllocationEntry.budgetPerAgent")
    }

    @Test
    fun `getLiveAgentCount decrements after agent completes`() = runTest {
        val manager = makeManager { _, onBudgetLedger ->
            val ledger = BudgetLedger("auto", 50.0, 2.5, budgetConfig)
            onBudgetLedger?.invoke(ledger)
            // Agent completes immediately
        }

        manager.spawnGeneration(
            allocation = singleResearcherAllocation(),
            stageDisclosure = "",
            modelAssignments = emptyMap(),
            rolePrompts = emptyMap(),
            scope = this
        )
        testScheduler.advanceUntilIdle()

        assertEquals(0, manager.getLiveAgentCount(),
            "Live agent count should be 0 after agent completes")
    }

    @Test
    fun `terminateAll cancels running agents`() = runTest {
        val startedFlag = kotlinx.coroutines.CompletableDeferred<Unit>()
        val cancelledFlag = kotlinx.coroutines.CompletableDeferred<Unit>()
        val manager = makeManager { _, onBudgetLedger ->
            val ledger = BudgetLedger("auto", 50.0, 2.5, budgetConfig)
            onBudgetLedger?.invoke(ledger)
            startedFlag.complete(Unit)
            // Long-running agent — blocks until cancelled
            try {
                kotlinx.coroutines.delay(600_000)
            } catch (_: kotlinx.coroutines.CancellationException) {
                cancelledFlag.complete(Unit)
                throw kotlinx.coroutines.CancellationException()
            }
        }

        val agentJob = launch {
            manager.spawnGeneration(
                allocation = singleResearcherAllocation(),
                stageDisclosure = "",
                modelAssignments = emptyMap(),
                rolePrompts = emptyMap(),
                scope = this
            )
        }
        testScheduler.advanceUntilIdle()
        startedFlag.await()

        manager.terminateAll()
        testScheduler.advanceUntilIdle()

        assertTrue(cancelledFlag.isCompleted,
            "terminateAll should cancel the running agent coroutine")
        agentJob.cancel()
    }
}
