package com.achulkov.evokammer.api.service

import com.achulkov.evokammer.api.model.RunConfig
import com.achulkov.evokammer.competition.scoring.BlindSubmissionGate
import com.achulkov.evokammer.competition.scoring.FailureTelemetry
import com.achulkov.evokammer.competition.scoring.GroupRoundResult
import com.achulkov.evokammer.competition.scoring.ScoringEngine
import com.achulkov.evokammer.core.model.GradingResult
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first

/**
 * Encapsulates the blind-tournament scoring lifecycle behind a dedicated in-process service boundary.
 *
 * This keeps the scoring gate, scoring engine, and their collectors off the main agent scope while
 * still remaining in the same JVM process.
 */
internal class BlindTournamentService(
    private val onFailureTelemetry: suspend (FailureTelemetry) -> Unit
) {
    private var scope: CoroutineScope? = null
    private var gate: BlindSubmissionGate? = null
    private var engine: ScoringEngine? = null
    private var failureTelemetryJob: Job? = null

    fun start(groupIds: List<String>) {
        check(scope == null) { "BlindTournamentService is already running" }
        val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val blindGate = BlindSubmissionGate(groupIds)
        val scoringEngine = ScoringEngine(
            metaJudge = com.achulkov.evokammer.competition.grader.MetaJudge(minGraderGroups = 1),
            blindGate = blindGate
        )

        scope = runtimeScope
        gate = blindGate
        engine = scoringEngine

        failureTelemetryJob = runtimeScope.launch {
            scoringEngine.failureTelemetry.collect { telemetry ->
                onFailureTelemetry(telemetry)
            }
        }
    }

    suspend fun publishRoundResult(
        config: RunConfig,
        groupIds: List<String>,
        round: Int,
        registerRound: suspend (ScoringEngine, RunConfig, Int) -> Unit,
        buildGroupRoundResult: (String) -> GroupRoundResult
    ): GradingResult {
        val blindGate = gate ?: error("BlindSubmissionGate is not initialised for run ${config.runId}")
        val scoringEngine = engine ?: error("ScoringEngine is not initialised for run ${config.runId}")

        registerRound(scoringEngine, config, round)

        blindGate.openGate(round)
        groupIds.forEach { groupId ->
            blindGate.submitGroupResults(
                groupId = groupId,
                result = buildGroupRoundResult(groupId)
            )
        }

        val revealed = blindGate.closeAndReveal(forceReveal = false)
        return scoringEngine.processRevealedScores(revealed)
            ?: error("ScoringEngine did not produce a grading result for run ${config.runId} round $round")
    }

    fun stop() {
        failureTelemetryJob?.cancel()
        failureTelemetryJob = null
        scope?.cancel()
        scope = null
        gate = null
        engine = null
    }
}
