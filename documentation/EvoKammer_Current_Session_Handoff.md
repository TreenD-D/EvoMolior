# EvoKammer Current Session Handoff

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
- The console CLI writes each run into a per-run folder beneath the supplied `-output` path and persists `prompt.txt`, `summary.json`, and `results.zip` when available.

## Goal Audit
- `-fast` uses only the approved model IDs: verified by `CliArgumentsTest.fast mode only accepts the approved model set` and the live run `modelOverrides` in `summary.json`.
- The CLI writes all run logs into the output folder: verified by the live fast runs producing `debug-log.txt` under the per-run output directory.
- The live fast run uses 5 rounds and a MindVortex HTML5-article prompt: verified by `summary.json` from `mindvortex-fast-smoke-fixed`.
- The run produces both a standalone article file and a bundled archive copy: verified by `/tmp/evokammer-fast-smoke-vRAWXd/mindvortex-fast-smoke-fixed/.evokammer-data/evokammer/runs/mindvortex-fast-smoke-fixed/results/final-article.html` and `results.zip` containing `outputs/final-article.html`.
- The post-run fix plan captures the remaining issues: verified by `documentation/EvoKammer_Fast_Run_Fix_Plan.md`.

## New Active Goal
- Investigate the latest `/tmp` fast-run grader behavior and fix the grading architecture.
- Current evidence from `/private/tmp/evokammer-fast-verify-output-roWm54/mindvortex-fast-verify-20260603-095106/debug-log.txt`:
  - only one grader group ran
  - criteria agents did not leave durable staged criteria
  - judging logged `no criteria found for grader-0 round=0`
  - RoundJudge collapsed to the empty-assistant recovery path and submitted zero scores
- Implementation direction for the next pass is captured in:
  - `documentation/EvoKammer_Grader_Civilizations_Fix_Plan.md`

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
