package com.achulkov.evomolior.cli

import com.achulkov.evomolior.api.events.EvoMoliorEvent
import com.achulkov.evomolior.api.model.FinalOutputSpec
import com.achulkov.evomolior.api.model.FinalOutputKind
import com.achulkov.evomolior.api.model.FinalOutputValidationContract
import com.achulkov.evomolior.api.model.RunConfig
import com.achulkov.evomolior.api.model.RunAutoCompletionPolicy
import com.achulkov.evomolior.api.model.requireAcceptanceFinalOutputSpec
import com.achulkov.evomolior.api.service.FinalOutputContractValidator
import com.achulkov.evomolior.api.service.FinalOutputValidationContext
import com.achulkov.evomolior.api.service.GeneratedOutputFile
import com.achulkov.evomolior.api.service.PersistedRunStateStore
import com.achulkov.evomolior.api.service.AcceptanceAdmissionArtifact
import com.achulkov.evomolior.api.service.StartupActivationFlags
import com.achulkov.evomolior.api.service.StartupRejectionArtifact
import com.achulkov.evomolior.api.service.StartupRejectionError
import com.achulkov.evomolior.api.service.StartupRejectionErrorKind
import com.achulkov.evomolior.api.service.StartupRejectionFileStore
import com.achulkov.evomolior.api.service.StartupRejectionPhase
import com.achulkov.evomolior.api.service.StartupRejectionReason
import com.achulkov.evomolior.api.util.loadRunInputArtifacts
import com.achulkov.evomolior.cli.selfimprovement.CodeImprovementPlanApplicator
import com.achulkov.evomolior.agents.factory.LlmBackendToolSupportPolicy
import com.achulkov.evomolior.core.config.AcceptanceProfile
import com.achulkov.evomolior.core.config.FastModelSelectionConfig
import com.achulkov.evomolior.core.config.CouncilExecutionPurpose
import com.achulkov.evomolior.core.config.LlmBackend
import com.achulkov.evomolior.core.config.ModelSelectionConfig
import com.achulkov.evomolior.core.config.RunObjectiveHeuristics
import com.achulkov.evomolior.core.model.GroupProfileType
import io.github.aakira.napier.DebugAntilog
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@Serializable
internal data class CliPipelineConfig(
    val primary: CliPipelineStageConfig? = null,
    val stages: List<CliPipelineStageConfig> = emptyList(),
    val applyCodeImprovementPlans: Boolean = false
)

internal data class LoadedCliPipelineConfig(
    val config: CliPipelineConfig,
    val baseDir: Path
)

@Serializable
internal data class CliPipelineStageConfig(
    val id: String,
    val prompt: String? = null,
    val promptFile: String? = null,
    val fast: Boolean = true,
    val rounds: Int = 1,
    val groupCount: Int? = null,
    val maxAgentsPerGroup: Int? = null,
    val roundDurationMinutes: Int? = null,
    val budgetOverride: Double? = null,
    val inheritsPreviousOutputs: Boolean = true,
    val modelOverrides: Map<String, String> = emptyMap(),
    val finalOutputKind: FinalOutputKind = FinalOutputKind.GENERIC_FILE,
    val finalOutput: CliPipelineFinalOutputSpec? = null,
    val councilExecutionPurpose: CouncilExecutionPurpose? = null,
    val acceptanceProfile: AcceptanceProfile? = null
)

@Serializable
internal data class CliPipelineFinalOutputSpec(
    val relativePath: String,
    val mediaType: String,
    val description: String = "",
    val kind: FinalOutputKind = FinalOutputKind.GENERIC_FILE,
    val validation: FinalOutputValidationContract? = null
)

internal data class CliStagePlan(
    val id: String,
    val prompt: String,
    val inputDir: Path,
    val outputDir: Path,
    val runId: String,
    val fast: Boolean,
    val rounds: Int,
    val groupCount: Int?,
    val maxAgentsPerGroup: Int?,
    val roundDurationMinutes: Int?,
    val budgetOverride: Double?,
    val inheritsPreviousOutputs: Boolean,
    val modelOverrides: Map<String, String>,
    val finalOutputSpec: CliPipelineFinalOutputSpec?,
    val finalOutputExplicit: Boolean,
    val enforceProviderMonetaryCostCap: Boolean,
    val councilExecutionPurpose: CouncilExecutionPurpose = CouncilExecutionPurpose.STANDARD,
    val acceptanceProfile: AcceptanceProfile = AcceptanceProfile.NORMAL,
    val autoCompletionPolicy: RunAutoCompletionPolicy? = null
)

internal data class CliStageExecutionResult(
    val stageId: String,
    val runId: String,
    val stageOutputDir: Path,
    val summary: CliRunSummary,
    val publishedOutputRelativePath: String? = null
)

@Serializable
internal data class CliPipelineSummary(
    val pipelineRunId: String,
    val outputDirectory: String,
    val completedAt: String,
    val stages: List<CliPipelineStageSummary>,
    val publishedOutputs: List<String>
)

@Serializable
internal data class CliPipelineStageSummary(
    val stageId: String,
    val runId: String,
    val prompt: String,
    val inputDirectory: String,
    val outputDirectory: String,
    val status: String,
    val winnerId: String?,
    val durationMs: Long,
    val publishedOutputRelativePath: String? = null
)

private const val CODE_IMPROVEMENT_PLAN_RELATIVE_PATH = "improvements/code-improvement-plan.json"

