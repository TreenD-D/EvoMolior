package com.achulkov.evomolior.runtime

import com.achulkov.evomolior.core.model.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProductionGraderModelRoutingTest {

    @Test
    fun `fresh normal run retains configured grader model without sealed authority`() {
        val routing = resolveProductionGraderModelRouting(
            sealedAssignments = null,
            legacyGraderModelOverride = "runtime-grader"
        )

        assertNull(routing.sealedAssignments)
        assertEquals("runtime-grader", routing.legacyGraderModelOverride)
    }

    @Test
    fun `resumed uncompiled run retains configured grader model without sealed authority`() {
        val routing = resolveProductionGraderModelRouting(
            sealedAssignments = null,
            legacyGraderModelOverride = "resumed-runtime-grader"
        )

        assertNull(routing.sealedAssignments)
        assertEquals("resumed-runtime-grader", routing.legacyGraderModelOverride)
    }

    @Test
    fun `compiled run routes only copied sealed authority despite conflicting runtime override`() {
        val assignments = mutableMapOf(
            Role.GraderResearcher.name to "sealed-researcher",
            Role.GraderBuilder.name to "sealed-builder",
            Role.GraderCritic.name to "sealed-critic"
        )

        val routing = resolveProductionGraderModelRouting(
            sealedAssignments = assignments,
            legacyGraderModelOverride = "conflicting-runtime-grader"
        )
        assignments[Role.GraderCritic.name] = "mutated-after-resolution"

        assertEquals("sealed-critic", routing.sealedAssignments?.get(Role.GraderCritic.name))
        assertNull(routing.legacyGraderModelOverride)
    }
}
