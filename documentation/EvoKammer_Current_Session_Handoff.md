# EvoKammer Current Session Handoff

## Current Goal Extension (2026-06-06)
- New active goal:
  - apply fixes for the fast CLI variant 6 run
  - rerun the fast CLI end-to-end
  - inspect the outputs and logs for non-error issues, not just crashes
  - verify the implementation is working cleanly and efficiently

## MoE Router Slice (2026-06-15)
- Expanded the deterministic objective router with a new `perspective-heavy` expert bundle for stakeholder / blind-spot / verification tasks.
- `ObjectiveExpertRouter.select(...)` now routes perspective-oriented objectives to `PerspectiveTaker`, `Critic`, `Inspector`, `Auditor`, `Educator`, `Planner`, `Connector`, `Researcher`, and `Builder` assignments rather than falling back to the balanced baseline.
- Added router regressions in `ObjectiveExpertRouterTest` and an API integration regression in `EvoKammerRunServiceObjectiveExpertRoutingTest`.
- Verified with:
  - `./gradlew --no-daemon --no-build-cache :evokammer:core:test --tests com.achulkov.evokammer.core.config.ObjectiveExpertRouterTest`
  - `./gradlew --no-daemon --no-build-cache :evokammer:api:test --tests com.achulkov.evokammer.api.service.EvoKammerRunServiceObjectiveExpertRoutingTest`
- Next MoE gap:
  - the router is still deterministic/rule-based by design
  - a true learned/LLM-backed router would need a separate product decision because it would change runtime routing behavior materially

## Recurrent Loop Slice (2026-06-15)
- Added an explicit forget-gate compaction pass to the recurrent self-improvement kernel.
- `OverseerKernel.compactTerminalState(...)` now prunes stale terminal hypotheses, their plans, schedules, and results from the active kernel snapshot while preserving the archived history trail.
- `OverseerKernel.compactTerminalState(...)` now also removes overflow terminal entries from the stalest end of the age-ordered list, so cap-based compaction removes the oldest terminal state first.
- `OverseerKernelService.compactTerminalState(...)` now compacts and persists atomically, then records a `COMPACT_KERNEL_STATE` audit entry when pruning occurs.
- `SelfImprovementLoopRunner` now invokes the forget gate once per cycle so the recursive loop does not keep re-reading stale terminal state forever.
- Verified with:
  - `./gradlew --no-daemon --no-build-cache :evokammer:api:test --tests com.achulkov.evokammer.api.service.OverseerKernelServiceForgetGateTest`
  - `./gradlew --no-daemon --no-build-cache :evokammer:cli:test --tests com.achulkov.evokammer.cli.SelfImprovementLoopRunnerTest`
- Next recurrent-loop gap:
  - age-based compaction is deterministic and restart-safe, but it is still a snapshot-level forget gate rather than a learned policy

## Adversarial Reward Slice (2026-06-15)
- Added a deterministic discriminator reward multiplier derived from the latest blind grading spread.
- `EvoKammerRunService` now stores that multiplier per run, reloads it from the run meta-archive after restart, and exposes it so the production runtime can reward stronger discriminator rounds with slightly more budget on the next cycle.
- `GraderRunner` now scales grader spawn budgets through the multiplier, and the production runtime threads the current reward into both the criteria and judging graders.
- Verified with:
  - `./gradlew --no-daemon --no-build-cache :evokammer:api:test --tests com.achulkov.evokammer.api.service.AdversarialDiscriminatorRewardTest --tests com.achulkov.evokammer.api.grader.GraderRunnerBudgetMultiplierTest`
  - `./gradlew --no-daemon --no-build-cache :evokammer:runtime:compileKotlin`
- Next adversarial gap:
  - the reward still uses a deterministic grading-spread proxy rather than a learned calibration model

## Collective-Intelligence Slice (2026-06-15)
- Turn-taking regression is now green in `:evokammer:orchestration:test --tests com.achulkov.evokammer.orchestration.selector.EvolutionarySelectorDiversityTest`.
- Perspective-taking wiring is already live end-to-end:
  - `InspectorRunner` raises `Role.PerspectiveTaker` on turn-taking imbalance signals.
  - `EvoKammerRunService` spawns a `PerspectiveTaker` special agent for those flags.
  - `RolePromptBuilder` renders the perspective coverage / blind-spot rubric for graders.
  - `GraderRunner` includes the perspective-aware criteria phase coverage.
- Verified with:
  - `./gradlew --no-daemon --no-build-cache :evokammer:orchestration:test --tests com.achulkov.evokammer.orchestration.selector.EvolutionarySelectorDiversityTest`
  - `./gradlew --no-daemon --no-build-cache :evokammer:orchestration:test --tests com.achulkov.evokammer.orchestration.inspector.InspectorRunnerTest`
  - `./gradlew --no-daemon --no-build-cache :evokammer:api:test --tests com.achulkov.evokammer.api.grader.GraderRunnerPerspectiveRubricTest --tests com.achulkov.evokammer.api.grader.GraderRunnerPerspectiveRubricIntegrationTest --tests com.achulkov.evokammer.api.EvoKammerRunServiceModeGatingTest`
- Next likely gap from the architecture plan:
  - learned or model-backed MoE routing remains deterministic/rule-based for now
  - if that becomes a priority, it needs product direction because it would change routing behavior materially

## Graph-Topology Slice (2026-06-15)
- Implemented a trust-aware adaptive dispatch topology on top of the existing ring/star/small-world graph shapes.
- `DispatchTopologyAdaptationSpec` is now part of `StartingField`, so council-authored runs can enable graph rewiring through the normal run contract.
- `EvoKammerRunService` now passes the starting-field adaptation spec into `GroupCoordinator.configureTopology(...)` at run startup.
- `DispatchRelay` now keeps a cached adjacency snapshot and can rewire low-trust edges to higher-trust alternatives when `refreshTopology()` is called.
- `GroupCoordinator` now refreshes adaptive topology at round boundaries via `onRoundStart(...)`.
- Added regressions for both the relay-level rewiring behavior and the coordinator round-boundary refresh path.
- Latest targeted verification:
  - `./gradlew --no-daemon :evokammer:competition:test --tests com.achulkov.evokammer.competition.dispatch.DispatchRelayAdaptiveTopologyTest --tests com.achulkov.evokammer.competition.coordinator.GroupCoordinatorAdaptiveTopologyTest`
  - Result: `BUILD SUCCESSFUL`

## Task Decomposition Slice (2026-06-15)
- Added explicit `owningRole` metadata to `DeliverableSkeletonNode` and `DeliverableSkeletonNodeUpdate`, so role responsibility is part of the persisted DST contract instead of being implied only by the live agent assignment.
- `DeliverableSkeletonTree.fromNodeGraph(...)` now seeds default owners by node type, and DST refiners/store updates preserve or update the owning role when nodes are split, merged, or promoted.
- `DeliverableSkeletonTree.summarize()` now reports ownership clarity metrics, which the selector can consume for role-clarity-aware allocation.
- `RolePromptBuilder` now renders a compact task-ownership map from the run’s deliverable skeleton tree, making decomposition and role ownership visible to agents at spawn time.
- `FinalSynthesiser` now carries ownership into the final tree renderers and JSON output.
- Added regression coverage for `FinalSynthesiser` plaintext/JSON ownership rendering and for legacy DST JSON that omits `owningRole`.
- Added an end-to-end resumed run prompt regression proving the task ownership map survives the full service-to-prompt path.
- Latest targeted verification:
  - `./gradlew --no-daemon --no-build-cache :evokammer:core:test --tests com.achulkov.evokammer.core.contract.StartingFieldSerializationTest`
  - `./gradlew --no-daemon --no-build-cache :evokammer:agents:test --tests com.achulkov.evokammer.agents.factory.RolePromptBuilderTest`
  - `./gradlew --no-daemon --no-build-cache :evokammer:api:test --tests com.achulkov.evokammer.api.service.DeliverableSkeletonTreeStoreTest`
  - `./gradlew --no-daemon --no-build-cache :evokammer:orchestration:test --tests com.achulkov.evokammer.orchestration.selector.EvolutionarySelectorDeliverableTreeTest`
  - `./gradlew --no-daemon --no-build-cache :evokammer:api:test --tests com.achulkov.evokammer.api.service.FinalSynthesiserOwnershipTest --tests com.achulkov.evokammer.api.service.EvoKammerRunServiceResumeGraphPromptTest`
  - Result: `BUILD SUCCESSFUL`

## Turn-Taking Slice (2026-06-15)
- `EvolutionarySelector` now enforces a concrete turn-taking budget balance rule by swapping one unit of an overdominant role into a complementary role when allocation share exceeds the configured dominance ceiling.
- This keeps the existing inspector-based imbalance detection, but turns the human-team constraint into an actual allocation policy rather than only a post-hoc flag.
- Added a selector regression proving the balanced allocation reduces dominant-share concentration while preserving diverse roles.
- Latest targeted verification:
  - `./gradlew --no-daemon --no-build-cache :evokammer:orchestration:test --tests com.achulkov.evokammer.orchestration.selector.EvolutionarySelectorDiversityTest --tests com.achulkov.evokammer.orchestration.selector.EvolutionarySelectorDeliverableTreeTest --tests com.achulkov.evokammer.orchestration.lifecycle.LifecycleManagerTest`
  - Result: `BUILD SUCCESSFUL`
- Verification blocker encountered afterward:
  - `:evokammer:api:compileKotlin` / `:evokammer:api:compileTestKotlin` surfaced unrelated existing compile failures in `NestedHierarchySpawnPlanner.kt` and `RunInputArtifactFilesTest.kt` while sweeping broader API tests.

## Current Goal Extension (2026-06-06, self-improvement)
- New active goal:
  - add a separate `self-improve` launcher command
  - run it against a copied or detached workspace, never the live checkout
  - keep the launch implementation robust first, then validate it later
- Locked decision:
  - expose the launcher as a second CLI subcommand on the existing `evokammer` binary
  - prefer `git worktree` when available, but fall back to a filesystem copy
  - write a durable launch manifest and combined stdout/stderr log into the isolated workspace

## Self-Improvement Launcher Status
- Implemented a new `self-improve` subcommand on `:evokammer:cli`.
- Added an isolated-workspace launcher that:
  - validates source/workspace roots before doing anything else
  - uses `git worktree` when available
  - falls back to a filtered filesystem copy when necessary
  - writes `.evokammer-self-improve/launch-manifest.json`
  - writes `.evokammer-self-improve/launch.log`
- Verified unit coverage:
  - `./gradlew --no-daemon :evokammer:cli:compileKotlin :evokammer:cli:test --tests com.achulkov.evokammer.cli.SelfImprovementCliArgumentsTest --tests com.achulkov.evokammer.cli.SelfImprovementLauncherTest`
- Verified a live smoke run against a copied workspace:
  - command: `:evokammer:cli:run` with `self-improve ... -mode copy ...`
  - workspace: `/tmp/evokammer-self-improve-smoke/smoke-20260606`
  - manifest: `/tmp/evokammer-self-improve-smoke/smoke-20260606/.evokammer-self-improve/launch-manifest.json`
  - log: `/tmp/evokammer-self-improve-smoke/smoke-20260606/.evokammer-self-improve/launch.log`
