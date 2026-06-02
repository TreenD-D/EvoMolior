# EvoKammer Remediation Plan

> Based on the §10 misalignment audit from `EvoKammer_Implementation_Plan_v1.0.md`.
> Updated: 2026-05-27. Items already fixed in the 2026-03-22 session are listed first.

---

## Run Analysis (2026-05-27)

The newest execution log shows a different failure mix than the 2026-05-25 pass. The sealed-suite path is no longer the primary blocker; the run is now being derailed by model compatibility, agent output shape, and an over-expensive fallback surfacing in routine swarm roles.

### Observed symptoms from `documentation/exec_logs.txt`

- The round-judge / critic path is still hardwired to a deprecated model. The log repeatedly shows `Grok 4 Fast is deprecated. xAI recommends switching to Grok 4.3` and the failing model is `x-ai/grok-4-fast`.
- `anthropic/claude-sonnet-4.6` is still being selected for a normal Critic spawn in the first round. That model is too expensive for this swarm and should not appear in routine runtime pools.
- Several agents fail immediately with `AIAgentStuckInTheNodeException` at `nodeSendToolResult` because the model returned an empty assistant message (`Assistant(parts=[], ...)`) rather than a tool-call/result sequence the graph can continue from.
- Empty assistant turns are now normalized in the runtime factories via an explicit response processor that converts them into a retryable assistant text instead of letting Koog stall the graph.
- Some agents also hit `AIAgentMaxNumberOfIterationsReachedException` at the current 25-step cap, but the log shows they often fail after long wall-clock time while budget is still intact. The shared default cap has been raised to 40 so budget, not an arbitrary step ceiling, is the primary limiter.
- The first round is where the run becomes unstable: the spawn logs show low-cost worker models such as `deepseek/deepseek-v4-flash` and `deepseek/deepseek-v3.2` entering the pool, but at least one researcher still fails before producing any actions, which leaves the budget untouched and the round effectively stalled.

### Root-cause interpretation

- `grok-4-fast` is now a hard compatibility bug, not just a soft preference issue. Any path still pointing at it will continue to fail until the runtime and grader defaults are switched to `grok-4.3`.
- `sonnet-4.6` should not remain in the normal runtime pool set. It is too expensive for the purpose of the swarm and creates the wrong cost profile for routine Critic work.
- The `nodeSendToolResult` empty-response failures indicate a model/prompt/tool-graph mismatch, not a budget problem. That path is now guarded by an explicit empty-response recovery processor in the agent factories.
- The current iteration ceiling was too low for some prompts, and that has been increased in the shared budget config so long but productive runs do not die at 25 steps.

### Immediate model policy changes to make

- Remove `anthropic/claude-sonnet-4.6` from all routine runtime pools and prompt examples. Keep expensive models out of the swarm default path entirely.
- Replace every `x-ai/grok-4-fast` runtime/grader reference with `x-ai/grok-4.3`.
- Keep `sonnet-4.6` out of the normal runtime config even if it remains listed as a theoretical opt-in elsewhere; the practical runtime policy should not select it.
- Prefer a smaller, stable candidate set for first-round worker roles over a broad pool that includes models known to return empty assistant messages.

---

## Run Analysis (2026-05-25)

The latest execution log shows the system is not failing in one place. There are three separate failure classes:

1. The post-round scoring path is running against an empty sealed suite, so every deterministic score collapses to `0.0`.
2. The OpenRouter/Gemini path is unstable in both agent execution and grading, with repeated `Unexpected response: no tool calls and no content` failures.
3. The runtime is still emitting a large amount of structurally valid but metadata-incomplete content, so the Critic rejects most KG facts before they can contribute to the knowledge base.

### Observed symptoms from `documentation/exec_logs.txt`

