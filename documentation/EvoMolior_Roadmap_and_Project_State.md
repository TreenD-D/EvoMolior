# EvoMolior Roadmap and Project State

**Status:** Canonical current roadmap, implementation-state, and architecture authority.

**Evidence cut:** 2026-07-20.

This is the sole current source of truth for EvoMolior's roadmap phase status, connected
production architecture, known limitations, and next actions. The implementation plan defines the
intended phase behavior and exit criteria; dated plans, audits, goal logs, and run reports are
historical evidence. When another document conflicts with this file about current state, this file
wins.

## 1. How Status Is Determined

Evidence is ranked in this order:

1. A normal production entry-point call graph.
2. Persisted state or an observable downstream behavior change.
3. Focused integration tests.
4. Accepted realistic run evidence.
5. Unit-tested implementation or scaffolding.
6. Specifications and historical plans.

A class, DI binding, prompt, or isolated unit test does not prove production connectivity.
Component availability and roadmap exit-condition verification are reported separately.

| Status | Meaning |
|---|---|
| `Implemented` | The required behavior is connected in production and has focused verification. |
| `Partial` | Material behavior exists, but a required behavior or authority edge is absent. |
| `Disconnected` | An implementation exists, but normal production execution does not invoke it. |
| `Unverified` | A production path exists, but the roadmap exit condition lacks adequate evidence. |
| `Not Implemented` | The required behavior is absent. |
| `Working With Caveat` | The behavior is connected and useful but intentionally differs from the original plan or has a material limitation. |

## 2. Current Production Architecture

Normal CLI, benchmark, self-improvement, and desktop composition build an
`EvoMoliorRunService` through `createProductionRuntime()`. Ordinary agents currently run through
the `LifecycleManager` in-process branch because those entry points supply `localAgentRunner`.
Shuru VM execution exists as an alternate lifecycle branch, but normal production entry points do
not select it.

```text
objective + input artifacts
        |
        v
Genesis Council -> sealed StartingField
        |
        v
seeded legacy node projections -> sealed, versioned NodeAtlas authority
        |                                   |
        |                                   +-> append-only execution state and projections
        v
EvoMoliorRunService
        |
        +-> run-global control/atlas Room database
        +-> group-local Room/SQLite substrates
        +-> LifecycleManager -> in-process role agents -> role-gated tools
        |                           |                       |
        |                           |                       +-> ToolSubSandbox subprocesses
        |                           +-> staging -> CriticPipeline -> KB/KG/tools/WiP
        +-> Inspector/Watchdog signals -> orchestration actions
        +-> group coordination -> leaks, dispatches, disclosure, capture map
        +-> grader runner -> blind scoring -> MetaJudge -> round feedback
        +-> final synthesis -> publication contract -> run archive
```

### Connected Authority Edges

- The objective and artifacts feed the Genesis Council, which produces the sealed
  `StartingField`.
- Before worker activation, seeded deliverable, WIP, resource, and disclosure records compile into
  one sealed `NodeAtlas`. The run-global Atlas database is the transaction authority for ownership
  and disclosure state; legacy stores are hash-bound compatibility projections.
- The selector and lifecycle manager create role agents with bounded budget, task, stage, and
  substrate context.
- Each compiled `spawnGeneration` freezes one claim queue from its Atlas portfolio, so an early
  worker exit cannot release a node for a surplus peer in the same spawn pass.
- Agent writes enter staging; the Critic pipeline decides promotion, rejection, or arbitration.
- Grading reads sealed deliverables and influences feedback, scoring, and future work.
- Council resource-node lineage plus node-specific grades determines territorial ownership and
  later budget yield. Completion order is not ownership authority.
- Inspector and Watchdog signals are consumed by run orchestration.
- Disclosure/check-in results gate stage progression and influence leak pressure.
- Sustained stasis can trigger one nested group whose KB/KG/tool results roll into its parent.
- Explicit continuation routing can load selected prior-run context from the run graph/archive.
- Final synthesis is checked for structural and provenance requirements before publication and
  archiving.
- Ordinary CLI runs without fixed `-rounds` use bounded convergence authority: an explicit
  `--max-rounds` ceiling and two or three consecutive fully accepted rounds for the same winner.
  Fixed-round and configured pipeline stages remain fixed.
- Completion archives attest the exact persisted run state, verify manifest projections against
  independent StartingField/archive authority, and apply one meaningful run-level ZIP timestamp.

### Important Runtime Boundaries

- Group separation is concretely enforced through separate substrate databases and controlled
  leak/dispatch/scoring routes.
- Tool execution uses `ToolSubSandbox`, a bounded child-process boundary.
- Source tools use approval, allowlist, sanitization, and grounding paths.
- Native Koog tool calls pass through `ConstitutionalEnforcer`; side-effecting tools add typed
  staging, process, message, and network metadata before mutation. Decisions are append-only
  substrate events.
- `FetchSourceTool` composes run-approved hosts with `AllowlistProxy` role restrictions at the
  outbound boundary. Automatic redirects are disabled so an unreviewed destination cannot receive
  a request.
- Shuru VM management is implemented but is not the default or selected production agent boundary.
- Blind scoring now runs in a run-scoped child JVM bound to authenticated loopback transport.
  `BlindSubmissionGate`, `MetaJudge`, and `ScoringEngine` state remain private to that worker.
- The desktop's normal JVM entry point uses the real in-process service. Ktor IPC is implemented
  and tested but is not the default desktop composition path. The authenticated remote dashboard
  is a separate CLI service.
