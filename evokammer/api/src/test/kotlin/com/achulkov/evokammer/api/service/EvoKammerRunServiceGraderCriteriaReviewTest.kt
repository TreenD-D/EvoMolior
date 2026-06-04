package com.achulkov.evokammer.api.service

import com.achulkov.evokammer.api.events.EvoKammerEvent
import com.achulkov.evokammer.competition.coordinator.GroupCoordinator
import com.achulkov.evokammer.competition.grader.MetaJudge
import com.achulkov.evokammer.competition.grader.GraderGroupManager
import com.achulkov.evokammer.competition.scoring.BlindSubmissionGate
import com.achulkov.evokammer.competition.scoring.ScoringEngine
import com.achulkov.evokammer.core.contract.AgentBootContext
import com.achulkov.evokammer.core.model.GroupProfile
import com.achulkov.evokammer.core.model.GroupProfileType
import com.achulkov.evokammer.core.model.ScoringDimension
import com.achulkov.evokammer.core.model.SubmissionType
import com.achulkov.evokammer.orchestration.lifecycle.LifecycleManager
import com.achulkov.evokammer.orchestration.metaplanner.MetaPlannerRunner
import com.achulkov.evokammer.sandbox.shuru.ShuruVmManager
import com.achulkov.evokammer.substrate.SubstrateDatabaseFactory
import com.achulkov.evokammer.substrate.db.entity.StagingSubmissionEntity
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.jvm.isAccessible

/**
 * Verifies that the live round loop waits for criteria development to finish before judging.
 */
class EvoKammerRunServiceGraderCriteriaReviewTest {

    @Test
    fun `grader criteria phase completes before judging begins`() = runTest {
        val dataRoot = createTempDirectory("grader-criteria-round-flow-test").toFile()
        val factory = SubstrateDatabaseFactory(dataRoot)
        val runId = "run-criteria-flow"
        val order = mutableListOf<String>()
        val emittedEvents = mutableListOf<EvoKammerEvent>()
        val graderGroupId = "grader-0"
        val plannedCriteriaId = "grader-criteria-1"

        val service = EvoKammerRunService(
            lifecycleManagerProvider = { _, _, _, _, _ ->
                throw IllegalStateException("LifecycleManager should not be constructed in the round-flow test")
            },
            groupCoordinatorProvider = { groupIds, dimensions ->
                GroupCoordinator(groupIds, dimensions)
            },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = factory,
            metaPlannerRunner = MetaPlannerRunner(),
            graderCriteriaRunner = { runIdValue, _, reviewerGroupIds, roundIndex, runObjective, scoringDimensions, onProgress ->
                order += "criteria-start"
                val graderDb = factory.getGrader(runIdValue, reviewerGroupIds.single())
                graderDb.stagingDao().insert(
                    StagingSubmissionEntity(
                        id = plannedCriteriaId,
                        agentId = "criteria-agent",
                        role = "GraderResearcher",
                        type = SubmissionType.PLAN_UPDATE.name,
                        content = "draft rubric for $runObjective",
                        budgetSignature = "system:grader-criteria",
                        status = "pending",
                        generation = roundIndex + 1,
                        groupId = reviewerGroupIds.single(),
                        runId = runIdValue,
                        submittedAtMs = 1_000L,
                        reviewedAtMs = null
                    )
                )
                onProgress?.invoke("grader criteria staged")
                delay(100)
                order += "criteria-done"
            },
            graderJudgingRunner = { _, _, _, _, _, _, _, _, onProgress ->
                order += "judging-start"
                onProgress?.invoke("judging started")
                emptyMap()
            }
        )

        val eventCollector = launch {
            service.events.collect { emittedEvents += it }
        }

        val config = com.achulkov.evokammer.api.model.RunConfig(
            runId = runId,
            objective = "criteria review order test",
            groupCount = 2,
            groupProfiles = listOf(
                com.achulkov.evokammer.core.model.GroupProfileType.Library,
                com.achulkov.evokammer.core.model.GroupProfileType.Factory
            ),
            rounds = 1,
            roundDurationMinutes = 0,
            maxAgentsPerGroup = 1,
            scoringDimensions = listOf(
                ScoringDimension(
                    id = "accuracy",
                    name = "Accuracy",
                    description = "Reward evidence-based evaluation.",
                    weight = 1.0
                )
            ),
            openRouterApiKey = "sk-test",
            isTestRun = true
        )

        val runLoop = service::class.declaredFunctions.first { it.name == "runLoop" }.apply {
            isAccessible = true
        }
        runLoop.callSuspend(
            service,
            config,
            listOf("group-0", "group-1"),
            GroupCoordinator(listOf("group-0", "group-1"), config.scoringDimensions),
            listOf(graderGroupId),
            GraderGroupManager(config.scoringDimensions),
            mutableSetOf<Int>()
        )

        eventCollector.cancel()

        assertEquals(
            listOf("criteria-start", "criteria-done", "judging-start"),
            order,
            "The round should move from criteria development directly into judging once criteria completes"
        )
        assertTrue("criteria-review" !in order)
        assertTrue(
            emittedEvents.any { event ->
                event is EvoKammerEvent.EngineProgress &&
                    event.phase == "sealed_suite_verdict" &&
                    event.message.contains("winner", ignoreCase = true)
            },
            "The live round loop should emit a sealed-suite verdict progress event"
        )
    }

