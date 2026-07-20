package com.achulkov.evomolior.substrate.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Room entity for a Legacy Protocol extracted from top-20% agents. */
@Entity(
    tableName = "legacy_protocols",
    indices = [
        Index("groupId"),
        Index("runId"),
        Index("role"),
        Index("expiresAtGeneration")
    ]
)
data class LegacyProtocolEntity(
    @PrimaryKey val id: String,
    /** Role enum name. */
    val role: String,
    val extractedFromGeneration: Int,
    val expiresAtGeneration: Int,
    val reasoningPosture: String,
    val toolHeuristicsJson: String,
    val failureMapJson: String,
    val kgHabitsJson: String,
    /** Comma-separated source agent IDs whose protocols were merged. */
    val sourceAgentIds: String,
    val groupId: String,
    val runId: String,
    val createdAtMs: Long
)
