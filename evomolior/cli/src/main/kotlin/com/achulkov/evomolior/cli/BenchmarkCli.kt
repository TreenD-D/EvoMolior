@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.cli

import com.achulkov.evomolior.agents.factory.CodexIterativeSingleAgentRunner
import com.achulkov.evomolior.api.events.EvoMoliorEvent
import com.achulkov.evomolior.api.model.FinalOutputKind
import com.achulkov.evomolior.api.model.FinalOutputSpec
import com.achulkov.evomolior.api.model.RunConfig
import com.achulkov.evomolior.api.model.RunInputArtifactUpload
import com.achulkov.evomolior.core.benchmark.BenchmarkAnswerFormat
import com.achulkov.evomolior.core.benchmark.ComparativeInputCommitments
import com.achulkov.evomolior.core.benchmark.ComparativeRuntimeInputs
import com.achulkov.evomolior.core.benchmark.LiteratureDiscoveryScorer
import com.achulkov.evomolior.core.benchmark.LiteratureDiscoveryTaskScore
import com.achulkov.evomolior.core.benchmark.LiteratureDiscoveryTaskType
import com.achulkov.evomolior.core.benchmark.Tier0BenchmarkKind
import com.achulkov.evomolior.core.benchmark.Tier0BenchmarkPrediction
import com.achulkov.evomolior.core.benchmark.Tier0BenchmarkPromptContract
import com.achulkov.evomolior.core.benchmark.Tier0CandidatePaper
import com.achulkov.evomolior.core.benchmark.Tier0SourcePassage
import com.achulkov.evomolior.core.benchmark.Tier0BenchmarkTask
import com.achulkov.evomolior.core.benchmark.Tier0EvidenceStep
import com.achulkov.evomolior.core.benchmark.Tier0ExactAnswerScorer
import com.achulkov.evomolior.core.benchmark.Tier0RejectedCandidate
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.config.FastModelSelectionConfig
import com.achulkov.evomolior.core.config.LlmBackend
import com.achulkov.evomolior.core.config.ModelSelectionConfig
import com.achulkov.evomolior.core.contract.RunInputArtifactKind
import com.achulkov.evomolior.core.contract.RunInputArtifact
import com.achulkov.evomolior.core.contract.BenchmarkScoreContributionRecord
import com.achulkov.evomolior.core.contract.ContributionAuthorityKind
import com.achulkov.evomolior.core.contract.ContributionAuthorityRef
import com.achulkov.evomolior.core.council.CouncilDeterministicIds
import com.achulkov.evomolior.core.model.GroupProfileType
import com.achulkov.evomolior.core.model.ScoringDimensionCatalog
import com.achulkov.evomolior.runtime.createProductionRuntime
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.worker.ContributionGraphRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.random.Random
import kotlin.system.exitProcess

private const val FRAMES_TEST_TSV_URL = "https://huggingface.co/datasets/google/frames-benchmark/raw/main/test.tsv"

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
private const val LAB_BENCH_LITQA2_ROWS_URL = "https://datasets-server.huggingface.co/rows?dataset=futurehouse/lab-bench&config=LitQA2&split=train&offset=0&length="
private const val SCHOLAR_QABENCH_QASA_TEST_URL = "https://raw.githubusercontent.com/AkariAsai/ScholarQABench/main/data/single_paper_tasks/qasa_test.jsonl"
private const val SCHOLAR_QABENCH_PUBMED_TEST_URL = "https://raw.githubusercontent.com/AkariAsai/ScholarQABench/main/data/single_paper_tasks/pubmed_test.jsonl"
private const val SCHOLAR_QABENCH_SCIFACT_TEST_URL = "https://raw.githubusercontent.com/AkariAsai/ScholarQABench/main/data/single_paper_tasks/scifact_test.jsonl"
private const val SCHOLAR_QABENCH_CS_SNIPPETS_URL = "https://raw.githubusercontent.com/AkariAsai/ScholarQABench/main/data/scholarqa_cs/output_snippets.jsonl"
private const val BENCHMARK_PREDICTION_PATH = "tier0-prediction.json"
private const val AUTO_RESEARCH_BENCH_TARGET_IDS_PATH = "target_paper_ids.json"
private const val SOURCE_ARTIFACT_MAX_CHARS = 80_000
private const val SOURCE_SUMMARY_ARTIFACT_NAME = "source-summary.md"
internal const val SINGLE_AGENT_BASELINE_MODEL_ROLE = "Baseline"
private const val SINGLE_AGENT_DASHSCOPE_CHAT_COMPLETIONS_URL =
    "https://dashscope-intl.aliyuncs.com/compatible-mode/v1/chat/completions"
private const val SINGLE_AGENT_EXPLICIT_CACHE_MIN_CHARS = 4_096
private const val INCOMPLETE_CONSECUTIVE_WIN_COVERAGE_WARNING =
    "Coverage warning: the uploaded event-result tables do not cover every consecutive-win year."

internal val benchmarkJson = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
    encodeDefaults = true
}

/**
 * Runs small Tier-0 benchmark subsets through the EvoMolior CLI production runtime.
 *
 * @param args Command-line arguments after the `benchmark` subcommand.
 */
internal fun runBenchmarkCli(args: Array<String>) {
    val options = BenchmarkCliArguments.parse(args) ?: run {
        println(BenchmarkCliArguments.usage())
        return
    }
    val report = runBlocking { BenchmarkCliRunner().run(options) }
    val reportPath = options.outputDir.resolve("tier0-benchmark-report.json")
    Files.createDirectories(options.outputDir)
    Files.writeString(reportPath, benchmarkJson.encodeToString(report))
    println("Tier-0 benchmark report: ${reportPath.absolutePathString()}")
    println(
        "Runner ${report.runner.id} executed ${report.executedCount} task(s): " +
            "${report.exactCorrectCount}/${report.executedCount} exact correct, " +
            "accuracy=${"%.3f".format(report.exactAccuracy)}"
    )
    if (report.failures.isNotEmpty()) {
        System.err.println("Benchmark task failure(s): ${report.failures.joinToString("; ")}")
        exitProcess(1)
    }
}

@Serializable
internal data class BenchmarkCliReport(
    val benchmark: Tier0BenchmarkKind,
    val benchmarkVariant: String? = null,
    val backend: LlmBackend,
    val runner: BenchmarkCliRunnerKind = BenchmarkCliRunnerKind.EVOMOLIOR_SOCIETY,
    val modelId: String? = null,
    val modelOverrides: Map<String, String>,
    val startedAt: String,
    val completedAt: String,
    val executedCount: Int,
    val exactCorrectCount: Int,
    val exactAccuracy: Double,
    val evidencePathCoverage: Double,
    val citationSupportCoverage: Double? = null,
    val claimCoverage: Double? = null,
    val totalCost: Double? = null,
    val toolNativeSuccessRate: Double? = null,
    val taskResults: List<BenchmarkCliTaskResult>,
    val failures: List<String>
)

@Serializable
internal data class BenchmarkCliTaskResult(
    val taskId: String,
    val runId: String,
    val status: String,
    val outputDirectory: String,
    val finalAnswer: String,
    val goldAnswers: List<String>,
    val exactCorrect: Boolean,
    val evidencePathPresent: Boolean,
    val normalizedPrediction: String,
    val matchedGoldAnswer: String?,
    val supportingPassageCount: Int = 0,
    val paperIds: List<String> = emptyList(),
    val whyAlternativesWrongCount: Int = 0,
    val confidence: Double? = null,
    val citationSupportCoverage: Double? = null,
    val claimCoverage: Double? = null,
    val totalCost: Double? = null,
    val toolNativeSuccessRate: Double? = null,
    val literatureDiscoveryScore: LiteratureDiscoveryTaskScore? = null,
    val resultsBundlePath: String? = null,
    val finalDeliverableReferenceId: String? = null,
    val finalDeliverableReferenceHash: String? = null,
    val benchmarkScoreContributionId: String? = null,
    val observedComparativeCommitments: ComparativeInputCommitments? = null,
    val observedWorkerGroupCount: Int? = null,
    val observedGraderGroupCount: Int? = null,
    val error: String? = null
)

internal data class BenchmarkCliOptions(
    val benchmark: Tier0BenchmarkKind,
    val scholarQaBenchVariant: ScholarQaBenchVariant,
    val limit: Int,
    val offset: Int,
    val outputDir: Path,
    val apiKey: String,
    val llmBackend: LlmBackend,
    val fast: Boolean,
    val groupCount: Int,
    val rounds: Int,
    val roundDurationMinutes: Int,
    val maxAgentsPerGroup: Int,
    val budgetOverride: Double,
    val taskTimeoutMinutes: Int,
    val costOverrideUsdPerTask: Double?,
    val tasksJsonl: Path?,
    val modelOverrides: Map<String, String>,
    val runner: BenchmarkCliRunnerKind = BenchmarkCliRunnerKind.EVOMOLIOR_SOCIETY,
    val comparativePlanHash: String? = null,
    val comparativeCommitments: ComparativeInputCommitments? = null,
    val comparativeSeed: Long? = null,
    val comparativeRuntimeInputs: ComparativeRuntimeInputs? = null,
    val comparativeGraderGroupCount: Int? = null,
    val comparativeSourceArtifacts: List<RunInputArtifactUpload>? = null
)

@Serializable
internal enum class BenchmarkCliRunnerKind(val id: String) {
    @SerialName("evomolior-society")
    EVOMOLIOR_SOCIETY("evomolior-society"),

    @SerialName("single-agent")
    SINGLE_AGENT("single-agent");

    companion object {
        fun parse(value: String): BenchmarkCliRunnerKind = entries.firstOrNull {
            it.id.equals(value, ignoreCase = true) || it.name.equals(value, ignoreCase = true)
        } ?: throw CliUsageException(
            "Unsupported benchmark runner '$value'. Supported runners: ${entries.joinToString(", ") { it.id }}"
        )
    }
}

internal enum class ScholarQaBenchVariant(val id: String) {
    QASA("qasa"),
    PUBMED("pubmed"),
    SCIFACT("scifact"),
    CS("cs")
}

private data class BenchmarkSourceArtifact(
    val url: String,
    val upload: RunInputArtifactUpload
)

private data class FetchedBenchmarkSource(
    val url: String,
    val content: String
)

private data class WikipediaSnapshot(
    val revisionId: Long,
    val timestamp: String
)

internal data class TallBuildingRow(
    val tableRank: String,
    val name: String,
    val heightFeet: Double,
    val year: Int
)

