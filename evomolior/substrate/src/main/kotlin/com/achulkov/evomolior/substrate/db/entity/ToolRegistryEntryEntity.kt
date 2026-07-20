package com.achulkov.evomolior.substrate.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for a Tool Registry entry.
 *
 * Tools are versioned — updates create a new row; the prior row is archived.
 * Success rate is maintained as a running average updated by the Lifecycle Manager
 * as agents execute the tool.
 */
@Entity(
    tableName = "tool_registry_entries",
    indices = [
        Index("groupId"),
        Index("runId"),
        Index("name"),
        Index("domain"),
        Index("status"),
        Index(value = ["runId", "groupId", "name", "status"])
    ]
)
data class ToolRegistryEntryEntity(
    @PrimaryKey val id: String,
    val name: String,
    val version: Int = 1,
    val authorAgentId: String,
    val successRate: Double = 1.0,
    /** JSON array of last N failure descriptions. */
    val failureLogJson: String = "[]",
    /** JSON schema for tool inputs. */
    val inputSchemaJson: String,
    /** JSON schema for tool outputs. */
    val outputSchemaJson: String,
    val domain: String,
    /** "active" | "deprecated" | "archived" */
    val status: String = "active",
    /** The tool implementation (Kotlin script, shell, or Python). */
    val code: String,
    /** The language/runtime of [code]: "kotlin", "shell", "python". */
    val codeLanguage: String = "shell",
    val generation: Int,
    val groupId: String,
    val runId: String,
    val createdAtMs: Long,
    val previousVersionId: String? = null
)
