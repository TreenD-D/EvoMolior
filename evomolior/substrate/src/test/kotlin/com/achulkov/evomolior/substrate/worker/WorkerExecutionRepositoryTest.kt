@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.substrate.worker

import com.achulkov.evomolior.core.atlas.AtlasAgentId
import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.ArtifactType
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.atlas.ProofMethod
import com.achulkov.evomolior.core.contract.AgentExecutionPhase
import com.achulkov.evomolior.core.contract.AgentExitRecord
import com.achulkov.evomolior.core.contract.AgentLifecycleClassification
import com.achulkov.evomolior.core.contract.AgentLifecycleRegistration
import com.achulkov.evomolior.core.contract.AgentRuntimePhase
import com.achulkov.evomolior.core.contract.AgentRuntimeState
import com.achulkov.evomolior.core.contract.AgentRuntimeStateEvent
import com.achulkov.evomolior.core.contract.AgentRuntimeTransitionIntent
import com.achulkov.evomolior.core.contract.CommitType
import com.achulkov.evomolior.core.contract.ContributionCommit
import com.achulkov.evomolior.core.contract.DegradedExecutionRecord
import com.achulkov.evomolior.core.contract.NodeBid
import com.achulkov.evomolior.core.contract.NodePortfolio
import com.achulkov.evomolior.core.contract.PersistExitResult
import com.achulkov.evomolior.core.contract.TtlCheckpointEvent
import com.achulkov.evomolior.core.contract.TtlCheckpointKind
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

class WorkerExecutionRepositoryTest {
    private val runId = "run-worker-execution"
    private val groupId = AtlasGroupId("group-worker-execution")
    private val agentId = AtlasAgentId("agent-worker-execution")
    private val nodeId = NodeId("node-worker-execution")

