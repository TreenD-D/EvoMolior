package com.achulkov.evokammer.api.service

import com.achulkov.evokammer.api.model.RunConfig
import com.achulkov.evokammer.competition.coordinator.GroupCoordinator
import com.achulkov.evokammer.competition.grader.GraderGroupManager
import com.achulkov.evokammer.core.contract.FeedforwardLayerSpec
import com.achulkov.evokammer.core.contract.StartingField
import com.achulkov.evokammer.core.model.GroupProfileType
import com.achulkov.evokammer.core.model.ScoringDimension
import com.achulkov.evokammer.orchestration.lifecycle.LifecycleManager
import com.achulkov.evokammer.orchestration.metaplanner.MetaPlannerRunner
import com.achulkov.evokammer.sandbox.shuru.ShuruVmManager
import com.achulkov.evokammer.substrate.SubstrateDatabaseFactory
import com.achulkov.evokammer.substrate.db.entity.MetaArchiveEntryEntity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EvoKammerRunServiceRoutingTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `continuation routing prefers the expected artifact categories in order`() = runBlocking {
        val dataRoot = createTempDirectory("routing-order-").toFile()
        val substrateFactory = SubstrateDatabaseFactory(dataRoot)
        val sourceRunId = "source-run"
        val sourceRoot = substrateFactory.runRootDir(sourceRunId)
        val resultsDir = java.io.File(sourceRoot, "results")
        resultsDir.mkdirs()
        java.io.File(resultsDir, "high-level-run-log.txt").writeText("high-level run log headline")

        val completionRecord = RunCompletionRecord(
            runId = sourceRunId,
            winnerId = "group-0",
            finalAnswerSummary = "final answer headline",
            finalDeliverableHtml = "<html><body>FINAL DELIVERABLE headline</body></html>",
            judgeOutputsByGrader = mapOf("grader-0" to "judge output headline"),
            submissionSummariesByGroup = mapOf("group-0" to "group summary headline")
        )
        val archiveDb = substrateFactory.get(sourceRunId, "__meta_archive__")
        archiveDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = "completion-$sourceRunId",
                runId = sourceRunId,
                entryType = RunArchiveService.ENTRY_RUN_COMPLETED,
                groupId = null,
                label = "Run completed",
                contentJson = json.encodeToString(RunCompletionRecord.serializer(), completionRecord),
                runObjective = "source objective",
                finalScore = null,
                isFromWinner = true,
                generation = 1,
                archivedAtMs = System.currentTimeMillis()
            )
        )

        val service = EvoKammerRunService(
            lifecycleManagerProvider = { _, _, _, _, _ -> error("unused") },
            groupCoordinatorProvider = { _, _ -> error("unused") },
            graderGroupManagerProvider = { _ -> error("unused") },
            substrateFactory = substrateFactory,
            shuruVmManager = ShuruVmManager(),
            metaPlannerRunner = MetaPlannerRunner()
        )

        val routed = service.routeContinuationContextSummaries(
            sourceRunId = sourceRunId,
            sourceStartingField = StartingField.default(
                objective = "source objective",
                runId = sourceRunId,
                groupCount = 1,
                groupProfilesMap = mapOf("group-0" to GroupProfileType.Library)
            ),
            objective = "high level final answer final deliverable judge output",
            maxSummaries = 4
        )

        assertEquals(4, routed.size)
        assertTrue(routed[0].startsWith("high-level-run-log:"))
        assertTrue(routed[1].startsWith("final-answer-summary:"))
        assertTrue(routed[2].startsWith("final-deliverable-html:"))
        assertTrue(routed[3].startsWith("judge-output:"))
    }

    @Test
    fun `continuation routing prefers the archived graph message over file artefacts`() = runBlocking {
        val dataRoot = createTempDirectory("routing-graph-message-").toFile()
        val substrateFactory = SubstrateDatabaseFactory(dataRoot)
        val sourceRunId = "source-run"
        val sourceRoot = substrateFactory.runRootDir(sourceRunId)
        val resultsDir = java.io.File(sourceRoot, "results")
        resultsDir.mkdirs()
        java.io.File(resultsDir, "high-level-run-log.txt").writeText("artifact file headline")

        val completionRecord = RunCompletionRecord(
            runId = sourceRunId,
            winnerId = "group-0",
            finalAnswerSummary = "artifact final answer headline",
            finalDeliverableHtml = "<html><body>artifact deliverable headline</body></html>",
            judgeOutputsByGrader = mapOf("grader-0" to "artifact judge output headline"),
            submissionSummariesByGroup = mapOf("group-0" to "artifact group summary headline"),
            graphMessage = RunGraphMessage(
                runId = sourceRunId,
                headline = "graph envelope headline",
                routedContextSummaries = listOf(
                    "high-level-run-log: graph envelope headline",
                    "final-answer-summary: graph envelope final answer"
                )
            )
        )
        val archiveDb = substrateFactory.get(sourceRunId, "__meta_archive__")
        archiveDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = "completion-$sourceRunId",
                runId = sourceRunId,
                entryType = RunArchiveService.ENTRY_RUN_COMPLETED,
                groupId = null,
                label = "Run completed",
                contentJson = json.encodeToString(RunCompletionRecord.serializer(), completionRecord),
                runObjective = "source objective",
                finalScore = null,
                isFromWinner = true,
                generation = 1,
                archivedAtMs = System.currentTimeMillis()
            )
        )

        val service = EvoKammerRunService(
            lifecycleManagerProvider = { _, _, _, _, _ -> error("unused") },
            groupCoordinatorProvider = { _, _ -> error("unused") },
            graderGroupManagerProvider = { _ -> error("unused") },
            substrateFactory = substrateFactory,
            shuruVmManager = ShuruVmManager(),
            metaPlannerRunner = MetaPlannerRunner()
        )

        val routed = service.routeContinuationContextSummaries(
            sourceRunId = sourceRunId,
            sourceStartingField = StartingField.default(
                objective = "source objective",
                runId = sourceRunId,
                groupCount = 1,
                groupProfilesMap = mapOf("group-0" to GroupProfileType.Library)
            ),
            objective = "graph envelope final answer",
            maxSummaries = 2
        )

        assertEquals(2, routed.size)
        assertTrue(routed[0].contains("graph envelope"))
        assertTrue(routed[1].contains("graph envelope"))
        assertTrue(routed.none { it.contains("artifact file headline") })
    }

    @Test
    fun `continuation routing uses embedding similarity to order graph message summaries`() = runBlocking {
        val dataRoot = createTempDirectory("routing-embedding-").toFile()
        val substrateFactory = SubstrateDatabaseFactory(dataRoot)
        val sourceRunId = "source-run"
        val sourceRoot = substrateFactory.runRootDir(sourceRunId)
        java.io.File(sourceRoot, "results").mkdirs()

        val completionRecord = RunCompletionRecord(
            runId = sourceRunId,
            winnerId = "group-0",
            finalAnswerSummary = "routing routing graph",
            graphMessage = RunGraphMessage(
                runId = sourceRunId,
                headline = "routing routing graph",
                routedContextSummaries = listOf(
                    "routing routing graph",
                    "routing graph graph"
                )
            )
        )
        substrateFactory.get(sourceRunId, "__meta_archive__").metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = "completion-$sourceRunId",
                runId = sourceRunId,
                entryType = RunArchiveService.ENTRY_RUN_COMPLETED,
                groupId = null,
                label = "Run completed",
                contentJson = json.encodeToString(RunCompletionRecord.serializer(), completionRecord),
                runObjective = "source objective",
                finalScore = null,
                isFromWinner = true,
                generation = 1,
                archivedAtMs = System.currentTimeMillis()
            )
        )

        val service = EvoKammerRunService(
            lifecycleManagerProvider = { _, _, _, _, _ -> error("unused") },
            groupCoordinatorProvider = { _, _ -> error("unused") },
            graderGroupManagerProvider = { _ -> error("unused") },
            substrateFactory = substrateFactory,
            shuruVmManager = ShuruVmManager(),
            metaPlannerRunner = MetaPlannerRunner()
        )

        val routed = service.routeContinuationContextSummaries(
            sourceRunId = sourceRunId,
            sourceStartingField = StartingField.default(
                objective = "source objective",
                runId = sourceRunId,
                groupCount = 1,
                groupProfilesMap = mapOf("group-0" to GroupProfileType.Library)
            ),
            objective = "routing routing graph",
            maxSummaries = 1
        )

        assertEquals(1, routed.size)
        assertTrue(routed.single().contains("routing routing graph"))
    }

    @Test
    fun `continuation routing prefers an ancestor graph message when the source run lacks one`() = runBlocking {
        val dataRoot = createTempDirectory("routing-ancestor-graph-message-").toFile()
        val substrateFactory = SubstrateDatabaseFactory(dataRoot)
        val ancestorRunId = "ancestor-run"
        val sourceRunId = "source-run"

        val ancestorStartingField = StartingField.default(
            objective = "Ancestor objective",
            runId = ancestorRunId,
            groupCount = 1,
            groupProfilesMap = mapOf("group-0" to GroupProfileType.Library)
        )
        val sourceStartingField = StartingField.default(
            objective = "Source objective",
            runId = sourceRunId,
            groupCount = 1,
            groupProfilesMap = mapOf("group-0" to GroupProfileType.Library)
        ).copy(
            continuedFromRunId = ancestorRunId,
            runGraphEdges = listOf(
                com.achulkov.evokammer.core.contract.RunGraphEdge(
                    sourceRunId = ancestorRunId,
                    targetRunId = sourceRunId,
                    edgeType = "continuation",
                    weight = 1.0
                )
            )
        )

        val ancestorRoot = substrateFactory.runRootDir(ancestorRunId)
        val ancestorResults = java.io.File(ancestorRoot, "results")
        ancestorResults.mkdirs()
        java.io.File(ancestorResults, "high-level-run-log.txt").writeText("ancestor artifact headline")

        val ancestorCompletion = RunCompletionRecord(
            runId = ancestorRunId,
            winnerId = "group-0",
            finalAnswerSummary = "ancestor artifact final answer",
            finalDeliverableHtml = "<html><body>ancestor artifact deliverable</body></html>",
            graphMessage = RunGraphMessage(
                runId = ancestorRunId,
                headline = "ancestor graph envelope headline",
                routedContextSummaries = listOf(
                    "high-level-run-log: ancestor graph envelope headline",
                    "final-answer-summary: ancestor graph envelope final answer"
                )
            )
        )
        val sourceRoot = substrateFactory.runRootDir(sourceRunId)
        val sourceResults = java.io.File(sourceRoot, "results")
        sourceResults.mkdirs()
        java.io.File(sourceResults, "high-level-run-log.txt").writeText("source artifact headline")

        val sourceCompletion = RunCompletionRecord(
            runId = sourceRunId,
            winnerId = "group-0",
            finalAnswerSummary = "source artifact final answer",
            finalDeliverableHtml = "<html><body>source artifact deliverable</body></html>",
            judgeOutputsByGrader = mapOf("grader-0" to "source judge output"),
            submissionSummariesByGroup = mapOf("group-0" to "source group summary")
        )

        val archiveDb = substrateFactory.get(ancestorRunId, "__meta_archive__")
        archiveDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = "starting-field-$ancestorRunId",
                runId = ancestorRunId,
                entryType = RunArchiveService.ENTRY_STARTING_FIELD,
                groupId = null,
                label = "StartingField — $ancestorRunId",
                contentJson = json.encodeToString(StartingField.serializer(), ancestorStartingField),
                runObjective = ancestorStartingField.objective,
                finalScore = null,
                isFromWinner = false,
                generation = 0,
                archivedAtMs = System.currentTimeMillis()
            )
        )
        archiveDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = "completion-$ancestorRunId",
                runId = ancestorRunId,
                entryType = RunArchiveService.ENTRY_RUN_COMPLETED,
                groupId = null,
                label = "Run completed",
                contentJson = json.encodeToString(RunCompletionRecord.serializer(), ancestorCompletion),
                runObjective = ancestorStartingField.objective,
                finalScore = null,
                isFromWinner = true,
                generation = 1,
                archivedAtMs = System.currentTimeMillis()
            )
        )

        val sourceArchiveDb = substrateFactory.get(sourceRunId, "__meta_archive__")
        sourceArchiveDb.metaArchiveDao().insert(
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
        sourceArchiveDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = "completion-$sourceRunId",
                runId = sourceRunId,
                entryType = RunArchiveService.ENTRY_RUN_COMPLETED,
                groupId = null,
                label = "Run completed",
                contentJson = json.encodeToString(RunCompletionRecord.serializer(), sourceCompletion),
                runObjective = sourceStartingField.objective,
                finalScore = null,
                isFromWinner = true,
                generation = 1,
                archivedAtMs = System.currentTimeMillis()
            )
        )

        val service = EvoKammerRunService(
            lifecycleManagerProvider = { _, _, _, _, _ -> error("unused") },
            groupCoordinatorProvider = { _, _ -> error("unused") },
            graderGroupManagerProvider = { _ -> error("unused") },
            substrateFactory = substrateFactory,
            shuruVmManager = ShuruVmManager(),
            metaPlannerRunner = MetaPlannerRunner()
        )

        val routed = service.routeContinuationContextSummaries(
            sourceRunId = sourceRunId,
            sourceStartingField = sourceStartingField,
            objective = "ancestor graph envelope final answer",
            maxSummaries = 2
        )

        assertEquals(2, routed.size)
        assertTrue(routed.all { it.contains("ancestor graph envelope") })
        assertTrue(routed.none { it.contains("source artifact headline") })
    }

    @Test
    fun `legacy completion records without graph envelopes still route from file artefacts`() = runBlocking {
        val dataRoot = createTempDirectory("routing-legacy-fallback-").toFile()
        val substrateFactory = SubstrateDatabaseFactory(dataRoot)
        val sourceRunId = "source-run"
        val sourceRoot = substrateFactory.runRootDir(sourceRunId)
        val resultsDir = java.io.File(sourceRoot, "results")
        resultsDir.mkdirs()
        java.io.File(resultsDir, "high-level-run-log.txt").writeText("legacy artifact headline")

        val completionRecord = RunCompletionRecord(
            runId = sourceRunId,
            winnerId = "group-0",
            finalAnswerSummary = "legacy final answer headline",
            finalDeliverableHtml = "<html><body>legacy deliverable headline</body></html>",
            graphMessage = null,
            judgeOutputsByGrader = mapOf("grader-0" to "legacy judge output headline"),
            submissionSummariesByGroup = mapOf("group-0" to "legacy group summary headline")
        )
        val archiveDb = substrateFactory.get(sourceRunId, "__meta_archive__")
        archiveDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = "starting-field-$sourceRunId",
                runId = sourceRunId,
                entryType = RunArchiveService.ENTRY_STARTING_FIELD,
                groupId = null,
                label = "StartingField — $sourceRunId",
                contentJson = json.encodeToString(
                    StartingField.serializer(),
                    StartingField.default(
                        objective = "source objective",
                        runId = sourceRunId,
                        groupCount = 1,
                        groupProfilesMap = mapOf("group-0" to GroupProfileType.Library)
                    )
                ),
                runObjective = "source objective",
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
                contentJson = json.encodeToString(RunCompletionRecord.serializer(), completionRecord),
                runObjective = "source objective",
                finalScore = null,
                isFromWinner = true,
                generation = 1,
                archivedAtMs = System.currentTimeMillis()
            )
        )

        val service = EvoKammerRunService(
            lifecycleManagerProvider = { _, _, _, _, _ -> error("unused") },
            groupCoordinatorProvider = { _, _ -> error("unused") },
            graderGroupManagerProvider = { _ -> error("unused") },
            substrateFactory = substrateFactory,
            shuruVmManager = ShuruVmManager(),
            metaPlannerRunner = MetaPlannerRunner()
        )

        val routed = service.routeContinuationContextSummaries(
            sourceRunId = sourceRunId,
            sourceStartingField = StartingField.default(
                objective = "source objective",
                runId = sourceRunId,
                groupCount = 1,
                groupProfilesMap = mapOf("group-0" to GroupProfileType.Library)
            ),
            objective = "legacy artifact headline",
            maxSummaries = 1
        )

        assertEquals(1, routed.size)
        assertTrue(routed.single().startsWith("high-level-run-log:"))
        assertTrue(routed.single().contains("legacy artifact headline"))
    }

    @Test
    fun `dedicated run graph message entry wins over the embedded completion payload`() = runBlocking {
        val dataRoot = createTempDirectory("routing-dedicated-graph-entry-").toFile()
        val substrateFactory = SubstrateDatabaseFactory(dataRoot)
        val sourceRunId = "source-run"
        val sourceRoot = substrateFactory.runRootDir(sourceRunId)
        val resultsDir = java.io.File(sourceRoot, "results")
        resultsDir.mkdirs()
        java.io.File(resultsDir, "high-level-run-log.txt").writeText("legacy file headline")

        val completionRecord = RunCompletionRecord(
            runId = sourceRunId,
            winnerId = "group-0",
            finalAnswerSummary = "completion payload headline",
            finalDeliverableHtml = "<html><body>completion payload deliverable</body></html>",
            graphMessage = RunGraphMessage(
                runId = sourceRunId,
                headline = "completion payload headline",
                routedContextSummaries = listOf("high-level-run-log: completion payload headline")
            )
        )
        val dedicatedGraphMessage = RunGraphMessage(
            runId = sourceRunId,
            headline = "dedicated bus headline",
            routedContextSummaries = listOf(
                "high-level-run-log: dedicated bus headline",
                "final-answer-summary: dedicated bus headline"
            )
        )
        val archiveDb = substrateFactory.get(sourceRunId, "__meta_archive__")
        archiveDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = "starting-field-$sourceRunId",
                runId = sourceRunId,
                entryType = RunArchiveService.ENTRY_STARTING_FIELD,
                groupId = null,
                label = "StartingField — $sourceRunId",
                contentJson = json.encodeToString(
                    StartingField.serializer(),
                    StartingField.default(
                        objective = "source objective",
                        runId = sourceRunId,
                        groupCount = 1,
                        groupProfilesMap = mapOf("group-0" to GroupProfileType.Library)
                    )
                ),
                runObjective = "source objective",
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
                contentJson = json.encodeToString(RunCompletionRecord.serializer(), completionRecord),
                runObjective = "source objective",
                finalScore = null,
                isFromWinner = true,
                generation = 1,
                archivedAtMs = System.currentTimeMillis()
            )
        )
        archiveDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = "graph-$sourceRunId",
                runId = sourceRunId,
                entryType = RunArchiveService.ENTRY_RUN_GRAPH_MESSAGE,
                groupId = null,
                label = "Run Graph Message — $sourceRunId",
                contentJson = json.encodeToString(RunGraphMessage.serializer(), dedicatedGraphMessage),
                runObjective = "source objective",
                finalScore = null,
                isFromWinner = true,
                generation = 1,
                archivedAtMs = System.currentTimeMillis()
            )
        )

        val service = EvoKammerRunService(
            lifecycleManagerProvider = { _, _, _, _, _ -> error("unused") },
            groupCoordinatorProvider = { _, _ -> error("unused") },
            graderGroupManagerProvider = { _ -> error("unused") },
            substrateFactory = substrateFactory,
            shuruVmManager = ShuruVmManager(),
            metaPlannerRunner = MetaPlannerRunner()
        )

        val routed = service.routeContinuationContextSummaries(
            sourceRunId = sourceRunId,
            sourceStartingField = StartingField.default(
                objective = "source objective",
                runId = sourceRunId,
                groupCount = 1,
                groupProfilesMap = mapOf("group-0" to GroupProfileType.Library)
            ),
            objective = "dedicated bus headline",
            maxSummaries = 2
        )

        assertEquals(2, routed.size)
        assertTrue(routed.all { it.contains("dedicated bus headline") })
        assertTrue(routed.none { it.contains("completion payload headline") })
        assertTrue(routed.none { it.contains("legacy file headline") })
    }

    @Test
    fun `fresh runs seed the first feedforward layer automatically`() {
        val service = EvoKammerRunService(
            lifecycleManagerProvider = { _, _, _, _, _ -> error("unused") },
            groupCoordinatorProvider = { _, _ -> error("unused") },
            graderGroupManagerProvider = { _ -> error("unused") },
            substrateFactory = SubstrateDatabaseFactory(createTempDirectory("seed-layer-").toFile()),
            metaPlannerRunner = MetaPlannerRunner()
        )

        val seeded = service.seedFeedforwardLayerIfMissing(
            StartingField.default(
                objective = "Fresh layered run",
                runId = "fresh-run",
                groupCount = 1,
                groupProfilesMap = mapOf("group-0" to GroupProfileType.Library)
            )
        )

        assertEquals(0, seeded.feedforwardLayer?.layerIndex)
        assertEquals("L1", seeded.feedforwardLayer?.layerLabel)
        assertEquals(emptyList(), seeded.feedforwardLayer?.upstreamRunIds)
        assertEquals("Fresh layered run", seeded.feedforwardLayer?.layerObjective)
    }
}
