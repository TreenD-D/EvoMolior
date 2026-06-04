package com.achulkov.evokammer.cli

import com.achulkov.evokammer.api.events.EvoKammerEvent
import com.achulkov.evokammer.api.model.RunConfig
import com.achulkov.evokammer.api.service.EngineState
import com.achulkov.evokammer.api.service.EngineStatus
import com.achulkov.evokammer.api.service.EvoKammerEngineService
import com.achulkov.evokammer.api.service.GroupSummary
import com.achulkov.evokammer.api.service.MetaArchiveEntryRecord
import com.achulkov.evokammer.api.service.MetaArchiveSummary
import com.achulkov.evokammer.core.config.FastModelSelectionConfig
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.ZipInputStream

class CliArgumentsTest {

    @Test
    fun `parses prompt, input, output, debug, api key, and model overrides`() {
        val inputDir = createTempDirectory("cli-input-").toFile()
        val outputDir = createTempDirectory("cli-output-").toFile()

        val options = CliArguments.parse(
            arrayOf(
                "-prompt",
                "Solve the task",
                "-input",
                inputDir.absolutePath,
                "-output",
                outputDir.absolutePath,
                "-openrouter-api-key",
                "sk-test",
                "-fast",
                "-rounds",
                "5",
                "-debug",
                "-model",
                "RESEARCHER=qwen/qwen3.5-flash-02-23",
                "-model",
                "CRITIC=deepseek/deepseek-v4-flash"
            )
        )

        assertNotNull(options)
        assertEquals("Solve the task", options.prompt)
        assertEquals(inputDir.toPath().toAbsolutePath().normalize(), options.inputDir)
        assertEquals(outputDir.toPath().toAbsolutePath().normalize(), options.outputDir)
        assertEquals("sk-test", options.openRouterApiKey)
        assertTrue(options.debug)
        assertTrue(options.fast)
        assertEquals(5, options.rounds)
        assertEquals("qwen/qwen3.5-flash-02-23", options.modelOverrides["RESEARCHER"])
        assertEquals("deepseek/deepseek-v4-flash", options.modelOverrides["CRITIC"])
    }

    @Test
    fun `rejects specifying both prompt sources`() {
        val inputDir = createTempDirectory("cli-input-").toFile()
        val outputDir = createTempDirectory("cli-output-").toFile()
        val promptFile = createTempDirectory("cli-prompt-").toFile().resolve("prompt.txt").apply {
            writeText("hello prompt")
        }

        assertFailsWith<CliUsageException> {
            CliArguments.parse(
                arrayOf(
                    "-prompt",
                    "Solve the task",
                    "-promptfile",
                    promptFile.absolutePath,
                    "-input",
                    inputDir.absolutePath,
                    "-output",
                    outputDir.absolutePath,
                    "-openrouter-api-key",
                    "sk-test"
                )
            )
        }
    }

    @Test
    fun `runCli writes the per-run output bundle using a fake engine`() = runBlocking {
        val inputDir = createTempDirectory("cli-smoke-input-").toFile().apply {
            resolve("notes.txt").writeText("hello from input")
        }
        val outputDir = createTempDirectory("cli-smoke-output-").toFile()
        val fakeEngine = FakeCliEngineService()

        val result = runCli(
            options = CliOptions(
                prompt = "Smoke test objective",
                inputDir = inputDir.toPath(),
                outputDir = outputDir.toPath(),
                openRouterApiKey = "sk-test",
                modelOverrides = mapOf("RESEARCHER" to "test-model"),
                debug = false,
                fast = false,
                rounds = 1,
                runId = "cli-smoke"
            ),
            runtimeFactory = { _, _ -> CliRuntime(fakeEngine) }
        )

        assertTrue(result.completedSuccessfully)

        val runOutputDir = outputDir.resolve("cli-smoke")
        assertTrue(runOutputDir.resolve("prompt.txt").exists())
        assertTrue(runOutputDir.resolve("summary.json").exists())
        assertTrue(runOutputDir.resolve("results.zip").exists())
        assertEquals("Smoke test objective", runOutputDir.resolve("prompt.txt").readText())
        assertTrue(runOutputDir.resolve("summary.json").readText().contains("\"runId\": \"cli-smoke\""))
        assertTrue(fakeEngine.capturedConfig != null)
        assertEquals("cli-smoke", fakeEngine.capturedConfig!!.runId)
        assertEquals("Smoke test objective", fakeEngine.capturedConfig!!.objective)
        assertEquals(0, fakeEngine.capturedConfig!!.roundDurationMinutes)
    }

