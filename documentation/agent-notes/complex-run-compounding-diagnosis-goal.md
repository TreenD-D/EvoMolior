# Complex Run Compounding Diagnosis Goal Log

Started: 2026-07-04

## User Goal

Find out why the more complex run did not improve: was the task already solved earlier, or are
compounding mechanics not working across rounds? Fix issues to improve robustness and results.

## Locked Interpretation

- Investigate the task 419 simple-vs-complex comparison:
  - simple baseline: `tier4-real-metasyn-task419-20260704`
  - complex run: `tier4-real-metasyn-task419-complex-20260704`
- Determine whether no-improvement was caused by:
  - benchmark/gold ambiguity for `7016`;
  - agents not reading/using the full input;
  - agents failing to write durable KB/KG/staging artifacts;
  - later rounds/final deliverable writer not reusing earlier evidence;
  - group/round winner selection not tracking benchmark-correct evidence.
- Apply code/config/docs fixes that improve robustness and can be verified.
- Do not claim the benchmark improved unless rerun/evaluation evidence proves it.

## Plan

- [x] Inspect complex run logs/archives for cross-round evidence of compounding and task-419
  decisions about `7016`.
- [x] Inspect relevant runtime/final-output code paths for causes of no-progress or evidence loss.
- [x] Identify root causes and choose fixes with minimal product ambiguity.
- [x] Implement fixes.
- [x] Run targeted tests.
- [x] Rerun or otherwise verify on task 419 if feasible.
- [x] Update tracked audit docs with diagnosis, fixes, and verification.
- [x] Stage tracked changes.
- [ ] Mark goal complete.

## Diagnosis

- Complex task 419 did not simply "solve the task earlier." The persisted final deliverable
  `results/tier4-real-metasyn-task419-20260704-complex/tier4-real-metasyn-task419-complex-20260704/stages/systematic-review-pack/outputs/systematic_review_pack.md`
  excluded official gold ID `7016`, matching the simple run's false negative.
- Cross-round compounding was weak in rounds 0-1: round summaries show groups mostly ingested
  `metasyn_task_419_candidates.md` and recorded blockers rather than durable screening decisions.
  Round 0 group-0 summary explicitly says no `systematic_review_pack.md` existed yet; round 1
  group-0 still only listed candidates and had no CSV/KG facts.
- A tool robustness bug reduced diversity in round 0. `agent-outlier-6cd7eb7c` first called
  `search_kb` successfully, then called it with nested/malformed args
  `{"value":{"query":"systematic review article screening inclusion exclusion criteria RCT","maxResults":5}}`.
  Because `SearchKBTool.Args.query` was required, Koog/Kotlinx failed argument parsing and the
  blocking failure policy aborted the Outlier.
- Late in round 2, group-1 did generate a conflicting/better benchmark draft: the archived
  `outputs/round_summary/16-round-summary-...-2.json` group-1 HTML fragment includes both
  `7016` and `7017` as included. The archived `final_answer.md` also preserves that fragment.
  However, the persisted final output file still selected/exposed the earlier one-included-study
  version that excluded `7016`. So the complex run did create some benchmark-correct evidence,
  but final deliverable synthesis did not treat it as authoritative.
- Code inspection found why this can happen: `spawnFinalDeliverableFile` built the final writer
  prompt from the run objective, round count, group ID, and output spec, but it did not pass the
  winning group's final round submission summary. Late round decisions that existed in summaries
  but were not fully promoted into the winner KB could be absent from the final writer's context.
- The `7016` case remains scientifically ambiguous: the abstract describes integrated
  collaborative care rather than a clear internet/mobile intervention. EvoMolior's exclusion is
  defensible from the prompt text, but candidate-subset scoring counts `7016` as gold included
  because it appears in official `matched_corpus_ids`.

## Fixes Applied

- `SearchKBTool.Args.query` now defaults to `""`, matching `ReadKBTool`. Missing `query` no longer
  parse-fails and kills the agent.
- Blank semantic searches now list bounded trust-ranked active KB entries instead of embedding an
  empty query. `maxResults` is clamped to `1..25` to avoid unbounded output.
- The agent prompt tool reminder now documents `search_kb(query?, domain?, maxResults?)`, so the
  prompt contract matches runtime behavior.
- Final deliverable synthesis now passes the winning group's final round submission summary into
  `RolePromptBuilder.buildFinalDeliverableFile`. The prompt includes a dedicated
  `WINNING GROUP FINAL ROUND SUMMARY` section and instructs the final writer to reconcile late
  accepted evidence instead of silently discarding concrete decisions recorded there.

## Verification

