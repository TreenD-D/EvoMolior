@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.orchestration.lifecycle

import com.achulkov.evomolior.agents.tools.ExecuteToolTool
import com.achulkov.evomolior.core.budget.BudgetConfig
import com.achulkov.evomolior.core.budget.BudgetLedger
import com.achulkov.evomolior.core.model.GroupProfile
import com.achulkov.evomolior.core.model.GroupProfileType
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.atlas.PhysicalDimension
import com.achulkov.evomolior.core.atlas.ToolDslBinaryOperation
import com.achulkov.evomolior.core.atlas.ToolDslExpression
import com.achulkov.evomolior.core.atlas.ToolDslKind
import com.achulkov.evomolior.core.atlas.ToolDslOutput
import com.achulkov.evomolior.core.atlas.ToolDslProgram
import com.achulkov.evomolior.core.atlas.ToolImplementationMode
import com.achulkov.evomolior.core.atlas.ToolBehavior
import com.achulkov.evomolior.core.atlas.ToolLifecycleState
import com.achulkov.evomolior.core.atlas.ToolOpportunity
import com.achulkov.evomolior.core.atlas.ToolOpportunityCommitment
import com.achulkov.evomolior.core.atlas.ToolOpportunityType
import com.achulkov.evomolior.core.atlas.ToolVersionId
import com.achulkov.evomolior.core.atlas.TypedField
import com.achulkov.evomolior.core.atlas.TypedFieldType
import com.achulkov.evomolior.core.atlas.TypedToolSchemaCompiler
import com.achulkov.evomolior.core.contract.WorkerToolCard
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import com.achulkov.evomolior.substrate.tools.ToolInfrastructureRepository
import com.achulkov.evomolior.sandbox.toolsandbox.ToolSubSandbox
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Instant

class ToolPromotionServiceTest {
    private val runId = "run-promotion"
    private val groupId = "group-promotion"
    private val nodeId = NodeId("node-tool")

