package com.achulkov.evomolior.core.config

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RunObjectiveHeuristicsTest {

    @Test
    fun `requestsBrowserDeliverable detects publication-ready final artifacts`() {
        assertTrue(
            RunObjectiveHeuristics.requestsBrowserDeliverable(
                "The final artifacts must be publication-ready strategy and submission-brief documents."
            )
        )
    }

    @Test
    fun `requestsBrowserDeliverable detects submission and publication ready benchmark briefs`() {
        assertTrue(
            RunObjectiveHeuristics.requestsBrowserDeliverable(
                "Produce a Qwen Cloud Track 3 submission brief and publication-ready benchmark plan."
            )
        )
    }

    @Test
    fun `requestsBrowserDeliverable detects operator-facing final reports`() {
        assertTrue(
            RunObjectiveHeuristics.requestsBrowserDeliverable(
                "Produce a final operator-facing report with ranked prediction-market verticals, hypotheses, rubric, rulebook, and risks."
            )
        )
    }

    @Test
    fun `requestsBrowserDeliverable remains false for analysis-only objectives`() {
        assertFalse(
            RunObjectiveHeuristics.requestsBrowserDeliverable(
                "Compare the candidate model assignments and report risks to the operator."
            )
        )
    }

    @Test
    fun `requestsBrowserDeliverable avoids broad deliverable and benchmark plan false positives`() {
        assertFalse(
            RunObjectiveHeuristics.requestsBrowserDeliverable(
                "Compare internal deliverable risk and produce a benchmark plan for the next sprint."
            )
        )
    }

    @Test
    fun `current market objective requires external evidence`() {
        assertTrue(
            RunObjectiveHeuristics.requiresExternalEvidence(
                "Build a current market intelligence map with competitor pricing and public sources."
            )
        )
    }

    @Test
    fun `offline synthesis objective does not require external evidence`() {
        assertFalse(
            RunObjectiveHeuristics.requiresExternalEvidence(
                "Summarize the supplied experiment corpus into a concise decision memo."
            )
        )
    }
}