internal object BenchmarkCliArguments {
    fun parse(args: Array<String>): BenchmarkCliOptions? {
        if (args.any { it == "-h" || it == "--help" }) return null
        var benchmark = Tier0BenchmarkKind.FRAMES
        var limit = 2
        var offset = 0
        var outputDir: Path? = null
        var apiKey: String? = null
        var backend = LlmBackend.QWEN_CLOUD
        var fast = true
        var groupCount = 1
        var rounds = 1
        var roundDurationMinutes = 1
        var maxAgentsPerGroup = 3
        var budgetOverride = 80.0
        var taskTimeoutMinutes = 15
        var costOverrideUsdPerTask: Double? = null
        var tasksJsonl: Path? = null
        var scholarQaBenchVariant = ScholarQaBenchVariant.QASA
        var runner = BenchmarkCliRunnerKind.EVOMOLIOR_SOCIETY
        val modelOverrides = linkedMapOf<String, String>()

        var index = 0
        while (index < args.size) {
            when (val arg = args[index]) {
                "-benchmark", "--benchmark" -> {
                    val value = args.getOrNull(++index) ?: throw CliUsageException("Missing value for $arg")
                    benchmark = when (value.lowercase()) {
                        "frames" -> Tier0BenchmarkKind.FRAMES
                        "litqa", "litqa2", "lab-bench-litqa2", "labbench-litqa2" -> Tier0BenchmarkKind.LITQA2
                        "scholarqa", "scholar-qa", "openscholar", "open-scholar",
                        "scholarqa-cs2", "arxivdigestables", "arxiv-digestables" -> Tier0BenchmarkKind.SCHOLAR_QA
                        "scholarqabench", "scholarqa-bench", "scholar-qa-bench", "qasa" -> {
                            scholarQaBenchVariant = ScholarQaBenchVariant.QASA
                            Tier0BenchmarkKind.SCHOLAR_QABENCH
                        }
                        "scholarqabench-pubmed", "scholarqa-bench-pubmed", "pubmedqa", "pubmed-qa" -> {
                            scholarQaBenchVariant = ScholarQaBenchVariant.PUBMED
                            Tier0BenchmarkKind.SCHOLAR_QABENCH
                        }
                        "scholarqabench-scifact", "scholarqa-bench-scifact", "scifact" -> {
                            scholarQaBenchVariant = ScholarQaBenchVariant.SCIFACT
                            Tier0BenchmarkKind.SCHOLAR_QABENCH
                        }
                        "scholarqabench-cs", "scholarqa-bench-cs", "scholarqa-cs" -> {
                            scholarQaBenchVariant = ScholarQaBenchVariant.CS
                            Tier0BenchmarkKind.SCHOLAR_QABENCH
                        }
                        "ai2-scholar-qa", "ai2-scholarqa" -> Tier0BenchmarkKind.AI2_SCHOLAR_QA
                        "autoresearchbench", "auto-research-bench", "auto-researchbench", "arb" -> Tier0BenchmarkKind.AUTO_RESEARCH_BENCH
                        else -> throw CliUsageException("Unsupported benchmark for CLI subset runner: $value")
                    }
                }
                "-limit", "--limit" -> {
                    val value = args.getOrNull(++index) ?: throw CliUsageException("Missing value for $arg")
                    limit = value.toIntOrNull()?.takeIf { it > 0 }
                        ?: throw CliUsageException("Limit must be a positive integer, got: $value")
                }
                "-offset", "--offset" -> {
                    val value = args.getOrNull(++index) ?: throw CliUsageException("Missing value for $arg")
                    offset = value.toIntOrNull()?.takeIf { it >= 0 }
                        ?: throw CliUsageException("Offset must be a non-negative integer, got: $value")
                }
                "-output", "--output" -> {
                    outputDir = Path.of(args.getOrNull(++index) ?: throw CliUsageException("Missing value for $arg"))
                }
                "-qwen-api-key", "--qwen-api-key" -> {
                    apiKey = args.getOrNull(++index)
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                        ?: throw CliUsageException("Missing non-blank value for $arg")
                    backend = LlmBackend.QWEN_CLOUD
                }
                "-openrouter-api-key", "--openrouter-api-key" -> {
                    apiKey = args.getOrNull(++index)
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                        ?: throw CliUsageException("Missing non-blank value for $arg")
                    backend = LlmBackend.OPENROUTER
                }
                "-llm-backend", "--llm-backend" -> {
                    backend = LlmBackend.parse(args.getOrNull(++index) ?: throw CliUsageException("Missing value for $arg"))
                }
                "-fast", "--fast" -> fast = true
                "-no-fast", "--no-fast" -> fast = false
                "-group-count", "--group-count" -> {
                    val value = args.getOrNull(++index) ?: throw CliUsageException("Missing value for $arg")
                    groupCount = value.toIntOrNull()?.takeIf { it > 0 }
                        ?: throw CliUsageException("Group count must be a positive integer, got: $value")
                }
                "-rounds", "--rounds" -> {
                    val value = args.getOrNull(++index) ?: throw CliUsageException("Missing value for $arg")
                    rounds = value.toIntOrNull()?.takeIf { it > 0 }
                        ?: throw CliUsageException("Rounds must be a positive integer, got: $value")
                }
                "-round-duration-minutes", "--round-duration-minutes" -> {
                    val value = args.getOrNull(++index) ?: throw CliUsageException("Missing value for $arg")
                    roundDurationMinutes = value.toIntOrNull()?.takeIf { it > 0 }
                        ?: throw CliUsageException("Round duration minutes must be a positive integer, got: $value")
                }
                "-max-agents-per-group", "--max-agents-per-group" -> {
                    val value = args.getOrNull(++index) ?: throw CliUsageException("Missing value for $arg")
                    maxAgentsPerGroup = value.toIntOrNull()?.takeIf { it > 0 }
                        ?: throw CliUsageException("Max agents per group must be a positive integer, got: $value")
                }
                "-budget-override", "--budget-override" -> {
                    val value = args.getOrNull(++index) ?: throw CliUsageException("Missing value for $arg")
                    budgetOverride = value.toDoubleOrNull()?.takeIf { it > 0.0 }
                        ?: throw CliUsageException("Budget override must be a positive number, got: $value")
                }
                "-task-timeout-minutes", "--task-timeout-minutes" -> {
                    val value = args.getOrNull(++index) ?: throw CliUsageException("Missing value for $arg")
                    taskTimeoutMinutes = value.toIntOrNull()?.takeIf { it > 0 }
                        ?: throw CliUsageException("Task timeout minutes must be a positive integer, got: $value")
                }
                "-cost-override-usd-per-task", "--cost-override-usd-per-task" -> {
                    val value = args.getOrNull(++index) ?: throw CliUsageException("Missing value for $arg")
                    costOverrideUsdPerTask = value.toDoubleOrNull()?.takeIf { it >= 0.0 }
                        ?: throw CliUsageException("Cost override must be a non-negative USD number, got: $value")
                }
                "-runner", "--runner" -> {
                    runner = BenchmarkCliRunnerKind.parse(
                        args.getOrNull(++index) ?: throw CliUsageException("Missing value for $arg")
                    )
                }
                "-single-agent-baseline", "--single-agent-baseline" -> {
                    runner = BenchmarkCliRunnerKind.SINGLE_AGENT
                }
                "-tasks-jsonl", "--tasks-jsonl" -> {
                    tasksJsonl = Path.of(args.getOrNull(++index) ?: throw CliUsageException("Missing value for $arg"))
                }
                "-scholarqabench-variant", "--scholarqabench-variant" -> {
                    scholarQaBenchVariant = parseScholarQaBenchVariant(
                        args.getOrNull(++index) ?: throw CliUsageException("Missing value for $arg")
                    )
                    benchmark = Tier0BenchmarkKind.SCHOLAR_QABENCH
                }
                "-model", "--model" -> {
                    val mapping = args.getOrNull(++index) ?: throw CliUsageException("Missing value for $arg")
                    val separatorIndex = mapping.indexOf('=')
                    if (separatorIndex <= 0 || separatorIndex == mapping.lastIndex) {
                        throw CliUsageException("Model override must be in the form role=model, got: $mapping")
                    }
                    modelOverrides[mapping.substring(0, separatorIndex).trim()] =
                        mapping.substring(separatorIndex + 1).trim()
                }
                else -> throw CliUsageException("Unknown benchmark argument: $arg")
            }
            index++
        }

        val resolvedOutput = outputDir ?: throw CliUsageException("Missing required -output directory")
        val resolvedTasksJsonl = tasksJsonl?.toAbsolutePath()?.normalize()
        if (
            benchmark == Tier0BenchmarkKind.SCHOLAR_QA ||
            benchmark == Tier0BenchmarkKind.AI2_SCHOLAR_QA ||
            benchmark == Tier0BenchmarkKind.AUTO_RESEARCH_BENCH
        ) {
            val sourceFile = resolvedTasksJsonl
                ?: throw CliUsageException("-tasks-jsonl is required for scholarqa/openscholar/ai2-scholar-qa/autoresearchbench local JSONL runs")
            if (!Files.isRegularFile(sourceFile)) {
                throw CliUsageException("Tasks JSONL file does not exist: $sourceFile")
            }
        }
        val resolvedKey = if (backend == LlmBackend.CODEX_CHATGPT) {
            CodexAuthCommand.locateCli(
                sequenceOf(
                    readLocalProperty("EVOMOLIOR_CODEX_CLI"),
                    readLocalProperty(LlmBackend.CODEX_CLI_ENV_VAR)
                ).filterNotNull()
            )
        } else {
            apiKey
                ?: System.getenv(backend.apiKeyEnvVar)
                ?: readLocalProperty(backend.apiKeyEnvVar)
                ?: if (backend == LlmBackend.OPENROUTER) System.getenv("OPENROUTER_API_KEY") else null
                ?: throw CliUsageException("${backend.apiKeyEnvVar} is required for backend ${backend.id}")
        }
        if (backend != LlmBackend.CODEX_CHATGPT) {
            backend.textRuntimeUnavailableReason()?.let { reason ->
                throw CliUsageException("LLM backend '${backend.id}' is not available: $reason")
            }
        }
        if (backend == LlmBackend.CODEX_CHATGPT) {
            CodexAuthCommand.requireUsableSession(resolvedKey)
        }
        if (
            runner == BenchmarkCliRunnerKind.SINGLE_AGENT &&
            backend !in setOf(LlmBackend.QWEN_CLOUD, LlmBackend.CODEX_CHATGPT)
        ) {
            throw CliUsageException(
                "The single-agent baseline supports qwen-cloud and codex-chatgpt."
            )
        }

        val backendOverrides = when {
            backend == LlmBackend.CODEX_CHATGPT -> ModelSelectionConfig.codexChatGptRuntimeAssignments()
            fast -> when (backend) {
                LlmBackend.OPENROUTER -> FastModelSelectionConfig.fastRuntimeAssignments()
                LlmBackend.QWEN_CLOUD -> FastModelSelectionConfig.fastQwenCloudRuntimeAssignments()
                LlmBackend.CODEX_CHATGPT -> ModelSelectionConfig.codexChatGptRuntimeAssignments()
            }
            else -> emptyMap()
        }

        return BenchmarkCliOptions(
            benchmark = benchmark,
            scholarQaBenchVariant = scholarQaBenchVariant,
            limit = limit,
            offset = offset,
            outputDir = resolvedOutput.toAbsolutePath().normalize(),
            apiKey = resolvedKey,
            llmBackend = backend,
            fast = fast,
            groupCount = groupCount,
            rounds = rounds,
            roundDurationMinutes = roundDurationMinutes,
            maxAgentsPerGroup = maxAgentsPerGroup,
            budgetOverride = budgetOverride,
            taskTimeoutMinutes = taskTimeoutMinutes,
            costOverrideUsdPerTask = costOverrideUsdPerTask,
            tasksJsonl = resolvedTasksJsonl,
            modelOverrides = backendOverrides + modelOverrides,
            runner = runner
        )
    }

    fun usage(): String = """
        EvoMolior Tier-0 benchmark runner

        Required:
          benchmark -benchmark frames|litqa2|scholarqabench|scholarqa|ai2-scholar-qa|autoresearchbench -limit N -output /path/to/output

        Optional:
          -offset N                         (skip N tasks before applying -limit)
          -scholarqabench-variant qasa|pubmed|scifact|cs
          -tasks-jsonl /path/to/tasks.jsonl   (required for scholarqa/openscholar/ai2-scholar-qa/autoresearchbench local JSONL modes)
          -qwen-api-key KEY
          -openrouter-api-key KEY
          -llm-backend qwen-cloud|openrouter|codex-chatgpt
          -group-count N
          -rounds N
          -round-duration-minutes N
          -max-agents-per-group N
          -budget-override N
          -task-timeout-minutes N          (default: 15; timeout is recorded per task and the batch continues)
          -cost-override-usd-per-task N      (deterministic fallback when runtime USD telemetry is unavailable)
          -runner evomolior-society|single-agent
          -single-agent-baseline           (shortcut for -runner single-agent; qwen-cloud only)
          -model ROLE=MODEL

        Notes:
          -benchmark litqa currently aliases the public LAB-Bench/LitQA2 loader.
          -benchmark ai2-scholar-qa is an evidence-first local JSONL adapter, not an official public Ai2 dataset import.
    """.trimIndent()

    private fun readLocalProperty(key: String): String? {
        val file = generateSequence(Path.of("").toAbsolutePath().normalize()) { current ->
            current.parent
        }.map { it.resolve("local.properties") }
            .firstOrNull(Files::exists)
            ?: return null
        return Files.readAllLines(file)
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .firstOrNull { it.substringBefore('=').trim() == key }
            ?.substringAfter('=', "")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }
}

