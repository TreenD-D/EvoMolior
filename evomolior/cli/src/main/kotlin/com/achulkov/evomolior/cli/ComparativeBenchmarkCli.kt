@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.cli

import com.achulkov.evomolior.agents.factory.TaskToolCapabilityPolicy
import com.achulkov.evomolior.api.model.FinalOutputKind
import com.achulkov.evomolior.api.model.FinalOutputSpec
import com.achulkov.evomolior.api.model.RunConfig
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.benchmark.ComparativeArmDefinition
import com.achulkov.evomolior.core.benchmark.ComparativeArmExecutor
import com.achulkov.evomolior.core.benchmark.ComparativeBenchmarkArm
import com.achulkov.evomolior.core.benchmark.ComparativeBenchmarkHarness
import com.achulkov.evomolior.core.benchmark.ComparativeBenchmarkHashing
import com.achulkov.evomolior.core.benchmark.ComparativeCellStatus
import com.achulkov.evomolior.core.benchmark.ComparativeConstraintMode
import com.achulkov.evomolior.core.benchmark.ComparativeConstraintPolicy
import com.achulkov.evomolior.core.benchmark.ComparativeEvaluation
import com.achulkov.evomolior.core.benchmark.ComparativeEvaluatorContract
import com.achulkov.evomolior.core.benchmark.ComparativeExperimentAssignment
import com.achulkov.evomolior.core.benchmark.ComparativeExperimentExecution
import com.achulkov.evomolior.core.benchmark.ComparativeExperimentPlan
import com.achulkov.evomolior.core.benchmark.ComparativeExperimentResult
import com.achulkov.evomolior.core.benchmark.ComparativeInputCommitments
import com.achulkov.evomolior.core.benchmark.ComparativeRuntimeInputs
import com.achulkov.evomolior.core.benchmark.ComparativeTaskCommitment
import com.achulkov.evomolior.core.benchmark.Tier0BenchmarkTask
import com.achulkov.evomolior.core.config.LlmBackend
import com.achulkov.evomolior.core.config.ModelSelectionConfig
import com.achulkov.evomolior.core.model.Role
import com.achulkov.evomolior.core.model.GroupProfileType
import com.achulkov.evomolior.core.model.ScoringDimensionCatalog
import com.achulkov.evomolior.runtime.generateComparativeRuntimeInputs
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.system.exitProcess
import kotlin.time.Clock

private val comparativeRuntimeJson = Json { ignoreUnknownKeys = false }

/** Task-level execution seam used by explicit comparative arm adapters. */
internal fun interface ComparativeBenchmarkTaskExecutor {
    /** Executes one task using the exact options, run ID, and artifact namespace supplied. */
    suspend fun execute(
        options: BenchmarkCliOptions,
        task: Tier0BenchmarkTask,
        runId: String,
        artifactDirectory: Path
    ): BenchmarkCliTaskResult
}

/** Explicit task adapter for one comparative benchmark arm. */
internal interface ComparativeBenchmarkArmAdapter {
    /** Arm implemented by this adapter. */
    val arm: ComparativeBenchmarkArm

    /** Executes one exact sealed assignment. */
    suspend fun execute(
        plan: ComparativeExperimentPlan,
        assignment: ComparativeExperimentAssignment,
        task: Tier0BenchmarkTask
    ): BenchmarkCliTaskResult
}

/** Reuses the iterative Qwen single-agent tool loop as the matched baseline adapter. */
internal class IterativeSingleAgentBenchmarkAdapter(
    private val baseOptions: BenchmarkCliOptions,
    private val taskExecutor: ComparativeBenchmarkTaskExecutor
) : ComparativeBenchmarkArmAdapter {
    override val arm: ComparativeBenchmarkArm =
        ComparativeBenchmarkArm.ITERATIVE_SINGLE_AGENT

    override suspend fun execute(
        plan: ComparativeExperimentPlan,
        assignment: ComparativeExperimentAssignment,
        task: Tier0BenchmarkTask
    ): BenchmarkCliTaskResult = taskExecutor.execute(
        options = baseOptions.copy(
            runner = BenchmarkCliRunnerKind.SINGLE_AGENT,
            groupCount = 1,
            outputDir = resolveArtifactDirectory(baseOptions.outputDir, assignment),
            modelOverrides = matchedWorkerModelOverrides(
                plan.commitments.workerModelFamily,
                baseOptions.llmBackend
            ),
            comparativePlanHash = plan.planHash,
            comparativeCommitments = plan.commitments,
            comparativeSeed = assignment.seed,
            comparativeRuntimeInputs = baseOptions.comparativeRuntimeInputs,
            comparativeGraderGroupCount = null
        ),
        task = task,
        runId = assignment.runId(),
        artifactDirectory = resolveArtifactDirectory(baseOptions.outputDir, assignment)
    )
}

