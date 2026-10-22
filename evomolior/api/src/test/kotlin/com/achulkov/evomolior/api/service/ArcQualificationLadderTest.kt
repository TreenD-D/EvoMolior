@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.api.events.EvoMoliorEvent
import com.achulkov.evomolior.api.model.FinalOutputSpec
import com.achulkov.evomolior.api.model.FinalOutputValidationContract
import com.achulkov.evomolior.api.model.RunConfig
import com.achulkov.evomolior.competition.coordinator.GroupCoordinator
import com.achulkov.evomolior.competition.grader.GraderGroupManager
import com.achulkov.evomolior.core.deliverable.AcceptanceStatus
import com.achulkov.evomolior.core.deliverable.ArcVisibleLooBenchmarkDefinition
import com.achulkov.evomolior.core.deliverable.ArcVisibleLooFamilyProvenance
import com.achulkov.evomolior.core.deliverable.ArcVisibleLooFixtureFactory
import com.achulkov.evomolior.core.deliverable.ArcVisibleLooHashing
import com.achulkov.evomolior.core.deliverable.ArcVisibleLooReport
import com.achulkov.evomolior.core.deliverable.ArtifactRef
import com.achulkov.evomolior.core.deliverable.ArtifactSnapshot
import com.achulkov.evomolior.core.deliverable.ExecutionCompletionStatus
import com.achulkov.evomolior.core.deliverable.PrimaryArtifactValidationStatus
import com.achulkov.evomolior.core.deliverable.WriterSourceAuthorityClass
import com.achulkov.evomolior.core.model.GroupProfileType
import com.achulkov.evomolior.core.model.ScoringDimension
import com.achulkov.evomolior.core.model.SubmissionType
import com.achulkov.evomolior.orchestration.lifecycle.LifecycleManager
import com.achulkov.evomolior.sandbox.shuru.ShuruVmManager
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import com.achulkov.evomolior.substrate.worker.WorkerExecutionRepository
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.zip.ZipInputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json

/**
 * Provider-free runtime coverage for general candidate delivery and the ARC artifact spine.
 *
 * These are runtime topology fixtures, not provider or Kaggle acceptance evidence. Their purpose
 * is to prove that the same two-round scaffold, writer ordering, and provisional terminal axes
 * work at the isolated one-group and competing three-group boundaries before a live Gate-E run.
 */
class ArcQualificationLadderTest {
    @Test
    fun `provisional publication drains a writer that remains active after its DOC`() = runBlocking {
        val content = "# Receiver report\n\nA useful source-grounded report.\n"
        val result = runTopology(
            groupCount = 1,
            isTestRun = true,
            runId = "general-main-writer-drain",
            emitMainWriterArtifact = true,
            requestedOutputSpec = FinalOutputSpec(
                relativePath = "report.md",
                mediaType = "text/markdown",
                validation = FinalOutputValidationContract(
                    requiredMarkdownSectionSignals = listOf("Receiver report"),
                ),
            ),
            writerContent = content,
            lingerAfterWriterArtifact = true,
        )
        val terminal = assertIs<EvoMoliorEvent.RunCompletedProvisional>(result.terminal)
        assertEquals(
            ExecutionCompletionStatus.COMPLETED,
            terminal.terminalOutcome?.executionStatus,
        )
        assertEquals(content.trim(), result.archiveEntries["outputs/recovered/report.md"]?.trim())
        assertFalse(result.events.any { it is EvoMoliorEvent.RunAborted })
    }

