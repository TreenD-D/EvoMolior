package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.api.model.RunConfig
import com.achulkov.evomolior.competition.grader.GradingSheet
import com.achulkov.evomolior.competition.grader.RubricDimension
import com.achulkov.evomolior.competition.scoring.FailureTelemetry
import com.achulkov.evomolior.competition.scoring.GroupRoundResult
import com.achulkov.evomolior.core.model.GroupProfileType
import com.achulkov.evomolior.core.model.ScoringDimension
import com.achulkov.evomolior.core.model.ScoringResolution
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/**
 * Regression coverage for the blind scoring boundary lifecycle.
 */
class BlindTournamentServiceTest {
    @Test
    fun `service startup cancellation reaches the scoring child and cleans it up`() = runBlocking {
        val worker = ScoringWorkerProcess(
            startupTimeoutMs = 60_000,
            workerMainClass = HangingScoringWorkerMain::class.java.name
        )
        val service = BlindTournamentService(
            onFailureTelemetry = {},
            workerFactory = { worker }
        )

        assertFailsWith<TimeoutCancellationException> {
            withTimeout(1_000) {
                service.start(listOf("group-0"))
            }
        }

        assertFalse(worker.isAlive())
    }

    @Test
    fun `exact grader quorum cannot cross scoring transport without bundles`() = runBlocking {
        val service = BlindTournamentService(onFailureTelemetry = {})
        service.start(listOf("group-0", "group-1"))
        val config = testConfig("run-missing-exact-bundles")

        val error = assertFailsWith<IllegalArgumentException> {
            service.publishRoundResult(
                config = config,
                groupIds = listOf("group-0", "group-1"),
                round = 0,
                expectedGraderGroupIds = setOf("grader-0"),
                exactGraderBundles = emptyList(),
                buildGradingSheets = { _, _ -> emptyList() },
                buildGroupRoundResult = { groupId ->
                    GroupRoundResult(
                        groupId = groupId,
                        roundIndex = 0,
                        dimensionScores = mapOf("accuracy" to 0.0),
                        failureModesTriggered = emptyList(),
                        automatedTestsPassed = 0,
                        automatedTestsFailed = 0
                    )
                }
            )
        }

        assertTrue(error.message.orEmpty().contains("supplied together before scoring transport"))
        service.stop()
    }


    @Test
    fun `blind tournament service can publish and stop independently`() = runBlocking {
        val telemetry = mutableListOf<FailureTelemetry>()
        val service = BlindTournamentService(onFailureTelemetry = { telemetry += it })

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
            buildGradingSheets = { runConfig, round ->
                listOf(
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

    private fun testConfig(runId: String) = RunConfig(
        runId = runId,
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

    @Test
    fun `blind tournament service forwards failure telemetry after reveal`() = runBlocking {
        val telemetry = mutableListOf<FailureTelemetry>()
        val service = BlindTournamentService(onFailureTelemetry = { telemetry += it })

        service.start(listOf("group-0", "group-1"))

        val config = RunConfig(
            runId = "run-blind-service-telemetry-test",
            objective = "blind scoring telemetry boundary",
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
            buildGradingSheets = { runConfig, round ->
                listOf(
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
                if (groupId == "group-0") {
                    GroupRoundResult(
                        groupId = groupId,
                        roundIndex = 0,
                        dimensionScores = mapOf("accuracy" to 9.0),
                        failureModesTriggered = listOf("missing-citation"),
                        automatedTestsPassed = 0,
                        automatedTestsFailed = 1
                    )
                } else {
                    GroupRoundResult(
                        groupId = groupId,
                        roundIndex = 0,
                        dimensionScores = mapOf("accuracy" to 8.0),
                        failureModesTriggered = emptyList(),
                        automatedTestsPassed = 1,
                        automatedTestsFailed = 0
                    )
                }
            }
        )

        assertEquals("group-0", result.winnerId)
        assertEquals(2, result.scores.size)
        withTimeout(5_000) {
            while (telemetry.isEmpty()) {
                kotlinx.coroutines.yield()
            }
        }
        assertEquals(1, telemetry.size)
        assertEquals("group-0", telemetry.single().groupId)
        assertEquals(1, telemetry.single().automatedTestsFailed)
        assertTrue(telemetry.single().weightedScore >= 0.0)

        service.stop()
    }

    @Test
    fun `blind tournament service cannot be started twice without stopping`() = runBlocking {
        val service = BlindTournamentService(onFailureTelemetry = {})
        service.start(listOf("group-0", "group-1"))

        assertFailsWith<IllegalStateException> {
            service.start(listOf("group-0", "group-1"))
        }

        service.stop()
    }

    @Test
    fun `blind tournament service fails closed when sealed result is absent`() = runBlocking {
        val service = BlindTournamentService(onFailureTelemetry = {})
        service.start(listOf("group-0", "group-1"))

        val config = RunConfig(
            runId = "run-blind-service-fallback-test",
            objective = "blind scoring fallback boundary",
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

        assertFailsWith<IllegalStateException> {
            service.publishRoundResult(
                config = config,
                groupIds = listOf("group-0", "group-1"),
                round = 0,
                buildGradingSheets = { _, _ -> emptyList() },
                buildGroupRoundResult = { groupId ->
                    GroupRoundResult(
                        groupId = groupId,
                        roundIndex = 0,
                        dimensionScores = mapOf("accuracy" to if (groupId == "group-0") 4.0 else 7.0),
                        failureModesTriggered = emptyList(),
                        automatedTestsPassed = 0,
                        automatedTestsFailed = 0
                    )
                }
            )
        }

        service.stop()
    }

    @Test
    fun `fallback keeps Pareto ranks but leaves an all-equal round unresolved`() = runBlocking {
        val service = BlindTournamentService(onFailureTelemetry = {})
        service.start(listOf("zeta", "alpha"))
        val config = RunConfig(
            runId = "run-blind-service-equal-fallback-test",
            objective = "detect fallback scoring degeneracy",
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
            groupIds = listOf("zeta", "alpha"),
            round = 0,
            buildGradingSheets = { runConfig, round ->
                listOf(
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
                    dimensionScores = mapOf("accuracy" to 5.0),
                    failureModesTriggered = emptyList(),
                    automatedTestsPassed = 0,
                    automatedTestsFailed = 0
                )
            }
        )

        assertEquals(null, result.winnerId)
        assertEquals(ScoringResolution.ALL_EQUAL_TIE, result.resolution)
        assertEquals(listOf("alpha", "zeta"), result.paretoFronts.single().groupIds)
        assertTrue(result.scores.all { score ->
            score.dimensionScores.single().justification.contains("Missing evidence:")
        })

        service.stop()
    }
}
