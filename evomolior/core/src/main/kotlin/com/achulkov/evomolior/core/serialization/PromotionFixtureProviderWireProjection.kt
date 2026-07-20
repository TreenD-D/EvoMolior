package com.achulkov.evomolior.core.serialization

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

internal sealed interface PromotionFixtureWireProjection {
    data class Success(val element: JsonElement) : PromotionFixtureWireProjection
    data class Failure(val issue: CouncilSynthesisWireIssue) : PromotionFixtureWireProjection
}

/**
 * Projects exact promotion fixtures between the generic tagged JSON codec and provider wire.
 *
 * Exact object schemas use a closed property map so required keys are machine-required exactly
 * once. Optional properties remain explicit on provider wire through ABSENT/PRESENT wrappers.
 * The persisted/domain representation remains ordinary JSON and the generic tagged codec remains
 * unchanged.
 */
internal object PromotionFixtureProviderWireProjector {
    fun encode(
        element: JsonElement,
        schema: JsonObject,
        path: List<String>
    ): PromotionFixtureWireProjection = project(
        element = element,
        schema = schema,
        path = path,
        direction = ProjectionDirection.ENCODE
    )

    fun normalize(
        element: JsonElement,
        schema: JsonObject,
        path: List<String>
    ): PromotionFixtureWireProjection = project(
        element = element,
        schema = schema,
        path = path,
        direction = ProjectionDirection.NORMALIZE
    )

    private fun project(
        element: JsonElement,
        schema: JsonObject,
        path: List<String>,
        direction: ProjectionDirection
    ): PromotionFixtureWireProjection {
        val expectedType = (schema["type"] as? JsonPrimitive)?.content
            ?: return failure(path, "Task-derived promotion schema is missing type")
        val tagged = element as? JsonObject
            ?: return if (direction == ProjectionDirection.NORMALIZE) {
                PromotionFixtureWireProjection.Success(element)
            } else {
                failure(path, "Expected one tagged '$expectedType' value")
            }
        val actualType = (tagged["type"] as? JsonPrimitive)
            ?.takeIf(JsonPrimitive::isString)
            ?.content
            ?: return failure(path + "type", "Expected a tagged-value discriminator string")

        // Wrong roots stay in generic tagged form so exact input and output failures can be
        // reported conjunctively by TypedToolSchemaValidator after normalization.
        if (actualType != expectedType) {
            return if (direction == ProjectionDirection.NORMALIZE) {
                PromotionFixtureWireProjection.Success(element)
            } else {
                failure(path + "type", "Expected tagged '$expectedType', found '$actualType'")
            }
        }
        val expectedFields = when (expectedType) {
            "object" -> setOf("type", "entries")
            "array" -> setOf("type", "values")
            "string", "integer", "number", "boolean" -> setOf("type", "value")
            else -> emptySet()
        }
        tagged.keys.sorted().firstOrNull { it !in expectedFields }?.let {
            return failure(path + it, "Unknown tagged-value field")
        }
        expectedFields.sorted().firstOrNull { it !in tagged }?.let {
            return failure(path + it, "Required tagged-value field is missing")
        }
        return when (expectedType) {
            "object" -> projectObject(tagged, schema, path, direction)
            "array" -> projectArray(tagged, schema, path, direction)
            "string", "integer", "number", "boolean" ->
                PromotionFixtureWireProjection.Success(element)
            else -> failure(path, "Unsupported Task-derived promotion schema type '$expectedType'")
        }
    }

