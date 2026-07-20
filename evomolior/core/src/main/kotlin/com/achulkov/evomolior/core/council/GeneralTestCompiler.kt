package com.achulkov.evomolior.core.council

import com.achulkov.evomolior.core.atlas.FinalOutputSpec
import com.achulkov.evomolior.core.grader.SafeRegexMatcher
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/** Deterministic compile-time and execution-time bounds for general sealed tests. */
object GeneralTestLimits {
    /** Maximum number of general tests accepted by one sealed suite. */
    const val MAX_TESTS_PER_SUITE: Int = 64

    /** Maximum UTF-8 size of an expected text value. */
    const val MAX_EXPECTED_TEXT_UTF8_BYTES: Int = 4 * 1024

    /** Maximum UTF-8 size of an RE2/J pattern. */
    const val MAX_RE2_PATTERN_UTF8_BYTES: Int = 4 * 1024

    /** Maximum number of RFC 6901 reference tokens in one JSON Pointer. */
    const val MAX_JSON_POINTER_DEPTH: Int = 32

    /** Maximum text-file size accepted by the evaluator. */
    const val MAX_OUTPUT_TEXT_FILE_BYTES: Int = 2 * 1024 * 1024

    /** Maximum parsed JSON nesting depth accepted by the evaluator. */
    const val MAX_PARSED_JSON_DEPTH: Int = 64
}

/**
 * Receiver-output authority resolved against one exact final-output contract.
 *
 * Every branch retains the authorized path and canonical media type so a compiled plan cannot be
 * replayed against a different output contract without changing its commitment.
 */
@Serializable
sealed interface ResolvedGeneralTestTarget {
    /**
     * Complete receiver-facing final output interpreted as text.
     *
     * @param relativePath Canonical path authorized by the final-output contract.
     * @param mediaType Canonical textual media type authorized by the final-output contract.
     */
    @Serializable
    @SerialName("FINAL_OUTPUT_TEXT")
    data class FinalOutputText(
        val relativePath: String,
        val mediaType: String
    ) : ResolvedGeneralTestTarget

    /**
     * Exact text artifact authorized by the final-output contract.
     *
     * @param relativePath Canonical path authorized by the final-output contract.
     * @param mediaType Normalized media type from the text top-level type.
     */
    @Serializable
    @SerialName("OUTPUT_TEXT_FILE")
    data class OutputTextFile(
        val relativePath: String,
        val mediaType: String
    ) : ResolvedGeneralTestTarget

    /**
     * Exact JSON artifact authorized by the final-output contract.
     *
     * @param relativePath Canonical path authorized by the final-output contract.
     * @param mediaType Normalized JSON media type.
     */
    @Serializable
    @SerialName("OUTPUT_JSON_FILE")
    data class OutputJsonFile(
        val relativePath: String,
        val mediaType: String
    ) : ResolvedGeneralTestTarget
}

/**
 * Deterministic operation ready for execution without reparsing assertion JSON.
 *
 * RE2/J patterns remain strings in this persisted contract. Runtime preparation may compile them
 * into private matcher instances, but provider-library types never enter public authority.
 */
@Serializable
sealed interface CompiledGeneralOperation {
    /**
     * Exact text-containment operation.
     *
     * @param expected Required bounded text.
     * @param caseSensitive Whether Unicode case must match exactly.
     */
    @Serializable
    @SerialName("TEXT_CONTAINS")
    data class TextContains(
        val expected: String,
        val caseSensitive: Boolean
    ) : CompiledGeneralOperation

    /**
     * Bounded RE2/J containment operation.
     *
     * @param pattern Valid RE2/J pattern retained as portable text.
     */
    @Serializable
    @SerialName("TEXT_MATCHES_RE2")
    data class TextMatchesRe2(
        val pattern: String
    ) : CompiledGeneralOperation

    /**
     * JSON Pointer existence operation.
     *
     * @param pointer Original valid RFC 6901 pointer.
     * @param tokens Decoded reference tokens used directly by the evaluator.
     */
    @Serializable
    @SerialName("JSON_POINTER_EXISTS")
    data class JsonPointerExists(
        val pointer: String,
        val tokens: List<String>
    ) : CompiledGeneralOperation

