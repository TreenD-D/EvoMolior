package com.achulkov.evomolior.api.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EvoMoliorRunServiceRecoverySourceSelectionTest {
    @Test
    fun `source-only recovery prefers valid candidate without asserting winner authority`() {
        assertEquals(
            "group-b",
            deterministicRecoverySourceGroupId(
                winnerId = "group-b",
                groupIds = listOf("group-a", "group-b"),
            ),
        )
    }

    @Test
    fun `source-only recovery falls back deterministically when candidate is unresolved`() {
        assertEquals(
            "group-a",
            deterministicRecoverySourceGroupId(
                winnerId = null,
                groupIds = listOf("group-c", "group-a", "group-b"),
            ),
        )
        assertEquals(
            "group-a",
            deterministicRecoverySourceGroupId(
                winnerId = "foreign-group",
                groupIds = listOf("group-c", "group-a", "group-b"),
            ),
        )
        assertNull(deterministicRecoverySourceGroupId(null, emptyList()))
    }

    @Test
    fun `without a winner the group with the most substantial canon document is the source`() {
        // October 2026 synthesis: an all-equal tie routed the writer to the alphabetically first
        // group (26,753-char draft) instead of the group holding a 39,670-char report.
        val groups = listOf("worker-group_a934ef", "worker-group_49b87b")
        val canonChars = mapOf("worker-group_a934ef" to 39_670, "worker-group_49b87b" to 26_753)

        assertEquals(
            "worker-group_a934ef",
            deterministicRecoverySourceGroupId(winnerId = null, groupIds = groups, canonicalDocumentChars = canonChars)
        )
        // A resolved winner still takes precedence; evidence size never creates winner authority.
        assertEquals(
            "worker-group_49b87b",
            deterministicRecoverySourceGroupId("worker-group_49b87b", groups, canonChars)
        )
        // Equal or unknown evidence keeps the previous deterministic alphabetical order.
        assertEquals(
            "worker-group_49b87b",
            deterministicRecoverySourceGroupId(null, groups, groups.associateWith { 500 })
        )
    }
}
