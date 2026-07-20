package com.achulkov.evomolior.core.grader

import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.atlas.NodeScoreVector
import kotlinx.serialization.Serializable

/** Stable identity of one evaluation-frontier node. */
@Serializable
@JvmInline
value class GraderNodeId(val value: String)

/** Stable identity of one typed grading requirement. */
@Serializable
@JvmInline
value class GradingRequirementId(val value: String)

/** Stable identity of one hidden executable test. */
@Serializable
@JvmInline
value class HiddenTestId(val value: String)

/** Evaluation-frontier categories represented by the competitive Grader Atlas. */
@Serializable
enum class GraderNodeKind {
    RECEIVER_COVERAGE,
    FAILURE_CHAIN_DEPTH,
    EVIDENCE_AUTHENTICITY,
    UNCERTAINTY_HANDLING,
    ADVERSARIAL_ROBUSTNESS,
    REPRODUCIBILITY,
    INTEGRATION_COHERENCE,
    TOOL_RESULT_VALIDITY,
    COST_BENEFIT_REALISM,
    ACCESSIBILITY,
    HIDDEN_TEST_DESIGN,
    RUBRIC_CALIBRATION,
    FALSE_POSITIVE_REDUCTION,
    NOVEL_FAILURE_DISCOVERY
}

/**
 * One immutable evaluation-frontier node.
 *
 * @param nodeId Stable node identity.
 * @param kind Evaluation capability represented by the node.
 * @param title Human-readable title.
 * @param evaluationQuestion Atomic question the grader work must answer.
 * @param receiverObligationRefs Receiver obligations governed by this node.
 * @param mappedWorkerNodeIds Worker nodes evaluated by this frontier.
 * @param prerequisiteNodeIds Grader nodes required before this node can be claimed.
 * @param requiredRequirementCount Minimum typed requirements needed for completion.
 * @param payloadHash Deterministic commitment excluding this field.
 */
@Serializable
data class GraderAtlasNode(
    val nodeId: GraderNodeId,
    val kind: GraderNodeKind,
    val title: String,
    val evaluationQuestion: String,
    val receiverObligationRefs: Set<String>,
    val mappedWorkerNodeIds: Set<NodeId>,
    val prerequisiteNodeIds: Set<GraderNodeId> = emptySet(),
    val requiredRequirementCount: Int = 1,
    val payloadHash: String = ""
) {
    /** Returns a copy carrying its deterministic payload hash. */
    fun withComputedHash(): GraderAtlasNode =
        copy(payloadHash = AtlasHashing.hashGraderAtlasNode(this))

    /** Returns whether the stored payload hash matches the semantic content. */
    fun hasValidHash(): Boolean =
        AtlasHashing.constantTimeEquals(payloadHash, AtlasHashing.hashGraderAtlasNode(this))
}

/**
 * Sealed evaluation-frontier authority for grader civilisations.
 *
 * @param graderAtlasId Stable Atlas identity.
 * @param runId Owning run.
 * @param version Append-only Atlas version.
 * @param workerAtlasHash Exact worker Atlas this evaluation frontier governs.
 * @param rootNodeIds Root grader nodes.
 * @param nodes Complete grader node map.
 * @param atlasHash Deterministic commitment excluding this field.
 */
@Serializable
data class GraderAtlas(
    val graderAtlasId: String,
    val runId: String,
    val version: Int,
    val workerAtlasHash: String,
    val rootNodeIds: Set<GraderNodeId>,
    val nodes: Map<GraderNodeId, GraderAtlasNode>,
    val atlasHash: String = ""
) {
    /** Returns a copy carrying its deterministic Atlas hash. */
    fun withComputedHash(): GraderAtlas =
        copy(atlasHash = AtlasHashing.hashGraderAtlas(this))

    /** Returns whether the stored Atlas and node hashes are valid. */
    fun hasValidHash(): Boolean =
        nodes.values.all(GraderAtlasNode::hasValidHash) &&
            AtlasHashing.constantTimeEquals(atlasHash, AtlasHashing.hashGraderAtlas(this))
}

/** Runtime status of one grader-frontier node. */
@Serializable
enum class GraderNodeStatus {
    LOCKED,
    OPEN,
    LEASED,
    REQUIREMENTS_COMMITTED,
    VALIDATED
}

