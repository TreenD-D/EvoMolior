package com.achulkov.evomolior.cli

import com.achulkov.evomolior.api.events.EvoMoliorEvent
import com.achulkov.evomolior.api.model.FinalOutputKind
import com.achulkov.evomolior.api.model.RunConfig
import com.achulkov.evomolior.api.model.RunInputArtifactUpload
import com.achulkov.evomolior.api.model.RunAutoCompletionPolicy
import com.achulkov.evomolior.api.service.EngineState
import com.achulkov.evomolior.api.service.EngineStatus
import com.achulkov.evomolior.api.service.EvoMoliorEngineService
import com.achulkov.evomolior.api.service.GroupSummary
import com.achulkov.evomolior.api.service.MetaArchiveEntryRecord
import com.achulkov.evomolior.api.service.MetaArchiveSummary
import com.achulkov.evomolior.api.service.AcceptanceAdmissionArtifact
import com.achulkov.evomolior.api.service.AcceptanceAdmissionCheck
import com.achulkov.evomolior.api.service.AcceptanceRunBoundary
import com.achulkov.evomolior.core.contract.OverseerDeadEnd
import com.achulkov.evomolior.core.contract.OverseerDeadEndHistoryEntry
import com.achulkov.evomolior.core.contract.OverseerExperimentPlan
import com.achulkov.evomolior.core.contract.OverseerExperimentPlanEligibility
import com.achulkov.evomolior.core.contract.OverseerExperimentPlanHistoryEntry
import com.achulkov.evomolior.core.contract.OverseerExperimentResult
import com.achulkov.evomolior.core.contract.OverseerHypothesis
import com.achulkov.evomolior.core.contract.OverseerKernelAuditEntry
import com.achulkov.evomolior.core.contract.OverseerKernelEligibility
import com.achulkov.evomolior.core.contract.OverseerKernelState
import com.achulkov.evomolior.api.service.PersistedRunStateStore
import com.achulkov.evomolior.core.benchmark.BenchmarkAnswerFormat
import com.achulkov.evomolior.core.benchmark.Tier0BenchmarkKind
import com.achulkov.evomolior.core.benchmark.Tier0BenchmarkPrediction
import com.achulkov.evomolior.core.benchmark.Tier0BenchmarkPromptContract
import com.achulkov.evomolior.core.benchmark.Tier0BenchmarkTask
import com.achulkov.evomolior.core.benchmark.Tier0EvidenceStep
import com.achulkov.evomolior.core.config.AcceptanceProfile
import com.achulkov.evomolior.core.config.FastModelSelectionConfig
import com.achulkov.evomolior.core.config.CouncilExecutionPurpose
import com.achulkov.evomolior.core.config.LlmBackend
import com.achulkov.evomolior.core.config.ModelSelectionConfig
import com.achulkov.evomolior.core.contract.RunInputArtifactKind
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.ZipInputStream

class CliArgumentsTest {
    @Test
    fun `omitted rounds requires a maximum and enables grader stability completion`() {
        val input = createTempDirectory("cli-auto-complete-input")
        val output = createTempDirectory("cli-auto-complete-output")

        val options = CliArguments.parse(
            arrayOf(
                "-prompt", "Run until the winner is fully accepted",
                "-input", input.toString(),
                "-output", output.toString(),
                "-openrouter-api-key", "sk-test",
                "-max-rounds", "12",
                "-completion-stability-rounds", "2"
            )
        )!!

        assertEquals(null, options.rounds)
        assertEquals(12, options.maxRounds)
        assertEquals(2, options.completionStabilityRounds)
        assertFailsWith<CliUsageException> {
            CliArguments.parse(
                arrayOf(
                    "-prompt", "Missing safety ceiling",
                    "-input", input.toString(),
                    "-output", output.toString(),
                    "-openrouter-api-key", "sk-test"
                )
            )
        }
    }

    @Test
    fun `Appendix B execution purpose is explicit and Codex only`() {
        val input = createTempDirectory("cli-appendix-purpose-input")
        val output = createTempDirectory("cli-appendix-purpose-output")

        val options = CliArguments.parse(
            args = arrayOf(
                "-prompt", "Appendix B acceptance",
                "-input", input.toString(),
                "-output", output.toString(),
                "-llm-backend", "codex-chatgpt",
                "-rounds", "6",
                "-council-execution-purpose", "appendix-b-acceptance",
                "-acceptance-profile", "appendix-b-full"
            ),
            codexCliLocator = { "/tmp/codex" },
            codexSessionPreflight = {}
        )!!

        assertEquals(
            CouncilExecutionPurpose.APPENDIX_B_ACCEPTANCE,
            options.councilExecutionPurpose
        )
        assertEquals(AcceptanceProfile.APPENDIX_B_FULL, options.acceptanceProfile)
        assertEquals(
            RunAutoCompletionPolicy.DEFAULT_CONSECUTIVE_ACCEPTED_ROUNDS,
            options.completionStabilityRounds
        )
        assertFailsWith<CliUsageException> {
            CliArguments.parse(
                arrayOf(
                    "-prompt", "Invalid provider purpose",
                    "-input", input.toString(),
                    "-output", output.toString(),
                    "-openrouter-api-key", "sk-test",
                    "-rounds", "6",
                    "-council-execution-purpose", "appendix-b-acceptance"
                )
            )
        }
        assertFailsWith<CliUsageException> {
            CliArguments.parse(
                args = arrayOf(
                    "-prompt", "Wrong acceptance horizon",
                    "-input", input.toString(),
                    "-output", output.toString(),
                    "-llm-backend", "codex-chatgpt",
                    "-rounds", "5",
                    "-council-execution-purpose", "appendix-b-acceptance",
                    "-acceptance-profile", "appendix-b-full"
                ),
                codexCliLocator = { "/tmp/codex" },
                codexSessionPreflight = {}
            )
        }
        assertFailsWith<CliUsageException> {
            CliArguments.parse(
                args = arrayOf(
                    "-prompt", "Missing acceptance purpose",
                    "-input", input.toString(),
                    "-output", output.toString(),
                    "-llm-backend", "codex-chatgpt",
                    "-rounds", "1",
                    "-acceptance-profile", "runtime-usability-smoke"
                ),
                codexCliLocator = { "/tmp/codex" },
                codexSessionPreflight = {}
            )
        }
    }

    @Test
    fun `iso latency disables provider monetary cap without changing action budget`() {
        val input = createTempDirectory("cli-iso-latency-input")
        val output = createTempDirectory("cli-iso-latency-output")

        val options = CliArguments.parse(
            arrayOf(
                "-prompt", "Run an iso-latency acceptance scenario",
                "-input", input.toString(),
                "-output", output.toString(),
                "-llm-backend", "codex-chatgpt",
                "-budget-override", "1200",
                "-rounds", "1",
                "-iso-latency"
            ),
            codexCliLocator = { "/tmp/codex" },
            codexSessionPreflight = {}
        )!!

        assertEquals(1200.0, options.budgetOverride)
        assertFalse(options.enforceProviderMonetaryCostCap)
    }

    @Test
    fun `pipeline output enumeration excludes operating system metadata`() {
        assertFalse(isPublishablePipelineOutput(".DS_Store"))
        assertFalse(isPublishablePipelineOutput("nested/.DS_Store"))
        assertFalse(isPublishablePipelineOutput("__MACOSX/market-map.html"))
        assertTrue(isPublishablePipelineOutput("company/product-database.csv"))
    }


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
    fun `parses codex chatgpt backend without api key`() {
        val inputDir = createTempDirectory("cli-codex-input-").toFile()
        val outputDir = createTempDirectory("cli-codex-output-").toFile()
        val preflightPaths = mutableListOf<String>()

        val options = CliArguments.parse(
            args = arrayOf(
                "-prompt",
                "Run a small Codex smoke task",
                "-input",
                inputDir.absolutePath,
                "-output",
                outputDir.absolutePath,
                "-llm-backend",
                "codex",
                "-rounds",
                "1",
                "-model",
                "Researcher=${ModelSelectionConfig.CODEX_CHATGPT_GPT56_LUNA_MODEL}"
            ),
            codexCliLocator = { "/tmp/codex" },
            codexSessionPreflight = { preflightPaths += it }
        )

        assertNotNull(options)
        assertEquals(LlmBackend.CODEX_CHATGPT, options.llmBackend)
        assertEquals(ModelSelectionConfig.CODEX_CHATGPT_GPT56_LUNA_MODEL, options.modelOverrides["Researcher"])
        assertEquals("/tmp/codex", options.openRouterApiKey)
        assertEquals(listOf("/tmp/codex"), preflightPaths)
    }

    @Test
    fun `daemon client normalizes run path arguments before handoff`() {
        val normalized = LocalRunDaemonClient.normalizePathArguments(
            arrayOf(
                "run",
                "-prompt",
                "keep literal",
                "-input",
                "relative-input",
                "-output",
                "relative-output",
                "-promptfile",
                "/tmp/already-absolute.md",
                "-pipeline-config",
                "configs/pipeline.json"
            )
        )

        val workingDirectory = Path.of("").toAbsolutePath().normalize()
        assertEquals(workingDirectory.resolve("relative-input").toString(), normalized[4])
        assertEquals(workingDirectory.resolve("relative-output").toString(), normalized[6])
        assertEquals("/tmp/already-absolute.md", normalized[8])
        assertEquals(workingDirectory.resolve("configs/pipeline.json").toString(), normalized[10])
    }

    @Test
    fun `daemon client does not intercept help or non-run subcommands`() {
        assertFalse(LocalRunDaemonClient.shouldDelegate(arrayOf("run", "--help"), emptyMap()))
        assertFalse(LocalRunDaemonClient.shouldDelegate(arrayOf("gptauth", "status"), emptyMap()))
        assertFalse(LocalRunDaemonClient.shouldDelegate(arrayOf("run", "-prompt", "x"), mapOf("EVOMOLIOR_DISABLE_DAEMON" to "1")))
        assertTrue(LocalRunDaemonClient.shouldDelegate(arrayOf("run", "-prompt", "x"), emptyMap()))
    }

    @Test
    fun `parses qwen cloud backend and qwen api key`() {
        val inputDir = createTempDirectory("cli-qwen-input-").toFile()
        val outputDir = createTempDirectory("cli-qwen-output-").toFile()

        val options = CliArguments.parse(
            arrayOf(
                "-prompt",
                "Generate an illustrated article",
                "-input",
                inputDir.absolutePath,
                "-output",
                outputDir.absolutePath,
                "-llm-backend",
                "qwen-cloud",
                "-qwen-api-key",
                "qwen-test",
                "-rounds",
                "1"
            )
        )

        assertNotNull(options)
        assertEquals(LlmBackend.QWEN_CLOUD, options.llmBackend)
        assertEquals("qwen-test", options.openRouterApiKey)
    }

