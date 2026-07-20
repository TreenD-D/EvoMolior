# Task 24 Complex Run Stall Root-Cause and Hardening Goal

Started: 2026-07-04

## User Goal

Analyze the latest complex benchmark run deeply and identify why it stalled and did not make
meaningful improvements in later rounds. Specifically inspect whether:

- grader groups produce more robust and broader grading requirements over time;
- competing groups deepen the deliverable by researching additional related sources and compounding
  them;
- Outliers introduce non-obvious vectors that diversify the answer;
- later rounds compound prior evidence rather than repeat shallow setup work.

Then compose a report and proceed with hardening/fixing/improving these mechanics.

## Locked Run Under Analysis

Latest complex benchmark run:

- Run ID: `tier4-real-metasyn-task24-complex-complexity-20260704`
- Output root:
  `results/tier4-real-metasyn-task24-two-complexity-20260704-complex/tier4-real-metasyn-task24-complex-complexity-20260704`
- Stage under analysis:
  `results/tier4-real-metasyn-task24-two-complexity-20260704-complex/tier4-real-metasyn-task24-complex-complexity-20260704/stages/systematic-review-pack`
- Main stage log:
  `results/tier4-real-metasyn-task24-two-complexity-20260704-complex/tier4-real-metasyn-task24-complex-complexity-20260704/stages/systematic-review-pack/debug-log.txt`
- Persisted final output:
  `results/tier4-real-metasyn-task24-two-complexity-20260704-complex/tier4-real-metasyn-task24-complex-complexity-20260704/outputs/systematic_review_pack.md`

## Plan

- [x] Inspect run logs and archives for round-by-round compounding, grader behavior, source use,
  and Outlier contributions.
- [x] Identify root causes for no meaningful later-round improvement.
- [x] Inspect relevant runtime code paths for grader criteria/judge fallbacks, no-action fallback,
  source acquisition, and Outlier prompts.
- [x] Implement hardening fixes.
- [x] Add focused tests.
- [x] Run verification tests.
- [x] Compose tracked report with evidence, root causes, fixes, and remaining caveats.
- [x] Stage tracked changes.
- [x] Mark goal complete.

## Initial Hypotheses To Validate

- Grader groups may be producing missing/synthetic criteria because CriteriaResearcher/Builder
  agents complete without durable `PLAN_UPDATE`/scores.
- Round judges may be returning prose instead of calling `submit_grading_score`, causing fallback
  scores and suppressing meaningful winner selection.
- Competing groups may be repeatedly reading setup artifacts instead of fetching/adding new related
  evidence or emitting durable candidate-level decisions.
- Outlier prompts may be too generic; Outliers are not forced to persist assumption-breaking
  screening hypotheses or adjacent evidence vectors.
- The benchmark prompt says "Use only uploaded file", so this specific run cannot legitimately use
  external related sources. For benchmark stages, hardening should focus on deeper extraction,
  adversarial checks, and durable evidence/KG/staging, not external fetching unless the benchmark
  stage explicitly allows it.

## Diagnosis Result

Tracked report:

- `documentation/agent-notes/task24-complex-stall-root-cause-report.md`

Key conclusions:

- Round 0 was mostly artifact ingestion/no-action fallback; round 1 began real screening; round 2
  produced a substantive but structurally incomplete review pack.
- Grader criteria/judge signals were fallback-heavy. Criteria agents often did not stage
  `PLAN_UPDATE`; previous fallback behavior could erase useful criteria text.
- Outliers were spawned but early evidence shows mostly read-only/no-action fallback, not durable
  assumption-break vectors.
- The final artifact tied simple scoring but omitted candidate rows from required CSV tables.

## Hardening Implemented

- `GraderRunner` now recovers substantive criteria-agent text as `RECOVERED GRADER CRITERIA`
  instead of replacing it with generic synthetic criteria when `PLAN_UPDATE` was missing.
- Agent prompts now explicitly forbid stopping with "let me..." intent text after read-only calls and
  steer uploaded-file-only runs toward candidate-level decisions, extraction notes, and
  table-completeness checks.
- Outlier prompts now require durable `NOVEL VECTOR` / `ASSUMPTION BREAK` artifacts and benchmark-safe
  adversarial checks without external source fetching unless a source contract exists.
- Final-output validation now rejects systematic-review Markdown CSV packs whose own inferred
  `corpus_id` universe is omitted from `evidence_table.csv` or from combined
  `included_studies.csv` / `excluded_hard_negatives.csv` decision coverage when at least one
  decision table is present.

## Verification

- Passed:
  `JAVA_HOME=$(/usr/libexec/java_home -v 17.0.12) ./gradlew :engine:api:test --tests '*GraderRunnerJudgingPhaseTest*' --tests '*EvoMoliorRunServiceFinalOutputImageGateTest*' :engine:agents:test --tests '*RolePromptBuilderTest*'`

  This focused suite includes a guarded replay of the actual ignored task-24
  `outputs/systematic_review_pack.md` artifact when present locally; it rejected the known
  incomplete artifact in this workspace.
