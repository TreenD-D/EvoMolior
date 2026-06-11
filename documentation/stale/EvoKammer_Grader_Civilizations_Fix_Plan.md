# EvoKammer Grader Civilizations Fix Plan

## Goal

Fix the current grading architecture so it no longer behaves like a single fragile grader lane with shallow rubric generation.

The required outcome for this pass is:

- inspect the latest `/tmp` run evidence and anchor changes in observed failures
- make grader criteria generation deeper and more diverse
- run several grader groups, not just one
- seed grader groups from a separate grader-focused genesis council run, distinct from the worker-group starting field
- re-run the fast CLI scenario for at least 10 rounds using the same MindVortex prompt lineage as the previous validation runs

## Latest Runtime Evidence

Source run reviewed:

- `/private/tmp/evokammer-fast-verify-output-roWm54/mindvortex-fast-verify-20260603-095106`

Observed grader problems from `debug-log.txt`:

- only one grader group existed: `graderGroups=1`
- criteria agents completed without leaving usable staged criteria
- judging logged `no criteria found for grader-0 round=0`
- RoundJudge ended in the shared empty-assistant recovery path
- RoundJudge submitted `0` scores and the system fell back to substrate/sealed-suite scoring

## Implementation Direction

1. Introduce a separate grader-focused genesis council path.
   - Use a dedicated council objective focused on adversarial evaluation design, failure-mode discovery, and deep rubric generation.
   - Persist or otherwise retain its output as the grader seed for the run.

2. Move grader-group cardinality away from the current single-group default.
   - Ensure multi-group worker runs also create multiple grader groups by default.
   - Prefer council-authored grader ecology when available, but enforce a sane lower bound in runtime normalization.

3. Seed grader groups from the grader council output, not the worker starting field.
   - Primordial KB/KG, norms, and node/task seeds for grader groups should come from the grader seed.
   - Preserve worker-group seeding behavior separately.

4. Make grader criteria durable even when model behavior is weak.
   - If a criteria agent fails to stage a rubric, synthesize a fallback staged criteria artifact from its output and the run dimensions.
   - Ensure the judging phase always has real criteria context.

5. Reduce RoundJudge fragility.
   - Prefer narrower judging prompts or a fallback judging path when the aggregated all-groups judge produces no scores.
   - Keep grader output quality observable in logs and archived artifacts.

## Verification Target

After implementation:

- targeted tests for grader seeding / criteria durability / judging behavior pass
- a fast CLI run with at least 10 rounds completes
- logs show multiple grader groups
- logs show non-empty criteria context or staged criteria fallback
- grader judging no longer collapses immediately into the empty-assistant no-score path as the only effective evaluation route

## Verification Completed

- Targeted grader tests passed:
  - `:evokammer:api:test --tests com.achulkov.evokammer.api.grader.GraderRunnerCriteriaFallbackTest`
  - `:evokammer:api:test --tests com.achulkov.evokammer.api.grader.GraderRunnerJudgingPhaseTest`
  - `:evokammer:api:test --tests com.achulkov.evokammer.api.grader.GraderRunnerFailureTelemetryTest`
  - `:evokammer:api:test --tests com.achulkov.evokammer.api.grader.GraderRunnerPromptCompactionTest`
  - `:evokammer:api:test --tests com.achulkov.evokammer.api.service.EvoKammerRunServiceStartingFieldSealTest.resolveGraderStartingField\ runs\ a\ separate\ grader\ council\ and\ normalizes\ grader\ groups`

- Live fast CLI verification completed:
  - Run ID: `mindvortex-fast-grader-fix-20260603-134740`
  - Output directory: `/tmp/evokammer-fast-grader-fix-output-jmsZwG/mindvortex-fast-grader-fix-20260603-134740`
  - Mode: `-fast -rounds 10`
  - Outcome: `COMPLETED`
  - Winner: `group-0`

- Concrete runtime evidence from `debug-log.txt`:
  - `GraderRunner: starting criteria phase round=0 graderGroups=3`
  - `graderGroups=3` appears once per round, 10 times total
  - `persisted synthetic criteria` appears 53 times across the run
  - `no criteria found` appears 0 times
  - `RoundJudge spawning for round` appears 60 times, showing per-target judging across 3 graders × 2 worker groups × 10 rounds
  - `collected 1 scores from RoundJudge` appears, proving the judging path is no longer exclusively zero-score

- Final artifact path still completed correctly:
  - `summary.json` reports `COMPLETED`
  - `results.zip` contains `outputs/final-article.html`
  - archived completion record contains judge outputs for `grader-0`, `grader-1`, and `grader-2`
  - archived completion record contains submission summaries for both `group-0` and `group-1`

## Remaining Caveat

- The architecture-level issues from the goal are fixed:
  - several grader groups now run
  - grader criteria are durable even when criteria agents fail to write
  - a separate grader-genesis path exists and is covered by a targeted test
- But the live 10-round run still shows inconsistent RoundJudge productivity:
  - `collected 0 scores from RoundJudge` still appears frequently
  - the system therefore still relies heavily on substrate/sealed-suite scoring for authoritative outcomes
- So this pass fixed grader multiplicity and rubric durability, but not the full scoring effectiveness of the judge agents.
