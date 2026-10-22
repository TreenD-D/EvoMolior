package com.achulkov.evomolior.core.atlas

import kotlinx.serialization.Serializable

/**
 * Immutable cost incurred while producing one node evidence packet.
 *
 * @param totalCredits Total normalized resource cost.
 * @param llmTokens LLM tokens consumed.
 * @param toolExecutions Successful and failed tool executions.
 * @param sourceFetches Approved source fetches.
 * @param wallClockMillis Measured elapsed execution time.
 * @param resourceCosts Additional typed costs keyed by resource identifier.
 */
@Serializable
data class NodeEvidenceCostSummary(
    val totalCredits: Double,
    val llmTokens: Long = 0,
    val toolExecutions: Int = 0,
    val sourceFetches: Int = 0,
    val wallClockMillis: Long = 0,
    val resourceCosts: Map<String, Double> = emptyMap()
)

/** Authority class represented in a node evidence provenance commitment. */
@Serializable
enum class NodeEvidenceAuthorityType {
    CRITIC_PROMOTION,
    CONTRIBUTION_COMMIT,
    TOOL_VERSION,
    TOOL_RUN,
    TEST_RESULT,
    NEGATIVE_RESULT
}

/**
 * One exact accepted-reference authority included in a packet's provenance root.
 *
 * @param authorityType Authority class.
 * @param referenceId Evidence reference controlled by the authority.
 * @param authorityId Stable authority record identity.
 * @param authorityHash Exact authority record commitment.
 */
@Serializable
data class NodeEvidenceProvenanceEntry(
    val authorityType: NodeEvidenceAuthorityType,
    val referenceId: String,
    val authorityId: String,
    val authorityHash: String
)

/**
 * Canonical provenance manifest committed by [NodeEvidencePacket.provenanceRootHash].
 *
 * @param runId Run owning every entry.
 * @param round Round represented by every contribution authority.
 * @param groupId Group owning every entry.
 * @param nodeId Node proved by every entry.
 * @param entries Exact accepted-reference authorities.
 */
@Serializable
data class NodeEvidenceProvenanceManifest(
    val runId: String,
    val round: Int,
    val groupId: AtlasGroupId,
    val nodeId: NodeId,
    val entries: List<NodeEvidenceProvenanceEntry>
)

/**
 * Exact Critic-accepted unit evaluated by node graders.
 *
 * All referenced material must belong to the same run, group, Atlas version, and node. The packet
 * hash excludes [packetHash].
 *
 * @param packetId Stable packet identity.
 * @param runId Run owning the evidence.
 * @param round Round in which the packet was sealed.
 * @param groupId Group submitting the evidence.
 * @param nodeId Node proved by the evidence.
 * @param atlasVersion Exact Atlas version used during work.
 * @param atlasHash Exact sealed Atlas hash.
 * @param nodePayloadHash Exact hidden node payload commitment.
 * @param acceptedArtifactIds Critic-accepted artifact references.
 * @param acceptedClaimIds Critic-accepted claim references.
 * @param toolRunIds Accepted tool execution references.
 * @param testResultIds Accepted executable-test references.
 * @param negativeResultIds Accepted structured negative-result references.
 * @param provenanceRootHash Root commitment of the referenced provenance graph.
 * @param cost Cost incurred while producing the evidence.
 * @param sealedAtEpochMillis Packet sealing time for audit, not competitive ordering.
 * @param packetHash Deterministic packet hash excluding this field.
 */
@Serializable
data class NodeEvidencePacket(
    val packetId: String,
    val runId: String,
    val round: Int,
    val groupId: AtlasGroupId,
    val nodeId: NodeId,
    val atlasVersion: Int,
    val atlasHash: String,
    val nodePayloadHash: String,
    val acceptedArtifactIds: List<String> = emptyList(),
    val acceptedClaimIds: List<String> = emptyList(),
    val toolRunIds: List<String> = emptyList(),
    val testResultIds: List<String> = emptyList(),
    val negativeResultIds: List<String> = emptyList(),
    val provenanceRootHash: String,
    val cost: NodeEvidenceCostSummary,
    val sealedAtEpochMillis: Long,
    val packetHash: String = ""
) {
    /** Returns a copy carrying its deterministic self-hash. */
    fun withComputedHash(): NodeEvidencePacket =
        copy(packetHash = AtlasHashing.hashNodeEvidencePacket(this))

    /** Returns whether the packet's stored self-hash matches its complete semantic content. */
    fun hasValidHash(): Boolean =
        AtlasHashing.constantTimeEquals(packetHash, AtlasHashing.hashNodeEvidencePacket(this))
}

