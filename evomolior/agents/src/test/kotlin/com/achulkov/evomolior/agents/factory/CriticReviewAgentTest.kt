package com.achulkov.evomolior.agents.factory

import com.achulkov.evomolior.core.contract.CriticOutcome
import com.achulkov.evomolior.core.contract.CriticReview
import com.achulkov.evomolior.core.contract.GRADER_METHODOLOGY_DRAFT_SIGNATURE_PREFIX
import com.achulkov.evomolior.core.repair.ToolRepairCode
import com.achulkov.evomolior.core.repair.ToolSemanticVerdict
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies the Critic review wrapper builds prompts and parses model responses deterministically.
 */
class CriticReviewAgentTest {

    @Test
    fun `buildPrompt preserves the critic policy text and arbitration suffix`() {
        val agent = CriticReviewAgent(
            openRouterApiKey = "sk-test",
            criticModelId = "anthropic/claude-haiku-4.5"
        )

        val prompt = agent.buildPrompt(
            submission = sampleSubmission(),
            disputeContext = "borderline quality"
        )

        val normalised = prompt.replace("\r\n", "\n")
        assertTrue(normalised.contains("Review the following DOC submission from a Researcher agent."))
        assertTrue(normalised.contains("Respond with a JSON object containing:"))
        assertTrue(normalised.contains("- PROMOTE if the submission is accurate, useful, well-structured, and adds value to the KB"))
        assertTrue(normalised.contains("- REJECT if it's incorrect, duplicate, low quality, incoherent, or violates norms"))
        assertTrue(normalised.contains("JSON response only:"))
        assertTrue(
            normalised.contains(
                "NOTE — This is a SECOND REVIEW (arbitration). The first Critic disputed this submission with reason: \"borderline quality\". Your verdict is final."
            )
        )
    }

    @Test
    fun `reviewSubmission forwards prompt and dispute context to the injected runner`() = runTest {
        val prompts = mutableListOf<String>()
        val agent = CriticReviewAgent(
            openRouterApiKey = "sk-test",
            criticModelId = "anthropic/claude-haiku-4.5",
            deterministicReviewRunner = { submission, prompt, disputeContext, criticId ->
                prompts += prompt
                assertEquals("submission-1", submission.id)
                assertEquals("borderline quality", disputeContext)
                assertTrue(criticId.startsWith("critic-"))
                CriticReview(
                    submissionId = submission.id,
                    outcome = CriticOutcome.PROMOTE,
                    criticAgentId = criticId,
                    initialTrustScore = 0.88
                )
            }
        )

        val review = agent.reviewSubmission(
            submission = sampleSubmission(),
            disputeContext = "borderline quality",
            criticId = "critic-1234"
        )

        assertEquals(1, prompts.size)
        assertTrue(prompts.single().contains("SECOND REVIEW"))
        assertTrue(prompts.single().contains("borderline quality"))
        assertEquals(CriticOutcome.PROMOTE, review.outcome)
        assertEquals(0.88, review.initialTrustScore)
    }

    @Test
    fun `parseResponse extracts review fields and defaults invalid responses to dispute`() {
        val agent = CriticReviewAgent(
            openRouterApiKey = "sk-test",
            criticModelId = "anthropic/claude-haiku-4.5"
        )

        val parsed = agent.parseResponse(
            responseText = """{"outcome":"REJECT","reason":"duplicate"}""",
            criticId = "critic-1",
            submissionId = "submission-2"
        )
        assertEquals(CriticOutcome.REJECT, parsed.outcome)
        assertEquals("duplicate", parsed.rejectionReason)

        val blank = agent.parseResponse(
            responseText = "{}",
            criticId = "critic-3",
            submissionId = "submission-4"
        )
        assertEquals(CriticOutcome.REJECT, blank.outcome)

        val fallback = agent.parseResponse(
            responseText = """{"outcome":"INVALID"}""",
            criticId = "critic-2",
            submissionId = "submission-3"
        )
        assertEquals(CriticOutcome.DISPUTE, fallback.outcome)
        assertTrue(fallback.disputeReason?.contains("parse error") == true)
    }

