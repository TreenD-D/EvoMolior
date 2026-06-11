# EvoKammer Phase 9/10 Execution Plan

Date: 2026-06-05

## User Intent Lock

Active goal:

- reconcile implementation plan and `AGENTS.md`
- keep `documentation/EvoKammer_Implementation_Plan_v1.0.md` unchanged
- apply reconciliation edits only to `AGENTS.md`
- fix and implement identified gaps from earlier stages
- continue with Stage 9 and Stage 10 implementation work
- do not start new runs for now
- create a separate file with the highest-quality run targets and full CLI commands for later review

## Current Evidence Baseline

Implementation-plan phase estimate:

- `documentation/EvoKammer_Implementation_Plan_v1.0.md`
  - `10.18 Current Phase Estimate`
  - claims Phase 1–8 mostly complete, Phase 3/6 partial, Phases 9–10 not started

Root `AGENTS.md` phase estimate:

- claims Phases 0–9 implemented/iterating and Phase 10 in progress

This mismatch must be resolved in `AGENTS.md` only, using direct code evidence.

## Locked Findings Before Reconciliation

### Phase 9

Evidence already found:

- sub-agent delegation scaffolding exists:
  - `evokammer/agents/src/main/kotlin/com/achulkov/evokammer/agents/delegation/SubAgentCoordinator.kt`
  - `evokammer/agents/src/main/kotlin/com/achulkov/evokammer/agents/tools/DelegateSubTaskTool.kt`
  - `evokammer/core/src/main/kotlin/com/achulkov/evokammer/core/model/Role.kt`
  - `evokammer/core/src/main/kotlin/com/achulkov/evokammer/core/contract/AgentBootContext.kt`
- nested-group scaffolding exists:
  - `evokammer/orchestration/src/main/kotlin/com/achulkov/evokammer/orchestration/nested/NestedGroupConfig.kt`
  - `evokammer/orchestration/src/main/kotlin/com/achulkov/evokammer/orchestration/nested/NestedGroupRunner.kt`

Gaps already confirmed:

- `DelegateSubTaskTool` is not currently wired into `EvoKammerAgentFactory`, so eligible agents
  cannot actually delegate at runtime yet.
- no evidence yet that `NestedGroupRunner` is invoked from production lifecycle code.
- no direct tests currently prove end-to-end Phase 9 behavior.

Interim status conclusion:

- Phase 9 is scaffolded/partial, not safely describable as implemented.

### Phase 10

Implementation plan requires:

- full end-to-end realistic-task operation
- chaos testing
- budget ceiling simulation
- leak-oracle audit
- score-seal audit
- measurable comparison against a baseline single-agent system

Current limitations:

- user explicitly requested no new runs for now
- several runtime hardening issues remain documented in:
  - `documentation/EvoKammer_Current_Session_Handoff.md`
  - `documentation/stale/EvoKammer_Crash_Resume_Recovery_Plan.md`
  - `documentation/stale/EvoKammer_Current_Status.md`

Interim status conclusion:

- Phase 10 remains hardening/integration work in progress, not completion-ready.

## Execution Plan

### Slice 1 — Durable Status Reconciliation Inputs

- verify which earlier-phase gaps remain open in code
- verify which Phase 9 components are scaffolded vs runtime-wired
- verify which Phase 10 hardening/audit hooks exist

### Slice 2 — Phase 9 Activation

Priority target:

- [x] wire `delegate_sub_task` into the runtime agent tool registry for eligible top-level agents
- [x] ensure sub-agents cannot recursively delegate
- [x] add focused tests

Implemented evidence:

- `evokammer/agents/src/main/kotlin/com/achulkov/evokammer/agents/factory/EvoKammerAgentFactory.kt`
  now installs `DelegateSubTaskTool` when `bootContext.role.canDelegateToSubAgents` and
  `!bootContext.isSubAgent`.
- `evokammer/agents/src/test/kotlin/com/achulkov/evokammer/agents/factory/EvoKammerAgentFactoryTest.kt`
  now covers:
  - eligible top-level roles get delegation enabled
  - ineligible roles do not
  - sub-agents do not get delegation even if their role normally can delegate
- verification passed:
  - `./gradlew --no-daemon -Pkotlin.incremental=false :evokammer:agents:test --tests com.achulkov.evokammer.agents.factory.EvoKammerAgentFactoryTest --tests com.achulkov.evokammer.agents.runner.DeathNoteProtocolTest`

Compatibility fix completed alongside that slice:

- `evokammer/agents/src/test/kotlin/com/achulkov/evokammer/agents/runner/DeathNoteProtocolTest.kt`
  fake DAO now implements the newer `StagingDao` methods added by persisted-resume work.

Secondary target:

- trace `NestedGroupRunner` call sites
- if missing, add a minimal integration entrypoint or document as still partial depending on scope

