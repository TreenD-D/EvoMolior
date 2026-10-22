package com.achulkov.evomolior.agents.factory

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.singleRunStrategy
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.features.eventHandler.feature.EventHandler
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import com.achulkov.evomolior.core.config.LlmBackend
import com.achulkov.evomolior.core.config.CodexReasoningEffort
import com.achulkov.evomolior.core.config.ModelSelectionConfig
import com.achulkov.evomolior.core.contract.CriticOutcome
import com.achulkov.evomolior.core.contract.CriticReview
import com.achulkov.evomolior.core.contract.GRADER_METHODOLOGY_DRAFT_SIGNATURE_PREFIX
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.repair.ToolRepairCode
import com.achulkov.evomolior.core.repair.ToolSemanticReview
import com.achulkov.evomolior.core.repair.ToolSemanticVerdict
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import io.github.aakira.napier.Napier
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/**
 * Runs the Critic LLM as a dedicated wrapper around the Koog agent runtime.
 *
 * The wrapper owns the prompt construction and response parsing so orchestration code only
 * needs to handle pre-review guards and review application. Tests can inject a deterministic
 * review runner to avoid live OpenRouter calls.
 *
 * @param openRouterApiKey API key for the selected [llmBackend].
 * @param llmBackend Backend used for critic model resolution and prompt execution.
 * @param criticModelId Model identifier used when no deterministic runner is supplied.
 * @param deterministicReviewRunner Optional test/runtime override that returns a final
 *   [CriticReview] directly instead of invoking the LLM.
 */
