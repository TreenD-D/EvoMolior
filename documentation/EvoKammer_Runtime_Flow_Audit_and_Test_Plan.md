# EvoKammer Runtime Flow Audit And Test Plan

Updated: 2026-05-24

This document reviews the current runtime flow against the requested invariants and turns the findings into an execution plan for the next implementation pass.

## Evidence Matrix

| Invariant | Status | Evidence | Next test to add |
|---|---|---|---|
| Each agent is launched in its own runtime instance | confirmed by code | `LifecycleManager` spawns one agent job or one Shuru VM per agent entry; `ShuruVmManager.spawnVm()` returns a distinct `VmHandle`. | `evokammer/orchestration/src/test/kotlin/com/achulkov/evokammer/orchestration/lifecycle/LifecycleManagerTest.kt` should assert distinct agent IDs and isolated live-agent entries for two spawned agents. |
| Fallbacks are implemented and fail closed | confirmed by code | Council timeout/error fallback, Shuru availability fallback, VM spawn failure surfacing, budget-floor kill, and tool sandbox error handling all exist. | `evokammer/orchestration/src/test/kotlin/com/achulkov/evokammer/orchestration/council/GenesisCouncilTest.kt` should assert fallback fields are used after timeout/error. |
| Hidden targets remain hidden from ordinary agents | confirmed by code | Ordinary prompts only receive `stageDisclosure`; `goalFramedContent()` is only exposed through the disclosure engine accessor intended for MetaPlanner. | `evokammer/agents/src/test/kotlin/com/achulkov/evokammer/agents/factory/RolePromptBuilderTest.kt` and `evokammer/api/src/test/kotlin/com/achulkov/evokammer/api/EvoKammerRunServiceModeGatingTest.kt` assert the ordinary prompt path stays constraint-framed. |
| Main and grading tracks are separate | confirmed by code | Competing groups use per-group `LifecycleManager` instances and substrate DBs; grader groups use a separate `GraderGroupManager` and grader DB roots. | `evokammer/substrate/src/test/kotlin/com/achulkov/evokammer/substrate/GraderDatabaseIsolationTest.kt` proves competing and grader staging rows stay in separate substrate roots. `evokammer/api/src/test/kotlin/com/achulkov/evokammer/api/grader/GraderRunnerIsolationTest.kt` proves the criteria phase writes only to grader substrate. |
| Budget limits are propagated into runtime action space | confirmed by code | `allocatedBudget` flows into `BudgetLedger`, budget floors trigger kills, watchdog pause exists, and resource yields feed future budget multipliers. | `evokammer/orchestration/src/test/kotlin/com/achulkov/evokammer/orchestration/lifecycle/LifecycleManagerTest.kt` should assert a live agent reaches budget floor only after at least one meaningful tool/action path. |
| Tool reuse is supported at registry level | verified by test | Tool promotion writes `ToolRegistryEntryEntity`; `ExecuteToolTool` resolves latest active tool by name/version, and the end-to-end regression proves the latest materialised executable is the one that runs. | `evokammer/agents/src/test/kotlin/com/achulkov/evokammer/agents/tools/ExecuteToolToolIntegrationTest.kt` |
| Tool executables are materialised into `/tools` | confirmed by code | `ToolMaterializer` writes versioned executables into the group tools directory, `EvoKammerRunService` seeds primordial tools through it, and `CriticPipeline` materialises promoted tools before registry insertion. | `evokammer/substrate/src/test/kotlin/com/achulkov/evokammer/substrate/tools/ToolMaterializerTest.kt` should assert executable creation and `evokammer/substrate/src/test/kotlin/com/achulkov/evokammer/substrate/ToolRegistryDaoTest.kt` should assert versioned lookup semantics. |

## What Is Already In Place

### MicroVM isolation

Agent execution is split at the `LifecycleManager` boundary. When `localAgentRunner` is non-null, agents run in-process for development and tests. When it is null, agents are launched through `ShuruVmManager.spawnVm()` with per-agent `VmConfig` mounts and network allowlists. The VM config mounts `/kb` read-only, `/tools` read-only, and `/staging` write-only, so the underlying isolation story is strong at the sandbox boundary.

Relevant files:
- `evokammer/orchestration/src/main/kotlin/com/achulkov/evokammer/orchestration/lifecycle/LifecycleManager.kt`
- `evokammer/sandbox/src/main/kotlin/com/achulkov/evokammer/sandbox/shuru/ShuruVmManager.kt`
- `evokammer/sandbox/src/main/kotlin/com/achulkov/evokammer/sandbox/shuru/VmConfig.kt`