    @Test
    fun `grader manager verdict is emitted exactly once per round`() = runTest {
        val dataRoot = createTempDirectory("grader-manager-verdict-twice-test").toFile()
        val factory = SubstrateDatabaseFactory(dataRoot)
        val runId = "run-grader-manager-verdict"
        val emittedEvents = mutableListOf<EvoKammerEvent>()
        val criteriaRounds = mutableListOf<Int>()
        val judgingRounds = mutableListOf<Int>()

        val service = EvoKammerRunService(
            lifecycleManagerProvider = { _, _, _, _, _ ->
                throw IllegalStateException("LifecycleManager should not be constructed in the verdict-count test")
            },
            groupCoordinatorProvider = { groupIds, dimensions ->
                GroupCoordinator(groupIds, dimensions)
            },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = factory,
            metaPlannerRunner = MetaPlannerRunner(),
            graderCriteriaRunner = { _, _, _, roundIndex, _, _, onProgress ->
                criteriaRounds += roundIndex
                onProgress?.invoke("criteria staged for round $roundIndex")
            },
            graderJudgingRunner = { _, _, _, _, roundIndex, _, _, _, onProgress ->
                judgingRounds += roundIndex
                onProgress?.invoke("judging started for round $roundIndex")
                emptyMap()
            }
        )

        val blindTournamentService = BlindTournamentService { }
        blindTournamentService.start(listOf("group-0", "group-1"))
        val blindTournamentField = service::class.java.getDeclaredField("activeBlindTournamentService").apply {
            isAccessible = true
        }
        blindTournamentField.set(service, blindTournamentService)

        val eventCollector = launch {
            service.events.collect { emittedEvents += it }
        }

        val config = com.achulkov.evokammer.api.model.RunConfig(
            runId = runId,
            objective = "verdict count test",
            groupCount = 2,
            groupProfiles = listOf(
                com.achulkov.evokammer.core.model.GroupProfileType.Library,
                com.achulkov.evokammer.core.model.GroupProfileType.Factory
            ),
            rounds = 2,
            roundDurationMinutes = 0,
            maxAgentsPerGroup = 1,
            scoringDimensions = listOf(
                ScoringDimension(
                    id = "accuracy",
                    name = "Accuracy",
                    description = "Reward evidence-based evaluation.",
                    weight = 1.0
                )
            ),
            openRouterApiKey = "sk-test",
            isTestRun = true
        )

        val runLoop = service::class.declaredFunctions.first { it.name == "runLoop" }.apply {
            isAccessible = true
        }
        runLoop.callSuspend(
            service,
            config,
            listOf("group-0", "group-1"),
            GroupCoordinator(listOf("group-0", "group-1"), config.scoringDimensions),
            listOf("grader-0"),
            GraderGroupManager(config.scoringDimensions),
            mutableSetOf<Int>()
        )

        eventCollector.cancel()

        assertEquals(listOf(0, 1), criteriaRounds)
        assertEquals(listOf(0, 1), judgingRounds)
        assertEquals(
            2,
            emittedEvents.count { event ->
                event is EvoKammerEvent.EngineProgress && event.phase == "sealed_suite_verdict"
            },
            "The sealed-suite verdict step should fire once per round"
        )

        blindTournamentService.stop()
    }