    @Test
    fun `runCli fetches a results bundle that already contains the final article after run completion`() = runBlocking {
        val inputDir = createTempDirectory("cli-fast-input-").toFile()
        val outputDir = createTempDirectory("cli-fast-output-").toFile()
        val fakeEngine = FakeCliBundleEngineService()

        val result = runCli(
            options = CliOptions(
                prompt = "MindVortex article smoke test",
                inputDir = inputDir.toPath(),
                outputDir = outputDir.toPath(),
                openRouterApiKey = "sk-test",
                modelOverrides = emptyMap(),
                debug = true,
                fast = true,
                rounds = 5,
                runId = "cli-fast-bundle"
            ),
            runtimeFactory = { _, _ -> CliRuntime(fakeEngine) }
        )

        assertTrue(result.completedSuccessfully)
        assertTrue(fakeEngine.resultsBundleRequestedAfterCompletion)

        val bundleFile = outputDir.resolve("cli-fast-bundle").resolve("results.zip")
        assertTrue(bundleFile.exists())

        val entries = unzipEntries(bundleFile.readBytes())
        assertTrue(entries.keys.contains("outputs/final-article.html"))
        assertTrue(entries["outputs/final-article.html"]!!.contains("MindVortex fast-run article"))
    }

    @Test
    fun `fast mode only accepts the approved model set`() {
        val inputDir = createTempDirectory("cli-input-").toFile()
        val outputDir = createTempDirectory("cli-output-").toFile()

        val options = CliArguments.parse(
            arrayOf(
                "-prompt",
                "Fast mode test",
                "-input",
                inputDir.absolutePath,
                "-output",
                outputDir.absolutePath,
                "-openrouter-api-key",
                "sk-test",
                "-fast"
            )
        )

        assertNotNull(options)
        assertTrue(options.fast)
        assertTrue(options.modelOverrides.values.all { it in FastModelSelectionConfig.approvedModelIds })
    }

    @Test
    fun `fast mode run uses a non-zero round duration`() = runBlocking {
        val inputDir = createTempDirectory("cli-fast-input-").toFile()
        val outputDir = createTempDirectory("cli-fast-output-").toFile()
        val fakeEngine = FakeCliEngineService()

        runCli(
            options = CliOptions(
                prompt = "Fast duration objective",
                inputDir = inputDir.toPath(),
                outputDir = outputDir.toPath(),
                openRouterApiKey = "sk-test",
                modelOverrides = emptyMap(),
                debug = true,
                fast = true,
                rounds = 1,
                runId = "cli-fast-duration"
            ),
            runtimeFactory = { _, _ -> CliRuntime(fakeEngine) }
        )

        assertNotNull(fakeEngine.capturedConfig)
        assertEquals(1, fakeEngine.capturedConfig!!.roundDurationMinutes)
    }

