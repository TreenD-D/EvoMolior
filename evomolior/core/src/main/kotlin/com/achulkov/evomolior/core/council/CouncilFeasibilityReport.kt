package com.achulkov.evomolior.core.council

import kotlinx.serialization.Serializable

/** Scheduling assessment only; neither status grants strict acceptance or delivery authority. */
@Serializable
enum class CouncilFeasibilityStatus {
    FITS_SCHEDULING_BOUNDS,
    PROVISIONAL_ONLY
}

/**
 * Persistable necessary-bound assessment of the sealed Council plan.
 *
 * Bounds optimistically assume successful grading by the next round and immediate disclosure.
 * Only stage-mandatory nodes and their prerequisite closure are counted; optional alternatives,
 * coverage thresholds, replication and additional acceptance obligations can require more work.
 * Fitting these bounds is not proof of schedulability, sufficient credits, affordable provider spend,
 * successful validation, or acceptance. Group capacity cannot be pooled into one group's evidence.
 *
 * @param status Whether the plan exceeds a necessary scheduling bound.
 * @param dependencyWaveLowerBound Longest prerequisite chain, including its root.
 * @param perGroupNodeRoundLowerBound Rounds needed for one candidate group's own node evidence.
 * @param minimumRoundsLowerBound Maximum of the independent necessary bounds.
 * @param availableRounds Unmodified operator round limit.
 * @param slotsPerGroupPerRound Unmodified operator worker-slot limit.
 * @param workerGroupCount Number of designed worker groups, not a capacity multiplier.
 * @param replansUsed Feasibility-triggered replans consumed from the shared Council repair limit.
 * @param reasons Human-readable scheduling limitations.
 */
@Serializable
data class CouncilFeasibilityReport(
    val status: CouncilFeasibilityStatus,
    val dependencyWaveLowerBound: Int,
    val perGroupNodeRoundLowerBound: Int,
    val minimumRoundsLowerBound: Int,
    val availableRounds: Int,
    val slotsPerGroupPerRound: Int,
    val workerGroupCount: Int,
    val replansUsed: Int,
    val reasons: List<String>
)
