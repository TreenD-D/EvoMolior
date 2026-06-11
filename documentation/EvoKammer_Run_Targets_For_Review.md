# EvoKammer Run Targets For Review

Date: 2026-06-05

Constraint:

- Do not execute these now.
- These are review-only targets with full CLI commands for later approval.
- Prerequisite before any live execution:
  - `OPENROUTER_API_KEY` must be exported in the shell that runs the command.
  - If the key is missing, the target cannot be executed meaningfully.

## Target 1 — Phase 9 Sub-Agent Delegation Smoke

Purpose:

- verify that eligible roles can call `delegate_sub_task`
- verify child outputs persist into shared substrate
- verify sub-agents cannot recursively delegate

Suggested command:

```sh
./gradlew --no-daemon :evokammer:cli:run --args="-runid phase9-subagent-smoke-20260605 \
-promptfile /Volumes/CrucialX9_1TB/AStudioProjects/EvoMolior/documentation/prompts/mindvortex_fast_quality_prompt.txt \
-input /Volumes/CrucialX9_1TB/AStudioProjects/EvoMolior/documentation/examples \
-output /tmp/evokammer-phase9-subagent-output \
-debug \
-fast \
-rounds 5 \
-groupcount 2 \
-maxagents 3 \
-budget 220 \
-model Researcher=qwen/qwen3.5-flash-02-23 \
-model Builder=z-ai/glm-4.7-flash \
-model Planner=qwen/qwen3.5-flash-02-23 \
-model Critic=deepseek/deepseek-v4-flash \
-model Archivist=openai/gpt-oss-120b \
-model Educator=qwen/qwen3.5-flash-02-23 \
-model Connector=qwen/qwen3.5-flash-02-23 \
-model Outlier=z-ai/glm-4.7-flash \
-model Inspector=deepseek/deepseek-v4-flash \
-model Auditor=deepseek/deepseek-v4-flash \
-model MetaPlanner=openai/gpt-oss-120b \
-openrouter-api-key $OPENROUTER_API_KEY"
```

Review focus:

- debug log contains `delegate_sub_task`
- child agent IDs start with `sub-`
- staging rows attributed to sub-agents exist
- no nested sub-agent recursion succeeds

Expected output root:

- `/tmp/evokammer-phase9-subagent-output/phase9-subagent-smoke-20260605`

## Target 2 — Phase 10 Hardening Full Civilization Replay

Purpose:

- verify the full runtime path after recent grading, archive, and crash-resume fixes
- compare event ordering and output quality against prior audited runs

Suggested command:

```sh
./gradlew --no-daemon :evokammer:cli:run --args="-runid phase10-full-hardening-20260605 \
-promptfile /Volumes/CrucialX9_1TB/AStudioProjects/EvoMolior/documentation/prompts/mindvortex_fast_quality_prompt.txt \
-input /Volumes/CrucialX9_1TB/AStudioProjects/EvoMolior/documentation/examples \
-output /tmp/evokammer-phase10-hardening-output \
-debug \
-rounds 10 \
-groupcount 6 \
-maxagents 4 \
-budget 600 \
-model Researcher=qwen/qwen3.5-flash-02-23 \
-model Builder=z-ai/glm-4.7-flash \
-model Planner=qwen/qwen3.5-flash-02-23 \
-model Critic=deepseek/deepseek-v4-flash \
-model Archivist=openai/gpt-oss-120b \
-model Educator=qwen/qwen3.5-flash-02-23 \
-model Connector=qwen/qwen3.5-flash-02-23 \
-model Outlier=z-ai/glm-4.7-flash \
-model Inspector=deepseek/deepseek-v4-flash \
-model Auditor=deepseek/deepseek-v4-flash \
-model MetaPlanner=openai/gpt-oss-120b \
-openrouter-api-key $OPENROUTER_API_KEY"
```

Review focus:

- exactly one production grading pass per round
- event order includes `grader_criteria_start`, `grader_judge_start`,
  `grader_judge_result`, `round_summary_result`, `final_answer`
- final article path is authored output, not archive scaffold fallback
- no score-seal leakage before gate close

Expected output root:

- `/tmp/evokammer-phase10-hardening-output/phase10-full-hardening-20260605`

## Target 3 — Crash-Resume Regression Confirmation

Purpose:

- verify a fresh persisted run can be interrupted and resumed without creating a new run ID
- verify final configured outputs survive resume

Suggested command:

```sh
./gradlew --no-daemon :evokammer:cli:run --args="-runid crash-resume-regression-20260605 \
-prompt 'Produce a strict JSON evidence brief using the uploaded artifacts only.' \
-input /Volumes/CrucialX9_1TB/AStudioProjects/EvoMolior/documentation/examples \
-output /tmp/evokammer-crash-resume-regression-output \
-debug \
-fast \
-rounds 5 \
-groupcount 2 \
-maxagents 2 \
-budget 180 \
-model Researcher=qwen/qwen3.5-flash-02-23 \
-model Builder=z-ai/glm-4.7-flash \
-model Planner=qwen/qwen3.5-flash-02-23 \
-model Critic=deepseek/deepseek-v4-flash \
-model Archivist=openai/gpt-oss-120b \
-model Educator=qwen/qwen3.5-flash-02-23 \
-model Connector=qwen/qwen3.5-flash-02-23 \
-model Outlier=z-ai/glm-4.7-flash \
-model Inspector=deepseek/deepseek-v4-flash \
-model Auditor=deepseek/deepseek-v4-flash \
-model MetaPlanner=openai/gpt-oss-120b \
-openrouter-api-key $OPENROUTER_API_KEY"
```

Manual follow-up review:

- interrupt after persisted state exists
- resume with the same run ID and same output root
- verify no replacement run directory is created
- verify configured final output path exists in `results/` and `results.zip`

## Target 4 — Desktop Replay / Phase 10 UI Flow

Purpose:

- verify the desktop replay hook reaches `RunCompleted`
- verify replay log export path is written
- verify archived final answer renders in the post-run analysis UI

Suggested command:

```sh
./gradlew --no-daemon :composeApp:run --args="--replay-run-id phase10-full-hardening-20260605" \
-Ddevokammer.replay.enabled=true \
-Ddevokammer.replay.logPath=/tmp/evokammer-desktop-replay-log.txt
```

Review focus:

- replay log file exists
- dashboard/post-run analysis screens render durable archived fields
- no replay-only path skips final completion handling

## Recommendation Order

1. Target 1 — quickest evidence for actual Phase 9 activation.
2. Target 3 — confirms crash-resume remains stable after later edits.
3. Target 2 — strongest Phase 10 runtime hardening validation.
4. Target 4 — UI replay verification after a successful full runtime target exists.
