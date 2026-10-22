package com.achulkov.evomolior.cli

import com.achulkov.evomolior.api.events.EvoMoliorEvent
import com.achulkov.evomolior.api.model.RunConfig
import com.achulkov.evomolior.api.service.RunOutcomePresentation
import com.achulkov.evomolior.api.model.RunAutoCompletionPolicy
import com.achulkov.evomolior.api.model.AcceptanceDiagnosticMode
import com.achulkov.evomolior.api.service.EvoMoliorEngineService
import com.achulkov.evomolior.api.service.AcceptanceRunBoundaryVerifier
import com.achulkov.evomolior.api.util.loadRunInputArtifacts
import com.achulkov.evomolior.cli.remote.RemoteDashboardConfig
import com.achulkov.evomolior.cli.remote.RemoteDashboardServer
import com.achulkov.evomolior.core.config.AcceptanceProfile
import com.achulkov.evomolior.core.config.CodexReasoningEffort
import com.achulkov.evomolior.core.config.CodexRuntimePreset
import com.achulkov.evomolior.core.config.FastModelSelectionConfig
import com.achulkov.evomolior.core.config.CouncilExecutionPurpose
import com.achulkov.evomolior.core.config.LlmBackend
import com.achulkov.evomolior.core.config.ModelSelectionConfig
import com.achulkov.evomolior.core.config.RunObjectiveHeuristics
import com.achulkov.evomolior.core.deliverable.AcceptanceStatus
import com.achulkov.evomolior.core.deliverable.ExecutionCompletionStatus
import com.achulkov.evomolior.core.deliverable.PrimaryArtifactValidationStatus
import com.achulkov.evomolior.core.model.GroupProfileType
import com.achulkov.evomolior.core.model.Role
import com.achulkov.evomolior.core.model.ScoringDimension
import com.achulkov.evomolior.runtime.createProductionRuntime
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
 * Entry point for the EvoMolior console runner.
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
        if (args.firstOrNull() == "daemon") {
            val exitCode = LocalRunDaemonCommand.run(args.drop(1).toTypedArray())
            if (exitCode != 0) exitProcess(exitCode)
            return
        }
        LocalRunDaemonClient.tryRun(args)?.let { result ->
            if (!result.completedSuccessfully) exitProcess(1)
            return
        }
        val routedArgs = when (args.firstOrNull()) {
            "self-improve" -> {
                runSelfImprovementCli(args.drop(1).toTypedArray())
                return
            }
            "benchmark" -> {
                runBenchmarkCli(args.drop(1).toTypedArray())
                return
            }
            "benchmark-compare" -> {
                runComparativeBenchmarkCli(args.drop(1).toTypedArray())
                return
            }
            "template" -> {
                val exitCode = ApplicationTemplates.run(args.drop(1))
                if (exitCode != 0) exitProcess(exitCode)
                return
            }
            "gptauth" -> {
                val authArgs = args.drop(1).toTypedArray()
                if (authArgs.any { it == "-h" || it == "--help" }) {
                    println(CodexAuthCommand.usage())
                    return
                }
                val exitCode = CodexAuthCommand.run(authArgs)
                if (exitCode != 0) exitProcess(exitCode)
                return
            }
            "serve" -> {
                val serveArgs = args.drop(1).toTypedArray()
                if (serveArgs.any { it == "-h" || it == "--help" }) {
                    println(RemoteDashboardConfig.usage())
                    return
                }
                runRemoteDashboardCli(serveArgs)
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
    } catch (e: CliStoragePreflightException) {
        System.err.println("EvoMolior CLI did not start: ${e.message}")
        exitProcess(1)
    } catch (e: Exception) {
        System.err.println("EvoMolior CLI failed: ${e.message}")
        e.printStackTrace(System.err)
        exitProcess(1)
    }
}

internal fun runRemoteDashboardCli(args: Array<String>) {
    val config = RemoteDashboardConfig.parse(args)
    RemoteDashboardServer(config).start()
}

