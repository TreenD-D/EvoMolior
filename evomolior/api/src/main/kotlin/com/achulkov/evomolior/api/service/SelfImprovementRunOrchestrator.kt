package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.api.events.EvoMoliorEvent
import com.achulkov.evomolior.api.model.FinalOutputKind
import com.achulkov.evomolior.api.model.FinalOutputSpec
import com.achulkov.evomolior.api.model.RunConfig
import com.achulkov.evomolior.core.contract.OverseerExperimentPlan
import com.achulkov.evomolior.core.contract.OverseerExperimentOutcome
import com.achulkov.evomolior.core.contract.OverseerExperimentResult
import com.achulkov.evomolior.core.contract.OverseerExperimentSchedule
import com.achulkov.evomolior.core.contract.OverseerHypothesis
import com.achulkov.evomolior.core.contract.OverseerKernelState
import com.achulkov.evomolior.core.contract.OverseerMutabilityLevel
import com.achulkov.evomolior.core.model.GroupProfileType
import com.achulkov.evomolior.core.model.ScoringDimension
import com.achulkov.evomolior.core.overseer.PatchAcceptanceEvaluator
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Path
import java.util.UUID
import kotlin.time.Duration.Companion.hours

/**
 * Orchestrates the outer recursive self-improvement loop from §8 of the v4 plan.
 *
 * The orchestrator bridges the deterministic Overseer kernel with live EvoMolior runs,
 * evaluating experiment outcomes and feeding results back into the kernel lifecycle.
 *
 * Each cycle:
 * 1. Reads current kernel state.
 * 2. Schedules eligible experiment plans.
 * 3. For each un-launched scheduled plan, constructs a self-improvement RunConfig and launches
 *    a diagnostic EvoMolior run.
 * 4. Waits for runs to complete (with timeout).
 * 5. Collects basic metrics from completed run outputs.
 * 6. Evaluates acceptance criteria via [PatchAcceptanceEvaluator].
 * 7. Records results in the kernel, updating hypothesis status.
 *
 * Fully mutable runs can now emit a patch bundle and apply it to the mutable repo copy before the
 * next cycle starts.
 *
 * @param kernelService Durable Overseer kernel service for state management.
 * @param runService EvoMolior run service for launching and monitoring runs.
 * @param evaluator Deterministic patch-acceptance evaluator.
 * @param runTimeoutMs Maximum wall-clock time to wait for a single experiment run (default 2 hours).
 */
