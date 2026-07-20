package com.achulkov.evomolior.core

import com.achulkov.evomolior.core.model.GradingResult
import com.achulkov.evomolior.core.model.RoundScore
import com.achulkov.evomolior.core.model.ScoringDimension
import com.achulkov.evomolior.core.model.ScoringResolution
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScoringSerializationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `round score decodes legacy payload without Pareto metadata`() {
        val payload = """
            {
              "groupId": "group-0",
              "roundIndex": 0,
              "dimensionScores": [],
              "weightedTotal": 42.0,
              "rank": 1
            }
        """.trimIndent()

        val decoded = json.decodeFromString(RoundScore.serializer(), payload)

        assertEquals(0, decoded.paretoFront)
        assertEquals(emptyList(), decoded.dominatedByGroupIds)
    }

    @Test
    fun `grading result decodes legacy payload without Pareto fronts`() {
        val payload = """
            {
              "roundIndex": 0,
              "scores": [],
              "winnerId": "group-0"
            }
        """.trimIndent()

        val decoded = json.decodeFromString(GradingResult.serializer(), payload)

        assertEquals(emptyList(), decoded.paretoFronts)
        assertEquals(ScoringResolution.RESOLVED, decoded.resolution)
    }

    @Test
    fun `grading result serializes an unresolved all-zero outcome`() {
        val result = GradingResult(
            roundIndex = 0,
            scores = emptyList(),
            winnerId = null,
            resolution = ScoringResolution.ALL_ZERO_DEGENERACY
        )

        val decoded = json.decodeFromString(
            GradingResult.serializer(),
            json.encodeToString(GradingResult.serializer(), result)
        )

        assertEquals(null, decoded.winnerId)
        assertEquals(ScoringResolution.ALL_ZERO_DEGENERACY, decoded.resolution)
    }

    @Test
    fun `scoring dimension preserves lower-is-better direction`() {
        val encoded = json.encodeToString(
            ScoringDimension.serializer(),
            ScoringDimension(
                id = "security_risk",
                name = "Security Risk",
                description = "Lower is better",
                weight = 1.0,
                higherIsBetter = false
            )
        )

        val decoded = json.decodeFromString(ScoringDimension.serializer(), encoded)

        assertFalse(decoded.higherIsBetter)
    }

    @Test
    fun `scoring dimension decodes legacy payload as higher-is-better`() {
        val payload = """
            {
              "id": "correctness",
              "name": "Correctness",
              "description": "Correctness",
              "weight": 1.0
            }
        """.trimIndent()

        val decoded = json.decodeFromString(ScoringDimension.serializer(), payload)

        assertTrue(decoded.higherIsBetter)
    }
}