    @Test
    fun `fast browser article run uses richer grading rubric and more worker capacity`() = runBlocking {
        val inputDir = createTempDirectory("cli-fast-article-input-").toFile()
        val outputDir = createTempDirectory("cli-fast-article-output-").toFile()
        val fakeEngine = FakeCliEngineService()

        runCli(
            options = CliOptions(
                prompt = "Create a long-form HTML5 article for mindvortex.app with embedded styles",
                inputDir = inputDir.toPath(),
                outputDir = outputDir.toPath(),
                openRouterApiKey = "sk-test",
                modelOverrides = emptyMap(),
                debug = true,
                fast = true,
                rounds = 10,
                runId = "cli-fast-article"
            ),
            runtimeFactory = { _, _ -> CliRuntime(fakeEngine) }
        )

        val capturedConfig = assertNotNull(fakeEngine.capturedConfig)
        assertEquals(2, capturedConfig.maxAgentsPerGroup)
        assertEquals(220.0, capturedConfig.budgetOverride)
        assertEquals(
            listOf(
                "content_depth",
                "semantic_html",
                "visual_polish",
                "product_positioning",
                "evidence_fidelity"
            ),
            capturedConfig.scoringDimensions.map { it.id }
        )
    }
}

private class FakeCliEngineService : EvoKammerEngineService {
    private val _events = MutableSharedFlow<EvoKammerEvent>(replay = 2, extraBufferCapacity = 8)
    override val events: SharedFlow<EvoKammerEvent> = _events.asSharedFlow()

    var capturedConfig: RunConfig? = null

    override suspend fun startRun(config: RunConfig): String {
        capturedConfig = config
        _events.emit(EvoKammerEvent.RunStarted(config.runId, config.objective))
        _events.emit(EvoKammerEvent.RunCompleted(config.runId, "group-0"))
        return config.runId
    }

    override suspend fun pauseRun(runId: String) = Unit
    override suspend fun resumeRun(runId: String) = Unit
    override suspend fun abortRun(runId: String, reason: String) = Unit
    override suspend fun getState(): EngineState = EngineState(
        status = EngineStatus.IDLE,
        activeRunId = null,
        currentRound = null,
        groupSummaries = emptyList()
    )
    override suspend fun getMetaArchiveSummary(runId: String): MetaArchiveSummary? = null
    override suspend fun getMetaArchiveEntries(runId: String, entryType: String?): List<MetaArchiveEntryRecord> = emptyList()
    override suspend fun getResultsBundle(runId: String): ByteArray? = byteArrayOf(1, 2, 3)
}

private class FakeCliBundleEngineService : EvoKammerEngineService {
    private val _events = MutableSharedFlow<EvoKammerEvent>(replay = 2, extraBufferCapacity = 8)
    override val events: SharedFlow<EvoKammerEvent> = _events.asSharedFlow()

    private var runCompletedEmitted = false
    var resultsBundleRequestedAfterCompletion = false

    override suspend fun startRun(config: RunConfig): String {
        _events.emit(EvoKammerEvent.RunStarted(config.runId, config.objective))
        runCompletedEmitted = true
        _events.emit(EvoKammerEvent.RunCompleted(config.runId, "group-0"))
        return config.runId
    }

    override suspend fun pauseRun(runId: String) = Unit
    override suspend fun resumeRun(runId: String) = Unit
    override suspend fun abortRun(runId: String, reason: String) = Unit
    override suspend fun getState(): EngineState = EngineState(
        status = EngineStatus.IDLE,
        activeRunId = null,
        currentRound = null,
        groupSummaries = emptyList()
    )
    override suspend fun getMetaArchiveSummary(runId: String): MetaArchiveSummary? = null
    override suspend fun getMetaArchiveEntries(runId: String, entryType: String?): List<MetaArchiveEntryRecord> = emptyList()
    override suspend fun getResultsBundle(runId: String): ByteArray? {
        check(runCompletedEmitted) { "results bundle requested before RunCompleted was emitted" }
        resultsBundleRequestedAfterCompletion = true
        return buildBundleBytes()
    }

    private fun buildBundleBytes(): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            fun addEntry(path: String, content: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }

            addEntry("outputs/final-article.html", "<!doctype html><html><body>MindVortex fast-run article</body></html>")
            addEntry("manifest.json", "{\"artifactCount\":0}")
        }
        return output.toByteArray()
    }
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
