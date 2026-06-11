package com.achulkov.evokammer.orchestration.lifecycle

import com.achulkov.evokammer.core.budget.BudgetLedger
import com.achulkov.evokammer.core.contract.BlockingAgentAbortException
import com.achulkov.evokammer.core.contract.AgentBootContext
import com.achulkov.evokammer.core.contract.RunInputArtifact
import com.achulkov.evokammer.core.contract.RoleAllocation
import com.achulkov.evokammer.core.contract.WipTaskClaim
import com.achulkov.evokammer.core.config.ModelSelectionConfig
import com.achulkov.evokammer.core.model.AgentStatus
import com.achulkov.evokammer.core.model.GroupProfile
import com.achulkov.evokammer.core.model.Role
import com.achulkov.evokammer.orchestration.nested.NestedGroupConfig
import com.achulkov.evokammer.orchestration.nested.NestedGroupResult
import com.achulkov.evokammer.orchestration.nested.NestedGroupRunner
import com.achulkov.evokammer.sandbox.network.ContextUrlAllowlistExtractor
import com.achulkov.evokammer.sandbox.shuru.NetworkAllowlistConfig
import com.achulkov.evokammer.sandbox.shuru.ShuruVmManager
import com.achulkov.evokammer.sandbox.shuru.VmConfig
import com.achulkov.evokammer.sandbox.shuru.VmHandle
import com.achulkov.evokammer.substrate.SubstrateDatabaseFactory
import com.achulkov.evokammer.substrate.db.entity.EventLogEntryEntity
import com.achulkov.evokammer.substrate.db.entity.EventTypes
import com.achulkov.evokammer.substrate.db.entity.KnowledgeBaseEntryEntity
import com.achulkov.evokammer.substrate.db.entity.KnowledgeGraphFactEntity
import com.achulkov.evokammer.substrate.db.entity.ToolRegistryEntryEntity
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

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
 * @param modelSelectionRandom RNG used to sample a concrete model from each role pool at spawn time.
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
    private val modelSelectionRandom: Random = Random.Default,
    private val localAgentRunner: (suspend (AgentBootContext, onBudgetLedger: ((BudgetLedger) -> Unit)?) -> Unit)? = null,
    private val nestedGroupRunBlock: (suspend (NestedGroupConfig) -> NestedGroupResult)? = null,
    private val gracePeriodSeconds: Int = 30,
    private val budgetPollingIntervalMs: Long = 5_000L,
    private val ipcPortBase: Int = 10_000
) {
    internal fun networkAllowlistFor(
        role: Role,
        extraAllowedHosts: Set<String> = emptySet()
    ): NetworkAllowlistConfig {
        val baseConfig = when (role) {
            Role.Researcher -> NetworkAllowlistConfig.RESEARCHER
            Role.Builder -> NetworkAllowlistConfig.BUILDER
            Role.Outlier -> NetworkAllowlistConfig.BROAD
            else -> NetworkAllowlistConfig.NO_INTERNET
        }
        if (extraAllowedHosts.isEmpty()) return baseConfig
        return baseConfig.copy(allowedDomains = baseConfig.allowedDomains + extraAllowedHosts)
    }

    private val db get() = substrateDatabaseFactory.get(runId, groupId)

    /** Live agent registry: agentId → (VmHandle/Job, status). */
    private val liveAgents = ConcurrentHashMap<String, LiveAgentEntry>()
    private val activeNestedGroupJobs = ConcurrentHashMap<String, Job>()
    private val refundedBudgetByAgent = ConcurrentHashMap<String, Double>()
    private val ipcPortCounter = AtomicInteger(ipcPortBase)

    private val _agentStates = MutableStateFlow<Map<String, AgentState>>(emptyMap())
    @Suppress("unused") // consumed by UI / Watchdog via the public StateFlow
    val agentStates: StateFlow<Map<String, AgentState>> = _agentStates.asStateFlow()

    private var budgetMonitorJob: Job? = null
    private var runInputArtifacts: List<RunInputArtifact> = emptyList()
    private var runArtifactsMountPath: String? = null
    private var runContextAllowedHosts: Set<String> = emptySet()

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
     * Called when a claimed WiP task is abandoned because its owner exited before the task
     * was canonically completed.
     *
     * Parameters: agentId, taskNodeId, generation.
     */
    var onWipTaskAbandoned: (agentId: String, taskNodeId: String, generation: Int) -> Unit = { _, _, _ -> }

    /**
     * Called periodically with the updated total remaining budget for this group
     * (sum of all live agents' current budget balances).
     */
    var onBudgetChanged: (totalRemainingBudget: Double) -> Unit = { _ -> }

    fun configureRunArtifacts(
        inputArtifacts: List<RunInputArtifact>,
        artifactsMountPath: String?,
        initialContextAllowedHosts: Set<String> = emptySet()
    ) {
        runInputArtifacts = inputArtifacts
        runArtifactsMountPath = artifactsMountPath
        runContextAllowedHosts = initialContextAllowedHosts
    }

    /**
     * Spawns all agents defined in the [allocation].
     *
     * Each agent gets an [AgentBootContext] with its assigned model, role prompt,
     * WiP task claim, and Legacy Protocol (if available).
     *
     * @param allocation The role allocation from the Evolutionary Selector.
     * @param stageDisclosure The current stage disclosure content.
     * @param modelAssignments Map of role name → OpenRouter model string.
     * @param modelPools Map of role name → candidate model IDs for per-agent sampling.
     * @param rolePrompts Map of role name → system prompt.
     * @param scope Coroutine scope for monitoring and in-process agent jobs.
     */
    suspend fun spawnGeneration(
        allocation: RoleAllocation,
        stageDisclosure: String,
        modelAssignments: Map<String, String>,
        modelPools: Map<String, List<String>> = emptyMap(),
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
                        modelAssignment = resolveModelForRole(
                            role = entry.role,
                            modelAssignments = modelAssignments,
                            modelPools = modelPools
                        ),
                        rolePrompt = rolePrompts[entry.role.name] ?: defaultRolePrompt(entry.role),
                        forceOutlier = entry.forceOutlier,
                        claimWipTask = true,
                        allowLegacyProtocol = entry.role.receivesLegacyProtocol && !entry.forceOutlier,
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
        activeNestedGroupJobs.values.toList().forEach { it.cancel() }
        liveAgents.keys.toList().forEach { agentId ->
            initiateKillSequence(agentId, "run_termination")
        }
        activeNestedGroupJobs.values.toList().forEach { nestedJob ->
            runCatching { nestedJob.cancelAndJoin() }
        }
    }

    /**
     * Spawns a special-purpose agent that should not claim a WiP task or inherit Legacy Protocol.
     *
     * Used by oversight roles such as MetaPlanner, which need broad read access but should not
     * consume one of the group's ordinary work claims.
     *
     * @param role The special role to spawn.
     * @param generation The generation number to stamp on the agent.
     * @param budgetPerAgent The agent's allocated budget.
     * @param stageDisclosure The current stage disclosure content.
     * @param modelAssignment Explicit model override for this agent.
     * @param modelAssignments Per-role model assignments from the council or config.
     * @param modelPools Per-role candidate model pools from the council or config.
     * @param rolePrompt The system prompt for the agent.
     * @param scope Coroutine scope that owns the agent lifecycle.
     */
    suspend fun spawnSpecialAgent(
        role: Role,
        generation: Int,
        budgetPerAgent: Double,
        stageDisclosure: String,
        modelAssignment: String? = null,
        modelAssignments: Map<String, String> = emptyMap(),
        modelPools: Map<String, List<String>> = emptyMap(),
        rolePrompt: String,
        scope: CoroutineScope
    ) {
        spawnAgent(
            role = role,
            generation = generation,
            budgetPerAgent = budgetPerAgent,
            stageDisclosure = stageDisclosure,
            modelAssignment = resolveModelForRole(
                role = role,
                modelAssignments = modelAssignments,
                modelPools = modelPools,
                explicitModelAssignment = modelAssignment
            ),
            rolePrompt = rolePrompt,
            forceOutlier = false,
            claimWipTask = false,
            allowLegacyProtocol = false,
            scope = scope
        )
    }

    /** Returns the number of currently live (active) agents. */
    @Suppress("unused") // called by orchestration layer to gate round advancement
    fun getLiveAgentCount(): Int = liveAgents.size

    /** Returns the number of currently active nested-group jobs. */
    fun getLiveNestedGroupCount(): Int = activeNestedGroupJobs.size

    /**
     * Launches a nested inner-group civilisation under this outer group.
     *
     * The nested job is tracked by this manager so `terminateAll()` cancels it together with the
     * parent group's ordinary agents.
     */
    fun spawnNestedGroup(
        config: NestedGroupConfig,
        scope: CoroutineScope,
        onCompleted: suspend (NestedGroupResult) -> Unit = {}
    ): Job {
        require(config.outerGroupId == groupId) {
            "Nested group outerGroupId=${config.outerGroupId} does not match manager groupId=$groupId"
        }
        require(config.outerRunId == runId) {
            "Nested group outerRunId=${config.outerRunId} does not match manager runId=$runId"
        }
        check(activeNestedGroupJobs[config.nestedGroupId]?.isActive != true) {
            "Nested group ${config.nestedGroupId} is already active for group=$groupId run=$runId"
        }

        val nestedJob = scope.launch {
            try {
                val nestedResult = nestedGroupRunBlock?.invoke(config) ?: buildNestedGroupRunner(config).run()
                val rolledUpResult = rollUpNestedGroupResult(nestedResult)
                onCompleted(rolledUpResult)
            } finally {
                activeNestedGroupJobs.remove(config.nestedGroupId)
            }
        }
        activeNestedGroupJobs[config.nestedGroupId] = nestedJob
        return nestedJob
    }

    // ---- Private ----

    private fun buildNestedGroupRunner(config: NestedGroupConfig): NestedGroupRunner =
        NestedGroupRunner(
            config = config,
            shuruVmManager = shuruVmManager,
            substrateDatabaseFactory = substrateDatabaseFactory,
            lifecycleManagerFactory = { nestedRunId, nestedGroupId, nestedOpenRouterApiKey, nestedAgentJarPath ->
                LifecycleManager(
                    runId = nestedRunId,
                    groupId = nestedGroupId,
                    shuruVmManager = shuruVmManager,
                    substrateDatabaseFactory = substrateDatabaseFactory,
                    agentJarPath = nestedAgentJarPath,
                    openRouterApiKey = nestedOpenRouterApiKey,
                    groupProfile = GroupProfile.default(config.profileType),
                    modelSelectionRandom = modelSelectionRandom,
                    localAgentRunner = localAgentRunner,
                    nestedGroupRunBlock = nestedGroupRunBlock,
                    gracePeriodSeconds = gracePeriodSeconds,
                    budgetPollingIntervalMs = budgetPollingIntervalMs,
                    ipcPortBase = ipcPortCounter.getAndAdd(100)
                )
            }
        )

    private suspend fun rollUpNestedGroupResult(result: NestedGroupResult): NestedGroupResult {
        val nestedRunId = nestedRunIdFor(result.nestedGroupId)
        val nestedDb = substrateDatabaseFactory.get(nestedRunId, result.nestedGroupId)

        val importedKbDocumentIds = result.kbDocumentIds.map { sourceId ->
            val source = requireNotNull(nestedDb.knowledgeBaseDao().getById(sourceId)) {
                "Nested KB entry $sourceId was reported by ${result.nestedGroupId} but not found in $nestedRunId"
            }
            val imported = source.asOuterImport()
            db.knowledgeBaseDao().insert(imported)
            imported.id
        }

        val importedKgFactIds = result.kgFactIds.map { sourceId ->
            val source = requireNotNull(nestedDb.knowledgeGraphFactDao().getById(sourceId)) {
                "Nested KG fact $sourceId was reported by ${result.nestedGroupId} but not found in $nestedRunId"
            }
            val imported = source.asOuterImport(
                nestedSourceRef = nestedSourceRef(
                    nestedRunId = nestedRunId,
                    nestedGroupId = result.nestedGroupId,
                    kind = "kg",
                    sourceId = source.id
                )
            )
            db.knowledgeGraphFactDao().insert(imported)
            imported.id
        }

        val importedToolIds = result.promotedToolIds.map { sourceId ->
            val source = requireNotNull(nestedDb.toolRegistryDao().getById(sourceId)) {
                "Nested tool $sourceId was reported by ${result.nestedGroupId} but not found in $nestedRunId"
            }
            val latestOuterVersion = db.toolRegistryDao().getLatestVersionByName(runId, groupId, source.name)
            val imported = source.asOuterImport(latestOuterVersion)
            db.toolRegistryDao().insert(imported)
            imported.id
        }

        emitEvent(
            type = EventTypes.NESTED_GROUP_ROLLUP,
            agentId = null,
            generation = result.generationsCompleted.coerceAtLeast(0),
            payload = buildNestedRollupPayload(
                result = result,
                importedKbDocumentIds = importedKbDocumentIds,
                importedKgFactIds = importedKgFactIds,
                importedToolIds = importedToolIds
            )
        )

        return result.copy(
            importedKbDocumentIds = importedKbDocumentIds,
            importedKgFactIds = importedKgFactIds,
            importedToolIds = importedToolIds
        )
    }

    private fun nestedRunIdFor(nestedGroupId: String): String = "$runId-nested-$nestedGroupId"

    private fun nestedSourceRef(
        nestedRunId: String,
        nestedGroupId: String,
        kind: String,
        sourceId: String
    ): String = "nested://$nestedRunId/$nestedGroupId/$kind/$sourceId"

    private fun KnowledgeBaseEntryEntity.asOuterImport(): KnowledgeBaseEntryEntity =
        copy(
            id = UUID.randomUUID().toString(),
            groupId = this@LifecycleManager.groupId,
            runId = this@LifecycleManager.runId,
            previousVersionId = null
        )

    private fun KnowledgeGraphFactEntity.asOuterImport(nestedSourceRef: String): KnowledgeGraphFactEntity =
        copy(
            id = UUID.randomUUID().toString(),
            groupId = this@LifecycleManager.groupId,
            runId = this@LifecycleManager.runId,
            provenanceJson = appendNestedProvenance(provenanceJson, nestedSourceRef)
        )

    private fun ToolRegistryEntryEntity.asOuterImport(
        latestOuterVersion: ToolRegistryEntryEntity?
    ): ToolRegistryEntryEntity {
        val importedVersion = when {
            latestOuterVersion == null -> version
            latestOuterVersion.version >= version -> latestOuterVersion.version + 1
            else -> version
        }
        return copy(
            id = UUID.randomUUID().toString(),
            version = importedVersion,
            groupId = this@LifecycleManager.groupId,
            runId = this@LifecycleManager.runId,
            previousVersionId = latestOuterVersion
                ?.takeIf { it.version >= version }
                ?.id
        )
    }

    private fun appendNestedProvenance(existingJson: String, nestedSourceRef: String): String {
        val existingEntries = runCatching {
            when (val parsed = Json.parseToJsonElement(existingJson)) {
                is JsonArray -> parsed
                else -> buildJsonArray { add(parsed) }
            }
        }.getOrElse {
            buildJsonArray {
                if (existingJson.isNotBlank() && existingJson != "[]") {
                    add(JsonPrimitive(existingJson))
                }
            }
        }
        return buildJsonArray {
            existingEntries.forEach { add(it) }
            add(JsonPrimitive(nestedSourceRef))
        }.toString()
    }

    private fun buildNestedRollupPayload(
        result: NestedGroupResult,
        importedKbDocumentIds: List<String>,
        importedKgFactIds: List<String>,
        importedToolIds: List<String>
    ): String =
        """{"nestedGroupId":"${result.nestedGroupId}","completedNormally":${result.completedNormally},"budgetConsumed":${result.budgetConsumed},"kbImported":${importedKbDocumentIds.size},"kgImported":${importedKgFactIds.size},"toolsImported":${importedToolIds.size}}"""

    private fun resolveModelForRole(
        role: Role,
        modelAssignments: Map<String, String>,
        modelPools: Map<String, List<String>>,
        explicitModelAssignment: String? = null
    ): String {
        explicitModelAssignment?.let { return it }
        modelPools[role.name]?.takeIf { it.isNotEmpty() }?.let { pool ->
            return pool.random(modelSelectionRandom)
        }
        modelAssignments[role.name]?.let { return it }
        return ModelSelectionConfig.defaultModelFor(role, modelSelectionRandom)
    }

    private suspend fun spawnAgent(
        role: Role,
        generation: Int,
        budgetPerAgent: Double,
        stageDisclosure: String,
        modelAssignment: String,
        rolePrompt: String,
        forceOutlier: Boolean,
        claimWipTask: Boolean,
        allowLegacyProtocol: Boolean,
        scope: CoroutineScope
    ) {
        val agentId = "agent-${role.name.lowercase()}-${UUID.randomUUID().toString().take(8)}"
        val ipcPort = ipcPortCounter.getAndIncrement()

        // Claim WiP task (Outliers never get tasks)
        val wipClaim = if (claimWipTask && !forceOutlier && role != Role.Outlier) {
            db.wipTaskDao().getNextUnclaimed(runId, groupId)?.let { task ->
                val claimed = db.wipTaskDao().claimTask(task.nodeId, runId, groupId, agentId, generation)
                if (claimed > 0) WipTaskClaim(task.nodeId, task.description) else null
            }
        } else null

        // Retrieve Legacy Protocol
        val legacyProtocol = if (allowLegacyProtocol && role.receivesLegacyProtocol && !forceOutlier) {
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

        val artifactsMountHostPath = runArtifactsMountPath?.takeIf { runInputArtifacts.isNotEmpty() }
            ?: if (runInputArtifacts.isNotEmpty()) File(
                substrateDatabaseFactory.dbFilePath(runId, groupId).parentFile?.parentFile?.parentFile
                    ?: error("Could not resolve run root for artifacts"),
                "artifacts"
            ).absolutePath else null
        val promptContextAllowedHosts = ContextUrlAllowlistExtractor.extractHosts(
            rolePrompt,
            stageDisclosure,
            dynamicNorms,
            wipClaim?.description,
            legacyProtocol?.reasoningPosture,
            legacyProtocol?.toolHeuristics,
            legacyProtocol?.failureMap,
            legacyProtocol?.kgHabits,
            runInputArtifacts.joinToString(separator = "\n") { artifact ->
                artifact.textPreview.orEmpty()
            }
        )
        val effectiveAllowedHosts = runContextAllowedHosts + promptContextAllowedHosts

        val bootArtifactsMountPath = when {
            artifactsMountHostPath == null -> null
            localAgentRunner != null -> artifactsMountHostPath
            else -> "/artifacts"
        }

        val bootContext = AgentBootContext(
            agentId = agentId,
            runId = runId,
            groupId = groupId,
            role = role,
            rolePrompt = rolePrompt,
            budgetToken = "token:$agentId:${System.currentTimeMillis()}",
            substrateDbPath = substrateDatabaseFactory.dbFilePath(runId, groupId).absolutePath,
            stagingMountPath = "/staging",
            // In-process agents read artifacts directly from the host filesystem, while
            // Shuru-backed agents receive the VM mount path.
            artifactsMountPath = bootArtifactsMountPath,
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
            allocatedBudget = budgetPerAgent,
            inputArtifacts = runInputArtifacts
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
                } catch (e: BlockingAgentAbortException) {
                    Napier.w(e) { "[$groupId] In-process agent $agentId aborted due to blocking runtime error after ${System.currentTimeMillis() - spawnTimeMs}ms: ${e.message}" }
                    val entry = liveAgents[agentId]
                    if (entry != null) {
                        handleAgentExit(
                            agentId = agentId,
                            entry = entry,
                            wasKilled = true,
                            refundedBudget = entry.budgetLedger?.initialBudget ?: entry.budgetRemaining
                        )
                    }
                } catch (e: Exception) {
                    val durationMs = System.currentTimeMillis() - spawnTimeMs
                    Napier.e(e) { "[$groupId] In-process agent $agentId [${e::class.simpleName}] error after ${durationMs}ms: ${e.message}" }
                    val entry = liveAgents[agentId]
                    if (entry != null) {
                        handleAgentExit(
                            agentId = agentId,
                            entry = entry,
                            wasKilled = true,
                            refundedBudget = entry.budgetLedger?.initialBudget ?: entry.budgetRemaining
                        )
                    }
                }
            }

            liveAgents[agentId] = LiveAgentEntry(
                handle = null,
                job = agentJob,
                status = AgentStatus.Active,
                generation = generation,
                budgetRemaining = budgetPerAgent,
                budgetLedger = null,  // will be set by the runner callback above
                wipTaskClaim = wipClaim
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
                stagingMountHostPath = "$dbDir/staging/$agentId",
                artifactsMountHostPath = artifactsMountHostPath
            )
            val networkConfig = networkAllowlistFor(role, effectiveAllowedHosts)

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
                budgetLedger = null,  // Shuru VM agents manage their own ledger internally
                wipTaskClaim = wipClaim
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
            // Wait for the coroutine to acknowledge cancellation so termination is deterministic.
            entry.job.cancelAndJoin()
            // `cancelAndJoin()` returns only after the job has observed cancellation, but
            // we still finalize the agent here so WIP cleanup does not depend on the
            // coroutine's catch block running before the test scheduler advances.
            liveAgents[agentId]?.let { currentEntry ->
                handleAgentExit(agentId, currentEntry, wasKilled = true)
            }
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

    private suspend fun handleAgentExit(
        agentId: String,
        entry: LiveAgentEntry,
        wasKilled: Boolean,
        refundedBudget: Double = 0.0
    ) {
        withContext(NonCancellable) {
            liveAgents.remove(agentId)
            if (refundedBudget > 0.0) {
                refundedBudgetByAgent[agentId] = refundedBudget
            } else {
                refundedBudgetByAgent.remove(agentId)
            }
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

            val taskClaim = entry.wipTaskClaim
            if (taskClaim != null) {
                runCatching {
                    val released = db.wipTaskDao().abandonTask(
                        nodeId = taskClaim.nodeId,
                        runId = runId,
                        groupId = groupId,
                        agentId = agentId,
                        generation = entry.generation
                    )
                    if (released > 0) {
                        onWipTaskAbandoned(agentId, taskClaim.nodeId, entry.generation)
                        Napier.i { "[$groupId] WiP task ${taskClaim.nodeId} abandoned by $agentId (wasKilled=$wasKilled)" }
                    }
                }.onFailure { e ->
                    Napier.w(e) { "[$groupId] Failed to abandon WiP task ${taskClaim.nodeId} for $agentId" }
                }
            }

            // Emit updated group budget after agent exit
            val totalRemaining = totalRemainingBudget()
            onBudgetChanged(totalRemaining)

            Napier.i { "[$groupId] Agent $agentId exited (wasKilled=$wasKilled, inProcess=${entry.job != null})" }
        }
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

    private fun totalRemainingBudget(): Double =
        liveAgents.values.sumOf { it.budgetLedger?.balance ?: it.budgetRemaining } +
            refundedBudgetByAgent.values.sum()

    private fun buildSpawnPayload(agentId: String, role: Role, generation: Int): String =
        """{"agentId":"$agentId","role":"${role.name}","generation":$generation,"inProcess":${localAgentRunner != null}}"""

    private fun defaultRolePrompt(role: Role): String =
        "You are a ${role.name} agent in the EvoKammer system. Act according to your role."

}

private data class LiveAgentEntry(
    val handle: VmHandle?,          // non-null in Shuru VM mode
    val job: Job?,                  // non-null in in-process mode
    val status: AgentStatus,
    val generation: Int,
    val budgetRemaining: Double,
    val budgetLedger: BudgetLedger?, // non-null once the in-process runner registers it
    val wipTaskClaim: WipTaskClaim?
)

/** Snapshot of an agent's live state (for IPC / UI). */
data class AgentState(
    val agentId: String,
    val status: AgentStatus,
    val generation: Int,
    val budgetRemaining: Double
)
