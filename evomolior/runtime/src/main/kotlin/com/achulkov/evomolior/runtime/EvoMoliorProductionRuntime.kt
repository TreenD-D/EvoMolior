@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.runtime

import com.achulkov.evomolior.agents.factory.CouncilRunner
import com.achulkov.evomolior.agents.factory.EvoMoliorAgentFactory
import com.achulkov.evomolior.agents.factory.GraderAgentContext
import com.achulkov.evomolior.agents.factory.ProviderCallCostRecorder
import com.achulkov.evomolior.agents.factory.TaskToolCapabilityPolicy
import com.achulkov.evomolior.agents.factory.buildGenericNoActionRecoveryPrompt
import com.achulkov.evomolior.agents.factory.buildSourceAcquisitionNoActionRecoveryPrompt
import com.achulkov.evomolior.agents.factory.buildWriteOnlyNoActionRecoveryPrompt
import com.achulkov.evomolior.agents.factory.countProductiveAgentActions
import com.achulkov.evomolior.agents.factory.requireProductiveAgentOutcome
import com.achulkov.evomolior.agents.factory.requiresGenericNoActionRecovery
import com.achulkov.evomolior.agents.factory.requiresRuntimeSourceAcquisition
import com.achulkov.evomolior.competition.coordinator.GroupCoordinator
import com.achulkov.evomolior.competition.grader.GraderGroupManager
import com.achulkov.evomolior.core.budget.BudgetConfig
import com.achulkov.evomolior.core.benchmark.ComparativeRuntimeInputs
import com.achulkov.evomolior.core.contract.RunInputArtifact
import com.achulkov.evomolior.core.council.StartingFieldCompileResult
import com.achulkov.evomolior.core.council.providerCostCapUsd
import com.achulkov.evomolior.api.model.RunConfig
import com.achulkov.evomolior.api.service.AcceptanceRunBoundaryVerifier
import com.achulkov.evomolior.api.service.CouncilExecutionEnvelopeFactory
import com.achulkov.evomolior.core.config.LlmBackend
import com.achulkov.evomolior.core.config.LlmTimeoutPolicy
import com.achulkov.evomolior.core.config.ModelSelectionConfig
import com.achulkov.evomolior.core.contract.BlockingAgentAbortException
import com.achulkov.evomolior.core.contract.CostLedgerPolicy
import com.achulkov.evomolior.core.contract.StartingField
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.atlas.ToolOpportunityCommitment
import com.achulkov.evomolior.core.model.GroupProfile
import com.achulkov.evomolior.core.model.GroupProfileType
import com.achulkov.evomolior.orchestration.council.GenesisCouncil
import com.achulkov.evomolior.orchestration.lifecycle.LifecycleManager
import com.achulkov.evomolior.orchestration.lifecycle.ToolSubmissionValidationResult
import com.achulkov.evomolior.orchestration.lifecycle.ToolSubmissionValidator
import com.achulkov.evomolior.orchestration.metaplanner.MetaPlannerRunner
import com.achulkov.evomolior.api.service.EvoMoliorRunService
import com.achulkov.evomolior.api.service.RunArchiveService
import com.achulkov.evomolior.sandbox.shuru.ShuruVmManager
import com.achulkov.evomolior.sandbox.toolsandbox.ToolSubSandbox
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.atlas.AtlasTransactionRepository
import com.achulkov.evomolior.substrate.cost.CostLedgerRepository
import com.achulkov.evomolior.substrate.db.EvoMoliorDatabase
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.File
import kotlin.io.path.createTempDirectory

private const val OPENROUTER_PROVIDER_502_NEEDLE = "Provider returned error (code: 502)"
private const val OPENROUTER_GENERIC_502_NEEDLE = "(code: 502)"
private const val OPENROUTER_PROVIDER_PARSE_NEEDLE = "JsonLiteral is not a JsonObject"
private const val OPENROUTER_PROVIDER_TOOL_PARSE_NEEDLE = "toolCallPartsOrNull"
private const val OPENROUTER_ALIBABA_INVALID_PARAMETER_NEEDLE = "InternalError.Algo.InvalidParameter"

/**
 * Container for the production EvoMolior engine wiring used by both the desktop UI and CLI.
 *
 * @param dataRootDir Root directory for durable EvoMolior state.
 * @param substrateFactory Factory for the per-run substrate databases.
 * @param shuruVmManager Shared Shuru manager used by lifecycle managers.
 * @param engineService Fully wired EvoMolior engine service.
 */
data class EvoMoliorProductionRuntime(
    val dataRootDir: File,
    val substrateFactory: SubstrateDatabaseFactory,
    val shuruVmManager: ShuruVmManager,
    val engineService: EvoMoliorRunService
)

private val QWEN_CLOUD_TEXT_MODEL_IDS = setOf(
    ModelSelectionConfig.QWEN_CLOUD_QWEN_PLUS_MODEL,
    ModelSelectionConfig.QWEN_CLOUD_QWEN_FAST_MODEL,
    ModelSelectionConfig.QWEN_CLOUD_DEEPSEEK_FLASH_MODEL
)

