package com.achulkov.evomolior.cli

import com.achulkov.evomolior.api.model.FinalOutputKind
import com.achulkov.evomolior.core.config.CodexReasoningEffort
import com.achulkov.evomolior.core.config.LlmBackend
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CliStageRequestFingerprintTest {
    private val prompt = "Create a long-form HTML5 article for mindvortex.app with embedded styles"
    private val htmlSpec = CliPipelineFinalOutputSpec(
        relativePath = "final-article.html",
        mediaType = "text/html",
        description = "Browser-renderable final deliverable",
        kind = FinalOutputKind.HTML_ARTICLE
    )

    @Test
    fun `fingerprint is stable and changes with every request dimension`() {
        val input = createTempDirectory("fingerprint-input-").toFile().apply {
            resolve("brief.txt").writeText("brief v1")
            resolve("nested").mkdirs()
            resolve("nested/data.csv").writeText("a,b\n1,2\n")
        }
        val base = stage(input, File("/unused"))
        val baseline = cliStageRequestFingerprint(base, LlmBackend.OPENROUTER)

        assertEquals(baseline, cliStageRequestFingerprint(base, LlmBackend.OPENROUTER))
        // Output location is not part of the request.
        assertEquals(baseline, cliStageRequestFingerprint(base.copy(outputDir = File("/elsewhere").toPath()), LlmBackend.OPENROUTER))

        val variants = listOf(
            base.copy(prompt = "$prompt, now with a FAQ"),
            base.copy(rounds = 2),
            base.copy(groupCount = 3),
            base.copy(maxAgentsPerGroup = 2),
            base.copy(budgetOverride = 300.0),
            base.copy(modelOverrides = mapOf("Builder" to "other-model")),
            base.copy(finalOutputSpec = htmlSpec.copy(relativePath = "report.html")),
            base.copy(finalOutputSpec = null),
            base.copy(codexReasoningEffort = CodexReasoningEffort.MEDIUM),
            base.copy(enforceProviderMonetaryCostCap = false),
            base.copy(ordinaryAgentTtlSeconds = 900),
            base.copy(graderGroupCount = 1)
        )
        variants.forEach { variant ->
            assertNotEquals(baseline, cliStageRequestFingerprint(variant, LlmBackend.OPENROUTER), variant.toString())
        }
        assertNotEquals(baseline, cliStageRequestFingerprint(base, LlmBackend.QWEN_CLOUD))

        input.resolve("nested/data.csv").writeText("a,b\n1,3\n")
        assertNotEquals(baseline, cliStageRequestFingerprint(base, LlmBackend.OPENROUTER))
        input.resolve("nested/data.csv").writeText("a,b\n1,2\n")
        assertEquals(baseline, cliStageRequestFingerprint(base, LlmBackend.OPENROUTER))
        input.resolve("added.txt").writeText("new input")
        assertNotEquals(baseline, cliStageRequestFingerprint(base, LlmBackend.OPENROUTER))
    }

    @Test
    fun `matching fingerprint reuses the completed stage without a runtime`(): Unit = runBlocking {
        val fixture = completedFixture()
        writeSummary(fixture, requestFingerprint = cliStageRequestFingerprint(fixture.stage, LlmBackend.OPENROUTER))

        val result = runCli(
            options = fixture.options,
            runtimeFactory = { _, _ -> error("runtime must not be created for a reusable completed stage") }
        )

        assertTrue(result.completedSuccessfully)
        assertTrue(fixture.runOutputDir.resolve("outputs/final-article.html").readText().contains("reused article"))
    }

    @Test
    fun `changed input refuses to return the stale completed result`() {
        val fixture = completedFixture()
        writeSummary(fixture, requestFingerprint = cliStageRequestFingerprint(fixture.stage, LlmBackend.OPENROUTER))
        fixture.inputDir.resolve("brief.txt").writeText("a different brief")

        val error = assertFailsWith<CliUsageException> {
            runCli(
                options = fixture.options,
                runtimeFactory = { _, _ -> error("runtime must not be created for a refused reuse") }
            )
        }

        assertTrue(error.message.orEmpty().contains("different request"), error.message)
        assertTrue(!fixture.runOutputDir.resolve("outputs").exists())
    }

    @Test
    fun `legacy summary without fingerprint is refused when its prompt differs`() {
        val fixture = completedFixture()
        writeSummary(fixture, requestFingerprint = null, prompt = "An unrelated earlier objective")

        val error = assertFailsWith<CliUsageException> {
            runCli(
                options = fixture.options,
                runtimeFactory = { _, _ -> error("runtime must not be created for a refused reuse") }
            )
        }

        assertTrue(error.message.orEmpty().contains("prompt differs"), error.message)
    }

    private data class Fixture(
        val inputDir: File,
        val runOutputDir: File,
        val options: CliOptions,
        val stage: CliStagePlan
    )

    private fun completedFixture(): Fixture {
        val inputDir = createTempDirectory("fingerprint-reuse-input-").toFile().apply {
            resolve("brief.txt").writeText("mindvortex brief")
        }
        val outputDir = createTempDirectory("fingerprint-reuse-output-").toFile()
        val runId = "fingerprint-reuse"
        val runOutputDir = outputDir.resolve(runId).apply { mkdirs() }
        runOutputDir.resolve("results.zip").writeBytes(
            zipBytes(
                mapOf(
                    "outputs/final-article.html" to "<!doctype html><html><body>reused article</body></html>",
                    "manifest.json" to """{"artifactCount":1}"""
                )
            )
        )
        val options = CliOptions(
            prompt = prompt,
            inputDir = inputDir.toPath(),
            outputDir = outputDir.toPath(),
            openRouterApiKey = "sk-test",
            modelOverrides = emptyMap(),
            debug = false,
            fast = false,
            rounds = 1,
            runId = runId
        )
        return Fixture(inputDir, runOutputDir, options, stage(inputDir, runOutputDir).copy(runId = runId))
    }

    /** Mirrors the primary stage plan `runCliPipeline` builds for [completedFixture] options. */
    private fun stage(inputDir: File, runOutputDir: File) = CliStagePlan(
        id = "primary",
        prompt = prompt,
        inputDir = inputDir.toPath(),
        outputDir = runOutputDir.toPath(),
        runId = "fingerprint-reuse",
        fast = false,
        rounds = 1,
        groupCount = null,
        maxAgentsPerGroup = null,
        roundDurationMinutes = null,
        budgetOverride = null,
        inheritsPreviousOutputs = false,
        modelOverrides = emptyMap(),
        finalOutputSpec = htmlSpec,
        finalOutputExplicit = false,
        enforceProviderMonetaryCostCap = true
    )

    private fun writeSummary(fixture: Fixture, requestFingerprint: String?, prompt: String = this.prompt) {
        val summary = CliRunSummary(
            runId = fixture.options.runId!!,
            prompt = prompt,
            inputDirectory = fixture.inputDir.absolutePath,
            inputArtifactCount = 1,
            outputDirectory = fixture.runOutputDir.absolutePath,
            completedAt = "2026-10-01T00:00:00Z",
            durationMs = 42,
            debugLogPath = null,
            resultsBundlePath = fixture.runOutputDir.resolve("results.zip").absolutePath,
            status = "COMPLETED",
            winnerId = "group-0",
            modelOverrides = emptyMap(),
            requestFingerprint = requestFingerprint
        )
        fixture.runOutputDir.resolve("summary.json")
            .writeText(cliJson.encodeToString(CliRunSummary.serializer(), summary))
    }

    private fun zipBytes(entries: Map<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (path, content) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
