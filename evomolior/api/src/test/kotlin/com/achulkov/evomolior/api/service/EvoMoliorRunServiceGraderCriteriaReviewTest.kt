package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.api.initializeTestWorkerAtlas
import com.achulkov.evomolior.api.events.EvoMoliorEvent
import com.achulkov.evomolior.competition.coordinator.GroupCoordinator
import com.achulkov.evomolior.competition.grader.MetaJudge
import com.achulkov.evomolior.competition.grader.GraderGroupManager
import com.achulkov.evomolior.competition.grader.GradingSheet
import com.achulkov.evomolior.competition.grader.RubricDimension
import com.achulkov.evomolior.competition.scoring.BlindSubmissionGate
import com.achulkov.evomolior.competition.scoring.ScoringEngine
import com.achulkov.evomolior.core.contract.AgentBootContext
import com.achulkov.evomolior.core.contract.GraderAutomatedTestDraft
import com.achulkov.evomolior.core.contract.GraderFailureModeDraft
import com.achulkov.evomolior.core.contract.GraderMethodologyDraft
import com.achulkov.evomolior.core.contract.GraderRequirementDraft
import com.achulkov.evomolior.core.contract.GraderRubricDraft
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.grader.AdversarialCase
import com.achulkov.evomolior.core.grader.DisclosureSensitivity
import com.achulkov.evomolior.core.grader.FailureLevel
import com.achulkov.evomolior.core.grader.GraderAtlas
import com.achulkov.evomolior.core.grader.GraderAtlasNode
import com.achulkov.evomolior.core.grader.GraderNodeId
import com.achulkov.evomolior.core.grader.GraderNodeKind
import com.achulkov.evomolior.core.model.DimensionScore
import com.achulkov.evomolior.core.model.GroupProfile
import com.achulkov.evomolior.core.model.GroupProfileType
import com.achulkov.evomolior.core.model.ScoringDimension
import com.achulkov.evomolior.core.model.SubmissionType
import com.achulkov.evomolior.orchestration.lifecycle.LifecycleManager
import com.achulkov.evomolior.orchestration.metaplanner.MetaPlannerRunner
import com.achulkov.evomolior.sandbox.shuru.ShuruVmManager
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import com.achulkov.evomolior.substrate.grader.GraderAuthorityRepository
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.jvm.isAccessible

/**
 * Verifies that the live round loop waits for criteria development to finish before judging.
 */
class EvoMoliorRunServiceGraderCriteriaReviewTest {
    @Test
    fun `prior-round promoted methodology does not satisfy current-round repair`() = runTest {
        val factory = SubstrateDatabaseFactory(
            createTempDirectory("grader-current-round-repair-test").toFile()
        )
        val runId = "run-current-round-repair"
        val graderGroupId = "grader-0"
        val draftJson = Json.encodeToString(
            GraderMethodologyDraft.serializer(),
            validTypedDraft()
        )
        factory.getGrader(runId, graderGroupId).stagingDao().insert(
            StagingSubmissionEntity(
                id = "round-0-methodology",
                agentId = "round-0-builder",
                role = "GraderBuilder",
                type = SubmissionType.PLAN_UPDATE.name,
                content = draftJson,
                budgetSignature = "GRADING_SHEET_DRAFT:round-0-builder:1",
                status = "promoted",
                generation = 1,
                groupId = graderGroupId,
                runId = runId,
                submittedAtMs = 1L
            )
        )
        var criteriaCalls = 0
        val service = EvoMoliorRunService(
            lifecycleManagerProvider = { _, _, _, _, _ -> error("unused") },
            groupCoordinatorProvider = { groupIds, dimensions ->
                GroupCoordinator(groupIds, dimensions)
            },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = factory,
            graderCriteriaRunner = {
                    criteriaRunId, _, _, roundIndex, _, _, _ ->
                criteriaCalls += 1
                factory.getGrader(criteriaRunId, graderGroupId).stagingDao().insert(
                    StagingSubmissionEntity(
                        id = "round-1-methodology",
                        agentId = "round-1-builder",
                        role = "GraderBuilder",
                        type = SubmissionType.PLAN_UPDATE.name,
                        content = draftJson,
                        budgetSignature = "GRADING_SHEET_DRAFT:round-1-builder:2",
                        status = "promoted",
                        generation = roundIndex + 1,
                        groupId = graderGroupId,
                        runId = criteriaRunId,
                        submittedAtMs = 2L
                    )
                )
            }
        )
        val config = com.achulkov.evomolior.api.model.RunConfig(
            runId = runId,
            objective = "Evaluate shelter cooling evidence.",
            groupCount = 2,
            groupProfiles = listOf(GroupProfileType.Library, GroupProfileType.Factory),
            rounds = 2,
            roundDurationMinutes = 0,
            maxAgentsPerGroup = 1,
            scoringDimensions = listOf(
                ScoringDimension(
                    id = "accuracy",
                    name = "Accuracy",
                    description = "Evidence-based correctness.",
                    weight = 1.0
                )
            ),
            openRouterApiKey = "sk-test",
            isTestRun = true
        )

        val remaining = service.repairRejectedGraderMethodologies(
            config = config,
            graderGroupIds = listOf(graderGroupId),
            round = 1
        )

        assertTrue(remaining.isEmpty())
        assertEquals(1, criteriaCalls)
    }