internal fun runCliPipeline(
    options: CliOptions,
    runtimeFactory: (File, CliOptions) -> CliRuntime,
    acceptanceAdmissionController: AcceptanceAdmissionController = AcceptanceAdmissionController.NONE
): CliRunResult = runBlocking {
    val pipelineRunId = options.runId ?: "cli-${UUID.randomUUID()}"
    val outputRoot = options.outputDir.toFile().absoluteFile
    val runOutputDir = outputRoot.resolve(pipelineRunId)
    val pipelineConfig = loadPipelineConfig(options.pipelineConfigPath)
    val applyCodeImprovementPlans = pipelineConfig?.config?.applyCodeImprovementPlans == true

    val stagePlans = mutableListOf<CliStagePlan>()
    val configuredPrimary = pipelineConfig?.config?.primary
    val primaryPrompt = configuredPrimary?.let {
        resolveStagePrompt(it, pipelineConfig.baseDir)
    } ?: options.prompt
    val primaryOutputSpec = configuredPrimary?.finalOutput
        ?.let { outputSpec ->
            if (configuredPrimary.finalOutputKind == FinalOutputKind.GENERIC_FILE) {
                outputSpec
            } else {
                outputSpec.copy(kind = configuredPrimary.finalOutputKind)
            }
        }
        ?: defaultFinalOutputSpec(
            prompt = primaryPrompt,
            preferredPath = "final-article.html"
        )
    stagePlans += CliStagePlan(
        id = "primary",
        prompt = primaryPrompt,
        inputDir = options.inputDir,
        outputDir = runOutputDir.toPath(),
        runId = pipelineRunId,
        fast = configuredPrimary?.fast ?: options.fast,
        rounds = configuredPrimary?.rounds
            ?: options.rounds
            ?: requireNotNull(options.maxRounds) {
                "Auto-completion requires a maximum round ceiling"
            },
        groupCount = configuredPrimary?.groupCount ?: options.groupCount,
        maxAgentsPerGroup = configuredPrimary?.maxAgentsPerGroup ?: options.maxAgentsPerGroup,
        roundDurationMinutes = configuredPrimary?.roundDurationMinutes ?: options.roundDurationMinutes,
        budgetOverride = configuredPrimary?.budgetOverride ?: options.budgetOverride,
        inheritsPreviousOutputs = false,
        modelOverrides = options.modelOverrides + configuredPrimary?.modelOverrides.orEmpty(),
        finalOutputSpec = primaryOutputSpec,
        finalOutputExplicit = configuredPrimary?.finalOutput != null,
        enforceProviderMonetaryCostCap = options.enforceProviderMonetaryCostCap,
        councilExecutionPurpose = configuredPrimary?.councilExecutionPurpose
            ?: options.councilExecutionPurpose,
        acceptanceProfile = configuredPrimary?.acceptanceProfile
            ?: options.acceptanceProfile,
        autoCompletionPolicy = if (configuredPrimary == null && options.rounds == null) {
            RunAutoCompletionPolicy(options.completionStabilityRounds)
        } else {
            null
        }
    )

    val configuredExtraStages = pipelineConfig?.config?.stages?.mapIndexed { index, stageConfig ->
        val resolvedPrompt = resolveStagePrompt(stageConfig, pipelineConfig.baseDir)
        CliStagePlan(
            id = stageConfig.id.ifBlank { "stage-${index + 1}" },
            prompt = resolvedPrompt,
            inputDir = options.inputDir,
            outputDir = runOutputDir.toPath().resolve("stages").resolve(stageConfig.id.ifBlank { "stage-${index + 1}" }),
            runId = "$pipelineRunId-${stageConfig.id.ifBlank { "stage-${index + 1}" }}",
            fast = stageConfig.fast,
            rounds = stageConfig.rounds,
            groupCount = stageConfig.groupCount,
            maxAgentsPerGroup = stageConfig.maxAgentsPerGroup,
            roundDurationMinutes = stageConfig.roundDurationMinutes,
            budgetOverride = stageConfig.budgetOverride,
            inheritsPreviousOutputs = stageConfig.inheritsPreviousOutputs,
            modelOverrides = stageConfig.modelOverrides,
            finalOutputSpec = stageConfig.finalOutput?.let { outputSpec ->
                if (stageConfig.finalOutputKind == FinalOutputKind.GENERIC_FILE) {
                    outputSpec
                } else {
                    outputSpec.copy(kind = stageConfig.finalOutputKind)
                }
            },
            finalOutputExplicit = stageConfig.finalOutput != null,
            enforceProviderMonetaryCostCap = options.enforceProviderMonetaryCostCap,
            councilExecutionPurpose = stageConfig.councilExecutionPurpose
                ?: CouncilExecutionPurpose.STANDARD,
            acceptanceProfile = stageConfig.acceptanceProfile ?: AcceptanceProfile.NORMAL
        )
    }.orEmpty()

    if (configuredExtraStages.isEmpty() &&
        options.fast &&
        RunObjectiveHeuristics.requestsBrowserDeliverable(options.prompt)
    ) {
        stagePlans += CliStagePlan(
            id = "fast-follow-up",
            prompt = buildDefaultFollowUpPrompt(options.prompt),
            inputDir = options.inputDir,
            outputDir = runOutputDir.toPath().resolve("stages").resolve("fast-follow-up"),
            runId = "$pipelineRunId-fast-follow-up",
            fast = true,
            rounds = 1,
            groupCount = options.groupCount,
            maxAgentsPerGroup = options.maxAgentsPerGroup,
            roundDurationMinutes = options.roundDurationMinutes,
            budgetOverride = options.budgetOverride,
            inheritsPreviousOutputs = true,
            modelOverrides = emptyMap(),
            finalOutputSpec = defaultFinalOutputSpec(
                prompt = options.prompt,
                preferredPath = if (RunObjectiveHeuristics.requestsBrowserDeliverable(options.prompt)) {
                    "refined/final-article-refined.html"
                } else {
                    "derived/final-output.txt"
                }
            ),
            finalOutputExplicit = false,
            enforceProviderMonetaryCostCap = options.enforceProviderMonetaryCostCap
        )
    } else if (configuredExtraStages.isNotEmpty()) {
        stagePlans += configuredExtraStages
    }
    try {
        stagePlans.forEach { stage ->
            validateStageAcceptanceProfile(stage, options.llmBackend)
        }
        validateAcceptancePipelineShape(stagePlans)
    } catch (error: CliUsageException) {
        val acceptanceStage = stagePlans.firstOrNull {
            it.acceptanceProfile != AcceptanceProfile.NORMAL
        } ?: throw error
        persistCliStartupRejection(
            stage = acceptanceStage,
            preferredOutputDir = runOutputDir.toPath(),
            outputRoot = outputRoot.toPath(),
            error = error,
            artifact = null,
            modelOverrides = acceptanceStage.modelOverrides
        )
        return@runBlocking CliRunResult(completedSuccessfully = false)
    }

    val acceptanceLease = try {
        acceptanceAdmissionController.admit(
            stagePlans = stagePlans,
            options = options,
            pipelineRunOutput = runOutputDir.toPath()
        )
    } catch (error: AcceptanceAdmissionException) {
        persistCliStartupRejection(
            stage = stagePlans.singleOrNull { it.acceptanceProfile != AcceptanceProfile.NORMAL }
                ?: stagePlans.first(),
            preferredOutputDir = runOutputDir.toPath(),
            outputRoot = outputRoot.toPath(),
            error = error,
            artifact = null,
            modelOverrides = stagePlans.first().modelOverrides
        )
        return@runBlocking CliRunResult(completedSuccessfully = false)
    }
    try {
    if (acceptanceLease != null) {
        persistAcceptanceAdmissionRecord(runOutputDir.toPath(), acceptanceLease.record)
    }
    val stageResults = mutableListOf<CliStageExecutionResult>()
    var previousStageResult: CliStageExecutionResult? = null
    stagePlans.forEachIndexed { index, stagePlan ->
        val resolvedInputDir = if (index == 0) {
            stagePlan.inputDir
        } else {
            preparePipelineStageInput(
                baseInputDir = options.inputDir,
                pipelineRoot = runOutputDir.toPath(),
                stageId = stagePlan.id,
                previousStage = previousStageResult.takeIf { stagePlan.inheritsPreviousOutputs },
                applyCodeImprovementPlans = applyCodeImprovementPlans
            )
        }
        val resolvedStagePlan = stagePlan.copy(inputDir = resolvedInputDir)
        val result = loadCompletedStageResult(resolvedStagePlan)
            ?: run {
                if (resolvedStagePlan.outputDir != runOutputDir.toPath()) {
                    resolvedStagePlan.outputDir.toFile().deleteRecursively()
                }
                executeCliStage(
                    resolvedStagePlan,
                    options,
                    runtimeFactory,
                    acceptanceLease
                )
            }
        stageResults += result
        if (!result.summary.status.equals("COMPLETED", ignoreCase = true)) {
            return@runBlocking CliRunResult(completedSuccessfully = false)
        }
        if (applyCodeImprovementPlans) {
            acceptanceLease?.verifyStable("cli-stage-output-publication")
            publishStageOutput(
                stageOutputDir = result.stageOutputDir,
                relativePath = CODE_IMPROVEMENT_PLAN_RELATIVE_PATH,
                pipelineOutputDir = runOutputDir.toPath().resolve("outputs"),
                fallbackWhenMissing = false
            )
        }
        result.publishedOutputRelativePath?.let { relativePath ->
            acceptanceLease?.verifyStable("cli-stage-output-publication")
            publishStageOutput(
                stageOutputDir = result.stageOutputDir,
                relativePath = relativePath,
                pipelineOutputDir = runOutputDir.toPath().resolve("outputs")
            )
            publishStageAssetOutputs(
                stageOutputDir = result.stageOutputDir,
                pipelineOutputDir = runOutputDir.toPath().resolve("outputs")
            )
            stagePlan.finalOutputSpec?.let { outputSpec ->
                verifyPublishedStageOutput(
                    stageOutputDir = result.stageOutputDir,
                    outputSpec = outputSpec,
                    publishedOutputDir = runOutputDir.toPath().resolve("outputs")
                ) ?: throw IllegalStateException(
                    "Pipeline stage ${stagePlan.id} completed without publishing required output ${outputSpec.relativePath} (${outputSpec.mediaType})"
                )
            }
        }
        previousStageResult = result
    }

    val publishedOutputs = runCatching {
        runOutputDir.toPath().resolve("outputs")
            .toFile()
            .walkTopDown()
            .filter { it.isFile }
            .filter {
                isPublishablePipelineOutput(
                    it.relativeTo(runOutputDir.resolve("outputs")).invariantSeparatorsPath
                )
            }
            .map { it.relativeTo(runOutputDir.resolve("outputs")).invariantSeparatorsPath }
            .sorted()
            .toList()
    }.getOrDefault(emptyList())

    val pipelineSummary = CliPipelineSummary(
        pipelineRunId = pipelineRunId,
        outputDirectory = runOutputDir.absolutePath,
        completedAt = Instant.now().toString(),
        stages = stageResults.map { result ->
            CliPipelineStageSummary(
                stageId = result.stageId,
                runId = result.runId,
                prompt = result.summary.prompt,
                inputDirectory = result.summary.inputDirectory,
                outputDirectory = result.summary.outputDirectory,
                status = result.summary.status,
                winnerId = result.summary.winnerId,
                durationMs = result.summary.durationMs,
                publishedOutputRelativePath = result.publishedOutputRelativePath
            )
        },
        publishedOutputs = publishedOutputs
    )

    Files.writeString(
        runOutputDir.toPath().resolve("pipeline-summary.json"),
        cliJson.encodeToString(CliPipelineSummary.serializer(), pipelineSummary)
    )
    acceptanceLease?.verifyStable("pipeline-results-publication")
    writePipelineResultsZip(runOutputDir.toPath())

    CliRunResult(
        completedSuccessfully = stageResults.all { it.summary.status == "COMPLETED" }
    )
    } finally {
        acceptanceLease?.close()
    }
}

