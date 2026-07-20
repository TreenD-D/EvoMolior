package com.achulkov.evomolior.core.council

import java.math.BigDecimal
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.encodeToJsonElement

/**
 * Closed schema-bounded JSON value authority used by sealed promotion fixtures.
 *
 * Integer and decimal number branches remain distinct so fixture commitments preserve exact
 * executable schema semantics. Numeric values are intentionally limited to signed 64-bit integers
 * and finite doubles whose mathematical decimal value can be represented without precision loss.
 */
@Serializable
sealed interface TypedJsonValue {
    /** JSON null. */
    @Serializable
    @SerialName("NULL")
    data object NullValue : TypedJsonValue

    /**
     * JSON string.
     *
     * @param value Exact string value.
     */
    @Serializable
    @SerialName("STRING")
    data class StringValue(
        val value: String
    ) : TypedJsonValue

    /**
     * JSON boolean.
     *
     * @param value Exact boolean value.
     */
    @Serializable
    @SerialName("BOOLEAN")
    data class BooleanValue(
        val value: Boolean
    ) : TypedJsonValue

    /**
     * JSON integer representable as a signed 64-bit value.
     *
     * @param value Exact integer value.
     */
    @Serializable
    @SerialName("INTEGER")
    data class IntegerValue(
        val value: Long
    ) : TypedJsonValue

    /**
     * Finite JSON decimal number.
     *
     * @param value Exact finite number value.
     */
    @Serializable
    @SerialName("NUMBER")
    data class NumberValue(
        val value: Double
    ) : TypedJsonValue {
        init {
            require(value.isFinite()) { "Typed JSON number must be finite" }
        }
    }

    /**
     * Ordered JSON array.
     *
     * @param values Values retained in exact array order.
     */
    @Serializable
    @SerialName("ARRAY")
    data class ArrayValue(
        val values: List<TypedJsonValue>
    ) : TypedJsonValue

    /**
     * Canonically ordered JSON object.
     *
     * Object entries use an ordered list instead of a map so strict provider schemas remain closed.
     *
     * @param entries Unique entries ordered lexicographically by key.
     */
    @Serializable
    @SerialName("OBJECT")
    data class ObjectValue(
        val entries: List<TypedJsonObjectEntry>
    ) : TypedJsonValue {
        init {
            require(entries.map(TypedJsonObjectEntry::key).distinct().size == entries.size) {
                "Typed JSON object keys must be unique"
            }
            require(entries == entries.sortedBy(TypedJsonObjectEntry::key)) {
                "Typed JSON object entries must be ordered by key"
            }
        }
    }
}

/**
 * One deterministic entry in a typed JSON object.
 *
 * @param key Exact JSON object key.
 * @param value Exact typed JSON value.
 */
@Serializable
data class TypedJsonObjectEntry(
    val key: String,
    val value: TypedJsonValue
)

/** Stable strict codec and canonical hashing authority for [TypedJsonValue]. */
object TypedJsonValueCodec {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        classDiscriminator = "type"
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
        allowSpecialFloatingPointValues = false
    }

    /**
     * Encodes [value] as canonical closed tagged JSON.
     *
     * @param value Typed value to encode.
     * @return Canonical tagged JSON text.
     */
    fun encode(value: TypedJsonValue): String =
        CouncilContractHashing.canonicalJson(
            json.encodeToJsonElement(TypedJsonValue.serializer(), value)
        )

    /**
     * Decodes one strict closed tagged JSON value.
     *
     * @param value Strict tagged JSON text.
     * @return Typed value.
     */
    fun decode(value: String): TypedJsonValue =
        json.decodeFromString(TypedJsonValue.serializer(), value)

    /**
     * Returns the canonical tagged-value commitment.
     *
     * @param value Typed value to hash.
     * @return Lowercase SHA-256 digest.
     */
    fun hash(value: TypedJsonValue): String =
        CouncilContractHashing.sha256(encode(value))

    /**
     * Converts parsed schema-bounded JSON into its closed typed representation.
     *
     * Object entries are normalized by key. Integer values outside signed 64-bit range and decimal
     * values that lose precision as finite doubles fail instead of being silently rounded.
     *
     * @param value Parsed JSON value.
     * @return Lossless typed value.
     */
    fun fromJsonElement(value: JsonElement): TypedJsonValue = when (value) {
        JsonNull -> TypedJsonValue.NullValue
        is JsonArray -> TypedJsonValue.ArrayValue(value.map(::fromJsonElement))
        is JsonObject -> TypedJsonValue.ObjectValue(
            value.entries
                .sortedBy(Map.Entry<String, JsonElement>::key)
                .map { (key, child) ->
                    TypedJsonObjectEntry(key, fromJsonElement(child))
                }
        )
        is JsonPrimitive -> primitiveValue(value)
    }

    /**
     * Converts [value] to ordinary JSON for exact promotion-schema validation and execution.
     *
     * @param value Typed value.
     * @return Equivalent ordinary JSON element.
     */
    fun toJsonElement(value: TypedJsonValue): JsonElement = when (value) {
        TypedJsonValue.NullValue -> JsonNull
        is TypedJsonValue.StringValue -> JsonPrimitive(value.value)
        is TypedJsonValue.BooleanValue -> JsonPrimitive(value.value)
        is TypedJsonValue.IntegerValue -> JsonPrimitive(value.value)
        is TypedJsonValue.NumberValue -> JsonPrimitive(value.value)
        is TypedJsonValue.ArrayValue -> JsonArray(value.values.map(::toJsonElement))
        is TypedJsonValue.ObjectValue -> JsonObject(
            value.entries.associate { entry ->
                entry.key to toJsonElement(entry.value)
            }
        )
    }

    /**
     * Converts [value] to canonical ordinary JSON text.
     *
     * @param value Typed value.
     * @return Canonical ordinary JSON.
     */
    fun toCanonicalJson(value: TypedJsonValue): String =
        CouncilContractHashing.canonicalJson(toJsonElement(value))

    private fun primitiveValue(value: JsonPrimitive): TypedJsonValue {
        if (value.isString) return TypedJsonValue.StringValue(value.content)
        value.booleanOrNull?.let(TypedJsonValue::BooleanValue)
            ?.let { return it }

        val lexical = value.content
        if (INTEGER_PATTERN.matches(lexical)) {
            return lexical.toLongOrNull()
                ?.let(TypedJsonValue::IntegerValue)
                ?: throw SerializationException(
                    "JSON integer is outside the signed 64-bit typed-value range"
                )
        }
        val decimal = runCatching { BigDecimal(lexical) }
            .getOrElse {
                throw SerializationException("JSON number is not a supported decimal value", it)
            }
        val double = value.doubleOrNull?.takeIf(Double::isFinite)
            ?: throw SerializationException("JSON number is not a supported finite double")
        if (BigDecimal.valueOf(double).compareTo(decimal) != 0) {
            throw SerializationException(
                "JSON number cannot be represented as a finite double without precision loss"
            )
        }
        return TypedJsonValue.NumberValue(double)
    }

    private val INTEGER_PATTERN = Regex("-?(?:0|[1-9][0-9]*)")
}