/**
 * Complete node-quality vector retained before policy weighting.
 *
 * @param correctness Factual or behavioral correctness.
 * @param evidenceQuality Strength and relevance of supporting evidence.
 * @param reproducibility Ability for an independent group to reproduce the result.
 * @param robustness Performance under perturbation and adversarial checks.
 * @param integrationUtility Value to dependent nodes and the final deliverable.
 * @param receiverRelevance Direct value to the sealed receiver contract.
 * @param novelty Non-redundant insight or capability.
 * @param efficiency Value delivered relative to cost.
 * @param hardFailure Whether a committed hard-failure condition was triggered.
 * @param confidence Calibrated confidence in the vector.
 */
@Serializable
data class NodeScoreVector(
    val correctness: Double,
    val evidenceQuality: Double,
    val reproducibility: Double,
    val robustness: Double,
    val integrationUtility: Double,
    val receiverRelevance: Double,
    val novelty: Double,
    val efficiency: Double,
    val hardFailure: Boolean,
    val confidence: Double
)

/**
 * Frozen normalized weights used to reduce a complete node vector to a comparison score.
 *
 * The weights are explicit assignment authority and never derive from generic round dimensions.
 */
@Serializable
data class NodeScoreWeights(
    val correctness: Double = 1.0,
    val evidenceQuality: Double = 1.0,
    val reproducibility: Double = 1.0,
    val robustness: Double = 1.0,
    val integrationUtility: Double = 1.0,
    val receiverRelevance: Double = 1.0,
    val novelty: Double = 1.0,
    val efficiency: Double = 1.0
) {
    init {
        val values = listOf(
            correctness,
            evidenceQuality,
            reproducibility,
            robustness,
            integrationUtility,
            receiverRelevance,
            novelty,
            efficiency
        )
        require(values.all { it.isFinite() && it >= 0.0 }) {
            "Node score weights must be finite and non-negative"
        }
        require(values.any { it > 0.0 }) { "At least one node score weight must be positive" }
    }

    /** Returns the policy-weighted normalized score for [vector]. */
    fun normalizedScore(vector: NodeScoreVector): Double {
        val weighted = correctness * vector.correctness +
            evidenceQuality * vector.evidenceQuality +
            reproducibility * vector.reproducibility +
            robustness * vector.robustness +
            integrationUtility * vector.integrationUtility +
            receiverRelevance * vector.receiverRelevance +
            novelty * vector.novelty +
            efficiency * vector.efficiency
        return weighted / total()
    }

    /** Returns the positive total weight. */
    fun total(): Double = correctness + evidenceQuality + reproducibility + robustness +
        integrationUtility + receiverRelevance + novelty + efficiency
}

/**
 * Immutable packet-specific work order supplied to every independent node grader.
 *
 * @param assignmentId Deterministic assignment identity.
 * @param packet Exact sealed evidence unit.
 * @param criteriaHash Frozen node proof/deliverable criteria commitment.
 * @param graderCriteriaFreezeHashes Exact per-grader competitive criteria freezes.
 * @param capturePolicy Exact frozen capture policy used for pass/fail and capture.
 * @param capturePolicyHash Commitment of [capturePolicy].
 * @param scoreWeights Explicit node-vector reduction policy.
 * @param requiredQuorum Number of independent grader responses required.
 * @param assignmentHash Deterministic semantic commitment excluding this field.
 */
