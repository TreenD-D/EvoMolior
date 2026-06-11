# EvoKammer Crash Resume Recovery Plan

## Goal

Implement durable crash-resume support for persisted runs, use it to recover the unfinished
`adhd-live-pipeline-comparison-r1-evidence-brief` stage run if the saved state is sufficient,
and audit the affected live runs against the implementation plan and runtime expectations.

## Locked Findings

- The recent two-stage pipeline root run
  `adhd-live-pipeline-comparison-r1`
  completed successfully.
  - Evidence:
    - `/Volumes/CrucialX9_1TB/AStudioProjects/EvoMolior/documentation/tmp/adhd_live_run/output/adhd-live-pipeline-comparison-r1/debug-log.txt`
      contains:
      - `Round 17 started`
      - `Round 18 started`
      - `Round 19 started`
      - `EvoKammer CLI run completed runId=adhd-live-pipeline-comparison-r1 winner=group-0`
    - the run meta-archive contains `RUN_COMPLETED|1`
- The follow-up stage run
  `adhd-live-pipeline-comparison-r1-evidence-brief`
  is incomplete.
  - Evidence:
    - stage directory has no `summary.json`
    - stage directory has no `results.zip`
    - stage run root has no `results/` folder
    - stage meta-archive contains `ROUND_SUMMARY|21`, `STARTING_FIELD|1`, and `RUN_COMPLETED|0`
    - stage debug log only shows round progress through round 3 / generation 4-era work
    - stage DB still contains pending submissions
- Current `resumeRun(runId)` is not durable resume.
  - In `evokammer/api/src/main/kotlin/com/achulkov/evokammer/api/service/EvoKammerRunService.kt`
    it only flips in-memory status from `PAUSED` to `RUNNING` for the active process.
- There is no current process-death recovery boot path.
  - `startRun(config)` assumes a fresh run boot.
  - The run loop is launched asynchronously after large amounts of in-memory setup.
  - The same `runId` cannot simply be started again because the sealed StartingField write is
    intentionally write-once.
- `run_configs` schema exists and already has useful columns:
  - `status`
  - `startedAtMs`
  - `completedAtMs`
  - `startingFieldJson`
  - `currentGeneration`
  - `groupStagesJson`
  - but current runtime wiring does not yet appear to persist/populate it from the real run path.

## Recovery Constraints

- Do not start a replacement run with a new `runId` as a substitute for resume.
- Resume must reconstruct enough runtime state to continue the same persisted run safely.
- Resume must fail closed if the persisted state is not sufficient for a safe continuation.
- Recovery must work for both:
  - UI/API-triggered engine runs
  - CLI pipeline stage runs

## Design Direction

### 1. Durable run record

Make `run_configs` the canonical persisted control-plane record for an active run:

- insert a row before the run loop starts
- persist `startingFieldJson`
- persist `status` transitions:
  - `Pending`
  - `Genesis`
  - `Running`
  - `Paused`
  - `Completed`
  - `Failed`
  - `Terminated`
- persist `currentGeneration` on each round boundary
- persist per-group disclosure stages
- persist enough serialized run-launch config to rebuild the run loop after crash

### 2. Explicit recovery API

Add a durable recovery entrypoint separate from soft unpause semantics.

Candidate shape:

- `resumePersistedRun(runId: String)` on the engine service
- keep `resumeRun(runId)` for in-process paused active runs

CLI wrapper support should use the durable recovery path when it detects an unfinished persisted run.

### 3. Reconstructable runtime snapshot

Persist enough data to rebuild:

- original `RunConfig` launch inputs needed by the engine
- derived `StartingField`
- group IDs
- grader group IDs
- active disclosure stage state
- uploaded/context artifact mount info
- model overrides
- final output spec

### 4. Safe resume point semantics

Phase 1 implementation target:

- resume only from round boundaries / pre-round spawn
- if the crash happened mid-round, restart that round from the beginning
- before restart:
  - terminate any lingering local VM/agent state if needed
  - clean or quarantine incomplete live-round state that would double-apply effects
  - preserve already promoted canonical substrate state
- explicitly document which side effects are replay-safe

This is the safest first implementation and is likely sufficient for the evidence-brief recovery,
because the stage died before completion and already has durable promoted outputs per prior rounds.

### 5. CLI pipeline recovery

For pipeline stages:

