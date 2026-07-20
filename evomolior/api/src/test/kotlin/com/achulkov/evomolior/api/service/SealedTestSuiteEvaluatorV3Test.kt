package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.core.atlas.ArtifactType
import com.achulkov.evomolior.core.atlas.AtlasAgentId
import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.FinalOutputSpec
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.atlas.ToolExecutionPurpose
import com.achulkov.evomolior.core.atlas.ToolExecutionRecord
import com.achulkov.evomolior.core.atlas.ToolExecutionResult
import com.achulkov.evomolior.core.atlas.ToolVersionId
import com.achulkov.evomolior.core.atlas.TypedField
import com.achulkov.evomolior.core.atlas.TypedFieldType
import com.achulkov.evomolior.core.atlas.TypedToolSchemaCompiler
import com.achulkov.evomolior.core.council.CompiledGeneralOperation
import com.achulkov.evomolior.core.council.CouncilPromotionFixtureWireSchemaAuthority
import com.achulkov.evomolior.core.council.GeneralAssertionV1
import com.achulkov.evomolior.core.council.GeneralTestCompileResult
import com.achulkov.evomolior.core.council.GeneralTestCompiler
import com.achulkov.evomolior.core.council.GeneralTestDefinitionV1
import com.achulkov.evomolior.core.council.GeneralTestLimits
import com.achulkov.evomolior.core.council.GeneralTestTargetV1
import com.achulkov.evomolior.core.council.SealedTestIdentityAuthorityV3
import com.achulkov.evomolior.core.council.SealedTestSuiteV3
import com.achulkov.evomolior.core.council.SealedTestSuiteV3Authority
import com.achulkov.evomolior.core.council.SealedTestSuiteV3Codec
import com.achulkov.evomolior.core.council.SealedTestSuiteV3ValidationContext
import com.achulkov.evomolior.core.council.SealedTestV3
import com.achulkov.evomolior.core.council.TypedJsonObjectEntry
import com.achulkov.evomolior.core.council.TypedJsonValue
import com.achulkov.evomolior.core.council.TypedJsonValueCodec
import com.achulkov.evomolior.core.model.ScoringDimension
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SealedTestSuiteEvaluatorV3Test {
    @Test
    fun `v3 inspection verifies complete external authority and legacy path cannot execute it`() {
        val fixture = textFixture(
            "contains" to GeneralAssertionV1.TextContains("alpha", caseSensitive = true)
        )
        val evaluator = SealedTestSuiteEvaluator()

        val inspection = evaluator.inspectCompiledSuite(fixture.encoded, fixture.context)
        val contextFree = evaluator.inspectCompiledSuite(fixture.encoded)
        val historical = evaluator.inspectSuite(fixture.encoded)

        assertTrue(inspection.isComplete)
        assertEquals(1, inspection.executableTestCount)
        assertFalse(contextFree.isComplete)
        assertEquals(0, contextFree.executableTestCount)
        assertTrue(
            contextFree.failures.any {
                it.contains("UNSUPPORTED_EXECUTABLE_TEST_VERSION")
            }
        )
        assertFalse(historical.isComplete)
        assertEquals(0, historical.executableTestCount)
        assertTrue(
            historical.failures.any {
                it.contains("strict compiled-plan evaluator path")
            }
        )
    }

    @Test
    fun `v3 executes text contains and RE2 only from compiled operations`() {
        val fixture = textFixture(
            "contains" to GeneralAssertionV1.TextContains(
                expected = "alpha beta",
                caseSensitive = false
            ),
            "re2" to GeneralAssertionV1.TextMatchesRe2(
                pattern = "(?i)alpha\\s+beta"
            )
        )

        val assessment = assess(
            fixture = fixture,
            output = SealedTestOutputArtifactV3(
                relativePath = TEXT_OUTPUT.relativePath,
                mediaType = TEXT_OUTPUT.mediaType,
                content = "Prefix ALPHA beta suffix"
            )
        )

        assertEquals(2, assessment.automatedTestsPassed)
        assertEquals(0, assessment.automatedTestsFailed)
        assertEquals(1.0, assessment.overallScore)
        assertFalse(assessment.mandatoryFailure)
    }

    @Test
    fun `v3 executes compiled RFC 6901 pointer string and finite number operations`() {
        val fixture = jsonFixture(
            "exists-null" to GeneralAssertionV1.JsonPointerExists("/presentNull"),
            "escaped-string" to GeneralAssertionV1.JsonPointerEqualsString(
                pointer = "/items/0/a~1b/~0value",
                expected = "exact"
            ),
            "number-range" to GeneralAssertionV1.JsonPointerNumberRange(
                pointer = "/score",
                minimumInclusive = 2.0,
                maximumInclusive = 3.0
            )
        )

        val assessment = assess(
            fixture = fixture,
            output = SealedTestOutputArtifactV3(
                relativePath = JSON_OUTPUT.relativePath,
                mediaType = JSON_OUTPUT.mediaType,
                content =
                    """{"items":[{"a/b":{"~value":"exact"}}],"presentNull":null,"score":2.5}"""
            )
        )

        assertEquals(3, assessment.automatedTestsPassed)
        assertEquals(0, assessment.automatedTestsFailed)
    }

    @Test
    fun `v3 keeps JSON string and finite number semantics distinct`() {
        val fixture = jsonFixture(
            "string-is-not-number" to GeneralAssertionV1.JsonPointerNumberRange(
                pointer = "/stringValue",
                minimumInclusive = 2.0,
                maximumInclusive = 3.0
            ),
            "number-is-not-string" to GeneralAssertionV1.JsonPointerEqualsString(
                pointer = "/numberValue",
                expected = "2.5"
            ),
            "overflow-is-not-finite" to GeneralAssertionV1.JsonPointerNumberRange(
                pointer = "/overflow",
                minimumInclusive = 0.0,
                maximumInclusive = Double.MAX_VALUE
            )
        )

        val assessment = assess(
            fixture = fixture,
            output = SealedTestOutputArtifactV3(
                relativePath = JSON_OUTPUT.relativePath,
                mediaType = JSON_OUTPUT.mediaType,
                content = """{"stringValue":"2.5","numberValue":2.5,"overflow":1e309}"""
            )
        )

        assertEquals(0, assessment.automatedTestsPassed)
        assertEquals(3, assessment.automatedTestsFailed)
        assertEquals(0.0, assessment.overallScore)
        assertTrue(assessment.mandatoryFailure)
    }

    @Test
    fun `v3 enforces the exact two MiB UTF8 output bound`() {
        val fixture = textFixture(
            "last-byte" to GeneralAssertionV1.TextContains("z", caseSensitive = true)
        )
        val accepted = "a".repeat(GeneralTestLimits.MAX_OUTPUT_TEXT_FILE_BYTES - 1) + "z"
        val rejected = accepted + "z"

        val acceptedAssessment = assess(
            fixture,
            SealedTestOutputArtifactV3(
                TEXT_OUTPUT.relativePath,
                TEXT_OUTPUT.mediaType,
                accepted
            )
        )
        val rejectedAssessment = assess(
            fixture,
            SealedTestOutputArtifactV3(
                TEXT_OUTPUT.relativePath,
                TEXT_OUTPUT.mediaType,
                rejected
            )
        )

        assertEquals(1, acceptedAssessment.automatedTestsPassed)
        assertEquals(1, rejectedAssessment.automatedTestsFailed)
    }

    @Test
    fun `v3 rejects JSON nesting deeper than sixty four before parsing`() {
        val fixture = jsonFixture(
            "root-exists" to GeneralAssertionV1.JsonPointerExists("")
        )
        fun nested(depth: Int): String = "[".repeat(depth) + "null" + "]".repeat(depth)

        val accepted = assess(
            fixture,
            SealedTestOutputArtifactV3(
                JSON_OUTPUT.relativePath,
                JSON_OUTPUT.mediaType,
                nested(GeneralTestLimits.MAX_PARSED_JSON_DEPTH)
            )
        )
        val rejected = assess(
            fixture,
            SealedTestOutputArtifactV3(
                JSON_OUTPUT.relativePath,
                JSON_OUTPUT.mediaType,
                nested(GeneralTestLimits.MAX_PARSED_JSON_DEPTH + 1)
            )
        )

        assertEquals(1, accepted.automatedTestsPassed)
        assertEquals(1, rejected.automatedTestsFailed)
    }

    @Test
    fun `v3 rejects output path media and content commitment drift`() {
        val fixture = textFixture(
            "contains" to GeneralAssertionV1.TextContains("alpha", caseSensitive = true)
        )

        assertFailsWith<IllegalArgumentException> {
            assess(
                fixture,
                SealedTestOutputArtifactV3("other.txt", TEXT_OUTPUT.mediaType, "alpha")
            )
        }
        assertFailsWith<IllegalArgumentException> {
            assess(
                fixture,
                SealedTestOutputArtifactV3(TEXT_OUTPUT.relativePath, "text/html", "alpha")
            )
        }
        assertFailsWith<IllegalArgumentException> {
            assess(
                fixture,
                SealedTestOutputArtifactV3(
                    TEXT_OUTPUT.relativePath,
                    TEXT_OUTPUT.mediaType,
                    "alpha",
                    contentHash = "0".repeat(64)
                )
            )
        }
    }

    @Test
    fun `v3 rejects plan suite evaluator and external context tampering`() {
        val fixture = textFixture(
            "contains" to GeneralAssertionV1.TextContains("alpha", caseSensitive = true)
        )
        val original = assertIs<SealedTestV3.General>(fixture.suite.tests.single())
        val tamperedPlan = original.compiledPlan.copy(
            operation = CompiledGeneralOperation.TextContains(
                expected = "other",
                caseSensitive = true
            ),
            planHash = ""
        ).withComputedHash()
        val tamperedTest = original.copy(
            compiledPlan = tamperedPlan,
            testHash = ""
        ).withComputedHash()
        val tamperedSuite = fixture.suite.copy(
            tests = listOf(tamperedTest),
            suiteHash = ""
        ).withComputedHash()
        val suiteHashTamper = fixture.suite.copy(suiteHash = "0".repeat(64))
        val evaluatorTamper = fixture.suite.copy(
            evaluatorVersion = "other-evaluator",
            suiteHash = ""
        ).withComputedHash()
        val otherContext = fixture.context.copy(atlasHash = "b".repeat(64))
        val evaluator = SealedTestSuiteEvaluator()

        listOf(tamperedSuite, suiteHashTamper, evaluatorTamper).forEach { suite ->
            val inspection = evaluator.inspectCompiledSuite(
                rawEncode(suite),
                fixture.context
            )
            assertFalse(inspection.isComplete)
            assertEquals(0, inspection.executableTestCount)
            assertFailsWith<IllegalArgumentException> {
                evaluator.assessGroup(
                    suiteJson = rawEncode(suite),
                    validationContext = fixture.context,
                    input = input(
                        SealedTestOutputArtifactV3(
                            TEXT_OUTPUT.relativePath,
                            TEXT_OUTPUT.mediaType,
                            "alpha"
                        )
                    ),
                    scoringDimensions = DIMENSIONS
                )
            }
        }
        assertFalse(
            evaluator.inspectCompiledSuite(fixture.encoded, otherContext).isComplete
        )
    }

    @Test
    @OptIn(kotlin.time.ExperimentalTime::class)
    fun `v3 resolves and evaluates exact typed promotion fixtures`() {
        val promotionInput = TypedJsonValue.ObjectValue(
            listOf(
                TypedJsonObjectEntry(
                    "value",
                    TypedJsonValue.StringValue("input")
                )
            )
        )
        val promotionOutput = TypedJsonValue.ObjectValue(
            listOf(
                TypedJsonObjectEntry(
                    "ok",
                    TypedJsonValue.BooleanValue(true)
                )
            )
        )
        val test = SealedTestSuiteV3Authority.promotionTest(
            testId = "promotion",
            nodeId = "node-promotion",
            opportunityId = "opportunity",
            fixtureSchemaHash = PROMOTION_SCHEMA.schemaKey,
            acceptedArtifactTypes = setOf(ArtifactType.TOOL),
            input = promotionInput,
            expectedOutput = promotionOutput
        )
        val context = SealedTestSuiteV3ValidationContext(
            finalOutputSpec = JSON_OUTPUT,
            atlasHash = ATLAS_HASH,
            tests = listOf(
                SealedTestIdentityAuthorityV3.Promotion(
                    testId = test.testId,
                    nodeId = test.nodeId,
                    opportunityId = test.opportunityId,
                    fixtureSchema = PROMOTION_SCHEMA
                )
            )
        )
        val suite = SealedTestSuiteV3Authority.seal(
            rubricSchemaVersion = RUBRIC_VERSION,
            tests = listOf(test),
            context = context
        )
        val encoded = SealedTestSuiteV3Codec.encode(suite, context)
        val execution = ToolExecutionRecord(
            executionId = "execution",
            runId = RUN_ID,
            toolVersionId = ToolVersionId("tool-v1"),
            opportunityId = test.opportunityId,
            groupId = AtlasGroupId(GROUP_ID),
            agentId = AtlasAgentId("critic"),
            round = 0,
            nodeId = NodeId(test.nodeId),
            purpose = ToolExecutionPurpose.PROMOTION_TEST,
            inputHash = AtlasHashing.hashText(
                TypedJsonValueCodec.toCanonicalJson(promotionInput)
            ),
            outputHash = "output-hash",
            outputJson = TypedJsonValueCodec.toJsonElement(promotionOutput),
            outputArtifactId = "promotion-output:tool-v1:${test.testId}",
            startedAt = kotlin.time.Instant.fromEpochMilliseconds(1),
            completedAt = kotlin.time.Instant.fromEpochMilliseconds(2),
            durationMillis = 1,
            exitCode = 0,
            result = ToolExecutionResult.SUCCEEDED,
            sandboxRecordHash = "sandbox-hash"
        ).withComputedHash()
        val evaluator = SealedTestSuiteEvaluator()

        val fixtures = evaluator.promotionFixtures(
            suiteJson = encoded,
            validationContext = context,
            nodeId = test.nodeId,
            opportunityId = test.opportunityId,
            testIds = setOf(test.testId)
        )
        val assessment = evaluator.assessGroup(
            suiteJson = encoded,
            validationContext = context,
            input = SealedTestEvaluationInputV3(
                runId = RUN_ID,
                groupId = GROUP_ID,
                roundIndex = 0,
                finalOutput = null,
                promotionExecutions = listOf(execution)
            ),
            scoringDimensions = DIMENSIONS
        )

        assertEquals(
            TypedJsonValueCodec.toJsonElement(promotionInput),
            fixtures?.single()?.input
        )
        assertEquals(1, assessment.automatedTestsPassed)
        assertEquals(0, assessment.automatedTestsFailed)
    }

    @Test
    fun `v3 grading sheet exposes plan commitments and normalized dimensions`() {
        val fixture = textFixture(
            "contains" to GeneralAssertionV1.TextContains("alpha", caseSensitive = true)
        )
        val sheet = SealedTestSuiteEvaluator().buildGradingSheet(
            runId = RUN_ID,
            roundIndex = 2,
            suiteJson = fixture.encoded,
            validationContext = fixture.context,
            scoringDimensions = listOf(
                DIMENSIONS.single().copy(id = "correctness", weight = 1.35),
                DIMENSIONS.single().copy(id = "evidence", weight = 0.8)
            )
        )

        assertEquals(1.0, sheet.rubricDimensions.sumOf { it.weight }, 0.000000001)
        assertTrue(
            sheet.automatedTests.single().expectedOutputPattern
                .startsWith("compiled-general-plan:")
        )
        assertTrue(sheet.automatedTests.single().isMandatory)
    }

    private fun assess(
        fixture: SuiteFixture,
        output: SealedTestOutputArtifactV3
    ): DeterministicRoundAssessment =
        SealedTestSuiteEvaluator().assessGroup(
            suiteJson = fixture.encoded,
            validationContext = fixture.context,
            input = input(output),
            scoringDimensions = DIMENSIONS
        )

    private fun input(output: SealedTestOutputArtifactV3): SealedTestEvaluationInputV3 =
        SealedTestEvaluationInputV3(
            runId = RUN_ID,
            groupId = GROUP_ID,
            roundIndex = 0,
            finalOutput = output
        )

    private fun textFixture(
        vararg assertions: Pair<String, GeneralAssertionV1>
    ): SuiteFixture = generalFixture(
        outputSpec = TEXT_OUTPUT,
        definitions = assertions.map { (testId, assertion) ->
            testId to GeneralTestDefinitionV1(
                target = if (testId == "contains") {
                    GeneralTestTargetV1.FinalOutputText
                } else {
                    GeneralTestTargetV1.OutputTextFile(TEXT_OUTPUT.relativePath)
                },
                assertion = assertion
            )
        }
    )

    private fun jsonFixture(
        vararg assertions: Pair<String, GeneralAssertionV1>
    ): SuiteFixture = generalFixture(
        outputSpec = JSON_OUTPUT,
        definitions = assertions.map { (testId, assertion) ->
            testId to GeneralTestDefinitionV1(
                target = GeneralTestTargetV1.OutputJsonFile(JSON_OUTPUT.relativePath),
                assertion = assertion
            )
        }
    )

    private fun generalFixture(
        outputSpec: FinalOutputSpec,
        definitions: List<Pair<String, GeneralTestDefinitionV1>>
    ): SuiteFixture {
        val tests = definitions.map { (testId, definition) ->
            val plan = assertIs<GeneralTestCompileResult.Success>(
                GeneralTestCompiler.compile(
                    testId = testId,
                    definition = definition,
                    outputSpec = outputSpec
                )
            ).plan
            SealedTestSuiteV3Authority.generalTest(
                testId = testId,
                nodeId = "node-$testId",
                acceptedArtifactTypes = setOf(ArtifactType.FINAL_DELIVERABLE),
                definition = definition,
                compiledPlan = plan
            )
        }
        val context = SealedTestSuiteV3ValidationContext(
            finalOutputSpec = outputSpec,
            atlasHash = ATLAS_HASH,
            tests = tests.map { test ->
                SealedTestIdentityAuthorityV3.General(
                    testId = test.testId,
                    nodeId = test.nodeId
                )
            }.sortedBy(SealedTestIdentityAuthorityV3::testId)
        )
        val suite = SealedTestSuiteV3Authority.seal(
            rubricSchemaVersion = RUBRIC_VERSION,
            tests = tests,
            context = context
        )
        return SuiteFixture(
            suite = suite,
            context = context,
            encoded = SealedTestSuiteV3Codec.encode(suite, context)
        )
    }

    private fun rawEncode(suite: SealedTestSuiteV3): String =
        RAW_JSON.encodeToString(SealedTestSuiteV3.serializer(), suite)

    private data class SuiteFixture(
        val suite: SealedTestSuiteV3,
        val context: SealedTestSuiteV3ValidationContext,
        val encoded: String
    )

    private companion object {
        const val RUN_ID = "run-v3-evaluator"
        const val GROUP_ID = "group-0"
        const val RUBRIC_VERSION = "rubric-v1"
        val ATLAS_HASH: String = "a".repeat(64)
        val TEXT_OUTPUT = FinalOutputSpec("final.txt", "text/plain")
        val JSON_OUTPUT = FinalOutputSpec("result.json", "application/json")
        val DIMENSIONS = listOf(
            ScoringDimension(
                id = "accuracy",
                name = "Accuracy",
                description = "Exact deterministic assertion accuracy",
                weight = 1.0,
                maxScore = 100.0
            )
        )
        val RAW_JSON = Json {
            encodeDefaults = true
            explicitNulls = true
            classDiscriminator = "type"
            ignoreUnknownKeys = false
            isLenient = false
            coerceInputValues = false
            allowSpecialFloatingPointValues = false
        }
        val PROMOTION_SCHEMA = CouncilPromotionFixtureWireSchemaAuthority.fromSchemas(
            inputSchema = TypedToolSchemaCompiler.compileSchema(
                listOf(
                    TypedField(
                        name = "value",
                        type = TypedFieldType.STRING,
                        description = "Exact promotion input."
                    )
                )
            ),
            outputSchema = TypedToolSchemaCompiler.compileSchema(
                listOf(
                    TypedField(
                        name = "ok",
                        type = TypedFieldType.BOOLEAN,
                        description = "Exact promotion output."
                    )
                )
            )
        )
    }
}