    /**
     * Exact JSON string comparison operation.
     *
     * @param pointer Original valid RFC 6901 pointer.
     * @param tokens Decoded reference tokens used directly by the evaluator.
     * @param expected Required bounded string.
     */
    @Serializable
    @SerialName("JSON_POINTER_EQUALS_STRING")
    data class JsonPointerEqualsString(
        val pointer: String,
        val tokens: List<String>,
        val expected: String
    ) : CompiledGeneralOperation

    /**
     * Inclusive finite JSON number-range operation.
     *
     * @param pointer Original valid RFC 6901 pointer.
     * @param tokens Decoded reference tokens used directly by the evaluator.
     * @param minimumInclusive Inclusive finite lower bound.
     * @param maximumInclusive Inclusive finite upper bound.
     */
    @Serializable
    @SerialName("JSON_POINTER_NUMBER_RANGE")
    data class JsonPointerNumberRange(
        val pointer: String,
        val tokens: List<String>,
        val minimumInclusive: Double,
        val maximumInclusive: Double
    ) : CompiledGeneralOperation
}

/**
 * Immutable executable authority for one general sealed test.
 *
 * @param testId Stable sealed-test identity.
 * @param target Exact resolved receiver-output authority.
 * @param operation Deterministic compiled assertion.
 * @param definitionHash SHA-256 commitment of the canonical typed definition.
 * @param assertionLanguageVersion Assertion ABI used to interpret the definition.
 * @param evaluatorVersion Evaluator semantics pinned at compilation.
 * @param planHash SHA-256 commitment of every preceding field, excluding this self-hash.
 */
@Serializable
data class CompiledGeneralTestPlan(
    val testId: String,
    val target: ResolvedGeneralTestTarget,
    val operation: CompiledGeneralOperation,
    val definitionHash: String,
    val assertionLanguageVersion: String,
    val evaluatorVersion: String,
    val planHash: String = ""
) {
    /** Returns a copy carrying the deterministic plan commitment. */
    fun withComputedHash(): CompiledGeneralTestPlan =
        copy(planHash = CompiledGeneralTestPlanCodec.hash(this))

    /** Returns whether [planHash] matches the complete canonical plan content. */
    fun hasValidHash(): Boolean =
        CouncilContractHashing.constantTimeEquals(
            planHash,
            CompiledGeneralTestPlanCodec.hash(this)
        )
}

/** Stable canonical codec and self-hash authority for [CompiledGeneralTestPlan]. */
object CompiledGeneralTestPlanCodec {
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
     * Encodes [plan] as canonical JSON with stable operation and target discriminators.
     *
     * @param plan Compiled plan to encode.
     * @return Canonical JSON text including the attached plan hash.
     */
    fun encode(plan: CompiledGeneralTestPlan): String =
        CouncilContractHashing.canonicalJson(
            json.encodeToJsonElement(CompiledGeneralTestPlan.serializer(), plan)
        )

    /**
     * Decodes one strict compiled plan.
     *
     * @param value Strict compiled-plan JSON.
     * @return Decoded plan; callers should additionally require [CompiledGeneralTestPlan.hasValidHash].
     */
    fun decode(value: String): CompiledGeneralTestPlan =
        json.decodeFromString(CompiledGeneralTestPlan.serializer(), value)

    /**
     * Strictly decodes [value] and requires its self-hash to match.
     *
     * @param value Strict compiled-plan JSON.
     * @return Decoded hash-verified plan.
     * @throws IllegalArgumentException when the attached plan hash is invalid.
     */
    fun decodeVerified(value: String): CompiledGeneralTestPlan =
        decode(value).also { plan ->
            require(plan.hasValidHash()) { "Compiled general-test plan hash is invalid" }
        }

