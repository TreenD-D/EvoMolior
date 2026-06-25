package com.achulkov.evokammer.cli

import com.achulkov.evokammer.api.events.EvoKammerEvent
import com.achulkov.evokammer.api.model.FinalOutputKind
import com.achulkov.evokammer.api.model.RunConfig
import com.achulkov.evokammer.api.service.EngineState
import com.achulkov.evokammer.api.service.EngineStatus
import com.achulkov.evokammer.api.service.EvoKammerEngineService
import com.achulkov.evokammer.api.service.GroupSummary
import com.achulkov.evokammer.api.service.MetaArchiveEntryRecord
import com.achulkov.evokammer.api.service.MetaArchiveSummary
import com.achulkov.evokammer.core.contract.OverseerDeadEnd
import com.achulkov.evokammer.core.contract.OverseerDeadEndHistoryEntry
import com.achulkov.evokammer.core.contract.OverseerExperimentPlan
import com.achulkov.evokammer.core.contract.OverseerExperimentPlanEligibility
import com.achulkov.evokammer.core.contract.OverseerExperimentPlanHistoryEntry
import com.achulkov.evokammer.core.contract.OverseerExperimentResult
import com.achulkov.evokammer.core.contract.OverseerHypothesis
import com.achulkov.evokammer.core.contract.OverseerKernelAuditEntry
import com.achulkov.evokammer.core.contract.OverseerKernelEligibility
import com.achulkov.evokammer.core.contract.OverseerKernelState
import com.achulkov.evokammer.api.service.PersistedRunStateStore
import com.achulkov.evokammer.core.config.FastModelSelectionConfig
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
                "RESEARCHER=deepseek/deepseek-v4-flash",
                "-model",
                "BUILDER=z-ai/glm-4.7-flash"
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
        assertEquals("deepseek/deepseek-v4-flash", options.modelOverrides["RESEARCHER"])
        assertEquals("z-ai/glm-4.7-flash", options.modelOverrides["BUILDER"])
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
    fun `parses pipeline config path`() {
        val inputDir = createTempDirectory("cli-input-").toFile()
        val outputDir = createTempDirectory("cli-output-").toFile()
        val pipelineFile = createTempDirectory("cli-pipeline-").toFile().resolve("pipeline.json").apply {
            writeText("""{"stages":[]}""")
        }

        val options = CliArguments.parse(
            arrayOf(
                "-prompt",
                "Pipeline parsing objective",
                "-input",
                inputDir.absolutePath,
                "-output",
                outputDir.absolutePath,
                "-openrouter-api-key",
                "sk-test",
                "-pipeline-config",
                pipelineFile.absolutePath
            )
        )

        assertNotNull(options)
        assertEquals(
            pipelineFile.toPath().toAbsolutePath().normalize(),
            options.pipelineConfigPath
        )
    }

    @Test
    fun `parses overseer execution mode flag explicitly`() {
        val sourceDir = createTempDirectory("self-improve-source-").toFile()
        val workspaceRoot = createTempDirectory("self-improve-workspace-").toFile()

        val options = SelfImprovementCliArguments.parse(
            arrayOf(
                "-source", sourceDir.absolutePath,
                "-workspace-root", workspaceRoot.absolutePath,
                "-workspace-name", "cycle-001",
                "-overseer-cycle",
                "-overseer-execute",
                "-overseer-run-all-cycles",
                "-env", "OPENROUTER_API_KEY=sk-test"
            )
        )

        assertNotNull(options)
        assertTrue(options.overseerCycle)
        assertTrue(options.overseerExecuteExperiments)
        assertTrue(options.overseerRunAllCycles)
        assertEquals(3, options.overseerCycles)
    }

    @Test
    fun `parses run shape overrides`() {
        val inputDir = createTempDirectory("cli-shape-input-").toFile()
        val outputDir = createTempDirectory("cli-shape-output-").toFile()

        val options = CliArguments.parse(
            arrayOf(
                "-prompt",
                "Shape override objective",
                "-input",
                inputDir.absolutePath,
                "-output",
                outputDir.absolutePath,
                "-openrouter-api-key",
                "sk-test",
                "-group-count",
                "6",
                "-max-agents-per-group",
                "5",
                "-budget-override",
                "480"
            )
        )

        assertNotNull(options)
        assertEquals(6, options.groupCount)
        assertEquals(5, options.maxAgentsPerGroup)
        assertEquals(480.0, options.budgetOverride)
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
    fun `fast browser run adds default follow up stage and aggregates published outputs`() = runBlocking {
        val inputDir = createTempDirectory("cli-pipeline-default-input-").toFile().apply {
            resolve("brief.txt").writeText("mindvortex brief")
        }
        val outputDir = createTempDirectory("cli-pipeline-default-output-").toFile()
        val fakeEngine = FakeCliPipelineEngineService()

        val result = runCli(
            options = CliOptions(
                prompt = "Create a long-form HTML5 article for mindvortex.app with embedded styles",
                inputDir = inputDir.toPath(),
                outputDir = outputDir.toPath(),
                openRouterApiKey = "sk-test",
                modelOverrides = emptyMap(),
                debug = true,
                fast = true,
                rounds = 5,
                runId = "cli-pipeline-default"
            ),
            runtimeFactory = { _, _ -> CliRuntime(fakeEngine) }
        )

        assertTrue(result.completedSuccessfully)
        assertEquals(
            listOf("cli-pipeline-default", "cli-pipeline-default-fast-follow-up"),
            fakeEngine.capturedConfigs.map { it.runId }
        )

        val primaryConfig = assertNotNull(fakeEngine.capturedConfigs.firstOrNull())
        val followUpConfig = assertNotNull(fakeEngine.capturedConfigs.getOrNull(1))
        assertEquals("final-article.html", primaryConfig.finalOutputSpec?.relativePath)
        assertEquals("refined/final-article-refined.html", followUpConfig.finalOutputSpec?.relativePath)
        assertTrue(followUpConfig.objective.contains("do not compress, summarize, or shorten"))
        assertTrue(followUpConfig.objective.contains("source-grounded evidence notes"))

        val followUpArtifacts = followUpConfig.contextArtifacts.map { it.relativePath }.toSet()
        assertTrue("previous-stage/results.zip" in followUpArtifacts)
        assertTrue("previous-stage/results-bundle/outputs/final-article.html" in followUpArtifacts)

        val runOutputDir = outputDir.resolve("cli-pipeline-default")
        assertTrue(runOutputDir.resolve("pipeline-summary.json").exists())
        assertTrue(runOutputDir.resolve("pipeline-results.zip").exists())
        assertTrue(runOutputDir.resolve("outputs/final-article.html").exists())
        assertTrue(runOutputDir.resolve("outputs/refined/final-article-refined.html").exists())
        assertTrue(runOutputDir.resolve("outputs/final-article.html").readText().contains("cli-pipeline-default"))
        assertTrue(runOutputDir.resolve("outputs/refined/final-article-refined.html").readText().contains("cli-pipeline-default-fast-follow-up"))

        val pipelineSummary = runOutputDir.resolve("pipeline-summary.json").readText()
        assertTrue(pipelineSummary.contains("\"publishedOutputs\": ["))
        assertTrue(pipelineSummary.contains("\"final-article.html\""))
        assertTrue(pipelineSummary.contains("\"refined/final-article-refined.html\""))

        val pipelineEntries = unzipEntries(runOutputDir.resolve("pipeline-results.zip").readBytes())
        assertTrue(pipelineEntries.keys.contains("outputs/final-article.html"))
        assertTrue(pipelineEntries.keys.contains("outputs/refined/final-article-refined.html"))
        assertTrue(pipelineEntries.keys.contains("pipeline-summary.json"))
    }

    @Test
    fun `default follow up stage inherits explicit run shape overrides`() = runBlocking {
        val inputDir = createTempDirectory("cli-pipeline-default-shape-input-").toFile().apply {
            resolve("brief.txt").writeText("mindvortex brief")
        }
        val outputDir = createTempDirectory("cli-pipeline-default-shape-output-").toFile()
        val fakeEngine = FakeCliPipelineEngineService()

        val result = runCli(
            options = CliOptions(
                prompt = "Create a long-form HTML5 article for mindvortex.app with embedded styles",
                inputDir = inputDir.toPath(),
                outputDir = outputDir.toPath(),
                openRouterApiKey = "sk-test",
                modelOverrides = emptyMap(),
                debug = true,
                fast = true,
                rounds = 20,
                groupCount = 6,
                maxAgentsPerGroup = 4,
                budgetOverride = 1800.0,
                runId = "cli-pipeline-default-shape"
            ),
            runtimeFactory = { _, _ -> CliRuntime(fakeEngine) }
        )

        assertTrue(result.completedSuccessfully)
        assertEquals(
            listOf("cli-pipeline-default-shape", "cli-pipeline-default-shape-fast-follow-up"),
            fakeEngine.capturedConfigs.map { it.runId }
        )

        val primaryConfig = assertNotNull(fakeEngine.capturedConfigs.firstOrNull())
        val followUpConfig = assertNotNull(fakeEngine.capturedConfigs.getOrNull(1))
        assertEquals(6, primaryConfig.groupCount)
        assertEquals(4, primaryConfig.maxAgentsPerGroup)
        assertEquals(1800.0, primaryConfig.budgetOverride)
        assertEquals(20, primaryConfig.rounds)
        assertEquals(6, followUpConfig.groupCount)
        assertEquals(4, followUpConfig.maxAgentsPerGroup)
        assertEquals(1800.0, followUpConfig.budgetOverride)
        assertEquals(1, followUpConfig.rounds)
    }

    @Test
    fun `pipeline config resolves stage prompt file relative to config and publishes json output`() = runBlocking {
        val inputDir = createTempDirectory("cli-pipeline-config-input-").toFile().apply {
            resolve("brief.txt").writeText("mindvortex brief")
        }
        val outputDir = createTempDirectory("cli-pipeline-config-output-").toFile()
        val pipelineDir = createTempDirectory("cli-pipeline-config-").toFile()
        val promptFile = pipelineDir.resolve("prompts/brief-json.txt").apply {
            parentFile.mkdirs()
            writeText("Summarize the previous stage into a JSON brief.")
        }
        val pipelineFile = pipelineDir.resolve("pipeline.json").apply {
            writeText(
                """
                {
                  "stages": [
                    {
                      "id": "brief-json",
                      "promptFile": "prompts/brief-json.txt",
                      "fast": true,
                      "rounds": 1,
                      "finalOutputKind": "PATCH_BUNDLE",
                      "finalOutput": {
                        "relativePath": "briefs/summary.json",
                        "mediaType": "application/json",
                        "description": "Localized summary brief"
                      }
                    }
                  ]
                }
                """.trimIndent()
            )
        }
        val fakeEngine = FakeCliPipelineEngineService()

        val result = runCli(
            options = CliOptions(
                prompt = "Create a long-form HTML5 article for mindvortex.app with embedded styles",
                inputDir = inputDir.toPath(),
                outputDir = outputDir.toPath(),
                openRouterApiKey = "sk-test",
                modelOverrides = emptyMap(),
                debug = true,
                fast = true,
                rounds = 5,
                runId = "cli-pipeline-config",
                pipelineConfigPath = pipelineFile.toPath()
            ),
            runtimeFactory = { _, _ -> CliRuntime(fakeEngine) }
        )

        assertTrue(result.completedSuccessfully)
        assertEquals(
            listOf("cli-pipeline-config", "cli-pipeline-config-brief-json"),
            fakeEngine.capturedConfigs.map { it.runId }
        )

        val configuredStage = assertNotNull(fakeEngine.capturedConfigs.getOrNull(1))
        assertEquals(promptFile.readText(), configuredStage.objective)
        assertEquals("briefs/summary.json", configuredStage.finalOutputSpec?.relativePath)
        assertEquals("application/json", configuredStage.finalOutputSpec?.mediaType)
        assertEquals(FinalOutputKind.PATCH_BUNDLE, configuredStage.finalOutputSpec?.kind)

        val configuredStageArtifacts = configuredStage.contextArtifacts.map { it.relativePath }.toSet()
        assertTrue("previous-stage/results-bundle/outputs/final-article.html" in configuredStageArtifacts)

        val runOutputDir = outputDir.resolve("cli-pipeline-config")
        val jsonOutput = runOutputDir.resolve("outputs/briefs/summary.json")
        assertTrue(jsonOutput.exists())
        assertTrue(jsonOutput.readText().contains("\"runId\":\"cli-pipeline-config-brief-json\""))
        assertTrue(runOutputDir.resolve("pipeline-summary.json").readText().contains("\"briefs/summary.json\""))
    }

    @Test
    fun `pipeline config fails when the declared json output never materializes`() = runBlocking {
        val inputDir = createTempDirectory("cli-pipeline-missing-input-").toFile().apply {
            resolve("brief.txt").writeText("mindvortex brief")
        }
        val outputDir = createTempDirectory("cli-pipeline-missing-output-").toFile()
        val pipelineDir = createTempDirectory("cli-pipeline-missing-").toFile()
        val pipelineFile = pipelineDir.resolve("pipeline.json").apply {
            writeText(
                """
                {
                  "stages": [
                    {
                      "id": "brief-json",
                      "prompt": "Summarize the previous stage into a JSON brief.",
                      "fast": true,
                      "rounds": 1,
                      "finalOutput": {
                        "relativePath": "briefs/summary.json",
                        "mediaType": "application/json",
                        "description": "Localized summary brief"
                      }
                    }
                  ]
                }
                """.trimIndent()
            )
        }
        val fakeEngine = FakeCliBrokenOutputEngineService(emptyMap())

        assertFailsWith<IllegalStateException> {
            runCli(
                options = CliOptions(
                    prompt = "Create a long-form HTML5 article for mindvortex.app with embedded styles",
                    inputDir = inputDir.toPath(),
                    outputDir = outputDir.toPath(),
                    openRouterApiKey = "sk-test",
                    modelOverrides = emptyMap(),
                    debug = true,
                    fast = true,
                    rounds = 5,
                    runId = "cli-pipeline-missing",
                    pipelineConfigPath = pipelineFile.toPath()
                ),
                runtimeFactory = { _, _ -> CliRuntime(fakeEngine) }
            )
        }
    }

    @Test
    fun `pipeline config fails when the declared json output has the wrong media type content`() = runBlocking {
        val inputDir = createTempDirectory("cli-pipeline-wrong-type-input-").toFile().apply {
            resolve("brief.txt").writeText("mindvortex brief")
        }
        val outputDir = createTempDirectory("cli-pipeline-wrong-type-output-").toFile()
        val pipelineDir = createTempDirectory("cli-pipeline-wrong-type-").toFile()
        val pipelineFile = pipelineDir.resolve("pipeline.json").apply {
            writeText(
                """
                {
                  "stages": [
                    {
                      "id": "brief-json",
                      "prompt": "Summarize the previous stage into a JSON brief.",
                      "fast": true,
                      "rounds": 1,
                      "finalOutput": {
                        "relativePath": "briefs/summary.json",
                        "mediaType": "application/json",
                        "description": "Localized summary brief"
                      }
                    }
                  ]
                }
                """.trimIndent()
            )
        }
        val fakeEngine = FakeCliBrokenOutputEngineService(
            mapOf("outputs/briefs/summary.json" to "<!doctype html><html><body>wrong type</body></html>")
        )

        assertFailsWith<IllegalStateException> {
            runCli(
                options = CliOptions(
                    prompt = "Create a long-form HTML5 article for mindvortex.app with embedded styles",
                    inputDir = inputDir.toPath(),
                    outputDir = outputDir.toPath(),
                    openRouterApiKey = "sk-test",
                    modelOverrides = emptyMap(),
                    debug = true,
                    fast = true,
                    rounds = 5,
                    runId = "cli-pipeline-wrong-type",
                    pipelineConfigPath = pipelineFile.toPath()
                ),
                runtimeFactory = { _, _ -> CliRuntime(fakeEngine) }
            )
        }
    }

    @Test
    fun `pipeline run shape overrides propagate to primary and configured follow up stages`() = runBlocking {
        val inputDir = createTempDirectory("cli-pipeline-shape-input-").toFile().apply {
            resolve("brief.txt").writeText("mindvortex brief")
        }
        val outputDir = createTempDirectory("cli-pipeline-shape-output-").toFile()
        val pipelineFile = createTempDirectory("cli-pipeline-shape-").toFile().resolve("pipeline.json").apply {
            writeText(
                """
                {
                  "stages": [
                    {
                      "id": "comparison-follow-up",
                      "prompt": "Create a JSON follow-up brief.",
                      "fast": true,
                      "rounds": 10,
                      "groupCount": 6,
                      "maxAgentsPerGroup": 5,
                      "budgetOverride": 480,
                      "finalOutput": {
                        "relativePath": "briefs/summary.json",
                        "mediaType": "application/json",
                        "description": "Comparison summary brief"
                      }
                    }
                  ]
                }
                """.trimIndent()
            )
        }
        val fakeEngine = FakeCliPipelineEngineService()

        val result = runCli(
            options = CliOptions(
                prompt = "Create a long-form HTML5 article for mindvortex.app with embedded styles",
                inputDir = inputDir.toPath(),
                outputDir = outputDir.toPath(),
                openRouterApiKey = "sk-test",
                modelOverrides = emptyMap(),
                debug = true,
                fast = true,
                rounds = 20,
                groupCount = 6,
                maxAgentsPerGroup = 5,
                budgetOverride = 480.0,
                runId = "cli-pipeline-shape",
                pipelineConfigPath = pipelineFile.toPath()
            ),
            runtimeFactory = { _, _ -> CliRuntime(fakeEngine) }
        )

        assertTrue(result.completedSuccessfully)

        val primaryConfig = assertNotNull(fakeEngine.capturedConfigs.firstOrNull())
        val followUpConfig = assertNotNull(fakeEngine.capturedConfigs.getOrNull(1))
        assertEquals(6, primaryConfig.groupCount)
        assertEquals(5, primaryConfig.maxAgentsPerGroup)
        assertEquals(480.0, primaryConfig.budgetOverride)
        assertEquals(20, primaryConfig.rounds)
        assertEquals(6, followUpConfig.groupCount)
        assertEquals(5, followUpConfig.maxAgentsPerGroup)
        assertEquals(480.0, followUpConfig.budgetOverride)
        assertEquals(10, followUpConfig.rounds)
    }

    @Test
    fun `pipeline stage can opt out of inheriting previous outputs`() = runBlocking {
        val inputDir = createTempDirectory("cli-pipeline-no-inherit-input-").toFile().apply {
            resolve("brief.txt").writeText("mindvortex brief")
        }
        val outputDir = createTempDirectory("cli-pipeline-no-inherit-output-").toFile()
        val pipelineFile = createTempDirectory("cli-pipeline-no-inherit-").toFile().resolve("pipeline.json").apply {
            writeText(
                """
                {
                  "stages": [
                    {
                      "id": "brief-json",
                      "prompt": "Create a JSON brief without reading previous-stage outputs.",
                      "fast": true,
                      "rounds": 1,
                      "inheritsPreviousOutputs": false,
                      "finalOutput": {
                        "relativePath": "briefs/summary.json",
                        "mediaType": "application/json",
                        "description": "Standalone summary brief"
                      }
                    }
                  ]
                }
                """.trimIndent()
            )
        }
        val fakeEngine = FakeCliPipelineEngineService()

        val result = runCli(
            options = CliOptions(
                prompt = "Create a long-form HTML5 article for mindvortex.app with embedded styles",
                inputDir = inputDir.toPath(),
                outputDir = outputDir.toPath(),
                openRouterApiKey = "sk-test",
                modelOverrides = emptyMap(),
                debug = true,
                fast = true,
                rounds = 5,
                runId = "cli-pipeline-no-inherit",
                pipelineConfigPath = pipelineFile.toPath()
            ),
            runtimeFactory = { _, _ -> CliRuntime(fakeEngine) }
        )

        assertTrue(result.completedSuccessfully)

        val configuredStage = assertNotNull(fakeEngine.capturedConfigs.getOrNull(1))
        val configuredStageArtifacts = configuredStage.contextArtifacts.map { it.relativePath }.toSet()
        assertFalse(configuredStageArtifacts.any { it.startsWith("previous-stage/") })
        assertTrue(configuredStageArtifacts.contains("brief.txt"))
    }

    @Test
    fun `aborted follow up stage reruns after stale output directory is cleaned`() = runBlocking {
        val inputDir = createTempDirectory("cli-pipeline-abort-input-").toFile().apply {
            resolve("brief.txt").writeText("mindvortex brief")
        }
        val outputDir = createTempDirectory("cli-pipeline-abort-output-").toFile()
        val runRoot = outputDir.resolve("cli-pipeline-abort")
        val followUpDir = runRoot.resolve("stages").resolve("fast-follow-up")
        followUpDir.mkdirs()
        followUpDir.resolve("summary.json").writeText("""{"status":"ABORTED"}""")
        val staleMarker = followUpDir.resolve("stale-marker.txt").apply {
            writeText("stale")
        }

        val fakeEngine = FakeCliPipelineEngineService()

        val result = runCli(
            options = CliOptions(
                prompt = "Create a long-form HTML5 article for mindvortex.app with embedded styles",
                inputDir = inputDir.toPath(),
                outputDir = outputDir.toPath(),
                openRouterApiKey = "sk-test",
                modelOverrides = emptyMap(),
                debug = true,
                fast = true,
                rounds = 5,
                runId = "cli-pipeline-abort"
            ),
            runtimeFactory = { _, _ -> CliRuntime(fakeEngine) }
        )

        assertTrue(result.completedSuccessfully)
        assertTrue(!staleMarker.exists(), "Aborted follow-up stage output should be cleaned before rerun")
        assertTrue(followUpDir.resolve("summary.json").exists())
        assertTrue(followUpDir.resolve("results.zip").exists())
        assertTrue(followUpDir.resolve("summary.json").readText().contains("\"status\": \"COMPLETED\""))
        assertEquals(
            listOf("cli-pipeline-abort", "cli-pipeline-abort-fast-follow-up"),
            fakeEngine.capturedConfigs.map { it.runId }
        )
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
    fun `fast mode parser accepts updated builder default and rejects failed qwen worker model`() {
        val inputDir = createTempDirectory("cli-input-").toFile()
        val outputDir = createTempDirectory("cli-output-").toFile()

        val accepted = CliArguments.parse(
            arrayOf(
                "-prompt",
                "Fast mode model test",
                "-input",
                inputDir.absolutePath,
                "-output",
                outputDir.absolutePath,
                "-openrouter-api-key",
                "sk-test",
                "-fast",
                "-model",
                "Builder=${FastModelSelectionConfig.FAST_RUNTIME_BUILDER_MODEL}"
            )
        )

        assertNotNull(accepted)
        assertEquals("openai/gpt-oss-120b", accepted.modelOverrides["Builder"])

        val error = assertFailsWith<CliUsageException> {
            CliArguments.parse(
                arrayOf(
                    "-prompt",
                    "Fast mode model test",
                    "-input",
                    inputDir.absolutePath,
                    "-output",
                    outputDir.absolutePath,
                    "-openrouter-api-key",
                    "sk-test",
                    "-fast",
                    "-model",
                    "Researcher=qwen/qwen3.5-flash-02-23"
                )
            )
        }
        assertTrue(error.message.orEmpty().contains("Fast mode only allows"))
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

    @Test
    fun `runCli resumes a persisted run when prior state exists but summary is missing`() = runBlocking {
        val inputDir = createTempDirectory("cli-resume-input-").toFile()
        val outputDir = createTempDirectory("cli-resume-output-").toFile()
        val fakeEngine = FakeCliResumeEngineService()
        val runOutputDir = outputDir.resolve("cli-resume")
        val dataRoot = runOutputDir.resolve(".evokammer-data").also { it.mkdirs() }
        PersistedRunStateStore.resolveStateFile(dataRoot, "cli-resume").apply {
            parentFile.mkdirs()
            writeText("{}")
        }

        val result = runCli(
            options = CliOptions(
                prompt = "Resume the old run",
                inputDir = inputDir.toPath(),
                outputDir = outputDir.toPath(),
                openRouterApiKey = "sk-test",
                modelOverrides = emptyMap(),
                debug = true,
                fast = false,
                rounds = 1,
                runId = "cli-resume"
            ),
            runtimeFactory = { _, _ -> CliRuntime(fakeEngine) }
        )

        assertTrue(result.completedSuccessfully)
        assertTrue(fakeEngine.resumeCalled)
        assertFalse(fakeEngine.startCalled)
        assertTrue(runOutputDir.resolve("summary.json").exists())
    }

    @Test
    fun `runCli reuses a completed stage that already published the requested final output`() = runBlocking {
        val inputDir = createTempDirectory("cli-reuse-input-").toFile().apply {
            resolve("brief.txt").writeText("mindvortex brief")
        }
        val outputDir = createTempDirectory("cli-reuse-output-").toFile()
        val runOutputDir = outputDir.resolve("cli-reuse")
        runOutputDir.mkdirs()

        runOutputDir.resolve("summary.json").writeText(
            """
            {
              "runId": "cli-reuse",
              "prompt": "Create a long-form HTML5 article for mindvortex.app with embedded styles",
              "inputDirectory": "${inputDir.toPath()}",
              "inputArtifactCount": 1,
              "outputDirectory": "${runOutputDir.absolutePath}",
              "completedAt": "2026-06-11T15:00:00Z",
              "durationMs": 42,
              "debugLogPath": null,
              "resultsBundlePath": "${runOutputDir.resolve("results.zip").absolutePath}",
              "status": "COMPLETED",
              "winnerId": "group-0",
              "modelOverrides": {}
            }
            """.trimIndent()
        )
        runOutputDir.resolve("results.zip").writeBytes(
            buildZipBytes(
                mapOf(
                    "outputs/final-article.html" to "<!doctype html><html><body>MindVortex reused article</body></html>",
                    "manifest.json" to """{"artifactCount":1}"""
                )
            )
        )

        val result = runCli(
            options = CliOptions(
                prompt = "Create a long-form HTML5 article for mindvortex.app with embedded styles",
                inputDir = inputDir.toPath(),
                outputDir = outputDir.toPath(),
                openRouterApiKey = "sk-test",
                modelOverrides = emptyMap(),
                debug = true,
                fast = false,
                rounds = 1,
                runId = "cli-reuse"
            ),
            runtimeFactory = { _, _ -> error("runtime should not be created for a reusable completed stage") }
        )

        assertTrue(result.completedSuccessfully)
        assertTrue(runOutputDir.resolve("outputs/final-article.html").exists())
        assertTrue(runOutputDir.resolve("outputs/final-article.html").readText().contains("MindVortex reused article"))
    }
}

private abstract class NoopOverseerEngineService : EvoKammerEngineService {
    override suspend fun getOverseerKernelState(): OverseerKernelState = OverseerKernelState()
    override suspend fun preflightOverseerHypothesis(hypothesis: OverseerHypothesis): OverseerKernelEligibility =
        OverseerKernelEligibility(allowed = true)
    override suspend fun preflightOverseerExperimentPlan(experimentPlan: OverseerExperimentPlan): OverseerExperimentPlanEligibility =
        OverseerExperimentPlanEligibility(allowed = true)
    override suspend fun formalizeOverseerHypothesis(hypothesis: OverseerHypothesis): OverseerKernelState = OverseerKernelState()
    override suspend fun registerOverseerExperimentPlan(experimentPlan: OverseerExperimentPlan): OverseerKernelState = OverseerKernelState()
    override suspend fun recordOverseerExperimentResult(experimentResult: OverseerExperimentResult): OverseerKernelState = OverseerKernelState()
    override suspend fun recordOverseerDeadEnd(deadEnd: OverseerDeadEnd): OverseerKernelState = OverseerKernelState()
    override suspend fun getOverseerAuditEntries(): List<OverseerKernelAuditEntry> = emptyList()
    override suspend fun getOverseerExperimentPlanHistory(experimentPlanId: String): List<OverseerExperimentPlanHistoryEntry> = emptyList()
    override suspend fun getOverseerDeadEndHistory(hypothesisId: String): List<OverseerDeadEndHistoryEntry> = emptyList()
    override suspend fun getOverseerAuditArchiveEntries(): List<MetaArchiveEntryRecord> = emptyList()
    override suspend fun scheduleOverseerExperimentPlans(): OverseerKernelState = OverseerKernelState()
}

private class FakeCliEngineService : NoopOverseerEngineService() {
    private val _events = MutableSharedFlow<EvoKammerEvent>(replay = 2, extraBufferCapacity = 8)
    override val events: SharedFlow<EvoKammerEvent> = _events.asSharedFlow()

    var capturedConfig: RunConfig? = null
    private var runCompleted = false

    override suspend fun startRun(config: RunConfig): String {
        capturedConfig = config
        _events.emit(EvoKammerEvent.RunStarted(config.runId, config.objective))
        runCompleted = true
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
        check(runCompleted) { "results bundle requested before RunCompleted was emitted" }
        val config = assertNotNull(capturedConfig)
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            fun addEntry(path: String, content: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }

            config.finalOutputSpec?.let { spec ->
                val content = if (spec.mediaType.startsWith("text/html", ignoreCase = true)) {
                    "<!doctype html><html><body>${config.runId}</body></html>"
                } else {
                    """{"runId":"${config.runId}","mediaType":"${spec.mediaType}"}"""
                }
                addEntry("outputs/${spec.relativePath}", content)
            }
            addEntry("manifest.json", """{"runId":"${config.runId}","artifactCount":${config.contextArtifacts.size}}""")
        }
        return output.toByteArray()
    }
}

private class FakeCliBundleEngineService : NoopOverseerEngineService() {
    private val _events = MutableSharedFlow<EvoKammerEvent>(replay = 2, extraBufferCapacity = 8)
    override val events: SharedFlow<EvoKammerEvent> = _events.asSharedFlow()

    private var runCompletedEmitted = false
    private var capturedConfig: RunConfig? = null
    var resultsBundleRequestedAfterCompletion = false

    override suspend fun startRun(config: RunConfig): String {
        capturedConfig = config
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

            val config = assertNotNull(capturedConfig)
            config.finalOutputSpec?.let { spec ->
                val content = if (spec.mediaType.startsWith("text/html", ignoreCase = true)) {
                    "<!doctype html><html><body>MindVortex fast-run article ${config.runId}</body></html>"
                } else {
                    """{"runId":"${config.runId}","mediaType":"${spec.mediaType}"}"""
                }
                addEntry("outputs/${spec.relativePath}", content)
            }
            addEntry("manifest.json", """{"runId":"${config.runId}","artifactCount":${config.contextArtifacts.size}}""")
        }
        return output.toByteArray()
    }
}

