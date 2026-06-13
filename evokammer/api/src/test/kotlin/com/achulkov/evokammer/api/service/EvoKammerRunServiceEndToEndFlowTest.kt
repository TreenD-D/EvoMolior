package com.achulkov.evokammer.api.service

import com.achulkov.evokammer.api.events.EvoKammerEvent
import com.achulkov.evokammer.api.model.FinalOutputSpec
import com.achulkov.evokammer.api.model.RunConfig
import com.achulkov.evokammer.api.model.RunInputArtifactUpload
import com.achulkov.evokammer.competition.coordinator.GroupCoordinator
import com.achulkov.evokammer.competition.grader.GraderGroupManager
import com.achulkov.evokammer.core.contract.AgentBootContext
import com.achulkov.evokammer.core.contract.StartingField
import com.achulkov.evokammer.core.model.GroupProfileType
import com.achulkov.evokammer.core.model.Role
import com.achulkov.evokammer.core.model.ScoringDimension
import com.achulkov.evokammer.orchestration.metaplanner.MetaPlannerRunner
import com.achulkov.evokammer.orchestration.lifecycle.LifecycleManager
import com.achulkov.evokammer.sandbox.shuru.ShuruVmManager
import com.achulkov.evokammer.substrate.SubstrateDatabaseFactory
import com.achulkov.evokammer.substrate.db.entity.StagingSubmissionEntity
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

/**
 * End-to-end regression for the user-facing run lifecycle.
 *
 * Proves that callers can start a run, observe the live event stream, wait for completion,
 * and query the finished run's state and archive summary.
 */
class EvoKammerRunServiceEndToEndFlowTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `start run emits lifecycle events and exposes completion state and archive summary`() = runBlocking {
        val tmpDir = createTempDirectory("svc-end-to-end-flow-test-").toFile()
        val substrateFactory = SubstrateDatabaseFactory(tmpDir)
        val runId = "run-end-to-end"
        val collectedEvents = mutableListOf<EvoKammerEvent>()

