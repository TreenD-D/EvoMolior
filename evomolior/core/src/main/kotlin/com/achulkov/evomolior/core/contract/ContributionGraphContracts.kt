@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.core.contract

import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.AtlasHashing
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** Immutable authority classes that can participate in causal contribution lineage. */
@Serializable
enum class ContributionAuthorityKind {
    AGENT,
    CONTRIBUTION_COMMIT,
    STAGING_SUBMISSION,
    CRITIC_DECISION,
    CANONICAL_ARTIFACT,
    STRUCTURED_DEAD_END,
    NODE_EVIDENCE_PACKET,
    NODE_GRADE_BUNDLE,
    NODE_GRADE_DELTA,
    NODE_CAPTURE_DECISION,
    DOWNSTREAM_READ,
    CITATION,
    TOOL_EXECUTION,
    INTEGRATION_NODE,
    FINAL_DELIVERABLE_REFERENCE,
    RECEIVER_SCORE_CONTRIBUTION,
    BENCHMARK_SCORE_CONTRIBUTION,
    TOOL_FITNESS,
    OUTLIER_FITNESS,
    GRADER_FITNESS,
    COST_LEDGER_ENTRY,
    CONTROL_INTERVENTION,
    SUMMARY_VALIDATION
}

/** Society plane responsible for the action represented by a contribution edge. */
@Serializable
enum class ContributionPlane {
    WORKER,
    REVIEW,
    CONTROL,
    GRADER,
    SUMMARY,
    RECEIVER
}

/** Exact downstream use modes over immutable canonical contribution authority. */
@Serializable
enum class DownstreamUseKind {
    READ,
    CITATION
}

/** Closed causal relations accepted by the durable contribution graph. */
@Serializable
enum class ContributionRelation(
    val sourceKinds: Set<ContributionAuthorityKind>,
    val targetKinds: Set<ContributionAuthorityKind>
) {
    AUTHORED(
        setOf(ContributionAuthorityKind.AGENT),
        setOf(ContributionAuthorityKind.CONTRIBUTION_COMMIT)
    ),
    STAGED_AS(
        setOf(ContributionAuthorityKind.CONTRIBUTION_COMMIT),
        setOf(ContributionAuthorityKind.STAGING_SUBMISSION)
    ),
    REVIEWED_AS(
        setOf(ContributionAuthorityKind.STAGING_SUBMISSION),
        setOf(ContributionAuthorityKind.CRITIC_DECISION)
    ),
    PROMOTED_TO(
        setOf(ContributionAuthorityKind.CRITIC_DECISION),
        setOf(ContributionAuthorityKind.CANONICAL_ARTIFACT)
    ),
    ACCEPTED_AS_STRUCTURED_DEAD_END(
        setOf(ContributionAuthorityKind.CRITIC_DECISION),
        setOf(ContributionAuthorityKind.STRUCTURED_DEAD_END)
    ),
    INCLUDED_IN_EVIDENCE(
        setOf(
            ContributionAuthorityKind.CANONICAL_ARTIFACT,
            ContributionAuthorityKind.STRUCTURED_DEAD_END,
            ContributionAuthorityKind.TOOL_EXECUTION
        ),
        setOf(ContributionAuthorityKind.NODE_EVIDENCE_PACKET)
    ),
    GRADED_AS(
        setOf(ContributionAuthorityKind.NODE_EVIDENCE_PACKET),
        setOf(
            ContributionAuthorityKind.NODE_GRADE_BUNDLE,
            ContributionAuthorityKind.NODE_GRADE_DELTA
        )
    ),
    AUTHORIZED_CAPTURE(
        setOf(
            ContributionAuthorityKind.NODE_GRADE_BUNDLE,
            ContributionAuthorityKind.NODE_GRADE_DELTA
        ),
        setOf(ContributionAuthorityKind.NODE_CAPTURE_DECISION)
    ),
    READ_DOWNSTREAM(
        setOf(
            ContributionAuthorityKind.CANONICAL_ARTIFACT,
            ContributionAuthorityKind.STRUCTURED_DEAD_END
        ),
        setOf(ContributionAuthorityKind.DOWNSTREAM_READ)
    ),
    CITED_DOWNSTREAM(
        setOf(
            ContributionAuthorityKind.CANONICAL_ARTIFACT,
            ContributionAuthorityKind.STRUCTURED_DEAD_END
        ),
        setOf(ContributionAuthorityKind.CITATION)
    ),
    EXECUTED_DOWNSTREAM(
        setOf(ContributionAuthorityKind.CANONICAL_ARTIFACT),
        setOf(ContributionAuthorityKind.TOOL_EXECUTION)
    ),
    INTEGRATED_INTO_NODE(
        setOf(
            ContributionAuthorityKind.DOWNSTREAM_READ,
            ContributionAuthorityKind.CITATION,
            ContributionAuthorityKind.TOOL_EXECUTION,
            ContributionAuthorityKind.CANONICAL_ARTIFACT,
            ContributionAuthorityKind.STRUCTURED_DEAD_END,
            ContributionAuthorityKind.NODE_CAPTURE_DECISION
        ),
        setOf(ContributionAuthorityKind.INTEGRATION_NODE)
    ),
    REFERENCED_BY_FINAL_DELIVERABLE(
        setOf(
            ContributionAuthorityKind.INTEGRATION_NODE,
            ContributionAuthorityKind.CANONICAL_ARTIFACT,
            ContributionAuthorityKind.STRUCTURED_DEAD_END,
            ContributionAuthorityKind.NODE_CAPTURE_DECISION
        ),
        setOf(ContributionAuthorityKind.FINAL_DELIVERABLE_REFERENCE)
    ),
    CONTRIBUTED_TO_RECEIVER_SCORE(
        setOf(ContributionAuthorityKind.FINAL_DELIVERABLE_REFERENCE),
        setOf(ContributionAuthorityKind.RECEIVER_SCORE_CONTRIBUTION)
    ),
    CONTRIBUTED_TO_BENCHMARK_SCORE(
        setOf(ContributionAuthorityKind.FINAL_DELIVERABLE_REFERENCE),
        setOf(ContributionAuthorityKind.BENCHMARK_SCORE_CONTRIBUTION)
    );

    /** Whether this relation admits the supplied endpoint authority kinds. */
    fun accepts(
        sourceKind: ContributionAuthorityKind,
        targetKind: ContributionAuthorityKind
    ): Boolean = sourceKind in sourceKinds && targetKind in targetKinds
}