    @Test
    fun `parses qwen backed frames benchmark options`() {
        val outputDir = createTempDirectory("tier0-benchmark-output-")

        val options = BenchmarkCliArguments.parse(
            arrayOf(
                "-benchmark",
                "frames",
                "-limit",
                "2",
                "-offset",
                "2",
                "-output",
                outputDir.toString(),
                "-qwen-api-key",
                "qwen-test",
                "-group-count",
                "1",
                "-task-timeout-minutes",
                "7"
            )
        )

        assertNotNull(options)
        assertEquals(Tier0BenchmarkKind.FRAMES, options.benchmark)
        assertEquals(2, options.limit)
        assertEquals(2, options.offset)
        assertEquals(outputDir.toAbsolutePath().normalize(), options.outputDir)
        assertEquals(LlmBackend.QWEN_CLOUD, options.llmBackend)
        assertEquals("qwen-test", options.apiKey)
        assertEquals(1, options.groupCount)
        assertEquals(7, options.taskTimeoutMinutes)
        assertEquals(BenchmarkCliRunnerKind.EVOMOLIOR_SOCIETY, options.runner)
        assertEquals(FastModelSelectionConfig.FAST_QWEN_CLOUD_QWEN_MODEL, options.modelOverrides["Researcher"])
    }

    @Test
    fun `parses single agent qwen baseline benchmark options`() {
        val outputDir = createTempDirectory("tier0-single-agent-baseline-output-")

        val options = BenchmarkCliArguments.parse(
            arrayOf(
                "-benchmark",
                "frames",
                "-limit",
                "1",
                "-output",
                outputDir.toString(),
                "-qwen-api-key",
                "qwen-test",
                "-single-agent-baseline"
            )
        )

        assertNotNull(options)
        assertEquals(BenchmarkCliRunnerKind.SINGLE_AGENT, options.runner)
        assertEquals(LlmBackend.QWEN_CLOUD, options.llmBackend)
        assertEquals(FastModelSelectionConfig.FAST_QWEN_CLOUD_QWEN_MODEL, options.modelOverrides["Baseline"] ?: options.modelOverrides["Researcher"])
    }

    @Test
    fun `single agent baseline rejects non qwen backend`() {
        val outputDir = createTempDirectory("tier0-single-agent-openrouter-output-")

        val error = assertFailsWith<CliUsageException> {
            BenchmarkCliArguments.parse(
                arrayOf(
                    "-benchmark",
                    "frames",
                    "-limit",
                    "1",
                    "-output",
                    outputDir.toString(),
                    "-openrouter-api-key",
                    "sk-test",
                    "-runner",
                    "single-agent"
                )
            )
        }

        assertTrue(error.message.orEmpty().contains("qwen-cloud"))
    }

    @Test
    fun `single agent baseline executes tool loop and writes benchmark artifacts`() = runBlocking {
        val outputDir = createTempDirectory("tier0-single-agent-baseline-run-output-")
        val tasksJsonl = createTempDirectory("tier0-single-agent-baseline-tasks-").resolve("tasks.jsonl")
        tasksJsonl.toFile().writeText(
            """
            {"id":"baseline-1","question":"What does mechanism X do?","paper_ids":["paper-a"],"source_passages":[{"paper_id":"paper-a","passage":"Mechanism X improves signal Y in treated cells."}],"required_claims":["Mechanism X improves signal Y"],"ideal_answer":"Mechanism X improves signal Y."}
            """.trimIndent()
        )
        val callCount = AtomicInteger(0)
        val runner = BenchmarkCliRunner(
            singleAgentChatCompletion = { _, modelId, messages ->
                assertEquals(FastModelSelectionConfig.FAST_QWEN_CLOUD_QWEN_MODEL, modelId)
                assertTrue(messages.first().toString().contains("cache_control"))
                assertTrue(messages[1].toString().contains("cache_control"))
                when (callCount.getAndIncrement()) {
                    0 -> buildJsonObject {
                        putJsonArray("choices") {
                            add(buildJsonObject {
                                putJsonObject("message") {
                                    put("role", "assistant")
                                    put("content", JsonNull)
                                    putJsonArray("tool_calls") {
                                        add(buildJsonObject {
                                            put("id", "call-read-source")
                                            put("type", "function")
                                            putJsonObject("function") {
                                                put("name", "read_run_artifact")
                                                put(
                                                    "arguments",
                                                    """{"relativePath":"benchmark-sources/scholarqa-baseline-1/1-paper-a.md"}"""
                                                )
                                            }
                                        })
                                    }
                                }
                            })
                        }
                        putJsonObject("usage") {
                            put("prompt_tokens", 10)
                            put("completion_tokens", 5)
                            put("total_tokens", 15)
                        }
                    }
                    else -> buildJsonObject {
                        putJsonArray("choices") {
                            add(buildJsonObject {
                                putJsonObject("message") {
                                    put("role", "assistant")
                                    put(
                                        "content",
                                        """
                                        {
                                          "final_answer": "Mechanism X improves signal Y.",
                                          "evidence_path": [
                                            {"source": "paper-a", "claim": "Mechanism X improves signal Y in treated cells."}
                                          ],
                                          "supporting_passages": ["Mechanism X improves signal Y in treated cells."],
                                          "confidence": 0.92,
                                          "total_cost": 0.25
                                        }
                                        """.trimIndent()
                                    )
                                }
                            })
                        }
                        putJsonObject("usage") {
                            put("prompt_tokens", 20)
                            put("completion_tokens", 10)
                            put("total_tokens", 30)
                        }
                    }
                }
            }
        )

        val report = runner.run(
            BenchmarkCliOptions(
                benchmark = Tier0BenchmarkKind.SCHOLAR_QA,
                scholarQaBenchVariant = ScholarQaBenchVariant.QASA,
                limit = 1,
                offset = 0,
                outputDir = outputDir,
                apiKey = "qwen-test",
                llmBackend = LlmBackend.QWEN_CLOUD,
                fast = true,
                groupCount = 1,
                rounds = 1,
                roundDurationMinutes = 1,
                maxAgentsPerGroup = 1,
                budgetOverride = 1.0,
                taskTimeoutMinutes = 1,
                costOverrideUsdPerTask = null,
                tasksJsonl = tasksJsonl,
                modelOverrides = emptyMap(),
                runner = BenchmarkCliRunnerKind.SINGLE_AGENT
            )
        )

        val taskOutputDir = outputDir.resolve("scholarqa-baseline-1")
        assertEquals(BenchmarkCliRunnerKind.SINGLE_AGENT, report.runner)
        assertEquals(FastModelSelectionConfig.FAST_QWEN_CLOUD_QWEN_MODEL, report.modelId)
        assertTrue(report.failures.isEmpty(), report.failures.joinToString())
        assertEquals("COMPLETED", report.taskResults.single().status)
        assertEquals(0.25, report.taskResults.single().totalCost)
        assertEquals(0.25, report.totalCost)
        assertEquals(1.0, report.taskResults.single().toolNativeSuccessRate)
        assertEquals(1.0, report.toolNativeSuccessRate)
        assertEquals(2, callCount.get())
        assertTrue(Files.isRegularFile(taskOutputDir.resolve("single-agent-final.txt")))
        assertTrue(Files.isRegularFile(taskOutputDir.resolve("tier0-prediction.json")))
        assertTrue(Files.isRegularFile(taskOutputDir.resolve("single-agent-baseline-report.json")))
        assertTrue(taskOutputDir.resolve("tier0-prediction.json").readText().contains("Mechanism X improves signal Y"))
        assertTrue(taskOutputDir.resolve("single-agent-baseline-report.json").readText().contains("\"model_id\":\"qwen3.7-plus\""))
        assertTrue(taskOutputDir.resolve("single-agent-baseline-report.json").readText().contains("\"tool_native_success_rate\":1.0"))
        assertTrue(report.taskResults.single().evidencePathPresent)
    }

    @Test
    fun `single agent qwen request body splits required tool turn from json emission turn`() {
        val runner = BenchmarkCliRunner()
        val messages = listOf(
            buildJsonObject {
                put("role", "system")
                put("content", "Use tools, then return JSON.")
            }
        )

        val toolTurn = runner.buildSingleAgentChatCompletionBody(
            modelId = FastModelSelectionConfig.FAST_QWEN_CLOUD_QWEN_MODEL,
            messages = messages,
            requireToolCall = true
        )
        assertEquals("required", toolTurn["tool_choice"]?.jsonPrimitive?.content)
        assertFalse("response_format" in toolTurn)
        assertEquals("false", toolTurn["enable_thinking"]?.jsonPrimitive?.content)

        val jsonEmissionTurn = runner.buildSingleAgentChatCompletionBody(
            modelId = FastModelSelectionConfig.FAST_QWEN_CLOUD_QWEN_MODEL,
            messages = messages,
            requireToolCall = false
        )
        assertEquals(
            "json_object",
            jsonEmissionTurn["response_format"]?.jsonObject?.get("type")?.jsonPrimitive?.content
        )
        assertEquals("false", jsonEmissionTurn["enable_thinking"]?.jsonPrimitive?.content)
        assertFalse("tool_choice" in jsonEmissionTurn)
    }

    @Test
    fun `parses openrouter backed litqa2 benchmark options`() {
        val outputDir = createTempDirectory("tier1-litqa2-benchmark-output-")

        val options = BenchmarkCliArguments.parse(
            arrayOf(
                "-benchmark",
                "litqa2",
                "-limit",
                "3",
                "-output",
                outputDir.toString(),
                "-openrouter-api-key",
                "sk-test",
                "-group-count",
                "2",
                "-rounds",
                "1"
            )
        )

        assertNotNull(options)
        assertEquals(Tier0BenchmarkKind.LITQA2, options.benchmark)
        assertEquals(3, options.limit)
        assertEquals(outputDir.toAbsolutePath().normalize(), options.outputDir)
        assertEquals(LlmBackend.OPENROUTER, options.llmBackend)
        assertEquals("sk-test", options.apiKey)
        assertEquals(2, options.groupCount)
        assertEquals(1, options.rounds)
    }

    @Test
    fun `parses scholarqabench benchmark options without local jsonl`() {
        val outputDir = createTempDirectory("tier1-scholarqabench-benchmark-output-")

        val options = BenchmarkCliArguments.parse(
            arrayOf(
                "-benchmark",
                "scholarqabench",
                "-limit",
                "1",
                "-offset",
                "2",
                "-output",
                outputDir.toString(),
                "-openrouter-api-key",
                "sk-test"
            )
        )

        assertNotNull(options)
        assertEquals(Tier0BenchmarkKind.SCHOLAR_QABENCH, options.benchmark)
        assertEquals(ScholarQaBenchVariant.QASA, options.scholarQaBenchVariant)
        assertEquals(1, options.limit)
        assertEquals(2, options.offset)
        assertEquals(null, options.tasksJsonl)
    }

