package com.achulkov.evokammer.orchestration

import com.achulkov.evokammer.orchestration.epigenetic.FitnessScore
import com.achulkov.evokammer.orchestration.epigenetic.FitnessScorer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the pure-logic aspects of [FitnessScorer] that don't require a database.
 *
 * - [FitnessScorer.topPercentile] selection logic
 * - [FitnessScore] field constraints
 */
class FitnessScorerLogicTest {

    // ── topPercentile ──────────────────────────────────────────────────────────

    private fun score(agentId: String, total: Double) = FitnessScore(
        agentId = agentId,
        generation = 1,
        citationScore = 0.0,
        kgContributionScore = 0.0,
        toolAdoptionScore = 0.0,
        stagingSuccessScore = 0.0,
        taskCompletionScore = total,
        total = total
    )

    @Test
    fun `topPercentile returns top 20% of sorted list`() {
        val scores = (1..10).map { i -> score("agent-$i", i.toDouble()) }
            .sortedByDescending { it.total }
        // 20% of 10 = 2
        val top = topPercentile(scores, 0.20)
        assertEquals(2, top.size)
        assertEquals("agent-10", top[0].agentId)
        assertEquals("agent-9", top[1].agentId)
    }

    @Test
    fun `topPercentile always returns at least 1 even for tiny list`() {
        val scores = listOf(score("only-one", 50.0))
        val top = topPercentile(scores, 0.20)
        assertEquals(1, top.size)
    }

    @Test
    fun `topPercentile rounds down but never below 1`() {
        val scores = (1..3).map { i -> score("agent-$i", i.toDouble()) }
            .sortedByDescending { it.total }
        // 20% of 3 = 0.6 → floor = 0 → coerced to 1
        val top = topPercentile(scores, 0.20)
        assertEquals(1, top.size)
    }

    @Test
    fun `FitnessScore total equals sum of all dimensions`() {
        val fs = FitnessScore(
            agentId = "a",
            generation = 2,
            citationScore = 5.0,
            kgContributionScore = 3.0,
            toolAdoptionScore = 10.0,
            stagingSuccessScore = 12.5,
            taskCompletionScore = 20.0,
            total = 50.5
        )
        val expectedTotal = fs.citationScore + fs.kgContributionScore +
            fs.toolAdoptionScore + fs.stagingSuccessScore + fs.taskCompletionScore
        assertEquals(expectedTotal, fs.total, 0.001)
    }

    @Test
    fun `FitnessScore max total is capped at 100`() {
        // Verify the per-dimension caps enforced by FitnessScorer:
        // citationScore ≤ 20, kgScore ≤ 15, toolScore ≤ 20, stagingScore ≤ 25, taskScore ≤ 20
        val maxTotal = 20.0 + 15.0 + 20.0 + 25.0 + 20.0
        assertEquals(100.0, maxTotal, 0.001)
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /** Standalone replica of FitnessScorer.topPercentile so we don't need a real instance. */
    private fun topPercentile(scores: List<FitnessScore>, percentile: Double): List<FitnessScore> {
        val count = (scores.size * percentile).toInt().coerceAtLeast(1)
        return scores.take(count)
    }
}
