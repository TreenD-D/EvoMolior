package com.achulkov.evokammer.core.contract

import kotlinx.serialization.Serializable

/**
 * The result of a check-in evaluation, delivered to all groups simultaneously when the
 * BlindSubmissionGate closes.
 *
 * @param checkinId Unique identifier for this check-in.
 * @param runId The run this check-in belongs to.
 * @param generationMilestone The generation at which this check-in was triggered.
 * @param groupResults Per-group scores and pass/fail decisions.
 * @param sealedAtMs Unix epoch ms when the gate closed and scoring started.
 * @param revealedAtMs Unix epoch ms when scores were simultaneously revealed.
 */
@Serializable
data class CheckinResult(
    val checkinId: String,
    val runId: String,
    val generationMilestone: Int,
    val groupResults: List<GroupCheckinResult>,
    val sealedAtMs: Long,
    val revealedAtMs: Long
)

/**
 * The check-in result for a single group.
 *
 * @param groupId The group.
 * @param overallScore Normalised score (0.0–1.0).
 * @param passed Whether the group met the minimum threshold to advance to the next stage.
 * @param dimensionScores Per-dimension scores (dimension name → score).
 * @param testPassRate Deterministic test pass rate (0.0–1.0) for the sealed suite.
 * @param failedTestCount Number of failed deterministic tests.
 * @param gradingSheetApplied ID of the GradingSheet used.
 * @param stageAdvanced Whether the group's disclosure stage was advanced.
 * @param deliverableNodeUpdates Node-level deliverable updates synthesized from the check-in.
 */
@Serializable
data class GroupCheckinResult(
    val groupId: String,
    val overallScore: Double,
    val passed: Boolean,
    val dimensionScores: Map<String, Double>,
    val testPassRate: Double,
    val failedTestCount: Int,
    val gradingSheetApplied: String,
    val stageAdvanced: Boolean,
    val deliverableNodeUpdates: List<DeliverableSkeletonNodeUpdate> = emptyList()
)
