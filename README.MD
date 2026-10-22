# EvoMolior

**Civilisational multi-agent runtime for evidence-heavy work.**

EvoMolior runs short-lived agents against a persistent substrate. Agents can die while reviewed
knowledge, graph facts, tools, evidence, scores, dead ends, and run archives remain available to
later work.

The system explores when specialised groups, governed tools, and explicit evaluation improve or
audit difficult work. It records failures and unavailable measurements instead of treating every
completed run as a success claim.

## What It Does

- Runs competing agent groups over shared objectives and input artifacts; dedicated benchmark
  and internal diagnostic interfaces also support single-group workflows.
- Persists group-isolated Knowledge Bases, Knowledge Graphs, tools, staging, messages, trust, and
  run history in Room/SQLite.
- Uses role-gated tools, source approval, bounded subprocesses, Critic review, and Death Notes.
- Compiles typed run authority, staged disclosure, grading, publication, replay, and manifests.
- Exposes CLI, benchmark, authenticated remote-dashboard, Compose Desktop, Docker, and KMP targets.

The canonical architecture and roadmap status are maintained in
[`documentation/EvoMolior_Roadmap_and_Project_State.md`](documentation/EvoMolior_Roadmap_and_Project_State.md).

## Repository Map

```text
composeApp/       Compose Multiplatform app and desktop control panel
designsystem/     shared Compose design tokens and components
evomolior/
  core/           domain models, contracts, budgets, and laws
  substrate/      Room/SQLite persistent substrate
  agents/         Koog agent factories, prompts, tools, and runners
  orchestration/  lifecycle, Council, selection, and evolution
  competition/    groups, grading, scoring, and disclosure
  sandbox/        Shuru and bounded tool execution
  api/            engine services, archives, and IPC-facing models
  runtime/        production runtime composition
  cli/            command-line runner, benchmarks, and dashboard
libs/             authentication and subscription abstractions
documentation/   maintained architecture, specification, benchmark, and deployment docs
```

## Technology

| Concern | Stack |
|---|---|
| Language/build | Kotlin `2.2.21`, Gradle, Kotlin Multiplatform |
| Agent runtime | JetBrains Koog `1.0.0` |
| LLM backends | OpenRouter, optional Qwen Cloud/DashScope, and local Codex/ChatGPT text-only execution |
| UI | Compose Multiplatform `1.10.0-rc01` |
| Persistence | Room `2.8.3` with SQLite |
| Server/dashboard | Ktor `3.3.1` |
| Isolation target | Shuru microVMs on macOS/Apple Virtualization paths |

## Prerequisites

- JDK 17.
- Git.
- One configured provider:
  - `OPENROUTER_API_KEY` for OpenRouter;
  - `QWEN_API_KEY` for optional Qwen Cloud;
  - a local Codex CLI session for `codex-chatgpt`.
- Optional `local.properties` containing the same key names.

## Quick Start

Build the CLI distribution:

```bash
./gradlew :engine:cli:installDist
./evomolior/cli/build/install/evomolior/bin/evomolior --help
```

Create a small input:

```bash
mkdir -p /tmp/evomolior-input /tmp/evomolior-output
printf 'Use grounded claims and produce a concise decision memo.' \
  > /tmp/evomolior-input/instructions.txt
```

Run with OpenRouter:

```bash
./evomolior/cli/build/install/evomolior/bin/evomolior run \
  -promptfile /tmp/evomolior-input/instructions.txt \
  -input /tmp/evomolior-input \
  -output /tmp/evomolior-output \
  -llm-backend openrouter \
  -openrouter-api-key "$OPENROUTER_API_KEY" \
  -rounds 1 \
  -group-count 2 \
  -max-agents-per-group 1
```

Qwen Cloud remains available as an alternate provider:

```bash
./evomolior/cli/build/install/evomolior/bin/evomolior run \
  -promptfile /tmp/evomolior-input/instructions.txt \
  -input /tmp/evomolior-input \
  -output /tmp/evomolior-output \
  -llm-backend qwen-cloud \
  -qwen-api-key "$QWEN_API_KEY" \
  -rounds 1 \
  -group-count 2
```

Codex/ChatGPT is a local, text-only compatibility backend:

```bash
./evomolior/cli/build/install/evomolior/bin/evomolior gptauth login
./evomolior/cli/build/install/evomolior/bin/evomolior run \
  -promptfile /tmp/evomolior-input/instructions.txt \
  -input /tmp/evomolior-input \
  -output /tmp/evomolior-output \
  -llm-backend codex-chatgpt \
  -codex-preset full-luna \
  -rounds 1 \
  -group-count 2
```

Use `evomolior run --help` for the complete flag set. Important flags include
`-llm-backend`, `-model ROLE=MODEL`, `-rounds`, `-group-count`,
`-max-agents-per-group`, `-budget-override`, `-run-id`, and `-pipeline-config`.

Codex offers `full-luna` and `mixed-families` presets with role-based reasoning limited to
low/medium/high. Start with full Luna; see
[preset roles, budgets, and comparison setup](documentation/configuration/Codex_Runtime_Presets.md).

CLI runs use the Council and require at least two competing groups, including when `-fast` is
supplied. That flag does not enable a one-group diagnostic run or bypass Council startup.
Follow-up stages run only when configured explicitly; requesting a browser deliverable does not
silently add another stage.

