@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.substrate.publication

import com.achulkov.evomolior.core.contract.PublicationStateEvent
import com.achulkov.evomolior.core.contract.PublicationStatePolicy
import com.achulkov.evomolior.core.contract.FinalCandidateRecord
import com.achulkov.evomolior.substrate.db.EvoMoliorDatabase
import com.achulkov.evomolior.substrate.db.entity.PublicationStateEventEntity
import com.achulkov.evomolior.substrate.db.entity.FinalCandidateRecordEntity
import kotlinx.serialization.json.Json

/** Validates and appends the exact publication state machine for one run-global database. */
class PublicationStateRepository(
    database: EvoMoliorDatabase,
    private val json: Json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    }
) {
    private val dao = database.publicationStateDao()

    /** Persists one complete immutable candidate set before the first publication transition. */
    suspend fun sealFinalCandidates(
        records: List<FinalCandidateRecord>
    ): List<FinalCandidateRecord> {
        require(records.isNotEmpty()) { "Publication requires at least one final candidate" }
        require(records.map { it.runId }.distinct().size == 1) {
            "Final candidates must belong to one run"
        }
        require(records.map { it.groupId }.distinct().size == records.size) {
            "Final candidates must contain at most one record per group"
        }
        require(records.all(FinalCandidateRecord::hasValidHash)) {
            "Final-candidate hash does not match its content"
        }
        val existing = dao.finalCandidates(records.first().runId).map { it.toContract() }
        if (existing.isNotEmpty()) {
            require(existing == records.sortedBy { it.groupId }) {
                "Final-candidate set is immutable once sealed"
            }
            return existing
        }
        dao.insertFinalCandidates(records.sortedBy { it.groupId }.map { it.toEntity() })
        return records.sortedBy { it.groupId }
    }

    /** Returns the exact sealed final-candidate set for one run. */
    suspend fun finalCandidates(runId: String): List<FinalCandidateRecord> =
        dao.finalCandidates(runId).map { it.toContract() }

    /** Appends one strict hash-linked publication transition. */
    suspend fun append(event: PublicationStateEvent): PublicationStateEvent {
        require(event.hasValidHash()) { "Publication event hash does not match its content" }
        val previous = latest(event.runId)
        require(PublicationStatePolicy.allows(previous, event)) {
            "Illegal publication transition ${previous?.state} -> ${event.state}"
        }
        dao.append(event.toEntity(), previous?.eventHash)
        return event
    }

    /** Returns the latest exact publication transition for [runId]. */
    suspend fun latest(runId: String): PublicationStateEvent? =
        dao.latest(runId)?.toContract()

    /** Returns the complete ordered publication transition history for [runId]. */
    suspend fun history(runId: String): List<PublicationStateEvent> =
        dao.history(runId).map { it.toContract() }

    private fun PublicationStateEvent.toEntity() = PublicationStateEventEntity(
        eventId = eventId,
        runId = runId,
        state = state.name,
        sequence = sequence,
        previousEventHash = previousEventHash,
        eventHash = eventHash,
        contentJson = json.encodeToString(PublicationStateEvent.serializer(), this),
        occurredAtMs = occurredAt.toEpochMilliseconds()
    )

    private fun PublicationStateEventEntity.toContract(): PublicationStateEvent =
        json.decodeFromString(PublicationStateEvent.serializer(), contentJson)

    private fun FinalCandidateRecord.toEntity() = FinalCandidateRecordEntity(
        candidateId = candidateId,
        runId = runId,
        groupId = groupId,
        round = round,
        outputContractHash = outputContractHash,
        recordHash = recordHash,
        contentJson = json.encodeToString(FinalCandidateRecord.serializer(), this),
        sealedAtMs = sealedAt.toEpochMilliseconds()
    )

    private fun FinalCandidateRecordEntity.toContract(): FinalCandidateRecord =
        json.decodeFromString(FinalCandidateRecord.serializer(), contentJson).also {
            require(it.hasValidHash() && it.recordHash == recordHash) {
                "Persisted final-candidate authority hash does not match"
            }
        }
}
