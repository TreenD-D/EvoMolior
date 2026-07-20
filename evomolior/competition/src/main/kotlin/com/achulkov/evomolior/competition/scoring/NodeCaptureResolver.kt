package com.achulkov.evomolior.competition.scoring

import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.CaptureMode
import com.achulkov.evomolior.core.atlas.CaptureState
import com.achulkov.evomolior.core.atlas.GradeVerdict
import com.achulkov.evomolior.core.atlas.LimitationNode
import com.achulkov.evomolior.core.atlas.NodeAtlas
import com.achulkov.evomolior.core.atlas.NodeCaptureDecision
import com.achulkov.evomolior.core.atlas.NodeCaptureDecisionType
import com.achulkov.evomolior.core.atlas.NodeCaptureReasonCode
import com.achulkov.evomolior.core.atlas.NodeExecutionState
import com.achulkov.evomolior.core.atlas.NodeGrade
import com.achulkov.evomolior.core.atlas.NodeGradeBundle
import com.achulkov.evomolior.core.atlas.NodeGradeBundleVerdict
import com.achulkov.evomolior.core.atlas.ReplicationMode
import kotlin.math.abs

/**
 * Complete input for replaying one node's capture decision.
 *
 * @param atlas Exact sealed Atlas.
 * @param currentState Current append-only execution-state projection.
 * @param round Judging round.
 * @param expectedGroupIds Worker groups that must each supply exactly one bundle.
 * @param bundles Exact aggregated node-grade bundles.
 * @param graderCriteriaFreezeHashes Exact competitive grader freezes used to construct the
 * node-grading assignments. Empty only for legacy/offline node-only grading.
 * @param previousCaptureDecision Exact prior owner authority required for recapture.
 */
data class NodeCaptureResolutionRequest(
    val atlas: NodeAtlas,
    val currentState: NodeExecutionState,
    val round: Int,
    val expectedGroupIds: Set<AtlasGroupId>,
    val bundles: List<NodeGradeBundle>,
    val graderCriteriaFreezeHashes: Map<String, String> = emptyMap(),
    val previousCaptureDecision: NodeCaptureDecision? = null
)

/**
 * Pure replay result containing the audit decision and optional next execution-state snapshot.
 *
 * [nextState] is null when authority is invalid, no evidence exists, or no append-only state
 * material changes. Ownership and yields must remain untouched in those cases.
 */
data class NodeCaptureResolution(
    val decision: NodeCaptureDecision,
    val nextState: NodeExecutionState?,
    val expectedStateHash: String
)

/**
 * Resolves node capture exclusively from complete, exact, node-specific grader bundles.
 *
 * Completion time and generic group scores are intentionally absent from this API.
 */