Before any provider call, `run` checks free space on the output volume, the JVM temp directory
(the launcher binds `java.io.tmpdir` to `TMPDIR`) and, for Codex, `EVOMOLIOR_CODEX_TMPDIR`. It
refuses to start when a volume has less than 2 GiB available; `EVOMOLIOR_MIN_FREE_DISK_MB` changes
the floor and `0` disables the check. Each round start re-checks and logs a warning under the floor.

Re-running with an existing `-run-id` reuses a completed stage only when its prompt, input-file
bytes, output contract, and run/model configuration match the stored request fingerprint. A
mismatch is refused instead of returning the earlier run's output; use a new `-run-id`.

Pipeline stage configs may set `ordinaryAgentTtlSeconds` (60–3600, default 600) to bound each
ordinary worker's lifetime. It limits time only, not credits or provider spend.

`-grader-groups 1|2|3` (pipeline field `graderGroupCount`) chooses how many independent grader
civilisations score a normal run. The default, three, maximises evaluation independence; fewer
groups cut grading calls and time roughly proportionally. Acceptance profiles always use three.

## Application Templates

Ready-made pipeline configs cover the applications EvoMolior fits best. Each pairs a prompt
scaffold with a deterministic output contract (sections, word floor and ceiling, URL hygiene,
access dates, or JSON shape), so a malformed or silently condensed deliverable fails validation
instead of being published as if it met the request.

```bash
./evomolior/cli/build/install/evomolior/bin/evomolior template list
```

```bash
./evomolior/cli/build/install/evomolior/bin/evomolior template init research-dossier ./my-dossier
```

| Template | Deliverable |
|---|---|
| `research-dossier` | Evidence-graded landscape with VERIFIED/DISCOVERY/GAP status per entry |
| `systematic-screening` | Criteria-driven inclusion/exclusion with a per-decision audit trail |
| `code-audit` | Cited findings, proposed changes, and a validation plan for uploaded code |
| `evaluation-rubric` | Machine-readable rubric, mechanical checks, and failure modes (JSON) |
| `decision-memo` | Short evidence-backed recommendation comparing options |

Edit the generated `prompt.md`, then run with `-pipeline-config <dir>/pipeline.json`.

## Outputs

Each run writes a directory below `-output`. Common artifacts include:

- `summary.json`;
- `results.zip` with selected outputs, replay, logs, and scoring context;
- `outputs/` containing published deliverables;
- run metrics, manifests, and archive metadata when available.

## Benchmarks

The benchmark runner supports maintained benchmark slices. Society-mode runs can select supported
backends; the current single-agent baseline path is Qwen Cloud-only:

```bash
./evomolior/cli/build/install/evomolior/bin/evomolior benchmark \
  -benchmark frames \
  -limit 2 \
  -output /tmp/evomolior-benchmark \
  -llm-backend openrouter \
  -openrouter-api-key "$OPENROUTER_API_KEY" \
  -group-count 1 \
  -rounds 1
```

Use Qwen Cloud by selecting `-llm-backend qwen-cloud` and supplying `QWEN_API_KEY`. Benchmark
results are historical, bounded observations; compare frozen inputs, runner shape, provider,
sample size, latency, and unavailable metrics before drawing conclusions.

See [`documentation/benchmarks/EvoMolior_Benchmark_Ladder.md`](documentation/benchmarks/EvoMolior_Benchmark_Ladder.md)
for the maintained evidence ledger and limitations.

## Desktop And Dashboard

Run the JVM desktop control panel:

```bash
./gradlew :composeApp:run
```

The authenticated remote dashboard and Docker deployment are documented in
[`documentation/deployment/Remote_CLI_Dashboard_Deployment.md`](documentation/deployment/Remote_CLI_Dashboard_Deployment.md).

## Documentation

- [`documentation/EvoMolior_Roadmap_and_Project_State.md`](documentation/EvoMolior_Roadmap_and_Project_State.md) —
  canonical status, gaps, and next actions.
- [`documentation/EvoMolior_Current_Architecture_Deep_Dive.md`](documentation/EvoMolior_Current_Architecture_Deep_Dive.md) —
  detailed subsystem and provider reference.
- [`documentation/EvoMolior_Specification_v0.2.html`](documentation/EvoMolior_Specification_v0.2.html) —
  product specification and design intent.
- [`documentation/EvoMolior_Implementation_Plan_v1.0.md`](documentation/EvoMolior_Implementation_Plan_v1.0.md) —
  normative behavior and original exit criteria.
- [`documentation/README.md`](documentation/README.md) — documentation surface and local-material policy.
- [`documentation/opensource/Open_Source_Readiness.md`](documentation/opensource/Open_Source_Readiness.md) —
  licensing and release hygiene.

## Development

Run focused engine tests:

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

Provider-backed tests are opt-in and must never print or persist credentials. See
[`CONTRIBUTING.md`](CONTRIBUTING.md) and [`SECURITY.md`](SECURITY.md).

## License

EvoMolior is licensed under the GNU Affero General Public License v3.0 only
(`AGPL-3.0-only`). See [`LICENSE`](LICENSE).
