# engine:competition

## Purpose

The `competition` module owns group coordination, topology-aware dispatch, intelligence leaks,
progressive disclosure, resource ownership/yield, blind scoring, grader aggregation, and
MetaJudge logic.

## Connected Mechanics

| Area | Current Behavior |
|---|---|
| Group coordination | Maintains group identities/topology and routes only explicit cross-group channels. |
| Dispatch | Supports voluntary formal dispatch, lag, delivery, and trust behavior. |
| Leaks | Samples and routes leak packets with quotas/cadence. Missing transformers and transform failures both fail closed; raw leak content is never relayed. |
| Disclosure | Advances per-group stages from sealed Council-authored content using real check-in telemetry. |
| Resource map | Uses Council node lineage plus node-specific grades for claim/recapture; held nodes influence later budget yield. Completion order is not territorial authority. |
| Blind scoring | Seals submissions and computes final scores inside a run-scoped child JVM; only final outcomes and post-reveal telemetry return to the API process. |
| Graders | Uses isolated grader databases, criteria agents, Critic review, RoundJudge agents, failure telemetry, and score aggregation. |
| MetaJudge | Deterministic in-process consensus, quality weighting, and arbitration logic. |

## Grader Scope

Grader groups now have adaptive selection, generation-scoped WiP, grader-local Legacy rollover,
typed grader-authored sheets and tests, Critic review, and multi-generation substrate persistence.
Dynamic grader-local tool-registry reuse plus broad KB/KG/history acceptance remain open.
MetaJudge remains the accepted deterministic arbitration authority rather than a standalone agent.

`BlindSubmissionGate`, MetaJudge state, and `ScoringEngine` state live only in the scoring worker.
The competition module remains the pure scoring implementation and oracle; the API owns process
supervision without exposing scoring controls to agents.

## Privacy Scope

`DifferentialPrivacyTransform` is wired when `GroupCoordinator` receives an API key. Errors during
transformation fail closed. Construction without a transformer disables leak delivery, so raw
content cannot reach the oracle, inbox, or persistence path.

## Resource Authority

Territorial ownership comes from the typed Council capture map and the best node-specific grade.
Promoted artifact completion may provide lineage, but cannot grant ownership merely by finishing
first. Recent best-grade capture corrections have deterministic tests and have not yet been
validated by a fresh realistic run.

See
[`../../documentation/EvoMolior_Roadmap_and_Project_State.md`](../../documentation/EvoMolior_Roadmap_and_Project_State.md)
for current phase status and acceptance gaps.
