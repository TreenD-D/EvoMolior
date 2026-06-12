package com.achulkov.evokammer.cli

import com.achulkov.evokammer.api.events.EvoKammerEvent
import com.achulkov.evokammer.api.model.FinalOutputSpec
import com.achulkov.evokammer.api.model.RunConfig
import com.achulkov.evokammer.api.service.PersistedRunStateStore
import com.achulkov.evokammer.api.util.loadRunInputArtifacts
import com.achulkov.evokammer.cli.selfimprovement.CodeImprovementPlanApplicator
import com.achulkov.evokammer.core.config.FastModelSelectionConfig
import com.achulkov.evokammer.core.config.RunObjectiveHeuristics
import com.achulkov.evokammer.core.model.GroupProfileType
import io.github.aakira.napier.DebugAntilog
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@Serializable
internal data class CliPipelineConfig(
    val stages: List<CliPipelineStageConfig> = emptyList()
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
    val budgetOverride: Double? = null,
    val inheritsPreviousOutputs: Boolean = true,
    val modelOverrides: Map<String, String> = emptyMap(),
    val finalOutput: CliPipelineFinalOutputSpec? = null
)

@Serializable
internal data class CliPipelineFinalOutputSpec(
    val relativePath: String,
    val mediaType: String,
    val description: String = ""
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
    val budgetOverride: Double?,
    val inheritsPreviousOutputs: Boolean,
    val modelOverrides: Map<String, String>,
    val finalOutputSpec: CliPipelineFinalOutputSpec?
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
    runtimeFactory: (File, CliOptions) -> CliRuntime
): CliRunResult = runBlocking {
    val pipelineRunId = options.runId ?: "cli-${UUID.randomUUID()}"
    val outputRoot = options.outputDir.toFile().absoluteFile
    val runOutputDir = outputRoot.resolve(pipelineRunId).apply { mkdirs() }
    val pipelineConfig = loadPipelineConfig(options.pipelineConfigPath)

    val stagePlans = mutableListOf<CliStagePlan>()
    val primaryOutputSpec = defaultFinalOutputSpec(
        prompt = options.prompt,
        preferredPath = "final-article.html"
    )
    stagePlans += CliStagePlan(
        id = "primary",
        prompt = options.prompt,
        inputDir = options.inputDir,
        outputDir = runOutputDir.toPath(),
        runId = pipelineRunId,
        fast = options.fast,
        rounds = options.rounds ?: if (options.fast) 5 else 1,
        groupCount = options.groupCount,
        maxAgentsPerGroup = options.maxAgentsPerGroup,
        budgetOverride = options.budgetOverride,
        inheritsPreviousOutputs = false,
        modelOverrides = options.modelOverrides,
        finalOutputSpec = primaryOutputSpec
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
            budgetOverride = stageConfig.budgetOverride,
            inheritsPreviousOutputs = stageConfig.inheritsPreviousOutputs,
            modelOverrides = stageConfig.modelOverrides,
            finalOutputSpec = stageConfig.finalOutput
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
            )
        )
    } else if (configuredExtraStages.isNotEmpty()) {
        stagePlans += configuredExtraStages
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
                previousStage = previousStageResult.takeIf { stagePlan.inheritsPreviousOutputs }
            )
        }
        val resolvedStagePlan = stagePlan.copy(inputDir = resolvedInputDir)
        val result = loadCompletedStageResult(resolvedStagePlan)
            ?: executeCliStage(resolvedStagePlan, options, runtimeFactory)
        stageResults += result
        publishStageOutput(
            stageOutputDir = result.stageOutputDir,
            relativePath = CODE_IMPROVEMENT_PLAN_RELATIVE_PATH,
            pipelineOutputDir = runOutputDir.toPath().resolve("outputs")
        )
        result.publishedOutputRelativePath?.let { relativePath ->
            publishStageOutput(
                stageOutputDir = result.stageOutputDir,
                relativePath = relativePath,
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
        if (!result.summary.status.equals("COMPLETED", ignoreCase = true)) {
            return@runBlocking CliRunResult(completedSuccessfully = false)
        }
    }

    val publishedOutputs = runCatching {
        runOutputDir.toPath().resolve("outputs")
            .toFile()
            .walkTopDown()
            .filter { it.isFile }
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
    writePipelineResultsZip(runOutputDir.toPath())

    CliRunResult(
        completedSuccessfully = stageResults.all { it.summary.status == "COMPLETED" }
    )
}

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
    Napier.i("EvoKammer CLI reusing completed stage ${stage.id} runId=${stage.runId}")
    return CliStageExecutionResult(
        stageId = stage.id,
        runId = stage.runId,
        stageOutputDir = stage.outputDir,
        summary = summary,
        publishedOutputRelativePath = stage.finalOutputSpec?.relativePath
    )
}