class NodeCaptureResolver {
    /** Replays one deterministic capture decision. */
    fun resolve(request: NodeCaptureResolutionRequest): NodeCaptureResolution {
        require(request.round >= 0) { "Capture round cannot be negative" }
        require(request.expectedGroupIds.isNotEmpty()) {
            "Capture resolution requires at least one expected worker group"
        }
        val atlas = request.atlas
        require(atlas.hasValidHash()) { "Capture resolution requires a valid sealed Atlas hash" }
        val node = requireNotNull(atlas.nodes[request.currentState.nodeId]) {
            "Execution state references a node outside the sealed Atlas"
        }
        require(node.hasValidPayloadHash()) { "Capture node payload commitment is invalid" }
        require(request.currentState.round <= request.round) {
            "Capture resolution cannot move execution state backward"
        }

        if (node.capturePolicy.mode == CaptureMode.NONE) {
            return unchanged(
                request,
                node,
                NodeCaptureDecisionType.RETIRED,
                NodeCaptureReasonCode.CAPTURE_DISABLED,
                authoritativeBundles = emptySet()
            )
        }

        val groupedBundles = request.bundles.groupBy(NodeGradeBundle::groupId)
        if (groupedBundles.any { (_, bundles) -> bundles.size != 1 }) {
            return frozen(request, node, NodeCaptureReasonCode.DUPLICATE_GROUP_BUNDLE)
        }
        if (groupedBundles.keys != request.expectedGroupIds) {
            return frozen(request, node, NodeCaptureReasonCode.MISSING_GRADE_BUNDLE)
        }
        if (request.bundles.any { authorityDefect(it, request, node) != null }) {
            return frozen(request, node, NodeCaptureReasonCode.INVALID_GRADE_BUNDLE)
        }

        val authoritativeBundles = request.bundles.mapTo(linkedSetOf()) {
            it.bundleHash
        }
        val evaluated = request.bundles.filter {
            it.verdict == NodeGradeBundleVerdict.VALID_PASS ||
                it.verdict == NodeGradeBundleVerdict.VALID_FAIL
        }
        if (evaluated.isEmpty()) {
            return unchanged(
                request,
                node,
                NodeCaptureDecisionType.NO_EVIDENCE,
                NodeCaptureReasonCode.NO_EVIDENCE,
                authoritativeBundles
            )
        }

        val appendedState = appendEvaluatedGrades(request.currentState, request.round, evaluated)
        val passing = evaluated
            .filter {
                it.verdict == NodeGradeBundleVerdict.VALID_PASS &&
                    it.scoreVector?.hardFailure == false &&
                    requireNotNull(it.normalizedScore) >= node.capturePolicy.minimumGrade
            }
            .sortedWith(
                compareByDescending<NodeGradeBundle> { requireNotNull(it.normalizedScore) }
                    .thenBy { it.groupId.value }
                    .thenBy(NodeGradeBundle::bundleHash)
            )
        if (passing.isEmpty()) {
            return resolution(
                request = request,
                node = node,
                baseState = appendedState,
                decisionType = NodeCaptureDecisionType.BELOW_THRESHOLD,
                reasonCode = NodeCaptureReasonCode.BELOW_MINIMUM_GRADE,
                authoritativeBundles = evaluated.mapTo(linkedSetOf()) { it.bundleHash },
                resultingOwners = currentOwners(request.currentState),
                resultingCaptureState = request.currentState.captureState,
                winningScore = evaluated.maxOfOrNull { requireNotNull(it.normalizedScore) },
                yieldEligible = false
            )
        }

        val top = passing.first()
        val topScore = requireNotNull(top.normalizedScore)
        val nearTieMargin = node.capturePolicy.nearTieMargin ?: 0.0
        val nearTies = passing.takeWhile {
            topScore - requireNotNull(it.normalizedScore) <= nearTieMargin + SCORE_TOLERANCE
        }
        if (nearTies.size > 1) {
            val tiedOwners = nearTies.mapTo(linkedSetOf(), NodeGradeBundle::groupId)
            val shared = node.capturePolicy.mode == CaptureMode.SHARED
            return resolution(
                request = request,
                node = node,
                baseState = appendedState,
                decisionType = if (shared) {
                    NodeCaptureDecisionType.SHARED_VALIDATION_CREDIT
                } else {
                    NodeCaptureDecisionType.CONTESTED
                },
                reasonCode = NodeCaptureReasonCode.NEAR_TIE,
                authoritativeBundles = nearTies.mapTo(linkedSetOf()) { it.bundleHash },
                resultingOwners = if (shared) tiedOwners else currentOwners(request.currentState),
                resultingCaptureState = if (shared) {
                    CaptureState.SHARED
                } else {
                    CaptureState.CONTESTED
                },
                winningScore = topScore,
                yieldEligible = shared
            )
        }

        val replicationPending = node.replicationPolicy.mode in REPLICATION_REQUIRED_MODES &&
            passing.size < node.replicationPolicy.requiredCount
        if (replicationPending) {
            return resolution(
                request = request,
                node = node,
                baseState = appendedState,
                decisionType = NodeCaptureDecisionType.PROVISIONAL_CAPTURE,
                reasonCode = NodeCaptureReasonCode.REPLICATION_PENDING,
                authoritativeBundles = setOf(top.bundleHash),
                resultingOwners = setOf(top.groupId),
                resultingCaptureState = CaptureState.CONTESTED,
                winningScore = topScore,
                yieldEligible = false
            )
        }

        val currentOwner = request.currentState.ownerGroupId
        if (currentOwner == null || currentOwner == top.groupId) {
            return resolution(
                request = request,
                node = node,
                baseState = appendedState,
                decisionType = NodeCaptureDecisionType.CAPTURED,
                reasonCode = NodeCaptureReasonCode.BEST_VALID_GRADE,
                authoritativeBundles = setOf(top.bundleHash),
                resultingOwners = setOf(top.groupId),
                resultingCaptureState = CaptureState.CAPTURED,
                winningScore = topScore,
                priorWinningScore = request.previousCaptureDecision?.winningScore,
                yieldEligible = true
            )
        }

        val priorDecision = request.previousCaptureDecision
        if (!validPriorOwnerAuthority(request, priorDecision, currentOwner)) {
            return frozen(request, node, NodeCaptureReasonCode.OWNER_AUTHORITY_MISSING)
        }
        val priorScore = requireNotNull(priorDecision?.winningScore)
        if (topScore + SCORE_TOLERANCE < priorScore + node.capturePolicy.recaptureMargin) {
            return resolution(
                request = request,
                node = node,
                baseState = appendedState,
                decisionType = NodeCaptureDecisionType.INSUFFICIENT_RECAPTURE_MARGIN,
                reasonCode = NodeCaptureReasonCode.RECAPTURE_MARGIN_NOT_MET,
                authoritativeBundles = setOf(top.bundleHash),
                resultingOwners = setOf(currentOwner),
                resultingCaptureState = request.currentState.captureState,
                winningScore = topScore,
                priorWinningScore = priorScore,
                yieldEligible = false
            )
        }
        return resolution(
            request = request,
            node = node,
            baseState = appendedState,
            decisionType = NodeCaptureDecisionType.RECAPTURED,
            reasonCode = NodeCaptureReasonCode.RECAPTURE_MARGIN_MET,
            authoritativeBundles = setOf(top.bundleHash),
            resultingOwners = setOf(top.groupId),
            resultingCaptureState = CaptureState.CAPTURED,
            winningScore = topScore,
            priorWinningScore = priorScore,
            yieldEligible = true
        )
    }

