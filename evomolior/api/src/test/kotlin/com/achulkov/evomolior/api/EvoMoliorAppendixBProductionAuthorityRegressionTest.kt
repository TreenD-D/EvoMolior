@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.api

import com.achulkov.evomolior.agents.tools.CommitContributionTool
import com.achulkov.evomolior.agents.tools.WriteStagingTool
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
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.atlas.NodeKind
import com.achulkov.evomolior.core.atlas.NodeLease
import com.achulkov.evomolior.core.atlas.NodeStatus
import com.achulkov.evomolior.core.atlas.ProofContract
import com.achulkov.evomolior.core.atlas.ProofMethod
import com.achulkov.evomolior.core.atlas.ReceiverContract
import com.achulkov.evomolior.core.atlas.ReplicationPolicy
import com.achulkov.evomolior.core.atlas.RevealMode
import com.achulkov.evomolior.core.atlas.RevealPolicy
import com.achulkov.evomolior.core.atlas.RewardClass
import com.achulkov.evomolior.core.atlas.RewardSpec
import com.achulkov.evomolior.core.atlas.LeaseState
import com.achulkov.evomolior.substrate.atlas.AtlasLeaseAuthority
import com.achulkov.evomolior.core.contract.AgentBootContext
import com.achulkov.evomolior.core.contract.AgentTtlPolicy
import com.achulkov.evomolior.core.contract.AtlasSkeletonNodeView
import com.achulkov.evomolior.core.contract.AtlasSkeletonView
import com.achulkov.evomolior.core.contract.CriticOutcome
import com.achulkov.evomolior.core.contract.CriticReview
import com.achulkov.evomolior.core.contract.DeliverableSkeletonNode
import com.achulkov.evomolior.core.contract.DeliverableSkeletonTree
import com.achulkov.evomolior.core.contract.WorkerLeaseAssignment
import com.achulkov.evomolior.core.contract.WorkerView
import com.achulkov.evomolior.core.contract.WipTaskClaim
import com.achulkov.evomolior.core.contract.toRevealedNodeCard
import com.achulkov.evomolior.core.model.GroupProfile
import com.achulkov.evomolior.core.model.GroupProfileType
import com.achulkov.evomolior.core.model.Role
import com.achulkov.evomolior.orchestration.lifecycle.CriticPipeline
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.atlas.AtlasProjectionSet
import com.achulkov.evomolior.substrate.atlas.AtlasTransactionRepository
import com.achulkov.evomolior.substrate.atlas.DisclosureProjection
import com.achulkov.evomolior.substrate.atlas.LegacyNodeMapping
import com.achulkov.evomolior.substrate.atlas.LegacyNodeSource
import com.achulkov.evomolior.core.atlas.NodeExecutionState
import com.achulkov.evomolior.substrate.atlas.NodeCaptureRepository
import com.achulkov.evomolior.substrate.atlas.NodeEvidencePacketSealer
import com.achulkov.evomolior.substrate.atlas.PreparedAtlasMigration
import com.achulkov.evomolior.substrate.atlas.ResourceProjection
import com.achulkov.evomolior.substrate.atlas.ResourceProjectionNode
import com.achulkov.evomolior.substrate.atlas.SealNodeEvidenceRequest
import com.achulkov.evomolior.substrate.db.entity.WipTaskEntity
import com.achulkov.evomolior.substrate.worker.WorkerExecutionRepository
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Appendix B regression for the production authority chain.
 */
class EvoMoliorAppendixBProductionAuthorityRegressionTest {
    private val runId = "run-appendix-b"
    private val atlasId = "atlas-appendix-b"
    private val nodeId = NodeId("node-appendix-b")
    private val groupA = AtlasGroupId("group-a")
    private val groupB = AtlasGroupId("group-b")
    private val agentA = "agent-a"
    private val agentB = "agent-b"
    private val criticId = "critic-appendix-b"
    private val round = 1

