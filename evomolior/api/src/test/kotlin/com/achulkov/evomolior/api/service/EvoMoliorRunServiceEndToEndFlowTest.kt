@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.api.events.EvoMoliorEvent
import com.achulkov.evomolior.api.grader.GraderRunner
import com.achulkov.evomolior.api.model.FinalOutputKind
import com.achulkov.evomolior.api.model.FinalOutputSpec
import com.achulkov.evomolior.api.model.RunConfig
import com.achulkov.evomolior.api.model.RunInputArtifactUpload
import com.achulkov.evomolior.agents.factory.StructuredDimensionScoreDraft
import com.achulkov.evomolior.agents.factory.StructuredDimensionScoreRepairDraft
import com.achulkov.evomolior.agents.factory.StructuredNodeGradeDraft
import com.achulkov.evomolior.agents.factory.StructuredNodeGradeVerdict
import com.achulkov.evomolior.competition.coordinator.GroupCoordinator
import com.achulkov.evomolior.competition.grader.GraderGroupManager
import com.achulkov.evomolior.competition.grader.GraderNodeLeaseAuthority
import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.NodeScoreVector
import com.achulkov.evomolior.core.contract.AgentBootContext
import com.achulkov.evomolior.core.contract.ContributionAuthorityKind
import com.achulkov.evomolior.core.contract.ContributionAuthorityRef
import com.achulkov.evomolior.core.contract.CriticDecisionRecord
import com.achulkov.evomolior.core.contract.CriticOutcome
import com.achulkov.evomolior.core.contract.CriticReview
import com.achulkov.evomolior.core.contract.GRADER_METHODOLOGY_DRAFT_SIGNATURE_PREFIX
import com.achulkov.evomolior.core.contract.GraderAutomatedTestDraft
import com.achulkov.evomolior.core.contract.GraderFailureModeDraft
import com.achulkov.evomolior.core.contract.GraderMethodologyDraft
import com.achulkov.evomolior.core.contract.GraderRequirementDraft
import com.achulkov.evomolior.core.contract.GraderRubricDraft
import com.achulkov.evomolior.core.contract.PublicationState
import com.achulkov.evomolior.core.contract.StartingField
import com.achulkov.evomolior.core.config.AcceptanceProfile
import com.achulkov.evomolior.core.config.CouncilExecutionPurpose
import com.achulkov.evomolior.core.config.LlmBackend
import com.achulkov.evomolior.core.config.ModelSelectionConfig
import com.achulkov.evomolior.core.grader.DisclosureSensitivity
import com.achulkov.evomolior.core.grader.FailureLevel
import com.achulkov.evomolior.core.model.DimensionScore
import com.achulkov.evomolior.core.model.GroupProfileType
import com.achulkov.evomolior.core.model.Role
import com.achulkov.evomolior.core.model.ScoringDimension
import com.achulkov.evomolior.orchestration.metaplanner.MetaPlannerRunner
import com.achulkov.evomolior.orchestration.lifecycle.LifecycleManager
import com.achulkov.evomolior.sandbox.shuru.ShuruVmManager
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.db.entity.KnowledgeBaseEntryEntity
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import com.achulkov.evomolior.substrate.grader.GraderAuthorityRepository
import com.achulkov.evomolior.substrate.publication.PublicationStateRepository
import com.achulkov.evomolior.substrate.worker.ContributionGraphRepository
import com.achulkov.evomolior.substrate.worker.WorkerExecutionRepository
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.jvm.isAccessible
import kotlin.time.Instant

/**
 * End-to-end regression for the user-facing run lifecycle.
 *
 * Proves that callers can start a run, observe the live event stream, wait for completion,
 * and query the finished run's state and archive summary.
 */
class EvoMoliorRunServiceEndToEndFlowTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `multi round run replenishes generation budget and spawns workers after round zero`() = runBlocking {
        val tmpDir = createTempDirectory("svc-multi-round-spawn-budget-test-").toFile()
        val substrateFactory = SubstrateDatabaseFactory(tmpDir)
        val runId = "run-multi-round-spawn-budget"
        val collectedEvents = ConcurrentLinkedQueue<EvoMoliorEvent>()

        val service = EvoMoliorRunService(
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
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = substrateFactory,
            metaPlannerRunner = MetaPlannerRunner(),
            roundHeartbeatIntervalMs = 1L,
            inspectorCheckIntervalMs = 1L
        )

        val eventCollector = launch(start = CoroutineStart.UNDISPATCHED) {
            service.events.collect { event ->
                collectedEvents += event
            }
        }

        service.startRun(
            RunConfig(
                runId = runId,
                objective = "multi-round worker spawn regression",
                groupCount = 2,
                groupProfiles = listOf(
                    GroupProfileType.Library,
                    GroupProfileType.Factory
                ),
                rounds = 2,
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
                isTestRun = true
            )
        )

        withTimeout(10_000) {
            service.events.first { event ->
                event is EvoMoliorEvent.RunCompleted && event.runId == runId
            }
        }
        assertEquals(
            EngineStatus.IDLE,
            service.getState().status,
            "RunCompleted must not be observable until run-scoped cleanup has finished."
        )

        eventCollector.cancel()

