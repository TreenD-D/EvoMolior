## ADHD Live Pipeline Handoff

Date: 2026-06-04

### Goal
Finish and prove the multi-output pipeline feature with a real OpenRouter-backed CLI run.

### What is already implemented
- CLI pipeline orchestration exists in `evokammer/cli/src/main/kotlin/com/achulkov/evokammer/cli/CliPipeline.kt`.
- Generic final output contract exists via `FinalOutputSpec` and `GeneratedOutputFile`.
- CLI/tests added for:
  - `-pipeline-config`
  - default follow-up stage
  - relative `promptFile` resolution
  - aggregated outputs
  - `inheritsPreviousOutputs=false`
- Archive test added for generic JSON outputs.

### Important live-run findings
The real bug is not the pipeline scheduler. The scheduler works.

The real failing path is the final-deliverable writer:
- In live runs, the dedicated final writer often does **not** persist a `DOC` staging entry.
- HTML appears to work only because `RunArchiveService` falls back to summary-derived HTML.
- Non-HTML outputs therefore fail completely in real runs because there is no equivalent fallback.

### Real runs already executed
Artifacts are under:
- `documentation/tmp/adhd_live_run/output/adhd-live-pipeline-20260604`
- `documentation/tmp/adhd_live_run/output/adhd-live-pipeline-20260604-r2`
- `documentation/tmp/adhd_live_run/output/adhd-live-pipeline-20260604-r3`

Observed:
- First long run proved multi-stage execution and previous-stage artifact handoff, but top-level `publishedOutputs` only had `final-article.html`.
- `stages/evidence-brief/results.zip` lacked `outputs/briefs/evidence-brief.json`; it only had fallback `outputs/final-article.html`.
- `RunCompletionRecord.generatedOutputFiles` was empty in the live stage bundle.

### Code changes made after the first live failure
1. Strengthened `RolePromptBuilder.buildFinalDeliverableFile(...)`
   - explicitly forbids chat-only completion
   - adds `read_run_artifact` to available tools
   - forces action sequence: optional one read -> `write_staging(type="DOC")` -> `write_death_note`
   - retry prompt now explicitly says not to end with analysis/plain text

2. Changed final-deliverable writer retry behavior in `EvoKammerRunService`
   - `FINAL_DELIVERABLE_MAX_ATTEMPTS` increased from `2` to `3`
   - retry attempts now rotate models instead of reusing one model
   - model order is:
     - `Role.Builder`
     - `Role.Researcher`
     - `Role.Archivist`

3. Updated targeted API test expectation
   - `EvoKammerRunServiceEndToEndFlowTest` now expects writer model attempts:
     - `test-builder-model`
     - `test-archivist-model`

4. Added a synchronization fix in `EvoKammerRunService`
   - replaced the old final-output flow:
     - `waitForLiveAgentsToDrain(...)`
     - single `readFinalDeliverableFile(...)`
   - with `awaitGeneratedOutputFile(...)`
   - this now polls for a valid persisted output marker and treats that as success as soon as it exists, even if the writer is still wrapping up or later fails on `write_death_note`
   - focused API test still passes after this change

### Tests most recently verified
- `./gradlew --no-daemon :evokammer:agents:test --tests com.achulkov.evokammer.agents.factory.RolePromptBuilderTest`
- `./gradlew --no-daemon :evokammer:api:test --tests 'com.achulkov.evokammer.api.service.EvoKammerRunServiceEndToEndFlowTest.browser deliverable archive prefers dedicated final writer html over summary fallback html'`

### Current live run status
`adhd-live-pipeline-20260604-r3` is/was the first run using the new retry/model-fallback logic.

The crucial thing to inspect after it exits:
- `documentation/tmp/adhd_live_run/output/adhd-live-pipeline-20260604-r3/pipeline-summary.json`
- top-level `outputs/`
- `pipeline-results.zip`
- `stages/evidence-brief/results.zip`
- `outputs/run_completed/*.json` inside the stage bundle

Pass condition:
- top-level `outputs/` contains both:
  - `final-article.html`
  - `briefs/evidence-brief.json`

### Update after full `r3` completion
`r3` fully completed and wrote:
- `pipeline-summary.json`
- `pipeline-results.zip`
- `stages/evidence-brief/summary.json`
- `stages/evidence-brief/results.zip`