private fun validateStageAcceptanceProfile(
    stage: CliStagePlan,
    llmBackend: LlmBackend
) {
    if (stage.acceptanceProfile == AcceptanceProfile.NORMAL) return
    if (stage.councilExecutionPurpose != CouncilExecutionPurpose.APPENDIX_B_ACCEPTANCE) {
        throw CliUsageException(
            "Pipeline stage ${stage.id} acceptance profile '${stage.acceptanceProfile.id}' " +
                "requires councilExecutionPurpose=appendix-b-acceptance"
        )
    }
    if (llmBackend != LlmBackend.CODEX_CHATGPT) {
        throw CliUsageException(
            "Pipeline stage ${stage.id} acceptance profile '${stage.acceptanceProfile.id}' " +
                "requires the codex-chatgpt backend"
        )
    }
    if ((stage.groupCount ?: 2) < 2) {
        throw CliUsageException(
            "Pipeline stage ${stage.id} acceptance profile '${stage.acceptanceProfile.id}' " +
                "requires at least two worker groups"
        )
    }
    if (!stage.finalOutputExplicit || stage.finalOutputSpec == null) {
        throw CliUsageException(
            "Pipeline stage ${stage.id} acceptance profile '${stage.acceptanceProfile.id}' " +
                "requires an explicit finalOutput"
        )
    }
    try {
        requireAcceptanceFinalOutputSpec(
            stage.acceptanceProfile,
            stage.finalOutputSpec.toRunFinalOutputSpec()
        )
    } catch (error: IllegalArgumentException) {
        throw CliUsageException(
            "Pipeline stage ${stage.id}: ${error.message.orEmpty()}"
        )
    }
    if (stage.autoCompletionPolicy != null) {
        throw CliUsageException(
            "Pipeline stage ${stage.id} acceptance profile '${stage.acceptanceProfile.id}' " +
                "requires a fixed round horizon"
        )
    }
    val requiredRounds = when (stage.acceptanceProfile) {
        AcceptanceProfile.NORMAL -> return
        AcceptanceProfile.RUNTIME_USABILITY_SMOKE -> 1
        AcceptanceProfile.APPENDIX_B_FULL -> 6
    }
    if (stage.rounds != requiredRounds) {
        throw CliUsageException(
            "Pipeline stage ${stage.id} acceptance profile '${stage.acceptanceProfile.id}' " +
                "requires exactly $requiredRounds round${if (requiredRounds == 1) "" else "s"}"
        )
    }
}

private fun validateAcceptancePipelineShape(stagePlans: List<CliStagePlan>) {
    val acceptanceStages = stagePlans.filter {
        it.acceptanceProfile != AcceptanceProfile.NORMAL
    }
    if (acceptanceStages.isNotEmpty() && (stagePlans.size != 1 || acceptanceStages.size != 1)) {
        throw CliUsageException(
            "An acceptance pipeline must contain exactly one non-normal stage and no ordinary or follow-up stages"
        )
    }
}

private fun CliPipelineFinalOutputSpec.toRunFinalOutputSpec(): FinalOutputSpec = FinalOutputSpec(
    relativePath = relativePath,
    mediaType = mediaType,
    description = description,
    kind = kind,
    validation = validation
)

private fun loadCompletedStageResult(stage: CliStagePlan): CliStageExecutionResult? {
    val summaryFile = stage.outputDir.toFile().resolve("summary.json")
    if (!summaryFile.exists()) return null
    val summary = runCatching {
        cliJson.decodeFromString(CliRunSummary.serializer(), summaryFile.readText())
    }.getOrNull() ?: return null
    if (!summary.status.equals("COMPLETED", ignoreCase = true)) return null
    stage.finalOutputSpec?.let { outputSpec ->
        verifyPublishedStageOutput(
            stageOutputDir = stage.outputDir,
            outputSpec = outputSpec
        ) ?: return null
    }
    Napier.i("EvoMolior CLI reusing completed stage ${stage.id} runId=${stage.runId}")
    return CliStageExecutionResult(
        stageId = stage.id,
        runId = stage.runId,
        stageOutputDir = stage.outputDir,
        summary = summary,
        publishedOutputRelativePath = stage.finalOutputSpec?.relativePath
            ?: inferArchivedPublishedOutputRelativePath(stage.outputDir)
    )
}

