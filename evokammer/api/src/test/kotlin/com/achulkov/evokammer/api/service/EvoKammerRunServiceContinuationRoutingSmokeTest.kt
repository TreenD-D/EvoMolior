package com.achulkov.evokammer.api.service

import com.achulkov.evokammer.agents.factory.RolePromptBuilder
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EvoKammerRunServiceContinuationRoutingSmokeTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `resumed run launches agents with routed context in the real prompt`() = runBlocking {
        val dataRoot = createTempDirectory("continuation-routing-smoke-").toFile()
        val substrateFactory = SubstrateDatabaseFactory(dataRoot)
        val sourceRunId = "source-continuation-run"
        val sourceStartingField = StartingField.default(
            objective = "Base objective for the source run",
            runId = sourceRunId,
            groupCount = 1,
            groupProfilesMap = mapOf("group-0" to GroupProfileType.Library)
        )
        val sourceRoot = substrateFactory.runRootDir(sourceRunId)
        val resultsDir = java.io.File(sourceRoot, "results")
        resultsDir.mkdirs()
        java.io.File(resultsDir, "high-level-run-log.txt").writeText("high-level run log headline")

        val archiveDb = substrateFactory.get(sourceRunId, "__meta_archive__")
        archiveDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = "starting-field-$sourceRunId",
                runId = sourceRunId,
                entryType = RunArchiveService.ENTRY_STARTING_FIELD,
                groupId = null,
                label = "StartingField — $sourceRunId",
                contentJson = json.encodeToString(StartingField.serializer(), sourceStartingField),
                runObjective = sourceStartingField.objective,
                finalScore = null,
                isFromWinner = false,
                generation = 0,
                archivedAtMs = System.currentTimeMillis()
            )
        )
        archiveDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = "completion-$sourceRunId",
                runId = sourceRunId,
                entryType = RunArchiveService.ENTRY_RUN_COMPLETED,
                groupId = null,
                label = "Run completed",
                contentJson = json.encodeToString(
                    RunCompletionRecord.serializer(),
                    RunCompletionRecord(
                        runId = sourceRunId,
                        winnerId = "group-0",
                        finalAnswerSummary = "final answer headline",
                        finalDeliverableHtml = "<html><body>FINAL DELIVERABLE headline</body></html>",
                        judgeOutputsByGrader = mapOf("grader-0" to "judge output headline")
                    )
                ),
                runObjective = sourceStartingField.objective,
                finalScore = null,
                isFromWinner = true,
                generation = 1,
                archivedAtMs = System.currentTimeMillis()
            )
        )

        val promptDeferred = CompletableDeferred<String>()
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
                    localAgentRunner = { bootContext, _ ->
                        promptDeferred.complete(RolePromptBuilder.build(bootContext))
                    }
                )
            },
            groupCoordinatorProvider = { _, _ -> error("unused") },
            graderGroupManagerProvider = { _ -> error("unused") },
            substrateFactory = substrateFactory,
            shuruVmManager = ShuruVmManager(),
            metaPlannerRunner = MetaPlannerRunner(),
            roundHeartbeatIntervalMs = 1L,
            inspectorCheckIntervalMs = 1L
        )

        val runConfig = RunConfig(
            runId = "continued-run",
            objective = "Continue the source run with routed context",
            groupCount = 1,
            groupProfiles = listOf(GroupProfileType.Library),
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
            budgetOverride = 100.0,
            continuationFromRunId = sourceRunId
        )

        val launchedRunId = service.startRun(runConfig)
        assertNotNull(launchedRunId)

        val prompt = withTimeout(10_000) { promptDeferred.await() }
        assertContains(prompt, "=== ROUTED CONTEXT ===")
        assertContains(prompt, "high-level-run-log: high-level run log headline")
        assertContains(prompt, "final-answer-summary: final answer headline")
        assertContains(prompt, "final-deliverable-html:")
        assertContains(prompt, "judge-output:grader-0")

        withTimeout(10_000) {
            service.events.first { event ->
                event is EvoKammerEvent.RunCompleted && event.runId == launchedRunId
            }
        }
    }
}
