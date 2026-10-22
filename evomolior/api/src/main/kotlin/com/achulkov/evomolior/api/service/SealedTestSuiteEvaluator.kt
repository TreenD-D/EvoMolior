package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.competition.grader.AutomatedTest
import com.achulkov.evomolior.competition.grader.ComparativePair
import com.achulkov.evomolior.competition.grader.FailureMode
import com.achulkov.evomolior.competition.grader.GradingSheet
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.ToolExecutionPurpose
import com.achulkov.evomolior.core.atlas.ToolExecutionRecord
import com.achulkov.evomolior.core.atlas.ToolExecutionResult
import com.achulkov.evomolior.core.council.CompiledGeneralOperation
import com.achulkov.evomolior.core.council.CompiledGeneralTestPlan
import com.achulkov.evomolior.core.council.CouncilContractHashing
import com.achulkov.evomolior.core.council.CouncilSealedTestWireKind
import com.achulkov.evomolior.core.council.GeneralTestLimits
import com.achulkov.evomolior.core.council.ResolvedGeneralTestTarget
import com.achulkov.evomolior.core.council.SealedTestSuiteV3
import com.achulkov.evomolior.core.council.SealedTestSuiteV3Authority
import com.achulkov.evomolior.core.council.SealedTestSuiteV3Codec
import com.achulkov.evomolior.core.council.SealedTestSuiteV3ValidationContext
import com.achulkov.evomolior.core.council.SealedTestV3
import com.achulkov.evomolior.core.council.TypedJsonValueCodec
import com.achulkov.evomolior.core.grader.SafeRegexMatcher
import com.achulkov.evomolior.core.model.DimensionScore
import com.achulkov.evomolior.core.model.ScoringDimension
import com.achulkov.evomolior.orchestration.lifecycle.SealedPromotionTestFixture
import io.github.aakira.napier.Napier
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Deterministically evaluates sealed tests without granting filesystem authority.
 *
 * Active v3 execution strictly verifies [SealedTestSuiteV3] and executes only persisted
 * [CompiledGeneralTestPlan] operations. The permissive parser below remains isolated for
 * pre-v3 compatibility and historical archive diagnostics.
 */
