package com.achulkov.evomolior.competition.grader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CanonSnapshotContentBudgetTest {
    @Test
    fun `one substantial document receives the budget left by short documents`() {
        val limits = CanonSnapshotExporter.allocateContentLimits(
            lengths = listOf(39_670, 400, 900, 250),
            floor = 600,
            budget = 24_000
        )

        assertEquals(listOf(400, 900, 250), listOf(limits[1], limits[2], limits[3]), "short documents stay whole")
        assertEquals(24_000 - 400 - 900 - 250, limits[0])
        assertEquals(24_000, limits.sum())
    }

    @Test
    fun `budget is shared fairly and never exceeds document lengths`() {
        val limits = CanonSnapshotExporter.allocateContentLimits(
            lengths = listOf(10_000, 10_000, 3_000),
            floor = 600,
            budget = 12_000
        )

        assertEquals(3_000, limits[2])
        assertEquals(limits[0], limits[1])
        assertEquals(12_000, limits.sum())
        assertTrue(limits.zip(listOf(10_000, 10_000, 3_000)).all { (limit, length) -> limit <= length })
    }

    @Test
    fun `floors are kept when the budget is smaller than their sum and empty input is safe`() {
        assertEquals(
            listOf(600, 600, 600),
            CanonSnapshotExporter.allocateContentLimits(listOf(5_000, 5_000, 5_000), floor = 600, budget = 1_000)
        )
        assertEquals(emptyList(), CanonSnapshotExporter.allocateContentLimits(emptyList(), floor = 600, budget = 24_000))
        assertEquals(
            listOf(1_000, 2_000),
            CanonSnapshotExporter.allocateContentLimits(listOf(1_000, 2_000), floor = 600, budget = 24_000)
        )
    }
}
