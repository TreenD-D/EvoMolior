package com.achulkov.evokammer.agents.factory

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.singleRunStrategy
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.features.eventHandler.feature.EventHandler
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import com.achulkov.evokammer.core.config.ModelSelectionConfig
import com.achulkov.evokammer.core.contract.BlockingAgentAbortException
import com.achulkov.evokammer.core.model.Role
import io.github.aakira.napier.Napier

/**
 * Creates and runs Genesis Council agents using Koog.
 *
 * Council agents are lightweight: no substrate tools, no budget ledger.
 * Each agent receives accumulated context from all previous council agents and
 * produces structured JSON output.
 *
 * This class provides the [agentRunner] callback consumed by `GenesisCouncil`.
 *
 * @param openRouterApiKey OpenRouter API key for LLM calls.
 * @param councilModelId The LLM model to use for all council agents. Defaults to the shared
 *   cost-conscious council model from [ModelSelectionConfig].
 * @param maxIterations Maximum Koog iterations per council agent (defaults to 10 — single-shot
 *   agents still need ≥2 graph iterations: one for the LLM call and one to return the result).
 */
class CouncilRunner(
    private val openRouterApiKey: String,
    private val councilModelId: String = ModelSelectionConfig.DEFAULT_COUNCIL_MODEL,
    private val maxIterations: Int = 10,
    private val promptExecutor: PromptExecutor? = null
) {

    private val executor by lazy { promptExecutor ?: createOpenRouterPromptExecutor(openRouterApiKey) }

    /**
     * Returns a suspend function compatible with `GenesisCouncil.run`.
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
                .responseProcessor(EmptyAssistantResponseRecoveryProcessor)
                .toolRegistry(ToolRegistry.EMPTY)
                .maxIterations(maxIterations)
                .graphStrategy(singleRunStrategy())
                .install(EventHandler.Feature) { config ->
                    config.installBlockingFailurePolicy("Council[$role]")
                }
                .build()

            val result = runWithOpenRouterProviderRetry("Council[$role]") {
                agent.run(
                    """
                    COUNCIL CONTEXT (outputs from previous council agents):
                    $contextJson
                    
                    Produce your structured JSON output now.
                    """.trimIndent()
                )
            }
            val output = result.trim().ifBlank { "{}" }
            Napier.i("CouncilRunner: ${role.name} produced ${output.length} chars")
            output
        } catch (e: BlockingAgentAbortException) {
            Napier.e(e) { "CouncilRunner: ${role.name} aborted due to a blocking runtime error" }
            throw e
        } catch (e: Exception) {
            Napier.e("CouncilRunner: ${role.name} failed — ${e.message}")
            "{}" // Return empty JSON so the council can continue
        }
    }

    private fun resolveModel(modelId: String): LLModel = OpenRouterModelResolver.resolve(modelId)
}
