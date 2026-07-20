package com.achulkov.evomolior.substrate.db.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * Immutable amendment linking two sealed Atlas versions.
 */
@Entity(
    tableName = "atlas_amendments",
    indices = [
        Index(value = ["atlasId", "toVersion"], unique = true),
        Index(value = ["resultAtlasHash"], unique = true),
        Index("parentAtlasHash")
    ]
)
data class AtlasAmendmentEntity(
    @androidx.room.PrimaryKey
    val amendmentId: String,
    val atlasId: String,
    val runId: String,
    val fromVersion: Int,
    val toVersion: Int,
    val parentAtlasHash: String,
    val resultAtlasHash: String,
    val amendmentJson: String,
    val createdAtMs: Long
)
