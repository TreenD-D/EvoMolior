@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.core.atlas.ArtifactType
import com.achulkov.evomolior.core.atlas.AtlasAgentId
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
import com.achulkov.evomolior.core.atlas.NodeExecutionState
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.atlas.NodeKind
import com.achulkov.evomolior.core.atlas.NoveltyAssessmentStatus
import com.achulkov.evomolior.core.atlas.NoveltyGraderAssessment
import com.achulkov.evomolior.core.atlas.NoveltyGraderVector
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
import com.achulkov.evomolior.core.atlas.ShadowNodeLifecycleEvent
import com.achulkov.evomolior.core.atlas.ShadowNodeState
import com.achulkov.evomolior.core.contract.CommitType
import com.achulkov.evomolior.core.contract.ContributionCommit
import com.achulkov.evomolior.core.contract.GraderFailureModeDraft
import com.achulkov.evomolior.core.contract.GraderMethodologyDraft
import com.achulkov.evomolior.core.contract.GraderRequirementDraft
import com.achulkov.evomolior.core.contract.GraderRubricDraft
import com.achulkov.evomolior.core.grader.DisclosureSensitivity
import com.achulkov.evomolior.core.grader.FailureLevel
import com.achulkov.evomolior.core.grader.GraderAtlas
import com.achulkov.evomolior.core.grader.GraderAtlasNode
import com.achulkov.evomolior.core.grader.GraderNodeId
import com.achulkov.evomolior.core.grader.GraderNodeKind
import com.achulkov.evomolior.competition.coordinator.GroupCoordinator
import com.achulkov.evomolior.competition.grader.GraderGroupManager
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.atlas.AtlasProjectionSet
import com.achulkov.evomolior.substrate.atlas.AtlasTransactionRepository
import com.achulkov.evomolior.substrate.atlas.DisclosureProjection
import com.achulkov.evomolior.substrate.atlas.LegacyNodeMapping
import com.achulkov.evomolior.substrate.atlas.LegacyNodeSource
import com.achulkov.evomolior.substrate.atlas.PreparedAtlasMigration
import com.achulkov.evomolior.substrate.atlas.ResourceProjection
import com.achulkov.evomolior.substrate.atlas.ResourceProjectionNode
import com.achulkov.evomolior.substrate.atlas.WipProjection
import com.achulkov.evomolior.substrate.atlas.WipProjectionTask
import com.achulkov.evomolior.substrate.db.entity.OutlierFitnessRecordEntity
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import com.achulkov.evomolior.substrate.grader.GraderAuthorityRepository
import com.achulkov.evomolior.substrate.outlier.OutlierNoveltyRepository
import com.achulkov.evomolior.substrate.worker.WorkerExecutionRepository
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class OutlierNoveltyRoundProcessorTest {
    private val runId = "run-outlier-flow"
    private val groupId = AtlasGroupId("worker-a")
    private val nodeId = NodeId("visible-node")
    private val graderNodeId = GraderNodeId("grader-node")

    @Test
    fun `accepted shadow atomically evolves both atlases and retries after transaction rollback`() =
        runTest {
        val factory = SubstrateDatabaseFactory(
            createTempDirectory("outlier-round-processor-").toFile()
        )
        val atlasRepository = AtlasTransactionRepository(factory, now = { 100L })
        val receiver = receiver()
        val atlas = atlas(receiver)
        atlasRepository.initialize(
            PreparedAtlasMigration(
                receiverContract = receiver,
                atlas = atlas,
                executionStates = listOf(
                    NodeExecutionState(
                        nodeId = nodeId,
                        round = 0,
                        revealedToGroups = setOf(groupId)
                    )
                ),
                projections = projections(atlas)
            )
        )
        val graderRepository = GraderAuthorityRepository(factory, now = { 100L })
        val initialGraderAtlas = graderAtlas(atlas)
        graderRepository.appendAtlas(initialGraderAtlas)
        val roundAuthority = RoundAtlasAuthority(atlas, initialGraderAtlas)
        val proposal = proposal(atlas)
        val groupNovelty = OutlierNoveltyRepository(factory.get(runId, groupId.value))
        groupNovelty.appendInitialProposal(
            proposal,
            ShadowNodeLifecycleEvent(
                eventId = "${proposal.proposalId}:1:PROPOSED",
                proposalId = proposal.proposalId,
                proposalHash = proposal.proposalHash,
                runId = runId,
                sequence = 1,
                previousEventHash = null,
                fromState = null,
                toState = ShadowNodeState.PROPOSED,
                round = 0
            ).withComputedHash()
        )
        val processor = OutlierNoveltyRoundProcessor(
            substrateFactory = factory,
            atlasRepository = atlasRepository,
            assessmentRunner = { proposals ->
                proposals.flatMap { value ->
                    listOf(assessment(value, "grader-a"), assessment(value, "grader-b"))
                }
            }
        )

        val proposedRound = processor.process(
            runId = runId,
            atlasId = atlas.atlasId,
            workerGroupIds = listOf(groupId.value),
            graderGroupIds = setOf("grader-a", "grader-b"),
            round = 0
        )

        assertEquals(1, proposedRound.atlas.version)
        assertEquals(1, proposedRound.metrics.acceptedShadowNodeCount)
        val global = OutlierNoveltyRepository(factory.getAtlas(runId))
        assertEquals(
            ShadowNodeState.SHADOW_NODE,
            global.lifecycle(proposal.proposalId).last().toState
        )
        assertTrue(global.fitnessForProposal(proposal.proposalId).isEmpty())

        val contributionDraft = ContributionCommit(
            commitId = "later-test",
            runId = runId,
            groupId = groupId,
            round = 1,
            agentId = AtlasAgentId("worker-tester"),
            nodeId = nodeId,
            commitType = CommitType.INTEGRATION_RESULT,
            artifactIds = listOf("artifact-repair-test"),
            claimIds = emptyList(),
            toolRunIds = emptyList(),
            testsRun = listOf("repair-test"),
            referencedNoveltyProposalIds = listOf(proposal.proposalId),
            changedStateSummary = "Tested and adopted the repairability vector.",
            evidenceSummary = "The independent repair test passed.",
            confidence = 0.9,
            unresolvedRisks = emptyList(),
            successorRecommendations = emptyList(),
            dedupeHash = "",
            committedAt = Instant.parse("2026-07-12T01:00:00Z")
        )
        WorkerExecutionRepository(factory.get(runId, groupId.value)).commit(
            contributionDraft.copy(
                dedupeHash = AtlasHashing.hashContributionDedupe(contributionDraft)
            )
        )

        factory.getAtlas(runId).outlierNoveltyDao().insertFitness(
            OutlierFitnessRecordEntity(
                recordId = AtlasHashing.hashText("${proposal.proposalHash}|fitness|1"),
                proposalId = "conflicting-proposal",
                proposalHash = "conflicting-proposal-hash",
                agentId = "conflicting-agent",
                observationRound = 99,
                recordHash = "conflicting-record-hash",
                contentJson = "{}",
                createdAtMs = 101L
            )
        )
        assertFailsWith<Exception> {
            processor.process(
                runId = runId,
                atlasId = atlas.atlasId,
                workerGroupIds = listOf(groupId.value),
                graderGroupIds = setOf("grader-a", "grader-b"),
                round = 1
            )
        }
        assertEquals(1, atlasRepository.currentAtlas(runId, atlas.atlasId)?.version)
        assertEquals(1, graderRepository.currentAtlas(runId)?.version)
        assertEquals(
            ShadowNodeState.SHADOW_NODE,
            global.lifecycle(proposal.proposalId).last().toState
        )
        assertTrue(
            assertNotNull(atlasRepository.currentProjections(runId, atlas.atlasId))
                .wip
                .flatMap { it.tasks }
                .all { it.atlasNodeId == nodeId }
        )

        val service = service(factory)
        factory.getGrader(runId, "grader-a").stagingDao().insert(
            promotedMethodology(
                id = "round-2-v1-methodology",
                graderGroupId = "grader-a",
                generation = 3,
                workerNodeIds = setOf(nodeId)
            )
        )
        val roundTwoFreeze = service.freezeCompetitiveGraderCriteria(
            runId = runId,
            graderGroupIds = listOf("grader-a"),
            competingGroupIds = listOf(groupId.value),
            round = 2,
            roundAuthority = roundAuthority
        ).getValue("grader-a")
        assertEquals(initialGraderAtlas.atlasHash, roundTwoFreeze.graderAtlasHash)

        val evidenceRound = processor.process(
            runId = runId,
            atlasId = atlas.atlasId,
            workerGroupIds = listOf(groupId.value),
            graderGroupIds = setOf("grader-a", "grader-b"),
            round = 2
        )

        assertEquals(2, evidenceRound.atlas.version)
        assertEquals(1, evidenceRound.metrics.testedProposalCount)
        assertEquals(1, evidenceRound.metrics.acceptedAmendmentCount)
        assertEquals(
            ShadowNodeState.ACCEPTED_AMENDMENT,
            global.lifecycle(proposal.proposalId).last().toState
        )
        assertEquals(1, global.downstreamLineage(proposal.proposalId).size)
        assertEquals(1, global.fitnessForProposal(proposal.proposalId).size)
        val addedNode = evidenceRound.atlas.nodes.values.single { it.id != nodeId }
        assertEquals(3, addedNode.revealPolicy.minimumRound)
        assertNotNull(
            atlasRepository.currentProjections(runId, atlas.atlasId)
                ?.wip
                ?.single()
                ?.tasks
                ?.singleOrNull { it.atlasNodeId == addedNode.id }
        )
        val evolvedGraderAtlas = assertNotNull(graderRepository.currentAtlas(runId))
        assertEquals(2, evolvedGraderAtlas.version)
        assertEquals(evidenceRound.atlas.atlasHash, evolvedGraderAtlas.workerAtlasHash)
        assertEquals(
            evidenceRound.atlas.nodes.keys,
            evolvedGraderAtlas.nodes.values.single().mappedWorkerNodeIds
        )
        assertEquals(1, roundAuthority.workerAtlas.version)
        assertEquals(1, roundAuthority.graderAtlas.version)
        val nextRoundAuthority = RoundAtlasAuthority(evidenceRound.atlas, evolvedGraderAtlas)
        assertEquals(2, nextRoundAuthority.workerAtlas.version)
        assertEquals(2, nextRoundAuthority.graderAtlas.version)
        factory.getGrader(runId, "grader-a").stagingDao().insert(
            promotedMethodology(
                id = "round-3-v2-methodology",
                graderGroupId = "grader-a",
                generation = 4,
                workerNodeIds = evidenceRound.atlas.nodes.keys
            )
        )
        val roundThreeFreeze = service.freezeCompetitiveGraderCriteria(
            runId = runId,
            graderGroupIds = listOf("grader-a"),
            competingGroupIds = listOf(groupId.value),
            round = 3,
            roundAuthority = nextRoundAuthority
        ).getValue("grader-a")
        assertEquals(evolvedGraderAtlas.atlasHash, roundThreeFreeze.graderAtlasHash)
        assertEquals(
            evidenceRound.atlas.nodes.keys,
            graderRepository.requirementsForRound(runId, 3, "grader-a")
                .flatMapTo(linkedSetOf()) { it.mappedWorkerNodeIds }
        )
    }

    private fun proposal(atlas: NodeAtlas) = NoveltyVectorProposal(
        proposalId = "proposal-repair",
        runId = runId,
        atlasHash = atlas.atlasHash,
        groupId = groupId,
        round = 0,
        proposerAgentId = AtlasAgentId("outlier-a"),
        noveltyType = NoveltyType.MISSING_INTEGRATION_DEPENDENCY,
        affectedVisibleNodeIds = setOf(nodeId),
        missingLayerOrAssumption = "Distributed repairability is not represented.",
        proposedVector = "Measure repair cost under independent maintenance.",
        whyCurrentMapMayMissIt = "The current node evaluates initial assembly only.",
        falsifiablePrediction = "Modularity lowers repair time by thirty percent.",
        minimalTestOrArtifact = "Run a blinded repair comparison.",
        expectedReceiverImpact = "Improves long-term receiver reliability.",
        expectedCost = 3.0,
        noveltyFingerprint = "distributed-repairability"
    ).withComputedHash()

    private fun assessment(
        proposal: NoveltyVectorProposal,
        graderId: String
    ) = NoveltyGraderAssessment(
        assessmentId = "$graderId:${proposal.proposalId}",
        proposalHash = proposal.proposalHash,
        graderGroupId = graderId,
        status = NoveltyAssessmentStatus.VALID,
        vector = NoveltyGraderVector(0.9, 0.9, 0.9, 0.8, 0.9, 0.8),
        rationaleHash = AtlasHashing.hashText("$graderId rationale")
    ).withComputedHash()

    private fun receiver() = ReceiverContract(
        rawObjective = "Build a maintainable receiver artifact.",
        receiverId = "operator",
        receiverDescription = "Operator",
        situation = "Long-lived deployment",
        decisionOrUse = "Adopt the artifact",
        whyNeeded = "Reduce maintenance cost",
        successOutcomes = listOf("Reliable repair"),
        unacceptableOutcomes = listOf("Unverifiable behavior"),
        constraints = listOf("Auditable"),
        finalDeliverableContract = FinalOutputSpec(
            relativePath = "result.json",
            mediaType = "application/json",
            requiredSections = setOf("result")
        ),
        evidenceProfile = EvidenceProfile(
            acceptedEvidenceKinds = setOf(EvidenceKind.PRIMARY_SOURCE)
        )
    )

    private fun atlas(receiver: ReceiverContract): NodeAtlas {
        val node = LimitationNode(
            id = nodeId,
            kind = NodeKind.INTEGRATION,
            layer = 0,
            parentIds = emptySet(),
            prerequisiteIds = emptySet(),
            receiverWhyRef = "maintainability",
            limitationStatement = "Initial components must integrate.",
            capabilityTarget = "Produce a coherent artifact.",
            atomicQuestion = "Do the components integrate?",
            proofContract = ProofContract(
                ProofMethod.RECEIVER_VALIDATION,
                setOf("Integration succeeds")
            ),
            deliverableContract = NodeDeliverableContract(
                setOf(ArtifactType.DELIVERABLE_FRAGMENT)
            ),
            revealPolicy = RevealPolicy("stage-0", RevealMode.INITIAL),
            replicationPolicy = ReplicationPolicy(),
            capturePolicy = CapturePolicy(minimumGrade = 0.7),
            rewardSpec = RewardSpec(RewardClass.MEDIUM, 2.0)
        ).withComputedPayloadHash()
        val nodes = mapOf(node.id to node)
        val stage = DisclosureStage(
            stageId = "stage-0",
            stageIndex = 0,
            title = "Initial",
            nodeIds = setOf(node.id),
            mandatoryNodeIds = setOf(node.id),
            evidenceCoverageThreshold = 1.0,
            integrationReadinessThreshold = 0.7,
            revealPayloadHash = AtlasHashing.hashDisclosurePayload(
                "stage-0",
                setOf(node.id),
                nodes
            )
        )
        return NodeAtlas(
            atlasId = "atlas-outlier-flow",
            runId = runId,
            version = 1,
            receiverContractHash = AtlasHashing.hashReceiverContract(receiver),
            rootNodeIds = setOf(node.id),
            nodes = nodes,
            stageDefinitions = listOf(stage)
        ).withComputedHash()
    }

    private fun graderAtlas(workerAtlas: NodeAtlas): GraderAtlas {
        val graderNode = GraderAtlasNode(
            nodeId = graderNodeId,
            kind = GraderNodeKind.EVIDENCE_AUTHENTICITY,
            title = "Evidence authenticity",
            evaluationQuestion = "Does exact evidence support every worker node?",
            receiverObligationRefs = setOf("maintainability"),
            mappedWorkerNodeIds = workerAtlas.nodes.keys
        ).withComputedHash()
        return GraderAtlas(
            graderAtlasId = "grader-atlas-outlier-flow",
            runId = runId,
            version = workerAtlas.version,
            workerAtlasHash = workerAtlas.atlasHash,
            rootNodeIds = setOf(graderNodeId),
            nodes = mapOf(graderNodeId to graderNode)
        ).withComputedHash()
    }

    private fun service(factory: SubstrateDatabaseFactory) = EvoMoliorRunService(
        lifecycleManagerProvider = { _, _, _, _, _ -> error("unused") },
        groupCoordinatorProvider = { groupIds, dimensions ->
            GroupCoordinator(groupIds, dimensions)
        },
        graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
        substrateFactory = factory
    )

    private fun promotedMethodology(
        id: String,
        graderGroupId: String,
        generation: Int,
        workerNodeIds: Set<NodeId>
    ) = StagingSubmissionEntity(
        id = id,
        agentId = "agent-$id",
        role = "GraderResearcher",
        type = "PLAN_UPDATE",
        content = Json.encodeToString(
            GraderMethodologyDraft.serializer(),
            GraderMethodologyDraft(
                rubricDimensions = listOf(
                    GraderRubricDraft("evidence", "Grade exact evidence.")
                ),
                automatedTests = emptyList(),
                failureModes = listOf(
                    GraderFailureModeDraft("unsupported", "Unsupported material claim.")
                ),
                requirements = workerNodeIds.sortedBy(NodeId::value).mapIndexed { index, nodeId ->
                    GraderRequirementDraft(
                        requirementId = "requirement-$generation-$index",
                        graderAtlasNodeId = graderNodeId,
                        mappedWorkerNodeIds = setOf(nodeId),
                        mappedReceiverObligationRefs = setOf("maintainability"),
                        criterion = "Exact evidence satisfies ${nodeId.value}.",
                        observableEvidence = "Critic-promoted packet-bound evidence.",
                        passThreshold = "The atomic node claim is fully supported.",
                        failureLevels = listOf(
                            FailureLevel(1, "Material evidence is missing.", 0.4)
                        ),
                        adversarialCases = emptyList(),
                        executableTestIds = emptyList(),
                        disclosureSensitivity = DisclosureSensitivity.GRADER_INTERNAL,
                        rationale = "The receiver requires auditable evidence.",
                        noveltyFingerprint = "evidence-$generation-$index",
                        maximumEvaluationCost = 1.0
                    )
                }
            )
        ),
        budgetSignature = "GRADING_SHEET_DRAFT:agent-$id:1",
        status = "promoted",
        generation = generation,
        groupId = graderGroupId,
        runId = runId,
        submittedAtMs = generation.toLong() * 100,
        reviewedAtMs = generation.toLong() * 100 + 1
    )

    private fun projections(atlas: NodeAtlas): AtlasProjectionSet {
        val taskId = "task-visible"
        val resourceId = "resource-visible"
        return AtlasProjectionSet(
            wip = listOf(
                WipProjection(
                    runId,
                    groupId.value,
                    listOf(
                        WipProjectionTask(
                            taskId = taskId,
                            atlasNodeId = nodeId,
                            description = "Integrate the artifact.",
                            lastChangedGeneration = 0
                        )
                    )
                )
            ),
            deliverables = emptyList(),
            resources = ResourceProjection(
                runId,
                listOf(
                    ResourceProjectionNode(
                        resourceNodeId = resourceId,
                        atlasNodeId = nodeId,
                        name = "Integration",
                        resourceType = "GOSSIP_RELAY",
                        yieldMultiplier = 1.0
                    )
                )
            ),
            disclosure = DisclosureProjection(
                runId = runId,
                stages = atlas.stageDefinitions,
                groupStageIndices = mapOf(groupId.value to 0)
            ),
            mappings = listOf(
                LegacyNodeMapping(LegacyNodeSource.WIP, taskId, groupId.value, nodeId),
                LegacyNodeMapping(LegacyNodeSource.RESOURCE, resourceId, runId, nodeId)
            )
        )
    }
}