- Codex process calls retain a 30-minute default. Only explicit persisted
  `APPENDIX_B_ACCEPTANCE` Council purpose may select a 45-minute per-call bound; the existing
  overall Codex Genesis Council envelope remains 45 minutes and cancels active child processes.
  The rejected v63 run reached this total envelope during strict compiler repair, proving cleanup
  but producing no compiled field or runtime society. Current Genesis repair contexts now narrow
  only Synthesis-only compiler cycles to the complete prior Synthesis object, exact typed errors,
  related node contracts, and canonical generated schemas; any upstream/shared repair retains all
  seven current dependencies. Synthesis provider execution additionally requires exact
  Task-derived test/node and promotion/general authority. Promotion fixtures can use only the
  object-only fixture branch, every authority key must occur exactly once, and no static
  Synthesis schema or codec fallback remains.

## 3. Roadmap Status

The phase definitions and original exit conditions come from
`EvoMolior_Implementation_Plan_v1.0.md`.

| Phase | Component Status | Exit Condition | Evidence and Missing Connection | Next Action |
|---|---|---|---|---|
| 0. Foundation | `Implemented` | `Met` with deterministic integration evidence | The supported in-process lifecycle spawns through `LifecycleManager`, constructs the production agent factory and budget ledger, reads canonical KB, writes staging, executes a materialized child-process tool, writes a promoted Death Note, and records spawn/death events. Constitutional interception and fail-closed outbound source policy remain connected; Shuru is optional. | Retain the acceptance test in the broad API suite. |
| 1. Single group, single generation | `Implemented` | `Met` with deterministic integration evidence | A generation-1 Critic authority pass promotes a reviewed document into canonical KB, validates and materializes a reviewed tool into the active registry, and promotes a Death Note as an explicit Dead End in the same isolated group substrate. | Retain the acceptance test in the broad orchestration suite. |
| 2. Multi-generation compounding | `Implemented` | `Met` with deterministic integration evidence | Ten sequential lifecycle generations run under the connected in-process manager. Generation 3 promotes a canonical KB artifact; a Generation 10 agent retrieves it through the production KB tool, increments its citation count, writes a derived canonical document naming the source ID, and preserves the same lineage in its Death Note. | Retain the long-horizon acceptance test in the broad API suite. |
| 3. Knowledge Graph | `Implemented` | `Met` with deterministic integration evidence | Across ten generations, the lifecycle-bound Critic validates and promotes exactly 50 typed facts, rejects ten staged hard-constraint violations before model review, and leaves zero forbidden relations in canonical KG. A Generation 10 fact carries provenance to a Generation 1 canonical fact. | Retain the KG acceptance test in the broad orchestration suite. |
| 4. Multi-group competition | `Implemented` | `Met` with deterministic integration evidence | Three isolated group databases complete five lifecycle generations each with matching spawn/death logs and no private-canon cross-contamination. A formal dispatch queued at Generation 0 remains absent through Generation 1 and reaches topology neighbours at Generation 2. Best-grade Council-node capture remains deterministically covered; model-backed recapture stays in the bounded post-v7 diagnostic scope. | Retain the competition acceptance test and exercise grade-authoritative recapture in a bounded realistic run. |
| 5. Differential privacy leaks | `Implemented` | `Met` for the original ten-leak audit | Leak delivery fails closed without a transformer or when transformation fails. A retained ten-consecutive-leak audit exercises the production coordinator, mandatory transform, Leak Oracle quotas, and delivery consumer; every delivered packet is checked against explicit source group, agent, path, and raw-sentence trace markers. | Include a larger adversarial privacy sample in Phase 10 hardening. |
| 6. Grader groups and blind scoring | `Implemented` | `Met` with deterministic integration evidence | Isolated grader DBs, production-connected criteria Critic review, adaptive grader selection, generation-scoped WiP, Legacy extraction/injection/expiry, constitutional grader tool interception, typed grader-authored sheets and automated tests, generation-bounded authored-sheet history, promoted grader-local tool listing/execution, and grader KB/KG reuse in later-generation criteria and RoundJudge prompts are connected. The run-scoped child JVM owns the blind gate, MetaJudge, and scoring engine; startup, transport, invalid response, and process-death paths fail closed without local scoring fallback. | Retain Phase 7's separate measurable GradingSheet-improvement criterion; Phase 6 itself is closed. |
| 7. Progressive disclosure | `Implemented` | `Met` with deterministic integration evidence | Two groups complete two check-ins through the production grader and disclosure authorities. The passing group advances twice while the failing group remains gated with elevated leak pressure. Check-in-1 failure telemetry is anonymized into the grader substrate, appears in generation-2 criteria prompts, and causes the GraderBuilder to publish a richer promoted methodology whose structural quality is strictly higher under MetaJudge's own deterministic arbitration metric. Recipient-specific leak cadence remains post-v7 and deterministically tested only. | Retain the acceptance test and include the curriculum in the bounded post-v7 realistic validation. |
| 8. Genesis Council | `Implemented` | `Met` with bounded provider-backed evidence | The production-equivalent eight-role Council pipeline generated three valid, pairwise structurally distinct StartingFields from three raw objectives with no intervention using Codex/ChatGPT `gpt-5.6-luna`. Each field contained three groups, eight role prompts, eight model assignments, typed resource nodes, staged disclosures, and a non-empty consistency report with no unresolved conflict. The diagnostic also exposed and fixed Council objective elaboration overriding the raw operator objective; production now preserves the exact raw objective. If typed resource-node decoding fails, production preserves the otherwise valid field, retains each individually valid node, rejects malformed entries, and records those rejections in the consistency report. | Retain the opt-in three-objective diagnostic and its evidence record. |
| 9. Hierarchical civilizations | `Implemented`; one nested level | `Met` with deterministic integration evidence | One-level `delegate_sub_task` and stasis-triggered nested groups are connected. Nested jobs are published before execution and generation barriers wait for child groups. A retained acceptance scenario runs an inner civilization that produces a document and executable tool, rolls both into the outer substrate with imported IDs, executes the imported materialized tool in `ToolSubSandbox`, and composes both exact outer artifact references and their contents into the final deliverable. | Retain one-level hierarchy as the accepted scope; arbitrary recursive depth is not required. |
| 10. Integration and hardening | `Implemented` | `Met` for the accepted bounded release scope | Deterministic chaos kills 10% of a live generation while preserving all committed staging rows and cleanly completing survivors. The 50-leak source-trace and 100-check-in score-seal audits pass. The retained per-generation budget policy is validated by generation-budget arithmetic, Watchdog pressure telemetry, non-pausing service response, and multi-round replenishment tests. A fresh current-code three-round Codex run completed with two worker groups, three grader civilizations, three authoritative verdicts and blind seals, six stage advances, five resource claims, two final deliverables, archive completion, zero run failures, and zero constitutional violations. Existing matched FRAMES and LitQA2 slices provide small same-task society-over-single-agent gains; broad benchmark superiority is explicitly not claimed. | Retain the deterministic audits, bounded current-code run, and benchmark comparisons as regression baselines. |

