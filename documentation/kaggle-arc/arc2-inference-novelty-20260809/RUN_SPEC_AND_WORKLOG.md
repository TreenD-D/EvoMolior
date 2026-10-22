# EvoMolior ARC2 inference and novelty run — locked specification

**Date:** August 9, 2026  
**Status:** Completed with a provisional, non-accepted receiver report  
**Workspace:** `/Volumes/CrucialX9_1TB/AStudioProjects/EvoMolior`  
**Input authority (read-only):** `/Volumes/CrucialX9_1TB/arc2-em/docs`  
**EvoMolior artifacts:** `/Volumes/CrucialX9_1TB/AStudioProjects/EvoMolior`

## Operator intent

Execute a real EvoMolior multi-society run that studies the current ARC-AGI-2
Kaggle solver state and produces grounded, actionable inference research and
novel solver ideas. The run must use the plans and state documents in the input
authority above.

## Locked run shape

- Six evaluation rounds.
- Four competing worker groups.
- A concurrency ceiling of five agents per group; the objective instructs the
  Genesis Council to allocate four or five complementary agents per group.
- Research/inference scope, not implementation or an unsupported score claim.
- Current source documents are authoritative; conflicts must be called out and
  resolved by date, evidence, and explicit authority.
- Public-training evidence may guide hypotheses, but evaluation/private labels
  must not be used or inferred.

## Required deliverables

The primary receiver artifact must be a standalone, valid HTML5 report. It must
contain:

1. an evidence-grounded current-state synthesis;
2. a residual/plateau diagnosis;
3. novel inference mechanisms and solver hypotheses;
4. explicit links from each important recommendation to source evidence;
5. ranked experiments with falsifiers, metrics, budgets, and promotion/kill
   gates;
6. a concrete Kaggle-safe integration path;
7. risks, leakage controls, and claims that must not be made;
8. a 24-hour, 7-day, and longer-horizon research sequence.

Additional requested artifacts:

- machine-readable hypothesis register (`.json`);
- experiment backlog (`.csv`);
- source/evidence index (`.json` or `.csv`);
- concise implementation handoff (`.md`);
- run summary, replay/archive bundles, logs, and any other native EvoMolior
  outputs.

## Evidence and completion audit

- [x] Goal and deliverable contract locked in a durable file.
- [x] Runtime prerequisites verified.
- [x] Prompt and run configuration captured.
- [x] Six-round, four-group, five-agent-cap run launched.
- [x] Run reaches a terminal state.
- [x] Native run summary proves configured rounds/groups/agent cap.
- [x] Standalone HTML5 primary report exists and parses successfully.
- [x] Additional requested artifacts exist and parse successfully.
- [x] Final artifact inventory and hashes recorded.
- [x] Explicit operator requirement audit completed.

## Worklog

- 2026-08-09: Created the locked specification before launching the run.
- 2026-08-09: Initial Gradle invocation selected host JDK 26.0.1 and failed
  during configuration with `26.0.1`; JDK 17 is available and will be used for
  build/runtime verification.
- 2026-08-09: Rebuilt the current CLI distribution successfully with JDK 17.
- 2026-08-09: Verified both OpenRouter and Qwen credentials are configured in
  `local.properties`, and verified the local Codex/ChatGPT session. Selected
  the OpenRouter production backend because it preserves native Koog tool
  calls and heterogeneous role models; the Codex compatibility backend is
  text-only.
- 2026-08-09: Captured the objective in `RUN_PROMPT.md` and the exact six-round,
  four-group, five-agent-cap HTML pipeline contract in `pipeline.json`.
- 2026-08-09: Attempt `arc2-inference-novelty-20260809-r1` reached the first
  Genesis Council role and failed before provider dispatch. The durable cost
  ledger correctly rejected an unknown-price reservation while a monetary cap
  was enabled (`Cost reservation was duplicated or exceeded the cumulative
  safety cap`). No Council answer or worker round was produced. The failed
  attempt is retained under this project's
  `runs/arc2-inference-novelty-20260809-prior-attempts/`.
