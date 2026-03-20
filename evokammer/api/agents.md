# evokammer:api — agents.md

## Purpose

The `api` module defines the IPC contract between the EvoKammer engine and the
Compose Desktop control panel (`composeApp` JVM target). It contains only interface
definitions, data classes, and event types — no transport implementation.

The engine exposes this service; the desktop app consumes it. Both live on the same machine
(local socket or gRPC over loopback).

## Design Principles

- **Pure interfaces** — no transport-specific code in this module
- **Typed contracts** — no `Any` or untyped JSON payloads at the API boundary
- **Streaming events** — the engine pushes updates; the desktop does not poll
- **Command / query separation** — commands return `Result<T>`; queries return `Flow<T>`

## Package Structure

```
com.achulkov.evokammer.api/
  service/
    EvoKammerService.kt          -- Main service interface (commands + queries)
    RunCommand.kt                -- Sealed command hierarchy
    RunQuery.kt                  -- Sealed query hierarchy
  events/
    EvoKammerEvent.kt            -- Sealed event hierarchy (all engine events for the UI)
    EventFilter.kt               -- Filter parameters for event subscriptions
  model/
    RunSummary.kt                -- Lightweight run state for dashboard list
    GroupState.kt                -- Per-group live metrics (density, budget, generation)
    AgentState.kt                -- Per-agent live state (role, status, budget, WiP)
    SubstrateSnapshot.kt         -- KB entry, KG fact, Tool entry (for browser)
    ScoringResult.kt             -- Check-in or final scoring result
    MetaArchiveEntry.kt          -- Entry in cross-task meta-archive
```

## EvoKammerService Interface

```kotlin
interface EvoKammerService {

    // --- Commands ---

    /** Start a new run with the given configuration. Returns the assigned runId. */
    suspend fun startRun(config: RunConfigRequest): Result<String>

    /** Pause all agent spawning in the run. Active agents finish their current TTL. */
    suspend fun pauseRun(runId: String): Result<Unit>

    /** Resume a paused run. */
    suspend fun resumeRun(runId: String): Result<Unit>

    /** Force-open a check-in gate immediately. */
    suspend fun forceCheckin(runId: String): Result<Unit>

    /** Terminate a run immediately. All active agents are hard-killed. */
    suspend fun terminateRun(runId: String): Result<Unit>

    // --- Queries ---

    /** Observe all runs (past and active) for the dashboard list. */
    fun observeRuns(): Flow<List<RunSummary>>

    /** Observe live group states for a run (for the monitor screen). */
    fun observeGroupStates(runId: String): Flow<List<GroupState>>

    /** Observe live agent states for a group. */
    fun observeAgentStates(runId: String, groupId: String): Flow<List<AgentState>>

    /** Observe the event log stream with optional filtering. */
    fun observeEvents(runId: String, filter: EventFilter): Flow<EvoKammerEvent>

    /** Query KB entries for a group (for substrate browser). */
    suspend fun queryKB(runId: String, groupId: String, query: String, domain: String?, limit: Int): List<SubstrateSnapshot.KBEntry>

    /** Query KG facts for a group. */
    suspend fun queryKG(runId: String, groupId: String, pattern: String, limit: Int): List<SubstrateSnapshot.KGFact>

    /** Get Tool Registry entries for a group. */
    suspend fun getTools(runId: String, groupId: String, domain: String?): List<SubstrateSnapshot.ToolEntry>

    /** Get Dead Ends entries for a group. */
    suspend fun getDeadEnds(runId: String, groupId: String): List<SubstrateSnapshot.DeadEnd>

    /** Get Legacy Protocols for a group and role. */
    suspend fun getLegacyProtocols(runId: String, groupId: String, role: String?): List<SubstrateSnapshot.LegacyProtocol>

    /** Get scoring results for a run. */
    suspend fun getScoringResults(runId: String): List<ScoringResult>

    /** Get meta-archive entries (cross-task learning). */
    suspend fun getMetaArchive(): List<MetaArchiveEntry>
}
```

## EvoKammerEvent Hierarchy

