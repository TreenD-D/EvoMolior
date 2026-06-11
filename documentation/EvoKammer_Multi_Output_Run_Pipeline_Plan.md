# EvoKammer Multi-Output Run Pipeline Plan

## Goal

Extend the console run flow so one CLI invocation can execute a small pipeline of EvoKammer runs
and publish multiple generated output files into one overall output directory.

This is meant to support scenarios like:

- initial long-form generation run
- one or more short follow-up runs that consume the first run's outputs
- localized variants of the main deliverable
- different file types per follow-up stage, not only HTML

## Product Direction

The right abstraction here is not "one run can magically emit every artifact shape at once".
Instead, the CLI should orchestrate a **run pipeline**:

1. execute the primary run
2. stage its artifacts into a shared pipeline workspace
3. execute one or more follow-up runs against those artifacts
4. aggregate the generated files into a single final output folder and ZIP

This composes naturally with the already-proven "fast 1-round post-processing run" pattern.

## Scope For This Pass

1. Add CLI-level pipeline orchestration.
2. Add a JSON pipeline config file for extra run stages.
3. Default to one additional fast follow-up stage when no config is supplied.
4. Generalize the final deliverable path from HTML-only toward a typed single-file output per run.
5. Aggregate all stage outputs into one overall output directory.
6. Write a thorough README-style guide with command examples and config examples.

## Constraints

- Keep the existing single-run CLI path working.
- Do not break the desktop app or existing run archive semantics.
- Preserve the existing browser-deliverable path.
- Make follow-up stages deterministic in file naming so downstream automation can rely on them.

## Proposed Design

### 1. CLI Pipeline Orchestrator

Add a pipeline layer in `:evokammer:cli`.

Instead of only:

- parse CLI args
- execute one run
- write one summary

the CLI will support:

- build a stage plan
- run stages sequentially
- pass previous-stage artifacts into the next stage as input artifacts
- write:
  - `pipeline-summary.json`
  - `stages/<stage-id>/...`
  - `outputs/...` aggregated generated files
  - `pipeline-results.zip`

### 2. Stage Config JSON

Add an optional CLI flag:

```bash
-pipeline-config /absolute/or/relative/path/to/pipeline.json
```

The file defines additional follow-up stages and how they publish outputs.

If absent, the CLI will still create:

- the primary stage from the normal CLI prompt/options
- one default additional fast follow-up stage

### 3. One Generated Output File Per Stage

Generalize the current per-run publication concept from:

- one optional HTML final deliverable

to:

- one typed generated output file per run stage

Each stage can declare:

- relative output path
- media type
- human description
- whether to inherit previous-stage artifacts (`inheritsPreviousOutputs`, default `true`)

Examples:

- `outputs/final-article.html` + `text/html`
- `outputs/final-article-de.html` + `text/html`
- `outputs/final-article-fr.md` + `text/markdown`
- `outputs/brief.json` + `application/json`

This keeps the per-run archive model simple while allowing multiple files overall through
multiple stages.

### 4. Final Deliverable Extraction

The runtime final-deliverable writer should:

- keep the strong HTML path for browser objectives
- support a generic typed-file mode for non-HTML stage outputs
- persist the generated file into the completion record
- allow archive writing to place the file under the requested relative path

### 5. Aggregated Overall Output

The overall CLI output directory should contain:

```text
<output>/<pipeline-run-id>/
  pipeline-summary.json
  pipeline-results.zip
  outputs/
    final-article.html
    final-article-de.html
    final-article-fr.html
    brief.json
  stages/
    primary/
      prompt.txt
      summary.json
      results.zip
      debug-log.txt
      .evokammer-data/...
    localize-de/
      ...
    localize-fr/
      ...
```

### 6. Previous-Stage Artifact Handoff

Each follow-up stage should receive a synthetic input directory containing normalized handoff
artifacts from the prior stage, for example:

- `prior-stage/prompt.txt`
- `prior-stage/summary.json`
- `prior-stage/results.zip`
- extracted generated outputs like `prior-stage/outputs/final-article.html`

This lets the next stage use `read_run_artifact(...)` instead of depending on opaque runtime state.

## JSON Config Shape

Proposed config:

```json
{
  "stages": [
    {
      "id": "localize-de",
      "prompt": "Using the uploaded prior-stage HTML article, create a German localized version.",
      "fast": true,
      "rounds": 1,
      "inheritsPreviousOutputs": true,
      "finalOutput": {
        "relativePath": "final-article-de.html",
        "mediaType": "text/html",
        "description": "German localized HTML article"
      }
    },
    {
      "id": "brief-json",
      "prompt": "Using the uploaded prior-stage outputs, produce a structured JSON content brief.",
      "fast": true,
      "rounds": 1,
      "inheritsPreviousOutputs": true,
      "finalOutput": {
        "relativePath": "brief.json",
        "mediaType": "application/json",
        "description": "Machine-readable content brief"
      }
    }
  ]
}
```

## CLI README

### Basic single invocation with default extra stage

```bash
KEY=$(python3 - <<'PY'
from pathlib import Path
text = Path('local.properties').read_text()
for line in text.splitlines():
    if line.startswith('OPENROUTER_API_KEY='):
        print(line.split('=', 1)[1].strip())
        break
PY
)

./evokammer/cli/build/install/evokammer/bin/evokammer \
  -fast \
  -rounds 10 \
  -run-id "mindvortex-pipeline-demo" \
  -promptfile documentation/prompts/mindvortex_fast_quality_prompt.txt \
  -input /absolute/path/to/input \
  -output /absolute/path/to/output \
  -openrouter-api-key "$KEY"
```

Behavior:

- runs the main stage
- runs one default fast follow-up stage
- writes aggregated outputs into one output folder

### Pipeline config with two localized follow-up stages

```bash
./evokammer/cli/build/install/evokammer/bin/evokammer \
  -fast \
  -rounds 10 \
  -run-id "mindvortex-localized-pipeline" \
  -promptfile documentation/prompts/mindvortex_fast_quality_prompt.txt \
  -pipeline-config documentation/examples/mindvortex_localized_pipeline.json \
  -input /absolute/path/to/input \
  -output /absolute/path/to/output \
  -openrouter-api-key "$KEY"
```

### Example config: German + French HTML plus JSON brief

```json
{
  "stages": [
    {
      "id": "localize-de",
      "prompt": "Read the prior-stage HTML article and create a German localized version with equivalent structure and CTA.",
      "fast": true,
      "rounds": 1,
      "inheritsPreviousOutputs": true,
      "finalOutput": {
        "relativePath": "final-article-de.html",
        "mediaType": "text/html",
        "description": "German localized article"
      }
    },
    {
      "id": "localize-fr",
      "prompt": "Read the prior-stage HTML article and create a French localized version with equivalent structure and CTA.",
      "fast": true,
      "rounds": 1,
      "inheritsPreviousOutputs": true,
      "finalOutput": {
        "relativePath": "final-article-fr.html",
        "mediaType": "text/html",
        "description": "French localized article"
      }
    },
    {
      "id": "content-brief",
      "prompt": "Read the prior-stage outputs and write a machine-readable JSON brief with title, audience, sections, CTA, and SEO metadata.",
      "fast": true,
      "rounds": 1,
      "inheritsPreviousOutputs": true,
      "finalOutput": {
        "relativePath": "content-brief.json",
        "mediaType": "application/json",
        "description": "Structured content brief"
      }
    }
  ]
}
```

### Expected aggregated outputs

```text
outputs/
  final-article.html
  final-article-de.html
  final-article-fr.html
  content-brief.json
```

## Implementation Steps

1. Refactor current single-run CLI path into a reusable per-stage executor.
2. Add pipeline config parsing and validation.
3. Add a stage-plan builder with:
   - primary stage from CLI args
   - one default extra fast follow-up stage when config absent