### Competitive Progressive-Disclosure Program

The program defined by
`EvoMolior_Competitive_Progressive_Disclosure_Action_Plan-1.html` extends the closed historical
phase baseline. Its increments are tracked separately so earlier phase labels do not imply the new
authority model is complete.

| Increment | Status | Evidence and Boundary | Next Action |
|---|---|---|---|
| 0. Historical evidence freeze | `Implemented` | Deterministic score replay, Inspector causality, contribution-sensitive fitness, KG relation canonicalization, and one-run manifest integrity regressions pass. | Retain the regressions. |
| 1. Authoritative Node Atlas | `Implemented` | Exact `ReceiverContract`, deterministic sealed Atlas and payload hashes, append-only versions/amendments/execution state, schema-v7 persistence, typed legacy migration, transaction-only ownership/disclosure mutation, new-run bootstrap, persisted recovery, and non-destructive v6-to-v7 migration are covered. Full core, substrate, competition, and API suites pass sequentially on JDK 17. | Retain the authority regressions. |
| 2. Council field compiler | `Implemented` | The runtime-bound `ExecutionEnvelope`, eight strict typed role outputs, deterministic node/Atlas/group IDs, staged selective retries, structured consistency reporting, authoritative Incentive Designer policy, exact receiver/Atlas compilation, redacted worker and isolated grader fields, hidden-test commitments, material-revision validation, and shared worker/grader seal are connected. Production fails closed before activation on missing/invalid Council output or persistence/seal/Atlas mismatch. Typed archive readback and resume reload the worker, grader, envelope, report, receiver, and exact Atlas; tampering fails closed. Full core, agents, orchestration, substrate, competition, and API suites pass sequentially on JDK 17; runtime and Compose JVM composition compile. | Retain compiler and seal regressions. |
| 3. Redacted mortal worker execution | `Implemented` | Hash-bound WorkerViews, deterministic portfolios, Atlas-authoritative leases, schema-v8 contribution/degraded/TTL/exit records, ordinary boot-context redaction, `commit_contribution`, degraded fallback separation, lease finalization, no-contribution release, and useful-exit telemetry are connected. Focused acceptance and full core, agents, orchestration, substrate, competition, and API suites pass sequentially on JDK 17; runtime and Compose JVM compile. | Retain worker-redaction and contribution regressions. |
| 4. Exact node evidence, capture, and yields | `Implemented` | Critic-promoted typed-cost work seals into hash-bound evidence packets. Independent RoundJudge responses form exact fail-closed node bundles; capture and recapture use only valid node grades. Accepted decisions produce bounded next-round schedules, hash-complete claim events, and causal spend/artifact/score lineage. Focused authority and production acceptance pass; the full API suite passes after the strengthened Council fixtures, and the preceding broad run passed all other engine suites plus runtime and Compose compilation. | Retain exact capture and reward-lineage regressions. |
| 5. Competitive Grader Atlas | `Implemented` | A separate Council-sealed Grader Atlas now governs redacted frontier views, deterministic competitive leases, typed requirements and hashed hidden executors, immutable worker-snapshot criteria freezes, exact per-grader/target bundles, contested critical failures, and fail-closed quorum. Exact replay also crosses the scoring child JVM. Production persists requirement-discovery and judgment-quality fitness, reconstructs grader Legacy only from eligible requirement hashes, and derives structured provider/family independence labels including `LOW_SINGLE_FAMILY`. Focused authority/tamper/cardinality/fitness/independence tests and the full API suite pass; the broad engine suites pass. | Retain exact grader authority. |
| 6. Mandatory progressive disclosure | `Implemented` | Exact mandatory-node coverage, node thresholds, evidence coverage, integration readiness, valid exact grader verdicts, and unresolved-hard-failure status now determine each group's stage independently. Group-local reveals bind the sealed stage payload and exact delivered cards into append-only Atlas lineage; transaction and WorkerView replay reject tampering. Invalid/abstained evaluations do not escalate leaks, compiled workers receive no uncommitted stage prose, and worker feedback is linted to current-stage revealed limitations. Focused tamper/independence/redaction/replay coverage and full engine suites pass on JDK 17; runtime and Compose JVM compile. | Retain exact disclosure authority. |
| 7. Outlier shadow nodes and amendments | `Implemented` | Outlier-only, WorkerView-bound proposals now flow through revealed/canon duplicate checks, sealed hidden-map anticipation, isolated exact grader assessments, and append-only shadow lifecycle. Later agents receive group-local redacted shadow cards and bind independent evidence through typed contribution references. Delayed lineage and fitness precede immutable next-round Atlas amendments with added execution state and refreshed disclosure/WIP/resource projections. Focused authority, migration, tool, grader, WorkerView, and production-round tests pass; full engine suites pass on JDK 17 and runtime/Compose compile. | Retain shadow/amendment authority. |
| 8. Tool and simulation compounding | `Implemented` | Council-sealed typed opportunities compile into executable shell, Python, or closed-AST DSL projects. Promotion requires exact schemas/tests and real bounded sandbox execution. Runtime emits immutable execution/simulation records, automatic contribution/evidence lineage, evidence-bound later-node reuse, delayed causal fitness, exact metrics, and immutable maintained/forked/retired version history. Grader production rejects unbound promotion and primordial tools remain archived projections. Focused acceptance and full engine suites pass on JDK 17; runtime and Compose JVM compile. | Retain tool and simulation authority. |
| 9. Causal fitness, cost, publication, and observability | `Implemented` | Schema v18 and the contribution graph bind exact Critic, canonical, evidence, grade, capture, integration, final-reference, receiver-score, Control, and Summary authority. Worker, Review, assessed Control, and validated Summary fitness remain plane-separated. Run-global provider/tool cost reservations fail closed on unknown capped cost and active publication reservations. Immutable final candidates enter the strict hash-linked publication sequence through Death Note and quiescence gates. Manifest v2 self-verifies Atlas, grader, contribution, publication, event, database/WAL, output, and final-reference exports. Read-only dashboards preserve unavailable zero denominators. Focused authority coverage and the full engine suites pass on JDK 17; runtime and Compose JVM compile. | Retain causal publication authority. |
| 10. Matched comparative benchmark harness | `Implemented` | Immutable experiment authority executes explicit iterative-single-agent, one-group, and full-society arms over the same frozen sources, task tools, sealed Atlas, output contract, GPT worker family, deterministic evaluator, seeds, and iso-latency policy. The accepted one-task/two-seed report in `results/increment10-gpt-matched-20260712-v3/` retains all six cells, exact runtime commitments, disjoint namespaces, society final-reference hashes, failures, and unavailable cost/confidence values. The two complete pairs tied at quality `1.0`; one-group and full society each failed one seed, so no superiority claim is supported. | v68 is permanently rejected. Its promotion-wire root repair, clean independent re-review, expanded preflight, non-cached nine-module suite, and Section 18.1 pass on current bytes. v69 is statically launch-eligible; accept only a complete audited Appendix B archive before reconciling Section 18.2/18.3 metrics and claims. |

