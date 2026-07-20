package com.achulkov.evomolior.orchestration.lifecycle

import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.atlas.PhysicalDimension
import com.achulkov.evomolior.core.atlas.ToolBehavior
import com.achulkov.evomolior.core.atlas.ToolDslBinaryOperation
import com.achulkov.evomolior.core.atlas.ToolDslExpression
import com.achulkov.evomolior.core.atlas.ToolDslKind
import com.achulkov.evomolior.core.atlas.ToolDslOutput
import com.achulkov.evomolior.core.atlas.ToolDslProgram
import com.achulkov.evomolior.core.atlas.ToolImplementationMode
import com.achulkov.evomolior.core.atlas.ToolOpportunity
import com.achulkov.evomolior.core.atlas.ToolOpportunityCommitment
import com.achulkov.evomolior.core.atlas.ToolOpportunityType
import com.achulkov.evomolior.core.atlas.TypedField
import com.achulkov.evomolior.core.atlas.TypedFieldType
import com.achulkov.evomolior.core.atlas.TypedToolSchemaCompiler
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Tests deterministic validation of staged TOOL submissions. */
class ToolSubmissionValidatorTest {

    @Test
    fun `validator rejects non-zero executable test result`() = runTest {
        val dataRoot = createTempDirectory("tool-submission-validator-test").toFile()
        val validator = ToolSubmissionValidator(
            runId = "run-tool-validator",
            groupId = "group-0",
            toolsMountHostPath = dataRoot.resolve("tools").absolutePath
        )

        val result = validator.validate(
            toolSubmission(
                content = """
                    {
                      "name": "failing-tool",
                      "domain": "audit",
                      "language": "shell",
                      "inputSchema": {"type":"object"},
                      "outputSchema": {"type":"object"},
                      "code": "cat >/dev/null\necho failed >&2\nexit 7",
                      "tests": [{"input": {}}]
                    }
                """.trimIndent()
            )
        )

        assertTrue(result is ToolSubmissionValidationResult.Invalid)
        assertTrue(result.reason.contains("tool_validation_test_failed"))
        assertTrue(result.reason.contains("exited 7"))
    }

    @Test
    fun `validator rejects executable test timeout`() = runTest {
        val dataRoot = createTempDirectory("tool-submission-validator-timeout-test").toFile()
        val validator = ToolSubmissionValidator(
            runId = "run-tool-validator",
            groupId = "group-0",
            toolsMountHostPath = dataRoot.resolve("tools").absolutePath,
            executionTimeoutSeconds = 1
        )

        val result = validator.validate(
            toolSubmission(
                content = """
                    {
                      "name": "hanging-tool",
                      "domain": "audit",
                      "language": "shell",
                      "inputSchema": {"type":"object"},
                      "outputSchema": {"type":"object"},
                      "code": "cat >/dev/null\nsleep 2\necho '{}'",
                      "tests": [{"input": {}}]
                    }
                """.trimIndent()
            )
        )

        assertTrue(result is ToolSubmissionValidationResult.Invalid)
        assertTrue(result.reason.contains("tool_validation_test_timeout"))
    }

    @Test
    fun `validator rejects undeclared output before Critic review`() = runTest {
        val dataRoot = createTempDirectory("tool-submission-validator-output-schema-test").toFile()
        val validator = ToolSubmissionValidator(
            runId = "run-tool-validator",
            groupId = "group-0",
            toolsMountHostPath = dataRoot.resolve("tools").absolutePath
        )

        val result = validator.validate(
            toolSubmission(
                content = """
                    {
                      "name": "extra-output-tool",
                      "domain": "audit",
                      "language": "shell",
                      "inputSchema": {"type":"object"},
                      "outputSchema": {
                        "type":"object",
                        "additionalProperties":false,
                        "properties":{"declared":{"type":"number"}},
                        "required":["declared"]
                      },
                      "code": "cat >/dev/null\necho '{\"declared\":1,\"extra\":2}'",
                      "tests": [{"input": {}}]
                    }
                """.trimIndent()
            )
        )

        assertTrue(result is ToolSubmissionValidationResult.Invalid)
        assertTrue(result.reason.contains("tool_validation_schema_mismatch"))
        assertTrue(result.reason.contains("additional_property_root.extra"))
    }

