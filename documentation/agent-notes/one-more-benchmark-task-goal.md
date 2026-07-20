# One More Benchmark Task Goal Log

Started: 2026-07-04

## User Goal

Try running one more benchmark task and check results.

## Locked Interpretation

- Continue with a real, objectively scorable benchmark task rather than a synthetic smoke.
- Prefer a second official MetaSyn test task because the previous Tier 4 harness already has a
  working pattern for task extraction, CLI execution, and deterministic screening evaluation.
- Use the installed EvoMolior CLI/runtime for the run.
- Check results against gold labels and document exact paths, metrics, failures, and caveats.

## Plan

- [x] Select a second official MetaSyn test task with a manageable matched-corpus subset.
- [x] Build tracked/ignored input, prompt/config, and gold-label artifacts for that task.
- [x] Run the task through the EvoMolior CLI.
- [x] Evaluate output against gold labels.
- [x] Add tracked audit evidence and reproducible metric checks.
- [x] Update benchmark documentation.
- [x] Stage tracked changes.
- [x] Verify evidence and mark complete.

## Selected Task

- Dataset: `THUIR/MetaSyn`
- Official test task ID: `419`
- Topic: internet/mobile-based interventions for adults with overweight/obesity and comorbid
  depressive symptoms.
- Matched positive corpus IDs: `7016`, `7017`
- Hard-negative corpus IDs supplied: `7014`, `7015`, `7008`, `7012`, `7018`
- Caveat: official `search_strategies` metadata appeared inconsistent with the PICO and was omitted
  from the agent-facing input before the run.

## Artifacts

- Input:
  `results/tier4-real-metasyn-task419-20260704-input/metasyn_task_419_candidates.md`
- Prompt/config:
  - `results/tier4-real-metasyn-task419-20260704-config/metasyn_real_task_419_prompt.txt`
  - `results/tier4-real-metasyn-task419-20260704-config/metasyn_real_task_419_pipeline.json`
- Gold source:
  `results/tier4-real-metasyn-task419-20260704-eval/metasyn_task_419_gold.json`
- Output root:
  `results/tier4-real-metasyn-task419-20260704-run/tier4-real-metasyn-task419-20260704`
- Tracked audit:
  `documentation/agent-notes/tier4-real-metasyn-task419-audit.md`
- Tracked metric artifacts:
  - `documentation/agent-notes/tier4-real-metasyn-task419-gold.json`
  - `documentation/agent-notes/tier4-real-metasyn-task419-predictions.json`
  - `documentation/agent-notes/tier4-real-metasyn-task419-evaluation.json`

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

Outcome: primary stage `COMPLETED`; systematic-review-pack stage `COMPLETED`;
`systematic_review_pack.md` published.

## Results

- Predicted included IDs: `7017`
- Predicted excluded IDs: `7016`, `7014`, `7015`, `7008`, `7012`, `7018`
- Included-study recall: `1/2 = 0.5`
- Included-study precision: `1/1 = 1.0`
- Hard-negative rejection accuracy: `5/5 = 1.0`
- Candidate-level accuracy: `6/7 = 0.8571428571428571`
- False negatives: `7016`
- False positives: none
- Synthesis direction: matched gold qualitatively.

## Verification

```sh
python3 documentation/agent-notes/evaluate_metasyn_candidate_subset.py \
  --gold documentation/agent-notes/tier4-real-metasyn-task419-gold.json \
  --predictions documentation/agent-notes/tier4-real-metasyn-task419-predictions.json \
  --verify-files \
  --pretty
```

Final verification printed `task419-metrics-and-file-hashes-verified`.
