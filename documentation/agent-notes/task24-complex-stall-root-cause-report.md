# Task 24 Complex Run Stall Root-Cause Report

Date: 2026-07-04

Run under analysis:

- Run ID: `tier4-real-metasyn-task24-complex-complexity-20260704`
- Stage: `systematic-review-pack`
- Stage log: `results/tier4-real-metasyn-task24-two-complexity-20260704-complex/tier4-real-metasyn-task24-complex-complexity-20260704/stages/systematic-review-pack/debug-log.txt`
- Final output: `results/tier4-real-metasyn-task24-two-complexity-20260704-complex/tier4-real-metasyn-task24-complex-complexity-20260704/outputs/systematic_review_pack.md`
- Archive: `results/tier4-real-metasyn-task24-two-complexity-20260704-complex/tier4-real-metasyn-task24-complex-complexity-20260704/stages/systematic-review-pack/results.zip`

## Executive Summary

The complex run did not stall completely: it moved from shallow ingestion in round 0, to real
candidate screening in round 1, to a concrete review pack in round 2. The problem is that this
improvement happened despite the intended complex-run steering mechanisms, not because they worked
reliably.

The first round mostly burned agents on read-only setup and "let me now..." intent text. Grader
criteria often had to be synthesized because criteria agents did not stage `PLAN_UPDATE` rubric
documents. RoundJudge outputs in final progress were also intent text instead of tool/score calls.
Outliers were spawned, but their early outputs were mostly read-only/no-action fallbacks, not durable
assumption-breaking vectors. The final artifact preserved enough exclusion evidence to tie the
simple run under the deterministic evaluator, but required CSV tables were incomplete.

## Evidence

### Round Evolution

- Round 0 was dominated by artifact ingestion. The group submission summaries at
  `debug-log.txt:621-665` say the groups read and parsed `metasyn_task_24_candidates.md` but did not
  yet generate the review pack. The archived files
  `outputs/round_summary/3-round-summary-tier4-real-metasyn-task24-complex-complexity-20260704-0.json`
  and
  `outputs/round_summary/4-round-summary-tier4-real-metasyn-task24-complex-complexity-20260704-0-group-0.json`
  match this.
- Round 1 contained the first substantive screening. At `debug-log.txt:1558-1611`, group-0's
  Archivist reported full manual screening, and promoted facts included candidate decisions and
  source/evidence links.
- Round 2 produced the review-pack shape. At `debug-log.txt:2504-2553`, group summaries reported
  `systematic_review_pack.md` tables. At `debug-log.txt:2783-2809`, the final deliverable writer
  read the uploaded artifact and wrote a `DOC`.

### No-Action Setup Loop

Early agents repeatedly made read calls and then ended with intent text instead of executing the
next tool action:

- `debug-log.txt:241-246`: Planner ended after saying it had one input artifact and would read it
  in full, triggering no-action fallback.
- `debug-log.txt:261-266`: Researcher ended with "Let me read the full candidate file now" and
  no tool/write action.
- `debug-log.txt:281-286`: Researcher ended with intent to search existing dead ends / KB entries.
- `debug-log.txt:390-396` and `407-412`: Outlier/Critic ended after promising systematic
  screening, again causing no-action fallback.

The fallback did preserve warning artifacts, but those artifacts were low-trust descriptions of
missed actions rather than candidate-level evidence or adversarial checks.

### Grader Criteria And Judge Signals

The grader path did not provide stable rubric evolution:

- Grader criteria phase started in round 0 at `debug-log.txt:128-140`.
- Criteria agents repeatedly completed without tool/write actions. Examples include
  `debug-log.txt:338-345` and `debug-log.txt:628-629`, where `GraderRunner` persisted synthetic
  criteria because no `PLAN_UPDATE` was staged.
- The archive round-0 summary has empty `judgeOutputsByGrader`, showing no meaningful per-grader
  narrative for that round.
- Final progress at `debug-log.txt:2770-2782` shows RoundJudge narratives like "Let me re-read the
  canon snapshot more carefully" rather than clear submitted-score reasoning. The code already has
  infrastructure fallback scores for missing dimensions, but this run's useful grader steering was
  weak and difficult to audit.

### Outlier Behavior