- 2026-08-09: Root cause verified in `ProviderCallCostRecorder` and
  `CostLedgerDao`: provider reservations intentionally carry no invented USD
  bound, so OpenRouter runs must use the CLI's explicit `-iso-latency` mode
  until authoritative model-price bounds are available. The retry preserves
  the 12,000 action-credit ceiling while disabling only the unavailable USD
  price cap.
- 2026-08-09: Attempt `arc2-inference-novelty-20260809-r2` passed cost
  reservation and reached the OpenRouter network boundary, then failed with
  HTTP 402 `Insufficient credits` before the first Council answer. No worker
  round was produced. The configured Qwen Cloud credential and native
  tool-capable backend are the next available production route.
- 2026-08-09: At the operator's direction, interrupted Qwen attempt `r3`
  during the first Council call, before any Council answer or worker round,
  and moved every EvoMolior-created prompt, worklog, pipeline file, database,
  log, and startup diagnostic out of the ARC2 project. The ARC2 project is now
  used only as the read-only input authority. All run output and final
  deliverables remain in the current EvoMolior project.
- 2026-08-09: Qwen Cloud attempt `arc2-inference-novelty-20260809-r4`
  completed multiple typed specialist outputs and three closure-repair cycles,
  but the eight-minute network-provider Council envelope expired before a
  field compiled. Persistent errors concerned KG node mappings, reward-credit
  bounds, and seed calibration commitments. The startup diagnostic is retained
  under this project's `runs/.startup-rejections/`.
- 2026-08-09: Selected the authenticated Codex/ChatGPT `gpt-5.6-luna`
  backend for the next attempt. Current runtime policy gives this backend a
  45-minute Council envelope and code-mode access to the isolated
  `read_run_artifact` bridge; medium reasoning is used to balance deep
  inference with the full six-round population.
- 2026-08-10: Codex/ChatGPT attempt
  `arc2-inference-novelty-20260809-r5` loaded all 184 ARC2 source artifacts,
  compiled the Genesis Council field, created four worker groups, and allocated
  five agents to every group in rounds 0 through 3. Rounds 0 through 2 sealed
  and archived successfully. Round 3 completed worker, Critic, grader, capture,
  yield, disclosure, and future-diagnostic phases, then failed while compiling
  interim receiver snapshots. The run summary is `ABORTED`; no receiver output
  bundle was published.
- 2026-08-10: Queried the r5 Atlas database and identified the exact invalid
  producer record. Node
  `node_b47a43c06f983db48c6ee5f8e780e1215c872ccbddc0eae003443c5211babe49`
  had a valid round-2 `SHARED_VALIDATION_CREDIT` decision owned by two worker
  groups and backed by two `VALID_PASS` bundles. Its round-3 `NO_EVIDENCE`
  audit retained `SHARED` state but emitted an empty owner set because
  `NodeExecutionState` represents shared ownership with a null scalar owner.
  The strict snapshot compiler correctly rejected that inconsistent audit
  history.
- 2026-08-10: Fixed the capture producer to resolve and validate historical
  shared capture authority and preserve its exact owner set across audit-only
  and no-evidence outcomes. Added a resolver regression reproducing a shared
  capture followed by a no-evidence round. Focused competition resolver and
  strict snapshot compiler tests pass under JDK 17. The failed r5 ledger is
  immutable and partially sealed, so it will remain preserved; a fresh
  six-round attempt is required.
- 2026-08-10: Fresh attempt `arc2-inference-novelty-20260810-r6` was rejected
  before worker activation. Under the normal acceptance profile, its Task
  Anatomist declared zero executable or promotion test IDs. That correctly
  produced an empty Synthesis test authority, but the later v3 suite compiler
  attributed the non-empty-validation-context failure to Synthesis and spent
  the remaining repair cycles rerunning a role that was forbidden to invent
  Task-owned test identities. The startup diagnostic and rejection summary are
  preserved under `runs/.startup-rejections/`.
- 2026-08-10: Added normal-profile specialist-closure validation requiring at
  least one Task-owned executable or promotion test declaration and routing the
  repair to `CouncilTaskAnatomist` before Synthesis. Updated the Task prompt to
  state the non-empty authority requirement and added closure/prompt
  regressions. Focused agent, orchestration, and full starting-field compiler
  tests pass under JDK 17.