    /**
     * Hashes [plan] after removing its self-referential `planHash` field.
     *
     * @param plan Compiled plan to commit.
     * @return Lowercase SHA-256 commitment.
     */
    fun hash(plan: CompiledGeneralTestPlan): String {
        val element = json.encodeToJsonElement(CompiledGeneralTestPlan.serializer(), plan)
        val commitment = JsonObject(element.jsonObject.filterKeys { it != "planHash" })
        return CouncilContractHashing.sha256(
            CouncilContractHashing.canonicalJson(commitment)
        )
    }

    /**
     * Encodes [plan] with the strict serializer before canonical key ordering.
     *
     * @param plan Compiled plan to encode.
     * @return Strict serializer JSON text.
     */
    fun encodeStrict(plan: CompiledGeneralTestPlan): String =
        json.encodeToString(CompiledGeneralTestPlan.serializer(), plan)
}

/** Machine-readable deterministic general-test compilation failures. */
@Serializable
enum class GeneralTestCompileErrorCode {
    GENERAL_TEST_NULL,
    GENERAL_TEST_UNKNOWN_VERSION,
    GENERAL_TEST_EMPTY_EXPECTED_TEXT,
    GENERAL_TEST_PATH_OUTSIDE_OUTPUT_SPEC,
    GENERAL_TEST_TARGET_TYPE_MISMATCH,
    GENERAL_TEST_INVALID_JSON_POINTER,
    GENERAL_TEST_INVALID_RE2,
    GENERAL_TEST_PATTERN_TOO_LARGE,
    GENERAL_TEST_RANGE_NON_FINITE,
    GENERAL_TEST_RANGE_REVERSED,
    GENERAL_TEST_UNSUPPORTED_ASSERTION,
    GENERAL_TEST_NOT_EXECUTABLE
}

/**
 * One typed deterministic compilation diagnostic.
 *
 * @param testId Test whose definition was rejected.
 * @param code Stable error category.
 * @param path Exact invalid contract path.
 * @param message Bounded operator-facing explanation.
 */
@Serializable
data class GeneralTestCompileError(
    val testId: String,
    val code: GeneralTestCompileErrorCode,
    val path: String,
    val message: String
)

/** Result of compiling exactly one general sealed test. */
@Serializable
sealed interface GeneralTestCompileResult {
    /**
     * Successful deterministic compilation.
     *
     * @param plan Hash-bound executable plan.
     */
    @Serializable
    @SerialName("SUCCESS")
    data class Success(
        val plan: CompiledGeneralTestPlan
    ) : GeneralTestCompileResult

    /**
     * Rejected compilation with complete deterministic diagnostics.
     *
     * @param errors Non-empty typed failures.
     */
    @Serializable
    @SerialName("FAILURE")
    data class Failure(
        val errors: List<GeneralTestCompileError>
    ) : GeneralTestCompileResult
}

/**
 * Pure compiler from the closed general-test language to one hash-bound executable plan.
 *
 * The compiler reads no filesystem state. File authorization is derived exclusively from the
 * supplied [FinalOutputSpec].
 */
object GeneralTestCompiler {
    /** Evaluator semantics pinned into every plan emitted by this compiler. */
    const val EVALUATOR_VERSION: String = "general-test-evaluator-v1"

    private val structuralJson = Json {
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
        allowSpecialFloatingPointValues = false
    }
    private val supportedAssertionTypes = setOf(
        "TEXT_CONTAINS",
        "TEXT_MATCHES_RE2",
        "JSON_POINTER_EXISTS",
        "JSON_POINTER_EQUALS_STRING",
        "JSON_POINTER_NUMBER_RANGE"
    )

