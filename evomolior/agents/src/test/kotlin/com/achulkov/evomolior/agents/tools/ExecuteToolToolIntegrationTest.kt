@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.agents.tools

import com.achulkov.evomolior.core.atlas.AtlasAgentId
import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.atlas.SimulationRecordBindings
import com.achulkov.evomolior.core.atlas.ToolBehavior
import com.achulkov.evomolior.core.atlas.ToolExecutionPurpose
import com.achulkov.evomolior.core.atlas.ToolExecutionRecord
import com.achulkov.evomolior.core.atlas.ToolExecutionResult
import com.achulkov.evomolior.core.atlas.ToolImplementationMode
import com.achulkov.evomolior.core.atlas.ToolLifecycleEvent
import com.achulkov.evomolior.core.atlas.ToolLifecycleState
import com.achulkov.evomolior.core.atlas.ToolOpportunity
import com.achulkov.evomolior.core.atlas.ToolOpportunityCommitment
import com.achulkov.evomolior.core.atlas.ToolOpportunityType
import com.achulkov.evomolior.core.atlas.ToolValidationExecutionAuthority
import com.achulkov.evomolior.core.atlas.ToolVersionId
import com.achulkov.evomolior.core.atlas.TypedField
import com.achulkov.evomolior.core.atlas.TypedFieldType
import com.achulkov.evomolior.core.atlas.TypedToolSchemaCompiler
import com.achulkov.evomolior.core.budget.BudgetConfig
import com.achulkov.evomolior.core.budget.BudgetLedger
import com.achulkov.evomolior.core.contract.CostLedgerEntryKind
import com.achulkov.evomolior.core.contract.CostSubjectType
import com.achulkov.evomolior.core.contract.MonetaryAmountStatus
import com.achulkov.evomolior.core.contract.WorkerToolCard
import com.achulkov.evomolior.core.model.ActionType
import com.achulkov.evomolior.core.model.GroupProfile
import com.achulkov.evomolior.core.model.GroupProfileType
import com.achulkov.evomolior.sandbox.toolsandbox.ToolSubSandbox
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.cost.CostLedgerRepository
import com.achulkov.evomolior.substrate.db.entity.ToolRegistryEntryEntity
import com.achulkov.evomolior.substrate.tools.ToolMaterializer
import com.achulkov.evomolior.substrate.tools.ToolInfrastructureRepository
import kotlinx.coroutines.test.runTest
import kotlin.time.Instant
import kotlin.io.path.createTempDirectory
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end regression for tool reuse across registry, materialisation, and execution.
 */
class ExecuteToolToolIntegrationTest {

    @Test
    fun `latest active materialized tool is executed end to end`() = runTest {
        val dataRoot = createTempDirectory("execute-tool-integration-test").toFile()
        val factory = SubstrateDatabaseFactory(dataRoot)
        val runId = "run-tool-reuse"
        val groupId = "group-0"
        val db = factory.get(runId, groupId)
        val toolsRoot = factory.dbFilePath(runId, groupId).parentFile!!.resolve("tools")

        val toolV1 = ToolRegistryEntryEntity(
            id = "demo-tool-v1",
            name = "demo-tool",
            version = 1,
            authorAgentId = "agent-1",
            inputSchemaJson = "{}",
            outputSchemaJson = "{}",
            domain = "general",
            code = "echo version-1",
            codeLanguage = "shell",
            generation = 1,
            groupId = groupId,
            runId = runId,
            createdAtMs = 1_000L
        )
        val toolV2 = toolV1.copy(
            id = "demo-tool-v2",
            version = 2,
            code = "echo version-2",
            createdAtMs = 2_000L,
            previousVersionId = toolV1.id
        )

        val materializer = ToolMaterializer(toolsRoot)
        materializer.materialize(toolV1)
        materializer.materialize(toolV2)
        db.toolRegistryDao().insert(toolV1)
        db.toolRegistryDao().insert(toolV2)

        val resolved = db.toolRegistryDao().getLatestByName(runId, groupId, "demo-tool")
        assertNotNull(resolved)
        assertEquals(2, resolved.version)
        assertTrue(
            toolsRoot.resolve("demo-tool-v2").canExecute(),
            "Materialized tool executable should exist and be executable"
        )

        val ledger = BudgetLedger(
            agentId = "agent-test",
            initialBudget = 500.0,
            floor = 1.0,
            config = BudgetConfig(
                groupId = groupId,
                profile = GroupProfile.default(GroupProfileType.Factory)
            )
        )

        val tool = ExecuteToolTool(
            toolRegistryDao = db.toolRegistryDao(),
            toolSubSandbox = ToolSubSandbox(createTempDirectory(prefix = "workspace-").toFile()),
            budgetLedger = ledger,
            runId = runId,
            groupId = groupId,
            toolsMountPath = toolsRoot.absolutePath
        )

        val result = tool.execute(ExecuteToolTool.Args(toolName = "demo-tool", argsJson = "{}"))

        assertContains(result, "version-2", ignoreCase = true)
        assertContains(result, "SUCCESS")
        assertTrue(
            ledger.balance < 500.0,
            "Successful execution should deduct budget from the ledger"
        )
        assertTrue(
            ledger.balance <= 500.0 - BudgetConfig(groupId = groupId, profile = GroupProfile.default(GroupProfileType.Factory))
                .costFor(ActionType.TOOL_EXEC_SUCCESS) + 0.0001,
            "Success cost should be applied for end-to-end tool execution"
        )
    }