- Initial test attempt without Java 17 failed at Gradle configuration with host JDK `26.0.1`.
- Passed with the required Java 17 runtime:
  `JAVA_HOME=$(/usr/libexec/java_home -v 17.0.12) ./gradlew :engine:agents:test --tests '*SearchKBToolTest*' --tests '*RolePromptBuilderTest*' :engine:api:compileKotlin`
- No full task 419 rerun was performed after this fix. The completed complex run took about
  20.36 minutes. The fixes improve the robustness paths that failed in the archived run, but they
  do not retroactively change the archived candidate-subset metrics.

## Post-Fix Rerun Goal

Started: 2026-07-04

User goal: rerun another benchmark task and evaluate the result. Locked interpretation: rerun the
MetaSyn task 419 complex candidate-subset benchmark after the `search_kb` and final-round-summary
plumbing fixes, then evaluate the persisted `outputs/systematic_review_pack.md` against the
tracked task 419 gold labels.

Plan:

- [x] Rebuild/install the EvoMolior CLI from the current runtime code.
- [x] Run focused unit/API regression tests for search-kb fallback and final-writer summary
  plumbing.
- [x] Run the post-fix task 419 complex CLI benchmark under a new output root.
- [x] Extract predictions from the new persisted `systematic_review_pack.md`.
- [x] Evaluate predictions with `evaluate_metasyn_candidate_subset.py`.
- [x] Update tracked evidence with command/results/caveats.
- [x] Stage tracked changes and mark goal complete.

Post-fix run command:

```sh
OUT=results/tier4-real-metasyn-task419-20260704-complex-rerun-final-summary
JAVA_HOME=$(/usr/libexec/java_home -v 17.0.12) \
evomolior/cli/build/install/evomolior/bin/evomolior run \
  -prompt "Pipeline driver for configured Tier 4 real MetaSyn test task 419 systematic-review stage after final-round-summary plumbing fix. The configured stage must write systematic_review_pack.md." \
  -input results/tier4-real-metasyn-task419-20260704-input \
  -output "$OUT" \
  -pipeline-config results/tier4-real-metasyn-task419-20260704-config/metasyn_real_task_419_complex_pipeline.json \
  -llm-backend openrouter \
  -fast \
  -rounds 1 \
  -group-count 1 \
  -max-agents-per-group 2 \
  -round-duration-minutes 1 \
  -budget-override 80 \
  -model Builder=deepseek/deepseek-v4-flash \
  -run-id tier4-real-metasyn-task419-complex-rerun-final-summary-20260704
```

Post-fix rerun artifacts:

- Output root:
  `results/tier4-real-metasyn-task419-20260704-complex-rerun-final-summary/tier4-real-metasyn-task419-complex-rerun-final-summary-20260704`
- Persisted output:
  `results/tier4-real-metasyn-task419-20260704-complex-rerun-final-summary/tier4-real-metasyn-task419-complex-rerun-final-summary-20260704/outputs/systematic_review_pack.md`
- Tracked predictions:
  `documentation/agent-notes/tier4-real-metasyn-task419-complex-rerun-final-summary-predictions.json`
- Tracked evaluation:
  `documentation/agent-notes/tier4-real-metasyn-task419-complex-rerun-final-summary-evaluation.json`
- Tracked audit:
  `documentation/agent-notes/tier4-real-metasyn-task419-complex-rerun-final-summary.md`

Post-fix rerun result:

- Primary stage: `COMPLETED`, duration `62228 ms`.
- Benchmark stage: `COMPLETED`, duration `1454079 ms` (`24.23465` minutes).
- Benchmark stage winner: `group-1`.
- Persisted predicted included IDs: `7017`.
- Persisted predicted excluded IDs: `7008`, `7012`, `7014`, `7015`, `7016`, `7018`.
- Candidate-subset metrics: recall `0.5`, precision `1.0`, hard-negative rejection `1.0`,
  candidate-level accuracy `0.8571428571428571`, false negative `7016`.
- Conclusion: the post-fix rerun did not improve objective benchmark results. The final persisted
  output still excludes official matched positive `7016` as not internet/mobile-based. The fixes
  improve robustness/context plumbing but are insufficient to overcome this benchmark/gold
  ambiguity or force different screening behavior.
- Follow-up hardening: the post-fix output also contained a duplicate `7014` exclusion row. Even
  though the evaluator scores unique candidate IDs, this is an artifact-correctness bug. Added
  final-output validation that rejects duplicate `corpus_id` rows in fenced CSV blocks and tests
  this path in `EvoMoliorRunServiceFinalOutputImageGateTest`.
