@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evokammer.agents.factory

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.singleRunStrategy
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.features.eventHandler.feature.EventHandler
import ai.koog.prompt.executor.clients.openrouter.OpenRouterLLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.dsl.prompt as koogPrompt
import ai.koog.prompt.llm.LLModel
import ai.koog.serialization.kotlinx.KotlinxSerializer
import com.achulkov.evokammer.core.config.ModelSelectionConfig
import com.achulkov.evokammer.agents.runner.DeathNoteProtocol
import com.achulkov.evokammer.agents.tools.EmitFactTool
import com.achulkov.evokammer.agents.tools.ReadKBTool
import com.achulkov.evokammer.agents.tools.ReadKGTool
import com.achulkov.evokammer.agents.tools.ReadRunArtifactTool
import com.achulkov.evokammer.agents.tools.SendMessageTool
import com.achulkov.evokammer.agents.tools.SubmitGradingScoreTool
import com.achulkov.evokammer.agents.tools.WriteDeathNoteTool
import com.achulkov.evokammer.agents.tools.WriteStagingTool
import com.achulkov.evokammer.core.budget.BudgetConfig
import com.achulkov.evokammer.core.budget.BudgetLedger
import com.achulkov.evokammer.core.model.DimensionScore
import com.achulkov.evokammer.core.model.Role
import com.achulkov.evokammer.core.model.ScoringDimension
import com.achulkov.evokammer.substrate.db.EvoKammerDatabase
import io.github.aakira.napier.Napier
import java.util.UUID

/**
 * Creates Koog [AIAgent] instances for Grader Group roles.
 *
 * Grader agents differ from standard civilisational agents in three ways:
 * 1. They receive a **canon snapshot** of the competing group's KB/KG as part of their system prompt.
 * 2. They have the **[SubmitGradingScoreTool]** instead of ClaimTaskTool/ExecuteToolTool.
 * 3. They write to their **own grader group substrate DB** (not the competing group's DB).
 *
 * Roles supported: [Role.GraderResearcher], [Role.GraderBuilder], [Role.GraderCritic].
 *
 * @param openRouterApiKey OpenRouter API key.
 * @param graderDb The grader group's own persistent substrate DB.
 * @param graderBudgetConfig Budget configuration for grader agents.
 * @param graderGroupId The grader group's ID (used in tool registrations).
 * @param onScoreSubmitted Callback for each score submitted via [SubmitGradingScoreTool].
 *   Signature: (targetGroupId: String, score: DimensionScore) → Unit
 */
