# EvoKammer Grader Latency Baseline

Source: `documentation/logs/stuck_logs3.txt`

This note captures the pre-optimization grader wall-clock baseline so later prompt trimming or timeout tuning can be judged against the same evidence.

Observed timings from the log slice:

- Round 0 criteria phase:
  - `GraderResearcher` ran for `125,932 ms`
  - `GraderBuilder` ran for `60,460 ms`
  - phase wall clock from `grader_criteria_start` to `grader_criteria_done`: `186,667 ms`
- Round 0 judge phase:
  - `GraderRoundStarted` to `GraderRoundCompleted`: `301,226 ms`
  - judge score emission was visibly back-loaded, with all 12 scores emitted near the end of the phase
- Round 1 criteria phase:
  - `GraderResearcher` ran for `99,233 ms`
  - `GraderBuilder` ran for `92,605 ms`
  - phase wall clock from `grader_criteria_start` to `grader_criteria_done`: `191,844 ms`

The same log excerpt shows the round 1 judge beginning at `16:27:35.327` and still emitting score updates at `16:28:02.180`, but the excerpt does not include the final round 1 completion event. That makes the visible round 1 judge baseline a lower bound only.

Takeaway:

- The grader is not merely silent; it is slow in a way that matches the stuck perception from the UI.
- The next optimization pass should keep these numbers as the comparison baseline.