/**
 * Reference to an existing immutable authority without copying its artifact or score payload.
 *
 * [authorityHash] is required whenever the referenced authority exposes a self/content hash.
 * Authorities such as agent identities may be addressed by stable ID alone.
 */
@Serializable
data class ContributionAuthorityRef(
    val kind: ContributionAuthorityKind,
    val authorityId: String,
    val authorityHash: String? = null
) {
    init {
        require(authorityId.isNotBlank()) { "Contribution authority ID must not be blank" }
        require(authorityHash == null || authorityHash.isNotBlank()) {
            "Contribution authority hash must be null or non-blank"
        }
    }
}

/**
 * One append-only causal edge over existing contribution authorities.
 *
 * The edge contains no artifact body, mutable status, aggregate score, or dashboard projection.
 * [edgeHash] commits the exact provenance relation and excludes only itself.
 */
@Serializable
data class ContributionEdge(
    val edgeId: String,
    val runId: String,
    val groupId: AtlasGroupId,
    val round: Int,
    val plane: ContributionPlane,
    val relation: ContributionRelation,
    val source: ContributionAuthorityRef,
    val target: ContributionAuthorityRef,
    val occurredAt: Instant,
    val edgeHash: String = ""
) {
    init {
        require(edgeId.isNotBlank()) { "Contribution edge ID must not be blank" }
        require(runId.isNotBlank()) { "Contribution edge run ID must not be blank" }
        require(groupId.value.isNotBlank()) { "Contribution edge group ID must not be blank" }
        require(round >= 0) { "Contribution edge round must not be negative" }
        require(source != target) { "Contribution edge cannot self-reference one authority" }
        require(relation.accepts(source.kind, target.kind)) {
            "${relation.name} does not accept ${source.kind.name} -> ${target.kind.name}"
        }
    }

    /** Returns this edge with its deterministic self-hash attached. */
    fun withComputedHash(): ContributionEdge =
        copy(edgeHash = AtlasHashing.hashContributionEdge(this))

    /** Verifies that [edgeHash] commits this exact causal relation. */
    fun hasValidHash(): Boolean =
        AtlasHashing.constantTimeEquals(edgeHash, AtlasHashing.hashContributionEdge(this))
}

