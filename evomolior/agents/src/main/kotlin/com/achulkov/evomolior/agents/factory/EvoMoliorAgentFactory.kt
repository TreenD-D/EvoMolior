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
import com.achulkov.evomolior.agents.delegation.SubAgentCoordinator
import com.achulkov.evomolior.agents.tools.ClaimTaskTool
import com.achulkov.evomolior.agents.tools.CheckConsistencyTool
import com.achulkov.evomolior.agents.tools.CommitContributionTool
import com.achulkov.evomolior.agents.tools.DelegateSubTaskTool
import com.achulkov.evomolior.agents.tools.EmitConstraintTool
import com.achulkov.evomolior.agents.tools.EmitFactTool
import com.achulkov.evomolior.agents.tools.ExecuteToolTool
import com.achulkov.evomolior.agents.tools.FetchSourceTool
import com.achulkov.evomolior.agents.tools.ListToolsTool
import com.achulkov.evomolior.agents.tools.ListKBTool
import com.achulkov.evomolior.agents.tools.QueryPatternTool
import com.achulkov.evomolior.agents.tools.ReadKBTool
import com.achulkov.evomolior.agents.tools.ReadKGTool
import com.achulkov.evomolior.agents.tools.ReadMessagesTool
import com.achulkov.evomolior.agents.tools.ReadRunArtifactTool
import com.achulkov.evomolior.agents.tools.ReportSourceAdvisory
import com.achulkov.evomolior.agents.tools.ReportSourceInputs
import com.achulkov.evomolior.agents.tools.RequestSourceAccessTool
import com.achulkov.evomolior.agents.tools.SearchKBTool
import com.achulkov.evomolior.agents.tools.SendMessageTool
import com.achulkov.evomolior.agents.tools.WriteDeathNoteTool
import com.achulkov.evomolior.agents.tools.WriteStagingTool
import com.achulkov.evomolior.agents.tools.AssignedToolSubmissionAuthority
import com.achulkov.evomolior.agents.tools.ProposeNoveltyVectorTool
import com.achulkov.evomolior.agents.tools.ToolAuthoringRuntime
import com.achulkov.evomolior.agents.tools.ToolAuthoringWorkspaceTool
import com.achulkov.evomolior.sandbox.network.ContentSanitiser
import com.achulkov.evomolior.agents.runner.DeathNoteProtocol
import com.achulkov.evomolior.agents.runner.expectedAgentStopReason
import com.achulkov.evomolior.core.budget.BudgetConfig
import com.achulkov.evomolior.core.budget.BudgetDeduction
import com.achulkov.evomolior.core.budget.BudgetLedger
import com.achulkov.evomolior.core.config.LlmBackend
import com.achulkov.evomolior.core.config.CodexReasoningEffort
import com.achulkov.evomolior.core.config.CodexRuntimePreset
import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.ToolExecutionPurpose
import com.achulkov.evomolior.core.atlas.ToolValidationExecutionAuthority
import com.achulkov.evomolior.core.atlas.ToolVersionId
import com.achulkov.evomolior.core.contract.AgentBootContext
import com.achulkov.evomolior.core.contract.DegradedExecutionRecord
import com.achulkov.evomolior.core.model.ActionType
import com.achulkov.evomolior.core.model.Role
import com.achulkov.evomolior.core.model.SubmissionType
import com.achulkov.evomolior.sandbox.toolsandbox.ToolSubSandbox
import com.achulkov.evomolior.sandbox.enforcer.ActionEventType
import com.achulkov.evomolior.sandbox.enforcer.AgentAction
import com.achulkov.evomolior.sandbox.enforcer.ConstitutionalActionGate
import com.achulkov.evomolior.sandbox.enforcer.ConstitutionalEnforcer
import com.achulkov.evomolior.substrate.tools.ToolInfrastructureRepository
import com.achulkov.evomolior.sandbox.network.AllowlistProxy
import com.achulkov.evomolior.substrate.db.EvoMoliorDatabase
import com.achulkov.evomolior.substrate.db.entity.EventLogEntryEntity
import com.achulkov.evomolior.substrate.db.entity.EventTypes
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import com.achulkov.evomolior.substrate.worker.WorkerExecutionRepository
import com.achulkov.evomolior.substrate.worker.ContributionGraphRepository
import com.achulkov.evomolior.substrate.cost.CostLedgerRepository
import com.achulkov.evomolior.substrate.outlier.OutlierNoveltyRepository
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * Creates Koog [AIAgent] instances for EvoMolior agent roles.
 *
 * The factory wires together:
 * - Koog's prompt executor for the selected LLM backend
 * - Role-specific system prompt (built by [RolePromptBuilder])
 * - All EvoMolior custom tools ([ToolRegistry])
 * - [BudgetLedger] shared by tools for token deduction
 *
 * Koog 1.0.0 builder API is used:
 * ```
 * AIAgent.builder()
 *     .promptExecutor(LlmExecutorFactory.create(backend, apiKey))
 *     .llmModel(model)
 *     .systemPrompt(prompt)
 *     .toolRegistry(toolRegistry)
 *     .graphStrategy(singleRunStrategy())
 *     .build()
 * ```
 *
 * @param openRouterApiKey API key for the selected [llmBackend].
 * @param llmBackend Backend used for model resolution and prompt execution.
 * @param codexReasoningEffort Maximum operator-approved Codex reasoning effort.
 * @param codexRuntimePreset Optional role effort policy; null preserves the configured effort.
 * @param db The Room database for the current (runId, groupId).
 * @param toolSubSandbox The sub-sandbox for tool execution.
 * @param budgetConfig The group's budget configuration.
 * @param toolsMountPath Path to the /tools mount inside the agent VM.
 * @param toolSubmissionPreflight Optional deterministic TOOL validation before Critic review.
 */
