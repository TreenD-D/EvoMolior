@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.agents.factory

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.singleRunStrategy
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.features.eventHandler.feature.EventHandler
import ai.koog.prompt.dsl.prompt as koogPrompt
import ai.koog.prompt.llm.LLModel
import ai.koog.serialization.kotlinx.KotlinxSerializer
import com.achulkov.evomolior.core.config.LlmBackend
import com.achulkov.evomolior.core.config.CodexReasoningEffort
import com.achulkov.evomolior.core.config.ModelSelectionConfig
import com.achulkov.evomolior.agents.runner.DeathNoteProtocol
import com.achulkov.evomolior.agents.tools.EmitFactTool
import com.achulkov.evomolior.agents.tools.ExecuteToolTool
import com.achulkov.evomolior.agents.tools.ListKBTool
import com.achulkov.evomolior.agents.tools.ListToolsTool
import com.achulkov.evomolior.agents.tools.ReadKBTool
import com.achulkov.evomolior.agents.tools.ReadKGTool
import com.achulkov.evomolior.agents.tools.ReadMessagesTool
import com.achulkov.evomolior.agents.tools.ReadRunArtifactTool
import com.achulkov.evomolior.agents.tools.PublishGradingSheetTool
import com.achulkov.evomolior.agents.tools.GraderMethodologyPublicationService
import com.achulkov.evomolior.agents.tools.SendMessageTool
import com.achulkov.evomolior.agents.tools.SubmitGradingScoreTool
import com.achulkov.evomolior.agents.tools.SubmitNodeGradeTool
import com.achulkov.evomolior.agents.tools.NodeGradeSubmissionGuard
import com.achulkov.evomolior.agents.tools.SubmitNoveltyAssessmentTool
import com.achulkov.evomolior.agents.tools.WriteDeathNoteTool
import com.achulkov.evomolior.agents.tools.WriteStagingTool
import com.achulkov.evomolior.core.budget.BudgetConfig
import com.achulkov.evomolior.core.budget.BudgetLedger
import com.achulkov.evomolior.core.contract.GraderMethodologyBindingCatalog
import com.achulkov.evomolior.core.model.DimensionScore
import com.achulkov.evomolior.core.model.Role
import com.achulkov.evomolior.core.model.ScoringDimension
import com.achulkov.evomolior.core.atlas.NodeGraderResponse
import com.achulkov.evomolior.core.atlas.ExactNodeEvaluationContext
import com.achulkov.evomolior.core.atlas.NodeAtlas
import com.achulkov.evomolior.core.atlas.NoveltyGraderAssessment
import com.achulkov.evomolior.core.atlas.NoveltyVectorProposal
import com.achulkov.evomolior.core.grader.GraderAtlas
import com.achulkov.evomolior.core.grader.GraderView
import com.achulkov.evomolior.substrate.db.EvoMoliorDatabase
import com.achulkov.evomolior.substrate.cost.CostLedgerRepository
import com.achulkov.evomolior.sandbox.enforcer.ActionEventType
import com.achulkov.evomolior.sandbox.enforcer.AgentAction
import com.achulkov.evomolior.sandbox.enforcer.ConstitutionalEnforcer
import com.achulkov.evomolior.sandbox.enforcer.ConstitutionalActionGate
import com.achulkov.evomolior.sandbox.toolsandbox.ToolSubSandbox
import io.github.aakira.napier.Napier
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** Execution target shared by interactive and typed grader methodology authorship. */
const val GRADER_AUTHORED_TEST_TARGET_CONTRACT =
    "AUTOMATED TEST EXECUTION CONTRACT: expectedOutputPattern is an RE2 search over the " +
        "ROUND_CANON_SNAPSHOT: a bounded, per-entry-truncated substrate digest, not a complete " +
        "document or final publication candidate. Tests execute after ordinary worker quiescence, " +
        "before final synthesis. Author only predicates meaningful for that exact target and timing. " +
        "Final document heading order, word limits, and whole-document citation/quotation fidelity " +
        "belong to final-candidate validation; do not misrepresent them as snapshot regex checks. " +
        "Keep those obligations explicit in requirements and rubric guidance. Do not claim a regex " +
        "checks more than its predicate proves. Mandatory authored failures remain fail-closed."

