package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.api.model.RunConfig
import com.achulkov.evomolior.competition.coordinator.GroupCoordinator
import com.achulkov.evomolior.competition.dispatch.DispatchMessage
import com.achulkov.evomolior.competition.grader.GraderGroupManager
import com.achulkov.evomolior.competition.leaks.LeakOracleConfig
import com.achulkov.evomolior.core.contract.CriticOutcome
import com.achulkov.evomolior.core.contract.CriticReview
import com.achulkov.evomolior.core.model.GroupProfileType
import com.achulkov.evomolior.core.model.ScoringDimension
import com.achulkov.evomolior.orchestration.lifecycle.CriticPipeline
import com.achulkov.evomolior.orchestration.metaplanner.MetaPlannerRunner
import com.achulkov.evomolior.orchestration.watchdog.WatchdogSignal
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.db.entity.EventTypes
import com.achulkov.evomolior.substrate.db.entity.KnowledgeBaseEntryEntity
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.reflect.jvm.isAccessible
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Exercises the production boundaries joining Critic promotion, dispatch lag, trust persistence,
 * Watchdog leak delivery, and recipient canonical substrate state.
 */
class EvoMoliorRunServiceCompetitionMechanicsTest {

    @Test
    fun `automatic leak cadence starts after source-producing generation`() {
        val service = fixture(dispatchLagGenerations = 0).service

        assertFalse(service.shouldRequestAutomaticLeak(false, 0, 1, 2))
        assertTrue(service.shouldRequestAutomaticLeak(false, 1, 1, 2))
        assertFalse(service.shouldRequestAutomaticLeak(false, 1, 2, 2))
        assertTrue(service.shouldRequestAutomaticLeak(false, 2, 2, 2))
        assertFalse(service.shouldRequestAutomaticLeak(true, 2, 1, 2))
        assertFalse(service.shouldRequestAutomaticLeak(false, 2, 1, 1))
    }

    @Test
    fun `disclosure multiplier advances recipient leak cadence without enabling test runs`() {
        val service = fixture(dispatchLagGenerations = 0).service
        val groupIds = listOf("group-0", "group-1", "group-2")

        assertEquals(
            listOf("group-1"),
            service.automaticLeakTargets(
                isTestRun = false,
                roundIndex = 5,
                intervalGenerations = 20,
                groupIds = groupIds,
                leakMultipliers = mapOf("group-0" to 1.0, "group-1" to 4.0, "group-2" to 2.0)
            )
        )
        assertEquals(
            emptyList(),
            service.automaticLeakTargets(
                isTestRun = true,
                roundIndex = 5,
                intervalGenerations = 20,
                groupIds = groupIds,
                leakMultipliers = mapOf("group-1" to 8.0)
            )
        )
    }

