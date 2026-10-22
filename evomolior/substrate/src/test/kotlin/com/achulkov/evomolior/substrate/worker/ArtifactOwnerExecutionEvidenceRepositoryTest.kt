@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.substrate.worker

import com.achulkov.evomolior.core.atlas.AtlasAgentId
import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.contract.AgentLifecycleClassification
import com.achulkov.evomolior.core.contract.AgentLifecycleDuty
import com.achulkov.evomolior.core.contract.AgentLifecycleRegistration
import com.achulkov.evomolior.core.contract.AgentRuntimePhase
import com.achulkov.evomolior.core.contract.AgentRuntimeState
import com.achulkov.evomolior.core.contract.AgentRuntimeStateEvent
import com.achulkov.evomolior.core.contract.AgentRuntimeTransitionIntent
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class ArtifactOwnerExecutionEvidenceRepositoryTest {
    @Test
    fun `Builder registration is durable but only explicit workspace use creates lineage evidence`() =
        runTest {
            val runId = "artifact-owner-evidence-run"
            val groupId = AtlasGroupId("group")
            val agentId = AtlasAgentId("builder")
            val database = SubstrateDatabaseFactory(
                createTempDirectory("artifact-owner-evidence-").toFile()
            ).get(runId, groupId.value)
            try {
                val repository = WorkerExecutionRepository(database)
                val registration = AgentLifecycleRegistration(
                    registrationId = "registration-builder",
                    runId = runId,
                    groupId = groupId,
                    agentId = agentId,
                    role = "Builder",
                    generation = 1,
                    classification = AgentLifecycleClassification.NON_DAEMON,
                    registeredAt = Instant.parse("2026-08-02T12:00:00Z"),
                    duty = AgentLifecycleDuty.ARTIFACT_OWNER,
                    workspaceId = "workspace-1",
                    workspaceSourceHash = "source-hash-1"
                ).withComputedHash()

                repository.registerAgent(registration)
                assertEquals(
                    listOf(registration),
                    repository.artifactOwnerRegistrations(runId, groupId.value)
                )
                assertTrue(
                    repository.artifactOwnerExecutionEvidence(
                        runId,
                        groupId.value,
                        agentId.value,
                        1
                    ).isEmpty()
                )

                val registered = event(
                    eventId = "runtime-1",
                    revision = 1,
                    intent = AgentRuntimeTransitionIntent.REGISTER_AGENT,
                    state = state(
                        revision = 1,
                        phase = AgentRuntimePhase.REGISTERED,
                        registrationRecorded = true
                    ),
                    previousStateHash = null
                )
                val running = event(
                    eventId = "runtime-2",
                    revision = 2,
                    intent = AgentRuntimeTransitionIntent.START_EXECUTION,
                    state = state(
                        revision = 2,
                        phase = AgentRuntimePhase.RUNNING,
                        registrationRecorded = true
                    ),
                    previousStateHash = registered.state.stateHash
                )
                val used = event(
                    eventId = "runtime-3",
                    revision = 3,
                    intent = AgentRuntimeTransitionIntent.MARK_ARTIFACT_WORKSPACE_USED,
                    state = state(
                        revision = 3,
                        phase = AgentRuntimePhase.RUNNING,
                        registrationRecorded = true,
                        artifactWorkspaceStarted = true,
                        artifactWorkspaceId = "workspace-1",
                        artifactWorkspaceSourceHash = "source-hash-1"
                    ),
                    previousStateHash = running.state.stateHash
                )
                val settled = event(
                    eventId = "runtime-4",
                    revision = 4,
                    intent = AgentRuntimeTransitionIntent.MARK_EXECUTION_SETTLED,
                    state = state(
                        revision = 4,
                        phase = AgentRuntimePhase.EXECUTION_SETTLED,
                        registrationRecorded = true,
                        artifactWorkspaceStarted = true,
                        artifactWorkspaceId = "workspace-1",
                        artifactWorkspaceSourceHash = "source-hash-1"
                    ),
                    previousStateHash = used.state.stateHash
                )

                repository.appendRuntimeStateEvent(registered)
                repository.appendRuntimeStateEvent(running)
                repository.appendRuntimeStateEvent(used)
                repository.appendRuntimeStateEvent(settled)

                val evidence = repository.artifactOwnerExecutionEvidence(
                    runId,
                    groupId.value,
                    agentId.value,
                    1
                )
                assertEquals(listOf(used), evidence)
                assertEquals("workspace-1", evidence.single().state.artifactWorkspaceId)
                assertEquals(
                    "source-hash-1",
                    evidence.single().state.artifactWorkspaceSourceHash
                )
            } finally {
                database.close()
            }
        }

    private fun state(
        revision: Long,
        phase: AgentRuntimePhase,
        registrationRecorded: Boolean,
        artifactWorkspaceStarted: Boolean = false,
        artifactWorkspaceId: String? = null,
        artifactWorkspaceSourceHash: String? = null
    ) = AgentRuntimeState(
        runId = "artifact-owner-evidence-run",
        groupId = AtlasGroupId("group"),
        agentId = AtlasAgentId("builder"),
        generation = 1,
        phase = phase,
        revision = revision,
        budgetRemaining = 10.0,
        registrationRecorded = registrationRecorded,
        artifactWorkspaceStarted = artifactWorkspaceStarted,
        artifactWorkspaceId = artifactWorkspaceId,
        artifactWorkspaceSourceHash = artifactWorkspaceSourceHash
    ).withComputedHash()

    private fun event(
        eventId: String,
        revision: Long,
        intent: AgentRuntimeTransitionIntent,
        state: AgentRuntimeState,
        previousStateHash: String?
    ) = AgentRuntimeStateEvent(
        eventId = eventId,
        runId = "artifact-owner-evidence-run",
        groupId = AtlasGroupId("group"),
        agentId = AtlasAgentId("builder"),
        generation = 1,
        revision = revision,
        intent = intent,
        previousStateHash = previousStateHash,
        state = state,
        occurredAt = Instant.parse("2026-08-02T12:00:0${revision}Z")
    ).withComputedHash()
}