        val service = EvoKammerRunService(
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
                isTestRun = false
            )
        )

        assertEquals(runId, returnedRunId)

        withTimeout(10_000) {
            service.events.first { event ->
                event is EvoKammerEvent.RunCompleted && event.runId == runId
            }
        }

        eventCollector.cancel()

        assertTrue(
            collectedEvents.any { it is EvoKammerEvent.RunStarted && it.runId == runId },
            "The live event stream should include RunStarted"
        )
        assertTrue(
            collectedEvents.any { it is EvoKammerEvent.RoundStarted && it.runId == runId && it.roundIndex == 0 },
            "The live event stream should include the first round"
        )
        assertTrue(
            collectedEvents.any { it is EvoKammerEvent.RoundCompleted && it.runId == runId },
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
    fun `single round lifecycle emits summary completion and final answer in canonical order`() = runBlocking {
        val tmpDir = createTempDirectory("svc-event-order-flow-test-").toFile()
        val substrateFactory = SubstrateDatabaseFactory(tmpDir)
        val runId = "run-event-order"
        val collectedEvents = mutableListOf<EvoKammerEvent>()

        val service = EvoKammerRunService(
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
            service.events.first { event ->
                event is EvoKammerEvent.RunCompleted && event.runId == runId
            }
        }

        eventCollector.cancel()

        val roundStartedIndex = collectedEvents.indexOfFirst {
            it is EvoKammerEvent.RoundStarted && it.runId == runId && it.roundIndex == 0
        }
        val roundSummaryIndex = collectedEvents.indexOfFirst {
            it is EvoKammerEvent.EngineProgress && it.runId == runId && it.phase == "round_summary_result"
        }
        val roundCompletedIndex = collectedEvents.indexOfFirst {
            it is EvoKammerEvent.RoundCompleted && it.runId == runId
        }
        val finalAnswerIndex = collectedEvents.indexOfFirst {
            it is EvoKammerEvent.EngineProgress && it.runId == runId && it.phase == "final_answer"
        }
        val runCompletedIndex = collectedEvents.indexOfFirst {
            it is EvoKammerEvent.RunCompleted && it.runId == runId
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
            collectedEvents.count { it is EvoKammerEvent.RoundCompleted && it.runId == runId },
            "One-round runs should publish exactly one RoundCompleted event"
        )
    }

    @Test
    fun `continuation run clones archived StartingField and merges new context artifacts`() = runBlocking {
        val tmpDir = createTempDirectory("svc-continuation-flow-test-").toFile()
        val substrateFactory = SubstrateDatabaseFactory(tmpDir)
        val firstRunId = "run-continuation-source"
        val secondRunId = "run-continuation-target"
        val collectedEvents = mutableListOf<EvoKammerEvent>()

        val service = EvoKammerRunService(
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
                        relativePath = "docs/source.txt",
                        sourceName = "source.txt",
                        mimeType = "text/plain",
                        contentBase64 = Base64.getEncoder().encodeToString(sourceContext.toByteArray())
                    )
                )
            )
        )

        assertEquals(firstRunId, sourceRunIdReturned)
        withTimeout(10_000) {
            service.events.first { event ->
                event is EvoKammerEvent.RunCompleted && event.runId == firstRunId
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
                continuationFromRunId = firstRunId
            )
        )

        assertEquals(secondRunId, continuationRunIdReturned)
        withTimeout(10_000) {
            service.events.first { event ->
                event is EvoKammerEvent.RunCompleted && event.runId == secondRunId
            }
        }

        eventCollector.cancel()

        assertTrue(
            collectedEvents.any { it is EvoKammerEvent.EngineProgress && it.runId == secondRunId && it.phase == "continuation_start" },
            "Continuation should emit a progress marker"
        )

        val metaArchiveDb = substrateFactory.get(secondRunId, "__meta_archive__")
        val startingFieldEntry = metaArchiveDb.metaArchiveDao().getByType("STARTING_FIELD")
            .first { it.runId == secondRunId }
        val decodedStartingField = json.decodeFromString(StartingField.serializer(), startingFieldEntry.contentJson)

        assertEquals(firstRunId, decodedStartingField.continuedFromRunId)
        assertEquals("continued objective", decodedStartingField.objective)
        assertEquals(2, decodedStartingField.groupCount)
        assertEquals(GroupProfileType.Library, decodedStartingField.groupProfiles["group-0"])
        assertEquals(GroupProfileType.Factory, decodedStartingField.groupProfiles["group-1"])
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
        val finalWriterAttempts = AtomicInteger(0)
        val finalWriterModels = ConcurrentLinkedQueue<String>()
        val sawRetryPrompt = AtomicInteger(0)

        val service = EvoKammerRunService(
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

        withTimeout(10_000) {
            service.events.first { event ->
                event is EvoKammerEvent.RunCompleted && event.runId == runId
            }
        }

        val bundleEntries = unzipEntries(assertNotNull(service.getResultsBundle(runId)))
        val finalArticle = assertNotNull(bundleEntries["outputs/final-article.html"])
        assertTrue(finalArticle.contains("Dedicated Final Writer Article"))
        assertFalse(finalArticle.contains("Summary Fallback Article"))
        assertTrue(finalWriterAttempts.get() in 2..3)
        assertTrue(finalWriterModels.size >= 2)
        assertEquals("test-builder-model", finalWriterModels.elementAt(0))
        assertEquals("test-archivist-model", finalWriterModels.elementAt(1))
        assertTrue(sawRetryPrompt.get() >= 1)
    }

    @Test
    fun `browser deliverable archive survives many late doc entries before the canonical html`() = runBlocking {
        val tmpDir = createTempDirectory("svc-final-deliverable-volume-test-").toFile()
        val substrateFactory = SubstrateDatabaseFactory(tmpDir)
        val runId = "run-final-deliverable-volume"
        val lateDocCount = 180

        val service = EvoKammerRunService(
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

        withTimeout(10_000) {
            service.events.first { event ->
                event is EvoKammerEvent.RunCompleted && event.runId == runId
            }
        }

        val bundleEntries = unzipEntries(assertNotNull(service.getResultsBundle(runId)))
        val finalArticle = assertNotNull(bundleEntries["outputs/final-article.html"])
        assertTrue(finalArticle.contains("High-Volume Final Writer Article"))

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

        val service = EvoKammerRunService(
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

        withTimeout(10_000) {
            service.events.first { event ->
                event is EvoKammerEvent.RunCompleted && event.runId == runId
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

        val staleStateFile = File(tmpDir, "evokammer/runs/$runId/control/${PersistedRunStateStore.STATE_FILE_NAME}")
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

        val restartedService = EvoKammerRunService(
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
}