/** The host, not a model transport, owns automated score adjustments. */
const val GRADER_RAW_SCORE_CONTRACT =
    "Submit raw quality scores before automated-test gates or penalties. " +
        "The host applies the frozen authored-test policy exactly once after collecting your scores."

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
 * @param openRouterApiKey API key for the selected [llmBackend].
 * @param llmBackend Backend used for model resolution and prompt execution.
 * @param graderDb The grader group's own persistent substrate DB.
 * @param graderBudgetConfig Budget configuration for grader agents.
 * @param graderGroupId The grader group's ID (used in tool registrations).
 * @param toolsMountPath Host path containing grader-local materialized executables.
 * @param onScoreSubmitted Callback for each score submitted via [SubmitGradingScoreTool].
 *   Signature: (targetGroupId: String, score: DimensionScore) → Unit
 */
class GraderAgentFactory(
    private val openRouterApiKey: String,
    private val llmBackend: LlmBackend = LlmBackend.OPENROUTER,
    private val codexReasoningEffort: CodexReasoningEffort = CodexReasoningEffort.HIGH,
    private val graderDb: EvoMoliorDatabase,
    private val graderBudgetConfig: BudgetConfig,
    private val graderGroupId: String,
    private val toolsMountPath: String = File(
        System.getProperty("java.io.tmpdir"),
        "evomolior-grader-tools/$graderGroupId"
    ).absolutePath,
    private val onScoreSubmitted: (targetGroupId: String, score: DimensionScore) -> Unit,
    private val onNodeGradeSubmitted: (NodeGraderResponse) -> Unit = {},
    private val constitutionalEnforcer: ConstitutionalEnforcer = ConstitutionalEnforcer(),
    private val costLedgerRepository: CostLedgerRepository = CostLedgerRepository(graderDb),
    qwenToolSupportEnabled: Boolean = LlmBackendToolSupportPolicy.isQwenToolSupportEnabledByEnvironment()
) {
    private val backendToolSupport = LlmBackendToolSupportPolicy.resolve(
        backend = llmBackend,
        qwenToolSupportEnabled = qwenToolSupportEnabled
    )
    private val nodeGradeSubmissionGuard = NodeGradeSubmissionGuard()

    /**
     * Creates the same constitutional publication boundary used by `publish_grading_sheet`.
     *
     * @param runId Owning run.
     * @param roundIndex Methodology round.
     * @param scoringDimensions Exact allowed scoring dimensions.
     * @param graderAtlas Sealed grader authority.
     * @param graderView Group-scoped leased frontier.
     * @param workerAtlas Sealed worker Atlas governed by [graderAtlas].
     * @param wipTaskRef Claimed grader task.
     * @param agentId Model author whose identity is persisted with the draft.
     */
    fun createMethodologyPublicationService(
        runId: String,
        roundIndex: Int,
        scoringDimensions: List<ScoringDimension>,
        graderAtlas: GraderAtlas,
        graderView: GraderView,
        workerAtlas: NodeAtlas,
        wipTaskRef: String?,
        agentId: String
    ): GraderMethodologyPublicationService {
        val initialBudget = graderBudgetConfig.profile.spawnBudget
        val budgetLedger = BudgetLedger(
            agentId = agentId,
            initialBudget = initialBudget,
            floor = graderBudgetConfig.floorFor(initialBudget),
            config = graderBudgetConfig
        )
        return GraderMethodologyPublicationService(
            stagingDao = graderDb.stagingDao(),
            budgetLedger = budgetLedger,
            runId = runId,
            graderGroupId = graderGroupId,
            agentId = agentId,
            generation = roundIndex + 1,
            allowedDimensionIds = scoringDimensions.mapTo(linkedSetOf()) { it.id },
            graderAtlas = graderAtlas,
            graderView = graderView,
            workerAtlas = workerAtlas,
            defaultWipTaskRef = wipTaskRef,
            actionGate = databaseConstitutionalActionGate(
                constitutionalEnforcer,
                graderDb,
                runId,
                graderGroupId,
                agentId,
                roundIndex + 1
            )
        )
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
        val actionGate = databaseConstitutionalActionGate(
            constitutionalEnforcer,
            graderDb,
            runId,
            graderGroupId,
            agentId,
            generation
        )

        val dimensionDescriptions = scoringDimensions.joinToString("\n") { dim ->
            "  • [${dim.id}] ${dim.name} (weight=${
                String.format(java.util.Locale.ROOT, "%.2f", dim.weight)
            }): ${dim.description}"
        }

        val systemPrompt = buildBackendAdjustedSystemPrompt(RolePromptBuilder.buildGrader(
            graderRole = role.name,
            runObjective = runObjective,
            dimensionDescriptions = dimensionDescriptions,
            canonSnapshot = canonSnapshot,
            targetGroupId = targetGroupId,
            graderGroupId = graderGroupId,
            roundIndex = roundIndex,
            previousGraderFindings = previousFindings,
            foundingBrief = foundingBrief
        ))

        val toolRegistry = if (usesTextOnlyCompatibilityMode()) ToolRegistry.EMPTY else ToolRegistry {
            // Read-only substrate tools pointing at the grader's own KB
            tool(ListKBTool(graderDb.knowledgeBaseDao(), runId, graderGroupId))
            tool(ReadKBTool(graderDb.knowledgeBaseDao(), runId, graderGroupId))
            tool(ReadKGTool(graderDb.knowledgeGraphFactDao(), graderDb.kgSchemaDao(), runId, graderGroupId))
            tool(ListToolsTool(graderDb.toolRegistryDao(), runId, graderGroupId))
            tool(
                ExecuteToolTool(
                    toolRegistryDao = graderDb.toolRegistryDao(),
                    toolSubSandbox = graderToolSubSandbox(),
                    budgetLedger = budgetLedger,
                    runId = runId,
                    groupId = graderGroupId,
                    toolsMountPath = toolsMountPath,
                    agentId = agentId,
                    actionGate = actionGate,
                    costLedgerRepository = costLedgerRepository
                )
            )

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
                    isOutlier = false,
                    actionGate = actionGate
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
                    defaultWipTaskRef = null,
                    actionGate = actionGate,
                    kgSchemaDao = graderDb.kgSchemaDao()
                )
            )
            tool(
                SendMessageTool(
                    budgetLedger = budgetLedger,
                    eventLogDao = graderDb.eventLogDao(),
                    runId = runId,
                    groupId = graderGroupId,
                    agentId = agentId,
                    generation = generation,
                    actionGate = actionGate
                )
            )
            tool(
                ReadMessagesTool(
                    eventLogDao = graderDb.eventLogDao(),
                    runId = runId,
                    groupId = graderGroupId,
                    agentId = agentId
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
                    onWritten = deathNoteProtocol?.let { protocol -> { protocol.markDeathNoteWritten() } },
                    actionGate = actionGate
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
        val executor = LlmExecutorFactory.create(
            backend = llmBackend,
            apiKey = openRouterApiKey,
            codexToolRegistry = toolRegistry,
            codexActionGate = actionGate,
            codexAgentId = agentId,
            codexReasoningEffort = codexReasoningEffort
        )

        // Use agentConfig with KotlinxSerializer so tool Args deserialization works correctly.
        // ignoreUnknownKeys=true prevents failures when LLMs pass extra fields.
        val costRecorder = providerCostRecorder(runId, agentId)
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
                config.installProviderCostRecorder(costRecorder)
                config.onToolCallStarting { ctx ->
                    actionGate.intercept(
                        AgentAction(ActionEventType.TOOL_CALL, agentId, ctx.toolName)
                    )
                }
                config.onToolValidationFailed { ctx ->
                    Napier.w("[Grader[$agentId]] aborting agent due to blocking runtime error: tool validation failed: ${ctx.message}")
                    throw com.achulkov.evomolior.core.contract.BlockingAgentAbortException("[Grader[$agentId]] tool validation failed: ${ctx.message}")
                }
                config.onToolCallFailed { ctx ->
                    Napier.w("[Grader[$agentId]] aborting agent due to blocking runtime error: tool call failed: ${ctx.message}")
                    throw com.achulkov.evomolior.core.contract.BlockingAgentAbortException("[Grader[$agentId]] tool call failed: ${ctx.message}")
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
            textOnlyCompatibilityMode = usesTextOnlyCompatibilityMode(),
            taskPrompt = "Evaluate the competing group '$targetGroupId' " +
                "based on the Canon Snapshot in your system prompt. " +
                "Submit grading scores using submit_grading_score for each dimension.",
            providerCostRecorder = costRecorder
        )
    }

    private fun resolveModel(modelId: String): LLModel = LlmExecutorFactory.resolveModel(llmBackend, modelId)

    private fun graderToolSubSandbox(): ToolSubSandbox =
        ToolSubSandbox(workspaceDir = File(toolsMountPath).parentFile.resolve("tool-workspace"))

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
     * @param wipTaskRef Grader-local WiP task claimed by this agent.
     * @param wipTaskDescription Human-readable grader-local task description.
     * @param legacyContext Active grader Legacy Protocol context for this role.
     * @param graderAtlas Sealed competitive grader frontier.
     * @param graderView Redacted leased frontier visible to this grader civilisation.
     * @param workerAtlas Sealed worker Atlas supplying node-local evaluator context.
     * @param baselineCriteria Previous round's accepted criteria; when present the agent revises
     *   that baseline instead of authoring criteria from scratch.
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
        foundingBrief: String? = null,
        wipTaskRef: String? = null,
        wipTaskDescription: String? = null,
        legacyContext: String? = null,
        graderAtlas: GraderAtlas? = null,
        graderView: GraderView? = null,
        workerAtlas: NodeAtlas? = null,
        baselineCriteria: String? = null
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
        val actionGate = databaseConstitutionalActionGate(
            constitutionalEnforcer,
            graderDb,
            runId,
            graderGroupId,
            agentId,
            generation
        )

        val dimensionDescriptions = scoringDimensions.joinToString("\n") { dim ->
            "  • [${dim.id}] ${dim.name} (weight=${
                String.format(java.util.Locale.ROOT, "%.2f", dim.weight)
            }): ${dim.description}"
        }
        val methodologyBindingCatalog = graderAtlas?.let {
            GraderMethodologyBindingCatalog.from(
                requireNotNull(graderView) {
                    "Compiled Grader Atlas criteria require a leased GraderView"
                },
                requireNotNull(workerAtlas) {
                    "Compiled Grader Atlas criteria require its sealed worker Atlas"
                }
            )
        }

        val systemPrompt = buildBackendAdjustedSystemPrompt(RolePromptBuilder.buildGraderCriteria(
            graderRole = role.name,
            runObjective = runObjective,
            dimensionDescriptions = dimensionDescriptions,
            roundIndex = roundIndex,
            previousCriteria = previousCriteria,
            foundingBrief = foundingBrief,
            wipTaskDescription = wipTaskDescription,
            legacyContext = legacyContext,
            graderAtlasContext = methodologyBindingCatalog?.modelView?.let {
                Json.encodeToString(it)
            },
            baselineCriteria = baselineCriteria
        ) + "\n\n" + GRADER_AUTHORED_TEST_TARGET_CONTRACT)

        val toolRegistry = if (usesTextOnlyCompatibilityMode()) ToolRegistry.EMPTY else ToolRegistry {
            tool(ListKBTool(graderDb.knowledgeBaseDao(), runId, graderGroupId))
            tool(ReadKBTool(graderDb.knowledgeBaseDao(), runId, graderGroupId))
            tool(ReadKGTool(graderDb.knowledgeGraphFactDao(), graderDb.kgSchemaDao(), runId, graderGroupId))
            tool(ListToolsTool(graderDb.toolRegistryDao(), runId, graderGroupId))
            tool(
                ExecuteToolTool(
                    toolRegistryDao = graderDb.toolRegistryDao(),
                    toolSubSandbox = graderToolSubSandbox(),
                    budgetLedger = budgetLedger,
                    runId = runId,
                    groupId = graderGroupId,
                    toolsMountPath = toolsMountPath,
                    agentId = agentId,
                    actionGate = actionGate,
                    costLedgerRepository = costLedgerRepository
                )
            )
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
                    defaultWipTaskRef = wipTaskRef,
                    isOutlier = false,
                    actionGate = actionGate
                )
            )
            if (role == Role.GraderBuilder) {
                tool(
                    PublishGradingSheetTool(
                        stagingDao = graderDb.stagingDao(),
                        budgetLedger = budgetLedger,
                        runId = runId,
                        graderGroupId = graderGroupId,
                        agentId = agentId,
                        generation = generation,
                        allowedDimensionIds = scoringDimensions.mapTo(linkedSetOf()) { it.id },
                        graderAtlas = graderAtlas,
                        graderView = graderView,
                        workerAtlas = workerAtlas,
                        defaultWipTaskRef = wipTaskRef,
                        actionGate = actionGate
                    )
                )
            }
            tool(
                WriteDeathNoteTool(
                    stagingDao = graderDb.stagingDao(),
                    runId = runId,
                    groupId = graderGroupId,
                    agentId = agentId,
                    role = role.name,
                    generation = generation,
                    onWritten = deathNoteProtocol?.let { protocol -> { protocol.markDeathNoteWritten() } },
                    actionGate = actionGate
                )
            )
        }

        val model = resolveModel(modelOverride ?: ModelSelectionConfig.DEFAULT_GRADER_MODEL)
        val executor = LlmExecutorFactory.create(
            backend = llmBackend,
            apiKey = openRouterApiKey,
            codexToolRegistry = toolRegistry,
            codexActionGate = actionGate,
            codexAgentId = agentId,
            codexReasoningEffort = codexReasoningEffort
        )
        val costRecorder = providerCostRecorder(runId, agentId)
        val agent: AIAgent<String, String> = AIAgent.builder()
            .promptExecutor(executor)
            .agentConfig(
                AIAgentConfig(
                    prompt = koogPrompt("criteria-agent") { system(systemPrompt) },
                    model = model,
                    maxAgentIterations = 25,
                    responseProcessor = EmptyAssistantResponseRecoveryProcessor,
                    serializer = KotlinxSerializer(kotlinx.serialization.json.Json {
                        ignoreUnknownKeys = true
                        isLenient = true
                    })
                )
            )
            .toolRegistry(toolRegistry)
            .graphStrategy(singleRunStrategy())
            .maxIterations(25)
            .install(EventHandler.Feature) { config ->
                config.installProviderCostRecorder(costRecorder)
                config.onToolCallStarting { ctx ->
                    actionGate.intercept(
                        AgentAction(ActionEventType.TOOL_CALL, agentId, ctx.toolName)
                    )
                }
                config.installBlockingFailurePolicy("GraderCriteria[$agentId]")
            }
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
            providerCostRecorder = costRecorder,
            textOnlyCompatibilityMode = usesTextOnlyCompatibilityMode(),
            taskPrompt = if (usesTextOnlyCompatibilityMode()) {
                "Develop detailed scoring criteria for all dimensions as described in your system prompt. " +
                    "Return the criteria directly as plain text."
            } else {
                if (role == Role.GraderBuilder && graderAtlas != null) {
                    "Develop the exact typed requirements and hidden tests described in your system prompt, " +
                        "then call publish_grading_sheet. Supporting PLAN_UPDATE analysis is optional."
                } else {
                    "Develop detailed scoring criteria for all dimensions as described in your system prompt. " +
                        "Write each dimension's criteria to staging using write_staging(type='PLAN_UPDATE', content=...)."
                }
            }
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
        nodeEvaluationContexts: List<ExactNodeEvaluationContext> = emptyList(),
        modelOverride: String? = null,
        deathNoteProtocol: DeathNoteProtocol? = null,
        foundingBrief: String? = null
    ): GraderAgentContext {
        require(
            nodeEvaluationContexts.all {
                it.graderGroupId == graderGroupId && it.hasValidHash()
            }
        ) {
            "RoundJudge exact node contexts do not match this grader civilisation"
        }
        val nodeGradingAssignments = nodeEvaluationContexts.map {
            it.assignment
        }
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
        val actionGate = databaseConstitutionalActionGate(
            constitutionalEnforcer,
            graderDb,
            runId,
            graderGroupId,
            agentId,
            generation
        )

        val dimensionDescriptions = scoringDimensions.joinToString("\n") { dim ->
            "  • [${dim.id}] ${dim.name} (weight=${
                String.format(java.util.Locale.ROOT, "%.2f", dim.weight)
            }): ${dim.description}"
        }

        val systemPrompt = buildBackendAdjustedSystemPrompt(RolePromptBuilder.buildRoundJudge(
            runObjective = runObjective,
            dimensionDescriptions = dimensionDescriptions,
            criteriaContext = criteriaContext,
            allGroupSnapshots = allGroupSnapshots,
            nodeEvaluationContexts = nodeEvaluationContexts,
            roundIndex = roundIndex,
            foundingBrief = foundingBrief
        ) + "\n\n" + GRADER_RAW_SCORE_CONTRACT)

        val toolRegistry = if (usesTextOnlyCompatibilityMode()) ToolRegistry.EMPTY else ToolRegistry {
            tool(ListKBTool(graderDb.knowledgeBaseDao(), runId, graderGroupId))
            tool(ReadKBTool(graderDb.knowledgeBaseDao(), runId, graderGroupId))
            tool(ListToolsTool(graderDb.toolRegistryDao(), runId, graderGroupId))
            tool(
                ExecuteToolTool(
                    toolRegistryDao = graderDb.toolRegistryDao(),
                    toolSubSandbox = graderToolSubSandbox(),
                    budgetLedger = budgetLedger,
                    runId = runId,
                    groupId = graderGroupId,
                    toolsMountPath = toolsMountPath,
                    agentId = agentId,
                    actionGate = actionGate,
                    costLedgerRepository = costLedgerRepository
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
                    onWritten = deathNoteProtocol?.let { protocol -> { protocol.markDeathNoteWritten() } },
                    actionGate = actionGate
                )
            )
            tool(
                SubmitGradingScoreTool(
                    budgetLedger = budgetLedger,
                    graderGroupId = graderGroupId,
                    onScoreSubmitted = onScoreSubmitted
                )
            )
            if (nodeGradingAssignments.isNotEmpty()) {
                tool(
                    SubmitNodeGradeTool(
                        budgetLedger = budgetLedger,
                        graderId = graderGroupId,
                        assignments = nodeGradingAssignments,
                        submissionGuard = nodeGradeSubmissionGuard,
                        onResponseSubmitted = onNodeGradeSubmitted
                    )
                )
            }
        }

        val model = resolveModel(modelOverride ?: ModelSelectionConfig.DEFAULT_GRADER_MODEL)
        val executor = LlmExecutorFactory.create(
            backend = llmBackend,
            apiKey = openRouterApiKey,
            codexToolRegistry = toolRegistry,
            codexActionGate = actionGate,
            codexAgentId = agentId,
            codexReasoningEffort = codexReasoningEffort
        )
        val costRecorder = providerCostRecorder(runId, agentId)
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
            .install(EventHandler.Feature) { config ->
                config.installProviderCostRecorder(costRecorder)
                config.onToolCallStarting { ctx ->
                    actionGate.intercept(
                        AgentAction(ActionEventType.TOOL_CALL, agentId, ctx.toolName)
                    )
                }
                config.installBlockingFailurePolicy("RoundJudge[$agentId]")
            }
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
            providerCostRecorder = costRecorder,
            budgetLedger = budgetLedger,
            targetGroupId = targetGroupRef,
            roundIndex = roundIndex,
            systemPrompt = systemPrompt,
            textOnlyCompatibilityMode = usesTextOnlyCompatibilityMode(),
            taskPrompt = if (usesTextOnlyCompatibilityMode()) {
                "Score all competing groups using the criteria and canon snapshots in your system prompt. " +
                    "Return only the strict JSON score object specified in the text-only contract, " +
                    "with one score for every group-dimension combination and no narrative or markdown. Groups: $groupList"
            } else {
                "Score all competing groups using the criteria and canon snapshots in your system prompt. " +
                    "Do not request more reads; the complete scoring snapshot is already in the prompt. " +
                    "Call submit_grading_score for every group-dimension combination. " +
                    "If you cannot call the tool, return the strict JSON score object from the fallback contract. " +
                    "Also submit every assigned exact node grade. Groups: $groupList"
            }
        )
    }

    /**
     * Creates an isolated assessor for one redacted novelty proposal.
     *
     * The prompt excludes proposer and worker-group identity, canon snapshots, and hidden-map
     * comparison details. The only write authority is one proposal-bound assessment.
     *
     * @param proposal Exact immutable proposal to assess.
     * @param roundIndex Round in which the proposal was authored.
     * @param runObjective Receiver-facing objective used only for relevance evaluation.
     * @param modelOverride Optional grader model override.
     * @param onAssessmentSubmitted Callback for the exact typed assessment.
     * @return A grader context restricted to novelty assessment.
     */
    fun createNoveltyAssessor(
        proposal: NoveltyVectorProposal,
        roundIndex: Int,
        runObjective: String,
        modelOverride: String? = null,
        onAssessmentSubmitted: (NoveltyGraderAssessment) -> Unit
    ): GraderAgentContext {
        require(proposal.hasValidHash() && proposal.round == roundIndex) {
            "Novelty assessor requires the exact proposal and source round"
        }
        val agentId = "novelty-assessor-${UUID.randomUUID().toString().take(8)}"
        val generation = roundIndex + 1
        val initialBudget = graderBudgetConfig.profile.spawnBudget
        val budgetLedger = BudgetLedger(
            agentId = agentId,
            initialBudget = initialBudget,
            floor = graderBudgetConfig.floorFor(initialBudget),
            config = graderBudgetConfig
        )
        val actionGate = databaseConstitutionalActionGate(
            constitutionalEnforcer,
            graderDb,
            proposal.runId,
            graderGroupId,
            agentId,
            generation
        )
        val systemPrompt = buildBackendAdjustedSystemPrompt(
            RolePromptBuilder.buildNoveltyAssessor(
                runObjective = runObjective,
                proposal = proposal,
                roundIndex = roundIndex
            )
        )
        val toolRegistry = if (usesTextOnlyCompatibilityMode()) ToolRegistry.EMPTY else ToolRegistry {
            tool(
                SubmitNoveltyAssessmentTool(
                    budgetLedger = budgetLedger,
                    proposalHash = proposal.proposalHash,
                    graderGroupId = graderGroupId,
                    onAssessmentSubmitted = onAssessmentSubmitted
                )
            )
        }
        val model = resolveModel(modelOverride ?: ModelSelectionConfig.DEFAULT_GRADER_MODEL)
        val executor = LlmExecutorFactory.create(
            backend = llmBackend,
            apiKey = openRouterApiKey,
            codexToolRegistry = toolRegistry,
            codexActionGate = actionGate,
            codexAgentId = agentId,
            codexReasoningEffort = codexReasoningEffort
        )
        val costRecorder = providerCostRecorder(proposal.runId, agentId)
        val agent: AIAgent<String, String> = AIAgent.builder()
            .promptExecutor(executor)
            .agentConfig(
                AIAgentConfig(
                    prompt = koogPrompt("novelty-assessor") { system(systemPrompt) },
                    model = model,
                    maxAgentIterations = 8,
                    responseProcessor = EmptyAssistantResponseRecoveryProcessor,
                    serializer = KotlinxSerializer(kotlinx.serialization.json.Json {
                        ignoreUnknownKeys = true
                        isLenient = true
                    })
                )
            )
            .toolRegistry(toolRegistry)
            .graphStrategy(singleRunStrategy())
            .maxIterations(8)
            .install(EventHandler.Feature) { config ->
                config.installProviderCostRecorder(costRecorder)
                config.onToolCallStarting { ctx ->
                    actionGate.intercept(
                        AgentAction(ActionEventType.TOOL_CALL, agentId, ctx.toolName)
                    )
                }
                config.installBlockingFailurePolicy("NoveltyAssessor[$agentId]")
            }
            .build()
        return GraderAgentContext(
            agentId = agentId,
            role = Role.GraderCritic,
            agent = agent,
            budgetLedger = budgetLedger,
            targetGroupId = "redacted",
            roundIndex = roundIndex,
            systemPrompt = systemPrompt,
            providerCostRecorder = costRecorder,
            textOnlyCompatibilityMode = usesTextOnlyCompatibilityMode(),
            taskPrompt = if (usesTextOnlyCompatibilityMode()) {
                "Assess the proposal and return only the strict novelty-assessment JSON object."
            } else {
                "Assess the proposal and call submit_novelty_assessment exactly once."
            }
        )
    }

    internal fun usesTextOnlyCompatibilityMode(): Boolean = backendToolSupport.textOnlyCompatibilityMode

    private fun providerCostRecorder(
        runId: String,
        agentId: String
    ): ProviderCallCostRecorder = ProviderCallCostRecorder(
        repository = costLedgerRepository,
        runId = runId,
        groupId = graderGroupId,
        agentId = agentId
    )

    private fun buildBackendAdjustedSystemPrompt(systemPrompt: String): String {
        if (!usesTextOnlyCompatibilityMode()) return systemPrompt
        return buildString {
            appendLine(systemPrompt)
            appendLine()
            appendLine("=== TEXT-ONLY BACKEND COMPATIBILITY MODE ===")
            appendLine("Do not attempt to call tools or emit tool JSON.")
            appendLine("For criteria work, return useful grader work directly as plain text.")
            appendLine("For judging, return ONLY one strict JSON object with this schema:")
            appendLine("""{"scores":[{"targetGroupId":"<group-id>","dimensionId":"<dimension-id>","rawScore":<0-100>,"rationale":"<brief evidence-grounded rationale>"}]}""")
            appendLine("Include every required score exactly once. Do not add prose, markdown, code fences, comments, or additional fields.")
            appendLine("Missing or invalid score objects are not treated as grader-civilisation judgments.")
        }
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
 * @param textOnlyCompatibilityMode Whether direct substantive text is the backend's typed success path.
 * @param taskPrompt The task prompt passed to [AIAgent.run] when this agent is executed.
 * @param providerCostRecorder The same recorder installed in Koog, for cancellation-safe runner cleanup.
 */
data class GraderAgentContext(
    val agentId: String,
    val role: Role,
    val agent: AIAgent<String, String>,
    val budgetLedger: BudgetLedger,
    val targetGroupId: String,
    val roundIndex: Int,
    val systemPrompt: String,
    val textOnlyCompatibilityMode: Boolean = false,
    /**
     * The task prompt passed to [AIAgent.run] when this agent is executed.
     * Defaults to a generic grader instruction if not set by the factory.
     */
    val taskPrompt: String = "Perform your evaluation task as described in your system prompt.",
    val providerCostRecorder: ProviderCallCostRecorder? = null
) {
    init {
        require(systemPrompt.isNotBlank()) { "GraderAgentContext.systemPrompt must not be blank" }
    }
}
