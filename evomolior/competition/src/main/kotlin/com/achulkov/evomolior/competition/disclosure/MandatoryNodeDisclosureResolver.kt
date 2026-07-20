package com.achulkov.evomolior.competition.disclosure

import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.DisclosureDecision
import com.achulkov.evomolior.core.atlas.DisclosureDecisionStatus
import com.achulkov.evomolior.core.atlas.DisclosureEvaluationStatus
import com.achulkov.evomolior.core.atlas.DisclosureRevealAuthorization
import com.achulkov.evomolior.core.atlas.GroupRevealPayload
import com.achulkov.evomolior.core.atlas.MandatoryNodeCoverage
import com.achulkov.evomolior.core.atlas.NodeAtlas
import com.achulkov.evomolior.core.atlas.NodeGradeBundle
import com.achulkov.evomolior.core.atlas.NodeGradeBundleVerdict
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.contract.toRevealedNodeCard

/** Exact inputs required to decide one group's stage progression. */
data class DisclosureRoundEvaluation(
    val atlas: NodeAtlas,
    val groupId: AtlasGroupId,
    val round: Int,
    val currentStageIndex: Int,
    val evaluationStatus: DisclosureEvaluationStatus,
    val nodeGradeBundles: List<NodeGradeBundle>,
    val unresolvedHardFailureNodeIds: Set<NodeId> = emptySet()
)

/** Resolves mandatory-node progression and constructs exact group-local reveals. */
class MandatoryNodeDisclosureResolver {
    /** Returns a hash-bound decision without mutating Atlas execution state. */
    fun resolve(input: DisclosureRoundEvaluation): DisclosureDecision {
        val atlas = input.atlas
        require(atlas.atlasHash.isNotBlank() && atlas.validate().isValid) {
            "Disclosure decisions require a valid sealed Atlas"
        }
        val stages = atlas.stageDefinitions.sortedBy { it.stageIndex }
        val stage = stages.singleOrNull { it.stageIndex == input.currentStageIndex }
            ?: error("Unknown disclosure stage ${input.currentStageIndex}")
        val nextStage = stages.firstOrNull { it.stageIndex == stage.stageIndex + 1 }
        val groupBundles = input.nodeGradeBundles.filter {
            it.runId == atlas.runId &&
                it.round <= input.round &&
                it.groupId == input.groupId &&
                it.nodeId in stage.nodeIds
        }
        val currentBundlesByNode = groupBundles
            .filter { it.round == input.round }
            .groupBy(NodeGradeBundle::nodeId)
        val malformedCurrentNodes = stage.nodeIds.filterTo(linkedSetOf()) { nodeId ->
            val bundles = currentBundlesByNode[nodeId].orEmpty()
            bundles.size != 1 ||
                !isExactBundle(bundles.single(), atlas) ||
                bundles.single().verdict in DEFECTIVE_AUTHORITY_VERDICTS
        }
        val currentAuthoritative = currentBundlesByNode.mapNotNull { (nodeId, bundles) ->
            bundles.singleOrNull()
                ?.takeIf { isExactBundle(it, atlas) }
                ?.let { nodeId to it }
        }.toMap()
        val historicalPasses = groupBundles
            .groupBy { it.nodeId to it.round }
            .filterValues { it.size == 1 }
            .values
            .map(List<NodeGradeBundle>::single)
            .filter { it.nodeId !in malformedCurrentNodes }
            .filter { isExactPassingCoverage(it, atlas) }
            .groupBy(NodeGradeBundle::nodeId)
            .mapValues { (_, bundles) ->
                bundles.maxWithOrNull(
                    compareBy<NodeGradeBundle> { it.round }
                        .thenBy(NodeGradeBundle::bundleHash)
                )
            }
            .mapNotNull { (nodeId, bundle) -> bundle?.let { nodeId to it } }
            .toMap()
        val effective = stage.nodeIds.mapNotNull { nodeId ->
            if (nodeId in malformedCurrentNodes) {
                null
            } else {
                (historicalPasses[nodeId] ?: currentAuthoritative[nodeId])
                    ?.let { nodeId to it }
            }
        }.toMap()
        val mandatory = stage.mandatoryNodeIds
        val missing = mandatory.filterTo(linkedSetOf()) { it !in effective }
        val noEvidence = mandatory.filterTo(linkedSetOf()) {
            effective[it]?.verdict == NodeGradeBundleVerdict.NO_EVIDENCE
        }
        val failed = mandatory.filterTo(linkedSetOf()) { nodeId ->
            val bundle = effective[nodeId] ?: return@filterTo false
            val threshold = atlas.nodes.getValue(nodeId).capturePolicy.minimumGrade
            val normalizedScore = bundle.normalizedScore
            bundle.verdict != NodeGradeBundleVerdict.VALID_PASS ||
                normalizedScore == null ||
                normalizedScore < threshold ||
                bundle.scoreVector?.hardFailure == true
        }
        val unresolved = mandatory.intersect(input.unresolvedHardFailureNodeIds)
        val passing = mandatory - missing - noEvidence - failed - unresolved
        val evidencedStageNodes = stage.nodeIds.count { nodeId ->
            effective[nodeId]?.let { bundle ->
                bundle.evidencePacketId != null &&
                    bundle.evidencePacketHash != null &&
                    bundle.verdict in VALID_EVIDENCE_VERDICTS
            } == true
        }
        val evidenceCoverage = if (stage.nodeIds.isEmpty()) 1.0
        else evidencedStageNodes.toDouble() / stage.nodeIds.size
        val integrationReadiness = mandatory.mapNotNull { nodeId ->
            effective[nodeId]?.scoreVector?.integrationUtility
        }.takeIf { it.size == mandatory.size }?.average() ?: 0.0
        val coverage = MandatoryNodeCoverage(
            stageId = stage.stageId,
            mandatoryNodeIds = mandatory,
            passingMandatoryNodeIds = passing,
            failedMandatoryNodeIds = failed,
            missingMandatoryNodeIds = missing,
            noEvidenceMandatoryNodeIds = noEvidence,
            unresolvedHardFailureNodeIds = unresolved,
            evidenceCoverage = evidenceCoverage,
            integrationReadiness = integrationReadiness
        )
        val status = when (input.evaluationStatus) {
            DisclosureEvaluationStatus.INVALID -> DisclosureDecisionStatus.FREEZE_INVALID
            DisclosureEvaluationStatus.ABSTAINED -> DisclosureDecisionStatus.FREEZE_ABSTAINED
            DisclosureEvaluationStatus.VALID -> when {
                malformedCurrentNodes.isNotEmpty() ||
                    missing.isNotEmpty() || noEvidence.isNotEmpty() || failed.isNotEmpty() ||
                    unresolved.isNotEmpty() ||
                    evidenceCoverage < stage.evidenceCoverageThreshold ||
                    integrationReadiness < stage.integrationReadinessThreshold ->
                    DisclosureDecisionStatus.HOLD_VALID_FAILURE
                nextStage == null -> DisclosureDecisionStatus.FINAL_STAGE_COMPLETE
                else -> DisclosureDecisionStatus.ADVANCE
            }
        }
        return DisclosureDecision(
            runId = atlas.runId,
            atlasId = atlas.atlasId,
            atlasVersion = atlas.version,
            atlasHash = atlas.atlasHash,
            groupId = input.groupId,
            round = input.round,
            fromStageId = stage.stageId,
            fromStageIndex = stage.stageIndex,
            toStageId = nextStage?.stageId?.takeIf { status == DisclosureDecisionStatus.ADVANCE },
            toStageIndex = nextStage?.stageIndex?.takeIf { status == DisclosureDecisionStatus.ADVANCE },
            evaluationStatus = input.evaluationStatus,
            status = status,
            coverage = coverage,
            authoritativeBundleHashes = (
                effective.values +
                    currentAuthoritative
                        .filterKeys { it !in malformedCurrentNodes }
                        .values
                )
                .mapTo(linkedSetOf(), NodeGradeBundle::bundleHash),
            reason = reason(status, coverage)
        ).withComputedHash()
    }

