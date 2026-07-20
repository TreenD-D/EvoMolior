# engine:orchestration

## Purpose

The `orchestration` module owns lifecycle control, role allocation, staged validation, Genesis
Council execution, fitness/Legacy behavior, observation signals, MetaPlanner behavior, and nested
group execution.

## Main Runtime Paths

| Area | Current Behavior |
|---|---|
| `LifecycleManager` | Spawns ordinary agents through a supplied local runner or an alternate Shuru branch, tracks liveness, and performs shutdown/rollup. Normal production uses the local branch. |
| `EvolutionarySelector` | Computes per-generation role allocation from available substrate and progress signals. |
| `CriticPipeline` | Applies metadata/KG/tool guards and model-backed review, then promotes, rejects, or arbitrates staged work inside `LifecycleBoundCriticPipeline`. |
| Genesis Council | Runs seven specialist roles in parallel, synthesis afterward, consistency handling, and sealed `StartingField` production. |
| Fitness and Legacy | Computes post-mortem fitness, extracts/crosses/injects protocols, expires protocols, and promotes usable failure maps to Dead Ends. |
| Inspector/Watchdog | Produces deterministic signals consumed by `EvoMoliorRunService`. |
| MetaPlanner | Special-purpose stasis response and plan-update route, not a full independent civilization. |
| Nested groups | Runs an inner group and rolls KB/KG/tools into its parent. Production triggering is narrow and stasis-driven. |

## Budget Semantics

Role allocations and agent ledgers enforce bounded per-agent/per-generation spawning.
`LifecycleManager` live balances now feed Watchdog telemetry using the authoritative computed
generation allocation. `BudgetCritical` is generation-scoped observability and does not pause later
replenished rounds. `GroupSummary.remainingBudget` remains active-agent balance, not cumulative run
spend.

Per-generation spawning is the retained product policy.

## Critic and KG

KG schema validation and model-backed conflict arbitration are connected through
`LifecycleBoundCriticPipeline`, which provides stable identity, finite budget, TTL, and a promoted
Death Note. This is the accepted fail-closed Critic/KG-Critic authority boundary.

## Hierarchy

One-level agent delegation lives in `agents`. `NestedGroupRunner` and `LifecycleManager` support
inner-group rollup. Production uses `NestedHierarchySpawnPlanner` in the API service to trigger at
most one nested instance per group after sustained stasis and multiple open tasks. This is partial
Phase 9 behavior, not general recursive hierarchy.

See
[`../../documentation/EvoMolior_Roadmap_and_Project_State.md`](../../documentation/EvoMolior_Roadmap_and_Project_State.md)
for phase evidence and unresolved exit criteria.
