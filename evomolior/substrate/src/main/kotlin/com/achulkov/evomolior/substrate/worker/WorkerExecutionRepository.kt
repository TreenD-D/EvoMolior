@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.substrate.worker

import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.contract.AgentExitRecord
import com.achulkov.evomolior.core.contract.AgentLifecycleRegistration
import com.achulkov.evomolior.core.contract.CommitType
import com.achulkov.evomolior.core.contract.ContributionCommit
import com.achulkov.evomolior.core.contract.DegradedExecutionRecord
import com.achulkov.evomolior.core.contract.NodePortfolio
import com.achulkov.evomolior.core.contract.TtlCheckpointEvent
import com.achulkov.evomolior.core.contract.TtlCheckpointKind
import com.achulkov.evomolior.core.contract.UsefulExitSummary
import com.achulkov.evomolior.substrate.db.EvoMoliorDatabase
import com.achulkov.evomolior.substrate.db.entity.AgentExitRecordEntity
import com.achulkov.evomolior.substrate.db.entity.AgentLifecycleRegistrationEntity
import com.achulkov.evomolior.substrate.db.entity.ContributionCommitEntity
import com.achulkov.evomolior.substrate.db.entity.DegradedExecutionRecordEntity
import com.achulkov.evomolior.substrate.db.entity.NodePortfolioEntity
import com.achulkov.evomolior.substrate.db.entity.TtlCheckpointEventEntity
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/** Validates and persists immutable worker execution records. */
class WorkerExecutionRepository(
    private val database: EvoMoliorDatabase,
    private val json: Json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    }
) {
    private val dao = database.workerExecutionDao()

    /** Persists one exact NodePortfolio idempotently while enforcing immutable identity. */
    suspend fun appendNodePortfolio(portfolio: NodePortfolio): NodePortfolio {
        require(portfolio.portfolioHash.isNotBlank() && portfolio.hasValidHash()) {
            "NodePortfolio portfolioHash must be present and match its exact content"
        }
        val encoded = encode(NodePortfolio.serializer(), portfolio)
        dao.nodePortfolioById(portfolio.portfolioId)?.let { existing ->
            val decoded = decode(NodePortfolio.serializer(), existing.contentJson)
            require(decoded == portfolio) { "NodePortfolio identity is immutable" }
            return decoded
        }
        dao.insertNodePortfolio(
            NodePortfolioEntity(
                portfolioId = portfolio.portfolioId,
                runId = portfolio.runId,
                groupId = portfolio.groupId.value,
                round = portfolio.round,
                portfolioHash = portfolio.portfolioHash,
                contentJson = encoded
            )
        )
        val persisted = requireNotNull(dao.nodePortfolioById(portfolio.portfolioId)) {
            "NodePortfolio append did not produce a persisted authority record"
        }
        val decoded = decode(NodePortfolio.serializer(), persisted.contentJson)
        require(decoded == portfolio) { "NodePortfolio identity is immutable" }
        return decoded
    }

    /** Returns exact NodePortfolios for one run and group in round and identity order. */
    suspend fun nodePortfolios(runId: String, groupId: String): List<NodePortfolio> =
        dao.nodePortfolios(runId, groupId)
            .map { decode(NodePortfolio.serializer(), it.contentJson) }

    /** Persists one immutable agent lifecycle registration idempotently. */
    suspend fun registerAgent(
        registration: AgentLifecycleRegistration
    ): AgentLifecycleRegistration {
        require(registration.hasValidHash()) {
            "Agent lifecycle registration hash does not match its content"
        }
        dao.registrations(registration.runId, registration.groupId.value)
            .firstOrNull { it.agentId == registration.agentId.value }
            ?.let { existing ->
                val decoded = decode(
                    AgentLifecycleRegistration.serializer(),
                    existing.contentJson
                )
                require(decoded == registration) {
                    "Agent lifecycle registration identity is immutable"
                }
                return decoded
            }
        dao.insertRegistration(
            AgentLifecycleRegistrationEntity(
                registrationId = registration.registrationId,
                runId = registration.runId,
                groupId = registration.groupId.value,
                agentId = registration.agentId.value,
                role = registration.role,
                generation = registration.generation,
                classification = registration.classification.name,
                registrationHash = registration.registrationHash,
                contentJson = encode(AgentLifecycleRegistration.serializer(), registration),
                registeredAtMs = registration.registeredAt.toEpochMilliseconds()
            )
        )
        return registration
    }

    /** Returns all immutable lifecycle registrations for one group run. */
    suspend fun registrations(
        runId: String,
        groupId: String
    ): List<AgentLifecycleRegistration> =
        dao.registrations(runId, groupId).map {
            decode(AgentLifecycleRegistration.serializer(), it.contentJson)
        }

    /** Persists one deduplicated contribution commitment. */
    suspend fun commit(contribution: ContributionCommit): ContributionCommit {
        require(contribution.confidence in 0.0..1.0) { "Contribution confidence must be in [0, 1]" }
        require(contribution.changedStateSummary.isNotBlank()) {
            "Contribution changedStateSummary must not be blank"
        }
        val references = contribution.artifactIds + contribution.claimIds +
            contribution.citedArtifactIds +
            contribution.toolRunIds + contribution.testsRun +
            contribution.referencedNoveltyProposalIds
        if (contribution.commitType == CommitType.NO_CONTRIBUTION) {
            require(references.isEmpty()) { "NO_CONTRIBUTION cannot claim durable references" }
        } else {
            require(references.isNotEmpty() || contribution.evidenceSummary.isNotBlank()) {
                "A potentially useful contribution requires a durable reference or evidence summary"
            }
        }
        val expectedDedupeHash = AtlasHashing.hashContributionDedupe(contribution)
        require(AtlasHashing.constantTimeEquals(contribution.dedupeHash, expectedDedupeHash)) {
            "Contribution dedupe hash does not match its semantic content"
        }
        dao.contributionByDedupeHash(
            contribution.runId,
            contribution.groupId.value,
            contribution.dedupeHash
        )?.let { existing ->
            return decode(ContributionCommit.serializer(), existing.contentJson)
        }
        dao.insertContribution(
            ContributionCommitEntity(
                commitId = contribution.commitId,
                runId = contribution.runId,
                groupId = contribution.groupId.value,
                agentId = contribution.agentId.value,
                nodeId = contribution.nodeId?.value,
                round = contribution.round,
                commitType = contribution.commitType.name,
                dedupeHash = contribution.dedupeHash,
                contentJson = encode(ContributionCommit.serializer(), contribution),
                committedAtMs = contribution.committedAt.toEpochMilliseconds()
            )
        )
        return contribution
    }

    /** Returns immutable commitments submitted by one agent. */
    suspend fun contributionsForAgent(
        runId: String,
        groupId: String,
        agentId: String
    ): List<ContributionCommit> =
        dao.contributionsForAgent(runId, groupId, agentId)
            .map { decode(ContributionCommit.serializer(), it.contentJson) }

    /** Returns immutable commitments for one Atlas node in round order. */
    suspend fun contributionsForNode(
        runId: String,
        groupId: String,
        nodeId: String
    ): List<ContributionCommit> =
        dao.contributionsForNode(runId, groupId, nodeId)
            .map { decode(ContributionCommit.serializer(), it.contentJson) }

    /** Returns every immutable group contribution authored in one round. */
    suspend fun contributionsForRound(
        runId: String,
        groupId: String,
        round: Int
    ): List<ContributionCommit> =
        dao.contributionsForRound(runId, groupId, round)
            .map { decode(ContributionCommit.serializer(), it.contentJson) }

    /** Persists a degraded-execution record that cannot count as a positive contribution. */
    suspend fun recordDegradedExecution(record: DegradedExecutionRecord) {
        dao.insertDegradedExecution(
            DegradedExecutionRecordEntity(
                recordId = record.recordId,
                runId = record.runId,
                groupId = record.groupId.value,
                agentId = record.agentId.value,
                nodeId = record.nodeId?.value,
                round = record.round,
                reasonCode = record.reasonCode,
                contentJson = encode(DegradedExecutionRecord.serializer(), record),
                occurredAtMs = record.occurredAt.toEpochMilliseconds()
            )
        )
    }

    /** Persists an ordered TTL checkpoint once; duplicate checkpoint attempts are idempotent. */
    suspend fun recordCheckpoint(event: TtlCheckpointEvent): Boolean {
        val expectedFraction = CHECKPOINT_FRACTIONS.getValue(event.checkpoint)
        require(event.elapsedFraction == expectedFraction) {
            "${event.checkpoint} must use elapsed fraction $expectedFraction"
        }
        require(event.remainingBudget >= 0.0) { "TTL checkpoint budget cannot be negative" }
        return dao.insertCheckpoint(
            TtlCheckpointEventEntity(
                eventId = event.eventId,
                runId = event.runId,
                groupId = event.groupId.value,
                agentId = event.agentId.value,
                round = event.round,
                checkpoint = event.checkpoint.name,
                elapsedFraction = event.elapsedFraction,
                remainingBudget = event.remainingBudget,
                phase = event.phase.name,
                contentJson = encode(TtlCheckpointEvent.serializer(), event),
                occurredAtMs = event.occurredAt.toEpochMilliseconds()
            )
        ) != -1L
    }

    /** Returns persisted TTL checkpoints in elapsed-fraction order. */
    suspend fun checkpointsForAgent(
        runId: String,
        groupId: String,
        agentId: String
    ): List<TtlCheckpointEvent> =
        dao.checkpointsForAgent(runId, groupId, agentId)
            .map { decode(TtlCheckpointEvent.serializer(), it.contentJson) }

    /** Persists one immutable final exit classification. */
    suspend fun recordExit(record: AgentExitRecord): Boolean =
        dao.insertExit(
            AgentExitRecordEntity(
                exitId = record.exitId,
                runId = record.runId,
                groupId = record.groupId.value,
                agentId = record.agentId.value,
                nodeId = record.nodeId?.value,
                round = record.round,
                finalPhase = record.finalPhase.name,
                commitId = record.commitId,
                commitType = record.commitType?.name,
                acceptedContribution = record.acceptedContribution,
                acceptedStructuredDeadEnd = record.acceptedStructuredDeadEnd,
                acceptedNoveltyProposal = record.acceptedNoveltyProposal,
                degradedExecution = record.degradedExecution,
                wasKilled = record.wasKilled,
                contentJson = encode(AgentExitRecord.serializer(), record),
                exitedAtMs = record.exitedAt.toEpochMilliseconds()
            )
        ) != -1L

    /** Returns immutable final exit records for publication accounting. */
    suspend fun exits(runId: String, groupId: String? = null): List<AgentExitRecord> =
        dao.exits(runId, groupId).map {
            decode(AgentExitRecord.serializer(), it.contentJson)
        }

    /** Reports useful ordinary exits for one run or group. */
    suspend fun usefulExitSummary(runId: String, groupId: String? = null): UsefulExitSummary {
        val exits = dao.exits(runId, groupId)
            .map { decode(AgentExitRecord.serializer(), it.contentJson) }
        return UsefulExitSummary(
            runId = runId,
            groupId = groupId?.let(::AtlasGroupId),
            usefulExitCount = exits.count(AgentExitRecord::useful),
            ordinaryExitCount = exits.size
        )
    }

    private fun <T> encode(serializer: KSerializer<T>, value: T): String =
        json.encodeToString(serializer, value)

    private fun <T> decode(serializer: KSerializer<T>, value: String): T =
        json.decodeFromString(serializer, value)

    private companion object {
        val CHECKPOINT_FRACTIONS = mapOf(
            TtlCheckpointKind.WORK_WARNING to 0.65,
            TtlCheckpointKind.COMMIT_SELECTION_REQUIRED to 0.80,
            TtlCheckpointKind.BROAD_EXPLORATION_DISABLED to 0.90,
            TtlCheckpointKind.DEATH_NOTE_GRACE to 0.95,
            TtlCheckpointKind.TERMINATED to 1.0
        )
    }
}
