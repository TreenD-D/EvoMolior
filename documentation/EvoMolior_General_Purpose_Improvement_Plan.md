# General-Purpose Delivery Improvement Plan

**Review date:** 2026-09-08. **Type:** proposal based on the current working tree.

The [canonical roadmap](EvoMolior_Roadmap_and_Project_State.md) remains the sole implementation
status authority. This plan does not certify new provider-backed acceptance results.

## Product Direction

Preserve the society model: disposable agents, persistent reviewed knowledge and tools, group
competition, and explicit evaluation. Make ordinary delivery depend on the user's output
contract, not a named benchmark, provider, or experimental qualification protocol.

```text
objective + inputs + output contract + finite budget
  -> Council planning -> worker society -> candidate artifact
  -> contract validation -> publication + archive
                          + explicit acceptance assessment
```

Artifact availability, execution completion, acceptance, and comparative quality are separate
facts. A usable provisional artifact must not acquire an accepted label merely because it exists.
Missing experimental evidence should not prevent generating a candidate.

## Proposed Sequence

### 1. Finish separating delivery from qualification

**Evidence:** `EvoMoliorRunService.preparePreAcceptanceWriterOutput` was ARC-specific. The
maintenance change generalizes candidate authorship, retaining notebook compilation as a
specialized branch. CLI inference still favors HTML, and qualification profiles/stage IDs encode
Appendix-B/ARC assumptions.

**Next:** pass the same typed output contract through CLI, Council, worker boot, writer, validator,
and archive. Introduce a deliverable-adapter boundary for generation, validation, and packaging;
move ARC notebook compilation and visible-LOO qualification behind it. Select qualification using
typed configuration, not a stage ID, filename, or objective substring. Keep bounded repair,
private-test confidentiality, and source-authority checks for accepted claims.

**Exit tests:** Markdown memo, JSON dataset, HTML report, and notebook use the same orchestration
contract. Each produces a validated candidate or explicit unavailable result. Failed qualification
cannot grant acceptance or erase a usable provisional candidate.

### 2. Decompose the central run service

**Evidence:** `EvoMoliorRunService` exceeds 17,000 lines; the changed `runLoop` hit the JVM method-size
limit during this maintenance build. Candidate sealing, failure recovery, and terminal cleanup
were extracted. The acceptance lock also used a global build-finished listener incompatible
with the enabled Gradle configuration cache; lock ownership now uses a task-scoped build service.

**Next:** extract cohesive phases for startup/admission, round execution, candidate production,
publication, and recovery. Give each phase immutable inputs and explicit results. Keep one owner
for terminal-state transitions and transaction boundaries; do not distribute mutable service state
across helper classes.

**Exit tests:** phase-level and end-to-end tests cover success, cancellation, writer timeout,
unavailable scoring, archive failure, and restart without changing ledger settlement or allowing
duplicate terminal publication.

### 3. Make continuation and CLI behavior predictable

**Evidence:** `CliPipeline.loadCompletedStageResult` reuses a completed summary with optional
output validation but no comparison of the new objective/input/configuration fingerprint. Fast
browser requests formerly inserted an implicit refinement stage.

**Next:** bind reuse to a persisted request fingerprint covering objective, input hashes, output
contract, relevant model/run configuration, and qualification policy. A mismatch needs a new run
or explicit continuation. Keep refinement stages explicit. Consider a documented finite default
horizon for ordinary runs, while retaining explicit reproducibility horizons; the default/budget
choice needs separate product approval.

**Exit tests:** unchanged requests resume; changing a prompt, input byte, provider configuration,
or contract cannot return old work as the new result. Ordinary fast execution starts one stage;
configured follow-ups retain their provenance.

### 4. Make artifact handling byte-safe and bounded

**Evidence:** `CliPipeline` output verification uses UTF-8 readers; ZIP assembly holds payloads in
memory and performs repeated verification.

**Next:** publish manifest-addressed files using exact byte hashes and stream archives to disk.
Dispatch semantic validation by media type. Enforce bounded artifact sizes and verify manifests
at trust boundaries without repeated full in-memory ZIP reconstruction.

**Exit tests:** binary output round-trips unchanged; large bundles have bounded peak memory;
path traversal, symlinks, duplicate names, hash mismatches, and partial publication are rejected.

### 5. Consolidate runtime composition and productive-work signals

**Evidence:** `EvoMoliorProductionRuntime` has multiple lifecycle/grader construction paths. The
legacy lifecycle path uses HIGH reasoning and no monetary cap, whereas envelope construction
uses run settings. Agent factories generate KG/tool abstention plan updates; selector allocation
consumes pending work.

**Next:** consolidate run-scoped construction around one immutable execution configuration.
Represent task obligations and abstentions directly so compliance records cannot accidentally
become productive backlog. Batch substrate queries for selector and reconciliation snapshots.

**Exit tests:** all construction paths honor the same limits; abstention cannot create repeated
repair work; signal snapshots are transactionally consistent.

### 6. Prevent storage accumulation and verify the production target

**Evidence:** the initial checkout occupied about 224 GiB, overwhelmingly temporary test trees and
copied distributions. Some test bundles seal directories read-only. Existing CI builds mobile apps
and a narrow API log-scanner test rather than verifying the main CLI delivery path.

**Next:**

- Convert unmanaged test temporary directories to lifecycle-managed fixtures, restoring sealed
  directory permissions during teardown.
- Use one retained dependency cache and bounded disposable-distribution retention.
- Correct CLI classpath-snapshot teardown: the generated launcher installs an EXIT trap and then
  replaces the shell with `exec`, so that trap cannot provide normal-process-exit cleanup. Preserve
  signal/exit-code behavior and active daemon snapshots when designing its lifecycle owner.
- Keep run evidence and continuation archives under a separate, explicit retention policy.
- Keep generated directories/packages ignored; reject force-added ignored files and indexed
  files above 10 MiB in CI.
- Add CLI distribution building and provider-free multi-format delivery tests to CI. Keep paid
  provider checks opt-in with explicit spend ceilings.

**Exit tests:** repeated tests do not accumulate unbounded scratch trees; generated/oversized
files fail hygiene checks; a fresh checkout builds and runs the supported CLI path.

## Boundaries to Preserve

- Role-aware source egress and secret isolation.
- Bounded tool processes, worker budgets, and cancellation.
- Staging/Critic authority for canonical substrate writes.
- Group/grader separation and sealed-test confidentiality.
- Exact lineage, output hashes, and honest accepted/provisional/unavailable status.
- Lifecycle/ledger settlement and durable recovery before terminal publication.

Removing these changes safety or evidence semantics, not just delivery complexity.
