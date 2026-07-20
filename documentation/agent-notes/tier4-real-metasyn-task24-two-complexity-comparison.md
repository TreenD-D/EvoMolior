# Tier 4 MetaSyn Task 24 Two-Complexity Comparison

Date: 2026-07-04

## Question

Run a different benchmark task from task 419 with two different-complexity EvoMolior profiles and
evaluate whether the more complex profile improves objective candidate-subset metrics.

## Benchmark Task

- Dataset: `THUIR/MetaSyn`
- Official test task ID: `24`
- Scoring scope: candidate subset with all matched positive corpus IDs available in the public task
  row.
- Gold included candidate IDs: `4565`, `4566`, `4567`
- Gold hard negatives: `4558`, `4559`, `4568`, `4570`, `4571`
- Important limitation: task 24 has `study_count=5` but only three matched corpus IDs in the
  accessible public task metadata.

## Runs

Simple profile:

- Run ID: `tier4-real-metasyn-task24-simple-complexity-v2-20260704`
- Output root:
  `results/tier4-real-metasyn-task24-two-complexity-20260704-simple-v2/tier4-real-metasyn-task24-simple-complexity-v2-20260704`
- Published output:
  `results/tier4-real-metasyn-task24-two-complexity-20260704-simple-v2/tier4-real-metasyn-task24-simple-complexity-v2-20260704/outputs/systematic_review_pack.md`
- Output SHA-256: `4a5bf875dde51293898752a65e014691e4ad3be065ad1eee9131781bfd69b044`
- Stage profile: `groupCount=1`, `rounds=1`, `maxAgentsPerGroup=3`,
  `roundDurationMinutes=2`, `budgetOverride=120`
- Primary duration: `74021 ms`
- Systematic-review-pack duration: `171726 ms` (`2.8621` minutes)
- Stage winner: `group-0`

Complex profile:

- Run ID: `tier4-real-metasyn-task24-complex-complexity-20260704`
- Output root:
  `results/tier4-real-metasyn-task24-two-complexity-20260704-complex/tier4-real-metasyn-task24-complex-complexity-20260704`
- Published output:
  `results/tier4-real-metasyn-task24-two-complexity-20260704-complex/tier4-real-metasyn-task24-complex-complexity-20260704/outputs/systematic_review_pack.md`
- Output SHA-256: `0f69cabfdff94da877834f5d0be85c387f4edc606a25df89534f0c5f220f34f5`
- Stage profile: `groupCount=2`, `rounds=3`, `maxAgentsPerGroup=5`,
  `roundDurationMinutes=3`, `budgetOverride=320`
- Primary duration: `84913 ms`
- Systematic-review-pack duration: `1513902 ms` (`25.2317` minutes)
- Stage winner: `group-0`

Input candidate file SHA-256:
`327341b8f269fe6a68b76817f814b9084f8dbaeb9edaca4274b324e9ea1d1fc7`.

## Metrics

| Metric | Simple profile | Complex profile | Rank result |
|---|---:|---:|---|
| Included-study recall | `0.6666666666666666` | `0.6666666666666666` | Tie |
| Included-study precision | `1.0` | `1.0` | Tie |
| Hard-negative rejection accuracy | `1.0` | `1.0` | Tie |
| Candidate-level accuracy | `0.875` | `0.875` | Tie |
| Synthesis direction match | `true` | `true` | Tie |
| False negative IDs | `4567` | `4567` | Tie |
| Stage wall-clock minutes | `2.8621` | `25.2317` | Simple better |

## Interpretation

Both profiles made the same core screening decision: include `4565` and `4566`, exclude official
matched positive `4567`, and reject all hard negatives. The complex run therefore did not improve
objective candidate-subset metrics. It took about `8.82x` longer for the systematic-review-pack
stage.

The false negative is the same historical task-24 ambiguity: `4567` is an official matched positive,
but the output judged that its abstract did not provide a separate diabetic-vs-non-diabetic
comparator outcome.

## Artifact Quality Notes

- The simple output populated included, excluded, exclusion-reason, pooled-conclusion, and evidence
  sections.
- The complex output listed only `4567`, `4558`, and `4559` in `excluded_hard_negatives.csv` and
  only included studies in `evidence_table.csv`; however, it explicitly excluded `4568`, `4570`,
  and `4571` in `exclusion_reasons.csv`.
- The candidate partition for the complex run was scored from all explicit include/exclude
  decisions in the persisted Markdown output.
- Both runs had warning fallbacks in logs, including no-action fallbacks and missing MetaJudge
  verdict fallbacks. The complex profile had many more grader warning fallbacks.
- An earlier simple-profile attempt
  `tier4-real-metasyn-task24-simple-complexity-20260704` aborted because the first duplicate-row
  validator incorrectly treated the same candidate ID across different required CSV tables as a
  duplicate. That validator was fixed to check duplicates within each fenced CSV block only, then
  the simple profile was rerun as `v2`.

## Tracked Artifacts

- Gold labels:
  `documentation/agent-notes/tier4-real-metasyn-task24-gold.json`
- Simple predictions:
  `documentation/agent-notes/tier4-real-metasyn-task24-simple-complexity-predictions.json`
- Simple evaluation:
  `documentation/agent-notes/tier4-real-metasyn-task24-simple-complexity-evaluation.json`
- Complex predictions:
  `documentation/agent-notes/tier4-real-metasyn-task24-complex-complexity-predictions.json`
- Complex evaluation:
  `documentation/agent-notes/tier4-real-metasyn-task24-complex-complexity-evaluation.json`

Verification commands:

```sh
python3 documentation/agent-notes/evaluate_metasyn_candidate_subset.py \
  --gold documentation/agent-notes/tier4-real-metasyn-task24-gold.json \
  --predictions documentation/agent-notes/tier4-real-metasyn-task24-simple-complexity-predictions.json \
  --verify-files \
  --pretty

python3 documentation/agent-notes/evaluate_metasyn_candidate_subset.py \
  --gold documentation/agent-notes/tier4-real-metasyn-task24-gold.json \
  --predictions documentation/agent-notes/tier4-real-metasyn-task24-complex-complexity-predictions.json \
  --verify-files \
  --pretty
```
