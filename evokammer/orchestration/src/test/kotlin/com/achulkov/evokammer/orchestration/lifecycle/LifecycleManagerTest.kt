package com.achulkov.evokammer.orchestration.lifecycle

import com.achulkov.evokammer.core.budget.BudgetLedger
import com.achulkov.evokammer.core.budget.BudgetConfig
import com.achulkov.evokammer.core.contract.AgentBootContext
import com.achulkov.evokammer.core.contract.BlockingAgentAbortException
import com.achulkov.evokammer.core.contract.RoleAllocation
import com.achulkov.evokammer.core.contract.RoleAllocationEntry
import com.achulkov.evokammer.core.model.GroupProfile
import com.achulkov.evokammer.core.model.GroupProfileType
import com.achulkov.evokammer.core.model.Role
import com.achulkov.evokammer.sandbox.network.ContextUrlAllowlistExtractor
import com.achulkov.evokammer.sandbox.shuru.NetworkAllowlistConfig
import com.achulkov.evokammer.substrate.db.entity.WipTaskEntity
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

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
        modelSelectionRandom: Random = Random(0),
        runner: suspend (AgentBootContext, ((BudgetLedger) -> Unit)?) -> Unit
    ): LifecycleManager {
        val tmpDir = createTempDirectory("lm-test-").toFile()
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
            modelSelectionRandom = modelSelectionRandom,
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

    /**
     * Polls a value on a real dispatcher until it becomes available.
     *
     * The lifecycle manager runs some work on background coroutines outside the test scheduler,
     * so tests that observe DB or boot-context side effects need a real-time wait helper.
     */
    private suspend fun <T : Any> awaitRealTimeValue(
        timeoutMs: Long = 5_000,
        pollIntervalMs: Long = 10,
        supplier: suspend () -> T?
    ): T = withContext(Dispatchers.Default) {
        withTimeout(timeoutMs) {
            while (true) {
                val value = supplier()
                if (value != null) return@withTimeout value
                kotlinx.coroutines.delay(pollIntervalMs)
            }
            error("unreachable")
        }
    }

    // ── tests ──────────────────────────────────────────────────────────────────

    @Test
    fun `usesInProcessMode is true when localAgentRunner is provided`() {
        val manager = makeManager { _, _ -> }
        assertTrue(manager.usesInProcessMode,
            "Manager with localAgentRunner must report usesInProcessMode=true")
    }

    @Test
    fun `networkAllowlistFor maps web roles to broad public web access and offline roles to no internet`() {
        val manager = makeManager { _, _ -> }

        assertEquals(NetworkAllowlistConfig.RESEARCHER, manager.networkAllowlistFor(Role.Researcher))
        assertEquals(NetworkAllowlistConfig.BUILDER, manager.networkAllowlistFor(Role.Builder))
        assertEquals(NetworkAllowlistConfig.BROAD, manager.networkAllowlistFor(Role.Outlier))
        assertEquals(NetworkAllowlistConfig.NO_INTERNET, manager.networkAllowlistFor(Role.Planner))
        assertEquals(NetworkAllowlistConfig.NO_INTERNET, manager.networkAllowlistFor(Role.Critic))
        assertEquals(NetworkAllowlistConfig.NO_INTERNET, manager.networkAllowlistFor(Role.Archivist))
        assertEquals(NetworkAllowlistConfig.NO_INTERNET, manager.networkAllowlistFor(Role.MetaPlanner))
    }

    @Test
    fun `networkAllowlistFor merges explicitly linked hosts into the role policy`() {
        val manager = makeManager { _, _ -> }
        val allowlist = manager.networkAllowlistFor(
            Role.Planner,
            ContextUrlAllowlistExtractor.extractHosts(
                "Review https://docs.example.com/guide and www.example.org/reference before you proceed."
            )
        )

        assertEquals(
            setOf("docs.example.com", "www.example.org"),
            allowlist.allowedDomains
        )
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
            modelPools = emptyMap(),
            rolePrompts = mapOf(Role.Researcher.name to "You are a researcher."),
            scope = backgroundScope
        )

        // Give the coroutine a chance to run
        testScheduler.advanceUntilIdle()

        assertEquals(1, spawnedAgents.size, "Exactly one agent should have been spawned")
        manager.terminateAll()
        testScheduler.advanceUntilIdle()
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
            modelPools = emptyMap(),
            rolePrompts = emptyMap(),
            scope = backgroundScope
        )
        testScheduler.advanceUntilIdle()

        assertEquals(allocationBudget, receivedBudget, 0.001,
            "bootContext.allocatedBudget must equal the RoleAllocationEntry.budgetPerAgent")
        manager.terminateAll()
        testScheduler.advanceUntilIdle()
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
            modelPools = emptyMap(),
            rolePrompts = emptyMap(),
            scope = backgroundScope
        )
        testScheduler.advanceUntilIdle()

        assertEquals(0, manager.getLiveAgentCount(),
            "Live agent count should be 0 after agent completes")
        manager.terminateAll()
        testScheduler.advanceUntilIdle()
    }

    @Test
    fun `spawnGeneration draws models from the role pool per agent spawn`() = runTest {
        val seenModels = java.util.concurrent.CopyOnWriteArrayList<String>()
        val manager = makeManager(
            runner = { bootContext, onBudgetLedger ->
                val ledger = BudgetLedger("auto", 50.0, 2.5, budgetConfig)
                onBudgetLedger?.invoke(ledger)
                seenModels.add(bootContext.modelAssignment)
            },
            modelSelectionRandom = Random(1)
        )

        val allocation = RoleAllocation(
            runId = "run-test",
            groupId = "group-0",
            generation = 1,
            entries = listOf(RoleAllocationEntry(Role.Researcher, 2, 50.0)),
            totalBudgetCeiling = 100.0,
            rationale = "test"
        )

        manager.spawnGeneration(
            allocation = allocation,
            stageDisclosure = "",
            modelAssignments = mapOf(Role.Researcher.name to "openai/gpt-4o-mini"),
            modelPools = mapOf(
                Role.Researcher.name to listOf(
                    "openai/gpt-4o-mini",
                    "deepseek/deepseek-v4-flash"
                )
            ),
            rolePrompts = mapOf(Role.Researcher.name to "You are a researcher."),
            scope = backgroundScope
        )
        testScheduler.advanceUntilIdle()

        assertEquals(2, seenModels.size)
        assertEquals(
            setOf("deepseek/deepseek-v4-flash", "openai/gpt-4o-mini"),
            seenModels.toSet()
        )
        manager.terminateAll()
        testScheduler.advanceUntilIdle()
    }

    @Test
    fun `terminateAll cancels running agents`() = runTest {
        val startedFlag = kotlinx.coroutines.CompletableDeferred<Unit>()
        val cancelledFlag = kotlinx.coroutines.CompletableDeferred<Unit>()
        val diedFlag = kotlinx.coroutines.CompletableDeferred<Unit>()
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
        manager.onAgentDied = { _, _ -> diedFlag.complete(Unit) }

        val agentJob = launch {
            manager.spawnGeneration(
                allocation = singleResearcherAllocation(),
                stageDisclosure = "",
                modelAssignments = emptyMap(),
                modelPools = emptyMap(),
                rolePrompts = emptyMap(),
                scope = backgroundScope
            )
        }
        testScheduler.advanceUntilIdle()
        startedFlag.await()

        manager.terminateAll()
        testScheduler.advanceUntilIdle()

        assertTrue(
            cancelledFlag.isCompleted || diedFlag.isCompleted || manager.getLiveAgentCount() == 0,
            "terminateAll should cancel the running agent coroutine and/or report agent death"
        )
        agentJob.cancel()
    }

    @Test
    fun `spawnSpecialAgent does not inject WIP claim or Legacy Protocol`() = runTest {
        val seenContexts = mutableListOf<AgentBootContext>()
        val manager = makeManager { bootContext, _ ->
            seenContexts += bootContext
        }

        manager.spawnSpecialAgent(
            role = Role.MetaPlanner,
            generation = 7,
            budgetPerAgent = 60.0,
            stageDisclosure = "",
            modelAssignment = "anthropic/claude-sonnet-4",
            rolePrompt = "You are a MetaPlanner.",
            scope = backgroundScope
        )

        val seenContext = awaitRealTimeValue { seenContexts.firstOrNull() }
        manager.terminateAll()

        assertEquals(1, seenContexts.size, "Special spawn should create exactly one boot context")
        assertNull(seenContext.wipTaskClaim, "MetaPlanner must not consume a WIP task claim")
        assertNull(seenContext.legacyProtocol, "MetaPlanner must not inherit a Legacy Protocol")
    }

    @Test
    fun `blocking agent abort is treated as a kill and refunds the full group allocation`() = runTest {
        var lastBudgetTotal = -1.0
        val readyToAbort = kotlinx.coroutines.CompletableDeferred<Unit>()
        val spawned = kotlinx.coroutines.CompletableDeferred<Unit>()
        val manager = makeManager { _, onBudgetLedger ->
            val ledger = BudgetLedger("auto", 50.0, 2.5, budgetConfig)
            onBudgetLedger?.invoke(ledger)
            readyToAbort.await()
            ledger.deductRaw(12.0, "blocking-test-spend")
            throw BlockingAgentAbortException("tool validation failed")
        }
        manager.onBudgetChanged = { lastBudgetTotal = it }
        manager.onAgentSpawned = { _, _, _ -> spawned.complete(Unit) }

        manager.spawnGeneration(
            allocation = singleResearcherAllocation(),
            stageDisclosure = "",
            modelAssignments = emptyMap(),
            modelPools = emptyMap(),
            rolePrompts = mapOf(Role.Researcher.name to "You are a researcher."),
            scope = backgroundScope
        )
        spawned.await()
        readyToAbort.complete(Unit)
        testScheduler.advanceUntilIdle()
        awaitRealTimeValue { manager.getLiveAgentCount().takeIf { it == 0 } }
        awaitRealTimeValue { lastBudgetTotal.takeIf { it >= 0.0 } }
        assertEquals(0, manager.getLiveAgentCount(), "Blocking failure must remove the agent from the live set")
        assertEquals(50.0, lastBudgetTotal, 0.001, "Blocking failure must refund the full agent allocation")
        manager.terminateAll()
        testScheduler.advanceUntilIdle()
    }

    @Test
    fun `claimed WIP task is injected into the boot context`() = runTest {
        val tmpDir = createTempDirectory("lm-wip-abandon-").toFile()
        val substrateFactory = com.achulkov.evokammer.substrate.SubstrateDatabaseFactory(tmpDir)
        val db = substrateFactory.get("run-test", "group-0")
        db.wipTaskDao().insertAll(
            listOf(
                WipTaskEntity(
                    nodeId = "node-1",
                    runId = "run-test",
                    groupId = "group-0",
                    description = "draft the first plan",
                    status = "unclaimed",
                    lastChangedGeneration = 0
                )
            )
        )

        val seenClaims = mutableListOf<String?>()
        val manager = LifecycleManager(
            runId = "run-test",
            groupId = "group-0",
            shuruVmManager = com.achulkov.evokammer.sandbox.shuru.ShuruVmManager(),
            substrateDatabaseFactory = substrateFactory,
            agentJarPath = "/fake/agent.jar",
            openRouterApiKey = "sk-test",
            groupProfile = profile,
            localAgentRunner = { bootContext, onBudgetLedger ->
                val ledger = BudgetLedger("auto", 50.0, 2.5, budgetConfig)
                onBudgetLedger?.invoke(ledger)
                seenClaims += bootContext.wipTaskClaim?.nodeId
            },
            gracePeriodSeconds = 1,
            budgetPollingIntervalMs = 100L
        )

        manager.spawnGeneration(
            allocation = singleResearcherAllocation(),
            stageDisclosure = "",
            modelAssignments = emptyMap(),
            modelPools = emptyMap(),
            rolePrompts = emptyMap(),
            scope = backgroundScope
        )
        testScheduler.advanceUntilIdle()

        assertEquals(1, seenClaims.size)
        assertEquals("node-1", seenClaims.single())
        manager.terminateAll()
        testScheduler.advanceUntilIdle()
    }

    @Test
    fun `claimed WIP task is abandoned on forced shutdown`() = kotlinx.coroutines.runBlocking {
        val tmpDir = createTempDirectory("lm-wip-abandon-kill-").toFile()
        val substrateFactory = com.achulkov.evokammer.substrate.SubstrateDatabaseFactory(tmpDir)
        val db = substrateFactory.get("run-test", "group-0")
        db.wipTaskDao().insertAll(
            listOf(
                WipTaskEntity(
                    nodeId = "node-2",
                    runId = "run-test",
                    groupId = "group-0",
                    description = "finish the plan",
                    status = "unclaimed",
                    lastChangedGeneration = 0
                )
            )
        )

        val manager = LifecycleManager(
            runId = "run-test",
            groupId = "group-0",
            shuruVmManager = com.achulkov.evokammer.sandbox.shuru.ShuruVmManager(),
            substrateDatabaseFactory = substrateFactory,
            agentJarPath = "/fake/agent.jar",
            openRouterApiKey = "sk-test",
            groupProfile = profile,
            localAgentRunner = { _, onBudgetLedger ->
                val ledger = BudgetLedger("auto", 50.0, 2.5, budgetConfig)
                onBudgetLedger?.invoke(ledger)
                kotlinx.coroutines.delay(60_000)
            },
            gracePeriodSeconds = 1,
            budgetPollingIntervalMs = 100L
        )

        val agentScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        manager.spawnGeneration(
            allocation = singleResearcherAllocation(),
            stageDisclosure = "",
            modelAssignments = emptyMap(),
            modelPools = emptyMap(),
            rolePrompts = emptyMap(),
            scope = agentScope
        )

        val claimed = awaitRealTimeValue {
            db.wipTaskDao().getByNodeId("node-2", "run-test", "group-0")
                ?.takeIf { it.status == "claimed" }
        }
        assertEquals("claimed", claimed.status)

        manager.terminateAll()
        val stored = awaitRealTimeValue {
            db.wipTaskDao().getByNodeId("node-2", "run-test", "group-0")
                ?.takeIf { it.status == "unclaimed" && it.abandonCount == 1 }
        }
        assertEquals("unclaimed", stored.status)
        assertEquals(1, stored.abandonCount)
        agentScope.cancel()
    }
}
