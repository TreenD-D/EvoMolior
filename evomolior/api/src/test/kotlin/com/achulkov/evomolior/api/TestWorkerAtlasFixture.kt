package com.achulkov.evomolior.api

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
import com.achulkov.evomolior.core.contract.DeliverableSkeletonNode
import com.achulkov.evomolior.core.contract.DeliverableSkeletonTree
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.atlas.AtlasProjectionSet
import com.achulkov.evomolior.substrate.atlas.AtlasTransactionRepository
import com.achulkov.evomolior.substrate.atlas.DisclosureProjection
import com.achulkov.evomolior.substrate.atlas.LegacyNodeMapping
import com.achulkov.evomolior.substrate.atlas.LegacyNodeSource
import com.achulkov.evomolior.substrate.atlas.PreparedAtlasMigration
import com.achulkov.evomolior.substrate.atlas.ResourceProjection
import com.achulkov.evomolior.substrate.atlas.ResourceProjectionNode

internal suspend fun initializeTestWorkerAtlas(
    factory: SubstrateDatabaseFactory,
    runId: String,
    nodeIds: Set<NodeId>,
    groupId: String = "worker-a",
    requiredSections: Set<String> = emptySet(),
    capturedNodeIds: Set<NodeId> = emptySet()
): NodeAtlas {
    require(nodeIds.isNotEmpty())
    require(capturedNodeIds.all(nodeIds::contains))
    val receiver = ReceiverContract(
        rawObjective = "Test exact worker Atlas authority.",
        receiverId = "receiver-$runId",
        receiverDescription = "Test receiver",
        situation = "Authority validation",
        decisionOrUse = "Evaluate exact local evidence",
        whyNeeded = "Keep grader tests production-shaped",
        successOutcomes = listOf("Exact authority is available"),
        unacceptableOutcomes = listOf("Synthetic worker authority"),
        constraints = listOf("Use sealed contracts"),
        finalDeliverableContract = FinalOutputSpec(
            relativePath = "result.json",
            mediaType = "application/json",
            requiredSections = requiredSections
        ),
        evidenceProfile = EvidenceProfile(
            acceptedEvidenceKinds = setOf(EvidenceKind.PRIMARY_SOURCE)
        )
    )
    val nodes = nodeIds.associateWith { nodeId ->
        LimitationNode(
            id = nodeId,
            kind = NodeKind.EVIDENCE,
            layer = 0,
            parentIds = emptySet(),
            prerequisiteIds = emptySet(),
            receiverWhyRef = "receiver:evidence",
            limitationStatement = "The exact local claim is unresolved.",
            capabilityTarget = "One inspectable local evidence claim.",
            atomicQuestion = "Does exact evidence establish this local claim?",
            proofContract = ProofContract(
                ProofMethod.SOURCE_CORROBORATION,
                setOf("Provide one source-bound claim.")
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
        nodeIds = nodeIds,
        mandatoryNodeIds = nodeIds,
        evidenceCoverageThreshold = 1.0,
        integrationReadinessThreshold = 1.0,
        revealPayloadHash = AtlasHashing.hashDisclosurePayload("stage-0", nodeIds, nodes)
    )
    val atlas = NodeAtlas(
        atlasId = "worker-atlas-$runId",
        runId = runId,
        version = 1,
        receiverContractHash = AtlasHashing.hashReceiverContract(receiver),
        rootNodeIds = nodeIds,
        nodes = nodes,
        stageDefinitions = listOf(stage)
    ).withComputedHash()
    val rootNodeId = nodeIds.sortedBy(NodeId::value).first()
    AtlasTransactionRepository(factory).initialize(
        PreparedAtlasMigration(
            receiverContract = receiver,
            atlas = atlas,
            executionStates = nodeIds.map { nodeId ->
                NodeExecutionState(
                    nodeId = nodeId,
                    round = 0,
                    revealedToGroups = setOf(AtlasGroupId(groupId)),
                    ownerGroupId = AtlasGroupId(groupId).takeIf {
                        nodeId in capturedNodeIds
                    },
                    captureState = if (nodeId in capturedNodeIds) {
                        CaptureState.CAPTURED
                    } else {
                        CaptureState.UNOWNED
                    }
                )
            },
            projections = AtlasProjectionSet(
                wip = emptyList(),
                deliverables = listOf(
                    DeliverableSkeletonTree(
                        runId = runId,
                        groupId = groupId,
                        rootNodeId = rootNodeId.value,
                        nodes = nodeIds.sortedBy(NodeId::value).map { nodeId ->
                            DeliverableSkeletonNode(
                                nodeId = nodeId.value,
                                parentId = if (nodeId == rootNodeId) null else rootNodeId.value,
                                title = "Evidence ${nodeId.value}"
                            )
                        },
                        createdAtMs = 1,
                        updatedAtMs = 1
                    )
                ),
                resources = ResourceProjection(
                    runId,
                    nodeIds.sortedBy(NodeId::value).mapIndexed { index, nodeId ->
                        ResourceProjectionNode(
                            resourceNodeId = "resource-$index",
                            atlasNodeId = nodeId,
                            name = "Evidence $index",
                            resourceType = "WEB_CACHE",
                            yieldMultiplier = 1.0,
                            claimedByGroupId = groupId.takeIf {
                                nodeId in capturedNodeIds
                            }
                        )
                    }
                ),
                disclosure = DisclosureProjection(
                    runId,
                    listOf(stage),
                    mapOf(groupId to 0)
                ),
                mappings = nodeIds.sortedBy(NodeId::value).flatMapIndexed { index, nodeId ->
                    listOf(
                        LegacyNodeMapping(
                            LegacyNodeSource.DELIVERABLE,
                            nodeId.value,
                            groupId,
                            nodeId
                        ),
                        LegacyNodeMapping(
                            LegacyNodeSource.RESOURCE,
                            "resource-$index",
                            runId,
                            nodeId
                        )
                    )
                }
            )
        )
    )
    return atlas
}