    /**
     * Compiles one nullable typed definition against exact output authority.
     *
     * Nullable input is intentional: copied legacy/provider evidence containing canonical `null`
     * must fail with `GENERAL_TEST_NULL`, not disappear before diagnostics are produced.
     *
     * @param testId Stable sealed-test identity.
     * @param definition Strict typed definition, or null evidence.
     * @param outputSpec Exact final-output authority.
     * @param assertionLanguageVersion Claimed definition ABI.
     * @return Deterministic success or typed failures.
     */
    fun compile(
        testId: String,
        definition: GeneralTestDefinitionV1?,
        outputSpec: FinalOutputSpec,
        assertionLanguageVersion: String =
            GeneralTestDefinitionV1Codec.ASSERTION_LANGUAGE_VERSION
    ): GeneralTestCompileResult {
        if (assertionLanguageVersion != GeneralTestDefinitionV1Codec.ASSERTION_LANGUAGE_VERSION) {
            return failure(
                testId,
                GeneralTestCompileErrorCode.GENERAL_TEST_UNKNOWN_VERSION,
                "assertionLanguageVersion",
                "Unsupported general-test assertion language version"
            )
        }
        if (testId.isBlank()) {
            return failure(
                testId,
                GeneralTestCompileErrorCode.GENERAL_TEST_NOT_EXECUTABLE,
                "testId",
                "General test requires a non-blank stable testId"
            )
        }
        if (definition == null) {
            return failure(
                testId,
                GeneralTestCompileErrorCode.GENERAL_TEST_NULL,
                "definition",
                "General test definition is null"
            )
        }

        val errors = mutableListOf<GeneralTestCompileError>()
        val target = resolveTarget(testId, definition.target, outputSpec, errors)
        validateTargetCompatibility(testId, definition, errors)
        val operation = compileOperation(testId, definition.assertion, errors)
        if (errors.isNotEmpty() || target == null || operation == null) {
            return GeneralTestCompileResult.Failure(errors)
        }

        val plan = CompiledGeneralTestPlan(
            testId = testId,
            target = target,
            operation = operation,
            definitionHash = GeneralTestDefinitionV1Codec.hash(definition),
            assertionLanguageVersion = assertionLanguageVersion,
            evaluatorVersion = EVALUATOR_VERSION
        ).withComputedHash()
        return GeneralTestCompileResult.Success(plan)
    }

    /**
     * Strictly decodes and compiles a raw provider/archive definition.
     *
     * This adapter exists for diagnostics and migration preflight. Active v3 authority should
     * decode through the typed serializer before calling [compile].
     *
     * @param testId Stable sealed-test identity.
     * @param definitionJson Strict JSON text, or null evidence.
     * @param outputSpec Exact final-output authority.
     * @param assertionLanguageVersion Claimed definition ABI.
     * @return Deterministic success or typed failures.
     */
    fun compileEncoded(
        testId: String,
        definitionJson: String?,
        outputSpec: FinalOutputSpec,
        assertionLanguageVersion: String =
            GeneralTestDefinitionV1Codec.ASSERTION_LANGUAGE_VERSION
    ): GeneralTestCompileResult {
        if (assertionLanguageVersion != GeneralTestDefinitionV1Codec.ASSERTION_LANGUAGE_VERSION) {
            return compile(
                testId = testId,
                definition = null,
                outputSpec = outputSpec,
                assertionLanguageVersion = assertionLanguageVersion
            )
        }
        if (definitionJson == null) {
            return compile(testId, null, outputSpec, assertionLanguageVersion)
        }
        val element = runCatching {
            structuralJson.parseToJsonElement(definitionJson)
        }.getOrElse {
            return failure(
                testId,
                GeneralTestCompileErrorCode.GENERAL_TEST_NOT_EXECUTABLE,
                "definition",
                "General test definition is not strict JSON"
            )
        }
        if (element is JsonNull) {
            return compile(testId, null, outputSpec, assertionLanguageVersion)
        }

        unsupportedAssertionType(element)?.let {
            return failure(
                testId,
                GeneralTestCompileErrorCode.GENERAL_TEST_UNSUPPORTED_ASSERTION,
                "definition.assertion.type",
                "Unsupported general-test assertion type"
            )
        }
        val definition = runCatching {
            GeneralTestDefinitionV1Codec.decode(definitionJson)
        }.getOrElse {
            return failure(
                testId,
                GeneralTestCompileErrorCode.GENERAL_TEST_NOT_EXECUTABLE,
                "definition",
                "General test definition cannot be strictly decoded"
            )
        }
        return compile(testId, definition, outputSpec, assertionLanguageVersion)
    }

