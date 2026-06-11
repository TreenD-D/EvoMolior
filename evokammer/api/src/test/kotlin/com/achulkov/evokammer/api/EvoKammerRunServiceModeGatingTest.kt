package com.achulkov.evokammer.api

import com.achulkov.evokammer.core.budget.BudgetLedger
import com.achulkov.evokammer.core.contract.AgentBootContext
import com.achulkov.evokammer.core.model.GroupProfile
import com.achulkov.evokammer.core.model.Role
import com.achulkov.evokammer.core.model.ScoringDimension
import com.achulkov.evokammer.orchestration.metaplanner.MetaPlannerRunner
import com.achulkov.evokammer.orchestration.lifecycle.LifecycleManager
import com.achulkov.evokammer.orchestration.watchdog.Watchdog
import com.achulkov.evokammer.orchestration.watchdog.WatchdogSignal
import com.achulkov.evokammer.orchestration.watchdog.WatchdogSeverity
import com.achulkov.evokammer.api.service.EvoKammerRunService
import com.achulkov.evokammer.api.service.BlindTournamentService
import com.achulkov.evokammer.api.events.EvoKammerEvent
import com.achulkov.evokammer.api.service.SealedTestSuiteEvaluator
import com.achulkov.evokammer.competition.coordinator.GroupCoordinator
import com.achulkov.evokammer.competition.grader.FailureMode
import com.achulkov.evokammer.competition.grader.GradingSheet
import com.achulkov.evokammer.competition.grader.GraderGroupManager
import com.achulkov.evokammer.competition.grader.MetaJudge
import com.achulkov.evokammer.competition.grader.RubricDimension
import com.achulkov.evokammer.competition.scoring.BlindSubmissionGate
import com.achulkov.evokammer.competition.scoring.GroupRoundResult
import com.achulkov.evokammer.competition.scoring.ScoringEngine
import com.achulkov.evokammer.competition.scoring.FailureTelemetry
import com.achulkov.evokammer.sandbox.shuru.ShuruCheckResult
import com.achulkov.evokammer.sandbox.shuru.ShuruVmManager
import com.achulkov.evokammer.substrate.db.entity.EventLogEntryEntity
import com.achulkov.evokammer.substrate.db.entity.EventTypes
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.io.path.createTempDirectory
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredFunctions
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.reflect.jvm.isAccessible

/**
 * Verifies the Shuru availability check logic in [EvoKammerRunService].
 *
 * Uses the [LifecycleManager.usesInProcessMode] contract to confirm:
 * - A manager with a [localAgentRunner] reports in-process mode (Shuru not required)
 * - A manager without a runner reports Shuru VM mode (Shuru required)
 *
 * The full service-start path is tested separately in integration tests that
 * require a running DB. This test focuses on the mode-gating contract only.
 */
class EvoKammerRunServiceModeGatingTest {

    // ── mode contract tests ────────────────────────────────────────────────────

    @Test
    fun `LifecycleManager with localAgentRunner reports usesInProcessMode true`() {
        val lm = makeManager(withLocalRunner = true)
        assertTrue(lm.usesInProcessMode,
            "A LifecycleManager with localAgentRunner must report usesInProcessMode=true")
    }

    @Test
    fun `LifecycleManager without localAgentRunner reports usesInProcessMode false`() {
        val lm = makeManager(withLocalRunner = false)
        assertFalse(lm.usesInProcessMode,
            "A LifecycleManager without localAgentRunner must report usesInProcessMode=false (Shuru mode)")
    }

    @Test
    fun `all in-process managers do not need Shuru`() {
        val managers = listOf(makeManager(true), makeManager(true))
        val needsShuru = managers.any { !it.usesInProcessMode }
        assertFalse(needsShuru,
            "When all managers are in-process, Shuru should not be required")
    }

    @Test
    fun `at least one Shuru VM manager means Shuru is required`() {
        val managers = listOf(makeManager(true), makeManager(false))
        val needsShuru = managers.any { !it.usesInProcessMode }
        assertTrue(needsShuru,
            "When any manager is in Shuru VM mode, Shuru is required")
    }

