package com.achulkov.evomolior.api.deliverable

import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.CaptureState
import com.achulkov.evomolior.core.atlas.NodeAtlas
import com.achulkov.evomolior.core.atlas.NodeCaptureDecision
import com.achulkov.evomolior.core.atlas.NodeEvidencePacket
import com.achulkov.evomolior.core.atlas.NodeGradeBundle
import com.achulkov.evomolior.core.atlas.NodeGradeBundleVerdict
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.atlas.NodeKind
import com.achulkov.evomolior.core.atlas.ReceiverContract
import com.achulkov.evomolior.core.deliverable.ComponentAuthorityClass
import com.achulkov.evomolior.core.deliverable.DeliverableBlueprint
import com.achulkov.evomolior.core.deliverable.DeliverableComponentKind
import com.achulkov.evomolior.core.deliverable.InterimDeliverableSnapshot
import com.achulkov.evomolior.core.deliverable.ReceiverDeliverableBlueprintCompiler

/**
 * Exact sealed-round inputs used to compile one receiver-progress snapshot.
 *
 * Inputs are control-plane authorities only. No private test value, raw Critic prose, path,
 * payload, URL, stack trace, or model-private reasoning is accepted by this contract.
 *
 * @param receiverContract Exact sealed receiver authority.
 * @param atlas Current worker Atlas authority.
 * @param atlasHistory Complete validated Atlas lineage through [atlas].
 * @param runId Owning run.
 * @param round Sealed round.
 * @param groupId Group whose receiver progress is compiled.
 * @param evidencePackets Exact evidence authority available through [round].
 * @param gradeBundles Exact node-grade authority available through [round].
 * @param captureDecisions Exact capture history available through [round].
 * @param submissionSummaryPresent Whether a public round summary exists.
 * @param additionalFailedGateIds Additional typed control-plane failures.
 */
data class SealedRoundDeliverableSnapshotRequest(
    val receiverContract: ReceiverContract,
    val atlas: NodeAtlas,
    val atlasHistory: List<NodeAtlas> = listOf(atlas),
    val runId: String,
    val round: Int,
    val groupId: String,
    val evidencePackets: List<NodeEvidencePacket>,
    val gradeBundles: List<NodeGradeBundle>,
    val captureDecisions: List<NodeCaptureDecision>,
    val submissionSummaryPresent: Boolean,
    val additionalFailedGateIds: Set<String> = emptySet(),
)

/**
 * Deterministically derives the run deliverable blueprint and one group/round progress snapshot.
 *
 * The snapshot records authority coverage only; it does not synthesize receiver claims. Captured
 * node authority can mark a component accepted, sealed evidence can mark it provisional, and all
 * remaining components stay withheld.
 */
object SealedRoundDeliverableSnapshotCompiler {
    /** Compiles the stable receiver blueprint for the exact sealed [receiverContract]. */
    fun compileBlueprint(receiverContract: ReceiverContract): DeliverableBlueprint =
        ReceiverDeliverableBlueprintCompiler.compile(receiverContract)

