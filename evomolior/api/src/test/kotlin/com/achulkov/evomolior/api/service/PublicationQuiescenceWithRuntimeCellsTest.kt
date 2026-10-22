package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.competition.coordinator.GroupCoordinator
import com.achulkov.evomolior.competition.grader.GraderGroupManager
import com.achulkov.evomolior.core.budget.BudgetConfig
import com.achulkov.evomolior.core.budget.BudgetLedger
import com.achulkov.evomolior.core.contract.RoleAllocation
import com.achulkov.evomolior.core.contract.RoleAllocationEntry
import com.achulkov.evomolior.core.model.GroupProfile
import com.achulkov.evomolior.core.model.GroupProfileType
import com.achulkov.evomolior.core.model.Role
import com.achulkov.evomolior.orchestration.lifecycle.LifecycleManager
import com.achulkov.evomolior.orchestration.metaplanner.MetaPlannerRunner
import com.achulkov.evomolior.sandbox.shuru.ShuruVmManager
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.publication.PublicationOutcomeRepository
import com.achulkov.evomolior.substrate.publication.PublicationStateRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Production publication-gate coverage backed by real cancellation-resistant runtime cells. */
class PublicationQuiescenceWithRuntimeCellsTest {
    @Test
    fun `accepted and recovery publication stay blocked until actual execution settles`() =
        runBlocking {
            val runId = "publication-runtime-cell-quiescence"
            val groupId = "group-0"
            val substrateFactory = SubstrateDatabaseFactory(
                createTempDirectory("publication-runtime-cell-quiescence-").toFile()
            )
            val profile = GroupProfile.default(GroupProfileType.Library)
            val budgetConfig = BudgetConfig(groupId = groupId, profile = profile)
            val runnerStarted = CompletableDeferred<Unit>()
            val releaseRunner = CompletableDeferred<Unit>()
            val manager = LifecycleManager(
                runId = runId,
                groupId = groupId,
                shuruVmManager = ShuruVmManager(),
                substrateDatabaseFactory = substrateFactory,
                agentJarPath = "/fake/agent.jar",
                openRouterApiKey = "sk-test",
                groupProfile = profile,
                localAgentRunner = { _, onBudgetLedger ->
                    onBudgetLedger?.invoke(
                        BudgetLedger("publication-agent", 50.0, 2.5, budgetConfig)
                    )
                    runnerStarted.complete(Unit)
                    withContext(NonCancellable) {
                        releaseRunner.await()
                    }
                },
                gracePeriodSeconds = 1,
                budgetPollingIntervalMs = 100L,
            )
            val service = EvoMoliorRunService(
                lifecycleManagerProvider = { _, _, _, _, _ -> error("unused") },
                groupCoordinatorProvider = { groupIds, dimensions ->
                    GroupCoordinator(groupIds, dimensions)
                },
                graderGroupManagerProvider = { dimensions ->
                    GraderGroupManager(dimensions)
                },
                substrateFactory = substrateFactory,
                metaPlannerRunner = MetaPlannerRunner(),
            )
            val liveManagersField = service::class.java
                .getDeclaredField("liveLifecycleManagers")
                .apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val liveManagers =
                liveManagersField.get(service) as MutableMap<String, LifecycleManager>
            liveManagers[groupId] = manager
            val agentScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

            try {
                manager.spawnGeneration(
                    allocation = RoleAllocation(
                        runId = runId,
                        groupId = groupId,
                        generation = 1,
                        entries = listOf(
                            RoleAllocationEntry(Role.Researcher, 1, 50.0)
                        ),
                        totalBudgetCeiling = 50.0,
                        rationale = "publication quiescence test",
                    ),
                    stageDisclosure = "",
                    modelAssignments = emptyMap(),
                    modelPools = emptyMap(),
                    rolePrompts = emptyMap(),
                    scope = agentScope,
                )
                withTimeout(2_000L) { runnerStarted.await() }
                withTimeout(2_000L) { manager.terminateAll() }

                assertFalse(manager.publicationAccounting().isQuiescent)
                listOf("Accepted publication", "Recovery publication").forEach { outcome ->
                    val failure = assertFailsWith<IllegalArgumentException> {
                        service.requirePublicationQuiescence(listOf(groupId), outcome, timeoutMillis = 100)
                    }
                    assertContains(failure.message.orEmpty(), outcome)
                    assertContains(failure.message.orEmpty(), "non-quiescent runtime-cell")
                }
                val atlasDatabase = substrateFactory.getAtlas(runId)
                assertTrue(PublicationStateRepository(atlasDatabase).history(runId).isEmpty())
                val outcomeRepository = PublicationOutcomeRepository(atlasDatabase)
                assertTrue(outcomeRepository.acceptedHistory(runId).isEmpty())
                assertTrue(outcomeRepository.recoveryHistory(runId).isEmpty())

                releaseRunner.complete(Unit)
                withTimeout(2_000L) {
                    while (!manager.publicationAccounting().isQuiescent) {
                        delay(10L)
                    }
                }

                assertTrue(
                    service.requirePublicationQuiescence(
                        listOf(groupId),
                        "Accepted publication",
                    ).getValue(groupId).isQuiescent
                )
                assertTrue(
                    service.requirePublicationQuiescence(
                        listOf(groupId),
                        "Recovery publication",
                    ).getValue(groupId).isQuiescent
                )
            } finally {
                releaseRunner.complete(Unit)
                manager.terminateAll()
                agentScope.cancel()
            }
        }
}
