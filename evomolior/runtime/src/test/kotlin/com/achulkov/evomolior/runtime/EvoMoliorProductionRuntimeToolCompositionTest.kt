@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.runtime

import com.achulkov.evomolior.agents.factory.TaskToolCapabilityPolicy
import com.achulkov.evomolior.agents.tools.ExecuteToolTool
import com.achulkov.evomolior.core.atlas.ArtifactType
import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.CapturePolicy
import com.achulkov.evomolior.core.atlas.NodeDeliverableContract
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.atlas.NodeKind
import com.achulkov.evomolior.core.atlas.ProofContract
import com.achulkov.evomolior.core.atlas.ProofMethod
import com.achulkov.evomolior.core.atlas.ReplicationPolicy
import com.achulkov.evomolior.core.atlas.RewardClass
import com.achulkov.evomolior.core.atlas.RewardSpec
import com.achulkov.evomolior.core.atlas.ToolBehavior
import com.achulkov.evomolior.core.atlas.ToolExecutionPurpose
import com.achulkov.evomolior.core.atlas.ToolExecutionResult
import com.achulkov.evomolior.core.atlas.ToolImplementationMode
import com.achulkov.evomolior.core.atlas.ToolLifecycleState
import com.achulkov.evomolior.core.atlas.ToolOpportunity
import com.achulkov.evomolior.core.atlas.ToolOpportunityCommitment
import com.achulkov.evomolior.core.atlas.ToolOpportunityType
import com.achulkov.evomolior.core.atlas.ToolVersionId
import com.achulkov.evomolior.core.atlas.TypedField
import com.achulkov.evomolior.core.atlas.TypedFieldType
import com.achulkov.evomolior.core.atlas.TypedToolSchemaCompiler
import com.achulkov.evomolior.core.budget.BudgetConfig
import com.achulkov.evomolior.core.config.LlmBackend
import com.achulkov.evomolior.core.contract.AgentBootContext
import com.achulkov.evomolior.core.contract.AgentTtlPolicy
import com.achulkov.evomolior.core.contract.AtlasSkeletonView
import com.achulkov.evomolior.core.contract.RevealedNodeCard
import com.achulkov.evomolior.core.contract.WipTaskClaim
import com.achulkov.evomolior.core.contract.WorkerLeaseAssignment
import com.achulkov.evomolior.core.contract.WorkerToolCard
import com.achulkov.evomolior.core.contract.WorkerView
import com.achulkov.evomolior.core.model.GroupProfile
import com.achulkov.evomolior.core.model.GroupProfileType
import com.achulkov.evomolior.core.model.Role
import com.achulkov.evomolior.orchestration.lifecycle.ToolPromotionResult
import com.achulkov.evomolior.orchestration.lifecycle.ToolPromotionService
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.cost.CostLedgerRepository
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import com.achulkov.evomolior.substrate.tools.ToolInfrastructureRepository
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive

class EvoMoliorProductionRuntimeToolCompositionTest {