    @Test
    fun `EvoKammerRunService can be constructed with MetaPlannerRunner wiring`() = runTest {
        val tmpDir = createTempDirectory("svc-meta-test-").toFile()
        val substrateFactory = com.achulkov.evokammer.substrate.SubstrateDatabaseFactory(tmpDir)

        val service = EvoKammerRunService(
            lifecycleManagerProvider = { _, _, _, _, _ ->
                throw IllegalStateException("LifecycleManager should not be constructed in this smoke test")
            },
            groupCoordinatorProvider = { groupIds, dimensions ->
                GroupCoordinator(groupIds, dimensions)
            },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = substrateFactory,
            metaPlannerRunner = MetaPlannerRunner()
        )

        assertEquals("IDLE", service.getState().status.name)
    }

    @Test
    fun `sealed test suite is persisted and retrievable after Genesis Council`() = runTest {
        val tmpDir = createTempDirectory("svc-suite-test-").toFile()
        val substrateFactory = com.achulkov.evokammer.substrate.SubstrateDatabaseFactory(tmpDir)

        val service = EvoKammerRunService(
            lifecycleManagerProvider = { _, _, _, _, _ ->
                throw IllegalStateException("stop after genesis storage")
            },
            groupCoordinatorProvider = { groupIds, dimensions ->
                GroupCoordinator(groupIds, dimensions)
            },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = substrateFactory,
            metaPlannerRunner = MetaPlannerRunner(),
            genesisCouncilRunner = { runId, objective, _, _ ->
                com.achulkov.evokammer.core.contract.StartingField.default(
                    objective = objective,
                    runId = runId,
                    groupCount = 2,
                    groupProfilesMap = mapOf(
                        "group-0" to com.achulkov.evokammer.core.model.GroupProfileType.Library,
                        "group-1" to com.achulkov.evokammer.core.model.GroupProfileType.Factory
                    )
                ).copy(
                    sealedTestSuiteJson = """{"tests":[{"id":"smoke","assert":"true"}]}"""
                )
            }
        )

        val config = com.achulkov.evokammer.api.model.RunConfig(
            runId = "run-suite-test",
            objective = "retrieve sealed suite",
            groupCount = 2,
            groupProfiles = listOf(
                com.achulkov.evokammer.core.model.GroupProfileType.Library,
                com.achulkov.evokammer.core.model.GroupProfileType.Factory
            ),
            rounds = 1,
            roundDurationMinutes = 1,
            maxAgentsPerGroup = 1,
            scoringDimensions = listOf(ScoringDimension(id = "d1", name = "d1", description = "d1", weight = 1.0)),
            openRouterApiKey = "sk-test"
        )

        try {
            service.startRun(config)
        } catch (_: IllegalStateException) {
            // Expected: the lifecycle manager provider aborts after genesis storage.
        }

        val db = substrateFactory.get("run-suite-test", "group-0")
        val stored = db.sealedTestSuiteDao().get("run-suite-test", "group-0")
        assertNotNull(stored, "Sealed test suite should be retrievable from the first group's substrate DB")
        assertTrue(stored.suiteJson.contains("\"smoke\""))
        assertTrue(stored.isLocked)
    }

    @Test
    fun `council stage contents become disclosure stages`() {
        val service = EvoKammerRunService(
            lifecycleManagerProvider = { _, _, _, _, _ ->
                throw IllegalStateException("LifecycleManager should not be constructed in this disclosure test")
            },
            groupCoordinatorProvider = { groupIds, dimensions ->
                GroupCoordinator(groupIds, dimensions)
            },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = com.achulkov.evokammer.substrate.SubstrateDatabaseFactory(
                createTempDirectory("svc-disclosure-test-").toFile()
            ),
            metaPlannerRunner = MetaPlannerRunner()
        )

        val startingField = com.achulkov.evokammer.core.contract.StartingField.default(
            objective = "disclosure test",
            groupCount = 2,
            groupProfilesMap = mapOf(
                "group-0" to com.achulkov.evokammer.core.model.GroupProfileType.Library,
                "group-1" to com.achulkov.evokammer.core.model.GroupProfileType.Factory
            )
        ).copy(
            stageContents = listOf(
                "Stage 0 council constraints",
                "Stage 1 council constraints"
            )
        )

        val stages = service.buildDisclosureStages(startingField)
        assertEquals(2, stages.size)
        assertEquals("Stage 0 council constraints", stages[0].constraintFramedContent)
        assertEquals("Stage 1 council constraints", stages[1].constraintFramedContent)
        assertEquals("disclosure test", stages[0].goalFramedContent)
        assertEquals("disclosure test", stages[1].goalFramedContent)
        assertTrue(stages[0].goalFramedContent != stages[0].constraintFramedContent,
            "Goal-framed content must remain distinct from the constraint-framed disclosure")
    }

