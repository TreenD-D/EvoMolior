# EvoMolior Current Architecture Deep Dive

**Status:** Maintained detailed subsystem reference.

The sole authority for current roadmap status, production connectivity, known gaps, and next
actions is
[`EvoMolior_Roadmap_and_Project_State.md`](EvoMolior_Roadmap_and_Project_State.md). This deep dive
explains components and flows in more detail; it does not override the canonical status
classifications. Section 21 is historical run evidence.

Primary evidence:

- Canonical state: `EvoMolior_Roadmap_and_Project_State.md`.
- Root instructions: `AGENTS.md`.
- Module instructions: `evomolior/*/agents.md`.
- Core contracts and runtime code under `evomolior/core`, `evomolior/agents`,
  `evomolior/substrate`, `evomolior/sandbox`, `evomolior/orchestration`,
  `evomolior/competition`, `evomolior/api`, `evomolior/cli`, and `evomolior/runtime`.
- Desktop control-panel code under `composeApp/src/jvmMain/kotlin/com/achulkov/evomolior/evomolior`.
- Tests named throughout this document.

This document describes the system from the smallest unit upward: one spawned agent, its model
backend, tools, budget, substrate, group lifecycle, inter-group competition, grader groups,
progressive disclosure, overseer/nested mechanisms, operator interfaces, and the complete
input-to-output run.

## 1. System Thesis

EvoMolior is a Kotlin Multiplatform project whose JVM target hosts the EvoMolior engine and a
desktop control panel. The engine is a civilisational multi-agent AI system: individual agents are
stateless and disposable, while durable intelligence compounds in persistent substrate layers.

The design center is not "one long-lived smart agent". It is a population of short-lived agents
working against:

- A canonical Knowledge Base.
- A typed Knowledge Graph.
- A Tool Registry.
- A staging pipeline reviewed by critics.
- Event logs, budget ledgers, dead ends, legacy protocols, and run archives.

The smallest agent can die without losing the civilization's memory. The substrate is the durable
memory.

## 2. Repository Map

The engine-relevant modules are:

- `evomolior/core`: pure domain models and serializable contracts.
- `evomolior/substrate`: Room/SQLite persistence for every durable substrate layer.
- `evomolior/agents`: Koog integration, agent factory, role prompts, tools, runner, Death Note
  protocol, LLM backend wiring.
- `evomolior/sandbox`: Shuru VM configuration, constitutional enforcement, source access,
  network allowlists, nested tool subprocess execution.
- `evomolior/orchestration`: lifecycle, role allocation, Genesis Council, epigenetic inheritance,
  inspector/watchdog/meta-planner, nested groups.
- `evomolior/competition`: group coordination, leaks, dispatches, resource map, scoring, grader
  groups, progressive disclosure.
- `evomolior/api`: engine service, IPC server, run service, final synthesis, archives, persisted
  state, CLI-facing run services.
- `evomolior/cli`: console run entrypoint and self-improvement launcher.
- `evomolior/runtime`: production runtime wiring.
- `composeApp/src/jvmMain/.../evomolior`: Compose Desktop control panel.

## 3. One Agent

### 3.1 Agent Identity and Spawn Payload

The spawn contract is `AgentBootContext` in
`evomolior/core/src/main/kotlin/com/achulkov/evomolior/core/contract/AgentBootContext.kt`.

It carries:

- `agentId`, `runId`, `groupId`.
- `role`.
- `rolePrompt`.
- `budgetToken`.
- `substrateDbPath`.
- `stagingMountPath`.
- Optional `artifactsMountPath`.
- Uploaded `inputArtifacts`.
- Current source-access allowlist and audit path.
- Optional `startingField`.
- Optional `wipTaskClaim`.
- Optional `legacyProtocol`.
- Current `stageDisclosure`.
- `modelAssignment`.
- `ipcBaseUrl`.
- `generation`.
- `gracePeriodSeconds`.
- Exact `allocatedBudget`.
- Optional `parentAgentId` and `isSubAgent`.
- Optional `dynamicNorms`.

This means an agent is born with a bounded local worldview: identity, task, role, budget, readable
artifacts, writable staging path, substrate location, and a model assignment. It is not born with
personal memory.

Implementation note: the comment in `AgentBootContext.kt` still says it is passed via stdin, but
current VM code and tests use environment injection via `EVOMOLIOR_BOOT_CONTEXT`. The current
implementation is in `VmConfig.kt` and `AgentRunner.kt`; the env-var contract is verified by
`evomolior/sandbox/src/test/kotlin/com/achulkov/evomolior/sandbox/shuru/VmConfigContractTest.kt`.

### 3.2 Role Semantics

Roles are defined in `evomolior/core/src/main/kotlin/com/achulkov/evomolior/core/model/Role.kt`.

Core civilisational roles:

- `Researcher`: discovers and synthesizes knowledge.
- `Builder`: builds and tests tools.
- `Planner`: decomposes objectives and manages work.
- `Critic`: reviews staged submissions.
- `Archivist`: organizes and improves canon.
- `Educator`: creates teaching/onboarding material from canon.
- `Connector`: bridges isolated knowledge clusters.
- `Outlier`: explores unconventional directions, does not receive legacy protocols, and does not
  claim ordinary WiP tasks.

Tier-0 or oversight roles:

- `Inspector`: passive log reader.
- `Auditor`: mortal anomaly investigator.
- `PerspectiveTaker`: fills missing viewpoints.
- `MetaPlanner`: stasis/cost-pressure planner with elevated write route to Plan Registry.

Genesis Council roles:

- `CouncilTaskAnatomist`.
- `CouncilIncentiveDesigner`.
- `CouncilEcologist`.
- `CouncilPromptArchitect`.
- `CouncilKGArchitect`.
- `CouncilModelEcologist`.
- `CouncilSeedCurator`.
- `CouncilSynthesis`.

Grader roles:

- `GraderResearcher`.
- `GraderBuilder`.
- `GraderCritic`.

`Role.kt` also defines delegation eligibility. For example, Builders may delegate to Builders or
Researchers, Planners may delegate to several civilisational roles, and Critics/Outliers cannot
delegate. This is used by the sub-agent delegation tool.

### 3.3 Koog Launch

Agent construction is centralized in
`evomolior/agents/src/main/kotlin/com/achulkov/evomolior/agents/factory/EvoMoliorAgentFactory.kt`.

The factory creates a Koog `AIAgent` using:

- `LlmExecutorFactory.create(...)`.
- `LlmExecutorFactory.resolveModel(...)`.
- `RolePromptBuilder`.
- A Koog `ToolRegistry`.
- `singleRunStrategy()`.
- `KotlinxSerializer`.
- `EmptyAssistantResponseRecoveryProcessor`.
- Blocking-provider failure handling via the factory utilities.

The current agent loop is intentionally finite: an agent receives a prompt and runs through Koog's
LLM/tool loop up to configured iteration limits, then it exits or is killed by lifecycle control.

