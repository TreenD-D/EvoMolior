package com.achulkov.evomolior.substrate.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for the Dead Ends Registry.
 *
 * Dead Ends are documented failure modes: approaches that were tried and found to be
 * dead ends, either by agent self-report (in Death Note) or by Critic rejection.
 *
 * Special characteristics:
 * - Never decayed (trust score always preserved)
 * - Always returned in KB search results regardless of trust score
 * - Displayed prominently in Agent boot context to prevent re-exploring the same paths
 * - Promoted from Death Notes via [EventTypes.STAGING_PROMOTE] with type = "dead_end"
 * - Can be referenced by agents to claim "I avoided X because it's a known dead end"
 */
@Entity(
    tableName = "dead_ends",
    indices = [
        Index("runId"),
        Index("groupId"),
        Index("domain"),
        Index("generation"),
        Index("sourceAgentId"),
        Index(value = ["runId", "groupId", "domain"])
    ]
)
data class DeadEndsEntity(
    @PrimaryKey val id: String,
    val runId: String,
    val groupId: String,
    /** Short, searchable description of the failed approach. */
    val approach: String,
    /** Explanation of why this approach is a dead end. */
    val reason: String,
    /** Domain tag (e.g., "search", "tool_execution", "kg_inference"). */
    val domain: String,
    val generation: Int,
    val sourceAgentId: String,
    val createdAtMs: Long,
    /**
     * Number of times this dead end has been referenced by subsequent agents
     * in their Death Notes (useful for ranking relevance).
     */
    val referenceCount: Int = 0
)
