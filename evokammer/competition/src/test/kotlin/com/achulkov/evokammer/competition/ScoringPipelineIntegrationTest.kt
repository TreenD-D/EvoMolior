package com.achulkov.evokammer.competition

import com.achulkov.evokammer.competition.grader.AutomatedTest
import com.achulkov.evokammer.competition.grader.FailureMode
import com.achulkov.evokammer.competition.grader.GradingSheet
import com.achulkov.evokammer.competition.grader.MetaJudge
import com.achulkov.evokammer.competition.grader.RubricDimension
import com.achulkov.evokammer.competition.scoring.BlindSubmissionGate
import com.achulkov.evokammer.competition.scoring.GroupRoundResult
import com.achulkov.evokammer.competition.scoring.RevealedScores
import com.achulkov.evokammer.competition.scoring.ScoringEngine
import com.achulkov.evokammer.core.model.GradingResult
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Integration tests for the competition scoring pipeline:
 *   BlindSubmissionGate state-machine + ScoringEngine scoring math + MetaJudge quality.
 */
class ScoringPipelineIntegrationTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun makeGradingSheet(
        roundIndex: Int = 0,
        graderGroupId: String = "grader-alpha",
        dimensions: List<Pair<String, Double>> = listOf("accuracy" to 0.6, "novelty" to 0.4)
    ): GradingSheet = GradingSheet(
        sheetId = "sheet-$roundIndex-$graderGroupId",
        runId = "run-test",
        roundIndex = roundIndex,
        graderGroupId = graderGroupId,
        rubricDimensions = dimensions.map { (id, w) ->
            RubricDimension(dimensionId = id, name = id, weight = w, scoringGuidance = "Score $id")
        },
        automatedTests = listOf(
            AutomatedTest("t1", "basic check", ".*", penaltyOnFailure = 1.0, isMandatory = false)
        ),
        failureModes = listOf(
            FailureMode("fm-plagiarism", "Copy-paste detected", penaltyWeight = 2.0)
        ),
        comparativePairs = emptyList(),
        sealedAt = System.currentTimeMillis()
    )

    /** Directly compute scores via ScoringEngine using a pre-built RevealedScores. */
    private suspend fun scoreViaEngine(
        roundIndex: Int,
        groupResults: Map<String, GroupRoundResult>,
        sheet: GradingSheet
    ): GradingResult = coroutineScope {
        val metaJudge = MetaJudge(minGraderGroups = 1)
        val gate = BlindSubmissionGate(groupResults.keys.toList(), gateTimeoutMs = 60_000L)
        val engine = ScoringEngine(metaJudge, gate)
        engine.registerGradingSheet(sheet)

        // Subscribe FIRST using async, then yield to let the subscriber register, then emit
        val deferred = async { engine.gradingResults.first() }
        yield()

        val revealed = RevealedScores(
            roundIndex = roundIndex,
            scores = groupResults,
            revealedAt = System.currentTimeMillis(),
            wasForced = false,
            missingGroupIds = emptyList()
        )
        engine.processRevealedScores(revealed)

        deferred.await()
    }

    // ── BlindSubmissionGate state-machine ─────────────────────────────────────

    @Test
    fun `gate starts closed and rejects submissions`() {
        val gate = BlindSubmissionGate(listOf("g1"))
        val ex = try {
            gate.submitGroupResults(
                "g1", GroupRoundResult(
                    groupId = "g1", roundIndex = 0,
                    dimensionScores = emptyMap(), failureModesTriggered = emptyList(),
                    automatedTestsPassed = 0, automatedTestsFailed = 0
                )
            )
            null
        } catch (e: IllegalStateException) { e }
        assertTrue(ex != null, "Should throw IllegalStateException when gate not open")
    }

    @Test
    fun `gate rejects unknown group`() {
        val gate = BlindSubmissionGate(listOf("g1"))
        gate.openGate(0)
        val ex = try {
            gate.submitGroupResults(
                "unknown", GroupRoundResult(
                    groupId = "unknown", roundIndex = 0,
                    dimensionScores = emptyMap(), failureModesTriggered = emptyList(),
                    automatedTestsPassed = 0, automatedTestsFailed = 0
                )
            )
            null
        } catch (e: IllegalArgumentException) { e }
        assertTrue(ex != null, "Should throw IllegalArgumentException for unknown group")
    }

    @Test
    fun `allSubmitted is false until all groups submit`() {
        val gate = BlindSubmissionGate(listOf("g1", "g2"))
        gate.openGate(0)

        assertFalse(gate.allSubmitted(), "No groups submitted yet")

        gate.submitGroupResults(
            "g1", GroupRoundResult(
                groupId = "g1", roundIndex = 0,
                dimensionScores = emptyMap(), failureModesTriggered = emptyList(),
                automatedTestsPassed = 0, automatedTestsFailed = 0
            )
        )
        assertFalse(gate.allSubmitted(), "Only one group submitted")

        gate.submitGroupResults(
            "g2", GroupRoundResult(
                groupId = "g2", roundIndex = 0,
                dimensionScores = emptyMap(), failureModesTriggered = emptyList(),
                automatedTestsPassed = 0, automatedTestsFailed = 0
            )
        )
        assertTrue(gate.allSubmitted(), "Both groups submitted")
    }

    @Test
    fun `gate refuses double-open`() {
        val gate = BlindSubmissionGate(listOf("g1"))
        gate.openGate(0)
        val ex = try { gate.openGate(1); null } catch (e: IllegalStateException) { e }
        assertTrue(ex != null, "Should throw on double openGate")
    }

    @Test
    fun `gate force reveals missing groups when timeout boundary is reached`() = runTest {
        val gate = BlindSubmissionGate(listOf("g1", "g2"))
        val revealedDeferred = async { gate.revealedScores.first() }
        yield()

        gate.openGate(0)
        gate.submitGroupResults(
            "g1", GroupRoundResult(
                groupId = "g1", roundIndex = 0,
                dimensionScores = mapOf("accuracy" to 5.0),
                failureModesTriggered = emptyList(),
                automatedTestsPassed = 1, automatedTestsFailed = 0
            )
        )

        gate.closeAndReveal(forceReveal = true)

        val revealed = revealedDeferred.await()
        assertTrue(revealed.wasForced, "Force reveal should be marked as forced")
        assertEquals(listOf("g2"), revealed.missingGroupIds)
        assertTrue(revealed.scores["g2"]?.isDefaultZeroScore == true)
    }

    @Test
    fun `blind gate does not reveal scores before close`() = runTest {
        val gate = BlindSubmissionGate(listOf("g1", "g2"))
        val revealedDeferred = async { gate.revealedScores.first() }
        yield()

        gate.openGate(0)
        gate.submitGroupResults(
            "g1", GroupRoundResult(
                groupId = "g1", roundIndex = 0,
                dimensionScores = mapOf("accuracy" to 5.0),
                failureModesTriggered = emptyList(),
                automatedTestsPassed = 1, automatedTestsFailed = 0
            )
        )

        yield()
        assertFalse(revealedDeferred.isCompleted, "Gate must not expose scores before closeAndReveal")

        gate.closeAndReveal(forceReveal = true)
        val revealed = revealedDeferred.await()
        assertEquals(0, revealed.scores["g2"]?.automatedTestsFailed)
    }

    // ── ScoringEngine math ────────────────────────────────────────────────────

    @Test
    fun `higher raw scores produce rank 1`() = runTest {
        val result = scoreViaEngine(
            roundIndex = 0,
            groupResults = mapOf(
                "alpha" to GroupRoundResult(
                    groupId = "alpha", roundIndex = 0,
                    dimensionScores = mapOf("accuracy" to 9.0, "novelty" to 8.0),
                    failureModesTriggered = emptyList(),
                    automatedTestsPassed = 1, automatedTestsFailed = 0
                ),
                "beta" to GroupRoundResult(
                    groupId = "beta", roundIndex = 0,
                    dimensionScores = mapOf("accuracy" to 4.0, "novelty" to 3.0),
                    failureModesTriggered = emptyList(),
                    automatedTestsPassed = 1, automatedTestsFailed = 0
                )
            ),
            sheet = makeGradingSheet(roundIndex = 0)
        )
        assertEquals("alpha", result.winnerId, "Alpha should win with higher scores")
        assertEquals(1, result.scores.first { it.groupId == "alpha" }.rank)
        assertEquals(2, result.scores.first { it.groupId == "beta" }.rank)
    }

    @Test
    fun `failure mode penalty reduces weighted score`() = runTest {
        val result = scoreViaEngine(
            roundIndex = 0,
            groupResults = mapOf(
                "cheater" to GroupRoundResult(
                    groupId = "cheater", roundIndex = 0,
                    dimensionScores = mapOf("accuracy" to 10.0, "novelty" to 10.0),
                    failureModesTriggered = listOf("fm-plagiarism"),  // -2.0 penalty
                    automatedTestsPassed = 1, automatedTestsFailed = 0
                ),
                "clean" to GroupRoundResult(
                    groupId = "clean", roundIndex = 0,
                    dimensionScores = mapOf("accuracy" to 7.0, "novelty" to 7.0),
                    failureModesTriggered = emptyList(),
                    automatedTestsPassed = 1, automatedTestsFailed = 0
                )
            ),
            sheet = makeGradingSheet(roundIndex = 0)
        )
        val cheaterScore = result.scores.first { it.groupId == "cheater" }.weightedTotal
        // Raw weighted for cheater: ~10.0 - 2.0 penalty = 8.0
        assertTrue(cheaterScore < 10.0, "Penalty must reduce cheater's score below perfect")
        assertTrue(cheaterScore >= 0.0, "Score must not go negative")
    }

    @Test
    fun `automated test failures reduce blind score`() = runTest {
        val mandatorySheet = GradingSheet(
            sheetId = "mandatory-sheet",
            runId = "run-test",
            roundIndex = 0,
            graderGroupId = "grader-mandatory",
            rubricDimensions = listOf(
                RubricDimension("accuracy", "accuracy", 1.0, "Score accuracy")
            ),
            automatedTests = listOf(
                AutomatedTest("must-pass", "Must pass", "alpha", penaltyOnFailure = 3.0, isMandatory = true)
            ),
            failureModes = emptyList(),
            comparativePairs = emptyList(),
            sealedAt = System.currentTimeMillis()
        )

        val result = scoreViaEngine(
            roundIndex = 0,
            groupResults = mapOf(
                "pass" to GroupRoundResult(
                    groupId = "pass", roundIndex = 0,
                    dimensionScores = mapOf("accuracy" to 10.0),
                    failureModesTriggered = emptyList(),
                    automatedTestsPassed = 1, automatedTestsFailed = 0
                ),
                "fail" to GroupRoundResult(
                    groupId = "fail", roundIndex = 0,
                    dimensionScores = mapOf("accuracy" to 10.0),
                    failureModesTriggered = emptyList(),
                    automatedTestsPassed = 0, automatedTestsFailed = 1
                )
            ),
            sheet = mandatorySheet
        )

        val passScore = result.scores.first { it.groupId == "pass" }.weightedTotal
        val failScore = result.scores.first { it.groupId == "fail" }.weightedTotal
        assertTrue(failScore < passScore, "Failed automated tests must reduce the reveal-phase score")
    }

    @Test
    fun `zero-score result is produced for group with no dimension scores`() = runTest {
        val result = scoreViaEngine(
            roundIndex = 0,
            groupResults = mapOf(
                "present" to GroupRoundResult(
                    groupId = "present", roundIndex = 0,
                    dimensionScores = mapOf("accuracy" to 8.0, "novelty" to 7.0),
                    failureModesTriggered = emptyList(),
                    automatedTestsPassed = 1, automatedTestsFailed = 0
                ),
                "absent" to GroupRoundResult(
                    groupId = "absent", roundIndex = 0,
                    dimensionScores = emptyMap(),   // no scores
                    failureModesTriggered = emptyList(),
                    automatedTestsPassed = 0, automatedTestsFailed = 0,
                    isDefaultZeroScore = true
                )
            ),
            sheet = makeGradingSheet(roundIndex = 0)
        )
        val absentScore = result.scores.first { it.groupId == "absent" }.weightedTotal
        assertEquals(0.0, absentScore, "Absent group should have zero weighted score")
        assertEquals("present", result.winnerId)
    }

    // ── MetaJudge quality scoring ─────────────────────────────────────────────

    @Test
    fun `minimal sheet scores lower quality than rich sheet`() {
        val metaJudge = MetaJudge(minGraderGroups = 1)

        val minimalSheet = GradingSheet(
            sheetId = "min-sheet",
            runId = "run-test",
            roundIndex = 0,
            graderGroupId = "grader-x",
            rubricDimensions = listOf(
                RubricDimension("d1", "d1", 1.0, "test")
            ),
            automatedTests = emptyList(),
            failureModes = emptyList(),
            comparativePairs = emptyList(),
            sealedAt = System.currentTimeMillis()
        )

        val richSheet = GradingSheet(
            sheetId = "rich-sheet",
            runId = "run-test",
            roundIndex = 1,
            graderGroupId = "grader-y",
            rubricDimensions = (1..5).map { i ->
                RubricDimension("dim$i", "Dim $i", 0.2, "Score dimension $i")
            },
            automatedTests = (1..10).map { i ->
                AutomatedTest("t$i", "Test $i", ".*", 0.5)
            },
            failureModes = (1..5).map { i ->
                FailureMode("fm$i", "Failure mode $i", 0.5)
            },
            comparativePairs = emptyList(),
            sealedAt = System.currentTimeMillis()
        )

        metaJudge.registerSheet(minimalSheet)
        metaJudge.registerSheet(richSheet)

        val minVerdict = metaJudge.verdict(0)
        val richVerdict = metaJudge.verdict(1)

        assertTrue(
            richVerdict.averageSheetQuality > minVerdict.averageSheetQuality,
            "Rich sheet (${richVerdict.averageSheetQuality}) should score higher quality than minimal (${minVerdict.averageSheetQuality})"
        )
    }

    @Test
    fun `metajudge merges dimension weights from single sheet correctly`() {
        val metaJudge = MetaJudge(minGraderGroups = 1)
        metaJudge.registerSheet(
            makeGradingSheet(roundIndex = 0, dimensions = listOf("accuracy" to 0.6, "novelty" to 0.4))
        )
        val verdict = metaJudge.verdict(0)

        assertEquals(2, verdict.dimensionWeights.size, "Should have 2 dimensions")
        // Weights should normalise to sum 1.0
        val weightSum = verdict.dimensionWeights.values.sum()
        assertTrue(
            Math.abs(weightSum - 1.0) < 0.01,
            "Merged dimension weights must sum to ~1.0, got $weightSum"
        )
    }
}
