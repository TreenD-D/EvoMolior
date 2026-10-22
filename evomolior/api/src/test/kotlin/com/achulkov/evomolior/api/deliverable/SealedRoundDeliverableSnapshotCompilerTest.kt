package com.achulkov.evomolior.api.deliverable

import com.achulkov.evomolior.core.atlas.ArtifactType
import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.CapturePolicy
import com.achulkov.evomolior.core.atlas.CaptureState
import com.achulkov.evomolior.core.atlas.DisclosureStage
import com.achulkov.evomolior.core.atlas.EvidenceKind
import com.achulkov.evomolior.core.atlas.EvidenceProfile
import com.achulkov.evomolior.core.atlas.FinalOutputSpec
import com.achulkov.evomolior.core.atlas.LimitationNode
import com.achulkov.evomolior.core.atlas.NodeAtlas
import com.achulkov.evomolior.core.atlas.NodeDeliverableContract
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.atlas.NodeGradeBundle
import com.achulkov.evomolior.core.atlas.NodeGradeBundleVerdict
import com.achulkov.evomolior.core.atlas.NodeCaptureDecision
import com.achulkov.evomolior.core.atlas.NodeCaptureDecisionType
import com.achulkov.evomolior.core.atlas.NodeCaptureReasonCode
import com.achulkov.evomolior.core.atlas.NodeKind
import com.achulkov.evomolior.core.atlas.NodeScoreVector
import com.achulkov.evomolior.core.atlas.ProofContract
import com.achulkov.evomolior.core.atlas.ProofMethod
import com.achulkov.evomolior.core.atlas.ReceiverContract
import com.achulkov.evomolior.core.atlas.ReplicationPolicy
import com.achulkov.evomolior.core.atlas.RevealMode
import com.achulkov.evomolior.core.atlas.RevealPolicy
import com.achulkov.evomolior.core.atlas.RewardClass
import com.achulkov.evomolior.core.atlas.RewardSpec
import com.achulkov.evomolior.core.deliverable.DeliverableFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SealedRoundDeliverableSnapshotCompilerTest {
    @Test
    fun `summary progress compiles without inventing accepted decision authority`() {
        val receiver = receiver()
        val atlas = atlas(receiver)

        val blueprint = SealedRoundDeliverableSnapshotCompiler.compileBlueprint(receiver)
        val snapshot = SealedRoundDeliverableSnapshotCompiler.compile(
            SealedRoundDeliverableSnapshotRequest(
                receiverContract = receiver,
                atlas = atlas,
                runId = atlas.runId,
                round = 2,
                groupId = "group-a",
                evidencePackets = emptyList(),
                gradeBundles = emptyList(),
                captureDecisions = emptyList(),
                submissionSummaryPresent = true,
                additionalFailedGateIds = setOf("sealed-suite:mandatory-failure"),
            )
        )

        assertTrue(blueprint.hasValidHash())
        assertEquals(DeliverableFormat.HTML5, blueprint.primaryFormat)
        assertEquals(setOf(DeliverableFormat.JSON), blueprint.companionFormats)
        assertTrue(snapshot.hasValidHash())
        assertEquals(listOf("executive-summary"), snapshot.provisionalComponentIds)
        assertTrue(snapshot.acceptedComponentIds.isEmpty())
        assertTrue("recommendation" in snapshot.withheldComponentIds)
        assertTrue("recommendation" in snapshot.missingAuthorityComponentIds)
        assertEquals(
            listOf("sealed-suite:mandatory-failure"),
            snapshot.failedGateIds
        )
        assertFalse(snapshot.toString().contains("private-fixture"))
    }

    @Test
    fun `later valid grade supersedes historical no evidence gate`() {
        val receiver = receiver()
        val atlas = atlas(receiver)
        val node = atlas.nodes.getValue(NodeId("decision-node"))
        val noEvidence = gradeBundle(
            atlas = atlas,
            verdict = NodeGradeBundleVerdict.NO_EVIDENCE,
            round = 0,
        )
        val validPass = gradeBundle(
            atlas = atlas,
            verdict = NodeGradeBundleVerdict.VALID_PASS,
            round = 1,
        )

        val snapshot = SealedRoundDeliverableSnapshotCompiler.compile(
            SealedRoundDeliverableSnapshotRequest(
                receiverContract = receiver,
                atlas = atlas,
                runId = atlas.runId,
                round = 1,
                groupId = "group-a",
                evidencePackets = emptyList(),
                gradeBundles = listOf(noEvidence, validPass),
                captureDecisions = emptyList(),
                submissionSummaryPresent = false,
            )
        )

        assertTrue(snapshot.failedGateIds.isEmpty())
        assertEquals(node.id, validPass.nodeId)
    }

    @Test
    fun `later no evidence does not revoke accepted capture authority`() {
        val receiver = receiver()
        val atlas = atlas(receiver)
        val validPass = gradeBundle(
            atlas = atlas,
            verdict = NodeGradeBundleVerdict.VALID_PASS,
            round = 0,
        )
        val noEvidence = gradeBundle(
            atlas = atlas,
            verdict = NodeGradeBundleVerdict.NO_EVIDENCE,
            round = 1,
        )
        val capture = acceptedCapture(atlas, validPass)

        val snapshot = SealedRoundDeliverableSnapshotCompiler.compile(
            SealedRoundDeliverableSnapshotRequest(
                receiverContract = receiver,
                atlas = atlas,
                runId = atlas.runId,
                round = 1,
                groupId = "group-a",
                evidencePackets = emptyList(),
                gradeBundles = listOf(validPass, noEvidence),
                captureDecisions = listOf(capture),
                submissionSummaryPresent = false,
            )
        )

        assertTrue(snapshot.failedGateIds.isEmpty())
        assertTrue("recommendation" in snapshot.acceptedComponentIds)
    }

    @Test
    fun `later frozen audit preserves earlier exact capture authority`() {
        val receiver = receiver()
        val atlas = atlas(receiver)
        val validPass = gradeBundle(
            atlas = atlas,
            verdict = NodeGradeBundleVerdict.VALID_PASS,
            round = 0,
        )
        val capture = acceptedCapture(atlas, validPass)
        val frozen = capture.copy(
            decisionId = "capture-frozen-audit",
            round = 1,
            candidateBundleHashes = emptySet(),
            authoritativeBundleHashes = emptySet(),
            previousOwnerGroupId = validPass.groupId,
            previousAuthoritativeBundleHash = validPass.bundleHash,
            decisionType = NodeCaptureDecisionType.FROZEN_INVALID_AUTHORITY,
            winningScore = null,
            priorWinningScore = validPass.normalizedScore,
            yieldEligible = false,
            reasonCode = NodeCaptureReasonCode.OWNER_AUTHORITY_MISSING,
            decisionHash = "",
        ).withComputedHash()

        val snapshot = SealedRoundDeliverableSnapshotCompiler.compile(
            SealedRoundDeliverableSnapshotRequest(
                receiverContract = receiver,
                atlas = atlas,
                runId = atlas.runId,
                round = 1,
                groupId = "group-a",
                evidencePackets = emptyList(),
                gradeBundles = listOf(validPass),
                captureDecisions = listOf(capture, frozen),
                submissionSummaryPresent = false,
            )
        )

        assertTrue("recommendation" in snapshot.acceptedComponentIds)
    }

    @Test
    fun `captured audit state without historical exact capture authority is rejected`() {
        val receiver = receiver()
        val atlas = atlas(receiver)
        val validPass = gradeBundle(
            atlas = atlas,
            verdict = NodeGradeBundleVerdict.VALID_PASS,
            round = 0,
        )
        val frozen = acceptedCapture(atlas, validPass).copy(
            decisionId = "capture-frozen-without-origin",
            round = 1,
            candidateBundleHashes = emptySet(),
            authoritativeBundleHashes = emptySet(),
            previousOwnerGroupId = validPass.groupId,
            previousAuthoritativeBundleHash = validPass.bundleHash,
            decisionType = NodeCaptureDecisionType.FROZEN_INVALID_AUTHORITY,
            winningScore = null,
            priorWinningScore = validPass.normalizedScore,
            yieldEligible = false,
            reasonCode = NodeCaptureReasonCode.OWNER_AUTHORITY_MISSING,
            decisionHash = "",
        ).withComputedHash()

        assertFailsWith<IllegalArgumentException> {
            SealedRoundDeliverableSnapshotCompiler.compile(
                SealedRoundDeliverableSnapshotRequest(
                    receiverContract = receiver,
                    atlas = atlas,
                    runId = atlas.runId,
                    round = 1,
                    groupId = "group-a",
                    evidencePackets = emptyList(),
                    gradeBundles = listOf(validPass),
                    captureDecisions = listOf(frozen),
                    submissionSummaryPresent = false,
                )
            )
        }
    }

    @Test
    fun `foreign Atlas grade authority is rejected before snapshot classification`() {
        val receiver = receiver()
        val atlas = atlas(receiver)
        val foreign = gradeBundle(
            atlas = atlas,
            verdict = NodeGradeBundleVerdict.NO_EVIDENCE,
            round = 0,
        ).copy(
            atlasHash = AtlasHashing.hashText("foreign-atlas"),
            bundleHash = "",
        ).withComputedHash()

        assertFailsWith<IllegalArgumentException> {
            SealedRoundDeliverableSnapshotCompiler.compile(
                SealedRoundDeliverableSnapshotRequest(
                    receiverContract = receiver,
                    atlas = atlas,
                    runId = atlas.runId,
                    round = 0,
                    groupId = "group-a",
                    evidencePackets = emptyList(),
                    gradeBundles = listOf(foreign),
                    captureDecisions = emptyList(),
                    submissionSummaryPresent = false,
                )
            )
        }
    }

    @Test
    fun `accepted capture requires its exact authoritative grade bundle`() {
        val receiver = receiver()
        val atlas = atlas(receiver)
        val validPass = gradeBundle(
            atlas = atlas,
            verdict = NodeGradeBundleVerdict.VALID_PASS,
            round = 0,
        )
        val capture = acceptedCapture(atlas, validPass)

        assertFailsWith<IllegalArgumentException> {
            SealedRoundDeliverableSnapshotCompiler.compile(
                SealedRoundDeliverableSnapshotRequest(
                    receiverContract = receiver,
                    atlas = atlas,
                    runId = atlas.runId,
                    round = 0,
                    groupId = "group-a",
                    evidencePackets = emptyList(),
                    gradeBundles = emptyList(),
                    captureDecisions = listOf(capture),
                    submissionSummaryPresent = false,
                )
            )
        }
    }

    private fun receiver() = ReceiverContract(
        rawObjective = "Choose an evidence-backed action.",
        receiverId = "operator",
        receiverDescription = "Emergency manager",
        situation = "A bounded operational decision",
        decisionOrUse = "Select the safest feasible intervention",
        whyNeeded = "Action is time sensitive",
        successOutcomes = listOf("A supported decision"),
        unacceptableOutcomes = listOf("Unsupported recommendation"),
        constraints = listOf("Preserve uncertainty"),
        finalDeliverableContract = FinalOutputSpec(
            relativePath = "decision.html",
            mediaType = "text/html",
            requiredSections = setOf("Recommendation"),
            minimumEvidenceItems = 1,
        ),
        evidenceProfile = EvidenceProfile(
            acceptedEvidenceKinds = setOf(EvidenceKind.PRIMARY_SOURCE)
        ),
    )

    private fun atlas(receiver: ReceiverContract): NodeAtlas {
        val nodeId = NodeId("decision-node")
        val node = LimitationNode(
            id = nodeId,
            kind = NodeKind.DECISION,
            layer = 0,
            parentIds = emptySet(),
            prerequisiteIds = emptySet(),
            receiverWhyRef = "receiver-decision",
            limitationStatement = "No accepted decision exists.",
            capabilityTarget = "One supported decision.",
            atomicQuestion = "Which action is supported?",
            proofContract = ProofContract(
                method = ProofMethod.RECEIVER_VALIDATION,
                acceptanceCriteria = setOf("Evidence supports the action.")
            ),
            deliverableContract = NodeDeliverableContract(
                acceptedArtifactTypes = setOf(ArtifactType.DECISION_RECORD)
            ),
            revealPolicy = RevealPolicy("stage-0", RevealMode.INITIAL),
            replicationPolicy = ReplicationPolicy(),
            capturePolicy = CapturePolicy(minimumGrade = 0.8),
            rewardSpec = RewardSpec(RewardClass.LARGE, 3.0),
        ).withComputedPayloadHash()
        return NodeAtlas(
            atlasId = "atlas-round-snapshot",
            runId = "run-round-snapshot",
            version = 1,
            receiverContractHash = AtlasHashing.hashReceiverContract(receiver),
            rootNodeIds = setOf(nodeId),
            nodes = mapOf(nodeId to node),
            stageDefinitions = listOf(
                DisclosureStage(
                    stageId = "stage-0",
                    stageIndex = 0,
                    title = "Decision",
                    nodeIds = setOf(nodeId),
                    mandatoryNodeIds = setOf(nodeId),
                    evidenceCoverageThreshold = 1.0,
                    integrationReadinessThreshold = 1.0,
                    revealPayloadHash = AtlasHashing.hashDisclosurePayload(
                        "stage-0",
                        setOf(nodeId),
                        mapOf(nodeId to node),
                    ),
                )
            ),
        ).withComputedHash()
    }

    private fun gradeBundle(
        atlas: NodeAtlas,
        verdict: NodeGradeBundleVerdict,
        round: Int,
    ): NodeGradeBundle {
        val node = atlas.nodes.getValue(NodeId("decision-node"))
        val passing = verdict == NodeGradeBundleVerdict.VALID_PASS
        return NodeGradeBundle(
            bundleId = "bundle-${verdict.name.lowercase()}-$round",
            runId = atlas.runId,
            round = round,
            groupId = AtlasGroupId("group-a"),
            nodeId = node.id,
            atlasVersion = atlas.version,
            atlasHash = atlas.atlasHash,
            nodePayloadHash = node.payloadHash,
            evidencePacketId = "packet-$round".takeIf { passing },
            evidencePacketHash = AtlasHashing.hashText("packet-$round").takeIf { passing },
            criteriaHash = AtlasHashing.hashNodeGradingCriteria(node),
            capturePolicyHash = AtlasHashing.hashCapturePolicy(node.capturePolicy),
            scoreVector = NodeScoreVector(
                correctness = 1.0,
                evidenceQuality = 1.0,
                reproducibility = 1.0,
                robustness = 1.0,
                integrationUtility = 1.0,
                receiverRelevance = 1.0,
                novelty = 1.0,
                efficiency = 1.0,
                hardFailure = false,
                confidence = 1.0,
            ).takeIf { passing },
            normalizedScore = 1.0.takeIf { passing },
            verdict = verdict,
            requiredQuorum = 1,
            graderIds = setOf("grader-a"),
            completedGraderIds = setOf("grader-a"),
            graderResponseHashes = setOf(AtlasHashing.hashText("response-$round")),
        ).withComputedHash()
    }

    private fun acceptedCapture(
        atlas: NodeAtlas,
        bundle: NodeGradeBundle,
    ): NodeCaptureDecision {
        val node = atlas.nodes.getValue(bundle.nodeId)
        return NodeCaptureDecision(
            decisionId = "capture-${bundle.bundleId}",
            runId = atlas.runId,
            round = bundle.round,
            nodeId = bundle.nodeId,
            atlasVersion = atlas.version,
            atlasHash = atlas.atlasHash,
            nodePayloadHash = node.payloadHash,
            capturePolicyHash = bundle.capturePolicyHash,
            candidateBundleHashes = setOf(bundle.bundleHash),
            authoritativeBundleHashes = setOf(bundle.bundleHash),
            resultingOwnerGroupIds = setOf(bundle.groupId),
            resultingCaptureState = CaptureState.CAPTURED,
            decisionType = NodeCaptureDecisionType.CAPTURED,
            winningScore = bundle.normalizedScore,
            yieldEligible = true,
            reasonCode = NodeCaptureReasonCode.BEST_VALID_GRADE,
        ).withComputedHash()
    }
}