However the feature is still not complete:
- top-level `outputs/` still only contains `final-article.html`
- `pipeline-summary.json` lists only `final-article.html` under `publishedOutputs`
- `stages/evidence-brief/results.zip` still lacks `outputs/briefs/evidence-brief.json`
- the stage bundle instead contains fallback `outputs/final-article.html`
- the stage `Run Completed` record still has no `generatedOutputFiles`

This confirms the remaining gap is dedicated non-HTML final-file publication, not stage execution or pipeline bundling.

### New code change after `r3`
Implemented a simplification for configured non-HTML final outputs:
- `RolePromptBuilder.buildFinalDeliverableFile(...)` now instructs non-HTML final writers to persist the raw file body only.
- The runtime already knows `relativePath` and `mediaType`, so models no longer need to emit the fragile `FINAL DELIVERABLE FILE / Path / Media-Type / CONTENT` wrapper.
- `EvoKammerRunService.extractGeneratedOutputFile(...)` now accepts:
  - raw parseable JSON bodies for `application/json` and `+json` media types
  - raw bodies for other non-HTML `text/*` outputs
  - while retaining backward-compatible support for the old wrapper format

### Tests now verified
- `./gradlew --no-daemon :evokammer:agents:test --tests com.achulkov.evokammer.agents.factory.RolePromptBuilderTest`
- `./gradlew --no-daemon :evokammer:api:test --tests com.achulkov.evokammer.api.service.EvoKammerRunServiceEndToEndFlowTest`
- `./gradlew --no-daemon :evokammer:cli:test --tests com.achulkov.evokammer.cli.CliArgumentsTest`

Added regression coverage:
- a service end-to-end test proving a raw JSON `DOC` final-writer submission becomes a canonical generated output file and is archived to `outputs/briefs/evidence-brief.json`

### Immediate next step
Run a new real provider-backed verification, e.g. `adhd-live-pipeline-20260604-r4`, using the same live prompt/config and confirm:
- top-level `outputs/final-article.html`
- top-level `outputs/briefs/evidence-brief.json`
- `pipeline-summary.json` lists both published outputs

### Final live proof (`r5`)
The feature is now proved live with:
- `/Volumes/CrucialX9_1TB/AStudioProjects/EvoMolior/documentation/tmp/adhd_live_run/output/adhd-live-pipeline-20260604-r5`

Verified artifact-level outcome:
- top-level `outputs/final-article.html` exists
- top-level `outputs/briefs/evidence-brief.json` exists
- `pipeline-summary.json` lists:
  - `briefs/evidence-brief.json`
  - `final-article.html`
- `stages/evidence-brief/results.zip` contains `outputs/briefs/evidence-brief.json`
- the evidence-brief stage `Run Completed` record contains `generatedOutputFiles` with:
  - `relativePath = briefs/evidence-brief.json`
  - `mediaType = application/json`

### Final root cause and fix
The key remaining live blocker was not the pipeline scheduler.

It was a lifecycle/runtime bug in in-process mode:
- `LifecycleManager` always passed `"/artifacts"` into `AgentBootContext.artifactsMountPath`
- that path is only valid inside Shuru VMs
- in-process agents therefore had metadata for uploaded artifacts but could not open the real files
- `read_run_artifact(...)` would list previous-stage artifacts yet fail with "does not exist" on reads
- this caused the follow-up JSON final writer to loop, exhaust iterations, or provider-fail before writing a `DOC`

Fix applied:
- in-process agents now receive the real host artifact directory path
- Shuru-backed agents still receive the VM mount path

Additional supporting fix:
- non-HTML final writers can now persist the raw file body directly
- the runtime attaches the configured `relativePath` and `mediaType`
- JSON outputs no longer depend on the fragile metadata wrapper format

### Final verification commands
- `./gradlew --no-daemon :evokammer:agents:test --tests com.achulkov.evokammer.agents.factory.RolePromptBuilderTest`
- `./gradlew --no-daemon :evokammer:orchestration:test --tests com.achulkov.evokammer.orchestration.lifecycle.LifecycleManagerTest`
- `./gradlew --no-daemon :evokammer:api:test --tests com.achulkov.evokammer.api.service.EvoKammerRunServiceEndToEndFlowTest`
- `./gradlew --no-daemon :evokammer:cli:test --tests com.achulkov.evokammer.cli.CliArgumentsTest`

## Follow-on Comparison Run Goal