private suspend fun executeCliStage(
    stage: CliStagePlan,
    rootOptions: CliOptions,
    runtimeFactory: (File, CliOptions) -> CliRuntime
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
    Napier.i("EvoKammer CLI starting runId=${stage.runId} outputDir=${runOutputDir.absolutePath}")

    val stageOptions = rootOptions.copy(
        prompt = stage.prompt,
        inputDir = stage.inputDir,
        outputDir = stage.outputDir.parent ?: rootOptions.outputDir,
        modelOverrides = stage.modelOverrides,
        fast = stage.fast,
        rounds = stage.rounds,
        groupCount = stage.groupCount,
        maxAgentsPerGroup = stage.maxAgentsPerGroup,
        budgetOverride = stage.budgetOverride,
        runId = stage.runId
    )
    val dataRoot = runOutputDir.resolve(".evokammer-data").also { it.mkdirs() }
    val runtime = runtimeFactory(dataRoot, stageOptions)
    val persistedRunRoot = dataRoot.resolve("evokammer/runs/${stage.runId}")
    val persistedStateFile = PersistedRunStateStore.resolveStateFile(dataRoot, stage.runId)
    val shouldResumePersistedRun =
        !runOutputDir.resolve("summary.json").exists() &&
            (persistedStateFile.exists() || persistedRunRoot.exists())

    val inputArtifacts = loadRunInputArtifacts(stage.inputDir)
    Napier.i("EvoKammer CLI loaded ${inputArtifacts.size} input artifacts from ${stage.inputDir}")

    val objectiveProfile = buildCliObjectiveProfile(stage.prompt, stage.fast)
    val modelOverrides = if (stage.fast) {
        FastModelSelectionConfig.fastRuntimeAssignments().toMutableMap().apply {
            putAll(stage.modelOverrides)
        }
    } else {
        stage.modelOverrides
    }

    val runConfig = RunConfig(
        runId = stage.runId,
        objective = stage.prompt,
        groupCount = stage.groupCount ?: if (stage.fast) 2 else 1,
        groupProfiles = List(stage.groupCount ?: if (stage.fast) 2 else 1) { GroupProfileType.Library },
        rounds = stage.rounds,
        roundDurationMinutes = if (stage.fast) 1 else 0,
        maxAgentsPerGroup = stage.maxAgentsPerGroup ?: objectiveProfile.maxAgentsPerGroup,
        scoringDimensions = objectiveProfile.scoringDimensions,
        openRouterApiKey = rootOptions.openRouterApiKey,
        modelOverrides = modelOverrides,
        contextArtifacts = inputArtifacts,
        finalOutputSpec = stage.finalOutputSpec?.let {
            FinalOutputSpec(
                relativePath = it.relativePath,
                mediaType = it.mediaType,
                description = it.description
            )
        },
        isTestRun = true,
        budgetOverride = stage.budgetOverride ?: objectiveProfile.budgetOverride
    )

    val completion = CompletableDeferred<EvoKammerEvent>()
    val eventCollector = launch(Dispatchers.Default) {
        runtime.engineService.events.collect { event ->
            when (event) {
                is EvoKammerEvent.RunStarted -> Napier.i("EvoKammer CLI run started runId=${event.runId}")
                is EvoKammerEvent.RoundStarted -> Napier.i("EvoKammer CLI round started round=${event.roundIndex}")
                is EvoKammerEvent.RoundCompleted -> Napier.i("EvoKammer CLI round completed round=${event.result.roundIndex}")
                is EvoKammerEvent.RunCompleted -> {
                    Napier.i("EvoKammer CLI run completed runId=${event.runId} winner=${event.winnerId}")
                    if (!completion.isCompleted) completion.complete(event)
                }
                is EvoKammerEvent.RunAborted -> {
                    Napier.e("EvoKammer CLI run aborted runId=${event.runId} reason=${event.reason}")
                    if (!completion.isCompleted) completion.complete(event)
                }
                is EvoKammerEvent.EngineProgress -> Napier.d("EvoKammer CLI progress phase=${event.phase} msg=${event.message}")
                else -> Unit
            }
        }
    }

    val startedAt = Instant.now()
    val completedEvent = try {
        if (shouldResumePersistedRun) {
            Napier.i("EvoKammer CLI resuming persisted runId=${stage.runId}")
            runtime.engineService.resumePersistedRun(runConfig)
        } else {
            runtime.engineService.startRun(runConfig)
        }
        completion.await()
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
            is EvoKammerEvent.RunCompleted -> "COMPLETED"
            is EvoKammerEvent.RunAborted -> "ABORTED"
            else -> "UNKNOWN"
        },
        winnerId = (completedEvent as? EvoKammerEvent.RunCompleted)?.winnerId,
        modelOverrides = modelOverrides
    )

    Files.writeString(
        runOutputDir.resolve("summary.json").toPath(),
        cliJson.encodeToString(CliRunSummary.serializer(), summary)
    )

    Napier.i("EvoKammer CLI wrote summary to ${runOutputDir.resolve("summary.json").absolutePath}")
    if (fileAntilog != null) {
        Napier.base(DebugAntilog())
    }
    fileAntilog?.close()

    CliStageExecutionResult(
        stageId = stage.id,
        runId = stage.runId,
        stageOutputDir = runOutputDir.toPath(),
        summary = summary,
        publishedOutputRelativePath = stage.finalOutputSpec?.relativePath
    )
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
            description = "Browser-renderable final deliverable"
        )
    } else {
        null
    }
}