    private fun authorityDefect(
        bundle: NodeGradeBundle,
        request: NodeCaptureResolutionRequest,
        node: LimitationNode
    ): String? {
        if (!bundle.hasValidHash()) return "INVALID_HASH"
        if (bundle.runId != request.atlas.runId ||
            bundle.round != request.round ||
            bundle.nodeId != node.id ||
            bundle.atlasVersion != request.atlas.version ||
            !AtlasHashing.constantTimeEquals(bundle.atlasHash, request.atlas.atlasHash) ||
            !AtlasHashing.constantTimeEquals(bundle.nodePayloadHash, node.payloadHash) ||
            !AtlasHashing.constantTimeEquals(
                bundle.capturePolicyHash,
                AtlasHashing.hashCapturePolicy(node.capturePolicy)
            ) ||
            !AtlasHashing.constantTimeEquals(
                bundle.criteriaHash,
                AtlasHashing.hashNodeGradingAuthority(
                    node,
                    request.graderCriteriaFreezeHashes
                )
            )
        ) {
            return "IDENTITY_MISMATCH"
        }
        if (request.graderCriteriaFreezeHashes.isNotEmpty() &&
            (
                bundle.graderIds != request.graderCriteriaFreezeHashes.keys ||
                    bundle.requiredQuorum != request.graderCriteriaFreezeHashes.size
                )
        ) {
            return "GRADER_FREEZE_AUTHORITY_MISMATCH"
        }
        if (bundle.requiredQuorum <= 0 ||
            !bundle.completedGraderIds.all(bundle.graderIds::contains) ||
            bundle.completedGraderIds.size < bundle.requiredQuorum ||
            bundle.graderResponseHashes.size != bundle.completedGraderIds.size
        ) {
            return "QUORUM_INVALID"
        }
        return when (bundle.verdict) {
            NodeGradeBundleVerdict.VALID_PASS,
            NodeGradeBundleVerdict.VALID_FAIL -> {
                val vector = bundle.scoreVector ?: return "MISSING_VECTOR"
                val normalizedScore = bundle.normalizedScore ?: return "MISSING_SCORE"
                if (bundle.evidencePacketId.isNullOrBlank() ||
                    bundle.evidencePacketHash.isNullOrBlank() ||
                    vector.values().any { !it.isFinite() || it !in 0.0..1.0 } ||
                    !normalizedScore.isFinite() ||
                    normalizedScore !in 0.0..1.0
                ) {
                    "MALFORMED_EVALUATION"
                } else if (
                    bundle.verdict == NodeGradeBundleVerdict.VALID_PASS &&
                    (
                        vector.hardFailure ||
                            normalizedScore + SCORE_TOLERANCE <
                            node.capturePolicy.minimumGrade
                        )
                ) {
                    "PASS_POLICY_MISMATCH"
                } else if (
                    bundle.verdict == NodeGradeBundleVerdict.VALID_FAIL &&
                    !vector.hardFailure &&
                    normalizedScore + SCORE_TOLERANCE >= node.capturePolicy.minimumGrade
                ) {
                    "FAIL_POLICY_MISMATCH"
                } else {
                    null
                }
            }
            NodeGradeBundleVerdict.NO_EVIDENCE -> {
                if (bundle.scoreVector != null ||
                    bundle.normalizedScore != null ||
                    bundle.evidencePacketId != null ||
                    bundle.evidencePacketHash != null
                ) {
                    "MALFORMED_NO_EVIDENCE"
                } else {
                    null
                }
            }
            NodeGradeBundleVerdict.INVALID,
            NodeGradeBundleVerdict.ABSTAINED,
            NodeGradeBundleVerdict.MALFORMED,
            NodeGradeBundleVerdict.INCOMPLETE_QUORUM -> bundle.verdict.name
        }
    }