- The smoke run exited `0` and the log contained the expected `smoke-ok` marker.

## Current Live Validation (2026-06-12)
- The overseer-cycle runner now receives the source repo root and reads real repository context during bootstrap.
- Added bootstrap-context coverage to `SelfImprovementLoopRunnerTest`:
  - verifies the empty-kernel fallback can read the current session handoff and operator guide from the source repo copy
  - verifies bootstrap metadata is persisted in hypothesis notes
- Added CLI wiring coverage:
  - `SelfImprovementCliOverseerCycleWiringTest` verifies `runOverseerCycle(...)` threads the source repo root, isolated workspace root, OpenRouter key, and cycle limit into the runner seam
- Added fast pipeline patch-application coverage:
  - `CliPipelineCodeImprovementPlanTest` verifies a published `outputs/improvements/code-improvement-plan.json` is mirrored into pipeline outputs
  - the same test verifies the next stage input tree on disk is rewritten before the next stage starts
  - the next stage receives the updated file content through uploaded run context artifacts
- Added live fast pipeline proof:
  - `fast-code-live-reuse-20260612` reused a published code-improvement plan and completed a follow-up `verify` stage
  - the follow-up stage input tree shows the rewritten `Example.kt` content before that stage started
  - the live pipeline published `outputs/improvements/code-improvement-plan.json` and reused it successfully across the stage boundary
- Verified a live 2-cycle overseer run against the updated code:
  - command: `:evokammer:cli:run` with `self-improve ... -overseer-cycle -overseer-cycles 2`
  - output root: `/tmp/evokammer-self-improve-loop/cycle-002`
  - cycle 1: formalized `HYP-001`
  - cycle 2: registered `EXP-001` and scheduled it
  - persisted state now contains an under-experiment hypothesis plus one scheduled experiment plan
- Remaining limitation:
  - the overseer loop still seeds and schedules; it does not yet apply code edits back into a repo copy automatically

## Current Overseer Relaunch (2026-06-13)
- Fresh isolated workspace:
  - `/tmp/evokammer-self-improve-longrun/overseer-20260613-bootstrap3`
- Bootstrap source:
  - `documentation/EvoKammer_Architecture_Improvements_Plan.md`
- Bootstrap shape:
  - one plan-driven hypothesis is seeded per empty cycle from the architecture-plan backlog
  - missing architecture-plan headings fail loudly instead of collapsing into a thin fallback rationale
  - when execution is enabled, the bootstrap also registers one experiment plan for the selected item
- Current runtime status:
  - relaunch is active and producing real agent/tool activity
  - cycle 0 has already moved through grader/criteria activity instead of going inert after bootstrap
  - the run is now aligned to a backlog model rather than a one-shot bootstrap dump
- Remaining watch item:
  - confirm the loop continues beyond the first cycle and keeps advancing through the remaining plan items

## Current Architecture-Plan Follow-up (2026-06-14)
- Current active implementation focus:
  - human collective intelligence constraint 7.2, now expressed as a turn-taking imbalance signal plus a `PerspectiveTaker` intervention path
- Runtime behavior now in place:
  - `LifecycleManager` emits `INSPECTOR_FLAG` for lopsided role allocation/spend
  - `InspectorRunner` recognizes turn-taking anomalies in recent event logs
  - `EvoKammerRunService` can spawn a `PerspectiveTaker` special agent when the inspector flags a coordination blind spot
- Verification added:
  - core role/model-selection regressions for `PerspectiveTaker`
  - orchestration regression for inspector turn-taking detection
  - API regression for spawning `PerspectiveTaker` from the watchdog path
- Important scope note:
  - the earlier fairness work remains in place, but the current improvement is the perspective-taking response path, not just the imbalance signal itself

## Current Run-Seed Propagation (2026-06-14)
- Residual `StartingField` injection is now threaded into the agent boot context and rendered into the standard system prompt for all ordinary lifecycle-spawned agents.
- `LifecycleManager` now stores the original run seed via `configureStartingField(...)` and passes it into every `AgentBootContext`, including nested lifecycle managers.
- `RolePromptBuilder` now renders a concise `=== ORIGINAL RUN SEED ===` block so spawned agents can see the canonical run metadata alongside the current task context.
- Added a prompt regression test that checks the original run seed block is present in ordinary agent prompts.
- Verified with `./gradlew :evokammer:core:compileKotlin :evokammer:agents:compileKotlin :evokammer:orchestration:compileKotlin :evokammer:api:compileKotlin :evokammer:agents:test --tests com.achulkov.evokammer.agents.factory.RolePromptBuilderTest`.
- Verified with `./gradlew :evokammer:api:test --tests com.achulkov.evokammer.api.EvoKammerRunServiceModeGatingTest --tests com.achulkov.evokammer.api.service.NestedHierarchySpawnPlannerTest`.
- Remaining open question for later follow-up: whether the grader-civilisation prompt path should receive the same boot-context seed block directly, or continue relying on the existing grader seed substrate channel.

## Current Adversarial Loop (2026-06-14)
- Implemented the first adversarial generator/discriminator feedback loop for round-to-round improvement.
- After blind scoring, each group's scores are distilled into a compact discriminator brief that highlights the weakest dimensions and the submission-summary headline.
- Those discriminator briefs are prepended to the next round's stage disclosure so generators receive failure-focused context before spawning.
- Added a regression test covering the discriminator brief helper.
- Verified with `./gradlew :evokammer:api:compileKotlin :evokammer:api:test --tests com.achulkov.evokammer.api.service.AdversarialDiscriminatorBriefTest --tests com.achulkov.evokammer.api.service.RunArchiveServiceTest :evokammer:orchestration:test --tests com.achulkov.evokammer.orchestration.lifecycle.LifecycleManagerTest`.
- Archive output now includes the additional compact `outputs/high-level-run-log.txt` artifact alongside the detailed logs.

## Current Grader Criteria Rubric Fix (2026-06-14)
- The criteria-phase perspective rubric is now aligned with the live criteria builder path and explicitly includes `trade-off reconciliation`.
- This fixes the regression where the criteria-phase prompt was correct in structure but failed the exact substring assertions in the live prompt capture tests.
- Verified with `./gradlew --no-daemon :evokammer:api:test --tests com.achulkov.evokammer.api.grader.GraderRunnerPerspectiveRubricTest --tests com.achulkov.evokammer.api.grader.GraderRunnerPerspectiveRubricIntegrationTest`.

## Current Topology Routing (2026-06-14)
- Added an explicit `GroupTopologyKind` contract so the competition layer can choose between ring, star, and small-world inter-group routing.
- `StartingField` now serializes the configured topology kind, and `GroupCoordinator` reconfigures its `DispatchRelay` from that value at run start.
- `DispatchRelay` now computes neighbours according to the configured topology instead of assuming a fixed ring.
- Verified with `./gradlew --no-daemon :evokammer:competition:test --tests com.achulkov.evokammer.competition.dispatch.DispatchRelayTopologyTest --tests com.achulkov.evokammer.competition.dispatch.DispatchRelayPrivacyTransformTest :evokammer:api:test --tests com.achulkov.evokammer.api.EvoKammerRunServiceModeGatingTest :evokammer:core:test --tests com.achulkov.evokammer.core.contract.StartingFieldSerializationTest`.

## Current Run-Graph Router (2026-06-14)
- Extracted the continuation-context scoring and traversal logic into `ContinuationContextGraphRouter` so graph-routing has a dedicated owner instead of living only inside `EvoKammerRunService`.
- The router now prefers a dedicated `RUN_GRAPH_MESSAGE` archive entry for each completed run, then falls back to the embedded `RunGraphMessage` on the completion record, and only then falls back to the old high-level-log / summary scrape for that same run.
- `EvoKammerRunService` now writes that dedicated graph-message entry at completion time, so the graph bus is a first-class archive artefact rather than only a derived field.
- The router still walks the ancestry graph, so ancestor runs can contribute their own messages when present.
- Verified with `./gradlew --no-daemon :evokammer:api:test --tests com.achulkov.evokammer.api.service.RunCompletionRecordSerializationTest --tests com.achulkov.evokammer.api.service.EvoKammerRunServiceRoutingTest --tests com.achulkov.evokammer.api.service.EvoKammerRunServiceGraphRoutingTest`.

## Current Adversarial Critic Loop (2026-06-15)
- The adversarial discriminator brief is now archived as a dedicated `ADVERSARIAL_DISCRIMINATOR_FEEDBACK` entry per group, not only held in the in-memory round map.
- `EvoKammerRunService` persists the brief at round end and can read it back when the in-memory brief is absent, so the feedback survives restart / resume boundaries.
- The next generator round still receives the brief in stage disclosure, but the signal is now durable and replayable.
- Verified with `./gradlew --no-daemon :evokammer:api:test --tests com.achulkov.evokammer.api.service.AdversarialDiscriminatorBriefTest --tests com.achulkov.evokammer.api.service.EvoKammerRunServiceRoutingTest --tests com.achulkov.evokammer.api.service.RunCompletionRecordSerializationTest`.

## Current Feedforward Stack And Attention Routing (2026-06-15)
- Added an explicit `FeedforwardLayerSpec` to `StartingField` so layered runs can carry a first-class L1/L2/L3 descriptor and upstream run IDs.
- `RolePromptBuilder` now renders a `Feedforward layer` block in the original run seed, and continuation runs automatically advance the layer descriptor when present.
- `ContinuationContextGraphRouter` now mixes embedding similarity into its scoring, giving the continuation path a simple attention-style signal on top of graph distance and artifact priority.
- Verified with `./gradlew --no-daemon :evokammer:core:test --tests com.achulkov.evokammer.core.contract.StartingFieldSerializationTest :evokammer:agents:test --tests com.achulkov.evokammer.agents.factory.RolePromptBuilderTest :evokammer:api:test --tests com.achulkov.evokammer.api.service.EvoKammerRunServiceGraphRoutingTest --tests com.achulkov.evokammer.api.service.EvoKammerRunServiceRoutingTest`.

## Current MoE Router Expansion (2026-06-15)
- Added a coordination-heavy expert bundle to `ObjectiveExpertRouter` so coordination/integration objectives use a distinct router profile instead of falling through to the generic synthesis or balanced defaults.
- The objective router now has explicit coverage for research-heavy, build-heavy, synthesis-heavy, coordination-heavy, and balanced-baseline bundles.
- Verified with `./gradlew --no-daemon :evokammer:core:test --tests com.achulkov.evokammer.core.config.ObjectiveExpertRouterTest :evokammer:api:test --tests com.achulkov.evokammer.api.service.EvoKammerRunServiceObjectiveExpertRoutingTest`.

