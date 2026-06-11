package com.achulkov.evokammer.orchestration.lifecycle

import com.achulkov.evokammer.core.budget.BudgetLedger
import com.achulkov.evokammer.core.budget.BudgetConfig
import com.achulkov.evokammer.core.contract.AgentBootContext
import com.achulkov.evokammer.core.contract.BlockingAgentAbortException
import com.achulkov.evokammer.core.contract.RunInputArtifact
import com.achulkov.evokammer.core.contract.RunInputArtifactKind
import com.achulkov.evokammer.core.contract.RoleAllocation
import com.achulkov.evokammer.core.contract.RoleAllocationEntry
import com.achulkov.evokammer.core.model.GroupProfile
import com.achulkov.evokammer.core.model.GroupProfileType
import com.achulkov.evokammer.core.model.Role
import com.achulkov.evokammer.orchestration.nested.NestedGroupConfig
import com.achulkov.evokammer.orchestration.nested.NestedGroupResult
import com.achulkov.evokammer.sandbox.network.ContextUrlAllowlistExtractor
import com.achulkov.evokammer.sandbox.shuru.NetworkAllowlistConfig
import com.achulkov.evokammer.sandbox.shuru.ShuruVmManager
import com.achulkov.evokammer.substrate.SubstrateDatabaseFactory
import com.achulkov.evokammer.substrate.db.entity.EventTypes
import com.achulkov.evokammer.substrate.db.entity.KnowledgeBaseEntryEntity
import com.achulkov.evokammer.substrate.db.entity.KnowledgeGraphFactEntity
import com.achulkov.evokammer.substrate.db.entity.ToolRegistryEntryEntity
import com.achulkov.evokammer.substrate.db.entity.WipTaskEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration-style tests for [LifecycleManager] using the in-process agent runner.
 *
 * No Shuru CLI, Room DB, or LLM is required.
 * Tests verify the spawn/kill/budget-poll lifecycle using a fake runner.
 */
class LifecycleManagerTest {

