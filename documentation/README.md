# Public Documentation

This directory contains the maintained public documentation surface. Local run results and internal
engineering records remain in the worktree but are excluded from public Git.

## Maintained Documents

| Document | Purpose |
|---|---|
| [`EvoMolior_Roadmap_and_Project_State.md`](EvoMolior_Roadmap_and_Project_State.md) | Sole current authority for architecture status, roadmap state, gaps, and next actions |
| [`EvoMolior_Current_Architecture_Deep_Dive.md`](EvoMolior_Current_Architecture_Deep_Dive.md) | Detailed subsystem, lifecycle, and provider reference |
| [`EvoMolior_General_Purpose_Improvement_Plan.md`](EvoMolior_General_Purpose_Improvement_Plan.md) | General-purpose delivery review and proposed improvement sequence |
| [`EvoMolior_Log_Driven_Review_2026-10-11.md`](EvoMolior_Log_Driven_Review_2026-10-11.md) | Dated review of the October 2026 run logs: defects, implemented fixes, open decisions, and best-fit applications |
| [`configuration/Codex_Runtime_Presets.md`](configuration/Codex_Runtime_Presets.md) | Full-Luna control, mixed-family roles, reasoning ceilings, and budget boundaries |
| [`configuration/Report_Validation_and_Run_Diagnostics.md`](configuration/Report_Validation_and_Run_Diagnostics.md) | Non-blocking source checks, candidate validation, grading attribution, and credit telemetry |
| [`EvoMolior_Specification_v0.2.html`](EvoMolior_Specification_v0.2.html) | Product specification and design intent |
| [`EvoMolior_Implementation_Plan_v1.0.md`](EvoMolior_Implementation_Plan_v1.0.md) | Normative behavior and original exit criteria; its status audit is historical |
| [`benchmarks/EvoMolior_Benchmark_Ladder.md`](benchmarks/EvoMolior_Benchmark_Ladder.md) | Historical benchmark observations, methodology, and claim boundaries |
| [`deployment/Remote_CLI_Dashboard_Deployment.md`](deployment/Remote_CLI_Dashboard_Deployment.md) | Remote CLI/dashboard deployment runbook |
| [`opensource/Open_Source_Readiness.md`](opensource/Open_Source_Readiness.md) | Licensing and public-release hygiene review |

## Bounded Historical Documents

These documents explain design evolution or preserve implementation context. Their status headers
and the canonical roadmap determine how they may be interpreted:

- `EvoMolior_Alignment_Plan.md`
- `EvoMolior_Gap_Matrix.md`
- `EvoMolior_Competitive_Progressive_Disclosure_Action_Plan-1.html`
- `EvoMolior_Competitive_Progressive_Disclosure_Action_Plan_v1.1_agentsdiff.html`

## Excluded Local Material

The following paths are intentionally preserved locally and excluded from public Git:

- `documentation/agent-notes/` — active goal logs, audits, benchmark evaluations, and helper scripts;
- `documentation/development/` — internal plans, prompts, historical reports, databases, and run
  outputs;
- `documentation/logs/`, `documentation/tmp/`, and `documentation/stale/` — generated or superseded
  material;
- `documentation/prompts/`, `documentation/examples/`, and temporary scratch inputs;
- `documentation/Remediation_plan_after_v72.md` — local acceptance-packaging input with historical
  status and private evidence references;
- root `results/`, `runs/`, `output/`, and `outputs/` — retained local run artifacts.

Do not delete run-result directories as part of public-release cleanup. Curate the Git index while
leaving local bytes intact.