internal class SealedTestSuiteEvaluator(
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true }
) {
    private val strictOutputJson = Json {
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
        allowSpecialFloatingPointValues = false
    }

    /**
     * Strictly inspects one active v3 suite against external receiver, Atlas, and Task authority.
     *
     * GENERAL semantics are accepted only from persisted compiler-owned plans. Source definitions
     * are used by the v3 authority verifier solely to prove that each plan still recompiles
     * exactly; this evaluator never heuristically interprets them.
     */
    fun inspectCompiledSuite(
        suiteJson: String,
        validationContext: SealedTestSuiteV3ValidationContext
    ): SealedTestSuiteInspection {
        if (suiteJson.isBlank()) {
            return SealedTestSuiteInspection(
                declaredTestCount = 0,
                executableTestCount = 0,
                failures = listOf("sealed-tests-v3 suite must not be blank")
            )
        }
        val root = runCatching { json.parseToJsonElement(suiteJson) as? JsonObject }
            .getOrNull()
        val schemaVersion = root?.get("schemaVersion")
            ?.jsonPrimitive
            ?.contentOrNull
        if (
            schemaVersion != null &&
            schemaVersion != SealedTestSuiteV3Authority.SCHEMA_VERSION
        ) {
            return SealedTestSuiteInspection(
                declaredTestCount = (root["tests"] as? JsonArray)?.size ?: 0,
                executableTestCount = 0,
                failures = listOf(
                    "UNSUPPORTED_EXECUTABLE_TEST_VERSION: active compiled evaluation requires " +
                        SealedTestSuiteV3Authority.SCHEMA_VERSION +
                        ", received $schemaVersion"
                )
            )
        }
        val suite = runCatching {
            SealedTestSuiteV3Codec.decode(suiteJson)
        }.getOrElse { error ->
            return SealedTestSuiteInspection(
                declaredTestCount = 0,
                executableTestCount = 0,
                failures = listOf(
                    "sealed-tests-v3 strict decode failed: " +
                        error.message.orEmpty().take(MAX_DIAGNOSTIC_CHARS)
                )
            )
        }
        val failures = SealedTestSuiteV3Authority.validationErrors(
            suite = suite,
            context = validationContext
        )
        return SealedTestSuiteInspection(
            declaredTestCount = suite.tests.size,
            executableTestCount = if (failures.isEmpty()) suite.tests.size else 0,
            failures = failures
        )
    }

    /**
     * Resolves exact private v3 promotion fixtures after complete suite/context verification.
     */
    fun promotionFixtures(
        suiteJson: String,
        validationContext: SealedTestSuiteV3ValidationContext,
        nodeId: String,
        opportunityId: String,
        testIds: Set<String>
    ): List<SealedPromotionTestFixture>? {
        val suite = decodeVerifiedV3(suiteJson, validationContext)
        val selected = suite.tests.mapNotNull { test ->
            val promotion = test as? SealedTestV3.Promotion ?: return@mapNotNull null
            if (
                promotion.testId !in testIds ||
                promotion.nodeId != nodeId ||
                promotion.opportunityId != opportunityId
            ) {
                return@mapNotNull null
            }
            SealedPromotionTestFixture(
                id = promotion.testId,
                input = TypedJsonValueCodec.toJsonElement(promotion.input),
                expectedOutput = TypedJsonValueCodec.toJsonElement(promotion.expectedOutput)
            )
        }
        return selected.takeIf {
            it.size == testIds.size &&
                it.mapTo(linkedSetOf(), SealedPromotionTestFixture::id) == testIds
        }
    }

    /**
     * Builds a grading sheet from one verified v3 authority without exposing raw definitions.
     */
    fun buildGradingSheet(
        runId: String,
        roundIndex: Int,
        suiteJson: String,
        validationContext: SealedTestSuiteV3ValidationContext,
        scoringDimensions: List<ScoringDimension>
    ): GradingSheet {
        val suite = decodeVerifiedV3(suiteJson, validationContext)
        val rubricWeights = normalisedRubricWeights(scoringDimensions)
        return GradingSheet(
            sheetId = "sealed-suite-$roundIndex-${suite.suiteHash.take(12)}",
            runId = runId,
            roundIndex = roundIndex,
            graderGroupId = "sealed-suite",
            rubricDimensions = scoringDimensions.map { dimension ->
                com.achulkov.evomolior.competition.grader.RubricDimension(
                    dimensionId = dimension.id,
                    name = dimension.name,
                    weight = rubricWeights.getValue(dimension.id),
                    scoringGuidance =
                        "Deterministic scoring derived from verified sealed-tests-v3 plans.",
                    maxScore = dimension.maxScore,
                    dstNodeRef = dimension.dstNodeRef
                )
            },
            automatedTests = suite.tests.map(::v3AutomatedTest),
            failureModes = emptyList(),
            comparativePairs = emptyList<ComparativePair>(),
            sealedAt = System.currentTimeMillis()
        )
    }

    /**
     * Executes one verified v3 suite against explicit in-memory output and promotion authority.
     *
     * No filesystem path, root, callback, mount, or substrate snapshot enters this boundary.
     */
    fun assessGroup(
        suiteJson: String,
        validationContext: SealedTestSuiteV3ValidationContext,
        input: SealedTestEvaluationInputV3,
        scoringDimensions: List<ScoringDimension>,
        selection: SealedTestEvaluationSelection = SealedTestEvaluationSelection.COMPLETE_SUITE
    ): DeterministicRoundAssessment {
        val suite = decodeVerifiedV3(suiteJson, validationContext)
        val selectedTests = suite.tests.filter(selection::includes)
        val output = input.finalOutput?.also { candidate ->
            require(candidate.hasValidHash()) {
                "Sealed-test final output content hash is invalid"
            }
        }
        val outputUtf8Bytes = output?.content?.let { content ->
            if (content.length > GeneralTestLimits.MAX_OUTPUT_TEXT_FILE_BYTES) {
                GeneralTestLimits.MAX_OUTPUT_TEXT_FILE_BYTES + 1
            } else {
                content.encodeToByteArray().size
            }
        }
        val parsedOutputJson = if (
            output != null &&
            outputUtf8Bytes != null &&
            outputUtf8Bytes <= GeneralTestLimits.MAX_OUTPUT_TEXT_FILE_BYTES &&
            selectedTests.any(::requiresParsedJson)
        ) {
            parseBoundedOutputJson(output.content)
        } else {
            null
        }
        val results = selectedTests.map { test ->
            V3TestResult(
                testId = test.testId,
                passed = when (test) {
                    is SealedTestV3.General -> executeCompiledGeneralPlan(
                        plan = test.compiledPlan,
                        output = output,
                        outputUtf8Bytes = outputUtf8Bytes,
                        parsedOutputJson = parsedOutputJson
                    )
                    is SealedTestV3.Promotion -> evaluatePromotion(
                        test = test,
                        input = input
                    )
                }
            )
        }
        return buildV3Assessment(
            suite = suite,
            input = input,
            scoringDimensions = scoringDimensions,
            results = results
        )
    }

    /**
     * Inspects whether every declared sealed test can be evaluated before a costly round starts.
     *
     * Compiled Council suites wrap each exact test definition in `testDefinitionJson`; legacy
     * suites place the assertion fields directly on the test object. This reader is retained only
     * for legacy non-v3 compatibility and archive diagnostics.
     */
    fun inspectSuite(suiteJson: String): SealedTestSuiteInspection {
        val parsed = parseSuite(suiteJson)
        return SealedTestSuiteInspection(
            declaredTestCount = parsed.declaredTestCount,
            executableTestCount = parsed.tests.size,
            failures = parsed.failures
        )
    }

    /**
     * Rejects context-free active execution.
     *
     * Appendix-B acceptance must call the v3 overload with exact external validation authority.
     * Legacy v2 remains readable through [inspectSuite] but cannot be activated here.
     */
    fun inspectCompiledSuite(suiteJson: String): SealedTestSuiteInspection {
        val inspection = inspectSuite(suiteJson)
        return inspection.copy(
            executableTestCount = 0,
            failures = (
                inspection.failures +
                    "UNSUPPORTED_EXECUTABLE_TEST_VERSION: active compiled evaluation requires " +
                    "${SealedTestSuiteV3Authority.SCHEMA_VERSION} and external validation context"
                ).distinct()
        )
    }

    /** Resolves exact private promotion fixtures without exposing them to worker views. */
    fun promotionFixtures(
        suiteJson: String,
        nodeId: String,
        opportunityId: String,
        testIds: Set<String>
    ): List<SealedPromotionTestFixture>? {
        val selected = parseSuite(suiteJson).tests.mapNotNull { test ->
            val promotion = test.evaluation as? ParsedTestEvaluation.Promotion
                ?: return@mapNotNull null
            if (
                test.id !in testIds ||
                promotion.nodeId != nodeId ||
                promotion.opportunityId != opportunityId
            ) {
                return@mapNotNull null
            }
            SealedPromotionTestFixture(
                id = test.id,
                input = promotion.input,
                expectedOutput = promotion.expectedOutput
            )
        }
        return selected.takeIf {
            it.size == testIds.size &&
                it.mapTo(linkedSetOf(), SealedPromotionTestFixture::id) == testIds
        }
    }

    /**
     * Builds a sealed [GradingSheet] for the round from the council's test suite JSON.
     *
     * The resulting sheet keeps the sealed automated tests available to the blind scorer so
     * mandatory failures can still be penalised after the reveal.
     */
    fun buildGradingSheet(
        runId: String,
        roundIndex: Int,
        suiteJson: String,
        suiteHash: String,
        scoringDimensions: List<ScoringDimension>
    ): GradingSheet {
        val parsed = parseSuite(suiteJson)
        val rubricWeights = normalisedRubricWeights(scoringDimensions)
        return GradingSheet(
            sheetId = "sealed-suite-$roundIndex-${suiteHash.take(12)}",
            runId = runId,
            roundIndex = roundIndex,
            graderGroupId = "sealed-suite",
            rubricDimensions = scoringDimensions.map { dimension ->
                com.achulkov.evomolior.competition.grader.RubricDimension(
                    dimensionId = dimension.id,
                    name = dimension.name,
                    weight = rubricWeights.getValue(dimension.id),
                    scoringGuidance = "Deterministic scoring derived from the sealed test suite and canonical substrate snapshot.",
                    maxScore = dimension.maxScore,
                    dstNodeRef = dimension.dstNodeRef
                )
            },
            automatedTests = parsed.tests.map { test ->
                AutomatedTest(
                    testId = test.id,
                    description = test.description,
                    expectedOutputPattern = test.expectedOutputPattern,
                    penaltyOnFailure = test.penaltyOnFailure,
                    isMandatory = test.isMandatory
                )
            },
            failureModes = parsed.failureModes.map { mode ->
                FailureMode(
                    modeId = mode.id,
                    description = mode.description,
                    penaltyWeight = mode.penaltyWeight
                )
            },
            comparativePairs = emptyList<ComparativePair>(),
            sealedAt = System.currentTimeMillis()
        )
    }

    private fun normalisedRubricWeights(scoringDimensions: List<ScoringDimension>): Map<String, Double> {
        val total = scoringDimensions.sumOf { it.weight }.takeIf { it > 0.0 } ?: 1.0
        return scoringDimensions.associate { dimension ->
            dimension.id to (dimension.weight / total)
        }
    }

    private fun decodeVerifiedV3(
        suiteJson: String,
        validationContext: SealedTestSuiteV3ValidationContext
    ): SealedTestSuiteV3 =
        SealedTestSuiteV3Codec.decodeVerified(suiteJson, validationContext)

    private fun v3AutomatedTest(test: SealedTestV3): AutomatedTest = when (test) {
        is SealedTestV3.General -> AutomatedTest(
            testId = test.testId,
            description = "Execute compiler-owned ${operationName(test.compiledPlan.operation)} plan",
            expectedOutputPattern = "compiled-general-plan:${test.compiledPlan.planHash}",
            penaltyOnFailure = 1.0,
            isMandatory = true
        )
        is SealedTestV3.Promotion -> AutomatedTest(
            testId = test.testId,
            description = "Verify exact sealed promotion execution",
            expectedOutputPattern = "authoritative-promotion-execution:${test.testId}",
            penaltyOnFailure = 1.0,
            isMandatory = true
        )
    }

    private fun operationName(operation: CompiledGeneralOperation): String = when (operation) {
        is CompiledGeneralOperation.TextContains -> "TEXT_CONTAINS"
        is CompiledGeneralOperation.TextMatchesRe2 -> "TEXT_MATCHES_RE2"
        is CompiledGeneralOperation.JsonPointerExists -> "JSON_POINTER_EXISTS"
        is CompiledGeneralOperation.JsonPointerEqualsString -> "JSON_POINTER_EQUALS_STRING"
        is CompiledGeneralOperation.JsonPointerNumberRange -> "JSON_POINTER_NUMBER_RANGE"
    }

    private fun executeCompiledGeneralPlan(
        plan: CompiledGeneralTestPlan,
        output: SealedTestOutputArtifactV3?,
        outputUtf8Bytes: Int?,
        parsedOutputJson: JsonElement?
    ): Boolean {
        output ?: return false
        val expectedPath = when (val target = plan.target) {
            is ResolvedGeneralTestTarget.FinalOutputText -> target.relativePath
            is ResolvedGeneralTestTarget.OutputTextFile -> target.relativePath
            is ResolvedGeneralTestTarget.OutputJsonFile -> target.relativePath
        }
        val expectedMediaType = when (val target = plan.target) {
            is ResolvedGeneralTestTarget.FinalOutputText -> target.mediaType
            is ResolvedGeneralTestTarget.OutputTextFile -> target.mediaType
            is ResolvedGeneralTestTarget.OutputJsonFile -> target.mediaType
        }
        require(output.relativePath == expectedPath) {
            "Sealed-test output path does not match compiled target authority"
        }
        require(output.mediaType == expectedMediaType) {
            "Sealed-test output media type does not match compiled target authority"
        }
        if (
            outputUtf8Bytes == null ||
            outputUtf8Bytes > GeneralTestLimits.MAX_OUTPUT_TEXT_FILE_BYTES
        ) {
            return false
        }

        return when (val operation = plan.operation) {
            is CompiledGeneralOperation.TextContains ->
                output.content.contains(
                    other = operation.expected,
                    ignoreCase = !operation.caseSensitive
                )
            is CompiledGeneralOperation.TextMatchesRe2 ->
                SafeRegexMatcher.containsMatch(operation.pattern, output.content)
            is CompiledGeneralOperation.JsonPointerExists ->
                parsedOutputJson
                    ?.let { resolveJsonPointer(it, operation.tokens) != null }
                    ?: false
            is CompiledGeneralOperation.JsonPointerEqualsString -> {
                val value = parsedOutputJson
                    ?.let { resolveJsonPointer(it, operation.tokens) }
                value is JsonPrimitive &&
                    value.isString &&
                    value.content == operation.expected
            }
            is CompiledGeneralOperation.JsonPointerNumberRange -> {
                val value = parsedOutputJson
                    ?.let { resolveJsonPointer(it, operation.tokens) }
                val number = (value as? JsonPrimitive)
                    ?.takeUnless(JsonPrimitive::isString)
                    ?.doubleOrNull
                    ?.takeIf(Double::isFinite)
                number != null &&
                    number >= operation.minimumInclusive &&
                    number <= operation.maximumInclusive
            }
        }
    }

    private fun requiresParsedJson(test: SealedTestV3): Boolean =
        test is SealedTestV3.General &&
            when (test.compiledPlan.operation) {
                is CompiledGeneralOperation.JsonPointerExists,
                is CompiledGeneralOperation.JsonPointerEqualsString,
                is CompiledGeneralOperation.JsonPointerNumberRange -> true
                is CompiledGeneralOperation.TextContains,
                is CompiledGeneralOperation.TextMatchesRe2 -> false
            }

    private fun parseBoundedOutputJson(content: String): JsonElement? {
        if (!hasBoundedJsonDepth(content)) return null
        return runCatching {
            strictOutputJson.parseToJsonElement(content)
        }.getOrNull()
    }

    /**
     * Rejects excessive nesting before recursive JSON parsing can consume attacker-controlled
     * stack. Syntax remains the strict JSON parser's authority.
     */
    private fun hasBoundedJsonDepth(content: String): Boolean {
        var depth = 0
        var inString = false
        var escaped = false
        content.forEach { character ->
            if (inString) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> inString = false
                }
            } else {
                when (character) {
                    '"' -> inString = true
                    '{', '[' -> {
                        depth += 1
                        if (depth > GeneralTestLimits.MAX_PARSED_JSON_DEPTH) return false
                    }
                    '}', ']' -> {
                        depth -= 1
                        if (depth < 0) return false
                    }
                }
            }
        }
        return depth == 0 && !inString && !escaped
    }

    private fun resolveJsonPointer(
        root: JsonElement,
        tokens: List<String>
    ): JsonElement? {
        var current = root
        tokens.forEach { token ->
            current = when (current) {
                is JsonObject -> current[token] ?: return null
                is JsonArray -> {
                    val index = jsonArrayIndex(token) ?: return null
                    current.getOrNull(index) ?: return null
                }
                else -> return null
            }
        }
        return current
    }

    private fun jsonArrayIndex(token: String): Int? {
        if (token == "0") return 0
        if (
            token.isEmpty() ||
            token.first() !in '1'..'9' ||
            token.any { it !in '0'..'9' }
        ) {
            return null
        }
        return token.toIntOrNull()
    }

    private fun evaluatePromotion(
        test: SealedTestV3.Promotion,
        input: SealedTestEvaluationInputV3
    ): Boolean {
        val inputHash = AtlasHashing.hashText(
            TypedJsonValueCodec.toCanonicalJson(test.input)
        )
        val expectedOutput = TypedJsonValueCodec.toJsonElement(test.expectedOutput)
        return input.promotionExecutions.any { execution ->
            execution.runId == input.runId &&
                execution.groupId.value == input.groupId &&
                execution.round <= input.roundIndex &&
                execution.nodeId.value == test.nodeId &&
                execution.opportunityId == test.opportunityId &&
                execution.purpose == ToolExecutionPurpose.PROMOTION_TEST &&
                execution.result == ToolExecutionResult.SUCCEEDED &&
                execution.inputHash == inputHash &&
                execution.outputJson == expectedOutput &&
                execution.outputArtifactId ==
                "promotion-output:${execution.toolVersionId.value}:${test.testId}" &&
                execution.hasValidHash()
        }
    }

    private fun buildV3Assessment(
        suite: SealedTestSuiteV3,
        input: SealedTestEvaluationInputV3,
        scoringDimensions: List<ScoringDimension>,
        results: List<V3TestResult>
    ): DeterministicRoundAssessment {
        val passedCount = results.count(V3TestResult::passed)
        val failedCount = results.size - passedCount
        val passedRatio = if (results.isEmpty()) {
            0.0
        } else {
            passedCount.toDouble() / results.size.toDouble()
        }
        val mandatoryPenalty = failedCount.toDouble()
        val baseScore = (passedRatio / (1.0 + mandatoryPenalty)).coerceIn(0.0, 1.0)
        val mandatoryFailed = failedCount > 0
        val dimensionScores = scoringDimensions.map { dimension ->
            DimensionScore(
                dimensionId = dimension.id,
                rawScore = (baseScore * dimension.maxScore).coerceIn(0.0, dimension.maxScore),
                justification = buildString {
                    append("Deterministic sealed-tests-v3 evaluation for ")
                    append(input.groupId)
                    append(" round ")
                    append(input.roundIndex)
                    append(": ")
                    append(passedCount)
                    append("/")
                    append(results.size)
                    append(" tests passed")
                    if (mandatoryFailed) {
                        append(" (mandatory penalty applied: ")
                        append(
                            String.format(
                                java.util.Locale.ROOT,
                                "%.2f",
                                1.0 / (1.0 + mandatoryPenalty)
                            )
                        )
                        append("x multiplier)")
                    }
                }
            )
        }
        return DeterministicRoundAssessment(
            dimensionScores = dimensionScores,
            automatedTestsPassed = passedCount,
            automatedTestsFailed = failedCount,
            overallScore = baseScore,
            gradingSheetApplied =
                "sealed-suite-${input.roundIndex}-${suite.suiteHash.take(12)}",
            failedTestIds = results.filterNot(V3TestResult::passed).map(V3TestResult::testId),
            mandatoryFailure = mandatoryFailed
        )
    }

    /**
     * Produces a deterministic assessment for one group's snapshot at a given round.
     */
    fun assessGroup(
        runId: String,
        groupId: String,
        roundIndex: Int,
        suiteJson: String,
        suiteHash: String,
        canonicalSnapshot: String,
        scoringDimensions: List<ScoringDimension>,
        toolExecutions: Collection<ToolExecutionRecord> = emptyList()
    ): DeterministicRoundAssessment {
        val parsed = parseSuite(suiteJson)
        val testResults = parsed.tests.map { test ->
            ParsedTestResult(
                test = test,
                passed = evaluateTest(
                    test = test,
                    snapshot = canonicalSnapshot,
                    runId = runId,
                    groupId = groupId,
                    roundIndex = roundIndex,
                    toolExecutions = toolExecutions
                )
            )
        }

        val passedCount = testResults.count { it.passed }
        val failedCount = testResults.size - passedCount
        val mandatoryFailedTests = testResults.filter { it.test.isMandatory && !it.passed }
        val mandatoryFailed = mandatoryFailedTests.isNotEmpty()
        val passedRatio = if (testResults.isEmpty()) {
            0.0
        } else {
            passedCount.toDouble() / testResults.size.toDouble()
        }
        // Mandatory failures now apply a soft penalty instead of collapsing the whole round to zero.
        // That keeps the signal visible while still making hard misses materially worse.
        val mandatoryPenalty = mandatoryFailedTests.sumOf {
            it.test.penaltyOnFailure.coerceAtLeast(1.0)
        }
        val baseScore = (passedRatio / (1.0 + mandatoryPenalty)).coerceIn(0.0, 1.0)

        val dimensionScores = scoringDimensions.map { dimension ->
            DimensionScore(
                dimensionId = dimension.id,
                rawScore = (baseScore * dimension.maxScore).coerceIn(0.0, dimension.maxScore),
                justification = buildString {
                    append("Deterministic sealed-suite evaluation for ")
                    append(groupId)
                    append(" round ")
                    append(roundIndex)
                    append(": ")
                    append(passedCount)
                    append("/")
                    append(testResults.size)
                    append(" tests passed")
                    if (mandatoryFailed) {
                        append(" (mandatory penalty applied: ")
                        append(String.format(java.util.Locale.ROOT, "%.2f", 1.0 / (1.0 + mandatoryPenalty)))
                        append("x multiplier)")
                    }
                }
            )
        }

        if (testResults.isEmpty()) {
            Napier.w(
                "SealedTestSuiteEvaluator: no tests parsed for run=$runId group=$groupId round=$roundIndex " +
                    "from ${parsed.declaredTestCount} declared test(s) — scoring remains zero because " +
                    "no deterministic evidence was available" +
                    parsed.failures.takeIf { it.isNotEmpty() }
                        ?.joinToString(prefix = " (", postfix = ")", separator = "; ")
                        .orEmpty()
            )
        }

        return DeterministicRoundAssessment(
            dimensionScores = dimensionScores,
            automatedTestsPassed = passedCount,
            automatedTestsFailed = failedCount,
            overallScore = baseScore,
            gradingSheetApplied = "sealed-suite-$roundIndex-${suiteHash.take(12)}",
            failedTestIds = testResults.filterNot { it.passed }.map { it.test.id },
            mandatoryFailure = mandatoryFailed
        )
    }

    private fun parseSuite(suiteJson: String): ParsedSuite {
        if (suiteJson.isBlank()) {
            return ParsedSuite(emptyList(), emptyList(), 0, emptyList())
        }

        return try {
            val root = json.parseToJsonElement(suiteJson)
            when (root) {
                is JsonObject -> parseObjectSuite(root)
                is JsonArray -> parseTestArray(root, emptyList())
                else -> ParsedSuite(
                    tests = emptyList(),
                    failureModes = emptyList(),
                    declaredTestCount = 0,
                    failures = listOf("sealed suite root must be an object or array")
                )
            }
        } catch (e: Exception) {
            Napier.w("SealedTestSuiteEvaluator: failed to parse sealed suite JSON: ${e.message}")
            ParsedSuite(
                tests = emptyList(),
                failureModes = emptyList(),
                declaredTestCount = 0,
                failures = listOf("sealed suite JSON is invalid: ${e.message.orEmpty().take(160)}")
            )
        }
    }

    private fun parseObjectSuite(root: JsonObject): ParsedSuite {
        if (firstString(root, "schemaVersion") == SealedTestSuiteV3Authority.SCHEMA_VERSION) {
            val declared = (root["tests"] as? JsonArray)?.size ?: 0
            return ParsedSuite(
                tests = emptyList(),
                failureModes = emptyList(),
                declaredTestCount = declared,
                failures = listOf(
                    "sealed-tests-v3 requires the strict compiled-plan evaluator path"
                )
            )
        }
        val testsElement = root["tests"] ?: root["automatedTests"]
            ?: return ParsedSuite(
                tests = emptyList(),
                failureModes = extractFailureModes(root),
                declaredTestCount = 0,
                failures = listOf("sealed suite does not declare tests or automatedTests")
            )
        val array = testsElement as? JsonArray
            ?: return ParsedSuite(
                tests = emptyList(),
                failureModes = extractFailureModes(root),
                declaredTestCount = 0,
                failures = listOf("sealed suite tests field must be an array")
            )
        return parseTestArray(array, extractFailureModes(root))
    }

    private fun parseTestArray(
        tests: JsonArray,
        failureModes: List<ParsedFailureMode>
    ): ParsedSuite {
        val failures = mutableListOf<String>()
        val parsedTests = tests.mapIndexedNotNull { index, element ->
            val obj = element as? JsonObject
            if (obj == null) {
                failures += "test[$index] must be an object"
                return@mapIndexedNotNull null
            }
            parseTestObject(obj, index).also { parsed ->
                if (parsed == null) failures += "test[$index] has no executable assertion"
            }
        }
        return ParsedSuite(parsedTests, failureModes, tests.size, failures)
    }

    private fun extractFailureModes(root: JsonObject): List<ParsedFailureMode> {
        val failureElement = root["failureModes"] ?: return emptyList()
        val array = failureElement as? JsonArray ?: return emptyList()
        return array.mapIndexedNotNull { index, element ->
            val obj = element as? JsonObject ?: return@mapIndexedNotNull null
            val id = firstString(obj, "id", "modeId", "name") ?: return@mapIndexedNotNull null
            ParsedFailureMode(
                id = id,
                description = firstString(obj, "description", "detail", "name") ?: id,
                penaltyWeight = firstDouble(obj, "penaltyWeight", "penalty", "weight") ?: (0.5 + index * 0.0)
            )
        }
    }

    private fun parseTestObject(obj: JsonObject, index: Int): ParsedTest? {
        val id = firstString(obj, "id", "testId", "name") ?: return null
        val compiledKind = firstString(obj, "kind")?.let { raw ->
            runCatching { CouncilSealedTestWireKind.valueOf(raw) }.getOrNull()
                ?: return null
        }
        if (compiledKind != null && !obj.containsKey("testDefinitionJson")) return null
        val definition = parseEmbeddedDefinition(
            obj = obj,
            requireCommitment = compiledKind != null
        ) ?: if (compiledKind == null) obj else return null
        val definitionObject = definition as? JsonObject
        val description = firstString(obj, "description", "name", "label")
            ?: definitionObject?.let { firstString(it, "description", "name", "label") }
            ?: id
        val snapshotPattern = definitionObject?.let {
            firstString(
                it,
                "expectedOutputPattern",
                "pattern",
                "regex",
                "contains",
                "assert",
                "assertion"
            )
        } ?: firstString(
            obj,
            "expectedOutputPattern",
            "pattern",
            "regex",
            "contains",
            "assert",
            "assertion"
        )
        val evaluation = when {
            compiledKind == CouncilSealedTestWireKind.PROMOTION &&
                definitionObject?.keys == PROMOTION_TEST_FIELDS ->
                ParsedTestEvaluation.Promotion(
                    nodeId = firstString(obj, "nodeId") ?: return null,
                    opportunityId = firstString(obj, "opportunityId") ?: return null,
                    input = definitionObject.getValue("input"),
                    inputHash = AtlasHashing.hashText(
                        CouncilContractHashing.canonicalJson(definitionObject.getValue("input"))
                    ),
                    expectedOutput = definitionObject.getValue("expectedOutput")
                )
            compiledKind == CouncilSealedTestWireKind.PROMOTION -> return null
            compiledKind == CouncilSealedTestWireKind.GENERAL && snapshotPattern != null ->
                ParsedTestEvaluation.Snapshot(snapshotPattern)
            compiledKind == CouncilSealedTestWireKind.GENERAL -> return null
            obj.containsKey("testDefinitionJson") -> return null
            snapshotPattern != null -> ParsedTestEvaluation.Snapshot(snapshotPattern)
            else -> return null
        }
        val penalty = definitionObject?.let {
            firstDouble(it, "penaltyOnFailure", "failurePenalty", "penalty")
        } ?: firstDouble(obj, "penaltyOnFailure", "failurePenalty", "penalty") ?: 1.0
        val mandatory = definitionObject?.let {
            firstBoolean(it, "isMandatory", "mandatory")
        } ?: firstBoolean(obj, "isMandatory", "mandatory")
            ?: (evaluation is ParsedTestEvaluation.Promotion)
        return ParsedTest(
            id = id,
            description = description,
            expectedOutputPattern = when (evaluation) {
                is ParsedTestEvaluation.Snapshot -> evaluation.pattern
                is ParsedTestEvaluation.Promotion ->
                    "authoritative-promotion-execution:${id}"
            },
            penaltyOnFailure = penalty,
            isMandatory = mandatory,
            evaluation = evaluation
        )
    }

    private fun parseEmbeddedDefinition(
        obj: JsonObject,
        requireCommitment: Boolean
    ): JsonElement? {
        val raw = obj["testDefinitionJson"] ?: return null
        val definition = when (raw) {
            is JsonPrimitive -> {
                if (!raw.isString) return null
                runCatching { json.parseToJsonElement(raw.content) }.getOrNull() ?: return null
            }
            else -> raw
        }
        val commitmentHash = firstString(obj, "commitmentHash")
        if (requireCommitment && commitmentHash == null) return null
        if (
            commitmentHash != null &&
            !CouncilContractHashing.constantTimeEquals(
                commitmentHash,
                CouncilContractHashing.sha256(CouncilContractHashing.canonicalJson(definition))
            )
        ) {
            return null
        }
        return definition
    }

    private fun evaluateTest(
        test: ParsedTest,
        snapshot: String,
        runId: String,
        groupId: String,
        roundIndex: Int,
        toolExecutions: Collection<ToolExecutionRecord>
    ): Boolean {
        return when (val evaluation = test.evaluation) {
            is ParsedTestEvaluation.Snapshot -> {
                val asserted = evaluation.pattern.trim()
                when {
                    asserted.equals("true", ignoreCase = true) -> snapshot.isNotBlank()
                    asserted.equals("false", ignoreCase = true) -> false
                    else -> try {
                        Regex(asserted, setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                            .containsMatchIn(snapshot)
                    } catch (_: Exception) {
                        snapshot.contains(asserted, ignoreCase = true)
                    }
                }
            }
            is ParsedTestEvaluation.Promotion -> toolExecutions.any { execution ->
                execution.runId == runId &&
                    execution.groupId.value == groupId &&
                    execution.round <= roundIndex &&
                    execution.nodeId.value == evaluation.nodeId &&
                    execution.opportunityId == evaluation.opportunityId &&
                    execution.purpose == ToolExecutionPurpose.PROMOTION_TEST &&
                    execution.result == ToolExecutionResult.SUCCEEDED &&
                    execution.inputHash == evaluation.inputHash &&
                    execution.outputJson == evaluation.expectedOutput &&
                    execution.outputArtifactId ==
                    "promotion-output:${execution.toolVersionId.value}:${test.id}" &&
                    execution.hasValidHash()
            }
        }
    }

    private fun firstString(obj: JsonObject, vararg keys: String): String? {
        keys.forEach { key ->
            val value = obj[key]?.jsonPrimitive?.contentOrNull?.trim()
            if (!value.isNullOrEmpty()) return value
        }
        return null
    }

    private fun firstDouble(obj: JsonObject, vararg keys: String): Double? {
        keys.forEach { key ->
            val primitive = obj[key]?.jsonPrimitive ?: return@forEach
            primitive.doubleOrNull?.let { return it }
        }
        return null
    }

    private fun firstBoolean(obj: JsonObject, vararg keys: String): Boolean? {
        keys.forEach { key ->
            val primitive = obj[key]?.jsonPrimitive ?: return@forEach
            primitive.booleanOrNull?.let { return it }
        }
        return null
    }

    private data class ParsedSuite(
        val tests: List<ParsedTest>,
        val failureModes: List<ParsedFailureMode>,
        val declaredTestCount: Int,
        val failures: List<String>
    )

    private data class ParsedTest(
        val id: String,
        val description: String,
        val expectedOutputPattern: String,
        val penaltyOnFailure: Double,
        val isMandatory: Boolean,
        val evaluation: ParsedTestEvaluation
    )

    private sealed interface ParsedTestEvaluation {
        data class Snapshot(val pattern: String) : ParsedTestEvaluation

        data class Promotion(
            val nodeId: String,
            val opportunityId: String,
            val input: JsonElement,
            val inputHash: String,
            val expectedOutput: JsonElement
        ) : ParsedTestEvaluation
    }

    private data class ParsedTestResult(
        val test: ParsedTest,
        val passed: Boolean
    )

    private data class V3TestResult(
        val testId: String,
        val passed: Boolean
    )

    private data class ParsedFailureMode(
        val id: String,
        val description: String,
        val penaltyWeight: Double
    )

    private companion object {
        const val MAX_DIAGNOSTIC_CHARS = 500
        val PROMOTION_TEST_FIELDS = setOf("input", "expectedOutput")
    }
}