private class FakeCliPipelineEngineService : NoopOverseerEngineService() {
    private val _events = MutableSharedFlow<EvoKammerEvent>(replay = 2, extraBufferCapacity = 8)
    override val events: SharedFlow<EvoKammerEvent> = _events.asSharedFlow()

    val capturedConfigs = mutableListOf<RunConfig>()
    private val configsByRunId = linkedMapOf<String, RunConfig>()
    private val completedRuns = linkedSetOf<String>()

    override suspend fun startRun(config: RunConfig): String {
        capturedConfigs += config
        configsByRunId[config.runId] = config
        _events.emit(EvoKammerEvent.RunStarted(config.runId, config.objective))
        completedRuns += config.runId
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
        assertTrue(runId in completedRuns, "results bundle requested before run completion for $runId")
        val config = assertNotNull(configsByRunId[runId])
        return buildBundleBytes(config)
    }
    private fun buildBundleBytes(config: RunConfig): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            fun addEntry(path: String, content: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }

            config.finalOutputSpec?.let { spec ->
                val content = if (spec.mediaType.startsWith("text/html")) {
                    "<!doctype html><html><body>${config.runId}</body></html>"
                } else {
                    """{"runId":"${config.runId}","mediaType":"${spec.mediaType}"}"""
                }
                addEntry("outputs/${spec.relativePath}", content)
            }
            addEntry("manifest.json", """{"runId":"${config.runId}","artifactCount":${config.contextArtifacts.size}}""")
        }
        return output.toByteArray()
    }
}