class SelfImprovementRunOrchestrator(
    private val kernelService: OverseerKernelService,
    private val runService: EvoMoliorRunService,
    private val evaluator: PatchAcceptanceEvaluator = PatchAcceptanceEvaluator(),
    private val runTimeoutMs: Long = 2.hours.inWholeMilliseconds,
    private val repoWorkspaceRoot: Path? = null
) {

    /**
     * Structured report for a single orchestration cycle.
     *
     * @param cycleIndex Zero-based cycle index within this orchestrator session.
     * @param stateBefore Kernel state before the cycle.
     * @param stateAfter Kernel state after the cycle.
     * @param launchedRunIds Run IDs launched in this cycle.
     * @param experimentResults Results recorded in this cycle.
     * @param errors Non-fatal errors encountered during the cycle.
     */
    data class CycleReport(
        val cycleIndex: Int,
        val stateBefore: OverseerKernelState,
        val stateAfter: OverseerKernelState,
        val launchedRunIds: List<String> = emptyList(),
        val experimentResults: List<OverseerExperimentResult> = emptyList(),
        val errors: List<String> = emptyList()
    ) {
        /** Whether any hypotheses changed status in this cycle. */
        val hasProgress: Boolean get() = experimentResults.isNotEmpty()
    }

    private var cycleIndex = 0

    /**
     * Runs a single self-improvement cycle: schedules, launches, monitors, and evaluates.
     *
     * @param apiKey OpenRouter API key used for launched experiment runs.
     * @param additionalModelOverrides Optional role→model overrides for experiment runs.
     * @return Structured cycle report.
     */
    suspend fun runCycle(
        apiKey: String,
        additionalModelOverrides: Map<String, String> = emptyMap()
    ): CycleReport {
        val stateBefore = kernelService.currentState()
        cycleIndex++

        val errors = mutableListOf<String>()

        // 1. Schedule eligible experiment plans
        val scheduled = runCatching {
            kernelService.scheduleEligibleExperimentPlans()
        }.getOrElse { e ->
            errors += "Scheduling failed: ${e.message}"
            stateBefore
        }

        if (errors.isNotEmpty()) {
            return CycleReport(
                cycleIndex = cycleIndex,
                stateBefore = stateBefore,
                stateAfter = scheduled,
                errors = errors
            )
        }

        // 2. Find un-launched schedules
        val unlaunchedSchedules = scheduled.experimentSchedules.values
            .filter { it.launchedAtMs == null }
            .sortedBy { it.experimentPlanId }

        if (unlaunchedSchedules.isEmpty()) {
            return CycleReport(
                cycleIndex = cycleIndex,
                stateBefore = stateBefore,
                stateAfter = scheduled,
                errors = listOf("No eligible experiment plans to launch.")
            )
        }

        val launchedRunIds = mutableListOf<String>()
        val experimentResults = mutableListOf<OverseerExperimentResult>()

        for (schedule in unlaunchedSchedules) {
            val plan = scheduled.experimentPlans[schedule.experimentPlanId]
            val hypothesis = scheduled.hypotheses[schedule.hypothesisId]
            if (plan == null || hypothesis == null) {
                errors += "Missing plan '${schedule.experimentPlanId}' or hypothesis '${schedule.hypothesisId}'."
                continue
            }

            // Launch each scheduled run
            val runEvidences = mutableListOf<PatchAcceptanceEvaluator.RunEvidence>()

            for (scheduledRunId in schedule.scheduledRunIds) {
                val launchResult = runCatching {
                    launchAndWaitForExperimentRun(
                        hypothesis = hypothesis,
                        plan = plan,
                        scheduledRunId = scheduledRunId,
                        apiKey = apiKey,
                        modelOverrides = additionalModelOverrides,
                        schedule = schedule
                    )
                }

                launchResult.fold(
                    onSuccess = { evidence ->
                        launchedRunIds += scheduledRunId
                        runEvidences += evidence
                    },
                    onFailure = { error ->
                        errors += "Run '$scheduledRunId' failed: ${error.message}"
                        runEvidences += PatchAcceptanceEvaluator.RunEvidence(
                            experimentPlanId = plan.id,
                            hypothesisId = hypothesis.id,
                            note = "Run failed: ${error.message}",
                            buildPassed = false,
                            testsPassed = false,
                            invariantsHeld = false,
                            counterexamplePaths = emptyList()
                        )
                    }
                )
            }

            // 3. Evaluate multi-run results
            if (runEvidences.isNotEmpty()) {
                val result = evaluator.evaluateMultiRun(
                    hypothesis = hypothesis,
                    plan = plan,
                    evidenceList = runEvidences
                )
                kernelService.recordExperimentResult(result)
                experimentResults += result
            }
        }

        val stateAfter = kernelService.currentState()

        return CycleReport(
            cycleIndex = cycleIndex,
            stateBefore = stateBefore,
            stateAfter = stateAfter,
            launchedRunIds = launchedRunIds,
            experimentResults = experimentResults,
            errors = errors
        )
    }

    /**
     * Runs multiple cycles until no more un-launched schedules exist or max cycles reached.
     *
     * @param apiKey OpenRouter API key for experiment runs.
     * @param maxCycles Maximum number of cycles before stopping (safety valve).
     * @param additionalModelOverrides Optional role→model overrides for experiment runs.
     * @return All cycle reports.
     */
    suspend fun runToCompletion(
        apiKey: String,
        maxCycles: Int = 10,
        additionalModelOverrides: Map<String, String> = emptyMap()
    ): List<CycleReport> {
        val reports = mutableListOf<CycleReport>()
        repeat(maxCycles) {
            val report = runCycle(apiKey, additionalModelOverrides)
            reports += report
            if (!report.hasProgress && report.errors.all { it.contains("No eligible") }) {
                return reports // No more work to do
            }
        }
        return reports
    }

    private suspend fun launchAndWaitForExperimentRun(
        hypothesis: OverseerHypothesis,
        plan: OverseerExperimentPlan,
        scheduledRunId: String,
        apiKey: String,
        modelOverrides: Map<String, String>,
        schedule: OverseerExperimentSchedule
    ): PatchAcceptanceEvaluator.RunEvidence {
        // Build a self-improvement RunConfig
        val config = buildExperimentRunConfig(
            hypothesis = hypothesis,
            plan = plan,
            scheduledRunId = scheduledRunId,
            apiKey = apiKey,
            modelOverrides = modelOverrides
        )

        // Mark the schedule as launched in the kernel
        kernelService.launchExperimentRun(plan.id, scheduledRunId)

        // Launch the actual run
        runService.startRun(config)

        // Wait for the run to complete
        val event = withTimeoutOrNull(runTimeoutMs) {
            runService.events.first { event ->
                event is EvoMoliorEvent.RunCompleted && event.runId == scheduledRunId ||
                    event is EvoMoliorEvent.RunAborted && event.runId == scheduledRunId
            }
        }

        // Collect basic evidence from the completed run
        val completed = event is EvoMoliorEvent.RunCompleted
        val runPassed = completed // MVP: treat successful completion as pass

        // Try to get results bundle for metric extraction and patch application.
        val resultsBundle = if (completed) {
            waitForResultsBundle(scheduledRunId)
        } else {
            runCatching { runService.getResultsBundle(scheduledRunId) }.getOrNull()
        }

        if (completed && resultsBundle != null && config.finalOutputSpec?.kind == FinalOutputKind.PATCH_BUNDLE && repoWorkspaceRoot != null) {
            val outputSpec = config.finalOutputSpec
            val applied = PatchBundleApplicator.applyIfPresent(
                resultsBundle = resultsBundle,
                repoRoot = repoWorkspaceRoot,
                expectedRelativePath = outputSpec.relativePath,
                runId = scheduledRunId
            )
            if (applied != null) {
                Napier.i(
                    "SelfImprovementRunOrchestrator: applied patch bundle ${outputSpec.relativePath} for run $scheduledRunId into ${applied.repoRoot}"
                )
            }
        }

        val metricValues = runCatching {
            resultsBundle
        }.fold(
            onSuccess = { bytes ->
                if (bytes != null && bytes.isNotEmpty()) {
                    mapOf(
                        "run_completed" to 1.0,
                        "result_size_bytes" to bytes.size.toDouble()
                    )
                } else {
                    mapOf("run_completed" to if (runPassed) 1.0 else 0.0)
                }
            },
            onFailure = {
                mapOf("run_completed" to if (runPassed) 1.0 else 0.0)
            }
        )

        // Build run evidence scoped to the target paths
        val changedFiles = hypothesis.targetScope.paths.toSet()

        return PatchAcceptanceEvaluator.RunEvidence(
            experimentPlanId = plan.id,
            hypothesisId = hypothesis.id,
            changedFiles = changedFiles,
            totalLinesChanged = if (runPassed) 1 else 0, // MVP: minimal change tracking
            buildPassed = runPassed,
            testsPassed = runPassed,
            invariantsHeld = runPassed,
            metricValues = metricValues,
            note = if (completed) {
                "Experiment run '$scheduledRunId' completed successfully."
            } else {
                "Experiment run '$scheduledRunId' was aborted or timed out."
            }
        )
    }

    private suspend fun waitForResultsBundle(runId: String): ByteArray? {
        var bundle: ByteArray? = null
        withTimeoutOrNull(runTimeoutMs) {
            while (bundle == null) {
                val candidate = runCatching { runService.getResultsBundle(runId) }.getOrNull()
                if (candidate != null && candidate.isNotEmpty()) {
                    bundle = candidate
                    break
                }
                delay(250)
            }
        }
        return bundle
    }

    /**
     * Builds a self-improvement RunConfig from a hypothesis and experiment plan.
     *
     * The RunConfig encodes the hypothesis target paths and expected effects into the run
     * objective so EvoMolior agents receive context about what they should improve.
     *
     * @param hypothesis Hypothesis under test.
     * @param plan Experiment plan defining run parameters.
     * @param scheduledRunId Scheduled run identifier (used as the RunConfig.runId).
     * @param apiKey OpenRouter API key.
     * @param modelOverrides Optional role→model overrides.
     * @return RunConfig ready for launch.
     */
    private fun buildExperimentRunConfig(
        hypothesis: OverseerHypothesis,
        plan: OverseerExperimentPlan,
        scheduledRunId: String,
        apiKey: String,
        modelOverrides: Map<String, String>
    ): RunConfig {
        // Build a self-improvement objective from the hypothesis
        val mutabilityLabel = when (hypothesis.targetScope.mutabilityLevel) {
            OverseerMutabilityLevel.SEMI_MUTABLE -> "configuration"
            OverseerMutabilityLevel.FULLY_MUTABLE -> "application code"
        }

        val targetPaths = hypothesis.targetScope.paths.joinToString(", ")

        val objective = buildString {
            appendLine("SELF-IMPROVEMENT EXPERIMENT RUN")
            appendLine()
            appendLine("Experiment Plan: ${plan.id}")
            appendLine("Hypothesis: ${hypothesis.title}")
            appendLine("Rationale: ${hypothesis.rationale}")
            appendLine()
            appendLine("Target Scope: $mutabilityLabel")
            appendLine("Target Paths: $targetPaths")
            appendLine()
            if (plan.variationSpec.configChanges.isNotEmpty()) {
                appendLine("Configuration Changes: ${plan.variationSpec.configChanges.joinToString("; ")}")
            }
            if (plan.variationSpec.toolOrPromptChanges.isNotEmpty()) {
                appendLine("Tool/Prompt Changes: ${plan.variationSpec.toolOrPromptChanges.joinToString("; ")}")
            }
            appendLine()
            appendLine("Expected Effect: ${hypothesis.expectedEffect.metricsUp.joinToString(", ")} should improve,")
            appendLine("  ${hypothesis.expectedEffect.metricsDown.joinToString(", ")} should decrease,")
            appendLine("  invariants ${hypothesis.expectedEffect.invariantsHold.joinToString(", ")} must hold.")
            appendLine()
            appendLine("Acceptance Criteria: ${plan.acceptanceCriteria.predicates.joinToString(" AND ")}")
            appendLine()
            appendLine("YOUR TASK:")
            appendLine("Analyze the target paths and produce a detailed assessment of whether the proposed")
            appendLine("changes would be beneficial. Document your findings, paying special attention to")
            appendLine("the metrics and invariants listed above.")
        }

        val requestedOutputSpec = when (hypothesis.targetScope.mutabilityLevel) {
            OverseerMutabilityLevel.FULLY_MUTABLE -> FinalOutputSpec(
                relativePath = "self-improvement-patch-bundle.json",
                mediaType = "application/json",
                description = "Machine-readable patch bundle with edited files, diffs, and documentation.",
                kind = FinalOutputKind.PATCH_BUNDLE
            )
            OverseerMutabilityLevel.SEMI_MUTABLE -> FinalOutputSpec(
                relativePath = "experiment-analysis.md",
                mediaType = "text/markdown",
                description = "Self-improvement experiment analysis report.",
                kind = FinalOutputKind.GENERIC_FILE
            )
        }

        val runObjective = if (requestedOutputSpec.kind == FinalOutputKind.PATCH_BUNDLE) {
            buildString {
                appendLine(objective)
                appendLine()
                appendLine("OUTPUT REQUIREMENT:")
                appendLine("Produce a machine-readable JSON patch bundle, not a prose report.")
                appendLine("The JSON must include edited files or diffs plus documentation of the change set.")
                appendLine("Use the target paths as the patch scope and keep the bundle directly actionable.")
            }
        } else {
            objective
        }

        val groupProfiles = listOf(GroupProfileType.Library, GroupProfileType.Factory)

        return RunConfig(
            runId = scheduledRunId,
            objective = runObjective,
            groupCount = 2,
            groupProfiles = groupProfiles,
            rounds = plan.generationsPerRun.coerceIn(1, 5),
            roundDurationMinutes = 15,
            maxAgentsPerGroup = 4,
            scoringDimensions = listOf(
                ScoringDimension(
                    id = "analysis_quality",
                    name = "analysis_quality",
                    weight = 1.0,
                    description = "Quality and depth of architectural analysis of the proposed changes."
                ),
                ScoringDimension(
                    id = "metric_alignment",
                    name = "metric_alignment",
                    weight = 1.0,
                    description = "How well the assessment aligns with the hypothesis metrics."
                ),
                ScoringDimension(
                    id = "feasibility",
                    name = "feasibility",
                    weight = 0.5,
                    description = "Practical feasibility assessment of the proposed changes."
                )
            ),
            openRouterApiKey = apiKey,
            modelOverrides = modelOverrides,
            isTestRun = true,
            budgetOverride = plan.maxCost.takeIf { it > 0 },
            finalOutputSpec = requestedOutputSpec
        )
    }
}
