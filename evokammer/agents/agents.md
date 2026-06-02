# evokammer:agents — agents.md

## Purpose

The `agents` module owns the Koog integration layer. It creates Koog `AIAgent` instances
per role, registers all EvoKammer-specific tools in Koog's `ToolRegistry`, runs the agent
main loop inside Shuru microVMs, and manages the Death Note protocol.

This module is the **only** place where Koog API is used directly. All other modules interact
with agents through the contracts in `core` and events in `substrate`.

## Key Dependencies

- `ai.koog:koog-agents:1.0.0` — agent framework
- `evokammer:core` — domain models and contracts
- `evokammer:substrate` — KB/KG/Staging read/write via repositories

## Package Structure

```
com.achulkov.evokammer.agents/
  factory/
    EvoKammerAgentFactory.kt     -- Creates AIAgent per role with correct model + tools
    RolePromptBuilder.kt         -- Assembles system prompts from StartingField + role
    ModelAssignmentResolver.kt   -- Maps Role → LLM model string from ModelAssignmentMap
  tools/
    ReadKBTool.kt                -- Semantic/keyword search on KB (cost: 0)
    ReadKGTool.kt                -- Pattern-match query on KG (cost: 0)
    SearchKBTool.kt              -- Vector search (cost: 0.1)
    QueryPatternKGTool.kt        -- Typed graph traversal (cost: 0.5)
    WriteStagingTool.kt          -- Submit doc/tool/fact to staging (cost: 1)
    ExecuteToolTool.kt           -- Run Tool Registry entry in sub-sandbox (cost: 2/4)
    SendMessageTool.kt           -- Inter-agent message on bus (cost: 0.5)
    ClaimTaskTool.kt             -- Claim WiP node (cost: 0)
    WriteDeathNoteTool.kt        -- Mandatory final write (cost: 0)
    EmitFactTool.kt              -- Propose KG fact to staging (cost: 1)
    EmitConstraintTool.kt        -- Propose KG soft constraint (cost: 2)
    CheckConsistencyTool.kt      -- Validate staged facts batch (cost: 0 per batch)
  runner/
    AgentRunner.kt               -- Main agent loop: boot → act → death note → die
    DeathNoteProtocol.kt         -- Signal → grace period → hard kill sequence
    AgentBootstrap.kt            -- Deserialises AgentBootContext, wires Koin in VM
  subagent/
    SubAgentDelegator.kt         -- Budget slice delegation, sub-agent spawn/wait/rollup
  DI.kt
```

## Koog Integration Pattern

### Creating an Agent

```kotlin
val agent = AIAgent(
    executor = createOpenRouterPromptExecutor(apiKey),
    systemPrompt = RolePromptBuilder.build(role, bootContext),
    llmModel = ModelAssignmentResolver.resolve(role, bootContext.modelAssignment),
    toolRegistry = ToolRegistry {
        tool(ReadKBTool(kbRepository, budgetLedger))
        tool(ReadKGTool(kgRepository, budgetLedger))
        tool(WriteStagingTool(stagingRepository, budgetLedger))
        tool(ExecuteToolTool(toolRepository, toolSubSandbox, budgetLedger))
        tool(ClaimTaskTool(wipRepository, budgetLedger))
        tool(WriteDeathNoteTool(stagingRepository))
        // KG tools added when KG feature is active (Phase 3+)
        tool(EmitFactTool(stagingRepository, budgetLedger))
        tool(QueryPatternKGTool(kgRepository, budgetLedger))
    },
    maxIterations = budgetConfig.maxIterations
)
```

### Tool Architecture

Every tool follows this pattern:
1. Implements `Tool<Args, Result>` or `SimpleTool<Args>` from Koog
2. Args is a `@Serializable` data class implementing `ToolArgs`
3. Execution first checks the `ConstitutionalEnforcer` — if blocked, throw `ConstitutionalViolationException`
4. Then deducts budget from `BudgetLedger` — if floor reached, throw `BudgetExhaustedException`
5. Then executes the actual operation
6. Returns typed result or error string

### Budget Deduction in Tools