/** Explicit one-worker-group society adapter. */
internal class OneGroupSocietyBenchmarkAdapter(
    private val baseOptions: BenchmarkCliOptions,
    private val taskExecutor: ComparativeBenchmarkTaskExecutor
) : ComparativeBenchmarkArmAdapter {
    override val arm: ComparativeBenchmarkArm =
        ComparativeBenchmarkArm.ONE_GROUP_SOCIETY

    override suspend fun execute(
        plan: ComparativeExperimentPlan,
        assignment: ComparativeExperimentAssignment,
        task: Tier0BenchmarkTask
    ): BenchmarkCliTaskResult {
        val definition = plan.arms.single { it.arm == arm }
        require(definition.workerGroupCount == 1) {
            "One-group adapter requires an exact one-worker-group arm definition"
        }
        val artifactDirectory = resolveArtifactDirectory(baseOptions.outputDir, assignment)
        return taskExecutor.execute(
            options = baseOptions.copy(
                runner = BenchmarkCliRunnerKind.EVOMOLIOR_SOCIETY,
                groupCount = 1,
                outputDir = artifactDirectory,
                modelOverrides = matchedWorkerModelOverrides(
                    plan.commitments.workerModelFamily,
                    baseOptions.llmBackend
                ),
                comparativePlanHash = plan.planHash,
                comparativeCommitments = plan.commitments,
                comparativeSeed = assignment.seed,
                comparativeRuntimeInputs = baseOptions.comparativeRuntimeInputs,
                comparativeGraderGroupCount = definition.graderGroupCount
            ),
            task = task,
            runId = assignment.runId(),
            artifactDirectory = artifactDirectory
        )
    }
}

/** Explicit competitive full-society adapter with multiple worker and grader groups. */
internal class FullSocietyBenchmarkAdapter(
    private val baseOptions: BenchmarkCliOptions,
    private val taskExecutor: ComparativeBenchmarkTaskExecutor
) : ComparativeBenchmarkArmAdapter {
    override val arm: ComparativeBenchmarkArm = ComparativeBenchmarkArm.FULL_SOCIETY

    override suspend fun execute(
        plan: ComparativeExperimentPlan,
        assignment: ComparativeExperimentAssignment,
        task: Tier0BenchmarkTask
    ): BenchmarkCliTaskResult {
        val definition = plan.arms.single { it.arm == arm }
        require(definition.workerGroupCount >= 2 && definition.graderGroupCount >= 2) {
            "Full-society adapter requires multiple worker and grader groups"
        }
        val artifactDirectory = resolveArtifactDirectory(baseOptions.outputDir, assignment)
        return taskExecutor.execute(
            options = baseOptions.copy(
                runner = BenchmarkCliRunnerKind.EVOMOLIOR_SOCIETY,
                groupCount = definition.workerGroupCount,
                outputDir = artifactDirectory,
                modelOverrides = matchedWorkerModelOverrides(
                    plan.commitments.workerModelFamily,
                    baseOptions.llmBackend
                ),
                comparativePlanHash = plan.planHash,
                comparativeCommitments = plan.commitments,
                comparativeSeed = assignment.seed,
                comparativeRuntimeInputs = baseOptions.comparativeRuntimeInputs,
                comparativeGraderGroupCount = definition.graderGroupCount
            ),
            task = task,
            runId = assignment.runId(),
            artifactDirectory = artifactDirectory
        )
    }
}

/**
 * CLI-level matched experiment runner.
 *
 * It delegates task execution to explicit arm adapters, converts task results into immutable
 * experiment cells, validates the complete matrix, and writes one plan, result set, and report.
 */