    @Test
    fun `unreviewed pending methodology gets a review-only pass instead of re-authoring`() = runTest {
        val factory = SubstrateDatabaseFactory(
            createTempDirectory("grader-pending-draft-review-test").toFile()
        )
        val runId = "run-pending-draft-review"
        val graderGroupId = "grader-0"
        val draftJson = Json.encodeToString(GraderMethodologyDraft.serializer(), validTypedDraft())
        // The deadline-bounded Critic pass ended before reaching this draft: pending, not rejected.
        factory.getGrader(runId, graderGroupId).stagingDao().insert(
            StagingSubmissionEntity(
                id = "unreviewed-methodology",
                agentId = "round-0-builder",
                role = "GraderBuilder",
                type = SubmissionType.PLAN_UPDATE.name,
                content = draftJson,
                budgetSignature = "GRADING_SHEET_DRAFT:round-0-builder:1",
                generation = 1,
                groupId = graderGroupId,
                runId = runId,
                submittedAtMs = 1L
            )
        )
        var criteriaCalls = 0
        val reviewGroups = mutableListOf<List<String>>()
        val service = EvoMoliorRunService(
            lifecycleManagerProvider = { _, _, _, _, _ -> error("unused") },
            groupCoordinatorProvider = { groupIds, dimensions -> GroupCoordinator(groupIds, dimensions) },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = factory,
            graderCriteriaRunner = { _, _, _, _, _, _, _ -> criteriaCalls += 1 },
            graderCriteriaReviewRunner = { reviewRunId, _, graderGroupIds, _, _ ->
                reviewGroups += graderGroupIds
                factory.getGrader(reviewRunId, graderGroupId).stagingDao().updateReview(
                    id = "unreviewed-methodology",
                    status = "promoted",
                    criticId = "critic-1",
                    trustScore = 0.9,
                    reason = null,
                    reviewedAtMs = 100L
                )
            }
        )
        val config = com.achulkov.evomolior.api.model.RunConfig(
            runId = runId,
            objective = "Evaluate shelter cooling evidence.",
            groupCount = 2,
            groupProfiles = listOf(GroupProfileType.Library, GroupProfileType.Factory),
            rounds = 1,
            roundDurationMinutes = 0,
            maxAgentsPerGroup = 1,
            scoringDimensions = listOf(
                ScoringDimension(
                    id = "accuracy",
                    name = "Accuracy",
                    description = "Evidence-based correctness.",
                    weight = 1.0
                )
            ),
            openRouterApiKey = "sk-test",
            isTestRun = true
        )

        val remaining = service.repairRejectedGraderMethodologies(
            config = config,
            graderGroupIds = listOf(graderGroupId),
            round = 0
        )

        assertTrue(remaining.isEmpty())
        assertEquals(0, criteriaCalls, "a pending draft must not trigger re-authoring")
        assertEquals(listOf(listOf(graderGroupId)), reviewGroups)
    }

