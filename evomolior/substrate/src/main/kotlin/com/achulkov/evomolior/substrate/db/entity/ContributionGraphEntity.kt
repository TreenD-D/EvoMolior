package com.achulkov.evomolior.substrate.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Append-only typed edge referencing immutable contribution authorities. */
@Entity(
    tableName = "contribution_edges",
    indices = [
        Index(value = ["runId", "groupId", "round"]),
        Index(value = ["runId", "groupId", "sourceKind", "sourceAuthorityId"]),
        Index(value = ["runId", "groupId", "targetKind", "targetAuthorityId"]),
        Index(value = ["runId", "groupId", "edgeHash"], unique = true)
    ]
)
data class ContributionEdgeEntity(
    @PrimaryKey
    val edgeId: String,
    val runId: String,
    val groupId: String,
    val round: Int,
    val plane: String,
    val relation: String,
    val sourceKind: String,
    val sourceAuthorityId: String,
    val sourceAuthorityHash: String?,
    val targetKind: String,
    val targetAuthorityId: String,
    val targetAuthorityHash: String?,
    val edgeHash: String,
    val contentJson: String,
    val occurredAtMs: Long
)

/** Immutable final Critic decision over one exact staged submission. */
@Entity(
    tableName = "critic_decisions",
    indices = [
        Index(value = ["runId", "groupId", "submissionId"], unique = true),
        Index(value = ["runId", "groupId", "round"]),
        Index(value = ["runId", "groupId", "criticAgentId"])
    ]
)
data class CriticDecisionEntity(
    @PrimaryKey
    val decisionId: String,
    val runId: String,
    val groupId: String,
    val round: Int,
    val submissionId: String,
    val submissionContentHash: String,
    val criticAgentId: String,
    val outcome: String,
    val resultKind: String?,
    val resultAuthorityId: String?,
    val resultAuthorityHash: String?,
    val decisionHash: String,
    val contentJson: String,
    val decidedAtMs: Long
)

/** Immutable exact downstream read or citation over canonical contribution authority. */
@Entity(
    tableName = "downstream_use_records",
    indices = [
        Index(value = ["runId", "groupId", "round"]),
        Index(value = ["runId", "groupId", "agentId"]),
        Index(
            value = ["runId", "groupId", "sourceKind", "sourceAuthorityId"]
        ),
        Index(value = ["recordHash"], unique = true)
    ]
)
data class DownstreamUseRecordEntity(
    @PrimaryKey
    val useId: String,
    val runId: String,
    val groupId: String,
    val round: Int,
    val agentId: String,
    val nodeId: String?,
    val kind: String,
    val sourceKind: String,
    val sourceAuthorityId: String,
    val sourceAuthorityHash: String,
    val contextHash: String,
    val recordHash: String,
    val contentJson: String,
    val occurredAtMs: Long
)

/** Immutable authority for exact accepted inputs integrated into one captured Atlas node. */
@Entity(
    tableName = "integration_node_records",
    indices = [
        Index(value = ["runId", "groupId", "round"]),
        Index(value = ["runId", "groupId", "nodeId"]),
        Index(value = ["captureDecisionId"], unique = true),
        Index(value = ["recordHash"], unique = true)
    ]
)
data class IntegrationNodeRecordEntity(
    @PrimaryKey
    val integrationId: String,
    val runId: String,
    val groupId: String,
    val round: Int,
    val nodeId: String,
    val atlasVersion: Int,
    val atlasHash: String,
    val nodePayloadHash: String,
    val captureDecisionId: String,
    val captureDecisionHash: String,
    val recordHash: String,
    val contentJson: String,
    val integratedAtMs: Long
)

/** Immutable binding from one admitted contribution authority to one final output file. */
@Entity(
    tableName = "final_deliverable_reference_records",
    indices = [
        Index(value = ["runId", "groupId", "round"]),
        Index(value = ["runId", "relativePath", "contentHash"]),
        Index(value = ["sourceKind", "sourceAuthorityId"]),
        Index(value = ["recordHash"], unique = true)
    ]
)
data class FinalDeliverableReferenceRecordEntity(
    @PrimaryKey
    val referenceId: String,
    val runId: String,
    val groupId: String,
    val round: Int,
    val relativePath: String,
    val mediaType: String,
    val contentHash: String,
    val writerAgentId: String,
    val writerSubmissionId: String,
    val nodeId: String?,
    val sourceKind: String,
    val sourceAuthorityId: String,
    val sourceAuthorityHash: String,
    val recordHash: String,
    val contentJson: String,
    val referencedAtMs: Long
)

/** Immutable receiver-score component over one exact final-output reference. */
@Entity(
    tableName = "receiver_score_contribution_records",
    indices = [
        Index(value = ["runId", "groupId", "round"]),
        Index(value = ["finalReferenceId"]),
        Index(value = ["recordHash"], unique = true)
    ]
)
data class ReceiverScoreContributionRecordEntity(
    @PrimaryKey
    val contributionId: String,
    val runId: String,
    val groupId: String,
    val round: Int,
    val finalReferenceId: String,
    val finalReferenceHash: String,
    val scoringContractId: String,
    val scoringContractVersion: Int,
    val scoringContractHash: String,
    val criterionId: String,
    val scoreContribution: Double,
    val maximumContribution: Double,
    val recordHash: String,
    val contentJson: String,
    val scoredAtMs: Long
)

/** Immutable benchmark-score component over one exact final-output reference. */
@Entity(
    tableName = "benchmark_score_contribution_records",
    indices = [
        Index(value = ["runId", "groupId", "round"]),
        Index(value = ["finalReferenceId"]),
        Index(value = ["recordHash"], unique = true)
    ]
)
data class BenchmarkScoreContributionRecordEntity(
    @PrimaryKey
    val contributionId: String,
    val runId: String,
    val groupId: String,
    val round: Int,
    val finalReferenceId: String,
    val finalReferenceHash: String,
    val benchmarkId: String,
    val benchmarkVersion: Int,
    val benchmarkContractHash: String,
    val componentId: String,
    val scoreContribution: Double,
    val maximumContribution: Double,
    val recordHash: String,
    val contentJson: String,
    val scoredAtMs: Long
)

/** Immutable typed control-plane intervention and its assessed outcome authority. */
@Entity(
    tableName = "control_intervention_records",
    indices = [
        Index(value = ["runId", "groupId", "round"]),
        Index(value = ["runId", "groupId", "controllerAgentId"]),
        Index(value = ["outcome"]),
        Index(value = ["recordHash"], unique = true)
    ]
)
data class ControlInterventionRecordEntity(
    @PrimaryKey
    val interventionId: String,
    val runId: String,
    val groupId: String,
    val round: Int,
    val controllerAgentId: String,
    val controllerRole: String,
    val targetKind: String,
    val targetId: String,
    val outcome: String,
    val recordHash: String,
    val contentJson: String,
    val occurredAtMs: Long
)

/** Immutable final-summary fidelity, retrieval, compression, and competition validation. */
@Entity(
    tableName = "summary_validation_records",
    indices = [
        Index(value = ["runId", "groupId", "round"]),
        Index(value = ["runId", "groupId", "summarizerAgentId"]),
        Index(value = ["recordHash"], unique = true)
    ]
)
data class SummaryValidationRecordEntity(
    @PrimaryKey
    val validationId: String,
    val runId: String,
    val groupId: String,
    val round: Int,
    val summarizerAgentId: String,
    val validatorContractHash: String,
    val recordHash: String,
    val contentJson: String,
    val validatedAtMs: Long
)