private suspend fun executeCliStage(
    stage: CliStagePlan,
    rootOptions: CliOptions,
    runtimeFactory: (File, CliOptions) -> CliRuntime,
    acceptanceLease: AcceptanceRunLeaseHandle? = null
): CliStageExecutionResult = coroutineScope {
    val runOutputDir = stage.outputDir.toFile().absoluteFile.apply { mkdirs() }
    val debugLogFile = if (rootOptions.debug || stage.fast) runOutputDir.resolve("debug-log.txt") else null
    runOutputDir.resolve("prompt.txt").writeText(stage.prompt)

    val fileAntilog = debugLogFile?.let { FileAntilog(it.toPath()) }
    val logAntilog = if (fileAntilog != null) {
        CompositeAntilog(listOf(DebugAntilog(), fileAntilog))
    } else {
        DebugAntilog()
    }
    Napier.base(logAntilog)
    Napier.i("EvoMolior CLI starting runId=${stage.runId} outputDir=${runOutputDir.absolutePath}")

    val stageOptions = rootOptions.copy(
        prompt = stage.prompt,
        inputDir = stage.inputDir,
        outputDir = stage.outputDir.parent ?: rootOptions.outputDir,
        modelOverrides = stage.modelOverrides,
        fast = stage.fast,
        rounds = stage.rounds,
        maxRounds = null,
        groupCount = stage.groupCount,
        maxAgentsPerGroup = stage.maxAgentsPerGroup,
        budgetOverride = stage.budgetOverride,
        runId = stage.runId,
        councilExecutionPurpose = stage.councilExecutionPurpose,
        acceptanceProfile = stage.acceptanceProfile,
        acceptanceRunBoundaryVerifier = acceptanceLease
            ?: com.achulkov.evomolior.api.service.AcceptanceRunBoundaryVerifier.NONE
    )

    val inputArtifacts = loadRunInputArtifacts(stage.inputDir)
    val sourceArtifacts = BenchmarkCliRunner().buildSourceArtifactUploadsForObjective(
        objective = stage.prompt,
        namespace = stage.runId
    )
    val allInputArtifacts = inputArtifacts + sourceArtifacts
    Napier.i("EvoMolior CLI loaded ${inputArtifacts.size} input artifacts from ${stage.inputDir}")
    if (sourceArtifacts.isNotEmpty()) {
        Napier.i("EvoMolior CLI materialized ${sourceArtifacts.size} source artifact(s) from prompt URLs")
    }
    val benchmarkCliRunner = BenchmarkCliRunner()
    val objective = buildString {
        append(stage.prompt)
        if (sourceArtifacts.isNotEmpty()) {
            appendLine()
            appendLine()
            append(benchmarkCliRunner.buildNormalRunSourceInstruction(sourceArtifacts))
        }
        if (
            LlmBackendToolSupportPolicy.resolve(rootOptions.llmBackend).textOnlyCompatibilityMode &&
            allInputArtifacts.isNotEmpty()
        ) {
            val textOnlyArtifactInstruction = benchmarkCliRunner.buildTextOnlyArtifactInstruction(allInputArtifacts)
            if (textOnlyArtifactInstruction.isNotBlank()) {
                appendLine()
                appendLine()
                append(textOnlyArtifactInstruction)
            }
        }
    }

    val objectiveProfile = buildCliObjectiveProfile(stage.prompt, stage.fast)
    val modelOverrides = when {
        rootOptions.llmBackend == LlmBackend.CODEX_CHATGPT -> {
            ModelSelectionConfig.codexChatGptRuntimeAssignments()
        }
        stage.fast -> {
            when (rootOptions.llmBackend) {
                LlmBackend.OPENROUTER -> FastModelSelectionConfig.fastRuntimeAssignments()
                LlmBackend.QWEN_CLOUD -> FastModelSelectionConfig.fastQwenCloudRuntimeAssignments()
                LlmBackend.CODEX_CHATGPT -> ModelSelectionConfig.codexChatGptRuntimeAssignments()
            }.toMutableMap().apply {
                putAll(stage.modelOverrides)
            }
        }
        else -> stage.modelOverrides
    }

    val runConfig = try {
        RunConfig(
        runId = stage.runId,
        objective = objective,
        groupCount = stage.groupCount ?: 2,
        groupProfiles = List(stage.groupCount ?: 2) { GroupProfileType.Library },
        rounds = stage.rounds,
        roundDurationMinutes = stage.roundDurationMinutes ?: if (stage.fast) 1 else 0,
        maxAgentsPerGroup = resolveCliMaxAgentsPerGroup(stage, objectiveProfile),
        scoringDimensions = objectiveProfile.scoringDimensions,
        openRouterApiKey = rootOptions.openRouterApiKey,
        llmBackend = rootOptions.llmBackend,
        councilExecutionPurpose = stage.councilExecutionPurpose,
        acceptanceProfile = stage.acceptanceProfile,
        autoCompletionPolicy = stage.autoCompletionPolicy,
        modelOverrides = modelOverrides,
        contextArtifacts = allInputArtifacts,
        finalOutputSpec = stage.finalOutputSpec?.toRunFinalOutputSpec(),
        isTestRun = false,
        budgetOverride = stage.budgetOverride ?: objectiveProfile.budgetOverride,
        enforceProviderMonetaryCostCap = stage.enforceProviderMonetaryCostCap
        )
    } catch (error: Exception) {
        fileAntilog?.close()
        return@coroutineScope rejectedStageResult(
            stage = stage,
            runOutputDir = runOutputDir.toPath(),
            rootOutputDir = rootOptions.outputDir,
            modelOverrides = modelOverrides,
            error = error,
            artifact = null
        )
    }
    val dataRoot = runOutputDir.resolve(".evomolior-data").also { it.mkdirs() }
    val persistedRunRoot = dataRoot.resolve("evomolior/runs/${stage.runId}")
    val persistedStateFile = PersistedRunStateStore.resolveStateFile(dataRoot, stage.runId)
    val persistedRunExistedBeforeAdmissionRecord =
        persistedStateFile.exists() || persistedRunRoot.exists()
    acceptanceLease?.let { lease ->
        persistAcceptanceAdmissionRecord(persistedRunRoot.toPath(), lease.record)
    }
    val shouldResumePersistedRun =
        !runOutputDir.resolve("summary.json").exists() &&
            persistedRunExistedBeforeAdmissionRecord
    val runtime = try {
        acceptanceLease?.verifyStable("runtime-construction")
        runtimeFactory(dataRoot, stageOptions)
    } catch (error: Exception) {
        fileAntilog?.close()
        return@coroutineScope rejectedStageResult(
            stage = stage,
            runOutputDir = runOutputDir.toPath(),
            rootOutputDir = rootOptions.outputDir,
            modelOverrides = modelOverrides,
            error = error,
            artifact = null
        )
    }

    val completion = CompletableDeferred<EvoMoliorEvent>()
    val eventCollector = launch(Dispatchers.Default) {
        runtime.engineService.events.collect { event ->
            when (event) {
                is EvoMoliorEvent.RunStarted -> Napier.i("EvoMolior CLI run started runId=${event.runId}")
                is EvoMoliorEvent.RoundStarted -> Napier.i("EvoMolior CLI round started round=${event.roundIndex}")
                is EvoMoliorEvent.RoundCompleted -> {
                    acceptanceLease?.verifyStable("round-${event.result.roundIndex}-boundary")
                    Napier.i("EvoMolior CLI round completed round=${event.result.roundIndex}")
                }
                is EvoMoliorEvent.RunCompleted -> {
                    acceptanceLease?.verifyStable("run-completion")
                    Napier.i("EvoMolior CLI run completed runId=${event.runId} winner=${event.winnerId}")
                    if (!completion.isCompleted) completion.complete(event)
                }
                is EvoMoliorEvent.RunAborted -> {
                    Napier.e("EvoMolior CLI run aborted runId=${event.runId} reason=${event.reason}")
                    if (!completion.isCompleted) completion.complete(event)
                }
                is EvoMoliorEvent.EngineProgress -> Napier.d("EvoMolior CLI progress phase=${event.phase} msg=${event.message}")
                else -> Unit
            }
        }
    }

    val startedAt = Instant.now()
    val completedEvent = try {
        if (shouldResumePersistedRun) {
            Napier.i("EvoMolior CLI resuming persisted runId=${stage.runId}")
            runtime.engineService.resumePersistedRun(runConfig)
        } else {
            runtime.engineService.startRun(runConfig)
        }
        completion.await()
    } catch (error: Exception) {
        val persistedRejection = StartupRejectionFileStore { runId ->
            dataRoot.resolve("evomolior/runs/$runId/control")
        }.read(stage.runId)
        fileAntilog?.close()
        return@coroutineScope rejectedStageResult(
            stage = stage,
            runOutputDir = runOutputDir.toPath(),
            rootOutputDir = rootOptions.outputDir,
            modelOverrides = modelOverrides,
            error = error,
            artifact = persistedRejection
        )
    } finally {
        eventCollector.cancelAndJoin()
    }

    val durationMs = Duration.between(startedAt, Instant.now()).toMillis()
    val resultsBundle = runtime.engineService.getResultsBundle(stage.runId)
    val resultsBundleFile = resultsBundle?.let {
        val file = runOutputDir.resolve("results.zip")
        Files.write(file.toPath(), it)
        file
    }

    val summary = CliRunSummary(
        runId = stage.runId,
        prompt = stage.prompt,
        inputDirectory = stage.inputDir.toString(),
        inputArtifactCount = inputArtifacts.size,
        outputDirectory = runOutputDir.absolutePath,
        completedAt = Instant.now().toString(),
        durationMs = durationMs,
        debugLogPath = debugLogFile?.absolutePath,
        resultsBundlePath = resultsBundleFile?.absolutePath,
        status = when (completedEvent) {
            is EvoMoliorEvent.RunCompleted -> "COMPLETED"
            is EvoMoliorEvent.RunAborted -> "ABORTED"
            else -> "UNKNOWN"
        },
        winnerId = (completedEvent as? EvoMoliorEvent.RunCompleted)?.winnerId,
        modelOverrides = modelOverrides
    )

    Files.writeString(
        runOutputDir.resolve("summary.json").toPath(),
        cliJson.encodeToString(CliRunSummary.serializer(), summary)
    )

    Napier.i("EvoMolior CLI wrote summary to ${runOutputDir.resolve("summary.json").absolutePath}")
    Napier.takeLogarithm(logAntilog)
    fileAntilog?.close()

    CliStageExecutionResult(
        stageId = stage.id,
        runId = stage.runId,
        stageOutputDir = runOutputDir.toPath(),
        summary = summary,
        publishedOutputRelativePath = stage.finalOutputSpec?.relativePath
            ?: inferArchivedPublishedOutputRelativePath(runOutputDir.toPath())
    )
}