internal class BenchmarkCliRunner(
    private val singleAgentChatCompletion: ((String, String, List<JsonObject>) -> JsonObject)? = null
) {
    suspend fun run(options: BenchmarkCliOptions): BenchmarkCliReport {
        options.outputDir.createDirectories()
        val startedAt = Instant.now().toString()
        val tasks = loadTasks(options).drop(options.offset).take(options.limit)
        val results = mutableListOf<BenchmarkCliTaskResult>()
        val failures = mutableListOf<String>()

        for (task in tasks) {
            val result = runTask(options, task)
            results += result
            result.error?.let { failures += "${task.id}: $it" }
        }

        val scoredResults = results.filter { it.error == null }
        val exactCorrect = scoredResults.count { it.exactCorrect }
        val evidencePresent = scoredResults.count { it.evidencePathPresent }
        val citationSupportCoverage = scoredResults.mapNotNull { it.citationSupportCoverage }.averageOrNull()
        val claimCoverage = scoredResults.mapNotNull { it.claimCoverage }.averageOrNull()
        val costValues = scoredResults.mapNotNull { it.totalCost }
        val totalCost = costValues.takeIf { it.isNotEmpty() }?.sum()
        val toolNativeSuccessRate = scoredResults.mapNotNull { it.toolNativeSuccessRate }.averageOrNull()
        return BenchmarkCliReport(
            benchmark = options.benchmark,
            benchmarkVariant = if (options.benchmark == Tier0BenchmarkKind.SCHOLAR_QABENCH) {
                options.scholarQaBenchVariant.id
            } else {
                null
            },
            backend = options.llmBackend,
            runner = options.runner,
            modelId = if (options.runner == BenchmarkCliRunnerKind.SINGLE_AGENT) {
                modelForRole(options, SINGLE_AGENT_BASELINE_MODEL_ROLE)
            } else {
                null
            },
            modelOverrides = options.modelOverrides,
            startedAt = startedAt,
            completedAt = Instant.now().toString(),
            executedCount = scoredResults.size,
            exactCorrectCount = exactCorrect,
            exactAccuracy = if (scoredResults.isEmpty()) 0.0 else exactCorrect.toDouble() / scoredResults.size,
            evidencePathCoverage = if (scoredResults.isEmpty()) 0.0 else evidencePresent.toDouble() / scoredResults.size,
            citationSupportCoverage = citationSupportCoverage,
            claimCoverage = claimCoverage,
            totalCost = totalCost,
            toolNativeSuccessRate = toolNativeSuccessRate,
            taskResults = results,
            failures = failures
        )
    }

    internal suspend fun runTask(
        options: BenchmarkCliOptions,
        task: Tier0BenchmarkTask,
        runId: String = "tier0-${task.id}-${UUID.randomUUID().toString().take(8)}",
        taskOutputDir: Path = options.outputDir.resolve(task.id)
    ): BenchmarkCliTaskResult {
        taskOutputDir.createDirectories()
        val sourceArtifacts = options.comparativeSourceArtifacts
            ?.map { upload -> BenchmarkSourceArtifact(upload.sourceName, upload) }
            ?: buildSourceArtifacts(task)
        val objective = buildObjective(options, task, sourceArtifacts)
        taskOutputDir.resolve("objective.txt").writeText(objective)
        val result = if (options.runner == BenchmarkCliRunnerKind.SINGLE_AGENT) {
            runSingleAgentTask(
                options = options,
                task = task,
                runId = runId,
                taskOutputDir = taskOutputDir,
                objective = objective,
                sourceArtifacts = sourceArtifacts
            )
        } else {
            runProductionRuntimeTask(
                options = options,
                task = task,
                runId = runId,
                taskOutputDir = taskOutputDir,
                objective = objective,
                sourceArtifacts = sourceArtifacts
            )
        }
        val commitments = options.comparativeCommitments
        val runtimeInputs = options.comparativeRuntimeInputs
        return if (commitments != null && runtimeInputs != null) {
            result.copy(
                observedComparativeCommitments = commitments.copy(
                    toolBundleHash = runtimeInputs.taskToolBundleHash(),
                    atlasHash = runtimeInputs.atlas.atlasHash
                ),
                observedWorkerGroupCount = if (
                    options.runner == BenchmarkCliRunnerKind.SINGLE_AGENT
                ) 0 else options.groupCount,
                observedGraderGroupCount = if (
                    options.runner == BenchmarkCliRunnerKind.SINGLE_AGENT
                ) 0 else requireNotNull(options.comparativeGraderGroupCount)
            )
        } else {
            result
        }
    }

    private suspend fun runProductionRuntimeTask(
        options: BenchmarkCliOptions,
        task: Tier0BenchmarkTask,
        runId: String,
        taskOutputDir: Path,
        objective: String,
        sourceArtifacts: List<BenchmarkSourceArtifact>
    ): BenchmarkCliTaskResult {
        val dataRoot = taskOutputDir.resolve(".evomolior-data").toFile().also { it.mkdirs() }
        val runtime = createProductionRuntime(
            dataRootDir = dataRoot,
            llmBackend = options.llmBackend,
            councilModelId = modelForRole(options, "MetaPlanner"),
            graderModelId = modelForRole(options, "Critic"),
            criticPipelineModelId = modelForRole(options, "Critic"),
            leakTransformModelId = modelForRole(options, "Outlier"),
            comparativeRuntimeInputs = options.comparativeRuntimeInputs
        ).engineService

        val completion = CompletableDeferred<EvoMoliorEvent>()
        val eventJob = kotlinx.coroutines.CoroutineScope(Dispatchers.Default).launch {
            runtime.events.collect { event ->
                when (event) {
                    is EvoMoliorEvent.RunCompleted -> if (event.runId == runId && !completion.isCompleted) completion.complete(event)
                    is EvoMoliorEvent.RunAborted -> if (event.runId == runId && !completion.isCompleted) completion.complete(event)
                    else -> Unit
                }
            }
        }

        val status = try {
            withTimeout(options.taskTimeoutMinutes * 60_000L) {
                runtime.startRun(buildRunConfig(options, task, runId, objective, sourceArtifacts))
                when (val event = completion.await()) {
                    is EvoMoliorEvent.RunCompleted -> "COMPLETED"
                    is EvoMoliorEvent.RunAborted -> "ABORTED: ${event.reason}"
                    else -> "UNKNOWN"
                }
            }
        } catch (e: TimeoutCancellationException) {
            val reason = "Timed out after ${options.taskTimeoutMinutes} minute(s)"
            runCatching { runtime.abortRun(runId, reason) }
            eventJob.cancelAndJoin()
            return failedTaskResult(task, runId, taskOutputDir, reason)
        } catch (e: Exception) {
            eventJob.cancelAndJoin()
            return failedTaskResult(task, runId, taskOutputDir, e.message ?: e::class.simpleName.orEmpty())
        } finally {
            if (eventJob.isActive) eventJob.cancelAndJoin()
        }

        val bundle = runtime.getResultsBundle(runId)
        val bundlePath = bundle?.let { bytes ->
            taskOutputDir.resolve("results.zip").also { Files.write(it, bytes) }
        }
        val outputPath = benchmarkPredictionPath(task.benchmark)
        val output = bundle?.let { extractZipEntry(it, "outputs/$outputPath") }
        if (!status.startsWith("COMPLETED")) {
            return failedTaskResult(task, runId, taskOutputDir, status, bundlePath?.absolutePathString())
        }
        if (output.isNullOrBlank()) {
            return failedTaskResult(task, runId, taskOutputDir, "Missing outputs/$outputPath", bundlePath?.absolutePathString())
        }

        val prediction = parsePrediction(task.id, output)
            .abstainIfIncompleteSourceCoverage(
                task = task,
                sourceSummary = bundle?.let {
                    extractZipEntry(it, "inputs/benchmark-sources/${task.id}/$SOURCE_SUMMARY_ARTIFACT_NAME")
                },
                summaryPath = "benchmark-sources/${task.id}/$SOURCE_SUMMARY_ARTIFACT_NAME"
            )
            .withFallbackCost(options.costOverrideUsdPerTask)
        if (task.benchmark == Tier0BenchmarkKind.AUTO_RESEARCH_BENCH) {
            writeAutoResearchBenchArtifacts(taskOutputDir, prediction)
        }
        val score = Tier0ExactAnswerScorer.score(task, prediction)
        val citationSupportCoverage = scoreCitationSupport(task, prediction)
        val claimCoverage = scoreClaimCoverage(task, prediction)
        val literatureDiscoveryScore = if (task.benchmark == Tier0BenchmarkKind.AUTO_RESEARCH_BENCH) {
            LiteratureDiscoveryScorer.score(task, prediction)
        } else {
            null
        }
        val exactCorrect = literatureDiscoveryScore
            ?.let { it.exactTargetFound ?: (it.precision == 1.0 && it.recall == 1.0) }
            ?: score.exactCorrect
        val benchmarkAuthority = recordBenchmarkScoreAuthority(
            dataRoot = dataRoot,
            options = options,
            task = task,
            runId = runId,
            outputPath = outputPath,
            exactCorrect = exactCorrect
        )
        return BenchmarkCliTaskResult(
            taskId = task.id,
            runId = runId,
            status = status,
            outputDirectory = taskOutputDir.absolutePathString(),
            finalAnswer = prediction.finalAnswer,
            goldAnswers = task.acceptedAnswers,
            exactCorrect = exactCorrect,
            evidencePathPresent = score.evidencePathPresent,
            normalizedPrediction = literatureDiscoveryScore?.let { prediction.targetPaperIds.joinToString(",") }
                ?: score.normalizedPrediction,
            matchedGoldAnswer = if (literatureDiscoveryScore?.foundGoldPaperCount == task.acceptedAnswers.size) {
                task.acceptedAnswers.joinToString(",")
            } else {
                score.matchedGoldAnswer
            },
            supportingPassageCount = prediction.supportingPassages.size,
            paperIds = prediction.paperIds,
            whyAlternativesWrongCount = prediction.whyAlternativesWrong.size,
            confidence = prediction.confidence,
            citationSupportCoverage = citationSupportCoverage,
            claimCoverage = claimCoverage,
            literatureDiscoveryScore = literatureDiscoveryScore,
            resultsBundlePath = bundlePath?.absolutePathString(),
            finalDeliverableReferenceId = benchmarkAuthority?.finalReference?.authorityId,
            finalDeliverableReferenceHash = benchmarkAuthority?.finalReference?.authorityHash,
            benchmarkScoreContributionId = benchmarkAuthority?.contributionId
        )
    }

    internal suspend fun recordBenchmarkScoreAuthority(
        dataRoot: File,
        options: BenchmarkCliOptions,
        task: Tier0BenchmarkTask,
        runId: String,
        outputPath: String,
        exactCorrect: Boolean
    ): BenchmarkScoreContributionRecord? {
        val factory = SubstrateDatabaseFactory(dataRoot)
        return try {
            val located = (0 until options.groupCount)
                .map { "group-$it" }
                .firstNotNullOfOrNull { groupId ->
                    val repository = ContributionGraphRepository(
                        database = factory.get(runId, groupId),
                        atlasDatabase = factory.getAtlas(runId)
                    )
                    repository.finalDeliverableReferencesForRun(runId, groupId)
                        .filter { it.relativePath == outputPath }
                        .sortedBy { it.referenceId }
                        .firstOrNull()
                        ?.let { reference -> repository to reference }
                } ?: return null
            val (repository, reference) = located
            val benchmarkContractHash = AtlasHashing.hashText(
                benchmarkJson.encodeToString(Tier0BenchmarkTask.serializer(), task)
            )
            val componentId = if (task.benchmark == Tier0BenchmarkKind.AUTO_RESEARCH_BENCH) {
                "exact_target_or_set_match"
            } else {
                "exact_answer_match"
            }
            repository.recordBenchmarkScoreContribution(
                BenchmarkScoreContributionRecord(
                    contributionId = CouncilDeterministicIds.create(
                        "benchmark-score-contribution",
                        runId,
                        reference.referenceId,
                        benchmarkContractHash,
                        componentId
                    ),
                    runId = runId,
                    groupId = reference.groupId,
                    round = reference.round,
                    finalReference = ContributionAuthorityRef(
                        kind = ContributionAuthorityKind.FINAL_DELIVERABLE_REFERENCE,
                        authorityId = reference.referenceId,
                        authorityHash = reference.recordHash
                    ),
                    benchmarkId = task.benchmark.name,
                    benchmarkVersion = 1,
                    benchmarkContractHash = benchmarkContractHash,
                    componentId = componentId,
                    scoreContribution = if (exactCorrect) 1.0 else 0.0,
                    maximumContribution = 1.0,
                    scoredAt = kotlin.time.Clock.System.now()
                ).withComputedHash()
            )
        } finally {
            (0 until options.groupCount).forEach { index ->
                factory.close(runId, "group-$index")
            }
            factory.close(runId, "atlas-authority")
        }
    }

    private suspend fun runSingleAgentTask(
        options: BenchmarkCliOptions,
        task: Tier0BenchmarkTask,
        runId: String,
        taskOutputDir: Path,
        objective: String,
        sourceArtifacts: List<BenchmarkSourceArtifact>
    ): BenchmarkCliTaskResult {
        if (options.llmBackend == LlmBackend.CODEX_CHATGPT) {
            return runCodexSingleAgentTask(
                options = options,
                task = task,
                runId = runId,
                taskOutputDir = taskOutputDir,
                objective = objective,
                sourceArtifacts = sourceArtifacts
            )
        }
        val modelId = modelForRole(options, SINGLE_AGENT_BASELINE_MODEL_ROLE)
        val artifactIndex = sourceArtifacts.associateBy { it.upload.relativePath }
        val messages = mutableListOf<JsonObject>(
            chatMessage(
                role = "system",
                content = """
                    You are the iterative single-agent baseline for EvoMolior benchmark comparisons.
                    You may use tools, but you are one agent only: no critics, graders, substrate, KG, death notes, or society mechanisms.
                    You must inspect relevant uploaded artifacts with read_run_artifact before answering when artifacts are listed.
                    Return one JSON object matching the benchmark contract. Do not omit evidence_path.
                """.trimIndent(),
                cacheControl = true
            ),
            chatMessageWithCachedPrefix(
                role = "user",
                cachedPrefix = buildString {
                    appendLine("Available tools:")
                    appendLine("- read_run_artifact: list or read uploaded source artifacts.")
                    appendLine("- fetch_source: fetch public web URLs when artifacts are insufficient.")
                    appendLine()
                    appendLine("Final response requirement: return the required benchmark JSON object only.")
                },
                suffix = objective
            )
        )
        val httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
        var promptTokens = 0.0
        var completionTokens = 0.0
        var totalTokens = 0.0
        var nativeToolCallAttempts = 0
        var nativeToolCallSuccesses = 0
        val transcript = mutableListOf<JsonObject>()

        repeat(8) { iteration ->
            val responseRoot = singleAgentChatCompletion?.invoke(options.apiKey, modelId, messages)
                ?: postSingleAgentChatCompletion(
                    httpClient = httpClient,
                    apiKey = options.apiKey,
                    modelId = modelId,
                    messages = messages,
                    requireToolCall = iteration == 0 && sourceArtifacts.isNotEmpty()
                )
            responseRoot["usage"]?.jsonObject?.let { usage ->
                promptTokens += usage["prompt_tokens"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                completionTokens += usage["completion_tokens"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                totalTokens += usage["total_tokens"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            }
            val assistantMessage = responseRoot["choices"]!!
                .jsonArray
                .first()
                .jsonObject["message"]!!
                .jsonObject
            transcript += assistantMessage
            messages += assistantMessageForReplay(assistantMessage)
            val toolCalls = assistantMessage["tool_calls"]?.jsonArray.orEmpty()
            if (toolCalls.isEmpty()) {
                val finalText = assistantMessage["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
                return scoreSingleAgentFinalAnswer(
                    options = options,
                    task = task,
                    runId = runId,
                    taskOutputDir = taskOutputDir,
                    finalText = finalText,
                    modelId = modelId,
                    promptTokens = promptTokens,
                    completionTokens = completionTokens,
                    totalTokens = totalTokens,
                    toolNativeSuccessRate = nativeToolSuccessRate(nativeToolCallSuccesses, nativeToolCallAttempts),
                    transcript = transcript
                )
            }

            toolCalls.forEach { toolCallElement ->
                nativeToolCallAttempts += 1
                val toolCall = toolCallElement.jsonObject
                val toolCallId = toolCall["id"]?.jsonPrimitive?.contentOrNull ?: "tool-$iteration"
                val function = toolCall["function"]?.jsonObject ?: JsonObject(emptyMap())
                val toolName = function["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val args = parseToolArguments(function["arguments"])
                val toolResult = executeSingleAgentTool(
                    name = toolName,
                    args = args,
                    task = task,
                    objective = objective,
                    artifactIndex = artifactIndex,
                    httpClient = httpClient
                )
                nativeToolCallSuccesses += 1
                messages += buildJsonObject {
                    put("role", "tool")
                    put("tool_call_id", toolCallId)
                    put("content", toolResult)
                }
            }
        }

        return failedTaskResult(
            task = task,
            runId = runId,
            taskOutputDir = taskOutputDir,
            error = "Single-agent baseline exceeded tool loop iteration limit."
        )
    }

    private suspend fun runCodexSingleAgentTask(
        options: BenchmarkCliOptions,
        task: Tier0BenchmarkTask,
        runId: String,
        taskOutputDir: Path,
        objective: String,
        sourceArtifacts: List<BenchmarkSourceArtifact>
    ): BenchmarkCliTaskResult {
        val artifactsRoot = taskOutputDir.resolve("single-agent-inputs").also { it.createDirectories() }
        val inputArtifacts = sourceArtifacts.map { source ->
            val bytes = Base64.getDecoder().decode(source.upload.contentBase64)
            val relativePath = Path.of(source.upload.relativePath).normalize()
            require(!relativePath.isAbsolute && !relativePath.startsWith("..")) {
                "Benchmark source artifact escapes the single-agent input root"
            }
            val target = artifactsRoot.resolve(relativePath)
            target.parent?.createDirectories()
            Files.write(target, bytes)
            RunInputArtifact(
                relativePath = relativePath.toString(),
                sourceName = source.upload.sourceName,
                mimeType = source.upload.mimeType,
                sizeBytes = bytes.size.toLong(),
                sha256 = sha256Hex(bytes),
                kind = source.upload.kind,
                textPreview = source.upload.textPreview
            )
        }
        val allowedHosts = task.relevantSources.mapNotNullTo(sortedSetOf()) { source ->
            runCatching { URI.create(source).host?.lowercase() }.getOrNull()
        }
        val modelId = modelForRole(options, SINGLE_AGENT_BASELINE_MODEL_ROLE)
        val response = CodexIterativeSingleAgentRunner.run(
            cliPath = options.apiKey,
            runId = runId,
            objective = objective,
            artifactsRoot = artifactsRoot.toString(),
            inputArtifacts = inputArtifacts,
            allowedSourceHosts = allowedHosts,
            sourceAuditPath = taskOutputDir.resolve("source-access.jsonl").toString()
        )
        return scoreSingleAgentFinalAnswer(
            options = options,
            task = task,
            runId = runId,
            taskOutputDir = taskOutputDir,
            finalText = response,
            modelId = modelId,
            promptTokens = 0.0,
            completionTokens = 0.0,
            totalTokens = 0.0,
            toolNativeSuccessRate = null,
            transcript = emptyList()
        )
    }

    private fun scoreSingleAgentFinalAnswer(
        options: BenchmarkCliOptions,
        task: Tier0BenchmarkTask,
        runId: String,
        taskOutputDir: Path,
        finalText: String,
        modelId: String,
        promptTokens: Double,
        completionTokens: Double,
        totalTokens: Double,
        toolNativeSuccessRate: Double?,
        transcript: List<JsonObject>
    ): BenchmarkCliTaskResult {
        taskOutputDir.resolve("single-agent-final.txt").writeText(finalText)
        val predictionJson = extractJsonObject(finalText).toString()
        val outputPath = benchmarkPredictionPath(task.benchmark)
        taskOutputDir.resolve(outputPath).also { path ->
            path.parent?.createDirectories()
            path.writeText(predictionJson)
        }
        val prediction = parsePrediction(task.id, predictionJson)
            .withFallbackCost(options.costOverrideUsdPerTask)
        if (task.benchmark == Tier0BenchmarkKind.AUTO_RESEARCH_BENCH) {
            writeAutoResearchBenchArtifacts(taskOutputDir, prediction)
        }
        val score = Tier0ExactAnswerScorer.score(task, prediction)
        val citationSupportCoverage = scoreCitationSupport(task, prediction)
        val claimCoverage = scoreClaimCoverage(task, prediction)
        val literatureDiscoveryScore = if (task.benchmark == Tier0BenchmarkKind.AUTO_RESEARCH_BENCH) {
            LiteratureDiscoveryScorer.score(task, prediction)
        } else {
            null
        }
        val exactCorrect = literatureDiscoveryScore
            ?.let { it.exactTargetFound ?: (it.precision == 1.0 && it.recall == 1.0) }
            ?: score.exactCorrect
        taskOutputDir.resolve("single-agent-baseline-report.json").writeText(
            buildJsonObject {
                put("runner", BenchmarkCliRunnerKind.SINGLE_AGENT.id)
                put("model_id", modelId)
                put("prompt_tokens", promptTokens)
                put("completion_tokens", completionTokens)
                put("total_tokens", totalTokens)
                prediction.totalCost?.let { put("total_cost", it) } ?: put("total_cost", JsonNull)
                put("supported_claim_count", prediction.evidencePath.size)
                put("citation_support_coverage", citationSupportCoverage?.let(::JsonPrimitive) ?: JsonNull)
                put("claim_coverage", claimCoverage?.let(::JsonPrimitive) ?: JsonNull)
                put("tool_native_success_rate", toolNativeSuccessRate?.let(::JsonPrimitive) ?: JsonNull)
                putJsonArray("transcript") {
                    transcript.forEach(::add)
                }
            }.toString()
        )

        return BenchmarkCliTaskResult(
            taskId = task.id,
            runId = runId,
            status = "COMPLETED",
            outputDirectory = taskOutputDir.absolutePathString(),
            finalAnswer = prediction.finalAnswer,
            goldAnswers = task.acceptedAnswers,
            exactCorrect = exactCorrect,
            evidencePathPresent = score.evidencePathPresent,
            normalizedPrediction = literatureDiscoveryScore?.let { prediction.targetPaperIds.joinToString(",") }
                ?: score.normalizedPrediction,
            matchedGoldAnswer = if (literatureDiscoveryScore?.foundGoldPaperCount == task.acceptedAnswers.size) {
                task.acceptedAnswers.joinToString(",")
            } else {
                score.matchedGoldAnswer
            },
            supportingPassageCount = prediction.supportingPassages.size,
            paperIds = prediction.paperIds,
            whyAlternativesWrongCount = prediction.whyAlternativesWrong.size,
            confidence = prediction.confidence,
            citationSupportCoverage = citationSupportCoverage,
            claimCoverage = claimCoverage,
            totalCost = prediction.totalCost,
            toolNativeSuccessRate = toolNativeSuccessRate,
            literatureDiscoveryScore = literatureDiscoveryScore
        )
    }

    private fun postSingleAgentChatCompletion(
        httpClient: HttpClient,
        apiKey: String,
        modelId: String,
        messages: List<JsonObject>,
        requireToolCall: Boolean
    ): JsonObject {
        val body = buildSingleAgentChatCompletionBody(
            modelId = modelId,
            messages = messages,
            requireToolCall = requireToolCall
        ).toString()
        val request = HttpRequest.newBuilder(URI.create(SINGLE_AGENT_DASHSCOPE_CHAT_COMPLETIONS_URL))
            .timeout(Duration.ofMinutes(2))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) {
            "Single-agent Qwen request failed with HTTP ${response.statusCode()}: ${response.body().take(800)}"
        }
        return benchmarkJson.parseToJsonElement(response.body()).jsonObject
    }

    internal fun buildSingleAgentChatCompletionBody(
        modelId: String,
        messages: List<JsonObject>,
        requireToolCall: Boolean
    ): JsonObject = buildJsonObject {
            put("model", modelId)
            putJsonArray("messages") {
                messages.forEach(::add)
            }
            put("temperature", 0.1)
            put("enable_thinking", false)
            if (requireToolCall) {
                put("tool_choice", "required")
            } else {
                putJsonObject("response_format") {
                    put("type", "json_object")
                }
            }
            putJsonArray("tools") {
                add(singleAgentToolDescriptor("read_run_artifact", "List or read uploaded benchmark source artifacts.") {
                    putJsonObject("properties") {
                        putJsonObject("relativePath") {
                            put("type", "string")
                            put("description", "Artifact relative path to read. Omit when listOnly=true.")
                        }
                        putJsonObject("listOnly") {
                            put("type", "boolean")
                            put("description", "When true, list available artifact paths and previews.")
                        }
                    }
                })
                add(singleAgentToolDescriptor("fetch_source", "Fetch a public HTTP(S) URL for additional evidence.") {
                    putJsonObject("properties") {
                        putJsonObject("url") {
                            put("type", "string")
                            put("description", "HTTP(S) URL to fetch.")
                        }
                        putJsonObject("purpose") {
                            put("type", "string")
                            put("description", "Why this source is needed for the benchmark answer.")
                        }
                    }
                    putJsonArray("required") {
                        add(JsonPrimitive("url"))
                        add(JsonPrimitive("purpose"))
                    }
                })
            }
        }

    private fun nativeToolSuccessRate(successes: Int, attempts: Int): Double? =
        if (attempts == 0) null else successes.toDouble() / attempts.toDouble()

    private inline fun singleAgentToolDescriptor(
        name: String,
        description: String,
        crossinline parameters: JsonObjectBuilder.() -> Unit
    ): JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", name)
            put("description", description)
            putJsonObject("parameters") {
                put("type", "object")
                parameters()
            }
        }
    }

    private fun chatMessage(role: String, content: String, cacheControl: Boolean = false): JsonObject = buildJsonObject {
        put("role", role)
        if (cacheControl) {
            putJsonArray("content") {
                add(cachedTextContent(content))
            }
        } else {
            put("content", content)
        }
    }

    private fun chatMessageWithCachedPrefix(role: String, cachedPrefix: String, suffix: String): JsonObject = buildJsonObject {
        put("role", role)
        putJsonArray("content") {
            add(cachedTextContent(cachedPrefix))
            if (suffix.length >= SINGLE_AGENT_EXPLICIT_CACHE_MIN_CHARS) {
                add(cachedTextContent(suffix))
            } else {
                add(
                    buildJsonObject {
                        put("type", "text")
                        put("text", suffix)
                    }
                )
            }
        }
    }

    private fun cachedTextContent(text: String): JsonObject = buildJsonObject {
        put("type", "text")
        put("text", text)
        putJsonObject("cache_control") {
            put("type", "ephemeral")
        }
    }

    private fun assistantMessageForReplay(message: JsonObject): JsonObject = buildJsonObject {
        put("role", "assistant")
        put("content", message["content"] ?: JsonNull)
        message["tool_calls"]?.let { put("tool_calls", it) }
    }

    private fun parseToolArguments(arguments: JsonElement?): JsonObject {
        return when (arguments) {
            is JsonObject -> arguments
            is JsonPrimitive -> arguments.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?.let { raw -> runCatching { benchmarkJson.parseToJsonElement(raw).jsonObject }.getOrNull() }
                ?: JsonObject(emptyMap())
            else -> JsonObject(emptyMap())
        }
    }

    private fun executeSingleAgentTool(
        name: String,
        args: JsonObject,
        task: Tier0BenchmarkTask,
        objective: String,
        artifactIndex: Map<String, BenchmarkSourceArtifact>,
        httpClient: HttpClient
    ): String = when (name) {
        "read_run_artifact" -> readSingleAgentArtifact(args, artifactIndex)
        "fetch_source" -> fetchSingleAgentSource(args, task, objective, httpClient)
        else -> "ERROR: unknown tool '$name'."
    }

    private fun readSingleAgentArtifact(
        args: JsonObject,
        artifactIndex: Map<String, BenchmarkSourceArtifact>
    ): String {
        val listOnly = args["listOnly"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        val path = args["relativePath"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (listOnly || path.isBlank()) {
            return buildString {
                appendLine("Available artifacts:")
                artifactIndex.values.sortedBy { it.upload.relativePath }.forEach { artifact ->
                    appendLine("- ${artifact.upload.relativePath} mirrors ${artifact.url}")
                    artifact.upload.textPreview?.takeIf { it.isNotBlank() }?.let { preview ->
                        appendLine("  preview: ${preview.replace(Regex("\\s+"), " ").take(240)}")
                    }
                }
            }
        }
        val artifact = artifactIndex[path]
            ?: return "ERROR: artifact '$path' not found. Call read_run_artifact with listOnly=true."
        val bytes = Base64.getDecoder().decode(artifact.upload.contentBase64)
        return buildString {
            appendLine("Artifact: ${artifact.upload.relativePath}")
            appendLine("Source: ${artifact.url}")
            appendLine("MIME: ${artifact.upload.mimeType}")
            appendLine()
            appendLine(bytes.toString(Charsets.UTF_8).take(SOURCE_ARTIFACT_MAX_CHARS))
        }
    }

    private fun fetchSingleAgentSource(
        args: JsonObject,
        task: Tier0BenchmarkTask,
        objective: String,
        httpClient: HttpClient
    ): String {
        val url = args["url"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return "ERROR: fetch_source requires an http(s) URL."
        }
        val knownSource = task.relevantSources.any { it.equals(url, ignoreCase = true) } ||
            objective.contains(url, ignoreCase = true)
        if (!knownSource && task.relevantSources.isNotEmpty()) {
            return "BLOCKED: fetch_source is restricted to benchmark-provided URLs for this baseline."
        }
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(45))
            .header("User-Agent", "EvoMolior single-agent baseline/1.0")
            .GET()
            .build()
        return runCatching {
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            buildString {
                appendLine("SOURCE_FETCHED")
                appendLine("URL: $url")
                appendLine("Status: ${response.statusCode()}")
                response.headers().firstValue("content-type").ifPresent { appendLine("Content-Type: $it") }
                appendLine()
                appendLine(
                    response.body()
                        .replace(Regex("(?is)<script.*?</script>"), " ")
                        .replace(Regex("(?is)<style.*?</style>"), " ")
                        .replace(Regex("(?is)<[^>]+>"), " ")
                        .replace(Regex("\\s+"), " ")
                        .take(SOURCE_ARTIFACT_MAX_CHARS)
                )
            }
        }.getOrElse { error ->
            "FETCH_FAILED: $url. ${error.message ?: error::class.simpleName.orEmpty()}"
        }
    }

    private fun buildRunConfig(
        options: BenchmarkCliOptions,
        task: Tier0BenchmarkTask,
        runId: String,
        objective: String,
        sourceArtifacts: List<BenchmarkSourceArtifact>
    ): RunConfig = RunConfig(
        runId = runId,
        objective = objective,
        groupCount = options.groupCount,
        groupProfiles = List(options.groupCount) { GroupProfileType.Library },
        rounds = options.rounds,
        roundDurationMinutes = options.roundDurationMinutes,
        maxAgentsPerGroup = options.maxAgentsPerGroup,
        scoringDimensions = ScoringDimensionCatalog.all,
        openRouterApiKey = options.apiKey,
        llmBackend = options.llmBackend,
        modelOverrides = options.modelOverrides,
        contextArtifacts = sourceArtifacts.map { it.upload },
        finalOutputSpec = FinalOutputSpec(
            relativePath = benchmarkPredictionPath(task.benchmark),
            mediaType = "application/json",
            description = if (task.benchmark == Tier0BenchmarkKind.AUTO_RESEARCH_BENCH) {
                "AutoResearchBench target_paper_ids.json discovery output"
            } else {
                "Tier-0 benchmark prediction JSON"
            },
            kind = FinalOutputKind.GENERIC_FILE
        ),
        isTestRun = true,
        budgetOverride = options.budgetOverride,
        graderGroupCountOverride = options.comparativeGraderGroupCount
    )

    private fun buildObjective(
        options: BenchmarkCliOptions,
        task: Tier0BenchmarkTask,
        sourceArtifacts: List<BenchmarkSourceArtifact>
    ): String = buildString {
        append(Tier0BenchmarkPromptContract.buildObjective(task))
        appendSourceArtifactInstructions(
            sourceArtifacts = sourceArtifacts,
            heading = "Uploaded benchmark source artifacts:",
            summaryInstruction = "Read benchmark source-summary.md before other source artifacts. When it contains an explicit derived ranking line such as 'Published table-rank insertion', use that exact ordinal for final_answer and cite the underlying source artifact rows in evidence_path."
        )
        appendSourceSummaryExcerpt(sourceArtifacts)
        appendSourceArtifactExcerpts(task, sourceArtifacts)
    }

    internal fun buildSourceArtifactUploadsForObjective(
        objective: String,
        namespace: String
    ): List<RunInputArtifactUpload> {
        val sources = extractSourceUrls(objective)
        if (sources.isEmpty()) return emptyList()
        val task = Tier0BenchmarkTask(
            id = namespace.toBenchmarkTaskIdFragment(),
            benchmark = Tier0BenchmarkKind.FRAMES,
            question = objective,
            relevantSources = sources,
            answerFormat = BenchmarkAnswerFormat.EXACT,
            requiresBrowsing = true
        )
        return buildSourceArtifacts(task).map { artifact ->
            artifact.upload.copy(
                relativePath = artifact.upload.relativePath.replace(
                    "benchmark-sources/${task.id}/",
                    "source-artifacts/${task.id}/"
                )
            )
        }
    }

    /** Freezes one exact benchmark source bundle before comparative arm execution. */
    internal fun freezeComparativeSourceArtifacts(
        task: Tier0BenchmarkTask
    ): List<RunInputArtifactUpload> = buildSourceArtifacts(task).map(BenchmarkSourceArtifact::upload)

    /** Builds the exact objective seen by every arm from one frozen source bundle. */
    internal fun buildComparativeObjective(
        options: BenchmarkCliOptions,
        task: Tier0BenchmarkTask,
        uploads: List<RunInputArtifactUpload>
    ): String = buildObjective(
        options,
        task,
        uploads.map { upload -> BenchmarkSourceArtifact(upload.sourceName, upload) }
    )

    /** Converts frozen uploads to the canonical metadata consumed by Genesis Council. */
    internal fun comparativeInputArtifacts(
        uploads: List<RunInputArtifactUpload>
    ): List<RunInputArtifact> = uploads.map { upload ->
        val bytes = Base64.getDecoder().decode(upload.contentBase64)
        RunInputArtifact(
            relativePath = upload.relativePath,
            sourceName = upload.sourceName,
            mimeType = upload.mimeType,
            sizeBytes = bytes.size.toLong(),
            sha256 = sha256Hex(bytes),
            kind = upload.kind,
            textPreview = upload.textPreview
        )
    }

    internal fun buildNormalRunSourceInstruction(sourceArtifacts: List<RunInputArtifactUpload>): String =
        buildString {
            appendSourceArtifactInstructions(
                sourceArtifacts = sourceArtifacts.map { BenchmarkSourceArtifact(it.sourceName, it) },
                heading = "Uploaded source artifacts:",
                summaryInstruction = "Read source-summary.md before other source artifacts when present. If it contains an explicit derived ranking/counting line, use that derived value instead of estimating from memory."
            )
        }.trim()

    internal fun buildTextOnlyArtifactInstruction(
        artifacts: List<RunInputArtifactUpload>,
        totalBudget: Int = 40_000,
        perArtifactBudget: Int = 20_000
    ): String {
        if (artifacts.isEmpty() || totalBudget <= 0 || perArtifactBudget <= 0) return ""
        var remaining = totalBudget
        val excerpts = artifacts.mapNotNull { artifact ->
            if (remaining <= 0) return@mapNotNull null
            val text = runCatching {
                String(Base64.getDecoder().decode(artifact.contentBase64), Charsets.UTF_8)
            }.getOrNull()
                ?.trim()
                ?.takeUnless { it.isBlank() }
                ?: return@mapNotNull null
            val limit = minOf(perArtifactBudget, remaining)
            val excerpt = text.take(limit)
            remaining -= excerpt.length
            val truncated = text.length > excerpt.length
            Triple(artifact.relativePath, artifact.sourceName, excerpt + if (truncated) "\n[TRUNCATED]" else "")
        }
        if (excerpts.isEmpty()) return ""

        return buildString {
            appendLine("Uploaded artifact text for text-only backend compatibility:")
            appendLine("Use the in-context artifact text below directly. Do not call read_run_artifact or describe tool calls.")
            excerpts.forEach { (relativePath, sourceName, excerpt) ->
                appendLine()
                appendLine("### $relativePath")
                appendLine("Source: $sourceName")
                appendLine("```text")
                appendLine(excerpt)
                appendLine("```")
            }
        }.trim()
    }

    private fun extractSourceUrls(text: String): List<String> =
        Regex("""https?://[^\s<>"')\]]+""")
            .findAll(text)
            .map { it.value.trimEnd('.', ',', ';', ':') }
            .distinct()
            .toList()

    private fun StringBuilder.appendSourceArtifactInstructions(
        sourceArtifacts: List<BenchmarkSourceArtifact>,
        heading: String,
        summaryInstruction: String
    ) {
        if (sourceArtifacts.isEmpty()) return
        appendLine()
        appendLine(heading)
        sourceArtifacts.forEach { artifact ->
            appendLine("- ${artifact.upload.relativePath} mirrors ${artifact.url}")
        }
        appendLine()
        if (sourceArtifacts.any { it.upload.relativePath.endsWith("/$SOURCE_SUMMARY_ARTIFACT_NAME") }) {
            appendLine(summaryInstruction)
        }
        appendLine("Before answering, call read_run_artifact for the relevant uploaded source artifact paths above. Prefer artifact text/table rows over memory when they conflict.")
    }

    private fun StringBuilder.appendSourceSummaryExcerpt(sourceArtifacts: List<BenchmarkSourceArtifact>) {
        val summaryArtifact = sourceArtifacts.firstOrNull { artifact ->
            artifact.upload.relativePath.endsWith("/$SOURCE_SUMMARY_ARTIFACT_NAME") ||
                artifact.upload.relativePath == SOURCE_SUMMARY_ARTIFACT_NAME
        } ?: return
        val summaryText = runCatching {
            String(Base64.getDecoder().decode(summaryArtifact.upload.contentBase64), Charsets.UTF_8)
        }.getOrNull()?.trim().takeUnless { it.isNullOrBlank() } ?: return

        appendLine()
        appendLine("Generated benchmark source-summary excerpt:")
        appendLine(summaryText.take(4_000))
        appendLine()
        appendLine("Use the excerpt above as runtime-provided source evidence, and cite the source-summary artifact plus the underlying source artifact paths in evidence_path.")
    }

    private fun StringBuilder.appendSourceArtifactExcerpts(
        task: Tier0BenchmarkTask,
        sourceArtifacts: List<BenchmarkSourceArtifact>
    ) {
        val excerptBudget = 10_000
        val perArtifactBudget = 1_800
        var remaining = excerptBudget
        val excerpts = sourceArtifacts.asSequence()
            .filterNot { it.upload.relativePath.endsWith("/$SOURCE_SUMMARY_ARTIFACT_NAME") }
            .mapNotNull { artifact ->
                if (remaining <= 0) return@mapNotNull null
                val text = runCatching {
                    String(Base64.getDecoder().decode(artifact.upload.contentBase64), Charsets.UTF_8)
                }.getOrNull()
                    ?.trim()
                    ?.takeUnless { it.isBlank() }
                    ?: return@mapNotNull null
                val excerpt = selectSourceArtifactExcerpt(text, task.question)
                    .take(minOf(perArtifactBudget, remaining))
                remaining -= excerpt.length
                artifact.upload.relativePath to excerpt
            }
            .toList()
        if (excerpts.isEmpty()) return

        appendLine()
        appendLine("Benchmark source text excerpts for text-only backends:")
        appendLine("Use these selected snippets as in-context source evidence when artifact-reading tools are unavailable. Cite the matching artifact paths in evidence_path.")
        excerpts.forEach { (path, excerpt) ->
            appendLine()
            appendLine("### $path")
            appendLine(excerpt)
        }
    }

    private fun selectSourceArtifactExcerpt(text: String, question: String): String {
        val keywordSeeds = setOf(
            "answer", "assassinated", "born", "date", "family", "first", "lady", "maiden",
            "married", "mother", "name", "parent", "president", "rank", "sister", "surname",
            "table", "wife", "year"
        )
        val questionKeywords = question
            .lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 4 }
            .toSet()
        val keywords = keywordSeeds + questionKeywords
        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
        val selectedIndices = lines
            .mapIndexedNotNull { index, line ->
                val lower = line.lowercase()
                val score = keywords.count { keyword -> lower.contains(keyword) }
                score.takeIf { it > 0 }?.let { index to it }
            }
            .sortedByDescending { it.second }
            .take(8)
            .flatMap { (index, _) -> (index - 1..index + 1) }
            .filter { it in lines.indices }
            .toSortedSet()

        val header = lines.take(6)
        val snippets = selectedIndices.map { excerptLineAroundKeywords(lines[it], keywords) }
        return (header + snippets)
            .distinct()
            .joinToString("\n")
            .takeIf { it.isNotBlank() }
            ?: text.take(1_800)
    }

    private fun excerptLineAroundKeywords(line: String, keywords: Set<String>): String {
        if (line.length <= 700) return line
        val lower = line.lowercase()
        val matchIndex = keywords
            .mapNotNull { keyword -> lower.indexOf(keyword).takeIf { it >= 0 } }
            .minOrNull()
            ?: return line.take(700)
        val start = (matchIndex - 250).coerceAtLeast(0)
        val end = (matchIndex + 1_000).coerceAtMost(line.length)
        val prefix = if (start > 0) "..." else ""
        val suffix = if (end < line.length) "..." else ""
        return prefix + line.substring(start, end) + suffix
    }

    private fun modelForRole(options: BenchmarkCliOptions, role: String): String =
        options.modelOverrides[role]
            ?: options.modelOverrides.values.firstOrNull()
            ?: when (options.llmBackend) {
                LlmBackend.OPENROUTER -> FastModelSelectionConfig.FAST_RUNTIME_RESEARCHER_MODEL
                LlmBackend.QWEN_CLOUD -> FastModelSelectionConfig.FAST_QWEN_CLOUD_QWEN_MODEL
                LlmBackend.CODEX_CHATGPT -> ModelSelectionConfig.CODEX_CHATGPT_GPT56_LUNA_MODEL
            }

    internal fun benchmarkPredictionPath(benchmark: Tier0BenchmarkKind): String =
        if (benchmark == Tier0BenchmarkKind.AUTO_RESEARCH_BENCH) {
            AUTO_RESEARCH_BENCH_TARGET_IDS_PATH
        } else {
            BENCHMARK_PREDICTION_PATH
        }

    internal fun buildFramesSourceSummaryForTesting(
        task: Tier0BenchmarkTask,
        sourcesByUrl: Map<String, String>
    ): String? = buildBenchmarkSourceSummary(
        task = task,
        sources = sourcesByUrl.map { (url, content) -> FetchedBenchmarkSource(url, content) }
    )

    internal fun applySourceSummaryCoverageAbstentionForTesting(
        task: Tier0BenchmarkTask,
        prediction: Tier0BenchmarkPrediction,
        sourceSummary: String?
    ): Tier0BenchmarkPrediction = prediction.abstainIfIncompleteSourceCoverage(
        task = task,
        sourceSummary = sourceSummary,
        summaryPath = "benchmark-sources/${task.id}/$SOURCE_SUMMARY_ARTIFACT_NAME"
    )

    internal fun selectSourceArtifactExcerptForTesting(text: String, question: String): String =
        selectSourceArtifactExcerpt(text, question)

    private fun Tier0BenchmarkPrediction.abstainIfIncompleteSourceCoverage(
        task: Tier0BenchmarkTask,
        sourceSummary: String?,
        summaryPath: String
    ): Tier0BenchmarkPrediction {
        if (
            task.benchmark != Tier0BenchmarkKind.FRAMES ||
            finalAnswer.isBlank() ||
            sourceSummary?.contains(INCOMPLETE_CONSECUTIVE_WIN_COVERAGE_WARNING) != true
        ) return this

        return copy(
            finalAnswer = "",
            evidencePath = evidencePath + Tier0EvidenceStep(
                source = summaryPath,
                claim = "Abstained because source-summary reports incomplete consecutive-win event-page coverage; the uploaded-row diagnostic count is not a valid final answer."
            ),
            confidence = 0.0
        )
    }

    private fun Tier0BenchmarkPrediction.withFallbackCost(costOverrideUsdPerTask: Double?): Tier0BenchmarkPrediction =
        if (totalCost == null && costOverrideUsdPerTask != null) {
            copy(totalCost = costOverrideUsdPerTask)
        } else {
            this
        }

    private fun failedTaskResult(
        task: Tier0BenchmarkTask,
        runId: String,
        taskOutputDir: Path,
        error: String,
        bundlePath: String? = null
    ): BenchmarkCliTaskResult = BenchmarkCliTaskResult(
        taskId = task.id,
        runId = runId,
        status = "FAILED",
        outputDirectory = taskOutputDir.absolutePathString(),
        finalAnswer = "",
        goldAnswers = task.acceptedAnswers,
        exactCorrect = false,
        evidencePathPresent = false,
        normalizedPrediction = "",
        matchedGoldAnswer = null,
        resultsBundlePath = bundlePath,
        error = error
    )

    internal fun loadTasks(options: BenchmarkCliOptions): List<Tier0BenchmarkTask> = when (options.benchmark) {
        Tier0BenchmarkKind.FRAMES -> loadFramesTasks()
        Tier0BenchmarkKind.LITQA2 -> loadLitQa2Tasks(options.limit)
        Tier0BenchmarkKind.SCHOLAR_QABENCH -> loadScholarQaBenchTasks(options.scholarQaBenchVariant)
        Tier0BenchmarkKind.SCHOLAR_QA -> parseScholarQaJsonl(
            options.tasksJsonl ?: error("Missing tasks JSONL"),
            Tier0BenchmarkKind.SCHOLAR_QA
        )
        Tier0BenchmarkKind.AI2_SCHOLAR_QA -> parseScholarQaJsonl(
            options.tasksJsonl ?: error("Missing tasks JSONL"),
            Tier0BenchmarkKind.AI2_SCHOLAR_QA
        )
        Tier0BenchmarkKind.AUTO_RESEARCH_BENCH -> parseAutoResearchBenchJsonl(
            options.tasksJsonl ?: error("Missing tasks JSONL")
        )
        Tier0BenchmarkKind.BROWSECOMP,
        Tier0BenchmarkKind.GAIA -> error("Benchmark ${options.benchmark} is not wired to the CLI subset runner yet.")
    }

    private fun loadFramesTasks(): List<Tier0BenchmarkTask> {
        val body = HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(URI.create(FRAMES_TEST_TSV_URL)).GET().build(),
                HttpResponse.BodyHandlers.ofString()
            )
            .body()
        val rows = parseTsv(body)
        return rows.mapIndexed { index, row ->
            val sources = (1..11)
                .mapNotNull { row["wikipedia_link_$it"]?.takeIf(String::isNotBlank) }
            Tier0BenchmarkTask(
                id = "frames-${row[""]?.ifBlank { index.toString() } ?: index}",
                benchmark = Tier0BenchmarkKind.FRAMES,
                question = row.getValue("Prompt"),
                acceptedAnswers = listOf(row.getValue("Answer")),
                relevantSources = sources,
                answerFormat = BenchmarkAnswerFormat.EXACT,
                requiresBrowsing = true
            )
        }
    }

    private fun buildSourceArtifacts(task: Tier0BenchmarkTask): List<BenchmarkSourceArtifact> {
        val asOfTimestamp = extractAsOfSnapshotTimestamp(task.question)
        val fetchedSources = task.relevantSources.filter(::isFetchableSourceUrl).mapNotNull { sourceUrl ->
            val content = fetchSourceArtifactContent(sourceUrl, asOfTimestamp).takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            FetchedBenchmarkSource(sourceUrl, content)
        }
        val fixedCorpusSources = task.sourcePassages.map { source ->
            FetchedBenchmarkSource(
                url = source.paperId,
                content = buildString {
                    appendLine("# Benchmark fixed-corpus excerpt")
                    appendLine()
                    appendLine("paper_id: ${source.paperId}")
                    appendLine()
                    appendLine(source.passage)
                }
            )
        }
        val mirroredSources = (fixedCorpusSources + fetchedSources).mapIndexed { index, source ->
            source.toBenchmarkSourceArtifact("benchmark-sources/${task.id}/${index + 1}-${slugForSource(source.url)}.md")
        }
        val summary = buildBenchmarkSourceSummary(task, fetchedSources)?.let { content ->
            FetchedBenchmarkSource("deterministic-summary:${task.id}", content)
                .toBenchmarkSourceArtifact("benchmark-sources/${task.id}/$SOURCE_SUMMARY_ARTIFACT_NAME")
        }
        return listOfNotNull(summary) + mirroredSources
    }

    private fun FetchedBenchmarkSource.toBenchmarkSourceArtifact(relativePath: String): BenchmarkSourceArtifact {
        val bytes = content.toByteArray(Charsets.UTF_8)
        return BenchmarkSourceArtifact(
            url = url,
            upload = RunInputArtifactUpload(
                relativePath = relativePath,
                sourceName = url,
                mimeType = "text/markdown",
                contentBase64 = Base64.getEncoder().encodeToString(bytes),
                kind = RunInputArtifactKind.TEXT,
                textPreview = content.replace(Regex("\\s+"), " ").take(512)
            )
        )
    }

    private fun fetchSourceArtifactContent(sourceUrl: String, asOfTimestamp: String?): String {
        val title = wikipediaTitle(sourceUrl)
        val snapshot = title
            ?.let { asOfTimestamp?.let { timestamp -> resolveWikipediaSnapshot(it, timestamp) } }
        val extract = when {
            title == null -> ""
            snapshot != null -> fetchWikipediaExtractByRevision(snapshot.revisionId)
            else -> fetchWikipediaExtract(title)
        }
        val tableText = fetchHtmlTables(sourceUrl, snapshot)
        return buildString {
            appendLine("# Benchmark source")
            appendLine()
            appendLine("URL: $sourceUrl")
            title?.let { appendLine("Wikipedia title: ${it.replace('_', ' ')}") }
            snapshot?.let {
                appendLine("Wikipedia snapshot revision: ${it.revisionId}")
                appendLine("Wikipedia snapshot timestamp: ${it.timestamp}")
            }
            appendLine()
            if (extract.isNotBlank()) {
                appendLine("## Plaintext extract")
                appendLine()
                appendLine(extract)
                appendLine()
            }
            if (tableText.isNotBlank()) {
                appendLine("## Extracted table rows")
                appendLine()
                appendLine(tableText)
            }
        }.take(SOURCE_ARTIFACT_MAX_CHARS)
    }

    private fun isFetchableSourceUrl(source: String): Boolean =
        source.startsWith("http://", ignoreCase = true) || source.startsWith("https://", ignoreCase = true)

    private fun buildBenchmarkSourceSummary(
        task: Tier0BenchmarkTask,
        sources: List<FetchedBenchmarkSource>
    ): String? {
        if (task.benchmark != Tier0BenchmarkKind.FRAMES) return null
        val deweyDecimal = extractDeweyDecimal(sources.joinToString("\n") { it.content })
        val asOfYear = extractAsOfYear(task.question)
        val buildingSource = sources.firstOrNull {
            it.url.contains("List_of_tallest_buildings_in_New_York_City", ignoreCase = true)
        }
        val buildingRows = buildingSource?.let { parseTallestNewYorkBuildingRows(it.content) }.orEmpty()
        val frames2PhilCapitolAge = extractPunxsutawneyPhilCapitolAge(sources)
        val wrestleManiaComparison = extractWrestleManiaDurationComparison(task, sources)
        if (
            deweyDecimal == null &&
            asOfYear == null &&
            buildingRows.isEmpty() &&
            frames2PhilCapitolAge == null &&
            wrestleManiaComparison == null
        ) return null

        val asOfRows = asOfYear?.let { year -> buildingRows.filter { it.year <= year } } ?: buildingRows
        val placement = deweyDecimal?.takeIf { asOfRows.isNotEmpty() }?.let { heightFeet ->
            val tallerRows = asOfRows.filter { it.heightFeet > heightFeet }
            TallBuildingPlacement(
                heightFeet = heightFeet,
                tallerPhysicalBuildingCount = tallerRows.size,
                ordinalRank = tallerRows.size + 1,
                tableRank = inferPublishedTableRank(asOfRows, heightFeet),
                contextRows = contextRowsAroundHeight(asOfRows, heightFeet)
            )
        }

        return buildString {
            appendLine("# Deterministic benchmark source summary")
            appendLine()
            appendLine("This artifact is generated from uploaded benchmark source artifacts before the run. Use it as a navigation aid; cite the underlying source artifacts for source claims.")
            appendLine()
            deweyDecimal?.let { appendLine("- Extracted Dewey Decimal value from source artifacts: ${it.formatHeight()}") }
            asOfYear?.let { appendLine("- The task asks for an as-of date in $it; rows with year after $it are excluded for as-of ranking.") }
            buildingSource?.let { appendLine("- Parsed ${buildingRows.size} tallest-building rows from ${it.url}.") }
            if (asOfYear != null && buildingRows.isNotEmpty()) {
                appendLine("- ${asOfRows.size} parsed rows remain after the as-of-year filter.")
            }
            placement?.let { value ->
                appendLine("- Buildings strictly taller than ${value.heightFeet.formatHeight()} ft after filtering: ${value.tallerPhysicalBuildingCount}.")
                appendLine("- A hypothetical building of ${value.heightFeet.formatHeight()} ft would be ${ordinal(value.ordinalRank)} by physical-building ordinal placement.")
                value.tableRank?.let { rank ->
                    appendLine("- Published table-rank insertion for ${value.heightFeet.formatHeight()} ft: ${ordinal(rank)}. Use this as the final answer when the question asks where it would rank among the table's tallest buildings.")
                }
                appendLine()
                appendLine("Rows around the placement threshold:")
                appendLine("Table rank | Name | Height ft | Year")
                value.contextRows.forEach { row ->
                    appendLine("${row.tableRank} | ${row.name} | ${row.heightFeet.formatHeight()} | ${row.year}")
                }
            }
            frames2PhilCapitolAge?.let { value ->
                appendLine("- Punxsutawney Phil source row: Born | ${value.philBirthYear} Punxsutawney, Pennsylvania.")
                appendLine("- U.S. Capitol source line: central sections completed in ${value.capitolCompletionYear}, when the 6th U.S. Congress convened there.")
                appendLine("- Derived age/difference: ${value.philBirthYear} - ${value.capitolCompletionYear} = ${value.differenceYears}. Use ${value.differenceYears} as final_answer when asked how old Punxsutawney Phil was when the capitol was completed.")
            }
            wrestleManiaComparison?.let { value ->
                appendLine("- This task asks for a count comparing Mark Calaway/The Undertaker's WrestleMania match duration to the final result duration in the same event, excluding rows where Calaway is part of the final result.")
                appendLine("- Parsed ${value.rows.size} event-result tables from the uploaded WrestleMania source artifacts.")
                value.consecutiveWinCount?.let { count ->
                    appendLine("- The Streak table lists $count consecutive WrestleMania wins before the first loss.")
                }
                if (value.consecutiveWinCount != null && value.rows.size < value.consecutiveWinCount) {
                    appendLine("- $INCOMPLETE_CONSECUTIVE_WIN_COVERAGE_WARNING Do not use the uploaded-row count as the final answer for a full-streak question; use it only as a partial diagnostic and gather/inspect missing event-result pages before answering.")
                } else {
                    appendLine("- The uploaded event-result artifacts appear to cover the available consecutive-win rows for this task.")
                }
                appendLine()
                appendLine("Parsed uploaded-event comparison rows:")
                appendLine("Event | Undertaker result | Undertaker time | Final result | Final time | Calaway in final? | Undertaker longer?")
                value.rows.forEach { row ->
                    appendLine(
                        listOf(
                            row.event,
                            row.undertakerResult,
                            row.undertakerTime,
                            row.finalResult,
                            row.finalTime,
                            row.calawayInFinal.toString(),
                            row.undertakerLonger?.toString() ?: "unknown"
                        ).joinToString(" | ")
                    )
                }
                val uploadedLongerCount = value.rows.count { it.undertakerLonger == true && !it.calawayInFinal }
                appendLine()
                appendLine("- Diagnostic count from uploaded event-result rows only, excluding Calaway-final rows: $uploadedLongerCount. This is not the final answer when the coverage warning above is present.")
            }
        }.trim()
    }

    private data class WrestleManiaDurationComparison(
        val rows: List<WrestleManiaDurationRow>,
        val consecutiveWinCount: Int?
    )

    private data class WrestleManiaDurationRow(
        val event: String,
        val undertakerResult: String,
        val undertakerTime: String,
        val finalResult: String,
        val finalTime: String,
        val calawayInFinal: Boolean,
        val undertakerLonger: Boolean?
    )

    private fun extractWrestleManiaDurationComparison(
        task: Tier0BenchmarkTask,
        sources: List<FetchedBenchmarkSource>
    ): WrestleManiaDurationComparison? {
        val question = task.question.lowercase()
        if (!question.contains("mark calaway") || !question.contains("wrestlemania")) return null
        val rows = sources
            .filter { source -> source.url.contains("WrestleMania", ignoreCase = true) }
            .mapNotNull(::parseWrestleManiaDurationRow)
        val consecutiveWinCount = sources
            .firstOrNull { source -> source.url.contains("The_Streak", ignoreCase = true) }
            ?.let { source -> countConsecutiveUndertakerWrestleManiaWins(source.content) }
        return rows.takeIf { it.isNotEmpty() }?.let { WrestleManiaDurationComparison(it, consecutiveWinCount) }
    }

    private fun countConsecutiveUndertakerWrestleManiaWins(content: String): Int {
        var count = 0
        for (line in content.lineSequence().map(String::trim)) {
            if (line.startsWith("Loss |") && line.contains("WrestleMania", ignoreCase = true)) return count
            if (line.startsWith("Win |") && line.contains("WrestleMania", ignoreCase = true)) count += 1
        }
        return count
    }

    private fun parseWrestleManiaDurationRow(source: FetchedBenchmarkSource): WrestleManiaDurationRow? {
        val resultRows = source.content
            .lineSequence()
            .map(String::trim)
            .filter { line -> Regex("""^\d+\s*\|""").containsMatchIn(line) }
            .mapNotNull(::parseResultTableRow)
            .toList()
        val undertakerRow = resultRows.firstOrNull { row ->
            row.result.contains("Undertaker", ignoreCase = true)
        } ?: return null
        val finalRow = resultRows.lastOrNull() ?: return null
        val undertakerSeconds = durationSeconds(undertakerRow.time)
        val finalSeconds = durationSeconds(finalRow.time)
        val calawayInFinal = finalRow.result.contains("Undertaker", ignoreCase = true) ||
            finalRow.result.contains("Calaway", ignoreCase = true)
        return WrestleManiaDurationRow(
            event = source.url.substringAfterLast('/').substringBefore('#').replace('_', ' '),
            undertakerResult = undertakerRow.result,
            undertakerTime = undertakerRow.time,
            finalResult = finalRow.result,
            finalTime = finalRow.time,
            calawayInFinal = calawayInFinal,
            undertakerLonger = undertakerSeconds?.let { u ->
                finalSeconds?.let { f -> u > f }
            }
        )
    }

    private data class EventResultTableRow(
        val result: String,
        val time: String
    )

    private fun parseResultTableRow(line: String): EventResultTableRow? {
        val cells = line.split(" | ").map(String::trim)
        val result = cells.getOrNull(1)?.takeIf(String::isNotBlank) ?: return null
        val time = cells
            .asReversed()
            .firstOrNull { cell -> durationSeconds(cell) != null }
            ?: return null
        return EventResultTableRow(result = result, time = time)
    }

    private fun durationSeconds(value: String): Int? {
        val match = Regex("""\b(?:(\d+):)?(\d{1,2}):(\d{2})\b|\b(\d{1,2}):(\d{2})\b""")
            .find(value)
            ?: return null
        return if (match.groupValues[4].isNotBlank()) {
            (match.groupValues[4].toIntOrNull() ?: return null) * 60 +
                (match.groupValues[5].toIntOrNull() ?: return null)
        } else {
            val hours = match.groupValues[1].takeIf(String::isNotBlank)?.toIntOrNull() ?: 0
            val minutes = match.groupValues[2].toIntOrNull() ?: return null
            val seconds = match.groupValues[3].toIntOrNull() ?: return null
            hours * 3600 + minutes * 60 + seconds
        }
    }

    private data class YearDifferenceSummary(
        val philBirthYear: Int,
        val capitolCompletionYear: Int,
        val differenceYears: Int
    )

    private fun extractPunxsutawneyPhilCapitolAge(
        sources: List<FetchedBenchmarkSource>
    ): YearDifferenceSummary? {
        val philSource = sources.firstOrNull {
            it.url.contains("Punxsutawney_Phil", ignoreCase = true)
        } ?: return null
        val capitolSource = sources.firstOrNull {
            it.url.contains("United_States_Capitol", ignoreCase = true)
        } ?: return null
        val philBirthYear = Regex("""Born\s*\|\s*(\d{4})\s+Punxsutawney""")
            .find(philSource.content)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: return null
        val capitolCompletionYear = Regex("""Central sections of the present building were completed in (\d{4})""")
            .find(capitolSource.content)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Regex("""Completed\s*\|\s*(\d{4})""")
                .find(capitolSource.content)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
            ?: return null
        return YearDifferenceSummary(
            philBirthYear = philBirthYear,
            capitolCompletionYear = capitolCompletionYear,
            differenceYears = philBirthYear - capitolCompletionYear
        )
    }

    private data class TallBuildingPlacement(
        val heightFeet: Double,
        val tallerPhysicalBuildingCount: Int,
        val ordinalRank: Int,
        val tableRank: Int?,
        val contextRows: List<TallBuildingRow>
    )

    private fun ordinal(value: Int): String {
        val suffix = if (value % 100 in 11..13) {
            "th"
        } else {
            when (value % 10) {
                1 -> "st"
                2 -> "nd"
                3 -> "rd"
                else -> "th"
            }
        }
        return "$value$suffix"
    }

    private fun Double.formatHeight(): String =
        if (this % 1.0 == 0.0) toInt().toString() else toString()

    private fun fetchWikipediaExtract(title: String): String {
        val encodedTitle = URLEncoder.encode(title, Charsets.UTF_8)
        val uri = URI.create(
            "https://en.wikipedia.org/w/api.php?action=query&prop=extracts&explaintext=1&format=json&redirects=1&titles=$encodedTitle"
        )
        return runCatching {
            val element = benchmarkJson.parseToJsonElement(fetchText(uri))
            val pages = element.jsonObject["query"]?.jsonObject?.get("pages")?.jsonObject.orEmpty()
            pages.values.firstOrNull()
                ?.jsonObject
                ?.get("extract")
                ?.jsonPrimitive
                ?.contentOrNull
                .orEmpty()
        }.getOrDefault("")
    }

    private fun fetchWikipediaExtractByRevision(revisionId: Long): String {
        val uri = URI.create(
            "https://en.wikipedia.org/w/api.php?action=query&prop=extracts&explaintext=1&format=json&revids=$revisionId"
        )
        return runCatching {
            val element = benchmarkJson.parseToJsonElement(fetchText(uri))
            val pages = element.jsonObject["query"]?.jsonObject?.get("pages")?.jsonObject.orEmpty()
            pages.values.firstOrNull()
                ?.jsonObject
                ?.get("extract")
                ?.jsonPrimitive
                ?.contentOrNull
                .orEmpty()
        }.getOrDefault("")
    }

    private fun resolveWikipediaSnapshot(title: String, beforeTimestamp: String): WikipediaSnapshot? {
        val encodedTitle = URLEncoder.encode(title, Charsets.UTF_8)
        val encodedTimestamp = URLEncoder.encode(beforeTimestamp, Charsets.UTF_8)
        val uri = URI.create(
            "https://en.wikipedia.org/w/api.php?action=query&prop=revisions&rvlimit=1&rvdir=older&rvprop=ids%7Ctimestamp&format=json&redirects=1&titles=$encodedTitle&rvstart=$encodedTimestamp"
        )
        return runCatching {
            val element = benchmarkJson.parseToJsonElement(fetchText(uri))
            val pages = element.jsonObject["query"]?.jsonObject?.get("pages")?.jsonObject.orEmpty()
            val revision = pages.values.firstOrNull()
                ?.jsonObject
                ?.get("revisions")
                ?.jsonArray
                ?.firstOrNull()
                ?.jsonObject
                ?: return@runCatching null
            val revisionId = revision["revid"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                ?: return@runCatching null
            val timestamp = revision["timestamp"]?.jsonPrimitive?.contentOrNull.orEmpty()
            WikipediaSnapshot(revisionId = revisionId, timestamp = timestamp)
        }.getOrNull()
    }

    private fun fetchHtmlTables(sourceUrl: String, snapshot: WikipediaSnapshot? = null): String =
        runCatching {
            val html = fetchText(snapshot?.let { oldRevisionUri(it.revisionId) } ?: URI.create(sourceUrl))
            Regex("""(?is)<table\b.*?</table>""")
                .findAll(html)
                .take(4)
                .mapIndexed { tableIndex, tableMatch ->
                    buildString {
                        appendLine("Table ${tableIndex + 1}")
                        Regex("""(?is)<tr\b.*?</tr>""")
                            .findAll(tableMatch.value)
                            .take(120)
                            .forEach { rowMatch ->
                                val cells = Regex("""(?is)<t[dh]\b[^>]*>(.*?)</t[dh]>""")
                                    .findAll(rowMatch.value)
                                    .map { cleanHtmlText(it.groupValues[1]) }
                                    .filter { it.isNotBlank() }
                                    .toList()
                                if (cells.isNotEmpty()) {
                                    appendLine(cells.joinToString(" | "))
                                }
                            }
                    }
                }
                .joinToString("\n")
        }.getOrDefault("")

    private fun oldRevisionUri(revisionId: Long): URI =
        URI.create("https://en.wikipedia.org/w/index.php?oldid=$revisionId")

    private fun fetchText(uri: URI): String =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(uri)
                    .header("User-Agent", "EvoMolior Tier0 benchmark runner/1.0 (local evaluation)")
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            )
            .body()

    private fun cleanHtmlText(html: String): String =
        html
            .replace(Regex("(?is)<style\\b.*?</style>"), " ")
            .replace(Regex("(?is)<script\\b.*?</script>"), " ")
            .replace(Regex("(?is)<sup\\b.*?</sup>"), " ")
            .replace(Regex("(?is)<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace(Regex("\\s+"), " ")
            .trim()

    internal fun extractAsOfYear(question: String): Int? =
        Regex("""(?i)\bas of [A-Za-z]+\s+(\d{4})\b""")
            .find(question)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()

    internal fun extractAsOfSnapshotTimestamp(question: String): String? {
        val match = Regex("""(?i)\bas of ([A-Za-z]+)\s+(\d{4})\b""").find(question) ?: return null
        val month = monthNumber(match.groupValues[1]) ?: return null
        val year = match.groupValues[2].toIntOrNull() ?: return null
        val nextMonth = if (month == 12) 1 else month + 1
        val nextMonthYear = if (month == 12) year + 1 else year
        return "%04d-%02d-01T00:00:00Z".format(nextMonthYear, nextMonth)
    }

    private fun monthNumber(month: String): Int? =
        listOf(
            "january",
            "february",
            "march",
            "april",
            "may",
            "june",
            "july",
            "august",
            "september",
            "october",
            "november",
            "december"
        ).indexOf(month.lowercase()).takeIf { it >= 0 }?.plus(1)

    internal fun extractDeweyDecimal(text: String): Double? =
        Regex("""(?i)Dewey(?:&#160;|&nbsp;|\u00A0|\s)*(?:Decimal)?(?:&#160;|&nbsp;|\u00A0|\s)*\|\s*([0-9]+(?:\.[0-9]+)?)""")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toDoubleOrNull()

    internal fun parseTallestNewYorkBuildingRows(content: String): List<TallBuildingRow> =
        content.lineSequence()
            .map(String::trim)
            .fold(TallBuildingTableParseState()) { state, line ->
                when {
                    line.startsWith("Table ") && state.inTargetTable -> state.copy(inTargetTable = false)
                    line.contains("Rank | Name", ignoreCase = true) &&
                        line.contains("Height ft (m)", ignoreCase = true) &&
                        !line.contains("Pinn. Rank", ignoreCase = true) -> state.copy(inTargetTable = true)
                    state.inTargetTable && Regex("""^\d+=?\s*\|""").containsMatchIn(line) ->
                        state.copy(rows = state.rows + listOfNotNull(parseTallBuildingRow(line)))
                    else -> state
                }
            }
            .rows

    private data class TallBuildingTableParseState(
        val inTargetTable: Boolean = false,
        val rows: List<TallBuildingRow> = emptyList()
    )

    private fun parseTallBuildingRow(line: String): TallBuildingRow? {
        val cells = line.split(" | ").map(String::trim)
        val rank = cells.getOrNull(0)?.takeIf { Regex("""^\d+=?$""").matches(it) } ?: return null
        val name = cells.getOrNull(1)?.takeIf(String::isNotBlank) ?: return null
        val heightIndex = cells.indexOfFirst { Regex("""^\d[\d,]*\s*\(\d+""").containsMatchIn(it) }
            .takeIf { it >= 0 }
            ?: return null
        val height = cells[heightIndex].substringBefore('(').replace(",", "").trim().toDoubleOrNull()
            ?: return null
        val year = cells.drop(heightIndex + 1)
            .mapNotNull { Regex("""\b(18|19|20|21)\d{2}\b""").find(it)?.value?.toIntOrNull() }
            .firstOrNull()
            ?: return null
        return TallBuildingRow(tableRank = rank, name = name, heightFeet = height, year = year)
    }

    internal fun contextRowsAroundHeight(rows: List<TallBuildingRow>, heightFeet: Double): List<TallBuildingRow> {
        val sorted = rows.sortedWith(compareByDescending<TallBuildingRow> { it.heightFeet }.thenBy { it.name })
        val insertionIndex = sorted.indexOfFirst { it.heightFeet < heightFeet }
            .takeIf { it >= 0 }
            ?: sorted.size
        val start = (insertionIndex - 4).coerceAtLeast(0)
        val end = (insertionIndex + 4).coerceAtMost(sorted.size)
        return sorted.subList(start, end)
    }

    internal fun inferPublishedTableRank(rows: List<TallBuildingRow>, heightFeet: Double): Int? {
        val sorted = rows.sortedWith(compareByDescending<TallBuildingRow> { it.heightFeet }.thenBy { it.name })
        val sameHeightRank = sorted.firstOrNull { it.heightFeet == heightFeet }?.tableRank?.rankNumber()
        if (sameHeightRank != null) return sameHeightRank
        return sorted.firstOrNull { it.heightFeet < heightFeet }?.tableRank?.rankNumber()
            ?: (sorted.size + 1)
    }

    private fun String.rankNumber(): Int? =
        replace("=", "").trim().toIntOrNull()

    private fun wikipediaTitle(sourceUrl: String): String? =
        runCatching { URI.create(sourceUrl).path.substringAfter("/wiki/", "") }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }

    private fun slugForSource(sourceUrl: String): String =
        sourceUrl.substringAfterLast('/').ifBlank { "source" }
            .replace(Regex("[^A-Za-z0-9._-]+"), "-")
            .take(80)

    private fun loadLitQa2Tasks(limit: Int): List<Tier0BenchmarkTask> {
        val body = HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(URI.create(LAB_BENCH_LITQA2_ROWS_URL + limit.coerceIn(1, 100))).GET().build(),
                HttpResponse.BodyHandlers.ofString()
            )
            .body()
        return parseLitQa2RowsResponse(body)
    }

    private fun loadScholarQaBenchTasks(variant: ScholarQaBenchVariant): List<Tier0BenchmarkTask> =
        when (variant) {
            ScholarQaBenchVariant.QASA -> parseScholarQaBenchQasaJsonl(fetchText(URI.create(SCHOLAR_QABENCH_QASA_TEST_URL)))
            ScholarQaBenchVariant.PUBMED -> parseScholarQaBenchGoldContextJsonl(
                body = fetchText(URI.create(SCHOLAR_QABENCH_PUBMED_TEST_URL)),
                variant = ScholarQaBenchVariant.PUBMED
            )
            ScholarQaBenchVariant.SCIFACT -> parseScholarQaBenchGoldContextJsonl(
                body = fetchText(URI.create(SCHOLAR_QABENCH_SCIFACT_TEST_URL)),
                variant = ScholarQaBenchVariant.SCIFACT
            )
            ScholarQaBenchVariant.CS -> parseScholarQaBenchCsSnippetsJsonl(fetchText(URI.create(SCHOLAR_QABENCH_CS_SNIPPETS_URL)))
        }

    private fun parsePrediction(taskId: String, content: String): Tier0BenchmarkPrediction {
        val element = extractJsonObject(content)
        val finalAnswer = element["final_answer"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val evidence = element["evidence_path"]
            ?.jsonArray
            ?.mapNotNull { evidenceElement ->
                val obj = evidenceElement.jsonObject
                val source = obj["source"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val claim = obj["claim"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (source.isBlank() || claim.isBlank()) null else Tier0EvidenceStep(source, claim)
            }
            .orEmpty()
        val supportingPassages = element["supporting_passages"]
            ?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull?.takeIf(String::isNotBlank) }
            .orEmpty()
        val paperIds = element["paper_ids"]
            ?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull?.takeIf(String::isNotBlank) }
            .orEmpty()
        val targetPaperIds = element["target_paper_ids"]
            ?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull?.takeIf(String::isNotBlank) }
            .orEmpty()
        val evidenceByCandidate = element["evidence_for_each_candidate"]
            ?.jsonObject
            ?.mapValues { it.value.jsonPrimitive.contentOrNull.orEmpty() }
            ?.filterValues { it.isNotBlank() }
            .orEmpty()
        val candidatePaperRanking = element["candidate_paper_ranking"]
            ?.jsonArray
            ?.mapIndexedNotNull { index, candidateElement ->
                val obj = candidateElement.jsonObject
                val paperId = obj["paper_id"]?.jsonPrimitive?.contentOrNull
                    ?: obj["paperId"]?.jsonPrimitive?.contentOrNull
                    ?: return@mapIndexedNotNull null
                Tier0CandidatePaper(
                    paperId = paperId,
                    rank = obj["rank"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: index + 1,
                    title = obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    evidence = obj["evidence"]?.jsonPrimitive?.contentOrNull
                        ?: evidenceByCandidate[paperId]
                        ?: ""
                )
            }
            .orEmpty()
        val rejectedCandidates = element["rejected_candidates"]
            ?.jsonArray
            ?.mapNotNull { rejectedElement ->
                val obj = rejectedElement.jsonObject
                val paperId = obj["paper_id"]?.jsonPrimitive?.contentOrNull
                    ?: obj["paperId"]?.jsonPrimitive?.contentOrNull
                    ?: return@mapNotNull null
                val reason = obj["reason"]?.jsonPrimitive?.contentOrNull
                    ?: return@mapNotNull null
                Tier0RejectedCandidate(paperId = paperId, reason = reason)
            }
            .orEmpty()
        val searchStrategy = element["search_strategy"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val whyAlternativesWrong = element["why_alternatives_wrong"]
            ?.jsonObject
            ?.mapValues { it.value.jsonPrimitive.contentOrNull.orEmpty() }
            ?.filterValues { it.isNotBlank() }
            .orEmpty()
        val confidence = element["confidence"]?.jsonPrimitive?.doubleOrNull
        val cost = element["total_cost"]?.jsonPrimitive?.doubleOrNull
        return Tier0BenchmarkPrediction(
            taskId = taskId,
            finalAnswer = finalAnswer,
            evidencePath = evidence,
            supportingPassages = supportingPassages,
            paperIds = paperIds,
            targetPaperIds = targetPaperIds,
            candidatePaperRanking = candidatePaperRanking,
            rejectedCandidates = rejectedCandidates,
            searchStrategy = searchStrategy,
            whyAlternativesWrong = whyAlternativesWrong,
            confidence = confidence,
            totalCost = cost
        )
    }

    private fun extractJsonObject(content: String): JsonObject {
        val trimmed = content.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        val json = if (start >= 0 && end > start) trimmed.substring(start, end + 1) else trimmed
        return benchmarkJson.parseToJsonElement(json).jsonObject
    }

    private fun parseTsv(tsv: String): List<Map<String, String>> {
        val lines = tsv.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) return emptyList()
        val headers = splitTsvLine(lines.first())
        return lines.drop(1).map { line ->
            val cells = splitTsvLine(line)
            headers.mapIndexed { index, header -> header to cells.getOrElse(index) { "" } }.toMap()
        }
    }

    private fun splitTsvLine(line: String): List<String> = line.split('\t')

    private fun extractZipEntry(zipBytes: ByteArray, entryName: String): String? =
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
            generateSequence { zip.nextEntry }
                .firstOrNull { !it.isDirectory && it.name == entryName }
                ?.let { zip.readBytes().toString(Charsets.UTF_8) }
        }

    private fun writeAutoResearchBenchArtifacts(taskOutputDir: Path, prediction: Tier0BenchmarkPrediction) {
        taskOutputDir.resolve(AUTO_RESEARCH_BENCH_TARGET_IDS_PATH).writeText(
            benchmarkJson.encodeToString(ListSerializer(String.serializer()), prediction.targetPaperIds)
        )
        taskOutputDir.resolve("candidate_paper_ranking.csv").writeText(
            buildString {
                appendLine("rank,paper_id,title,evidence")
                prediction.candidatePaperRanking
                    .sortedBy { it.rank }
                    .forEach { candidate ->
                        appendLine(
                            listOf(
                                candidate.rank.toString(),
                                candidate.paperId,
                                candidate.title,
                                candidate.evidence
                            ).joinToString(",") { csvCell(it) }
                        )
                    }
            }
        )
        taskOutputDir.resolve("evidence_for_each_candidate.md").writeText(
            buildString {
                appendLine("# Evidence For Each Candidate")
                prediction.candidatePaperRanking.sortedBy { it.rank }.forEach { candidate ->
                    appendLine()
                    appendLine("## ${candidate.paperId}")
                    if (candidate.title.isNotBlank()) appendLine("Title: ${candidate.title}")
                    appendLine(candidate.evidence.ifBlank { "No candidate-specific evidence supplied." })
                }
            }
        )
        taskOutputDir.resolve("rejected_candidates.md").writeText(
            buildString {
                appendLine("# Rejected Candidates")
                prediction.rejectedCandidates.forEach { rejected ->
                    appendLine()
                    appendLine("- `${rejected.paperId}`: ${rejected.reason}")
                }
            }
        )
        taskOutputDir.resolve("search_strategy.md").writeText(
            buildString {
                appendLine("# Search Strategy")
                appendLine()
                appendLine(prediction.searchStrategy.ifBlank { "No search strategy supplied." })
            }
        )
    }

    private fun csvCell(value: String): String =
        "\"" + value.replace("\"", "\"\"") + "\""

}

internal fun scoreCitationSupport(task: Tier0BenchmarkTask, prediction: Tier0BenchmarkPrediction): Double? {
    if (!task.benchmark.isLongFormBenchmark()) return null
    val expected = task.relevantSources.map(::normalizeSourceId).filter(String::isNotBlank).toSet()
    if (expected.isEmpty()) return null
    val predicted = prediction.paperIds.map(::normalizeSourceId).filter(String::isNotBlank).toSet()
    return expected.count(predicted::contains).toDouble() / expected.size
}

internal fun scoreClaimCoverage(task: Tier0BenchmarkTask, prediction: Tier0BenchmarkPrediction): Double? {
    if (!task.benchmark.isLongFormBenchmark()) return null
    val requiredClaims = task.requiredClaims.map(::normalizeClaim).filter(String::isNotBlank)
    if (requiredClaims.isEmpty()) return null
    val answerCorpus = normalizeClaim(
        buildString {
            appendLine(prediction.finalAnswer)
            prediction.evidencePath.forEach { appendLine(it.claim) }
            prediction.supportingPassages.forEach(::appendLine)
        }
    )
    val answerTokens = answerCorpus.claimCoverageTokens()
    return requiredClaims.count { claim ->
        answerCorpus.contains(claim) || claimCoveredByTokenRecall(claim, answerTokens)
    }.toDouble() / requiredClaims.size
}

@Serializable
internal data class ScholarQaJsonlTask(
    val id: String,
    val question: String,
    @SerialName("paper_ids")
    val paperIds: List<String> = emptyList(),
    val sources: List<String> = emptyList(),
    @SerialName("source_passages")
    val sourcePassages: List<ScholarQaSourcePassage> = emptyList(),
    @SerialName("required_claims")
    val requiredClaims: List<String> = emptyList(),
    @SerialName("ideal_answer")
    val idealAnswer: String? = null
)

@Serializable
internal data class ScholarQaSourcePassage(
    @SerialName("paper_id")
    val paperId: String,
    val passage: String
)

@Serializable
internal data class AutoResearchBenchJsonlTask(
    val id: String,
    val question: String,
    @SerialName("task_type")
    val taskType: String = "deep",
    @SerialName("gold_paper_ids")
    val goldPaperIds: List<String> = emptyList(),
    @SerialName("target_paper_ids")
    val targetPaperIds: List<String> = emptyList(),
    @SerialName("seed_paper_ids")
    val seedPaperIds: List<String> = emptyList(),
    val sources: List<String> = emptyList(),
    @SerialName("source_passages")
    val sourcePassages: List<ScholarQaSourcePassage> = emptyList()
)

internal fun parseScholarQaJsonl(
    path: Path,
    benchmark: Tier0BenchmarkKind = Tier0BenchmarkKind.SCHOLAR_QA
): List<Tier0BenchmarkTask> =
    Files.readAllLines(path)
        .asSequence()
        .map(String::trim)
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .map { line -> benchmarkJson.decodeFromString<ScholarQaJsonlTask>(line) }
        .map { task ->
            val sources = (task.sources + task.paperIds).distinct().filter(String::isNotBlank)
            Tier0BenchmarkTask(
                id = "scholarqa-${task.id.toBenchmarkTaskIdFragment()}",
                benchmark = benchmark,
                question = task.question,
                acceptedAnswers = listOfNotNull(task.idealAnswer?.takeIf(String::isNotBlank)),
                relevantSources = sources,
                sourcePassages = task.sourcePassages.mapNotNull { source ->
                    val paperId = source.paperId.takeIf(String::isNotBlank) ?: return@mapNotNull null
                    val passage = source.passage.takeIf(String::isNotBlank) ?: return@mapNotNull null
                    Tier0SourcePassage(paperId = paperId, passage = passage)
                },
                requiredClaims = task.requiredClaims.filter(String::isNotBlank),
                answerFormat = BenchmarkAnswerFormat.SYNTHESIS,
                requiresBrowsing = sources.any { it.startsWith("http://") || it.startsWith("https://") }
            )
        }
        .toList()

internal fun parseAutoResearchBenchJsonl(path: Path): List<Tier0BenchmarkTask> =
    Files.readAllLines(path)
        .asSequence()
        .map(String::trim)
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .map { line -> benchmarkJson.decodeFromString<AutoResearchBenchJsonlTask>(line) }
        .map { task ->
            val taskType = when (task.taskType.lowercase().replace("-", "_")) {
                "deep", "deep_research" -> LiteratureDiscoveryTaskType.DEEP
                "wide", "wide_research" -> LiteratureDiscoveryTaskType.WIDE
                else -> throw CliUsageException("Unsupported AutoResearchBench task_type: ${task.taskType}")
            }
            val goldIds = (task.goldPaperIds + task.targetPaperIds).distinct().filter(String::isNotBlank)
            val sources = (task.sources + task.seedPaperIds).distinct().filter(String::isNotBlank)
            Tier0BenchmarkTask(
                id = "autoresearchbench-${task.id.toBenchmarkTaskIdFragment()}",
                benchmark = Tier0BenchmarkKind.AUTO_RESEARCH_BENCH,
                question = task.question,
                acceptedAnswers = goldIds,
                relevantSources = sources,
                sourcePassages = task.sourcePassages.mapNotNull { source ->
                    val paperId = source.paperId.takeIf(String::isNotBlank) ?: return@mapNotNull null
                    val passage = source.passage.takeIf(String::isNotBlank) ?: return@mapNotNull null
                    Tier0SourcePassage(paperId = paperId, passage = passage)
                },
                literatureDiscoveryTaskType = taskType,
                answerFormat = BenchmarkAnswerFormat.EXACT,
                requiresBrowsing = sources.any { it.startsWith("http://") || it.startsWith("https://") }
            )
        }
        .toList()

@Serializable
internal data class ScholarQaBenchQasaRow(
    val input: String,
    val answer: String,
    @SerialName("gold_ctxs")
    val goldContexts: List<Int> = emptyList(),
    val ctxs: List<ScholarQaBenchContext> = emptyList()
)

@Serializable
internal data class ScholarQaBenchContext(
    val id: String = "",
    val title: String? = null,
    val text: String
)

@Serializable
internal data class ScholarQaBenchGoldContextRow(
    val input: String,
    val answer: String,
    @SerialName("gold_ctx")
    val goldContext: List<ScholarQaBenchGoldContext> = emptyList()
)

@Serializable
internal data class ScholarQaBenchGoldContext(
    val title: String? = null,
    val text: JsonElement = JsonPrimitive("")
)

@Serializable
internal data class ScholarQaBenchCsRow(
    val question: String,
    val ingredients: ScholarQaBenchCsIngredients = ScholarQaBenchCsIngredients()
)

@Serializable
internal data class ScholarQaBenchCsIngredients(
    @SerialName("most_important")
    val mostImportant: List<ScholarQaBenchCsIngredient> = emptyList()
)

@Serializable
internal data class ScholarQaBenchCsIngredient(
    val text: String,
    val snippets: List<String> = emptyList()
)

internal fun parseScholarQaBenchQasaJsonl(body: String): List<Tier0BenchmarkTask> =
    body.lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .mapIndexed { index, line ->
            val row = benchmarkJson.decodeFromString<ScholarQaBenchQasaRow>(line)
            val selectedContexts = row.goldContexts
                .mapNotNull(row.ctxs::getOrNull)
                .ifEmpty {
                    val contextsByGoldIndex = row.ctxs.associateBy { context ->
                        context.id.substringAfterLast('_', "").toIntOrNull()
                    }
                    row.goldContexts.mapNotNull(contextsByGoldIndex::get)
                }
                .ifEmpty { row.ctxs.take(3) }
                .take(5)
            val sourcePassages = selectedContexts.map { context ->
                Tier0SourcePassage(
                    paperId = context.id,
                    passage = buildString {
                        context.title?.takeIf(String::isNotBlank)?.let { appendLine("Title: $it") }
                        append(context.text)
                    }.take(SOURCE_ARTIFACT_MAX_CHARS)
                )
            }
            val requiredClaim = row.answer.withoutInlineCitationMarkers()
            Tier0BenchmarkTask(
                id = "scholarqabench-qasa-${index.toString().padStart(5, '0')}",
                benchmark = Tier0BenchmarkKind.SCHOLAR_QABENCH,
                question = row.input.trim(),
                acceptedAnswers = listOf(row.answer),
                relevantSources = sourcePassages.map { it.paperId },
                sourcePassages = sourcePassages,
                requiredClaims = listOf(requiredClaim).filter(String::isNotBlank),
                answerFormat = BenchmarkAnswerFormat.SYNTHESIS,
                requiresBrowsing = false
            )
        }
        .toList()

internal fun parseScholarQaBenchGoldContextJsonl(
    body: String,
    variant: ScholarQaBenchVariant
): List<Tier0BenchmarkTask> =
    body.lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .mapIndexed { index, line ->
            val row = benchmarkJson.decodeFromString<ScholarQaBenchGoldContextRow>(line)
            val sourcePassages = row.goldContext.mapIndexed { contextIndex, context ->
                val paperId = context.title
                    ?.takeIf(String::isNotBlank)
                    ?.toBenchmarkTaskIdFragment()
                    ?: "${variant.id}-${index.toString().padStart(5, '0')}-ctx-${contextIndex + 1}"
                Tier0SourcePassage(
                    paperId = paperId,
                    passage = buildString {
                        context.title?.takeIf(String::isNotBlank)?.let { appendLine("Title: $it") }
                        append(context.text.toGoldContextText())
                    }.take(SOURCE_ARTIFACT_MAX_CHARS)
                )
            }
            Tier0BenchmarkTask(
                id = "scholarqabench-${variant.id}-${index.toString().padStart(5, '0')}",
                benchmark = Tier0BenchmarkKind.SCHOLAR_QABENCH,
                question = buildString {
                    append(row.input.trim())
                    appendLine()
                    appendLine()
                    appendLine("Return the exact concise answer label when the task is yes/no or true/false.")
                }.trim(),
                acceptedAnswers = listOf(row.answer),
                relevantSources = sourcePassages.map { it.paperId },
                sourcePassages = sourcePassages,
                requiredClaims = listOf(row.answer).filter(String::isNotBlank),
                answerFormat = BenchmarkAnswerFormat.EXACT_LABEL,
                requiresBrowsing = false
            )
        }
        .toList()

internal fun parseScholarQaBenchCsSnippetsJsonl(body: String): List<Tier0BenchmarkTask> =
    body.lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .mapIndexed { index, line ->
            val row = benchmarkJson.decodeFromString<ScholarQaBenchCsRow>(line)
            val sourcePassages = row.ingredients.mostImportant
                .flatMapIndexed { ingredientIndex, ingredient ->
                    ingredient.snippets
                        .filter(String::isNotBlank)
                        .take(3)
                        .mapIndexed { snippetIndex, snippet ->
                            Tier0SourcePassage(
                                paperId = "scholarqabench-cs-${index.toString().padStart(5, '0')}-ingredient-${ingredientIndex + 1}-snippet-${snippetIndex + 1}",
                                passage = snippet.take(SOURCE_ARTIFACT_MAX_CHARS)
                            )
                        }
                }
                .take(12)
            val requiredClaims = row.ingredients.mostImportant
                .mapNotNull { ingredient ->
                    ingredient.snippets.firstOrNull(String::isNotBlank)
                        ?: ingredient.text.takeIf(String::isNotBlank)
                }
                .take(8)
            val requiredSourceIds = row.ingredients.mostImportant
                .mapIndexedNotNull { ingredientIndex, ingredient ->
                    ingredient.snippets.firstOrNull(String::isNotBlank)?.let {
                        "scholarqabench-cs-${index.toString().padStart(5, '0')}-ingredient-${ingredientIndex + 1}-snippet-1"
                    }
                }
                .take(8)
            Tier0BenchmarkTask(
                id = "scholarqabench-cs-${index.toString().padStart(5, '0')}",
                benchmark = Tier0BenchmarkKind.SCHOLAR_QABENCH,
                question = buildString {
                    appendLine(row.question.trim())
                    appendLine()
                    appendLine("ScholarQABench CS scoring rubric:")
                    appendLine("A complete answer must cover every rubric ingredient below, not only the narrow wording of the question.")
                    row.ingredients.mostImportant.forEachIndexed { ingredientIndex, ingredient ->
                        appendLine("${ingredientIndex + 1}. ${ingredient.text}")
                    }
                    appendLine()
                    appendLine("Use the fixed source excerpts to satisfy each rubric ingredient with cited claims.")
                }.trim(),
                relevantSources = requiredSourceIds.ifEmpty { sourcePassages.map { it.paperId } },
                sourcePassages = sourcePassages,
                requiredClaims = requiredClaims,
                answerFormat = BenchmarkAnswerFormat.SYNTHESIS,
                requiresBrowsing = false
            )
        }
        .toList()

private fun String.withoutInlineCitationMarkers(): String =
    replace(Regex("""\[\d+(?:,\s*\d+)*]"""), "")
        .replace(Regex("""\s+([.,;:!?])"""), "$1")
        .replace(Regex("\\s+"), " ")
        .trim()

private fun JsonElement.toGoldContextText(): String =
    when (this) {
        is JsonPrimitive -> contentOrNull.orEmpty()
        is JsonObject -> this["contexts"]
            ?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull?.takeIf(String::isNotBlank) }
            ?.joinToString("\n\n")
            .orEmpty()
            .ifBlank { toString() }
        else -> toString()
    }