    /**
     * Verifies one persisted plan against its exact typed definition and output authority.
     *
     * Verification requires a valid internal plan hash and byte-for-byte equality with a fresh
     * deterministic compilation under the currently pinned evaluator.
     *
     * @param plan Persisted compiled plan.
     * @param definition Exact paired typed definition.
     * @param outputSpec Exact paired final-output authority.
     * @return Whether all hashes, versions, resolved authority, and compiled semantics match.
     */
    fun verify(
        plan: CompiledGeneralTestPlan,
        definition: GeneralTestDefinitionV1?,
        outputSpec: FinalOutputSpec
    ): Boolean {
        if (!plan.hasValidHash()) return false
        val recompiled = compile(
            testId = plan.testId,
            definition = definition,
            outputSpec = outputSpec,
            assertionLanguageVersion = plan.assertionLanguageVersion
        )
        return recompiled is GeneralTestCompileResult.Success &&
            recompiled.plan == plan
    }

    private fun resolveTarget(
        testId: String,
        target: GeneralTestTargetV1,
        outputSpec: FinalOutputSpec,
        errors: MutableList<GeneralTestCompileError>
    ): ResolvedGeneralTestTarget? {
        return when (target) {
            GeneralTestTargetV1.FinalOutputText -> {
                val authority = resolveFileAuthority(testId, outputSpec, errors)
                    ?: return null
                if (!isTextualMediaType(authority.mediaType)) {
                    errors += error(
                        testId,
                        GeneralTestCompileErrorCode.GENERAL_TEST_TARGET_TYPE_MISMATCH,
                        "definition.target",
                        "FINAL_OUTPUT_TEXT requires a text or JSON media type"
                    )
                }
                ResolvedGeneralTestTarget.FinalOutputText(
                    relativePath = authority.relativePath,
                    mediaType = authority.mediaType
                )
            }

            is GeneralTestTargetV1.OutputTextFile -> {
                val authority = resolveFileAuthority(testId, outputSpec, errors)
                    ?: return null
                val targetPath = authorizeExactPath(
                    testId = testId,
                    path = target.relativePath,
                    outputPath = authority.relativePath,
                    errors = errors
                )
                if (!isTextMediaType(authority.mediaType)) {
                    errors += error(
                        testId,
                        GeneralTestCompileErrorCode.GENERAL_TEST_TARGET_TYPE_MISMATCH,
                        "definition.target",
                        "OUTPUT_TEXT_FILE requires a text media type"
                    )
                }
                targetPath?.let {
                    ResolvedGeneralTestTarget.OutputTextFile(it, authority.mediaType)
                }
            }

            is GeneralTestTargetV1.OutputJsonFile -> {
                val authority = resolveFileAuthority(testId, outputSpec, errors)
                    ?: return null
                val targetPath = authorizeExactPath(
                    testId = testId,
                    path = target.relativePath,
                    outputPath = authority.relativePath,
                    errors = errors
                )
                if (!isJsonMediaType(authority.mediaType)) {
                    errors += error(
                        testId,
                        GeneralTestCompileErrorCode.GENERAL_TEST_TARGET_TYPE_MISMATCH,
                        "definition.target",
                        "OUTPUT_JSON_FILE requires a JSON media type"
                    )
                }
                targetPath?.let {
                    ResolvedGeneralTestTarget.OutputJsonFile(it, authority.mediaType)
                }
            }
        }
    }