    @Test
    fun `council-defined score normalization overrides max scores`() {
        val service = EvoKammerRunService(
            lifecycleManagerProvider = { _, _, _, _, _ ->
                throw IllegalStateException("LifecycleManager should not be constructed in this normalization test")
            },
            groupCoordinatorProvider = { groupIds, dimensions ->
                GroupCoordinator(groupIds, dimensions)
            },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = com.achulkov.evokammer.substrate.SubstrateDatabaseFactory(
                createTempDirectory("svc-normalization-test-").toFile()
            ),
            metaPlannerRunner = MetaPlannerRunner()
        )

        val normalizationField = service.javaClass.getDeclaredField("activeScoreNormalization").apply {
            isAccessible = true
        }
        normalizationField.set(service, mapOf("accuracy" to 42.0))

        val normalizeFn = service::class.declaredFunctions.first { it.name == "normaliseScoringDimensions" }.apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val normalized = normalizeFn.call(
            service,
            listOf(
                ScoringDimension(
                    id = "accuracy",
                    name = "Accuracy",
                    description = "Normalize via council policy",
                    weight = 1.0,
                    maxScore = 100.0
                )
            )
        ) as List<ScoringDimension>

        assertEquals(42.0, normalized.single().maxScore)
    }

    @Test
    fun `ring topology from council field becomes the run group order`() = runTest {
        val tmpDir = createTempDirectory("svc-ring-topology-test-").toFile()
        val substrateFactory = com.achulkov.evokammer.substrate.SubstrateDatabaseFactory(tmpDir)
        val service = EvoKammerRunService(
            lifecycleManagerProvider = { _, _, _, _, _ ->
                throw IllegalStateException("LifecycleManager should not be constructed after ring topology capture")
            },
            groupCoordinatorProvider = { groupIds, dimensions ->
                GroupCoordinator(groupIds, dimensions)
            },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = substrateFactory,
            metaPlannerRunner = MetaPlannerRunner(),
            genesisCouncilRunner = { runId, objective, _, _ ->
                com.achulkov.evokammer.core.contract.StartingField.default(
                    objective = objective,
                    runId = runId,
                    groupCount = 2,
                    groupProfilesMap = mapOf(
                        "north" to com.achulkov.evokammer.core.model.GroupProfileType.Library,
                        "south" to com.achulkov.evokammer.core.model.GroupProfileType.Factory
                    )
                ).copy(
                    ringTopology = listOf("south", "north")
                )
            }
        )

        val config = com.achulkov.evokammer.api.model.RunConfig(
            runId = "run-ring-topology-test",
            objective = "ring topology test",
            groupCount = 2,
            groupProfiles = listOf(
                com.achulkov.evokammer.core.model.GroupProfileType.Library,
                com.achulkov.evokammer.core.model.GroupProfileType.Factory
            ),
            rounds = 1,
            roundDurationMinutes = 1,
            maxAgentsPerGroup = 1,
            scoringDimensions = listOf(
                ScoringDimension(
                    id = "accuracy",
                    name = "Accuracy",
                    description = "Council order should drive run order.",
                    weight = 1.0
                )
            ),
            openRouterApiKey = "sk-test"
        )

        runCatching { service.startRun(config) }
        val lastRunGroupIdsField = service.javaClass.getDeclaredField("lastRunGroupIds").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val lastRunGroupIds = lastRunGroupIdsField.get(service) as List<String>
        assertEquals(listOf("south", "north"), lastRunGroupIds)
    }