private fun rejectedStageResult(
    stage: CliStagePlan,
    runOutputDir: Path,
    rootOutputDir: Path,
    modelOverrides: Map<String, String>,
    error: Throwable,
    artifact: StartupRejectionArtifact?
): CliStageExecutionResult {
    val persisted = persistCliStartupRejection(
        stage = stage,
        preferredOutputDir = runOutputDir,
        outputRoot = rootOutputDir,
        error = error,
        artifact = artifact,
        modelOverrides = modelOverrides
    )
    return CliStageExecutionResult(
        stageId = stage.id,
        runId = stage.runId,
        stageOutputDir = persisted.outputDirectory,
        summary = persisted.summary,
        publishedOutputRelativePath = null
    )
}

internal data class PersistedCliStartupRejection(
    val outputDirectory: Path,
    val summary: CliRunSummary
)

internal fun persistCliStartupRejection(
    stage: CliStagePlan,
    preferredOutputDir: Path,
    outputRoot: Path,
    error: Throwable,
    artifact: StartupRejectionArtifact?,
    modelOverrides: Map<String, String>,
    publicCopyPublisher: (Path, Path) -> Unit = ::publishImmutableStartupRejectionCopy
): PersistedCliStartupRejection {
    val outputDirectory = if (!Files.exists(preferredOutputDir) || Files.list(preferredOutputDir).use {
            !it.findAny().isPresent
        }
    ) {
        preferredOutputDir
    } else {
        outputRoot.resolve(".startup-rejections")
            .resolve("${stage.runId}-${UUID.randomUUID()}")
    }
    Files.createDirectories(outputDirectory)
    Files.writeString(outputDirectory.resolve("prompt.txt"), stage.prompt)
    val rejection = artifact ?: StartupRejectionArtifact(
        runId = stage.runId,
        phase = StartupRejectionPhase.ADMISSION,
        reasons = listOf(StartupRejectionReason.ADMISSION_REJECTED),
        errors = listOf(
            StartupRejectionError(
                kind = StartupRejectionErrorKind.ADMISSION,
                message = error.message.orEmpty()
                    .ifBlank { error::class.simpleName.orEmpty() }
                    .take(StartupRejectionError.MAX_DIAGNOSTIC_CHARS)
            )
        ),
        declaredWorkerGroupCount = stage.groupCount ?: 2,
        declaredGraderGroupCount = stage.groupCount ?: 2,
        sourceManifestHash = currentAcceptanceSourceManifestHash(),
        distributionManifestHash = currentAcceptanceDistributionManifestHash(),
        providerExecutionRefs = emptyList(),
        providerExecutionCount = 0,
        activationFlags = StartupActivationFlags(
            runStartedEmitted = false,
            workerInfrastructureActivated = false,
            graderInfrastructureActivated = false,
            successArchiveCreated = false,
            winnerAuthorityCreated = false
        ),
        createdAt = Instant.now().toString()
    )
    val stored = StartupRejectionFileStore { File(outputDirectory.toFile(), "control") }
        .persist(rejection)
    publicCopyPublisher(
        stored.toPath(),
        outputDirectory.resolve("startup-rejection.json")
    )
    val summary = CliRunSummary(
        runId = stage.runId,
        prompt = stage.prompt,
        inputDirectory = stage.inputDir.toString(),
        inputArtifactCount = 0,
        outputDirectory = outputDirectory.toAbsolutePath().toString(),
        completedAt = Instant.now().toString(),
        durationMs = 0,
        debugLogPath = null,
        resultsBundlePath = null,
        status = "REJECTED",
        winnerId = null,
        modelOverrides = modelOverrides
    )
    Files.writeString(
        outputDirectory.resolve("summary.json"),
        cliJson.encodeToString(CliRunSummary.serializer(), summary)
    )
    return PersistedCliStartupRejection(outputDirectory, summary)
}