    /** Compiles one immutable snapshot after all mutations for [request.round] are sealed. */
    fun compile(request: SealedRoundDeliverableSnapshotRequest): InterimDeliverableSnapshot {
        require(request.runId == request.atlas.runId && request.round >= 0) {
            "Round snapshot run and round authority are invalid"
        }
        require(
            AtlasHashing.constantTimeEquals(
                request.atlas.atlasHash,
                AtlasHashing.hashAtlas(request.atlas)
            )
        ) { "Round snapshot Atlas hash is invalid" }
        require(
            request.atlas.receiverContractHash ==
                AtlasHashing.hashReceiverContract(request.receiverContract)
        ) { "Round snapshot receiver contract does not match the sealed Atlas" }
        require(
            request.atlasHistory.isNotEmpty() &&
                request.atlasHistory.last() == request.atlas &&
                request.atlasHistory.map(NodeAtlas::version) ==
                (1..request.atlas.version).toList() &&
                request.atlasHistory.all {
                    it.runId == request.runId &&
                        it.atlasId == request.atlas.atlasId &&
                        it.receiverContractHash == request.atlas.receiverContractHash
                }
        ) { "Round snapshot Atlas history is incomplete, foreign, or non-canonical" }
        request.atlasHistory.forEachIndexed { index, historical ->
            historical.validate(request.atlasHistory.getOrNull(index - 1)).requireValid()
        }
        val atlasByVersion = request.atlasHistory.associateBy(NodeAtlas::version)
        val group = AtlasGroupId(request.groupId)
        val packets = request.evidencePackets
            .filter {
                val authorityAtlas = atlasByVersion[it.atlasVersion]
                val authorityNode = authorityAtlas?.nodes?.get(it.nodeId)
                val currentNode = request.atlas.nodes[it.nodeId]
                it.hasValidHash() &&
                    it.runId == request.runId &&
                    it.groupId == group &&
                    it.round <= request.round &&
                    authorityAtlas?.atlasHash == it.atlasHash &&
                    authorityNode?.payloadHash == it.nodePayloadHash &&
                    currentNode?.payloadHash == it.nodePayloadHash
            }
            .sortedWith(compareBy(NodeEvidencePacket::round, NodeEvidencePacket::packetId))
        require(packets.size == request.evidencePackets.size) {
            "Round snapshot evidence authority is foreign, future, or invalid"
        }
        val grades = request.gradeBundles
            .filter {
                val authorityAtlas = atlasByVersion[it.atlasVersion]
                val authorityNode = authorityAtlas?.nodes?.get(it.nodeId)
                val currentNode = request.atlas.nodes[it.nodeId]
                it.hasValidHash() &&
                    it.runId == request.runId &&
                    it.round <= request.round &&
                    authorityAtlas?.atlasHash == it.atlasHash &&
                    authorityNode?.payloadHash == it.nodePayloadHash &&
                    currentNode?.payloadHash == it.nodePayloadHash &&
                    authorityNode?.capturePolicy?.let(AtlasHashing::hashCapturePolicy) ==
                    it.capturePolicyHash &&
                    currentNode?.capturePolicy?.let(AtlasHashing::hashCapturePolicy) ==
                    it.capturePolicyHash
            }
            .sortedWith(compareBy(NodeGradeBundle::round, NodeGradeBundle::bundleId))
        require(grades.size == request.gradeBundles.size) {
            "Round snapshot grade authority is foreign, future, or invalid"
        }
        val gradesByHash = grades.associateBy(NodeGradeBundle::bundleHash)
        val decisions = request.captureDecisions
            .filter {
                val authorityAtlas = atlasByVersion[it.atlasVersion]
                val authorityNode = authorityAtlas?.nodes?.get(it.nodeId)
                val currentNode = request.atlas.nodes[it.nodeId]
                it.hasValidHash() &&
                    it.runId == request.runId &&
                    it.round <= request.round &&
                    authorityAtlas?.atlasHash == it.atlasHash &&
                    authorityNode?.payloadHash == it.nodePayloadHash &&
                    currentNode?.payloadHash == it.nodePayloadHash &&
                    authorityNode?.capturePolicy?.let(AtlasHashing::hashCapturePolicy) ==
                    it.capturePolicyHash &&
                    currentNode?.capturePolicy?.let(AtlasHashing::hashCapturePolicy) ==
                    it.capturePolicyHash &&
                    it.authoritativeBundleHashes.all { bundleHash ->
                        gradesByHash[bundleHash]?.nodeId == it.nodeId
                    }
            }
            .sortedWith(compareBy(NodeCaptureDecision::round, NodeCaptureDecision::decisionId))
        require(decisions.size == request.captureDecisions.size) {
            "Round snapshot capture authority is foreign, future, or invalid"
        }
        val captureHistoryByNode = decisions.groupBy(NodeCaptureDecision::nodeId)
        require(
            decisions.all { decision ->
                decision.resultingCaptureState !in ACCEPTED_CAPTURE_STATES ||
                    captureHistoryByNode
                        .getValue(decision.nodeId)
                        .any { candidate ->
                            candidate.round <= decision.round &&
                                candidate.decisionType in CAPTURE_AUTHORITY_DECISIONS &&
                                candidate.resultingCaptureState in ACCEPTED_CAPTURE_STATES &&
                                candidate.resultingOwnerGroupIds ==
                                decision.resultingOwnerGroupIds &&
                                candidate.authoritativeBundleHashes.isNotEmpty() &&
                                candidate.authoritativeBundleHashes.all { bundleHash ->
                                    gradesByHash[bundleHash]?.let { bundle ->
                                        bundle.nodeId == candidate.nodeId &&
                                            bundle.verdict ==
                                            NodeGradeBundleVerdict.VALID_PASS
                                    } == true
                                }
                        }
            }
        ) {
            "Round snapshot captured state lacks historical exact capture authority"
        }

        val blueprint = compileBlueprint(request.receiverContract)
        val nodeKinds = request.atlas.nodes.mapValues { it.value.kind }
        val provisionalNodeIds = packets.mapTo(linkedSetOf(), NodeEvidencePacket::nodeId)
        val acceptedNodeIds = captureHistoryByNode
            .mapValues { (_, history) -> history.maxBy(NodeCaptureDecision::round) }
            .filterValues { decision ->
                group in decision.resultingOwnerGroupIds &&
                    decision.resultingCaptureState in
                    setOf(CaptureState.CAPTURED, CaptureState.SHARED)
            }
            .keys

        val accepted = mutableListOf<String>()
        val provisional = mutableListOf<String>()
        val withheld = mutableListOf<String>()
        val missing = mutableListOf<String>()
        COMPONENTS.forEach { definition ->
            val acceptedForComponent = acceptedNodeIds.any { nodeId ->
                nodeKinds[nodeId] in definition.nodeKinds
            }
            val provisionalForComponent =
                acceptedForComponent ||
                    provisionalNodeIds.any { nodeId ->
                        nodeKinds[nodeId] in definition.nodeKinds
                    } ||
                    (
                        definition.kind == DeliverableComponentKind.EXECUTIVE_SUMMARY &&
                            request.submissionSummaryPresent
                        )
            val authority = when {
                acceptedForComponent -> ComponentAuthorityClass.ACCEPTED
                provisionalForComponent -> ComponentAuthorityClass.PROVISIONAL
                else -> ComponentAuthorityClass.WITHHELD
            }
            when (authority) {
                ComponentAuthorityClass.ACCEPTED -> accepted += definition.componentId
                ComponentAuthorityClass.PROVISIONAL -> provisional += definition.componentId
                ComponentAuthorityClass.WITHHELD -> withheld += definition.componentId
            }
            if (!authority.satisfies(definition.minimumAuthority)) {
                missing += definition.componentId
            }
        }

        val evidenceRefs = packets.flatMap { packet ->
            listOf(packet.packetId) +
                packet.acceptedArtifactIds +
                packet.acceptedClaimIds +
                packet.toolRunIds +
                packet.testResultIds +
                packet.negativeResultIds
        }.distinct().sorted()
        val latestGrades = grades
            .filter { it.groupId == group }
            .groupBy(NodeGradeBundle::nodeId)
            .values
            .flatMap { history ->
                val latestRound = history.maxOf(NodeGradeBundle::round)
                history.filter { it.round == latestRound }
            }
        val failedGateIds = (
            request.additionalFailedGateIds +
                latestGrades.filter {
                    it.nodeId !in acceptedNodeIds &&
                        it.verdict != NodeGradeBundleVerdict.VALID_PASS
                }
                    .map { "node:${it.nodeId.value}:grade:${it.verdict.name}" }
            ).distinct().sorted()
        return InterimDeliverableSnapshot(
            runId = request.runId,
            round = request.round,
            groupId = request.groupId,
            blueprintHash = blueprint.blueprintHash,
            acceptedComponentIds = accepted.sorted(),
            provisionalComponentIds = provisional.sorted(),
            withheldComponentIds = withheld.sorted(),
            failedGateIds = failedGateIds,
            evidenceRefs = evidenceRefs,
            missingAuthorityComponentIds = missing.sorted(),
        ).withComputedHash()
    }