class EvoMoliorAgentFactory(
    private val openRouterApiKey: String,
    private val llmBackend: LlmBackend = LlmBackend.OPENROUTER,
    private val codexReasoningEffort: CodexReasoningEffort = CodexReasoningEffort.HIGH,
    private val db: EvoMoliorDatabase,
    private val toolSubSandbox: ToolSubSandbox,
    private val budgetConfig: BudgetConfig,
    private val toolsMountPath: String = "/tools",
    private val contentSanitiser: ContentSanitiser = ContentSanitiser(),
    private val constitutionalEnforcer: ConstitutionalEnforcer = ConstitutionalEnforcer(),
    private val allowlistProxy: AllowlistProxy = AllowlistProxy(),
    private val authoritativeToolExecution: Boolean = true,
    private val costLedgerRepository: CostLedgerRepository = CostLedgerRepository(db),
    private val taskToolCapabilityPolicy: TaskToolCapabilityPolicy =
        TaskToolCapabilityPolicy.unrestricted,
    private val toolSubmissionPreflight:
        suspend (StagingSubmissionEntity) -> String? = { null },
    qwenToolSupportEnabled: Boolean = LlmBackendToolSupportPolicy.isQwenToolSupportEnabledByEnvironment(),
    private val codexRuntimePreset: CodexRuntimePreset? = null
) {
    private companion object {
        const val DEFAULT_RUN_ROOT_WIP_REF = "dst:root"
        val TOOL_CONTRIBUTION_STATUSES = setOf("pending", "promoted")
        val PUBLIC_TOOL_FAILURE_CODE = Regex("[a-z0-9_]{1,96}")
    }

    private val backendToolSupport = LlmBackendToolSupportPolicy.resolve(
        backend = llmBackend,
        qwenToolSupportEnabled = qwenToolSupportEnabled
    )

    internal fun shouldInstallDelegationTool(bootContext: AgentBootContext): Boolean =
        bootContext.role.canDelegateToSubAgents && !bootContext.isSubAgent

    internal fun codexReasoningEffortFor(role: Role): CodexReasoningEffort =
        codexRuntimePreset?.reasoningEffort(role, codexReasoningEffort) ?: codexReasoningEffort

    /**
     * Creates an [AgentContext] containing a Koog [AIAgent] and its [BudgetLedger].
     *
     * @param bootContext The agent's boot context.
     * @param existingBudgetLedger If provided, use this ledger instead of creating a new one.
     *   Used by the sub-agent delegation flow to inject the carved budget slice.
     * @param deathNoteProtocol Optional [DeathNoteProtocol] for in-process agent runs.
     *   When provided, [WriteDeathNoteTool] will call [DeathNoteProtocol.markDeathNoteWritten]
     *   so the grace-period hard-kill timer is cancelled on successful Death Note write.
     * @param toolAuthoringRuntime Optional lifecycle-local generated workspace and compiler.
     * @return An [AgentContext] ready to run.
     */
    fun create(
        bootContext: AgentBootContext,
        existingBudgetLedger: BudgetLedger? = null,
        deathNoteProtocol: DeathNoteProtocol? = null,
        toolAuthoringRuntime: ToolAuthoringRuntime? = null
    ): AgentContext {
        if (llmBackend == LlmBackend.CODEX_CHATGPT) {
            codexRuntimePreset?.let { preset ->
                require(bootContext.modelAssignment in preset.supportedModelIds) {
                    "Agent model exceeds the selected Codex preset"
                }
            }
        }
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

        val systemPrompt = buildBackendAdjustedSystemPrompt(bootContext)
        val sourceAdvisory = reportSourceAdvisory(bootContext)
        val isOutlier = bootContext.role.name == "Outlier"
        val defaultWipTaskRef = bootContext.wipTaskClaim?.nodeId ?: DEFAULT_RUN_ROOT_WIP_REF
        val workerView = bootContext.workerView
        val assignedToolAuthority = workerView
            ?.assignedToolSkeleton
            ?.let { skeleton ->
                val assignedNode = requireNotNull(workerView.assignedNode)
                val opportunity = requireNotNull(assignedNode.toolOpportunity)
                AssignedToolSubmissionAuthority(
                    nodeId = assignedNode.nodeId.value,
                    opportunityId = opportunity.opportunityId,
                    opportunityCommitmentHash = skeleton.opportunityCommitmentHash
                )
            }
        require(
            toolAuthoringRuntime == null ||
                assignedToolAuthority?.opportunityCommitmentHash ==
                toolAuthoringRuntime.workspace.opportunityCommitmentHash
        ) {
            "Tool authoring workspace does not match assigned worker authority"
        }
        val actionGate = constitutionalActionGate(bootContext)
        val providerCostRecorder = ProviderCallCostRecorder(
            repository = costLedgerRepository,
            runId = bootContext.runId,
            groupId = bootContext.groupId,
            agentId = bootContext.agentId
        )
        val toolInfrastructureRepository = if (authoritativeToolExecution) {
            ToolInfrastructureRepository(
                database = db,
                validationExecutionAuthorities = bootContext.workerView
                    ?.availableToolCards
                    .orEmpty()
                    .filter { it.executionPurpose == ToolExecutionPurpose.VALIDATION_TEST }
                    .mapTo(linkedSetOf()) { card ->
                        ToolValidationExecutionAuthority(
                            runId = bootContext.runId,
                            groupId = AtlasGroupId(bootContext.groupId),
                            toolVersionId = ToolVersionId(card.toolId),
                            opportunityId = card.opportunityId,
                            opportunityCommitmentHash = card.opportunityCommitmentHash,
                            validationNodeId = card.requiredForNodeId
                        )
                    }
            )
        } else {
            null
        }
        val contributionGraphRepository = ContributionGraphRepository(db)

        val toolRegistry = if (usesTextOnlyCompatibilityMode()) {
            ToolRegistry.EMPTY
        } else {
            ToolRegistry {
                // Always-available tools
                tool(ListKBTool(db.knowledgeBaseDao(), bootContext.runId, bootContext.groupId))
                tool(
                    ReadKBTool(
                        knowledgeBaseDao = db.knowledgeBaseDao(),
                        runId = bootContext.runId,
                        groupId = bootContext.groupId,
                        agentId = bootContext.agentId,
                        round = bootContext.generation,
                        nodeId = bootContext.workerView?.assignedNode?.nodeId?.value,
                        contributionGraphRepository = contributionGraphRepository
                    )
                )
                tool(
                    SearchKBTool(
                        knowledgeBaseDao = db.knowledgeBaseDao(),
                        runId = bootContext.runId,
                        groupId = bootContext.groupId,
                        agentId = bootContext.agentId,
                        round = bootContext.generation,
                        nodeId = bootContext.workerView?.assignedNode?.nodeId?.value,
                        contributionGraphRepository = contributionGraphRepository
                    )
                )
                tool(
                    ReadKGTool(
                        knowledgeGraphFactDao = db.knowledgeGraphFactDao(),
                        kgSchemaDao = db.kgSchemaDao(),
                        runId = bootContext.runId,
                        groupId = bootContext.groupId,
                        agentId = bootContext.agentId,
                        round = bootContext.generation,
                        nodeId = bootContext.workerView?.assignedNode?.nodeId?.value,
                        contributionGraphRepository = contributionGraphRepository
                    )
                )
                if (taskToolCapabilityPolicy.allows(TaskToolCapabilityPolicy.READ_RUN_ARTIFACT)) {
                    tool(
                        ReadRunArtifactTool(
                            artifactsMountPath = bootContext.artifactsMountPath,
                            inputArtifacts = bootContext.inputArtifacts
                        )
                    )
                }
                if (taskToolCapabilityPolicy.allows(TaskToolCapabilityPolicy.REQUEST_SOURCE_ACCESS)) {
                    tool(RequestSourceAccessTool(
                        runId = bootContext.runId,
                        groupId = bootContext.groupId,
                        agentId = bootContext.agentId,
                        role = bootContext.role.name,
                        generation = bootContext.generation,
                        runObjective = bootContext.startingField?.objective ?: "",
                        currentAllowedHosts = bootContext.sourceAllowedHosts.toSet(),
                        auditFilePath = bootContext.sourceAccessAuditPath,
                        actionGate = actionGate
                    ))
                }
                if (taskToolCapabilityPolicy.allows(TaskToolCapabilityPolicy.FETCH_SOURCE)) {
                    tool(FetchSourceTool(
                        runId = bootContext.runId,
                        groupId = bootContext.groupId,
                        agentId = bootContext.agentId,
                        role = bootContext.role.name,
                        generation = bootContext.generation,
                        runObjective = bootContext.startingField?.objective ?: "",
                        currentAllowedHosts = bootContext.sourceAllowedHosts.toSet(),
                        auditFilePath = bootContext.sourceAccessAuditPath,
                        stagingDao = db.stagingDao(),
                        contentSanitiser = contentSanitiser,
                        allowlistProxy = allowlistProxy,
                        actionGate = actionGate
                    ))
                }
                tool(
                    QueryPatternTool(
                        knowledgeGraphFactDao = db.knowledgeGraphFactDao(),
                        kgSchemaDao = db.kgSchemaDao(),
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
                        defaultWipTaskRef = defaultWipTaskRef,
                        isOutlier = isOutlier,
                        actionGate = actionGate,
                        requireAssignedToolAuthority = bootContext.role.isOrdinaryWorkerRole,
                        assignedToolAuthority = assignedToolAuthority,
                        toolCandidateWireCompiler =
                            toolAuthoringRuntime?.compileCandidate,
                        toolSubmissionPreflight = toolSubmissionPreflight,
                        docSourceCheck = sourceAdvisory?.let { it::check }
                    )
                )
                toolAuthoringRuntime?.let { runtime ->
                    tool(
                        ToolAuthoringWorkspaceTool(
                            runtime = runtime,
                            agentId = bootContext.agentId,
                            actionGate = actionGate
                        )
                    )
                }
                tool(
                    WriteDeathNoteTool(
                        stagingDao = db.stagingDao(),
                        runId = bootContext.runId,
                        groupId = bootContext.groupId,
                        agentId = bootContext.agentId,
                        role = bootContext.role.name,
                        generation = bootContext.generation,
                        onWritten = deathNoteProtocol?.let { protocol -> { protocol.markDeathNoteWritten() } },
                        actionGate = actionGate
                    )
                )
                if (bootContext.workerView != null) {
                    tool(
                        CommitContributionTool(
                            repository = WorkerExecutionRepository(db),
                            bootContext = bootContext,
                            budgetLedger = budgetLedger,
                            actionGate = actionGate,
                            toolInfrastructureRepository = toolInfrastructureRepository,
                            contributionGraphRepository = contributionGraphRepository
                        )
                    )
                }
                if (isOutlier && bootContext.workerView != null) {
                    tool(
                        ProposeNoveltyVectorTool(
                            repository = OutlierNoveltyRepository(db),
                            bootContext = bootContext,
                            actionGate = actionGate
                        )
                    )
                }
                tool(
                    EmitFactTool(
                        stagingDao = db.stagingDao(),
                        budgetLedger = budgetLedger,
                        runId = bootContext.runId,
                        groupId = bootContext.groupId,
                        agentId = bootContext.agentId,
                        role = bootContext.role.name,
                        generation = bootContext.generation,
                        defaultWipTaskRef = defaultWipTaskRef,
                        actionGate = actionGate,
                        kgSchemaDao = db.kgSchemaDao()
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
                        defaultWipTaskRef = defaultWipTaskRef,
                        actionGate = actionGate
                    )
                )
                if (taskToolCapabilityPolicy.allows(TaskToolCapabilityPolicy.LIST_PROMOTED_TOOLS)) {
                    tool(ListToolsTool(
                        toolRegistryDao = db.toolRegistryDao(),
                        runId = bootContext.runId,
                        groupId = bootContext.groupId
                    ))
                }
                if (taskToolCapabilityPolicy.allows(TaskToolCapabilityPolicy.EXECUTE_PROMOTED_TOOL)) {
                    tool(ExecuteToolTool(
                        toolRegistryDao = db.toolRegistryDao(),
                        toolSubSandbox = toolSubSandbox,
                        budgetLedger = budgetLedger,
                        runId = bootContext.runId,
                        groupId = bootContext.groupId,
                        toolsMountPath = toolsMountPath,
                        contentSanitiser = contentSanitiser,
                        agentId = bootContext.agentId,
                        actionGate = actionGate,
                        assignedNodeId = bootContext.workerView?.assignedNode?.nodeId,
                        availableToolCards = bootContext.workerView
                            ?.availableToolCards
                            .orEmpty(),
                        round = bootContext.generation,
                        infrastructureRepository = toolInfrastructureRepository,
                        costLedgerRepository = costLedgerRepository
                    ))
                }
                tool(
                    SendMessageTool(
                        budgetLedger = budgetLedger,
                        eventLogDao = db.eventLogDao(),
                        runId = bootContext.runId,
                        groupId = bootContext.groupId,
                        agentId = bootContext.agentId,
                        generation = bootContext.generation,
                        actionGate = actionGate
                    )
                )
                tool(
                    ReadMessagesTool(
                        eventLogDao = db.eventLogDao(),
                        runId = bootContext.runId,
                        groupId = bootContext.groupId,
                        agentId = bootContext.agentId
                    )
                )
                if (bootContext.workerView == null) {
                    tool(
                        ClaimTaskTool(
                            wipTaskDao = db.wipTaskDao(),
                            runId = bootContext.runId,
                            groupId = bootContext.groupId,
                            agentId = bootContext.agentId,
                            generation = bootContext.generation,
                            actionGate = actionGate
                        )
                    )
                }
                if (shouldInstallDelegationTool(bootContext)) {
                    tool(
                        DelegateSubTaskTool(
                            coordinator = SubAgentCoordinator(
                                parentContext = bootContext,
                                parentBudgetLedger = budgetLedger,
                                db = db,
                                budgetConfig = budgetConfig,
                                agentFactory = this@EvoMoliorAgentFactory
                            ),
                            bootContext = bootContext
                        )
                    )
                }
            }
        }

        val executor = LlmExecutorFactory.create(
            backend = llmBackend,
            apiKey = openRouterApiKey,
            onCodexSearchEvents = { events -> persistCodexSearchEvents(bootContext, events) },
            codexToolRegistry = toolRegistry,
            codexActionGate = actionGate,
            codexAgentId = bootContext.agentId,
            codexReasoningEffort = codexReasoningEffortFor(bootContext.role),
            codexNativeSearchEnabled = taskToolCapabilityPolicy.allows(
                TaskToolCapabilityPolicy.CODEX_NATIVE_SEARCH
            )
        )

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
                    val stopReason = expectedAgentStopReason(ctx.error)
                    if (stopReason != null) {
                        io.github.aakira.napier.Napier.i(
                            "Koog[$roleName/$agentId] agent stopped by lifecycle: $stopReason"
                        )
                    } else {
                        io.github.aakira.napier.Napier.e(
                            "Koog[$roleName/$agentId] agent FAILED: ${ctx.error.message}",
                            ctx.error
                        )
                    }
                }
                config.onToolCallStarting { ctx ->
                    actionGate.intercept(
                        AgentAction(
                            type = ActionEventType.TOOL_CALL,
                            agentId = bootContext.agentId,
                            detail = ctx.toolName
                        )
                    )
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
                config.installProviderCostRecorder(providerCostRecorder)
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
    ) = withContext(NonCancellable) {
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
                com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity(
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
                "EvoMoliorAgentFactory: $noteKind death note written for ${bootContext.agentId} (id=$submissionId)"
            )
        } catch (insertEx: Exception) {
            // Best-effort — never let the emergency write crash the runner
            io.github.aakira.napier.Napier.e(
                "EvoMoliorAgentFactory: failed to write $noteKind death note for ${bootContext.agentId}: ${insertEx.message}",
                insertEx
            )
        }
    }

    suspend fun writeQwenTextOnlySubmission(
        bootContext: AgentBootContext,
        finalResult: String
    ): Boolean {
        if (!usesTextOnlyCompatibilityMode()) return false
        val trimmed = finalResult.trim().takeIf { it.isNotBlank() } ?: return false
        val isFinalDeliverable = isQwenTextOnlyFinalDeliverableContext(bootContext)
        val isRoundSummary = isRoundSubmissionSummaryContext(bootContext)
        val explicitSubmissionType = bootContext.textOnlySubmissionType
        val submissionType = when {
            isFinalDeliverable -> SubmissionType.DOC.name
            isRoundSummary -> SubmissionType.PLAN_UPDATE.name
            explicitSubmissionType != null -> explicitSubmissionType.name
            bootContext.role == Role.Planner || bootContext.role == Role.MetaPlanner ->
                SubmissionType.PLAN_UPDATE.name
            else -> SubmissionType.DOC.name
        }
        val content = when {
            isFinalDeliverable -> {
                val extracted = extractQwenTextOnlyFinalDeliverableBody(trimmed)
                if (expectsHtmlFinalDeliverable(bootContext) && !looksLikeHtmlDocument(extracted)) {
                    io.github.aakira.napier.Napier.w(
                        "EvoMoliorAgentFactory: skipped no-action final deliverable fallback for " +
                            "${bootContext.agentId}; output did not look like HTML"
                    )
                    return false
                }
                extracted
            }
            isRoundSummary -> ensureRoundSubmissionSummaryMarker(
                text = trimmed,
                groupId = bootContext.groupId,
                generation = bootContext.generation,
                provenance = "Backend text-only compatibility summary"
            )
            else -> buildString {
                appendLine("TEXT-ONLY BACKEND CONTRIBUTION")
                appendLine("Agent: ${bootContext.agentId} (${bootContext.role.name})")
                appendLine("Generation: ${bootContext.generation}")
                appendLine()
                appendLine(trimmed)
            }
        }

        val nowMs = System.currentTimeMillis()
        db.stagingDao().insert(
            StagingSubmissionEntity(
                id = "text-only-${bootContext.agentId}-$nowMs",
                agentId = bootContext.agentId,
                role = bootContext.role.name,
                type = submissionType,
                content = content,
                wipTaskRef = if (bootContext.workerView == null) {
                    bootContext.wipTaskClaim?.nodeId ?: DEFAULT_RUN_ROOT_WIP_REF
                } else {
                    null
                },
                speculative = false,
                outlier = bootContext.role.name == "Outlier",
                budgetSignature = "TEXT_ONLY_BACKEND:${bootContext.agentId}:$nowMs",
                status = "pending",
                initialTrustScore = null,
                generation = bootContext.generation,
                groupId = bootContext.groupId,
                runId = bootContext.runId,
                submittedAtMs = nowMs
            )
        )
        recordDegradedExecution(
            bootContext = bootContext,
            reasonCode = "TEXT_ONLY_BACKEND",
            detail = "Text-only backend output was staged through compatibility salvage.",
            artifactIds = listOf("text-only-${bootContext.agentId}-$nowMs")
        )
        io.github.aakira.napier.Napier.i(
            "EvoMoliorAgentFactory: text-only backend ${submissionType.lowercase()} staged for ${bootContext.agentId}"
        )
        return true
    }

    /**
     * Salvages a no-action completion into staging for tool-capable runs.
     *
     * A warning-only Death Note preserves observability, but it is not a group contribution and
     * cannot satisfy round-summary or final-writer readers. This low-trust fallback persists the
     * final answer as a typed artifact whenever an agent produced useful text but failed to call
     * the required write tool before exiting.
     *
     * @param bootContext Agent identity and prompt context.
     * @param finalResult Final text returned by the agent.
     * @return True when a fallback staging artifact was written.
     */
    suspend fun writeNoActionFallbackSubmission(
        bootContext: AgentBootContext,
        finalResult: String
    ): Boolean {
        if (usesTextOnlyCompatibilityMode()) return false
        val trimmed = finalResult.trim().takeIf { it.isNotBlank() } ?: return false
        val isFinalDeliverable = isQwenTextOnlyFinalDeliverableContext(bootContext)
        val isRoundSummary = isRoundSubmissionSummaryContext(bootContext)
        val submissionType = when {
            isFinalDeliverable -> SubmissionType.DOC.name
            isRoundSummary -> SubmissionType.PLAN_UPDATE.name
            else -> SubmissionType.PLAN_UPDATE.name
        }
        val content = when {
            isFinalDeliverable -> {
                val extracted = extractQwenTextOnlyFinalDeliverableBody(trimmed)
                if (expectsHtmlFinalDeliverable(bootContext) && !looksLikeHtmlDocument(extracted)) {
                    io.github.aakira.napier.Napier.w(
                        "EvoMoliorAgentFactory: skipped no-action final deliverable fallback for " +
                            "${bootContext.agentId}; output did not look like HTML"
                    )
                    return false
                }
                extracted
            }
            isRoundSummary -> ensureRoundSubmissionSummaryMarker(
                text = trimmed,
                groupId = bootContext.groupId,
                generation = bootContext.generation,
                provenance = "Runtime-salvaged summary"
            )
            else -> buildString {
                appendLine("UNWRITTEN AGENT OUTPUT SALVAGE")
                appendLine("Agent: ${bootContext.agentId} (${bootContext.role.name})")
                appendLine("Generation: ${bootContext.generation}")
                appendLine("Reason: agent completed without tool/write actions; runtime salvaged final text.")
                appendLine()
                appendLine(trimmed)
            }
        }

        val nowMs = System.currentTimeMillis()
        val fallbackId = "no-action-fallback-${bootContext.agentId}-$nowMs"
        db.stagingDao().insert(
            StagingSubmissionEntity(
                id = fallbackId,
                agentId = bootContext.agentId,
                role = bootContext.role.name,
                type = submissionType,
                content = content,
                wipTaskRef = if (bootContext.workerView == null) bootContext.wipTaskClaim?.nodeId else null,
                speculative = !isRoundSummary && !isFinalDeliverable,
                outlier = bootContext.role == Role.Outlier,
                budgetSignature = "NO_ACTION_FALLBACK:${bootContext.agentId}:$nowMs",
                status = "pending",
                initialTrustScore = 0.15,
                generation = bootContext.generation,
                groupId = bootContext.groupId,
                runId = bootContext.runId,
                submittedAtMs = nowMs
            )
        )
        recordDegradedExecution(
            bootContext = bootContext,
            reasonCode = "NO_ACTION_FALLBACK",
            detail = "Agent completed without a typed durable action; final text was salvaged.",
            artifactIds = listOf(fallbackId)
        )
        io.github.aakira.napier.Napier.i(
            "EvoMoliorAgentFactory: no-action fallback ${submissionType.lowercase()} staged for ${bootContext.agentId}"
        )
        return true
    }

    /**
     * Enforces the substrate contribution contract after a model finishes.
     *
     * The prompt contract asks every agent to either contribute KG/tool substrate or write an
     * explicit abstention rationale. Models can ignore that instruction; this guard turns ignored
     * obligations into durable low-trust PLAN_UPDATE artifacts so the archive can distinguish
     * "agent abstained or failed to comply" from "the substrate path was invisible."
     */
    suspend fun writeMissingSubstrateContractFallbacks(
        bootContext: AgentBootContext,
        finalResult: String,
        actionLog: List<BudgetDeduction> = emptyList()
    ): Int {
        if (
            usesTextOnlyCompatibilityMode() ||
            isQwenTextOnlyFinalDeliverableContext(bootContext) ||
            isRoundSubmissionSummaryContext(bootContext)
        ) {
            return 0
        }

        val agentSubmissions = db.stagingDao()
            .getByAgent(bootContext.runId, bootContext.groupId, bootContext.agentId)
            .filter { it.generation == bootContext.generation }
        val wroteKgArtifact = agentSubmissions.any {
            it.type == "KG_FACT" ||
                it.type == SubmissionType.KG_CONSTRAINT.name ||
                it.content.contains("KG ABSTENTION RATIONALE", ignoreCase = true)
        }
        val rejectedToolSubmissions = agentSubmissions.filter {
            it.type == SubmissionType.TOOL.name && it.status == "rejected"
        }
        val wroteToolArtifact = agentSubmissions.any {
            (it.type == SubmissionType.TOOL.name && it.status in TOOL_CONTRIBUTION_STATUSES) ||
                it.content.contains("TOOL ABSTENTION RATIONALE", ignoreCase = true)
        } || actionLog.any { deduction ->
            deduction.actionType == ActionType.TOOL_EXEC_SUCCESS ||
                deduction.actionType == ActionType.TOOL_EXEC_FAILURE ||
                deduction.actionType == ActionType.TOOL_EXEC_RETRY
        }

        var written = 0
        if (!wroteKgArtifact) {
            writeSubstrateContractFallback(
                bootContext = bootContext,
                heading = "KG ABSTENTION RATIONALE",
                reason = "Runtime-enforced abstention: the agent finished without emitting a KG fact, " +
                    "proposing a KG constraint, or writing its own KG abstention rationale.",
                finalResult = finalResult
            )
            written += 1
        }
        if (!wroteToolArtifact) {
            val rejectedToolSubmission = rejectedToolSubmissions.lastOrNull()
            writeSubstrateContractFallback(
                bootContext = bootContext,
                heading = "TOOL ABSTENTION RATIONALE",
                reason = rejectedToolSubmission?.let { rejected ->
                    "Runtime-enforced repair required: the agent's TOOL submission was rejected. " +
                        "submissionId=${rejected.id}; " +
                        publicToolPreflightFailure(rejected.rejectionReason)
                } ?: "Runtime-enforced abstention: the agent finished without submitting a tool, " +
                    "executing a reusable tool, or writing its own tool abstention rationale.",
                finalResult = if (rejectedToolSubmission == null) finalResult else ""
            )
            written += 1
        }
        return written
    }

    private fun publicToolPreflightFailure(reason: String?): String {
        val exactReason = reason.orEmpty().ifBlank { "tool_preflight_rejected" }
        val candidateCode = exactReason.substringBefore(':').trim()
        val reasonCode = candidateCode.takeIf(PUBLIC_TOOL_FAILURE_CODE::matches)
            ?: "tool_preflight_rejected"
        return "reasonCode=$reasonCode; reasonHash=${AtlasHashing.hashText(exactReason)}"
    }

    suspend fun recordModelCompletionAttempt(
        budgetLedger: BudgetLedger,
        finalResult: String
    ): Int {
        val estimatedOutputTokens = estimateOutputTokens(finalResult)
        budgetLedger.deduct(ActionType.LLM_CALL, tokenCount = estimatedOutputTokens)
        io.github.aakira.napier.Napier.d(
            "EvoMoliorAgentFactory: recorded model completion attempt for ${budgetLedger.agentId} " +
                "estimatedOutputTokens=$estimatedOutputTokens"
        )
        return estimatedOutputTokens
    }

    internal fun estimateOutputTokens(text: String): Int =
        (text.length / 4).coerceAtLeast(1)

    private suspend fun writeSubstrateContractFallback(
        bootContext: AgentBootContext,
        heading: String,
        reason: String,
        finalResult: String
    ) {
        val nowMs = System.currentTimeMillis()
        val fallbackId =
            "substrate-contract-${heading.substringBefore(' ').lowercase()}-${bootContext.agentId}-$nowMs"
        db.stagingDao().insert(
            StagingSubmissionEntity(
                id = fallbackId,
                agentId = bootContext.agentId,
                role = bootContext.role.name,
                type = SubmissionType.PLAN_UPDATE.name,
                content = buildString {
                    appendLine(heading)
                    appendLine("Agent: ${bootContext.agentId} (${bootContext.role.name})")
                    appendLine("Generation: ${bootContext.generation}")
                    appendLine("Reason: $reason")
                    finalResult.trim().takeIf { it.isNotBlank() }?.let { text ->
                        appendLine()
                        appendLine("Final result excerpt:")
                        appendLine(text.take(1200))
                    }
                },
                wipTaskRef = if (bootContext.workerView == null) bootContext.wipTaskClaim?.nodeId else null,
                speculative = true,
                outlier = bootContext.role == Role.Outlier,
                budgetSignature = "SUBSTRATE_CONTRACT_FALLBACK:${bootContext.agentId}:$nowMs",
                status = "pending",
                initialTrustScore = 0.1,
                generation = bootContext.generation,
                groupId = bootContext.groupId,
                runId = bootContext.runId,
                submittedAtMs = nowMs
            )
        )
        recordDegradedExecution(
            bootContext = bootContext,
            reasonCode = "SUBSTRATE_CONTRACT_FALLBACK",
            detail = "$heading: $reason",
            artifactIds = listOf(fallbackId)
        )
        io.github.aakira.napier.Napier.i(
            "EvoMoliorAgentFactory: runtime substrate contract fallback staged for " +
                "${bootContext.agentId} heading=$heading"
        )
    }

    internal fun isQwenTextOnlyFinalDeliverableContext(bootContext: AgentBootContext): Boolean =
        bootContext.role == Role.Builder &&
            bootContext.rolePrompt.contains("Final Deliverable Writer", ignoreCase = true)

    private suspend fun recordDegradedExecution(
        bootContext: AgentBootContext,
        reasonCode: String,
        detail: String,
        artifactIds: List<String>
    ) {
        val occurredAt = kotlin.time.Clock.System.now()
        WorkerExecutionRepository(db).recordDegradedExecution(
            DegradedExecutionRecord(
                recordId = com.achulkov.evomolior.core.council.CouncilDeterministicIds.create(
                    "degraded-execution",
                    bootContext.runId,
                    bootContext.groupId,
                    bootContext.agentId,
                    reasonCode,
                    occurredAt.toEpochMilliseconds().toString(),
                    artifactIds.joinToString(",")
                ),
                runId = bootContext.runId,
                groupId = com.achulkov.evomolior.core.atlas.AtlasGroupId(bootContext.groupId),
                agentId = com.achulkov.evomolior.core.atlas.AtlasAgentId(bootContext.agentId),
                nodeId = bootContext.assignedLease?.nodeId,
                round = bootContext.workerView?.round ?: bootContext.generation,
                reasonCode = reasonCode,
                detail = detail,
                artifactIds = artifactIds,
                occurredAt = occurredAt
            )
        )
    }

    internal fun isRoundSubmissionSummaryContext(bootContext: AgentBootContext): Boolean =
        bootContext.rolePrompt.contains("ROUND SUBMISSION SUMMARY")

    internal fun ensureRoundSubmissionSummaryMarker(
        text: String,
        groupId: String,
        generation: Int,
        provenance: String
    ): String {
        val trimmed = text.trim()
        if (trimmed.startsWith("ROUND SUBMISSION SUMMARY")) return trimmed
        return buildString {
            appendLine("ROUND SUBMISSION SUMMARY")
            appendLine("$provenance for $groupId, generation $generation.")
            appendLine()
            appendLine(trimmed)
        }
    }

    internal fun extractQwenTextOnlyFinalDeliverableBody(raw: String): String {
        val trimmed = raw.trim()
        val directHtmlStart = listOf("<!doctype html>", "<html")
            .firstOrNull { marker -> trimmed.startsWith(marker, ignoreCase = true) }
        if (directHtmlStart != null) return trimmed

        val contentMarker = Regex("""(?im)^\s*CONTENT\s*$""").find(trimmed)
        if (contentMarker != null) {
            val body = trimmed.substring(contentMarker.range.last + 1).trim()
            if (body.isNotBlank()) return body
        }

        return trimmed
    }

    internal fun expectsHtmlFinalDeliverable(bootContext: AgentBootContext): Boolean {
        val prompt = "${bootContext.rolePrompt}\n${bootContext.stageDisclosure}"
        val explicitMediaType = Regex("""(?im)^\s*-\s*Media type:\s*([^\s]+)\s*$""")
            .find(prompt)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
        if (explicitMediaType != null) {
            return explicitMediaType.startsWith("text/html", ignoreCase = true)
        }
        return prompt.contains("text/html", ignoreCase = true) ||
            prompt.contains("final-article.html", ignoreCase = true) ||
            prompt.contains("html final", ignoreCase = true) ||
            prompt.contains("<!doctype html>", ignoreCase = true)
    }

    internal fun looksLikeHtmlDocument(content: String): Boolean {
        val trimmed = content.trimStart()
        return trimmed.startsWith("<!doctype html>", ignoreCase = true) ||
            trimmed.startsWith("<html", ignoreCase = true)
    }

    /**
     * Returns true when the active backend is intentionally running without tool calls.
     *
     * Runtime wrappers use this to classify no-tool completions as text-only compatibility
     * artifacts instead of warning-only no-progress failures.
     */
    fun usesTextOnlyCompatibilityMode(): Boolean = backendToolSupport.textOnlyCompatibilityMode

    internal fun buildBackendAdjustedSystemPrompt(bootContext: AgentBootContext): String {
        val basePrompt = RolePromptBuilder.build(bootContext)
        val requiresExternalEvidence =
            com.achulkov.evomolior.core.config.RunObjectiveHeuristics.requiresExternalEvidence(
                bootContext.startingField?.objective.orEmpty()
            )
        if (!usesTextOnlyCompatibilityMode()) {
            if (
                llmBackend != LlmBackend.CODEX_CHATGPT ||
                bootContext.role != Role.Researcher ||
                !requiresExternalEvidence ||
                !taskToolCapabilityPolicy.allows(TaskToolCapabilityPolicy.CODEX_NATIVE_SEARCH)
            ) {
                return basePrompt
            }
            return buildString {
                appendLine(basePrompt)
                appendLine()
                appendLine("=== CODEX NATIVE SEARCH REQUIREMENT ===")
                appendLine(CODEX_NATIVE_SEARCH_REQUIRED_MARKER)
                appendLine("Before drafting, execute at least two distinct native web-search queries in this lifecycle and inspect their results.")
                appendLine("The supplied source catalog is a starting point and does not satisfy live re-verification.")
                appendLine("For every material factual claim, include the source title, exact URL, observation date or access context, and the claim it supports.")
                appendLine("Clearly label any statement that remains a hypothesis.")
            }.trim()
        }

        return buildString {
            appendLine("=== RUNTIME EXECUTION MODE: TEXT-ONLY (HIGHEST PRIORITY) ===")
            appendLine("The backend cannot call EvoMolior Koog tools in this lifecycle.")
            appendLine("Do not attempt to call tools or describe tool JSON.")
            appendLine("Return the useful work directly as plain text.")
            appendLine("The runtime will persist your text into EvoMolior staging after completion.")
            appendLine("Any tool mandate in the role contract below is translated into direct analysis over the supplied canonical context.")
            bootContext.canonicalWorkingContext?.takeIf { it.isNotBlank() }?.let { context ->
                appendLine()
                appendLine("=== CANONICAL WORKING CONTEXT AT SPAWN ===")
                appendLine(context)
                appendLine("=== END CANONICAL WORKING CONTEXT ===")
            }
            appendLine()
            appendLine("=== ROLE AND TASK CONTRACT ===")
            appendLine(basePrompt)
            appendLine("=== END ROLE AND TASK CONTRACT ===")
            appendLine()
            appendLine("=== FINAL TEXT-ONLY EXECUTION REMINDER ===")
            appendLine("Produce the substantive artifact now. Do not refuse because Koog tools are unavailable.")
            if (isRoundSubmissionSummaryContext(bootContext)) {
                appendLine("For round summaries, start your answer with the exact marker: ROUND SUBMISSION SUMMARY")
            }
            if (isQwenTextOnlyFinalDeliverableContext(bootContext)) {
                appendLine("For final deliverables, output the complete final file body directly.")
                appendLine("Do not call or mention read_kb, read_kg, read_run_artifact, write_staging, or write_death_note.")
                appendLine("For HTML final deliverables, your first characters must be: <!doctype html>")
                appendLine("Include every verified generated image asset path listed in the task prompt.")
            }
            if (requiresExternalEvidence) {
                appendLine("Native web search is enabled. Research externally verifiable claims before writing.")
                if (
                    llmBackend == LlmBackend.CODEX_CHATGPT &&
                    bootContext.role == Role.Researcher &&
                    taskToolCapabilityPolicy.allows(TaskToolCapabilityPolicy.CODEX_NATIVE_SEARCH)
                ) {
                    appendLine(CODEX_NATIVE_SEARCH_REQUIRED_MARKER)
                    appendLine("Before drafting, execute at least two distinct native web-search queries in this lifecycle and inspect their results.")
                    appendLine("The supplied source catalog is a starting point and does not satisfy live re-verification.")
                }
                appendLine("For every material factual claim, include the source title, exact URL, observation date or access context, and the claim it supports.")
                appendLine("Clearly label any statement that remains a hypothesis.")
            }
        }
    }

    private suspend fun persistCodexSearchEvents(
        bootContext: AgentBootContext,
        events: List<CodexSearchEvent>
    ) {
        events.forEach { event ->
            val payload = buildJsonObject {
                put("toolName", "native_web_search")
                put("success", true)
                put("query", event.query)
                put("queries", JsonArray(event.queries.map(::JsonPrimitive)))
                put("provider", "codex-chatgpt")
                put("rawEvent", event.rawJson)
            }
            db.eventLogDao().insert(
                EventLogEntryEntity(
                    id = UUID.randomUUID().toString(),
                    timestampMs = System.currentTimeMillis(),
                    eventType = EventTypes.TOOL_EXEC,
                    agentId = bootContext.agentId,
                    groupId = bootContext.groupId,
                    runId = bootContext.runId,
                    generation = bootContext.generation,
                    payloadJson = payload.toString()
                )
            )
        }
    }

    internal fun reportSourceAdvisory(bootContext: AgentBootContext): ReportSourceAdvisory? =
        if (taskToolCapabilityPolicy.allows(TaskToolCapabilityPolicy.READ_RUN_ARTIFACT)) {
            ReportSourceAdvisory {
                ReportSourceInputs.load(bootContext.inputArtifacts, bootContext.artifactsMountPath)
            }
        } else {
            null
        }

    internal fun constitutionalActionGate(bootContext: AgentBootContext): ConstitutionalActionGate =
        databaseConstitutionalActionGate(
            enforcer = constitutionalEnforcer,
            db = db,
            runId = bootContext.runId,
            groupId = bootContext.groupId,
            agentId = bootContext.agentId,
            generation = bootContext.generation
        )

    private fun resolveModel(modelId: String): LLModel {
        return LlmExecutorFactory.resolveModel(llmBackend, modelId)
    }
}

/** Container for a created agent and its associated context. */
data class AgentContext(
    val agent: AIAgent<String, String>,
    val budgetLedger: BudgetLedger,
    val bootContext: AgentBootContext
)
