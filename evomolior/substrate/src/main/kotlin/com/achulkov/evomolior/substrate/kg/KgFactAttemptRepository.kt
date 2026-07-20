@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.substrate.kg

import com.achulkov.evomolior.core.kg.KgFactAttemptRecord
import com.achulkov.evomolior.substrate.db.EvoMoliorDatabase
import com.achulkov.evomolior.substrate.db.entity.KgFactAttemptEntity
import kotlinx.serialization.json.Json

/** Append-only persistence authority for typed KG fact-attempt records. */
class KgFactAttemptRepository(
    database: EvoMoliorDatabase,
    private val json: Json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    }
) {
    private val dao = database.kgFactAttemptDao()

    /** Appends [record] idempotently and rejects mutation of an existing submission identity. */
    suspend fun append(record: KgFactAttemptRecord): KgFactAttemptRecord {
        require(record.recordHash.isNotBlank()) { "KG fact attempt recordHash must be present" }
        require(
            record.recordHash ==
                record.withComputedHash().recordHash
        ) {
            "KG fact attempt recordHash does not match its exact content"
        }
        existing(record.runId, record.groupId, record.submissionId)?.let { persisted ->
            require(persisted == record) { "KG fact attempt identity is immutable" }
            return persisted
        }
        dao.insert(record.toEntity())
        val persisted = requireNotNull(existing(record.runId, record.groupId, record.submissionId)) {
            "KG fact attempt append did not produce a persisted authority record"
        }
        require(persisted == record) { "KG fact attempt identity is immutable" }
        return persisted
    }

    /** Returns one attempt by its staging submission identity. */
    suspend fun bySubmission(
        runId: String,
        groupId: String,
        submissionId: String
    ): KgFactAttemptRecord? = existing(runId, groupId, submissionId)

    /** Returns all attempts for one group run in deterministic generation/time/identity order. */
    suspend fun forGroup(runId: String, groupId: String): List<KgFactAttemptRecord> =
        dao.forGroup(runId, groupId).map(::decode)

    private suspend fun existing(
        runId: String,
        groupId: String,
        submissionId: String
    ): KgFactAttemptRecord? = dao.bySubmission(runId, groupId, submissionId)?.let(::decode)

    private fun KgFactAttemptRecord.toEntity(): KgFactAttemptEntity = KgFactAttemptEntity(
        attemptId = attemptId,
        runId = runId,
        groupId = groupId,
        submissionId = submissionId,
        agentId = agentId,
        generation = generation,
        submissionContentHash = submissionContentHash,
        status = status.name,
        requestedRelation = requestedRelation,
        resolvedRelation = resolvedRelation,
        failureCode = failureCode?.name,
        recordedAtMs = recordedAt.toEpochMilliseconds(),
        recordHash = recordHash,
        contentJson = json.encodeToString(KgFactAttemptRecord.serializer(), this)
    )

    private fun decode(entity: KgFactAttemptEntity): KgFactAttemptRecord =
        json.decodeFromString(KgFactAttemptRecord.serializer(), entity.contentJson)
}