- Sealed-suite scoring is the path that collapses to zero. In rounds 3 and 4, every group logged `tests=0/0` and `SealedTestSuiteEvaluator: no tests parsed ... scoring is forced to zero` around `23:16:17.814-23:16:17.825` and again at `23:13:45.045-23:13:45.054`.
- The grader-manager verdict path is different: it still emitted non-zero scores before the external judge failed. At `23:16:10.190`, `GraderRunner.judge` logged `plan_execution = 50.0`, `tool_utility = 50.0`, `kg_coherence = 50.0`, and `kb_density = 60.0/65.0/75.0` for the three groups.
- `GraderRunner.criteria` and `GraderRunner.judge` both failed on `OpenRouterLLMClient`, with the raw symptom `Unexpected response: no tool calls and no content` at `23:14:24.451`, `23:14:06.243`, and `23:16:17.807`.
- The failed grader models were `google/gemini-2.5-flash` for `GraderBuilder` / `RoundJudge` at `23:16:04.034` and `23:14:06.247`.
- Main-run researchers were using `google/gemini-3-flash-preview`; at least one of those agent calls also failed on `OpenRouterLLMClient` with the same empty-response error at `23:14:12.120` and `23:14:03.961`.
- Critic agents using `anthropic/claude-sonnet-4.6` completed more often, but several still ended on `AIAgentMaxNumberOfIterationsReachedException` after spending only part of their budget.
- Concrete budget/iteration examples: `agent-researcher-dfb0dca9` started with `30.0`, spent `14` actions, and still failed on the 25-step cap; `agent-critic-e9a67187` started with `60.0`, spent only `5` actions, and still hit the same cap.
- The log also shows that KB/KG export is not empty: `CanonSnapshotExporter` exported non-zero counts such as `kbTotal=20 kgTotal=8`, `kbTotal=24 kgTotal=8`, and `kbTotal=19 kgTotal=8`, so the likely failure is persistence/readback/parsing of the sealed suite, not a total absence of round data.
- Group budgets were not the primary blocker: agents were failing with substantial remaining budget still available.
- The main metadata regression is still present: many KG facts are auto-rejected because `wip_task_reference` is missing.

### Root-cause interpretation

- The zero scores are not a scoring-weight bug. They are a suite-hydration bug or suite-generation bug until proven otherwise.
- The judge failures are not a budget exhaustion symptom. They are provider/model contract failures in OpenRouter.
- The iteration cap is currently too low for some roles or prompts, but that is secondary to the provider failures and the empty-suite problem.
- The `wip_task_reference` gate is doing its job, but the agents are not consistently populating the required metadata, so useful work is being discarded before it can affect KB/KG density.

### Model and budget diagnosis

- `google/gemini-2.5-flash` is the highest-risk model in this run because both grader phases failed on it.
- `google/gemini-3-flash-preview` is also unstable for main-run researchers in this run and should be treated as suspect until a minimal tool-call smoke test passes.
- `anthropic/claude-sonnet-4.6` is comparatively more reliable in this log, but it is also expensive enough that it should not remain the default for this project. Use it only where a role genuinely needs that capability.
- Budgets are currently not the limiting factor. The run shows failures at remaining budgets like 49/50, 55/60, and 24/30, which means the work is stopping because of provider errors or step caps, not because the ledger is empty.
- `maxAgentIterations = 25` is too low for at least some of the current role prompts. One researcher reached 26-38 actions before failing, which is a clear sign that the step cap is cutting off otherwise productive work.
- Model selection must be centralized in a single config file, not scattered across role factories and runner wiring. The canonical file is `evokammer/core/src/main/kotlin/com/achulkov/evokammer/core/config/ModelSelectionConfig.kt`, and it must define the project-approved defaults, the fallback order, and the allowed expensive exceptions in one place so cost creep is visible and reviewable.
- Expensive models must be explicit opt-ins only. No silent defaults, no hidden per-role overrides, and no prompt-text fallbacks that reintroduce Sonnet by accident.
- Current call sites to inventory and migrate are `LifecycleManager`, `CriticPipeline`, `CouncilRunner`, `GraderAgentFactory`, `DifferentialPrivacyTransform`, `EvoKammerRunService`, and any UI or prompt-text defaults that duplicate model IDs.
- A path-based run-log scanner now exists in `:evokammer:api` to catch recurring signatures like `Grok 4 Fast is deprecated`, `AIAgentStuckInTheNodeException`, `AIAgentMaxNumberOfIterationsReachedException`, and `Unexpected response: no tool calls and no content` on generated log artifacts instead of on this committed evidence file.

---

