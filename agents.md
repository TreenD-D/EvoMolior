# EvoMolior — Project Root agents.md

## Project Purpose

EvoMolior is a Kotlin Multiplatform project whose primary JVM target hosts the **EvoKammer** system:
a civilisational multi-agent AI framework where stateless agents compound intelligence through a
persistent substrate (Knowledge Base + Knowledge Graph + Tool Registry) rather than through
individual memory. The core insight: _the agent is disposable; the substrate is not._

## Repository Structure

```
EvoMolior/
  composeApp/           -- KMP app; JVM target = EvoKammer desktop control panel
  designsystem/         -- Shared Compose UI component library
  evokammer/
    core/               -- Domain models, contracts, budget model, constitutional laws
    substrate/          -- Room/SQLite persistence for all substrate layers
    agents/             -- Koog-based agent factory, tools, runner, Death Note protocol
    orchestration/      -- Lifecycle Manager, Evolutionary Selector, Genesis Council, Meta-Planner
    competition/        -- Multi-group coordination, Leak Oracle, Scoring Engine, Grader Groups
    sandbox/            -- Shuru microVM management, constitutional enforcer, tool sub-sandbox
    api/                -- IPC service definitions (engine <-> desktop)
  libs/
    auth/               -- Authentication abstraction (Firebase impl)
    subscription/       -- Subscription abstraction (RevenueCat / Adapty impls)
  build-logic/          -- Gradle convention plugins
```

## Tech Stack

| Concern | Technology |
|---|---|
| Agent framework | [Koog](https://github.com/JetBrains/koog/) 0.7.2 (`ai.koog:koog-agents`) |
| LLM provider | OpenRouter (via Koog `simpleOpenRouterExecutor`) |
| Agent isolation | [Shuru](https://github.com/superhq-ai/shuru) microVMs (macOS, Apple Virtualization.framework) |
| Substrate storage | Room/SQLite (per-group isolated DB files) |
| UI framework | Compose Multiplatform (JVM desktop target for control panel) |
| DI | Koin |
| Async | kotlinx.coroutines |
| Serialisation | kotlinx.serialization |
| Networking | Ktor |
| Build | Kotlin Multiplatform Gradle (KMP), Kotlin 2.2.21 |

## Architecture Overview

The system has two runtime components:

1. **Engine** (`:evokammer:*` modules) — runs agent orchestration, manages groups, substrates,
   scoring. Runs as a separate JVM process. Communicates with the desktop via IPC.
2. **Control Panel** (`composeApp`, JVM target) — Compose Desktop UI for configuring runs,
   monitoring live execution, and inspecting results.

Agent workloads execute inside Shuru microVMs, providing OS-level isolation. The
Constitutional Law Enforcer intercepts every agent action before it reaches the VM.

### Isolation Tiers (from spec §2.1)

- **Tier 0** — Global: Watchdog, Meta-Planner, Genesis Council
- **Tier 1** — Group: each competing group has its own isolated substrate DB + VM network
- **Tier 2** — Agent: each agent runs in its own Shuru microVM
- **Tier 3** — Tool: tools run in a nested sub-process inside the agent VM

## Module Dependency Graph

```
core  <──  substrate
core  <──  agents
core  <──  sandbox
core  <──  api
substrate <── agents
substrate <── orchestration
substrate <── competition
agents    <── orchestration
sandbox   <── orchestration
sandbox   <── agents
orchestration <── competition
api       <── composeApp (JVM)
```

## Global Coding Conventions

- **No hardcoded UI strings** — always use Compose resource strings (except previews/tests)
- **Javadoc on all public classes and functions** with `@param` references
- **Clear, concise inline comments** explaining the _why_, not the _what_
- **Package structure** follows `com.achulkov.evokammer.<module>.<feature>` pattern
- **Koin modules** declared in each submodule's `DI.kt` file; wired in composeApp's `AppInitializer`
- **Room entities** are pure data classes with no business logic
- **No business logic in ViewModels** — delegate to use cases or repositories
- **Coroutines** everywhere for async; no blocking calls on main thread

## Spec References

- Full spec: `EvoKammer_Specification_v0.2.html`
- Implementation plan: `EvoKammer_Implementation_Plan_v1.0.md`
- Conversation context: `conversation.md`

## Sub-module agents.md Files

Each submodule has its own `agents.md` with detailed context:

- [`evokammer/agents.md`](evokammer/agents.md) — engine parent overview
- [`evokammer/core/agents.md`](evokammer/core/agents.md) — domain models and contracts
- [`evokammer/substrate/agents.md`](evokammer/substrate/agents.md) — Room DB schema
- [`evokammer/agents/agents.md`](evokammer/agents/agents.md) — Koog integration
- [`evokammer/orchestration/agents.md`](evokammer/orchestration/agents.md) — lifecycle and control
- [`evokammer/competition/agents.md`](evokammer/competition/agents.md) — multi-group mechanics
- [`evokammer/sandbox/agents.md`](evokammer/sandbox/agents.md) — isolation and enforcement
- [`evokammer/api/agents.md`](evokammer/api/agents.md) — IPC contracts

## Implementation Phase Status

| Phase | Description | Status |
|---|---|---|
| 0 | Foundation — substrate, models, sandbox, budget | Implemented |
| 1 | Single group, single generation | Implemented (iterating) |
| 2 | Multi-generation, civilisational compounding | Implemented (iterating) |
| 3 | Knowledge Graph integration | Implemented (iterating) |
| 4 | Multi-group competition | Implemented (iterating) |
| 5 | Differential privacy leaks | Implemented (iterating) |
| 6 | Grader groups and blind scoring | Implemented (iterating) |
| 7 | Progressive disclosure and check-in curriculum | Implemented (iterating) |
| 8 | Genesis Council automation | Implemented (iterating) |
| 9 | Hierarchical civilisations and sub-agent delegation | Implemented (iterating) |
| 10 | Full integration and production hardening | In progress |
