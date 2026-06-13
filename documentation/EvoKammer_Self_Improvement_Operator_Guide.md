# EvoKammer Self-Improvement — Operator Guide

> How to set up, launch, monitor, and interpret a recursive self-improvement run.

---

## 1. Architecture Summary

The self-improvement loop has four layers:

| Layer | Component | Role |
|---|---|---|
| **Proposal** | `OverseerAgentRunner` | LLM agent scans kernel state and proposes structured hypotheses |
| **Validation** | `OverseerKernel` | Deterministic rule engine; validates hypotheses against immutable paths |
| **Execution** | `SelfImprovementRunOrchestrator` | Builds `RunConfig` from experiment plans, launches EvoKammer runs, collects evidence |
| **Evaluation** | `PatchAcceptanceEvaluator` | Deterministic checks: scope, build, tests, invariants, metric dominance, change budget |

**Immutable paths** (never modifiable by self-improvement):

- `constitution/` — constitutional principles
- `watchdog/` — stasis detection and cost ledger
- `scoring_engine/`, `blind_tournament/` — sealed scoring
- `contracts/` — integration contract schemas
- `evokammer/core/src/main/kotlin/.../core/config/ModelSelectionConfig.kt` — model catalog

**Fully mutable paths** (target for self-improvement):

- Tools, adapters, agent prompts (non-constitutional roles), documentation, tests, orchestration helpers

**Semi-mutable paths** (structured config files only — `.json`, `.yaml`, `.yml`, `.toml`, `.kts`, or in a `/config/` directory):

- Budget weights, selector heuristics, rubric configs

---

## 2. Prerequisites

### 2.1 Environment

- JDK 17+ (build validated on JDK 23)
- Gradle 8.x (the project ships its own wrapper)
- Git (for `worktree` isolation; optional if using `-mode copy`)

### 2.2 API Key

An **OpenRouter API key** is required. The OverseerAgent and any launched experiment runs use it.

Set it in your shell or pass it via `-env`:

```bash
export OPENROUTER_API_KEY="sk-or-v1-..."
```

### 2.3 Build Verification

Before launching, confirm the project compiles and tests pass:

```bash
./gradlew --no-daemon :evokammer:core:test :evokammer:cli:test
```

### 2.4 Long-Run Readiness

Current readiness verdict:

- Use the self-improvement loop for supervised runs only.
- Do not leave long multi-stage runs unattended until all of the following are revalidated end to end:
  - final-deliverable discovery after high-volume `DOC` staging activity,
  - safe rerun of aborted stages with stale output directories or sealed starting fields,
  - provider-failure tolerance under load for the final writer and judge paths.

Current verification status:

- Final-deliverable discovery after high-volume `DOC` staging is now covered by a focused regression
  that keeps the canonical HTML file visible after 180 late-stage `DOC` noise entries.
- Aborted follow-up stage reruns are now covered by a regression that confirms stale stage output
  directories are cleaned before re-execution.
- Provider-failure tolerance under load still needs a dedicated end-to-end proof before long
  unattended runs are considered ready.

---

## 3. Two Launch Modes

The `self-improve` subcommand supports two modes:

| Mode | Flag | What it does |
|---|---|---|
| **Workspace isolation** | (default) | Creates an isolated copy of the repo, runs a command inside it, captures a manifest |
| **Overseer cycle** | `--overseer-cycle` | Runs the full recursive self-improvement loop: hypothesis proposal → formalisation → scheduling → execution → result recording. When the kernel starts empty and the agent returns nothing, a bootstrap hypothesis and minimal experiment plan are seeded so the first cycle still discovers concrete work and can execute it. |

---

## 4. Mode 1 — Workspace Isolation

Use this mode to run an arbitrary command (e.g. a Gradle build, a test suite) inside an isolated copy of the repository. The source checkout is never touched.

### 4.1 Command

```bash
./gradlew --no-daemon :evokammer:cli:run \
  --args="self-improve \
    -source /path/to/EvoMolior \
    -workspace-root /tmp/evokammer-self-improve \
    -workspace-name experiment-001 \
    -mode auto \
    -command ./gradlew \
    -arg :evokammer:core:test \
    -arg --no-daemon"
```

### 4.2 Arguments

| Argument | Required | Description |
|---|---|---|
| `-source` | Yes | Path to the Git checkout to isolate |
| `-workspace-root` | Yes | Parent directory for the isolated workspace (created if missing) |
| `-workspace-name` | Yes | Child directory name (created inside `-workspace-root`) |
| `-command` | Yes | Executable to run inside the workspace |
| `-arg` | No | Additional argument for the command (repeatable) |
| `-mode` | No | `auto` (default), `worktree`, or `copy` |
| `-env` | No | `KEY=VALUE` environment variable (repeatable) |
| `-cleanup` | No | Delete workspace on success (default: keep) |

