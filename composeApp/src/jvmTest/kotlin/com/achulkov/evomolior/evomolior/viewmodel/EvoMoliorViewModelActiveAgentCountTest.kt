package com.achulkov.evomolior.evomolior.viewmodel

import com.achulkov.evomolior.api.events.EvoMoliorEvent
import com.achulkov.evomolior.api.service.EngineState
import com.achulkov.evomolior.api.service.EngineStatus
import com.achulkov.evomolior.api.service.EvoMoliorEngineService
import com.achulkov.evomolior.api.service.GroupSummary
import com.achulkov.evomolior.core.model.GroupProfileType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class EvoMoliorViewModelActiveAgentCountTest {

    @Test
    fun `agent spawn and death events update visible active agent counts`() = runTest {
        val runId = "run-1"
        val fakeService = FakeEngineService(
            initialState = EngineState(
                status = EngineStatus.RUNNING,
                activeRunId = runId,
                currentRound = 0,
                groupSummaries = listOf(
                    GroupSummary("group-0", GroupProfileType.Library.name, 0, 100.0, null),
                    GroupSummary("group-1", GroupProfileType.Factory.name, 0, 100.0, null)
                )
            )
        )

        val testScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val viewModel = EvoMoliorViewModel(fakeService, externalScope = testScope)

        advanceUntilIdle()
        assertEquals(2, viewModel.engineState.value.groupSummaries.size)

        fakeService.events.emit(EvoMoliorEvent.RunStarted(runId, "objective"))
        advanceUntilIdle()
        assertEquals(2, viewModel.engineState.value.groupSummaries.size)

        fakeService.events.emit(EvoMoliorEvent.AgentSpawned(runId, "group-0", "agent-1", com.achulkov.evomolior.core.model.Role.Researcher))
        advanceUntilIdle()
        assertEquals(1, viewModel.engineState.value.groupSummaries.first { it.groupId == "group-0" }.activeAgents)

        fakeService.events.emit(EvoMoliorEvent.AgentDied(runId, "group-0", "agent-1", "completed"))
        advanceUntilIdle()

        assertEquals(0, viewModel.engineState.value.groupSummaries.first { it.groupId == "group-0" }.activeAgents)
    }

    private class FakeEngineService(
        initialState: EngineState
    ) : EvoMoliorEngineService {
        private val _events = MutableSharedFlow<EvoMoliorEvent>(extraBufferCapacity = 16)
        override val events = _events
        private var state: EngineState = initialState

        suspend fun emit(event: EvoMoliorEvent) {
            _events.emit(event)
        }

        override suspend fun startRun(config: com.achulkov.evomolior.api.model.RunConfig): String {
            state = state.copy(status = EngineStatus.RUNNING, activeRunId = config.runId)
            return config.runId
        }

        override suspend fun pauseRun(runId: String) = Unit
        override suspend fun resumeRun(runId: String) = Unit
        override suspend fun abortRun(runId: String, reason: String) = Unit
        override suspend fun getState(): EngineState = state
        override suspend fun getMetaArchiveSummary(runId: String) = null
        override suspend fun getMetaArchiveEntries(runId: String, entryType: String?) = emptyList<com.achulkov.evomolior.api.service.MetaArchiveEntryRecord>()
        override suspend fun getResultsBundle(runId: String) = null
    }
}