Tests worth reading:

- `evomolior/agents/src/test/kotlin/com/achulkov/evomolior/agents/factory/EvoMoliorAgentFactoryTest.kt`.
- `evomolior/agents/src/test/kotlin/com/achulkov/evomolior/agents/factory/RolePromptBuilderTest.kt`.
- `evomolior/agents/src/test/kotlin/com/achulkov/evomolior/agents/runner/AgentRunnerContractTest.kt`.

### 3.4 LLM Backends: OpenRouter and Qwen Cloud

Backend selection is defined by
`evomolior/core/src/main/kotlin/com/achulkov/evomolior/core/config/LlmBackend.kt`.

Supported backend IDs:

- `openrouter`, API key env var `OPENROUTER_API_KEY`.
- `qwen-cloud`, API key env var `QWEN_API_KEY`.

The OpenRouter path is the historical default. It uses
`evomolior/agents/src/main/kotlin/com/achulkov/evomolior/agents/factory/OpenRouterExecutorFactory.kt`,
which builds a Koog `MultiLLMPromptExecutor` around `OpenRouterLLMClient`.

The Qwen Cloud path is implemented in:

- `evomolior/agents/src/main/kotlin/com/achulkov/evomolior/agents/factory/LlmExecutorFactory.kt`.
- `evomolior/agents/src/main/kotlin/com/achulkov/evomolior/agents/factory/DashscopeToolCompatibleLLMClient.kt`.
- `evomolior/agents/src/main/kotlin/com/achulkov/evomolior/agents/factory/LlmBackendToolSupportPolicy.kt`.

Qwen Cloud uses Koog's Dashscope client behind a reflection boundary and a custom compatibility
wrapper for tool-call continuation serialization. `LlmBackend.isTextRuntimeAvailable()` checks
that the Dashscope client classes exist on the runtime classpath before allowing a Qwen text run.
If those classes are missing, the CLI reports that Qwen Cloud is unavailable.

Canonical model selection is in
`evomolior/core/src/main/kotlin/com/achulkov/evomolior/core/config/ModelSelectionConfig.kt`.

Qwen Cloud runtime assignments use:

- `qwen3.7-plus`.
- `deepseek-v4-flash`.

Fast Qwen assignments are in
`evomolior/core/src/main/kotlin/com/achulkov/evomolior/core/config/FastModelSelectionConfig.kt`.

Evidence tests:

- `evomolior/agents/src/test/kotlin/com/achulkov/evomolior/agents/factory/LlmExecutorFactoryTest.kt`.
- `evomolior/agents/src/test/kotlin/com/achulkov/evomolior/agents/factory/OpenRouterModelResolverTest.kt`.
- `evomolior/agents/src/test/kotlin/com/achulkov/evomolior/agents/factory/QwenCloudTextBackendLiveTest.kt`.
- `evomolior/agents/src/test/kotlin/com/achulkov/evomolior/agents/factory/QwenCloudToolLoopLiveTest.kt`.
- `evomolior/cli/src/test/kotlin/com/achulkov/evomolior/cli/CliArgumentsTest.kt`.

### 3.5 Agent Tools

The installed tool registry in `EvoMoliorAgentFactory.kt` includes:

- `ReadKBTool`.
- `SearchKBTool`.
- `ReadKGTool`.
- `ReadRunArtifactTool`.
- `RequestSourceAccessTool`.
- `QueryPatternTool`.
- `CheckConsistencyTool`.
- `WriteStagingTool`.
- `WriteDeathNoteTool`.
- `EmitFactTool`.
- `EmitConstraintTool`.
- `ListToolsTool`.
- `ExecuteToolTool`.
- `SendMessageTool`.
- `ReadMessagesTool`.
- `ClaimTaskTool`.
- `DelegateSubTaskTool`, only when the role supports delegation and the agent is top-level.

If the backend is in text-only compatibility mode, the tool registry becomes empty and the prompt
is adjusted to ask the agent to produce plain-text output instead of tool calls.

Important implementation points:

- Agents do not write directly to canonical KB/KG/tool registry. They write to staging.
- `WriteDeathNoteTool` is always available in the ordinary tool path and can mark the
  `DeathNoteProtocol` as written.
- Run input files are exposed through `ReadRunArtifactTool`, using metadata from
  `AgentBootContext.inputArtifacts` and the mounted artifact path.
- Source access is explicit through `RequestSourceAccessTool`; approved hosts are audited.

### 3.6 Budget

Budget contracts live in:

- `evomolior/core/src/main/kotlin/com/achulkov/evomolior/core/budget/BudgetLedger.kt`.
- `evomolior/core/src/main/kotlin/com/achulkov/evomolior/core/budget/BudgetConfig.kt`.
- `evomolior/core/src/main/kotlin/com/achulkov/evomolior/core/model/ActionType.kt`.

`BudgetLedger` is mutex-protected and returns:

- `OK`.
- `FLOOR_REACHED`.
- `BELOW_FLOOR`.

The ledger supports:

- Action pricing through `BudgetConfig`.
- Always-free actions, including death-note writes.
- Retry detection using a tool key window.
- Signed staging tokens with HMAC-SHA256.
- Raw budget deduction for sub-agent budget carve-outs.
- Serializable snapshots for persistence.

Agent factory behavior is important: it uses `bootContext.allocatedBudget` if it is positive;
otherwise it falls back to the group's profile spawn budget. This keeps the Evolutionary
Selector's per-agent allocation authoritative.

### 3.7 Death Note

The Death Note protocol is in:

- `evomolior/agents/src/main/kotlin/com/achulkov/evomolior/agents/runner/DeathNoteProtocol.kt`.
- `evomolior/agents/src/main/kotlin/com/achulkov/evomolior/agents/tools/WriteDeathNoteTool.kt`.
- `evomolior/orchestration/src/main/kotlin/com/achulkov/evomolior/orchestration/lifecycle/LifecycleManager.kt`.

The pattern is:

1. Agent runs.
2. Lifecycle may send graceful termination.
3. Agent receives a grace window.
4. Agent writes a Death Note to staging.
5. If no Death Note is written, the protocol writes or captures a fallback.
6. Death-note content feeds legacy/dead-end learning.

The key property is that useful failure information survives agent death.

## 4. Sandbox and Enforcement

### 4.1 Execution Modes

`LifecycleManager` supports two modes:

- In-process coroutine execution, selected by normal CLI, benchmark, self-improvement, and desktop
  production composition as well as tests.
- Shuru VM execution, available when no local runner is provided but not selected by normal
  production entry points.

This is visible in
`evomolior/orchestration/src/main/kotlin/com/achulkov/evomolior/orchestration/lifecycle/LifecycleManager.kt`.

The alternate VM contract is in:

- `evomolior/sandbox/src/main/kotlin/com/achulkov/evomolior/sandbox/shuru/VmConfig.kt`.
- `evomolior/sandbox/src/main/kotlin/com/achulkov/evomolior/sandbox/shuru/ShuruVmManager.kt`.

