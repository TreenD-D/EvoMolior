# EvoMolior

**Civilisational multi-agent runtime for evidence-heavy work.**

EvoMolior runs short-lived AI agents inside a persistent substrate. Agents can die; the Knowledge
Base, Knowledge Graph, staged work, run artifacts, scores, and legacy protocols remain. The system
is built to test when a society of specialised agents can outperform or better audit a single normal
agent, and to preserve the evidence when it cannot.

> **Global AI Hackathon Series with Qwen Cloud — Agent Society track.** EvoMolior connects
> directly to Qwen Cloud/DashScope for structured, tool-enabled agent execution and optional
> Qwen-generated final-deliverable images. The judge-ready submission pack is in
> [`documentation/submission/`](documentation/submission/README.md).

[![EvoMolior architecture](documentation/submission/assets/evomolior-architecture.svg)](documentation/submission/EvoMolior_Architecture.html)

Submission shortcuts:

- [Devpost-ready project description](documentation/submission/Devpost_Submission.md)
- [Layered HTML5 architecture deep dive](documentation/submission/EvoMolior_Architecture.html)
- [Three-minute demo script](documentation/submission/Demo_Video_Script.md)
- [Judge inspection guide](documentation/submission/Judge_Guide.md)
- [Architecture diagram and explanation](documentation/submission/Architecture.md)
- [Final submission checklist](documentation/submission/Submission_Checklist.md)

```
objective + input artifacts
        |
        v
Genesis Council -> StartingField -> competing groups
        |                              |
        |                              v
        |                     short-lived role agents
        |                              |
        |                              v
        |                 KB + KG + staging + messages
        |                              |
        v                              v
grader groups <-------------- sealed submissions
        |
        v
scores + feedback + final outputs + replayable archive
```

## What It Does

| Capability | Current implementation |
|---|---|
| Multi-agent runs | Creates competing groups, role-specialised agents, staged submissions, critique, scoring, and round feedback. |
| Persistent substrate | Stores group-isolated KB entries, KG facts, WIP tasks, messages, events, trust scores, dead ends, legacy protocols, and run config in Room/SQLite. |
| Agent tools | Exposes role-gated tools for KB/KG reads, staged writes, source approval/fetching, messaging, task claiming, consistency checks, sub-task delegation, grading, and Death Notes. |
| Source governance | External URLs outside the allowlist must go through `request_source_access`; `fetch_source` only fetches approved hosts. |
| Context control | KB access is index-first: `list_kb`/`search_kb` return organized active headers by default, and agents load full entries only by selected IDs or explicit full-content opt-in. |
| Generational KB hygiene | At generation boundaries, old uncited lower-trust KB entries are archived out of the active working set while reused, fresh, high-trust, and Dead End entries remain discoverable. |
| Benchmarks | Runs small benchmark slices through either EvoMolior society mode or supported single-agent baselines. |
| Interfaces | CLI runner, benchmark runner, authenticated remote dashboard, Compose Desktop control panel, Docker image, and KMP mobile/web app targets. |

## Current Status

EvoMolior is implemented for its accepted bounded release scope and is actively completing the
latest multi-round acceptance boundary. The canonical phase-by-phase status, architecture
authority map, known gaps, and next actions are maintained in
[`documentation/EvoMolior_Roadmap_and_Project_State.md`](documentation/EvoMolior_Roadmap_and_Project_State.md).

