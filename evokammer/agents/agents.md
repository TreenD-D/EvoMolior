# evokammer:agents — agents.md

## Purpose

The `agents` module owns the Koog integration layer. It creates Koog `AIAgent` instances
per role, registers all EvoKammer-specific tools in Koog's `ToolRegistry`, runs the agent
main loop inside Shuru microVMs, and manages the Death Note protocol.

This module is the **only** place where Koog API is used directly. All other modules interact
with agents through the contracts in `core` and events in `substrate`.

## Key Dependencies

- `ai.koog:koog-agents:0.7.2` — agent framework
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
    executor = simpleOpenRouterExecutor(apiKey),
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

## AgentRunner Loop

```
boot:
  1. Deserialise AgentBootContext from stdin
  2. Initialise Koin with substrate repositories (connected to group DB)
  3. Create AIAgent via EvoKammerAgentFactory
  4. Claim WiP task (if provided in boot context)

main loop:
  5. Run agent.run(buildInitialPrompt()) — Koog handles the LLM + tool call loop
  6. BudgetExhaustedException or maxIterations reached → proceed to death note

death note:
  7. Signal received (or budget exhausted): call WriteDeathNoteTool with current context
  8. Grace period: wait up to 30 seconds for death note to complete
  9. Hard kill: process exits

```

## Death Note Protocol (DeathNoteProtocol.kt)

1. `LifecycleManager` sends SIGTERM to the agent VM process (via Shuru CLI)
2. The signal is caught by `AgentRunner.installSignalHandler()`
3. Agent is given `gracePeriodSeconds` to write Death Note
4. If Death Note written: normal exit
5. If timeout: Lifecycle Manager sends SIGKILL; partial Death Note (if any) is captured

The Death Note goes to staging as `type = DEATH_NOTE`. Critic reviews are skipped for Death Notes
— they are always promoted to the Dead Ends / Legacy section of the substrate.

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