    @Test
    fun `validator reports bounded expected and actual output on exact mismatch`() = runTest {
        val dataRoot = createTempDirectory("tool-submission-validator-mismatch-test").toFile()
        val validator = ToolSubmissionValidator(
            runId = "run-tool-validator",
            groupId = "group-0",
            toolsMountHostPath = dataRoot.resolve("tools").absolutePath
        )

        val result = validator.validate(
            toolSubmission(
                content = """
                    {
                      "name": "mismatched-tool",
                      "domain": "audit",
                      "language": "shell",
                      "inputSchema": {"type":"object"},
                      "outputSchema": {
                        "type":"object",
                        "additionalProperties":false,
                        "properties":{"value":{"type":"number"}},
                        "required":["value"]
                      },
                      "code": "cat >/dev/null\necho '{\"value\":2}'",
                      "tests": [{"input": {}, "expectedOutput":{"value":1}}]
                    }
                """.trimIndent()
            )
        )

        assertTrue(result is ToolSubmissionValidationResult.Invalid)
        assertTrue(result.reason.contains("expected={\"value\":1}"))
        assertTrue(result.reason.contains("actual={\"value\":2}"))
    }

    @Test
    fun `sealed contract preflight executes a statically valid formula DSL candidate`() = runTest {
        val dataRoot = createTempDirectory("tool-submission-validator-sealed-valid").toFile()
        val commitment = formulaCommitment()
        val validator = ToolSubmissionValidator(
            runId = "run-tool-validator",
            groupId = "group-0",
            toolsMountHostPath = dataRoot.resolve("tools").absolutePath,
            opportunityResolver = { commitment }
        )
        val program = ToolDslProgram(
            kind = ToolDslKind.FORMULA,
            outputs = listOf(
                ToolDslOutput("normalized", ToolDslExpression.Input("value"))
            )
        )

        val result = validator.validate(formulaSubmission(commitment, program))

        assertEquals(ToolSubmissionValidationResult.Valid, result)
    }

    @Test
    fun `sealed contract preflight rejects wrong fixed output cardinality`() = runTest {
        val dataRoot = createTempDirectory("tool-submission-validator-fixed-array").toFile()
        val commitment = ToolOpportunityCommitment(
            runId = "run-tool-validator",
            atlasHash = "atlas-hash",
            nodeId = NodeId("node-1"),
            opportunity = ToolOpportunity(
                opportunityId = "hourly-load-opportunity",
                opportunityType = ToolOpportunityType.CALCULATION,
                repeatedQuestionPattern = "Calculate exactly 72 hourly loads.",
                requiredInputs = listOf(
                    TypedField("hours", TypedFieldType.INTEGER, "Forecast horizon.")
                ),
                requiredOutputs = listOf(
                    TypedField(
                        name = "hourly_load",
                        type = TypedFieldType.ARRAY,
                        description = "Exactly 72 hourly loads.",
                        itemType = TypedFieldType.NUMBER,
                        minimumItems = 72,
                        maximumItems = 72
                    )
                ),
                validationStrategy = "Execute the exact 72-hour boundary case.",
                expectedReuseNodeIds = setOf(NodeId("node-later")),
                preferredImplementationMode = ToolImplementationMode.PYTHON,
                behavior = ToolBehavior.DETERMINISTIC,
                promotionTestIds = setOf("hourly-test"),
                maximumRuntimeMillis = 5_000,
                maximumOutputBytes = 32_768
            )
        ).withComputedHash()
        val validator = ToolSubmissionValidator(
            runId = "run-tool-validator",
            groupId = "group-0",
            toolsMountHostPath = dataRoot.resolve("tools").absolutePath,
            opportunityResolver = { commitment }
        )
        val inputSchema = TypedToolSchemaCompiler.compile(
            commitment.opportunity.requiredInputs
        )
        val outputSchema = TypedToolSchemaCompiler.compile(
            commitment.opportunity.requiredOutputs
        )
        val shortValues = List(71) { "1.0" }.joinToString(",")
        val submission = toolSubmission(
            """
                {
                  "opportunityId":"hourly-load-opportunity",
                  "name":"hourly-load",
                  "domain":"calculation",
                  "language":"python",
                  "inputSchema":$inputSchema,
                  "outputSchema":$outputSchema,
                  "code":"#!/usr/bin/env python3\nprint('{}')",
                  "tests":[{
                    "testId":"hourly-test",
                    "input":{"hours":72},
                    "expectedOutput":{"hourly_load":[$shortValues]}
                  }]
                }
            """.trimIndent()
        )

        val result = assertIs<ToolSubmissionValidationResult.Invalid>(
            validator.validate(submission)
        )

        assertEquals(
            "tool_promotion_contract_test_expected_output_invalid:0:" +
                "too_few_items_root.hourly_load",
            result.reason
        )
    }