## Current Dispatch Trust Persistence (2026-06-14)
- Dispatch trust is now persisted per receiving group/source pair through `TrustScoreDao` when the relay delivers a message or when feedback is recorded.
- `EvoKammerRunService` now hands each group's trust-score DAO to the coordinator at run start so the trust channel survives process boundaries.
- `GroupCoordinator` keeps the in-memory scorer and the substrate-backed rows aligned through the same dispatch trust API.
- Verified with `./gradlew --no-daemon :evokammer:competition:test --tests com.achulkov.evokammer.competition.dispatch.DispatchTrustScoringTest --tests com.achulkov.evokammer.competition.dispatch.DispatchRelayTrustScoringTest --tests com.achulkov.evokammer.competition.dispatch.DispatchRelayTopologyTest --tests com.achulkov.evokammer.competition.dispatch.DispatchRelayPrivacyTransformTest --tests com.achulkov.evokammer.competition.coordinator.GroupCoordinatorDispatchLagTest --tests com.achulkov.evokammer.competition.coordinator.GroupCoordinatorLeakTest :evokammer:api:test --tests com.achulkov.evokammer.api.EvoKammerRunServiceModeGatingTest --tests com.achulkov.evokammer.api.service.EvoKammerRunServiceDisclosureLeakTest`.

## Current Dispatch Trust Wiring (2026-06-14)
- `DispatchRelay` now carries an in-memory `DispatchTrustScoring` policy object and exposes `trustScoreOf(...)` plus `recordFeedback(...)`.
- `GroupCoordinator` delegates dispatch trust operations to the relay so future critic-side feedback can update the same policy surface without adding another trust tracker.
- Added regressions for the trust scorer itself and for the relay-level trust delegation.
- Verified with `./gradlew --no-daemon :evokammer:competition:test --tests com.achulkov.evokammer.competition.dispatch.DispatchTrustScoringTest --tests com.achulkov.evokammer.competition.dispatch.DispatchRelayTrustScoringTest --tests com.achulkov.evokammer.competition.dispatch.DispatchRelayTopologyTest --tests com.achulkov.evokammer.competition.dispatch.DispatchRelayPrivacyTransformTest --tests com.achulkov.evokammer.competition.coordinator.GroupCoordinatorDispatchLagTest --tests com.achulkov.evokammer.competition.coordinator.GroupCoordinatorLeakTest :evokammer:api:test --tests com.achulkov.evokammer.api.EvoKammerRunServiceModeGatingTest --tests com.achulkov.evokammer.api.service.EvoKammerRunServiceDisclosureLeakTest`.

## Current Dispatch Lag Wiring (2026-06-14)
- `GroupCoordinator` now owns a `FormalDispatchRelay` alongside the topology-aware relay, so voluntary cross-group dispatches can be held for a configurable generation lag.
- `dispatchLagGenerations` is now carried through the coordinator and flushed at round boundaries from `EvoKammerRunService`, which makes the lagged path live instead of dead code.
- Added a regression test proving dispatches stay queued until `flushPendingDispatches(...)` runs.
- Verified with `./gradlew --no-daemon :evokammer:competition:test --tests com.achulkov.evokammer.competition.dispatch.DispatchRelayTopologyTest --tests com.achulkov.evokammer.competition.dispatch.DispatchRelayPrivacyTransformTest --tests com.achulkov.evokammer.competition.coordinator.GroupCoordinatorDispatchLagTest --tests com.achulkov.evokammer.competition.coordinator.GroupCoordinatorLeakTest :evokammer:api:test --tests com.achulkov.evokammer.api.EvoKammerRunServiceModeGatingTest --tests com.achulkov.evokammer.api.service.EvoKammerRunServiceDisclosureLeakTest`.

## Current Attention Routing (2026-06-14)
- The continuation path now derives routed prior-run context from the archived source run and threads the selected summaries into the new `StartingField`.
- `StartingField` carries `routedContextSummaries`, and `RolePromptBuilder` renders them in a bounded `=== ROUTED CONTEXT ===` section for continuation launches.
- The routing helper is heuristic and archive-driven for now: it prefers compact high-level artifacts, final answer summaries, deliverable HTML, group summaries, and judge outputs, then ranks them with artifact-type priority before objective overlap.
- Verified with `./gradlew :evokammer:api:compileKotlin :evokammer:api:test --tests com.achulkov.evokammer.api.service.EvoKammerRunServicePersistedResumeTest --tests com.achulkov.evokammer.api.service.AdversarialDiscriminatorBriefTest --tests com.achulkov.evokammer.api.service.RunArchiveServiceTest :evokammer:agents:test --tests com.achulkov.evokammer.agents.factory.RolePromptBuilderTest`.
- Verified with `./gradlew :evokammer:core:test --tests com.achulkov.evokammer.core.contract.StartingFieldSerializationTest :evokammer:api:test --tests com.achulkov.evokammer.api.service.EvoKammerRunServiceRoutingTest --tests com.achulkov.evokammer.api.service.EvoKammerRunServiceContinuationRoutingSmokeTest --tests com.achulkov.evokammer.api.service.EvoKammerRunServicePersistedResumeTest --tests com.achulkov.evokammer.api.service.AdversarialDiscriminatorBriefTest --tests com.achulkov.evokammer.api.service.RunArchiveServiceTest :evokammer:agents:test --tests com.achulkov.evokammer.agents.factory.RolePromptBuilderTest`.

## Current MoE Baseline (2026-06-14)
- Added a deterministic objective router that selects a small expert bundle from the run objective instead of relying only on the generic runtime defaults.
- The router currently distinguishes research-heavy, build-heavy, synthesis-heavy, and balanced baseline bundles and returns both model assignments and per-role pools.
- `EvoKammerRunService` now applies the expert bundle only when the council did not already author model selections, so council-authored runs still win.
- Added regression coverage for the pure router and for the service-level fallback path that fills a test run with an objective-specific expert bundle.
- Verified with `./gradlew :evokammer:core:test --tests com.achulkov.evokammer.core.config.ObjectiveExpertRouterTest --tests com.achulkov.evokammer.core.contract.StartingFieldSerializationTest :evokammer:api:test --tests com.achulkov.evokammer.api.service.EvoKammerRunServiceObjectiveExpertRoutingTest --tests com.achulkov.evokammer.api.service.EvoKammerRunServiceRoutingTest --tests com.achulkov.evokammer.api.service.EvoKammerRunServiceContinuationRoutingSmokeTest --tests com.achulkov.evokammer.api.service.EvoKammerRunServicePersistedResumeTest --tests com.achulkov.evokammer.api.service.AdversarialDiscriminatorBriefTest --tests com.achulkov.evokammer.api.service.RunArchiveServiceTest :evokammer:agents:test --tests com.achulkov.evokammer.agents.factory.RolePromptBuilderTest`.

## Current Run-Graph Expansion (2026-06-14)
- Added explicit run-graph ancestry edges to `StartingField` so continuation runs can carry a small graph of prior launches instead of only a linear predecessor pointer.
- `EvoKammerRunService` now appends a continuation edge when it derives a new `StartingField`, and its routed-context selector recursively traverses the inherited ancestry graph to pull context from earlier connected runs.
- `RolePromptBuilder` renders a bounded `=== RUN GRAPH ===` section so the ancestry is visible in the launched prompt.
- Added regressions for `StartingField` serialization compatibility, sealed-archive persistence of populated graph edges, graph traversal over an ancestor chain, the continuation edge append behavior, and persisted-resume prompt visibility.
- Verified with `./gradlew :evokammer:api:compileKotlin :evokammer:core:test --tests com.achulkov.evokammer.core.config.ObjectiveExpertRouterTest --tests com.achulkov.evokammer.core.contract.StartingFieldSerializationTest :evokammer:agents:test --tests com.achulkov.evokammer.agents.factory.RolePromptBuilderTest :evokammer:api:test --tests com.achulkov.evokammer.api.service.EvoKammerRunServiceGraphRoutingTest --tests com.achulkov.evokammer.api.service.EvoKammerRunServiceObjectiveExpertRoutingTest --tests com.achulkov.evokammer.api.service.EvoKammerRunServiceContinuationRoutingSmokeTest --tests com.achulkov.evokammer.api.service.EvoKammerRunServiceRoutingTest`.

## Current Human-Pattern Constraints (2026-06-14)
- The turn-taking dominance signal in `LifecycleManager` now carries explicit conversation-entropy metrics derived from the live role budget distribution, making the equal-participation constraint measurable rather than only heuristic.
- The emitted inspector-flag payload now includes both `conversationEntropy` and `normalizedConversationEntropy`, which makes the signal auditable downstream.
- Grader prompts now include a dedicated `=== PERSPECTIVE COVERAGE RUBRIC ===` block that instructs the grader civilisation to score stakeholder-perspective modelling, blind spots, and trade-off reconciliation.
- `EvolutionarySelector` now emits a role-diversity entropy score, normalized diversity, distinct-role count, and outlier share in its allocation decision payload, giving the bounded-heterogeneity constraint a concrete audit trail.
- Added regressions for the entropy helper, the turn-taking inspector payload, the grader perspective-coverage rubric, and the selector diversity metric.
- Verified with `./gradlew :evokammer:orchestration:test --tests com.achulkov.evokammer.orchestration.lifecycle.LifecycleManagerTest --tests com.achulkov.evokammer.orchestration.inspector.InspectorRunnerTest` and `./gradlew :evokammer:agents:compileKotlin :evokammer:agents:test --tests com.achulkov.evokammer.agents.factory.RolePromptBuilderTest`.

## Fresh Rerun Result
- Fresh rerun output root:
  - `/Volumes/CrucialX9_1TB/AStudioProjects/EvoMolior/documentation/tmp/fast_cli_variant6_output_rerun2/fast-cli-variant6-20260606-rerun2/`
- The fresh rerun completed successfully and is the first valid signal for this goal because the API key was passed correctly inside the Gradle `--args` string.
- Verified good state:
  - `summary.json` now contains a real `debugLogPath`
  - `debug-log.txt` exists in the run output
  - there are no `401 Missing Authentication header` entries in the fresh debug log
  - the final article artifact exists and is published in `results.zip`
- Remaining quality concerns:
  - the fast run still took several hours end-to-end
  - the log contains many provider `Connection reset` failures
  - several grader criteria and judge calls timed out and fell back to substrate scoring
  - many agent completions were warning-only with no tool/write actions
- Interpretation:
  - the previous null `debugLogPath` / auth issue was an invocation error, not a code-path bug
  - the bigger-picture implementation is functional, but the fast grading path still needs performance/reliability hardening if the goal is truly efficient fast runs

## Baseline Evidence From The Previous Variant 6 Run
- The previous fast run completed successfully under:
  - `/Volumes/CrucialX9_1TB/AStudioProjects/EvoMolior/documentation/tmp/fast_cli_variant6_output/fast-cli-variant6-20260606/`
- Observed issues in the archived artifacts:
  - `summary.json` reported `debugLogPath: null`
  - no `debug-log.txt` was persisted in the run directory
  - grader notes inside `outputs/final-article.html` captured repeated `401 Missing Authentication header` failures
- The current task is to rerun after fixes and determine whether those issues are still present.

# EvoKammer Current Session Handoff

## Current Goal Extension (2026-06-05)
- New active goal:
  - implement durable crash-resume support for persisted runs
  - recover the unfinished stage run `adhd-live-pipeline-comparison-r1-evidence-brief`
  - audit weak runtime points and implementation-plan phase alignment

## New Work Completed In This Pass
- Added filesystem-backed persisted run state support, deliberately avoiding a Room schema
  migration because `SubstrateDatabaseFactory` uses destructive fallback migrations and we must
  not risk wiping the very persisted run we are trying to recover.
