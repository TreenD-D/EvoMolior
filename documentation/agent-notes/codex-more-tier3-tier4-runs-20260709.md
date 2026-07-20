# Codex More Tier 3/4 Runs - 2026-07-09

Goal: run additional Tier 3 and Tier 4 benchmark-style tasks with the `codex-chatgpt` backend after
the latest mechanics fixes. Evaluate results, inspect logs/artifacts for regressions, and document
evidence.

Backend decision:
- Use Codex/ChatGPT mini again.
- Runtime model policy: all roles use `gpt-5.4-mini`.
- Reasoning policy: Codex CLI adapter uses `model_reasoning_effort="low"`.

Constraints:
- Do not call `codex login` or device auth.
- Use existing Tier 3/4 inputs/configs/evaluators where possible.
- Keep claims conservative: artifact-completeness for DeepResearch Bench unless an official rubric
  evaluator exists; candidate-subset metrics for MetaSyn unless a full leaderboard evaluator exists.
- For large Codex runs, prefer inline execution with:
  `EVOMOLIOR_DISABLE_DAEMON=1` and
  `EVOMOLIOR_CODEX_TMPDIR=/Volumes/CrucialX9_1TB/AStudioProjects/EvoMolior/.evomolior-codex-tmp`.

Planned slices:
- [x] Select and run one additional Tier 3 DeepResearch-style task.
- [x] Select and run one additional Tier 4 MetaSyn-style task.
- [x] Evaluate outputs with existing deterministic or artifact-completeness checks.
- [x] Inspect logs/artifacts for scoring fallback, final-writer timeout, model leakage, and text-only
  artifact access regressions.
- [x] Update benchmark/evidence documentation.
- [x] Run targeted verification if code changes are needed.
- [x] Audit this goal against artifacts and complete it with `kent goal complete`.

## Selected Runs

Selected Codex/ChatGPT mini runs:
- Tier 3: clinical RAG fixed-corpus DeepResearch-style task, distinct from the previous official
  DeepResearch Bench prompt 62 run.
- Tier 4: MetaSyn real test task 419 candidate-subset systematic-review reconstruction, distinct
  from the previous task 24 Codex run.

The clinical pipeline config used for this pass is:
- `documentation/agent-notes/tier3-clinical-codex-pipeline-20260709.json`

Both runs used:
- `EVOMOLIOR_DISABLE_DAEMON=1`
- `EVOMOLIOR_CODEX_TMPDIR=/Volumes/CrucialX9_1TB/AStudioProjects/EvoMolior/.evomolior-codex-tmp`
- `-llm-backend codex-chatgpt`

## Tier 3 Clinical Run

Command shape:

```sh
EVOMOLIOR_DISABLE_DAEMON=1 \
EVOMOLIOR_CODEX_TMPDIR=/Volumes/CrucialX9_1TB/AStudioProjects/EvoMolior/.evomolior-codex-tmp \
evomolior run \
  -promptfile results/tier3-deepresearch-clinical-20260703/tier3-clinical-20260703/prompt.txt \
  -input results/tier3-deepresearch-clinical-only-input \
  -output results/tier3-deepresearch-clinical-codex-20260709 \
  -pipeline-config documentation/agent-notes/tier3-clinical-codex-pipeline-20260709.json \
  -fast -rounds 1 -group-count 1 -max-agents-per-group 3 \
  -round-duration-minutes 2 -budget-override 120 \
  -llm-backend codex-chatgpt \
  -run-id tier3-clinical-codex-20260709
```

Evidence:
- Run root: `results/tier3-deepresearch-clinical-codex-20260709/tier3-clinical-codex-20260709`
- Published output: `outputs/report.md`
- Evaluation artifact: `documentation/agent-notes/tier3-clinical-codex-20260709-evaluation.json`

Result:
- Primary stage `COMPLETED` in `218697` ms.
- Report stage `COMPLETED` in `187610` ms.
- `report.md` published, `14650` bytes, `251` lines.
- Required sections present: executive answer, claim ledger, evaluation rubric, failure modes and
  abstention policy, citation map.
- Claim targets present: `arXiv:2005.11401`, `PMID:33462507`, `PMID:34585260`, `PMID:35675601`.
- Claim ledger rows: `9`.

Log audit:
- No `Dimension weight must be positive`.
- No `MetaJudge: need` missing-sheet fallback.
- No `falling back to submitted dimension scores`.
- No `RUN FAILED`.
- No final-writer timeout.
- No `No space left`.
- No `deepseek`, `qwen/qwen`, or `qwen3` model-id leakage.
- Dimension-rubric scoring sheet registered; MetaJudge verdict and ScoringEngine round score were
  produced normally.
