package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.api.model.RunConfig
import com.achulkov.evomolior.competition.coordinator.GroupCoordinator
import com.achulkov.evomolior.competition.grader.GraderGroupManager
import com.achulkov.evomolior.core.contract.FeedforwardLayerSpec
import com.achulkov.evomolior.core.contract.RunGraphEdge
import com.achulkov.evomolior.core.contract.StartingField
import com.achulkov.evomolior.core.model.GroupProfileType
import com.achulkov.evomolior.core.model.ScoringDimension
import com.achulkov.evomolior.orchestration.metaplanner.MetaPlannerRunner
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.db.entity.MetaArchiveEntryEntity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.jvm.isAccessible
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EvoMoliorRunServiceGraphRoutingTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `graph routed context can traverse a prior ancestor run`() = runBlocking {
        val dataRoot = createTempDirectory("graph-routing-").toFile()
        val substrateFactory = SubstrateDatabaseFactory(dataRoot)
        val grandparentRunId = "grandparent-run"
        val ancestorRunId = "ancestor-run"
        val sourceRunId = "source-run"

        val grandparentSource = StartingField.default(
            objective = "Grandparent run objective",
            runId = grandparentRunId,
            groupCount = 1,
            groupProfilesMap = mapOf("group-0" to GroupProfileType.Library)
        )
        val ancestorSource = StartingField.default(
            objective = "Ancestor run objective",
            runId = ancestorRunId,
            groupCount = 1,
            groupProfilesMap = mapOf("group-0" to GroupProfileType.Library)
        ).copy(
            continuedFromRunId = grandparentRunId,
            runGraphEdges = listOf(
                RunGraphEdge(
                    sourceRunId = grandparentRunId,
                    targetRunId = ancestorRunId,
                    edgeType = "continuation",
                    weight = 0.9
                )
            )
        )
        val sourceSource = StartingField.default(
            objective = "Source run objective",
            runId = sourceRunId,
            groupCount = 1,
            groupProfilesMap = mapOf("group-0" to GroupProfileType.Library)
        ).copy(
            continuedFromRunId = ancestorRunId,
            runGraphEdges = listOf(
                RunGraphEdge(
                    sourceRunId = ancestorRunId,
                    targetRunId = sourceRunId,
                    edgeType = "continuation",
                    weight = 1.0
                )
            )
        )

        persistCompletion(
            substrateFactory = substrateFactory,
            runId = grandparentRunId,
            objective = grandparentSource.objective,
            startingField = grandparentSource,
            finalAnswerSummary = "grandparent synthesis headline",
            finalDeliverableHtml = "<html><body>grandparent final deliverable</body></html>"
        )
        persistCompletion(
            substrateFactory = substrateFactory,
            runId = ancestorRunId,
            objective = ancestorSource.objective,
            startingField = ancestorSource,
            finalAnswerSummary = "ancestor synthesis headline",
            finalDeliverableHtml = "<html><body>ancestor final deliverable</body></html>"
        )
        persistCompletion(
            substrateFactory = substrateFactory,
            runId = sourceRunId,
            objective = sourceSource.objective,
            startingField = sourceSource,
            finalAnswerSummary = "source log headline",
            finalDeliverableHtml = "<html><body>source final deliverable</body></html>"
        )

        val service = EvoMoliorRunService(
            lifecycleManagerProvider = { _, _, _, _, _ -> error("unused") },
            groupCoordinatorProvider = { groupIds, dimensions -> GroupCoordinator(groupIds, dimensions) },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = substrateFactory,
            metaPlannerRunner = MetaPlannerRunner()
        )

        val routed = service.routeContinuationContextSummaries(
            sourceRunId = sourceRunId,
            sourceStartingField = sourceSource,
            objective = "Please synthesize the ancestor evidence into a final answer",
            maxSummaries = 12
        )

        assertTrue(routed.any { it.contains("grandparent synthesis headline") })
        assertTrue(routed.any { it.contains("ancestor synthesis headline") })
        assertTrue(routed.any { it.startsWith("high-level-run-log:") })
    }

    @Test
    fun `buildContinuationStartingField appends a graph edge to the inherited ancestry`() = runBlocking {
        val dataRoot = createTempDirectory("graph-continuation-").toFile()
        val service = EvoMoliorRunService(
            lifecycleManagerProvider = { _, _, _, _, _ -> error("unused") },
            groupCoordinatorProvider = { groupIds, dimensions -> GroupCoordinator(groupIds, dimensions) },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = SubstrateDatabaseFactory(dataRoot),
            metaPlannerRunner = MetaPlannerRunner()
        )

        val source = StartingField.default(
            objective = "Ancestor chain",
            runId = "source-run",
            groupCount = 1,
            groupProfilesMap = mapOf("group-0" to GroupProfileType.Library)
        ).copy(
            feedforwardLayer = FeedforwardLayerSpec(
                layerIndex = 0,
                layerLabel = "L1",
                upstreamRunIds = emptyList(),
                layerObjective = "Source research"
            ),
            runGraphEdges = listOf(
                RunGraphEdge(sourceRunId = "grandparent-run", targetRunId = "source-run", edgeType = "continuation", weight = 0.8)
            )
        )
        val config = RunConfig(
            runId = "continued-run",
            objective = "Descendant objective",
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
            continuationFromRunId = "source-run"
        )

        val method = service::class.declaredFunctions.first { it.name == "buildContinuationStartingField" }.apply {
            isAccessible = true
        }
        val derived = method.call(service, source, config, emptyList<String>()) as? StartingField
            ?: error("buildContinuationStartingField returned an unexpected type")

        assertEquals(source.runGraphEdges.size + 1, derived.runGraphEdges.size)
        assertEquals("source-run", derived.runGraphEdges.last().sourceRunId)
        assertEquals("continued-run", derived.runGraphEdges.last().targetRunId)
        assertEquals(source.runGraphEdges, derived.runGraphEdges.dropLast(1))
        assertEquals(1, derived.feedforwardLayer?.layerIndex)
        assertEquals("L2", derived.feedforwardLayer?.layerLabel)
        assertEquals(listOf("source-run"), derived.feedforwardLayer?.upstreamRunIds)
    }

    private suspend fun persistCompletion(
        substrateFactory: SubstrateDatabaseFactory,
        runId: String,
        objective: String,
        startingField: StartingField,
        finalAnswerSummary: String,
        finalDeliverableHtml: String
    ) {
        val archiveDb = substrateFactory.get(runId, "__meta_archive__")
        val resultsDir = File(substrateFactory.runRootDir(runId), "results")
        resultsDir.mkdirs()
        File(resultsDir, "high-level-run-log.txt").writeText("high-level run log for $runId")
        archiveDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = "starting-field-$runId",
                runId = runId,
                entryType = RunArchiveService.ENTRY_STARTING_FIELD,
                groupId = null,
                label = "StartingField — $runId",
                contentJson = json.encodeToString(StartingField.serializer(), startingField),
                runObjective = objective,
                finalScore = null,
                isFromWinner = false,
                generation = 0,
                archivedAtMs = System.currentTimeMillis()
            )
        )
        archiveDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = "completion-$runId",
                runId = runId,
                entryType = RunArchiveService.ENTRY_RUN_COMPLETED,
                groupId = null,
                label = "Run completed",
                contentJson = json.encodeToString(
                    RunCompletionRecord.serializer(),
                    RunCompletionRecord(
                        runId = runId,
                        winnerId = "group-0",
                        finalAnswerSummary = finalAnswerSummary,
                        finalDeliverableHtml = finalDeliverableHtml,
                        judgeOutputsByGrader = mapOf("grader-0" to "judge output for $runId")
                    )
                ),
                runObjective = objective,
                finalScore = null,
                isFromWinner = true,
                generation = 1,
                archivedAtMs = System.currentTimeMillis()
            )
        )
    }
}