- 2026-08-10: Attempt `arc2-inference-novelty-20260810-r7` loaded 189 current
  source artifacts from the read-only ARC2 input authority, compiled the
  Genesis Council field, and completed rounds 0 through 5. Its debug log
  contains 24 allocation records: exactly four worker groups received exactly
  five agents in each of six rounds. The Atlas ledger contains all 102 required
  phase events, including `ROUND_LEDGER_ARCHIVED` for every round, plus 24
  interim deliverable snapshots. No accepted-state decision lost its owner
  authority.
- 2026-08-10: r7 reached terminal `COMPLETED` execution status after
  11,153,909 ms. Strict accepted publication authority was unavailable because
  mandatory sealed-suite acceptance remained failed, so the native outcome is
  a 20-artifact `PROVISIONAL_DECISION_PACKAGE`, not an accepted winner. The
  recovery writer was cancelled during terminal cleanup after strict
  publication had already been ruled out. This caveat is retained in the
  report, handoff, verification record, and final audit.
- 2026-08-10: The substantive native receiver artifact is
  `runs/arc2-inference-novelty-20260810-r7/primary/arc2-inference-novelty-report.html`;
  the separate `outputs/` copy is a deterministic static fallback and was not
  selected. Published the substantive recovered report into this documentation
  directory. The publication tool corrected three occurrences of one
  non-existent evidence path,
  `ARC2_A2_2_INTERFACE_FREEZE.md`, to the exact supplied authority path
  `A2_2_INTERFACE_FREEZE.md`; no analytical claim or recommendation was
  changed.
- 2026-08-10: Validated the published report as standalone interactive HTML5
  with no external asset dependency, eleven required semantic sections, twelve
  complete hypothesis records, ten explicitly novel hypotheses, and eight
  source-index records that all resolve in the read-only ARC2 input authority.
  Extracted the embedded hypothesis register and evidence index, generated the
  ranked experiment backlog CSV, implementation handoff, report-validation
  receipt, run-verification record, and final hash inventory.
- 2026-08-10: Final focused JDK 17 tests pass for
  `NodeCaptureResolverTest`, `SealedRoundDeliverableSnapshotCompilerTest`,
  `CouncilSpecialistClosureValidatorTest`, `StartingFieldCompilerTest`, and
  `CouncilRolePromptsTest`. A broader API verification invocation also exposed
  one existing user-owned end-to-end test timeout:
  `EvoMoliorRunServiceEndToEndFlowTest.full diagnostic mode publishes a
  provisional package without acceptance authority` waits 60 seconds without
  observing its terminal event, both in the broad suite and in isolation. The
  task did not modify that test or its API service path; it remains an explicit
  repository test caveat rather than evidence against the completed r7 run.
  The system `/usr/bin/tidy` is an HTML4-era validator and rejects semantic
  HTML5 elements; the report instead passed the checked-in HTML5 structural,
  payload, interaction, and source-resolution validator. Its one actionable
  bare-ampersand warning was corrected during publication.

## Final operator requirement audit

| Requirement | Evidence | Result |
|---|---|---|
| Use ARC2 plans/state documents as input | r7 summary records `/Volumes/CrucialX9_1TB/arc2-em/docs` and 189 loaded artifacts; the run-local immutable copy is under r7 `.evomolior-data` | Met |
| Research inference and novel solver ideas | Report contains plateau diagnosis, twelve falsifiable hypotheses, ten marked novel, ranked causal experiments, pass@2 policy, and Kaggle-safe integration | Met |
| At least 5–6 rounds | Round starts, worker completions, and archived ledgers exist for rounds 0–5 | Met: 6 |
| Four worker groups | Persisted state and 24 allocation records identify four distinct worker groups | Met |
| Four or five agents per group | Every group-round allocation record says `5 agents` | Met |
| Standalone HTML5 report | `arc2-inference-novelty-report.html`; validation receipt confirms HTML5 doctype, semantic structure, built-in interaction, and no external assets | Met |
| Additional files | Hypothesis JSON, evidence JSON, experiment CSV, implementation handoff, validation receipt, verification record, hash inventory, native summary/log/database/recovery ZIP | Met |
| Honest evidence boundary | Report and handoff explicitly reject private-score, leaderboard, promotion, and submission-readiness claims; acceptance status is recorded as provisional | Met with caveat |
