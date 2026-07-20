# Task 24 Two-Complexity Benchmark Goal

Started: 2026-07-04

## User Goal

Run a different task from the benchmark set with two different-complexity EvoMolior runs and
evaluate the results.

## Locked Interpretation

- Use MetaSyn official test task `24`, not task `419`.
- Run two fresh CLI pipeline executions over the same task-24 candidate input and tracked gold
  labels:
  - simple profile: one group, short stage, fewer agents;
  - complex profile: two groups, more rounds, more agents.
- Score both persisted `outputs/systematic_review_pack.md` files with
  `documentation/agent-notes/evaluate_metasyn_candidate_subset.py`.
- Compare objective candidate-subset metrics first; runtime/caveats second.
- Do not claim an official MetaSyn leaderboard score. These are candidate-subset runs.

## Plan

- [x] Lock task, configs, and evaluation scope.
- [x] Rebuild installed EvoMolior CLI from current code.
- [x] Run simple task-24 CLI benchmark.
- [x] Run complex task-24 CLI benchmark.
- [x] Extract predictions from both persisted outputs.
- [x] Evaluate both predictions against tracked task-24 gold labels.
- [x] Add tracked predictions/evaluations/comparison audit.
- [x] Stage tracked changes.
- [x] Mark goal complete.

## Inputs

- Candidate input:
  `results/tier4-real-metasyn-20260704-input/metasyn_task_24_candidates.md`
- Prompt:
  `results/tier4-real-metasyn-20260704-config/metasyn_real_task_24_prompt.txt`
- Gold labels:
  `documentation/agent-notes/tier4-real-metasyn-task24-gold.json`
- Evaluator:
  `documentation/agent-notes/evaluate_metasyn_candidate_subset.py`

## Planned Runs

Simple:

- Run ID: `tier4-real-metasyn-task24-simple-complexity-20260704`
- Output root:
  `results/tier4-real-metasyn-task24-two-complexity-20260704-simple/tier4-real-metasyn-task24-simple-complexity-20260704`
- Stage profile: `groupCount=1`, `rounds=1`, `maxAgentsPerGroup=3`,
  `roundDurationMinutes=2`, `budgetOverride=120`.

Complex:

- Run ID: `tier4-real-metasyn-task24-complex-complexity-20260704`
- Output root:
  `results/tier4-real-metasyn-task24-two-complexity-20260704-complex/tier4-real-metasyn-task24-complex-complexity-20260704`
- Stage profile: `groupCount=2`, `rounds=3`, `maxAgentsPerGroup=5`,
  `roundDurationMinutes=3`, `budgetOverride=320`.

## CLI Build

Passed:

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 17.0.12) ./gradlew --no-configuration-cache :engine:cli:installDist
```

## Run Results

Simple profile:

- Final scored run ID: `tier4-real-metasyn-task24-simple-complexity-v2-20260704`
- Output root:
  `results/tier4-real-metasyn-task24-two-complexity-20260704-simple-v2/tier4-real-metasyn-task24-simple-complexity-v2-20260704`
- Stage status: `COMPLETED`
- Stage duration: `171726 ms` (`2.8621` minutes)
- Predicted included IDs: `4565`, `4566`
- Predicted excluded IDs: `4558`, `4559`, `4567`, `4568`, `4570`, `4571`
- Metrics: recall `0.6666666666666666`, precision `1.0`,
  hard-negative rejection `1.0`, candidate accuracy `0.875`, false negative `4567`.

Complex profile:

- Run ID: `tier4-real-metasyn-task24-complex-complexity-20260704`
- Output root:
  `results/tier4-real-metasyn-task24-two-complexity-20260704-complex/tier4-real-metasyn-task24-complex-complexity-20260704`
- Stage status: `COMPLETED`
- Stage duration: `1513902 ms` (`25.2317` minutes)
- Predicted included IDs: `4565`, `4566`
- Predicted excluded IDs: `4558`, `4559`, `4567`, `4568`, `4570`, `4571`
- Metrics: recall `0.6666666666666666`, precision `1.0`,
  hard-negative rejection `1.0`, candidate accuracy `0.875`, false negative `4567`.

Conclusion: both profiles tie on objective candidate-subset metrics. The simple run ranks better on
latency and artifact completeness. The complex run took about `8.82x` longer and had more grader
warning fallbacks.

Tracked artifacts:

- `documentation/agent-notes/tier4-real-metasyn-task24-simple-complexity-predictions.json`
- `documentation/agent-notes/tier4-real-metasyn-task24-simple-complexity-evaluation.json`
- `documentation/agent-notes/tier4-real-metasyn-task24-complex-complexity-predictions.json`
- `documentation/agent-notes/tier4-real-metasyn-task24-complex-complexity-evaluation.json`
- `documentation/agent-notes/tier4-real-metasyn-task24-two-complexity-comparison.md`

Important caveats:

- The first simple attempt `tier4-real-metasyn-task24-simple-complexity-20260704` aborted because
  the initial duplicate-row validator incorrectly rejected repeated `corpus_id` values across
  separate required CSV tables. The validator was corrected to enforce uniqueness within each
  fenced CSV block only; focused API test passed; then simple was rerun as `v2`.
- The complex output explicitly excludes all hard negatives across the full Markdown file, but its
  `excluded_hard_negatives.csv` and `evidence_table.csv` sections are incomplete.
- These are candidate-subset runs, not official leaderboard evaluations.