- Added run-root/control helpers on `SubstrateDatabaseFactory`:
  - `runRootDir(runId)`
  - `runControlDir(runId)`
- Added persisted recovery model and store:
  - `evokammer/api/src/main/kotlin/com/achulkov/evokammer/api/service/PersistedRunState.kt`
- Added engine API surface:
  - `EvoKammerEngineService.resumePersistedRun(config: RunConfig): String`
  - default implementation throws "not supported"
- Refactored `EvoKammerRunService`:
  - extracted shared `activateRunInfrastructure(...)`
  - fresh runs now persist a recovery snapshot after bootstrap
  - run loop now tracks persisted state:
    - `nextRoundIndex`
    - `activeRoundIndex`
    - status transitions (`BOOTSTRAPPED`, `RUNNING`, `PAUSED`, `COMPLETED`, `FAILED`, `ABORTED`)
  - added `resumePersistedRun(config)`
  - added legacy fallback inference when no persisted state file exists:
    - load sealed `StartingField` from meta-archive
    - infer last completed round from `ROUND_SUMMARY`
    - infer in-flight round from event/staging max generation
    - sanitize pending submissions and claimed WiP tasks for the replayed round
- Added DAO support used by replay sanitization:
  - `StagingDao.getPendingByGeneration(...)`
  - `StagingDao.getMaxGeneration(...)`
  - `WipTaskDao.releaseClaimsFromGeneration(...)`
  - `EventLogDao.getMaxGeneration(...)`
- Updated CLI pipeline recovery behavior:
  - completed stage summaries are reused instead of rerunning completed stages
  - unfinished persisted stage runs trigger `resumePersistedRun(...)`
- Added tests:
  - `evokammer/api/src/test/kotlin/com/achulkov/evokammer/api/service/EvoKammerRunServicePersistedResumeTest.kt`
  - `CliArgumentsTest.runCli resumes a persisted run when prior state exists but summary is missing`
- Added archive determinism hardening:
  - `RunArchiveService` now accepts an explicit requested `FinalOutputSpec` from the run service
  - completion bundling no longer depends solely on the persisted-state file to decide whether to
    recover a non-HTML generated output
  - `RunArchiveServiceTest` now verifies explicit JSON output recovery still works even when the
    persisted state file is absent
  - `EvoKammerRunService.getResultsBundle()` now prefers the live run's declared output spec, so
    caller-facing downloads keep the configured JSON artifact even if the persisted-state file is
    missing or stale
  - `EvoKammerRunServiceEndToEndFlowTest` now proves the service-layer bundle path ignores stale
    persisted output specs after completion
- Verification completed:
  - `./gradlew --no-daemon :evokammer:api:compileKotlin :evokammer:cli:compileKotlin`
  - `./gradlew --no-daemon :evokammer:api:test --tests com.achulkov.evokammer.api.service.EvoKammerRunServicePersistedResumeTest`
  - `./gradlew --no-daemon :evokammer:cli:test --tests com.achulkov.evokammer.cli.CliArgumentsTest.runCli\ resumes\ a\ persisted\ run\ when\ prior\ state\ exists\ but\ summary\ is\ missing`

## New Audit Findings
- The recent two-stage root run `adhd-live-pipeline-comparison-r1` completed successfully.
- The stage run `adhd-live-pipeline-comparison-r1-evidence-brief` is incomplete:
  - no `summary.json`
  - no `results.zip`
  - no `RUN_COMPLETED` archive marker
  - stage meta-archive `ROUND_SUMMARY` max generation is `2`
  - group event log max generation observed is `4`
  - this is exactly why replay should restart the in-flight round instead of starting a new run
- Implementation plan vs AGENTS.md are out of sync:
  - `documentation/EvoKammer_Implementation_Plan_v1.0.md` says effectively Phase 1–8 with partial 3/6
  - root `AGENTS.md` says Phases 0–9 implemented iterating and Phase 10 in progress

## Recovery Attempt Status
- A one-stage recovery pipeline config was added at:
  - `/Volumes/CrucialX9_1TB/AStudioProjects/EvoMolior/documentation/tmp/adhd_live_run/recovery-comparison-r1-pipeline.json`
- The first live recovery invocation was malformed:
  - command used single-quoted Gradle `--args='...'`
  - `$OPENROUTER_API_KEY` therefore did not expand
  - runtime evidence showed `401 Missing Authentication header`
- That malformed invocation is not a harmless no-op:
  - it resumed the real persisted stage run and has been mutating it
  - latest observed progress during this session reached at least rounds 6–7
  - repeated evidence in the live shell output showed:
    - no active agents remaining in-round
    - synthetic grader criteria being persisted as fallback
    - repeated `401 Missing Authentication header` failures for provider-backed calls
- Background shell session ID for that in-flight invocation: `1159`
- Do not reuse that exact quoting form.
- After that process exits, rerun recovery with shell expansion enabled, for example by using
  double quotes around the Gradle `--args=...` value or by inlining the concrete env value safely.

## Recovery Command To Re-run After The Bad Attempt Exits
- Same command shape, but with correct API key expansion:
  - run ID: `adhd-live-pipeline-comparison-r1`
  - output root: `/Volumes/CrucialX9_1TB/AStudioProjects/EvoMolior/documentation/tmp/adhd_live_run/output`
  - pipeline config:
    `/Volumes/CrucialX9_1TB/AStudioProjects/EvoMolior/documentation/tmp/adhd_live_run/recovery-comparison-r1-pipeline.json`
  - root prompt file:
    `/Volumes/CrucialX9_1TB/AStudioProjects/EvoMolior/documentation/tmp/adhd_live_run/output/adhd-live-pipeline-comparison-r1/prompt.txt`
  - input dir:
    `/Volumes/CrucialX9_1TB/AStudioProjects/EvoMolior/documentation/tmp/adhd_live_run/input`

## Files Touched In This Pass
- `documentation/stale/EvoKammer_Crash_Resume_Recovery_Plan.md`
- `documentation/tmp/adhd_live_run/recovery-comparison-r1-pipeline.json`
- `evokammer/substrate/src/main/kotlin/com/achulkov/evokammer/substrate/SubstrateDatabaseFactory.kt`
- `evokammer/substrate/src/main/kotlin/com/achulkov/evokammer/substrate/db/dao/StagingDao.kt`
- `evokammer/substrate/src/main/kotlin/com/achulkov/evokammer/substrate/db/dao/WipTaskDao.kt`
- `evokammer/substrate/src/main/kotlin/com/achulkov/evokammer/substrate/db/dao/EventLogDao.kt`
- `evokammer/api/src/main/kotlin/com/achulkov/evokammer/api/service/EvoKammerEngineService.kt`
- `evokammer/api/src/main/kotlin/com/achulkov/evokammer/api/service/PersistedRunState.kt`
- `evokammer/api/src/main/kotlin/com/achulkov/evokammer/api/service/EvoKammerRunService.kt`
- `evokammer/api/src/test/kotlin/com/achulkov/evokammer/api/service/EvoKammerRunServicePersistedResumeTest.kt`
- `evokammer/cli/src/main/kotlin/com/achulkov/evokammer/cli/CliPipeline.kt`
- `evokammer/cli/src/test/kotlin/com/achulkov/evokammer/cli/CliArgumentsTest.kt`

## What changed in this session
- Fast CLI run root-cause fix:
  - `:evokammer:cli` no longer sets `roundDurationMinutes = 0` in fast mode.
  - Verified consequence in a live rerun: primary worker agents no longer die in a few milliseconds with immediate `JobCancellationException`.
- Fast summary-path fix:
  - `spawnRoundSubmissionSummaries()` no longer hard-wires the Archivist to `DEFAULT_RUNTIME_RESEARCHER_MODEL`.
  - Verified consequence in a live rerun: the post-round summary Archivist used the active fast Archivist model (`openai/gpt-oss-120b`) and wrote a real `ROUND SUBMISSION SUMMARY` staging entry.
- Final article archive path:
  - `RunCompletionRecord` can carry `finalDeliverableHtml`, and `RunArchiveService` now prefers authored HTML over the scaffold fallback.
  - The summary prompt now instructs the Archivist to embed `FINAL DELIVERABLE HTML` when the objective asks for browser-renderable output.
  - `RunArchiveService` now extracts that embedded HTML block from the winner summary before falling back to the scaffold renderer.
- The first attempted dedicated post-run Builder-based article writer was removed from the active path.
  - It worked architecturally but caused new OpenRouter `402` failures under tight provider-credit limits.
  - The embedded-summary HTML path replaced it because it reuses an already-running agent instead of paying for another post-run model call.
- Shared no-progress handling is now consistent between the engine and desktop launcher.
- The automatic post-round criteria-review pass was removed from the production round loop.
- Live grader result and round-summary progress events were added:
  - `grader_judge_result`
  - `round_summary_result`
  - `final_answer`
- The post-run analysis screen now shows a durable archived final answer card.
- The run-completion archive marker now stores:
  - winner ID
  - final answer summary text
  - judge outputs by grader
  - submission summaries by group
- Focused regressions now cover both the durable completion archive and the post-run analysis parser.
- A new console runner is available as `:evokammer:cli`, with `-prompt` / `-promptfile`, recursive `-input`, `-output`, `-debug`, and repeated `-model ROLE=MODEL` support.
- The shared run-input artifact loader now lives in `evokammer:api` and is used by both the desktop file picker and the CLI.
- The production engine wiring was extracted into `evokammer:runtime` so the desktop app and CLI share the same runtime bootstrap.

## Normal-Run Boundary Fix
- Root cause discovered:
  - the normal CLI pipeline was unconditionally publishing and re-applying `outputs/improvements/code-improvement-plan.json`
  - that allowed self-improvement repair artifacts to leak into ordinary browser/article runs
- Boundary fix applied:
  - `CliPipelineConfig.applyCodeImprovementPlans` now defaults to `false`
  - code-improvement plan propagation only occurs when a pipeline config explicitly opts in
  - ordinary article runs no longer inherit repo-rewrite artifacts from the self-improvement path
- Verification added:
  - one regression now proves an explicit improvement pipeline still rewrites the next-stage repo copy
  - another regression proves a normal pipeline leaves the repo copy untouched and does not publish the repair artifact

## New Article Run Goal (2026-06-13)
- New active goal:
  - redo the browser-deliverable article generation run
  - use a larger staged pipeline with 4 total stages
  - keep 12 rounds per stage
  - use 4 groups per stage
  - use 4 agents per group
  - keep the run content-only; do not enable code-improvement propagation
- Locked run shape:
  - primary stage comes from `-promptfile documentation/tmp/fast_cli_variant6_prompt_high_bar.txt`
  - follow-up stages come from:
    - `documentation/tmp/fast_cli_variant6_stage2_prompt.txt`
    - `documentation/tmp/fast_cli_variant6_stage3_prompt.txt`
    - `documentation/tmp/fast_cli_variant6_stage4_prompt.txt`
  - pipeline config: `documentation/tmp/fast_cli_variant6_4stage_pipeline.json`
  - run id: `fast-cli-variant6-20260613-4stage`
- Expected artifacts:
  - `summary.json`
  - `pipeline-summary.json`
  - `pipeline-results.zip`
  - `results.zip`
  - `debug-log.txt`
  - `outputs/final-article.html`
  - `outputs/refined/final-article-refined.html`