    private fun projectArray(
        tagged: JsonObject,
        schema: JsonObject,
        path: List<String>,
        direction: ProjectionDirection
    ): PromotionFixtureWireProjection {
        val values = tagged["values"] as? JsonArray
            ?: return failure(path + "values", "Expected a tagged array values list")
        val itemSchema = schema["items"] as? JsonObject
            ?: return failure(path, "Task-derived array schema is missing items")
        val projected = ArrayList<JsonElement>(values.size)
        values.forEachIndexed { index, value ->
            when (
                val result = project(
                    element = value,
                    schema = itemSchema,
                    path = path + listOf("values", index.toString()),
                    direction = direction
                )
            ) {
                is PromotionFixtureWireProjection.Failure -> return result
                is PromotionFixtureWireProjection.Success -> projected += result.element
            }
        }
        return PromotionFixtureWireProjection.Success(
            JsonObject(tagged + ("values" to JsonArray(projected)))
        )
    }

    private fun projectObject(
        tagged: JsonObject,
        schema: JsonObject,
        path: List<String>,
        direction: ProjectionDirection
    ): PromotionFixtureWireProjection {
        val properties = schema["properties"] as? JsonObject
            ?: return failure(path, "Task-derived object schema is missing properties")
        val required = (schema["required"] as? JsonArray)
            ?.mapTo(linkedSetOf()) { it.jsonPrimitive.content }
            ?: return failure(path, "Task-derived object schema is missing required")
        return when (direction) {
            ProjectionDirection.ENCODE ->
                encodeObject(tagged, properties, required, path)
            ProjectionDirection.NORMALIZE ->
                normalizeObject(tagged, properties, required, path)
        }
    }

    private fun encodeObject(
        tagged: JsonObject,
        properties: JsonObject,
        required: Set<String>,
        path: List<String>
    ): PromotionFixtureWireProjection {
        val entries = tagged["entries"] as? JsonArray
            ?: return failure(path + "entries", "Expected generic tagged object entries")
        val values = linkedMapOf<String, JsonElement>()
        entries.forEachIndexed { index, entryElement ->
            val entry = entryElement as? JsonObject
                ?: return failure(
                    path + listOf("entries", index.toString()),
                    "Expected a generic tagged object entry"
                )
            val key = (entry["key"] as? JsonPrimitive)
                ?.takeIf(JsonPrimitive::isString)
                ?.content
                ?: return failure(
                    path + listOf("entries", index.toString(), "key"),
                    "Expected an object-entry key string"
                )
            val value = entry["value"]
                ?: return failure(
                    path + listOf("entries", index.toString(), "value"),
                    "Required field is missing"
                )
            if (values.put(key, value) != null) {
                return failure(
                    path + listOf("entries", index.toString(), "key"),
                    "Promotion object property '$key' is repeated"
                )
            }
        }
        values.keys.sorted().firstOrNull { it !in properties }?.let {
            return failure(path + listOf("entries", it), "Additional promotion object property")
        }
        required.sorted().firstOrNull { it !in values }?.let {
            return failure(path + listOf("entries", it), "Required promotion object property is missing")
        }

        val providerEntries = linkedMapOf<String, JsonElement>()
        properties.entries.sortedBy { it.key }.forEach { (name, definition) ->
            val value = values[name]
            if (value == null) {
                providerEntries[name] = JsonObject(
                    mapOf("state" to JsonPrimitive(OPTIONAL_STATE_ABSENT))
                )
            } else {
                when (
                    val result = encode(
                        value,
                        definition as? JsonObject
                            ?: return failure(
                                path + listOf("entries", name),
                                "Invalid Task-derived property schema"
                            ),
                        path + listOf("entries", name)
                    )
                ) {
                    is PromotionFixtureWireProjection.Failure -> return result
                    is PromotionFixtureWireProjection.Success -> {
                        providerEntries[name] = if (name in required) {
                            result.element
                        } else {
                            JsonObject(
                                linkedMapOf(
                                    "state" to JsonPrimitive(OPTIONAL_STATE_PRESENT),
                                    "value" to result.element
                                )
                            )
                        }
                    }
                }
            }
        }
        return PromotionFixtureWireProjection.Success(
            JsonObject(
                linkedMapOf(
                    "type" to JsonPrimitive("object"),
                    "entries" to JsonObject(providerEntries)
                )
            )
        )
    }

