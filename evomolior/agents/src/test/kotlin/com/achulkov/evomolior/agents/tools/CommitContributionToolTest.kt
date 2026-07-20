@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.agents.tools

import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.atlas.AtlasAgentId
import com.achulkov.evomolior.core.atlas.ToolExecutionPurpose
import com.achulkov.evomolior.core.atlas.ToolExecutionRecord
import com.achulkov.evomolior.core.atlas.ToolExecutionResult
import com.achulkov.evomolior.core.atlas.ToolVersionId
import com.achulkov.evomolior.core.contract.AgentBootContext
import com.achulkov.evomolior.core.contract.AgentTtlPolicy
import com.achulkov.evomolior.core.contract.AtlasSkeletonView
import com.achulkov.evomolior.core.contract.WorkerLeaseAssignment
import com.achulkov.evomolior.core.contract.WorkerView
import com.achulkov.evomolior.core.model.Role
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.worker.WorkerExecutionRepository
import com.achulkov.evomolior.substrate.db.entity.ToolExecutionRecordEntity
import com.achulkov.evomolior.substrate.tools.ToolInfrastructureRepository
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class CommitContributionToolTest {
    private val instant = Instant.parse("2026-07-11T10:00:00Z")

    @Test
    fun `node-bound contribution is persisted idempotently and cannot claim another node`() = runTest {
        val database = SubstrateDatabaseFactory(
            createTempDirectory("commit-contribution-tool-").toFile()
        ).get("run-commit", "group-commit")
        try {
            val context = context(Role.Researcher, assignedNodeId = NodeId("node-assigned"))
            val repository = WorkerExecutionRepository(database)
            val tool = CommitContributionTool(repository, context, now = { instant })
            val args = CommitContributionTool.Args(
                commitType = "POSITIVE_RESULT",
                nodeId = "node-assigned",
                artifactIds = listOf("artifact-1"),
                changedStateSummary = "Produced the assigned evidence artifact.",
                evidenceSummary = "artifact-1 contains the evidence.",
                confidence = 0.8
            )

            val first = tool.execute(args)
            val second = tool.execute(args)

            assertEquals(first, second)
            assertTrue(first.startsWith("Contribution committed"))
            assertEquals(
                1,
                repository.contributionsForAgent("run-commit", "group-commit", "agent-commit").size
            )
            assertTrue(
                tool.execute(args.copy(nodeId = "node-other"))
                    .startsWith("Error: Contribution node must match")
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun `outlier can commit novelty or no contribution but not ordinary positive work`() = runTest {
        val database = SubstrateDatabaseFactory(
            createTempDirectory("commit-contribution-outlier-").toFile()
        ).get("run-commit", "group-commit")
        try {
            val tool = CommitContributionTool(
                WorkerExecutionRepository(database),
                context(Role.Outlier, assignedNodeId = null),
                now = { instant }
            )

            assertTrue(
                tool.execute(
                    CommitContributionTool.Args(
                        commitType = "NOVELTY_PROPOSAL",
                        artifactIds = listOf("novelty-1"),
                        changedStateSummary = "Proposed a visible-frontier novelty.",
                        confidence = 0.5
                    )
                ).startsWith("Contribution committed")
            )
            assertTrue(
                tool.execute(
                    CommitContributionTool.Args(
                        commitType = "POSITIVE_RESULT",
                        artifactIds = listOf("forbidden"),
                        changedStateSummary = "Forbidden ordinary result.",
                        confidence = 0.5
                    )
                ).startsWith("Error: Outliers may commit only")
            )
            assertTrue(
                tool.execute(
                    CommitContributionTool.Args(
                        commitType = "NO_CONTRIBUTION",
                        artifactIds = listOf("invalid-reference"),
                        changedStateSummary = "No contribution.",
                        confidence = 0.0
                    )
                ).startsWith("Error: Contribution commit rejected")
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun `successful persisted node executions are bound into contribution automatically`() =
        runTest {
            val database = SubstrateDatabaseFactory(
                createTempDirectory("commit-contribution-tool-run-").toFile()
            ).get("run-commit", "group-commit")
            try {
                val execution = ToolExecutionRecord(
                    executionId = "execution-automatic",
                    runId = "run-commit",
                    toolVersionId = ToolVersionId("tool-v1"),
                    opportunityId = "opportunity-1",
                    groupId = AtlasGroupId("group-commit"),
                    agentId = AtlasAgentId("agent-commit"),
                    round = 1,
                    nodeId = NodeId("node-assigned"),
                    purpose = ToolExecutionPurpose.AGENT_USE,
                    inputHash = "input-hash",
                    outputHash = "output-hash",
                    outputJson = kotlinx.serialization.json.JsonPrimitive("output"),
                    outputArtifactId = "execution-output:execution-automatic",
                    startedAt = instant,
                    completedAt = instant,
                    durationMillis = 0,
                    exitCode = 0,
                    result = ToolExecutionResult.SUCCEEDED,
                    sandboxRecordHash = "sandbox-hash"
                ).withComputedHash()
                database.toolInfrastructureDao().insertExecution(
                    ToolExecutionRecordEntity(
                        executionId = execution.executionId,
                        runId = execution.runId,
                        opportunityId = execution.opportunityId,
                        toolVersionId = execution.toolVersionId.value,
                        groupId = execution.groupId.value,
                        agentId = execution.agentId.value,
                        round = execution.round,
                        nodeId = execution.nodeId.value,
                        purpose = execution.purpose.name,
                        result = execution.result.name,
                        recordHash = execution.recordHash,
                        contentJson = Json.encodeToString(execution),
                        createdAtMs = 1L
                    )
                )
                val repository = WorkerExecutionRepository(database)
                val tool = CommitContributionTool(
                    repository = repository,
                    bootContext = context(
                        Role.Researcher,
                        assignedNodeId = NodeId("node-assigned")
                    ),
                    now = { instant },
                    toolInfrastructureRepository = ToolInfrastructureRepository(database)
                )

                assertTrue(
                    tool.execute(
                        CommitContributionTool.Args(
                            commitType = "REUSABLE_TOOL",
                            changedStateSummary = "Reused exact promoted infrastructure.",
                            evidenceSummary = "The persisted execution produced the result.",
                            confidence = 0.9
                        )
                    ).startsWith("Contribution committed")
                )

                assertEquals(
                    listOf("execution-automatic"),
                    repository.contributionsForAgent(
                        "run-commit",
                        "group-commit",
                        "agent-commit"
                    ).single().toolRunIds
                )
            } finally {
                database.close()
            }
        }

    private fun context(role: Role, assignedNodeId: NodeId?): AgentBootContext {
        val view = WorkerView(
            runId = "run-commit",
            groupId = AtlasGroupId("group-commit"),
            round = 1,
            stageId = "stage-0",
            visibleAtlasSkeleton = AtlasSkeletonView(
                atlasId = "atlas-commit",
                atlasVersion = 1,
                atlasHash = "atlas-hash",
                rootNodeIds = if (assignedNodeId == null) emptySet() else setOf(assignedNodeId),
                nodes = emptyMap<NodeId, com.achulkov.evomolior.core.contract.AtlasSkeletonNodeView>()
            ),
            revealedNodes = emptyList(),
            assignedNode = null,
            groupFeedback = null,
            outlierMandate = if (role == Role.Outlier) "Find novelty." else null
        ).withComputedHash()
        return AgentBootContext(
            agentId = "agent-commit",
            runId = "run-commit",
            groupId = "group-commit",
            role = role,
            rolePrompt = "Commit durable work.",
            budgetToken = "token",
            substrateDbPath = "/tmp/substrate.db",
            stagingMountPath = "/staging",
            workerView = view,
            assignedLease = assignedNodeId?.let {
                WorkerLeaseAssignment(
                    leaseId = "lease-assigned",
                    nodeId = it,
                    expiresAt = Instant.parse("2026-07-11T10:10:00Z"),
                    replicationSlot = 0
                )
            },
            ttlPolicy = AgentTtlPolicy(600, Instant.parse("2026-07-11T10:10:00Z")),
            wipTaskClaim = assignedNodeId?.let {
                com.achulkov.evomolior.core.contract.WipTaskClaim(it.value, "Assigned node")
            },
            legacyProtocol = null,
            stageDisclosure = "Redacted view only.",
            modelAssignment = "test/model",
            ipcBaseUrl = "http://localhost",
            generation = 1
        )
    }
}