internal fun runCli(
    options: CliOptions,
    runtimeFactory: (File, CliOptions) -> CliRuntime = { dataRoot, cliOptions ->
        if (cliOptions.fast) {
            val qwenFastUtilityModel = cliOptions.modelOverrides["MetaPlanner"]
                ?: ModelSelectionConfig.QWEN_CLOUD_QWEN_PLUS_MODEL
            val qwenFastCriticModel = cliOptions.modelOverrides["Critic"]
                ?: qwenFastUtilityModel
            val qwenFastOutlierModel = cliOptions.modelOverrides["Outlier"]
                ?: qwenFastUtilityModel
            val runtime = when (cliOptions.llmBackend) {
                LlmBackend.OPENROUTER -> createProductionRuntime(
                    dataRootDir = dataRoot,
                    llmBackend = cliOptions.llmBackend,
                    councilModelId = FastModelSelectionConfig.FAST_COUNCIL_MODEL,
                    graderModelId = FastModelSelectionConfig.FAST_GRADER_MODEL,
                    criticPipelineModelId = FastModelSelectionConfig.FAST_CRITIC_PIPELINE_MODEL,
                    leakTransformModelId = FastModelSelectionConfig.FAST_LEAK_TRANSFORM_MODEL,
                    acceptanceRunBoundaryVerifier = cliOptions.acceptanceRunBoundaryVerifier
                )
                LlmBackend.QWEN_CLOUD -> createProductionRuntime(
                    dataRootDir = dataRoot,
                    llmBackend = cliOptions.llmBackend,
                    councilModelId = qwenFastUtilityModel,
                    graderModelId = qwenFastCriticModel,
                    criticPipelineModelId = qwenFastCriticModel,
                    leakTransformModelId = qwenFastOutlierModel,
                    acceptanceRunBoundaryVerifier = cliOptions.acceptanceRunBoundaryVerifier
                )
                LlmBackend.CODEX_CHATGPT -> createProductionRuntime(
                    dataRootDir = dataRoot,
                    llmBackend = cliOptions.llmBackend,
                    codexRuntimePreset = cliOptions.codexRuntimePreset,
                    councilModelId = cliOptions.codexRuntimePreset?.defaultModel(Role.CouncilSynthesis)
                        ?: ModelSelectionConfig.CODEX_CHATGPT_GPT56_LUNA_MODEL,
                    graderModelId = cliOptions.codexRuntimePreset?.defaultModel(Role.GraderCritic)
                        ?: ModelSelectionConfig.CODEX_CHATGPT_GPT56_LUNA_MODEL,
                    criticPipelineModelId = cliOptions.codexRuntimePreset?.defaultModel(Role.Critic)
                        ?: ModelSelectionConfig.CODEX_CHATGPT_GPT56_LUNA_MODEL,
                    leakTransformModelId = cliOptions.codexRuntimePreset?.defaultModel(Role.Connector)
                        ?: ModelSelectionConfig.CODEX_CHATGPT_GPT56_LUNA_MODEL,
                    acceptanceRunBoundaryVerifier = cliOptions.acceptanceRunBoundaryVerifier
                )
            }
            CliRuntime(runtime.engineService)
        } else {
            CliRuntime(
                createProductionRuntime(
                    dataRoot,
                    llmBackend = cliOptions.llmBackend,
                    codexRuntimePreset = cliOptions.codexRuntimePreset,
                    acceptanceRunBoundaryVerifier = cliOptions.acceptanceRunBoundaryVerifier
                ).engineService
            )
        }
    },
    acceptanceAdmissionController: AcceptanceAdmissionController =
        AcceptanceAdmissionController.production(),
    acceptanceAdmissionDiagnosticSink: (String) -> Unit = System.err::println,
    storagePreflight: (CliOptions) -> Unit = ::verifyRunStorageBeforeStart
): CliRunResult = runBlocking {
    // Reject before Council or any provider call when a write volume is nearly full.
    storagePreflight(options)
    runCliPipeline(
        options,
        runtimeFactory,
        acceptanceAdmissionController,
        acceptanceAdmissionDiagnosticSink
    )
}