    @Test
    fun `grader criteria review hook promotes staged criteria before judging`() = runTest {
        val dataRoot = createTempDirectory("grader-criteria-review-test").toFile()
        val factory = SubstrateDatabaseFactory(dataRoot)
        val runId = "run-criteria-review"
        val graderGroupId = "grader-0"
        val graderDb = factory.getGrader(runId, graderGroupId)

        graderDb.stagingDao().insert(
            StagingSubmissionEntity(
                id = "grader-criteria-1",
                agentId = "criteria-agent",
                role = "GraderResearcher",
                type = SubmissionType.PLAN_UPDATE.name,
                content = "draft grading rubric for evaluation",
                budgetSignature = "system:grader-criteria",
                status = "pending",
                generation = 1,
                groupId = graderGroupId,
                runId = runId,
                submittedAtMs = 1_000L,
                reviewedAtMs = null
            )
        )

        var reviewerInvoked = false
        val service = EvoKammerRunService(
            lifecycleManagerProvider = { _, _, _, _, _ ->
                throw IllegalStateException("LifecycleManager should not be constructed in this review test")
            },
            groupCoordinatorProvider = { groupIds, dimensions ->
                GroupCoordinator(groupIds, dimensions)
            },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = factory,
            metaPlannerRunner = MetaPlannerRunner(),
            graderCriteriaReviewRunner = { reviewRunId, _, reviewerGroupIds, roundIndex, onProgress ->
                reviewerInvoked = true
                assertEquals(runId, reviewRunId)
                assertEquals(listOf(graderGroupId), reviewerGroupIds)
                assertEquals(0, roundIndex)
                val entry = graderDb.stagingDao().getById("grader-criteria-1")
                requireNotNull(entry)
                graderDb.stagingDao().updateReview(
                    id = entry.id,
                    status = "promoted",
                    criticId = "critic-test",
                    trustScore = 0.9,
                    reason = null,
                    reviewedAtMs = 2_000L
                )
                onProgress?.invoke("grader criteria review complete")
            }
        )

        service.reviewGraderCriteriaForRound(
            runId = runId,
            openRouterApiKey = "sk-test",
            graderGroupIds = listOf(graderGroupId),
            roundIndex = 0
        )

        assertTrue(reviewerInvoked, "The grader criteria review hook should be invoked before judging")

        val promotedEntry = graderDb.stagingDao().getById("grader-criteria-1")
        requireNotNull(promotedEntry)
        assertEquals("promoted", promotedEntry.status)
        assertEquals("critic-test", promotedEntry.criticAgentId)

        val pendingCount = graderDb.stagingDao().countPending(runId, graderGroupId)
        assertEquals(0, pendingCount)

        // The criteria document still remains in grader substrate history for later judging.
        val allEntries = graderDb.stagingDao().getAll(runId, graderGroupId)
        assertTrue(allEntries.any { it.content.contains("draft grading rubric") })
    }