`VmConfig` injects:

- `EVOMOLIOR_BOOT_CONTEXT`.
- `EVOMOLIOR_LLM_BACKEND`.
- `OPENROUTER_API_KEY`.
- The backend-specific API key variable, such as `QWEN_API_KEY`.
- `EVOMOLIOR_GROUP_PROFILE`.

It mounts:

- `/kb`.
- `/tools`.
- `/staging`.
- Optional `/artifacts`.

### 4.2 Constitutional Enforcement

The enforcement layer is
`evomolior/sandbox/src/main/kotlin/com/achulkov/evomolior/sandbox/enforcer/ConstitutionalEnforcer.kt`.

The current enforcer can block:

- Direct KB writes.
- Cross-agent workspace reads.
- Unallowed network egress.
- Forking outside the tool sandbox.
- Environment-variable reads.
- Oversized staging writes.
- Direct KG writes.
- KG constraint modifications.
- TTL extension attempts.
- Cross-group substrate access.

It can log:

- Tool calls.
- Web requests.
- Inter-agent messages.
- Staging writes.
- LLM calls.
- Budget deductions.

The rule engine is infrastructure-oriented rather than prompt guidance, but it is currently
**disconnected**: normal production tool/action paths do not invoke
`ConstitutionalEnforcer.intercept()`. Its presence and unit tests do not establish universal
enforcement.

### 4.3 Network and Source Access

Network/source access code lives in:

- `evomolior/sandbox/src/main/kotlin/com/achulkov/evomolior/sandbox/network/SourceAccessPolicy.kt`.
- `evomolior/sandbox/src/main/kotlin/com/achulkov/evomolior/sandbox/network/SourceAccessDecisionStore.kt`.
- `evomolior/sandbox/src/main/kotlin/com/achulkov/evomolior/sandbox/network/ContextUrlAllowlistExtractor.kt`.
- `evomolior/sandbox/src/main/kotlin/com/achulkov/evomolior/sandbox/network/AllowlistProxy.kt`.

The policy normalizes hosts, rejects local/private/unsafe hosts, infers objective-relevant hosts,
and writes durable audit records. Tests include:

- `evomolior/sandbox/src/test/kotlin/com/achulkov/evomolior/sandbox/network/SourceAccessPolicyTest.kt`.
- `evomolior/sandbox/src/test/kotlin/com/achulkov/evomolior/sandbox/network/ContextUrlAllowlistExtractorTest.kt`.

The source-access policy paths are connected. `AllowlistProxy` itself is not a universal outbound
boundary and has no normal production `evaluate()` caller.

### 4.4 Tool Sub-Sandbox

`evomolior/sandbox/src/main/kotlin/com/achulkov/evomolior/sandbox/toolsandbox/ToolSubSandbox.kt`
runs tools as nested child processes. It caps stdout, captures stderr, enforces timeouts, and
returns structured execution results to `ExecuteToolTool`.

Conceptually:

`agent -> ExecuteToolTool -> ToolSubSandbox -> child process -> bounded result`.

## 5. Substrate: The Persistent Civilization

### 5.1 Database Boundaries

The Room database is defined in
`evomolior/substrate/src/main/kotlin/com/achulkov/evomolior/substrate/db/EvoMoliorDatabase.kt`.

`SubstrateDatabaseFactory` creates database files per run/group and separate grader databases:

- Competing groups: `.../evomolior/runs/{runId}/groups/{groupId}/substrate.db`.
- Grader groups: `.../evomolior/runs/{runId}/graders/{graderGroupId}/substrate.db`.

This gives group-level storage isolation.

Evidence:

- `evomolior/substrate/src/main/kotlin/com/achulkov/evomolior/substrate/SubstrateDatabaseFactory.kt`.
- `evomolior/substrate/src/test/kotlin/com/achulkov/evomolior/substrate/GraderDatabaseIsolationTest.kt`.

### 5.2 Major Tables

The current database includes:

- `KnowledgeBaseEntryEntity`.
- `KnowledgeGraphFactEntity`.
- `ToolRegistryEntryEntity`.
- `StagingSubmissionEntity`.
- `WipTaskEntity`.
- `EventLogEntryEntity`.
- `LegacyProtocolEntity`.
- `TrustScoreEntity`.
- `DeadEndsEntity`.
- `KGSchemaEntity`.
- `KgSchemaConstraintEntity`.
- `MetaArchiveEntryEntity`.
- `SealedTestSuiteEntity`.
- `RunConfigEntity`.

### 5.3 Knowledge Base

The KB stores promoted documents and summaries with trust, generation, domain, group, and run
metadata. Reads are normal agent operations; writes are not direct. Writes go through staging and
Critic review.

The current DAO supports keyword/domain queries, active counts, trust updates, and decay-oriented
queries.

### 5.4 Knowledge Graph

The KG stores facts as triples with confidence, trust, provenance/status, generation, group, and
run. Agents propose KG facts through `EmitFactTool`. Consistency and critic review determine
promotion.

Important related files:

- `evomolior/agents/src/main/kotlin/com/achulkov/evomolior/agents/tools/EmitFactTool.kt`.
- `evomolior/agents/src/main/kotlin/com/achulkov/evomolior/agents/tools/EmitConstraintTool.kt`.
- `evomolior/orchestration/src/main/kotlin/com/achulkov/evomolior/orchestration/lifecycle/CriticPipeline.kt`.
- `evomolior/orchestration/src/main/kotlin/com/achulkov/evomolior/orchestration/lifecycle/KGConsistencyChecker.kt`.

### 5.5 Tool Registry

The Tool Registry stores versioned tools, input/output schemas, code, status, success/failure
statistics, author, domain, generation, group, and run. Builders can submit tools through staging;
accepted tools become reusable infrastructure for later agents.

Execution uses `ListToolsTool` and `ExecuteToolTool`.

### 5.6 Staging

Staging is the boundary between agent output and canon.

Typical submission types are defined in
`evomolior/core/src/main/kotlin/com/achulkov/evomolior/core/model/SubmissionType.kt` and include:

- Documents.
- Tools.
- Facts.
- Death notes.
- Plan updates.
- Dispatches.
- Generated outputs.

Critic pipelines promote, reject, dispute, or route staged submissions.

### 5.7 WiP Tasks and Deliverable Skeleton Tree

WiP tasks are durable work nodes claimable by agents. The deliverable skeleton tree is richer: it
tracks the semantic structure of the evolving deliverable, ownership, trust, grading history, gaps,
dead-end refs, and citation chains.

Core contract:

- `evomolior/core/src/main/kotlin/com/achulkov/evomolior/core/contract/DeliverableSkeletonTree.kt`.

Store/refiner:

- `evomolior/substrate/src/main/kotlin/com/achulkov/evomolior/substrate/DeliverableSkeletonTreeStore.kt`.
- `evomolior/substrate/src/main/kotlin/com/achulkov/evomolior/substrate/DeliverableSkeletonTreeRefiner.kt`.

