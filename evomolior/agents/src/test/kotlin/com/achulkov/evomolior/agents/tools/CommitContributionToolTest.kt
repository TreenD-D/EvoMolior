@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.agents.tools

import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.atlas.AtlasAgentId
import com.achulkov.evomolior.core.atlas.ToolExecutionPurpose
import com.achulkov.evomolior.core.atlas.ToolExecutionRecord
import com.achulkov.evomolior.core.atlas.ToolExecutionResult
import com.achulkov.evomolior.core.atlas.ToolLifecycleEvent
import com.achulkov.evomolior.core.atlas.ToolLifecycleState
import com.achulkov.evomolior.core.atlas.ToolVersionId
import com.achulkov.evomolior.core.contract.AgentBootContext
import com.achulkov.evomolior.core.contract.AgentTtlPolicy
import com.achulkov.evomolior.core.contract.AtlasSkeletonView
import com.achulkov.evomolior.core.contract.WorkerLeaseAssignment
import com.achulkov.evomolior.core.contract.WorkerView
import com.achulkov.evomolior.core.model.Role
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import com.achulkov.evomolior.substrate.worker.WorkerExecutionRepository
import com.achulkov.evomolior.substrate.db.entity.ToolExecutionRecordEntity
import com.achulkov.evomolior.substrate.db.entity.ToolLifecycleEventEntity
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
    fun `reusable tool commit rejects preflight failure and accepts pending candidate`() = runTest {
        val database = SubstrateDatabaseFactory(
            createTempDirectory("commit-contribution-tool-authority-").toFile()
        ).get("run-commit", "group-commit")
        try {
            listOf(
                StagingSubmissionEntity(
                    id = "tool-rejected",
                    agentId = "agent-commit",
                    role = Role.Researcher.name,
                    type = "TOOL",
                    content = """{"name":"rejected"}""",
                    wipTaskRef = "node-assigned",
                    budgetSignature = "test",
                    status = "rejected",
                    rejectionReason = "tool_validation_unexpected_output",
                    generation = 1,
                    groupId = "group-commit",
                    runId = "run-commit",
                    submittedAtMs = 1L
                ),
                StagingSubmissionEntity(
                    id = "tool-pending",
                    agentId = "agent-commit",
                    role = Role.Researcher.name,
                    type = "TOOL",
                    content = """{"name":"pending"}""",
                    wipTaskRef = "node-assigned",
                    budgetSignature = "test",
                    status = "pending",
                    generation = 1,
                    groupId = "group-commit",
                    runId = "run-commit",
                    submittedAtMs = 2L
                )
            ).forEach { database.stagingDao().insert(it) }
            val tool = CommitContributionTool(
                WorkerExecutionRepository(database),
                context(Role.Researcher, assignedNodeId = NodeId("node-assigned")),
                now = { instant }
            )

            val rejected = tool.execute(
                CommitContributionTool.Args(
                    commitType = "REUSABLE_TOOL",
                    artifactIds = listOf("tool-rejected"),
                    changedStateSummary = "Authored a reusable tool.",
                    confidence = 0.8
                )
            )
            val accepted = tool.execute(
                CommitContributionTool.Args(
                    commitType = "REUSABLE_TOOL",
                    artifactIds = listOf("tool-pending"),
                    changedStateSummary = "Authored a reusable tool.",
                    confidence = 0.8
                )
            )

            assertTrue(rejected.startsWith("Error: Contribution commit rejected"))
            assertTrue(rejected.contains("tool_validation_unexpected_output"))
            assertTrue(accepted.startsWith("Contribution committed"))
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
                insertPromotedLifecycle(database, execution)
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

    @Test
    fun `reusable tool commit rejects caller supplied execution without authority`() = runTest {
        val database = SubstrateDatabaseFactory(
            createTempDirectory("commit-contribution-tool-run-rejection-").toFile()
        ).get("run-commit", "group-commit")
        try {
            val tool = CommitContributionTool(
                repository = WorkerExecutionRepository(database),
                bootContext = context(
                    Role.Researcher,
                    assignedNodeId = NodeId("node-assigned")
                ),
                now = { instant }
            )

            val result = tool.execute(
                CommitContributionTool.Args(
                    commitType = "REUSABLE_TOOL",
                    toolRunIds = listOf("missing-execution"),
                    changedStateSummary = "Claimed an unverified execution.",
                    confidence = 0.9
                )
            )

            assertTrue(result.startsWith("Error: Contribution commit rejected"))
            assertTrue(result.contains("unknown tool execution"))
        } finally {
            database.close()
        }
    }

    @Test
    fun `reusable tool commit rejects failed and cross scope execution records`() = runTest {
        val database = SubstrateDatabaseFactory(
            createTempDirectory("commit-contribution-tool-run-scope-").toFile()
        ).get("run-commit", "group-commit")
        try {
            val base = ToolExecutionRecord(
                executionId = "execution-base",
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
                outputArtifactId = "execution-output",
                startedAt = instant,
                completedAt = instant,
                durationMillis = 0,
                exitCode = 0,
                result = ToolExecutionResult.SUCCEEDED,
                sandboxRecordHash = "sandbox-hash"
            )
            val invalidExecutions = listOf(
                base.copy(
                    executionId = "execution-failed",
                    result = ToolExecutionResult.FAILED,
                    failure = com.achulkov.evomolior.core.atlas.ToolExecutionFailure.NON_ZERO_EXIT
                ).withComputedHash(),
                base.copy(
                    executionId = "execution-foreign-agent",
                    agentId = AtlasAgentId("agent-other")
                ).withComputedHash(),
                base.copy(
                    executionId = "execution-wrong-node",
                    nodeId = NodeId("node-other")
                ).withComputedHash(),
                base.copy(
                    executionId = "execution-wrong-round",
                    round = 0
                ).withComputedHash(),
                base.copy(
                    executionId = "execution-wrong-purpose",
                    purpose = ToolExecutionPurpose.PROMOTION_TEST
                ).withComputedHash()
            )
            invalidExecutions.forEach { execution ->
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
            }
            database.stagingDao().insert(
                StagingSubmissionEntity(
                    id = "tool-pending-with-run",
                    agentId = "agent-commit",
                    role = Role.Researcher.name,
                    type = "TOOL",
                    content = """{"name":"pending"}""",
                    wipTaskRef = "node-assigned",
                    budgetSignature = "test",
                    status = "pending",
                    generation = 1,
                    groupId = "group-commit",
                    runId = "run-commit",
                    submittedAtMs = 1L
                )
            )
            val tool = CommitContributionTool(
                repository = WorkerExecutionRepository(database),
                bootContext = context(
                    Role.Researcher,
                    assignedNodeId = NodeId("node-assigned")
                ),
                now = { instant }
            )

            invalidExecutions.forEach { execution ->
                val result = tool.execute(
                    CommitContributionTool.Args(
                        commitType = "REUSABLE_TOOL",
                        artifactIds = listOf("tool-pending-with-run"),
                        toolRunIds = listOf(execution.executionId),
                        changedStateSummary = "Claimed an invalid execution.",
                        confidence = 0.9
                    )
                )
                assertTrue(result.startsWith("Error: Contribution commit rejected"))
            }
        } finally {
            database.close()
        }
    }

    private suspend fun insertPromotedLifecycle(
        database: com.achulkov.evomolior.substrate.db.EvoMoliorDatabase,
        execution: ToolExecutionRecord
    ) {
        var previous: ToolLifecycleEvent? = null
        val states = listOf(
            ToolLifecycleState.TOOL_SPEC,
            ToolLifecycleState.IMPLEMENTED,
            ToolLifecycleState.STATIC_VALIDATED,
            ToolLifecycleState.SANDBOX_TESTED,
            ToolLifecycleState.CRITIC_ACCEPTED,
            ToolLifecycleState.PROMOTED,
            ToolLifecycleState.EXECUTED_BY_LATER_AGENT
        )
        states.forEach { state ->
            val event = ToolLifecycleEvent(
                eventId = "event-${state.name.lowercase()}",
                runId = execution.runId,
                opportunityId = execution.opportunityId,
                toolVersionId = execution.toolVersionId,
                groupId = execution.groupId,
                originNodeId = NodeId("node-origin"),
                contextNodeId = if (state == ToolLifecycleState.EXECUTED_BY_LATER_AGENT) {
                    execution.nodeId
                } else {
                    NodeId("node-origin")
                },
                state = state,
                sequence = (previous?.sequence ?: -1L) + 1,
                previousEventHash = previous?.eventHash,
                authorityReferenceIds = if (
                    state == ToolLifecycleState.EXECUTED_BY_LATER_AGENT
                ) {
                    setOf(execution.executionId)
                } else {
                    setOf("authority-${state.name.lowercase()}")
                },
                recordedAt = instant
            ).withComputedHash()
            database.toolInfrastructureDao().insertLifecycle(
                ToolLifecycleEventEntity(
                    eventId = event.eventId,
                    runId = event.runId,
                    opportunityId = event.opportunityId,
                    toolVersionId = event.toolVersionId.value,
                    groupId = event.groupId.value,
                    originNodeId = event.originNodeId.value,
                    contextNodeId = event.contextNodeId.value,
                    state = event.state.name,
                    sequence = event.sequence,
                    eventHash = event.eventHash,
                    contentJson = Json.encodeToString(event),
                    createdAtMs = 1L
                )
            )
            previous = event
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