    @Test
    fun `sealed contract preflight returns the exact static DSL promotion failure`() = runTest {
        val dataRoot = createTempDirectory("tool-submission-validator-sealed-invalid").toFile()
        val commitment = formulaCommitment()
        val validator = ToolSubmissionValidator(
            runId = "run-tool-validator",
            groupId = "group-0",
            toolsMountHostPath = dataRoot.resolve("tools").absolutePath,
            opportunityResolver = { commitment }
        )
        val program = ToolDslProgram(
            kind = ToolDslKind.FORMULA,
            outputs = listOf(
                ToolDslOutput(
                    "normalized",
                    ToolDslExpression.Conditional(
                        condition = ToolDslExpression.Literal(JsonPrimitive(true)),
                        whenTrue = ToolDslExpression.Input("value"),
                        whenFalse = ToolDslExpression.Literal(JsonPrimitive(0))
                    )
                )
            )
        )

        val result = assertIs<ToolSubmissionValidationResult.Invalid>(
            validator.validate(formulaSubmission(commitment, program))
        )

        assertEquals(
            "tool_promotion_contract_dsl_validation_failed:invalid_expression_normalized",
            result.reason
        )
    }

    @Test
    fun `sealed contract preflight rejects unsupported top level payload fields`() = runTest {
        val dataRoot = createTempDirectory("tool-submission-validator-extra-payload").toFile()
        val commitment = formulaCommitment()
        val validator = ToolSubmissionValidator(
            runId = "run-tool-validator",
            groupId = "group-0",
            toolsMountHostPath = dataRoot.resolve("tools").absolutePath,
            opportunityResolver = { commitment }
        )
        val program = ToolDslProgram(
            kind = ToolDslKind.FORMULA,
            outputs = listOf(
                ToolDslOutput("normalized", ToolDslExpression.Input("value"))
            )
        )
        val payload = Json.parseToJsonElement(
            formulaSubmission(commitment, program).content
        ).jsonObject
        val submission = toolSubmission(
            JsonObject(
                payload + mapOf(
                    "shadowAuthority" to JsonPrimitive(true),
                    "uncommittedMetadata" to JsonPrimitive("ignored")
                )
            ).toString()
        )

        val result = assertIs<ToolSubmissionValidationResult.Invalid>(
            validator.validate(submission)
        )

        assertEquals(
            "tool_promotion_contract_unsupported_payload_fields:" +
                "shadowAuthority,uncommittedMetadata",
            result.reason
        )
    }

    @Test
    fun `sealed contract preflight rejects missing top level payload fields`() = runTest {
        val dataRoot = createTempDirectory("tool-submission-validator-missing-payload").toFile()
        val commitment = formulaCommitment()
        val validator = ToolSubmissionValidator(
            runId = "run-tool-validator",
            groupId = "group-0",
            toolsMountHostPath = dataRoot.resolve("tools").absolutePath,
            opportunityResolver = { commitment }
        )
        val program = ToolDslProgram(
            kind = ToolDslKind.FORMULA,
            outputs = listOf(
                ToolDslOutput("normalized", ToolDslExpression.Input("value"))
            )
        )
        val payload = Json.parseToJsonElement(
            formulaSubmission(commitment, program).content
        ).jsonObject
        val submission = toolSubmission(
            JsonObject(payload - "domain").toString()
        )

        val result = assertIs<ToolSubmissionValidationResult.Invalid>(
            validator.validate(submission)
        )

        assertEquals(
            "tool_promotion_contract_missing_payload_fields:domain",
            result.reason
        )
    }

