package com.achulkov.evomolior.substrate.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for trust score tracking of content sources.
 *
 * Trust scores are maintained for:
 * - KB documents (sourceType = "kb_entry")
 * - Dispatches from other groups (sourceType = "dispatch")
 * - Staging submissions (sourceType = "staging")
 * - Individual agents (sourceType = "agent")
 *
 * Scores decay over time via TRUST_DECAY events.
 */
@Entity(
    tableName = "trust_scores",
    indices = [
        Index("runId"),
        Index("groupId"),
        Index("sourceType"),
        Index(value = ["runId", "groupId", "sourceId"])
    ]
)
data class TrustScoreEntity(
    @PrimaryKey val id: String,          // UUID
    val sourceId: String,                // ID of the entity being scored
    val sourceType: String,              // "kb_entry" | "dispatch" | "agent" | "staging"
    val runId: String,
    val groupId: String,
    val score: Double,                   // 0.0 – 1.0
    val lastUpdatedMs: Long,
    val decayCount: Int = 0,             // How many decay events have hit this entry
    val promotionCount: Int = 0,         // Successful promotions that boosted this score
    val rejectionCount: Int = 0          // Rejections that degraded this score
)