## 4. Explicit Feature Gap Matrix

### Disconnected

| Feature | Current Reality | Required Closure |
|---|---|---|
No accepted feature is currently disconnected in this category. Shuru remains an optional stronger
runtime branch; supported in-process execution uses the connected constitutional, group-database,
source-egress, and tool-subprocess boundaries.

### Partial or Not Working as Originally Intended

| Feature | Current Reality | Missing Behavior |
|---|---|---|
| Global budget ceiling | The retained policy is per-generation spawning. `GroupSummary.remainingBudget` is active-agent balance, not cumulative spend. | The original cumulative run-cost ceiling is not implemented. This is currently an explicit product decision, not a hidden bug. |
| Critic lifecycle | `LifecycleBoundCriticPipeline` provides stable identity, finite budget, TTL, and a promoted Death Note around the fail-closed Critic authority. | Accepted as an equivalent connected authority boundary; no separate ordinary role-agent process is required. |
| KG-Critic authority | KG validation and model-backed conflict arbitration run inside the lifecycle-bound Critic authority. | Accepted as equivalent to a separate KG-Critic loop; Phase 3 acceptance is closed. |
| Grader-authored tests | GraderBuilders publish typed regex tests; promoted history is generation-bounded and executed during judging without exposing future drafts. | The Phase 7 acceptance now proves failure telemetry drives a structurally improved generation-2 sheet under MetaJudge's connected quality metric. |
| MetaJudge authority | Consensus and weighted arbitration work deterministically over sealed grader sheets. | Accepted as an equivalent authority boundary; no standalone MetaJudge agent is required. |
| Hierarchical execution | One-level delegation and stasis-triggered nested groups are connected and accepted. Inner documents and tools are rolled up, executed, and composed into the outer final deliverable. | Recursive depth beyond one nested level remains intentionally unsupported. |
| Cross-task learning | Explicit continuation/run-graph routing works. | New Genesis runs do not automatically learn from arbitrary prior tasks. |
| Scoring isolation | A run-scoped child JVM owns blind-gate, MetaJudge, and scoring-engine state behind authenticated loopback JSON. | Phase 6 and the 100-check-in Phase 10 seal audit are closed. The child now binds an ephemeral port atomically, publishes readiness after bind, and returns bounded stdout/stderr diagnostics on startup failure. |
| Atlas projection migration | The sealed Atlas is authoritative for versioning, WIP, leases, evidence, typed grading, capture, ownership, and disclosure. Existing group WIP, deliverable-tree, and resource rows remain hash/commit-bound compatibility projections. | Removing the compatibility projections is future cleanup, not an acceptance blocker. |

### Accepted Evidence Boundaries

These are limits on the claims supported by the retained evidence, not open phase-exit conditions.