Outliers were spawned at round start (`debug-log.txt:50-54` and `96-100`), but the observable early
contributions were mostly read-only. `debug-log.txt:210-222` shows artifact reading, and
`debug-log.txt:390-396` shows no-action completion. Later Outlier-related promotions were mostly
fallback/substrate-rationale artifacts rather than explicit novel vectors that altered screening.

For this benchmark, external source acquisition would be invalid because the task prompt says to use
only the uploaded file. The desired Outlier behavior is therefore adversarial extraction from the
uploaded artifact: comparator mismatches, subgroup/outcome ambiguity, hard-negative traps, duplicate
or omitted IDs, and required-table completeness.

### Final Artifact Completeness

The deterministic evaluation for complex task-24 tied the simple run:

- Recall: `0.6666666666666666`
- Precision: `1.0`
- Hard-negative rejection: `1.0`
- Candidate accuracy: `0.875`
- False negative: `4567`

However, the final `systematic_review_pack.md` was structurally incomplete. Candidates such as
`4568`, `4570`, and `4571` were explicitly excluded in `exclusion_reasons.csv` but absent from
`excluded_hard_negatives.csv` and `evidence_table.csv`. This let scoring recover the correct
exclusions from another table, but the deliverable itself failed the expected systematic-review pack
shape.

## Root Causes

1. Criteria fallback was too lossy. When criteria agents returned rubric text without staging it, the
   runtime replaced it with generic synthetic criteria. This erased any useful grader-specific
   evolution and made RoundJudge prompts less grounded.
2. Ordinary-agent prompts allowed intent text to be treated as a completed turn. The no-action
   fallback made this observable, but did not force immediate continuation from "let me read/search"
   into actual artifact extraction or a structured blocker.
3. Outlier instructions were too generic. They asked for novelty but did not require a durable
   assumption-break artifact with candidate/table implications, especially in uploaded-file-only
   benchmark stages where external source exploration is disallowed.
4. Final-output validation checked duplicate candidate rows but not structural completeness. A
   partially complete pack could pass if scoring evidence appeared elsewhere.

## Hardening Implemented

- `GraderRunner` now recovers substantive criteria-agent text as `RECOVERED GRADER CRITERIA` when
  the agent failed to stage `PLAN_UPDATE`. Only thin intent/failure text falls back to synthetic
  criteria. Recovered criteria are marked explicitly with lower trust than normal staged criteria.
- Agent substrate contribution prompts now explicitly forbid stopping with "let me..." intent text
  after read-only calls and direct uploaded-file-only runs toward candidate-level decisions,
  extraction notes, or table-completeness checks.
- Outlier prompts now require a durable `NOVEL VECTOR` / `ASSUMPTION BREAK` artifact and include
  benchmark-safe adversarial checks such as comparator mismatch, subgroup/outcome ambiguity,
  hard-negative traps, duplicate/omitted IDs, and required-table completeness.
- Final-output validation now checks systematic-review Markdown CSV completeness when the output's
  own `corpus_id` tables reveal a candidate universe of at least four IDs. It rejects omitted IDs
  from `evidence_table.csv` and from the combined `included_studies.csv` /
  `excluded_hard_negatives.csv` decision coverage whenever at least one of those decision tables is
  present.
- A guarded regression smoke replays the actual ignored task-24 final artifact from
  `results/tier4-real-metasyn-task24-two-complexity-20260704-complex/tier4-real-metasyn-task24-complex-complexity-20260704/outputs/systematic_review_pack.md`
  when it is available locally; this workspace's focused test run confirmed that the new runtime
  validator rejects the known incomplete artifact.

## Remaining Caveats

- These fixes are regression-tested at the runtime/prompt/validator level. A full complex rerun was
  not performed in this pass because the prior run took about 25 minutes and the immediate defects
  were directly reproducible from logs and unit-level seams.
- The generic final-output completeness check intentionally does not know gold labels or task-24
  candidate metadata. It only activates when the final output itself exposes a systematic-review
  corpus-table universe of at least four IDs, so very small examples are left to ordinary prompt
  compliance rather than hard rejection.
- RoundJudge still depends on model/tool compliance. The existing parser and retry path remain
  useful, but a future hardening pass should add stronger judge telemetry reporting in archived
  summaries.
