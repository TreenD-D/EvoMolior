package com.achulkov.evokammer.orchestration.lifecycle

import com.achulkov.evokammer.core.contract.AgentBootContext
import com.achulkov.evokammer.core.contract.RoleAllocation
import com.achulkov.evokammer.core.contract.WipTaskClaim
import com.achulkov.evokammer.core.model.AgentStatus
import com.achulkov.evokammer.core.model.Role
import com.achulkov.evokammer.sandbox.shuru.ShuruVmManager
import com.achulkov.evokammer.sandbox.shuru.VmConfig
import com.achulkov.evokammer.sandbox.shuru.VmHandle
import com.achulkov.evokammer.substrate.SubstrateDatabaseFactory
import com.achulkov.evokammer.substrate.db.entity.EventLogEntryEntity
import com.achulkov.evokammer.substrate.db.entity.EventTypes
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.currentCoroutineContext
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
 * - Kill sequence: SIGTERM → grace period → SIGKILL
 * - Post-kill: capturing Death Note, emitting events
 *
 * One LifecycleManager instance per (runId, groupId).
 *
 * @param runId The run identifier.
 * @param groupId The group identifier.
 * @param shuruVmManager VM lifecycle manager.
 * @param substrateDatabaseFactory Factory for group DB access.
 * @param agentJarPath Path to the AgentRunner JAR.
 * @param openRouterApiKey OpenRouter API key (passed to agent VMs via boot context).
 * @param gracePeriodSeconds How long to wait for Death Note after SIGTERM.
 * @param budgetPollingIntervalMs How often to check agent budgets.
 * @param ipcPortBase Base port for IPC; incremented per agent.
 */
class LifecycleManager(
    private val runId: String,
    private val groupId: String,
    private val shuruVmManager: ShuruVmManager,
    private val substrateDatabaseFactory: SubstrateDatabaseFactory,
    private val agentJarPath: String,
    @Suppress("unused") private val openRouterApiKey: String, // reserved for future IPC-based key injection
    private val gracePeriodSeconds: Int = 30,
    private val budgetPollingIntervalMs: Long = 5_000L,
    private val ipcPortBase: Int = 10_000
) {
    private val db get() = substrateDatabaseFactory.get(runId, groupId)

    /** Live agent registry: agentId → (VmHandle, status). */
    private val liveAgents = ConcurrentHashMap<String, LiveAgentEntry>()
    private val ipcPortCounter = AtomicInteger(ipcPortBase)

    private val _agentStates = MutableStateFlow<Map<String, AgentState>>(emptyMap())
    @Suppress("unused") // consumed by UI / Watchdog via the public StateFlow
    val agentStates: StateFlow<Map<String, AgentState>> = _agentStates.asStateFlow()

    private var budgetMonitorJob: Job? = null

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
     * @param scope Coroutine scope for monitoring jobs.
     */
    suspend fun spawnGeneration(
        allocation: RoleAllocation,
        stageDisclosure: String,
        modelAssignments: Map<String, String>,
        rolePrompts: Map<String, String>,
        scope: CoroutineScope
    ) {
        Napier.i { "[$groupId] Spawning generation ${allocation.generation}: ${allocation.totalAgentCount} agents" }

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
                        forceOutlier = entry.forceOutlier
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
     * Gracefully terminates all live agents (SIGTERM then SIGKILL after grace period).
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
        forceOutlier: Boolean
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
            dynamicNorms = dynamicNorms
        )

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
            ipcPort = ipcPort
        )

        val handle = shuruVmManager.spawnVm(vmConfig)
        liveAgents[agentId] = LiveAgentEntry(handle = handle, status = AgentStatus.Active, generation = generation, budgetRemaining = budgetPerAgent)

        updateAgentStates()
        emitEvent(EventTypes.AGENT_SPAWN, agentId, generation, buildSpawnPayload(agentId, role, generation))

        Napier.i { "[$groupId] Spawned agent: $agentId (${role.name})" }
    }

    private suspend fun monitorBudgets() {
        while (currentCoroutineContext().isActive) {
            delay(budgetPollingIntervalMs)
            val agentIds = liveAgents.keys.toList()
            for (agentId in agentIds) {
                val entry = liveAgents[agentId] ?: continue
                // In a real implementation, the BudgetLedger runs in the VM and reports balance via IPC
                // Here we check VM status as a proxy
                val status = shuruVmManager.status(entry.handle)
                if (status == "stopped" || status == "unknown") {
                    // VM exited on its own (budget exhausted or natural completion)
                    handleAgentExit(agentId, entry, wasKilled = false)
                }
            }
        }
    }

    private suspend fun initiateKillSequence(agentId: String, reason: String) {
        val entry = liveAgents[agentId] ?: return
        Napier.i { "[$groupId] Kill sequence initiated for $agentId: $reason" }

        // Mark as dying
        liveAgents[agentId] = entry.copy(status = AgentStatus.DyingGracefully)
        updateAgentStates()

        // SIGTERM — give agent grace period to write Death Note
        try {
            shuruVmManager.signal(entry.handle, "SIGTERM")
        } catch (e: Exception) {
            Napier.w(e) { "SIGTERM failed for $agentId" }
        }

        // Wait for grace period
        delay(gracePeriodSeconds * 1000L)

        // Check if VM still running
        val status = shuruVmManager.status(entry.handle)
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
        emitEvent(
            if (wasKilled) EventTypes.AGENT_KILLED else EventTypes.AGENT_DEATH,
            agentId,
            entry.generation,
            """{"agentId":"$agentId","wasKilled":$wasKilled}"""
        )
        Napier.i { "[$groupId] Agent $agentId exited (wasKilled=$wasKilled)" }
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
        _agentStates.value = liveAgents.mapValues { (_, entry) ->
            AgentState(
                agentId = entry.handle.agentId,
                status = entry.status,
                generation = entry.generation,
                budgetRemaining = entry.budgetRemaining
            )
        }
    }

    private fun buildSpawnPayload(agentId: String, role: Role, generation: Int): String =
        """{"agentId":"$agentId","role":"${role.name}","generation":$generation}"""

    private fun defaultRolePrompt(role: Role): String =
        "You are a ${role.name} agent in the EvoKammer system. Act according to your role."

    companion object {
        private val DEFAULT_MODELS = mapOf(
            Role.Planner to "deepseek/deepseek-v3.2",
            Role.Critic to "anthropic/claude-sonnet-4.6",
            Role.Researcher to "google/gemini-3-flash-preview",
            Role.Builder to "minimax/minimax-m2.5",
            Role.Archivist to "google/gemini-3-flash-preview",
            Role.Outlier to "openai/gpt-oss-120b",
            Role.MetaPlanner to "anthropic/claude-sonnet-4.6"
        )
    }
}

private data class LiveAgentEntry(
    val handle: VmHandle,
    val status: AgentStatus,
    val generation: Int,
    val budgetRemaining: Double
)

/** Snapshot of an agent's live state (for IPC / UI). */
data class AgentState(
    val agentId: String,
    val status: AgentStatus,
    val generation: Int,
    val budgetRemaining: Double
)