/**
 * Publishes the canonical startup-rejection artifact as an immutable public copy.
 *
 * The hard-link publication is a single create-new directory operation: unlike an atomic move,
 * it can never replace an existing public artifact.
 *
 * @param canonicalArtifact Write-once control artifact that supplies the exact public bytes.
 * @param publicArtifact Public path to create exactly once.
 */
internal fun publishImmutableStartupRejectionCopy(
    canonicalArtifact: Path,
    publicArtifact: Path
) {
    val canonicalBytes = Files.readAllBytes(canonicalArtifact)
    val directory = requireNotNull(publicArtifact.parent) {
        "Startup rejection public artifact must have a parent directory"
    }
    val temporary = directory.resolve(".${publicArtifact.fileName}.${UUID.randomUUID()}.tmp")
    try {
        FileChannel.open(
            temporary,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE
        ).use { channel ->
            val bytes = java.nio.ByteBuffer.wrap(canonicalBytes)
            while (bytes.hasRemaining()) {
                channel.write(bytes)
            }
            channel.force(true)
        }
        if (Files.getFileAttributeView(temporary, PosixFileAttributeView::class.java) != null) {
            Files.setPosixFilePermissions(
                temporary,
                setOf(PosixFilePermission.OWNER_READ)
            )
        }

        // createLink is atomic and fails with FileAlreadyExistsException on a collision.
        Files.createLink(publicArtifact, temporary)
        FileChannel.open(directory, StandardOpenOption.READ).use { channel ->
            channel.force(true)
        }

        check(
            Files.readAllBytes(publicArtifact).contentEquals(
                Files.readAllBytes(canonicalArtifact)
            )
        ) {
            "Public startup rejection artifact differs from canonical bytes"
        }
    } finally {
        Files.deleteIfExists(temporary)
    }
}

private fun currentAcceptanceDistributionManifestHash(): String? =
    currentAcceptanceManifestBytes()?.let(::sha256Hex)

private fun currentAcceptanceSourceManifestHash(): String? {
    val bytes = currentAcceptanceManifestBytes() ?: return null
    val manifest = runCatching {
        cliJson.decodeFromString(
            AcceptanceDistributionManifestV1.serializer(),
            bytes.toString(Charsets.UTF_8)
        )
    }.getOrNull() ?: return null
    val authority = manifest.sourceFiles.joinToString(separator = "\n", postfix = "\n") {
        "${it.path}:${it.sha256}:${it.sizeBytes}"
    }
    return sha256Hex(authority.toByteArray(Charsets.UTF_8))
}

private fun currentAcceptanceManifestBytes(): ByteArray? =
    System.getenv(ACCEPTANCE_MANIFEST_ENV)
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.let(Path::of)
        ?.takeIf(Files::isRegularFile)
        ?.let(Files::readAllBytes)

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

private fun persistAcceptanceAdmissionRecord(
    runOutputDir: Path,
    record: AcceptanceAdmissionArtifact
) {
    val controlDir = runOutputDir.resolve("control")
    Files.createDirectories(controlDir)
    val target = controlDir.resolve(AcceptanceAdmissionArtifact.FILE_NAME)
    val temporary = controlDir.resolve(".acceptance-admission-v1-${UUID.randomUUID()}.tmp")
    val bytes = cliJson.encodeToString(AcceptanceAdmissionArtifact.serializer(), record)
        .toByteArray(Charsets.UTF_8)
    Files.write(
        temporary,
        bytes,
        java.nio.file.StandardOpenOption.CREATE_NEW,
        java.nio.file.StandardOpenOption.WRITE
    )
    Files.move(
        temporary,
        target,
        StandardCopyOption.ATOMIC_MOVE
    )
    val decoded = cliJson.decodeFromString(
        AcceptanceAdmissionArtifact.serializer(),
        Files.readString(target)
    )
    check(decoded == record) {
        "Acceptance admission record changed during atomic persistence"
    }
}

private fun resolveCliMaxAgentsPerGroup(
    stage: CliStagePlan,
    objectiveProfile: CliObjectiveProfile
): Int {
    stage.maxAgentsPerGroup?.let { return it }
    val groupCount = stage.groupCount ?: if (stage.fast) 2 else 1
    val isLargeCompetitiveRun = groupCount > 1 || stage.rounds > 1
    return if (isLargeCompetitiveRun) {
        // A one-agent non-fast default only spawns a Critic and prevents Outlier/tool/research
        // behavior. Four slots cover the selector's base Critic/Researcher/Builder/Outlier mix.
        maxOf(objectiveProfile.maxAgentsPerGroup, 4)
    } else {
        objectiveProfile.maxAgentsPerGroup
    }
}

private fun loadPipelineConfig(path: Path?): LoadedCliPipelineConfig? {
    if (path == null) return null
    return LoadedCliPipelineConfig(
        config = cliJson.decodeFromString(CliPipelineConfig.serializer(), Files.readString(path)),
        baseDir = path.parent ?: Path.of("").toAbsolutePath().normalize()
    )
}

private fun resolveStagePrompt(stageConfig: CliPipelineStageConfig, pipelineBaseDir: Path): String {
    return when {
        stageConfig.prompt != null && stageConfig.promptFile != null ->
            throw CliUsageException("Pipeline stage ${stageConfig.id} cannot specify both prompt and promptFile")
        stageConfig.prompt != null -> stageConfig.prompt
        stageConfig.promptFile != null -> {
            val promptPath = Path.of(stageConfig.promptFile)
            val resolvedPromptPath = if (promptPath.isAbsolute) promptPath else pipelineBaseDir.resolve(promptPath).normalize()
            Files.readString(resolvedPromptPath)
        }
        else -> throw CliUsageException("Pipeline stage ${stageConfig.id} must specify either prompt or promptFile")
    }
}

private fun buildDefaultFollowUpPrompt(primaryPrompt: String): String = """
    Using the uploaded artifacts from the previous stage, produce one refined final deliverable
    that better fulfills the original objective below.

    Original objective:
    $primaryPrompt

    Requirements:
    - inspect previous-stage artifacts via read_run_artifact
    - preserve strong structure and improve weak areas
    - do not compress, summarize, or shorten a prior deliverable that already satisfies the
      objective; keep its substantive depth and expand only the weak parts
    - preserve source-grounded evidence notes, evidence-traceability tables, metrics tables,
      caveats, and explicit inference labels when they exist in the prior deliverable
    - if a prior final deliverable exists, expand and refine it rather than starting over
    - produce one polished operator-facing output file
""".trimIndent()

private fun defaultFinalOutputSpec(
    prompt: String,
    preferredPath: String
): CliPipelineFinalOutputSpec? {
    return if (RunObjectiveHeuristics.requestsBrowserDeliverable(prompt)) {
        CliPipelineFinalOutputSpec(
            relativePath = preferredPath,
            mediaType = "text/html",
            description = "Browser-renderable final deliverable",
            kind = FinalOutputKind.HTML_ARTICLE
        )
    } else {
        null
    }
}