/**
 * Exact in-memory final-output authority available to compiled GENERAL plans.
 *
 * The media type must already be normalized to the canonical base type committed by the compiled
 * target. No host path or filesystem resolver is retained.
 *
 * @param relativePath Exact output-relative path.
 * @param mediaType Exact canonical base media type.
 * @param content Exact textual output content.
 * @param contentHash SHA-256 commitment of [content].
 */
internal data class SealedTestOutputArtifactV3(
    val relativePath: String,
    val mediaType: String,
    val content: String,
    val contentHash: String = AtlasHashing.hashText(content)
) {
    /** Returns whether [contentHash] still commits the exact supplied content. */
    fun hasValidHash(): Boolean =
        CouncilContractHashing.constantTimeEquals(
            contentHash,
            AtlasHashing.hashText(content)
        )
}

/**
 * Complete bounded runtime input for one v3 suite evaluation.
 *
 * @param runId Exact run identity.
 * @param groupId Exact competing group identity.
 * @param roundIndex Current zero-based round.
 * @param finalOutput Receiver-facing output content, or null for promotion-only evaluation.
 * @param promotionExecutions Exact promotion executions visible through the current round.
 */
internal data class SealedTestEvaluationInputV3(
    val runId: String,
    val groupId: String,
    val roundIndex: Int,
    val finalOutput: SealedTestOutputArtifactV3?,
    val promotionExecutions: List<ToolExecutionRecord> = emptyList()
) {
    init {
        require(runId.isNotBlank()) { "V3 sealed-test evaluation requires a run ID" }
        require(groupId.isNotBlank()) { "V3 sealed-test evaluation requires a group ID" }
        require(roundIndex >= 0) { "V3 sealed-test evaluation round must be non-negative" }
    }
}