## Stage-3 Abort Root Cause
- The larger 4-stage run reached stage 3 and then aborted during final deliverable publication.
- Root cause:
  - the final-deliverable reader in `EvoKammerRunService` only inspected the newest 150 `DOC`
    staging entries when looking for the authored final HTML file
  - on a 12-round, 4-group run that window is too shallow, so the canonical article can be missed
    even when the builder successfully writes it
- Fix applied:
  - widened the final deliverable scan window to 1000 staging entries so the HTML article can be
    recovered and published after high-volume stages
- Next step:
  - rerun the same 4-stage pipeline and verify that stage 3 now publishes `outputs/final-article.html`
    and that stage 4 can start normally

## Readiness Check Result
- Current assessment:
  - the self-improvement loop is operational, but it is not yet ready for an unattended long run
- Evidence:
  - large article runs can still fail during final-deliverable publication if the output scan window is too narrow
  - aborted stage reruns can hit sealed-starting-field / stale-stage-directory issues unless the stage workspace is reset
  - provider-side OpenRouter failures still abort individual builder attempts under load
  - long browser-deliverable runs still take hours and produce many warning-only grader paths
- Practical conclusion:
  - use the self-improvement loop for controlled runs, but keep a human in the loop for now
  - long autonomous runs need one more hardening pass around publication discovery, retry/resume hygiene, and provider fallback stability
- Revalidated during this pass:
  - final-deliverable discovery after 180 late-stage `DOC` entries is now covered by a regression
  - stale follow-up stage directories are now cleaned before rerun and covered by a regression

## New Long Self-Improvement Run Goal (2026-06-13)
- New active goal:
  - start a long recursive self-improvement loop against an isolated copy of the current project state
  - keep the loop on the overseer-cycle path
  - allow the loop to execute scheduled experiments rather than stopping after proposal/scheduling
  - target a multi-hour run with multiple overseer cycles
- Locked launch shape:
  - source repo: `/Volumes/CrucialX9_1TB/AStudioProjects/EvoMolior`
  - workspace root: `/tmp/evokammer-self-improve-longrun`
  - workspace name: `overseer-20260613`
  - mode: overseer cycle
  - execution enabled: yes
  - continue all requested cycles even if a cycle makes no progress: yes
  - cycle limit: 5
- Current expectation:
  - the loop should operate on the isolated workspace copy, not the live checkout
  - the run should be supervised during this initial launch window so we can verify progress and artifacts

## Files touched most recently
- `evokammer/api/src/main/kotlin/com/achulkov/evokammer/api/service/EvoKammerRunService.kt`
- `composeApp/src/jvmMain/kotlin/com/achulkov/evomolior/evokammer/viewmodel/EvoKammerViewModel.kt`
- `composeApp/src/jvmMain/kotlin/com/achulkov/evomolior/evokammer/screens/EvoKammerPostRunAnalysisScreen.kt`
- `composeApp/src/jvmMain/kotlin/com/achulkov/evomolior/evokammer/screens/EvoKammerDashboardScreen.kt`
- `composeApp/src/jvmMain/kotlin/com/achulkov/evomolior/evokammer/screens/EvoKammerLogViewerScreen.kt`
- `composeApp/src/jvmMain/kotlin/com/achulkov/evomolior/evokammer/LogExportUtils.kt`
- `evokammer/api/src/main/kotlin/com/achulkov/evokammer/api/service/EvoKammerEngineService.kt`

## Verification already completed
- `./gradlew --no-daemon :evokammer:api:test --tests com.achulkov.evokammer.api.service.RunArchiveServiceTest --tests com.achulkov.evokammer.api.service.RunCompletionRecordSerializationTest`
- `./gradlew --no-daemon --no-configuration-cache :evokammer:cli:test --tests com.achulkov.evokammer.cli.CliArgumentsTest.fast\ mode\ run\ uses\ a\ non-zero\ round\ duration`
- `./gradlew --no-daemon :evokammer:api:test --tests com.achulkov.evokammer.api.service.EvoKammerRunServiceEndToEndFlowTest`
- `./gradlew --no-daemon :evokammer:api:test --tests com.achulkov.evokammer.api.service.EvoKammerRunServiceGraderCriteriaReviewTest`
- `./gradlew --no-daemon :composeApp:jvmTest --tests com.achulkov.evomolior.evokammer.LogExportUtilsTest --tests com.achulkov.evomolior.evokammer.screens.EvoKammerDashboardScreenTest`
- `./gradlew --no-daemon :composeApp:jvmTest --tests com.achulkov.evomolior.evokammer.screens.EvoKammerPostRunAnalysisScreenTest --tests com.achulkov.evomolior.evokammer.LogExportUtilsTest --tests com.achulkov.evomolior.evokammer.screens.EvoKammerDashboardScreenTest`
- `./gradlew --no-daemon :composeApp:compileKotlinJvm`
- `./gradlew --no-daemon :evokammer:api:test --tests com.achulkov.evokammer.api.service.EvoKammerRunServiceEndToEndFlowTest`
- `./gradlew --no-daemon :evokammer:api:test --tests com.achulkov.evokammer.api.util.RunInputArtifactFilesTest :evokammer:cli:test --tests com.achulkov.evokammer.cli.CliArgumentsTest :composeApp:jvmTest --tests com.achulkov.evomolior.util.RunInputArtifactPickerTest`
- `./gradlew --no-daemon :evokammer:api:test --tests com.achulkov.evokammer.api.util.RunInputArtifactFilesTest :evokammer:cli:test --tests com.achulkov.evokammer.cli.CliArgumentsTest :composeApp:compileKotlinJvm`
- Live run evidence reviewed:
  - Broken reference run: `/tmp/evokammer-fast-output-MqmmzW/mindvortex-fast-20260602-220211`
  - Diagnostic rerun proving the timing/model fixes: `/tmp/evokammer-fast-verify-output-3wgPRl/mindvortex-fast-verify-20260602-224611`
  - Later rerun after credits degraded: `/tmp/evokammer-fast-verify-output-Ks0NWn/mindvortex-fast-verify-20260602-224936`
  - Final rerun after switching publication HTML onto the summary path: `/tmp/evokammer-fast-verify-output-LY1pa4/mindvortex-fast-verify-20260602-225836`
    - `results/final-article.html` is now a real standalone HTML article, not the scaffold fallback
    - source of truth was the promoted `PLAN_UPDATE` summary in `group-1` containing a `FINAL DELIVERABLE HTML` block
    - concrete proof:
      - the summary contains title `Unlock Your Creativity with MindVortex – The Ultimate Thought‑Mapping Platform`
      - the extracted `results/final-article.html` begins with the same standalone HTML document
    - the run still showed upstream `qwen`/Alibaba `502` tool-call formatting failures on some worker calls, but the output path completed
  - Latest hardening rerun after adding summary retry + sparse-substrate fallback instructions:
    - `/tmp/evokammer-fast-verify-output-roWm54/mindvortex-fast-verify-20260603-095106`
    - `results.zip -> outputs/final-article.html` and
      `.evokammer-data/evokammer/runs/mindvortex-fast-verify-20260603-095106/results/final-article.html`
      both contain the authored article `Unlock Your Mind’s Potential with MindVortex – The Ultimate Thought‑Flow Platform`
    - both `group-0` and `group-1` completion summaries contain `ROUND SUBMISSION SUMMARY`
      plus embedded `FINAL DELIVERABLE HTML`
    - concrete runtime proof:
      - `debug-log.txt` shows both Archivists calling `write_staging(...)` with `ROUND SUBMISSION SUMMARY`
      - `run_completed` archive JSON stores both embedded HTML summaries under `submissionSummariesByGroup`
    - remaining caveats during that same rerun:
      - both `qwen` Researcher agents still died on Alibaba/OpenRouter `502` tool-call-formatting failures after bounded retries
      - the RoundJudge still collapsed to the empty-assistant recovery string
      - neither caveat prevented correct final article publication

## Important remaining verification
- The fast MindVortex scenario has now been re-run successfully after the summary hardening.
  - confirmed: the winner summary contains both `ROUND SUBMISSION SUMMARY` and `FINAL DELIVERABLE HTML`
  - confirmed: `results/final-article.html` renders embedded authored HTML instead of the archive scaffold fallback in
    `/tmp/evokammer-fast-verify-output-roWm54/mindvortex-fast-verify-20260603-095106`
- Remaining follow-up is hardening, not root-cause fixing:
  - reduce the upstream `qwen`/Alibaba `502` tool-call-formatting failures that still appear in fast runs
  - reduce the RoundJudge empty-assistant path so grader output quality improves along with the final article path
- Rerun a full scenario and diff the resulting log against `documentation/logs/full_run_logs.txt`.
- Confirm the sequence around each round is now:
  - `grader_criteria_start`
  - `grader_judge_start`
  - `grader_judge_result`
  - `round_summary_result`
  - `final_answer`
- Confirm there is only one production grading pass per round.
- Confirm the archived final answer renders in the post-run analysis UI from the durable completion record.
- A system-property-gated auto replay hook was added to `composeApp` so a fresh run can be launched and auto-exited, but the replay log dump file still was not observed after the first packaged replay attempt. The next agent should verify whether the app actually reached `RunCompleted` and whether the log dump path is being written.

## Runtime note
- The original `/tmp` fast-run failure was not primarily a model-selection bug; it was a control-flow bug caused by zero-minute fast rounds plus a summary-agent model override bug.
- Provider-credit exhaustion became the limiting factor for later live reruns:
  - once credits fell low enough, OpenRouter started returning `402` broadly, making further provider-backed reruns non-diagnostic
  - do not treat those later `402`-dominated runs as regressions of the round-timing fix
- The current grader factory and GraderRunner defaults already point at `deepseek/deepseek-v4-flash`; the stale full-run log still showing `openai/gpt-oss-120b` looks like an older capture or an alternate launcher path, not the present default wiring.
- The compose desktop replay now accepts `-Devokammer.replay.enabled=true` and `-Devokammer.replay.logPath=...`; use that to capture a deterministic runtime artifact once the replay completion path is confirmed.

## Current Continuation Update (2026-06-06)
- Added a production-path restart regression that uses the real runtime service instead of the fake engine harness:
  - `evokammer/api/src/test/kotlin/com/achulkov/evokammer/api/service/EvoKammerRunServiceOverseerRestartRegressionTest.kt`
  - it proves Overseer hypotheses, experiment plans, dead ends, and the append-only audit trail survive a stop/restart cycle through `EvoKammerIpcServer` and `OverseerKernelClient`
- Mirrored Overseer audit entries into the project meta-archive as `OVERSEER_AUDIT` entries under the dedicated `__overseer_kernel__` archive run, so the control-plane history is now queryable through the same durable archive surface as other run evidence.
- Centralized the synthetic archive identifiers in `evokammer/core/src/main/kotlin/com/achulkov/evokammer/core/contract/OverseerKernelArchive.kt`.
- Exposed the archive mirror through a dedicated IPC route instead of leaking the synthetic run ID to callers:
  - `GET /overseer/audit/archive`
