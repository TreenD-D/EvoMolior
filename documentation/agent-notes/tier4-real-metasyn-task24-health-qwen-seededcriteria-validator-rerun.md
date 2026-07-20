# Task 24 Complex Health Rerun — Qwen Seeded Criteria + Validator

Date: 2026-07-05

## Run

- Run ID: `tier4-real-metasyn-task24-complex-health-qwen-seededcriteria-validator-20260705`
- Backend: `qwen-cloud`
- Output root: `results/tier4-real-metasyn-task24-health-20260704-complex/tier4-real-metasyn-task24-complex-health-qwen-seededcriteria-validator-20260705`
- Stage output: `results/tier4-real-metasyn-task24-health-20260704-complex/tier4-real-metasyn-task24-complex-health-qwen-seededcriteria-validator-20260705/outputs/systematic_review_pack.md`
- Status: completed with exit code `0`.
- Stage duration: `2070635` ms.
- Winner: `group-1`.

This run supersedes `tier4-real-metasyn-task24-complex-health-qwen-toolchoice-20260704` as the final health-pass evidence.

## Implementation Changes Validated

- Grader criteria now get a deterministic objective-specific `PLAN_UPDATE` seed before criteria agents run.
- Criteria agents can still refine the seed by writing a role-authored `PLAN_UPDATE`, and substantive text-only criteria remain recoverable.
- Non-substantive no-write criteria-agent outputs no longer create per-role synthetic fallback criteria when the deterministic seed exists.
- The final-output validator now accepts multiple criterion-failure rows for the same excluded candidate in `exclusion_reasons.csv`, while still rejecting duplicate candidate rows in one-row-per-candidate tables.
- Qwen tool calls still use tool-compatible request shaping with thinking disabled for tool-enabled calls.

## Objective Evaluation

Prediction file:

- `documentation/agent-notes/tier4-real-metasyn-task24-health-qwen-seededcriteria-validator-predictions.json`

Evaluation file:

- `documentation/agent-notes/tier4-real-metasyn-task24-health-qwen-seededcriteria-validator-evaluation.json`

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

## Single-Agent Baseline Comparison

Comparison report:

- `documentation/agent-notes/tier4-real-metasyn-task24-single-vs-society-comparison.md`

An ad hoc single direct `qwen3.7-plus` baseline run was executed against the same uploaded candidate
corpus:

- Run ID: `tier4-real-metasyn-task24-single-qwen-baseline-20260705`
- Output: `results/tier4-real-metasyn-task24-health-20260704-complex/tier4-real-metasyn-task24-single-qwen-baseline-20260705/outputs/systematic_review_pack.md`
- Duration: `31434` ms.
- Token usage: prompt `4744`, completion `1631`, total `6375`.

The single-agent baseline tied this EvoMolior society run on deterministic candidate-subset
metrics: recall `0.6666666666666666`, precision `1.0`, hard-negative rejection `1.0`, candidate
accuracy `0.875`, same false negative `4567`, and no false positives.

The structural difference is that the EvoMolior society output is more schema-complete:
`excluded_hard_negatives.csv` lists all six excluded candidates, while the single-agent baseline
lists only three excluded candidates in that table and relies on `exclusion_reasons.csv` plus
`evidence_table.csv` to cover the other excluded candidates. The single-agent run is far faster
for this task, but it does not exercise grader criteria, RoundJudge, substrate, critic, or
multi-agent orchestration health.

Baseline limitation: the project's reusable `benchmark -runner single-agent` path is currently
wired for Tier-0 benchmark adapters, not this MetaSyn systematic-review pipeline. This baseline is
therefore useful same-model evidence, but it is not a complete benchmark-harness single-agent pack.

## Health Counts

Systematic-review stage log counts:

| Signal | Count |
|---|---:|
| `completed without tool/write actions` | 0 |
| `no-action fallback` | 0 |
| `no-action-recovery` | 0 |
| `write-only final recovery` | 0 |
| `persisted synthetic criteria` | 0 |
| `persisted deterministic criteria seed` | 9 |
| `preserved deterministic criteria seed` | 9 |
| `RECOVERED GRADER CRITERIA` | 0 |
| `deterministic snapshot fallback scores` | 0 |
| `generated infrastructure failure fallback scores` | 0 |
| `collected .* scores from RoundJudge` | 9 |
| `final deliverable file systematic_review_pack.md rejected` | 0 |
| `DashScope chat completion failed` | 40 |
| `AIAgentMaxNumberOfIterationsReachedException` | 130 |
| `RUN FAILED` | 35 |
| `EvoMolior CLI run aborted` | 0 |

Interpretation:

- Ordinary no-action fallback remains eliminated.
- Infrastructure scoring fallback remains eliminated.
- Synthetic criteria fallback is eliminated; grader groups use deterministic seeded criteria when role agents do not stage criteria.
- RoundJudge collected all expected score batches.
- Final output validation accepted the systematic-review pack after the `exclusion_reasons.csv` duplicate-row false positive was fixed.
- Qwen still produces provider/tool-call failures and max-iteration failures, but they no longer prevent a complete CLI run, final pack publication, grader criteria substrate availability, or RoundJudge score collection.

## Caveats

The run is healthy enough for this goal but not perfect. Remaining runtime health debt:

- `DashScope chat completion failed` remains high, mostly from repetitive tool-call/provider errors.
- `AIAgentMaxNumberOfIterationsReachedException` remains high.
- `RUN FAILED` remains nonzero, although the run completed and produced verified outputs.
- Candidate `4567` remains a semantic false negative. This was not fixed by orchestration hardening and was not gold-leaked.
