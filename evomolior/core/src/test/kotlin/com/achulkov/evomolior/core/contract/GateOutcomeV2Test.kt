package com.achulkov.evomolior.core.contract

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals

class GateOutcomeV2Test {
    @Test
    fun `failed gate can preserve repairable salvage without authority`() {
        val outcome = GateOutcomeV2(
            gateId = "tool-schema",
            subjectRef = "tool-submission-1",
            authorityEffect = AuthorityEffect.NONE,
            epistemicState = EpistemicState.REPAIRABLE,
            retentionDisposition = RetentionDisposition.PRESERVE_ATOMIZED,
            reuseScope = ReuseScope.SUCCESSOR_ONLY,
            diagnostics = listOf(
                TypedDiagnostic(
                    code = "SCHEMA_INVALID",
                    publicMessage = "The tool submission did not satisfy its public schema.",
                    authorityHash = hash("diagnostic"),
                )
            ),
            salvageUnits = listOf(
                SalvageUnit(
                    unitId = "algorithm-idea",
                    kind = "ALGORITHM_IDEA",
                    contentHash = hash("idea"),
                    publicSummary = "Preserve the algorithm idea for a successor repair.",
                )
            ),
            recommendedNextAction = NextAction.SPAWN_SUCCESSOR,
        )

        assertEquals(AuthorityEffect.NONE, outcome.authorityEffect)
        assertEquals(1, outcome.salvageUnits.size)
    }

    @Test
    fun `receiver review requires validated material`() {
        assertFailsWith<IllegalArgumentException> {
            GateOutcomeV2(
                gateId = "unvalidated",
                subjectRef = "candidate-1",
                authorityEffect = AuthorityEffect.PROVISIONAL_ONLY,
                epistemicState = EpistemicState.PROVISIONAL,
                retentionDisposition = RetentionDisposition.PRESERVE_FULL,
                reuseScope = ReuseScope.RECEIVER_REVIEW,
            )
        }
    }

    private fun hash(value: String): String =
        com.achulkov.evomolior.core.deliverable.DeliverableHashing.hashText(value)
}