    @Test
    fun `non ARC candidate survives withheld acceptance without another paid writer`() =
        runBlocking {
            val content = "# Receiver report\n\nPRODUCED_BY_MAIN_WRITER\n"
            val result = runTopology(
                groupCount = 1,
                isTestRun = true,
                runId = "general-main-writer-provisional",
                emitMainWriterArtifact = true,
                requestedOutputSpec = FinalOutputSpec(
                    relativePath = "report.md",
                    mediaType = "text/markdown",
                    validation = FinalOutputValidationContract(
                        requiredMarkdownSectionSignals = listOf("Receiver report"),
                    ),
                ),
                writerContent = content,
            )
            val terminal = assertIs<EvoMoliorEvent.RunCompletedProvisional>(result.terminal)
            val outcome = assertNotNull(terminal.terminalOutcome)
            assertEquals(ExecutionCompletionStatus.COMPLETED, outcome.executionStatus)
            assertEquals(AcceptanceStatus.PROVISIONAL, outcome.acceptanceStatus)
            assertEquals(null, outcome.acceptedWinnerId)
            assertEquals(null, outcome.writerSourceAuthority)
            assertTrue(outcome.failedGateIds.any { it.startsWith("strict-acceptance-") })
            assertFalse(result.events.any { it is EvoMoliorEvent.RunCompleted })
            assertEquals(
                content.trim(),
                assertNotNull(result.archiveEntries["outputs/recovered/report.md"]).trim(),
            )
            val progress = result.events.filterIsInstance<EvoMoliorEvent.EngineProgress>()
            assertEquals(1, progress.count { it.phase == "main_writer_spawn" })
            assertFalse(progress.any { it.phase == "recovery_deliverable_spawn" })
            assertFalse(progress.any { it.phase == "final_deliverable_spawn" })
            assertTrue(result.artifactWorkspaces.isEmpty())
            assertTrue(
                result.archiveEntries["recovery/recovered-run-v2.json"]
                    ?.contains(RecoveredDeliverableSource.STAGED_WRITER_OUTPUT.name) == true,
            )
        }

    @Test
    fun `cancelling candidate wait stops service scope writer without publishing an outcome`() =
        runBlocking {
            val result = runTopology(
                groupCount = 1,
                isTestRun = true,
                runId = "general-main-writer-cancelled",
                emitMainWriterArtifact = true,
                requestedOutputSpec = FinalOutputSpec(
                    relativePath = "report.md",
                    mediaType = "text/markdown",
                ),
                writerContent = "# Must not be published",
                cancelDuringWriter = true,
            )
            assertEquals(null, result.terminal)
            assertFalse(
                result.events.any {
                    it is EvoMoliorEvent.RunCompleted ||
                        it is EvoMoliorEvent.RunCompletedProvisional ||
                        it is EvoMoliorEvent.RunRecovered
                },
            )
            val progress = result.events.filterIsInstance<EvoMoliorEvent.EngineProgress>()
            assertEquals(1, progress.count { it.phase == "main_writer_spawn" })
            assertFalse(progress.any { it.phase == "recovery_deliverable_spawn" })
            assertTrue(result.archiveEntries.isEmpty())
        }

    @Test
    fun `resolved HTML output is authored before acceptance and reused provisionally`() =
        runBlocking {
            val result = runTopology(
                groupCount = 1,
                isTestRun = true,
                runId = "resolved-html-main-writer-provisional",
                emitMainWriterArtifact = true,
                requestedOutputSpec = null,
                writerContent =
                    "<!doctype html><html><head><title>Report</title></head>" +
                        "<body><h1>PRODUCED_BY_MAIN_WRITER</h1></body></html>",
                objective = "Produce a standalone HTML article.",
            )
            val terminal = assertIs<EvoMoliorEvent.RunCompletedProvisional>(result.terminal)
            assertEquals(AcceptanceStatus.PROVISIONAL, terminal.terminalOutcome?.acceptanceStatus)
            assertEquals(null, terminal.terminalOutcome?.acceptedWinnerId)
            assertTrue(
                assertNotNull(result.archiveEntries["outputs/recovered/final-article.html"])
                    .contains("PRODUCED_BY_MAIN_WRITER"),
            )
            val progress = result.events.filterIsInstance<EvoMoliorEvent.EngineProgress>()
            assertEquals(1, progress.count { it.phase == "main_writer_spawn" })
            assertFalse(progress.any { it.phase == "recovery_deliverable_spawn" })
            assertFalse(result.events.any { it is EvoMoliorEvent.RunCompleted })
        }

