# evokammer — Engine Parent Module agents.md

## Purpose

The `evokammer/` directory contains all Gradle submodules that make up the EvoKammer engine.
The engine is a standalone JVM process responsible for all agent orchestration, substrate
management, multi-group competition, and scoring. It has no Compose UI dependencies.

## Submodule Overview

| Module | Primary Responsibility |
|---|---|
| `core` | Domain models, integration contracts, budget model, constitutional laws |
| `substrate` | Room/SQLite persistence for all substrate layers |
| `agents` | Koog agent factory, tools, runner loop, Death Note protocol |
| `orchestration` | Lifecycle Manager, Evolutionary Selector, Genesis Council, Inspector, Meta-Planner |
| `competition` | Multi-group coordination, Leak Oracle, Dispatch Relay, Scoring Engine, Grader Groups |
| `sandbox` | Shuru microVM management, constitutional enforcement, tool sub-sandbox |
| `api` | IPC service contracts (engine exposes to desktop control panel) |

## Inter-Module Data Flow

```
Genesis Council (orchestration)
    → produces StartingField
    → LifecycleManager (orchestration) reads StartingField
        → spawns agents via ShuruVmManager (sandbox)
            → AgentRunner (agents) executes in Shuru microVM
                → reads Substrate (substrate) via Koog tools
                → writes to Staging Area (substrate) via Koog tools
                → calls OpenRouter LLM via Koog
            → ConstitutionalEnforcer (sandbox) intercepts every action
        → BudgetLedger (core) tracks consumption
        → Critic pipeline (orchestration) validates staged submissions
        → EvolutionarySelector (orchestration) adjusts next generation
    → MultiGroupCoordinator (competition) manages ring topology
    → LeakOracle (competition) delivers cross-group intelligence
    → ScoringEngine (competition) runs blind evaluations at check-ins
    → Watchdog (orchestration) monitors global health
```

## Engine Entry Point

The engine starts via `EvoKammerEngine.start(config: RunConfig)` in the `orchestration` module.
It exposes an IPC service (defined in `api`) for the desktop control panel to connect to.

## Isolation Tier Model

Every module must respect the four isolation tiers defined in the spec (§2.1):

- Actions that cross tier boundaries require explicit transformation (no raw sharing)
- Tier 1 (group) boundaries are enforced by the `competition` module's coordinator
- Tier 2 (agent) boundaries are enforced by the `sandbox` module's VM manager
- Tier 3 (tool sub-sandbox) boundaries are enforced by `ToolSubSandbox` in `sandbox`

## Key Architectural Constraints

- The engine must function without any Compose or Android dependencies
- All cross-module communication uses typed contracts from `core`
- No module may bypass the staging + Critic pipeline to write to canonical substrate
- The `sandbox` module is the only place that invokes the Shuru CLI
- The `api` module defines only interfaces; transport implementation is in `orchestration`

## Testing Approach

Each module has unit tests covering its core behaviours in isolation (contract mocking).
After Phase 4, isolation audits run adversarial scenarios (agent trying to read another group's
KB, agent trying to extend its own TTL, etc.). See the Implementation Plan §7 for full strategy.
