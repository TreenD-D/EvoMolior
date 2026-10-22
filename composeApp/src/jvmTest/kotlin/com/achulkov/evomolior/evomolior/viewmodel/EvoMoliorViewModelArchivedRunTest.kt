package com.achulkov.evomolior.evomolior.viewmodel

import com.achulkov.evomolior.api.events.EvoMoliorEvent
import com.achulkov.evomolior.api.model.RunConfig
import com.achulkov.evomolior.api.service.EngineState
import com.achulkov.evomolior.api.service.EngineStatus
import com.achulkov.evomolior.api.service.EvoMoliorEngineService
import com.achulkov.evomolior.api.service.MetaArchiveEntryRecord
import com.achulkov.evomolior.api.service.MetaArchiveSummary
import com.achulkov.evomolior.api.service.RunOutcomePresentation
import com.achulkov.evomolior.core.contract.OverseerDeadEnd
import com.achulkov.evomolior.core.contract.OverseerDeadEndHistoryEntry
import com.achulkov.evomolior.core.contract.OverseerExperimentPlan
import com.achulkov.evomolior.core.contract.OverseerExperimentPlanEligibility
import com.achulkov.evomolior.core.contract.OverseerExperimentPlanHistoryEntry
import com.achulkov.evomolior.core.contract.OverseerExperimentResult
import com.achulkov.evomolior.core.contract.OverseerHypothesis
import com.achulkov.evomolior.core.contract.OverseerKernelAuditEntry
import com.achulkov.evomolior.core.contract.OverseerKernelEligibility
import com.achulkov.evomolior.core.contract.OverseerKernelState
import com.achulkov.evomolior.core.deliverable.AcceptanceStatus
import com.achulkov.evomolior.core.deliverable.ExecutionCompletionStatus
import com.achulkov.evomolior.core.deliverable.PrimaryArtifactValidationStatus
import com.achulkov.evomolior.core.deliverable.RunOutcomeClass
import com.achulkov.evomolior.evomolior.screens.buildArchivedGradingHistory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class, kotlin.io.path.ExperimentalPathApi::class)
class EvoMoliorViewModelArchivedRunTest {

