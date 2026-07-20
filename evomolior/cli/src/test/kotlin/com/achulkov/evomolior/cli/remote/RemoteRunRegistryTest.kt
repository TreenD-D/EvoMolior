package com.achulkov.evomolior.cli.remote

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class RemoteRunRegistryTest {
    @Test
    fun launchMetadataPersistsAndReloads() = runTest {
        val root = Files.createTempDirectory("dashboard-registry")
        val registry = RemoteRunRegistry(root)
        val launch = RemoteRunLaunch(
            backend = "openrouter",
            runner = "single-agent",
            fast = false,
            benchmark = "frames",
            limit = 4,
            offset = 2
        )

        registry.create(
            id = "persisted",
            type = "benchmark",
            command = listOf("evomolior", "benchmark"),
            outputDir = root.resolve("runs/persisted/output"),
            logPath = root.resolve("runs/persisted/run.log"),
            launch = launch
        )

        val reloaded = RemoteRunRegistry(root)
        reloaded.load()
        val record = assertNotNull(reloaded.get("persisted"))
        assertEquals(launch, record.launch)
        assertTrue(root.resolve("runs/persisted/run.json").readText().contains("\"schemaVersion\""))
        assertFalse(Files.exists(root.resolve("runs/persisted/run.json.tmp")))
    }

    @Test
    fun oldMetadataWithoutNewFieldsRemainsLoadable() = runTest {
        val root = Files.createTempDirectory("dashboard-registry-legacy")
        val runDir = root.resolve("runs/legacy").also(Files::createDirectories)
        Files.writeString(
            runDir.resolve("run.json"),
            """
            {
              "id": "legacy",
              "type": "run",
              "status": "SUCCEEDED",
              "startedAt": "2026-07-01T00:00:00Z",
              "command": ["evomolior", "run"],
              "outputDir": "${runDir.resolve("output").toString().replace("\\", "\\\\")}",
              "logPath": "${runDir.resolve("run.log").toString().replace("\\", "\\\\")}"
            }
            """.trimIndent()
        )

        val registry = RemoteRunRegistry(root)
        registry.load()

        val record = assertNotNull(registry.get("legacy"))
        assertEquals(1, record.schemaVersion)
        assertEquals(null, record.launch)
    }

    @Test
    fun activeRecordsAreRecoveredAsInterruptedAfterRestart() = runTest {
        val root = Files.createTempDirectory("dashboard-registry-recovery")
        val registry = RemoteRunRegistry(root)
        registry.create(
            id = "interrupted",
            type = "run",
            command = listOf("evomolior", "run"),
            outputDir = root.resolve("runs/interrupted/output"),
            logPath = root.resolve("runs/interrupted/run.log")
        )
        val processStartedAt = Instant.parse("2026-07-18T10:00:00Z")
        registry.markRunning("interrupted", 1234, processStartedAt)

        var reconciled: RemoteRunRecord? = null
        val reloaded = RemoteRunRegistry(
            root,
            activeProcessReconciler = RemoteActiveProcessReconciler { record ->
                reconciled = record
                "The verified test child was reconciled."
            }
        )
        reloaded.load()

        val record = assertNotNull(reloaded.get("interrupted"))
        assertEquals(RemoteRunStatus.FAILED, record.status)
        assertContains(record.error.orEmpty(), "restart")
        assertNotNull(record.completedAt)
        assertEquals(1234, reconciled?.processId)
        assertEquals(null, record.processId)
        assertEquals(null, record.processStartedAt)
    }

    @Test
    fun unsafeActiveProcessReconciliationFailsRegistryStartup() = runTest {
        val root = Files.createTempDirectory("dashboard-registry-unsafe-recovery")
        val registry = RemoteRunRegistry(root)
        registry.create(
            id = "unreconciled",
            type = "run",
            command = listOf("evomolior", "run"),
            outputDir = root.resolve("runs/unreconciled/output"),
            logPath = root.resolve("runs/unreconciled/run.log")
        )
        registry.markRunning("unreconciled", 4321, Instant.parse("2026-07-18T10:00:00Z"))

        val reloaded = RemoteRunRegistry(
            root,
            activeProcessReconciler = RemoteActiveProcessReconciler {
                error("process identity cannot be verified")
            }
        )

        assertFailsWith<IllegalStateException> { reloaded.load() }
    }

    @Test
    fun persistedLiveChildIsTerminatedBeforeRegistryAdmission() = runTest {
        val shell = Path.of("/bin/sh")
        if (!Files.isExecutable(shell)) return@runTest
        val root = Files.createTempDirectory("dashboard-registry-live-recovery")
        val process = ProcessBuilder(shell.toString(), "-c", "sleep 30").start()
        try {
            val startedAt = assertNotNull(process.toHandle().info().startInstant().orElse(null))
            val registry = RemoteRunRegistry(root)
            registry.create(
                id = "live-child",
                type = "run",
                command = listOf(shell.toString(), "-c", "sleep 30"),
                outputDir = root.resolve("runs/live-child/output"),
                logPath = root.resolve("runs/live-child/run.log")
            )
            registry.markRunning("live-child", process.pid(), startedAt)

            val reloaded = RemoteRunRegistry(root)
            reloaded.load()

            assertFalse(process.isAlive)
            assertEquals(RemoteRunStatus.FAILED, reloaded.get("live-child")?.status)
        } finally {
            process.toHandle().takeIf(ProcessHandle::isAlive)?.let(::terminateProcessTree)
        }
    }

    @Test
    fun registryStartupIsBoundedByRunDirectoryCount() = runTest {
        val root = Files.createTempDirectory("dashboard-registry-count-limit")
        repeat(3) { index ->
            Files.createDirectories(root.resolve("runs/run-$index"))
        }

        val registry = RemoteRunRegistry(
            root,
            limits = RemoteRunRegistryLimits(maxRunDirectories = 2)
        )

        assertFailsWith<IllegalStateException> { registry.load() }
    }

    @Test
    fun oversizedMetadataIsSkippedWithABoundedDiagnostic() = runTest {
        val root = Files.createTempDirectory("dashboard-registry-metadata-limit")
        val runDir = root.resolve("runs/oversized").also(Files::createDirectories)
        Files.writeString(runDir.resolve("run.json"), "x".repeat(65))
        val registry = RemoteRunRegistry(
            root,
            limits = RemoteRunRegistryLimits(maxMetadataBytes = 64)
        )

        val report = registry.load()

        assertEquals(0, report.loadedCount)
        assertEquals(1, report.skippedCount)
        assertTrue(report.warnings.single().contains("oversized"))
        assertTrue(registry.list().isEmpty())
    }

    @Test
    fun symlinkedOutputRootsAncestorsAndLogsAreRejected() {
        val root = Files.createTempDirectory("dashboard-registry-symlinks")
        val runDir = root.resolve("runs/run-1").also(Files::createDirectories)
        val external = root.resolve("external").also(Files::createDirectories)
        Files.writeString(external.resolve("secret.txt"), "secret")
        Files.createSymbolicLink(runDir.resolve("output"), external)
        Files.createSymbolicLink(runDir.resolve("run.log"), external.resolve("secret.txt"))
        val registry = RemoteRunRegistry(root)
        val record = RemoteRunRecord(
            id = "run-1",
            type = "run",
            status = RemoteRunStatus.SUCCEEDED,
            startedAt = "2026-07-18T00:00:00Z",
            command = listOf("evomolior"),
            outputDir = runDir.resolve("output").toString(),
            logPath = runDir.resolve("run.log").toString()
        )

        assertEquals(null, registry.resolveOutputFile(record, "secret.txt"))
        assertEquals(null, registry.openLog(record))

        Files.delete(runDir.resolve("output"))
        val output = runDir.resolve("output").also(Files::createDirectories)
        Files.createSymbolicLink(output.resolve("linked"), external)
        assertEquals(null, registry.resolveOutputFile(record, "linked/secret.txt"))
    }
}