| Feature | Accepted Evidence and Claim Boundary |
|---|---|
| Grade-authoritative territorial capture | Deterministic acceptance proves best-grade ownership and recapture. The current-code realistic run exercised typed Council resource nodes and emitted five claims; it is retained as bounded model-backed evidence, not exhaustive capture-quality validation. |
| Leak privacy hardening | Phase 5's ten-leak audit and Phase 10's 50-leak source-trace audit are accepted. Universal semantic anonymity against adversarial models is not claimed. |
| Progressive curriculum | Deterministic production-connected acceptance proves telemetry-driven methodology improvement. The current-code realistic run exercises the surrounding grader and disclosure loop; no claim is made that every provider will improve every methodology. |
| Latest Genesis contracts | Three provider-backed objectives produced rich typed resource nodes and distinct valid StartingFields. Provider compliance beyond that bounded sample is not claimed. |
| Nested hierarchy | Deterministically accepted for outer plus one inner civilization, including useful document/tool consumption. |
| Score sealing | The 100-check-in audit passes with no pre-close reveal or seal-audit emission. |
| Society superiority | Small matched FRAMES and LitQA2 slices show measurable society gains; research-synthesis comparisons remain ties at materially higher cost, so broad superiority is not claimed. |
| Latest publication/authority fixes | A current-code single-stage, three-round Codex code-mode run completed, used scoped staging/KG tools, and published the contracted audit plan. |
| Competitive progressive-disclosure closure | Increments 0-10 remain accepted and the short Step 14 representative archive passed on current post-v72 bytes. The full Step 15 v4 attempt completed Genesis Council and all six worker/grader/judging rounds on exact `CODEX_CHATGPT:gpt-5.6-luna` with no fallback, but aborted after four final-writer attempts failed to publish a sealed-test-valid receiver file. It produced no normal archive, manifest, accepted Section 18.2 metrics, or winner. The user closed the goal under a reduced presentation-readiness scope and explicitly skipped Step 16. Strict full Appendix-B acceptance, the new matched comparison, and broad society-superiority claims remain unaccepted. |

### Working With Caveats

- Per-generation spawning is the current budget policy; cumulative global-spend enforcement is not
  implied.
- Publication contracts enforce shape, required sections, provenance, and thresholds. They do not
  establish factual truth.
- Claim-support validation intentionally skips when no claim ledger exists.
- Legacy expiry records with blank failure maps remain a compatibility edge case.
- The normal desktop path is connected directly to the service; Ktor IPC remains an available
  implementation rather than the default composition.
- Accepted v7 is historical pre-latest-fix evidence, not validation of subsequent changes.
- Codex/ChatGPT uses Codex code mode in an isolated temporary workspace with an agent-scoped
  filesystem RPC bridge to typed EvoMolior tools. Current evidence proves staging/KG tool use,
  constitutional interception, grading, substrate accumulation, and single-stage explicit-file
  publication. It does not prove Koog-native function calling, promoted `execute_tool` selection,
  or native web-search grounding.
- Runs without an explicit `FinalOutputSpec` publish an archive-backed inspection article. Use an
  explicit output contract when an operator-facing authored artifact is required.
- The final CLI suite and clean independent re-review pass with identical pre/post source
  fingerprints. The canonical nine-module suite, augmented Section 18.1 matrix, Compose JVM
  compilation, and static/document reconciliation also pass on those final CLI-security bytes.
- v72 consumed that clean short-run authorization and failed closed before workers: the private
  grader suite contained seven `GENERAL` tests whose exact definition was JSON `null`. The
  commitments were correct, but compiler-valid JSON was not evaluator-executable. No provider run
  is currently authorized.
- The macOS internal data volume can become too full for `/var/folders` test fixtures. Canonical
  static gates isolate JVM/native temporary files on the repository volume; the environmental
  no-space attempt is not passing evidence.

## 5. Roadmap Priorities

1. **Retain deterministic phase acceptance.** Event delivery, nested lifecycle quiescence, and
   useful two-level composition are stable in their broad module suites.
2. **Retain Genesis acceptance.** Phase 8 is closed by the bounded three-objective provider-backed
   diagnostic and preserved evidence record.
3. **Retain progressive grader validation.** Phase 6 grader civilization, blind scoring, and the
   Phase 7 telemetry-driven methodology improvement are closed with deterministic evidence.
4. **Retain post-v7 authority evidence.** The current-code three-round diagnostic covers Council
   typed resource nodes, best-grade capture, disclosure cadence, and publication contracts.
5. **Retain Phase 10 evidence.** Deterministic chaos, 50-leak privacy, 100-check-in score sealing,
   retained budget-policy validation, matched benchmark gains, and the fresh bounded current-code
   run form the accepted release baseline.
6. **Preserve claim discipline.** Treat narrow same-task benchmark gains as satisfying the Phase
   10 measurable-improvement exit condition without claiming broad research-synthesis superiority.
7. **Complete the competitive progressive-disclosure acceptance boundary.** Increments 0-10 remain
   accepted. v63-v72 are permanently rejected. v70 proved that the
   transported root still exposed generic promotion values even though an unreachable duplicate
   definition held the exact Task-derived branches. The post-v70 repair binds exact authority to
   the actual root, removes Koog's invalid provider-only `$id`, prunes unreachable definitions,
   validates exact response bytes independently under Draft 2020-12, and makes schema-bearing
   Codex calls tool-free. Failure evidence is owner-only, bounded, atomically published, and
   crash-reclaimed without changing strict decode or selective repair. Independent continuation
   `23b33769-4525-4695-938e-dd16dc9b4bb3` was clean, and focused verification, expanded preflight,
   the non-cached rerun serial nine-module suite, and augmented Section 18.1 passed before v71.
   v71 consumed that authorization and was intentionally stopped after repeated `tests=0/0`; it
   must never be resumed or reclassified. Current runtime-usability repairs and the final
   authorized CLI observability/security slice pass focused verification and clean independent
   review. The canonical nine-module suite, augmented Section 18.1, Compose JVM compilation, and
   static/document gates passed on those final bytes and were consumed by v72. v72 failed startup
   preflight at `0/7` executable tests because `GENERAL` test generation still has no shared typed
   contract with the evaluator. The next work is contract design and deterministic repair, not
   another provider run. A conjunctively accepted Appendix B archive must still precede
   accepted-run Section 18.2/18.3 reconciliation, final claims, and goal closure.