- detect missing stage `summary.json` / `results.zip`
- detect unfinished run state from the run root
- call durable resume on the exact stage run ID instead of starting a new stage run
- only write stage wrapper artifacts after the resumed run reaches completion

## Open Questions To Resolve In Code

- Where should the serialized launch snapshot live?
  - extend `run_configs`
  - or add a dedicated persisted recovery record
- How should mid-round pending staging submissions be handled on replay?
  - preserve pending and let Critic reconsider
  - or prune only current-generation pending entries before respawn
- How should live agent budgets be reconstructed?
  - current likely-safe answer: respawn the round from boundary with fresh per-round agents
- How should grader-manager and coordinator state be rebuilt?
  - from deterministic persisted round outputs where possible
  - otherwise reconstruct per-round and continue from next safe boundary

## Audit Work Required Alongside Implementation

- Review live run logs for:
  - weak/no-progress agent completions
  - provider/tool-call formatting failures
  - round summary inconsistencies
  - stage wrapper/output persistence gaps
- Compare observed runtime against
  `/Volumes/CrucialX9_1TB/AStudioProjects/EvoMolior/documentation/EvoKammer_Implementation_Plan_v1.0.md`
  for:
  - crash survival expectations
  - substrate durability expectations
  - orchestration phase expectations
  - current project phase alignment

## Working Checklist

- [x] Trace all current `run_configs` reads/writes and confirm the active runtime path does not populate them
- [x] Design durable recovery API and a filesystem-backed recovery snapshot schema
- [x] Implement engine-level persisted-run resume
- [x] Add tests for crash-recovery from persisted run state
- [x] Add CLI/pipeline recovery path for unfinished stages
- [x] Recover `adhd-live-pipeline-comparison-r1-evidence-brief` if saved state is sufficient
- [x] Audit live run logs and outputs for weak points / inconsistencies
- [x] Reconcile current project phase against the implementation plan document

## Final Recovery Outcome

- Durable persisted-run resume support is now implemented in the engine and used by the CLI
  pipeline wrapper to continue unfinished stage runs instead of replacing them with new run IDs.
- The old stage run `adhd-live-pipeline-comparison-r1-evidence-brief` was resumed under the same
  persisted run ID and reached `COMPLETED`.
- That resumed completion was not operator-correct by itself because the malformed no-auth replay
  caused the dedicated final-deliverable writer to fail three times, leaving the archive layer to
  publish a misleading fallback `final-article.html`.
- The old run was still recoverable because earlier archived round summaries contained valid strict
  JSON evidence briefs:
  - `group-5` round-2 promoted `PLAN_UPDATE`
    `a4253d01-ef11-444f-9d8b-db2e48d48203`
  - `group-3` round-3 promoted `PLAN_UPDATE`
    `c995469e-c3f4-4922-95b2-a5083458cf43`
  - `group-4` round-3 promoted `PLAN_UPDATE`
    `06c0a04e-a7a0-408b-8053-4e33fe8a437e`
- The recovered operator-facing artifact was materialized from the persisted `group-5` round-2
  summary JSON because it is valid JSON, preserves date uncertainty conservatively, and matches
  the required evidence-brief schema.

Recovered artifact paths:

- pipeline output:
  `/Volumes/CrucialX9_1TB/AStudioProjects/EvoMolior/documentation/tmp/adhd_live_run/output/adhd-live-pipeline-comparison-r1/outputs/briefs/evidence-brief.json`
- stage persisted results:
  `/Volumes/CrucialX9_1TB/AStudioProjects/EvoMolior/documentation/tmp/adhd_live_run/output/adhd-live-pipeline-comparison-r1/stages/evidence-brief/.evokammer-data/evokammer/runs/adhd-live-pipeline-comparison-r1-evidence-brief/results/briefs/evidence-brief.json`
- updated stage bundle:
  `/Volumes/CrucialX9_1TB/AStudioProjects/EvoMolior/documentation/tmp/adhd_live_run/output/adhd-live-pipeline-comparison-r1/stages/evidence-brief/results.zip`

## Additional Hardening

- `RunArchiveService` now recovers configured non-HTML generated outputs from archived round
  summaries when the final writer failed, instead of silently fabricating an HTML fallback for
  JSON stages.
- `RunArchiveService` now writes recovered/generated output files into the run `results/`
  directory, not only into `results.zip`.
- Targeted regression coverage now includes recovery of a configured JSON output from archived
  round summaries after a final-writer failure.
