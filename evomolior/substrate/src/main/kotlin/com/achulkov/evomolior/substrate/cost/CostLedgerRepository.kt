@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.substrate.cost

import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.contract.CostLedgerEntry
import com.achulkov.evomolior.core.contract.CostLedgerEntryKind
import com.achulkov.evomolior.core.contract.CostLedgerPolicy
import com.achulkov.evomolior.core.contract.CostLedgerSummary
import com.achulkov.evomolior.substrate.db.EvoMoliorDatabase
import com.achulkov.evomolior.substrate.db.entity.CostLedgerEntryEntity
import kotlinx.serialization.json.Json

/** Validates provider/tool cost events and enforces an optional cumulative safety cap atomically. */
class CostLedgerRepository(
    private val database: EvoMoliorDatabase,
    private val policy: CostLedgerPolicy = CostLedgerPolicy(),
    private val json: Json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    }
) {
    private val dao = database.costLedgerDao()

    /** Reserves bounded provider/tool cost without spending product action credits. */
    suspend fun reserve(entry: CostLedgerEntry): CostLedgerEntry {
        require(entry.kind == CostLedgerEntryKind.RESERVED) {
            "Cost reserve requires a RESERVED entry"
        }
        validate(entry)
        check(dao.reserve(entry.toEntity(), policy.cumulativeCapUsd)) {
            "Cost reservation was duplicated or exceeded the cumulative safety cap"
        }
        return entry
    }

    /** Finalizes exact usage; unknown monetary cost fails closed when a cap is configured. */
    suspend fun finalize(entry: CostLedgerEntry): CostLedgerEntry {
        require(entry.kind == CostLedgerEntryKind.FINALIZED) {
            "Cost finalize requires a FINALIZED entry"
        }
        validate(entry)
        check(dao.finalize(entry.toEntity(), policy.cumulativeCapUsd)) {
            "Cost finalization was invalid, duplicated, unknown under cap, or exceeded the cap"
        }
        return entry
    }

    /** Releases an unused reservation without inventing zero usage. */
    suspend fun release(entry: CostLedgerEntry): CostLedgerEntry {
        require(entry.kind == CostLedgerEntryKind.RELEASED) {
            "Cost release requires a RELEASED entry"
        }
        validate(entry)
        check(dao.release(entry.toEntity())) {
            "Cost release was invalid or duplicated"
        }
        return entry
    }

    /** Returns one exact immutable ledger event. */
    suspend fun byId(entryId: String): CostLedgerEntry? =
        dao.byId(entryId)?.let(::decode)

    /** Returns every exact ledger event for one run in occurrence order. */
    suspend fun entriesForRun(runId: String): List<CostLedgerEntry> =
        dao.entriesForRun(runId).map(::decode)

    /** Returns a read-only run aggregate preserving the unknown-price count. */
    suspend fun summary(runId: String): CostLedgerSummary {
        val aggregate = dao.aggregate(runId)
        return CostLedgerSummary(
            runId = runId,
            finalizedEntryCount = aggregate.finalizedEntryCount,
            unknownMonetaryEntryCount = aggregate.unknownMonetaryEntryCount,
            inputTokens = aggregate.inputTokens,
            outputTokens = aggregate.outputTokens,
            knownProviderCostUsd = aggregate.knownProviderCostUsd,
            knownToolCostUsd = aggregate.knownToolCostUsd,
            toolComputeMillis = aggregate.toolComputeMillis,
            wallTimeMillis = aggregate.wallTimeMillis,
            activeReservedMaximumUsd = dao.activeReservedUsd(runId, null),
            activeReservationCount = dao.activeReservationCount(runId)
        )
    }

    private fun validate(entry: CostLedgerEntry) {
        require(entry.hasValidHash()) { "Cost ledger entry hash is invalid" }
    }

    private fun CostLedgerEntry.toEntity() = CostLedgerEntryEntity(
        entryId = entryId,
        reservationId = reservationId,
        runId = runId,
        groupId = groupId?.value,
        agentId = agentId,
        subjectType = subjectType.name,
        subjectId = subjectId,
        kind = kind.name,
        modelProviderId = modelProviderId,
        modelId = modelId,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        providerCostStatus = providerCost.status.name,
        providerCostUsd = providerCost.usd,
        toolCostStatus = toolCost.status.name,
        toolCostUsd = toolCost.usd,
        knownTotalUsd = knownTotalUsd(),
        toolComputeMillis = toolComputeMillis,
        wallTimeMillis = wallTimeMillis,
        reservedMaximumUsd = reservedMaximumUsd,
        entryHash = entryHash,
        contentJson = json.encodeToString(CostLedgerEntry.serializer(), this),
        occurredAtMs = occurredAt.toEpochMilliseconds()
    )

    private fun decode(entity: CostLedgerEntryEntity): CostLedgerEntry =
        json.decodeFromString(CostLedgerEntry.serializer(), entity.contentJson).also {
            require(AtlasHashing.constantTimeEquals(it.entryHash, entity.entryHash)) {
                "Persisted cost ledger payload hash does not match indexed authority"
            }
        }
}