    @Test
    fun `ordinary in-process factory executes a promoted tool from the group host directory`() =
        runBlocking {
            val dataRoot = createTempDirectory("production-tool-composition-").toFile()
            val substrateFactory = SubstrateDatabaseFactory(dataRoot)
            val runId = "run-production-tool-composition"
            val groupId = "group-0"
            val originNodeId = NodeId("node-tool-origin")
            val laterNodeId = NodeId("node-tool-reuse")
            val database = substrateFactory.get(runId, groupId)
            val toolsRoot = substrateFactory.dbFilePath(runId, groupId).parentFile
                .resolve("tools")
            val commitment = toolCommitment(runId, originNodeId, laterNodeId)
            val promotionService = ToolPromotionService(
                database = database,
                runId = runId,
                groupId = groupId,
                toolsMountHostPath = toolsRoot.absolutePath,
                opportunityResolver = { commitment }
            )

            try {
                assertEquals(
                    ToolPromotionResult.Promoted("normalize-v1"),
                    promotionService.promote(
                        validToolSubmission(runId, groupId, originNodeId, commitment),
                        criticAgentId = "critic-1"
                    )
                )
                assertTrue(toolsRoot.resolve("normalize-v1").canExecute())

                val profile = GroupProfile.default(GroupProfileType.Factory)
                val agentFactory = createProductionInProcessAgentFactory(
                    openRouterApiKey = "sk-test",
                    llmBackend = LlmBackend.OPENROUTER,
                    database = database,
                    workspaceDir = File(dataRoot, "workspace/$runId/$groupId"),
                    budgetConfig = BudgetConfig(groupId, profile),
                    toolsMountHostPath = toolsRoot.absolutePath,
                    costLedgerRepository = CostLedgerRepository(database),
                    taskToolCapabilityPolicy = TaskToolCapabilityPolicy.unrestricted,
                    toolSubmissionPreflight = { null }
                )
                val context = agentFactory.create(
                    workerBootContext(runId, groupId, laterNodeId, commitment)
                )
                val executeTool = tools(context.agent)
                    .single { it.javaClass.simpleName == "ExecuteToolTool" }
                    as ExecuteToolTool

                val response = executeTool.execute(
                    ExecuteToolTool.Args(
                        toolName = "normalize",
                        toolVersion = 1,
                        argsJson = """{"value":1}"""
                    )
                )

                assertContains(response, "Status: SUCCESS")
                assertContains(response, """{"normalized":1}""")
                val repository = ToolInfrastructureRepository(database)
                val laterExecution = repository.executions(ToolVersionId("normalize-v1"))
                    .single { it.purpose == ToolExecutionPurpose.AGENT_USE }
                assertEquals(ToolExecutionResult.SUCCEEDED, laterExecution.result)
                assertEquals(laterNodeId, laterExecution.nodeId)
                assertEquals(
                    ToolLifecycleState.EXECUTED_BY_LATER_AGENT,
                    repository.lifecycle(ToolVersionId("normalize-v1")).last().state
                )
            } finally {
                database.close()
            }
        }

    private fun toolCommitment(
        runId: String,
        originNodeId: NodeId,
        laterNodeId: NodeId
    ): ToolOpportunityCommitment = ToolOpportunityCommitment(
        runId = runId,
        atlasHash = "atlas-hash",
        nodeId = originNodeId,
        opportunity = ToolOpportunity(
            opportunityId = "opportunity-normalize",
            opportunityType = ToolOpportunityType.TRANSFORMATION,
            repeatedQuestionPattern = "Normalize each bounded numeric value.",
            requiredInputs = listOf(
                TypedField("value", TypedFieldType.NUMBER, "Input numeric value.")
            ),
            requiredOutputs = listOf(
                TypedField("normalized", TypedFieldType.NUMBER, "Normalized value.")
            ),
            validationStrategy = "Run the exact identity boundary case.",
            expectedReuseNodeIds = setOf(laterNodeId),
            preferredImplementationMode = ToolImplementationMode.SHELL,
            behavior = ToolBehavior.DETERMINISTIC,
            requiredCapabilities = setOf("shell"),
            promotionTestIds = setOf("identity-test"),
            maximumRuntimeMillis = 1_000,
            maximumOutputBytes = 4_096,
            assumptions = listOf("Input is finite."),
            knownInvalidDomains = listOf("Non-numeric values.")
        )
    ).withComputedHash()

    private fun validToolSubmission(
        runId: String,
        groupId: String,
        originNodeId: NodeId,
        commitment: ToolOpportunityCommitment
    ): StagingSubmissionEntity {
        val inputSchema = TypedToolSchemaCompiler.compile(
            commitment.opportunity.requiredInputs
        )
        val outputSchema = TypedToolSchemaCompiler.compile(
            commitment.opportunity.requiredOutputs
        )
        val code = JsonPrimitive(
            "cat >/dev/null\necho '{\"normalized\":1}'"
        )
        return StagingSubmissionEntity(
            id = "submission-tool",
            runId = runId,
            groupId = groupId,
            agentId = "builder-1",
            role = Role.Builder.name,
            type = "TOOL",
            content = """
                {
                  "opportunityId": "${commitment.opportunity.opportunityId}",
                  "name": "normalize",
                  "domain": "calculation",
                  "language": "shell",
                  "inputSchema": $inputSchema,
                  "outputSchema": $outputSchema,
                  "code": $code,
                  "tests": [
                    {
                      "testId": "identity-test",
                      "input": {"value": 1},
                      "expectedOutput": {"normalized": 1}
                    }
                  ]
                }
            """.trimIndent(),
            wipTaskRef = originNodeId.value,
            budgetSignature = "signature",
            generation = 1,
            submittedAtMs = 1L
        )
    }