- Added a typed client convenience for the mirrored archive trail:
  - `OverseerKernelClient.getAuditArchiveEntries()`
- Cleaned up the multipart-upload deprecation noise in `EvoKammerIpcServer.kt` by isolating the upload read path behind a tiny suspend helper with a narrow suppression.
- Verified the updated API slice with:
  - `./gradlew --no-daemon -Pkotlin.incremental=false :evokammer:api:test --tests com.achulkov.evokammer.api.service.OverseerKernelServiceTest --tests com.achulkov.evokammer.api.service.OverseerKernelClientAndRestartRegressionTest --tests com.achulkov.evokammer.api.service.EvoKammerRunServiceOverseerRestartRegressionTest --tests com.achulkov.evokammer.api.service.EvoKammerIpcServerSmokeTest`
- The Overseer audit trail still remains the canonical append-only control-plane log, but it now has a durable archive mirror for evidence queries.
- Added a deterministic experiment-plan preflight surface, parallel to hypothesis preflight:
  - `evokammer/core/src/main/kotlin/com/achulkov/evokammer/core/contract/OverseerExperimentPlanEligibility.kt`
  - `OverseerKernel.validateExperimentPlanEligibility(...)`
  - `OverseerKernelService.preflightExperimentPlan(...)`
  - `POST /overseer/experiment-plans/preflight`
  - `OverseerKernelClient.preflightExperimentPlan(...)`
- Promoted experiment-plan registration into the lifecycle by marking the referenced hypothesis `UNDER_EXPERIMENT` and storing the linked `experimentPlanId`.
- Verified the new preflight contract with focused core/API tests:
  - `evokammer/core/src/test/kotlin/com/achulkov/evokammer/core/overseer/OverseerKernelTest.kt`
  - `evokammer/api/src/test/kotlin/com/achulkov/evokammer/api/service/OverseerKernelServiceTest.kt`
  - `evokammer/api/src/test/kotlin/com/achulkov/evokammer/api/service/OverseerKernelClientAndRestartRegressionTest.kt`
  - `evokammer/api/src/test/kotlin/com/achulkov/evokammer/api/service/EvoKammerIpcServerSmokeTest.kt`
- Verified the lifecycle transition with:
  - `kernel.registerExperimentPlan(...)` marking the hypothesis `UNDER_EXPERIMENT`

## Launch Hardening Follow-Up (2026-06-06)
- The operator-triggered Overseer launch path is now one-shot and restart-safe:
  - `OverseerExperimentSchedule.launchedAtMs` persists the consumption marker
  - repeat launches are rejected after the first successful launch
  - the fake IPC harness in `EvoKammerIpcServerSmokeTest` mirrors the durable launch contract
- The restart regression now proves launch consumption survives restart as well as hypothesis, plan, result, and dead-end state.
- Focused verification passed after the launch hardening:
  - `./gradlew --no-daemon -Pkotlin.incremental=false :evokammer:core:test --tests com.achulkov.evokammer.core.overseer.OverseerKernelTest :evokammer:substrate:test --tests com.achulkov.evokammer.substrate.OverseerKernelStateStoreTest :evokammer:api:test --tests com.achulkov.evokammer.api.service.OverseerKernelServiceTest --tests com.achulkov.evokammer.api.service.EvoKammerIpcServerSmokeTest --tests com.achulkov.evokammer.api.service.OverseerKernelClientAndRestartRegressionTest`

## Durable History Follow-Up (2026-06-06)
- Added explicit durable history surfaces to the Overseer snapshot for experiment-plan and dead-end evolution:
  - `OverseerKernelState.experimentPlanHistory`
  - `OverseerKernelState.deadEndHistory`
- Wired the kernel to append history entries on registration, scheduling, launch, experiment results, and direct dead-end records.
- Exposed the new history surfaces through:
  - `OverseerKernelService.getExperimentPlanHistory(...)`
  - `OverseerKernelService.getDeadEndHistory(...)`
  - `EvoKammerEngineService.getOverseerExperimentPlanHistory(...)`
  - `EvoKammerEngineService.getOverseerDeadEndHistory(...)`
  - `POST /overseer/experiment-plans/{experimentPlanId}/history`
  - `GET /overseer/dead-ends/{hypothesisId}/history`
  - `OverseerKernelClient.getExperimentPlanHistory(...)`
  - `OverseerKernelClient.getDeadEndHistory(...)`
- Verified the history layer with focused regressions:
  - `OverseerKernelTest`
  - `OverseerKernelStateStoreTest`
  - `OverseerKernelServiceTest`
  - `EvoKammerIpcServerSmokeTest`
  - `OverseerKernelClientAndRestartRegressionTest`

## Goal Evidence Matrix
| Goal item | Evidence |
|---|---|
| Deliverable Skeleton Tree verified | `documentation/DeliverableSkeletonTree_EvoKammer.md` was reviewed, and the live implementation/test surface remains covered by `evokammer/api/src/test/kotlin/com/achulkov/evokammer/api/service/DeliverableSkeletonTreeCompatibilityTest.kt`, `DeliverableSkeletonTreeStoreTest.kt`, and the existing runtime wiring in `EvoKammerRunService`. |
| Stale docs quarantined | `documentation/stale/README.md` is present and the stale plans/handoffs are already isolated under `documentation/stale/` (for example `EvoKammer_Continuation_Plan.md`, `EvoKammer_Crash_Resume_Recovery_Plan.md`, `EvoKammer_Remediation_Plan.md`). |
| Next RSCI slice identified | `documentation/EvoKammer_Recursive_Self_Improvement_Plan_v4.md` remains the active plan, and the next concrete follow-up is the broader archive/event mirroring or a split into dedicated registry stores if the snapshot-backed history proves too coarse. |
| New history layer round-tripped | `evokammer/core/src/main/kotlin/com/achulkov/evokammer/core/contract/OverseerModels.kt`, `evokammer/core/src/main/kotlin/com/achulkov/evokammer/core/overseer/OverseerKernel.kt`, `evokammer/substrate/src/test/kotlin/com/achulkov/evokammer/substrate/OverseerKernelStateStoreTest.kt`, `evokammer/api/src/test/kotlin/com/achulkov/evokammer/api/service/OverseerKernelServiceTest.kt`, `evokammer/api/src/test/kotlin/com/achulkov/evokammer/api/service/EvoKammerIpcServerSmokeTest.kt`, and `evokammer/api/src/test/kotlin/com/achulkov/evokammer/api/service/OverseerKernelClientAndRestartRegressionTest.kt`. |

## Next RSCI Slice
- Continue from `documentation/EvoKammer_Recursive_Self_Improvement_Plan_v4.md` with the first concrete follow-up beyond the current snapshot-backed history layer:
  - broaden archive/event mirroring if cross-run review needs more durable evidence, or
  - split experiment-plan / dead-end history into dedicated registry stores if the snapshot remains too coarse.
- Keep the slice deterministic and testable:
  - preserve the current contract shape,
  - add only durable persistence/query helpers,
  - verify with focused round-trip and IPC tests,
  - avoid any LLM agent loop or model-set/constitutional changes.

## Goal Audit
- `-fast` uses only the approved model IDs: verified by `CliArgumentsTest.fast mode only accepts the approved model set` and the live run `modelOverrides` in `summary.json`.
- The CLI writes all run logs into the output folder: verified by the live fast runs producing `debug-log.txt` under the per-run output directory.
- The live fast run uses 5 rounds and a MindVortex HTML5-article prompt: verified by `summary.json` from `mindvortex-fast-smoke-fixed`.
- The run produces both a standalone article file and a bundled archive copy: verified by `/tmp/evokammer-fast-smoke-vRAWXd/mindvortex-fast-smoke-fixed/.evokammer-data/evokammer/runs/mindvortex-fast-smoke-fixed/results/final-article.html` and `results.zip` containing `outputs/final-article.html`.
- The post-run fix plan captures the remaining issues: verified by `documentation/stale/EvoKammer_Fast_Run_Fix_Plan.md`.

## New Active Goal
- Investigate the latest `/tmp` fast-run grader behavior and fix the grading architecture.
- Current evidence from `/private/tmp/evokammer-fast-verify-output-roWm54/mindvortex-fast-verify-20260603-095106/debug-log.txt`:
  - only one grader group ran
  - criteria agents did not leave durable staged criteria
  - judging logged `no criteria found for grader-0 round=0`
  - RoundJudge collapsed to the empty-assistant recovery path and submitted zero scores
- Implementation direction for the next pass is captured in:
  - `documentation/stale/EvoKammer_Grader_Civilizations_Fix_Plan.md`

## Latest Grader Verification
- Dedicated grader hardening verification run completed:
  - Run ID: `mindvortex-fast-grader-fix-20260603-134740`
  - Output directory: `/tmp/evokammer-fast-grader-fix-output-jmsZwG/mindvortex-fast-grader-fix-20260603-134740`
  - Mode: `-fast -rounds 10`
  - Outcome: `COMPLETED`
- Concrete runtime changes versus the earlier broken grader run:
  - `graderGroups=3` instead of 1
  - `persisted synthetic criteria` appeared 53 times, preventing empty criteria context
  - `no criteria found` appeared 0 times
  - `RoundJudge spawning for round` appeared 60 times, reflecting per-target scoring attempts by all three grader groups
  - `collected 1 scores from RoundJudge` now appears in the log, so the judge path is no longer exclusively zero-score
- Remaining caveat:
  - many RoundJudge invocations still collect zero scores, so sealed-suite/substrate scoring remains the dominant scoring path even though the grader-rubric architecture is now much stronger

## Known caveat
- A combined `:evokammer:api:test ... :evokammer:cli:test ...` Gradle invocation intermittently appeared to stall at `:evokammer:cli:test`, even though the isolated CLI test and the isolated API test both passed. Treat that as a Gradle/JUnit shutdown quirk until reproduced with a minimal test case.
- The last API/Compose verification hit intermittent Kotlin incremental compilation cache errors, but each targeted build/test finished successfully after fallback compilation.

## Crash-Resume Recovery Closeout (2026-06-05)
- Persisted crash-resume support now exists for real process-death recovery:
  - `EvoKammerEngineService.resumePersistedRun(config)`
  - filesystem-backed persisted run state under
    `evokammer/runs/<runId>/control/persisted-run-state.json`
  - CLI stage execution now reuses completed stages and resumes unfinished persisted runs.
- The old stage run `adhd-live-pipeline-comparison-r1-evidence-brief` was resumed and completed
  under the same run ID, but the malformed no-auth replay caused the dedicated final-deliverable
  writer to fail three times with `401 Missing Authentication header`.
- Because of that failure, the completion archive had no generated output file and the old archive
  layer incorrectly published a fallback `final-article.html` even though the configured stage
  output was JSON.
- Recovery was still possible from persisted stage summaries:
  - `group-5` promoted `PLAN_UPDATE`
    `a4253d01-ef11-444f-9d8b-db2e48d48203`
    contains a full fenced `EVIDENCE BRIEF JSON` block
  - `group-3` and `group-4` also contained valid JSON brief candidates
