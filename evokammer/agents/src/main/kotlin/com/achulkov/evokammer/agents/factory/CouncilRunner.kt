package com.achulkov.evokammer.agents.factory

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.singleRunStrategy
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.executor.clients.openrouter.OpenRouterModels
import ai.koog.prompt.executor.llms.all.simpleOpenRouterExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider
import com.achulkov.evokammer.core.model.Role
import io.github.aakira.napier.Napier

/**
 * Creates and runs Genesis Council agents using Koog.
 *
 * Council agents are lightweight: no substrate tools, no budget ledger.
 * Each agent receives accumulated context from all previous council agents and
 * produces structured JSON output.
 *
 * This class provides the [agentRunner] callback consumed by [com.achulkov.evokammer.orchestration.council.GenesisCouncil].
 *
 * @param openRouterApiKey OpenRouter API key for LLM calls.
 * @param councilModelId The LLM model to use for all council agents. Defaults to a strong reasoning model.
 * @param maxIterations Maximum Koog iterations per council agent (defaults to 1 — single-shot).
 */
class CouncilRunner(
    private val openRouterApiKey: String,
    private val councilModelId: String = "anthropic/claude-3-7-sonnet",
    private val maxIterations: Int = 1
) {

    private val executor by lazy { simpleOpenRouterExecutor(openRouterApiKey) }

    /**
     * Returns a suspend function compatible with [com.achulkov.evokammer.orchestration.council.GenesisCouncil.run].
     *
     * Usage:
     * ```kotlin
     * val council = GenesisCouncil(objective, apiKey)
     * val runner = CouncilRunner(apiKey)
     * val startingField = council.run(runner.agentRunner())
     * ```
     */
    fun agentRunner(): suspend (role: Role, contextJson: String) -> String = { role, contextJson ->
        runCouncilAgent(role, contextJson)
    }

    /**
     * Runs a single council agent for [role] with [contextJson] as its task input.
     *
     * @return The agent's output (expected to be a JSON string).
     */
    suspend fun runCouncilAgent(role: Role, contextJson: String): String {
        require(role.isCouncilRole) { "CouncilRunner: role $role is not a council role" }

        val systemPrompt = CouncilRolePrompts.forRole(role)
        val model: LLModel = resolveModel(councilModelId)

        Napier.i("CouncilRunner: spawning ${role.name} agent")

        return try {
            val agent: AIAgent<String, String> = AIAgent.builder()
                .promptExecutor(executor)
                .llmModel(model)
                .systemPrompt(systemPrompt)
                .toolRegistry(ToolRegistry.EMPTY)
                .maxIterations(maxIterations)
                .graphStrategy(singleRunStrategy())
                .build()

            val result = agent.run(
                """
                COUNCIL CONTEXT (outputs from previous council agents):
                $contextJson
                
                Produce your structured JSON output now.
                """.trimIndent()
            )
            val output = result?.trim() ?: "{}"
            Napier.i("CouncilRunner: ${role.name} produced ${output.length} chars")
            output
        } catch (e: Exception) {
            Napier.e("CouncilRunner: ${role.name} failed — ${e.message}")
            "{}" // Return empty JSON so the council can continue
        }
    }

    private fun resolveModel(modelId: String): LLModel = when (modelId) {
        "google/gemini-2.5-flash"       -> OpenRouterModels.Gemini2_5Flash
        "google/gemini-2.5-flash-lite"  -> OpenRouterModels.Gemini2_5FlashLite
        "google/gemini-2.5-pro"         -> OpenRouterModels.Gemini2_5Pro
        "anthropic/claude-3-5-sonnet"   -> OpenRouterModels.Claude3_5Sonnet
        "anthropic/claude-3-7-sonnet"   -> OpenRouterModels.Claude3_7Sonnet
        "openai/gpt-4o"                 -> OpenRouterModels.GPT4o
        "openai/gpt-4o-mini"            -> OpenRouterModels.GPT4oMini
        else -> LLModel(provider = LLMProvider.OpenRouter, id = modelId)
    }
}