    @Test
    fun `parses scholarqabench variant aliases and explicit selector`() {
        val outputDir = createTempDirectory("tier1-scholarqabench-variants-output-")

        val pubmedOptions = BenchmarkCliArguments.parse(
            arrayOf(
                "-benchmark",
                "scholarqabench-pubmed",
                "-limit",
                "1",
                "-output",
                outputDir.toString(),
                "-openrouter-api-key",
                "sk-test"
            )
        )

        val csOptions = BenchmarkCliArguments.parse(
            arrayOf(
                "-benchmark",
                "scholarqabench",
                "-scholarqabench-variant",
                "cs",
                "-limit",
                "1",
                "-output",
                outputDir.toString(),
                "-openrouter-api-key",
                "sk-test"
            )
        )

        assertNotNull(pubmedOptions)
        assertEquals(Tier0BenchmarkKind.SCHOLAR_QABENCH, pubmedOptions.benchmark)
        assertEquals(ScholarQaBenchVariant.PUBMED, pubmedOptions.scholarQaBenchVariant)
        assertNotNull(csOptions)
        assertEquals(Tier0BenchmarkKind.SCHOLAR_QABENCH, csOptions.benchmark)
        assertEquals(ScholarQaBenchVariant.CS, csOptions.scholarQaBenchVariant)
    }

    @Test
    fun `parses scholar qa benchmark options with jsonl task file`() {
        val outputDir = createTempDirectory("tier1-scholarqa-benchmark-output-")
        val tasksFile = Files.createTempFile("scholarqa-tasks-", ".jsonl")
        Files.writeString(
            tasksFile,
            """{"id":"task-1","question":"Synthesize finding X.","paper_ids":["10.1234/example"],"source_passages":[{"paper_id":"10.1234/example","passage":"finding X improves outcome Y"}],"required_claims":["finding X improves outcome Y"]}"""
        )

        val options = BenchmarkCliArguments.parse(
            arrayOf(
                "-benchmark",
                "openscholar",
                "-limit",
                "2",
                "-output",
                outputDir.toString(),
                "-tasks-jsonl",
                tasksFile.toString(),
                "-cost-override-usd-per-task",
                "0.0125",
                "-openrouter-api-key",
                "sk-test"
            )
        )

        assertNotNull(options)
        assertEquals(Tier0BenchmarkKind.SCHOLAR_QA, options.benchmark)
        assertEquals(tasksFile.toAbsolutePath().normalize(), options.tasksJsonl)
        assertEquals(LlmBackend.OPENROUTER, options.llmBackend)
    }

    @Test
    fun `scholar qa benchmark requires jsonl task file`() {
        val outputDir = createTempDirectory("tier1-scholarqa-missing-jsonl-output-")

        val error = assertFailsWith<CliUsageException> {
            BenchmarkCliArguments.parse(
                arrayOf(
                    "-benchmark",
                    "scholarqa",
                    "-output",
                    outputDir.toString(),
                    "-openrouter-api-key",
                    "sk-test"
                )
            )
        }

        assertTrue(error.message.orEmpty().contains("-tasks-jsonl"))
    }

    @Test
    fun `parses autoresearchbench benchmark options with jsonl task file`() {
        val outputDir = createTempDirectory("tier2-arb-benchmark-output-")
        val tasksFile = Files.createTempFile("autoresearchbench-tasks-", ".jsonl")
        Files.writeString(
            tasksFile,
            """{"id":"deep-1","task_type":"deep","question":"Find the target paper.","gold_paper_ids":["10.1234/target"],"source_passages":[{"paper_id":"10.1234/seed","passage":"seed passage"}]}"""
        )

        val options = BenchmarkCliArguments.parse(
            arrayOf(
                "-benchmark",
                "autoresearchbench",
                "-limit",
                "2",
                "-output",
                outputDir.toString(),
                "-tasks-jsonl",
                tasksFile.toString(),
                "-cost-override-usd-per-task",
                "0.0125",
                "-openrouter-api-key",
                "sk-test"
            )
        )

        assertNotNull(options)
        assertEquals(Tier0BenchmarkKind.AUTO_RESEARCH_BENCH, options.benchmark)
        assertEquals(tasksFile.toAbsolutePath().normalize(), options.tasksJsonl)
        assertEquals(LlmBackend.OPENROUTER, options.llmBackend)
        assertEquals(0.0125, options.costOverrideUsdPerTask)
    }

    @Test
    fun `parses astabench literature aliases as local scholar qa tasks`() {
        val outputDir = createTempDirectory("tier2-astabench-output-")
        val tasksFile = Files.createTempFile("astabench-literature-", ".jsonl")
        Files.writeString(
            tasksFile,
            """{"id":"digestable-1","question":"Compare papers.","paper_ids":["p1"],"source_passages":[{"paper_id":"p1","passage":"Paper p1 has property x."}]}"""
        )

        val options = BenchmarkCliArguments.parse(
            arrayOf(
                "-benchmark",
                "arxivdigestables",
                "-limit",
                "1",
                "-output",
                outputDir.toString(),
                "-tasks-jsonl",
                tasksFile.toString(),
                "-openrouter-api-key",
                "sk-test"
            )
        )

        assertNotNull(options)
        assertEquals(Tier0BenchmarkKind.SCHOLAR_QA, options.benchmark)
        assertEquals(tasksFile.toAbsolutePath().normalize(), options.tasksJsonl)
    }

    @Test
    fun `benchmark parser rejects blank explicit api key`() {
        val outputDir = createTempDirectory("tier1-litqa2-blank-key-output-")

        val error = assertFailsWith<CliUsageException> {
            BenchmarkCliArguments.parse(
                arrayOf(
                    "-benchmark",
                    "litqa2",
                    "-limit",
                    "1",
                    "-output",
                    outputDir.toString(),
                    "-openrouter-api-key",
                    ""
                )
            )
        }

        assertTrue(error.message.orEmpty().contains("non-blank"))
    }

    @Test
    fun `litqa2 rows parser builds benchmark tasks without leaking key passage`() {
        val tasks = parseLitQa2RowsResponse(
            """
            {
              "rows": [
                {
                  "row_idx": 0,
                  "row": {
                    "id": "e3b5a4af-41d9-48db-becf-29a08d0ad28e",
                    "question": "Acinetobacter lwoffii has been evolved in the lab to be resistant to which of these antibiotics?",
                    "ideal": "ciproflaxin",
                    "distractors": ["meropenem", "gentamicin", "ampicillin"],
                    "sources": ["https://doi.org/10.1128/msphere.00109-24"],
                    "key-passage": "A. lwoffii only evolved resistance to ciprofloxacin"
                  }
                }
              ]
            }
            """.trimIndent()
        )

        val task = tasks.single()
        assertEquals("litqa2-e3b5a4af-41d9-48db-becf", task.id)
        assertEquals(Tier0BenchmarkKind.LITQA2, task.benchmark)
        assertEquals(listOf("ciproflaxin"), task.acceptedAnswers)
        assertEquals(listOf("https://doi.org/10.1128/msphere.00109-24"), task.relevantSources)
        assertTrue(task.question.contains("Answer choices"))
        assertTrue(task.question.contains("meropenem"))
        assertFalse(task.question.contains("only evolved resistance"))
    }

    @Test
    fun `frames source helpers parse as-of ranking evidence from extracted tables`() {
        val runner = BenchmarkCliRunner()
        val rows = runner.parseTallestNewYorkBuildingRows(
            """
            Table 2
            Rank | Name | Image | Location | Height ft (m) | Floors | Year | Purpose | Notes
            33= | 425 Park Avenue | Midtown Manhattan | 860 (262) | 44 | 2021 | Office | Designed by Foster
            33= | 262 Fifth Avenue | NoMad | 860 (262) | 56 | 2025 | Residential | Future row
            35 | 30 Rockefeller Plaza | Midtown Manhattan | 850 (259) | 70 | 1933 | Office | Art Deco
            36= | One Manhattan Square | Two Bridges | 847 (258) | 72 | 2019 | Residential | Completed
            36= | Sutton Tower | Midtown Manhattan | 847 (258) | 65 | 2022 | Residential | Completed
            38 | The Orchard | Long Island City | 823 (251) | 69 | 2026 | Residential | Future row
            39 | 56 Leonard Street | Lower Manhattan | 821 (250) | 57 | 2016 | Residential | Completed
            Table 3
            Pinn. Rank | Std. Rank | Name | Pinnacle height ft (m) | Standard height ft (m) | Floors | Year | Sources
            10 | 39 | Condé Nast Building | 1,118 (341) | 809 (247) | 48 | 1999
            """.trimIndent()
        )

        val asOfRows = rows.filter { it.year <= 2024 }
        val context = runner.contextRowsAroundHeight(asOfRows, 823.8)

        assertEquals(2024, runner.extractAsOfYear("Where would this building rank, as of August 2024?"))
        assertEquals("2024-09-01T00:00:00Z", runner.extractAsOfSnapshotTimestamp("Where would this building rank, as of August 2024?"))
        assertEquals(823.8, runner.extractDeweyDecimal("Dewey&#160;Decimal | 823.8"))
        assertEquals(823.8, runner.extractDeweyDecimal("Dewey\u00A0 \u00A0Decimal | 823.8"))
        assertEquals(
            listOf("425 Park Avenue", "30 Rockefeller Plaza", "One Manhattan Square", "Sutton Tower", "56 Leonard Street"),
            asOfRows.map { it.name }
        )
        assertEquals(
            listOf("425 Park Avenue", "30 Rockefeller Plaza", "One Manhattan Square", "Sutton Tower", "56 Leonard Street"),
            context.map { it.name }
        )
        assertEquals(4, asOfRows.count { it.heightFeet > 823.8 })
        assertEquals(39, runner.inferPublishedTableRank(asOfRows, 823.8))
    }