- The recovered operator-facing JSON brief has been materialized at:
  - `/Volumes/CrucialX9_1TB/AStudioProjects/EvoMolior/documentation/tmp/adhd_live_run/output/adhd-live-pipeline-comparison-r1/outputs/briefs/evidence-brief.json`
  - `/Volumes/CrucialX9_1TB/AStudioProjects/EvoMolior/documentation/tmp/adhd_live_run/output/adhd-live-pipeline-comparison-r1/stages/evidence-brief/.evokammer-data/evokammer/runs/adhd-live-pipeline-comparison-r1-evidence-brief/results/briefs/evidence-brief.json`
- Both stage bundles were updated to include `outputs/briefs/evidence-brief.json` and no longer
  expose a top-level `outputs/final-article.html` for this JSON stage:
  - stage wrapper bundle:
    `/Volumes/CrucialX9_1TB/AStudioProjects/EvoMolior/documentation/tmp/adhd_live_run/output/adhd-live-pipeline-comparison-r1/stages/evidence-brief/results.zip`
  - persisted run bundle:
    `/Volumes/CrucialX9_1TB/AStudioProjects/EvoMolior/documentation/tmp/adhd_live_run/output/adhd-live-pipeline-comparison-r1/stages/evidence-brief/.evokammer-data/evokammer/runs/adhd-live-pipeline-comparison-r1-evidence-brief/results/results.zip`
- Archive hardening landed in code:
  - `RunArchiveService` now recovers configured non-HTML outputs from archived round summaries
    when a final writer failed
  - `RunArchiveService` now writes generated/recovered outputs into the run `results/` directory
  - `RunArchiveServiceTest` covers the JSON-stage recovery path
- Verification completed for this closeout:
  - `./gradlew --no-daemon :evokammer:api:compileKotlin`
  - `./gradlew --no-daemon :evokammer:api:test --tests com.achulkov.evokammer.api.service.RunArchiveServiceTest`
- Phase-status conclusion for user-facing reporting:
  - `documentation/EvoKammer_Implementation_Plan_v1.0.md` still estimates
    **Phase 1–8 implemented, Phase 3/6 partial, Phases 9–10 not started**
  - root `AGENTS.md` says **Phases 0–9 implemented iterating, Phase 10 in progress**
  - based on the live runtime shape and the amount of hardening still being done, the codebase is
    best described as **late integration / production-hardening work in progress**, but the plan
    document and AGENTS status are materially out of sync.

## Phase 9/10 Continuation (2026-06-05)
- New broader goal after crash-resume closeout:
  - reconcile implementation-plan phase status with `AGENTS.md` without editing the plan
  - fix earlier-stage gaps where found
  - continue Phase 9 and Phase 10 work
  - do not start new runs yet
  - produce a separate review-only file with exact CLI run targets
- Durable planning docs created:
  - `/Volumes/CrucialX9_1TB/AStudioProjects/EvoMolior/documentation/EvoKammer_Phase9_10_Execution_Plan.md`
  - `/Volumes/CrucialX9_1TB/AStudioProjects/EvoMolior/documentation/EvoKammer_Run_Targets_For_Review.md`
- Root `AGENTS.md` has been reconciled conservatively:
  - Phase 3 -> `Partial`
  - Phase 6 -> `Partial`
  - Phase 9 -> `Partial`
  - Phase 10 -> `In progress`
  - rationale is now embedded directly below the phase table
- Concrete Phase 9 runtime gap fixed:
  - `delegate_sub_task` is now actually wired into the runtime agent tool registry for eligible
    top-level agents in
    `evokammer/agents/src/main/kotlin/com/achulkov/evokammer/agents/factory/EvoKammerAgentFactory.kt`
  - it is intentionally disabled for sub-agents to preserve one-level delegation
- Focused verification passed:
  - `./gradlew --no-daemon -Pkotlin.incremental=false :evokammer:agents:test --tests com.achulkov.evokammer.agents.factory.EvoKammerAgentFactoryTest --tests com.achulkov.evokammer.agents.runner.DeathNoteProtocolTest`
- Regression fixed while verifying that slice:
  - `DeathNoteProtocolTest.FakeStagingDao` now implements the new `StagingDao` methods added by
    the persisted-resume work
- Important locked finding for the next agent:
  - `NestedGroupRunner` / `NestedGroupConfig` now have a narrow production call path through
    MetaPlanner-triggered stasis handling in `EvoKammerRunService`
  - `LifecycleManager` now tracks nested jobs and cancels them from `terminateAll()`
  - `NestedGroupRunner` KG extraction bug is fixed; it now reads from
    `knowledgeGraphFactDao()`
  - outer-group rollup now exists
- Recommended next code slice:
  - strengthen real-run verification of the stasis-triggered nested path once runs are allowed
  - then re-audit whether Phase 9 can remain `Partial` or is still blocked by runtime validation

## Phase 9 Nested Rollup Follow-up (2026-06-05 later)
- Nested hierarchy is now stronger than a control-plane scaffold:
  - `LifecycleManager.spawnNestedGroup(...)` now imports nested KB, KG, and tool outputs back
    into the outer group substrate before `onCompleted(...)` fires.
  - imported entities get fresh outer IDs and outer `(runId, groupId)` ownership.
  - KG facts keep their prior provenance and append a nested-source marker of the form
    `nested://<nestedRunId>/<nestedGroupId>/kg/<factId>`.
  - imported tools now merge into the outer tool version chain when a same-name tool already
    exists, preventing ambiguous latest-version lookups inside the parent registry.
  - `EventTypes.NESTED_GROUP_ROLLUP` now records the rollup boundary in the event log.
  - a narrow production trigger now exists: when MetaPlanner stasis handling sees multiple open
    WiP tasks, `EvoKammerRunService` can spawn one nested inner instance for that outer group.
- New/updated files in this pass:
  - `evokammer/orchestration/src/main/kotlin/com/achulkov/evokammer/orchestration/lifecycle/LifecycleManager.kt`
  - `evokammer/orchestration/src/main/kotlin/com/achulkov/evokammer/orchestration/nested/NestedGroupResult.kt`
  - `evokammer/api/src/main/kotlin/com/achulkov/evokammer/api/service/NestedHierarchySpawnPlanner.kt`
  - `evokammer/api/src/main/kotlin/com/achulkov/evokammer/api/service/EvoKammerRunService.kt`
  - `evokammer/substrate/src/main/kotlin/com/achulkov/evokammer/substrate/db/entity/EventLogEntryEntity.kt`
  - `evokammer/substrate/src/main/kotlin/com/achulkov/evokammer/substrate/db/dao/WipTaskDao.kt`
  - `evokammer/orchestration/src/test/kotlin/com/achulkov/evokammer/orchestration/lifecycle/LifecycleManagerTest.kt`
  - `evokammer/orchestration/src/test/kotlin/com/achulkov/evokammer/orchestration/nested/NestedGroupRunnerTest.kt`
  - `evokammer/api/src/test/kotlin/com/achulkov/evokammer/api/service/NestedHierarchySpawnPlannerTest.kt`

## Deliverable Skeleton Tree Pass (2026-06-05)
- New active goal:
  - implement the Deliverable Skeleton Tree spec from `documentation/DeliverableSkeletonTree_ EvoKammer.md`
  - keep the implementation file-backed and non-destructive to persisted runs
- Durable design decision:
  - the tree store lives in `evokammer:substrate` rather than `evokammer:api` so orchestration can
    read/update it without crossing module boundaries
  - the tree is persisted per group at
    `evokammer/runs/<runId>/control/groups/<groupId>/deliverable-skeleton-tree.json`
- Core contract changes landed:
  - `StartingField.deliverableSkeletonTreeJson`
  - `ScoringDimension.dstNodeRef`
  - `GradingSheet.RubricDimension.dstNodeRef`
  - `GroupCheckinResult.deliverableNodeUpdates`
  - new core tree model in `core/contract/DeliverableSkeletonTree.kt`
- Runtime wiring landed:
  - Genesis Council synthesis now rescues `deliverableSkeletonTreeJson`
  - `EvoKammerRunService` seeds WIP tasks from the deliverable tree when available
  - `CriticPipeline` updates the tree on promoted submissions via the WIP task ref
  - `EvolutionarySelector` now accepts deliverable-tree summary pressure
  - `ScoringEngine` emits synthesized deliverable node updates from verdicts
  - `RunArchiveService` archives tree JSON and includes tree exports in `results.zip`
- Files added/updated in this pass:
  - `evokammer/core/src/main/kotlin/com/achulkov/evokammer/core/contract/DeliverableSkeletonTree.kt`
  - `evokammer/substrate/src/main/kotlin/com/achulkov/evokammer/substrate/DeliverableSkeletonTreeStore.kt`
  - `evokammer/api/src/main/kotlin/com/achulkov/evokammer/api/service/EvoKammerRunService.kt`
  - `evokammer/orchestration/src/main/kotlin/com/achulkov/evokammer/orchestration/lifecycle/CriticPipeline.kt`
  - `evokammer/orchestration/src/main/kotlin/com/achulkov/evokammer/orchestration/selector/EvolutionarySelector.kt`
  - `evokammer/competition/src/main/kotlin/com/achulkov/evokammer/competition/grader/GradingSheet.kt`
  - `evokammer/competition/src/main/kotlin/com/achulkov/evokammer/competition/grader/MetaJudge.kt`
  - `evokammer/competition/src/main/kotlin/com/achulkov/evokammer/competition/scoring/ScoringEngine.kt`
  - `evokammer/api/src/main/kotlin/com/achulkov/evokammer/api/service/RunArchiveService.kt`
  - `evokammer/api/src/main/kotlin/com/achulkov/evokammer/api/service/SealedTestSuiteEvaluator.kt`
  - `evokammer/agents/src/main/kotlin/com/achulkov/evokammer/agents/factory/CouncilRolePrompts.kt`
  - `evokammer/agents/src/main/kotlin/com/achulkov/evokammer/agents/factory/RolePromptBuilder.kt`
  - `evokammer/orchestration/src/test/kotlin/com/achulkov/evokammer/orchestration/selector/EvolutionarySelectorDeliverableTreeTest.kt`
  - `evokammer/api/src/test/kotlin/com/achulkov/evokammer/api/service/DeliverableSkeletonTreeStoreTest.kt`
- Verification completed:
  - `./gradlew --no-daemon :evokammer:core:compileKotlin :evokammer:competition:compileKotlin :evokammer:orchestration:compileKotlin :evokammer:api:compileKotlin`
  - `./gradlew --no-daemon :evokammer:orchestration:test --tests com.achulkov.evokammer.orchestration.selector.EvolutionarySelectorDeliverableTreeTest :evokammer:api:test --tests com.achulkov.evokammer.api.service.DeliverableSkeletonTreeStoreTest --tests com.achulkov.evokammer.api.service.RunArchiveServiceTest`
  - `./gradlew --no-daemon --rerun-tasks :evokammer:api:test --tests com.achulkov.evokammer.api.service.RunArchiveServiceTest`
  - `./gradlew --no-daemon :evokammer:competition:compileKotlin :evokammer:competition:test --tests com.achulkov.evokammer.competition.ScoringPipelineIntegrationTest --tests com.achulkov.evokammer.competition.grader.MetaJudgeTest`
- Live-run blocker for the next agent:
  - `OPENROUTER_API_KEY` is not set in the current shell, so the review-only run targets in
    `documentation/EvoKammer_Run_Targets_For_Review.md` cannot be executed yet
  - the next meaningful live step is to supply that key and run Target 1 or Target 2
