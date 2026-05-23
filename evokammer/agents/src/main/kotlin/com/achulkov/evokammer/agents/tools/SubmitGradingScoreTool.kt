package com.achulkov.evokammer.agents.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.typeToken
import com.achulkov.evokammer.core.model.DimensionScore
import kotlinx.serialization.Serializable

/**
 * Koog tool for Grader agents to submit a scored dimension for a competing group.
 *
 * Each [SubmitGradingScoreTool] call records one [DimensionScore] entry for one target group
 * on one dimension. Grader agents should call this once per dimension per group they evaluate.
 *
 * The submitted scores are collected by the [GraderRunner] via an injected callback and
 * forwarded to [GraderGroupManager.submitScores].
 *
 * This tool is FREE — it does not deduct from the grader agent's budget, since score
 * submission is the grader's primary output (penalising the submission itself would
 * incentivise graders to withhold scores).
 *
 * @param graderGroupId The grader group that this agent belongs to.
 * @param onScoreSubmitted Callback invoked for each valid score submission.
 */
class SubmitGradingScoreTool(
    private val graderGroupId: String,
    private val onScoreSubmitted: (targetGroupId: String, score: DimensionScore) -> Unit
) : SimpleTool<SubmitGradingScoreTool.Args>(
    argsType = typeToken<Args>(),
    name = "submit_grading_score",
    description = """
        Submit a dimension score for a competing group. Call this once per dimension per group.
        
        Arguments:
        - targetGroupId: The ID of the group being scored (NOT your own grader group ID).
        - dimensionId: The scoring dimension ID (e.g. "kb_density", "kg_coherence", "tool_utility").
        - rawScore: Numeric score from 0.0 to 100.0. Be calibrated — 50.0 is average.
        - rationale: 1–3 sentences justifying the score based on the KB snapshot evidence.
        
        This tool is FREE (no budget deduction).
        Call this for EVERY dimension for EVERY competing group you are evaluating.
        You MUST call write_death_note as your final action.
    """.trimIndent()
) {
    @Serializable
    data class Args(
        /** The ID of the group being scored. */
        val targetGroupId: String,
        /** The scoring dimension ID (must match a dimension from the run config). */
        val dimensionId: String,
        /** Raw score 0.0–100.0. */
        val rawScore: Double,
        /** Justification / rationale for the score. */
        val rationale: String
    )

    override suspend fun execute(args: Args): String {
        if (args.targetGroupId.isBlank()) {
            return "Error: targetGroupId must not be blank."
        }
        if (args.dimensionId.isBlank()) {
            return "Error: dimensionId must not be blank."
        }
        if (args.rawScore !in 0.0..100.0) {
            return "Error: rawScore must be between 0.0 and 100.0 (got ${args.rawScore})."
        }
        if (args.rationale.isBlank()) {
            return "Error: rationale must not be blank."
        }

        val score = DimensionScore(
            dimensionId = args.dimensionId,
            rawScore = args.rawScore,
            justification = args.rationale
        )

        onScoreSubmitted(args.targetGroupId, score)

        return "Score recorded: ${args.targetGroupId} | ${args.dimensionId} = ${
            String.format(java.util.Locale.ROOT, "%.1f", args.rawScore)
        }. Rationale: ${args.rationale.take(120)}"
    }
}