    @Test
    fun `invalid generic candidate exhausts bounded fallback without claiming deliverable success`() =
        runBlocking {
            val result = runTopology(
                groupCount = 1,
                isTestRun = true,
                runId = "general-main-writer-invalid",
                emitMainWriterArtifact = true,
                requestedOutputSpec = FinalOutputSpec(
                    relativePath = "report.json",
                    mediaType = "application/json",
                    validation = FinalOutputValidationContract(
                        requiredJsonRootKeys = listOf("required-result"),
                    ),
                ),
                writerContent = """{"wrong-result":"PRODUCED_BY_MAIN_WRITER"}""",
            )
            val terminal = assertIs<EvoMoliorEvent.RunRecovered>(result.terminal)
            val outcome = assertNotNull(terminal.terminalOutcome)
            assertEquals(ExecutionCompletionStatus.FAILED_UNWRITABLE_OUTPUT, outcome.executionStatus)
            assertEquals(AcceptanceStatus.NOT_EVALUATED, outcome.acceptanceStatus)
            assertEquals(null, outcome.acceptedWinnerId)
            assertEquals(null, outcome.primaryArtifactRef)
            assertFalse(result.archiveEntries.containsKey("outputs/recovered/report.json"))
            assertFalse(result.events.any { it is EvoMoliorEvent.RunCompleted })
            val progress = result.events.filterIsInstance<EvoMoliorEvent.EngineProgress>()
            assertEquals(1, progress.count { it.phase == "main_writer_spawn" })
            assertTrue(progress.count { it.phase == "recovery_deliverable_spawn" } in 1..2)
            assertFalse(progress.any { it.phase == "final_deliverable_spawn" })
        }

    @Test
    fun `Gate C one group and two rounds complete with provisional ARC artifact authority`() =
        runBlocking {
            val result = runTopology(
                groupCount = 1,
                isTestRun = true,
                runId = "gate-c-arc-one-group-two-rounds",
            )
            assertQualification(result, expectedGroups = 1)
        }

    @Test
    fun `Gate D three groups and two rounds complete with provisional ARC artifact authority`() =
        runBlocking {
            val result = runTopology(
                groupCount = 3,
                isTestRun = false,
                runId = "gate-d-arc-three-groups-two-rounds",
            )
            assertQualification(result, expectedGroups = 3)
        }

    @Test
    fun `provider-free ARC run archives deliverable produced by main writer path`() =
        runBlocking {
            val result = runTopology(
                groupCount = 1,
                isTestRun = true,
                runId = "arc-main-writer-production-path",
                emitMainWriterArtifact = true,
            )
            assertQualification(result, expectedGroups = 1)

            val terminal = assertIs<EvoMoliorEvent.RunCompletedProvisional>(result.terminal)
            val outcome = assertNotNull(terminal.terminalOutcome)
            val writerAuthority = assertNotNull(outcome.writerSourceAuthority)
            assertEquals(
                WriterSourceAuthorityClass.BENCHMARKED_ARTIFACT,
                writerAuthority.authorityClass,
            )
            assertTrue(writerAuthority.sourceSnapshotIds.isNotEmpty())
            assertTrue(
                result.events.any {
                    it is EvoMoliorEvent.EngineProgress &&
                        it.runId == "arc-main-writer-production-path" &&
                        it.phase == "main_writer_spawn"
                },
            )
            assertFalse(
                result.events.any {
                    it is EvoMoliorEvent.EngineProgress &&
                        it.runId == "arc-main-writer-production-path" &&
                        it.phase == "recovery_deliverable_spawn"
                },
                "A valid main-writer artifact must not be replaced by a recovery writer.",
            )

            val archivedNotebook = assertNotNull(
                result.archiveEntries["outputs/recovered/submission.ipynb"],
            )
            val archivedNotebookHash = com.achulkov.evomolior.core.atlas.AtlasHashing
                .hashText(archivedNotebook)
            assertEquals(
                outcome.primaryArtifactRef?.artifactHash,
                archivedNotebookHash,
            )
            assertTrue(
                archivedNotebook.contains("PRODUCED_BY_MAIN_WRITER"),
                archivedNotebook.take(600),
            )
            assertTrue(
                result.archiveEntries["recovery/recovered-run-v2.json"]
                    ?.contains(RecoveredDeliverableSource.STAGED_WRITER_OUTPUT.name) == true,
            )

            val scaffoldHash = result.artifactWorkspaces
                .single()
                .resolve("state/snapshots")
                .listFiles()
                .orEmpty()
                .map { file -> Json.decodeFromString(ArtifactSnapshot.serializer(), file.readText()) }
                .single { it.round == 0 }
                .artifactHash
            assertNotEquals(
                scaffoldHash,
                archivedNotebookHash,
                "The archived notebook must be authored by the main writer, not the scaffold.",
            )
        }

