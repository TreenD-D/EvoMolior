package com.achulkov.evomolior.agents.factory

import com.achulkov.evomolior.core.atlas.ArtifactType
import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.CapturePolicy
import com.achulkov.evomolior.core.atlas.ExactNodeEvaluationContext
import com.achulkov.evomolior.core.atlas.ExactNodeEvidenceContent
import com.achulkov.evomolior.core.atlas.ExactNodeEvidenceKind
import com.achulkov.evomolior.core.atlas.LimitationNode
import com.achulkov.evomolior.core.atlas.NodeDeliverableContract
import com.achulkov.evomolior.core.atlas.NodeEvidenceCostSummary
import com.achulkov.evomolior.core.atlas.NodeEvidencePacket
import com.achulkov.evomolior.core.atlas.NodeGradingAssignment
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.atlas.NodeKind
import com.achulkov.evomolior.core.atlas.ProofContract
import com.achulkov.evomolior.core.atlas.ProofMethod
import com.achulkov.evomolior.core.atlas.ReplicationPolicy
import com.achulkov.evomolior.core.atlas.RevealMode
import com.achulkov.evomolior.core.atlas.RevealPolicy
import com.achulkov.evomolior.core.atlas.RewardClass
import com.achulkov.evomolior.core.atlas.RewardSpec
import com.achulkov.evomolior.core.grader.DisclosureSensitivity
import com.achulkov.evomolior.core.grader.FailureLevel
import com.achulkov.evomolior.core.grader.GraderCriteriaFreeze
import com.achulkov.evomolior.core.grader.GraderNodeId
import com.achulkov.evomolior.core.grader.GradingRequirement
import com.achulkov.evomolior.core.grader.GradingRequirementId
import kotlin.test.Test
import kotlin.test.assertContains

class ExactNodeEvaluationPromptTest {
    @Test
    fun `interactive round judge receives complete exact packet content and mapped criterion`() {
        val context = context()

        val prompt = RolePromptBuilder.buildRoundJudge(
            runObjective = "Evaluate exact node evidence.",
            dimensionDescriptions = "  • [accuracy] Accuracy (weight=1.00): Broad accuracy.",
            criteriaContext = "Broad dimension criteria.",
            allGroupSnapshots = mapOf("worker-a" to "Generic capped snapshot."),
            nodeEvaluationContexts = listOf(context),
            roundIndex = 1
        )

        assertContains(prompt, "Complete exact promoted artifact content.")
        assertContains(prompt, "Does this exact artifact support the local claim?")
        assertContains(prompt, "The exact local claim has direct source support.")
        assertContains(prompt, context.assignment.assignmentId)
        assertContains(
            prompt,
            "passing only assignmentId, verdict, scoreVector, and rationale"
        )
        assertContains(prompt, "binds all packet, node, criteria, capture-policy, and hash commitments server-side")
        assertContains(prompt, "Do not copy or submit assignmentHash")
        assertContains(prompt, "Generic snapshots, broad criteria, dimensions, and weighted totals")
        assertContains(prompt, "is still evaluable evidence of that limitation")
        assertContains(prompt, "do not copy the artifact's own abstention")
    }

    @Test
    fun `structured reconsideration grades unavailable-output evidence instead of mirroring abstention`() {
        val negativeEvidence =
            "The requested report and receiver-validation result were unavailable."
        val context = context(negativeEvidence)
        val interactivePrompt = RolePromptBuilder.buildRoundJudge(
            runObjective = "Evaluate exact node evidence.",
            dimensionDescriptions = "  • [accuracy] Accuracy (weight=1.00): Broad accuracy.",
            criteriaContext = "Broad dimension criteria.",
            allGroupSnapshots = mapOf("worker-a" to "Generic capped snapshot."),
            nodeEvaluationContexts = listOf(context),
            roundIndex = 1
        )
        val structuredPrompt = buildStructuredNodeGradeSystemPrompt(
            StructuredNodeGradeRequest(
                graderGroupId = "grader-a",
                runObjective = "Evaluate exact node evidence.",
                evaluationContext = context,
                priorDecision = StructuredNodeGradePriorDecision(
                    verdict = StructuredNodeGradeVerdict.ABSTAINED,
                    rationale = "The final report and validation result are unavailable."
                )
            )
        )

        assertContains(interactivePrompt, negativeEvidence)
        assertContains(interactivePrompt, "is still evaluable evidence of that limitation")
        assertContains(interactivePrompt, "Use ABSTAINED only when the exact frozen authority")
        assertContains(structuredPrompt, negativeEvidence)
        assertContains(structuredPrompt, "complete, non-empty packet-bound evidence")
        assertContains(structuredPrompt, "is therefore still evaluable evidence of that limitation")
        assertContains(structuredPrompt, "requires a low VALID grade, not a repeated abstention")
        assertContains(
            structuredPrompt,
            "ABSTAINED again only if no criterion-grounded score can be authored"
        )
        assertContains(structuredPrompt, "fail-closed status will then be preserved")
    }