    @Test
    fun `successful stochastic simulation emits exact immutable run record`() = runTest {
        val dataRoot = createTempDirectory("execute-simulation-integration-test").toFile()
        val factory = SubstrateDatabaseFactory(dataRoot)
        val runId = "run-simulation"
        val groupId = "group-simulation"
        val agentId = "agent-simulator"
        val originNodeId = NodeId("node-origin")
        val validationNodeId = NodeId("node-validation")
        val executionNodeId = NodeId("node-later")
        val db = factory.get(runId, groupId)
        val toolsRoot = factory.dbFilePath(runId, groupId).parentFile!!.resolve("tools")
        val opportunity = ToolOpportunity(
            opportunityId = "simulation-opportunity",
            opportunityType = ToolOpportunityType.SIMULATION,
            repeatedQuestionPattern = "Run the same bounded scenario for each candidate.",
            requiredInputs = listOf(
                TypedField("iterations", TypedFieldType.INTEGER, "Iteration count."),
                TypedField("randomSeed", TypedFieldType.INTEGER, "Explicit random seed.")
            ),
            requiredOutputs = listOf(
                TypedField("samples", TypedFieldType.INTEGER, "Samples produced."),
                TypedField("summary", TypedFieldType.STRING, "Bounded result summary."),
                TypedField("uncertainty", TypedFieldType.STRING, "Explicit uncertainty.")
            ),
            validationStrategy = "Run exact committed seed cases.",
            expectedReuseNodeIds = setOf(executionNodeId),
            preferredImplementationMode = ToolImplementationMode.SHELL,
            behavior = ToolBehavior.STOCHASTIC,
            requiredCapabilities = setOf("shell"),
            promotionTestIds = setOf("seed-test"),
            maximumRuntimeMillis = 2_000,
            maximumOutputBytes = 8_192,
            assumptions = listOf("Samples are independent."),
            knownInvalidDomains = listOf("Negative iteration counts."),
            simulationRecordBindings = SimulationRecordBindings(
                resultSummaryOutputField = "summary",
                uncertaintyOutputField = "uncertainty",
                randomSeedInputField = "randomSeed"
            )
        )
        val commitment = ToolOpportunityCommitment(
            runId = runId,
            atlasHash = "atlas-hash",
            nodeId = originNodeId,
            opportunity = opportunity
        ).withComputedHash()
        val versionId = ToolVersionId("simulator-v1")
        val repository = ToolInfrastructureRepository(
            database = db,
            validationExecutionAuthorities = setOf(
                ToolValidationExecutionAuthority(
                    runId = runId,
                    groupId = AtlasGroupId(groupId),
                    toolVersionId = versionId,
                    opportunityId = opportunity.opportunityId,
                    opportunityCommitmentHash = commitment.commitmentHash,
                    validationNodeId = validationNodeId
                )
            ),
            now = { 1_000L }
        )
        repository.appendOpportunity(commitment)

        var previous: ToolLifecycleEvent? = null
        suspend fun appendLifecycle(
            state: ToolLifecycleState,
            references: Set<String>
        ) {
            val event = ToolLifecycleEvent(
                eventId = "event-${state.name}",
                runId = runId,
                opportunityId = opportunity.opportunityId,
                toolVersionId = versionId,
                groupId = AtlasGroupId(groupId),
                originNodeId = originNodeId,
                contextNodeId = originNodeId,
                state = state,
                sequence = (previous?.sequence ?: -1L) + 1L,
                previousEventHash = previous?.eventHash,
                authorityReferenceIds = references,
                recordedAt = Instant.fromEpochMilliseconds(1_000L)
            ).withComputedHash()
            repository.appendLifecycle(event)
            previous = event
        }
        appendLifecycle(ToolLifecycleState.TOOL_SPEC, setOf(commitment.commitmentHash))
        appendLifecycle(ToolLifecycleState.IMPLEMENTED, setOf("implementation-hash"))
        appendLifecycle(ToolLifecycleState.STATIC_VALIDATED, setOf("static-validation"))
        val promotionExecution = ToolExecutionRecord(
            executionId = "promotion-execution",
            runId = runId,
            toolVersionId = versionId,
            opportunityId = opportunity.opportunityId,
            groupId = AtlasGroupId(groupId),
            agentId = AtlasAgentId("builder"),
            round = 0,
            nodeId = originNodeId,
            purpose = ToolExecutionPurpose.PROMOTION_TEST,
            inputHash = "promotion-input-hash",
            outputHash = "promotion-output-hash",
            outputJson = JsonPrimitive("passed"),
            outputArtifactId = "promotion-output",
            startedAt = Instant.fromEpochMilliseconds(1_000L),
            completedAt = Instant.fromEpochMilliseconds(1_001L),
            durationMillis = 1,
            exitCode = 0,
            result = ToolExecutionResult.SUCCEEDED,
            sandboxRecordHash = "promotion-sandbox-hash"
        ).withComputedHash()
        repository.appendExecution(promotionExecution)
        appendLifecycle(ToolLifecycleState.SANDBOX_TESTED, setOf(promotionExecution.executionId))
        appendLifecycle(ToolLifecycleState.CRITIC_ACCEPTED, setOf("critic-review"))
        appendLifecycle(ToolLifecycleState.PROMOTED, setOf(versionId.value))

        val registryEntry = ToolRegistryEntryEntity(
            id = versionId.value,
            name = "simulator",
            version = 1,
            authorAgentId = "builder",
            inputSchemaJson = TypedToolSchemaCompiler.compile(opportunity.requiredInputs).toString(),
            outputSchemaJson = TypedToolSchemaCompiler.compile(opportunity.requiredOutputs).toString(),
            domain = "simulation",
            code = "cat >/dev/null\necho '{\"samples\":10,\"summary\":\"stable range\",\"uncertainty\":\"95% interval\"}'",
            codeLanguage = "shell",
            generation = 1,
            groupId = groupId,
            runId = runId,
            createdAtMs = 1_000L
        )
        ToolMaterializer(toolsRoot).materialize(registryEntry)
        db.toolRegistryDao().insert(registryEntry)
        fun executionCard(
            nodeId: NodeId,
            purpose: ToolExecutionPurpose
        ) = WorkerToolCard(
            toolId = registryEntry.id,
            name = registryEntry.name,
            version = registryEntry.version,
            opportunityId = opportunity.opportunityId,
            opportunityCommitmentHash = commitment.commitmentHash,
            requiredForNodeId = nodeId,
            executionPurpose = purpose,
            description = opportunity.repeatedQuestionPattern,
            inputSchemaHash = AtlasHashing.hashText(registryEntry.inputSchemaJson),
            outputSchemaHash = AtlasHashing.hashText(registryEntry.outputSchemaJson)
        )

        val ledger = BudgetLedger(
            agentId = agentId,
            initialBudget = 500.0,
            floor = 1.0,
            config = BudgetConfig(
                groupId = groupId,
                profile = GroupProfile.default(GroupProfileType.Factory)
            )
        )
        val tool = ExecuteToolTool(
            toolRegistryDao = db.toolRegistryDao(),
            toolSubSandbox = ToolSubSandbox(createTempDirectory(prefix = "workspace-").toFile()),
            budgetLedger = ledger,
            runId = runId,
            groupId = groupId,
            toolsMountPath = toolsRoot.absolutePath,
            agentId = agentId,
            assignedNodeId = executionNodeId,
            availableToolCards = listOf(
                executionCard(executionNodeId, ToolExecutionPurpose.AGENT_USE)
            ),
            round = 2,
            infrastructureRepository = repository,
            costLedgerRepository = CostLedgerRepository(db),
            nowEpochMillis = { 2_000L },
            executionIdFactory = { "simulation-execution" }
        )
        val wrongNodeTool = ExecuteToolTool(
            toolRegistryDao = db.toolRegistryDao(),
            toolSubSandbox = ToolSubSandbox(createTempDirectory(prefix = "workspace-").toFile()),
            budgetLedger = ledger,
            runId = runId,
            groupId = groupId,
            toolsMountPath = toolsRoot.absolutePath,
            agentId = agentId,
            assignedNodeId = originNodeId,
            availableToolCards = listOf(
                executionCard(originNodeId, ToolExecutionPurpose.AGENT_USE)
            ),
            round = 2,
            infrastructureRepository = repository
        )
        val wrongNode = wrongNodeTool.execute(
            ExecuteToolTool.Args(
                toolName = "simulator",
                toolVersion = 1,
                argsJson = """{"iterations":10,"randomSeed":42}"""
            )
        )
        assertContains(wrongNode, "not Council-authorized", ignoreCase = true)

        val missingAssignmentTool = ExecuteToolTool(
            toolRegistryDao = db.toolRegistryDao(),
            toolSubSandbox = ToolSubSandbox(createTempDirectory(prefix = "workspace-").toFile()),
            budgetLedger = ledger,
            runId = runId,
            groupId = groupId,
            toolsMountPath = toolsRoot.absolutePath,
            agentId = agentId,
            assignedNodeId = null,
            round = 2,
            infrastructureRepository = repository
        )
        val missingAssignment = missingAssignmentTool.execute(
            ExecuteToolTool.Args(
                toolName = "simulator",
                toolVersion = 1,
                argsJson = """{"iterations":10,"randomSeed":42}"""
            )
        )
        assertContains(missingAssignment, "assigned-node authority", ignoreCase = true)

        val authorTool = ExecuteToolTool(
            toolRegistryDao = db.toolRegistryDao(),
            toolSubSandbox = ToolSubSandbox(createTempDirectory(prefix = "workspace-").toFile()),
            budgetLedger = ledger,
            runId = runId,
            groupId = groupId,
            toolsMountPath = toolsRoot.absolutePath,
            agentId = registryEntry.authorAgentId,
            assignedNodeId = executionNodeId,
            availableToolCards = listOf(
                executionCard(executionNodeId, ToolExecutionPurpose.AGENT_USE)
            ),
            round = 2,
            infrastructureRepository = repository
        )
        val authorReuse = authorTool.execute(
            ExecuteToolTool.Args(
                toolName = "simulator",
                toolVersion = 1,
                argsJson = """{"iterations":10,"randomSeed":42}"""
            )
        )
        assertContains(authorReuse, "independent agent", ignoreCase = true)
        assertEquals(null, repository.execution("simulation-execution"))

        val validationTool = ExecuteToolTool(
            toolRegistryDao = db.toolRegistryDao(),
            toolSubSandbox = ToolSubSandbox(createTempDirectory(prefix = "workspace-").toFile()),
            budgetLedger = ledger,
            runId = runId,
            groupId = groupId,
            toolsMountPath = toolsRoot.absolutePath,
            agentId = agentId,
            assignedNodeId = validationNodeId,
            availableToolCards = listOf(
                executionCard(validationNodeId, ToolExecutionPurpose.VALIDATION_TEST)
            ),
            round = 2,
            infrastructureRepository = repository,
            costLedgerRepository = CostLedgerRepository(db),
            nowEpochMillis = { 2_000L },
            executionIdFactory = { "validation-execution" }
        )
        val validationResult = validationTool.execute(
            ExecuteToolTool.Args(
                toolName = "simulator",
                toolVersion = 1,
                argsJson = """{"iterations":10,"randomSeed":42}"""
            )
        )
        assertContains(validationResult, "SUCCESS")
        assertEquals(
            ToolExecutionPurpose.VALIDATION_TEST,
            assertNotNull(repository.execution("validation-execution")).purpose
        )
        assertTrue(repository.lifecycle(versionId).none {
            "validation-execution" in it.authorityReferenceIds
        })

        val missingSeed = tool.execute(
            ExecuteToolTool.Args(
                toolName = "simulator",
                toolVersion = 1,
                argsJson = """{"iterations":10}"""
            )
        )
        assertContains(missingSeed, "input does not satisfy", ignoreCase = true)
        assertEquals(null, repository.execution("simulation-execution"))
        assertEquals(null, repository.simulation("simulation:simulation-execution"))

        val result = tool.execute(
            ExecuteToolTool.Args(
                toolName = "simulator",
                toolVersion = 1,
                argsJson = """{"iterations":10,"randomSeed":42}"""
            )
        )

        assertContains(result, "SUCCESS")
        val simulation = assertNotNull(repository.simulation("simulation:simulation-execution"))
        assertEquals(42L, simulation.randomSeed)
        assertEquals(JsonPrimitive(10), simulation.parameters["iterations"])
        assertEquals("stable range", simulation.resultSummary)
        assertEquals("95% interval", simulation.uncertainty)
        assertEquals(listOf("seed-test"), simulation.validationRefs)
        assertEquals("execution-output:simulation-execution", simulation.outputArtifactId)
        val finalizedCost = assertNotNull(
            CostLedgerRepository(db).byId("tool-cost:simulation-execution:finalized")
        )
        assertEquals(CostLedgerEntryKind.FINALIZED, finalizedCost.kind)
        assertEquals(CostSubjectType.SIMULATION, finalizedCost.subjectType)
        assertEquals(MonetaryAmountStatus.UNKNOWN, finalizedCost.toolCost.status)
        assertEquals(0L, finalizedCost.toolComputeMillis)
    }
}
