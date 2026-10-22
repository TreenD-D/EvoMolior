package com.achulkov.evomolior.api

import com.achulkov.evomolior.core.budget.BudgetLedger
import com.achulkov.evomolior.core.contract.AgentBootContext
import com.achulkov.evomolior.core.contract.StartingField
import com.achulkov.evomolior.core.model.GroupProfile
import com.achulkov.evomolior.core.model.Role
import com.achulkov.evomolior.core.model.ScoringDimension
import com.achulkov.evomolior.orchestration.metaplanner.MetaPlannerRunner
import com.achulkov.evomolior.orchestration.lifecycle.LifecycleManager
import com.achulkov.evomolior.orchestration.watchdog.Watchdog
import com.achulkov.evomolior.orchestration.watchdog.WatchdogSignal
import com.achulkov.evomolior.orchestration.watchdog.WatchdogSeverity
import com.achulkov.evomolior.api.service.EvoMoliorRunService
import com.achulkov.evomolior.api.service.AcceptanceRunBoundary
import com.achulkov.evomolior.api.service.AcceptanceRunBoundaryVerifier
import com.achulkov.evomolior.api.service.StartupRejectionFileStore
import com.achulkov.evomolior.api.service.StartupRejectionPhase
import com.achulkov.evomolior.api.service.StartupRejectionReason
import com.achulkov.evomolior.api.service.BlindTournamentService
import com.achulkov.evomolior.api.service.CouncilExecutionEnvelopeFactory
import com.achulkov.evomolior.api.service.DeterministicRoundAssessment
import com.achulkov.evomolior.api.events.EvoMoliorEvent
import com.achulkov.evomolior.api.model.FinalOutputSpec
import com.achulkov.evomolior.api.model.RunConfig
import com.achulkov.evomolior.api.service.GeneratedOutputFile
import com.achulkov.evomolior.api.service.RunArchiveService
import com.achulkov.evomolior.api.service.RunOutcomeBundleInspector
import com.achulkov.evomolior.api.service.SealedTestSuiteEvaluator
import com.achulkov.evomolior.api.service.compiledCouncilTestFixture
import com.achulkov.evomolior.api.service.resolveAuthoritativeGraderRoundResult
import com.achulkov.evomolior.competition.coordinator.GroupCoordinator
import com.achulkov.evomolior.competition.grader.FailureMode
import com.achulkov.evomolior.competition.grader.GradingSheet
import com.achulkov.evomolior.competition.grader.GraderGroupManager
import com.achulkov.evomolior.competition.grader.MetaJudge
import com.achulkov.evomolior.competition.grader.RubricDimension
import com.achulkov.evomolior.competition.scoring.BlindSubmissionGate
import com.achulkov.evomolior.competition.scoring.GroupRoundResult
import com.achulkov.evomolior.competition.scoring.ScoringEngine
import com.achulkov.evomolior.competition.scoring.FailureTelemetry
import com.achulkov.evomolior.core.contract.GroupTopologyKind
import com.achulkov.evomolior.core.config.AcceptanceProfile
import com.achulkov.evomolior.core.config.CouncilExecutionPurpose
import com.achulkov.evomolior.core.config.LlmBackend
import com.achulkov.evomolior.core.council.CouncilDeterministicIds
import com.achulkov.evomolior.core.deliverable.RunOutcomeClass
import com.achulkov.evomolior.sandbox.shuru.ShuruCheckResult
import com.achulkov.evomolior.sandbox.shuru.ShuruVmManager
import com.achulkov.evomolior.substrate.db.entity.EventLogEntryEntity
import com.achulkov.evomolior.substrate.db.entity.EventTypes
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlin.io.path.createTempDirectory
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredFunctions
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFailsWith
import kotlin.reflect.jvm.isAccessible

/**
 * Verifies the Shuru availability check logic in [EvoMoliorRunService].
 *
 * Uses the [LifecycleManager.usesInProcessMode] contract to confirm:
 * - A manager with a [localAgentRunner] reports in-process mode (Shuru not required)
 * - A manager without a runner reports Shuru VM mode (Shuru required)
 *
 * The full service-start path is tested separately in integration tests that
 * require a running DB. This test focuses on the mode-gating contract only.
 */
class EvoMoliorRunServiceModeGatingTest {

    // ── mode contract tests ────────────────────────────────────────────────────

    @Test
    fun `coordinator construction failure remains a preactivation startup rejection`() = runTest {
        val runId = "coordinator-preactivation-rejection"
        val root = createTempDirectory("coordinator-preactivation-rejection-").toFile()
        val substrateFactory =
            com.achulkov.evomolior.substrate.SubstrateDatabaseFactory(root)
        val service = EvoMoliorRunService(
            lifecycleManagerProvider = { _, _, _, _, _ ->
                error("lifecycle activation must not begin")
            },
            groupCoordinatorProvider = { _, _ ->
                error("coordinator construction failed")
            },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = substrateFactory,
            metaPlannerRunner = MetaPlannerRunner()
        )
        val config = RunConfig(
            runId = runId,
            objective = "Reject before runtime infrastructure owns the run",
            groupCount = 2,
            groupProfiles = listOf(
                com.achulkov.evomolior.core.model.GroupProfileType.Library,
                com.achulkov.evomolior.core.model.GroupProfileType.Factory
            ),
            rounds = 1,
            roundDurationMinutes = 1,
            maxAgentsPerGroup = 1,
            scoringDimensions = listOf(
                ScoringDimension(
                    id = "accuracy",
                    name = "Accuracy",
                    description = "Preactivation classification",
                    weight = 1.0
                )
            ),
            openRouterApiKey = "sk-test",
            isTestRun = true
        )

        val failure = assertFailsWith<IllegalStateException> {
            service.startRun(config)
        }

        assertTrue(failure.message.orEmpty().contains("coordinator construction failed"))
        val rejection = assertNotNull(
            StartupRejectionFileStore(substrateFactory::runControlDir).read(runId)
        )
        assertFalse(rejection.activationFlags.runStartedEmitted)
        assertFalse(rejection.activationFlags.workerInfrastructureActivated)
        assertFalse(rejection.activationFlags.graderInfrastructureActivated)
        assertEquals("IDLE", service.getState().status.name)
    }

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
    fun `configured grader authority fails closed while absent authority selects deterministic mode`() {
        val dimensions = listOf(
            ScoringDimension("accuracy", "Accuracy", "Accuracy", 1.0, maxScore = 10.0),
            ScoringDimension("evidence", "Evidence", "Evidence", 1.0, maxScore = 10.0)
        )
        val manager = GraderGroupManager(dimensions)
        manager.submitScores(
            graderGroupId = "grader-0",
            targetGroupId = "group-0",
            roundIndex = 0,
            scores = listOf(
                com.achulkov.evomolior.core.model.DimensionScore(
                    "accuracy",
                    0.0,
                    "Explicit zero but incomplete bundle"
                )
            )
        )

        assertEquals(
            null,
            resolveAuthoritativeGraderRoundResult(
                manager,
                listOf("group-0"),
                roundIndex = 0,
                graderAuthorityConfigured = false
            )
        )
        assertFailsWith<IllegalStateException> {
            resolveAuthoritativeGraderRoundResult(
                manager,
                listOf("group-0"),
                roundIndex = 0,
                graderAuthorityConfigured = true
            )
        }
    }

