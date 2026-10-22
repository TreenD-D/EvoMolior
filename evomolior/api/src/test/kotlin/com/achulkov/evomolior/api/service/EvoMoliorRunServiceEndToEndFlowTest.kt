@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.api.events.EvoMoliorEvent
import com.achulkov.evomolior.api.grader.GraderRunner
import com.achulkov.evomolior.api.model.FinalOutputKind
import com.achulkov.evomolior.api.model.FinalOutputSpec
import com.achulkov.evomolior.api.model.FinalOutputValidationContract
import com.achulkov.evomolior.api.model.AcceptanceDiagnosticMode
import com.achulkov.evomolior.api.model.RunConfig
import com.achulkov.evomolior.api.model.RunInputArtifactUpload
import com.achulkov.evomolior.agents.factory.StructuredDimensionScoreDraft
import com.achulkov.evomolior.agents.factory.StructuredDimensionScoreRepairDraft
import com.achulkov.evomolior.agents.factory.StructuredNodeGradeDraft
import com.achulkov.evomolior.agents.factory.StructuredNodeGradeVerdict
import com.achulkov.evomolior.competition.coordinator.GroupCoordinator
import com.achulkov.evomolior.competition.grader.GraderGroupManager
import com.achulkov.evomolior.competition.grader.GraderNodeLeaseAuthority
import com.achulkov.evomolior.core.atlas.ArtifactType
import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.AtlasAgentId
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.NodeEvidenceCostSummary
import com.achulkov.evomolior.core.atlas.NodeGradeBundle
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.atlas.NodeKind
import com.achulkov.evomolior.core.atlas.NodeScoreVector
import com.achulkov.evomolior.core.budget.BudgetLedger
import com.achulkov.evomolior.core.contract.AgentBootContext
import com.achulkov.evomolior.core.contract.AgentExecutionPhase
import com.achulkov.evomolior.core.contract.AgentExitRecord
import com.achulkov.evomolior.core.contract.AgentLifecycleClassification
import com.achulkov.evomolior.core.contract.AgentLifecycleRegistration
import com.achulkov.evomolior.core.contract.CommitType
import com.achulkov.evomolior.core.contract.ContributionCommit
import com.achulkov.evomolior.core.contract.ContributionAuthorityKind
import com.achulkov.evomolior.core.contract.ContributionAuthorityRef
import com.achulkov.evomolior.core.contract.CriticDecisionRecord
import com.achulkov.evomolior.core.contract.CriticOutcome
import com.achulkov.evomolior.core.contract.CriticReview
import com.achulkov.evomolior.core.contract.CostLedgerEntryKind
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
import com.achulkov.evomolior.core.council.LocalNodeKey
import com.achulkov.evomolior.core.deliverable.AcceptanceStatus
import com.achulkov.evomolior.core.deliverable.ExecutionCompletionStatus
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
import com.achulkov.evomolior.substrate.atlas.AtlasTransactionRepository
import com.achulkov.evomolior.substrate.atlas.NodeCaptureRepository
import com.achulkov.evomolior.substrate.db.entity.KnowledgeBaseEntryEntity
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import com.achulkov.evomolior.substrate.grader.GraderAuthorityRepository
import com.achulkov.evomolior.substrate.cost.CostLedgerRepository
import com.achulkov.evomolior.substrate.publication.PublicationOutcomeRepository
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
import java.io.ByteArrayOutputStream
import java.lang.reflect.InvocationTargetException
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
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

    private companion object {
        const val ACCEPTED_PUBLICATION_MARKER = "receiver-delivery-accepted"
        val ACCEPTANCE_BOUNDARY_DISTRIBUTION_HASH =
            AtlasHashing.hashText("acceptance-boundary-distribution")
        val ACCEPTED_DELIVERABLE_COMPONENT_IDS = listOf(
            "executive-summary",
            "decision-matrix",
            "recommendation",
            "evidence-ledger",
            "assumptions",
            "uncertainties",
            "risk-register",
            "implementation-plan",
            "action-checklist",
            "tool-and-simulation-results",
            "alternative-views",
            "limitations-and-next-evidence",
            "technical-appendix",
        )
        val ACCEPTED_SUITE_MARKERS = listOf(
            "accepted-delivery-marker",
            "accepted-decision-marker",
            "accepted-evidence-marker",
            "accepted-hypothesis-marker",
            "accepted-validation-marker",
            "accepted-tool-marker",
        )
    }

    @Test
    fun `unresolved scoring publishes every group in a multi source provisional archive`() = runBlocking {
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
            genesisCouncilRunner = {
                    callbackRunId,
                    callbackObjective,
                    _,
                    envelope,
                    inputArtifacts,
                    _,
                    onInvocationStarted ->
                onInvocationStarted()
                compiledCouncilTestFixture(
                    runId = callbackRunId,
                    objective = callbackObjective,
                    envelope = envelope,
                    inputArtifacts = inputArtifacts,
                    workerGroupKeys = listOf("group-0", "group-1"),
                    workerProfiles = listOf(
                        GroupProfileType.Library,
                        GroupProfileType.Factory
                    )
                )
            },
            metaPlannerRunner = MetaPlannerRunner(),
            roundHeartbeatIntervalMs = 1L,
            inspectorCheckIntervalMs = 1L
        )

        val eventCollector = launch(start = CoroutineStart.UNDISPATCHED) {
            service.events.collect { event ->
                collectedEvents += event
            }
        }
        val terminalEvent = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(15_000) {
                service.events.first { event ->
                    when (event) {
                        is EvoMoliorEvent.RunRecovered -> event.runId == runId
                        is EvoMoliorEvent.RunCompleted -> event.runId == runId
                        is EvoMoliorEvent.RunAborted -> event.runId == runId
                        else -> false
                    }
                }
            }
        }

        val config = RunConfig(
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
            openRouterApiKey = "sk-test"
        )
        service.startRun(config)

        val recovered = assertIs<EvoMoliorEvent.RunRecovered>(terminalEvent.await())
        assertEquals(
            EngineStatus.IDLE,
            service.getState().status,
            "RunRecovered must not be observable until run-scoped cleanup has finished."
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
            actualWorkerRoundsByGroup.values.any { rounds -> rounds.containsAll(setOf(0, 1)) },
            "The two-round production run must persist ordinary worker registrations in both rounds: " +
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
        assertFalse(collectedEvents.any { it is EvoMoliorEvent.RunCompleted })
        assertTrue(
            service.getMetaArchiveEntries(
                runId,
                RunArchiveService.ENTRY_RUN_COMPLETED
            ).isEmpty()
        )
        assertTrue(recovered.reason.isNotBlank())
        val provisional = RunOutcomeArchiveService().verifyProvisional(
            assertNotNull(service.getResultsBundle(runId)),
        )
        assertEquals(
            workerGroupIds.toSet(),
            provisional.recoverySourceSets.single().sourceGroupIds.toSet(),
        )
        assertNotNull(provisional.outcomeManifest.metricsHash)
        Unit
    }

    @Test
    fun `start run emits lifecycle events and exposes provisional state and archive summary`() = runBlocking {
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
            genesisCouncilRunner = {
                    callbackRunId,
                    callbackObjective,
                    _,
                    envelope,
                    inputArtifacts,
                    _,
                    onInvocationStarted ->
                onInvocationStarted()
                compiledCouncilTestFixture(
                    runId = callbackRunId,
                    objective = callbackObjective,
                    envelope = envelope,
                    inputArtifacts = inputArtifacts,
                    workerGroupKeys = listOf("group-0", "group-1"),
                    workerProfiles = listOf(
                        GroupProfileType.Library,
                        GroupProfileType.Factory,
                    ),
                )
            },
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

        val terminalEvent = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(10_000) {
                service.events.first { event ->
                    when (event) {
                        is EvoMoliorEvent.RunAborted -> event.runId == runId
                        is EvoMoliorEvent.RunRecovered -> event.runId == runId
                        is EvoMoliorEvent.RunCompleted -> event.runId == runId
                        else -> false
                    }
                }
            }
        }
        val returnedRunId = service.startRun(
            RunConfig(
                runId = runId,
                objective = "end-to-end lifecycle flow",
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

        val recovered = assertIs<EvoMoliorEvent.RunRecovered>(terminalEvent.await())
        assertTrue(recovered.reason.isNotBlank())

        eventCollector.cancel()

        assertTrue(
            collectedEvents.any { it is EvoMoliorEvent.RunStarted && it.runId == runId },
            "The live event stream should include RunStarted"
        )
        assertTrue(
            collectedEvents.any { it is EvoMoliorEvent.RoundStarted && it.runId == runId && it.roundIndex == 0 },
            "The live event stream should include the first round"
        )
        assertFalse(collectedEvents.any { it is EvoMoliorEvent.RunCompleted })

        val state = service.getState()
        assertEquals(EngineStatus.IDLE, state.status)
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
        val runCompletedEntries = service.getMetaArchiveEntries(runId, RunArchiveService.ENTRY_RUN_COMPLETED)
        assertTrue(runCompletedEntries.isEmpty(), "A provisional run must not expose accepted completion authority")
        val decodedStartingField = json.decodeFromString(StartingField.serializer(), startingFieldEntry.contentJson)
        assertEquals(1, decodedStartingField.inputArtifacts.size)
        assertEquals("docs/context.txt", decodedStartingField.inputArtifacts.single().relativePath)
        assertTrue(decodedStartingField.inputArtifacts.single().textPreview.orEmpty().contains("starting context document"))

        assertEquals(
            com.achulkov.evomolior.core.contract.RecoveryPublicationState.entries,
            PublicationOutcomeRepository(substrateFactory.getAtlas(runId))
                .recoveryHistory(runId)
                .map { it.state },
        )
        val provisional = RunOutcomeArchiveService().verifyProvisional(
            assertNotNull(service.getResultsBundle(runId)),
        )
        assertNotNull(provisional.outcomeManifest.metricsHash)
        Unit
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

        val terminal = async(start = CoroutineStart.UNDISPATCHED) {
            awaitRunTerminal(service, runId, 10_000L)
        }
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
                isTestRun = true,
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
    fun `single round lifecycle emits summary and provisional publication in canonical order`() = runBlocking {
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
            genesisCouncilRunner = {
                    callbackRunId,
                    callbackObjective,
                    _,
                    envelope,
                    inputArtifacts,
                    _,
                    onInvocationStarted ->
                onInvocationStarted()
                compiledCouncilTestFixture(
                    runId = callbackRunId,
                    objective = callbackObjective,
                    envelope = envelope,
                    inputArtifacts = inputArtifacts,
                    workerGroupKeys = listOf("group-0", "group-1"),
                    workerProfiles = listOf(
                        GroupProfileType.Library,
                        GroupProfileType.Factory,
                    ),
                )
            },
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

        val terminal = async(start = CoroutineStart.UNDISPATCHED) {
            awaitRunTerminal(service, runId, 10_000L)
        }
        service.startRun(
            RunConfig(
                runId = runId,
                objective = "event ordering regression",
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
                isTestRun = true,
            )
        )

        val terminalEvent = terminal.await()
        if (terminalEvent is EvoMoliorEvent.RunAborted) {
            error(terminalEvent.reason)
        }
        assertIs<EvoMoliorEvent.RunRecovered>(terminalEvent)
        eventCollector.cancel()

        val roundStartedIndex = collectedEvents.indexOfFirst {
            it is EvoMoliorEvent.RoundStarted && it.runId == runId && it.roundIndex == 0
        }
        val runRecoveredIndex = collectedEvents.indexOfFirst {
            it is EvoMoliorEvent.RunRecovered && it.runId == runId
        }

        assertTrue(roundStartedIndex >= 0, "RoundStarted should be emitted")
        assertTrue(runRecoveredIndex >= 0, "RunRecovered should be emitted")
        assertTrue(roundStartedIndex < runRecoveredIndex)
        assertFalse(collectedEvents.any { it is EvoMoliorEvent.RunCompleted })
        assertFalse(
            collectedEvents.any {
                it is EvoMoliorEvent.EngineProgress &&
                    it.runId == runId &&
                    it.phase == "final_answer"
            }
        )
        assertEquals(
            1,
            collectedEvents.count { it is EvoMoliorEvent.RoundCompleted && it.runId == runId }
        )
        assertEquals(
            com.achulkov.evomolior.core.contract.RecoveryPublicationState.entries,
            PublicationOutcomeRepository(substrateFactory.getAtlas(runId))
                .recoveryHistory(runId)
                .map { it.state },
        )
        val provisional = RunOutcomeArchiveService().verifyProvisional(
            assertNotNull(service.getResultsBundle(runId)),
        )
        assertNotNull(provisional.outcomeManifest.metricsHash)
        Unit
    }

    @Test
    fun `continuation run clones recovered StartingField and merges new context artifacts`() = runBlocking {
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
        val sourceTerminal = async(start = CoroutineStart.UNDISPATCHED) {
            awaitRunTerminal(service, firstRunId, 10_000L)
        }
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
        val sourceRecovered = assertIs<EvoMoliorEvent.RunRecovered>(sourceTerminal.await())
        assertTrue(sourceRecovered.reason.isNotBlank())
        withTimeout(10_000) {
            while (service.getState().activeRunId != null) {
                kotlinx.coroutines.yield()
            }
        }
        assertTrue(
            service.getMetaArchiveEntries(
                firstRunId,
                RunArchiveService.ENTRY_RUN_COMPLETED
            ).isEmpty()
        )

        eventCollector.cancel()

        val metaArchiveDb = substrateFactory.get(firstRunId, "__meta_archive__")
        val startingFieldEntry = metaArchiveDb.metaArchiveDao().getByType("STARTING_FIELD")
            .first { it.runId == firstRunId }
        val decodedStartingField = json.decodeFromString(StartingField.serializer(), startingFieldEntry.contentJson)

        assertEquals(null, decodedStartingField.continuedFromRunId)
        assertEquals("seed run objective", decodedStartingField.objective)
        assertEquals(1, decodedStartingField.groupCount)
        assertEquals(GroupProfileType.Library, decodedStartingField.groupProfiles["group-0"])
        assertTrue(decodedStartingField.inputArtifacts.any { it.relativePath == "docs/source.txt" })
        val provisional = RunOutcomeArchiveService().verifyProvisional(
            assertNotNull(service.getResultsBundle(firstRunId)),
        )
        assertEquals(firstRunId, provisional.runId)
        assertNotNull(provisional.outcomeManifest.metricsHash)
        Unit
    }

    @Test
    fun `browser deliverable archive prefers dedicated final writer html over summary fallback html`() = runBlocking {
        val tmpDir = createTempDirectory("svc-final-deliverable-flow-test-").toFile()
        val substrateFactory = SubstrateDatabaseFactory(tmpDir)
        val runId = "run-final-deliverable"
        val finalWriterAttempts = AtomicInteger(0)
        val finalWriterModels = ConcurrentLinkedQueue<String>()
        val sawRetryPrompt = AtomicInteger(0)
        val writerPhases = ConcurrentLinkedQueue<String>()

        val service = acceptanceBoundaryService(
            substrateFactory = substrateFactory,
            failingBoundary = null,
            archiveCalls = AtomicInteger(),
            localAgentRunnerOverride = { bootContext: AgentBootContext, _ ->
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
                                            <body><!-- ${ACCEPTED_SUITE_MARKERS.joinToString(" ")} --><article><h1>Summary Fallback Article</h1><p>summary path</p></article></body>
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
                                                  <!-- ${ACCEPTED_SUITE_MARKERS.joinToString(" ")} -->
                                                  <article>
                                                    <header><h1>Dedicated Final Writer Article</h1></header>
                                                    <section><p>$ACCEPTED_PUBLICATION_MARKER publication path</p></section>
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
                                    insertAcceptedFinalWriterLineageAndDeathNote(
                                        substrateFactory = substrateFactory,
                                        database = db,
                                        bootContext = bootContext,
                                        outputRelativePath = "final-article.html",
                                        lineageSubmittedAtMs = now + 1L,
                                        deathNoteSubmittedAtMs = now + 2L,
                                    )
                                }
                            }
                        }
            },
        )

        val phaseCollector = launch(start = CoroutineStart.UNDISPATCHED) {
            service.events.collect { event ->
                if (
                    event is EvoMoliorEvent.EngineProgress &&
                    event.runId == runId &&
                    event.phase in setOf(
                        "main_writer_spawn",
                        "final_deliverable_spawn",
                        "recovery_deliverable_spawn",
                    )
                ) {
                    writerPhases += event.phase
                }
            }
        }
        val terminal = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(30_000L) {
                service.events.first { event ->
                    when (event) {
                        is EvoMoliorEvent.RunCompleted -> event.runId == runId
                        is EvoMoliorEvent.RunRecovered -> event.runId == runId
                        is EvoMoliorEvent.RunAborted -> event.runId == runId
                        else -> false
                    }
                }
            }
        }
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
                finalOutputSpec = acceptedFinalOutputSpec(
                    relativePath = "final-article.html",
                    mediaType = "text/html",
                    kind = FinalOutputKind.HTML_ARTICLE,
                ),
            )
        )

        val terminalEvent = terminal.await()
        phaseCollector.cancel()
        assertIs<EvoMoliorEvent.RunCompleted>(terminalEvent)
        assertCompleteAcceptedSnapshotAuthority(substrateFactory, tmpDir, runId)
        assertEquals(2, finalWriterAttempts.get())
        assertEquals(2, finalWriterModels.size)
        assertEquals(0, sawRetryPrompt.get())
        assertEquals(listOf("main_writer_spawn", "final_deliverable_spawn"), writerPhases.toList())
        assertTrue(
            acceptedWriterDocs(substrateFactory, tmpDir, runId)
                .any { "Dedicated Final Writer Article" in it },
        )
        assertNotNull(service.getResultsBundle(runId))
        Unit
    }

    @Test
    fun `browser final writer retries html that violates objective minimum word count`() = runBlocking {
        val tmpDir = createTempDirectory("svc-final-deliverable-min-words-test-").toFile()
        val substrateFactory = SubstrateDatabaseFactory(tmpDir)
        val runId = "run-final-deliverable-min-words"
        val finalWriterAttempts = AtomicInteger(0)
        val finalWriterModels = ConcurrentLinkedQueue<String>()

        val service = acceptanceBoundaryService(
            substrateFactory = substrateFactory,
            failingBoundary = null,
            archiveCalls = AtomicInteger(),
            localAgentRunnerOverride = { bootContext: AgentBootContext, _ ->
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
                                            FINAL DELIVERABLE HTML
                                            <!doctype html>
                                            <html lang="en">
                                            <head><meta charset="utf-8"><title>Minimum Words Fallback</title></head>
                                            <body><!-- ${ACCEPTED_SUITE_MARKERS.joinToString(" ")} --><main><article>
                                            <h1>Minimum Words Fallback</h1>
                                            <p>This accepted fallback contains enough visible receiver-facing
                                            words to satisfy the objective while preserving deterministic
                                            evidence, assumptions, uncertainty, risks, actions, and limitations.</p>
                                            </article></main></body>
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
                                            <body><!-- ${ACCEPTED_SUITE_MARKERS.joinToString(" ")} --><main><article><h1>Minimum Words Article</h1><p>$ACCEPTED_PUBLICATION_MARKER $body</p></article></main></body>
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
                                if (attempt > 1) {
                                    insertAcceptedFinalWriterLineageAndDeathNote(
                                        substrateFactory = substrateFactory,
                                        database = db,
                                        bootContext = bootContext,
                                        outputRelativePath = "final-article.html",
                                        lineageSubmittedAtMs = now + 1L,
                                        deathNoteSubmittedAtMs = now + 2L,
                                    )
                                }
                            }
                        }
            },
        )

        val terminal = async(start = CoroutineStart.UNDISPATCHED) {
            awaitRunTerminal(service, runId, 30_000L)
        }
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
                finalOutputSpec = acceptedFinalOutputSpec(
                    relativePath = "final-article.html",
                    mediaType = "text/html",
                    kind = FinalOutputKind.HTML_ARTICLE,
                ),
            )
        )

        val terminalEvent = terminal.await()
        assertCompleteAcceptedSnapshotAuthority(substrateFactory, tmpDir, runId)
        assertIs<EvoMoliorEvent.RunCompleted>(terminalEvent)
        assertEquals(2, finalWriterAttempts.get())
        assertEquals(2, finalWriterModels.size)
        assertTrue(
            acceptedWriterDocs(substrateFactory, tmpDir, runId)
                .any { "twentyone twentytwo" in it },
        )
        assertNotNull(service.getResultsBundle(runId))
        Unit
    }

    @Test
    fun `browser final writer retries source grounded html without evidence notes`() = runBlocking {
        val tmpDir = createTempDirectory("svc-final-deliverable-evidence-test-").toFile()
        val substrateFactory = SubstrateDatabaseFactory(tmpDir)
        val runId = "run-final-deliverable-evidence"
        val finalWriterAttempts = AtomicInteger(0)

        val service = acceptanceBoundaryService(
            substrateFactory = substrateFactory,
            failingBoundary = null,
            archiveCalls = AtomicInteger(),
            localAgentRunnerOverride = { bootContext: AgentBootContext, _ ->
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
                                            FINAL DELIVERABLE HTML
                                            <!doctype html>
                                            <html lang="en">
                                            <head><meta charset="utf-8"><title>Evidence Strategy Fallback</title></head>
                                            <body><!-- ${ACCEPTED_SUITE_MARKERS.joinToString(" ")} --><main><article>
                                            <h1>Evidence Strategy Fallback</h1>
                                            <section><h2>Source-grounded evidence notes</h2>
                                            <p>The accepted fallback distinguishes verified source evidence
                                            from inference and names the next validation experiment.</p></section>
                                            </article></main></body>
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
                                            <body><!-- ${ACCEPTED_SUITE_MARKERS.joinToString(" ")} --><main><article><h1>Evidence Strategy</h1><p>$ACCEPTED_PUBLICATION_MARKER</p>$evidenceSection</article></main></body>
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
                                if (attempt > 1) {
                                    insertAcceptedFinalWriterLineageAndDeathNote(
                                        substrateFactory = substrateFactory,
                                        database = db,
                                        bootContext = bootContext,
                                        outputRelativePath = "final-article.html",
                                        lineageSubmittedAtMs = now + 1L,
                                        deathNoteSubmittedAtMs = now + 2L,
                                    )
                                }
                            }
                        }
            },
        )

        val terminal = async(start = CoroutineStart.UNDISPATCHED) {
            awaitRunTerminal(service, runId, 30_000L)
        }
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
                finalOutputSpec = acceptedFinalOutputSpec(
                    relativePath = "final-article.html",
                    mediaType = "text/html",
                    kind = FinalOutputKind.HTML_ARTICLE,
                ),
            )
        )

        assertIs<EvoMoliorEvent.RunCompleted>(terminal.await())
        assertCompleteAcceptedSnapshotAuthority(substrateFactory, tmpDir, runId)
        assertEquals(2, finalWriterAttempts.get())
        assertTrue(
            acceptedWriterDocs(substrateFactory, tmpDir, runId)
                .any { "Source-grounded evidence notes" in it },
        )
        assertNotNull(service.getResultsBundle(runId))
        Unit
    }

    @Test
    fun `final writer coroutine timeout recovers before outer benchmark timeout`() = runBlocking {
        val tmpDir = createTempDirectory("svc-final-deliverable-timeout-test-").toFile()
        val substrateFactory = SubstrateDatabaseFactory(tmpDir)
        val runId = "run-final-deliverable-timeout"
        val finalWriterAttempts = AtomicInteger(0)
        val runConfig = acceptanceBoundaryConfig(runId, substrateFactory).copy(
            objective = "Produce the final benchmark prediction JSON.",
            councilExecutionPurpose = CouncilExecutionPurpose.STANDARD,
            acceptanceProfile = AcceptanceProfile.NORMAL,
            llmBackend = LlmBackend.OPENROUTER,
            finalOutputSpec = FinalOutputSpec(
                relativePath = "tier0-prediction.json",
                mediaType = "application/json",
                description = "Tier-0 benchmark prediction JSON",
                kind = FinalOutputKind.GENERIC_FILE,
            ),
            isTestRun = false,
        )

        val service = acceptanceBoundaryService(
            substrateFactory = substrateFactory,
            failingBoundary = null,
            archiveCalls = AtomicInteger(),
            finalDeliverableAttemptTimeoutMsOverride = 1_000L,
            localAgentRunnerOverride = { bootContext, _ ->
                if (bootContext.role == Role.Archivist &&
                    bootContext.rolePrompt.contains("Round Submission Summariser")
                ) {
                    val database = substrateFactory.get(bootContext.runId, bootContext.groupId)
                    val now = System.currentTimeMillis()
                    database.stagingDao().insert(
                        StagingSubmissionEntity(
                            id = "timeout-summary-${bootContext.groupId}-$now",
                            agentId = bootContext.agentId,
                            role = bootContext.role.name,
                            type = "PLAN_UPDATE",
                            content = """
                                ROUND SUBMISSION SUMMARY
                                {
                                  "authorityMarker": "$ACCEPTED_PUBLICATION_MARKER",
                                  "sealedTestMarkers": "${ACCEPTED_SUITE_MARKERS.joinToString(" ")}"
                                }
                            """.trimIndent(),
                            budgetSignature = "test-timeout-summary",
                            status = "pending",
                            generation = bootContext.generation,
                            groupId = bootContext.groupId,
                            runId = bootContext.runId,
                            submittedAtMs = now,
                        ),
                    )
                } else if (bootContext.role == Role.Builder &&
                    bootContext.rolePrompt.contains("Final Deliverable Writer")
                ) {
                    finalWriterAttempts.incrementAndGet()
                    withTimeout(25L) { delay(60_000L) }
                }
            },
        )

        val terminal = async(start = CoroutineStart.UNDISPATCHED) {
            awaitRunTerminalIncludingProvisional(service, runId, 25_000L)
        }
        service.startRun(runConfig)

        val terminalEvent = terminal.await()
        if (terminalEvent is EvoMoliorEvent.RunAborted) {
            error(terminalEvent.reason)
        }
        val terminalReason = when (terminalEvent) {
            is EvoMoliorEvent.RunRecovered -> terminalEvent.reason
            is EvoMoliorEvent.RunCompletedProvisional -> terminalEvent.reason
            else -> error("Unexpected timeout terminal event: $terminalEvent")
        }
        assertTrue(terminalReason.isNotBlank())
        assertTrue(finalWriterAttempts.get() > 0)
        assertEquals(EngineStatus.IDLE, service.getState().status)
        assertTrue(service.getMetaArchiveEntries(runId, RunArchiveService.ENTRY_RUN_COMPLETED).isEmpty())
        val publication = PublicationStateRepository(substrateFactory.getAtlas(runId))
        assertTrue(publication.history(runId).isEmpty())
        assertEquals(
            com.achulkov.evomolior.core.contract.RecoveryPublicationState.entries,
            PublicationOutcomeRepository(substrateFactory.getAtlas(runId))
                .recoveryHistory(runId)
                .map { it.state }
        )
        val provisional = RunOutcomeArchiveService().verifyProvisional(
            assertNotNull(service.getResultsBundle(runId)),
        )
        assertNotNull(provisional.outcomeManifest.metricsHash)
        Unit
    }

    @Test
    fun `later round closure failure publishes provisional package from last archived round`() =
        runBlocking {
            val tmpDir = createTempDirectory("svc-later-round-recovery-test-").toFile()
            val substrateFactory = SubstrateDatabaseFactory(tmpDir)
            val runId = "run-later-round-recovery"
            val snapshotCompilationRounds = ConcurrentLinkedQueue<Int>()
            val service = acceptanceBoundaryService(
                substrateFactory = substrateFactory,
                failingBoundary = null,
                archiveCalls = AtomicInteger(),
                beforeRoundSnapshotCompilation = { callbackRunId, round ->
                    assertEquals(runId, callbackRunId)
                    snapshotCompilationRounds += round
                    if (round == 1) {
                        error("forced private round snapshot closure failure")
                    }
                },
            )
            val terminal = async(start = CoroutineStart.UNDISPATCHED) {
                awaitRunTerminalIncludingProvisional(service, runId, 60_000L)
            }

            service.startRun(
                acceptanceBoundaryConfig(runId, substrateFactory).copy(
                    rounds = 2,
                    councilExecutionPurpose = CouncilExecutionPurpose.STANDARD,
                    acceptanceProfile = AcceptanceProfile.NORMAL,
                )
            )

            val recovered = assertIs<EvoMoliorEvent.RunCompletedProvisional>(terminal.await())
            val outcome = assertNotNull(recovered.terminalOutcome)
            assertEquals(ExecutionCompletionStatus.COMPLETED, outcome.executionStatus)
            assertEquals(AcceptanceStatus.PROVISIONAL, outcome.acceptanceStatus)
            assertEquals(null, outcome.acceptedWinnerId)
            assertEquals(EngineStatus.IDLE, service.getState().status)
            assertEquals(null, recovered.sourceGroupId)
            assertEquals(listOf(0, 1), snapshotCompilationRounds.toList())
            assertEquals(
                "Run stopped after 1 fully archived round; " +
                    "accepted publication authority was unavailable",
                recovered.reason,
            )
            assertFalse("forced private" in recovered.reason)

            val persistedState = assertNotNull(PersistedRunStateStore.read(tmpDir, runId))
            assertEquals(PersistedRunStatus.COMPLETED, persistedState.status)
            assertEquals(1, persistedState.nextRoundIndex)
            assertEquals(null, persistedState.activeRoundIndex)

            val publication = PublicationStateRepository(substrateFactory.getAtlas(runId))
            assertTrue(publication.history(runId).isEmpty())
            assertTrue(publication.finalCandidates(runId).isEmpty())
            val outcomes = PublicationOutcomeRepository(substrateFactory.getAtlas(runId))
            assertTrue(outcomes.acceptedHistory(runId).isEmpty())
            assertEquals(
                com.achulkov.evomolior.core.contract.RecoveryPublicationState.entries,
                outcomes.recoveryHistory(runId).map { it.state },
            )
            assertTrue(
                service.getMetaArchiveEntries(
                    runId,
                    RunArchiveService.ENTRY_RUN_COMPLETED,
                ).isEmpty()
            )

            val provisional = RunOutcomeArchiveService().verifyProvisional(
                assertNotNull(service.getResultsBundle(runId)),
            )
            assertNotNull(provisional.outcomeManifest.metricsHash)
            val deliverables =
                com.achulkov.evomolior.substrate.deliverable.DeliverablePersistenceRepository(
                    substrateFactory.getAtlas(runId)
                )
            val groupIds = persistedState.groupIds.sorted()
            val roundZeroSnapshots = groupIds.map { groupId ->
                assertNotNull(deliverables.snapshot(runId, groupId, 0))
            }
            assertEquals(
                roundZeroSnapshots.map { it.snapshotHash },
                provisional.recoverySourceSets.single().sourceSnapshotHashes,
            )
            val roundZeroHistory =
                com.achulkov.evomolior.substrate.roundledger.RoundPhaseLedgerRepository(
                    substrateFactory.getAtlas(runId)
                ).history(runId, 0)
            val expectedWorkerAtlasHash = roundZeroHistory
                .single {
                    it.phase ==
                        com.achulkov.evomolior.core.contract.RoundPhaseV1
                            .MUTATION_CAPTURE_YIELD_DISCLOSURE_AND_OUTLIER_RESOLVED
                }
                .authorityReferenceIds
                .single { it.startsWith("post-mutation-atlas:") }
                .removePrefix("post-mutation-atlas:")
            assertEquals(
                expectedWorkerAtlasHash,
                provisional.outcomeManifest.workerAtlasHash,
            )
            assertTrue(groupIds.all { deliverables.snapshot(runId, it, 1) == null })
        }

    @Test
    fun `failed later round recovery does not expose private failure text`() = runBlocking {
        val tmpDir = createTempDirectory("svc-later-round-recovery-failure-test-").toFile()
        val substrateFactory = SubstrateDatabaseFactory(tmpDir)
        val runId = "run-later-round-recovery-failure"
        val service = acceptanceBoundaryService(
            substrateFactory = substrateFactory,
            failingBoundary = null,
            archiveCalls = AtomicInteger(),
            archiveServiceOverride = FailingProvisionalArchiveService(substrateFactory),
            beforeRoundSnapshotCompilation = { _, round ->
                if (round == 1) error("private round snapshot failure path=/secret")
            },
        )
        val terminal = async(start = CoroutineStart.UNDISPATCHED) {
            awaitRunTerminal(service, runId, 60_000L)
        }

        service.startRun(
            acceptanceBoundaryConfig(runId, substrateFactory).copy(
                rounds = 2,
                councilExecutionPurpose = CouncilExecutionPurpose.STANDARD,
                acceptanceProfile = AcceptanceProfile.NORMAL,
            )
        )

        val aborted = assertIs<EvoMoliorEvent.RunAborted>(terminal.await())
        assertEquals(
            "Run failed after archived work; provisional publication was unavailable",
            aborted.reason,
        )
        assertFalse("private" in aborted.reason)
        assertFalse("secret" in aborted.reason)
        assertEquals(null, service.getResultsBundle(runId))
        assertEquals(
            PersistedRunStatus.FAILED,
            PersistedRunStateStore.read(tmpDir, runId)?.status,
        )
    }

    @Test
    fun `pre archive accepted state tail aborts and fails closed on resume`() = runBlocking {
        val tmpDir = createTempDirectory("svc-pre-archive-failure-test-").toFile()
        val substrateFactory = SubstrateDatabaseFactory(tmpDir)
        val runId = "run-pre-archive-failure"
        val collectedEvents = ConcurrentLinkedQueue<EvoMoliorEvent>()
        val service = acceptanceBoundaryService(
            substrateFactory = substrateFactory,
            failingBoundary = null,
            archiveCalls = AtomicInteger(),
            archiveServiceOverride = FailingArchiveService(substrateFactory),
        )

        val eventCollector = launch(start = CoroutineStart.UNDISPATCHED) {
            service.events.collect { event ->
                collectedEvents += event
            }
        }

        val runConfig = acceptanceBoundaryConfig(runId, substrateFactory)
        val terminal = async(start = CoroutineStart.UNDISPATCHED) {
            awaitRunTerminal(service, runId, 60_000L)
        }
        service.startRun(runConfig)

        val aborted = assertIs<EvoMoliorEvent.RunAborted>(terminal.await())

        eventCollector.cancel()

        assertTrue(
            aborted.reason.contains("forced accepted metrics compilation failure"),
            aborted.reason,
        )
        assertFalse(
            collectedEvents.any { it is EvoMoliorEvent.RunCompleted && it.runId == runId },
            "RunCompleted must not be emitted after deterministic archive enforcement fails."
        )
        assertFalse(collectedEvents.any { it is EvoMoliorEvent.RunRecovered })
        val publication = PublicationStateRepository(substrateFactory.getAtlas(runId))
        val acceptedHistory = PublicationOutcomeRepository(
            substrateFactory.getAtlas(runId)
        ).acceptedHistory(runId)
        assertEquals(publication.history(runId), acceptedHistory)
        assertTrue(
            service.getMetaArchiveEntries(
                runId,
                RunArchiveService.ENTRY_RUN_COMPLETED
            ).isNotEmpty()
        )
        assertTrue(service.getMetaArchiveEntries(
            runId,
            RunArchiveService.ENTRY_RUN_RECOVERED
        ).isEmpty())
        assertEquals(null, service.getResultsBundle(runId))
        assertEquals(
            PersistedRunStatus.FAILED,
            PersistedRunStateStore.read(tmpDir, runId)?.status
        )
        assertEquals(EngineStatus.IDLE, service.getState().status)

        assertTrue(
            PublicationOutcomeRepository(substrateFactory.getAtlas(runId))
                .recoveryHistory(runId)
                .isEmpty(),
        )
    }

    @Test
    fun `renderer failure retains semantic package without terminal publication`() = runBlocking {
        val tmpDir = createTempDirectory("svc-renderer-package-persistence-test-").toFile()
        val substrateFactory = SubstrateDatabaseFactory(tmpDir)
        val runId = "run-renderer-package-persistence"
        val archiveService = RendererFailingArchiveService(substrateFactory)
        val service = acceptanceBoundaryService(
            substrateFactory = substrateFactory,
            failingBoundary = null,
            archiveCalls = AtomicInteger(),
            archiveServiceOverride = archiveService,
        )
        val terminal = async(start = CoroutineStart.UNDISPATCHED) {
            awaitRunTerminal(service, runId, 60_000L)
        }
        service.startRun(acceptanceBoundaryConfig(runId, substrateFactory))

        val aborted = assertIs<EvoMoliorEvent.RunAborted>(terminal.await())

        assertTrue(
            aborted.reason.contains("forced accepted renderer compilation failure"),
            aborted.reason,
        )
        val persistedPackages = com.achulkov.evomolior.substrate.deliverable
            .DeliverablePersistenceRepository(substrateFactory.getAtlas(runId))
            .decisionPackages(runId)
        assertEquals(1, persistedPackages.size)
        assertNotNull(persistedPackages.single().acceptedAuthority)
        assertEquals(persistedPackages.single(), archiveService.attemptedPackage.get())
        assertEquals(null, service.getResultsBundle(runId))
        val publicationHistory = PublicationStateRepository(
            substrateFactory.getAtlas(runId)
        ).history(runId)
        val outcomeHistory = PublicationOutcomeRepository(
            substrateFactory.getAtlas(runId)
        ).acceptedHistory(runId)
        assertEquals(publicationHistory, outcomeHistory)
        assertTrue(publicationHistory.none {
            it.state in setOf(
                PublicationState.METRICS_EXPORTED,
                PublicationState.MANIFEST_HASHED,
                PublicationState.ARCHIVED,
            )
        })
        assertTrue(
            PublicationOutcomeRepository(substrateFactory.getAtlas(runId))
                .recoveryHistory(runId)
                .isEmpty(),
        )
        assertEquals(
            PersistedRunStatus.FAILED,
            PersistedRunStateStore.read(tmpDir, runId)?.status,
        )
    }

    @Test
    fun `accepted archive survives a post archive metrics persistence failure`() = runBlocking {
        val tmpDir = createTempDirectory("svc-post-archive-persistence-test-").toFile()
        val substrateFactory = SubstrateDatabaseFactory(tmpDir)
        val runId = "run-post-archive-persistence"
        val collectedEvents = ConcurrentLinkedQueue<EvoMoliorEvent>()
        val archiveService = PostArchiveMetricsFailingService(substrateFactory)
        val service = acceptanceBoundaryService(
            substrateFactory = substrateFactory,
            failingBoundary = null,
            archiveCalls = AtomicInteger(),
            archiveServiceOverride = archiveService,
        )
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            service.events.collect { collectedEvents += it }
        }
        val terminal = async(start = CoroutineStart.UNDISPATCHED) {
            awaitRunTerminal(service, runId, 60_000L)
        }

        service.startRun(acceptanceBoundaryConfig(runId, substrateFactory))

        assertIs<EvoMoliorEvent.RunCompleted>(terminal.await())
        delay(25L)
        collector.cancel()

        assertCompleteAcceptedSnapshotAuthority(substrateFactory, tmpDir, runId)
        assertEquals(1, archiveService.forcedFailureCount.get())
        assertTrue(collectedEvents.any { it is EvoMoliorEvent.RunCompleted })
        assertEquals(
            PersistedRunStatus.COMPLETED,
            PersistedRunStateStore.read(tmpDir, runId)?.status
        )
        assertNotNull(service.getResultsBundle(runId))
        Unit
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

        service.startRun(acceptanceBoundaryConfig(runId, substrateFactory))

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
    fun `acceptance final publication boundary failure aborts without terminal authority`() =
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

            service.startRun(acceptanceBoundaryConfig(runId, substrateFactory))

            val terminal = aborted.await()
            assertTrue(
                terminal.reason.contains("forced acceptance boundary failure"),
                terminal.reason,
            )
            val publication = PublicationStateRepository(substrateFactory.getAtlas(runId))
            assertTrue(
                publication.finalCandidates(runId).isEmpty(),
                "Final candidates must not be sealed after final-publication boundary rejection."
            )
            assertTrue(
                publication.history(runId).isEmpty(),
                "No publication authority may exist before a rejected final-publication boundary."
            )
            assertNoNormalCompletionAuthorities(service, runId)
            assertTrue(
                service.getMetaArchiveEntries(
                    runId,
                    RunArchiveService.ENTRY_RUN_RECOVERED
                ).isEmpty()
            )
            assertTrue(
                PublicationOutcomeRepository(substrateFactory.getAtlas(runId))
                    .recoveryHistory(runId)
                    .isEmpty()
            )
            assertEquals(null, service.getResultsBundle(runId))
            assertEquals(
                PersistedRunStatus.FAILED,
                PersistedRunStateStore.read(tmpDir, runId)?.status
            )
            assertEquals(0, archiveCalls.get())
        }

    @Test
    fun `accepted state tail fails closed at the archive boundary without reclassification`() =
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

            service.startRun(acceptanceBoundaryConfig(runId, substrateFactory))

            val terminal = aborted.await()
            assertTrue(
                terminal.reason.contains("forced acceptance boundary failure"),
                terminal.reason,
            )
            val publication = PublicationStateRepository(substrateFactory.getAtlas(runId))
            assertNoNormalCompletionAuthorities(service, runId)
            assertTrue(
                service.getMetaArchiveEntries(
                    runId,
                    RunArchiveService.ENTRY_RUN_RECOVERED
                ).isEmpty()
            )
            val acceptedHistory = PublicationOutcomeRepository(
                substrateFactory.getAtlas(runId)
            ).acceptedHistory(runId)
            assertEquals(publication.history(runId), acceptedHistory)
            assertFalse(
                publication.history(runId).any {
                    it.state == PublicationState.MANIFEST_HASHED ||
                        it.state == PublicationState.ARCHIVED
                },
                "Archive publication states must not be written after archive-boundary rejection."
            )
            assertTrue(
                PublicationOutcomeRepository(substrateFactory.getAtlas(runId))
                    .recoveryHistory(runId)
                    .isEmpty(),
            )
            assertEquals(
                PersistedRunStatus.FAILED,
                PersistedRunStateStore.read(tmpDir, runId)?.status
            )
            assertEquals(null, service.getResultsBundle(runId))
            assertEquals(0, archiveCalls.get(), "Archive service must not run after boundary rejection.")
        }

    @Test
    fun `full diagnostic mode publishes a provisional package without acceptance authority`() =
        runBlocking {
            val tmpDir = createTempDirectory("svc-acceptance-diagnostic-sweep-").toFile()
            val substrateFactory = SubstrateDatabaseFactory(tmpDir)
            val runId = "acceptance-diagnostic-sweep"
            val archiveCalls = AtomicInteger()
            val events = ConcurrentLinkedQueue<EvoMoliorEvent>()
            val service = acceptanceBoundaryService(
                substrateFactory = substrateFactory,
                failingBoundary = null,
                archiveCalls = archiveCalls
            )
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                service.events.collect { events += it }
            }
            val terminal = async(start = CoroutineStart.UNDISPATCHED) {
                awaitRunTerminalIncludingProvisional(service, runId, 60_000L)
            }

            val diagnosticConfig = acceptanceBoundaryConfig(runId, substrateFactory).copy(
                acceptanceDiagnosticMode =
                    AcceptanceDiagnosticMode.FULL_SWEEP_NON_ACCEPTING
            )
            service.startRun(diagnosticConfig)

            val event = assertIs<EvoMoliorEvent.RunCompletedProvisional>(terminal.await())
            val outcome = assertNotNull(event.terminalOutcome)
            assertEquals(ExecutionCompletionStatus.COMPLETED, outcome.executionStatus)
            assertEquals(AcceptanceStatus.PROVISIONAL, outcome.acceptanceStatus)
            assertEquals(null, outcome.acceptedWinnerId)
            collector.cancel()
            assertTrue(
                event.reason.isNotBlank(),
                event.reason,
            )
            assertEquals(0, archiveCalls.get())
            assertFalse(events.any { it is EvoMoliorEvent.RunCompleted })
            assertFalse(
                events.any {
                    it is EvoMoliorEvent.EngineProgress && it.phase == "final_answer"
                }
            )
            assertTrue(
                PublicationStateRepository(substrateFactory.getAtlas(runId))
                    .history(runId)
                    .isEmpty()
            )
            assertNoNormalCompletionAuthorities(service, runId)
            val provisional = RunOutcomeArchiveService().verifyProvisional(
                assertNotNull(service.getResultsBundle(runId)),
            )
            assertNotNull(provisional.outcomeManifest.metricsHash)
            val report = assertNotNull(provisional.diagnosticSweepReport)
            assertEquals(
                AcceptanceDiagnosticGate.entries,
                report.results.map(AcceptanceDiagnosticGateResult::gate),
            )
            assertTrue(
                AcceptanceDiagnosticSweepReport.BUNDLE_PATH in
                    unzipEntries(assertNotNull(service.getResultsBundle(runId))).keys
            )
            Unit
        }

    @Test
    fun `browser deliverable archive survives many late doc entries before the canonical html`() = runBlocking {
        val tmpDir = createTempDirectory("svc-final-deliverable-volume-test-").toFile()
        val substrateFactory = SubstrateDatabaseFactory(tmpDir)
        val runId = "run-final-deliverable-volume"
        val lateDocCount = 180

        val service = acceptanceBoundaryService(
            substrateFactory = substrateFactory,
            failingBoundary = null,
            archiveCalls = AtomicInteger(),
            localAgentRunnerOverride = { bootContext: AgentBootContext, _ ->
                        if (bootContext.role == Role.Archivist &&
                            bootContext.rolePrompt.contains("Round Submission Summariser")
                        ) {
                            val db = substrateFactory.get(bootContext.runId, bootContext.groupId)
                            val now = System.currentTimeMillis()
                            db.stagingDao().insert(
                                StagingSubmissionEntity(
                                    id = "volume-summary-${bootContext.groupId}-$now",
                                    agentId = bootContext.agentId,
                                    role = bootContext.role.name,
                                    type = "PLAN_UPDATE",
                                    content = """
                                        ROUND SUBMISSION SUMMARY
                                        FINAL DELIVERABLE HTML
                                        <!doctype html>
                                        <html lang="en"><head><meta charset="utf-8"><title>Volume fallback</title></head>
                                        <body><!-- ${ACCEPTED_SUITE_MARKERS.joinToString(" ")} -->
                                        <article><h1>Volume fallback</h1><p>$ACCEPTED_PUBLICATION_MARKER</p></article>
                                        </body></html>
                                    """.trimIndent(),
                                    budgetSignature = "test-volume-summary",
                                    status = "pending",
                                    generation = bootContext.generation,
                                    groupId = bootContext.groupId,
                                    runId = bootContext.runId,
                                    submittedAtMs = now,
                                ),
                            )
                        }
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
                                          <!-- ${ACCEPTED_SUITE_MARKERS.joinToString(" ")} -->
                                          <article><h1>High-Volume Final Writer Article</h1><p>$ACCEPTED_PUBLICATION_MARKER</p></article>
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
                            insertAcceptedFinalWriterLineageAndDeathNote(
                                substrateFactory = substrateFactory,
                                database = db,
                                bootContext = bootContext,
                                outputRelativePath = "final-article.html",
                                lineageSubmittedAtMs = baseNow + lateDocCount + 1L,
                                deathNoteSubmittedAtMs = baseNow + lateDocCount + 2L,
                            )
                        }
            },
        )

        val terminal = async(start = CoroutineStart.UNDISPATCHED) {
            awaitRunTerminal(service, runId, 30_000L)
        }
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
                finalOutputSpec = acceptedFinalOutputSpec(
                    relativePath = "final-article.html",
                    mediaType = "text/html",
                    kind = FinalOutputKind.HTML_ARTICLE,
                ),
            )
        )

        val terminalEvent = terminal.await()
        if (terminalEvent is EvoMoliorEvent.RunRecovered) {
            error(terminalEvent.reason)
        }
        assertIs<EvoMoliorEvent.RunCompleted>(terminalEvent)
        assertCompleteAcceptedSnapshotAuthority(substrateFactory, tmpDir, runId)
        val writerDocs = acceptedWriterDocs(substrateFactory, tmpDir, runId)
        assertTrue(writerDocs.any { "High-Volume Final Writer Article" in it })
        assertEquals(lateDocCount, writerDocs.count { it.startsWith("late-doc-noise-") })
        assertNotNull(service.getResultsBundle(runId))
        Unit
    }

    @Test
    fun `non html final writer stores raw json body as generated output file`() = runBlocking {
        val tmpDir = createTempDirectory("svc-json-final-deliverable-flow-test-").toFile()
        val substrateFactory = SubstrateDatabaseFactory(tmpDir)
        val runId = "run-json-final-deliverable"

        val service = acceptanceBoundaryService(
            substrateFactory = substrateFactory,
            failingBoundary = null,
            archiveCalls = AtomicInteger(),
            localAgentRunnerOverride = { bootContext: AgentBootContext, _ ->
                        if (bootContext.role == Role.Archivist &&
                            bootContext.rolePrompt.contains("Round Submission Summariser")
                        ) {
                            val db = substrateFactory.get(bootContext.runId, bootContext.groupId)
                            val now = System.currentTimeMillis()
                            db.stagingDao().insert(
                                StagingSubmissionEntity(
                                    id = "json-summary-${bootContext.groupId}-$now",
                                    agentId = bootContext.agentId,
                                    role = bootContext.role.name,
                                    type = "PLAN_UPDATE",
                                    content = """
                                        ROUND SUBMISSION SUMMARY
                                        {
                                          "authorityMarker": "$ACCEPTED_PUBLICATION_MARKER",
                                          "sealedTestMarkers": "${ACCEPTED_SUITE_MARKERS.joinToString(" ")}",
                                          "componentAuthority": "${ACCEPTED_DELIVERABLE_COMPONENT_IDS.joinToString(" ")}"
                                        }
                                    """.trimIndent(),
                                    budgetSignature = "test-json-summary",
                                    status = "pending",
                                    generation = bootContext.generation,
                                    groupId = bootContext.groupId,
                                    runId = bootContext.runId,
                                    submittedAtMs = now,
                                ),
                            )
                        }
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
                                          "authorityMarker": "$ACCEPTED_PUBLICATION_MARKER",
                                          "sealedTestMarkers": "${ACCEPTED_SUITE_MARKERS.joinToString(" ")}",
                                          "componentAuthority": "${ACCEPTED_DELIVERABLE_COMPONENT_IDS.joinToString(" ")}",
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
                            insertAcceptedFinalWriterLineageAndDeathNote(
                                substrateFactory = substrateFactory,
                                database = db,
                                bootContext = bootContext,
                                outputRelativePath = "briefs/evidence-brief.json",
                                lineageSubmittedAtMs = now + 1L,
                                deathNoteSubmittedAtMs = now + 2L,
                            )
                        }
            },
        )

        val terminal = async(start = CoroutineStart.UNDISPATCHED) {
            awaitRunTerminal(service, runId, 30_000L)
        }
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
                finalOutputSpec = acceptedFinalOutputSpec(
                    relativePath = "briefs/evidence-brief.json",
                    mediaType = "application/json",
                    description = "Machine-readable evidence brief",
                ),
            )
        )

        val terminalEvent = terminal.await()
        if (terminalEvent is EvoMoliorEvent.RunAborted) {
            error(terminalEvent.reason)
        }
        if (terminalEvent is EvoMoliorEvent.RunRecovered) {
            error(terminalEvent.reason)
        }
        assertIs<EvoMoliorEvent.RunCompleted>(terminalEvent)
        assertCompleteAcceptedSnapshotAuthority(substrateFactory, tmpDir, runId)
        assertTrue(
            acceptedWriterDocs(substrateFactory, tmpDir, runId)
                .any { "\"title\": \"ADHD Evidence Brief\"" in it },
        )
        assertNotNull(service.getResultsBundle(runId))
        Unit
    }

    @Test
    fun `final writer output without Death Note blocks accepted publication`() = runBlocking {
        val tmpDir = createTempDirectory("svc-final-writer-missing-death-note-").toFile()
        val substrateFactory = SubstrateDatabaseFactory(tmpDir)
        val runId = "run-final-writer-missing-death-note"
        val groupId = "group-0"
        val writer = AtlasAgentId("final-writer")
        val database = substrateFactory.get(runId, groupId)
        val execution = WorkerExecutionRepository(database)
        execution.registerAgent(
            AgentLifecycleRegistration(
                registrationId = "registration:$runId:$groupId:${writer.value}",
                runId = runId,
                groupId = AtlasGroupId(groupId),
                agentId = writer,
                role = Role.Builder.name,
                generation = 1,
                classification = AgentLifecycleClassification.NON_DAEMON,
                registeredAt = Instant.parse("2026-07-24T10:00:00Z")
            ).withComputedHash()
        )
        execution.recordExit(
            AgentExitRecord(
                exitId = "exit:$runId:$groupId:${writer.value}",
                runId = runId,
                groupId = AtlasGroupId(groupId),
                agentId = writer,
                nodeId = null,
                round = 1,
                finalPhase = AgentExecutionPhase.TERMINATED,
                commitId = null,
                commitType = null,
                acceptedContribution = false,
                acceptedStructuredDeadEnd = false,
                acceptedNoveltyProposal = false,
                degradedExecution = false,
                wasKilled = false,
                exitedAt = Instant.parse("2026-07-24T10:01:00Z")
            )
        )
        val submissionId = "final-output-without-death-note"
        val content = """{"title":"Durable final output"}"""
        database.stagingDao().insert(
            StagingSubmissionEntity(
                id = submissionId,
                agentId = writer.value,
                role = Role.Builder.name,
                type = "DOC",
                content = content,
                budgetSignature = "final-output-authority",
                status = "pending",
                generation = 1,
                groupId = groupId,
                runId = runId,
                submittedAtMs = 1L
            )
        )
        val service = EvoMoliorRunService(
            lifecycleManagerProvider = { _, _, _, _, _ -> error("unused") },
            groupCoordinatorProvider = { groupIds, dimensions ->
                GroupCoordinator(groupIds, dimensions)
            },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = substrateFactory,
            metaPlannerRunner = MetaPlannerRunner()
        )
        val generatedOutput = GeneratedOutputFile(
            relativePath = "receiver/result.json",
            mediaType = "application/json",
            content = content,
            sourceGroupId = groupId,
            sourceAgentId = writer.value,
            sourceSubmissionId = submissionId,
            sourceRole = Role.Builder
        )

        val invocationFailure = assertFailsWith<InvocationTargetException> {
            service::class.declaredFunctions
                .first { it.name == "finalizePublicationQuiescence" }
                .apply { isAccessible = true }
                .callSuspend(service, runId, listOf(groupId), generatedOutput)
        }
        val failure = assertIs<IllegalArgumentException>(invocationFailure.targetException)

        assertTrue(
            failure.message.orEmpty().contains("Cost policy requested for inactive run"),
            failure.message.orEmpty(),
        )
        assertEquals(listOf(writer), execution.exits(runId, groupId).map { it.agentId })
        assertEquals(null, PublicationStateRepository(substrateFactory.getAtlas(runId)).latest(runId))
        assertTrue(
            CostLedgerRepository(substrateFactory.get(runId, "__cost_ledger__"))
                .entriesForRun(runId)
                .isEmpty()
        )
        val outcomes = PublicationOutcomeRepository(substrateFactory.getAtlas(runId))
        assertTrue(outcomes.acceptedHistory(runId).isEmpty())
        assertTrue(outcomes.recoveryHistory(runId).isEmpty())
        assertTrue(
            service.getMetaArchiveEntries(runId, RunArchiveService.ENTRY_RUN_COMPLETED).isEmpty()
        )
        assertFalse(
            File(
                substrateFactory.dbFilePath(runId, groupId).parentFile,
                "results/results.zip"
            ).exists()
        )
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

    private fun acceptedPublicationGeneralTests(): List<CompiledCouncilGeneralTestFixture> =
        listOf(
            "delivery" to NodeKind.DELIVERY,
            "decision" to NodeKind.DECISION,
            "evidence" to NodeKind.EVIDENCE,
            "hypothesis" to NodeKind.HYPOTHESIS,
            "validation" to NodeKind.VALIDATION,
            "tool" to NodeKind.TOOL,
        ).map { (id, kind) ->
            CompiledCouncilGeneralTestFixture(
                testId = "accepted-$id-test",
                nodeKey = LocalNodeKey("accepted-$id"),
                nodeKind = kind,
                acceptedArtifactTypes = setOf(ArtifactType.FINAL_DELIVERABLE),
                expectedText = "accepted-$id-marker",
            )
        }

    private suspend fun insertAcceptedFinalWriterLineageAndDeathNote(
        substrateFactory: SubstrateDatabaseFactory,
        database: com.achulkov.evomolior.substrate.db.EvoMoliorDatabase,
        bootContext: AgentBootContext,
        outputRelativePath: String,
        lineageSubmittedAtMs: Long,
        deathNoteSubmittedAtMs: Long,
    ) {
        val contributionGraph = ContributionGraphRepository(
            database = database,
            atlasDatabase = substrateFactory.getAtlas(bootContext.runId),
        )
        val captureRepository = NodeCaptureRepository(substrateFactory)
        val gradeBundlesByHash = captureRepository.gradeBundlesForRun(bootContext.runId)
            .associateBy(NodeGradeBundle::bundleHash)
        captureRepository.captureDecisionsForRun(bootContext.runId)
            .filter { AtlasGroupId(bootContext.groupId) in it.resultingOwnerGroupIds }
            .forEach { decision ->
                decision.authoritativeBundleHashes.forEach { bundleHash ->
                    gradeBundlesByHash[bundleHash]
                        ?.takeIf { it.groupId.value == bootContext.groupId }
                        ?.let { bundle ->
                            contributionGraph.recordCaptureLineage(
                                decision = decision,
                                bundle = bundle,
                                persistedAtEpochMillis = lineageSubmittedAtMs - 1L,
                            )
                        }
                }
            }
        val selectedNodeIds = contributionGraph
            .finalDeliverableSources(bootContext.runId, bootContext.groupId)
            .mapNotNullTo(sortedSetOf()) { it.nodeId }
        require(selectedNodeIds.isNotEmpty()) {
            "Accepted final-writer fixture requires current captured node authority"
        }
        val selectedNodeIdsJson = selectedNodeIds.joinToString(",") { "\"$it\"" }
        val componentNodeIdsJson = ACCEPTED_DELIVERABLE_COMPONENT_IDS.joinToString(",") {
            "\"$it\":[$selectedNodeIdsJson]"
        }
        database.stagingDao().insert(
            StagingSubmissionEntity(
                id = "accepted-final-lineage-${bootContext.agentId}-$lineageSubmittedAtMs",
                agentId = bootContext.agentId,
                role = bootContext.role.name,
                type = "PLAN_UPDATE",
                content = """
                    $FINAL_COMPONENT_LINEAGE_V1_MARKER
                    {
                      "schemaVersion":"final-component-lineage-v1",
                      "outputRelativePath":"$outputRelativePath",
                      "componentNodeIds":{$componentNodeIdsJson}
                    }
                """.trimIndent(),
                budgetSignature = "accepted-final-lineage",
                status = "pending",
                generation = bootContext.generation,
                groupId = bootContext.groupId,
                runId = bootContext.runId,
                submittedAtMs = lineageSubmittedAtMs,
            )
        )
        insertFinalWriterDeathNote(database, bootContext, deathNoteSubmittedAtMs)
    }

    private fun acceptedFinalOutputSpec(
        relativePath: String,
        mediaType: String,
        description: String = "",
        kind: FinalOutputKind = FinalOutputKind.GENERIC_FILE,
    ): FinalOutputSpec = FinalOutputSpec(
        relativePath = relativePath,
        mediaType = mediaType,
        description = description,
        kind = kind,
        validation = FinalOutputValidationContract(
            requiredMarkdownSectionSignals = ACCEPTED_DELIVERABLE_COMPONENT_IDS,
        ),
    )

    private fun acceptedMarkdownContent(title: String): String = buildString {
        appendLine("# $title")
        appendLine()
        appendLine(ACCEPTED_PUBLICATION_MARKER)
        ACCEPTED_SUITE_MARKERS.forEach(::appendLine)
        ACCEPTED_DELIVERABLE_COMPONENT_IDS.forEach { componentId ->
            appendLine()
            appendLine("## $componentId")
            appendLine()
            appendLine("Accepted production authority is mapped in the final component lineage.")
        }
    }.trim()

    private suspend fun assertCompleteAcceptedSnapshotAuthority(
        substrateFactory: SubstrateDatabaseFactory,
        dataRoot: File,
        runId: String,
    ) {
        val winnerGroupId = acceptedSnapshotWinnerGroupId(substrateFactory, dataRoot, runId)
        val snapshot = assertNotNull(
            com.achulkov.evomolior.substrate.deliverable.DeliverablePersistenceRepository(
                substrateFactory.getAtlas(runId)
            ).snapshots(runId, winnerGroupId).lastOrNull()
        )
        assertEquals(ACCEPTED_DELIVERABLE_COMPONENT_IDS.sorted(), snapshot.acceptedComponentIds)
        assertTrue(snapshot.provisionalComponentIds.isEmpty())
        assertTrue(snapshot.withheldComponentIds.isEmpty())
        assertTrue(snapshot.failedGateIds.isEmpty())
        assertTrue(snapshot.missingAuthorityComponentIds.isEmpty())
    }

    private suspend fun awaitRunTerminal(
        service: EvoMoliorRunService,
        runId: String,
        timeoutMs: Long,
    ): EvoMoliorEvent = withTimeout(timeoutMs) {
        service.events.first { event ->
            when (event) {
                is EvoMoliorEvent.RunCompleted -> event.runId == runId
                is EvoMoliorEvent.RunRecovered -> event.runId == runId
                is EvoMoliorEvent.RunAborted -> event.runId == runId
                else -> false
            }
        }
    }

    private suspend fun awaitRunTerminalIncludingProvisional(
        service: EvoMoliorRunService,
        runId: String,
        timeoutMs: Long,
    ): EvoMoliorEvent = withTimeout(timeoutMs) {
        service.events.first { event ->
            when (event) {
                is EvoMoliorEvent.RunCompleted -> event.runId == runId
                is EvoMoliorEvent.RunCompletedProvisional -> event.runId == runId
                is EvoMoliorEvent.RunRecovered -> event.runId == runId
                is EvoMoliorEvent.RunAborted -> event.runId == runId
                else -> false
            }
        }
    }

    private suspend fun acceptedWriterDocs(
        substrateFactory: SubstrateDatabaseFactory,
        dataRoot: File,
        runId: String,
    ): List<String> {
        val winnerGroupId = acceptedSnapshotWinnerGroupId(substrateFactory, dataRoot, runId)
        return substrateFactory.get(runId, winnerGroupId)
            .stagingDao()
            .getAll(runId, winnerGroupId, 1_000)
            .filter { it.type == "DOC" }
            .map { it.content }
    }

    private suspend fun acceptedSnapshotWinnerGroupId(
        substrateFactory: SubstrateDatabaseFactory,
        dataRoot: File,
        runId: String,
    ): String {
        val atlasRepository = AtlasTransactionRepository(substrateFactory)
        val atlas = assertNotNull(atlasRepository.currentAtlas(runId))
        val groupIds = buildSet {
            addAll(assertNotNull(PersistedRunStateStore.read(dataRoot, runId)).groupIds)
            atlasRepository.currentStates(runId, atlas.atlasId)
                .values
                .flatMapTo(this) { state -> state.revealedToGroups.map(AtlasGroupId::value) }
        }
        val repository =
            com.achulkov.evomolior.substrate.deliverable.DeliverablePersistenceRepository(
                substrateFactory.getAtlas(runId)
            )
        val snapshotsByGroup = groupIds.associateWith { groupId ->
            repository.snapshots(runId, groupId)
        }
        val winners = snapshotsByGroup.filterValues { snapshots ->
            snapshots.lastOrNull()?.let { snapshot ->
                snapshot.hasValidHash() &&
                    snapshot.acceptedComponentIds == ACCEPTED_DELIVERABLE_COMPONENT_IDS.sorted() &&
                    snapshot.provisionalComponentIds.isEmpty() &&
                    snapshot.withheldComponentIds.isEmpty() &&
                    snapshot.failedGateIds.isEmpty() &&
                    snapshot.missingAuthorityComponentIds.isEmpty()
            } == true
        }.keys
        assertEquals(
            1,
            winners.size,
            "Accepted fixture requires one exact complete accepted snapshot; " +
                snapshotsByGroup.mapValues { (_, snapshots) ->
                    snapshots.map { snapshot ->
                        "round=${snapshot.round},accepted=${snapshot.acceptedComponentIds}," +
                            "provisional=${snapshot.provisionalComponentIds}," +
                            "withheld=${snapshot.withheldComponentIds}," +
                            "failed=${snapshot.failedGateIds}," +
                            "missing=${snapshot.missingAuthorityComponentIds}"
                    }
                },
        )
        return winners.single()
    }

    private fun acceptanceBoundaryService(
        substrateFactory: SubstrateDatabaseFactory,
        failingBoundary: AcceptanceRunBoundary?,
        archiveCalls: AtomicInteger,
        archiveServiceOverride: RunArchiveService? = null,
        finalDeliverableAttemptTimeoutMsOverride: Long? = null,
        localAgentRunnerOverride:
            (suspend (AgentBootContext, ((BudgetLedger) -> Unit)?) -> Unit)? = null,
        beforeRoundSnapshotCompilation: suspend (runId: String, round: Int) -> Unit =
            { _, _ -> },
    ): EvoMoliorRunService {
        val acceptedWinnerGroupId = AtomicReference<String>()
        return EvoMoliorRunService(
        lifecycleManagerProvider = { runId, groupId, _, _, groupProfile ->
            LifecycleManager(
                runId = runId,
                groupId = groupId,
                shuruVmManager = ShuruVmManager(),
                substrateDatabaseFactory = substrateFactory,
                agentJarPath = "/fake/agent.jar",
                openRouterApiKey = "/tmp/codex",
                groupProfile = groupProfile,
                localAgentRunner = { bootContext, ledgerCallback ->
                    val isFinalWriter =
                        bootContext.role == Role.Builder &&
                            bootContext.rolePrompt.contains("Final Deliverable Writer")
                    val isRoundSummariser =
                        bootContext.role == Role.Archivist &&
                            bootContext.rolePrompt.contains("Round Submission Summariser")
                    if (localAgentRunnerOverride != null && isFinalWriter) {
                        localAgentRunnerOverride(bootContext, ledgerCallback)
                    } else if (isFinalWriter) {
                        val database = substrateFactory.get(bootContext.runId, bootContext.groupId)
                        val now = System.currentTimeMillis()
                        database.stagingDao().insert(
                            StagingSubmissionEntity(
                                id = "acceptance-final-${bootContext.agentId}-$now",
                                agentId = bootContext.agentId,
                                role = bootContext.role.name,
                                type = "DOC",
                                content = acceptedMarkdownContent("Receiver result"),
                                budgetSignature = "acceptance-boundary-final-writer",
                                status = "pending",
                                generation = bootContext.generation,
                                groupId = bootContext.groupId,
                                runId = bootContext.runId,
                                submittedAtMs = now,
                                reviewedAtMs = null
                            )
                        )
                        insertAcceptedFinalWriterLineageAndDeathNote(
                            substrateFactory = substrateFactory,
                            database = database,
                            bootContext = bootContext,
                            outputRelativePath = "receiver/result.md",
                            lineageSubmittedAtMs = now + 1L,
                            deathNoteSubmittedAtMs = now + 2L,
                        )
                    } else if (isRoundSummariser) {
                        if (localAgentRunnerOverride != null) {
                            localAgentRunnerOverride(bootContext, ledgerCallback)
                        } else {
                            val database = substrateFactory.get(bootContext.runId, bootContext.groupId)
                            val now = System.currentTimeMillis()
                            val markerBody = ACCEPTED_SUITE_MARKERS.joinToString("\n")
                            database.stagingDao().insert(
                            StagingSubmissionEntity(
                                id = "acceptance-summary-${bootContext.agentId}-$now",
                                agentId = bootContext.agentId,
                                role = bootContext.role.name,
                                type = "PLAN_UPDATE",
                                content = """
                                    ROUND SUBMISSION SUMMARY
                                    $ACCEPTED_PUBLICATION_MARKER
                                    $markerBody
                                    Source-grounded evidence notes explain the verified basis, assumptions,
                                    uncertainties, risks, implementation plan, action checklist, alternatives,
                                    limitations, and next evidence. This deterministic fallback contains more
                                    than twenty visible words so the sealed receiver tests can evaluate the
                                    complete Council-authored output contract before the dedicated writer runs.

                                    FINAL DELIVERABLE HTML
                                    <!doctype html>
                                    <html lang="en">
                                    <head><meta charset="utf-8"><title>Accepted fallback</title></head>
                                    <body><main><article>
                                    <h1>Accepted fallback</h1>
                                    <p>$ACCEPTED_PUBLICATION_MARKER $markerBody</p>
                                    <section><h2>Source-grounded evidence notes</h2>
                                    <p>The verified basis, assumptions, uncertainties, risks, implementation
                                    plan, action checklist, alternatives, limitations, and next evidence are
                                    stated explicitly for deterministic receiver validation.</p></section>
                                    </article></main></body>
                                    </html>
                                """.trimIndent(),
                                budgetSignature = "acceptance-boundary-round-summary",
                                status = "pending",
                                generation = bootContext.generation,
                                groupId = bootContext.groupId,
                                runId = bootContext.runId,
                                submittedAtMs = now + 10_000L,
                            ),
                        )
                        }
                    // Keep the loser evidence-free so exact node grades resolve capture instead
                    // of producing a deterministic tie across otherwise identical fixtures.
                    } else if (bootContext.groupId == acceptedWinnerGroupId.get()) {
                        seedAcceptedWorkerContribution(substrateFactory, bootContext)
                    }
                },
            )
        },
        groupCoordinatorProvider = { groupIds, dimensions ->
            GroupCoordinator(groupIds, dimensions)
        },
        graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
        substrateFactory = substrateFactory,
        archiveService = archiveServiceOverride
            ?: CountingArchiveService(substrateFactory, archiveCalls),
        acceptanceRunBoundaryVerifier = AcceptanceRunBoundaryVerifier { _, boundary ->
            if (failingBoundary != null && boundary == failingBoundary) {
                error("forced acceptance boundary failure: $boundary")
            }
        },
        distributionManifestHashProvider = {
            ACCEPTANCE_BOUNDARY_DISTRIBUTION_HASH
        },
        workerCriticReviewRunner = { submission, _, _, criticId ->
            CriticReview(
                submissionId = submission.id,
                outcome = CriticOutcome.PROMOTE,
                criticAgentId = criticId,
                initialTrustScore = 1.0,
            )
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
            compiledCouncilTestFixture(
                runId = callbackRunId,
                objective = callbackObjective,
                envelope = envelope,
                inputArtifacts = inputArtifacts,
                workerGroupKeys = listOf("group-0", "group-1"),
                workerProfiles = listOf(GroupProfileType.Library, GroupProfileType.Factory),
                generalTests = acceptedPublicationGeneralTests(),
            ).also { compiled ->
                acceptedWinnerGroupId.set(compiled.workerField.ringTopology.first())
            }
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
            assertEquals(acceptedWinnerGroupId.get(), competingGroupIds.first())
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
        roundDurationMillisOverride = 2_000L,
        finalDeliverableAttemptTimeoutMsOverride = finalDeliverableAttemptTimeoutMsOverride,
        beforeRoundSnapshotCompilation = beforeRoundSnapshotCompilation,
        roundHeartbeatIntervalMs = 1L,
        inspectorCheckIntervalMs = 1L
    )
    }

    private suspend fun seedAcceptedWorkerContribution(
        substrateFactory: SubstrateDatabaseFactory,
        bootContext: AgentBootContext,
    ) {
        val claimedNodeId = bootContext.wipTaskClaim?.nodeId ?: return
        val nodeIds = requireNotNull(
            AtlasTransactionRepository(substrateFactory).currentAtlas(bootContext.runId),
        ).nodes.keys
            .map { it.value }
            .sortedWith(compareBy<String> { it != claimedNodeId }.thenBy { it })
        val database = substrateFactory.get(bootContext.runId, bootContext.groupId)
        nodeIds.forEach { nodeId ->
        val submissionId = "acceptance-evidence-" +
            AtlasHashing.hashText(
                "${bootContext.groupId}|${bootContext.generation}|$nodeId",
            ).take(32)
        if (database.stagingDao().getById(submissionId) != null) return
        val content = ACCEPTED_SUITE_MARKERS.joinToString(
            prefix = "$ACCEPTED_PUBLICATION_MARKER evidence for " +
                "${bootContext.groupId}, node $nodeId.\n",
            separator = "\n",
        )
        val contentHash = AtlasHashing.hashText(content)
        val now = System.currentTimeMillis()
        database.stagingDao().insert(
            StagingSubmissionEntity(
                id = submissionId,
                agentId = bootContext.agentId,
                role = bootContext.role.name,
                type = "DOC",
                content = content,
                wipTaskRef = nodeId,
                budgetSignature = "acceptance-worker-evidence",
                status = "promoted",
                criticAgentId = "acceptance-critic",
                initialTrustScore = 1.0,
                generation = bootContext.generation,
                groupId = bootContext.groupId,
                runId = bootContext.runId,
                submittedAtMs = now,
                reviewedAtMs = now + 1L,
                promotedArtifactRef = "kb:$submissionId",
                promotedArtifactHash = contentHash,
            ),
        )
        database.knowledgeBaseDao().insert(
            KnowledgeBaseEntryEntity(
                id = submissionId,
                content = content,
                sourceAgentId = bootContext.agentId,
                generation = bootContext.generation,
                trustScore = 1.0,
                domain = "acceptance:worker-evidence",
                groupId = bootContext.groupId,
                runId = bootContext.runId,
                createdAtMs = now + 1L,
            ),
        )
        ContributionGraphRepository(
            database = database,
            atlasDatabase = substrateFactory.getAtlas(bootContext.runId),
        ).recordCriticDecision(
            CriticDecisionRecord(
                decisionId = "acceptance-decision-$submissionId",
                runId = bootContext.runId,
                groupId = AtlasGroupId(bootContext.groupId),
                round = bootContext.generation,
                submissionId = submissionId,
                submissionContentHash = contentHash,
                resolvedReview = CriticReview(
                    submissionId = submissionId,
                    outcome = CriticOutcome.PROMOTE,
                    criticAgentId = "acceptance-critic",
                    initialTrustScore = 1.0,
                ),
                resultAuthority = ContributionAuthorityRef(
                    kind = ContributionAuthorityKind.CANONICAL_ARTIFACT,
                    authorityId = "kb:$submissionId",
                    authorityHash = contentHash,
                ),
                decidedAt = Instant.fromEpochMilliseconds(now + 1L),
            ).withComputedHash(),
        )
        val contribution = ContributionCommit(
            commitId = "acceptance-contribution-$submissionId",
            runId = bootContext.runId,
            groupId = AtlasGroupId(bootContext.groupId),
            round = bootContext.generation,
            agentId = AtlasAgentId(bootContext.agentId),
            nodeId = NodeId(nodeId),
            commitType = CommitType.POSITIVE_RESULT,
            artifactIds = listOf(submissionId),
            claimIds = emptyList(),
            toolRunIds = emptyList(),
            testsRun = emptyList(),
            changedStateSummary = "Persisted deterministic accepted evidence.",
            evidenceSummary = "Critic-promoted receiver-facing evidence.",
            confidence = 1.0,
            unresolvedRisks = emptyList(),
            successorRecommendations = emptyList(),
            costSummary = NodeEvidenceCostSummary(totalCredits = 1.0),
            dedupeHash = "",
            committedAt = Instant.fromEpochMilliseconds(now + 2L),
        )
        WorkerExecutionRepository(database).commit(
            contribution.copy(
                dedupeHash = AtlasHashing.hashContributionDedupe(contribution),
            ),
        )
        }
    }

    private fun acceptanceBoundaryConfig(
        runId: String,
        substrateFactory: SubstrateDatabaseFactory,
    ): RunConfig {
        val admission = AcceptanceAdmissionArtifact(
            runId = runId,
            acceptanceProfile = AcceptanceProfile.RUNTIME_USABILITY_SMOKE,
            admittedAtUtc = "2026-07-26T00:00:00Z",
            ownerPid = 1L,
            distributionManifestSha256 = ACCEPTANCE_BOUNDARY_DISTRIBUTION_HASH,
            sourceRevision = "acceptance-boundary-fixture",
            configurationSha256 = AtlasHashing.hashText("acceptance-boundary-config"),
            modelAssignmentsSha256 = AtlasHashing.hashText("acceptance-boundary-models"),
            jvmVersion = System.getProperty("java.runtime.version"),
            javaHome = "/test/jdk",
            javaExecutableSha256 = AtlasHashing.hashText("acceptance-boundary-java"),
            javaReleaseSha256 = AtlasHashing.hashText("acceptance-boundary-java-release"),
            javaModulesSha256 = AtlasHashing.hashText("acceptance-boundary-java-modules"),
            codexCliPath = "/test/codex",
            codexCliSha256 = AtlasHashing.hashText("acceptance-boundary-codex"),
            codexCliVersion = "test",
            estimatedMaximumRunArchiveBytes = 1L,
            requiredFreeBytes = 1L,
            checks = listOf(
                AcceptanceAdmissionCheck("fixture", "service acceptance boundary")
            ),
        )
        substrateFactory.runControlDir(runId).mkdirs()
        File(
            substrateFactory.runControlDir(runId),
            AcceptanceAdmissionArtifact.FILE_NAME,
        ).writeText(
            Json.encodeToString(AcceptanceAdmissionArtifact.serializer(), admission)
        )
        return RunConfig(
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
            finalOutputSpec = acceptedFinalOutputSpec(
                relativePath = "receiver/result.md",
                mediaType = "text/markdown",
            )
        )
    }

    private suspend fun awaitAcceptanceBoundaryAbort(
        service: EvoMoliorRunService,
        runId: String
    ): EvoMoliorEvent.RunAborted = withTimeout(20_000L) {
        service.events.first { event ->
            event is EvoMoliorEvent.RunAborted && event.runId == runId
        } as EvoMoliorEvent.RunAborted
    }

    private suspend fun awaitAcceptanceBoundaryRecovery(
        service: EvoMoliorRunService,
        runId: String
    ): EvoMoliorEvent.RunRecovered = withTimeout(60_000L) {
        service.events.first { event ->
            event is EvoMoliorEvent.RunRecovered && event.runId == runId
        } as EvoMoliorEvent.RunRecovered
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
        assertTrue(
            service.getMetaArchiveEntries(runId, RunArchiveService.ENTRY_RUN_RECOVERED).isEmpty(),
            "Rejected acceptance boundary must not invoke terminal recovery."
        )
    }

    private suspend fun assertNoNormalCompletionAuthorities(
        service: EvoMoliorRunService,
        runId: String
    ) {
        assertTrue(
            service.getMetaArchiveEntries(
                runId,
                RunArchiveService.ENTRY_RUN_COMPLETED
            ).isEmpty()
        )
        assertTrue(
            service.getMetaArchiveEntries(
                runId,
                RunArchiveService.ENTRY_RUN_GRAPH_MESSAGE
            ).isEmpty()
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

    private fun zipEntries(entries: Map<String, String>): ByteArray =
        ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                entries.toSortedMap().forEach { (path, content) ->
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(content.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }
            output.toByteArray()
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
        override suspend fun compileAcceptedRunMetrics(
            runId: String,
            groupIds: List<String>,
            startingFieldJson: String,
            finalGeneration: Int,
            requestedOutputSpec: FinalOutputSpec?,
            completionRecord: RunCompletionRecord?,
            acceptedDecisionPackage:
                com.achulkov.evomolior.core.deliverable.ReceiverDecisionPackage,
            closureRuntimeInput: RunClosureMetricRuntimeInput?,
        ): RunMetricsV1Artifact {
            throw IllegalStateException(
                "forced accepted metrics compilation failure for $runId"
            )
        }
    }

    private class FailingProvisionalArchiveService(
        substrateFactory: SubstrateDatabaseFactory
    ) : RunArchiveService(substrateFactory) {
        override suspend fun compileProvisionalRunMetrics(
            runId: String,
            groupIds: List<String>,
            startingFieldJson: String,
            finalGeneration: Int,
            requestedOutputSpec: FinalOutputSpec?,
            closureRuntimeInput: RunClosureMetricRuntimeInput?,
        ): RunMetricsV1Artifact {
            throw IllegalStateException(
                "private provisional metrics failure path=/secret/$runId"
            )
        }
    }

    private class PostArchiveMetricsFailingService(
        substrateFactory: SubstrateDatabaseFactory
    ) : RunArchiveService(substrateFactory) {
        val forcedFailureCount = AtomicInteger()

        override fun persistCompiledRunMetrics(
            artifact: RunMetricsV1Artifact,
        ): RunMetricsV1Artifact {
            if (forcedFailureCount.getAndIncrement() == 0) {
                throw IllegalStateException(
                    "forced metrics persistence failure after accepted archive write"
                )
            }
            return super.persistCompiledRunMetrics(artifact)
        }
    }

    private class RendererFailingArchiveService(
        substrateFactory: SubstrateDatabaseFactory
    ) : RunArchiveService(substrateFactory) {
        val attemptedPackage = AtomicReference<
            com.achulkov.evomolior.core.deliverable.ReceiverDecisionPackage?
            >(null)

        override fun buildOutcomeAwareAcceptedBundle(
            request: AcceptedOutcomeArchiveRequest,
        ): OutcomeArchive {
            attemptedPackage.set(request.decisionPackage)
            throw IllegalStateException(
                "forced accepted renderer compilation failure for ${request.runId}"
            )
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
