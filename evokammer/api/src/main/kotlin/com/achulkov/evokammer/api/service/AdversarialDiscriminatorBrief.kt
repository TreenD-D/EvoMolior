package com.achulkov.evokammer.api.service

import com.achulkov.evokammer.core.model.GradingResult
import com.achulkov.evokammer.core.model.RoundScore

private const val DISCRIMINATOR_MAX_SCORE_LINES = 3
private const val DISCRIMINATOR_MAX_HEADLINE_CHARS = 220

internal fun buildAdversarialDiscriminatorBriefs(
    gradingResult: GradingResult,
    submissionSummariesByGroup: Map<String, String?>
): Map<String, String> = gradingResult.scores.associate { roundScore ->
    roundScore.groupId to buildAdversarialDiscriminatorBrief(
        roundScore = roundScore,
        winnerId = gradingResult.winnerId,
        submissionSummary = submissionSummariesByGroup[roundScore.groupId]
    )
}

internal fun buildAdversarialDiscriminatorBrief(
    roundScore: RoundScore,
    winnerId: String,
    submissionSummary: String?
): String = buildString {
    val weakestDimensions = roundScore.dimensionScores
        .sortedBy { it.rawScore }
        .take(DISCRIMINATOR_MAX_SCORE_LINES)

    appendLine("Round ${roundScore.roundIndex + 1} adversarial discriminator brief")
    appendLine("Group: ${roundScore.groupId}")
    appendLine("Rank: ${roundScore.rank} / winner=$winnerId")
    appendLine("Weighted total: ${String.format(java.util.Locale.ROOT, "%.2f", roundScore.weightedTotal)}")
    appendLine("Weakest dimensions:")
    weakestDimensions.forEach { score ->
        val rationale = score.justification
            .lineSequence()
            .firstOrNull()
            .orEmpty()
            .trim()
            .take(DISCRIMINATOR_MAX_HEADLINE_CHARS)
        appendLine("- ${score.dimensionId}: ${String.format(java.util.Locale.ROOT, "%.1f", score.rawScore)} :: ${rationale.ifBlank { "no short rationale" }}")
    }
    submissionSummary
        ?.takeIf { it.isNotBlank() }
        ?.let { summary ->
            appendLine()
            appendLine("Submission summary headline:")
            appendLine(summary.lineSequence().firstOrNull().orEmpty().take(DISCRIMINATOR_MAX_HEADLINE_CHARS))
        }
    appendLine()
    appendLine("Use this as failure-focused context for the next generator round. Prefer correcting the weakest dimensions above.")
}