    @Test
    fun `EvoMoliorRunService can be constructed with MetaPlannerRunner wiring`() = runTest {
        val tmpDir = createTempDirectory("svc-meta-test-").toFile()
        val substrateFactory = com.achulkov.evomolior.substrate.SubstrateDatabaseFactory(tmpDir)

        val service = EvoMoliorRunService(
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
    fun `non-normal acceptance verifies exact synchronous run authority boundaries`() {
        val substrateFactory = com.achulkov.evomolior.substrate.SubstrateDatabaseFactory(
            createTempDirectory("svc-acceptance-boundaries-").toFile()
        )
        val observed = mutableListOf<Pair<String, AcceptanceRunBoundary>>()
        val service = EvoMoliorRunService(
            lifecycleManagerProvider = { _, _, _, _, _ -> error("runtime must not activate") },
            groupCoordinatorProvider = { groupIds, dimensions ->
                GroupCoordinator(groupIds, dimensions)
            },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = substrateFactory,
            acceptanceRunBoundaryVerifier = AcceptanceRunBoundaryVerifier { runId, boundary ->
                observed += runId to boundary
            }
        )
        val config = fullAcceptanceConfig("acceptance-boundary-run")

        service.verifyAcceptanceRunBoundary(
            config,
            AcceptanceRunBoundary.RoundCompleted(roundIndex = 5)
        )
        service.verifyAcceptanceRunBoundary(
            config,
            AcceptanceRunBoundary.BeforeFinalPublication
        )
        service.verifyAcceptanceRunBoundary(
            config,
            AcceptanceRunBoundary.BeforeArchivePublication
        )

        assertEquals(
            listOf(
                config.runId to AcceptanceRunBoundary.RoundCompleted(5),
                config.runId to AcceptanceRunBoundary.BeforeFinalPublication,
                config.runId to AcceptanceRunBoundary.BeforeArchivePublication
            ),
            observed
        )
    }

    @Test
    fun `normal runs skip acceptance verifier and acceptance verifier failures propagate`() {
        val substrateFactory = com.achulkov.evomolior.substrate.SubstrateDatabaseFactory(
            createTempDirectory("svc-acceptance-boundary-failure-").toFile()
        )
        var invocations = 0
        val service = EvoMoliorRunService(
            lifecycleManagerProvider = { _, _, _, _, _ -> error("runtime must not activate") },
            groupCoordinatorProvider = { groupIds, dimensions ->
                GroupCoordinator(groupIds, dimensions)
            },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = substrateFactory,
            acceptanceRunBoundaryVerifier = AcceptanceRunBoundaryVerifier { _, _ ->
                invocations += 1
                error("immutable acceptance authority changed")
            }
        )
        val acceptanceConfig = fullAcceptanceConfig("acceptance-boundary-failure")
        val normalConfig = acceptanceConfig.copy(
            councilExecutionPurpose = CouncilExecutionPurpose.STANDARD,
            acceptanceProfile = AcceptanceProfile.NORMAL
        )

        service.verifyAcceptanceRunBoundary(
            normalConfig,
            AcceptanceRunBoundary.BeforeFinalPublication
        )
        assertEquals(0, invocations)

        val failure = assertFailsWith<IllegalStateException> {
            service.verifyAcceptanceRunBoundary(
                acceptanceConfig,
                AcceptanceRunBoundary.BeforeFinalPublication
            )
        }
        assertTrue(failure.message.orEmpty().contains("immutable acceptance authority changed"))
        assertEquals(1, invocations)
    }

    @Test
    fun `acceptance profile without archive rejects before Council or runtime work`() = runTest {
        val substrateFactory = com.achulkov.evomolior.substrate.SubstrateDatabaseFactory(
            createTempDirectory("svc-acceptance-no-archive-").toFile()
        )
        var councilInvocations = 0
        var runtimeAuthorityInvocations = 0
        val service = EvoMoliorRunService(
            lifecycleManagerProvider = { _, _, _, _, _ ->
                runtimeAuthorityInvocations += 1
                error("runtime must not activate")
            },
            groupCoordinatorProvider = { groupIds, dimensions ->
                runtimeAuthorityInvocations += 1
                GroupCoordinator(groupIds, dimensions)
            },
            graderGroupManagerProvider = { dimensions ->
                runtimeAuthorityInvocations += 1
                GraderGroupManager(dimensions)
            },
            substrateFactory = substrateFactory,
            acceptanceRunBoundaryVerifier = AcceptanceRunBoundaryVerifier { _, _ -> },
            genesisCouncilRunner = { _, _, _, _, _, _, _ ->
                councilInvocations += 1
                error("Council must not run")
            }
        )

        val failure = assertFailsWith<IllegalStateException> {
            service.startRun(fullAcceptanceConfig("acceptance-no-archive"))
        }

        assertTrue(failure.message.orEmpty().contains("requires a durable run archive service"))
        assertEquals(0, councilInvocations)
        assertEquals(0, runtimeAuthorityInvocations)
        val rejection = assertNotNull(
            StartupRejectionFileStore(substrateFactory::runControlDir)
                .read("acceptance-no-archive")
        )
        assertEquals(StartupRejectionPhase.ADMISSION, rejection.phase)
        assertEquals(listOf(StartupRejectionReason.ADMISSION_REJECTED), rejection.reasons)
        assertEquals(0, rejection.providerExecutionCount)
        assertFalse(rejection.activationFlags.runStartedEmitted)
        assertFalse(rejection.activationFlags.workerInfrastructureActivated)
        assertFalse(rejection.activationFlags.graderInfrastructureActivated)
        assertEquals("IDLE", service.getState().status.name)
        val presentation = RunOutcomeBundleInspector.inspect(
            assertNotNull(service.getResultsBundle("acceptance-no-archive"))
        )
        assertEquals(RunOutcomeClass.STARTUP_DIAGNOSTIC_PACKAGE, presentation.outcomeClass)
        assertNotNull(presentation.startupDiagnostic)
        assertEquals(null, presentation.decisionPackage?.acceptedAuthority)
        assertEquals(null, presentation.metrics)
    }

    @Test
    fun `acceptance profile without production Council rejects before runtime work`() = runTest {
        val substrateFactory = com.achulkov.evomolior.substrate.SubstrateDatabaseFactory(
            createTempDirectory("svc-acceptance-no-council-").toFile()
        )
        var runtimeAuthorityInvocations = 0
        val service = EvoMoliorRunService(
            lifecycleManagerProvider = { _, _, _, _, _ ->
                runtimeAuthorityInvocations += 1
                error("runtime must not activate")
            },
            groupCoordinatorProvider = { groupIds, dimensions ->
                runtimeAuthorityInvocations += 1
                GroupCoordinator(groupIds, dimensions)
            },
            graderGroupManagerProvider = { dimensions ->
                runtimeAuthorityInvocations += 1
                GraderGroupManager(dimensions)
            },
            substrateFactory = substrateFactory,
            archiveService = RunArchiveService(substrateFactory),
            acceptanceRunBoundaryVerifier = AcceptanceRunBoundaryVerifier { _, _ -> }
        )

        val failure = assertFailsWith<IllegalStateException> {
            service.startRun(fullAcceptanceConfig("acceptance-no-council"))
        }

        assertTrue(failure.message.orEmpty().contains("requires production Genesis Council compilation"))
        assertEquals(0, runtimeAuthorityInvocations)
        val rejection = assertNotNull(
            StartupRejectionFileStore(substrateFactory::runControlDir).read("acceptance-no-council")
        )
        assertEquals(0, rejection.providerExecutionCount)
        assertTrue(rejection.providerExecutionRefs.isEmpty())
        assertEquals("IDLE", service.getState().status.name)
    }

    @Test
    fun `Council runner failure before invocation start retains zero provider executions`() = runTest {
        val substrateFactory = com.achulkov.evomolior.substrate.SubstrateDatabaseFactory(
            createTempDirectory("svc-council-not-started-").toFile()
        )
        val runId = "acceptance-council-not-started"
        val service = EvoMoliorRunService(
            lifecycleManagerProvider = { _, _, _, _, _ -> error("runtime must not activate") },
            groupCoordinatorProvider = { groupIds, dimensions ->
                GroupCoordinator(groupIds, dimensions)
            },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = substrateFactory,
            archiveService = RunArchiveService(substrateFactory),
            acceptanceRunBoundaryVerifier = AcceptanceRunBoundaryVerifier { _, _ -> },
            genesisCouncilRunner = { _, _, _, _, _, _, _ ->
                error("runner failed before invocation start")
            }
        )

        val failure = assertFailsWith<IllegalStateException> {
            service.startRun(fullAcceptanceConfig(runId))
        }

        assertTrue(failure.message.orEmpty().contains("runner failed before invocation start"))
        val rejection = assertNotNull(
            StartupRejectionFileStore(substrateFactory::runControlDir).read(runId)
        )
        assertEquals(0, rejection.providerExecutionCount)
        assertTrue(rejection.providerExecutionRefs.isEmpty())
        assertEquals("IDLE", service.getState().status.name)
    }

    @Test
    fun `acceptance profile without boundary verifier rejects before Council or runtime work`() = runTest {
        val substrateFactory = com.achulkov.evomolior.substrate.SubstrateDatabaseFactory(
            createTempDirectory("svc-acceptance-no-verifier-").toFile()
        )
        var councilInvocations = 0
        var runtimeAuthorityInvocations = 0
        val runId = "acceptance-no-verifier"
        val service = EvoMoliorRunService(
            lifecycleManagerProvider = { _, _, _, _, _ ->
                runtimeAuthorityInvocations += 1
                error("runtime must not activate")
            },
            groupCoordinatorProvider = { groupIds, dimensions ->
                runtimeAuthorityInvocations += 1
                GroupCoordinator(groupIds, dimensions)
            },
            graderGroupManagerProvider = { dimensions ->
                runtimeAuthorityInvocations += 1
                GraderGroupManager(dimensions)
            },
            substrateFactory = substrateFactory,
            archiveService = RunArchiveService(substrateFactory),
            genesisCouncilRunner = { _, _, _, _, _, _, _ ->
                councilInvocations += 1
                error("Council must not run")
            }
        )

        val failure = assertFailsWith<IllegalStateException> {
            service.startRun(fullAcceptanceConfig(runId))
        }

        assertTrue(failure.message.orEmpty().contains("non-NONE AcceptanceRunBoundaryVerifier"))
        assertEquals(0, councilInvocations)
        assertEquals(0, runtimeAuthorityInvocations)
        val rejection = assertNotNull(
            StartupRejectionFileStore(substrateFactory::runControlDir).read(runId)
        )
        assertEquals(StartupRejectionPhase.ADMISSION, rejection.phase)
        assertEquals(0, rejection.providerExecutionCount)
        assertFalse(rejection.activationFlags.runStartedEmitted)
        assertFalse(rejection.activationFlags.workerInfrastructureActivated)
        assertFalse(rejection.activationFlags.graderInfrastructureActivated)
    }

    @Test
    fun `required Shuru rejects before any runtime provider is constructed`() = runTest {
        val substrateFactory = com.achulkov.evomolior.substrate.SubstrateDatabaseFactory(
            createTempDirectory("svc-required-shuru-preflight-").toFile()
        )
        var runtimeAuthorityInvocations = 0
        val runId = "required-shuru-preflight"
        val service = EvoMoliorRunService(
            lifecycleManagerProvider = { _, _, _, _, _ ->
                runtimeAuthorityInvocations += 1
                error("runtime must not activate")
            },
            groupCoordinatorProvider = { groupIds, dimensions ->
                runtimeAuthorityInvocations += 1
                GroupCoordinator(groupIds, dimensions)
            },
            graderGroupManagerProvider = { dimensions ->
                runtimeAuthorityInvocations += 1
                GraderGroupManager(dimensions)
            },
            substrateFactory = substrateFactory,
            requiresShuru = true,
            shuruPreflight = {
                ShuruCheckResult(
                    available = false,
                    version = null,
                    message = "deterministic unavailable Shuru"
                )
            }
        )

        val failure = assertFailsWith<IllegalStateException> {
            service.startRun(
                RunConfig(
                    runId = runId,
                    objective = "Fail before provider construction",
                    groupCount = 1,
                    groupProfiles = listOf(com.achulkov.evomolior.core.model.GroupProfileType.Library),
                    rounds = 1,
                    roundDurationMinutes = 1,
                    maxAgentsPerGroup = 1,
                    scoringDimensions = listOf(
                        ScoringDimension("quality", "Quality", "Quality", 1.0)
                    ),
                    openRouterApiKey = "unused",
                    isTestRun = true
                )
            )
        }

        assertTrue(failure.message.orEmpty().contains("shuru_unavailable"))
        assertEquals(0, runtimeAuthorityInvocations)
        val rejection = assertNotNull(
            StartupRejectionFileStore(substrateFactory::runControlDir).read(runId)
        )
        assertEquals(StartupRejectionPhase.ADMISSION, rejection.phase)
        assertFalse(rejection.activationFlags.runStartedEmitted)
        assertFalse(rejection.activationFlags.workerInfrastructureActivated)
        assertFalse(rejection.activationFlags.graderInfrastructureActivated)
    }

    @Test
    fun `sealed grader model assignments are absent for uncompiled active routes`() {
        val service = EvoMoliorRunService(
            lifecycleManagerProvider = { _, _, _, _, _ -> error("runtime must not activate") },
            groupCoordinatorProvider = { groupIds, dimensions -> GroupCoordinator(groupIds, dimensions) },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = com.achulkov.evomolior.substrate.SubstrateDatabaseFactory(
                createTempDirectory("svc-active-grader-models-").toFile()
            )
        )
        val runId = "active-grader-models"
        service.javaClass.getDeclaredField("activeRunId").apply { isAccessible = true }
            .set(service, runId)
        service.javaClass.getDeclaredField("activeGraderStartingField").apply { isAccessible = true }
            .set(
                service,
                StartingField.default(
                    objective = "grader assignment visibility",
                    runId = runId,
                    groupCount = 1,
                    groupProfilesMap = mapOf(
                        "group-0" to com.achulkov.evomolior.core.model.GroupProfileType.Library
                    )
                ).copy(modelAssignment = mapOf("RoundJudge" to "grader-model"))
            )

        assertEquals(null, service.activeSealedGraderModelAssignments(runId))
        assertFailsWith<IllegalStateException> {
            service.activeSealedGraderModelAssignments("wrong-run")
        }
    }

    @Test
    fun `abort waits for a cancelled non-cooperative run loop before releasing ownership`() = runBlocking {
        val service = EvoMoliorRunService(
            lifecycleManagerProvider = { _, _, _, _, _ -> error("unused") },
            groupCoordinatorProvider = { ids, dimensions -> GroupCoordinator(ids, dimensions) },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = com.achulkov.evomolior.substrate.SubstrateDatabaseFactory(
                createTempDirectory("svc-abort-join-").toFile()
            )
        )
        val runId = "abort-join"
        service.javaClass.getDeclaredField("activeRunId").apply { isAccessible = true }
            .set(service, runId)
        val loopStarted = CompletableDeferred<Unit>()
        val cancellationObserved = CompletableDeferred<Unit>()
        val allowQuiescence = CompletableDeferred<Unit>()
        val loopJob = CoroutineScope(Dispatchers.Default).launch {
            try {
                loopStarted.complete(Unit)
                awaitCancellation()
            } finally {
                cancellationObserved.complete(Unit)
                withContext(NonCancellable) { allowQuiescence.await() }
            }
        }
        service.javaClass.getDeclaredField("activeRunLoopJob").apply { isAccessible = true }
            .set(service, loopJob)
        val terminalEvents = mutableListOf<EvoMoliorEvent>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            service.events.collect { event ->
                if (event is EvoMoliorEvent.RunAborted && event.runId == runId) {
                    terminalEvents += event
                }
            }
        }
        withTimeout(5_000) { loopStarted.await() }
        val abort = async { service.abortRun(runId, "deterministic join") }

        withTimeout(5_000) { cancellationObserved.await() }
        assertFalse(abort.isCompleted)
        assertEquals(runId, service.getState().activeRunId)

        allowQuiescence.complete(Unit)
        abort.await()
        withTimeout(5_000) {
            while (terminalEvents.isEmpty()) yield()
        }
        assertEquals(1, terminalEvents.size)
        assertEquals(null, service.getState().activeRunId)
        collector.cancel()
    }

    @Test
    fun `active run conflict persists rejection without clearing existing owner`() = runTest {
        val substrateFactory = com.achulkov.evomolior.substrate.SubstrateDatabaseFactory(
            createTempDirectory("svc-active-run-rejection-").toFile()
        )
        val service = EvoMoliorRunService(
            lifecycleManagerProvider = { _, _, _, _, _ -> error("runtime must not activate") },
            groupCoordinatorProvider = { groupIds, dimensions ->
                GroupCoordinator(groupIds, dimensions)
            },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = substrateFactory
        )
        val activeRunId = service.javaClass.getDeclaredField("activeRunId").apply {
            isAccessible = true
        }
        activeRunId.set(service, "existing-run")

        val failure = assertFailsWith<IllegalStateException> {
            service.startRun(fullAcceptanceConfig("conflicting-run"))
        }

        assertTrue(failure.message.orEmpty().contains("existing-run is already active"))
        assertEquals("existing-run", activeRunId.get(service))
        val rejection = assertNotNull(
            StartupRejectionFileStore(substrateFactory::runControlDir).read("conflicting-run")
        )
        assertEquals(StartupRejectionPhase.ADMISSION, rejection.phase)
        assertEquals(0, rejection.providerExecutionCount)
        assertFalse(rejection.activationFlags.workerInfrastructureActivated)
        assertFalse(rejection.activationFlags.graderInfrastructureActivated)
    }

    @Test
    fun `sealed test suite is persisted and retrievable after Genesis Council`() = runTest {
        val tmpDir = createTempDirectory("svc-suite-test-").toFile()
        val substrateFactory = com.achulkov.evomolior.substrate.SubstrateDatabaseFactory(tmpDir)

        val service = EvoMoliorRunService(
            lifecycleManagerProvider = { _, _, _, _, _ ->
                throw IllegalStateException("stop after genesis storage")
            },
            groupCoordinatorProvider = { groupIds, dimensions ->
                GroupCoordinator(groupIds, dimensions)
            },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = substrateFactory,
            metaPlannerRunner = MetaPlannerRunner(),
            genesisCouncilRunner = {
                    runId,
                    objective,
                    _,
                    envelope,
                    inputArtifacts,
                    _,
                    onInvocationStarted ->
                onInvocationStarted()
                compiledCouncilTestFixture(
                    runId = runId,
                    objective = objective,
                    envelope = envelope,
                    inputArtifacts = inputArtifacts,
                    workerGroupKeys = listOf("north", "south"),
                    workerProfiles = listOf(
                        com.achulkov.evomolior.core.model.GroupProfileType.Library,
                        com.achulkov.evomolior.core.model.GroupProfileType.Factory
                    )
                )
            }
        )

        val config = com.achulkov.evomolior.api.model.RunConfig(
            runId = "run-suite-test",
            objective = "retrieve sealed suite",
            groupCount = 2,
            groupProfiles = listOf(
                com.achulkov.evomolior.core.model.GroupProfileType.Library,
                com.achulkov.evomolior.core.model.GroupProfileType.Factory
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

        val firstGroupId = CouncilDeterministicIds.groupId(
            "run-suite-test",
            "worker",
            "north"
        )
        val db = substrateFactory.get("run-suite-test", firstGroupId)
        val stored = db.sealedTestSuiteDao().get("run-suite-test", firstGroupId)
        assertNotNull(stored, "Sealed test suite should be retrievable from the first group's substrate DB")
        assertTrue(stored.suiteJson.contains("\"delivery-test\""))
        assertTrue(stored.isLocked)
    }

    @Test
    fun `council stage contents become disclosure stages`() {
        val service = EvoMoliorRunService(
            lifecycleManagerProvider = { _, _, _, _, _ ->
                throw IllegalStateException("LifecycleManager should not be constructed in this disclosure test")
            },
            groupCoordinatorProvider = { groupIds, dimensions ->
                GroupCoordinator(groupIds, dimensions)
            },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = com.achulkov.evomolior.substrate.SubstrateDatabaseFactory(
                createTempDirectory("svc-disclosure-test-").toFile()
            ),
            metaPlannerRunner = MetaPlannerRunner()
        )

        val startingField = com.achulkov.evomolior.core.contract.StartingField.default(
            objective = "disclosure test",
            groupCount = 2,
            groupProfilesMap = mapOf(
                "group-0" to com.achulkov.evomolior.core.model.GroupProfileType.Library,
                "group-1" to com.achulkov.evomolior.core.model.GroupProfileType.Factory
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
        val service = EvoMoliorRunService(
            lifecycleManagerProvider = { _, _, _, _, _ ->
                throw IllegalStateException("LifecycleManager should not be constructed in this normalization test")
            },
            groupCoordinatorProvider = { groupIds, dimensions ->
                GroupCoordinator(groupIds, dimensions)
            },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = com.achulkov.evomolior.substrate.SubstrateDatabaseFactory(
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
    fun `council ring profile order is preserved under canonical runtime group ids`() = runTest {
        val tmpDir = createTempDirectory("svc-ring-topology-test-").toFile()
        val substrateFactory = com.achulkov.evomolior.substrate.SubstrateDatabaseFactory(tmpDir)
        val observedProfileOrder =
            mutableListOf<com.achulkov.evomolior.core.model.GroupProfileType>()
        val service = EvoMoliorRunService(
            lifecycleManagerProvider = {
                    runId,
                    groupId,
                    openRouterApiKey,
                    agentJarPath,
                    groupProfile ->
                observedProfileOrder += groupProfile.profileType
                if (observedProfileOrder.size == 2) {
                    error("stop after ring topology capture")
                }
                LifecycleManager(
                    runId = runId,
                    groupId = groupId,
                    shuruVmManager = ShuruVmManager(),
                    substrateDatabaseFactory = substrateFactory,
                    agentJarPath = agentJarPath,
                    openRouterApiKey = openRouterApiKey,
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
            genesisCouncilRunner = {
                    runId,
                    objective,
                    _,
                    envelope,
                    inputArtifacts,
                    _,
                    onInvocationStarted ->
                onInvocationStarted()
                compiledCouncilTestFixture(
                    runId = runId,
                    objective = objective,
                    envelope = envelope,
                    inputArtifacts = inputArtifacts,
                    workerGroupKeys = listOf("north", "south"),
                    workerProfiles = listOf(
                        com.achulkov.evomolior.core.model.GroupProfileType.Library,
                        com.achulkov.evomolior.core.model.GroupProfileType.Factory
                    ),
                    topologyOrder = listOf("south", "north")
                )
            }
        )

        val config = com.achulkov.evomolior.api.model.RunConfig(
            runId = "run-ring-topology-test",
            objective = "ring topology test",
            groupCount = 2,
            groupProfiles = listOf(
                com.achulkov.evomolior.core.model.GroupProfileType.Library,
                com.achulkov.evomolior.core.model.GroupProfileType.Factory
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
        assertEquals(2, lastRunGroupIds.size)
        assertTrue(lastRunGroupIds.all { it.startsWith("worker-group_") })
        assertEquals(
            listOf(
                com.achulkov.evomolior.core.model.GroupProfileType.Factory,
                com.achulkov.evomolior.core.model.GroupProfileType.Library
            ),
            observedProfileOrder
        )
        assertEquals("IDLE", service.getState().status.name)
    }

    @Test
    fun `star topology from council field configures dispatch neighbours`() = runTest {
        val tmpDir = createTempDirectory("svc-star-topology-test-").toFile()
        val substrateFactory = com.achulkov.evomolior.substrate.SubstrateDatabaseFactory(tmpDir)
        var capturedCoordinator: GroupCoordinator? = null
        val service = EvoMoliorRunService(
            lifecycleManagerProvider = { _, _, _, _, _ ->
                throw IllegalStateException("LifecycleManager should not be constructed after topology capture")
            },
            groupCoordinatorProvider = { groupIds, dimensions ->
                GroupCoordinator(groupIds, dimensions).also { capturedCoordinator = it }
            },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = substrateFactory,
            metaPlannerRunner = MetaPlannerRunner(),
            genesisCouncilRunner = {
                    runId,
                    objective,
                    _,
                    envelope,
                    inputArtifacts,
                    _,
                    onInvocationStarted ->
                onInvocationStarted()
                compiledCouncilTestFixture(
                    runId = runId,
                    objective = objective,
                    envelope = envelope,
                    inputArtifacts = inputArtifacts,
                    workerGroupKeys = listOf("north", "south"),
                    workerProfiles = listOf(
                        com.achulkov.evomolior.core.model.GroupProfileType.Library,
                        com.achulkov.evomolior.core.model.GroupProfileType.Factory
                    ),
                    topologyOrder = listOf("south", "north"),
                    topologyKind = GroupTopologyKind.STAR
                )
            }
        )

        val config = com.achulkov.evomolior.api.model.RunConfig(
            runId = "run-star-topology-test",
            objective = "star topology test",
            groupCount = 2,
            groupProfiles = listOf(
                com.achulkov.evomolior.core.model.GroupProfileType.Library,
                com.achulkov.evomolior.core.model.GroupProfileType.Factory
            ),
            rounds = 1,
            roundDurationMinutes = 1,
            maxAgentsPerGroup = 1,
            scoringDimensions = listOf(
                ScoringDimension(
                    id = "accuracy",
                    name = "Accuracy",
                    description = "Council topology should drive dispatch topology.",
                    weight = 1.0
                )
            ),
            openRouterApiKey = "sk-test"
        )

        val startResult = runCatching { service.startRun(config) }

        val coordinator = requireNotNull(capturedCoordinator) {
            "Coordinator should be created during startRun; failure=${startResult.exceptionOrNull()?.message}"
        }
        assertEquals(2, coordinator.groupIds.size)
        assertEquals(listOf(coordinator.groupIds[1]), coordinator.neighboursOf(coordinator.groupIds[0]))
        assertEquals(listOf(coordinator.groupIds[0]), coordinator.neighboursOf(coordinator.groupIds[1]))
    }

    @Test
    fun `high severity inspector flag spawns a single auditor per group`() = runTest {
        val tmpDir = createTempDirectory("svc-auditor-test-").toFile()
        val substrateFactory = com.achulkov.evomolior.substrate.SubstrateDatabaseFactory(tmpDir)
        val service = EvoMoliorRunService(
            lifecycleManagerProvider = { _, _, _, _, _ ->
                LifecycleManager(
                    runId = "run-auditor-test",
                    groupId = "group-0",
                    shuruVmManager = ShuruVmManager(),
                    substrateDatabaseFactory = substrateFactory,
                    agentJarPath = "/fake/agent.jar",
                    openRouterApiKey = "sk-test",
                    groupProfile = GroupProfile.default(com.achulkov.evomolior.core.model.GroupProfileType.Library),
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
            groupProfile = GroupProfile.default(com.achulkov.evomolior.core.model.GroupProfileType.Library),
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

        val config = com.achulkov.evomolior.api.model.RunConfig(
            runId = "run-auditor-test",
            objective = "auditor spawn",
            groupCount = 2,
            groupProfiles = listOf(
                com.achulkov.evomolior.core.model.GroupProfileType.Library,
                com.achulkov.evomolior.core.model.GroupProfileType.Factory
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
        val substrateFactory = com.achulkov.evomolior.substrate.SubstrateDatabaseFactory(tmpDir)
        val runId = "run-live-auditor"
        val groupId = "group-0"

        val service = EvoMoliorRunService(
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
                event is EvoMoliorEvent.EngineProgress &&
                    event.phase == "auditor_spawn"
            }
        }

        yield()
        service.startRun(
            com.achulkov.evomolior.api.model.RunConfig(
                runId = runId,
                objective = "live auditor path",
                groupCount = 2,
                groupProfiles = listOf(
                    com.achulkov.evomolior.core.model.GroupProfileType.Library,
                    com.achulkov.evomolior.core.model.GroupProfileType.Factory
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
                event is EvoMoliorEvent.RoundStarted &&
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
            auditorEvent is EvoMoliorEvent.EngineProgress &&
                auditorEvent.phase == "auditor_spawn",
            "The live run should emit an auditor_spawn progress event from the inspector/watchdog path"
        )

        runCatching { service.abortRun(runId, "cleanup after live auditor regression") }
    }

    @Test
    fun `turn-taking inspector flag spawns a perspective taker`() = runBlocking {
        val tmpDir = createTempDirectory("svc-perspective-taker-test-").toFile()
        val substrateFactory = com.achulkov.evomolior.substrate.SubstrateDatabaseFactory(tmpDir)
        val service = EvoMoliorRunService(
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

        val spawnedRoles = mutableListOf<Role>()

        val liveManagersField = service.javaClass.getDeclaredField("liveLifecycleManagers").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val liveManagers = liveManagersField.get(service) as MutableMap<String, LifecycleManager>
        val manager = LifecycleManager(
            runId = "run-perspective-taker",
            groupId = "group-0",
            shuruVmManager = ShuruVmManager(),
            substrateDatabaseFactory = substrateFactory,
            agentJarPath = "/fake/agent.jar",
            openRouterApiKey = "sk-test",
            groupProfile = GroupProfile.default(com.achulkov.evomolior.core.model.GroupProfileType.Library),
            localAgentRunner = { _, _ -> }
        )
        manager.onAgentSpawned = { _, role, _ -> spawnedRoles += role }
        liveManagers["group-0"] = manager

        val config = com.achulkov.evomolior.api.model.RunConfig(
            runId = "run-perspective-taker",
            objective = "perspective taker spawn",
            groupCount = 2,
            groupProfiles = listOf(
                com.achulkov.evomolior.core.model.GroupProfileType.Library,
                com.achulkov.evomolior.core.model.GroupProfileType.Factory
            ),
            rounds = 1,
            roundDurationMinutes = 1,
            maxAgentsPerGroup = 1,
            scoringDimensions = listOf(ScoringDimension(id = "d1", name = "d1", description = "d1", weight = 1.0)),
            openRouterApiKey = "sk-test"
        )

        service.handleInspectorFlag(
            config,
            WatchdogSignal.InspectorFlag(
                groupId = "group-0",
                severity = WatchdogSeverity.MEDIUM,
                description = "turn-taking imbalance detected in 1 recent flag(s)",
                suggestedRole = Role.PerspectiveTaker
            ),
            listOf("group-0")
        )
        service.handleInspectorFlag(
            config,
            WatchdogSignal.InspectorFlag(
                groupId = "group-0",
                severity = WatchdogSeverity.MEDIUM,
                description = "turn-taking imbalance detected in 1 recent flag(s)",
                suggestedRole = Role.PerspectiveTaker
            ),
            listOf("group-0")
        )

        assertTrue(spawnedRoles.contains(Role.PerspectiveTaker))
        assertEquals(1, spawnedRoles.count { it == Role.PerspectiveTaker })
    }

    @Test
    fun `failure telemetry is routed into grader staging`() = runTest {
        val tmpDir = createTempDirectory("svc-telemetry-test-").toFile()
        val substrateFactory = com.achulkov.evomolior.substrate.SubstrateDatabaseFactory(tmpDir)
        val service = EvoMoliorRunService(
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
    fun `budget critical watchdog signal reports generation pressure without pausing future rounds`() = runTest {
        val service = EvoMoliorRunService(
            lifecycleManagerProvider = { _, _, _, _, _ ->
                throw IllegalStateException("LifecycleManager should not be constructed in this budget test")
            },
            groupCoordinatorProvider = { groupIds, dimensions -> GroupCoordinator(groupIds, dimensions) },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = com.achulkov.evomolior.substrate.SubstrateDatabaseFactory(
                createTempDirectory("svc-budget-pause-test-").toFile()
            ),
            metaPlannerRunner = MetaPlannerRunner()
        )

        val config = com.achulkov.evomolior.api.model.RunConfig(
            runId = "run-budget-pause-test",
            objective = "budget pause",
            groupCount = 2,
            groupProfiles = listOf(
                com.achulkov.evomolior.core.model.GroupProfileType.Library,
                com.achulkov.evomolior.core.model.GroupProfileType.Factory
            ),
            rounds = 1,
            roundDurationMinutes = 1,
            maxAgentsPerGroup = 1,
            scoringDimensions = listOf(ScoringDimension(id = "d1", name = "d1", description = "d1", weight = 1.0)),
            openRouterApiKey = "sk-test"
        )

        val event = async {
            service.events.first { emitted ->
                emitted is EvoMoliorEvent.EngineProgress &&
                    emitted.phase == "generation_budget_critical"
            }
        }
        yield()
        service.handleWatchdogSignal(
            config = config,
            signal = WatchdogSignal.BudgetCritical(
                groupId = "group-0",
                remainingBudget = 4.0,
                generationBudget = 100.0,
                generation = 2
            ),
            groupIds = listOf("group-0")
        )

        val progress = event.await() as EvoMoliorEvent.EngineProgress
        assertTrue(progress.message.contains("Generation 2 budget critical"))
        assertFalse(service.getState().status.name == "PAUSED")
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
    @OptIn(kotlin.time.ExperimentalTime::class)
    fun `sealed suite evaluator executes compiled Council test definitions`() {
        val evaluator = SealedTestSuiteEvaluator()
        val json = Json
        val definition = """{"expectedOutput":{"electricalDemandKw":5.625},"input":{"cop":3.2}}"""
        val inputHash = com.achulkov.evomolior.core.atlas.AtlasHashing.hashText("""{"cop":3.2}""")
        val execution = com.achulkov.evomolior.core.atlas.ToolExecutionRecord(
            executionId = "promotion-execution",
            runId = "run-compiled-suite-test",
            toolVersionId = com.achulkov.evomolior.core.atlas.ToolVersionId("calculator-v1"),
            opportunityId = "calculator-opportunity",
            groupId = com.achulkov.evomolior.core.atlas.AtlasGroupId("group-0"),
            agentId = com.achulkov.evomolior.core.atlas.AtlasAgentId("critic"),
            round = 0,
            nodeId = com.achulkov.evomolior.core.atlas.NodeId("node-calculator"),
            purpose = com.achulkov.evomolior.core.atlas.ToolExecutionPurpose.PROMOTION_TEST,
            inputHash = inputHash,
            outputHash = "output-hash",
            outputJson = json.parseToJsonElement("""{"electricalDemandKw":5.625}"""),
            outputArtifactId = "promotion-output:calculator-v1:cooling-demand-boundary",
            startedAt = kotlin.time.Instant.fromEpochMilliseconds(1),
            completedAt = kotlin.time.Instant.fromEpochMilliseconds(2),
            durationMillis = 1,
            exitCode = 0,
            result = com.achulkov.evomolior.core.atlas.ToolExecutionResult.SUCCEEDED,
            sandboxRecordHash = "sandbox-hash"
        ).withComputedHash()
        val suiteJson = """
            {
              "schemaVersion": "sealed-tests-v2",
              "tests": [
                {
                  "id": "cooling-demand-boundary",
                  "nodeId": "node-calculator",
                  "kind": "PROMOTION",
                  "opportunityId": "calculator-opportunity",
                  "acceptedArtifactTypes": ["TOOL"],
                  "testDefinitionJson": ${json.encodeToString(definition)},
                  "commitmentHash": "${com.achulkov.evomolior.core.council.CouncilContractHashing.sha256(definition)}"
                }
              ]
            }
        """.trimIndent()

        val inspection = evaluator.inspectSuite(suiteJson)
        val assessment = evaluator.assessGroup(
            runId = "run-compiled-suite-test",
            groupId = "group-0",
            roundIndex = 0,
            suiteJson = suiteJson,
            suiteHash = "compiled-suite-hash",
            canonicalSnapshot = "No promotion output text is exposed here.",
            scoringDimensions = listOf(
                ScoringDimension(
                    id = "accuracy",
                    name = "Accuracy",
                    description = "Accuracy",
                    weight = 1.0,
                    maxScore = 100.0
                )
            ),
            toolExecutions = listOf(execution)
        )

        assertTrue(inspection.isComplete)
        assertEquals(1, inspection.declaredTestCount)
        assertEquals(1, assessment.automatedTestsPassed)
        assertEquals(0, assessment.automatedTestsFailed)
    }

    @Test
    fun `sealed suite inspection rejects commitment-only worker views before rounds`() {
        val inspection = SealedTestSuiteEvaluator().inspectSuite(
            """
            {
              "tests": [
                {
                  "id": "hidden-test",
                  "nodeId": "node-a",
                  "acceptedArtifactTypes": ["FINAL_DELIVERABLE"],
                  "commitmentHash": "deadbeef"
                }
              ]
            }
            """.trimIndent()
        )

        assertFalse(inspection.isComplete)
        assertEquals(1, inspection.declaredTestCount)
        assertEquals(0, inspection.executableTestCount)
    }

    @Test
    fun `sealed suite inspection rejects compiled definitions without commitment authority`() {
        val inspection = SealedTestSuiteEvaluator().inspectSuite(
            """
            {
              "schemaVersion": "sealed-tests-v2",
              "tests": [
                {
                  "id": "uncommitted-general-test",
                  "nodeId": "node-general",
                  "kind": "GENERAL",
                  "acceptedArtifactTypes": ["DOCUMENT"],
                  "testDefinitionJson": "{\"contains\":\"required evidence\"}"
                }
              ]
            }
            """.trimIndent()
        )

        assertFalse(inspection.isComplete)
        assertEquals(1, inspection.declaredTestCount)
        assertEquals(0, inspection.executableTestCount)
    }

    @Test
    fun `Appendix B compiled inspection rejects legacy compatibility suites`() {
        val inspection = SealedTestSuiteEvaluator().inspectCompiledSuite(
            """
            {
              "tests": [
                {
                  "id": "legacy-flat-test",
                  "expectedOutputPattern": "legacy evidence"
                }
              ]
            }
            """.trimIndent()
        )

        assertFalse(inspection.isComplete)
        assertEquals(0, inspection.executableTestCount)
        assertTrue(
            inspection.failures.any {
                it.contains("UNSUPPORTED_EXECUTABLE_TEST_VERSION")
            }
        )
    }

    @Test
    fun `Appendix B compiled inspection rejects a blank sealed suite`() {
        val inspection = SealedTestSuiteEvaluator().inspectCompiledSuite("")

        assertFalse(inspection.isComplete)
        assertEquals(0, inspection.declaredTestCount)
        assertEquals(0, inspection.executableTestCount)
    }

    @Test
    fun `sealed suite grading sheet normalizes relative scoring weights`() {
        val evaluator = SealedTestSuiteEvaluator()
        val sheet = evaluator.buildGradingSheet(
            runId = "run-relative-weight-suite-test",
            roundIndex = 0,
            suiteJson = """{
              "tests": [
                {
                  "id": "must-have-alpha",
                  "description": "snapshot must mention alpha",
                  "expectedOutputPattern": "alpha",
                  "isMandatory": true
                }
              ]
            }""",
            suiteHash = "suite-hash-test",
            scoringDimensions = listOf(
                ScoringDimension(
                    id = "correctness",
                    name = "Correctness",
                    description = "Correctness",
                    weight = 1.35
                ),
                ScoringDimension(
                    id = "evidence_quality",
                    name = "Evidence quality",
                    description = "Evidence quality",
                    weight = 0.8
                )
            )
        )

        val weights = sheet.rubricDimensions.associate { it.dimensionId to it.weight }
        assertTrue(kotlin.math.abs(weights.values.sum() - 1.0) < 0.000000001)
        assertEquals(1.35 / 2.15, weights.getValue("correctness"), 0.000000001)
        assertEquals(0.8 / 2.15, weights.getValue("evidence_quality"), 0.000000001)
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
    fun `run service skips sealed suite assessment when no suite is active`() = runTest {
        val tmpDir = createTempDirectory("svc-blank-suite-test-").toFile()
        val service = EvoMoliorRunService(
            lifecycleManagerProvider = { _, _, _, _, _ ->
                throw IllegalStateException("LifecycleManager should not be constructed in this sealed-suite test")
            },
            groupCoordinatorProvider = { groupIds, dimensions ->
                GroupCoordinator(groupIds, dimensions)
            },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = com.achulkov.evomolior.substrate.SubstrateDatabaseFactory(tmpDir)
        )
        val method = service::class.declaredFunctions
            .first { it.name == "activeSealedSuiteAssessment" }
            .apply { isAccessible = true }

        val result = method.callSuspend(
            service,
            "run-blank-suite",
            "group-0",
            0,
            "canonical snapshot",
            null,
            listOf(
                ScoringDimension(
                    id = "accuracy",
                    name = "Accuracy",
                    description = "Accuracy",
                    weight = 1.0,
                    maxScore = 100.0
                )
            )
        )

        assertEquals(null, result)
    }

    @Test
    fun `run service evaluates v3 plans from in-memory output and builds contextual sheets`() =
        runTest {
            val tmpDir = createTempDirectory("svc-v3-runtime-wiring-").toFile()
            val substrateFactory =
                com.achulkov.evomolior.substrate.SubstrateDatabaseFactory(tmpDir)
            val service = EvoMoliorRunService(
                lifecycleManagerProvider = { _, _, _, _, _ ->
                    error("runtime activation is not part of this wiring test")
                },
                groupCoordinatorProvider = { groupIds, dimensions ->
                    GroupCoordinator(groupIds, dimensions)
                },
                graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
                substrateFactory = substrateFactory
            )
            val runId = "v3-runtime-wiring"
            val objective = "Evaluate one exact receiver output."
            val dimensions = listOf(
                ScoringDimension(
                    id = "quality",
                    name = "Quality",
                    description = "Exact sealed-test quality.",
                    weight = 1.0,
                    maxScore = 100.0
                )
            )
            val config = com.achulkov.evomolior.api.model.RunConfig(
                runId = runId,
                objective = objective,
                groupCount = 2,
                groupProfiles = listOf(
                    com.achulkov.evomolior.core.model.GroupProfileType.Library,
                    com.achulkov.evomolior.core.model.GroupProfileType.Factory
                ),
                rounds = 1,
                roundDurationMinutes = 0,
                maxAgentsPerGroup = 1,
                scoringDimensions = dimensions,
                openRouterApiKey = "sk-test",
                finalOutputSpec = com.achulkov.evomolior.api.model.FinalOutputSpec(
                    relativePath = "answer.md",
                    mediaType = "text/markdown"
                )
            )
            val compiled = compiledCouncilTestFixture(
                runId = runId,
                objective = objective,
                envelope = CouncilExecutionEnvelopeFactory.create(config)
            )
            service.javaClass.getDeclaredField("activeSealedTestSuiteJson")
                .apply { isAccessible = true }
                .set(service, compiled.graderField.sealedTestSuiteJson)
            service.javaClass.getDeclaredField("activeSealedTestSuiteHash")
                .apply { isAccessible = true }
                .set(service, compiled.graderField.sealedTestSuiteHash)
            service.javaClass.getDeclaredField("activeSealedTestSuiteValidationContext")
                .apply { isAccessible = true }
                .set(service, compiled.sealedTests.validationContext)

            val assessmentMethod = service::class.declaredFunctions
                .first { it.name == "activeSealedSuiteAssessment" }
                .apply { isAccessible = true }
            val passing = assessmentMethod.callSuspend(
                service,
                runId,
                compiled.workerField.ringTopology.first(),
                0,
                "canonical snapshot intentionally lacks the receiver output",
                "ROUND SUBMISSION SUMMARY\nreceiver-delivery-accepted",
                dimensions
            ) as DeterministicRoundAssessment
            val failing = assessmentMethod.callSuspend(
                service,
                runId,
                compiled.workerField.ringTopology.first(),
                0,
                "receiver-delivery-accepted appears only in the legacy snapshot",
                "ROUND SUBMISSION SUMMARY\nnot accepted",
                dimensions
            ) as DeterministicRoundAssessment

            assertEquals(1, passing.automatedTestsPassed)
            assertEquals(0, passing.automatedTestsFailed)
            assertEquals(0, failing.automatedTestsPassed)
            assertEquals(1, failing.automatedTestsFailed)

            val sheetMethod = service::class.declaredFunctions
                .first { it.name == "buildBlindScoringSheets" }
                .apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val sheets = sheetMethod.call(service, config, 0) as List<GradingSheet>
            assertEquals("delivery-test", sheets.single().automatedTests.single().testId)

            val finalValidationMethod = service::class.declaredFunctions
                .first { it.name == "validateStagedFinalOutput" }
                .apply { isAccessible = true }
            val outputSpec = assertNotNull(config.finalOutputSpec)
            finalValidationMethod.callSuspend(
                service,
                GeneratedOutputFile(
                    relativePath = outputSpec.relativePath,
                    mediaType = outputSpec.mediaType,
                    content = "receiver-delivery-accepted"
                ),
                outputSpec,
                objective,
                emptyList<com.achulkov.evomolior.core.contract.GeneratedImageAsset>(),
                runId,
                compiled.workerField.ringTopology.first(),
                0,
                dimensions
            )
            val finalFailure = assertFailsWith<IllegalArgumentException> {
                finalValidationMethod.callSuspend(
                    service,
                    GeneratedOutputFile(
                        relativePath = outputSpec.relativePath,
                        mediaType = outputSpec.mediaType,
                        content = "not accepted"
                    ),
                    outputSpec,
                    objective,
                    emptyList<com.achulkov.evomolior.core.contract.GeneratedImageAsset>(),
                    runId,
                    compiled.workerField.ringTopology.first(),
                    0,
                    dimensions
                )
            }
            assertTrue(
                finalFailure.message.orEmpty().contains("sealed-tests-v3 authority")
            )
        }

    @Test
    fun `publishBlindRoundResult uses sealed suite telemetry end to end`() = runTest {
        val tmpDir = createTempDirectory("svc-blind-round-test-").toFile()
        val substrateFactory = com.achulkov.evomolior.substrate.SubstrateDatabaseFactory(tmpDir)
        val service = EvoMoliorRunService(
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
        assessments["group-0"] = com.achulkov.evomolior.api.service.DeterministicRoundAssessment(
            dimensionScores = listOf(
                com.achulkov.evomolior.core.model.DimensionScore(
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

        val config = com.achulkov.evomolior.api.model.RunConfig(
            runId = "run-blind-round-test",
            objective = "blind round test",
            groupCount = 1,
            groupProfiles = listOf(com.achulkov.evomolior.core.model.GroupProfileType.Library),
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
        val result = method.callSuspend(service, config, listOf("group-0"), 0) as com.achulkov.evomolior.core.model.GradingResult
        val score = result.scores.single { it.groupId == "group-0" }
        assertTrue(score.weightedTotal < 100.0, "Failed automated tests must change the blind reveal score")
        assertTrue(score.weightedTotal > 99.0, "The sealed-suite penalty should be small but non-zero for one failed mandatory test")
        assertEquals(1, score.dimensionScores.size)
        assertTrue(assessments.containsKey("group-0"))

        blindTournamentService.stop()
    }

    @Test
    fun `publishBlindRoundResult uses dimension rubric when sealed suite is absent`() = runTest {
        val tmpDir = createTempDirectory("svc-blind-dimension-rubric-test-").toFile()
        val substrateFactory = com.achulkov.evomolior.substrate.SubstrateDatabaseFactory(tmpDir)
        val service = EvoMoliorRunService(
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
        blindTournamentService.start(listOf("group-0", "group-1"))
        val tournamentField = service.javaClass.getDeclaredField("activeBlindTournamentService").apply {
            isAccessible = true
        }
        tournamentField.set(service, blindTournamentService)
        val dimensionScoresField = service.javaClass.getDeclaredField("latestGroupDimensionScores").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val dimensionScores = dimensionScoresField.get(service) as MutableMap<String, Map<String, Double>>
        dimensionScores["group-0"] = mapOf("correctness" to 9.0, "evidence_quality" to 8.0)
        dimensionScores["group-1"] = mapOf("correctness" to 4.0, "evidence_quality" to 3.0)

        val config = com.achulkov.evomolior.api.model.RunConfig(
            runId = "run-blind-dimension-rubric-test",
            objective = "blind dimension rubric test",
            groupCount = 2,
            groupProfiles = listOf(
                com.achulkov.evomolior.core.model.GroupProfileType.Library,
                com.achulkov.evomolior.core.model.GroupProfileType.Factory
            ),
            rounds = 1,
            roundDurationMinutes = 1,
            maxAgentsPerGroup = 1,
            scoringDimensions = listOf(
                ScoringDimension(
                    id = "correctness",
                    name = "Correctness",
                    description = "Correctness",
                    weight = 1.35,
                    maxScore = 10.0
                ),
                ScoringDimension(
                    id = "evidence_quality",
                    name = "Evidence quality",
                    description = "Evidence quality",
                    weight = 0.8,
                    maxScore = 10.0
                )
            ),
            openRouterApiKey = "sk-test",
            isTestRun = true
        )

        val method = service::class.declaredFunctions.first { it.name == "publishBlindRoundResult" }.apply {
            isAccessible = true
        }
        val result = method.callSuspend(
            service,
            config,
            listOf("group-0", "group-1"),
            0
        ) as com.achulkov.evomolior.core.model.GradingResult

        assertEquals("group-0", result.winnerId)
        assertTrue(result.acceptedRefinements.isEmpty())
        assertFalse(
            result.scores.flatMap { it.dimensionScores }
                .any { it.justification.contains("Synthetic fallback") },
            "Dimension-only rubric should keep blind scoring on the MetaJudge path."
        )

        blindTournamentService.stop()
    }

    @Test
    fun `authoritative grader scores control blind winner and discriminator feedback`() = runTest {
        val tmpDir = createTempDirectory("svc-authoritative-grader-score-test-").toFile()
        val service = EvoMoliorRunService(
            lifecycleManagerProvider = { _, _, _, _, _ ->
                throw IllegalStateException("LifecycleManager should not be constructed in this scoring test")
            },
            groupCoordinatorProvider = { groupIds, dimensions ->
                GroupCoordinator(groupIds, dimensions)
            },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = com.achulkov.evomolior.substrate.SubstrateDatabaseFactory(tmpDir),
            metaPlannerRunner = MetaPlannerRunner()
        )
        val groupIds = listOf("group-0", "group-1")
        val blindTournamentService = BlindTournamentService(onFailureTelemetry = {})
        blindTournamentService.start(groupIds)
        service.javaClass.getDeclaredField("activeBlindTournamentService")
            .apply { isAccessible = true }
            .set(service, blindTournamentService)

        @Suppress("UNCHECKED_CAST")
        val fallbackScores = service.javaClass.getDeclaredField("latestGroupDimensionScores")
            .apply { isAccessible = true }
            .get(service) as MutableMap<String, Map<String, Double>>
        fallbackScores["group-0"] = mapOf("accuracy" to 9.0)
        fallbackScores["group-1"] = mapOf("accuracy" to 2.0)

        val graderResult = com.achulkov.evomolior.core.model.GradingResult(
            roundIndex = 0,
            scores = listOf(
                com.achulkov.evomolior.core.model.RoundScore(
                    groupId = "group-1",
                    roundIndex = 0,
                    dimensionScores = listOf(
                        com.achulkov.evomolior.core.model.DimensionScore(
                            "accuracy",
                            8.0,
                            "RoundJudge cited group-1 artifact evidence."
                        )
                    ),
                    weightedTotal = 80.0
                ),
                com.achulkov.evomolior.core.model.RoundScore(
                    groupId = "group-0",
                    roundIndex = 0,
                    dimensionScores = listOf(
                        com.achulkov.evomolior.core.model.DimensionScore(
                            "accuracy",
                            3.0,
                            "RoundJudge found weaker group-0 evidence."
                        )
                    ),
                    weightedTotal = 30.0
                )
            ),
            winnerId = "group-1"
        )
        @Suppress("UNCHECKED_CAST")
        val authoritativeResults = service.javaClass.getDeclaredField("authoritativeGraderRoundResults")
            .apply { isAccessible = true }
            .get(service) as MutableMap<Int, com.achulkov.evomolior.core.model.GradingResult>
        authoritativeResults[0] = graderResult

        val config = com.achulkov.evomolior.api.model.RunConfig(
            runId = "run-authoritative-grader-score-test",
            objective = "authoritative grader scoring",
            groupCount = 2,
            groupProfiles = listOf(
                com.achulkov.evomolior.core.model.GroupProfileType.Library,
                com.achulkov.evomolior.core.model.GroupProfileType.Factory
            ),
            rounds = 1,
            roundDurationMinutes = 1,
            maxAgentsPerGroup = 1,
            scoringDimensions = listOf(
                ScoringDimension(
                    id = "accuracy",
                    name = "Accuracy",
                    description = "Accuracy",
                    weight = 1.0,
                    maxScore = 10.0
                )
            ),
            openRouterApiKey = "sk-test",
            isTestRun = true
        )

        val publish = service::class.declaredFunctions
            .first { it.name == "publishBlindRoundResult" }
            .apply { isAccessible = true }
        val result = publish.callSuspend(service, config, groupIds, 0) as
            com.achulkov.evomolior.core.model.GradingResult

        assertEquals("group-1", result.winnerId)
        assertEquals(
            "RoundJudge cited group-1 artifact evidence.",
            result.scores.first { it.groupId == "group-1" }.dimensionScores.single().justification
        )

        val updateBriefs = service::class.declaredFunctions
            .first { it.name == "updateAdversarialDiscriminatorBriefs" }
            .apply { isAccessible = true }
        updateBriefs.callSuspend(service, config.runId, 0, result, emptyMap<String, String?>())
        @Suppress("UNCHECKED_CAST")
        val briefs = service.javaClass.getDeclaredField("adversarialDiscriminatorBriefs")
            .apply { isAccessible = true }
            .get(service) as Map<String, String>
        assertTrue(briefs.getValue("group-1").contains("Rank: 1 / winner=group-1"))
        assertTrue(briefs.getValue("group-0").contains("Rank: 2 / winner=group-1"))

        blindTournamentService.stop()
    }

    @Test
    fun `codex test runs use production final deliverable attempt timeout`() {
        val service = EvoMoliorRunService(
            lifecycleManagerProvider = { _, _, _, _, _ ->
                throw IllegalStateException("LifecycleManager should not be constructed in this timeout test")
            },
            groupCoordinatorProvider = { groupIds, dimensions ->
                GroupCoordinator(groupIds, dimensions)
            },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = com.achulkov.evomolior.substrate.SubstrateDatabaseFactory(
                createTempDirectory("svc-codex-timeout-test-").toFile()
            ),
            metaPlannerRunner = MetaPlannerRunner()
        )
        val method = service::class.declaredFunctions.first { it.name == "finalDeliverableAttemptTimeoutMs" }.apply {
            isAccessible = true
        }
        val config = com.achulkov.evomolior.api.model.RunConfig(
            runId = "run-codex-timeout-test",
            objective = "codex timeout test",
            groupCount = 1,
            groupProfiles = listOf(com.achulkov.evomolior.core.model.GroupProfileType.Library),
            rounds = 1,
            roundDurationMinutes = 1,
            maxAgentsPerGroup = 1,
            scoringDimensions = listOf(
                ScoringDimension(
                    id = "accuracy",
                    name = "Accuracy",
                    description = "Accuracy",
                    weight = 1.0
                )
            ),
            openRouterApiKey = "sk-test",
            llmBackend = com.achulkov.evomolior.core.config.LlmBackend.CODEX_CHATGPT,
            isTestRun = true
        )

        assertEquals(300_000L, method.call(service, config))
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

    private fun fullAcceptanceConfig(runId: String): RunConfig = RunConfig(
        runId = runId,
        objective = "Produce a receiver-usable result.",
        groupCount = 2,
        groupProfiles = listOf(
            com.achulkov.evomolior.core.model.GroupProfileType.Library,
            com.achulkov.evomolior.core.model.GroupProfileType.Factory
        ),
        rounds = 6,
        roundDurationMinutes = 1,
        maxAgentsPerGroup = 1,
        scoringDimensions = listOf(
            ScoringDimension(
                id = "quality",
                name = "Quality",
                description = "Receiver quality",
                weight = 1.0
            )
        ),
        openRouterApiKey = "/tmp/codex",
        llmBackend = LlmBackend.CODEX_CHATGPT,
        councilExecutionPurpose = CouncilExecutionPurpose.APPENDIX_B_ACCEPTANCE,
        acceptanceProfile = AcceptanceProfile.APPENDIX_B_FULL,
        finalOutputSpec = FinalOutputSpec(
            relativePath = "receiver/result.md",
            mediaType = "text/markdown"
        )
    )

    private fun makeManager(withLocalRunner: Boolean): LifecycleManager {
        val tmpDir = createTempDirectory("svc-mode-test-").toFile()
        val substrateFactory = com.achulkov.evomolior.substrate.SubstrateDatabaseFactory(tmpDir)
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
            groupProfile = GroupProfile.default(com.achulkov.evomolior.core.model.GroupProfileType.Library),
            localAgentRunner = runner
        )
    }
}
