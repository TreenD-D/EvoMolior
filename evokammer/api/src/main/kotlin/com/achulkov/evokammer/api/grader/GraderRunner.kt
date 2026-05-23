package com.achulkov.evokammer.api.grader

import com.achulkov.evokammer.agents.factory.GraderAgentContext
import com.achulkov.evokammer.agents.factory.GraderAgentFactory
import com.achulkov.evokammer.competition.grader.CanonSnapshotExporter
import com.achulkov.evokammer.competition.grader.GraderGroupManager
import com.achulkov.evokammer.core.budget.BudgetConfig
import com.achulkov.evokammer.core.model.DimensionScore
import com.achulkov.evokammer.core.model.GroupProfile
import com.achulkov.evokammer.core.model.GroupProfileType
import com.achulkov.evokammer.core.model.Role
import com.achulkov.evokammer.core.model.ScoringDimension
import com.achulkov.evokammer.substrate.SubstrateDatabaseFactory
import io.github.aakira.napier.Napier
import kotlinx.coroutines.withTimeout

/**
 * Orchestrates a full Grader Group evaluation round.
 *
 * For each grader group and each competing group, this runner:
 * 1. Exports a canon snapshot of the competing group's substrate.
 * 2. Runs three grader agents sequentially: GraderResearcher → GraderBuilder → GraderCritic.
 * 3. Each agent passes a summary of their findings to the next agent in the pipeline.
 * 4. Collected [DimensionScore]s are submitted to [GraderGroupManager].
 *
 * Grader agents run **post-round** (after all competing agents have completed) and
 * **pre-grading** (before GraderGroupManager.computeRoundResult).
 *
 * Lives in the `:evokammer:api` module because it depends on both `:evokammer:agents`
 * (via transitive path api → competition → orchestration → agents) and
 * `:evokammer:competition` directly, avoiding a circular dependency.
 *
 * @param openRouterApiKey OpenRouter API key passed to [GraderAgentFactory].
 * @param substrateFactory Factory for accessing both competing and grader substrate DBs.
 * @param localAgentRunner Suspend function that runs a [GraderAgentContext] agent.
 *   Called once per grader agent. Return value is the agent's final text output.
 * @param graderAgentTimeoutMs Maximum ms a single grader agent is allowed to run (default 4 min).
 */
