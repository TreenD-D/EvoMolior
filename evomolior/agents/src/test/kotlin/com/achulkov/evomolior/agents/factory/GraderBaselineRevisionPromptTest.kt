package com.achulkov.evomolior.agents.factory

import com.achulkov.evomolior.core.contract.GraderAutomatedTestDraft
import com.achulkov.evomolior.core.contract.GraderFailureModeDraft
import com.achulkov.evomolior.core.contract.GraderMethodologyWireDraft
import com.achulkov.evomolior.core.contract.GraderRubricDraft
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Criteria agents revise the previous round's accepted criteria instead of re-authoring them.
 */
class GraderBaselineRevisionPromptTest {
    private val dimensions = "  • [accuracy] Accuracy (weight=1.00): Evidence-backed accuracy."

    @Test
    fun `without a baseline criteria agents author per-dimension criteria`() {
        val prompt = RolePromptBuilder.buildGraderCriteria(
            graderRole = "GraderResearcher",
            runObjective = "Evaluate a geo-data source landscape.",
            dimensionDescriptions = dimensions,
            roundIndex = 0
        )

        assertContains(prompt, "Prefer one substantial criteria document per dimension.")
        assertFalse(prompt.contains("ACCEPTED CRITERIA BASELINE"))
    }

    @Test
    fun `with a baseline agents stage one consolidated revision starting from it`() {
        val prompt = RolePromptBuilder.buildGraderCriteria(
            graderRole = "GraderResearcher",
            runObjective = "Evaluate a geo-data source landscape.",
            dimensionDescriptions = dimensions,
            roundIndex = 2,
            baselineCriteria = "BASELINE_RUBRIC_MARKER: penalise discovery-only URLs."
        )

        assertContains(prompt, "=== ACCEPTED CRITERIA BASELINE FROM THE PREVIOUS ROUND (start here) ===")
        assertContains(prompt, "BASELINE_RUBRIC_MARKER")
        assertContains(prompt, "CRITERIA REVISION — generation 3")
        assertContains(prompt, "[KEPT], [CHANGED], or [NEW]")
        assertFalse(prompt.contains("Prefer one substantial criteria document per dimension."))
    }

    @Test
    fun `builder with a compiled frontier republishes an improved revision first`() {
        val prompt = RolePromptBuilder.buildGraderCriteria(
            graderRole = "GraderBuilder",
            runObjective = "Evaluate a geo-data source landscape.",
            dimensionDescriptions = dimensions,
            roundIndex = 1,
            graderAtlasContext = """{"rows":[]}""",
            baselineCriteria = "Typed methodology bound to this round's handle matrix: {}"
        )

        assertContains(prompt, "improved revision of the baseline typed methodology")
        assertFalse(prompt.contains("with a valid, objective-specific typed sheet"))
    }

    @Test
    fun `judge criteria keep authored test results when the digest is long`() {
        val digest = "CRITERIA_HEAD " + "rubric detail ".repeat(1_500)
        val results = RolePromptBuilder.JUDGE_AUTOMATED_TEST_RESULTS_HEADER +
            "\nTarget: ROUND_CANON_SNAPSHOT\n- test-access-date: FAILED (mandatory)"
        val bounded = RolePromptBuilder.boundedJudgeCriteria("$digest\n\n$results")

        assertTrue(bounded.length <= RolePromptBuilder.ROUND_JUDGE_CRITERIA_MAX_CHARS)
        assertContains(bounded, "CRITERIA_HEAD")
        assertContains(bounded, "...[criteria truncated]")
        assertContains(bounded, "test-access-date: FAILED (mandatory)")
        assertEquals("short criteria", RolePromptBuilder.boundedJudgeCriteria("short criteria"))
    }

    @Test
    fun `structured author improves the baseline on a first attempt and repairs on validation errors`() {
        val baseline = GraderMethodologyWireDraft(
            rubricDimensions = listOf(GraderRubricDraft("accuracy", "BASELINE_GUIDANCE_MARKER")),
            automatedTests = listOf(
                GraderAutomatedTestDraft(
                    testId = "test-1",
                    description = "Requires an access date.",
                    expectedOutputPattern = "accessed 20[0-9]{2}"
                )
            ),
            failureModes = listOf(GraderFailureModeDraft("unsupported", "Unsupported product claim."))
        )

        val first = structuredMethodologyUserMessage(
            validationError = null,
            priorDraft = null,
            baselineDraft = baseline,
            bindingGuide = { error("binding guide is only needed for repairs") }
        )
        assertContains(first, "Start from this accepted previous-round methodology")
        assertContains(first, "BASELINE_GUIDANCE_MARKER")
        assertContains(first, "Return the complete improved JSON value")

        val repair = structuredMethodologyUserMessage(
            validationError = "requirement-1 uses an unknown handle",
            priorDraft = baseline,
            baselineDraft = baseline,
            bindingGuide = { "- graderNodeHandle=g1" }
        )
        assertContains(repair, "The prior attempt failed validation")
        assertContains(repair, "Repair this prior handle-bound draft")
        assertFalse(repair.contains("Start from this accepted previous-round methodology"))
        assertTrue(repair.contains("- graderNodeHandle=g1"))
    }
}