internal class ComparativeBenchmarkCliRunner(
    private val adapters: Map<ComparativeBenchmarkArm, ComparativeBenchmarkArmAdapter>,
    private val clock: Clock = Clock.System
) {
    init {
        require(adapters.keys.containsAll(ComparativeBenchmarkArm.entries)) {
            "Comparative CLI runner requires every explicit arm adapter"
        }
        require(adapters.all { (arm, adapter) -> arm == adapter.arm }) {
            "Comparative CLI adapter map contains an arm mismatch"
        }
    }

    /** Executes [plan] against [tasks] and writes machine-readable authority files. */
    suspend fun run(
        plan: ComparativeExperimentPlan,
        tasks: List<Tier0BenchmarkTask>,
        outputRoot: Path
    ): ComparativeExperimentExecution {
        require(plan.hasValidHash()) { "Comparative plan hash is invalid" }
        val tasksById = tasks.associateBy(Tier0BenchmarkTask::id)
        require(tasksById.size == tasks.size) { "Comparative benchmark tasks must be unique" }
        plan.tasks.forEach { commitment ->
            val task = requireNotNull(tasksById[commitment.taskId]) {
                "Missing benchmark task ${commitment.taskId}"
            }
            require(task.contractHash() == commitment.taskContractHash) {
                "Benchmark task ${task.id} does not match its sealed contract"
            }
        }

        val harness = ComparativeBenchmarkHarness(
            executors = ComparativeBenchmarkArm.entries.associateWith { arm ->
                ComparativeArmExecutor { sealedPlan, assignment ->
                    val startedAtNanos = System.nanoTime()
                    val task = requireNotNull(tasksById[assignment.taskId])
                    val taskResult = adapters.getValue(arm).execute(
                        sealedPlan,
                        assignment,
                        task
                    )
                    taskResult.toComparativeResult(
                        plan = sealedPlan,
                        assignment = assignment,
                        latencyMillis = (System.nanoTime() - startedAtNanos)
                            .coerceAtLeast(0L) / 1_000_000L,
                        completedAt = clock.now()
                    )
                }
            },
            clock = clock
        )
        val execution = harness.run(plan)
        outputRoot.createDirectories()
        Files.writeString(
            outputRoot.resolve("comparative-experiment-plan.json"),
            benchmarkJson.encodeToString(ComparativeExperimentPlan.serializer(), execution.plan)
        )
        Files.writeString(
            outputRoot.resolve("comparative-experiment-results.json"),
            benchmarkJson.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(
                    ComparativeExperimentResult.serializer()
                ),
                execution.results
            )
        )
        Files.writeString(
            outputRoot.resolve("comparative-experiment-report.json"),
            benchmarkJson.encodeToString(
                com.achulkov.evomolior.core.benchmark.ComparativeExperimentReport.serializer(),
                execution.report
            )
        )
        return execution
    }
}

/** Parsed CLI configuration for one matched comparative experiment. */
internal data class ComparativeBenchmarkCliOptions(
    val base: BenchmarkCliOptions,
    val experimentId: String,
    val seeds: List<Long>,
    val constraintPolicy: ComparativeConstraintPolicy,
    val commitments: ComparativeInputCommitments,
    val evaluator: ComparativeEvaluatorContract,
    val workerModelFamily: String,
    val fullSocietyWorkerGroups: Int,
    val fullSocietyGraderGroups: Int
)