    @Test
    fun `default grader criteria review path rejects invalid staged criteria without custom hook`() = runTest {
        val dataRoot = createTempDirectory("grader-criteria-default-path-test").toFile()
        val factory = SubstrateDatabaseFactory(dataRoot)
        val runId = "run-criteria-default"
        val graderGroupId = "grader-0"
        val graderDb = factory.getGrader(runId, graderGroupId)

        graderDb.stagingDao().insert(
            StagingSubmissionEntity(
                id = "grader-criteria-default-1",
                agentId = "criteria-agent",
                role = "GraderResearcher",
                type = SubmissionType.PLAN_UPDATE.name,
                content = "draft rubric that should be rejected by CriticPipeline",
                budgetSignature = "",
                status = "pending",
                generation = 1,
                groupId = graderGroupId,
                runId = runId,
                submittedAtMs = 1_000L,
                reviewedAtMs = null
            )
        )

        val service = EvoKammerRunService(
            lifecycleManagerProvider = { _, _, _, _, _ ->
                throw IllegalStateException("LifecycleManager should not be constructed in this default-path test")
            },
            groupCoordinatorProvider = { groupIds, dimensions ->
                GroupCoordinator(groupIds, dimensions)
            },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = factory,
            metaPlannerRunner = MetaPlannerRunner()
        )

        service.reviewGraderCriteriaForRound(
            runId = runId,
            openRouterApiKey = "sk-test",
            graderGroupIds = listOf(graderGroupId),
            roundIndex = 0
        )

        val reviewed = graderDb.stagingDao().getById("grader-criteria-default-1")
        requireNotNull(reviewed)
        assertEquals("rejected", reviewed.status)
        assertEquals("critic-grader-0-round-0", reviewed.criticAgentId)
        assertTrue(
            reviewed.rejectionReason?.contains("missing_budget_signature", ignoreCase = true) == true,
            "Default CriticPipeline fallback should reject invalid grader criteria submissions"
        )
    }

    @Test
    fun `main critic pipeline path stores the deterministic reviewer identity`() = runTest {
        val dataRoot = createTempDirectory("critic-pipeline-main-reviewer-test").toFile()
        val factory = SubstrateDatabaseFactory(dataRoot)
        val runId = "run-main-reviewer"
        val groupId = "group-0"
        val db = factory.get(runId, groupId)

        db.stagingDao().insert(
            StagingSubmissionEntity(
                id = "main-path-doc",
                agentId = "agent-main-1",
                role = "Researcher",
                type = SubmissionType.DOC.name,
                content = "This submission exercises the main critic pipeline call site.",
                budgetSignature = "",
                status = "pending",
                generation = 1,
                groupId = groupId,
                runId = runId,
                submittedAtMs = 1_000L
            )
        )

        val service = EvoKammerRunService(
            lifecycleManagerProvider = { _, _, _, _, _ ->
                throw IllegalStateException("LifecycleManager should not be constructed in the main-path test")
            },
            groupCoordinatorProvider = { groupIds, dimensions ->
                GroupCoordinator(groupIds, dimensions)
            },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = factory,
            metaPlannerRunner = MetaPlannerRunner()
        )

        val runCriticPipelinesForRound = service::class.declaredFunctions.first { it.name == "runCriticPipelinesForRound" }.apply {
            isAccessible = true
        }

        val config = com.achulkov.evokammer.api.model.RunConfig(
            runId = runId,
            objective = "main path reviewer test",
            groupCount = 1,
            groupProfiles = listOf(com.achulkov.evokammer.core.model.GroupProfileType.Library),
            rounds = 1,
            roundDurationMinutes = 0,
            maxAgentsPerGroup = 1,
            scoringDimensions = listOf(
                ScoringDimension(
                    id = "accuracy",
                    name = "Accuracy",
                    description = "Reward evidence-based evaluation.",
                    weight = 1.0
                )
            ),
            openRouterApiKey = "sk-test",
            isTestRun = true
        )

        runCriticPipelinesForRound.callSuspend(service, config, listOf(groupId), 0)

        val reviewed = db.stagingDao().getById("main-path-doc")
        assertNotNull(reviewed)
        assertEquals("critic-group-0-round-0", reviewed.criticAgentId)
        assertEquals("rejected", reviewed.status)
    }

