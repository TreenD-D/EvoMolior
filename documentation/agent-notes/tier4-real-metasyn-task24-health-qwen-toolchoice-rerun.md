# Task 24 Complex Health Rerun — Qwen Tool-Choice

Date: 2026-07-04 / 2026-07-05

## Run

- Run ID: `tier4-real-metasyn-task24-complex-health-qwen-toolchoice-20260704`
- Backend: `qwen-cloud`
- Output root: `results/tier4-real-metasyn-task24-health-20260704-complex/tier4-real-metasyn-task24-complex-health-qwen-toolchoice-20260704`
- Stage output: `results/tier4-real-metasyn-task24-health-20260704-complex/tier4-real-metasyn-task24-complex-health-qwen-toolchoice-20260704/outputs/systematic_review_pack.md`
- Status: completed with exit code `0`.

This run supersedes the OpenRouter attempts for this health pass. OpenRouter was blocked by 402 credit/max-token errors, and the user instructed that Qwen should be used for now.

## Implementation Changes Validated

- Qwen tool calls are now sent with `enable_thinking=false` when tools are present while keeping `tool_choice=required`; this fixed DashScope's deterministic `tool_choice`/thinking-mode 400 error.
- The production runtime now uses generic no-action recovery, productive-action counting that excludes `LLM_CALL`, and a write-only final recovery pass.
- Final systematic-review writers are explicitly instructed to read `metasyn_task_24_candidates.md` and emit candidate rows instead of publishing header-only "screening incomplete" tables.
- Final output validation rejects systematic-review CSV sections with no `corpus_id` rows and treats repeated `corpus_id` headers inside one fenced CSV as separate logical tables.
- Grader criteria prompts/fallbacks now include broad multi-aspect criteria and `EXCELLENT / EXCEEDS EXPECTATIONS` thresholds. Criteria iteration budget was raised from `15` to bounded `25`; a temporary `40`-iteration verification run became pathological and was terminated with user approval.
- After this evaluated run, criteria persistence was hardened further: each grader group now receives an objective-specific deterministic criteria seed before criteria agents run, substantive text-only criteria are still recovered, and non-substantive no-write criteria-agent outputs no longer create per-role synthetic fallback entries when that seed exists. Focused regression tests and `installDist` passed for this bounded code path.

## Objective Evaluation

Prediction file:

- `documentation/agent-notes/tier4-real-metasyn-task24-health-qwen-toolchoice-predictions.json`

Evaluation file:

- `documentation/agent-notes/tier4-real-metasyn-task24-health-qwen-toolchoice-evaluation.json`

Metrics with `--verify-files`:

```json
{
  "included_recall": 0.6666666666666666,
  "included_precision": 1.0,
  "hard_negative_rejection_accuracy": 1.0,
  "candidate_level_accuracy": 0.875,
  "included_false_negatives": [4567],
  "included_false_positives": [],
  "synthesis_direction_match": true
}
```

The final pack includes all eight candidate IDs:

- Included: `4565`, `4566`
- Excluded: `4558`, `4559`, `4567`, `4568`, `4570`, `4571`

The semantic miss remains unchanged: `4567` is still excluded, so objective recall stays at `2/3`.

## Health Counts

Systematic-review stage log comparison:

| Signal | Post-hardening OpenRouter | Qwen final before tool-choice | Qwen tool-choice |
|---|---:|---:|---:|
| `completed without tool/write actions` | 57 | 51 | 0 |
| `no-action fallback` | 15 | 20 | 0 |
| `produced intent-only no-action` | 0 | 9 | 0 |
| `no-action-recovery` | 0 | 4 | 0 |
| `write-only final recovery` | 0 | 4 | 0 |
| `persisted synthetic criteria` | 18 | 18 | 13 |
| `deterministic snapshot fallback scores` | 0 | 0 | 0 |
| `generated infrastructure failure fallback scores` | 18 | 0 | 0 |
| `collected .* scores from RoundJudge` | 9 | 9 | 9 |
| `DashScope chat completion failed` | 0 | 0 | 12 |
| `AIAgentMaxNumberOfIterationsReachedException` | 0 | 0 | 150 |

Interpretation:

- Ordinary agent no-action fallback was eliminated in the completed Qwen tool-choice run.
- Infrastructure fallback score batches were eliminated.
- RoundJudge produced collected score batches for all expected judge runs.
- Final output is structurally complete and hash-verified.
- Grader criteria still used synthetic criteria 13 times, reduced from 18 but not eliminated.
- Qwen still has agent max-iteration failures in grader/final paths, although the completed run recovered enough to publish a valid pack.

## Caveats

This is healthier than the post-hardening run but not perfect. The evaluated run still had criteria agents spend their iteration budget without staging a PLAN_UPDATE. The current code no longer depends on those failures producing generic synthetic per-role criteria: it seeds an auditable objective-specific rubric first, then preserves agent-written or recovered substantive criteria as refinements. This deterministic seed path is unit-tested but was not revalidated by a full end-to-end benchmark after the earlier `40`-iteration verification became pathological.

The objective MetaSyn error on `4567` is a semantic screening miss and was not fixed by orchestration hardening. No gold labels were leaked into prompts or runtime code.