All engine events flow to the desktop via `observeEvents()`. The desktop subscribes to an
event stream and the engine pushes events as they occur in real time.

```kotlin
sealed class EvoKammerEvent {
    data class AgentSpawned(val runId, groupId, agentId, role, generation, timestamp)
    data class AgentDied(val runId, groupId, agentId, role, budgetConsumed, hadDeathNote, generation, timestamp)
    data class AgentKilled(val runId, groupId, agentId, reason, generation, timestamp)
    data class StagingSubmitted(val runId, groupId, agentId, submissionId, type, speculative, generation, timestamp)
    data class StagingPromoted(val runId, groupId, submissionId, type, initialTrustScore, generation, timestamp)
    data class StagingRejected(val runId, groupId, submissionId, reason, generation, timestamp)
    data class StagingDisputed(val runId, groupId, submissionId, generation, timestamp)
    data class ToolExecuted(val runId, groupId, agentId, toolName, success, costDeducted, generation, timestamp)
    data class LLMCalled(val runId, groupId, agentId, model, inputTokens, outputTokens, costDeducted, generation, timestamp)
    data class BudgetDeducted(val runId, groupId, agentId, action, amount, remainingBalance, generation, timestamp)
    data class WipClaimed(val runId, groupId, agentId, nodeId, generation, timestamp)
    data class WipReleased(val runId, groupId, agentId, nodeId, reason, generation, timestamp)
    data class DispatchSent(val runId, groupId, submissionId, destinationGroupId, lagGenerations, generation, timestamp)
    data class LeakDelivered(val runId, targetGroupId, domain, leakId, generation, timestamp)
    data class CheckinGateOpened(val runId, checkinId, generationMilestone, timestamp)
    data class CheckinGateClosed(val runId, checkinId, scores, timestamp)
    data class ScoreRevealed(val runId, groupId, checkinId, score, passed, generation, timestamp)
    data class NodeClaimed(val runId, groupId, nodeId, qualityScore, timestamp)
    data class NodeRecaptured(val runId, groupId, nodeId, previousGroupId, newQualityScore, timestamp)
    data class WatchdogSignalEmitted(val runId, signalType, targetGroupIds, timestamp)
    data class StasisDetected(val runId, groupId, generation, timestamp)
    data class GenerationBoundary(val runId, groupId, newGeneration, kbDensity, kgFactCount, timestamp)
    data class KBTrustDecay(val runId, groupId, archivedCount, generation, timestamp)
    data class LegacyProtocolExtracted(val runId, groupId, role, protocolId, sourceAgentIds, generation, timestamp)
    data class FinalSubmissionComplete(val runId, winnerGroupId, timestamp)
}
```

## RunConfigRequest

```kotlin
data class RunConfigRequest(
    val objective: String,
    val globalBudgetCeiling: Double,
    val generationCeiling: Int,
    val groupCount: Int,
    val groupProfileAssignments: Map<String, GroupProfileType>,
    val modelOverrides: Map<String, String>,          // role → openrouter model string
    val checkinGenerationMilestones: List<Int>,
    val leakIntervalGenerations: Int,
    val dispatchLagGenerations: Int,
    val graderGroupCount: Int,
    val openRouterApiKey: String
)
```

## EventFilter

```kotlin
data class EventFilter(
    val groupIds: List<String>? = null,    // null = all groups
    val agentIds: List<String>? = null,    // null = all agents
    val eventTypes: List<String>? = null,  // null = all event types
    val minGeneration: Int? = null,
    val maxGeneration: Int? = null
)
```

## Transport (Implementation Notes — not in this module)

The API service is implemented in `orchestration` using Ktor's server with WebSocket or
SSE for the event stream, and HTTP endpoints for commands and queries. Communication is
over loopback (`127.0.0.1`) with a port configurable via the run configuration.

The Compose Desktop app connects using Ktor client (already in the project dependencies).
Connection lifecycle: app connects on startup, reconnects on disconnect (exponential backoff),
engine starts accepting connections after it initialises.

Authentication: none required (loopback only); a session token may be added in Phase 10.