    private fun context(
        content: String = "Complete exact promoted artifact content."
    ): ExactNodeEvaluationContext {
        val nodeId = NodeId("node-1")
        val node = LimitationNode(
            id = nodeId,
            kind = NodeKind.EVIDENCE,
            layer = 0,
            parentIds = emptySet(),
            prerequisiteIds = emptySet(),
            receiverWhyRef = "receiver:evidence",
            limitationStatement = "The local claim lacks support.",
            capabilityTarget = "One source-supported local claim.",
            atomicQuestion = "Does this exact artifact support the local claim?",
            proofContract = ProofContract(
                ProofMethod.SOURCE_CORROBORATION,
                setOf("The source directly supports the claim.")
            ),
            deliverableContract = NodeDeliverableContract(
                acceptedArtifactTypes = setOf(ArtifactType.CLAIM_SET)
            ),
            revealPolicy = RevealPolicy("stage-0", RevealMode.INITIAL),
            replicationPolicy = ReplicationPolicy(),
            capturePolicy = CapturePolicy(minimumGrade = 0.6),
            rewardSpec = RewardSpec(RewardClass.SMALL, 1.0)
        ).withComputedPayloadHash()
        val requirement = GradingRequirement(
            requirementId = GradingRequirementId("requirement-1"),
            runId = "run-1",
            graderAtlasHash = "grader-atlas-hash",
            graderAtlasNodeId = GraderNodeId("grader-node-1"),
            graderGroupId = "grader-a",
            round = 1,
            mappedWorkerNodeIds = setOf(nodeId),
            mappedReceiverObligationRefs = setOf("receiver:evidence"),
            criterion = "The exact local claim has direct source support.",
            observableEvidence = "Complete promoted artifact content.",
            passThreshold = "The source directly supports the claim.",
            failureLevels = listOf(FailureLevel(1, "The source is unrelated.", 0.4)),
            adversarialCases = emptyList(),
            executableTestIds = emptyList(),
            disclosureSensitivity = DisclosureSensitivity.GRADER_INTERNAL,
            rationale = "Prevents package-level substitution.",
            noveltyFingerprint = "local-source-support-v1",
            maximumEvaluationCost = 1.0
        ).withComputedHash()
        val freeze = GraderCriteriaFreeze(
            freezeId = "freeze-1",
            runId = "run-1",
            round = 1,
            graderGroupId = "grader-a",
            graderAtlasHash = "grader-atlas-hash",
            requirementHashes = setOf(requirement.requirementHash),
            hiddenTestHashes = emptySet(),
            targetSnapshotHashes = mapOf(AtlasGroupId("worker-a") to "snapshot-hash"),
            sealedAtEpochMillis = 10,
            workerRevealEpochMillis = 10
        ).withComputedHash()
        val packet = NodeEvidencePacket(
            packetId = "packet-1",
            runId = "run-1",
            round = 1,
            groupId = AtlasGroupId("worker-a"),
            nodeId = nodeId,
            atlasVersion = 1,
            atlasHash = "worker-atlas-hash",
            nodePayloadHash = node.payloadHash,
            acceptedArtifactIds = listOf("artifact-1"),
            provenanceRootHash = "provenance-hash",
            cost = NodeEvidenceCostSummary(1.0),
            sealedAtEpochMillis = 11
        ).withComputedHash()
        val freezeHashes = mapOf("grader-a" to freeze.freezeHash)
        val assignment = NodeGradingAssignment(
            assignmentId = "assignment-1",
            packet = packet,
            criteriaHash = AtlasHashing.hashNodeGradingAuthority(node, freezeHashes),
            graderCriteriaFreezeHashes = freezeHashes,
            capturePolicy = node.capturePolicy,
            capturePolicyHash = AtlasHashing.hashCapturePolicy(node.capturePolicy),
            requiredQuorum = 1
        ).withComputedHash()
        return ExactNodeEvaluationContext(
            graderGroupId = "grader-a",
            assignment = assignment,
            nodeContract = node,
            criteriaFreeze = freeze,
            mappedRequirements = listOf(requirement),
            evidence = listOf(
                ExactNodeEvidenceContent(
                    referenceId = "artifact-1",
                    kind = ExactNodeEvidenceKind.ARTIFACT,
                    submissionType = "DOC",
                    content = content,
                    promotedArtifactRef = "kb:artifact-1",
                    promotedArtifactHash = AtlasHashing.hashText(content)
                )
            )
        ).withComputedHash()
    }
}
