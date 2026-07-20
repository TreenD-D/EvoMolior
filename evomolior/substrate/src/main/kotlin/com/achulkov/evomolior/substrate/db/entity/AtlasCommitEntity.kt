package com.achulkov.evomolior.substrate.db.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * Append-only transaction boundary for Atlas and execution-state changes.
 *
 * Projection rows carrying the same [commitSequence] were derived in the same database
 * transaction and therefore cannot describe a partially committed authority state.
 */
@Entity(
    tableName = "atlas_commits",
    indices = [
        Index(value = ["atlasId", "commitSequence"], unique = true),
        Index(value = ["runId", "commitSequence"], unique = true),
        Index("atlasHash")
    ]
)
data class AtlasCommitEntity(
    @androidx.room.PrimaryKey
    val commitId: String,
    val atlasId: String,
    val runId: String,
    val commitSequence: Long,
    val atlasVersion: Int,
    val atlasHash: String,
    val mutationType: String,
    val createdAtMs: Long
)