    @Test
    fun `sealed contract preflight rejects lenient nonstandard JSON`() = runTest {
        val dataRoot = createTempDirectory("tool-submission-validator-strict-json").toFile()
        val commitment = formulaCommitment()
        val validator = ToolSubmissionValidator(
            runId = "run-tool-validator",
            groupId = "group-0",
            toolsMountHostPath = dataRoot.resolve("tools").absolutePath,
            opportunityResolver = { commitment }
        )
        val program = ToolDslProgram(
            kind = ToolDslKind.FORMULA,
            outputs = listOf(
                ToolDslOutput("normalized", ToolDslExpression.Input("value"))
            )
        )
        val nonstandardJson = formulaSubmission(commitment, program).content
            .replaceFirst("\"opportunityId\"", "opportunityId")

        val result = assertIs<ToolSubmissionValidationResult.Invalid>(
            validator.validate(toolSubmission(nonstandardJson))
        )

        assertTrue(result.reason.startsWith("tool_validation_invalid_json"))
    }

    @Test
    fun `sealed preflight drains near limit stdout before waiting for process exit`() = runTest {
        val dataRoot = createTempDirectory("tool-submission-validator-large-stdout").toFile()
        val payload = "x".repeat(900_000)
        val commitment = largeOutputCommitment()
        val validator = ToolSubmissionValidator(
            runId = "run-tool-validator",
            groupId = "group-0",
            toolsMountHostPath = dataRoot.resolve("tools").absolutePath,
            opportunityResolver = { commitment }
        )
        val code = """
            #!/usr/bin/env python3
            import json
            print(json.dumps({"payload": "x" * 900000}, separators=(",", ":")))
        """.trimIndent()

        val result = validator.validate(
            largeOutputSubmission(commitment, code, payload)
        )

        assertEquals(ToolSubmissionValidationResult.Valid, result)
    }

    @Test
    fun `sealed preflight drains near limit stderr while preserving valid stdout`() = runTest {
        val dataRoot = createTempDirectory("tool-submission-validator-large-stderr").toFile()
        val commitment = largeOutputCommitment()
        val validator = ToolSubmissionValidator(
            runId = "run-tool-validator",
            groupId = "group-0",
            toolsMountHostPath = dataRoot.resolve("tools").absolutePath,
            opportunityResolver = { commitment }
        )
        val code = """
            #!/usr/bin/env python3
            import json
            import sys
            sys.stderr.write("e" * 900000)
            print(json.dumps({"payload": "ok"}, separators=(",", ":")))
        """.trimIndent()

        val result = validator.validate(
            largeOutputSubmission(commitment, code, "ok")
        )

        assertEquals(ToolSubmissionValidationResult.Valid, result)
    }

