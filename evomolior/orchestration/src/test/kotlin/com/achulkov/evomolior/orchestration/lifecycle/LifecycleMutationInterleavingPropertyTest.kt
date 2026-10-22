@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.orchestration.lifecycle

import com.achulkov.evomolior.core.atlas.AtlasAgentId
import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.contract.AgentExecutionPhase
import com.achulkov.evomolior.core.contract.AgentExitRecord
import com.achulkov.evomolior.core.contract.AgentLifecycleClassification
import com.achulkov.evomolior.core.contract.AgentRuntimeStateEvent
import com.achulkov.evomolior.core.contract.CommitType
import com.achulkov.evomolior.core.contract.TtlCheckpointKind
import com.achulkov.evomolior.core.model.Role
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class LifecycleMutationInterleavingPropertyTest {
    @Test
    fun `two thousand deterministic mutation schedules preserve one exit identity`() {
        repeat(2_000) { seed ->
            val events = mutableListOf<AgentRuntimeStateEvent>()
            val cell = AgentRuntimeCell(
                runId = "run-$seed",
                groupId = "group",
                agentId = "agent",
                generation = 1,
                role = Role.Researcher,
                allocatedBudget = 100.0,
                wipTaskClaim = null,
                workerAuthority = null,
                classification = AgentLifecycleClassification.NON_DAEMON,
                spawnedAtMs = 0L,
                transitionSink = events::add,
                now = { Instant.parse("2026-07-24T12:00:00Z") }
            )
            cell.confirmRegistration()

            val exitA = exit(seed, "2026-07-24T12:00:01Z")
            val exitB = exit(seed, "2026-07-24T12:00:02Z")
            val operations = mutableListOf<() -> Unit>(
                { cell.observeBudget(90.0) },
                { cell.observeBudget(80.0) },
                { cell.recordTtlCheckpoint(TtlCheckpointKind.WORK_WARNING) },
                { cell.recordTtlCheckpoint(TtlCheckpointKind.COMMIT_SELECTION_REQUIRED) },
                { cell.requestTermination() },
                { cell.freezeExit(exitA) },
                { cell.freezeExit(exitB) },
                { cell.markExecutionSettled() }
            )
            operations.shuffle(Random(seed))
            operations.forEach { it() }

            val frozen = assertNotNull(cell.snapshot().exitRecord)
            cell.confirmExitPersisted()
            cell.markAuthorityCleanupComplete()
            cell.markExecutionSettled()
            cell.markCostSettled()
            assertTrue(cell.claimDeathCallbackEmission())
            assertTrue(!cell.claimDeathCallbackEmission())
            cell.confirmCleanupComplete()

            val finalState = cell.snapshot()
            assertEquals(frozen, finalState.exitRecord)
            assertTrue(finalState.executionSettled)
            assertTrue(finalState.authorityCleanupComplete)
            assertTrue(finalState.costSettled)
            assertTrue(finalState.deathCallbackEmitted)
            assertTrue(finalState.cleanupComplete)
            assertTrue(finalState.hasValidHash())
            assertEquals(events.size, events.map { it.revision }.distinct().size)
            assertEquals((1L..events.size.toLong()).toList(), events.map { it.revision })
            assertEquals(null, events.first().previousStateHash)
            events.zipWithNext().forEach { (previous, next) ->
                assertEquals(previous.state.stateHash, next.previousStateHash)
                assertTrue(next.state.phase >= previous.state.phase)
            }
            assertTrue(events.all(AgentRuntimeStateEvent::hasValidHash))
        }
    }

    private fun exit(seed: Int, exitedAt: String) = AgentExitRecord(
        exitId = "exit:run-$seed:group:agent",
        runId = "run-$seed",
        groupId = AtlasGroupId("group"),
        agentId = AtlasAgentId("agent"),
        nodeId = null,
        round = 1,
        finalPhase = AgentExecutionPhase.CONTRIBUTION_STAGED,
        commitId = "commit",
        commitType = CommitType.POSITIVE_RESULT,
        acceptedContribution = true,
        acceptedStructuredDeadEnd = false,
        acceptedNoveltyProposal = false,
        degradedExecution = false,
        wasKilled = false,
        exitedAt = Instant.parse(exitedAt)
    )
}
