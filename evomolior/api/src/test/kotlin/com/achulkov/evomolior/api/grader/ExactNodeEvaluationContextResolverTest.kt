package com.achulkov.evomolior.api.grader

import com.achulkov.evomolior.core.atlas.ArtifactType
import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.CapturePolicy
import com.achulkov.evomolior.core.atlas.DisclosureStage
import com.achulkov.evomolior.core.atlas.EvidenceKind
import com.achulkov.evomolior.core.atlas.EvidenceProfile
import com.achulkov.evomolior.core.atlas.FinalOutputSpec
import com.achulkov.evomolior.core.atlas.LimitationNode
import com.achulkov.evomolior.core.atlas.NodeAtlas
import com.achulkov.evomolior.core.atlas.NodeDeliverableContract
import com.achulkov.evomolior.core.atlas.NodeEvidenceCostSummary
import com.achulkov.evomolior.core.atlas.NodeEvidencePacket
import com.achulkov.evomolior.core.atlas.NodeExecutionState
import com.achulkov.evomolior.core.atlas.NodeGradingAssignment
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.atlas.NodeKind
import com.achulkov.evomolior.core.atlas.ProofContract
import com.achulkov.evomolior.core.atlas.ProofMethod
import com.achulkov.evomolior.core.atlas.ReceiverContract
import com.achulkov.evomolior.core.atlas.ReplicationPolicy
import com.achulkov.evomolior.core.atlas.RevealMode
import com.achulkov.evomolior.core.atlas.RevealPolicy
import com.achulkov.evomolior.core.atlas.RewardClass
import com.achulkov.evomolior.core.atlas.RewardSpec
import com.achulkov.evomolior.core.contract.DeliverableSkeletonNode
import com.achulkov.evomolior.core.contract.DeliverableSkeletonTree
import com.achulkov.evomolior.core.grader.DisclosureSensitivity
import com.achulkov.evomolior.core.grader.FailureLevel
import com.achulkov.evomolior.core.grader.GraderAtlas
import com.achulkov.evomolior.core.grader.GraderAtlasNode
import com.achulkov.evomolior.core.grader.GraderCriteriaFreeze
import com.achulkov.evomolior.core.grader.GraderNodeId
import com.achulkov.evomolior.core.grader.GraderNodeKind
import com.achulkov.evomolior.core.grader.GradingRequirement
import com.achulkov.evomolior.core.grader.GradingRequirementId
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.atlas.AtlasProjectionSet
import com.achulkov.evomolior.substrate.atlas.AtlasTransactionRepository
import com.achulkov.evomolior.substrate.atlas.DisclosureProjection
import com.achulkov.evomolior.substrate.atlas.LegacyNodeMapping
import com.achulkov.evomolior.substrate.atlas.LegacyNodeSource
import com.achulkov.evomolior.substrate.atlas.PreparedAtlasMigration
import com.achulkov.evomolior.substrate.atlas.ResourceProjection
import com.achulkov.evomolior.substrate.atlas.ResourceProjectionNode
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import com.achulkov.evomolior.substrate.grader.GraderAuthorityRepository
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExactNodeEvaluationContextResolverTest {
    @Test
    fun `resolver exposes complete exact promoted content and node-local frozen criteria`() =
        runTest {
            val fixture = fixture("exact-context")

            val contexts = ExactNodeEvaluationContextResolver(fixture.factory).resolve(
                runId = fixture.runId,
                roundIndex = fixture.round,
                graderGroupId = fixture.graderGroupId,
                assignments = listOf(fixture.assignment)
            )

            val context = contexts.single()
            assertTrue(context.hasValidHash())
            assertEquals(fixture.node, context.nodeContract)
            assertEquals(listOf(fixture.requirement), context.mappedRequirements)
            assertEquals(fixture.content, context.evidence.single().content)
            assertEquals(fixture.submissionId, context.evidence.single().referenceId)
        }

    @Test
    fun `resolver fails closed when exact promoted content is tampered`() = runTest {
        val fixture = fixture("tampered-context")
        val database = fixture.factory.get(fixture.runId, fixture.workerGroupId)
        val original = requireNotNull(database.stagingDao().getById(fixture.submissionId))
        database.stagingDao().update(
            original.copy(content = "Tampered after promotion.")
        )

        assertFailsWith<IllegalArgumentException> {
            ExactNodeEvaluationContextResolver(fixture.factory).resolve(
                fixture.runId,
                fixture.round,
                fixture.graderGroupId,
                listOf(fixture.assignment)
            )
        }
    }

    @Test
    fun `resolver never silently drops an unavailable packet reference`() = runTest {
        val fixture = fixture("missing-context")
        val missingPacket = fixture.assignment.packet.copy(
            acceptedArtifactIds = listOf(fixture.submissionId, "missing-reference"),
            packetHash = ""
        ).withComputedHash()
        val missingAssignment = fixture.assignment.copy(
            packet = missingPacket,
            assignmentHash = ""
        ).withComputedHash()

        assertFailsWith<IllegalArgumentException> {
            ExactNodeEvaluationContextResolver(fixture.factory).resolve(
                fixture.runId,
                fixture.round,
                fixture.graderGroupId,
                listOf(missingAssignment)
            )
        }
    }

    @Test
    fun `resolver rejects oversized evidence instead of truncating it`() = runTest {
        val fixture = fixture("oversized-context")
        val database = fixture.factory.get(fixture.runId, fixture.workerGroupId)
        val original = requireNotNull(database.stagingDao().getById(fixture.submissionId))
        val oversized = "x".repeat(MAX_EXACT_NODE_EVIDENCE_ITEM_BYTES + 1)
        database.stagingDao().update(
            original.copy(
                content = oversized,
                promotedArtifactHash = AtlasHashing.hashText(oversized)
            )
        )

        assertFailsWith<IllegalArgumentException> {
            ExactNodeEvaluationContextResolver(fixture.factory).resolve(
                fixture.runId,
                fixture.round,
                fixture.graderGroupId,
                listOf(fixture.assignment)
            )
        }
    }

    private suspend fun fixture(suffix: String): Fixture {
        val runId = "run-$suffix"
        val workerGroupId = "worker-a"
        val graderGroupId = "grader-a"
        val round = 1
        val nodeId = NodeId("node-evidence")
        val receiver = ReceiverContract(
            rawObjective = "Evaluate one exact promoted artifact.",
            receiverId = "receiver",
            receiverDescription = "Operator",
            situation = "Evidence review",
            decisionOrUse = "Accept or reject the local claim",
            whyNeeded = "Prevent unsupported capture",
            successOutcomes = listOf("Exact evidence is graded"),
            unacceptableOutcomes = listOf("Unrelated evidence is substituted"),
            constraints = listOf("Use complete promoted content"),
            finalDeliverableContract = FinalOutputSpec("result.json", "application/json"),
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
            receiverWhyRef = "exact-evidence",
            limitationStatement = "The local claim lacks exact evidence.",
            capabilityTarget = "One inspectable source-bound claim.",
            atomicQuestion = "Does the exact promoted artifact support the claim?",
            proofContract = ProofContract(
                method = ProofMethod.SOURCE_CORROBORATION,
                acceptanceCriteria = setOf("The source directly supports the claim.")
            ),
            deliverableContract = NodeDeliverableContract(
                acceptedArtifactTypes = setOf(ArtifactType.CLAIM_SET),
                requiredFields = setOf("claim", "source")
            ),
            revealPolicy = RevealPolicy("stage-0", RevealMode.INITIAL),
            replicationPolicy = ReplicationPolicy(),
            capturePolicy = CapturePolicy(minimumGrade = 0.65),
            rewardSpec = RewardSpec(RewardClass.SMALL, 1.0)
        ).withComputedPayloadHash()
        val nodes = mapOf(nodeId to node)
        val stage = DisclosureStage(
            stageId = "stage-0",
            stageIndex = 0,
            title = "Foundation",
            nodeIds = setOf(nodeId),
            mandatoryNodeIds = setOf(nodeId),
            evidenceCoverageThreshold = 1.0,
            integrationReadinessThreshold = 1.0,
            revealPayloadHash = AtlasHashing.hashDisclosurePayload(
                "stage-0",
                setOf(nodeId),
                nodes
            )
        )
        val atlas = NodeAtlas(
            atlasId = "atlas-$suffix",
            runId = runId,
            version = 1,
            receiverContractHash = AtlasHashing.hashReceiverContract(receiver),
            rootNodeIds = setOf(nodeId),
            nodes = nodes,
            stageDefinitions = listOf(stage)
        ).withComputedHash()
        val factory = SubstrateDatabaseFactory(
            createTempDirectory("exact-node-context-$suffix-").toFile()
        )
        AtlasTransactionRepository(factory).initialize(
            PreparedAtlasMigration(
                receiverContract = receiver,
                atlas = atlas,
                executionStates = listOf(
                    NodeExecutionState(
                        nodeId = nodeId,
                        round = 0,
                        revealedToGroups = setOf(AtlasGroupId(workerGroupId))
                    )
                ),
                projections = AtlasProjectionSet(
                    wip = emptyList(),
                    deliverables = listOf(
                        DeliverableSkeletonTree(
                            runId = runId,
                            groupId = workerGroupId,
                            rootNodeId = nodeId.value,
                            nodes = listOf(
                                DeliverableSkeletonNode(
                                    nodeId = nodeId.value,
                                    title = "Exact evidence"
                                )
                            ),
                            createdAtMs = 1,
                            updatedAtMs = 1
                        )
                    ),
                    resources = ResourceProjection(
                        runId,
                        listOf(
                            ResourceProjectionNode(
                                resourceNodeId = "resource-1",
                                atlasNodeId = nodeId,
                                name = "Evidence",
                                resourceType = "WEB_CACHE",
                                yieldMultiplier = 1.0
                            )
                        )
                    ),
                    disclosure = DisclosureProjection(
                        runId,
                        listOf(stage),
                        mapOf(workerGroupId to 0)
                    ),
                    mappings = listOf(
                        LegacyNodeMapping(
                            LegacyNodeSource.DELIVERABLE,
                            nodeId.value,
                            workerGroupId,
                            nodeId
                        ),
                        LegacyNodeMapping(
                            LegacyNodeSource.RESOURCE,
                            "resource-1",
                            runId,
                            nodeId
                        )
                    )
                )
            )
        )

        val graderNodeId = GraderNodeId("grader-node")
        val graderAtlas = GraderAtlas(
            graderAtlasId = "grader-atlas-$suffix",
            runId = runId,
            version = 1,
            workerAtlasHash = atlas.atlasHash,
            rootNodeIds = setOf(graderNodeId),
            nodes = mapOf(
                graderNodeId to GraderAtlasNode(
                    nodeId = graderNodeId,
                    kind = GraderNodeKind.EVIDENCE_AUTHENTICITY,
                    title = "Evidence authenticity",
                    evaluationQuestion = "Does exact content support this node?",
                    receiverObligationRefs = setOf("exact-evidence"),
                    mappedWorkerNodeIds = setOf(nodeId)
                ).withComputedHash()
            )
        ).withComputedHash()
        val graderRepository = GraderAuthorityRepository(factory)
        graderRepository.appendAtlas(graderAtlas)
        val requirement = GradingRequirement(
            requirementId = GradingRequirementId("requirement-1"),
            runId = runId,
            graderAtlasHash = graderAtlas.atlasHash,
            graderAtlasNodeId = graderNodeId,
            graderGroupId = graderGroupId,
            round = round,
            mappedWorkerNodeIds = setOf(nodeId),
            mappedReceiverObligationRefs = setOf("exact-evidence"),
            criterion = "The complete promoted artifact directly supports the local claim.",
            observableEvidence = "Exact hash-checked packet content.",
            passThreshold = "The source and claim are directly linked.",
            failureLevels = listOf(
                FailureLevel(1, "The source does not support the claim.", 0.4, critical = true)
            ),
            adversarialCases = emptyList(),
            executableTestIds = emptyList(),
            disclosureSensitivity = DisclosureSensitivity.GRADER_INTERNAL,
            rationale = "Prevents unrelated package-level evidence from governing this node.",
            noveltyFingerprint = "exact-local-evidence-v1",
            maximumEvaluationCost = 1.0
        ).withComputedHash()
        graderRepository.appendCriteriaBatch(runId, listOf(requirement), emptyList())
        val targetSnapshotHashes = mapOf(
            AtlasGroupId(workerGroupId) to "target-snapshot-hash"
        )
        val freeze = GraderCriteriaFreeze(
            freezeId = "freeze-$suffix",
            runId = runId,
            round = round,
            graderGroupId = graderGroupId,
            graderAtlasHash = graderAtlas.atlasHash,
            requirementHashes = setOf(requirement.requirementHash),
            hiddenTestHashes = emptySet(),
            targetSnapshotHashes = targetSnapshotHashes,
            sealedAtEpochMillis = 10,
            workerRevealEpochMillis = 10
        ).withComputedHash()
        graderRepository.appendCriteriaFreeze(freeze, targetSnapshotHashes)

        val submissionId = "artifact-1"
        val content = "Complete exact accepted artifact evidence."
        factory.get(runId, workerGroupId).stagingDao().insert(
            StagingSubmissionEntity(
                id = submissionId,
                agentId = "worker-agent",
                role = "Researcher",
                type = "DOC",
                content = content,
                wipTaskRef = nodeId.value,
                budgetSignature = "signed",
                status = "promoted",
                criticAgentId = "critic",
                initialTrustScore = 0.9,
                generation = round,
                groupId = workerGroupId,
                runId = runId,
                submittedAtMs = 1,
                reviewedAtMs = 2,
                promotedArtifactRef = "kb:$submissionId",
                promotedArtifactHash = AtlasHashing.hashText(content)
            )
        )
        val packet = NodeEvidencePacket(
            packetId = "packet-1",
            runId = runId,
            round = round,
            groupId = AtlasGroupId(workerGroupId),
            nodeId = nodeId,
            atlasVersion = atlas.version,
            atlasHash = atlas.atlasHash,
            nodePayloadHash = node.payloadHash,
            acceptedArtifactIds = listOf(submissionId),
            provenanceRootHash = "provenance-hash",
            cost = NodeEvidenceCostSummary(1.0),
            sealedAtEpochMillis = 3
        ).withComputedHash()
        val freezeHashes = mapOf(graderGroupId to freeze.freezeHash)
        val assignment = NodeGradingAssignment(
            assignmentId = "assignment-1",
            packet = packet,
            criteriaHash = AtlasHashing.hashNodeGradingAuthority(node, freezeHashes),
            graderCriteriaFreezeHashes = freezeHashes,
            capturePolicy = node.capturePolicy,
            capturePolicyHash = AtlasHashing.hashCapturePolicy(node.capturePolicy),
            requiredQuorum = 1
        ).withComputedHash()
        return Fixture(
            factory = factory,
            runId = runId,
            workerGroupId = workerGroupId,
            graderGroupId = graderGroupId,
            round = round,
            node = node,
            requirement = requirement,
            submissionId = submissionId,
            content = content,
            assignment = assignment
        )
    }

    private data class Fixture(
        val factory: SubstrateDatabaseFactory,
        val runId: String,
        val workerGroupId: String,
        val graderGroupId: String,
        val round: Int,
        val node: LimitationNode,
        val requirement: GradingRequirement,
        val submissionId: String,
        val content: String,
        val assignment: NodeGradingAssignment
    )
}