    private suspend fun runTopology(
        groupCount: Int,
        isTestRun: Boolean,
        runId: String,
        emitMainWriterArtifact: Boolean = false,
        requestedOutputSpec: FinalOutputSpec? = FinalOutputSpec(
            relativePath = "submission.ipynb",
            mediaType = "application/x-ipynb+json",
            validation = ArcAgi2NotebookQualificationAuthority.notebookValidationContract,
        ),
        writerContent: String? = null,
        cancelDuringWriter: Boolean = false,
        lingerAfterWriterArtifact: Boolean = false,
        objective: String = if (writerContent == null) {
            "Provider-free ARC receiver topology qualification"
        } else {
            "Produce a receiver report from the available group evidence."
        },
    ): QualificationResult = coroutineScope {
        val root = createTempDirectory("$runId-").toFile()
        val substrateFactory = SubstrateDatabaseFactory(root)
        val events = ConcurrentLinkedQueue<EvoMoliorEvent>()
        val workerGroupKeys = (0 until groupCount).map { "group-$it" }
        val profiles = listOf(
            GroupProfileType.Library,
            GroupProfileType.Factory,
            GroupProfileType.Commune,
        ).take(groupCount)
        val providerGroupOrdinals = mutableMapOf<String, Int>()
        val providerGroupOrdinalLock = Any()
        val writerStarted = CompletableDeferred<Unit>()
        val writerStopped = CompletableDeferred<Unit>()
        val lifecycleManagers = ConcurrentLinkedQueue<LifecycleManager>()
        val service = EvoMoliorRunService(
            lifecycleManagerProvider = { activeRunId, groupId, _, _, groupProfile ->
                LifecycleManager(
                    runId = activeRunId,
                    groupId = groupId,
                    shuruVmManager = ShuruVmManager(),
                    substrateDatabaseFactory = substrateFactory,
                    agentJarPath = "/fake/agent.jar",
                    openRouterApiKey = "sk-provider-free-fixture",
                    groupProfile = groupProfile,
                    localAgentRunner = { bootContext, _ ->
                        if (
                            emitMainWriterArtifact &&
                            bootContext.role == com.achulkov.evomolior.core.model.Role.Builder &&
                            bootContext.rolePrompt.contains(
                                "Final Deliverable Writer",
                                ignoreCase = true,
                            )
                        ) {
                            if (cancelDuringWriter) {
                                writerStarted.complete(Unit)
                                try {
                                    awaitCancellation()
                                } finally {
                                    writerStopped.complete(Unit)
                                }
                            }
                            val now = System.currentTimeMillis()
                            val content = writerContent ?: ArcAgi2NotebookRecoveryFallback
                                .build(requireNotNull(requestedOutputSpec), bootContext.groupId)
                                .content
                                .replace(
                                    "SOURCE_HASHES = {}",
                                    "SOURCE_HASHES = {'main_writer_path': 'PRODUCED_BY_MAIN_WRITER'}",
                                )
                            substrateFactory.get(
                                bootContext.runId,
                                bootContext.groupId,
                            ).stagingDao().insert(
                                StagingSubmissionEntity(
                                    id = "arc-main-writer-${bootContext.agentId}-$now",
                                    agentId = bootContext.agentId,
                                    role = bootContext.role.name,
                                    type = SubmissionType.DOC.name,
                                    content = content,
                                    budgetSignature = "arc-main-writer-production-fixture",
                                    status = "pending",
                                    generation = bootContext.generation,
                                    groupId = bootContext.groupId,
                                    runId = bootContext.runId,
                                    submittedAtMs = now,
                                ),
                            )
                            if (lingerAfterWriterArtifact) {
                                try {
                                    awaitCancellation()
                                } finally {
                                    withContext(NonCancellable) { delay(100) }
                                }
                            }
                        }
                    },
                ).also(lifecycleManagers::add)
            },
            groupCoordinatorProvider = { groupIds, dimensions ->
                GroupCoordinator(groupIds, dimensions)
            },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = substrateFactory,
            genesisCouncilRunner = if (isTestRun) {
                null
            } else {
                {
                        callbackRunId,
                        callbackObjective,
                        _,
                        envelope,
                        inputArtifacts,
                        _,
                        onInvocationStarted,
                    ->
                    onInvocationStarted()
                    compiledCouncilTestFixture(
                        runId = callbackRunId,
                        objective = callbackObjective,
                        envelope = envelope,
                        inputArtifacts = inputArtifacts,
                        workerGroupKeys = workerGroupKeys,
                    )
                }
            },
            roundDurationMillisOverride = 1_000L,
            roundHeartbeatIntervalMs = 1L,
            inspectorCheckIntervalMs = 1L,
            arcVisibleLooRowsProvider = { _, groupId, _, _ ->
                val ordinal = synchronized(providerGroupOrdinalLock) {
                    providerGroupOrdinals.getOrPut(groupId) {
                        providerGroupOrdinals.size
                    }
                }
                val (top1, anyTwo) = benchmarkScore(groupCount, ordinal)
                ArcVisibleLooFixtureFactory.rows(
                    top1CorrectRows = top1,
                    anyTwoCorrectRows = anyTwo,
                )
            },
        )

        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            service.events.collect(events::add)
        }
        val terminal = if (cancelDuringWriter) null else async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(45_000L) {
                service.events.first { event ->
                    event.runId() == runId &&
                        (event is EvoMoliorEvent.RunCompletedProvisional ||
                            event is EvoMoliorEvent.RunRecovered ||
                            event is EvoMoliorEvent.RunCompleted ||
                            event is EvoMoliorEvent.RunAborted)
                }
            }
        }
        service.startRun(
            RunConfig(
                runId = runId,
                objective = objective,
                groupCount = groupCount,
                groupProfiles = profiles,
                rounds = 2,
                roundDurationMinutes = 0,
                maxAgentsPerGroup = 1,
                scoringDimensions = listOf(
                    ScoringDimension(
                        id = "quality",
                        name = "Quality",
                        description = "Deterministic topology fixture quality.",
                        weight = 1.0,
                    )
                ),
                openRouterApiKey = "sk-provider-free-fixture",
                finalOutputSpec = requestedOutputSpec,
                isTestRun = isTestRun,
                budgetOverride = 200.0.takeIf { isTestRun },
            )
        )
        val terminalEvent = if (cancelDuringWriter) {
            withTimeout(15_000L) {
                writerStarted.await()
                val runLoopJob = service.javaClass.getDeclaredField("activeRunLoopJob")
                    .apply { isAccessible = true }
                    .get(service) as Job
                runLoopJob.cancelAndJoin()
                writerStopped.await()
            }
            assertTrue(lifecycleManagers.all { it.getLiveAgentCount() == 0 })
            assertEquals(
                null,
                service.javaClass.getDeclaredField("activeRunId")
                    .apply { isAccessible = true }
                    .get(service),
            )
            null
        } else {
            requireNotNull(terminal).await()
        }
        collector.cancelAndJoin()

        /*
         * AgentSpawned is emitted from a service-scope callback. A provider-free in-process agent
         * can finish before that callback is scheduled, so the synchronous spawn progress is the
         * authoritative topology observation for this fixture.
         */
        val artifactOwnerGroups = events
            .filterIsInstance<EvoMoliorEvent.EngineProgress>()
            .filter { it.runId == runId && it.phase == "spawning" }
            .filter { it.message.contains("Spawning") && it.message.contains("×Builder") }
            .mapNotNull { progress ->
                Regex("""^\[([^\]]+)] Spawning""")
                    .find(progress.message)
                    ?.groupValues
                    ?.getOrNull(1)
            }
            .toSet()
        val artifactWorkspaces = File(root, "evomolior/runs/$runId/groups")
            .listFiles()
            .orEmpty()
            .map { groupDirectory -> File(groupDirectory, "artifact-workspace") }
            .filter(File::isDirectory)
        val artifactOwnerEvidenceGroups = artifactWorkspaces
            .map { requireNotNull(it.parentFile).name }
            .filter { groupId ->
                WorkerExecutionRepository(
                    substrateFactory.get(runId, groupId)
                ).artifactOwnerExecutionEvidence(runId, groupId).isNotEmpty()
            }
            .toSet()
        val archiveBytes = if (emitMainWriterArtifact && !cancelDuringWriter) {
            requireNotNull(service.getResultsBundle(runId)).also { bytes ->
                File(root, "results.zip").writeBytes(bytes)
            }
        } else {
            null
        }
        val archiveEntries = archiveBytes?.let(::unzipEntries).orEmpty()
        QualificationResult(
            events = events.toList(),
            terminal = terminalEvent,
            artifactOwnerGroups = artifactOwnerGroups,
            artifactWorkspaces = artifactWorkspaces,
            artifactOwnerEvidenceGroups = artifactOwnerEvidenceGroups,
            archiveEntries = archiveEntries,
        )
    }

    private fun assertQualification(
        result: QualificationResult,
        expectedGroups: Int,
    ) {
        assertEquals(
            expectedGroups,
            result.artifactOwnerGroups.size,
            result.events.joinToString("\n") { event -> event.toString() },
        )
        assertEquals(expectedGroups, result.artifactWorkspaces.size)
        assertEquals(expectedGroups, result.artifactOwnerEvidenceGroups.size)
        assertTrue(
            result.artifactWorkspaces.all { workspace ->
                val state = File(workspace, "state/artifact-workspace-state.json")
                val snapshots = File(workspace, "state/snapshots")
                val generated = File(workspace, "generated/snapshots")
                state.isFile &&
                    state.readText().contains("\"bestSnapshotId\":\"") &&
                    snapshots.listFiles().orEmpty().isNotEmpty() &&
                    generated.listFiles().orEmpty().isNotEmpty()
            },
        )
        assertTrue(
            result.events.any {
                it is EvoMoliorEvent.EngineProgress &&
                    it.phase == "artifact_scaffold_ready" &&
                    it.message.contains("Prepared $expectedGroups host-owned ARC artifact")
            },
        )
        assertEquals(
            setOf(0, 1),
            result.events
                .filterIsInstance<EvoMoliorEvent.RoundStarted>()
                .mapTo(linkedSetOf()) { it.roundIndex },
        )
        assertTrue(
            result.events.any {
                it is EvoMoliorEvent.EngineProgress &&
                    it.phase == "main_writer_spawn"
            },
        )
        val terminal = assertIs<EvoMoliorEvent.RunCompletedProvisional>(result.terminal)
        val outputPaths = terminal.outputPaths
        assertTrue(outputPaths.any { it.endsWith("submission.ipynb") })
        val outcome = requireNotNull(terminal.terminalOutcome)
        assertEquals(ExecutionCompletionStatus.COMPLETED, outcome.executionStatus)
        assertEquals(AcceptanceStatus.PROVISIONAL, outcome.acceptanceStatus)
        assertEquals(
            PrimaryArtifactValidationStatus.NOT_EVALUATED,
            outcome.primaryArtifactValidationStatus,
        )
        assertEquals(null, outcome.acceptedWinnerId)
        val writerSource = assertNotNull(outcome.writerSourceAuthority)
        assertTrue(writerSource.primaryGroupId?.isNotBlank() == true)
        assertTrue(writerSource.donorArtifactRefs.isNotEmpty())
        assertTrue(writerSource.hasValidHash())
        assertTrue(outcome.primaryArtifactRef?.artifactHash?.matches(Regex("[0-9a-f]{64}")) == true)
        val snapshotsByGroup = result.artifactWorkspaces.associate { workspace ->
            requireNotNull(workspace.parentFile).name to File(workspace, "state/snapshots")
                .listFiles()
                .orEmpty()
                .filter(File::isFile)
                .map { file ->
                    Json.decodeFromString(ArtifactSnapshot.serializer(), file.readText())
                }
                .sortedWith(compareBy<ArtifactSnapshot> { it.round }.thenBy { it.snapshotId })
        }
        val snapshots = snapshotsByGroup.values.flatten()
        val roundOneSnapshots = snapshots.filter { it.round == 1 }
        assertEquals(expectedGroups, roundOneSnapshots.size)
        assertTrue(
            snapshots.any {
                it.round >= 1 && it.benchmarkRowCount == 358
            },
            "Round/final snapshots must carry exact visible-LOO facts.",
        )
        roundOneSnapshots.forEach { snapshot ->
            assertPersistedBenchmarkEvidence(
                workspace = requireNotNull(result.artifactWorkspaces.singleOrNull {
                    requireNotNull(it.parentFile).name == snapshot.groupId
                }),
                snapshot = snapshot,
            )
        }
        assertEquals(
            1,
            snapshots.mapNotNull { it.backboneHash }.distinct().size,
            "All groups must share one common ARC artifact backbone.",
        )
        assertEquals(
            expectedGroups,
            snapshots.mapNotNull { it.branchHash }.distinct().size,
            "Each group must retain a distinct solver-family branch.",
        )
        val sourceGroupId = requireNotNull(writerSource.primaryGroupId)
        if (writerSource.sourceSnapshotIds.isNotEmpty()) {
            val sourceSnapshot = writerSource.sourceSnapshotIds
                .mapNotNull { snapshotId ->
                    snapshotsByGroup[sourceGroupId]?.singleOrNull { it.snapshotId == snapshotId }
                }
                .singleOrNull()
            val selectedSourceSnapshot = assertNotNull(
                sourceSnapshot,
                "Terminal writer source must reference one persisted artifact snapshot.",
            )
            assertEquals(sourceGroupId, selectedSourceSnapshot.groupId)
            assertTrue(selectedSourceSnapshot.backboneHash != null)
            assertTrue(selectedSourceSnapshot.branchHash != null)
        } else {
            assertEquals(WriterSourceAuthorityClass.SCAFFOLD_ONLY, writerSource.authorityClass)
        }
        assertTrue(writerSource.donorArtifactRefs.contains("primary/submission.ipynb"))
        assertTrue(
            writerSource.authorityClass != WriterSourceAuthorityClass.ACCEPTED_WINNER,
            "Provider-free qualification must not emit accepted writer authority.",
        )

        if (expectedGroups == 3) {
            val comparativeLeaderGroupId = roundOneSnapshots
                .minWith(compareBy<ArtifactSnapshot> { it.top1 ?: Int.MAX_VALUE }
                    .thenBy { it.anyTwo ?: Int.MAX_VALUE }
                    .thenBy { it.groupId })
                .groupId
            val benchmarkCandidates = roundOneSnapshots.map { snapshot ->
                WriterSourceCandidate(
                    groupId = snapshot.groupId,
                    snapshot = snapshot,
                    artifactRef = ArtifactRef(
                        artifactId = "arc-${snapshot.groupId}",
                        relativePath = "primary/submission.ipynb",
                        mediaType = "application/x-ipynb+json",
                        artifactHash = snapshot.artifactHash,
                    ),
                    comparativeScore =
                        if (snapshot.groupId == comparativeLeaderGroupId) 0.99 else 0.10,
                )
            }
            val benchmarkAwareAuthority = WriterSourceResolver.resolve(
                benchmarkedCandidates = benchmarkCandidates,
                finalComparativeLeader =
                    benchmarkCandidates.single { it.groupId == comparativeLeaderGroupId },
                scaffold = WriterScaffoldSource(
                    primaryGroupId = comparativeLeaderGroupId,
                    artifactRef = ArtifactRef(
                        artifactId = "arc-scaffold",
                        relativePath = "primary/submission.ipynb",
                        mediaType = "application/x-ipynb+json",
                        artifactHash = "f".repeat(64),
                    ),
                ),
            )
            assertEquals(
                WriterSourceAuthorityClass.BENCHMARKED_ARTIFACT,
                benchmarkAwareAuthority.authorityClass,
            )
            val benchmarkWinner = roundOneSnapshots.maxWith(
                compareBy<ArtifactSnapshot> { it.top1 ?: Int.MIN_VALUE }
                    .thenBy { it.anyTwo ?: Int.MIN_VALUE }
                    .thenByDescending { it.groupId },
            )
            assertEquals(benchmarkWinner.groupId, benchmarkAwareAuthority.primaryGroupId)
            assertEquals(
                listOf(benchmarkWinner.snapshotId),
                benchmarkAwareAuthority.sourceSnapshotIds,
            )
            assertTrue(benchmarkAwareAuthority.hasValidHash())
        }
    }

    private fun assertPersistedBenchmarkEvidence(
        workspace: File,
        snapshot: ArtifactSnapshot,
    ) {
        val reportFile = File(
            workspace,
            "generated/reports/${snapshot.snapshotId}-benchmark.json",
        )
        assertTrue(reportFile.isFile, "Missing persisted benchmark report: ${reportFile.path}")
        val reportBytes = reportFile.readBytes()
        val report = Json.decodeFromString(
            ArcVisibleLooReport.serializer(),
            reportBytes.decodeToString(),
        )
        assertTrue(report.hasValidHash())
        assertContentEquals(report.canonicalBytes(), reportBytes)
        assertEquals(358, report.rowCount)
        assertEquals(snapshot.artifactHash, report.sourceArtifactHash)
        assertEquals("${snapshot.groupId}-r1", report.candidateId)
        assertEquals(
            ArcVisibleLooBenchmarkDefinition.fixture().challengeDefinitionHash,
            report.challengeDefinitionHash,
        )
        assertEquals(snapshot.top1, report.top1Correct)
        assertEquals(snapshot.anyTwo, report.anyTwoCorrect)
        assertEquals(snapshot.benchmarkReportHash, ArcVisibleLooHashing.hashBytes(reportBytes))
        assertEquals(snapshot.benchmarkReportBytesHash, report.canonicalBytesHash())
        assertEquals(snapshot.benchmarkRepeatedBytesHash, report.canonicalBytesHash())
        val csvFile = File(
            workspace,
            "generated/reports/${snapshot.snapshotId}-benchmark.csv",
        )
        assertTrue(csvFile.isFile, "Missing persisted benchmark CSV: ${csvFile.path}")
        assertEquals(
            snapshot.benchmarkCsvBytesHash,
            ArcVisibleLooHashing.hashBytes(csvFile.readBytes()),
        )
        assertContentEquals(report.canonicalCsvBytes(), csvFile.readBytes())
        assertTrue(snapshot.benchmarkRepeatable == true)
        assertEquals(
            listOf(
                ArcVisibleLooFamilyProvenance("family-alternate", 358),
                ArcVisibleLooFamilyProvenance("family-top1", 358),
            ),
            report.candidateFamilyProvenance,
        )
        assertEquals(
            snapshot.benchmarkCandidateFamilyIds,
            report.candidateFamilyProvenance.map { it.familyId },
        )
        assertTrue(report.repeatability.providerFree)
        assertEquals(2, report.repeatability.repeatCount)
        assertTrue(report.repeatability.canonicalBytesEqual)
        assertTrue(report.repeatability.canonicalHashEqual)
    }

    private fun benchmarkScore(groupCount: Int, ordinal: Int): Pair<Int, Int> =
        if (groupCount == 3) {
            when (ordinal) {
                0 -> 1 to 2
                1 -> 3 to 4
                2 -> 2 to 3
                else -> error("Unexpected Gate-D provider group ordinal $ordinal")
            }
        } else {
            1 to 2
        }

    private fun EvoMoliorEvent.runId(): String = when (this) {
        is EvoMoliorEvent.RunStarted -> runId
        is EvoMoliorEvent.RoundStarted -> runId
        is EvoMoliorEvent.RoundCompleted -> runId
        is EvoMoliorEvent.RunCompleted -> runId
        is EvoMoliorEvent.RunCompletedProvisional -> runId
        is EvoMoliorEvent.RunRecovered -> runId
        is EvoMoliorEvent.RunAborted -> runId
        is EvoMoliorEvent.AgentSpawned -> runId
        is EvoMoliorEvent.AgentDied -> runId
        is EvoMoliorEvent.UsefulExitRateUpdated -> runId
        is EvoMoliorEvent.AgentMessage -> runId
        is EvoMoliorEvent.GroupBudgetChanged -> runId
        is EvoMoliorEvent.ResourceNodeClaimed -> runId
        is EvoMoliorEvent.EngineProgress -> runId
        else -> ""
    }

    private data class QualificationResult(
        val events: List<EvoMoliorEvent>,
        val terminal: EvoMoliorEvent?,
        val artifactOwnerGroups: Set<String>,
        val artifactWorkspaces: List<File>,
        val artifactOwnerEvidenceGroups: Set<String>,
        val archiveEntries: Map<String, String>,
    )

    private fun unzipEntries(bytes: ByteArray): Map<String, String> {
        val entries = linkedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entry ->
                entries[entry.name] = zip.readBytes().decodeToString()
                zip.closeEntry()
            }
        }
        return entries
    }
}
