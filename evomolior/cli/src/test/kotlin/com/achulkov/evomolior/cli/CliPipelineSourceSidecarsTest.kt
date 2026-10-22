@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.cli

import com.achulkov.evomolior.api.service.RunArchiveService
import com.achulkov.evomolior.api.service.RunCompletionRecord
import com.achulkov.evomolior.api.service.RecoveryBundleEntry
import com.achulkov.evomolior.api.service.RecoveryBundleEntryCategory
import com.achulkov.evomolior.api.service.RecoveryBundleManifest
import com.achulkov.evomolior.api.service.RecoveredRunRecord
import com.achulkov.evomolior.api.service.RunRecoveryFailureClass
import com.achulkov.evomolior.api.service.ReportSourceSidecars
import com.achulkov.evomolior.core.contract.StartingField
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CliPipelineSourceSidecarsTest {
    @Test
    fun `legacy recovery sidecars verify record manifest hashes and independent run identity`() {
        val root = createTempDirectory("legacy-recovery-source-check-")
        val runId = "legacy-run"
        val reportPath = "recovery/recovery-notice.md"
        val report = "Useful non-accepted recovery report.".encodeToByteArray()
        val sidecarPath = ReportSourceSidecars.pathFor(runId, reportPath)
        val payload = linkedMapOf(
            reportPath to report,
            sidecarPath to ReportSourceSidecars.render(runId, reportPath, report, emptyList()),
        )
        fun authority(path: String, bytes: ByteArray, category: RecoveryBundleEntryCategory) =
            RecoveryBundleEntry(
                path, hash(bytes), bytes.size.toLong(), category,
            )
        val commitments = payload.map { (path, bytes) ->
            authority(path, bytes, RecoveryBundleEntryCategory.RECOVERY_NOTICE)
        }.sortedBy { it.path }
        val manifest = RecoveryBundleManifest(runId = runId, entries = commitments)
        val manifestBytes = Json.encodeToString(RecoveryBundleManifest.serializer(), manifest).encodeToByteArray()
        val record = RecoveredRunRecord(
            runId = runId,
            acceptanceProfile = "normal",
            completedRoundCount = 1,
            failureClass = RunRecoveryFailureClass.FINAL_OUTPUT_REJECTED,
            failureSummary = "Non-accepted report retained",
            opaqueFailureDigest = hash("failure".encodeToByteArray()),
            committedBundleEntries = commitments,
            manifestAuthority = authority(
                RecoveryBundleManifest.MANIFEST_PATH, manifestBytes, RecoveryBundleEntryCategory.RECOVERY_MANIFEST,
            ),
            startingFieldHash = hash("starting-field".encodeToByteArray()),
            preRecoveryPersistedRunStateHash = null,
            recoveredAtMs = 0,
        )
        val files = payload + mapOf(
            RecoveryBundleManifest.MANIFEST_PATH to manifestBytes,
            RecoveryBundleManifest.RECOVERY_RECORD_PATH to
                Json.encodeToString(RecoveredRunRecord.serializer(), record).encodeToByteArray(),
        )
        val archive = root.resolve("recovery-results.zip").toFile().apply { writeBytes(zip(files)) }
        val immutableBytes = archive.readBytes()
        val outputs = root.resolve("outputs")
        publishStageSourceSidecars(archive, outputs, expectedRunId = runId)
        assertContentEquals(payload.getValue(sidecarPath), Files.readAllBytes(outputs.resolve(sidecarPath)))
        assertContentEquals(immutableBytes, archive.readBytes())
        assertFailsWith<IllegalArgumentException> {
            publishStageSourceSidecars(archive, root.resolve("wrong-run"), expectedRunId = "other-run")
        }
        assertFailsWith<IllegalArgumentException> {
            publishStageSourceSidecars(archive, root.resolve("no-run"))
        }
        listOf(reportPath, sidecarPath, RecoveryBundleManifest.MANIFEST_PATH).forEachIndexed { index, path ->
            val tampered = root.resolve("tampered-$index.zip").toFile().apply {
                writeBytes(zip(files + (path to "tampered".encodeToByteArray())))
            }
            assertFailsWith<IllegalArgumentException> {
                publishStageSourceSidecars(tampered, root.resolve("tampered-output-$index"), expectedRunId = runId)
            }
        }
    }

    @Test
    fun `publication copies manifest committed sidecars without changing historical archive`() = runTest {
        val root = createTempDirectory("pipeline-source-check-").toFile()
        val factory = SubstrateDatabaseFactory(root)
        try {
            val service = RunArchiveService(factory)
            val field = StartingField.default("Source-check fixture", runId = "run-source-check", groupCount = 1)
            service.archiveRun(
                runId = field.runId, groupIds = listOf("group-0"), winnerId = "group-0",
                objective = field.objective,
                startingFieldJson = Json.encodeToString(StartingField.serializer(), field),
                finalGeneration = 1,
                completionRecord = RunCompletionRecord(
                    runId = field.runId, winnerId = "group-0", finalAnswerSummary = "Useful report",
                ),
            )
            val bytes = assertNotNull(service.buildResultsBundle(field.runId))
            val archive = root.resolve("immutable.zip").apply { writeBytes(bytes) }
            val outputs = root.resolve("published").toPath()
            publishStageSourceSidecars(archive, outputs)
            publishStageSourceSidecars(archive, outputs)
            assertContentEquals(bytes, archive.readBytes())
            ZipFile(archive).use { zip ->
                val sidecars = zip.entries().asSequence().filter { it.name.startsWith("source-checks/") }.toList()
                assertTrue(sidecars.isNotEmpty())
                sidecars.forEach { entry ->
                    assertContentEquals(
                        zip.getInputStream(entry).use { it.readBytes() },
                        Files.readAllBytes(outputs.resolve(entry.name)),
                    )
                }
                Files.writeString(outputs.resolve(sidecars.first().name), "different content")
                assertFailsWith<IllegalArgumentException> { publishStageSourceSidecars(archive, outputs) }
            }
        } finally {
            listOf("group-0", "__meta_archive__", "__cost_ledger__").forEach {
                factory.close("run-source-check", it)
            }
            factory.getAtlas("run-source-check").close()
        }
    }

    @Test
    fun `unattested sidecars are rejected and historical archives are left untouched`() {
        val root = createTempDirectory("unattested-source-check-")
        val sidecarPath = "source-checks/${"a".repeat(64)}.md"
        val invalid = root.resolve("invalid.zip").toFile()
        invalid.writeBytes(zip(mapOf(sidecarPath to "unattested".encodeToByteArray())))
        assertFailsWith<IllegalArgumentException> {
            publishStageSourceSidecars(invalid, root.resolve("outputs"))
        }
        assertFalse(Files.exists(root.resolve("outputs")))
        val historical = root.resolve("historical.zip").toFile()
        val bytes = zip(mapOf("outputs/report.md" to "legacy".encodeToByteArray()))
        historical.writeBytes(bytes)
        publishStageSourceSidecars(historical, root.resolve("outputs"))
        assertContentEquals(bytes, historical.readBytes())
        assertFalse(Files.exists(root.resolve("outputs")))
    }

    private fun zip(files: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use { zip ->
            files.forEach { (path, bytes) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        output.toByteArray()
    }

    private fun hash(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
