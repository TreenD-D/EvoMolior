package com.achulkov.evokammer.competition.leaks

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.singleRunStrategy
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.executor.clients.openrouter.OpenRouterModels
import ai.koog.prompt.executor.llms.all.simpleOpenRouterExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider
import io.github.aakira.napier.Napier
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Applies LLM-based differential privacy to raw leak content before inter-group delivery.
 *
 * The transform strips source-traceable details while preserving conceptual patterns,
 * making it impossible for the recipient to reverse-engineer the source group's
 * implementation specifics.
 *
 * Transform constraints (applied by LLM prompt):
 * - No code identifiers (variable names, function signatures, import paths)
 * - No exact URLs, endpoints, or external source names
 * - No internal naming conventions (class prefixes, package structures)
 * - No verbatim quotes longer than 20 tokens
 * - Only conceptual patterns, design decisions, and high-level approaches survive
 * - Stochastic paraphrasing: reword to avoid surface similarity
 * - Random detail dropping: 20–40% of detail is discarded on each pass
 *
 * @param openRouterApiKey OpenRouter API key for the transform agent.
 * @param modelId LLM model to use for privacy transformation. Defaults to a fast, cheap model.
 * @param timeoutMs Maximum time (ms) to wait for the LLM response. Returns original on timeout.
 */
class DifferentialPrivacyTransform(
    private val openRouterApiKey: String,
    private val modelId: String = "google/gemini-2.5-flash",
    private val timeoutMs: Long = 10_000L
) {

    private val executor by lazy { simpleOpenRouterExecutor(openRouterApiKey) }

    /**
     * Transforms [rawContent] so that it cannot be traced back to the source group.
     *
     * Returns the transformed content, or [rawContent] unchanged if the transform
     * fails or times out (fail-open to preserve relay flow).
     *
     * @param rawContent The original KB fragment to be anonymised.
     * @param domain The knowledge domain of the content (hints prompt).
     * @return Anonymised content suitable for inter-group delivery.
     */
    suspend fun transform(rawContent: String, domain: String = "general"): String {
        if (rawContent.isBlank()) return rawContent

        val systemPrompt = buildSystemPrompt(domain)
        val model = resolveModel(modelId)

        return withTimeoutOrNull(timeoutMs) {
            try {
                val agent: AIAgent<String, String> = AIAgent.builder()
                    .promptExecutor(executor)
                    .llmModel(model)
                    .systemPrompt(systemPrompt)
                    .toolRegistry(ToolRegistry.EMPTY)
                    .maxIterations(1)
                    .graphStrategy(singleRunStrategy())
                    .build()

                val result = agent.run(
                    """
                    Apply the differential privacy transform to the following knowledge fragment.
                    
                    DOMAIN: $domain
                    
                    ORIGINAL CONTENT:
                    $rawContent
                    
                    Return ONLY the transformed content. No explanation. No preamble.
                    """.trimIndent()
                )
                result?.trim() ?: rawContent
            } catch (e: Exception) {
                Napier.w("DifferentialPrivacyTransform: LLM transform failed, returning original: ${e.message}")
                rawContent
            }
        } ?: run {
            Napier.w("DifferentialPrivacyTransform: transform timed out for domain=$domain, returning original")
            rawContent
        }
    }

    private fun buildSystemPrompt(domain: String): String = """
        You are a differential privacy anonymisation agent. Your task is to transform knowledge
        fragments so they cannot be traced back to their original source, while preserving
        their conceptual value for the recipient.
        
        MANDATORY RULES (violating any rule results in failure):
        1. Remove ALL code identifiers: variable names, function names, class names, package paths.
        2. Remove ALL exact URLs, endpoint paths, API names, and external service names.
        3. Remove ALL internal naming conventions that could identify an implementation.
        4. Do NOT include verbatim sequences longer than 15 consecutive tokens from the input.
        5. Paraphrase actively — restate ideas in different sentence structures and vocabulary.
        6. Drop 20–40% of specific details randomly. Only high-level patterns survive.
        7. Do NOT add new information, speculation, or embellishment.
        8. Preserve the domain context ("$domain") — keep the field and general approach.
        9. Output should be 30–70% of the original length.
        10. Output plain text only — no JSON, no code blocks, no markdown.
    """.trimIndent()

    private fun resolveModel(id: String): LLModel = when {
        id.contains("gemini-2.5-flash-lite") -> OpenRouterModels.Gemini2_5FlashLite
        id.contains("gemini-2.5-flash")      -> OpenRouterModels.Gemini2_5Flash
        id.contains("gemini-2.5-pro")        -> OpenRouterModels.Gemini2_5Pro
        id.contains("gpt-4o-mini")           -> OpenRouterModels.GPT4oMini
        id.contains("claude-3-5-sonnet")     -> OpenRouterModels.Claude3_5Sonnet
        else -> LLModel(provider = LLMProvider.OpenRouter, id = id)
    }
}