### 4.3 Isolation Strategies

| Mode | How | When to use |
|---|---|---|
| `auto` (default) | Tries `git worktree`, falls back to filesystem copy | General use |
| `worktree` | `git worktree add --detach HEAD` | Fast, lightweight, requires Git |
| `copy` | Recursive file copy (skips `.git`, `build/`, `.gradle/`, `documentation/tmp/`) | No Git available, or when worktree fails |

### 4.4 Output

After the command completes, the launcher emits a JSON report:

```json
{
  "manifestPath": "/tmp/evokammer-self-improve/experiment-001/.evokammer-self-improve/launch-manifest.json",
  "workspaceDir": "/tmp/evokammer-self-improve/experiment-001",
  "logFilePath": "/tmp/evokammer-self-improve/experiment-001/.evokammer-self-improve/launch.log",
  "exitCode": 0
}
```

The manifest (`launch-manifest.json`) contains full provenance: source path, workspace path, isolation mode, command, timestamps, and exit status.

---

## 5. Mode 2 — Overseer Cycle

Use this mode to run the full recursive self-improvement loop. The loop scans the current Overseer kernel state, asks the OverseerAgent to propose hypotheses, formalises valid proposals, registers experiment plans, and schedules them for future execution.

### 5.1 Command

```bash
./gradlew --no-daemon :evokammer:cli:run \
  --args="self-improve \
    -source /path/to/EvoMolior \
    -workspace-root /tmp/evokammer-self-improve \
    -workspace-name cycle-001 \
    -overseer-cycle \
    -overseer-cycles 5 \
    -env OPENROUTER_API_KEY=sk-or-v1-..."
```

To force the loop to execute scheduled experiments and keep running all requested cycles even
when a cycle makes no progress, add the explicit execution flags:

```bash
./gradlew --no-daemon :evokammer:cli:run \
  --args="self-improve \
    -source /path/to/EvoMolior \
    -workspace-root /tmp/evokammer-self-improve \
    -workspace-name cycle-001 \
    -overseer-cycle \
    -overseer-execute \
    -overseer-run-all-cycles \
    -overseer-cycles 3 \
    -env OPENROUTER_API_KEY=sk-or-v1-..."
```

### 5.2 Arguments

| Argument | Required | Description |
|---|---|---|
| `-source` | Yes | Path to the repository (used as context for the loop) |
| `-workspace-root` | Yes | Output root for persistent state (created if missing) |
| `-workspace-name` | Yes | Directory name for this cycle's output |
| `-overseer-cycle` | Yes | Enables the overseer loop mode |
| `-overseer-execute` | No | Launch scheduled experiment runs instead of stopping after scheduling |
| `-overseer-cycles` | No | Maximum cycles (default: `3`, must be ≥ 1) |
| `-env OPENROUTER_API_KEY=...` | Yes* | API key for LLM calls (*or set via shell env) |

### 5.3 What Happens Each Cycle

1. **OverseerAgent proposes** — The LLM agent scans the current kernel state (existing hypotheses, dead ends, experiment results) and proposes new hypotheses and experiment plans in structured JSON.
2. **Kernel validates** — Each proposal is checked against immutable-path rules. Proposals touching `constitution/`, `watchdog/`, etc. are rejected with reasons.
3. **Formalisation** — Valid hypotheses are stored in the kernel with `FORMALIZED` status.
4. **Experiment plan registration** — Plans referencing formalized hypotheses are stored and marked `UNDER_EXPERIMENT`.
5. **Scheduling** — Eligible experiment plans are scheduled with deterministic run IDs.

**Bootstrap note:** If the loop starts from an empty kernel and the agent returns no proposals, the runner seeds a small CLI-oriented bootstrap hypothesis so the first cycle can still produce actionable work rather than ending as a no-op.

**Note:** In the current MVP, overseer-cycle execution now launches scheduled bootstrap/experiment plans through `SelfImprovementRunOrchestrator`. The operator can still inspect or resume runs separately, but the CLI loop itself now produces experiment results.

**Cold-start behavior:** A completely empty overseer kernel no longer ends as a no-op. If the LLM returns no proposals on cycle 1, the runner seeds a bootstrap hypothesis focused on CLI integration coverage and a matching minimal experiment plan so the run produces a concrete improvement target and can execute it immediately.

### 5.4 Output

Each cycle emits a JSON report:

```json
[
  {
    "cycleIndex": 0,
    "hypothesesCount": 3,
    "proposalsCount": 2,
    "formalizedCount": 2,
    "experimentResults": 0,
    "errors": []
  },
  {
    "cycleIndex": 1,
    "hypothesesCount": 5,
    "proposalsCount": 3,
    "formalizedCount": 2,
    "experimentResults": 0,
    "errors": ["Hypothesis 'HYP-004' rejected by kernel: ..."]
  }
]
```

The loop stops early if a cycle produces no new proposals and no experiment results.

### 5.5 Persistent State

The kernel state is persisted at:

```
<workspace-root>/<workspace-name>/.evokammer-data/evokammer/overseer/overseer-kernel-state.json
```

This file survives across invocations. Re-running the loop with the same `-workspace-root` and `-workspace-name` resumes from the last persisted state.

The append-only audit trail is stored at:

```
<workspace-root>/<workspace-name>/.evokammer-data/evokammer/overseer/overseer-kernel-audit.jsonl
```

### 5.6 Fast Pipeline Plan Application

When the fast CLI pipeline emits `outputs/improvements/code-improvement-plan.json`, the next configured stage starts from a copied repo root under `.pipeline-inputs/<stageId>/`, and that plan is applied before the stage launches.

The applied-change report is written into the copied repo at:

```
<workspace-root>/<workspace-name>/.pipeline-inputs/<stageId>/.evokammer-self-improve/applied-code-improvement-report.json
```

This keeps the original checkout untouched while still making the next stage consume the rewritten tree as its uploaded run context.

Live validation: the `fast-code-live-reuse-20260612` run reused a published plan, rewrote `Example.kt` in the follow-up stage input tree, and completed the verification stage with the rewritten tree in place.

### 5.7 Patch Bundle Outputs

For fully mutable experiments, the self-improvement run should prefer a machine-readable JSON patch bundle over a prose-only report. The bundle should include the edited files or diffs, documentation of the change set, and verification steps so downstream tooling can apply or review the result without scraping HTML.

When the overseer loop is running against an isolated workspace, accepted patch bundles are applied back into the mutable repo copy under `.evokammer-self-improve/repo/` before the next cycle reads bootstrap context.

---

## 6. Hypothesis Lifecycle

Every hypothesis follows this state machine:

```
IDEA → FORMALIZED → UNDER_EXPERIMENT → ACCEPTED → INTEGRATED
                     ↘                  ↘
                       REJECTED → DEAD_END
```

| State | Meaning |
|---|---|
| `IDEA` | Raw proposal, not yet validated |
| `FORMALIZED` | Passed kernel validation; eligible for experiment registration |
| `UNDER_EXPERIMENT` | At least one experiment plan is registered against this hypothesis |
| `ACCEPTED` | All experiment runs passed acceptance criteria |
| `INTEGRATED` | Patches applied to the baseline repository |
| `REJECTED` | Experiments showed no improvement |
| `DEAD_END` | Terminal failure; future similar proposals are discouraged |

---

## 7. Inspecting State via IPC

When the EvoKammer IPC server is running (port 7400), you can query the Overseer state directly:

```bash
# Full kernel snapshot
curl -s http://localhost:7400/overseer/state | jq .

# Audit trail
curl -s http://localhost:7400/overseer/audit | jq .

# Experiment plan history
curl -s http://localhost:7400/overseer/experiment-plans/EXP-001/history | jq .

# Dead-end history for a hypothesis
curl -s http://localhost:7400/overseer/dead-ends/HYP-001/history | jq .

# Preflight a hypothesis without persisting
curl -s -X POST http://localhost:7400/overseer/hypotheses/preflight \
  -H "Content-Type: application/json" \
  -d '{"id":"HYP-test","title":"Test","origin":{"type":"INTERNAL_METRICS"},"targetScope":{"paths":["doc/test.md"],"mutabilityLevel":"FULLY_MUTABLE"},"rationale":"test","expectedEffect":{}}' | jq .
```

---

## 8. Launching Scheduled Experiment Runs (Future)

Once experiment plans are scheduled, the operator launches individual runs:

```bash
curl -s -X POST http://localhost:7400/overseer/experiment-plans/EXP-001/launch \
  -H "Content-Type: application/json" \
  -d '{
    "scheduledRunId": "EXP-001-run-01",
    "runConfig": {
      "runId": "EXP-001-run-01",
      "objective": "SELF-IMPROVEMENT EXPERIMENT RUN ...",
      "groupCount": 2,
      "groupProfiles": ["EXPLORATORY", "STRUCTURED"],
      "rounds": 2,
      "scoringDimensions": [{"name":"analysis_quality","weight":1.0,"description":"..."}],
      "openRouterApiKey": "sk-or-v1-...",
      "isTestRun": true,
      "budgetOverride": 50.0
    }
  }' | jq .
```

