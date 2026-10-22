package com.achulkov.evomolior.substrate.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for a staged submission awaiting Critic review.
 *
 * All agent outputs (documents, tools, facts, death notes) must pass through staging before
 * entering canonical substrate. Death notes skip Critic review and are always promoted.
 */
@Entity(
    tableName = "staging_submissions",
    indices = [
        Index("groupId"),
        Index("runId"),
        Index("agentId"),
        Index("status"),
        Index("type"),
        Index("generation"),
        Index(value = ["runId", "groupId", "status"]),
        Index(
            value = [
                "runId",
                "groupId",
                "wipTaskRef",
                "status",
                "type",
                "generation",
                "reviewedAtMs",
                "submittedAtMs",
                "id"
            ],
            orders = [
                Index.Order.ASC,
                Index.Order.ASC,
                Index.Order.ASC,
                Index.Order.ASC,
                Index.Order.ASC,
                Index.Order.DESC,
                Index.Order.DESC,
                Index.Order.DESC,
                Index.Order.ASC
            ],
            name = "index_staging_tool_repair_history"
        )
    ]
)
data class StagingSubmissionEntity(
    @PrimaryKey val id: String,
    val agentId: String,
    /** Role enum name. */
    val role: String,
    /** SubmissionType enum name. */
    val type: String,
    /** JSON-serialised payload for structured types; plain text for DOC. */
    val content: String,
    val wipTaskRef: String? = null,
    val speculative: Boolean = false,
    val outlier: Boolean = false,
    /** Required: signed token from BudgetLedger. */
    val budgetSignature: String,
    /** "pending" | "promoted" | "rejected" | "disputed" */
    val status: String = "pending",
    val rejectionReason: String? = null,
    val criticAgentId: String? = null,
    /** Initial trust score assigned on promotion; null until reviewed. */
    val initialTrustScore: Double? = null,
    val generation: Int,
    val groupId: String,
    val runId: String,
    val submittedAtMs: Long,
    val reviewedAtMs: Long? = null,
    /** Canonical substrate reference created by a successful Critic promotion. */
    val promotedArtifactRef: String? = null,
    /** Hash of the exact promoted submission content. */
    val promotedArtifactHash: String? = null,
    /** Safe runtime-owned candidate authority; never contains private fixtures or values. */
    val toolCandidateAuthorityJson: String? = null
)
