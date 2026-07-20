package com.achulkov.evomolior.core.atlas

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ToolDslContractsTest {
    @Test
    fun `closed formula AST round trips and matches typed opportunity`() {
        val program = ToolDslProgram(
            kind = ToolDslKind.FORMULA,
            outputs = listOf(
                ToolDslOutput(
                    field = "normalized",
                    expression = ToolDslExpression.Binary(
                        operation = ToolDslBinaryOperation.DIVIDE,
                        left = ToolDslExpression.Input("value"),
                        right = ToolDslExpression.Literal(JsonPrimitive(100))
                    )
                )
            )
        )
        val encoded = Json.encodeToString(program)

        assertEquals(program, Json.decodeFromString<ToolDslProgram>(encoded))
        assertContains(
            encoded,
            "\"type\":\"literal\",\"value\":{\"type\":\"integer\",\"value\":100}"
        )
        val runtime = ToolDslPythonRuntime.render(encoded)
        assertContains(runtime, "def decode_literal(node, depth=0):")
        assertContains(runtime, "return decode_literal(node[\"value\"])")
        assertNull(ToolDslValidator.validationError(program, opportunity()))
    }

    @Test
    fun `invalid operator domain and output type fail closed`() {
        val booleanArithmetic = ToolDslProgram(
            kind = ToolDslKind.FORMULA,
            outputs = listOf(
                ToolDslOutput(
                    field = "normalized",
                    expression = ToolDslExpression.Binary(
                        operation = ToolDslBinaryOperation.ADD,
                        left = ToolDslExpression.Literal(JsonPrimitive(true)),
                        right = ToolDslExpression.Input("value")
                    )
                )
            )
        )
        val wrongOutput = ToolDslProgram(
            kind = ToolDslKind.FORMULA,
            outputs = listOf(
                ToolDslOutput(
                    field = "normalized",
                    expression = ToolDslExpression.Literal(JsonPrimitive("not a number"))
                )
            )
        )

        assertEquals(
            "invalid_expression_normalized",
            ToolDslValidator.validationError(booleanArithmetic, opportunity())
        )
        assertEquals(
            "wrong_output_type_normalized",
            ToolDslValidator.validationError(wrongOutput, opportunity())
        )
    }

    @Test
    fun `dimension metadata and structured formula commitment survive serialization`() {
        val referenceProgram = formula(ToolDslExpression.Input("value"))
        val opportunity = opportunity(
            inputDimension = PhysicalDimension(mapOf("length" to 2)),
            outputDimension = PhysicalDimension(mapOf("length" to 2)),
            referenceProgram = referenceProgram
        )
        val encoded = Json.encodeToString(opportunity)

        assertEquals(opportunity, Json.decodeFromString<ToolOpportunity>(encoded))
        assertContains(encoded, "\"exponents\":[{\"key\":\"length\",\"value\":2}]")
    }

    @Test
    fun `structured literal objects round trip with deterministic entries and reject duplicates`() {
        val literal = ToolDslExpression.Literal(
            JsonObject(
                linkedMapOf(
                    "zeta" to JsonPrimitive(true),
                    "alpha" to JsonPrimitive(2)
                )
            )
        )
        val encoded = Json.encodeToString<ToolDslExpression>(literal)

        assertEquals(literal, Json.decodeFromString<ToolDslExpression>(encoded))
        assertContains(
            encoded,
            "\"type\":\"object\",\"entries\":[{\"key\":\"alpha\"," +
                "\"value\":{\"type\":\"integer\",\"value\":2}},{\"key\":\"zeta\"," +
                "\"value\":{\"type\":\"boolean\",\"value\":true}}]"
        )
        assertFailsWith<kotlinx.serialization.SerializationException> {
            Json.decodeFromString<ToolDslExpression>(
                """
                    {
                      "type":"literal",
                      "value":{
                        "type":"object",
                        "entries":[
                          {"key":"duplicate","value":{"type":"integer","value":1}},
                          {"key":"duplicate","value":{"type":"integer","value":2}}
                        ]
                      }
                    }
                """.trimIndent()
            )
        }
        assertFailsWith<kotlinx.serialization.SerializationException> {
            Json.decodeFromString<PhysicalDimension>(
                """
                    {
                      "exponents":[
                        {"key":"length","value":1},
                        {"key":"length","value":2}
                      ]
                    }
                """.trimIndent()
            )
        }
    }

    @Test
    fun `reference formula is optional but restricted to formula semantics and mode`() {
        val directOpportunity = opportunity().copy(
            formulaDslReferenceSemantics = null,
            formulaDslReferenceProgram = null
        )

        assertNull(ToolDslValidator.referenceProgramValidationError(directOpportunity))
        assertFailsWith<IllegalArgumentException> {
            directOpportunity.copy(
                preferredImplementationMode = ToolImplementationMode.RULE_DSL,
                formulaDslReferenceSemantics =
                    FormulaDslReferenceSemantics.DIMENSIONAL_ANALYSIS_V1
            )
        }
        assertFailsWith<IllegalArgumentException> {
            directOpportunity.copy(
                formulaDslReferenceProgram =
                    formula(ToolDslExpression.Input("value"))
            )
        }
        assertFailsWith<IllegalArgumentException> {
            directOpportunity.copy(
                formulaDslReferenceSemantics =
                    FormulaDslReferenceSemantics.DIMENSIONAL_ANALYSIS_V1,
                formulaDslReferenceProgram = ToolDslProgram(
                    kind = ToolDslKind.RULE,
                    outputs = listOf(
                        ToolDslOutput("normalized", ToolDslExpression.Input("value"))
                    )
                )
            )
        }
    }

    @Test
    fun `coherent Council reference formula passes dimensional validation`() {
        val powerPerAreaPerTemperature = PhysicalDimension(
            mapOf(
                "mass" to 1,
                "time" to -3,
                "temperature" to -1
            )
        )
        val area = PhysicalDimension(mapOf("length" to 2))
        val powerPerTemperature = PhysicalDimension(
            mapOf(
                "mass" to 1,
                "length" to 2,
                "time" to -3,
                "temperature" to -1
            )
        )
        val referenceProgram = formula(
            ToolDslExpression.Binary(
                operation = ToolDslBinaryOperation.MULTIPLY,
                left = ToolDslExpression.Aggregate(
                    operation = ToolDslAggregateOperation.MEAN,
                    values = ToolDslExpression.Input("coefficients")
                ),
                right = ToolDslExpression.Input("area")
            )
        )
        val opportunity = opportunity(
            inputDimension = powerPerAreaPerTemperature,
            outputDimension = powerPerTemperature,
            additionalInputs = listOf(
                TypedField(
                    "coefficients",
                    TypedFieldType.ARRAY,
                    "Power-per-area-per-temperature coefficients.",
                    itemType = TypedFieldType.NUMBER,
                    minimumItems = 1,
                    maximumItems = 64,
                    physicalDimension = powerPerAreaPerTemperature
                ),
                TypedField(
                    "area",
                    TypedFieldType.NUMBER,
                    "Measured area.",
                    physicalDimension = area
                )
            ),
            referenceProgram = referenceProgram
        )

        assertNull(ToolDslValidator.referenceProgramValidationError(opportunity))
    }

    @Test
    fun `v38 reference formula rejects aggregate power per temperature multiplied by area`() {
        val referenceProgram = formula(
            ToolDslExpression.Binary(
                operation = ToolDslBinaryOperation.MULTIPLY,
                left = ToolDslExpression.Aggregate(
                    operation = ToolDslAggregateOperation.MEAN,
                    values = ToolDslExpression.Input("coefficients")
                ),
                right = ToolDslExpression.Input("area")
            )
        )
        val powerPerTemperature = PhysicalDimension(
            mapOf(
                "mass" to 1,
                "length" to 2,
                "time" to -3,
                "temperature" to -1
            )
        )
        val area = PhysicalDimension(mapOf("length" to 2))
        val opportunity = opportunity(
            inputDimension = powerPerTemperature,
            outputDimension = powerPerTemperature,
            additionalInputs = listOf(
                TypedField(
                    "coefficients",
                    TypedFieldType.ARRAY,
                    "Power-per-temperature coefficients.",
                    itemType = TypedFieldType.NUMBER,
                    minimumItems = 1,
                    maximumItems = 64,
                    physicalDimension = powerPerTemperature
                ),
                TypedField(
                    "area",
                    TypedFieldType.NUMBER,
                    "Measured area.",
                    physicalDimension = area
                )
            ),
            referenceProgram = referenceProgram
        )

        assertEquals(
            "wrong_output_dimension_normalized",
            ToolDslValidator.referenceProgramValidationError(opportunity)
        )
    }

    @Test
    fun `addition comparison and conditional branches reject unequal dimensions`() {
        val length = PhysicalDimension(mapOf("length" to 1))
        val time = PhysicalDimension(mapOf("time" to 1))
        val opportunity = opportunity(
            inputDimension = length,
            outputDimension = length,
            additionalInputs = listOf(
                TypedField(
                    "duration",
                    TypedFieldType.NUMBER,
                    "Measured duration.",
                    physicalDimension = time
                )
            )
        )

        assertEquals(
            "invalid_expression_normalized",
            ToolDslValidator.validationError(
                formula(
                    ToolDslExpression.Binary(
                        ToolDslBinaryOperation.ADD,
                        ToolDslExpression.Input("value"),
                        ToolDslExpression.Input("duration")
                    )
                ),
                opportunity
            )
        )
        assertEquals(
            "invalid_expression_normalized",
            ToolDslValidator.validationError(
                formula(
                    ToolDslExpression.Conditional(
                        condition = ToolDslExpression.Binary(
                            ToolDslBinaryOperation.LESS_THAN,
                            ToolDslExpression.Input("value"),
                            ToolDslExpression.Input("duration")
                        ),
                        whenTrue = ToolDslExpression.Input("value"),
                        whenFalse = ToolDslExpression.Input("duration")
                    )
                ),
                opportunity
            )
        )
    }

    @Test
    fun `random bounds reject unequal dimensions`() {
        val length = PhysicalDimension(mapOf("length" to 1))
        val time = PhysicalDimension(mapOf("time" to 1))
        val program = ToolDslProgram(
            kind = ToolDslKind.SIMULATION,
            randomSeedInputField = "seed",
            outputs = listOf(
                ToolDslOutput(
                    "sample",
                    ToolDslExpression.RandomUniform(
                        minimum = ToolDslExpression.Input("minimum"),
                        maximum = ToolDslExpression.Input("maximum")
                    )
                ),
                ToolDslOutput("summary", ToolDslExpression.Literal(JsonPrimitive("summary"))),
                ToolDslOutput(
                    "uncertainty",
                    ToolDslExpression.Literal(JsonPrimitive("uncertainty"))
                )
            )
        )
        val opportunity = ToolOpportunity(
            opportunityId = "simulation-opportunity",
            opportunityType = ToolOpportunityType.SIMULATION,
            repeatedQuestionPattern = "Draw one bounded sample.",
            requiredInputs = listOf(
                TypedField(
                    "minimum",
                    TypedFieldType.NUMBER,
                    "Minimum bound.",
                    physicalDimension = length
                ),
                TypedField(
                    "maximum",
                    TypedFieldType.NUMBER,
                    "Maximum bound.",
                    physicalDimension = time
                ),
                TypedField("seed", TypedFieldType.INTEGER, "Random seed.")
            ),
            requiredOutputs = listOf(
                TypedField(
                    "sample",
                    TypedFieldType.NUMBER,
                    "Sampled value.",
                    physicalDimension = length
                ),
                TypedField("summary", TypedFieldType.STRING, "Result summary."),
                TypedField("uncertainty", TypedFieldType.STRING, "Result uncertainty.")
            ),
            validationStrategy = "Compare seeded boundary cases.",
            expectedReuseNodeIds = setOf(NodeId("node-later")),
            preferredImplementationMode = ToolImplementationMode.SIMULATION_DSL,
            behavior = ToolBehavior.STOCHASTIC,
            promotionTestIds = setOf("seeded-boundary-test"),
            maximumRuntimeMillis = 1_000,
            maximumOutputBytes = 4_096,
            assumptions = listOf("Bounds are finite."),
            simulationRecordBindings = SimulationRecordBindings(
                resultSummaryOutputField = "summary",
                uncertaintyOutputField = "uncertainty",
                randomSeedInputField = "seed"
            )
        )

        assertEquals(
            "invalid_expression_sample",
            ToolDslValidator.validationError(program, opportunity)
        )
    }

    private fun formula(expression: ToolDslExpression) = ToolDslProgram(
        kind = ToolDslKind.FORMULA,
        outputs = listOf(ToolDslOutput("normalized", expression))
    )

    private fun opportunity(
        inputDimension: PhysicalDimension? = null,
        outputDimension: PhysicalDimension? = null,
        additionalInputs: List<TypedField> = emptyList(),
        referenceProgram: ToolDslProgram? = null
    ) = ToolOpportunity(
        opportunityId = "formula-opportunity",
        opportunityType = ToolOpportunityType.FORMULA,
        repeatedQuestionPattern = "Normalize each percentage.",
        requiredInputs = listOf(
            TypedField(
                "value",
                TypedFieldType.NUMBER,
                "Percentage value.",
                physicalDimension = inputDimension
            )
        ) + additionalInputs,
        requiredOutputs = listOf(
            TypedField(
                "normalized",
                TypedFieldType.NUMBER,
                "Normalized value.",
                physicalDimension = outputDimension
            )
        ),
        validationStrategy = "Compare exact boundary values.",
        expectedReuseNodeIds = setOf(NodeId("node-later")),
        preferredImplementationMode = ToolImplementationMode.FORMULA_DSL,
        behavior = ToolBehavior.DETERMINISTIC,
        promotionTestIds = setOf("boundary-test"),
        maximumRuntimeMillis = 1_000,
        maximumOutputBytes = 4_096,
        assumptions = listOf("Inputs are finite."),
        formulaDslReferenceSemantics =
            FormulaDslReferenceSemantics.DIMENSIONAL_ANALYSIS_V1,
        formulaDslReferenceProgram = referenceProgram
    )
}