| Area | Status |
|---|---|
| Engine foundation | Implemented for the supported in-process lifecycle with persistent substrates, constitutional interception, source-egress controls, and bounded tool subprocesses. Shuru remains an optional stronger branch. |
| CLI task runner | Implemented. Supports OpenRouter, Qwen Cloud, and Codex/ChatGPT text-only backends. |
| Benchmark harness | Implemented for maintained benchmark slices, direct single-Qwen baselines, and immutable comparative experiments. |
| Desktop control panel | Implemented for JVM desktop control and inspection. |
| Remote dashboard | Implemented as a Ktor dashboard served by the CLI. |
| Agent isolation | Working with caveat. Worker groups use isolated databases and controlled routes; ordinary role agents are in-process, generated tools run in bounded child processes, and Shuru is not the default branch. |
| Constitutional/network enforcement | Connected. Native tool actions pass typed constitutional gates; approved source egress composes run and role allowlists with redirects disabled. |
| Knowledge Graph and tools | Implemented with deterministic long-horizon KG acceptance, Critic promotion, typed tool opportunities, bounded execution, versioning, and later-agent reuse authority. |
| Grader civilisation | Implemented with a separate sealed Grader Atlas, frozen criteria, exact bundles, fail-closed quorum, isolated scoring, MetaJudge arbitration, and replay. |
| Hierarchical execution | Implemented for the accepted outer-plus-one-inner scope, including rolled-up documents and executable tools. |
| Progressive disclosure and publication | Implemented with exact node evidence, capture, disclosure, Outlier amendments, causal lineage, typed metrics, sealed tests, and hash-verifiable archives. |
| Final acceptance | The post-v72 short representative archive passed. The full six-round Appendix-B acceptance is running; the subsequent new matched comparison is intentionally deferred. |

## Repository Map

```
EvoMolior/
  composeApp/           KMP app; JVM target is the EvoMolior desktop control panel
  designsystem/         shared Compose design tokens and components
  evomolior/
    core/               domain models, config, benchmark contracts, budgets, laws
    substrate/          Room/SQLite persistence for the civilisational substrate
    agents/             Koog agent factories, prompts, tools, runner behavior
    orchestration/      lifecycle, Genesis Council, watchdog, selection, evolution
    competition/        multi-group coordination, blind scoring, graders, leak oracle
    sandbox/            Shuru/microVM integration and source/network policy
    api/                engine service contracts, run archive, IPC-facing models
    runtime/            production runtime composition across engine modules
    cli/                `evomolior` CLI, benchmarks, self-improvement, dashboard
  libs/
    auth/               authentication abstraction and Firebase implementation
    subscription/       subscription abstraction and provider implementations
  build-logic/          Gradle convention plugins
  documentation/        specs, architecture notes, benchmark reports, deployment docs
```

Gradle module names intentionally stay under `:engine:*` even though source lives under
`evomolior/*`.

## Technology

| Concern | Stack |
|---|---|
| Language/build | Kotlin `2.2.21`, Gradle, Kotlin Multiplatform |
| Agent runtime | JetBrains Koog `1.0.0` |
| LLM backends | OpenRouter, Qwen Cloud/DashScope where the text client is available, and local Codex/ChatGPT text-only execution |
| UI | Compose Multiplatform `1.10.0-rc01` |
| Persistence | Room `2.8.3` with SQLite |
| Server/dashboard | Ktor `3.3.1` |
| Async/serialization | kotlinx.coroutines `1.10.2`, kotlinx.serialization `1.9.0` |
| Isolation target | Shuru microVMs on macOS/Apple Virtualization paths |

## Prerequisites

- JDK 17. If multiple JDKs are installed, pin `JAVA_HOME` to 17 before running Gradle.
- macOS for the full Shuru-backed isolation path. Non-macOS hosts can still build and run many JVM
  tests and dashboard paths, but full production isolation is macOS-oriented.
- One of:
  - `OPENROUTER_API_KEY` for OpenRouter.
  - `QWEN_API_KEY` for Qwen Cloud.
  - A local Codex CLI session for Codex/ChatGPT. Use `evomolior gptauth login` to authenticate via
    OpenAI browser OAuth, or `evomolior gptauth status` to check an existing session. EvoMolior does
    not shell out to `codex login` or device auth. If needed, set `EVOMOLIOR_CODEX_CLI` or
    `CODEX_CLI_PATH` to the Codex executable path.
- Optional `local.properties` with the same key names if you prefer not to export environment
  variables.

## Quick Start

Build the CLI distribution:

```bash
./gradlew :engine:cli:installDist
./evomolior/cli/build/install/evomolior/bin/evomolior --help
```

This builds a repository-local, runnable distribution at:

```text
evomolior/cli/build/install/evomolior/
```

To opt into a user-local command and background daemon/service integration, run:

```bash
./gradlew :engine:cli:installUserCli
```

