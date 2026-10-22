package com.achulkov.evomolior.cli

import com.achulkov.evomolior.api.events.EvoMoliorEvent
import com.achulkov.evomolior.api.model.FinalOutputSpec
import com.achulkov.evomolior.api.model.FinalOutputKind
import com.achulkov.evomolior.api.model.FinalOutputValidationContract
import com.achulkov.evomolior.api.model.RunConfig
import com.achulkov.evomolior.api.model.RunAutoCompletionPolicy
import com.achulkov.evomolior.api.model.AcceptanceDiagnosticMode
import com.achulkov.evomolior.core.config.CodexReasoningEffort
import com.achulkov.evomolior.core.config.CodexRuntimePreset
import com.achulkov.evomolior.api.model.requireAcceptanceFinalOutputSpec
import com.achulkov.evomolior.api.service.FinalOutputContractValidator
import com.achulkov.evomolior.api.service.FinalOutputValidationContext
import com.achulkov.evomolior.api.service.GeneratedOutputFile
import com.achulkov.evomolior.api.service.PersistedRunStateStore
import com.achulkov.evomolior.api.service.RunOutcomeBundleInspector
import com.achulkov.evomolior.api.service.RunOutcomePresentation
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
import com.achulkov.evomolior.core.council.QuantitativeToolObligation
import com.achulkov.evomolior.core.deliverable.AcceptanceStatus
import com.achulkov.evomolior.core.deliverable.ExecutionCompletionStatus
import com.achulkov.evomolior.core.deliverable.PrimaryArtifactValidationStatus
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
import java.io.ByteArrayInputStream
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
import java.util.zip.ZipInputStream
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
    val acceptanceProfile: AcceptanceProfile? = null,
    val acceptanceDiagnosticMode: AcceptanceDiagnosticMode? = null,
    val codexReasoningEffort: CodexReasoningEffort? = null,
    val codexRuntimePreset: CodexRuntimePreset? = null,
    val quantitativeToolObligation: QuantitativeToolObligation? = null,
    val ordinaryAgentTtlSeconds: Int? = null,
    val graderGroupCount: Int? = null
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
    val acceptanceDiagnosticMode: AcceptanceDiagnosticMode = AcceptanceDiagnosticMode.STRICT,
    val codexReasoningEffort: CodexReasoningEffort = CodexReasoningEffort.HIGH,
    val codexRuntimePreset: CodexRuntimePreset? = null,
    val quantitativeToolObligation: QuantitativeToolObligation? = null,
    val autoCompletionPolicy: RunAutoCompletionPolicy? = null,
    val ordinaryAgentTtlSeconds: Int? = null,
    val graderGroupCount: Int? = null
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

@Serializable
internal data class CliPipelineResultsManifestV1(
    val schemaVersion: String = SCHEMA_VERSION,
    val entries: List<CliPipelineResultsManifestEntry>,
    val manifestHash: String = "",
) {
    init {
        require(schemaVersion == SCHEMA_VERSION && entries.isNotEmpty()) {
            "Pipeline results manifest identity or entries are invalid"
        }
        require(entries == entries.sortedBy(CliPipelineResultsManifestEntry::path)) {
            "Pipeline results manifest entries must be sorted"
        }
        require(entries.distinctBy(CliPipelineResultsManifestEntry::path).size == entries.size) {
            "Pipeline results manifest paths must be unique"
        }
    }

    internal fun withComputedHash(): CliPipelineResultsManifestV1 =
        copy(manifestHash = pipelineResultsManifestHash(this))

    internal fun hasValidHash(): Boolean =
        manifestHash.matches(Regex("[0-9a-f]{64}")) &&
            MessageDigest.isEqual(
                manifestHash.encodeToByteArray(),
                pipelineResultsManifestHash(this).encodeToByteArray(),
            )

    companion object {
        const val SCHEMA_VERSION = "pipeline-results-manifest-v1"
        const val PATH = "pipeline-results-manifest-v1.json"
    }
}

@Serializable
internal data class CliPipelineResultsManifestEntry(
    val path: String,
    val sizeBytes: Long,
    val sha256: String,
) {
    init {
        require(path.isSafePipelineArchivePath() && path != CliPipelineResultsManifestV1.PATH) {
            "Pipeline results entry path is invalid"
        }
        require(sizeBytes >= 0L && sha256.matches(Regex("[0-9a-f]{64}"))) {
            "Pipeline results entry size or hash is invalid"
        }
    }
}

private const val CODE_IMPROVEMENT_PLAN_RELATIVE_PATH = "improvements/code-improvement-plan.json"

