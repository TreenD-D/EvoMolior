package com.achulkov.evokammer.cli

import com.achulkov.evokammer.api.events.EvoKammerEvent
import com.achulkov.evokammer.api.model.RunConfig
import com.achulkov.evokammer.api.service.EvoKammerEngineService
import com.achulkov.evokammer.api.util.loadRunInputArtifacts
import com.achulkov.evokammer.core.config.FastModelSelectionConfig
import com.achulkov.evokammer.core.config.RunObjectiveHeuristics
import com.achulkov.evokammer.core.model.GroupProfileType
import com.achulkov.evokammer.core.model.ScoringDimension
import com.achulkov.evokammer.runtime.createProductionRuntime
import io.github.aakira.napier.Antilog
import io.github.aakira.napier.DebugAntilog
import io.github.aakira.napier.LogLevel
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.system.exitProcess

internal val cliJson = Json { prettyPrint = true }

/**
 * Entry point for the EvoKammer console runner.
 *
 * Usage:
 * - `-prompt "..."` or `-promptfile /path/to/file`
 * - `-input /path/to/folder`
 * - `-output /path/to/folder`
 * - optional `-openrouter-api-key`, `-debug`, and repeated `-model role=model`
 *
 * @param args Command-line arguments.
 */
fun main(args: Array<String>) {
    try {
        val routedArgs = when (args.firstOrNull()) {
            "self-improve" -> {
                runSelfImprovementCli(args.drop(1).toTypedArray())
                return
            }
            "run" -> args.drop(1).toTypedArray()
            else -> args
        }
        val options = CliArguments.parse(routedArgs) ?: run {
            println(CliArguments.usage())
            exitProcess(0)
        }
        val result = runCli(options)
        if (!result.completedSuccessfully) {
            exitProcess(1)
        }
    } catch (e: CliUsageException) {
        System.err.println(e.message)
        System.err.println()
        System.err.println(CliArguments.usage())
        exitProcess(2)
    } catch (e: Exception) {
        System.err.println("EvoKammer CLI failed: ${e.message}")
        e.printStackTrace(System.err)
        exitProcess(1)
    }
}

internal fun runCli(
    options: CliOptions,
    runtimeFactory: (File, CliOptions) -> CliRuntime = { dataRoot, cliOptions ->
        if (cliOptions.fast) {
            CliRuntime(
                createProductionRuntime(
                    dataRootDir = dataRoot,
                    councilModelId = FastModelSelectionConfig.FAST_COUNCIL_MODEL,
                    graderModelId = FastModelSelectionConfig.FAST_GRADER_MODEL,
                    criticPipelineModelId = FastModelSelectionConfig.FAST_CRITIC_PIPELINE_MODEL,
                    leakTransformModelId = FastModelSelectionConfig.FAST_LEAK_TRANSFORM_MODEL
                ).engineService
            )
        } else {
            CliRuntime(createProductionRuntime(dataRoot).engineService)
        }
    }
): CliRunResult = runBlocking {
    runCliPipeline(options, runtimeFactory)
}

internal data class CliRunResult(
    val completedSuccessfully: Boolean
)

internal data class CliObjectiveProfile(
    val maxAgentsPerGroup: Int,
    val scoringDimensions: List<ScoringDimension>,
    val budgetOverride: Double
)

internal fun buildCliObjectiveProfile(
    objective: String,
    fastMode: Boolean
): CliObjectiveProfile {
    if (!fastMode) {
        return CliObjectiveProfile(
            maxAgentsPerGroup = 1,
            scoringDimensions = defaultCliScoringDimensions(),
            budgetOverride = 200.0
        )
    }

    return if (RunObjectiveHeuristics.requestsLongFormArticle(objective)) {
        CliObjectiveProfile(
            maxAgentsPerGroup = 2,
            scoringDimensions = browserArticleScoringDimensions(),
            budgetOverride = 220.0
        )
    } else {
        CliObjectiveProfile(
            maxAgentsPerGroup = 1,
            scoringDimensions = defaultCliScoringDimensions(),
            budgetOverride = 150.0
        )
    }
}

internal fun defaultCliScoringDimensions(): List<ScoringDimension> = listOf(
    ScoringDimension(
        id = "accuracy",
        name = "Accuracy",
        description = "Reward evidence-based evaluation.",
        weight = 1.0
    )
)

