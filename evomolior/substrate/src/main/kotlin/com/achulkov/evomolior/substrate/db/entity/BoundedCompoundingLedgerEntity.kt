package com.achulkov.evomolior.substrate.db.entity

import androidx.room.Entity
import androidx.room.Index

/** Immutable persisted disposition for one accepted repair diagnostic. */
@Entity(
    tableName = "compounding_repair_work_items",
    indices = [
        Index(value = ["diagnosticHash"], unique = true),
        Index(value = ["workItemHash"], unique = true),
        Index(value = ["runId", "groupId", "nextRound"])
    ]
)
data class CompoundingRepairWorkItemEntity(
    @androidx.room.PrimaryKey
    val workItemId: String,
    val diagnosticId: String,
    val diagnosticHash: String,
    val runId: String,
    val groupId: String,
    val nodeId: String,
    val opportunityId: String,
    val opportunityHash: String,
    val nextRound: Int,
    val disposition: String,
    val dispositionReason: String?,
    val workItemHash: String,
    val contentJson: String
)

/** Immutable bounded experiment/WIP materialized for one accepted Outlier proposal. */
@Entity(
    tableName = "compounding_outlier_experiments",
    indices = [
        Index(value = ["proposalHash"], unique = true),
        Index(value = ["experimentHash"], unique = true),
        Index(value = ["runId", "groupId", "startRound"])
    ]
)
data class CompoundingOutlierExperimentEntity(
    @androidx.room.PrimaryKey
    val experimentId: String,
    val proposalId: String,
    val proposalHash: String,
    val runId: String,
    val groupId: String,
    val sourceRound: Int,
    val startRound: Int,
    val maxRounds: Int,
    val maxAttempts: Int,
    val boundedWipTaskId: String,
    val disposition: String,
    val dispositionReason: String?,
    val experimentHash: String,
    val contentJson: String
)

/** Immutable explicit spend of an applied reward against a concrete action. */
@Entity(
    tableName = "compounding_reward_spends",
    indices = [
        Index(value = ["spendHash"], unique = true),
        Index(value = ["rewardApplicationHash"]),
        Index(value = ["runId", "groupId", "spentRound"])
    ]
)
data class CompoundingRewardSpendEntity(
    @androidx.room.PrimaryKey
    val spendId: String,
    val runId: String,
    val groupId: String,
    val rewardApplicationHash: String,
    val rewardType: String,
    val amountSpent: Double,
    val spentRound: Int,
    val purposeHash: String,
    val purposeKind: String,
    val purposeTargetId: String,
    val purposeTargetHash: String,
    val purposeActionId: String,
    val spendHash: String,
    val contentJson: String
)

/** Immutable later artifact delta linked to one explicit reward spend. */
@Entity(
    tableName = "compounding_reward_artifact_deltas",
    indices = [
        Index(value = ["spendId"], unique = true),
        Index(value = ["deltaHash"], unique = true),
        Index(value = ["runId", "groupId", "observedRound"])
    ]
)
data class CompoundingRewardArtifactDeltaEntity(
    @androidx.room.PrimaryKey
    val deltaId: String,
    val spendId: String,
    val spendHash: String,
    val runId: String,
    val groupId: String,
    val observedRound: Int,
    val artifactId: String,
    val beforeArtifactHash: String,
    val afterArtifactHash: String,
    val metricDelta: Double,
    val deltaHash: String,
    val contentJson: String
)
