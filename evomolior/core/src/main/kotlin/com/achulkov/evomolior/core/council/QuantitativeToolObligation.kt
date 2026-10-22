package com.achulkov.evomolior.core.council

import com.achulkov.evomolior.core.atlas.PhysicalDimension
import com.achulkov.evomolior.core.atlas.ToolOpportunityType
import com.achulkov.evomolior.core.atlas.TypedFieldType
import com.achulkov.evomolior.core.repair.QuantitySpec
import com.achulkov.evomolior.core.repair.TimeBasis
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Location of one operator-required quantitative field in a tool contract. */
@Serializable
enum class QuantitativeToolFieldLocation {
    INPUT,
    OUTPUT
}

/** Structural shape required for one operator-owned quantitative field role. */
@Serializable
enum class QuantitativeToolFieldShape {
    SCALAR,
    HORIZON_SERIES
}

/**
 * Exact typed field role that one sealed tool opportunity must expose.
 *
 * Field names are immutable schema authority rather than prose hints. A horizon-series field must
 * be a required numeric array whose exact bounds equal the obligation horizon.
 *
 * @param roleId Stable operator-owned semantic role identifier.
 * @param fieldName Exact top-level tool-contract field name.
 * @param location Whether the field belongs to the input or output contract.
 * @param shape Scalar or one value per declared horizon interval.
 * @param numericType Required scalar or array-item numeric type.
 * @param quantitySpec Exact physical dimension, canonical unit, aggregation, and time basis.
 */
@Serializable
data class QuantitativeToolFieldObligation(
    val roleId: String,
    val fieldName: String,
    val location: QuantitativeToolFieldLocation,
    val shape: QuantitativeToolFieldShape,
    val numericType: TypedFieldType = TypedFieldType.NUMBER,
    val quantitySpec: QuantitySpec
) {
    init {
        require(roleId.matches(STABLE_ID)) {
            "Quantitative tool field roleId must be a stable identifier"
        }
        require(fieldName.matches(STABLE_ID)) {
            "Quantitative tool fieldName must be a JSON-compatible identifier"
        }
        require(numericType in setOf(TypedFieldType.INTEGER, TypedFieldType.NUMBER)) {
            "Quantitative tool field numericType must be INTEGER or NUMBER"
        }
    }

    private companion object {
        val STABLE_ID = Regex("[A-Za-z_][A-Za-z0-9_]*")
    }
}

/**
 * Exact operator-owned promotion fixture for one quantitative-tool boundary.
 *
 * The complete execution envelope commits these values. Council synthesis must reproduce them
 * exactly; production promotion then resolves and executes the separately sealed compiled copy.
 *
 * @param testId Exact precommitted promotion-test ID.
 * @param input Exact typed fixture input object.
 * @param expectedOutput Exact typed expected output object.
 */
@Serializable
data class QuantitativePromotionFixtureObligation(
    val testId: String,
    val input: JsonElement,
    val expectedOutput: JsonElement
) {
    init {
        require(testId.isNotBlank()) {
            "Quantitative promotion fixture test ID must not be blank"
        }
        require(input is JsonObject) {
            "Quantitative promotion fixture input must be a JSON object"
        }
        require(expectedOutput is JsonObject) {
            "Quantitative promotion fixture expected output must be a JSON object"
        }
    }
}

/**
 * Immutable operator-owned obligation for one bounded quantitative tool contract.
 *
 * The obligation is optional so generic Appendix-B runs can continue to seal non-physics tools.
 * When present, every required role must be satisfied by the same Council-authored opportunity.
 *
 * @param horizonTimeBasis Time basis shared by all horizon-series field roles.
 * @param horizonIntervalCount Exact positive number of intervals in the bounded horizon.
 * @param allowedOpportunityTypes Accepted repeated-work classifications; empty permits any type.
 * @param requiredOpportunityId Exact opportunity identifier, when operator-owned.
 * @param exactPromotionTestIds Exact promotion-test set, when operator-owned.
 * @param exactPromotionFixtures Exact operator-owned fixture values for the promotion tests.
 * @param maximumOpportunityRuntimeMillis Maximum committed runtime bound, when operator-owned.
 * @param requiredFields Exact input and output field roles required on one opportunity.
 */
