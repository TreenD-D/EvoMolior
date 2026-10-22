package com.achulkov.evomolior.orchestration.acceptance

import com.achulkov.evomolior.core.contract.RoleAllocation
import com.achulkov.evomolior.core.contract.RoleAllocationEntry
import com.achulkov.evomolior.core.model.GroupProfile
import com.achulkov.evomolior.core.model.GroupProfileType
import com.achulkov.evomolior.core.model.Role
import com.achulkov.evomolior.orchestration.lifecycle.LifecycleManager
import com.achulkov.evomolior.sandbox.shuru.ShuruVmManager
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.db.entity.EventTypes
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase 10 chaos acceptance: killing ten percent of a live generation must not corrupt committed
 * substrate rows or prevent the remaining generation from reaching quiescence.
 */
class Phase10AgentChaosAcceptanceTest {
    @Test
    fun `targeted mid lifecycle kill preserves substrate consistency`() = runBlocking {
        val root = createTempDirectory("phase-10-agent-chaos-").toFile()
        val factory = SubstrateDatabaseFactory(root)
        val runId = "phase-10-chaos"
        val groupId = "group-0"
        val db = factory.get(runId, groupId)
        val allStarted = Channel<Unit>(capacity = 10)
        val releaseSurvivors = CompletableDeferred<Unit>()
        val manager = LifecycleManager(
            runId = runId,
            groupId = groupId,
            shuruVmManager = ShuruVmManager(),
            substrateDatabaseFactory = factory,
            agentJarPath = "/unused/agent.jar",
            openRouterApiKey = "sk-test",
            groupProfile = GroupProfile.default(GroupProfileType.Library),
            localAgentRunner = { context, _ ->
                db.stagingDao().insert(
                    StagingSubmissionEntity(
                        id = "chaos-${context.agentId}",
                        agentId = context.agentId,
                        role = context.role.name,
                        type = "DOC",
                        content = "Committed before chaos injection by ${context.agentId}.",
                        budgetSignature = "phase-10-chaos",
                        status = "pending",
                        generation = context.generation,
                        groupId = groupId,
                        runId = runId,
                        submittedAtMs = System.currentTimeMillis()
                    )
                )
                allStarted.send(Unit)
                releaseSurvivors.await()
            },
            gracePeriodSeconds = 1,
            budgetPollingIntervalMs = 100L
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val allExited = CompletableDeferred<Unit>()
        val exitCount = AtomicInteger()
        manager.onAgentDied = { _, _ ->
            if (exitCount.incrementAndGet() == 10) allExited.complete(Unit)
        }
        try {
            manager.spawnGeneration(
                allocation = RoleAllocation(
                    runId = runId,
                    groupId = groupId,
                    generation = 1,
                    entries = listOf(RoleAllocationEntry(Role.Researcher, count = 10, budgetPerAgent = 10.0)),
                    totalBudgetCeiling = 100.0,
                    rationale = "Phase 10 ten-percent chaos cohort."
                ),
                stageDisclosure = "",
                modelAssignments = emptyMap(),
                modelPools = emptyMap(),
                rolePrompts = emptyMap(),
                scope = scope
            )
            val killedAgentId = withTimeout(30_000) {
                repeat(10) { allStarted.receive() }
                val liveAgents = manager.agentStates.first { it.size == 10 }
                val killedId = liveAgents.keys.sorted().first()

                assertTrue(manager.terminateAgent(killedId, "phase_10_random_10_percent_kill"))
                assertFalse(manager.terminateAgent("missing-agent", "phase_10_missing_agent"))
                manager.agentStates.first { it.size == 9 }
                releaseSurvivors.complete(Unit)
                allExited.await()
                manager.agentStates.first { it.isEmpty() }
                killedId
            }

            val staged = db.stagingDao().getAll(runId, groupId)
            assertEquals(10, staged.size)
            assertEquals(10, staged.map { it.id }.toSet().size)
            assertTrue(staged.any { it.agentId == killedAgentId })
            assertEquals(
                10,
                db.eventLogDao().getLatestByType(runId, groupId, EventTypes.AGENT_SPAWN, 20).size
            )
            assertEquals(
                9,
                db.eventLogDao().getLatestByType(runId, groupId, EventTypes.AGENT_DEATH, 20).size
            )
            val killedEvents = db.eventLogDao()
                .getLatestByType(runId, groupId, EventTypes.AGENT_KILLED, 20)
            assertEquals(1, killedEvents.size)
            assertTrue(killedEvents.single().payloadJson.contains(killedAgentId))
        } finally {
            scope.cancel()
        }
    }
}