    private fun normalizeObject(
        tagged: JsonObject,
        properties: JsonObject,
        required: Set<String>,
        path: List<String>
    ): PromotionFixtureWireProjection {
        val entries = tagged["entries"] as? JsonObject
            ?: return failure(
                path + "entries",
                "Expected exact promotion object property map"
            )
        entries.keys.sorted().firstOrNull { it !in properties }?.let {
            return failure(path + listOf("entries", it), "Additional promotion object property")
        }
        properties.keys.sorted().firstOrNull { it !in entries }?.let {
            return failure(path + listOf("entries", it), "Promotion object property is missing")
        }

        val genericEntries = mutableListOf<JsonElement>()
        properties.entries.sortedBy { it.key }.forEach { (name, definitionElement) ->
            val definition = definitionElement as? JsonObject
                ?: return failure(
                    path + listOf("entries", name),
                    "Invalid Task-derived property schema"
                )
            val providerValue = entries.getValue(name)
            val value = if (name in required) {
                providerValue
            } else {
                when (
                    val optional = normalizeOptional(
                        providerValue,
                        path + listOf("entries", name)
                    )
                ) {
                    is OptionalProjection.Failure -> return optional.result
                    OptionalProjection.Absent -> null
                    is OptionalProjection.Present -> optional.value
                }
            }
            if (value != null) {
                when (
                    val result = normalize(
                        value,
                        definition,
                        path + listOf("entries", name)
                    )
                ) {
                    is PromotionFixtureWireProjection.Failure -> return result
                    is PromotionFixtureWireProjection.Success -> {
                        genericEntries += JsonObject(
                            linkedMapOf(
                                "key" to JsonPrimitive(name),
                                "value" to result.element
                            )
                        )
                    }
                }
            }
        }
        return PromotionFixtureWireProjection.Success(
            JsonObject(
                linkedMapOf(
                    "type" to JsonPrimitive("object"),
                    "entries" to JsonArray(genericEntries)
                )
            )
        )
    }

    private fun normalizeOptional(
        element: JsonElement,
        path: List<String>
    ): OptionalProjection {
        val wrapper = element as? JsonObject
            ?: return OptionalProjection.Failure(
                failure(path, "Expected an ABSENT/PRESENT optional-property wrapper")
            )
        val state = (wrapper["state"] as? JsonPrimitive)
            ?.takeIf(JsonPrimitive::isString)
            ?.content
            ?: return OptionalProjection.Failure(
                failure(path + "state", "Expected optional-property state string")
            )
        return when (state) {
            OPTIONAL_STATE_ABSENT -> {
                if (wrapper.keys != setOf("state")) {
                    OptionalProjection.Failure(
                        failure(path, "ABSENT optional-property wrapper must contain only state")
                    )
                } else {
                    OptionalProjection.Absent
                }
            }
            OPTIONAL_STATE_PRESENT -> {
                if (wrapper.keys != setOf("state", "value")) {
                    OptionalProjection.Failure(
                        failure(
                            path,
                            "PRESENT optional-property wrapper must contain state and value"
                        )
                    )
                } else {
                    OptionalProjection.Present(wrapper.getValue("value"))
                }
            }
            else -> OptionalProjection.Failure(
                failure(path + "state", "Unknown optional-property state '$state'")
            )
        }
    }

    private fun failure(
        path: List<String>,
        message: String
    ): PromotionFixtureWireProjection.Failure =
        PromotionFixtureWireProjection.Failure(CouncilSynthesisWireIssue(path, message))

    private enum class ProjectionDirection {
        ENCODE,
        NORMALIZE
    }

    private sealed interface OptionalProjection {
        data object Absent : OptionalProjection
        data class Present(val value: JsonElement) : OptionalProjection
        data class Failure(val result: PromotionFixtureWireProjection.Failure) :
            OptionalProjection
    }

    private const val OPTIONAL_STATE_ABSENT = "ABSENT"
    private const val OPTIONAL_STATE_PRESENT = "PRESENT"
}
