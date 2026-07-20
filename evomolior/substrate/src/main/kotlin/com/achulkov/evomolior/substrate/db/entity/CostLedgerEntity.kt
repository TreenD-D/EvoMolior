package com.achulkov.evomolior.substrate.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Append-only reservation, finalization, or release event in the provider/tool cost ledger. */
@Entity(
    tableName = "cost_ledger_entries",
    indices = [
        Index(value = ["runId", "occurredAtMs"]),
        Index(value = ["runId", "groupId", "agentId"]),
        Index(value = ["reservationId", "kind"], unique = true),
        Index(value = ["subjectType", "subjectId"]),
        Index(value = ["entryHash"], unique = true)
    ]
)
data class CostLedgerEntryEntity(
    @PrimaryKey
    val entryId: String,
    val reservationId: String,
    val runId: String,
    val groupId: String?,
    val agentId: String?,
    val subjectType: String,
    val subjectId: String,
    val kind: String,
    val modelProviderId: String?,
    val modelId: String?,
    val inputTokens: Long?,
    val outputTokens: Long?,
    val providerCostStatus: String,
    val providerCostUsd: Double?,
    val toolCostStatus: String,
    val toolCostUsd: Double?,
    val knownTotalUsd: Double?,
    val toolComputeMillis: Long?,
    val wallTimeMillis: Long?,
    val reservedMaximumUsd: Double?,
    val entryHash: String,
    val contentJson: String,
    val occurredAtMs: Long
)
