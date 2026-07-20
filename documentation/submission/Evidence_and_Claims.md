# Evidence And Claim Boundaries

This is the compact benchmark and architecture claim ledger for judges.

## Safe Headline

> EvoMolior is a Qwen Cloud agent-society runtime that makes multi-agent work auditable. On two
> small Qwen Cloud FRAMES comparisons it achieved higher strict exact-answer accuracy than a direct
> Qwen 3.7 Plus agent, while research-synthesis comparisons tied and the society was materially
> slower.

## Qwen Cloud Comparative Evidence

Sanitized machine-readable evidence:
[`evidence/qwen-frames-comparison.json`](evidence/qwen-frames-comparison.json).

| Comparison | EvoMolior society | Direct Qwen baseline | Verdict |
|---|---:|---:|---|
| FRAMES `19..21` | `2/3` exact; evidence path `1.0`; no failures | `1/3` exact; evidence path `1.0`; no failures | Society +1 exact answer |
| Hard FRAMES `537` | `1/1` exact; evidence path `1.0` | `0/1` exact; evidence path `1.0` | Society win on one task |
| Hard FRAMES `444` | `0/1` | `0/1` | Tie |
| MetaSyn task 24 subset | recall `0.6667`; precision `1.0`; accuracy `0.875` | identical | Tie; society much slower |
| MetaSyn task 419 subset | recall `0.5`; precision `1.0`; accuracy `0.8571` | identical | Tie |
| DeepResearch Bench prompt 62 | required report present | required report present | Artifact completeness only; unscored |

### FRAMES `19..21`

- Society: `qwen3.7-plus` assignments, `2/3`, exact accuracy `0.6666666666666666`.
- Baseline: direct `qwen3.7-plus`, `1/3`, exact accuracy `0.3333333333333333`.
- Both: evidence-path coverage `1.0`, no task failures.
- Approximate wall clock: society `501.7 s`; baseline `26.8 s`.
- Archived provider cost: unavailable. Do not infer a cost advantage.

### Hard FRAMES `537`

- Society: `1/1` exact.
- Baseline: `0/1` exact.
- Sample size: one. Treat it as a case study, not an aggregate result.

## Architecture Evidence In The Pending Release

The current worktree adds or retains:

- typed `GeneralTestDefinitionV1`, pure `GeneralTestCompiler`, and `sealed-tests-v3`;
- pre-activation exact test compilation and contextual execution;
- separate Worker and Grader Atlas authority;
- redacted worker views and exact grader snapshots;
- exact evidence, grading, capture, disclosure, Outlier, tool, simulation, cost, and final-output
  lineage;
- typed `run-metrics-v1.json` with `AVAILABLE`, `UNAVAILABLE`, and `NOT_APPLICABLE` states;
- deterministic score replay and hash-verifiable run manifests;
- immutable acceptance distributions, an OS-level run lock, admission checks, and startup rejection
  artifacts;
- full authoritative final-output validation inside the bounded retry loop.

The successful short representative post-v72 archive proves the repaired runtime ABI can compile
and execute non-zero sealed tests and publish a verified receiver artifact. The full six-round
acceptance is still being finalized. The next matched comparison is deliberately deferred.

## Claims We Do Not Make

- broad superiority over a strong single agent;
- lower cost or better cost efficiency;
- an official DeepResearch Bench score;
- universal microVM isolation for normal agents;
- automatic learning across unrelated tasks;
- factual correctness guaranteed by publication validation;
- full multi-round post-v72 Appendix-B acceptance before that archive actually passes;
- independence between graders that use the same provider/model family.

## Why This Is Still A Strong Agent Society Submission

Track fit does not depend on pretending that agent count is a quality metric. EvoMolior demonstrates:

- distinct capabilities and role allocation;
- within-group collaboration and cross-group competition;
- dialogue through messages, staging, dispatches, leaks, critique, and round feedback;
- disagreement resolution through Critic, exact graders, quorum, and MetaJudge authority;
- measurable same-backend comparisons;
- explicit evidence of where society helps, ties, or regresses.
