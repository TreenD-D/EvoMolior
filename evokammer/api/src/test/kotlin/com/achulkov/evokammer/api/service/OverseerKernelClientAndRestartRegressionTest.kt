package com.achulkov.evokammer.api.service

import com.achulkov.evokammer.core.contract.OverseerAcceptanceCriteria
import com.achulkov.evokammer.core.contract.OverseerDeadEnd
import com.achulkov.evokammer.api.model.OverseerExperimentLaunchRequest
import com.achulkov.evokammer.api.model.OverseerExperimentLaunchResponse
import com.achulkov.evokammer.core.contract.OverseerExperimentOutcome
import com.achulkov.evokammer.core.contract.OverseerExperimentPlanHistoryActionType
import com.achulkov.evokammer.core.contract.OverseerExpectedEffect
import com.achulkov.evokammer.core.contract.OverseerExperimentMetrics
import com.achulkov.evokammer.core.contract.OverseerExperimentPlan
import com.achulkov.evokammer.core.contract.OverseerExperimentPlanEligibility
import com.achulkov.evokammer.core.contract.OverseerExperimentResult
import com.achulkov.evokammer.core.contract.OverseerExperimentSchedule
import com.achulkov.evokammer.core.contract.OverseerHypothesis
import com.achulkov.evokammer.core.contract.OverseerHypothesisStatus
import com.achulkov.evokammer.core.contract.OverseerKernelEligibility
import com.achulkov.evokammer.core.contract.OverseerKernelState
import com.achulkov.evokammer.core.contract.OverseerMutabilityLevel
import com.achulkov.evokammer.core.contract.OverseerOrigin
import com.achulkov.evokammer.core.contract.OverseerOriginType
import com.achulkov.evokammer.core.contract.OverseerTargetScope
import com.achulkov.evokammer.core.contract.OverseerVariationSpec
import com.achulkov.evokammer.core.contract.OverseerKernelAuditActionType
import com.achulkov.evokammer.core.contract.OverseerKernelAuditEntry
import com.achulkov.evokammer.core.contract.OverseerKernelArchive
import com.achulkov.evokammer.substrate.OverseerKernelStateStore
import com.achulkov.evokammer.substrate.OverseerKernelAuditStore
import com.achulkov.evokammer.substrate.SubstrateDatabaseFactory
import com.achulkov.evokammer.substrate.db.entity.MetaArchiveEntryEntity
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.delay
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.net.http.HttpClient
import java.net.ServerSocket

class OverseerKernelClientAndRestartRegressionTest {
    private var server: EvoKammerIpcServer? = null

    @AfterTest
    fun tearDown() {
        server?.stop()
        server = null
    }

