package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.api.model.FinalOutputSpec
import com.achulkov.evomolior.api.model.RunConfig
import com.achulkov.evomolior.competition.coordinator.GroupCoordinator
import com.achulkov.evomolior.competition.grader.GraderGroupManager
import com.achulkov.evomolior.core.atlas.ArtifactType
import com.achulkov.evomolior.core.atlas.FinalOutputSpec as AtlasFinalOutputSpec
import com.achulkov.evomolior.core.atlas.TypedField
import com.achulkov.evomolior.core.atlas.TypedFieldType
import com.achulkov.evomolior.core.atlas.TypedToolSchemaCompiler
import com.achulkov.evomolior.core.contract.GeneratedImageAsset
import com.achulkov.evomolior.core.council.CouncilPromotionFixtureWireSchemaAuthority
import com.achulkov.evomolior.core.council.GeneralAssertionV1
import com.achulkov.evomolior.core.council.GeneralTestCompileResult
import com.achulkov.evomolior.core.council.GeneralTestCompiler
import com.achulkov.evomolior.core.council.GeneralTestDefinitionV1
import com.achulkov.evomolior.core.council.GeneralTestTargetV1
import com.achulkov.evomolior.core.council.SealedTestIdentityAuthorityV3
import com.achulkov.evomolior.core.council.SealedTestSuiteV3Authority
import com.achulkov.evomolior.core.council.SealedTestSuiteV3Codec
import com.achulkov.evomolior.core.council.SealedTestSuiteV3ValidationContext
import com.achulkov.evomolior.core.council.TypedJsonObjectEntry
import com.achulkov.evomolior.core.council.TypedJsonValue
import com.achulkov.evomolior.core.model.ScoringDimension
import com.achulkov.evomolior.orchestration.metaplanner.MetaPlannerRunner
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.publication.PublicationStateRepository
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.jvm.isAccessible
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EvoMoliorRunServiceFinalOutputSealedAuthorityTest {
    @Test
    fun `writer output authority stays strict while immutable promotion authority is deferred`() =
        runTest {
            val runId = "run-final-output-phase-authority"
            val groupId = "group-0"
            val outputSpec = FinalOutputSpec(
                relativePath = "receiver/result.md",
                mediaType = "text/markdown"
            )
            val fixture = mixedSuite(
                AtlasFinalOutputSpec(outputSpec.relativePath, outputSpec.mediaType)
            )
            val substrateFactory = SubstrateDatabaseFactory(
                createTempDirectory("final-output-sealed-authority-").toFile()
            )
            val service = service(substrateFactory)
            service.javaClass.getDeclaredField("activeSealedTestSuiteJson")
                .apply { isAccessible = true }
                .set(service, fixture.encoded)
            service.javaClass.getDeclaredField("activeSealedTestSuiteValidationContext")
                .apply { isAccessible = true }
                .set(service, fixture.context)

            val beginWriterMethod = service::class.declaredFunctions
                .first { it.name == "validateFinalWriterRunAuthority" }
                .apply { isAccessible = true }
            val immutableFailure = assertFailsWith<IllegalStateException> {
                beginWriterMethod.callSuspend(
                    service,
                    runId,
                    groupId,
                    0,
                    DIMENSIONS
                )
            }
            assertTrue(immutableFailure.message.orEmpty().contains("immutable mandatory"))
            assertFalse(immutableFailure.message.orEmpty().contains(GENERAL_TEST_ID))
            assertFalse(immutableFailure.message.orEmpty().contains(PROMOTION_TEST_ID))
            assertNull(
                PublicationStateRepository(substrateFactory.getAtlas(runId)).latest(runId),
                "Immutable run-authority failure must precede FINAL_WRITER_RUNNING publication"
            )

            val candidateMethod = service::class.declaredFunctions
                .first { it.name == "validateFinalOutputCandidate" }
                .apply { isAccessible = true }
            candidateMethod.callSuspend(
                service,
                GeneratedOutputFile(
                    relativePath = outputSpec.relativePath,
                    mediaType = outputSpec.mediaType,
                    content = "alpha"
                ),
                outputSpec,
                "Produce alpha.",
                emptyList<GeneratedImageAsset>(),
                runId,
                groupId,
                0,
                DIMENSIONS,
                SealedTestEvaluationSelection.FINAL_OUTPUT_ADDRESSABLE,
                false,
                "Final output failed active sealed-tests-v3 output authority"
            )
            val recordCandidate = service::class.declaredFunctions
                .first { it.name == "recordCandidateOutputValidation" }
                .apply { isAccessible = true }
            recordCandidate.callSuspend(
                service,
                RunConfig(
                    runId = runId,
                    objective = "Produce alpha.",
                    groupCount = 1,
                    groupProfiles = listOf(com.achulkov.evomolior.core.model.GroupProfileType.Library),
                    scoringDimensions = DIMENSIONS,
                    openRouterApiKey = "provider-free",
                    isTestRun = true,
                ),
                groupId,
                0,
                GeneratedOutputFile(outputSpec.relativePath, outputSpec.mediaType, "alpha"),
                outputSpec,
            )
            val candidateEntry = substrateFactory.get(runId, "__meta_archive__").metaArchiveDao()
                .getByType(CandidateOutputValidationRecord.ENTRY_TYPE).single()
            val candidateRecord = kotlinx.serialization.json.Json.decodeFromString(
                CandidateOutputValidationRecord.serializer(), candidateEntry.contentJson)
            assertTrue(candidateRecord.addressableTestsPassed == 1)
            assertTrue(candidateRecord.addressableTestsFailed == 0)
            assertFalse(candidateRecord.acceptanceGranted)
            assertTrue(candidateEntry.contentJson.contains("\"acceptanceGranted\":false"))
            assertFailsWith<IllegalStateException> {
                beginWriterMethod.callSuspend(service, runId, groupId, 0, DIMENSIONS)
            }
            assertNull(PublicationStateRepository(substrateFactory.getAtlas(runId)).latest(runId))
            val repairableFailure = assertFailsWith<IllegalArgumentException> {
                candidateMethod.callSuspend(
                    service,
                    GeneratedOutputFile(
                        relativePath = outputSpec.relativePath,
                        mediaType = outputSpec.mediaType,
                        content = "beta"
                    ),
                    outputSpec,
                    "Produce alpha.",
                    emptyList<GeneratedImageAsset>(),
                    runId,
                    groupId,
                    0,
                    DIMENSIONS,
                    SealedTestEvaluationSelection.FINAL_OUTPUT_ADDRESSABLE,
                    false,
                    "Final output failed active sealed-tests-v3 output authority"
                )
            }
            assertTrue(repairableFailure.message.orEmpty().contains("output authority"))
            assertFalse(repairableFailure.message.orEmpty().contains(GENERAL_TEST_ID))
            assertFalse(repairableFailure.message.orEmpty().contains(PROMOTION_TEST_ID))

            val stagedMethod = service::class.declaredFunctions
                .first { it.name == "validateStagedFinalOutput" }
                .apply { isAccessible = true }
            val fullSuiteFailure = assertFailsWith<IllegalArgumentException> {
                stagedMethod.callSuspend(
                    service,
                    GeneratedOutputFile(
                        relativePath = outputSpec.relativePath,
                        mediaType = outputSpec.mediaType,
                        content = "alpha"
                    ),
                    outputSpec,
                    "Produce alpha.",
                    emptyList<GeneratedImageAsset>(),
                    runId,
                    groupId,
                    0,
                    DIMENSIONS
                )
            }
            assertTrue(fullSuiteFailure.message.orEmpty().contains("sealed-tests-v3 authority"))
            assertFalse(fullSuiteFailure.message.orEmpty().contains(GENERAL_TEST_ID))
            assertFalse(fullSuiteFailure.message.orEmpty().contains(PROMOTION_TEST_ID))
        }

    @Test
    fun `general only suite does not invent missing promotion authority`() = runTest {
        val runId = "run-general-only-authority"
        val groupId = "group-0"
        val outputSpec = AtlasFinalOutputSpec(
            relativePath = "receiver/result.md",
            mediaType = "text/markdown"
        )
        val fixture = sealedSuite(outputSpec, includePromotion = false)
        val substrateFactory = SubstrateDatabaseFactory(
            createTempDirectory("general-only-sealed-authority-").toFile()
        )
        val service = service(substrateFactory)
        service.javaClass.getDeclaredField("activeSealedTestSuiteJson")
            .apply { isAccessible = true }
            .set(service, fixture.encoded)
        service.javaClass.getDeclaredField("activeSealedTestSuiteValidationContext")
            .apply { isAccessible = true }
            .set(service, fixture.context)

        val validateRunAuthority = service::class.declaredFunctions
            .first { it.name == "validateFinalWriterRunAuthority" }
            .apply { isAccessible = true }
        validateRunAuthority.callSuspend(
            service,
            runId,
            groupId,
            0,
            DIMENSIONS
        )

        assertNull(PublicationStateRepository(substrateFactory.getAtlas(runId)).latest(runId))
    }

    private fun service(factory: SubstrateDatabaseFactory): EvoMoliorRunService =
        EvoMoliorRunService(
            lifecycleManagerProvider = { _, _, _, _, _ ->
                error("Final-output authority unit test must not spawn a lifecycle manager")
            },
            groupCoordinatorProvider = { groupIds, dimensions ->
                GroupCoordinator(groupIds, dimensions)
            },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = factory,
            metaPlannerRunner = MetaPlannerRunner()
        )

    private fun mixedSuite(
        outputSpec: AtlasFinalOutputSpec
    ): MixedSuiteFixture = sealedSuite(outputSpec, includePromotion = true)

    private fun sealedSuite(
        outputSpec: AtlasFinalOutputSpec,
        includePromotion: Boolean
    ): MixedSuiteFixture {
        val definition = GeneralTestDefinitionV1(
            target = GeneralTestTargetV1.FinalOutputText,
            assertion = GeneralAssertionV1.TextContains("alpha", caseSensitive = true)
        )
        val plan = (
            GeneralTestCompiler.compile(
                testId = GENERAL_TEST_ID,
                definition = definition,
                outputSpec = outputSpec
            ) as GeneralTestCompileResult.Success
            ).plan
        val general = SealedTestSuiteV3Authority.generalTest(
            testId = GENERAL_TEST_ID,
            nodeId = "node-general",
            acceptedArtifactTypes = setOf(ArtifactType.FINAL_DELIVERABLE),
            definition = definition,
            compiledPlan = plan
        )
        val schema = CouncilPromotionFixtureWireSchemaAuthority.fromSchemas(
            inputSchema = TypedToolSchemaCompiler.compileSchema(
                listOf(
                    TypedField(
                        name = "value",
                        type = TypedFieldType.STRING,
                        description = "Promotion input."
                    )
                )
            ),
            outputSchema = TypedToolSchemaCompiler.compileSchema(
                listOf(
                    TypedField(
                        name = "ok",
                        type = TypedFieldType.BOOLEAN,
                        description = "Promotion output."
                    )
                )
            )
        )
        val promotion = SealedTestSuiteV3Authority.promotionTest(
            testId = PROMOTION_TEST_ID,
            nodeId = "node-promotion",
            opportunityId = "opportunity",
            fixtureSchemaHash = schema.schemaKey,
            acceptedArtifactTypes = setOf(ArtifactType.TOOL),
            input = TypedJsonValue.ObjectValue(
                listOf(
                    TypedJsonObjectEntry(
                        "value",
                        TypedJsonValue.StringValue("input")
                    )
                )
            ),
            expectedOutput = TypedJsonValue.ObjectValue(
                listOf(
                    TypedJsonObjectEntry(
                        "ok",
                        TypedJsonValue.BooleanValue(true)
                    )
                )
            )
        )
        val sealedTestsForContext = buildList {
            add(
                SealedTestIdentityAuthorityV3.General(
                    testId = general.testId,
                    nodeId = general.nodeId
                )
            )
            if (includePromotion) {
                add(
                    SealedTestIdentityAuthorityV3.Promotion(
                        testId = promotion.testId,
                        nodeId = promotion.nodeId,
                        opportunityId = promotion.opportunityId,
                        fixtureSchema = schema
                    )
                )
            }
        }.sortedBy(SealedTestIdentityAuthorityV3::testId)
        val context = SealedTestSuiteV3ValidationContext(
            finalOutputSpec = outputSpec,
            atlasHash = "a".repeat(64),
            tests = sealedTestsForContext
        )
        val suite = SealedTestSuiteV3Authority.seal(
            rubricSchemaVersion = "rubric-v1",
            tests = buildList {
                add(general)
                if (includePromotion) add(promotion)
            },
            context = context
        )
        return MixedSuiteFixture(
            encoded = SealedTestSuiteV3Codec.encode(suite, context),
            context = context
        )
    }

    private data class MixedSuiteFixture(
        val encoded: String,
        val context: SealedTestSuiteV3ValidationContext
    )

    private companion object {
        const val GENERAL_TEST_ID = "private-general"
        const val PROMOTION_TEST_ID = "private-promotion"
        val DIMENSIONS = listOf(
            ScoringDimension(
                id = "quality",
                name = "Quality",
                description = "Receiver quality.",
                weight = 1.0,
                maxScore = 100.0
            )
        )
    }
}
