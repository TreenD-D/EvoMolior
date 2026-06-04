package com.achulkov.evokammer.api.service

import com.achulkov.evokammer.core.contract.RunInputArtifact
import com.achulkov.evokammer.core.contract.RunInputArtifactKind
import com.achulkov.evokammer.core.contract.StartingField
import com.achulkov.evokammer.substrate.SubstrateDatabaseFactory
import com.achulkov.evokammer.substrate.db.entity.MetaArchiveEntryEntity
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RunArchiveServiceTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `buildResultsBundle includes starting field uploaded inputs and meta archive outputs`() = runTest {
        val rootDir = createTempDirectory("run-archive-test-").toFile()
        val dbFactory = SubstrateDatabaseFactory(rootDir)
        val service = RunArchiveService(dbFactory, json)
        val runId = "run-archive-test"
        val groupId = "group-0"

        dbFactory.get(runId, groupId)
        val metaDb = dbFactory.get(runId, "__meta_archive__")

        val artifactDir = File(rootDir, "evokammer/runs/$runId/artifacts/docs/specs")
        artifactDir.mkdirs()
        val inputFile = File(artifactDir, "brief.txt")
        inputFile.writeText("hello archive")

        val artifact = RunInputArtifact(
            relativePath = "docs/specs/brief.txt",
            sourceName = "brief.txt",
            mimeType = "text/plain",
            sizeBytes = inputFile.length(),
            sha256 = sha256Hex(inputFile.readBytes()),
            kind = RunInputArtifactKind.TEXT,
            textPreview = "hello archive"
        )

        val startingField = StartingField.default("archive the run", runId = runId).copy(
            inputArtifacts = listOf(artifact)
        )
        metaDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = UUID.randomUUID().toString(),
                runId = runId,
                entryType = RunArchiveService.ENTRY_STARTING_FIELD,
                groupId = null,
                label = "StartingField — $runId",
                contentJson = json.encodeToString(StartingField.serializer(), startingField),
                runObjective = startingField.objective,
                finalScore = null,
                isFromWinner = false,
                generation = 0,
                archivedAtMs = System.currentTimeMillis()
            )
        )
        metaDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = UUID.randomUUID().toString(),
                runId = runId,
                entryType = RunArchiveService.ENTRY_DEAD_ENDS,
                groupId = groupId,
                label = "Dead Ends — $groupId",
                contentJson = """[{"approach":"wrong","reason":"failed","domain":"test"}]""",
                runObjective = startingField.objective,
                finalScore = null,
                isFromWinner = false,
                generation = 1,
                archivedAtMs = System.currentTimeMillis()
            )
        )
        metaDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = UUID.randomUUID().toString(),
                runId = runId,
                entryType = RunArchiveService.ENTRY_ROUND_SUMMARY,
                groupId = groupId,
                label = "Round summary — $groupId",
                contentJson = """{"groupId":"$groupId","roundIndex":0,"summary":"hello"}""",
                runObjective = startingField.objective,
                finalScore = 88.0,
                isFromWinner = true,
                generation = 1,
                archivedAtMs = System.currentTimeMillis()
            )
        )
        metaDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = "run-completed-$runId",
                runId = runId,
                entryType = RunArchiveService.ENTRY_RUN_COMPLETED,
                groupId = null,
                label = "Run Completed — $runId",
                contentJson = json.encodeToString(
                    RunCompletionRecord.serializer(),
                    RunCompletionRecord(
                        runId = runId,
                        winnerId = groupId,
                        finalAnswerSummary = "Round 1 final answer: winner=$groupId",
                        finalDeliverableHtml = """
                            <!doctype html>
                            <html lang="en">
                            <head><meta charset="utf-8"><title>MindVortex</title></head>
                            <body><article><h1>MindVortex</h1><p>hello archive article</p></article></body>
                            </html>
                        """.trimIndent(),
                        judgeOutputsByGrader = mapOf("grader-0" to "Judge output"),
                        submissionSummariesByGroup = mapOf(groupId to "<article>hello</article>")
                    )
                ),
                runObjective = startingField.objective,
                finalScore = null,
                isFromWinner = false,
                generation = 1,
                archivedAtMs = System.currentTimeMillis()
            )
        )

        val bundle = service.buildResultsBundle(runId)
        assertNotNull(bundle)

        val entries = unzipEntries(bundle)
        assertTrue(entries.keys.contains("manifest.json"))
        assertTrue(entries.keys.contains("input-artifacts.json"))
        assertTrue(entries.keys.contains("starting-field.json"))
        assertTrue(entries.keys.contains("inputs/docs/specs/brief.txt"))
        assertTrue(entries.keys.contains("outputs/final-article.html"))
        assertTrue(entries.keys.any { it.startsWith("outputs/dead_ends_export") })
        assertTrue(entries.keys.any { it.startsWith("outputs/round_summary") })
        assertTrue(entries["manifest.json"]!!.contains("\"artifactCount\":1"))
        assertTrue(entries["input-artifacts.json"]!!.contains("\"docs/specs/brief.txt\""))
        assertTrue(entries["starting-field.json"]!!.contains("\"inputArtifacts\""))
        assertTrue(entries["outputs/final-article.html"]!!.contains("<!doctype html>"))
        assertTrue(entries["outputs/final-article.html"]!!.contains("<h1>MindVortex</h1>"))
        assertTrue(entries["outputs/final-article.html"]!!.contains("hello archive article"))
        assertEquals("hello archive", entries["inputs/docs/specs/brief.txt"])
    }

    @Test
    fun `buildResultsBundle extracts embedded final deliverable html from submission summary`() = runTest {
        val rootDir = createTempDirectory("run-archive-summary-html-test-").toFile()
        val dbFactory = SubstrateDatabaseFactory(rootDir)
        val service = RunArchiveService(dbFactory, json)
        val runId = "run-archive-summary-html"
        val groupId = "group-0"
        val metaDb = dbFactory.get(runId, "__meta_archive__")

        val startingField = StartingField.default("archive the run", runId = runId)
        metaDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = UUID.randomUUID().toString(),
                runId = runId,
                entryType = RunArchiveService.ENTRY_STARTING_FIELD,
                groupId = null,
                label = "StartingField — $runId",
                contentJson = json.encodeToString(StartingField.serializer(), startingField),
                runObjective = startingField.objective,
                finalScore = null,
                isFromWinner = false,
                generation = 0,
                archivedAtMs = System.currentTimeMillis()
            )
        )
        metaDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = "run-completed-$runId",
                runId = runId,
                entryType = RunArchiveService.ENTRY_RUN_COMPLETED,
                groupId = null,
                label = "Run Completed — $runId",
                contentJson = json.encodeToString(
                    RunCompletionRecord.serializer(),
                    RunCompletionRecord(
                        runId = runId,
                        winnerId = groupId,
                        finalAnswerSummary = "Round 1 final answer: winner=$groupId",
                        submissionSummariesByGroup = mapOf(
                            groupId to """
                                ROUND SUBMISSION SUMMARY
                                - Completed article structure.
                                
                                FINAL DELIVERABLE HTML
                                <!doctype html>
                                <html lang="en">
                                <head><meta charset="utf-8"><title>Embedded summary article</title></head>
                                <body><article><h1>Embedded summary article</h1><p>Publication-ready.</p></article></body>
                                </html>
                            """.trimIndent()
                        )
                    )
                ),
                runObjective = startingField.objective,
                finalScore = null,
                isFromWinner = false,
                generation = 1,
                archivedAtMs = System.currentTimeMillis()
            )
        )

        val bundle = service.buildResultsBundle(runId)
        assertNotNull(bundle)

        val entries = unzipEntries(bundle)
        assertTrue(entries["outputs/final-article.html"]!!.contains("Embedded summary article"))
        assertTrue(entries["outputs/final-article.html"]!!.contains("Publication-ready."))
    }

    @Test
    fun `buildResultsBundle falls back from winner summary to another summary with embedded html`() = runTest {
        val rootDir = createTempDirectory("run-archive-summary-fallback-test-").toFile()
        val dbFactory = SubstrateDatabaseFactory(rootDir)
        val service = RunArchiveService(dbFactory, json)
        val runId = "run-archive-summary-fallback"
        val winnerId = "group-0"
        val otherGroupId = "group-1"
        val metaDb = dbFactory.get(runId, "__meta_archive__")

        val startingField = StartingField.default("archive the run", runId = runId).copy(
            groupProfiles = mapOf(winnerId to com.achulkov.evokammer.core.model.GroupProfileType.Library, otherGroupId to com.achulkov.evokammer.core.model.GroupProfileType.Library),
            ringTopology = listOf(winnerId, otherGroupId)
        )
        metaDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = UUID.randomUUID().toString(),
                runId = runId,
                entryType = RunArchiveService.ENTRY_STARTING_FIELD,
                groupId = null,
                label = "StartingField — $runId",
                contentJson = json.encodeToString(StartingField.serializer(), startingField),
                runObjective = startingField.objective,
                finalScore = null,
                isFromWinner = false,
                generation = 0,
                archivedAtMs = System.currentTimeMillis()
            )
        )
        metaDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = "run-completed-$runId",
                runId = runId,
                entryType = RunArchiveService.ENTRY_RUN_COMPLETED,
                groupId = null,
                label = "Run Completed — $runId",
                contentJson = json.encodeToString(
                    RunCompletionRecord.serializer(),
                    RunCompletionRecord(
                        runId = runId,
                        winnerId = winnerId,
                        finalAnswerSummary = "Round 1 final answer: winner=$winnerId",
                        submissionSummariesByGroup = mapOf(
                            winnerId to "ROUND SUBMISSION SUMMARY\n- Winner summary without embedded html.",
                            otherGroupId to """
                                ROUND SUBMISSION SUMMARY
                                - Non-winner summary carries the final deliverable.

                                FINAL DELIVERABLE HTML
                                <!doctype html>
                                <html lang="en">
                                <head><meta charset="utf-8"><title>Fallback article</title></head>
                                <body><article><h1>Fallback article</h1><p>Chosen from the first available embedded html block.</p></article></body>
                                </html>
                            """.trimIndent()
                        )
                    )
                ),
                runObjective = startingField.objective,
                finalScore = null,
                isFromWinner = false,
                generation = 1,
                archivedAtMs = System.currentTimeMillis()
            )
        )

        val bundle = service.buildResultsBundle(runId)
        assertNotNull(bundle)

        val entries = unzipEntries(bundle)
        assertTrue(entries["outputs/final-article.html"]!!.contains("Fallback article"))
        assertTrue(entries["outputs/final-article.html"]!!.contains("Chosen from the first available embedded html block."))
    }

    private fun unzipEntries(bytes: ByteArray): Map<String, String> {
        val result = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entry ->
                val content = zip.readBytes().toString(Charsets.UTF_8)
                result[entry.name] = content
                zip.closeEntry()
            }
        }
        return result
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }
}