`installUserCli` creates `~/.local/bin/evomolior`, copies the distribution to
`~/.local/share/evomolior/evomolior`, and configures the platform service. On macOS it installs a
user LaunchAgent at
`~/Library/LaunchAgents/com.achulkov.evomolior.daemon.plist`. On Linux systems with systemd user
services, including typical Ubuntu server installs, it writes
`~/.config/systemd/user/evomolior-daemon.service`, enables it, and restarts it. Open a new shell or
run `export PATH="$HOME/.local/bin:$PATH"` to call the CLI as plain `evomolior`. The installer adds
that PATH entry to `~/.zshrc`, `~/.zprofile`, `~/.bashrc`, and `~/.profile` when it is not already
present.

Check the local daemon:

```bash
evomolior daemon status
```

When a run is delegated to the daemon, file path arguments such as `-input`, `-output`,
`-promptfile`, and `-pipeline-config` are resolved from the terminal process that invoked
`evomolior`, so relative paths keep the same meaning as inline CLI execution.

Create small input/output folders:

```bash
mkdir -p /tmp/evomolior-input /tmp/evomolior-output
printf 'Use only grounded claims and produce a concise decision memo.' \
  > /tmp/evomolior-input/instructions.txt
```

Run a small OpenRouter-backed task:

```bash
./evomolior/cli/build/install/evomolior/bin/evomolior run \
  -prompt "Synthesize the input artifacts into a decision memo." \
  -input /tmp/evomolior-input \
  -output /tmp/evomolior-output \
  -fast \
  -rounds 1 \
  -group-count 1 \
  -max-agents-per-group 1 \
  -openrouter-api-key "$OPENROUTER_API_KEY"
```

Run the same shape against Qwen Cloud:

```bash
./evomolior/cli/build/install/evomolior/bin/evomolior run \
  -promptfile /tmp/evomolior-input/instructions.txt \
  -input /tmp/evomolior-input \
  -output /tmp/evomolior-output \
  -llm-backend qwen-cloud \
  -qwen-api-key "$QWEN_API_KEY" \
  -fast \
  -rounds 1
```

Authenticate or check a Codex/ChatGPT session and run against it:

```bash
./evomolior/cli/build/install/evomolior/bin/evomolior gptauth login

./evomolior/cli/build/install/evomolior/bin/evomolior gptauth status

./evomolior/cli/build/install/evomolior/bin/evomolior run \
  -promptfile /tmp/evomolior-input/instructions.txt \
  -input /tmp/evomolior-input \
  -output /tmp/evomolior-output \
  -llm-backend codex-chatgpt \
  -model Researcher=gpt-5.6-luna \
  -fast \
  -rounds 1
```

The Codex/ChatGPT backend wraps local `codex exec` with `gpt-5.6-luna` and medium reasoning effort,
and is intentionally text-only compatibility mode. It is suitable for smoke and test runs, but it
does not support native Koog tool calls. `evomolior gptauth login` uses OpenAI browser OAuth with a
localhost callback and writes `~/.codex/auth.json` without printing token values. `evomolior
gptauth status` is a non-mutating session check.

Useful task-run flags:

| Flag | Purpose |
|---|---|
| `-prompt "..."` | Inline objective. Mutually exclusive with `-promptfile`. |
| `-promptfile path` | Load the objective from disk. |
| `-input dir` | Recursively ingested run-start artifacts. Required. |
| `-output dir` | Per-run output directory root. Required. |
| `-fast` | Uses fast defaults and enables debug logging. |
| `-rounds N` | Number of evaluation rounds. Defaults to `5` in fast mode, `1` otherwise. |
| `-group-count N` | Number of groups. Use `1` for smoke tests. |
| `-max-agents-per-group N` | Group concurrency cap. |
| `-round-duration-minutes N` | Forced check-in duration. |
| `-budget-override N` | Runtime budget override. |
| `-llm-backend openrouter\|qwen-cloud\|codex-chatgpt` | Select backend. Codex aliases: `codex`, `chatgpt`. |
| `-model ROLE=MODEL` | Repeatable role-specific model override. |
| `-run-id id` | Stable run identifier. |
| `-pipeline-config path` | Multi-stage pipeline config. |
| `-debug` | Emit richer logs. |