internal fun browserArticleScoringDimensions(): List<ScoringDimension> = listOf(
    ScoringDimension(
        id = "content_depth",
        name = "Content Depth",
        description = "Reward substantial, specific, insight-rich long-form content rather than thin summary text.",
        weight = 0.30
    ),
    ScoringDimension(
        id = "semantic_html",
        name = "Semantic HTML",
        description = "Reward valid standalone HTML5 structure, metadata, accessibility, and semantic sectioning.",
        weight = 0.20
    ),
    ScoringDimension(
        id = "visual_polish",
        name = "Visual Polish",
        description = "Reward embedded styling, hierarchy, readability, and a publication-ready presentation.",
        weight = 0.15
    ),
    ScoringDimension(
        id = "product_positioning",
        name = "Product Positioning",
        description = "Reward clear articulation of audience pain points, product value, and persuasive CTA quality.",
        weight = 0.20
    ),
    ScoringDimension(
        id = "evidence_fidelity",
        name = "Evidence Fidelity",
        description = "Reward grounded claims, internal consistency, and faithful synthesis of run knowledge over empty flourish.",
        weight = 0.15
    )
)

internal data class CliOptions(
    val prompt: String,
    val inputDir: Path,
    val outputDir: Path,
    val openRouterApiKey: String,
    val modelOverrides: Map<String, String>,
    val debug: Boolean,
    val fast: Boolean,
    val rounds: Int?,
    val groupCount: Int? = null,
    val maxAgentsPerGroup: Int? = null,
    val budgetOverride: Double? = null,
    val runId: String? = null,
    val pipelineConfigPath: Path? = null
)

internal data class CliRuntime(
    val engineService: EvoKammerEngineService
)

@kotlinx.serialization.Serializable
internal data class CliRunSummary(
    val runId: String,
    val prompt: String,
    val inputDirectory: String,
    val inputArtifactCount: Int,
    val outputDirectory: String,
    val completedAt: String,
    val durationMs: Long,
    val debugLogPath: String?,
    val resultsBundlePath: String?,
    val status: String,
    val winnerId: String?,
    val modelOverrides: Map<String, String>
)

internal object CliArguments {
    fun parse(args: Array<String>): CliOptions? {
        if (args.any { it == "-h" || it == "--help" }) {
            return null
        }
        if (args.isEmpty()) {
            throw CliUsageException(usage())
        }

        var prompt: String? = null
        var promptFile: Path? = null
        var inputDir: Path? = null
        var outputDir: Path? = null
        var openRouterApiKey: String? = null
        var debug = false
        var fast = false
        var rounds: Int? = null
        var groupCount: Int? = null
        var maxAgentsPerGroup: Int? = null
        var budgetOverride: Double? = null
        var runId: String? = null
        var pipelineConfigPath: Path? = null
        val modelOverrides = linkedMapOf<String, String>()

        var index = 0
        while (index < args.size) {
            when (val arg = args[index]) {
                "-prompt" -> {
                    prompt = args.getOrNull(++index) ?: throw CliUsageException("Missing value for -prompt")
                }
                "-promptfile" -> {
                    val value = args.getOrNull(++index) ?: throw CliUsageException("Missing value for -promptfile")
                    promptFile = Path.of(value)
                }
                "-input" -> {
                    val value = args.getOrNull(++index) ?: throw CliUsageException("Missing value for -input")
                    inputDir = Path.of(value)
                }
                "-output" -> {
                    val value = args.getOrNull(++index) ?: throw CliUsageException("Missing value for -output")
                    outputDir = Path.of(value)
                }
                "-openrouter-api-key", "--openrouter-api-key" -> {
                    openRouterApiKey = args.getOrNull(++index) ?: throw CliUsageException("Missing value for $arg")
                }
                "-debug", "--debug" -> debug = true
                "-fast", "--fast" -> fast = true
                "-rounds", "--rounds" -> {
                    val value = args.getOrNull(++index) ?: throw CliUsageException("Missing value for $arg")
                    rounds = value.toIntOrNull()?.takeIf { it > 0 }
                        ?: throw CliUsageException("Rounds must be a positive integer, got: $value")
                }
                "-groupcount", "-group-count", "--group-count" -> {
                    val value = args.getOrNull(++index) ?: throw CliUsageException("Missing value for $arg")
                    groupCount = value.toIntOrNull()?.takeIf { it > 0 }
                        ?: throw CliUsageException("Group count must be a positive integer, got: $value")
                }
                "-maxagents", "-max-agents", "-max-agents-per-group", "--max-agents-per-group" -> {
                    val value = args.getOrNull(++index) ?: throw CliUsageException("Missing value for $arg")
                    maxAgentsPerGroup = value.toIntOrNull()?.takeIf { it > 0 }
                        ?: throw CliUsageException("Max agents per group must be a positive integer, got: $value")
                }
                "-budgetoverride", "-budget-override", "--budget-override" -> {
                    val value = args.getOrNull(++index) ?: throw CliUsageException("Missing value for $arg")
                    budgetOverride = value.toDoubleOrNull()?.takeIf { it > 0.0 }
                        ?: throw CliUsageException("Budget override must be a positive number, got: $value")
                }
                "-runid", "-run-id", "--run-id" -> {
                    runId = args.getOrNull(++index) ?: throw CliUsageException("Missing value for $arg")
                }
                "-pipelineconfig", "-pipeline-config", "--pipeline-config" -> {
                    val value = args.getOrNull(++index) ?: throw CliUsageException("Missing value for $arg")
                    pipelineConfigPath = Path.of(value)
                }
                "-model", "--model" -> {
                    val mapping = args.getOrNull(++index) ?: throw CliUsageException("Missing value for $arg")
                    val separatorIndex = mapping.indexOf('=')
                    if (separatorIndex <= 0 || separatorIndex == mapping.lastIndex) {
                        throw CliUsageException("Model override must be in the form role=model, got: $mapping")
                    }
                    val role = mapping.substring(0, separatorIndex).trim()
                    val model = mapping.substring(separatorIndex + 1).trim()
                    if (role.isBlank()) throw CliUsageException("Model override role cannot be blank")
                    if (model.isBlank()) throw CliUsageException("Model override value cannot be blank")
                    modelOverrides[role] = model
                }
                else -> throw CliUsageException("Unknown argument: $arg")
            }
            index++
        }

        val resolvedPrompt = when {
            prompt != null && promptFile != null -> throw CliUsageException("Specify either -prompt or -promptfile, not both")
            prompt != null -> prompt!!
            promptFile != null -> Files.readString(promptFile!!)
            else -> throw CliUsageException("You must provide either -prompt or -promptfile")
        }

        val resolvedInput = inputDir ?: throw CliUsageException("Missing required -input directory")
        val resolvedOutput = outputDir ?: throw CliUsageException("Missing required -output directory")
        if (!Files.exists(resolvedInput) || !Files.isDirectory(resolvedInput)) {
            throw CliUsageException("Input path must be an existing directory: $resolvedInput")
        }
        Files.createDirectories(resolvedOutput)

        val resolvedKey = openRouterApiKey
            ?: System.getenv("OPENROUTER_API_KEY")
            ?: throw CliUsageException("OpenRouter API key is required via -openrouter-api-key or OPENROUTER_API_KEY")

        if (fast) {
            val invalidFastModels = modelOverrides.values.filterNot { it in FastModelSelectionConfig.approvedModelIds }
            if (invalidFastModels.isNotEmpty()) {
                throw CliUsageException(
                    "Fast mode only allows these model IDs: ${FastModelSelectionConfig.approvedModelIds.joinToString(", ")}"
                )
            }
        }

        val resolvedRounds = rounds ?: if (fast) 5 else 1

        return CliOptions(
            prompt = resolvedPrompt,
            inputDir = resolvedInput.toAbsolutePath().normalize(),
            outputDir = resolvedOutput.toAbsolutePath().normalize(),
            openRouterApiKey = resolvedKey,
            modelOverrides = modelOverrides,
            debug = debug || fast,
            fast = fast,
            rounds = resolvedRounds,
            groupCount = groupCount,
            maxAgentsPerGroup = maxAgentsPerGroup,
            budgetOverride = budgetOverride,
            runId = runId,
            pipelineConfigPath = pipelineConfigPath?.toAbsolutePath()?.normalize()
        )
    }

