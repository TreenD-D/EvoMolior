package com.achulkov.evokammer.api.service

import com.achulkov.evokammer.competition.grader.AutomatedTest
import com.achulkov.evokammer.competition.grader.ComparativePair
import com.achulkov.evokammer.competition.grader.FailureMode
import com.achulkov.evokammer.competition.grader.GradingSheet
import com.achulkov.evokammer.core.model.DimensionScore
import com.achulkov.evokammer.core.model.ScoringDimension
import io.github.aakira.napier.Napier
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Deterministically evaluates a sealed test suite against a canonical substrate snapshot.
 *
 * The sealed suite is kept as opaque JSON at genesis, so this evaluator intentionally
 * supports a small set of test-object shapes rather than a single rigid schema. The goal is
 * to preserve determinism while accepting the council's existing output formats.
 */
internal class SealedTestSuiteEvaluator(
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true }
) {

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
        return GradingSheet(
            sheetId = "sealed-suite-$roundIndex-${suiteHash.take(12)}",
            runId = runId,
            roundIndex = roundIndex,
            graderGroupId = "sealed-suite",
            rubricDimensions = scoringDimensions.map { dimension ->
                com.achulkov.evokammer.competition.grader.RubricDimension(
                    dimensionId = dimension.id,
                    name = dimension.name,
                    weight = dimension.weight,
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
        scoringDimensions: List<ScoringDimension>
    ): DeterministicRoundAssessment {
        val parsed = parseSuite(suiteJson)
        val testResults = parsed.tests.map { test ->
            ParsedTestResult(
                test = test,
                passed = evaluateTest(test, canonicalSnapshot)
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
                    "— scoring remains zero because no deterministic evidence was available"
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
            return ParsedSuite(emptyList(), emptyList())
        }

        return try {
            val root = json.parseToJsonElement(suiteJson)
            when (root) {
                is JsonObject -> ParsedSuite(
                    tests = extractTests(root),
                    failureModes = extractFailureModes(root)
                )
                is JsonArray -> ParsedSuite(
                    tests = root.mapIndexedNotNull { index, element ->
                        parseTestObject(element.jsonObject, index)
                    },
                    failureModes = emptyList()
                )
                else -> ParsedSuite(emptyList(), emptyList())
            }
        } catch (e: Exception) {
            Napier.w("SealedTestSuiteEvaluator: failed to parse sealed suite JSON: ${e.message}")
            ParsedSuite(emptyList(), emptyList())
        }
    }

    private fun extractTests(root: JsonObject): List<ParsedTest> {
        val testsElement = root["tests"] ?: root["automatedTests"] ?: return emptyList()
        val array = testsElement as? JsonArray ?: return emptyList()
        return array.mapIndexedNotNull { index, element ->
            parseTestObject(element.jsonObject, index)
        }
    }

    private fun extractFailureModes(root: JsonObject): List<ParsedFailureMode> {
        val failureElement = root["failureModes"] ?: return emptyList()
        val array = failureElement as? JsonArray ?: return emptyList()
        return array.mapIndexedNotNull { index, element ->
            val obj = element.jsonObject
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
        val description = firstString(obj, "description", "name", "label") ?: id
        val expected = firstString(
            obj,
            "expectedOutputPattern",
            "pattern",
            "regex",
            "contains",
            "assert"
        ) ?: return null
        val penalty = firstDouble(obj, "penaltyOnFailure", "failurePenalty", "penalty") ?: 1.0
        val mandatory = firstBoolean(obj, "isMandatory", "mandatory") ?: false
        return ParsedTest(
            id = id,
            description = description,
            expectedOutputPattern = expected,
            penaltyOnFailure = penalty,
            isMandatory = mandatory
        )
    }

    private fun evaluateTest(test: ParsedTest, snapshot: String): Boolean {
        val asserted = test.expectedOutputPattern.trim()
        if (asserted.equals("true", ignoreCase = true)) return snapshot.isNotBlank()
        if (asserted.equals("false", ignoreCase = true)) return false

        return try {
            Regex(asserted, setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                .containsMatchIn(snapshot)
        } catch (_: Exception) {
            snapshot.contains(asserted, ignoreCase = true)
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
        val failureModes: List<ParsedFailureMode>
    )

    private data class ParsedTest(
        val id: String,
        val description: String,
        val expectedOutputPattern: String,
        val penaltyOnFailure: Double,
        val isMandatory: Boolean
    )

    private data class ParsedTestResult(
        val test: ParsedTest,
        val passed: Boolean
    )

    private data class ParsedFailureMode(
        val id: String,
        val description: String,
        val penaltyWeight: Double
    )
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