    private fun appendEvaluatedGrades(
        state: NodeExecutionState,
        round: Int,
        bundles: List<NodeGradeBundle>
    ): NodeExecutionState {
        val knownBundleHashes = state.grades.mapTo(mutableSetOf(), NodeGrade::scoreBundleHash)
        val newGrades = bundles
            .filterNot { it.bundleHash in knownBundleHashes }
            .map { bundle ->
                NodeGrade(
                    gradeId = bundle.bundleId,
                    groupId = bundle.groupId,
                    evidencePacketId = requireNotNull(bundle.evidencePacketId),
                    evidencePacketHash = requireNotNull(bundle.evidencePacketHash),
                    scoreBundleHash = bundle.bundleHash,
                    scoreVector = bundle.scoreVector,
                    criteriaHash = bundle.criteriaHash,
                    capturePolicyHash = bundle.capturePolicyHash,
                    normalizedScore = requireNotNull(bundle.normalizedScore),
                    verdict = when (bundle.verdict) {
                        NodeGradeBundleVerdict.VALID_PASS -> GradeVerdict.VALID_PASS
                        NodeGradeBundleVerdict.VALID_FAIL -> GradeVerdict.VALID_FAIL
                        else -> error("Only evaluated bundles can become Atlas grades")
                    },
                    graderIds = bundle.completedGraderIds,
                    round = bundle.round
                )
            }
        val packetIds = bundles.mapNotNull(NodeGradeBundle::evidencePacketId)
        return state.copy(
            round = round,
            evidencePacketIds = (state.evidencePacketIds + packetIds).distinct(),
            grades = state.grades + newGrades
        )
    }

    private fun frozen(
        request: NodeCaptureResolutionRequest,
        node: LimitationNode,
        reasonCode: NodeCaptureReasonCode
    ): NodeCaptureResolution = unchanged(
        request = request,
        node = node,
        decisionType = NodeCaptureDecisionType.FROZEN_INVALID_AUTHORITY,
        reasonCode = reasonCode,
        authoritativeBundles = emptySet()
    )

    private fun unchanged(
        request: NodeCaptureResolutionRequest,
        node: LimitationNode,
        decisionType: NodeCaptureDecisionType,
        reasonCode: NodeCaptureReasonCode,
        authoritativeBundles: Set<String>
    ): NodeCaptureResolution {
        val decision = decision(
            request = request,
            node = node,
            decisionType = decisionType,
            reasonCode = reasonCode,
            authoritativeBundles = authoritativeBundles,
            resultingOwners = currentOwners(request.currentState),
            resultingCaptureState = request.currentState.captureState,
            winningScore = null,
            priorWinningScore = request.previousCaptureDecision?.winningScore,
            yieldEligible = false
        )
        return NodeCaptureResolution(
            decision = decision,
            nextState = null,
            expectedStateHash = AtlasHashing.hashExecutionState(request.currentState)
        )
    }