    private data class ManagerHarness(
        val manager: LifecycleManager,
        val substrateFactory: SubstrateDatabaseFactory
    )

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
        nestedRunner: (suspend (NestedGroupConfig) -> NestedGroupResult)? = null,
        runner: suspend (AgentBootContext, ((BudgetLedger) -> Unit)?) -> Unit
    ): LifecycleManager = makeManagerHarness(
        modelSelectionRandom = modelSelectionRandom,
        nestedRunner = nestedRunner,
        runner = runner
    ).manager

    private fun makeManagerHarness(
        modelSelectionRandom: Random = Random(0),
        nestedRunner: (suspend (NestedGroupConfig) -> NestedGroupResult)? = null,
        runner: suspend (AgentBootContext, ((BudgetLedger) -> Unit)?) -> Unit
    ): ManagerHarness {
        val tmpDir = createTempDirectory("lm-test-").toFile()
        val substrateFactory = SubstrateDatabaseFactory(tmpDir)
        val shuruVmManager = ShuruVmManager()

        return ManagerHarness(
            manager = LifecycleManager(
                runId = "run-test",
                groupId = "group-0",
                shuruVmManager = shuruVmManager,
                substrateDatabaseFactory = substrateFactory,
                agentJarPath = "/fake/agent.jar",
                openRouterApiKey = "sk-test",
                groupProfile = profile,
                modelSelectionRandom = modelSelectionRandom,
                localAgentRunner = runner,
                nestedGroupRunBlock = nestedRunner,
                gracePeriodSeconds = 1,
                budgetPollingIntervalMs = 100L
            ),
            substrateFactory = substrateFactory
        )
    }

    private fun nestedConfig(
        nestedGroupId: String = "inner-1",
        outerGroupId: String = "group-0",
        outerRunId: String = "run-test"
    ): NestedGroupConfig = NestedGroupConfig(
        nestedGroupId = nestedGroupId,
        outerGroupId = outerGroupId,
        outerRunId = outerRunId,
        objective = "nested objective",
        budgetAllocation = 40.0,
        openRouterApiKey = "sk-test",
        agentJarPath = "/fake/agent.jar"
    )

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
    fun `in process agents receive host artifact path instead of vm mount path`() = runTest {
        val seenContexts = mutableListOf<AgentBootContext>()
        val manager = makeManager { bootContext, _ ->
            seenContexts += bootContext
        }
        val artifactsRoot = createTempDirectory("lm-artifacts-").toFile()

        manager.configureRunArtifacts(
            inputArtifacts = listOf(
                RunInputArtifact(
                    relativePath = "previous-stage/results-bundle/outputs/final-article.html",
                    sourceName = "final-article.html",
                    mimeType = "text/html",
                    sizeBytes = 128,
                    sha256 = "test-sha",
                    kind = RunInputArtifactKind.TEXT,
                    textPreview = "<!doctype html>"
                )
            ),
            artifactsMountPath = artifactsRoot.absolutePath
        )

        manager.spawnSpecialAgent(
            role = Role.Builder,
            generation = 1,
            budgetPerAgent = 12.5,
            stageDisclosure = "",
            modelAssignment = "test-model",
            rolePrompt = "You are a Final Deliverable Writer.",
            scope = backgroundScope
        )

        val seenContext = awaitRealTimeValue { seenContexts.firstOrNull() }
        manager.terminateAll()

        assertEquals(
            artifactsRoot.absolutePath,
            seenContext.artifactsMountPath,
            "In-process agents must receive the host artifact path so read_run_artifact can open files"
        )
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

    @Test
    fun `terminateAll cancels active nested group jobs`() = runTest {
        val nestedStarted = CompletableDeferred<Unit>()
        val nestedCancelled = CompletableDeferred<Unit>()
        val manager = makeManager(
            runner = { _, _ -> },
            nestedRunner = {
                nestedStarted.complete(Unit)
                try {
                    delay(60_000)
                    NestedGroupResult(
                        nestedGroupId = it.nestedGroupId,
                        outerGroupId = it.outerGroupId,
                        generationsCompleted = 0,
                        kbDocumentIds = emptyList(),
                        kgFactIds = emptyList(),
                        promotedToolIds = emptyList(),
                        budgetConsumed = 0.0,
                        completedNormally = false
                    )
                } catch (_: kotlinx.coroutines.CancellationException) {
                    nestedCancelled.complete(Unit)
                    throw kotlinx.coroutines.CancellationException()
                }
            }
        )

        val nestedJob = manager.spawnNestedGroup(
            config = NestedGroupConfig(
                nestedGroupId = "inner-1",
                outerGroupId = "group-0",
                outerRunId = "run-test",
                objective = "nested objective",
                budgetAllocation = 40.0,
                openRouterApiKey = "sk-test",
                agentJarPath = "/fake/agent.jar"
            ),
            scope = backgroundScope
        )

        nestedStarted.await()
        assertEquals(1, manager.getLiveNestedGroupCount())

        manager.terminateAll()
        nestedCancelled.await()
        awaitRealTimeValue<Int> { manager.getLiveNestedGroupCount().takeIf { it == 0 } }

        assertTrue(nestedJob.isCancelled, "Parent termination should cancel the nested group job")
        assertEquals(0, manager.getLiveNestedGroupCount())
    }

    @Test
    fun `spawnNestedGroup rejects duplicate nested group ids while active`() = runTest {
        val nestedStarted = CompletableDeferred<Unit>()
        val manager = makeManager(
            runner = { _, _ -> },
            nestedRunner = {
                nestedStarted.complete(Unit)
                delay(60_000)
                NestedGroupResult(
                    nestedGroupId = it.nestedGroupId,
                    outerGroupId = it.outerGroupId,
                    generationsCompleted = 0,
                    kbDocumentIds = emptyList(),
                    kgFactIds = emptyList(),
                    promotedToolIds = emptyList(),
                    budgetConsumed = 0.0,
                    completedNormally = true
                )
            }
        )

        manager.spawnNestedGroup(
            config = nestedConfig(),
            scope = backgroundScope
        )
        nestedStarted.await()

        val error = assertFailsWith<IllegalStateException> {
            manager.spawnNestedGroup(
                config = nestedConfig(),
                scope = backgroundScope
            )
        }

        assertTrue(error.message?.contains("already active") == true)
        manager.terminateAll()
        awaitRealTimeValue<Int> { manager.getLiveNestedGroupCount().takeIf { it == 0 } }
    }

    @Test
    fun `spawnNestedGroup validates outer identifiers`() = runTest {
        val manager = makeManager { _, _ -> }

        assertFailsWith<IllegalArgumentException> {
            manager.spawnNestedGroup(
                config = nestedConfig(outerGroupId = "group-1"),
                scope = backgroundScope
            )
        }
        assertFailsWith<IllegalArgumentException> {
            manager.spawnNestedGroup(
                config = nestedConfig(outerRunId = "run-other"),
                scope = backgroundScope
            )
        }
    }

    @Test
    fun `spawnNestedGroup rolls nested artifacts into the outer substrate and cleans up state`() = runTest {
        val tmpDir = createTempDirectory("lm-nested-rollup-test-").toFile()
        val substrateFactory = SubstrateDatabaseFactory(tmpDir)
        val manager = LifecycleManager(
            runId = "run-test",
            groupId = "group-0",
            shuruVmManager = ShuruVmManager(),
            substrateDatabaseFactory = substrateFactory,
            agentJarPath = "/fake/agent.jar",
            openRouterApiKey = "sk-test",
            groupProfile = profile,
            localAgentRunner = { _, _ -> },
            nestedGroupRunBlock = { config ->
                val nestedRunId = "${config.outerRunId}-nested-${config.nestedGroupId}"
                val nestedDb = substrateFactory.get(nestedRunId, config.nestedGroupId)

                nestedDb.knowledgeBaseDao().insert(
                    KnowledgeBaseEntryEntity(
                        id = "kb-1",
                        content = "Nested KB entry",
                        sourceAgentId = "nested-agent-kb",
                        generation = 1,
                        trustScore = 0.92,
                        citationCount = 3,
                        domain = "general",
                        status = "active",
                        groupId = config.nestedGroupId,
                        runId = nestedRunId,
                        createdAtMs = 1_000L
                    )
                )
                nestedDb.knowledgeGraphFactDao().insert(
                    KnowledgeGraphFactEntity(
                        id = "kg-1",
                        subject = "Nested Subject",
                        relation = "SUPPORTS",
                        objectNode = "Nested Object",
                        confidence = 0.86,
                        sourceAgentId = "nested-agent-kg",
                        generation = 1,
                        trustScore = 0.89,
                        provenanceJson = """["kb-1"]""",
                        status = "active",
                        speculative = false,
                        groupId = config.nestedGroupId,
                        runId = nestedRunId,
                        createdAtMs = 2_000L
                    )
                )
                nestedDb.toolRegistryDao().insert(
                    ToolRegistryEntryEntity(
                        id = "tool-1",
                        name = "nested_tool",
                        version = 1,
                        authorAgentId = "nested-agent-tool",
                        successRate = 1.0,
                        inputSchemaJson = "{}",
                        outputSchemaJson = "{}",
                        domain = "general",
                        status = "active",
                        code = "echo nested",
                        codeLanguage = "shell",
                        generation = 1,
                        groupId = config.nestedGroupId,
                        runId = nestedRunId,
                        createdAtMs = 3_000L
                    )
                )

                NestedGroupResult(
                    nestedGroupId = config.nestedGroupId,
                    outerGroupId = config.outerGroupId,
                    generationsCompleted = 2,
                    kbDocumentIds = listOf("kb-1"),
                    kgFactIds = listOf("kg-1"),
                    promotedToolIds = listOf("tool-1"),
                    budgetConsumed = 12.5,
                    completedNormally = true
                )
            },
            gracePeriodSeconds = 1,
            budgetPollingIntervalMs = 100L
        )
        val outerDb = substrateFactory.get("run-test", "group-0")
        outerDb.toolRegistryDao().insert(
            ToolRegistryEntryEntity(
                id = "outer-tool-1",
                name = "nested_tool",
                version = 1,
                authorAgentId = "outer-agent-tool",
                successRate = 0.8,
                inputSchemaJson = "{}",
                outputSchemaJson = "{}",
                domain = "general",
                status = "active",
                code = "echo outer",
                codeLanguage = "shell",
                generation = 0,
                groupId = "group-0",
                runId = "run-test",
                createdAtMs = 500L
            )
        )

        val completed = CompletableDeferred<NestedGroupResult>()
        val nestedJob = manager.spawnNestedGroup(
            config = nestedConfig(),
            scope = backgroundScope,
            onCompleted = { completed.complete(it) }
        )

        val result = completed.await()
        nestedJob.join()

        assertEquals(0, manager.getLiveNestedGroupCount())
        assertEquals(1, result.importedKbDocumentIds.size)
        assertEquals(1, result.importedKgFactIds.size)
        assertEquals(1, result.importedToolIds.size)

        val importedKb = outerDb.knowledgeBaseDao().getById(result.importedKbDocumentIds.single())
        assertNotNull(importedKb)
        assertEquals("run-test", importedKb.runId)
        assertEquals("group-0", importedKb.groupId)
        assertEquals("Nested KB entry", importedKb.content)
        assertNull(importedKb.previousVersionId)

        val importedKg = outerDb.knowledgeGraphFactDao().getById(result.importedKgFactIds.single())
        assertNotNull(importedKg)
        assertEquals("run-test", importedKg.runId)
        assertEquals("group-0", importedKg.groupId)
        assertTrue(importedKg.provenanceJson.contains("nested://run-test-nested-inner-1/inner-1/kg/kg-1"))

        val importedTool = outerDb.toolRegistryDao().getById(result.importedToolIds.single())
        assertNotNull(importedTool)
        assertEquals("run-test", importedTool.runId)
        assertEquals("group-0", importedTool.groupId)
        assertEquals(2, importedTool.version)
        assertEquals("outer-tool-1", importedTool.previousVersionId)

        val rollupEvent = outerDb.eventLogDao()
            .getLatestByType("run-test", "group-0", EventTypes.NESTED_GROUP_ROLLUP, 1)
            .single()
        assertTrue(rollupEvent.payloadJson.contains("\"nestedGroupId\":\"inner-1\""))
        assertTrue(rollupEvent.payloadJson.contains("\"kbImported\":1"))
    }
}
