# Tier 4 Real Task Goal Log

Started: 2026-07-04

## User Goal

Try some real task from Tier 4 and evaluate results.

## Locked Interpretation

- Tier 4 means MetaSyn/systematic-review reconstruction work, not another synthetic smoke task if a
  real MetaSyn artifact can be obtained.
- A valid attempt should run through the installed EvoMolior CLI/runtime.
- Evaluation must compare generated inclusion/exclusion/synthesis output against available task
  evidence or gold labels.
- If official MetaSyn task rows are not directly available, record exact access attempts and use the
  smallest official-real artifact that is accessible, while explicitly documenting the limitation.

## Plan

- [x] Locate an accessible real Tier 4 task artifact or confirm the public blockage.
- [x] Build a CLI input directory and prompt/config for one real systematic-review task.
- [x] Run the task through `evomolior/cli/build/install/evomolior/bin/evomolior`.
- [x] Evaluate output against gold labels/evidence available for that task.
- [x] Update benchmark ladder / notes with exact artifacts and caveats.
- [x] Audit evidence and mark the goal complete.

## Dataset Access Evidence

- Dataset API: `https://huggingface.co/api/datasets/THUIR/MetaSyn`
- Dataset files discovered:
  - `README.md`
  - `corpus.json`
  - `papers.json`
  - `test_ids.json`
- Dataset card states MetaSyn has 442 meta-analyses and a 140,585-article retrieval corpus.
- `test_ids.json` contains `24`, making task 24 an official test task.
- `papers.json` is directly accessible and was downloaded to `/tmp/evomolior-metasyn/papers.json`
  for local extraction.
- `corpus.json` is directly accessible but large (`x-linked-size: 2428710311` bytes), so only the
  required candidate records were streamed/extracted for the selected task.

## Selected Real Task

- Dataset: `THUIR/MetaSyn`
- Official test task ID: `24`
- Topic: effectiveness of low-energy formula diets for weight loss in obese patients with and
  without type 2 diabetes.
- Research question: compare weight losses after VLCD/LELD in obese patients with type 2 diabetes
  versus obese patients without type 2 diabetes.
- Official task metadata fields used: PICO, inclusion/exclusion criteria, search strategy, matched
  corpus IDs, official extracted titles, conclusion summary, effect direction, statistical
  significance, and effect size category.

Artifacts:

- Input corpus:
  `results/tier4-real-metasyn-20260704-input/metasyn_task_24_candidates.md`
- Gold/evaluation labels:
  `results/tier4-real-metasyn-20260704-eval/metasyn_task_24_gold.json`
- Evaluation report:
  `results/tier4-real-metasyn-20260704-eval/evaluation.md`
- Tracked audit copy:
  `documentation/agent-notes/tier4-real-metasyn-task24-audit.md`
- Prompt:
  `results/tier4-real-metasyn-20260704-config/metasyn_real_task_24_prompt.txt`
- Initial pipeline:
  `results/tier4-real-metasyn-20260704-config/metasyn_real_task_24_pipeline.json`
- Rerun pipeline:
  `results/tier4-real-metasyn-20260704-config/metasyn_real_task_24_rerun_pipeline.json`

## CLI Runs

First attempt:

```sh
OUT=results/tier4-real-metasyn-20260704-run
JAVA_HOME=$(/usr/libexec/java_home -v 17.0.12) \
evomolior/cli/build/install/evomolior/bin/evomolior run \
  -prompt "Pipeline driver for configured Tier 4 real MetaSyn test task 24 systematic-review stage." \
  -input results/tier4-real-metasyn-20260704-input \
  -output "$OUT" \
  -pipeline-config results/tier4-real-metasyn-20260704-config/metasyn_real_task_24_pipeline.json \
  -llm-backend openrouter \
  -fast \
  -rounds 1 \
  -group-count 1 \
  -max-agents-per-group 2 \
  -round-duration-minutes 1 \
  -budget-override 80 \
  -run-id tier4-real-metasyn-task24-20260704
```

Outcome: CLI exited `0` and the stage reported `COMPLETED`, but the published
`systematic_review_pack.md` was only `[no-progress completion: empty-assistant recovery]`. This is a
failed benchmark attempt and was not scored.

Successful rerun:

```sh
OUT=results/tier4-real-metasyn-20260704-rerun
JAVA_HOME=$(/usr/libexec/java_home -v 17.0.12) \
evomolior/cli/build/install/evomolior/bin/evomolior run \
  -prompt "Pipeline driver for configured Tier 4 real MetaSyn test task 24 systematic-review stage. The configured stage must write systematic_review_pack.md." \
  -input results/tier4-real-metasyn-20260704-input \
  -output "$OUT" \
  -pipeline-config results/tier4-real-metasyn-20260704-config/metasyn_real_task_24_rerun_pipeline.json \
  -llm-backend openrouter \
  -fast \
  -rounds 1 \
  -group-count 1 \
  -max-agents-per-group 2 \
  -round-duration-minutes 1 \
  -budget-override 80 \
  -model Builder=deepseek/deepseek-v4-flash \
  -run-id tier4-real-metasyn-task24-rerun-20260704
```

Outcome: primary stage `COMPLETED`; systematic-review-pack stage `COMPLETED`;
`systematic_review_pack.md` published.

Successful output root:
`results/tier4-real-metasyn-20260704-rerun/tier4-real-metasyn-task24-rerun-20260704`

## Evaluation Summary

- Gold included candidate corpus IDs: `4565`, `4566`, `4567`
- Gold hard negatives supplied: `4558`, `4559`, `4568`, `4570`, `4571`
- Predicted included IDs: `4565`, `4566`
- Predicted excluded IDs: `4567`, `4558`, `4559`, `4568`, `4570`, `4571`
- Included-study recall: `2/3 = 0.6666666666666666`
- Included-study precision: `2/2 = 1.0`
- Hard-negative rejection accuracy: `5/5 = 1.0`
- Candidate-level accuracy: `7/8 = 0.875`
- False negatives: `4567`
- False positives: none
- Synthesis direction: matched gold qualitatively. The gold says VLCD/LELD weight losses were not
  significantly different between obese participants with and without T2DM; EvoMolior concluded the
  weight-loss magnitude was similar irrespective of diabetes status.

## Caveats

- This is a real MetaSyn test task, not the earlier synthetic Tier 4 smoke.
- It is still not a full official MetaSyn leaderboard run because EvoMolior did not retrieve over
  the entire 140,585-article corpus.
- The official task has `study_count=5`, `matched_ref_count=3`, and `match_rate=0.6`; this run scored
  the three matched positive corpus candidates plus five selected corpus hard negatives.
- Runtime cost telemetry still reports `usd_cost=null`.