class GraderAgentFactory(
    private val openRouterApiKey: String,
    private val graderDb: EvoKammerDatabase,
    private val graderBudgetConfig: BudgetConfig,
    private val graderGroupId: String,
    private val onScoreSubmitted: (targetGroupId: String, score: DimensionScore) -> Unit
) {
    private val executor by lazy {
        MultiLLMPromptExecutor(LLMProvider.OpenRouter to OpenRouterLLMClient(openRouterApiKey))
    }

    /**
     * Creates a Grader agent for the specified role.
     *
     * @param role The grader role ([Role.GraderResearcher], [Role.GraderBuilder], or [Role.GraderCritic]).
     * @param runId The current run ID.
     * @param roundIndex The round being evaluated.
     * @param targetGroupId The ID of the competing group being evaluated.
     * @param runObjective The run's objective (used in system prompt).
     * @param scoringDimensions All scoring dimensions for this run.
     * @param canonSnapshot Structured text export of the competing group's KB/KG.
     * @param modelOverride Optional model override; falls back to [ModelSelectionConfig.DEFAULT_GRADER_MODEL].
     * @param deathNoteProtocol Optional [DeathNoteProtocol] for in-process agent runs.
     * @param previousFindings Optional summary from a prior grader agent in this round.
     * @return A [GraderAgentContext] ready to run.
     */
    fun create(
        role: Role,
        runId: String,
        roundIndex: Int,
        targetGroupId: String,
        runObjective: String,
        scoringDimensions: List<ScoringDimension>,
        canonSnapshot: String,
        modelOverride: String? = null,
        deathNoteProtocol: DeathNoteProtocol? = null,
        previousFindings: String? = null,
        foundingBrief: String? = null
    ): GraderAgentContext {
        require(role.isGraderRole) { "GraderAgentFactory: role must be a grader role, got $role" }

        val agentId = "grader-${role.name.lowercase()}-${UUID.randomUUID().toString().take(8)}"
        val generation = roundIndex + 1  // Grader generation mirrors the round index

        val initialBudget = graderBudgetConfig.profile.spawnBudget
        val floor = graderBudgetConfig.floorFor(initialBudget)
        val budgetLedger = BudgetLedger(
            agentId = agentId,
            initialBudget = initialBudget,
            floor = floor,
            config = graderBudgetConfig
        )

        val dimensionDescriptions = scoringDimensions.joinToString("\n") { dim ->
            "  • [${dim.id}] ${dim.name} (weight=${
                String.format(java.util.Locale.ROOT, "%.2f", dim.weight)
            }): ${dim.description}"
        }

        val systemPrompt = RolePromptBuilder.buildGrader(
            graderRole = role.name,
            runObjective = runObjective,
            dimensionDescriptions = dimensionDescriptions,
            canonSnapshot = canonSnapshot,
            targetGroupId = targetGroupId,
            graderGroupId = graderGroupId,
            roundIndex = roundIndex,
            previousGraderFindings = previousFindings,
            foundingBrief = foundingBrief
        )

        val toolRegistry = ToolRegistry {
            // Read-only substrate tools pointing at the grader's own KB
            tool(ReadKBTool(graderDb.knowledgeBaseDao(), runId, graderGroupId))
            tool(ReadKGTool(graderDb.knowledgeGraphFactDao(), runId, graderGroupId))

            // Write tools for the grader's own substrate (methodology documentation)
            tool(
                WriteStagingTool(
                    stagingDao = graderDb.stagingDao(),
                    budgetLedger = budgetLedger,
                    runId = runId,
                    groupId = graderGroupId,
                    agentId = agentId,
                    role = role.name,
                    generation = generation,
                    defaultWipTaskRef = null,
                    isOutlier = false
                )
            )
            tool(
                EmitFactTool(
                    stagingDao = graderDb.stagingDao(),
                    budgetLedger = budgetLedger,
                    runId = runId,
                    groupId = graderGroupId,
                    agentId = agentId,
                    role = role.name,
                    generation = generation,
                    defaultWipTaskRef = null
                )
            )
            tool(
                SendMessageTool(
                    budgetLedger = budgetLedger,
                    eventLogDao = graderDb.eventLogDao(),
                    runId = runId,
                    groupId = graderGroupId,
                    agentId = agentId,
                    generation = generation
                )
            )
            tool(
                WriteDeathNoteTool(
                    stagingDao = graderDb.stagingDao(),
                    runId = runId,
                    groupId = graderGroupId,
                    agentId = agentId,
                    role = role.name,
                    generation = generation,
                    onWritten = deathNoteProtocol?.let { protocol -> { protocol.markDeathNoteWritten() } }
                )
            )

            // The primary grader output tool
            tool(
                SubmitGradingScoreTool(
                    budgetLedger = budgetLedger,
                    graderGroupId = graderGroupId,
                    onScoreSubmitted = onScoreSubmitted
                )
            )
        }

        val model = resolveModel(modelOverride ?: ModelSelectionConfig.DEFAULT_GRADER_MODEL)

        // Use agentConfig with KotlinxSerializer so tool Args deserialization works correctly.
        // ignoreUnknownKeys=true prevents failures when LLMs pass extra fields.
        val agent: AIAgent<String, String> = AIAgent.builder()
            .promptExecutor(executor)
            .agentConfig(
                AIAgentConfig(
                    prompt = koogPrompt("grader-agent") { system(systemPrompt) },
                    model = model,
                    maxAgentIterations = 20,
                    responseProcessor = EmptyAssistantResponseRecoveryProcessor,
                    serializer = KotlinxSerializer(kotlinx.serialization.json.Json {
                        ignoreUnknownKeys = true
                        isLenient = true
                    })
                )
            )
            .toolRegistry(toolRegistry)
            .graphStrategy(singleRunStrategy())
            .maxIterations(20)  // Grader agents have simpler task; 20 is enough
            .install(EventHandler.Feature) { config ->
                config.onToolValidationFailed { ctx ->
                    Napier.w("[Grader[$agentId]] aborting agent due to blocking runtime error: tool validation failed: ${ctx.message}")
                    throw com.achulkov.evokammer.core.contract.BlockingAgentAbortException("[Grader[$agentId]] tool validation failed: ${ctx.message}")
                }
                config.onToolCallFailed { ctx ->
                    Napier.w("[Grader[$agentId]] aborting agent due to blocking runtime error: tool call failed: ${ctx.message}")
                    throw com.achulkov.evokammer.core.contract.BlockingAgentAbortException("[Grader[$agentId]] tool call failed: ${ctx.message}")
                }
            }
            .build()

        Napier.d(
            "GraderAgentFactory: created $agentId " +
            "(role=${role.name} targetGroup=$targetGroupId round=$roundIndex " +
            "model=${modelOverride ?: ModelSelectionConfig.DEFAULT_GRADER_MODEL})"
        )

        return GraderAgentContext(
            agentId = agentId,
            role = role,
            agent = agent,
            budgetLedger = budgetLedger,
            targetGroupId = targetGroupId,
            roundIndex = roundIndex,
            systemPrompt = systemPrompt,
            taskPrompt = "Evaluate the competing group '$targetGroupId' " +
                "based on the Canon Snapshot in your system prompt. " +
                "Submit grading scores using submit_grading_score for each dimension."
        )
    }

    private fun resolveModel(modelId: String): LLModel = OpenRouterModelResolver.resolve(modelId)

    /**
     * Creates a grader agent for the **criteria development phase** (runs DURING the round).
     *
     * Criteria agents study the run objective and scoring dimensions to develop detailed,
     * measurable rubric guidance. They do NOT evaluate any specific competing group and do
     * NOT have the [SubmitGradingScoreTool]. Instead they write their criteria to the grader
     * group's staging area via [WriteStagingTool].
     *
     * @param role Must be [Role.GraderResearcher] or [Role.GraderBuilder].
     * @param runId The current run ID.
     * @param roundIndex The round for which criteria are being developed.
     * @param runObjective The run's original objective.
     * @param scoringDimensions All scoring dimensions to develop criteria for.
     * @param modelOverride Optional model override.
     * @param deathNoteProtocol Optional [DeathNoteProtocol] for in-process agent runs.
     * @param previousCriteria Optional output from a prior criteria agent (pass GraderResearcher
     *   output to GraderBuilder so it can refine the criteria).
     */
    fun createCriteria(
        role: Role,
        runId: String,
        roundIndex: Int,
        runObjective: String,
        scoringDimensions: List<ScoringDimension>,
        modelOverride: String? = null,
        deathNoteProtocol: DeathNoteProtocol? = null,
        previousCriteria: String? = null,
        foundingBrief: String? = null
    ): GraderAgentContext {
        require(role == Role.GraderResearcher || role == Role.GraderBuilder) {
            "createCriteria: expected GraderResearcher or GraderBuilder, got $role"
        }

        val agentId = "criteria-${role.name.lowercase()}-${UUID.randomUUID().toString().take(8)}"
        val generation = roundIndex + 1

        val initialBudget = graderBudgetConfig.profile.spawnBudget
        val floor = graderBudgetConfig.floorFor(initialBudget)
        val budgetLedger = BudgetLedger(
            agentId = agentId,
            initialBudget = initialBudget,
            floor = floor,
            config = graderBudgetConfig
        )

        val dimensionDescriptions = scoringDimensions.joinToString("\n") { dim ->
            "  • [${dim.id}] ${dim.name} (weight=${
                String.format(java.util.Locale.ROOT, "%.2f", dim.weight)
            }): ${dim.description}"
        }

        val systemPrompt = RolePromptBuilder.buildGraderCriteria(
            graderRole = role.name,
            runObjective = runObjective,
            dimensionDescriptions = dimensionDescriptions,
            roundIndex = roundIndex,
            previousCriteria = previousCriteria,
            foundingBrief = foundingBrief
        )

        val toolRegistry = ToolRegistry {
            tool(ReadKBTool(graderDb.knowledgeBaseDao(), runId, graderGroupId))
            tool(ReadKGTool(graderDb.knowledgeGraphFactDao(), runId, graderGroupId))
            tool(
                ReadRunArtifactTool(
                    artifactsMountPath = null,
                    inputArtifacts = emptyList()
                )
            )
            tool(
                WriteStagingTool(
                    stagingDao = graderDb.stagingDao(),
                    budgetLedger = budgetLedger,
                    runId = runId,
                    groupId = graderGroupId,
                    agentId = agentId,
                    role = role.name,
                    generation = generation,
                    defaultWipTaskRef = null,
                    isOutlier = false
                )
            )
            tool(
                WriteDeathNoteTool(
                    stagingDao = graderDb.stagingDao(),
                    runId = runId,
                    groupId = graderGroupId,
                    agentId = agentId,
                    role = role.name,
                    generation = generation,
                    onWritten = deathNoteProtocol?.let { protocol -> { protocol.markDeathNoteWritten() } }
                )
            )
        }

        val model = resolveModel(modelOverride ?: ModelSelectionConfig.DEFAULT_GRADER_MODEL)
        val agent: AIAgent<String, String> = AIAgent.builder()
            .promptExecutor(executor)
            .agentConfig(
                AIAgentConfig(
                    prompt = koogPrompt("criteria-agent") { system(systemPrompt) },
                    model = model,
                    maxAgentIterations = 15,
                    responseProcessor = EmptyAssistantResponseRecoveryProcessor,
                    serializer = KotlinxSerializer(kotlinx.serialization.json.Json {
                        ignoreUnknownKeys = true
                        isLenient = true
                    })
                )
            )
            .toolRegistry(toolRegistry)
            .graphStrategy(singleRunStrategy())
            .maxIterations(15)
            .build()

        Napier.d(
            "GraderAgentFactory: created criteria agent $agentId " +
            "(role=${role.name} round=$roundIndex model=${modelOverride ?: ModelSelectionConfig.DEFAULT_GRADER_MODEL})"
        )

        return GraderAgentContext(
            agentId = agentId,
            role = role,
            agent = agent,
            budgetLedger = budgetLedger,
            targetGroupId = "criteria-phase",
            roundIndex = roundIndex,
            systemPrompt = systemPrompt,
            taskPrompt = "Develop detailed scoring criteria for all dimensions as described in your system prompt. " +
                "Write each dimension's criteria to staging using write_staging(type='PLAN_UPDATE', content=...)."
        )
    }

    /**
     * Creates a **RoundJudge** agent for the post-round judging phase.
     *
     * The RoundJudge receives:
     * - Scoring criteria developed by criteria-phase agents (from the grader group's staging DB)
     * - Canon snapshots from ALL competing groups (concatenated)
     *
     * It calls [SubmitGradingScoreTool] for each (group, dimension) pair, producing the
     * authoritative round scores.
     *
     * @param runId The current run ID.
     * @param roundIndex The round being judged.
     * @param runObjective The run's original objective.
     * @param scoringDimensions All scoring dimensions to score.
     * @param criteriaContext Concatenated criteria from the criteria phase (from grader staging DB).
     * @param allGroupSnapshots Map of groupId → canon snapshot text for all competing groups.
     * @param modelOverride Optional model override.
     * @param deathNoteProtocol Optional [DeathNoteProtocol].
     */
    fun createRoundJudge(
        runId: String,
        roundIndex: Int,
        runObjective: String,
        scoringDimensions: List<ScoringDimension>,
        criteriaContext: String,
        allGroupSnapshots: Map<String, String>,
        modelOverride: String? = null,
        deathNoteProtocol: DeathNoteProtocol? = null,
        foundingBrief: String? = null
    ): GraderAgentContext {
        val agentId = "round-judge-${UUID.randomUUID().toString().take(8)}"
        val generation = roundIndex + 1

        val initialBudget = graderBudgetConfig.profile.spawnBudget * 1.5  // Judges get extra budget
        val floor = graderBudgetConfig.floorFor(initialBudget)
        val budgetLedger = BudgetLedger(
            agentId = agentId,
            initialBudget = initialBudget,
            floor = floor,
            config = graderBudgetConfig
        )

        val dimensionDescriptions = scoringDimensions.joinToString("\n") { dim ->
            "  • [${dim.id}] ${dim.name} (weight=${
                String.format(java.util.Locale.ROOT, "%.2f", dim.weight)
            }): ${dim.description}"
        }

        val systemPrompt = RolePromptBuilder.buildRoundJudge(
            runObjective = runObjective,
            dimensionDescriptions = dimensionDescriptions,
            criteriaContext = criteriaContext,
            allGroupSnapshots = allGroupSnapshots,
            roundIndex = roundIndex,
            foundingBrief = foundingBrief
        )

        val toolRegistry = ToolRegistry {
            tool(ReadKBTool(graderDb.knowledgeBaseDao(), runId, graderGroupId))
            tool(ReadKGTool(graderDb.knowledgeGraphFactDao(), runId, graderGroupId))
            tool(
                ReadRunArtifactTool(
                    artifactsMountPath = null,
                    inputArtifacts = emptyList()
                )
            )
            tool(
                WriteDeathNoteTool(
                    stagingDao = graderDb.stagingDao(),
                    runId = runId,
                    groupId = graderGroupId,
                    agentId = agentId,
                    role = "RoundJudge",
                    generation = generation,
                    onWritten = deathNoteProtocol?.let { protocol -> { protocol.markDeathNoteWritten() } }
                )
            )
            tool(
                SubmitGradingScoreTool(
                    budgetLedger = budgetLedger,
                    graderGroupId = graderGroupId,
                    onScoreSubmitted = onScoreSubmitted
                )
            )
        }

        val model = resolveModel(modelOverride ?: ModelSelectionConfig.DEFAULT_GRADER_MODEL)
        val agent: AIAgent<String, String> = AIAgent.builder()
            .promptExecutor(executor)
            .agentConfig(
                AIAgentConfig(
                    prompt = koogPrompt("round-judge") { system(systemPrompt) },
                    model = model,
                    maxAgentIterations = 30,
                    responseProcessor = EmptyAssistantResponseRecoveryProcessor,
                    serializer = KotlinxSerializer(kotlinx.serialization.json.Json {
                        ignoreUnknownKeys = true
                        isLenient = true
                    })
                )
            )
            .toolRegistry(toolRegistry)
            .graphStrategy(singleRunStrategy())
            .maxIterations(30)  // RoundJudge scores N groups × M dimensions — needs more iterations
            .build()

        Napier.d(
            "GraderAgentFactory: created RoundJudge $agentId " +
            "(round=$roundIndex groups=${allGroupSnapshots.keys} model=${modelOverride ?: ModelSelectionConfig.DEFAULT_GRADER_MODEL})"
        )

        val groupList = allGroupSnapshots.keys.joinToString(", ")
        val targetGroupRef = allGroupSnapshots.keys.singleOrNull() ?: "all-groups"
        return GraderAgentContext(
            agentId = agentId,
            role = Role.GraderCritic,  // RoundJudge uses the GraderCritic slot
            agent = agent,
            budgetLedger = budgetLedger,
            targetGroupId = targetGroupRef,
            roundIndex = roundIndex,
            systemPrompt = systemPrompt,
            taskPrompt = "Score all competing groups using the criteria and canon snapshots in your system prompt. " +
                "Call submit_grading_score for every group-dimension combination. Groups: $groupList"
        )
    }

}

/**
 * Container for a created Grader agent and its associated context.
 *
 * @param agentId Unique agent identifier.
 * @param role The grader role.
 * @param agent The Koog [AIAgent] instance.
 * @param budgetLedger The agent's budget ledger.
 * @param targetGroupId The competing group this agent is evaluating.
 * @param roundIndex The round being evaluated.
 * @param systemPrompt The rendered system prompt injected into the Koog agent.
 * @param taskPrompt The task prompt passed to [AIAgent.run] when this agent is executed.
 */
data class GraderAgentContext(
    val agentId: String,
    val role: Role,
    val agent: AIAgent<String, String>,
    val budgetLedger: BudgetLedger,
    val targetGroupId: String,
    val roundIndex: Int,
    val systemPrompt: String,
    /**
     * The task prompt passed to [AIAgent.run] when this agent is executed.
     * Defaults to a generic grader instruction if not set by the factory.
     */
    val taskPrompt: String = "Perform your evaluation task as described in your system prompt."
) {
    init {
        require(systemPrompt.isNotBlank()) { "GraderAgentContext.systemPrompt must not be blank" }
    }
}
