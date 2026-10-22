package com.achulkov.evomolior.competition.scoring

import com.achulkov.evomolior.core.atlas.ArtifactType
import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.CaptureMode
import com.achulkov.evomolior.core.atlas.CapturePolicy
import com.achulkov.evomolior.core.atlas.CaptureState
import com.achulkov.evomolior.core.atlas.DisclosureStage
import com.achulkov.evomolior.core.atlas.EvidenceKind
import com.achulkov.evomolior.core.atlas.EvidenceProfile
import com.achulkov.evomolior.core.atlas.FinalOutputSpec
import com.achulkov.evomolior.core.atlas.GradeVerdict
import com.achulkov.evomolior.core.atlas.LimitationNode
import com.achulkov.evomolior.core.atlas.NodeAtlas
import com.achulkov.evomolior.core.atlas.NodeCaptureDecisionType
import com.achulkov.evomolior.core.atlas.NodeCaptureReasonCode
import com.achulkov.evomolior.core.atlas.NodeDeliverableContract
import com.achulkov.evomolior.core.atlas.NodeExecutionState
import com.achulkov.evomolior.core.atlas.NodeGradeBundle
import com.achulkov.evomolior.core.atlas.NodeGradeBundleVerdict
import com.achulkov.evomolior.core.atlas.NodeId
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NodeCaptureResolverTest {
    private val nodeId = NodeId("node-capture")
    private val groupA = AtlasGroupId("group-a")
    private val groupB = AtlasGroupId("group-b")
    private val resolver = NodeCaptureResolver()

    @Test
    fun `highest valid node grade wins independent of bundle order`() {
        val atlas = atlas()
        val lowerEarlierBundle = bundle(atlas, groupA, round = 1, score = 0.76)
        val higherLaterBundle = bundle(atlas, groupB, round = 1, score = 0.91)

        val result = resolver.resolve(
            request(
                atlas = atlas,
                state = state(),
                round = 1,
                bundles = listOf(lowerEarlierBundle, higherLaterBundle)
            )
        )

        assertEquals(NodeCaptureDecisionType.CAPTURED, result.decision.decisionType)
        assertEquals(setOf(groupB), result.decision.resultingOwnerGroupIds)
        assertEquals(higherLaterBundle.bundleHash, result.decision.authoritativeBundleHashes.single())
        val nextState = assertNotNull(result.nextState)
        assertEquals(groupB, nextState.ownerGroupId)
        assertEquals(
            setOf(lowerEarlierBundle.evidencePacketHash, higherLaterBundle.evidencePacketHash),
            nextState.grades.mapTo(mutableSetOf()) { it.evidencePacketHash }
        )
        assertTrue(nextState.grades.all { it.scoreVector != null && it.scoreBundleHash.isNotBlank() })
    }

    @Test
    fun `invalid abstained malformed and missing authority freeze capture`() {
        val atlas = atlas()
        val capturedState = state().copy(
            ownerGroupId = groupA,
            captureState = CaptureState.CAPTURED
        )
        val valid = bundle(atlas, groupA, round = 2, score = 0.8)
        val abstained = bundle(atlas, groupB, round = 2, score = 0.0).copy(
            evidencePacketId = null,
            evidencePacketHash = null,
            scoreVector = null,
            normalizedScore = null,
            verdict = NodeGradeBundleVerdict.ABSTAINED,
            bundleHash = ""
        ).withComputedHash()

        listOf(
            listOf(valid, abstained),
            listOf(valid),
            listOf(valid, bundle(atlas, groupB, round = 2, score = 0.9).copy(bundleHash = "bad"))
        ).forEach { bundles ->
            val result = resolver.resolve(
                request(atlas, capturedState, round = 2, bundles = bundles)
            )

            assertEquals(
                NodeCaptureDecisionType.FROZEN_INVALID_AUTHORITY,
                result.decision.decisionType
            )
            assertEquals(setOf(groupA), result.decision.resultingOwnerGroupIds)
            assertEquals(CaptureState.CAPTURED, result.decision.resultingCaptureState)
            assertNull(result.nextState)
            assertTrue(!result.decision.yieldEligible)
        }
    }

    @Test
    fun `complete valid failures append grades and hold below threshold`() {
        val atlas = atlas()
        val failedA = bundle(atlas, groupA, round = 1, score = 0.55).copy(
            verdict = NodeGradeBundleVerdict.VALID_FAIL,
            bundleHash = ""
        ).withComputedHash()
        val failedB = bundle(atlas, groupB, round = 1, score = 0.58).copy(
            verdict = NodeGradeBundleVerdict.VALID_FAIL,
            bundleHash = ""
        ).withComputedHash()

        val result = resolver.resolve(
            request(
                atlas = atlas,
                state = state(),
                round = 1,
                bundles = listOf(failedA, failedB)
            )
        )

        assertEquals(NodeCaptureDecisionType.BELOW_THRESHOLD, result.decision.decisionType)
        assertEquals(NodeCaptureReasonCode.BELOW_MINIMUM_GRADE, result.decision.reasonCode)
        assertEquals(setOf(failedA.bundleHash, failedB.bundleHash), result.decision.authoritativeBundleHashes)
        val nextState = assertNotNull(result.nextState)
        assertEquals(2, nextState.grades.size)
        assertTrue(nextState.grades.all { it.verdict == GradeVerdict.VALID_FAIL })
        assertNull(nextState.ownerGroupId)
        assertTrue(!result.decision.yieldEligible)
    }

    @Test
    fun `competitive freeze commitments authorize exact evaluated bundles`() {
        val atlas = atlas()
        val freezeHashes = mapOf(
            "grader-1" to "freeze-1",
            "grader-2" to "freeze-2"
        )
        val result = resolver.resolve(
            request(
                atlas = atlas,
                state = state(),
                round = 1,
                bundles = listOf(
                    bundle(atlas, groupA, 1, 0.82, freezeHashes),
                    bundle(atlas, groupB, 1, 0.76, freezeHashes)
                ),
                freezeHashes = freezeHashes
            )
        )

        assertEquals(NodeCaptureDecisionType.CAPTURED, result.decision.decisionType)
        assertEquals(setOf(groupA), result.decision.resultingOwnerGroupIds)
    }

    @Test
    fun `pass fail verdicts inconsistent with sealed capture policy freeze`() {
        val atlas = atlas()
        val belowThresholdPass = bundle(atlas, groupA, 1, 0.55)
        val aboveThresholdFail = bundle(atlas, groupB, 1, 0.9).copy(
            verdict = NodeGradeBundleVerdict.VALID_FAIL,
            bundleHash = ""
        ).withComputedHash()

        listOf(
            listOf(belowThresholdPass, bundle(atlas, groupB, 1, 0.8)),
            listOf(bundle(atlas, groupA, 1, 0.8), aboveThresholdFail)
        ).forEach { bundles ->
            val result = resolver.resolve(
                request(atlas, state(), round = 1, bundles = bundles)
            )

            assertEquals(
                NodeCaptureDecisionType.FROZEN_INVALID_AUTHORITY,
                result.decision.decisionType
            )
            assertEquals(NodeCaptureReasonCode.INVALID_GRADE_BUNDLE, result.decision.reasonCode)
            assertNull(result.nextState)
        }
    }

    @Test
    fun `recapture requires typed material improvement margin`() {
        val atlas = atlas(recaptureMargin = 0.05)
        val initial = resolver.resolve(
            request(
                atlas,
                state(),
                round = 1,
                bundles = listOf(
                    bundle(atlas, groupA, 1, 0.80),
                    bundle(atlas, groupB, 1, 0.70)
                )
            )
        )
        val ownedState = assertNotNull(initial.nextState)

        val insufficient = resolver.resolve(
            request(
                atlas,
                ownedState,
                round = 2,
                bundles = listOf(
                    bundle(atlas, groupA, 2, 0.75),
                    bundle(atlas, groupB, 2, 0.84)
                ),
                previous = initial.decision
            )
        )
        assertEquals(
            NodeCaptureDecisionType.INSUFFICIENT_RECAPTURE_MARGIN,
            insufficient.decision.decisionType
        )
        assertEquals(groupA, assertNotNull(insufficient.nextState).ownerGroupId)
        assertTrue(!insufficient.decision.yieldEligible)

        val sufficient = resolver.resolve(
            request(
                atlas,
                assertNotNull(insufficient.nextState),
                round = 3,
                bundles = listOf(
                    bundle(atlas, groupA, 3, 0.74),
                    bundle(atlas, groupB, 3, 0.86)
                ),
                previous = initial.decision
            )
        )
        assertEquals(NodeCaptureDecisionType.RECAPTURED, sufficient.decision.decisionType)
        assertEquals(NodeCaptureReasonCode.RECAPTURE_MARGIN_MET, sufficient.decision.reasonCode)
        assertEquals(groupB, assertNotNull(sufficient.nextState).ownerGroupId)
        assertTrue(sufficient.decision.yieldEligible)
    }

    @Test
    fun `prior owner authority ignores later audit-only frozen decisions`() {
        val atlas = atlas(recaptureMargin = 0.05)
        val initial = resolver.resolve(
            request(
                atlas,
                state(),
                round = 1,
                bundles = listOf(
                    bundle(atlas, groupA, 1, 0.80),
                    bundle(atlas, groupB, 1, 0.70),
                ),
            )
        )
        val ownedState = assertNotNull(initial.nextState)
        val frozen = resolver.resolve(
            request(
                atlas,
                ownedState,
                round = 2,
                bundles = listOf(bundle(atlas, groupA, 2, 0.80)),
            )
        )

        assertEquals(
            NodeCaptureDecisionType.FROZEN_INVALID_AUTHORITY,
            frozen.decision.decisionType,
        )
        assertEquals(
            initial.decision,
            resolver.priorOwnerAuthority(
                atlas = atlas,
                currentState = ownedState,
                decisions = listOf(initial.decision, frozen.decision),
            ),
        )
    }

    @Test
    fun `prior owner authority survives an unchanged node across Atlas versions`() {
        val firstAtlas = atlas(recaptureMargin = 0.05)
        val initial = resolver.resolve(
            request(
                firstAtlas,
                state(),
                round = 1,
                bundles = listOf(
                    bundle(firstAtlas, groupA, 1, 0.80),
                    bundle(firstAtlas, groupB, 1, 0.70),
                ),
            )
        )
        val secondAtlas = firstAtlas.copy(
            version = 2,
            parentAtlasHash = firstAtlas.atlasHash,
            amendmentIds = listOf("amendment-1"),
        ).withComputedHash()
        assertTrue(secondAtlas.validate(firstAtlas).isValid)
        val history = listOf(firstAtlas, secondAtlas)
        val prior = assertNotNull(
            resolver.priorOwnerAuthority(
                atlas = secondAtlas,
                currentState = assertNotNull(initial.nextState),
                decisions = listOf(initial.decision),
                atlasHistory = history,
            )
        )

        val recaptured = resolver.resolve(
            request(
                atlas = secondAtlas,
                state = assertNotNull(initial.nextState),
                round = 2,
                bundles = listOf(
                    bundle(secondAtlas, groupA, 2, 0.80),
                    bundle(secondAtlas, groupB, 2, 0.90),
                ),
                previous = prior,
                atlasHistory = history,
            )
        )

        assertEquals(NodeCaptureDecisionType.RECAPTURED, recaptured.decision.decisionType)
        assertEquals(groupB, assertNotNull(recaptured.nextState).ownerGroupId)
    }

    @Test
    fun `typed near tie contests without deterministic ownership`() {
        val atlas = atlas(nearTieMargin = 0.02)
        val result = resolver.resolve(
            request(
                atlas,
                state(),
                round = 1,
                bundles = listOf(
                    bundle(atlas, groupA, 1, 0.90),
                    bundle(atlas, groupB, 1, 0.89)
                )
            )
        )

        assertEquals(NodeCaptureDecisionType.CONTESTED, result.decision.decisionType)
        assertEquals(CaptureState.CONTESTED, assertNotNull(result.nextState).captureState)
        assertNull(result.nextState.ownerGroupId)
        assertTrue(!result.decision.yieldEligible)
    }

    @Test
    fun `shared owners survive a later no evidence audit`() {
        val atlas = atlas(
            captureMode = CaptureMode.SHARED,
            nearTieMargin = 0.02,
        )
        val shared = resolver.resolve(
            request(
                atlas,
                state(),
                round = 1,
                bundles = listOf(
                    bundle(atlas, groupA, 1, 0.90),
                    bundle(atlas, groupB, 1, 0.89),
                ),
            )
        )
        val sharedState = assertNotNull(shared.nextState)
        val priorAuthority = assertNotNull(
            resolver.priorOwnerAuthority(
                atlas = atlas,
                currentState = sharedState,
                decisions = listOf(shared.decision),
            )
        )
        val noEvidence = listOf(groupA, groupB).map { groupId ->
            bundle(atlas, groupId, round = 2, score = 0.0).copy(
                evidencePacketId = null,
                evidencePacketHash = null,
                scoreVector = null,
                normalizedScore = null,
                verdict = NodeGradeBundleVerdict.NO_EVIDENCE,
                bundleHash = "",
            ).withComputedHash()
        }

        val audited = resolver.resolve(
            request(
                atlas = atlas,
                state = sharedState,
                round = 2,
                bundles = noEvidence,
                previous = priorAuthority,
            )
        )

        assertEquals(NodeCaptureDecisionType.NO_EVIDENCE, audited.decision.decisionType)
        assertEquals(setOf(groupA, groupB), audited.decision.resultingOwnerGroupIds)
        assertEquals(CaptureState.SHARED, audited.decision.resultingCaptureState)
        assertNull(audited.nextState)
    }

    private fun request(
        atlas: NodeAtlas,
        state: NodeExecutionState,
        round: Int,
        bundles: List<NodeGradeBundle>,
        freezeHashes: Map<String, String> = emptyMap(),
        previous: com.achulkov.evomolior.core.atlas.NodeCaptureDecision? = null,
        atlasHistory: List<NodeAtlas> = listOf(atlas),
    ): NodeCaptureResolutionRequest = NodeCaptureResolutionRequest(
        atlas = atlas,
        currentState = state,
        round = round,
        expectedGroupIds = setOf(groupA, groupB),
        bundles = bundles,
        graderCriteriaFreezeHashes = freezeHashes,
        previousCaptureDecision = previous,
        atlasHistory = atlasHistory,
    )

    private fun bundle(
        atlas: NodeAtlas,
        groupId: AtlasGroupId,
        round: Int,
        score: Double,
        freezeHashes: Map<String, String> = emptyMap()
    ): NodeGradeBundle {
        val node = atlas.nodes.getValue(nodeId)
        return NodeGradeBundle(
            bundleId = "bundle-${groupId.value}-$round",
            runId = atlas.runId,
            round = round,
            groupId = groupId,
            nodeId = nodeId,
            atlasVersion = atlas.version,
            atlasHash = atlas.atlasHash,
            nodePayloadHash = node.payloadHash,
            evidencePacketId = "packet-${groupId.value}-$round",
            evidencePacketHash = "packet-hash-${groupId.value}-$round",
            criteriaHash = AtlasHashing.hashNodeGradingAuthority(node, freezeHashes),
            capturePolicyHash = AtlasHashing.hashCapturePolicy(node.capturePolicy),
            scoreVector = NodeScoreVector(
                correctness = score,
                evidenceQuality = score,
                reproducibility = score,
                robustness = score,
                integrationUtility = score,
                receiverRelevance = score,
                novelty = score,
                efficiency = score,
                hardFailure = false,
                confidence = score
            ),
            normalizedScore = score,
            verdict = NodeGradeBundleVerdict.VALID_PASS,
            requiredQuorum = 2,
            graderIds = setOf("grader-1", "grader-2"),
            completedGraderIds = setOf("grader-1", "grader-2"),
            graderResponseHashes = setOf(
                "response-${groupId.value}-$round-1",
                "response-${groupId.value}-$round-2"
            )
        ).withComputedHash()
    }

    private fun state(): NodeExecutionState = NodeExecutionState(
        nodeId = nodeId,
        round = 0
    )

    private fun atlas(
        recaptureMargin: Double = 0.0,
        nearTieMargin: Double? = null,
        captureMode: CaptureMode = CaptureMode.GRADE_AUTHORITY,
    ): NodeAtlas {
        val receiver = ReceiverContract(
            rawObjective = "Resolve the node.",
            receiverId = "receiver",
            receiverDescription = "Operator",
            situation = "Decision",
            decisionOrUse = "Choose",
            whyNeeded = "Reduce uncertainty",
            successOutcomes = listOf("Verified"),
            unacceptableOutcomes = listOf("Unsupported"),
            constraints = listOf("Auditable"),
            finalDeliverableContract = FinalOutputSpec(
                relativePath = "result.json",
                mediaType = "application/json"
            ),
            evidenceProfile = EvidenceProfile(
                acceptedEvidenceKinds = setOf(EvidenceKind.PRIMARY_SOURCE)
            )
        )
        val node = LimitationNode(
            id = nodeId,
            kind = NodeKind.EVIDENCE,
            layer = 0,
            parentIds = emptySet(),
            prerequisiteIds = emptySet(),
            receiverWhyRef = "success-1",
            limitationStatement = "Evidence is missing.",
            capabilityTarget = "Provide exact evidence.",
            atomicQuestion = "What evidence resolves this node?",
            proofContract = ProofContract(
                method = ProofMethod.SOURCE_CORROBORATION,
                acceptanceCriteria = setOf("Correct", "Reproducible")
            ),
            deliverableContract = NodeDeliverableContract(
                acceptedArtifactTypes = setOf(ArtifactType.EVIDENCE_TABLE)
            ),
            revealPolicy = RevealPolicy("stage-0", RevealMode.INITIAL),
            replicationPolicy = ReplicationPolicy(),
            capturePolicy = CapturePolicy(
                mode = captureMode,
                minimumGrade = 0.7,
                recaptureMargin = recaptureMargin,
                nearTieMargin = nearTieMargin
            ),
            rewardSpec = RewardSpec(RewardClass.SMALL, credits = 1.0)
        ).withComputedPayloadHash()
        val nodes = mapOf(nodeId to node)
        return NodeAtlas(
            atlasId = "atlas-capture",
            runId = "run-capture",
            version = 1,
            receiverContractHash = AtlasHashing.hashReceiverContract(receiver),
            rootNodeIds = setOf(nodeId),
            nodes = nodes,
            stageDefinitions = listOf(
                DisclosureStage(
                    stageId = "stage-0",
                    stageIndex = 0,
                    title = "Initial",
                    nodeIds = setOf(nodeId),
                    mandatoryNodeIds = setOf(nodeId),
                    evidenceCoverageThreshold = 0.8,
                    integrationReadinessThreshold = 0.7,
                    revealPayloadHash = AtlasHashing.hashDisclosurePayload(
                        "stage-0",
                        setOf(nodeId),
                        nodes
                    )
                )
            )
        ).withComputedHash()
    }
}
