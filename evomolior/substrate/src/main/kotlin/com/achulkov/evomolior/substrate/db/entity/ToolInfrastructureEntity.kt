package com.achulkov.evomolior.substrate.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "tool_opportunity_commitments",
    indices = [
        Index(value = ["runId", "atlasHash", "nodeId"], unique = true),
        Index(value = ["commitmentHash"], unique = true)
    ]
)
data class ToolOpportunityCommitmentEntity(
    @PrimaryKey val opportunityId: String,
    val runId: String,
    val atlasHash: String,
    val nodeId: String,
    val commitmentHash: String,
    val contentJson: String,
    val createdAtMs: Long
)

@Entity(
    tableName = "tool_lifecycle_events",
    indices = [
        Index(value = ["toolVersionId", "sequence"], unique = true),
        Index(value = ["opportunityId"]),
        Index(value = ["runId", "groupId", "state"]),
        Index(value = ["eventHash"], unique = true)
    ]
)
data class ToolLifecycleEventEntity(
    @PrimaryKey val eventId: String,
    val runId: String,
    val opportunityId: String,
    val toolVersionId: String,
    val groupId: String,
    val originNodeId: String,
    val contextNodeId: String,
    val state: String,
    val sequence: Long,
    val eventHash: String,
    val contentJson: String,
    val createdAtMs: Long
)

@Entity(
    tableName = "tool_execution_records",
    indices = [
        Index(value = ["runId", "groupId", "round"]),
        Index(value = ["toolVersionId", "result"]),
        Index(value = ["nodeId"]),
        Index(value = ["recordHash"], unique = true)
    ]
)
data class ToolExecutionRecordEntity(
    @PrimaryKey val executionId: String,
    val runId: String,
    val opportunityId: String,
    val toolVersionId: String,
    val groupId: String,
    val agentId: String,
    val round: Int,
    val nodeId: String,
    val purpose: String,
    val result: String,
    val recordHash: String,
    val contentJson: String,
    val createdAtMs: Long
)

@Entity(
    tableName = "simulation_run_records",
    indices = [
        Index(value = ["executionId"], unique = true),
        Index(value = ["toolVersionId"]),
        Index(value = ["recordHash"], unique = true)
    ]
)
data class SimulationRunRecordEntity(
    @PrimaryKey val simulationRunId: String,
    val executionId: String,
    val toolVersionId: String,
    val recordHash: String,
    val contentJson: String,
    val createdAtMs: Long
)

@Entity(
    tableName = "tool_reuse_lineage",
    indices = [
        Index(value = ["executionId"], unique = true),
        Index(value = ["toolVersionId", "reusedOnNodeId"]),
        Index(value = ["runId", "reusedByAgentId"]),
        Index(value = ["lineageHash"], unique = true)
    ]
)
data class ToolReuseLineageEntity(
    @PrimaryKey val lineageId: String,
    val runId: String,
    val toolVersionId: String,
    val executionId: String,
    val originNodeId: String,
    val reusedByAgentId: String,
    val reusedOnNodeId: String,
    val contributionCommitId: String,
    val nodeEvidencePacketHash: String,
    val lineageHash: String,
    val contentJson: String,
    val createdAtMs: Long
)

@Entity(
    tableName = "tool_fitness_records",
    indices = [
        Index(value = ["toolVersionId", "throughRound"], unique = true),
        Index(value = ["runId"]),
        Index(value = ["recordHash"], unique = true)
    ]
)
data class ToolFitnessRecordEntity(
    @PrimaryKey val recordId: String,
    val runId: String,
    val toolVersionId: String,
    val throughRound: Int,
    val recordHash: String,
    val contentJson: String,
    val createdAtMs: Long
)