@Serializable
data class NodeGradingAssignment(
    val assignmentId: String,
    val packet: NodeEvidencePacket,
    val criteriaHash: String,
    val graderCriteriaFreezeHashes: Map<String, String> = emptyMap(),
    val capturePolicy: CapturePolicy,
    val capturePolicyHash: String,
    val scoreWeights: NodeScoreWeights = NodeScoreWeights(),
    val requiredQuorum: Int,
    val assignmentHash: String = ""
) {
    /** Returns a copy carrying its deterministic self-hash. */
    fun withComputedHash(): NodeGradingAssignment =
        copy(assignmentHash = AtlasHashing.hashNodeGradingAssignment(this))

    /** Returns whether this assignment and every embedded authority commitment are intact. */
    fun hasValidHash(): Boolean =
        packet.hasValidHash() &&
            AtlasHashing.constantTimeEquals(
                capturePolicyHash,
                AtlasHashing.hashCapturePolicy(capturePolicy)
            ) &&
            AtlasHashing.constantTimeEquals(
                assignmentHash,
                AtlasHashing.hashNodeGradingAssignment(this)
            )
}

/**
 * Status carried by one independent grader response for one exact assignment.
 *
 * [INVALID] is reserved for deterministic runtime or imported-authority defects. Model-facing
 * grading boundaries expose only [VALID] and [ABSTAINED].
 */
@Serializable
enum class NodeGraderResponseVerdict {
    VALID,
    ABSTAINED,
    INVALID
}

/**
 * One independent grader's exact response to a [NodeGradingAssignment].
 *
 * @param responseId Deterministic response identity.
 * @param assignmentId Exact assignment identity.
 * @param assignmentHash Exact assignment commitment.
 * @param graderId Independent grader-group identity.
 * @param scoreVector Complete vector for [NodeGraderResponseVerdict.VALID].
 * @param verdict Explicit response status.
 * @param rationale Concise evidence-grounded explanation.
 * @param responseHash Deterministic semantic commitment excluding this field.
 */
@Serializable
data class NodeGraderResponse(
    val responseId: String,
    val assignmentId: String,
    val assignmentHash: String,
    val graderId: String,
    val scoreVector: NodeScoreVector? = null,
    val verdict: NodeGraderResponseVerdict,
    val rationale: String,
    val responseHash: String = ""
) {
    /** Returns a copy carrying its deterministic self-hash. */
    fun withComputedHash(): NodeGraderResponse =
        copy(responseHash = AtlasHashing.hashNodeGraderResponse(this))

    /** Returns whether this response's stored semantic commitment is intact. */
    fun hasValidHash(): Boolean =
        AtlasHashing.constantTimeEquals(responseHash, AtlasHashing.hashNodeGraderResponse(this))
}

/**
 * Authority status of an exact node-grade bundle.
 *
 * [NO_EVIDENCE] is a valid evaluated result. The remaining non-valid statuses freeze capture and
 * yields and must never be converted to a zero score.
 */
@Serializable
enum class NodeGradeBundleVerdict {
    VALID_PASS,
    VALID_FAIL,
    NO_EVIDENCE,
    INVALID,
    ABSTAINED,
    MALFORMED,
    INCOMPLETE_QUORUM
}

/**
 * Exact aggregated grader authority for one evidence packet.
 *
 * The bundle preserves both the complete vector and the policy-weighted normalized score. Its
 * hash excludes [bundleHash].
 *
 * @param bundleId Stable bundle identity.
 * @param runId Run owning the grade.
 * @param round Judging round.
 * @param groupId Group whose packet was graded.
 * @param nodeId Graded node.
 * @param atlasVersion Exact Atlas version.
 * @param atlasHash Exact Atlas commitment.
 * @param nodePayloadHash Exact node payload commitment.
 * @param evidencePacketId Exact packet identity, absent only for a valid no-evidence result or
 * defective authority that never established an evaluable packet.
 * @param evidencePacketHash Exact packet commitment under the same condition.
 * @param criteriaHash Frozen node criteria commitment.
 * @param capturePolicyHash Frozen capture policy commitment.
 * @param scoreVector Complete quality vector, absent only when authority is defective or no
 * evidence was validly established.
 * @param normalizedScore Policy-weighted score, absent unless a complete vector was evaluated.
 * @param verdict Bundle authority status.
 * @param requiredQuorum Frozen required grader quorum.
 * @param graderIds Independent graders assigned to the bundle.
 * @param completedGraderIds Graders contributing valid responses.
 * @param graderResponseHashes Exact response commitments.
 * @param bundleHash Deterministic bundle hash excluding this field.
 */
