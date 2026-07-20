# Tier 4 MetaSyn Task 419 Post-Fix Complex Rerun

Date: 2026-07-04

## Purpose

Rerun a benchmark task after the `search_kb` robustness fix and final-round-summary prompt
plumbing fix, then evaluate the persisted output rather than intermediate logs.

## Run

- Dataset: `THUIR/MetaSyn`
- Official test task ID: `419`
- Scoring scope: candidate subset with all matched positive corpus IDs available in the public task
  row.
- Run ID: `tier4-real-metasyn-task419-complex-rerun-final-summary-20260704`
- Output root:
  `results/tier4-real-metasyn-task419-20260704-complex-rerun-final-summary/tier4-real-metasyn-task419-complex-rerun-final-summary-20260704`
- Published output:
  `results/tier4-real-metasyn-task419-20260704-complex-rerun-final-summary/tier4-real-metasyn-task419-complex-rerun-final-summary-20260704/outputs/systematic_review_pack.md`
- Pipeline config:
  `results/tier4-real-metasyn-task419-20260704-config/metasyn_real_task_419_complex_pipeline.json`

The refreshed CLI was installed first with:

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 17.0.12) ./gradlew --no-configuration-cache :engine:cli:installDist
```

The first install attempt without `--no-configuration-cache` reached `installDist` but failed while
storing Gradle configuration cache because `verifyStartScriptClasspathSnapshot` captures a
`Project`; rerunning with configuration cache disabled passed.

## Outcome

- Primary stage: `COMPLETED`, duration `62228 ms`.
- Benchmark stage: `COMPLETED`, duration `1454079 ms` (`24.23465` minutes).
- Benchmark stage winner: `group-1`.
- Published `systematic_review_pack.md`: yes.
- Persisted predicted included IDs: `7017`.
- Persisted predicted excluded IDs: `7008`, `7012`, `7014`, `7015`, `7016`, `7018`.
- Output issue: the excluded table contains a duplicate row for `7014`; unique-ID scoring is
  unaffected.

## Metrics

| Metric | Post-fix rerun |
|---|---:|
| Included-study recall | `0.5` |
| Included-study precision | `1.0` |
| Hard-negative rejection accuracy | `1.0` |
| Candidate-level accuracy | `0.8571428571428571` |
| Synthesis direction match | `true` |
| False negative IDs | `7016` |

This rerun did **not** improve the objective candidate-subset result. The persisted final output
still excludes official matched positive `7016` because it judges the intervention as in-person
integrated collaborative care rather than internet/mobile-based.

## Verification

```sh
python3 documentation/agent-notes/evaluate_metasyn_candidate_subset.py \
  --gold documentation/agent-notes/tier4-real-metasyn-task419-gold.json \
  --predictions documentation/agent-notes/tier4-real-metasyn-task419-complex-rerun-final-summary-predictions.json \
  --verify-files \
  --pretty
```

The evaluator returned recall `0.5`, precision `1.0`, hard-negative rejection accuracy `1.0`,
candidate-level accuracy `0.8571428571428571`, and false negative `7016`.

## Caveats

- This is still a candidate-subset run, not a full official MetaSyn leaderboard run.
- The task 419 `7016` gold label remains scientifically ambiguous under the prompt because the
  abstract does not clearly describe an internet/mobile intervention.
- The rerun had grader warning fallbacks in logs. The persisted output was still produced and was
  evaluated directly.