private fun preparePipelineStageInput(
    baseInputDir: Path,
    pipelineRoot: Path,
    stageId: String,
    previousStage: CliStageExecutionResult?
): Path {
    val stageInputRoot = pipelineRoot.resolve(".pipeline-inputs").resolve(stageId)
    stageInputRoot.toFile().deleteRecursively()
    Files.createDirectories(stageInputRoot)
    copyDirectoryContents(baseInputDir, stageInputRoot)
    applyCodeImprovementPlanIfPresent(
        repoRoot = stageInputRoot,
        pipelineRoot = pipelineRoot
    )
    if (previousStage != null) {
        val previousStageDir = stageInputRoot.resolve("previous-stage")
        Files.createDirectories(previousStageDir)
        copyIfExists(previousStage.stageOutputDir.resolve("prompt.txt"), previousStageDir.resolve("prompt.txt"))
        copyIfExists(previousStage.stageOutputDir.resolve("summary.json"), previousStageDir.resolve("summary.json"))
        copyIfExists(previousStage.stageOutputDir.resolve("results.zip"), previousStageDir.resolve("results.zip"))
        previousStage.stageOutputDir.resolve("results.zip").toFile().takeIf { it.exists() }?.let { bundle ->
            runCatching {
                unzip(bundle, previousStageDir.resolve("results-bundle"))
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
    pipelineOutputDir: Path
) {
    val resultsZip = stageOutputDir.resolve("results.zip").toFile()
    if (!resultsZip.exists()) return
    runCatching {
        ZipFile(resultsZip).use { zip ->
            val entry = zip.getEntry("outputs/$relativePath") ?: return
            val target = pipelineOutputDir.resolve(relativePath)
            Files.createDirectories(target.parent)
            zip.getInputStream(entry).use { input ->
                Files.write(target, input.readBytes())
            }
        }
    }.onFailure { err ->
        Napier.w("CliPipeline: failed to publish stage output $relativePath: ${err.message}")
    }
}

private fun writePipelineResultsZip(runOutputDir: Path) {
    val zipBytes = ByteArrayOutputStream()
    ZipOutputStream(zipBytes).use { zip ->
        val outputsRoot = runOutputDir.resolve("outputs").toFile()
        if (outputsRoot.exists()) {
            outputsRoot.walkTopDown()
                .filter { it.isFile }
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

private fun verifyPublishedStageOutput(
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

    validatePublishedOutputContent(expectedEntry, content, outputSpec.mediaType)

    val publishedFile = publishedOutputDir?.resolve(outputSpec.relativePath)
    if (publishedOutputDir != null) {
        if (publishedFile == null || !Files.exists(publishedFile)) return null

        val publishedContent = runCatching { Files.readString(publishedFile) }.getOrNull() ?: return null
        if (publishedContent.trim() != content.trim()) return null
    }

    return publishedFile ?: stageOutputDir
}

private fun validatePublishedOutputContent(
    entryPath: String,
    content: String,
    mediaType: String
) {
    val trimmed = content.trim()
    require(trimmed.isNotBlank()) { "Published output $entryPath was empty" }

    when {
        mediaType.startsWith("text/html", ignoreCase = true) -> {
            require(
                trimmed.startsWith("<!doctype html>", ignoreCase = true) ||
                    trimmed.startsWith("<html", ignoreCase = true)
            ) {
                "Published output $entryPath does not look like HTML"
            }
        }
        mediaType.equals("application/json", ignoreCase = true) ||
            mediaType.endsWith("+json", ignoreCase = true) -> {
            runCatching { cliJson.parseToJsonElement(trimmed) }
                .getOrElse {
                    throw IllegalStateException("Published output $entryPath is not valid JSON: ${it.message}")
                }
        }
    }
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

private fun unzip(zipFile: File, targetDir: Path) {
    Files.createDirectories(targetDir)
    ZipFile(zipFile).use { zip ->
        zip.entries().asSequence().forEach { entry ->
            val target = targetDir.resolve(entry.name)
            if (entry.isDirectory) {
                Files.createDirectories(target)
            } else {
                Files.createDirectories(target.parent)
                zip.getInputStream(entry).use { input ->
                    Files.write(target, input.readBytes())
                }
            }
        }
    }
}