New operator request after the multi-output feature was proved:
- add `documentation/tmp/` to `.gitignore`
- add the generated run-output folders under `documentation/tmp/` to `.gitignore`
- keep the handoff up to date
- try a heavier comparison run with the same ADHD inputs
- still only use two consecutive runs total
- use a materially larger run shape:
  - 6 groups
  - primary run: 20 rounds
  - 4-5 agents per group
  - second consecutive run: 10 rounds

### Implementation progress for the comparison-run goal

Completed:
- `.gitignore` now ignores generated output folders via `documentation/tmp/**/output/`
- CLI now supports explicit run-shape overrides:
  - `-group-count`
  - `-max-agents-per-group`
  - `-budget-override`
- pipeline stage config now supports per-stage overrides:
  - `groupCount`
  - `maxAgentsPerGroup`
  - `budgetOverride`
- tests added/updated to verify parsing and propagation of these overrides

Verified:
- `./gradlew --no-daemon :evokammer:cli:test --tests com.achulkov.evokammer.cli.CliArgumentsTest`
- `./gradlew --no-daemon :evokammer:orchestration:test --tests com.achulkov.evokammer.orchestration.lifecycle.LifecycleManagerTest`

### Comparison-run config prepared

File:
- `/Volumes/CrucialX9_1TB/AStudioProjects/EvoMolior/documentation/tmp/adhd_live_run/adhd_comparison_pipeline.json`

Current comparison configuration:
- two stages total
- same ADHD prompt and inputs
- primary run will be controlled from CLI flags:
  - `-group-count 6`
  - `-max-agents-per-group 4`
  - `-budget-override 1800`
  - `-rounds 20`
- follow-up stage is configured in `adhd_comparison_pipeline.json` with:
  - `rounds = 10`
  - `groupCount = 6`
  - `maxAgentsPerGroup = 4`
  - `budgetOverride = 1800`
  - same JSON evidence-brief output path as before

Rationale:
- using `4` agents per group stays within the requested `4-5` range
- it is substantially larger than the earlier proof runs
- it reduces the risk of a prohibitively long provider-backed run compared with `5`

### Intended command for the comparison run

```bash
ROOT=/Volumes/CrucialX9_1TB/AStudioProjects/EvoMolior
OPENROUTER_API_KEY=$(grep '^OPENROUTER_API_KEY=' "$ROOT/local.properties" | cut -d= -f2-)
PROMPT_FILE="$ROOT/documentation/tmp/adhd_live_run/adhd_research_article_prompt.txt"
INPUT_DIR="$ROOT/documentation/tmp/adhd_live_run/input"
OUTPUT_DIR="$ROOT/documentation/tmp/adhd_live_run/output"
PIPELINE_FILE="$ROOT/documentation/tmp/adhd_live_run/adhd_comparison_pipeline.json"

./gradlew --no-daemon :evokammer:cli:run --args="-promptfile $PROMPT_FILE -input $INPUT_DIR -output $OUTPUT_DIR -openrouter-api-key $OPENROUTER_API_KEY -fast -rounds 20 -group-count 6 -max-agents-per-group 4 -budget-override 1800 -run-id adhd-live-pipeline-comparison-r1 -pipeline-config $PIPELINE_FILE"
```

### Next step if handing off

1. Start the above comparison run in a background shell.
2. Wait for completion.
3. Audit:
   - `/outputs/final-article.html`
   - `/outputs/briefs/evidence-brief.json`
   - `pipeline-summary.json`
   - both stage summaries and stage bundles
4. Report runtime characteristics:
   - total wall-clock time
   - whether the larger shape produced stronger or weaker artifact quality
   - whether provider instability became materially worse at this scale

### Active comparison run

Started:
- run id: `adhd-live-pipeline-comparison-r1`
- background shell session: `1640`

Command in flight:

```bash
ROOT=/Volumes/CrucialX9_1TB/AStudioProjects/EvoMolior
OPENROUTER_API_KEY=$(grep '^OPENROUTER_API_KEY=' "$ROOT/local.properties" | cut -d= -f2-)
PROMPT_FILE="$ROOT/documentation/tmp/adhd_live_run/adhd_research_article_prompt.txt"
INPUT_DIR="$ROOT/documentation/tmp/adhd_live_run/input"
OUTPUT_DIR="$ROOT/documentation/tmp/adhd_live_run/output"
PIPELINE_FILE="$ROOT/documentation/tmp/adhd_live_run/adhd_comparison_pipeline.json"

./gradlew --no-daemon :evokammer:cli:run --args="-promptfile $PROMPT_FILE -input $INPUT_DIR -output $OUTPUT_DIR -openrouter-api-key $OPENROUTER_API_KEY -fast -rounds 20 -group-count 6 -max-agents-per-group 4 -budget-override 1800 -run-id adhd-live-pipeline-comparison-r1 -pipeline-config $PIPELINE_FILE"
```