    @Test
    fun `critic promoted dispatch is lagged then persisted with target trust`() = runTest {
        val fixture = fixture(dispatchLagGenerations = 1)
        val sourceDb = fixture.substrateFactory.get(fixture.runId, fixture.sourceGroupId)
        sourceDb.stagingDao().insert(
            StagingSubmissionEntity(
                id = "dispatch-source-1",
                agentId = "connector-1",
                role = "Connector",
                type = "DISPATCH",
                content = "Validated cross-group market signal.",
                budgetSignature = "sig:dispatch",
                generation = 0,
                groupId = fixture.sourceGroupId,
                runId = fixture.runId,
                submittedAtMs = 1_000L
            )
        )

        CriticPipeline(
            db = sourceDb,
            runId = fixture.runId,
            groupId = fixture.sourceGroupId,
            generation = 0,
            openRouterApiKey = "sk-test",
            criticModel = "test-model",
            criticReviewRunner = { submission, _, _, criticId ->
                CriticReview(
                    submissionId = submission.id,
                    outcome = CriticOutcome.PROMOTE,
                    criticAgentId = criticId,
                    initialTrustScore = 0.84
                )
            },
            toolsMountHostPath = fixture.dataRoot.absolutePath,
            onSubmissionPromoted = { submission, trustScore ->
                fixture.service.routePromotedDispatch(
                    runId = fixture.runId,
                    groupId = fixture.sourceGroupId,
                    submission = submission,
                    trustScore = trustScore,
                    coordinator = fixture.coordinator
                )
            }
        ).processPendingSubmissions()

        val targetDb = fixture.substrateFactory.get(fixture.runId, fixture.targetGroupId)
        assertTrue(targetDb.knowledgeBaseDao().queryByDomain(
            fixture.runId,
            fixture.targetGroupId,
            "dispatch_inbox",
            10
        ).isEmpty())
        assertEquals(0, fixture.coordinator.flushPendingDispatches(0))
        assertEquals(1, fixture.coordinator.flushPendingDispatches(1))

        val inboxEntry = targetDb.knowledgeBaseDao().queryByDomain(
            fixture.runId,
            fixture.targetGroupId,
            "dispatch_inbox",
            10
        ).single()
        assertTrue(inboxEntry.content.contains("Validated cross-group market signal."))
        assertTrue(inboxEntry.content.contains("Source group: ${fixture.sourceGroupId}"))
        assertEquals(
            0.5,
            targetDb.trustScoreDao().getScore(
                fixture.runId,
                fixture.targetGroupId,
                fixture.sourceGroupId,
                "dispatch"
            )
        )
        assertEquals(
            1,
            sourceDb.eventLogDao().getLatestByType(
                fixture.runId,
                fixture.sourceGroupId,
                EventTypes.DISPATCH_SENT,
                10
            ).size
        )
        assertEquals(
            1,
            targetDb.eventLogDao().getLatestByType(
                fixture.runId,
                fixture.targetGroupId,
                EventTypes.DISPATCH_DELIVERED,
                10
            ).size
        )
    }

    @Test
    fun `watchdog deliver leak signal writes redacted recipient evidence once`() = runTest {
        val fixture = fixture(dispatchLagGenerations = 0)
        val sourceDb = fixture.substrateFactory.get(fixture.runId, fixture.sourceGroupId)
        sourceDb.knowledgeBaseDao().insert(
            KnowledgeBaseEntryEntity(
                id = "source-kb-1",
                content = "Conceptual insight suitable for bounded disclosure.",
                sourceAgentId = "researcher-1",
                generation = 0,
                domain = "general",
                groupId = fixture.sourceGroupId,
                runId = fixture.runId,
                createdAtMs = 1_000L
            )
        )
        setActiveCoordinator(fixture.service, fixture.coordinator)

        fixture.service.handleWatchdogSignal(
            config = fixture.runConfig,
            signal = WatchdogSignal.DeliverLeak(
                targetGroupIds = listOf(fixture.targetGroupId),
                roundIndex = 0,
                domain = "general"
            ),
            groupIds = listOf(fixture.sourceGroupId, fixture.targetGroupId)
        )

        val targetDb = fixture.substrateFactory.get(fixture.runId, fixture.targetGroupId)
        val leakEntry = targetDb.knowledgeBaseDao().queryByDomain(
            fixture.runId,
            fixture.targetGroupId,
            "intelligence_leaks",
            10
        ).single()
        assertEquals("watchdog-leak-oracle", leakEntry.sourceAgentId)
        assertFalse(leakEntry.content.contains(fixture.sourceGroupId))
        assertEquals("Privacy-safe conceptual summary.", leakEntry.content)
        assertFalse(leakEntry.content.contains("Conceptual insight"))
        assertEquals(
            1,
            targetDb.eventLogDao().getLatestByType(
                fixture.runId,
                fixture.targetGroupId,
                EventTypes.LEAK_DELIVERED,
                10
            ).size
        )
        assertEquals(
            null,
            targetDb.trustScoreDao().getScore(
                fixture.runId,
                fixture.targetGroupId,
                fixture.sourceGroupId,
                "dispatch"
            ),
            "Anonymous leaks must not create formal-dispatch trust state"
        )
    }