    @Test
    fun `high severity inspector flag spawns a single auditor per group`() = runTest {
        val tmpDir = createTempDirectory("svc-auditor-test-").toFile()
        val substrateFactory = com.achulkov.evokammer.substrate.SubstrateDatabaseFactory(tmpDir)
        val service = EvoKammerRunService(
            lifecycleManagerProvider = { _, _, _, _, _ ->
                LifecycleManager(
                    runId = "run-auditor-test",
                    groupId = "group-0",
                    shuruVmManager = ShuruVmManager(),
                    substrateDatabaseFactory = substrateFactory,
                    agentJarPath = "/fake/agent.jar",
                    openRouterApiKey = "sk-test",
                    groupProfile = GroupProfile.default(com.achulkov.evokammer.core.model.GroupProfileType.Library),
                    localAgentRunner = { _, _ -> }
                )
            },
            groupCoordinatorProvider = { groupIds, dimensions ->
                GroupCoordinator(groupIds, dimensions)
            },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = substrateFactory,
            metaPlannerRunner = MetaPlannerRunner()
        )

        val manager = LifecycleManager(
            runId = "run-auditor-test",
            groupId = "group-0",
            shuruVmManager = ShuruVmManager(),
            substrateDatabaseFactory = substrateFactory,
            agentJarPath = "/fake/agent.jar",
            openRouterApiKey = "sk-test",
            groupProfile = GroupProfile.default(com.achulkov.evokammer.core.model.GroupProfileType.Library),
            localAgentRunner = { _, _ -> }
        )
        val spawnedRoles = mutableListOf<Role>()
        manager.onAgentSpawned = { _, role, _ -> spawnedRoles += role }

        val liveManagersField = service.javaClass.getDeclaredField("liveLifecycleManagers").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val liveManagers = liveManagersField.get(service) as MutableMap<String, LifecycleManager>
        liveManagers["group-0"] = manager

        val config = com.achulkov.evokammer.api.model.RunConfig(
            runId = "run-auditor-test",
            objective = "auditor spawn",
            groupCount = 2,
            groupProfiles = listOf(
                com.achulkov.evokammer.core.model.GroupProfileType.Library,
                com.achulkov.evokammer.core.model.GroupProfileType.Factory
            ),
            rounds = 1,
            roundDurationMinutes = 1,
            maxAgentsPerGroup = 1,
            scoringDimensions = listOf(ScoringDimension(id = "d1", name = "d1", description = "d1", weight = 1.0)),
            openRouterApiKey = "sk-test"
        )

        val flag = WatchdogSignal.InspectorFlag(
            groupId = "group-0",
            severity = WatchdogSeverity.HIGH,
            description = "constitutional violation spike",
            suggestedRole = Role.Auditor
        )

        service.handleInspectorFlag(config, flag, listOf("group-0"))
        service.handleInspectorFlag(config, flag, listOf("group-0"))

        assertTrue(spawnedRoles.contains(Role.Auditor))
        assertEquals(1, spawnedRoles.count { it == Role.Auditor })
    }

