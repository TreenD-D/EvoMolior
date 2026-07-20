package com.achulkov.evomolior.substrate.db.entity

import androidx.room.Entity
import androidx.room.Index

/** Immutable serialized node evidence packet. */
@Entity(
    tableName = "node_evidence_packets",
    indices = [
        Index(value = ["packetHash"], unique = true),
        Index(value = ["runId", "nodeId", "round"]),
        Index(value = ["runId", "groupId", "round"]),
        Index(value = ["atlasHash", "nodePayloadHash"])
    ]
)
data class NodeEvidencePacketEntity(
    @androidx.room.PrimaryKey
    val packetId: String,
    val runId: String,
    val round: Int,
    val groupId: String,
    val nodeId: String,
    val atlasVersion: Int,
    val atlasHash: String,
    val nodePayloadHash: String,
    val packetHash: String,
    val contentJson: String,
    val createdAtMs: Long
)

/** Immutable serialized exact node-grade bundle. */
@Entity(
    tableName = "node_grade_bundles",
    indices = [
        Index(value = ["bundleHash"], unique = true),
        Index(value = ["runId", "nodeId", "round"]),
        Index(value = ["runId", "groupId", "round"]),
        Index(value = ["evidencePacketHash"]),
        Index(value = ["atlasHash", "nodePayloadHash"])
    ]
)
data class NodeGradeBundleEntity(
    @androidx.room.PrimaryKey
    val bundleId: String,
    val runId: String,
    val round: Int,
    val groupId: String,
    val nodeId: String,
    val atlasVersion: Int,
    val atlasHash: String,
    val nodePayloadHash: String,
    val evidencePacketId: String?,
    val evidencePacketHash: String?,
    val verdict: String,
    val normalizedScore: Double?,
    val bundleHash: String,
    val contentJson: String,
    val createdAtMs: Long
)

/** Immutable serialized node-capture resolution. */
@Entity(
    tableName = "node_capture_decisions",
    indices = [
        Index(value = ["decisionHash"], unique = true),
        Index(value = ["runId", "nodeId", "round"]),
        Index(value = ["atlasHash", "nodePayloadHash"]),
        Index(value = ["decisionType"])
    ]
)
data class NodeCaptureDecisionEntity(
    @androidx.room.PrimaryKey
    val decisionId: String,
    val runId: String,
    val round: Int,
    val nodeId: String,
    val atlasVersion: Int,
    val atlasHash: String,
    val nodePayloadHash: String,
    val decisionType: String,
    val resultingCaptureState: String,
    val yieldEligible: Boolean,
    val decisionHash: String,
    val contentJson: String,
    val createdAtMs: Long
)

/** Immutable serialized next-round node-yield schedule. */
@Entity(
    tableName = "node_yield_schedules",
    indices = [
        Index(value = ["scheduleHash"], unique = true),
        Index(value = ["runId", "groupId", "activationRound"]),
        Index(value = ["runId", "nodeId", "judgingRound"]),
        Index(value = ["captureDecisionHash"])
    ]
)
data class NodeYieldScheduleEntity(
    @androidx.room.PrimaryKey
    val scheduleId: String,
    val runId: String,
    val nodeId: String,
    val groupId: String,
    val judgingRound: Int,
    val activationRound: Int,
    val captureDecisionHash: String,
    val scheduleHash: String,
    val contentJson: String,
    val createdAtMs: Long
)

/** Immutable serialized application of one delayed node-yield schedule. */
@Entity(
    tableName = "node_yield_applications",
    indices = [
        Index(value = ["applicationHash"], unique = true),
        Index(value = ["scheduleId"], unique = true),
        Index(value = ["scheduleHash"], unique = true),
        Index(value = ["runId", "groupId", "round"])
    ]
)
data class NodeYieldApplicationEntity(
    @androidx.room.PrimaryKey
    val applicationId: String,
    val scheduleId: String,
    val scheduleHash: String,
    val runId: String,
    val nodeId: String,
    val groupId: String,
    val round: Int,
    val applicationHash: String,
    val contentJson: String,
    val createdAtMs: Long
)

/** Immutable serialized causal reward-use lineage event. */
@Entity(
    tableName = "reward_use_lineage_events",
    indices = [
        Index(value = ["eventHash"], unique = true),
        Index(value = ["runId", "groupId", "spentRound"]),
        Index(value = ["yieldApplicationHash"]),
        Index(value = ["parentEventHash"])
    ]
)
data class RewardUseLineageEventEntity(
    @androidx.room.PrimaryKey
    val eventId: String,
    val runId: String,
    val groupId: String,
    val nodeId: String,
    val spentRound: Int,
    val rewardType: String,
    val yieldApplicationHash: String,
    val parentEventHash: String?,
    val eventHash: String,
    val contentJson: String,
    val createdAtMs: Long
)
