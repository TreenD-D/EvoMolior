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

        val bundle = service.buildResultsBundle(runId)
        assertNotNull(bundle)

        val entries = unzipEntries(bundle)
        assertTrue(entries.keys.contains("manifest.json"))
        assertTrue(entries.keys.contains("input-artifacts.json"))
        assertTrue(entries.keys.contains("starting-field.json"))
        assertTrue(entries.keys.contains("inputs/docs/specs/brief.txt"))
        assertTrue(entries.keys.any { it.startsWith("outputs/dead_ends_export") })
        assertTrue(entries.keys.any { it.startsWith("outputs/round_summary") })
        assertTrue(entries["manifest.json"]!!.contains("\"artifactCount\":1"))
        assertTrue(entries["input-artifacts.json"]!!.contains("\"docs/specs/brief.txt\""))
        assertTrue(entries["starting-field.json"]!!.contains("\"inputArtifacts\""))
        assertEquals("hello archive", entries["inputs/docs/specs/brief.txt"])
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
