package com.achulkov.evokammer.agents.factory

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.singleRunStrategy
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.features.eventHandler.feature.EventHandler
import ai.koog.prompt.llm.LLModel
import com.achulkov.evokammer.core.config.ModelSelectionConfig
import com.achulkov.evokammer.core.contract.CriticOutcome
import com.achulkov.evokammer.core.contract.CriticReview
import com.achulkov.evokammer.substrate.db.entity.StagingSubmissionEntity
import io.github.aakira.napier.Napier
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/**
 * Runs the Critic LLM as a dedicated wrapper around the Koog agent runtime.
 *
 * The wrapper owns the prompt construction and response parsing so orchestration code only
 * needs to handle pre-review guards and review application. Tests can inject a deterministic
 * review runner to avoid live OpenRouter calls.
 *
 * @param openRouterApiKey OpenRouter API key used for the default LLM-backed review path.
 * @param criticModelId OpenRouter model identifier used when no deterministic runner is supplied.
 * @param deterministicReviewRunner Optional test/runtime override that returns a final
 *   [CriticReview] directly instead of invoking the LLM.
 */
class CriticReviewAgent(
    openRouterApiKey: String,
    private val criticModelId: String = ModelSelectionConfig.DEFAULT_CRITIC_PIPELINE_MODEL,
    private val systemPrompt: String = CRITIC_SYSTEM_PROMPT,
    private val deterministicReviewRunner: (suspend (
        submission: StagingSubmissionEntity,
        prompt: String,
        disputeContext: String?,
        criticId: String
    ) -> CriticReview)? = null
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val executor by lazy { createOpenRouterPromptExecutor(openRouterApiKey) }

    /**
     * Reviews a staged submission using either the injected deterministic runner or the LLM.
     *
     * @param submission The staged submission to review.
     * @param disputeContext Optional second-pass context from a prior DISPUTE outcome.
     * @param criticId Stable critic identity for this review round.
     * @return The parsed or injected [CriticReview].
     */
    suspend fun reviewSubmission(
        submission: StagingSubmissionEntity,
        disputeContext: String? = null,
        criticId: String = "critic-${UUID.randomUUID().toString().take(8)}"
    ): CriticReview {
        return reviewPrompt(
            submission = submission,
            prompt = buildPrompt(submission, disputeContext),
            disputeContext = disputeContext,
            criticId = criticId
        )
    }

    /**
     * Reviews a staged submission using a pre-built prompt.
     *
     * This is used by specialised critic variants, such as KG arbitration, so they can supply
     * a narrower policy prompt without duplicating the LLM wiring and parsing logic.
     *
     * @param submission The staged submission to review.
     * @param prompt The exact prompt to send to the critic model.
     * @param criticId Stable critic identity for this review round.
     * @return The parsed or injected [CriticReview].
     */
    suspend fun reviewPrompt(
        submission: StagingSubmissionEntity,
        prompt: String,
        disputeContext: String? = null,
        criticId: String = "critic-${UUID.randomUUID().toString().take(8)}"
    ): CriticReview {
        val review = deterministicReviewRunner?.invoke(submission, prompt, disputeContext, criticId)
            ?: run {
                val model: LLModel = OpenRouterModelResolver.resolve(criticModelId)
                val agent: AIAgent<String, String> = AIAgent.builder()
                    .promptExecutor(executor)
                    .llmModel(model)
                    .systemPrompt(systemPrompt)
                    .responseProcessor(EmptyAssistantResponseRecoveryProcessor)
                    .toolRegistry(ToolRegistry {})
                    .graphStrategy(singleRunStrategy())
                    .install(EventHandler.Feature) { config ->
                        config.installBlockingFailurePolicy("Critic[$criticId]")
                    }
                    .build()

                val responseText = runWithOpenRouterProviderRetry("Critic[$criticId]") {
                    agent.run(prompt)
                }
                parseResponse(responseText, criticId, submission.id)
            }

        return review
    }

    /**
     * Builds the review prompt for a staged submission.
     *
     * @param submission The staged submission being reviewed.
     * @param disputeContext Optional second-pass dispute context.
     * @return A plain-text prompt to send to the Critic agent.
     */
    fun buildPrompt(
        submission: StagingSubmissionEntity,
        disputeContext: String? = null
    ): String = buildString {
        append("""
            Review the following ${submission.type} submission from a ${submission.role} agent.

            Submission ID: ${submission.id}
            Type: ${submission.type}
            Speculative: ${submission.speculative}
            Outlier: ${submission.outlier}

            Content:
            ---
            ${submission.content.take(8000)}
            ${if (submission.content.length > 8000) "\n[...truncated...]" else ""}
            ---

            Respond with a JSON object containing:
            - "outcome": "PROMOTE", "REJECT", or "DISPUTE"
            - "initialTrustScore": number 0.0-1.0 (only if PROMOTE)
            - "reason": string (required if REJECT or DISPUTE, optional if PROMOTE)

            Criteria:
            - PROMOTE if the submission is accurate, useful, well-structured, and adds value to the KB
            - REJECT if it's incorrect, duplicate, low quality, incoherent, or violates norms
            - DISPUTE if quality is borderline or you need a second opinion

            For speculative submissions: lower bar for promotion (0.5+ is fine), but mark as speculative in reason.
            For Outlier submissions: allow unconventional approaches; only reject if factually incorrect.

            JSON response only:
        """.trimIndent())
        if (disputeContext != null) {
            append("\n\nNOTE — This is a SECOND REVIEW (arbitration). The first Critic disputed this submission with reason: \"$disputeContext\". Your verdict is final.")
        }
    }

    /**
     * Parses a JSON response from the Critic agent into a [CriticReview].
     *
     * @param responseText Raw model output, expected to contain a JSON object.
     * @param criticId Critic identity assigned to the review.
     * @param submissionId Submission being reviewed.
     * @return A structured [CriticReview].
     */
    fun parseResponse(responseText: String, criticId: String, submissionId: String): CriticReview {
        return try {
            val jsonStr = Regex("""\{.*\}""", RegexOption.DOT_MATCHES_ALL)
                .find(responseText)?.value ?: "{}"
            val obj = json.parseToJsonElement(jsonStr) as JsonObject
            val outcome = obj["outcome"]?.jsonPrimitive?.content?.let {
                CriticOutcome.valueOf(it)
            } ?: CriticOutcome.REJECT

            CriticReview(
                submissionId = submissionId,
                outcome = outcome,
                criticAgentId = criticId,
                initialTrustScore = obj["initialTrustScore"]?.jsonPrimitive?.content?.toDoubleOrNull(),
                rejectionReason = if (outcome == CriticOutcome.REJECT) obj["reason"]?.jsonPrimitive?.content else null,
                disputeReason = if (outcome == CriticOutcome.DISPUTE) obj["reason"]?.jsonPrimitive?.content else null
            )
        } catch (e: Exception) {
            Napier.w(e) { "Failed to parse Critic response: $responseText" }
            CriticReview(
                submissionId = submissionId,
                outcome = CriticOutcome.DISPUTE,
                criticAgentId = criticId,
                disputeReason = "Critic response parse error: ${e.message}"
            )
        }
    }

    private companion object {
        val CRITIC_SYSTEM_PROMPT = """
            You are a Critic agent in the EvoKammer civilisational multi-agent system.
            Your role is to evaluate submissions from other agents and decide whether to promote,
            reject, or dispute them.
            
            You are rigorous but fair. You promote high-quality, accurate, and useful content.
            You reject content that is incorrect, duplicate, incoherent, or violates system norms.
            You dispute borderline content that needs a second opinion.
            
            Always respond with a JSON object only.
        """.trimIndent()
    }
}
