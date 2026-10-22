package com.achulkov.evomolior.api.grader

import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.contract.GraderAutomatedTestDraft
import com.achulkov.evomolior.core.contract.GraderMethodologyDraft
import com.achulkov.evomolior.core.model.DimensionScore
import com.achulkov.evomolior.core.model.ScoringDimension
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthoredGradingDiagnosticTest {
    @Test
    fun `v7 mandatory final review predicates fail on intermediate snapshot without discarding raw scores`() {
        val runner = runner()
        // Exact mandatory predicates recovered from v7 grader methodology storage.
        val patterns = listOf(
            "(?s)## Summary.*## Evidence.*## Proposed change.*## Validation plan.*## Risks",
            "(^|\\n)## Summary\\n[\\s\\S]*\\n## Evidence\\n[\\s\\S]*\\n## Proposed change\\n[\\s\\S]*\\n## Validation plan\\n[\\s\\S]*\\n## Risks\\n"
        )
        val intermediate = "Source tracing and remediation evidence...[truncated]"
        val finalCandidate = "## Summary\ns\n## Evidence\ne\n## Proposed change\np\n## Validation plan\nv\n## Risks\nr\n"
        val scores = (1..9).map { DimensionScore("dimension-$it", 92.0, "Raw evidence judgment") }
        patterns.forEach { pattern ->
            val draft = draft(pattern)
            val assessment = with(runner) { draft.assess(intermediate) }
            assertEquals(AtlasHashing.hashText(intermediate), assessment.targetSnapshotHash)
            assertFalse(assessment.outcomes.single().passed)
            assertTrue(assessment.applyTo(scores).all { it.rawScore == 0.0 })
            assertTrue(scores.all { it.rawScore == 92.0 })
            assertTrue(assessment.applyTo(scores).all { it.justification.contains("Raw evidence judgment") })
            assertTrue(with(runner) { draft.assess(finalCandidate) }.outcomes.single().passed)
            // Later candidate success does not change the earlier snapshot assessment.
            assertFalse(assessment.outcomes.single().passed)
        }
    }

    @Test
    fun `invalid mandatory regex is explicit execution error and remains fail closed`() {
        val assessment = with(runner()) { draft("(?=unsupported)").assess("evidence") }
        val outcome = assessment.outcomes.single()
        assertFalse(outcome.passed)
        assertTrue(outcome.executionError != null)
        assertEquals(AuthoredTestExecutionStatus.ERROR, AuthoredTestDiagnostic(outcome, null).status)
        assertEquals(0.0, assessment.applyTo(listOf(DimensionScore("accuracy", 90.0, "raw"))).single().rawScore)
        assertTrue(assessment.applyTo(emptyList()).isEmpty())
    }

    @Test
    fun `optional failures subtract penalty once and passing tests do not hide a raw zero`() {
        val raw = listOf(DimensionScore("accuracy", 90.0, "raw"), DimensionScore("safety", 0.0, "raw zero"))
        val assessment = with(runner()) { draft("absent", mandatory = false).assess("evidence") }
        assertEquals(listOf(89.0, 0.0), assessment.applyTo(raw).map { it.rawScore })
        assertEquals(listOf(90.0, 0.0), raw.map { it.rawScore })
        val passed = with(runner()) { draft("evidence").assess("evidence") }
        assertEquals(raw, passed.applyTo(raw))
    }

    @Test
    fun `complete round judge scores do not announce or invoke structured repair`() = runTest {
        var authorCalls = 0
        val progress = mutableListOf<String>()
        val runner = runner { authorCalls++ }
        val scores = mutableMapOf("target" to mutableListOf(DimensionScore("accuracy", 90.0, "raw")))
        runner.repairMissingDimensionScores(
            runId = "run",
            graderGroupId = "grader",
            targetGroupId = "target",
            runObjective = "review",
            criteriaFreezeHash = "sealed-freeze",
            targetSnapshotHash = AtlasHashing.hashText("snapshot"),
            criteriaContext = "criteria",
            targetSnapshot = "snapshot",
            scoringDimensions = listOf(ScoringDimension("accuracy", "Accuracy", "Accuracy", 1.0)),
            collectedScores = scores,
            onProgress = progress::add
        )
        assertEquals(0, authorCalls)
        assertTrue(progress.isEmpty())
        assertEquals(90.0, scores.getValue("target").single().rawScore)
    }

    private fun draft(pattern: String, mandatory: Boolean = true) = GraderMethodologyDraft(
        rubricDimensions = emptyList(),
        automatedTests = listOf(
            GraderAutomatedTestDraft(
                testId = "review-structure-and-evidence-check",
                description = "Required review structure",
                expectedOutputPattern = pattern,
                isMandatory = mandatory
            )
        ),
        failureModes = emptyList()
    )

    private fun runner(onAuthor: () -> Unit = {}) = GraderRunner(
        openRouterApiKey = "test",
        substrateFactory = SubstrateDatabaseFactory(createTempDirectory("grading-diagnostic-").toFile()),
        localAgentRunner = { error("No provider calls allowed") },
        structuredDimensionScoreAuthor = {
            onAuthor()
            error("Complete scores must not invoke repair")
        }
    )
}