    @Test
    fun `round submission summaries retry once and capture staged marker before agent exit`() = runBlocking {
        val dataRoot = createTempDirectory("round-summary-retry-test").toFile()
        val factory = SubstrateDatabaseFactory(dataRoot)
        val runId = "run-round-summary-retry"
        val groupId = "group-0"
        val attemptCounter = AtomicInteger(0)

        val lifecycleManager = LifecycleManager(
            runId = runId,
            groupId = groupId,
            shuruVmManager = ShuruVmManager(),
            substrateDatabaseFactory = factory,
            agentJarPath = "/fake/agent.jar",
            openRouterApiKey = "sk-test",
            groupProfile = GroupProfile.default(GroupProfileType.Library),
            localAgentRunner = { bootContext: AgentBootContext, _ ->
                when (attemptCounter.incrementAndGet()) {
                    1 -> delay(50)
                    2 -> {
                        factory.get(runId, groupId).stagingDao().insert(
                            StagingSubmissionEntity(
                                id = "round-summary-2",
                                agentId = bootContext.agentId,
                                role = bootContext.role.name,
                                type = SubmissionType.PLAN_UPDATE.name,
                                content = """
                                    ROUND SUBMISSION SUMMARY
                                    - Retry succeeded.

                                    FINAL DELIVERABLE HTML
                                    <!doctype html>
                                    <html lang="en"><head><meta charset="utf-8"><title>Retry Summary</title></head><body><article><h1>Retry Summary</h1></article></body></html>
                                """.trimIndent(),
                                budgetSignature = "system:test",
                                status = "pending",
                                generation = 1,
                                groupId = groupId,
                                runId = runId,
                                submittedAtMs = System.currentTimeMillis(),
                                reviewedAtMs = null
                            )
                        )
                        delay(5_000)
                    }
                }
            }
        )

        val service = EvoKammerRunService(
            lifecycleManagerProvider = { _, _, _, _, _ ->
                throw IllegalStateException("LifecycleManager provider should not be used in this focused round-summary test")
            },
            groupCoordinatorProvider = { groupIds, dimensions ->
                GroupCoordinator(groupIds, dimensions)
            },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = factory,
            metaPlannerRunner = MetaPlannerRunner()
        )

        val liveManagersField = service::class.java.getDeclaredField("liveLifecycleManagers").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val liveManagers = liveManagersField.get(service) as MutableMap<String, LifecycleManager>
        liveManagers[groupId] = lifecycleManager

        val activeProfilesField = service::class.java.getDeclaredField("activeGroupProfiles").apply {
            isAccessible = true
        }
        activeProfilesField.set(service, listOf(GroupProfileType.Library))

        val spawnRoundSubmissionSummaries = service::class.declaredFunctions.first {
            it.name == "spawnRoundSubmissionSummaries"
        }.apply {
            isAccessible = true
        }

        val config = com.achulkov.evokammer.api.model.RunConfig(
            runId = runId,
            objective = "Create an HTML5 article for mindvortex.app",
            groupCount = 1,
            groupProfiles = listOf(GroupProfileType.Library),
            rounds = 1,
            roundDurationMinutes = 1,
            maxAgentsPerGroup = 1,
            scoringDimensions = listOf(
                ScoringDimension(
                    id = "accuracy",
                    name = "Accuracy",
                    description = "Reward evidence-based evaluation.",
                    weight = 1.0
                )
            ),
            openRouterApiKey = "sk-test",
            isTestRun = true
        )

        @Suppress("UNCHECKED_CAST")
        val summaries = withTimeout(4_000) {
            spawnRoundSubmissionSummaries.callSuspend(
                service,
                config,
                listOf(groupId),
                0
            ) as Map<String, String?>
        }

        assertEquals(2, attemptCounter.get())
        assertTrue(summaries[groupId].orEmpty().contains("ROUND SUBMISSION SUMMARY"))
        assertTrue(summaries[groupId].orEmpty().contains("FINAL DELIVERABLE HTML"))
        assertTrue(summaries[groupId].orEmpty().contains("Retry Summary"))
    }
}
