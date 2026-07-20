package com.achulkov.evomolior.competition.scoring

import com.achulkov.evomolior.core.model.DimensionScore
import com.achulkov.evomolior.core.model.RoundScore
import com.achulkov.evomolior.core.model.ScoringDimensionCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ParetoRankingTest {
    @Test
    fun `keeps non-dominated tradeoffs on first front`() {
        val dimensions = listOf(
            ScoringDimensionCatalog.byId(ScoringDimensionCatalog.CORRECTNESS)!!,
            ScoringDimensionCatalog.byId(ScoringDimensionCatalog.COST_EFFICIENCY)!!
        )
        val ranked = ParetoRanking(dimensions).rank(
            listOf(
                score("accurate", 90.0, 40.0),
                score("cheap", 60.0, 95.0),
                score("dominated", 50.0, 30.0)
            )
        )

        assertEquals(listOf("cheap", "accurate"), ranked.fronts.first().groupIds)
        assertEquals(2, ranked.scores.first { it.groupId == "dominated" }.paretoFront)
        assertTrue("accurate" in ranked.scores.first { it.groupId == "dominated" }.dominatedByGroupIds)
        assertTrue("cheap" in ranked.scores.first { it.groupId == "dominated" }.dominatedByGroupIds)
    }

    @Test
    fun `treats security risk as lower is better`() {
        val dimensions = listOf(
            ScoringDimensionCatalog.byId(ScoringDimensionCatalog.CORRECTNESS)!!,
            ScoringDimensionCatalog.byId(ScoringDimensionCatalog.SECURITY_RISK)!!
        )
        val ranked = ParetoRanking(dimensions).rank(
            listOf(
                score("safe", correctness = 80.0, securityRisk = 10.0),
                score("risky", correctness = 80.0, securityRisk = 90.0)
            )
        )

        assertEquals(listOf("safe"), ranked.fronts.first().groupIds)
        assertEquals(listOf("safe"), ranked.scores.first { it.groupId == "risky" }.dominatedByGroupIds)
    }

    @Test
    fun `missing lower-is-better dimensions do not look safe after scorer aggregation`() {
        val scorer = Scorer(
            listOf(
                ScoringDimensionCatalog.byId(ScoringDimensionCatalog.CORRECTNESS)!!,
                ScoringDimensionCatalog.byId(ScoringDimensionCatalog.SECURITY_RISK)!!
            )
        )

        val score = scorer.compute(
            groupId = "unknown-risk",
            roundIndex = 0,
            submissions = listOf(DimensionScore(ScoringDimensionCatalog.CORRECTNESS, 80.0, "correct"))
        )

        assertEquals(
            100.0,
            score.dimensionScores.first { it.dimensionId == ScoringDimensionCatalog.SECURITY_RISK }.rawScore
        )
    }

    @Test
    fun `ranker treats sparse missing dimensions as worst case`() {
        val dimensions = listOf(
            ScoringDimensionCatalog.byId(ScoringDimensionCatalog.CORRECTNESS)!!,
            ScoringDimensionCatalog.byId(ScoringDimensionCatalog.SECURITY_RISK)!!
        )

        val ranked = ParetoRanking(dimensions).rank(
            listOf(
                RoundScore(
                    groupId = "complete",
                    roundIndex = 0,
                    weightedTotal = 80.0,
                    dimensionScores = listOf(
                        DimensionScore(ScoringDimensionCatalog.CORRECTNESS, 80.0, "correct"),
                        DimensionScore(ScoringDimensionCatalog.SECURITY_RISK, 10.0, "low risk")
                    )
                ),
                RoundScore(
                    groupId = "sparse",
                    roundIndex = 0,
                    weightedTotal = 80.0,
                    dimensionScores = listOf(
                        DimensionScore(ScoringDimensionCatalog.CORRECTNESS, 80.0, "correct")
                    )
                )
            )
        )

        assertEquals(listOf("complete"), ranked.scores.first { it.groupId == "sparse" }.dominatedByGroupIds)
    }

    private fun score(
        groupId: String,
        correctness: Double,
        costEfficiency: Double = 0.0,
        securityRisk: Double = 0.0
    ): RoundScore = RoundScore(
        groupId = groupId,
        roundIndex = 0,
        weightedTotal = correctness + costEfficiency - securityRisk,
        dimensionScores = listOf(
            DimensionScore(ScoringDimensionCatalog.CORRECTNESS, correctness, "correctness"),
            DimensionScore(ScoringDimensionCatalog.COST_EFFICIENCY, costEfficiency, "cost"),
            DimensionScore(ScoringDimensionCatalog.SECURITY_RISK, securityRisk, "risk")
        )
    )
}
