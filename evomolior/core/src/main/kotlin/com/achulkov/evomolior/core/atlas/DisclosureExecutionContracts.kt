package com.achulkov.evomolior.core.atlas

import com.achulkov.evomolior.core.contract.GroupFeedbackPacket
import com.achulkov.evomolior.core.contract.RevealedNodeCard
import kotlinx.serialization.Serializable

/** Exact upstream evaluation status used by progressive-disclosure authority. */
@Serializable
enum class DisclosureEvaluationStatus {
    VALID,
    INVALID,
    ABSTAINED
}

/** Typed result of evaluating one group's current disclosure stage. */
@Serializable
enum class DisclosureDecisionStatus {
    ADVANCE,
    HOLD_VALID_FAILURE,
    FREEZE_INVALID,
    FREEZE_ABSTAINED,
    FINAL_STAGE_COMPLETE
}

/**
 * Replayable mandatory-node coverage calculation for one group and stage.
 */
@Serializable
data class MandatoryNodeCoverage(
    val stageId: String,
    val mandatoryNodeIds: Set<NodeId>,
    val passingMandatoryNodeIds: Set<NodeId>,
    val failedMandatoryNodeIds: Set<NodeId>,
    val missingMandatoryNodeIds: Set<NodeId>,
    val noEvidenceMandatoryNodeIds: Set<NodeId>,
    val unresolvedHardFailureNodeIds: Set<NodeId>,
    val evidenceCoverage: Double,
    val integrationReadiness: Double
)

/**
 * Exact disclosure decision made from sealed Atlas and node-grade authority.
 */
@Serializable
data class DisclosureDecision(
    val runId: String,
    val atlasId: String,
    val atlasVersion: Int,
    val atlasHash: String,
    val groupId: AtlasGroupId,
    val round: Int,
    val fromStageId: String,
    val fromStageIndex: Int,
    val toStageId: String?,
    val toStageIndex: Int?,
    val evaluationStatus: DisclosureEvaluationStatus,
    val status: DisclosureDecisionStatus,
    val coverage: MandatoryNodeCoverage,
    val authoritativeBundleHashes: Set<String>,
    val reason: String,
    val decisionHash: String = ""
) {
    /** Returns this decision with its deterministic commitment attached. */
    fun withComputedHash(): DisclosureDecision =
        copy(decisionHash = AtlasHashing.hashDisclosureDecision(this))

    /** Verifies the exact decision commitment. */
    fun hasValidHash(): Boolean =
        AtlasHashing.constantTimeEquals(
            decisionHash,
            AtlasHashing.hashDisclosureDecision(this)
        )
}

/**
 * Exact group-local content delivered when one stage is revealed.
 */
@Serializable
data class GroupRevealPayload(
    val runId: String,
    val atlasId: String,
    val atlasVersion: Int,
    val atlasHash: String,
    val groupId: AtlasGroupId,
    val round: Int,
    val stageId: String,
    val stageIndex: Int,
    val stagePayloadHash: String,
    val revealedNodes: List<RevealedNodeCard>,
    val payloadHash: String = ""
) {
    /** Returns this payload with its deterministic delivery commitment attached. */
    fun withComputedHash(): GroupRevealPayload =
        copy(payloadHash = AtlasHashing.hashGroupRevealPayload(this))

    /** Verifies both the group-local delivery hash and sealed stage commitment. */
    fun hasValidHashes(atlas: NodeAtlas): Boolean {
        val stage = atlas.stageDefinitions.singleOrNull { it.stageId == stageId } ?: return false
        val expectedStageHash = AtlasHashing.hashDisclosurePayload(
            stage.stageId,
            stage.nodeIds,
            atlas.nodes
        )
        return atlas.atlasId == atlasId &&
            atlas.version == atlasVersion &&
            atlas.atlasHash == atlasHash &&
            stage.stageIndex == stageIndex &&
            AtlasHashing.constantTimeEquals(stagePayloadHash, expectedStageHash) &&
            revealedNodes.mapTo(linkedSetOf()) { it.nodeId } == stage.nodeIds &&
            revealedNodes.all { card ->
                atlas.nodes[card.nodeId]?.payloadHash == card.payloadHash
            } &&
            AtlasHashing.constantTimeEquals(payloadHash, AtlasHashing.hashGroupRevealPayload(this))
    }
}

/**
 * Append-only authorization binding a valid decision to the exact delivered payload.
 */
@Serializable
data class DisclosureRevealAuthorization(
    val authorizationId: String,
    val decisionHash: String,
    val groupId: AtlasGroupId,
    val round: Int,
    val fromStageId: String,
    val toStageId: String,
    val stagePayloadHash: String,
    val deliveredPayloadHash: String,
    val authoritativeBundleHashes: Set<String>,
    val authorizationHash: String = ""
) {
    /** Returns this authorization with its deterministic commitment attached. */
    fun withComputedHash(): DisclosureRevealAuthorization =
        copy(authorizationHash = AtlasHashing.hashDisclosureRevealAuthorization(this))

    /** Verifies the authorization commitment. */
    fun hasValidHash(): Boolean =
        AtlasHashing.constantTimeEquals(
            authorizationHash,
            AtlasHashing.hashDisclosureRevealAuthorization(this)
        )
}

/** Append-only disclosure lineage retained in Atlas execution state. */
@Serializable
data class DisclosureLineageEntry(
    val authorization: DisclosureRevealAuthorization,
    val feedback: GroupFeedbackPacket? = null
)

/**
 * Three-way rationale split. Only [workerFeedback] may enter an ordinary worker view.
 */
@Serializable
data class DisclosureFeedbackSplit(
    val internalRationale: String,
    val workerFeedback: GroupFeedbackPacket?,
    val futureDisclosureNote: String?,
    val splitHash: String = ""
) {
    /** Returns this split with its deterministic commitment attached. */
    fun withComputedHash(): DisclosureFeedbackSplit =
        copy(splitHash = AtlasHashing.hashDisclosureFeedbackSplit(this))

    /** Verifies the split commitment. */
    fun hasValidHash(): Boolean =
        AtlasHashing.constantTimeEquals(splitHash, AtlasHashing.hashDisclosureFeedbackSplit(this))
}

/** Optional bounded authorization to reveal one adjacent node without advancing a stage. */
@Serializable
data class AdjacentDisclosureToken(
    val tokenId: String,
    val runId: String,
    val atlasHash: String,
    val groupId: AtlasGroupId,
    val nodeId: NodeId,
    val round: Int,
    val expiresAfterRound: Int,
    val tokenHash: String = ""
) {
    init {
        require(expiresAfterRound >= round) {
            "Adjacent disclosure token cannot expire before its grant round"
        }
    }

    /** Returns this token with its deterministic commitment attached. */
    fun withComputedHash(): AdjacentDisclosureToken =
        copy(tokenHash = AtlasHashing.hashAdjacentDisclosureToken(this))

    /** Verifies the token commitment. */
    fun hasValidHash(): Boolean =
        AtlasHashing.constantTimeEquals(tokenHash, AtlasHashing.hashAdjacentDisclosureToken(this))
}