internal fun runCliPipeline(
    options: CliOptions,
    runtimeFactory: (File, CliOptions) -> CliRuntime,
    acceptanceAdmissionController: AcceptanceAdmissionController = AcceptanceAdmissionController.NONE,
    acceptanceAdmissionDiagnosticSink: (String) -> Unit = System.err::println
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
        acceptanceDiagnosticMode = configuredPrimary?.acceptanceDiagnosticMode
            ?: options.acceptanceDiagnosticMode,
        codexReasoningEffort = configuredPrimary?.codexReasoningEffort
            ?: options.codexReasoningEffort,
        codexRuntimePreset = configuredPrimary?.codexRuntimePreset ?: options.codexRuntimePreset,
        quantitativeToolObligation = configuredPrimary?.quantitativeToolObligation,
        ordinaryAgentTtlSeconds = configuredPrimary?.ordinaryAgentTtlSeconds,
        graderGroupCount = configuredPrimary?.graderGroupCount ?: options.graderGroupCount,
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
            acceptanceProfile = stageConfig.acceptanceProfile ?: AcceptanceProfile.NORMAL,
            acceptanceDiagnosticMode = stageConfig.acceptanceDiagnosticMode
                ?: AcceptanceDiagnosticMode.STRICT,
            codexReasoningEffort = stageConfig.codexReasoningEffort
                ?: options.codexReasoningEffort,
            codexRuntimePreset = stageConfig.codexRuntimePreset ?: options.codexRuntimePreset,
            quantitativeToolObligation = stageConfig.quantitativeToolObligation,
            ordinaryAgentTtlSeconds = stageConfig.ordinaryAgentTtlSeconds,
            graderGroupCount = stageConfig.graderGroupCount ?: options.graderGroupCount
        )
    }.orEmpty()

    stagePlans += configuredExtraStages
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
        acceptanceAdmissionDiagnosticSink(error.ownerDiagnostic())
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
        val result = loadCompletedStageResult(resolvedStagePlan, options.llmBackend)
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
                required = false
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
    validateCodexPreset(stage.codexRuntimePreset, llmBackend, stage.modelOverrides, stage.acceptanceProfile)
    if (
        stage.acceptanceDiagnosticMode ==
        AcceptanceDiagnosticMode.FULL_SWEEP_NON_ACCEPTING &&
        stage.acceptanceProfile == AcceptanceProfile.NORMAL
    ) {
        throw CliUsageException(
            "Pipeline stage ${stage.id} diagnostic sweep requires a non-normal profile"
        )
    }
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