    @Test
    fun `leased staging commits promote seal and settle while sibling staging without contribution cannot seal`() =
        runTest {
            val root = createTempDirectory("appendix-b-authority-").toFile()
            val factory = SubstrateDatabaseFactory(root)
            val migration = migration()
            AtlasTransactionRepository(factory).initialize(migration)
            val captureRepository = NodeCaptureRepository(factory)
            val sealer = NodeEvidencePacketSealer(factory, captureRepository)

            val leaseAuthority = AtlasLeaseAuthority(AtlasTransactionRepository(factory))
            val happy = groupHarness(factory, migration, leaseAuthority, groupA, agentA)
            happy.bootContext.requireValidWorkerAuthority()
            assertTrue(
                writeTool(happy.database, agentA, groupA, nodeId.value, happy.ledger).execute(
                    WriteStagingTool.Args(
                        type = "DOC",
                        content = "Appendix B evidence for ${groupA.value}.",
                        speculative = false
                    )
                ).startsWith("Submission accepted")
            )
            val happySubmission = happy.database.stagingDao()
                .getByAgent(runId, groupA.value, agentA)
                .single()
            assertTrue(
                commitTool(happy.database, happy.bootContext, happy.ledger).execute(
                    CommitContributionTool.Args(
                        commitType = "POSITIVE_RESULT",
                        nodeId = nodeId.value,
                        artifactIds = listOf(happySubmission.id),
                        changedStateSummary = "Persisted the leased node work.",
                        evidenceSummary = "The staged artifact is durably committed.",
                        confidence = 0.9
                    )
                ).startsWith("Contribution committed")
            )
            assertEquals(1, criticPipeline(happy.database, root, groupA).processPendingSubmissions().promoted)
            assertEquals(
                "completed",
                assertNotNull(
                    happy.database.wipTaskDao().getByNodeId(nodeId.value, runId, groupA.value)
                ).status
            )
            assertEquals(
                1,
                WorkerExecutionRepository(happy.database).contributionsForNode(
                    runId,
                    groupA.value,
                    nodeId.value
                ).size
            )

            val happyPacket = sealer.seal(sealRequest(migration.atlas, groupA, happySubmission.id))
            assertEquals(listOf(happySubmission.id), happyPacket.acceptedArtifactIds)
            assertEquals(nodeId, happyPacket.nodeId)
            assertTrue(happyPacket.hasValidHash())
            assertEquals(1, captureRepository.evidencePacketsForNode(runId, nodeId).size)
            val committedLease = leaseAuthority.finalize(
                runId = runId,
                atlasId = atlasId,
                leaseId = happy.nodeLease.leaseId,
                committed = true
            )
            assertEquals(LeaseState.COMMITTED, committedLease.state)
            assertEquals(
                LeaseState.COMMITTED,
                AtlasTransactionRepository(factory).currentStates(runId, atlasId)
                    .getValue(nodeId)
                    .leases
                    .single { it.leaseId == happy.nodeLease.leaseId }
                    .state
            )

            val sibling = groupHarness(factory, migration, leaseAuthority, groupB, agentB)
            sibling.bootContext.requireValidWorkerAuthority()
            assertTrue(
                writeTool(sibling.database, agentB, groupB, nodeId.value, sibling.ledger).execute(
                    WriteStagingTool.Args(
                        type = "DOC",
                        content = "Appendix B sibling evidence for ${groupB.value}.",
                        speculative = false
                    )
                ).startsWith("Submission accepted")
            )
            val siblingSubmission = sibling.database.stagingDao()
                .getByAgent(runId, groupB.value, agentB)
                .single()
            assertEquals(1, criticPipeline(sibling.database, root, groupB).processPendingSubmissions().promoted)
            assertEquals(
                "completed",
                assertNotNull(
                    sibling.database.wipTaskDao().getByNodeId(nodeId.value, runId, groupB.value)
                ).status
            )
            assertEquals(
                0,
                WorkerExecutionRepository(sibling.database).contributionsForNode(
                    runId,
                    groupB.value,
                    nodeId.value
                ).size
            )
            assertEquals(LeaseState.ACTIVE, sibling.nodeLease.state)

            assertFailsWith<IllegalArgumentException> {
                sealer.seal(sealRequest(migration.atlas, groupB, siblingSubmission.id))
            }
            assertEquals(1, captureRepository.evidencePacketsForNode(runId, nodeId).size)
            assertEquals(
                LeaseState.ACTIVE,
                AtlasTransactionRepository(factory).currentStates(runId, atlasId)
                    .getValue(nodeId)
                    .leases
                    .single { it.leaseId == sibling.nodeLease.leaseId }
                    .state
            )
        }

    private fun criticPipeline(
        database: com.achulkov.evomolior.substrate.db.EvoMoliorDatabase,
        toolsRoot: java.io.File,
        groupId: AtlasGroupId
    ) = CriticPipeline(
        db = database,
        runId = runId,
        groupId = groupId.value,
        generation = round,
        openRouterApiKey = "test",
        criticModel = "test-model",
        criticAgentId = criticId,
        criticReviewRunner = { submission, _, _, critic ->
            CriticReview(
                submissionId = submission.id,
                outcome = CriticOutcome.PROMOTE,
                criticAgentId = critic,
                initialTrustScore = 0.9
            )
        },
        toolsMountHostPath = toolsRoot.absolutePath,
        onWipTaskCompleted = { _, _, _, _ -> },
        onSubmissionPromoted = { _, _ -> }
    )

