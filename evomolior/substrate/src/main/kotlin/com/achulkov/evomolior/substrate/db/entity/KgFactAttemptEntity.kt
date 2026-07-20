package com.achulkov.evomolior.substrate.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Exact immutable persistence row for one attempted `KG_FACT` classification. */
@Entity(
    tableName = "kg_fact_attempts",
    indices = [
        Index(value = ["runId", "groupId", "submissionId"], unique = true),
        Index(value = ["runId", "groupId", "generation", "recordedAtMs"]),
        Index(value = ["recordHash"], unique = true)
    ]
)
data class KgFactAttemptEntity(
    @PrimaryKey
    val attemptId: String,
    val runId: String,
    val groupId: String,
    val submissionId: String,
    val agentId: String,
    val generation: Int,
    val submissionContentHash: String,
    val status: String,
    val requestedRelation: String?,
    val resolvedRelation: String?,
    val failureCode: String?,
    val recordedAtMs: Long,
    val recordHash: String,
    val contentJson: String
)