The Evolutionary Selector can use `DeliverableSkeletonTreeSummary` to bias role allocation.

### 5.8 Event Log

The event log is the audit substrate. It records lifecycle, budget, staging, scoring, selector,
and coordination events. Many higher-level behaviors consume the event log:

- Inspector/anomaly detection.
- Watchdog/meta-planner signals.
- Desktop event streams.
- Archive/post-run analysis.

### 5.9 Legacy Protocols and Dead Ends

Legacy Protocols encode useful reasoning posture, tool heuristics, failure maps, and KG habits
extracted from high-performing or informative agents. Dead Ends preserve failed paths so future
agents do not rediscover them as if new.

Key files:

- `evomolior/orchestration/src/main/kotlin/com/achulkov/evomolior/orchestration/epigenetic/FitnessScorer.kt`.
- `evomolior/orchestration/src/main/kotlin/com/achulkov/evomolior/orchestration/epigenetic/LegacyProtocolExtractor.kt`.
- `evomolior/orchestration/src/main/kotlin/com/achulkov/evomolior/orchestration/epigenetic/CrossoverEngine.kt`.

## 6. One Group

### 6.1 Group Profile

Group profiles are defined in
`evomolior/core/src/main/kotlin/com/achulkov/evomolior/core/model/GroupProfile.kt`.

Current profiles:

- `Library`: cheap web fetch, expensive tools, knowledge-heavy.
- `Factory`: expensive web fetch, cheap tool execution.
- `Commune`: cheap inter-agent messaging, larger fleet.
- `Monastery`: depth-oriented, costly writes and tools, small fleet.
- `Mercenary`: adaptive costs.

Each profile defines:

- Cost multipliers.
- Spawn budget.
- Max fleet size.
- KG emphasis.
- KB emphasis.

### 6.2 Lifecycle Manager

One `LifecycleManager` owns one group's live agents for one run. It:

- Receives role allocation.
- Builds `AgentBootContext`.
- Resolves model assignment or model-pool choice.
- Claims WiP tasks.
- Loads legacy protocols.
- Spawns agents in-process or in Shuru.
- Tracks live agents.
- Polls budgets.
- Terminates agents.
- Emits lifecycle and budget events.

Core file:

- `evomolior/orchestration/src/main/kotlin/com/achulkov/evomolior/orchestration/lifecycle/LifecycleManager.kt`.

Evidence test:

- `evomolior/orchestration/src/test/kotlin/com/achulkov/evomolior/orchestration/lifecycle/LifecycleManagerTest.kt`.

### 6.3 Evolutionary Selector

The Evolutionary Selector computes role allocation per generation/round. It is in
`evomolior/orchestration/src/main/kotlin/com/achulkov/evomolior/orchestration/selector/EvolutionarySelector.kt`.

It reads substrate signals such as:

- Active KB count.
- Staging backlog.
- Tool failure rate.
- Unclaimed tasks.
- Plan freshness.
- Deliverable skeleton gaps, contested nodes, dead ends, trust, and complexity.

Hard tendencies in the current implementation:

- Spawn at least a Critic.
- Spawn at least a Researcher.
- Spawn a Builder, with extras when tools fail often.
- Add Outlier on cadence, especially under stasis.
- Add extra Critics for backlog/contested deliverables.
- Add Researchers for unclaimed tasks/open gaps.
- Add Archivists as KB/dead-end load grows.
- Add Planners when the plan is stale or deliverable trust is low.
- Enforce bounded heterogeneity so the group does not collapse into one role.

The selector logs `SELECTOR_DECISION` events with role counts, budget ceiling, signals, and
diversity metrics.

### 6.4 A Group Round

Inside a round, a group generally does:

1. Lifecycle Manager spawns selected roles.
2. Agents read context, use tools, claim tasks, and write staging submissions.
3. Agents die normally or are terminated at round end.
4. Critic pipeline reviews staging.
5. Fitness and epigenetic systems score outputs and extract legacy.
6. Trust decay and dead-end/legacy updates maintain substrate quality.
7. Scoring and disclosure systems update group position.

## 7. Genesis and Starting Field

The run seed is `StartingField`, defined in
`evomolior/core/src/main/kotlin/com/achulkov/evomolior/core/contract/StartingField.kt`.

It contains:

- Run ID and objective.
- Global budget ceiling.
- Generation ceiling.
- Group count and profiles.
- Ring topology and topology kind.
- Check-in milestones.
- Leak interval.
- Dispatch lag.
- Grader group count.
- Role prompts.
- Model assignments and model pools.
- KG schema.
- Action costs.
- Score normalization.
- Node graph.
- Deliverable skeleton tree seed.
- Primordial KB/KG/tools.
- Input artifacts.
- Continuation metadata.
- Death Note schema.
- Legacy protocol template.
- Sealed test suite and hash.
- Founding norms.
- Stage contents.
- Routed context summaries.
- Feedforward/run graph metadata.
- Consistency report.

Genesis Council implementation is in:

- `evomolior/orchestration/src/main/kotlin/com/achulkov/evomolior/orchestration/council/GenesisCouncil.kt`.
- `evomolior/agents/src/main/kotlin/com/achulkov/evomolior/agents/factory/CouncilRunner.kt`.
- `evomolior/agents/src/main/kotlin/com/achulkov/evomolior/agents/factory/CouncilRolePrompts.kt`.

In full runs, the council can design the starting field. In CLI test runs, `EvoMoliorRunService`
skips Genesis Council and uses `StartingField.default(...)` with the requested group/round shape
and budget override.

## 8. Inter-Group Setup

### 8.1 Group Coordinator

Inter-group mechanics are centralized in
`evomolior/competition/src/main/kotlin/com/achulkov/evomolior/competition/coordinator/GroupCoordinator.kt`.

It owns:

- `LeakOracle`.
- Optional `DifferentialPrivacyTransform`.
- Dispatch relay and formal delayed dispatch relay.
- Dispatch trust scoring.
- Grader group compatibility facade.
- Resource map and yield applier.
- Grading result stream.

`MultiGroupCoordinator.kt` is a plan-aligned facade over `GroupCoordinator`.

### 8.2 Topology

`RingTopology` in
`evomolior/competition/src/main/kotlin/com/achulkov/evomolior/competition/coordinator/RingTopology.kt`
defines adjacency. For N groups, each group has left/right neighbors in a ring. In a two-group
ring, both directions collapse to one neighbor.

The current `StartingField` also supports a `GroupTopologyKind` and optional dispatch topology
adaptation spec for trust-aware rewiring.

### 8.3 Leaks

Leak logic is in:

- `evomolior/competition/src/main/kotlin/com/achulkov/evomolior/competition/leaks/LeakOracle.kt`.
- `evomolior/competition/src/main/kotlin/com/achulkov/evomolior/competition/leaks/DifferentialPrivacyTransform.kt`.