- Phase 10 hardening added in this pass:
  - `BlindSubmissionGate` now emits a `sealAuditEvents` stream carrying `ScoreSealAudit`
  - `ScoringPipelineIntegrationTest` now verifies the seal audit is emitted only when the gate closes
- Backward-compatibility regression added:
  - `DeliverableSkeletonTreeCompatibilityTest` deserializes an older `StartingField` payload that
    omits `deliverableSkeletonTreeJson` and confirms the `nodeGraph` fallback still works
- Focused verification now passed:
  - `./gradlew --no-daemon -Pkotlin.incremental=false :evokammer:orchestration:test --tests com.achulkov.evokammer.orchestration.lifecycle.LifecycleManagerTest --tests com.achulkov.evokammer.orchestration.nested.NestedGroupRunnerTest`
  - `./gradlew --no-daemon -Pkotlin.incremental=false :evokammer:api:test --tests com.achulkov.evokammer.api.service.NestedHierarchySpawnPlannerTest --tests com.achulkov.evokammer.api.service.EvoKammerRunServiceEndToEndFlowTest`
- What is still missing:
  - the new production trigger has not yet been exercised in a user-approved real run
  - deterministic nested completion is now implemented and regression-covered, but the hierarchy
    path still needs a user-approved live run for end-to-end proof
  - because of that remaining runtime gap, Phase 9 should remain `Partial` in `AGENTS.md`
- Recommended next slice:
  - verify the stasis-triggered nested path during a real approved run
  - rerun the Phase 9 status audit only after that runtime verification lands

## Latest Continuation Note (2026-06-05 latest)
- The first narrow production trigger for hierarchy is now in code:
  - `EvoKammerRunService` calls nested execution from the MetaPlanner stasis path
  - trigger planning was extracted into
    `evokammer/api/src/main/kotlin/com/achulkov/evokammer/api/service/NestedHierarchySpawnPlanner.kt`
    so the decomposition policy is testable without a full live run
- New focused verification passed:
  - `./gradlew --no-daemon -Pkotlin.incremental=false :evokammer:api:test --tests com.achulkov.evokammer.api.service.NestedHierarchySpawnPlannerTest --tests com.achulkov.evokammer.api.service.EvoKammerRunServiceEndToEndFlowTest`
- Root status file was re-audited:
  - `AGENTS.md` still keeps Phase 9 at `Partial`
  - rationale now correctly says a narrow production trigger exists, while real-task validation is
    still missing even though deterministic nested completion is now covered by code and tests
- Best next slice under the user's current no-run constraint:
  - move to Phase 10 code-side hardening that does not require execution of new runs
  - strongest candidates remain:
    - explicit score-seal audit harness around the blind gate
    - replay/log export verification path in desktop replay
    - stronger runtime invariant tests around round event ordering

## Latest Phase 10 Hardening (2026-06-05 latest+1)
- Added a one-round event-ordering regression in
  `evokammer/api/src/test/kotlin/com/achulkov/evokammer/api/service/EvoKammerRunServiceEndToEndFlowTest.kt`
  that asserts the canonical order:
  - `RoundStarted`
  - `round_summary_result`
  - `RoundCompleted`
  - `final_answer`
  - `RunCompleted`
- That test surfaced a real duplicate `RoundCompleted` emission bug.
  - Root cause: `EvoKammerRunService` emitted `RoundCompleted` once inside
    `publishBlindRoundResult(...)` and again in the outer round loop.
  - Fix: removed the duplicate outer emission and redundant summary score update from the round
    loop; the blind-publish boundary remains the single source of the round-completion event.
- Verification now passed:
  - `./gradlew --no-daemon -Pkotlin.incremental=false :evokammer:api:test --tests com.achulkov.evokammer.api.service.BlindTournamentServiceTest --tests com.achulkov.evokammer.api.service.NestedHierarchySpawnPlannerTest --tests com.achulkov.evokammer.api.service.EvoKammerRunServiceEndToEndFlowTest`
- Additional blind-gate hardening also landed:
  - `BlindTournamentServiceTest` now verifies failure telemetry is forwarded after reveal and that
    the service cannot be started twice without an intervening stop.
- Best next no-run slice after this:
  - add an explicit score-seal audit harness around the blind gate itself
  - or validate desktop replay/log-export invariants end-to-end without starting external model runs

## Overseer Experiment Result Lifecycle (2026-06-06)
- Added deterministic experiment-result recording across the Overseer kernel, durable service
  façade, IPC bridge, and typed client:
  - `OverseerKernel.recordExperimentResult(...)`
  - `OverseerKernelService.recordExperimentResultWithAudit(...)`
  - `EvoKammerEngineService.recordOverseerExperimentResult(...)`
  - `POST /overseer/experiment-results`
  - `OverseerKernelClient.recordExperimentResult(...)`
- Extended the kernel state snapshot with an `experimentResults` registry so experiment outcomes
  survive restart alongside hypotheses, experiment plans, and dead ends.
- Kept the lifecycle deterministic and explicit:
  - `ACCEPTED` promotes the hypothesis to `ACCEPTED`
  - `REJECTED` promotes to `REJECTED`
  - `REJECTED` with counterexample paths promotes to `DEAD_END` and stores a dead-end record
- Updated the smoke harness and restart regression to assert the new result path end-to-end.
- Verification completed:
  - `./gradlew --no-daemon -Pkotlin.incremental=false :evokammer:core:test --tests com.achulkov.evokammer.core.overseer.OverseerKernelTest :evokammer:api:test --tests com.achulkov.evokammer.api.service.OverseerKernelServiceTest --tests com.achulkov.evokammer.api.service.OverseerKernelClientAndRestartRegressionTest --tests com.achulkov.evokammer.api.service.EvoKammerIpcServerSmokeTest`
  - `./gradlew --no-daemon -Pkotlin.incremental=false :evokammer:api:test --tests com.achulkov.evokammer.api.service.OverseerKernelServiceTest --tests com.achulkov.evokammer.api.service.OverseerKernelClientAndRestartRegressionTest --tests com.achulkov.evokammer.api.service.EvoKammerIpcServerSmokeTest`
- Next slice from `documentation/EvoKammer_Recursive_Self_Improvement_Plan_v4.md`:
  - execution scheduling for eligible experiment plans, not more validation plumbing
  - keep the constitutional core/model set immutable while the runtime orchestration grows


## Overseer Experiment Scheduling (2026-06-06)
- Added deterministic launch-intent scheduling for eligible Overseer experiment plans.
- The kernel now stores pure `OverseerExperimentSchedule` records in the durable Overseer snapshot.
- Scheduling is explicit and restart-safe:
  - only formalized or under-experiment hypotheses are considered
  - the schedule stores derived deterministic run IDs
  - scheduling is idempotent when called again on the same state
- Wired the schedule path through the durable service façade, IPC bridge, typed client, and runtime service.
- Added IPC coverage for `POST /overseer/experiment-plans/schedule` and the persisted `experimentSchedules` state.
- Verified with:
  - `./gradlew --no-daemon -Pkotlin.incremental=false :evokammer:api:test --tests com.achulkov.evokammer.api.service.OverseerKernelServiceTest`
  - `./gradlew --no-daemon -Pkotlin.incremental=false :evokammer:core:test --tests com.achulkov.evokammer.core.overseer.OverseerKernelTest :evokammer:api:test --tests com.achulkov.evokammer.api.service.OverseerKernelServiceTest --tests com.achulkov.evokammer.api.service.OverseerKernelClientAndRestartRegressionTest --tests com.achulkov.evokammer.api.service.EvoKammerIpcServerSmokeTest :evokammer:substrate:test --tests com.achulkov.evokammer.substrate.OverseerKernelStateStoreTest`
- Next slice from `documentation/EvoKammer_Recursive_Self_Improvement_Plan_v4.md`:
  - execution orchestration that consumes schedules and launches real self-improvement runs
  - keep the kernel deterministic; the launcher can be dumb, but the schedule contract should stay stable


## Overseer Experiment Launch (2026-06-06)
- Added an explicit operator-triggered launch path for scheduled Overseer experiment runs.
- The launch route is intentionally not autonomous:
  - the operator supplies the concrete `RunConfig`
  - the launch request is validated against the stored schedule
  - the engine then calls the existing `startRun(...)` path
- Wired the launch path through:
  - `EvoKammerEngineService.launchOverseerExperimentRun(...)`
  - `EvoKammerRunService.launchOverseerExperimentRun(...)`
  - `POST /overseer/experiment-plans/{experimentPlanId}/launch`
  - `OverseerKernelClient.launchExperimentRun(...)`
- Added smoke coverage proving the scheduled run id reaches the engine's `startRun(...)` call.
- Verified with:
  - `./gradlew --no-daemon -Pkotlin.incremental=false :evokammer:api:test --tests com.achulkov.evokammer.api.service.EvoKammerIpcServerSmokeTest --tests com.achulkov.evokammer.api.service.OverseerKernelServiceTest --tests com.achulkov.evokammer.api.service.OverseerKernelClientAndRestartRegressionTest`
- Next slice from `documentation/EvoKammer_Recursive_Self_Improvement_Plan_v4.md`:
  - execution hardening and launch semantics, especially how launch requests should be constrained or versioned once real experiment bundles are in use

## Live Overseer Execution Proof (2026-06-12)
- Live execution root:
  - `/tmp/evokammer-overseer-live-2/cycle-001`
- Explicit execution-mode rerun:
  - `/tmp/evokammer-overseer-live-3/cycle-001`
- Forced 3-cycle rerun with explicit no-stop flag:
  - `/tmp/evokammer-overseer-live-5/cycle-001`
- The overseer cycle now executes scheduled bootstrap plans through the production runtime:
  - the empty-kernel fallback seeded `HYP-self-improve-bootstrap-001`
  - it also seeded `PLAN-self-improve-bootstrap-001`
  - the production runtime launched `PLAN-self-improve-bootstrap-001-run-01`
  - the kernel recorded the result as `ACCEPTED`
- Persistent proof artifacts:
  - overseer kernel state: `/tmp/evokammer-overseer-live-2/cycle-001/.evokammer-data/evokammer/overseer/overseer-kernel-state.json`
  - overseer audit log: `/tmp/evokammer-overseer-live-2/cycle-001/.evokammer-data/evokammer/overseer/overseer-kernel-audit.jsonl`
  - generated patch bundle: `/tmp/evokammer-overseer-live-2/cycle-001/.evokammer-data/evokammer/runs/PLAN-self-improve-bootstrap-001-run-01/results/self-improvement-patch-bundle.json`
- Result shape:
  - explicit execution rerun: `experimentResults=1`
  - forced 3-cycle rerun: `experimentResults=[0, 1, 1]` across three cycles
  - forced 3-cycle kernel state:
    - hypotheses: `HYP-001`, `HYP-002`
    - plans: `EXP-001`, `EXP-002`
    - results: both `EXP-001` and `EXP-002` recorded as `ACCEPTED`
- The generated patch bundle was a real JSON change artifact with `summary`, `changedFiles`, `documentation`, `verification`, and `notes`, not a prose-only report.
