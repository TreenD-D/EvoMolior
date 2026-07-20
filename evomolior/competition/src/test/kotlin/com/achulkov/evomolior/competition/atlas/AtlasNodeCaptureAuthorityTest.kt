package com.achulkov.evomolior.competition.atlas

import com.achulkov.evomolior.competition.scoring.NodeCaptureResolutionRequest
import com.achulkov.evomolior.competition.scoring.NodeCaptureResolver
import com.achulkov.evomolior.core.atlas.ArtifactType
import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.CapturePolicy
import com.achulkov.evomolior.core.atlas.DisclosureStage
import com.achulkov.evomolior.core.atlas.EvidenceKind
import com.achulkov.evomolior.core.atlas.EvidenceProfile
import com.achulkov.evomolior.core.atlas.FinalOutputSpec
import com.achulkov.evomolior.core.atlas.InstrumentalRewardType
import com.achulkov.evomolior.core.atlas.LimitationNode
import com.achulkov.evomolior.core.atlas.NodeAtlas
import com.achulkov.evomolior.core.atlas.NodeDeliverableContract
import com.achulkov.evomolior.core.atlas.NodeEvidenceCostSummary
import com.achulkov.evomolior.core.atlas.NodeEvidencePacket
import com.achulkov.evomolior.core.atlas.NodeExecutionState
import com.achulkov.evomolior.core.atlas.NodeGradeBundle
import com.achulkov.evomolior.core.atlas.NodeGradeBundleVerdict
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.atlas.NodeKind
import com.achulkov.evomolior.core.atlas.NodeScoreVector
import com.achulkov.evomolior.core.atlas.NodeYieldSchedule
import com.achulkov.evomolior.core.atlas.ProofContract
import com.achulkov.evomolior.core.atlas.ProofMethod
import com.achulkov.evomolior.core.atlas.ReceiverContract
import com.achulkov.evomolior.core.atlas.ReplicationPolicy
import com.achulkov.evomolior.core.atlas.RevealMode
import com.achulkov.evomolior.core.atlas.RevealPolicy
import com.achulkov.evomolior.core.atlas.RewardClass
import com.achulkov.evomolior.core.atlas.RewardSpec
import com.achulkov.evomolior.core.atlas.ScheduledReward
import com.achulkov.evomolior.core.contract.DeliverableSkeletonNode
import com.achulkov.evomolior.core.contract.DeliverableSkeletonTree
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.atlas.AtlasProjectionSet
import com.achulkov.evomolior.substrate.atlas.AtlasTransactionRepository
import com.achulkov.evomolior.substrate.atlas.DisclosureProjection
import com.achulkov.evomolior.substrate.atlas.LegacyNodeMapping
import com.achulkov.evomolior.substrate.atlas.LegacyNodeSource
import com.achulkov.evomolior.substrate.atlas.NodeCaptureRepository
import com.achulkov.evomolior.substrate.atlas.PreparedAtlasMigration
import com.achulkov.evomolior.substrate.atlas.ResourceProjection
import com.achulkov.evomolior.substrate.atlas.ResourceProjectionNode
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AtlasNodeCaptureAuthorityTest {
    private val runId = "run-capture-authority"
    private val atlasId = "atlas-capture-authority"
    private val nodeId = NodeId("node-capture-authority")
    private val groupA = AtlasGroupId("group-a")
    private val groupB = AtlasGroupId("group-b")

    @Test
    fun `capture decision state grades and projections commit atomically`() = runTest {
        val factory = SubstrateDatabaseFactory(
            createTempDirectory("atlas-node-capture-authority-").toFile()
        )
        val atlasRepository = AtlasTransactionRepository(factory, now = { 100L })
        val migration = migration()
        atlasRepository.initialize(migration)
        val captureRepository = NodeCaptureRepository(factory, now = { 90L })
        val packets = listOf(groupA, groupB).associateWith { group ->
            captureRepository.appendEvidencePacket(packet(migration.atlas, group))
        }
        val bundles = packets.map { (group, packet) ->
            captureRepository.appendGradeBundle(
                bundle(
                    migration.atlas,
                    packet,
                    if (group == groupA) 0.78 else 0.92
                )
            )
        }
        val current = atlasRepository.currentStates(runId, atlasId).getValue(nodeId)
        val resolution = NodeCaptureResolver().resolve(
            NodeCaptureResolutionRequest(
                atlas = migration.atlas,
                currentState = current,
                round = 1,
                expectedGroupIds = setOf(groupA, groupB),
                bundles = bundles
            )
        )
        val authority = AtlasNodeCaptureAuthority(
            runId = runId,
            atlasId = atlasId,
            atlasRepository = atlasRepository,
            captureRepository = captureRepository,
            now = { 95L }
        )

        val commit = assertNotNull(authority.commit(resolution))

        assertEquals(2L, commit.commitSequence)
        val committedState = atlasRepository.currentStates(runId, atlasId).getValue(nodeId)
        assertEquals(groupB, committedState.ownerGroupId)
        assertEquals(2, committedState.grades.size)
        assertEquals(
            resolution.decision,
            captureRepository.captureDecisionsForNode(runId, nodeId).single()
        )
        val projections = assertNotNull(atlasRepository.currentProjections(runId, atlasId))
        assertEquals("group-b", projections.resources.nodes.single().claimedByGroupId)
        assertEquals(
            1,
            projections.deliverables
                .single { it.groupId == groupA.value }
                .nodes.single()
                .gradingHistory.size
        )
        assertEquals(
            1,
            projections.deliverables
                .single { it.groupId == groupB.value }
                .nodes.single()
                .gradingHistory.size
        )

        val winningBundle = bundles.single { it.groupId == groupB }
        val winningPacket = packets.getValue(groupB)
        val schedule = captureRepository.appendYieldSchedule(
            NodeYieldSchedule(
                scheduleId = "schedule-capture-authority",
                runId = runId,
                nodeId = nodeId,
                groupId = groupB,
                judgingRound = 1,
                activationRound = 2,
                captureDecisionId = resolution.decision.decisionId,
                captureDecisionHash = resolution.decision.decisionHash,
                evidencePacketHash = winningPacket.packetHash,
                scoreBundleHash = winningBundle.bundleHash,
                constraintPolicyHash = "yield-policy-hash",
                rewards = listOf(
                    ScheduledReward(InstrumentalRewardType.SPAWN_CREDITS, 2.0),
                    ScheduledReward(InstrumentalRewardType.RETRIEVAL_BUDGET, 1.0)
                )
            ).withComputedHash()
        )
        val yieldAuthority = AtlasNodeYieldAuthority(
            runId = runId,
            atlasId = atlasId,
            atlasRepository = atlasRepository,
            captureRepository = captureRepository
        )

        assertTrue(yieldAuthority.applyDue(groupB, 1).isEmpty())
        val applied = yieldAuthority.applyDue(groupB, 2).single()
        assertEquals(schedule.scheduleHash, applied.application.scheduleHash)
        assertTrue(yieldAuthority.applyDue(groupB, 2).isEmpty())
        val yieldedState = atlasRepository.currentStates(runId, atlasId).getValue(nodeId)
        assertEquals(2, yieldedState.round)
        assertEquals(2, yieldedState.yieldHistory.size)
        assertTrue(yieldedState.yieldHistory.all { it.scheduleHash == schedule.scheduleHash })
        assertEquals(
            applied.application,
            captureRepository.yieldApplicationForSchedule(runId, schedule.scheduleId)
        )
    }

    private fun migration(): PreparedAtlasMigration {
        val receiver = receiver()
        val node = LimitationNode(
            id = nodeId,
            kind = NodeKind.EVIDENCE,
            layer = 0,
            parentIds = emptySet(),
            prerequisiteIds = emptySet(),
            receiverWhyRef = "outcome-1",
            limitationStatement = "Evidence is missing.",
            capabilityTarget = "Establish exact evidence.",
            atomicQuestion = "What evidence resolves the limitation?",
            proofContract = ProofContract(
                method = ProofMethod.SOURCE_CORROBORATION,
                acceptanceCriteria = setOf("Correct")
            ),
            deliverableContract = NodeDeliverableContract(
                acceptedArtifactTypes = setOf(ArtifactType.EVIDENCE_TABLE)
            ),
            revealPolicy = RevealPolicy("stage-0", RevealMode.INITIAL),
            replicationPolicy = ReplicationPolicy(),
            capturePolicy = CapturePolicy(minimumGrade = 0.7),
            rewardSpec = RewardSpec(RewardClass.SMALL, 1.0)
        ).withComputedPayloadHash()
        val nodes = mapOf(nodeId to node)
        val atlas = NodeAtlas(
            atlasId = atlasId,
            runId = runId,
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
        val groupIds = listOf(groupA.value, groupB.value)
        val deliverables = groupIds.map { groupId ->
            DeliverableSkeletonTree(
                runId = runId,
                groupId = groupId,
                rootNodeId = nodeId.value,
                nodes = listOf(
                    DeliverableSkeletonNode(
                        nodeId = nodeId.value,
                        title = "Evidence node"
                    )
                ),
                createdAtMs = 1L,
                updatedAtMs = 1L
            )
        }
        return PreparedAtlasMigration(
            receiverContract = receiver,
            atlas = atlas,
            executionStates = listOf(
                NodeExecutionState(
                    nodeId = nodeId,
                    round = 0,
                    revealedToGroups = setOf(groupA, groupB)
                )
            ),
            projections = AtlasProjectionSet(
                wip = emptyList(),
                deliverables = deliverables,
                resources = ResourceProjection(
                    runId = runId,
                    nodes = listOf(
                        ResourceProjectionNode(
                            resourceNodeId = "resource-1",
                            atlasNodeId = nodeId,
                            name = "Evidence resource",
                            resourceType = "WEB_CACHE",
                            yieldMultiplier = 0.9
                        )
                    )
                ),
                disclosure = DisclosureProjection(
                    runId = runId,
                    stages = atlas.stageDefinitions,
                    groupStageIndices = groupIds.associateWith { 0 }
                ),
                mappings = buildList {
                    groupIds.forEach { groupId ->
                        add(
                            LegacyNodeMapping(
                                source = LegacyNodeSource.DELIVERABLE,
                                sourceNodeId = nodeId.value,
                                scopeId = groupId,
                                atlasNodeId = nodeId
                            )
                        )
                    }
                    add(
                        LegacyNodeMapping(
                            source = LegacyNodeSource.RESOURCE,
                            sourceNodeId = "resource-1",
                            scopeId = runId,
                            atlasNodeId = nodeId
                        )
                    )
                }
            )
        )
    }

    private fun packet(atlas: NodeAtlas, group: AtlasGroupId): NodeEvidencePacket =
        NodeEvidencePacket(
            packetId = "packet-${group.value}",
            runId = runId,
            round = 1,
            groupId = group,
            nodeId = nodeId,
            atlasVersion = atlas.version,
            atlasHash = atlas.atlasHash,
            nodePayloadHash = atlas.nodes.getValue(nodeId).payloadHash,
            acceptedArtifactIds = listOf("artifact-${group.value}"),
            provenanceRootHash = "provenance-${group.value}",
            cost = NodeEvidenceCostSummary(totalCredits = 1.0),
            sealedAtEpochMillis = 10L
        ).withComputedHash()

    private fun bundle(
        atlas: NodeAtlas,
        packet: NodeEvidencePacket,
        score: Double
    ): NodeGradeBundle {
        val node = atlas.nodes.getValue(nodeId)
        return NodeGradeBundle(
            bundleId = "bundle-${packet.groupId.value}",
            runId = runId,
            round = 1,
            groupId = packet.groupId,
            nodeId = nodeId,
            atlasVersion = atlas.version,
            atlasHash = atlas.atlasHash,
            nodePayloadHash = node.payloadHash,
            evidencePacketId = packet.packetId,
            evidencePacketHash = packet.packetHash,
            criteriaHash = AtlasHashing.hashNodeGradingCriteria(node),
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
                "response-${packet.groupId.value}-1",
                "response-${packet.groupId.value}-2"
            )
        ).withComputedHash()
    }

    private fun receiver(): ReceiverContract = ReceiverContract(
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
}
