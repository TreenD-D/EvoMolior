package com.achulkov.evokammer.api.service

import com.achulkov.evokammer.api.model.RunConfig
import com.achulkov.evokammer.competition.grader.GradingSheet
import com.achulkov.evokammer.competition.grader.RubricDimension
import com.achulkov.evokammer.competition.scoring.FailureTelemetry
import com.achulkov.evokammer.competition.scoring.GroupRoundResult
import com.achulkov.evokammer.core.model.GroupProfileType
import com.achulkov.evokammer.core.model.ScoringDimension
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression coverage for the blind scoring boundary lifecycle.
 */
class BlindTournamentServiceTest {

    @Test
    fun `blind tournament service can publish and stop independently`() = runBlocking {
        val telemetry = mutableListOf<FailureTelemetry>()
        val service = BlindTournamentService { telemetry += it }

        service.start(listOf("group-0", "group-1"))

        val config = RunConfig(
            runId = "run-blind-service-test",
            objective = "blind scoring boundary",
            groupCount = 2,
            groupProfiles = listOf(GroupProfileType.Library, GroupProfileType.Factory),
            rounds = 1,
            roundDurationMinutes = 1,
            maxAgentsPerGroup = 1,
            scoringDimensions = listOf(
                ScoringDimension(
                    id = "accuracy",
                    name = "Accuracy",
                    description = "Score accuracy",
                    weight = 1.0,
                    maxScore = 10.0
                )
            ),
            openRouterApiKey = "sk-test",
            isTestRun = true
        )

        val result = service.publishRoundResult(
            config = config,
            groupIds = listOf("group-0", "group-1"),
            round = 0,
            registerRound = { engine, runConfig, round ->
                engine.registerGradingSheet(
                    GradingSheet(
                        sheetId = "sheet-$round",
                        runId = runConfig.runId,
                        roundIndex = round,
                        graderGroupId = "grader-0",
                        rubricDimensions = listOf(
                            RubricDimension(
                                dimensionId = "accuracy",
                                name = "Accuracy",
                                weight = 1.0,
                                scoringGuidance = "Score accuracy",
                                maxScore = 10.0
                            )
                        ),
                        automatedTests = emptyList(),
                        failureModes = emptyList(),
                        comparativePairs = emptyList(),
                        sealedAt = System.currentTimeMillis()
                    )
                )
            },
            buildGroupRoundResult = { groupId ->
                GroupRoundResult(
                    groupId = groupId,
                    roundIndex = 0,
                    dimensionScores = mapOf("accuracy" to if (groupId == "group-0") 9.0 else 6.0),
                    failureModesTriggered = emptyList(),
                    automatedTestsPassed = 1,
                    automatedTestsFailed = 0
                )
            }
        )

        assertEquals("group-0", result.winnerId)
        assertTrue(result.scores.isNotEmpty())
        assertTrue(telemetry.isEmpty())

        service.stop()
    }
}