@Serializable
data class NodeGradeBundle(
    val bundleId: String,
    val runId: String,
    val round: Int,
    val groupId: AtlasGroupId,
    val nodeId: NodeId,
    val atlasVersion: Int,
    val atlasHash: String,
    val nodePayloadHash: String,
    val evidencePacketId: String? = null,
    val evidencePacketHash: String? = null,
    val criteriaHash: String,
    val capturePolicyHash: String,
    val scoreVector: NodeScoreVector? = null,
    val normalizedScore: Double? = null,
    val verdict: NodeGradeBundleVerdict,
    val requiredQuorum: Int,
    val graderIds: Set<String>,
    val completedGraderIds: Set<String>,
    val graderResponseHashes: Set<String>,
    val bundleHash: String = ""
) {
    /** Returns a copy carrying its deterministic self-hash. */
    fun withComputedHash(): NodeGradeBundle =
        copy(bundleHash = AtlasHashing.hashNodeGradeBundle(this))

    /** Returns whether the bundle's stored self-hash matches its complete semantic content. */
    fun hasValidHash(): Boolean =
        AtlasHashing.constantTimeEquals(bundleHash, AtlasHashing.hashNodeGradeBundle(this))
}

/** Replayable result of resolving node ownership from exact grade bundles. */
@Serializable
enum class NodeCaptureDecisionType {
    NO_EVIDENCE,
    PROVISIONAL_CAPTURE,
    CAPTURED,
    CONTESTED,
    SHARED_VALIDATION_CREDIT,
    RECAPTURED,
    RETIRED,
    BELOW_THRESHOLD,
    INSUFFICIENT_RECAPTURE_MARGIN,
    FROZEN_INVALID_AUTHORITY
}

/** Stable machine-readable explanation for a capture resolution. */
@Serializable
enum class NodeCaptureReasonCode {
    BEST_VALID_GRADE,
    NO_EVIDENCE,
    BELOW_MINIMUM_GRADE,
    NEAR_TIE,
    REPLICATION_PENDING,
    CAPTURE_DISABLED,
    MISSING_GRADE_BUNDLE,
    DUPLICATE_GROUP_BUNDLE,
    INVALID_GRADE_BUNDLE,
    OWNER_AUTHORITY_MISSING,
    RECAPTURE_MARGIN_NOT_MET,
    RECAPTURE_MARGIN_MET
}

/**
 * Append-only replay record for one node capture resolution.
 *
 * @param decisionId Stable decision identity.
 * @param runId Run owning the decision.
 * @param round Resolution round.
 * @param nodeId Resolved node.
 * @param atlasVersion Exact Atlas version.
 * @param atlasHash Exact Atlas commitment.
 * @param nodePayloadHash Exact node payload commitment.
 * @param capturePolicyHash Frozen policy commitment.
 * @param candidateBundleHashes All bundles considered by the resolver.
 * @param authoritativeBundleHashes Valid bundles controlling the outcome.
 * @param previousOwnerGroupId Owner before this decision.
 * @param previousAuthoritativeBundleHash Bundle supporting prior ownership.
 * @param resultingOwnerGroupIds Owners after this decision.
 * @param resultingCaptureState Projected Atlas capture state.
 * @param decisionType Typed outcome.
 * @param winningScore Winning normalized score when one exists.
 * @param priorWinningScore Prior ownership score when recapture was considered.
 * @param yieldEligible Whether this decision may schedule next-round yield.
 * @param reasonCode Stable machine-readable explanation.
 * @param decisionHash Deterministic decision hash excluding this field.
 */
