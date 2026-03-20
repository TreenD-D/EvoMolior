package com.achulkov.evokammer.agents.runner

import com.achulkov.evokammer.agents.factory.EvoKammerAgentFactory
import com.achulkov.evokammer.core.budget.BudgetConfig
import com.achulkov.evokammer.core.contract.AgentBootContext
import com.achulkov.evokammer.core.model.GroupProfile
import com.achulkov.evokammer.core.model.GroupProfileType
import com.achulkov.evokammer.sandbox.toolsandbox.ToolSubSandbox
import com.achulkov.evokammer.substrate.SubstrateDatabaseFactory
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
 * 1. Reads [AgentBootContext] from `EVOKAMMER_BOOT_CONTEXT` env var or `/boot/context.json`.
 * 2. Opens the substrate DB at [AgentBootContext.substrateDbPath].
 * 3. Registers the [DeathNoteProtocol] JVM shutdown hook.
 * 4. Creates the Koog [EvoKammerAgentFactory] and builds the agent.
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

        // 4. Create agent via factory
        val factory = EvoKammerAgentFactory(
            openRouterApiKey = openRouterApiKey,
            db = db,
            toolSubSandbox = toolSubSandbox,
            budgetConfig = budgetConfig,
            toolsMountPath = "/tools"
        )
        val agentContext = factory.create(bootContext)

        Napier.i("AgentRunner: ${bootContext.agentId} budget=${agentContext.budgetLedger.balance} floor=${agentContext.budgetLedger.floor}")

        // 5. Run the Koog agent loop
        try {
            val result = agentContext.agent.run(bootContext.rolePrompt)
            Napier.i("AgentRunner: ${bootContext.agentId} completed. result=${result.take(200)}")
        } catch (e: Exception) {
            Napier.e("AgentRunner: ${bootContext.agentId} agent error: ${e.message}", e)
        }

        // 6. Trigger grace period for Death Note
        val finalBalance = agentContext.budgetLedger.balance
        Napier.i("AgentRunner: ${bootContext.agentId} final balance=$finalBalance — entering grace period")
        deathNoteProtocol.signalGrace(scope, reason = "agent_loop_complete")

        // 7. Wait for Death Note to be confirmed written (or grace period to expire)
        val deadline = System.currentTimeMillis() + (bootContext.gracePeriodSeconds + 5L) * 1000
        while (!deathNoteProtocol.isWritten && System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(200)
        }

        Napier.i("AgentRunner: ${bootContext.agentId} exiting. deathNoteWritten=${deathNoteProtocol.isWritten}")
    }

    companion object {
        /** Boot context JSON env variable name. */
        const val BOOT_CONTEXT_ENV = "EVOKAMMER_BOOT_CONTEXT"

        /** Boot context file path inside the VM. */
        const val BOOT_CONTEXT_FILE = "/boot/context.json"

        /** OpenRouter API key env variable. */
        const val API_KEY_ENV = "OPENROUTER_API_KEY"

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

        /**
         * Reads the OpenRouter API key from the environment.
         * Throws if not set.
         */
        fun readApiKey(): String =
            System.getenv(API_KEY_ENV)
                ?: error("AgentRunner: $API_KEY_ENV environment variable not set")

        /**
         * Resolves the [GroupProfile] from the `EVOKAMMER_GROUP_PROFILE` env var.
         * Falls back to [GroupProfileType.Library] if not set or unrecognised.
         */
        fun readGroupProfile(): GroupProfile {
            val profileName = System.getenv("EVOKAMMER_GROUP_PROFILE") ?: return GroupProfile.Library
            return try {
                GroupProfile.default(GroupProfileType.valueOf(profileName))
            } catch (_: IllegalArgumentException) {
                GroupProfile.Library
            }
        }
    }
}
