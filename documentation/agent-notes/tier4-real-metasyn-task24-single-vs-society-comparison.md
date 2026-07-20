# Task 24 Benchmark Comparison — Single Qwen Agent vs EvoMolior Society

Date: 2026-07-05

## Compared Runs

Single-agent baseline:

- Run ID: `tier4-real-metasyn-task24-single-qwen-baseline-20260705`
- Runner: ad hoc one direct Qwen chat-completions call, no EvoMolior society orchestration.
- Model: `qwen3.7-plus`
- Output root: `results/tier4-real-metasyn-task24-health-20260704-complex/tier4-real-metasyn-task24-single-qwen-baseline-20260705`
- Published output: `results/tier4-real-metasyn-task24-health-20260704-complex/tier4-real-metasyn-task24-single-qwen-baseline-20260705/outputs/systematic_review_pack.md`
- Output SHA-256: `412f5bdab660b5efe7ce07ed2d4bad0a9d2e6d3444058b545c2fa084e4adc0ce`
- Duration: `31434` ms.
- Token usage: prompt `4744`, completion `1631`, total `6375`.

EvoMolior society:

- Run ID: `tier4-real-metasyn-task24-complex-health-qwen-seededcriteria-validator-20260705`
- Runner: configured EvoMolior complex society pipeline with Qwen backend.
- Main model family: `qwen3.7-plus` with `deepseek-v4-flash` for auditor assignment.
- Output root: `results/tier4-real-metasyn-task24-health-20260704-complex/tier4-real-metasyn-task24-complex-health-qwen-seededcriteria-validator-20260705`
- Published output: `results/tier4-real-metasyn-task24-health-20260704-complex/tier4-real-metasyn-task24-complex-health-qwen-seededcriteria-validator-20260705/outputs/systematic_review_pack.md`
- Output SHA-256: `9c23b0865781efeaf0fa271e030e961627a8a13b1e5180f05bb36b9b6d42fee6`
- Stage duration: `2070635` ms.
- Winner: `group-1`.

Both runs used input artifact SHA-256:
`327341b8f269fe6a68b76817f814b9084f8dbaeb9edaca4274b324e9ea1d1fc7`.

## Objective Metrics

| Metric | Single Qwen agent | EvoMolior society | Result |
|---|---:|---:|---|
| Included-study recall | `0.6666666666666666` | `0.6666666666666666` | Tie |
| Included-study precision | `1.0` | `1.0` | Tie |
| Hard-negative rejection accuracy | `1.0` | `1.0` | Tie |
| Candidate-level accuracy | `0.875` | `0.875` | Tie |
| Synthesis direction match | `true` | `true` | Tie |
| Included true positives | `4565`, `4566` | `4565`, `4566` | Tie |
| Included false negatives | `4567` | `4567` | Tie |
| Included false positives | none | none | Tie |
| Wall-clock duration | `31.434` seconds | `2070.635` seconds | Single agent faster |
| Runtime ratio | baseline | `65.87x` single-agent duration | Single agent faster |

## Structural Quality

Both outputs made the same core candidate partition:

- Included: `4565`, `4566`
- Excluded: `4558`, `4559`, `4567`, `4568`, `4570`, `4571`

The EvoMolior society output is structurally stronger as a systematic-review pack. Its
`excluded_hard_negatives.csv` table lists all six excluded candidates, and its
`exclusion_reasons.csv` includes multiple criterion-specific rows where appropriate.

The single-agent output includes all eight candidate IDs overall, but its
`excluded_hard_negatives.csv` table lists only `4567`, `4570`, and `4571`. The omitted excluded
candidate IDs `4558`, `4559`, and `4568` are still present in `exclusion_reasons.csv` and
`evidence_table.csv`, so deterministic candidate partition scoring is unaffected, but the table is
less complete against the requested schema.

This is a material limitation of the baseline evidence. It should not be read as a complete
benchmark-formatted single-agent pack. The project's current `benchmark -runner single-agent`
baseline path is wired for Tier-0 benchmark adapters, not the MetaSyn task-24 systematic-review
pipeline, so this run is a direct same-model baseline rather than an execution of the reusable
single-agent benchmark harness.

## Interpretation

The single Qwen baseline is much faster and ties the society run on the deterministic candidate
subset metrics for this task. The EvoMolior society run does not improve recall on the remaining
hard semantic miss `4567`.

The society run does, however, validate the orchestration health work that the single-agent
baseline does not exercise:

- no synthetic grader criteria fallback;
- deterministic seeded criteria available to grader groups;
- no infrastructure fallback scoring;
- RoundJudge score batches collected;
- final output accepted by the stricter systematic-review validator.

For task-24 specifically, the current result is: EvoMolior society is healthier as an orchestration
system and produces a more schema-complete final pack, but it does not beat this ad hoc single
`qwen3.7-plus` agent on objective candidate-subset accuracy and is far slower. A future cleaner
comparison should add MetaSyn task support to the reusable `benchmark -runner single-agent` harness
so the baseline emits the same benchmark report shape and validates the same output schema.

## Tracked Artifacts

- Single-agent predictions:
  `documentation/agent-notes/tier4-real-metasyn-task24-single-qwen-baseline-predictions.json`
- Single-agent evaluation:
  `documentation/agent-notes/tier4-real-metasyn-task24-single-qwen-baseline-evaluation.json`
- Society predictions:
  `documentation/agent-notes/tier4-real-metasyn-task24-health-qwen-seededcriteria-validator-predictions.json`
- Society evaluation:
  `documentation/agent-notes/tier4-real-metasyn-task24-health-qwen-seededcriteria-validator-evaluation.json`

Verification command for the single-agent baseline:

```sh
python3 documentation/agent-notes/evaluate_metasyn_candidate_subset.py \
  --gold documentation/agent-notes/tier4-real-metasyn-task24-gold.json \
  --predictions documentation/agent-notes/tier4-real-metasyn-task24-single-qwen-baseline-predictions.json \
  --verify-files \
  --pretty
```
