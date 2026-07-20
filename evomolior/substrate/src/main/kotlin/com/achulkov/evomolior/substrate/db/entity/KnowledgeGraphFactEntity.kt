package com.achulkov.evomolior.substrate.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for a Knowledge Graph fact (typed triple).
 *
 * Facts are validated by the KG Consistency Checker before Critic review.
 * Hard constraint violations are always rejected; soft constraint violations reduce trust score.
 *
 * Speculative facts (from Outlier agents) are stored separately and require higher evidence
 * before being elevated to standard trust scores.
 *
 * Note: the triple's object is stored as [objectNode] to avoid Kotlin reserved word `object`,
 * but the DB column is named `object` for SQL clarity.
 */
@Entity(
    tableName = "knowledge_graph_facts",
    indices = [
        Index("groupId"),
        Index("runId"),
        Index("subject"),
        Index("relation"),
        Index("status"),
        Index("generation"),
        Index(value = ["runId", "groupId", "status"])
    ]
)
data class KnowledgeGraphFactEntity(
    @PrimaryKey val id: String,
    val subject: String,
    val relation: String,
    /** The triple's object node. Column is named `object` in the DB. */
    @ColumnInfo(name = "object") val objectNode: String,
    val confidence: Double,
    val sourceAgentId: String,
    val generation: Int,
    val trustScore: Double = 0.7,
    /** JSON array of source evidence references (KB entry IDs, external URLs). */
    val provenanceJson: String = "[]",
    /** "active" | "disputed" | "rejected" | "archived" */
    val status: String = "active",
    /** True if submitted by an Outlier agent. */
    val speculative: Boolean = false,
    val groupId: String,
    val runId: String,
    val createdAtMs: Long
)