/** Lifecycle status of one grader-node lease. */
@Serializable
enum class GraderNodeLeaseStatus {
    ACTIVE,
    COMMITTED,
    EXPIRED,
    ABANDONED
}

/**
 * Immutable grader-node lease revision.
 *
 * @param leaseId Stable lease identity.
 * @param runId Owning run.
 * @param graderAtlasHash Exact Grader Atlas authority.
 * @param nodeId Leased grader node.
 * @param graderGroupId Owning grader group.
 * @param acquiredAtEpochMillis Acquisition time.
 * @param expiresAtEpochMillis Hard expiry.
 * @param status Lease status.
 * @param leaseHash Deterministic commitment excluding this field.
 */
@Serializable
data class GraderNodeLease(
    val leaseId: String,
    val runId: String,
    val graderAtlasHash: String,
    val nodeId: GraderNodeId,
    val graderGroupId: String = "",
    val acquiredAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val status: GraderNodeLeaseStatus = GraderNodeLeaseStatus.ACTIVE,
    val leaseHash: String = ""
) {
    /** Returns a copy carrying its deterministic lease hash. */
    fun withComputedHash(): GraderNodeLease =
        copy(leaseHash = AtlasHashing.hashGraderNodeLease(this))

    /** Returns whether the stored hash matches the lease. */
    fun hasValidHash(): Boolean =
        AtlasHashing.constantTimeEquals(leaseHash, AtlasHashing.hashGraderNodeLease(this))
}

/**
 * Append-only execution snapshot for one Grader Atlas node.
 *
 * @param nodeId Governed grader node.
 * @param runId Owning run.
 * @param graderAtlasHash Exact Grader Atlas authority.
 * @param graderGroupId Grader group whose independent traversal this state governs.
 * @param revision Optimistic append revision.
 * @param status Current status.
 * @param leaseId Active lease identity.
 * @param leasedByGraderGroupId Active grader group.
 * @param leaseExpiresAtEpochMillis Lease expiry.
 * @param committedRequirementHashes Exact accepted requirement hashes.
 * @param stateHash Deterministic commitment excluding this field.
 */
@Serializable
data class GraderNodeExecutionState(
    val nodeId: GraderNodeId,
    val runId: String,
    val graderAtlasHash: String,
    val graderGroupId: String,
    val revision: Long,
    val status: GraderNodeStatus,
    val leaseId: String? = null,
    val leasedByGraderGroupId: String? = null,
    val leaseExpiresAtEpochMillis: Long? = null,
    val committedRequirementHashes: Set<String> = emptySet(),
    val stateHash: String = ""
) {
    /** Returns a copy carrying its deterministic state hash. */
    fun withComputedHash(): GraderNodeExecutionState =
        copy(stateHash = AtlasHashing.hashGraderNodeExecutionState(this))

    /** Returns whether the stored state hash matches the semantic content. */
    fun hasValidHash(): Boolean =
        AtlasHashing.constantTimeEquals(
            stateHash,
            AtlasHashing.hashGraderNodeExecutionState(this)
        )
}

/**
 * Redacted grader-frontier node visible to one grader civilisation.
 *
 * Future locked nodes and their prerequisite structure are intentionally absent.
 *
 * @param nodeId Exact governed grader-node identity.
 * @param kind Evaluation capability represented by the node.
 * @param title Human-readable title.
 * @param evaluationQuestion Atomic question the grader work must answer.
 * @param receiverObligationRefs Receiver obligations governed by this node.
 * @param mappedWorkerNodeIds Exact worker nodes evaluated by this frontier.
 * @param status Current execution status.
 * @param leasedByThisGroup Whether the receiving group owns the active lease.
 * @param activeLeaseId Exact active lease identity when one exists.
 * @param activeLeaseHash Exact active lease commitment when one exists.
 */
@Serializable
data class GraderViewNode(
    val nodeId: GraderNodeId,
    val kind: GraderNodeKind,
    val title: String,
    val evaluationQuestion: String,
    val receiverObligationRefs: Set<String>,
    val mappedWorkerNodeIds: Set<NodeId>,
    val status: GraderNodeStatus,
    val leasedByThisGroup: Boolean,
    val activeLeaseId: String? = null,
    val activeLeaseHash: String? = null
)

