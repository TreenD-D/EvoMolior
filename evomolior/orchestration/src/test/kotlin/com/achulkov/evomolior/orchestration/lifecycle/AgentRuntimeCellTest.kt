@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.orchestration.lifecycle

import com.achulkov.evomolior.core.atlas.AtlasAgentId
import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.contract.AgentExecutionPhase
import com.achulkov.evomolior.core.contract.AgentExitRecord
import com.achulkov.evomolior.core.contract.AgentLifecycleClassification
import com.achulkov.evomolior.core.contract.AgentRuntimePhase
import com.achulkov.evomolior.core.contract.CommitType
import com.achulkov.evomolior.core.contract.TtlCheckpointKind
import com.achulkov.evomolior.core.model.Role
import java.util.Collections
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class AgentRuntimeCellTest {
    @Test
    fun `concurrent intents preserve every monotonic field and one frozen exit`() {
        val events = Collections.synchronizedList(
            mutableListOf<com.achulkov.evomolior.core.contract.AgentRuntimeStateEvent>()
        )
        val cell = AgentRuntimeCell(
            runId = "run",
            groupId = "group",
            agentId = "agent",
            generation = 2,
            role = Role.Researcher,
            allocatedBudget = 100.0,
            wipTaskClaim = null,
            workerAuthority = null,
            classification = AgentLifecycleClassification.NON_DAEMON,
            spawnedAtMs = 0,
            transitionSink = events::add,
            now = { Instant.parse("2026-07-24T12:00:00Z") }
        )
        cell.confirmRegistration()
        val executor = Executors.newFixedThreadPool(4)
        try {
            val firstExit = exit(wasKilled = false, exitedAt = "2026-07-24T12:00:01Z")
            val secondExit = exit(wasKilled = false, exitedAt = "2026-07-24T12:00:02Z")
            listOf(
                { cell.observeBudget(80.0) },
                { cell.recordTtlCheckpoint(TtlCheckpointKind.WORK_WARNING) },
                { cell.requestTermination() },
                { cell.freezeExit(firstExit) },
                { cell.freezeExit(secondExit) },
                { cell.markExecutionSettled() }
            ).map { task ->
                executor.submit(Runnable { task() })
            }.forEach { it.get() }

            val state = cell.snapshot()
            assertEquals(80.0, state.budgetRemaining)
            assertTrue(TtlCheckpointKind.WORK_WARNING in state.ttlCheckpoints)
            assertTrue(state.killRequested)
            assertTrue(state.executionSettled)
            assertTrue(state.phase >= AgentRuntimePhase.EXECUTION_SETTLED)
            assertNotNull(state.exitRecord)
            assertTrue(state.exitRecord!!.wasKilled)
            assertTrue(state.hasValidHash())
            assertEquals(events.size, events.map { it.revision }.distinct().size)
            assertTrue(events.all { it.hasValidHash() })
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `cleanup complete is terminal for late budget and checkpoint telemetry`() {
        val events = mutableListOf<com.achulkov.evomolior.core.contract.AgentRuntimeStateEvent>()
        val cell = AgentRuntimeCell(
            runId = "run",
            groupId = "group",
            agentId = "agent",
            generation = 2,
            role = Role.Researcher,
            allocatedBudget = 100.0,
            wipTaskClaim = null,
            workerAuthority = null,
            classification = AgentLifecycleClassification.NON_DAEMON,
            spawnedAtMs = 0,
            transitionSink = events::add,
            now = { Instant.parse("2026-07-24T12:00:00Z") }
        )
        cell.confirmRegistration()
        cell.freezeExit(exit(wasKilled = false, exitedAt = "2026-07-24T12:00:01Z"))
        cell.confirmExitPersisted()
        cell.markAuthorityCleanupComplete()
        cell.markExecutionSettled()
        cell.markCostSettled()
        assertTrue(cell.claimDeathCallbackEmission())
        cell.confirmCleanupComplete()
        val terminalState = cell.snapshot()
        val terminalEventCount = events.size

        assertTrue(!cell.observeBudget(1.0))
        assertTrue(!cell.recordTtlCheckpoint(TtlCheckpointKind.TERMINATED))
        assertEquals(terminalState, cell.snapshot())
        assertEquals(terminalEventCount, events.size)
    }

    private fun exit(wasKilled: Boolean, exitedAt: String) = AgentExitRecord(
        exitId = "exit:run:group:agent",
        runId = "run",
        groupId = AtlasGroupId("group"),
        agentId = AtlasAgentId("agent"),
        nodeId = null,
        round = 2,
        finalPhase = AgentExecutionPhase.CONTRIBUTION_STAGED,
        commitId = "commit",
        commitType = CommitType.POSITIVE_RESULT,
        acceptedContribution = true,
        acceptedStructuredDeadEnd = false,
        acceptedNoveltyProposal = false,
        degradedExecution = false,
        wasKilled = wasKilled,
        exitedAt = Instant.parse(exitedAt)
    )
}
