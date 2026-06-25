package com.achulkov.evokammer.runtime

import com.achulkov.evokammer.agents.factory.CouncilRunner
import com.achulkov.evokammer.agents.factory.EvoKammerAgentFactory
import com.achulkov.evokammer.agents.factory.GraderAgentContext
import com.achulkov.evokammer.agents.factory.requireProductiveAgentOutcome
import com.achulkov.evokammer.competition.coordinator.GroupCoordinator
import com.achulkov.evokammer.competition.grader.GraderGroupManager
import com.achulkov.evokammer.core.budget.BudgetConfig
import com.achulkov.evokammer.core.config.ModelSelectionConfig
import com.achulkov.evokammer.core.contract.BlockingAgentAbortException
import com.achulkov.evokammer.core.contract.StartingField
import com.achulkov.evokammer.core.model.GroupProfile
import com.achulkov.evokammer.core.model.GroupProfileType
import com.achulkov.evokammer.orchestration.council.GenesisCouncil
import com.achulkov.evokammer.orchestration.lifecycle.LifecycleManager
import com.achulkov.evokammer.orchestration.metaplanner.MetaPlannerRunner
import com.achulkov.evokammer.api.service.EvoKammerRunService
import com.achulkov.evokammer.api.service.RunArchiveService
import com.achulkov.evokammer.sandbox.shuru.ShuruVmManager
import com.achulkov.evokammer.sandbox.toolsandbox.ToolSubSandbox
import com.achulkov.evokammer.substrate.SubstrateDatabaseFactory
import io.github.aakira.napier.Napier
import kotlinx.coroutines.delay
import java.io.File

private const val OPENROUTER_PROVIDER_502_NEEDLE = "Provider returned error (code: 502)"
private const val OPENROUTER_GENERIC_502_NEEDLE = "(code: 502)"
private const val OPENROUTER_PROVIDER_PARSE_NEEDLE = "JsonLiteral is not a JsonObject"
private const val OPENROUTER_PROVIDER_TOOL_PARSE_NEEDLE = "toolCallPartsOrNull"
private const val OPENROUTER_ALIBABA_INVALID_PARAMETER_NEEDLE = "InternalError.Algo.InvalidParameter"

/**
 * Container for the production EvoKammer engine wiring used by both the desktop UI and CLI.
 *
 * @param dataRootDir Root directory for durable EvoKammer state.
 * @param substrateFactory Factory for the per-run substrate databases.
 * @param shuruVmManager Shared Shuru manager used by lifecycle managers.
 * @param engineService Fully wired EvoKammer engine service.
 */
data class EvoKammerProductionRuntime(
    val dataRootDir: File,
    val substrateFactory: SubstrateDatabaseFactory,
    val shuruVmManager: ShuruVmManager,
    val engineService: EvoKammerRunService
)

/**
 * Builds the production EvoKammer runtime used by the desktop UI and the console CLI.
 *
 * @param dataRootDir Root directory for durable engine state.
 * @return The fully wired runtime components.
 */
