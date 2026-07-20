# Codex Mechanics Log Audit And Follow-Up Benchmarks - 2026-07-09

Goal: audit the latest clean Codex Tier 3/4 benchmark run logs for mechanics, compounding, artifact
publication, model routing, text-only behavior, retries, scoring, and any orchestration issues. Fix
issues that are concrete and reproducible, then run several additional benchmark tasks, evaluate
results, and update evidence.

Primary audit targets:
- Tier 3 DeepResearch Bench prompt 62 Codex clean run:
  `results/tier3-official-deepresearchbench-codex-final-20260709/tier3-drb-q62-codex-final-20260709`
- Tier 4 MetaSyn task 24 Codex clean run:
  `results/tier4-real-metasyn-task24-codex-simple-clean-20260709/tier4-real-metasyn-task24-codex-simple-clean-20260709`

Constraints:
- Backend remains `codex-chatgpt`.
- Codex runs use `gpt-5.4-mini` for all roles and `model_reasoning_effort="low"`.
- Codex backend remains text-only; no Koog tool bridge is assumed.
- Do not invoke `codex login` or device auth.
- Benchmark claims must stay scoped to available evaluators and artifacts.

Work plan:
- [x] Inventory mechanics/log issues in the primary clean Tier 3/4 runs.
- [x] Separate benign recovered retries from real issues that affect output quality, compounding, or metrics.
- [x] Fix concrete root causes in code/config/prompting.
- [x] Run targeted verification for any code fixes.
- [x] Run several additional Codex benchmark tasks after fixes.
- [x] Evaluate each additional run with existing deterministic or artifact-completeness evaluators.
- [x] Update benchmark/evidence docs.
- [x] Audit the active goal against concrete artifacts and complete it with `kent goal complete`.

## Primary Log Audit Findings

Audited runs:
- `results/tier3-official-deepresearchbench-codex-final-20260709/tier3-drb-q62-codex-final-20260709`
- `results/tier4-real-metasyn-task24-codex-simple-clean-20260709/tier4-real-metasyn-task24-codex-simple-clean-20260709`

Findings:
- Model routing was healthy in the clean runs. Genesis logs assigned every role to `gpt-5.4-mini`,
  and the audited logs had no `deepseek`, `qwen/qwen`, or `qwen3` model-id leakage.
- The recurring real mechanics issue was blind scoring fallback:
  `MetaJudge: need 1 sheets for round 0, have 0`, followed by fallback to submitted dimension
  scores. Root cause: CLI/test-style runs can bypass full Genesis and therefore have no sealed test
  suite, while `BlindTournamentService` still expects at least one rubric sheet.
- Tier 3 also had two `RUN FAILED` entries from final-deliverable Builder attempts in the primary
  stage. The detailed sequence showed attempts 1 and 2 timed out, were killed via `run_termination`,
  and attempt 3 completed successfully. This was a recovered final-writer retry artifact, but the
  retry was avoidable for Codex because test-run final-writer attempts were capped at 90 seconds.
- Tier 4 clean logs had no `RUN FAILED`, `ABORTED`, `No space left`, or forbidden model-id hits.
- Codex/text-only behavior is still expected to produce KG/tool abstention records rather than native
  tool calls. That is not treated as a mechanics failure unless the task needs artifact/tool access
  that was not embedded in the prompt.

## Fixes Implemented

- Added dimension-only blind grading sheets for runs that have declared scoring dimensions but no
  sealed suite. This keeps blind scoring on the MetaJudge path instead of silently using the synthetic
  fallback scorer.
- Normalized rubric weights in both dimension-only blind sheets and sealed-suite grading sheets.
  This fixed the live benchmark abort where MetaJudge quality scoring reduced an over-weighted sheet
  to zero quality, producing invalid `0.0` verdict weights.
- Changed Codex final-deliverable attempt timeout behavior so Codex runs use the production
  300-second final-writer timeout even when the run is marked `isTestRun=true`.

Code locations:
- `evomolior/api/src/main/kotlin/com/achulkov/evomolior/api/service/EvoMoliorRunService.kt`
- `evomolior/api/src/main/kotlin/com/achulkov/evomolior/api/service/SealedTestSuiteEvaluator.kt`
- `evomolior/api/src/test/kotlin/com/achulkov/evomolior/api/EvoMoliorRunServiceModeGatingTest.kt`

Verification:

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 17.0.12) ./gradlew --no-daemon --no-configuration-cache \
  :engine:api:test --tests '*EvoMoliorRunServiceModeGatingTest*' \
  :engine:cli:installDist
