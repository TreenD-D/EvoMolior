package com.achulkov.evokammer.agents.factory

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.singleRunStrategy
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.executor.clients.openrouter.OpenRouterModels
import ai.koog.prompt.executor.llms.all.simpleOpenRouterExecutor
import ai.koog.prompt.llm.LLModel
import com.achulkov.evokammer.agents.tools.ClaimTaskTool
import com.achulkov.evokammer.agents.tools.CheckConsistencyTool
import com.achulkov.evokammer.agents.tools.EmitConstraintTool
import com.achulkov.evokammer.agents.tools.EmitFactTool
import com.achulkov.evokammer.agents.tools.ExecuteToolTool
import com.achulkov.evokammer.agents.tools.QueryPatternTool
import com.achulkov.evokammer.agents.tools.ReadKBTool
import com.achulkov.evokammer.agents.tools.ReadKGTool
import com.achulkov.evokammer.agents.tools.SearchKBTool
import com.achulkov.evokammer.agents.tools.SendMessageTool
import com.achulkov.evokammer.agents.tools.WriteDeathNoteTool
import com.achulkov.evokammer.agents.tools.WriteStagingTool
import com.achulkov.evokammer.sandbox.network.ContentSanitiser
import com.achulkov.evokammer.agents.runner.DeathNoteProtocol
import com.achulkov.evokammer.core.budget.BudgetConfig
import com.achulkov.evokammer.core.budget.BudgetLedger
import com.achulkov.evokammer.core.contract.AgentBootContext
import com.achulkov.evokammer.sandbox.toolsandbox.ToolSubSandbox
import com.achulkov.evokammer.substrate.db.EvoKammerDatabase

/**
 * Creates Koog [AIAgent] instances for EvoKammer agent roles.
 *
 * The factory wires together:
 * - Koog's OpenRouter executor (via [simpleOpenRouterExecutor])
 * - Role-specific system prompt (built by [RolePromptBuilder])
 * - All EvoKammer custom tools ([ToolRegistry])
 * - [BudgetLedger] shared by tools for token deduction
 *
 * Koog 0.7.2 builder API is used:
 * ```
 * AIAgent.builder()
 *     .promptExecutor(simpleOpenRouterExecutor(apiKey))
 *     .llmModel(model)
 *     .systemPrompt(prompt)
 *     .toolRegistry(toolRegistry)
 *     .graphStrategy(singleRunStrategy())
 *     .build()
 * ```
 *
 * @param openRouterApiKey OpenRouter API key.
 * @param db The Room database for the current (runId, groupId).
 * @param toolSubSandbox The sub-sandbox for tool execution.
 * @param budgetConfig The group's budget configuration.
 * @param toolsMountPath Path to the /tools mount inside the agent VM.
 */