/**
 * Round-scoped redacted Grader Atlas view.
 *
 * @param runId Owning run.
 * @param graderAtlasHash Exact sealed authority.
 * @param graderGroupId Receiving isolated grader civilisation.
 * @param round Current grading round.
 * @param nodes Only currently revealable frontier nodes.
 */
@Serializable
data class GraderView(
    val runId: String,
    val graderAtlasHash: String,
    val graderGroupId: String,
    val round: Int,
    val nodes: List<GraderViewNode>
)

/** Requirement visibility classification used to prevent future-stage leakage. */
@Serializable
enum class DisclosureSensitivity {
    PUBLIC_CURRENT_STAGE,
    GRADER_INTERNAL,
    HIDDEN_TEST,
    FUTURE_STAGE
}

/** Provenance plane of a grading requirement. */
@Serializable
enum class GradingRequirementOrigin {
    GRADER_AUTHORED,
    COUNCIL_SEED,
    DETERMINISTIC_SEED
}

/**
 * One ordered failure severity and its observable condition.
 *
 * @param level Stable severity level.
 * @param description Observable failure.
 * @param scoreCeiling Maximum normalized score when this failure is present.
 * @param critical Whether the failure requires contest/tie-break handling.
 */
@Serializable
data class FailureLevel(
    val level: Int,
    val description: String,
    val scoreCeiling: Double,
    val critical: Boolean = false
)

/**
 * One adversarial evaluation case.
 *
 * @param caseId Stable case identity.
 * @param setup Allowed test setup.
 * @param expectedDetection Observable expected detection.
 * @param boundedCost Maximum execution cost.
 */
@Serializable
data class AdversarialCase(
    val caseId: String,
    val setup: String,
    val expectedDetection: String,
    val boundedCost: Double
)

/**
 * Typed, falsifiable evaluation requirement committed before worker reveal.
 *
 * @param requirementId Stable requirement identity.
 * @param runId Owning run.
 * @param graderAtlasHash Exact Grader Atlas authority.
 * @param graderAtlasNodeId Governing evaluation-frontier node.
 * @param graderGroupId Authoring grader group.
 * @param round Round in which the requirement applies.
 * @param mappedWorkerNodeIds Worker nodes governed by the requirement.
 * @param mappedReceiverObligationRefs Receiver obligations governed by the requirement.
 * @param criterion Falsifiable criterion.
 * @param observableEvidence Evidence allowed to satisfy or fail the criterion.
 * @param passThreshold Explicit pass threshold.
 * @param failureLevels Ordered failure severities.
 * @param adversarialCases Bounded adversarial cases.
 * @param executableTestIds Exact hidden or public executable tests.
 * @param disclosureSensitivity Visibility classification.
 * @param rationale Receiver-relevant rationale.
 * @param noveltyFingerprint Deduplication/stronger-than commitment.
 * @param maximumEvaluationCost Bounded cost.
 * @param origin Provenance plane.
 * @param requirementHash Deterministic commitment excluding this field.
 */
@Serializable
data class GradingRequirement(
    val requirementId: GradingRequirementId,
    val runId: String,
    val graderAtlasHash: String,
    val graderAtlasNodeId: GraderNodeId,
    val graderGroupId: String,
    val round: Int,
    val mappedWorkerNodeIds: Set<NodeId>,
    val mappedReceiverObligationRefs: Set<String>,
    val criterion: String,
    val observableEvidence: String,
    val passThreshold: String,
    val failureLevels: List<FailureLevel>,
    val adversarialCases: List<AdversarialCase>,
    val executableTestIds: List<HiddenTestId>,
    val disclosureSensitivity: DisclosureSensitivity,
    val rationale: String,
    val noveltyFingerprint: String,
    val maximumEvaluationCost: Double,
    val origin: GradingRequirementOrigin = GradingRequirementOrigin.GRADER_AUTHORED,
    val requirementHash: String = ""
) {
    /** Returns a copy carrying its deterministic requirement hash. */
    fun withComputedHash(): GradingRequirement =
        copy(requirementHash = AtlasHashing.hashGradingRequirement(this))

    /** Returns whether the stored hash matches the requirement. */
    fun hasValidHash(): Boolean =
        AtlasHashing.constantTimeEquals(
            requirementHash,
            AtlasHashing.hashGradingRequirement(this)
        )

    /** Whether this requirement may contribute to grader fitness or Legacy. */
    val isFitnessEligible: Boolean
        get() = origin != GradingRequirementOrigin.DETERMINISTIC_SEED
}

