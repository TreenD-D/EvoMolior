package com.achulkov.evokammer.api.service

import com.achulkov.evokammer.api.model.FinalOutputSpec
import com.achulkov.evokammer.api.model.FinalOutputKind
import com.achulkov.evokammer.core.contract.StartingField
import com.achulkov.evokammer.core.model.GroupProfileType
import com.achulkov.evokammer.orchestration.metaplanner.MetaPlannerRunner
import com.achulkov.evokammer.sandbox.shuru.ShuruVmManager
import com.achulkov.evokammer.substrate.SubstrateDatabaseFactory
import com.achulkov.evokammer.substrate.db.entity.MetaArchiveEntryEntity
import kotlinx.serialization.json.Json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
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

        val service = EvoKammerRunService(
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
}
