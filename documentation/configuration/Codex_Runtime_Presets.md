# Codex runtime presets

Presets are opt-in for ordinary CLI runs. Omitting `-codex-preset` preserves legacy
all-Luna assignments and uniform reasoning effort. `full-luna` is the baseline
control; `mixed-families` selects the core role policy. Both named presets apply
role-based reasoning bounded by `-codex-reasoning-effort low|medium|high` (default
`high`, CLI taking precedence over the effort environment variable). Higher effort
names are rejected. Presets do not increase group counts, agent counts or budgets.

| Roles | Mixed model policy | Reasoning tier before the run ceiling |
|---|---|---|
| Council, graders, MetaPlanner, Auditor | Astra | High |
| Planner, PerspectiveTaker | 50% Sol / 50% Terra | Medium |
| Critic | 50% Sol / 50% Terra | High |
| Inspector | 50% Sol / 50% Terra | Low |
| Researcher, Builder | Luna | Medium |
| Archivist, Educator, Connector | Luna | Low |
| Outlier | 85% Luna / 7% Sol / 7% Terra / 1% Astra | Medium |

The weights apply to existing role spawns, not a guaranteed quota per small run.
Council and grading utility calls use their assigned high-level models; the Critic utility uses
the primary Critic model, and leak transformation uses Luna/low. Full Luna substitutes Luna for
every model while keeping these same reasoning tiers. `high` is the ceiling, not the effort of
every call; a `medium` ceiling also lowers judges to medium.

No preset disables monetary enforcement or increases worker budgets. Codex subscription usage is
not a known per-token USD tariff here: provider cost availability remains explicit in the existing
ledger. Do not treat budget points as a promise of a particular subscription charge, or silently
enable iso-latency mode to bypass unknown-cost checks.

Explicit `-model Role=model-id` overrides must belong to the selected preset's
supported models. No default single-model override map is injected for a preset;
Council role pools retain their authority, including in fast mode.

The current CLI constructs production runs even with `-fast`: at least two worker groups are
required, and Council startup is not bypassed. A one-group invocation was rejected before
activation in the September 8, 2026 live trial. Do not use `-fast` as a single-agent smoke shortcut.

Pipeline JSON uses `"codexRuntimePreset": "full-luna"` or `"mixed-families"`.
Primary and subsequent stages inherit the CLI preset and effort unless explicitly
configured. Omitted/null stage presets inherit; null is not an opt-out sentinel.

Presets require `codex-chatgpt`. Explicit acceptance profiles allow only the
legacy null preset or `full-luna`; mixed-family qualification is rejected.
Comparative benchmarks reject this flag and keep their existing semantics.

## Baseline-first setup

The October 1, 2026 local CLI check found Codex 0.159.2 bundled at
`/Applications/ChatGPT.app/Contents/Resources/codex-cli/bin/codex`. Discovery checks that
layout before the older ChatGPT/Codex app paths, while explicit operator paths remain first.
Set `CODEX_HOME` to an external private directory when internal-disk writes are prohibited;
CLI authentication reads/writes honor that same home. Preset model identities do not change
automatically when a newer catalog adds models.

Commands below are instructions for later operator-approved execution. No live run is started
by selecting or building a preset. From the repository root:

```sh
./gradlew :engine:cli:installDist
export EVOMOLIOR_CODEX_CLI="/absolute/path/to/codex"
evomolior/cli/build/install/evomolior/bin/evomolior gptauth status
```

Only after choosing the objective and resource bounds and separately approving a live run,
start the production baseline. Replace each placeholder with an approved value:

```sh
evomolior/cli/build/install/evomolior/bin/evomolior \
  -llm-backend codex-chatgpt \
  -codex-preset full-luna \
  -codex-reasoning-effort high \
  -rounds "<round-limit>" \
  -group-count "<group-count-at-least-2>" \
  -max-agents-per-group "<agent-limit>" \
  -budget-override "<approved-budget>" \
  -prompt "<objective>" \
  -input "/absolute/path/to/input" \
  -output "/absolute/path/to/output"
```

Start with the baseline; do not launch mixed-family runs automatically. For a
later separately approved comparison, keep the objective, inputs, effort ceiling
and resource bounds fixed, use a separate output directory, and change only
`-codex-preset` to `mixed-families`. This is not the formal comparative benchmark.
