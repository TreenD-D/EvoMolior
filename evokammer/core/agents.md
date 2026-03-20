# evokammer:core — agents.md

## Purpose

The `core` module is the shared domain foundation for the entire EvoKammer engine.
It defines all enums, data classes, contracts, and the budget model that every other module
depends on. It has **zero external dependencies beyond Kotlin stdlib and kotlinx-serialization**.
No Room, no Koog, no Ktor here.

## Package Structure

```
com.achulkov.evokammer.core/
  model/
    Role.kt                  -- Agent role enum
    ActionType.kt            -- Budget-costed action types
    GroupProfile.kt          -- Asymmetric group economy profiles
    ConstitutionalLaw.kt     -- ALWAYS_BLOCK and ALWAYS_LOG rules
    SubmissionType.kt        -- doc | tool | fact | death_note | plan_update | dispatch
    AgentStatus.kt           -- active | dead | killed | completed
    RunStatus.kt             -- pending | running | paused | completed | failed
  contract/
    SubmissionPayload.kt     -- Agent → Staging Area contract
    PromotionRecord.kt       -- Critic → Substrate contract
    AgentBootContext.kt      -- Lifecycle Manager → Agent (at spawn) contract
    WatchdogSignal.kt        -- Watchdog → components signal bus contract
    LeakPacket.kt            -- Leak Oracle → Group Intelligence Inbox contract
    CheckinResult.kt         -- Scoring Engine → Groups (on gate close) contract
    RoleAllocation.kt        -- Evolutionary Selector → Lifecycle Manager contract
    StartingField.kt         -- Genesis Council output contract
    GradingSheet.kt          -- Grader Group output contract
  budget/
    BudgetLedger.kt          -- Atomic budget tracking with floor enforcement
    BudgetConfig.kt          -- Cost per action type, configurable per group profile
    BudgetAction.kt          -- Sealed class of budget-deductible actions
```

## Key Types

### Role

```kotlin
enum class Role {
    Researcher, Builder, Planner, Critic, Archivist,
    Educator, Connector, Outlier,
    Inspector, Auditor, MetaPlanner,
    CouncilTaskAnatomist, CouncilIncentiveDesigner, CouncilEcologist,
    CouncilPromptArchitect, CouncilKGArchitect, CouncilModelEcologist,
    CouncilSeedCurator, CouncilSynthesis,
    GraderResearcher, GraderBuilder, GraderCritic
}
```

### ActionType (with default costs)

| Action | Default Cost | Notes |
|---|---|---|
| READ_KB | 0.0 | Always free |
| READ_KG | 0.0 | Always free |
| SEARCH_KB | 0.1 | Light retrieval |
| QUERY_PATTERN_KG | 0.5 | Pattern traversal |
| WRITE_STAGING | 1.0 | Prevents spam |
| TOOL_EXEC_SUCCESS | 2.0 | Productive action |
| TOOL_EXEC_FAILURE | 4.0 | Penalty for thrashing |
| TOOL_EXEC_RETRY | 8.0 | Quadruple penalty for retry |
| INTER_AGENT_MESSAGE | 0.5 | Cheap but not free |
| LLM_CALL | proportional | Per output token |
| EMIT_FACT | 1.0 | KG fact proposal |
| EMIT_CONSTRAINT | 2.0 | KG constraint proposal |
| WRITE_DEATH_NOTE | 0.0 | Always free — guaranteed exit write |

### GroupProfile

Five predefined profiles defining asymmetric cost multipliers:

- **Library** — near-free web reads, expensive tool exec, small agent fleet
- **Factory** — expensive web reads, cheap tool exec, medium fleet
- **Commune** — medium costs, large fleet, near-free inter-agent messages
- **Monastery** — free reads/costly writes to staging, expensive tool exec, tiny fleet
- **Mercenary** — expensive reads, can convert between resource types, adaptive

### ConstitutionalLaw

Sealed class with two variants:
- `AlwaysBlock(rule: BlockRule)` — action never executes; kill signal emitted
- `AlwaysLog(rule: LogRule)` — action executes; event logged with high priority

Hard block rules (from spec §7):
- Write to canonical KB/Tools bypassing staging + Critic pipeline
- Read another agent's /workspace or staging area
- Outbound network not on role-specific allowlist
- Process fork outside tool sub-sandbox
- Read host environment variables
- Write to staging exceeding size quota
- Direct KG write bypassing Checker + Critic pipeline
- Modify KG constraints from within an agent lifecycle

### BudgetLedger

- Mutex-protected atomic decrements — no race past zero under concurrent coroutines
- Floor safety margin: emits kill signal when `balance <= floor` (not at zero)
- Retry detection: hashes `(toolName, argsJson)` — same hash in same lifecycle = retry multiplier
- Budget history appended to `EventLogEntry` on agent death (for fitness scoring)
- `deduct(action: BudgetAction): BudgetDeductResult` returns OK, FLOOR_REACHED, or BELOW_FLOOR

## Integration Contracts (Section 4 of spec)

All contracts are `@Serializable` data classes. Nullable fields signal optional data.
Every contract carries a `generation: Int` field for temporal tracing.

### AgentBootContext — injected into agent VM at spawn

```
agentId, role, rolePrompt, budgetToken, substrateReadCredential,
stagingWriteCredential, wipTaskClaim (nullable), legacyProtocol (nullable),
stageDisclosure, modelAssignment, groupId, runId
```

### SubmissionPayload — agent → staging

```
submissionId, agentId, role, type, originFlags (speculative, outlier),
content, wipTaskReference (nullable for death notes), budgetSignature, generation
```

The staging area MUST reject any submission with an invalid `budgetSignature`.
The signature is a signed token from the BudgetLedger confirming deduction occurred.

### WatchdogSignal — signal bus

```
signalId, type (enum), targetGroupIds, metadata (JSON), timestamp
```

Signal types: SPAWN_META_PLANNER, SPAWN_AUDITOR, INCREASE_LEAK_RATE,
OPEN_CHECKIN_GATE, CLOSE_CHECKIN_GATE, PAUSE_SPAWNING, TRIGGER_FINAL_SUBMISSION

## Dependency Rules

- `core` may NOT depend on any other evokammer module
- `core` may NOT depend on Room, Koog, Ktor, or Android libraries
- All other evokammer modules depend on `core`
- Use `@Serializable` on all contract data classes
- Use `@JvmInline value class` for typed IDs (AgentId, GroupId, RunId, NodeId)

## Serialisation Convention

All money/cost values are `Double`. Generation counters are `Int`. Timestamps are
`kotlinx.datetime.Instant`. IDs are inline value classes wrapping `String` (UUID format).
