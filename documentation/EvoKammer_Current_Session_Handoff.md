# EvoKammer Current Session Handoff

## Current Goal Extension (2026-06-06)
- New active goal:
  - apply fixes for the fast CLI variant 6 run
  - rerun the fast CLI end-to-end
  - inspect the outputs and logs for non-error issues, not just crashes
  - verify the implementation is working cleanly and efficiently

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
