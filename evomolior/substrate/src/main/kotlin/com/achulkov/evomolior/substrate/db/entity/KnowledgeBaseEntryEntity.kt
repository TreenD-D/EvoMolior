package com.achulkov.evomolior.substrate.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for a Knowledge Base document.
 *
 * Canonical KB entries are immutable after promotion — updates create a new row with
 * incremented [version]; the prior row is set to [status] = "archived".
 *
 * Dead End entries ([isDeadEnd] = true) are exempt from trust decay and are always
 * returned in queries regardless of trust score.
 */
@Entity(
    tableName = "knowledge_base_entries",
    indices = [
        Index("groupId"),
        Index("runId"),
        Index("domain"),
        Index("status"),
        Index("generation"),
        Index(value = ["runId", "groupId", "status"])
    ]
)
data class KnowledgeBaseEntryEntity(
    @PrimaryKey val id: String,
    val version: Int = 1,
    val content: String,
    /** Vector embedding stored as raw bytes; nullable until embedding is computed. */
    val embedding: ByteArray? = null,
    val sourceAgentId: String,
    val generation: Int,
    val trustScore: Double = 0.7,
    val citationCount: Int = 0,
    val domain: String,
    /** "active" | "archived" | "dead_end" */
    val status: String = "active",
    val groupId: String,
    val runId: String,
    val createdAtMs: Long,
    /** If true, this entry is in the Dead Ends registry — never decayed, always visible. */
    val isDeadEnd: Boolean = false,
    /** Reference to the prior version's ID; null for the first version. */
    val previousVersionId: String? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is KnowledgeBaseEntryEntity) return false
        if (id != other.id) return false
        return true
    }

    override fun hashCode(): Int = id.hashCode()
}
