package com.achulkov.evomolior.core.council

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement

/**
 * One typed deterministic assertion authored for a general sealed test.
 *
 * Semantic validation and target compatibility are owned by `GeneralTestCompiler`; this wire
 * contract intentionally closes the representable shape without duplicating compiler policy.
 *
 * @param target Receiver-output authority evaluated by the test.
 * @param assertion Deterministic operation applied to the target.
 */
@Serializable
data class GeneralTestDefinitionV1(
    val target: GeneralTestTargetV1,
    val assertion: GeneralAssertionV1
)

/** Closed receiver-output targets supported by general-test language version 1. */
@Serializable
sealed interface GeneralTestTargetV1 {
    /** The complete receiver-facing final output text. */
    @Serializable
    @SerialName("FINAL_OUTPUT_TEXT")
    data object FinalOutputText : GeneralTestTargetV1

    /**
     * One text file declared by the final-output contract.
     *
     * @param relativePath Normalized relative output path validated by the compiler.
     */
    @Serializable
    @SerialName("OUTPUT_TEXT_FILE")
    data class OutputTextFile(
        val relativePath: String
    ) : GeneralTestTargetV1

    /**
     * One JSON file declared by the final-output contract.
     *
     * @param relativePath Normalized relative output path validated by the compiler.
     */
    @Serializable
    @SerialName("OUTPUT_JSON_FILE")
    data class OutputJsonFile(
        val relativePath: String
    ) : GeneralTestTargetV1
}

/** Closed deterministic assertions supported by general-test language version 1. */
@Serializable
sealed interface GeneralAssertionV1 {
    /**
     * Requires target text to contain an exact value.
     *
     * @param expected Required text.
     * @param caseSensitive Whether Unicode case must match exactly.
     */
    @Serializable
    @SerialName("TEXT_CONTAINS")
    data class TextContains(
        val expected: String,
        val caseSensitive: Boolean
    ) : GeneralAssertionV1

    /**
     * Requires target text to contain a match under RE2/J semantics.
     *
     * @param pattern Bounded RE2/J pattern validated by the compiler.
     */
    @Serializable
    @SerialName("TEXT_MATCHES_RE2")
    data class TextMatchesRe2(
        val pattern: String
    ) : GeneralAssertionV1

    /**
     * Requires a JSON Pointer to resolve in the target JSON document.
     *
     * @param pointer RFC 6901 pointer validated by the compiler.
     */
    @Serializable
    @SerialName("JSON_POINTER_EXISTS")
    data class JsonPointerExists(
        val pointer: String
    ) : GeneralAssertionV1

    /**
     * Requires a JSON Pointer to resolve to one exact string.
     *
     * @param pointer RFC 6901 pointer validated by the compiler.
     * @param expected Exact expected string.
     */
    @Serializable
    @SerialName("JSON_POINTER_EQUALS_STRING")
    data class JsonPointerEqualsString(
        val pointer: String,
        val expected: String
    ) : GeneralAssertionV1

    /**
     * Requires a JSON Pointer to resolve to a finite number inside an inclusive range.
     *
     * @param pointer RFC 6901 pointer validated by the compiler.
     * @param minimumInclusive Inclusive finite lower bound.
     * @param maximumInclusive Inclusive finite upper bound.
     */
    @Serializable
    @SerialName("JSON_POINTER_NUMBER_RANGE")
    data class JsonPointerNumberRange(
        val pointer: String,
        val minimumInclusive: Double,
        val maximumInclusive: Double
    ) : GeneralAssertionV1
}

/** Stable canonical codec for the general-test language version 1 ABI. */
object GeneralTestDefinitionV1Codec {
    /** Version committed by compiled plans and sealed-test suites. */
    const val ASSERTION_LANGUAGE_VERSION: String = "general-test-v1"

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
     * Encodes [definition] as canonical JSON with a stable `type` discriminator.
     *
     * @param definition Typed definition to encode.
     * @return Canonical JSON bytes represented as text.
     */
    fun encode(definition: GeneralTestDefinitionV1): String =
        CouncilContractHashing.canonicalJson(encodeToJsonElement(definition))

    /**
     * Encodes [definition] to the strict version-owned JSON representation.
     *
     * @param definition Typed definition to encode.
     * @return Strict JSON element before canonical key ordering.
     */
    fun encodeToJsonElement(definition: GeneralTestDefinitionV1): JsonElement =
        json.encodeToJsonElement(GeneralTestDefinitionV1.serializer(), definition)

    /**
     * Decodes one strict general-test definition.
     *
     * Nulls, scalar roots, unknown branches, missing fields, and extra fields fail decoding.
     *
     * @param value JSON text to decode.
     * @return Typed definition.
     */
    fun decode(value: String): GeneralTestDefinitionV1 =
        json.decodeFromString(GeneralTestDefinitionV1.serializer(), value)

    /**
     * Returns the SHA-256 commitment of the canonical definition.
     *
     * @param definition Typed definition to commit.
     * @return Lowercase SHA-256 digest.
     */
    fun hash(definition: GeneralTestDefinitionV1): String =
        CouncilContractHashing.sha256(encode(definition))

    /**
     * Encodes a definition with the strict serializer without canonical key ordering.
     *
     * This is exposed only for provider-schema and codec symmetry tests; commitments use [encode].
     *
     * @param definition Typed definition to encode.
     * @return Strict serializer JSON text.
     */
    fun encodeStrict(definition: GeneralTestDefinitionV1): String =
        json.encodeToString(GeneralTestDefinitionV1.serializer(), definition)
}
