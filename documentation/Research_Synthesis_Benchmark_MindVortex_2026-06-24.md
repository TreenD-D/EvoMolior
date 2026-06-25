# Research Synthesis Benchmark: MindVortex External Cognition - 2026-06-24

## Purpose
This document preserves the benchmark workflow and final result outside `documentation/tmp`, so the comparison remains available after tmp cleanup.

The benchmark tested whether EvoKammer's fast CLI path, at sustainable complexity, improved over a modern cheap single-agent pipeline on a cross-domain research-synthesis task.

## Candidate
Candidate 1: synthesize a research-grounded product strategy for MindVortex as an ADHD-friendly external cognition system.

The task required:

- A theory of user failure modes.
- Cross-domain synthesis across ADHD research, executive function, external cognition, HCI, AI cognitive offloading, privacy, ethics, and user pain signals.
- Product architecture, modules, data flows, user controls, and boundary conditions.
- UX principles and anti-patterns.
- A 6-week MVP experiment plan.
- Evaluation metrics.
- Risks, ethical constraints, privacy constraints, and clinical caveats.
- Differentiated positioning.
- Source-grounded evidence notes.

## Systems Compared
- EvoKammer fast CLI: `-fast -rounds 2 -group-count 2 -max-agents 3 -budget-override 160`
- Single-agent baseline: `openai/gpt-oss-120b` through OpenRouter
- Judge: `deepseek/deepseek-v4-flash`, anonymized A/B rubric comparison

## Working Artifacts
- Benchmark plan: `documentation/tmp/research_synthesis_benchmark/BENCHMARK_PLAN.md`
- Source pack: `documentation/tmp/research_synthesis_benchmark/source_pack/research_synthesis_source_pack.md`
- Task prompt: `documentation/tmp/research_synthesis_benchmark/prompts/benchmark_task_prompt.txt`
- Rubric: `documentation/tmp/research_synthesis_benchmark/prompts/evaluation_rubric.md`
- Detailed results: `documentation/tmp/research_synthesis_benchmark/RESULTS.md`
- Evaluation JSON: `documentation/tmp/research_synthesis_benchmark/evaluations/latest-evaluation.json`

## Run Artifacts
- EvoKammer run id: `benchmark-evokammer-mindvortex-strategy-20260624-102002`
- EvoKammer output: `documentation/tmp/research_synthesis_benchmark/runs/benchmark-evokammer-mindvortex-strategy-20260624-102002/outputs/refined/final-article-refined.html`
- Single-agent run id: `single-agent-openai_gpt-oss-120b-20260624-102108`
- Single-agent output: `documentation/tmp/research_synthesis_benchmark/baselines/single-agent-openai_gpt-oss-120b-20260624-102108/mindvortex-adhd-external-cognition-strategy.html`

## Structural Metrics
- EvoKammer: 14,087 bytes, about 1,263 visible words, 24 `h1`-`h3` headings, 0 tables, footer present, 43 source-signal mentions.
- Single-agent: 15,363 bytes, about 1,431 visible words, 20 `h1`-`h3` headings, 2 tables, footer absent, 40 source-signal mentions.

## Blind Judge Result
Blind mapping:

- Output A: single-agent baseline
- Output B: EvoKammer

Scores:

| Dimension | Single-agent | EvoKammer |
|---|---:|---:|
| Evidence fidelity | 5 | 2 |
| Cross-domain synthesis | 5 | 3 |
| Novelty and usefulness | 5 | 3 |
| Operationalization | 5 | 3 |
| ADHD fit and ethics | 5 | 4 |
| Clarity and artifact quality | 5 | 4 |
| Total | 30 | 19 |

Judge winner: single-agent baseline.

## Conclusion
This benchmark did **not** show EvoKammer outperforming the cheap single-agent baseline. The single-agent output was more source-grounded and operationally specific. EvoKammer completed the task and produced a coherent artifact, but the result had weaker evidence fidelity and less concrete operational detail.

Runtime signals also indicate fast-path fragility that likely reduced output quality:

- Primary log: `warnings=141`, `errors=10`, `json_decode_errors=3`, `no_tool=36`, `neutral_fallback=10`, `final_published=1`, `final_no_output_retry=1`.
- Fast follow-up log: `warnings=85`, `errors=0`, `timeout=4`, `no_tool=21`, `neutral_fallback=6`, `final_published=1`, `final_no_output_retry=2`.

## Recommended Next Iteration
- Require source-fidelity annotations in the final synthesis path.
- Improve or bypass unreliable internal grader behavior before using it as a quality signal.
- Compare against a stronger single-agent draft-critique-revise baseline, not only one-shot output.
- Re-run the same benchmark after source-fidelity and final-synthesis improvements using the same source pack and rubric.

## Key Handling Note
The OpenRouter key had previously appeared in tool output. The user clarified it is a small-credit limited test key and acceptable. Current local check showed `local.properties` contains a present test-key-format value without printing it.