fun createProductionRuntime(
    dataRootDir: File,
    councilModelId: String = ModelSelectionConfig.DEFAULT_COUNCIL_MODEL,
    graderModelId: String = ModelSelectionConfig.DEFAULT_GRADER_MODEL,
    criticPipelineModelId: String = ModelSelectionConfig.DEFAULT_CRITIC_PIPELINE_MODEL,
    leakTransformModelId: String = ModelSelectionConfig.DEFAULT_LEAK_TRANSFORM_MODEL
): EvoKammerProductionRuntime {
    val substrateFactory = SubstrateDatabaseFactory(dataRootDir)
    val shuruVmManager = ShuruVmManager()
    val archiveService = RunArchiveService(substrateFactory)
    lateinit var engineService: EvoKammerRunService

    engineService = EvoKammerRunService(
        lifecycleManagerProvider = { runId, groupId, openRouterApiKey, agentJarPath, groupProfile ->
            Napier.i("EvoKammerRuntime: creating LifecycleManager for runId=$runId groupId=$groupId profile=${groupProfile.profileType}")

            val groupDb = substrateFactory.get(runId, groupId)
            val budgetConfig = BudgetConfig(groupId = groupId, profile = groupProfile)
            val workspaceDir = File(dataRootDir, "workspace/$runId/$groupId").also { it.mkdirs() }

            val agentFactory = EvoKammerAgentFactory(
                openRouterApiKey = openRouterApiKey,
                db = groupDb,
                toolSubSandbox = ToolSubSandbox(workspaceDir),
                budgetConfig = budgetConfig
            )

            LifecycleManager(
                runId = runId,
                groupId = groupId,
                shuruVmManager = shuruVmManager,
                substrateDatabaseFactory = substrateFactory,
                agentJarPath = agentJarPath,
                openRouterApiKey = openRouterApiKey,
                groupProfile = groupProfile,
                localAgentRunner = { bootContext, onBudgetLedger ->
                    val agentId = bootContext.agentId
                    val role = bootContext.role
                    val model = bootContext.modelAssignment
                    Napier.i("EvoKammer [$groupId]: launching agent $agentId (${role.name}) model=$model allocatedBudget=${bootContext.allocatedBudget}")

                    if (openRouterApiKey.isBlank()) {
                        throw IllegalArgumentException(
                            "OpenRouter API key is blank — provide a valid key before starting a run."
                        )
                    }

                    val deathNoteProtocol = com.achulkov.evokammer.agents.runner.DeathNoteProtocol(bootContext, groupDb.stagingDao())
                    val startMs = System.currentTimeMillis()

                    val agentContext = try {
                        agentFactory.create(bootContext, deathNoteProtocol = deathNoteProtocol)
                    } catch (e: Exception) {
                        val durationMs = System.currentTimeMillis() - startMs
                        Napier.e(
                            "EvoKammer [$groupId]: agent $agentId (${role.name}) CONSTRUCT FAILED after ${durationMs}ms [${e::class.simpleName}]: ${e.message}",
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
                    Napier.i("EvoKammer [$groupId]: agent $agentId BudgetLedger registered (initial=${agentContext.budgetLedger.initialBudget})")

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
                            "You are an EvoKammer ${role.name} agent. Fulfil your role objectives for this round."
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
                            agentContext.budgetLedger
                        )
                        val actionCount = agentContext.budgetLedger.snapshot().actionLog.size
                        if (actionCount == 0) {
                            agentFactory.writeEmergencyDeathNote(
                                bootContext = bootContext,
                                reason = "WarningOnlyNoProgressCompletion",
                                errorMessage = "agent completed without tool/write actions",
                                finalResult = finalResult,
                                noteKind = "warning-only"
                            )
                            deathNoteProtocol.markDeathNoteWritten()
                        }
                        val durationMs = System.currentTimeMillis() - startMs
                        val ledgerSnapshot = agentContext.budgetLedger.snapshot()
                        val spentBudget = String.format(java.util.Locale.ROOT, "%.2f", ledgerSnapshot.initialBudget - ledgerSnapshot.currentBalance)
                        val remainingBudget = String.format(java.util.Locale.ROOT, "%.2f", ledgerSnapshot.currentBalance)
                        Napier.i(
                            "EvoKammer [$groupId]: agent $agentId completed in ${durationMs}ms — budget: spent=$spentBudget remaining=$remainingBudget actions=${ledgerSnapshot.actionLog.size} result=${finalResult!!.take(100)}"
                        )
                    } catch (e: BlockingAgentAbortException) {
                        val durationMs = System.currentTimeMillis() - startMs
                        Napier.e(
                            "EvoKammer [$groupId]: agent $agentId (${role.name}) RUN FAILED after ${durationMs}ms [${e::class.simpleName}]: ${e.message}",
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
                            "EvoKammer [$groupId]: agent $agentId (${role.name}) RUN FAILED after ${durationMs}ms [${e::class.simpleName}]: ${e.message}",
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
        },
        groupCoordinatorProvider = { groupIds, dimensions ->
            Napier.i("EvoKammerRuntime: creating GroupCoordinator for groups=$groupIds")
            GroupCoordinator(groupIds = groupIds, dimensions = dimensions)
        },
        groupCoordinatorProviderWithKey = { groupIds, dimensions, openRouterApiKey ->
            Napier.i("EvoKammerRuntime: creating keyed GroupCoordinator for groups=$groupIds")
            GroupCoordinator(
                groupIds = groupIds,
                dimensions = dimensions,
                openRouterApiKey = openRouterApiKey,
                privacyTransformModelId = leakTransformModelId
            )
        },
        graderGroupManagerProvider = { dimensions ->
            Napier.i("EvoKammerRuntime: creating GraderGroupManager with ${dimensions.size} dimensions")
            GraderGroupManager(dimensions = dimensions)
        },
        substrateFactory = substrateFactory,
        shuruVmManager = shuruVmManager,
        archiveService = archiveService,
        criticModelOverride = criticPipelineModelId,
        graderRunner = null,
        graderCriteriaRunner = { runId, openRouterApiKey, graderGroupIds, roundIndex, runObjective, scoringDimensions, onProgress ->
            if (openRouterApiKey.isBlank()) {
                Napier.w("GraderRunner: API key blank — skipping criteria phase run=$runId round=$roundIndex")
                return@EvoKammerRunService
            }
            val criteriaRunner = com.achulkov.evokammer.api.grader.GraderRunner(
                openRouterApiKey = openRouterApiKey,
                substrateFactory = substrateFactory,
                localAgentRunner = ::runGraderCriteriaAgent,
                graderBudgetMultiplier = engineService.adversarialDiscriminatorBudgetMultiplier(runId),
                graderModelOverride = graderModelId
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
        graderJudgingRunner = { runId, openRouterApiKey, graderGroupIds, competingGroupIds, roundIndex, runObjective, scoringDimensions, graderGroupManager, onProgress ->
            if (openRouterApiKey.isBlank()) {
                Napier.w("GraderRunner: API key blank — skipping judging phase run=$runId round=$roundIndex")
                return@EvoKammerRunService emptyMap()
            }
            val judgingRunner = com.achulkov.evokammer.api.grader.GraderRunner(
                openRouterApiKey = openRouterApiKey,
                substrateFactory = substrateFactory,
                localAgentRunner = ::runGraderJudgeAgent,
                graderBudgetMultiplier = engineService.adversarialDiscriminatorBudgetMultiplier(runId),
                graderModelOverride = graderModelId
            )
            judgingRunner.runJudgingPhase(
                runId = runId,
                graderGroupIds = graderGroupIds,
                competingGroupIds = competingGroupIds,
                roundIndex = roundIndex,
                runObjective = runObjective,
                scoringDimensions = scoringDimensions,
                graderGroupManager = graderGroupManager,
                onProgress = onProgress
            )
        },
        metaPlannerRunner = MetaPlannerRunner(),
        genesisCouncilRunner = { runId, objective, openRouterApiKey, onProgress ->
            Napier.i("GenesisCouncil: starting council for run=$runId objective='${objective.take(80)}'")
            onProgress?.invoke("Genesis Council initialising — assembling council agents…")

            val council = GenesisCouncil(objective = objective, openRouterApiKey = openRouterApiKey)
            val runner = CouncilRunner(
                openRouterApiKey = openRouterApiKey,
                councilModelId = councilModelId
            )

            Napier.i("GenesisCouncil: running council agents (this may take 1–3 minutes)")
            onProgress?.invoke("Genesis Council running 8 specialist agents in sequence…")

            try {
                val startingField = council.run(
                    agentRunner = runner.agentRunner(),
                    onProgress = onProgress
                )
                Napier.i(
                    "GenesisCouncil: council complete — groups=${startingField.groupCount} models=${startingField.modelAssignment.size} stages=${startingField.stageContents.size}"
                )
                onProgress?.invoke("Genesis Council complete — ${startingField.groupCount} groups designed")
                startingField.copy(runId = runId)
            } catch (e: Exception) {
                Napier.e("GenesisCouncil: council run failed: ${e.message}", e)
                onProgress?.invoke("Genesis Council failed: ${e.message} — using defaults")
                StartingField.default(objective, runId)
            }
        }
    )

    return EvoKammerProductionRuntime(
        dataRootDir = dataRootDir,
        substrateFactory = substrateFactory,
        shuruVmManager = shuruVmManager,
        engineService = engineService
    )
}

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
        requireProductiveAgentOutcome("GraderCriteria[$agentId]", result, graderContext.budgetLedger)
        Napier.i("GraderRunner.criteria: $agentId completed in ${System.currentTimeMillis() - startMs}ms")
        result
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
        requireProductiveAgentOutcome("GraderJudge[$agentId]", result, graderContext.budgetLedger)
        Napier.i("GraderRunner.judge: $agentId completed in ${System.currentTimeMillis() - startMs}ms")
        result
    } catch (e: BlockingAgentAbortException) {
        Napier.e("GraderRunner.judge: $agentId aborted due to blocking provider/runtime error", e)
        throw e
    } catch (e: Exception) {
        Napier.w("GraderRunner.judge: $agentId failed: ${e.message}")
        "[${role.name} judge failed: ${e.message?.take(200)}]"
    }
}
