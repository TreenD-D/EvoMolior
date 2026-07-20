package com.achulkov.evomolior.api.acceptance

import com.achulkov.evomolior.api.grader.GraderRunner
import com.achulkov.evomolior.api.service.EvoMoliorRunService
import com.achulkov.evomolior.competition.coordinator.GroupCoordinator
import com.achulkov.evomolior.competition.disclosure.DisclosureStage
import com.achulkov.evomolior.competition.disclosure.ProgressiveDisclosureEngine
import com.achulkov.evomolior.competition.grader.AutomatedTest
import com.achulkov.evomolior.competition.grader.FailureMode
import com.achulkov.evomolior.competition.grader.GraderGroupManager
import com.achulkov.evomolior.competition.grader.GradingSheet
import com.achulkov.evomolior.competition.grader.MetaJudge
import com.achulkov.evomolior.competition.grader.RubricDimension
import com.achulkov.evomolior.competition.scoring.FailureTelemetry
import com.achulkov.evomolior.core.contract.GraderAutomatedTestDraft
import com.achulkov.evomolior.core.contract.GraderFailureModeDraft
import com.achulkov.evomolior.core.contract.GraderRubricDraft
import com.achulkov.evomolior.core.contract.GroupCheckinResult
import com.achulkov.evomolior.core.model.GradingResult
import com.achulkov.evomolior.core.model.Role
import com.achulkov.evomolior.core.model.ScoringDimension
import com.achulkov.evomolior.orchestration.metaplanner.MetaPlannerRunner
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.jvm.isAccessible
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Phase 7 acceptance: check-in failure must improve the next grader methodology and preserve
 * per-group disclosure gating.
 */
class Phase7ProgressiveCurriculumAcceptanceTest {