Budget deduction happens **inside** the tool's `execute()` call, not before.
This ensures a deduction only occurs if the action actually ran (not on validation failure).
The BudgetLedger returns a `BudgetDeductResult` — if `BELOW_FLOOR`, the tool throws
`BudgetExhaustedException` which the AgentRunner catches to trigger the Death Note sequence.

## EvoKammer Koog Tools Reference

### ReadKBTool
- **Args**: `query: String`, `domain: String?`, `maxResults: Int`
- **Returns**: JSON array of KB entries (id, content snippet, trustScore, generation)
- **Cost**: 0 (reads are always free)
- **No constitutional check needed** — reading is always permitted

### WriteStagingTool
- **Args**: `type: SubmissionType`, `content: String`, `wipTaskRef: String?`, `speculative: Boolean`
- **Returns**: `submissionId: String` on success
- **Cost**: 1 unit; deducts before writing
- **Validates**: budget signature generation; rejects if budget exhausted
- If the agent has an assigned WiP task, the runtime populates `wipTaskRef` automatically.
- An explicit `wipTaskRef` argument still overrides the assigned task when needed.

### ExecuteToolTool
- **Args**: `toolName: String`, `toolVersion: Int?`, `argsJson: String`
- **Returns**: tool stdout (size-capped at 50KB), truncation indicator
- **Cost**: 2 on success, 4 on failure, 8 on retry (same tool+args hash)
- **Runs in**: `ToolSubSandbox` (nested process in agent VM)

### WriteDeathNoteTool
- **Args**: `summary: String`, `whatWorked: String`, `whatFailed: String`, `recommendations: String`, `kgHabits: String?`
- **Returns**: `submissionId: String`
- **Cost**: 0 — always runs even on budget floor
- **Special**: uses a reserved budget slice that cannot be depleted by other actions

### EmitFactTool
- **Args**: `subject: String`, `relation: String`, `object_: String`, `confidence: Float`, `evidence: String`
- **Returns**: `factId: String` (pending staging review)
- **Cost**: 1 unit
- **Validated by**: KG Consistency Checker before Critic review
- If the agent has an assigned WiP task, the runtime populates the staging submission's `wipTaskRef` automatically.
- An explicit `wipTaskRef` on the tool args still takes precedence when supplied.

### EmitConstraintTool
- **Args**: `constraintId: String`, `description: String`, `appliesToType: String`, `condition: String`, `violationPenalty: Double`, `rationale: String`
- **Returns**: `constraintId: String` (pending staging review)
- **Cost**: 2 units
- **Validated by**: Critic review before entering the canonical KG schema
- If the agent has an assigned WiP task, the runtime attaches that `wipTaskRef` automatically.

## AgentRunner Loop

```
boot:
  1. Deserialise AgentBootContext from EVOKAMMER_BOOT_CONTEXT env var (or /boot/context.json fallback)
  2. Read OPENROUTER_API_KEY and EVOKAMMER_GROUP_PROFILE env vars
  3. Initialise Koin with substrate repositories (connected to group DB)
  4. Create DeathNoteProtocol; register JVM shutdown hook for unexpected termination
  5. Create AIAgent via EvoKammerAgentFactory(bootContext, deathNoteProtocol=protocol)
     - BudgetLedger initial budget = bootContext.allocatedBudget (from EvolutionarySelector)
     - Falls back to GroupProfile.spawnBudget only when allocatedBudget == 0

main loop:
  6. Run agent.run(buildInitialPrompt()) — Koog handles the LLM + tool call loop
  7. On budget floor: WriteDeathNoteTool is always-free and can still execute
  8. If WriteDeathNoteTool writes a note: deathNoteProtocol.markDeathNoteWritten() cancels grace timer

death note:
  9. If agent completed without writing a note: signalGrace() starts the timer
 10. Grace period: wait up to gracePeriodSeconds for death note
 11. If grace expires without note: emergencyDeathNote() writes a plain-text fallback
 12. Process exits

```

## Death Note Protocol (DeathNoteProtocol.kt)

1. `LifecycleManager` sends SIGTERM to the agent VM process (via Shuru CLI)
2. The signal is caught by `AgentRunner.installSignalHandler()`
3. Agent is given `gracePeriodSeconds` to write Death Note
4. If Death Note written: normal exit
5. If timeout: Lifecycle Manager sends SIGKILL; partial Death Note (if any) is captured