@Serializable
data class NodeCaptureDecision(
    val decisionId: String,
    val runId: String,
    val round: Int,
    val nodeId: NodeId,
    val atlasVersion: Int,
    val atlasHash: String,
    val nodePayloadHash: String,
    val capturePolicyHash: String,
    val candidateBundleHashes: Set<String>,
    val authoritativeBundleHashes: Set<String> = emptySet(),
    val previousOwnerGroupId: AtlasGroupId? = null,
    val previousAuthoritativeBundleHash: String? = null,
    val resultingOwnerGroupIds: Set<AtlasGroupId> = emptySet(),
    val resultingCaptureState: CaptureState,
    val decisionType: NodeCaptureDecisionType,
    val winningScore: Double? = null,
    val priorWinningScore: Double? = null,
    val yieldEligible: Boolean = false,
    val reasonCode: NodeCaptureReasonCode,
    val decisionHash: String = ""
) {
    /** Returns a copy carrying its deterministic self-hash. */
    fun withComputedHash(): NodeCaptureDecision =
        copy(decisionHash = AtlasHashing.hashNodeCaptureDecision(this))

    /** Returns whether the decision's stored self-hash matches its complete semantic content. */
    fun hasValidHash(): Boolean =
        AtlasHashing.constantTimeEquals(decisionHash, AtlasHashing.hashNodeCaptureDecision(this))
}

/** Instrumental rewards that may be consumed only in a later round. */
@Serializable
enum class InstrumentalRewardType {
    SPAWN_CREDITS,
    TOOL_COMPUTE_CREDITS,
    ARTIFACT_VALIDATION_CREDITS,
    SOURCE_ACCESS_CREDITS,
    RETRIEVAL_BUDGET,
    DISCLOSURE_TOKEN,
    DISPATCH_CREDITS,
    MODEL_TIER_COUPON,
    LEGACY_SLOT
}

/**
 * One typed quantity within a delayed yield schedule.
 *
 * @param rewardType Instrumental reward class.
 * @param amount Scheduled amount after caps and diminishing returns.
 */
@Serializable
data class ScheduledReward(
    val rewardType: InstrumentalRewardType,
    val amount: Double
)

/**
 * Frozen anti-concentration policy used to create a yield schedule.
 *
 * @param policyId Stable policy identity.
 * @param policyHash Exact policy commitment.
 * @param perGroupRoundCap Maximum total reward amount for one group in one round.
 * @param totalRoundCap Maximum total reward amount scheduled across all groups in one round.
 * @param maximumActiveYieldFraction Maximum fraction of active yields controlled by one group.
 * @param repeatOwnershipDiminishingFactor Multiplicative factor for repeated ownership.
 * @param mandatoryFrontierBonus Bonus for previously uncaptured mandatory frontier nodes.
 * @param independentValidationCredit Credit granted to an independent reproducing group.
 */
@Serializable
data class YieldConstraintPolicy(
    val policyId: String,
    val policyHash: String,
    val perGroupRoundCap: Double,
    val totalRoundCap: Double = perGroupRoundCap,
    val maximumActiveYieldFraction: Double,
    val repeatOwnershipDiminishingFactor: Double,
    val mandatoryFrontierBonus: Double,
    val independentValidationCredit: Double
) {
    /** Returns a copy carrying its deterministic self-hash. */
    fun withComputedHash(): YieldConstraintPolicy =
        copy(policyHash = AtlasHashing.hashYieldConstraintPolicy(this))

    /** Returns whether the stored policy hash matches the complete policy content. */
    fun hasValidHash(): Boolean =
        AtlasHashing.constantTimeEquals(policyHash, AtlasHashing.hashYieldConstraintPolicy(this))
}

/**
 * Next-round reward schedule caused by a valid capture decision.
 *
 * The schedule hash excludes [scheduleHash]. [activationRound] must be later than
 * [judgingRound].
 *
 * @param scheduleId Stable schedule identity.
 * @param runId Run owning the schedule.
 * @param nodeId Captured node.
 * @param groupId Reward recipient.
 * @param judgingRound Round whose grading caused the schedule.
 * @param activationRound Earliest round in which rewards may be applied.
 * @param captureDecisionId Causing capture decision.
 * @param captureDecisionHash Exact decision commitment.
 * @param evidencePacketHash Exact winning evidence commitment.
 * @param scoreBundleHash Exact winning grade commitment.
 * @param constraintPolicyHash Exact anti-concentration policy commitment.
 * @param rewards Bounded typed rewards.
 * @param frontierBonusApplied Whether mandatory-frontier bonus was included.
 * @param independentValidationCredit Whether this schedule rewards independent validation.
 * @param scheduleHash Deterministic schedule hash excluding this field.
 */
