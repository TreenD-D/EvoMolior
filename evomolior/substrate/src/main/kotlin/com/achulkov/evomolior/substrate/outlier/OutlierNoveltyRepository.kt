package com.achulkov.evomolior.substrate.outlier

import com.achulkov.evomolior.core.atlas.NoveltyGraderAssessment
import com.achulkov.evomolior.core.atlas.NoveltyScreeningResult
import com.achulkov.evomolior.core.atlas.NoveltyVectorProposal
import com.achulkov.evomolior.core.atlas.OutlierDownstreamLineage
import com.achulkov.evomolior.core.atlas.OutlierFitnessRecord
import com.achulkov.evomolior.core.atlas.ShadowNodeGraderVerdict
import com.achulkov.evomolior.core.atlas.ShadowNodeLifecycleEvent
import com.achulkov.evomolior.core.atlas.ShadowNodeLifecycleValidator
import com.achulkov.evomolior.core.atlas.ShadowNodeState
import com.achulkov.evomolior.substrate.db.EvoMoliorDatabase
import com.achulkov.evomolior.substrate.db.entity.NoveltyGraderAssessmentEntity
import com.achulkov.evomolior.substrate.db.entity.NoveltyScreeningResultEntity
import com.achulkov.evomolior.substrate.db.entity.NoveltyVectorProposalEntity
import com.achulkov.evomolior.substrate.db.entity.OutlierDownstreamLineageEntity
import com.achulkov.evomolior.substrate.db.entity.OutlierFitnessRecordEntity
import com.achulkov.evomolior.substrate.db.entity.ShadowNodeGraderVerdictEntity
import com.achulkov.evomolior.substrate.db.entity.ShadowNodeLifecycleEventEntity
import kotlinx.serialization.KSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Append-only persistence authority for Outlier proposals and shadow-node evidence. */
class OutlierNoveltyRepository(
    private val database: EvoMoliorDatabase,
    private val json: Json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    },
    private val now: () -> Long = System::currentTimeMillis
) {
    private val dao = database.outlierNoveltyDao()

    /**
     * Atomically persists a proposal and its first `PROPOSED` lifecycle event.
     */
    suspend fun appendInitialProposal(
        proposal: NoveltyVectorProposal,
        initialEvent: ShadowNodeLifecycleEvent
    ): NoveltyVectorProposal {
        require(proposal.hasValidHash()) { "Novelty proposal hash is invalid" }
        ShadowNodeLifecycleValidator.validateNext(null, initialEvent)
        require(
            initialEvent.proposalId == proposal.proposalId &&
                initialEvent.proposalHash == proposal.proposalHash &&
                initialEvent.runId == proposal.runId &&
                initialEvent.round == proposal.round
        ) {
            "Initial novelty lifecycle event does not match its proposal"
        }
        dao.proposalById(proposal.proposalId)?.let { existing ->
            val decodedProposal = decode(
                NoveltyVectorProposal.serializer(),
                existing.contentJson
            )
            val existingEvent = dao.lifecycleEventById(initialEvent.eventId)
                ?.let {
                    decode(ShadowNodeLifecycleEvent.serializer(), it.contentJson)
                }
            require(decodedProposal == proposal && existingEvent == initialEvent) {
                "Novelty proposal identity or initial lifecycle is immutable"
            }
            return decodedProposal
        }
        val createdAtMs = now()
        dao.insertInitialProposal(
            proposal = proposal.toEntity(createdAtMs),
            lifecycleEvent = initialEvent.toEntity(createdAtMs)
        )
        return proposal
    }

    /** Persists one immutable proposal, idempotently for exact retries. */
    suspend fun appendProposal(value: NoveltyVectorProposal): NoveltyVectorProposal {
        require(value.hasValidHash()) { "Novelty proposal hash is invalid" }
        dao.proposalById(value.proposalId)?.let { existing ->
            val decoded = decode(NoveltyVectorProposal.serializer(), existing.contentJson)
            require(decoded == value) { "Novelty proposal identity is immutable" }
            return decoded
        }
        dao.insertProposal(value.toEntity(now()))
        return value
    }

    suspend fun proposalsForRound(runId: String, round: Int): List<NoveltyVectorProposal> =
        dao.proposalsForRound(runId, round)
            .map { decode(NoveltyVectorProposal.serializer(), it.contentJson) }

    suspend fun proposalsBeforeRound(runId: String, round: Int): List<NoveltyVectorProposal> =
        dao.proposalsBeforeRound(runId, round)
            .map { decode(NoveltyVectorProposal.serializer(), it.contentJson) }

    /** Returns all novelty proposals for one run in round order. */
    suspend fun proposalsForRun(runId: String): List<NoveltyVectorProposal> =
        dao.proposalsForRun(runId)
            .map { decode(NoveltyVectorProposal.serializer(), it.contentJson) }

    suspend fun appendScreening(value: NoveltyScreeningResult): NoveltyScreeningResult {
        require(value.hasValidHash()) { "Novelty screening hash is invalid" }
        dao.screeningByProposalHash(value.proposalHash)?.let { existing ->
            val decoded = decode(NoveltyScreeningResult.serializer(), existing.contentJson)
            require(decoded == value) { "Novelty screening is immutable" }
            return decoded
        }
        val proposal = requireNotNull(proposalByHash(value.proposalHash)) {
            "Novelty screening references an unknown proposal"
        }
        dao.insertScreening(
            NoveltyScreeningResultEntity(
                screeningId = value.screeningId,
                proposalId = proposal.proposalId,
                proposalHash = value.proposalHash,
                status = value.status.name,
                screeningHash = value.screeningHash,
                contentJson = encode(NoveltyScreeningResult.serializer(), value),
                createdAtMs = now()
            )
        )
        return value
    }

    suspend fun appendAssessment(value: NoveltyGraderAssessment): NoveltyGraderAssessment {
        require(value.hasValidHash()) { "Novelty assessment hash is invalid" }
        dao.assessment(value.proposalHash, value.graderGroupId)?.let { existing ->
            val decoded = decode(NoveltyGraderAssessment.serializer(), existing.contentJson)
            require(decoded == value) {
                "Novelty assessment authority is immutable per grader group"
            }
            return decoded
        }
        dao.insertAssessment(
            NoveltyGraderAssessmentEntity(
                assessmentId = value.assessmentId,
                proposalHash = value.proposalHash,
                graderGroupId = value.graderGroupId,
                status = value.status.name,
                assessmentHash = value.assessmentHash,
                contentJson = encode(NoveltyGraderAssessment.serializer(), value),
                createdAtMs = now()
            )
        )
        return value
    }

    suspend fun assessmentsForProposal(proposalHash: String): List<NoveltyGraderAssessment> =
        dao.assessmentsForProposal(proposalHash)
            .map { decode(NoveltyGraderAssessment.serializer(), it.contentJson) }

    suspend fun screeningForProposal(proposalHash: String): NoveltyScreeningResult? =
        dao.screeningByProposalHash(proposalHash)
            ?.let { decode(NoveltyScreeningResult.serializer(), it.contentJson) }

    suspend fun appendVerdict(value: ShadowNodeGraderVerdict): ShadowNodeGraderVerdict {
        require(value.hasValidHash()) { "Shadow-node grader verdict hash is invalid" }
        dao.verdictByProposalHash(value.proposalHash)?.let { existing ->
            val decoded = decode(ShadowNodeGraderVerdict.serializer(), existing.contentJson)
            require(decoded == value) { "Shadow-node grader verdict is immutable" }
            return decoded
        }
        dao.insertVerdict(
            ShadowNodeGraderVerdictEntity(
                proposalHash = value.proposalHash,
                decision = value.decision.name,
                verdictHash = value.verdictHash,
                contentJson = encode(ShadowNodeGraderVerdict.serializer(), value),
                createdAtMs = now()
            )
        )
        return value
    }

    suspend fun verdictForProposal(proposalHash: String): ShadowNodeGraderVerdict? =
        dao.verdictByProposalHash(proposalHash)
            ?.let { decode(ShadowNodeGraderVerdict.serializer(), it.contentJson) }

    suspend fun appendLifecycle(value: ShadowNodeLifecycleEvent): ShadowNodeLifecycleEvent {
        dao.lifecycleEventById(value.eventId)?.let { existing ->
            val decoded = decode(ShadowNodeLifecycleEvent.serializer(), existing.contentJson)
            require(decoded == value) { "Shadow-node lifecycle event identity is immutable" }
            return decoded
        }
        val history = lifecycle(value.proposalId)
        ShadowNodeLifecycleValidator.validateNext(history.lastOrNull(), value)
        dao.insertLifecycleEvent(
            ShadowNodeLifecycleEventEntity(
                eventId = value.eventId,
                proposalId = value.proposalId,
                proposalHash = value.proposalHash,
                runId = value.runId,
                sequence = value.sequence,
                toState = value.toState.name,
                round = value.round,
                eventHash = value.eventHash,
                contentJson = encode(ShadowNodeLifecycleEvent.serializer(), value),
                createdAtMs = now()
            )
        )
        return value
    }

    /**
     * Atomically commits a tested non-amendment outcome and its delayed fitness.
     *
     * @param lifecycleEvents Ordered `TESTED` and terminal lifecycle events.
     * @param fitness Delayed fitness derived from the same downstream lineage.
     */
    suspend fun appendTestOutcome(
        lifecycleEvents: List<ShadowNodeLifecycleEvent>,
        fitness: OutlierFitnessRecord
    ) {
        require(
            lifecycleEvents.size == 2 &&
                lifecycleEvents.first().toState == ShadowNodeState.TESTED &&
                lifecycleEvents.last().toState in setOf(
                    ShadowNodeState.REJECTED,
                    ShadowNodeState.DEFERRED
                )
        ) {
            "Test outcome requires ordered TESTED and terminal lifecycle events"
        }
        val history = lifecycle(lifecycleEvents.first().proposalId)
        var previous = history.lastOrNull()
        lifecycleEvents.forEach { event ->
            ShadowNodeLifecycleValidator.validateNext(previous, event)
            previous = event
        }
        require(fitness.hasValidHash()) { "Outlier fitness hash is invalid" }
        val proposal = requireNotNull(proposalByHash(fitness.proposalHash)) {
            "Outlier fitness references an unknown proposal"
        }
        require(
            lifecycleEvents.all {
                it.proposalId == proposal.proposalId &&
                    it.proposalHash == proposal.proposalHash &&
                    it.runId == proposal.runId &&
                    it.round == fitness.observationRound
            } &&
                fitness.proposalId == proposal.proposalId &&
                fitness.agentId == proposal.proposerAgentId &&
                fitness.sourceRound == proposal.round
        ) {
            "Outlier test outcome does not match its proposal and fitness authority"
        }
        val lineageHashes = downstreamLineage(fitness.proposalId)
            .mapTo(linkedSetOf(), OutlierDownstreamLineage::lineageHash)
        require(fitness.evidenceLineageHashes.all(lineageHashes::contains)) {
            "Outlier fitness references unavailable downstream evidence"
        }
        val timestamp = now()
        dao.insertTestOutcome(
            expectedPreviousEventHash = requireNotNull(history.lastOrNull()).eventHash,
            lifecycleEvents = lifecycleEvents.map { it.toEntity(timestamp) },
            fitness = OutlierFitnessRecordEntity(
                recordId = fitness.recordId,
                proposalId = fitness.proposalId,
                proposalHash = fitness.proposalHash,
                agentId = fitness.agentId.value,
                observationRound = fitness.observationRound,
                recordHash = fitness.recordHash,
                contentJson = encode(OutlierFitnessRecord.serializer(), fitness),
                createdAtMs = timestamp
            )
        )
    }

    suspend fun lifecycle(proposalId: String): List<ShadowNodeLifecycleEvent> =
        dao.lifecycleForProposal(proposalId)
            .map { decode(ShadowNodeLifecycleEvent.serializer(), it.contentJson) }

    suspend fun appendDownstreamLineage(
        value: OutlierDownstreamLineage
    ): OutlierDownstreamLineage {
        require(value.hasValidHash()) { "Outlier downstream lineage hash is invalid" }
        dao.downstreamLineageById(value.lineageId)?.let { existing ->
            val decoded = decode(OutlierDownstreamLineage.serializer(), existing.contentJson)
            require(decoded == value) { "Outlier downstream lineage identity is immutable" }
            return decoded
        }
        dao.insertDownstreamLineage(
            OutlierDownstreamLineageEntity(
                lineageId = value.lineageId,
                proposalId = value.proposalId,
                proposalHash = value.proposalHash,
                observationRound = value.observationRound,
                outcome = value.outcome.name,
                lineageHash = value.lineageHash,
                contentJson = encode(OutlierDownstreamLineage.serializer(), value),
                createdAtMs = now()
            )
        )
        return value
    }

    suspend fun downstreamLineage(proposalId: String): List<OutlierDownstreamLineage> =
        dao.downstreamLineage(proposalId)
            .map { decode(OutlierDownstreamLineage.serializer(), it.contentJson) }

    suspend fun appendFitness(value: OutlierFitnessRecord): OutlierFitnessRecord {
        require(value.hasValidHash()) { "Outlier fitness hash is invalid" }
        dao.fitnessById(value.recordId)?.let { existing ->
            val decoded = decode(OutlierFitnessRecord.serializer(), existing.contentJson)
            require(decoded == value) { "Outlier fitness identity is immutable" }
            return decoded
        }
        val lineageHashes = downstreamLineage(value.proposalId)
            .mapTo(linkedSetOf(), OutlierDownstreamLineage::lineageHash)
        require(value.evidenceLineageHashes.all(lineageHashes::contains)) {
            "Outlier fitness references unavailable downstream evidence"
        }
        dao.insertFitness(
            OutlierFitnessRecordEntity(
                recordId = value.recordId,
                proposalId = value.proposalId,
                proposalHash = value.proposalHash,
                agentId = value.agentId.value,
                observationRound = value.observationRound,
                recordHash = value.recordHash,
                contentJson = encode(OutlierFitnessRecord.serializer(), value),
                createdAtMs = now()
            )
        )
        return value
    }

    suspend fun fitnessForProposal(proposalId: String): List<OutlierFitnessRecord> =
        dao.fitnessForProposal(proposalId)
            .map { decode(OutlierFitnessRecord.serializer(), it.contentJson) }

    private suspend fun proposalByHash(hash: String): NoveltyVectorProposal? =
        dao.proposalByHash(hash)
            ?.let { decode(NoveltyVectorProposal.serializer(), it.contentJson) }

    private fun NoveltyVectorProposal.toEntity(createdAtMs: Long) =
        NoveltyVectorProposalEntity(
            proposalId = proposalId,
            runId = runId,
            groupId = groupId.value,
            round = round,
            proposerAgentId = proposerAgentId.value,
            noveltyFingerprint = noveltyFingerprint,
            proposalHash = proposalHash,
            contentJson = encode(NoveltyVectorProposal.serializer(), this),
            createdAtMs = createdAtMs
        )

    private fun ShadowNodeLifecycleEvent.toEntity(createdAtMs: Long) =
        ShadowNodeLifecycleEventEntity(
            eventId = eventId,
            proposalId = proposalId,
            proposalHash = proposalHash,
            runId = runId,
            sequence = sequence,
            toState = toState.name,
            round = round,
            eventHash = eventHash,
            contentJson = encode(ShadowNodeLifecycleEvent.serializer(), this),
            createdAtMs = createdAtMs
        )

    private fun <T> encode(serializer: KSerializer<T>, value: T): String =
        json.encodeToString(serializer, value)

    private fun <T> decode(serializer: KSerializer<T>, value: String): T =
        json.decodeFromString(serializer, value)
}
