# evomolior Engine Modules

## Purpose

The `evomolior/` directory contains the JVM engine modules for contracts, persistence, agents,
orchestration, competition, sandbox utilities, API/service implementations, runtime composition,
and CLI interfaces. It has no Compose UI dependency; `composeApp` consumes the engine service.

Current implementation and phase status are authoritative only in
[`../documentation/EvoMolior_Roadmap_and_Project_State.md`](../documentation/EvoMolior_Roadmap_and_Project_State.md).

## Modules

| Module | Responsibility |
|---|---|
| `core` | Domain models, typed contracts, budget and configuration models. |
| `substrate` | Group-local and grader-local Room/SQLite persistence. |
| `agents` | Koog factories, prompts, tools, agent runner, delegation, and Death Notes. |
| `orchestration` | Lifecycle, selection, Critic pipeline, Council, Inspector, Watchdog, MetaPlanner, epigenetics, and nested groups. |
| `competition` | Group coordination, dispatches, leaks, disclosure, resources, graders, and scoring. |
| `sandbox` | Optional Shuru VM management, policy/enforcer implementations, sanitization, and tool subprocesses. |
| `api` | Service contracts and implementations, Ktor IPC, run orchestration, archives, grading, final publication, and continuation routing. |
| `runtime` | Production dependency composition. |
| `cli` | CLI runs, benchmarks, self-improvement, daemon, and remote dashboard. |

## Production Flow

```text
runtime/CLI/desktop
    -> EvoMoliorRunService
    -> Genesis Council and sealed StartingField
    -> group-local LifecycleManager instances
    -> in-process role agents
    -> role-gated tools and staging
    -> CriticPipeline promotion/rejection/arbitration
    -> competition, grading, disclosure, final publication, archive
```

Normal production constructors supply `localAgentRunner`, so ordinary agents execute in-process.
`LifecycleManager` also has a Shuru branch, but normal CLI, benchmark, self-improvement, and desktop
entry points do not select it. `ConstitutionalEnforcer` and `AllowlistProxy` exist but are not
called by normal production tools/requests. `ToolSubSandbox` is the connected subprocess boundary.

The Compose desktop normally receives a real in-process service-backed view model. Ktor IPC is
implemented in `api` and tested, but it is not the default desktop composition. The authenticated
remote dashboard is a separate CLI server.

## Constraints

- Preserve typed contracts across module boundaries.
- Preserve group and grader database separation.
- Route canonical KB/KG/tool writes through staging and the Critic pipeline.
- Treat specifications as intent and production callers as connectivity evidence.
- Do not describe optional Shuru or disconnected enforcers as universal production guarantees.