    private fun resolution(
        request: NodeCaptureResolutionRequest,
        node: LimitationNode,
        baseState: NodeExecutionState,
        decisionType: NodeCaptureDecisionType,
        reasonCode: NodeCaptureReasonCode,
        authoritativeBundles: Set<String>,
        resultingOwners: Set<AtlasGroupId>,
        resultingCaptureState: CaptureState,
        winningScore: Double?,
        priorWinningScore: Double? = null,
        yieldEligible: Boolean
    ): NodeCaptureResolution {
        val nextOwner = resultingOwners.singleOrNull()
        val nextState = baseState.copy(
            ownerGroupId = nextOwner,
            captureState = resultingCaptureState
        )
        val decision = decision(
            request,
            node,
            decisionType,
            reasonCode,
            authoritativeBundles,
            resultingOwners,
            resultingCaptureState,
            winningScore,
            priorWinningScore,
            yieldEligible
        )
        return NodeCaptureResolution(
            decision = decision,
            nextState = nextState.takeIf { it != request.currentState },
            expectedStateHash = AtlasHashing.hashExecutionState(request.currentState)
        )
    }

    private fun decision(
        request: NodeCaptureResolutionRequest,
        node: LimitationNode,
        decisionType: NodeCaptureDecisionType,
        reasonCode: NodeCaptureReasonCode,
        authoritativeBundles: Set<String>,
        resultingOwners: Set<AtlasGroupId>,
        resultingCaptureState: CaptureState,
        winningScore: Double?,
        priorWinningScore: Double?,
        yieldEligible: Boolean
    ): NodeCaptureDecision {
        val preliminary = NodeCaptureDecision(
            decisionId = "",
            runId = request.atlas.runId,
            round = request.round,
            nodeId = node.id,
            atlasVersion = request.atlas.version,
            atlasHash = request.atlas.atlasHash,
            nodePayloadHash = node.payloadHash,
            capturePolicyHash = AtlasHashing.hashCapturePolicy(node.capturePolicy),
            candidateBundleHashes = request.bundles.mapTo(linkedSetOf()) { it.bundleHash },
            authoritativeBundleHashes = authoritativeBundles,
            previousOwnerGroupId = request.currentState.ownerGroupId,
            previousAuthoritativeBundleHash =
                request.previousCaptureDecision?.authoritativeBundleHashes?.singleOrNull(),
            resultingOwnerGroupIds = resultingOwners,
            resultingCaptureState = resultingCaptureState,
            decisionType = decisionType,
            winningScore = winningScore,
            priorWinningScore = priorWinningScore,
            yieldEligible = yieldEligible,
            reasonCode = reasonCode
        )
        val decisionId = "capture:${AtlasHashing.hashNodeCaptureDecision(preliminary)}"
        return preliminary.copy(decisionId = decisionId).withComputedHash()
    }

    private fun validPriorOwnerAuthority(
        request: NodeCaptureResolutionRequest,
        decision: NodeCaptureDecision?,
        owner: AtlasGroupId
    ): Boolean = decision != null &&
        decision.hasValidHash() &&
        decision.runId == request.atlas.runId &&
        decision.nodeId == request.currentState.nodeId &&
        decision.atlasVersion == request.atlas.version &&
        decision.atlasHash == request.atlas.atlasHash &&
        owner in decision.resultingOwnerGroupIds &&
        decision.winningScore != null &&
        decision.authoritativeBundleHashes.isNotEmpty()

    private fun currentOwners(state: NodeExecutionState): Set<AtlasGroupId> =
        state.ownerGroupId?.let(::setOf).orEmpty()

    private fun com.achulkov.evomolior.core.atlas.NodeScoreVector.values(): List<Double> = listOf(
        correctness,
        evidenceQuality,
        reproducibility,
        robustness,
        integrationUtility,
        receiverRelevance,
        novelty,
        efficiency,
        confidence
    )

    private companion object {
        const val SCORE_TOLERANCE = 1e-9
        val REPLICATION_REQUIRED_MODES = setOf(
            ReplicationMode.REQUIRED,
            ReplicationMode.ADVERSARIAL
        )
    }
}

private fun NodeAtlas.hasValidHash(): Boolean =
    AtlasHashing.constantTimeEquals(atlasHash, AtlasHashing.hashAtlas(this))