After runs complete, record their results:

```bash
curl -s -X POST http://localhost:7400/overseer/experiment-results \
  -H "Content-Type: application/json" \
  -d '{
    "experimentPlanId": "EXP-001",
    "hypothesisId": "HYP-001",
    "outcome": "ACCEPTED",
    "metricValues": {"pass_rate": 0.85},
    "note": "All acceptance criteria met."
  }' | jq .
```

---

## 9. Safety Constraints

- **Source checkout is read-only.** Neither mode modifies the live repository.
- **Workspace isolation.** The launcher refuses to create a workspace inside the source tree.
- **Immutable path enforcement.** The kernel rejects any hypothesis touching `constitution/`, `watchdog/`, `scoring_engine/`, `blind_tournament/`, `contracts/`, or model-selection config.
- **Budget limits.** Experiment plans declare `maxCost`; the orchestrator enforces per-run line and file budgets (default 500 lines, 20 files).
- **Dead-end registry.** Failed hypotheses are recorded with counterexamples; the OverseerAgent is instructed to avoid re-proposing similar ideas.
- **Model set is frozen.** Only the initial human-chosen model set is available. The OverseerAgent cannot propose new models or API keys.

---

## 10. Troubleshooting

### 10.1 "Missing required -command executable"

You're using the default workspace-isolation mode but forgot `-command`. Either add `-command` or add `-overseer-cycle`.

### 10.2 "OPENROUTER_API_KEY must be set"

In overseer-cycle mode, the API key must be available. Pass it via `-env OPENROUTER_API_KEY=...` or export it in your shell.

### 10.3 Long-Run Preflight Checklist

Before starting a long unattended run:

1. Confirm the output root is clean or intentionally reusable.
2. Verify any previously aborted stage directories have been removed or intentionally resumed.
3. Confirm the publication scan path still finds the canonical final HTML after many late-stage `DOC` entries.
4. Check provider health and recent failure rates for the configured OpenRouter models.
5. Prefer a supervised first pass on any new 4-stage or larger pipeline shape.

### 10.3 "Workspace directory already exists"

Choose a different `-workspace-name` or delete the existing workspace first.

### 10.4 "Hypothesis rejected by kernel: targets immutable path"

The OverseerAgent proposed changes to a protected directory. This is expected behaviour — the kernel is working correctly. The rejection reason is included in the cycle report's `errors` field.

### 10.5 "OverseerAgent proposal failed"

The LLM call timed out or returned unparseable output. The cycle continues with an error recorded. Subsequent cycles retry with the accumulated kernel state.

### 10.6 Slow Cycles

Each cycle involves an LLM call to the OverseerAgent. With typical models this takes 10–60 seconds. Reduce `-overseer-cycles` to speed up initial exploration.

---

## 11. Complete Example — End-to-End

```bash
# 1. Build and verify
./gradlew --no-daemon :evokammer:core:test :evokammer:cli:test

# 2. Export API key
export OPENROUTER_API_KEY="sk-or-v1-..."

# 3. Run the overseer loop (5 cycles)
./gradlew --no-daemon :evokammer:cli:run \
  --args="self-improve \
    -source $(pwd) \
    -workspace-root /tmp/evokammer-self-improve \
    -workspace-name cycle-$(date +%Y%m%d-%H%M%S) \
    -overseer-cycle \
    -overseer-cycles 5"

# 4. Inspect persisted state
cat /tmp/evokammer-self-improve/cycle-*/.evokammer-data/evokammer/overseer/overseer-kernel-state.json | jq '.hypotheses | length'

# 5. Re-run to accumulate more hypotheses
./gradlew --no-daemon :evokammer:cli:run \
  --args="self-improve \
    -source $(pwd) \
    -workspace-root /tmp/evokammer-self-improve \
    -workspace-name cycle-$(date +%Y%m%d-%H%M%S) \
    -overseer-cycle \
    -overseer-cycles 3"
```

---

## 12. Related Documents

- `EvoKammer_Recursive_Self_Improvement_Plan_v4.md` — Full behaviour-driven design spec
- `EvoKammer_Self_Improvement_Launch_Plan.md` — Original launcher design decisions
- `evokammer/core/src/main/kotlin/.../overseer/OverseerKernel.kt` — Kernel implementation
- `evokammer/agents/src/main/kotlin/.../factory/OverseerAgentRunner.kt` — Agent implementation
- `evokammer/api/src/main/kotlin/.../service/SelfImprovementRunOrchestrator.kt` — Orchestrator
- `evokammer/core/src/main/kotlin/.../overseer/PatchAcceptanceEvaluator.kt` — Patch evaluator
