package com.achulkov.evomolior.orchestration.lifecycle

import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.ToolDslProgram
import com.achulkov.evomolior.core.atlas.ToolDslValidator
import com.achulkov.evomolior.core.atlas.ToolImplementationMode
import com.achulkov.evomolior.core.atlas.ToolOpportunityCommitment
import com.achulkov.evomolior.core.atlas.TypedToolSchemaCompiler
import com.achulkov.evomolior.core.atlas.TypedToolSchemaValidator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Pure validation of a staged tool payload against its exact sealed Atlas opportunity.
 *
 * Both inline worker preflight and post-Critic promotion use this validator so a mortal worker can
 * repair every deterministic contract failure before its lifecycle ends.
 */
internal class ToolPromotionContractValidator(
    private val json: Json = StrictToolPayloadJson
) {
    fun validate(
        payload: JsonObject,
        commitment: ToolOpportunityCommitment
    ): ToolPromotionContractValidationResult {
        val unsupportedPayloadFields = payload.keys - PAYLOAD_FIELDS
        if (unsupportedPayloadFields.isNotEmpty()) {
            return rejected(
                "unsupported_payload_fields:" +
                    unsupportedPayloadFields.sorted().joinToString(",")
            )
        }
        val missingPayloadFields = PAYLOAD_FIELDS - payload.keys
        if (missingPayloadFields.isNotEmpty()) {
            return rejected(
                "missing_payload_fields:" +
                    missingPayloadFields.sorted().joinToString(",")
            )
        }
        val opportunity = commitment.opportunity
        if ((payload["opportunityId"] as? JsonPrimitive)?.contentOrNull !=
            opportunity.opportunityId
        ) {
            return rejected("opportunity_id_mismatch")
        }
        val name = (payload["name"] as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.matches(TOOL_NAME_PATTERN) }
            ?: return rejected("invalid_name")
        val domain = (payload["domain"] as? JsonPrimitive)?.contentOrNull
            ?.takeIf(String::isNotBlank)
            ?: return rejected("invalid_domain")
        val language = (payload["language"] as? JsonPrimitive)?.contentOrNull?.lowercase()
            ?: return rejected("missing_language")
        if (language != opportunity.preferredImplementationMode.runtimeLanguage) {
            return rejected("language_mismatch")
        }
        val submittedCode = (payload["code"] as? JsonPrimitive)?.contentOrNull
            ?.takeIf(String::isNotBlank)
            ?: return rejected("missing_code")
        val code = if (language == "dsl") {
            val program = runCatching {
                json.decodeFromString(ToolDslProgram.serializer(), submittedCode)
            }.getOrNull() ?: return rejected("invalid_dsl")
            ToolDslValidator.validationError(program, opportunity)?.let { reason ->
                return rejected("dsl_validation_failed:$reason")
            }
            json.encodeToString(ToolDslProgram.serializer(), program)
        } else {
            submittedCode
        }
        val inputSchema = payload["inputSchema"] as? JsonObject
            ?: return rejected("missing_input_schema")
        val outputSchema = payload["outputSchema"] as? JsonObject
            ?: return rejected("missing_output_schema")
        if (AtlasHashing.canonicalJson(inputSchema) !=
            TypedToolSchemaCompiler.compile(opportunity.requiredInputs) ||
            AtlasHashing.canonicalJson(outputSchema) !=
            TypedToolSchemaCompiler.compile(opportunity.requiredOutputs)
        ) {
            return rejected("schema_mismatch")
        }
        val testElements = payload["tests"] as? JsonArray
            ?: return rejected("missing_tests")
        val tests = buildList {
            testElements.forEachIndexed { index, element ->
                val value = element as? JsonObject
                    ?: return rejected("invalid_test_case:$index")
                val unexpectedFields = value.keys - TEST_FIELDS
                if (unexpectedFields.isNotEmpty()) {
                    return rejected(
                        "unsupported_test_fields:$index:" +
                            unexpectedFields.sorted().joinToString(",")
                    )
                }
                val id = (value["testId"] as? JsonPrimitive)?.contentOrNull
                    ?: return rejected("missing_test_id:$index")
                val input = value["input"]
                    ?: return rejected("missing_test_input:$index")
                TypedToolSchemaValidator.validationError(input, inputSchema)?.let { reason ->
                    return rejected("test_input_invalid:$index:$reason")
                }
                val expectedOutput = value["expectedOutput"]
                    ?: return rejected("missing_expected_output:$index")
                TypedToolSchemaValidator.validationError(
                    expectedOutput,
                    outputSchema
                )?.let { reason ->
                    return rejected("test_expected_output_invalid:$index:$reason")
                }
                add(ValidatedPromotionTest(id, input, expectedOutput))
            }
        }
        if (tests.size != opportunity.promotionTestIds.size ||
            tests.mapTo(linkedSetOf(), ValidatedPromotionTest::id) !=
            opportunity.promotionTestIds
        ) {
            return rejected("tests_mismatch")
        }
        return ToolPromotionContractValidationResult.Accepted(
            ValidatedToolPromotionContract(
                name = name,
                domain = domain,
                language = language,
                code = code,
                inputSchema = inputSchema,
                outputSchema = outputSchema,
                tests = tests
            )
        )
    }

    private fun rejected(reason: String): ToolPromotionContractValidationResult.Rejected =
        ToolPromotionContractValidationResult.Rejected("tool_promotion_contract_$reason")

    private val ToolImplementationMode.runtimeLanguage: String
        get() = when (this) {
            ToolImplementationMode.SHELL -> "shell"
            ToolImplementationMode.PYTHON -> "python"
            ToolImplementationMode.FORMULA_DSL,
            ToolImplementationMode.RULE_DSL,
            ToolImplementationMode.SIMULATION_DSL -> "dsl"
        }

    private companion object {
        val PAYLOAD_FIELDS = setOf(
            "opportunityId",
            "name",
            "domain",
            "language",
            "inputSchema",
            "outputSchema",
            "code",
            "tests"
        )
        val TEST_FIELDS = setOf("testId", "input", "expectedOutput")
        val TOOL_NAME_PATTERN = Regex("[A-Za-z0-9_.-]{1,80}")
    }
}

internal val StrictToolPayloadJson: Json = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
    isLenient = false
}

internal data class ValidatedToolPromotionContract(
    val name: String,
    val domain: String,
    val language: String,
    val code: String,
    val inputSchema: JsonObject,
    val outputSchema: JsonObject,
    val tests: List<ValidatedPromotionTest>
)

internal data class ValidatedPromotionTest(
    val id: String,
    val input: JsonElement,
    val expectedOutput: JsonElement
)

internal sealed interface ToolPromotionContractValidationResult {
    data class Accepted(
        val tool: ValidatedToolPromotionContract
    ) : ToolPromotionContractValidationResult

    data class Rejected(
        val reason: String
    ) : ToolPromotionContractValidationResult
}
