package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.api.events.EvoMoliorEvent
import com.achulkov.evomolior.api.model.FinalOutputSpec as RequestedFinalOutputSpec
import com.achulkov.evomolior.api.model.RunConfig
import com.achulkov.evomolior.competition.coordinator.GroupCoordinator
import com.achulkov.evomolior.competition.grader.GraderGroupManager
import com.achulkov.evomolior.core.atlas.ArtifactType
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.FinalOutputSpec
import com.achulkov.evomolior.core.atlas.NodeKind
import com.achulkov.evomolior.core.config.CouncilExecutionPurpose
import com.achulkov.evomolior.core.config.LlmBackend
import com.achulkov.evomolior.core.council.CouncilFieldSealing
import com.achulkov.evomolior.core.council.CouncilContractHashing
import com.achulkov.evomolior.core.council.CouncilDeterministicIds
import com.achulkov.evomolior.core.council.FieldCompileErrorCode
import com.achulkov.evomolior.core.council.LocalNodeKey
import com.achulkov.evomolior.core.council.SealedTestSuiteV3Codec
import com.achulkov.evomolior.core.council.StartingFieldCompileResult
import com.achulkov.evomolior.core.model.GroupProfileType
import com.achulkov.evomolior.core.model.Role
import com.achulkov.evomolior.core.model.ScoringDimension
import com.achulkov.evomolior.orchestration.lifecycle.LifecycleManager
import com.achulkov.evomolior.orchestration.metaplanner.MetaPlannerRunner
import com.achulkov.evomolior.sandbox.shuru.ShuruVmManager
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Literal v71/v72 regressions plus the executable seven-test positive sibling required by Step 7.
 */