    @Test
    fun `frames wrestlemania summary warns on incomplete coverage and abstains from partial count`() {
        val runner = BenchmarkCliRunner()
        val task = Tier0BenchmarkTask(
            id = "frames-444",
            benchmark = Tier0BenchmarkKind.FRAMES,
            question = "How many of Mark Calaway's consecutive Wrestlemania wins were longer than the final result?",
            acceptedAnswers = listOf("Five"),
            relevantSources = emptyList(),
            answerFormat = BenchmarkAnswerFormat.EXACT
        )
        val summary = runner.buildFramesSourceSummaryForTesting(
            task = task,
            sourcesByUrl = mapOf(
                "https://en.wikipedia.org/wiki/The_Streak_(professional_wrestling)" to """
                    Result | Rec. | Opponent | Date | Match time | Venue | Note
                    Win | 1-0 | Jimmy Snuka | March 24, 1991 WrestleMania VII | 4:20 Pinfall | Los Angeles
                    Win | 2-0 | Jake Roberts | April 5, 1992 WrestleMania VIII | 6:36 Pinfall | Indianapolis
                    Win | 3-0 | Giant Gonzalez | April 4, 1993 WrestleMania IX | 7:33 Disqualification | Las Vegas
                    Win | 4-0 | King Kong Bundy | April 2, 1995 WrestleMania XI | 6:36 Pinfall | Hartford
                    Loss | 21-1 | Brock Lesnar | April 6, 2014 WrestleMania XXX | 25:12 Pinfall | New Orleans
                """.trimIndent(),
                "https://en.wikipedia.org/wiki/WrestleMania_VII#Results" to """
                    Table 4
                    No. | Results | Stipulations | Times
                    7 | The Undertaker (with Paul Bearer) defeated Jimmy Snuka by pinfall | Singles match | 4:20
                    15 | Hulk Hogan defeated Sgt. Slaughter by pinfall | Singles match for the WWF Championship | 20:26
                """.trimIndent(),
                "https://en.wikipedia.org/wiki/WrestleMania_IX#Results" to """
                    Table 4
                    No. | Results | Stipulations | Times
                    8 | The Undertaker (with Paul Bearer) defeated Giant Gonzalez by disqualification | Singles match | 7:33
                    10 | Hulk Hogan defeated Yokozuna | Singles match for the WWF Championship | 0:22
                """.trimIndent()
            )
        )

        assertNotNull(summary)
        assertTrue(summary.contains("Coverage warning: the uploaded event-result tables do not cover every consecutive-win year."))
        assertTrue(summary.contains("Diagnostic count from uploaded event-result rows only, excluding Calaway-final rows: 1."))
        assertFalse(summary.contains("Five"))

        val partialPrediction = Tier0BenchmarkPrediction(
            taskId = task.id,
            finalAnswer = "1",
            evidencePath = listOf(
                Tier0EvidenceStep(
                    source = "benchmark-sources/frames-444/source-summary.md",
                    claim = "Diagnostic count is 1."
                )
            ),
            confidence = 0.4
        )
        val abstained = runner.applySourceSummaryCoverageAbstentionForTesting(
            task = task,
            prediction = partialPrediction,
            sourceSummary = summary
        )

        assertEquals("", abstained.finalAnswer)
        assertEquals(0.0, abstained.confidence)
        assertTrue(abstained.evidencePath.any { it.claim.contains("Abstained because source-summary reports incomplete") })
    }

    @Test
    fun `normal run source instructions prioritize generated summary artifacts`() {
        val instruction = BenchmarkCliRunner().buildNormalRunSourceInstruction(
            listOf(
                RunInputArtifactUpload(
                    relativePath = "source-artifacts/normal/source-summary.md",
                    sourceName = "deterministic-summary:normal",
                    mimeType = "text/markdown",
                    contentBase64 = "",
                    kind = RunInputArtifactKind.TEXT,
                    textPreview = "Published table-rank insertion"
                )
            )
        )

        assertTrue(instruction.contains("Uploaded source artifacts"))
        assertTrue(instruction.contains("Read source-summary.md before other source artifacts"))
        assertTrue(instruction.contains("read_run_artifact"))
    }

    @Test
    fun `text only artifact instructions embed uploaded artifact text`() {
        val instruction = BenchmarkCliRunner().buildTextOnlyArtifactInstruction(
            artifacts = listOf(
                RunInputArtifactUpload(
                    relativePath = "metasyn_task_24_candidates.md",
                    sourceName = "metasyn_task_24_candidates.md",
                    mimeType = "text/markdown",
                    contentBase64 = Base64.getEncoder().encodeToString(
                        "CANDIDATE-4565\nAbstract: included evidence.".toByteArray(Charsets.UTF_8)
                    ),
                    kind = RunInputArtifactKind.TEXT,
                    textPreview = "CANDIDATE-4565"
                )
            ),
            totalBudget = 200,
            perArtifactBudget = 200
        )

        assertTrue(instruction.contains("text-only backend compatibility"))
        assertTrue(instruction.contains("metasyn_task_24_candidates.md"))
        assertTrue(instruction.contains("CANDIDATE-4565"))
        assertTrue(instruction.contains("Do not call read_run_artifact"))
    }

    @Test
    fun `source artifact excerpts keep relation evidence from long text lines`() {
        val excerpt = BenchmarkCliRunner().selectSourceArtifactExcerptForTesting(
            text = """
                # Benchmark source

                URL: https://en.wikipedia.org/wiki/James_A._Garfield
                Wikipedia title: James A. Garfield

                ## Plaintext extract

                James Abram Garfield was born into poverty in a log cabin. ${"x".repeat(650)} James's father Abraham came to Ohio to woo his childhood sweetheart, Mehitabel Ballou, only to find her married. He instead wed her sister Eliza, who was born in New Hampshire. ${"x".repeat(650)} James was raised in a household led by his strong-willed mother.
            """.trimIndent(),
            question = "What surname is the same as the second assassinated president's mother's maiden name?"
        )

        assertTrue(excerpt.contains("Mehitabel Ballou"))
        assertTrue(excerpt.contains("sister Eliza"))
    }

    @Test
    fun `scholar qa jsonl parser builds citation backed synthesis tasks`() {
        val tasksFile = Files.createTempFile("scholarqa-parser-", ".jsonl")
        Files.writeString(
            tasksFile,
            """
            {"id":"mechanism/a","question":"Explain mechanism A.","paper_ids":["10.1000/a"],"sources":["https://doi.org/10.1000/b"],"source_passages":[{"paper_id":"10.1000/a","passage":"mechanism A activates pathway B in the source corpus."}],"required_claims":["mechanism A activates pathway B"],"ideal_answer":"Mechanism A activates pathway B."}
            """.trimIndent()
        )

        val task = parseScholarQaJsonl(tasksFile).single()

        assertEquals("scholarqa-mechanism-a", task.id)
        assertEquals(Tier0BenchmarkKind.SCHOLAR_QA, task.benchmark)
        assertEquals(listOf("Mechanism A activates pathway B."), task.acceptedAnswers)
        assertEquals(listOf("https://doi.org/10.1000/b", "10.1000/a"), task.relevantSources)
        assertEquals("10.1000/a", task.sourcePassages.single().paperId)
        assertEquals("mechanism A activates pathway B in the source corpus.", task.sourcePassages.single().passage)
        assertEquals(listOf("mechanism A activates pathway B"), task.requiredClaims)
        assertTrue(task.requiresBrowsing)
    }

    @Test
    fun `autoresearchbench jsonl parser builds literature discovery tasks`() {
        val tasksFile = Files.createTempFile("autoresearchbench-parser-", ".jsonl")
        Files.writeString(
            tasksFile,
            """
            {"id":"wide/a","task_type":"wide","question":"Find all papers about method A in venue B.","gold_paper_ids":["10.1000/a","10.1000/b"],"seed_paper_ids":["10.1000/seed"],"source_passages":[{"paper_id":"10.1000/seed","passage":"Method A appears in venue B papers."}]}
            """.trimIndent()
        )

        val task = parseAutoResearchBenchJsonl(tasksFile).single()

        assertEquals("autoresearchbench-wide-a", task.id)
        assertEquals(Tier0BenchmarkKind.AUTO_RESEARCH_BENCH, task.benchmark)
        assertEquals(listOf("10.1000/a", "10.1000/b"), task.acceptedAnswers)
        assertEquals("10.1000/seed", task.relevantSources.single())
        assertEquals("10.1000/seed", task.sourcePassages.single().paperId)
        assertEquals(com.achulkov.evomolior.core.benchmark.LiteratureDiscoveryTaskType.WIDE, task.literatureDiscoveryTaskType)
    }

    @Test
    fun `scholarqabench qasa parser builds fixed corpus tasks from gold contexts`() {
        val tasks = parseScholarQaBenchQasaJsonl(
            """
            {"input":"What are the side effects of group convolution?","answer":"Blocked flow of information between channel groups [16].","gold_ctxs":[16],"ctxs":[{"id":"1707.01083_all_0","text":"irrelevant context"},{"id":"1707.01083_all_16","title":"ShuffleNet","text":"Group convolutions can block information flow between channel groups."}]}
            """.trimIndent()
        )

        val task = tasks.single()

        assertEquals("scholarqabench-qasa-00000", task.id)
        assertEquals(Tier0BenchmarkKind.SCHOLAR_QABENCH, task.benchmark)
        assertEquals(listOf("Blocked flow of information between channel groups [16]."), task.acceptedAnswers)
        assertEquals(listOf("1707.01083_all_16"), task.relevantSources)
        assertEquals("1707.01083_all_16", task.sourcePassages.single().paperId)
        assertTrue(task.sourcePassages.single().passage.contains("ShuffleNet"))
        assertEquals(listOf("Blocked flow of information between channel groups."), task.requiredClaims)
    }

    @Test
    fun `scholarqabench qasa parser prefers gold context positions before id suffix fallback`() {
        val tasks = parseScholarQaBenchQasaJsonl(
            """
            {"input":"What does the paper say?","answer":"position selected answer [1].","gold_ctxs":[1],"ctxs":[{"id":"paper_all_1","text":"suffix would be wrong if selected first"},{"id":"paper_no_suffix","title":"Selected","text":"position context contains the answer"}]}
            """.trimIndent()
        )

        val task = tasks.single()

        assertEquals("paper_no_suffix", task.sourcePassages.single().paperId)
        assertTrue(task.sourcePassages.single().passage.contains("position context contains the answer"))
    }

    @Test
    fun `scholarqabench gold context parser builds pubmed and scifact style tasks`() {
        val tasks = parseScholarQaBenchGoldContextJsonl(
            body = """
            {"input":"Is monitoring helpful?","gold_ctx":[{"title":"Monitoring Trial","text":"PHQ-9 monitoring was helpful and feasible."}],"answer":"yes"}
            """.trimIndent(),
            variant = ScholarQaBenchVariant.PUBMED
        )

        val task = tasks.single()

        assertEquals("scholarqabench-pubmed-00000", task.id)
        assertEquals(listOf("yes"), task.acceptedAnswers)
        assertEquals(listOf("yes"), task.requiredClaims)
        assertEquals("Monitoring-Trial", task.sourcePassages.single().paperId)
        assertTrue(task.question.contains("exact concise answer label"))
    }