    @Test
    fun `post-Critic methodology repair repeats only missing groups and stops after promotion`() =
        runTest {
            val factory = SubstrateDatabaseFactory(
                createTempDirectory("grader-multi-cycle-repair-test").toFile()
            )
            val runId = "run-multi-cycle-repair"
            val graderGroupId = "grader-0"
            var criteriaCalls = 0
            var reviewCalls = 0
            val criteriaGroups = mutableListOf<List<String>>()
            val reviewGroups = mutableListOf<List<String>>()
            val draftJson = Json.encodeToString(
                GraderMethodologyDraft.serializer(),
                validTypedDraft()
            )
            val service = EvoMoliorRunService(
                lifecycleManagerProvider = { _, _, _, _, _ ->
                    throw IllegalStateException("LifecycleManager should not be constructed")
                },
                groupCoordinatorProvider = { groupIds, dimensions ->
                    GroupCoordinator(groupIds, dimensions)
                },
                graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
                substrateFactory = factory,
                metaPlannerRunner = MetaPlannerRunner(),
                graderCriteriaRunner = {
                        criteriaRunId, _, graderGroupIds, roundIndex, _, _, _ ->
                    criteriaCalls += 1
                    criteriaGroups += graderGroupIds
                    factory.getGrader(criteriaRunId, graderGroupId).stagingDao().insert(
                        StagingSubmissionEntity(
                            id = "repair-draft-$criteriaCalls",
                            agentId = "structured-builder-$criteriaCalls",
                            role = "GraderBuilder",
                            type = SubmissionType.PLAN_UPDATE.name,
                            content = draftJson,
                            budgetSignature =
                                "GRADING_SHEET_DRAFT:structured-builder-$criteriaCalls:1",
                            generation = roundIndex + 1,
                            groupId = graderGroupId,
                            runId = criteriaRunId,
                            submittedAtMs = criteriaCalls.toLong()
                        )
                    )
                },
                graderCriteriaReviewRunner = {
                        reviewRunId, _, graderGroupIds, _, _ ->
                    reviewCalls += 1
                    reviewGroups += graderGroupIds
                    val draft = factory.getGrader(reviewRunId, graderGroupId)
                        .stagingDao()
                        .getById("repair-draft-$reviewCalls")
                    requireNotNull(draft)
                    factory.getGrader(reviewRunId, graderGroupId).stagingDao().updateReview(
                        id = draft.id,
                        status = if (reviewCalls == 1) "rejected" else "promoted",
                        criticId = "critic-$reviewCalls",
                        trustScore = if (reviewCalls == 1) null else 0.9,
                        reason = if (reviewCalls == 1) "Methodology remains generic." else null,
                        reviewedAtMs = 100L + reviewCalls
                    )
                }
            )
            val config = com.achulkov.evomolior.api.model.RunConfig(
                runId = runId,
                objective = "Evaluate shelter cooling evidence.",
                groupCount = 2,
                groupProfiles = listOf(GroupProfileType.Library, GroupProfileType.Factory),
                rounds = 1,
                roundDurationMinutes = 0,
                maxAgentsPerGroup = 1,
                scoringDimensions = listOf(
                    ScoringDimension(
                        id = "accuracy",
                        name = "Accuracy",
                        description = "Evidence-based correctness.",
                        weight = 1.0
                    )
                ),
                openRouterApiKey = "sk-test",
                isTestRun = true
            )

            val remaining = service.repairRejectedGraderMethodologies(
                config = config,
                graderGroupIds = listOf(graderGroupId),
                round = 0
            )

            assertTrue(remaining.isEmpty())
            assertEquals(2, criteriaCalls)
            assertEquals(2, reviewCalls)
            assertEquals(listOf(listOf(graderGroupId), listOf(graderGroupId)), criteriaGroups)
            assertEquals(criteriaGroups, reviewGroups)
        }