## 6. Evidence Baseline

Recent authoritative evidence:

- `agent-notes/phase8-genesis-council-live-acceptance-20260711.json`
- `agent-notes/phase10-current-code-codex-audit-20260711.json`
- `agent-notes/roadmap-gpt-backend-validation-report-20260711.md`
- `agent-notes/private-intelligence-v7-architecture-audit-and-remediation-20260710.md`
- `agent-notes/private-intelligence-v7-architecture-audit-and-remediation-20260710.json`
- `agent-notes/private-intelligence-run-deep-dive-20260709.md`
- `benchmarks/EvoMolior_Benchmark_Ladder.md`
- `build/appendix-b-v63-focused-council-repair-20260717.log`
- `build/appendix-b-v63-focused-council-repair-preflight-authority-20260717.log`
- `build/appendix-b-v63-focused-council-repair-full-engine-suite-20260717.log`
- `build/section18-1-contract-security-matrix-focused-council-repair-20260717.log`
- `build/appendix-b-v63-focused-council-repair-independent-rereview-20260717.json`
- `build/appendix-b-realistic-gpt-20260717-v63-runtime.log`
- `agent-notes/appendix-b-v63-genesis-council-envelope-timeout-rejection.md`
- `build/appendix-b-realistic-gpt-20260717-v64-runtime.log`
- `agent-notes/appendix-b-v64-focused-synthesis-envelope-timeout-rejection.md`
- `build/appendix-b-realistic-gpt-20260717-v65-runtime.log`
- `agent-notes/appendix-b-v65-compiled-council-readback-rejection.md`
- `build/appendix-b-v65-startup-readback-audit-20260717-123845/`
- `build/appendix-b-v65-purpose-readback-repair-focused-v2-20260717.log`
- `build/appendix-b-v65-purpose-readback-repair-independent-review-20260717.txt`
- `build/appendix-b-v65-purpose-readback-repair-preflight-authority-v2-20260717.log`
- `build/appendix-b-v65-purpose-readback-repair-full-engine-suite-20260717.log`
- `build/section18-1-contract-security-matrix-v65-purpose-readback-repair-20260717.log`
- `build/appendix-b-realistic-gpt-20260717-v66-runtime.log`
- `agent-notes/appendix-b-v66-genesis-fixture-cardinality-rejection.md`
- `build/appendix-b-v66-paired-fixture-diagnostics-focused-v4-20260717.log`
- `build/appendix-b-v66-paired-fixture-diagnostics-independent-rereview-20260717.txt`
- `build/appendix-b-v66-paired-fixture-diagnostics-preflight-authority-20260717.log`
- `build/appendix-b-v66-paired-fixture-diagnostics-full-engine-suite-20260717.log`
- `build/section18-1-contract-security-matrix-v66-paired-fixture-diagnostics-20260717.log`
- `build/appendix-b-realistic-gpt-20260717-v67-runtime.log`
- `agent-notes/appendix-b-v67-genesis-schema-json-rejection.md`
- `build/appendix-b-v67-schema-json-repair-focused-v8-20260717.log`
- `build/appendix-b-v67-schema-json-repair-independent-rereview-20260717.txt`
- `build/appendix-b-v67-schema-json-repair-preflight-authority-20260717.log`
- `build/appendix-b-v67-schema-json-repair-full-engine-suite-20260717.log`
- `build/section18-1-contract-security-matrix-v67-schema-json-repair-20260717.log`
- `build/appendix-b-realistic-gpt-20260717-v68-runtime.log`
- `agent-notes/appendix-b-v68-promotion-test-non-object-rejection.md`
- `build/appendix-b-v68-promotion-wire-repair-focused-v5-20260717.log`
- `build/appendix-b-v68-promotion-wire-repair-independent-rereview-v2-20260717.txt`
- `build/appendix-b-v68-promotion-wire-repair-preflight-authority-v2-20260717.log`
- `build/appendix-b-v68-promotion-wire-repair-full-engine-suite-v5-20260717.log`
- `build/section18-1-contract-security-matrix-v68-promotion-wire-repair-20260717.log`
- `build/appendix-b-realistic-gpt-20260717-v69-runtime.log`
- `agent-notes/appendix-b-v69-promotion-fixture-wrong-root-rejection.md`
- `build/appendix-b-v69-promotion-schema-authority-focused-v5-20260717.log`
- `build/appendix-b-v69-promotion-schema-authority-independent-rereview-20260717.txt`
- `build/appendix-b-v69-promotion-schema-authority-preflight-authority-v2-20260717.log`
- `build/appendix-b-v69-promotion-schema-authority-full-engine-suite-20260717.log`
- `build/section18-1-contract-security-matrix-v69-promotion-schema-authority-20260717.log`
- `build/appendix-b-realistic-gpt-20260718-v70-runtime.log`
- `agent-notes/appendix-b-v70-promotion-schema-provider-enforcement-rejection.md`
- `agent-notes/appendix-b-v70-schema-root-transport-repair.md`
- `build/appendix-b-v71-schema-transport-independent-rereview-20260718.txt`
- `build/appendix-b-v71-schema-transport-focused-postreview-20260718.log`
- `build/appendix-b-v71-schema-transport-preflight-authority-20260718.log`
- `build/appendix-b-v71-schema-transport-full-engine-suite-v2-20260718.log`
- `build/section18-1-contract-security-matrix-v71-schema-transport-20260718.log`
- Production code and focused tests under `evomolior/`.