private class FakeCliBrokenOutputEngineService(
    private val outputEntries: Map<String, String>
) : NoopOverseerEngineService() {
    private val _events = MutableSharedFlow<EvoKammerEvent>(replay = 2, extraBufferCapacity = 8)
    override val events: SharedFlow<EvoKammerEvent> = _events.asSharedFlow()

    private val completedRuns = linkedSetOf<String>()
    private val configsByRunId = linkedMapOf<String, RunConfig>()

    override suspend fun startRun(config: RunConfig): String {
        configsByRunId[config.runId] = config
        _events.emit(EvoKammerEvent.RunStarted(config.runId, config.objective))
        completedRuns += config.runId
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
        assertTrue(runId in completedRuns, "results bundle requested before run completion for $runId")
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            outputEntries.forEach { (path, content) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write("""{"runId":"$runId"}""".toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return output.toByteArray()
    }
}

private class FakeCliResumeEngineService : NoopOverseerEngineService() {
    private val _events = MutableSharedFlow<EvoKammerEvent>(replay = 2, extraBufferCapacity = 8)
    override val events: SharedFlow<EvoKammerEvent> = _events.asSharedFlow()

    var startCalled = false
    var resumeCalled = false

    override suspend fun startRun(config: RunConfig): String {
        startCalled = true
        _events.emit(EvoKammerEvent.RunStarted(config.runId, config.objective))
        _events.emit(EvoKammerEvent.RunCompleted(config.runId, "group-0"))
        return config.runId
    }

    override suspend fun resumePersistedRun(config: RunConfig): String {
        resumeCalled = true
        _events.emit(EvoKammerEvent.EngineProgress(config.runId, "recovery_start", "Recovering"))
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
    override suspend fun getResultsBundle(runId: String): ByteArray? = byteArrayOf(9, 9, 9)
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

private fun buildZipBytes(entries: Map<String, String>): ByteArray {
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
