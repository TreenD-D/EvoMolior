package com.achulkov.evomolior.cli

import com.achulkov.evomolior.core.config.LlmBackend
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RunStoragePreflightTest {
    private val gib = 1024L * 1024 * 1024

    @Test
    fun `floor defaults to two GiB and honours the MiB override`() {
        assertEquals(RunStoragePreflight.DEFAULT_MIN_FREE_BYTES, RunStoragePreflight.requiredFreeBytes(emptyMap()))
        assertEquals(
            512L * 1024 * 1024,
            RunStoragePreflight.requiredFreeBytes(mapOf(RunStoragePreflight.MIN_FREE_ENV to " 512 "))
        )
        assertEquals(0L, RunStoragePreflight.requiredFreeBytes(mapOf(RunStoragePreflight.MIN_FREE_ENV to "0")))
        assertFailsWith<CliUsageException> {
            RunStoragePreflight.requiredFreeBytes(mapOf(RunStoragePreflight.MIN_FREE_ENV to "-1"))
        }
        assertFailsWith<CliUsageException> {
            RunStoragePreflight.requiredFreeBytes(mapOf(RunStoragePreflight.MIN_FREE_ENV to "2GB"))
        }
    }

    @Test
    fun `codex runs also check the codex temp authority`() {
        val output = createTempDirectory("storage-preflight-output-")
        val jvmTemp = Path.of("/jvm-temp")
        val codexTemp = Path.of("/codex-temp")

        val codex = RunStoragePreflight.locations(
            options(output, LlmBackend.CODEX_CHATGPT),
            env = mapOf("EVOMOLIOR_CODEX_TMPDIR" to codexTemp.toString()),
            systemTemp = jvmTemp
        )
        val openRouter = RunStoragePreflight.locations(
            options(output, LlmBackend.OPENROUTER),
            env = mapOf("EVOMOLIOR_CODEX_TMPDIR" to codexTemp.toString()),
            systemTemp = jvmTemp
        )

        assertEquals(listOf(output, jvmTemp, codexTemp), codex.map { it.path })
        assertEquals(listOf(output, jvmTemp), openRouter.map { it.path })
    }

    @Test
    fun `low volume is rejected with every location that shares it`() {
        val roomy = createTempDirectory("storage-preflight-roomy-")
        val full = createTempDirectory("storage-preflight-full-")
        val locations = listOf(
            RunStoragePreflight.Location("run output (-output)", roomy.resolve("not-created-yet/run")),
            RunStoragePreflight.Location("JVM temp (java.io.tmpdir)", full),
            RunStoragePreflight.Location("Codex temp (EVOMOLIOR_CODEX_TMPDIR)", full.resolve("codex"))
        )

        val error = assertFailsWith<CliStoragePreflightException> {
            RunStoragePreflight.verify(
                locations = locations,
                requiredFreeBytes = 2 * gib,
                volumeKey = { path -> if (path.startsWith(full)) "internal" else "external" },
                usableSpace = { path -> if (path.startsWith(full)) gib / 2 else 200 * gib }
            )
        }

        val message = error.message.orEmpty()
        assertTrue(message.contains("JVM temp (java.io.tmpdir) + Codex temp (EVOMOLIOR_CODEX_TMPDIR)"), message)
        assertTrue(message.contains("512.0 MiB usable"), message)
        assertTrue(message.contains("2.0 GiB"), message)
        assertTrue(!message.contains("run output"), message)
        // The preflight inspects the nearest existing ancestor and never creates directories.
        assertTrue(!roomy.resolve("not-created-yet").toFile().exists())
    }

    @Test
    fun `sufficient volumes are reported once each and a zero floor skips measurement`() {
        val directory = createTempDirectory("storage-preflight-ok-")
        val measured = AtomicInteger()
        val locations = listOf(
            RunStoragePreflight.Location("run output (-output)", directory),
            RunStoragePreflight.Location("JVM temp (java.io.tmpdir)", directory.resolve("tmp"))
        )

        val statuses = RunStoragePreflight.verify(
            locations = locations,
            requiredFreeBytes = gib,
            volumeKey = { "same-volume" },
            usableSpace = { measured.incrementAndGet(); 5 * gib }
        )
        assertEquals(1, statuses.size)
        assertEquals(listOf("run output (-output)", "JVM temp (java.io.tmpdir)"), statuses.single().labels)
        assertEquals(1, measured.get())

        val skipped = RunStoragePreflight.verify(
            locations = locations,
            requiredFreeBytes = 0,
            volumeKey = { error("must not inspect volumes when disabled") },
            usableSpace = { error("must not inspect volumes when disabled") }
        )
        assertTrue(skipped.isEmpty())
    }

    @Test
    fun `runCli rejects before creating any runtime when storage preflight fails`() {
        val output = createTempDirectory("storage-preflight-cli-output-")
        val runtimeCreations = AtomicInteger()

        assertFailsWith<CliStoragePreflightException> {
            runCli(
                options = options(output, LlmBackend.OPENROUTER),
                runtimeFactory = { _, _ ->
                    runtimeCreations.incrementAndGet()
                    error("provider runtime must not be created")
                },
                storagePreflight = { throw CliStoragePreflightException("volume full") }
            )
        }

        assertEquals(0, runtimeCreations.get())
        assertTrue(output.toFile().listFiles().isNullOrEmpty())
    }

    private fun options(output: Path, backend: LlmBackend) = CliOptions(
        prompt = "Check storage before starting.",
        inputDir = createTempDirectory("storage-preflight-input-"),
        outputDir = output,
        openRouterApiKey = "test-key",
        llmBackend = backend,
        modelOverrides = emptyMap(),
        debug = false,
        fast = false,
        rounds = 1,
        runId = "storage-preflight"
    )
}
