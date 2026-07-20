package com.achulkov.evomolior.competition.disclosure

import com.achulkov.evomolior.core.atlas.ArtifactType
import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.CapturePolicy
import com.achulkov.evomolior.core.atlas.DisclosureDecisionStatus
import com.achulkov.evomolior.core.atlas.DisclosureEvaluationStatus
import com.achulkov.evomolior.core.atlas.DisclosureFeedbackSplit
import com.achulkov.evomolior.core.atlas.DisclosureStage
import com.achulkov.evomolior.core.atlas.EvidenceKind
import com.achulkov.evomolior.core.atlas.EvidenceProfile
import com.achulkov.evomolior.core.atlas.FinalOutputSpec
import com.achulkov.evomolior.core.atlas.LimitationNode
import com.achulkov.evomolior.core.atlas.NodeAtlas
import com.achulkov.evomolior.core.atlas.NodeDeliverableContract
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
import com.achulkov.evomolior.core.contract.GroupFeedbackPacket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MandatoryNodeDisclosureResolverTest {
    private val resolver = MandatoryNodeDisclosureResolver()
    private val group = AtlasGroupId("group-a")

    @Test
    fun `valid mandatory coverage reveals exact next payload`() {
        val atlas = atlas()
        val decision = resolver.resolve(
            evaluation(atlas, DisclosureEvaluationStatus.VALID, listOf(bundle(atlas)))
        )

        assertEquals(DisclosureDecisionStatus.ADVANCE, decision.status)
        val (payload, authorization) = resolver.buildReveal(atlas, decision)
        assertTrue(payload.hasValidHashes(atlas))
        assertTrue(authorization.hasValidHash())
        assertEquals("stage-1", payload.stageId)
        assertEquals(setOf(NodeId("future-root")), payload.revealedNodes.map { it.nodeId }.toSet())
        assertTrue(!payload.copy(stagePayloadHash = "tampered").hasValidHashes(atlas))
        assertTrue(
            !authorization.copy(deliveredPayloadHash = "tampered").hasValidHash()
        )
    }

    @Test
    fun `invalid and abstained evaluation freeze without changing leak pressure`() =
        kotlinx.coroutines.test.runTest {
            val atlas = atlas()
            val engine = ProgressiveDisclosureEngine(
                stages = listOf(
                    DisclosureStageView(0, "Stage 0"),
                    DisclosureStageView(1, "Stage 1")
                ),
                stageAuthority = LocalProjectionDisclosureStageAuthority
            )
            engine.registerGroup(group.value)

            val invalid = engine.onRoundEvaluated(
                evaluation(atlas, DisclosureEvaluationStatus.INVALID, emptyList())
            )
            val abstained = engine.onRoundEvaluated(
                evaluation(atlas, DisclosureEvaluationStatus.ABSTAINED, emptyList())
            )

            assertEquals(DisclosureDecisionStatus.FREEZE_INVALID, invalid.status)
            assertEquals(DisclosureDecisionStatus.FREEZE_ABSTAINED, abstained.status)
            assertEquals(0, engine.currentStage(group.value).stageIndex)
            assertEquals(1.0, engine.leakMultiplierFor(group.value))
        }

    @Test
    fun `groups advance independently and no evidence cannot satisfy coverage`() =
        kotlinx.coroutines.test.runTest {
            val atlas = atlas()
            val engine = ProgressiveDisclosureEngine(
                stages = listOf(
                    DisclosureStageView(0, "Stage 0"),
                    DisclosureStageView(1, "Stage 1")
                ),
                stageAuthority = LocalProjectionDisclosureStageAuthority
            )
            engine.registerGroup("group-a")
            engine.registerGroup("group-b")

            engine.onRoundEvaluated(
                evaluation(atlas, DisclosureEvaluationStatus.VALID, listOf(bundle(atlas)))
            )
            val held = engine.onRoundEvaluated(
                evaluation(
                    atlas,
                    DisclosureEvaluationStatus.VALID,
                    listOf(bundle(atlas, AtlasGroupId("group-b"), NodeGradeBundleVerdict.NO_EVIDENCE)),
                    AtlasGroupId("group-b")
                )
            )

            assertEquals(1, engine.currentStage("group-a").stageIndex)
            assertEquals(0, engine.currentStage("group-b").stageIndex)
            assertEquals(DisclosureDecisionStatus.HOLD_VALID_FAILURE, held.status)
        }

    @Test
    fun `historical passing coverage combines with current exact bundles deterministically`() {
        val atlas = atlas(includeComparison = true)
        val historicalPass = bundle(atlas, round = 0)
        val currentNoEvidence = bundle(
            atlas,
            verdict = NodeGradeBundleVerdict.NO_EVIDENCE,
            round = 1
        )
        val currentComparisonPass = bundle(
            atlas,
            nodeId = NodeId("comparison"),
            round = 1
        )
        val bundles = listOf(currentComparisonPass, historicalPass, currentNoEvidence)

        val decision = resolver.resolve(
            evaluation(atlas, DisclosureEvaluationStatus.VALID, bundles)
        )
        val replayed = resolver.resolve(
            evaluation(atlas, DisclosureEvaluationStatus.VALID, bundles.reversed())
        )

        assertEquals(decision, replayed)
        assertEquals(DisclosureDecisionStatus.ADVANCE, decision.status)
        assertEquals(
            setOf(NodeId("foundation"), NodeId("comparison")),
            decision.coverage.passingMandatoryNodeIds
        )
        assertEquals(
            bundles.mapTo(linkedSetOf(), NodeGradeBundle::bundleHash),
            decision.authoritativeBundleHashes
        )
        assertTrue(decision.hasValidHash())
    }

    @Test
    fun `current valid failure does not erase an earlier passing grade`() {
        val atlas = atlas()
        val historicalPass = bundle(atlas, round = 0)
        val currentFailure = bundle(
            atlas,
            verdict = NodeGradeBundleVerdict.VALID_FAIL,
            round = 1
        )

        val decision = resolver.resolve(
            evaluation(
                atlas,
                DisclosureEvaluationStatus.VALID,
                listOf(currentFailure, historicalPass)
            )
        )

        assertEquals(DisclosureDecisionStatus.ADVANCE, decision.status)
        assertEquals(setOf(NodeId("foundation")), decision.coverage.passingMandatoryNodeIds)
        assertEquals(
            setOf(historicalPass.bundleHash, currentFailure.bundleHash),
            decision.authoritativeBundleHashes
        )
    }

    @Test
    fun `non-passing or foreign historical authority cannot satisfy coverage`() {
        val atlas = atlas()
        val currentNoEvidence = bundle(
            atlas,
            verdict = NodeGradeBundleVerdict.NO_EVIDENCE,
            round = 1
        )
        val priorFailure = bundle(
            atlas,
            verdict = NodeGradeBundleVerdict.VALID_FAIL,
            round = 0
        )
        val duplicatedPass = bundle(atlas, round = 0, suffix = "duplicate-pass")
        val tamperedPass = bundle(atlas, round = 0, suffix = "tampered")
            .copy(bundleHash = "tampered")
        val foreignPass = bundle(atlas, groupId = AtlasGroupId("group-b"), round = 0)

        val decision = resolver.resolve(
            evaluation(
                atlas,
                DisclosureEvaluationStatus.VALID,
                listOf(
                    priorFailure,
                    duplicatedPass,
                    tamperedPass,
                    foreignPass,
                    currentNoEvidence
                )
            )
        )

        assertEquals(DisclosureDecisionStatus.HOLD_VALID_FAILURE, decision.status)
        assertTrue(decision.coverage.passingMandatoryNodeIds.isEmpty())
        assertEquals(setOf(NodeId("foundation")), decision.coverage.noEvidenceMandatoryNodeIds)
        assertEquals(setOf(currentNoEvidence.bundleHash), decision.authoritativeBundleHashes)
    }

    @Test
    fun `current bundle cardinality and integrity fail closed despite historical pass`() {
        val atlas = atlas()
        val historicalPass = bundle(atlas, round = 0)
        val currentNoEvidence = bundle(
            atlas,
            verdict = NodeGradeBundleVerdict.NO_EVIDENCE,
            round = 1
        )
        val duplicateCurrent = bundle(
            atlas,
            verdict = NodeGradeBundleVerdict.NO_EVIDENCE,
            round = 1,
            suffix = "duplicate"
        )
        val malformedCurrent = currentNoEvidence.copy(bundleHash = "tampered")
        val currentBundleVariants = listOf(
            emptyList(),
            listOf(currentNoEvidence, duplicateCurrent),
            listOf(malformedCurrent)
        )

        currentBundleVariants.forEach { currentBundles ->
            val decision = resolver.resolve(
                evaluation(
                    atlas,
                    DisclosureEvaluationStatus.VALID,
                    listOf(historicalPass) + currentBundles
                )
            )

            assertEquals(DisclosureDecisionStatus.HOLD_VALID_FAILURE, decision.status)
            assertEquals(setOf(NodeId("foundation")), decision.coverage.missingMandatoryNodeIds)
            assertTrue(decision.coverage.passingMandatoryNodeIds.isEmpty())
            assertTrue(decision.authoritativeBundleHashes.isEmpty())
        }
    }

    @Test
    fun `missing current optional bundle still fails closed before threshold evaluation`() {
        val atlas = atlas(
            includeComparison = true,
            comparisonMandatory = false,
            initialEvidenceCoverageThreshold = 0.5
        )

        val decision = resolver.resolve(
            evaluation(
                atlas,
                DisclosureEvaluationStatus.VALID,
                listOf(bundle(atlas, nodeId = NodeId("foundation")))
            )
        )

        assertEquals(DisclosureDecisionStatus.HOLD_VALID_FAILURE, decision.status)
        assertEquals(setOf(NodeId("foundation")), decision.coverage.passingMandatoryNodeIds)
        assertEquals(0.5, decision.coverage.evidenceCoverage)
    }

    @Test
    fun `invalid and abstained current rounds freeze despite historical coverage`() {
        val atlas = atlas()
        val historicalPass = bundle(atlas, round = 0)
        val currentNoEvidence = bundle(
            atlas,
            verdict = NodeGradeBundleVerdict.NO_EVIDENCE,
            round = 1
        )

        val invalid = resolver.resolve(
            evaluation(
                atlas,
                DisclosureEvaluationStatus.INVALID,
                listOf(historicalPass, currentNoEvidence)
            )
        )
        val abstained = resolver.resolve(
            evaluation(
                atlas,
                DisclosureEvaluationStatus.ABSTAINED,
                listOf(historicalPass, currentNoEvidence)
            )
        )

        assertEquals(DisclosureDecisionStatus.FREEZE_INVALID, invalid.status)
        assertEquals(DisclosureDecisionStatus.FREEZE_ABSTAINED, abstained.status)
        assertEquals(setOf(NodeId("foundation")), invalid.coverage.passingMandatoryNodeIds)
        assertEquals(setOf(NodeId("foundation")), abstained.coverage.passingMandatoryNodeIds)
    }

    @Test
    fun `feedback linter rejects future node content`() {
        val atlas = atlas()
        val future = atlas.nodes.getValue(NodeId("future-root"))
        val feedback = GroupFeedbackPacket(
            feedbackId = "feedback",
            stageId = "stage-0",
            failedVisibleNodeIds = setOf(NodeId("foundation")),
            evidenceGaps = listOf("Do not reveal ${future.receiverWhyRef}"),
            actionableNextSteps = emptyList(),
            feedbackHash = ""
        )

        assertFailsWith<IllegalArgumentException> {
            DisclosureFeedbackLinter().validateAndSeal(
                atlas = atlas,
                groupId = group,
                currentStageIndex = 0,
                revealedNodeIds = setOf(NodeId("foundation")),
                split = DisclosureFeedbackSplit("internal", feedback, "sealed")
            )
        }
    }

    private fun evaluation(
        atlas: NodeAtlas,
        status: DisclosureEvaluationStatus,
        bundles: List<NodeGradeBundle>,
        groupId: AtlasGroupId = group
    ) = DisclosureRoundEvaluation(
        atlas = atlas,
        groupId = groupId,
        round = 1,
        currentStageIndex = 0,
        evaluationStatus = status,
        nodeGradeBundles = bundles
    )

    private fun bundle(
        atlas: NodeAtlas,
        groupId: AtlasGroupId = group,
        verdict: NodeGradeBundleVerdict = NodeGradeBundleVerdict.VALID_PASS,
        nodeId: NodeId = NodeId("foundation"),
        round: Int = 1,
        suffix: String = ""
    ): NodeGradeBundle {
        val node = atlas.nodes.getValue(nodeId)
        val identitySuffix = suffix.takeIf(String::isNotBlank)?.let { "-$it" }.orEmpty()
        return NodeGradeBundle(
            bundleId = "bundle-${groupId.value}-${node.id.value}-$round$identitySuffix",
            runId = atlas.runId,
            round = round,
            groupId = groupId,
            nodeId = node.id,
            atlasVersion = atlas.version,
            atlasHash = atlas.atlasHash,
            nodePayloadHash = node.payloadHash,
            evidencePacketId = if (verdict == NodeGradeBundleVerdict.NO_EVIDENCE) {
                null
            } else {
                "packet-${node.id.value}-$round$identitySuffix"
            },
            evidencePacketHash = if (verdict == NodeGradeBundleVerdict.NO_EVIDENCE) {
                null
            } else {
                "packet-hash-${node.id.value}-$round$identitySuffix"
            },
            criteriaHash = "criteria",
            capturePolicyHash = AtlasHashing.hashCapturePolicy(node.capturePolicy),
            scoreVector = if (verdict == NodeGradeBundleVerdict.NO_EVIDENCE) null else vector(),
            normalizedScore = if (verdict == NodeGradeBundleVerdict.NO_EVIDENCE) null else 0.9,
            verdict = verdict,
            requiredQuorum = 2,
            graderIds = setOf("grader-a", "grader-b"),
            completedGraderIds = setOf("grader-a", "grader-b"),
            graderResponseHashes = setOf("response-a", "response-b")
        ).withComputedHash()
    }

    private fun atlas(
        includeComparison: Boolean = false,
        comparisonMandatory: Boolean = true,
        initialEvidenceCoverageThreshold: Double = 1.0
    ): NodeAtlas {
        val foundation = node(NodeId("foundation"), "stage-0", RevealMode.INITIAL, 0)
        val comparison = node(NodeId("comparison"), "stage-0", RevealMode.INITIAL, 0)
        val future = node(NodeId("future-root"), "stage-1", RevealMode.STAGE_PASS, 1)
        val initialNodes = if (includeComparison) {
            listOf(foundation, comparison)
        } else {
            listOf(foundation)
        }
        val nodes = (initialNodes + future).associateBy(LimitationNode::id)
        val initialNodeIds = initialNodes.mapTo(linkedSetOf(), LimitationNode::id)
        val initialMandatoryNodeIds = if (includeComparison && !comparisonMandatory) {
            setOf(foundation.id)
        } else {
            initialNodeIds
        }
        val receiver = ReceiverContract(
            rawObjective = "Produce the receiver artifact.",
            receiverId = "receiver",
            receiverDescription = "Operator",
            situation = "Decision",
            decisionOrUse = "Act",
            whyNeeded = "Resolve uncertainty",
            successOutcomes = listOf("Verified"),
            unacceptableOutcomes = listOf("Unsupported"),
            constraints = listOf("Auditable"),
            finalDeliverableContract = FinalOutputSpec("result.json", "application/json"),
            evidenceProfile = EvidenceProfile(
                acceptedEvidenceKinds = setOf(EvidenceKind.PRIMARY_SOURCE)
            )
        )
        return NodeAtlas(
            atlasId = "atlas",
            runId = "run",
            version = 1,
            receiverContractHash = AtlasHashing.hashReceiverContract(receiver),
            rootNodeIds = initialNodeIds,
            nodes = nodes,
            stageDefinitions = listOf(
                stage(
                    "stage-0",
                    0,
                    initialNodeIds,
                    nodes,
                    mandatoryNodeIds = initialMandatoryNodeIds,
                    evidenceCoverageThreshold = initialEvidenceCoverageThreshold
                ),
                stage("stage-1", 1, setOf(future.id), nodes)
            )
        ).withComputedHash()
    }

    private fun stage(
        id: String,
        index: Int,
        nodeIds: Set<NodeId>,
        nodes: Map<NodeId, LimitationNode>,
        mandatoryNodeIds: Set<NodeId> = nodeIds,
        evidenceCoverageThreshold: Double = 1.0
    ) = DisclosureStage(
        stageId = id,
        stageIndex = index,
        title = id,
        nodeIds = nodeIds,
        mandatoryNodeIds = mandatoryNodeIds,
        evidenceCoverageThreshold = evidenceCoverageThreshold,
        integrationReadinessThreshold = 0.7,
        revealPayloadHash = AtlasHashing.hashDisclosurePayload(id, nodeIds, nodes)
    )

    private fun node(
        id: NodeId,
        stageId: String,
        mode: RevealMode,
        layer: Int
    ) = LimitationNode(
        id = id,
        kind = NodeKind.EVIDENCE,
        layer = layer,
        parentIds = if (layer == 0) emptySet() else setOf(NodeId("foundation")),
        prerequisiteIds = if (layer == 0) emptySet() else setOf(NodeId("foundation")),
        receiverWhyRef = if (layer == 0) "visible-why" else "sealed-receiver-root",
        limitationStatement = if (layer == 0) "Visible evidence is missing." else "Final receiver need.",
        capabilityTarget = if (layer == 0) "Establish evidence." else "Deliver final answer.",
        atomicQuestion = if (layer == 0) "What evidence is valid?" else "What should receiver do?",
        proofContract = ProofContract(
            ProofMethod.SOURCE_CORROBORATION,
            setOf("accepted evidence")
        ),
        deliverableContract = NodeDeliverableContract(setOf(ArtifactType.EVIDENCE_TABLE)),
        revealPolicy = RevealPolicy(stageId, mode),
        replicationPolicy = ReplicationPolicy(),
        capturePolicy = CapturePolicy(minimumGrade = 0.7),
        rewardSpec = RewardSpec(RewardClass.SMALL, 1.0)
    ).withComputedPayloadHash()

    private fun vector() = NodeScoreVector(
        correctness = 0.9,
        evidenceQuality = 0.9,
        reproducibility = 0.9,
        robustness = 0.9,
        integrationUtility = 0.9,
        receiverRelevance = 0.9,
        novelty = 0.8,
        efficiency = 0.8,
        hardFailure = false,
        confidence = 0.9
    )

    private fun DisclosureStageView(index: Int, title: String) =
        com.achulkov.evomolior.competition.disclosure.DisclosureStage(
            stageIndex = index,
            title = title,
            constraintFramedContent = title,
            goalFramedContent = "hidden"
        )
}