/** Parser for `benchmark-compare`, leaving ordinary benchmark options to the existing parser. */
internal object ComparativeBenchmarkCliArguments {
    /** Parses comparison flags and delegates the remaining benchmark flags. */
    fun parse(args: Array<String>): ComparativeBenchmarkCliOptions? {
        if (args.any { it == "-h" || it == "--help" }) return null
        val benchmarkArgs = mutableListOf<String>()
        var experimentId = "comparison-${System.currentTimeMillis()}"
        var seeds: List<Long>? = null
        var mode: ComparativeConstraintMode? = null
        var maximumCostUsd: Double? = null
        var maximumLatencyMillis: Long? = null
        var sourceBundleHash: String? = null
        var toolBundleHash: String? = null
        var atlasHash: String? = null
        var frozenRuntimeInputsPath: Path? = null
        var outputContractHash: String? = null
        var workerModelFamily: String? = null
        var evaluatorId = "tier0-independent-evaluator"
        var evaluatorHash: String? = null
        var evaluatorModelFamily: String? = null
        var fullWorkerGroups = 2
        var fullGraderGroups = 2

        var index = 0
        while (index < args.size) {
            val arg = args[index]
            fun value(): String = args.getOrNull(++index)
                ?: throw CliUsageException("Missing value for $arg")
            when (arg) {
                "-experiment-id", "--experiment-id" -> experimentId = value()
                "-seeds", "--seeds" -> seeds = value().split(',')
                    .map(String::trim)
                    .map { seed ->
                        seed.toLongOrNull()
                            ?: throw CliUsageException("Invalid comparison seed: $seed")
                    }
                "-constraint", "--constraint" -> mode = when (value().lowercase()) {
                    "iso-cost" -> ComparativeConstraintMode.ISO_COST
                    "iso-latency" -> ComparativeConstraintMode.ISO_LATENCY
                    else -> throw CliUsageException("Constraint must be iso-cost or iso-latency")
                }
                "-max-cost-usd", "--max-cost-usd" ->
                    maximumCostUsd = value().toDoubleOrNull()
                        ?: throw CliUsageException("Maximum cost must be numeric")
                "-max-latency-ms", "--max-latency-ms" ->
                    maximumLatencyMillis = value().toLongOrNull()
                        ?: throw CliUsageException("Maximum latency must be an integer")
                "-source-bundle-hash", "--source-bundle-hash" -> sourceBundleHash = value()
                "-tool-bundle-hash", "--tool-bundle-hash" -> toolBundleHash = value()
                "-atlas-hash", "--atlas-hash" -> atlasHash = value()
                "-frozen-runtime-inputs", "--frozen-runtime-inputs" ->
                    frozenRuntimeInputsPath = Path.of(value())
                "-output-contract-hash", "--output-contract-hash" -> outputContractHash = value()
                "-worker-model-family", "--worker-model-family" -> workerModelFamily = value()
                "-evaluator-id", "--evaluator-id" -> evaluatorId = value()
                "-evaluator-contract-hash", "--evaluator-contract-hash" -> evaluatorHash = value()
                "-evaluator-model-family", "--evaluator-model-family" ->
                    evaluatorModelFamily = value()
                "-full-worker-groups", "--full-worker-groups" ->
                    fullWorkerGroups = value().toIntOrNull()
                        ?: throw CliUsageException("Full worker group count must be an integer")
                "-full-grader-groups", "--full-grader-groups" ->
                    fullGraderGroups = value().toIntOrNull()
                        ?: throw CliUsageException("Full grader group count must be an integer")
                else -> {
                    benchmarkArgs += arg
                    if (benchmarkArgumentConsumesValue(arg)) {
                        benchmarkArgs += value()
                    }
                }
            }
            index += 1
        }

        val base = BenchmarkCliArguments.parse(benchmarkArgs.toTypedArray()) ?: return null
        require(base.llmBackend in setOf(LlmBackend.QWEN_CLOUD, LlmBackend.CODEX_CHATGPT)) {
            "Matched comparison requires qwen-cloud or codex-chatgpt"
        }
        val resolvedMode = mode ?: throw CliUsageException("Missing -constraint")
        val policy = runCatching {
            ComparativeConstraintPolicy(
                mode = resolvedMode,
                maximumCostUsd = maximumCostUsd,
                maximumLatencyMillis = maximumLatencyMillis
            )
        }.getOrElse { error ->
            throw CliUsageException(error.message ?: "Invalid comparative constraint policy")
        }
        val resolvedWorkerFamily = workerModelFamily
            ?.takeIf(String::isNotBlank)
            ?: throw CliUsageException("Missing -worker-model-family")
        val expectedFamilySignal = when (base.llmBackend) {
            LlmBackend.QWEN_CLOUD -> "qwen"
            LlmBackend.CODEX_CHATGPT -> "gpt"
            LlmBackend.OPENROUTER -> error("OpenRouter is not supported for matched comparison")
        }
        if (!resolvedWorkerFamily.contains(expectedFamilySignal, ignoreCase = true)) {
            throw CliUsageException(
                "Worker model family must match the selected ${base.llmBackend.id} backend"
            )
        }
        val commitments = ComparativeInputCommitments(
            sourceBundleHash = sourceBundleHash ?: "derived-at-runtime",
            toolBundleHash = toolBundleHash ?: "derived-at-runtime",
            atlasHash = atlasHash ?: "derived-at-runtime",
            outputContractHash = outputContractHash ?: "derived-at-runtime",
            workerModelFamily = resolvedWorkerFamily
        )
        val runtimeInputs = frozenRuntimeInputsPath?.let { path ->
            runCatching {
                comparativeRuntimeJson.decodeFromString<ComparativeRuntimeInputs>(
                    Files.readString(path)
                )
            }.getOrElse { error ->
                throw CliUsageException(
                    "Invalid frozen comparative runtime inputs: ${error.message}"
                )
            }
        }
        return ComparativeBenchmarkCliOptions(
            base = base.copy(comparativeRuntimeInputs = runtimeInputs),
            experimentId = experimentId,
            seeds = seeds ?: throw CliUsageException("Missing -seeds"),
            constraintPolicy = policy,
            commitments = commitments,
            evaluator = ComparativeEvaluatorContract(
                evaluatorId = evaluatorId,
                evaluatorVersion = 1,
                evaluatorContractHash = evaluatorHash
                    ?: throw CliUsageException("Missing -evaluator-contract-hash"),
                modelFamily = evaluatorModelFamily
                    ?: throw CliUsageException("Missing -evaluator-model-family"),
                independent = true,
                calibrated = true
            ),
            workerModelFamily = resolvedWorkerFamily,
            fullSocietyWorkerGroups = fullWorkerGroups,
            fullSocietyGraderGroups = fullGraderGroups
        )
    }

