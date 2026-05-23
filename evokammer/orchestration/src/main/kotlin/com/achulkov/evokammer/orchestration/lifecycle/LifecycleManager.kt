package com.achulkov.evokammer.orchestration.lifecycle

import com.achulkov.evokammer.core.budget.BudgetLedger
import com.achulkov.evokammer.core.contract.AgentBootContext
import com.achulkov.evokammer.core.contract.RoleAllocation
import com.achulkov.evokammer.core.contract.WipTaskClaim
import com.achulkov.evokammer.core.model.AgentStatus
import com.achulkov.evokammer.core.model.GroupProfile
import com.achulkov.evokammer.core.model.Role
import com.achulkov.evokammer.sandbox.shuru.ShuruVmManager
import com.achulkov.evokammer.sandbox.shuru.VmConfig
import com.achulkov.evokammer.sandbox.shuru.VmHandle
import com.achulkov.evokammer.substrate.SubstrateDatabaseFactory
import com.achulkov.evokammer.substrate.db.entity.EventLogEntryEntity
import com.achulkov.evokammer.substrate.db.entity.EventTypes
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manages the full lifecycle of agent VMs for a single group within a run.
 *
 * Responsibilities:
 * - Spawning agents based on [RoleAllocation] from the Evolutionary Selector
 * - Budget monitoring (polling; kill sequence on floor reached)
 * - Kill sequence: SIGTERM → grace period → SIGKILL  (Shuru mode)
 *   OR coroutine cancellation (in-process mode)
 * - Post-kill: capturing Death Note, emitting events
 *
 * One LifecycleManager instance per (runId, groupId).
 *
 * ### Execution modes
 * When [localAgentRunner] is **non-null**, agents are executed as in-process coroutines
 * (no Shuru VMs required). This is the recommended mode for development and testing.
 * When [localAgentRunner] is **null**, agents are launched as Shuru microVMs.
 *
 * @param runId The run identifier.
 * @param groupId The group identifier.
 * @param shuruVmManager VM lifecycle manager (only used when [localAgentRunner] is null).
 * @param substrateDatabaseFactory Factory for group DB access.
 * @param agentJarPath Path to the AgentRunner JAR (only used in Shuru VM mode).
 * @param openRouterApiKey OpenRouter API key — injected as `OPENROUTER_API_KEY` env var in
 *   Shuru VMs so the in-VM `AgentRunner` can reach OpenRouter without any IPC.
 * @param groupProfile The group's cost profile — injected as `EVOKAMMER_GROUP_PROFILE` env var
 *   in Shuru VMs so the in-VM runner constructs the correct [BudgetConfig].
 * @param localAgentRunner Optional suspend function that runs an agent in-process.
 *   The second parameter is an optional callback the runner uses to register the agent's
 *   [BudgetLedger] with this manager, enabling live budget polling. When provided,
 *   bypasses Shuru VM spawning entirely.
 * @param gracePeriodSeconds How long to wait for Death Note after SIGTERM.
 * @param budgetPollingIntervalMs How often to check agent budgets and emit budget change events.
 * @param ipcPortBase Base port for IPC; incremented per agent.
 */