    @Test
    fun `scholarqabench cs snippets parser builds long form fixed corpus tasks`() {
        val tasks = parseScholarQaBenchCsSnippetsJsonl(
            """
            {"question":"What datasets are used?","ingredients":{"most_important":[{"text":"Mention Dataset A.","snippets":["Dataset A is used for evaluation.","Another supporting snippet."]},{"text":"Mention Dataset B.","snippets":["Dataset B is also used."]}]}}
            """.trimIndent()
        )

        val task = tasks.single()

        assertEquals("scholarqabench-cs-00000", task.id)
        assertEquals(Tier0BenchmarkKind.SCHOLAR_QABENCH, task.benchmark)
        assertEquals(BenchmarkAnswerFormat.SYNTHESIS, task.answerFormat)
        assertTrue(task.question.contains("ScholarQABench CS scoring rubric"))
        assertTrue(task.question.contains("complete answer must cover every rubric ingredient"))
        assertTrue(task.question.contains("Mention Dataset A."))
        assertEquals(
            listOf("Dataset A is used for evaluation.", "Dataset B is also used."),
            task.requiredClaims
        )
        assertEquals(3, task.sourcePassages.size)
        assertEquals("scholarqabench-cs-00000-ingredient-1-snippet-1", task.sourcePassages.first().paperId)
        assertEquals(
            listOf(
                "scholarqabench-cs-00000-ingredient-1-snippet-1",
                "scholarqabench-cs-00000-ingredient-2-snippet-1"
            ),
            task.relevantSources
        )
    }

    @Test
    fun `scholarqabench cs rubric task scores full citation and claim coverage`() {
        val task = parseScholarQaBenchCsSnippetsJsonl(
            """
            {"question":"What datasets are used?","ingredients":{"most_important":[{"text":"Mention Dataset A.","snippets":["Dataset A is used for evaluation.","Another supporting snippet."]},{"text":"Mention Dataset B.","snippets":["Dataset B is also used."]}]}}
            """.trimIndent()
        ).single()
        val prompt = Tier0BenchmarkPromptContract.buildObjective(task)
        val prediction = Tier0BenchmarkPrediction(
            taskId = task.id,
            finalAnswer = "Dataset A is used for evaluation [${task.relevantSources[0]}]. Dataset B is also used [${task.relevantSources[1]}].",
            evidencePath = listOf(
                Tier0EvidenceStep(task.relevantSources[0], "Dataset A is used for evaluation."),
                Tier0EvidenceStep(task.relevantSources[1], "Dataset B is also used.")
            ),
            supportingPassages = task.requiredClaims,
            paperIds = task.relevantSources,
            confidence = 1.0
        )

        assertTrue(prompt.contains("ScholarQABench CS scoring rubric"))
        assertTrue(prompt.contains("citation-backed synthesis answer"))
        assertFalse(prompt.contains("concise exact answer string; no explanation"))
        assertEquals(1.0, scoreCitationSupport(task, prediction))
        assertEquals(1.0, scoreClaimCoverage(task, prediction))
    }

    @Test
    fun `benchmark report serializes empty failures field`() {
        val report = BenchmarkCliReport(
            benchmark = Tier0BenchmarkKind.FRAMES,
            backend = LlmBackend.QWEN_CLOUD,
            runner = BenchmarkCliRunnerKind.SINGLE_AGENT,
            modelId = FastModelSelectionConfig.FAST_QWEN_CLOUD_QWEN_MODEL,
            modelOverrides = emptyMap(),
            startedAt = "2026-07-03T00:00:00Z",
            completedAt = "2026-07-03T00:00:01Z",
            executedCount = 0,
            exactCorrectCount = 0,
            exactAccuracy = 0.0,
            evidencePathCoverage = 0.0,
            citationSupportCoverage = null,
            claimCoverage = null,
            totalCost = 0.0,
            taskResults = emptyList(),
            failures = emptyList()
        )

        val json = benchmarkJson.encodeToString(report)

        assertTrue(json.contains("\"failures\": []"))
        assertTrue(json.contains("\"runner\": \"single-agent\""))
        assertTrue(json.contains("\"modelId\": \"qwen3.7-plus\""))
        assertTrue(json.contains("\"totalCost\": 0.0"))
    }

    @Test
    fun `fast qwen cloud mode rejects openrouter model ids`() {
        val inputDir = createTempDirectory("cli-qwen-fast-input-").toFile()
        val outputDir = createTempDirectory("cli-qwen-fast-output-").toFile()

        val error = assertFailsWith<CliUsageException> {
            CliArguments.parse(
                arrayOf(
                    "-prompt",
                    "Generate an illustrated article",
                    "-input",
                    inputDir.absolutePath,
                    "-output",
                    outputDir.absolutePath,
                    "-llm-backend",
                    "qwen-cloud",
                    "-qwen-api-key",
                    "qwen-test",
                    "-fast",
                    "-model",
                    "RESEARCHER=deepseek/deepseek-v4-flash"
                )
            )
        }

        assertTrue(error.message.orEmpty().contains("qwen3.7-plus"))
        assertTrue(error.message.orEmpty().contains("deepseek-v4-flash"))
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
                "-rounds",
                "1",
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
    fun `pipeline config decodes stable acceptance profile ids`() {
        val decoded = cliJson.decodeFromString(
            CliPipelineConfig.serializer(),
            """
            {
              "stages": [
                {
                  "id": "acceptance-smoke",
                  "prompt": "Produce the receiver-facing report.",
                  "rounds": 1,
                  "groupCount": 2,
                  "councilExecutionPurpose": "APPENDIX_B_ACCEPTANCE",
                  "acceptanceProfile": "runtime-usability-smoke",
                  "finalOutput": {
                    "relativePath": "report.md",
                    "mediaType": "text/markdown"
                  }
                }
              ]
            }
            """.trimIndent()
        )

        val stage = decoded.stages.single()
        assertEquals(
            CouncilExecutionPurpose.APPENDIX_B_ACCEPTANCE,
            stage.councilExecutionPurpose
        )
        assertEquals(AcceptanceProfile.RUNTIME_USABILITY_SMOKE, stage.acceptanceProfile)
        assertTrue(
            cliJson.encodeToString(CliPipelineConfig.serializer(), decoded)
                .contains("\"runtime-usability-smoke\"")
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
                "-rounds",
                "1",
                "-group-count",
                "6",
                "-max-agents-per-group",
                "5",
                "-round-duration-minutes",
                "3",
                "-budget-override",
                "480"
            )
        )

        assertNotNull(options)
        assertEquals(6, options.groupCount)
        assertEquals(5, options.maxAgentsPerGroup)
        assertEquals(3, options.roundDurationMinutes)
        assertEquals(480.0, options.budgetOverride)
    }

    @Test
    fun `accepts single group runs`() {
        val inputDir = createTempDirectory("cli-single-group-input-").toFile()
        val outputDir = createTempDirectory("cli-single-group-output-").toFile()

        val options = CliArguments.parse(
            arrayOf(
                "-prompt",
                "Single group objective",
                "-input",
                inputDir.absolutePath,
                "-output",
                outputDir.absolutePath,
                "-openrouter-api-key",
                "sk-test",
                "-rounds",
                "1",
                "-group-count",
                "1"
            )
        )

        assertNotNull(options)
        assertEquals(1, options.groupCount)
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
        assertEquals(2, fakeEngine.capturedConfig!!.groupCount)
        assertFalse(fakeEngine.capturedConfig!!.isTestRun)
    }

    @Test
    fun `runCli projects omitted rounds into bounded grader-authoritative completion`() = runBlocking {
        val inputDir = createTempDirectory("cli-auto-run-input-").toFile()
        val outputDir = createTempDirectory("cli-auto-run-output-").toFile()
        val fakeEngine = FakeCliEngineService()

        val result = runCli(
            options = CliOptions(
                prompt = "Continue until one winner is repeatedly accepted",
                inputDir = inputDir.toPath(),
                outputDir = outputDir.toPath(),
                openRouterApiKey = "sk-test",
                modelOverrides = emptyMap(),
                debug = false,
                fast = false,
                rounds = null,
                maxRounds = 8,
                completionStabilityRounds = 2,
                runId = "cli-auto-run"
            ),
            runtimeFactory = { _, _ -> CliRuntime(fakeEngine) }
        )

        assertTrue(result.completedSuccessfully)
        val captured = assertNotNull(fakeEngine.capturedConfig)
        assertEquals(8, captured.rounds)
        assertEquals(RunAutoCompletionPolicy(2), captured.autoCompletionPolicy)
        assertEquals(CouncilExecutionPurpose.STANDARD, captured.councilExecutionPurpose)
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
        assertTrue(runOutputDir.resolve("outputs/assets/images/cli-pipeline-default-hero.png").exists())
        assertTrue(runOutputDir.resolve("outputs/assets/images/cli-pipeline-default-fast-follow-up-hero.png").exists())
        assertTrue(runOutputDir.resolve("outputs/final-article.html").readText().contains("cli-pipeline-default"))
        assertTrue(runOutputDir.resolve("outputs/refined/final-article-refined.html").readText().contains("cli-pipeline-default-fast-follow-up"))

        val pipelineSummary = runOutputDir.resolve("pipeline-summary.json").readText()
        assertTrue(pipelineSummary.contains("\"publishedOutputs\": ["))
        assertTrue(pipelineSummary.contains("\"final-article.html\""))
        assertTrue(pipelineSummary.contains("\"refined/final-article-refined.html\""))
        assertTrue(pipelineSummary.contains("\"assets/images/cli-pipeline-default-hero.png\""))
        assertTrue(pipelineSummary.contains("\"assets/images/cli-pipeline-default-fast-follow-up-hero.png\""))

        val pipelineEntries = unzipEntries(runOutputDir.resolve("pipeline-results.zip").readBytes())
        assertTrue(pipelineEntries.keys.contains("outputs/final-article.html"))
        assertTrue(pipelineEntries.keys.contains("outputs/refined/final-article-refined.html"))
        assertTrue(pipelineEntries.keys.contains("outputs/assets/images/cli-pipeline-default-hero.png"))
        assertTrue(pipelineEntries.keys.contains("outputs/assets/images/cli-pipeline-default-fast-follow-up-hero.png"))
        assertTrue(pipelineEntries.keys.contains("pipeline-summary.json"))
    }

