package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.core.model.DimensionScore
import com.achulkov.evomolior.core.model.GradingResult
import com.achulkov.evomolior.core.model.RoundScore
import com.achulkov.evomolior.core.model.ScoringResolution
import com.achulkov.evomolior.core.contract.StartingField
import com.achulkov.evomolior.core.model.GroupProfileType
import com.achulkov.evomolior.orchestration.metaplanner.MetaPlannerRunner
import com.achulkov.evomolior.sandbox.shuru.ShuruVmManager
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.db.entity.MetaArchiveEntryEntity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.io.path.createTempDirectory
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.jvm.isAccessible
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdversarialDiscriminatorBriefTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `brief highlights weakest dimensions and submission headline`() {
        val brief = buildAdversarialDiscriminatorBriefs(
            gradingResult = GradingResult(
                roundIndex = 2,
                scores = listOf(
                    RoundScore(
                        groupId = "group-0",
                        roundIndex = 2,
                        dimensionScores = listOf(
                            DimensionScore("kb_density", 82.0, "Dense KB with evidence."),
                            DimensionScore("kg_coherence", 41.0, "KG is sparse and inconsistent."),
                            DimensionScore("tool_utility", 63.0, "Useful tools but few are exercised."),
                            DimensionScore("plan_execution", 38.0, "Plan execution is uneven.")
                        ),
                        weightedTotal = 56.75,
                        rank = 2
                    )
                ),
                winnerId = "group-1"
            ),
            submissionSummariesByGroup = mapOf(
                "group-0" to """ROUND SUBMISSION SUMMARY
- Built a draft.
- Needs stronger evidence."""
            )
        )["group-0"] ?: error("missing brief")

        assertContains(brief, "adversarial discriminator brief")
        assertContains(brief, "group-0")
        assertContains(brief, "winner=group-1")
        assertContains(brief, "kg_coherence")
        assertContains(brief, "plan_execution")
        assertContains(brief, "Submission summary headline")
    }

    @Test
    fun `grader feedback brief converts weak scores into next round constraints`() {
        val brief = buildGraderFeedbackBriefs(
            GradingResult(
                roundIndex = 0,
                scores = listOf(
                    RoundScore(
                        groupId = "group-0",
                        roundIndex = 0,
                        dimensionScores = listOf(
                            DimensionScore("evidence", 22.0, "Claims lack checkable sources."),
                            DimensionScore("tooling", 65.0, "Some reusable validation exists."),
                            DimensionScore("kg", 18.0, "No promoted graph facts.")
                        ),
                        weightedTotal = 35.0,
                        rank = 2
                    )
                ),
                winnerId = "group-1"
            )
        ).getValue("group-0")

        assertContains(brief, "grader-group feedback")
        assertContains(brief, "Next-round improvement constraints")
        assertContains(brief, "Improve kg")
        assertContains(brief, "Improve evidence")
        assertContains(brief, "Produce substrate artifacts")
    }

    @Test
    fun `degenerate briefs state unresolved tie and concrete missing evidence`() {
        val result = GradingResult(
            roundIndex = 0,
            scores = listOf(
                RoundScore(
                    groupId = "group-z",
                    roundIndex = 0,
                    dimensionScores = listOf(
                        DimensionScore(
                            "evidence",
                            0.0,
                            "Missing evidence: no submitted evidence score or artifact citation."
                        )
                    ),
                    weightedTotal = 0.0,
                    rank = 2,
                    paretoFront = 1
                )
            ),
            winnerId = null,
            resolution = ScoringResolution.ALL_ZERO_DEGENERACY
        )

        val discriminator = buildAdversarialDiscriminatorBriefs(
            result,
            submissionSummariesByGroup = emptyMap()
        ).getValue("group-z")
        val grader = buildGraderFeedbackBriefs(result).getValue("group-z")

        assertContains(discriminator, "unresolved all-zero scoring degeneracy")
        assertContains(discriminator, "no substantive winner")
        assertContains(discriminator, "Compatibility rank 2 is non-substantive")
        assertContains(discriminator, "Missing discriminatory evidence")
        assertContains(discriminator, "artifact citations")
        assertContains(grader, "unresolved all-zero scoring degeneracy")
        assertContains(grader, "executed test outcomes")
        assertTrue("Auto-computed" !in discriminator)
        assertTrue("Auto-computed" !in grader)
    }

    @Test
    fun `all-equal grader feedback asks for evidence that separates submissions`() {
        val brief = buildGraderFeedbackBriefs(
            GradingResult(
                roundIndex = 1,
                scores = listOf(
                    RoundScore(
                        groupId = "group-a",
                        roundIndex = 1,
                        dimensionScores = listOf(
                            DimensionScore("correctness", 50.0, "No artifact-level rationale supplied.")
                        ),
                        weightedTotal = 50.0,
                        rank = 1,
                        paretoFront = 1
                    )
                ),
                winnerId = null,
                resolution = ScoringResolution.ALL_EQUAL_TIE
            )
        ).getValue("group-a")

        assertContains(brief, "unresolved all-equal scoring tie")
        assertContains(brief, "no between-group separation")
        assertContains(brief, "dimension-specific grader observations")
    }

    @Test
    fun `grader feedback brief is persisted and injected into disclosure`() = runBlocking {
        val dataRoot = createTempDirectory("grader-feedback-brief-").toFile()
        val substrateFactory = SubstrateDatabaseFactory(dataRoot)
        val runId = "run-grader-feedback"
        seedStartingField(substrateFactory, runId, "Improve group outputs")
        val service = buildService(substrateFactory)
        val brief = mapOf("group-0" to "Round 1 grader-group feedback\n- Improve evidence.")

        service.persistGraderFeedbackBriefs(
            runId = runId,
            roundIndex = 0,
            briefs = brief
        )

        assertEquals(brief.getValue("group-0"), service.readGraderFeedbackBrief(runId, "group-0"))

        val method = service::class.declaredFunctions.first {
            it.name == "composeStageDisclosureWithDiscriminatorFeedback"
        }.apply { isAccessible = true }
        val disclosure = method.callSuspend(service, runId, "group-0", "base disclosure") as String

        assertContains(disclosure, "=== ROUND ADAPTATION CONTRACT ===")
        assertContains(disclosure, "compact Genesis Council rerun")
        assertContains(disclosure, "improvement, expansion, validation, or robustness check")
        assertContains(disclosure, "=== GRADER GROUP FEEDBACK ===")
        assertContains(disclosure, "Improve evidence")
        assertContains(disclosure, "base disclosure")
    }

    @Test
    fun `adversarial discriminator briefs are persisted and recoverable from archive`() = runBlocking {
        val dataRoot = createTempDirectory("adversarial-brief-").toFile()
        val substrateFactory = SubstrateDatabaseFactory(dataRoot)
        val runId = "run-1"
        val archiveDb = substrateFactory.get(runId, "__meta_archive__")
        archiveDb.metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = "starting-field-$runId",
                runId = runId,
                entryType = RunArchiveService.ENTRY_STARTING_FIELD,
                groupId = null,
                label = "StartingField — $runId",
                contentJson = json.encodeToString(
                    StartingField.serializer(),
                    StartingField.default(
                        objective = "Critic objective",
                        runId = runId,
                        groupCount = 1,
                        groupProfilesMap = mapOf("group-0" to GroupProfileType.Library)
                    )
                ),
                runObjective = "Critic objective",
                finalScore = null,
                isFromWinner = false,
                generation = 0,
                archivedAtMs = System.currentTimeMillis()
            )
        )

        val service = EvoMoliorRunService(
            lifecycleManagerProvider = { _, _, _, _, _ -> error("unused") },
            groupCoordinatorProvider = { _, _ -> error("unused") },
            graderGroupManagerProvider = { _ -> error("unused") },
            substrateFactory = substrateFactory,
            shuruVmManager = ShuruVmManager(),
            metaPlannerRunner = MetaPlannerRunner()
        )
        val brief = mapOf("group-0" to buildAdversarialDiscriminatorBrief(
            roundScore = RoundScore(
                groupId = "group-0",
                roundIndex = 0,
                dimensionScores = listOf(
                    DimensionScore("kb_density", 82.0, "Dense KB with evidence."),
                    DimensionScore("kg_coherence", 41.0, "KG is sparse and inconsistent.")
                ),
                weightedTotal = 61.5,
                rank = 2
            ),
            winnerId = "group-1",
            submissionSummary = "ROUND SUBMISSION SUMMARY\n- Needs stronger evidence."
        ))

        service.persistAdversarialDiscriminatorBriefs(
            runId = runId,
            roundIndex = 0,
            briefs = brief,
            rewardMultiplier = 1.0
        )

        val stored = archiveDb.metaArchiveDao()
            .getByType(RunArchiveService.ENTRY_ADVERSARIAL_DISCRIMINATOR_FEEDBACK)
            .firstOrNull { it.runId == runId && it.groupId == "group-0" }
            ?: error("expected discriminator feedback entry")
        val decoded = json.decodeFromString(String.serializer(), stored.contentJson)

        assertEquals(brief.getValue("group-0"), decoded)
        assertContains(stored.label, "Adversarial Discriminator Feedback")

        val recovered = service.readAdversarialDiscriminatorBrief(runId, "group-0")
        assertEquals(brief.getValue("group-0"), recovered)
    }

    @Test
    fun `latest round discriminator brief replaces earlier archived briefs`() = runBlocking {
        val dataRoot = createTempDirectory("adversarial-brief-latest-").toFile()
        val substrateFactory = SubstrateDatabaseFactory(dataRoot)
        val runId = "run-1"
        seedStartingField(substrateFactory, runId, "Critic objective")

        val service = buildService(substrateFactory)
        service.persistAdversarialDiscriminatorBriefs(
            runId = runId,
            roundIndex = 0,
            briefs = mapOf("group-0" to "round-1 brief"),
            rewardMultiplier = 1.0
        )
        service.persistAdversarialDiscriminatorBriefs(
            runId = runId,
            roundIndex = 1,
            briefs = mapOf("group-0" to "round-2 brief"),
            rewardMultiplier = 1.0
        )

        val recovered = service.readAdversarialDiscriminatorBrief(runId, "group-0")
        assertEquals("round-2 brief", recovered)

        val entries = substrateFactory.get(runId, "__meta_archive__")
            .metaArchiveDao()
            .getByType(RunArchiveService.ENTRY_ADVERSARIAL_DISCRIMINATOR_FEEDBACK)
            .filter { it.runId == runId && it.groupId == "group-0" }
        assertEquals(2, entries.size)
        assertTrue(entries.any { it.generation == 0 })
        assertTrue(entries.any { it.generation == 1 })
    }

    @Test
    fun `fresh service instance injects archived discriminator brief into disclosure`() = runBlocking {
        val dataRoot = createTempDirectory("adversarial-brief-restart-").toFile()
        val substrateFactory = SubstrateDatabaseFactory(dataRoot)
        val runId = "run-1"
        seedStartingField(substrateFactory, runId, "Critic objective")

        val writerService = buildService(substrateFactory)
        val archivedBrief = "archived critic brief"
        writerService.persistAdversarialDiscriminatorBriefs(
            runId = runId,
            roundIndex = 0,
            briefs = mapOf("group-0" to archivedBrief),
            rewardMultiplier = 1.0
        )

        val freshService = buildService(substrateFactory)
        val method = freshService::class.declaredFunctions.first {
            it.name == "composeStageDisclosureWithDiscriminatorFeedback"
        }.apply { isAccessible = true }
        val disclosure = method.callSuspend(freshService, runId, "group-0", "base disclosure") as String

        assertContains(disclosure, "ADVERSARIAL DISCRIMINATOR FEEDBACK")
        assertContains(disclosure, archivedBrief)
        assertContains(disclosure, "base disclosure")
    }

    private fun buildService(substrateFactory: SubstrateDatabaseFactory) = EvoMoliorRunService(
        lifecycleManagerProvider = { _, _, _, _, _ -> error("unused") },
        groupCoordinatorProvider = { _, _ -> error("unused") },
        graderGroupManagerProvider = { _ -> error("unused") },
        substrateFactory = substrateFactory,
        shuruVmManager = ShuruVmManager(),
        metaPlannerRunner = MetaPlannerRunner()
    )

    private suspend fun seedStartingField(
        substrateFactory: SubstrateDatabaseFactory,
        runId: String,
        objective: String
    ) {
        substrateFactory.get(runId, "__meta_archive__").metaArchiveDao().insert(
            MetaArchiveEntryEntity(
                id = "starting-field-$runId",
                runId = runId,
                entryType = RunArchiveService.ENTRY_STARTING_FIELD,
                groupId = null,
                label = "StartingField — $runId",
                contentJson = json.encodeToString(
                    StartingField.serializer(),
                    StartingField.default(
                        objective = objective,
                        runId = runId,
                        groupCount = 1,
                        groupProfilesMap = mapOf("group-0" to GroupProfileType.Library)
                    )
                ),
                runObjective = objective,
                finalScore = null,
                isFromWinner = false,
                generation = 0,
                archivedAtMs = System.currentTimeMillis()
            )
        )
    }
}