Current finding:

- nested hierarchy now has a narrow production trigger surface:
  - sustained MetaPlanner stasis now evaluates the group's open WiP backlog and can spawn one
    nested inner instance for that outer group
  - trigger planning is isolated in
    `evokammer/api/src/main/kotlin/com/achulkov/evokammer/api/service/NestedHierarchySpawnPlanner.kt`
- `NestedGroupRunner` also appears incomplete:
  - [x] fixed: it now extracts `kgFactIds` from `knowledgeGraphFactDao()`
  - [x] outer-group rollup now exists in `LifecycleManager`
- `LifecycleManager` now has a real nested-group control-plane entrypoint:
  - `spawnNestedGroup(...)`
  - `getLiveNestedGroupCount()`
  - `terminateAll()` now cancels tracked nested jobs
- nested results now roll back into the outer substrate:
  - KB, KG, and tool entities are copied into the parent `(runId, groupId)` with fresh IDs
  - KG provenance is extended with a nested-source marker
  - tool imports join the outer version chain when a same-name tool already exists
  - a `NESTED_GROUP_ROLLUP` event is emitted for auditability
- focused nested hierarchy tests now cover:
  - duplicate nested-group ID rejection
  - outer run/group validation
  - normal-completion callback + nested job cleanup
  - outer-substrate rollup behavior
  - direct `NestedGroupRunner` artifact extraction regression
  - stasis-trigger nested spawn planning
- additional verification passed:
  - `./gradlew --no-daemon -Pkotlin.incremental=false :evokammer:orchestration:test --tests com.achulkov.evokammer.orchestration.lifecycle.LifecycleManagerTest --tests com.achulkov.evokammer.orchestration.nested.NestedGroupRunnerTest`
  - `./gradlew --no-daemon -Pkotlin.incremental=false :evokammer:api:test --tests com.achulkov.evokammer.api.service.NestedHierarchySpawnPlannerTest --tests com.achulkov.evokammer.api.service.EvoKammerRunServiceEndToEndFlowTest`

Recommended next implementation slice:

- harden the first production trigger with a real runtime verification path once user-approved
  runs are allowed again
- keep Phase 9 marked `Partial` until hierarchy is verified on a real task and the nested runner
  gains a deterministic completion barrier

### Slice 3 — Earlier-Stage Gap Fixes

Use evidence from current runtime audits and gap documents to pick the highest-value still-open
non-Phase-9 regressions that block reliable Phase 10 hardening.

Current hardening progress:

- [x] Added a runtime event-ordering invariant for one-round runs in
  `evokammer/api/src/test/kotlin/com/achulkov/evokammer/api/service/EvoKammerRunServiceEndToEndFlowTest.kt`
- [x] That invariant exposed a real duplicate `RoundCompleted` emission in `EvoKammerRunService`
  and the duplicate outer emission was removed.
- [x] Added blind-gate lifecycle regressions in
  `evokammer/api/src/test/kotlin/com/achulkov/evokammer/api/service/BlindTournamentServiceTest.kt`
  covering failure-telemetry forwarding after reveal and double-start rejection.
- verification passed:
  - `./gradlew --no-daemon -Pkotlin.incremental=false :evokammer:api:test --tests com.achulkov.evokammer.api.service.BlindTournamentServiceTest --tests com.achulkov.evokammer.api.service.NestedHierarchySpawnPlannerTest --tests com.achulkov.evokammer.api.service.EvoKammerRunServiceEndToEndFlowTest`

### Slice 4 — `AGENTS.md` Reconciliation

Only after code evidence is updated:

- rewrite the implementation phase table in root `AGENTS.md`
- keep the implementation plan document intact
- prefer conservative wording such as `Partial`, `Scaffolded`, `In progress`, or
  `Implemented (iterating)` only where justified by live wiring/tests

Status:

- [x] root `AGENTS.md` reconciled conservatively without modifying the implementation plan
- current status table now marks:
  - Phase 3 `Partial`
  - Phase 6 `Partial`
  - Phase 9 `Partial`
  - Phase 10 `In progress`
- rationale is recorded inline in `AGENTS.md`

### Slice 5 — Run Targets File

Create a separate review-only file containing:

- highest-quality candidate runs to execute later
- exact CLI commands
- why each target is diagnostic
- expected output paths
- prerequisites/caveats

No runs are to be started during this goal unless the user explicitly changes that constraint.

## Completion Standard

Do not mark this goal complete until all of the following are true:

- `AGENTS.md` is reconciled to the implementation-plan evidence without editing the plan document
- at least one concrete Phase 9 gap is implemented and tested
- any chosen earlier-stage gaps are implemented and verified
- a separate run-target file exists with full CLI commands and rationale
- the final report maps each goal requirement to concrete files/tests/evidence
