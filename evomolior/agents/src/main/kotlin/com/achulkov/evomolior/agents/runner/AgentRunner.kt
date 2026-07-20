package com.achulkov.evomolior.agents.runner

import com.achulkov.evomolior.agents.factory.EvoMoliorAgentFactory
import com.achulkov.evomolior.agents.factory.buildGenericNoActionRecoveryPrompt
import com.achulkov.evomolior.agents.factory.buildSourceAcquisitionNoActionRecoveryPrompt
import com.achulkov.evomolior.agents.factory.buildWriteOnlyNoActionRecoveryPrompt
import com.achulkov.evomolior.agents.factory.countProductiveAgentActions
import com.achulkov.evomolior.agents.factory.requireProductiveAgentOutcome
import com.achulkov.evomolior.agents.factory.requiresGenericNoActionRecovery
import com.achulkov.evomolior.agents.factory.requiresRuntimeSourceAcquisition
import com.achulkov.evomolior.agents.factory.runWithOpenRouterProviderRetry
import com.achulkov.evomolior.core.budget.BudgetConfig
import com.achulkov.evomolior.core.contract.AgentBootContext
import com.achulkov.evomolior.core.config.LlmBackend
import com.achulkov.evomolior.core.model.GroupProfile
import com.achulkov.evomolior.core.model.GroupProfileType
import com.achulkov.evomolior.sandbox.toolsandbox.ToolSubSandbox
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Main agent loop — the entry point that runs **inside** a Shuru microVM.
 *
 * Lifecycle:
 * 1. Reads [AgentBootContext] from `EVOMOLIOR_BOOT_CONTEXT` env var or `/boot/context.json`.
 * 2. Opens the substrate DB at [AgentBootContext.substrateDbPath].
 * 3. Registers the [DeathNoteProtocol] JVM shutdown hook.
 * 4. Creates the Koog [EvoMoliorAgentFactory] and builds the agent.
 * 5. Runs the agent to completion; on budget exhaustion triggers the grace period.
 * 6. Exits cleanly once the Death Note is written.
 *
 * The OpenRouter API key is read from the `OPENROUTER_API_KEY` environment variable
 * (injected by the LifecycleManager via [VmConfig.env]).
 *
 * @param bootContext             The agent's full boot context.
 * @param substrateDatabaseFactory Factory to open the group's Room DB.
 * @param openRouterApiKey        API key for OpenRouter (from env).
 * @param groupProfile            Resolved cost profile for this group.
 * @param toolSubSandbox          Sub-sandbox for tool execution.
 */