    @Test
    fun `grader criteria phase completes before judging begins`() = runBlocking {
        val dataRoot = createTempDirectory("grader-criteria-round-flow-test").toFile()
        val factory = SubstrateDatabaseFactory(dataRoot)
        val runId = "run-criteria-flow"
        val order = mutableListOf<String>()
        val emittedEvents = mutableListOf<EvoMoliorEvent>()
        val graderGroupId = "grader-0"
        val plannedCriteriaId = "grader-criteria-1"
        val methodologyJson = Json.encodeToString(
            GraderMethodologyDraft.serializer(),
            validTypedDraft(),
        )

        val service = EvoMoliorRunService(
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
                        agentId = "criteria-builder",
                        role = "GraderBuilder",
                        type = SubmissionType.PLAN_UPDATE.name,
                        content = methodologyJson,
                        budgetSignature = "GRADING_SHEET_DRAFT:criteria-builder:1",
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
            graderCriteriaReviewRunner = {
                    reviewRunId, _, reviewerGroupIds, _, _ ->
                order += "criteria-review"
                factory.getGrader(reviewRunId, reviewerGroupIds.single())
                    .stagingDao()
                    .updateReview(
                        id = plannedCriteriaId,
                        status = "promoted",
                        criticId = "critic-grader-0-round-0",
                        trustScore = 0.9,
                        reason = null,
                        reviewedAtMs = 2_000L,
                    )
            },
            graderJudgingRunner = {
                    _, _, graderGroupIds, competingGroupIds, roundIndex, _, dimensions, manager, _, _, onProgress ->
                order += "judging-start"
                onProgress?.invoke("judging started")
                manager.registerSheet(testGradingSheet(runId, graderGroupIds.single(), roundIndex, dimensions))
                competingGroupIds.forEach { targetGroupId ->
                    manager.submitScores(
                        graderGroupId = graderGroupIds.single(),
                        targetGroupId = targetGroupId,
                        roundIndex = roundIndex,
                        scores = dimensions.map { dimension ->
                            DimensionScore(dimension.id, 80.0, "complete deterministic test coverage")
                        }
                    )
                }
                emptyMap()
            }
        )

        val config = com.achulkov.evomolior.api.model.RunConfig(
            runId = runId,
            objective = "criteria review order test",
            groupCount = 2,
            groupProfiles = listOf(
                com.achulkov.evomolior.core.model.GroupProfileType.Library,
                com.achulkov.evomolior.core.model.GroupProfileType.Factory
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

        prepareRunLoopAuthority(
            service = service,
            factory = factory,
            config = config,
            groupIds = listOf("group-0", "group-1"),
        )
        val eventCollector = launch(start = CoroutineStart.UNDISPATCHED) {
            service.events.collect(emittedEvents::add)
        }
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
            mutableSetOf<Int>(),
            0,
        )
        eventCollector.cancel()

        assertEquals(
            listOf("criteria-start", "criteria-done", "criteria-review", "judging-start"),
            order,
        )
        assertEquals(
            1,
            emittedEvents.count {
                it is EvoMoliorEvent.EngineProgress &&
                    it.phase == "grader_authoritative_verdict"
            },
        )
    }

    @Test
    fun `grader manager verdict is emitted exactly once for the authority-backed round`() = runBlocking {
        val dataRoot = createTempDirectory("grader-manager-verdict-twice-test").toFile()
        val factory = SubstrateDatabaseFactory(dataRoot)
        val runId = "run-grader-manager-verdict"
        val criteriaRounds = mutableListOf<Int>()
        val judgingRounds = mutableListOf<Int>()

        val service = EvoMoliorRunService(
            lifecycleManagerProvider = { _, _, _, _, _ ->
                throw IllegalStateException("LifecycleManager should not be constructed in the verdict-count test")
            },
            groupCoordinatorProvider = { groupIds, dimensions ->
                GroupCoordinator(groupIds, dimensions)
            },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = factory,
            metaPlannerRunner = MetaPlannerRunner(),
            graderCriteriaRunner = { callbackRunId, _, graderGroupIds, roundIndex, _, _, onProgress ->
                criteriaRounds += roundIndex
                factory.getGrader(callbackRunId, graderGroupIds.single()).stagingDao().insert(
                    StagingSubmissionEntity(
                        id = "criteria-$roundIndex",
                        agentId = "builder-$roundIndex",
                        role = "GraderBuilder",
                        type = SubmissionType.PLAN_UPDATE.name,
                        content = Json.encodeToString(
                            GraderMethodologyDraft.serializer(),
                            validTypedDraft(),
                        ),
                        budgetSignature = "GRADING_SHEET_DRAFT:builder-$roundIndex:1",
                        status = "pending",
                        generation = roundIndex + 1,
                        groupId = graderGroupIds.single(),
                        runId = callbackRunId,
                        submittedAtMs = 1_000L + roundIndex,
                    )
                )
                onProgress?.invoke("criteria staged for round $roundIndex")
            },
            graderCriteriaReviewRunner = {
                    reviewRunId, _, graderGroupIds, roundIndex, _ ->
                factory.getGrader(reviewRunId, graderGroupIds.single())
                    .stagingDao()
                    .updateReview(
                        id = "criteria-$roundIndex",
                        status = "promoted",
                        criticId = "critic-${graderGroupIds.single()}-round-$roundIndex",
                        trustScore = 0.9,
                        reason = null,
                        reviewedAtMs = 2_000L + roundIndex,
                    )
            },
            graderJudgingRunner = {
                    _, _, graderGroupIds, competingGroupIds, roundIndex, _, dimensions, manager, _, _, onProgress ->
                judgingRounds += roundIndex
                onProgress?.invoke("judging started for round $roundIndex")
                manager.registerSheet(testGradingSheet(runId, graderGroupIds.single(), roundIndex, dimensions))
                competingGroupIds.forEach { targetGroupId ->
                    manager.submitScores(
                        graderGroupId = graderGroupIds.single(),
                        targetGroupId = targetGroupId,
                        roundIndex = roundIndex,
                        scores = dimensions.map { dimension ->
                            DimensionScore(dimension.id, 80.0, "complete deterministic test coverage")
                        }
                    )
                }
                emptyMap()
            }
        )

        val blindTournamentService = BlindTournamentService(onFailureTelemetry = {})
        blindTournamentService.start(listOf("group-0", "group-1"))
        val blindTournamentField = service::class.java.getDeclaredField("activeBlindTournamentService").apply {
            isAccessible = true
        }
        blindTournamentField.set(service, blindTournamentService)

        val config = com.achulkov.evomolior.api.model.RunConfig(
            runId = runId,
            objective = "verdict count test",
            groupCount = 2,
            groupProfiles = listOf(
                com.achulkov.evomolior.core.model.GroupProfileType.Library,
                com.achulkov.evomolior.core.model.GroupProfileType.Factory
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

        prepareRunLoopAuthority(
            service = service,
            factory = factory,
            config = config,
            groupIds = listOf("group-0", "group-1"),
        )
        val emittedEvents = mutableListOf<EvoMoliorEvent>()
        val eventCollector = launch(start = CoroutineStart.UNDISPATCHED) {
            service.events.collect(emittedEvents::add)
        }
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
            mutableSetOf<Int>(),
            0,
        )
        eventCollector.cancel()

        assertEquals(listOf(0), criteriaRounds)
        assertEquals(listOf(0), judgingRounds)
        assertEquals(
            1,
            emittedEvents.count {
                it is EvoMoliorEvent.EngineProgress &&
                    it.phase == "grader_authoritative_verdict"
            },
        )

        blindTournamentService.stop()
    }

    private fun testGradingSheet(
        runId: String,
        graderGroupId: String,
        roundIndex: Int,
        dimensions: List<ScoringDimension>
    ): GradingSheet = GradingSheet(
        sheetId = "test-sheet-$roundIndex",
        runId = runId,
        roundIndex = roundIndex,
        graderGroupId = graderGroupId,
        rubricDimensions = dimensions.map { dimension ->
            RubricDimension(
                dimensionId = dimension.id,
                name = dimension.name,
                weight = dimension.weight,
                scoringGuidance = dimension.description
            )
        },
        automatedTests = emptyList(),
        failureModes = emptyList(),
        comparativePairs = emptyList(),
        sealedAt = 1L
    )

    private suspend fun prepareRunLoopAuthority(
        service: EvoMoliorRunService,
        factory: SubstrateDatabaseFactory,
        config: com.achulkov.evomolior.api.model.RunConfig,
        groupIds: List<String>,
    ) {
        val workerNodeId = NodeId("worker-node")
        val workerAtlas = initializeTestWorkerAtlas(
            factory = factory,
            runId = config.runId,
            nodeIds = setOf(workerNodeId),
            groupId = groupIds.first(),
        )
        val graderNodeId = GraderNodeId("grader-node")
        val graderNode = GraderAtlasNode(
            nodeId = graderNodeId,
            kind = GraderNodeKind.EVIDENCE_AUTHENTICITY,
            title = "Evidence authenticity",
            evaluationQuestion = "Is the material claim source-bound?",
            receiverObligationRefs = setOf("receiver:evidence"),
            mappedWorkerNodeIds = setOf(workerNodeId),
        ).withComputedHash()
        GraderAuthorityRepository(factory).appendAtlas(
            GraderAtlas(
                graderAtlasId = "grader-atlas-${config.runId}",
                runId = config.runId,
                version = workerAtlas.version,
                workerAtlasHash = workerAtlas.atlasHash,
                rootNodeIds = setOf(graderNodeId),
                nodes = mapOf(graderNodeId to graderNode),
            ).withComputedHash()
        )
        service::class.java.getDeclaredField("activeWorkerAtlasId").apply {
            isAccessible = true
            set(service, workerAtlas.atlasId)
        }
        service::class.java.getDeclaredField("activeGenerationCeiling").apply {
            isAccessible = true
            setInt(service, config.rounds)
        }
        service::class.java.getDeclaredField("activeGroupProfiles").apply {
            isAccessible = true
            set(service, config.groupProfiles)
        }
        val liveManagersField = service::class.java
            .getDeclaredField("liveLifecycleManagers")
            .apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val liveManagers = liveManagersField.get(service) as MutableMap<String, LifecycleManager>
        groupIds.forEachIndexed { index, groupId ->
            liveManagers[groupId] = LifecycleManager(
                runId = config.runId,
                groupId = groupId,
                shuruVmManager = ShuruVmManager(),
                substrateDatabaseFactory = factory,
                agentJarPath = "/fake/agent.jar",
                openRouterApiKey = config.openRouterApiKey,
                groupProfile = GroupProfile.default(config.groupProfiles[index]),
                localAgentRunner = { _, _ -> },
            )
        }
    }

    private fun validTypedDraft() = GraderMethodologyDraft(
        rubricDimensions = listOf(
            GraderRubricDraft("accuracy", "Verify calculations and source lineage.")
        ),
        automatedTests = listOf(
            GraderAutomatedTestDraft(
                testId = "evidence-test",
                description = "Requires inspectable shelter evidence.",
                expectedOutputPattern = "(?i)evidence",
                mappedRequirementIds = setOf("requirement-1"),
                inputSchemaHash = "shelter-snapshot-v1",
                expectedOutcomeHash = "evidence-present-v1",
                difficulty = 0.6
            )
        ),
        failureModes = listOf(
            GraderFailureModeDraft("unsupported-claim", "Shelter claim lacks evidence.")
        ),
        requirements = listOf(
            GraderRequirementDraft(
                requirementId = "requirement-1",
                graderAtlasNodeId =
                    com.achulkov.evomolior.core.grader.GraderNodeId("grader-node"),
                mappedWorkerNodeIds = setOf(NodeId("worker-node")),
                mappedReceiverObligationRefs = setOf("receiver:evidence"),
                criterion = "Every cooling claim has inspectable evidence.",
                observableEvidence = "Promoted claim-to-source lineage.",
                passThreshold = "All material claims are source-bound.",
                failureLevels = listOf(
                    FailureLevel(1, "One material claim is unsupported.", 0.5)
                ),
                adversarialCases = listOf(
                    AdversarialCase(
                        "fabricated-source",
                        "Insert a plausible unsupported cooling claim.",
                        "Flag the unsupported claim.",
                        1.0
                    )
                ),
                executableTestIds = listOf("evidence-test"),
                disclosureSensitivity = DisclosureSensitivity.GRADER_INTERNAL,
                rationale = "Municipal receivers require auditable evidence.",
                noveltyFingerprint = "shelter-evidence-v1",
                maximumEvaluationCost = 2.0
            )
        )
    )

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
                generation = 0,
                groupId = graderGroupId,
                runId = runId,
                submittedAtMs = 1_000L,
                reviewedAtMs = null
            )
        )