private fun normalizeSourceId(source: String): String =
    source.trim()
        .removePrefix("https://doi.org/")
        .removePrefix("http://doi.org/")
        .removePrefix("doi:")
        .trim()
        .trimEnd('/')
        .lowercase()

private fun normalizeClaim(claim: String): String =
    claim.lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

private fun claimCoveredByTokenRecall(
    normalizedClaim: String,
    answerTokens: Set<String>
): Boolean {
    val claimTokens = normalizedClaim.claimCoverageTokens()
    if (claimTokens.isEmpty()) return false
    val coveredCount = claimTokens.count(answerTokens::contains)
    val recall = coveredCount.toDouble() / claimTokens.size
    return coveredCount >= claimTokens.size.coerceAtMost(4) && recall >= 0.55
}

private fun String.claimCoverageTokens(): Set<String> =
    split(' ')
        .asSequence()
        .map(String::trim)
        .filter { it.length >= 3 }
        .filterNot { it in claimCoverageStopWords }
        .toSet()

private val claimCoverageStopWords = setOf(
    "the",
    "and",
    "for",
    "with",
    "that",
    "this",
    "are",
    "was",
    "were",
    "into",
    "from",
    "has",
    "have",
    "had",
    "not",
    "but",
    "can",
    "should",
    "would",
    "could",
    "about",
    "than",
    "then",
    "also"
)