private fun preparePipelineStageInput(
    baseInputDir: Path,
    pipelineRoot: Path,
    stageId: String,
    previousStage: CliStageExecutionResult?,
    applyCodeImprovementPlans: Boolean
): Path {
    val stageInputRoot = pipelineRoot.resolve(".pipeline-inputs").resolve(stageId)
    stageInputRoot.toFile().deleteRecursively()
    Files.createDirectories(stageInputRoot)
    copyDirectoryContents(baseInputDir, stageInputRoot)
    if (applyCodeImprovementPlans) {
        applyCodeImprovementPlanIfPresent(
            repoRoot = stageInputRoot,
            pipelineRoot = pipelineRoot
        )
    }
    if (previousStage != null) {
        val previousStageDir = stageInputRoot.resolve("previous-stage")
        previousStageDir.toFile().deleteRecursively()
        Files.createDirectories(previousStageDir)
        copyIfExists(previousStage.stageOutputDir.resolve("prompt.txt"), previousStageDir.resolve("prompt.txt"))
        copyIfExists(previousStage.stageOutputDir.resolve("summary.json"), previousStageDir.resolve("summary.json"))
        copyIfExists(previousStage.stageOutputDir.resolve("results.zip"), previousStageDir.resolve("results.zip"))
        previousStage.stageOutputDir.resolve("results.zip").toFile().takeIf { it.exists() }?.let { bundle ->
            runCatching {
                unzipPipelineBundle(bundle, previousStageDir.resolve("results-bundle"))
            }.onFailure { err ->
                Napier.w("CliPipeline: failed to expand previous-stage results bundle for $stageId: ${err.message}")
            }
        }
    }
    return stageInputRoot
}

private fun applyCodeImprovementPlanIfPresent(
    repoRoot: Path,
    pipelineRoot: Path
) {
    val planFile = pipelineRoot.resolve("outputs").resolve(CODE_IMPROVEMENT_PLAN_RELATIVE_PATH)
    if (!Files.exists(planFile)) return

    CodeImprovementPlanApplicator.applyIfPresent(planFile, repoRoot)
        ?.also { report ->
            Napier.i(
                "CliPipeline: applied code improvement plan ${report.planPath} into ${report.repoRoot} with ${report.changedFiles.size} changed file(s)"
            )
        }
}

private fun publishStageOutput(
    stageOutputDir: Path,
    relativePath: String,
    pipelineOutputDir: Path,
    fallbackWhenMissing: Boolean = true
) {
    val resultsZip = stageOutputDir.resolve("results.zip").toFile()
    if (!resultsZip.exists()) {
        if (fallbackWhenMissing) {
            publishStaticFallbackStageOutput(stageOutputDir, relativePath, pipelineOutputDir, mediaType = null)
        }
        return
    }
    var published = false
    runCatching {
        ZipFile(resultsZip).use { zip ->
            val entry = zip.getEntry("outputs/$relativePath") ?: return@use
            val target = resolvePublishedOutputTarget(
                pipelineOutputDir = pipelineOutputDir,
                relativePath = relativePath
            ) ?: return
            Files.createDirectories(target.parent)
            zip.getInputStream(entry).use { input ->
                Files.write(target, input.readBytes())
            }
            published = true
        }
    }.onFailure { err ->
        Napier.w("CliPipeline: failed to publish stage output $relativePath: ${err.message}")
    }
    if (!published && fallbackWhenMissing) {
        publishStaticFallbackStageOutput(stageOutputDir, relativePath, pipelineOutputDir, mediaType = null)
    }
}

private fun publishStaticFallbackStageOutput(
    stageOutputDir: Path,
    relativePath: String,
    pipelineOutputDir: Path,
    mediaType: String?
) {
    val target = resolvePublishedOutputTarget(pipelineOutputDir, relativePath) ?: return
    val summary = Files.readString(stageOutputDir.resolve("summary.json").takeIf { Files.exists(it) } ?: return)
    val claimLedger = readOutputFromResultsZip(stageOutputDir, "claim_ledger.jsonl")
        ?: readOutputFromResultsZip(stageOutputDir, "final_answer.md")
        ?: ""
    val content = buildStaticFallbackOutput(relativePath, mediaType, summary, claimLedger)
    Files.createDirectories(target.parent)
    Files.writeString(target, content)
    Napier.w("CliPipeline: published static fallback output $relativePath from summary.json and claim ledger")
}

