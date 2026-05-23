# evokammer:orchestration — agents.md

## Purpose

The `orchestration` module is the control center of the EvoKammer engine. It owns the
agent lifecycle (spawn/kill), population evolution (role allocation), intelligence compounding
(epigenetic inheritance), global health monitoring (Inspector/Auditor/Watchdog), and the
Genesis Council that bootstraps each run.

It does NOT own multi-group coordination or scoring — those live in `competition`.

## Package Structure

```
com.achulkov.evokammer.orchestration/
  lifecycle/
    LifecycleManager.kt          -- Spawns/kills agents; routes staging; monitors budget
    SpawnConfig.kt               -- Per-agent spawn parameters
    KillSequence.kt              -- SIGTERM → grace → SIGKILL logic
  selector/
    EvolutionarySelector.kt      -- Computes RoleAllocation from substrate signals
    BottleneckDetector.kt        -- Reads KB/staging/tool signals to detect bottlenecks
    StasisDetector.kt            -- Detects when no progress is being made
  epigenetic/
    PostMortemScorer.kt          -- Scores dead agents: citation, KG, tool adoption signals
    LegacyProtocolExtractor.kt   -- Extracts Legacy Protocol from elite Death Notes
    CrossoverEngine.kt           -- Merges two Legacy Protocols of same role
    LegacyProtocolInjector.kt    -- Prepares privileged context prefix from protocol
  council/
    GenesisCouncil.kt            -- Orchestrates all 8 council roles in parallel
    CouncilSynthesisAgent.kt     -- Merges council outputs, runs consistency check
    CouncilConsistencyChecker.kt -- Validates StartingField for internal conflicts
    roles/
      TaskAnatomistAgent.kt
      IncentiveDesignerAgent.kt
      EcologistAgent.kt
      PromptArchitectAgent.kt
      KGArchitectAgent.kt
      ModelEcologistAgent.kt
      SeedCuratorAgent.kt
  inspector/
    Inspector.kt                 -- Passive continuous log reader, emits anomaly flags
    AnomalyFlag.kt               -- Typed anomaly signal
    Auditor.kt                   -- Mortal LLM agent spawned on high-severity flags
  metaplanner/
    MetaPlanner.kt               -- Mortal LLM agent with elevated read; rewrites Plan Registry
    PlanRegistry.kt              -- Current subgoal decomposition for a group
  watchdog/
    GlobalWatchdog.kt            -- Deterministic rule engine; triggers component signals
    WatchdogThresholds.kt        -- Configurable trigger thresholds
  EvoKammerEngine.kt             -- Engine entry point: start(config) -> manages the run loop
  DI.kt
```

## Lifecycle Manager

### Spawn Sequence

1. Read `RoleAllocation` from `EvolutionarySelector`
2. For each agent to spawn:
   - Query `SubstrateDatabaseFactory` for group DB
   - Claim a `WipTask` atomically (skip for Outliers)
   - Retrieve applicable `LegacyProtocol` from `LegacyProtocolRepository`
   - Build `AgentBootContext` with role prompt, budget token, credentials, WiP claim, Legacy Protocol
     — **`allocatedBudget` is set to `RoleAllocationEntry.budgetPerAgent`** (not profile.spawnBudget)
   - **In-process mode**: invoke `localAgentRunner(bootContext)` as a coroutine
   - **Shuru VM mode**: call `ShuruVmManager.spawnVm(VmConfig)` — gets `VmHandle`
     — `VmConfig` injects boot context as `EVOKAMMER_BOOT_CONTEXT` env var (not stdin)
     — Also injects `OPENROUTER_API_KEY` and `EVOKAMMER_GROUP_PROFILE` env vars
   - Register agent in `BudgetRepository` and emit `AGENT_SPAWN` event
3. Track all live agents in a `MutableStateFlow<Map<AgentId, VmHandle>>`

### LifecycleManager Constructor Fields

Key fields added/changed:
- `openRouterApiKey: String` — no longer suppressed; injected into Shuru VM env
- `groupProfile: GroupProfile` — the group's cost profile, injected as `EVOKAMMER_GROUP_PROFILE`

### Budget Monitoring

- Polls `BudgetRepository.getCurrentBalance(agentId)` every 5 seconds
- When balance <= floor: initiate kill sequence
- Kill sequence is `KillSequence.execute(handle)`:
  1. Send SIGTERM via `ShuruVmManager.signal(handle, SIGTERM)`
  2. Wait up to `gracePeriodSeconds` (default 30) for Death Note in staging
  3. Send SIGKILL via `ShuruVmManager.killVm(handle)`
  4. Capture staging area output
  5. Route Death Note to legacy protocol queue
  6. Emit `AGENT_DEATH` event

### Post-Kill Processing

After kill: `PostMortemScorer` is queued (deferred — needs citation signals from future gens).
Preliminary fitness score computed immediately from available signals.
Final fitness score updated 3-5 generations later when forward citation data is available.

## Evolutionary Selector

### Input Signals

- KB density per domain (from `KBRepository.countByDomain(groupId)`)
- Staging backlog by type (from `StagingRepository.countPendingByType(groupId)`)
- Tool failure rates (from `ToolRegistryRepository.getFailureRates(groupId)`)
- Plan Registry freshness (last updated generation vs. current generation)
- Stasis signal from `StasisDetector` (no successful staging promotions in last N gens)
- Inspector anomaly flags (via signal bus)

### Role Allocation Rules