    private fun resolveFileAuthority(
        testId: String,
        outputSpec: FinalOutputSpec,
        errors: MutableList<GeneralTestCompileError>
    ): ResolvedFileAuthority? {
        val outputPath = canonicalRelativePath(outputSpec.relativePath)
        if (outputPath == null) {
            errors += error(
                testId,
                GeneralTestCompileErrorCode.GENERAL_TEST_PATH_OUTSIDE_OUTPUT_SPEC,
                "outputSpec.relativePath",
                "Final-output authority is not a canonical relative path"
            )
        }
        val mediaType = canonicalBaseMediaType(outputSpec.mediaType)
        if (mediaType == null) {
            errors += error(
                testId,
                GeneralTestCompileErrorCode.GENERAL_TEST_TARGET_TYPE_MISMATCH,
                "outputSpec.mediaType",
                "Final-output authority requires a valid media type and subtype"
            )
        }
        return if (outputPath != null && mediaType != null) {
            ResolvedFileAuthority(outputPath, mediaType)
        } else {
            null
        }
    }

    private fun authorizeExactPath(
        testId: String,
        path: String,
        outputPath: String,
        errors: MutableList<GeneralTestCompileError>
    ): String? {
        val targetPath = canonicalRelativePath(path)
        if (targetPath == null || targetPath != outputPath) {
            errors += error(
                testId,
                GeneralTestCompileErrorCode.GENERAL_TEST_PATH_OUTSIDE_OUTPUT_SPEC,
                "definition.target.relativePath",
                "Target path is not the exact canonical path authorized by FinalOutputSpec"
            )
            return null
        }
        return targetPath
    }

    private fun validateTargetCompatibility(
        testId: String,
        definition: GeneralTestDefinitionV1,
        errors: MutableList<GeneralTestCompileError>
    ) {
        val compatible = when (definition.assertion) {
            is GeneralAssertionV1.TextContains,
            is GeneralAssertionV1.TextMatchesRe2 ->
                definition.target is GeneralTestTargetV1.FinalOutputText ||
                    definition.target is GeneralTestTargetV1.OutputTextFile

            is GeneralAssertionV1.JsonPointerExists,
            is GeneralAssertionV1.JsonPointerEqualsString,
            is GeneralAssertionV1.JsonPointerNumberRange ->
                definition.target is GeneralTestTargetV1.OutputJsonFile
        }
        if (!compatible) {
            errors += error(
                testId,
                GeneralTestCompileErrorCode.GENERAL_TEST_TARGET_TYPE_MISMATCH,
                "definition",
                "Assertion type is incompatible with the selected target"
            )
        }
    }

