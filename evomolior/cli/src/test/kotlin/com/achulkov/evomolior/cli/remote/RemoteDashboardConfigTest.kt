package com.achulkov.evomolior.cli.remote

import com.achulkov.evomolior.cli.CliUsageException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.ByteBuffer
import java.util.concurrent.Executors

class RemoteDashboardConfigTest {
    @Test
    fun missingCredentialsFailStartup() {
        assertFailsWith<CliUsageException> {
            RemoteDashboardConfig.parse(emptyArray(), emptyMap())
        }
    }

    @Test
    fun defaultBindingIsLoopbackOnly() {
        val config = RemoteDashboardConfig.parse(
            emptyArray(),
            mapOf(
                "EVOMOLIOR_DASHBOARD_USERNAME" to "operator",
                "EVOMOLIOR_DASHBOARD_PASSWORD" to "secret"
            )
        )

        assertEquals("127.0.0.1", config.host)
        assertFalse(config.secureCookie)
    }

    @Test
    fun nonLoopbackBindingRequiresSecureCookieAndHttpsTermination() {
        assertFailsWith<CliUsageException> {
            RemoteDashboardConfig.parse(
                arrayOf("--host", "0.0.0.0"),
                mapOf(
                    "EVOMOLIOR_DASHBOARD_USERNAME" to "operator",
                    "EVOMOLIOR_DASHBOARD_PASSWORD" to "secret"
                )
            )
        }

        val config = RemoteDashboardConfig.parse(
            arrayOf("--host", "0.0.0.0"),
            mapOf(
                "EVOMOLIOR_DASHBOARD_USERNAME" to "operator",
                "EVOMOLIOR_DASHBOARD_PASSWORD" to "secret",
                "EVOMOLIOR_DASHBOARD_SECURE_COOKIE" to "true"
            )
        )
        assertTrue(config.secureCookie)
    }

    @Test
    fun environmentAndArgumentsResolveConfig() {
        val config = RemoteDashboardConfig.parse(
            arrayOf("--host", "127.0.0.1", "--port", "9090", "--data-dir", "dashboard-data", "--cli-bin", "/bin/evomolior"),
            mapOf(
                "EVOMOLIOR_DASHBOARD_USERNAME" to "operator",
                "EVOMOLIOR_DASHBOARD_PASSWORD" to "secret"
            )
        )

        assertEquals("127.0.0.1", config.host)
        assertEquals(9090, config.port)
        assertEquals("operator", config.username)
        assertEquals("secret", config.password)
        assertEquals("/bin/evomolior", config.cliExecutable)
        assertEquals("dashboard-data", config.dataDir.fileName.toString())
        assertEquals(2, config.maxConcurrentRuns)
    }

    @Test
    fun outputPathTraversalIsRejected() {
        val root = Files.createTempDirectory("evomolior-dashboard-test")
        val output = root.resolve("runs/run-1/output")
        Files.createDirectories(output)
        Files.createDirectories(output.resolve("outputs"))
        Files.writeString(output.resolve("outputs/answer.txt"), "ok")
        Files.createDirectories(output.resolve(".evomolior-data/databases"))
        Files.writeString(output.resolve(".evomolior-data/databases/substrate.db"), "private")
        val registry = RemoteRunRegistry(root)
        val record = RemoteRunRecord(
            id = "run-1",
            type = "run",
            status = RemoteRunStatus.SUCCEEDED,
            startedAt = "2026-07-04T00:00:00Z",
            command = listOf("evomolior"),
            outputDir = output.toString(),
            logPath = root.resolve("run.log").toString()
        )

        assertEquals(output.resolve("outputs/answer.txt"), safeOutputPathForTest(registry, record, "outputs/answer.txt"))
        val opened = assertNotNull(registry.openOutputFile(record, "outputs/answer.txt"))
        Files.delete(output.resolve("outputs/answer.txt"))
        Files.writeString(output.resolve("outputs/answer.txt"), "replacement")
        val buffer = ByteBuffer.allocate(2)
        assertEquals(2, opened.channel.read(buffer))
        assertEquals("ok", String(buffer.array()))
        opened.close()
        Files.writeString(output.resolve("summary.json"), """{"prompt":"first"}""")
        val previewOpened = assertNotNull(registry.openPreviewFile(record, "summary.json"))
        Files.delete(output.resolve("summary.json"))
        Files.writeString(output.resolve("summary.json"), """{"prompt":"replacement"}""")
        val preview = assertNotNull(
            RemoteRunObservability(registry).preview("summary.json", previewOpened)
        )
        previewOpened.close()
        assertFalse(preview.content.contains("replacement"))
        assertNull(safeOutputPathForTest(registry, record, "../secret.txt"))
        assertNull(safeOutputPathForTest(registry, record, "../../../../etc/outputs/passwd.md"))
        assertNull(safeOutputPathForTest(registry, record, root.resolve("secret.txt").toString()))
        assertNull(safeOutputPathForTest(registry, record, ".evomolior-data/databases/substrate.db"))
        Files.writeString(output.resolve("notes.md"), "private")
        assertNull(safeOutputPathForTest(registry, record, "notes.md"))
    }

    @Test
    fun invalidConcurrencyLimitFailsStartup() {
        assertFailsWith<CliUsageException> {
            RemoteDashboardConfig.parse(
                emptyArray(),
                mapOf(
                    "EVOMOLIOR_DASHBOARD_USERNAME" to "operator",
                    "EVOMOLIOR_DASHBOARD_PASSWORD" to "secret",
                    "EVOMOLIOR_DASHBOARD_MAX_CONCURRENT_RUNS" to "0"
                )
            )
        }
    }

    @Test
    fun invalidRealtimeConnectionLimitFailsStartup() {
        assertFailsWith<CliUsageException> {
            RemoteDashboardConfig.parse(
                emptyArray(),
                mapOf(
                    "EVOMOLIOR_DASHBOARD_USERNAME" to "operator",
                    "EVOMOLIOR_DASHBOARD_PASSWORD" to "secret",
                    "EVOMOLIOR_DASHBOARD_MAX_SSE_CONNECTIONS" to "0"
                )
            )
        }
    }

    @Test
    fun outputTransferRunsBlockingWorkOnTheIoDispatcherAndClosesTheDescriptor() {
        val file = Files.createTempFile("evomolior-dashboard-transfer", ".txt")
        Files.writeString(file, "descriptor-safe payload")
        val channel = Files.newByteChannel(file)
        val opened = RemoteOpenedOutput(file.fileName.toString(), Files.size(file), channel)
        val writtenOn = mutableListOf<String>()
        val output = object : ByteArrayOutputStream() {
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                writtenOn += Thread.currentThread().name
                super.write(buffer, offset, length)
            }
        }
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "dashboard-file-io")
        }
        val dispatcher = executor.asCoroutineDispatcher()

        try {
            runBlocking {
                transferOpenedOutput(opened, output, dispatcher)
            }
        } finally {
            dispatcher.close()
            executor.shutdownNow()
        }

        assertEquals("descriptor-safe payload", output.toString(Charsets.UTF_8))
        assertTrue(writtenOn.isNotEmpty())
        assertTrue(writtenOn.all { it.startsWith("dashboard-file-io") })
        assertFalse(channel.isOpen)
    }
}