class CriticReviewAgent(
    openRouterApiKey: String,
    private val llmBackend: LlmBackend = LlmBackend.OPENROUTER,
    private val codexReasoningEffort: CodexReasoningEffort = CodexReasoningEffort.HIGH,
    private val criticModelId: String = ModelSelectionConfig.DEFAULT_CRITIC_PIPELINE_MODEL,
    private val systemPrompt: String = CRITIC_SYSTEM_PROMPT,
    private val deterministicReviewRunner: (suspend (
        submission: StagingSubmissionEntity,
        prompt: String,
        disputeContext: String?,
        criticId: String
    ) -> CriticReview)? = null,
    private val deterministicSemanticReviewRunner: (suspend (
        submission: StagingSubmissionEntity,
        prompt: String,
        criticId: String
    ) -> ToolSemanticReview)? = null,
    private val providerCostRecorder: ((String) -> ProviderCallCostRecorder)? = null,
    private val promptExecutor: PromptExecutor? = null
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val executor by lazy {
        promptExecutor ?: LlmExecutorFactory.create(
            backend = llmBackend,
            apiKey = openRouterApiKey,
            codexReasoningEffort = codexReasoningEffort
        )
    }

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
     * Produces a typed semantic TOOL review without projecting arbitrary rationale prose.
     *
     * @param submission Deterministically preflight-valid TOOL submission.
     * @param publicContractSummary Runtime-authored public contract and quantity summary.
     * @param criticId Stable Critic identity.
     * @return Closed semantic review suitable for deterministic compatibility validation.
     */
    suspend fun reviewToolSemantics(
        submission: StagingSubmissionEntity,
        publicContractSummary: String,
        criticId: String = "critic-${UUID.randomUUID().toString().take(8)}"
    ): ToolSemanticReview {
        require(submission.type == "TOOL") {
            "Typed semantic review is available only for TOOL submissions"
        }
        val prompt = buildToolSemanticPrompt(submission, publicContractSummary)
        return deterministicSemanticReviewRunner?.invoke(submission, prompt, criticId) ?: run {
            val model: LLModel = LlmExecutorFactory.resolveModel(llmBackend, criticModelId)
            val costRecorder = providerCostRecorder?.invoke(criticId)
            val agent: AIAgent<String, String> = AIAgent.builder()
                .promptExecutor(executor)
                .llmModel(model)
                .systemPrompt(TOOL_SEMANTIC_SYSTEM_PROMPT)
                .responseProcessor(EmptyAssistantResponseRecoveryProcessor)
                .toolRegistry(ToolRegistry {})
                .graphStrategy(singleRunStrategy())
                .install(EventHandler.Feature) { config ->
                    costRecorder?.let(config::installProviderCostRecorder)
                    config.installBlockingFailurePolicy("ToolSemanticCritic[$criticId]")
                }
                .build()
            val response = try {
                runWithOpenRouterProviderRetry("ToolSemanticCritic[$criticId]") {
                    agent.run(prompt)
                }
            } finally {
                costRecorder?.failedAll()
            }
            parseToolSemanticResponse(response)
        }
    }

    /**
     * Builds the closed semantic-review prompt from public authority only.
     *
     * @param submission TOOL candidate whose source is visible to the Critic.
     * @param publicContractSummary Runtime-owned public schema, reference, and quantity summary.
     */
    fun buildToolSemanticPrompt(
        submission: StagingSubmissionEntity,
        publicContractSummary: String
    ): String = """
        Review this deterministically preflight-valid TOOL using only the public authority below.

        Public contract authority:
        ---
        ${publicContractSummary.take(TOOL_SEMANTIC_PUBLIC_AUTHORITY_MAX_CHARS)}
        ---

        Candidate:
        ---
        ${modelSafeToolCandidate(submission.content)
            .take(TOOL_SEMANTIC_CANDIDATE_MAX_CHARS)}
        ---

        Return exactly ToolSemanticReview JSON:
        - verdict: PROMOTE, REJECT, or DISPUTE
        - publicFindings: zero or more objects containing only code, optional
          affectedPublicField, the exact checked-in remediationKey, and optional public
          quantitySpec copied from the authority above
        - safeRepairCodes: the exact unique code set represented by publicFindings
        - privateRationaleCommitment: lowercase SHA-256 only

        Allowed repair codes:
        ${ToolRepairCode.entries.joinToString(",") { it.name }}

        Your public tests prove local consistency only. Private promotion authority is separate.
        Private fixtures and exact private outputs are never disclosed. Do not invent or request
        private inputs, expected values, actual values, differences, fixture names, paths, URLs,
        stack traces, exception text, or another group's implementation. Codes are hypotheses
        against public semantics, never claims about hidden values. JSON only.
    """.trimIndent()

    /**
     * Parses a closed semantic review and fails closed without retaining malformed provider prose.
     *
     * @param responseText Raw provider response.
     */
    fun parseToolSemanticResponse(responseText: String): ToolSemanticReview = runCatching {
        val jsonValue = Regex("""\{.*\}""", RegexOption.DOT_MATCHES_ALL)
            .find(responseText)?.value ?: error("missing object")
        json.decodeFromString(ToolSemanticReview.serializer(), jsonValue)
    }.getOrElse {
        ToolSemanticReview(
            verdict = ToolSemanticVerdict.DISPUTE,
            publicFindings = emptyList(),
            safeRepairCodes = emptySet(),
            privateRationaleCommitment =
                AtlasHashing.hashText("typed-tool-semantic-review-parse-failure")
        )
    }

    private fun modelSafeToolCandidate(content: String): String {
        val payload = runCatching {
            json.parseToJsonElement(content) as? JsonObject
        }.getOrNull() ?: return content
        val tests = payload["tests"] as? JsonArray ?: return content
        val redactedTests = JsonArray(tests.map { element ->
            val test = element as? JsonObject ?: return@map element
            JsonObject(test.filterKeys { it != "testId" })
        })
        return AtlasHashing.canonicalJson(
            JsonObject(payload.toMutableMap().apply { put("tests", redactedTests) })
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
                val model: LLModel = LlmExecutorFactory.resolveModel(llmBackend, criticModelId)
                val costRecorder = providerCostRecorder?.invoke(criticId)
                val agent: AIAgent<String, String> = AIAgent.builder()
                    .promptExecutor(executor)
                    .llmModel(model)
                    .systemPrompt(systemPrompt)
                    .responseProcessor(EmptyAssistantResponseRecoveryProcessor)
                    .toolRegistry(ToolRegistry {})
                    .graphStrategy(singleRunStrategy())
                    .install(EventHandler.Feature) { config ->
                        costRecorder?.let(config::installProviderCostRecorder)
                        config.installBlockingFailurePolicy("Critic[$criticId]")
                    }
                    .build()

                val responseText = try {
                    runWithOpenRouterProviderRetry("Critic[$criticId]") {
                        agent.run(prompt)
                    }
                } finally {
                    // A lifecycle TTL can cancel Koog before its failure event is delivered.
                    costRecorder?.failedAll()
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
    ): String {
        val typedMethodology = submission.budgetSignature.startsWith(
            GRADER_METHODOLOGY_DRAFT_SIGNATURE_PREFIX
        )
        val contentLimit = if (typedMethodology) {
            TYPED_METHODOLOGY_REVIEW_CONTENT_MAX_CHARS
        } else {
            DEFAULT_REVIEW_CONTENT_MAX_CHARS
        }
        val boundedContent = submission.content.take(contentLimit)
        val truncationNotice = if (submission.content.length > contentLimit) {
            "\n[...bounded critic view: $contentLimit of ${submission.content.length} chars...]"
        } else {
            ""
        }
        val toolReviewPolicy = if (submission.type == "TOOL") {
            """

                TOOL-specific authority:
                - This TOOL reached you only after deterministic validation proved its exact sealed
                  opportunity identity, typed input/output schemas, closed implementation shape,
                  bounded runtime/output, and worker-visible executable tests.
                - If you PROMOTE it, the promotion boundary independently reruns the private sealed
                  fixtures in the sandbox and fails closed before registry activation on any mismatch.
                - Review semantic usefulness, concrete duplication, visible malicious behavior, and
                  objective alignment. Do not reject merely because private fixtures are unavailable
                  to you, because the payload is executable rather than prose, or because earlier
                  invalid attempts existed.
                - PROMOTE a useful exact-opportunity implementation unless you can name a concrete
                  semantic, duplication, safety, or objective-alignment defect visible in this payload.
            """.trimIndent()
        } else {
            ""
        }
        return buildString {
            append(
                """
                Review the following ${submission.type} submission from a ${submission.role} agent.

                Submission ID: ${submission.id}
                Type: ${submission.type}
                Speculative: ${submission.speculative}
                Outlier: ${submission.outlier}

                Content:
                ---
                $boundedContent$truncationNotice
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

                $toolReviewPolicy

                JSON response only:
                """.trimIndent()
            )
            if (typedMethodology) {
                append(
                    """

                    Typed grader-methodology review:
                    - The runtime decoded this payload and validated its typed publication contract
                      before staging it.
                    - Review objective specificity, falsifiability, test quality, failure analysis,
                      and receiver relevance.
                    - If no bounded-view marker appears above, the complete persisted payload is
                      present. Do not claim that it is truncated or syntactically incomplete.
                    """.trimIndent()
                )
            }
            if (disputeContext != null) {
                append(
                    "\n\nNOTE — This is a SECOND REVIEW (arbitration). The first Critic " +
                        "disputed this submission with reason: \"$disputeContext\". " +
                        "Your verdict is final."
                )
            }
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
        const val DEFAULT_REVIEW_CONTENT_MAX_CHARS = 8_000
        const val TYPED_METHODOLOGY_REVIEW_CONTENT_MAX_CHARS = 64_000
        const val TOOL_SEMANTIC_PUBLIC_AUTHORITY_MAX_CHARS = 24_000
        const val TOOL_SEMANTIC_CANDIDATE_MAX_CHARS = 64_000

        val CRITIC_SYSTEM_PROMPT = """
            You are a Critic agent in the EvoMolior civilisational multi-agent system.
            Your role is to evaluate submissions from other agents and decide whether to promote,
            reject, or dispute them.
            
            You are rigorous but fair. You promote high-quality, accurate, and useful content.
            You reject content that is incorrect, duplicate, incoherent, or violates system norms.
            You dispute borderline content that needs a second opinion.
            
            Always respond with a JSON object only.
        """.trimIndent()

        val TOOL_SEMANTIC_SYSTEM_PROMPT = """
            You are the typed semantic TOOL Critic. Return only the closed ToolSemanticReview JSON
            requested by the runtime. Never disclose, infer, or fabricate private fixture material.
        """.trimIndent()
    }
}
