package com.achulkov.evokammer.api.service

import com.achulkov.evokammer.api.events.EvoKammerEvent
import com.achulkov.evokammer.api.model.RunConfig
import com.achulkov.evokammer.competition.coordinator.GroupCoordinator
import com.achulkov.evokammer.competition.grader.GraderGroupManager
import com.achulkov.evokammer.core.contract.StartingField
import com.achulkov.evokammer.core.model.GroupProfileType
import com.achulkov.evokammer.core.model.ScoringDimension
import com.achulkov.evokammer.orchestration.lifecycle.LifecycleManager
import com.achulkov.evokammer.orchestration.metaplanner.MetaPlannerRunner
import com.achulkov.evokammer.sandbox.shuru.ShuruVmManager
import com.achulkov.evokammer.substrate.SubstrateDatabaseFactory
import com.achulkov.evokammer.substrate.db.entity.MetaArchiveEntryEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EvoKammerRunServicePersistedResumeTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `resume persisted run recovers from sealed starting field and finishes`() = runBlocking {
        val tmpDir = createTempDirectory("svc-persisted-resume-").toFile()
        val substrateFactory = SubstrateDatabaseFactory(tmpDir)
        val runId = "persisted-resume-run"
        val config = RunConfig(
            runId = runId,
            objective = "Resume the persisted run",
            groupCount = 2,
            groupProfiles = listOf(GroupProfileType.Library, GroupProfileType.Factory),
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
            isTestRun = true,
            budgetOverride = 100.0
        )
        val startingField = StartingField.default(
            objective = config.objective,
            runId = runId,
            groupCount = 2,
            groupProfilesMap = mapOf(
                "group-0" to GroupProfileType.Library,
                "group-1" to GroupProfileType.Factory
            ),
            budgetCeiling = 100.0,
            generationCeiling = 1
        )
        val archiveDb = substrateFactory.get(runId, "__meta_archive__")
        archiveDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = "starting-field-$runId",
                runId = runId,
                entryType = RunArchiveService.ENTRY_STARTING_FIELD,
                groupId = null,
                label = "StartingField — $runId",
                contentJson = json.encodeToString(StartingField.serializer(), startingField),
                runObjective = config.objective,
                finalScore = null,
                isFromWinner = false,
                generation = 0,
                archivedAtMs = System.currentTimeMillis()
            )
        )
        PersistedRunStateStore.write(
            PersistedRunStateStore.resolveStateFile(tmpDir, runId),
            PersistedRunState.fromRunConfig(
                config = config,
                groupIds = listOf("group-0", "group-1"),
                graderGroupIds = emptyList(),
                allowedHosts = emptySet(),
                nextRoundIndex = 0,
                activeRoundIndex = null,
                status = PersistedRunStatus.BOOTSTRAPPED
            )
        )

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
            groupCoordinatorProvider = { groupIds, dimensions -> GroupCoordinator(groupIds, dimensions) },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = substrateFactory,
            metaPlannerRunner = MetaPlannerRunner(),
            roundHeartbeatIntervalMs = 1L,
            inspectorCheckIntervalMs = 1L
        )

        val resumedRunId = service.resumePersistedRun(config)
        assertEquals(runId, resumedRunId)

        withTimeout(10_000) {
            service.events.first { event ->
                event is EvoKammerEvent.RunCompleted && event.runId == runId
            }
        }

        val bundle = service.getResultsBundle(runId)
        assertNotNull(bundle)
        val persistedState = PersistedRunStateStore.read(tmpDir, runId)
        assertNotNull(persistedState)
        assertEquals(PersistedRunStatus.COMPLETED, persistedState.status)
        assertTrue(
            service.getMetaArchiveEntries(runId, RunArchiveService.ENTRY_RUN_COMPLETED).isNotEmpty(),
            "Recovered run should persist a completion marker"
        )
    }
}