    /** CLI usage for the matched experiment command. */
    fun usage(): String = """
        EvoMolior matched comparative benchmark

        benchmark-compare [ordinary benchmark flags] \
          -experiment-id ID -seeds 11,22 \
          -constraint iso-cost|iso-latency \
          [-max-cost-usd N | -max-latency-ms N] \
          [-frozen-runtime-inputs FILE] \
          -worker-model-family FAMILY \
          -evaluator-contract-hash HASH -evaluator-model-family FAMILY
    """.trimIndent()
}

/** Runs the matched three-arm comparison from the CLI. */
internal fun runComparativeBenchmarkCli(args: Array<String>) {
    val options = ComparativeBenchmarkCliArguments.parse(args) ?: run {
        println(ComparativeBenchmarkCliArguments.usage())
        return
    }
    val taskRunner = BenchmarkCliRunner()
    val tasks = taskRunner.loadTasks(options.base)
        .drop(options.base.offset)
        .take(options.base.limit)
    val task = tasks.single()
    val frozenSourceUploads = options.base.comparativeSourceArtifacts
        ?: taskRunner.freezeComparativeSourceArtifacts(task)
    val preparedBase = options.base.copy(
        comparativeSourceArtifacts = frozenSourceUploads
    )
    val objective = taskRunner.buildComparativeObjective(
        preparedBase,
        task,
        frozenSourceUploads
    )
    val logicalRunId = comparativeRunId(options.experimentId, task.id)
    val outputSpec = FinalOutputSpec(
        relativePath = taskRunner.benchmarkPredictionPath(task.benchmark),
        mediaType = "application/json",
        description = if (task.benchmark == com.achulkov.evomolior.core.benchmark.Tier0BenchmarkKind.AUTO_RESEARCH_BENCH) {
            "AutoResearchBench target_paper_ids.json discovery output"
        } else {
            "Tier-0 benchmark prediction JSON"
        },
        kind = FinalOutputKind.GENERIC_FILE
    )
    val runtimeInputs = options.base.comparativeRuntimeInputs ?: kotlinx.coroutines.runBlocking {
        generateComparativeRuntimeInputs(
            config = RunConfig(
                runId = logicalRunId,
                objective = objective,
                groupCount = options.fullSocietyWorkerGroups,
                groupProfiles = List(options.fullSocietyWorkerGroups) { GroupProfileType.Library },
                rounds = options.base.rounds,
                roundDurationMinutes = options.base.roundDurationMinutes,
                maxAgentsPerGroup = options.base.maxAgentsPerGroup,
                scoringDimensions = ScoringDimensionCatalog.all,
                openRouterApiKey = options.base.apiKey,
                llmBackend = options.base.llmBackend,
                // The neutral Council chooses runtime-role assignments from the backend's sealed
                // model cards. Baseline is an experiment adapter role, while Council roles are
                // transient; neither is a valid StartingField model assignment.
                modelOverrides = emptyMap(),
                contextArtifacts = frozenSourceUploads,
                finalOutputSpec = outputSpec,
                isTestRun = true,
                budgetOverride = options.base.budgetOverride,
                graderGroupCountOverride = options.fullSocietyGraderGroups
            ),
            inputArtifacts = taskRunner.comparativeInputArtifacts(frozenSourceUploads),
            taskToolIds = setOf(
                TaskToolCapabilityPolicy.READ_RUN_ARTIFACT,
                TaskToolCapabilityPolicy.FETCH_SOURCE
            )
        )
    }
    require(
        runtimeInputs.taskToolIds == setOf(
            TaskToolCapabilityPolicy.READ_RUN_ARTIFACT,
            TaskToolCapabilityPolicy.FETCH_SOURCE
        )
    ) {
        "The current iterative baseline supports exactly read_run_artifact and fetch_source"
    }
    val commitments = ComparativeInputCommitments(
        sourceBundleHash = AtlasHashing.hashText(
            comparativeRuntimeJson.encodeToString(frozenSourceUploads)
        ),
        toolBundleHash = runtimeInputs.taskToolBundleHash(),
        atlasHash = runtimeInputs.atlas.atlasHash,
        outputContractHash = AtlasHashing.hashText(
            comparativeRuntimeJson.encodeToString(outputSpec)
        ),
        workerModelFamily = options.workerModelFamily
    )
    runtimeInputs.requireMatches(commitments)
    val executionBase = preparedBase.copy(
        comparativeRuntimeInputs = runtimeInputs,
        comparativeSourceArtifacts = frozenSourceUploads
    )
    val taskExecutor = ComparativeBenchmarkTaskExecutor { taskOptions, task, runId, directory ->
        taskRunner.runTask(taskOptions, task, runId, directory)
    }
    val plan = ComparativeExperimentPlan(
        experimentId = options.experimentId,
        experimentVersion = 1,
        benchmarkId = options.base.benchmark.name,
        benchmarkVersion = 1,
        tasks = tasks.map { ComparativeTaskCommitment(it.id, it.contractHash()) },
        seeds = options.seeds,
        arms = listOf(
            ComparativeArmDefinition(
                ComparativeBenchmarkArm.ITERATIVE_SINGLE_AGENT,
                0,
                0,
                options.workerModelFamily
            ),
            ComparativeArmDefinition(
                ComparativeBenchmarkArm.ONE_GROUP_SOCIETY,
                1,
                1,
                options.workerModelFamily
            ),
            ComparativeArmDefinition(
                ComparativeBenchmarkArm.FULL_SOCIETY,
                options.fullSocietyWorkerGroups,
                options.fullSocietyGraderGroups,
                options.workerModelFamily
            )
        ),
        commitments = commitments,
        constraintPolicy = options.constraintPolicy,
        evaluator = options.evaluator,
        artifactRoot = "matched-${options.experimentId}",
        createdAt = Clock.System.now()
    ).withComputedHash()
    val adapters = listOf(
        IterativeSingleAgentBenchmarkAdapter(executionBase, taskExecutor),
        OneGroupSocietyBenchmarkAdapter(executionBase, taskExecutor),
        FullSocietyBenchmarkAdapter(executionBase, taskExecutor)
    ).associateBy(ComparativeBenchmarkArmAdapter::arm)
    val execution = kotlinx.coroutines.runBlocking {
        ComparativeBenchmarkCliRunner(adapters).run(plan, tasks, options.base.outputDir)
    }
    println(
        "Comparative benchmark report: " +
            options.base.outputDir.resolve("comparative-experiment-report.json").absolutePathString()
    )
    if (execution.results.any { it.status == ComparativeCellStatus.FAILED }) {
        exitProcess(1)
    }
}