/** Lifecycle status of one hidden executable test. */
@Serializable
enum class HiddenTestStatus {
    DRAFT,
    SEALED,
    RETIRED
}

/**
 * Immutable hidden-test definition and measured quality.
 *
 * @param testId Stable test identity.
 * @param runId Owning run.
 * @param graderAtlasHash Exact Grader Atlas authority.
 * @param graderGroupId Authoring grader group.
 * @param roundCreated Creation round.
 * @param mappedRequirementIds Requirements executed by this test.
 * @param executorRef Sandboxed deterministic executor reference.
 * @param inputSchemaHash Exact input contract.
 * @param expectedOutcomeHash Hidden expected outcome commitment.
 * @param parentTestHash Mutation parent, when applicable.
 * @param mutationOperator Typed mutation label.
 * @param difficulty Normalized observed difficulty.
 * @param detectionRate Normalized failure-detection rate.
 * @param falsePositiveRate Normalized false-positive rate.
 * @param status Lifecycle status.
 * @param testHash Deterministic commitment excluding this field.
 */
@Serializable
data class HiddenTestDefinition(
    val testId: HiddenTestId,
    val runId: String,
    val graderAtlasHash: String,
    val graderGroupId: String,
    val roundCreated: Int,
    val mappedRequirementIds: Set<GradingRequirementId>,
    val executorRef: String,
    val inputSchemaHash: String,
    val expectedOutcomeHash: String,
    val parentTestHash: String? = null,
    val mutationOperator: String? = null,
    val difficulty: Double? = null,
    val detectionRate: Double? = null,
    val falsePositiveRate: Double? = null,
    val status: HiddenTestStatus = HiddenTestStatus.DRAFT,
    val testHash: String = ""
) {
    /** Returns a copy carrying its deterministic test hash. */
    fun withComputedHash(): HiddenTestDefinition =
        copy(testHash = AtlasHashing.hashHiddenTestDefinition(this))

    /** Returns whether the stored hash matches the test definition. */
    fun hasValidHash(): Boolean =
        AtlasHashing.constantTimeEquals(testHash, AtlasHashing.hashHiddenTestDefinition(this))
}

/**
 * Frozen round criteria committed before worker evidence is revealed.
 *
 * @param freezeId Stable freeze identity.
 * @param runId Owning run.
 * @param round Governed round.
 * @param graderGroupId Grader group whose criteria are frozen.
 * @param graderAtlasHash Exact Grader Atlas authority.
 * @param requirementHashes Exact requirements included.
 * @param hiddenTestHashes Exact sealed hidden tests included.
 * @param targetSnapshotHashes Sealed worker snapshot commitments by target group.
 * @param sealedAtEpochMillis Freeze time.
 * @param workerRevealEpochMillis First worker reveal time, if already known.
 * @param freezeHash Deterministic commitment excluding this field.
 */
@Serializable
data class GraderCriteriaFreeze(
    val freezeId: String,
    val runId: String,
    val round: Int,
    val graderGroupId: String,
    val graderAtlasHash: String,
    val requirementHashes: Set<String>,
    val hiddenTestHashes: Set<String>,
    val targetSnapshotHashes: Map<AtlasGroupId, String>,
    val sealedAtEpochMillis: Long,
    val workerRevealEpochMillis: Long? = null,
    val freezeHash: String = ""
) {
    /** Returns a copy carrying its deterministic freeze hash. */
    fun withComputedHash(): GraderCriteriaFreeze =
        copy(freezeHash = AtlasHashing.hashGraderCriteriaFreeze(this))

    /** Returns whether the stored hash and freeze ordering are valid. */
    fun hasValidHash(): Boolean =
        (workerRevealEpochMillis == null || sealedAtEpochMillis <= workerRevealEpochMillis) &&
            AtlasHashing.constantTimeEquals(
                freezeHash,
                AtlasHashing.hashGraderCriteriaFreeze(this)
            )
}