## Outputs

CLI runs write a per-run directory under `-output`. Important files include:

| Path | Meaning |
|---|---|
| `summary.json` | Machine-readable run summary. |
| `results.zip` | Results bundle with outputs, replay, logs, selected artifacts, and scoring context. |
| `pipeline-summary.json` | Multi-stage pipeline summary when pipeline mode is used. |
| `pipeline-results.zip` | Packaged pipeline outputs. |
| `outputs/` | Published final files such as `final-article.html`, `report.md`, assets, or fallback outputs. |
| `debug-log.txt` | Debug trace when enabled. |

The archive service also writes replayable artifacts such as `run_replay.html`, claim ledgers,
high-level run logs, and generated outputs inside result bundles.

## Benchmark Runner

Run a small EvoMolior society benchmark:

```bash
./evomolior/cli/build/install/evomolior/bin/evomolior benchmark \
  -benchmark frames \
  -limit 2 \
  -offset 0 \
  -output /tmp/evomolior-benchmark \
  -llm-backend qwen-cloud \
  -qwen-api-key "$QWEN_API_KEY" \
  -fast \
  -group-count 1 \
  -rounds 1 \
  -max-agents-per-group 3 \
  -task-timeout-minutes 15
```

Run a supported single-agent baseline:

```bash
./evomolior/cli/build/install/evomolior/bin/evomolior benchmark \
  -benchmark litqa2 \
  -limit 3 \
  -output /tmp/evomolior-litqa2-single \
  -runner single-agent \
  -qwen-api-key "$QWEN_API_KEY"
```

Single-agent benchmark baselines are intentionally Qwen Cloud-only in the current CLI so they remain
comparable with the maintained baseline ladder. `codex-chatgpt` can be selected for society-mode
benchmark slices, but not for `-runner single-agent`.

Supported benchmark selectors in the current CLI include:

| Selector | Notes |
|---|---|
| `frames` | Google FRAMES loader. |
| `litqa2` | LAB-Bench/LitQA2 loader. |
| `scholarqabench` | QASA by default; variants include `pubmed`, `scifact`, and `cs`. |
| `scholarqa` | Local JSONL mode; requires `-tasks-jsonl`. |
| `ai2-scholar-qa` | Evidence-first local JSONL adapter; requires `-tasks-jsonl`. |
| `autoresearchbench` | Local JSONL discovery mode; requires `-tasks-jsonl`. |

Benchmark reports are written to `tier0-benchmark-report.json` in the selected output directory.

## Current Benchmark Readout

The current evidence is intentionally modest. EvoMolior has small same-backend Qwen Cloud wins on
selected FRAMES slices, ties multiple research-synthesis baselines, and does not yet demonstrate
broad accuracy superiority over a single normal agent.

| Area | Current result |
|---|---|
| Qwen FRAMES `19..21` | Society `2/3` strict exact versus direct Qwen 3.7 Plus `1/3`; both evidence-path coverage `1.0`. |
| Hard Qwen FRAMES `537` | Society `1/1` strict exact versus direct Qwen baseline `0/1`; a one-task case study. |
| LitQA2 smoke | The society result used an OpenRouter model mix while the reusable baseline used Qwen Cloud, so it is not used as a same-backend Qwen comparison. |
| Tier 2 fixed-corpus discovery and Asta-style coverage | Ties supported single-agent baselines on current objective metrics. |
| MetaSyn task 24 and task 419 candidate subsets | Ties direct single-Qwen baselines on objective metrics, with much higher latency. |
| DeepResearch Bench prompt 62 | Artifact-completeness comparison only; official rubric scoring is not wired. |
| Strongest validated gain | Reliability, artifact structure, orchestration health, and auditability rather than broad benchmark dominance. |

See `documentation/benchmarks/EvoMolior_Benchmark_Ladder.md` for the maintained evidence table and
the exact artifact paths behind each claim.

## Desktop Control Panel

Run the JVM desktop app:

```bash
./gradlew :composeApp:run
```

Package native desktop installers:

```bash
./gradlew :composeApp:packageDmg
./gradlew :composeApp:packageMsi
./gradlew :composeApp:packageDeb
```

The desktop target is the operator control panel for configuring runs, monitoring events, inspecting
agents, reading logs, browsing substrate state, viewing scoring, and reviewing post-run analysis.

On macOS, the desktop launcher currently applies `-Xint` because Corretto 17.0.7 has been observed
crashing in HotSpot compiler threads for this app.

## Remote Dashboard

The CLI can serve an authenticated dashboard:

```bash
EVOMOLIOR_DASHBOARD_USERNAME=operator \
EVOMOLIOR_DASHBOARD_PASSWORD='replace-with-a-long-random-password' \
./evomolior/cli/build/install/evomolior/bin/evomolior serve \
  --host 127.0.0.1 \
  --port 8080 \
  --data-dir /tmp/evomolior-dashboard \
  --cli-bin ./evomolior/cli/build/install/evomolior/bin/evomolior
```

Dashboard environment variables:

| Variable | Purpose |
|---|---|
| `EVOMOLIOR_DASHBOARD_USERNAME` | Required dashboard username. |
| `EVOMOLIOR_DASHBOARD_PASSWORD` | Required dashboard password. |
| `EVOMOLIOR_DASHBOARD_HOST` | Optional bind host; default `0.0.0.0`. |
| `EVOMOLIOR_DASHBOARD_PORT` | Optional bind port; default `8080`. |
| `EVOMOLIOR_DASHBOARD_DATA_DIR` | Persistent data root; default `/data`. |
| `EVOMOLIOR_CLI_BIN` | CLI executable used by dashboard subprocess launches; default `evomolior`. |
| `EVOMOLIOR_DASHBOARD_SECURE_COOKIE` | Set `true` when the browser reaches the dashboard over HTTPS. |
| `EVOMOLIOR_DASHBOARD_MAX_CONCURRENT_RUNS` | Concurrent subprocess limit; default `2`, allowed `1..32`. |

Detailed VPS deployment runbook: `documentation/deployment/Remote_CLI_Dashboard_Deployment.md`.

The dashboard is a full run control room rather than a process list. It provides live Server-Sent
Event updates, fleet health and workload charts, strict launch validation and admission control,
run lifecycle timelines, bounded structural logs, benchmark/comparison scorecards, restricted safe
artifact previews/downloads, and an allowlisted metric catalog covering cost, tokens/calls, society
activity, evidence quality, claims, tools, Atlas, evolution, outliers, grading, and model
independence. Metrics that a run did not emit remain visibly unavailable instead of being inferred.
Only files produced under a run's explicit `outputs/` export subtree are downloadable; internal
summaries and reports remain redacted-preview-only.

## Docker

Build the dashboard image:

```bash
docker build -t evomolior-cli-dashboard .
```

Run it locally:

```bash
docker run --rm \
  -p 127.0.0.1:8080:8080 \
  -e EVOMOLIOR_DASHBOARD_USERNAME=operator \
  -e EVOMOLIOR_DASHBOARD_PASSWORD='replace-with-a-long-random-password' \
  -e OPENROUTER_API_KEY="$OPENROUTER_API_KEY" \
  -e QWEN_API_KEY="$QWEN_API_KEY" \
  -v evomolior-data:/data \
  evomolior-cli-dashboard
```

Or use the deployment helper:

```bash
# Fast one-off deployment: build locally and stream the image over SSH.
EVOMOLIOR_CONTAINER_IMAGE=evomolior-cli-dashboard \
EVOMOLIOR_CONTAINER_TAG=latest \
EVOMOLIOR_REMOTE_HOST=203.0.113.10 \
EVOMOLIOR_REMOTE_USER=evomolior \
EVOMOLIOR_DASHBOARD_USERNAME=operator \
EVOMOLIOR_DASHBOARD_PASSWORD='replace-with-a-long-random-password' \
./scripts/remote_cli_container.sh release-direct
```

For a registry-backed release:

```bash
EVOMOLIOR_CONTAINER_IMAGE=registry.example.com/evomolior/cli-dashboard \
EVOMOLIOR_CONTAINER_TAG=latest \
EVOMOLIOR_REMOTE_HOST=203.0.113.10 \
EVOMOLIOR_REMOTE_USER=evomolior \
EVOMOLIOR_DASHBOARD_USERNAME=operator \
EVOMOLIOR_DASHBOARD_PASSWORD='replace-with-a-long-random-password' \
./scripts/remote_cli_container.sh release
```

The Docker image builds `:engine:cli:installDist`, exposes port `8080`, stores dashboard state under
`/data`, and starts `evomolior serve` by default.

## Mobile And Web Targets

The KMP app still has Android, iOS, JS, and Wasm targets for the broader product shell. The
EvoMolior engine itself is JVM-only and is not expected to execute inside Android or iOS runtime
targets.

Common platform commands:

```bash
./gradlew :composeApp:assembleDebug
./gradlew :composeApp:jsBrowserDevelopmentRun
./gradlew :composeApp:wasmJsBrowserDevelopmentRun
```

For iOS, open `iosApp/iosApp.xcproject` in Xcode or use the Kotlin Multiplatform IDE tooling.

## Engine Architecture

| Module | Responsibility |
|---|---|
| `:engine:core` | Domain models, roles, benchmark contracts, configuration, budget and law primitives. |
| `:engine:substrate` | Room/SQLite persistence for KB, KG, staging, tasks, events, trust, dead ends, and protocols. |
| `:engine:agents` | Koog agent factories, role prompts, tool registry, context policy, source tools, and runner behavior. |
| `:engine:sandbox` | MicroVM/tool sandbox integration, constitutional enforcement, and source access policy. |
| `:engine:orchestration` | Run lifecycle, Genesis Council, watchdog, critic pipeline, metaplanner, evolution, nested groups. |
| `:engine:competition` | Multi-group coordination, blind submission gates, graders, scoring, leak detection, dispatch. |
| `:engine:api` | Engine service interface, run config, events, archives, result bundles, IPC-facing contracts. |
| `:engine:runtime` | Production wiring across engine modules. |
| `:engine:cli` | Command-line entry points, benchmark runner, remote dashboard, self-improvement loop. |

High-level dependency shape:

```
core  <- substrate
core  <- agents
core  <- sandbox
core  <- api
substrate <- agents
substrate <- orchestration
substrate <- competition
agents <- orchestration
sandbox <- orchestration
sandbox <- agents
orchestration <- competition
api <- composeApp
runtime -> api + agents + competition + orchestration + sandbox + substrate
cli -> runtime + api
```

## Agent Roles

Current roles are defined in `:engine:core`:

| Family | Roles |
|---|---|
| Civilisational | `Researcher`, `Builder`, `Planner`, `Critic`, `Archivist`, `Educator`, `Connector`, `Outlier` |
| Oversight | `Inspector`, `Auditor`, `PerspectiveTaker`, `MetaPlanner` |
| Genesis Council | `CouncilTaskAnatomist`, `CouncilIncentiveDesigner`, `CouncilEcologist`, `CouncilPromptArchitect`, `CouncilKGArchitect`, `CouncilModelEcologist`, `CouncilSeedCurator`, `CouncilSynthesis` |
| Grader group | `GraderResearcher`, `GraderBuilder`, `GraderCritic` |

Roles determine prompt contract, tool access, model assignment, delegation permissions, legacy
protocol handling, and source/network constraints.

## Agent Tool Surface

Important tool names visible to agents:

| Tool | Purpose |
|---|---|
| `list_kb` | Compact KB title/header index without full bodies. |
| `read_kb` | Selected KB full-entry reads by ID; header-only by default without IDs. |
| `search_kb` | Semantic KB search returning headers by default. |
| `read_kg`, `query_pattern`, `check_consistency` | KG reads, traversal, and fact preflight. |
| `read_run_artifact` | Uploaded run-start context artifacts and summaries. |
| `request_source_access`, `fetch_source` | Approval-first public source acquisition. |
| `claim_task`, `write_staging`, `emit_fact`, `emit_constraint` | Work claiming and staged substrate writes. |
| `send_message`, `read_messages` | Agent-to-agent coordination. |
| `delegate_sub_task` | Bounded sub-agent delegation where role policy allows it. |
| `execute_tool`, `list_tools` | Tool registry interaction. |
| `write_death_note` | Mandatory end-of-life knowledge preservation. |
| `submit_grading_score` | Grader scoring output. |