class GraderRunner(
    private val openRouterApiKey: String,
    private val substrateFactory: SubstrateDatabaseFactory,
    private val localAgentRunner: suspend (context: GraderAgentContext) -> String,
    private val graderAgentTimeoutMs: Long = 4 * 60_000L
) {
    /**
     * Runs a full grader evaluation round.
     *
     * For each graderGroupId in [graderGroupIds], evaluates all [competingGroupIds] by running
     * 3 grader agents sequentially per target group. Scores are submitted to [graderGroupManager].
     *
     * This method catches and logs per-group errors so a single grader failure does not abort
     * the entire round. The [GraderGroupManager] will use whatever scores were collected.
     *
     * @param runId The current run ID.
     * @param graderGroupIds Grader group IDs (typically ["grader-0"] for single grader group).
     * @param competingGroupIds All competing group IDs to evaluate.
     * @param roundIndex Current round index (0-based).
     * @param runObjective The run's original objective string.
     * @param scoringDimensions All scoring dimensions for this run.
     * @param graderGroupManager Manager to submit collected scores to.
     * @param onProgress Optional callback for progress updates (human-readable status string).
     */
    @Suppress("unused") // Legacy; replaced by runCriteriaPhase + runJudgingPhase two-phase architecture
    suspend fun runGraderRound(
        runId: String,
        graderGroupIds: List<String>,
        competingGroupIds: List<String>,
        roundIndex: Int,
        runObjective: String,
        scoringDimensions: List<ScoringDimension>,
        graderGroupManager: GraderGroupManager,
        onProgress: ((String) -> Unit)? = null
    ) {
        Napier.i(
            "GraderRunner: starting grader round=$roundIndex " +
            "graders=${graderGroupIds.size} targets=${competingGroupIds.size}"
        )

        for (graderGroupId in graderGroupIds) {
            val graderDb = substrateFactory.getGrader(runId, graderGroupId)

            // Collect scores via callback — each grader agent calls submit_grading_score
            val collectedScores = mutableMapOf<String, MutableList<DimensionScore>>()

            val factory = GraderAgentFactory(
                openRouterApiKey = openRouterApiKey,
                graderDb = graderDb,
                graderBudgetConfig = GRADER_BUDGET_CONFIG.copy(groupId = graderGroupId),
                graderGroupId = graderGroupId,
                onScoreSubmitted = { targetGroupId, score ->
                    collectedScores.getOrPut(targetGroupId) { mutableListOf() }.add(score)
                    Napier.d(
                        "GraderRunner [$graderGroupId] score collected: " +
                        "$targetGroupId | ${score.dimensionId} = ${
                            String.format(java.util.Locale.ROOT, "%.1f", score.rawScore)
                        }"
                    )
                }
            )

            for (targetGroupId in competingGroupIds) {
                onProgress?.invoke("[$graderGroupId] evaluating $targetGroupId (round $roundIndex)")

                try {
                    evaluateGroup(
                        factory = factory,
                        runId = runId,
                        roundIndex = roundIndex,
                        targetGroupId = targetGroupId,
                        graderGroupId = graderGroupId,
                        runObjective = runObjective,
                        scoringDimensions = scoringDimensions
                    )
                } catch (e: Exception) {
                    Napier.w(
                        "GraderRunner: evaluation failed for $targetGroupId " +
                        "by $graderGroupId round=$roundIndex: ${e.message}"
                    )
                }

                // Submit whatever scores were collected (even partial from a crashed run)
                val scoresForGroup = collectedScores[targetGroupId] ?: emptyList()
                if (scoresForGroup.isNotEmpty()) {
                    graderGroupManager.submitScores(
                        graderGroupId = graderGroupId,
                        targetGroupId = targetGroupId,
                        roundIndex = roundIndex,
                        scores = scoresForGroup
                    )
                    Napier.i(
                        "GraderRunner [$graderGroupId] submitted ${scoresForGroup.size} scores " +
                        "for $targetGroupId round=$roundIndex"
                    )
                } else {
                    Napier.w(
                        "GraderRunner [$graderGroupId]: no scores collected for $targetGroupId " +
                        "round=$roundIndex — group will default to substrate metrics only"
                    )
                }
            }
        }

        Napier.i("GraderRunner: grader round=$roundIndex complete")
    }

    // ── Two-phase architecture: criteria development + round judging ────────────────────────

    /**
     * **Phase A: Criteria Development** — runs DURING the round, in parallel with main agents.
     *
     * For each grader group, spawns two agents sequentially:
     * 1. [Role.GraderResearcher] — researches and designs scoring criteria for each dimension
     * 2. [Role.GraderBuilder] — refines the criteria for operational precision
     *
     * Agents write their criteria to the grader group's staging area via `write_staging`.
     * They do NOT have access to any competing group's data and do NOT call `submit_grading_score`.
     *
     * Meant to be launched as a concurrent coroutine at round start.
     *
     * @param runId Current run ID.
     * @param graderGroupIds Grader group IDs.
     * @param roundIndex Round for which criteria are being developed.
     * @param runObjective The run's objective string.
     * @param scoringDimensions Dimensions to develop criteria for.
     * @param onProgress Optional progress callback.
     */
    suspend fun runCriteriaPhase(
        runId: String,
        graderGroupIds: List<String>,
        roundIndex: Int,
        runObjective: String,
        scoringDimensions: List<ScoringDimension>,
        onProgress: ((String) -> Unit)? = null
    ) {
        Napier.i("GraderRunner: starting criteria phase round=$roundIndex graderGroups=${graderGroupIds.size}")

        for (graderGroupId in graderGroupIds) {
            val graderDb = substrateFactory.getGrader(runId, graderGroupId)
            val factory = GraderAgentFactory(
                openRouterApiKey = openRouterApiKey,
                graderDb = graderDb,
                graderBudgetConfig = GRADER_BUDGET_CONFIG.copy(groupId = graderGroupId),
                graderGroupId = graderGroupId,
                onScoreSubmitted = { _, _ -> /* Criteria agents never submit scores */ }
            )

            var previousCriteria: String? = null
            for (role in listOf(Role.GraderResearcher, Role.GraderBuilder)) {
                onProgress?.invoke("[$graderGroupId] criteria phase: spawning $role for round $roundIndex")
                Napier.d("GraderRunner: criteria phase spawning $role for $graderGroupId round=$roundIndex")
                val context = factory.createCriteria(
                    role = role,
                    runId = runId,
                    roundIndex = roundIndex,
                    runObjective = runObjective,
                    scoringDimensions = scoringDimensions,
                    previousCriteria = previousCriteria
                )
                val output = try {
                    withTimeout(graderAgentTimeoutMs) { localAgentRunner(context) }
                } catch (e: Exception) {
                    Napier.w("GraderRunner: criteria $role failed for $graderGroupId: ${e.message}")
                    "[$role criteria phase terminated: ${e.message?.take(200)}]"
                }
                previousCriteria = buildString {
                    appendLine("=== ${role.name} CRITERIA ===")
                    appendLine(output.take(3_000))
                }
                Napier.d("GraderRunner: criteria $role completed for $graderGroupId round=$roundIndex (${output.length} chars)")
            }
            onProgress?.invoke("[$graderGroupId] criteria phase complete for round $roundIndex")
        }
        Napier.i("GraderRunner: criteria phase complete round=$roundIndex")
    }

    /**
     * **Phase B: Round Judging** — runs POST-round, after CriticPipeline.
     *
     * For each grader group, spawns a single RoundJudge agent that:
     * 1. Reads criteria developed during the criteria phase from the grader staging DB.
     * 2. Receives canon snapshots from all competing groups.
     * 3. Calls `submit_grading_score` for every (group × dimension) pair.
     *
     * Scores are submitted to [graderGroupManager] after the judge completes.
     *
     * @param runId Current run ID.
     * @param graderGroupIds Grader group IDs.
     * @param competingGroupIds All competing group IDs to be judged.
     * @param roundIndex Current round index.
     * @param runObjective The run's objective string.
     * @param scoringDimensions All scoring dimensions.
     * @param graderGroupManager Manager to submit collected scores to.
     * @param onProgress Optional progress callback.
     */
    suspend fun runJudgingPhase(
        runId: String,
        graderGroupIds: List<String>,
        competingGroupIds: List<String>,
        roundIndex: Int,
        runObjective: String,
        scoringDimensions: List<ScoringDimension>,
        graderGroupManager: GraderGroupManager,
        onProgress: ((String) -> Unit)? = null
    ) {
        Napier.i(
            "GraderRunner: starting judging phase round=$roundIndex " +
            "graders=${graderGroupIds.size} targets=${competingGroupIds.size}"
        )

        for (graderGroupId in graderGroupIds) {
            val graderDb = substrateFactory.getGrader(runId, graderGroupId)
            val collectedScores = mutableMapOf<String, MutableList<DimensionScore>>()

            val factory = GraderAgentFactory(
                openRouterApiKey = openRouterApiKey,
                graderDb = graderDb,
                graderBudgetConfig = GRADER_BUDGET_CONFIG.copy(groupId = graderGroupId),
                graderGroupId = graderGroupId,
                onScoreSubmitted = { targetGroupId, score ->
                    collectedScores.getOrPut(targetGroupId) { mutableListOf() }.add(score)
                    Napier.d(
                        "GraderRunner.judge [$graderGroupId] score: $targetGroupId | " +
                        "${score.dimensionId} = ${String.format(java.util.Locale.ROOT, "%.1f", score.rawScore)}"
                    )
                }
            )

            // Read criteria from grader staging DB — promoted PLAN_UPDATE entries are the criteria
            val criteriaEntries = try {
                graderDb.stagingDao().getAll(runId, graderGroupId)
                    .filter { it.type == "PLAN_UPDATE" && (it.status == "promoted" || it.status == "pending") }
                    .sortedBy { it.submittedAtMs }
            } catch (e: Exception) {
                Napier.w("GraderRunner: failed to read criteria for $graderGroupId: ${e.message}")
                emptyList()
            }
            val criteriaContext = if (criteriaEntries.isNotEmpty()) {
                criteriaEntries.joinToString("\n\n") { entry ->
                    "[Criteria by ${entry.role}]\n${entry.content}"
                }
            } else {
                Napier.w("GraderRunner: no criteria found for $graderGroupId round=$roundIndex — judge will use dimension descriptions only")
                "(no criteria developed — evaluate based on dimension descriptions and your own judgment)"
            }

            // Export canon snapshots for all competing groups
            val allGroupSnapshots = competingGroupIds.associateWith { targetGroupId ->
                try {
                    CanonSnapshotExporter.export(
                        substrateFactory.get(runId, targetGroupId),
                        runId, targetGroupId, roundIndex
                    )
                } catch (e: Exception) {
                    Napier.w("GraderRunner: snapshot export failed for $targetGroupId: ${e.message}")
                    "(snapshot unavailable: ${e.message?.take(150)})"
                }
            }

            onProgress?.invoke("[$graderGroupId] RoundJudge spawning for round $roundIndex — ${competingGroupIds.size} groups to score")
            Napier.i("GraderRunner: spawning RoundJudge for $graderGroupId round=$roundIndex")

            val judgeContext = factory.createRoundJudge(
                runId = runId,
                roundIndex = roundIndex,
                runObjective = runObjective,
                scoringDimensions = scoringDimensions,
                criteriaContext = criteriaContext,
                allGroupSnapshots = allGroupSnapshots
            )

            try {
                withTimeout(graderAgentTimeoutMs * 2) {  // RoundJudge needs more time — scores N×M combinations
                    localAgentRunner(judgeContext)
                }
            } catch (e: Exception) {
                Napier.w("GraderRunner: RoundJudge failed for $graderGroupId round=$roundIndex: ${e.message}")
            }

            // Submit whatever scores were collected (even if judge partially failed)
            val totalScores = collectedScores.values.sumOf { it.size }
            Napier.i("GraderRunner.judge [$graderGroupId] collected $totalScores scores from RoundJudge")

            for (targetGroupId in competingGroupIds) {
                val scoresForGroup = collectedScores[targetGroupId] ?: emptyList()
                if (scoresForGroup.isNotEmpty()) {
                    graderGroupManager.submitScores(
                        graderGroupId = graderGroupId,
                        targetGroupId = targetGroupId,
                        roundIndex = roundIndex,
                        scores = scoresForGroup
                    )
                    Napier.i("GraderRunner.judge [$graderGroupId] submitted ${scoresForGroup.size} scores for $targetGroupId")
                } else {
                    Napier.w("GraderRunner.judge [$graderGroupId] no scores for $targetGroupId — will rely on substrate scoring")
                }
            }
            onProgress?.invoke("[$graderGroupId] judging phase complete for round $roundIndex — $totalScores scores submitted")
        }

        Napier.i("GraderRunner: judging phase complete round=$roundIndex")
    }

    // ── Legacy 3-agent pipeline (kept for backward compatibility) ────────────────────────────

    /**
     * Runs the 3-agent sequential evaluation pipeline for one target group.
     *
     * Pipeline: GraderResearcher → GraderBuilder → GraderCritic
     * Each stage receives the prior stage's output as previousFindings.
     */
    private suspend fun evaluateGroup(
        factory: GraderAgentFactory,
        runId: String,
        roundIndex: Int,
        targetGroupId: String,
        graderGroupId: String,
        runObjective: String,
        scoringDimensions: List<ScoringDimension>
    ) {
        Napier.d("GraderRunner: evaluating $targetGroupId by $graderGroupId round=$roundIndex")

        // Export the competing group's substrate as a read-only text snapshot
        val competingDb = substrateFactory.get(runId, targetGroupId)
        val snapshot = CanonSnapshotExporter.export(competingDb, runId, targetGroupId, roundIndex)

        var previousFindings: String? = null

        for (role in listOf(Role.GraderResearcher, Role.GraderBuilder, Role.GraderCritic)) {
            Napier.d("GraderRunner: spawning $role for $targetGroupId")

            val context = factory.create(
                role = role,
                runId = runId,
                roundIndex = roundIndex,
                targetGroupId = targetGroupId,
                runObjective = runObjective,
                scoringDimensions = scoringDimensions,
                canonSnapshot = snapshot,
                previousFindings = previousFindings
            )

            val agentOutput = try {
                withTimeout(graderAgentTimeoutMs) {
                    localAgentRunner(context)
                }
            } catch (e: Exception) {
                Napier.w(
                    "GraderRunner: $role agent timed out or failed for $targetGroupId: ${e.message}"
                )
                "[$role terminated without output: ${e.message?.take(200)}]"
            }

            // Pass this agent's output as "previous findings" to the next agent
            previousFindings = buildString {
                appendLine("=== ${role.name} FINDINGS ===")
                appendLine(agentOutput.take(3_000))
            }

            Napier.d(
                "GraderRunner: $role completed for $targetGroupId " +
                "budget_remaining=${
                    String.format(java.util.Locale.ROOT, "%.1f", context.budgetLedger.balance)
                }"
            )
        }
    }

    companion object {
        /**
         * Default budget profile for grader agents.
         *
         * Grader agents use the Library profile (read-heavy) but with a modest spawnBudget
         * since their primary work is reading + scoring, not writing.
         */
        private val GRADER_BUDGET_CONFIG = BudgetConfig(
            groupId = "grader",
            profile = GroupProfile(
                profileType = GroupProfileType.Library,
                webFetchMultiplier = 0.5,
                toolExecMultiplier = 2.0,
                interAgentMessageMultiplier = 1.0,
                writeStagingMultiplier = 1.0,
                llmCallMultiplier = 1.0,
                spawnBudget = 40.0,
                maxFleetSize = 3,
                kgEmphasis = 0.5,
                kbEmphasis = 0.5
            )
        )
    }
}
