# Complex Run Benchmark Comparison Goal Log

Started: 2026-07-04

## User Goal

Recheck the results by running a benchmark task and compare whether a more complex run with more
groups/rounds ranks better against the benchmark task.

## Locked Interpretation

- Use an already prepared, objectively scorable real benchmark task so the comparison is direct.
- Use MetaSyn official test task `419` because the previous simple run is already audited:
  - baseline run: `tier4-real-metasyn-task419-20260704`
  - baseline metrics: recall `0.5`, precision `1.0`, hard-negative rejection `1.0`,
    candidate-level accuracy `0.8571428571428571`
- Run a more complex EvoMolior CLI stage on the same input/gold labels:
  - more groups than the baseline;
  - more rounds than the baseline;
  - same final deliverable contract and same evaluator.
- Compare by benchmark metrics first, then by caveats/runtime cost. Do not treat internal
  `quality_score` as the benchmark ranking unless candidate-subset metrics tie.

## Plan

- [x] Create a complex task 419 pipeline config.
- [x] Run the complex task through the EvoMolior CLI.
- [x] Evaluate the complex output against tracked task 419 gold labels.
- [x] Compare complex metrics against the previous simple baseline.
- [x] Add tracked audit/evaluation artifacts.
- [x] Update benchmark documentation with the comparison.
- [x] Stage tracked changes.
- [x] Verify evidence and mark complete.

## Complex Run Artifacts

- Complex pipeline config:
  `results/tier4-real-metasyn-task419-20260704-config/metasyn_real_task_419_complex_pipeline.json`
- Output root:
  `results/tier4-real-metasyn-task419-20260704-complex/tier4-real-metasyn-task419-complex-20260704`
- Published output:
  `results/tier4-real-metasyn-task419-20260704-complex/tier4-real-metasyn-task419-complex-20260704/outputs/systematic_review_pack.md`
- Tracked predictions:
  `documentation/agent-notes/tier4-real-metasyn-task419-complex-predictions.json`
- Tracked evaluation:
  `documentation/agent-notes/tier4-real-metasyn-task419-complex-evaluation.json`
- Tracked comparison:
  `documentation/agent-notes/tier4-real-metasyn-task419-complex-comparison.md`

## Complex Run Outcome

- Primary stage: `COMPLETED`, duration `68475 ms`
- Benchmark stage: `COMPLETED`, duration `1221602 ms`
- Benchmark stage winner: `group-1`
- Benchmark stage shape: `groupCount=2`, `rounds=3`, `maxAgentsPerGroup=5`,
  `roundDurationMinutes=3`, `budgetOverride=320`
- Cost report: `usd_cost=null`, `wall_clock_minutes=20.358766666666668`,
  `quality_score=11.891891891891893`, citation precision proxy `0.030303030303030304`

## Comparison Result

- Simple baseline recall: `0.5`
- Complex recall: `0.5`
- Simple baseline precision: `1.0`
- Complex precision: `1.0`
- Simple hard-negative rejection: `1.0`
- Complex hard-negative rejection: `1.0`
- Simple candidate-level accuracy: `0.8571428571428571`
- Complex candidate-level accuracy: `0.8571428571428571`
- Simple false negatives: `7016`
- Complex false negatives: `7016`
- Simple stage wall-clock minutes: `5.4068`
- Complex stage wall-clock minutes: `20.358766666666668`

Conclusion: the complex run did not rank better on objective benchmark metrics. It tied the simple
baseline on candidate-subset accuracy and ranked worse on runtime/latency.

## Verification

```sh
python3 documentation/agent-notes/evaluate_metasyn_candidate_subset.py \
  --gold documentation/agent-notes/tier4-real-metasyn-task419-gold.json \
  --predictions documentation/agent-notes/tier4-real-metasyn-task419-complex-predictions.json \
  --verify-files \
  --pretty
```

Final verification printed `complex-task419-metrics-and-file-hashes-verified`.