`LeakOracle` enforces:

- Per-source quota.
- Per-pair migration ceiling.
- Domain allowlist.
- Optional corruption/noise.
- Dynamic per-group ceiling patched by `GroupCoordinator.updateMigrationCeiling(...)`.

The 15% information migration cap is implemented by computing a ceiling from active KB count and
patching the oracle.

If an API key is present, `DifferentialPrivacyTransform` uses a Koog agent to strip source-traceable
details from leaks. It removes exact code identifiers, URLs, service names, package paths, and long
verbatim content while preserving conceptual patterns.

Tests:

- `evomolior/competition/src/test/kotlin/com/achulkov/evomolior/competition/leaks/LeakOracleTest.kt`.
- `evomolior/competition/src/test/kotlin/com/achulkov/evomolior/competition/coordinator/GroupCoordinatorLeakTest.kt`.

### 8.4 Dispatches

Dispatches are formal group-to-group messages. The coordinator uses:

- `evomolior/competition/src/main/kotlin/com/achulkov/evomolior/competition/dispatch/DispatchRelay.kt`.
- `evomolior/competition/src/main/kotlin/com/achulkov/evomolior/competition/dispatch/FormalDispatchRelay.kt`.
- `evomolior/competition/src/main/kotlin/com/achulkov/evomolior/competition/dispatch/DispatchTrustScoring.kt`.

Dispatches can be delayed by `dispatchLagGenerations`, flushed at round boundaries, and trust-scored
per source/target.

### 8.5 Resource Map

Resource map files include:

- `evomolior/competition/src/main/kotlin/com/achulkov/evomolior/competition/resourcemap/ResourceMapEngine.kt`.
- `evomolior/competition/src/main/kotlin/com/achulkov/evomolior/competition/resourcemap/ResourceMap.kt`.
- `evomolior/competition/src/main/kotlin/com/achulkov/evomolior/competition/resourcemap/ResourceNode.kt`.
- `evomolior/competition/src/main/kotlin/com/achulkov/evomolior/competition/resourcemap/NodeYieldApplier.kt`.

Resource nodes turn high-quality work into competitive advantage. A group can claim or recapture
nodes; held nodes can modify future resource yields/budget multipliers.

## 9. Group Types and Competitiveness

Competition arises from several independent asymmetries:

- Group profile economics: Library, Factory, Commune, Monastery, Mercenary.
- Different initial group profiles and budgets.
- Ring topology and dispatch delays.
- Leak quotas and privacy transforms.
- Resource-node claims.
- Blind scoring.
- Grader-group rubric evolution.
- Progressive disclosure, where strong groups unlock deeper stages and weak groups receive more
  leak pressure.
- Epigenetic inheritance, where effective strategies survive as legacy protocols.

The most important point: groups are not only parallel attempts. They are semi-isolated
civilizations with different economics, information flows, and durable knowledge trajectories.

## 10. Grader Groups and Scoring

### 10.1 Blind Submission Gate

`BlindSubmissionGate` in
`evomolior/competition/src/main/kotlin/com/achulkov/evomolior/competition/scoring/BlindSubmissionGate.kt`
holds group results until every group has submitted or a timeout/force reveal occurs. Missing groups
receive default zero-score results.

It emits:

- `RevealedScores`.
- `ScoreSealAudit`.

### 10.2 Scoring Engine

`ScoringEngine` in
`evomolior/competition/src/main/kotlin/com/achulkov/evomolior/competition/scoring/ScoringEngine.kt`
applies a `MetaJudgeVerdict` to revealed scores:

- Computes weighted dimension totals.
- Applies penalties for failure modes.
- Applies mandatory-test penalties.
- Ranks groups.
- Emits `GradingResult`.
- Emits failure telemetry.
- Synthesizes deliverable skeleton node updates.

### 10.3 Grader Group Manager

`GraderGroupManager` in
`evomolior/competition/src/main/kotlin/com/achulkov/evomolior/competition/grader/GraderGroupManager.kt`
holds isolated grader groups, registers grading sheets with `MetaJudge`, collects dimension scores,
and computes consolidated round results.

### 10.4 Meta Judge

`MetaJudge` in
`evomolior/competition/src/main/kotlin/com/achulkov/evomolior/competition/grader/MetaJudge.kt`
reconciles grading sheets, detects contested dimensions, and arbitrates.

Evidence:

- `evomolior/competition/src/test/kotlin/com/achulkov/evomolior/competition/grader/MetaJudgeTest.kt`.
- `evomolior/api/src/test/kotlin/com/achulkov/evomolior/api/grader/GraderRunnerJudgingPhaseTest.kt`.
- `evomolior/api/src/test/kotlin/com/achulkov/evomolior/api/grader/GraderCivilizationCycleTest.kt`.

## 11. Progressive Disclosure

`ProgressiveDisclosureEngine` is in
`evomolior/competition/src/main/kotlin/com/achulkov/evomolior/competition/disclosure/ProgressiveDisclosureEngine.kt`.

It tracks per-group disclosure stage and leak multiplier:

- Groups start at stage 0.
- `currentStage(groupId)` returns what ordinary agents can see.
- `goalFramedContent(groupId)` is intended only for MetaPlanner.
- On check-in pass, the group advances.
- On check-in failure, the group remains at the stage and its leak multiplier rises.

This enables a curriculum-like run: not every group sees the full target framing at the same time.

Evidence:

- `evomolior/api/src/test/kotlin/com/achulkov/evomolior/api/service/EvoMoliorRunServiceDisclosureLeakTest.kt`.

## 12. Oversight: Inspector, Watchdog, Meta-Planner

### 12.1 Inspector

`InspectorRunner` reads event/substrate signals and emits anomaly flags. It is not the same as a
normal group worker; it is a monitoring role.

File:

- `evomolior/orchestration/src/main/kotlin/com/achulkov/evomolior/orchestration/inspector/InspectorRunner.kt`.

### 12.2 Watchdog

`Watchdog` is deterministic control logic, not a persistent LLM mind.

File:

- `evomolior/orchestration/src/main/kotlin/com/achulkov/evomolior/orchestration/watchdog/Watchdog.kt`.

It handles signals such as check-in gates, stasis, budget pressure, and final-submission triggers.

### 12.3 Meta-Planner

`MetaPlannerRunner` is in
`evomolior/orchestration/src/main/kotlin/com/achulkov/evomolior/orchestration/metaplanner/MetaPlannerRunner.kt`.

It is spawned under stasis or coordination failures to propose plan changes. In the current run
loop, `EvoMoliorRunService` processes meta-planner signals after WiP completions are drained and
blind scoring has been published.

## 13. Nested Groups and Sub-Agent Delegation

### 13.1 Sub-Agent Delegation Inside an Agent

Sub-agent delegation lives in:

- `evomolior/agents/src/main/kotlin/com/achulkov/evomolior/agents/delegation/SubAgentCoordinator.kt`.
- `evomolior/agents/src/main/kotlin/com/achulkov/evomolior/agents/tools/DelegateSubTaskTool.kt`.
- `evomolior/agents/src/main/kotlin/com/achulkov/evomolior/agents/delegation/SubAgentRequest.kt`.

Eligible parent roles can carve budget into child agents. Sub-agents are marked in
`AgentBootContext` with `parentAgentId` and `isSubAgent`.

### 13.2 Nested Group Runner

Nested group execution lives in:

- `evomolior/orchestration/src/main/kotlin/com/achulkov/evomolior/orchestration/nested/NestedGroupRunner.kt`.
- `evomolior/orchestration/src/main/kotlin/com/achulkov/evomolior/orchestration/nested/NestedGroupConfig.kt`.
- `evomolior/orchestration/src/test/kotlin/com/achulkov/evomolior/orchestration/nested/NestedGroupRunnerTest.kt`.

Nested groups can run a small inner civilization and roll up KB/KG/tool outputs to the parent
level. Current tests confirm extraction and lifecycle quiescence behavior.

## 14. API, Runtime, Desktop, and CLI

### 14.1 Engine Service

The engine service contract is
`evomolior/api/src/main/kotlin/com/achulkov/evomolior/api/service/EvoMoliorEngineService.kt`.

The main in-process implementation is
`evomolior/api/src/main/kotlin/com/achulkov/evomolior/api/service/EvoMoliorRunService.kt`.

`EvoMoliorRunService` manages:

- Startup and Genesis/default StartingField selection.
- Materialization of input artifacts.
- Group LifecycleManagers.
- Grader group setup.
- Round loop.
- Critic, fitness, trust decay, grading, disclosure, blind scoring, resource claims,
  meta-planner signals.
- Final deliverable writing.
- Completion marker and archive.
- Event emission.

### 14.2 IPC Server

`evomolior/api/src/main/kotlin/com/achulkov/evomolior/api/service/EvoMoliorIpcServer.kt`
exposes local Ktor routes for the desktop:

- Event websocket.
- Run start/pause/resume/abort.
- State queries.
- Meta archive.
- Results download.

### 14.3 Production Runtime

`evomolior/runtime/src/main/kotlin/com/achulkov/evomolior/runtime/EvoMoliorProductionRuntime.kt`
wires the production engine service, substrate factory, lifecycle manager provider, coordinator
provider, archive service, and model/backend options.

### 14.4 Desktop Control Panel

Desktop EvoMolior UI entrypoint:

- `composeApp/src/jvmMain/kotlin/com/achulkov/evomolior/evomolior/EvoMoliorWindow.kt`.

State bridge:

- `composeApp/src/jvmMain/kotlin/com/achulkov/evomolior/evomolior/viewmodel/EvoMoliorViewModel.kt`.

Important screens:

- `EvoMoliorDashboardScreen.kt`.
- `EvoMoliorRunSetupScreen.kt`.
- `EvoMoliorTestRunSetupScreen.kt`.
- `EvoMoliorRunMonitorScreen.kt`.
- `EvoMoliorLogViewerScreen.kt`.
- `EvoMoliorScoringDashboardScreen.kt`.
- `EvoMoliorSubstrateBrowserScreen.kt`.
- `EvoMoliorAgentDetailScreen.kt`.
- `EvoMoliorPostRunAnalysisScreen.kt`.
- `RunContextArtifactsSection.kt`.

### 14.5 CLI

CLI entrypoint:

- `evomolior/cli/src/main/kotlin/com/achulkov/evomolior/cli/EvoMoliorCliMain.kt`.

Supported run flags include:

- `-prompt "text"` or `-promptfile /path/to/file`.
- `-input /path/to/input-folder`.
- `-output /path/to/output-folder`.
- `-openrouter-api-key KEY`.
- `-qwen-api-key KEY`.
- `-llm-backend openrouter|qwen-cloud`.
- `-fast`.
- `-rounds N`.
- `-group-count N`.
- `-max-agents-per-group N`.
- `-budget-override N`.
- `-run-id custom-run-id`.
- `-pipeline-config /path/to/pipeline.json`.
- Repeated `-model ROLE=MODEL`.

The CLI always builds a `RunConfig`, loads input artifacts from the input directory, creates a
production runtime, starts the run, writes `summary.json`, and, when available, writes
`results.zip` and pipeline artifacts.

## 15. Input Artifacts

Input artifact transport is defined in:

- `evomolior/api/src/main/kotlin/com/achulkov/evomolior/api/model/RunConfig.kt`.
- `evomolior/api/src/main/kotlin/com/achulkov/evomolior/api/util/RunInputArtifactFiles.kt`.

Each upload has:

- Relative path.
- Source name.
- MIME type.
- Base64 content.
- Artifact kind: text, image, or binary.
- Optional text preview.

The engine materializes these into the run artifact directory, computes metadata, extracts allowed
hosts from raw content, and passes stable `RunInputArtifact` metadata into `StartingField` and
agent boot contexts.

Evidence tests:

- `evomolior/api/src/test/kotlin/com/achulkov/evomolior/api/util/RunInputArtifactFilesTest.kt`.
- `evomolior/api/src/test/kotlin/com/achulkov/evomolior/api/service/EvoMoliorRunServiceArtifactAllowlistTest.kt`.

## 16. Complete Input-to-Output Run Flow

### 16.1 Operator Input

The operator provides:

- Objective.
- Input folder/files.
- Output folder.
- API key/backend.
- Group count.
- Agent count per group.
- Rounds.
- Optional model overrides.
- Optional pipeline config.

Desktop and CLI both converge into `RunConfig`.

### 16.2 Startup

`EvoMoliorRunService.startRun(config)`:

1. Emits run-start progress.
2. Handles continuation if requested.
3. Runs Genesis Council or uses `StartingField.default(...)` for test runs/fallback.
4. Normalizes models for the selected backend.
5. Materializes input artifacts.
6. Resolves grader starting field.
7. Persists sealed starting field.
8. Creates per-group substrates.
9. Seeds KB/KG/tools/stage content.
10. Creates `LifecycleManager`s.
11. Creates `GroupCoordinator`.
12. Starts the run loop.

### 16.3 Round Loop

For each round, `EvoMoliorRunService.runLoop(...)`:

1. Applies pause/abort state.
2. Calls `coordinator.onRoundStart(round)`.
3. Flushes due dispatches.
4. Emits `RoundStarted`.
5. Spawns agents for every group.
6. Optionally starts grader criteria agents in parallel.
7. Waits for round duration, emitting heartbeat progress.
8. Ends early if all agents are done.
9. Terminates remaining agents.
10. Spawns round submission summarizers.
11. Runs Critic pipelines.
12. Runs fitness scoring and epigenetic extraction.
13. Applies trust decay.
14. Waits for grader criteria and runs grader judging.
15. Registers supplemental grader verdicts.
16. Computes substrate-based scores.
17. Opens/closes check-in gate and processes disclosure.
18. Publishes blind round result.
19. Updates adversarial discriminator briefs.
20. Applies deliverable skeleton tree refinements.
21. Publishes resource node claims.
22. Calls `coordinator.onGradingComplete(round)`.
23. Processes MetaPlanner signals.
24. Archives round summaries.

