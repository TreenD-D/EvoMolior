package com.achulkov.evomolior.substrate.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/** Immutable serialized Grader Atlas version. */
@Entity(
    tableName = "grader_atlas_versions",
    primaryKeys = ["graderAtlasId", "version"],
    indices = [
        Index(value = ["atlasHash"], unique = true),
        Index(value = ["runId", "version"])
    ]
)
data class GraderAtlasVersionEntity(
    val graderAtlasId: String,
    val runId: String,
    val version: Int,
    val workerAtlasHash: String,
    val atlasHash: String,
    val contentJson: String,
    val createdAtMs: Long
)

/** Immutable append event for one Grader Atlas node state revision. */
@Entity(
    tableName = "grader_node_state_events",
    indices = [
        Index(value = ["stateHash"], unique = true),
        Index(
            value = ["runId", "graderAtlasHash", "graderGroupId", "nodeId", "revision"],
            unique = true
        )
    ]
)
data class GraderNodeStateEventEntity(
    @androidx.room.PrimaryKey
    val eventId: String,
    val runId: String,
    val graderAtlasHash: String,
    @ColumnInfo(defaultValue = "")
    val graderGroupId: String,
    val nodeId: String,
    val revision: Long,
    val status: String,
    val stateHash: String,
    val contentJson: String,
    val createdAtMs: Long
)

/** Immutable grader-node lease revision. */
@Entity(
    tableName = "grader_node_leases",
    indices = [
        Index(value = ["leaseHash"], unique = true),
        Index(value = ["runId", "graderAtlasHash", "nodeId", "graderGroupId"]),
        Index(value = ["expiresAtEpochMillis", "status"])
    ]
)
data class GraderNodeLeaseEntity(
    @androidx.room.PrimaryKey
    val leaseId: String,
    val runId: String,
    val graderAtlasHash: String,
    val nodeId: String,
    val graderGroupId: String,
    val expiresAtEpochMillis: Long,
    val status: String,
    val leaseHash: String,
    val contentJson: String,
    val createdAtMs: Long
)

/** Immutable typed grading requirement. */
@Entity(
    tableName = "grading_requirements",
    indices = [
        Index(value = ["requirementHash"], unique = true),
        Index(value = ["runId", "round", "graderGroupId"]),
        Index(value = ["graderAtlasHash", "graderAtlasNodeId"]),
        Index(value = ["origin"])
    ]
)
data class GradingRequirementEntity(
    @androidx.room.PrimaryKey
    val requirementId: String,
    val runId: String,
    val round: Int,
    val graderGroupId: String,
    val graderAtlasHash: String,
    val graderAtlasNodeId: String,
    val origin: String,
    val requirementHash: String,
    val contentJson: String,
    val createdAtMs: Long
)

/** Immutable hidden-test definition or mutation version. */
@Entity(
    tableName = "hidden_test_definitions",
    indices = [
        Index(value = ["testHash"], unique = true),
        Index(value = ["runId", "graderGroupId", "roundCreated"]),
        Index(value = ["graderAtlasHash", "status"]),
        Index(value = ["parentTestHash"])
    ]
)
data class HiddenTestDefinitionEntity(
    @androidx.room.PrimaryKey
    val testId: String,
    val runId: String,
    val graderGroupId: String,
    val roundCreated: Int,
    val graderAtlasHash: String,
    val status: String,
    val parentTestHash: String?,
    val testHash: String,
    val contentJson: String,
    val createdAtMs: Long
)

/** Immutable criteria freeze; cardinality is unique per grader group and round. */
@Entity(
    tableName = "grader_criteria_freezes",
    indices = [
        Index(value = ["freezeHash"], unique = true),
        Index(value = ["runId", "round", "graderGroupId"], unique = true),
        Index(value = ["graderAtlasHash"])
    ]
)
data class GraderCriteriaFreezeEntity(
    @androidx.room.PrimaryKey
    val freezeId: String,
    val runId: String,
    val round: Int,
    val graderGroupId: String,
    val graderAtlasHash: String,
    val freezeHash: String,
    val contentJson: String,
    val createdAtMs: Long
)

/** Immutable exact-cardinality score bundle. */
@Entity(
    tableName = "grader_score_bundles",
    indices = [
        Index(value = ["payloadHash"], unique = true),
        Index(
            value = ["runId", "round", "graderGroupId", "targetGroupId"],
            unique = true
        ),
        Index(value = ["criteriaFreezeHash"]),
        Index(value = ["targetSnapshotHash"])
    ]
)
data class GraderScoreBundleEntity(
    @androidx.room.PrimaryKey
    val bundleId: String,
    val runId: String,
    val round: Int,
    val graderGroupId: String,
    val targetGroupId: String,
    val criteriaFreezeHash: String,
    val targetSnapshotHash: String,
    val status: String,
    val payloadHash: String,
    val contentJson: String,
    val createdAtMs: Long
)

/** Immutable plane-separated grader-fitness record. */
@Entity(
    tableName = "grader_fitness_records",
    indices = [
        Index(value = ["recordHash"], unique = true),
        Index(value = ["runId", "round", "graderGroupId", "plane"])
    ]
)
data class GraderFitnessRecordEntity(
    @androidx.room.PrimaryKey
    val recordId: String,
    val runId: String,
    val round: Int,
    val graderGroupId: String,
    val plane: String,
    val aggregateScore: Double,
    val recordHash: String,
    val contentJson: String,
    val createdAtMs: Long
)

/** Immutable run-level evaluation-independence profile. */
@Entity(
    tableName = "evaluation_independence_profiles",
    indices = [
        Index(value = ["profileHash"], unique = true),
        Index(value = ["runId", "createdAtMs"])
    ]
)
data class EvaluationIndependenceProfileEntity(
    @androidx.room.PrimaryKey
    val profileId: String,
    val runId: String,
    val independenceLabel: String,
    val profileHash: String,
    val contentJson: String,
    val createdAtMs: Long
)