/**
 * Immutable evidence that one agent read or cited one exact canonical contribution.
 *
 * [contextHash] commits the exact read request or citation context without persisting worker
 * prose in the graph authority. [recordHash] excludes itself.
 */
@Serializable
data class DownstreamUseRecord(
    val useId: String,
    val runId: String,
    val groupId: AtlasGroupId,
    val round: Int,
    val agentId: String,
    val nodeId: String?,
    val kind: DownstreamUseKind,
    val sourceAuthority: ContributionAuthorityRef,
    val contextHash: String,
    val occurredAt: Instant,
    val recordHash: String = ""
) {
    init {
        require(useId.isNotBlank() && runId.isNotBlank() && agentId.isNotBlank()) {
            "Downstream-use identity fields must not be blank"
        }
        require(groupId.value.isNotBlank()) { "Downstream-use group ID must not be blank" }
        require(round >= 0) { "Downstream-use round must not be negative" }
        require(nodeId == null || nodeId.isNotBlank()) {
            "Downstream-use node ID must be null or non-blank"
        }
        require(
            sourceAuthority.kind in setOf(
                ContributionAuthorityKind.CANONICAL_ARTIFACT,
                ContributionAuthorityKind.STRUCTURED_DEAD_END
            )
        ) {
            "Downstream use requires canonical artifact or structured dead-end authority"
        }
        require(sourceAuthority.authorityHash != null) {
            "Downstream use requires the exact canonical authority hash"
        }
        require(contextHash.isNotBlank()) { "Downstream-use context hash must not be blank" }
    }

    /** Returns this record with its deterministic self-hash attached. */
    fun withComputedHash(): DownstreamUseRecord =
        copy(recordHash = AtlasHashing.hashDownstreamUseRecord(this))

    /** Verifies the exact immutable downstream-use commitment. */
    fun hasValidHash(): Boolean =
        AtlasHashing.constantTimeEquals(
            recordHash,
            AtlasHashing.hashDownstreamUseRecord(this)
        )
}

/**
 * Immutable authority proving that exact accepted inputs were integrated into an Atlas node.
 *
 * This record is created only for captured `INTEGRATION` nodes. It binds the capture decision and
 * every canonical/tool authority entering the winning evidence packet.
 */
@Serializable
data class IntegrationNodeRecord(
    val integrationId: String,
    val runId: String,
    val groupId: AtlasGroupId,
    val round: Int,
    val nodeId: String,
    val atlasVersion: Int,
    val atlasHash: String,
    val nodePayloadHash: String,
    val captureDecision: ContributionAuthorityRef,
    val sourceAuthorities: List<ContributionAuthorityRef>,
    val integratedAt: Instant,
    val recordHash: String = ""
) {
    init {
        require(
            integrationId.isNotBlank() && runId.isNotBlank() &&
                groupId.value.isNotBlank() && nodeId.isNotBlank()
        ) { "Integration-node identity fields must not be blank" }
        require(round >= 0 && atlasVersion >= 1) {
            "Integration-node round and Atlas version are invalid"
        }
        require(atlasHash.isNotBlank() && nodePayloadHash.isNotBlank()) {
            "Integration-node Atlas commitments must not be blank"
        }
        require(
            captureDecision.kind == ContributionAuthorityKind.NODE_CAPTURE_DECISION &&
                captureDecision.authorityHash != null
        ) { "Integration-node authority requires an exact capture decision" }
        require(sourceAuthorities.isNotEmpty() && sourceAuthorities.distinct() == sourceAuthorities) {
            "Integration-node source authorities must be non-empty and unique"
        }
        require(sourceAuthorities.all { source ->
            ContributionRelation.INTEGRATED_INTO_NODE.accepts(
                source.kind,
                ContributionAuthorityKind.INTEGRATION_NODE
            ) && source.authorityHash != null
        }) {
            "Integration-node sources must be exact accepted integration authorities"
        }
    }

    /** Returns this integration authority with its deterministic self-hash. */
    fun withComputedHash(): IntegrationNodeRecord =
        copy(recordHash = AtlasHashing.hashIntegrationNodeRecord(this))

    /** Verifies the exact immutable integration commitment. */
    fun hasValidHash(): Boolean =
        AtlasHashing.constantTimeEquals(
            recordHash,
            AtlasHashing.hashIntegrationNodeRecord(this)
        )
}