class AgentRunner(
    private val bootContext: AgentBootContext,
    private val substrateDatabaseFactory: SubstrateDatabaseFactory,
    private val openRouterApiKey: String,
    private val llmBackend: LlmBackend = LlmBackend.OPENROUTER,
    private val groupProfile: GroupProfile = GroupProfile.Library,
    private val toolSubSandbox: ToolSubSandbox = ToolSubSandbox()
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Runs the agent until completion or budget exhaustion.
     *
     * On completion (normal or budget), triggers the [DeathNoteProtocol] grace period.
     * The process exits after the Death Note is confirmed written.
     */
    suspend fun run() {
        Napier.i("AgentRunner: starting ${bootContext.agentId} role=${bootContext.role} gen=${bootContext.generation}")

        // 1. Open the substrate DB
        val dbDir = File(bootContext.substrateDbPath).parentFile
        val db = substrateDatabaseFactory.get(bootContext.runId, bootContext.groupId)

        // 2. Register Death Note protocol
        val deathNoteProtocol = DeathNoteProtocol(bootContext, db.stagingDao())
        deathNoteProtocol.register()

        // 3. Build budget config
        val budgetConfig = BudgetConfig(
            groupId = bootContext.groupId,
            profile = groupProfile
        )

        // 4. Create agent via factory — pass DeathNoteProtocol so that WriteDeathNoteTool
        //    can call markDeathNoteWritten() and cancel the grace-period emergency fallback.
        val factory = EvoMoliorAgentFactory(
            openRouterApiKey = openRouterApiKey,
            llmBackend = llmBackend,
            db = db,
            toolSubSandbox = toolSubSandbox,
            budgetConfig = budgetConfig,
            toolsMountPath = "/tools"
        )
        val agentContext = factory.create(bootContext, deathNoteProtocol = deathNoteProtocol)

        Napier.i("AgentRunner: ${bootContext.agentId} budget=${agentContext.budgetLedger.balance} floor=${agentContext.budgetLedger.floor}")

        // 5. Run the Koog agent loop
        var finalResult: String? = null
        try {
            finalResult = runWithOpenRouterProviderRetry("AgentRunner[${bootContext.agentId}]") {
                agentContext.agent.run(bootContext.rolePrompt)
            }
            finalResult = requireProductiveAgentOutcome(
                "AgentRunner[${bootContext.agentId}]",
                finalResult!!,
                agentContext.budgetLedger,
                textOnlyCompatibilityMode = factory.usesTextOnlyCompatibilityMode()
            )
            var preCompletionActionLog = agentContext.budgetLedger.snapshot().actionLog
            val noActionRecoveryPrompt = when {
                countProductiveAgentActions(preCompletionActionLog) > 0 || factory.usesTextOnlyCompatibilityMode() -> null
                requiresRuntimeSourceAcquisition(bootContext) -> {
                    Napier.w("AgentRunner: ${bootContext.agentId} produced no actions in source-acquisition run; attempting one recovery pass")
                    buildSourceAcquisitionNoActionRecoveryPrompt(
                        bootContext = bootContext,
                        previousResult = finalResult!!
                    )
                }
                requiresGenericNoActionRecovery(bootContext, finalResult!!) -> {
                    Napier.w("AgentRunner: ${bootContext.agentId} produced intent-only no-action output; attempting one generic recovery pass")
                    buildGenericNoActionRecoveryPrompt(
                        bootContext = bootContext,
                        previousResult = finalResult!!
                    )
                }
                else -> null
            }
            if (noActionRecoveryPrompt != null) {
                val recoveryContext = factory.create(
                    bootContext = bootContext,
                    existingBudgetLedger = agentContext.budgetLedger,
                    deathNoteProtocol = deathNoteProtocol
                )
                finalResult = runWithOpenRouterProviderRetry("AgentRunner[${bootContext.agentId}]/no-action-recovery") {
                    recoveryContext.agent.run(noActionRecoveryPrompt)
                }
                finalResult = requireProductiveAgentOutcome(
                    "AgentRunner[${bootContext.agentId}]/no-action-recovery",
                    finalResult!!,
                    agentContext.budgetLedger,
                    textOnlyCompatibilityMode = false
                )
                preCompletionActionLog = agentContext.budgetLedger.snapshot().actionLog
                if (countProductiveAgentActions(preCompletionActionLog) == 0) {
                    Napier.w(
                        "AgentRunner: ${bootContext.agentId} no-action recovery still produced no durable action; " +
                            "attempting one write-only final recovery pass"
                    )
                    val writeOnlyContext = factory.create(
                        bootContext = bootContext,
                        existingBudgetLedger = agentContext.budgetLedger,
                        deathNoteProtocol = deathNoteProtocol
                    )
                    finalResult = runWithOpenRouterProviderRetry("AgentRunner[${bootContext.agentId}]/write-only-recovery") {
                        writeOnlyContext.agent.run(
                            buildWriteOnlyNoActionRecoveryPrompt(
                                bootContext = bootContext,
                                previousResult = finalResult!!
                            )
                        )
                    }
                    finalResult = requireProductiveAgentOutcome(
                        "AgentRunner[${bootContext.agentId}]/write-only-recovery",
                        finalResult!!,
                        agentContext.budgetLedger,
                        textOnlyCompatibilityMode = false
                    )
                    preCompletionActionLog = agentContext.budgetLedger.snapshot().actionLog
                }
            }
            factory.recordModelCompletionAttempt(agentContext.budgetLedger, finalResult)
            val qwenTextOnlySubmissionWritten = factory.writeQwenTextOnlySubmission(
                bootContext = bootContext,
                finalResult = finalResult
            )
            val ledgerSnapshot = agentContext.budgetLedger.snapshot()
            val actionCount = countProductiveAgentActions(preCompletionActionLog)
            factory.writeMissingSubstrateContractFallbacks(
                bootContext = bootContext,
                finalResult = finalResult,
                actionLog = preCompletionActionLog
            )
            if (actionCount == 0) {
                if (!qwenTextOnlySubmissionWritten) {
                    factory.writeNoActionFallbackSubmission(
                        bootContext = bootContext,
                        finalResult = finalResult
                    )
                }
                val textOnlyCompatibilityCompletion = factory.usesTextOnlyCompatibilityMode() &&
                    qwenTextOnlySubmissionWritten
                factory.writeEmergencyDeathNote(
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
            Napier.i("AgentRunner: ${bootContext.agentId} completed. result=${finalResult!!.take(200)}")
        } catch (e: com.achulkov.evomolior.core.contract.BlockingAgentAbortException) {
            Napier.e("AgentRunner: ${bootContext.agentId} agent error: ${e.message}", e)
            if (!deathNoteProtocol.isWritten) {
                factory.writeEmergencyDeathNote(
                    bootContext = bootContext,
                    reason = e.javaClass.simpleName,
                    errorMessage = e.message ?: "unknown error",
                    finalResult = finalResult
                )
            }
        } catch (e: Exception) {
            Napier.e("AgentRunner: ${bootContext.agentId} agent error: ${e.message}", e)
            // Write an emergency death note only if the agent didn't already write its own.
            if (!deathNoteProtocol.isWritten) {
                factory.writeEmergencyDeathNote(
                    bootContext = bootContext,
                    reason = e.javaClass.simpleName,
                    errorMessage = e.message ?: "unknown error",
                    finalResult = finalResult
                )
            }
        }

        // 6. If the agent wrote its own Death Note, skip the grace wait entirely.
        if (deathNoteProtocol.isWritten) {
            Napier.i("AgentRunner: ${bootContext.agentId} Death Note already written — exiting immediately")
            return
        }

        // 7. Trigger grace period for Death Note (agent finished without writing one)
        val finalBalance = agentContext.budgetLedger.balance
        Napier.i("AgentRunner: ${bootContext.agentId} final balance=$finalBalance — entering grace period for Death Note")
        deathNoteProtocol.signalGrace(scope, reason = "agent_loop_complete")

        // 8. Wait for Death Note to be confirmed written (or grace period to expire)
        val deadline = System.currentTimeMillis() + (bootContext.gracePeriodSeconds + 5L) * 1000
        while (!deathNoteProtocol.isWritten && System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(200)
        }

        Napier.i("AgentRunner: ${bootContext.agentId} exiting. deathNoteWritten=${deathNoteProtocol.isWritten}")
    }

    companion object {
        /** Boot context JSON env variable name. */
        const val BOOT_CONTEXT_ENV = "EVOMOLIOR_BOOT_CONTEXT"

        /** Boot context file path inside the VM. */
        const val BOOT_CONTEXT_FILE = "/boot/context.json"

        /** OpenRouter API key env variable. */
        const val API_KEY_ENV = "OPENROUTER_API_KEY"

        /** Selected backend env variable. */
        const val BACKEND_ENV = "EVOMOLIOR_LLM_BACKEND"

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Reads the [AgentBootContext] from the environment variable or the boot context file.
         * Throws if neither is available.
         */
        fun readBootContext(): AgentBootContext {
            val envJson = System.getenv(BOOT_CONTEXT_ENV)
            if (!envJson.isNullOrBlank()) {
                return json.decodeFromString(envJson)
            }
            val file = File(BOOT_CONTEXT_FILE)
            if (file.exists()) {
                return json.decodeFromString(file.readText())
            }
            error("AgentRunner: No boot context found. Set $BOOT_CONTEXT_ENV or provide $BOOT_CONTEXT_FILE")
        }

        fun readLlmBackend(): LlmBackend = System.getenv(BACKEND_ENV)
            ?.takeIf { it.isNotBlank() }
            ?.let(LlmBackend::parse)
            ?: LlmBackend.OPENROUTER

        fun readApiKey(backend: LlmBackend = readLlmBackend()): String =
            readApiKey(
                backend = backend,
                getenv = System::getenv
            )

        internal fun readApiKey(
            backend: LlmBackend,
            getenv: (String) -> String?
        ): String =
            when (backend) {
                LlmBackend.CODEX_CHATGPT -> LlmBackend.resolveCodexCliPath(
                    LlmBackend.codexCliCandidates(getenv)
                )
                    ?: error(
                        "AgentRunner: Codex CLI not found. Set ${backend.apiKeyEnvVar} or " +
                            "${LlmBackend.CODEX_CLI_ENV_VAR}, install the Codex app at " +
                            LlmBackend.DEFAULT_CODEX_APP_CLI_PATH +
                            ", or put 'codex' on PATH."
                    )
                else -> getenv(backend.apiKeyEnvVar)
                    ?: getenv(API_KEY_ENV)
                    ?: error("AgentRunner: ${backend.apiKeyEnvVar} environment variable not set")
            }

        /**
         * Resolves the [GroupProfile] from the `EVOMOLIOR_GROUP_PROFILE` env var.
         * Falls back to [GroupProfileType.Library] if not set or unrecognised.
         */
        fun readGroupProfile(): GroupProfile {
            val profileName = System.getenv("EVOMOLIOR_GROUP_PROFILE") ?: return GroupProfile.Library
            return try {
                GroupProfile.default(GroupProfileType.valueOf(profileName))
            } catch (_: IllegalArgumentException) {
                GroupProfile.Library
            }
        }
    }
}