/** Explicit status of a bounded group-dimension score. */
@Serializable
enum class BoundedScoreStatus {
    SCORED,
    NOT_APPLICABLE
}

/** One bounded group score with explicit applicability. */
@Serializable
data class BoundedScore(
    val value: Double? = null,
    val minimum: Double = 0.0,
    val maximum: Double = 1.0,
    val status: BoundedScoreStatus = BoundedScoreStatus.SCORED
)

/** Typed outcome of one frozen requirement. */
@Serializable
enum class RequirementResultStatus {
    PASS,
    FAIL,
    NOT_APPLICABLE,
    CONTESTED,
    INVALID
}

/**
 * One requirement result.
 *
 * @param status Explicit result status.
 * @param observedEvidenceHashes Exact evidence commitments.
 * @param failureLevel Applied failure severity.
 * @param criticalFailure Whether this result claims a critical failure.
 * @param executableTestResultHashes Exact test execution commitments.
 */
@Serializable
data class RequirementResult(
    val status: RequirementResultStatus,
    val observedEvidenceHashes: Set<String> = emptySet(),
    val failureLevel: Int? = null,
    val criticalFailure: Boolean = false,
    val executableTestResultHashes: Set<String> = emptySet()
)

/** Exact score-bundle authority status. */
@Serializable
enum class GraderScoreBundleStatus {
    VALID,
    INVALID,
    ABSTAINED
}

/**
 * Persisted normalization step used to replay one score bundle.
 *
 * @param sourceId Requirement, node, or dimension identity.
 * @param rawValue Raw finite value.
 * @param normalizedValue Normalized value.
 * @param weight Applied sealed weight.
 * @param policyHash Exact normalization policy.
 */
@Serializable
data class ScoreNormalizationTrace(
    val sourceId: String,
    val rawValue: Double,
    val normalizedValue: Double,
    val weight: Double,
    val policyHash: String
)

/**
 * Exact-cardinality score bundle from one grader group to one target group.
 *
 * @param bundleId Stable bundle identity.
 * @param runId Owning run.
 * @param round Governed round.
 * @param graderGroupId Authoring grader group.
 * @param targetGroupId Target worker group.
 * @param graderAtlasHash Exact Grader Atlas authority.
 * @param criteriaFreezeHash Exact frozen criteria authority.
 * @param targetSnapshotHash Exact sealed worker snapshot.
 * @param nodeScores Exact required node vectors.
 * @param groupScores Exact required broad dimensions.
 * @param requirementResults Exact frozen requirement outcomes.
 * @param rationaleHashes Rationale commitments by requirement or score.
 * @param normalizationTrace Complete replay trace.
 * @param status Explicit bundle status.
 * @param payloadHash Deterministic commitment excluding this field.
 */
@Serializable
data class GraderScoreBundle(
    val bundleId: String,
    val runId: String,
    val round: Int,
    val graderGroupId: String,
    val targetGroupId: AtlasGroupId,
    val graderAtlasHash: String,
    val criteriaFreezeHash: String,
    val targetSnapshotHash: String,
    val nodeScores: Map<NodeId, NodeScoreVector>,
    val groupScores: Map<String, BoundedScore>,
    val requirementResults: Map<GradingRequirementId, RequirementResult>,
    val rationaleHashes: Map<String, String>,
    val normalizationTrace: List<ScoreNormalizationTrace>,
    val status: GraderScoreBundleStatus,
    val validationFailures: Set<GraderBundleValidationFailure> = emptySet(),
    val payloadHash: String = ""
) {
    /** Returns a copy carrying its deterministic payload hash. */
    fun withComputedHash(): GraderScoreBundle =
        copy(payloadHash = AtlasHashing.hashGraderScoreBundle(this))

    /** Returns whether the stored hash matches the complete bundle. */
    fun hasValidHash(): Boolean =
        AtlasHashing.constantTimeEquals(payloadHash, AtlasHashing.hashGraderScoreBundle(this))
}

