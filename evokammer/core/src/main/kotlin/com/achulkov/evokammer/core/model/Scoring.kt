package com.achulkov.evokammer.core.model

import com.achulkov.evokammer.core.contract.DstRefinementProposal
import com.achulkov.evokammer.core.contract.DstRubricPolicy
import kotlinx.serialization.Serializable

/**
 * A single scoring dimension used to evaluate a group's civilisation at the end of a round.
 *
 * Dimensions are defined by the Genesis Council and stored in the RunConfig.
 *
 * @param id Unique dimension identifier (e.g. "kb_density")
 * @param name Human-readable name (e.g. "Knowledge Base Density")
 * @param description Full description shown to Graders.
 * @param weight Relative weight in the final score (0.0–1.0, all weights should sum to 1.0).
 * @param maxScore Maximum raw score for this dimension (default 100.0).
 * @param dstNodeRef Optional deliverable-skeleton node reference that this dimension should
 *   update. Null means the dimension is global rather than node-specific.
 */
@Serializable
data class ScoringDimension(
    val id: String,
    val name: String,
    val description: String,
    val weight: Double,
    val maxScore: Double = 100.0,
    val dstNodeRef: String? = null
) {
    init {
        require(weight > 0.0) { "Dimension weight must be positive, got $weight for '$id'" }
        require(maxScore > 0.0) { "maxScore must be positive, got $maxScore for '$id'" }
    }
}

/**
 * A raw score assigned by a Grader to one dimension for one group in one round.
 */
@Serializable
data class DimensionScore(
    val dimensionId: String,
    val rawScore: Double,
    val justification: String
)

/**
 * Final aggregated score for one group in one round.
 *
 * @param groupId The competing group.
 * @param roundIndex 0-based round index.
 * @param dimensionScores Per-dimension raw scores.
 * @param weightedTotal The final weighted total (0.0–100.0).
 * @param rank Rank among all groups for this round (1 = best).
 */
@Serializable
data class RoundScore(
    val groupId: String,
    val roundIndex: Int,
    val dimensionScores: List<DimensionScore>,
    val weightedTotal: Double,
    val rank: Int = 0
)

/**
 * Final grading result for a full evaluation round.
 *
 * @param roundIndex 0-based round index.
 * @param scores All group scores for this round (ranked).
 * @param winnerId The group with rank = 1 for this round.
 * @param acceptedRefinements DST structural refinement proposals accepted by the Meta-Judge (Phase 7.5).
 * @param newRubricPolicies Rubric policies enacted this round (Phase 7.5).
 */
@Serializable
data class GradingResult(
    val roundIndex: Int,
    val scores: List<RoundScore>,
    val winnerId: String,
    val acceptedRefinements: List<DstRefinementProposal> = emptyList(),
    val newRubricPolicies: List<DstRubricPolicy> = emptyList()
)