### Fallbacks

The runtime already implements several fail-closed or fallback paths:
- Genesis Council falls back to `StartingField.default()` on timeout or error.
- Shuru availability is checked before the run; in-process managers can continue without Shuru.
- VM spawn failure is surfaced through `onSpawnFailed`.
- Budget-floor events trigger kill sequences and graceful Death Note handling.
- The disclosure engine falls back to injected disclosure stages only when council-authored stages are absent.
- Tool execution failure is returned as a structured error and charged to budget.

Relevant files:
- `evokammer/api/src/main/kotlin/com/achulkov/evokammer/api/service/EvoKammerRunService.kt`
- `evokammer/orchestration/src/main/kotlin/com/achulkov/evokammer/orchestration/lifecycle/LifecycleManager.kt`
- `evokammer/agents/src/main/kotlin/com/achulkov/evokammer/agents/tools/ExecuteToolTool.kt`
- `evokammer/competition/src/main/kotlin/com/achulkov/evokammer/competition/disclosure/ProgressiveDisclosureEngine.kt`

### Hidden-target handling

Ordinary agent prompts only receive constraint-framed stage disclosure through `AgentBootContext.stageDisclosure` and `RolePromptBuilder`. Goal-framed disclosure is exposed only through `ProgressiveDisclosureEngine.goalFramedContent()`, which is intended for MetaPlanner usage. This is the correct architectural split.

Relevant files:
- `evokammer/core/src/main/kotlin/com/achulkov/evokammer/core/contract/AgentBootContext.kt`
- `evokammer/agents/src/main/kotlin/com/achulkov/evokammer/agents/factory/RolePromptBuilder.kt`
- `evokammer/competition/src/main/kotlin/com/achulkov/evokammer/competition/disclosure/ProgressiveDisclosureEngine.kt`
- `evokammer/core/src/main/kotlin/com/achulkov/evokammer/core/model/Role.kt`

### Separate main and grading tracks

The main competing groups and the grader groups are structurally isolated:
- competing groups get their own `LifecycleManager` instances and per-group substrates;
- grader groups are represented by a separate `GraderGroupManager` and separate grader DBs;
- grading can run concurrently with the main round through the criteria/judging runners.

This is architecturally separate, but the execution model is not a single blanket “all groups always run in parallel” statement. Main-group spawn is managed in one loop, and grader execution is gated by the configured grader runner callbacks.

Relevant files:
- `evokammer/api/src/main/kotlin/com/achulkov/evokammer/api/service/EvoKammerRunService.kt`
- `evokammer/competition/src/main/kotlin/com/achulkov/evokammer/competition/grader/GraderGroupManager.kt`
- `evokammer/substrate/src/main/kotlin/com/achulkov/evokammer/substrate/SubstrateDatabaseFactory.kt`
- `evokammer/orchestration/src/main/kotlin/com/achulkov/evokammer/orchestration/di/OrchestrationModule.kt`

### Budget enforcement

Budget is not just decorative. The runtime wires:
- per-agent budgets from `RoleAllocationEntry.budgetPerAgent`;
- `BudgetLedger` floors that trigger kill sequences;
- a watchdog-level `BudgetCritical` pause when the run drops below 5% of the global ceiling;
- action-costed tools that deduct budget on write/execution;
- resource-node yield multipliers that feed back into future spawn budgets.

What is still not proven end-to-end is the “meaningful amount of action before death” invariant across both in-process and Shuru modes under realistic workloads. The controls exist, but the test suite should demonstrate that they are producing the intended operational envelope rather than just existing as counters.

Relevant files:
- `evokammer/core/src/main/kotlin/com/achulkov/evokammer/core/budget/BudgetLedger.kt`
- `evokammer/orchestration/src/main/kotlin/com/achulkov/evokammer/orchestration/lifecycle/LifecycleManager.kt`
- `evokammer/api/src/main/kotlin/com/achulkov/evokammer/api/service/EvoKammerRunService.kt`
- `evokammer/competition/src/main/kotlin/com/achulkov/evokammer/competition/resourcemap/NodeYieldApplier.kt`

### Tool reuse

Tool entries are persistently stored in the group substrate and `ExecuteToolTool` resolves the latest active tool by name/version from the registry. That means later agents in the same group can discover and reuse previously promoted tools through registry lookup.