```

Result: `BUILD SUCCESSFUL`.

Earlier broader targeted verification also passed:

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 17.0.12) ./gradlew --no-daemon --no-configuration-cache \
  :engine:api:test --tests '*EvoMoliorRunServiceModeGatingTest*' \
  :engine:cli:test --tests '*CliArgumentsTest*' \
  :engine:agents:test --tests '*LlmExecutorFactoryTest*' \
  :engine:core:compileKotlin \
  :engine:agents:compileKotlin \
  :engine:cli:compileKotlin
```

Result: `BUILD SUCCESSFUL`.

## Follow-Up Benchmark Runs

The first post-fix attempt at these three slices failed before task execution with:
`Dimension weight must be positive, got 0.0 for 'correctness'`. This exposed the rubric-weight
normalization bug described above. Evidence for the failed attempts is retained in:
- `results/codex-mechanics-followup-frames-offset1-20260709/tier0-benchmark-report.json`
- `results/codex-mechanics-followup-pubmed-offset1-20260709/tier0-benchmark-report.json`
- `results/codex-mechanics-followup-scifact-offset1-20260709/tier0-benchmark-report.json`

After the rubric normalization fix, the same three slices completed:

| Slice | Result | Evidence |
|---|---:|---|
| FRAMES `frames-1` | `1/1` exact; final answer `37th`; evidence path present. | `results/codex-mechanics-followup-frames-offset1-fixed-20260709/tier0-benchmark-report.json` |
| ScholarQABench PubMedQA offset 1 | `1/1` exact; final answer `yes`; evidence path, citation support, and claim coverage all `1.0`. | `results/codex-mechanics-followup-pubmed-offset1-fixed-20260709/tier0-benchmark-report.json` |
| ScholarQABench SciFact offset 1 | `1/1` exact; final answer `false`; evidence path, citation support, and claim coverage all `1.0`. | `results/codex-mechanics-followup-scifact-offset1-fixed-20260709/tier0-benchmark-report.json` |

Structured evaluation artifact:
- `documentation/agent-notes/codex-mechanics-followup-benchmark-evaluation-20260709.json`

Mechanics audit of the fixed result bundles:
- No `Synthetic fallback` marker in archived text.
- No `Dimension weight must be positive` marker.
- No `RUN FAILED` marker.
- No final-writer timeout marker.
- No forbidden Qwen/OpenRouter model-id marker.
- Each bundle contains a legacy protocol artifact and a deliverable-skeleton-tree artifact.
- Each high-level run log records one group summary and substrate health.
- Each report has `failures: []` and all model overrides set to `gpt-5.4-mini`.

Remaining caveat:
- These are one-round, one-group Codex compatibility benchmarks, so there is limited opportunity for
  multi-generation compounding. The relevant compounding evidence is local to the run: promoted KB
  artifacts, round summaries, legacy protocol extraction, trust decay, and deliverable skeleton tree
  publication. Tool and KG counts remain zero because Codex is intentionally text-only and these
  slices did not require a native tool bridge.

## Goal Completion Audit

- Requirement: go through logs of the run and identify mechanics/compounding issues.
  Evidence: audited primary Tier 3/4 clean Codex run logs and recorded findings above. Concrete issues
  found: MetaJudge rubric-sheet absence/fallback, Codex final-writer test timeout cap, and live
  follow-up abort from non-normalized rubric weights.
- Requirement: improve anything not working as it should and fix issues.
  Evidence: code changes in `EvoMoliorRunService.kt` and `SealedTestSuiteEvaluator.kt`, with
  regression coverage in `EvoMoliorRunServiceModeGatingTest.kt`.
- Requirement: do several more benchmark task runs.
  Evidence: three fixed Codex follow-up benchmark slices completed after the fixes:
  FRAMES `frames-1`, ScholarQABench PubMedQA offset 1, and ScholarQABench SciFact offset 1.
- Requirement: evaluate results.
  Evidence: benchmark reports under `results/codex-mechanics-followup-*-fixed-20260709/` and the
  structured summary `documentation/agent-notes/codex-mechanics-followup-benchmark-evaluation-20260709.json`.
- Requirement: verify fixes.
  Evidence: final Gradle command passed:

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 17.0.12) ./gradlew --no-daemon --no-configuration-cache \
  :engine:core:compileKotlin \
  :engine:agents:compileKotlin \
  :engine:api:test --tests '*EvoMoliorRunServiceModeGatingTest*' \
  :engine:cli:test --tests '*CliArgumentsTest*' \
  :engine:agents:test --tests '*LlmExecutorFactoryTest*' \
  :engine:cli:installDist
```

Result: `BUILD SUCCESSFUL`.