- Genesis/model lines show all roles assigned to `gpt-5.4-mini`.

## Tier 4 MetaSyn Task 419 Run

Command shape:

```sh
EVOMOLIOR_DISABLE_DAEMON=1 \
EVOMOLIOR_CODEX_TMPDIR=/Volumes/CrucialX9_1TB/AStudioProjects/EvoMolior/.evomolior-codex-tmp \
evomolior run \
  -promptfile results/tier4-real-metasyn-task419-20260704-config/metasyn_real_task_419_prompt.txt \
  -input results/tier4-real-metasyn-task419-20260704-input \
  -output results/tier4-real-metasyn-task419-codex-20260709 \
  -pipeline-config results/tier4-real-metasyn-task419-20260704-config/metasyn_real_task_419_pipeline.json \
  -fast -rounds 2 -group-count 1 -max-agents-per-group 4 \
  -round-duration-minutes 3 -budget-override 180 \
  -llm-backend codex-chatgpt \
  -run-id tier4-real-metasyn-task419-codex-20260709
```

Evidence:
- Run root: `results/tier4-real-metasyn-task419-codex-20260709/tier4-real-metasyn-task419-codex-20260709`
- Published output: `outputs/systematic_review_pack.md`
- Predictions: `documentation/agent-notes/tier4-real-metasyn-task419-codex-20260709-predictions.json`
- Evaluation: `documentation/agent-notes/tier4-real-metasyn-task419-codex-20260709-evaluation.json`

Result:
- Primary stage `COMPLETED` in `421764` ms.
- Systematic-review-pack stage `COMPLETED` in `249938` ms.
- `systematic_review_pack.md` published, `8769` bytes, `64` lines.
- Predicted included corpus IDs: `7017`.
- Predicted excluded corpus IDs: `7016`, `7014`, `7015`, `7008`, `7012`, `7018`.
- Deterministic candidate-subset metrics:
  - included recall `0.5`
  - included precision `1.0`
  - hard-negative rejection accuracy `1.0`
  - candidate-level accuracy `0.8571428571428571`
  - synthesis direction match `true`
  - false negative `7016`
  - no false positives

Log audit:
- No `Dimension weight must be positive`.
- No `MetaJudge: need` missing-sheet fallback.
- No `falling back to submitted dimension scores`.
- No `RUN FAILED`.
- No final-writer timeout.
- No `No space left`.
- No `deepseek`, `qwen/qwen`, or `qwen3` model-id leakage.
- Dimension-rubric scoring sheets registered for rounds 0 and 1; MetaJudge verdicts and
  ScoringEngine round scores were produced normally.
- Genesis/model lines show all roles assigned to `gpt-5.4-mini`.
- Primary stage had one validation retry for `final-article.html` because the generic HTML article
  lacked corpus-id rows. This did not affect the benchmark artifact: the `systematic_review_pack.md`
  stage published on its first final-writer attempt.

## Goal Completion Audit

- Requirement: do more Tier 3/4 runs.
  Evidence: completed one additional Tier 3 clinical fixed-corpus Codex run and one additional Tier 4
  MetaSyn task 419 Codex run.
- Requirement: use evaluated benchmark-style evidence.
  Evidence: Tier 3 artifact-completeness evaluation JSON and Tier 4 deterministic candidate-subset
  evaluation JSON listed above.
- Requirement: validate fixes/mechanics on these harder runs.
  Evidence: log audits above show no recurrence of the scoring fallback, zero-weight abort,
  final-writer timeout, temp-space error, or model routing regression.
- Requirement: avoid overclaiming.
  Evidence: Tier 3 is reported as artifact-completeness only; Tier 4 is reported as candidate-subset
  reconstruction, not a full MetaSyn leaderboard run.

Final verification:

```sh
jq empty \
  documentation/agent-notes/tier3-clinical-codex-20260709-evaluation.json \
  documentation/agent-notes/tier4-real-metasyn-task419-codex-20260709-predictions.json \
  documentation/agent-notes/tier4-real-metasyn-task419-codex-20260709-evaluation.json

JAVA_HOME=$(/usr/libexec/java_home -v 17.0.12) ./gradlew --no-daemon --no-configuration-cache \
  :engine:api:test --tests '*EvoMoliorRunServiceModeGatingTest*' \
  :engine:cli:test --tests '*CliArgumentsTest*' \
  :engine:agents:test --tests '*LlmExecutorFactoryTest*'
```

Result: `BUILD SUCCESSFUL`.