    @Test
    fun `sealed contract preflight rejects aggregate coefficient multiplied by area`() = runTest {
        val dataRoot = createTempDirectory("tool-submission-validator-dimensional-invalid").toFile()
        val commitment = dimensionalFormulaCommitment()
        val validator = ToolSubmissionValidator(
            runId = "run-tool-validator",
            groupId = "group-0",
            toolsMountHostPath = dataRoot.resolve("tools").absolutePath,
            opportunityResolver = { commitment }
        )
        val program = ToolDslProgram(
            kind = ToolDslKind.FORMULA,
            outputs = listOf(
                ToolDslOutput(
                    "required_power",
                    ToolDslExpression.Binary(
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

        val result = assertIs<ToolSubmissionValidationResult.Invalid>(
            validator.validate(formulaSubmission(commitment, program))
        )

        assertEquals(
            "tool_promotion_contract_dsl_validation_failed:" +
                "wrong_output_dimension_required_power",
            result.reason
        )
    }

    private fun formulaCommitment(): ToolOpportunityCommitment =
        ToolOpportunityCommitment(
            runId = "run-tool-validator",
            atlasHash = "atlas-hash",
            nodeId = NodeId("node-1"),
            opportunity = ToolOpportunity(
                opportunityId = "formula-opportunity",
                opportunityType = ToolOpportunityType.CALCULATION,
                repeatedQuestionPattern = "Normalize one bounded number.",
                requiredInputs = listOf(
                    TypedField("value", TypedFieldType.NUMBER, "Value to normalize.")
                ),
                requiredOutputs = listOf(
                    TypedField("normalized", TypedFieldType.NUMBER, "Normalized value.")
                ),
                validationStrategy = "Execute the exact identity case.",
                expectedReuseNodeIds = setOf(NodeId("node-later")),
                preferredImplementationMode = ToolImplementationMode.FORMULA_DSL,
                behavior = ToolBehavior.DETERMINISTIC,
                promotionTestIds = setOf("identity-test"),
                maximumRuntimeMillis = 5_000,
                maximumOutputBytes = 4_096
            )
        ).withComputedHash()

    private fun dimensionalFormulaCommitment(): ToolOpportunityCommitment =
        ToolOpportunityCommitment(
            runId = "run-tool-validator",
            atlasHash = "atlas-hash",
            nodeId = NodeId("node-1"),
            opportunity = ToolOpportunity(
                opportunityId = "dimensional-formula-opportunity",
                opportunityType = ToolOpportunityType.CALCULATION,
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
                validationStrategy = "Execute the exact aggregate cooling case.",
                expectedReuseNodeIds = setOf(NodeId("node-later")),
                preferredImplementationMode = ToolImplementationMode.FORMULA_DSL,
                behavior = ToolBehavior.DETERMINISTIC,
                promotionTestIds = setOf("identity-test"),
                maximumRuntimeMillis = 1_000,
                maximumOutputBytes = 4_096
            )
        ).withComputedHash()

    private fun largeOutputCommitment(): ToolOpportunityCommitment =
        ToolOpportunityCommitment(
            runId = "run-tool-validator",
            atlasHash = "atlas-hash",
            nodeId = NodeId("node-1"),
            opportunity = ToolOpportunity(
                opportunityId = "large-output-opportunity",
                opportunityType = ToolOpportunityType.CALCULATION,
                repeatedQuestionPattern = "Produce one bounded payload.",
                requiredInputs = listOf(
                    TypedField("requested", TypedFieldType.BOOLEAN, "Request the payload.")
                ),
                requiredOutputs = listOf(
                    TypedField("payload", TypedFieldType.STRING, "Bounded payload.")
                ),
                validationStrategy = "Execute the committed payload case.",
                expectedReuseNodeIds = setOf(NodeId("node-later")),
                preferredImplementationMode = ToolImplementationMode.PYTHON,
                behavior = ToolBehavior.DETERMINISTIC,
                promotionTestIds = setOf("large-output-test"),
                maximumRuntimeMillis = 10_000,
                maximumOutputBytes = 1_048_576
            )
        ).withComputedHash()

    private fun largeOutputSubmission(
        commitment: ToolOpportunityCommitment,
        code: String,
        expectedPayload: String
    ): StagingSubmissionEntity {
        val inputSchema = Json.parseToJsonElement(
            TypedToolSchemaCompiler.compile(commitment.opportunity.requiredInputs)
        )
        val outputSchema = Json.parseToJsonElement(
            TypedToolSchemaCompiler.compile(commitment.opportunity.requiredOutputs)
        )
        val payload = buildJsonObject {
            put("opportunityId", commitment.opportunity.opportunityId)
            put("name", "large-output-tool")
            put("domain", "calculation")
            put("language", "python")
            put("inputSchema", inputSchema)
            put("outputSchema", outputSchema)
            put("code", code)
            put(
                "tests",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("testId", "large-output-test")
                            put("input", buildJsonObject { put("requested", true) })
                            put(
                                "expectedOutput",
                                buildJsonObject { put("payload", expectedPayload) }
                            )
                        }
                    )
                }
            )
        }
        return toolSubmission(payload.toString())
    }

    private fun formulaSubmission(
        commitment: ToolOpportunityCommitment,
        program: ToolDslProgram
    ): StagingSubmissionEntity {
        val inputSchema = TypedToolSchemaCompiler.compile(
            commitment.opportunity.requiredInputs
        )
        val outputSchema = TypedToolSchemaCompiler.compile(
            commitment.opportunity.requiredOutputs
        )
        return toolSubmission(
            """
                {
                  "opportunityId":"${commitment.opportunity.opportunityId}",
                  "name":"identity-formula",
                  "domain":"calculation",
                  "language":"dsl",
                  "inputSchema":$inputSchema,
                  "outputSchema":$outputSchema,
                  "code":${JsonPrimitive(Json.encodeToString(program))},
                  "tests":[{
                    "testId":"identity-test",
                    "input":{"value":1.5},
                    "expectedOutput":{"normalized":1.5}
                  }]
                }
            """.trimIndent()
        )
    }

    private fun toolSubmission(content: String): StagingSubmissionEntity =
        StagingSubmissionEntity(
            id = "tool-submission",
            runId = "run-tool-validator",
            groupId = "group-0",
            agentId = "agent-builder",
            role = "Builder",
            type = "TOOL",
            content = content,
            budgetSignature = "sig",
            wipTaskRef = "node-1",
            generation = 1,
            submittedAtMs = 1_000L
        )
}