    private fun writeTool(
        database: com.achulkov.evomolior.substrate.db.EvoMoliorDatabase,
        agentId: String,
        groupId: AtlasGroupId,
        defaultWipTaskRef: String,
        budgetLedger: com.achulkov.evomolior.core.budget.BudgetLedger
    ): WriteStagingTool = WriteStagingTool(
        stagingDao = database.stagingDao(),
        budgetLedger = budgetLedger,
        runId = runId,
        groupId = groupId.value,
        agentId = agentId,
        role = Role.Researcher.name,
        generation = round,
        defaultWipTaskRef = defaultWipTaskRef,
        isOutlier = false
    )

    private fun commitTool(
        database: com.achulkov.evomolior.substrate.db.EvoMoliorDatabase,
        bootContext: AgentBootContext,
        budgetLedger: com.achulkov.evomolior.core.budget.BudgetLedger
    ): CommitContributionTool = CommitContributionTool(
        repository = WorkerExecutionRepository(database),
        bootContext = bootContext,
        budgetLedger = budgetLedger,
        now = { Instant.parse("2026-07-12T12:00:00Z") }
    )

    private fun budgetLedger(agentId: String, groupId: String) = com.achulkov.evomolior.core.budget.BudgetLedger(
        agentId = agentId,
        initialBudget = 10.0,
        floor = 1.0,
        config = com.achulkov.evomolior.core.budget.BudgetConfig(
            groupId = groupId,
            profile = GroupProfile.default(GroupProfileType.Library)
        )
    )

    private suspend fun groupHarness(
        factory: SubstrateDatabaseFactory,
        migration: PreparedAtlasMigration,
        leaseAuthority: AtlasLeaseAuthority,
        groupId: AtlasGroupId,
        agentId: String
    ): GroupHarness {
        val database = factory.get(runId, groupId.value)
        val ledger = budgetLedger(agentId, groupId.value)
        val lease = leaseAuthority.acquire(
            runId = runId,
            atlasId = atlasId,
            groupId = groupId,
            agentId = AtlasAgentId(agentId),
            nodeId = nodeId,
            round = round,
            expiresAt = Instant.parse("2099-01-01T00:00:00Z"),
            replicationSlot = if (groupId == groupA) 0 else 1
        )
        database.wipTaskDao().insertAll(
            listOf(
                WipTaskEntity(
                    nodeId = nodeId.value,
                    runId = runId,
                    groupId = groupId.value,
                    description = "Produce the exact Appendix B node evidence.",
                    claimedByAgentId = null,
                    status = "unclaimed",
                    lastChangedGeneration = round,
                    linkedNodeId = nodeId.value
                )
            )
        )
        database.wipTaskDao().claimTask(
            nodeId = nodeId.value,
            runId = runId,
            groupId = groupId.value,
            agentId = agentId,
            generation = round
        )
        return GroupHarness(
            database = database,
            bootContext = bootContext(migration, groupId, agentId, lease),
            ledger = ledger,
            nodeLease = lease
        )
    }

    private fun bootContext(
        migration: PreparedAtlasMigration,
        groupId: AtlasGroupId,
        agentId: String,
        nodeLease: NodeLease
    ): AgentBootContext {
        val node = migration.atlas.nodes.getValue(nodeId)
        val revealedNode = node.toRevealedNodeCard()
        val workerView = WorkerView(
            runId = runId,
            groupId = groupId,
            round = round,
            stageId = "stage-0",
            visibleAtlasSkeleton = AtlasSkeletonView(
                atlasId = atlasId,
                atlasVersion = migration.atlas.version,
                atlasHash = migration.atlas.atlasHash,
                rootNodeIds = setOf(nodeId),
                nodes = mapOf(
                    nodeId to AtlasSkeletonNodeView(
                        nodeId = nodeId,
                        kind = NodeKind.EVIDENCE,
                        layer = 0,
                        parentIds = emptySet(),
                        prerequisiteIds = emptySet(),
                        alternativeToIds = emptySet(),
                        stageId = "stage-0",
                        payloadHash = node.payloadHash,
                        status = NodeStatus.REVEALED,
                        congestionLevel = 0
                    )
                )
            ),
            revealedNodes = listOf(revealedNode),
            assignedNode = revealedNode,
            groupFeedback = null,
            outlierMandate = null
        ).withComputedHash()
        return AgentBootContext(
            agentId = agentId,
            runId = runId,
            groupId = groupId.value,
            role = Role.Researcher,
            rolePrompt = "Appendix B regression worker.",
            budgetToken = "budget-token",
            substrateDbPath = "/tmp/substrate.db",
            stagingMountPath = "/staging",
            workerView = workerView,
            assignedLease = WorkerLeaseAssignment(
                leaseId = nodeLease.leaseId,
                nodeId = nodeLease.nodeId,
                expiresAt = nodeLease.expiresAt,
                replicationSlot = nodeLease.replicationSlot
            ),
            ttlPolicy = AgentTtlPolicy(
                lifetimeSeconds = 600,
                deadline = Instant.parse("2099-01-01T00:00:00Z")
            ),
            wipTaskClaim = WipTaskClaim(nodeId.value, "Appendix B exact node work"),
            legacyProtocol = null,
            stageDisclosure = "Stage-0 exact capture only.",
            modelAssignment = "test/model",
            ipcBaseUrl = "http://localhost",
            generation = round
        )
    }