/** Machine-readable predicates that made an exact grader bundle invalid. */
@Serializable
enum class GraderBundleValidationFailure {
    TARGET_SNAPSHOT_MISMATCH,
    ASSIGNMENT_RESPONSE_STRUCTURE_INVALID,
    ASSIGNMENT_RESPONSE_AUTHORITY_INVALID,
    DIMENSION_POLICY_INVALID,
    DIMENSIONS_INCOMPLETE,
    HIDDEN_TEST_RESULTS_INCOMPLETE,
    ALL_REQUIREMENTS_NOT_APPLICABLE,
    NORMALIZATION_TRACE_MISSING,
    RATIONALE_HASHES_MISSING
}

/** Multi-objective grader-quality vector; no raw requirement-count objective exists. */
@Serializable
data class GraderQualityVector(
    val breadth: Double,
    val depth: Double,
    val robustness: Double,
    val creativity: Double,
    val executability: Double,
    val calibration: Double,
    val discrimination: Double,
    val predictiveValidity: Double,
    val parsimony: Double,
    val actionability: Double
)

/** Plane containing a grader-quality observation. */
@Serializable
enum class GraderFitnessPlane {
    REQUIREMENT_DISCOVERY,
    JUDGMENT_QUALITY
}

/**
 * Immutable grader fitness observation.
 *
 * @param recordId Stable record identity.
 * @param runId Owning run.
 * @param round Observation round.
 * @param graderGroupId Evaluated grader group.
 * @param plane Competition plane.
 * @param quality Multi-objective quality vector.
 * @param eligibleRequirementHashes Only non-deterministic authority.
 * @param deterministicSeedHashes Explicit excluded seed hashes.
 * @param aggregateScore Sealed-policy aggregate for selection only.
 * @param policyHash Exact aggregation policy.
 * @param recordHash Deterministic commitment excluding this field.
 */
@Serializable
data class GraderFitnessRecord(
    val recordId: String,
    val runId: String,
    val round: Int,
    val graderGroupId: String,
    val plane: GraderFitnessPlane,
    val quality: GraderQualityVector,
    val eligibleRequirementHashes: Set<String>,
    val deterministicSeedHashes: Set<String>,
    val aggregateScore: Double,
    val policyHash: String,
    val recordHash: String = ""
) {
    /** Returns a copy carrying its deterministic record hash. */
    fun withComputedHash(): GraderFitnessRecord =
        copy(recordHash = AtlasHashing.hashGraderFitnessRecord(this))

    /** Returns whether the stored hash matches the record. */
    fun hasValidHash(): Boolean =
        eligibleRequirementHashes.intersect(deterministicSeedHashes).isEmpty() &&
            AtlasHashing.constantTimeEquals(
                recordHash,
                AtlasHashing.hashGraderFitnessRecord(this)
            )
}

/** Evaluation-independence classification reported with every run. */
@Serializable
enum class IndependenceLabel {
    LOW_SINGLE_FAMILY,
    MODERATE_PROMPT_DIVERSE,
    HIGH_CROSS_FAMILY,
    CALIBRATED_EXTERNAL
}

/**
 * Persisted evaluation-independence profile.
 *
 * @param distinctModelFamilies Number of distinct worker/grader model families.
 * @param distinctProviders Number of distinct providers.
 * @param sharedPromptTemplateFraction Fraction of prompt templates shared across evaluators.
 * @param workerGraderFamilyOverlap Fractional family overlap between worker and grader roles.
 * @param independenceLabel Derived label.
 * @param profileHash Deterministic commitment excluding this field.
 */
@Serializable
data class EvaluationIndependenceProfile(
    val distinctModelFamilies: Int,
    val distinctProviders: Int,
    val sharedPromptTemplateFraction: Double,
    val workerGraderFamilyOverlap: Double,
    val independenceLabel: IndependenceLabel,
    val profileHash: String = ""
) {
    /** Returns a copy carrying its deterministic profile hash. */
    fun withComputedHash(): EvaluationIndependenceProfile =
        copy(profileHash = AtlasHashing.hashEvaluationIndependenceProfile(this))

    /** Returns whether the stored hash matches the profile. */
    fun hasValidHash(): Boolean =
        AtlasHashing.constantTimeEquals(
            profileHash,
            AtlasHashing.hashEvaluationIndependenceProfile(this)
        )
}
