package com.achulkov.evomolior.core.council

import com.achulkov.evomolior.core.atlas.ArtifactType
import com.achulkov.evomolior.core.atlas.FinalOutputSpec
import com.achulkov.evomolior.core.atlas.TypedField
import com.achulkov.evomolior.core.atlas.TypedFieldType
import com.achulkov.evomolior.core.atlas.TypedToolSchemaCompiler
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SealedTestSuiteV3Test {
    @Test
    fun `GENERAL and PROMOTION definitions are closed canonical and hash stable`() {
        val general = SealedTestDefinitionV3.General(generalDefinition())
        val promotion = SealedTestDefinitionV3.Promotion(promotionInput(), promotionOutput())

        listOf(general, promotion).forEach { definition ->
            val encoded = SealedTestDefinitionV3Codec.encode(definition)
            assertEquals(definition, SealedTestDefinitionV3Codec.decode(encoded))
            assertEquals(encoded, SealedTestDefinitionV3Codec.encode(definition))
            assertEquals(
                CouncilContractHashing.sha256(encoded),
                SealedTestDefinitionV3Codec.hash(definition)
            )
        }
        assertNotEquals(
            SealedTestDefinitionV3Codec.hash(general),
            SealedTestDefinitionV3Codec.hash(promotion)
        )
        listOf(
            "null",
            "{}",
            """{"type":"UNKNOWN"}""",
            """{"type":"GENERAL","definition":null}""",
            """{"type":"PROMOTION","input":{"type":"NULL"}}""",
            """{"type":"GENERAL","definition":${GeneralTestDefinitionV1Codec.encode(generalDefinition())},"extra":true}"""
        ).forEach { invalid ->
            assertFails("Expected closed v3 definition failure for $invalid") {
                SealedTestDefinitionV3Codec.decode(invalid)
            }
        }
    }

    @Test
    fun `one common suite round trips and derives the worker commitment view`() {
        val general = generalTest("general-1")
        val promotion = promotionTest("promotion-1")
        val suite = suite(listOf(promotion, general))
        val context = contextFor(suite.tests)
        val encoded = SealedTestSuiteV3Codec.encode(suite, context)
        val decoded = SealedTestSuiteV3Codec.decodeVerified(encoded, context)
        val view = SealedTestSuiteV3Authority.commitmentView(decoded, context)
        val viewEncoded = SealedTestSuiteV3CommitmentViewCodec.encode(view)

        assertEquals(listOf("general-1", "promotion-1"), suite.tests.map(SealedTestV3::testId))
        assertEquals(suite, decoded)
        assertTrue(SealedTestSuiteV3Authority.isValid(decoded, context))
        assertTrue(decoded.hasValidHash())
        assertTrue(view.hasValidHash())
        assertEquals(suite.suiteHash, view.sourceSuiteHash)
        assertEquals(suite.schemaVersion, view.sourceSuiteSchemaVersion)
        assertEquals(
            suite.tests.map(SealedTestV3::testHash),
            view.tests.map(SealedTestCommitmentV3::testHash)
        )
        assertEquals(
            view,
            SealedTestSuiteV3CommitmentViewCodec.decodeVerified(viewEncoded)
        )
        assertTrue(
            SealedTestSuiteV3Authority.verifyCommitmentView(view, suite, context)
        )
        val tamperedView = view.copy(
            tests = view.tests.mapIndexed { index, test ->
                if (index == 0) test.copy(nodeId = "other-node") else test
            },
            viewHash = ""
        ).withComputedHash()
        assertTrue(tamperedView.hasValidHash())
        assertFalse(
            SealedTestSuiteV3Authority.verifyCommitmentView(
                tamperedView,
                suite,
                context
            )
        )
        assertFalse(viewEncoded.contains("compiledPlan"))
        assertFalse(viewEncoded.contains("\"input\""))
        assertContains(encoded, "\"compiledPlan\"")
        assertContains(encoded, "\"input\"")
    }

    @Test
    fun `suite definition test and suite hashes have stable golden values`() {
        val test = generalTest("general-golden")
        val suite = suite(listOf(test))

        assertEquals(
            "533e0fb336d040e3e7b98494e83056f56207b6132c5535fcfddeaa12ca85aa88",
            test.definitionHash
        )
        assertEquals(
            "094934f3680305c9cab536084e10aef193c85a19ac5ee176d8bc52aec85c89ef",
            test.testHash
        )
        assertEquals(
            "4981aa3b40680cda46f4986300698ad9fa831be2f87402e658b089d74e25a93a",
            suite.suiteHash
        )
        assertEquals(suite.suiteHash, SealedTestSuiteV3Codec.hash(suite))
        assertEquals(test.testHash, SealedTestV3Codec.hash(test))
        assertEquals(
            suite,
            SealedTestSuiteV3Codec.decodeVerified(
                SealedTestSuiteV3Codec.encode(suite, contextFor(suite.tests)),
                contextFor(suite.tests)
            )
        )
    }

    @Test
    fun `suite accepts exactly sixty four tests and rejects sixty five`() {
        val tests = (1..SealedTestSuiteV3Limits.MAX_TESTS_PER_SUITE).map { index ->
            promotionTest("promotion-${index.toString().padStart(2, '0')}")
        }

        assertEquals(
            SealedTestSuiteV3Limits.MAX_TESTS_PER_SUITE,
            suite(tests).tests.size
        )
        assertFails {
            suite(
                tests + promotionTest(
                    "promotion-${SealedTestSuiteV3Limits.MAX_TESTS_PER_SUITE + 1}"
                )
            )
        }
    }

    @Test
    fun `suite construction orders records and rejects duplicate identities`() {
        val general = generalTest("general-1")
        val promotion = promotionTest("promotion-1")

        assertEquals(
            listOf("general-1", "promotion-1"),
            suite(listOf(promotion, general)).tests.map(SealedTestV3::testId)
        )
        assertFails {
            suite(listOf(general, general))
        }

        val valid = suite(listOf(general, promotion))
        val unsorted = valid.copy(
            tests = valid.tests.reversed(),
            suiteHash = ""
        ).withComputedHash()
        val context = contextFor(valid.tests)
        assertFalse(SealedTestSuiteV3Authority.isValid(unsorted, context))
        assertContains(
            SealedTestSuiteV3Authority.validationErrors(unsorted, context),
            "tests must be ordered by testId"
        )
    }

    @Test
    fun `suite rejects unsorted duplicate or empty artifact authorities`() {
        val base = generalTest("general-artifacts")
        val invalidLists = listOf(
            listOf(ArtifactType.TOOL, ArtifactType.CLAIM_SET),
            listOf(ArtifactType.CLAIM_SET, ArtifactType.CLAIM_SET),
            emptyList()
        )

        invalidLists.forEach { artifacts ->
            val invalidTest = base.copy(
                acceptedArtifactTypes = artifacts,
                testHash = ""
            ).withComputedHash()
            val invalidSuite = SealedTestSuiteV3(
                rubricSchemaVersion = RUBRIC_VERSION,
                atlasHash = ATLAS_HASH,
                finalOutputSpecHash =
                    SealedTestSuiteV3Authority.hashFinalOutputSpec(OUTPUT_SPEC),
                tests = listOf(invalidTest)
            ).withComputedHash()

            assertFalse(
                SealedTestSuiteV3Authority.isValid(
                    invalidSuite,
                    contextFor(listOf(base))
                )
            )
        }
    }

    @Test
    fun `GENERAL outer hashes cannot hide definition plan or evaluator drift`() {
        val original = generalTest("general-tamper")
        val baseSuite = suite(listOf(original))
        val changedDefinition = generalDefinition().copy(
            assertion = GeneralAssertionV1.JsonPointerExists("/different")
        )
        val definitionTamper = original.copy(
            definition = changedDefinition,
            definitionHash = SealedTestDefinitionV3Codec.hash(
                SealedTestDefinitionV3.General(changedDefinition)
            ),
            testHash = ""
        ).withComputedHash()
        assertInvalidRehashedSuite(baseSuite, definitionTamper, "compiledPlan")

        val invalidPlanHash = original.copy(
            compiledPlan = original.compiledPlan.copy(planHash = "0".repeat(64)),
            testHash = ""
        ).withComputedHash()
        assertInvalidRehashedSuite(baseSuite, invalidPlanHash, "planHash")

        val changedEvaluatorPlan = original.compiledPlan
            .copy(evaluatorVersion = "other-evaluator", planHash = "")
            .withComputedHash()
        val evaluatorTamper = original.copy(
            compiledPlan = changedEvaluatorPlan,
            testHash = ""
        ).withComputedHash()
        assertInvalidRehashedSuite(baseSuite, evaluatorTamper, "evaluator")

        val otherDefinition = GeneralTestDefinitionV1(
            target = GeneralTestTargetV1.OutputJsonFile("other.json"),
            assertion = GeneralAssertionV1.JsonPointerExists("/status")
        )
        val otherPlan = assertIs<GeneralTestCompileResult.Success>(
            GeneralTestCompiler.compile(
                testId = original.testId,
                definition = otherDefinition,
                outputSpec = FinalOutputSpec("other.json", "application/json")
            )
        ).plan
        val selfConsistentWrongOutput = SealedTestSuiteV3Authority.generalTest(
            testId = original.testId,
            nodeId = original.nodeId,
            acceptedArtifactTypes = original.acceptedArtifactTypes.toSet(),
            definition = otherDefinition,
            compiledPlan = otherPlan
        )
        assertInvalidRehashedSuite(
            baseSuite,
            selfConsistentWrongOutput,
            "external receiver output authority"
        )
    }

    @Test
    fun `PROMOTION definition and schema commitments bind fixture authority`() {
        val original = promotionTest("promotion-tamper")
        val changedInput = TypedJsonValue.ObjectValue(
            listOf(
                TypedJsonObjectEntry("count", TypedJsonValue.IntegerValue(2)),
                TypedJsonObjectEntry("ratio", TypedJsonValue.NumberValue(1.0))
            )
        )
        val changed = SealedTestSuiteV3Authority.promotionTest(
            testId = original.testId,
            nodeId = original.nodeId,
            opportunityId = original.opportunityId,
            fixtureSchemaHash = original.fixtureSchemaHash,
            acceptedArtifactTypes = original.acceptedArtifactTypes.toSet(),
            input = changedInput,
            expectedOutput = original.expectedOutput
        )

        assertNotEquals(original.definitionHash, changed.definitionHash)
        assertNotEquals(original.testHash, changed.testHash)
        assertNotEquals(
            suite(listOf(original)).suiteHash,
            suite(listOf(changed)).suiteHash
        )

        val invalidSchema = original.copy(
            fixtureSchemaHash = "not-a-hash",
            testHash = ""
        ).withComputedHash()
        assertInvalidRehashedSuite(suite(listOf(original)), invalidSchema, "fixtureSchemaHash")

        val wrongValidSchema = original.copy(
            fixtureSchemaHash = "c".repeat(64),
            testHash = ""
        ).withComputedHash()
        assertInvalidRehashedSuite(
            suite(listOf(original)),
            wrongValidSchema,
            "Task-derived validation authority"
        )

        val blankOpportunity = original.copy(
            opportunityId = "",
            testHash = ""
        ).withComputedHash()
        assertInvalidRehashedSuite(suite(listOf(original)), blankOpportunity, "opportunityId")

        val wrongOpportunity = original.copy(
            opportunityId = "other-opportunity",
            testHash = ""
        ).withComputedHash()
        assertInvalidRehashedSuite(
            suite(listOf(original)),
            wrongOpportunity,
            "Task-derived validation authority"
        )

        val nullInput = SealedTestSuiteV3Authority.promotionTest(
            testId = original.testId,
            nodeId = original.nodeId,
            opportunityId = original.opportunityId,
            fixtureSchemaHash = original.fixtureSchemaHash,
            acceptedArtifactTypes = original.acceptedArtifactTypes.toSet(),
            input = TypedJsonValue.NullValue,
            expectedOutput = original.expectedOutput
        )
        assertInvalidRehashedSuite(suite(listOf(original)), nullInput, "input violates")

        val invalidOutput = SealedTestSuiteV3Authority.promotionTest(
            testId = original.testId,
            nodeId = original.nodeId,
            opportunityId = original.opportunityId,
            fixtureSchemaHash = original.fixtureSchemaHash,
            acceptedArtifactTypes = original.acceptedArtifactTypes.toSet(),
            input = original.input,
            expectedOutput = TypedJsonValue.StringValue("not-an-object")
        )
        assertInvalidRehashedSuite(
            suite(listOf(original)),
            invalidOutput,
            "expectedOutput violates"
        )
    }

    @Test
    fun `suite pins schema atlas language evaluator and self hash`() {
        val valid = suite(listOf(generalTest("general-pins")))
        val invalidSuites = listOf(
            valid.copy(schemaVersion = "sealed-tests-v2", suiteHash = "").withComputedHash(),
            valid.copy(atlasHash = "not-a-hash", suiteHash = "").withComputedHash(),
            valid.copy(assertionLanguageVersion = "other-language", suiteHash = "").withComputedHash(),
            valid.copy(evaluatorVersion = "other-evaluator", suiteHash = "").withComputedHash(),
            valid.copy(suiteHash = "0".repeat(64))
        )

        invalidSuites.forEach { invalid ->
            val context = contextFor(valid.tests)
            assertFalse(SealedTestSuiteV3Authority.isValid(invalid, context))
            assertFails { SealedTestSuiteV3Codec.encode(invalid, context) }
        }

        val otherAtlasContext = contextFor(valid.tests).copy(
            atlasHash = "d".repeat(64)
        )
        assertFalse(
            SealedTestSuiteV3Authority.isValid(valid, otherAtlasContext)
        )
        assertTrue(
            SealedTestSuiteV3Authority.validationErrors(valid, otherAtlasContext)
                .any { it.contains("external Task-derived Atlas authority") }
        )
    }

    @Test
    fun `sealed tests v2 bytes cannot decode or activate as v3`() {
        val historicalFull =
            """{"schemaVersion":"sealed-tests-v2","rubricSchemaVersion":"v1","atlasHash":"${"a".repeat(64)}","tests":[{"id":"legacy","nodeId":"node","kind":"GENERAL","opportunityId":null,"acceptedArtifactTypes":["EVIDENCE_TABLE"],"testDefinitionJson":"null","commitmentHash":"${CouncilContractHashing.sha256("null")}"}]}"""
        val historicalCommitments =
            """{"schemaVersion":"sealed-tests-v2","rubricSchemaVersion":"v1","atlasHash":"${"a".repeat(64)}","tests":[{"id":"legacy","nodeId":"node","kind":"GENERAL","opportunityId":null,"acceptedArtifactTypes":["EVIDENCE_TABLE"],"commitmentHash":"${CouncilContractHashing.sha256("null")}"}]}"""

        listOf(historicalFull, historicalCommitments).forEach { historical ->
            assertFails {
                SealedTestSuiteV3Codec.decodeVerified(
                    historical,
                    contextFor(listOf(generalTest("legacy")))
                )
            }
        }
    }

    private fun assertInvalidRehashedSuite(
        base: SealedTestSuiteV3,
        test: SealedTestV3,
        expectedErrorFragment: String
    ) {
        val invalid = base.copy(tests = listOf(test), suiteHash = "").withComputedHash()
        val context = contextFor(base.tests)
        val errors = SealedTestSuiteV3Authority.validationErrors(invalid, context)
        assertTrue(errors.any { it.contains(expectedErrorFragment) }, errors.joinToString())
        assertFalse(SealedTestSuiteV3Authority.isValid(invalid, context))
    }

    private fun suite(tests: List<SealedTestV3>): SealedTestSuiteV3 =
        SealedTestSuiteV3Authority.seal(
            rubricSchemaVersion = RUBRIC_VERSION,
            tests = tests,
            context = contextFor(tests)
        )

    private fun contextFor(
        tests: List<SealedTestV3>
    ): SealedTestSuiteV3ValidationContext =
        SealedTestSuiteV3ValidationContext(
            finalOutputSpec = OUTPUT_SPEC,
            atlasHash = ATLAS_HASH,
            tests = tests.map { test ->
                when (test) {
                    is SealedTestV3.General ->
                        SealedTestIdentityAuthorityV3.General(
                            testId = test.testId,
                            nodeId = test.nodeId
                        )
                    is SealedTestV3.Promotion ->
                        SealedTestIdentityAuthorityV3.Promotion(
                            testId = test.testId,
                            nodeId = test.nodeId,
                            opportunityId = "opportunity-1",
                            fixtureSchema = PROMOTION_SCHEMA
                        )
                }
            }.sortedBy(SealedTestIdentityAuthorityV3::testId)
        )

    private fun generalTest(testId: String): SealedTestV3.General {
        val definition = generalDefinition()
        val plan = assertIs<GeneralTestCompileResult.Success>(
            GeneralTestCompiler.compile(
                testId = testId,
                definition = definition,
                outputSpec = OUTPUT_SPEC
            )
        ).plan
        return SealedTestSuiteV3Authority.generalTest(
            testId = testId,
            nodeId = "node-general",
            acceptedArtifactTypes = setOf(
                ArtifactType.VALIDATION_REPORT,
                ArtifactType.EVIDENCE_TABLE
            ),
            definition = definition,
            compiledPlan = plan
        )
    }

    private fun promotionTest(testId: String): SealedTestV3.Promotion =
        SealedTestSuiteV3Authority.promotionTest(
            testId = testId,
            nodeId = "node-promotion",
            opportunityId = "opportunity-1",
            fixtureSchemaHash = PROMOTION_SCHEMA.schemaKey,
            acceptedArtifactTypes = setOf(ArtifactType.TOOL),
            input = promotionInput(),
            expectedOutput = promotionOutput()
        )

    private fun generalDefinition(): GeneralTestDefinitionV1 =
        GeneralTestDefinitionV1(
            target = GeneralTestTargetV1.OutputJsonFile("result.json"),
            assertion = GeneralAssertionV1.JsonPointerExists("/status")
        )

    private fun promotionInput(): TypedJsonValue =
        TypedJsonValue.ObjectValue(
            listOf(
                TypedJsonObjectEntry("count", TypedJsonValue.IntegerValue(1)),
                TypedJsonObjectEntry("ratio", TypedJsonValue.NumberValue(1.0))
            )
        )

    private fun promotionOutput(): TypedJsonValue =
        TypedJsonValue.ObjectValue(
            listOf(
                TypedJsonObjectEntry("ok", TypedJsonValue.BooleanValue(true))
            )
        )

    private companion object {
        const val RUBRIC_VERSION = "rubric-v1"
        val ATLAS_HASH: String = "a".repeat(64)
        val OUTPUT_SPEC = FinalOutputSpec("result.json", "application/json")
        val PROMOTION_SCHEMA = CouncilPromotionFixtureWireSchemaAuthority.fromSchemas(
            inputSchema = TypedToolSchemaCompiler.compileSchema(
                listOf(
                    TypedField(
                        name = "count",
                        type = TypedFieldType.INTEGER,
                        description = "Exact promotion count."
                    ),
                    TypedField(
                        name = "ratio",
                        type = TypedFieldType.NUMBER,
                        description = "Exact promotion ratio."
                    )
                )
            ),
            outputSchema = TypedToolSchemaCompiler.compileSchema(
                listOf(
                    TypedField(
                        name = "ok",
                        type = TypedFieldType.BOOLEAN,
                        description = "Exact promotion success flag."
                    )
                )
            )
        )
    }
}
