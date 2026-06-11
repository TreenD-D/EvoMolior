package com.achulkov.evokammer.orchestration.nested

import com.achulkov.evokammer.core.budget.BudgetConfig
import com.achulkov.evokammer.core.budget.BudgetLedger
import com.achulkov.evokammer.core.model.GroupProfile
import com.achulkov.evokammer.core.model.GroupProfileType
import com.achulkov.evokammer.orchestration.lifecycle.LifecycleManager
import com.achulkov.evokammer.sandbox.shuru.ShuruVmManager
import com.achulkov.evokammer.substrate.SubstrateDatabaseFactory
import com.achulkov.evokammer.substrate.db.entity.KnowledgeBaseEntryEntity
import com.achulkov.evokammer.substrate.db.entity.KnowledgeGraphFactEntity
import com.achulkov.evokammer.substrate.db.entity.ToolRegistryEntryEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NestedGroupRunnerTest {

    @Test
    fun `run extracts KB KG and tool artifacts from the nested substrate`() = runTest {
        val dataRoot = createTempDirectory("nested-group-runner-test").toFile()
        val substrateFactory = SubstrateDatabaseFactory(dataRoot)
        val config = NestedGroupConfig(
            nestedGroupId = "inner-1",
            outerGroupId = "group-0",
            outerRunId = "run-test",
            objective = "inspect nested extraction",
            budgetAllocation = 20.0,
            maxGenerations = 0,
            profileType = GroupProfileType.Library,
            openRouterApiKey = "sk-test",
            agentJarPath = "/fake/agent.jar"
        )
        val nestedRunId = "${config.outerRunId}-nested-${config.nestedGroupId}"
        val nestedDb = substrateFactory.get(nestedRunId, config.nestedGroupId)

        nestedDb.knowledgeBaseDao().insert(
            KnowledgeBaseEntryEntity(
                id = "kb-1",
                content = "Nested KB entry",
                sourceAgentId = "agent-kb",
                generation = 1,
                trustScore = 0.9,
                citationCount = 2,
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
                confidence = 0.85,
                sourceAgentId = "agent-kg",
                generation = 1,
                trustScore = 0.88,
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
                authorAgentId = "agent-tool",
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

        val runner = NestedGroupRunner(
            config = config,
            shuruVmManager = ShuruVmManager(),
            substrateDatabaseFactory = substrateFactory,
            lifecycleManagerFactory = { runId, groupId, openRouterApiKey, agentJarPath ->
                LifecycleManager(
                    runId = runId,
                    groupId = groupId,
                    shuruVmManager = ShuruVmManager(),
                    substrateDatabaseFactory = substrateFactory,
                    agentJarPath = agentJarPath,
                    openRouterApiKey = openRouterApiKey,
                    groupProfile = GroupProfile.default(GroupProfileType.Library),
                    localAgentRunner = { _, _ -> }
                )
            }
        )

        val result = runner.run()

        assertEquals(listOf("kb-1"), result.kbDocumentIds)
        assertEquals(listOf("kg-1"), result.kgFactIds)
        assertEquals(listOf("tool-1"), result.promotedToolIds)
        assertTrue(result.summaryJson.contains("\"kbDocumentCount\":1"))
        assertTrue(result.summaryJson.contains("\"kgFactCount\":1"))
        assertTrue(result.summaryJson.contains("\"toolCount\":1"))
    }

    @Test
    fun `run waits for nested lifecycle quiescence before returning`() = runTest {
        val dataRoot = createTempDirectory("nested-group-runner-barrier-test").toFile()
        val substrateFactory = SubstrateDatabaseFactory(dataRoot)
        val profile = GroupProfile.default(GroupProfileType.Library)
        val nestedStarted = CompletableDeferred<Unit>()
        val nestedRelease = CompletableDeferred<Unit>()
        val nestedJobScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        lateinit var manager: LifecycleManager
        lateinit var childNestedConfig: NestedGroupConfig

        val config = NestedGroupConfig(
            nestedGroupId = "inner-2",
            outerGroupId = "group-0",
            outerRunId = "run-test",
            objective = "wait on nested lifecycle",
            budgetAllocation = 20.0,
            maxGenerations = 1,
            profileType = GroupProfileType.Library,
            openRouterApiKey = "sk-test",
            agentJarPath = "/fake/agent.jar"
        )

        val runner = NestedGroupRunner(
            config = config,
            shuruVmManager = ShuruVmManager(),
            substrateDatabaseFactory = substrateFactory,
            lifecycleManagerFactory = { runId, groupId, openRouterApiKey, agentJarPath ->
                childNestedConfig = NestedGroupConfig(
                    nestedGroupId = "inner-child",
                    outerGroupId = groupId,
                    outerRunId = runId,
                    objective = "child nested workload",
                    budgetAllocation = 10.0,
                    maxGenerations = 0,
                    profileType = GroupProfileType.Library,
                    openRouterApiKey = openRouterApiKey,
                    agentJarPath = agentJarPath
                )
                manager = LifecycleManager(
                    runId = runId,
                    groupId = groupId,
                    shuruVmManager = ShuruVmManager(),
                    substrateDatabaseFactory = substrateFactory,
                    agentJarPath = agentJarPath,
                    openRouterApiKey = openRouterApiKey,
                    groupProfile = profile,
                    localAgentRunner = { bootContext, onBudgetLedger ->
                        onBudgetLedger?.invoke(
                            BudgetLedger(
                                agentId = bootContext.agentId,
                                initialBudget = bootContext.allocatedBudget,
                                floor = bootContext.allocatedBudget * 0.05,
                                config = BudgetConfig(groupId = bootContext.groupId, profile = profile)
                            )
                        )
                        manager.spawnNestedGroup(
                            config = childNestedConfig,
                            scope = nestedJobScope
                        )
                        nestedStarted.await()
                    },
                    nestedGroupRunBlock = { nestedConfig ->
                        nestedStarted.complete(Unit)
                        nestedRelease.await()
                        NestedGroupResult(
                            nestedGroupId = nestedConfig.nestedGroupId,
                            outerGroupId = nestedConfig.outerGroupId,
                            generationsCompleted = 0,
                            kbDocumentIds = emptyList(),
                            kgFactIds = emptyList(),
                            promotedToolIds = emptyList(),
                            budgetConsumed = 0.0,
                            completedNormally = true
                        )
                    },
                    budgetPollingIntervalMs = 50L
                )
                manager
            }
        )

        val runDeferred = async(Dispatchers.Default) { runner.run() }

        nestedStarted.await()

        withContext(Dispatchers.Default) {
            withTimeout(5_000L) {
                while (manager.getLiveAgentCount() != 0 || manager.getLiveNestedGroupCount() == 0) {
                    delay(10L)
                }
            }
        }

        assertEquals(0, manager.getLiveAgentCount(), "Outer agents should have quiesced before release")
        assertTrue(
            manager.getLiveNestedGroupCount() > 0,
            "A nested child should still be active when the barrier is being exercised"
        )
        assertTrue(
            runDeferred.isActive,
            "NestedGroupRunner should still be waiting while a nested group is active even after all agents exit"
        )

        nestedRelease.complete(Unit)

        val result = runDeferred.await()
        nestedJobScope.cancel()

        assertEquals(1, result.generationsCompleted)
        assertTrue(result.completedNormally)
    }

    @Test
    fun `run fails fast when nested lifecycle never quiesces`() = runTest {
        val dataRoot = createTempDirectory("nested-group-runner-timeout-test").toFile()
        val substrateFactory = SubstrateDatabaseFactory(dataRoot)
        val profile = GroupProfile.default(GroupProfileType.Library)
        val nestedStarted = CompletableDeferred<Unit>()
        lateinit var manager: LifecycleManager
        lateinit var childNestedConfig: NestedGroupConfig

        val config = NestedGroupConfig(
            nestedGroupId = "inner-3",
            outerGroupId = "group-0",
            outerRunId = "run-test",
            objective = "timeout on stuck nested lifecycle",
            budgetAllocation = 20.0,
            maxGenerations = 1,
            profileType = GroupProfileType.Library,
            openRouterApiKey = "sk-test",
            agentJarPath = "/fake/agent.jar"
        )

        val runner = NestedGroupRunner(
            config = config,
            shuruVmManager = ShuruVmManager(),
            substrateDatabaseFactory = substrateFactory,
            lifecycleManagerFactory = { runId, groupId, openRouterApiKey, agentJarPath ->
                childNestedConfig = NestedGroupConfig(
                    nestedGroupId = "inner-child-timeout",
                    outerGroupId = groupId,
                    outerRunId = runId,
                    objective = "stuck child",
                    budgetAllocation = 10.0,
                    maxGenerations = 0,
                    profileType = GroupProfileType.Library,
                    openRouterApiKey = openRouterApiKey,
                    agentJarPath = agentJarPath
                )
                manager = LifecycleManager(
                    runId = runId,
                    groupId = groupId,
                    shuruVmManager = ShuruVmManager(),
                    substrateDatabaseFactory = substrateFactory,
                    agentJarPath = agentJarPath,
                    openRouterApiKey = openRouterApiKey,
                    groupProfile = profile,
                    localAgentRunner = { bootContext, onBudgetLedger ->
                        onBudgetLedger?.invoke(
                            BudgetLedger(
                                agentId = bootContext.agentId,
                                initialBudget = bootContext.allocatedBudget,
                                floor = bootContext.allocatedBudget * 0.05,
                                config = BudgetConfig(groupId = bootContext.groupId, profile = profile)
                            )
                        )
                        manager.spawnNestedGroup(
                            config = childNestedConfig,
                            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                        )
                        nestedStarted.await()
                    },
                    nestedGroupRunBlock = { nestedConfig ->
                        nestedStarted.complete(Unit)
                        delay(Long.MAX_VALUE)
                        NestedGroupResult(
                            nestedGroupId = nestedConfig.nestedGroupId,
                            outerGroupId = nestedConfig.outerGroupId,
                            generationsCompleted = 0,
                            kbDocumentIds = emptyList(),
                            kgFactIds = emptyList(),
                            promotedToolIds = emptyList(),
                            budgetConsumed = 0.0,
                            completedNormally = false
                        )
                    },
                    budgetPollingIntervalMs = 50L
                )
                manager
            },
            generationBarrierTimeoutMs = 300L
        )

        val result = runner.run()
        assertEquals(0, result.generationsCompleted)
        assertEquals(config.effectiveBudget, result.budgetConsumed, 0.001)
        assertTrue(
            !result.completedNormally,
            "Runner should surface a timeout failure instead of reporting a normal completion"
        )
    }
}