@Serializable
data class NodeYieldSchedule(
    val scheduleId: String,
    val runId: String,
    val nodeId: NodeId,
    val groupId: AtlasGroupId,
    val judgingRound: Int,
    val activationRound: Int,
    val captureDecisionId: String,
    val captureDecisionHash: String,
    val evidencePacketHash: String,
    val scoreBundleHash: String,
    val constraintPolicyHash: String,
    val rewards: List<ScheduledReward>,
    val frontierBonusApplied: Boolean = false,
    val independentValidationCredit: Boolean = false,
    val scheduleHash: String = ""
) {
    /** Returns a copy carrying its deterministic self-hash. */
    fun withComputedHash(): NodeYieldSchedule =
        copy(scheduleHash = AtlasHashing.hashNodeYieldSchedule(this))

    /** Returns whether the schedule's stored self-hash matches its complete semantic content. */
    fun hasValidHash(): Boolean =
        AtlasHashing.constantTimeEquals(scheduleHash, AtlasHashing.hashNodeYieldSchedule(this))
}

/**
 * Append-only record that a delayed schedule was applied.
 *
 * @param applicationId Stable application identity.
 * @param scheduleId Applied schedule identity.
 * @param scheduleHash Exact schedule commitment.
 * @param runId Run owning the application.
 * @param nodeId Source node.
 * @param groupId Reward recipient.
 * @param round Actual application round.
 * @param appliedRewards Rewards applied after final policy validation.
 * @param applicationHash Deterministic application hash excluding this field.
 */
@Serializable
data class NodeYieldApplication(
    val applicationId: String,
    val scheduleId: String,
    val scheduleHash: String,
    val runId: String,
    val nodeId: NodeId,
    val groupId: AtlasGroupId,
    val round: Int,
    val appliedRewards: List<ScheduledReward>,
    val applicationHash: String = ""
) {
    /** Returns a copy carrying its deterministic self-hash. */
    fun withComputedHash(): NodeYieldApplication =
        copy(applicationHash = AtlasHashing.hashNodeYieldApplication(this))

    /** Returns whether the application's stored self-hash matches its semantic content. */
    fun hasValidHash(): Boolean =
        AtlasHashing.constantTimeEquals(
            applicationHash,
            AtlasHashing.hashNodeYieldApplication(this)
        )
}

/**
 * Causal downstream-use event for an applied node reward.
 *
 * Events can first record spend and later append a separate score-delta observation linked through
 * [parentEventHash].
 *
 * @param eventId Stable event identity.
 * @param runId Run owning the event.
 * @param groupId Group consuming the reward.
 * @param nodeId Node that caused the reward.
 * @param captureDecisionHash Exact capture commitment.
 * @param yieldScheduleHash Exact schedule commitment.
 * @param yieldApplicationHash Exact application commitment.
 * @param rewardType Consumed reward type.
 * @param amountSpent Amount consumed by this event.
 * @param spentRound Consumption round.
 * @param downstreamArtifactIds Artifacts causally produced using the reward.
 * @param observedScoreDelta Later measured score delta, when available.
 * @param observationRound Round in which the score delta was observed.
 * @param parentEventHash Prior lineage event when this is a later observation.
 * @param eventHash Deterministic event hash excluding this field.
 */
@Serializable
data class RewardUseLineageEvent(
    val eventId: String,
    val runId: String,
    val groupId: AtlasGroupId,
    val nodeId: NodeId,
    val captureDecisionHash: String,
    val yieldScheduleHash: String,
    val yieldApplicationHash: String,
    val rewardType: InstrumentalRewardType,
    val amountSpent: Double,
    val spentRound: Int,
    val downstreamArtifactIds: Set<String> = emptySet(),
    val observedScoreDelta: Double? = null,
    val observationRound: Int? = null,
    val parentEventHash: String? = null,
    val eventHash: String = ""
) {
    /** Returns a copy carrying its deterministic self-hash. */
    fun withComputedHash(): RewardUseLineageEvent =
        copy(eventHash = AtlasHashing.hashRewardUseLineageEvent(this))

    /** Returns whether the event's stored self-hash matches its complete semantic content. */
    fun hasValidHash(): Boolean =
        AtlasHashing.constantTimeEquals(eventHash, AtlasHashing.hashRewardUseLineageEvent(this))
}