The executable-materialisation path is now explicit:
- `ToolMaterializer` writes the executable into the group tools directory on the host.
- `LifecycleManager` mounts that directory read-only into the VM as `/tools`.
- `ExecuteToolTool` launches `/tools/<name>-v<version>` from the VM after the registry lookup selects the correct version.

Repo-wide search scope used for that conclusion:
- `rg -n "materializ|/tools|tool.*mount|copy.*tool|deploy.*tool|sync.*tool|tool.*executable|ToolPromotionService|promoteToolSubmission|build hook|packag|hook" . -g'*.kt' -g'*.kts' -g'*.gradle*' -g'*.md' -g'*.sh'`

So the reusable-part is real, and the host-side executable path is now part of the runtime contract rather than an inferred assumption.

Relevant files:
- `evokammer/orchestration/src/main/kotlin/com/achulkov/evokammer/orchestration/lifecycle/CriticPipeline.kt`
- `evokammer/substrate/src/main/kotlin/com/achulkov/evokammer/substrate/db/dao/ToolRegistryDao.kt`
- `evokammer/substrate/src/main/kotlin/com/achulkov/evokammer/substrate/db/entity/ToolRegistryEntryEntity.kt`
- `evokammer/agents/src/main/kotlin/com/achulkov/evokammer/agents/tools/ExecuteToolTool.kt`
- `evokammer/orchestration/src/main/kotlin/com/achulkov/evokammer/orchestration/lifecycle/LifecycleManager.kt`
- `evokammer/sandbox/src/main/kotlin/com/achulkov/evokammer/sandbox/shuru/ShuruVmManager.kt`

## Gaps To Verify Or Close

1. The grader track should be covered with at least one end-to-end test proving its execution path remains isolated from the competing groups. The DB-root isolation test, criteria-phase runner isolation test, and judging-phase prompt-wiring regression are now in place, but the live RoundJudge execution path still needs a dedicated regression.
2. Hidden-target protection needs regression coverage proving non-MetaPlanner prompts never contain goal-framed content.
3. Budget realism should be verified under both in-process and Shuru modes, not only via ledger unit tests.
4. The “parallel” story should be tested as a lifecycle property: competing groups, grader phases, and watchdog/criteria jobs should not block each other unnecessarily.

## Test Plan

### 1. MicroVM and isolation contract tests

Add or extend tests that verify:
- in-process mode does not require Shuru;
- Shuru mode writes a correct VM config with the expected read-only/read-write mounts;
- each agent gets its own `VmHandle` or coroutine job, not a shared runtime instance;
- the group substrate path is unique per `(runId, groupId)`.

Suggested files:
- `evokammer/orchestration/src/test/kotlin/com/achulkov/evokammer/orchestration/lifecycle/LifecycleManagerTest.kt`
- `evokammer/sandbox/src/test/kotlin/com/achulkov/evokammer/sandbox/shuru/VmConfigContractTest.kt`
- `evokammer/api/src/test/kotlin/com/achulkov/evokammer/api/EvoKammerRunServiceModeGatingTest.kt`

### 2. Fallback behavior tests

Add tests that explicitly simulate:
- Genesis Council timeout/error fallback;
- Shuru unavailable but in-process managers present;
- Shuru spawn failure and the surfaced failure event;
- tool execution timeout and sandbox exceptions;
- budget-floor termination and Death Note grace handling.

Suggested files:
- `evokammer/orchestration/src/test/kotlin/com/achulkov/evokammer/orchestration/council/GenesisCouncilTest.kt`
- `evokammer/orchestration/src/test/kotlin/com/achulkov/evokammer/orchestration/lifecycle/LifecycleManagerTest.kt`
- `evokammer/agents/src/test/kotlin/com/achulkov/evokammer/agents/tools/ExecuteToolToolTest.kt`
- `evokammer/agents/src/test/kotlin/com/achulkov/evokammer/agents/runner/DeathNoteProtocolTest.kt`

### 3. Hidden-target and prompt-leak tests

Add tests that assert:
- ordinary agent prompts include only constraint-framed disclosure;
- MetaPlanner-only accessors are not accidentally surfaced in ordinary prompts;
- stage content canonically comes from council-authored `stageContents` when present.

Acceptance criteria:
- A standard agent prompt includes the current constraint-framed stage text and the universal norms.
- A standard agent prompt does not contain `goalFramedContent`, hidden answer text, or target wording that appears only in MetaPlanner context.
- A prompt built from a council-authored `stageContents` list uses that content and not the injected fallback disclosure list.