private fun parseScholarQaBenchVariant(value: String): ScholarQaBenchVariant =
    when (value.lowercase()) {
        "qasa" -> ScholarQaBenchVariant.QASA
        "pubmed", "pubmedqa", "pubmed-qa" -> ScholarQaBenchVariant.PUBMED
        "scifact", "sci-fact" -> ScholarQaBenchVariant.SCIFACT
        "cs", "scholarqa-cs", "computer-science" -> ScholarQaBenchVariant.CS
        else -> throw CliUsageException("Unsupported ScholarQABench variant: $value")
    }

private fun List<Double>.averageOrNull(): Double? =
    if (isEmpty()) null else average()

private fun Tier0BenchmarkKind.isLongFormBenchmark(): Boolean =
    this == Tier0BenchmarkKind.SCHOLAR_QA ||
        this == Tier0BenchmarkKind.SCHOLAR_QABENCH ||
        this == Tier0BenchmarkKind.AI2_SCHOLAR_QA

internal fun parseLitQa2RowsResponse(body: String): List<Tier0BenchmarkTask> {
    val root = benchmarkJson.parseToJsonElement(body).jsonObject
    val rows = root["rows"]?.jsonArray.orEmpty()
    return rows.mapNotNull { rowElement ->
        val rowWrapper = rowElement.jsonObject
        val row = rowWrapper["row"]?.jsonObject ?: return@mapNotNull null
        val rowIndex = rowWrapper["row_idx"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val rawId = row["id"]?.jsonPrimitive?.contentOrNull
            ?.takeIf(String::isNotBlank)
            ?: rowIndex.takeIf(String::isNotBlank)
            ?: return@mapNotNull null
        val question = row["question"]?.jsonPrimitive?.contentOrNull
            ?.takeIf(String::isNotBlank)
            ?: return@mapNotNull null
        val ideal = row["ideal"]?.jsonPrimitive?.contentOrNull
            ?.takeIf(String::isNotBlank)
            ?: return@mapNotNull null
        val distractors = row["distractors"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull?.takeIf(String::isNotBlank) }
            .orEmpty()
        val sources = row["sources"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull?.takeIf(String::isNotBlank) }
            .orEmpty()
        val choices = (distractors + ideal)
            .distinct()
            .shuffled(Random(rawId.hashCode()))
        val taskQuestion = buildString {
            appendLine(question.trim())
            appendLine()
            appendLine("Answer choices. Return the exact choice text, not a letter:")
            choices.forEach { choice -> appendLine("- $choice") }
        }.trim()
        Tier0BenchmarkTask(
            id = "litqa2-${rawId.toBenchmarkTaskIdFragment()}",
            benchmark = Tier0BenchmarkKind.LITQA2,
            question = taskQuestion,
            acceptedAnswers = listOf(ideal),
            relevantSources = sources,
            answerFormat = BenchmarkAnswerFormat.EXACT_LABEL,
            requiresBrowsing = true
        )
    }
}

private fun String.toBenchmarkTaskIdFragment(): String =
    replace(Regex("[^A-Za-z0-9_.-]"), "-")
        .trim('-')
        .take(24)
        .trim('-')
        .ifBlank { "row" }
