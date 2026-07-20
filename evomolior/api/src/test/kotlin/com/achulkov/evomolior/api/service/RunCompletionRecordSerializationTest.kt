package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.api.model.FinalOutputSpec
import com.achulkov.evomolior.api.model.FinalOutputKind
import com.achulkov.evomolior.core.contract.StartingField
import com.achulkov.evomolior.core.model.GroupProfileType
import com.achulkov.evomolior.orchestration.metaplanner.MetaPlannerRunner
import com.achulkov.evomolior.sandbox.shuru.ShuruVmManager
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.db.entity.MetaArchiveEntryEntity
import kotlinx.serialization.json.Json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlin.io.path.createTempDirectory
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.jvm.isAccessible
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RunCompletionRecordSerializationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `run completion record round-trips final answer payload`() {
        val payload = RunCompletionRecord(
            runId = "run-1",
            winnerId = "group-0",
            finalAnswerSummary = "winner=group-0",
            finalDeliverableHtml = "<!doctype html><html><body><article>done</article></body></html>",
            requestedOutputSpec = FinalOutputSpec(
                relativePath = "briefs/evidence-brief.json",
                mediaType = "application/json",
                description = "Machine-readable evidence brief",
                kind = FinalOutputKind.PATCH_BUNDLE
            ),
            judgeOutputsByGrader = mapOf("grader-0" to "judge output"),
            submissionSummariesByGroup = mapOf("group-0" to "submission summary"),
            substrateHealthByGroup = listOf(
                RunSubstrateHealthSummary(
                    groupId = "group-0",
                    kbCount = 3,
                    kgCount = 2,
                    activeToolCount = 1,
                    toolExecutionCount = 4,
                    kgFactSubmissionCount = 2,
                    toolSubmissionCount = 1,
                    kgAbstentionRationaleCount = 1,
                    toolAbstentionRationaleCount = 1,
                    noActionFallbackSubmissionCount = 2,
                    trustDecayEventCount = 2,
                    lowTrustArchivedByDecayCount = 3,
                    stagedSubmissionCount = 5,
                    promotedSubmissionCount = 2,
                    pendingSubmissionCount = 1,
                    rejectedSubmissionCount = 2,
                    outlierSubmissionCount = 1,
                    roundSubmissionSummaryPresent = true
                )
            ),
            graphMessage = RunGraphMessage(
                runId = "run-1",
                headline = "winner=group-0",
                routedContextSummaries = listOf(
                    "high-level-run-log: winner=group-0",
                    "final-answer-summary: winner=group-0"
                ),
            ),
            completedAtMs = 42L
        )

        val encoded = json.encodeToString(RunCompletionRecord.serializer(), payload)
        val decoded = json.decodeFromString(RunCompletionRecord.serializer(), encoded)

        assertEquals(payload, decoded)
    }

    @Test
    fun `final output spec defaults missing kind to generic file`() {
        val payload = """
            {
              "relativePath": "briefs/evidence-brief.json",
              "mediaType": "application/json",
              "description": "Machine-readable evidence brief"
            }
        """.trimIndent()

        val decoded = json.decodeFromString(FinalOutputSpec.serializer(), payload)

        assertEquals("briefs/evidence-brief.json", decoded.relativePath)
        assertEquals("application/json", decoded.mediaType)
        assertEquals("Machine-readable evidence brief", decoded.description)
        assertEquals(FinalOutputKind.GENERIC_FILE, decoded.kind)
    }

    @Test
    fun `persist run graph message writes a dedicated archive entry`() = runBlocking {
        val dataRoot = createTempDirectory("graph-message-write-").toFile()
        val substrateFactory = SubstrateDatabaseFactory(dataRoot)
        val runId = "run-1"
        val archiveDb = substrateFactory.get(runId, "__meta_archive__")
        archiveDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = "starting-field-$runId",
                runId = runId,
                entryType = RunArchiveService.ENTRY_STARTING_FIELD,
                groupId = null,
                label = "StartingField — $runId",
                contentJson = json.encodeToString(
                    StartingField.serializer(),
                    StartingField.default(
                        objective = "Graph message objective",
                        runId = runId,
                        groupCount = 1,
                        groupProfilesMap = mapOf("group-0" to GroupProfileType.Library)
                    )
                ),
                runObjective = "Graph message objective",
                finalScore = null,
                isFromWinner = false,
                generation = 0,
                archivedAtMs = 1L
            )
        )

        val service = EvoMoliorRunService(
            lifecycleManagerProvider = { _, _, _, _, _ -> error("unused") },
            groupCoordinatorProvider = { _, _ -> error("unused") },
            graderGroupManagerProvider = { _ -> error("unused") },
            substrateFactory = substrateFactory,
            shuruVmManager = ShuruVmManager(),
            metaPlannerRunner = MetaPlannerRunner()
        )
        val completionRecord = RunCompletionRecord(
            runId = runId,
            winnerId = "group-0",
            finalAnswerSummary = "graph message headline",
            graphMessage = RunGraphMessage(
                runId = runId,
                headline = "graph message headline",
                routedContextSummaries = listOf(
                    "high-level-run-log: graph message headline",
                    "final-answer-summary: graph message headline"
                )
            )
        )

        service.persistRunGraphMessage(completionRecord)

        val entries = archiveDb.metaArchiveDao().getByType(RunArchiveService.ENTRY_RUN_GRAPH_MESSAGE)
        val stored = entries.firstOrNull { it.runId == runId } ?: error("expected graph message entry")
        val decoded = json.decodeFromString(RunGraphMessage.serializer(), stored.contentJson)

        assertEquals(completionRecord.graphMessage, decoded)
        assertTrue(stored.label.contains("Run Graph Message"))
    }

    @Test
    fun `run graph message strips obvious protocol planning from judge snippets`() {
        val root = createTempDirectory("run-graph-sanitizer-test-").toFile()
        val substrateFactory = SubstrateDatabaseFactory(root)
        val service = EvoMoliorRunService(
            lifecycleManagerProvider = { _, _, _, _, _ -> error("unused") },
            groupCoordinatorProvider = { _, _ -> error("unused") },
            graderGroupManagerProvider = { _ -> error("unused") },
            substrateFactory = substrateFactory,
            shuruVmManager = ShuruVmManager(),
            metaPlannerRunner = MetaPlannerRunner()
        )

        val method = service::class.declaredFunctions.first { it.name == "buildRunGraphMessage" }
            .apply { isAccessible = true }
        val message = method.call(
            service,
            "run-sanitizer",
            "group-0",
            "final summary",
            null,
            emptyList<GeneratedOutputFile>(),
            emptyList<com.achulkov.evomolior.core.contract.GeneratedImageAsset>(),
            mapOf(
                "grader-0" to """
                    Now I should write my death note.
                    Now I'll write my mandatory Death Note:
                    Tool call plan: call the tool after scoring.
                    Evidence score: group-0 has concrete promoted artifacts.
                """.trimIndent()
            ),
            emptyMap<String, String?>(),
            emptyList<RunSubstrateHealthSummary>()
        ) as RunGraphMessage

        val rendered = message.routedContextSummaries.joinToString("\n")
        assertFalse(rendered.contains("death note", ignoreCase = true))
        assertFalse(rendered.contains("tool call", ignoreCase = true))
        assertTrue(rendered.contains("Evidence score"))
    }

    @Test
    fun `run graph message replaces protocol-only judge snippets`() {
        val root = createTempDirectory("run-graph-protocol-only-test-").toFile()
        val substrateFactory = SubstrateDatabaseFactory(root)
        val service = EvoMoliorRunService(
            lifecycleManagerProvider = { _, _, _, _, _ -> error("unused") },
            groupCoordinatorProvider = { _, _ -> error("unused") },
            graderGroupManagerProvider = { _ -> error("unused") },
            substrateFactory = substrateFactory,
            shuruVmManager = ShuruVmManager(),
            metaPlannerRunner = MetaPlannerRunner()
        )

        val method = service::class.declaredFunctions.first { it.name == "buildRunGraphMessage" }
            .apply { isAccessible = true }
        val message = method.call(
            service,
            "run-sanitizer",
            "group-0",
            "final summary",
            null,
            emptyList<GeneratedOutputFile>(),
            emptyList<com.achulkov.evomolior.core.contract.GeneratedImageAsset>(),
            mapOf("grader-0" to "Now I must write my death note."),
            emptyMap<String, String?>(),
            emptyList<RunSubstrateHealthSummary>()
        ) as RunGraphMessage

        val rendered = message.routedContextSummaries.joinToString("\n")
        assertFalse(rendered.contains("death note", ignoreCase = true))
        assertTrue(rendered.contains("[protocol-only content omitted]"))
    }
}
