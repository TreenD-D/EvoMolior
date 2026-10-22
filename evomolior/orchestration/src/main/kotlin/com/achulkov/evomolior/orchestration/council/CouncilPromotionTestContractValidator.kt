package com.achulkov.evomolior.orchestration.council

import com.achulkov.evomolior.core.atlas.ToolOpportunity
import com.achulkov.evomolior.core.atlas.TypedToolSchemaValidationCode
import com.achulkov.evomolior.core.atlas.TypedToolSchemaValidationFailure
import com.achulkov.evomolior.core.atlas.TypedToolSchemaCompiler
import com.achulkov.evomolior.core.atlas.TypedToolSchemaValidator
import com.achulkov.evomolior.core.council.SealedTestDraft
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** One exact Council promotion fixture proven against its sealed tool schemas. */
internal data class CouncilPromotionTestFixture(
    val testId: String,
    val input: JsonElement,
    val expectedOutput: JsonElement
)

/** One deterministic promotion-fixture contract failure. */
internal enum class CouncilPromotionTestComponent {
    DEFINITION,
    INPUT,
    EXPECTED_OUTPUT
}

/** One deterministic promotion-fixture contract failure. */
internal data class CouncilPromotionTestContractFailure(
    val testId: String,
    val component: CouncilPromotionTestComponent,
    val message: String
)

/** Complete generic validation result for one tool opportunity's committed fixtures. */
internal data class CouncilPromotionTestContractValidation(
    val fixtures: List<CouncilPromotionTestFixture>,
    val failures: List<CouncilPromotionTestContractFailure>
)

/**
 * Proves every Council promotion fixture against the exact schemas used in production.
 *
 * This check is implementation-mode independent. FORMULA_DSL adds reference execution only after
 * these generic fixture and schema obligations pass.
 */
internal object CouncilPromotionTestContractValidator {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    }

    fun validate(
        opportunity: ToolOpportunity,
        tests: List<SealedTestDraft>
    ): CouncilPromotionTestContractValidation {
        val inputSchema = json.parseToJsonElement(
            TypedToolSchemaCompiler.compile(opportunity.requiredInputs)
        ) as JsonObject
        val outputSchema = json.parseToJsonElement(
            TypedToolSchemaCompiler.compile(opportunity.requiredOutputs)
        ) as JsonObject
        val fixtures = mutableListOf<CouncilPromotionTestFixture>()
        val failures = mutableListOf<CouncilPromotionTestContractFailure>()

        tests.sortedBy(SealedTestDraft::testKey).forEach { test ->
            val definition = runCatching {
                json.parseToJsonElement(test.testDefinitionJson) as? JsonObject
            }.getOrNull()
            if (definition == null) {
                failures += failure(
                    test,
                    CouncilPromotionTestComponent.DEFINITION,
                    "test definition must be one JSON object"
                )
                return@forEach
            }
            val unexpectedFields = definition.keys - TEST_FIELDS
            if (unexpectedFields.isNotEmpty()) {
                failures += failure(
                    test,
                    CouncilPromotionTestComponent.DEFINITION,
                    "test definition contains unsupported fields: " +
                        unexpectedFields.sorted().joinToString(",")
                )
                return@forEach
            }
            val input = definition["input"]
            if (input == null) {
                failures += failure(
                    test,
                    CouncilPromotionTestComponent.INPUT,
                    "test definition requires exact input"
                )
                return@forEach
            }
            val expectedOutput = definition["expectedOutput"]
            if (expectedOutput == null) {
                failures += failure(
                    test,
                    CouncilPromotionTestComponent.EXPECTED_OUTPUT,
                    "test definition requires exact expectedOutput"
                )
                return@forEach
            }
            val inputFailure = TypedToolSchemaValidator.validationFailure(input, inputSchema)
            val outputFailure =
                TypedToolSchemaValidator.validationFailure(expectedOutput, outputSchema)
                    ?: TypedToolSchemaValidator.canonicalOutputFailure(
                        expectedOutput,
                        outputSchema
                    )
            if (inputFailure != null) {
                failures += failure(
                    test,
                    CouncilPromotionTestComponent.INPUT,
                    "input does not match the sealed schema: " +
                        inputFailure.repairDiagnostic() +
                        validCounterpartGuidance(
                            counterpartName = "expectedOutput",
                            counterpartFailure = outputFailure
                        )
                )
            }
            if (outputFailure != null) {
                failures += failure(
                    test,
                    CouncilPromotionTestComponent.EXPECTED_OUTPUT,
                    "expectedOutput does not match the sealed schema: " +
                        outputFailure.repairDiagnostic() +
                        validCounterpartGuidance(
                            counterpartName = "input",
                            counterpartFailure = inputFailure
                        )
                )
            }
            if (inputFailure != null || outputFailure != null) {
                return@forEach
            }
            fixtures += CouncilPromotionTestFixture(
                testId = test.testKey,
                input = input,
                expectedOutput = expectedOutput
            )
        }
        return CouncilPromotionTestContractValidation(
            fixtures = fixtures,
            failures = failures
        )
    }

    private fun failure(
        test: SealedTestDraft,
        component: CouncilPromotionTestComponent,
        message: String
    ): CouncilPromotionTestContractFailure =
        CouncilPromotionTestContractFailure(test.testKey, component, message)

    private fun TypedToolSchemaValidationFailure.repairDiagnostic(): String {
        val cardinalityDetails = when (code) {
            TypedToolSchemaValidationCode.TOO_FEW_ITEMS -> {
                val actual = requireNotNull(actualItemCount)
                val minimum = requireNotNull(minimumItemCount)
                "actualItems=$actual, minimumItems=$minimum, missingItems=${minimum - actual}"
            }
            TypedToolSchemaValidationCode.TOO_MANY_ITEMS -> {
                val actual = requireNotNull(actualItemCount)
                val maximum = requireNotNull(maximumItemCount)
                "actualItems=$actual, maximumItems=$maximum, excessItems=${actual - maximum}"
            }
            else -> null
        }
        return cardinalityDetails?.let { "$stableCode ($it)" } ?: stableCode
    }

    private fun validCounterpartGuidance(
        counterpartName: String,
        counterpartFailure: TypedToolSchemaValidationFailure?
    ): String = if (counterpartFailure == null) {
        "; $counterpartName already satisfies its sealed schema and must be copied unchanged " +
            "from previousOutput"
    } else {
        ""
    }

    private val TEST_FIELDS = setOf("input", "expectedOutput")
}
