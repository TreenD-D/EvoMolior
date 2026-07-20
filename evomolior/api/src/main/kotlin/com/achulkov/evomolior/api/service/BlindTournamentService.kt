package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.api.model.RunConfig
import com.achulkov.evomolior.api.model.ScoringWorkerRequest
import com.achulkov.evomolior.competition.grader.GradingSheet
import com.achulkov.evomolior.competition.grader.ExactGraderRoundVerdict
import com.achulkov.evomolior.competition.grader.ExactGraderRoundStatus
import com.achulkov.evomolior.core.grader.GraderScoreBundle
import com.achulkov.evomolior.competition.scoring.FailureTelemetry
import com.achulkov.evomolior.competition.scoring.GroupRoundResult
import com.achulkov.evomolior.core.contract.DeliverableSkeletonNodeUpdate
import com.achulkov.evomolior.core.model.GradingResult
import kotlinx.coroutines.runBlocking

/**
 * Coordinates final-only scoring through a dedicated run-scoped child JVM.
 *
 * The API process constructs immutable input snapshots but owns no blind-gate or scoring-engine
 * state. Worker startup, transport, decoding, and scoring failures propagate and fail the round.
 *
 * @param onFailureTelemetry Consumer for post-reveal failure telemetry.
 * @param onDeliverableNodeUpdates Consumer for post-reveal deliverable updates.
 * @param onExactGraderReplay Consumer for the isolated child-JVM exact-bundle replay.
 * @param workerFactory Factory used to create the isolated process supervisor.
 */
internal class BlindTournamentService(
    private val onFailureTelemetry: suspend (FailureTelemetry) -> Unit,
    private val onDeliverableNodeUpdates: suspend (List<DeliverableSkeletonNodeUpdate>) -> Unit = {},
    private val onExactGraderReplay:
        suspend (round: Int, verdict: ExactGraderRoundVerdict) -> Unit = { _, _ -> },
    private val workerFactory: () -> ScoringWorkerProcess = ::ScoringWorkerProcess
) {
    private var worker: ScoringWorkerProcess? = null

    fun start(groupIds: List<String>) {
        check(worker == null) { "BlindTournamentService is already running" }
        val process = workerFactory()
        runBlocking { process.start(groupIds) }
        worker = process
    }

    suspend fun publishRoundResult(
        config: RunConfig,
        groupIds: List<String>,
        round: Int,
        expectedGraderGroupIds: Set<String> = emptySet(),
        exactGraderBundles: List<GraderScoreBundle> = emptyList(),
        buildGradingSheets: suspend (RunConfig, Int) -> List<GradingSheet>,
        buildGroupRoundResult: (String) -> GroupRoundResult
    ): GradingResult {
        val process = worker ?: error("Scoring worker is not initialised for run ${config.runId}")
        check(process.isAlive()) { "Scoring worker is unavailable for run ${config.runId}" }
        require(expectedGraderGroupIds.isEmpty() == exactGraderBundles.isEmpty()) {
            "Exact grader quorum and bundles must be supplied together before scoring transport"
        }
        val response = process.score(
            ScoringWorkerRequest(
                roundIndex = round,
                gradingSheets = buildGradingSheets(config, round),
                submissions = groupIds.map(buildGroupRoundResult),
                expectedGraderGroupIds = expectedGraderGroupIds,
                exactGraderBundles = exactGraderBundles
            )
        )
        response.exactGraderVerdict?.let { verdict ->
            check(verdict.status == ExactGraderRoundStatus.VALID) {
                "Scoring child rejected exact grader authority: ${verdict.reason}"
            }
            onExactGraderReplay(round, verdict)
        }
        response.failureTelemetry.forEach { onFailureTelemetry(it) }
        if (response.deliverableNodeUpdates.isNotEmpty()) {
            onDeliverableNodeUpdates(response.deliverableNodeUpdates)
        }
        return response.result
    }

    fun stop() {
        val process = worker ?: return
        runBlocking { process.stop() }
        worker = null
    }
}