        var reviewerInvoked = false
        val service = EvoMoliorRunService(
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
    fun `default grader criteria review path rejects self-authored staged criteria`() = runTest {
        val dataRoot = createTempDirectory("grader-criteria-default-path-test").toFile()
        val factory = SubstrateDatabaseFactory(dataRoot)
        val runId = "run-criteria-default"
        val graderGroupId = "grader-0"
        val graderDb = factory.getGrader(runId, graderGroupId)

        graderDb.stagingDao().insert(
            StagingSubmissionEntity(
                id = "grader-criteria-default-1",
                agentId = "critic-grader-0-round-0",
                role = "GraderResearcher",
                type = SubmissionType.PLAN_UPDATE.name,
                content = "draft rubric that should be rejected by CriticPipeline",
                budgetSignature = "",
                status = "pending",
                generation = 0,
                groupId = graderGroupId,
                runId = runId,
                submittedAtMs = 1_000L,
                reviewedAtMs = null
            )
        )

        val service = EvoMoliorRunService(
            lifecycleManagerProvider = { _, _, _, _, _ ->
                throw IllegalStateException("LifecycleManager should not be constructed in this default-path test")
            },
            groupCoordinatorProvider = { groupIds, dimensions ->
                GroupCoordinator(groupIds, dimensions)
            },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = factory,
            metaPlannerRunner = MetaPlannerRunner(),
        )
        service::class.java.getDeclaredField("activeRunId").apply {
            isAccessible = true
            set(service, runId)
        }

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
            reviewed.rejectionReason?.isNotBlank() == true,
            "Default CriticPipeline must reject criteria authored by its reviewer identity"
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

        val service = EvoMoliorRunService(
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

        val config = com.achulkov.evomolior.api.model.RunConfig(
            runId = runId,
            objective = "main path reviewer test",
            groupCount = 1,
            groupProfiles = listOf(com.achulkov.evomolior.core.model.GroupProfileType.Library),
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

        val service = EvoMoliorRunService(
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

        val config = com.achulkov.evomolior.api.model.RunConfig(
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
            ) as Map<String, RoundSubmissionSummaryCandidate?>
        }

        assertEquals(2, attemptCounter.get())
        val summary = assertNotNull(summaries[groupId]).content
        assertTrue(summary.contains("ROUND SUBMISSION SUMMARY"))
        assertTrue(summary.contains("FINAL DELIVERABLE HTML"))
        assertTrue(summary.contains("Retry Summary"))
    }

    @Test
    fun `round summary promotion is bound to the exact spawned submission identity`() = runBlocking {
        val factory = SubstrateDatabaseFactory(
            createTempDirectory("round-summary-authority-test").toFile(),
        )
        val runId = "run-round-summary-authority"
        val groupId = "group-0"
        val content = "ROUND SUBMISSION SUMMARY\n- Exact authority."
        val candidate = StagingSubmissionEntity(
            id = "summary-candidate",
            agentId = "summary-agent",
            role = com.achulkov.evomolior.core.model.Role.Archivist.name,
            type = SubmissionType.PLAN_UPDATE.name,
            content = content,
            budgetSignature = "summary-candidate",
            status = "pending",
            generation = 1,
            groupId = groupId,
            runId = runId,
            submittedAtMs = 10L,
        )
        val clone = candidate.copy(
            id = "summary-clone",
            agentId = "other-archivist",
            status = "promoted",
            criticAgentId = "critic-clone",
            reviewedAtMs = 12L,
            promotedArtifactRef = "submission:summary-clone",
            promotedArtifactHash = AtlasHashing.hashText(content),
            submittedAtMs = 11L,
        )
        val staging = factory.get(runId, groupId).stagingDao()
        staging.insert(candidate)
        staging.insert(clone)
        val service = EvoMoliorRunService(
            lifecycleManagerProvider = { _, _, _, _, _ ->
                error("No lifecycle manager should be created in this authority test")
            },
            groupCoordinatorProvider = { groupIds, dimensions ->
                GroupCoordinator(groupIds, dimensions)
            },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = factory,
            metaPlannerRunner = MetaPlannerRunner(),
        )
        val resolvePromoted = service::class.declaredFunctions.first {
            it.name == "promotedRoundSubmissionSummaries"
        }.apply {
            isAccessible = true
        }
        val authority = mapOf(
            groupId to RoundSubmissionSummaryCandidate(
                submissionId = candidate.id,
                agentId = candidate.agentId,
                content = candidate.content,
            ),
        )

        @Suppress("UNCHECKED_CAST")
        val whilePending = resolvePromoted.callSuspend(
            service,
            runId,
            0,
            authority,
        ) as Map<String, String?>
        assertNull(whilePending[groupId], "A promoted same-content clone must not substitute.")

        staging.updateReview(
            id = candidate.id,
            status = "promoted",
            criticId = "critic-candidate",
            trustScore = 1.0,
            reason = null,
            reviewedAtMs = 13L,
        )
        assertEquals(
            1,
            staging.recordPromotionArtifact(
                id = candidate.id,
                artifactRef = "submission:${candidate.id}",
                artifactHash = AtlasHashing.hashText(content),
            ),
        )
        @Suppress("UNCHECKED_CAST")
        val promoted = resolvePromoted.callSuspend(
            service,
            runId,
            0,
            authority,
        ) as Map<String, String?>
        assertEquals(content, promoted[groupId])
    }
}