    private fun compileOperation(
        testId: String,
        assertion: GeneralAssertionV1,
        errors: MutableList<GeneralTestCompileError>
    ): CompiledGeneralOperation? = when (assertion) {
        is GeneralAssertionV1.TextContains -> {
            val valid = validateExpectedText(
                testId,
                assertion.expected,
                "definition.assertion.expected",
                errors
            )
            if (valid) {
                CompiledGeneralOperation.TextContains(
                    expected = assertion.expected,
                    caseSensitive = assertion.caseSensitive
                )
            } else {
                null
            }
        }

        is GeneralAssertionV1.TextMatchesRe2 -> {
            val patternBytes = assertion.pattern.encodeToByteArray().size
            when {
                assertion.pattern.isBlank() -> {
                    errors += error(
                        testId,
                        GeneralTestCompileErrorCode.GENERAL_TEST_NOT_EXECUTABLE,
                        "definition.assertion.pattern",
                        "RE2/J pattern must not be blank"
                    )
                    null
                }

                patternBytes > GeneralTestLimits.MAX_RE2_PATTERN_UTF8_BYTES -> {
                    errors += error(
                        testId,
                        GeneralTestCompileErrorCode.GENERAL_TEST_PATTERN_TOO_LARGE,
                        "definition.assertion.pattern",
                        "RE2/J pattern exceeds the 4 KiB UTF-8 limit"
                    )
                    null
                }

                else -> {
                    val validationError = SafeRegexMatcher.validationError(assertion.pattern)
                    if (validationError != null) {
                        errors += error(
                            testId,
                            GeneralTestCompileErrorCode.GENERAL_TEST_INVALID_RE2,
                            "definition.assertion.pattern",
                            "RE2/J rejected the pattern: ${validationError.take(500)}"
                        )
                        null
                    } else {
                        CompiledGeneralOperation.TextMatchesRe2(assertion.pattern)
                    }
                }
            }
        }

        is GeneralAssertionV1.JsonPointerExists -> {
            compilePointer(testId, assertion.pointer, errors)?.let { tokens ->
                CompiledGeneralOperation.JsonPointerExists(assertion.pointer, tokens)
            }
        }

        is GeneralAssertionV1.JsonPointerEqualsString -> {
            val tokens = compilePointer(testId, assertion.pointer, errors)
            val expectedValid = validateExpectedText(
                testId,
                assertion.expected,
                "definition.assertion.expected",
                errors
            )
            if (tokens != null && expectedValid) {
                CompiledGeneralOperation.JsonPointerEqualsString(
                    pointer = assertion.pointer,
                    tokens = tokens,
                    expected = assertion.expected
                )
            } else {
                null
            }
        }

        is GeneralAssertionV1.JsonPointerNumberRange -> {
            val tokens = compilePointer(testId, assertion.pointer, errors)
            val finite = assertion.minimumInclusive.isFinite() &&
                assertion.maximumInclusive.isFinite()
            if (!finite) {
                errors += error(
                    testId,
                    GeneralTestCompileErrorCode.GENERAL_TEST_RANGE_NON_FINITE,
                    "definition.assertion",
                    "JSON number range bounds must both be finite"
                )
            }
            val ordered = !finite ||
                assertion.minimumInclusive <= assertion.maximumInclusive
            if (finite && !ordered) {
                errors += error(
                    testId,
                    GeneralTestCompileErrorCode.GENERAL_TEST_RANGE_REVERSED,
                    "definition.assertion",
                    "JSON number range minimum exceeds maximum"
                )
            }
            if (tokens != null && finite && ordered) {
                CompiledGeneralOperation.JsonPointerNumberRange(
                    pointer = assertion.pointer,
                    tokens = tokens,
                    minimumInclusive = assertion.minimumInclusive,
                    maximumInclusive = assertion.maximumInclusive
                )
            } else {
                null
            }
        }
    }

    private fun validateExpectedText(
        testId: String,
        expected: String,
        path: String,
        errors: MutableList<GeneralTestCompileError>
    ): Boolean {
        if (expected.isBlank()) {
            errors += error(
                testId,
                GeneralTestCompileErrorCode.GENERAL_TEST_EMPTY_EXPECTED_TEXT,
                path,
                "Expected text must not be blank"
            )
            return false
        }
        if (expected.encodeToByteArray().size > GeneralTestLimits.MAX_EXPECTED_TEXT_UTF8_BYTES) {
            errors += error(
                testId,
                GeneralTestCompileErrorCode.GENERAL_TEST_NOT_EXECUTABLE,
                path,
                "Expected text exceeds the 4 KiB UTF-8 limit"
            )
            return false
        }
        return true
    }

    private fun compilePointer(
        testId: String,
        pointer: String,
        errors: MutableList<GeneralTestCompileError>
    ): List<String>? {
        val tokens = parseJsonPointer(pointer)
        if (tokens == null || tokens.size > GeneralTestLimits.MAX_JSON_POINTER_DEPTH) {
            errors += error(
                testId,
                GeneralTestCompileErrorCode.GENERAL_TEST_INVALID_JSON_POINTER,
                "definition.assertion.pointer",
                "JSON Pointer must use valid RFC 6901 escaping and at most 32 reference tokens"
            )
            return null
        }
        return tokens
    }

    private fun parseJsonPointer(pointer: String): List<String>? {
        if (pointer.isEmpty()) return emptyList()
        if (!pointer.startsWith('/')) return null

        val tokens = mutableListOf<String>()
        var tokenStart = 1
        var index = 1
        while (index <= pointer.length) {
            if (index == pointer.length || pointer[index] == '/') {
                val decoded = decodePointerToken(pointer.substring(tokenStart, index))
                    ?: return null
                tokens += decoded
                if (tokens.size > GeneralTestLimits.MAX_JSON_POINTER_DEPTH) return null
                tokenStart = index + 1
            }
            index += 1
        }
        return tokens
    }