4. Add previous-stage artifact handoff preparation.
5. Add generic per-run final-output spec support in the runtime/archive path.
6. Add aggregated pipeline output writing and pipeline ZIP manifest.
7. Add targeted tests for:
   - pipeline config parsing
   - default extra fast stage creation
   - previous-stage artifact handoff
   - generic output file persistence
   - aggregated pipeline outputs

## Current Status

- Implemented:
  - `:evokammer:cli` now has a pipeline orchestration layer in
    `evokammer/cli/src/main/kotlin/com/achulkov/evokammer/cli/CliPipeline.kt`
  - `RunConfig` now supports a generic `FinalOutputSpec`
  - `EvoKammerRunService` now persists a generic generated output file, not only HTML
  - `RunArchiveService` now writes `generatedOutputFiles` into `results.zip`
  - CLI parsing now accepts `-pipeline-config`
  - pipeline stage `promptFile` paths are resolved relative to the pipeline config file
  - example pipeline config added:
    `/Volumes/CrucialX9_1TB/AStudioProjects/EvoMolior/documentation/examples/mindvortex_localized_pipeline.json`
- Verified so far:
  - `./gradlew --no-daemon :evokammer:cli:compileKotlin :evokammer:api:compileKotlin :evokammer:agents:compileTestKotlin`
  - `./gradlew --no-daemon :evokammer:cli:test --tests com.achulkov.evokammer.cli.CliArgumentsTest`
  - `./gradlew --no-daemon :evokammer:api:test --tests com.achulkov.evokammer.api.service.RunArchiveServiceTest`
  - `./gradlew --no-daemon :evokammer:agents:test --tests com.achulkov.evokammer.agents.factory.RolePromptBuilderTest`
  - `./gradlew --no-daemon :evokammer:api:test --tests com.achulkov.evokammer.api.service.EvoKammerRunServiceEndToEndFlowTest`
  - `./gradlew --no-daemon :evokammer:orchestration:test --tests com.achulkov.evokammer.orchestration.lifecycle.LifecycleManagerTest`
  - `CliArgumentsTest` now explicitly covers:
    - `-pipeline-config` parsing
    - the default extra fast follow-up stage for fast browser-deliverable runs
    - pipeline summary file creation
    - aggregated top-level `outputs/` publication
    - previous-stage handoff expansion from a valid `results.zip`
    - relative stage `promptFile` resolution from the pipeline config directory
    - configured non-HTML stage output publication
- Live OpenRouter-backed proof:
  - real provider-backed verification now exists under:
    `/Volumes/CrucialX9_1TB/AStudioProjects/EvoMolior/documentation/tmp/adhd_live_run/output/adhd-live-pipeline-20260604-r5`
  - that run completed with top-level aggregated outputs:
    - `outputs/final-article.html`
    - `outputs/briefs/evidence-brief.json`
  - `pipeline-summary.json` for `r5` lists both published outputs
  - `stages/evidence-brief/results.zip` for `r5` contains
    `outputs/briefs/evidence-brief.json`
  - the evidence-brief stage `Run Completed` record for `r5` contains
    `generatedOutputFiles=[{relativePath:\"briefs/evidence-brief.json\", mediaType:\"application/json\", ...}]`
- Important implementation note:
  - a key live root cause was fixed in `LifecycleManager`: in-process agents were incorrectly
    given the VM mount path `"/artifacts"` instead of the host artifact directory, which caused
    `read_run_artifact(...)` to list metadata but fail to open actual previous-stage files
  - non-HTML final-output extraction was also simplified so JSON final writers can publish the
    raw JSON body without restating file metadata headers

## Verification Target

After implementation:

- one CLI invocation can emit multiple files into one overall output folder
- config can define multiple follow-up stages
- non-HTML output media types are supported at the run publication level
- the default no-config path still runs one additional fast follow-up stage
- existing single-run flows still work
