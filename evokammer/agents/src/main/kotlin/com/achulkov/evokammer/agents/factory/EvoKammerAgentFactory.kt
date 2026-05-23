@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evokammer.agents.factory

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.singleRunStrategy
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.features.eventHandler.feature.EventHandler
import ai.koog.prompt.dsl.prompt as koogPrompt
import ai.koog.prompt.executor.clients.openrouter.OpenRouterModels
import ai.koog.prompt.executor.llms.all.simpleOpenRouterExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.serialization.kotlinx.KotlinxSerializer
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
        // Prefer the exact budget allocated by the EvolutionarySelector (bootContext.allocatedBudget).
        // Fall back to the profile's spawn budget only when no allocation was carried
        // (e.g. direct factory construction in tests or older callers).
        val initialBudget = if (bootContext.allocatedBudget > 0.0) bootContext.allocatedBudget
                            else budgetConfig.profile.spawnBudget
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

        // Build the Koog agent using the 0.7.2 builder API.
        // maxIterations=25: enough for productive agents; caps budget burn when tools keep failing.
        // The default of 50 is too generous when tool errors cause retry loops.
        //
        // IMPORTANT: The JVM AIAgentBuilder defaults to JacksonSerializer (plain ObjectMapper
        // without jackson-module-kotlin). All Args data classes use @Serializable from
        // kotlinx.serialization — they require KotlinxSerializer for correct deserialization.
        // We inject it via AIAgentConfig BEFORE .graphStrategy() snapshots the config, so that
        // subsequent config.copy() calls (from .maxIterations etc.) preserve the serializer.
        val agentId = bootContext.agentId
        val roleName = bootContext.role.name
        val agent: AIAgent<String, String> = AIAgent.builder()
            .promptExecutor(executor)
            .agentConfig(
                AIAgentConfig(
                    prompt = koogPrompt("evo-agent") { system(systemPrompt) },
                    model = model,
                    maxAgentIterations = 25,
                    // ignoreUnknownKeys allows LLMs that pass extra fields (e.g. "domain" on
                    // write_staging) to succeed rather than failing with a parse error.
                    serializer = KotlinxSerializer(kotlinx.serialization.json.Json {
                        ignoreUnknownKeys = true
                        isLenient = true
                    })
                )
            )
            .toolRegistry(toolRegistry)
            .graphStrategy(singleRunStrategy())
            .maxIterations(25)
            // ── Koog lifecycle event handler ─────────────────────────────────
            // Provides per-call Napier traces for every tool dispatch, LLM call,
            // and agent lifecycle transition so logs flow into the UI debug panel.
            // ConfigureAction<EventHandlerConfig> is a SAM with `configure(config: T)`,
            // so `config` is the parameter (not the receiver) — use `config.onX { ... }`.
            .install(EventHandler.Feature) { config ->
                config.onAgentStarting { _ ->
                    io.github.aakira.napier.Napier.i(
                        "Koog[$roleName/$agentId] agent starting"
                    )
                }
                config.onAgentCompleted { ctx ->
                    val resultSnippet = ctx.result?.toString()?.take(200) ?: "(no result)"
                    io.github.aakira.napier.Napier.i(
                        "Koog[$roleName/$agentId] agent completed — result: $resultSnippet"
                    )
                }
                config.onAgentExecutionFailed { ctx ->
                    io.github.aakira.napier.Napier.e(
                        "Koog[$roleName/$agentId] agent FAILED: ${ctx.throwable.message}",
                        ctx.throwable
                    )
                }
                config.onToolCallStarting { ctx ->
                    val args = ctx.toolArgs.toString().take(300)
                    io.github.aakira.napier.Napier.d(
                        "Koog[$roleName/$agentId] → ${ctx.toolName}($args)"
                    )
                }
                config.onToolCallCompleted { ctx ->
                    val result = ctx.toolResult?.toString()?.take(300) ?: "null"
                    io.github.aakira.napier.Napier.d(
                        "Koog[$roleName/$agentId] ← ${ctx.toolName} = $result"
                    )
                }
                config.onToolCallFailed { ctx ->
                    io.github.aakira.napier.Napier.w(
                        "Koog[$roleName/$agentId] ✕ ${ctx.toolName} FAILED: ${ctx.message}"
                    )
                }
                config.onToolValidationFailed { ctx ->
                    io.github.aakira.napier.Napier.w(
                        "Koog[$roleName/$agentId] ✕ ${ctx.toolName} VALIDATION FAILED: ${ctx.message}"
                    )
                }
                config.onLLMCallStarting { _ ->
                    io.github.aakira.napier.Napier.d(
                        "Koog[$roleName/$agentId] LLM call starting"
                    )
                }
                config.onLLMCallCompleted { _ ->
                    io.github.aakira.napier.Napier.d(
                        "Koog[$roleName/$agentId] LLM call completed"
                    )
                }
            }
            .build()

        return AgentContext(
            agent = agent,
            budgetLedger = budgetLedger,
            bootContext = bootContext
        )
    }

    /**
     * Writes an emergency death note directly to staging when an agent terminates abnormally
     * (e.g. max iterations reached, uncaught exception, tool failures). Called from the
     * agent runner's catch block to guarantee some record always exists.
     *
     * Death Notes written here are auto-promoted (skip Critic review).
     *
     * @param bootContext Agent identity and context.
     * @param reason Short description of why the agent terminated unexpectedly.
     * @param errorMessage Full error/exception message (truncated to 500 chars).
     */
    suspend fun writeEmergencyDeathNote(
        bootContext: AgentBootContext,
        reason: String,
        errorMessage: String
    ) {
        try {
            val submissionId = "death-auto-${bootContext.agentId}-${System.currentTimeMillis()}"
            val content = buildString {
                appendLine("=== EMERGENCY DEATH NOTE (auto-generated) ===")
                appendLine("Agent: ${bootContext.agentId} (${bootContext.role.name})")
                appendLine("Termination: $reason")
                appendLine("Error: ${errorMessage.take(500)}")
                appendLine()
                appendLine("This note was auto-written by the runner because the agent terminated")
                appendLine("before completing its own write_death_note call.")
            }
            db.stagingDao().insert(
                com.achulkov.evokammer.substrate.db.entity.StagingSubmissionEntity(
                    id = submissionId,
                    agentId = bootContext.agentId,
                    role = bootContext.role.name,
                    type = "DEATH_NOTE",
                    content = content,
                    wipTaskRef = bootContext.wipTaskClaim?.nodeId,
                    speculative = false,
                    outlier = false,
                    budgetSignature = "EMERGENCY:${bootContext.agentId}",
                    status = "promoted",
                    initialTrustScore = 0.2,
                    generation = bootContext.generation,
                    groupId = bootContext.groupId,
                    runId = bootContext.runId,
                    submittedAtMs = System.currentTimeMillis(),
                    reviewedAtMs = System.currentTimeMillis()
                )
            )
            io.github.aakira.napier.Napier.i(
                "EvoKammerAgentFactory: emergency death note written for ${bootContext.agentId} (id=$submissionId)"
            )
        } catch (insertEx: Exception) {
            // Best-effort — never let the emergency write crash the runner
            io.github.aakira.napier.Napier.e(
                "EvoKammerAgentFactory: failed to write emergency death note for ${bootContext.agentId}: ${insertEx.message}",
                insertEx
            )
        }
    }

    /**
     * Resolves a model string (e.g. "anthropic/claude-3-haiku") to a [LLModel].
     *
     * First checks the [OpenRouterModels] catalogue; falls back to a generic [LLModel]
     * with standard capabilities (including [LLMCapability.Completion]) so any custom
     * model string passed in the boot context can still run.
     *
     * IMPORTANT: the fallback MUST include [LLMCapability.Completion] — without it Koog
     * rejects the model before even making an API call.
     */
    private fun resolveModel(modelId: String): LLModel {
        return when (modelId) {
            // Gemini models
            "google/gemini-3-flash-preview"    -> OpenRouterModels.Gemini2_5Flash         // legacy ID remapped
            "google/gemini-2.5-flash"          -> OpenRouterModels.Gemini2_5Flash
            "google/gemini-2.5-flash-lite"     -> OpenRouterModels.Gemini2_5FlashLite
            "google/gemini-2.5-pro"            -> OpenRouterModels.Gemini2_5Pro
            // Anthropic models — note: "claude-sonnet-4.6" and "claude-sonnet-4" both map to Claude4Sonnet
            "anthropic/claude-sonnet-4.6",
            "anthropic/claude-sonnet-4"        -> OpenRouterModels.Claude4Sonnet
            "anthropic/claude-haiku-4.5"       -> OpenRouterModels.Claude4_5Haiku
            "anthropic/claude-sonnet-4.5"      -> OpenRouterModels.Claude4_5Sonnet
            "anthropic/claude-3-haiku"         -> OpenRouterModels.Claude3Haiku
            "anthropic/claude-3-5-sonnet"      -> OpenRouterModels.Claude3_5Sonnet
            "anthropic/claude-3-7-sonnet"      -> OpenRouterModels.Claude3_7Sonnet
            // OpenAI models
            "openai/gpt-oss-120b"              -> OpenRouterModels.GPT_OSS_120b
            "openai/gpt-4o"                    -> OpenRouterModels.GPT4o
            "openai/gpt-4o-mini"               -> OpenRouterModels.GPT4oMini
            "openai/gpt-5"                     -> OpenRouterModels.GPT5
            "openai/gpt-5-mini"                -> OpenRouterModels.GPT5Mini
            // DeepSeek models — "deepseek-v3.2" is a non-existent ID, remap to the catalogue version
            "deepseek/deepseek-v3.2",
            "deepseek/deepseek-chat-v3-0324"   -> OpenRouterModels.DeepSeekV30324
            // MiniMax models — not in catalogue, substitute with capable alternative
            "minimax/minimax-m2.5"             -> OpenRouterModels.GPT4o
            // Other models
            "microsoft/phi-4-reasoning:free"   -> OpenRouterModels.Phi4Reasoning
            // Fallback: create a generic model entry WITH capabilities so Koog doesn't reject it
            else                               -> LLModel(
                provider = LLMProvider.OpenRouter,
                id = modelId,
                capabilities = listOf(
                    LLMCapability.Temperature,
                    LLMCapability.Speculation,
                    LLMCapability.Tools,
                    LLMCapability.Completion
                )
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
