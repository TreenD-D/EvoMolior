# Tier 4 Real MetaSyn Task 419 Audit

Date: 2026-07-04

## Task

- Dataset: `THUIR/MetaSyn`
- Dataset page: `https://huggingface.co/datasets/THUIR/MetaSyn`
- Official test task ID: `419`
- Topic: effectiveness of internet- and mobile-based interventions for improving mental health in
  adults with overweight/obesity and comorbid depressive symptoms.
- Gold effect direction: `Positive`
- Gold statistical significance: `Partially`
- Gold conclusion summary: internet/mobile interventions show evidence of effectiveness, with two
  of three RCTs reducing both depressive symptoms and weight and one RCT reducing depressive
  symptoms only.

## Corpus-Matching Limitation

The official task row has:

- `study_count=3`
- `matched_ref_count=2`
- `match_rate=0.6666666666666666`
- `matched_corpus_ids=[7016, 7017]`

There is no third matched corpus ID in the available public row metadata. This run therefore scores
the two matched positive corpus records plus five selected official corpus hard negatives, not the
full official systematic-review reference set.

The official `search_strategies` field for this row appeared inconsistent with the IMI/depression
PICO because it referenced maternal obesity and child cardiovascular outcomes. The value is
preserved in ignored source gold artifacts, but it was omitted from the agent-facing candidate input
so screening was governed by the review question, PICO, and inclusion/exclusion criteria.

## Artifacts

Ignored runtime artifacts:

- Input:
  `results/tier4-real-metasyn-task419-20260704-input/metasyn_task_419_candidates.md`
- Gold source:
  `results/tier4-real-metasyn-task419-20260704-eval/metasyn_task_419_gold.json`
- Prompt/config:
  - `results/tier4-real-metasyn-task419-20260704-config/metasyn_real_task_419_prompt.txt`
  - `results/tier4-real-metasyn-task419-20260704-config/metasyn_real_task_419_pipeline.json`
- Output root:
  `results/tier4-real-metasyn-task419-20260704-run/tier4-real-metasyn-task419-20260704`
- Published deliverable:
  `results/tier4-real-metasyn-task419-20260704-run/tier4-real-metasyn-task419-20260704/outputs/systematic_review_pack.md`

Tracked audit artifacts:

- Gold labels: `documentation/agent-notes/tier4-real-metasyn-task419-gold.json`
- Extracted predictions: `documentation/agent-notes/tier4-real-metasyn-task419-predictions.json`
- Expected evaluation metrics: `documentation/agent-notes/tier4-real-metasyn-task419-evaluation.json`
- Reused evaluator: `documentation/agent-notes/evaluate_metasyn_candidate_subset.py`

Hashes:

- Input SHA-256: `1c72bdb36302beb00af501ad597466412aa5b4944f7628e4e591ef067f7f4398`
- Output SHA-256: `7e0dbd211947eaf063ff8368e75ef7944100bbefc06d031f5f9f4248cd2f8d6b`

## CLI Run

```sh
OUT=results/tier4-real-metasyn-task419-20260704-run
JAVA_HOME=$(/usr/libexec/java_home -v 17.0.12) \
evomolior/cli/build/install/evomolior/bin/evomolior run \
  -prompt "Pipeline driver for configured Tier 4 real MetaSyn test task 419 systematic-review stage. The configured stage must write systematic_review_pack.md." \
  -input results/tier4-real-metasyn-task419-20260704-input \
  -output "$OUT" \
  -pipeline-config results/tier4-real-metasyn-task419-20260704-config/metasyn_real_task_419_pipeline.json \
  -llm-backend openrouter \
  -fast \
  -rounds 1 \
  -group-count 1 \
  -max-agents-per-group 2 \
  -round-duration-minutes 1 \
  -budget-override 80 \
  -model Builder=deepseek/deepseek-v4-flash \
  -run-id tier4-real-metasyn-task419-20260704
```

Outcome:

- Primary stage: `COMPLETED`, duration `96623 ms`
- Systematic-review-pack stage: `COMPLETED`, duration `324465 ms`
- Runtime cost telemetry: `usd_cost=null`
- `cost_report.json`: `wall_clock_minutes=5.4068`, `quality_score=11.891891891891893`,
  citation precision proxy `0.045454545454545456`

## Scored Output

Gold included candidate corpus IDs:

- `7016`
- `7017`

Gold hard negatives:

- `7014`
- `7015`
- `7008`
- `7012`
- `7018`

Predicted included corpus IDs:

- `7017`

Predicted excluded corpus IDs:

- `7016`
- `7014`
- `7015`
- `7008`
- `7012`
- `7018`

Metrics:

- Included-study recall: `1/2 = 0.5`
- Included-study precision: `1/1 = 1.0`
- Hard-negative rejection accuracy: `5/5 = 1.0`
- Candidate-level accuracy: `6/7 = 0.8571428571428571`
- False negatives: `7016`
- False positives: none
- Synthesis direction match: pass. The generated conclusion was positive/preliminary for IMI
  improving depression and weight outcomes, matching the gold direction qualitatively.

## Verification

Run:

```sh
python3 documentation/agent-notes/evaluate_metasyn_candidate_subset.py \
  --gold documentation/agent-notes/tier4-real-metasyn-task419-gold.json \
  --predictions documentation/agent-notes/tier4-real-metasyn-task419-predictions.json \
  --verify-files \
  --pretty
```

Expected computed metrics:

```json
{
  "candidate_level_accuracy": 0.8571428571428571,
  "dataset": "THUIR/MetaSyn",
  "hard_negative_rejection_accuracy": 1.0,
  "hard_negative_true_negatives": [7008, 7012, 7014, 7015, 7018],
  "included_false_negatives": [7016],
  "included_false_positives": [],
  "included_precision": 1.0,
  "included_recall": 0.5,
  "included_true_positives": [7017],
  "is_full_leaderboard_run": false,
  "run_id": "tier4-real-metasyn-task419-20260704",
  "scoring_scope": "candidate_subset_with_all_matched_positive_corpus_ids",
  "synthesis_direction_match": true,
  "task_id": 419
}
```

Final verification command printed `task419-metrics-and-file-hashes-verified`.

## Interpretation

This second real Tier 4 run gives another objective candidate-subset datapoint. EvoMolior was
conservative: it rejected every hard negative but also rejected one official matched positive
(`7016`) because the output judged that integrated collaborative care was not internet/mobile based.
The synthesis direction still matched the gold positive/partially significant conclusion, but recall
remained the limiting metric.