    @Test
    fun `live run emits auditor spawn from inspector watchdog path`() = runBlocking {
        val tmpDir = createTempDirectory("svc-live-auditor-test-").toFile()
        val substrateFactory = com.achulkov.evokammer.substrate.SubstrateDatabaseFactory(tmpDir)
        val runId = "run-live-auditor"
        val groupId = "group-0"

        val service = EvoKammerRunService(
            lifecycleManagerProvider = { runIdValue, groupIdValue, _, _, groupProfile ->
                LifecycleManager(
                    runId = runIdValue,
                    groupId = groupIdValue,
                    shuruVmManager = ShuruVmManager(),
                    substrateDatabaseFactory = substrateFactory,
                    agentJarPath = "/fake/agent.jar",
                    openRouterApiKey = "sk-test",
                    groupProfile = groupProfile,
                    localAgentRunner = { _, _ -> }
                )
            },
            groupCoordinatorProvider = { groupIds, dimensions ->
                GroupCoordinator(groupIds, dimensions)
            },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = substrateFactory,
            metaPlannerRunner = MetaPlannerRunner(),
            roundHeartbeatIntervalMs = 1_000L,
            inspectorCheckIntervalMs = 1L
        )

        val auditorEventDeferred = async {
            service.events.first { event ->
                event is EvoKammerEvent.EngineProgress &&
                    event.phase == "auditor_spawn"
            }
        }

        yield()
        service.startRun(
            com.achulkov.evokammer.api.model.RunConfig(
                runId = runId,
                objective = "live auditor path",
                groupCount = 2,
                groupProfiles = listOf(
                    com.achulkov.evokammer.core.model.GroupProfileType.Library,
                    com.achulkov.evokammer.core.model.GroupProfileType.Factory
                ),
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
        )

        withTimeout(5_000) {
            service.events.first { event ->
                event is EvoKammerEvent.RoundStarted &&
                    event.runId == runId &&
                    event.roundIndex == 0
            }
        }

        val watchdog = service.javaClass.getDeclaredField("activeWatchdog").apply {
            isAccessible = true
        }.get(service) as Watchdog
        watchdog.raiseFlag(
            groupId = groupId,
            severity = WatchdogSeverity.HIGH,
            description = "constitutional violation spike",
            suggestedRole = Role.Auditor
        )

        val auditorEvent = auditorEventDeferred.await()
        assertTrue(
            auditorEvent is EvoKammerEvent.EngineProgress &&
                auditorEvent.phase == "auditor_spawn",
            "The live run should emit an auditor_spawn progress event from the inspector/watchdog path"
        )

        runCatching { service.abortRun(runId, "cleanup after live auditor regression") }
    }

    @Test
    fun `failure telemetry is routed into grader staging`() = runTest {
        val tmpDir = createTempDirectory("svc-telemetry-test-").toFile()
        val substrateFactory = com.achulkov.evokammer.substrate.SubstrateDatabaseFactory(tmpDir)
        val service = EvoKammerRunService(
            lifecycleManagerProvider = { _, _, _, _, _ ->
                throw IllegalStateException("LifecycleManager should not be constructed in this telemetry test")
            },
            groupCoordinatorProvider = { groupIds, dimensions ->
                GroupCoordinator(groupIds, dimensions)
            },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = substrateFactory,
            metaPlannerRunner = MetaPlannerRunner()
        )

        service.routeFailureTelemetry(
            runId = "run-telemetry-test",
            graderGroupIds = listOf("grader-0"),
            telemetry = FailureTelemetry(
                groupId = "group-0",
                roundIndex = 2,
                failureModeIds = listOf("missing_tests"),
                automatedTestsFailed = 3,
                gradingSheetIds = listOf("sheet-a"),
                rawScore = 0.25,
                weightedScore = 0.10
            )
        )

        val graderDb = substrateFactory.getGrader("run-telemetry-test", "grader-0")
        val entries = graderDb.stagingDao().getAll("run-telemetry-test", "grader-0")
        val telemetryEntry = entries.firstOrNull { it.type == "FAILURE_TELEMETRY" }
        assertNotNull(telemetryEntry, "Failure telemetry should be written into grader staging")
        assertTrue(telemetryEntry.content.contains("Failure telemetry"))
        assertTrue(
            telemetryEntry.content.contains("Round: 2"),
            "Failure telemetry should retain round context while omitting direct group identity"
        )
        assertTrue(telemetryEntry.content.contains("missing_tests"))
        assertTrue(
            !telemetryEntry.content.contains("Target group:", ignoreCase = true),
            "Failure telemetry routed to grader staging should be anonymised"
        )

        val competingDb = substrateFactory.get("run-telemetry-test", "group-0")
        val competingEntries = competingDb.stagingDao().getAll("run-telemetry-test", "group-0")
        assertTrue(
            competingEntries.none { it.type == "FAILURE_TELEMETRY" },
            "Failure telemetry must stay out of the competing group's substrate DB"
        )
        assertTrue(
            substrateFactory.dbFilePath("run-telemetry-test", "group-0").absolutePath !=
                substrateFactory.graderDbFilePath("run-telemetry-test", "grader-0").absolutePath,
            "Competing and grader substrate roots must be distinct"
        )
    }

    @Test
    fun `budget critical watchdog signal pauses spawning at threshold`() = runTest {
        val service = EvoKammerRunService(
            lifecycleManagerProvider = { _, _, _, _, _ ->
                throw IllegalStateException("LifecycleManager should not be constructed in this budget test")
            },
            groupCoordinatorProvider = { groupIds, dimensions -> GroupCoordinator(groupIds, dimensions) },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = com.achulkov.evokammer.substrate.SubstrateDatabaseFactory(
                createTempDirectory("svc-budget-pause-test-").toFile()
            ),
            metaPlannerRunner = MetaPlannerRunner()
        )

        val ceilingField = service.javaClass.getDeclaredField("activeGlobalBudgetCeiling").apply {
            isAccessible = true
        }
        ceilingField.setDouble(service, 100.0)

        val config = com.achulkov.evokammer.api.model.RunConfig(
            runId = "run-budget-pause-test",
            objective = "budget pause",
            groupCount = 2,
            groupProfiles = listOf(
                com.achulkov.evokammer.core.model.GroupProfileType.Library,
                com.achulkov.evokammer.core.model.GroupProfileType.Factory
            ),
            rounds = 1,
            roundDurationMinutes = 1,
            maxAgentsPerGroup = 1,
            scoringDimensions = listOf(ScoringDimension(id = "d1", name = "d1", description = "d1", weight = 1.0)),
            openRouterApiKey = "sk-test"
        )

        service.handleWatchdogSignal(
            config = config,
            signal = WatchdogSignal.BudgetCritical(groupId = "group-0", remainingBudget = 4.0),
            groupIds = listOf("group-0")
        )

        assertEquals(
            "PAUSED",
            service.getState().status.name,
            "BudgetCritical at <=5% of the ceiling must pause the run"
        )
    }

    @Test
    fun `sealed suite evaluator reports real pass fail telemetry`() {
        val evaluator = SealedTestSuiteEvaluator()
        val assessment = evaluator.assessGroup(
            runId = "run-assessment-test",
            groupId = "group-0",
            roundIndex = 1,
            suiteJson = """{
              "tests": [
                {
                  "id": "must-have-alpha",
                  "description": "snapshot must mention alpha",
                  "expectedOutputPattern": "alpha",
                  "isMandatory": true
                },
                {
                  "id": "optional-beta",
                  "description": "snapshot should mention beta",
                  "expectedOutputPattern": "beta",
                  "isMandatory": false
                }
              ]
            }""",
            suiteHash = "suite-hash-test",
            canonicalSnapshot = "=== CANON SNAPSHOT ===\nbeta only\n=== END SNAPSHOT ===",
            scoringDimensions = listOf(
                ScoringDimension(
                    id = "accuracy",
                    name = "Accuracy",
                    description = "Deterministic evaluation accuracy",
                    weight = 1.0,
                    maxScore = 100.0
                )
            )
        )

        assertEquals(1, assessment.automatedTestsPassed)
        assertEquals(1, assessment.automatedTestsFailed)
        assertTrue(assessment.mandatoryFailure)
        assertEquals(0.25, assessment.overallScore)
        assertEquals(25.0, assessment.dimensionScores.single().rawScore)
        assertTrue(assessment.failedTestIds.contains("must-have-alpha"))
        assertTrue(assessment.gradingSheetApplied.startsWith("sealed-suite-1-"))
    }

    @Test
    fun `malformed sealed suite definitions fail closed`() {
        val evaluator = SealedTestSuiteEvaluator()
        val assessment = evaluator.assessGroup(
            runId = "run-malformed-suite-test",
            groupId = "group-0",
            roundIndex = 0,
            suiteJson = """{
              "tests": [
                {
                  "id": "broken-test"
                }
              ]
            }""",
            suiteHash = "suite-hash-test",
            canonicalSnapshot = "alpha beta",
            scoringDimensions = listOf(
                ScoringDimension(
                    id = "accuracy",
                    name = "Accuracy",
                    description = "Deterministic evaluation accuracy",
                    weight = 1.0,
                    maxScore = 100.0
                )
            )
        )

        assertEquals(0, assessment.automatedTestsPassed)
        assertEquals(0, assessment.automatedTestsFailed)
        assertEquals(0.0, assessment.overallScore)
        assertEquals(0.0, assessment.dimensionScores.single().rawScore)
        assertTrue(assessment.failedTestIds.isEmpty())
    }

    @Test
    fun `publishBlindRoundResult uses sealed suite telemetry end to end`() = runTest {
        val tmpDir = createTempDirectory("svc-blind-round-test-").toFile()
        val substrateFactory = com.achulkov.evokammer.substrate.SubstrateDatabaseFactory(tmpDir)
        val service = EvoKammerRunService(
            lifecycleManagerProvider = { _, _, _, _, _ ->
                throw IllegalStateException("LifecycleManager should not be constructed in this blind-round test")
            },
            groupCoordinatorProvider = { groupIds, dimensions ->
                GroupCoordinator(groupIds, dimensions)
            },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = substrateFactory,
            metaPlannerRunner = MetaPlannerRunner()
        )

        val blindTournamentService = BlindTournamentService(onFailureTelemetry = {})
        blindTournamentService.start(listOf("group-0"))
        val field = service.javaClass.getDeclaredField("activeBlindTournamentService").apply { isAccessible = true }
        field.set(service, blindTournamentService)
        val suiteField = service.javaClass.getDeclaredField("activeSealedTestSuiteJson").apply { isAccessible = true }
        suiteField.set(service, """{
          "tests": [
            {
              "id": "must-have-alpha",
              "description": "snapshot must mention alpha",
              "expectedOutputPattern": "alpha",
              "isMandatory": true
            }
          ]
        }""")
        val hashField = service.javaClass.getDeclaredField("activeSealedTestSuiteHash").apply { isAccessible = true }
        hashField.set(service, "suite-hash-test")
        val assessmentsField = service.javaClass.getDeclaredField("latestGroupRoundAssessments").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val assessments = assessmentsField.get(service) as MutableMap<String, Any>
        assessments["group-0"] = com.achulkov.evokammer.api.service.DeterministicRoundAssessment(
            dimensionScores = listOf(
                com.achulkov.evokammer.core.model.DimensionScore(
                    dimensionId = "accuracy",
                    rawScore = 100.0,
                    justification = "sealed-suite telemetry"
                )
            ),
            automatedTestsPassed = 0,
            automatedTestsFailed = 1,
            overallScore = 1.0,
            gradingSheetApplied = "sealed-suite-0-suite-hash-test",
            failedTestIds = listOf("must-have-alpha"),
            mandatoryFailure = true
        )

        val config = com.achulkov.evokammer.api.model.RunConfig(
            runId = "run-blind-round-test",
            objective = "blind round test",
            groupCount = 1,
            groupProfiles = listOf(com.achulkov.evokammer.core.model.GroupProfileType.Library),
            rounds = 1,
            roundDurationMinutes = 1,
            maxAgentsPerGroup = 1,
            scoringDimensions = listOf(
                ScoringDimension(
                    id = "accuracy",
                    name = "Accuracy",
                    description = "Deterministic evaluation accuracy",
                    weight = 1.0,
                    maxScore = 100.0
                )
            ),
            openRouterApiKey = "sk-test",
            isTestRun = true
        )

        val method = service::class.declaredFunctions.first { it.name == "publishBlindRoundResult" }.apply { isAccessible = true }
        val result = method.callSuspend(service, config, listOf("group-0"), 0) as com.achulkov.evokammer.core.model.GradingResult
        val score = result.scores.single { it.groupId == "group-0" }
        assertTrue(score.weightedTotal < 100.0, "Failed automated tests must change the blind reveal score")
        assertTrue(score.weightedTotal > 99.0, "The sealed-suite penalty should be small but non-zero for one failed mandatory test")
        assertEquals(1, score.dimensionScores.size)
        assertTrue(assessments.containsKey("group-0"))

        blindTournamentService.stop()
    }

    // ── ShuruCheckResult contract tests ────────────────────────────────────────

    @Test
    fun `ShuruCheckResult available=true is parsed correctly`() {
        val result = ShuruCheckResult(available = true, version = "0.1.0", message = "OK")
        assertTrue(result.available)
    }

    @Test
    fun `ShuruCheckResult available=false carries reason message`() {
        val msg = "shuru: command not found"
        val result = ShuruCheckResult(available = false, version = null, message = msg)
        assertFalse(result.available)
        assertTrue(result.message.lowercase().contains("shuru"),
            "Message should mention shuru")
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private fun makeManager(withLocalRunner: Boolean): LifecycleManager {
        val tmpDir = createTempDirectory("svc-mode-test-").toFile()
        val substrateFactory = com.achulkov.evokammer.substrate.SubstrateDatabaseFactory(tmpDir)
        val shuruVmManager = ShuruVmManager()
        val runner: (suspend (AgentBootContext, ((BudgetLedger) -> Unit)?) -> Unit)? =
            if (withLocalRunner) ({ _, _ -> }) else null

        return LifecycleManager(
            runId = "run-test",
            groupId = "group-0",
            shuruVmManager = shuruVmManager,
            substrateDatabaseFactory = substrateFactory,
            agentJarPath = "/fake/agent.jar",
            openRouterApiKey = "sk-test",
            groupProfile = GroupProfile.default(com.achulkov.evokammer.core.model.GroupProfileType.Library),
            localAgentRunner = runner
        )
    }
}