    @Test
    fun `node portfolios append exactly remain immutable and query in deterministic round order`() = runTest {
        val database = database()
        try {
            val repository = WorkerExecutionRepository(database)
            val later = portfolio("portfolio-b", round = 2)
            val earlier = portfolio("portfolio-a", round = 1)

            assertEquals(later, repository.appendNodePortfolio(later))
            assertEquals(earlier, repository.appendNodePortfolio(earlier))
            assertEquals(earlier, repository.appendNodePortfolio(earlier))
            assertEquals(
                listOf(earlier, later),
                repository.nodePortfolios(runId, groupId.value)
            )
            assertFailsWith<IllegalArgumentException> {
                repository.appendNodePortfolio(
                    earlier.copy(diversityScore = 0.9, portfolioHash = "").withComputedHash()
                )
            }
            assertFailsWith<IllegalArgumentException> {
                repository.appendNodePortfolio(later.copy(portfolioHash = ""))
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun `agent lifecycle registrations are immutable and queryable for publication`() = runTest {
        val database = database()
        try {
            val repository = WorkerExecutionRepository(database)
            val registration = AgentLifecycleRegistration(
                registrationId = "registration-1",
                runId = runId,
                groupId = groupId,
                agentId = agentId,
                role = "Researcher",
                generation = 1,
                classification = AgentLifecycleClassification.NON_DAEMON,
                registeredAt = Instant.parse("2026-07-11T09:59:00Z")
            ).withComputedHash()

            assertEquals(registration, repository.registerAgent(registration))
            assertEquals(registration, repository.registerAgent(registration))
            assertEquals(listOf(registration), repository.registrations(runId, groupId.value))
            assertFailsWith<IllegalArgumentException> {
                repository.registerAgent(
                    registration.copy(role = "Builder", registrationHash = "").withComputedHash()
                )
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun `contributions validate semantic hashes and deduplicate immutable commits`() = runTest {
        val database = database()
        try {
            val repository = WorkerExecutionRepository(database)
            val first = positiveCommit()
            val duplicateIdentity = first.copy(
                commitId = "commit-duplicate",
                committedAt = Instant.parse("2026-07-11T10:05:00Z")
            )

            assertEquals(first, repository.commit(first))
            assertEquals(first, repository.commit(duplicateIdentity))
            assertEquals(
                listOf(first),
                repository.contributionsForAgent(runId, groupId.value, agentId.value)
            )
            assertFailsWith<IllegalArgumentException> {
                repository.commit(first.copy(commitId = "bad-hash", dedupeHash = "incorrect"))
            }

            val noContribution = noContributionCommit()
            assertEquals(noContribution, repository.commit(noContribution))
            assertFailsWith<IllegalArgumentException> {
                repository.commit(
                    noContribution.copy(
                        commitId = "invalid-no-contribution",
                        artifactIds = listOf("artifact-not-allowed")
                    ).withDedupeHash()
                )
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun `TTL checkpoints are idempotent ordered and constrained to their typed boundary`() = runTest {
        val database = database()
        try {
            val repository = WorkerExecutionRepository(database)
            val warning = checkpoint(
                "checkpoint-warning",
                TtlCheckpointKind.WORK_WARNING,
                0.65,
                AgentExecutionPhase.WORKING
            )
            val commitRequired = checkpoint(
                "checkpoint-commit",
                TtlCheckpointKind.COMMIT_SELECTION_REQUIRED,
                0.80,
                AgentExecutionPhase.COMMIT_REQUIRED
            )

            assertTrue(repository.recordCheckpoint(commitRequired))
            assertTrue(repository.recordCheckpoint(warning))
            assertFalse(repository.recordCheckpoint(warning.copy(eventId = "duplicate-warning")))
            assertEquals(
                listOf(TtlCheckpointKind.WORK_WARNING, TtlCheckpointKind.COMMIT_SELECTION_REQUIRED),
                repository.checkpointsForAgent(runId, groupId.value, agentId.value).map { it.checkpoint }
            )
            assertFailsWith<IllegalArgumentException> {
                repository.recordCheckpoint(warning.copy(eventId = "wrong-boundary", elapsedFraction = 0.90))
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun `degraded execution never changes useful exit accounting and every exit is counted`() = runTest {
        val database = database()
        try {
            val repository = WorkerExecutionRepository(database)
            repository.recordDegradedExecution(
                DegradedExecutionRecord(
                    recordId = "degraded-1",
                    runId = runId,
                    groupId = groupId,
                    agentId = agentId,
                    nodeId = nodeId,
                    round = 1,
                    reasonCode = "NO_ACTION_FALLBACK",
                    detail = "Synthetic fallback was persisted separately.",
                    artifactIds = listOf("fallback-artifact"),
                    occurredAt = Instant.parse("2026-07-11T10:00:00Z")
                )
            )
            repository.recordExit(
                exit(
                    exitId = "exit-useful",
                    agentId = agentId,
                    acceptedContribution = true,
                    degraded = true
                )
            )
            repository.recordExit(
                exit(
                    exitId = "exit-empty",
                    agentId = AtlasAgentId("agent-empty"),
                    acceptedContribution = false,
                    degraded = true
                )
            )
            repository.recordExit(
                exit(
                    exitId = "exit-other-group",
                    agentId = AtlasAgentId("agent-other"),
                    acceptedContribution = true,
                    degraded = false,
                    group = AtlasGroupId("group-other")
                )
            )

            val groupSummary = repository.usefulExitSummary(runId, groupId.value)
            assertEquals(1, groupSummary.usefulExitCount)
            assertEquals(2, groupSummary.ordinaryExitCount)
            assertEquals(0.5, groupSummary.usefulExitRate)

            val runSummary = repository.usefulExitSummary(runId)
            assertEquals(2, runSummary.usefulExitCount)
            assertEquals(3, runSummary.ordinaryExitCount)
            assertEquals(1, database.workerExecutionDao().degradedExecutionsForAgent(
                runId,
                groupId.value,
                agentId.value
            ).size)
        } finally {
            database.close()
        }
    }

    @Test
    fun `exit persistence returns inserted idempotent and conflict results`() = runTest {
        val database = database()
        try {
            val repository = WorkerExecutionRepository(database)
            val persisted = exit(
                exitId = "exit-idempotent",
                agentId = agentId,
                acceptedContribution = false,
                degraded = false
            )

            val expectedHash = AtlasHashing.hashAgentExitRecord(persisted)
            assertEquals(
                PersistExitResult.Inserted(expectedHash),
                repository.recordExit(persisted)
            )
            assertEquals(
                PersistExitResult.AlreadyPersisted(expectedHash),
                repository.recordExit(persisted)
            )
            val conflict = assertIs<PersistExitResult.Conflict>(
                repository.recordExit(persisted.copy(wasKilled = true))
            )
            assertEquals(
                AtlasHashing.hashAgentExitRecord(persisted.copy(wasKilled = true)),
                conflict.exitRecordHash
            )
            assertEquals(expectedHash, conflict.persistedExitRecordHash)
            assertEquals(listOf(persisted), repository.exits(runId, groupId.value))
        } finally {
            database.close()
        }
    }

    @Test
    fun `runtime state events append idempotently and query in immutable revision order`() = runTest {
        val database = database()
        try {
            val repository = WorkerExecutionRepository(database)
            repository.registerAgent(runtimeRegistration())
            val registered = runtimeEvent(
                eventId = "runtime-registered",
                revision = 1,
                phase = AgentRuntimePhase.REGISTERED,
                previousStateHash = null
            )
            val running = runtimeEvent(
                eventId = "runtime-running",
                revision = 2,
                phase = AgentRuntimePhase.RUNNING,
                previousStateHash = registered.state.stateHash
            )

            assertEquals(registered, repository.appendRuntimeStateEvent(registered))
            assertEquals(registered, repository.appendRuntimeStateEvent(registered))
            assertEquals(running, repository.appendRuntimeStateEvent(running))
            assertEquals(
                listOf(registered, running),
                repository.runtimeStateEvents(runId, groupId.value, agentId.value, 1)
            )

            val mutatedIdentity = registered.copy(
                state = registered.state.copy(budgetRemaining = 9.0, stateHash = "")
                    .withComputedHash(),
                eventHash = ""
            ).withComputedHash()
            assertFailsWith<IllegalArgumentException> {
                repository.appendRuntimeStateEvent(mutatedIdentity)
            }

            val conflictingRevision = runtimeEvent(
                eventId = "runtime-running-conflict",
                revision = 2,
                phase = AgentRuntimePhase.TERMINATION_REQUESTED,
                previousStateHash = registered.state.stateHash
            )
            assertFailsWith<IllegalArgumentException> {
                repository.appendRuntimeStateEvent(conflictingRevision)
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun `runtime state event append rejects invalid hashes and broken chains`() = runTest {
        val database = database()
        try {
            val repository = WorkerExecutionRepository(database)
            repository.registerAgent(runtimeRegistration())
            val first = runtimeEvent(
                eventId = "runtime-first",
                revision = 1,
                phase = AgentRuntimePhase.REGISTERED,
                previousStateHash = null
            )
            assertFailsWith<IllegalArgumentException> {
                repository.appendRuntimeStateEvent(first.copy(eventHash = "invalid"))
            }
            assertFailsWith<IllegalArgumentException> {
                repository.appendRuntimeStateEvent(
                    runtimeEvent(
                        eventId = "runtime-skipped",
                        revision = 2,
                        phase = AgentRuntimePhase.RUNNING,
                        previousStateHash = first.state.stateHash
                    )
                )
            }

            repository.appendRuntimeStateEvent(first)
            assertFailsWith<IllegalArgumentException> {
                repository.appendRuntimeStateEvent(
                    runtimeEvent(
                        eventId = "runtime-broken-link",
                        revision = 2,
                        phase = AgentRuntimePhase.RUNNING,
                        previousStateHash = "wrong-previous-state"
                    )
                )
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun `runtime state events require the exact registered lifecycle generation`() = runTest {
        val database = database()
        try {
            val repository = WorkerExecutionRepository(database)
            assertFailsWith<IllegalArgumentException> {
                repository.appendRuntimeStateEvent(
                    runtimeEvent(
                        eventId = "runtime-unregistered",
                        revision = 1,
                        phase = AgentRuntimePhase.REGISTERED,
                        previousStateHash = null
                    )
                )
            }

            repository.registerAgent(runtimeRegistration(generation = 1))
            assertFailsWith<IllegalArgumentException> {
                repository.appendRuntimeStateEvent(
                    runtimeEvent(
                        eventId = "runtime-wrong-generation",
                        revision = 1,
                        phase = AgentRuntimePhase.REGISTERED,
                        previousStateHash = null,
                        generation = 2
                    )
                )
            }
        } finally {
            database.close()
        }
    }

    private fun database() = SubstrateDatabaseFactory(
        createTempDirectory("worker-execution-repository-").toFile()
    ).get(runId, groupId.value)

    private fun positiveCommit(): ContributionCommit = ContributionCommit(
        commitId = "commit-positive",
        runId = runId,
        groupId = groupId,
        round = 1,
        agentId = agentId,
        nodeId = nodeId,
        commitType = CommitType.POSITIVE_RESULT,
        artifactIds = listOf("artifact-1"),
        claimIds = listOf("claim-1"),
        toolRunIds = emptyList(),
        testsRun = listOf("test-1"),
        changedStateSummary = "Produced a supported result.",
        evidenceSummary = "Evidence is stored in artifact-1.",
        confidence = 0.8,
        unresolvedRisks = listOf("Independent replication remains."),
        successorRecommendations = listOf("Replicate the result."),
        dedupeHash = "",
        committedAt = Instant.parse("2026-07-11T10:00:00Z")
    ).withDedupeHash()

    private fun portfolio(portfolioId: String, round: Int): NodePortfolio = NodePortfolio(
        portfolioId = portfolioId,
        runId = runId,
        atlasId = "atlas-worker-execution",
        atlasHash = "atlas-hash",
        groupId = groupId,
        round = round,
        bids = listOf(
            NodeBid(
                bidId = "bid-$portfolioId",
                groupId = groupId,
                nodeId = nodeId,
                intendedArtifactType = ArtifactType.CLAIM_SET,
                estimatedCredits = 2.0,
                expectedProofMethod = ProofMethod.SOURCE_CORROBORATION,
                reason = "Exercise exact portfolio persistence.",
                round = round
            )
        ),
        diversityScore = 0.5
    ).withComputedHash()

    private fun noContributionCommit(): ContributionCommit = ContributionCommit(
        commitId = "commit-none",
        runId = runId,
        groupId = groupId,
        round = 1,
        agentId = AtlasAgentId("agent-no-contribution"),
        nodeId = nodeId,
        commitType = CommitType.NO_CONTRIBUTION,
        artifactIds = emptyList(),
        claimIds = emptyList(),
        toolRunIds = emptyList(),
        testsRun = emptyList(),
        changedStateSummary = "No durable state changed.",
        evidenceSummary = "",
        confidence = 0.0,
        unresolvedRisks = listOf("Node remains unresolved."),
        successorRecommendations = listOf("Return the node to the frontier."),
        dedupeHash = "",
        committedAt = Instant.parse("2026-07-11T10:00:00Z")
    ).withDedupeHash()

    private fun ContributionCommit.withDedupeHash(): ContributionCommit =
        copy(dedupeHash = AtlasHashing.hashContributionDedupe(this))

    private fun checkpoint(
        eventId: String,
        kind: TtlCheckpointKind,
        fraction: Double,
        phase: AgentExecutionPhase
    ) = TtlCheckpointEvent(
        eventId = eventId,
        runId = runId,
        groupId = groupId,
        agentId = agentId,
        round = 1,
        checkpoint = kind,
        elapsedFraction = fraction,
        remainingBudget = 10.0,
        phase = phase,
        occurredAt = Instant.parse("2026-07-11T10:00:00Z")
    )

    private fun runtimeEvent(
        eventId: String,
        revision: Long,
        phase: AgentRuntimePhase,
        previousStateHash: String?,
        generation: Int = 1
    ): AgentRuntimeStateEvent {
        val state = AgentRuntimeState(
            runId = runId,
            groupId = groupId,
            agentId = agentId,
            generation = generation,
            phase = phase,
            revision = revision,
            budgetRemaining = 10.0,
            registrationRecorded = true
        ).withComputedHash()
        return AgentRuntimeStateEvent(
            eventId = eventId,
            runId = runId,
            groupId = groupId,
            agentId = agentId,
            generation = generation,
            revision = revision,
            intent = if (revision == 1L) {
                AgentRuntimeTransitionIntent.REGISTER_AGENT
            } else {
                AgentRuntimeTransitionIntent.START_EXECUTION
            },
            previousStateHash = previousStateHash,
            state = state,
            occurredAt = Instant.parse("2026-07-11T10:00:0${revision}Z")
        ).withComputedHash()
    }

    private fun runtimeRegistration(generation: Int = 1) = AgentLifecycleRegistration(
        registrationId = "runtime-registration-$generation",
        runId = runId,
        groupId = groupId,
        agentId = agentId,
        role = "Runtime worker",
        generation = generation,
        classification = AgentLifecycleClassification.NON_DAEMON,
        registeredAt = Instant.parse("2026-07-11T09:59:00Z")
    ).withComputedHash()

    private fun exit(
        exitId: String,
        agentId: AtlasAgentId,
        acceptedContribution: Boolean,
        degraded: Boolean,
        group: AtlasGroupId = groupId
    ) = AgentExitRecord(
        exitId = exitId,
        runId = runId,
        groupId = group,
        agentId = agentId,
        nodeId = nodeId,
        round = 1,
        finalPhase = if (acceptedContribution) {
            AgentExecutionPhase.CONTRIBUTION_STAGED
        } else {
            AgentExecutionPhase.NO_CONTRIBUTION
        },
        commitId = if (acceptedContribution) "commit-$exitId" else null,
        commitType = if (acceptedContribution) CommitType.POSITIVE_RESULT else null,
        acceptedContribution = acceptedContribution,
        acceptedStructuredDeadEnd = false,
        acceptedNoveltyProposal = false,
        degradedExecution = degraded,
        wasKilled = false,
        exitedAt = Instant.parse("2026-07-11T11:00:00Z")
    )
}