## Testing And Verification

Common compile/install check:

```bash
./gradlew --no-daemon --no-configuration-cache \
  projects \
  :composeApp:compileKotlinJvm \
  :engine:cli:installDist
```

Focused engine checks:

```bash
./gradlew --no-daemon --no-configuration-cache \
  :engine:core:test \
  :engine:substrate:test \
  :engine:agents:test \
  :engine:sandbox:test \
  :engine:api:test \
  :engine:orchestration:test \
  :engine:competition:test \
  :engine:cli:test
```

For targeted work, prefer module-specific tests with `--tests`, then run the broader set relevant to
the changed modules. Some full-suite behavioral tests may be under active hardening; record exact
failures and do not hide them behind generic “tests passed” language.

## Documentation Index

| File | Use |
|---|---|
| `agents.md` | Root project instructions for agents working in this repo. |
| `documentation/README.md` | Public documentation allowlist and excluded-local-material policy. |
| `documentation/EvoMolior_Roadmap_and_Project_State.md` | Canonical current architecture, roadmap phase status, gaps, and next actions. |
| `evomolior/*/agents.md` | Module-specific design and coding context. |
| `documentation/EvoMolior_Specification_v0.2.html` | Full specification. |
| `documentation/EvoMolior_Implementation_Plan_v1.0.md` | Normative phase definitions and original exit criteria; its status audit is historical. |
| `documentation/EvoMolior_Current_Architecture_Deep_Dive.md` | Maintained detailed architecture reference. |
| `documentation/benchmarks/EvoMolior_Benchmark_Ladder.md` | Current benchmark evidence and limitations. |
| `documentation/deployment/Remote_CLI_Dashboard_Deployment.md` | Remote dashboard deployment runbook. |
| `documentation/opensource/Open_Source_Readiness.md` | Open-source readiness audit. |
| `documentation/submission/README.md` | Judge-facing Qwen Cloud hackathon submission package. |

## Keeping This README Current

Future agents should update `README.MD` in the same change that alters user-visible behavior. Treat
this file as an operator contract, not a static brochure.

Update the README when any of these change:

| If this changes | Update this README area |
|---|---|
| CLI flags, subcommands, defaults, backend names, or output paths | `Quick Start`, `Outputs`, `Benchmark Runner`, `Remote Dashboard`. |
| Gradle module names, source layout, or dependency direction | `Repository Map`, `Engine Architecture`, command examples. |
| Tool names or context/source-access behavior | `What It Does`, `Agent Tool Surface`. |
| Role enum values or role responsibilities | `Agent Roles`. |
| Benchmark results or evidence quality | `Current Benchmark Readout` and the benchmark doc link. |
| Deployment variables, Docker defaults, or dashboard auth | `Remote Dashboard`, `Docker`. |
| Kotlin, Koog, Compose, Ktor, Room, or serialization versions | `Technology`. |
| Known production limitations improve or regress | `Current Status`, `Testing And Verification`. |

README maintenance rules for agents:

- Verify claims against code or current docs before editing; do not infer from memory.
- Keep benchmark language conservative and cite `documentation/benchmarks/EvoMolior_Benchmark_Ladder.md`.
- Keep commands copy-pasteable from the repository root.
- Prefer concrete file paths and module names over broad descriptions.
- Do not add contribution boilerplate, community sections, or generic project fluff unless the user
  explicitly asks for them.
- If a README claim cannot be verified, remove it or mark it as a limitation with the evidence gap.

## License

EvoMolior is licensed under the GNU Affero General Public License v3.0 only (`AGPL-3.0-only`).

See `LICENSE` for the full license text.

## Contributing And Security

Contributions are welcome under the repository license. See `CONTRIBUTING.md` for setup and review
expectations. Please report vulnerabilities privately as described in `SECURITY.md`.
