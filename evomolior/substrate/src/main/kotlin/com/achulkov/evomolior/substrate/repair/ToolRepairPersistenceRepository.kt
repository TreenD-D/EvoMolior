package com.achulkov.evomolior.substrate.repair

import com.achulkov.evomolior.core.repair.ToolRepairAttemptRecord
import com.achulkov.evomolior.core.repair.ToolRepairDiagnosticV1
import com.achulkov.evomolior.substrate.db.EvoMoliorDatabase
import com.achulkov.evomolior.substrate.db.entity.ToolRepairAttemptEntity
import com.achulkov.evomolior.substrate.db.entity.ToolRepairDiagnosticEntity
import kotlinx.serialization.json.Json

/** Durable append-only authority for typed tool-repair diagnostics and bounded attempts. */
class ToolRepairPersistenceRepository(
    database: EvoMoliorDatabase,
    private val json: Json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    }
) {
    private val dao = database.completionPersistenceDao()

    /**
     * Appends [diagnostic] idempotently and rejects any mutation of its stable identity.
     *
     * @param diagnostic Exact hash-valid diagnostic.
     * @return Exact persisted diagnostic.
     */
    suspend fun appendDiagnostic(
        diagnostic: ToolRepairDiagnosticV1
    ): ToolRepairDiagnosticV1 {
        require(diagnostic.hasValidHash()) {
            "Tool-repair diagnostic hash does not match its exact content"
        }
        diagnostic(diagnostic.diagnosticId)?.let { existing ->
            require(existing == diagnostic) { "Tool-repair diagnostic identity is immutable" }
            return existing
        }
        dao.insertDiagnostic(diagnostic.toEntity())
        return requireNotNull(diagnostic(diagnostic.diagnosticId)) {
            "Tool-repair diagnostic append conflicted with another immutable authority"
        }.also { persisted ->
            require(persisted == diagnostic) { "Tool-repair diagnostic identity is immutable" }
        }
    }

    /** Returns one exact diagnostic by [diagnosticId]. */
    suspend fun diagnostic(diagnosticId: String): ToolRepairDiagnosticV1? =
        dao.diagnostic(diagnosticId)?.toDiagnostic()

    /**
     * Returns diagnostics visible to [opportunityId] in [round], in deterministic order.
     *
     * @param runId Owning run.
     * @param groupId Owning group.
     * @param opportunityId Sealed opportunity.
     * @param round Current repair round.
     * @return Exact visible diagnostics.
     */
    suspend fun visibleDiagnostics(
        runId: String,
        groupId: String,
        opportunityId: String,
        round: Int
    ): List<ToolRepairDiagnosticV1> =
        dao.visibleDiagnostics(runId, groupId, opportunityId, round)
            .map { it.toDiagnostic() }

    /**
     * Appends [attempt] idempotently and rejects any mutation of its stable identity.
     *
     * @param attempt Exact hash-valid bounded attempt.
     * @return Exact persisted attempt.
     */
    suspend fun appendAttempt(attempt: ToolRepairAttemptRecord): ToolRepairAttemptRecord {
        require(attempt.hasValidHash()) {
            "Tool-repair attempt hash does not match its exact content"
        }
        attempt(attempt.attemptId)?.let { existing ->
            require(existing == attempt) { "Tool-repair attempt identity is immutable" }
            return existing
        }
        return dao.appendAttemptBounded(attempt.toEntity(), MAX_ATTEMPTS_PER_OPPORTUNITY)
            .toAttempt()
            .also { persisted ->
                require(persisted == attempt) { "Tool-repair attempt identity is immutable" }
            }
    }

    /** Returns one exact repair attempt by [attemptId]. */
    suspend fun attempt(attemptId: String): ToolRepairAttemptRecord? =
        dao.attempt(attemptId)?.toAttempt()

    /** Returns the exact ordered attempt lineage for [opportunityId]. */
    suspend fun attempts(opportunityId: String): List<ToolRepairAttemptRecord> =
        dao.attempts(opportunityId).map { it.toAttempt() }

    private fun ToolRepairDiagnosticV1.toEntity() = ToolRepairDiagnosticEntity(
        diagnosticId = diagnosticId,
        runId = runId,
        groupId = groupId,
        nodeId = nodeId,
        opportunityId = opportunityId,
        candidateHash = candidateHash,
        source = source.name,
        disclosureClass = disclosureClass.name,
        code = code.name,
        createdRound = createdRound,
        visibleFromRound = visibleFromRound,
        diagnosticHash = diagnosticHash,
        contentJson = json.encodeToString(ToolRepairDiagnosticV1.serializer(), this)
    )

    private fun ToolRepairDiagnosticEntity.toDiagnostic(): ToolRepairDiagnosticV1 =
        json.decodeFromString(ToolRepairDiagnosticV1.serializer(), contentJson).also { value ->
            require(
                value.hasValidHash() &&
                    value.diagnosticId == diagnosticId &&
                    value.runId == runId &&
                    value.groupId == groupId &&
                    value.nodeId == nodeId &&
                    value.opportunityId == opportunityId &&
                    value.candidateHash == candidateHash &&
                    value.source.name == source &&
                    value.disclosureClass.name == disclosureClass &&
                    value.code.name == code &&
                    value.createdRound == createdRound &&
                    value.visibleFromRound == visibleFromRound &&
                    value.diagnosticHash == diagnosticHash
            ) { "Persisted tool-repair diagnostic metadata does not match its exact content" }
        }

    private fun ToolRepairAttemptRecord.toEntity() = ToolRepairAttemptEntity(
        attemptId = attemptId,
        opportunityId = opportunityId,
        candidateHash = candidateHash,
        authorAgentId = authorAgentId,
        round = round,
        preflightResult = preflightResult.name,
        criticResult = criticResult?.name,
        promotionResult = promotionResult.name,
        attemptHash = attemptHash,
        contentJson = json.encodeToString(ToolRepairAttemptRecord.serializer(), this)
    )

    private fun ToolRepairAttemptEntity.toAttempt(): ToolRepairAttemptRecord =
        json.decodeFromString(ToolRepairAttemptRecord.serializer(), contentJson).also { value ->
            require(
                value.hasValidHash() &&
                    value.attemptId == attemptId &&
                    value.opportunityId == opportunityId &&
                    value.candidateHash == candidateHash &&
                    value.authorAgentId == authorAgentId &&
                    value.round == round &&
                    value.preflightResult.name == preflightResult &&
                    value.criticResult?.name == criticResult &&
                    value.promotionResult.name == promotionResult &&
                    value.attemptHash == attemptHash
            ) { "Persisted tool-repair attempt metadata does not match its exact content" }
        }

    private companion object {
        const val MAX_ATTEMPTS_PER_OPPORTUNITY: Int = 3
    }
}
