package com.achulkov.evomolior.core.atlas

import com.achulkov.evomolior.core.grader.GraderCriteriaFreeze
import com.achulkov.evomolior.core.grader.GradingRequirement
import kotlinx.serialization.Serializable

/** Packet-reference class exposed to an exact node grader. */
@Serializable
enum class ExactNodeEvidenceKind {
    ARTIFACT,
    CLAIM
}

/**
 * Complete Critic-promoted content for one exact packet reference.
 *
 * @param referenceId Exact staging reference committed by the packet.
 * @param kind Packet reference class.
 * @param submissionType Typed staging submission class.
 * @param content Complete promoted content without truncation.
 * @param promotedArtifactRef Canonical promotion authority reference.
 * @param promotedArtifactHash SHA-256 commitment of [content].
 */
@Serializable
data class ExactNodeEvidenceContent(
    val referenceId: String,
    val kind: ExactNodeEvidenceKind,
    val submissionType: String,
    val content: String,
    val promotedArtifactRef: String,
    val promotedArtifactHash: String
)

/**
 * Deterministic pre-grade projection for one exact assignment and grader civilisation.
 *
 * Generic canon snapshots and broad criteria are deliberately absent. This projection contains
 * the sealed node contract, the exact frozen requirements mapped to that node, and every complete
 * packet-bound artifact and claim.
 *
 * @param graderGroupId Grader civilisation receiving the projection.
 * @param assignment Immutable packet-specific work order.
 * @param nodeContract Exact sealed worker node governed by [assignment].
 * @param criteriaFreeze Exact competitive criteria freeze for [graderGroupId].
 * @param mappedRequirements Complete frozen requirement subset governing this node.
 * @param evidence Complete packet-bound promoted content.
 * @param contextHash Deterministic semantic commitment excluding this field.
 */
@Serializable
data class ExactNodeEvaluationContext(
    val graderGroupId: String,
    val assignment: NodeGradingAssignment,
    val nodeContract: LimitationNode,
    val criteriaFreeze: GraderCriteriaFreeze,
    val mappedRequirements: List<GradingRequirement>,
    val evidence: List<ExactNodeEvidenceContent>,
    val contextHash: String = ""
) {
    /** Returns a copy carrying its deterministic semantic commitment. */
    fun withComputedHash(): ExactNodeEvaluationContext =
        copy(contextHash = AtlasHashing.hashExactNodeEvaluationContext(this))

    /** Returns whether every embedded authority and the complete projection remain intact. */
    fun hasValidHash(): Boolean {
        val packet = assignment.packet
        val expectedEvidence = buildSet {
            packet.acceptedArtifactIds.forEach {
                add(ExactNodeEvidenceKind.ARTIFACT to it)
            }
            packet.acceptedClaimIds.forEach {
                add(ExactNodeEvidenceKind.CLAIM to it)
            }
        }
        val actualEvidence = evidence.mapTo(linkedSetOf()) {
            it.kind to it.referenceId
        }
        val freezeHash = assignment.graderCriteriaFreezeHashes[graderGroupId]
        return assignment.hasValidHash() &&
            nodeContract.id == packet.nodeId &&
            nodeContract.payloadHash == packet.nodePayloadHash &&
            nodeContract.hasValidPayloadHash() &&
            assignment.capturePolicy == nodeContract.capturePolicy &&
            assignment.criteriaHash == AtlasHashing.hashNodeGradingAuthority(
                nodeContract,
                assignment.graderCriteriaFreezeHashes
            ) &&
            freezeHash == criteriaFreeze.freezeHash &&
            criteriaFreeze.hasValidHash() &&
            criteriaFreeze.runId == packet.runId &&
            criteriaFreeze.round == packet.round &&
            criteriaFreeze.graderGroupId == graderGroupId &&
            AtlasGroupId(packet.groupId.value) in criteriaFreeze.targetSnapshotHashes &&
            mappedRequirements.isNotEmpty() &&
            mappedRequirements.map(GradingRequirement::requirementHash).toSet().size ==
            mappedRequirements.size &&
            mappedRequirements.all { requirement ->
                requirement.hasValidHash() &&
                    requirement.requirementHash in criteriaFreeze.requirementHashes &&
                    requirement.runId == packet.runId &&
                    requirement.round == packet.round &&
                    requirement.graderGroupId == graderGroupId &&
                    requirement.graderAtlasHash == criteriaFreeze.graderAtlasHash &&
                    requirement.mappedWorkerNodeIds == setOf(packet.nodeId)
            } &&
            packet.acceptedArtifactIds.toSet()
                .intersect(packet.acceptedClaimIds.toSet())
                .isEmpty() &&
            expectedEvidence.size == evidence.size &&
            actualEvidence == expectedEvidence &&
            evidence.all {
                it.referenceId.isNotBlank() &&
                    it.promotedArtifactRef.isNotBlank() &&
                    AtlasHashing.constantTimeEquals(
                        it.promotedArtifactHash,
                        AtlasHashing.hashText(it.content)
                    )
            } &&
            AtlasHashing.constantTimeEquals(
                contextHash,
                AtlasHashing.hashExactNodeEvaluationContext(this)
            )
    }
}
