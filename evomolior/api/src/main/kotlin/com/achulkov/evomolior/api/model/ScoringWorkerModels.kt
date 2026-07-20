package com.achulkov.evomolior.api.model

import com.achulkov.evomolior.competition.grader.GradingSheet
import com.achulkov.evomolior.competition.grader.ExactGraderRoundVerdict
import com.achulkov.evomolior.competition.scoring.FailureTelemetry
import com.achulkov.evomolior.competition.scoring.GroupRoundResult
import com.achulkov.evomolior.competition.scoring.ScoreSealAudit
import com.achulkov.evomolior.core.contract.DeliverableSkeletonNodeUpdate
import com.achulkov.evomolior.core.model.GradingResult
import com.achulkov.evomolior.core.grader.GraderScoreBundle
import kotlinx.serialization.Serializable

/**
 * Complete sealed input for one isolated scoring operation.
 *
 * @param roundIndex Round being scored.
 * @param gradingSheets Sealed grader sheets available to the worker.
 * @param submissions Complete group submissions to place behind the blind gate.
 * @param expectedGraderGroupIds Exact independent grader quorum.
 * @param exactGraderBundles Immutable exact bundles resolved inside the scoring child JVM.
 */
@Serializable
data class ScoringWorkerRequest(
    val roundIndex: Int,
    val gradingSheets: List<GradingSheet>,
    val submissions: List<GroupRoundResult>,
    val expectedGraderGroupIds: Set<String> = emptySet(),
    val exactGraderBundles: List<GraderScoreBundle> = emptyList()
)

/**
 * Final-only output returned after the worker has closed the blind gate and scored the round.
 *
 * @param result Final authoritative grading result.
 * @param failureTelemetry Post-score grader-learning telemetry.
 * @param deliverableNodeUpdates Post-score deliverable updates.
 * @param sealAudit Blind gate audit record.
 * @param exactGraderVerdict Child-JVM replay of exact bundle cardinality and aggregation.
 */
@Serializable
data class ScoringWorkerResponse(
    val result: GradingResult,
    val failureTelemetry: List<FailureTelemetry>,
    val deliverableNodeUpdates: List<DeliverableSkeletonNodeUpdate>,
    val sealAudit: ScoreSealAudit,
    val exactGraderVerdict: ExactGraderRoundVerdict? = null
)

/**
 * Minimal worker health response.
 *
 * @param ready Whether the scoring authority is ready.
 * @param pid Worker process identifier.
 */
@Serializable
data class ScoringWorkerHealth(
    val ready: Boolean,
    val pid: Long
)