/**
 * Selects the sealed-test authority addressable by one runtime phase.
 *
 * Final writers can change only receiver-output bytes. Promotion fixtures are immutable
 * run-level authority by publication time and therefore must be checked outside writer retries.
 */
internal enum class SealedTestEvaluationSelection {
    /** Evaluate every GENERAL and PROMOTION test for scoring and publication. */
    COMPLETE_SUITE,

    /** Evaluate only GENERAL tests whose result can change with final-output bytes. */
    FINAL_OUTPUT_ADDRESSABLE,

    /** Evaluate only PROMOTION tests backed by already-recorded run-level executions. */
    RUN_AUTHORITY;

    /** Returns whether [test] belongs to this evaluation selection. */
    fun includes(test: SealedTestV3): Boolean = when (this) {
        COMPLETE_SUITE -> true
        FINAL_OUTPUT_ADDRESSABLE -> test is SealedTestV3.General
        RUN_AUTHORITY -> test is SealedTestV3.Promotion
    }
}

/**
 * Startup-facing sealed-suite compatibility result.
 *
 * @param declaredTestCount Tests present in the sealed authority.
 * @param executableTestCount Tests understood by the deterministic evaluator.
 * @param failures Bounded deterministic incompatibility diagnostics.
 */
internal data class SealedTestSuiteInspection(
    val declaredTestCount: Int,
    val executableTestCount: Int,
    val failures: List<String>
) {
    /** Whether the sealed suite is non-empty and every declared test is executable. */
    val isComplete: Boolean
        get() = declaredTestCount > 0 &&
            executableTestCount == declaredTestCount &&
            failures.isEmpty()
}

/**
 * Deterministic output for one group's sealed-suite evaluation.
 */
internal data class DeterministicRoundAssessment(
    val dimensionScores: List<DimensionScore>,
    val automatedTestsPassed: Int,
    val automatedTestsFailed: Int,
    val overallScore: Double,
    val gradingSheetApplied: String,
    val failedTestIds: List<String>,
    val mandatoryFailure: Boolean
)