Observed so far:
- the run has moved well beyond startup
- a live agent log excerpt already shows normal task/artifact tool use such as:
  - `claim_task({})`
  - `read_run_artifact({"listOnly":true})`
- this strongly suggests the new run-shape overrides did not break basic fast-run execution
- the run is materially heavier than the proof runs:
  - by observation it reached at least round `2` of `20`
  - the output directory already reached roughly `17M` while still early in the primary stage
- early runtime pattern under the larger shape:
  - frequent OpenRouter/Alibaba `function.arguments must be in JSON format` provider failures
  - many grader runs fall back to substrate scoring because RoundJudge produced `0` submitted scores
  - multiple warning-only no-progress completions from grader agents
  - agent aborts appear more frequent than in the smaller `r5` proof run

Interim quantified snapshot as of `2026-06-05 11:09 +0200`:
- progress reached at least primary-stage round `8`
- output root size reached about `41M`
- `debug-log.txt` already contains:
  - `913` occurrences of the provider-side `function.arguments` JSON-format failure pattern
  - `146` `warning-only no-progress completion` events
  - `144` grader fallback warnings of the form `no scores for group-* — will rely on substrate scoring`
- interpretation:
  - the larger shape is operationally progressing
  - artifact handoff and run-shape override wiring appear intact
  - competitive grading quality is degrading materially under this load, so final comparison notes should separate publication success from content/scoring quality

### Critical new runtime finding from `r3`
The final-deliverable attempts are not strictly serialized in practice.

Observed in `r3` log:
- attempt 1 used `z-ai/glm-4.7-flash`
- `waitForLiveAgentsToDrain(..., "final deliverable")` timed out
- runtime retried with attempt 2 (`qwen/qwen3.5-flash-02-23`)
- runtime retried with attempt 3 (`openai/gpt-oss-120b`)
- **after** the retry chain had already started, attempt 1 eventually produced:
  - `write_staging(type="DOC", content="<!doctype html>...")`

Implication:
- `readFinalDeliverableFile(...)` can miss a real output simply because it runs too early after the drain timeout.
- The bug is therefore not only prompt/model reliability. It is also a synchronization / timeout problem in the final-writer orchestration.

Additional `r3` detail:
- attempt 1 eventually executed `write_staging(type="DOC", content="<!doctype html>...")`
- immediately after that, it failed on `write_death_note` argument parsing because the model produced a wrapped payload like `{\"value\":{...}}`
- so a writer can partially succeed (persisting the actual output) and still end in an aborted state

This strengthens the case that the runtime should treat persisted `DOC` output as success even if the subsequent death-note step fails, and should not retry blindly once a valid generated file already exists.

Likely root-cause fix direction:
1. Re-check whether the new `awaitGeneratedOutputFile(...)` logic is sufficient in live runs before making further lifecycle changes.
2. If live runs still overlap retries incorrectly, make final-deliverable attempts strictly serialized.
3. Do not spawn a retry while the previous final-writer agent is still alive.
4. If needed, explicitly kill/cancel a timed-out final writer before retrying.

### Known runtime quirks from logs
- Fast-mode `Builder` model `z-ai/glm-4.7-flash` can hit provider/rate-limit issues during final writer step.
- `Archivist` model `openai/gpt-oss-120b` often collapses into warning-only no-progress completions.
- This is why model rotation was added.

### Likely next actions
1. Poll/inspect `r3` output after completion.
2. If `briefs/evidence-brief.json` still missing:
   - inspect `r3` debug log for `final_deliverable_spawn`
   - inspect which model attempt wrote/failed
   - inspect stage `run_completed` archive record for `generatedOutputFiles`
3. If still broken, likely next fix is not pipeline-level. It will be one of:
   - more explicit final-writer prompt/tooling constraints
   - special-case non-HTML persistence fallback
   - forcing final writer onto a more reliable model than the role defaults
