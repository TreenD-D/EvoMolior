package com.achulkov.evokammer.api.service

import com.achulkov.evokammer.api.events.EvoKammerEvent
import com.achulkov.evokammer.api.model.RunConfig
import com.achulkov.evokammer.competition.coordinator.GroupCoordinator
import com.achulkov.evokammer.competition.grader.GraderGroupManager
import com.achulkov.evokammer.core.contract.DeliverableSkeletonNode
import com.achulkov.evokammer.core.contract.DeliverableSkeletonNodeType
import com.achulkov.evokammer.core.contract.DeliverableSkeletonTree
import com.achulkov.evokammer.core.contract.RunGraphEdge
import com.achulkov.evokammer.core.contract.StartingField
import com.achulkov.evokammer.core.model.GroupProfileType
import com.achulkov.evokammer.core.model.ScoringDimension
import com.achulkov.evokammer.orchestration.lifecycle.LifecycleManager
import com.achulkov.evokammer.orchestration.metaplanner.MetaPlannerRunner
import com.achulkov.evokammer.sandbox.shuru.ShuruVmManager
import com.achulkov.evokammer.substrate.SubstrateDatabaseFactory
import com.achulkov.evokammer.substrate.db.entity.MetaArchiveEntryEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EvoKammerRunServiceResumeGraphPromptTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `resume persisted run preserves graph ancestry and routed context in the launched prompt`() = runBlocking {
        val dataRoot = createTempDirectory("resume-graph-prompt-").toFile()
        val substrateFactory = SubstrateDatabaseFactory(dataRoot)
        val runId = "resume-graph-run"
        val startingField = StartingField.default(
            objective = "Resume graph prompt objective",
            runId = runId,
            groupCount = 2,
            groupProfilesMap = mapOf(
                "group-0" to GroupProfileType.Library,
                "group-1" to GroupProfileType.Factory
            )
        ).copy(
            deliverableSkeletonTreeJson = json.encodeToString(
                DeliverableSkeletonTree.serializer(),
                DeliverableSkeletonTree(
                    runId = runId,
                    groupId = "group-0",
                    nodes = listOf(
                        DeliverableSkeletonNode(
                            nodeId = DeliverableSkeletonTree.DEFAULT_ROOT_NODE_ID,
                            title = "Root",
                            nodeType = DeliverableSkeletonNodeType.ROOT,
                            children = listOf("dst:research"),
                            owningRole = com.achulkov.evokammer.core.model.Role.Planner
                        ),
                        DeliverableSkeletonNode(
                            nodeId = "dst:research",
                            parentId = DeliverableSkeletonTree.DEFAULT_ROOT_NODE_ID,
                            title = "Research",
                            nodeType = DeliverableSkeletonNodeType.DOCUMENT_SECTION,
                            owningRole = com.achulkov.evokammer.core.model.Role.Researcher
                        )
                    )
                )
            ),
            routedContextSummaries = listOf("high-level-run-log: ancestor headline"),
            runGraphEdges = listOf(
                RunGraphEdge(
                    sourceRunId = "ancestor-run",
                    targetRunId = runId,
                    edgeType = "continuation",
                    weight = 0.95
                ),
                RunGraphEdge(
                    sourceRunId = "grandparent-run",
                    targetRunId = "ancestor-run",
                    edgeType = "continuation",
                    weight = 0.85
                )
            )
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
                runObjective = startingField.objective,
                finalScore = null,
                isFromWinner = false,
                generation = 0,
                archivedAtMs = System.currentTimeMillis()
            )
        )
        PersistedRunStateStore.write(
            PersistedRunStateStore.resolveStateFile(dataRoot, runId),
            PersistedRunState.fromRunConfig(
                config = RunConfig(
                    runId = runId,
                    objective = startingField.objective,
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
                ),
                groupIds = listOf("group-0", "group-1"),
                graderGroupIds = emptyList(),
                allowedHosts = emptySet(),
                nextRoundIndex = 0,
                activeRoundIndex = null,
                status = PersistedRunStatus.BOOTSTRAPPED
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
                        promptDeferred.complete(com.achulkov.evokammer.agents.factory.RolePromptBuilder.build(bootContext))
                    }
                )
            },
            groupCoordinatorProvider = { groupIds, dimensions -> GroupCoordinator(groupIds, dimensions) },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = substrateFactory,
            metaPlannerRunner = MetaPlannerRunner(),
            roundHeartbeatIntervalMs = 1L,
            inspectorCheckIntervalMs = 1L
        )

        val resumedRunId = service.resumePersistedRun(
            RunConfig(
                runId = runId,
                objective = startingField.objective,
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
        )

        assertEquals(runId, resumedRunId)
        val prompt = withTimeout(10_000) { promptDeferred.await() }
        assertContains(prompt, "=== RUN GRAPH ===")
        assertContains(prompt, "ancestor-run -[continuation, w=0.95]-> resume-graph-run")
        assertContains(prompt, "high-level-run-log: ancestor headline")
        assertContains(prompt, "=== TASK OWNERSHIP MAP ===")
        assertContains(prompt, "Ownership clarity:")
        assertContains(prompt, "dst:research: Research -> Researcher")

        withTimeout(10_000) {
            service.events.first { event ->
                event is EvoKammerEvent.RunCompleted && event.runId == runId
            }
        }
        assertTrue(true)
    }
}
