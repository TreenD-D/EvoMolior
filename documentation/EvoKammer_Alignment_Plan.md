# EvoKammer Alignment Plan

This document is a current-state alignment audit for the EvoKammer engine. It replaces the older remediation framing that assumed several gaps were still open. The codebase now implements most of the original plan, so this file focuses on what is actually left, what is only partially wired, and what still needs validation.

For historical gap context only, see `.firebender/plans/remediation_ab_08f12e2b.plan.md`. Treat this alignment audit as the current source of truth.

## Current State

The following remediation items now appear implemented in code and covered by tests:

- A-1 Critic self-submission guard
- A-2 structured `SELECTOR_DECISION` event logging
- A-3 check-in score threading
- A-4 dead-end-inclusive KB retrieval verification
- A-5 `wip_task_reference` enforcement
- A-6 `budget_signature` enforcement
- A-7 budget-critical pause behavior
- A-8 privacy transform wiring on the live disclosure path
- B-1 KG consistency checking before critic review
- B-2 KG constraint persistence via `kg_schema_constraints`
- B-3 `consistencyReport` support in `StartingField`
- B-4 legacy protocol expiry with Dead End promotion
- B-5 check-in gate open/close signals
- B-6 pair-specific leak migration ceiling
- B-7 `fromCouncil` / `usedDefault` provenance fix

The code paths behind those items now exist in:

- `evokammer/orchestration/src/main/kotlin/com/achulkov/evokammer/orchestration/lifecycle/CriticPipeline.kt`
- `evokammer/api/src/main/kotlin/com/achulkov/evokammer/api/service/EvoKammerRunService.kt`
- `evokammer/orchestration/src/main/kotlin/com/achulkov/evokammer/orchestration/council/GenesisCouncil.kt`
- `evokammer/orchestration/src/main/kotlin/com/achulkov/evokammer/orchestration/watchdog/Watchdog.kt`
- `evokammer/orchestration/src/main/kotlin/com/achulkov/evokammer/orchestration/lifecycle/KGConsistencyChecker.kt`
- `evokammer/agents/src/main/kotlin/com/achulkov/evokammer/agents/tools/ReadKBTool.kt`
- `evokammer/substrate/src/main/kotlin/com/achulkov/evokammer/substrate/db/dao/KnowledgeBaseDao.kt`

Verified for A-3:

- `computeAndSubmitSubstrateScores(...)` stores the fresh per-dimension scores in `latestGroupRoundAssessments[groupId]`.
- `processDisclosureCheckIns(...)` calls `buildDisclosureCheckinResult(groupId, round)` immediately after that cache is populated.
- `buildDisclosureCheckinResult(...)` reads the same `latestGroupRoundAssessments` entry and emits the disclosure payload.

## Validation Still Pending

The env-gated live OpenRouter smoke test remains unexecuted in this session. The test scaffolding exists, but provider credentials were not available here, so live provider behavior has not been rechecked against the current wrapper path.

## Revised Workstream Order

1. Run the env-gated OpenRouter smoke test when credentials are available.
2. Rerun focused regressions around CriticPipeline, KG promotion, leak routing, and run-service gating if further code changes land.

## Acceptance Criteria

This alignment pass is complete when:

- the live provider smoke test passes with credentials,
- and the existing regression suite still passes after those changes.

## Notes

The original remediation plan is no longer a reliable source of truth for status. It was written before several of the smaller wiring fixes landed, so it should be treated as historical context rather than an active gap list.
