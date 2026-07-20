@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.substrate.worker

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
import com.achulkov.evomolior.core.atlas.NodeCaptureDecision
import com.achulkov.evomolior.core.atlas.NodeCaptureDecisionType
import com.achulkov.evomolior.core.atlas.NodeCaptureReasonCode
import com.achulkov.evomolior.core.atlas.NodeDeliverableContract
import com.achulkov.evomolior.core.atlas.NodeExecutionState
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
import com.achulkov.evomolior.core.contract.ContributionAuthorityKind
import com.achulkov.evomolior.core.contract.ContributionAuthorityRef
import com.achulkov.evomolior.core.contract.FinalDeliverableReferenceRecord
import com.achulkov.evomolior.core.contract.IntegrationNodeRecord
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.atlas.AtlasProjectionSet
import com.achulkov.evomolior.substrate.atlas.AtlasTransactionRepository
import com.achulkov.evomolior.substrate.atlas.DisclosureProjection
import com.achulkov.evomolior.substrate.atlas.PreparedAtlasMigration
import com.achulkov.evomolior.substrate.atlas.ResourceProjection
import com.achulkov.evomolior.substrate.db.entity.NodeCaptureDecisionEntity
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class FinalDeliverableLineageRepositoryTest {
    private val runId = "run-final-lineage"
    private val groupId = AtlasGroupId("group-final-lineage")
    private val nodeA = NodeId("node-a")
    private val nodeB = NodeId("node-b")

    @Test
    fun `final node references require exact current capture or integration ancestry`() = runTest {
        val factory = SubstrateDatabaseFactory(
            createTempDirectory("final-deliverable-lineage-").toFile()
        )
        val atlas = initializeCapturedAtlas(factory)
        val database = factory.get(runId, groupId.value)
        val atlasDatabase = factory.getAtlas(runId)
        val repository = ContributionGraphRepository(database, atlasDatabase)
        val captureA = capture(atlas, nodeA, "capture-a", NodeCaptureDecisionType.CAPTURED)
        val captureB = capture(atlas, nodeB, "capture-b", NodeCaptureDecisionType.CAPTURED)
        insertCapture(atlasDatabase, captureA, 1_000L)
        insertCapture(atlasDatabase, captureB, 1_001L)
        val captureARef = captureA.authority()
        val integrationA = IntegrationNodeRecord(
            integrationId = "integration-a",
            runId = runId,
            groupId = groupId,
            round = 0,
            nodeId = nodeA.value,
            atlasVersion = captureA.atlasVersion,
            atlasHash = captureA.atlasHash,
            nodePayloadHash = captureA.nodePayloadHash,
            captureDecision = captureARef,
            sourceAuthorities = listOf(captureARef),
            integratedAt = Instant.parse("2026-07-19T09:00:00Z")
        ).withComputedHash()
        repository.recordIntegrationNode(integrationA)
        val mismatchedIntegration = IntegrationNodeRecord(
            integrationId = "integration-mismatched",
            runId = runId,
            groupId = groupId,
            round = 0,
            nodeId = nodeA.value,
            atlasVersion = captureB.atlasVersion,
            atlasHash = captureB.atlasHash,
            nodePayloadHash = captureB.nodePayloadHash,
            captureDecision = captureB.authority(),
            sourceAuthorities = listOf(captureB.authority()),
            integratedAt = Instant.parse("2026-07-19T09:00:01Z")
        ).withComputedHash()
        repository.recordIntegrationNode(mismatchedIntegration)
        database.stagingDao().insert(
            StagingSubmissionEntity(
                id = "final-writer-doc",
                agentId = "final-writer",
                role = "Builder",
                type = "DOC",
                content = "Final output",
                budgetSignature = "signed",
                generation = 1,
                groupId = groupId.value,
                runId = runId,
                submittedAtMs = 2_000L
            )
        )

        assertEquals(
            nodeB.value,
            repository.recordFinalDeliverableReference(
                reference(
                    id = "capture-reference",
                    nodeId = nodeB.value,
                    source = captureB.authority()
                )
            ).nodeId
        )
        assertEquals(
            nodeA.value,
            repository.recordFinalDeliverableReference(
                reference(
                    id = "integration-reference",
                    nodeId = nodeA.value,
                    source = integrationA.authority()
                )
            ).nodeId
        )
        assertFailsWith<IllegalArgumentException> {
            repository.recordFinalDeliverableReference(
                reference(
                    id = "canonical-forged-node",
                    nodeId = nodeA.value,
                    source = ContributionAuthorityRef(
                        ContributionAuthorityKind.CANONICAL_ARTIFACT,
                        "kb:forged",
                        "forged-hash"
                    )
                )
            )
        }
        assertFailsWith<IllegalArgumentException> {
            repository.recordFinalDeliverableReference(
                reference(
                    id = "integration-mismatched-chain",
                    nodeId = nodeA.value,
                    source = mismatchedIntegration.authority()
                )
            )
        }

        val sources = repository.finalDeliverableSources(runId, groupId.value)
            .associateBy { it.nodeId }
        assertEquals(integrationA.authority(), sources.getValue(nodeA.value).authority)
        assertEquals(captureB.authority(), sources.getValue(nodeB.value).authority)

        val recaptureA = capture(
            atlas,
            nodeA,
            "capture-a-current",
            NodeCaptureDecisionType.RECAPTURED,
            round = 1
        )
        insertCapture(atlasDatabase, recaptureA, 3_000L)
        val currentSources = repository.finalDeliverableSources(runId, groupId.value)
            .associateBy { it.nodeId }
        assertEquals(recaptureA.authority(), currentSources.getValue(nodeA.value).authority)
        assertEquals(captureB.authority(), currentSources.getValue(nodeB.value).authority)
        assertFalse(
            repository.isAcceptedFinalNodeSource(
                runId,
                groupId.value,
                nodeA.value,
                integrationA.authority()
            )
        )
        assertTrue(
            repository.isAcceptedFinalNodeSource(
                runId,
                groupId.value,
                nodeA.value,
                recaptureA.authority()
            )
        )
    }

    private suspend fun initializeCapturedAtlas(
        factory: SubstrateDatabaseFactory
    ): NodeAtlas {
        val receiver = ReceiverContract(
            rawObjective = "Test final component lineage.",
            receiverId = "receiver",
            receiverDescription = "Test receiver",
            situation = "Final publication",
            decisionOrUse = "Use the exact output",
            whyNeeded = "Verify causal lineage",
            successOutcomes = listOf("Every component has honest lineage"),
            unacceptableOutcomes = listOf("Forged ancestry"),
            constraints = listOf("Use accepted capture authority"),
            finalDeliverableContract = FinalOutputSpec(
                relativePath = "answer.md",
                mediaType = "text/markdown",
                requiredSections = setOf("answer", "evidence")
            ),
            evidenceProfile = EvidenceProfile(
                acceptedEvidenceKinds = setOf(EvidenceKind.PRIMARY_SOURCE)
            )
        )
        val nodes = setOf(nodeA, nodeB).associateWith { nodeId ->
            LimitationNode(
                id = nodeId,
                kind = NodeKind.EVIDENCE,
                layer = 0,
                parentIds = emptySet(),
                prerequisiteIds = emptySet(),
                receiverWhyRef = "receiver:${nodeId.value}",
                limitationStatement = "Resolve ${nodeId.value}.",
                capabilityTarget = "Verified ${nodeId.value}.",
                atomicQuestion = "Is ${nodeId.value} supported?",
                proofContract = ProofContract(
                    ProofMethod.SOURCE_CORROBORATION,
                    setOf("Provide exact evidence.")
                ),
                deliverableContract = NodeDeliverableContract(
                    acceptedArtifactTypes = setOf(ArtifactType.CLAIM_SET)
                ),
                revealPolicy = RevealPolicy("stage-0", RevealMode.INITIAL),
                replicationPolicy = ReplicationPolicy(),
                capturePolicy = CapturePolicy(minimumGrade = 0.6),
                rewardSpec = RewardSpec(RewardClass.SMALL, 1.0)
            ).withComputedPayloadHash()
        }
        val stage = DisclosureStage(
            stageId = "stage-0",
            stageIndex = 0,
            title = "Foundation",
            nodeIds = nodes.keys,
            mandatoryNodeIds = nodes.keys,
            evidenceCoverageThreshold = 1.0,
            integrationReadinessThreshold = 1.0,
            revealPayloadHash = AtlasHashing.hashDisclosurePayload(
                "stage-0",
                nodes.keys,
                nodes
            )
        )
        val atlas = NodeAtlas(
            atlasId = "worker-atlas-$runId",
            runId = runId,
            version = 1,
            receiverContractHash = AtlasHashing.hashReceiverContract(receiver),
            rootNodeIds = nodes.keys,
            nodes = nodes,
            stageDefinitions = listOf(stage)
        ).withComputedHash()
        AtlasTransactionRepository(factory).initialize(
            PreparedAtlasMigration(
                receiverContract = receiver,
                atlas = atlas,
                executionStates = nodes.keys.map { nodeId ->
                    NodeExecutionState(
                        nodeId = nodeId,
                        round = 0,
                        revealedToGroups = setOf(groupId),
                        ownerGroupId = groupId,
                        captureState = CaptureState.CAPTURED
                    )
                },
                projections = AtlasProjectionSet(
                    wip = emptyList(),
                    deliverables = emptyList(),
                    resources = ResourceProjection(runId, emptyList()),
                    disclosure = DisclosureProjection(
                        runId = runId,
                        stages = listOf(stage),
                        groupStageIndices = mapOf(groupId.value to 0)
                    ),
                    mappings = emptyList()
                )
            )
        )
        return atlas
    }

    private fun capture(
        atlas: NodeAtlas,
        nodeId: NodeId,
        decisionId: String,
        type: NodeCaptureDecisionType,
        round: Int = 0
    ) = NodeCaptureDecision(
        decisionId = decisionId,
        runId = runId,
        round = round,
        nodeId = nodeId,
        atlasVersion = atlas.version,
        atlasHash = atlas.atlasHash,
        nodePayloadHash = atlas.nodes.getValue(nodeId).payloadHash,
        capturePolicyHash = AtlasHashing.hashCapturePolicy(
            atlas.nodes.getValue(nodeId).capturePolicy
        ),
        candidateBundleHashes = emptySet(),
        resultingOwnerGroupIds = setOf(groupId),
        resultingCaptureState = CaptureState.CAPTURED,
        decisionType = type,
        winningScore = 0.9,
        yieldEligible = true,
        reasonCode = NodeCaptureReasonCode.BEST_VALID_GRADE
    ).withComputedHash()

    private suspend fun insertCapture(
        atlasDatabase: com.achulkov.evomolior.substrate.db.EvoMoliorDatabase,
        decision: NodeCaptureDecision,
        createdAtMs: Long
    ) {
        atlasDatabase.nodeCaptureDao().insertCaptureDecision(
            NodeCaptureDecisionEntity(
                decisionId = decision.decisionId,
                runId = decision.runId,
                round = decision.round,
                nodeId = decision.nodeId.value,
                atlasVersion = decision.atlasVersion,
                atlasHash = decision.atlasHash,
                nodePayloadHash = decision.nodePayloadHash,
                decisionType = decision.decisionType.name,
                resultingCaptureState = decision.resultingCaptureState.name,
                yieldEligible = decision.yieldEligible,
                decisionHash = decision.decisionHash,
                contentJson = Json.encodeToString(decision),
                createdAtMs = createdAtMs
            )
        )
    }

    private fun reference(
        id: String,
        nodeId: String,
        source: ContributionAuthorityRef
    ) = FinalDeliverableReferenceRecord(
        referenceId = id,
        runId = runId,
        groupId = groupId,
        round = 1,
        relativePath = "answer.md",
        mediaType = "text/markdown",
        contentHash = "output-hash",
        writerAgentId = "final-writer",
        writerSubmissionId = "final-writer-doc",
        nodeId = nodeId,
        sourceAuthority = source,
        referencedAt = Instant.parse("2026-07-19T09:01:00Z"),
        componentId = "answer"
    ).withComputedHash()

    private fun NodeCaptureDecision.authority() = ContributionAuthorityRef(
        ContributionAuthorityKind.NODE_CAPTURE_DECISION,
        decisionId,
        decisionHash
    )

    private fun IntegrationNodeRecord.authority() = ContributionAuthorityRef(
        ContributionAuthorityKind.INTEGRATION_NODE,
        integrationId,
        recordHash
    )
}
