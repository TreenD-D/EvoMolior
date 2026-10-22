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
import com.achulkov.evomolior.core.atlas.GradeVerdict
import com.achulkov.evomolior.core.atlas.LimitationNode
import com.achulkov.evomolior.core.atlas.NodeAtlas
import com.achulkov.evomolior.core.atlas.NodeDeliverableContract
import com.achulkov.evomolior.core.atlas.NodeExecutionState
import com.achulkov.evomolior.core.atlas.NodeGrade
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
import com.achulkov.evomolior.core.contract.DeliverableNodeGradeHistoryEntry
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
    capturedNodeIds: Set<NodeId> = emptySet(),
    revealedNodeIds: Set<NodeId> = nodeIds,
    passedNodeIds: Set<NodeId> = emptySet()
): NodeAtlas {
    require(nodeIds.isNotEmpty())
    require(capturedNodeIds.all(nodeIds::contains))
    require(revealedNodeIds.all(nodeIds::contains))
    require(passedNodeIds.all(revealedNodeIds::contains))
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
            revealPolicy = if (nodeId in revealedNodeIds) {
                RevealPolicy("stage-0", RevealMode.INITIAL)
            } else {
                RevealPolicy("stage-1", RevealMode.STAGE_PASS)
            },
            replicationPolicy = ReplicationPolicy(),
            capturePolicy = CapturePolicy(minimumGrade = 0.6),
            rewardSpec = RewardSpec(RewardClass.SMALL, 1.0)
        ).withComputedPayloadHash()
    }
    val hiddenNodeIds = nodeIds - revealedNodeIds
    val stages = buildList {
        add(
            DisclosureStage(
                stageId = "stage-0",
                stageIndex = 0,
                title = "Foundation",
                nodeIds = revealedNodeIds,
                mandatoryNodeIds = revealedNodeIds,
                evidenceCoverageThreshold = 1.0,
                integrationReadinessThreshold = 1.0,
                revealPayloadHash = AtlasHashing.hashDisclosurePayload(
                    "stage-0",
                    revealedNodeIds,
                    nodes
                )
            )
        )
        if (hiddenNodeIds.isNotEmpty()) {
            add(
                DisclosureStage(
                    stageId = "stage-1",
                    stageIndex = 1,
                    title = "Future",
                    nodeIds = hiddenNodeIds,
                    mandatoryNodeIds = hiddenNodeIds,
                    evidenceCoverageThreshold = 1.0,
                    integrationReadinessThreshold = 1.0,
                    revealPayloadHash = AtlasHashing.hashDisclosurePayload(
                        "stage-1",
                        hiddenNodeIds,
                        nodes
                    )
                )
            )
        }
    }
    val atlas = NodeAtlas(
        atlasId = "worker-atlas-$runId",
        runId = runId,
        version = 1,
        receiverContractHash = AtlasHashing.hashReceiverContract(receiver),
        rootNodeIds = nodeIds,
        nodes = nodes,
        stageDefinitions = stages
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
                    revealedToGroups = if (nodeId in revealedNodeIds) {
                        setOf(AtlasGroupId(groupId))
                    } else {
                        emptySet()
                    },
                    grades = if (nodeId in passedNodeIds) {
                        listOf(
                            NodeGrade(
                                gradeId = "grade:${nodeId.value}",
                                groupId = AtlasGroupId(groupId),
                                evidencePacketId = "packet:${nodeId.value}",
                                normalizedScore = 0.9,
                                verdict = GradeVerdict.VALID_PASS,
                                graderIds = setOf("grader-a", "grader-b"),
                                round = 0
                            )
                        )
                    } else {
                        emptyList()
                    },
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
                                title = "Evidence ${nodeId.value}",
                                gradingHistory = if (nodeId in passedNodeIds) {
                                    listOf(
                                        DeliverableNodeGradeHistoryEntry(
                                            checkinId = "grade:${nodeId.value}",
                                            dimensionScores = mapOf("coverage" to 0.9),
                                            graderIds = listOf("grader-a", "grader-b"),
                                            rubricVersion = "fixture-v1",
                                            passedThreshold = true,
                                            recordedAtMs = 1
                                        )
                                    )
                                } else {
                                    emptyList()
                                }
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
                    stages,
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
