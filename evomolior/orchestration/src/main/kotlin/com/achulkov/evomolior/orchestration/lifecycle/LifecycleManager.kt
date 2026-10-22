@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.orchestration.lifecycle

import com.achulkov.evomolior.core.config.CodexRuntimePreset

import com.achulkov.evomolior.core.budget.BudgetLedger
import com.achulkov.evomolior.core.contract.ExpectedAgentStopCancellationException
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.contract.BlockingAgentAbortException
import com.achulkov.evomolior.core.contract.AgentBootContext
import com.achulkov.evomolior.core.contract.RunInputArtifact
import com.achulkov.evomolior.core.contract.RoleAllocation
import com.achulkov.evomolior.core.contract.StartingField
import com.achulkov.evomolior.core.contract.AgentTtlPolicy
import com.achulkov.evomolior.core.contract.AgentExecutionPhase
import com.achulkov.evomolior.core.contract.AgentExitRecord
import com.achulkov.evomolior.core.contract.AgentLifecycleClassification
import com.achulkov.evomolior.core.contract.AgentLifecycleRegistration
import com.achulkov.evomolior.core.contract.AgentLifecycleDuty
import com.achulkov.evomolior.core.contract.AgentRuntimeStateEvent
import com.achulkov.evomolior.core.contract.CommitType
import com.achulkov.evomolior.core.contract.PersistExitResult
import com.achulkov.evomolior.core.contract.TtlCheckpointEvent
import com.achulkov.evomolior.core.contract.TtlCheckpointKind
import com.achulkov.evomolior.core.contract.WorkerLeaseAssignment
import com.achulkov.evomolior.core.contract.WorkerAuthorityScope
import com.achulkov.evomolior.core.contract.WorkerView
import com.achulkov.evomolior.core.contract.WipTaskClaim
import com.achulkov.evomolior.core.config.LlmBackend
import com.achulkov.evomolior.core.config.ModelSelectionConfig
import com.achulkov.evomolior.core.model.AgentStatus
import com.achulkov.evomolior.core.model.GroupProfile
import com.achulkov.evomolior.core.model.Role
import com.achulkov.evomolior.core.model.SubmissionType
import com.achulkov.evomolior.orchestration.nested.NestedGroupConfig
import com.achulkov.evomolior.orchestration.nested.NestedGroupResult
import com.achulkov.evomolior.orchestration.nested.NestedGroupRunner
import com.achulkov.evomolior.sandbox.network.ContextUrlAllowlistExtractor
import com.achulkov.evomolior.sandbox.network.SourceAccessDecisionStore
import com.achulkov.evomolior.sandbox.shuru.NetworkAllowlistConfig
import com.achulkov.evomolior.sandbox.shuru.ShuruVmManager
import com.achulkov.evomolior.sandbox.shuru.VmConfig
import com.achulkov.evomolior.sandbox.shuru.VmHandle
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.db.entity.EventLogEntryEntity
import com.achulkov.evomolior.substrate.db.entity.EventTypes
import com.achulkov.evomolior.substrate.db.entity.KnowledgeBaseEntryEntity
import com.achulkov.evomolior.substrate.db.entity.KnowledgeGraphFactEntity
import com.achulkov.evomolior.substrate.db.entity.ToolRegistryEntryEntity
import com.achulkov.evomolior.substrate.tools.ToolMaterializer
import com.achulkov.evomolior.substrate.worker.WorkerExecutionRepository
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.time.Clock

private const val CANONICAL_CONTEXT_MAX_CHARS = 16_000
private const val CANONICAL_CONTEXT_KB_LIMIT = 12
private const val CANONICAL_CONTEXT_CROSS_GROUP_LIMIT = 4
private const val CANONICAL_CONTEXT_KG_LIMIT = 12
private const val CANONICAL_CONTEXT_STAGING_SCAN_LIMIT = 80
private const val CANONICAL_CONTEXT_PROMOTED_LIMIT = 10
private const val CANONICAL_CONTEXT_PLAN_LIMIT = 5
private const val CANONICAL_CONTEXT_WIP_LIMIT = 8
private const val CANONICAL_CONTEXT_ARTIFACT_LIMIT = 5
private const val CANONICAL_CONTEXT_EXCERPT_CHARS = 700
private const val CANONICAL_CONTEXT_ARTIFACT_EXCERPT_CHARS = 1_200
private const val RUNTIME_TRANSITION_PERSISTENCE_ATTEMPTS = 3
private const val RUNTIME_TRANSITION_PERSISTENCE_RETRY_DELAY_MS = 25L
private const val EXIT_FINALIZATION_RETRY_ATTEMPTS = 3
private const val EXIT_FINALIZATION_RETRY_DELAY_MS = 25L
private val CANONICAL_CONTEXT_SYNTHETIC_MARKERS = listOf(
    "AUTO-GENERATED",
    "SUBSTRATE CONTRACT FALLBACK",
    "KG ABSTENTION RATIONALE",
    "TOOL ABSTENTION RATIONALE",
    "UNWRITTEN AGENT OUTPUT SALVAGE"
)

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
 * @param groupProfile The group's cost profile — injected as `EVOMOLIOR_GROUP_PROFILE` env var
 *   in Shuru VMs so the in-VM runner constructs the correct [BudgetConfig].
 * @param modelSelectionRandom RNG used to sample a concrete model from each role pool at spawn time.
 * @param codexRuntimePreset Optional bounded Codex model policy; null preserves legacy selection.
 * @param localAgentRunner Optional suspend function that runs an agent in-process.
 *   The second parameter is an optional callback the runner uses to register the agent's
 *   [BudgetLedger] with this manager, enabling live budget polling. When provided,
 *   bypasses Shuru VM spawning entirely.
 * @param gracePeriodSeconds How long to wait for Death Note after SIGTERM.
 * @param budgetPollingIntervalMs How often to check agent budgets and emit budget change events.
 * @param ipcPortBase Base port for IPC; incremented per agent.
 * @param terminationCommitBarrier Optional control barrier entered after a runtime cell owns one
 * termination request but before exit freezing and process cancellation.
 * @param registrationCommitBarrier Optional control barrier before durable runtime registration.
 */
