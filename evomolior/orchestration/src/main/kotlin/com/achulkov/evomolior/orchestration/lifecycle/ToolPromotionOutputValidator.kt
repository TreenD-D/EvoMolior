package com.achulkov.evomolior.orchestration.lifecycle

import com.achulkov.evomolior.core.atlas.TypedToolSchemaValidator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Parsed output and exact validation result from one production promotion-test execution.
 *
 * @param output Parsed JSON output, or null when stdout is not JSON.
 * @param failure Stable validation failure, or null when schema and exact output both pass.
 * @param expectedOutput Exact expected JSON when committed by the test.
 */
internal data class ToolPromotionOutputValidation(
    val output: JsonElement?,
    val failure: String?,
    val failureClass: ToolPromotionOutputFailure?,
    val expectedOutput: JsonElement?
)

/** Closed output-validation class retained without private expected/actual values. */
internal enum class ToolPromotionOutputFailure {
    OUTPUT_NOT_JSON,
    SCHEMA_MISMATCH,
    NON_CANONICAL_REPRESENTATION,
    EXPECTED_OUTPUT_MISMATCH
}

/** Shared strict output semantics for Council reference tests and production tool promotion. */
internal object ToolPromotionOutputValidator {
    /**
     * Parses and validates stdout without numeric coercion or representation repair.
     *
     * @param stdout Exact executable stdout.
     * @param outputSchema Sealed output schema.
     * @param expectedOutput Optional exact expected JSON.
     * @param json Strict JSON parser.
     * @return Parsed output and stable failure details.
     */
    fun validate(
        stdout: String,
        outputSchema: JsonObject,
        expectedOutput: JsonElement?,
        json: Json
    ): ToolPromotionOutputValidation {
        val output = runCatching {
            json.parseToJsonElement(stdout.trim())
        }.getOrNull()
        val schemaFailure = if (output == null) {
            "output_not_json"
        } else {
            TypedToolSchemaValidator.validationError(output, outputSchema)
        }
        val representationFailure = if (schemaFailure == null && output != null) {
            TypedToolSchemaValidator.canonicalOutputFailure(output, outputSchema)?.stableCode
        } else {
            null
        }
        val exactFailure = if (
            schemaFailure == null &&
            representationFailure == null &&
            expectedOutput != null &&
            output != expectedOutput
        ) {
            "unexpected_output"
        } else {
            null
        }
        return ToolPromotionOutputValidation(
            output = output,
            failure = schemaFailure ?: representationFailure ?: exactFailure,
            failureClass = when {
                output == null -> ToolPromotionOutputFailure.OUTPUT_NOT_JSON
                schemaFailure != null -> ToolPromotionOutputFailure.SCHEMA_MISMATCH
                representationFailure != null ->
                    ToolPromotionOutputFailure.NON_CANONICAL_REPRESENTATION
                exactFailure != null -> ToolPromotionOutputFailure.EXPECTED_OUTPUT_MISMATCH
                else -> null
            },
            expectedOutput = expectedOutput
        )
    }
}