The earlier closure baseline passed the complete agents, API, orchestration, competition, and CLI
module suites on JDK 17 and exercised runtime through compilation and the retained current-code
three-round diagnostic. The July 17 baseline below extends current-byte static coverage to all
nine engine modules, including runtime tests. Neither baseline is a claim about unrelated
application or platform test graphs.

The Increment 1 Atlas baseline additionally passed complete core, substrate, competition, and API
suites sequentially on JDK 17. `EvoMoliorDatabaseMigrationTest` verifies the checked-in v6 schema
upgrades to v7 without destructive fallback, retains legacy WIP lifecycle data, and creates every
Atlas authority table.

The Increment 2 compiler baseline passed complete core, agents, orchestration, substrate,
competition, and API suites sequentially on JDK 17. Runtime and Compose JVM production composition
also compile. `CompiledCouncilBootstrapAcceptanceTest` covers fail-before-activation invalid
compilation, exact typed persistence/readback, authoritative Atlas equality, shared-seal
verification, compiled resume restoration, and tamper rejection.

The July 17 competitive progressive-disclosure static baseline passed the acceptance-purpose
timeout/recovery/archive/auto-completion work, the post-v63 focused Council repair, and the v65
purpose-readback root repair. The v65 repair persists and seals non-default Council execution
purpose while preserving the historical default-standard representation; resume-purpose mismatch
fails before activation and readback diagnostics are artifact-specific. Focused tests,
independent review, expanded authority preflight, the fresh clean serial nine-module suite, and
the augmented current-byte Section 18.1 matrix all pass.

The historical v63-v67 runs remain rejected. v63 and v64 were cancelled by the unchanged
45-minute total Genesis Council envelope. v65 compiled and sealed the fields, then failed the
now-repaired execution-purpose readback boundary before runtime activation. v66 exhausted the
unchanged five-cycle Genesis repair budget on alternating exact fixed-array cardinality failures
in the sealed promotion fixture and produced no field or runtime authority. The v66 source defect
is now repaired: typed count-exact failures preserve legacy codes, both fixture components report
in one pass, valid counterparts are frozen, and literal-v66 regressions plus all broad gates pass.
v67 then exhausted all five full-dependency repairs on opaque executable schemas and malformed
promotion-test JSON. Its root repair excludes opaque executable JSON, uses tagged provider test
JSON while preserving persisted bytes, aligns strict decode requirements, and refreshes downstream
repair authority. Focused verification, independent re-review, expanded preflight, the clean
nine-module suite, and Section 18.1 pass. v68 subsequently exhausted five Synthesis repairs on
two promotion-test definitions that decoded as valid non-object JSON values. v68 is permanently
rejected. Its root repair now derives exact wire authority from Task output, restricts promotion
fixtures to the object-only branch, requires every authority key exactly once, removes static
Synthesis fallbacks, and preserves persisted domain bytes. Focused verification and clean review
pass. The current-byte preflight, non-cached nine-module suite, and Section 18.1 matrix also pass.
v69 then reached the exact promotion object branch but exhausted all five Synthesis cycles with
both fixture components using roots that did not match three Task-authored schema pairs. v69 is
permanently rejected. Its root repair now machine-binds exact schema groups at provider generation,
uses fixed closed object maps and optional wrappers, and performs lossless strict provider-wire
normalization before the unchanged domain serializer. Focused verification and clean review pass;
the current-byte expanded preflight, non-cached nine-module suite, and augmented Section 18.1
matrix also pass. v70 exercised the repaired bytes but every Synthesis response still retained the
same six wrong-root failures. Strict decode rejected them, yet the provider transport did not
enforce the nested roots as assumed. v70 is permanently rejected. Diagnosis then found that the
exact branches lived only in an unreachable duplicate definition while the transported root
remained generic. The current repair binds the root itself, removes the invalid `$id`, prunes
unreachable definitions, isolates schema calls from tools/user config, and independently validates
returned bytes. At that pre-execution point, clean review and every then-current gate passed and
v71 was authorized. That historical eligibility was consumed and is superseded by the permanent
v71 rejection below. Appendix B remains unaccepted.

## July 18 v71/v72 Runtime-Usability Rejections

v71 compiled and sealed the Council field, completed rounds 0-2, and entered round 3 without the
v70 nested-schema failure. It was intentionally stopped after every completed round reported
`tests=0/0` for both groups. The compiler persisted nested typed test envelopes while the runtime
evaluator still accepted only an obsolete flat assertion shape. v71 is permanently rejected and
has no complete archive or manifest.

Current code now emits discriminator-bearing `sealed-tests-v2`, evaluates promotion tests only
from exact hash-valid bounded execution records, preflights every declared test before worker
activation, requires Appendix B archive/output composition, requires final-output lineage, rolls
back false completion authority on archive failure, and wires the desktop archive service.
Focused API, compiler, substrate, and Compose JVM checks pass. The later authorized CLI
observability/security repair now passes its complete suite and clean independent re-review with
identical pre/post source fingerprint
`52b0f3760eaaa0261dd89d8a27d2e821f7b09ac3feccf7029489a36d6b027de5`.
Canonical CLI log:
`build/appendix-b-cli-observability-final-v3-20260718.log`
(`a273aa2771d7763c5615138714f9563dc59c180f8c57c0ad14996e715486b5af`).