class LifecycleManager(
    private val runId: String,
    private val groupId: String,
    private val shuruVmManager: ShuruVmManager,
    private val substrateDatabaseFactory: SubstrateDatabaseFactory,
    private val agentJarPath: String,
    private val openRouterApiKey: String,
    private val llmBackend: LlmBackend = LlmBackend.OPENROUTER,
    private val groupProfile: GroupProfile = GroupProfile.Library,
    private val modelSelectionRandom: Random = Random.Default,
    private val localAgentRunner: (suspend (AgentBootContext, onBudgetLedger: ((BudgetLedger) -> Unit)?) -> Unit)? = null,
    private val nestedGroupRunBlock: (suspend (NestedGroupConfig) -> NestedGroupResult)? = null,
    private val gracePeriodSeconds: Int = 30,
    private val budgetPollingIntervalMs: Long = 5_000L,
    private val ipcPortBase: Int = 10_000,
    private val terminationCommitBarrier:
        suspend (agentId: String, cause: AgentTerminationCause) -> Unit = { _, _ -> },
    private val codexRuntimePreset: CodexRuntimePreset? = null,
    private val registrationCommitBarrier: suspend (agentId: String) -> Unit = {},
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

    /** Stable live-agent registry. Mutable runtime state is reduced inside each cell. */
    private val liveAgents = ConcurrentHashMap<String, AgentRuntimeCell>()
    private val inProcessExecutionJobs = ConcurrentHashMap<String, Job>()
    private val exitFinalizationMutexes = ConcurrentHashMap<String, Mutex>()
    private val exitFinalizationPersistenceMutex = Mutex()
    private val runtimeRegistrationMutex = Mutex()
    private val runtimeTransitionPersistenceJobs = ConcurrentHashMap<String, Job>()
    private val pendingRuntimeTransitions =
        ConcurrentHashMap<String, ConcurrentSkipListMap<Long, AgentRuntimeStateEvent>>()
    private val nextRuntimeTransitionRevision = ConcurrentHashMap<String, Long>()
    private val runtimeTransitionPersistenceFailures = ConcurrentHashMap<String, Throwable>()
    private val fatalExitConflicts = ConcurrentHashMap<String, AgentExitIdentityConflictException>()
    private val exitFinalizationRetryJobs = ConcurrentHashMap<String, Job>()
    private val exitFinalizationRetriesInProgress = ConcurrentHashMap.newKeySet<String>()
    private val exitFinalizationRetryAttempts = ConcurrentHashMap<String, Int>()
    private val exitFinalizationFailures = ConcurrentHashMap<String, Throwable>()
    private val exitFinalizationRetryCount = AtomicInteger(0)
    private val deathCallbackDuplicateCount = AtomicInteger(0)
    private val maxExitToQuiescenceMs = java.util.concurrent.atomic.AtomicLong(0L)
    private val runtimeTransitionPersistenceScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeNestedGroupJobs = ConcurrentHashMap<String, Job>()
    private val refundedBudgetByAgent = ConcurrentHashMap<String, Double>()
    // Registry membership and settled totals move atomically, so an exit is never lost or counted twice.
    private val budgetTelemetryLock = Any()
    private data class SettledCredits(val allocated: Double = 0.0, val returned: Double = 0.0)
    private val settledCreditsByGeneration = mutableMapOf<Int, SettledCredits>()
    private var budgetTelemetryRevision = 0L
    private val ipcPortCounter = AtomicInteger(ipcPortBase)
    private var lastTurnTakingImbalanceSignature: String? = null
    private var startingField: StartingField? = null
    private var workerAuthorityFactory:
        (suspend (WorkerAuthorityRequest) -> WorkerAuthorityAssignment)? = null
    private var preferredWipNodeIds: List<String>? = null

    private val _agentStates = MutableStateFlow<Map<String, AgentState>>(emptyMap())
    @Suppress("unused") // consumed by UI / Watchdog via the public StateFlow
    val agentStates: StateFlow<Map<String, AgentState>> = _agentStates.asStateFlow()

    private var budgetMonitorJob: Job? = null
    private var runInputArtifacts: List<RunInputArtifact> = emptyList()
    private var runArtifactsMountPath: String? = null
    private var runContextAllowedHosts: Set<String> = emptySet()
    private var sourceAccessAuditPath: String? = null

    /**
     * Whether this manager uses in-process (coroutine-based) agent execution.
     * When true, Shuru VMs are not required.
     */
    val usesInProcessMode: Boolean get() = localAgentRunner != null

    /**
     * Configures the original run seed that should be propagated into every agent boot context.
     *
     * @param startingField The canonical run seed, or null to clear the current value.
     */
    fun configureStartingField(startingField: StartingField?) {
        this.startingField = startingField
    }

    /**
     * Configures the compiled-production ordinary-worker context and lease authority.
     *
     * When configured, every ordinary spawn fails closed unless this factory returns a valid,
     * redacted assignment. Legacy/offline managers may leave it unset.
     */
    fun configureWorkerAuthority(
        factory: suspend (WorkerAuthorityRequest) -> WorkerAuthorityAssignment
    ) {
        workerAuthorityFactory = factory
    }

    /** Configures this round's deterministic Atlas portfolio as WIP claim priority. */
    fun configureNodePortfolio(nodeIds: List<com.achulkov.evomolior.core.atlas.NodeId>) {
        preferredWipNodeIds = nodeIds.distinct().map { it.value }
    }

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
     * Observes an already-finalized agent exit (normally or killed).
     *
     * Durable exit authority and lease/WiP cleanup complete before this callback. Implementations
     * must not attach required lifecycle cleanup here: notification failures are logged and not
     * retried so an observer receives at most one invocation per agent.
     *
     * Parameters: agentId, wasKilled.
     */
    var onAgentDied: (agentId: String, wasKilled: Boolean) -> Unit = { _, _ -> }

    /**
     * Observes an already-persisted claimed WiP task abandonment.
     *
     * Implementations must not attach required lifecycle cleanup here; database state is
     * authoritative and callback failures are not retried.
     *
     * Parameters: agentId, taskNodeId, generation.
     */
    var onWipTaskAbandoned: (agentId: String, taskNodeId: String, generation: Int) -> Unit = { _, _, _ -> }

    /**
     * Legacy group availability observation: live balances plus policy refunds.
     * This is not cumulative unspent credit or a generation depletion signal.
     * Use [onBudgetTelemetryChanged] for population-aware budget monitoring.
     */
    var onBudgetChanged: (totalRemainingBudget: Double) -> Unit = { _ -> }

    /** Immutable generation observations after spawning, ledger polling, and fully settled exits. */
    var onBudgetTelemetryChanged: (LifecycleBudgetTelemetry) -> Unit = {}

    /**
     * Observes live ledger balances and settled unused credits without changing budget policy.
     *
     * Membership is captured atomically with settlement; each live ledger balance is sampled once.
     * @param generation Generation whose actual allocations should be included.
     * @return Immutable observation; allocated = live + spent + returned credits.
     */
    fun snapshotBudgetTelemetry(generation: Int): LifecycleBudgetTelemetry =
        synchronized(budgetTelemetryLock) {
            val settled = settledCreditsByGeneration[generation] ?: SettledCredits()
            val cells = liveAgents.values.filter { it.generation == generation }
            val liveAllocated = cells.sumOf { it.allocatedBudget }
            val live = cells.sumOf { it.ledger()?.balance ?: it.snapshot().budgetRemaining }
            LifecycleBudgetTelemetry(
                generation = generation,
                allocatedCredits = settled.allocated + liveAllocated,
                liveAllocatedCredits = liveAllocated,
                liveCredits = live,
                spentCredits = settled.allocated - settled.returned + liveAllocated - live,
                returnedCredits = settled.returned,
                liveAgentCount = cells.size,
                revision = ++budgetTelemetryRevision
            )
        }

    private fun emitBudgetTelemetry(generation: Int) {
        val telemetry = snapshotBudgetTelemetry(generation)
        runCatching { onBudgetTelemetryChanged(telemetry) }.onFailure { error ->
            Napier.w(error) { "[$groupId] Budget telemetry callback failed for generation $generation" }
        }
    }

    fun configureRunArtifacts(
        inputArtifacts: List<RunInputArtifact>,
        artifactsMountPath: String?,
        initialContextAllowedHosts: Set<String> = emptySet()
    ) {
        runInputArtifacts = inputArtifacts
        runArtifactsMountPath = artifactsMountPath
        runContextAllowedHosts = initialContextAllowedHosts
        sourceAccessAuditPath = resolveRunRoot()?.let { runRoot ->
            File(runRoot, "control/${SourceAccessDecisionStore.FILE_NAME}").absolutePath
        }
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
        val generationWipNodeIds = generationWipClaimQueue()
        Napier.i { "[$groupId] Spawning generation ${allocation.generation}: ${allocation.totalAgentCount} agents (mode=$mode)" }

        for (entry in allocation.entries) {
            repeat(entry.count) {
                currentCoroutineContext().ensureActive()
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
                        workerAuthorityScope = if (entry.forceOutlier || entry.role == Role.Outlier) {
                            WorkerAuthorityScope.OUTLIER_MANDATE
                        } else {
                            WorkerAuthorityScope.NODE_ASSIGNMENT
                        },
                        allowLegacyProtocol = entry.role.receivesLegacyProtocol && !entry.forceOutlier,
                        generationWipNodeIds = generationWipNodeIds,
                        scope = scope
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Napier.e(e) { "[$groupId] Failed to spawn agent for role ${entry.role}" }
                }
            }
        }

        maybeEmitTurnTakingImbalanceSignal(allocation)

        // Start budget monitoring loop if not already running
        if (budgetMonitorJob?.isActive != true) {
            budgetMonitorJob = scope.launch {
                monitorBudgets()
            }
        }
    }

    /**
     * Freezes this spawn pass's compiled portfolio before any launched agent can release its WIP.
     *
     * An immediately exiting agent may abandon its claim while [spawnGeneration] is still
     * launching peers. Re-reading live WIP in that window would assign the same portfolio node to
     * a surplus worker in the same generation.
     */
    private suspend fun generationWipClaimQueue(): ArrayDeque<String>? {
        preferredWipNodeIds?.let { return ArrayDeque(it) }
        if (workerAuthorityFactory == null) return null
        return db.wipTaskDao()
            .getAll(runId, groupId)
            .asSequence()
            .filter { it.status == "unclaimed" }
            .sortedWith(compareBy({ it.lastChangedGeneration }, { it.nodeId }))
            .map { it.nodeId }
            .toCollection(ArrayDeque())
    }

    /**
     * Gracefully terminates all live agents (SIGTERM then SIGKILL after grace period
     * for Shuru VMs, or coroutine cancellation for in-process agents).
     */
    suspend fun terminateAll() {
        budgetMonitorJob?.cancelAndJoin()
        budgetMonitorJob = null
        activeNestedGroupJobs.values.toList().forEach { it.cancel() }
        // Registration must finish publishing execution ownership before shutdown snapshots it.
        val registeredAgentIds = runtimeRegistrationMutex.withLock { liveAgents.keys.toList() }
        registeredAgentIds.forEach { agentId ->
            initiateKillSequence(agentId, AgentTerminationCause.RUN_TERMINATION)
        }
        activeNestedGroupJobs.values.toList().forEach { nestedJob ->
            runCatching { nestedJob.cancelAndJoin() }
        }
    }

    /**
     * Waits for actual runner completion and durable publication accounting after shutdown.
     *
     * Callers must stop spawning before calling [terminateAll] and this barrier. This does not
     * terminate agents or manufacture settlement for cancellation-resistant providers. A timeout
     * leaves ongoing execution and finalization intact, and publication must remain blocked.
     * Caller cancellation and [kotlinx.coroutines.TimeoutCancellationException] propagate.
     *
     * @param timeoutMillis Positive maximum drain wait, including persistence and cleanup.
     * @return Quiescent durable accounting for this group.
     */
    suspend fun awaitQuiescence(timeoutMillis: Long = 30_000L): AgentPublicationAccounting {
        require(timeoutMillis > 0L) { "Quiescence timeout must be positive" }
        return withTimeout(timeoutMillis) {
            while (true) {
                // Join only from the cancellable waiter, never from non-cancellable finalization.
                // Keep jobs tracked past live-cell removal so trailing lifecycle callbacks finish.
                inProcessExecutionJobs.values.toList().forEach { it.join() }
                val accounting = publicationAccounting()
                if (
                    accounting.isQuiescent &&
                    inProcessExecutionJobs.isEmpty() &&
                    exitFinalizationRetryJobs.isEmpty() &&
                    exitFinalizationRetriesInProgress.isEmpty()
                ) {
                    return@withTimeout accounting
                }
                // Finalization retries own their work independently of the stopped budget monitor.
                delay(50L)
            }
            @Suppress("UNREACHABLE_CODE")
            error("Quiescence loop exited without accounting")
        }
    }

    /**
     * Terminates one live agent while leaving the rest of the generation active.
     *
     * This is the targeted production control used by Watchdog/chaos diagnostics. Agent exit
     * bookkeeping remains non-cancellable so already committed substrate state is preserved.
     *
     * @param agentId Live agent to terminate.
     * @param reason Auditable bounded reason recorded in lifecycle logs.
     * @return `true` when a live agent was found and its kill sequence was initiated.
     */
    suspend fun terminateAgent(agentId: String, reason: String = "targeted_termination"): Boolean {
        if (!liveAgents.containsKey(agentId)) return false
        initiateKillSequence(
            agentId = agentId,
            cause = AgentTerminationCause.TARGETED_TERMINATION,
            detail = reason.take(120)
        )
        return true
    }

    /**
     * Requests the explicitly expected stop of a writer after its candidate is durably staged.
     *
     * This changes cancellation telemetry only; the exit remains killed and strict cleanup,
     * termination authority, and Death Note requirements are unchanged.
     * @param agentId Writer whose candidate was staged by the caller.
     * @return Whether a live agent was found for the stop request.
     */
    suspend fun terminateAgentAfterCandidateStaged(agentId: String): Boolean {
        if (!liveAgents.containsKey(agentId)) return false
        initiateKillSequence(
            agentId = agentId,
            cause = AgentTerminationCause.TARGETED_TERMINATION,
            detail = "candidate_staged",
            cancellationCause = ExpectedAgentStopCancellationException("candidate_staged")
        )
        return true
    }

    /**
     * Waits for one agent's execution and strict lifecycle cleanup without stopping other agents.
     *
     * A timeout leaves ownership and exit accounting intact; a durable exit alone is insufficient.
     * @param agentId Agent whose lifecycle ownership must be released.
     * @param timeoutMs Maximum wait duration.
     */
    suspend fun awaitAgentQuiescence(agentId: String, timeoutMs: Long) {
        require(timeoutMs > 0) { "Agent quiescence timeout must be positive" }
        withTimeout(timeoutMs) {
            while (true) {
                inProcessExecutionJobs[agentId]?.join()
                fatalExitConflicts[agentId]?.let { throw it }
                if (
                    !liveAgents.containsKey(agentId) &&
                    !inProcessExecutionJobs.containsKey(agentId) &&
                    !exitFinalizationRetryJobs.containsKey(agentId) &&
                    agentId !in exitFinalizationRetriesInProgress
                ) return@withTimeout
                delay(50L)
            }
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
            workerAuthorityScope = if (role == Role.PerspectiveTaker) {
                WorkerAuthorityScope.PERSPECTIVE_TAKER
            } else {
                WorkerAuthorityScope.SPECIAL_PURPOSE
            },
            allowLegacyProtocol = false,
            scope = scope
        )
    }

    /** Returns the number of currently live (active) agents. */
    @Suppress("unused") // called by orchestration layer to gate round advancement
    fun getLiveAgentCount(): Int = liveAgents.size

    /**
     * Returns a point-in-time copy of the remaining budgets for currently live agents.
     *
     * The snapshot is read from the public [agentStates] StateFlow so callers observe one
     * thread-safe state publication rather than iterating mutable lifecycle registries.
     *
     * @return Immutable agent-id to remaining-budget snapshot.
     */
    fun snapshotLiveAgentBudgets(): Map<String, Double> =
        agentStates.value.entries.associate { (agentId, state) ->
            agentId to state.budgetRemaining
        }

    /** Returns the number of currently active nested-group jobs. */
    fun getLiveNestedGroupCount(): Int = activeNestedGroupJobs.size

    /** Returns exact durable and live accounting for publication quiescence. */
    suspend fun publicationAccounting(): AgentPublicationAccounting {
        awaitRuntimeTransitionPersistence()
        val repository = WorkerExecutionRepository(db)
        val registrationsByAgentId = repository.registrations(runId, groupId)
            .filter { it.classification == AgentLifecycleClassification.NON_DAEMON }
            .associateBy { it.agentId.value }
        val registered = registrationsByAgentId.keys
        val exited = repository.exits(runId, groupId)
            .mapTo(linkedSetOf()) { it.agentId.value }
        val live = liveAgents.entries
            .filter { it.value.classification == AgentLifecycleClassification.NON_DAEMON }
            .mapTo(linkedSetOf()) { it.key }
        val latestRuntimeStates = registered.associateWith { registeredAgentId ->
            val registration = registrationsByAgentId.getValue(registeredAgentId)
            repository.runtimeStateEvents(
                runId,
                groupId,
                registeredAgentId,
                registration.generation
            ).lastOrNull()?.state
        }
        val orphanedUnsettled = latestRuntimeStates
            .filter { (registeredAgentId, state) ->
                registeredAgentId !in live &&
                    state?.exitPersisted == true &&
                    !state.executionSettled
            }
            .keys
        return AgentPublicationAccounting(
            registeredNonDaemonAgentIds = registered,
            exitedNonDaemonAgentIds = exited intersect registered,
            liveNonDaemonAgentIds = live,
            unaccountedNonDaemonAgentIds = registered - exited,
            liveNestedGroupCount = activeNestedGroupJobs.size,
            pendingRuntimeTransitionCount = pendingRuntimeTransitions.values.sumOf { it.size },
            runtimeTransitionFailureCount = runtimeTransitionPersistenceFailures.size,
            exitIdentityConflictCount = fatalExitConflicts.size,
            exitFinalizationFailureCount = exitFinalizationFailures.size,
            orphanedUnsettledAgentIds = orphanedUnsettled
        )
    }

    /** Exports the blueprint's lifecycle reconciliation and quiescence metrics. */
    suspend fun lifecycleReconciliationMetrics(): LifecycleReconciliationMetrics {
        val accounting = publicationAccounting()
        val repository = WorkerExecutionRepository(db)
        val registered = repository.registrations(runId, groupId)
        val exits = repository.exits(runId, groupId)
        val registeredIds = registered.mapTo(linkedSetOf()) { it.agentId.value }
        val exitIds = exits.mapTo(linkedSetOf()) { it.agentId.value }
        val persistedQuiescenceDurations = registered.mapNotNull { registration ->
            val cleanup = repository.runtimeStateEvents(
                runId,
                groupId,
                registration.agentId.value,
                registration.generation
            ).lastOrNull { it.state.cleanupComplete } ?: return@mapNotNull null
            val exit = exits.firstOrNull { it.agentId == registration.agentId }
                ?: return@mapNotNull null
            (cleanup.occurredAt.toEpochMilliseconds() - exit.exitedAt.toEpochMilliseconds())
                .coerceAtLeast(0L)
        }
        return LifecycleReconciliationMetrics(
            exitIdentityConflictCount = fatalExitConflicts.size,
            exitFinalizationRetryCount = exitFinalizationRetryCount.get(),
            registeredWithoutExitCount = (registeredIds - exitIds).size,
            exitWithoutRegisteredAgentCount = (exitIds - registeredIds).size,
            liveExecutionAfterArchiveBarrier = accounting.liveNonDaemonAgentIds.size +
                accounting.orphanedUnsettledAgentIds.size,
            deathCallbackDuplicateCount = deathCallbackDuplicateCount.get(),
            unsettledCostReservationCount = liveAgents.values.count {
                !it.snapshot().costSettled
            },
            maxExitToQuiescenceMs = (
                persistedQuiescenceDurations + maxExitToQuiescenceMs.get()
                ).maxOrNull()
        )
    }

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
        lateinit var nestedJob: Job
        nestedJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val nestedResult = nestedGroupRunBlock?.invoke(config) ?: buildNestedGroupRunner(config).run()
                val rolledUpResult = rollUpNestedGroupResult(nestedResult)
                onCompleted(rolledUpResult)
            } finally {
                activeNestedGroupJobs.remove(config.nestedGroupId, nestedJob)
            }
        }

        val registered = synchronized(activeNestedGroupJobs) {
            if (activeNestedGroupJobs.containsKey(config.nestedGroupId)) {
                false
            } else {
                activeNestedGroupJobs[config.nestedGroupId] = nestedJob
                true
            }
        }
        if (!registered) {
            nestedJob.cancel()
            error("Nested group ${config.nestedGroupId} is already active for group=$groupId run=$runId")
        }

        nestedJob.start()
        return nestedJob
    }

    // ---- Private ----

    private fun newRuntimeCell(
        agentId: String,
        generation: Int,
        role: Role,
        allocatedBudget: Double,
        wipTaskClaim: WipTaskClaim?,
        workerAuthority: WorkerAuthorityAssignment?,
        classification: AgentLifecycleClassification,
        spawnedAtMs: Long
    ): AgentRuntimeCell = AgentRuntimeCell(
        runId = runId,
        groupId = groupId,
        agentId = agentId,
        generation = generation,
        role = role,
        allocatedBudget = allocatedBudget,
        wipTaskClaim = wipTaskClaim,
        workerAuthority = workerAuthority,
        classification = classification,
        spawnedAtMs = spawnedAtMs,
        transitionSink = ::persistRuntimeTransition,
        duty = lifecycleDutyFor(role),
        workspaceId = artifactWorkspaceIdentity(role)?.first,
        workspaceSourceHash = artifactWorkspaceIdentity(role)?.second
    )

    private fun lifecycleDutyFor(role: Role): AgentLifecycleDuty =
        if (role == Role.Builder) {
            AgentLifecycleDuty.ARTIFACT_OWNER
        } else {
            AgentLifecycleDuty.NONE
        }

    private fun markArtifactOwnerWorkspaceUse(cell: AgentRuntimeCell) {
        if (cell.duty != AgentLifecycleDuty.ARTIFACT_OWNER) return
        cell.markArtifactWorkspaceUsed(
            workspaceId = requireNotNull(cell.workspaceId) {
                "ArtifactOwner runtime cell lacks its registered workspace identity"
            },
            workspaceSourceHash = requireNotNull(cell.workspaceSourceHash) {
                "ArtifactOwner runtime cell lacks its registered workspace source hash"
            },
        )
    }

    private fun artifactWorkspaceIdentity(role: Role): Pair<String, String>? {
        if (role != Role.Builder) return null
        val workspaceId = "artifact-workspace:$runId:$groupId"
        val sourceHash = AtlasHashing.hashText(
            "artifact-workspace-source:$runId:$groupId:${runArtifactsMountPath.orEmpty()}"
        )
        return workspaceId to sourceHash
    }

    private fun persistRuntimeTransition(event: AgentRuntimeStateEvent) {
        pendingRuntimeTransitions
            .computeIfAbsent(event.agentId.value) { ConcurrentSkipListMap() }[event.revision] = event
        scheduleRuntimeTransitionDrain(event.agentId.value)
    }

    private fun scheduleRuntimeTransitionDrain(agentId: String) {
        lateinit var persistenceJob: Job
        persistenceJob = runtimeTransitionPersistenceScope.launch(start = CoroutineStart.LAZY) {
            try {
                drainRuntimeTransitions(agentId)
            } finally {
                runtimeTransitionPersistenceJobs.remove(agentId, persistenceJob)
                if (
                    pendingRuntimeTransitions[agentId]?.isNotEmpty() == true &&
                    !hasBlockedRuntimeTransition(agentId)
                ) {
                    scheduleRuntimeTransitionDrain(agentId)
                }
            }
        }
        if (runtimeTransitionPersistenceJobs.putIfAbsent(agentId, persistenceJob) == null) {
            persistenceJob.start()
        } else {
            persistenceJob.cancel()
        }
    }

    private suspend fun awaitRuntimeTransitionPersistence() {
        while (true) {
            runtimeTransitionPersistenceJobs.values.toList().forEach { it.join() }
            val schedulableAgentIds = pendingRuntimeTransitions.entries
                .filter { (_, events) -> events.isNotEmpty() }
                .map { it.key }
                .filterNot(::hasBlockedRuntimeTransition)
            if (schedulableAgentIds.isEmpty()) return
            schedulableAgentIds.forEach(::scheduleRuntimeTransitionDrain)
        }
    }

    private fun hasBlockedRuntimeTransition(agentId: String): Boolean {
        val firstPending = pendingRuntimeTransitions[agentId]?.firstEntry()?.value ?: return false
        return runtimeTransitionPersistenceFailures.containsKey(firstPending.eventId)
    }

    private fun runtimeTransitionFailureFor(agentId: String): Throwable? =
        pendingRuntimeTransitions[agentId]
            ?.values
            ?.firstNotNullOfOrNull { event ->
                runtimeTransitionPersistenceFailures[event.eventId]
            }

    private suspend fun drainRuntimeTransitions(agentId: String) {
        val pending = pendingRuntimeTransitions[agentId] ?: return
        while (true) {
            val nextRevision = nextRuntimeTransitionRevision.getOrPut(agentId) { 1L }
            val event = pending[nextRevision] ?: return
            var persisted = false
            var failure: Throwable? = null
            repeat(RUNTIME_TRANSITION_PERSISTENCE_ATTEMPTS) { attempt ->
                if (!persisted) {
                    runCatching {
                        WorkerExecutionRepository(db).appendRuntimeStateEvent(event)
                    }.onSuccess {
                        persisted = true
                        failure = null
                        runtimeTransitionPersistenceFailures.remove(event.eventId)
                    }.onFailure { error ->
                        failure = error
                        if (attempt + 1 < RUNTIME_TRANSITION_PERSISTENCE_ATTEMPTS) {
                            delay(RUNTIME_TRANSITION_PERSISTENCE_RETRY_DELAY_MS)
                        }
                    }
                }
            }
            failure?.let { error ->
                runtimeTransitionPersistenceFailures[event.eventId] = error
                Napier.e(error) {
                    "[$groupId] Failed to persist runtime transition ${event.eventId}"
                }
                return
            }
            pending.remove(nextRevision, event)
            nextRuntimeTransitionRevision[agentId] = nextRevision + 1
        }
    }

    private fun buildNestedGroupRunner(config: NestedGroupConfig): NestedGroupRunner =
        NestedGroupRunner(
            config = config,
            shuruVmManager = shuruVmManager,
            substrateDatabaseFactory = substrateDatabaseFactory,
            lifecycleManagerFactory = { nestedRunId, nestedGroupId, nestedOpenRouterApiKey, nestedAgentJarPath ->
                buildNestedLifecycleManager(
                    config = config,
                    nestedRunId = nestedRunId,
                    nestedGroupId = nestedGroupId,
                    nestedOpenRouterApiKey = nestedOpenRouterApiKey,
                    nestedAgentJarPath = nestedAgentJarPath
                )
            }
        )

    internal fun buildNestedLifecycleManager(
        config: NestedGroupConfig,
        nestedRunId: String = nestedRunIdFor(config.nestedGroupId),
        nestedGroupId: String = config.nestedGroupId,
        nestedOpenRouterApiKey: String = config.openRouterApiKey,
        nestedAgentJarPath: String = config.agentJarPath
    ): LifecycleManager =
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
            ipcPortBase = ipcPortCounter.getAndAdd(100),
            terminationCommitBarrier = terminationCommitBarrier
        ).also { nestedLifecycleManager ->
            nestedLifecycleManager.configureStartingField(startingField)
        }

    private fun resolveRunRoot(): File? =
        substrateDatabaseFactory.dbFilePath(runId, groupId)
            .parentFile
            ?.parentFile
            ?.parentFile

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
            ToolMaterializer(outerToolsRoot()).materialize(imported)
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

    private fun outerToolsRoot(): File =
        substrateDatabaseFactory.dbFilePath(runId, groupId)
            .parentFile
            ?.resolve("tools")
            ?: error("Missing tools mount host path for run=$runId group=$groupId")

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

    internal fun resolveModelForRole(
        role: Role,
        modelAssignments: Map<String, String>,
        modelPools: Map<String, List<String>>,
        explicitModelAssignment: String? = null
    ): String {
        explicitModelAssignment?.let { modelId ->
            require(codexRuntimePreset == null || modelId in codexRuntimePreset.supportedModelIds) {
                "Explicit model assignment exceeds preset authority"
            }
            return modelId
        }
        codexRuntimePreset?.let { preset ->
            val candidates = modelPools[role.name]?.takeIf { it.isNotEmpty() }
                ?: modelAssignments[role.name]?.let(::listOf)
                ?: preset.modelPool(role)
            return preset.selectModel(role, candidates, modelSelectionRandom)
        }
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
        workerAuthorityScope: WorkerAuthorityScope,
        allowLegacyProtocol: Boolean,
        generationWipNodeIds: ArrayDeque<String>? = null,
        scope: CoroutineScope,
        classification: AgentLifecycleClassification =
            AgentLifecycleClassification.NON_DAEMON
    ) {
        val agentId = "agent-${role.name.lowercase()}-${UUID.randomUUID().toString().take(8)}"
        val ipcPort = ipcPortCounter.getAndIncrement()

        // Claim WiP task (Outliers never get tasks)
        val wipClaim = if (claimWipTask && !forceOutlier && role != Role.Outlier) {
            if (generationWipNodeIds == null) {
                db.wipTaskDao().getNextUnclaimed(runId, groupId)?.let { task ->
                    val claimed = db.wipTaskDao()
                        .claimTask(task.nodeId, runId, groupId, agentId, generation)
                    if (claimed > 0) WipTaskClaim(task.nodeId, task.description) else null
                }
            } else {
                var claim: WipTaskClaim? = null
                while (claim == null && generationWipNodeIds.isNotEmpty()) {
                    val nodeId = generationWipNodeIds.removeFirst()
                    val task = db.wipTaskDao().getByNodeId(nodeId, runId, groupId)
                        ?.takeIf { it.status == "unclaimed" }
                        ?: continue
                    val claimed = db.wipTaskDao()
                        .claimTask(task.nodeId, runId, groupId, agentId, generation)
                    if (claimed > 0) {
                        claim = WipTaskClaim(task.nodeId, task.description)
                    }
                }
                claim
            }
        } else null
        if (workerAuthorityFactory != null &&
            workerAuthorityScope == WorkerAuthorityScope.NODE_ASSIGNMENT &&
            wipClaim == null &&
            role != Role.Builder
        ) {
            Napier.i {
                "[$groupId] Skipping ${role.name} spawn because the compiled worker portfolio " +
                    "has no claimable node authority"
            }
            return
        }

        // Retrieve Legacy Protocol
        val legacyProtocol = if (allowLegacyProtocol && role.receivesLegacyProtocol && !forceOutlier) {
            db.legacyProtocolDao().getLatestForRole(runId, groupId, role.name, generation)?.let { p ->
                com.achulkov.evomolior.core.contract.LegacyProtocolContext(
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

        val workerAuthority = if (
            role.isOrdinaryWorkerRole &&
                !(role == Role.Builder && wipClaim == null)
        ) {
            workerAuthorityFactory?.let { factory ->
                runCatching {
                    val assignment = factory(
                        WorkerAuthorityRequest(
                            agentId = agentId,
                            role = role,
                            generation = generation,
                            wipTaskClaim = wipClaim
                        )
                    )
                    validateWorkerAuthorityAssignment(
                        role = role,
                        scope = workerAuthorityScope,
                        wipTaskClaim = wipClaim,
                        assignment = assignment
                    )
                    assignment
                }.getOrElse { error ->
                    wipClaim?.let { claim ->
                        db.wipTaskDao().abandonTask(
                            nodeId = claim.nodeId,
                            runId = runId,
                            groupId = groupId,
                            agentId = agentId,
                            generation = generation
                        )
                    }
                    onSpawnFailed(
                        agentId,
                        role,
                        IllegalStateException("Worker authority allocation failed", error)
                    )
                    Napier.e(error) {
                        "[$groupId] Worker authority allocation failed for $agentId (${role.name})"
                    }
                    return
                }
            }
        } else {
            null
        }
        val usesWorkerAuthority = workerAuthority != null
        val effectiveWipClaim = if (usesWorkerAuthority) {
            workerAuthority?.workerView?.assignedNode?.let { assigned ->
                WipTaskClaim(assigned.nodeId.value, assigned.atomicQuestion)
            }
        } else {
            wipClaim
        }
        val effectiveStageDisclosure = if (usesWorkerAuthority) {
            "Use only the hash-bound REDACTED WORKER VIEW supplied in this boot context."
        } else {
            stageDisclosure
        }
        val effectiveInputArtifacts = if (usesWorkerAuthority) {
            runInputArtifacts.map { it.copy(textPreview = null) }
        } else {
            runInputArtifacts
        }
        val effectiveLegacyProtocol = if (role == Role.Outlier) null else legacyProtocol

        val artifactsMountHostPath = runArtifactsMountPath?.takeIf { effectiveInputArtifacts.isNotEmpty() }
            ?: if (effectiveInputArtifacts.isNotEmpty()) File(
                substrateDatabaseFactory.dbFilePath(runId, groupId).parentFile?.parentFile?.parentFile
                    ?: error("Could not resolve run root for artifacts"),
                "artifacts"
            ).absolutePath else null
        val promptContextAllowedHosts = ContextUrlAllowlistExtractor.extractHosts(
            rolePrompt,
            effectiveStageDisclosure,
            dynamicNorms,
            effectiveWipClaim?.description,
            effectiveLegacyProtocol?.reasoningPosture,
            effectiveLegacyProtocol?.toolHeuristics,
            effectiveLegacyProtocol?.failureMap,
            effectiveLegacyProtocol?.kgHabits,
            effectiveInputArtifacts.joinToString(separator = "\n") { artifact ->
                artifact.textPreview.orEmpty()
            }
        )
        val dynamicAllowedHosts = sourceAccessAuditPath
            ?.let { path -> SourceAccessDecisionStore(File(path)).approvedHosts() }
            .orEmpty()
        val effectiveAllowedHosts = runContextAllowedHosts + promptContextAllowedHosts + dynamicAllowedHosts

        val bootArtifactsMountPath = when {
            artifactsMountHostPath == null -> null
            localAgentRunner != null -> artifactsMountHostPath
            else -> "/artifacts"
        }
        val canonicalWorkingContext = if (usesWorkerAuthority) null else buildCanonicalWorkingContext()

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
            wipTaskClaim = effectiveWipClaim,
            legacyProtocol = effectiveLegacyProtocol,
            stageDisclosure = effectiveStageDisclosure,
            modelAssignment = modelAssignment,
            ipcBaseUrl = "http://10.0.0.1:$ipcPort",
            generation = generation,
            gracePeriodSeconds = gracePeriodSeconds,
            dynamicNorms = dynamicNorms,
            startingField = if (usesWorkerAuthority) null else startingField,
            workerView = workerAuthority?.workerView,
            workerAuthorityScope = workerAuthorityScope,
            assignedLease = workerAuthority?.assignedLease,
            ttlPolicy = workerAuthority?.ttlPolicy,
            sourceAllowedHosts = effectiveAllowedHosts.toList().sorted(),
            sourceAccessAuditPath = sourceAccessAuditPath,
            canonicalWorkingContext = canonicalWorkingContext,
            textOnlySubmissionType = when (role) {
                Role.Connector -> SubmissionType.DISPATCH
                Role.Planner, Role.MetaPlanner -> SubmissionType.PLAN_UPDATE
                else -> null
            },
            // Carry the exact per-agent allocation so both in-process and Shuru runners
            // initialise BudgetLedger with the amount the selector intended.
            allocatedBudget = budgetPerAgent,
            inputArtifacts = effectiveInputArtifacts
        ).let { context ->
            if (usesWorkerAuthority) context.requireValidWorkerAuthority() else context
        }

        if (localAgentRunner != null) {
            // ── In-process mode: run agent as a coroutine ────────────────────
            val spawnTimeMs = System.currentTimeMillis()
            val agentJob = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    Napier.i { "[$groupId] In-process agent $agentId (${role.name}) starting — model=$modelAssignment budget=$budgetPerAgent" }
                    // Pass a callback so the runner can register the BudgetLedger for live polling
                    localAgentRunner.invoke(bootContext) { ledger ->
                        liveAgents[agentId]?.attachBudgetLedger(ledger)
                        Napier.d { "[$groupId] Agent $agentId registered BudgetLedger (initialBudget=${ledger.initialBudget})" }
                    }
                    val durationMs = System.currentTimeMillis() - spawnTimeMs
                    Napier.i { "[$groupId] In-process agent $agentId completed normally in ${durationMs}ms" }
                    val entry = markInProcessExecutionFinished(agentId)
                    if (entry != null) {
                        handleAgentExit(
                            agentId,
                            wasKilled = entry.snapshot().exitRecord?.wasKilled ?: false
                        )
                    }
                } catch (e: CancellationException) {
                    Napier.i { "[$groupId] In-process agent $agentId was cancelled after ${System.currentTimeMillis() - spawnTimeMs}ms" }
                    val entry = markInProcessExecutionFinished(agentId)
                    if (entry != null) {
                        handleAgentExit(
                            agentId,
                            wasKilled = entry.snapshot().exitRecord?.wasKilled ?: true
                        )
                    }
                    throw e // propagate to properly cancel the coroutine
                } catch (e: AgentExitIdentityConflictException) {
                    throw e
                } catch (e: BlockingAgentAbortException) {
                    Napier.w(e) { "[$groupId] In-process agent $agentId aborted due to blocking runtime error after ${System.currentTimeMillis() - spawnTimeMs}ms: ${e.message}" }
                    val entry = markInProcessExecutionFinished(agentId)
                    if (entry != null) {
                        val state = entry.snapshot()
                        handleAgentExit(
                            agentId = agentId,
                            wasKilled = state.exitRecord?.wasKilled ?: true,
                            refundedBudget = entry.ledger()?.initialBudget ?: state.budgetRemaining
                        )
                    }
                } catch (e: Exception) {
                    val durationMs = System.currentTimeMillis() - spawnTimeMs
                    Napier.e(e) { "[$groupId] In-process agent $agentId [${e::class.simpleName}] error after ${durationMs}ms: ${e.message}" }
                    val entry = markInProcessExecutionFinished(agentId)
                    if (entry != null) {
                        val state = entry.snapshot()
                        handleAgentExit(
                            agentId = agentId,
                            wasKilled = state.exitRecord?.wasKilled ?: true,
                            refundedBudget = entry.ledger()?.initialBudget ?: state.budgetRemaining
                        )
                    }
                }
            }

            try {
                // Durable registration and published execution ownership form one cancellation
                // boundary. Shutdown must never observe a partially registered runtime cell.
                withContext(NonCancellable) {
                    runtimeRegistrationMutex.withLock {
                        val runtimeCell = newRuntimeCell(
                            agentId = agentId,
                            generation = generation,
                            role = role,
                            allocatedBudget = budgetPerAgent,
                            wipTaskClaim = effectiveWipClaim,
                            workerAuthority = workerAuthority,
                            classification = classification,
                            spawnedAtMs = spawnTimeMs
                        )
                        registrationCommitBarrier(agentId)
                        WorkerExecutionRepository(db).registerAgent(
                            AgentLifecycleRegistration(
                                registrationId = "registration:$runId:$groupId:$agentId",
                                runId = runId,
                                groupId = com.achulkov.evomolior.core.atlas.AtlasGroupId(groupId),
                                agentId = com.achulkov.evomolior.core.atlas.AtlasAgentId(agentId),
                                role = role.name,
                                generation = generation,
                                classification = classification,
                                registeredAt = kotlin.time.Instant.fromEpochMilliseconds(spawnTimeMs),
                                duty = lifecycleDutyFor(role),
                                workspaceId = artifactWorkspaceIdentity(role)?.first,
                                workspaceSourceHash = artifactWorkspaceIdentity(role)?.second
                            ).withComputedHash()
                        )
                        runtimeCell.confirmRegistration()
                        runtimeCell.attachExecutionJob(agentJob)
                        markArtifactOwnerWorkspaceUse(runtimeCell)
                        synchronized(budgetTelemetryLock) {
                            check(liveAgents.putIfAbsent(agentId, runtimeCell) == null) {
                                "Agent runtime identity already registered: $agentId"
                            }
                        }
                        inProcessExecutionJobs[agentId] = agentJob
                        agentJob.invokeOnCompletion {
                            // A cancelled lazy job never enters its body. Install fallback
                            // settlement only after its registered cell is visible.
                            if (!runtimeCell.snapshot().executionSettled) {
                                val cleanupJob = runtimeTransitionPersistenceScope.launch(
                                    start = CoroutineStart.LAZY,
                                ) {
                                    markInProcessExecutionFinished(agentId)?.let { completedEntry ->
                                        handleAgentExit(
                                            agentId,
                                            wasKilled = completedEntry.snapshot().exitRecord?.wasKilled
                                                ?: agentJob.isCancelled
                                        )
                                    }
                                }
                                inProcessExecutionJobs.replace(agentId, agentJob, cleanupJob)
                                cleanupJob.invokeOnCompletion {
                                    inProcessExecutionJobs.remove(agentId, cleanupJob)
                                }
                                cleanupJob.start()
                            } else {
                                inProcessExecutionJobs.remove(agentId, agentJob)
                            }
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                updateAgentStates()
                maybeEmitTurnTakingImbalanceSignal()
                emitEvent(
                    EventTypes.AGENT_SPAWN,
                    agentId,
                    generation,
                    buildSpawnPayload(agentId, role, generation),
                )
                onAgentSpawned(agentId, role, modelAssignment)
                emitBudgetTelemetry(generation)
                Napier.i {
                    "[$groupId] Launched in-process agent: $agentId (${role.name}) model=$modelAssignment"
                }
                agentJob.start()
            } catch (failure: Throwable) {
                agentJob.cancel()
                throw failure
            }
            yield()

        } else {
            // ── Shuru VM mode: spawn via CLI ──────────────────────────────────
            val dbDir = substrateDatabaseFactory.dbFilePath(runId, groupId).parentFile
            val mountConfig = com.achulkov.evomolior.sandbox.shuru.FilesystemMountConfig(
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
                llmBackend = llmBackend,
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

            val runtimeCell = newRuntimeCell(
                agentId = agentId,
                generation = generation,
                role = role,
                allocatedBudget = budgetPerAgent,
                wipTaskClaim = effectiveWipClaim,
                workerAuthority = workerAuthority,
                classification = classification,
                spawnedAtMs = System.currentTimeMillis()
            )
            synchronized(budgetTelemetryLock) {
                check(liveAgents.putIfAbsent(agentId, runtimeCell) == null) {
                    "Agent runtime identity already registered: $agentId"
                }
            }
            val registeredAt = Clock.System.now()
            WorkerExecutionRepository(db).registerAgent(
                AgentLifecycleRegistration(
                    registrationId = "registration:$runId:$groupId:$agentId",
                    runId = runId,
                    groupId = com.achulkov.evomolior.core.atlas.AtlasGroupId(groupId),
                    agentId = com.achulkov.evomolior.core.atlas.AtlasAgentId(agentId),
                    role = role.name,
                    generation = generation,
                    classification = classification,
                    registeredAt = registeredAt,
                    duty = lifecycleDutyFor(role),
                    workspaceId = artifactWorkspaceIdentity(role)?.first,
                    workspaceSourceHash = artifactWorkspaceIdentity(role)?.second
                ).withComputedHash()
            )
            runtimeCell.confirmRegistration()
            runtimeCell.attachVmHandle(handle)
            markArtifactOwnerWorkspaceUse(runtimeCell)
            updateAgentStates()
            maybeEmitTurnTakingImbalanceSignal()
            emitEvent(EventTypes.AGENT_SPAWN, agentId, generation, buildSpawnPayload(agentId, role, generation))
            onAgentSpawned(agentId, role, modelAssignment)
            emitBudgetTelemetry(generation)
            Napier.i { "[$groupId] Spawned Shuru VM agent: $agentId (${role.name}) model=$modelAssignment" }
        }
    }

    private fun validateWorkerAuthorityAssignment(
        role: Role,
        scope: WorkerAuthorityScope,
        wipTaskClaim: WipTaskClaim?,
        assignment: WorkerAuthorityAssignment
    ) {
        val view = assignment.workerView
        require(view.hasValidHash()) {
            "Worker authority factory returned an invalid WorkerView hash"
        }
        require(assignment.ttlPolicy.lifetimeSeconds > 0) {
            "Worker authority factory returned a non-positive TTL"
        }
        when (scope) {
            WorkerAuthorityScope.NODE_ASSIGNMENT -> {
                require(role != Role.Outlier && role != Role.PerspectiveTaker) {
                    "Node-assignment authority is invalid for ${role.name}"
                }
                val node = requireNotNull(view.assignedNode) {
                    "Compiled ordinary worker has no assigned node"
                }
                val lease = requireNotNull(assignment.assignedLease) {
                    "Compiled ordinary worker has no assigned Atlas lease"
                }
                val wip = requireNotNull(wipTaskClaim) {
                    "Compiled ordinary worker has no assigned WIP projection"
                }
                require(
                    node.nodeId == lease.nodeId &&
                        wip.nodeId == lease.nodeId.value
                ) {
                    "Compiled worker node, lease, and WIP authority do not match"
                }
            }
            WorkerAuthorityScope.OUTLIER_MANDATE -> {
                require(role == Role.Outlier && view.outlierMandate?.isNotBlank() == true) {
                    "Outlier authority requires an explicit Outlier mandate"
                }
                require(view.assignedNode == null && assignment.assignedLease == null) {
                    "Outlier authority cannot carry ordinary node work"
                }
            }
            WorkerAuthorityScope.PERSPECTIVE_TAKER -> {
                require(role == Role.PerspectiveTaker) {
                    "Perspective authority is invalid for ${role.name}"
                }
                require(view.assignedNode == null && assignment.assignedLease == null) {
                    "Perspective authority cannot carry ordinary node work"
                }
            }
            WorkerAuthorityScope.SPECIAL_PURPOSE -> {
                require(role != Role.Outlier && role != Role.PerspectiveTaker) {
                    "Dedicated no-node roles require their exact worker authority scope"
                }
                require(view.assignedNode == null && assignment.assignedLease == null) {
                    "Special-purpose worker authority cannot carry ordinary node work"
                }
            }
        }
    }

    private suspend fun buildCanonicalWorkingContext(): String {
        val crossGroupEntries = runCatching {
            (
                db.knowledgeBaseDao().queryByDomain(
                    runId,
                    groupId,
                    "dispatch_inbox",
                    CANONICAL_CONTEXT_CROSS_GROUP_LIMIT
                ) +
                    db.knowledgeBaseDao().queryByDomain(
                        runId,
                        groupId,
                        "intelligence_leaks",
                        CANONICAL_CONTEXT_CROSS_GROUP_LIMIT
                    )
                )
                .distinctBy { it.id }
                .sortedWith(
                    compareByDescending<KnowledgeBaseEntryEntity> { it.generation }
                        .thenByDescending { it.createdAtMs }
                )
                .take(CANONICAL_CONTEXT_CROSS_GROUP_LIMIT)
        }.getOrElse { emptyList() }
        val crossGroupIds = crossGroupEntries.mapTo(mutableSetOf()) { it.id }
        val kbEntries = runCatching {
            db.knowledgeBaseDao().listActiveOrganized(
                runId,
                groupId,
                CANONICAL_CONTEXT_KB_LIMIT + CANONICAL_CONTEXT_CROSS_GROUP_LIMIT
            )
                .filterNot { it.id in crossGroupIds }
                .take(CANONICAL_CONTEXT_KB_LIMIT)
        }.getOrElse { emptyList() }
        val kgFacts = runCatching {
            db.knowledgeGraphFactDao().queryByTriplePattern(
                runId = runId,
                groupId = groupId,
                subjectPattern = "%",
                relationPattern = "%",
                objectPattern = "%",
                limit = CANONICAL_CONTEXT_KG_LIMIT
            )
        }.getOrElse { emptyList() }
        val promotedWork = runCatching {
            db.stagingDao().getAll(runId, groupId, CANONICAL_CONTEXT_STAGING_SCAN_LIMIT)
                .asSequence()
                .filter { it.status == "promoted" }
                .filter { it.type != "DEATH_NOTE" }
                .filterNot { submission ->
                    CANONICAL_CONTEXT_SYNTHETIC_MARKERS.any {
                        marker -> submission.content.contains(marker, ignoreCase = true)
                    }
                }
                .take(CANONICAL_CONTEXT_PROMOTED_LIMIT)
                .toList()
        }.getOrElse { emptyList() }
        val plans = runCatching {
            db.planRegistryDao().getLatest(runId, groupId, CANONICAL_CONTEXT_PLAN_LIMIT)
        }.getOrElse { emptyList() }
        val openTasks = runCatching {
            db.wipTaskDao().getOpenTasks(runId, groupId, CANONICAL_CONTEXT_WIP_LIMIT)
        }.getOrElse { emptyList() }

        return buildString {
            appendLine("Snapshot scope: run=$runId group=$groupId. Canonical state is authoritative; newer pending work is labeled.")
            appendLine()
            appendLine("CROSS-GROUP INBOX (${crossGroupEntries.size} shown)")
            if (crossGroupEntries.isEmpty()) appendLine("- none")
            crossGroupEntries.forEach { entry ->
                appendLine(
                    "- [${entry.id}] domain=${entry.domain} gen=${entry.generation} " +
                        "trust=${formatContextScore(entry.trustScore)}: ${contextExcerpt(entry.content)}"
                )
            }
            appendLine()
            appendLine("ACTIVE KB (${kbEntries.size} shown)")
            if (kbEntries.isEmpty()) appendLine("- none")
            kbEntries.forEach { entry ->
                appendLine(
                    "- [${entry.id}] domain=${entry.domain} gen=${entry.generation} trust=${formatContextScore(entry.trustScore)} " +
                        "citations=${entry.citationCount}: ${contextExcerpt(entry.content)}"
                )
            }
            appendLine()
            appendLine("ACTIVE KG (${kgFacts.size} shown)")
            if (kgFacts.isEmpty()) appendLine("- none")
            kgFacts.forEach { fact ->
                appendLine(
                    "- [${fact.id}] ${contextExcerpt(fact.subject, 120)} --${contextExcerpt(fact.relation, 80)}--> " +
                        "${contextExcerpt(fact.objectNode, 160)} confidence=${formatContextScore(fact.confidence)} " +
                        "provenance=${contextExcerpt(fact.provenanceJson, 220)}"
                )
            }
            appendLine()
            appendLine("RECENT SUBSTANTIVE PROMOTIONS (${promotedWork.size} shown)")
            if (promotedWork.isEmpty()) appendLine("- none")
            promotedWork.forEach { submission ->
                appendLine(
                    "- [${submission.id}] type=${submission.type} role=${submission.role} gen=${submission.generation}: " +
                        contextExcerpt(submission.content)
                )
            }
            appendLine()
            appendLine("CURRENT PLAN UPDATES (${plans.size} shown; pending entries are not canonical yet)")
            if (plans.isEmpty()) appendLine("- none")
            plans.forEach { plan ->
                appendLine("- [${plan.id}] status=${plan.status} role=${plan.role}: ${contextExcerpt(plan.content)}")
            }
            appendLine()
            appendLine("OPEN WIP (${openTasks.size} shown)")
            if (openTasks.isEmpty()) appendLine("- none")
            openTasks.forEach { task ->
                appendLine(
                    "- [${task.nodeId}] status=${task.status} claimedBy=${task.claimedByAgentId ?: "none"}: " +
                        contextExcerpt(task.description)
                )
            }
            appendLine()
            appendLine("INPUT ARTIFACT PREVIEWS (${runInputArtifacts.take(CANONICAL_CONTEXT_ARTIFACT_LIMIT).size} shown)")
            if (runInputArtifacts.isEmpty()) appendLine("- none")
            runInputArtifacts.take(CANONICAL_CONTEXT_ARTIFACT_LIMIT).forEach { artifact ->
                appendLine(
                    "- [${artifact.relativePath}] ${artifact.mimeType}: " +
                        contextExcerpt(artifact.textPreview.orEmpty(), CANONICAL_CONTEXT_ARTIFACT_EXCERPT_CHARS)
                )
            }
        }.take(CANONICAL_CONTEXT_MAX_CHARS)
    }

    private fun contextExcerpt(value: String, maxChars: Int = CANONICAL_CONTEXT_EXCERPT_CHARS): String =
        value.replace(Regex("""\s+"""), " ").trim().take(maxChars).ifBlank { "(empty)" }

    private fun formatContextScore(value: Double): String =
        String.format(java.util.Locale.ROOT, "%.2f", value)

    private suspend fun monitorBudgets() {
        while (currentCoroutineContext().isActive) {
            delay(budgetPollingIntervalMs)
            val agentIds = liveAgents.keys.toList()
            var totalBudgetChanged = false

            for (agentId in agentIds) {
                fatalExitConflicts[agentId]?.let { throw it }
                val cell = liveAgents[agentId] ?: continue
                val state = cell.snapshot()
                val frozenExit = state.exitRecord
                if (frozenExit != null) {
                    // A prior cleanup attempt failed after the durable exit was written. Keep the
                    // agent live for publication accounting. Scheduled retries own cleanup after
                    // a failure; polling only resumes finalization once execution later settles.
                    if (
                        !exitFinalizationFailures.containsKey(agentId) &&
                        !exitFinalizationRetryJobs.containsKey(agentId) &&
                        agentId !in exitFinalizationRetriesInProgress
                    ) {
                        handleAgentExit(agentId, wasKilled = frozenExit.wasKilled)
                    }
                    continue
                }
                processTtlCheckpoints(cell)
                when {
                    cell.executionJob() != null -> {
                        // In-process agents self-cleanup via their coroutine completion handlers.
                        if (cell.executionJob()?.isCompleted == true) {
                            Napier.d { "[$groupId] In-process agent $agentId job completed (late detection)" }
                            markInProcessExecutionFinished(agentId)?.let { completedEntry ->
                                handleAgentExit(
                                    agentId,
                                    wasKilled = completedEntry.snapshot().exitRecord?.wasKilled ?: false
                                )
                            }
                        } else {
                            // Poll the live BudgetLedger balance if available
                            val ledger = cell.ledger()
                            if (ledger != null) {
                                val currentBalance = ledger.balance
                                if (cell.observeBudget(currentBalance)) {
                                    totalBudgetChanged = true
                                    Napier.d { "[$groupId] Agent $agentId budget: ${currentBalance} (spent=${ledger.initialBudget - currentBalance})" }
                                }
                                // ── Budget floor kill (§2a) ─────────────────────────────
                                // If the ledger has hit its floor, immediately cancel the
                                // agent coroutine so it writes its Death Note and exits.
                                if (ledger.isAtFloor && !cell.snapshot().killRequested) {
                                    Napier.w { "[$groupId] Agent $agentId hit budget floor — initiating kill" }
                                    initiateKillSequence(
                                        agentId,
                                        AgentTerminationCause.BUDGET_FLOOR
                                    )
                                }
                            }
                        }
                    }
                    cell.vmHandle() != null -> {
                        // Shuru VM: poll status via CLI
                        val status = shuruVmManager.status(requireNotNull(cell.vmHandle()))
                        if (status == "stopped" || status == "unknown") {
                            cell.markExecutionSettled()
                            handleAgentExit(agentId, wasKilled = false)
                        }
                    }
                }
            }

            // Emit aggregate budget update if any agent's balance changed
            if (totalBudgetChanged) {
                liveAgents.values.map { it.generation }.distinct().forEach(::emitBudgetTelemetry)
                runCatching {
                    onBudgetChanged(totalRemainingBudget())
                }.onFailure { error ->
                    Napier.w(error) { "[$groupId] Budget callback failed during polling" }
                }
                maybeEmitTurnTakingImbalanceSignal()
                updateAgentStates()
            }
        }
    }

    private suspend fun processTtlCheckpoints(cell: AgentRuntimeCell) {
        val agentId = cell.agentId
        val policy = cell.workerAuthority?.ttlPolicy ?: return
        val initialState = cell.snapshot()
        val elapsedFraction = (
            (System.currentTimeMillis() - cell.spawnedAtMs).coerceAtLeast(0L).toDouble() /
                (policy.lifetimeSeconds * 1_000L).toDouble()
            ).coerceIn(0.0, 1.0)
        val repository = WorkerExecutionRepository(db)
        var persisted = initialState.ttlCheckpoints
        for ((checkpoint, threshold) in policy.checkpointFractions.entries.sortedBy { it.value }) {
            if (elapsedFraction < threshold || checkpoint in persisted) continue
            val phase = when (checkpoint) {
                TtlCheckpointKind.WORK_WARNING -> AgentExecutionPhase.WORKING
                TtlCheckpointKind.COMMIT_SELECTION_REQUIRED,
                TtlCheckpointKind.BROAD_EXPLORATION_DISABLED -> AgentExecutionPhase.COMMIT_REQUIRED
                TtlCheckpointKind.DEATH_NOTE_GRACE -> AgentExecutionPhase.DEATH_NOTE_WINDOW
                TtlCheckpointKind.TERMINATED -> AgentExecutionPhase.TERMINATED
            }
            repository.recordCheckpoint(
                TtlCheckpointEvent(
                    eventId = "ttl:${runId}:${groupId}:${agentId}:${checkpoint.name}",
                    runId = runId,
                    groupId = com.achulkov.evomolior.core.atlas.AtlasGroupId(groupId),
                    agentId = com.achulkov.evomolior.core.atlas.AtlasAgentId(agentId),
                    round = cell.generation,
                    checkpoint = checkpoint,
                    elapsedFraction = threshold,
                    remainingBudget = cell.ledger()?.balance ?: cell.snapshot().budgetRemaining,
                    phase = phase,
                    occurredAt = Clock.System.now()
                )
            )
            persisted = persisted + checkpoint
            cell.recordTtlCheckpoint(checkpoint)
        }
        if (
            elapsedFraction >= 1.0 &&
            !cell.snapshot().killRequested &&
            liveAgents.containsKey(agentId)
        ) {
            initiateKillSequence(agentId, AgentTerminationCause.TTL_EXPIRED)
        }
    }

    private suspend fun initiateKillSequence(
        agentId: String,
        cause: AgentTerminationCause,
        detail: String = cause.name.lowercase(),
        cancellationCause: CancellationException? = null
    ) {
        val entry = liveAgents[agentId] ?: return
        val ownsKillSequence = entry.requestTermination()
        if (!ownsKillSequence) {
            entry.snapshot().exitRecord?.let { exit ->
                handleAgentExit(agentId, wasKilled = exit.wasKilled)
            }
            return
        }
        Napier.i {
            if (cancellationCause is ExpectedAgentStopCancellationException) {
                "[$groupId] Controlled stop initiated for $agentId: $detail"
            } else {
                "[$groupId] Kill sequence initiated for $agentId: $detail"
            }
        }

        updateAgentStates()
        terminationCommitBarrier(agentId, cause)

        if (entry.executionJob() != null) {
            // In-process mode: cancel the coroutine, but do not join it. Provider/Koog calls are
            // not guaranteed to be cancellation-cooperative; joining a stuck call blocks the run
            // loop forever and prevents final-output publication.
            handleAgentExit(agentId, wasKilled = true)
            entry.executionJob()?.cancel(cancellationCause)
            return
        }

        // Shuru VM mode: SIGTERM → grace period → SIGKILL
        handleAgentExit(agentId, wasKilled = true)
        val handle = requireNotNull(entry.vmHandle())
        try {
            shuruVmManager.signal(handle, "SIGTERM")
        } catch (e: Exception) {
            Napier.w(e) { "SIGTERM failed for $agentId" }
        }

        // Wait for grace period
        delay(gracePeriodSeconds * 1000L)

        // Check if VM still running
        val status = shuruVmManager.status(handle)
        if (status == "running") {
            Napier.w { "[$groupId] Agent $agentId did not exit during grace period — SIGKILL" }
            try {
                shuruVmManager.killVm(handle)
            } catch (e: Exception) {
                Napier.e(e) { "SIGKILL failed for $agentId" }
            }
        }

        entry.markExecutionSettled()
        handleAgentExit(agentId, wasKilled = true)
    }

    private suspend fun handleAgentExit(
        agentId: String,
        wasKilled: Boolean,
        refundedBudget: Double = 0.0
    ) {
        val mutex = exitFinalizationMutexes.computeIfAbsent(agentId) { Mutex() }
        withContext(NonCancellable) {
            mutex.withLock {
                exitFinalizationPersistenceMutex.withLock persistenceLock@{
                try {
                fatalExitConflicts[agentId]?.let { throw it }
                val exitEntry = liveAgents[agentId] ?: return@persistenceLock
                val initialState = exitEntry.snapshot()
                val workerRepository = WorkerExecutionRepository(db)

                val contribution = workerRepository
                    .contributionsForAgent(runId, groupId, agentId)
                    .lastOrNull()
                val contributionPotentiallyUseful = contribution?.isPotentiallyUseful == true
                val candidateExit = AgentExitRecord(
                    exitId = "exit:$runId:$groupId:$agentId",
                    runId = runId,
                    groupId = com.achulkov.evomolior.core.atlas.AtlasGroupId(groupId),
                    agentId = com.achulkov.evomolior.core.atlas.AtlasAgentId(agentId),
                    nodeId = exitEntry.workerAuthority?.assignedLease?.nodeId,
                    round = exitEntry.generation,
                    finalPhase = when (contribution?.commitType) {
                        CommitType.STRUCTURED_DEAD_END -> AgentExecutionPhase.NEGATIVE_RESULT_STAGED
                        CommitType.NO_CONTRIBUTION, null -> AgentExecutionPhase.NO_CONTRIBUTION
                        else -> AgentExecutionPhase.CONTRIBUTION_STAGED
                    },
                    commitId = contribution?.commitId,
                    commitType = contribution?.commitType,
                    acceptedContribution = contributionPotentiallyUseful &&
                        contribution?.commitType !in setOf(
                            CommitType.STRUCTURED_DEAD_END,
                            CommitType.NOVELTY_PROPOSAL
                        ),
                    acceptedStructuredDeadEnd =
                        contribution?.commitType == CommitType.STRUCTURED_DEAD_END,
                    acceptedNoveltyProposal =
                        contribution?.commitType == CommitType.NOVELTY_PROPOSAL,
                    degradedExecution = db.workerExecutionDao()
                        .degradedExecutionsForAgent(runId, groupId, agentId)
                        .isNotEmpty(),
                    wasKilled = wasKilled || initialState.killRequested,
                    exitedAt = Clock.System.now()
                )
                val exitRecord = exitEntry.freezeExit(candidateExit)
                val exitWasKilled = exitRecord.wasKilled

                // This is the lifecycle authority boundary. Do not release a lease, abandon WIP,
                // or notify observers until the immutable exit is durable.
                if (!exitEntry.snapshot().exitPersisted) {
                    when (val persisted = workerRepository.recordExit(exitRecord)) {
                        is PersistExitResult.Inserted -> exitEntry.confirmExitPersisted()
                        is PersistExitResult.AlreadyPersisted -> {
                            exitEntry.confirmExitPersisted()
                        }
                        is PersistExitResult.Conflict -> {
                            throw AgentExitIdentityConflictException(
                                agentId = agentId,
                                attemptedExitRecordHash = persisted.exitRecordHash,
                                persistedExitRecordHash = persisted.persistedExitRecordHash
                            )
                        }
                    }
                }

                if (refundedBudget > 0.0) {
                    refundedBudgetByAgent[agentId] = refundedBudget
                } else {
                    refundedBudgetByAgent.remove(agentId)
                }
                if (!exitEntry.snapshot().authorityCleanupComplete) {
                    finalizeFrozenAgentAuthority(exitEntry, exitRecord)
                    exitEntry.markAuthorityCleanupComplete()
                }
                updateAgentStates()

                // Cancellation-resistant in-process providers may outlive the bounded kill
                // request. Preserve the durable frozen exit classification, but keep the agent
                // live and publication non-quiescent until its runner has actually returned.
                if (!exitEntry.snapshot().executionSettled) {
                    Napier.w {
                        "[$groupId] Agent $agentId has durable exit authority but is still executing; " +
                            "strict publication remains blocked"
                    }
                    return@persistenceLock
                }
                exitEntry.markCostSettled()

                // Log final budget snapshot if we have a live ledger (in-process mode)
                val finalSnapshot = exitEntry.ledger()?.snapshot()
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
                    append("""{"agentId":"$agentId","wasKilled":$exitWasKilled""")
                    if (finalSnapshot != null) {
                        append(""","budgetRemaining":${finalSnapshot.currentBalance}""")
                        append(""","budgetSpent":${finalSnapshot.initialBudget - finalSnapshot.currentBalance}""")
                        append(""","actionCount":${finalSnapshot.actionLog.size}""")
                    }
                    append("}")
                }
                runCatching {
                    emitEvent(
                        if (exitWasKilled) EventTypes.AGENT_KILLED else EventTypes.AGENT_DEATH,
                        agentId,
                        exitEntry.generation,
                        exitPayload
                    )
                }.onFailure { error ->
                    Napier.w(error) { "[$groupId] Failed to emit durable exit event for $agentId" }
                }
                if (exitEntry.claimDeathCallbackEmission()) {
                    runCatching {
                        onAgentDied(agentId, exitWasKilled)
                    }.onFailure { error ->
                        Napier.w(error) { "[$groupId] Agent death callback failed for $agentId" }
                    }
                } else {
                    deathCallbackDuplicateCount.incrementAndGet()
                }

                exitEntry.confirmCleanupComplete()
                awaitRuntimeTransitionPersistence()
                runtimeTransitionFailureFor(agentId)?.let { throw it }
                maxExitToQuiescenceMs.accumulateAndGet(
                    (System.currentTimeMillis() - exitRecord.exitedAt.toEpochMilliseconds())
                        .coerceAtLeast(0L),
                    ::maxOf
                )
                synchronized(budgetTelemetryLock) {
                    if (liveAgents.remove(agentId, exitEntry)) {
                        val settled = settledCreditsByGeneration[exitEntry.generation] ?: SettledCredits()
                        settledCreditsByGeneration[exitEntry.generation] = SettledCredits(
                            allocated = settled.allocated + exitEntry.allocatedBudget,
                            returned = settled.returned +
                                (finalSnapshot?.currentBalance ?: exitEntry.snapshot().budgetRemaining)
                        )
                    }
                }
                exitFinalizationRetryAttempts.remove(agentId)
                exitFinalizationFailures.remove(agentId)
                updateAgentStates()

                // Emit updated group budget after agent exit
                emitBudgetTelemetry(exitEntry.generation)
                val totalRemaining = totalRemainingBudget()
                runCatching {
                    onBudgetChanged(totalRemaining)
                }.onFailure { error ->
                    Napier.w(error) { "[$groupId] Budget callback failed after $agentId exit" }
                }
                maybeEmitTurnTakingImbalanceSignal()

                Napier.i {
                    "[$groupId] Agent $agentId ${if (exitWasKilled) "stopped" else "completed normally"} " +
                        "(wasKilled=$exitWasKilled, inProcess=${exitEntry.executionJob() != null})"
                }
                } catch (conflict: AgentExitIdentityConflictException) {
                    fatalExitConflicts.putIfAbsent(agentId, conflict)
                    Napier.e(conflict) {
                        "[$groupId] Fatal immutable exit conflict for $agentId; retry is forbidden"
                    }
                    throw conflict
                } catch (error: Exception) {
                    Napier.w(error) {
                        "[$groupId] Exit finalization deferred for $agentId; accounting remains non-quiescent until retry"
                    }
                    scheduleExitFinalizationRetry(
                        agentId = agentId,
                        wasKilled = wasKilled,
                        refundedBudget = refundedBudget,
                        failure = error
                    )
                }
                }
            }
        }
        if (!liveAgents.containsKey(agentId)) {
            exitFinalizationMutexes.remove(agentId, mutex)
        }
    }

    private suspend fun finalizeFrozenAgentAuthority(
        cell: AgentRuntimeCell,
        exitRecord: AgentExitRecord
    ) {
        val usefulCommit = exitRecord.acceptedContribution
        cell.workerAuthority?.finalizeLease?.invoke(usefulCommit)

        val taskClaim = cell.wipTaskClaim ?: return
        val pendingReviewCount = db.stagingDao().countPendingCompletionCandidates(
            runId = runId,
            groupId = groupId,
            agentId = cell.agentId,
            nodeId = taskClaim.nodeId
        )
        if (usefulCommit && pendingReviewCount > 0) {
            Napier.i {
                "[$groupId] WiP task ${taskClaim.nodeId} retained for Critic review " +
                    "after ${cell.agentId} exit (pending=$pendingReviewCount, " +
                    "wasKilled=${exitRecord.wasKilled})"
            }
            return
        }
        val released = db.wipTaskDao().abandonTask(
            nodeId = taskClaim.nodeId,
            runId = runId,
            groupId = groupId,
            agentId = cell.agentId,
            generation = cell.generation
        )
        if (released > 0) {
            runCatching {
                onWipTaskAbandoned(cell.agentId, taskClaim.nodeId, cell.generation)
            }.onFailure { error ->
                Napier.w(error) {
                    "[$groupId] WiP abandonment callback failed for ${taskClaim.nodeId}"
                }
            }
            Napier.i {
                "[$groupId] WiP task ${taskClaim.nodeId} abandoned by ${cell.agentId} " +
                    "(wasKilled=${exitRecord.wasKilled})"
            }
        }
    }

    private fun scheduleExitFinalizationRetry(
        agentId: String,
        wasKilled: Boolean,
        refundedBudget: Double,
        failure: Throwable
    ) {
        lateinit var retryJob: Job
        var retryAttempt = 0
        retryJob = runtimeTransitionPersistenceScope.launch(start = CoroutineStart.LAZY) {
            try {
                delay(EXIT_FINALIZATION_RETRY_DELAY_MS * retryAttempt)
                exitFinalizationRetriesInProgress.add(agentId)
                exitFinalizationRetryJobs.remove(agentId, retryJob)
                handleAgentExit(agentId, wasKilled, refundedBudget)
            } finally {
                exitFinalizationRetryJobs.remove(agentId, retryJob)
                exitFinalizationRetriesInProgress.remove(agentId)
            }
        }
        if (exitFinalizationRetryJobs.putIfAbsent(agentId, retryJob) != null) {
            retryJob.cancel()
            return
        }
        retryAttempt = exitFinalizationRetryAttempts.merge(agentId, 1, Int::plus) ?: 1
        if (retryAttempt > EXIT_FINALIZATION_RETRY_ATTEMPTS) {
            exitFinalizationFailures[agentId] = failure
            exitFinalizationRetryJobs.remove(agentId, retryJob)
            retryJob.cancel()
            return
        }
        exitFinalizationRetryCount.incrementAndGet()
        retryJob.start()
    }

    private fun markInProcessExecutionFinished(agentId: String): AgentRuntimeCell? =
        liveAgents[agentId]?.also(AgentRuntimeCell::markExecutionSettled)

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
        _agentStates.value = liveAgents.mapValues { (agentId, cell) ->
            val state = cell.snapshot()
            AgentState(
                agentId = agentId,
                status = if (state.killRequested || state.exitRecord != null) {
                    AgentStatus.DyingGracefully
                } else {
                    AgentStatus.Active
                },
                generation = cell.generation,
                budgetRemaining = cell.ledger()?.balance ?: state.budgetRemaining
            )
        }
    }

    /**
     * Emits an inspector flag when one role dominates the live spent budget.
     *
     * This operationalises the roadmap's equal turn-taking constraint with a concrete signal:
     * if one role accounts for most of the active budget allocation or consumption while at
     * least two roles are making progress, the run is probably over-concentrated.
     */
    private suspend fun maybeEmitTurnTakingImbalanceSignal(allocation: RoleAllocation? = null) {
        val allocationSignal = allocation?.let {
            val budgetByRole = it.entries
                .groupBy { entry -> entry.role }
                .mapValues { (_, entries) -> entries.sumOf { entry -> entry.count * entry.budgetPerAgent } }
                .filterValues { value -> value > 0.0 }
            val entropyMetrics = participationEntropyMetrics(budgetByRole)

            dominantTurnTakingSignal(
                valuesByRole = budgetByRole,
                signalKind = "allocated",
                minimumDistinctRoles = 2
            )?.copy(
                generation = it.generation,
                activeRoleCount = budgetByRole.size,
                conversationEntropy = entropyMetrics?.entropy ?: 0.0,
                normalizedConversationEntropy = entropyMetrics?.normalizedEntropy ?: 0.0
            )
        }

        val activeAgents = liveAgents.values
        val liveSpentSignal = if (activeAgents.size >= 3) {
            val spentByRole = activeAgents
                .groupBy { it.role }
                .mapValues { (_, entries) ->
                    entries.sumOf { cell ->
                        val currentBalance = cell.ledger()?.balance ?: cell.snapshot().budgetRemaining
                        (cell.allocatedBudget - currentBalance).coerceAtLeast(0.0)
                    }
                }
                .filterValues { value -> value > 0.0 }
            val entropyMetrics = participationEntropyMetrics(spentByRole)

            dominantTurnTakingSignal(
                valuesByRole = spentByRole,
                signalKind = "spent",
                minimumDistinctRoles = 2
            )?.copy(
                generation = activeAgents.maxOf { it.generation },
                activeRoleCount = spentByRole.size,
                conversationEntropy = entropyMetrics?.entropy ?: 0.0,
                normalizedConversationEntropy = entropyMetrics?.normalizedEntropy ?: 0.0
            )
        } else null

        val dominantSignal = liveSpentSignal ?: allocationSignal
        if (dominantSignal == null) {
            lastTurnTakingImbalanceSignature = null
            return
        }

        val signature = "${dominantSignal.signalKind}:${dominantSignal.dominantRole.name}:${dominantSignal.generation}:${dominantSignal.activeRoleCount}:${dominantSignal.dominantShare.toString().take(4)}"
        if (signature == lastTurnTakingImbalanceSignature) {
            return
        }
        lastTurnTakingImbalanceSignature = signature

        val description = buildString {
            append("Role budget dominance detected: ")
            append(dominantSignal.dominantRole.name)
            append(" consumed ")
            append((dominantSignal.dominantShare * 100).toInt())
            append("% of active ")
            append(dominantSignal.signalKind)
            append(" budget across ")
            append(dominantSignal.activeRoleCount)
            append(" roles in generation ")
            append(dominantSignal.generation)
            append(". conversationEntropy=")
            append(String.format(java.util.Locale.ROOT, "%.3f", dominantSignal.conversationEntropy))
            append(" normalizedConversationEntropy=")
            append(String.format(java.util.Locale.ROOT, "%.3f", dominantSignal.normalizedConversationEntropy))
        }

        Napier.w { "[$groupId] $description" }
        emitEvent(
            EventTypes.INSPECTOR_FLAG,
            null,
            dominantSignal.generation,
            """{"severity":"warning","category":"turn_taking","signalKind":"${dominantSignal.signalKind}","description":"$description","dominantRole":"${dominantSignal.dominantRole}","dominantShare":${dominantSignal.dominantShare},"activeRoleCount":${dominantSignal.activeRoleCount},"conversationEntropy":${dominantSignal.conversationEntropy},"normalizedConversationEntropy":${dominantSignal.normalizedConversationEntropy}}"""
        )
    }

    internal data class TurnTakingDominanceSignal(
        val dominantRole: Role,
        val dominantShare: Double,
        val signalKind: String,
        val activeRoleCount: Int,
        val generation: Int,
        val conversationEntropy: Double = 0.0,
        val normalizedConversationEntropy: Double = 0.0
    )

    internal fun dominantTurnTakingSignal(
        valuesByRole: Map<Role, Double>,
        signalKind: String,
        minimumDistinctRoles: Int
    ): TurnTakingDominanceSignal? {
        if (valuesByRole.size < minimumDistinctRoles) return null
        val total = valuesByRole.values.sum()
        if (total <= 0.0) return null
        val dominantEntry = valuesByRole.maxBy { it.value }
        val share = dominantEntry.value / total
        if (share < 0.65) return null
        return TurnTakingDominanceSignal(
            dominantRole = dominantEntry.key,
            dominantShare = share,
            signalKind = signalKind,
            activeRoleCount = valuesByRole.size,
            generation = 0
        )
    }

    internal data class ParticipationEntropyMetrics(
        val entropy: Double,
        val normalizedEntropy: Double
    )

    internal fun participationEntropyMetrics(valuesByRole: Map<Role, Double>): ParticipationEntropyMetrics? {
        val total = valuesByRole.values.sum()
        if (total <= 0.0) return null
        val shares = valuesByRole.values
            .filter { it > 0.0 }
            .map { it / total }
        if (shares.isEmpty()) return null

        val entropy = -shares.sumOf { share -> share * (kotlin.math.ln(share) / kotlin.math.ln(2.0)) }
        val maxEntropy = kotlin.math.ln(shares.size.toDouble()) / kotlin.math.ln(2.0)
        val normalizedEntropy = if (maxEntropy > 0.0) entropy / maxEntropy else 1.0
        return ParticipationEntropyMetrics(entropy = entropy, normalizedEntropy = normalizedEntropy)
    }

    private fun totalRemainingBudget(): Double =
        liveAgents.values.sumOf { cell ->
            cell.ledger()?.balance ?: cell.snapshot().budgetRemaining
        } +
            refundedBudgetByAgent.values.sum()

    private fun buildSpawnPayload(agentId: String, role: Role, generation: Int): String =
        """{"agentId":"$agentId","role":"${role.name}","generation":$generation,"inProcess":${localAgentRunner != null}}"""

    private fun defaultRolePrompt(role: Role): String =
        "You are a ${role.name} agent in the EvoMolior system. Act according to your role."

}

/** Closed cause for the single runtime-cell termination request. */
enum class AgentTerminationCause {
    BUDGET_FLOOR,
    TTL_EXPIRED,
    RUN_TERMINATION,
    TARGETED_TERMINATION
}

/** Exact publication-time accounting for one group lifecycle manager. */
data class AgentPublicationAccounting(
    val registeredNonDaemonAgentIds: Set<String>,
    val exitedNonDaemonAgentIds: Set<String>,
    val liveNonDaemonAgentIds: Set<String>,
    val unaccountedNonDaemonAgentIds: Set<String>,
    val liveNestedGroupCount: Int,
    val pendingRuntimeTransitionCount: Int = 0,
    val runtimeTransitionFailureCount: Int = 0,
    val exitIdentityConflictCount: Int = 0,
    val exitFinalizationFailureCount: Int = 0,
    val orphanedUnsettledAgentIds: Set<String> = emptySet()
) {
    /** True only when every registered non-daemon agent exited and no nested work remains. */
    val isQuiescent: Boolean
        get() = liveNonDaemonAgentIds.isEmpty() &&
            unaccountedNonDaemonAgentIds.isEmpty() &&
            liveNestedGroupCount == 0 &&
            pendingRuntimeTransitionCount == 0 &&
            runtimeTransitionFailureCount == 0 &&
            exitIdentityConflictCount == 0 &&
            exitFinalizationFailureCount == 0 &&
            orphanedUnsettledAgentIds.isEmpty()
}

/** First-class lifecycle reconciliation metrics required by the completion blueprint. */
data class LifecycleReconciliationMetrics(
    val exitIdentityConflictCount: Int,
    val exitFinalizationRetryCount: Int,
    val registeredWithoutExitCount: Int,
    val exitWithoutRegisteredAgentCount: Int,
    val liveExecutionAfterArchiveBarrier: Int,
    val deathCallbackDuplicateCount: Int,
    val unsettledCostReservationCount: Int,
    val maxExitToQuiescenceMs: Long?
)

/** Fatal signal that a registered agent already owns a different immutable exit authority. */
class AgentExitIdentityConflictException(
    val agentId: String,
    val attemptedExitRecordHash: String,
    val persistedExitRecordHash: String
) : IllegalStateException(
    "Agent $agentId exit identity conflict: attempted=$attemptedExitRecordHash " +
        "persisted=$persistedExitRecordHash"
)

/** Input used by run authority to allocate one concrete ordinary-worker context. */
data class WorkerAuthorityRequest(
    val agentId: String,
    val role: Role,
    val generation: Int,
    val wipTaskClaim: WipTaskClaim?
)

/** Hash-bound worker view, optional node lease, and mortality policy returned at spawn. */
data class WorkerAuthorityAssignment(
    val workerView: WorkerView,
    val assignedLease: WorkerLeaseAssignment?,
    val ttlPolicy: AgentTtlPolicy,
    val finalizeLease: suspend (committed: Boolean) -> Unit = {}
)

/** Snapshot of an agent's live state (for IPC / UI). */
data class AgentState(
    val agentId: String,
    val status: AgentStatus,
    val generation: Int,
    val budgetRemaining: Double
)