    @Test
    fun `typed grader methodology receives a complete bounded critic view`() {
        val agent = CriticReviewAgent(
            openRouterApiKey = "sk-test",
            criticModelId = "anthropic/claude-haiku-4.5"
        )
        val tailMarker = "COMPLETE_TYPED_METHODOLOGY_TAIL"
        val prefix = "{\"rubricDimensions\":[\""
        val suffix = "\"]}"
        val content = buildString {
            append(prefix)
            append("x".repeat(21_276 - prefix.length - tailMarker.length - suffix.length))
            append(tailMarker)
            append(suffix)
        }

        assertEquals(21_276, content.length)
        val prompt = agent.buildPrompt(
            sampleSubmission().copy(
                type = "PLAN_UPDATE",
                content = content,
                budgetSignature =
                    "${GRADER_METHODOLOGY_DRAFT_SIGNATURE_PREFIX}grader-builder:1"
            )
        )

        assertTrue(prompt.contains(tailMarker))
        assertTrue(prompt.contains("complete persisted payload"))
        assertTrue(!prompt.contains("bounded critic view:"))
    }

    @Test
    fun `ordinary critic payload remains bounded`() {
        val agent = CriticReviewAgent(
            openRouterApiKey = "sk-test",
            criticModelId = "anthropic/claude-haiku-4.5"
        )
        val tailMarker = "ORDINARY_PAYLOAD_TAIL"
        val prompt = agent.buildPrompt(
            sampleSubmission().copy(
                content = "x".repeat(12_000) + tailMarker
            )
        )

        assertTrue(!prompt.contains(tailMarker))
        assertTrue(prompt.contains("bounded critic view: 8000"))
    }

    @Test
    fun `tool review prompt preserves deterministic promotion authority`() {
        val agent = CriticReviewAgent(
            openRouterApiKey = "sk-test",
            criticModelId = "anthropic/claude-haiku-4.5"
        )

        val prompt = agent.buildPrompt(
            sampleSubmission().copy(
                type = "TOOL",
                content = """{"opportunityId":"calculator","language":"dsl"}"""
            )
        )

        assertTrue(prompt.contains("deterministic validation proved its exact sealed"))
        assertTrue(prompt.contains("promotion boundary independently reruns"))
        assertTrue(prompt.contains("private sealed"))
        assertTrue(prompt.contains("Do not reject merely because private fixtures are unavailable"))
        assertTrue(prompt.contains("unless you can name a concrete"))
    }

    @Test
    fun `typed tool semantic prompt and parser expose only closed repair authority`() {
        val agent = CriticReviewAgent(
            openRouterApiKey = "sk-test",
            criticModelId = "anthropic/claude-haiku-4.5"
        )
        val submission = sampleSubmission().copy(
            type = "TOOL",
            content = """{"code":"power_watts * duration_hours"}"""
        )

        val prompt = agent.buildToolSemanticPrompt(
            submission,
            "energy_kwh is public unit kWh; power_watts is public unit W."
        )
        val review = agent.parseToolSemanticResponse(
            """
            {
              "verdict":"REJECT",
              "publicFindings":[{
                "code":"POWER_TO_ENERGY_UNIT_CONVERSION",
                "affectedPublicField":"energy_kwh",
                "remediationKey":"tool_repair.power_to_energy_unit_conversion"
              }],
              "safeRepairCodes":["POWER_TO_ENERGY_UNIT_CONVERSION"],
              "privateRationaleCommitment":"${"a".repeat(64)}"
            }
            """.trimIndent()
        )

        assertTrue(prompt.contains("Private fixtures and exact private outputs are never disclosed"))
        assertTrue(prompt.contains("Codes are hypotheses"))
        assertEquals(ToolSemanticVerdict.REJECT, review.verdict)
        assertEquals(
            setOf(ToolRepairCode.POWER_TO_ENERGY_UNIT_CONVERSION),
            review.safeRepairCodes
        )
        assertEquals(
            "tool_repair.power_to_energy_unit_conversion",
            review.publicFindings.single().remediationKey
        )
    }

    @Test
    fun `malformed semantic response fails closed without provider prose`() {
        val agent = CriticReviewAgent(
            openRouterApiKey = "sk-test",
            criticModelId = "anthropic/claude-haiku-4.5"
        )

        val review = agent.parseToolSemanticResponse(
            "private fixture /Users/alice expects 42.0"
        )

        assertEquals(ToolSemanticVerdict.DISPUTE, review.verdict)
        assertTrue(review.publicFindings.isEmpty())
        assertTrue(review.safeRepairCodes.isEmpty())
    }

    private fun sampleSubmission(): StagingSubmissionEntity = StagingSubmissionEntity(
        id = "submission-1",
        agentId = "agent-1",
        role = "Researcher",
        type = "DOC",
        content = "A short document for critic review.",
        budgetSignature = "sig",
        wipTaskRef = "node-1",
        speculative = false,
        outlier = false,
        generation = 1,
        groupId = "group-0",
        runId = "run-0",
        submittedAtMs = 1_000L
    )
}
