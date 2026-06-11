@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evokammer.agents.factory

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.singleRunStrategy
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.features.eventHandler.feature.EventHandler
import ai.koog.prompt.dsl.prompt as koogPrompt
import ai.koog.prompt.llm.LLModel
import ai.koog.serialization.kotlinx.KotlinxSerializer
import com.achulkov.evokammer.agents.delegation.SubAgentCoordinator
import com.achulkov.evokammer.agents.tools.ClaimTaskTool
import com.achulkov.evokammer.agents.tools.CheckConsistencyTool
import com.achulkov.evokammer.agents.tools.DelegateSubTaskTool
import com.achulkov.evokammer.agents.tools.EmitConstraintTool
import com.achulkov.evokammer.agents.tools.EmitFactTool
import com.achulkov.evokammer.agents.tools.ExecuteToolTool
import com.achulkov.evokammer.agents.tools.QueryPatternTool
import com.achulkov.evokammer.agents.tools.ReadKBTool
import com.achulkov.evokammer.agents.tools.ReadKGTool
import com.achulkov.evokammer.agents.tools.ReadRunArtifactTool
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
 * - Koog's OpenRouter executor (built from the published OpenRouter client and multi-provider executor)
 * - Role-specific system prompt (built by [RolePromptBuilder])
 * - All EvoKammer custom tools ([ToolRegistry])
 * - [BudgetLedger] shared by tools for token deduction
 *
 * Koog 1.0.0 builder API is used:
 * ```
 * AIAgent.builder()
 *     .promptExecutor(createOpenRouterPromptExecutor(apiKey))
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
    private val executor by lazy { createOpenRouterPromptExecutor(openRouterApiKey) }

    internal fun shouldInstallDelegationTool(bootContext: AgentBootContext): Boolean =
        bootContext.role.canDelegateToSubAgents && !bootContext.isSubAgent

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
                ReadRunArtifactTool(
                    artifactsMountPath = bootContext.artifactsMountPath,
                    inputArtifacts = bootContext.inputArtifacts
                )
            )
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
                    defaultWipTaskRef = bootContext.wipTaskClaim?.nodeId,
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
                    generation = bootContext.generation,
                    defaultWipTaskRef = bootContext.wipTaskClaim?.nodeId
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
                    generation = bootContext.generation,
                    defaultWipTaskRef = bootContext.wipTaskClaim?.nodeId
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
            if (shouldInstallDelegationTool(bootContext)) {
                tool(
                    DelegateSubTaskTool(
                        coordinator = SubAgentCoordinator(
                            parentContext = bootContext,
                            parentBudgetLedger = budgetLedger,
                            db = db,
                            openRouterApiKey = openRouterApiKey,
                            budgetConfig = budgetConfig
                        ),
                        bootContext = bootContext
                    )
                )
            }
        }

        // Resolve the model — each agent gets the model assigned in the boot context
        val model: LLModel = resolveModel(bootContext.modelAssignment)

        // Build the Koog agent using the 0.7.2 builder API.
        // The iteration cap comes from BudgetConfig so it can be tuned centrally with the
        // agent economy rather than drifting as a per-factory literal.
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
                    maxAgentIterations = budgetConfig.maxIterations,
                    responseProcessor = EmptyAssistantResponseRecoveryProcessor,
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
            .maxIterations(budgetConfig.maxIterations)
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
                        "Koog[$roleName/$agentId] agent FAILED: ${ctx.error.message}",
                        ctx.error
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
                config.installBlockingFailurePolicy("Koog[$roleName/$agentId]")
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
     * @param finalResult Optional final agent output to preserve when the agent completed
     *   without tool/write activity. Truncated before persistence.
     */
    suspend fun writeEmergencyDeathNote(
        bootContext: AgentBootContext,
        reason: String,
        errorMessage: String,
        finalResult: String? = null,
        noteKind: String = "emergency"
    ) {
        try {
            val submissionId = "death-auto-${bootContext.agentId}-${System.currentTimeMillis()}"
            val content = buildString {
                appendLine("=== ${noteKind.uppercase(java.util.Locale.ROOT)} DEATH NOTE (auto-generated) ===")
                appendLine("Agent: ${bootContext.agentId} (${bootContext.role.name})")
                appendLine("Termination: $reason")
                appendLine("Error: ${errorMessage.take(500)}")
                finalResult?.takeIf { it.isNotBlank() }?.let { text ->
                    appendLine()
                    appendLine("Final result:")
                    appendLine(text.take(2000))
                }
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
                "EvoKammerAgentFactory: $noteKind death note written for ${bootContext.agentId} (id=$submissionId)"
            )
        } catch (insertEx: Exception) {
            // Best-effort — never let the emergency write crash the runner
            io.github.aakira.napier.Napier.e(
                "EvoKammerAgentFactory: failed to write $noteKind death note for ${bootContext.agentId}: ${insertEx.message}",
                insertEx
            )
        }
    }

    private fun resolveModel(modelId: String): LLModel {
        return OpenRouterModelResolver.resolve(modelId)
    }
}

/** Container for a created agent and its associated context. */
data class AgentContext(
    val agent: AIAgent<String, String>,
    val budgetLedger: BudgetLedger,
    val bootContext: AgentBootContext
)