Suggested files:
- `evokammer/api/src/test/kotlin/com/achulkov/evokammer/api/EvoKammerRunServiceModeGatingTest.kt`
- `evokammer/agents/src/test/kotlin/com/achulkov/evokammer/agents/factory/RolePromptBuilderTest.kt` if the module already has a prompt test location.

### 4. Main vs grading separation tests

Add tests that prove:
- the main competing groups and grader groups are created as separate substrate roots;
- grader criteria/judging can run while the main round is active;
- grader DB data is not visible in a competing group DB and vice versa;
- grader runner failure does not collapse the main competition loop.

Acceptance criteria:
- A grader DB path is distinct from every competing-group DB path for the same run.
- A grader-focused runner can complete while a main round is still running, without blocking group spawn/round progression.
- A grader DB query cannot see competing-group submissions, and a competing-group DB query cannot see grader-only staging rows.
- A grader runner exception is surfaced, but the main competition loop still reaches its post-round scoring path.

Suggested files:
- `evokammer/api/src/test/kotlin/com/achulkov/evokammer/api/EvoKammerRunServiceModeGatingTest.kt`
- `evokammer/api/src/test/kotlin/com/achulkov/evokammer/api/grader/GraderRunnerTest.kt`
- `evokammer/competition/src/test/kotlin/com/achulkov/evokammer/competition/*`

### 5. Budget realism tests

Add tests that assert:
- `allocatedBudget` flows into `BudgetLedger` exactly;
- agents hit budget floor only after doing a meaningful amount of work for the selected profile;
- `BudgetCritical` pauses spawning at the configured threshold;
- action-costed tools actually charge the right amount under repeated use;
- resource-node yields alter future spawn budgets.

Acceptance criteria:
- The `AgentBootContext.allocatedBudget` value is the amount used to initialise the live ledger.
- A spawned agent executes at least one tool or write path before the floor-kill path fires in the test fixture.
- A `BudgetCritical` signal drops the run into `PAUSED` state when remaining budget is below 5% of the ceiling.
- The same tool called twice with the same args is charged as a retry, not as a first execution.
- A claimed resource node changes the next round’s spawn budget multiplier for that group.

Suggested files:
- `evokammer/orchestration/src/test/kotlin/com/achulkov/evokammer/orchestration/lifecycle/LifecycleManagerTest.kt`
- `evokammer/core/src/test/kotlin/com/achulkov/evokammer/core/BudgetLedgerTest.kt`
- `evokammer/competition/src/test/kotlin/com/achulkov/evokammer/competition/resourcemap/NodeYieldApplierTest.kt`

### 6. Tool reuse tests

Add tests that prove:
- a promoted tool is discoverable by later agents in the same group through `ToolRegistryDao`;
- `ExecuteToolTool` resolves the latest active version correctly;
- the `/tools` mount contains the executable that matches the registry row.

Acceptance criteria:
- Promoting `tool-v2` after `tool-v1` causes `ExecuteToolTool` to resolve `tool-v2` as the latest active version.
- A tool promoted in `group-0` is not discoverable from `group-1`.
- The file path `/tools/<name>-v<version>` exists inside the agent VM and is executable when `ExecuteToolTool` tries to launch it.

Suggested files:
- `evokammer/agents/src/test/kotlin/com/achulkov/evokammer/agents/tools/ExecuteToolToolIntegrationTest.kt`
- `evokammer/agents/src/test/kotlin/com/achulkov/evokammer/agents/tools/ExecuteToolToolTest.kt`
- `evokammer/substrate/src/test/kotlin/com/achulkov/evokammer/substrate/ToolRegistryDaoTest.kt` if needed

## Execution Order

1. Keep the tool-promotion/materialisation path covered by regression tests.
2. Add the hidden-target prompt-leak tests.
3. Add the main-vs-grading parallelism/isolation tests.
4. Add the budget realism tests that exercise both lifecycle modes.
5. Re-run the module suites after each layer.

## Current Confidence

- MicroVM boundary: high confidence at the control-plane level.
- Fallbacks: high confidence, with room for more targeted regression tests.
- Hidden-target separation: medium-high confidence, should be locked with prompt-leak tests.
- Main/grading separation: high confidence at the runner-wiring and live tool-execution level.
- Budget realism: medium confidence, controls exist but operational proof is still needed.
- Tool reuse: high confidence for registry lookup, host-side executable materialisation, and end-to-end latest-version execution.
