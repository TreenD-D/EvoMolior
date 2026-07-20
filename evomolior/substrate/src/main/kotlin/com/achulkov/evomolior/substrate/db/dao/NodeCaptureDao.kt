package com.achulkov.evomolior.substrate.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.achulkov.evomolior.substrate.db.entity.NodeCaptureDecisionEntity
import com.achulkov.evomolior.substrate.db.entity.NodeEvidencePacketEntity
import com.achulkov.evomolior.substrate.db.entity.NodeGradeBundleEntity
import com.achulkov.evomolior.substrate.db.entity.NodeYieldApplicationEntity
import com.achulkov.evomolior.substrate.db.entity.NodeYieldScheduleEntity
import com.achulkov.evomolior.substrate.db.entity.RewardUseLineageEventEntity

/** Internal append-only persistence boundary for node evidence, capture, and reward lineage. */
@Dao
internal interface NodeCaptureDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEvidencePacket(packet: NodeEvidencePacketEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertGradeBundle(bundle: NodeGradeBundleEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCaptureDecision(decision: NodeCaptureDecisionEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertYieldSchedule(schedule: NodeYieldScheduleEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertYieldApplication(application: NodeYieldApplicationEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRewardUseEvent(event: RewardUseLineageEventEntity): Long

    @Query("SELECT * FROM node_evidence_packets WHERE packetId = :packetId LIMIT 1")
    suspend fun evidencePacketById(packetId: String): NodeEvidencePacketEntity?

    @Query("SELECT * FROM node_evidence_packets WHERE packetHash = :packetHash LIMIT 1")
    suspend fun evidencePacketByHash(packetHash: String): NodeEvidencePacketEntity?

    @Query("SELECT * FROM node_grade_bundles WHERE bundleId = :bundleId LIMIT 1")
    suspend fun gradeBundleById(bundleId: String): NodeGradeBundleEntity?

    @Query("SELECT * FROM node_grade_bundles WHERE bundleHash = :bundleHash LIMIT 1")
    suspend fun gradeBundleByHash(bundleHash: String): NodeGradeBundleEntity?

    @Query("SELECT * FROM node_capture_decisions WHERE decisionId = :decisionId LIMIT 1")
    suspend fun captureDecisionById(decisionId: String): NodeCaptureDecisionEntity?

    @Query("SELECT * FROM node_capture_decisions WHERE decisionHash = :decisionHash LIMIT 1")
    suspend fun captureDecisionByHash(decisionHash: String): NodeCaptureDecisionEntity?

    @Query("SELECT * FROM node_yield_schedules WHERE scheduleId = :scheduleId LIMIT 1")
    suspend fun yieldScheduleById(scheduleId: String): NodeYieldScheduleEntity?

    @Query("SELECT * FROM node_yield_schedules WHERE scheduleHash = :scheduleHash LIMIT 1")
    suspend fun yieldScheduleByHash(scheduleHash: String): NodeYieldScheduleEntity?

    @Query("SELECT * FROM node_yield_applications WHERE applicationId = :applicationId LIMIT 1")
    suspend fun yieldApplicationById(applicationId: String): NodeYieldApplicationEntity?

    @Query("SELECT * FROM node_yield_applications WHERE scheduleId = :scheduleId LIMIT 1")
    suspend fun yieldApplicationByScheduleId(scheduleId: String): NodeYieldApplicationEntity?

    @Query(
        "SELECT * FROM node_yield_applications WHERE applicationHash = :applicationHash LIMIT 1"
    )
    suspend fun yieldApplicationByHash(
        applicationHash: String
    ): NodeYieldApplicationEntity?

    @Query("SELECT * FROM reward_use_lineage_events WHERE eventId = :eventId LIMIT 1")
    suspend fun rewardUseEventById(eventId: String): RewardUseLineageEventEntity?

    @Query("SELECT * FROM reward_use_lineage_events WHERE eventHash = :eventHash LIMIT 1")
    suspend fun rewardUseEventByHash(eventHash: String): RewardUseLineageEventEntity?

    @Query(
        """
        SELECT * FROM node_evidence_packets
        WHERE runId = :runId AND nodeId = :nodeId
        ORDER BY round ASC, createdAtMs ASC, packetId ASC
        """
    )
    suspend fun evidencePacketsForNode(
        runId: String,
        nodeId: String
    ): List<NodeEvidencePacketEntity>

    @Query(
        """
        SELECT * FROM node_evidence_packets
        WHERE runId = :runId
        ORDER BY round ASC, createdAtMs ASC, packetId ASC
        """
    )
    suspend fun evidencePacketsForRun(runId: String): List<NodeEvidencePacketEntity>

    @Query(
        """
        SELECT * FROM node_grade_bundles
        WHERE runId = :runId AND nodeId = :nodeId
        ORDER BY round ASC, createdAtMs ASC, bundleId ASC
        """
    )
    suspend fun gradeBundlesForNode(
        runId: String,
        nodeId: String
    ): List<NodeGradeBundleEntity>

    @Query(
        """
        SELECT * FROM node_grade_bundles
        WHERE runId = :runId
        ORDER BY round ASC, createdAtMs ASC, bundleId ASC
        """
    )
    suspend fun gradeBundlesForRun(runId: String): List<NodeGradeBundleEntity>

    @Query(
        """
        SELECT * FROM node_capture_decisions
        WHERE runId = :runId AND nodeId = :nodeId
        ORDER BY round ASC, createdAtMs ASC, decisionId ASC
        """
    )
    suspend fun captureDecisionsForNode(
        runId: String,
        nodeId: String
    ): List<NodeCaptureDecisionEntity>

    @Query(
        """
        SELECT * FROM node_capture_decisions
        WHERE runId = :runId
        ORDER BY round ASC, createdAtMs ASC, decisionId ASC
        """
    )
    suspend fun captureDecisionsForRun(runId: String): List<NodeCaptureDecisionEntity>

    @Query(
        """
        SELECT * FROM node_yield_schedules
        WHERE runId = :runId AND groupId = :groupId AND activationRound = :activationRound
        ORDER BY createdAtMs ASC, scheduleId ASC
        """
    )
    suspend fun yieldSchedulesForActivation(
        runId: String,
        groupId: String,
        activationRound: Int
    ): List<NodeYieldScheduleEntity>

    @Query(
        """
        SELECT * FROM node_yield_applications
        WHERE runId = :runId
        ORDER BY round ASC, createdAtMs ASC, applicationId ASC
        """
    )
    suspend fun yieldApplicationsForRun(runId: String): List<NodeYieldApplicationEntity>

    @Query(
        """
        SELECT * FROM reward_use_lineage_events
        WHERE runId = :runId AND yieldApplicationHash = :yieldApplicationHash
        ORDER BY spentRound ASC, createdAtMs ASC, eventId ASC
        """
    )
    suspend fun rewardUseEventsForApplication(
        runId: String,
        yieldApplicationHash: String
    ): List<RewardUseLineageEventEntity>

    @Query(
        """
        SELECT * FROM reward_use_lineage_events
        WHERE runId = :runId
        ORDER BY spentRound ASC, createdAtMs ASC, eventId ASC
        """
    )
    suspend fun rewardUseEventsForRun(runId: String): List<RewardUseLineageEventEntity>
}
