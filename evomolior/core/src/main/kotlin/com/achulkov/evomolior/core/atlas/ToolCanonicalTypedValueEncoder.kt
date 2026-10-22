package com.achulkov.evomolior.core.atlas

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Canonical runtime encoder for model-authored values governed by a sealed typed schema.
 *
 * NUMBER values are emitted with finite `Double.toString()` tokens; INTEGER values remain integer
 * tokens. The same recursive encoder is used for public tests and author-supplied local values.
 */
object ToolCanonicalTypedValueEncoder {
    private val strictJson = Json {
        isLenient = false
        ignoreUnknownKeys = false
    }

    /**
     * Parses and canonically encodes one model-authored JSON value.
     *
     * @param authoredJson Strict model-authored JSON.
     * @param schema Exact sealed schema.
     * @return Canonical typed JSON value.
     */
    fun parseAndEncode(authoredJson: String, schema: JsonObject): JsonElement =
        encode(strictJson.parseToJsonElement(authoredJson), schema)

    /**
     * Canonically encodes [value] according to [schema].
     *
     * @param value Parsed model-authored value.
     * @param schema Exact sealed schema.
     * @return Canonical typed JSON value.
     */
    fun encode(value: JsonElement, schema: JsonObject): JsonElement {
        TypedToolSchemaValidator.validationFailure(value, schema)?.let { failure ->
            throw ToolCanonicalValueException(
                failure.code.name.lowercase(),
                failure.path
            )
        }
        val type = schema["type"]?.jsonPrimitive?.contentOrNull
            ?: throw ToolCanonicalValueException("missing_schema_type", "root")
        return encodeAt(value, schema, type, "root")
    }

    /** Returns canonical compact JSON bytes for [value] under [schema]. */
    fun encodeToString(value: JsonElement, schema: JsonObject): String =
        AtlasHashing.canonicalJson(encode(value, schema))

    private fun encodeAt(
        value: JsonElement,
        schema: JsonObject,
        type: String,
        path: String
    ): JsonElement = when (type) {
        "number" -> {
            val primitive = value as? JsonPrimitive
                ?: throw ToolCanonicalValueException("wrong_type", path)
            if (primitive.isString) throw ToolCanonicalValueException("wrong_type", path)
            val number = primitive.content.toDoubleOrNull()
                ?: throw ToolCanonicalValueException("wrong_type", path)
            if (!number.isFinite()) {
                throw ToolCanonicalValueException("non_finite_number", path)
            }
            JsonPrimitive(number)
        }
        "integer" -> {
            val primitive = value as? JsonPrimitive
                ?: throw ToolCanonicalValueException("wrong_type", path)
            if (primitive.isString) throw ToolCanonicalValueException("wrong_type", path)
            val integer = primitive.longOrNull
                ?: throw ToolCanonicalValueException("wrong_type", path)
            JsonPrimitive(integer)
        }
        "string" -> {
            val primitive = value as? JsonPrimitive
                ?: throw ToolCanonicalValueException("wrong_type", path)
            if (!primitive.isString) throw ToolCanonicalValueException("wrong_type", path)
            primitive
        }
        "boolean" -> {
            val primitive = value as? JsonPrimitive
                ?: throw ToolCanonicalValueException("wrong_type", path)
            val boolean = primitive.booleanOrNull
                ?: throw ToolCanonicalValueException("wrong_type", path)
            JsonPrimitive(boolean)
        }
        "array" -> {
            val array = value as? JsonArray
                ?: throw ToolCanonicalValueException("wrong_type", path)
            val itemSchema = schema["items"] as? JsonObject
                ?: throw ToolCanonicalValueException("missing_items_schema", path)
            val itemType = itemSchema["type"]?.jsonPrimitive?.contentOrNull
                ?: throw ToolCanonicalValueException("missing_schema_type", "$path[]")
            JsonArray(
                array.mapIndexed { index, item ->
                    encodeAt(item, itemSchema, itemType, "$path[$index]")
                }
            )
        }
        "object" -> {
            val obj = value as? JsonObject
                ?: throw ToolCanonicalValueException("wrong_type", path)
            val properties = schema["properties"] as? JsonObject
                ?: return JsonObject(obj.entries.sortedBy { it.key }.associate { it.toPair() })
            if (schema["additionalProperties"]?.jsonPrimitive?.booleanOrNull == false) {
                obj.keys.firstOrNull { it !in properties }?.let { unexpected ->
                    throw ToolCanonicalValueException(
                        "additional_property",
                        "$path.$unexpected"
                    )
                }
            }
            val required = (schema["required"] as? JsonArray)
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                .orEmpty()
            required.firstOrNull { it !in obj }?.let { missing ->
                throw ToolCanonicalValueException("missing_property", "$path.$missing")
            }
            JsonObject(
                obj.entries.sortedBy { it.key }.associate { (name, child) ->
                    val childSchema = properties[name] as? JsonObject
                    if (childSchema == null) {
                        name to child
                    } else {
                        val childType = childSchema["type"]?.jsonPrimitive?.contentOrNull
                            ?: throw ToolCanonicalValueException(
                                "missing_schema_type",
                                "$path.$name"
                            )
                        name to encodeAt(child, childSchema, childType, "$path.$name")
                    }
                }
            )
        }
        else -> throw ToolCanonicalValueException("unsupported_schema_type", path)
    }
}

/**
 * Stable canonical typed-value failure.
 *
 * @param code Bounded machine-readable failure code.
 * @param path Exact public value path.
 */
class ToolCanonicalValueException(
    val code: String,
    val path: String
) : IllegalArgumentException("$code at $path")