    /** Builds and verifies the exact target-stage payload authorized by [decision]. */
    fun buildReveal(
        atlas: NodeAtlas,
        decision: DisclosureDecision
    ): Pair<GroupRevealPayload, DisclosureRevealAuthorization> {
        require(decision.hasValidHash() && decision.status == DisclosureDecisionStatus.ADVANCE) {
            "A valid advance decision is required to reveal a stage"
        }
        require(decision.atlasHash == atlas.atlasHash) {
            "Disclosure decision does not match the supplied Atlas"
        }
        val targetStageId = requireNotNull(decision.toStageId)
        val targetStage = atlas.stageDefinitions.single { it.stageId == targetStageId }
        val payload = GroupRevealPayload(
            runId = atlas.runId,
            atlasId = atlas.atlasId,
            atlasVersion = atlas.version,
            atlasHash = atlas.atlasHash,
            groupId = decision.groupId,
            round = decision.round,
            stageId = targetStage.stageId,
            stageIndex = targetStage.stageIndex,
            stagePayloadHash = targetStage.revealPayloadHash,
            revealedNodes = targetStage.nodeIds
                .sortedBy(NodeId::value)
                .map { atlas.nodes.getValue(it).toRevealedNodeCard() }
        ).withComputedHash()
        require(payload.hasValidHashes(atlas)) {
            "Group-local reveal payload does not match the sealed stage commitment"
        }
        val authorization = DisclosureRevealAuthorization(
            authorizationId = "${atlas.atlasId}:${decision.groupId.value}:${decision.round}:" +
                "${targetStage.stageId}:${payload.payloadHash}",
            decisionHash = decision.decisionHash,
            groupId = decision.groupId,
            round = decision.round,
            fromStageId = decision.fromStageId,
            toStageId = targetStage.stageId,
            stagePayloadHash = targetStage.revealPayloadHash,
            deliveredPayloadHash = payload.payloadHash,
            authoritativeBundleHashes = decision.authoritativeBundleHashes
        ).withComputedHash()
        return payload to authorization
    }

