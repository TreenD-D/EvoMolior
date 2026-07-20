# Public Documentation

This directory has a deliberately small public surface. Local run results and internal engineering
records remain in the worktree, but Git ignores them.

## Maintained Public Documents

| Document | Purpose |
|---|---|
| [`EvoMolior_Roadmap_and_Project_State.md`](EvoMolior_Roadmap_and_Project_State.md) | Sole current authority for architecture status, roadmap state, gaps, and next actions |
| [`EvoMolior_Current_Architecture_Deep_Dive.md`](EvoMolior_Current_Architecture_Deep_Dive.md) | Detailed maintained subsystem reference |
| [`EvoMolior_Specification_v0.2.html`](EvoMolior_Specification_v0.2.html) | Product specification and design intent |
| [`EvoMolior_Implementation_Plan_v1.0.md`](EvoMolior_Implementation_Plan_v1.0.md) | Normative behavior and original exit criteria; its status audit is historical |
| [`benchmarks/EvoMolior_Benchmark_Ladder.md`](benchmarks/EvoMolior_Benchmark_Ladder.md) | Sanitized public benchmark evidence and claim boundaries |
| [`deployment/Remote_CLI_Dashboard_Deployment.md`](deployment/Remote_CLI_Dashboard_Deployment.md) | Remote CLI/dashboard deployment runbook |
| [`opensource/Open_Source_Readiness.md`](opensource/Open_Source_Readiness.md) | Public-release and licensing review |
| [`submission/README.md`](submission/README.md) | Qwen Cloud hackathon submission package |

## Bounded Historical Documents

These are retained because they explain design evolution or remain build/acceptance inputs. Their
own status headers and the canonical roadmap determine how they may be interpreted.

- `EvoMolior_Alignment_Plan.md`
- `EvoMolior_Gap_Matrix.md`
- `EvoMolior_Competitive_Progressive_Disclosure_Action_Plan-1.html`
- `EvoMolior_Competitive_Progressive_Disclosure_Action_Plan_v1.1_agentsdiff.html`

## Excluded Local Material

The following paths are intentionally preserved locally and excluded from public Git:

- `documentation/agent-notes/` — active goal logs, audits, benchmark evaluations, and helper scripts;
- `documentation/development/` — internal plans, prompts, historical reports, databases, and run
  outputs;
- `documentation/logs/`, `documentation/tmp/`, `documentation/stale/` — generated or superseded
  material;
- `documentation/prompts/`, `documentation/examples/`, `documentation/temp_presub.md` — private
  inputs and submission scratch;
- `documentation/Remediation_plan_after_v72.md` — local acceptance-packaging input with stale
  remediation status and private evidence references;
- root `results/`, `runs/`, `output/`, and `outputs/` — retained local run artifacts.

Do not delete run-result directories as part of public-release cleanup. Curate the Git index so
these ignored paths are untracked, while leaving their local bytes intact.