@Serializable
data class QuantitativeToolObligation(
    val horizonTimeBasis: TimeBasis,
    val horizonIntervalCount: Int,
    val allowedOpportunityTypes: Set<ToolOpportunityType> = emptySet(),
    val requiredOpportunityId: String? = null,
    val exactPromotionTestIds: Set<String> = emptySet(),
    val exactPromotionFixtures: List<QuantitativePromotionFixtureObligation> = emptyList(),
    val maximumOpportunityRuntimeMillis: Long? = null,
    val requiredFields: List<QuantitativeToolFieldObligation>
) {
    init {
        require(horizonIntervalCount in 1..MAXIMUM_HORIZON_INTERVALS) {
            "Quantitative tool horizon must be within 1..$MAXIMUM_HORIZON_INTERVALS intervals"
        }
        require(requiredFields.isNotEmpty()) {
            "Quantitative tool obligation requires at least one typed field role"
        }
        require(requiredOpportunityId == null || requiredOpportunityId.isNotBlank()) {
            "Quantitative tool required opportunity ID must not be blank"
        }
        require(exactPromotionTestIds.none(String::isBlank)) {
            "Quantitative tool promotion test IDs must not be blank"
        }
        require(
            exactPromotionFixtures.map { it.testId }.distinct().size ==
                exactPromotionFixtures.size
        ) {
            "Quantitative promotion fixture test IDs must be unique"
        }
        require(
            exactPromotionFixtures.isEmpty() ||
                exactPromotionFixtures.mapTo(linkedSetOf()) { it.testId } ==
                exactPromotionTestIds
        ) {
            "Quantitative promotion fixtures must exactly cover the promotion test IDs"
        }
        maximumOpportunityRuntimeMillis?.let {
            require(it > 0) {
                "Quantitative tool maximum opportunity runtime must be positive"
            }
        }
        require(requiredFields.map { it.roleId }.distinct().size == requiredFields.size) {
            "Quantitative tool obligation role IDs must be unique"
        }
        require(
            requiredFields
                .groupBy { it.location }
                .values
                .all { fields -> fields.map { it.fieldName }.distinct().size == fields.size }
        ) {
            "Quantitative tool obligation field names must be unique within each contract side"
        }
        require(
            requiredFields
                .filter { it.shape == QuantitativeToolFieldShape.HORIZON_SERIES }
                .all { it.quantitySpec.timeBasis == horizonTimeBasis }
        ) {
            "Horizon-series quantity time bases must match the obligation horizon"
        }
    }

    private companion object {
        const val MAXIMUM_HORIZON_INTERVALS: Int = 10_000
    }
}

/**
 * Compiles this obligation into the exact Task Anatomist provider-wire fragment that the
 * operator-required tool opportunity must contain.
 *
 * The result deliberately excludes Council-owned implementation, execution, description, and
 * reuse design. Its included opportunity identity, promotion tests, runtime ceiling, and complete
 * input/output arrays are copy-ready immutable authority rather than prose guidance.
 *
 * @return Closed JSON fragment using the executable typed-field provider representation.
 */
fun QuantitativeToolObligation.compileCouncilToolOpportunityAuthority(): JsonObject =
    buildJsonObject {
        requiredOpportunityId?.let { put("opportunityId", JsonPrimitive(it)) }
        if (exactPromotionTestIds.isNotEmpty()) {
            put(
                "promotionTestIds",
                buildJsonArray {
                    exactPromotionTestIds.sorted().forEach { add(JsonPrimitive(it)) }
                }
            )
        }
        maximumOpportunityRuntimeMillis?.let { put("maximumRuntimeMillis", it) }
        put(
            "requiredInputs",
            requiredFields.compileCouncilFields(
                location = QuantitativeToolFieldLocation.INPUT,
                horizonIntervalCount = horizonIntervalCount
            )
        )
        put(
            "requiredOutputs",
            requiredFields.compileCouncilFields(
                location = QuantitativeToolFieldLocation.OUTPUT,
                horizonIntervalCount = horizonIntervalCount
            )
        )
    }

private fun List<QuantitativeToolFieldObligation>.compileCouncilFields(
    location: QuantitativeToolFieldLocation,
    horizonIntervalCount: Int
): JsonArray = buildJsonArray {
    filter { it.location == location }.forEach { field ->
        add(field.compileCouncilField(horizonIntervalCount))
    }
}

private fun QuantitativeToolFieldObligation.compileCouncilField(
    horizonIntervalCount: Int
): JsonObject = buildJsonObject {
    put("name", JsonPrimitive(fieldName))
    put(
        "type",
        JsonPrimitive(
            when (shape) {
                QuantitativeToolFieldShape.SCALAR -> numericType.name
                QuantitativeToolFieldShape.HORIZON_SERIES -> "ARRAY"
            }
        )
    )
    put(
        "description",
        JsonPrimitive("Operator-required quantitative field role '$roleId'.")
    )
    put("required", true)
    put("validationRules", JsonArray(emptyList()))
    when (shape) {
        QuantitativeToolFieldShape.SCALAR -> {
            put(
                "physicalDimension",
                quantitySpec.dimension.toCouncilAuthorityJson()
            )
            put(
                "quantitySpec",
                quantitySpec.toCouncilAuthorityJson()
            )
        }
        QuantitativeToolFieldShape.HORIZON_SERIES -> {
            put("minimumItems", horizonIntervalCount)
            put("maximumItems", horizonIntervalCount)
            put(
                "items",
                buildJsonObject {
                    put("type", JsonPrimitive("${numericType.name}_ITEMS"))
                    put(
                        "physicalDimension",
                        quantitySpec.dimension.toCouncilAuthorityJson()
                    )
                    put(
                        "quantitySpec",
                        quantitySpec.toCouncilAuthorityJson()
                    )
                }
            )
        }
    }
}

private fun PhysicalDimension.toCouncilAuthorityJson(): JsonObject =
    buildJsonObject {
        put(
            "exponents",
            buildJsonArray {
                exponents.entries.sortedBy(Map.Entry<String, Int>::key).forEach { entry ->
                    add(
                        buildJsonObject {
                            put("key", entry.key)
                            put("value", entry.value)
                        }
                    )
                }
            }
        )
    }

private fun QuantitySpec.toCouncilAuthorityJson(): JsonObject =
    buildJsonObject {
        put("dimension", dimension.toCouncilAuthorityJson())
        put("unitId", unitId)
        put("scaleToSi", scaleToSi)
        put("aggregation", aggregation.name)
        put("timeBasis", timeBasis?.let { JsonPrimitive(it.name) } ?: JsonNull)
    }
