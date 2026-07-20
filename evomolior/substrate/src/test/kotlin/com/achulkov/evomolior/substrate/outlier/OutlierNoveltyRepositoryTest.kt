package com.achulkov.evomolior.substrate.outlier

import androidx.sqlite.SQLiteException
import com.achulkov.evomolior.core.atlas.AtlasAgentId
import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.HiddenMapAnticipation
import com.achulkov.evomolior.core.atlas.NoveltyAssessmentStatus
import com.achulkov.evomolior.core.atlas.NoveltyGraderAssessment
import com.achulkov.evomolior.core.atlas.NoveltyGraderVector
import com.achulkov.evomolior.core.atlas.NoveltyScreeningResult
import com.achulkov.evomolior.core.atlas.NoveltyScreeningStatus
import com.achulkov.evomolior.core.atlas.NoveltyType
import com.achulkov.evomolior.core.atlas.NoveltyVectorProposal
import com.achulkov.evomolior.core.atlas.OutlierDownstreamLineage
import com.achulkov.evomolior.core.atlas.OutlierDownstreamOutcome
import com.achulkov.evomolior.core.atlas.OutlierFitnessRecord
import com.achulkov.evomolior.core.atlas.ShadowNodeLifecycleEvent
import com.achulkov.evomolior.core.atlas.ShadowNodeState
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.db.entity.ShadowNodeLifecycleEventEntity
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class OutlierNoveltyRepositoryTest {
    private val runId = "run-outlier"
    private val groupId = AtlasGroupId("outlier-group")
    private val proposerAgentId = AtlasAgentId("outlier-agent")
    private val proposalId = "proposal-1"

    @Test
    fun `initial proposal is atomic and exact retry is idempotent`() = runTest {
        val database = createDatabase()
        try {
            val repository = OutlierNoveltyRepository(database, now = { 100L })
            val proposal = proposal().withComputedHash()
            val initialEvent = initialEvent(proposal).withComputedHash()

            database.outlierNoveltyDao().insertLifecycleEvent(
                ShadowNodeLifecycleEventEntity(
                    eventId = initialEvent.eventId,
                    proposalId = "preoccupied-proposal",
                    proposalHash = "preoccupied-proposal-hash",
                    runId = runId,
                    sequence = 1L,
                    toState = ShadowNodeState.PROPOSED.name,
                    round = proposal.round,
                    eventHash = "preoccupied-event-hash",
                    contentJson = """{"eventId":"preoccupied"}""",
                    createdAtMs = 1L
                )
            )

            assertFailsWith<SQLiteException> {
                repository.appendInitialProposal(proposal, initialEvent)
            }
            assertNull(database.outlierNoveltyDao().proposalById(proposal.proposalId))
            assertNull(database.outlierNoveltyDao().proposalByHash(proposal.proposalHash))
            val retryProposal = proposal.copy(
                proposalId = "proposal-2",
                noveltyFingerprint = "novelty-fingerprint-2",
                proposalHash = ""
            ).withComputedHash()
            val retryInitialEvent = initialEvent(retryProposal).copy(
                eventId = "event-2",
                previousEventHash = null,
                eventHash = ""
            ).withComputedHash()
            assertEquals(
                retryProposal,
                repository.appendInitialProposal(retryProposal, retryInitialEvent)
            )
            assertEquals(
                retryProposal,
                repository.appendInitialProposal(retryProposal, retryInitialEvent)
            )

            assertEquals(
                listOf(retryProposal),
                repository.proposalsForRound(runId, retryProposal.round)
            )
            assertEquals(listOf(retryInitialEvent), repository.lifecycle(retryProposal.proposalId))
        } finally {
            database.close()
        }
    }

    @Test
    fun `changed retry is rejected after initial proposal is committed`() = runTest {
        val database = createDatabase()
        try {
            val repository = OutlierNoveltyRepository(database)
            val proposal = proposal().withComputedHash()
            val initialEvent = initialEvent(proposal).withComputedHash()
            repository.appendInitialProposal(proposal, initialEvent)

            val changedProposal = proposal.copy(
                noveltyFingerprint = "novelty-fingerprint-2",
                expectedCost = 3.0,
                proposalHash = ""
            ).withComputedHash()
            val changedEvent = initialEvent(changedProposal).withComputedHash()

            assertFailsWith<IllegalArgumentException> {
                repository.appendInitialProposal(changedProposal, changedEvent)
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun `screening is immutable for exact retries and changed retries are rejected`() = runTest {
        val database = createDatabase()
        try {
            val repository = OutlierNoveltyRepository(database)
            val proposal = proposal().withComputedHash()
            val initialEvent = initialEvent(proposal).withComputedHash()
            repository.appendInitialProposal(proposal, initialEvent)
            val screening = screening(proposal.proposalHash).withComputedHash()

            assertEquals(screening, repository.appendScreening(screening))
            assertEquals(screening, repository.appendScreening(screening))

            val changedScreening = screening.copy(
                screeningId = "screening-2",
                status = NoveltyScreeningStatus.DUPLICATE_REVEALED,
                hiddenMapAnticipation = HiddenMapAnticipation.PARTIAL,
                screeningHash = ""
            ).withComputedHash()
            assertFailsWith<IllegalArgumentException> {
                repository.appendScreening(changedScreening)
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun `assessment is idempotent per grader group and changed retries are rejected`() = runTest {
        val database = createDatabase()
        try {
            val repository = OutlierNoveltyRepository(database)
            val proposal = proposal().withComputedHash()
            val initialEvent = initialEvent(proposal).withComputedHash()
            repository.appendInitialProposal(proposal, initialEvent)
            val assessment = assessment(proposal.proposalHash, "grader-a").withComputedHash()

            assertEquals(assessment, repository.appendAssessment(assessment))
            assertEquals(assessment, repository.appendAssessment(assessment))

            val differentGraderAssessment = assessment(
                proposal.proposalHash,
                "grader-b"
            ).withComputedHash()
            assertEquals(differentGraderAssessment, repository.appendAssessment(differentGraderAssessment))
            assertEquals(
                listOf(assessment, differentGraderAssessment),
                repository.assessmentsForProposal(proposal.proposalHash)
            )

            val changedAssessment = assessment.copy(
                status = NoveltyAssessmentStatus.INVALID,
                vector = null,
                assessmentHash = ""
            ).withComputedHash()
            assertFailsWith<IllegalArgumentException> {
                repository.appendAssessment(changedAssessment)
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun `lifecycle accepts the legal chain and rejects an illegal transition`() = runTest {
        val database = createDatabase()
        try {
            val repository = OutlierNoveltyRepository(database)
            val proposal = proposal().withComputedHash()
            val initialEvent = initialEvent(proposal).withComputedHash()
            repository.appendInitialProposal(proposal, initialEvent)
            val screening = screening(proposal.proposalHash).withComputedHash()
            repository.appendScreening(screening)
            val illegalTransition = ShadowNodeLifecycleEvent(
                eventId = "event-illegal",
                proposalId = proposal.proposalId,
                proposalHash = proposal.proposalHash,
                runId = proposal.runId,
                sequence = 2L,
                previousEventHash = initialEvent.eventHash,
                fromState = ShadowNodeState.PROPOSED,
                toState = ShadowNodeState.TESTED,
                round = proposal.round
            ).withComputedHash()

            assertFailsWith<IllegalArgumentException> {
                repository.appendLifecycle(illegalTransition)
            }

            val duplicateChecked = nextLifecycle(
                previous = initialEvent,
                eventId = "event-2",
                toState = ShadowNodeState.DUPLICATE_CHECKED,
                screeningHash = screening.screeningHash
            )
            val shadowNode = nextLifecycle(
                previous = duplicateChecked,
                eventId = "event-3",
                toState = ShadowNodeState.SHADOW_NODE,
                amendmentId = "amendment-1"
            )
            val tested = nextLifecycle(
                previous = shadowNode,
                eventId = "event-4",
                toState = ShadowNodeState.TESTED,
                evidenceArtifactIds = setOf("artifact-1")
            )
            val accepted = nextLifecycle(
                previous = tested,
                eventId = "event-5",
                toState = ShadowNodeState.ACCEPTED_AMENDMENT,
                amendmentId = "amendment-1",
                evidenceArtifactIds = setOf("artifact-1")
            )

            assertEquals(duplicateChecked, repository.appendLifecycle(duplicateChecked))
            assertEquals(shadowNode, repository.appendLifecycle(shadowNode))
            assertEquals(tested, repository.appendLifecycle(tested))
            assertEquals(accepted, repository.appendLifecycle(accepted))
            assertEquals(
                listOf(
                    initialEvent(proposal).withComputedHash(),
                    duplicateChecked,
                    shadowNode,
                    tested,
                    accepted
                ),
                repository.lifecycle(proposal.proposalId)
            )

        } finally {
            database.close()
        }
    }

    @Test
    fun `downstream evidence must be later round and is queryable`() = runTest {
        val database = createDatabase()
        try {
            val repository = OutlierNoveltyRepository(database)
            val proposal = proposal().withComputedHash()
            val initialEvent = initialEvent(proposal).withComputedHash()
            repository.appendInitialProposal(proposal, initialEvent)

            assertFailsWith<IllegalArgumentException> {
                downstreamLineage(
                    proposalHash = proposal.proposalHash,
                    sourceRound = 2,
                    observationRound = 2
                ).withComputedHash()
            }

            val lineage = downstreamLineage(
                proposalHash = proposal.proposalHash,
                sourceRound = 2,
                observationRound = 3
            ).withComputedHash()

            assertEquals(lineage, repository.appendDownstreamLineage(lineage))
            assertEquals(listOf(lineage), repository.downstreamLineage(proposal.proposalId))
        } finally {
            database.close()
        }
    }

    @Test
    fun `fitness is unavailable before downstream evidence and accepted after lineage`() = runTest {
        val database = createDatabase()
        try {
            val repository = OutlierNoveltyRepository(database)
            val proposal = proposal().withComputedHash()
            val initialEvent = initialEvent(proposal).withComputedHash()
            repository.appendInitialProposal(proposal, initialEvent)
            val lineage = downstreamLineage(
                proposalHash = proposal.proposalHash,
                sourceRound = 2,
                observationRound = 4
            ).withComputedHash()
            val fitness = fitness(
                proposalHash = proposal.proposalHash,
                evidenceLineageHashes = setOf(lineage.lineageHash)
            ).withComputedHash()

            assertFailsWith<IllegalArgumentException> {
                repository.appendFitness(fitness)
            }

            repository.appendDownstreamLineage(lineage)

            assertEquals(fitness, repository.appendFitness(fitness))
            assertEquals(listOf(fitness), repository.fitnessForProposal(proposal.proposalId))
        } finally {
            database.close()
        }
    }

    private fun createDatabase() =
        SubstrateDatabaseFactory(createTempDirectory("outlier-novelty-repository-").toFile())
            .get(runId, groupId.value)

    private fun proposal() = NoveltyVectorProposal(
        proposalId = proposalId,
        runId = runId,
        atlasHash = "atlas-hash",
        groupId = groupId,
        round = 2,
        proposerAgentId = proposerAgentId,
        noveltyType = NoveltyType.MISSING_INTEGRATION_DEPENDENCY,
        affectedVisibleNodeIds = setOf(com.achulkov.evomolior.core.atlas.NodeId("visible-node")),
        missingLayerOrAssumption = "Dependency gap is not represented in the atlas.",
        proposedVector = "Add a dependency-specific validation step.",
        whyCurrentMapMayMissIt = "The current map encodes structure but not this failure mode.",
        falsifiablePrediction = "The new check will reject at least one invalid chain.",
        minimalTestOrArtifact = "Execute one legal and one illegal transition.",
        expectedReceiverImpact = "Reduces invalid capture risk.",
        expectedCost = 2.0,
        conflictWithExistingAssumptions = listOf("All transitions are already encoded."),
        noveltyFingerprint = "novelty-fingerprint-1"
    )

    private fun screening(proposalHash: String) = NoveltyScreeningResult(
        screeningId = "screening-1",
        proposalHash = proposalHash,
        status = NoveltyScreeningStatus.UNIQUE,
        revealedDuplicateNodeIds = setOf(),
        canonDuplicateArtifactIds = setOf(),
        hiddenMapAnticipation = HiddenMapAnticipation.NONE,
        sealedHiddenComparisonHash = "sealed-hidden-comparison-hash",
        reason = "No visible duplicate or hidden overlap"
    )

    private fun assessment(proposalHash: String, graderGroupId: String) =
        NoveltyGraderAssessment(
            assessmentId = "assessment-$graderGroupId",
            proposalHash = proposalHash,
            graderGroupId = graderGroupId,
            status = NoveltyAssessmentStatus.VALID,
            vector = NoveltyGraderVector(
                novelty = 0.8,
                receiverRelevance = 0.7,
                testability = 0.9,
                plausibility = 0.8,
                expectedImpact = 0.7,
                costDiscipline = 0.9,
                downstreamAdoption = 0.6
            ),
            rationaleHash = "rationale-$graderGroupId"
        )

    private fun nextLifecycle(
        previous: ShadowNodeLifecycleEvent,
        eventId: String,
        toState: ShadowNodeState,
        screeningHash: String? = null,
        graderVerdictHash: String? = null,
        amendmentId: String? = null,
        evidenceArtifactIds: Set<String> = emptySet()
    ) = ShadowNodeLifecycleEvent(
        eventId = eventId,
        proposalId = previous.proposalId,
        proposalHash = previous.proposalHash,
        runId = previous.runId,
        sequence = previous.sequence + 1,
        previousEventHash = previous.eventHash,
        fromState = previous.toState,
        toState = toState,
        round = previous.round,
        screeningHash = screeningHash,
        graderVerdictHash = graderVerdictHash,
        amendmentId = amendmentId,
        evidenceArtifactIds = evidenceArtifactIds
    ).withComputedHash()

    private fun initialEvent(proposal: NoveltyVectorProposal) = ShadowNodeLifecycleEvent(
        eventId = "event-1",
        proposalId = proposal.proposalId,
        proposalHash = proposal.proposalHash,
        runId = proposal.runId,
        sequence = 1L,
        previousEventHash = null,
        fromState = null,
        toState = ShadowNodeState.PROPOSED,
        round = proposal.round
    )

    private fun downstreamLineage(
        proposalHash: String,
        sourceRound: Int,
        observationRound: Int
    ) = OutlierDownstreamLineage(
        lineageId = "lineage-$observationRound",
        proposalId = proposalId,
        proposalHash = proposalHash,
        sourceAgentId = proposerAgentId,
        sourceRound = sourceRound,
        observingGroupId = groupId,
        observingAgentId = AtlasAgentId("observer-$observationRound"),
        observationRound = observationRound,
        outcome = OutlierDownstreamOutcome.TESTED,
        evidenceArtifactIds = setOf("artifact-$observationRound")
    )

    private fun fitness(
        proposalHash: String,
        evidenceLineageHashes: Set<String>
    ) = OutlierFitnessRecord(
        recordId = "fitness-1",
        proposalId = proposalId,
        proposalHash = proposalHash,
        agentId = proposerAgentId,
        sourceRound = 2,
        observationRound = 4,
        noveltyCredit = 0.8,
        anticipationCredit = 0.7,
        downstreamAdoptionCredit = 0.6,
        evidenceLineageHashes = evidenceLineageHashes
    )
}
