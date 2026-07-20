package com.achulkov.evomolior.competition

import com.achulkov.evomolior.competition.grader.AutomatedTest
import com.achulkov.evomolior.competition.grader.FailureMode
import com.achulkov.evomolior.competition.grader.GradingSheet
import com.achulkov.evomolior.competition.grader.MetaJudge
import com.achulkov.evomolior.competition.grader.RubricDimension
import com.achulkov.evomolior.competition.scoring.BlindSubmissionGate
import com.achulkov.evomolior.competition.scoring.GroupRoundResult
import com.achulkov.evomolior.competition.scoring.IncompleteBlindSubmissionException
import com.achulkov.evomolior.competition.scoring.IncompleteScoreEvidenceException
import com.achulkov.evomolior.competition.scoring.ScoreSealAudit
import com.achulkov.evomolior.competition.scoring.RevealedScores
import com.achulkov.evomolior.competition.scoring.ScoringEngine
import com.achulkov.evomolior.core.contract.DeliverableSkeletonNodeUpdate
import com.achulkov.evomolior.core.model.GradingResult
import com.achulkov.evomolior.core.model.ScoringResolution
import com.achulkov.evomolior.core.model.ScoringDimensionCatalog
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/**
 * Integration tests for the competition scoring pipeline:
 *   BlindSubmissionGate state-machine + ScoringEngine scoring math + MetaJudge quality.
 */
class ScoringPipelineIntegrationTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun makeGradingSheet(
        roundIndex: Int = 0,
        graderGroupId: String = "grader-alpha",
        dimensions: List<Pair<String, Double>> = listOf("accuracy" to 0.6, "novelty" to 0.4),
        dimensionNodeRefs: Map<String, String> = emptyMap()
    ): GradingSheet = GradingSheet(
        sheetId = "sheet-$roundIndex-$graderGroupId",
        runId = "run-test",
        roundIndex = roundIndex,
        graderGroupId = graderGroupId,
        rubricDimensions = dimensions.map { (id, w) ->
            RubricDimension(
                dimensionId = id,
                name = id,
                weight = w,
                scoringGuidance = "Score $id",
                dstNodeRef = dimensionNodeRefs[id]
            )
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

    private suspend fun deliverableUpdatesViaEngine(
        groupResults: Map<String, GroupRoundResult>,
        sheet: GradingSheet
    ): List<DeliverableSkeletonNodeUpdate> = coroutineScope {
        val metaJudge = MetaJudge(minGraderGroups = 1)
        val gate = BlindSubmissionGate(groupResults.keys.toList(), gateTimeoutMs = 60_000L)
        val engine = ScoringEngine(metaJudge, gate)
        engine.registerGradingSheet(sheet)

        val deferred = async { engine.deliverableNodeUpdates.first() }
        yield()
        engine.processRevealedScores(
            RevealedScores(
                roundIndex = sheet.roundIndex,
                scores = groupResults,
                revealedAt = System.currentTimeMillis(),
                wasForced = false,
                missingGroupIds = emptyList()
            )
        )
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
    fun `gate fails closed when timeout boundary has missing groups`() = runTest {
        val gate = BlindSubmissionGate(listOf("g1", "g2"))

        gate.openGate(0)
        gate.submitGroupResults(
            "g1", GroupRoundResult(
                groupId = "g1", roundIndex = 0,
                dimensionScores = mapOf("accuracy" to 5.0),
                failureModesTriggered = emptyList(),
                automatedTestsPassed = 1, automatedTestsFailed = 0
            )
        )

        val error = assertFailsWith<IncompleteBlindSubmissionException> {
            gate.closeAndReveal(forceReveal = true)
        }
        assertTrue(error.forcedReveal)
        assertEquals(listOf("g2"), error.missingGroupIds)
    }

    @Test
    fun `blind gate does not reveal scores before close`() = runTest {
        val gate = BlindSubmissionGate(listOf("g1", "g2"))

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
        assertFailsWith<IncompleteBlindSubmissionException> {
            gate.closeAndReveal(forceReveal = true)
        }
    }

    @Test
    fun `blind gate emits a seal audit when it closes`() = runTest {
        val gate = BlindSubmissionGate(listOf("g1", "g2"))
        val auditDeferred = async { gate.sealAuditEvents.first() }
        yield()

        gate.openGate(7)
        gate.submitGroupResults(
            "g1", GroupRoundResult(
                groupId = "g1", roundIndex = 7,
                dimensionScores = mapOf("accuracy" to 10.0),
                failureModesTriggered = emptyList(),
                automatedTestsPassed = 1, automatedTestsFailed = 0
            )
        )
        assertFailsWith<IncompleteBlindSubmissionException> {
            gate.closeAndReveal(forceReveal = true)
        }

        val audit: ScoreSealAudit = auditDeferred.await()
        assertEquals(7, audit.roundIndex)
        assertEquals(2, audit.expectedGroupCount)
        assertEquals(1, audit.sealedGroupCount)
        assertEquals(listOf("g2"), audit.missingGroupIds)
        assertTrue(audit.forcedReveal)
        assertTrue(audit.sealLatencyMs >= 0)
    }

    @Test
    fun `blind gate returns its seal audit synchronously with the reveal`() = runTest {
        val gate = BlindSubmissionGate(listOf("g1"))
        gate.openGate(3)
        gate.submitGroupResults(
            "g1",
            GroupRoundResult(
                groupId = "g1",
                roundIndex = 3,
                dimensionScores = mapOf("accuracy" to 8.0),
                failureModesTriggered = emptyList(),
                automatedTestsPassed = 1,
                automatedTestsFailed = 0
            )
        )

        val outcome = gate.closeAndRevealDetailed()

        assertEquals(3, outcome.revealedScores.roundIndex)
        assertEquals(setOf("g1"), outcome.revealedScores.scores.keys)
        val audit = checkNotNull(outcome.sealAudit)
        assertEquals(3, audit.roundIndex)
        assertEquals(1, audit.expectedGroupCount)
        assertEquals(1, audit.sealedGroupCount)
        assertTrue(audit.missingGroupIds.isEmpty())
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
        assertEquals(ScoringResolution.RESOLVED, result.resolution)
    }

    @Test
    fun `all-equal scoring preserves compatibility ranks without declaring a winner`() = runTest {
        val result = scoreViaEngine(
            roundIndex = 0,
            groupResults = mapOf(
                "zeta" to GroupRoundResult(
                    groupId = "zeta",
                    roundIndex = 0,
                    dimensionScores = mapOf("accuracy" to 7.0, "novelty" to 7.0),
                    failureModesTriggered = emptyList(),
                    automatedTestsPassed = 1,
                    automatedTestsFailed = 0
                ),
                "alpha" to GroupRoundResult(
                    groupId = "alpha",
                    roundIndex = 0,
                    dimensionScores = mapOf("accuracy" to 7.0, "novelty" to 7.0),
                    failureModesTriggered = emptyList(),
                    automatedTestsPassed = 1,
                    automatedTestsFailed = 0
                )
            ),
            sheet = makeGradingSheet(roundIndex = 0)
        )

        assertEquals(null, result.winnerId)
        assertEquals(ScoringResolution.ALL_EQUAL_TIE, result.resolution)
        assertEquals(listOf(1, 2), result.scores.map { it.rank })
        assertEquals(listOf("alpha", "zeta"), result.paretoFronts.single().groupIds)
    }

    @Test
    fun `all-zero scoring is explicitly degenerate without a lexical winner`() = runTest {
        val result = scoreViaEngine(
            roundIndex = 0,
            groupResults = mapOf(
                "zeta" to GroupRoundResult(
                    groupId = "zeta",
                    roundIndex = 0,
                    dimensionScores = mapOf("accuracy" to 0.0, "novelty" to 0.0),
                    failureModesTriggered = emptyList(),
                    automatedTestsPassed = 0,
                    automatedTestsFailed = 0
                ),
                "alpha" to GroupRoundResult(
                    groupId = "alpha",
                    roundIndex = 0,
                    dimensionScores = mapOf("accuracy" to 0.0, "novelty" to 0.0),
                    failureModesTriggered = emptyList(),
                    automatedTestsPassed = 0,
                    automatedTestsFailed = 0
                )
            ),
            sheet = makeGradingSheet(roundIndex = 0)
        )

        assertEquals(null, result.winnerId)
        assertEquals(ScoringResolution.ALL_ZERO_DEGENERACY, result.resolution)
        assertEquals("alpha", result.scores.single { it.rank == 1 }.groupId)
        assertTrue(result.scores.flatMap { it.dimensionScores }.all { it.rawScore == 0.0 })
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
    fun `blind scoring emits non-dominated Pareto front for tradeoffs`() = runTest {
        val result = scoreViaEngine(
            roundIndex = 0,
            groupResults = mapOf(
                "correct" to GroupRoundResult(
                    groupId = "correct", roundIndex = 0,
                    dimensionScores = mapOf(
                        ScoringDimensionCatalog.CORRECTNESS to 95.0,
                        ScoringDimensionCatalog.COST_EFFICIENCY to 40.0
                    ),
                    failureModesTriggered = emptyList(),
                    automatedTestsPassed = 1,
                    automatedTestsFailed = 0
                ),
                "efficient" to GroupRoundResult(
                    groupId = "efficient", roundIndex = 0,
                    dimensionScores = mapOf(
                        ScoringDimensionCatalog.CORRECTNESS to 70.0,
                        ScoringDimensionCatalog.COST_EFFICIENCY to 95.0
                    ),
                    failureModesTriggered = emptyList(),
                    automatedTestsPassed = 1,
                    automatedTestsFailed = 0
                ),
                "dominated" to GroupRoundResult(
                    groupId = "dominated", roundIndex = 0,
                    dimensionScores = mapOf(
                        ScoringDimensionCatalog.CORRECTNESS to 60.0,
                        ScoringDimensionCatalog.COST_EFFICIENCY to 30.0
                    ),
                    failureModesTriggered = emptyList(),
                    automatedTestsPassed = 1,
                    automatedTestsFailed = 0
                )
            ),
            sheet = makeGradingSheet(
                roundIndex = 0,
                dimensions = listOf(
                    ScoringDimensionCatalog.CORRECTNESS to 0.5,
                    ScoringDimensionCatalog.COST_EFFICIENCY to 0.5
                )
            )
        )

        assertEquals(listOf("efficient", "correct"), result.paretoFronts.first().groupIds)
        assertEquals(2, result.scores.first { it.groupId == "dominated" }.paretoFront)
        assertTrue("correct" in result.scores.first { it.groupId == "dominated" }.dominatedByGroupIds)
        assertTrue("efficient" in result.scores.first { it.groupId == "dominated" }.dominatedByGroupIds)
    }

    @Test
    fun `blind scoring fails closed when a risk dimension is missing`() = runTest {
        val error = assertFailsWith<IncompleteScoreEvidenceException> {
            scoreViaEngine(
                roundIndex = 0,
                groupResults = mapOf(
                    "reported-risk" to GroupRoundResult(
                        groupId = "reported-risk", roundIndex = 0,
                        dimensionScores = mapOf(
                            ScoringDimensionCatalog.CORRECTNESS to 80.0,
                            ScoringDimensionCatalog.SECURITY_RISK to 10.0
                        ),
                        failureModesTriggered = emptyList(),
                        automatedTestsPassed = 1,
                        automatedTestsFailed = 0
                    ),
                    "missing-risk" to GroupRoundResult(
                        groupId = "missing-risk", roundIndex = 0,
                        dimensionScores = mapOf(ScoringDimensionCatalog.CORRECTNESS to 80.0),
                        failureModesTriggered = emptyList(),
                        automatedTestsPassed = 1,
                        automatedTestsFailed = 0
                    )
                ),
                sheet = makeGradingSheet(
                    roundIndex = 0,
                    dimensions = listOf(
                        ScoringDimensionCatalog.CORRECTNESS to 0.5,
                        ScoringDimensionCatalog.SECURITY_RISK to 0.5
                    )
                )
            )
        }
        assertEquals(
            setOf(ScoringDimensionCatalog.SECURITY_RISK),
            error.missingDimensionIdsByGroup["missing-risk"]
        )
    }

    @Test
    fun `group with no dimension scores fails closed instead of becoming zero`() = runTest {
        val error = assertFailsWith<IncompleteScoreEvidenceException> {
            scoreViaEngine(
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
                        dimensionScores = emptyMap(),
                        failureModesTriggered = emptyList(),
                        automatedTestsPassed = 0, automatedTestsFailed = 0
                    )
                ),
                sheet = makeGradingSheet(roundIndex = 0)
            )
        }
        assertEquals(
            setOf("accuracy", "novelty"),
            error.missingDimensionIdsByGroup["absent"]
        )
    }

    @Test
    fun `deliverable threshold passes at 60 and fails immediately below it`() = runTest {
        val nodeRef = "dst:section:quality"
        val updates = deliverableUpdatesViaEngine(
            groupResults = mapOf(
                "at-boundary" to GroupRoundResult(
                    groupId = "at-boundary",
                    roundIndex = 0,
                    dimensionScores = mapOf(ScoringDimensionCatalog.CORRECTNESS to 60.0),
                    failureModesTriggered = emptyList(),
                    automatedTestsPassed = 1,
                    automatedTestsFailed = 0
                ),
                "below-boundary" to GroupRoundResult(
                    groupId = "below-boundary",
                    roundIndex = 0,
                    dimensionScores = mapOf(ScoringDimensionCatalog.CORRECTNESS to 59.9),
                    failureModesTriggered = emptyList(),
                    automatedTestsPassed = 1,
                    automatedTestsFailed = 0
                )
            ),
            sheet = makeGradingSheet(
                roundIndex = 0,
                dimensions = listOf(ScoringDimensionCatalog.CORRECTNESS to 1.0),
                dimensionNodeRefs = mapOf(ScoringDimensionCatalog.CORRECTNESS to nodeRef)
            )
        ).associateBy { it.groupId }

        assertEquals(true, updates.getValue("at-boundary").passedThreshold)
        assertTrue(updates.getValue("at-boundary").graderNotes.isEmpty())
        assertEquals(false, updates.getValue("below-boundary").passedThreshold)
        assertContains(
            updates.getValue("below-boundary").graderNotes.single(),
            "at least 60.0/100.0"
        )
    }

    @Test
    fun `failed deliverable notes identify only dimensions below threshold`() = runTest {
        val nodeRef = "dst:section:mixed"
        val update = deliverableUpdatesViaEngine(
            groupResults = mapOf(
                "mixed" to GroupRoundResult(
                    groupId = "mixed",
                    roundIndex = 0,
                    dimensionScores = mapOf(
                        ScoringDimensionCatalog.CORRECTNESS to 50.0,
                        ScoringDimensionCatalog.ROBUSTNESS to 69.0
                    ),
                    failureModesTriggered = emptyList(),
                    automatedTestsPassed = 1,
                    automatedTestsFailed = 0
                )
            ),
            sheet = makeGradingSheet(
                roundIndex = 0,
                dimensions = listOf(
                    ScoringDimensionCatalog.CORRECTNESS to 0.5,
                    ScoringDimensionCatalog.ROBUSTNESS to 0.5
                ),
                dimensionNodeRefs = mapOf(
                    ScoringDimensionCatalog.CORRECTNESS to nodeRef,
                    ScoringDimensionCatalog.ROBUSTNESS to nodeRef
                )
            )
        ).single()

        assertEquals(false, update.passedThreshold)
        assertEquals(1, update.graderNotes.size)
        assertContains(update.graderNotes.single(), "Correctness")
        assertFalse(update.graderNotes.single().contains("Robustness"))
        assertFalse(update.graderNotes.single().contains("Synthesised from round scoring"))
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
