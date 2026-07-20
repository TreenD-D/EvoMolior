package com.achulkov.evomolior.competition.grader

import com.achulkov.evomolior.core.model.DimensionScore
import com.achulkov.evomolior.core.model.ScoringDimension
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Permanently replays the historical zero/nonzero/zero score-event shape.
 */
class HistoricalRawScoreReplayTest {
    private val dimensions = listOf(
        ScoringDimension("accuracy", "Accuracy", "Accuracy", 1.0, maxScore = 10.0),
        ScoringDimension("evidence", "Evidence", "Evidence", 1.0, maxScore = 10.0)
    )

    @Test
    fun `historical partial zero bundle fails closed instead of zero filling`() {
        val fixture = checkNotNull(
            javaClass.getResourceAsStream("/scoring/s3-zero-nonzero-zero-score-events.json")
        ).bufferedReader().use { reader ->
            Json.decodeFromString<RawScoreReplayFixture>(reader.readText())
        }
        val manager = GraderGroupManager(dimensions)
        fixture.events.forEach { event ->
            manager.submitScores(
                graderGroupId = event.graderGroupId,
                targetGroupId = event.targetGroupId,
                roundIndex = fixture.roundIndex,
                scores = event.scores.map { (dimensionId, rawScore) ->
                    DimensionScore(
                        dimensionId = dimensionId,
                        rawScore = rawScore,
                        justification = "Historical raw score replay for ${event.graderGroupId}"
                    )
                }
            )
        }

        assertTrue(fixture.events.first().scores.values.all { it == 0.0 })
        assertTrue(fixture.events[1].scores.values.any { it > 0.0 })
        assertTrue(fixture.events.last().scores.values.all { it == 0.0 })
        assertFalse(manager.hasCompleteRoundScores(listOf("group-0"), fixture.roundIndex))
        assertFailsWith<IllegalStateException> {
            manager.computeRoundResult(listOf("group-0"), fixture.roundIndex)
        }
    }
}

@Serializable
private data class RawScoreReplayFixture(
    val runId: String,
    val roundIndex: Int,
    val events: List<RawScoreReplayEvent>
)

@Serializable
private data class RawScoreReplayEvent(
    val graderGroupId: String,
    val targetGroupId: String,
    val scores: Map<String, Double>
)
