@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.api.deliverable.StartupDiagnosticOutcomeCompiler
import com.achulkov.evomolior.api.events.EvoMoliorEvent
import com.achulkov.evomolior.api.model.FinalOutputSpec
import com.achulkov.evomolior.api.model.RunConfig
import com.achulkov.evomolior.competition.coordinator.GroupCoordinator
import com.achulkov.evomolior.competition.grader.GraderGroupManager
import com.achulkov.evomolior.core.contract.StartingField
import com.achulkov.evomolior.core.contract.PublicationState
import com.achulkov.evomolior.core.contract.PublicationStateEvent
import com.achulkov.evomolior.core.contract.StartupDiagnosticState
import com.achulkov.evomolior.core.contract.StartupDiagnosticStateEvent
import com.achulkov.evomolior.core.contract.RunInputArtifact
import com.achulkov.evomolior.core.contract.RunInputArtifactKind
import com.achulkov.evomolior.core.model.GroupProfileType
import com.achulkov.evomolior.core.model.ScoringDimension
import com.achulkov.evomolior.orchestration.lifecycle.LifecycleManager
import com.achulkov.evomolior.orchestration.metaplanner.MetaPlannerRunner
import com.achulkov.evomolior.sandbox.shuru.ShuruVmManager
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.db.entity.MetaArchiveEntryEntity
import com.achulkov.evomolior.substrate.publication.PublicationStateRepository
import com.achulkov.evomolior.substrate.publication.PublicationOutcomeRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.jvm.isAccessible

class EvoMoliorRunServicePersistedResumeTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `startup archive first resume restores terminal diagnostic history`() = runBlocking {
        val tmpDir = createTempDirectory("svc-startup-archive-first-resume-").toFile()
        val substrateFactory = SubstrateDatabaseFactory(tmpDir)
        val runId = "startup-archive-first-resume"
        val config = singleGroupConfig(
            runId = runId,
            objective = "Remain rejected before runtime activation.",
        )
        PersistedRunStateStore.writeForDataRoot(
            tmpDir,
            PersistedRunState.fromRunConfig(
                config = config,
                groupIds = listOf("group-0"),
                graderGroupIds = emptyList(),
                allowedHosts = emptySet(),
                status = PersistedRunStatus.RUNNING,
            )
        )
        val generatedAt = kotlin.time.Instant.parse("2026-07-25T09:00:00Z")
        val rejection = StartupRejectionArtifact(
            runId = runId,
            phase = StartupRejectionPhase.ADMISSION,
            reasons = listOf(StartupRejectionReason.ADMISSION_REJECTED),
            errors = listOf(
                StartupRejectionError(
                    kind = StartupRejectionErrorKind.ADMISSION,
                    message = "The public startup contract was rejected.",
                )
            ),
            declaredWorkerGroupCount = 1,
            declaredGraderGroupCount = 0,
            distributionManifestHash = "a".repeat(64),
            activationFlags = StartupActivationFlags(
                runStartedEmitted = false,
                workerInfrastructureActivated = false,
                graderInfrastructureActivated = false,
                successArchiveCreated = false,
                winnerAuthorityCreated = false,
            ),
            createdAt = "2026-07-25T09:00:00Z",
        )
        val compilation = StartupDiagnosticOutcomeCompiler.compile(
            rejection = rejection,
            generatedAt = generatedAt,
        )
        var previous: StartupDiagnosticStateEvent? = null
        val completeHistory = StartupDiagnosticState.entries.mapIndexed { index, state ->
            StartupDiagnosticStateEvent(
                eventId = "startup-archive-first-$index",
                runId = runId,
                state = state,
                sequence = index,
                previousEventHash = previous?.eventHash,
                authorityReferenceIds = setOf("startup-authority-$index"),
                occurredAt = generatedAt,
            ).withComputedHash().also { previous = it }
        }
        val outcomeRepository = PublicationOutcomeRepository(
            substrateFactory.getAtlas(runId)
        )
        completeHistory.dropLast(1).forEach { outcomeRepository.appendStartup(it) }
        val archive = RunOutcomeArchiveService().archiveStartupDiagnostic(
            StartupDiagnosticArchiveRequest(
                runId = runId,
                admissionDistributionHash = "a".repeat(64),
                startingFieldHash = null,
                lifecycleReconciliationHash = "b".repeat(64),
                blueprint = compilation.blueprint,
                decisionPackage = compilation.decisionPackage,
                diagnosticPackage = compilation.diagnosticPackage,
                publicationHistory = completeHistory,
                mechanismActivationReport = startupMechanismActivationReport(
                    runId = runId,
                    generatedAt = generatedAt,
                    rejectionAuthorityId = "startup-rejection/${"c".repeat(64)}",
                    terminalPublicationAuthorityId =
                        "startup-archive-event/${completeHistory.last().eventHash}",
                ),
                archivedAtMs = generatedAt.toEpochMilliseconds(),
            )
        )
        RunArchiveService(substrateFactory).writeOutcomeArchive(runId, archive)