private fun resolveUtilityModelForBackend(
    llmBackend: LlmBackend,
    requestedModelId: String,
    qwenFallbackModelId: String
): String = when (llmBackend) {
    LlmBackend.OPENROUTER -> requestedModelId
    LlmBackend.QWEN_CLOUD -> requestedModelId
        .takeIf { it in QWEN_CLOUD_TEXT_MODEL_IDS }
        ?: qwenFallbackModelId
    LlmBackend.CODEX_CHATGPT -> ModelSelectionConfig.CODEX_CHATGPT_GPT56_LUNA_MODEL
}

/**
 * Selects the model authority supplied to one production grader phase.
 *
 * A sealed run assignment is copied before composition so callers cannot mutate the authority
 * after it has been resolved. Legacy runtime configuration remains available only when no sealed
 * assignment exists.
 *
 * @param sealedAssignments Optional sealed role-to-model authority for the active run.
 * @param legacyGraderModelOverride Runtime grader model configured for uncompiled runs.
 */
internal data class ProductionGraderModelRouting(
    val sealedAssignments: Map<String, String>?,
    val legacyGraderModelOverride: String?
)

/**
 * Resolves sealed-versus-legacy grader model routing once at a phase boundary.
 *
 * @param sealedAssignments Optional active sealed role authority.
 * @param legacyGraderModelOverride Runtime fallback used only without sealed authority.
 * @return Immutable routing inputs for a [com.achulkov.evomolior.api.grader.GraderRunner].
 */
internal fun resolveProductionGraderModelRouting(
    sealedAssignments: Map<String, String>?,
    legacyGraderModelOverride: String
): ProductionGraderModelRouting = if (sealedAssignments == null) {
    ProductionGraderModelRouting(
        sealedAssignments = null,
        legacyGraderModelOverride = legacyGraderModelOverride
    )
} else {
    ProductionGraderModelRouting(
        sealedAssignments = sealedAssignments.toMap(),
        legacyGraderModelOverride = null
    )
}

internal fun resolveCouncilCodexExecutionTimeout(
    executionEnvelope: com.achulkov.evomolior.core.council.ExecutionEnvelope
) = LlmTimeoutPolicy.codexExecutionTimeout(executionEnvelope.councilExecutionPurpose)

/**
 * Composes the ordinary in-process factory against the host-visible group tool directory.
 *
 * The alternate Shuru runner deliberately uses its `/tools` mount namespace instead.
 */
internal fun createProductionInProcessAgentFactory(
    openRouterApiKey: String,
    llmBackend: LlmBackend,
    database: EvoMoliorDatabase,
    workspaceDir: File,
    budgetConfig: BudgetConfig,
    toolsMountHostPath: String,
    costLedgerRepository: CostLedgerRepository,
    taskToolCapabilityPolicy: TaskToolCapabilityPolicy,
    toolSubmissionPreflight: suspend (StagingSubmissionEntity) -> String?
): EvoMoliorAgentFactory = EvoMoliorAgentFactory(
    openRouterApiKey = openRouterApiKey,
    llmBackend = llmBackend,
    db = database,
    toolSubSandbox = ToolSubSandbox(workspaceDir),
    budgetConfig = budgetConfig,
    toolsMountPath = toolsMountHostPath,
    costLedgerRepository = costLedgerRepository,
    taskToolCapabilityPolicy = taskToolCapabilityPolicy,
    toolSubmissionPreflight = toolSubmissionPreflight
)

/**
 * Runs one neutral Genesis Council stage and freezes its exact comparative runtime payload.
 *
 * No worker or grader lifecycle is activated by this function.
 */
suspend fun generateComparativeRuntimeInputs(
    config: RunConfig,
    inputArtifacts: List<RunInputArtifact>,
    councilModelId: String = ModelSelectionConfig.CODEX_CHATGPT_GPT56_LUNA_MODEL,
    taskToolIds: Set<String>
): ComparativeRuntimeInputs {
    val envelope = CouncilExecutionEnvelopeFactory.create(config)
    val temporaryRoot = createTempDirectory("evomolior-comparative-council-").toFile()
    val substrateFactory = SubstrateDatabaseFactory(temporaryRoot)
    val costRepository = CostLedgerRepository(
        substrateFactory.get(config.runId, "__cost_ledger__"),
        CostLedgerPolicy()
    )
    return try {
        val compiled = GenesisCouncil(
            objective = config.objective,
            executionEnvelope = envelope
        ).runCompiled(
            runId = config.runId,
            agentRunner = CouncilRunner(
                openRouterApiKey = config.openRouterApiKey,
                executionEnvelope = envelope,
                councilModelId = councilModelId,
                llmBackend = config.llmBackend,
                providerCostRecorder = { role ->
                    ProviderCallCostRecorder(
                        repository = costRepository,
                        runId = config.runId,
                        groupId = null,
                        agentId = "comparative-council:${role.name}"
                    )
                }
            ).agentRunner(),
            inputArtifacts = inputArtifacts
        )
        val valid = compiled as? StartingFieldCompileResult.Valid
            ?: error("Comparative Genesis Council did not produce a valid sealed field: $compiled")
        ComparativeRuntimeInputs(
            receiverContract = valid.receiverContract,
            atlas = valid.atlas,
            startingFieldTemplate = valid.workerField,
            taskToolIds = taskToolIds
        )
    } finally {
        substrateFactory.close(config.runId, "__cost_ledger__")
        temporaryRoot.deleteRecursively()
    }
}

