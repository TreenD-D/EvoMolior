package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.competition.coordinator.GroupCoordinator
import com.achulkov.evomolior.competition.grader.GraderGroupManager
import com.achulkov.evomolior.api.events.EvoMoliorEvent
import com.achulkov.evomolior.core.model.Role
import com.achulkov.evomolior.orchestration.lifecycle.LifecycleManager
import com.achulkov.evomolior.orchestration.metaplanner.MetaPlannerRunner
import com.achulkov.evomolior.sandbox.shuru.ShuruVmManager
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RunConcurrentWorkCleanupTest {
    @Test
    fun `run finalizer drains concurrent criteria finalization before forgetting ownership`() = runBlocking {
        val root = createTempDirectory("run-criteria-drain-").toFile()
        val factory = SubstrateDatabaseFactory(root)
        val service = EvoMoliorRunService(
            lifecycleManagerProvider = { _, _, _, _, _ -> error("Not used") },
            groupCoordinatorProvider = { groups, dimensions -> GroupCoordinator(groups, dimensions) },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = factory,
            metaPlannerRunner = MetaPlannerRunner(),
        )
        val finalized = CompletableDeferred<Unit>()
        val criteria = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    delay(50)
                    finalized.complete(Unit)
                }
            }
        }
        try {
            withTimeout(2_000) {
                service.finishRunLoop("fixture-run", null, null, criteria)
            }
            assertTrue(criteria.isCompleted)
            assertTrue(finalized.isCompleted)
        } finally {
            criteria.cancel()
            root.deleteRecursively()
        }
    }

    @Test
    fun `criteria and first worker timeouts do not skip other cancellation or publish success`() = runBlocking {
        val root = createTempDirectory("run-multiple-drain-failures-").toFile()
        val factory = SubstrateDatabaseFactory(root)
        val runId = "drain-failure-run"
        val service = EvoMoliorRunService(
            lifecycleManagerProvider = { _, _, _, _, _ -> error("Not used") },
            groupCoordinatorProvider = { groups, dimensions -> GroupCoordinator(groups, dimensions) },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = factory,
            metaPlannerRunner = MetaPlannerRunner(),
        )
        val workScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val releaseCriteria = CompletableDeferred<Unit>()
        val releaseFirstWorker = CompletableDeferred<Unit>()
        val criteriaCancelled = CompletableDeferred<Unit>()
        val workerCancelled = List(2) { CompletableDeferred<Unit>() }
        val workerStarted = List(2) { CompletableDeferred<Unit>() }
        val events = mutableListOf<EvoMoliorEvent>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            service.events.collect(events::add)
        }
        val managers = (0..1).map { index ->
            LifecycleManager(
                runId = runId,
                groupId = "group-$index",
                shuruVmManager = ShuruVmManager(),
                substrateDatabaseFactory = factory,
                agentJarPath = "/unused/agent.jar",
                openRouterApiKey = "provider-free",
                localAgentRunner = { _, _ ->
                    workerStarted[index].complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        workerCancelled[index].complete(Unit)
                        if (index == 0) {
                            withContext(NonCancellable) { releaseFirstWorker.await() }
                        }
                    }
                },
            )
        }
        val criteria = workScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                criteriaCancelled.complete(Unit)
                withContext(NonCancellable) { releaseCriteria.await() }
            }
        }
        val liveField = service::class.java.getDeclaredField("liveLifecycleManagers")
            .apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val liveManagers = liveField.get(service) as MutableMap<String, LifecycleManager>
        service::class.java.getDeclaredField("activeRunId")
            .apply { isAccessible = true }.set(service, runId)

        try {
            managers.forEachIndexed { index, manager ->
                liveManagers["group-$index"] = manager
                manager.spawnSpecialAgent(
                    role = Role.Researcher,
                    generation = 0,
                    budgetPerAgent = 10.0,
                    stageDisclosure = "",
                    rolePrompt = "Provider-free cancellation fixture",
                    scope = workScope,
                )
            }
            withTimeout(5_000) { workerStarted.forEach { it.await() } }
            val failure = withTimeout(5_000) {
                assertFailsWith<IllegalStateException> {
                    service.finishRunLoop(
                        runId,
                        cancellationFailure = null,
                        terminalEvent = EvoMoliorEvent.RunCompleted(runId, "group-1"),
                        pendingCriteriaJob = criteria,
                        drainTimeoutMillis = 1_000,
                    )
                }
            }
            assertTrue(criteriaCancelled.isCompleted)
            assertTrue(workerCancelled.all { it.isCompleted })
            assertTrue(failure.message.orEmpty().contains("ownership is retained"))
            // Coroutine stack recovery may wrap the aggregate in a copy whose cause owns
            // the original suppressed failures.
            val reportedFailures = generateSequence<Throwable>(failure) { it.cause }
                .flatMap { it.suppressed.asSequence() }
                .toList()
            assertTrue(
                reportedFailures.any { it.message.orEmpty().contains("Criteria drain") },
                failure.stackTraceToString(),
            )
            assertTrue(
                reportedFailures.any { it.message.orEmpty().contains("group-0 drain") },
                failure.stackTraceToString(),
            )
            assertFalse(managers[0].publicationAccounting().isQuiescent)
            assertTrue(managers[1].publicationAccounting().isQuiescent)
            assertEquals(runId, service.getState().activeRunId)
            assertEquals(2, liveManagers.size)
            assertFalse(events.any { it is EvoMoliorEvent.RunCompleted })

            // Retained ownership is retryable once the real jobs acknowledge cancellation.
            releaseCriteria.complete(Unit)
            releaseFirstWorker.complete(Unit)
            withTimeout(5_000) { service.abortRun(runId, "retry settled cleanup") }
            assertEquals(null, service.getState().activeRunId)
            assertTrue(liveManagers.isEmpty())
            assertTrue(criteria.isCompleted)
            assertFalse(events.any { it is EvoMoliorEvent.RunCompleted })
        } finally {
            releaseCriteria.complete(Unit)
            releaseFirstWorker.complete(Unit)
            criteria.cancelAndJoin()
            managers.forEach {
                it.terminateAll()
                it.awaitQuiescence(5_000)
            }
            workScope.cancel()
            collector.cancelAndJoin()
            (0..1).forEach { factory.close(runId, "group-$it") }
            root.deleteRecursively()
        }
    }
}