## Residual Gaps Review (2026-05-23)

The 2026-03-22 remediation pass closed most of the original audit. The blind-scoring/check-in harness is now aligned with the sealed test suite and real telemetry path, so the residual gaps in this plan are elsewhere.

An implementation can expose a role or class without closing the runtime route behind it; the remaining gaps below point to those missing end-to-end paths, not just absent type definitions.

The blind scoring / check-in contract is now covered by the deterministic sealed-suite evaluator, the blind gate registration, and the real check-in telemetry bridge.

---

## Part 0 — Already Fixed (2026-03-22 Session)

The following previously-flagged misalignments were resolved before this plan was written:

| # | Item | Fix summary |
|---|---|---|
| 0-1 | Genesis Council parallel execution (§10.1 #2) | Roles 1–7 run concurrently via `coroutineScope { async }.awaitAll()`. Synthesis runs after. |
| 0-2 | Genesis Council consistency re-run (§10.1 #4) | Synthesis output checked for `consistencyError: true`; conflicting role re-run up to `maxRetries`. |
| 0-3 | Council timeout KDoc comment (§10.1 #7) | Both code and KDoc now say 8 minutes. |
| 0-4 | Watchdog + InspectorRunner not started (§10.7 #1-2) | `Watchdog.start()` and `InspectorRunner.startFor()` called for all groups in `startRun()`. |
| 0-5 | `stasisDetected` hardcoded false (§10.6 #4, §10.17 #1) | `stasisFlags` map populated by Watchdog signal subscription; passed per-group to `EvolutionarySelector`. |
| 0-6 | `generationCeiling` ignored (§10.17 #2) | `effectiveRounds = min(config.rounds, activeGenerationCeiling)` enforced in `runLoop()`. |
| 0-7 | `LegacyProtocolExtractor` never called (§10.2 #3, §10.13 #1-2) | `runFitnessAndEpigenetics()` called post-round; uses `FitnessScorer` + `LegacyProtocolExtractor`. |
| 0-8 | Budget floor kill not wired (§10.2 #4) | `LifecycleManager.monitorBudgets()` checks `ledger.isAtFloor` → `initiateKillSequence()`. |
| 0-9 | BudgetLedger: no mutex, no double/quadruple cost (§10.3 #2-4) | `Mutex` added; `ActionType.TOOL_EXEC_FAILURE` costs 2×; `RetryTracker` detects retries → 4× via `TOOL_EXEC_RETRY`. |
| 0-10 | `primordialTools` not seeded (§10.4 #11) | `seedPrimordialData` inserts `primordialTools` into `ToolRegistryDao` for all groups. |
| 0-11 | Grader groups not seeded (§10.4 #12) | `seedPrimordialData` called with `allGroupIds = groupIds + graderGroupIds`. |
| 0-12 | DISPUTE sits forever (§10.5 #2) | CriticPipeline now triggers a second review with dispute context. Double-promote → PROMOTE; double-reject → REJECT; split stays `"disputed"`. (Full Arbitration Agent still pending §A-8.) |
| 0-13 | Grader two-phase architecture (§10.10) | Criteria agents run concurrently during round (Phase A); RoundJudge scores post-round (Phase B). |
| 0-14 | Scoring dimensions hardcoded (§10.17 #3) | `computeAndSubmitSubstrateScores` dynamically maps council-designed dimension IDs. |
| 0-15 | `CriticPipelineCompleted` promoted count wrong (§10.17 #4) | `CriticSummary` data class with per-outcome counts; event emits real `promoted`/`rejected`/`disputed` counts. |
| 0-16 | `ringTopology` not threaded to `GroupCoordinator` (§10.8 #1) | `startingField.ringTopology` threaded into `groupCoordinatorProvider`. |
| 0-17 | Deterministic blind-scoring/check-in harness (§10.12 #2) | `SealedTestSuiteEvaluator` now binds the sealed suite to canonical snapshots, `processDisclosureCheckIns` reports actual test counts, and `publishBlindRoundResult` feeds those same counts into the blind reveal path. |

---

## Part A — Priority 1: Small Wiring (High value, low effort)

Each item is a targeted change to an existing class. Estimated effort: **0.5–2 hours each**.

### A-1: Critic self-submission guard (§10.5 #3)

**Problem:** `CriticPipeline` assigns a `criticId` for the round but never checks whether the submitting `agentId` matches it. A Critic agent could theoretically promote its own staged submissions.

**Fix:** In `CriticPipeline.reviewSubmission()`, add:
```kotlin
if (submission.agentId == criticId) {
    // Auto-reject with reason — Critic cannot review own submissions
    applyReview(submission, CriticReview(CriticOutcome.REJECT, "self_submission_violation"), generation)
    return
}
```

**File:** `evokammer/orchestration/src/main/kotlin/…/lifecycle/CriticPipeline.kt`

---

### A-2: EvolutionarySelector allocation as structured event (§10.6 #6)

**Problem:** `EvolutionarySelector.computeAllocation()` logs the decision via `Napier.i` but never writes a structured `EventLogEntry`. Post-mortem analysis can't query allocation history.

**Fix:** In `EvolutionarySelector`, after building the allocation, write an `EventLogEntry` with `eventType = EventTypes.SELECTOR_DECISION` and a JSON payload containing role counts, stasis flag, and signal values.

**File:** `evokammer/orchestration/src/main/kotlin/…/selector/EvolutionarySelector.kt`

---

### A-3: Check-in dimension scores filled (§10.12 #2)

**Problem:** `processDisclosureCheckIns` calls `onCheckinComplete(dimensionScores = emptyMap(), failedTestCount = 0)`. The `ProgressiveDisclosureEngine` stage gate only has access to the heuristic `overallScore`.

**Fix:** Pass the per-dimension `DimensionScore` list from the `GroupCheckinResult` (already computed by `computeAndSubmitSubstrateScores`) into `processDisclosureCheckIns` and forward them to `onCheckinComplete()`.

**File:** `evokammer/api/src/main/kotlin/…/service/EvoKammerRunService.kt`

---

### A-4: Dead Ends always-return in KB retrieval (§10.4 #4)

**Problem:** KB retrieval queries can filter Dead Ends by trust decay like regular entries. Dead ends should always be returned regardless of trust so agents don't re-investigate known dead ends.

**Fix:** In `KnowledgeBaseDao`, add a secondary query or amend the WHERE clause to `WHERE (trustScore >= :minTrust OR isDeadEnd = 1)`. The `SearchKBTool` should always pass `isDeadEnd`-inclusive results.

**File:** `evokammer/substrate/src/main/kotlin/…/db/dao/KnowledgeBaseDao.kt`

---

### A-5: `wip_task_reference` enforcement in staging (§10.16 #2)

**Problem:** Spec §4 requires `wip_task_reference` on all KB doc and tool submissions, but `StagingSubmissionEntity` has no such field and `CriticPipeline` never checks for one.

**Fix:** Add `wipTaskReference: String?` to `StagingSubmissionEntity`. In `CriticPipeline.reviewSubmission()`, for `KB_DOC` and `TOOL` submission types, auto-reject if `wipTaskReference` is null/blank.

**Files:** `StagingSubmissionEntity.kt`, `StagingDao.kt`, `WriteStagingTool.kt`, `CriticPipeline.kt`

---

### A-6: `budget_signature` on staging submissions (§10.4 #8, §10.16 #1)

**Problem:** `StagingSubmissionEntity` has no `budgetSignature` field. The Staging Area never validates that submissions come from a live agent with a real budget.

**Fix:** 
1. Add `budgetSignature: String = ""` to `StagingSubmissionEntity`.
2. `WriteStagingTool` calls `budgetLedger.generateStagingToken(submissionType)` and includes the result in the entity.
3. `CriticPipeline.reviewSubmission()` performs a basic non-empty check on `budgetSignature` (full HMAC verification can come later).

**Files:** `StagingSubmissionEntity.kt`, `WriteStagingTool.kt`, `CriticPipeline.kt`

---

### A-7: Watchdog cost ceiling → pause spawning (§10.14 #5)

**Problem:** Watchdog can detect a global cost ceiling breach (via `WatchdogSignal.BudgetCritical`) but `EvoKammerRunService` does not pause new agent spawning in response.

**Fix:** In the `watchdog.signals.collect` handler in `startRun()`, when `WatchdogSignal.BudgetCritical` arrives and `remainingBudget <= globalCostCeiling * 0.05` (last 5%), set `currentStatus = EngineStatus.PAUSED` and emit a `EngineProgress` event explaining the pause.

**File:** `evokammer/api/src/main/kotlin/…/service/EvoKammerRunService.kt`

---

### A-8: Verify/wire DifferentialPrivacyTransform in Leak Oracle (§10.8 #3-4)

**Problem:** `DifferentialPrivacyTransform` class exists but wiring to the active GroupCoordinator leak path was unconfirmed.

**Fix:** Read `GroupCoordinator.dispatchLeak()` / `LeakOracle`; confirm that leaked content passes through `DifferentialPrivacyTransform.transform()`. If not, add the call there.

**Files:** `GroupCoordinator.kt`, `DifferentialPrivacyTransform.kt`

---

## Part B — Priority 2: Medium Implementations (2–8 hours each)

### B-1: KG schema validation before CriticPipeline (§10.4 #5)

**Problem:** KG_FACT submissions go straight to the LLM critic with no schema type check. Agents can insert facts with invented relation types not in the council-designed schema.

**Fix:** Create `KGConsistencyChecker` class with a method `validate(fact: StagingSubmissionEntity, schemaJson: String): ConsistencyResult`. Called by `CriticPipeline` before the LLM review step for `KG_FACT` submissions. Schema extracted from `startingField.kgSchema` (stored in run config or DB). Violations → auto-DISPUTE with the constraint as context.

**New file:** `evokammer/orchestration/src/main/kotlin/…/lifecycle/KGConsistencyChecker.kt`

---

### B-2: KG_CONSTRAINT submissions applied to schema (§10.17 #6)

**Problem:** When a `KG_CONSTRAINT` submission is promoted by the Critic, `promoteStagingToSubstrate` logs it as "schema update" but never writes to any schema table.

**Fix:** Add a `KgSchemaConstraintEntity` table (or a JSON column in an existing config table). When `CriticPipeline` promotes a `KG_CONSTRAINT` submission, parse the constraint JSON and upsert it. `KGConsistencyChecker` reads this table at validation time.

**Files:** New `KgSchemaConstraintDao.kt`, `SubstrateDatabaseFactory.kt`, `CriticPipeline.kt`

---

### B-3: `consistencyReport` field in StartingField (§10.1 #3)

**Problem:** The spec requires the CouncilSynthesis to emit a `consistencyReport` alongside the `StartingField`. Currently no such field or event exists.

**Fix:** Add `consistencyReport: String = ""` to `StartingField`. The `CouncilSynthesis` system prompt already asks for this; `GenesisCouncil.parseStartingField()` extracts it. Emit `EvoKammerEvent.GenesisCouncilCompleted` with the report appended to `rolePromptSummary["consistency"]`.

**Files:** `StartingField.kt`, `GenesisCouncil.kt`, `EvoKammerEvent.kt`

---

### B-4: Legacy Protocol active expiry + failure maps to Dead Ends (§10.13 #4-5)

**Problem:** `expiresAtGeneration` is stored on each `LegacyProtocolEntity` and checked at spawn time, but no background process archives expired protocols or promotes their failure maps.

**Fix:** In `runFitnessAndEpigenetics()`, after running the extractor, query `legacyProtocolDao.getExpiredProtocols(currentGeneration)`, archive each, and for each protocol with `failureMapJson != null`, insert KB Dead End entries with `isDeadEnd = true`.

**File:** `evokammer/api/src/main/kotlin/…/service/EvoKammerRunService.kt`

---

### B-5: Watchdog check-in gate control (§10.14 #3)

**Problem:** Check-in timing is purely round-based. The Watchdog is specified to explicitly open and close check-in gates to prevent late-round submissions from being evaluated in the wrong context.

**Fix:** Add `WatchdogSignal.CheckInGateOpen(roundIndex)` and `CheckInGateClosed(roundIndex)` signal types. Watchdog emits these at round boundary (can be time-based or triggered by `EvoKammerRunService`). `processDisclosureCheckIns` only runs when the gate is open.

**Files:** `WatchdogSignal.kt`, `Watchdog.kt`, `EvoKammerRunService.kt`

---

### B-6: Migration rate ceiling enforcement (§10.8 #5)

**Problem:** The spec specifies a 15% per-generation migration rate ceiling for inter-group content migration, but no code enforces this.

**Fix:** In `GroupCoordinator.dispatchLeak()` (or wherever inter-group content passes), track the number of leak events per `(fromGroupId, toGroupId, roundIndex)` tuple. Cap at `ceil(totalKbEntries * 0.15)` per round. Reject excess leak events with a log warning.

**File:** `evokammer/competition/src/main/kotlin/…/coordinator/GroupCoordinator.kt`

---

### B-7: `GenesisCouncilCompleted.usedDefault` false positive fix (§10.17 #5)

**Problem:** `usedDefault = startingField.modelAssignment.isEmpty() && startingField.primordialKb.isEmpty()`. A council that deliberately chose no model overrides would incorrectly set `usedDefault = true`.

**Fix:** Track whether the council runner was invoked at all (not the output). Add a `fromCouncil: Boolean` flag to `StartingField` (or pass a separate boolean to the event).

**Files:** `EvoKammerRunService.kt`, `StartingField.kt`

---

## Part C — Priority 3: Large Architectural Work (1–5 days each)

These items require new subsystems or significant cross-cutting changes. Prioritise only after Priority A and B items are complete.

### C-1: CrossoverEngine for epigenetic inheritance (§10.13 #3)

**Problem:** No `CrossoverEngine` class. The spec requires merging two parent protocols for the same role into a child protocol (crossover step in epigenetic inheritance).

**Design:**
- New class `CrossoverEngine` with `crossover(parent1: LegacyProtocolEntity, parent2: LegacyProtocolEntity): LegacyProtocolEntity`
- Uses a weighted crossover: clauses from the higher-fitness parent preferred
- Called from `runFitnessAndEpigenetics()` when two protocols for the same role both exceed the extraction threshold

---

### C-2: ResourceMapEngine node capture/recapture (§10.9 #2-4)

**Problem:** WIP tasks from `nodeGraph` are seeded and claimed/completed, but no `ResourceMapEngine` manages live node ownership or awards budget bonuses to holders.

**Design:**
- New class `ResourceMapEngine` tracking `nodeId → (ownerGroupId, captureGeneration, artifactId)` in memory (or a new DB table)
- On `CriticPipeline.PROMOTE` of a `KB_DOC` or `TOOL`, check if the submitted `wipTaskReference` corresponds to a node; if so, register capture
- On cross-round scoring, compare artifact scores; if another group's artifact scores higher, trigger recapture
- Integrate with `BudgetLedger.deductRaw(amount = -bonus, reason = "node_hold_bonus")` for bonus credits

---

### C-3: Blind scoring seal (§10.11 #3-4)

**Problem:** Scores are computed and submitted immediately per group. The spec requires all group scores to be sealed until every group has submitted, then revealed simultaneously.

**Design:**
- `GroupCoordinator.sealScore(groupId, roundIndex, scores)` stores scores in a sealed ledger
- `coordinator.revealRound(roundIndex)` called only when all group scores are present
- `RoundCompleted` event emitted only after `revealRound()` — not immediately on each group's computation
- `computeAndSubmitSubstrateScores` calls `sealScore`, not direct submission; `runLoop` calls `revealRound` after all groups seal

---

### C-4: Genesis Council sealed test suite (§10.1 #5)

**Problem:** `StartingField.sealedTestSuiteHash` is always `""`. No test suite content is generated or stored.

**Design:**
- `CouncilTaskAnatomist` (or a new `CouncilTestDesigner` role) produces a JSON test suite during the parallel phase
- Suite is stored as a `SealedTestSuiteEntity` in the first group's DB before round 0
- `sealedTestSuiteHash` is the SHA-256 of the suite JSON, stored in `StartingField`
- `computeAndSubmitSubstrateScores` runs the suite's assertions against each group's KB/KG

---

### C-5: Meta-Planner spawn path (§10.14 #4, §10.15)

**Problem:** `Role.MetaPlanner` exists but has no spawn trigger. The spec requires the Watchdog to spawn a Meta-Planner agent when global stasis is detected across all groups.

**Design:**
- Add `WatchdogSignal.GlobalStasis(reason)` emitted when all groups have been flagged by `stasisFlags` for 2+ consecutive rounds
- In `EvoKammerRunService`, subscribe to `GlobalStasis` signals; spawn a one-shot Meta-Planner agent via `EvoKammerAgentFactory.buildMetaPlanner()`
- Meta-Planner reads cross-group KB summaries and proposes new WIP tasks or role prompt mutations, writes to a special `meta_planner_output` staging table
- `runLoop` reads `meta_planner_output` before the next round's `spawnAgentsForRound` and injects the suggestions

---

### C-6: Auditor spawn by Watchdog (§10.7 #3, §10.14 #6)

**Problem:** `InspectorRunner` emits anomaly flags to the Watchdog but no Auditor agent is ever spawned in response.

**Design:**
- `WatchdogSignal.InspectorFlag` with `severity >= HIGH` triggers an Auditor spawn
- New `AuditorAgent` built by `EvoKammerAgentFactory.buildAuditor()` — reads the flagged event log entries and produces a structured audit report
- Audit report written to a `AuditReportEntity` table and emitted as `EvoKammerEvent.AuditReport`

---

### C-7: KG-Critic Arbitration Agent (§10.4 #6, §10.5 #2)

**Problem:** Disputes that survive the second review (split verdict) sit permanently in `"disputed"` DB status. The spec requires an Arbitration Agent to resolve them.

**Design:**
- After `CriticPipeline.processPendingSubmissions()`, query any remaining `"disputed"` entries
- Spawn an `ArbitrationAgent` via `EvoKammerAgentFactory.buildArbitrator()` with the disputed submission + both critic reviews as context
- Arbitrator's final verdict (PROMOTE or REJECT) applied by `CriticPipeline.applyReview()`

---

### C-8: Grader Groups full civilisation cycle (§10.10 #1)

**Problem:** Grader Groups are 2 criteria agents + 1 RoundJudge per round. The spec requires a full EvoKammer civilisation (evolutionary selection, multi-generation KB accumulation, Critic pipeline for grader staging, Meta-Judge consensus).

**Design:**
- Grader groups have their own `LifecycleManager` + `CriticPipeline` + `EvolutionarySelector`
- `GraderCriteriaRunner` spawns a full generation of grader agents (not just 2), with KB accumulation across rounds
- Add `GraderMetaJudge` that runs after all grader groups' RoundJudge results are in — detects consensus and resolves outliers
- Add `CrossGraderArbitration` for cases where Meta-Judge detects systemic disagreement

---

### C-9: Scoring Engine isolation (§10.11 #1)

**Problem:** `computeAndSubmitSubstrateScores` runs in the same process and coroutine context as all agents. The spec requires isolation.

**Design:**
- Move scoring into a `ScoringEngineRunner` that executes in a separate `CoroutineScope` with its own `Dispatchers.IO` context
- Scoring reads only from DB snapshots (no live agent state); agents cannot observe or influence scoring mid-execution
- Score results communicated back via a sealed channel, not shared mutable state

---

### C-10: Critic as lifecycle-bound agent (§10.5 #5)

**Problem:** `CriticPipeline` is a plain class with LLM calls. No budget, no WiP task, no Death Note.

**Design:**
- Replace `CriticPipeline`'s internal LLM calls with a full `LifecycleManager`-managed Critic agent
- Agent spawned with a `CriticRole` boot context, limited budget, and a pre-assigned WiP task referencing the batch of pending submissions
- Agent writes a Death Note on timeout/budget exhaustion; any unreviewed submissions auto-reject
- This is a major refactor and should be gated on C-8 (Grader civilisation) patterns being stable first

---

## Summary Table

| Priority | # | Item | Effort | Spec ref |
|---|---|---|---|---|
| A | 1 | Critic self-submission guard | 30 min | §10.5 #3 |
| A | 2 | EvolutionarySelector structured event | 1 hr | §10.6 #6 |
| A | 3 | Check-in dimension scores | 1 hr | §10.12 #2 |
| A | 4 | Dead Ends always-return in KB queries | 1 hr | §10.4 #4 |
| A | 5 | `wip_task_reference` enforcement | 2 hr | §10.16 #2 |
| A | 6 | `budget_signature` on staging | 2 hr | §10.4 #8 |
| A | 7 | Watchdog cost ceiling → pause spawning | 1 hr | §10.14 #5 |
| A | 8 | Verify DifferentialPrivacyTransform wiring | 1 hr | §10.8 #3-4 |
| B | 1 | KGConsistencyChecker | 4 hr | §10.4 #5 |
| B | 2 | KG_CONSTRAINT applied to schema table | 3 hr | §10.17 #6 |
| B | 3 | `consistencyReport` field in StartingField | 2 hr | §10.1 #3 |
| B | 4 | Legacy Protocol expiry + Dead Ends | 2 hr | §10.13 #4-5 |
| B | 5 | Watchdog check-in gate control | 3 hr | §10.14 #3 |
| B | 6 | Migration rate ceiling enforcement | 2 hr | §10.8 #5 |
| B | 7 | `usedDefault` false positive fix | 30 min | §10.17 #5 |
| C | 1 | CrossoverEngine | 1 day | §10.13 #3 |
| C | 2 | ResourceMapEngine node capture | 2 days | §10.9 #2-4 |
| C | 3 | Blind scoring seal | 1 day | §10.11 #3-4 |
| C | 4 | Genesis Council sealed test suite | 2 days | §10.1 #5 |
| C | 5 | Meta-Planner spawn path | 2 days | §10.14-15 |
| C | 6 | Auditor spawn by Watchdog | 1 day | §10.7 #3 |
| C | 7 | KG-Critic Arbitration Agent | 2 days | §10.4 #6 |
| C | 8 | Grader Groups full civilisation | 5 days | §10.10 #1 |
| C | 9 | Scoring Engine isolation | 1 day | §10.11 #1 |
| C | 10 | Critic as lifecycle-bound agent | 3 days | §10.5 #5 |

**Total Priority A: ~9.5 hours**
**Total Priority B: ~17 hours**
**Total Priority C: ~18 days**

---

## Recommended Implementation Order

### For the failing run specifically

1. Fix sealed-suite hydration first so deterministic scoring is evaluating real tests instead of `0/0`.
2. Add a provider-level OpenRouter contract smoke test for `google/gemini-2.5-flash` and `google/gemini-3-flash-preview` that captures the raw response body and validates a non-empty, tool-capable response before any long run starts.
3. Add an OpenRouter fallback path for grading and judge phases, or pin those phases to a proven model until the provider contract is stable.
4. Centralize model selection in one config file and remove expensive default models like `anthropic/claude-sonnet-4.6` from the general path unless a role is explicitly exempted.
5. Inventory every current model-selection call site and remove any per-role hardcoded defaults so the new config is authoritative.
6. Map each existing role to a cheaper default or approved fallback before removing the old defaults.
7. Thread `wip_task_reference` through every agent path that emits KG facts, constraints, and plan updates so useful submissions are not auto-rejected.
8. Raise `maxAgentIterations` or reduce prompt/tool loop complexity for the roles that are repeatedly hitting the 25-step ceiling.
9. Re-run the full round flow and verify that the system now produces non-zero per-dimension scores and non-empty final outputs.

1. **Sprint 1** (1–2 days): All Priority A items — these are small, surgical, and unblock correctness
2. **Sprint 2** (3–4 days): B-1 (KGConsistencyChecker), B-2 (schema constraints), B-4 (protocol expiry), B-7 (usedDefault fix)
3. **Sprint 3** (3–4 days): B-3, B-5, B-6, B-8 — remaining Priority B items
4. **Sprint 4** (5 days): C-3 (blind seal), C-7 (Arbitration Agent), C-9 (scoring isolation) — most impactful C items
5. **Sprint 5+**: C-1, C-2, C-4, C-5, C-6 in dependency order
6. **Final sprint**: C-8 (Grader civilisation) and C-10 (Critic as agent) — largest scope, most risk

---

*EvoKammer Remediation Plan — 2026-05-27*