        val workerGroupIds = collectedEvents
            .filterIsInstance<EvoMoliorEvent.AgentSpawned>()
            .filter { event -> event.runId == runId }
            .map(EvoMoliorEvent.AgentSpawned::groupId)
            .distinct()
        assertEquals(2, workerGroupIds.size)
        val actualWorkerRoundsByGroup = workerGroupIds.associateWith { groupId ->
            WorkerExecutionRepository(substrateFactory.get(runId, groupId))
                .registrations(runId, groupId)
                .filter { registration ->
                    Role.valueOf(registration.role) !in setOf(
                        Role.Outlier,
                        Role.Archivist,
                        Role.PerspectiveTaker
                    )
                }
                .mapTo(linkedSetOf()) { registration -> registration.generation }
        }
        assertTrue(
            actualWorkerRoundsByGroup.values.all { rounds -> rounds.containsAll(setOf(0, 1)) },
            "A two-round production run must persist actual worker registrations in both rounds: " +
                actualWorkerRoundsByGroup
        )
        assertTrue(
            collectedEvents.any {
                it is EvoMoliorEvent.EngineProgress &&
                    it.runId == runId &&
                    it.phase == "budget_replenished_for_generation"
            },
            "Round 1 should surface that depleted live telemetry was replaced by generation spawn budget"
        )
    }

    @Test
    fun `start run emits lifecycle events and exposes completion state and archive summary`() = runBlocking {
        val tmpDir = createTempDirectory("svc-end-to-end-flow-test-").toFile()
        val substrateFactory = SubstrateDatabaseFactory(tmpDir)
        val runId = "run-end-to-end"
        val collectedEvents = mutableListOf<EvoMoliorEvent>()

        val service = EvoMoliorRunService(
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
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = substrateFactory,
            metaPlannerRunner = MetaPlannerRunner(),
            roundHeartbeatIntervalMs = 1L,
            inspectorCheckIntervalMs = 1L
        )

        val eventCollector = launch {
            service.events.collect { event ->
                collectedEvents += event
            }
        }

        kotlinx.coroutines.yield()

        val uploadedContext = buildString {
            appendLine("This is the starting context document.")
            appendLine("See https://docs.example.com/guide and www.example.org/reference.")
            repeat(200) { index ->
                appendLine("Additional context line $index.")
            }
        }

        val returnedRunId = service.startRun(
            RunConfig(
                runId = runId,
                objective = "end-to-end lifecycle flow",
                groupCount = 2,
                groupProfiles = listOf(
                    GroupProfileType.Library,
                    GroupProfileType.Factory
                ),
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
                contextArtifacts = listOf(
                    RunInputArtifactUpload(
                        relativePath = "docs/context.txt",
                        sourceName = "context.txt",
                        mimeType = "text/plain",
                        contentBase64 = Base64.getEncoder().encodeToString(uploadedContext.toByteArray())
                    )
                ),
                isTestRun = true
            )
        )

        assertEquals(runId, returnedRunId)

        withTimeout(10_000) {
            service.events.first { event ->
                event is EvoMoliorEvent.RunCompleted && event.runId == runId
            }
        }

        eventCollector.cancel()

        assertTrue(
            collectedEvents.any { it is EvoMoliorEvent.RunStarted && it.runId == runId },
            "The live event stream should include RunStarted"
        )
        assertTrue(
            collectedEvents.any { it is EvoMoliorEvent.RoundStarted && it.runId == runId && it.roundIndex == 0 },
            "The live event stream should include the first round"
        )
        assertTrue(
            collectedEvents.any { it is EvoMoliorEvent.RoundCompleted && it.runId == runId },
            "The live event stream should include the completed round result"
        )

        val state = service.getState()
        assertTrue(
            state.status.name == "COMPLETED" || state.status.name == "IDLE",
            "The finished run should report a terminal state"
        )
        assertTrue(
            state.activeRunId == runId || state.activeRunId == null,
            "The finished run should either still reference its runId briefly or have been cleaned up"
        )

        val metaArchive = service.getMetaArchiveSummary(runId)
        assertNotNull(metaArchive, "The completed run should expose a meta-archive summary")
        assertTrue(metaArchive.total > 0, "The completed run should persist at least one archive entry")
        assertTrue(
            metaArchive.byType.isNotEmpty(),
            "The archive summary should include at least one entry type"
        )

        val groupDb = substrateFactory.get(runId, "group-0")
        val uploadedDocs = groupDb.knowledgeBaseDao().queryByDomain(runId, "group-0", "uploaded_context", 10)
        assertTrue(uploadedDocs.isNotEmpty(), "Uploaded context docs should be visible to the group during the run")
        assertTrue(uploadedDocs.first().content.contains("context.txt"))
        assertTrue(uploadedDocs.first().content.contains("docs.example.com"))
        assertTrue(
            uploadedDocs.first().content.length < 1_200,
            "Uploaded context KB entry should stay compact and point agents to read_run_artifact for the full file"
        )

        val metaArchiveDb = substrateFactory.get(runId, "__meta_archive__")
        val startingFieldEntry = metaArchiveDb.metaArchiveDao().getByType("STARTING_FIELD")
            .first { it.runId == runId }
        val roundSummaryEntries = service.getMetaArchiveEntries(runId, "ROUND_SUMMARY")
        val runCompletedEntries = service.getMetaArchiveEntries(runId, RunArchiveService.ENTRY_RUN_COMPLETED)
        assertTrue(roundSummaryEntries.isNotEmpty(), "The completed run should expose round summary entries")
        assertTrue(runCompletedEntries.isNotEmpty(), "The completed run should expose a durable completion entry")
        val decodedStartingField = json.decodeFromString(StartingField.serializer(), startingFieldEntry.contentJson)
        assertEquals(1, decodedStartingField.inputArtifacts.size)
        assertEquals("docs/context.txt", decodedStartingField.inputArtifacts.single().relativePath)
        assertTrue(decodedStartingField.inputArtifacts.single().textPreview.orEmpty().contains("starting context document"))

        val decodedCompletion = json.decodeFromString(RunCompletionArchiveRecord.serializer(), runCompletedEntries.last().contentJson)
        assertEquals(runId, decodedCompletion.runId)
        assertTrue(decodedCompletion.winnerId != null)

        val bundle = service.getResultsBundle(runId)
        assertNotNull(bundle, "Completed run should expose a downloadable results bundle")
        val bundleEntries = unzipEntries(bundle)
        assertTrue(bundleEntries.keys.contains("inputs/docs/context.txt"))
        assertTrue(bundleEntries.keys.any { it.startsWith("outputs/round_summary") })
        assertTrue(bundleEntries["inputs/docs/context.txt"]!!.contains("This is the starting context document."))
    }

    @Test
    fun `prediction market run seeds inferred source hosts into persisted state and agent boot context`() = runBlocking {
        val tmpDir = createTempDirectory("svc-prediction-source-allowlist-test-").toFile()
        val substrateFactory = SubstrateDatabaseFactory(tmpDir)
        val runId = "run-prediction-source-allowlist"
        val capturedAllowedHosts = ConcurrentLinkedQueue<List<String>>()

        val service = EvoMoliorRunService(
            lifecycleManagerProvider = { runIdValue, groupIdValue, _, _, groupProfile ->
                LifecycleManager(
                    runId = runIdValue,
                    groupId = groupIdValue,
                    shuruVmManager = ShuruVmManager(),
                    substrateDatabaseFactory = substrateFactory,
                    agentJarPath = "/fake/agent.jar",
                    openRouterApiKey = "sk-test",
                    groupProfile = groupProfile,
                    localAgentRunner = { bootContext: AgentBootContext, _ ->
                        capturedAllowedHosts += bootContext.sourceAllowedHosts
                    }
                )
            },
            groupCoordinatorProvider = { groupIds, dimensions ->
                GroupCoordinator(groupIds, dimensions)
            },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = substrateFactory,
            metaPlannerRunner = MetaPlannerRunner(),
            roundHeartbeatIntervalMs = 1L,
            inspectorCheckIntervalMs = 1L
        )

        service.startRun(
            RunConfig(
                runId = runId,
                objective = "Find prediction-market edges across Kalshi, Polymarket, PredictIt, and Manifold without pre-seeded market data.",
                groupCount = 2,
                groupProfiles = listOf(
                    GroupProfileType.Library,
                    GroupProfileType.Factory
                ),
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
                isTestRun = true
            )
        )

        withTimeout(10_000) {
            while (capturedAllowedHosts.isEmpty() || PersistedRunStateStore.read(tmpDir, runId) == null) {
                kotlinx.coroutines.yield()
            }
        }

        val persistedState = assertNotNull(PersistedRunStateStore.read(tmpDir, runId))
        assertTrue("kalshi.com" in persistedState.allowedHosts)
        assertTrue("polymarket.com" in persistedState.allowedHosts)
        assertTrue("predictit.org" in persistedState.allowedHosts)
        assertTrue("manifold.markets" in persistedState.allowedHosts)
        assertTrue(
            capturedAllowedHosts.any { hosts ->
                "kalshi.com" in hosts &&
                    "polymarket.com" in hosts &&
                    "predictit.org" in hosts &&
                    "manifold.markets" in hosts
            },
            "Inferred prediction-market hosts should be carried into spawned agent boot contexts"
        )
        if (service.getState().activeRunId == runId) {
            runCatching {
                service.abortRun(runId, reason = "source allowlist regression captured boot context")
            }
        }
    }

    @Test
    fun `completion archive high level log names generated final output from completion record`() = runBlocking {
        val tmpDir = createTempDirectory("svc-completion-final-output-log-test-").toFile()
        val substrateFactory = SubstrateDatabaseFactory(tmpDir)
        val runId = "run-completion-final-output-log"
        val service = EvoMoliorRunService(
            lifecycleManagerProvider = { _, _, _, _, _ ->
                throw IllegalStateException("LifecycleManager should not be constructed in archive-completion test")
            },
            groupCoordinatorProvider = { groupIds, dimensions ->
                GroupCoordinator(groupIds, dimensions)
            },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = substrateFactory,
            metaPlannerRunner = MetaPlannerRunner(),
            archiveService = RunArchiveService(substrateFactory)
        )
        val outputSpec = FinalOutputSpec(
            relativePath = "final-article.html",
            mediaType = "text/html",
            description = "Operator-facing final report",
            kind = FinalOutputKind.HTML_ARTICLE
        )
        val config = RunConfig(
            runId = runId,
            objective = "Produce a final operator-facing report",
            groupCount = 1,
            groupProfiles = listOf(GroupProfileType.Library),
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
            finalOutputSpec = outputSpec,
            isTestRun = true
        )
        val completionRecord = RunCompletionRecord(
            runId = runId,
            winnerId = "group-0",
            finalAnswerSummary = "Generated report was written.",
            generatedOutputFiles = listOf(
                GeneratedOutputFile(
                    relativePath = outputSpec.relativePath,
                    mediaType = outputSpec.mediaType,
                    content = """
                        <!doctype html>
                        <html lang="en">
                        <head>
                          <meta charset="utf-8">
                          <meta name="viewport" content="width=device-width, initial-scale=1">
                          <title>Completion Path Final Report</title>
                        </head>
                        <body>
                          <main>
                            <article>
                              <h1>Completion Path Final Report</h1>
                              <section><h2>Executive Summary</h2><p>The generated output came from the completion record.</p></section>
                              <footer>Generated by test completion record.</footer>
                            </article>
                          </main>
                        </body>
                        </html>
                    """.trimIndent()
                )
            ),
            requestedOutputSpec = outputSpec
        )

        val persistRunCompletionMarker = service::class.declaredFunctions
            .first { it.name == "persistRunCompletionMarker" }
            .apply { isAccessible = true }
        persistRunCompletionMarker.callSuspend(service, completionRecord)

        val archiveCompletedRun = service::class.declaredFunctions
            .first { it.name == "archiveCompletedRun" }
            .apply { isAccessible = true }
        archiveCompletedRun.callSuspend(
            service,
            config,
            listOf("group-0"),
            "group-0",
            completionRecord
        )

        val bundleEntries = unzipEntries(assertNotNull(RunArchiveService(substrateFactory).buildResultsBundle(runId)))
        assertTrue(assertNotNull(bundleEntries["outputs/final-article.html"]).contains("Completion Path Final Report"))
        assertTrue(
            assertNotNull(bundleEntries["outputs/high-level-run-log.txt"])
                .contains("Final outputs: final-article.html")
        )
    }

    @Test
    fun `single round lifecycle emits summary completion and final answer in canonical order`() = runBlocking {
        val tmpDir = createTempDirectory("svc-event-order-flow-test-").toFile()
        val substrateFactory = SubstrateDatabaseFactory(tmpDir)
        val runId = "run-event-order"
        val collectedEvents = mutableListOf<EvoMoliorEvent>()

        val service = EvoMoliorRunService(
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
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = substrateFactory,
            metaPlannerRunner = MetaPlannerRunner(),
            roundHeartbeatIntervalMs = 1L,
            inspectorCheckIntervalMs = 1L
        )

        val eventCollector = launch {
            service.events.collect { event ->
                collectedEvents += event
            }
        }

        kotlinx.coroutines.yield()

        service.startRun(
            RunConfig(
                runId = runId,
                objective = "event ordering regression",
                groupCount = 2,
                groupProfiles = listOf(
                    GroupProfileType.Library,
                    GroupProfileType.Factory
                ),
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
                isTestRun = true
            )
        )

        withTimeout(10_000) {
            while (service.getMetaArchiveEntries(runId, RunArchiveService.ENTRY_RUN_COMPLETED).isEmpty()) {
                kotlinx.coroutines.yield()
            }
        }

        withTimeout(10_000) {
            while (collectedEvents.none { it is EvoMoliorEvent.RunCompleted && it.runId == runId }) {
                kotlinx.coroutines.yield()
            }
        }
        eventCollector.cancel()

        val roundStartedIndex = collectedEvents.indexOfFirst {
            it is EvoMoliorEvent.RoundStarted && it.runId == runId && it.roundIndex == 0
        }
        val roundSummaryIndex = collectedEvents.indexOfFirst {
            it is EvoMoliorEvent.EngineProgress && it.runId == runId && it.phase == "round_summary_result"
        }
        val roundCompletedIndex = collectedEvents.indexOfFirst {
            it is EvoMoliorEvent.RoundCompleted && it.runId == runId
        }
        val finalAnswerIndex = collectedEvents.indexOfFirst {
            it is EvoMoliorEvent.EngineProgress && it.runId == runId && it.phase == "final_answer"
        }
        val runCompletedIndex = collectedEvents.indexOfFirst {
            it is EvoMoliorEvent.RunCompleted && it.runId == runId
        }

        assertTrue(roundStartedIndex >= 0, "RoundStarted should be emitted")
        assertTrue(roundSummaryIndex >= 0, "round_summary_result should be emitted")
        assertTrue(roundCompletedIndex >= 0, "RoundCompleted should be emitted")
        assertTrue(finalAnswerIndex >= 0, "final_answer should be emitted")
        assertTrue(runCompletedIndex >= 0, "RunCompleted should be emitted")
        assertTrue(roundStartedIndex < roundSummaryIndex)
        assertTrue(roundSummaryIndex < roundCompletedIndex)
        assertTrue(roundCompletedIndex < finalAnswerIndex)
        assertTrue(finalAnswerIndex < runCompletedIndex)
        assertEquals(
            1,
            collectedEvents.count { it is EvoMoliorEvent.RoundCompleted && it.runId == runId },
            "One-round runs should publish exactly one RoundCompleted event"
        )
    }

    @Test
    fun `continuation run clones archived StartingField and merges new context artifacts`() = runBlocking {
        val tmpDir = createTempDirectory("svc-continuation-flow-test-").toFile()
        val substrateFactory = SubstrateDatabaseFactory(tmpDir)
        val firstRunId = "run-continuation-source"
        val secondRunId = "run-continuation-target"
        val collectedEvents = mutableListOf<EvoMoliorEvent>()

        val service = EvoMoliorRunService(
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
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = substrateFactory,
            metaPlannerRunner = MetaPlannerRunner(),
            roundHeartbeatIntervalMs = 1L,
            inspectorCheckIntervalMs = 1L
        )

        val eventCollector = launch {
            service.events.collect { event ->
                collectedEvents += event
            }
        }

        kotlinx.coroutines.yield()

        val sourceContext = "Source run context for continuation."
        val sourceRunIdReturned = service.startRun(
            RunConfig(
                runId = firstRunId,
                objective = "seed run objective",
                groupCount = 1,
                groupProfiles = listOf(GroupProfileType.Library),
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
                contextArtifacts = listOf(
                    RunInputArtifactUpload(
                        relativePath = "docs/source.txt",
                        sourceName = "source.txt",
                        mimeType = "text/plain",
                        contentBase64 = Base64.getEncoder().encodeToString(sourceContext.toByteArray())
                    )
                ),
                isTestRun = true
            )
        )

        assertEquals(firstRunId, sourceRunIdReturned)
        withTimeout(10_000) {
            service.events.first { event ->
                event is EvoMoliorEvent.RunCompleted && event.runId == firstRunId
            }
        }
        withTimeout(10_000) {
            while (service.getState().activeRunId != null) {
                kotlinx.coroutines.yield()
            }
        }
        withTimeout(30_000) {
            while (service.getMetaArchiveEntries(firstRunId, RunArchiveService.ENTRY_RUN_COMPLETED).isEmpty()) {
                kotlinx.coroutines.yield()
            }
        }

        val continuationContext = "Fresh continuation context."
        val continuationRunIdReturned = service.startRun(
            RunConfig(
                runId = secondRunId,
                objective = "continued objective",
                groupCount = 3,
                groupProfiles = listOf(
                    GroupProfileType.Factory,
                    GroupProfileType.Library,
                    GroupProfileType.Commune
                ),
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
                contextArtifacts = listOf(
                    RunInputArtifactUpload(
                        relativePath = "docs/continuation.txt",
                        sourceName = "continuation.txt",
                        mimeType = "text/plain",
                        contentBase64 = Base64.getEncoder().encodeToString(continuationContext.toByteArray())
                    )
                ),
                continuationFromRunId = firstRunId,
                isTestRun = true
            )
        )

        assertEquals(secondRunId, continuationRunIdReturned)
        withTimeout(10_000) {
            service.events.first { event ->
                event is EvoMoliorEvent.RunCompleted && event.runId == secondRunId
            }
        }

        eventCollector.cancel()

        assertTrue(
            collectedEvents.any { it is EvoMoliorEvent.EngineProgress && it.runId == secondRunId && it.phase == "continuation_start" },
            "Continuation should emit a progress marker"
        )

        val metaArchiveDb = substrateFactory.get(secondRunId, "__meta_archive__")
        val startingFieldEntry = metaArchiveDb.metaArchiveDao().getByType("STARTING_FIELD")
            .first { it.runId == secondRunId }
        val decodedStartingField = json.decodeFromString(StartingField.serializer(), startingFieldEntry.contentJson)

        assertEquals(firstRunId, decodedStartingField.continuedFromRunId)
        assertEquals("continued objective", decodedStartingField.objective)
        assertEquals(1, decodedStartingField.groupCount)
        assertEquals(GroupProfileType.Library, decodedStartingField.groupProfiles["group-0"])
        assertTrue(decodedStartingField.inputArtifacts.any { it.relativePath == "docs/source.txt" })
        assertTrue(decodedStartingField.inputArtifacts.any { it.relativePath == "docs/continuation.txt" })

        val bundle = service.getResultsBundle(secondRunId)
        assertNotNull(bundle, "Continuation run should expose a downloadable results bundle")
        val bundleEntries = unzipEntries(bundle)
        assertTrue(bundleEntries.keys.contains("inputs/docs/source.txt"))
        assertTrue(bundleEntries.keys.contains("inputs/docs/continuation.txt"))
        assertTrue(bundleEntries["inputs/docs/source.txt"]!!.contains("Source run context for continuation."))
        assertTrue(bundleEntries["inputs/docs/continuation.txt"]!!.contains("Fresh continuation context."))
    }

    @Test
    fun `browser deliverable archive prefers dedicated final writer html over summary fallback html`() = runBlocking {
        val tmpDir = createTempDirectory("svc-final-deliverable-flow-test-").toFile()
        val substrateFactory = SubstrateDatabaseFactory(tmpDir)
        val runId = "run-final-deliverable"
        seedFinalCandidateAuthority(substrateFactory, runId, listOf("group-0", "group-1"))
        val finalWriterAttempts = AtomicInteger(0)
        val finalWriterModels = ConcurrentLinkedQueue<String>()
        val sawRetryPrompt = AtomicInteger(0)

        val service = EvoMoliorRunService(
            lifecycleManagerProvider = { runIdValue, groupIdValue, _, _, groupProfile ->
                LifecycleManager(
                    runId = runIdValue,
                    groupId = groupIdValue,
                    shuruVmManager = ShuruVmManager(),
                    substrateDatabaseFactory = substrateFactory,
                    agentJarPath = "/fake/agent.jar",
                    openRouterApiKey = "sk-test",
                    groupProfile = groupProfile,
                    localAgentRunner = { bootContext: AgentBootContext, _ ->
                        val db = substrateFactory.get(bootContext.runId, bootContext.groupId)
                        val now = System.currentTimeMillis()
                        when {
                            bootContext.role == Role.Archivist &&
                                bootContext.rolePrompt.contains("Round Submission Summariser") -> {
                                db.stagingDao().insert(
                                    StagingSubmissionEntity(
                                        id = "summary-${bootContext.groupId}-$now",
                                        agentId = bootContext.agentId,
                                        role = bootContext.role.name,
                                        type = "PLAN_UPDATE",
                                        content = """
                                            ROUND SUBMISSION SUMMARY
                                            - Summary fallback drafted.
                                            - Grader should inspect the publication HTML fallback.

                                            FINAL DELIVERABLE HTML
                                            <!doctype html>
                                            <html lang="en">
                                            <head><meta charset="utf-8"><title>Summary Fallback Article</title></head>
                                            <body><article><h1>Summary Fallback Article</h1><p>summary path</p></article></body>
                                            </html>
                                        """.trimIndent(),
                                        budgetSignature = "test-summary",
                                        status = "pending",
                                        generation = bootContext.generation,
                                        groupId = bootContext.groupId,
                                        runId = bootContext.runId,
                                        submittedAtMs = now,
                                        reviewedAtMs = null
                                    )
                                )
                            }
                            bootContext.role == Role.Builder &&
                                bootContext.rolePrompt.contains("Final Deliverable Writer") -> {
                                finalWriterModels += bootContext.modelAssignment
                                val attempt = finalWriterAttempts.incrementAndGet()
                                if (bootContext.rolePrompt.contains("RETRY CONTEXT")) {
                                    sawRetryPrompt.incrementAndGet()
                                }
                                if (attempt >= 2) {
                                    db.stagingDao().insert(
                                        StagingSubmissionEntity(
                                            id = "final-${bootContext.groupId}-$now",
                                            agentId = bootContext.agentId,
                                            role = bootContext.role.name,
                                            type = "DOC",
                                            content = """
                                                <!doctype html>
                                                <html lang="en">
                                                <head>
                                                  <meta charset="utf-8">
                                                  <meta name="viewport" content="width=device-width, initial-scale=1">
                                                  <title>Dedicated Final Writer Article</title>
                                                  <style>body{font-family:system-ui,sans-serif;}section{margin:1rem 0;}</style>
                                                </head>
                                                <body>
                                                  <article>
                                                    <header><h1>Dedicated Final Writer Article</h1></header>
                                                    <section><p>publication path</p></section>
                                                  </article>
                                                </body>
                                                </html>
                                            """.trimIndent(),
                                            budgetSignature = "test-final",
                                            status = "pending",
                                            generation = bootContext.generation,
                                            groupId = bootContext.groupId,
                                            runId = bootContext.runId,
                                            submittedAtMs = now,
                                            reviewedAtMs = null
                                        )
                                    )
                                    insertFinalWriterDeathNote(db, bootContext, now + 1L)
                                }
                            }
                        }
                    }
                )
            },
            groupCoordinatorProvider = { groupIds, dimensions ->
                GroupCoordinator(groupIds, dimensions)
            },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = substrateFactory,
            metaPlannerRunner = MetaPlannerRunner(),
            roundHeartbeatIntervalMs = 1L,
            inspectorCheckIntervalMs = 1L
        )

        service.startRun(
            RunConfig(
                runId = runId,
                objective = "Create a long-form HTML5 article for mindvortex.app with embedded styles",
                groupCount = 2,
                groupProfiles = listOf(
                    GroupProfileType.Library,
                    GroupProfileType.Factory
                ),
                rounds = 1,
                roundDurationMinutes = 0,
                maxAgentsPerGroup = 1,
                scoringDimensions = listOf(
                    ScoringDimension(
                        id = "content_depth",
                        name = "Content Depth",
                        description = "Reward substantial content quality.",
                        weight = 1.0
                    )
                ),
                openRouterApiKey = "sk-test",
                modelOverrides = mapOf(
                    Role.Builder.name to "test-builder-model",
                    Role.Archivist.name to "test-archivist-model"
                ),
                isTestRun = true
            )
        )

        withTimeout(30_000) {
            while (service.getMetaArchiveEntries(runId, RunArchiveService.ENTRY_RUN_COMPLETED).isEmpty()) {
                kotlinx.coroutines.yield()
            }
        }

        val bundleEntries = unzipEntries(assertNotNull(service.getResultsBundle(runId)))
        val finalArticle = assertNotNull(bundleEntries["outputs/final-article.html"])
        assertTrue(finalArticle.contains("Dedicated Final Writer Article"))
        assertFalse(finalArticle.contains("Summary Fallback Article"))
        assertTrue(finalWriterAttempts.get() in 2..3)
        assertTrue(finalWriterModels.size >= 2)
        assertEquals("test-builder-model", finalWriterModels.elementAt(0))
        assertEquals("test-builder-model", finalWriterModels.elementAt(1))
        assertTrue(sawRetryPrompt.get() >= 1)
    }

    @Test
    fun `browser final writer retries html that violates objective minimum word count`() = runBlocking {
        val tmpDir = createTempDirectory("svc-final-deliverable-min-words-test-").toFile()
        val substrateFactory = SubstrateDatabaseFactory(tmpDir)
        val runId = "run-final-deliverable-min-words"
        seedFinalCandidateAuthority(substrateFactory, runId, listOf("group-0", "group-1"))
        val finalWriterAttempts = AtomicInteger(0)
        val finalWriterModels = ConcurrentLinkedQueue<String>()

        val service = EvoMoliorRunService(
            lifecycleManagerProvider = { runIdValue, groupIdValue, _, _, groupProfile ->
                LifecycleManager(
                    runId = runIdValue,
                    groupId = groupIdValue,
                    shuruVmManager = ShuruVmManager(),
                    substrateDatabaseFactory = substrateFactory,
                    agentJarPath = "/fake/agent.jar",
                    openRouterApiKey = "sk-test",
                    groupProfile = groupProfile,
                    localAgentRunner = { bootContext: AgentBootContext, _ ->
                        val db = substrateFactory.get(bootContext.runId, bootContext.groupId)
                        val now = System.currentTimeMillis()
                        when {
                            bootContext.role == Role.Archivist &&
                                bootContext.rolePrompt.contains("Round Submission Summariser") -> {
                                db.stagingDao().insert(
                                    StagingSubmissionEntity(
                                        id = "summary-${bootContext.groupId}-$now",
                                        agentId = bootContext.agentId,
                                        role = bootContext.role.name,
                                        type = "PLAN_UPDATE",
                                        content = "ROUND SUBMISSION SUMMARY\n- Minimal summary for final writer.",
                                        budgetSignature = "test-summary",
                                        status = "pending",
                                        generation = bootContext.generation,
                                        groupId = bootContext.groupId,
                                        runId = bootContext.runId,
                                        submittedAtMs = now,
                                        reviewedAtMs = null
                                    )
                                )
                            }
                            bootContext.role == Role.Builder &&
                                bootContext.rolePrompt.contains("Final Deliverable Writer") -> {
                                finalWriterModels += bootContext.modelAssignment
                                val attempt = finalWriterAttempts.incrementAndGet()
                                val body = if (attempt == 1) {
                                    "one two"
                                } else {
                                    "one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen nineteen twenty twentyone twentytwo"
                                }
                                db.stagingDao().insert(
                                    StagingSubmissionEntity(
                                        id = "final-${bootContext.groupId}-$now",
                                        agentId = bootContext.agentId,
                                        role = bootContext.role.name,
                                        type = "DOC",
                                        content = """
                                            <!doctype html>
                                            <html lang="en">
                                            <head><meta charset="utf-8"><title>Minimum Words Article</title></head>
                                            <body><main><article><h1>Minimum Words Article</h1><p>$body</p></article></main></body>
                                            </html>
                                        """.trimIndent(),
                                        budgetSignature = "test-final",
                                        status = "pending",
                                        generation = bootContext.generation,
                                        groupId = bootContext.groupId,
                                        runId = bootContext.runId,
                                        submittedAtMs = now,
                                        reviewedAtMs = null
                                    )
                                )
                                insertFinalWriterDeathNote(db, bootContext, now + 1L)
                            }
                        }
                    }
                )
            },
            groupCoordinatorProvider = { groupIds, dimensions ->
                GroupCoordinator(groupIds, dimensions)
            },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = substrateFactory,
            metaPlannerRunner = MetaPlannerRunner(),
            roundHeartbeatIntervalMs = 1L,
            inspectorCheckIntervalMs = 1L
        )

        service.startRun(
            RunConfig(
                runId = runId,
                objective = "Create a standalone HTML article. The visible article body must be at least 20 words.",
                groupCount = 2,
                groupProfiles = listOf(
                    GroupProfileType.Library,
                    GroupProfileType.Factory
                ),
                rounds = 1,
                roundDurationMinutes = 0,
                maxAgentsPerGroup = 1,
                scoringDimensions = listOf(
                    ScoringDimension(
                        id = "content_depth",
                        name = "Content Depth",
                        description = "Reward substantial content quality.",
                        weight = 1.0
                    )
                ),
                openRouterApiKey = "sk-test",
                modelOverrides = mapOf(
                    Role.Builder.name to "test-builder-model",
                    Role.Archivist.name to "test-archivist-model"
                ),
                isTestRun = true
            )
        )

        withTimeout(30_000) {
            while (service.getMetaArchiveEntries(runId, RunArchiveService.ENTRY_RUN_COMPLETED).isEmpty()) {
                kotlinx.coroutines.yield()
            }
        }

        val bundleEntries = unzipEntries(assertNotNull(service.getResultsBundle(runId)))
        val finalArticle = assertNotNull(bundleEntries["outputs/final-article.html"])
        assertTrue(finalArticle.contains("twentytwo"))
        assertFalse(finalArticle.contains("<p>one two</p>"))
        assertTrue(finalWriterAttempts.get() in 2..3)
        assertEquals("test-builder-model", finalWriterModels.elementAt(0))
        assertEquals("test-builder-model", finalWriterModels.elementAt(1))
    }

    @Test
    fun `browser final writer retries source grounded html without evidence notes`() = runBlocking {
        val tmpDir = createTempDirectory("svc-final-deliverable-evidence-test-").toFile()
        val substrateFactory = SubstrateDatabaseFactory(tmpDir)
        val runId = "run-final-deliverable-evidence"
        seedFinalCandidateAuthority(substrateFactory, runId, listOf("group-0", "group-1"))
        val finalWriterAttempts = AtomicInteger(0)

        val service = EvoMoliorRunService(
            lifecycleManagerProvider = { runIdValue, groupIdValue, _, _, groupProfile ->
                LifecycleManager(
                    runId = runIdValue,
                    groupId = groupIdValue,
                    shuruVmManager = ShuruVmManager(),
                    substrateDatabaseFactory = substrateFactory,
                    agentJarPath = "/fake/agent.jar",
                    openRouterApiKey = "sk-test",
                    groupProfile = groupProfile,
                    localAgentRunner = { bootContext: AgentBootContext, _ ->
                        val db = substrateFactory.get(bootContext.runId, bootContext.groupId)
                        val now = System.currentTimeMillis()
                        when {
                            bootContext.role == Role.Archivist &&
                                bootContext.rolePrompt.contains("Round Submission Summariser") -> {
                                db.stagingDao().insert(
                                    StagingSubmissionEntity(
                                        id = "summary-${bootContext.groupId}-$now",
                                        agentId = bootContext.agentId,
                                        role = bootContext.role.name,
                                        type = "PLAN_UPDATE",
                                        content = "ROUND SUBMISSION SUMMARY\n- Evidence strategy summary.",
                                        budgetSignature = "test-summary",
                                        status = "pending",
                                        generation = bootContext.generation,
                                        groupId = bootContext.groupId,
                                        runId = bootContext.runId,
                                        submittedAtMs = now,
                                        reviewedAtMs = null
                                    )
                                )
                            }
                            bootContext.role == Role.Builder &&
                                bootContext.rolePrompt.contains("Final Deliverable Writer") -> {
                                val attempt = finalWriterAttempts.incrementAndGet()
                                val evidenceSection = if (attempt == 1) {
                                    "<section><h2>Strategy</h2><p>Capture external cognition safely.</p></section>"
                                } else {
                                    """
                                        <section>
                                          <h2>Source-grounded evidence notes</h2>
                                          <p>Digital interventions review: frame claims as productivity support, not treatment.</p>
                                          <p>Inference: confidence labels should be tested in MVP experiments.</p>
                                        </section>
                                    """.trimIndent()
                                }
                                db.stagingDao().insert(
                                    StagingSubmissionEntity(
                                        id = "final-${bootContext.groupId}-$now",
                                        agentId = bootContext.agentId,
                                        role = bootContext.role.name,
                                        type = "DOC",
                                        content = """
                                            <!doctype html>
                                            <html lang="en">
                                            <head><meta charset="utf-8"><title>Evidence Strategy</title></head>
                                            <body><main><article><h1>Evidence Strategy</h1>$evidenceSection</article></main></body>
                                            </html>
                                        """.trimIndent(),
                                        budgetSignature = "test-final",
                                        status = "pending",
                                        generation = bootContext.generation,
                                        groupId = bootContext.groupId,
                                        runId = bootContext.runId,
                                        submittedAtMs = now,
                                        reviewedAtMs = null
                                    )
                                )
                                insertFinalWriterDeathNote(db, bootContext, now + 1L)
                            }
                        }
                    }
                )
            },
            groupCoordinatorProvider = { groupIds, dimensions ->
                GroupCoordinator(groupIds, dimensions)
            },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = substrateFactory,
            metaPlannerRunner = MetaPlannerRunner(),
            roundHeartbeatIntervalMs = 1L,
            inspectorCheckIntervalMs = 1L
        )

        service.startRun(
            RunConfig(
                runId = runId,
                objective = "Create a research-grounded HTML strategy report with Source-grounded evidence notes. Do not invent citations.",
                groupCount = 2,
                groupProfiles = listOf(
                    GroupProfileType.Library,
                    GroupProfileType.Factory
                ),
                rounds = 1,
                roundDurationMinutes = 0,
                maxAgentsPerGroup = 1,
                scoringDimensions = listOf(
                    ScoringDimension(
                        id = "evidence_fidelity",
                        name = "Evidence Fidelity",
                        description = "Reward source-grounded claims.",
                        weight = 1.0
                    )
                ),
                openRouterApiKey = "sk-test",
                modelOverrides = mapOf(
                    Role.Builder.name to "test-builder-model",
                    Role.Archivist.name to "test-archivist-model"
                ),
                isTestRun = true
            )
        )

        withTimeout(10_000) {
            service.events.first { event ->
                event is EvoMoliorEvent.RunCompleted && event.runId == runId
            }
        }

        val bundleEntries = unzipEntries(assertNotNull(service.getResultsBundle(runId)))
        val finalArticle = assertNotNull(bundleEntries["outputs/final-article.html"])
        assertTrue(finalArticle.contains("Source-grounded evidence notes"))
        assertEquals(2, finalWriterAttempts.get())
    }

    @Test
    fun `test run final writer timeout aborts before outer benchmark timeout`() = runBlocking {
        val tmpDir = createTempDirectory("svc-final-deliverable-timeout-test-").toFile()
        val substrateFactory = SubstrateDatabaseFactory(tmpDir)
        val runId = "run-final-deliverable-timeout"
        seedFinalCandidateAuthority(substrateFactory, runId, listOf("group-0"))
        val finalWriterAttempts = AtomicInteger(0)

        val service = EvoMoliorRunService(
            lifecycleManagerProvider = { runIdValue, groupIdValue, _, _, groupProfile ->
                LifecycleManager(
                    runId = runIdValue,
                    groupId = groupIdValue,
                    shuruVmManager = ShuruVmManager(),
                    substrateDatabaseFactory = substrateFactory,
                    agentJarPath = "/fake/agent.jar",
                    openRouterApiKey = "sk-test",
                    groupProfile = groupProfile,
                    localAgentRunner = { bootContext: AgentBootContext, _ ->
                        if (bootContext.role == Role.Builder &&
                            bootContext.rolePrompt.contains("Final Deliverable Writer")
                        ) {
                            finalWriterAttempts.incrementAndGet()
                            delay(60_000L)
                        }
                    }
                )
            },
            groupCoordinatorProvider = { groupIds, dimensions ->
                GroupCoordinator(groupIds, dimensions)
            },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = substrateFactory,
            metaPlannerRunner = MetaPlannerRunner(),
            roundHeartbeatIntervalMs = 1L,
            inspectorCheckIntervalMs = 1L
        )

        service.startRun(
            RunConfig(
                runId = runId,
                objective = "Produce the final benchmark prediction JSON.",
                groupCount = 1,
                groupProfiles = listOf(GroupProfileType.Library),
                rounds = 1,
                roundDurationMinutes = 0,
                maxAgentsPerGroup = 1,
                scoringDimensions = listOf(
                    ScoringDimension(
                        id = "accuracy",
                        name = "Accuracy",
                        description = "Reward exact benchmark answers.",
                        weight = 1.0
                    )
                ),
                openRouterApiKey = "sk-test",
                finalOutputSpec = FinalOutputSpec(
                    relativePath = "tier0-prediction.json",
                    mediaType = "application/json",
                    description = "Tier-0 benchmark prediction JSON",
                    kind = FinalOutputKind.GENERIC_FILE
                ),
                isTestRun = true
            )
        )

        val aborted = withTimeout(25_000L) {
            service.events.first { event ->
                event is EvoMoliorEvent.RunAborted && event.runId == runId
            } as EvoMoliorEvent.RunAborted
        }

        assertTrue(aborted.reason.contains("Final output tier0-prediction.json"))
        assertTrue(finalWriterAttempts.get() >= 3)
        assertTrue(finalWriterAttempts.get() <= 4)
        assertEquals(EngineStatus.IDLE, service.getState().status)
        assertTrue(service.getMetaArchiveEntries(runId, RunArchiveService.ENTRY_RUN_COMPLETED).isEmpty())
    }

    @Test
    fun `archive failure aborts completion before RunCompleted is emitted`() = runBlocking {
        val tmpDir = createTempDirectory("svc-archive-failure-completion-test-").toFile()
        val substrateFactory = SubstrateDatabaseFactory(tmpDir)
        val runId = "run-archive-failure-completion"
        val collectedEvents = ConcurrentLinkedQueue<EvoMoliorEvent>()

        val service = EvoMoliorRunService(
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
            groupCoordinatorProvider = { groupIds, dimensions -> GroupCoordinator(groupIds, dimensions) },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = substrateFactory,
            archiveService = FailingArchiveService(substrateFactory),
            metaPlannerRunner = MetaPlannerRunner(),
            roundHeartbeatIntervalMs = 1L,
            inspectorCheckIntervalMs = 1L
        )

        val eventCollector = launch {
            service.events.collect { event ->
                collectedEvents += event
            }
        }

        service.startRun(
            RunConfig(
                runId = runId,
                objective = "Archive failure must not produce a successful completion event.",
                groupCount = 1,
                groupProfiles = listOf(GroupProfileType.Library),
                rounds = 1,
                roundDurationMinutes = 0,
                maxAgentsPerGroup = 1,
                scoringDimensions = listOf(
                    ScoringDimension(
                        id = "accuracy",
                        name = "Accuracy",
                        description = "Reward evidence-based completion.",
                        weight = 1.0
                    )
                ),
                openRouterApiKey = "sk-test",
                isTestRun = true
            )
        )

        val aborted = withTimeout(10_000L) {
            service.events.first { event ->
                event is EvoMoliorEvent.RunAborted && event.runId == runId
            } as EvoMoliorEvent.RunAborted
        }

        eventCollector.cancel()

        assertTrue(aborted.reason.contains("forced archive failure"))
        assertFalse(
            collectedEvents.any { it is EvoMoliorEvent.RunCompleted && it.runId == runId },
            "RunCompleted must not be emitted after deterministic archive enforcement fails."
        )
        assertTrue(
            service.getMetaArchiveEntries(runId, RunArchiveService.ENTRY_RUN_COMPLETED).isEmpty(),
            "Archive failure must roll back the provisional completion authority."
        )
        assertEquals(EngineStatus.IDLE, service.getState().status)
    }

    @Test
    fun `acceptance round boundary failure suppresses the completed round event`() = runBlocking {
        val tmpDir = createTempDirectory("svc-acceptance-round-boundary-").toFile()
        val substrateFactory = SubstrateDatabaseFactory(tmpDir)
        val runId = "acceptance-round-boundary"
        val archiveCalls = AtomicInteger()
        val events = ConcurrentLinkedQueue<EvoMoliorEvent>()
        val service = acceptanceBoundaryService(
            substrateFactory = substrateFactory,
            failingBoundary = AcceptanceRunBoundary.RoundCompleted(0),
            archiveCalls = archiveCalls
        )
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            service.events.collect { events += it }
        }
        val aborted = async(start = CoroutineStart.UNDISPATCHED) {
            awaitAcceptanceBoundaryAbort(service, runId)
        }

        service.startRun(acceptanceBoundaryConfig(runId))

        val abortedReason = aborted.await().reason
        assertTrue(
            abortedReason.contains("forced acceptance boundary failure"),
            buildString {
                append(abortedReason)
                events.filterIsInstance<EvoMoliorEvent.EngineProgress>().forEach { event ->
                    append("\n")
                    append(event.phase)
                    append(": ")
                    append(event.message)
                }
            }
        )
        collector.cancel()

        assertFalse(
            events.any { it is EvoMoliorEvent.RoundCompleted && it.runId == runId },
            "A failed synchronous round boundary must prevent RoundCompleted publication."
        )
        assertEquals(0, archiveCalls.get())
    }

    @Test
    fun `acceptance final publication boundary failure leaves no public or completion authority`() =
        runBlocking {
            val tmpDir = createTempDirectory("svc-acceptance-final-boundary-").toFile()
            val substrateFactory = SubstrateDatabaseFactory(tmpDir)
            val runId = "acceptance-final-boundary"
            val archiveCalls = AtomicInteger()
            val service = acceptanceBoundaryService(
                substrateFactory = substrateFactory,
                failingBoundary = AcceptanceRunBoundary.BeforeFinalPublication,
                archiveCalls = archiveCalls
            )
            val aborted = async(start = CoroutineStart.UNDISPATCHED) {
                awaitAcceptanceBoundaryAbort(service, runId)
            }

            service.startRun(acceptanceBoundaryConfig(runId))

            val abortedReason = aborted.await().reason
            assertTrue(abortedReason.contains("forced acceptance boundary failure"), abortedReason)
            val publication = PublicationStateRepository(substrateFactory.getAtlas(runId))
            assertTrue(
                publication.finalCandidates(runId).isEmpty(),
                "Final candidates must not be sealed after final-publication boundary rejection."
            )
            assertTrue(
                publication.history(runId).isEmpty(),
                "No publication authority may exist before a rejected final-publication boundary."
            )
            assertNoCompletionAuthorities(service, runId)
            assertEquals(0, archiveCalls.get())
        }

    @Test
    fun `acceptance archive boundary failure leaves no completion graph or archive authority`() =
        runBlocking {
            val tmpDir = createTempDirectory("svc-acceptance-archive-boundary-").toFile()
            val substrateFactory = SubstrateDatabaseFactory(tmpDir)
            val runId = "acceptance-archive-boundary"
            val archiveCalls = AtomicInteger()
            val service = acceptanceBoundaryService(
                substrateFactory = substrateFactory,
                failingBoundary = AcceptanceRunBoundary.BeforeArchivePublication,
                archiveCalls = archiveCalls
            )
            val aborted = async(start = CoroutineStart.UNDISPATCHED) {
                awaitAcceptanceBoundaryAbort(service, runId)
            }

            service.startRun(acceptanceBoundaryConfig(runId))

            val abortedReason = aborted.await().reason
            assertTrue(abortedReason.contains("forced acceptance boundary failure"), abortedReason)
            val publication = PublicationStateRepository(substrateFactory.getAtlas(runId))
            assertNoCompletionAuthorities(service, runId)
            assertFalse(
                publication.history(runId).any {
                    it.state == PublicationState.MANIFEST_HASHED ||
                        it.state == PublicationState.ARCHIVED
                },
                "Archive publication states must not be written after archive-boundary rejection."
            )
            assertEquals(0, archiveCalls.get(), "Archive service must not run after boundary rejection.")
        }

    @Test
    fun `browser deliverable archive survives many late doc entries before the canonical html`() = runBlocking {
        val tmpDir = createTempDirectory("svc-final-deliverable-volume-test-").toFile()
        val substrateFactory = SubstrateDatabaseFactory(tmpDir)
        val runId = "run-final-deliverable-volume"
        seedFinalCandidateAuthority(substrateFactory, runId, listOf("group-0", "group-1"))
        val lateDocCount = 180

        val service = EvoMoliorRunService(
            lifecycleManagerProvider = { runIdValue, groupIdValue, _, _, groupProfile ->
                LifecycleManager(
                    runId = runIdValue,
                    groupId = groupIdValue,
                    shuruVmManager = ShuruVmManager(),
                    substrateDatabaseFactory = substrateFactory,
                    agentJarPath = "/fake/agent.jar",
                    openRouterApiKey = "sk-test",
                    groupProfile = groupProfile,
                    localAgentRunner = { bootContext: AgentBootContext, _ ->
                        if (bootContext.role == Role.Builder &&
                            bootContext.rolePrompt.contains("Final Deliverable Writer")
                        ) {
                            val db = substrateFactory.get(bootContext.runId, bootContext.groupId)
                            val baseNow = System.currentTimeMillis()
                            db.stagingDao().insert(
                                StagingSubmissionEntity(
                                    id = "final-${bootContext.groupId}-$baseNow",
                                    agentId = bootContext.agentId,
                                    role = bootContext.role.name,
                                    type = "DOC",
                                    content = """
                                        <!doctype html>
                                        <html lang="en">
                                        <head>
                                          <meta charset="utf-8">
                                          <meta name="viewport" content="width=device-width, initial-scale=1">
                                          <title>High-Volume Final Writer Article</title>
                                        </head>
                                        <body>
                                          <article><h1>High-Volume Final Writer Article</h1></article>
                                        </body>
                                        </html>
                                    """.trimIndent(),
                                    budgetSignature = "test-final-volume",
                                    status = "pending",
                                    generation = bootContext.generation,
                                    groupId = bootContext.groupId,
                                    runId = bootContext.runId,
                                    submittedAtMs = baseNow,
                                    reviewedAtMs = null
                                )
                            )

                            repeat(lateDocCount) { index ->
                                db.stagingDao().insert(
                                    StagingSubmissionEntity(
                                        id = "late-doc-${bootContext.groupId}-$baseNow-$index",
                                        agentId = bootContext.agentId,
                                        role = bootContext.role.name,
                                        type = "DOC",
                                        content = "late-doc-noise-$index",
                                        budgetSignature = "test-final-volume-noise",
                                        status = "pending",
                                        generation = bootContext.generation,
                                        groupId = bootContext.groupId,
                                        runId = bootContext.runId,
                                        submittedAtMs = baseNow + index + 1L,
                                        reviewedAtMs = null
                                    )
                                )
                            }
                            insertFinalWriterDeathNote(
                                db,
                                bootContext,
                                baseNow + lateDocCount + 1L
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
            metaPlannerRunner = MetaPlannerRunner(),
            roundHeartbeatIntervalMs = 1L,
            inspectorCheckIntervalMs = 1L
        )

        service.startRun(
            RunConfig(
                runId = runId,
                objective = "Create a long-form HTML5 article with lots of late-round DOC noise",
                groupCount = 2,
                groupProfiles = listOf(
                    GroupProfileType.Library,
                    GroupProfileType.Factory
                ),
                rounds = 1,
                roundDurationMinutes = 0,
                maxAgentsPerGroup = 1,
                scoringDimensions = listOf(
                    ScoringDimension(
                        id = "content_depth",
                        name = "Content Depth",
                        description = "Reward substantial content quality.",
                        weight = 1.0
                    )
                ),
                openRouterApiKey = "sk-test",
                modelOverrides = mapOf(
                    Role.Builder.name to "test-builder-model",
                    Role.Archivist.name to "test-archivist-model"
                ),
                isTestRun = true
            )
        )

        withTimeout(30_000) {
            while (service.getMetaArchiveEntries(runId, RunArchiveService.ENTRY_RUN_COMPLETED).isEmpty()) {
                kotlinx.coroutines.yield()
            }
        }

        val bundleEntries = unzipEntries(assertNotNull(service.getResultsBundle(runId)))
        val finalArticle = assertNotNull(bundleEntries["outputs/final-article.html"])
        assertTrue(finalArticle.contains("High-Volume Final Writer Article"))
        assertTrue(
            assertNotNull(bundleEntries["outputs/high-level-run-log.txt"])
                .contains("Final outputs: final-article.html")
        )

        val completionEntry = service.getMetaArchiveEntries(runId, RunArchiveService.ENTRY_RUN_COMPLETED).last()
        val decodedCompletion = json.decodeFromString(RunCompletionArchiveRecord.serializer(), completionEntry.contentJson)
        assertEquals(1, decodedCompletion.generatedOutputFiles.size)
        assertEquals("final-article.html", decodedCompletion.generatedOutputFiles.single().relativePath)
        assertEquals("text/html", decodedCompletion.generatedOutputFiles.single().mediaType)
    }

    @Test
    fun `non html final writer stores raw json body as generated output file`() = runBlocking {
        val tmpDir = createTempDirectory("svc-json-final-deliverable-flow-test-").toFile()
        val substrateFactory = SubstrateDatabaseFactory(tmpDir)
        val runId = "run-json-final-deliverable"
        seedFinalCandidateAuthority(substrateFactory, runId, listOf("group-0", "group-1"))

        val service = EvoMoliorRunService(
            lifecycleManagerProvider = { runIdValue, groupIdValue, _, _, groupProfile ->
                LifecycleManager(
                    runId = runIdValue,
                    groupId = groupIdValue,
                    shuruVmManager = ShuruVmManager(),
                    substrateDatabaseFactory = substrateFactory,
                    agentJarPath = "/fake/agent.jar",
                    openRouterApiKey = "sk-test",
                    groupProfile = groupProfile,
                    localAgentRunner = { bootContext: AgentBootContext, _ ->
                        if (bootContext.role == Role.Builder &&
                            bootContext.rolePrompt.contains("Final Deliverable Writer")
                        ) {
                            val db = substrateFactory.get(bootContext.runId, bootContext.groupId)
                            val now = System.currentTimeMillis()
                            db.stagingDao().insert(
                                StagingSubmissionEntity(
                                    id = "json-final-${bootContext.groupId}-$now",
                                    agentId = bootContext.agentId,
                                    role = bootContext.role.name,
                                    type = "DOC",
                                    content = """
                                        {
                                          "title": "ADHD Evidence Brief",
                                          "generatedOn": "2026-06-04",
                                          "audience": "Editors",
                                          "keyTakeaways": [
                                            "Medication evidence is strongest for short-term symptom reduction.",
                                            "Non-pharmacological evidence is useful but more heterogeneous."
                                          ]
                                        }
                                    """.trimIndent(),
                                    budgetSignature = "test-json-final",
                                    status = "pending",
                                    generation = bootContext.generation,
                                    groupId = bootContext.groupId,
                                    runId = bootContext.runId,
                                    submittedAtMs = now,
                                    reviewedAtMs = null
                                )
                            )
                            insertFinalWriterDeathNote(db, bootContext, now + 1L)
                        }
                    }
                )
            },
            groupCoordinatorProvider = { groupIds, dimensions ->
                GroupCoordinator(groupIds, dimensions)
            },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = substrateFactory,
            metaPlannerRunner = MetaPlannerRunner(),
            roundHeartbeatIntervalMs = 1L,
            inspectorCheckIntervalMs = 1L
        )

        service.startRun(
            RunConfig(
                runId = runId,
                objective = "Produce a machine-readable JSON evidence brief",
                groupCount = 2,
                groupProfiles = listOf(
                    GroupProfileType.Library,
                    GroupProfileType.Factory
                ),
                rounds = 1,
                roundDurationMinutes = 0,
                maxAgentsPerGroup = 1,
                scoringDimensions = listOf(
                    ScoringDimension(
                        id = "content_depth",
                        name = "Content Depth",
                        description = "Reward substantial content quality.",
                        weight = 1.0
                    )
                ),
                openRouterApiKey = "sk-test",
                finalOutputSpec = FinalOutputSpec(
                    relativePath = "briefs/evidence-brief.json",
                    mediaType = "application/json",
                    description = "Machine-readable evidence brief"
                ),
                isTestRun = true
            )
        )

        withTimeout(30_000) {
            while (service.getMetaArchiveEntries(runId, RunArchiveService.ENTRY_RUN_COMPLETED).isEmpty()) {
                kotlinx.coroutines.yield()
            }
        }

        val completionEntry = service.getMetaArchiveEntries(runId, RunArchiveService.ENTRY_RUN_COMPLETED).last()
        val decodedCompletion = json.decodeFromString(RunCompletionArchiveRecord.serializer(), completionEntry.contentJson)
        assertEquals(1, decodedCompletion.generatedOutputFiles.size)
        assertEquals("briefs/evidence-brief.json", decodedCompletion.generatedOutputFiles.single().relativePath)
        assertEquals("application/json", decodedCompletion.generatedOutputFiles.single().mediaType)
        assertTrue(decodedCompletion.generatedOutputFiles.single().content.contains("\"ADHD Evidence Brief\""))

        val bundleEntries = unzipEntries(assertNotNull(service.getResultsBundle(runId)))
        val evidenceBrief = assertNotNull(bundleEntries["outputs/briefs/evidence-brief.json"])
        assertTrue(json.parseToJsonElement(evidenceBrief).toString().contains("ADHD Evidence Brief"))
        assertTrue(
            assertNotNull(bundleEntries["outputs/high-level-run-log.txt"])
                .contains("Final outputs: briefs/evidence-brief.json")
        )

        val staleStateFile = File(tmpDir, "evomolior/runs/$runId/control/${PersistedRunStateStore.STATE_FILE_NAME}")
        PersistedRunStateStore.write(
            staleStateFile,
            PersistedRunState(
                runId = runId,
                objective = "stale state should not override the live output spec",
                groupCount = 2,
                groupProfiles = listOf(GroupProfileType.Library, GroupProfileType.Factory),
                rounds = 1,
                roundDurationMinutes = 0,
                maxAgentsPerGroup = 1,
                scoringDimensions = listOf(
                    ScoringDimension(
                        id = "content_depth",
                        name = "Content Depth",
                        description = "Reward substantial content quality.",
                        weight = 1.0
                    )
                ),
                finalOutputSpec = FinalOutputSpec(
                    relativePath = "briefs/wrong.json",
                    mediaType = "application/json",
                    description = "Stale output contract"
                ),
                isTestRun = true,
                budgetOverride = 200.0,
                groupIds = listOf("group-0", "group-1"),
                graderGroupIds = emptyList()
            )
        )

        val serviceBundleEntries = unzipEntries(assertNotNull(service.getResultsBundle(runId)))
        assertTrue(serviceBundleEntries["outputs/briefs/evidence-brief.json"]!!.contains("ADHD Evidence Brief"))
        assertFalse(serviceBundleEntries.containsKey("outputs/briefs/wrong.json"))

        assertTrue(staleStateFile.delete(), "The stale persisted state file should be removable for the missing-file case")
        val missingStateBundleEntries = unzipEntries(assertNotNull(service.getResultsBundle(runId)))
        assertTrue(missingStateBundleEntries["outputs/briefs/evidence-brief.json"]!!.contains("ADHD Evidence Brief"))
        assertFalse(missingStateBundleEntries.containsKey("outputs/briefs/wrong.json"))

        val restartedService = EvoMoliorRunService(
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
            groupCoordinatorProvider = { groupIds, dimensions -> GroupCoordinator(groupIds, dimensions) },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = substrateFactory,
            metaPlannerRunner = MetaPlannerRunner(),
            roundHeartbeatIntervalMs = 1L,
            inspectorCheckIntervalMs = 1L
        )
        val restartedBundleEntries = unzipEntries(assertNotNull(restartedService.getResultsBundle(runId)))
        assertTrue(restartedBundleEntries["outputs/briefs/evidence-brief.json"]!!.contains("ADHD Evidence Brief"))
        assertFalse(restartedBundleEntries.containsKey("outputs/briefs/wrong.json"))
    }

    private suspend fun seedFinalCandidateAuthority(
        substrateFactory: SubstrateDatabaseFactory,
        runId: String,
        groupIds: List<String>
    ) {
        groupIds.forEach { groupId ->
            val database = substrateFactory.get(runId, groupId)
            val content = "Validated final candidate source for $groupId."
            val contentHash = AtlasHashing.hashText(content)
            val submissionId = "final-candidate-source-$groupId"
            val artifactId = "kb:$submissionId"
            database.stagingDao().insert(
                StagingSubmissionEntity(
                    id = submissionId,
                    agentId = "seed-agent-$groupId",
                    role = Role.Researcher.name,
                    type = "DOC",
                    content = content,
                    wipTaskRef = null,
                    budgetSignature = "seed-authority",
                    status = "promoted",
                    criticAgentId = "seed-critic-$groupId",
                    initialTrustScore = 1.0,
                    generation = 0,
                    groupId = groupId,
                    runId = runId,
                    submittedAtMs = 1L,
                    reviewedAtMs = 2L,
                    promotedArtifactRef = artifactId,
                    promotedArtifactHash = contentHash
                )
            )
            database.knowledgeBaseDao().insert(
                KnowledgeBaseEntryEntity(
                    id = submissionId,
                    content = content,
                    sourceAgentId = "seed-agent-$groupId",
                    generation = 0,
                    trustScore = 1.0,
                    domain = "test:final-candidate",
                    groupId = groupId,
                    runId = runId,
                    createdAtMs = 2L
                )
            )
            ContributionGraphRepository(
                database = database,
                atlasDatabase = substrateFactory.getAtlas(runId)
            ).recordCriticDecision(
                CriticDecisionRecord(
                    decisionId = "final-candidate-decision-$groupId",
                    runId = runId,
                    groupId = AtlasGroupId(groupId),
                    round = 0,
                    submissionId = submissionId,
                    submissionContentHash = contentHash,
                    resolvedReview = CriticReview(
                        submissionId = submissionId,
                        outcome = CriticOutcome.PROMOTE,
                        criticAgentId = "seed-critic-$groupId",
                        initialTrustScore = 1.0
                    ),
                    resultAuthority = ContributionAuthorityRef(
                        kind = ContributionAuthorityKind.CANONICAL_ARTIFACT,
                        authorityId = artifactId,
                        authorityHash = contentHash
                    ),
                    decidedAt = Instant.fromEpochMilliseconds(2L)
                ).withComputedHash()
            )
        }
    }

    private suspend fun insertFinalWriterDeathNote(
        database: com.achulkov.evomolior.substrate.db.EvoMoliorDatabase,
        bootContext: AgentBootContext,
        submittedAtMs: Long
    ) {
        database.stagingDao().insert(
            StagingSubmissionEntity(
                id = "final-writer-death-note-${bootContext.agentId}",
                agentId = bootContext.agentId,
                role = bootContext.role.name,
                type = "DEATH_NOTE",
                content = "Final deliverable published and validated.",
                wipTaskRef = null,
                budgetSignature = "test-final-writer-death-note",
                status = "promoted",
                criticAgentId = "test-publication-gate",
                initialTrustScore = 1.0,
                generation = bootContext.generation,
                groupId = bootContext.groupId,
                runId = bootContext.runId,
                submittedAtMs = submittedAtMs,
                reviewedAtMs = submittedAtMs
            )
        )
    }

    private fun acceptanceBoundaryService(
        substrateFactory: SubstrateDatabaseFactory,
        failingBoundary: AcceptanceRunBoundary,
        archiveCalls: AtomicInteger
    ): EvoMoliorRunService = EvoMoliorRunService(
        lifecycleManagerProvider = { runId, groupId, _, _, groupProfile ->
            LifecycleManager(
                runId = runId,
                groupId = groupId,
                shuruVmManager = ShuruVmManager(),
                substrateDatabaseFactory = substrateFactory,
                agentJarPath = "/fake/agent.jar",
                openRouterApiKey = "/tmp/codex",
                groupProfile = groupProfile,
                localAgentRunner = { bootContext, _ ->
                    if (
                        bootContext.role == Role.Builder &&
                            bootContext.rolePrompt.contains("Final Deliverable Writer")
                    ) {
                        val database = substrateFactory.get(bootContext.runId, bootContext.groupId)
                        val now = System.currentTimeMillis()
                        database.stagingDao().insert(
                            StagingSubmissionEntity(
                                id = "acceptance-final-${bootContext.agentId}-$now",
                                agentId = bootContext.agentId,
                                role = bootContext.role.name,
                                type = "DOC",
                                content = "# Receiver result\n\nreceiver-delivery-accepted",
                                budgetSignature = "acceptance-boundary-final-writer",
                                status = "pending",
                                generation = bootContext.generation,
                                groupId = bootContext.groupId,
                                runId = bootContext.runId,
                                submittedAtMs = now,
                                reviewedAtMs = null
                            )
                        )
                        insertFinalWriterDeathNote(database, bootContext, now + 1L)
                    }
                }
            )
        },
        groupCoordinatorProvider = { groupIds, dimensions ->
            GroupCoordinator(groupIds, dimensions)
        },
        graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
        substrateFactory = substrateFactory,
        archiveService = CountingArchiveService(substrateFactory, archiveCalls),
        acceptanceRunBoundaryVerifier = AcceptanceRunBoundaryVerifier { _, boundary ->
            if (boundary == failingBoundary) {
                error("forced acceptance boundary failure: $boundary")
            }
        },
        genesisCouncilRunner = {
                callbackRunId,
                callbackObjective,
                _,
                envelope,
                inputArtifacts,
                _,
                onInvocationStarted ->
            onInvocationStarted()
            val compiled = compiledCouncilTestFixture(
                runId = callbackRunId,
                objective = callbackObjective,
                envelope = envelope,
                inputArtifacts = inputArtifacts,
                workerGroupKeys = listOf("group-0", "group-1"),
                workerProfiles = listOf(GroupProfileType.Library, GroupProfileType.Factory)
            )
            seedFinalCandidateAuthority(
                substrateFactory = substrateFactory,
                runId = callbackRunId,
                groupIds = compiled.workerField.ringTopology
            )
            compiled
        },
        graderCriteriaRunner = {
                criteriaRunId,
                _,
                graderGroupIds,
                roundIndex,
                _,
                scoringDimensions,
                _ ->
            val graderAuthorityRepository = GraderAuthorityRepository(substrateFactory)
            val graderAtlas = requireNotNull(
                graderAuthorityRepository.currentAtlas(criteriaRunId)
            ) {
                "Acceptance boundary fixture requires the compiled Grader Atlas"
            }
            val leases = GraderNodeLeaseAuthority(graderAuthorityRepository)
            graderGroupIds.forEach { graderGroupId ->
                val lease = leases.acquireNext(
                    atlas = graderAtlas,
                    graderGroupId = graderGroupId,
                    round = roundIndex,
                    ttlMillis = 60_000L
                )
                val node = graderAtlas.nodes.getValue(lease.nodeId)
                val requirements = node.mappedWorkerNodeIds
                    .sortedBy { it.value }
                    .map { workerNodeId ->
                        val requirementId = "acceptance-${lease.nodeId.value}-${workerNodeId.value}"
                        GraderRequirementDraft(
                            requirementId = requirementId,
                            graderAtlasNodeId = lease.nodeId,
                            mappedWorkerNodeIds = setOf(workerNodeId),
                            mappedReceiverObligationRefs = node.receiverObligationRefs,
                            criterion = "The assigned receiver obligation has inspectable evidence.",
                            observableEvidence = "The sealed worker snapshot is available for review.",
                            passThreshold = "The snapshot is available.",
                            failureLevels = listOf(
                                FailureLevel(1, "Required evidence is unavailable.", 0.0)
                            ),
                            adversarialCases = emptyList(),
                            executableTestIds = listOf("test-$requirementId"),
                            disclosureSensitivity = DisclosureSensitivity.GRADER_INTERNAL,
                            rationale = "Acceptance fixture exercises the exact grader authority path.",
                            noveltyFingerprint = "acceptance-$graderGroupId-$workerNodeId",
                            maximumEvaluationCost = 1.0
                        )
                    }
                val methodology = GraderMethodologyDraft(
                    rubricDimensions = scoringDimensions.map { dimension ->
                        GraderRubricDraft(
                            dimensionId = dimension.id,
                            scoringGuidance = "Score the sealed receiver-facing evidence."
                        )
                    },
                    automatedTests = requirements.map { requirement ->
                        GraderAutomatedTestDraft(
                            testId = "test-${requirement.requirementId}",
                            description = "The sealed snapshot is mechanically available.",
                            expectedOutputPattern = ".*",
                            penaltyOnFailure = 0.0,
                            isMandatory = true,
                            mappedRequirementIds = setOf(requirement.requirementId),
                            inputSchemaHash = "acceptance-snapshot-v1",
                            expectedOutcomeHash = "snapshot-available",
                            difficulty = 0.0
                        )
                    },
                    failureModes = listOf(
                        GraderFailureModeDraft(
                            modeId = "missing-snapshot",
                            description = "The grader cannot inspect the sealed worker snapshot.",
                            penaltyWeight = 0.0
                        )
                    ),
                    requirements = requirements
                )
                substrateFactory.getGrader(criteriaRunId, graderGroupId).stagingDao().insert(
                    StagingSubmissionEntity(
                        id = "acceptance-methodology-$roundIndex-$graderGroupId",
                        agentId = "acceptance-grader-$graderGroupId",
                        role = Role.GraderBuilder.name,
                        type = "PLAN_UPDATE",
                        content = json.encodeToString(
                            GraderMethodologyDraft.serializer(),
                            methodology
                        ),
                        budgetSignature =
                            "$GRADER_METHODOLOGY_DRAFT_SIGNATURE_PREFIX" +
                                "acceptance-grader-$graderGroupId:${roundIndex + 1}",
                        status = "promoted",
                        generation = roundIndex + 1,
                        groupId = graderGroupId,
                        runId = criteriaRunId,
                        submittedAtMs = System.currentTimeMillis(),
                        reviewedAtMs = System.currentTimeMillis()
                    )
                )
            }
        },
        graderCriteriaReviewRunner = { _, _, _, _, _ -> },
        graderJudgingRunner = {
                judgingRunId,
                _,
                graderGroupIds,
                competingGroupIds,
                roundIndex,
                objective,
                scoringDimensions,
                graderGroupManager,
                nodeAssignments,
                onNodeGradeBundles,
                onProgress ->
            val judgeOutputs = GraderRunner(
                openRouterApiKey = "acceptance-fixture",
                llmBackend = LlmBackend.CODEX_CHATGPT,
                substrateFactory = substrateFactory,
                localAgentRunner = { "" },
                graderAgentTimeoutMs = 1_000L,
                graderModelOverride = ModelSelectionConfig.CODEX_CHATGPT_GPT56_LUNA_MODEL,
                structuredDimensionScoreAuthor = { request ->
                    json.encodeToString(
                        StructuredDimensionScoreRepairDraft.serializer(),
                        StructuredDimensionScoreRepairDraft(
                            scores = request.missingDimensions.map { dimension ->
                                StructuredDimensionScoreDraft(
                                    dimensionId = dimension.id,
                                    rawScore =
                                        if (request.targetGroupId == competingGroupIds.first()) {
                                            90.0
                                        } else {
                                            80.0
                                        },
                                    rationale = "Deterministic acceptance-fixture score."
                                )
                            }
                        )
                    )
                },
                structuredNodeGradeAuthor = {
                    json.encodeToString(
                        StructuredNodeGradeDraft.serializer(),
                        StructuredNodeGradeDraft(
                            verdict = StructuredNodeGradeVerdict.VALID,
                            scoreVector = NodeScoreVector(
                                correctness = 0.9,
                                evidenceQuality = 0.9,
                                reproducibility = 0.9,
                                robustness = 0.9,
                                integrationUtility = 0.9,
                                receiverRelevance = 0.9,
                                novelty = 0.9,
                                efficiency = 0.9,
                                hardFailure = false,
                                confidence = 0.9
                            ),
                            rationale = "Deterministic acceptance-fixture node grade."
                        )
                    )
                }
            ).runJudgingPhase(
                runId = judgingRunId,
                graderGroupIds = graderGroupIds,
                competingGroupIds = competingGroupIds,
                roundIndex = roundIndex,
                runObjective = objective,
                scoringDimensions = scoringDimensions,
                graderGroupManager = graderGroupManager,
                nodeGradingAssignments = nodeAssignments,
                onNodeGradeBundles = onNodeGradeBundles,
                onProgress = onProgress
            )
            graderGroupIds.forEach { graderGroupId ->
                competingGroupIds.forEach { targetGroupId ->
                    val submittedDimensionIds = graderGroupManager
                        .group(graderGroupId)
                        .submittedDimensionIds(targetGroupId, roundIndex)
                    val missingScores = scoringDimensions
                        .filterNot { dimension -> dimension.id in submittedDimensionIds }
                        .map { dimension ->
                            DimensionScore(
                                dimensionId = dimension.id,
                                rawScore =
                                    if (targetGroupId == competingGroupIds.first()) 90.0 else 80.0,
                                justification = "Deterministic acceptance-boundary fixture score."
                            )
                        }
                    if (missingScores.isNotEmpty()) {
                        graderGroupManager.submitScores(
                            graderGroupId = graderGroupId,
                            targetGroupId = targetGroupId,
                            roundIndex = roundIndex,
                            scores = missingScores
                        )
                    }
                }
            }
            judgeOutputs
        },
        metaPlannerRunner = MetaPlannerRunner(),
        roundHeartbeatIntervalMs = 1L,
        inspectorCheckIntervalMs = 1L
    )

    private fun acceptanceBoundaryConfig(runId: String): RunConfig = RunConfig(
        runId = runId,
        objective = "Produce the exact receiver-delivery-accepted result.",
        groupCount = 2,
        groupProfiles = listOf(GroupProfileType.Library, GroupProfileType.Factory),
        rounds = 1,
        roundDurationMinutes = 0,
        maxAgentsPerGroup = 1,
        scoringDimensions = listOf(
            ScoringDimension(
                id = "quality",
                name = "Quality",
                description = "Reward exact receiver-facing delivery.",
                weight = 1.0
            )
        ),
        openRouterApiKey = "/tmp/codex",
        llmBackend = LlmBackend.CODEX_CHATGPT,
        councilExecutionPurpose = CouncilExecutionPurpose.APPENDIX_B_ACCEPTANCE,
        acceptanceProfile = AcceptanceProfile.RUNTIME_USABILITY_SMOKE,
        finalOutputSpec = FinalOutputSpec(
            relativePath = "receiver/result.md",
            mediaType = "text/markdown"
        )
    )

    private suspend fun awaitAcceptanceBoundaryAbort(
        service: EvoMoliorRunService,
        runId: String
    ): EvoMoliorEvent.RunAborted = withTimeout(20_000L) {
        service.events.first { event ->
            event is EvoMoliorEvent.RunAborted && event.runId == runId
        } as EvoMoliorEvent.RunAborted
    }

    private suspend fun assertNoCompletionAuthorities(
        service: EvoMoliorRunService,
        runId: String
    ) {
        assertTrue(
            service.getMetaArchiveEntries(runId, RunArchiveService.ENTRY_RUN_COMPLETED).isEmpty(),
            "Rejected acceptance boundary must not retain a completion marker."
        )
        assertTrue(
            service.getMetaArchiveEntries(runId, RunArchiveService.ENTRY_RUN_GRAPH_MESSAGE).isEmpty(),
            "Rejected acceptance boundary must not retain a completion graph publication."
        )
    }

    private fun unzipEntries(bytes: ByteArray): Map<String, String> {
        val result = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entry ->
                result[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                zip.closeEntry()
            }
        }
        return result
    }

    @Serializable
    private data class RunCompletionArchiveRecord(
        val runId: String,
        val winnerId: String? = null,
        val finalAnswerSummary: String = "",
        val generatedOutputFiles: List<GeneratedOutputArchiveRecord> = emptyList(),
        val judgeOutputsByGrader: Map<String, String> = emptyMap(),
        val submissionSummariesByGroup: Map<String, String?> = emptyMap()
    )

    @Serializable
    private data class GeneratedOutputArchiveRecord(
        val relativePath: String,
        val mediaType: String,
        val content: String
    )

    private class FailingArchiveService(
        substrateFactory: SubstrateDatabaseFactory
    ) : RunArchiveService(substrateFactory) {
        override suspend fun archiveRun(
            runId: String,
            groupIds: List<String>,
            winnerId: String?,
            objective: String,
            startingFieldJson: String,
            finalGeneration: Int,
            requestedOutputSpec: FinalOutputSpec?,
            completionRecord: RunCompletionRecord?
        ) {
            throw IllegalStateException("forced archive failure for $runId")
        }
    }

    private class CountingArchiveService(
        substrateFactory: SubstrateDatabaseFactory,
        private val calls: AtomicInteger
    ) : RunArchiveService(substrateFactory) {
        override suspend fun archiveRun(
            runId: String,
            groupIds: List<String>,
            winnerId: String?,
            objective: String,
            startingFieldJson: String,
            finalGeneration: Int,
            requestedOutputSpec: FinalOutputSpec?,
            completionRecord: RunCompletionRecord?
        ) {
            calls.incrementAndGet()
            super.archiveRun(
                runId = runId,
                groupIds = groupIds,
                winnerId = winnerId,
                objective = objective,
                startingFieldJson = startingFieldJson,
                finalGeneration = finalGeneration,
                requestedOutputSpec = requestedOutputSpec,
                completionRecord = completionRecord
            )
        }
    }
}
