package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.core.contract.StartingField
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.db.entity.MetaArchiveEntryEntity
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.util.zip.ZipInputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LegacyRecoveryBundleVerifierTest {
    @Test
    fun `actual legacy recovery publisher shares exact record and entry verification`() = runTest {
        val factory = SubstrateDatabaseFactory(createTempDirectory("legacy-recovery-verifier-").toFile())
        val runId = "legacy-recovery-verifier"
        val field = StartingField.default("Useful recovery report", runId = runId, groupCount = 1)
        val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
        val service = RunArchiveService(factory, json)
        try {
            factory.get(runId, "__meta_archive__").metaArchiveDao().insert(
                MetaArchiveEntryEntity(
                    id = "starting", runId = runId, entryType = RunArchiveService.ENTRY_STARTING_FIELD,
                    groupId = null, label = "StartingField",
                    contentJson = json.encodeToString(StartingField.serializer(), field),
                    runObjective = field.objective, finalScore = null, isFromWinner = false,
                    generation = 0, archivedAtMs = 0,
                ),
            )
            val record = service.archiveRecoveredRun(
                RecoveredRunArchiveRequest(
                    runId = runId, acceptanceProfile = "normal", completedRoundCount = 1,
                    failureClass = RunRecoveryFailureClass.FINAL_OUTPUT_REJECTED,
                    failureSummary = "Useful work retained", opaqueFailureDigest = "a".repeat(64),
                    normalPublicationState = null, recoverySourceGroupId = null,
                    requestedOutputSpec = null, recoveredOutput = null, recoveredOutputSource = null,
                    sourceRoundIndex = 0, recoveryNotice = "Non-accepted recovery report.",
                ),
            )
            val bytes = factory.runRootDir(runId).resolve("results/recovery-results.zip").readBytes()
            assertEquals(record, LegacyRecoveryBundleVerifier.verify(bytes, runId, record, json))
            assertEquals(record, LegacyRecoveryBundleVerifier.verify(bytes, runId))
            assertFailsWith<IllegalArgumentException> {
                LegacyRecoveryBundleVerifier.verify(bytes, "other-run")
            }
            assertFailsWith<IllegalArgumentException> {
                LegacyRecoveryBundleVerifier.verify(bytes, runId, record.copy(recoveredAtMs = record.recoveredAtMs + 1), json)
            }
            val files = linkedMapOf<String, ByteArray>()
            ZipInputStream(bytes.inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    files[entry.name] = zip.readBytes()
                    zip.closeEntry()
                }
            }
            val sidecars = record.committedBundleEntries.filter { it.path.startsWith("source-checks/") }
            assertTrue(sidecars.isNotEmpty())
            sidecars.forEach {
                assertEquals(it.sha256, sha256Hex(files.getValue(it.path)))
                assertTrue(files.getValue(it.path).decodeToString().contains(runId))
            }
        } finally {
            listOf("__meta_archive__", "__cost_ledger__").forEach { factory.close(runId, it) }
        }
    }
}
