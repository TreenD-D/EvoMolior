@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.agents.tools

import com.achulkov.evomolior.core.atlas.ArtifactType
import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.CapturePolicy
import com.achulkov.evomolior.core.atlas.NodeDeliverableContract
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.atlas.NodeKind
import com.achulkov.evomolior.core.atlas.NodeStatus
import com.achulkov.evomolior.core.atlas.NoveltyType
import com.achulkov.evomolior.core.atlas.ProofContract
import com.achulkov.evomolior.core.atlas.ProofMethod
import com.achulkov.evomolior.core.atlas.ReplicationPolicy
import com.achulkov.evomolior.core.atlas.RewardClass
import com.achulkov.evomolior.core.atlas.RewardSpec
import com.achulkov.evomolior.core.contract.AgentBootContext
import com.achulkov.evomolior.core.contract.AgentTtlPolicy
import com.achulkov.evomolior.core.contract.AtlasSkeletonNodeView
import com.achulkov.evomolior.core.contract.AtlasSkeletonView
import com.achulkov.evomolior.core.contract.RevealedNodeCard
import com.achulkov.evomolior.core.contract.WipTaskClaim
import com.achulkov.evomolior.core.contract.WorkerLeaseAssignment
import com.achulkov.evomolior.core.contract.WorkerView
import com.achulkov.evomolior.core.contract.LegacyProtocolContext
import com.achulkov.evomolior.core.model.Role
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.outlier.OutlierNoveltyRepository
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class ProposeNoveltyVectorToolTest {
    private val visibleNodeId = NodeId("visible-node")

    @Test
    fun `valid Outlier proposal is committed atomically and exact retry is idempotent`() = runTest {
        val database = SubstrateDatabaseFactory(
            createTempDirectory("propose-novelty-").toFile()
        ).get("run-novelty", "group-novelty")
        try {
            val repository = OutlierNoveltyRepository(database, now = { 100L })
            val tool = ProposeNoveltyVectorTool(repository, context())
            val args = validArgs()

            val first = tool.execute(args)
            val second = tool.execute(args)

            assertEquals(first, second)
            assertTrue(first.startsWith("Novelty proposal committed"))
            val proposal = repository.proposalsForRound("run-novelty", 2).single()
            assertEquals(setOf(visibleNodeId), proposal.affectedVisibleNodeIds)
            assertEquals(1, repository.lifecycle(proposal.proposalId).size)
        } finally {
            database.close()
        }
    }

    @Test
    fun `tool rejects non-Outliers hidden nodes and contaminated Outlier contexts`() = runTest {
        val database = SubstrateDatabaseFactory(
            createTempDirectory("propose-novelty-invalid-").toFile()
        ).get("run-novelty", "group-novelty")
        try {
            val repository = OutlierNoveltyRepository(database)
            assertTrue(
                ProposeNoveltyVectorTool(repository, context(role = Role.Researcher))
                    .execute(validArgs())
                    .startsWith("Error: propose_novelty_vector is available only")
            )
            assertTrue(
                ProposeNoveltyVectorTool(repository, context())
                    .execute(validArgs().copy(affectedVisibleNodeIds = listOf("hidden-node")))
                    .startsWith("Error: Novelty must connect only")
            )
            assertTrue(
                ProposeNoveltyVectorTool(
                    repository,
                    context().copy(wipTaskClaim = WipTaskClaim("ordinary-node", "Forbidden"))
                ).execute(validArgs())
                    .startsWith("Error: Outlier novelty authority cannot coexist")
            )
            assertTrue(
                ProposeNoveltyVectorTool(
                    repository,
                    context().copy(
                        legacyProtocol = LegacyProtocolContext(
                            protocolId = "forbidden-legacy",
                            role = Role.Outlier,
                            extractedFromGeneration = 1,
                            expiresAtGeneration = 3,
                            reasoningPosture = "Forbidden",
                            toolHeuristics = "",
                            failureMap = "",
                            kgHabits = ""
                        )
                    )
                ).execute(validArgs())
                    .startsWith("Error: Outlier novelty authority cannot coexist")
            )
            assertTrue(
                ProposeNoveltyVectorTool(
                    repository,
                    context().copy(
                        assignedLease = WorkerLeaseAssignment(
                            leaseId = "lease",
                            nodeId = visibleNodeId,
                            expiresAt = Instant.parse("2026-07-12T01:00:00Z"),
                            replicationSlot = 0
                        )
                    )
                ).execute(validArgs())
                    .startsWith("Error: Outlier novelty authority cannot coexist")
            )
            assertTrue(repository.proposalsForRound("run-novelty", 2).isEmpty())
        } finally {
            database.close()
        }
    }

    private fun validArgs() = ProposeNoveltyVectorTool.Args(
        noveltyType = NoveltyType.MISSING_INTEGRATION_DEPENDENCY,
        affectedVisibleNodeIds = listOf(visibleNodeId.value),
        missingLayerOrAssumption = "Distributed repair cost is not represented.",
        proposedVector = "Measure repairability under independent maintenance.",
        whyCurrentMapMayMissIt = "The visible map evaluates initial construction only.",
        falsifiablePrediction = "Modular repair reduces median correction cost by 30 percent.",
        minimalTestOrArtifact = "Run a blinded repair task on modular and coupled variants.",
        expectedReceiverImpact = "Improves long-term receiver reliability.",
        expectedCost = 4.0
    )

    private fun context(role: Role = Role.Outlier): AgentBootContext {
        val card = RevealedNodeCard(
            nodeId = visibleNodeId,
            kind = NodeKind.INTEGRATION,
            receiverWhyRef = "visible receiver relationship",
            limitationStatement = "Integrate independently produced components.",
            capabilityTarget = "A coherent final artifact.",
            atomicQuestion = "Do all components compose?",
            proofContract = ProofContract(
                method = ProofMethod.RECEIVER_VALIDATION,
                acceptanceCriteria = setOf("Composition succeeds")
            ),
            deliverableContract = NodeDeliverableContract(
                acceptedArtifactTypes = setOf(ArtifactType.DELIVERABLE_FRAGMENT)
            ),
            replicationPolicy = ReplicationPolicy(),
            capturePolicy = CapturePolicy(minimumGrade = 0.7),
            rewardSpec = RewardSpec(RewardClass.MEDIUM, 2.0),
            toolOpportunity = null,
            payloadHash = "visible-payload"
        )
        val skeletonNode = AtlasSkeletonNodeView(
            nodeId = visibleNodeId,
            kind = NodeKind.INTEGRATION,
            layer = 0,
            parentIds = emptySet(),
            prerequisiteIds = emptySet(),
            alternativeToIds = emptySet(),
            stageId = "stage-0",
            payloadHash = card.payloadHash,
            status = NodeStatus.REVEALED,
            congestionLevel = 0
        )
        val view = WorkerView(
            runId = "run-novelty",
            groupId = AtlasGroupId("group-novelty"),
            round = 2,
            stageId = "stage-0",
            visibleAtlasSkeleton = AtlasSkeletonView(
                atlasId = "atlas-novelty",
                atlasVersion = 1,
                atlasHash = "atlas-hash",
                rootNodeIds = setOf(visibleNodeId),
                nodes = mapOf(visibleNodeId to skeletonNode)
            ),
            revealedNodes = listOf(card),
            assignedNode = null,
            groupFeedback = null,
            outlierMandate = "Introduce one connected, falsifiable novelty vector."
        ).withComputedHash()
        return AgentBootContext(
            agentId = "outlier-1",
            runId = "run-novelty",
            groupId = "group-novelty",
            role = role,
            rolePrompt = "Find novelty.",
            budgetToken = "budget",
            substrateDbPath = "/tmp/substrate.db",
            stagingMountPath = "/tmp/staging",
            workerView = view,
            assignedLease = null,
            ttlPolicy = AgentTtlPolicy(
                lifetimeSeconds = 600,
                deadline = Instant.parse("2026-07-12T01:10:00Z")
            ),
            wipTaskClaim = null,
            legacyProtocol = null,
            stageDisclosure = "Redacted WorkerView only.",
            modelAssignment = "test/model",
            ipcBaseUrl = "http://localhost",
            generation = 2
        )
    }
}
