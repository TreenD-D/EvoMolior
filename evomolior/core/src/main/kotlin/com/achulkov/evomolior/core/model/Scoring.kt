package com.achulkov.evomolior.core.model

import com.achulkov.evomolior.core.contract.DstRefinementProposal
import com.achulkov.evomolior.core.contract.DstRubricPolicy
import com.achulkov.evomolior.core.atlas.NodeGradeBundle
import kotlinx.serialization.Serializable

/**
 * A single scoring dimension used to evaluate a group's civilisation at the end of a round.
 *
 * Dimensions are defined by the Genesis Council and stored in the RunConfig.
 *
 * @param id Unique dimension identifier (e.g. "kb_density")
 * @param name Human-readable name (e.g. "Knowledge Base Density")
 * @param description Full description shown to Graders.
 * @param weight Relative weight used only for scalar compatibility summaries.
 * @param maxScore Maximum raw score for this dimension (default 100.0).
 * @param dstNodeRef Optional deliverable-skeleton node reference that this dimension should
 *   update. Null means the dimension is global rather than node-specific.
 * @param higherIsBetter True when higher raw scores are better; false for risk/cost-style
 *   dimensions where lower raw scores dominate higher values on the Pareto front.
 */
@Serializable
data class ScoringDimension(
    val id: String,
    val name: String,
    val description: String,
    val weight: Double,
    val maxScore: Double = 100.0,
    val dstNodeRef: String? = null,
    val higherIsBetter: Boolean = true
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
 * @param rank Compatibility rank derived from Pareto front first, then scalar tie-break.
 * @param paretoFront Non-dominated front number (1 = non-dominated).
 * @param dominatedByGroupIds Groups that dominate this group across enabled dimensions.
 */
@Serializable
data class RoundScore(
    val groupId: String,
    val roundIndex: Int,
    val dimensionScores: List<DimensionScore>,
    val weightedTotal: Double,
    val rank: Int = 0,
    val paretoFront: Int = 0,
    val dominatedByGroupIds: List<String> = emptyList()
)

/**
 * Final grading result for a full evaluation round.
 *
 * @param roundIndex 0-based round index.
 * @param scores All group scores for this round (ranked).
 * @param winnerId Substantive winner, or null when scoring cannot discriminate between groups.
 * @param resolution Whether the round produced a winner or an unresolved scoring outcome.
 * @param paretoFronts Ordered non-dominated fronts; front 1 can contain multiple co-winners.
 * @param acceptedRefinements DST structural refinement proposals accepted by the Meta-Judge (Phase 7.5).
 * @param newRubricPolicies Rubric policies enacted this round (Phase 7.5).
 * @param dimensionNodeRefs Grader-authoritative mapping from dimension ID to deliverable node.
 * @param nodeGradeBundles Exact evidence-bound node grading authority. Generic dimensions are
 * never converted into these bundles.
 */
@Serializable
data class GradingResult(
    val roundIndex: Int,
    val scores: List<RoundScore>,
    val winnerId: String?,
    val resolution: ScoringResolution = ScoringResolution.RESOLVED,
    val acceptedRefinements: List<DstRefinementProposal> = emptyList(),
    val newRubricPolicies: List<DstRubricPolicy> = emptyList(),
    val paretoFronts: List<ParetoFront> = emptyList(),
    val dimensionNodeRefs: Map<String, String> = emptyMap(),
    val nodeGradeBundles: List<NodeGradeBundle> = emptyList()
) {
    init {
        require((winnerId != null) == (resolution == ScoringResolution.RESOLVED)) {
            "Resolved grading results require a winner; unresolved results must not declare one"
        }
    }
}

/**
 * Resolution state for a grading round.
 */
@Serializable
enum class ScoringResolution {
    /** The scoring evidence discriminated a substantive winner. */
    RESOLVED,

    /** Every group received the same non-zero aggregate score. */
    ALL_EQUAL_TIE,

    /** Every group received a zero aggregate score, so no positive evidence distinguished them. */
    ALL_ZERO_DEGENERACY
}

/**
 * A set of groups that are mutually non-dominated at the same Pareto depth.
 *
 * @param frontIndex 1-based front index.
 * @param groupIds Group IDs in deterministic tie-break order.
 */
@Serializable
data class ParetoFront(
    val frontIndex: Int,
    val groupIds: List<String>
)

/**
 * Canonical task-run scoring dimensions.
 */
object ScoringDimensionCatalog {
    const val CORRECTNESS = "correctness"
    const val EVIDENCE_QUALITY = "evidence_quality"
    const val NOVELTY = "novelty"
    const val ROBUSTNESS = "robustness"
    const val COST_EFFICIENCY = "cost_efficiency"
    const val SECURITY_RISK = "security_risk"
    const val IMPLEMENTATION_FEASIBILITY = "implementation_feasibility"
    const val TIME_TO_VALUE = "time_to_value"
    const val USER_RELEVANCE = "user_relevance"

    val all: List<ScoringDimension> = listOf(
        ScoringDimension(CORRECTNESS, "Correctness", "Factual, functional, and requirement-level correctness.", 1.0),
        ScoringDimension(EVIDENCE_QUALITY, "Evidence Quality", "Quality, traceability, and relevance of supporting evidence.", 1.0),
        ScoringDimension(NOVELTY, "Novelty", "Useful non-obviousness, original synthesis, and differentiated approach.", 1.0),
        ScoringDimension(ROBUSTNESS, "Robustness", "Resilience to edge cases, regressions, ambiguity, and operational failure.", 1.0),
        ScoringDimension(COST_EFFICIENCY, "Cost Efficiency", "Value delivered per token, runtime, tool call, and implementation cost.", 1.0),
        ScoringDimension(SECURITY_RISK, "Security Risk", "Residual security, privacy, and abuse risk. Lower is better.", 1.0, higherIsBetter = false),
        ScoringDimension(IMPLEMENTATION_FEASIBILITY, "Implementation Feasibility", "Practicality of implementing, integrating, maintaining, and verifying the deliverable.", 1.0),
        ScoringDimension(TIME_TO_VALUE, "Time-to-Value", "How quickly the deliverable can produce useful operator or user value.", 1.0),
        ScoringDimension(USER_RELEVANCE, "User Relevance", "Alignment with the user's actual goal, constraints, audience, and context.", 1.0)
    )

    private val byId = all.associateBy { it.id }

    /**
     * Resolves a canonical scoring dimension by stable identifier.
     *
     * @param id Dimension ID.
     * @return Matching canonical dimension, or null when [id] is bespoke.
     */
    fun byId(id: String): ScoringDimension? = byId[id]
}