class ExecutableTestHistoricalRegressionTest {
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
    }

    @Test
    fun `v71 nested typed envelope remains historical and cannot activate`() = runTest {
        val fixture = v71NestedTypedV2Fixture()
        assertEquals(V71_FIXTURE_SHA256, CouncilContractHashing.sha256(fixture))
        val fixtureRoot = json.parseToJsonElement(fixture).jsonObject
        val test = fixtureRoot.getValue("tests").jsonArray.single().jsonObject
        val definition = test.getValue("testDefinitionJson").jsonPrimitive.content

        assertEquals("sealed-tests-v2", fixtureRoot.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals(V71_ATLAS_HASH, fixtureRoot.getValue("atlasHash").jsonPrimitive.content)
        assertEquals(V71_TEST_ID, test.getValue("id").jsonPrimitive.content)
        assertEquals(V71_NODE_ID, test.getValue("nodeId").jsonPrimitive.content)
        assertEquals(V71_DEFINITION_HASH, CouncilContractHashing.sha256(definition))
        assertEquals(V71_DEFINITION_HASH, test.getValue("commitmentHash").jsonPrimitive.content)

        val historical = SealedTestSuiteEvaluator().inspectSuite(fixture)
        assertEquals(1, historical.declaredTestCount)
        assertEquals(0, historical.executableTestCount)
        assertTrue(historical.failures.single().contains("no executable assertion"))

        val active = SealedTestSuiteEvaluator().inspectCompiledSuite(fixture)
        assertEquals(1, active.declaredTestCount)
        assertEquals(0, active.executableTestCount)
        assertTrue(active.failures.any { it.contains("UNSUPPORTED_EXECUTABLE_TEST_VERSION") })
        assertFailsWith<Exception> { SealedTestSuiteV3Codec.decode(fixture) }

        val root = createTempDirectory("v71-legacy-active-rejection-").toFile()
        val substrateFactory = SubstrateDatabaseFactory(root)
        val runId = "v71-legacy-active-rejection"
        val config = appendixBConfig(runId, "Reject exact v71 legacy executable authority")
        val envelope = CouncilExecutionEnvelopeFactory.create(config)
        val compiled = compiledCouncilTestFixture(
            runId = runId,
            objective = config.objective,
            envelope = envelope,
            workerGroupKeys = listOf("north", "south"),
            workerProfiles = listOf(GroupProfileType.Library, GroupProfileType.Factory)
        )
        val legacyWorker = compiled.workerField.copy(
            sealedTestSuiteJson = fixture,
            sealedTestSuiteHash = CouncilContractHashing.sha256(fixture),
            seal = null
        )
        val legacyGrader = compiled.graderField.copy(
            sealedTestSuiteJson = fixture,
            sealedTestSuiteHash = CouncilContractHashing.sha256(fixture),
            seal = null
        )
        val legacyReport = compiled.report.copy(
            workerFieldHash = CouncilContractHashing.hashStartingField(legacyWorker),
            graderFieldHash = CouncilContractHashing.hashStartingField(legacyGrader)
        )
        val legacySeal = CouncilFieldSealing.createSeal(
            workerField = legacyWorker,
            graderField = legacyGrader,
            atlas = compiled.atlas,
            graderAtlas = compiled.graderAtlas,
            receiverContract = compiled.receiverContract,
            envelope = envelope,
            report = legacyReport
        )
        val legacyCompiled = compiled.copy(
            workerField = legacyWorker.copy(seal = legacySeal),
            graderField = legacyGrader.copy(seal = legacySeal),
            report = legacyReport
        )
        val lifecycleCalls = AtomicInteger()
        val service = EvoMoliorRunService(
            lifecycleManagerProvider = { _, _, _, _, _ ->
                lifecycleCalls.incrementAndGet()
                error("legacy v71 authority must not activate")
            },
            groupCoordinatorProvider = { groupIds, dimensions ->
                GroupCoordinator(groupIds, dimensions)
            },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = substrateFactory,
            archiveService = RunArchiveService(substrateFactory),
            metaPlannerRunner = MetaPlannerRunner(),
            genesisCouncilRunner = { _, _, _, _, _, _, onInvocationStarted ->
                onInvocationStarted()
                legacyCompiled
            }
        )
        val failure = assertFailsWith<IllegalStateException> {
            service.startRun(config)
        }
        assertTrue(failure.message.orEmpty().contains("UNSUPPORTED_EXECUTABLE_TEST_VERSION"))
        assertEquals(0, lifecycleCalls.get())
        val rejection = assertNotNull(
            StartupRejectionFileStore(substrateFactory::runControlDir).read(runId)
        )
        assertEquals(StartupRejectionPhase.TEST_EXECUTABILITY_PREFLIGHT, rejection.phase)
        assertEquals(listOf(StartupRejectionReason.TEST_SUITE_NOT_EXECUTABLE), rejection.reasons)
        assertEquals(1, rejection.declaredTestCount)
        assertEquals(0, rejection.compiledTestCount)
        assertEquals(CouncilContractHashing.sha256(fixture), rejection.testSuiteHash)
        assertFalse(rejection.activationFlags.runStartedEmitted)
        assertTrue(
            substrateFactory.get(runId, "__meta_archive__").metaArchiveDao()
                .getByType(RunArchiveService.ENTRY_RUN_COMPLETED)
                .none { it.runId == runId }
        )
    }

    @Test
    fun `v72 exact seven null fixture is rejected before activation or substrate seeding`() =
        runTest {
            val fixture = resourceText(V72_RESOURCE)
            assertEquals(V72_RESOURCE_SHA256, CouncilContractHashing.sha256(fixture))
            val fixtureRoot = json.parseToJsonElement(fixture).jsonObject
            assertEquals(
                "sealed-tests-v2",
                fixtureRoot.getValue("schemaVersion").jsonPrimitive.content
            )
            assertEquals(
                V72_ATLAS_HASH,
                fixtureRoot.getValue("atlasHash").jsonPrimitive.content
            )
            val records = v72Records(fixture)
            assertEquals(V72_TEST_IDS, records.map(V72Record::testId))
            assertTrue(records.all { it.definitionJson == "null" })
            assertTrue(records.all { it.commitmentHash == NULL_SHA256 })
            assertTrue(records.all { it.kind == "GENERAL" && it.opportunityId == null })

            val nullTests = records.map { record ->
                CompiledCouncilGeneralTestFixture(
                    testId = record.testId,
                    nodeKey = v72HistoricalNodeKey(record.nodeId),
                    nodeKind = v72NodeKind(record.testId),
                    acceptedArtifactTypes = record.acceptedArtifactTypes,
                    definitionJson = record.definitionJson
                )
            }
            val runId = V72_HISTORICAL_RUN_ID
            val config = appendixBConfig(runId, "Reject the exact v72 seven-null suite")
            val compiled = assertIs<StartingFieldCompileResult.Invalid>(
                compiledCouncilFixtureResult(
                    runId = runId,
                    objective = config.objective,
                    envelope = CouncilExecutionEnvelopeFactory.create(config),
                    workerGroupKeys = listOf("north", "south"),
                    workerProfiles = listOf(
                        GroupProfileType.Library,
                        GroupProfileType.Factory
                    ),
                    generalTests = nullTests
                )
            )
            assertEquals(7, compiled.errors.size)
            assertEquals(V72_TEST_IDS.size, compiled.declaredTestCount)
            assertEquals(0, compiled.compiledTestCount)
            assertEquals(
                V72_TEST_IDS.associateWith { NULL_SHA256 },
                compiled.definitionHashesByTestId
            )
            assertTrue(
                compiled.errors.all { it.code == FieldCompileErrorCode.GENERAL_TEST_NULL }
            )
            assertEquals(
                V72_TEST_IDS.map { "synthesis.sealedTests.$it.definition" },
                compiled.errors.map { it.path.toString() }
            )

            val root = createTempDirectory("v72-seven-null-regression-").toFile()
            val substrateFactory = SubstrateDatabaseFactory(root)
            val lifecycleCalls = AtomicInteger()
            val coordinatorCalls = AtomicInteger()
            val events = ConcurrentLinkedQueue<EvoMoliorEvent>()
            val service = EvoMoliorRunService(
                lifecycleManagerProvider = { _, _, _, _, _ ->
                    lifecycleCalls.incrementAndGet()
                    error("runtime activation must not begin")
                },
                groupCoordinatorProvider = { groupIds, dimensions ->
                    coordinatorCalls.incrementAndGet()
                    GroupCoordinator(groupIds, dimensions)
                },
                graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
                substrateFactory = substrateFactory,
                archiveService = RunArchiveService(substrateFactory),
                metaPlannerRunner = MetaPlannerRunner(),
                genesisCouncilRunner = {
                        callbackRunId,
                        objective,
                        _,
                        envelope,
                        inputArtifacts,
                        _,
                        onInvocationStarted ->
                    onInvocationStarted()
                    compiledCouncilFixtureResult(
                        runId = callbackRunId,
                        objective = objective,
                        envelope = envelope,
                        inputArtifacts = inputArtifacts,
                        workerGroupKeys = listOf("north", "south"),
                        workerProfiles = listOf(
                            GroupProfileType.Library,
                            GroupProfileType.Factory
                        ),
                        generalTests = nullTests
                    )
                }
            )
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                service.events.collect(events::add)
            }

            val failure = assertFailsWith<IllegalStateException> {
                service.startRun(config)
            }
            yield()

            assertTrue(failure.message.orEmpty().contains("field compilation failed"))
            assertEquals(0, lifecycleCalls.get())
            assertEquals(0, coordinatorCalls.get())
            assertFalse(events.any { it is EvoMoliorEvent.RunStarted })
            // Rejected startup now publishes a diagnostic outcome in the control-plane Atlas DB.
            // Runtime substrate seeding remains forbidden and is asserted by the empty worker,
            // grader, active-field, and completion authorities below.
            assertTrue(substrateFactory.atlasDbFilePath(runId).exists())
            assertTrue(workerGroupDirectories(substrateFactory, runId).isEmpty())
            assertTrue(graderDirectories(substrateFactory, runId).isEmpty())
            assertEquals(null, PersistedRunStateStore.read(root, runId))
            val rejection = assertNotNull(
                StartupRejectionFileStore(substrateFactory::runControlDir).read(runId)
            )
            assertEquals(StartupRejectionArtifact.SCHEMA_VERSION, rejection.schemaVersion)
            assertEquals(StartupRejectionPhase.COUNCIL_COMPILE, rejection.phase)
            assertEquals(listOf(StartupRejectionReason.COUNCIL_COMPILE_FAILED), rejection.reasons)
            assertEquals(V72_TEST_IDS.size, rejection.errors.size)
            assertEquals(V72_TEST_IDS.size, rejection.declaredTestCount)
            assertEquals(0, rejection.compiledTestCount)
            assertEquals(1, rejection.providerExecutionCount)
            assertEquals(1, rejection.providerExecutionRefs.size)
            assertTrue(rejection.providerExecutionRefs.single().startsWith("genesis-council:$runId:"))
            assertEquals(V72_TEST_IDS, rejection.errors.map { it.testId?.value })
            assertTrue(rejection.errors.all { it.code == FieldCompileErrorCode.GENERAL_TEST_NULL })
            assertTrue(rejection.errors.all { it.definitionHash == NULL_SHA256 })
            assertFalse(rejection.activationFlags.runStartedEmitted)
            assertFalse(rejection.activationFlags.workerInfrastructureActivated)
            assertFalse(rejection.activationFlags.graderInfrastructureActivated)
            assertFalse(rejection.activationFlags.successArchiveCreated)
            assertFalse(rejection.activationFlags.winnerAuthorityCreated)

            val archiveEntries = substrateFactory.get(runId, "__meta_archive__")
                .metaArchiveDao()
                .getByRun(runId)
            assertTrue(
                archiveEntries.none {
                    it.entryType in setOf(
                        RunArchiveService.ENTRY_STARTING_FIELD,
                        RunArchiveService.ENTRY_GRADER_STARTING_FIELD,
                        RunArchiveService.ENTRY_COMPILED_COUNCIL_CANDIDATE_MANIFEST,
                        RunArchiveService.ENTRY_ACTIVE_COMPILED_COUNCIL_MANIFEST,
                        RunArchiveService.ENTRY_RUN_COMPLETED
                    )
                }
            )
        }

    @Test
    fun `positive v72 sibling compiles persists preflights and executes seven of seven`() =
        runTest {
            val records = v72Records(resourceText(V72_RESOURCE))
            val output = V72_TEST_IDS.joinToString("\n") { testId ->
                V72_EXPECTED_TEXT.getValue(testId)
            }
            val expectedIdentityByTest = records.associate { it.testId to it.nodeId }
            val positiveTests = records.map { record ->
                CompiledCouncilGeneralTestFixture(
                    testId = record.testId,
                    nodeKey = v72HistoricalNodeKey(record.nodeId),
                    nodeKind = v72NodeKind(record.testId),
                    acceptedArtifactTypes = record.acceptedArtifactTypes,
                    expectedText = V72_EXPECTED_TEXT.getValue(record.testId)
                )
            }
            positiveTests.forEach { test ->
                assertEquals(
                    expectedIdentityByTest.getValue(test.testId),
                    CouncilDeterministicIds.nodeId(V72_HISTORICAL_RUN_ID, test.nodeKey)
                )
            }
            val root = createTempDirectory("v72-positive-seven-").toFile()
            val substrateFactory = SubstrateDatabaseFactory(root)
            val runId = V72_HISTORICAL_RUN_ID
            val config = appendixBConfig(runId, "Execute the typed v72 seven-test sibling")
            var compiled: StartingFieldCompileResult.Valid? = null
            val service = EvoMoliorRunService(
                lifecycleManagerProvider = {
                        runIdValue,
                        groupIdValue,
                        _,
                        _,
                        groupProfile ->
                    LifecycleManager(
                        runId = runIdValue,
                        groupId = groupIdValue,
                        shuruVmManager = ShuruVmManager(),
                        substrateDatabaseFactory = substrateFactory,
                        agentJarPath = "/fake/agent.jar",
                        openRouterApiKey = "/tmp/codex",
                        groupProfile = groupProfile,
                        localAgentRunner = { bootContext, _ ->
                            if (
                                bootContext.role == Role.Archivist &&
                                bootContext.rolePrompt.contains("Round Submission Summariser")
                            ) {
                                val now = System.currentTimeMillis()
                                val submissionId =
                                    "step7-summary-${bootContext.groupId}-$now"
                                val content = "ROUND SUBMISSION SUMMARY\n$output"
                                substrateFactory.get(bootContext.runId, bootContext.groupId)
                                    .stagingDao()
                                    .insert(
                                        StagingSubmissionEntity(
                                            id = submissionId,
                                            agentId = bootContext.agentId,
                                            role = bootContext.role.name,
                                            type = "PLAN_UPDATE",
                                            content = content,
                                            budgetSignature = "step7-summary",
                                            status = "promoted",
                                            criticAgentId =
                                                "step7-critic-${bootContext.groupId}",
                                            generation = bootContext.generation,
                                            groupId = bootContext.groupId,
                                            runId = bootContext.runId,
                                            submittedAtMs = now,
                                            reviewedAtMs = now,
                                            promotedArtifactRef = "submission:$submissionId",
                                            promotedArtifactHash = AtlasHashing.hashText(content)
                                        )
                                    )
                            }
                        }
                    )
                },
                groupCoordinatorProvider = { groupIds, dimensions ->
                    GroupCoordinator(groupIds, dimensions)
                },
                graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
                substrateFactory = substrateFactory,
                archiveService = RunArchiveService(substrateFactory),
                metaPlannerRunner = MetaPlannerRunner(),
                genesisCouncilRunner = {
                        callbackRunId,
                        objective,
                        _,
                        envelope,
                        inputArtifacts,
                        _,
                        onInvocationStarted ->
                    onInvocationStarted()
                    compiledCouncilTestFixture(
                        runId = callbackRunId,
                        objective = objective,
                        envelope = envelope,
                        inputArtifacts = inputArtifacts,
                        workerGroupKeys = listOf("north", "south"),
                        workerProfiles = listOf(
                            GroupProfileType.Library,
                            GroupProfileType.Factory
                        ),
                        generalTests = positiveTests
                    ).also { compiled = it }
                }
            )

            service.startRun(config)
            @Suppress("UNCHECKED_CAST")
            val runtimeAssessments = service.javaClass
                .getDeclaredField("latestGroupRoundAssessments")
                .apply { isAccessible = true }
                .get(service) as ConcurrentHashMap<String, DeterministicRoundAssessment>
            withTimeout(30_000) {
                while (runtimeAssessments.size < 2) {
                    yield()
                }
            }
            assertEquals(2, runtimeAssessments.size)
            assertTrue(
                runtimeAssessments.values.all { it.automatedTestsPassed == 7 },
                runtimeAssessments.toString()
            )
            assertTrue(runtimeAssessments.values.all { it.automatedTestsFailed == 0 })
            assertTrue(runtimeAssessments.values.none { it.mandatoryFailure })

            val compiledField = assertNotNull(compiled)
            assertEquals(7, compiledField.sealedTests.privateSuite.tests.size)
            assertEquals(7, compiledField.sealedTests.workerCommitmentView.tests.size)
            assertEquals(V72_TEST_IDS, compiledField.sealedTests.privateSuite.tests.map { it.testId })
            assertEquals(
                expectedIdentityByTest,
                compiledField.sealedTests.privateSuite.tests.associate {
                    it.testId to it.nodeId
                }
            )

            val archiveEntries = substrateFactory.get(runId, "__meta_archive__")
                .metaArchiveDao()
                .getByRun(runId)
            val candidate = CompiledCouncilCandidateManifestV3Codec.decodeVerified(
                archiveEntries.single {
                    it.entryType ==
                        RunArchiveService.ENTRY_COMPILED_COUNCIL_CANDIDATE_MANIFEST
                }.contentJson
            )
            val active = ActiveCompiledCouncilManifestV3Codec.decodeVerified(
                archiveEntries.single {
                    it.entryType ==
                        RunArchiveService.ENTRY_ACTIVE_COMPILED_COUNCIL_MANIFEST
                }.contentJson
            )
            assertEquals(candidate.manifestHash, active.candidateManifestHash)
            assertEquals(7, candidate.privateSuite.tests.size)
            assertEquals(7, candidate.workerCommitmentView.tests.size)
            assertEquals(7, candidate.validationContext.tests.size)
            assertEquals(
                expectedIdentityByTest,
                candidate.privateSuite.tests.associate { it.testId to it.nodeId }
            )
            assertEquals(
                expectedIdentityByTest,
                candidate.validationContext.tests.associate { it.testId to it.nodeId }
            )

            val groupId = candidate.workerStartingField.ringTopology.first()
            val stored = assertNotNull(
                substrateFactory.get(runId, groupId)
                    .sealedTestSuiteDao()
                    .get(runId, groupId)
            )
            val readback = SealedTestSuiteV3Codec.decodeVerified(
                stored.suiteJson,
                candidate.validationContext
            )
            assertEquals(7, readback.tests.size)
            assertEquals(candidate.privateSuite, readback)
            assertEquals(
                expectedIdentityByTest,
                readback.tests.associate { it.testId to it.nodeId }
            )

            val evaluator = SealedTestSuiteEvaluator()
            val preflight = evaluator.inspectCompiledSuite(
                stored.suiteJson,
                candidate.validationContext
            )
            assertTrue(preflight.isComplete, preflight.failures.joinToString())
            assertEquals(7, preflight.declaredTestCount)
            assertEquals(7, preflight.executableTestCount)
            assertEquals(V72_TEST_IDS, readback.tests.map { it.testId })
            assertTrue(
                readback.tests.all { it.definitionHash.matches(Regex("[0-9a-f]{64}")) }
            )
            val assessment = evaluator.assessGroup(
                suiteJson = stored.suiteJson,
                validationContext = candidate.validationContext,
                input = SealedTestEvaluationInputV3(
                    runId = runId,
                    groupId = groupId,
                    roundIndex = 0,
                    finalOutput = SealedTestOutputArtifactV3(
                        relativePath = OUTPUT_SPEC.relativePath,
                        mediaType = OUTPUT_SPEC.mediaType,
                        content = output
                    )
                ),
                scoringDimensions = config.scoringDimensions
            )
            assertEquals(7, assessment.automatedTestsPassed)
            assertEquals(0, assessment.automatedTestsFailed)
            assertFalse(assessment.mandatoryFailure)
            assertEquals(1.0, assessment.overallScore)
            service.abortRun(runId, "Step 7 runtime execution captured")
        }

    @Test
    fun `mixed Council rejection publishes explicit partial compilation evidence`() = runTest {
        val root = createTempDirectory("mixed-startup-rejection-").toFile()
        val substrateFactory = SubstrateDatabaseFactory(root)
        val runId = "mixed-startup-rejection"
        val config = appendixBConfig(runId, "Reject one invalid test while retaining one compiled sibling")
        val envelope = CouncilExecutionEnvelopeFactory.create(config)
        val compileResult = assertIs<StartingFieldCompileResult.Invalid>(
            compiledCouncilFixtureResult(
                runId = runId,
                objective = config.objective,
                envelope = envelope,
                workerGroupKeys = listOf("north", "south"),
                workerProfiles = listOf(GroupProfileType.Library, GroupProfileType.Factory),
                generalTests = listOf(
                    CompiledCouncilGeneralTestFixture(
                        testId = "valid-test",
                        nodeKey = LocalNodeKey("valid-node"),
                        nodeKind = NodeKind.DELIVERY,
                        acceptedArtifactTypes = setOf(ArtifactType.FINAL_DELIVERABLE),
                        expectedText = "valid-output"
                    ),
                    CompiledCouncilGeneralTestFixture(
                        testId = "invalid-test",
                        nodeKey = LocalNodeKey("invalid-node"),
                        nodeKind = NodeKind.EVIDENCE,
                        acceptedArtifactTypes = setOf(ArtifactType.EVIDENCE_TABLE),
                        definitionJson = "null"
                    )
                )
            )
        )
        assertEquals(2, compileResult.declaredTestCount)
        assertEquals(1, compileResult.compiledTestCount)

        val service = EvoMoliorRunService(
            lifecycleManagerProvider = { _, _, _, _, _ ->
                error("mixed invalid Council authority must not activate")
            },
            groupCoordinatorProvider = { groupIds, dimensions -> GroupCoordinator(groupIds, dimensions) },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = substrateFactory,
            archiveService = RunArchiveService(substrateFactory),
            metaPlannerRunner = MetaPlannerRunner(),
            genesisCouncilRunner = { _, _, _, _, _, _, onInvocationStarted ->
                onInvocationStarted()
                compileResult
            }
        )

        assertFailsWith<IllegalStateException> { service.startRun(config) }

        val rejection = assertNotNull(
            StartupRejectionFileStore(substrateFactory::runControlDir).read(runId)
        )
        assertEquals(2, rejection.declaredTestCount)
        assertEquals(1, rejection.compiledTestCount)
        val invalidDiagnostic = assertNotNull(
            rejection.errors.singleOrNull { it.testId?.value == "invalid-test" }
        )
        assertEquals(
            compileResult.definitionHashesByTestId.getValue("invalid-test"),
            invalidDiagnostic.definitionHash
        )
    }

    private fun v71NestedTypedV2Fixture(): String {
        fun stringArray(value: String): JsonArray =
            JsonArray(List(72) { JsonPrimitive(value) })

        // The copied v71 authority used a v1 outer suite. Step 7 requires this exact nested
        // definition replayed inside a v2 outer envelope; the historical definition commitment
        // below proves that no fixture value was simplified or reinterpreted.
        val definition = buildJsonObject {
            put(
                "expectedOutput",
                buildJsonObject {
                    put("hourly_electrical_demand", stringArray("5"))
                    put("hourly_sensible_cooling_load", stringArray("10"))
                    put("peak_electrical_demand", 5.0)
                    put(
                        "result_summary",
                        "heat-pump-enabled; peak demand 5 W; unmet sensible load 0 W"
                    )
                    put(
                        "uncertainty",
                        "Hand-calculation case under the bounded sensible-only model; " +
                            "excludes latent loads, infiltration, thermal storage, weather " +
                            "extremes, and equipment transients."
                    )
                }
            )
            put(
                "input",
                buildJsonObject {
                    put(
                        "scenario",
                        buildJsonObject {
                            put("base_transmission_coefficient", 1.0)
                            put("coating_reduction_fraction", 0.0)
                            put("heat_pump_cooling_capacity", 100.0)
                            put("heat_pump_cop", 2.0)
                            put("heat_pump_enabled", true)
                            put("indoor_setpoint", stringArray("20"))
                            put("internal_gain", stringArray("0"))
                            put("occupancy", stringArray("0"))
                            put("outdoor_temperature", stringArray("30"))
                            put("solar_gain", stringArray("0"))
                        }
                    )
                }
            )
        }
        val definitionJson = CouncilContractHashing.canonicalJson(definition)
        require(CouncilContractHashing.sha256(definitionJson) == V71_DEFINITION_HASH)
        return CouncilContractHashing.canonicalJson(
            buildJsonObject {
                put("atlasHash", V71_ATLAS_HASH)
                put("rubricSchemaVersion", "node-atlas-v1")
                put("schemaVersion", "sealed-tests-v2")
                put(
                    "tests",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put(
                                    "acceptedArtifactTypes",
                                    buildJsonArray {
                                        add(JsonPrimitive("SOURCE_CODE"))
                                        add(JsonPrimitive("TOOL"))
                                    }
                                )
                                put("commitmentHash", V71_DEFINITION_HASH)
                                put("id", V71_TEST_ID)
                                put("nodeId", V71_NODE_ID)
                                put("testDefinitionJson", definitionJson)
                            }
                        )
                    }
                )
            }
        )
    }

    private fun v72Records(fixture: String): List<V72Record> =
        json.parseToJsonElement(fixture)
            .jsonObject
            .getValue("tests")
            .jsonArray
            .map { element ->
                val record = element.jsonObject
                V72Record(
                    testId = record.getValue("id").jsonPrimitive.content,
                    nodeId = record.getValue("nodeId").jsonPrimitive.content,
                    acceptedArtifactTypes = record.getValue("acceptedArtifactTypes")
                        .jsonArray
                        .mapTo(linkedSetOf()) {
                            ArtifactType.valueOf(it.jsonPrimitive.content)
                        },
                    definitionJson =
                        record.getValue("testDefinitionJson").jsonPrimitive.content,
                    commitmentHash =
                        record.getValue("commitmentHash").jsonPrimitive.content,
                    kind = record.getValue("kind").jsonPrimitive.content,
                    opportunityId = record["opportunityId"]
                        ?.jsonPrimitive
                        ?.takeUnless { it.toString() == "null" }
                        ?.content
                )
            }

    private fun resourceText(path: String): String =
        assertNotNull(javaClass.getResource(path)).readText()

    private fun v72HistoricalNodeKey(nodeId: String): LocalNodeKey =
        LocalNodeKey(
            V72_NODE_KEYS_BY_NODE_ID[nodeId]
                ?: error("Unknown historical v72 node identity: $nodeId")
        )

    private fun appendixBConfig(runId: String, objective: String): RunConfig =
        RunConfig(
            runId = runId,
            objective = objective,
            groupCount = 2,
            groupProfiles = listOf(GroupProfileType.Library, GroupProfileType.Factory),
            rounds = 1,
            roundDurationMinutes = 0,
            maxAgentsPerGroup = 1,
            scoringDimensions = listOf(
                ScoringDimension(
                    id = "accuracy",
                    name = "Accuracy",
                    description = "Exact executable-test acceptance",
                    weight = 1.0
                )
            ),
            openRouterApiKey = "/tmp/codex",
            llmBackend = LlmBackend.CODEX_CHATGPT,
            councilExecutionPurpose = CouncilExecutionPurpose.APPENDIX_B_ACCEPTANCE,
            finalOutputSpec = RequestedFinalOutputSpec(
                relativePath = OUTPUT_SPEC.relativePath,
                mediaType = OUTPUT_SPEC.mediaType
            )
        )

    private fun v72NodeKind(testId: String): NodeKind = when (testId) {
        "final-package-completeness-check" -> NodeKind.DELIVERY
        "one-round-reuse-scheduling-check" -> NodeKind.SIMULATION
        "simulator-cop-boundary-test",
        "simulator-hour-count-test",
        "simulator-load-conservation-test" -> NodeKind.TOOL
        "validation-fixture-reproduction-test",
        "validation-invalid-input-test" -> NodeKind.VALIDATION
        else -> error("Unknown v72 test identity: $testId")
    }

    private fun workerGroupDirectories(
        factory: SubstrateDatabaseFactory,
        runId: String
    ): List<File> = File(factory.runRootDir(runId), "groups")
        .listFiles()
        .orEmpty()
        .filterNot { it.name.startsWith("__") }

    private fun graderDirectories(
        factory: SubstrateDatabaseFactory,
        runId: String
    ): List<File> = File(factory.runRootDir(runId), "graders")
        .listFiles()
        .orEmpty()
        .toList()

    private data class V72Record(
        val testId: String,
        val nodeId: String,
        val acceptedArtifactTypes: Set<ArtifactType>,
        val definitionJson: String,
        val commitmentHash: String,
        val kind: String,
        val opportunityId: String?
    )

    private companion object {
        const val V72_RESOURCE = "/sealed-tests/v72-seven-null-private.json"
        const val V72_RESOURCE_SHA256 =
            "a24d9a9ebe89dd5a490c3e8b9136e8ff82bdacfc0cd3ef200181307e98ac936a"
        const val NULL_SHA256 =
            "74234e98afe7498fb5daf1f36ac2d78acc339464f950703b8c019892f982b90b"
        const val V71_ATLAS_HASH =
            "57d3232a3765259846e5602dadffb0c9e737763e56c336b1ebea11a60d3567b4"
        const val V71_TEST_ID = "tool-promotion-hand-calculation-test"
        const val V71_NODE_ID =
            "node_67e775255f966f19446f029572ecb1e5cf3d7b2f1b33993385bcb21027d290e2"
        const val V71_DEFINITION_HASH =
            "86767ec1d124d81a3d91fa64fdf9192ca2a2f2517edcdb5219b06e9dab5eecb5"
        const val V71_FIXTURE_SHA256 =
            "3d8d6deff4ef6443cf9a07dc1a165d98ebb5b5111b8709eb64b3097aa43e3268"
        const val V72_ATLAS_HASH =
            "744c28eeb5fea0ee2086921965ec5038b91ed4ea1cf46a77f63fab288a4c63ac"
        const val V72_HISTORICAL_RUN_ID =
            "appendix-b-representative-gpt-20260718-v72"
        val V72_NODE_KEYS_BY_NODE_ID = mapOf(
            "node_218c6b72b879f26e1915a9a776c21d9aeef223421d0f47c254d704f48dc93666" to
                "final-markdown-package",
            "node_1b83fdeb7e3e865d94f025053d12b0288a81d5454f4f609732d5de9449c07c1c" to
                "independent-scenario-analysis",
            "node_8b35e1d4b5e16e00e549f7d72ec746034b7c548e2330c6866b0cc59b40b43e6b" to
                "hourly-cooling-demand-simulator-authoring",
            "node_fa91447ffe1e65b6d9613161cf0a804498d8e3487ff0dcd52c0577783b06b023" to
                "simulator-validation-record"
        )
        val OUTPUT_SPEC = FinalOutputSpec("final-deliverable.md", "text/markdown")
        val V72_TEST_IDS = listOf(
            "final-package-completeness-check",
            "one-round-reuse-scheduling-check",
            "simulator-cop-boundary-test",
            "simulator-hour-count-test",
            "simulator-load-conservation-test",
            "validation-fixture-reproduction-test",
            "validation-invalid-input-test"
        )
        val V72_EXPECTED_TEXT = linkedMapOf(
            "final-package-completeness-check" to "complete final decision package",
            "one-round-reuse-scheduling-check" to
                "later independent execution acceptance gap",
            "simulator-cop-boundary-test" to "COP boundary",
            "simulator-hour-count-test" to "72 hourly records",
            "simulator-load-conservation-test" to "load conservation",
            "validation-fixture-reproduction-test" to "fixture reproduction",
            "validation-invalid-input-test" to "invalid input"
        )
    }
}
