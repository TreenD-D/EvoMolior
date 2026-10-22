package com.achulkov.evomolior.core.deliverable

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecoverySourceSetTest {
    @Test
    fun `source set includes every eligible group with neutral deterministic labels`() {
        val sources = listOf(
            RecoverySourceCandidate("group-z", "b".repeat(64), setOf("evidence-z")),
            RecoverySourceCandidate("group-a", "a".repeat(64), setOf("evidence-a")),
        )

        val first = RecoverySourceSet.seal("run-1", sources)
        val second = RecoverySourceSet.seal("run-1", sources.reversed())

        assertEquals(listOf("group-a", "group-z"), first.sourceGroupIds)
        assertEquals(
            mapOf(
                "group-a" to "NON_ACCEPTED_SOURCE_A",
                "group-z" to "NON_ACCEPTED_SOURCE_B",
            ),
            first.sourceLabels,
        )
        assertEquals(first, second)
        assertTrue(first.hasValidHash())
        assertFalse(
            first.copy(
                sourceSnapshotHashes = listOf("c".repeat(64), "b".repeat(64))
            ).hasValidHash()
        )
    }
}