/**
 * Immutable authority binding one receiver-facing output file to one exact admitted source.
 *
 * A file may have multiple records when multiple admitted authorities materially contribute to
 * it. Writer identity and submission provenance are included so output publication never relies
 * on rendered-text inference.
 */
@Serializable
data class FinalDeliverableReferenceRecord(
    val referenceId: String,
    val runId: String,
    val groupId: AtlasGroupId,
    val round: Int,
    val relativePath: String,
    val mediaType: String,
    val contentHash: String,
    val writerAgentId: String,
    val writerSubmissionId: String,
    val nodeId: String?,
    val sourceAuthority: ContributionAuthorityRef,
    val referencedAt: Instant,
    val componentId: String? = null,
    val recordHash: String = ""
) {
    init {
        require(
            referenceId.isNotBlank() && runId.isNotBlank() && groupId.value.isNotBlank()
        ) { "Final-deliverable reference identity fields must not be blank" }
        require(round >= 0) { "Final-deliverable reference round must not be negative" }
        require(
            relativePath.isNotBlank() && mediaType.isNotBlank() && contentHash.isNotBlank()
        ) { "Final-deliverable output fields must not be blank" }
        require(writerAgentId.isNotBlank() && writerSubmissionId.isNotBlank()) {
            "Final-deliverable writer provenance must not be blank"
        }
        require(nodeId == null || nodeId.isNotBlank()) {
            "Final-deliverable node ID must be null or non-blank"
        }
        require(componentId == null || componentId.isNotBlank()) {
            "Final-deliverable component ID must be null or non-blank"
        }
        require(
            ContributionRelation.REFERENCED_BY_FINAL_DELIVERABLE.accepts(
                sourceAuthority.kind,
                ContributionAuthorityKind.FINAL_DELIVERABLE_REFERENCE
            ) && sourceAuthority.authorityHash != null
        ) { "Final-deliverable source must be an exact admitted contribution authority" }
    }

    /** Returns this output-lineage authority with its deterministic self-hash. */
    fun withComputedHash(): FinalDeliverableReferenceRecord =
        copy(recordHash = AtlasHashing.hashFinalDeliverableReference(this))

    /** Verifies the exact immutable output-lineage commitment. */
    fun hasValidHash(): Boolean =
        AtlasHashing.constantTimeEquals(
            recordHash,
            AtlasHashing.hashFinalDeliverableReference(this)
        )
}

/** Receiver-side scoring component attached to one exact final-output reference. */
@Serializable
data class ReceiverScoreContributionRecord(
    val contributionId: String,
    val runId: String,
    val groupId: AtlasGroupId,
    val round: Int,
    val finalReference: ContributionAuthorityRef,
    val scoringContractId: String,
    val scoringContractVersion: Int,
    val scoringContractHash: String,
    val criterionId: String,
    val scoreContribution: Double,
    val maximumContribution: Double,
    val scoredAt: Instant,
    val recordHash: String = ""
) {
    init {
        require(contributionId.isNotBlank() && runId.isNotBlank() && groupId.value.isNotBlank()) {
            "Receiver-score contribution identity fields must not be blank"
        }
        require(round >= 0 && scoringContractVersion >= 1) {
            "Receiver-score round and scoring-contract version are invalid"
        }
        require(
            scoringContractId.isNotBlank() &&
                scoringContractHash.isNotBlank() &&
                criterionId.isNotBlank()
        ) {
            "Receiver-score contract and criterion IDs must not be blank"
        }
        require(
            finalReference.kind == ContributionAuthorityKind.FINAL_DELIVERABLE_REFERENCE &&
                finalReference.authorityHash != null
        ) { "Receiver score requires an exact final-deliverable reference" }
        require(scoreContribution.isFinite() && maximumContribution.isFinite()) {
            "Receiver-score values must be finite"
        }
        require(maximumContribution > 0.0 && scoreContribution in 0.0..maximumContribution) {
            "Receiver-score contribution must be within its positive maximum"
        }
    }

    /** Returns this receiver-score authority with its deterministic self-hash. */
    fun withComputedHash(): ReceiverScoreContributionRecord =
        copy(recordHash = AtlasHashing.hashReceiverScoreContribution(this))

    /** Verifies the exact immutable receiver-score commitment. */
    fun hasValidHash(): Boolean =
        AtlasHashing.constantTimeEquals(
            recordHash,
            AtlasHashing.hashReceiverScoreContribution(this)
        )
}