    @Test
    fun `loading archived run replaces stale live state and repopulates archived history and log state from disk`() = runTest {
        val runId = "run-archived-1"
        val tempDir = createTempDirectory("evomolior-archive-test")
        val dataRootDir = tempDir.toFile()
        val logFile = File(dataRootDir, "evomolior/runs/$runId/results/debug-log.txt")
        logFile.parentFile?.mkdirs()
        logFile.writeText("line-1\nline-2\n")

        val roundSummary = archiveEntry(
            runId = runId,
            entryType = "ROUND_SUMMARY",
            contentJson = """
                {
                  "runId": "$runId",
                  "roundIndex": 2,
                  "roundWinnerId": "group-1",
                  "groupSummaries": [
                    {
                      "groupId": "group-0",
                      "blindScore": {
                        "weightedTotal": 10.0,
                        "rank": 2,
                        "dimensionScores": {
                          "kb_density": 3.0
                        }
                      }
                    },
                    {
                      "groupId": "group-1",
                      "blindScore": {
                        "weightedTotal": 15.0,
                        "rank": 1,
                        "dimensionScores": {
                          "kb_density": 5.0
                        }
                      }
                    }
                  ]
                }
            """.trimIndent()
        )

        try {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val viewModel = EvoMoliorViewModel(
                engineService = FakeArchivedEngineService(
                    initialState = EngineState(
                        status = EngineStatus.RUNNING,
                        activeRunId = "stale-run",
                        currentRound = 99,
                        groupSummaries = emptyList()
                    ),
                    summary = MetaArchiveSummary(total = 1, byType = mapOf("ROUND_SUMMARY" to 1)),
                    entries = listOf(roundSummary)
                ),
                dataRootDir = dataRootDir,
                ioDispatcher = dispatcher,
                externalScope = CoroutineScope(dispatcher + SupervisorJob())
            )

            advanceUntilIdle()
            assertEquals("stale-run", viewModel.engineState.value.activeRunId)
            assertEquals(EngineStatus.RUNNING, viewModel.engineState.value.status)
            assertEquals(99, viewModel.engineState.value.currentRound)
            assertEquals(emptyList(), viewModel.gradingHistory.value)

            viewModel.loadArchivedRun(runId)
            advanceUntilIdle()

            assertEquals(runId, viewModel.engineState.value.activeRunId)
            assertEquals(EngineStatus.COMPLETED, viewModel.engineState.value.status)
            assertEquals(null, viewModel.engineState.value.currentRound)
            assertEquals(1, viewModel.roundSummaryEntries.value.size)
            assertEquals(1, viewModel.gradingHistory.value.size)
            assertEquals(2, viewModel.gradingHistory.value.first().roundIndex)
            assertEquals("group-1", viewModel.gradingHistory.value.first().winnerId)
            assertTrue(viewModel.archivedLogText.value?.contains("line-2") == true)
            assertTrue(viewModel.gradingHistory.value.none { it.winnerId == "stale-winner" })
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `build archived grading history sorts rounds and preserves winner ordering`() {
        val history = buildArchivedGradingHistory(
            listOf(
                archiveEntry(
                    runId = "run-3",
                    entryType = "ROUND_SUMMARY",
                    contentJson = """
                        {
                          "runId": "run-3",
                          "roundIndex": 3,
                          "roundWinnerId": "group-beta",
                          "groupSummaries": [
                            {
                              "groupId": "group-alpha",
                              "blindScore": {
                                "weightedTotal": 12.5,
                                "rank": 2,
                                "dimensionScores": {
                                  "kb_density": 4.0,
                                  "kg_coherence": 8.5
                                }
                              }
                            },
                            {
                              "groupId": "group-beta",
                              "blindScore": {
                                "weightedTotal": 18.0,
                                "rank": 1,
                                "dimensionScores": {
                                  "kb_density": 6.0,
                                  "kg_coherence": 12.0
                                }
                              }
                            }
                          ]
                        }
                    """.trimIndent()
                ),
                archiveEntry(
                    runId = "run-3",
                    entryType = "ROUND_SUMMARY",
                    contentJson = """
                        {
                          "runId": "run-3",
                          "roundIndex": 1,
                          "roundWinnerId": "group-alpha",
                          "groupSummaries": [
                            {
                              "groupId": "group-alpha",
                              "blindScore": {
                                "weightedTotal": 9.0,
                                "rank": 1,
                                "dimensionScores": {
                                  "kb_density": 3.0,
                                  "kg_coherence": 6.0
                                }
                              }
                            }
                          ]
                        }
                    """.trimIndent()
                )
            )
        )

        assertEquals(2, history.size)
        assertEquals(1, history.first().roundIndex)
        assertEquals("group-alpha", history.first().winnerId)
        assertEquals(9.0, history.first().scores.first().weightedTotal)
        assertEquals(3, history.last().roundIndex)
        assertEquals("group-beta", history.last().winnerId)
        assertEquals(18.0, history.last().scores.first { it.groupId == "group-beta" }.weightedTotal)
    }

    @Test
    fun `loading recovered archive preserves non accepted terminal status`() = runTest {
        val runId = "run-recovered-archive"
        val dispatcher = StandardTestDispatcher(testScheduler)
        val viewModel = EvoMoliorViewModel(
            engineService = FakeArchivedEngineService(
                initialState = EngineState(
                    status = EngineStatus.IDLE,
                    activeRunId = null,
                    currentRound = null,
                    groupSummaries = emptyList()
                ),
                summary = MetaArchiveSummary(
                    total = 1,
                    byType = mapOf("RUN_RECOVERED_NON_ACCEPTED" to 1)
                ),
                entries = listOf(
                    archiveEntry(
                        runId = runId,
                        entryType = "RUN_RECOVERED_NON_ACCEPTED",
                        contentJson = """{"accepted":false}"""
                    )
                )
            ),
            externalScope = CoroutineScope(dispatcher + SupervisorJob()),
            ioDispatcher = dispatcher
        )

        advanceUntilIdle()
        viewModel.loadArchivedRun(runId)
        advanceUntilIdle()

        assertEquals(EngineStatus.RECOVERED, viewModel.engineState.value.status)
        assertEquals(runId, viewModel.engineState.value.activeRunId)
        assertTrue(viewModel.runCompletionEntries.value.isEmpty())
    }

    @Test
    fun `completed provisional projection drives completed process status without acceptance claim`() =
        runTest {
            val runId = "run-completed-provisional"
            val dispatcher = StandardTestDispatcher(testScheduler)
            val presentation = RunOutcomePresentation(
                runId = runId,
                outcomeClass = RunOutcomeClass.PROVISIONAL_DECISION_PACKAGE,
                manifestHash = "a".repeat(64),
                artifactPaths = listOf("outputs/recovered/submission.ipynb"),
                executionStatus = ExecutionCompletionStatus.COMPLETED,
                acceptanceStatus = AcceptanceStatus.PROVISIONAL,
                primaryArtifactPath = "outputs/recovered/submission.ipynb",
                primaryArtifactValidationStatus =
                    PrimaryArtifactValidationStatus.NOT_EVALUATED,
            )
            val viewModel = EvoMoliorViewModel(
                engineService = FakeArchivedEngineService(
                    initialState = EngineState(
                        status = EngineStatus.IDLE,
                        activeRunId = null,
                        currentRound = null,
                        groupSummaries = emptyList(),
                    ),
                    summary = MetaArchiveSummary(
                        total = 1,
                        byType = mapOf("RUN_RECOVERED_NON_ACCEPTED" to 1),
                    ),
                    entries = listOf(
                        archiveEntry(
                            runId = runId,
                            entryType = "RUN_RECOVERED_NON_ACCEPTED",
                            contentJson = """{"accepted":false}""",
                        ),
                    ),
                    outcome = presentation,
                ),
                externalScope = CoroutineScope(dispatcher + SupervisorJob()),
                ioDispatcher = dispatcher,
            )

            advanceUntilIdle()
            viewModel.loadArchivedRun(runId)
            advanceUntilIdle()

            assertEquals(EngineStatus.COMPLETED, viewModel.engineState.value.status)
            assertEquals(
                AcceptanceStatus.PROVISIONAL,
                viewModel.runOutcomePresentation.value?.acceptanceStatus,
            )
            assertEquals(
                ExecutionCompletionStatus.COMPLETED,
                viewModel.runOutcomePresentation.value?.executionStatus,
            )
        }

    private fun archiveEntry(
        runId: String,
        entryType: String,
        contentJson: String
    ): MetaArchiveEntryRecord = MetaArchiveEntryRecord(
        id = "entry-$entryType",
        runId = runId,
        entryType = entryType,
        groupId = null,
        label = entryType,
        contentJson = contentJson,
        runObjective = "objective",
        finalScore = null,
        isFromWinner = false,
        generation = 0,
        chunkIndex = 0,
        totalChunks = 1,
        archivedAtMs = 1L
    )
}

private class FakeArchivedEngineService(
    initialState: EngineState,
    private val summary: MetaArchiveSummary,
    private val entries: List<MetaArchiveEntryRecord>,
    private val outcome: RunOutcomePresentation? = null,
) : EvoMoliorEngineService {
    override val events: SharedFlow<EvoMoliorEvent> = MutableSharedFlow()
    private var state: EngineState = initialState

    override suspend fun startRun(config: RunConfig): String = error("not used")
    override suspend fun pauseRun(runId: String) = error("not used")
    override suspend fun resumeRun(runId: String) = error("not used")
    override suspend fun abortRun(runId: String, reason: String) = error("not used")

    override suspend fun getState(): EngineState = state

    override suspend fun getMetaArchiveSummary(runId: String): MetaArchiveSummary = summary

    override suspend fun getMetaArchiveEntries(runId: String, entryType: String?): List<MetaArchiveEntryRecord> =
        if (entryType == null) entries else entries.filter { it.entryType == entryType }

    override suspend fun getResultsBundle(runId: String): ByteArray? = null
    override suspend fun getRunOutcomePresentation(runId: String): RunOutcomePresentation? = outcome
    override suspend fun getOverseerKernelState() = OverseerKernelState()
    override suspend fun preflightOverseerHypothesis(hypothesis: OverseerHypothesis) =
        OverseerKernelEligibility(allowed = false, reasons = listOf("test"))
    override suspend fun preflightOverseerExperimentPlan(
        experimentPlan: OverseerExperimentPlan
    ) = OverseerExperimentPlanEligibility(allowed = false, reasons = listOf("test"))
    override suspend fun formalizeOverseerHypothesis(hypothesis: OverseerHypothesis) =
        OverseerKernelState()
    override suspend fun registerOverseerExperimentPlan(
        experimentPlan: OverseerExperimentPlan
    ) = OverseerKernelState()
    override suspend fun scheduleOverseerExperimentPlans() = OverseerKernelState()
    override suspend fun recordOverseerExperimentResult(
        experimentResult: OverseerExperimentResult
    ) = OverseerKernelState()
    override suspend fun recordOverseerDeadEnd(deadEnd: OverseerDeadEnd) =
        OverseerKernelState()
    override suspend fun getOverseerAuditEntries(): List<OverseerKernelAuditEntry> =
        emptyList()
    override suspend fun getOverseerExperimentPlanHistory(
        experimentPlanId: String
    ): List<OverseerExperimentPlanHistoryEntry> = emptyList()
    override suspend fun getOverseerDeadEndHistory(
        hypothesisId: String
    ): List<OverseerDeadEndHistoryEntry> = emptyList()
    override suspend fun getOverseerAuditArchiveEntries(): List<MetaArchiveEntryRecord> =
        emptyList()
}