    private fun reason(
        status: DisclosureDecisionStatus,
        coverage: MandatoryNodeCoverage
    ): String = when (status) {
        DisclosureDecisionStatus.ADVANCE -> "All mandatory-node and stage thresholds passed"
        DisclosureDecisionStatus.FINAL_STAGE_COMPLETE -> "Final stage requirements passed"
        DisclosureDecisionStatus.FREEZE_INVALID -> "Evaluation authority is invalid"
        DisclosureDecisionStatus.FREEZE_ABSTAINED -> "Evaluation quorum abstained"
        DisclosureDecisionStatus.HOLD_VALID_FAILURE ->
            "Stage requirements remain: missing=${coverage.missingMandatoryNodeIds.size}, " +
                "noEvidence=${coverage.noEvidenceMandatoryNodeIds.size}, " +
                "failed=${coverage.failedMandatoryNodeIds.size}, " +
                "unresolved=${coverage.unresolvedHardFailureNodeIds.size}"
    }

    private fun isExactPassingCoverage(
        bundle: NodeGradeBundle,
        atlas: NodeAtlas
    ): Boolean {
        if (!isExactBundle(bundle, atlas) ||
            bundle.verdict != NodeGradeBundleVerdict.VALID_PASS
        ) {
            return false
        }
        val node = atlas.nodes.getValue(bundle.nodeId)
        return requireNotNull(bundle.normalizedScore) >= node.capturePolicy.minimumGrade &&
            bundle.scoreVector?.hardFailure == false
    }

    private fun isExactBundle(
        bundle: NodeGradeBundle,
        atlas: NodeAtlas
    ): Boolean {
        val node = atlas.nodes[bundle.nodeId] ?: return false
        if (!bundle.hasValidHash() ||
            bundle.round < 0 ||
            bundle.atlasVersion != atlas.version ||
            bundle.atlasHash != atlas.atlasHash ||
            node.payloadHash != bundle.nodePayloadHash ||
            !AtlasHashing.constantTimeEquals(
                bundle.capturePolicyHash,
                AtlasHashing.hashCapturePolicy(node.capturePolicy)
            ) ||
            (bundle.evidencePacketId == null) != (bundle.evidencePacketHash == null) ||
            bundle.evidencePacketId?.isBlank() == true ||
            bundle.evidencePacketHash?.isBlank() == true ||
            bundle.criteriaHash.isBlank() ||
            bundle.requiredQuorum <= 0 ||
            bundle.graderIds.any(String::isBlank) ||
            bundle.completedGraderIds.any(String::isBlank) ||
            bundle.graderResponseHashes.any(String::isBlank) ||
            !bundle.completedGraderIds.all(bundle.graderIds::contains) ||
            bundle.graderResponseHashes.size != bundle.completedGraderIds.size
        ) {
            return false
        }
        val completeQuorum = bundle.completedGraderIds.size >= bundle.requiredQuorum
        if (bundle.verdict in QUORUM_VERDICTS && !completeQuorum) return false
        if (bundle.verdict == NodeGradeBundleVerdict.INCOMPLETE_QUORUM && completeQuorum) {
            return false
        }
        return if (bundle.verdict in VALID_EVIDENCE_VERDICTS) {
            bundle.evidencePacketId?.isNotBlank() == true &&
                bundle.evidencePacketHash?.isNotBlank() == true &&
                bundle.scoreVector?.let { vector ->
                    listOf(
                        vector.correctness,
                        vector.evidenceQuality,
                        vector.reproducibility,
                        vector.robustness,
                        vector.integrationUtility,
                        vector.receiverRelevance,
                        vector.novelty,
                        vector.efficiency,
                        vector.confidence
                    ).all { it.isFinite() && it in 0.0..1.0 }
                } == true &&
                bundle.normalizedScore?.let { it.isFinite() && it in 0.0..1.0 } == true
        } else {
            bundle.scoreVector == null &&
                bundle.normalizedScore == null &&
                (
                    bundle.verdict != NodeGradeBundleVerdict.NO_EVIDENCE ||
                        (bundle.evidencePacketId == null && bundle.evidencePacketHash == null)
                    )
        }
    }

    private companion object {
        val VALID_EVIDENCE_VERDICTS = setOf(
            NodeGradeBundleVerdict.VALID_PASS,
            NodeGradeBundleVerdict.VALID_FAIL
        )
        val QUORUM_VERDICTS = VALID_EVIDENCE_VERDICTS + NodeGradeBundleVerdict.NO_EVIDENCE
        val DEFECTIVE_AUTHORITY_VERDICTS = setOf(
            NodeGradeBundleVerdict.INVALID,
            NodeGradeBundleVerdict.ABSTAINED,
            NodeGradeBundleVerdict.MALFORMED,
            NodeGradeBundleVerdict.INCOMPLETE_QUORUM
        )
    }
}
