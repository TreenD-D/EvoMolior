package com.achulkov.evomolior.core.contract

import com.achulkov.evomolior.core.model.Role
import com.achulkov.evomolior.core.model.SubmissionType
import kotlinx.serialization.Serializable

/**
 * The payload submitted by an agent to the Staging Area.
 *
 * The Staging Area validates the [budgetSignature] before accepting the submission.
 * The budget signature is a signed token produced by the BudgetLedger after a successful
 * WRITE_STAGING deduction; without it, the staging area rejects the submission.
 *
 * @param submissionId Unique ID for this submission (UUID).
 * @param agentId The submitting agent.
 * @param role The agent's role at submission time.
 * @param type The type of content being submitted.
 * @param content The serialised content (JSON for structured types; plain text for DOC).
 * @param wipTaskRef Reference to the WiP task node this submission fulfils; null for death notes.
 * @param speculative True if this submission is explicitly marked as speculative/unverified.
 * @param outlier True if submitted by an Outlier role agent.
 * @param budgetSignature Signed token from BudgetLedger confirming WRITE_STAGING was deducted.
 * @param generation The generation in which this submission was made.
 * @param runId The run this submission belongs to.
 * @param groupId The group this submission belongs to.
 */
@Serializable
data class SubmissionPayload(
    val submissionId: String,
    val agentId: String,
    val role: Role,
    val type: SubmissionType,
    val content: String,
    val wipTaskRef: String?,
    val speculative: Boolean = false,
    val outlier: Boolean = false,
    val budgetSignature: String,
    val generation: Int,
    val runId: String,
    val groupId: String
)

/**
 * The result of a Critic review of a [SubmissionPayload].
 *
 * @param submissionId The submission that was reviewed.
 * @param outcome The Critic's decision.
 * @param criticAgentId The Critic agent that performed the review.
 * @param initialTrustScore Initial trust score assigned on promotion (0.0–1.0); null for non-promotions.
 * @param rejectionReason Human-readable reason if rejected.
 * @param disputeReason Human-readable reason if disputed.
 */
@Serializable
data class CriticReview(
    val submissionId: String,
    val outcome: CriticOutcome,
    val criticAgentId: String,
    val initialTrustScore: Double? = null,
    val rejectionReason: String? = null,
    val disputeReason: String? = null
)

/** The outcome of a Critic's review of a staged submission. */
@Serializable
enum class CriticOutcome {
    /** Submission is accepted and will be promoted to canonical substrate. */
    PROMOTE,

    /** Submission is rejected and will not enter canonical substrate. */
    REJECT,

    /**
     * Submission is disputed — a second Critic will review it.
     * On second rejection: final rejection. On second promotion: promoted with dispute note.
     */
    DISPUTE
}
