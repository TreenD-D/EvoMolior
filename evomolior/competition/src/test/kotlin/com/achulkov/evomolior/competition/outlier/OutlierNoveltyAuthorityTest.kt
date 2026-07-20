package com.achulkov.evomolior.competition.outlier

import com.achulkov.evomolior.core.atlas.ArtifactType
import com.achulkov.evomolior.core.atlas.AtlasAgentId
import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.CapturePolicy
import com.achulkov.evomolior.core.atlas.DisclosureStage
import com.achulkov.evomolior.core.atlas.EvidenceKind
import com.achulkov.evomolior.core.atlas.EvidenceProfile
import com.achulkov.evomolior.core.atlas.FinalOutputSpec
import com.achulkov.evomolior.core.atlas.HiddenMapAnticipation
import com.achulkov.evomolior.core.atlas.LimitationNode
import com.achulkov.evomolior.core.atlas.NodeAtlas
import com.achulkov.evomolior.core.atlas.NodeDeliverableContract
import com.achulkov.evomolior.core.atlas.NodeExecutionState
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.atlas.NodeKind
import com.achulkov.evomolior.core.atlas.NoveltyAssessmentStatus
import com.achulkov.evomolior.core.atlas.NoveltyGraderAssessment
import com.achulkov.evomolior.core.atlas.NoveltyGraderVector
import com.achulkov.evomolior.core.atlas.NoveltyScreeningStatus
import com.achulkov.evomolior.core.atlas.NoveltyType
import com.achulkov.evomolior.core.atlas.NoveltyVectorProposal
import com.achulkov.evomolior.core.atlas.ProofContract
import com.achulkov.evomolior.core.atlas.ProofMethod
import com.achulkov.evomolior.core.atlas.ReceiverContract
import com.achulkov.evomolior.core.atlas.ReplicationPolicy
import com.achulkov.evomolior.core.atlas.RevealMode
import com.achulkov.evomolior.core.atlas.RevealPolicy
import com.achulkov.evomolior.core.atlas.RewardClass
import com.achulkov.evomolior.core.atlas.RewardSpec
import com.achulkov.evomolior.core.atlas.ShadowNodeDecision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class OutlierNoveltyAuthorityTest {
    private val groupId = AtlasGroupId("group-outlier")
    private val visibleNodeId = NodeId("visible-node")
    private val hiddenNodeId = NodeId("hidden-node")
    private val resolver = ShadowNodeGraderResolver()
    private val compiler = NoveltyAmendmentCompiler()
    private val screener = OutlierNoveltyScreener()

    @Test
    fun `screening preserves redaction and commits the comparison hash`() {
        val atlas = atlas()
        val proposal = proposal(
            missingLayerOrAssumption = "hidden receiver final integration bridge",
            proposedVector = "deliver receiver facing integration bridge",
            whyCurrentMapMayMissIt = "receiver still lacks bridge",
            falsifiablePrediction = "bridge closes hidden dependency",
            minimalTestOrArtifact = "bridge test passes",
            expectedReceiverImpact = "receiver bridge"
        )

        val screening = screener.screen(atlas, states(), proposal)

        assertEquals(NoveltyScreeningStatus.UNIQUE, screening.status)
        assertTrue(
            screening.hiddenMapAnticipation == HiddenMapAnticipation.PARTIAL ||
                screening.hiddenMapAnticipation == HiddenMapAnticipation.DIRECT
        )
        assertTrue(screening.revealedDuplicateNodeIds.isEmpty())
        assertTrue(screening.canonDuplicateArtifactIds.isEmpty())
        assertTrue(screening.sealedHiddenComparisonHash.isNotBlank())
        assertTrue(screening.hasValidHash())
        assertEquals("Connected, testable, non-duplicate proposal", screening.reason)
    }

    @Test
    fun `screening fails closed for unrevealed references verbatim leaks and unjustified cost`() {
        val atlas = atlas()
        val hiddenNode = atlas.nodes.getValue(hiddenNodeId)

        val disconnected = screener.screen(
            atlas = atlas,
            states = states(),
            proposal = proposal(
                affectedVisibleNodeIds = linkedSetOf(hiddenNodeId),
                expectedReceiverImpact = null,
                expectedCost = 1.0
            )
        )
        val verbatimLeak = screener.screen(
            atlas = atlas,
            states = states(),
            proposal = proposal(
                missingLayerOrAssumption = hiddenNode.limitationStatement,
                proposedVector = "Move the hidden dependency into the visible test surface.",
                whyCurrentMapMayMissIt = "The hidden stage has not been acknowledged.",
                falsifiablePrediction = "The hidden bridge will be named by the proposal itself.",
                minimalTestOrArtifact = "Run a redaction check.",
                expectedReceiverImpact = "Keeps hidden content out of worker view."
            )
        )
        val costly = screener.screen(
            atlas = atlas,
            states = states(),
            proposal = proposal(
                missingLayerOrAssumption = "The visible stage lacks a cheap supporting alternative.",
                proposedVector = "Add a costly unbounded simulation layer.",
                whyCurrentMapMayMissIt = "The map is too sparse.",
                falsifiablePrediction = "The proposal would need broad unsupported validation.",
                minimalTestOrArtifact = "Run the expensive simulation.",
                expectedReceiverImpact = null,
                expectedCost = 250.0
            )
        )

        assertEquals(NoveltyScreeningStatus.DISCONNECTED, disconnected.status)
        assertEquals(NoveltyScreeningStatus.HIDDEN_INFORMATION_DEPENDENCY, verbatimLeak.status)
        assertEquals(NoveltyScreeningStatus.COST_UNJUSTIFIED, costly.status)
        assertEquals(HiddenMapAnticipation.NONE, verbatimLeak.hiddenMapAnticipation)
        assertEquals(HiddenMapAnticipation.NONE, costly.hiddenMapAnticipation)
        assertTrue(verbatimLeak.revealedDuplicateNodeIds.isEmpty())
        assertTrue(disconnected.reason.contains("disconnected", ignoreCase = true))
    }

    @Test
    fun `resolver accepts only exact quorum and fails closed on missing duplicate invalid abstained and non unique screening`() {
        val atlas = atlas()
        val proposal = proposal()
        val screening = screener.screen(atlas, states(), proposal)
        val acceptedAssessments = listOf(
            assessment("grader-a", proposal, NoveltyAssessmentStatus.VALID, 0.9, 0.85, 0.88, 0.92, 0.89, 0.86),
            assessment("grader-b", proposal, NoveltyAssessmentStatus.VALID, 0.8, 0.82, 0.81, 0.84, 0.83, 0.8)
        )

        val accepted = resolver.resolve(
            proposal = proposal,
            screening = screening,
            expectedGraderGroupIds = setOf("grader-a", "grader-b"),
            assessments = acceptedAssessments
        )
        val missing = resolver.resolve(
            proposal = proposal,
            screening = screening,
            expectedGraderGroupIds = setOf("grader-a", "grader-b"),
            assessments = acceptedAssessments.dropLast(1)
        )
        val duplicate = resolver.resolve(
            proposal = proposal,
            screening = screening,
            expectedGraderGroupIds = setOf("grader-a", "grader-b"),
            assessments = acceptedAssessments + assessment(
                "grader-a",
                proposal,
                NoveltyAssessmentStatus.VALID,
                0.95,
                0.9,
                0.91,
                0.92,
                0.94,
                0.93
            )
        )
        val extra = resolver.resolve(
            proposal = proposal,
            screening = screening,
            expectedGraderGroupIds = setOf("grader-a", "grader-b"),
            assessments = acceptedAssessments + assessment(
                "grader-c",
                proposal,
                NoveltyAssessmentStatus.VALID,
                0.9,
                0.9,
                0.9,
                0.9,
                0.9,
                0.9
            )
        )
        val abstained = resolver.resolve(
            proposal = proposal,
            screening = screening,
            expectedGraderGroupIds = setOf("grader-a", "grader-b"),
            assessments = listOf(
                assessment("grader-a", proposal, NoveltyAssessmentStatus.ABSTAINED),
                assessment("grader-b", proposal, NoveltyAssessmentStatus.VALID, 0.8, 0.8, 0.8, 0.8, 0.8, 0.8)
            )
        )
        val invalid = resolver.resolve(
            proposal = proposal,
            screening = screening,
            expectedGraderGroupIds = setOf("grader-a", "grader-b"),
            assessments = listOf(
                assessment("grader-a", proposal, NoveltyAssessmentStatus.VALID, 0.9, 0.9, 0.9, 0.9, 0.9, 0.9)
                    .copy(assessmentHash = "tampered"),
                assessment("grader-b", proposal, NoveltyAssessmentStatus.VALID, 0.8, 0.8, 0.8, 0.8, 0.8, 0.8)
            )
        )
        val rejectedScreening = resolver.resolve(
            proposal = proposal,
            screening = screening.copy(
                status = NoveltyScreeningStatus.DISCONNECTED,
                reason = "Proposal is disconnected from the visible map",
                screeningHash = ""
            ).withComputedHash(),
            expectedGraderGroupIds = setOf("grader-a", "grader-b"),
            assessments = acceptedAssessments
        )

        assertEquals(ShadowNodeDecision.ACCEPT, accepted.decision)
        assertEquals(2, accepted.requiredQuorum)
        assertTrue(accepted.aggregateVector != null)
        assertEquals(ShadowNodeDecision.INVALID, missing.decision)
        assertEquals(ShadowNodeDecision.INVALID, duplicate.decision)
        assertEquals(ShadowNodeDecision.INVALID, extra.decision)
        assertEquals(ShadowNodeDecision.DEFER, abstained.decision)
        assertEquals(ShadowNodeDecision.INVALID, invalid.decision)
        assertEquals(ShadowNodeDecision.REJECT, rejectedScreening.decision)
        assertEquals(2, rejectedScreening.requiredQuorum)
    }

    @Test
    fun `amendment compiler preserves the base atlas and activates only in the next round`() {
        val atlas = atlas()
        val proposal = proposal(round = 2)
        val screening = screener.screen(atlas, states(), proposal)
        val verdict = resolver.resolve(
            proposal = proposal,
            screening = screening,
            expectedGraderGroupIds = setOf("grader-a", "grader-b"),
            assessments = listOf(
                assessment("grader-a", proposal, NoveltyAssessmentStatus.VALID, 0.9, 0.86, 0.9, 0.88, 0.91, 0.87),
                assessment("grader-b", proposal, NoveltyAssessmentStatus.VALID, 0.84, 0.8, 0.83, 0.82, 0.85, 0.8)
            )
        )
        val originalNodes = atlas.nodes
        val originalStageNodes = atlas.stageDefinitions.single { it.stageId == "stage-0" }.nodeIds

        val compiled = compiler.compile(
            atlas = atlas,
            proposal = proposal,
            screening = screening,
            verdict = verdict,
            effectiveRound = 3
        )

        assertEquals(originalNodes, atlas.nodes)
        assertEquals(originalStageNodes, atlas.stageDefinitions.single { it.stageId == "stage-0" }.nodeIds)
        assertEquals(1, atlas.version)
        assertTrue(atlas.parentAtlasHash == null)
        assertTrue(atlas.amendmentIds.isEmpty())
        assertEquals(atlas.atlasHash, compiled.amendment.parentAtlasHash)
        assertEquals(3, compiled.amendment.effectiveRound)
        assertEquals(3, compiled.addedNode.revealPolicy.minimumRound)
        assertTrue(compiled.addedNode.hasValidPayloadHash())
        assertEquals(atlas.version + 1, compiled.nextAtlas.version)
        assertEquals(atlas.atlasHash, compiled.nextAtlas.parentAtlasHash)
        assertEquals(listOf(compiled.amendment.amendmentId), compiled.nextAtlas.amendmentIds)
        assertTrue(compiled.nextAtlas.nodes.containsKey(compiled.addedNode.id))
        assertEquals(
            compiled.addedNode,
            compiled.nextAtlas.nodes.getValue(compiled.addedNode.id)
        )
        assertTrue(compiled.nextAtlas.stageDefinitions.single { it.stageId == "stage-0" }
            .nodeIds.contains(compiled.addedNode.id))
        assertTrue(compiled.nextAtlas.validate(atlas).isValid)
        assertTrue(atlas.validate().isValid)
    }

    @Test
    fun `amendment compiler rejects changes that would take effect before the next round`() {
        val atlas = atlas()
        val proposal = proposal(round = 2)
        val screening = screener.screen(atlas, states(), proposal)
        val verdict = resolver.resolve(
            proposal = proposal,
            screening = screening,
            expectedGraderGroupIds = setOf("grader-a", "grader-b"),
            assessments = listOf(
                assessment("grader-a", proposal, NoveltyAssessmentStatus.VALID, 0.9, 0.86, 0.9, 0.88, 0.91, 0.87),
                assessment("grader-b", proposal, NoveltyAssessmentStatus.VALID, 0.84, 0.8, 0.83, 0.82, 0.85, 0.8)
            )
        )

        assertFailsWith<IllegalArgumentException> {
            compiler.compile(
                atlas = atlas,
                proposal = proposal,
                screening = screening,
                verdict = verdict,
                effectiveRound = 2
            )
        }
    }

    private fun proposal(
        round: Int = 4,
        affectedVisibleNodeIds: Set<NodeId> = linkedSetOf(visibleNodeId),
        missingLayerOrAssumption: String = "The map still lacks the final bridge to the hidden dependency.",
        proposedVector: String = "Deliver the receiver-facing integration bridge as a testable layer.",
        whyCurrentMapMayMissIt: String = "The visible stage stops before that bridge.",
        falsifiablePrediction: String = "If the bridge is absent, the narrow integration test will fail.",
        minimalTestOrArtifact: String = "Run the bridge test against the visible stage.",
        expectedReceiverImpact: String? = "Reduces the final-step uncertainty.",
        expectedCost: Double = 1.0
    ): NoveltyVectorProposal = NoveltyVectorProposal(
        proposalId = "proposal-1",
        runId = "run-1",
        atlasHash = atlas().atlasHash,
        groupId = groupId,
        round = round,
        proposerAgentId = AtlasAgentId("agent-1"),
        noveltyType = NoveltyType.MISSING_INTEGRATION_DEPENDENCY,
        affectedVisibleNodeIds = affectedVisibleNodeIds,
        missingLayerOrAssumption = missingLayerOrAssumption,
        proposedVector = proposedVector,
        whyCurrentMapMayMissIt = whyCurrentMapMayMissIt,
        falsifiablePrediction = falsifiablePrediction,
        minimalTestOrArtifact = minimalTestOrArtifact,
        expectedReceiverImpact = expectedReceiverImpact,
        expectedCost = expectedCost,
        conflictWithExistingAssumptions = listOf("assumption-a", "assumption-b"),
        noveltyFingerprint = "fingerprint-1"
    ).withComputedHash()

    private fun assessment(
        graderGroupId: String,
        proposal: NoveltyVectorProposal,
        status: NoveltyAssessmentStatus,
        novelty: Double? = null,
        receiverRelevance: Double? = null,
        testability: Double? = null,
        plausibility: Double? = null,
        expectedImpact: Double? = null,
        costDiscipline: Double? = null
    ): NoveltyGraderAssessment {
        val vector = if (status == NoveltyAssessmentStatus.VALID) {
            NoveltyGraderVector(
                novelty = requireNotNull(novelty),
                receiverRelevance = requireNotNull(receiverRelevance),
                testability = requireNotNull(testability),
                plausibility = requireNotNull(plausibility),
                expectedImpact = requireNotNull(expectedImpact),
                costDiscipline = requireNotNull(costDiscipline)
            )
        } else {
            null
        }

        return NoveltyGraderAssessment(
            assessmentId = "assessment-${graderGroupId}-${status.name.lowercase()}",
            proposalHash = proposal.proposalHash,
            graderGroupId = graderGroupId,
            status = status,
            vector = vector,
            rationaleHash = "rationale-${graderGroupId}"
        ).withComputedHash()
    }

    private fun atlas(): NodeAtlas {
        val receiver = ReceiverContract(
            rawObjective = "Resolve the outlier novelty checks.",
            receiverId = "receiver-1",
            receiverDescription = "Operator",
            situation = "Decision",
            decisionOrUse = "Choose",
            whyNeeded = "Reduce uncertainty",
            successOutcomes = listOf("Verified"),
            unacceptableOutcomes = listOf("Unsupported"),
            constraints = listOf("Auditable"),
            finalDeliverableContract = FinalOutputSpec(
                relativePath = "result.json",
                mediaType = "application/json",
                requiredSections = setOf("recommendation")
            ),
            evidenceProfile = EvidenceProfile(
                acceptedEvidenceKinds = setOf(EvidenceKind.PRIMARY_SOURCE)
            )
        )
        val visible = node(
            id = visibleNodeId,
            stageId = "stage-0",
            mode = RevealMode.INITIAL,
            layer = 0,
            parentIds = emptySet(),
            prerequisiteIds = emptySet(),
            receiverWhyRef = "visible-workflow",
            limitationStatement = "The visible stage still misses one integration bridge.",
            capabilityTarget = "Provide a testable bridge to the next layer.",
            atomicQuestion = "Which bridge closes the visible gap?"
        )
        val hidden = node(
            id = hiddenNodeId,
            stageId = "stage-1",
            mode = RevealMode.STAGE_PASS,
            layer = 1,
            parentIds = setOf(visibleNodeId),
            prerequisiteIds = setOf(visibleNodeId),
            receiverWhyRef = "hidden-receiver-gap",
            limitationStatement = "The receiver still lacks the final integration bridge.",
            capabilityTarget = "Deliver the receiver-facing integration bridge.",
            atomicQuestion = "Which bridge closes the hidden dependency?"
        )
        val nodes = linkedMapOf(visible.id to visible, hidden.id to hidden)
        return NodeAtlas(
            atlasId = "atlas-outlier",
            runId = "run-1",
            version = 1,
            receiverContractHash = AtlasHashing.hashReceiverContract(receiver),
            rootNodeIds = setOf(visible.id),
            nodes = nodes,
            stageDefinitions = listOf(
                stage("stage-0", 0, setOf(visible.id), nodes),
                stage("stage-1", 1, setOf(hidden.id), nodes)
            )
        ).withComputedHash()
    }

    private fun stage(
        stageId: String,
        stageIndex: Int,
        nodeIds: Set<NodeId>,
        nodes: Map<NodeId, LimitationNode>
    ) = DisclosureStage(
        stageId = stageId,
        stageIndex = stageIndex,
        title = stageId,
        nodeIds = nodeIds,
        mandatoryNodeIds = nodeIds,
        evidenceCoverageThreshold = 1.0,
        integrationReadinessThreshold = 0.7,
        revealPayloadHash = AtlasHashing.hashDisclosurePayload(stageId, nodeIds, nodes)
    )

    private fun node(
        id: NodeId,
        stageId: String,
        mode: RevealMode,
        layer: Int,
        parentIds: Set<NodeId>,
        prerequisiteIds: Set<NodeId>,
        receiverWhyRef: String,
        limitationStatement: String,
        capabilityTarget: String,
        atomicQuestion: String
    ) = LimitationNode(
        id = id,
        kind = if (layer == 0) NodeKind.KNOWLEDGE else NodeKind.INTEGRATION,
        layer = layer,
        parentIds = parentIds,
        prerequisiteIds = prerequisiteIds,
        receiverWhyRef = receiverWhyRef,
        limitationStatement = limitationStatement,
        capabilityTarget = capabilityTarget,
        atomicQuestion = atomicQuestion,
        proofContract = ProofContract(
            method = ProofMethod.SOURCE_CORROBORATION,
            acceptanceCriteria = setOf("bridge test passes")
        ),
        deliverableContract = NodeDeliverableContract(
            acceptedArtifactTypes = setOf(ArtifactType.CLAIM_SET)
        ),
        revealPolicy = RevealPolicy(stageId = stageId, mode = mode),
        replicationPolicy = ReplicationPolicy(),
        capturePolicy = CapturePolicy(minimumGrade = 0.7),
        rewardSpec = RewardSpec(RewardClass.SMALL, credits = 1.0)
    ).withComputedPayloadHash()

    private fun states(): Map<NodeId, NodeExecutionState> = mapOf(
        visibleNodeId to NodeExecutionState(
            nodeId = visibleNodeId,
            round = 4,
            revealedToGroups = setOf(groupId)
        ),
        hiddenNodeId to NodeExecutionState(
            nodeId = hiddenNodeId,
            round = 4
        )
    )
}
