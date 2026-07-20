package com.achulkov.evomolior.substrate.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Immutable hash-linked publication state transition. */
@Entity(
    tableName = "publication_state_events",
    indices = [
        Index(value = ["runId", "sequence"], unique = true),
        Index(value = ["runId", "state"], unique = true),
        Index(value = ["eventHash"], unique = true)
    ]
)
data class PublicationStateEventEntity(
    @PrimaryKey
    val eventId: String,
    val runId: String,
    val state: String,
    val sequence: Int,
    val previousEventHash: String?,
    val eventHash: String,
    val contentJson: String,
    val occurredAtMs: Long
)

/** Immutable final candidate sealed before winner or deterministic policy resolution. */
@Entity(
    tableName = "final_candidate_records",
    indices = [
        Index(value = ["runId", "groupId"], unique = true),
        Index(value = ["runId", "round"]),
        Index(value = ["recordHash"], unique = true)
    ]
)
data class FinalCandidateRecordEntity(
    @PrimaryKey
    val candidateId: String,
    val runId: String,
    val groupId: String,
    val round: Int,
    val outputContractHash: String,
    val recordHash: String,
    val contentJson: String,
    val sealedAtMs: Long
)
