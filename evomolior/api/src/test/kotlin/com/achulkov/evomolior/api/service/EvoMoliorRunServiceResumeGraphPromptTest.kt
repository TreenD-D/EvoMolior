package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.api.events.EvoMoliorEvent
import com.achulkov.evomolior.api.model.RunConfig
import com.achulkov.evomolior.competition.coordinator.GroupCoordinator
import com.achulkov.evomolior.competition.grader.GraderGroupManager
import com.achulkov.evomolior.core.contract.DeliverableSkeletonNode
import com.achulkov.evomolior.core.contract.DeliverableSkeletonNodeType
import com.achulkov.evomolior.core.contract.DeliverableSkeletonTree
import com.achulkov.evomolior.core.contract.RunGraphEdge
import com.achulkov.evomolior.core.contract.StartingField
import com.achulkov.evomolior.core.model.GroupProfileType
import com.achulkov.evomolior.core.model.ScoringDimension
import com.achulkov.evomolior.orchestration.lifecycle.LifecycleManager
import com.achulkov.evomolior.orchestration.metaplanner.MetaPlannerRunner
import com.achulkov.evomolior.sandbox.shuru.ShuruVmManager
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.db.entity.MetaArchiveEntryEntity
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

class EvoMoliorRunServiceResumeGraphPromptTest {
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
            graderGroupCount = 0,
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
                            owningRole = com.achulkov.evomolior.core.model.Role.Planner
                        ),
                        DeliverableSkeletonNode(
                            nodeId = "dst:research",
                            parentId = DeliverableSkeletonTree.DEFAULT_ROOT_NODE_ID,
                            title = "Research",
                            nodeType = DeliverableSkeletonNodeType.DOCUMENT_SECTION,
                            owningRole = com.achulkov.evomolior.core.model.Role.Researcher
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
                    localAgentRunner = { bootContext, _ ->
                        promptDeferred.complete(com.achulkov.evomolior.agents.factory.RolePromptBuilder.build(bootContext))
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
                when (event) {
                    is EvoMoliorEvent.RunCompleted -> event.runId == runId
                    is EvoMoliorEvent.RunRecovered -> event.runId == runId
                    is EvoMoliorEvent.RunAborted -> event.runId == runId
                    else -> false
                }
            }
        }.also { assertTrue(it is EvoMoliorEvent.RunRecovered, "Unexpected terminal event: $it") }
        assertTrue(true)
    }
}
