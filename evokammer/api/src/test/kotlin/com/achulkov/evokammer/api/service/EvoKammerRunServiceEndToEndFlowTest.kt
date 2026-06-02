package com.achulkov.evokammer.api.service

import com.achulkov.evokammer.api.events.EvoKammerEvent
import com.achulkov.evokammer.api.model.RunConfig
import com.achulkov.evokammer.api.model.RunInputArtifactUpload
import com.achulkov.evokammer.competition.coordinator.GroupCoordinator
import com.achulkov.evokammer.competition.grader.GraderGroupManager
import com.achulkov.evokammer.core.contract.StartingField
import com.achulkov.evokammer.core.model.GroupProfileType
import com.achulkov.evokammer.core.model.ScoringDimension
import com.achulkov.evokammer.orchestration.metaplanner.MetaPlannerRunner
import com.achulkov.evokammer.orchestration.lifecycle.LifecycleManager
import com.achulkov.evokammer.sandbox.shuru.ShuruVmManager
import com.achulkov.evokammer.substrate.SubstrateDatabaseFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.zip.ZipInputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
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
        val judgeOutputsByGrader: Map<String, String> = emptyMap(),
        val submissionSummariesByGroup: Map<String, String?> = emptyMap()
    )
}