    private fun decodePointerToken(token: String): String? {
        val decoded = StringBuilder(token.length)
        var index = 0
        while (index < token.length) {
            val character = token[index]
            if (character != '~') {
                decoded.append(character)
                index += 1
                continue
            }
            if (index + 1 >= token.length) return null
            when (token[index + 1]) {
                '0' -> decoded.append('~')
                '1' -> decoded.append('/')
                else -> return null
            }
            index += 2
        }
        return decoded.toString()
    }

    private fun canonicalRelativePath(value: String): String? {
        if (
            value.isBlank() ||
            value != value.trim() ||
            value.startsWith('/') ||
            value.contains('\\') ||
            value.any(Char::isISOControl)
        ) {
            return null
        }
        val segments = value.split('/')
        if (
            segments.any { segment ->
                segment.isBlank() ||
                    segment == "." ||
                    segment == ".."
            } ||
            ':' in segments.first()
        ) {
            return null
        }
        return segments.joinToString("/")
    }

    private fun canonicalBaseMediaType(value: String): String? {
        if (value.isBlank() || value.any(Char::isISOControl)) return null
        val parts = value.split(';')
        val base = parts.first().trim().lowercase()
        val slash = base.indexOf('/')
        if (
            slash <= 0 ||
            slash != base.lastIndexOf('/') ||
            slash == base.lastIndex ||
            !base.substring(0, slash).all(::isMediaTypeTokenCharacter) ||
            !base.substring(slash + 1).all(::isMediaTypeTokenCharacter) ||
            parts.drop(1).any { !isValidMediaTypeParameter(it) }
        ) {
            return null
        }
        return base
    }

    private fun isValidMediaTypeParameter(value: String): Boolean {
        val trimmed = value.trim()
        val equals = trimmed.indexOf('=')
        if (equals <= 0 || equals == trimmed.lastIndex) return false
        val name = trimmed.substring(0, equals).trim()
        val parameterValue = trimmed.substring(equals + 1).trim()
        return name.isNotEmpty() &&
            parameterValue.isNotEmpty() &&
            name.all(::isMediaTypeTokenCharacter) &&
            parameterValue.all(::isMediaTypeTokenCharacter)
    }

    private fun isMediaTypeTokenCharacter(character: Char): Boolean =
        character in 'a'..'z' ||
            character in 'A'..'Z' ||
            character in '0'..'9' ||
            character in "!#$%&'*+-.^_`|~"

    private fun isTextMediaType(mediaType: String): Boolean =
        mediaType.substringBefore(';').trim().startsWith("text/")

    private fun isTextualMediaType(mediaType: String): Boolean =
        isTextMediaType(mediaType) || isJsonMediaType(mediaType)

    private fun isJsonMediaType(mediaType: String): Boolean {
        val base = mediaType.substringBefore(';').trim()
        return base == "application/json" ||
            (base.startsWith("application/") && base.endsWith("+json"))
    }

    private fun unsupportedAssertionType(element: kotlinx.serialization.json.JsonElement): String? {
        val root = element as? JsonObject ?: return null
        val assertion = root["assertion"] as? JsonObject ?: return null
        val type = (assertion["type"] as? JsonPrimitive)
            ?.takeIf(JsonPrimitive::isString)
            ?.content
            ?: return null
        return type.takeUnless { it in supportedAssertionTypes }
    }

    private fun failure(
        testId: String,
        code: GeneralTestCompileErrorCode,
        path: String,
        message: String
    ): GeneralTestCompileResult.Failure =
        GeneralTestCompileResult.Failure(listOf(error(testId, code, path, message)))

    private fun error(
        testId: String,
        code: GeneralTestCompileErrorCode,
        path: String,
        message: String
    ): GeneralTestCompileError =
        GeneralTestCompileError(
            testId = testId,
            code = code,
            path = path,
            message = message
        )

    private data class ResolvedFileAuthority(
        val relativePath: String,
        val mediaType: String
    )
}