The Death Note goes to staging as `type = DEATH_NOTE`. Critic reviews are skipped for Death Notes
— they are always promoted to the Dead Ends / Legacy section of the substrate.

## Lifecycle Contract Invariants

The following invariants must hold for every agent lifecycle. Violations indicate a wiring bug.

| # | Invariant | Checked By |
|---|-----------|-----------|
| 1 | `bootContext.allocatedBudget` matches the `RoleAllocationEntry.budgetPerAgent` for this agent | `LifecycleManagerTest.allocatedBudget_in_bootContext_matches_budgetPerAgent` |
| 2 | `BudgetLedger.initialBudget` == `bootContext.allocatedBudget` (when > 0) | `EvoKammerAgentFactoryTest` |
| 3 | `WriteDeathNoteTool` calls `deathNoteProtocol.markDeathNoteWritten()` | `DeathNoteProtocolTest` |
| 4 | Emergency fallback does NOT fire if `markDeathNoteWritten()` was called | `DeathNoteProtocolTest` |
| 5 | `ExecuteToolTool` sandbox exceptions deduct failure budget + return structured error | `ExecuteToolToolTest` |
| 6 | Shuru VM config has `EVOKAMMER_BOOT_CONTEXT` in `boot.env`, not in `stdin` | `VmConfigContractTest` |
| 7 | Shuru availability check runs AFTER lifecycle managers exist (mode is known) | `EvoKammerRunServiceModeGatingTest` |

## Test Coverage Summary

```
evokammer/agents/src/test/kotlin/com/achulkov/evokammer/agents/
  runner/
    DeathNoteProtocolTest.kt       -- DeathNoteProtocol grace period and mark-written contract
    AgentRunnerContractTest.kt     -- Boot context JSON round-trip, env var reading
  factory/
    EvoKammerAgentFactoryTest.kt   -- Budget initialisation from allocatedBudget vs profile default
  tools/
    ExecuteToolToolTest.kt         -- Tool missing / sandbox exception / success / failure / retry costs
evokammer/sandbox/src/test/kotlin/com/achulkov/evokammer/sandbox/shuru/
  VmConfigContractTest.kt          -- Shuru JSON uses env vars (not stdin) for boot context
evokammer/orchestration/src/test/kotlin/com/achulkov/evokammer/orchestration/lifecycle/
  LifecycleManagerTest.kt          -- Spawn, allocatedBudget propagation, live count, terminateAll
evokammer/api/src/test/kotlin/com/achulkov/evokammer/api/
  EvoKammerRunServiceModeGatingTest.kt  -- usesInProcessMode contract, needsShuru logic
```

## Sub-Agent Delegation (Phase 9)

`SubAgentDelegator` allows an agent to spawn a sub-agent within its own VM (not a new VM):

- Validates delegated budget ≤ parent remaining - floor
- Creates a child `AIAgent` with delegated budget
- Runs it concurrently (coroutine scope)
- When parent's budget exhausts, all child coroutines are cancelled
- Child outputs are appended to parent's staging area
- Child Death Notes are embedded in parent's Death Note

Sub-agents inherit the parent role. One-level depth maximum.

## Model Assignment

Default model assignments (configurable in StartingField `modelAssignment`):

| Role | Default Model | Rationale |
|---|---|---|
| Planner | `deepseek/deepseek-r1` | Deep reasoning |
| Critic | `anthropic/claude-3-5-sonnet` | Evaluation quality |
| Researcher | `google/gemini-flash-1.5` | High throughput |
| Builder | `qwen/qwen-2.5-coder-32b` | Code specialisation |
| Archivist | `google/gemini-flash-1.5` | Fast summarisation |
| Outlier | `meta-llama/llama-3.1-70b` | Different cognitive texture |
| Graders | `anthropic/claude-3-haiku` | Different family from main agents |
| MetaPlanner | `anthropic/claude-3-5-sonnet` | High-stakes |
| Council | `openai/gpt-4o` | Broad capability for initialisation |

All model strings are OpenRouter model identifiers.
