# EvoKammer Current Status

Date: 2026-05-31

## What is fixed

- Blind reveal was double-processed; `BlindTournamentService` now processes revealed scores once.
- RoundJudge score submissions count as productive work, so free score-only judge runs no longer get misclassified as no-progress completions.
- The grader default model was moved off the slower 120b default to `deepseek/deepseek-v4-flash`.
- Round summaries are archived and the desktop post-run screen now renders parsed round summary content instead of raw JSON only.
- The durable run-completion archive now carries the final-answer summary, judge outputs, and submission summaries, and the post-run analysis screen renders that archived record.
- The live substrate scoring path is now separated from the supplemental sealed-suite verdict path.
- API and Compose JVM compile/test coverage passed after the above changes.
- Focused regressions now cover the archived final-answer card and durable completion payload decode path.

## What was the root cause of the `0.0` scores

`computeAndSubmitSubstrateScores()` was still using the sealed-suite evaluator instead of real substrate metrics. The round score path was therefore detached from actual KB/KG/tool/WIP activity and could collapse to zero even when the substrate was rich.

## Current architecture state

- Live substrate metrics are now computed separately from the sealed suite.
- The sealed suite remains a supplemental scoring/verdict layer.
- The round summary archive now has a readable UI surface in the desktop app.

## Remaining verification gap

- A fresh full civilization run still needs to be executed to confirm the runtime log no longer shows:
  - duplicate grading in the round loop,
  - all-`0.0` scoring,
  - empty final output,
  - or judge starvation from slow models.

## Important notes for the next agent

- The user currently preferred static verification when asked about running a new full simulation.
- If the user later approves a fresh run, verify the full log against `documentation/logs/full_run_logs.txt` and compare timestamps for the grader criteria/judging phases.
- If the log still looks slow, look first at grader model selection and judge prompt size, then at round-tail parallelism.
- The current code path already defaults grader agents to `deepseek/deepseek-v4-flash`; if a future log still shows `openai/gpt-oss-120b`, treat that as a launcher/configuration mismatch until proven otherwise.