class EvoKammerAgentFactory(
    private val openRouterApiKey: String,
    private val db: EvoKammerDatabase,
    private val toolSubSandbox: ToolSubSandbox,
    private val budgetConfig: BudgetConfig,
    private val toolsMountPath: String = "/tools",
    private val contentSanitiser: ContentSanitiser = ContentSanitiser()
) {
    private val executor by lazy { simpleOpenRouterExecutor(openRouterApiKey) }

    /**
     * Creates an [AgentContext] containing a Koog [AIAgent] and its [BudgetLedger].
     *
     * @param bootContext The agent's boot context.
     * @param existingBudgetLedger If provided, use this ledger instead of creating a new one.
     *   Used by the sub-agent delegation flow to inject the carved budget slice.
     * @param deathNoteProtocol Optional [DeathNoteProtocol] for in-process agent runs.
     *   When provided, [WriteDeathNoteTool] will call [DeathNoteProtocol.markDeathNoteWritten]
     *   so the grace-period hard-kill timer is cancelled on successful Death Note write.
     * @return An [AgentContext] ready to run.
     */
    fun create(
        bootContext: AgentBootContext,
        existingBudgetLedger: BudgetLedger? = null,
        deathNoteProtocol: DeathNoteProtocol? = null
    ): AgentContext {
        val initialBudget = budgetConfig.profile.spawnBudget
        val floor = budgetConfig.floorFor(initialBudget)

        val budgetLedger = existingBudgetLedger ?: BudgetLedger(
            agentId = bootContext.agentId,
            initialBudget = initialBudget,
            floor = floor,
            config = budgetConfig
        )

        val systemPrompt = RolePromptBuilder.build(bootContext)
        val isOutlier = bootContext.role.name == "Outlier"

        val toolRegistry = ToolRegistry {
            // Always-available tools
            tool(ReadKBTool(db.knowledgeBaseDao(), bootContext.runId, bootContext.groupId))
            tool(SearchKBTool(db.knowledgeBaseDao(), bootContext.runId, bootContext.groupId))
            tool(ReadKGTool(db.knowledgeGraphFactDao(), bootContext.runId, bootContext.groupId))
            tool(
                QueryPatternTool(
                    knowledgeGraphFactDao = db.knowledgeGraphFactDao(),
                    budgetLedger = budgetLedger,
                    runId = bootContext.runId,
                    groupId = bootContext.groupId
                )
            )
            tool(
                CheckConsistencyTool(
                    kgSchemaDao = db.kgSchemaDao(),
                    knowledgeGraphFactDao = db.knowledgeGraphFactDao(),
                    runId = bootContext.runId,
                    groupId = bootContext.groupId
                )
            )
            tool(
                WriteStagingTool(
                    stagingDao = db.stagingDao(),
                    budgetLedger = budgetLedger,
                    runId = bootContext.runId,
                    groupId = bootContext.groupId,
                    agentId = bootContext.agentId,
                    role = bootContext.role.name,
                    generation = bootContext.generation,
                    isOutlier = isOutlier
                )
            )
            tool(
                WriteDeathNoteTool(
                    stagingDao = db.stagingDao(),
                    runId = bootContext.runId,
                    groupId = bootContext.groupId,
                    agentId = bootContext.agentId,
                    role = bootContext.role.name,
                    generation = bootContext.generation,
                    onWritten = deathNoteProtocol?.let { protocol -> { protocol.markDeathNoteWritten() } }
                )
            )
            tool(
                EmitFactTool(
                    stagingDao = db.stagingDao(),
                    budgetLedger = budgetLedger,
                    runId = bootContext.runId,
                    groupId = bootContext.groupId,
                    agentId = bootContext.agentId,
                    role = bootContext.role.name,
                    generation = bootContext.generation
                )
            )
            tool(
                EmitConstraintTool(
                    stagingDao = db.stagingDao(),
                    budgetLedger = budgetLedger,
                    runId = bootContext.runId,
                    groupId = bootContext.groupId,
                    agentId = bootContext.agentId,
                    role = bootContext.role.name,
                    generation = bootContext.generation
                )
            )
            tool(
                ExecuteToolTool(
                    toolRegistryDao = db.toolRegistryDao(),
                    toolSubSandbox = toolSubSandbox,
                    budgetLedger = budgetLedger,
                    runId = bootContext.runId,
                    groupId = bootContext.groupId,
                    toolsMountPath = toolsMountPath,
                    contentSanitiser = contentSanitiser
                )
            )
            tool(
                SendMessageTool(
                    budgetLedger = budgetLedger,
                    eventLogDao = db.eventLogDao(),
                    runId = bootContext.runId,
                    groupId = bootContext.groupId,
                    agentId = bootContext.agentId,
                    generation = bootContext.generation
                )
            )
            tool(
                ClaimTaskTool(
                    wipTaskDao = db.wipTaskDao(),
                    runId = bootContext.runId,
                    groupId = bootContext.groupId,
                    agentId = bootContext.agentId,
                    generation = bootContext.generation
                )
            )
        }

        // Resolve the model — each agent gets the model assigned in the boot context
        val model: LLModel = resolveModel(bootContext.modelAssignment)

        // Build the Koog agent using the 0.7.2 builder API
        val agent: AIAgent<String, String> = AIAgent.builder()
            .promptExecutor(executor)
            .llmModel(model)
            .systemPrompt(systemPrompt)
            .toolRegistry(toolRegistry)
            .graphStrategy(singleRunStrategy())
            .build()

        return AgentContext(
            agent = agent,
            budgetLedger = budgetLedger,
            bootContext = bootContext
        )
    }

    /**
     * Resolves a model string (e.g. "anthropic/claude-3-haiku") to a [LLModel].
     *
     * First checks the [OpenRouterModels] catalogue; falls back to a generic [LLModel]
     * so the agent can still run with any model string passed in the boot context.
     */
    private fun resolveModel(modelId: String): LLModel {
        return when (modelId) {
            // Gemini models
            "google/gemini-3-flash-preview"    -> LLModel(provider = ai.koog.prompt.llm.LLMProvider.OpenRouter, id = modelId)
            "google/gemini-2.5-flash"          -> OpenRouterModels.Gemini2_5Flash
            "google/gemini-2.5-flash-lite"     -> OpenRouterModels.Gemini2_5FlashLite
            "google/gemini-2.5-pro"            -> OpenRouterModels.Gemini2_5Pro
            // Anthropic models
            "anthropic/claude-sonnet-4.6"      -> LLModel(provider = ai.koog.prompt.llm.LLMProvider.OpenRouter, id = modelId)
            "anthropic/claude-3-haiku"         -> OpenRouterModels.Claude3Haiku
            "anthropic/claude-3-5-sonnet"      -> OpenRouterModels.Claude3_5Sonnet
            "anthropic/claude-3-7-sonnet"      -> OpenRouterModels.Claude3_7Sonnet
            // OpenAI models
            "openai/gpt-oss-120b"              -> OpenRouterModels.GPT_OSS_120b
            "openai/gpt-4o"                    -> OpenRouterModels.GPT4o
            "openai/gpt-4o-mini"               -> OpenRouterModels.GPT4oMini
            // DeepSeek models
            "deepseek/deepseek-v3.2"           -> LLModel(provider = ai.koog.prompt.llm.LLMProvider.OpenRouter, id = modelId)
            // MiniMax models
            "minimax/minimax-m2.5"             -> LLModel(provider = ai.koog.prompt.llm.LLMProvider.OpenRouter, id = modelId)
            // Other models
            "microsoft/phi-4-reasoning:free"   -> OpenRouterModels.Phi4Reasoning
            else                               -> LLModel(
                provider = ai.koog.prompt.llm.LLMProvider.OpenRouter,
                id = modelId
            )
        }
    }
}

/** Container for a created agent and its associated context. */
data class AgentContext(
    val agent: AIAgent<String, String>,
    val budgetLedger: BudgetLedger,
    val bootContext: AgentBootContext
)