    fun usage(): String = """
        EvoKammer console runner

        Required:
          -prompt "text" | -promptfile /path/to/file
          -input /path/to/input-folder
          -output /path/to/output-folder

        Optional:
          -fast
          -rounds N
          -group-count N
          -max-agents-per-group N
          -budget-override N
          -openrouter-api-key KEY
          -debug
          -run-id custom-run-id
          -pipeline-config /path/to/pipeline.json
          -model ROLE=MODEL   (repeatable)
    """.trimIndent()
}

internal class CliUsageException(message: String) : IllegalArgumentException(message)

internal class CompositeAntilog(
    private val delegates: List<Antilog>
) : Antilog() {
    override fun performLog(priority: LogLevel, tag: String?, throwable: Throwable?, message: String?) {
        delegates.forEach { it.log(priority, tag, throwable, message) }
    }
}

internal class FileAntilog(
    private val path: Path
) : Antilog(), AutoCloseable {
    private val lock = Any()
    @Volatile
    private var closed = false
    private val writer = Files.newBufferedWriter(
        path,
        Charsets.UTF_8,
        java.nio.file.StandardOpenOption.CREATE,
        java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
        java.nio.file.StandardOpenOption.WRITE
    )

    override fun performLog(priority: LogLevel, tag: String?, throwable: Throwable?, message: String?) {
        val text = buildString {
            append('[')
            append(priority.name)
            append("] ")
            if (!tag.isNullOrBlank()) {
                append(tag)
                append(" - ")
            }
            if (!message.isNullOrBlank()) {
                append(message)
            }
            if (throwable != null) {
                if (isNotEmpty()) appendLine()
                append(throwable.stackTraceToString())
            }
        }
        synchronized(lock) {
            if (closed) return
            writer.write(text)
            writer.newLine()
            writer.flush()
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            writer.flush()
            writer.close()
        }
    }
}