    @Test
    fun `track three publication objective and submission stage request authored html outputs`() = runBlocking {
        val inputDir = createTempDirectory("cli-pipeline-track3-input-").toFile().apply {
            resolve("track3.md").writeText("Track 3 Agent Society application vector")
        }
        val outputDir = createTempDirectory("cli-pipeline-track3-output-").toFile()
        val pipelineFile = createTempDirectory("cli-pipeline-track3-").toFile().resolve("pipeline.json").apply {
            writeText(
                """
                {
                  "stages": [
                    {
                      "id": "submission-brief",
                      "prompt": "Produce a polished browser-readable submission brief for Qwen Cloud Hackathon Track 3.",
                      "fast": true,
                      "rounds": 1,
                      "finalOutputKind": "HTML_ARTICLE",
                      "finalOutput": {
                        "relativePath": "final-article.html",
                        "mediaType": "text/html",
                        "description": "Publication-ready submission brief",
                        "kind": "HTML_ARTICLE"
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
                prompt = "The final artifacts must be publication-ready strategy and submission-brief documents for Qwen Cloud Hackathon Track 3.",
                inputDir = inputDir.toPath(),
                outputDir = outputDir.toPath(),
                openRouterApiKey = "sk-test",
                modelOverrides = emptyMap(),
                debug = true,
                fast = true,
                rounds = 3,
                runId = "cli-pipeline-track3",
                pipelineConfigPath = pipelineFile.toPath()
            ),
            runtimeFactory = { _, _ -> CliRuntime(fakeEngine) }
        )

        assertTrue(result.completedSuccessfully)
        assertEquals(
            listOf("cli-pipeline-track3", "cli-pipeline-track3-submission-brief"),
            fakeEngine.capturedConfigs.map { it.runId }
        )

        val primaryConfig = assertNotNull(fakeEngine.capturedConfigs.firstOrNull())
        val submissionConfig = assertNotNull(fakeEngine.capturedConfigs.getOrNull(1))
        assertEquals("final-article.html", primaryConfig.finalOutputSpec?.relativePath)
        assertEquals("text/html", primaryConfig.finalOutputSpec?.mediaType)
        assertEquals(FinalOutputKind.HTML_ARTICLE, primaryConfig.finalOutputSpec?.kind)
        assertEquals("final-article.html", submissionConfig.finalOutputSpec?.relativePath)
        assertEquals("text/html", submissionConfig.finalOutputSpec?.mediaType)
        assertEquals(FinalOutputKind.HTML_ARTICLE, submissionConfig.finalOutputSpec?.kind)

        val runOutputDir = outputDir.resolve("cli-pipeline-track3")
        assertTrue(runOutputDir.resolve("outputs/final-article.html").exists())
        assertTrue(runOutputDir.resolve("pipeline-summary.json").readText().contains("\"submission-brief\""))
    }

    @Test
    fun `pipeline primary contract publishes required output without an extra stage`() = runBlocking {
        val inputDir = createTempDirectory("cli-pipeline-primary-input-").toFile().apply {
            resolve("brief.txt").writeText("single-stage audit brief")
        }
        val outputDir = createTempDirectory("cli-pipeline-primary-output-").toFile()
        val pipelineFile = createTempDirectory("cli-pipeline-primary-").toFile().resolve("pipeline.json").apply {
            writeText(
                """
                {
                  "primary": {
                    "id": "audit-plan",
                    "prompt": "Produce the required audit plan.",
                    "fast": true,
                    "rounds": 3,
                    "groupCount": 2,
                    "maxAgentsPerGroup": 4,
                    "budgetOverride": 180,
                    "finalOutput": {
                      "relativePath": "audit-plan.md",
                      "mediaType": "text/markdown",
                      "description": "Operator-facing audit plan",
                      "kind": "GENERIC_FILE",
                      "validation": {
                        "requiredMarkdownSectionSignals": ["Executive Summary"]
                      }
                    }
                  },
                  "stages": []
                }
                """.trimIndent()
            )
        }
        val fakeEngine = FakeCliPipelineEngineService()

        val result = runCli(
            options = CliOptions(
                prompt = "CLI fallback prompt",
                inputDir = inputDir.toPath(),
                outputDir = outputDir.toPath(),
                openRouterApiKey = "sk-test",
                modelOverrides = emptyMap(),
                debug = true,
                fast = false,
                rounds = null,
                maxRounds = 9,
                runId = "cli-pipeline-primary",
                pipelineConfigPath = pipelineFile.toPath()
            ),
            runtimeFactory = { _, _ -> CliRuntime(fakeEngine) }
        )

        assertTrue(result.completedSuccessfully)
        assertEquals(listOf("cli-pipeline-primary"), fakeEngine.capturedConfigs.map { it.runId })
        val primaryConfig = assertNotNull(fakeEngine.capturedConfigs.singleOrNull())
        assertEquals(3, primaryConfig.rounds)
        assertNull(primaryConfig.autoCompletionPolicy)
        assertEquals(2, primaryConfig.groupCount)
        assertEquals(4, primaryConfig.maxAgentsPerGroup)
        assertEquals(180.0, primaryConfig.budgetOverride)
        assertEquals("audit-plan.md", primaryConfig.finalOutputSpec?.relativePath)
        assertEquals(
            listOf("Executive Summary"),
            primaryConfig.finalOutputSpec?.validation?.requiredMarkdownSectionSignals
        )
        val runRoot = outputDir.resolve("cli-pipeline-primary")
        assertTrue(runRoot.resolve("outputs/audit-plan.md").exists())
        val summary = runRoot.resolve("pipeline-summary.json").readText()
        assertTrue("\"stageId\": \"primary\"" in summary)
        kotlin.test.assertFalse("\"stages/audit-plan\"" in summary)
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
                        "description": "Localized summary brief",
                        "validation": {
                          "requiredJsonRootKeys": ["runId"]
                        }
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
        assertEquals(
            listOf("runId"),
            configuredStage.finalOutputSpec?.validation?.requiredJsonRootKeys
        )

        val configuredStageArtifacts = configuredStage.contextArtifacts.map { it.relativePath }.toSet()
        assertTrue("previous-stage/results-bundle/outputs/final-article.html" in configuredStageArtifacts)

        val runOutputDir = outputDir.resolve("cli-pipeline-config")
        val jsonOutput = runOutputDir.resolve("outputs/briefs/summary.json")
        assertTrue(jsonOutput.exists())
        assertTrue(jsonOutput.readText().contains("\"runId\":\"cli-pipeline-config-brief-json\""))
        assertTrue(runOutputDir.resolve("pipeline-summary.json").readText().contains("\"briefs/summary.json\""))
    }

    @Test
    fun `pipeline config propagates explicit acceptance authority to run config`() = runBlocking {
        val inputDir = createTempDirectory("cli-pipeline-acceptance-input-").toFile().apply {
            resolve("brief.txt").writeText("acceptance fixture")
        }
        val outputDir = createTempDirectory("cli-pipeline-acceptance-output-").toFile()
        val pipelineFile = createTempDirectory("cli-pipeline-acceptance-").toFile()
            .resolve("pipeline.json")
            .apply {
                writeText(
                    """
                    {
                      "primary": {
                        "id": "appendix",
                        "prompt": "Produce a validated final report.",
                        "fast": true,
                        "rounds": 6,
                        "groupCount": 2,
                        "councilExecutionPurpose": "APPENDIX_B_ACCEPTANCE",
                        "acceptanceProfile": "appendix-b-full",
                        "finalOutput": {
                          "relativePath": "deliverable.md",
                          "mediaType": "text/markdown"
                        }
                      }
                    }
                    """.trimIndent()
                )
            }
        val fakeEngine = FakeCliPipelineEngineService()

        val result = runCli(
            options = CliOptions(
                prompt = "Produce the primary result.",
                inputDir = inputDir.toPath(),
                outputDir = outputDir.toPath(),
                openRouterApiKey = "/tmp/codex",
                llmBackend = LlmBackend.CODEX_CHATGPT,
                modelOverrides = emptyMap(),
                debug = false,
                fast = false,
                rounds = 1,
                runId = "cli-pipeline-acceptance",
                pipelineConfigPath = pipelineFile.toPath()
            ),
            runtimeFactory = { _, _ -> CliRuntime(fakeEngine) },
            acceptanceAdmissionController = AcceptanceAdmissionController.NONE
        )

        assertTrue(result.completedSuccessfully)
        val configuredStage = assertNotNull(fakeEngine.capturedConfigs.singleOrNull())
        assertEquals(
            CouncilExecutionPurpose.APPENDIX_B_ACCEPTANCE,
            configuredStage.councilExecutionPurpose
        )
        assertEquals(AcceptanceProfile.APPENDIX_B_FULL, configuredStage.acceptanceProfile)
        assertEquals(6, configuredStage.rounds)
        assertEquals(2, configuredStage.groupCount)
        assertEquals("deliverable.md", configuredStage.finalOutputSpec?.relativePath)
        assertEquals("text/markdown", configuredStage.finalOutputSpec?.mediaType)
    }

    @Test
    fun `acceptance admission record does not turn a fresh run into a persisted resume`() = runBlocking {
        val inputDir = createTempDirectory("cli-fresh-acceptance-input-").toFile()
        val outputDir = createTempDirectory("cli-fresh-acceptance-output-").toFile()
        val runId = "cli-fresh-acceptance"
        val pipelineFile = createTempDirectory("cli-fresh-acceptance-pipeline-").toFile()
            .resolve("pipeline.json")
            .apply {
                writeText(
                    """
                    {
                      "primary": {
                        "id": "acceptance-smoke",
                        "prompt": "Produce the receiver-facing smoke report.",
                        "fast": true,
                        "rounds": 1,
                        "groupCount": 2,
                        "councilExecutionPurpose": "APPENDIX_B_ACCEPTANCE",
                        "acceptanceProfile": "runtime-usability-smoke",
                        "finalOutput": {
                          "relativePath": "receiver/smoke.md",
                          "mediaType": "text/markdown"
                        }
                      }
                    }
                    """.trimIndent()
                )
            }
        val fakeEngine = FakeCliPipelineEngineService()
        val acceptanceLease = FakeAcceptanceRunLease(runId)

        val result = runCli(
            options = CliOptions(
                prompt = "The pipeline prompt is authoritative.",
                inputDir = inputDir.toPath(),
                outputDir = outputDir.toPath(),
                openRouterApiKey = "/tmp/codex",
                llmBackend = LlmBackend.CODEX_CHATGPT,
                modelOverrides = emptyMap(),
                debug = false,
                fast = false,
                rounds = 1,
                runId = runId,
                pipelineConfigPath = pipelineFile.toPath()
            ),
            runtimeFactory = { _, _ -> CliRuntime(fakeEngine) },
            acceptanceAdmissionController = AcceptanceAdmissionController { _, _, _ ->
                acceptanceLease
            }
        )

        assertTrue(result.completedSuccessfully)
        assertTrue(fakeEngine.startCalled)
        assertFalse(fakeEngine.resumeCalled)
        assertTrue(acceptanceLease.closed)
        assertTrue(
            outputDir.resolve(
                "$runId/.evomolior-data/evomolior/runs/$runId/control/" +
                    AcceptanceAdmissionArtifact.FILE_NAME
            ).exists()
        )
    }

    @Test
    fun `invalid pipeline acceptance stages reject before runtime creation`() = runBlocking {
        data class InvalidAcceptanceStage(
            val id: String,
            val stageJson: String,
            val expectedMessage: String,
            val llmBackend: LlmBackend = LlmBackend.CODEX_CHATGPT
        )

        val cases = listOf(
            InvalidAcceptanceStage(
                id = "purpose",
                stageJson = """
                    {
                      "id": "acceptance",
                      "prompt": "Produce a validated report.",
                      "rounds": 1,
                      "groupCount": 2,
                      "councilExecutionPurpose": "STANDARD",
                      "acceptanceProfile": "runtime-usability-smoke",
                      "finalOutput": {
                        "relativePath": "report.md",
                        "mediaType": "text/markdown"
                      }
                    }
                """.trimIndent(),
                expectedMessage = "requires councilExecutionPurpose=appendix-b-acceptance"
            ),
            InvalidAcceptanceStage(
                id = "backend",
                stageJson = """
                    {
                      "id": "acceptance",
                      "prompt": "Produce a validated report.",
                      "rounds": 1,
                      "groupCount": 2,
                      "councilExecutionPurpose": "APPENDIX_B_ACCEPTANCE",
                      "acceptanceProfile": "runtime-usability-smoke",
                      "finalOutput": {
                        "relativePath": "report.md",
                        "mediaType": "text/markdown"
                      }
                    }
                """.trimIndent(),
                expectedMessage = "requires the codex-chatgpt backend",
                llmBackend = LlmBackend.OPENROUTER
            ),
            InvalidAcceptanceStage(
                id = "horizon",
                stageJson = """
                    {
                      "id": "acceptance",
                      "prompt": "Produce a validated report.",
                      "rounds": 5,
                      "groupCount": 2,
                      "councilExecutionPurpose": "APPENDIX_B_ACCEPTANCE",
                      "acceptanceProfile": "appendix-b-full",
                      "finalOutput": {
                        "relativePath": "report.md",
                        "mediaType": "text/markdown"
                      }
                    }
                """.trimIndent(),
                expectedMessage = "requires exactly 6 rounds"
            ),
            InvalidAcceptanceStage(
                id = "output",
                stageJson = """
                    {
                      "id": "acceptance",
                      "prompt": "Produce a validated report.",
                      "rounds": 1,
                      "groupCount": 2,
                      "councilExecutionPurpose": "APPENDIX_B_ACCEPTANCE",
                      "acceptanceProfile": "runtime-usability-smoke"
                    }
                """.trimIndent(),
                expectedMessage = "requires an explicit finalOutput"
            ),
            InvalidAcceptanceStage(
                id = "unsafe-output-path",
                stageJson = """
                    {
                      "id": "acceptance",
                      "prompt": "Produce a validated report.",
                      "rounds": 1,
                      "groupCount": 2,
                      "councilExecutionPurpose": "APPENDIX_B_ACCEPTANCE",
                      "acceptanceProfile": "runtime-usability-smoke",
                      "finalOutput": {
                        "relativePath": "../report.md",
                        "mediaType": "text/markdown"
                      }
                    }
                """.trimIndent(),
                expectedMessage = "requires a safe receiver-facing output path"
            ),
            InvalidAcceptanceStage(
                id = "binary-output",
                stageJson = """
                    {
                      "id": "acceptance",
                      "prompt": "Produce a validated report.",
                      "rounds": 1,
                      "groupCount": 2,
                      "councilExecutionPurpose": "APPENDIX_B_ACCEPTANCE",
                      "acceptanceProfile": "runtime-usability-smoke",
                      "finalOutput": {
                        "relativePath": "report.bin",
                        "mediaType": "application/octet-stream"
                      }
                    }
                """.trimIndent(),
                expectedMessage = "requires a textual receiver-facing output"
            )
        )

        cases.forEach { case ->
            val inputDir = createTempDirectory("cli-pipeline-invalid-${case.id}-input-").toFile()
            val outputDir = createTempDirectory("cli-pipeline-invalid-${case.id}-output-").toFile()
            val pipelineFile = createTempDirectory("cli-pipeline-invalid-${case.id}-").toFile()
                .resolve("pipeline.json")
                .apply {
                    writeText("""{"stages":[${case.stageJson}]}""")
                }
            val runtimeFactoryCalls = AtomicInteger()

            val result = runCli(
                    options = CliOptions(
                        prompt = "Produce the primary result.",
                        inputDir = inputDir.toPath(),
                        outputDir = outputDir.toPath(),
                        openRouterApiKey = if (case.llmBackend == LlmBackend.CODEX_CHATGPT) {
                            "/tmp/codex"
                        } else {
                            "sk-test"
                        },
                        llmBackend = case.llmBackend,
                        modelOverrides = emptyMap(),
                        debug = false,
                        fast = false,
                        rounds = 1,
                        runId = "cli-pipeline-invalid-${case.id}",
                        pipelineConfigPath = pipelineFile.toPath()
                    ),
                    runtimeFactory = { _, _ ->
                        runtimeFactoryCalls.incrementAndGet()
                        error("runtime must not be created")
                    }
                )

            assertFalse(result.completedSuccessfully)
            assertTrue(
                outputDir.resolve("cli-pipeline-invalid-${case.id}/startup-rejection.json")
                    .readText()
                    .contains(case.expectedMessage)
            )
            assertEquals(0, runtimeFactoryCalls.get())
        }
    }

    @Test
    fun `primary acceptance profile does not treat inferred output as explicit`() = runBlocking {
        val inputDir = createTempDirectory("cli-pipeline-primary-acceptance-input-").toFile()
        val outputDir = createTempDirectory("cli-pipeline-primary-acceptance-output-").toFile()
        val pipelineFile = createTempDirectory("cli-pipeline-primary-acceptance-").toFile()
            .resolve("pipeline.json")
            .apply {
                writeText(
                    """
                    {
                      "primary": {
                        "id": "primary",
                        "prompt": "Produce a receiver-facing report.",
                        "rounds": 1,
                        "groupCount": 2,
                        "councilExecutionPurpose": "APPENDIX_B_ACCEPTANCE",
                        "acceptanceProfile": "runtime-usability-smoke"
                      }
                    }
                    """.trimIndent()
                )
            }
        val runtimeFactoryCalls = AtomicInteger()

        val result = runCli(
                options = CliOptions(
                    prompt = "Fallback prompt must not authorize an inferred output.",
                    inputDir = inputDir.toPath(),
                    outputDir = outputDir.toPath(),
                    openRouterApiKey = "/tmp/codex",
                    llmBackend = LlmBackend.CODEX_CHATGPT,
                    modelOverrides = emptyMap(),
                    debug = false,
                    fast = false,
                    rounds = 1,
                    runId = "cli-pipeline-primary-acceptance",
                    pipelineConfigPath = pipelineFile.toPath()
                ),
                runtimeFactory = { _, _ ->
                    runtimeFactoryCalls.incrementAndGet()
                    error("runtime must not be created")
                }
            )

        assertFalse(result.completedSuccessfully)
        assertTrue(
            outputDir.resolve("cli-pipeline-primary-acceptance/startup-rejection.json")
                .readText()
                .contains("requires an explicit finalOutput")
        )
        assertEquals(0, runtimeFactoryCalls.get())
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
                "-fast",
                "-rounds",
                "5"
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
                "-rounds",
                "5",
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
        assertEquals(4, capturedConfig.maxAgentsPerGroup)
        assertEquals(220.0, capturedConfig.budgetOverride)
        assertFalse(capturedConfig.isTestRun)
        assertEquals(
            browserArticleScoringDimensions().map { it.id },
            capturedConfig.scoringDimensions.map { it.id }
        )
    }

    @Test
    fun `fast competitive multi round run keeps complex worker capacity while using fast models`() = runBlocking {
        val inputDir = createTempDirectory("cli-fast-competitive-input-").toFile()
        val outputDir = createTempDirectory("cli-fast-competitive-output-").toFile()
        val fakeEngine = FakeCliEngineService()

        runCli(
            options = CliOptions(
                prompt = "Evaluate a competitive category with a matrix and final verdict.",
                inputDir = inputDir.toPath(),
                outputDir = outputDir.toPath(),
                openRouterApiKey = "sk-test",
                modelOverrides = emptyMap(),
                debug = true,
                fast = true,
                rounds = 10,
                groupCount = 5,
                runId = "cli-fast-competitive"
            ),
            runtimeFactory = { _, _ -> CliRuntime(fakeEngine) }
        )

        val capturedConfig = assertNotNull(fakeEngine.capturedConfig)
        assertEquals(5, capturedConfig.groupCount)
        assertEquals(10, capturedConfig.rounds)
        assertEquals(4, capturedConfig.maxAgentsPerGroup)
        assertTrue(capturedConfig.modelOverrides.isNotEmpty())
    }

    @Test
    fun `non fast competitive multi round run defaults to multi agent worker capacity`() = runBlocking {
        val inputDir = createTempDirectory("cli-nonfast-competitive-input-").toFile()
        val outputDir = createTempDirectory("cli-nonfast-competitive-output-").toFile()
        val fakeEngine = FakeCliEngineService()

        runCli(
            options = CliOptions(
                prompt = "Evaluate a competitive category with a matrix and final verdict.",
                inputDir = inputDir.toPath(),
                outputDir = outputDir.toPath(),
                openRouterApiKey = "sk-test",
                modelOverrides = emptyMap(),
                debug = true,
                fast = false,
                rounds = 10,
                groupCount = 5,
                runId = "cli-nonfast-competitive"
            ),
            runtimeFactory = { _, _ -> CliRuntime(fakeEngine) }
        )

        val capturedConfig = assertNotNull(fakeEngine.capturedConfig)
        assertEquals(5, capturedConfig.groupCount)
        assertEquals(10, capturedConfig.rounds)
        assertEquals(4, capturedConfig.maxAgentsPerGroup)
    }

    @Test
    fun `runCli resumes a persisted run when prior state exists but summary is missing`() = runBlocking {
        val inputDir = createTempDirectory("cli-resume-input-").toFile()
        val outputDir = createTempDirectory("cli-resume-output-").toFile()
        val fakeEngine = FakeCliResumeEngineService()
        val runOutputDir = outputDir.resolve("cli-resume")
        val dataRoot = runOutputDir.resolve(".evomolior-data").also { it.mkdirs() }
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
                    "outputs/assets/images/cli-reuse-hero.png" to "fake-reused-image-bytes",
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
        assertTrue(runOutputDir.resolve("outputs/assets/images/cli-reuse-hero.png").exists())
        assertTrue(runOutputDir.resolve("outputs/final-article.html").readText().contains("MindVortex reused article"))

        val pipelineEntries = unzipEntries(runOutputDir.resolve("pipeline-results.zip").readBytes())
        assertTrue(pipelineEntries.keys.contains("outputs/final-article.html"))
        assertTrue(pipelineEntries.keys.contains("outputs/assets/images/cli-reuse-hero.png"))
    }

    @Test
    fun `runCli publishes archived final article even without inferred final output spec`() = runBlocking {
        val inputDir = createTempDirectory("cli-archived-output-input-").toFile().apply {
            resolve("brief.txt").writeText("mindvortex brief")
        }
        val outputDir = createTempDirectory("cli-archived-output-").toFile()
        val runOutputDir = outputDir.resolve("cli-archived-output")
        runOutputDir.mkdirs()

        runOutputDir.resolve("summary.json").writeText(
            """
            {
              "runId": "cli-archived-output",
              "prompt": "Evaluate a category and produce the requested matrix and verdict.",
              "inputDirectory": "${inputDir.toPath()}",
              "inputArtifactCount": 1,
              "outputDirectory": "${runOutputDir.absolutePath}",
              "completedAt": "2026-06-30T22:00:00Z",
              "durationMs": 42,
              "debugLogPath": null,
              "resultsBundlePath": "${runOutputDir.resolve("results.zip").absolutePath}",
              "status": "COMPLETED",
              "winnerId": "group-1",
              "modelOverrides": {}
            }
            """.trimIndent()
        )
        runOutputDir.resolve("results.zip").writeBytes(
            buildZipBytes(
                mapOf(
                    "outputs/final-article.html" to "<!doctype html><html><body>Archived fallback article</body></html>",
                    "manifest.json" to """{"artifactCount":1}"""
                )
            )
        )

        val result = runCli(
            options = CliOptions(
                prompt = "Evaluate a category and produce the requested matrix and verdict.",
                inputDir = inputDir.toPath(),
                outputDir = outputDir.toPath(),
                openRouterApiKey = "sk-test",
                modelOverrides = emptyMap(),
                debug = true,
                fast = false,
                rounds = 1,
                runId = "cli-archived-output"
            ),
            runtimeFactory = { _, _ -> error("runtime should not be created for a reusable completed stage") }
        )

        assertTrue(result.completedSuccessfully)
        assertTrue(runOutputDir.resolve("outputs/final-article.html").exists())
        assertTrue(runOutputDir.resolve("outputs/final-article.html").readText().contains("Archived fallback article"))
        assertTrue(runOutputDir.resolve("pipeline-summary.json").readText().contains("\"final-article.html\""))
    }

    @Test
    fun `runCli does not publish archived asset paths outside outputs directory`() = runBlocking {
        val inputDir = createTempDirectory("cli-zip-slip-input-").toFile().apply {
            resolve("brief.txt").writeText("mindvortex brief")
        }
        val outputDir = createTempDirectory("cli-zip-slip-output-").toFile()
        val runOutputDir = outputDir.resolve("cli-zip-slip-output")
        runOutputDir.mkdirs()

        runOutputDir.resolve("summary.json").writeText(
            """
            {
              "runId": "cli-zip-slip-output",
              "prompt": "Evaluate a category and produce the requested matrix and verdict.",
              "inputDirectory": "${inputDir.toPath()}",
              "inputArtifactCount": 1,
              "outputDirectory": "${runOutputDir.absolutePath}",
              "completedAt": "2026-06-30T22:00:00Z",
              "durationMs": 42,
              "debugLogPath": null,
              "resultsBundlePath": "${runOutputDir.resolve("results.zip").absolutePath}",
              "status": "COMPLETED",
              "winnerId": "group-1",
              "modelOverrides": {}
            }
            """.trimIndent()
        )
        runOutputDir.resolve("results.zip").writeBytes(
            buildZipBytes(
                mapOf(
                    "outputs/final-article.html" to "<!doctype html><html><body>Archived fallback article</body></html>",
                    "outputs/assets/../../escaped.txt" to "escaped",
                    "manifest.json" to """{"artifactCount":2}"""
                )
            )
        )

        val result = runCli(
            options = CliOptions(
                prompt = "Evaluate a category and produce the requested matrix and verdict.",
                inputDir = inputDir.toPath(),
                outputDir = outputDir.toPath(),
                openRouterApiKey = "sk-test",
                modelOverrides = emptyMap(),
                debug = true,
                fast = false,
                rounds = 1,
                runId = "cli-zip-slip-output"
            ),
            runtimeFactory = { _, _ -> error("runtime should not be created for a reusable completed stage") }
        )

        assertTrue(result.completedSuccessfully)
        assertTrue(runOutputDir.resolve("outputs/final-article.html").exists())
        assertFalse(runOutputDir.resolve("escaped.txt").exists())
        assertFalse(outputDir.resolve("escaped.txt").exists())
    }

    @Test
    fun `pipeline bundle extraction rejects traversal before writing any entry`() {
        val root = createTempDirectory("cli-pipeline-unzip-root-")
        val archive = root.resolve("results.zip").toFile().apply {
            writeBytes(
                buildZipBytes(
                    linkedMapOf(
                        "safe/output.txt" to "safe",
                        "../escaped.txt" to "escaped"
                    )
                )
            )
        }
        val extractionRoot = root.resolve("expanded")

        val failure = assertFailsWith<IllegalArgumentException> {
            unzipPipelineBundle(archive, extractionRoot)
        }

        assertTrue(failure.message.orEmpty().contains("escapes extraction root"))
        assertFalse(root.resolve("escaped.txt").toFile().exists())
        assertFalse(extractionRoot.resolve("safe/output.txt").toFile().exists())
    }
}

private abstract class NoopOverseerEngineService : EvoMoliorEngineService {
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
    private val _events = MutableSharedFlow<EvoMoliorEvent>(replay = 2, extraBufferCapacity = 8)
    override val events: SharedFlow<EvoMoliorEvent> = _events.asSharedFlow()

    var capturedConfig: RunConfig? = null
    private var runCompleted = false

    override suspend fun startRun(config: RunConfig): String {
        capturedConfig = config
        _events.emit(EvoMoliorEvent.RunStarted(config.runId, config.objective))
        runCompleted = true
        _events.emit(EvoMoliorEvent.RunCompleted(config.runId, "group-0"))
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
    private val _events = MutableSharedFlow<EvoMoliorEvent>(replay = 2, extraBufferCapacity = 8)
    override val events: SharedFlow<EvoMoliorEvent> = _events.asSharedFlow()

    private var runCompletedEmitted = false
    private var capturedConfig: RunConfig? = null
    var resultsBundleRequestedAfterCompletion = false

    override suspend fun startRun(config: RunConfig): String {
        capturedConfig = config
        _events.emit(EvoMoliorEvent.RunStarted(config.runId, config.objective))
        runCompletedEmitted = true
        _events.emit(EvoMoliorEvent.RunCompleted(config.runId, "group-0"))
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
    private val _events = MutableSharedFlow<EvoMoliorEvent>(replay = 2, extraBufferCapacity = 8)
    override val events: SharedFlow<EvoMoliorEvent> = _events.asSharedFlow()

    val capturedConfigs = mutableListOf<RunConfig>()
    var startCalled = false
    var resumeCalled = false
    private val configsByRunId = linkedMapOf<String, RunConfig>()
    private val completedRuns = linkedSetOf<String>()

    override suspend fun startRun(config: RunConfig): String {
        startCalled = true
        capturedConfigs += config
        configsByRunId[config.runId] = config
        _events.emit(EvoMoliorEvent.RunStarted(config.runId, config.objective))
        completedRuns += config.runId
        _events.emit(EvoMoliorEvent.RunCompleted(config.runId, "group-0"))
        return config.runId
    }

    override suspend fun resumePersistedRun(config: RunConfig): String {
        resumeCalled = true
        capturedConfigs += config
        configsByRunId[config.runId] = config
        completedRuns += config.runId
        _events.emit(EvoMoliorEvent.RunCompleted(config.runId, "group-0"))
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
                val content = when {
                    spec.mediaType.startsWith("text/html") ->
                        "<!doctype html><html><body>${config.runId}</body></html>"
                    spec.mediaType.startsWith("text/markdown") ->
                        "# Executive Summary\n\nRun ${config.runId}"
                    else -> """{"runId":"${config.runId}","mediaType":"${spec.mediaType}"}"""
                }
                addEntry("outputs/${spec.relativePath}", content)
            }
            addEntry("outputs/assets/images/${config.runId}-hero.png", "fake-image-${config.runId}")
            addEntry("manifest.json", """{"runId":"${config.runId}","artifactCount":${config.contextArtifacts.size}}""")
        }
        return output.toByteArray()
    }
}

private class FakeAcceptanceRunLease(runId: String) : AcceptanceRunLeaseHandle {
    override val record = AcceptanceAdmissionArtifact(
        runId = runId,
        acceptanceProfile = AcceptanceProfile.RUNTIME_USABILITY_SMOKE,
        admittedAtUtc = "2026-07-20T00:00:00Z",
        ownerPid = 1L,
        distributionManifestSha256 = "a".repeat(64),
        sourceRevision = "test-revision",
        configurationSha256 = "b".repeat(64),
        modelAssignmentsSha256 = "c".repeat(64),
        jvmVersion = "17",
        codexCliPath = "/tmp/codex",
        codexCliSha256 = "d".repeat(64),
        codexCliVersion = "codex-cli test",
        estimatedMaximumRunArchiveBytes = 1L,
        requiredFreeBytes = 1L,
        checks = listOf(AcceptanceAdmissionCheck("test", "accepted"))
    )
    var closed = false

    override fun verifyStable(boundary: String) = Unit

    override fun verify(runId: String, boundary: AcceptanceRunBoundary) = Unit

    override fun close() {
        closed = true
    }
}

private class FakeCliBrokenOutputEngineService(
    private val outputEntries: Map<String, String>
) : NoopOverseerEngineService() {
    private val _events = MutableSharedFlow<EvoMoliorEvent>(replay = 2, extraBufferCapacity = 8)
    override val events: SharedFlow<EvoMoliorEvent> = _events.asSharedFlow()

    private val completedRuns = linkedSetOf<String>()
    private val configsByRunId = linkedMapOf<String, RunConfig>()

    override suspend fun startRun(config: RunConfig): String {
        configsByRunId[config.runId] = config
        _events.emit(EvoMoliorEvent.RunStarted(config.runId, config.objective))
        completedRuns += config.runId
        _events.emit(EvoMoliorEvent.RunCompleted(config.runId, "group-0"))
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
    private val _events = MutableSharedFlow<EvoMoliorEvent>(replay = 2, extraBufferCapacity = 8)
    override val events: SharedFlow<EvoMoliorEvent> = _events.asSharedFlow()

    var startCalled = false
    var resumeCalled = false

    override suspend fun startRun(config: RunConfig): String {
        startCalled = true
        _events.emit(EvoMoliorEvent.RunStarted(config.runId, config.objective))
        _events.emit(EvoMoliorEvent.RunCompleted(config.runId, "group-0"))
        return config.runId
    }

    override suspend fun resumePersistedRun(config: RunConfig): String {
        resumeCalled = true
        _events.emit(EvoMoliorEvent.EngineProgress(config.runId, "recovery_start", "Recovering"))
        _events.emit(EvoMoliorEvent.RunCompleted(config.runId, "group-0"))
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