/**
 * Production storage admission for [runCli]; logs each observed volume and throws
 * [CliStoragePreflightException] when one is below the configured floor.
 *
 * @param options Parsed CLI options naming the output directory and backend.
 */
internal fun verifyRunStorageBeforeStart(options: CliOptions) {
    val statuses = RunStoragePreflight.verify(
        locations = RunStoragePreflight.locations(options),
        requiredFreeBytes = RunStoragePreflight.requiredFreeBytes()
    )
    statuses.forEach { status ->
        Napier.i(
            "EvoMolior CLI storage ${status.labels.joinToString(" + ")}: " +
                "${RunStoragePreflight.formatBytes(status.usableBytes)} usable at ${status.probePath}"
        )
    }
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
    val llmBackend: LlmBackend = LlmBackend.OPENROUTER,
    val modelOverrides: Map<String, String>,
    val debug: Boolean,
    val fast: Boolean,
    val rounds: Int?,
    val maxRounds: Int? = null,
    val completionStabilityRounds: Int =
        RunAutoCompletionPolicy.DEFAULT_CONSECUTIVE_ACCEPTED_ROUNDS,
    val groupCount: Int? = null,
    val maxAgentsPerGroup: Int? = null,
    /** Grader groups for normal runs (1-3); null keeps the production default of three. */
    val graderGroupCount: Int? = null,
    val roundDurationMinutes: Int? = null,
    val budgetOverride: Double? = null,
    val runId: String? = null,
    val pipelineConfigPath: Path? = null,
    val enforceProviderMonetaryCostCap: Boolean = true,
    val councilExecutionPurpose: CouncilExecutionPurpose = CouncilExecutionPurpose.STANDARD,
    val acceptanceProfile: AcceptanceProfile = AcceptanceProfile.NORMAL,
    val acceptanceDiagnosticMode: AcceptanceDiagnosticMode = AcceptanceDiagnosticMode.STRICT,
    val codexReasoningEffort: CodexReasoningEffort = CodexReasoningEffort.HIGH,
    val codexRuntimePreset: CodexRuntimePreset? = null,
    val acceptanceRunBoundaryVerifier: AcceptanceRunBoundaryVerifier =
        AcceptanceRunBoundaryVerifier.NONE
)

internal data class CliRuntime(
    val engineService: EvoMoliorEngineService
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
    val modelOverrides: Map<String, String>,
    val terminalReason: String? = null,
    val outcome: RunOutcomePresentation? = null,
    val executionStatus: ExecutionCompletionStatus? = null,
    val acceptanceStatus: AcceptanceStatus? = null,
    val primaryArtifactPath: String? = null,
    val primaryArtifactValidationStatus: PrimaryArtifactValidationStatus? = null,
    val processExitCode: Int? = null,
    /** SHA-256 of the stage request; see [cliStageRequestFingerprint]. Absent in older summaries. */
    val requestFingerprint: String? = null,
)