/**
 * Builds the production EvoMolior runtime used by the desktop UI and the console CLI.
 *
 * @param dataRootDir Root directory for durable engine state.
 * @param acceptanceRunBoundaryVerifier Synchronous immutable-input verifier for non-normal
 * acceptance authority boundaries.
 * @return The fully wired runtime components.
 */
fun createProductionRuntime(
    dataRootDir: File,
    llmBackend: LlmBackend = LlmBackend.OPENROUTER,
    councilModelId: String = ModelSelectionConfig.DEFAULT_COUNCIL_MODEL,
    graderModelId: String = ModelSelectionConfig.DEFAULT_GRADER_MODEL,
    criticPipelineModelId: String = ModelSelectionConfig.DEFAULT_CRITIC_PIPELINE_MODEL,
    leakTransformModelId: String = ModelSelectionConfig.DEFAULT_LEAK_TRANSFORM_MODEL,
    comparativeRuntimeInputs: ComparativeRuntimeInputs? = null,
    acceptanceRunBoundaryVerifier: AcceptanceRunBoundaryVerifier =
        AcceptanceRunBoundaryVerifier.NONE
): EvoMoliorProductionRuntime {
    val substrateFactory = SubstrateDatabaseFactory(dataRootDir)
    val atlasTransactionRepository = AtlasTransactionRepository(substrateFactory)
    val shuruVmManager = ShuruVmManager()
    val archiveService = RunArchiveService(substrateFactory)
    val resolvedCouncilModelId = resolveUtilityModelForBackend(
        llmBackend = llmBackend,
        requestedModelId = councilModelId,
        qwenFallbackModelId = ModelSelectionConfig.QWEN_CLOUD_QWEN_PLUS_MODEL
    )
    val resolvedGraderModelId = resolveUtilityModelForBackend(
        llmBackend = llmBackend,
        requestedModelId = graderModelId,
        qwenFallbackModelId = ModelSelectionConfig.QWEN_CLOUD_QWEN_PLUS_MODEL
    )
    val resolvedCriticPipelineModelId = resolveUtilityModelForBackend(
        llmBackend = llmBackend,
        requestedModelId = criticPipelineModelId,
        qwenFallbackModelId = ModelSelectionConfig.QWEN_CLOUD_QWEN_PLUS_MODEL
    )
    val resolvedLeakTransformModelId = resolveUtilityModelForBackend(
        llmBackend = llmBackend,
        requestedModelId = leakTransformModelId,
        qwenFallbackModelId = ModelSelectionConfig.QWEN_CLOUD_DEEPSEEK_FLASH_MODEL
    )
    fun createLifecycleManager(
        runId: String,
        groupId: String,
        openRouterApiKey: String,
        agentJarPath: String,
        groupProfile: GroupProfile,
        activeBackend: LlmBackend,
        cumulativeCostCap: Double?
    ): LifecycleManager {
            Napier.i("EvoMoliorRuntime: creating LifecycleManager for runId=$runId groupId=$groupId profile=${groupProfile.profileType}")

            val groupDb = substrateFactory.get(runId, groupId)
            val budgetConfig = BudgetConfig(groupId = groupId, profile = groupProfile)
            val workspaceDir = File(dataRootDir, "workspace/$runId/$groupId").also { it.mkdirs() }
            val toolsMountHostPath = substrateFactory.dbFilePath(runId, groupId).parentFile
                ?.resolve("tools")
                ?.absolutePath
                ?: error("Missing tools mount host path for group=$groupId")
            val toolSubmissionValidator = ToolSubmissionValidator(
                runId = runId,
                groupId = groupId,
                toolsMountHostPath = toolsMountHostPath,
                opportunityResolver = { submission ->
                    val nodeId = submission.wipTaskRef?.let(::NodeId)
                    val atlas = atlasTransactionRepository.currentAtlas(runId)
                    val opportunity = nodeId?.let { atlas?.nodes?.get(it) }?.toolOpportunity
                    if (nodeId == null || atlas == null || opportunity == null) {
                        null
                    } else {
                        ToolOpportunityCommitment(
                            runId = runId,
                            atlasHash = atlas.atlasHash,
                            nodeId = nodeId,
                            opportunity = opportunity
                        ).withComputedHash()
                    }
                }
            )

            val agentFactory = createProductionInProcessAgentFactory(
                openRouterApiKey = openRouterApiKey,
                llmBackend = activeBackend,
                database = groupDb,
                workspaceDir = workspaceDir,
                budgetConfig = budgetConfig,
                toolsMountHostPath = toolsMountHostPath,
                costLedgerRepository = CostLedgerRepository(
                    substrateFactory.get(runId, "__cost_ledger__"),
                    CostLedgerPolicy(cumulativeCapUsd = cumulativeCostCap)
                ),
                taskToolCapabilityPolicy = comparativeRuntimeInputs
                    ?.taskToolIds
                    ?.let(::TaskToolCapabilityPolicy)
                    ?: TaskToolCapabilityPolicy.unrestricted,
                toolSubmissionPreflight = { submission ->
                    when (val result = toolSubmissionValidator.validate(submission)) {
                        ToolSubmissionValidationResult.Valid -> null
                        is ToolSubmissionValidationResult.Invalid -> result.reason
                    }
                }
            )

            return LifecycleManager(
                runId = runId,
                groupId = groupId,
                shuruVmManager = shuruVmManager,
                substrateDatabaseFactory = substrateFactory,
                agentJarPath = agentJarPath,
                openRouterApiKey = openRouterApiKey,
                llmBackend = activeBackend,
                groupProfile = groupProfile,
                localAgentRunner = { bootContext, onBudgetLedger ->
                    val agentId = bootContext.agentId
                    val role = bootContext.role
                    val model = bootContext.modelAssignment
                    Napier.i("EvoMolior [$groupId]: launching agent $agentId (${role.name}) model=$model allocatedBudget=${bootContext.allocatedBudget}")

                    if (openRouterApiKey.isBlank()) {
                        throw IllegalArgumentException(
                            "${activeBackend.apiKeyEnvVar} is blank — provide a valid key before starting a run."
                        )
                    }

                    val deathNoteProtocol = com.achulkov.evomolior.agents.runner.DeathNoteProtocol(bootContext, groupDb.stagingDao())
                    val startMs = System.currentTimeMillis()

                    val agentContext = try {
                        agentFactory.create(bootContext, deathNoteProtocol = deathNoteProtocol)
                    } catch (e: Exception) {
                        val durationMs = System.currentTimeMillis() - startMs
                        Napier.e(
                            "EvoMolior [$groupId]: agent $agentId (${role.name}) CONSTRUCT FAILED after ${durationMs}ms [${e::class.simpleName}]: ${e.message}",
                            e
                        )
                        agentFactory.writeEmergencyDeathNote(
                            bootContext = bootContext,
                            reason = "ConstructFailed:${e.javaClass.simpleName}",
                            errorMessage = e.message ?: "unknown error"
                        )
                        throw e
                    }

                    onBudgetLedger?.invoke(agentContext.budgetLedger)
                    Napier.i("EvoMolior [$groupId]: agent $agentId BudgetLedger registered (initial=${agentContext.budgetLedger.initialBudget})")

                    val taskPrompt = buildString {
                        if (bootContext.stageDisclosure.isNotBlank()) {
                            appendLine("=== STAGE DISCLOSURE ===")
                            appendLine(bootContext.stageDisclosure)
                            appendLine()
                        }
                        bootContext.wipTaskClaim?.let { claim ->
                            appendLine("=== YOUR ASSIGNED TASK ===")
                            appendLine(claim.description)
                            appendLine()
                        }
                        append(bootContext.rolePrompt.ifBlank {
                            "You are an EvoMolior ${role.name} agent. Fulfil your role objectives for this round."
                        })
                    }

                    var finalResult: String? = null
                    try {
                        finalResult = runWithOpenRouterProviderRetryLocal("AgentRunner[$agentId]") {
                            agentContext.agent.run(taskPrompt)
                        }
                        finalResult = requireProductiveAgentOutcome(
                            "AgentRunner[$agentId]",
                            finalResult!!,
                            agentContext.budgetLedger,
                            textOnlyCompatibilityMode = agentFactory.usesTextOnlyCompatibilityMode()
                        )
                        var preCompletionActionLog = agentContext.budgetLedger.snapshot().actionLog
                        val noActionRecoveryPrompt = when {
                            countProductiveAgentActions(preCompletionActionLog) > 0 ||
                                agentFactory.usesTextOnlyCompatibilityMode() -> null
                            requiresRuntimeSourceAcquisition(bootContext) -> {
                                Napier.w("EvoMolior [$groupId]: agent $agentId produced no actions in source-acquisition run; attempting one recovery pass")
                                buildSourceAcquisitionNoActionRecoveryPrompt(
                                    bootContext = bootContext,
                                    previousResult = finalResult!!
                                )
                            }
                            requiresGenericNoActionRecovery(bootContext, finalResult!!) -> {
                                Napier.w("EvoMolior [$groupId]: agent $agentId produced intent-only no-action output; attempting one generic recovery pass")
                                buildGenericNoActionRecoveryPrompt(
                                    bootContext = bootContext,
                                    previousResult = finalResult!!
                                )
                            }
                            else -> null
                        }
                        if (noActionRecoveryPrompt != null) {
                            val recoveryContext = agentFactory.create(
                                bootContext = bootContext,
                                existingBudgetLedger = agentContext.budgetLedger,
                                deathNoteProtocol = deathNoteProtocol
                            )
                            finalResult = runWithOpenRouterProviderRetryLocal("AgentRunner[$agentId]/no-action-recovery") {
                                recoveryContext.agent.run(noActionRecoveryPrompt)
                            }
                            finalResult = requireProductiveAgentOutcome(
                                "AgentRunner[$agentId]/no-action-recovery",
                                finalResult!!,
                                agentContext.budgetLedger,
                                textOnlyCompatibilityMode = false
                            )
                            preCompletionActionLog = agentContext.budgetLedger.snapshot().actionLog
                            if (countProductiveAgentActions(preCompletionActionLog) == 0) {
                                Napier.w(
                                    "EvoMolior [$groupId]: agent $agentId no-action recovery still produced no durable action; " +
                                        "attempting one write-only final recovery pass"
                                )
                                val writeOnlyContext = agentFactory.create(
                                    bootContext = bootContext,
                                    existingBudgetLedger = agentContext.budgetLedger,
                                    deathNoteProtocol = deathNoteProtocol
                                )
                                finalResult = runWithOpenRouterProviderRetryLocal("AgentRunner[$agentId]/write-only-recovery") {
                                    writeOnlyContext.agent.run(
                                        buildWriteOnlyNoActionRecoveryPrompt(
                                            bootContext = bootContext,
                                            previousResult = finalResult!!
                                        )
                                    )
                                }
                                finalResult = requireProductiveAgentOutcome(
                                    "AgentRunner[$agentId]/write-only-recovery",
                                    finalResult!!,
                                    agentContext.budgetLedger,
                                    textOnlyCompatibilityMode = false
                                )
                                preCompletionActionLog = agentContext.budgetLedger.snapshot().actionLog
                            }
                        }
                        agentFactory.recordModelCompletionAttempt(agentContext.budgetLedger, finalResult)
                        val qwenTextOnlySubmissionWritten = agentFactory.writeQwenTextOnlySubmission(
                            bootContext = bootContext,
                            finalResult = finalResult
                        )
                        val ledgerSnapshot = agentContext.budgetLedger.snapshot()
                        val actionCount = countProductiveAgentActions(preCompletionActionLog)
                        agentFactory.writeMissingSubstrateContractFallbacks(
                            bootContext = bootContext,
                            finalResult = finalResult,
                            actionLog = preCompletionActionLog
                        )
                        if (actionCount == 0) {
                            if (!qwenTextOnlySubmissionWritten) {
                                agentFactory.writeNoActionFallbackSubmission(
                                    bootContext = bootContext,
                                    finalResult = finalResult
                                )
                            }
                            val textOnlyCompatibilityCompletion = agentFactory.usesTextOnlyCompatibilityMode() &&
                                qwenTextOnlySubmissionWritten
                            agentFactory.writeEmergencyDeathNote(
                                bootContext = bootContext,
                                reason = if (textOnlyCompatibilityCompletion) {
                                    "TextOnlyCompatibilityCompletion"
                                } else {
                                    "WarningOnlyNoProgressCompletion"
                                },
                                errorMessage = if (textOnlyCompatibilityCompletion) {
                                    "text-only backend completion was preserved as a staged compatibility artifact"
                                } else {
                                    "agent completed without tool/write actions"
                                },
                                finalResult = finalResult,
                                noteKind = if (textOnlyCompatibilityCompletion) "text-only" else "warning-only"
                            )
                            deathNoteProtocol.markDeathNoteWritten()
                        }
                        val durationMs = System.currentTimeMillis() - startMs
                        val spentBudget = String.format(java.util.Locale.ROOT, "%.2f", ledgerSnapshot.initialBudget - ledgerSnapshot.currentBalance)
                        val remainingBudget = String.format(java.util.Locale.ROOT, "%.2f", ledgerSnapshot.currentBalance)
                        Napier.i(
                            "EvoMolior [$groupId]: agent $agentId completed in ${durationMs}ms — budget: spent=$spentBudget remaining=$remainingBudget actions=${ledgerSnapshot.actionLog.size} result=${finalResult!!.take(100)}"
                        )
                    } catch (e: BlockingAgentAbortException) {
                        val durationMs = System.currentTimeMillis() - startMs
                        Napier.e(
                            "EvoMolior [$groupId]: agent $agentId (${role.name}) RUN FAILED after ${durationMs}ms [${e::class.simpleName}]: ${e.message}",
                            e
                        )
                        if (!deathNoteProtocol.isWritten) {
                            agentFactory.writeEmergencyDeathNote(
                                bootContext = bootContext,
                                reason = e.javaClass.simpleName,
                                errorMessage = e.message ?: "unknown error",
                                finalResult = finalResult
                            )
                        }
                        throw e
                    } catch (e: Exception) {
                        val durationMs = System.currentTimeMillis() - startMs
                        Napier.e(
                            "EvoMolior [$groupId]: agent $agentId (${role.name}) RUN FAILED after ${durationMs}ms [${e::class.simpleName}]: ${e.message}",
                            e
                        )
                        if (!deathNoteProtocol.isWritten) {
                            agentFactory.writeEmergencyDeathNote(
                                bootContext = bootContext,
                                reason = e.javaClass.simpleName,
                                errorMessage = e.message ?: "unknown error",
                                finalResult = finalResult
                            )
                        }
                        throw e
                    }
                }
            )
    }

    lateinit var engineService: EvoMoliorRunService

    engineService = EvoMoliorRunService(
        lifecycleManagerProvider = { runId, groupId, openRouterApiKey, agentJarPath, groupProfile ->
            createLifecycleManager(
                runId,
                groupId,
                openRouterApiKey,
                agentJarPath,
                groupProfile,
                llmBackend,
                null
            )
        },
        lifecycleManagerProviderWithExecutionEnvelope = {
                runId,
                groupId,
                openRouterApiKey,
                agentJarPath,
                groupProfile,
                activeBackend,
                envelope
            ->
            createLifecycleManager(
                runId,
                groupId,
                openRouterApiKey,
                agentJarPath,
                groupProfile,
                activeBackend,
                envelope?.providerCostCapUsd
            )
        },
        groupCoordinatorProvider = { groupIds, dimensions ->
            Napier.i("EvoMoliorRuntime: creating GroupCoordinator for groups=$groupIds")
            GroupCoordinator(groupIds = groupIds, dimensions = dimensions)
        },
        groupCoordinatorProviderWithKey = { groupIds, dimensions, openRouterApiKey ->
            Napier.i("EvoMoliorRuntime: creating keyed GroupCoordinator for groups=$groupIds")
            GroupCoordinator(
                groupIds = groupIds,
                dimensions = dimensions,
                openRouterApiKey = openRouterApiKey,
                llmBackend = llmBackend,
                privacyTransformModelId = resolvedLeakTransformModelId
            )
        },
        graderGroupManagerProvider = { dimensions ->
            Napier.i("EvoMoliorRuntime: creating GraderGroupManager with ${dimensions.size} dimensions")
            GraderGroupManager(dimensions = dimensions)
        },
        substrateFactory = substrateFactory,
        shuruVmManager = shuruVmManager,
        archiveService = archiveService,
        acceptanceRunBoundaryVerifier = acceptanceRunBoundaryVerifier,
        comparativeRuntimeInputs = comparativeRuntimeInputs,
        criticModelOverride = resolvedCriticPipelineModelId,
        graderRunner = null,
        graderCriteriaRunner = { runId, openRouterApiKey, graderGroupIds, roundIndex, runObjective, scoringDimensions, onProgress ->
            if (openRouterApiKey.isBlank()) {
                Napier.w("GraderRunner: API key blank — skipping criteria phase run=$runId round=$roundIndex")
                return@EvoMoliorRunService
            }
            val sealedAssignments: Map<String, String>? =
                engineService.activeSealedGraderModelAssignments(runId)
            val modelRouting = resolveProductionGraderModelRouting(
                sealedAssignments = sealedAssignments,
                legacyGraderModelOverride = resolvedGraderModelId
            )
            val criteriaRunner = com.achulkov.evomolior.api.grader.GraderRunner(
                openRouterApiKey = openRouterApiKey,
                llmBackend = llmBackend,
                substrateFactory = substrateFactory,
                localAgentRunner = ::runGraderCriteriaAgent,
                graderAgentTimeoutMs = GRADER_CRITERIA_AGENT_TIMEOUT_MS,
                graderBudgetMultiplier = engineService.adversarialDiscriminatorBudgetMultiplier(runId),
                graderModelOverride = modelRouting.legacyGraderModelOverride,
                sealedGraderModelAssignments = modelRouting.sealedAssignments,
                costLedgerRepositoryProvider = { costRunId ->
                    CostLedgerRepository(
                        substrateFactory.get(costRunId, "__cost_ledger__"),
                        engineService.costLedgerPolicy(costRunId)
                    )
                }
            )
            criteriaRunner.runCriteriaPhase(
                runId = runId,
                graderGroupIds = graderGroupIds,
                roundIndex = roundIndex,
                runObjective = runObjective,
                scoringDimensions = scoringDimensions,
                onProgress = onProgress
            )
        },
        graderJudgingRunner = { runId, openRouterApiKey, graderGroupIds, competingGroupIds, roundIndex, runObjective, scoringDimensions, graderGroupManager, nodeAssignments, onNodeGradeBundles, onProgress ->
            if (openRouterApiKey.isBlank()) {
                Napier.w("GraderRunner: API key blank — skipping judging phase run=$runId round=$roundIndex")
                return@EvoMoliorRunService emptyMap()
            }
            val sealedAssignments: Map<String, String>? =
                engineService.activeSealedGraderModelAssignments(runId)
            val modelRouting = resolveProductionGraderModelRouting(
                sealedAssignments = sealedAssignments,
                legacyGraderModelOverride = resolvedGraderModelId
            )
            val judgingRunner = com.achulkov.evomolior.api.grader.GraderRunner(
                openRouterApiKey = openRouterApiKey,
                llmBackend = llmBackend,
                substrateFactory = substrateFactory,
                localAgentRunner = ::runGraderJudgeAgent,
                graderBudgetMultiplier = engineService.adversarialDiscriminatorBudgetMultiplier(runId),
                graderModelOverride = modelRouting.legacyGraderModelOverride,
                sealedGraderModelAssignments = modelRouting.sealedAssignments,
                costLedgerRepositoryProvider = { costRunId ->
                    CostLedgerRepository(
                        substrateFactory.get(costRunId, "__cost_ledger__"),
                        engineService.costLedgerPolicy(costRunId)
                    )
                }
            )
            judgingRunner.runJudgingPhase(
                runId = runId,
                graderGroupIds = graderGroupIds,
                competingGroupIds = competingGroupIds,
                roundIndex = roundIndex,
                runObjective = runObjective,
                scoringDimensions = scoringDimensions,
                graderGroupManager = graderGroupManager,
                nodeGradingAssignments = nodeAssignments,
                onNodeGradeBundles = onNodeGradeBundles,
                onProgress = onProgress
            )
        },
        graderNoveltyAssessmentRunner = {
                runId,
                openRouterApiKey,
                graderGroupIds,
                roundIndex,
                runObjective,
                proposals,
                onProgress ->
            if (openRouterApiKey.isBlank()) {
                Napier.w(
                    "GraderRunner: API key blank - skipping novelty assessment " +
                        "run=$runId round=$roundIndex"
                )
                return@EvoMoliorRunService emptyList()
            }
            val sealedAssignments: Map<String, String>? =
                engineService.activeSealedGraderModelAssignments(runId)
            val modelRouting = resolveProductionGraderModelRouting(
                sealedAssignments = sealedAssignments,
                legacyGraderModelOverride = resolvedGraderModelId
            )
            val noveltyRunner = com.achulkov.evomolior.api.grader.GraderRunner(
                openRouterApiKey = openRouterApiKey,
                llmBackend = llmBackend,
                substrateFactory = substrateFactory,
                localAgentRunner = ::runGraderJudgeAgent,
                graderBudgetMultiplier =
                    engineService.adversarialDiscriminatorBudgetMultiplier(runId),
                graderModelOverride = modelRouting.legacyGraderModelOverride,
                sealedGraderModelAssignments = modelRouting.sealedAssignments,
                costLedgerRepositoryProvider = { costRunId ->
                    CostLedgerRepository(
                        substrateFactory.get(costRunId, "__cost_ledger__"),
                        engineService.costLedgerPolicy(costRunId)
                    )
                }
            )
            noveltyRunner.runNoveltyAssessmentPhase(
                runId = runId,
                graderGroupIds = graderGroupIds,
                roundIndex = roundIndex,
                runObjective = runObjective,
                proposals = proposals,
                onProgress = onProgress
            )
        },
        metaPlannerRunner = MetaPlannerRunner(),
        genesisCouncilRunner = {
                runId,
                objective,
                openRouterApiKey,
                executionEnvelope,
                inputArtifacts,
                onProgress,
                onInvocationStarted ->
            onInvocationStarted()
            Napier.i("GenesisCouncil: starting council for run=$runId objective='${objective.take(80)}'")
            onProgress?.invoke("Genesis Council initialising — assembling council agents…")

            val council = GenesisCouncil(
                objective = objective,
                executionEnvelope = executionEnvelope
            )
            val councilCostRepository = CostLedgerRepository(
                substrateFactory.get(runId, "__cost_ledger__"),
                CostLedgerPolicy(
                    cumulativeCapUsd = executionEnvelope.providerCostCapUsd
                )
            )
            val runner = CouncilRunner(
                openRouterApiKey = openRouterApiKey,
                executionEnvelope = executionEnvelope,
                councilModelId = resolvedCouncilModelId,
                llmBackend = llmBackend,
                codexExecutionTimeout =
                    resolveCouncilCodexExecutionTimeout(executionEnvelope),
                structuredOutputDiagnosticRoot = dataRootDir.toPath()
                    .resolve("council-structured-output-diagnostics")
                    .resolve(runId),
                providerCostRecorder = { role ->
                    ProviderCallCostRecorder(
                        repository = councilCostRepository,
                        runId = runId,
                        groupId = null,
                        agentId = "council:${role.name}"
                    )
                }
            )

            Napier.i("GenesisCouncil: running council agents (this may take 1–3 minutes)")
            onProgress?.invoke("Genesis Council running 8 specialist agents in sequence…")

            council.runCompiled(
                runId = runId,
                agentRunner = runner.agentRunner(),
                inputArtifacts = inputArtifacts,
                onProgress = onProgress
            )
        }
    )

    return EvoMoliorProductionRuntime(
        dataRootDir = dataRootDir,
        substrateFactory = substrateFactory,
        shuruVmManager = shuruVmManager,
        engineService = engineService
    )
}