- Total allocated budget across all agents ≤ group's per-generation budget ceiling
- At least 1 Critic agent every generation
- At least 1 Outlier agent every 5th generation; every 3rd if stasis detected
- Outlier agents have no WiP task assigned (task claim = null)
- If Staging backlog > threshold → spawn extra Critics
- If KB has many unclaimed research gaps → spawn more Researchers
- If tools failing often → spawn more Builders

## Epigenetic Inheritance Engine

### Fitness Scoring Formula

```
fitnessScore = (
  citationScore * 0.3
  + toolAdoptionScore * 0.25
  + stagingAcceptanceRate * 0.20
  + kgContributionScore * 0.15
  + budgetEfficiency * 0.10
)
```

- `citationScore`: count of future KB reads that cited this agent's promoted documents
- `toolAdoptionScore`: count of other agents that successfully used this agent's tools
- `stagingAcceptanceRate`: ratio of agent's submissions that were promoted (not rejected)
- `kgContributionScore`: accepted facts × average constraint-satisfaction score
- `budgetEfficiency`: output quality (promotions) per budget unit consumed

### Legacy Protocol Structure

```json
{
  "reasoning_posture": "Start with existing Dead Ends before proposing new approaches.",
  "tool_heuristics": {
    "preferred_tools": ["http_client_v3", "json_parser_v2"],
    "avoid_patterns": ["spawning tools without testing against edge cases first"]
  },
  "failure_map": [
    {"pattern": "Timeout on external APIs", "mitigation": "Always wrap in retry with backoff"}
  ],
  "kg_habits": {
    "when_to_emit_facts": "Only after evidence from ≥2 independent sources",
    "common_errors": ["missing units on numeric fields"]
  }
}
```

### Protocol Lifecycle

1. Extracted from top-20% agents (by role cohort) after generation boundary
2. If 2 agents qualify: `CrossoverEngine` produces merged protocol (Koog agent call)
3. Stored in `LegacyProtocolRepository` with `expiresAtGeneration = current + 2`
4. On next spawn of same role: injected as privileged system context block
5. Failure maps extracted from ALL protocols → promoted to Dead Ends Registry (public)
6. Outlier agents NEVER receive Legacy Protocols

## Genesis Council (Phase 8)

All council roles run as parallel Koog agents with a transient substrate.
Synthesis Agent runs last and is the only agent that produces the final StartingField.

Council agents use a dedicated transient DB (discarded after StartingField is produced).
The sealed test suite is written by the Synthesis Agent and stored in a write-once encrypted store.

### Consistency Check Categories

- KG schema has at least 1 hard and 1 soft constraint
- All group profiles have viable paths (no budget starvation)
- Check-in schedule doesn't exceed total generation ceiling
- Model assignments include at least 2 distinct families (main groups ≠ grader groups)
- Knowledge node graph has no orphan nodes (all reachable from root)

On failure: conflicting council role re-runs with conflict as additional context. Max 3 retries.

## Inspector

- Passive coroutine reading `EventLogRepository.observeLatest()`
- Emits `AnomalyFlag` to Watchdog signal bus when:
  - Agent burn rate is >3x role average without staging output
  - Staging acceptance rate < 20% for 3 consecutive generations
  - Same WiP task claimed and abandoned by 3+ different agents
  - Identical low-quality submissions from 5+ agents (knowledge pollution)
  - KG inconsistency rate spike (>10% rejection in one generation)

## Auditor

- Mortal Koog agent (has TTL and budget like any other agent)
- Spawned by Watchdog on high-severity Inspector flag
- Reads full Event Log (anonymised) and all in-flight Staging Areas
- Can write `SystemFlag` entries to `system_flags` table
- System flags consumed by `LifecycleManager` (freeze tool, quarantine doc) and `EvolutionarySelector`
- Cannot write to canonical substrate

## Meta-Planner

- Mortal Koog agent, spawned by Watchdog on stasis or cost ceiling approach
- Extended TTL + budget (configurable, typically 2x normal Planner)
- Reads Event Log for all groups (anonymised by group ID)
- Can write to any group's Plan Registry via privileged staging route
- Cannot read any group's canonical KB directly
- Cannot modify Constitutional Laws or extend any TTL
- Dies on TTL expiry — no persistent context between invocations (prevents bias accumulation)

## Global Watchdog

Deterministic rule engine — NOT an LLM. Pure coroutine logic reading the event bus.

Monitors and triggers:
- Stasis per group: no staging promotions in last `stasisGenerationThreshold` (default 3) gens
  → trigger Meta-Planner spawn + increase Leak Oracle frequency
- Cost ceiling approach (total spend > 80% of `globalBudgetCeiling`)
  → pause spawning + trigger Meta-Planner
- Inspector high-severity flag → spawn Auditor
- Generation milestone reached → open Check-in gate
- All groups submitted to final check-in → close gate + trigger final scoring
- KG density vs KB density disparity → nudge groups to invest in formalisation

The Watchdog NEVER modifies substrate content. It only emits `WatchdogSignal` events.

## EvoKammerEngine Entry Point

```kotlin
class EvoKammerEngine(
    private val lifeCycleManager: LifecycleManager,
    private val watchdog: GlobalWatchdog,
    private val inspector: Inspector,
    private val multiGroupCoordinator: MultiGroupCoordinator, // from competition module
    private val scoringEngine: ScoringEngine,                 // from competition module
    private val ipcService: EvoKammerIpcService               // from api module
) {
    suspend fun start(config: RunConfig) { ... }
    suspend fun pause() { ... }
    suspend fun resume() { ... }
    suspend fun terminate() { ... }
}
```