    @Test
    fun `overseer state survives engine and ipc restart through the typed client`() = kotlinx.coroutines.runBlocking {
        val factory = SubstrateDatabaseFactory(createTempDirectory("overseer-restart-test-").toFile())
        val port = freePort()

        server = EvoKammerIpcServer(PersistentOverseerEngineService(factory), port).also { it.start() }
        waitForServer(port)

        val client = OverseerKernelClient(baseUrl = "http://localhost:$port", httpClient = HttpClient.newHttpClient())
        val hypothesis = hypothesis()
        val preflight = client.preflightHypothesis(hypothesis)
        assertTrue(preflight.allowed)
        val planned = OverseerExperimentPlan(
            id = "EXP-restart-1",
            hypothesisId = hypothesis.id,
            runCount = 1,
            generationsPerRun = 1,
            variationSpec = OverseerVariationSpec(configChanges = listOf("raise selector budget")),
            metrics = OverseerExperimentMetrics(primary = listOf("visibility")),
            acceptanceCriteria = OverseerAcceptanceCriteria(predicates = listOf("visibility > 0.5")),
            maxCost = 1.0
        )
        val planPreflight = client.preflightExperimentPlan(planned)
        assertTrue(planPreflight.allowed)

        val formalized = client.formalizeHypothesis(hypothesis)
        assertEquals(OverseerHypothesisStatus.FORMALIZED, formalized.hypotheses.getValue(hypothesis.id).status)
        client.registerExperimentPlan(planned)
        val scheduledState = client.scheduleExperimentPlans()
        assertEquals(1, scheduledState.experimentSchedules.size)
        assertEquals(
            listOf("EXP-restart-1-run-01"),
            scheduledState.experimentSchedules.getValue("EXP-restart-1").scheduledRunIds
        )
        val launched = client.launchExperimentRun(
            experimentPlanId = planned.id,
            launchRequest = OverseerExperimentLaunchRequest(
                scheduledRunId = "EXP-restart-1-run-01",
                runConfig = com.achulkov.evokammer.api.model.RunConfig(
                    runId = "EXP-restart-1-run-01",
                    objective = "restart regression launch",
                    groupCount = 2,
                    groupProfiles = listOf(
                        com.achulkov.evokammer.core.model.GroupProfileType.Library,
                        com.achulkov.evokammer.core.model.GroupProfileType.Factory
                    ),
                    rounds = 1,
                    roundDurationMinutes = 0,
                    maxAgentsPerGroup = 1,
                    scoringDimensions = listOf(
                        com.achulkov.evokammer.core.model.ScoringDimension(
                            id = "visibility",
                            name = "Visibility",
                            description = "Reward launch visibility.",
                            weight = 1.0
                        )
                    ),
                    openRouterApiKey = "sk-test",
                    isTestRun = true
                )
            )
        )
        assertEquals("EXP-restart-1", launched.experimentPlanId)
        assertEquals("EXP-restart-1-run-01", launched.scheduledRunId)
        assertEquals("EXP-restart-1-run-01", launched.runId)
        val launchedState = client.getState()
        assertTrue(launchedState.experimentSchedules.getValue("EXP-restart-1").launchedAtMs != null)
        assertFailsWith<IllegalArgumentException> {
            client.launchExperimentRun(
                experimentPlanId = planned.id,
                launchRequest = OverseerExperimentLaunchRequest(
                    scheduledRunId = "EXP-restart-1-run-01",
                    runConfig = com.achulkov.evokammer.api.model.RunConfig(
                        runId = "EXP-restart-1-run-01",
                        objective = "restart regression launch",
                        groupCount = 2,
                        groupProfiles = listOf(
                            com.achulkov.evokammer.core.model.GroupProfileType.Library,
                            com.achulkov.evokammer.core.model.GroupProfileType.Factory
                        ),
                        rounds = 1,
                        roundDurationMinutes = 0,
                        maxAgentsPerGroup = 1,
                        scoringDimensions = listOf(
                            com.achulkov.evokammer.core.model.ScoringDimension(
                                id = "visibility",
                                name = "Visibility",
                                description = "Reward launch visibility.",
                                weight = 1.0
                            )
                        ),
                        openRouterApiKey = "sk-test",
                        isTestRun = true
                    )
                )
            )
        }
        val acceptedState = client.recordExperimentResult(
            OverseerExperimentResult(
                experimentPlanId = planned.id,
                hypothesisId = hypothesis.id,
                outcome = OverseerExperimentOutcome.ACCEPTED,
                metricValues = mapOf("visibility" to 0.93)
            )
        )
        assertEquals(OverseerHypothesisStatus.ACCEPTED, acceptedState.hypotheses.getValue(hypothesis.id).status)
        client.recordDeadEnd(OverseerDeadEnd(hypothesisId = hypothesis.id, reason = "blocked by immutable core"))
        val auditBeforeRestart = client.getAuditEntries()
        assertTrue(auditBeforeRestart.size >= 3)

        server?.stop()
        server = EvoKammerIpcServer(PersistentOverseerEngineService(factory), port).also { it.start() }
        waitForServer(port)

        val reloaded = client.getState()
        assertTrue(reloaded.hypotheses.containsKey(hypothesis.id))
        assertTrue(reloaded.experimentPlans.containsKey(planned.id))
        assertTrue(reloaded.deadEnds.containsKey(hypothesis.id))
        assertTrue(reloaded.experimentSchedules.getValue(planned.id).launchedAtMs != null)
        assertTrue(client.getExperimentPlanHistory(planned.id).any { it.actionType == OverseerExperimentPlanHistoryActionType.LAUNCHED })
        assertTrue(client.getDeadEndHistory(hypothesis.id).isNotEmpty())
        val auditAfterRestart = client.getAuditEntries()
        assertTrue(auditAfterRestart.size >= auditBeforeRestart.size)
        assertTrue(auditAfterRestart.any { it.actionType.name == "RECORD_DEAD_END" })
        assertTrue(auditAfterRestart.any { it.actionType == OverseerKernelAuditActionType.LAUNCH_EXPERIMENT_RUN })

        val archiveMirror = client.getAuditArchiveEntries()
        assertEquals(6, archiveMirror.size)
        assertTrue(archiveMirror.any { it.contentJson.contains("SCHEDULE_EXPERIMENT_PLANS") })
        assertTrue(archiveMirror.any { it.contentJson.contains("LAUNCH_EXPERIMENT_RUN") })
        assertTrue(archiveMirror.any { it.contentJson.contains("RECORD_EXPERIMENT_RESULT") })
        assertTrue(archiveMirror.any { it.contentJson.contains(hypothesis.id) })
    }