    @Test
    fun `authority failure leaves disclosure stage unchanged`() = runTest {
        val engine = ProgressiveDisclosureEngine(
            stages = listOf(
                DisclosureStage(0, "Initial", "initial", "goal"),
                DisclosureStage(1, "Hidden", "hidden", "goal")
            ),
            stageAuthority = com.achulkov.evomolior.competition.disclosure.DisclosureStageAuthority {
                error("Atlas transaction rejected")
            }
        )
        engine.registerGroup("group-a")

        assertFailsWith<IllegalStateException> {
            engine.onCheckinComplete(
                "group-a",
                GroupCheckinResult(
                    groupId = "group-a",
                    overallScore = 0.9,
                    passed = true,
                    dimensionScores = emptyMap(),
                    testPassRate = 1.0,
                    failedTestCount = 0,
                    gradingSheetApplied = "test",
                    stageAdvanced = false
                )
            )
        }
        assertEquals(0, engine.currentStage("group-a").stageIndex)
    }
    @Test
    fun `two checkins feed failure telemetry into a measurably improved grader sheet`() = runBlocking {
        val root = createTempDirectory("phase-7-progressive-curriculum-").toFile()
        val substrateFactory = SubstrateDatabaseFactory(root)
        val runId = "phase-7-run"
        val graderGroupId = "grader-0"
        val groupIds = listOf("group-0", "group-1")
        val dimension = ScoringDimension("accuracy", "Accuracy", "Evidence-grounded accuracy.", 1.0)
        val graderDb = substrateFactory.getGrader(runId, graderGroupId)
        val disclosure = ProgressiveDisclosureEngine(
            stages = (0..2).map { index ->
                DisclosureStage(
                    stageIndex = index,
                    title = "Stage $index",
                    constraintFramedContent = "Constraints $index",
                    goalFramedContent = "Goal $index"
                )
            },
            stageAuthority = com.achulkov.evomolior.competition.disclosure.LocalProjectionDisclosureStageAuthority
        ).also { engine -> groupIds.forEach(engine::registerGroup) }
        val service = EvoMoliorRunService(
            lifecycleManagerProvider = { _, _, _, _, _ ->
                error("LifecycleManager is outside this acceptance boundary")
            },
            groupCoordinatorProvider = { ids, dimensions -> GroupCoordinator(ids, dimensions) },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = substrateFactory,
            metaPlannerRunner = MetaPlannerRunner(),
            graderCriteriaReviewRunner = { reviewRunId, _, reviewerIds, roundIndex, _ ->
                reviewerIds.forEach { reviewerId ->
                    substrateFactory.getGrader(reviewRunId, reviewerId).stagingDao()
                        .getAll(reviewRunId, reviewerId)
                        .filter { it.generation == roundIndex + 1 && it.status == "pending" }
                        .forEach { entry ->
                            substrateFactory.getGrader(reviewRunId, reviewerId).stagingDao().updateReview(
                                entry.id,
                                "promoted",
                                "phase-7-critic",
                                0.9,
                                null,
                                System.currentTimeMillis()
                            )
                        }
                }
            }
        )
        service.javaClass.getDeclaredField("activeDisclosureEngine")
            .apply { isAccessible = true }
            .set(service, disclosure)

        val criteriaPrompts = mutableMapOf<Int, String>()
        val runner = GraderRunner(
            openRouterApiKey = "sk-test",
            substrateFactory = substrateFactory,
            localAgentRunner = { context ->
                when (context.role) {
                    Role.GraderResearcher -> {
                        criteriaPrompts[context.roundIndex] = context.systemPrompt
                    }
                    Role.GraderBuilder -> {
                        criteriaPrompts[context.roundIndex] = context.systemPrompt
                        publishSheet(context, improved = context.roundIndex == 1)
                    }
                    Role.GraderCritic -> {
                        val score = if (context.targetGroupId == "group-0") 85.0 else 40.0
                        submitScore(context, score)
                    }
                    else -> Unit
                }
                ""
            },
            graderAgentTimeoutMs = 2_000L
        )

        val roundZero = runRound(
            runner, service, runId, graderGroupId, groupIds, dimension, roundIndex = 0
        )
        installAuthoritativeResult(service, roundZero, 0)
        processCheckins(service, runId, groupIds, 0)

        assertEquals(1, disclosure.currentStage("group-0").stageIndex)
        assertEquals(0, disclosure.currentStage("group-1").stageIndex)
        assertTrue(disclosure.leakMultiplierFor("group-1") > 1.0)

        service.routeFailureTelemetry(
            runId,
            listOf(graderGroupId),
            FailureTelemetry(
                groupId = "group-1",
                roundIndex = 0,
                failureModeIds = listOf("missing-reproducible-evidence"),
                automatedTestsFailed = 1,
                gradingSheetIds = listOf("grader-authored-grader-0-r1"),
                rawScore = 40.0,
                weightedScore = 40.0
            )
        )

        val roundOne = runRound(
            runner, service, runId, graderGroupId, groupIds, dimension, roundIndex = 1
        )
        installAuthoritativeResult(service, roundOne, 1)
        processCheckins(service, runId, groupIds, 1)

        val generationTwoPrompt = criteriaPrompts.getValue(1)
        assertContains(generationTwoPrompt, "=== RECENT FAILURE TELEMETRY ===")
        assertContains(generationTwoPrompt, "missing-reproducible-evidence")
        assertTrue(!generationTwoPrompt.contains("group-1"), "Telemetry must remain anonymized")

        val drafts = graderDb.stagingDao().getAll(runId, graderGroupId)
            .filter { it.budgetSignature.startsWith("GRADING_SHEET_DRAFT:") && it.status == "promoted" }
            .associateBy { it.generation }
        val first = assertNotNull(drafts[1])
        val second = assertNotNull(drafts[2])
        val firstQuality = quality(first.content, runId, graderGroupId, 0, dimension)
        val secondQuality = quality(second.content, runId, graderGroupId, 1, dimension)
        assertTrue(
            secondQuality > firstQuality,
            "Telemetry-responsive sheet quality must improve: generation1=$firstQuality generation2=$secondQuality"
        )
        assertEquals(2, disclosure.currentStage("group-0").stageIndex)
        assertEquals(0, disclosure.currentStage("group-1").stageIndex)
    }

    private suspend fun runRound(
        runner: GraderRunner,
        service: EvoMoliorRunService,
        runId: String,
        graderGroupId: String,
        groupIds: List<String>,
        dimension: ScoringDimension,
        roundIndex: Int
    ): GradingResult {
        runner.runCriteriaPhase(runId, listOf(graderGroupId), roundIndex, "Evaluate evidence.", listOf(dimension))
        service.reviewGraderCriteriaForRound(runId, "sk-test", listOf(graderGroupId), roundIndex)
        val manager = GraderGroupManager(listOf(dimension))
        runner.runJudgingPhase(
            runId, listOf(graderGroupId), groupIds, roundIndex, "Evaluate evidence.", listOf(dimension), manager
        )
        assertTrue(manager.hasCompleteRoundScores(groupIds, roundIndex))
        return manager.computeRoundResult(groupIds, roundIndex)
    }

