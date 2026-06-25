package com.achulkov.evokammer.competition.dispatch

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression coverage for the in-memory dispatch trust scorer.
 */
class DispatchTrustScoringTest {

    @Test
    fun `trust score increases and decreases with feedback`() {
        val scorer = DispatchTrustScoring()

        assertEquals(0.5, scorer.scoreOf("group-0"))

        scorer.applyFeedback("group-0", accepted = false)
        assertEquals(0.42, scorer.scoreOf("group-0"))

        scorer.applyFeedback("group-0", accepted = true, magnitude = 2.0)
        assertEquals(0.52, scorer.scoreOf("group-0"))
    }
}