    private fun hypothesis(): OverseerHypothesis = OverseerHypothesis(
        id = "HYP-restart-1",
        title = "Restart regression hypothesis",
        origin = OverseerOrigin(
            type = OverseerOriginType.INTERNAL_LOGS,
            sources = listOf("documentation/EvoKammer_Current_Session_Handoff.md")
        ),
        targetScope = OverseerTargetScope(
            paths = listOf("evokammer/core/src/main/resources/overseer/policy.json"),
            mutabilityLevel = OverseerMutabilityLevel.SEMI_MUTABLE
        ),
        rationale = "Prove persistence across engine restarts.",
        expectedEffect = OverseerExpectedEffect(metricsUp = listOf("visibility"))
    )

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private suspend fun waitForServer(port: Int) {
        repeat(50) {
            try {
                HttpClient.newHttpClient().send(
                    java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create("http://localhost:$port/state"))
                        .GET()
                        .build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString()
                )
                return
            } catch (_: Exception) {
                delay(50)
            }
        }
        error("Server on port $port did not become ready")
    }

    private class PersistentOverseerEngineService(
        private val factory: SubstrateDatabaseFactory
    ) : EvoKammerEngineService {
        private val store = OverseerKernelStateStore(factory)
        private val auditStore = OverseerKernelAuditStore(factory)
        private val archiveDb = factory.get(OverseerKernelArchive.RUN_ID, OVERSEER_KERNEL_ARCHIVE_GROUP_ID)
        private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
        private val eventsFlow = MutableSharedFlow<com.achulkov.evokammer.api.events.EvoKammerEvent>(extraBufferCapacity = 8)

        override val events = eventsFlow.asSharedFlow()

        override suspend fun startRun(config: com.achulkov.evokammer.api.model.RunConfig): String =
            error("Not used in this regression")

        override suspend fun resumePersistedRun(config: com.achulkov.evokammer.api.model.RunConfig): String =
            error("Not used in this regression")

        override suspend fun pauseRun(runId: String) = Unit

        override suspend fun resumeRun(runId: String) = Unit

        override suspend fun abortRun(runId: String, reason: String) = Unit

        override suspend fun getState(): EngineState =
            EngineState(EngineStatus.IDLE, null, null, emptyList())

        override suspend fun getMetaArchiveSummary(runId: String): MetaArchiveSummary? = null

        override suspend fun getMetaArchiveEntries(runId: String, entryType: String?): List<MetaArchiveEntryRecord> =
            if (runId == OverseerKernelArchive.RUN_ID) {
                archiveDb.metaArchiveDao().getByType(entryType ?: RunArchiveService.ENTRY_OVERSEER_AUDIT).map { entry ->
                    MetaArchiveEntryRecord(
                        id = entry.id,
                        runId = entry.runId,
                        entryType = entry.entryType,
                        groupId = entry.groupId,
                        label = entry.label,
                        contentJson = entry.contentJson,
                        runObjective = entry.runObjective,
                        finalScore = entry.finalScore,
                        isFromWinner = entry.isFromWinner,
                        generation = entry.generation,
                        chunkIndex = entry.chunkIndex,
                        totalChunks = entry.totalChunks,
                        archivedAtMs = entry.archivedAtMs
                    )
                }
            } else {
                emptyList()
            }

        override suspend fun getResultsBundle(runId: String): ByteArray? = null

        override suspend fun getOverseerKernelState(): OverseerKernelState = store.read() ?: OverseerKernelState()

        override suspend fun preflightOverseerHypothesis(hypothesis: OverseerHypothesis): com.achulkov.evokammer.core.contract.OverseerKernelEligibility =
            OverseerKernelEligibility(allowed = true)

        override suspend fun preflightOverseerExperimentPlan(experimentPlan: OverseerExperimentPlan): OverseerExperimentPlanEligibility =
            OverseerExperimentPlanEligibility(allowed = true)

        override suspend fun formalizeOverseerHypothesis(hypothesis: OverseerHypothesis): OverseerKernelState =
            persistAudit(
                actionType = OverseerKernelAuditActionType.FORMALIZE_HYPOTHESIS,
                hypothesisId = hypothesis.id
            ) {
                store.update { current ->
                    current.copy(
                        hypotheses = current.hypotheses + (hypothesis.id to hypothesis.copy(status = OverseerHypothesisStatus.FORMALIZED))
                    )
                }
            }

        override suspend fun registerOverseerExperimentPlan(experimentPlan: OverseerExperimentPlan): OverseerKernelState =
            persistAudit(
                actionType = OverseerKernelAuditActionType.REGISTER_EXPERIMENT_PLAN,
                experimentPlanId = experimentPlan.id
            ) {
                store.update { current ->
                    current.copy(
                        experimentPlans = current.experimentPlans + (experimentPlan.id to experimentPlan),
                        hypotheses = current.hypotheses + (
                            experimentPlan.hypothesisId to current.hypotheses.getValue(experimentPlan.hypothesisId).copy(
                                experimentPlanId = experimentPlan.id,
                                status = OverseerHypothesisStatus.UNDER_EXPERIMENT
                            )
                        ),
                        experimentPlanHistory = current.experimentPlanHistory + (
                            experimentPlan.id to (
                                current.experimentPlanHistory[experimentPlan.id].orEmpty() +
                                    com.achulkov.evokammer.core.contract.OverseerExperimentPlanHistoryEntry(
                                        experimentPlanId = experimentPlan.id,
                                        hypothesisId = experimentPlan.hypothesisId,
                                        actionType = OverseerExperimentPlanHistoryActionType.REGISTERED
                                    )
                            )
                        )
                    )
                }
            }

        override suspend fun scheduleOverseerExperimentPlans(): OverseerKernelState =
            persistAudit(
                actionType = OverseerKernelAuditActionType.SCHEDULE_EXPERIMENT_PLANS,
                note = "Scheduled eligible experiment plans"
            ) {
                store.update { current ->
                    val scheduled = current.experimentPlans.values
                        .sortedBy { it.id }
                        .filter { current.experimentSchedules[it.id] == null }
                        .filter { plan ->
                            current.hypotheses[plan.hypothesisId]?.status in setOf(
                                OverseerHypothesisStatus.FORMALIZED,
                                OverseerHypothesisStatus.UNDER_EXPERIMENT
                            )
                        }
                        .associate { plan ->
                            plan.id to OverseerExperimentSchedule(
                                experimentPlanId = plan.id,
                                hypothesisId = plan.hypothesisId,
                                runCount = plan.runCount,
                                generationsPerRun = plan.generationsPerRun,
                                scheduledRunIds = (1..plan.runCount).map { index -> "${plan.id}-run-${index.toString().padStart(2, '0')}" }
                            )
                        }
                    if (scheduled.isEmpty()) {
                        current
                    } else {
                        current.copy(
                            experimentSchedules = current.experimentSchedules + scheduled,
                            experimentPlanHistory = scheduled.values.fold(current.experimentPlanHistory) { acc, plan ->
                                acc + (
                                    plan.experimentPlanId to (
                                        acc[plan.experimentPlanId].orEmpty() +
                                            com.achulkov.evokammer.core.contract.OverseerExperimentPlanHistoryEntry(
                                                experimentPlanId = plan.experimentPlanId,
                                                hypothesisId = plan.hypothesisId,
                                                actionType = OverseerExperimentPlanHistoryActionType.SCHEDULED,
                                                note = "scheduled"
                                            )
                                    )
                                )
                            }
                        )
                    }
                }
            }

        override suspend fun launchOverseerExperimentRun(
            experimentPlanId: String,
            launchRequest: OverseerExperimentLaunchRequest
        ): OverseerExperimentLaunchResponse =
            persistAudit(
                actionType = OverseerKernelAuditActionType.LAUNCH_EXPERIMENT_RUN,
                experimentPlanId = experimentPlanId,
                note = launchRequest.scheduledRunId
            ) {
                store.update { current ->
                    val schedule = current.experimentSchedules.getValue(experimentPlanId)
                    require(launchRequest.scheduledRunId in schedule.scheduledRunIds) {
                        "Scheduled run '${launchRequest.scheduledRunId}' is not registered for experiment plan '$experimentPlanId'."
                    }
                    require(launchRequest.runConfig.runId == launchRequest.scheduledRunId) {
                        "Launch runId '${launchRequest.runConfig.runId}' must match scheduled run '${launchRequest.scheduledRunId}'."
                    }
                    require(schedule.launchedAtMs == null) {
                        "Experiment plan '$experimentPlanId' has already been launched."
                    }
                    current.copy(
                        experimentSchedules = current.experimentSchedules + (
                            experimentPlanId to schedule.copy(launchedAtMs = System.currentTimeMillis())
                        ),
                        experimentPlanHistory = current.experimentPlanHistory + (
                            experimentPlanId to (
                                current.experimentPlanHistory[experimentPlanId].orEmpty() +
                                    com.achulkov.evokammer.core.contract.OverseerExperimentPlanHistoryEntry(
                                        experimentPlanId = experimentPlanId,
                                        hypothesisId = schedule.hypothesisId,
                                        actionType = OverseerExperimentPlanHistoryActionType.LAUNCHED,
                                        scheduledRunId = launchRequest.scheduledRunId,
                                        note = "Launched at ${System.currentTimeMillis()}"
                                    )
                            )
                        )
                    )
                }
            }.let {
                OverseerExperimentLaunchResponse(
                    experimentPlanId = experimentPlanId,
                    scheduledRunId = launchRequest.scheduledRunId,
                    runId = launchRequest.runConfig.runId
                )
            }

        override suspend fun recordOverseerExperimentResult(experimentResult: OverseerExperimentResult): OverseerKernelState =
            persistAudit(
                actionType = OverseerKernelAuditActionType.RECORD_EXPERIMENT_RESULT,
                hypothesisId = experimentResult.hypothesisId,
                experimentPlanId = experimentResult.experimentPlanId,
                note = experimentResult.note.ifBlank { experimentResult.outcome.name }
            ) {
                store.update { current ->
                    val nextStatus = when (experimentResult.outcome) {
                        OverseerExperimentOutcome.ACCEPTED -> OverseerHypothesisStatus.ACCEPTED
                        OverseerExperimentOutcome.REJECTED -> if (experimentResult.counterexamplePaths.isNotEmpty()) {
                            OverseerHypothesisStatus.DEAD_END
                        } else {
                            OverseerHypothesisStatus.REJECTED
                        }
                    }
                    current.copy(
                        experimentResults = current.experimentResults + (experimentResult.experimentPlanId to experimentResult),
                        hypotheses = current.hypotheses + (
                            experimentResult.hypothesisId to current.hypotheses.getValue(experimentResult.hypothesisId).copy(
                                status = nextStatus
                            )
                        ),
                        deadEnds = if (nextStatus == OverseerHypothesisStatus.DEAD_END) {
                            current.deadEnds + (
                                experimentResult.hypothesisId to OverseerDeadEnd(
                                    hypothesisId = experimentResult.hypothesisId,
                                    reason = experimentResult.note.ifBlank { "Terminal failure recorded for regression." },
                                    counterexamplePaths = experimentResult.counterexamplePaths
                                )
                            )
                        } else {
                            current.deadEnds
                        },
                        experimentPlanHistory = current.experimentPlanHistory + (
                            experimentResult.experimentPlanId to (
                                current.experimentPlanHistory[experimentResult.experimentPlanId].orEmpty() +
                                    com.achulkov.evokammer.core.contract.OverseerExperimentPlanHistoryEntry(
                                        experimentPlanId = experimentResult.experimentPlanId,
                                        hypothesisId = experimentResult.hypothesisId,
                                        actionType = OverseerExperimentPlanHistoryActionType.RESULT_RECORDED,
                                        note = experimentResult.outcome.name
                                    )
                            )
                        ),
                        deadEndHistory = if (nextStatus == OverseerHypothesisStatus.DEAD_END) {
                            current.deadEndHistory + (
                                experimentResult.hypothesisId to (
                                    current.deadEndHistory[experimentResult.hypothesisId].orEmpty() +
                                        com.achulkov.evokammer.core.contract.OverseerDeadEndHistoryEntry(
                                            hypothesisId = experimentResult.hypothesisId,
                                            reason = experimentResult.note.ifBlank { "Terminal failure recorded for regression." },
                                            counterexamplePaths = experimentResult.counterexamplePaths,
                                            source = com.achulkov.evokammer.core.contract.OverseerDeadEndHistorySource.EXPERIMENT_RESULT
                                        )
                                )
                            )
                        } else {
                            current.deadEndHistory
                        }
                    )
                }
            }

        override suspend fun recordOverseerDeadEnd(deadEnd: OverseerDeadEnd): OverseerKernelState =
            persistAudit(
                actionType = OverseerKernelAuditActionType.RECORD_DEAD_END,
                deadEndHypothesisId = deadEnd.hypothesisId,
                note = deadEnd.reason
            ) {
                store.update { current ->
                    current.copy(
                        deadEnds = current.deadEnds + (deadEnd.hypothesisId to deadEnd),
                        deadEndHistory = current.deadEndHistory + (
                            deadEnd.hypothesisId to (
                                current.deadEndHistory[deadEnd.hypothesisId].orEmpty() +
                                    com.achulkov.evokammer.core.contract.OverseerDeadEndHistoryEntry(
                                        hypothesisId = deadEnd.hypothesisId,
                                        reason = deadEnd.reason,
                                        counterexamplePaths = deadEnd.counterexamplePaths,
                                        source = com.achulkov.evokammer.core.contract.OverseerDeadEndHistorySource.DIRECT_RECORD,
                                        recordedAtMs = deadEnd.recordedAtMs
                                    )
                            )
                        )
                    )
                }
            }

        override suspend fun getOverseerAuditEntries(): List<OverseerKernelAuditEntry> = auditStore.readAll()

        override suspend fun getOverseerExperimentPlanHistory(experimentPlanId: String): List<com.achulkov.evokammer.core.contract.OverseerExperimentPlanHistoryEntry> =
            store.read()?.experimentPlanHistory?.get(experimentPlanId).orEmpty()

        override suspend fun getOverseerDeadEndHistory(hypothesisId: String): List<com.achulkov.evokammer.core.contract.OverseerDeadEndHistoryEntry> =
            store.read()?.deadEndHistory?.get(hypothesisId).orEmpty()

        override suspend fun getOverseerAuditArchiveEntries(): List<MetaArchiveEntryRecord> =
            archiveDb.metaArchiveDao().getByType(RunArchiveService.ENTRY_OVERSEER_AUDIT).map { entry ->
                MetaArchiveEntryRecord(
                    id = entry.id,
                    runId = entry.runId,
                    entryType = entry.entryType,
                    groupId = entry.groupId,
                    label = entry.label,
                    contentJson = entry.contentJson,
                    runObjective = entry.runObjective,
                    finalScore = entry.finalScore,
                    isFromWinner = entry.isFromWinner,
                    generation = entry.generation,
                    chunkIndex = entry.chunkIndex,
                    totalChunks = entry.totalChunks,
                    archivedAtMs = entry.archivedAtMs
                )
            }

        private suspend fun persistAudit(
            actionType: OverseerKernelAuditActionType,
            hypothesisId: String? = null,
            experimentPlanId: String? = null,
            deadEndHypothesisId: String? = null,
            note: String? = null,
            update: () -> OverseerKernelState
        ): OverseerKernelState {
            val updated = update()
            val auditEntry = OverseerKernelAuditEntry(
                entryId = java.util.UUID.randomUUID().toString(),
                timestampMs = System.currentTimeMillis(),
                actionType = actionType,
                hypothesisId = hypothesisId,
                experimentPlanId = experimentPlanId,
                deadEndHypothesisId = deadEndHypothesisId,
                hypothesisCount = updated.hypotheses.size,
                experimentPlanCount = updated.experimentPlans.size,
                deadEndCount = updated.deadEnds.size,
                note = note
            )
            auditStore.append(
                auditEntry
            )
            factory.get(OverseerKernelArchive.RUN_ID, OVERSEER_KERNEL_ARCHIVE_GROUP_ID).metaArchiveDao().insert(
                MetaArchiveEntryEntity(
                    id = auditEntry.entryId,
                    runId = OverseerKernelArchive.RUN_ID,
                    entryType = OverseerKernelArchive.ENTRY_TYPE,
                    groupId = null,
                    label = "Overseer audit — $actionType",
                    contentJson = json.encodeToString(OverseerKernelAuditEntry.serializer(), auditEntry),
                    runObjective = "Recursive self-improvement control-plane evidence",
                    finalScore = null,
                    isFromWinner = false,
                    generation = 0,
                    archivedAtMs = auditEntry.timestampMs
                )
            )
            return updated
        }
    }

    private companion object {
        const val OVERSEER_KERNEL_ARCHIVE_GROUP_ID = "__meta_archive__"
    }
}
