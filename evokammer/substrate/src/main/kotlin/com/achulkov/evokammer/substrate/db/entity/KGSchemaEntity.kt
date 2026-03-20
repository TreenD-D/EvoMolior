package com.achulkov.evokammer.substrate.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for the Knowledge Graph schema.
 *
 * Stores the typed schema for a run's KG, as designed by the [CouncilKGArchitect].
 * The schema defines:
 * - Entity types (e.g., "Concept", "Algorithm", "Organisation", "Claim")
 * - Relation types (e.g., "CAUSES", "IMPLEMENTS", "CONTRADICTS", "SUPPORTS")
 * - Hard constraints (violations cause REJECT)
 * - Soft constraints (violations reduce trust score)
 *
 * One schema row per (runId, groupId) — groups may diverge on soft constraints
 * but share the same hard constraint set from the Genesis Council.
 *
 * All columns are stored as JSON for flexibility.
 */
@Entity(
    tableName = "kg_schemas",
    indices = [
        Index(value = ["runId", "groupId"], unique = true)
    ]
)
data class KGSchemaEntity(
    @PrimaryKey val id: String,
    val runId: String,
    val groupId: String,
    /** JSON array of entity type strings. */
    val entityTypesJson: String = "[]",
    /** JSON array of relation type strings. */
    val relationTypesJson: String = "[]",
    /**
     * JSON array of hard constraint objects:
     * { "id": "HC-001", "description": "...", "expression": "..." }
     */
    val hardConstraintsJson: String = "[]",
    /**
     * JSON array of soft constraint objects:
     * { "id": "SC-001", "description": "...", "penaltyFactor": 0.2 }
     */
    val softConstraintsJson: String = "[]",
    val createdAtMs: Long,
    val schemaVersion: Int = 1
)