### 16.4 Completion

After the last round:

1. Collect substrate health per group.
2. Select final winner.
3. Resolve final output spec.
4. Optionally generate final-deliverable image assets.
5. Spawn winner-side final deliverable writer.
6. Build a final answer summary.
7. Build a run graph message.
8. Persist `RunCompletionRecord`.
9. Persist run graph message.
10. Run final synthesisers.
11. Archive completed run.
12. Emit `RunCompleted`.
13. Persist completed state.

Artifacts can include:

- `summary.json`.
- `results.zip`.
- Output files under `outputs/`.
- Final generated HTML or generic file when requested.
- Completion record.
- Meta-archive rows.
- Run graph message.
- Sealed starting field.
- Substrate databases.

## 17. Current Implementation Caveats

These caveats matter for operators and future architecture work:

- Qwen Cloud runtime depends on the Koog Dashscope client being present on the runtime classpath.
  The code checks this before a run. If absent, Qwen launches fail before execution.
- Some module `agents.md` files describe intended/spec architecture, while code has evolved. This
  document prioritizes current Kotlin source and tests where they differ.
- Shuru VM mode exists, but normal CLI, benchmark, self-improvement, and desktop production
  composition currently supplies an in-process runner.
- Genesis Council is skipped only when `isTestRun = true`; the normal console runner uses
  production Council execution.
- `ConstitutionalEnforcer` and `AllowlistProxy` are implemented but disconnected from normal
  production action/request paths.
- Differential privacy transformation is configured only when a transform API key is supplied;
  `GroupCoordinator` otherwise permits untransformed leak relay.
- `RunConfig.openRouterApiKey` is kept under a historical name even when `llmBackend` is Qwen
  Cloud. The selected backend decides which API-key environment variable and client are used.
- The default CLI final output spec is only produced automatically for browser-deliverable prompts.
  Generic documentation-improvement prompts may complete with summaries/results rather than a
  deterministic standalone file unless the prompt causes agents to publish one.

## 18. Why This Architecture Stands Out

EvoMolior's distinguishing features are:

- Disposable agents, durable civilization: memory lives in substrate, not in agent context.
- Competitive heterogeneity: group economics and topology create diverse search paths.
- Staging and critic review: agent outputs do not become canon automatically.
- Death Notes and dead ends: failures become reusable infrastructure.
- Legacy protocols: effective role-specific behavior can be inherited without making agents
  persistent.
- Progressive disclosure: groups learn under constrained, staged information release.
- Differential privacy leaks: information can migrate without full copying or monoculture.
- Blind scoring and grader groups: evaluation can be separated from production.
- Resource maps: good work can alter future resource economics.
- Nested groups/sub-agents: hierarchy can turn hard subproblems into local civilizations.
- Run archives and graph messages: completed runs can feed future runs.

## 19. High-Value Run Targets

EvoMolior is best suited for tasks where many parallel approaches, durable learning, and rigorous
selection matter more than one-shot answer generation.

Strong targets:

- Architecture audits for complex software systems.
- Design-space exploration for agent frameworks.
- Competitive product strategy synthesis.
- Multi-source research where claims need canon/trust management.
- Test/rubric generation for ambiguous deliverables.
- Self-improvement planning for this repository.
- Security/threat-model exploration with competing assumptions.
- Documentation synthesis over large, messy codebases.
- Tool-building tasks where reusable utilities can compound.
- Benchmark design, failure-mode taxonomy, and evaluation harness creation.

Weak targets:

- Tiny deterministic coding tasks.
- Tasks where a single authoritative answer is known.
- Tasks with strict real-time latency requirements.
- Tasks where external API/classpath dependencies are unavailable.
- Tasks that cannot benefit from staged outputs, critique, or competition.

## 20. Improvement Questions for the Next EvoMolior Run

The requested enrichment run should challenge and improve this document along these axes:

- Is the bottom-up layering understandable to a new operator?
- Which current code paths contradict older spec-level descriptions?
- Which modules need diagrams or sequence charts?
- What parts of the run loop deserve operator-facing telemetry explanations?
- What Qwen-specific runtime constraints should be elevated into setup docs?
- Which high-value run targets should become first-class templates in the UI/CLI?
- Which architecture mechanisms most strongly differentiate EvoMolior from ordinary multi-agent
  orchestration?
- What alternatives should be documented: non-competitive mode, pure grader mode, single-group
  research mode, self-improvement mode, and nested hierarchy mode?
- What failure modes should operators expect: classpath backend failure, empty agent responses,
  staging pollution, grader disagreement, Shuru unavailability, budget starvation, and no-final-file
  completion?

## 21. Qwen Enrichment Run Findings

**Historical evidence:** this section records a specific 2026-07-01 run. It is not current
roadmap or runtime-status authority.

This section incorporates findings from a Qwen-backed EvoMolior CLI run launched after the first
version of this document was written. It used this document plus an internal enrichment prompt.
The prompt and complete run outputs remain intact in ignored local development/result directories.
The primary stage completed with:

- Run ID: `qwen-architecture-enrichment-20260701-185954`.
- Backend: `qwen-cloud`.
- Shape: `4` groups, `3` agents per group, `8` rounds.
- Published output: retained in ignored local run results.
- Follow-up refinement: retained in ignored local run results.
- Pipeline status: `COMPLETED`; the private pipeline summary remains with the local run results.

At that July 1 evidence cut, the run used `--fast`, in-process agents, frequent text-only Qwen
completions, warning-only Death Notes, and fallback score paths. Those observations motivated later
hardening; they are historical run evidence, not current runtime-status authority. Use the canonical
roadmap for current behavior and limitations.

### 21.1 Standout Architecture Thesis

The run's strongest synthesis was that EvoMolior does not stand out merely because it runs many
agents. Its differentiator is the evolutionary topology: budget ceilings, group profiles, leak
timing, dispatch lag, grading pressure, Death Notes, and durable substrate all act as selection
forces. Ordinary orchestrators usually freeze the graph and ask agents to follow it. EvoMolior lets
roles, model assignments, knowledge inheritance, and group strategies become material that can be
selected, retired, leaked, archived, or amplified.

This reframes the operator's job. The operator should not try to precompute the best team design
for every task. The operator should set the objective, constraints, budget, run shape, backend, and
evaluation pressure well enough that the system can explore competing designs and converge toward
stronger artifacts.

### 21.2 Proposed Architecture Improvements

The Qwen run proposed these experiment candidates. They are not all implemented today; treat them
as ranked design hypotheses.

- Adaptive leak interval: make leak cadence depend on diversity and convergence metrics. If groups
  collapse into similar solutions too early, reduce cross-pollination or increase diversity
  incentives. If groups diverge too far, leak more often or introduce synthesis checkpoints.
