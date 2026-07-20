package com.achulkov.evomolior.substrate.db.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * Immutable projection generated from one committed Atlas authority state.
 *
 * Consumers select the highest commit for their projection type and scope. Historical rows are
 * retained so a projection can be compared with the exact Atlas and execution state that made it.
 */
@Entity(
    tableName = "atlas_projections",
    indices = [
        Index(
            value = ["atlasId", "commitSequence", "projectionType", "scopeId"],
            unique = true
        ),
        Index(value = ["runId", "projectionType", "scopeId", "commitSequence"]),
        Index("sourceAtlasHash")
    ]
)
data class AtlasProjectionEntity(
    @androidx.room.PrimaryKey
    val projectionId: String,
    val atlasId: String,
    val runId: String,
    val atlasVersion: Int,
    val commitSequence: Long,
    val projectionType: String,
    val scopeId: String,
    val payloadJson: String,
    val sourceAtlasHash: String,
    val sourceStateHash: String,
    val createdAtMs: Long
)