    private suspend fun publishSheet(context: Any, improved: Boolean) {
        val tool = tools(context).single { it.javaClass.simpleName == "PublishGradingSheetTool" }
        val argsClass = tool.javaClass.declaredClasses.single { it.simpleName == "Args" }
        val tests = if (improved) {
            listOf(
                GraderAutomatedTestDraft("evidence", "Require evidence.", "(?i)evidence"),
                GraderAutomatedTestDraft("reproduction", "Require reproduction steps.", "(?i)reproduc"),
                GraderAutomatedTestDraft("source", "Require source trace.", "(?i)source")
            )
        } else {
            listOf(GraderAutomatedTestDraft("evidence", "Require evidence.", "(?i)evidence"))
        }
        val failures = if (improved) {
            listOf(
                GraderFailureModeDraft(
                    "missing-reproducible-evidence",
                    "Failure telemetry identified missing reproducible evidence."
                ),
                GraderFailureModeDraft("missing-source-trace", "Claims lack a source trace.")
            )
        } else emptyList()
        val args = argsClass.getDeclaredConstructor(List::class.java, List::class.java, List::class.java)
            .newInstance(
                listOf(GraderRubricDraft("accuracy", "Require reproducible evidence.")),
                tests,
                failures
            )
        tool::class.members.first { it.name == "execute" }.callSuspend(tool, args)
    }

    private suspend fun submitScore(context: Any, score: Double) {
        val targetGroupId = context.javaClass.getMethod("getTargetGroupId").invoke(context) as String
        val tool = tools(context).single { it.javaClass.simpleName == "SubmitGradingScoreTool" }
        val argsClass = tool.javaClass.declaredClasses.single { it.simpleName == "Args" }
        val args = argsClass.getDeclaredConstructor(
            String::class.java,
            String::class.java,
            Double::class.javaPrimitiveType,
            String::class.java
        ).newInstance(targetGroupId, "accuracy", score, "Phase 7 deterministic evidence.")
        tool::class.members.first { it.name == "execute" }.callSuspend(tool, args)
    }

    private fun tools(context: Any): List<Any> {
        val agent = context.javaClass.getMethod("getAgent").invoke(context)
        val registry = agent.javaClass.getMethod("getToolRegistry").invoke(agent)
        @Suppress("UNCHECKED_CAST")
        return registry.javaClass.methods.first { it.name == "getTools" }.invoke(registry) as List<Any>
    }

    private fun installAuthoritativeResult(service: EvoMoliorRunService, result: GradingResult, round: Int) {
        @Suppress("UNCHECKED_CAST")
        val results = service.javaClass.getDeclaredField("authoritativeGraderRoundResults")
            .apply { isAccessible = true }
            .get(service) as MutableMap<Int, GradingResult>
        results[round] = result
    }

    private suspend fun processCheckins(
        service: EvoMoliorRunService,
        runId: String,
        groupIds: List<String>,
        round: Int
    ) {
        service::class.declaredFunctions.first { it.name == "processDisclosureCheckIns" }
            .apply { isAccessible = true }
            .callSuspend(service, runId, groupIds, round)
    }

    private fun quality(
        draftJson: String,
        runId: String,
        graderGroupId: String,
        roundIndex: Int,
        dimension: ScoringDimension
    ): Double {
        val draft = kotlinx.serialization.json.Json.decodeFromString(
            com.achulkov.evomolior.core.contract.GraderMethodologyDraft.serializer(),
            draftJson
        )
        val sheet = GradingSheet(
            "quality-$roundIndex",
            runId,
            roundIndex,
            graderGroupId,
            draft.rubricDimensions.map {
                RubricDimension(it.dimensionId, dimension.name, dimension.weight, it.scoringGuidance)
            },
            draft.automatedTests.map {
                AutomatedTest(
                    it.testId, it.description, it.expectedOutputPattern, it.penaltyOnFailure, it.isMandatory
                )
            },
            draft.failureModes.map { FailureMode(it.modeId, it.description, it.penaltyWeight) },
            emptyList(),
            sealedAt = 1L
        )
        return assertNotNull(MetaJudge().assessSheetQuality(sheet).qualityScore)
    }
}