    private fun workerBootContext(
        runId: String,
        groupId: String,
        nodeId: NodeId,
        commitment: ToolOpportunityCommitment
    ): AgentBootContext {
        val assignedNode = RevealedNodeCard(
            nodeId = nodeId,
            kind = NodeKind.KNOWLEDGE,
            receiverWhyRef = "receiver obligation",
            limitationStatement = "Repeated calculations are not yet executable.",
            capabilityTarget = "Execute the promoted calculation.",
            atomicQuestion = "What does the promoted normalization tool return?",
            proofContract = ProofContract(
                method = ProofMethod.EXECUTABLE_TEST,
                acceptanceCriteria = setOf("The promoted tool executes successfully.")
            ),
            deliverableContract = NodeDeliverableContract(
                acceptedArtifactTypes = setOf(ArtifactType.CLAIM_SET)
            ),
            replicationPolicy = ReplicationPolicy(),
            capturePolicy = CapturePolicy(minimumGrade = 0.7),
            rewardSpec = RewardSpec(RewardClass.MEDIUM, 2.0),
            toolOpportunity = null,
            payloadHash = "later-node-payload"
        )
        val workerView = WorkerView(
            runId = runId,
            groupId = AtlasGroupId(groupId),
            round = 2,
            stageId = "stage-1",
            visibleAtlasSkeleton = AtlasSkeletonView(
                atlasId = "atlas-production-composition",
                atlasVersion = 1,
                atlasHash = "atlas-hash",
                rootNodeIds = setOf(nodeId),
                nodes = emptyMap()
            ),
            revealedNodes = listOf(assignedNode),
            assignedNode = assignedNode,
            groupFeedback = null,
            availableToolCards = listOf(
                WorkerToolCard(
                    toolId = "normalize-v1",
                    name = "normalize",
                    version = 1,
                    opportunityId = commitment.opportunity.opportunityId,
                    opportunityCommitmentHash = commitment.commitmentHash,
                    requiredForNodeId = nodeId,
                    description = commitment.opportunity.repeatedQuestionPattern,
                    inputSchemaHash = AtlasHashing.hashText(
                        TypedToolSchemaCompiler.compile(
                            commitment.opportunity.requiredInputs
                        )
                    ),
                    outputSchemaHash = AtlasHashing.hashText(
                        TypedToolSchemaCompiler.compile(
                            commitment.opportunity.requiredOutputs
                        )
                    )
                )
            )
        ).withComputedHash()
        return AgentBootContext(
            agentId = "agent-later",
            runId = runId,
            groupId = groupId,
            role = Role.Researcher,
            rolePrompt = "Execute the promoted tool.",
            budgetToken = "budget-token",
            substrateDbPath = "/unused/group.db",
            stagingMountPath = "/unused/staging",
            workerView = workerView,
            assignedLease = WorkerLeaseAssignment(
                leaseId = "lease-later",
                nodeId = nodeId,
                expiresAt = Instant.parse("2026-01-01T00:05:00Z"),
                replicationSlot = 0
            ),
            ttlPolicy = AgentTtlPolicy(
                lifetimeSeconds = 600,
                deadline = Instant.parse("2026-01-01T00:10:00Z")
            ),
            wipTaskClaim = WipTaskClaim(nodeId.value, assignedNode.atomicQuestion),
            legacyProtocol = null,
            stageDisclosure = "Execute the already promoted reusable tool.",
            modelAssignment = "openai/gpt-4o-mini",
            ipcBaseUrl = "http://localhost",
            generation = 2,
            allocatedBudget = 100.0
        ).requireValidWorkerAuthority()
    }

    private fun tools(agent: Any): List<Any> {
        val registry = agent.javaClass.getMethod("getToolRegistry").invoke(agent)
        val values = registry.javaClass.methods.first { it.name == "getTools" }
            .invoke(registry) as List<*>
        return values.filterNotNull()
    }
}