    private fun migration(): PreparedAtlasMigration {
        val receiver = ReceiverContract(
            rawObjective = "Resolve the limitation with exact node evidence.",
            receiverId = "receiver",
            receiverDescription = "Operator",
            situation = "Decision",
            decisionOrUse = "Choose",
            whyNeeded = "Reduce uncertainty",
            successOutcomes = listOf("Verified"),
            unacceptableOutcomes = listOf("Unsupported"),
            constraints = listOf("Auditable"),
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
            receiverWhyRef = "verified",
            limitationStatement = "Evidence is missing.",
            capabilityTarget = "Establish exact evidence.",
            atomicQuestion = "What evidence resolves the limitation?",
            proofContract = ProofContract(
                method = ProofMethod.SOURCE_CORROBORATION,
                acceptanceCriteria = setOf("Exact evidence")
            ),
            deliverableContract = NodeDeliverableContract(
                acceptedArtifactTypes = setOf(ArtifactType.EVIDENCE_TABLE)
            ),
            revealPolicy = RevealPolicy("stage-0", RevealMode.INITIAL),
            replicationPolicy = ReplicationPolicy(
                mode = com.achulkov.evomolior.core.atlas.ReplicationMode.REQUIRED,
                requiredCount = 2
            ),
            capturePolicy = CapturePolicy(minimumGrade = 0.7),
            rewardSpec = RewardSpec(RewardClass.SMALL, 1.0)
        ).withComputedPayloadHash()
        val nodes = mapOf(nodeId to node)
        val stage = DisclosureStage(
            stageId = "stage-0",
            stageIndex = 0,
            title = "Initial",
            nodeIds = setOf(nodeId),
            mandatoryNodeIds = setOf(nodeId),
            evidenceCoverageThreshold = 0.8,
            integrationReadinessThreshold = 0.7,
            revealPayloadHash = AtlasHashing.hashDisclosurePayload("stage-0", setOf(nodeId), nodes)
        )
        val atlas = NodeAtlas(
            atlasId = atlasId,
            runId = runId,
            version = 1,
            receiverContractHash = AtlasHashing.hashReceiverContract(receiver),
            rootNodeIds = setOf(nodeId),
            nodes = nodes,
            stageDefinitions = listOf(stage)
        ).withComputedHash()
        val groups = listOf(groupA.value, groupB.value)
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
                deliverables = groups.map { group ->
                    DeliverableSkeletonTree(
                        runId = runId,
                        groupId = group,
                        rootNodeId = nodeId.value,
                        nodes = listOf(
                            DeliverableSkeletonNode(
                                nodeId = nodeId.value,
                                title = "Evidence"
                            )
                        ),
                        createdAtMs = 1,
                        updatedAtMs = 1
                    )
                },
                resources = ResourceProjection(
                    runId,
                    listOf(
                        ResourceProjectionNode(
                            "resource-1",
                            nodeId,
                            "Evidence",
                            "WEB_CACHE",
                            1.0
                        )
                    )
                ),
                disclosure = DisclosureProjection(
                    runId,
                    listOf(stage),
                    groups.associateWith { 0 }
                ),
                mappings = groups.map { group ->
                    LegacyNodeMapping(
                        LegacyNodeSource.DELIVERABLE,
                        nodeId.value,
                        group,
                        nodeId
                    )
                } + LegacyNodeMapping(
                    LegacyNodeSource.RESOURCE,
                    "resource-1",
                    runId,
                    nodeId
                )
            )
        )
    }

    private fun sealRequest(
        atlas: NodeAtlas,
        groupId: AtlasGroupId,
        submissionId: String
    ): SealNodeEvidenceRequest = SealNodeEvidenceRequest(
        runId = runId,
        round = round,
        groupId = groupId,
        nodeId = nodeId,
        atlasVersion = atlas.version,
        atlasHash = atlas.atlasHash,
        nodePayloadHash = atlas.nodes.getValue(nodeId).payloadHash,
        acceptedArtifactIds = listOf(submissionId)
    )

    private data class GroupHarness(
        val database: com.achulkov.evomolior.substrate.db.EvoMoliorDatabase,
        val bootContext: AgentBootContext,
        val ledger: com.achulkov.evomolior.core.budget.BudgetLedger,
        val nodeLease: NodeLease
    )
}
