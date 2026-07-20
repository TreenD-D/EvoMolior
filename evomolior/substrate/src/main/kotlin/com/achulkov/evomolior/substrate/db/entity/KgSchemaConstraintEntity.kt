package com.achulkov.evomolior.substrate.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity storing a promoted KG constraint artifact.
 *
 * The live schema still lives in [KGSchemaEntity]; this table preserves the promoted payload
 * so the Critic pipeline can reconstruct schema changes and audits can inspect provenance.
 */
@Entity(
    tableName = "kg_schema_constraints",
    indices = [
        Index(value = ["runId", "groupId"]),
        Index(value = ["runId", "groupId", "createdAtMs"])
    ]
)
data class KgSchemaConstraintEntity(
    @PrimaryKey val id: String,
    val constraintJson: String,
    val source: String,
    val runId: String,
    val groupId: String,
    val createdAtMs: Long
)