private fun BenchmarkCliTaskResult.toComparativeResult(
    plan: ComparativeExperimentPlan,
    assignment: ComparativeExperimentAssignment,
    latencyMillis: Long,
    completedAt: kotlin.time.Instant
): ComparativeExperimentResult {
    val status = when {
        error != null || status.startsWith("ABORTED") -> ComparativeCellStatus.FAILED
        status.contains("ABSTAIN", ignoreCase = true) || finalAnswer.isBlank() ->
            ComparativeCellStatus.ABSTAINED
        else -> ComparativeCellStatus.SUCCEEDED
    }
    return ComparativeExperimentResult(
        resultId = "result-${assignment.assignmentId}",
        assignment = assignment,
        status = status,
        evaluation = if (status == ComparativeCellStatus.SUCCEEDED) {
            ComparativeEvaluation(
                evaluatorContractHash = plan.evaluator.evaluatorContractHash,
                scoreComponents = mapOf(
                    "exact_correct" to if (exactCorrect) 1.0 else 0.0,
                    "evidence_path_present" to if (evidencePathPresent) 1.0 else 0.0
                ),
                overallScore = if (exactCorrect) 1.0 else 0.0,
                maximumScore = 1.0
            )
        } else {
            null
        },
        costUsd = totalCost,
        latencyMillis = latencyMillis,
        finalDeliverableReferenceId = finalDeliverableReferenceId,
        finalDeliverableReferenceHash = finalDeliverableReferenceHash,
        failureReason = when (status) {
            ComparativeCellStatus.SUCCEEDED -> null
            ComparativeCellStatus.FAILED -> error ?: this.status
            ComparativeCellStatus.ABSTAINED -> "Benchmark arm abstained or emitted an empty answer"
        },
        observedCommitments = observedComparativeCommitments ?: plan.commitments,
        observedWorkerGroupCount = observedWorkerGroupCount
            ?: plan.arms.single { it.arm == assignment.arm }.workerGroupCount,
        observedGraderGroupCount = observedGraderGroupCount
            ?: plan.arms.single { it.arm == assignment.arm }.graderGroupCount,
        completedAt = completedAt
    ).withComputedHash()
}

