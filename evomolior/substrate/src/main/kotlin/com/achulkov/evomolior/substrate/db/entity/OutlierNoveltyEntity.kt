package com.achulkov.evomolior.substrate.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "novelty_vector_proposals",
    indices = [
        Index(value = ["runId", "round"]),
        Index(value = ["runId", "groupId", "round"]),
        Index(value = ["proposalHash"], unique = true),
        Index(value = ["runId", "noveltyFingerprint"])
    ]
)
data class NoveltyVectorProposalEntity(
    @PrimaryKey val proposalId: String,
    val runId: String,
    val groupId: String,
    val round: Int,
    val proposerAgentId: String,
    val noveltyFingerprint: String,
    val proposalHash: String,
    val contentJson: String,
    val createdAtMs: Long
)

@Entity(
    tableName = "novelty_screening_results",
    indices = [
        Index(value = ["proposalId"], unique = true),
        Index(value = ["proposalHash"], unique = true)
    ]
)
data class NoveltyScreeningResultEntity(
    @PrimaryKey val screeningId: String,
    val proposalId: String,
    val proposalHash: String,
    val status: String,
    val screeningHash: String,
    val contentJson: String,
    val createdAtMs: Long
)

@Entity(
    tableName = "novelty_grader_assessments",
    indices = [
        Index(value = ["proposalHash", "graderGroupId"], unique = true),
        Index(value = ["assessmentHash"], unique = true)
    ]
)
data class NoveltyGraderAssessmentEntity(
    @PrimaryKey val assessmentId: String,
    val proposalHash: String,
    val graderGroupId: String,
    val status: String,
    val assessmentHash: String,
    val contentJson: String,
    val createdAtMs: Long
)

@Entity(
    tableName = "shadow_node_grader_verdicts",
    indices = [
        Index(value = ["proposalHash"], unique = true),
        Index(value = ["verdictHash"], unique = true)
    ]
)
data class ShadowNodeGraderVerdictEntity(
    @PrimaryKey val proposalHash: String,
    val decision: String,
    val verdictHash: String,
    val contentJson: String,
    val createdAtMs: Long
)

@Entity(
    tableName = "shadow_node_lifecycle_events",
    indices = [
        Index(value = ["proposalId", "sequence"], unique = true),
        Index(value = ["proposalHash"]),
        Index(value = ["runId", "round"]),
        Index(value = ["eventHash"], unique = true)
    ]
)
data class ShadowNodeLifecycleEventEntity(
    @PrimaryKey val eventId: String,
    val proposalId: String,
    val proposalHash: String,
    val runId: String,
    val sequence: Long,
    val toState: String,
    val round: Int,
    val eventHash: String,
    val contentJson: String,
    val createdAtMs: Long
)

@Entity(
    tableName = "outlier_downstream_lineage",
    indices = [
        Index(value = ["proposalId", "observationRound"]),
        Index(value = ["proposalHash"]),
        Index(value = ["lineageHash"], unique = true)
    ]
)
data class OutlierDownstreamLineageEntity(
    @PrimaryKey val lineageId: String,
    val proposalId: String,
    val proposalHash: String,
    val observationRound: Int,
    val outcome: String,
    val lineageHash: String,
    val contentJson: String,
    val createdAtMs: Long
)

@Entity(
    tableName = "outlier_fitness_records",
    indices = [
        Index(value = ["proposalId", "observationRound"], unique = true),
        Index(value = ["proposalHash"]),
        Index(value = ["recordHash"], unique = true)
    ]
)
data class OutlierFitnessRecordEntity(
    @PrimaryKey val recordId: String,
    val proposalId: String,
    val proposalHash: String,
    val agentId: String,
    val observationRound: Int,
    val recordHash: String,
    val contentJson: String,
    val createdAtMs: Long
)
