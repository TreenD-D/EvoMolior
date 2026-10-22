package com.achulkov.evomolior.runtime

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolRepairAuthorityPolicyTest {

    @Test
    fun `fresh authoring remains eligible`() {
        assertFalse(
            shouldSkipGeneratedToolRepair(
                priorRunAttempts = 0,
                visibleDiagnosticCount = 0,
                hasSealedApproach = false,
            )
        )
    }

    @Test
    fun `sealed repair remains eligible below the attempt bound`() {
        assertFalse(
            shouldSkipGeneratedToolRepair(
                priorRunAttempts = 1,
                visibleDiagnosticCount = 1,
                hasSealedApproach = true,
            )
        )
    }

    @Test
    fun `unsealed repair skips authoring instead of crashing the agent`() {
        assertTrue(
            shouldSkipGeneratedToolRepair(
                priorRunAttempts = 1,
                visibleDiagnosticCount = 0,
                hasSealedApproach = false,
            )
        )
        assertTrue(
            shouldSkipGeneratedToolRepair(
                priorRunAttempts = 1,
                visibleDiagnosticCount = 1,
                hasSealedApproach = false,
            )
        )
    }

    @Test
    fun `exhausted repair skips authoring even with stale context`() {
        assertTrue(
            shouldSkipGeneratedToolRepair(
                priorRunAttempts = 3,
                visibleDiagnosticCount = 1,
                hasSealedApproach = true,
            )
        )
    }
}
