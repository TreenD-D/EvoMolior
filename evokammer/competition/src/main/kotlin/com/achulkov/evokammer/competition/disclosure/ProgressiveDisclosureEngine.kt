package com.achulkov.evokammer.competition.disclosure

import com.achulkov.evokammer.core.contract.GroupCheckinResult
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.Serializable

/**
 * Controls the multi-stage task disclosure gating mechanism.
 *
 * Each group progresses through disclosure stages independently:
 * - Stage 0: Domain context only (problem space, no implementation hints)
 * - Stage N (N≥1): Unlocked only after the group passes Check-in N
 * - Stage content is constraint-framed (what NOT to do), not target-framed (what to achieve)
 * - Only the MetaPlanner sees goal-framed content at all stages
 *
 * ### Stage advancement
 * - After each check-in, [onCheckinComplete] is called with the group's [CheckinResult]
 * - If the group meets [CheckInThreshold], their stage counter increments
 * - Below threshold: stage stays, leak frequency increases, no stage advance
 *
 * @param stages Ordered list of [DisclosureStage]s for this run.
 * @param checkinThreshold Minimum pass score (0.0–1.0) required to advance a stage.
 * @param belowThresholdLeakMultiplier Leak frequency multiplier for below-threshold groups.
 */
class ProgressiveDisclosureEngine(
    private val stages: List<DisclosureStage>,
    private val checkinThreshold: Double = 0.6,
    private val belowThresholdLeakMultiplier: Double = 2.0
) {

    // Per-group current stage index
    private val groupStages = mutableMapOf<String, Int>()

    // Per-group leak multiplier (elevated when below threshold)
    private val groupLeakMultipliers = mutableMapOf<String, Double>()

    private val _stageAdvances = MutableSharedFlow<StageAdvance>(extraBufferCapacity = 64)

    /** Emitted when a group advances to a new disclosure stage. */
    val stageAdvances: SharedFlow<StageAdvance> = _stageAdvances.asSharedFlow()

    /**
     * Registers [groupId] into the engine at stage 0.
     */
    fun registerGroup(groupId: String) {
        groupStages[groupId] = 0
        groupLeakMultipliers[groupId] = 1.0
        Napier.d("ProgressiveDisclosureEngine: registered group $groupId at stage 0")
    }

    /**
     * Returns the [DisclosureStage] currently visible to [groupId].
     *
     * @throws IllegalArgumentException if [groupId] was never registered.
     */
    fun currentStage(groupId: String): DisclosureStage {
        val idx = requireStageIndex(groupId)
        return stages[idx]
    }

    /**
     * Returns all [DisclosureStage]s up to (and including) the group's current stage.
     * This is the full context visible to the group at any point in time.
     */
    fun visibleStages(groupId: String): List<DisclosureStage> {
        val idx = requireStageIndex(groupId)
        return stages.take(idx + 1)
    }

    /**
     * Returns the goal-framed content for [groupId]. Only MetaPlanner agents should call this.
     *
     * @throws IllegalArgumentException if [groupId] was never registered.
     */
    fun goalFramedContent(groupId: String): String {
        val idx = requireStageIndex(groupId)
        return stages[idx].goalFramedContent
    }

    /**
     * Returns the current leak frequency multiplier for [groupId].
     * Groups that failed check-ins get a higher multiplier (more frequent leaks from neighbours).
     */
    fun leakMultiplierFor(groupId: String): Double =
        groupLeakMultipliers.getOrDefault(groupId, 1.0)

    /**
     * Processes a [CheckinResult] for [groupId] and potentially advances their stage.
     *
     * Side effects:
     * - Stage increment if score >= [checkinThreshold]
     * - Leak multiplier increase if score < [checkinThreshold]
     * - Emits [StageAdvance] on [stageAdvances] if stage changed
     */
    suspend fun onCheckinComplete(groupId: String, checkin: GroupCheckinResult) {
        val currentIdx = requireStageIndex(groupId)
        val normalised = checkin.overallScore.coerceIn(0.0, 1.0)

        if (normalised >= checkinThreshold) {
            val nextIdx = (currentIdx + 1).coerceAtMost(stages.size - 1)
            if (nextIdx > currentIdx) {
                groupStages[groupId] = nextIdx
                groupLeakMultipliers[groupId] = 1.0 // reset multiplier on advance
                val advance = StageAdvance(
                    groupId = groupId,
                    fromStage = currentIdx,
                    toStage = nextIdx,
                    checkinScore = normalised
                )
                _stageAdvances.emit(advance)
                Napier.i("ProgressiveDisclosureEngine: $groupId advanced stage $currentIdx → $nextIdx " +
                        "(checkin score=$normalised)")
            } else {
                Napier.i("ProgressiveDisclosureEngine: $groupId passed check-in at stage $currentIdx " +
                        "but already at final stage")
            }
        } else {
            // Below threshold — increase leak multiplier
            val current = groupLeakMultipliers.getOrDefault(groupId, 1.0)
            val elevated = (current * belowThresholdLeakMultiplier).coerceAtMost(8.0)
            groupLeakMultipliers[groupId] = elevated
            Napier.w("ProgressiveDisclosureEngine: $groupId below threshold ($normalised < $checkinThreshold) " +
                    "at stage $currentIdx — leak multiplier elevated to $elevated")
        }
    }

    /** Returns the stage index for [groupId], or throws [IllegalArgumentException] if not registered. */
    private fun requireStageIndex(groupId: String): Int =
        groupStages[groupId]
            ?: throw IllegalArgumentException("ProgressiveDisclosureEngine: unknown groupId=$groupId — call registerGroup first")
}

/**
 * A single disclosure stage in the run curriculum.
 *
 * @param stageIndex The 0-based stage index.
 * @param title Short human-readable stage title.
 * @param constraintFramedContent What agents see: problem space, constraints, forbidden approaches.
 *                                Does NOT reveal the target outcome or solution.
 * @param goalFramedContent What MetaPlanner sees: the actual goal and evaluation criteria.
 * @param unlockCondition Human-readable description of what triggers stage unlock.
 * @param isInitial True if this is the Stage 0 (always visible, no unlock required).
 */
@Serializable
data class DisclosureStage(
    val stageIndex: Int,
    val title: String,
    val constraintFramedContent: String,
    val goalFramedContent: String,
    val unlockCondition: String = "",
    val isInitial: Boolean = (stageIndex == 0)
)

/**
 * Emitted when a group successfully advances a disclosure stage.
 *
 * @param groupId The group that advanced.
 * @param fromStage Previous stage index.
 * @param toStage New stage index.
 * @param checkinScore The check-in score that triggered the advance.
 */
@Serializable
data class StageAdvance(
    val groupId: String,
    val fromStage: Int,
    val toStage: Int,
    val checkinScore: Double
)

/**
 * A check-in schedule entry: maps a generation milestone to a check-in index.
 *
 * @param checkInIndex The check-in number (1-based).
 * @param atGeneration The generation number at which this check-in triggers.
 * @param description Human-readable description of what this check-in evaluates.
 */
@Serializable
data class CheckInScheduleEntry(
    val checkInIndex: Int,
    val atGeneration: Int,
    val description: String
)