    @Test
    fun `promotion rejects lenient nonstandard staged JSON`() = runTest {
        val root = createTempDirectory("tool-promotion-strict-json-").toFile()
        val database = SubstrateDatabaseFactory(root).get(runId, groupId)
        try {
            val commitment = commitment(runtimeMillis = 1_000).withComputedHash()
            val service = ToolPromotionService(
                database = database,
                runId = runId,
                groupId = groupId,
                toolsMountHostPath = File(root, "tools").absolutePath,
                opportunityResolver = { commitment }
            )
            val valid = validSubmission(commitment)
            val nonstandard = valid.copy(
                content = valid.content.replaceFirst(
                    "\"opportunityId\"",
                    "opportunityId"
                )
            )

            assertEquals(
                ToolPromotionResult.Rejected("tool_promotion_invalid_json"),
                service.promote(nonstandard, "critic-1")
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun `promotion requires exact schemas and executes committed tests in bounded sandbox`() =
        runTest {
            val root = createTempDirectory("tool-promotion-service-").toFile()
            val database = SubstrateDatabaseFactory(root).get(runId, groupId)
            try {
                val commitment = commitment(runtimeMillis = 1_000).withComputedHash()
                val ids = ArrayDeque(
                    listOf(
                        "event-spec",
                        "event-implemented",
                        "event-static",
                        "execution-promotion",
                        "event-sandbox",
                        "event-critic",
                        "event-promoted"
                    )
                )
                val service = ToolPromotionService(
                    database = database,
                    runId = runId,
                    groupId = groupId,
                    toolsMountHostPath = File(root, "tools").absolutePath,
                    opportunityResolver = { commitment },
                    now = { Instant.parse("2026-01-01T00:00:00Z") },
                    idFactory = { ids.removeFirst() }
                )

                val result = service.promote(validSubmission(commitment), "critic-1")

                assertEquals(ToolPromotionResult.Promoted("normalize-v1"), result)
                val registry = database.toolRegistryDao().getById("normalize-v1")
                assertEquals("active", registry?.status)
                assertTrue(File(root, "tools/normalize-v1").canExecute())
                val exactRegistry = requireNotNull(registry)
                val repository = ToolInfrastructureRepository(database)
                assertEquals(
                    ToolLifecycleState.PROMOTED,
                    repository.lifecycle(ToolVersionId("normalize-v1")).last().state
                )
                val laterNodeId = NodeId("node-later")
                val executeTool = ExecuteToolTool(
                    toolRegistryDao = database.toolRegistryDao(),
                    toolSubSandbox = ToolSubSandbox(File(root, "workspace")),
                    budgetLedger = BudgetLedger(
                        agentId = "agent-later",
                        initialBudget = 100.0,
                        floor = 1.0,
                        config = BudgetConfig(
                            groupId,
                            GroupProfile.default(GroupProfileType.Factory)
                        )
                    ),
                    runId = runId,
                    groupId = groupId,
                    toolsMountPath = File(root, "tools").absolutePath,
                    agentId = "agent-later",
                    assignedNodeId = laterNodeId,
                    availableToolCards = listOf(
                        WorkerToolCard(
                            toolId = exactRegistry.id,
                            name = exactRegistry.name,
                            version = exactRegistry.version,
                            opportunityId = commitment.opportunity.opportunityId,
                            opportunityCommitmentHash = commitment.commitmentHash,
                            requiredForNodeId = laterNodeId,
                            description = commitment.opportunity.repeatedQuestionPattern,
                            inputSchemaHash = AtlasHashing.hashText(
                                exactRegistry.inputSchemaJson
                            ),
                            outputSchemaHash = AtlasHashing.hashText(
                                exactRegistry.outputSchemaJson
                            )
                        )
                    ),
                    round = 2,
                    infrastructureRepository = repository,
                    nowEpochMillis = { 1_767_225_600_000L },
                    executionIdFactory = { "execution-later" }
                )

                val executionResponse = executeTool.execute(
                    ExecuteToolTool.Args(
                        toolName = "normalize",
                        toolVersion = 1,
                        argsJson = """{"value":1}"""
                    )
                )

                assertTrue(executionResponse.contains("Execution ID: execution-later"))
                assertTrue(executionResponse.contains("Status: SUCCESS"))
                assertEquals(
                    2,
                    repository.executions(ToolVersionId("normalize-v1")).size
                )
                val lifecycle = repository.lifecycle(ToolVersionId("normalize-v1"))
                assertEquals(
                    ToolLifecycleState.EXECUTED_BY_LATER_AGENT,
                    lifecycle.last().state
                )
                assertEquals(laterNodeId, lifecycle.last().contextNodeId)
            } finally {
                database.close()
            }
        }

    @Test
    fun `promotion records canonical fixture input hashes`() = runTest {
        val root = createTempDirectory("tool-promotion-canonical-input-").toFile()
        val database = SubstrateDatabaseFactory(root).get(runId, groupId)
        try {
            val base = commitment(runtimeMillis = 1_000)
            val commitment = base.copy(
                opportunity = base.opportunity.copy(
                    requiredInputs = listOf(
                        TypedField("value", TypedFieldType.NUMBER, "Input numeric value."),
                        TypedField("scale", TypedFieldType.NUMBER, "Scale factor.")
                    )
                ),
                commitmentHash = ""
            ).withComputedHash()
            val submission = validSubmission(commitment).copy(
                content = validSubmission(commitment).content.replace(
                    "\"input\": {\"value\": 1}",
                    "\"input\": {\"value\": 1, \"scale\": 999}"
                )
            )
            val service = ToolPromotionService(
                database = database,
                runId = runId,
                groupId = groupId,
                toolsMountHostPath = File(root, "tools").absolutePath,
                opportunityResolver = { commitment },
                sealedPromotionTestResolver = {
                    listOf(
                        SealedPromotionTestFixture(
                            id = "identity-test",
                            input = Json.parseToJsonElement("""{"value":1,"scale":2}"""),
                            expectedOutput = Json.parseToJsonElement("""{"normalized":1}""")
                        )
                    )
                }
            )

            assertEquals(
                ToolPromotionResult.Promoted("normalize-v1"),
                service.promote(submission, "critic-canonical-input")
            )
            val execution = ToolInfrastructureRepository(database)
                .executions(ToolVersionId("normalize-v1"))
                .single()
            assertEquals(
                AtlasHashing.hashText("""{"scale":2,"value":1}"""),
                execution.inputHash
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun `ordinary same-name and differently named duplicates cannot replace active opportunity authority`() =
        runTest {
            val root = createTempDirectory("tool-promotion-duplicate-opportunity-").toFile()
            val database = SubstrateDatabaseFactory(root).get(runId, groupId)
            try {
                val commitment = commitment(runtimeMillis = 1_000).withComputedHash()
                val service = ToolPromotionService(
                    database = database,
                    runId = runId,
                    groupId = groupId,
                    toolsMountHostPath = File(root, "tools").absolutePath,
                    opportunityResolver = { commitment }
                )
                assertEquals(
                    ToolPromotionResult.Promoted("normalize-v1"),
                    service.promote(validSubmission(commitment), "critic-1")
                )

                val expectedRejection = ToolPromotionResult.Rejected(
                    "tool_promotion_opportunity_already_active:normalize-v1"
                )
                assertEquals(
                    expectedRejection,
                    service.promote(
                        validSubmission(
                            commitment,
                            id = "submission-same-name-duplicate"
                        ),
                        "critic-2"
                    )
                )
                assertEquals(
                    expectedRejection,
                    service.promote(
                        validSubmission(
                            commitment,
                            id = "submission-different-name-duplicate",
                            name = "normalize-alternative"
                        ),
                        "critic-3"
                    )
                )

                assertEquals(
                    listOf("normalize-v1"),
                    database.toolRegistryDao()
                        .getActive(runId, groupId, null)
                        .map { it.id }
                )
                val repository = ToolInfrastructureRepository(database)
                assertTrue(repository.lifecycle(ToolVersionId("normalize-v2")).isEmpty())
                assertTrue(
                    repository.lifecycle(ToolVersionId("normalize-alternative-v1")).isEmpty()
                )
            } finally {
                database.close()
            }
        }

    @Test
    fun `timeout and schema mismatch cannot activate registry version`() = runTest {
        val root = createTempDirectory("tool-promotion-rejection-").toFile()
        val database = SubstrateDatabaseFactory(root).get(runId, groupId)
        try {
            val commitment = commitment(runtimeMillis = 50).withComputedHash()
            val service = ToolPromotionService(
                database = database,
                runId = runId,
                groupId = groupId,
                toolsMountHostPath = File(root, "tools").absolutePath,
                opportunityResolver = { commitment }
            )
            val timeoutSubmission = validSubmission(
                commitment,
                code = "cat >/dev/null\nsleep 1\necho '{\"normalized\":1}'"
            )

            val timeout = service.promote(timeoutSubmission, "critic-1")

            assertIs<ToolPromotionResult.Rejected>(timeout)
            assertTrue(timeout.reason.contains("TIMEOUT"))
            assertTrue(database.toolRegistryDao().getActive(runId, groupId, null).isEmpty())

            val mismatched = validSubmission(commitment).copy(
                id = "submission-schema-mismatch",
                content = validSubmission(commitment).content.replace(
                    "\"additionalProperties\":false",
                    "\"additionalProperties\":true"
                )
            )
            val mismatchResult = service.promote(mismatched, "critic-1")
            assertEquals(
                ToolPromotionResult.Rejected("tool_promotion_contract_schema_mismatch"),
                mismatchResult
            )
            assertTrue(database.toolRegistryDao().getActive(runId, groupId, null).isEmpty())
        } finally {
            database.close()
        }
    }

    @Test
    fun `promotion reports exact sealed contract mismatch predicates`() = runTest {
        val root = createTempDirectory("tool-promotion-contract-predicates-").toFile()
        val database = SubstrateDatabaseFactory(root).get(runId, groupId)
        try {
            val commitment = commitment(runtimeMillis = 1_000).withComputedHash()
            val service = ToolPromotionService(
                database = database,
                runId = runId,
                groupId = groupId,
                toolsMountHostPath = File(root, "tools").absolutePath,
                opportunityResolver = { commitment }
            )
            val base = validSubmission(commitment)
            val cases = listOf(
                Triple(
                    "wrong-opportunity",
                    base.content.replace(
                        commitment.opportunity.opportunityId,
                        "opportunity-other"
                    ),
                    "tool_promotion_contract_opportunity_id_mismatch"
                ),
                Triple(
                    "wrong-language",
                    base.content.replace("\"language\": \"shell\"", "\"language\": \"python\""),
                    "tool_promotion_contract_language_mismatch"
                ),
                Triple(
                    "wrong-test",
                    base.content.replace("\"testId\": \"identity-test\"", "\"testId\": \"other\""),
                    "tool_promotion_contract_tests_mismatch"
                )
            )

            cases.forEach { (id, content, expectedReason) ->
                assertEquals(
                    ToolPromotionResult.Rejected(expectedReason),
                    service.promote(base.copy(id = id, content = content), "critic-1")
                )
            }
            assertTrue(database.toolRegistryDao().getActive(runId, groupId, null).isEmpty())
        } finally {
            database.close()
        }
    }

    @Test
    fun `closed formula DSL promotes through bounded execution and rejects unknown operator`() =
        runTest {
            val root = createTempDirectory("tool-dsl-promotion-").toFile()
            val database = SubstrateDatabaseFactory(root).get(runId, groupId)
            try {
                val commitment = commitment(runtimeMillis = 5_000).copy(
                    opportunity = commitment(runtimeMillis = 5_000).opportunity.copy(
                        preferredImplementationMode = ToolImplementationMode.FORMULA_DSL
                    )
                ).withComputedHash()
                val program = ToolDslProgram(
                    kind = ToolDslKind.FORMULA,
                    outputs = listOf(
                        ToolDslOutput(
                            field = "normalized",
                            expression = ToolDslExpression.Binary(
                                operation = ToolDslBinaryOperation.MULTIPLY,
                                left = ToolDslExpression.Input("value"),
                                right = ToolDslExpression.Literal(JsonPrimitive(1))
                            )
                        )
                    )
                )
                val service = ToolPromotionService(
                    database = database,
                    runId = runId,
                    groupId = groupId,
                    toolsMountHostPath = File(root, "tools").absolutePath,
                    opportunityResolver = { commitment }
                )

                val promoted = service.promote(
                    dslSubmission(commitment, Json.encodeToString(program)),
                    "critic-dsl"
                )

                assertEquals(ToolPromotionResult.Promoted("normalize-v1"), promoted)
                val invalid = service.promote(
                    dslSubmission(
                        commitment,
                        Json.encodeToString(program).replace("\"MULTIPLY\"", "\"EXECUTE\"")
                    ).copy(id = "invalid-dsl"),
                    "critic-dsl"
                )
                assertEquals(
                    ToolPromotionResult.Rejected("tool_promotion_contract_invalid_dsl"),
                    invalid
                )
                val wrongKind = service.promote(
                    dslSubmission(
                        commitment,
                        Json.encodeToString(program.copy(kind = ToolDslKind.RULE))
                    ).copy(id = "wrong-kind-dsl"),
                    "critic-dsl"
                )
                assertEquals(
                    ToolPromotionResult.Rejected(
                        "tool_promotion_contract_dsl_validation_failed:wrong_program_kind"
                    ),
                    wrongKind
                )
            } finally {
                database.close()
            }
        }

    @Test
    fun `post-Critic promotion rejects aggregate coefficient multiplied by area`() = runTest {
        val root = createTempDirectory("tool-dimensional-promotion-rejection-").toFile()
        val database = SubstrateDatabaseFactory(root).get(runId, groupId)
        try {
            val opportunity = commitment(runtimeMillis = 1_000).opportunity.copy(
                repeatedQuestionPattern = "Calculate aggregate cooling power.",
                requiredInputs = listOf(
                    TypedField(
                        "aggregate_coefficient",
                        TypedFieldType.NUMBER,
                        "Aggregate power per temperature.",
                        physicalDimension = PhysicalDimension(
                            mapOf("power" to 1, "temperature" to -1)
                        )
                    ),
                    TypedField(
                        "temperature_delta",
                        TypedFieldType.NUMBER,
                        "Temperature difference.",
                        physicalDimension = PhysicalDimension(mapOf("temperature" to 1))
                    ),
                    TypedField(
                        "area",
                        TypedFieldType.NUMBER,
                        "Shelter floor area.",
                        physicalDimension = PhysicalDimension(mapOf("length" to 2))
                    )
                ),
                requiredOutputs = listOf(
                    TypedField(
                        "required_power",
                        TypedFieldType.NUMBER,
                        "Required aggregate cooling power.",
                        physicalDimension = PhysicalDimension(mapOf("power" to 1))
                    )
                ),
                preferredImplementationMode = ToolImplementationMode.FORMULA_DSL
            )
            val commitment = commitment(runtimeMillis = 1_000).copy(
                opportunity = opportunity
            ).withComputedHash()
            val invalidProgram = ToolDslProgram(
                kind = ToolDslKind.FORMULA,
                outputs = listOf(
                    ToolDslOutput(
                        field = "required_power",
                        expression = ToolDslExpression.Binary(
                            operation = ToolDslBinaryOperation.MULTIPLY,
                            left = ToolDslExpression.Binary(
                                operation = ToolDslBinaryOperation.MULTIPLY,
                                left = ToolDslExpression.Input("aggregate_coefficient"),
                                right = ToolDslExpression.Input("temperature_delta")
                            ),
                            right = ToolDslExpression.Input("area")
                        )
                    )
                )
            )
            val service = ToolPromotionService(
                database = database,
                runId = runId,
                groupId = groupId,
                toolsMountHostPath = File(root, "tools").absolutePath,
                opportunityResolver = { commitment }
            )

            val result = service.promote(
                dslSubmission(commitment, Json.encodeToString(invalidProgram)),
                "critic-dimensional"
            )

            assertEquals(
                ToolPromotionResult.Rejected(
                    "tool_promotion_contract_dsl_validation_failed:" +
                        "wrong_output_dimension_required_power"
                ),
                result
            )
            assertTrue(database.toolRegistryDao().getActive(runId, groupId, null).isEmpty())
        } finally {
            database.close()
        }
    }

    private fun commitment(runtimeMillis: Long) = ToolOpportunityCommitment(
        runId = runId,
        atlasHash = "atlas-hash",
        nodeId = nodeId,
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
            expectedReuseNodeIds = setOf(NodeId("node-later")),
            preferredImplementationMode = ToolImplementationMode.SHELL,
            behavior = ToolBehavior.DETERMINISTIC,
            requiredCapabilities = setOf("shell"),
            promotionTestIds = setOf("identity-test"),
            maximumRuntimeMillis = runtimeMillis,
            maximumOutputBytes = 4_096,
            assumptions = listOf("Input is finite."),
            knownInvalidDomains = listOf("Non-numeric values.")
        )
    )

    private fun validSubmission(
        commitment: ToolOpportunityCommitment,
        code: String = "cat >/dev/null\necho '{\"normalized\":1}'",
        id: String = "submission-tool",
        name: String = "normalize"
    ): StagingSubmissionEntity {
        val inputSchema = TypedToolSchemaCompiler.compile(
            commitment.opportunity.requiredInputs
        )
        val outputSchema = TypedToolSchemaCompiler.compile(
            commitment.opportunity.requiredOutputs
        )
        return StagingSubmissionEntity(
            id = id,
            runId = runId,
            groupId = groupId,
            agentId = "builder-1",
            role = "Builder",
            type = "TOOL",
            content = """
                {
                  "opportunityId": "${commitment.opportunity.opportunityId}",
                  "name": "$name",
                  "domain": "calculation",
                  "language": "shell",
                  "inputSchema": $inputSchema,
                  "outputSchema": $outputSchema,
                  "code": ${jsonString(code)},
                  "tests": [
                    {
                      "testId": "identity-test",
                      "input": {"value": 1},
                      "expectedOutput": {"normalized": 1}
                    }
                  ]
                }
            """.trimIndent(),
            wipTaskRef = nodeId.value,
            budgetSignature = "signature",
            generation = 1,
            submittedAtMs = 1L
        )
    }

    private fun dslSubmission(
        commitment: ToolOpportunityCommitment,
        code: String
    ): StagingSubmissionEntity {
        val inputSchema = TypedToolSchemaCompiler.compile(commitment.opportunity.requiredInputs)
        val outputSchema = TypedToolSchemaCompiler.compile(commitment.opportunity.requiredOutputs)
        return StagingSubmissionEntity(
            id = "submission-dsl",
            runId = runId,
            groupId = groupId,
            agentId = "builder-dsl",
            role = "Builder",
            type = "TOOL",
            content = """
                {
                  "opportunityId": "${commitment.opportunity.opportunityId}",
                  "name": "normalize",
                  "domain": "formula",
                  "language": "dsl",
                  "inputSchema": $inputSchema,
                  "outputSchema": $outputSchema,
                  "code": ${jsonString(code)},
                  "tests": [
                    {
                      "testId": "identity-test",
                      "input": {"value": 1},
                      "expectedOutput": {"normalized": 1}
                    }
                  ]
                }
            """.trimIndent(),
            wipTaskRef = nodeId.value,
            budgetSignature = "signature",
            generation = 1,
            submittedAtMs = 1L
        )
    }

    private fun jsonString(value: String): String =
        kotlinx.serialization.json.JsonPrimitive(value).toString()
}
