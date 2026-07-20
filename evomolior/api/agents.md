# engine:api

## Purpose

The `api` module contains both typed service contracts and major production implementations. It is
not an interfaces-only module.

## Responsibilities

| Area | Examples |
|---|---|
| Engine service | `EvoMoliorEngineService`, `EvoMoliorRunService`, run configuration and events. |
| IPC | `EvoMoliorIpcServer`, a Ktor server implementation with focused smoke coverage. |
| Grading | `GraderRunner`, blind tournament integration, sealed-suite evaluation. |
| Publication | `FinalSynthesiser`, output contract and asset validators. |
| Persistence/recovery | persisted run state, resume handling, run archive, completion records. |
| Continuation | explicit run-graph and selected prior-context routing. |
| Source grounding | preflight, verified catalog, claim/provenance checks. |
| Self-improvement/overseer | run orchestration, kernel interfaces, patch-bundle application. |

`EvoMoliorRunService` is the principal production authority graph. It coordinates Council output,
group lifecycle managers, Critic processing, competition, disclosure, grading, capture-map updates,
nested-group triggers, final publication, and archiving.

## Interfaces

The Compose desktop's normal JVM entry point passes a real service-backed view model directly. The
Ktor IPC server is implemented and tested but is not the default desktop composition. The CLI
remote dashboard is a separate authenticated server and subprocess launcher.

Scoring is hidden from ordinary agent/API surfaces and runs in a run-scoped child JVM. The API
sends one complete immutable round request over authenticated loopback JSON and receives only the
final result plus post-reveal telemetry. Worker startup, transport, decode, and process failures
fail closed; there is no same-process scorer or synthetic score fallback.

Continuation routing is explicit. Do not describe it as automatic learning by every new Genesis
Council from arbitrary previous tasks.

Current status and known API/runtime caveats are maintained in
[`../../documentation/EvoMolior_Roadmap_and_Project_State.md`](../../documentation/EvoMolior_Roadmap_and_Project_State.md).
