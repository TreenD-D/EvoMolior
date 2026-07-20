package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.core.model.GradingResult
import com.achulkov.evomolior.core.model.RoundScore
import com.achulkov.evomolior.core.model.ScoringResolution

private const val DISCRIMINATOR_MAX_SCORE_LINES = 3
private const val DISCRIMINATOR_MAX_HEADLINE_CHARS = 220

internal fun buildAdversarialDiscriminatorBriefs(
    gradingResult: GradingResult,
    submissionSummariesByGroup: Map<String, String?>
): Map<String, String> = gradingResult.scores.associate { roundScore ->
    roundScore.groupId to buildAdversarialDiscriminatorBrief(
        roundScore = roundScore,
        winnerId = gradingResult.winnerId,
        resolution = gradingResult.resolution,
        submissionSummary = submissionSummariesByGroup[roundScore.groupId]
    )
}

internal fun buildAdversarialDiscriminatorBrief(
    roundScore: RoundScore,
    winnerId: String?,
    submissionSummary: String?,
    resolution: ScoringResolution = ScoringResolution.RESOLVED
): String = buildString {
    val weakestDimensions = roundScore.dimensionScores
        .sortedBy { it.rawScore }
        .take(DISCRIMINATOR_MAX_SCORE_LINES)

    appendLine("Round ${roundScore.roundIndex + 1} adversarial discriminator brief")
    appendLine("Group: ${roundScore.groupId}")
    appendOutcome(resolution, winnerId, roundScore.rank)
    appendLine("Weighted total: ${String.format(java.util.Locale.ROOT, "%.2f", roundScore.weightedTotal)}")
    appendDegeneracyEvidenceGap(resolution)
    appendLine("Weakest dimensions:")
    weakestDimensions.forEach { score ->
        appendLine(
            "- ${score.dimensionId}: " +
                "${String.format(java.util.Locale.ROOT, "%.1f", score.rawScore)} :: " +
                score.evidenceHeadline()
        )
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

internal fun buildGraderFeedbackBriefs(
    gradingResult: GradingResult
): Map<String, String> = gradingResult.scores.associate { roundScore ->
    roundScore.groupId to buildGraderFeedbackBrief(
        roundScore,
        gradingResult.winnerId,
        gradingResult.resolution
    )
}

internal fun buildGraderFeedbackBrief(
    roundScore: RoundScore,
    winnerId: String?,
    resolution: ScoringResolution = ScoringResolution.RESOLVED
): String = buildString {
    val weakestDimensions = roundScore.dimensionScores
        .sortedBy { it.rawScore }
        .take(DISCRIMINATOR_MAX_SCORE_LINES)

    appendLine("Round ${roundScore.roundIndex + 1} grader-group feedback")
    appendLine("Group: ${roundScore.groupId}")
    appendOutcome(resolution, winnerId, roundScore.rank)
    appendLine("Weighted total: ${String.format(java.util.Locale.ROOT, "%.2f", roundScore.weightedTotal)}")
    appendDegeneracyEvidenceGap(resolution)
    appendLine("Next-round improvement constraints:")
    weakestDimensions.forEach { score ->
        appendLine(
            "- Improve ${score.dimensionId}: " +
                "current=${String.format(java.util.Locale.ROOT, "%.1f", score.rawScore)}; " +
                "evidence=${score.evidenceHeadline()}"
        )
    }
    appendLine()
    appendLine("Treat these as grader-authored constraints for the next worker round. Produce substrate artifacts that make each correction checkable.")
}

private fun StringBuilder.appendOutcome(
    resolution: ScoringResolution,
    winnerId: String?,
    compatibilityRank: Int
) {
    when (resolution) {
        ScoringResolution.RESOLVED ->
            appendLine("Rank: $compatibilityRank / winner=${winnerId ?: "unresolved"}")
        ScoringResolution.ALL_EQUAL_TIE ->
            appendLine(
                "Outcome: unresolved all-equal scoring tie; no substantive winner. " +
                    "Compatibility rank $compatibilityRank is non-substantive."
            )
        ScoringResolution.ALL_ZERO_DEGENERACY ->
            appendLine(
                "Outcome: unresolved all-zero scoring degeneracy; no substantive winner. " +
                    "Compatibility rank $compatibilityRank is non-substantive."
            )
    }
}

private fun StringBuilder.appendDegeneracyEvidenceGap(resolution: ScoringResolution) {
    when (resolution) {
        ScoringResolution.RESOLVED -> Unit
        ScoringResolution.ALL_EQUAL_TIE -> appendLine(
            "Missing discriminatory evidence: aggregate scores contain no between-group separation. " +
                "Require artifact citations, dimension-specific grader observations, or test outcomes " +
                "that distinguish the submissions."
        )
        ScoringResolution.ALL_ZERO_DEGENERACY -> appendLine(
            "Missing discriminatory evidence: no group received positive scoring evidence. Require " +
                "artifact citations, executed test outcomes, and dimension-specific grader observations " +
                "before declaring a winner."
        )
    }
}

private fun com.achulkov.evomolior.core.model.DimensionScore.evidenceHeadline(): String =
    justification
        .lineSequence()
        .firstOrNull()
        .orEmpty()
        .trim()
        .take(DISCRIMINATOR_MAX_HEADLINE_CHARS)
        .ifBlank {
            "Missing evidence: no dimension-specific grader rationale or artifact citation was supplied."
        }