private fun Tier0BenchmarkTask.contractHash(): String =
    ComparativeBenchmarkHashing.hashTask(this)

private fun ComparativeExperimentAssignment.runId(): String =
    comparativeRunId(experimentId, taskId)

private fun comparativeRunId(experimentId: String, taskId: String): String =
    "compare-${experimentId}-${taskId}"
        .lowercase()
        .replace(Regex("[^a-z0-9-]"), "-")
        .take(120)

private fun resolveArtifactDirectory(
    outputRoot: Path,
    assignment: ComparativeExperimentAssignment
): Path {
    val normalizedRoot = outputRoot.toAbsolutePath().normalize()
    val resolved = normalizedRoot.resolve(assignment.artifactNamespace).normalize()
    require(resolved.startsWith(normalizedRoot)) {
        "Comparative artifact namespace escapes the output root"
    }
    resolved.createDirectories()
    return resolved
}

private fun matchedWorkerModelOverrides(
    workerModelFamily: String,
    backend: LlmBackend
): Map<String, String> {
    val model = when (backend) {
        LlmBackend.QWEN_CLOUD -> {
            require(workerModelFamily.contains("qwen", ignoreCase = true)) {
                "Qwen comparison requires a Qwen worker model family"
            }
            ModelSelectionConfig.QWEN_CLOUD_QWEN_PLUS_MODEL
        }
        LlmBackend.CODEX_CHATGPT -> {
            require(workerModelFamily.contains("gpt", ignoreCase = true)) {
                "Codex comparison requires a GPT worker model family"
            }
            ModelSelectionConfig.CODEX_CHATGPT_GPT56_LUNA_MODEL
        }
        LlmBackend.OPENROUTER -> error("OpenRouter is not supported for matched comparison")
    }
    return Role.entries.associate { role -> role.name to model } +
        mapOf(SINGLE_AGENT_BASELINE_MODEL_ROLE to model)
}

private fun benchmarkArgumentConsumesValue(argument: String): Boolean = argument in setOf(
    "-benchmark",
    "--benchmark",
    "-limit",
    "--limit",
    "-offset",
    "--offset",
    "-output",
    "--output",
    "-qwen-api-key",
    "--qwen-api-key",
    "-openrouter-api-key",
    "--openrouter-api-key",
    "-llm-backend",
    "--llm-backend",
    "-group-count",
    "--group-count",
    "-rounds",
    "--rounds",
    "-round-duration-minutes",
    "--round-duration-minutes",
    "-max-agents-per-group",
    "--max-agents-per-group",
    "-budget-override",
    "--budget-override",
    "-task-timeout-minutes",
    "--task-timeout-minutes",
    "-cost-override-usd-per-task",
    "--cost-override-usd-per-task",
    "-tasks-jsonl",
    "--tasks-jsonl",
    "-scholarqabench-variant",
    "--scholarqabench-variant",
    "-runner",
    "--runner",
    "-model",
    "--model"
)