internal object CliArguments {
    fun parse(
        args: Array<String>,
        codexCliLocator: () -> String = {
            CodexAuthCommand.locateCli(
                sequenceOf(
                    readLocalProperty("EVOMOLIOR_CODEX_CLI"),
                    readLocalProperty(LlmBackend.CODEX_CLI_ENV_VAR)
                ).filterNotNull()
            )
        },
        codexSessionPreflight: (String) -> Unit = CodexAuthCommand::requireUsableSession,
        codexReasoningEffortEnvironment: () -> String? = {
            System.getenv(CodexReasoningEffort.ENV_VAR)
        }
    ): CliOptions? {
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
        var llmBackend = LlmBackend.OPENROUTER
        var debug = false
        var fast = false
        var rounds: Int? = null
        var maxRounds: Int? = null
        var completionStabilityRounds =
            RunAutoCompletionPolicy.DEFAULT_CONSECUTIVE_ACCEPTED_ROUNDS
        var completionStabilityExplicit = false
        var groupCount: Int? = null
        var graderGroupCount: Int? = null
        var maxAgentsPerGroup: Int? = null
        var roundDurationMinutes: Int? = null
        var budgetOverride: Double? = null
        var runId: String? = null
        var pipelineConfigPath: Path? = null
        var enforceProviderMonetaryCostCap = true
        var councilExecutionPurpose = CouncilExecutionPurpose.STANDARD
        var acceptanceProfile = AcceptanceProfile.NORMAL
        var acceptanceDiagnosticMode = AcceptanceDiagnosticMode.STRICT
        var explicitCodexReasoningEffort: String? = null
        var codexRuntimePreset: CodexRuntimePreset? = null
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
                "-qwen-api-key", "--qwen-api-key" -> {
                    openRouterApiKey = args.getOrNull(++index) ?: throw CliUsageException("Missing value for $arg")
                    llmBackend = LlmBackend.QWEN_CLOUD
                }
                "-llm-backend", "--llm-backend" -> {
                    val value = args.getOrNull(++index) ?: throw CliUsageException("Missing value for $arg")
                    llmBackend = LlmBackend.parse(value)
                }
                "-debug", "--debug" -> debug = true
                "-fast", "--fast" -> fast = true
                "-rounds", "--rounds" -> {
                    val value = args.getOrNull(++index) ?: throw CliUsageException("Missing value for $arg")
                    rounds = value.toIntOrNull()?.takeIf { it > 0 }
                        ?: throw CliUsageException("Rounds must be a positive integer, got: $value")
                }
                "-max-rounds", "--max-rounds" -> {
                    val value = args.getOrNull(++index)
                        ?: throw CliUsageException("Missing value for $arg")
                    maxRounds = value.toIntOrNull()?.takeIf { it > 0 }
                        ?: throw CliUsageException("Max rounds must be a positive integer, got: $value")
                }
                "-completion-stability-rounds", "--completion-stability-rounds" -> {
                    val value = args.getOrNull(++index)
                        ?: throw CliUsageException("Missing value for $arg")
                    completionStabilityRounds = value.toIntOrNull()
                        ?.takeIf {
                            it in RunAutoCompletionPolicy.MIN_CONSECUTIVE_ACCEPTED_ROUNDS..
                                RunAutoCompletionPolicy.MAX_CONSECUTIVE_ACCEPTED_ROUNDS
                        }
                        ?: throw CliUsageException(
                            "Completion stability rounds must be 2 or 3, got: $value"
                        )
                    completionStabilityExplicit = true
                }
                "-groupcount", "-group-count", "--group-count" -> {
                    val value = args.getOrNull(++index) ?: throw CliUsageException("Missing value for $arg")
                    groupCount = value.toIntOrNull()?.takeIf { it > 0 }
                        ?: throw CliUsageException("Group count must be a positive integer, got: $value")
                }
                "-grader-groups", "--grader-groups" -> {
                    val value = args.getOrNull(++index) ?: throw CliUsageException("Missing value for $arg")
                    graderGroupCount = value.toIntOrNull()?.takeIf { it in 1..MAX_OPERATOR_GRADER_GROUPS }
                        ?: throw CliUsageException(
                            "Grader groups must be an integer from 1 to $MAX_OPERATOR_GRADER_GROUPS, got: $value"
                        )
                }
                "-maxagents", "-max-agents", "-max-agents-per-group", "--max-agents-per-group" -> {
                    val value = args.getOrNull(++index) ?: throw CliUsageException("Missing value for $arg")
                    maxAgentsPerGroup = value.toIntOrNull()?.takeIf { it > 0 }
                        ?: throw CliUsageException("Max agents per group must be a positive integer, got: $value")
                }
                "-round-duration-minutes", "--round-duration-minutes" -> {
                    val value = args.getOrNull(++index) ?: throw CliUsageException("Missing value for $arg")
                    roundDurationMinutes = value.toIntOrNull()?.takeIf { it >= 0 }
                        ?: throw CliUsageException(
                            "Round duration minutes must be a non-negative integer " +
                                "(0 enables budget-led production rounds), got: $value"
                        )
                }
                "-budgetoverride", "-budget-override", "--budget-override" -> {
                    val value = args.getOrNull(++index) ?: throw CliUsageException("Missing value for $arg")
                    budgetOverride = value.toDoubleOrNull()?.takeIf { it > 0.0 }
                        ?: throw CliUsageException("Budget override must be a positive number, got: $value")
                }
                "-iso-latency", "--iso-latency" -> enforceProviderMonetaryCostCap = false
                "-council-execution-purpose", "--council-execution-purpose" -> {
                    val value = args.getOrNull(++index)
                        ?: throw CliUsageException("Missing value for $arg")
                    councilExecutionPurpose = try {
                        CouncilExecutionPurpose.parse(value)
                    } catch (error: IllegalArgumentException) {
                        throw CliUsageException(error.message.orEmpty())
                    }
                }
                "-acceptance-profile", "--acceptance-profile" -> {
                    val value = args.getOrNull(++index)
                        ?: throw CliUsageException("Missing value for $arg")
                    acceptanceProfile = try {
                        AcceptanceProfile.parse(value)
                    } catch (error: IllegalArgumentException) {
                        throw CliUsageException(error.message.orEmpty())
                    }
                }
                "-acceptance-diagnostic-sweep", "--acceptance-diagnostic-sweep" -> {
                    acceptanceDiagnosticMode =
                        AcceptanceDiagnosticMode.FULL_SWEEP_NON_ACCEPTING
                }
                "-codex-reasoning-effort", "--codex-reasoning-effort" -> {
                    explicitCodexReasoningEffort = args.getOrNull(++index)
                        ?: throw CliUsageException("Missing value for $arg")
                }
                "-codex-preset", "--codex-preset" -> {
                    val value = args.getOrNull(++index)
                        ?: throw CliUsageException("Missing value for $arg")
                    codexRuntimePreset = try {
                        CodexRuntimePreset.parse(value)
                    } catch (error: IllegalArgumentException) {
                        throw CliUsageException(error.message.orEmpty())
                    }
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

        validateCodexPreset(codexRuntimePreset, llmBackend, modelOverrides, acceptanceProfile)
        val resolvedKey = if (llmBackend == LlmBackend.CODEX_CHATGPT) {
            codexCliLocator()
        } else {
            openRouterApiKey
                ?: System.getenv(llmBackend.apiKeyEnvVar)
                ?: readLocalProperty(llmBackend.apiKeyEnvVar)
                ?: if (llmBackend == LlmBackend.OPENROUTER) System.getenv("OPENROUTER_API_KEY") else null
                ?: throw CliUsageException(
                    "${llmBackend.apiKeyEnvVar} is required for backend ${llmBackend.id} via CLI flag, environment, or local.properties"
                )
        }
        if (llmBackend != LlmBackend.CODEX_CHATGPT) {
            llmBackend.textRuntimeUnavailableReason()?.let { reason ->
                throw CliUsageException("LLM backend '${llmBackend.id}' is not available: $reason")
            }
        }
        if (llmBackend == LlmBackend.CODEX_CHATGPT) {
            codexSessionPreflight(resolvedKey)
        }
        if (
            councilExecutionPurpose == CouncilExecutionPurpose.APPENDIX_B_ACCEPTANCE &&
            llmBackend != LlmBackend.CODEX_CHATGPT
        ) {
            throw CliUsageException(
                "Council execution purpose '${councilExecutionPurpose.id}' requires " +
                    "the codex-chatgpt backend"
            )
        }

        if (
            acceptanceProfile != AcceptanceProfile.NORMAL &&
            councilExecutionPurpose != CouncilExecutionPurpose.APPENDIX_B_ACCEPTANCE
        ) {
            throw CliUsageException(
                "Acceptance profile '${acceptanceProfile.id}' requires " +
                    "-council-execution-purpose appendix-b-acceptance"
            )
        }
        if (
            acceptanceDiagnosticMode ==
            AcceptanceDiagnosticMode.FULL_SWEEP_NON_ACCEPTING &&
            acceptanceProfile == AcceptanceProfile.NORMAL
        ) {
            throw CliUsageException(
                "-acceptance-diagnostic-sweep requires a non-normal acceptance profile"
            )
        }

        if (fast) {
            val approvedModels = when (llmBackend) {
                LlmBackend.OPENROUTER -> FastModelSelectionConfig.approvedModelIds
                LlmBackend.QWEN_CLOUD -> setOf(
                    FastModelSelectionConfig.FAST_QWEN_CLOUD_QWEN_MODEL,
                    ModelSelectionConfig.QWEN_CLOUD_QWEN_FAST_MODEL,
                    FastModelSelectionConfig.FAST_QWEN_CLOUD_DEEPSEEK_MODEL
                )
                LlmBackend.CODEX_CHATGPT -> codexRuntimePreset?.supportedModelIds
                    ?: setOf(ModelSelectionConfig.CODEX_CHATGPT_GPT56_LUNA_MODEL)
            }
            val invalidFastModels = modelOverrides.values.filterNot { it in approvedModels }
            if (invalidFastModels.isNotEmpty()) {
                throw CliUsageException(
                    "Fast mode only allows these model IDs for backend ${llmBackend.id}: ${approvedModels.joinToString(", ")}"
                )
            }
        }

        if (rounds != null && maxRounds != null) {
            throw CliUsageException("Specify either -rounds or -max-rounds, not both")
        }
        if (rounds == null && maxRounds == null) {
            throw CliUsageException(
                "When -rounds is omitted, -max-rounds is required as the auto-completion safety ceiling"
            )
        }
        if (completionStabilityExplicit && maxRounds == null) {
            throw CliUsageException(
                "-completion-stability-rounds is valid only when -rounds is omitted and -max-rounds is set"
            )
        }
        maxRounds?.let { ceiling ->
            if (ceiling < completionStabilityRounds) {
                throw CliUsageException(
                    "Max rounds must be at least the $completionStabilityRounds-round completion stability window"
                )
            }
        }

        when (acceptanceProfile) {
            AcceptanceProfile.NORMAL -> Unit
            AcceptanceProfile.RUNTIME_USABILITY_SMOKE -> {
                if (rounds != 1) {
                    throw CliUsageException(
                        "Acceptance profile '${acceptanceProfile.id}' requires -rounds 1"
                    )
                }
                if ((groupCount ?: 2) < 2) {
                    throw CliUsageException(
                        "Acceptance profile '${acceptanceProfile.id}' requires at least two groups"
                    )
                }
            }
            AcceptanceProfile.APPENDIX_B_FULL -> {
                if (rounds != 6) {
                    throw CliUsageException(
                        "Acceptance profile '${acceptanceProfile.id}' requires -rounds 6"
                    )
                }
                if ((groupCount ?: 2) < 2) {
                    throw CliUsageException(
                        "Acceptance profile '${acceptanceProfile.id}' requires at least two groups"
                    )
                }
            }
        }

        if (graderGroupCount != null && acceptanceProfile != AcceptanceProfile.NORMAL) {
            throw CliUsageException(
                "-grader-groups is unavailable to acceptance profiles; they use the production grader count"
            )
        }

        val codexReasoningEffort = try {
            CodexReasoningEffort.resolve(
                explicitCodexReasoningEffort ?: if (llmBackend == LlmBackend.CODEX_CHATGPT) {
                    codexReasoningEffortEnvironment()
                } else {
                    null
                }
            )
        } catch (error: IllegalArgumentException) {
            throw CliUsageException(error.message.orEmpty())
        }

        return CliOptions(
            prompt = resolvedPrompt,
            inputDir = resolvedInput.toAbsolutePath().normalize(),
            outputDir = resolvedOutput.toAbsolutePath().normalize(),
            openRouterApiKey = resolvedKey,
            llmBackend = llmBackend,
            modelOverrides = modelOverrides,
            debug = debug || fast,
            fast = fast,
            rounds = rounds,
            maxRounds = maxRounds,
            completionStabilityRounds = completionStabilityRounds,
            groupCount = groupCount,
            maxAgentsPerGroup = maxAgentsPerGroup,
            graderGroupCount = graderGroupCount,
            roundDurationMinutes = roundDurationMinutes,
            budgetOverride = budgetOverride,
            runId = runId,
            pipelineConfigPath = pipelineConfigPath?.toAbsolutePath()?.normalize(),
            enforceProviderMonetaryCostCap = enforceProviderMonetaryCostCap,
            councilExecutionPurpose = councilExecutionPurpose,
            acceptanceProfile = acceptanceProfile,
            acceptanceDiagnosticMode = acceptanceDiagnosticMode,
            codexReasoningEffort = codexReasoningEffort,
            codexRuntimePreset = codexRuntimePreset
        )
    }

    fun usage(): String = """
        EvoMolior console runner

        Required:
          -prompt "text" | -promptfile /path/to/file
          -input /path/to/input-folder
          -output /path/to/output-folder

        Optional:
          -fast
          -rounds N
          -max-rounds N                         required when -rounds is omitted
          -completion-stability-rounds 2|3      default: 3 in auto-completion mode
          -group-count N
          -max-agents-per-group N
          -grader-groups 1|2|3                     default 3; fewer cuts grading cost but lowers independence
          -round-duration-minutes N                 0 = budget-led production rounds
          -budget-override N
          -iso-latency
          -openrouter-api-key KEY
          -qwen-api-key KEY
          -llm-backend openrouter|qwen-cloud|codex-chatgpt
          -council-execution-purpose standard|appendix-b-acceptance
          -acceptance-profile normal|runtime-usability-smoke|appendix-b-full
          -acceptance-diagnostic-sweep             exhaustive late-gate report; never accepted
          -codex-reasoning-effort low|medium|high  CLI overrides environment; default: high
          -codex-preset full-luna|mixed-families  role policy; reasoning effort becomes a ceiling
          -debug
          -run-id custom-run-id
          -pipeline-config /path/to/pipeline.json
          -model ROLE=MODEL   (repeatable)

        Subcommands:
          serve                                  start authenticated remote dashboard
          benchmark -benchmark frames -limit 2 -output /path/to/output [-qwen-api-key KEY | -openrouter-api-key KEY]
          benchmark-compare [benchmark flags] -seeds 11,22 -constraint iso-cost|iso-latency [matched commitment flags]
          gptauth [status]                       check existing local Codex/ChatGPT session
          template list|show <id>|init <id> <dir>  ready pipeline configs for best-fit applications
    """.trimIndent()

    private fun readLocalProperty(key: String): String? {
        val file = generateSequence(Path.of("").toAbsolutePath().normalize()) { current ->
            current.parent
        }.map { it.resolve("local.properties") }
            .firstOrNull(Files::exists)
            ?: return null
        if (!Files.exists(file)) return null
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

/** Upper bound for `-grader-groups`, matching the production grader count. */
internal const val MAX_OPERATOR_GRADER_GROUPS = 3

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
