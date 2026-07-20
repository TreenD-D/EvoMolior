# Tier 4 MetaSyn Task 24 Post-Hardening Complex Rerun

Date: 2026-07-04

## Run

Command:

```sh
OUT="results/tier4-real-metasyn-task24-post-hardening-20260704-complex"
JAVA_HOME=$(/usr/libexec/java_home -v 17.0.12) \
evomolior/cli/build/install/evomolior/bin/evomolior run \
  -prompt "Pipeline driver for configured Tier 4 real MetaSyn test task 24 complex systematic-review stage after complex-run hardening. The configured stage must write systematic_review_pack.md." \
  -input results/tier4-real-metasyn-20260704-input \
  -output "$OUT" \
  -pipeline-config results/tier4-real-metasyn-task24-two-complexity-20260704-config/metasyn_real_task_24_complex_pipeline.json \
  -llm-backend openrouter \
  -fast \
  -rounds 1 \
  -group-count 1 \
  -max-agents-per-group 2 \
  -round-duration-minutes 1 \
  -budget-override 80 \
  -run-id tier4-real-metasyn-task24-complex-post-hardening-20260704
```

Run artifacts:

- Pipeline root:
  `results/tier4-real-metasyn-task24-post-hardening-20260704-complex/tier4-real-metasyn-task24-complex-post-hardening-20260704`
- Final output:
  `results/tier4-real-metasyn-task24-post-hardening-20260704-complex/tier4-real-metasyn-task24-complex-post-hardening-20260704/outputs/systematic_review_pack.md`
- Stage log:
  `results/tier4-real-metasyn-task24-post-hardening-20260704-complex/tier4-real-metasyn-task24-complex-post-hardening-20260704/stages/systematic-review-pack/debug-log.txt`
- Stage archive:
  `results/tier4-real-metasyn-task24-post-hardening-20260704-complex/tier4-real-metasyn-task24-complex-post-hardening-20260704/stages/systematic-review-pack/results.zip`

Status:

- Primary stage: `COMPLETED`, duration `80598 ms`.
- `systematic-review-pack` stage: `COMPLETED`, duration `1112395 ms`.
- Pipeline completed at `2026-07-04T14:18:05.570888Z`.
- Winner: `group-0`.

CLI rebuild before run:

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 17.0.12) ./gradlew --no-configuration-cache :engine:cli:installDist
```

Result: passed.

## Output Validation

Input/output hashes:

- Input `metasyn_task_24_candidates.md`:
  `327341b8f269fe6a68b76817f814b9084f8dbaeb9edaca4274b324e9ea1d1fc7`
- Output `systematic_review_pack.md`:
  `d1612040ed46bbd0b738a31680d3a16a8ebe745457386d493d856fb3235c0f18`

Tracked prediction extraction:

- `documentation/agent-notes/tier4-real-metasyn-task24-post-hardening-complex-predictions.json`

Tracked deterministic evaluation:

- `documentation/agent-notes/tier4-real-metasyn-task24-post-hardening-complex-evaluation.json`

Evaluator command:

```sh
python3 documentation/agent-notes/evaluate_metasyn_candidate_subset.py \
  --gold documentation/agent-notes/tier4-real-metasyn-task24-gold.json \
  --predictions documentation/agent-notes/tier4-real-metasyn-task24-post-hardening-complex-predictions.json \
  --verify-files \
  --pretty
```

Metrics:

- Included recall: `0.6666666666666666`
- Included precision: `1.0`
- Hard-negative rejection accuracy: `1.0`
- Candidate-level accuracy: `0.875`
- Synthesis direction match: `true`
- True positives: `4565`, `4566`
- False negatives: `4567`
- False positives: none
- Hard-negative true negatives: `4558`, `4559`, `4568`, `4570`, `4571`

This is still a candidate-subset audit, not an official full MetaSyn leaderboard run.

## Structural Validation

The prior complex run's final output omitted `4568`, `4570`, and `4571` from
`excluded_hard_negatives.csv` and `evidence_table.csv`. The post-hardening rerun fixes that
structural problem.

Independent table replay over the new final output found:

- `included_studies`: `4565`, `4566`
- `excluded_hard_negatives`: `4558`, `4559`, `4567`, `4568`, `4570`, `4571`
- `exclusion_reasons`: `4558`, `4559`, `4567`, `4568`, `4570`, `4571`
- `evidence_table`: `4558`, `4559`, `4565`, `4566`, `4567`, `4568`, `4570`, `4571`
- Missing combined included/excluded decision rows: none
- Missing evidence table rows: none

The final-output runtime validator also accepted the generated deliverable; no
`final deliverable file rejected` / `rejected for group` warnings appeared in the stage log.

## Mechanism Caveats

The hardening improved output structure but did not fully fix grader/tool compliance:

- `completed without tool/write actions`: `57` in the systematic-review stage log.
- `no-action fallback`: `15` in the systematic-review stage log.
- Synthetic grader criteria persisted: `18`.
- Recovered substantive grader criteria: `0`; criteria outputs were not substantive enough to
  trigger the new recovery path.
- Infrastructure fallback score batches: `18`.
- Primary driver no-action completions: `2`.

The stage archive's high-level log reports:

- `group-0`: `kb=7`, `kg=1`, `noActionFallback=7`, `staged=33`, `promoted=21`
- `group-1`: `kb=7`, `kg=1`, `noActionFallback=8`, `staged=37`, `promoted=21`,
  `outlierSubmissions=3`

The final artifact is complete, but internal judge output remained intent-heavy and fallback-driven.
The round-2 summaries suggest group-1 had the fuller in-round pack, while group-0 won under the
fallback-heavy internal scoring. Therefore the rerun validates final-output structural hardening but
does not prove that grader civilization mechanics are now healthy.

## Comparison To Previous Complex Run

Objective candidate-subset metrics are unchanged:

- Previous complex run: recall `0.6666666666666666`, precision `1.0`, hard-negative rejection
  `1.0`, candidate accuracy `0.875`, false negative `4567`.
- Post-hardening complex rerun: same metrics and same false negative.

Structural deliverable quality improved:

- Previous complex output omitted `4568`, `4570`, and `4571` from key required tables.
- Post-hardening output covers all eight candidate IDs in decision coverage and the evidence table.
