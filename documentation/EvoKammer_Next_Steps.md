# EvoKammer Next Steps

## Canonical Continuation

The active runtime-flow continuation plan is now:

- `documentation/EvoKammer_Runtime_Flow_Audit_and_Test_Plan.md`

Use that document as the source of truth for the remaining isolation, fallback, budget, grading, and tool-reuse work. The older remediation notes below are retained only as historical context for already-closed items.

## Closed In This Session

- WIP completion now drives `ResourceNodeClaimed` emission and MetaPlanner reset through the completion stream.
- `LifecycleManager` now finalizes in-process shutdown deterministically after `cancelAndJoin()`.
- `CriticPipeline` now completes WIP tasks on promoted submissions with `wipTaskRef`.
- `EvoKammerRunService` now threads a deterministic critic identity into the default CriticPipeline path so the self-review guard is active in production runs, not just tests.
- `CriticPipeline` now fails closed on schema-invalid KG facts before LLM review.
- `CriticPipeline` now routes active KG conflicts through its second-pass arbitration path.
- `EvoKammerRunService` now sweeps expired legacy protocols through a dedicated helper and promotes their failure maps into dead-end KB entries.
- `EvoKammerRunService` now seals the full `StartingField` snapshot, including disclosure stage content, into the run meta-archive before spawn.
- `GenesisCouncil` fallback now rescues `sealedTestSuiteJson` and `sealedTestSuiteHash` if full decode fails.
- High-severity Inspector flags now spawn a bounded Auditor once per group.
- Council-authored `stageContents` now become the runtime disclosure stages before fallback wiring.
- Failure telemetry is now routed into grader staging and read back into the next grader round.
- The grader-manager verdict path now runs once per round with a sealed-suite-backed baseline sheet.
- Added/updated tests for:
  - WIP DAO complete/abandon transitions,
  - completion-based resource claims,
  - sealed-suite rescue parsing,
  - MetaPlanner reset behavior,
  - Meta-Judge consensus/arbitration/quality scoring,
  - Auditor spawn and disclosure-stage canonicalization,
  - forced-shutdown cleanup paths.

## Still Worth Checking

- The runtime audit plan still needs the regressions it calls out for prompt-leak isolation, main-vs-grading separation, and budget realism.
- Grader DB-root isolation, criteria-phase runner isolation, criteria-review routing, judging-phase prompt wiring/score aggregation, and anonymized failure-telemetry routing/reuse are now proven.

## Verification Already Passed

- `:evokammer:substrate:test`
- `:evokammer:competition:test`
- `:evokammer:orchestration:test`
- `:evokammer:api:test`
- `:composeApp:compileKotlinJvm`
