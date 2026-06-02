package com.achulkov.evomolior.evokammer.viewmodel

import com.achulkov.evokammer.api.events.EvoKammerEvent
import com.achulkov.evokammer.api.service.EngineState
import com.achulkov.evokammer.api.service.EngineStatus
import com.achulkov.evokammer.api.service.EvoKammerEngineService
import com.achulkov.evokammer.api.service.GroupSummary
import com.achulkov.evokammer.core.model.GroupProfileType
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
class EvoKammerViewModelActiveAgentCountTest {

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
        val viewModel = EvoKammerViewModel(fakeService, externalScope = testScope)

        advanceUntilIdle()
        assertEquals(2, viewModel.engineState.value.groupSummaries.size)

        fakeService.events.emit(EvoKammerEvent.RunStarted(runId, "objective"))
        advanceUntilIdle()
        assertEquals(2, viewModel.engineState.value.groupSummaries.size)

        fakeService.events.emit(EvoKammerEvent.AgentSpawned(runId, "group-0", "agent-1", com.achulkov.evokammer.core.model.Role.Researcher))
        advanceUntilIdle()
        assertEquals(1, viewModel.engineState.value.groupSummaries.first { it.groupId == "group-0" }.activeAgents)

        fakeService.events.emit(EvoKammerEvent.AgentDied(runId, "group-0", "agent-1", "completed"))
        advanceUntilIdle()

        assertEquals(0, viewModel.engineState.value.groupSummaries.first { it.groupId == "group-0" }.activeAgents)
    }

    private class FakeEngineService(
        initialState: EngineState
    ) : EvoKammerEngineService {
        private val _events = MutableSharedFlow<EvoKammerEvent>(extraBufferCapacity = 16)
        override val events = _events
        private var state: EngineState = initialState

        suspend fun emit(event: EvoKammerEvent) {
            _events.emit(event)
        }

        override suspend fun startRun(config: com.achulkov.evokammer.api.model.RunConfig): String {
            state = state.copy(status = EngineStatus.RUNNING, activeRunId = config.runId)
            return config.runId
        }

        override suspend fun pauseRun(runId: String) = Unit
        override suspend fun resumeRun(runId: String) = Unit
        override suspend fun abortRun(runId: String, reason: String) = Unit
        override suspend fun getState(): EngineState = state
        override suspend fun getMetaArchiveSummary(runId: String) = null
        override suspend fun getMetaArchiveEntries(runId: String, entryType: String?) = emptyList<com.achulkov.evokammer.api.service.MetaArchiveEntryRecord>()
        override suspend fun getResultsBundle(runId: String) = null
    }
}