private fun readOutputFromResultsZip(stageOutputDir: Path, relativePath: String): String? {
    val resultsZip = stageOutputDir.resolve("results.zip").toFile()
    if (!resultsZip.exists()) return null
    return runCatching {
        ZipFile(resultsZip).use { zip ->
            val entry = zip.getEntry("outputs/$relativePath") ?: zip.getEntry(relativePath) ?: return@use null
            zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
    }.getOrNull()
}

private fun buildStaticFallbackOutput(
    relativePath: String,
    mediaType: String?,
    summaryJson: String,
    claimLedgerJsonl: String
): String {
    val extension = relativePath.substringAfterLast('.', "")
    return when {
        mediaType?.contains("html", ignoreCase = true) == true || extension.equals("html", ignoreCase = true) -> """
            <!doctype html>
            <html lang="en">
            <head><meta charset="utf-8"><title>EvoMolior Static Fallback</title></head>
            <body>
            <main>
            <h1>EvoMolior Static Fallback</h1>
            <p>The requested final file was not present in the run archive. This deterministic fallback was generated from <code>summary.json</code> and <code>claim_ledger.jsonl</code>.</p>
            <h2>Summary</h2>
            <pre>${escapeHtml(summaryJson)}</pre>
            <h2>Claim Ledger</h2>
            <pre>${escapeHtml(claimLedgerJsonl.ifBlank { "No claim ledger entries were available." })}</pre>
            </main>
            </body>
            </html>
        """.trimIndent()
        mediaType?.contains("json", ignoreCase = true) == true || extension.equals("json", ignoreCase = true) ->
            cliJson.encodeToString(
                mapOf(
                    "fallback" to "static_summary_claim_ledger",
                    "summary_json" to summaryJson,
                    "claim_ledger_jsonl" to claimLedgerJsonl
                )
            )
        else -> buildString {
            appendLine("# EvoMolior Static Fallback")
            appendLine()
            appendLine("The requested final file was not present in the run archive. This deterministic fallback was generated from `summary.json` and `claim_ledger.jsonl`.")
            appendLine()
            appendLine("## Summary")
            appendLine("```json")
            appendLine(summaryJson)
            appendLine("```")
            appendLine()
            appendLine("## Claim Ledger")
            appendLine("```jsonl")
            appendLine(claimLedgerJsonl.ifBlank { "No claim ledger entries were available." })
            appendLine("```")
        }
    }
}

private fun escapeHtml(value: String): String = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")

private fun inferArchivedPublishedOutputRelativePath(stageOutputDir: Path): String? {
    val resultsZip = stageOutputDir.resolve("results.zip").toFile()
    if (!resultsZip.exists()) return null
    return runCatching {
        ZipFile(resultsZip).use { zip ->
            val preferred = listOf(
                "outputs/final-article.html",
                "outputs/final-output.html",
                "outputs/report.html"
            ).firstOrNull { path -> zip.getEntry(path) != null }
            preferred
                ?: zip.entries().asSequence()
                    .filterNot { it.isDirectory }
                    .map { it.name }
                    .filter { it.startsWith("outputs/") }
                    .filterNot { it.startsWith("outputs/assets/") }
                    .firstOrNull { it.endsWith(".html", ignoreCase = true) }
        }?.removePrefix("outputs/")
    }.getOrNull()
}

private fun publishStageAssetOutputs(
    stageOutputDir: Path,
    pipelineOutputDir: Path
) {
    val resultsZip = stageOutputDir.resolve("results.zip").toFile()
    if (!resultsZip.exists()) return
    runCatching {
        ZipFile(resultsZip).use { zip ->
            zip.entries().asSequence()
                .filter { !it.isDirectory }
                .filter { it.name.startsWith("outputs/assets/") }
                .forEach { entry ->
                    val relativePath = entry.name.removePrefix("outputs/")
                    val target = resolvePublishedOutputTarget(
                        pipelineOutputDir = pipelineOutputDir,
                        relativePath = relativePath
                    ) ?: return@forEach
                    Files.createDirectories(target.parent)
                    zip.getInputStream(entry).use { input ->
                        Files.write(target, input.readBytes())
                    }
                }
        }
    }.onFailure { err ->
        Napier.w("CliPipeline: failed to publish stage asset outputs: ${err.message}")
    }
}

private fun resolvePublishedOutputTarget(
    pipelineOutputDir: Path,
    relativePath: String
): Path? {
    val normalizedRoot = pipelineOutputDir.toAbsolutePath().normalize()
    val normalizedTarget = normalizedRoot.resolve(relativePath).normalize()
    return normalizedTarget.takeIf { it.startsWith(normalizedRoot) }
}

private fun writePipelineResultsZip(runOutputDir: Path) {
    val zipBytes = ByteArrayOutputStream()
    ZipOutputStream(zipBytes).use { zip ->
        val outputsRoot = runOutputDir.resolve("outputs").toFile()
        if (outputsRoot.exists()) {
            outputsRoot.walkTopDown()
                .filter { it.isFile }
                .filter {
                    isPublishablePipelineOutput(it.relativeTo(outputsRoot).invariantSeparatorsPath)
                }
                .sortedBy { it.relativeTo(outputsRoot).invariantSeparatorsPath }
                .forEach { file ->
                    zip.putNextEntry(ZipEntry("outputs/${file.relativeTo(outputsRoot).invariantSeparatorsPath}"))
                    zip.write(file.readBytes())
                    zip.closeEntry()
                }
        }
        val pipelineSummary = runOutputDir.resolve("pipeline-summary.json").toFile()
        if (pipelineSummary.exists()) {
            zip.putNextEntry(ZipEntry("pipeline-summary.json"))
            zip.write(pipelineSummary.readBytes())
            zip.closeEntry()
        }
    }
    Files.write(runOutputDir.resolve("pipeline-results.zip"), zipBytes.toByteArray())
}

internal fun isPublishablePipelineOutput(relativePath: String): Boolean =
    relativePath
        .replace('\\', '/')
        .split('/')
        .none { segment ->
            segment.isBlank() ||
                segment.startsWith('.') ||
                segment.equals("__MACOSX", ignoreCase = true)
        }

internal fun verifyPublishedStageOutput(
    stageOutputDir: Path,
    outputSpec: CliPipelineFinalOutputSpec,
    publishedOutputDir: Path? = null
): Path? {
    val resultsZip = stageOutputDir.resolve("results.zip").toFile()
    if (!resultsZip.exists()) return null

    val expectedEntry = "outputs/${outputSpec.relativePath}"
    val content = runCatching {
        ZipFile(resultsZip).use { zip ->
            val entry = zip.getEntry(expectedEntry) ?: return null
            zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { reader ->
                reader.readText()
            }
        }
    }.getOrNull() ?: return null

    val output = GeneratedOutputFile(
        relativePath = outputSpec.relativePath,
        mediaType = outputSpec.mediaType,
        content = content
    )
    val finalOutputSpec = FinalOutputSpec(
        relativePath = outputSpec.relativePath,
        mediaType = outputSpec.mediaType,
        description = outputSpec.description,
        kind = outputSpec.kind,
        validation = outputSpec.validation
    )
    val validationContext = FinalOutputValidationContext(
        claimLedgerJsonl = readOutputFromResultsZip(stageOutputDir, "claim_ledger.jsonl")
    )
    runCatching {
        FinalOutputContractValidator.requireValid(output, finalOutputSpec, validationContext)
    }.onFailure { error ->
        Napier.w("CliPipeline: rejected published output $expectedEntry: ${error.message}")
    }.getOrElse { return null }

    val publishedFile = publishedOutputDir?.resolve(outputSpec.relativePath)
    if (publishedOutputDir != null) {
        if (publishedFile == null || !Files.exists(publishedFile)) return null

        val publishedContent = runCatching { Files.readString(publishedFile) }.getOrNull() ?: return null
        if (publishedContent.trim() != content.trim()) return null
    }

    return publishedFile ?: stageOutputDir
}

private fun copyDirectoryContents(sourceDir: Path, targetDir: Path) {
    if (!Files.exists(sourceDir)) return
    Files.walk(sourceDir).use { stream ->
        stream.forEach { source ->
            val relative = sourceDir.relativize(source)
            val target = targetDir.resolve(relative.toString())
            if (Files.isDirectory(source)) {
                Files.createDirectories(target)
            } else {
                Files.createDirectories(target.parent)
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}

private fun copyIfExists(source: Path, target: Path) {
    if (!Files.exists(source)) return
    Files.createDirectories(target.parent)
    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
}

internal fun unzipPipelineBundle(zipFile: File, targetDir: Path) {
    val normalizedRoot = targetDir.toAbsolutePath().normalize()
    Files.createDirectories(normalizedRoot)
    require(!Files.isSymbolicLink(normalizedRoot)) {
        "Pipeline bundle target must not be a symbolic link: $normalizedRoot"
    }
    ZipFile(zipFile).use { zip ->
        val plannedEntries = zip.entries().asSequence().map { entry ->
            val normalizedEntryName = entry.name.replace('\\', '/')
            require(normalizedEntryName.isNotBlank()) {
                "Pipeline bundle contains a blank entry path"
            }
            val target = normalizedRoot.resolve(normalizedEntryName).normalize()
            require(target != normalizedRoot && target.startsWith(normalizedRoot)) {
                "Pipeline bundle entry escapes extraction root: ${entry.name}"
            }
            entry to target
        }.toList()
        val duplicateTargets = plannedEntries
            .groupingBy { (_, target) -> target }
            .eachCount()
            .filterValues { it > 1 }
            .keys
        require(duplicateTargets.isEmpty()) {
            "Pipeline bundle contains duplicate normalized paths: ${duplicateTargets.joinToString()}"
        }

        plannedEntries.forEach { (entry, target) ->
            if (entry.isDirectory) {
                Files.createDirectories(target)
            } else {
                Files.createDirectories(target.parent)
                zip.getInputStream(entry).use { input ->
                    Files.newOutputStream(target).use(input::copyTo)
                }
            }
        }
    }
}