        val service = testService(substrateFactory)
        val aborted = async {
            withTimeout(5_000L) {
                service.events.first {
                    it is EvoMoliorEvent.RunAborted && it.runId == runId
                }
            }
        }
        yield()

        assertEquals(runId, service.resumePersistedRun(config))
        assertIs<EvoMoliorEvent.RunAborted>(aborted.await())
        assertEquals(completeHistory, outcomeRepository.startupHistory(runId))
        assertEquals(
            StartupDiagnosticState.ARCHIVED_REJECTED_PRE_RUNTIME,
            outcomeRepository.startupHistory(runId).last().state,
        )
        assertEquals(PersistedRunStatus.FAILED, PersistedRunStateStore.read(tmpDir, runId)?.status)
    }

    @Test
    fun `resume persisted run recovers from sealed starting field and finishes`() = runBlocking {
        val tmpDir = createTempDirectory("svc-persisted-resume-").toFile()
        val substrateFactory = SubstrateDatabaseFactory(tmpDir)
        val runId = "persisted-resume-run"
        val config = RunConfig(
            runId = runId,
            objective = "Resume the persisted run",
            groupCount = 2,
            groupProfiles = listOf(GroupProfileType.Library, GroupProfileType.Factory),
            rounds = 1,
            roundDurationMinutes = 0,
            maxAgentsPerGroup = 1,
            scoringDimensions = listOf(
                ScoringDimension(
                    id = "accuracy",
                    name = "Accuracy",
                    description = "Reward evidence-based evaluation.",
                    weight = 1.0
                )
            ),
            openRouterApiKey = "sk-test",
            isTestRun = true,
            budgetOverride = 100.0
        )
        val startingField = StartingField.default(
            objective = config.objective,
            runId = runId,
            groupCount = 2,
            groupProfilesMap = mapOf(
                "group-0" to GroupProfileType.Library,
                "group-1" to GroupProfileType.Factory
            ),
            budgetCeiling = 100.0,
            generationCeiling = 1
        ).copy(graderGroupCount = 0)
        val archiveDb = substrateFactory.get(runId, "__meta_archive__")
        archiveDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = "starting-field-$runId",
                runId = runId,
                entryType = RunArchiveService.ENTRY_STARTING_FIELD,
                groupId = null,
                label = "StartingField — $runId",
                contentJson = json.encodeToString(StartingField.serializer(), startingField),
                runObjective = config.objective,
                finalScore = null,
                isFromWinner = false,
                generation = 0,
                archivedAtMs = System.currentTimeMillis()
            )
        )
        PersistedRunStateStore.write(
            PersistedRunStateStore.resolveStateFile(tmpDir, runId),
            PersistedRunState.fromRunConfig(
                config = config,
                groupIds = listOf("group-0", "group-1"),
                graderGroupIds = emptyList(),
                allowedHosts = emptySet(),
                nextRoundIndex = 0,
                activeRoundIndex = null,
                status = PersistedRunStatus.BOOTSTRAPPED
            )
        )

        val service = testService(substrateFactory)

        val resumedRunId = service.resumePersistedRun(config)
        assertEquals(runId, resumedRunId)

        val terminalEvent = withTimeout(10_000) {
            service.events.first { event ->
                when (event) {
                    is EvoMoliorEvent.RunCompleted -> event.runId == runId
                    is EvoMoliorEvent.RunRecovered -> event.runId == runId
                    is EvoMoliorEvent.RunAborted -> event.runId == runId
                    else -> false
                }
            }
        }
        assertIs<EvoMoliorEvent.RunRecovered>(terminalEvent)

        val bundle = service.getResultsBundle(runId)
        assertNotNull(bundle)
        val persistedState = PersistedRunStateStore.read(tmpDir, runId)
        assertNotNull(persistedState)
        assertEquals(PersistedRunStatus.RECOVERED, persistedState.status)
        assertTrue(
            service.getMetaArchiveEntries(runId, RunArchiveService.ENTRY_RUN_COMPLETED).isEmpty(),
            "Provisional recovery must not mint an accepted completion marker"
        )
    }

    @Test
    @OptIn(kotlin.time.ExperimentalTime::class)
    fun `completed resume advances manifest hashed archive without rebuilding bundle`() = runBlocking {
        val tmpDir = createTempDirectory("svc-manifest-hashed-resume-").toFile()
        val substrateFactory = SubstrateDatabaseFactory(tmpDir)
        val runId = "manifest-hashed-resume-run"
        val config = singleGroupConfig(
            runId = runId,
            objective = "Recover the exact receiver archive."
        )
        val startingField = StartingField.default(
            objective = config.objective,
            runId = runId,
            groupCount = 1,
            groupProfilesMap = mapOf("group-0" to GroupProfileType.Library),
            budgetCeiling = 100.0,
            generationCeiling = 1
        )
        val completion = RunCompletionRecord(
            runId = runId,
            winnerId = "group-0",
            completedRoundCount = 1,
            finalAnswerSummary = "Exact archive recovery."
        )
        insertCompletionMarker(substrateFactory, runId, config.objective, completion)
        val archiveService = RunArchiveService(substrateFactory)
        archiveService.archiveRun(
            runId = runId,
            groupIds = listOf("group-0"),
            winnerId = "group-0",
            objective = config.objective,
            startingFieldJson = json.encodeToString(StartingField.serializer(), startingField),
            finalGeneration = 1,
            completionRecord = completion
        )
        PersistedRunStateStore.write(
            PersistedRunStateStore.resolveStateFile(tmpDir, runId),
            PersistedRunState.fromRunConfig(
                config = config,
                groupIds = listOf("group-0"),
                graderGroupIds = emptyList(),
                allowedHosts = emptySet(),
                nextRoundIndex = 1,
                activeRoundIndex = null,
                status = PersistedRunStatus.FAILED
            )
        )
        val bundle = assertNotNull(archiveService.writeResultsBundle(runId, completion))
        val exactHash = sha256Hex(bundle.readBytes())
        val publication = PublicationStateRepository(substrateFactory.getAtlas(runId))
        var previous: PublicationStateEvent? = null
        PublicationState.entries
            .takeWhile { it != PublicationState.ARCHIVED }
            .forEachIndexed { index, state ->
                val event = PublicationStateEvent(
                    eventId = "resume-publication-${state.name}",
                    runId = runId,
                    state = state,
                    sequence = index,
                    previousEventHash = previous?.eventHash,
                    authorityReferenceIds = setOf(
                        if (state == PublicationState.MANIFEST_HASHED) {
                            "results-bundle:${bundle.name}:$exactHash"
                        } else {
                            "authority-${state.name}"
                        }
                    ),
                    occurredAt = kotlin.time.Instant.parse("2026-07-18T07:00:00Z")
                ).withComputedHash()
                publication.append(event)
                previous = event
            }

        val service = testService(substrateFactory, archiveService)
        assertEquals(runId, service.resumePersistedRun(config))

        assertEquals(exactHash, sha256Hex(bundle.readBytes()))
        assertEquals(PublicationState.ARCHIVED, publication.latest(runId)?.state)
        assertEquals(
            EngineStatus.IDLE,
            service.getState().status,
            "Completed resume must finish cleanup before RunCompleted is observable."
        )
    }

    @Test
    fun `resume rejects pre-v62 StartingField with an explicit compatibility diagnostic`() =
        runBlocking {
            val tmpDir = createTempDirectory("svc-pre-v62-resume-").toFile()
            val substrateFactory = SubstrateDatabaseFactory(tmpDir)
            val runId = "pre-v62-resume-run"
            val config = RunConfig(
                runId = runId,
                objective = "Reject historical authority",
                groupCount = 2,
                groupProfiles = listOf(GroupProfileType.Library, GroupProfileType.Factory),
                rounds = 1,
                roundDurationMinutes = 0,
                maxAgentsPerGroup = 1,
                scoringDimensions = listOf(
                    ScoringDimension(
                        id = "accuracy",
                        name = "Accuracy",
                        description = "Reward exact authority.",
                        weight = 1.0
                    )
                ),
                openRouterApiKey = "sk-test",
                isTestRun = true,
                budgetOverride = 100.0
            )
            val current = StartingField.default(
                objective = config.objective,
                runId = runId,
                groupCount = 2,
                groupProfilesMap = mapOf(
                    "group-0" to GroupProfileType.Library,
                    "group-1" to GroupProfileType.Factory
                ),
                budgetCeiling = 100.0,
                generationCeiling = 1
            )
            val encoded = json.parseToJsonElement(
                json.encodeToString(StartingField.serializer(), current)
            ).jsonObject
            val preV62 = JsonObject(encoded - "wireVersion").toString()
            substrateFactory.get(runId, "__meta_archive__").metaArchiveDao().insert(
                MetaArchiveEntryEntity(
                    id = "starting-field-$runId",
                    runId = runId,
                    entryType = RunArchiveService.ENTRY_STARTING_FIELD,
                    groupId = null,
                    label = "StartingField - $runId",
                    contentJson = preV62,
                    runObjective = config.objective,
                    finalScore = null,
                    isFromWinner = false,
                    generation = 0,
                    archivedAtMs = System.currentTimeMillis()
                )
            )
            val service = testService(substrateFactory)

            val failure = assertFailsWith<IllegalStateException> {
                service.resumePersistedRun(config)
            }

            assertTrue(failure.message.orEmpty().contains("wireVersion=missing (pre-v62)"))
            assertTrue(
                failure.message.orEmpty().contains(
                    "cannot be resumed, continued, or rebuilt as current authority"
                )
            )
        }

    @Test
    fun `resume rejects current wire StartingField carrying a stale seal`() = runBlocking {
        val tmpDir = createTempDirectory("svc-stale-seal-resume-").toFile()
        val substrateFactory = SubstrateDatabaseFactory(tmpDir)
        val runId = "stale-seal-resume-run"
        val config = testConfig(runId, "Reject restamped active authority")
        val current = StartingField.default(
            objective = config.objective,
            runId = runId,
            groupCount = 2,
            groupProfilesMap = mapOf(
                "group-0" to GroupProfileType.Library,
                "group-1" to GroupProfileType.Factory
            ),
            budgetCeiling = 100.0,
            generationCeiling = 1
        )
        insertStartingField(
            substrateFactory = substrateFactory,
            runId = runId,
            objective = config.objective,
            contentJson = staleSealJson(current)
        )

        val failure = assertFailsWith<IllegalStateException> {
            testService(substrateFactory).resumePersistedRun(config)
        }

        assertTrue(failure.message.orEmpty().contains("seal.schemaVersion=2"))
        assertTrue(
            failure.message.orEmpty().contains(
                "Restamped or stale sealed fields cannot become current authority"
            )
        )
    }

    @Test
    fun `resume rejects unreadable persisted state before activating the run`() = runBlocking {
        val tmpDir = createTempDirectory("svc-corrupt-state-resume-").toFile()
        val substrateFactory = SubstrateDatabaseFactory(tmpDir)
        val runId = "corrupt-state-resume-run"
        val config = testConfig(runId, "Reject unreadable recovery authority")
        val startingField = StartingField.default(
            objective = config.objective,
            runId = runId,
            groupCount = 2,
            groupProfilesMap = mapOf(
                "group-0" to GroupProfileType.Library,
                "group-1" to GroupProfileType.Factory
            ),
            budgetCeiling = 100.0,
            generationCeiling = 1
        )
        insertStartingField(
            substrateFactory = substrateFactory,
            runId = runId,
            objective = config.objective,
            contentJson = json.encodeToString(StartingField.serializer(), startingField)
        )
        PersistedRunStateStore.resolveStateFile(tmpDir, runId).apply {
            parentFile.mkdirs()
            writeText("{")
        }
        val service = testService(substrateFactory)

        val failure = assertFailsWith<IllegalStateException> {
            service.resumePersistedRun(config)
        }

        assertTrue(failure.message.orEmpty().contains("Persisted run state is unreadable"))
        assertEquals(EngineStatus.IDLE, service.getState().status)
        assertEquals(null, service.getState().activeRunId)
    }

    @Test
    fun `continuation rejects current wire source carrying a stale seal`() = runBlocking {
        val tmpDir = createTempDirectory("svc-stale-seal-continuation-").toFile()
        val substrateFactory = SubstrateDatabaseFactory(tmpDir)
        val sourceRunId = "stale-seal-source-run"
        val targetRunId = "stale-seal-target-run"
        val source = StartingField.default(
            objective = "Historical source",
            runId = sourceRunId,
            groupCount = 1,
            groupProfilesMap = mapOf("group-0" to GroupProfileType.Library),
            budgetCeiling = 100.0,
            generationCeiling = 1
        )
        val archiveDb = substrateFactory.get(sourceRunId, "__meta_archive__")
        insertStartingField(
            substrateFactory = substrateFactory,
            runId = sourceRunId,
            objective = source.objective,
            contentJson = staleSealJson(source)
        )
        archiveDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = "run-completed-$sourceRunId",
                runId = sourceRunId,
                entryType = RunArchiveService.ENTRY_RUN_COMPLETED,
                groupId = null,
                label = "Run Completed - $sourceRunId",
                contentJson = json.encodeToString(
                    RunCompletionRecord.serializer(),
                    RunCompletionRecord(runId = sourceRunId, winnerId = "group-0")
                ),
                runObjective = source.objective,
                finalScore = null,
                isFromWinner = false,
                generation = 1,
                archivedAtMs = System.currentTimeMillis()
            )
        )
        val targetConfig = RunConfig(
            runId = targetRunId,
            objective = "Return one short status note.",
            groupCount = 1,
            groupProfiles = listOf(GroupProfileType.Library),
            rounds = 1,
            roundDurationMinutes = 0,
            maxAgentsPerGroup = 1,
            scoringDimensions = listOf(accuracyDimension()),
            openRouterApiKey = "sk-test",
            isTestRun = true,
            budgetOverride = 100.0,
            continuationFromRunId = sourceRunId
        )

        val failure = assertFailsWith<IllegalStateException> {
            testService(substrateFactory).startRun(targetConfig)
        }

        assertTrue(failure.message.orEmpty().contains("seal.schemaVersion=2"))
        assertTrue(failure.message.orEmpty().contains("continuation source StartingField"))
    }

    @Test
    fun `missing continuation artifact aborts target and preserves completed source`() =
        runBlocking {
            val tmpDir = createTempDirectory("svc-missing-continuation-artifact-").toFile()
            val substrateFactory = SubstrateDatabaseFactory(tmpDir)
            val sourceRunId = "missing-artifact-source"
            val targetRunId = "missing-artifact-target"
            val artifact = RunInputArtifact(
                relativePath = "docs/missing.txt",
                sourceName = "missing.txt",
                mimeType = "text/plain",
                sizeBytes = 7,
                sha256 = "0".repeat(64),
                kind = RunInputArtifactKind.TEXT,
                textPreview = "missing"
            )
            val source = StartingField.default(
                objective = "Completed source",
                runId = sourceRunId,
                groupCount = 1,
                groupProfilesMap = mapOf("group-0" to GroupProfileType.Library)
            ).copy(inputArtifacts = listOf(artifact))
            insertStartingField(
                substrateFactory = substrateFactory,
                runId = sourceRunId,
                objective = source.objective,
                contentJson = json.encodeToString(StartingField.serializer(), source)
            )
            insertCompletionMarker(substrateFactory, sourceRunId, source.objective)
            val sourceConfig = singleGroupConfig(sourceRunId, source.objective)
            PersistedRunStateStore.write(
                PersistedRunStateStore.resolveStateFile(tmpDir, sourceRunId),
                PersistedRunState.fromRunConfig(
                    config = sourceConfig,
                    groupIds = listOf("group-0"),
                    graderGroupIds = emptyList(),
                    allowedHosts = emptySet(),
                    nextRoundIndex = 1,
                    activeRoundIndex = null,
                    status = PersistedRunStatus.COMPLETED
                )
            )
            File(
                substrateFactory.runRootDir(sourceRunId),
                "artifacts"
            ).mkdirs()
            val service = testService(substrateFactory)
            val aborted = async {
                withTimeout(5_000) {
                    service.events.first {
                        it is EvoMoliorEvent.RunAborted && it.runId == targetRunId
                    }
                }
            }
            yield()

            val failure = assertFailsWith<IllegalStateException> {
                service.startRun(
                    singleGroupConfig(
                        runId = targetRunId,
                        objective = "Return one short status note.",
                        continuationFromRunId = sourceRunId
                    ).copy(
                        finalOutputSpec = FinalOutputSpec(
                            relativePath = "target.md",
                            mediaType = "text/markdown"
                        )
                    )
                )
            }

            val abortEvent = assertIs<EvoMoliorEvent.RunAborted>(aborted.await())
            assertTrue(abortEvent.reason.contains("artifact missing: docs/missing.txt"))
            assertTrue(failure.message.orEmpty().contains("source run=$sourceRunId"))
            assertEquals(
                PersistedRunStatus.COMPLETED,
                PersistedRunStateStore.read(tmpDir, sourceRunId)?.status
            )
            assertTrue(
                substrateFactory.get(sourceRunId, "__meta_archive__")
                    .metaArchiveDao()
                    .getByType(RunArchiveService.ENTRY_RUN_COMPLETED)
                    .any { it.runId == sourceRunId }
            )
            assertEquals(null, service.getState().activeRunId)
            assertFalse(targetRunId in finalOutputSpecs(service))

            val nextRunId = "post-artifact-failure-run"
            val nextRecovered = async {
                withTimeout(10_000) {
                    service.events.first { event ->
                        when (event) {
                            is EvoMoliorEvent.RunCompleted -> event.runId == nextRunId
                            is EvoMoliorEvent.RunRecovered -> event.runId == nextRunId
                            is EvoMoliorEvent.RunAborted -> event.runId == nextRunId
                            else -> false
                        }
                    }
                }
            }
            yield()
            assertEquals(
                nextRunId,
                service.startRun(
                    singleGroupConfig(
                        runId = nextRunId,
                        objective = "Return OK."
                    )
                )
            )
            val nextTerminal = nextRecovered.await()
            assertTrue(
                nextTerminal is EvoMoliorEvent.RunRecovered,
                "Expected provisional recovery, got $nextTerminal",
            )
            Unit
        }

    @Test
    fun `missing continuation artifact root fails when source metadata is nonempty`() =
        runBlocking {
            val tmpDir = createTempDirectory("svc-missing-continuation-root-").toFile()
            val substrateFactory = SubstrateDatabaseFactory(tmpDir)
            val sourceRunId = "missing-root-source"
            val targetRunId = "missing-root-target"
            val source = StartingField.default(
                objective = "Completed source",
                runId = sourceRunId,
                groupCount = 1,
                groupProfilesMap = mapOf("group-0" to GroupProfileType.Library)
            ).copy(
                inputArtifacts = listOf(
                    RunInputArtifact(
                        relativePath = "docs/missing.txt",
                        sourceName = "missing.txt",
                        mimeType = "text/plain",
                        sizeBytes = 7,
                        sha256 = "0".repeat(64),
                        kind = RunInputArtifactKind.TEXT
                    )
                )
            )
            insertStartingField(
                substrateFactory = substrateFactory,
                runId = sourceRunId,
                objective = source.objective,
                contentJson = json.encodeToString(StartingField.serializer(), source)
            )
            insertCompletionMarker(substrateFactory, sourceRunId, source.objective)

            val failure = assertFailsWith<IllegalStateException> {
                testService(substrateFactory).startRun(
                    singleGroupConfig(
                        runId = targetRunId,
                        objective = "Return one short status note.",
                        continuationFromRunId = sourceRunId
                    )
                )
            }

            assertTrue(failure.message.orEmpty().contains("artifact root is missing"))
        }

    private suspend fun insertStartingField(
        substrateFactory: SubstrateDatabaseFactory,
        runId: String,
        objective: String,
        contentJson: String
    ) {
        substrateFactory.get(runId, "__meta_archive__").metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = "starting-field-$runId",
                runId = runId,
                entryType = RunArchiveService.ENTRY_STARTING_FIELD,
                groupId = null,
                label = "StartingField - $runId",
                contentJson = contentJson,
                runObjective = objective,
                finalScore = null,
                isFromWinner = false,
                generation = 0,
                archivedAtMs = System.currentTimeMillis()
            )
        )
    }

    private suspend fun insertCompletionMarker(
        substrateFactory: SubstrateDatabaseFactory,
        runId: String,
        objective: String,
        completionRecord: RunCompletionRecord =
            RunCompletionRecord(runId = runId, winnerId = "group-0")
    ) {
        substrateFactory.get(runId, "__meta_archive__").metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = "run-completed-$runId",
                runId = runId,
                entryType = RunArchiveService.ENTRY_RUN_COMPLETED,
                groupId = null,
                label = "Run Completed - $runId",
                contentJson = json.encodeToString(
                    RunCompletionRecord.serializer(),
                    completionRecord
                ),
                runObjective = objective,
                finalScore = null,
                isFromWinner = false,
                generation = 1,
                archivedAtMs = System.currentTimeMillis()
            )
        )
    }

    private fun staleSealJson(field: StartingField): String {
        val encoded = json.parseToJsonElement(
            json.encodeToString(StartingField.serializer(), field)
        ).jsonObject
        return JsonObject(
            encoded + (
                "seal" to JsonObject(
                    mapOf("schemaVersion" to kotlinx.serialization.json.JsonPrimitive("2"))
                )
                )
        ).toString()
    }

    private fun testConfig(runId: String, objective: String): RunConfig = RunConfig(
        runId = runId,
        objective = objective,
        groupCount = 2,
        groupProfiles = listOf(GroupProfileType.Library, GroupProfileType.Factory),
        rounds = 1,
        roundDurationMinutes = 0,
        maxAgentsPerGroup = 1,
        scoringDimensions = listOf(accuracyDimension()),
        openRouterApiKey = "sk-test",
        isTestRun = true,
        budgetOverride = 100.0
    )

    private fun accuracyDimension(): ScoringDimension = ScoringDimension(
        id = "accuracy",
        name = "Accuracy",
        description = "Reward exact authority.",
        weight = 1.0
    )

    private fun singleGroupConfig(
        runId: String,
        objective: String,
        continuationFromRunId: String? = null
    ): RunConfig = RunConfig(
        runId = runId,
        objective = objective,
        groupCount = 1,
        groupProfiles = listOf(GroupProfileType.Library),
        rounds = 1,
        roundDurationMinutes = 0,
        maxAgentsPerGroup = 1,
        scoringDimensions = listOf(accuracyDimension()),
        openRouterApiKey = "sk-test",
        isTestRun = true,
        budgetOverride = 100.0,
        continuationFromRunId = continuationFromRunId
    )

    @Suppress("UNCHECKED_CAST")
    private fun finalOutputSpecs(
        service: EvoMoliorRunService
    ): Map<String, FinalOutputSpec> {
        val property = service::class.declaredMemberProperties
            .single { it.name == "runFinalOutputSpecs" }
            .apply { isAccessible = true }
        return property.getter.call(service) as Map<String, FinalOutputSpec>
    }

    private fun testService(
        substrateFactory: SubstrateDatabaseFactory,
        archiveService: RunArchiveService? = null
    ): EvoMoliorRunService = EvoMoliorRunService(
        lifecycleManagerProvider = { runIdValue, groupIdValue, _, _, groupProfile ->
            LifecycleManager(
                runId = runIdValue,
                groupId = groupIdValue,
                shuruVmManager = ShuruVmManager(),
                substrateDatabaseFactory = substrateFactory,
                agentJarPath = "/fake/agent.jar",
                openRouterApiKey = "sk-test",
                groupProfile = groupProfile,
                localAgentRunner = { _, _ -> }
            )
        },
        groupCoordinatorProvider = { groupIds, dimensions ->
            GroupCoordinator(groupIds, dimensions)
        },
        graderGroupManagerProvider = { dimensions ->
            GraderGroupManager(dimensions)
        },
        substrateFactory = substrateFactory,
        archiveService = archiveService,
        metaPlannerRunner = MetaPlannerRunner(),
        roundHeartbeatIntervalMs = 1L,
        inspectorCheckIntervalMs = 1L
    )
}