private const val GRADER_CRITERIA_AGENT_TIMEOUT_MS = 10 * 60_000L

private suspend fun <T> runWithOpenRouterProviderRetryLocal(
    agentLabel: String,
    maxRetries: Int = 2,
    retryDelayMs: Long = 500L,
    block: suspend () -> T
): T {
    var attempt = 0
    while (true) {
        try {
            return block()
        } catch (e: Exception) {
            if (!isOpenRouterProviderFailureLocal(e)) {
                throw e
            }
            if (attempt >= maxRetries) {
                throw BlockingAgentAbortException(
                    message = "$agentLabel aborted after ${attempt + 1} OpenRouter provider failure(s): ${e.message}",
                    cause = e
                )
            }

            attempt++
            Napier.w(e) { "$agentLabel OpenRouter provider failure (attempt $attempt/$maxRetries); retrying" }
            delay(retryDelayMs * attempt)
        }
    }
}

private fun isOpenRouterProviderFailureLocal(throwable: Throwable): Boolean {
    return generateSequence(throwable) { it.cause }
        .any { candidate ->
            candidate.message?.contains(OPENROUTER_PROVIDER_502_NEEDLE) == true ||
                candidate.message?.contains("OpenRouter API error: Provider returned error (code: 502)") == true ||
                candidate.message?.contains(OPENROUTER_GENERIC_502_NEEDLE) == true ||
                candidate.message?.contains(OPENROUTER_ALIBABA_INVALID_PARAMETER_NEEDLE) == true ||
                candidate.message?.contains(OPENROUTER_PROVIDER_PARSE_NEEDLE) == true ||
                candidate.message?.contains(OPENROUTER_PROVIDER_TOOL_PARSE_NEEDLE) == true
        }
}

