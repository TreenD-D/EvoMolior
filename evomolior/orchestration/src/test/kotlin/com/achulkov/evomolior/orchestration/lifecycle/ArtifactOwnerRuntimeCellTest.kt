@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.orchestration.lifecycle

import com.achulkov.evomolior.core.contract.AgentLifecycleClassification
import com.achulkov.evomolior.core.contract.AgentLifecycleDuty
import com.achulkov.evomolior.core.contract.AgentRuntimePhase
import com.achulkov.evomolior.core.contract.AgentRuntimeStateEvent
import com.achulkov.evomolior.core.model.Role
import kotlinx.coroutines.Job
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class ArtifactOwnerRuntimeCellTest {
    @Test
    fun `Builder duty has no workspace evidence until explicit workspace start`() {
        val events = mutableListOf<AgentRuntimeStateEvent>()
        val cell = cell(
            role = Role.Builder,
            duty = AgentLifecycleDuty.ARTIFACT_OWNER,
            events = events
        )

        cell.confirmRegistration()
        cell.attachExecutionJob(Job())

        assertFalse(cell.snapshot().artifactWorkspaceStarted)
        assertTrue(events.none { it.state.artifactWorkspaceStarted })

        val state = cell.markArtifactWorkspaceStarted("workspace-1", "source-hash-1")

        assertEquals(AgentRuntimePhase.RUNNING, state.phase)
        assertTrue(state.artifactWorkspaceStarted)
        assertEquals("workspace-1", state.artifactWorkspaceId)
        assertEquals("source-hash-1", state.artifactWorkspaceSourceHash)
        assertEquals(
            "MARK_ARTIFACT_WORKSPACE_USED",
            events.single { it.state.artifactWorkspaceStarted }.intent.name
        )
    }

    @Test
    fun `non Builder duty cannot mark artifact workspace execution`() {
        val cell = cell(
            role = Role.Researcher,
            duty = AgentLifecycleDuty.NONE,
            events = mutableListOf()
        )

        cell.confirmRegistration()
        cell.attachExecutionJob(Job())

        assertFailsWith<IllegalStateException> {
            cell.markArtifactWorkspaceStarted("workspace-1", "source-hash-1")
        }
    }

    private fun cell(
        role: Role,
        duty: AgentLifecycleDuty,
        events: MutableList<AgentRuntimeStateEvent>
    ) = AgentRuntimeCell(
        runId = "run",
        groupId = "group",
        agentId = "agent-${role.name.lowercase()}",
        generation = 1,
        role = role,
        allocatedBudget = 10.0,
        wipTaskClaim = null,
        workerAuthority = null,
        classification = AgentLifecycleClassification.NON_DAEMON,
        spawnedAtMs = 0L,
        transitionSink = events::add,
        now = { Instant.parse("2026-08-02T12:00:00Z") },
        duty = duty
    )
}
