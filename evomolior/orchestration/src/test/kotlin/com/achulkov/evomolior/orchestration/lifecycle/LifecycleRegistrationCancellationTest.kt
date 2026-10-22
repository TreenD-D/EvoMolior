@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.orchestration.lifecycle

import com.achulkov.evomolior.core.contract.RoleAllocation
import com.achulkov.evomolior.core.contract.RoleAllocationEntry
import com.achulkov.evomolior.core.model.GroupProfile
import com.achulkov.evomolior.core.model.GroupProfileType
import com.achulkov.evomolior.core.model.Role
import com.achulkov.evomolior.sandbox.shuru.ShuruVmManager
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.worker.WorkerExecutionRepository
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Exercises cancellation at the durable-registration boundary using real Room persistence. */
class LifecycleRegistrationCancellationTest {
    @Test
    fun `cancelled spawning settles registration and never starts the next allocation`() = runBlocking {
        assertRegistrationCancellation(cancelSpawning = true)
    }

    @Test
    fun `cancelled execution scope settles a job cancelled before cell publication`() = runBlocking {
        assertRegistrationCancellation(cancelSpawning = false)
    }

    private suspend fun assertRegistrationCancellation(cancelSpawning: Boolean) = coroutineScope {
        val factory = SubstrateDatabaseFactory(createTempDirectory("registration-cancellation-").toFile())
        val enteredRegistration = CompletableDeferred<String>()
        val releaseRegistration = CompletableDeferred<Unit>()
        val runnerCalls = AtomicInteger()
        val executionJob = SupervisorJob()
        val executionScope = CoroutineScope(executionJob + Dispatchers.Default)
        val manager = LifecycleManager(
            runId = "registration-cancellation",
            groupId = "group-0",
            shuruVmManager = ShuruVmManager(),
            substrateDatabaseFactory = factory,
            agentJarPath = "/fake/agent.jar",
            openRouterApiKey = "provider-free",
            groupProfile = GroupProfile.default(GroupProfileType.Library),
            localAgentRunner = { _, _ -> runnerCalls.incrementAndGet() },
            registrationCommitBarrier = { agentId ->
                enteredRegistration.complete(agentId)
                releaseRegistration.await()
            },
        )
        val spawning = launch(Dispatchers.Default) {
            manager.spawnGeneration(
                allocation = RoleAllocation(
                    runId = "registration-cancellation",
                    groupId = "group-0",
                    generation = 1,
                    entries = listOf(
                        RoleAllocationEntry(Role.Researcher, if (cancelSpawning) 2 else 1, 50.0),
                    ),
                    totalBudgetCeiling = 100.0,
                    rationale = "Cancellation must not advance an unfinished registration.",
                ),
                stageDisclosure = "",
                modelAssignments = emptyMap(),
                rolePrompts = emptyMap(),
                scope = executionScope,
            )
        }
        try {
            val agentId = withTimeout(10_000) { enteredRegistration.await() }
            assertEquals(0, manager.getLiveAgentCount(), "Unregistered cells must remain private.")
            if (cancelSpawning) spawning.cancel() else executionScope.cancel()
            val termination = async(start = CoroutineStart.UNDISPATCHED) { manager.terminateAll() }
            assertFalse(termination.isCompleted, "Shutdown must wait for registration ownership.")
            releaseRegistration.complete(Unit)
            withTimeout(10_000) {
                spawning.join()
                termination.await()
                assertTrue(manager.awaitQuiescence().isQuiescent)
            }
            val repository = WorkerExecutionRepository(factory.get("registration-cancellation", "group-0"))
            val registrations = repository.registrations("registration-cancellation", "group-0")
            val exits = repository.exits("registration-cancellation", "group-0")
            assertEquals(listOf(agentId), registrations.map { it.agentId.value })
            assertEquals(listOf(agentId), exits.map { it.agentId.value })
            assertEquals(0, runnerCalls.get())
            assertTrue(exits.single().wasKilled)
        } finally {
            withContext(NonCancellable) {
                releaseRegistration.complete(Unit)
                spawning.cancelAndJoin()
                executionJob.cancelAndJoin()
                manager.terminateAll()
                manager.awaitQuiescence(10_000)
            }
        }
    }
}
