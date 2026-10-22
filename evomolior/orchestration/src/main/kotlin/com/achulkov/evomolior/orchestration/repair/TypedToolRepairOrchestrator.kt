package com.achulkov.evomolior.orchestration.repair

import com.achulkov.evomolior.core.atlas.ToolOpportunityCommitment
import com.achulkov.evomolior.core.contract.CriticOutcome
import com.achulkov.evomolior.core.contract.CriticReview
import com.achulkov.evomolior.core.repair.ToolRepairDiagnosticV1
import com.achulkov.evomolior.core.repair.ToolSemanticReview
import com.achulkov.evomolior.core.repair.ToolSemanticVerdict

/** Typed Critic decision plus safe delayed diagnostics produced by one TOOL review. */
data class TypedToolCriticDecision(
    val criticReview: CriticReview,
    val repairDiagnostics: List<ToolRepairDiagnosticV1>
)

/** In-memory orchestration boundary connecting typed Critic output to repair authority. */
class TypedToolRepairOrchestrator(
    private val compatibilityValidator: ToolSemanticCompatibilityValidator =
        ToolSemanticCompatibilityValidator()
) {
    /**
     * Validates and compiles one semantic review into the existing Critic decision flow.
     *
     * @param submissionId Staged TOOL identity.
     * @param criticId Typed Critic identity.
     * @param semanticReview Closed semantic output.
     * @param commitment Exact public opportunity authority.
     * @param candidateHash Candidate content commitment.
     * @param groupId Owning group.
     * @param round Review round.
     */
    fun compileCriticDecision(
        submissionId: String,
        criticId: String,
        semanticReview: ToolSemanticReview,
        commitment: ToolOpportunityCommitment,
        candidateHash: String,
        groupId: String,
        round: Int
    ): TypedToolCriticDecision {
        val compatibility = compatibilityValidator.validate(semanticReview, commitment)
        if (compatibility is ToolSemanticCompatibilityResult.Rejected) {
            return TypedToolCriticDecision(
                criticReview = CriticReview(
                    submissionId = submissionId,
                    outcome = CriticOutcome.DISPUTE,
                    criticAgentId = criticId,
                    disputeReason =
                        "typed_tool_semantic_review_incompatible:${compatibility.reason.name}"
                ),
                repairDiagnostics = emptyList()
            )
        }
        val diagnostics = compatibilityValidator.compileDiagnostics(
            review = semanticReview,
            commitment = commitment,
            candidateHash = candidateHash,
            groupId = groupId,
            createdRound = round
        )
        val review = when (semanticReview.verdict) {
            ToolSemanticVerdict.PROMOTE -> CriticReview(
                submissionId = submissionId,
                outcome = CriticOutcome.PROMOTE,
                criticAgentId = criticId,
                initialTrustScore = 0.7
            )

            ToolSemanticVerdict.REJECT -> CriticReview(
                submissionId = submissionId,
                outcome = CriticOutcome.REJECT,
                criticAgentId = criticId,
                rejectionReason = "typed_tool_semantic_rejected:" +
                    semanticReview.safeRepairCodes.sortedBy(Enum<*>::name)
                        .joinToString(",") { it.name }
            )

            ToolSemanticVerdict.DISPUTE -> CriticReview(
                submissionId = submissionId,
                outcome = CriticOutcome.DISPUTE,
                criticAgentId = criticId,
                disputeReason = "typed_tool_semantic_dispute"
            )
        }
        return TypedToolCriticDecision(review, diagnostics)
    }
}