Final-byte gate evidence consumed by v72:

- `build/appendix-b-cli-observability-final-v3-20260718.log`:
  `a273aa2771d7763c5615138714f9563dc59c180f8c57c0ad14996e715486b5af`;
- `build/appendix-b-cli-observability-independent-rereview-20260718.txt`:
  `277d45d8bf9b6154ecb8815d339c16e87b19a2e99da1b5b11a6d1ff1bc1ae54d`;
- `build/appendix-b-final-cli-nine-module-current-20260718.log`:
  `fb4b9c462d369450663be18db717912d7a8af81a27fa73579594c0bffe30200f`,
  59/59 tasks executed with stable pre/post source manifest
  `e3e0bf0c63aa6477b1540d6b3e13d5bb4d77d29703f2334e67c399eb0784639c`;
- `build/section18-1-contract-security-matrix-final-cli-current-20260718.log`:
  `7004dad9218d38eed43a5f14c2f75d1b06217d62da342ba261500f69d8c696df`,
  56/56 tasks executed with the same stable source manifest;
- `build/appendix-b-final-cli-compose-current-20260718.log`:
  `3602c8db322b6583b9bf4f841a9c823071dd8d835fac71fceaa4523a750e2061`,
  55/55 tasks executed with stable pre/post source manifest
  `cee7692ef1b50e2a7e561e36465354372fd1cbb961bcec769ef1a0429ca612fb`;
- `build/appendix-b-final-cli-static-doc-gates-v2-20260718.log`:
  `d390d5598a29b9f45d4431bd723658f62ec518edd02b7f858e12d28ca0b93cac`.

That evidence authorized and was consumed by the one-round v72 representative attempt. v72
completed Council repair and sealed a one-stage field, then startup preflight rejected every one
of seven declared tests. Safe copied-SQLite inspection proved that all seven private grader
definitions were canonical JSON `null`; each exact commitment correctly equalled SHA-256(`null`),
but none matched the runtime `GENERAL` assertion grammar. The run created no worker round,
receiver deliverable, ZIP, or manifest.

Canonical v72 evidence:

- `build/appendix-b-representative-gpt-20260718-v72-runtime.log`:
  `2129cf974538857b18587a4254f3e7d0eb3313a9e4f372c3b374cc29a2140d55`;
- `build/appendix-b-v72-rejection-evidence-hashes-20260718.txt`:
  `6a1b9b73b5d161580cde85d41c09015f84419b058f4a66999961b57262d7d2b8`;
- `agent-notes/appendix-b-v72-general-test-executability-preflight-rejection-report.md`:
  `4523489870c8024c675abd5da55cee83d192c02e36ff348f884396a8ff54203e`.

v72 is permanently rejected. Its required shared `GENERAL` assertion contract, deterministic
regression coverage, independent review, and fresh gate cycle were subsequently completed; the
July 20 sequence below records the resulting representative and six-round runs.

Appendix B and accepted-run Section 18.2/18.3 remain unaccepted.

## July 20 Step 15 v4 and Reduced-Scope Goal Closure

The post-v72 remediation sequence completed Steps 1-14, including the accepted short
representative archive. The full Step 15 run
`appendix-b-full-gpt-20260720-v4` then passed immutable admission on exact
`codex-chatgpt:gpt-5.6-luna:fallbacks=[]`, completed Genesis Council, and exercised two worker
groups through six complete round starts, summaries, and RoundJudge phases.

The public log exposes useful provisional result signals, including a staged combined
reflective-coating plus incremental-portable-heat-pump strategy, electrical-demand ranges, the
45 kW service constraint, and an explicit lifecycle-cost evidence gap. The run nevertheless ended
`ABORTED`: two final-writer attempts timed out and two candidates failed mandatory sealed-test
authority. No receiver output, normal results archive, pipeline archive, manifest, accepted metrics
packet, or winner was published.

On July 20, 2026, the user explicitly changed the goal to presentation readiness: accept the
six-round pipeline demonstration as the Step 15 stopping point, skip Step 16, perform a quick
Step 17 reconciliation, document remaining work, and close the goal. This is an administrative
scope closure, not technical acceptance of the original definition of done.

Remaining uncompleted work is recorded in
`agent-notes/remediation-plan-after-v72-reduced-scope-closure-20260720.md`. Full Appendix-B
acceptance, accepted-run Section 18.2/18.3 metrics, the post-v72 matched three-arm comparison, and
any broad superiority claim remain open.

## 7. Document Authority

| Document Class | Authority |
|---|---|
| This file | Sole authority for current architecture, roadmap phase status, gaps, limitations, dependencies, and next actions. |
| `EvoMolior_Implementation_Plan_v1.0.md` | Normative intended behaviors, phase definitions, and original exit criteria. Its March 2026 status audit is historical. |
| `EvoMolior_Current_Architecture_Deep_Dive.md` | Maintained detailed subsystem reference. It defers current status and roadmap judgments to this file. |
| `EvoMolior_Specification_v0.2.html` | Product specification and design intent, not implementation proof. |
| `agent-notes/`, dated audits, postmortems, and goal logs | Historical evidence tied to their date and evidence cut. |
| `development/` plans | Specialized proposals or dated execution history. They do not override this file. |
| `development/stale/` and `development/tmp/` | Superseded or temporary material; never current authority. |

Update this document whenever a production authority edge, roadmap status, retained product
decision, or accepted validation baseline materially changes.