class LifecycleManager(
    private val runId: String,
    private val groupId: String,
    private val shuruVmManager: ShuruVmManager,
    private val substrateDatabaseFactory: SubstrateDatabaseFactory,
    private val agentJarPath: String,
    private val openRouterApiKey: String,
    private val groupProfile: GroupProfile = GroupProfile.Library,
    private val localAgentRunner: (suspend (AgentBootContext, onBudgetLedger: ((BudgetLedger) -> Unit)?) -> Unit)? = null,
    private val gracePeriodSeconds: Int = 30,
    private val budgetPollingIntervalMs: Long = 5_000L,
    private val ipcPortBase: Int = 10_000
) {
    private val db get() = substrateDatabaseFactory.get(runId, groupId)

    /** Live agent registry: agentId → (VmHandle/Job, status). */
    private val liveAgents = ConcurrentHashMap<String, LiveAgentEntry>()
    private val ipcPortCounter = AtomicInteger(ipcPortBase)

    private val _agentStates = MutableStateFlow<Map<String, AgentState>>(emptyMap())
    @Suppress("unused") // consumed by UI / Watchdog via the public StateFlow
    val agentStates: StateFlow<Map<String, AgentState>> = _agentStates.asStateFlow()

    private var budgetMonitorJob: Job? = null

    /**
     * Whether this manager uses in-process (coroutine-based) agent execution.
     * When true, Shuru VMs are not required.
     */
    val usesInProcessMode: Boolean get() = localAgentRunner != null

    /**
     * Called when a Shuru VM spawn fails.
     * Set by the service layer to surface spawn errors to the UI.
     */
    var onSpawnFailed: (agentId: String, role: Role, error: Exception) -> Unit = { _, _, _ -> }

    /**
     * Called when an agent is successfully spawned.
     * Parameters: agentId, role, modelId.
     */
    var onAgentSpawned: (agentId: String, role: Role, modelId: String) -> Unit = { _, _, _ -> }

    /**
     * Called when an agent exits (normally or killed).
     * Parameters: agentId, wasKilled.
     */
    var onAgentDied: (agentId: String, wasKilled: Boolean) -> Unit = { _, _ -> }

    /**
     * Called periodically with the updated total remaining budget for this group
     * (sum of all live agents' current budget balances).
     */
    var onBudgetChanged: (totalRemainingBudget: Double) -> Unit = { _ -> }

    /**
     * Spawns all agents defined in the [allocation].
     *
     * Each agent gets an [AgentBootContext] with its assigned model, role prompt,
     * WiP task claim, and Legacy Protocol (if available).
     *
     * @param allocation The role allocation from the Evolutionary Selector.
     * @param stageDisclosure The current stage disclosure content.
     * @param modelAssignments Map of role name → OpenRouter model string.
     * @param rolePrompts Map of role name → system prompt.
     * @param scope Coroutine scope for monitoring and in-process agent jobs.
     */
    suspend fun spawnGeneration(
        allocation: RoleAllocation,
        stageDisclosure: String,
        modelAssignments: Map<String, String>,
        rolePrompts: Map<String, String>,
        scope: CoroutineScope
    ) {
        val mode = if (localAgentRunner != null) "in-process" else "Shuru VM"
        Napier.i { "[$groupId] Spawning generation ${allocation.generation}: ${allocation.totalAgentCount} agents (mode=$mode)" }

        for (entry in allocation.entries) {
            repeat(entry.count) {
                try {
                    spawnAgent(
                        role = entry.role,
                        generation = allocation.generation,
                        budgetPerAgent = entry.budgetPerAgent,
                        stageDisclosure = stageDisclosure,
                        modelAssignment = modelAssignments[entry.role.name]
                            ?: DEFAULT_MODELS[entry.role]
                            ?: "openai/gpt-4o-mini",
                        rolePrompt = rolePrompts[entry.role.name] ?: defaultRolePrompt(entry.role),
                        forceOutlier = entry.forceOutlier,
                        scope = scope
                    )
                } catch (e: Exception) {
                    Napier.e(e) { "[$groupId] Failed to spawn agent for role ${entry.role}" }
                }
            }
        }

        // Start budget monitoring loop if not already running
        if (budgetMonitorJob?.isActive != true) {
            budgetMonitorJob = scope.launch {
                monitorBudgets()
            }
        }
    }

    /**
     * Gracefully terminates all live agents (SIGTERM then SIGKILL after grace period
     * for Shuru VMs, or coroutine cancellation for in-process agents).
     */
    suspend fun terminateAll() {
        budgetMonitorJob?.cancel()
        liveAgents.keys.toList().forEach { agentId ->
            initiateKillSequence(agentId, "run_termination")
        }
    }

    /** Returns the number of currently live (active) agents. */
    @Suppress("unused") // called by orchestration layer to gate round advancement
    fun getLiveAgentCount(): Int = liveAgents.size

    // ---- Private ----

    private suspend fun spawnAgent(
        role: Role,
        generation: Int,
        budgetPerAgent: Double,
        stageDisclosure: String,
        modelAssignment: String,
        rolePrompt: String,
        forceOutlier: Boolean,
        scope: CoroutineScope
    ) {
        val agentId = "agent-${role.name.lowercase()}-${UUID.randomUUID().toString().take(8)}"
        val ipcPort = ipcPortCounter.getAndIncrement()

        // Claim WiP task (Outliers never get tasks)
        val wipClaim = if (!forceOutlier && role != Role.Outlier) {
            db.wipTaskDao().getNextUnclaimed(runId, groupId)?.let { task ->
                val claimed = db.wipTaskDao().claimTask(task.nodeId, runId, groupId, agentId, generation)
                if (claimed > 0) WipTaskClaim(task.nodeId, task.description) else null
            }
        } else null

        // Retrieve Legacy Protocol
        val legacyProtocol = if (role.receivesLegacyProtocol && !forceOutlier) {
            db.legacyProtocolDao().getLatestForRole(runId, groupId, role.name, generation)?.let { p ->
                com.achulkov.evokammer.core.contract.LegacyProtocolContext(
                    protocolId = p.id,
                    role = role,
                    extractedFromGeneration = p.extractedFromGeneration,
                    expiresAtGeneration = p.expiresAtGeneration,
                    reasoningPosture = p.reasoningPosture,
                    toolHeuristics = p.toolHeuristicsJson,
                    failureMap = p.failureMapJson,
                    kgHabits = p.kgHabitsJson
                )
            }
        } else null

        // Load evolving social norms from KB (domain = "system:norms")
        val dynamicNorms: String? = runCatching {
            val normsEntries = db.knowledgeBaseDao().queryByDomain(
                runId = runId,
                groupId = groupId,
                domain = "system:norms",
                limit = 5
            )
            if (normsEntries.isNotEmpty()) {
                normsEntries.joinToString("\n\n") { it.content }.takeIf { it.isNotBlank() }
            } else null
        }.getOrNull()

        val bootContext = AgentBootContext(
            agentId = agentId,
            runId = runId,
            groupId = groupId,
            role = role,
            rolePrompt = rolePrompt,
            budgetToken = "token:$agentId:${System.currentTimeMillis()}",
            substrateDbPath = substrateDatabaseFactory.dbFilePath(runId, groupId).absolutePath,
            stagingMountPath = "/staging",
            wipTaskClaim = wipClaim,
            legacyProtocol = legacyProtocol,
            stageDisclosure = stageDisclosure,
            modelAssignment = modelAssignment,
            ipcBaseUrl = "http://10.0.0.1:$ipcPort",
            generation = generation,
            gracePeriodSeconds = gracePeriodSeconds,
            dynamicNorms = dynamicNorms,
            // Carry the exact per-agent allocation so both in-process and Shuru runners
            // initialise BudgetLedger with the amount the selector intended.
            allocatedBudget = budgetPerAgent
        )

        if (localAgentRunner != null) {
            // ── In-process mode: run agent as a coroutine ────────────────────
            val spawnTimeMs = System.currentTimeMillis()
            val agentJob = scope.launch {
                try {
                    Napier.i { "[$groupId] In-process agent $agentId (${role.name}) starting — model=$modelAssignment budget=$budgetPerAgent" }
                    // Pass a callback so the runner can register the BudgetLedger for live polling
                    localAgentRunner.invoke(bootContext) { ledger ->
                        // Store the live ledger reference so monitorBudgets() can poll it
                        liveAgents[agentId]?.let { entry ->
                            liveAgents[agentId] = entry.copy(budgetLedger = ledger)
                        }
                        Napier.d { "[$groupId] Agent $agentId registered BudgetLedger (initialBudget=${ledger.initialBudget})" }
                    }
                    val durationMs = System.currentTimeMillis() - spawnTimeMs
                    Napier.i { "[$groupId] In-process agent $agentId completed normally in ${durationMs}ms" }
                    val entry = liveAgents[agentId]
                    if (entry != null) handleAgentExit(agentId, entry, wasKilled = false)
                } catch (e: CancellationException) {
                    Napier.i { "[$groupId] In-process agent $agentId was cancelled after ${System.currentTimeMillis() - spawnTimeMs}ms" }
                    val entry = liveAgents[agentId]
                    if (entry != null) handleAgentExit(agentId, entry, wasKilled = true)
                    throw e // propagate to properly cancel the coroutine
                } catch (e: Exception) {
                    val durationMs = System.currentTimeMillis() - spawnTimeMs
                    Napier.e(e) { "[$groupId] In-process agent $agentId [${e::class.simpleName}] error after ${durationMs}ms: ${e.message}" }
                    val entry = liveAgents[agentId]
                    if (entry != null) handleAgentExit(agentId, entry, wasKilled = false)
                }
            }

            liveAgents[agentId] = LiveAgentEntry(
                handle = null,
                job = agentJob,
                status = AgentStatus.Active,
                generation = generation,
                budgetRemaining = budgetPerAgent,
                budgetLedger = null  // will be set by the runner callback above
            )
            updateAgentStates()
            emitEvent(EventTypes.AGENT_SPAWN, agentId, generation, buildSpawnPayload(agentId, role, generation))
            onAgentSpawned(agentId, role, modelAssignment)
            Napier.i { "[$groupId] Launched in-process agent: $agentId (${role.name}) model=$modelAssignment" }

        } else {
            // ── Shuru VM mode: spawn via CLI ──────────────────────────────────
            val dbDir = substrateDatabaseFactory.dbFilePath(runId, groupId).parentFile
            val mountConfig = com.achulkov.evokammer.sandbox.shuru.FilesystemMountConfig(
                kbMountHostPath = "$dbDir/kb",
                toolsMountHostPath = "$dbDir/tools",
                stagingMountHostPath = "$dbDir/staging/$agentId"
            )
            val networkConfig = when (role) {
                Role.Researcher -> com.achulkov.evokammer.sandbox.shuru.NetworkAllowlistConfig.RESEARCHER
                Role.Builder -> com.achulkov.evokammer.sandbox.shuru.NetworkAllowlistConfig.BUILDER
                Role.Outlier -> com.achulkov.evokammer.sandbox.shuru.NetworkAllowlistConfig.BROAD
                else -> com.achulkov.evokammer.sandbox.shuru.NetworkAllowlistConfig.NO_INTERNET
            }

            val vmConfig = VmConfig(
                agentId = agentId,
                groupId = groupId,
                runId = runId,
                mounts = mountConfig,
                network = networkConfig.copy(ipcTargetHost = "10.0.0.1"),
                agentJarPath = agentJarPath,
                bootContextJson = Json.encodeToString(bootContext),
                // Inject credentials and profile as env vars so the in-VM AgentRunner
                // can read them via System.getenv() — standard Shuru env injection.
                openRouterApiKey = openRouterApiKey,
                groupProfile = groupProfile.profileType.name,
                ipcPort = ipcPort
            )

            val handle = try {
                Napier.i { "[$groupId] Spawning Shuru VM for agent $agentId (${role.name}) model=$modelAssignment" }
                shuruVmManager.spawnVm(vmConfig)
            } catch (e: Exception) {
                onSpawnFailed(agentId, role, e)
                Napier.e(e) { "[$groupId] Failed to spawn Shuru VM for agent $agentId (${role.name}): ${e.message}" }
                return
            }

            liveAgents[agentId] = LiveAgentEntry(
                handle = handle,
                job = null,
                status = AgentStatus.Active,
                generation = generation,
                budgetRemaining = budgetPerAgent,
                budgetLedger = null  // Shuru VM agents manage their own ledger internally
            )
            updateAgentStates()
            emitEvent(EventTypes.AGENT_SPAWN, agentId, generation, buildSpawnPayload(agentId, role, generation))
            onAgentSpawned(agentId, role, modelAssignment)
            Napier.i { "[$groupId] Spawned Shuru VM agent: $agentId (${role.name}) model=$modelAssignment" }
        }
    }

    private suspend fun monitorBudgets() {
        while (currentCoroutineContext().isActive) {
            delay(budgetPollingIntervalMs)
            val agentIds = liveAgents.keys.toList()
            var totalBudgetChanged = false

            for (agentId in agentIds) {
                val entry = liveAgents[agentId] ?: continue
                when {
                    entry.job != null -> {
                        // In-process agents self-cleanup via their coroutine completion handlers.
                        if (!entry.job.isActive) {
                            Napier.d { "[$groupId] In-process agent $agentId job completed (late detection)" }
                            handleAgentExit(agentId, entry, wasKilled = false)
                        } else {
                            // Poll the live BudgetLedger balance if available
                            val ledger = entry.budgetLedger
                            if (ledger != null) {
                                val currentBalance = ledger.balance
                                if (currentBalance != entry.budgetRemaining) {
                                    liveAgents[agentId] = entry.copy(budgetRemaining = currentBalance)
                                    totalBudgetChanged = true
                                    Napier.d { "[$groupId] Agent $agentId budget: ${currentBalance} (spent=${ledger.initialBudget - currentBalance})" }
                                }
                                // ── Budget floor kill (§2a) ─────────────────────────────
                                // If the ledger has hit its floor, immediately cancel the
                                // agent coroutine so it writes its Death Note and exits.
                                if (ledger.isAtFloor && entry.status != AgentStatus.DyingGracefully) {
                                    Napier.w { "[$groupId] Agent $agentId hit budget floor — initiating kill" }
                                    initiateKillSequence(agentId, "budget_floor")
                                }
                            }
                        }
                    }
                    entry.handle != null -> {
                        // Shuru VM: poll status via CLI
                        val status = shuruVmManager.status(entry.handle)
                        if (status == "stopped" || status == "unknown") {
                            handleAgentExit(agentId, entry, wasKilled = false)
                        }
                    }
                }
            }

            // Emit aggregate budget update if any agent's balance changed
            if (totalBudgetChanged) {
                val totalRemaining = liveAgents.values.sumOf { it.budgetLedger?.balance ?: it.budgetRemaining }
                onBudgetChanged(totalRemaining)
                updateAgentStates()
            }
        }
    }

    private suspend fun initiateKillSequence(agentId: String, reason: String) {
        val entry = liveAgents[agentId] ?: return
        Napier.i { "[$groupId] Kill sequence initiated for $agentId: $reason" }

        // Mark as dying
        liveAgents[agentId] = entry.copy(status = AgentStatus.DyingGracefully)
        updateAgentStates()

        if (entry.job != null) {
            // In-process mode: cancel the coroutine.
            // The CancellationException handler in the launch block will call handleAgentExit.
            entry.job.cancel()
            return
        }

        // Shuru VM mode: SIGTERM → grace period → SIGKILL
        try {
            shuruVmManager.signal(entry.handle!!, "SIGTERM")
        } catch (e: Exception) {
            Napier.w(e) { "SIGTERM failed for $agentId" }
        }

        // Wait for grace period
        delay(gracePeriodSeconds * 1000L)

        // Check if VM still running
        val status = shuruVmManager.status(entry.handle!!)
        if (status == "running") {
            Napier.w { "[$groupId] Agent $agentId did not exit during grace period — SIGKILL" }
            try {
                shuruVmManager.killVm(entry.handle)
            } catch (e: Exception) {
                Napier.e(e) { "SIGKILL failed for $agentId" }
            }
        }

        handleAgentExit(agentId, entry, wasKilled = true)
    }

    private suspend fun handleAgentExit(agentId: String, entry: LiveAgentEntry, wasKilled: Boolean) {
        liveAgents.remove(agentId)
        updateAgentStates()

        // Log final budget snapshot if we have a live ledger (in-process mode)
        val finalSnapshot = entry.budgetLedger?.snapshot()
        if (finalSnapshot != null) {
            Napier.i {
                "[$groupId] Agent $agentId final budget: initial=${finalSnapshot.initialBudget} " +
                "remaining=${finalSnapshot.currentBalance} " +
                "actions=${finalSnapshot.actionLog.size} " +
                "spent=${finalSnapshot.initialBudget - finalSnapshot.currentBalance}"
            }
            if (finalSnapshot.actionLog.isNotEmpty()) {
                val actionSummary = finalSnapshot.actionLog
                    .groupBy { it.actionType }
                    .map { (type, deductions) -> "${type.name}=${deductions.size}" }
                    .joinToString(", ")
                Napier.d { "[$groupId] Agent $agentId action breakdown: $actionSummary" }
            }
        }

        val exitPayload = buildString {
            append("""{"agentId":"$agentId","wasKilled":$wasKilled""")
            if (finalSnapshot != null) {
                append(""","budgetRemaining":${finalSnapshot.currentBalance}""")
                append(""","budgetSpent":${finalSnapshot.initialBudget - finalSnapshot.currentBalance}""")
                append(""","actionCount":${finalSnapshot.actionLog.size}""")
            }
            append("}")
        }
        emitEvent(
            if (wasKilled) EventTypes.AGENT_KILLED else EventTypes.AGENT_DEATH,
            agentId,
            entry.generation,
            exitPayload
        )
        onAgentDied(agentId, wasKilled)

        // Emit updated group budget after agent exit
        val totalRemaining = liveAgents.values.sumOf { it.budgetLedger?.balance ?: it.budgetRemaining }
        onBudgetChanged(totalRemaining)

        Napier.i { "[$groupId] Agent $agentId exited (wasKilled=$wasKilled, inProcess=${entry.job != null})" }
    }

    private suspend fun emitEvent(type: String, agentId: String?, generation: Int, payload: String) {
        db.eventLogDao().insert(
            EventLogEntryEntity(
                id = UUID.randomUUID().toString(),
                timestampMs = System.currentTimeMillis(),
                eventType = type,
                agentId = agentId,
                groupId = groupId,
                runId = runId,
                generation = generation,
                payloadJson = payload
            )
        )
    }

    private fun updateAgentStates() {
        _agentStates.value = liveAgents.mapValues { (agentId, entry) ->
            AgentState(
                agentId = agentId,
                status = entry.status,
                generation = entry.generation,
                // Use live ledger balance if available, else last-known snapshot
                budgetRemaining = entry.budgetLedger?.balance ?: entry.budgetRemaining
            )
        }
    }

    private fun buildSpawnPayload(agentId: String, role: Role, generation: Int): String =
        """{"agentId":"$agentId","role":"${role.name}","generation":$generation,"inProcess":${localAgentRunner != null}}"""

    private fun defaultRolePrompt(role: Role): String =
        "You are a ${role.name} agent in the EvoKammer system. Act according to your role."

    companion object {
        /**
         * Default role → model mappings used when no modelAssignments override is provided.
         *
         * All IDs here MUST exist in the Koog OpenRouterModels catalogue so the models
         * are created with proper capabilities (including LLMCapability.Completion).
         * Using an unknown ID creates a model without capabilities and causes Koog to throw
         * "does not support completion" at agent launch time.
         */
        val DEFAULT_MODELS = mapOf(
            Role.Planner     to "google/gemini-2.5-pro",         // deep, slow reasoning
            Role.Critic      to "anthropic/claude-sonnet-4",     // evaluation-tuned, reliable gating
            Role.Researcher  to "google/gemini-2.5-flash",       // fast, broad-knowledge
            Role.Builder     to "openai/gpt-4o",                 // code-specialist
            Role.Archivist   to "google/gemini-2.5-flash-lite",  // efficient summarisation
            Role.Outlier     to "openai/gpt-oss-120b",           // oddball cognitive diversity
            Role.MetaPlanner to "anthropic/claude-sonnet-4"      // consistent policy reasoning
        )
    }
}

private data class LiveAgentEntry(
    val handle: VmHandle?,          // non-null in Shuru VM mode
    val job: Job?,                  // non-null in in-process mode
    val status: AgentStatus,
    val generation: Int,
    val budgetRemaining: Double,
    val budgetLedger: BudgetLedger? // non-null once the in-process runner registers it
)

/** Snapshot of an agent's live state (for IPC / UI). */
data class AgentState(
    val agentId: String,
    val status: AgentStatus,
    val generation: Int,
    val budgetRemaining: Double
)