    @Test
    fun `watchdog leak rate signal raises ceiling without delivering content`() = runTest {
        val fixture = fixture(dispatchLagGenerations = 0)
        setActiveCoordinator(fixture.service, fixture.coordinator)
        val initialCeiling = fixture.coordinator.leakOracle.ceilingFor(fixture.sourceGroupId)

        fixture.service.handleWatchdogSignal(
            config = fixture.runConfig,
            signal = WatchdogSignal.IncreaseLeakRate(
                targetGroupIds = listOf(fixture.targetGroupId),
                multiplier = 1.5
            ),
            groupIds = listOf(fixture.sourceGroupId, fixture.targetGroupId)
        )

        assertEquals(
            kotlin.math.ceil(initialCeiling * 1.5).toInt(),
            fixture.coordinator.leakOracle.ceilingFor(fixture.sourceGroupId)
        )
        assertTrue(
            fixture.substrateFactory.get(fixture.runId, fixture.targetGroupId)
                .knowledgeBaseDao()
                .queryByDomain(fixture.runId, fixture.targetGroupId, "intelligence_leaks", 10)
                .isEmpty(),
            "Rate adjustment and leak delivery are separate mechanics"
        )
    }

    private fun fixture(dispatchLagGenerations: Int): Fixture {
        val dataRoot = createTempDirectory("competition-mechanics-").toFile()
        val substrateFactory = SubstrateDatabaseFactory(dataRoot)
        val runId = "run-competition-mechanics"
        val sourceGroupId = "group-0"
        val targetGroupId = "group-1"
        val dimensions = listOf(
            ScoringDimension(
                id = "accuracy",
                name = "Accuracy",
                description = "Accuracy",
                weight = 1.0
            )
        )
        val service = EvoMoliorRunService(
            lifecycleManagerProvider = { _, _, _, _, _ ->
                error("LifecycleManager should not be constructed in mechanics boundary tests")
            },
            groupCoordinatorProvider = { groupIds, scoringDimensions ->
                GroupCoordinator(groupIds, scoringDimensions)
            },
            graderGroupManagerProvider = { GraderGroupManager(it) },
            substrateFactory = substrateFactory,
            metaPlannerRunner = MetaPlannerRunner()
        )
        val coordinator = GroupCoordinator(
            groupIds = listOf(sourceGroupId, targetGroupId),
            dimensions = dimensions,
            leakConfig = LeakOracleConfig(
                maxLeaksPerGroupPerRound = 2,
                maxMigrationPerPairPerRound = 2,
                corruptionProbability = 0.0,
                allowedDomains = setOf("general")
            ),
            privacyTransformerOverride = { packet ->
                packet.copy(content = "Privacy-safe conceptual summary.")
            },
            dispatchLagGenerations = dispatchLagGenerations
        )
        coordinator.configureDispatchTrustPersistence(
            runId = runId,
            trustScoreDaosByGroup = mapOf(
                sourceGroupId to substrateFactory.get(runId, sourceGroupId).trustScoreDao(),
                targetGroupId to substrateFactory.get(runId, targetGroupId).trustScoreDao()
            )
        )
        coordinator.configureDeliveryConsumer { source, target, message, trust ->
            service.persistCrossGroupDelivery(runId, source, target, message, trust)
        }
        return Fixture(
            dataRoot = dataRoot,
            substrateFactory = substrateFactory,
            service = service,
            coordinator = coordinator,
            runId = runId,
            sourceGroupId = sourceGroupId,
            targetGroupId = targetGroupId,
            runConfig = RunConfig(
                runId = runId,
                objective = "Exercise competition mechanics.",
                groupCount = 2,
                groupProfiles = listOf(GroupProfileType.Library, GroupProfileType.Factory),
                rounds = 2,
                roundDurationMinutes = 1,
                maxAgentsPerGroup = 1,
                scoringDimensions = dimensions,
                openRouterApiKey = "sk-test"
            )
        )
    }

    private fun setActiveCoordinator(service: EvoMoliorRunService, coordinator: GroupCoordinator) {
        val field = service.javaClass.getDeclaredField("activeCoordinator").apply {
            isAccessible = true
        }
        field.set(service, coordinator)
    }

    private data class Fixture(
        val dataRoot: java.io.File,
        val substrateFactory: SubstrateDatabaseFactory,
        val service: EvoMoliorRunService,
        val coordinator: GroupCoordinator,
        val runId: String,
        val sourceGroupId: String,
        val targetGroupId: String,
        val runConfig: RunConfig
    )
}
