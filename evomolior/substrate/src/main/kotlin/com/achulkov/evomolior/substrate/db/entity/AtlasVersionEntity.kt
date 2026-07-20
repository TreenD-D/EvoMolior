package com.achulkov.evomolior.substrate.db.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * Immutable storage record for one sealed Node Atlas version.
 *
 * Atlas versions are append-only. A correction or structural change creates another row whose
 * [parentAtlasHash] points at the previous sealed version.
 */
@Entity(
    tableName = "atlas_versions",
    primaryKeys = ["atlasId", "version"],
    indices = [
        Index(value = ["atlasHash"], unique = true),
        Index(value = ["runId", "version"], unique = true),
        Index("parentAtlasHash")
    ]
)
data class AtlasVersionEntity(
    val atlasId: String,
    val runId: String,
    val version: Int,
    val receiverContractHash: String,
    val receiverContractJson: String,
    val atlasHash: String,
    val parentAtlasHash: String?,
    val sealedAtlasJson: String,
    val createdAtMs: Long
)
