package com.achulkov.evomolior.core.council

import com.achulkov.evomolior.core.atlas.FinalOutputSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class GeneralTestCompilerTest {
    @Test
    fun `all five assertion branches compile to hash-stable executable plans`() {
        val definitions = listOf(
            GeneralTestDefinitionV1(
                target = GeneralTestTargetV1.FinalOutputText,
                assertion = GeneralAssertionV1.TextContains("required evidence", false)
            ) to textOutputSpec,
            GeneralTestDefinitionV1(
                target = GeneralTestTargetV1.OutputTextFile("reports/final.md"),
                assertion = GeneralAssertionV1.TextMatchesRe2("risk\\s+register")
            ) to textOutputSpec,
            GeneralTestDefinitionV1(
                target = GeneralTestTargetV1.OutputJsonFile("metrics.json"),
                assertion = GeneralAssertionV1.JsonPointerExists("/metrics/0")
            ) to jsonOutputSpec,
            GeneralTestDefinitionV1(
                target = GeneralTestTargetV1.OutputJsonFile("metrics.json"),
                assertion = GeneralAssertionV1.JsonPointerEqualsString("/status", "AVAILABLE")
            ) to jsonOutputSpec,
            GeneralTestDefinitionV1(
                target = GeneralTestTargetV1.OutputJsonFile("metrics.json"),
                assertion = GeneralAssertionV1.JsonPointerNumberRange("/score", 0.0, 1.0)
            ) to jsonOutputSpec
        )

        definitions.forEachIndexed { index, (definition, outputSpec) ->
            val first = success(
                GeneralTestCompiler.compile("test-$index", definition, outputSpec)
            ).plan
            val second = success(
                GeneralTestCompiler.compile("test-$index", definition, outputSpec)
            ).plan

            assertEquals(GeneralTestDefinitionV1Codec.hash(definition), first.definitionHash)
            assertEquals(GeneralTestDefinitionV1Codec.ASSERTION_LANGUAGE_VERSION, first.assertionLanguageVersion)
            assertEquals(GeneralTestCompiler.EVALUATOR_VERSION, first.evaluatorVersion)
            assertEquals(first, second)
            assertTrue(first.hasValidHash())
            assertEquals(
                first,
                CompiledGeneralTestPlanCodec.decodeVerified(
                    CompiledGeneralTestPlanCodec.encode(first)
                )
            )
        }
    }

    @Test
    fun `canonical definition and plan hashes have stable golden values`() {
        val definition = GeneralTestDefinitionV1(
            target = GeneralTestTargetV1.OutputTextFile("reports/final.md"),
            assertion = GeneralAssertionV1.TextContains(
                expected = "required evidence",
                caseSensitive = false
            )
        )
        val plan = success(
            GeneralTestCompiler.compile("test-golden", definition, textOutputSpec)
        ).plan

        assertEquals(
            "f3586ac6495f2fe257783122034d2510aa60475cba751cda1e7b3d28be0734b9",
            plan.definitionHash
        )
        assertEquals(
            "94fa5e5ebe333f5b23a141f0679337d97efdbb0efa5972dc9fedca723dd296d0",
            plan.planHash
        )
        assertEquals(
            """{"assertionLanguageVersion":"general-test-v1","definitionHash":"f3586ac6495f2fe257783122034d2510aa60475cba751cda1e7b3d28be0734b9","evaluatorVersion":"general-test-evaluator-v1","operation":{"caseSensitive":false,"expected":"required evidence","type":"TEXT_CONTAINS"},"planHash":"94fa5e5ebe333f5b23a141f0679337d97efdbb0efa5972dc9fedca723dd296d0","target":{"mediaType":"text/markdown","relativePath":"reports/final.md","type":"OUTPUT_TEXT_FILE"},"testId":"test-golden"}""",
            CompiledGeneralTestPlanCodec.encode(plan)
        )
    }

    @Test
    fun `plan commitment excludes only its own hash and binds every semantic field`() {
        val plan = success(
            GeneralTestCompiler.compile(
                testId = "test-hash",
                definition = GeneralTestDefinitionV1(
                    GeneralTestTargetV1.OutputTextFile("reports/final.md"),
                    GeneralAssertionV1.TextContains("evidence", true)
                ),
                outputSpec = textOutputSpec
            )
        ).plan

        assertEquals(plan.planHash, CompiledGeneralTestPlanCodec.hash(plan.copy(planHash = "tampered")))
        assertFalse(plan.copy(planHash = "tampered").hasValidHash())
        assertNotEquals(plan.planHash, CompiledGeneralTestPlanCodec.hash(plan.copy(testId = "other")))
        assertNotEquals(
            plan.planHash,
            CompiledGeneralTestPlanCodec.hash(plan.copy(evaluatorVersion = "other-evaluator"))
        )
        assertNotEquals(
            plan.planHash,
            CompiledGeneralTestPlanCodec.hash(plan.copy(definitionHash = "other-definition"))
        )
        assertNotEquals(
            plan.planHash,
            CompiledGeneralTestPlanCodec.hash(
                plan.copy(
                    target = ResolvedGeneralTestTarget.OutputTextFile(
                        relativePath = "other.md",
                        mediaType = "text/markdown"
                    )
                )
            )
        )
        assertNotEquals(
            plan.planHash,
            CompiledGeneralTestPlanCodec.hash(
                plan.copy(operation = CompiledGeneralOperation.TextContains("different", true))
            )
        )
        assertNotEquals(
            plan.planHash,
            CompiledGeneralTestPlanCodec.hash(
                plan.copy(assertionLanguageVersion = "other-language")
            )
        )
        assertNotEquals(
            plan.planHash,
            CompiledGeneralTestPlanCodec.hash(
                plan.copy(
                    target = ResolvedGeneralTestTarget.OutputTextFile(
                        relativePath = "reports/final.md",
                        mediaType = "text/plain"
                    )
                )
            )
        )
    }

    @Test
    fun `verified readback and recompilation reject persistence-shaped tampering`() {
        val definition = GeneralTestDefinitionV1(
            GeneralTestTargetV1.OutputTextFile("reports/final.md"),
            GeneralAssertionV1.TextContains("required evidence", true)
        )
        val plan = success(
            GeneralTestCompiler.compile("readback", definition, textOutputSpec)
        ).plan
        val encoded = CompiledGeneralTestPlanCodec.encode(plan)

        assertEquals(plan, CompiledGeneralTestPlanCodec.decodeVerified(encoded))
        assertTrue(GeneralTestCompiler.verify(plan, definition, textOutputSpec))
        assertFalse(
            GeneralTestCompiler.verify(
                plan,
                definition.copy(
                    assertion = GeneralAssertionV1.TextContains("different", true)
                ),
                textOutputSpec
            )
        )
        assertFalse(
            GeneralTestCompiler.verify(
                plan,
                definition,
                FinalOutputSpec("reports/final.md", "text/plain")
            )
        )
        assertFalse(
            GeneralTestCompiler.verify(
                plan.copy(evaluatorVersion = "other").withComputedHash(),
                definition,
                textOutputSpec
            )
        )
        assertFailsWith<IllegalArgumentException> {
            CompiledGeneralTestPlanCodec.decodeVerified(
                encoded.replace("required evidence", "tampered evidence")
            )
        }
    }

    @Test
    fun `null unknown unsupported and malformed definitions return exact typed errors`() {
        assertCodes(
            GeneralTestCompiler.compile("null-typed", null, textOutputSpec),
            GeneralTestCompileErrorCode.GENERAL_TEST_NULL
        )
        assertCodes(
            GeneralTestCompiler.compileEncoded("null-json", "null", textOutputSpec),
            GeneralTestCompileErrorCode.GENERAL_TEST_NULL
        )
        assertCodes(
            GeneralTestCompiler.compileEncoded("null-reference", null, textOutputSpec),
            GeneralTestCompileErrorCode.GENERAL_TEST_NULL
        )
        assertCodes(
            GeneralTestCompiler.compile(
                testId = "unknown-version",
                definition = validTextDefinition(),
                outputSpec = textOutputSpec,
                assertionLanguageVersion = "general-test-v2"
            ),
            GeneralTestCompileErrorCode.GENERAL_TEST_UNKNOWN_VERSION
        )
        assertCodes(
            GeneralTestCompiler.compileEncoded(
                testId = "unsupported",
                definitionJson =
                    """{"target":{"type":"FINAL_OUTPUT_TEXT"},"assertion":{"type":"TEXT_EQUALS","expected":"x"}}""",
                outputSpec = textOutputSpec
            ),
            GeneralTestCompileErrorCode.GENERAL_TEST_UNSUPPORTED_ASSERTION
        )
        assertCodes(
            GeneralTestCompiler.compileEncoded("malformed", "{", textOutputSpec),
            GeneralTestCompileErrorCode.GENERAL_TEST_NOT_EXECUTABLE
        )
        assertCodes(
            GeneralTestCompiler.compileEncoded(
                "unknown-target",
                """{"target":{"type":"OTHER"},"assertion":{"type":"TEXT_CONTAINS","expected":"x","caseSensitive":true}}""",
                textOutputSpec
            ),
            GeneralTestCompileErrorCode.GENERAL_TEST_NOT_EXECUTABLE
        )
    }

    @Test
    fun `expected text uses a nonblank four KiB UTF-8 boundary`() {
        assertCodes(
            compileTextContains(" \t\n"),
            GeneralTestCompileErrorCode.GENERAL_TEST_EMPTY_EXPECTED_TEXT
        )
        assertIs<GeneralTestCompileResult.Success>(
            compileTextContains("é".repeat(GeneralTestLimits.MAX_EXPECTED_TEXT_UTF8_BYTES / 2))
        )
        assertCodes(
            compileTextContains("é".repeat(GeneralTestLimits.MAX_EXPECTED_TEXT_UTF8_BYTES / 2 + 1)),
            GeneralTestCompileErrorCode.GENERAL_TEST_NOT_EXECUTABLE
        )
        assertCodes(
            GeneralTestCompiler.compile(
                "blank-json-string",
                GeneralTestDefinitionV1(
                    GeneralTestTargetV1.OutputJsonFile("metrics.json"),
                    GeneralAssertionV1.JsonPointerEqualsString("/status", "")
                ),
                jsonOutputSpec
            ),
            GeneralTestCompileErrorCode.GENERAL_TEST_EMPTY_EXPECTED_TEXT
        )
    }

    @Test
    fun `RE2 validation applies byte bound before syntax and rejects lookahead`() {
        listOf("", " \t\n").forEach { pattern ->
            assertCodes(
                compilePattern(pattern),
                GeneralTestCompileErrorCode.GENERAL_TEST_NOT_EXECUTABLE
            )
        }
        assertIs<GeneralTestCompileResult.Success>(
            compilePattern("a".repeat(GeneralTestLimits.MAX_RE2_PATTERN_UTF8_BYTES))
        )
        assertIs<GeneralTestCompileResult.Success>(
            compilePattern("é".repeat(GeneralTestLimits.MAX_RE2_PATTERN_UTF8_BYTES / 2))
        )
        assertCodes(
            compilePattern("é".repeat(GeneralTestLimits.MAX_RE2_PATTERN_UTF8_BYTES / 2 + 1)),
            GeneralTestCompileErrorCode.GENERAL_TEST_PATTERN_TOO_LARGE
        )
        assertCodes(
            compilePattern("a".repeat(GeneralTestLimits.MAX_RE2_PATTERN_UTF8_BYTES + 1) + "(?=x)"),
            GeneralTestCompileErrorCode.GENERAL_TEST_PATTERN_TOO_LARGE
        )
        assertCodes(
            compilePattern("value(?=unsafe)"),
            GeneralTestCompileErrorCode.GENERAL_TEST_INVALID_RE2
        )
        assertCodes(
            compilePattern("[unterminated"),
            GeneralTestCompileErrorCode.GENERAL_TEST_INVALID_RE2
        )
    }

    @Test
    fun `JSON Pointer root escaping and depth compile to decoded tokens`() {
        val root = success(compilePointer("")).plan.operation
        assertEquals(emptyList(), assertIs<CompiledGeneralOperation.JsonPointerExists>(root).tokens)

        val escaped = success(compilePointer("/a~1b/~0c/")).plan.operation
        assertEquals(
            listOf("a/b", "~c", ""),
            assertIs<CompiledGeneralOperation.JsonPointerExists>(escaped).tokens
        )
        assertEquals(
            listOf(""),
            assertIs<CompiledGeneralOperation.JsonPointerExists>(
                success(compilePointer("/")).plan.operation
            ).tokens
        )
        assertEquals(
            listOf("~1"),
            assertIs<CompiledGeneralOperation.JsonPointerExists>(
                success(compilePointer("/~01")).plan.operation
            ).tokens
        )
        assertIs<GeneralTestCompileResult.Success>(
            compilePointer((1..GeneralTestLimits.MAX_JSON_POINTER_DEPTH).joinToString("") { "/$it" })
        )
        assertCodes(
            compilePointer((1..GeneralTestLimits.MAX_JSON_POINTER_DEPTH + 1).joinToString("") { "/$it" }),
            GeneralTestCompileErrorCode.GENERAL_TEST_INVALID_JSON_POINTER
        )
        listOf("not-a-pointer", "/bad~", "/bad~2escape").forEach { pointer ->
            assertCodes(
                compilePointer(pointer),
                GeneralTestCompileErrorCode.GENERAL_TEST_INVALID_JSON_POINTER
            )
        }
    }

    @Test
    fun `file targets require the exact canonical FinalOutputSpec path`() {
        listOf(
            "../reports/final.md",
            "/reports/final.md",
            "C:/reports/final.md",
            "reports\\final.md",
            "./reports/final.md",
            "reports//final.md",
            "reports/../final.md",
            "reports/sibling.md",
            " reports/final.md"
        ).forEach { path ->
            assertCodes(
                GeneralTestCompiler.compile(
                    testId = "path",
                    definition = GeneralTestDefinitionV1(
                        GeneralTestTargetV1.OutputTextFile(path),
                        GeneralAssertionV1.TextContains("evidence", true)
                    ),
                    outputSpec = textOutputSpec
                ),
                GeneralTestCompileErrorCode.GENERAL_TEST_PATH_OUTSIDE_OUTPUT_SPEC
            )
        }

        assertIs<GeneralTestCompileResult.Success>(
            GeneralTestCompiler.compile(
                testId = "exact-path",
                definition = GeneralTestDefinitionV1(
                    GeneralTestTargetV1.OutputTextFile(textOutputSpec.relativePath),
                    GeneralAssertionV1.TextContains("evidence", true)
                ),
                outputSpec = textOutputSpec
            )
        )
    }

    @Test
    fun `file targets reject malformed output authority and canonicalize valid media parameters`() {
        listOf(
            "reports/",
            "reports//final.md",
            "reports/../final.md",
            "reports\\final.md",
            "reports/\u0000final.md"
        ).forEach { invalidAuthorityPath ->
            assertCodes(
                GeneralTestCompiler.compile(
                    testId = "invalid-authority-path",
                    definition = GeneralTestDefinitionV1(
                        GeneralTestTargetV1.OutputTextFile("reports/final.md"),
                        GeneralAssertionV1.TextContains("evidence", true)
                    ),
                    outputSpec = FinalOutputSpec(invalidAuthorityPath, "text/markdown")
                ),
                GeneralTestCompileErrorCode.GENERAL_TEST_PATH_OUTSIDE_OUTPUT_SPEC
            )
        }
        listOf(
            "text/",
            "application",
            "text/plain; charset",
            "text/plain; =utf-8",
            "text/plain; charset=",
            "text/pl ain"
        ).forEach { invalidMediaType ->
            assertCodes(
                GeneralTestCompiler.compile(
                    testId = "invalid-media",
                    definition = GeneralTestDefinitionV1(
                        GeneralTestTargetV1.OutputTextFile("reports/final.md"),
                        GeneralAssertionV1.TextContains("evidence", true)
                    ),
                    outputSpec = FinalOutputSpec("reports/final.md", invalidMediaType)
                ),
                GeneralTestCompileErrorCode.GENERAL_TEST_TARGET_TYPE_MISMATCH
            )
        }

        val plan = success(
            GeneralTestCompiler.compile(
                testId = "canonical-media",
                definition = GeneralTestDefinitionV1(
                    GeneralTestTargetV1.OutputTextFile("reports/final.md"),
                    GeneralAssertionV1.TextContains("evidence", true)
                ),
                outputSpec = FinalOutputSpec(
                    "reports/final.md",
                    "Text/Markdown; Charset=UTF-8"
                )
            )
        ).plan
        assertEquals(
            "text/markdown",
            assertIs<ResolvedGeneralTestTarget.OutputTextFile>(plan.target).mediaType
        )
    }

    @Test
    fun `target assertion and output media types must agree`() {
        assertCodes(
            GeneralTestCompiler.compile(
                "text-on-json-target",
                GeneralTestDefinitionV1(
                    GeneralTestTargetV1.OutputJsonFile("metrics.json"),
                    GeneralAssertionV1.TextContains("evidence", true)
                ),
                jsonOutputSpec
            ),
            GeneralTestCompileErrorCode.GENERAL_TEST_TARGET_TYPE_MISMATCH
        )
        assertCodes(
            GeneralTestCompiler.compile(
                "json-on-text-target",
                GeneralTestDefinitionV1(
                    GeneralTestTargetV1.OutputTextFile("reports/final.md"),
                    GeneralAssertionV1.JsonPointerExists("/status")
                ),
                textOutputSpec
            ),
            GeneralTestCompileErrorCode.GENERAL_TEST_TARGET_TYPE_MISMATCH
        )
        assertCodes(
            GeneralTestCompiler.compile(
                "text-target-json-media",
                GeneralTestDefinitionV1(
                    GeneralTestTargetV1.OutputTextFile("metrics.json"),
                    GeneralAssertionV1.TextContains("evidence", true)
                ),
                jsonOutputSpec
            ),
            GeneralTestCompileErrorCode.GENERAL_TEST_TARGET_TYPE_MISMATCH
        )
        assertCodes(
            GeneralTestCompiler.compile(
                "json-target-text-media",
                GeneralTestDefinitionV1(
                    GeneralTestTargetV1.OutputJsonFile("reports/final.md"),
                    GeneralAssertionV1.JsonPointerExists("/status")
                ),
                textOutputSpec
            ),
            GeneralTestCompileErrorCode.GENERAL_TEST_TARGET_TYPE_MISMATCH
        )
        assertIs<GeneralTestCompileResult.Success>(
            GeneralTestCompiler.compile(
                "vendor-json",
                GeneralTestDefinitionV1(
                    GeneralTestTargetV1.OutputJsonFile("metrics.json"),
                    GeneralAssertionV1.JsonPointerExists("/status")
                ),
                FinalOutputSpec("metrics.json", "application/problem+json")
            )
        )
        assertCodes(
            GeneralTestCompiler.compile(
                "non-application-vendor-json",
                GeneralTestDefinitionV1(
                    GeneralTestTargetV1.OutputJsonFile("metrics.json"),
                    GeneralAssertionV1.JsonPointerExists("/status")
                ),
                FinalOutputSpec("metrics.json", "text/example+json")
            ),
            GeneralTestCompileErrorCode.GENERAL_TEST_TARGET_TYPE_MISMATCH
        )
    }

    @Test
    fun `final output text binds canonical textual output authority`() {
        val definition = GeneralTestDefinitionV1(
            GeneralTestTargetV1.FinalOutputText,
            GeneralAssertionV1.TextContains("evidence", true)
        )
        val plan = success(
            GeneralTestCompiler.compile("final-text", definition, textOutputSpec)
        ).plan

        assertEquals(
            ResolvedGeneralTestTarget.FinalOutputText(
                relativePath = "reports/final.md",
                mediaType = "text/markdown"
            ),
            plan.target
        )
        assertFalse(
            GeneralTestCompiler.verify(
                plan,
                definition,
                FinalOutputSpec("reports/other.md", "text/markdown")
            )
        )
        assertFalse(
            GeneralTestCompiler.verify(
                plan,
                definition,
                FinalOutputSpec("reports/final.md", "application/json")
            )
        )
        assertIs<GeneralTestCompileResult.Success>(
            GeneralTestCompiler.compile(
                "final-json-text",
                definition,
                FinalOutputSpec("metrics.json", "application/problem+json")
            )
        )
        assertCodes(
            GeneralTestCompiler.compile(
                "final-binary",
                definition,
                FinalOutputSpec("report.pdf", "application/pdf")
            ),
            GeneralTestCompileErrorCode.GENERAL_TEST_TARGET_TYPE_MISMATCH
        )
        assertCodes(
            GeneralTestCompiler.compile(
                "final-invalid-path",
                definition,
                FinalOutputSpec("../report.md", "text/markdown")
            ),
            GeneralTestCompileErrorCode.GENERAL_TEST_PATH_OUTSIDE_OUTPUT_SPEC
        )
    }

    @Test
    fun `range bounds must be finite and ordered while equal endpoints remain executable`() {
        assertCodes(
            compileRange(Double.NaN, 1.0),
            GeneralTestCompileErrorCode.GENERAL_TEST_RANGE_NON_FINITE
        )
        assertCodes(
            compileRange(0.0, Double.POSITIVE_INFINITY),
            GeneralTestCompileErrorCode.GENERAL_TEST_RANGE_NON_FINITE
        )
        assertCodes(
            compileRange(2.0, 1.0),
            GeneralTestCompileErrorCode.GENERAL_TEST_RANGE_REVERSED
        )
        assertIs<GeneralTestCompileResult.Success>(compileRange(1.0, 1.0))
    }

    @Test
    fun `all required error codes are emitted and execution bounds remain pinned`() {
        val emittedCodes = buildSet {
            addAll(codes(GeneralTestCompiler.compile("null", null, textOutputSpec)))
            addAll(
                codes(
                    GeneralTestCompiler.compile(
                        "version",
                        validTextDefinition(),
                        textOutputSpec,
                        "other"
                    )
                )
            )
            addAll(codes(compileTextContains("")))
            addAll(
                codes(
                    GeneralTestCompiler.compile(
                        "path",
                        GeneralTestDefinitionV1(
                            GeneralTestTargetV1.OutputTextFile("../final.md"),
                            GeneralAssertionV1.TextContains("x", true)
                        ),
                        textOutputSpec
                    )
                )
            )
            addAll(
                codes(
                    GeneralTestCompiler.compile(
                        "target",
                        GeneralTestDefinitionV1(
                            GeneralTestTargetV1.FinalOutputText,
                            GeneralAssertionV1.JsonPointerExists("")
                        ),
                        textOutputSpec
                    )
                )
            )
            addAll(codes(compilePointer("invalid")))
            addAll(codes(compilePattern("(?=invalid)")))
            addAll(codes(compilePattern("a".repeat(GeneralTestLimits.MAX_RE2_PATTERN_UTF8_BYTES + 1))))
            addAll(codes(compileRange(Double.NEGATIVE_INFINITY, 1.0)))
            addAll(codes(compileRange(2.0, 1.0)))
            addAll(
                codes(
                    GeneralTestCompiler.compileEncoded(
                        "unsupported",
                        """{"target":{"type":"FINAL_OUTPUT_TEXT"},"assertion":{"type":"OTHER"}}""",
                        textOutputSpec
                    )
                )
            )
            addAll(codes(GeneralTestCompiler.compileEncoded("malformed", "[]", textOutputSpec)))
        }

        assertEquals(GeneralTestCompileErrorCode.entries.toSet(), emittedCodes)
        assertEquals(64, GeneralTestLimits.MAX_TESTS_PER_SUITE)
        assertEquals(4 * 1024, GeneralTestLimits.MAX_EXPECTED_TEXT_UTF8_BYTES)
        assertEquals(4 * 1024, GeneralTestLimits.MAX_RE2_PATTERN_UTF8_BYTES)
        assertEquals(32, GeneralTestLimits.MAX_JSON_POINTER_DEPTH)
        assertEquals(2 * 1024 * 1024, GeneralTestLimits.MAX_OUTPUT_TEXT_FILE_BYTES)
        assertEquals(64, GeneralTestLimits.MAX_PARSED_JSON_DEPTH)
    }

    private fun validTextDefinition(): GeneralTestDefinitionV1 =
        GeneralTestDefinitionV1(
            GeneralTestTargetV1.FinalOutputText,
            GeneralAssertionV1.TextContains("evidence", true)
        )

    private fun compileTextContains(expected: String): GeneralTestCompileResult =
        GeneralTestCompiler.compile(
            "text",
            GeneralTestDefinitionV1(
                GeneralTestTargetV1.FinalOutputText,
                GeneralAssertionV1.TextContains(expected, true)
            ),
            textOutputSpec
        )

    private fun compilePattern(pattern: String): GeneralTestCompileResult =
        GeneralTestCompiler.compile(
            "pattern",
            GeneralTestDefinitionV1(
                GeneralTestTargetV1.OutputTextFile("reports/final.md"),
                GeneralAssertionV1.TextMatchesRe2(pattern)
            ),
            textOutputSpec
        )

    private fun compilePointer(pointer: String): GeneralTestCompileResult =
        GeneralTestCompiler.compile(
            "pointer",
            GeneralTestDefinitionV1(
                GeneralTestTargetV1.OutputJsonFile("metrics.json"),
                GeneralAssertionV1.JsonPointerExists(pointer)
            ),
            jsonOutputSpec
        )

    private fun compileRange(
        minimumInclusive: Double,
        maximumInclusive: Double
    ): GeneralTestCompileResult =
        GeneralTestCompiler.compile(
            "range",
            GeneralTestDefinitionV1(
                GeneralTestTargetV1.OutputJsonFile("metrics.json"),
                GeneralAssertionV1.JsonPointerNumberRange(
                    "/score",
                    minimumInclusive,
                    maximumInclusive
                )
            ),
            jsonOutputSpec
        )

    private fun success(result: GeneralTestCompileResult): GeneralTestCompileResult.Success =
        assertIs(result)

    private fun codes(result: GeneralTestCompileResult): Set<GeneralTestCompileErrorCode> =
        assertIs<GeneralTestCompileResult.Failure>(result).errors.mapTo(linkedSetOf()) { it.code }

    private fun assertCodes(
        result: GeneralTestCompileResult,
        vararg expected: GeneralTestCompileErrorCode
    ) {
        assertEquals(expected.toSet(), codes(result))
    }

    private companion object {
        val textOutputSpec = FinalOutputSpec(
            relativePath = "reports/final.md",
            mediaType = "text/markdown"
        )
        val jsonOutputSpec = FinalOutputSpec(
            relativePath = "metrics.json",
            mediaType = "application/json"
        )
    }
}
