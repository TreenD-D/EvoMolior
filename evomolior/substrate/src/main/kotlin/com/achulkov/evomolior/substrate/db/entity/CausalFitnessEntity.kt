package com.achulkov.evomolior.substrate.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Immutable plane-separated causal fitness observation. */
@Entity(
    tableName = "causal_fitness_records",
    indices = [
        Index(value = ["runId", "groupId", "round", "plane"]),
        Index(value = ["runId", "groupId", "agentId", "round"], unique = true),
        Index(value = ["recordHash"], unique = true)
    ]
)
data class CausalFitnessRecordEntity(
    @PrimaryKey
    val recordId: String,
    val runId: String,
    val groupId: String,
    val round: Int,
    val agentId: String,
    val role: String,
    val plane: String,
    val total: Double,
    val legacyEligible: Boolean,
    val recordHash: String,
    val contentJson: String,
    val createdAtMs: Long
)