private suspend fun runGraderCriteriaAgent(graderContext: GraderAgentContext): String {
    val agentId = graderContext.agentId
    val role = graderContext.role
    Napier.i("GraderRunner.criteria: launching $agentId (${role.name})")
    val startMs = System.currentTimeMillis()
    return try {
        val result = runWithOpenRouterProviderRetryLocal("GraderCriteria[$agentId]") {
            graderContext.agent.run(graderContext.taskPrompt)
        }
        requireProductiveAgentOutcome(
            "GraderCriteria[$agentId]",
            result,
            graderContext.budgetLedger,
            textOnlyCompatibilityMode = graderContext.textOnlyCompatibilityMode
        )
        Napier.i("GraderRunner.criteria: $agentId completed in ${System.currentTimeMillis() - startMs}ms")
        result
    } catch (e: CancellationException) {
        throw e
    } catch (e: BlockingAgentAbortException) {
        Napier.e("GraderRunner.criteria: $agentId aborted due to blocking provider/runtime error", e)
        throw e
    } catch (e: Exception) {
        Napier.w("GraderRunner.criteria: $agentId failed: ${e.message}")
        "[${role.name} criteria failed: ${e.message?.take(200)}]"
    }
}

private suspend fun runGraderJudgeAgent(graderContext: GraderAgentContext): String {
    val agentId = graderContext.agentId
    val role = graderContext.role
    Napier.i("GraderRunner.judge: launching $agentId (${role.name}) targeting ${graderContext.targetGroupId}")
    val startMs = System.currentTimeMillis()
    return try {
        val result = runWithOpenRouterProviderRetryLocal("GraderJudge[$agentId]") {
            graderContext.agent.run(graderContext.taskPrompt)
        }
        requireProductiveAgentOutcome(
            "GraderJudge[$agentId]",
            result,
            graderContext.budgetLedger,
            textOnlyCompatibilityMode = graderContext.textOnlyCompatibilityMode
        )
        Napier.i("GraderRunner.judge: $agentId completed in ${System.currentTimeMillis() - startMs}ms")
        result
    } catch (e: CancellationException) {
        throw e
    } catch (e: BlockingAgentAbortException) {
        Napier.e("GraderRunner.judge: $agentId aborted due to blocking provider/runtime error", e)
        throw e
    } catch (e: Exception) {
        Napier.w("GraderRunner.judge: $agentId failed: ${e.message}")
        "[${role.name} judge failed: ${e.message?.take(200)}]"
    }
}