- Hierarchical grading: add bounded meta-graders that audit grader quality, score distributions,
  and rubric drift. This is valuable because weak graders compromise the whole evolutionary
  process, but it must be budget-bounded to avoid quality-control infinite regress.
- Explicit dead-end documentation: require agents to record failed approaches in structured Dead
  End entries before termination. This turns "read the failures before repeating work" from a norm
  into a protocol.
- Multi-objective fitness landscapes: score correctness, robustness, novelty, efficiency, cost,
  and evidence separately, then track Pareto fronts instead of collapsing everything into a single
  scalar too early.
- Agent archetype specialization tracking: track which role/model/prompt archetypes repeatedly
  perform well on which subtask classes, then feed that evidence back into Genesis Council and
  selector decisions. This should track archetypes rather than persistent agents to preserve the
  disposable-agent principle.
- Checkpointed "best so far" outputs: persist usable interim deliverables at budget milestones so
  operators are not left empty-handed if a final writer fails or a run times out.
- Knowledge quality scoring and pruning: attach provenance, confidence, contradiction status, and
  downstream-use evidence to KB/KG entries; periodically prune or quarantine low-trust knowledge.
- Lineage visualization: expose how final artifacts descend from groups, rounds, leaks, critiques,
  and substrate promotions so operators can debug why a result won.

### 21.3 Alternatives for Major Design Choices

Topology alternatives:

- Ring topology remains a strong default because it balances information flow and diversity
  preservation.
- Star topology could accelerate aggregation around a hub group, but creates a bottleneck and a
  single point of failure.
- Mesh topology maximizes cross-group information flow, but raises communication cost and
  premature-convergence risk.
- Tree topology fits naturally decomposable tasks where root groups integrate branch outputs.
- Dynamic topology is the most ambitious option: topology becomes part of the evolutionary genome
  and changes when evidence shows a different communication pattern is fitter.

Grading alternatives:

- Adversarial grading is best for high-stakes final artifacts and architecture decisions.
- Collaborative grading may be better for exploratory runs where speed and ideation matter more
  than strict selection pressure.
- Self-grading is cheap but should be treated as low-trust unless paired with external validation.
- Human or external-model grading is expensive and slower, but appropriate for production
  decisions, safety-sensitive outputs, and benchmark calibration.

Persistence alternatives:

- The current KB/KG hybrid is appropriate because documents and structured facts need different
  storage and trust mechanics.
- A vector-only substrate would simplify retrieval, but would lose explicit relationships,
  provenance, and contradiction tracking.
- A graph-only substrate would preserve structure, but would weaken free-form document synthesis.
- A versioned KB/KG would add rollback and knowledge-evolution auditability; this is the most
  valuable persistence extension to evaluate next.

### 21.4 Operator Pain Points and Solutions

Budget exhaustion before convergence:

- Use smaller or cheaper models for early exploration and stronger models for final synthesis.
- Add early stopping when score improvements plateau.
- Reduce group count or agents per group when the objective needs many rounds more than breadth.
- Preserve checkpoint deliverables at fixed budget percentages.

Premature convergence:

- Increase leak interval or dispatch lag.
- Reward novelty as a grading dimension.
- Assign different model pools, role prompts, or group profiles to preserve cognitive diversity.
- Use outlier roles deliberately when conventional groups begin to agree too quickly.

Grader drift:

- Rotate grader groups across rounds.
- Calibrate against fixed reference artifacts with known quality.
- Monitor score distributions for suspicious compression, inflation, or persistent fallback
  scoring.
- Add bounded meta-grading for long or high-stakes runs.

Knowledge pollution:

- Require confidence and provenance for all KG facts.
- Use consistency checks before promoting structured claims.
- Keep speculative material in KB documents until validated.
- Run cleanup passes that demote contradictory or low-trust entries.

Death Note quality:

- Use templates with required fields: summary, what worked, what failed, evidence, and successor
  recommendations.
- Grade Death Notes and include their quality in legacy selection.
- Require successor agents to read a minimum number of relevant Death Notes.

Operator visibility:

- Surface group fitness, budget burn, active roles, pending staged entries, rejected entries,
  grader disagreement, leak events, and final-writer retries in the UI/CLI.
- Generate progress reports at round boundaries.
- Allow carefully scoped operator guidance injection at milestones, while recording it as part of
  run provenance.

### 21.5 High-Value Future Run Targets

The enrichment run ranked tasks by fit. EvoMolior is strongest where the solution space is broad,
multiple approaches are plausible, artifacts can be graded, and accumulated knowledge compounds.

- Ideal fit: architecture design and enrichment, research synthesis, literature review, strategy
  documents, design-space exploration, and benchmark/rubric generation.
- Strong fit: complex code generation with tests, policy analysis, threat modeling, product
  strategy, and safety or reliability reviews.
- Moderate fit: creative writing, educational content, curriculum design, data-analysis insight
  generation, and exploratory scenario planning.
- Weak fit: translation/localization, narrow deterministic edits, and tasks with known single
  answers.
- Poor fit: real-time decision support, simple factual Q&A, and low-latency tasks that cannot
  benefit from generational evolution.

Specific future project runs that would make EvoMolior stand out:

- Compare ring, mesh, tree, and dynamic topologies on the same architecture-audit objective.
- Build a configuration recommender that maps objective type to group count, agents per group,
  rounds, model pool, leak interval, and grader strictness.
- Create a lineage graph viewer for final artifacts.
- Run a Qwen-vs-OpenRouter backend reliability study focused on tool-call validity, structured
  score compliance, latency, and final-file success.
- Add a versioned KB/KG experiment with rollback after detected contradiction.
- Run self-improvement tasks that specifically target grader fallback scoring and Qwen text-only
  write behavior.
- Generate reusable UI/CLI templates for architecture audit, strategy synthesis, codebase
  improvement, threat modeling, and research synthesis.

### 21.6 Configuration Guidance

Default recommendations from the enrichment run:

- Simple, narrow objectives: `2-3` groups, `2-3` agents per group, `1-3` rounds.
- Medium objectives with several plausible approaches: `3-5` groups, `3-5` agents per group,
  `3-10` rounds.
- Complex architecture, strategy, research, or policy objectives: `5-8` groups when budget allows,
  `3-5` agents per group, and enough rounds for divergence, critique, and synthesis.
- Code generation: prefer smaller groups (`2-4` agents) with stronger validation and test-based
  grading.
- Creative or exploratory tasks: use more agents per group (`4-6`) and stronger novelty scoring.
- Analysis/evaluation tasks: use fewer agents per group (`2-3`) with stricter evidence and
  citation requirements.

For this repository, a pragmatic default remains `4` groups, `3` agents per group, and `8` rounds
for documentation/architecture enrichment: it provides enough breadth for divergent viewpoints
without overwhelming the current Qwen/backend/tooling constraints.
