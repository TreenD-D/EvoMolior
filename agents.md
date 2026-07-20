# EvoMolior — Project Root agents.md

## Project Purpose

EvoMolior is a Kotlin Multiplatform project whose primary JVM target hosts the **EvoMolior** system:
a civilisational multi-agent AI framework where stateless agents compound intelligence through a
persistent substrate (Knowledge Base + Knowledge Graph + Tool Registry) rather than through
individual memory. The core insight: _the agent is disposable; the substrate is not._

## Repository Structure

```
EvoMolior/
  composeApp/           -- KMP app; JVM target = EvoMolior desktop control panel
  designsystem/         -- Shared Compose UI component library
  evomolior/
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
| Agent framework | [Koog](https://github.com/JetBrains/koog/) 1.0.0 (`ai.koog:koog-agents`) |
| LLM provider | OpenRouter (via the shared OpenRouter executor helper) |
| Agent isolation | [Shuru](https://github.com/superhq-ai/shuru) microVMs (macOS, Apple Virtualization.framework) |
| Substrate storage | Room/SQLite (per-group isolated DB files) |
| UI framework | Compose Multiplatform (JVM desktop target for control panel) |
| DI | Koin |
| Async | kotlinx.coroutines |
| Serialisation | kotlinx.serialization |
| Networking | Ktor |
| Build | Kotlin Multiplatform Gradle (KMP), Kotlin 2.2.21 |

## Architecture Overview

Normal CLI, benchmark, self-improvement, and desktop runs execute ordinary agents in-process.
Shuru VM execution is implemented as an alternate lifecycle branch but is not selected by normal
production entry points. Native tool calls use connected constitutional interception, typed
side-effect gates, run-approved and role-aware source egress, and bounded tool subprocesses. The
desktop normally uses the real service in-process; Ktor IPC is an implemented alternate interface.

### Isolation Tiers (from spec §2.1)

- **Tier 0** — Global: Watchdog, Meta-Planner, Genesis Council
- **Tier 1** — Group: each competing group has its own isolated substrate DB + VM network
- **Tier 2 intent** — Shuru agent isolation is implemented but disconnected from normal production.
- **Tier 3 implementation** — `ToolSubSandbox` executes tools in bounded child processes.

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
- **Package structure** follows `com.achulkov.evomolior.<module>.<feature>` pattern
- **Koin modules** declared in each submodule's `DI.kt` file; wired in composeApp's `AppInitializer`
- **Room entities** are pure data classes with no business logic
- **No business logic in ViewModels** — delegate to use cases or repositories
- **Coroutines** everywhere for async; no blocking calls on main thread

## Spec References

- Current roadmap/state: `documentation/EvoMolior_Roadmap_and_Project_State.md`
- Full spec: `documentation/EvoMolior_Specification_v0.2.html`
- Implementation plan: `documentation/EvoMolior_Implementation_Plan_v1.0.md`
- Conversation context: `conversation.md`

## Sub-module agents.md Files

Each submodule has its own `agents.md` with detailed context:

- [`evomolior/agents.md`](evomolior/agents.md) — engine parent overview
- [`evomolior/core/agents.md`](evomolior/core/agents.md) — domain models and contracts
- [`evomolior/substrate/agents.md`](evomolior/substrate/agents.md) — Room DB schema
- [`evomolior/agents/agents.md`](evomolior/agents/agents.md) — Koog integration
- [`evomolior/orchestration/agents.md`](evomolior/orchestration/agents.md) — lifecycle and control
- [`evomolior/competition/agents.md`](evomolior/competition/agents.md) — multi-group mechanics
- [`evomolior/sandbox/agents.md`](evomolior/sandbox/agents.md) — isolation and enforcement
- [`evomolior/api/agents.md`](evomolior/api/agents.md) — IPC contracts

## Implementation Phase Status

Current phase status is maintained only in
`documentation/EvoMolior_Roadmap_and_Project_State.md`. This lowercase file is retained for
tooling compatibility and must not become an independent status authority.
