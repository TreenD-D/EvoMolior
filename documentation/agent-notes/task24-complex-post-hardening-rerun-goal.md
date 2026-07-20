# Task 24 Complex Post-Hardening Rerun Goal

Started: 2026-07-04

## Goal

Rerun the MetaSyn task-24 complex benchmark after the complex-run hardening changes and re-validate
the produced outputs.

## Locked Interpretation

Benchmark to rerun:

- Complex task-24 candidate-subset benchmark.
- Prior run ID: `tier4-real-metasyn-task24-complex-complexity-20260704`
- Prior config path:
  `results/tier4-real-metasyn-task24-two-complexity-20260704-config/metasyn_real_task_24_complex_pipeline.json`
- Prompt/source artifact:
  `results/tier4-real-metasyn-task24-two-complexity-20260704-config/metasyn_real_task_24_prompt.txt`

Validation expectations:

- Run the actual EvoMolior CLI pipeline, not a synthetic script.
- Re-extract candidate predictions from the new `outputs/systematic_review_pack.md`.
- Validate predictions with
  `documentation/agent-notes/evaluate_metasyn_candidate_subset.py`.
- Check that final-output structural validation no longer permits the known missing-table pattern.
- Preserve metrics and caveats in tracked `documentation/agent-notes/` files because `results/`
  is ignored.

## Plan

- [x] Inspect/reuse the complex task-24 pipeline config and choose a new output/run ID.
- [x] Rebuild/install the EvoMolior CLI after code changes.
- [x] Run the complex task-24 benchmark through the CLI.
- [x] Extract predictions from the new output.
- [x] Run deterministic MetaSyn candidate-subset evaluator with file verification.
- [x] Inspect final output table completeness and runtime validation behavior.
- [x] Record metrics, runtime, and caveats in tracked docs.
- [x] Stage relevant tracked artifacts/docs.
- [x] Mark Kent goal complete.

## Completed Run

- Run ID: `tier4-real-metasyn-task24-complex-post-hardening-20260704`
- Output root:
  `results/tier4-real-metasyn-task24-post-hardening-20260704-complex/tier4-real-metasyn-task24-complex-post-hardening-20260704`
- Final output:
  `results/tier4-real-metasyn-task24-post-hardening-20260704-complex/tier4-real-metasyn-task24-complex-post-hardening-20260704/outputs/systematic_review_pack.md`
- Stage status: `COMPLETED`, duration `1112395 ms`
- Pipeline status: completed at `2026-07-04T14:18:05.570888Z`

## Validation Artifacts

- Summary:
  `documentation/agent-notes/tier4-real-metasyn-task24-post-hardening-complex-rerun.md`
- Predictions:
  `documentation/agent-notes/tier4-real-metasyn-task24-post-hardening-complex-predictions.json`
- Evaluation:
  `documentation/agent-notes/tier4-real-metasyn-task24-post-hardening-complex-evaluation.json`

## Validation Result

- Metrics unchanged from the previous complex run:
  - recall `0.6666666666666666`
  - precision `1.0`
  - hard-negative rejection `1.0`
  - candidate-level accuracy `0.875`
  - false negative `4567`
- Structural completeness improved:
  - all eight candidate IDs are present across combined included/excluded decision tables;
  - all eight candidate IDs are present in `evidence_table.csv`;
  - prior missing IDs `4568`, `4570`, and `4571` are now present in the required tables.
- Caveat:
  - grader criteria and RoundJudge behavior remains fallback-heavy; this rerun validates final-output
    structural hardening, not full grader-civilization health.
