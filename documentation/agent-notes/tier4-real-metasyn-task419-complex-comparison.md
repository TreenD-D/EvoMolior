# Tier 4 MetaSyn Task 419 Simple vs Complex Comparison

Date: 2026-07-04

## Question

Does a more complex EvoMolior run with more groups and rounds rank better against the same benchmark
task?

## Benchmark Task

- Dataset: `THUIR/MetaSyn`
- Official test task ID: `419`
- Scoring scope: candidate subset with all matched positive corpus IDs available in the public task
  row.
- Gold included candidate IDs: `7016`, `7017`
- Gold hard negatives: `7014`, `7015`, `7008`, `7012`, `7018`

## Runs Compared

Simple baseline:

- Run ID: `tier4-real-metasyn-task419-20260704`
- Output root: `results/tier4-real-metasyn-task419-20260704-run/tier4-real-metasyn-task419-20260704`
- Benchmark stage shape: `groupCount=1`, `rounds=2`, `maxAgentsPerGroup=4`,
  `roundDurationMinutes=3`, `budgetOverride=180`
- Benchmark stage duration: `324465 ms`
- Cost report wall clock: `5.4068` minutes
- Winner: `group-0`

Complex run:

- Run ID: `tier4-real-metasyn-task419-complex-20260704`
- Output root:
  `results/tier4-real-metasyn-task419-20260704-complex/tier4-real-metasyn-task419-complex-20260704`
- Benchmark stage shape: `groupCount=2`, `rounds=3`, `maxAgentsPerGroup=5`,
  `roundDurationMinutes=3`, `budgetOverride=320`
- Benchmark stage duration: `1221602 ms`
- Cost report wall clock: `20.358766666666668` minutes
- Winner: `group-1`

## Objective Benchmark Metrics

| Metric | Simple baseline | Complex run | Rank result |
|---|---:|---:|---|
| Included-study recall | `0.5` | `0.5` | Tie |
| Included-study precision | `1.0` | `1.0` | Tie |
| Hard-negative rejection accuracy | `1.0` | `1.0` | Tie |
| Candidate-level accuracy | `0.8571428571428571` | `0.8571428571428571` | Tie |
| Synthesis direction match | `true` | `true` | Tie |
| False negative IDs | `7016` | `7016` | Tie |
| Stage wall-clock minutes | `5.4068` | `20.358766666666668` | Simple better |

## Conclusion

The more complex run did **not** rank better on the objective benchmark task metrics. It tied the
simple baseline on recall, precision, hard-negative rejection, candidate-level accuracy, synthesis
direction, and the specific false negative. It ranked worse on runtime/latency because the complex
stage took about `3.77x` longer (`20.358766666666668 / 5.4068`).

The extra groups/rounds did create a multi-group run and selected `group-1` as the stage winner, but
that internal competition did not fix the key benchmark error: official matched positive `7016` was
still excluded as not internet/mobile-based.

## Tracked Metric Artifacts

Simple baseline:

- `documentation/agent-notes/tier4-real-metasyn-task419-predictions.json`
- `documentation/agent-notes/tier4-real-metasyn-task419-evaluation.json`

Complex run:

- `documentation/agent-notes/tier4-real-metasyn-task419-complex-predictions.json`
- `documentation/agent-notes/tier4-real-metasyn-task419-complex-evaluation.json`

Shared gold labels:

- `documentation/agent-notes/tier4-real-metasyn-task419-gold.json`

Verification command:

```sh
python3 documentation/agent-notes/evaluate_metasyn_candidate_subset.py \
  --gold documentation/agent-notes/tier4-real-metasyn-task419-gold.json \
  --predictions documentation/agent-notes/tier4-real-metasyn-task419-complex-predictions.json \
  --verify-files \
  --pretty
```

Final verification printed `complex-task419-metrics-and-file-hashes-verified`.