    private data class ComponentDefinition(
        val componentId: String,
        val kind: DeliverableComponentKind,
        val required: Boolean,
        val minimumAuthority: ComponentAuthorityClass,
        val nodeKinds: Set<NodeKind>,
    )

    private val ACCEPTED_CAPTURE_STATES =
        setOf(CaptureState.CAPTURED, CaptureState.SHARED)

    private val CAPTURE_AUTHORITY_DECISIONS = setOf(
        com.achulkov.evomolior.core.atlas.NodeCaptureDecisionType.CAPTURED,
        com.achulkov.evomolior.core.atlas.NodeCaptureDecisionType.RECAPTURED,
        com.achulkov.evomolior.core.atlas.NodeCaptureDecisionType.SHARED_VALIDATION_CREDIT,
    )

    private val COMPONENTS = listOf(
        ComponentDefinition(
            "executive-summary",
            DeliverableComponentKind.EXECUTIVE_SUMMARY,
            true,
            ComponentAuthorityClass.PROVISIONAL,
            setOf(NodeKind.DELIVERY, NodeKind.INTEGRATION),
        ),
        ComponentDefinition(
            "decision-matrix",
            DeliverableComponentKind.DECISION_MATRIX,
            true,
            ComponentAuthorityClass.PROVISIONAL,
            setOf(NodeKind.DECISION, NodeKind.MODEL),
        ),
        ComponentDefinition(
            "recommendation",
            DeliverableComponentKind.RECOMMENDATION,
            true,
            ComponentAuthorityClass.ACCEPTED,
            setOf(NodeKind.DECISION),
        ),
        ComponentDefinition(
            "evidence-ledger",
            DeliverableComponentKind.EVIDENCE_LEDGER,
            true,
            ComponentAuthorityClass.PROVISIONAL,
            setOf(NodeKind.EVIDENCE, NodeKind.VALIDATION, NodeKind.EXPERIMENT),
        ),
        ComponentDefinition(
            "assumptions",
            DeliverableComponentKind.ASSUMPTIONS,
            true,
            ComponentAuthorityClass.PROVISIONAL,
            setOf(NodeKind.HYPOTHESIS),
        ),
        ComponentDefinition(
            "uncertainties",
            DeliverableComponentKind.UNCERTAINTIES,
            true,
            ComponentAuthorityClass.PROVISIONAL,
            setOf(NodeKind.VALIDATION, NodeKind.MODEL, NodeKind.SIMULATION),
        ),
        ComponentDefinition(
            "risk-register",
            DeliverableComponentKind.RISK_REGISTER,
            true,
            ComponentAuthorityClass.PROVISIONAL,
            setOf(NodeKind.VALIDATION, NodeKind.HYPOTHESIS),
        ),
        ComponentDefinition(
            "implementation-plan",
            DeliverableComponentKind.IMPLEMENTATION_PLAN,
            false,
            ComponentAuthorityClass.PROVISIONAL,
            setOf(NodeKind.INTEGRATION, NodeKind.DELIVERY),
        ),
        ComponentDefinition(
            "action-checklist",
            DeliverableComponentKind.ACTION_CHECKLIST,
            false,
            ComponentAuthorityClass.PROVISIONAL,
            setOf(NodeKind.DELIVERY, NodeKind.DECISION),
        ),
        ComponentDefinition(
            "tool-and-simulation-results",
            DeliverableComponentKind.TOOL_AND_SIMULATION_RESULTS,
            false,
            ComponentAuthorityClass.PROVISIONAL,
            setOf(NodeKind.TOOL, NodeKind.SIMULATION),
        ),
        ComponentDefinition(
            "alternative-views",
            DeliverableComponentKind.ALTERNATIVE_VIEWS,
            false,
            ComponentAuthorityClass.PROVISIONAL,
            setOf(NodeKind.HYPOTHESIS, NodeKind.MODEL),
        ),
        ComponentDefinition(
            "limitations-and-next-evidence",
            DeliverableComponentKind.LIMITATIONS_AND_NEXT_EVIDENCE,
            true,
            ComponentAuthorityClass.PROVISIONAL,
            setOf(NodeKind.VALIDATION, NodeKind.EVIDENCE, NodeKind.HYPOTHESIS),
        ),
        ComponentDefinition(
            "technical-appendix",
            DeliverableComponentKind.TECHNICAL_APPENDIX,
            false,
            ComponentAuthorityClass.PROVISIONAL,
            NodeKind.entries.toSet(),
        ),
    )
}