/** Benchmark scoring component attached to one exact final-output reference. */
@Serializable
data class BenchmarkScoreContributionRecord(
    val contributionId: String,
    val runId: String,
    val groupId: AtlasGroupId,
    val round: Int,
    val finalReference: ContributionAuthorityRef,
    val benchmarkId: String,
    val benchmarkVersion: Int,
    val benchmarkContractHash: String,
    val componentId: String,
    val scoreContribution: Double,
    val maximumContribution: Double,
    val scoredAt: Instant,
    val recordHash: String = ""
) {
    init {
        require(contributionId.isNotBlank() && runId.isNotBlank() && groupId.value.isNotBlank()) {
            "Benchmark-score contribution identity fields must not be blank"
        }
        require(round >= 0 && benchmarkVersion >= 1) {
            "Benchmark-score round and benchmark version are invalid"
        }
        require(
            benchmarkId.isNotBlank() &&
                benchmarkContractHash.isNotBlank() &&
                componentId.isNotBlank()
        ) {
            "Benchmark and component IDs must not be blank"
        }
        require(
            finalReference.kind == ContributionAuthorityKind.FINAL_DELIVERABLE_REFERENCE &&
                finalReference.authorityHash != null
        ) { "Benchmark score requires an exact final-deliverable reference" }
        require(scoreContribution.isFinite() && maximumContribution.isFinite()) {
            "Benchmark-score values must be finite"
        }
        require(maximumContribution > 0.0 && scoreContribution in 0.0..maximumContribution) {
            "Benchmark-score contribution must be within its positive maximum"
        }
    }

    /** Returns this benchmark-score authority with its deterministic self-hash. */
    fun withComputedHash(): BenchmarkScoreContributionRecord =
        copy(recordHash = AtlasHashing.hashBenchmarkScoreContribution(this))

    /** Verifies the exact immutable benchmark-score commitment. */
    fun hasValidHash(): Boolean =
        AtlasHashing.constantTimeEquals(
            recordHash,
            AtlasHashing.hashBenchmarkScoreContribution(this)
        )
}

/**
 * Immutable final Critic authority for one exact staged submission.
 *
 * A promoted decision must identify the canonical artifact or structured dead end it created.
 * Rejections deliberately have no result authority.
 */
@Serializable
data class CriticDecisionRecord(
    val decisionId: String,
    val runId: String,
    val groupId: AtlasGroupId,
    val round: Int,
    val submissionId: String,
    val submissionContentHash: String,
    val resolvedReview: CriticReview,
    val resultAuthority: ContributionAuthorityRef?,
    val decidedAt: Instant,
    val decisionHash: String = ""
) {
    init {
        require(decisionId.isNotBlank()) { "Critic decision ID must not be blank" }
        require(runId.isNotBlank()) { "Critic decision run ID must not be blank" }
        require(groupId.value.isNotBlank()) { "Critic decision group ID must not be blank" }
        require(round >= 0) { "Critic decision round must not be negative" }
        require(submissionId.isNotBlank()) { "Critic decision submission ID must not be blank" }
        require(submissionContentHash.isNotBlank()) {
            "Critic decision submission content hash must not be blank"
        }
        require(resolvedReview.submissionId == submissionId) {
            "Resolved Critic review must reference the exact staged submission"
        }
        require(resolvedReview.outcome != CriticOutcome.DISPUTE) {
            "A final Critic decision cannot remain disputed"
        }
        if (resolvedReview.outcome == CriticOutcome.PROMOTE) {
            require(
                resultAuthority?.kind in setOf(
                    ContributionAuthorityKind.CANONICAL_ARTIFACT,
                    ContributionAuthorityKind.STRUCTURED_DEAD_END
                )
            ) {
                "A promoted Critic decision requires canonical result authority"
            }
            require(resultAuthority?.authorityHash != null) {
                "A promoted Critic result requires an exact content hash"
            }
        } else {
            require(resultAuthority == null) {
                "A rejected Critic decision cannot claim canonical result authority"
            }
        }
    }

    /** Returns this decision with its deterministic self-hash attached. */
    fun withComputedHash(): CriticDecisionRecord =
        copy(decisionHash = AtlasHashing.hashCriticDecision(this))

    /** Verifies that [decisionHash] commits this exact final review and result. */
    fun hasValidHash(): Boolean =
        AtlasHashing.constantTimeEquals(
            decisionHash,
            AtlasHashing.hashCriticDecision(this)
        )
}
