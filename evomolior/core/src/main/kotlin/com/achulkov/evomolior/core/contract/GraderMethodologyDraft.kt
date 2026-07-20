package com.achulkov.evomolior.core.contract

import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.grader.AdversarialCase
import com.achulkov.evomolior.core.grader.DisclosureSensitivity
import com.achulkov.evomolior.core.grader.FailureLevel
import com.achulkov.evomolior.core.grader.GraderNodeId
import kotlinx.serialization.Serializable

/** Prefix identifying a validated typed grader-methodology staging submission. */
const val GRADER_METHODOLOGY_DRAFT_SIGNATURE_PREFIX = "GRADING_SHEET_DRAFT:"

/**
 * Typed methodology authored by an isolated grader civilisation before judging starts.
 *
 * @param rubricDimensions Scoring guidance keyed to Council scoring-dimension IDs.
 * @param automatedTests Mechanically evaluated checks for canon snapshots.
 * @param failureModes Known deceptive or low-quality output patterns.
 * @param requirements Typed receiver- and worker-node-bound requirements.
 */
@Serializable
data class GraderMethodologyDraft(
    val rubricDimensions: List<GraderRubricDraft>,
    val automatedTests: List<GraderAutomatedTestDraft>,
    val failureModes: List<GraderFailureModeDraft>,
    val requirements: List<GraderRequirementDraft> = emptyList()
)

/**
 * Grader-authored requirement draft promoted into frozen Grader Atlas authority.
 *
 * @param requirementId Stable grader-local identity.
 * @param graderAtlasNodeId Governing Grader Atlas node.
 * @param mappedWorkerNodeIds Worker nodes governed by the requirement.
 * @param mappedReceiverObligationRefs Receiver obligations governed by the requirement.
 * @param criterion Falsifiable criterion.
 * @param observableEvidence Allowed observable evidence.
 * @param passThreshold Explicit pass threshold.
 * @param failureLevels Ordered failure severities.
 * @param adversarialCases Bounded adversarial cases.
 * @param executableTestIds Tests backing the requirement.
 * @param disclosureSensitivity Visibility classification.
 * @param rationale Receiver-relevant rationale.
 * @param noveltyFingerprint Deduplication/stronger-than commitment.
 * @param maximumEvaluationCost Bounded evaluation cost.
 */
@Serializable
data class GraderRequirementDraft(
    val requirementId: String,
    val graderAtlasNodeId: GraderNodeId,
    val mappedWorkerNodeIds: Set<NodeId>,
    val mappedReceiverObligationRefs: Set<String>,
    val criterion: String,
    val observableEvidence: String,
    val passThreshold: String,
    val failureLevels: List<FailureLevel>,
    val adversarialCases: List<AdversarialCase>,
    val executableTestIds: List<String>,
    val disclosureSensitivity: DisclosureSensitivity,
    val rationale: String,
    val noveltyFingerprint: String,
    val maximumEvaluationCost: Double
)

/** Grader-authored scoring guidance for one dimension. */
@Serializable
data class GraderRubricDraft(
    val dimensionId: String,
    val scoringGuidance: String
)

/**
 * Grader-authored deterministic snapshot check.
 *
 * @param testId Stable identifier within the sheet.
 * @param description Human-readable intent.
 * @param expectedOutputPattern Linear-time RE2/J expression matched against the canon snapshot.
 * @param penaltyOnFailure Score penalty recommendation.
 * @param isMandatory Whether failure should force all scores for the target to zero.
 * @param mappedRequirementIds Typed requirements executed by this test.
 * @param inputSchemaHash Exact input contract commitment.
 * @param expectedOutcomeHash Hidden expected-outcome commitment.
 * @param difficulty Optional authored difficulty estimate.
 */
@Serializable
data class GraderAutomatedTestDraft(
    val testId: String,
    val description: String,
    val expectedOutputPattern: String,
    val penaltyOnFailure: Double = 1.0,
    val isMandatory: Boolean = false,
    val mappedRequirementIds: Set<String> = emptySet(),
    val inputSchemaHash: String = "",
    val expectedOutcomeHash: String = "",
    val difficulty: Double? = null
)

/** Grader-authored failure pattern used by the RoundJudge. */
@Serializable
data class GraderFailureModeDraft(
    val modeId: String,
    val description: String,
    val penaltyWeight: Double = 0.5
)
