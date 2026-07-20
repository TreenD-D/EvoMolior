package com.achulkov.evomolior.substrate.db.entity

import androidx.room.Entity
import androidx.room.Index

/** Immutable registration of one agent lifecycle for publication accounting. */
@Entity(
    tableName = "agent_lifecycle_registrations",
    indices = [
        Index(value = ["runId", "groupId", "classification"]),
        Index(value = ["runId", "groupId", "agentId"], unique = true),
        Index(value = ["registrationHash"], unique = true)
    ]
)
data class AgentLifecycleRegistrationEntity(
    @androidx.room.PrimaryKey
    val registrationId: String,
    val runId: String,
    val groupId: String,
    val agentId: String,
    val role: String,
    val generation: Int,
    val classification: String,
    val registrationHash: String,
    val contentJson: String,
    val registeredAtMs: Long
)

/** Exact immutable serialization of one group NodePortfolio. */
@Entity(
    tableName = "node_portfolios",
    indices = [
        Index(value = ["runId", "groupId", "round", "portfolioId"], unique = true),
        Index(value = ["portfolioHash"], unique = true)
    ]
)
data class NodePortfolioEntity(
    @androidx.room.PrimaryKey
    val portfolioId: String,
    val runId: String,
    val groupId: String,
    val round: Int,
    val portfolioHash: String,
    val contentJson: String
)

/** Immutable serialized ContributionCommit submitted by one ordinary agent. */
@Entity(
    tableName = "contribution_commits",
    indices = [
        Index(value = ["runId", "groupId", "agentId"]),
        Index(value = ["runId", "groupId", "nodeId"]),
        Index(value = ["runId", "groupId", "dedupeHash"], unique = true)
    ]
)
data class ContributionCommitEntity(
    @androidx.room.PrimaryKey
    val commitId: String,
    val runId: String,
    val groupId: String,
    val agentId: String,
    val nodeId: String?,
    val round: Int,
    val commitType: String,
    val dedupeHash: String,
    val contentJson: String,
    val committedAtMs: Long
)

/** Append-only degraded-execution record that cannot count as a positive contribution. */
@Entity(
    tableName = "degraded_execution_records",
    indices = [
        Index(value = ["runId", "groupId", "agentId"]),
        Index(value = ["runId", "groupId", "nodeId"])
    ]
)
data class DegradedExecutionRecordEntity(
    @androidx.room.PrimaryKey
    val recordId: String,
    val runId: String,
    val groupId: String,
    val agentId: String,
    val nodeId: String?,
    val round: Int,
    val reasonCode: String,
    val contentJson: String,
    val occurredAtMs: Long
)

/** Idempotent append-only TTL checkpoint for one mortal agent. */
@Entity(
    tableName = "ttl_checkpoint_events",
    indices = [
        Index(value = ["runId", "groupId", "agentId", "checkpoint"], unique = true),
        Index(value = ["runId", "groupId", "round"])
    ]
)
data class TtlCheckpointEventEntity(
    @androidx.room.PrimaryKey
    val eventId: String,
    val runId: String,
    val groupId: String,
    val agentId: String,
    val round: Int,
    val checkpoint: String,
    val elapsedFraction: Double,
    val remainingBudget: Double,
    val phase: String,
    val contentJson: String,
    val occurredAtMs: Long
)

/** Immutable final exit classification for one ordinary mortal agent. */
@Entity(
    tableName = "agent_exit_records",
    indices = [
        Index(value = ["runId", "groupId", "agentId"], unique = true),
        Index(value = ["runId", "groupId", "round"]),
        Index(value = ["runId", "groupId", "acceptedContribution"])
    ]
)
data class AgentExitRecordEntity(
    @androidx.room.PrimaryKey
    val exitId: String,
    val runId: String,
    val groupId: String,
    val agentId: String,
    val nodeId: String?,
    val round: Int,
    val finalPhase: String,
    val commitId: String?,
    val commitType: String?,
    val acceptedContribution: Boolean,
    val acceptedStructuredDeadEnd: Boolean,
    val acceptedNoveltyProposal: Boolean,
    val degradedExecution: Boolean,
    val wasKilled: Boolean,
    val contentJson: String,
    val exitedAtMs: Long
)
