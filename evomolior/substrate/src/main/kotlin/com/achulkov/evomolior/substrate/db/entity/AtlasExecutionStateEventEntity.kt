package com.achulkov.evomolior.substrate.db.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * Immutable execution-state snapshot appended for one Atlas node.
 *
 * Each event contains the complete post-mutation state. This permits efficient current-state
 * reads while retaining enough information to reconstruct every prior ownership, grade, lease,
 * capture, and disclosure transition.
 */
@Entity(
    tableName = "atlas_execution_state_events",
    indices = [
        Index(value = ["atlasId", "nodeId", "stateRevision"], unique = true),
        Index(value = ["atlasId", "commitSequence"]),
        Index(value = ["runId", "nodeId"]),
        Index("stateHash")
    ]
)
data class AtlasExecutionStateEventEntity(
    @androidx.room.PrimaryKey
    val eventId: String,
    val atlasId: String,
    val runId: String,
    val nodeId: String,
    val stateRevision: Long,
    val commitSequence: Long,
    val previousStateHash: String?,
    val stateHash: String,
    val stateJson: String,
    val mutationType: String,
    val createdAtMs: Long
)