private fun loadCompletedStageResult(
    stage: CliStagePlan,
    llmBackend: LlmBackend
): CliStageExecutionResult? {
    val summaryFile = stage.outputDir.toFile().resolve("summary.json")
    if (!summaryFile.exists()) return null
    val summary = cliJson.decodeFromString(CliRunSummary.serializer(), summaryFile.readText())
    if (!summary.status.equals("COMPLETED", ignoreCase = true)) return null
    require(summary.runId == stage.runId) { "Completed stage summary run ID differs from ${stage.runId}" }
    requireReusableRequest(stage, summary, llmBackend)
    requireNotNull(resolveStageArchive(stage.outputDir)) { "Completed stage ${stage.id} has no archive" }
    stage.finalOutputSpec?.let { outputSpec ->
        verifyPublishedStageOutput(
            stageOutputDir = stage.outputDir,
            outputSpec = outputSpec
        ) ?: error("Completed stage ${stage.id} has no valid required output ${outputSpec.relativePath}")
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

/**
 * Refuses to reuse a completed stage produced by a different request.
 *
 * Summaries written before request fingerprints existed fall back to comparing the prompt they
 * recorded; their stored model overrides are resolved runtime assignments, not the requested
 * overrides, so they cannot be compared, and nothing else was recorded.
 *
 * @param stage Stage about to run or be reused.
 * @param summary Completed summary found in the stage output directory.
 * @param llmBackend Backend selected for this invocation.
 * @throws CliUsageException when the stored result belongs to a different request.
 */
private fun requireReusableRequest(stage: CliStagePlan, summary: CliRunSummary, llmBackend: LlmBackend) {
    val mismatch = summary.requestFingerprint
        ?.let { stored ->
            val current = cliStageRequestFingerprint(stage, llmBackend)
            "request fingerprint $stored differs from $current".takeIf { stored != current }
        }
        ?: run {
            Napier.w(
                "EvoMolior CLI completed stage ${stage.id} has no request fingerprint; " +
                    "only its prompt can be compared before reuse"
            )
            "prompt differs".takeIf { summary.prompt != stage.prompt }
        }
    if (mismatch != null) {
        throw CliUsageException(
            "Completed stage ${stage.id} in ${stage.outputDir} was produced by a different request " +
                "($mismatch: prompt, input files, output contract, or run configuration changed). " +
                "Refusing to return it as this run's result; use a new -run-id or -output directory."
        )
    }
}

private suspend fun executeCliStage(
    stage: CliStagePlan,
    rootOptions: CliOptions,
    runtimeFactory: (File, CliOptions) -> CliRuntime,
    acceptanceLease: AcceptanceRunLeaseHandle? = null
): CliStageExecutionResult = coroutineScope {
    // Bound to the inputs as they are when the run starts, before agents can read them.
    val requestFingerprint = cliStageRequestFingerprint(stage, rootOptions.llmBackend)
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
        acceptanceDiagnosticMode = stage.acceptanceDiagnosticMode,
        codexReasoningEffort = stage.codexReasoningEffort,
        codexRuntimePreset = stage.codexRuntimePreset,
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
        stage.codexRuntimePreset != null -> stage.modelOverrides
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
        acceptanceDiagnosticMode = stage.acceptanceDiagnosticMode,
        codexReasoningEffort = stage.codexReasoningEffort,
        codexRuntimePreset = stage.codexRuntimePreset,
        quantitativeToolObligation = stage.quantitativeToolObligation,
        autoCompletionPolicy = stage.autoCompletionPolicy,
        modelOverrides = modelOverrides,
        contextArtifacts = allInputArtifacts,
        finalOutputSpec = stage.finalOutputSpec?.toRunFinalOutputSpec(),
        isTestRun = false,
        budgetOverride = stage.budgetOverride ?: objectiveProfile.budgetOverride,
        enforceProviderMonetaryCostCap = stage.enforceProviderMonetaryCostCap,
        ordinaryAgentTtlSeconds = stage.ordinaryAgentTtlSeconds,
        graderGroupCountOverride = stage.graderGroupCount
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
    val storageLocations = RunStoragePreflight.locations(rootOptions)
    val storageFloorBytes = runCatching { RunStoragePreflight.requiredFreeBytes() }.getOrDefault(0L)
    val eventCollector = launch(Dispatchers.Default) {
        runtime.engineService.events.collect { event ->
            when (event) {
                is EvoMoliorEvent.RunStarted -> Napier.i("EvoMolior CLI run started runId=${event.runId}")
                is EvoMoliorEvent.RoundStarted -> {
                    Napier.i("EvoMolior CLI round started round=${event.roundIndex}")
                    RunStoragePreflight.warnIfLow(
                        storageLocations,
                        storageFloorBytes,
                        "round ${event.roundIndex} start"
                    )
                }
                is EvoMoliorEvent.RoundCompleted -> {
                    acceptanceLease?.verifyStable("round-${event.result.roundIndex}-boundary")
                    Napier.i("EvoMolior CLI round completed round=${event.result.roundIndex}")
                }
                is EvoMoliorEvent.RunCompleted -> {
                    acceptanceLease?.verifyStable("run-completion")
                    Napier.i("EvoMolior CLI run completed runId=${event.runId} winner=${event.winnerId}")
                    if (!completion.isCompleted) completion.complete(event)
                }
                is EvoMoliorEvent.RunCompletedProvisional -> {
                    acceptanceLease?.verifyStable("run-completion-provisional")
                    Napier.w(
                        "EvoMolior CLI run completed provisionally runId=${event.runId} " +
                            "reason=${event.reason}"
                    )
                    if (!completion.isCompleted) completion.complete(event)
                }
                is EvoMoliorEvent.RunRecovered -> {
                    acceptanceLease?.verifyStable("run-recovery")
                    Napier.w(
                        "EvoMolior CLI run recovered without acceptance runId=${event.runId} " +
                            "reason=${event.reason}"
                    )
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
    var stageCancelled = false
    val completedEvent = try {
        withCliRunShutdown(
            abort = {
                if (runtime.engineService.getState().activeRunId == stage.runId) {
                    runtime.engineService.abortRun(stage.runId, "CLI stage cancelled or failed")
                }
            },
        ) {
            if (shouldResumePersistedRun) {
                Napier.i("EvoMolior CLI resuming persisted runId=${stage.runId}")
                runtime.engineService.resumePersistedRun(runConfig)
            } else {
                runtime.engineService.startRun(runConfig)
            }
            completion.await()
        }
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        stageCancelled = true
        throw cancelled
    } catch (error: Exception) {
        val persistedRejection = StartupRejectionFileStore { runId ->
            dataRoot.resolve("evomolior/runs/$runId/control")
        }.read(stage.runId)
        val terminalBundle = runCatching {
            runtime.engineService.getResultsBundle(stage.runId)
        }.onFailure(error::addSuppressed).getOrNull()
        val terminalOutcome = terminalBundle?.let { bytes ->
            runCatching { RunOutcomeBundleInspector.inspect(bytes) }
                .onFailure(error::addSuppressed)
                .getOrNull()
                ?.takeIf {
                    it.outcomeClass ==
                        com.achulkov.evomolior.core.deliverable.RunOutcomeClass
                            .STARTUP_DIAGNOSTIC_PACKAGE
                }
        }
        fileAntilog?.close()
        return@coroutineScope rejectedStageResult(
            stage = stage,
            runOutputDir = runOutputDir.toPath(),
            rootOutputDir = rootOptions.outputDir,
            modelOverrides = modelOverrides,
            error = error,
            artifact = persistedRejection,
            terminalBundle = terminalBundle.takeIf { terminalOutcome != null },
            terminalOutcome = terminalOutcome,
        )
    } finally {
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            eventCollector.cancelAndJoin()
            if (stageCancelled) fileAntilog?.close()
        }
    }

    val durationMs = Duration.between(startedAt, Instant.now()).toMillis()
    val resultsBundle = runtime.engineService.getResultsBundle(stage.runId)
    val outcomePresentation = runCatching {
        runtime.engineService.getRunOutcomePresentation(stage.runId)
    }.onFailure { error ->
        Napier.w(
            "EvoMolior CLI could not decode typed outcome for runId=${stage.runId}; " +
                "preserving the terminal bundle as legacy-compatible output: ${error.message}"
        )
    }.getOrNull()
    val resultsBundleFile = resultsBundle?.let {
        val file = runOutputDir.resolve(
            when (outcomePresentation?.outcomeClass) {
                com.achulkov.evomolior.core.deliverable.RunOutcomeClass
                    .ACCEPTED_DECISION_PACKAGE -> "results.zip"
                com.achulkov.evomolior.core.deliverable.RunOutcomeClass
                    .PROVISIONAL_DECISION_PACKAGE -> "recovery-results.zip"
                com.achulkov.evomolior.core.deliverable.RunOutcomeClass
                    .STARTUP_DIAGNOSTIC_PACKAGE -> "startup-diagnostic.zip"
                null -> "terminal-results.zip"
            }
        )
        Files.write(file.toPath(), it)
        file
    }
    val eventTerminalOutcome = when (completedEvent) {
        is EvoMoliorEvent.RunCompletedProvisional -> completedEvent.terminalOutcome
        is EvoMoliorEvent.RunRecovered -> completedEvent.terminalOutcome
        else -> null
    }
    val executionStatus = outcomePresentation?.executionStatus
        ?: eventTerminalOutcome?.executionStatus
        ?: if (completedEvent is EvoMoliorEvent.RunCompleted) {
            ExecutionCompletionStatus.COMPLETED
        } else {
            null
        }
    val acceptanceStatus = outcomePresentation?.acceptanceStatus
        ?: eventTerminalOutcome?.acceptanceStatus
    val primaryArtifactPath = outcomePresentation
        ?.let { presentation ->
            materializePrimaryArtifact(
                resultsBundle = resultsBundle,
                presentation = presentation,
                outputDirectory = runOutputDir.toPath(),
            )
        }
    val summaryStatus = when {
        executionStatus == ExecutionCompletionStatus.COMPLETED -> "COMPLETED"
        completedEvent is EvoMoliorEvent.RunAborted -> "ABORTED"
        completedEvent is EvoMoliorEvent.RunRecovered -> "RECOVERED"
        else -> "UNKNOWN"
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
        status = summaryStatus,
        winnerId = (completedEvent as? EvoMoliorEvent.RunCompleted)?.winnerId,
        modelOverrides = modelOverrides,
        terminalReason = when (completedEvent) {
            is EvoMoliorEvent.RunCompletedProvisional -> completedEvent.reason
            is EvoMoliorEvent.RunRecovered -> completedEvent.reason
            is EvoMoliorEvent.RunAborted -> completedEvent.reason
            else -> null
        },
        outcome = outcomePresentation,
        executionStatus = executionStatus,
        acceptanceStatus = acceptanceStatus,
        primaryArtifactPath = primaryArtifactPath,
        primaryArtifactValidationStatus =
            outcomePresentation?.primaryArtifactValidationStatus
                ?: eventTerminalOutcome?.primaryArtifactValidationStatus,
        processExitCode = if (summaryStatus == "COMPLETED") 0 else 1,
        requestFingerprint = requestFingerprint,
    )

    Files.writeString(
        runOutputDir.resolve("summary.json").toPath(),
        cliJson.encodeToString(CliRunSummary.serializer(), summary)
    )

    Napier.i("EvoMolior CLI wrote summary to ${runOutputDir.resolve("summary.json").absolutePath}")
    outcomePresentation?.let { outcome ->
        Napier.i(
            "EvoMolior CLI outcome=${outcome.outcomeClass} " +
                "manifest=${outcome.manifestHash} artifacts=${outcome.artifactPaths.size}"
        )
    }
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

private fun materializePrimaryArtifact(
    resultsBundle: ByteArray?,
    presentation: RunOutcomePresentation,
    outputDirectory: Path,
): String? {
    val archivePath = presentation.primaryArtifactPath ?: return null
    val bytes = resultsBundle ?: return null
    val verified = RunOutcomeBundleInspector.inspect(bytes)
    require(verified.runId == presentation.runId && verified.manifestHash == presentation.manifestHash &&
        archivePath in verified.artifactPaths) { "Primary artifact does not match verified archive authority" }
    publishStageSourceSidecars(bytes, outputDirectory, presentation.runId)
    require(
        archivePath.isNotBlank() &&
            !archivePath.startsWith('/') &&
            archivePath.split('/').none { it.isBlank() || it == "." || it == ".." }
    ) { "Primary artifact archive path is unsafe: $archivePath" }
    val artifactName = archivePath.substringAfterLast('/')
    val target = outputDirectory.resolve("primary").resolve(artifactName)
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            if (entry.name == archivePath) {
                Files.createDirectories(target.parent)
                Files.write(target, zip.readBytes())
                return target.toAbsolutePath().toString()
            }
            zip.closeEntry()
        }
    }
    return null
}

private fun rejectedStageResult(
    stage: CliStagePlan,
    runOutputDir: Path,
    rootOutputDir: Path,
    modelOverrides: Map<String, String>,
    error: Throwable,
    artifact: StartupRejectionArtifact?,
    terminalBundle: ByteArray? = null,
    terminalOutcome: RunOutcomePresentation? = null,
): CliStageExecutionResult {
    val persisted = persistCliStartupRejection(
        stage = stage,
        preferredOutputDir = runOutputDir,
        outputRoot = rootOutputDir,
        error = error,
        artifact = artifact,
        modelOverrides = modelOverrides,
        terminalBundle = terminalBundle,
        terminalOutcome = terminalOutcome,
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
    terminalBundle: ByteArray? = null,
    terminalOutcome: RunOutcomePresentation? = null,
    publicCopyPublisher: (Path, Path) -> Unit = ::publishImmutableStartupRejectionCopy
): PersistedCliStartupRejection {
    require((terminalBundle == null) == (terminalOutcome == null)) {
        "Startup diagnostic bundle and typed outcome must be supplied together"
    }
    require(
        terminalOutcome == null ||
            terminalOutcome.outcomeClass ==
            com.achulkov.evomolior.core.deliverable.RunOutcomeClass.STARTUP_DIAGNOSTIC_PACKAGE
    ) { "Rejected CLI stage can attach only a startup diagnostic terminal outcome" }
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
                message = "Startup admission was rejected before runtime activation."
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
    val terminalBundlePath = terminalBundle?.let { bytes ->
        outputDirectory.resolve("startup-diagnostic.zip").also { path ->
            Files.write(path, bytes)
        }
    }
    val summary = CliRunSummary(
        runId = stage.runId,
        prompt = stage.prompt,
        inputDirectory = stage.inputDir.toString(),
        inputArtifactCount = 0,
        outputDirectory = outputDirectory.toAbsolutePath().toString(),
        completedAt = Instant.now().toString(),
        durationMs = 0,
        debugLogPath = null,
        resultsBundlePath = terminalBundlePath?.toAbsolutePath()?.toString(),
        status = "REJECTED",
        winnerId = null,
        modelOverrides = modelOverrides,
        terminalReason = terminalOutcome
            ?.startupDiagnostic
            ?.issues
            ?.joinToString(" ") { it.publicMessage }
            ?: "Startup admission was rejected before runtime activation.",
        outcome = terminalOutcome,
        executionStatus = terminalOutcome?.executionStatus,
        acceptanceStatus = terminalOutcome?.acceptanceStatus,
        primaryArtifactPath = terminalOutcome?.primaryArtifactPath,
        primaryArtifactValidationStatus = terminalOutcome?.primaryArtifactValidationStatus,
        processExitCode = 1,
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
        resolveStageArchive(previousStage.stageOutputDir)?.let { bundle ->
            Files.copy(bundle.toPath(), previousStageDir.resolve(bundle.name), StandardCopyOption.REPLACE_EXISTING)
            unzipPipelineBundle(bundle, previousStageDir.resolve("results-bundle"))
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

/**
 * Resolves only a stage-local archive, retaining its recorded outcome kind.
 * Typed bundles must pass the shared outcome verifier and match the summary authority.
 */
private fun resolveStageArchive(stageOutputDir: Path): File? {
    val summaryPath = stageOutputDir.resolve("summary.json")
    val summary = if (Files.exists(summaryPath)) {
        cliJson.decodeFromString(CliRunSummary.serializer(), Files.readString(summaryPath))
    } else null
    val expected = summary?.outcome
    val expectedName = when (expected?.outcomeClass) {
        com.achulkov.evomolior.core.deliverable.RunOutcomeClass.ACCEPTED_DECISION_PACKAGE -> "results.zip"
        com.achulkov.evomolior.core.deliverable.RunOutcomeClass.PROVISIONAL_DECISION_PACKAGE -> "recovery-results.zip"
        com.achulkov.evomolior.core.deliverable.RunOutcomeClass.STARTUP_DIAGNOSTIC_PACKAGE -> "startup-diagnostic.zip"
        null -> null
    }
    // Old summaries store absolute paths. Use only their basename within this stage,
    // so moved runs remain reusable without reading files outside the managed directory.
    val recordedName = summary?.resultsBundlePath?.let { Path.of(it).fileName.toString() }
    require(recordedName == null || recordedName in setOf(
        "results.zip", "recovery-results.zip", "startup-diagnostic.zip", "terminal-results.zip"
    )) { "Unsupported recorded stage archive: $recordedName" }
    require(expectedName == null || recordedName == null || recordedName == expectedName) {
        "Recorded stage archive kind differs from summary outcome"
    }
    val archive = stageOutputDir.resolve(recordedName ?: expectedName ?: "results.zip").toFile()
    if (!archive.exists()) {
        check(expected == null && recordedName == null) { "Missing recorded stage archive: ${archive.name}" }
        return null
    }
    require(!Files.isSymbolicLink(archive.toPath())) { "Stage archive must not be a symbolic link" }
    val hasTypedManifest = ZipFile(archive).use {
        it.getEntry(com.achulkov.evomolior.api.service.RunManifestVerifier.OUTCOME_MANIFEST_PATH) != null
    }
    val hasLegacyRecoveryManifest = !hasTypedManifest && ZipFile(archive).use {
        it.getEntry(com.achulkov.evomolior.api.service.RecoveryBundleManifest.MANIFEST_PATH) != null
    }
    if (hasLegacyRecoveryManifest) {
        require(expected == null && summary != null && summary.winnerId == null &&
            summary.acceptanceStatus != AcceptanceStatus.ACCEPTED &&
            (summary.status == "RECOVERED" || summary.acceptanceStatus == AcceptanceStatus.PROVISIONAL)
        ) {
            "Legacy recovery cannot replace typed or accepted summary authority"
        }
        com.achulkov.evomolior.api.service.LegacyRecoveryBundleVerifier.verify(
            archive.readBytes(), summary.runId,
        )
        return archive
    }
    if (expected != null || hasTypedManifest || archive.name == "recovery-results.zip" ||
        archive.name == "startup-diagnostic.zip"
    ) {
        val verified = RunOutcomeBundleInspector.inspect(archive.readBytes())
        requireNotNull(expected) { "Typed stage archive requires a typed summary outcome" }
        require(
            verified.runId == requireNotNull(summary).runId &&
                verified.runId == expected.runId &&
                verified.outcomeClass == expected.outcomeClass &&
                verified.manifestHash == expected.manifestHash
        ) { "Stage archive does not match summary manifest, outcome, or run ID" }
    }
    return archive
}

private fun publishStageOutput(
    stageOutputDir: Path,
    relativePath: String,
    pipelineOutputDir: Path,
    required: Boolean = true
) {
    val resultsZip = resolveStageArchive(stageOutputDir)
    if (resultsZip == null) {
        check(!required) { "Missing stage archive for required output $relativePath" }
        return
    }
    var published = false
    run {
        ZipFile(resultsZip).use { zip ->
            val archivePath = outputArchiveEntryCandidates(relativePath)
                .firstOrNull { zip.getEntry(it) != null }
                ?: return@use
            val target = resolvePublishedOutputTarget(
                pipelineOutputDir = pipelineOutputDir,
                relativePath = relativePath
            ) ?: error("Unsafe requested output path: $relativePath")
            Files.createDirectories(target.parent)
            zip.getInputStream(requireNotNull(zip.getEntry(archivePath))).use { input ->
                Files.write(target, input.readBytes())
            }
            published = true
        }
    }
    if (published) publishStageSourceSidecars(resultsZip, pipelineOutputDir)
    check(published || !required) { "Stage archive is missing required output $relativePath" }
}

/**
 * Copies only manifest-verified advisory attachments; never rewrites historical ZIPs.
 * @param archive Immutable stage archive.
 * @param pipelineOutputDir Public output directory included in the pipeline manifest.
 * @param expectedRunId Independent run identity; legacy recovery requires this or a stage summary.
 */
internal fun publishStageSourceSidecars(
    archive: File,
    pipelineOutputDir: Path,
    expectedRunId: String? = null,
) {
    val runId = expectedRunId ?: archive.parentFile.resolve("summary.json").takeIf(File::isFile)?.let {
        cliJson.decodeFromString(CliRunSummary.serializer(), it.readText()).runId
    }
    publishStageSourceSidecars(archive.readBytes(), pipelineOutputDir, runId)
}

private fun publishStageSourceSidecars(bytes: ByteArray, pipelineOutputDir: Path, expectedRunId: String?) {
    val sidecars = linkedMapOf<String, ByteArray>()
    var hasOutcomeManifest = false
    var hasLegacyRecoveryManifest = false
    ZipInputStream(bytes.inputStream()).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            if (entry.name == com.achulkov.evomolior.api.service.RunManifestVerifier.OUTCOME_MANIFEST_PATH) {
                hasOutcomeManifest = true
            }
            if (entry.name == com.achulkov.evomolior.api.service.RecoveryBundleManifest.MANIFEST_PATH) {
                hasLegacyRecoveryManifest = true
            }
            if (!entry.isDirectory && entry.name.startsWith("source-checks/")) {
                require(sidecars.put(entry.name, zip.readBytes()) == null) { "Duplicate source-check attachment" }
            }
            zip.closeEntry()
        }
    }
    if (sidecars.isNotEmpty()) {
        val verifiedPaths = if (
            hasOutcomeManifest
        ) {
            RunOutcomeBundleInspector.inspect(bytes).also {
                require(expectedRunId == null || it.runId == expectedRunId) { "Source-check run identity mismatch" }
            }.artifactPaths.toSet()
        } else if (hasLegacyRecoveryManifest) {
            com.achulkov.evomolior.api.service.LegacyRecoveryBundleVerifier.verify(
                bytes,
                requireNotNull(expectedRunId) { "Legacy recovery publication requires independent run identity" },
            ).committedBundleEntries.mapTo(mutableSetOf()) { it.path }
        } else {
            val verified = com.achulkov.evomolior.api.service.RunManifestVerifier.verify(
                bytes, expectedRunId = expectedRunId,
            )
            require(verified.isValid) { "Source-check publication requires a verified archive manifest" }
            requireNotNull(verified.manifest).entries.mapTo(mutableSetOf()) { it.path }
        }
        sidecars.toSortedMap().forEach { (name, content) ->
            require(name in verifiedPaths) { "Uncommitted source-check attachment: $name" }
            require(name.matches(Regex("source-checks/[0-9a-f]{64}\\.md"))) {
                "Unsafe source-check attachment identifier: $name"
            }
            val target = requireNotNull(resolvePublishedOutputTarget(pipelineOutputDir, name)) {
                "Unsafe source-check attachment path: $name"
            }
            require(generateSequence(target) { it.parent }.none(Files::isSymbolicLink)) {
                "Source-check destination must not be a symbolic link"
            }
            Files.createDirectories(target.parent)
            if (Files.exists(target)) {
                require(Files.readAllBytes(target).contentEquals(content)) {
                    "Source-check destination already contains different immutable content: $name"
                }
            } else {
                Files.write(target, content, java.nio.file.StandardOpenOption.CREATE_NEW)
            }
        }
    }
}

private fun readOutputFromResultsZip(stageOutputDir: Path, relativePath: String): String? {
    val resultsZip = resolveStageArchive(stageOutputDir) ?: return null
    return run {
        ZipFile(resultsZip).use { zip ->
            val entry = outputArchiveEntryCandidates(relativePath).firstNotNullOfOrNull(zip::getEntry)
                ?: zip.getEntry(relativePath) ?: return@use null
            zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
    }
}

private fun inferArchivedPublishedOutputRelativePath(stageOutputDir: Path): String? {
    val resultsZip = resolveStageArchive(stageOutputDir) ?: return null
    return run {
        ZipFile(resultsZip).use { zip ->
            if (zip.getEntry(com.achulkov.evomolior.api.service.RunManifestVerifier.OUTCOME_MANIFEST_PATH) != null) {
                RunOutcomeBundleInspector.inspect(resultsZip.readBytes()).primaryArtifactPath
                    ?.takeIf { it.startsWith("outputs/") }
                    // Plans use the opt-in publication branch or an explicit final-output request.
                    ?.takeUnless {
                        it.removePrefix("outputs/").removePrefix("recovered/") ==
                            CODE_IMPROVEMENT_PLAN_RELATIVE_PATH
                    }
                    ?.let { return@use it }
            }
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
                    .filterNot { it.startsWith("outputs/recovered/assets/") }
                    .firstOrNull { it.endsWith(".html", ignoreCase = true) }
        }?.removePrefix("outputs/")?.removePrefix("recovered/")
    }
}

private fun publishStageAssetOutputs(
    stageOutputDir: Path,
    pipelineOutputDir: Path
) {
    val resultsZip = resolveStageArchive(stageOutputDir) ?: return
    run {
        ZipFile(resultsZip).use { zip ->
            zip.entries().asSequence()
                .filter { !it.isDirectory }
                .filter { it.name.startsWith("outputs/assets/") || it.name.startsWith("outputs/recovered/assets/") }
                .forEach { entry ->
                    val relativePath = entry.name.removePrefix("outputs/").removePrefix("recovered/")
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
    }
}

private fun resolvePublishedOutputTarget(
    pipelineOutputDir: Path,
    relativePath: String
): Path? {
    if (!relativePath.isSafePipelineArchivePath()) return null
    val normalizedRoot = pipelineOutputDir.toAbsolutePath().normalize()
    val normalizedTarget = normalizedRoot.resolve(relativePath).normalize()
    return normalizedTarget.takeIf { it.startsWith(normalizedRoot) }
}

private fun outputArchiveEntryCandidates(relativePath: String): List<String> =
    listOf(
        "outputs/$relativePath",
        "outputs/recovered/$relativePath",
    )

private fun writePipelineResultsZip(runOutputDir: Path) {
    val payload = linkedMapOf<String, ByteArray>()
    val outputsRoot = runOutputDir.resolve("outputs").toFile()
    if (outputsRoot.exists()) {
        outputsRoot.walkTopDown()
            .filter { it.isFile }
            .filter {
                isPublishablePipelineOutput(it.relativeTo(outputsRoot).invariantSeparatorsPath)
            }
            .sortedBy { it.relativeTo(outputsRoot).invariantSeparatorsPath }
            .forEach { file ->
                payload["outputs/${file.relativeTo(outputsRoot).invariantSeparatorsPath}"] =
                    file.readBytes()
            }
    }
    val pipelineSummary = runOutputDir.resolve("pipeline-summary.json").toFile()
    if (pipelineSummary.exists()) {
        payload["pipeline-summary.json"] = pipelineSummary.readBytes()
    }
    val manifest = CliPipelineResultsManifestV1(
        entries = payload.entries
            .map { (path, bytes) ->
                CliPipelineResultsManifestEntry(
                    path = path,
                    sizeBytes = bytes.size.toLong(),
                    sha256 = sha256Hex(bytes),
                )
            }
            .sortedBy(CliPipelineResultsManifestEntry::path),
    ).withComputedHash()
    val allEntries = payload.toMutableMap().apply {
        put(
            CliPipelineResultsManifestV1.PATH,
            pipelineResultsJson.encodeToString(
                CliPipelineResultsManifestV1.serializer(),
                manifest,
            ).encodeToByteArray(),
        )
    }
    val archiveBytes = ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use { zip ->
            allEntries.toSortedMap().forEach { (path, bytes) ->
                zip.putNextEntry(ZipEntry(path).apply { time = PIPELINE_ZIP_EPOCH_1980_MS })
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        output.toByteArray()
    }
    verifyPipelineResultsZip(archiveBytes)
    val target = runOutputDir.resolve("pipeline-results.zip")
    val temporary = runOutputDir.resolve("pipeline-results.zip.tmp")
    try {
        Files.write(temporary, archiveBytes)
        verifyPipelineResultsZip(Files.readAllBytes(temporary))
        Files.move(
            temporary,
            target,
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
        verifyPipelineResultsZip(Files.readAllBytes(target))
    } finally {
        Files.deleteIfExists(temporary)
    }
}

internal fun verifyPipelineResultsZip(bytes: ByteArray): CliPipelineResultsManifestV1 {
    val files = linkedMapOf<String, ByteArray>()
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            require(
                !entry.isDirectory &&
                    entry.name.isSafePipelineArchivePath() &&
                    files.put(entry.name, zip.readBytes()) == null
            ) { "Pipeline results ZIP contains an invalid or duplicate entry ${entry.name}" }
            zip.closeEntry()
        }
    }
    val manifest = pipelineResultsJson.decodeFromString(
        CliPipelineResultsManifestV1.serializer(),
        requireNotNull(files[CliPipelineResultsManifestV1.PATH]).decodeToString(),
    )
    require(manifest.hasValidHash()) { "Pipeline results manifest hash is invalid" }
    val payload = files - CliPipelineResultsManifestV1.PATH
    require(payload.keys == manifest.entries.mapTo(linkedSetOf()) { it.path }) {
        "Pipeline results ZIP has unattested or missing payload entries"
    }
    manifest.entries.forEach { entry ->
        val content = payload.getValue(entry.path)
        require(
            content.size.toLong() == entry.sizeBytes &&
                sha256Hex(content) == entry.sha256
        ) { "Pipeline results entry authority mismatch for ${entry.path}" }
    }
    return manifest
}

private fun pipelineResultsManifestHash(
    manifest: CliPipelineResultsManifestV1
): String = sha256Hex(
    pipelineResultsJson.encodeToString(
        CliPipelineResultsManifestV1.serializer(),
        manifest.copy(manifestHash = ""),
    ).encodeToByteArray()
)

private fun String.isSafePipelineArchivePath(): Boolean =
    isNotBlank() &&
        !startsWith("/") &&
        replace('\\', '/').split('/').none { it.isBlank() || it == ".." }

private val pipelineResultsJson = kotlinx.serialization.json.Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
}

private const val PIPELINE_ZIP_EPOCH_1980_MS = 315_532_800_000L

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
    val resultsZip = resolveStageArchive(stageOutputDir) ?: return null

    val archived = runCatching {
        ZipFile(resultsZip).use { zip ->
            val archivePath = outputArchiveEntryCandidates(outputSpec.relativePath)
                .firstOrNull { zip.getEntry(it) != null }
                ?: return@use null
            archivePath to zip.getInputStream(
                requireNotNull(zip.getEntry(archivePath))
            ).bufferedReader(Charsets.UTF_8).use { reader ->
                reader.readText()
            }
        }
    }.getOrNull() ?: return null
    val archivePath = archived.first
    val content = archived.second

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
        Napier.w("CliPipeline: rejected published output $archivePath: ${error.message}")
    }.getOrElse { return null }

    val publishedFile = publishedOutputDir?.let {
        resolvePublishedOutputTarget(it, outputSpec.relativePath)
    }
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
