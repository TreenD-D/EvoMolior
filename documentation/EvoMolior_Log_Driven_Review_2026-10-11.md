# Log-Driven Review and Fixes — 2026-10-11

**Type:** dated review. The [canonical roadmap](EvoMolior_Roadmap_and_Project_State.md) remains the
sole status authority. No provider run was started for this review. All findings come from the
retained logs, databases, and outputs of the 2026-09-08/09 Luna runs and the 2026-10-01
geo-weather mixed-family research and synthesis runs.

## What the last runs actually show

| Signal | Evidence (Oct 1 runs) |
|---|---|
| Ordinary workers did almost none of the work | Builders made 8 of the 284 attempts before the disk filled (2.8%); grader criteria, criteria review, and judging made 186 of them (65%) and 322 of all 422 (76%). |
| Workers sat idle every round | Workers finished in 3.5–5 min; grader criteria authoring took 12–16 min on the critical path. |
| Judges never saw the deliverable | RoundJudge prompts kept 2,000 chars per snapshot and 600 per KB document; every dimension was scored "50 / insufficient evidence", giving an all-equal tie. |
| The tie picked the weaker source | No-winner fallback chose the alphabetically first group (3,519-word draft) over the group holding a 5,033-word report. |
| The writer could not read that draft | `read_kb` truncated bodies at 6,000 chars without paging; the writer saw 768 of 3,519 words. Archivist KB reads threw on missing lineage anchors. |
| Workers were killed early | Ordinary TTL = `atomicNodeEffortBound × 60 s` clamped to 300 s, but the bound counts agent slots, not minutes; one Builder was killed between staging and committing its report. |
| Planning ignored the budget | Council feasibility assumed 3 worker slots; 300 credits / 2 groups / 4 rounds funds one 50-credit Builder per group-round. 0 of 10 mandatory nodes were covered. |
| Wasted Critic calls | 28 of 51 worker Critic calls reviewed runtime bookkeeping (source-fetch excerpts, fallbacks); 6 of 9 criteria review passes hit the 120 s deadline with the methodology draft (staged last) unreviewed, triggering a needless 5-minute re-authoring cycle. |
| Disk exhaustion cascade | 138 of 422 attempts (33%) happened after the first `No space left on device`, all failing locally. |
| Broken links counted as valid | A Sentinel-2 URL with two U+200B characters in its host satisfied the 25-URL minimum; the source check did not flag it. |
| Source loss to redirects | The priority IGN RGE ALTI source on an already approved host became an "inspection gap" because every redirect was blocked without naming the destination. |
| Stale result reuse | Re-running with the same `-run-id` and a different prompt or inputs silently returned the old output. |
| Misleading telemetry | 404 of 422 provider calls labelled `role=UNKNOWN`; MetaJudge reported 9 of 9 dimensions "contested" from mixed weight scales; an expected per-round budget message logged as WARN; 57% of the 5.3 MB log was Codex transcript embedded in three exceptions. |

## Fixes implemented

| Area | Change | Files |
|---|---|---|
| Judge evidence | Shared 24k-char KB budget per judge snapshot (water-filled, 600-char floor); 40k-char prompt budget (≥4k per group) instead of a flat 2k cut. | `CanonSnapshotExporter.kt`, `RolePromptBuilder.kt` |
| Writer source on ties | No-winner fallback prefers the group with the most substantial canonical document; winner authority is unchanged. Required a behaviour-preserving extraction from `runLoop`, which sat at 64,522 of 65,535 bytecode bytes (now 63,697). | `EvoMoliorRunService.kt` |
| KB reading | `read_kb` pages long bodies with `offset`, shows lengths in listings, and no longer fails a read when an entry lacks a lineage anchor (the use is just not recorded). Writer prompt allows paging the primary document and treats a substantial existing report as the baseline. | `ReadKBTool.kt`, `KnowledgeBaseContextFormatter.kt`, `RolePromptBuilder.kt` |
| Worker lifetime | Default ordinary-agent TTL is a 600 s constant; the old effort-units × 60 s formula is removed. New `ordinaryAgentTtlSeconds` (60–3600) in `RunConfig`/pipeline config reaches the previously unreachable envelope override. | `EvoMoliorRunService.kt`, `RunConfig.kt`, `CouncilExecutionEnvelopeFactory.kt`, `CliPipeline.kt` |
| Budget-aware planning | `ExecutionEnvelope.creditAffordableWorkerSlots` mirrors the allocator; feasibility and the Council's planning context use it and report affordable vs nominal slots. | `ExecutionEnvelope.kt`, `CouncilFeasibilityPlanner.kt`, `GenesisCouncil.kt` |
| Critic cost | Source-fetch records are promoted and contract/no-action fallbacks rejected deterministically, without model calls; grader methodology drafts are reviewed first; a pending (unreviewed) draft gets a review-only pass before re-authoring. | `CriticPipeline.kt`, `EvoMoliorRunService.kt` |
| Infrastructure faults | Storage exhaustion anywhere in a cause chain stops a Critic pass and structured-methodology retries. The CLI rejects runs below 2 GiB free on the output, JVM-temp, or Codex-temp volume (`EVOMOLIOR_MIN_FREE_DISK_MB`) and warns at each round start. | `StorageExhaustion.kt`, `CriticPipeline.kt`, `GraderRunner.kt`, `RunStoragePreflight.kt`, `EvoMoliorCliMain.kt` |
| URL hygiene | Shared detector for invisible/format characters and invalid hosts; such URLs never satisfy `minimumPublicUrlCount` or CSV evidence columns; source checks emit a line-level, correctable `MALFORMED_URL`. | `PublicUrlHygiene.kt`, `FinalOutputContractValidator.kt`, `SourceReportChecker.kt`, `ReportSourceAdvisory.kt` |
| Source acquisition | Redirects are followed only after each hop passes the same source/role policy, audit, and constitutional interception (≤5 hops, loop detection); blocked hops name their destination. Response bodies are bounded at 4 MiB and decoded with the declared charset; cancellation is no longer swallowed. | `FetchSourceTool.kt` |
| Stage reuse | Completed stages carry a SHA-256 request fingerprint (prompt, input bytes, output contract, run/model configuration); a mismatch is refused. Legacy summaries fall back to a prompt comparison. | `CliStageRequestFingerprint.kt`, `CliPipeline.kt` |
| Telemetry | MetaJudge compares and merges per-sheet normalised weights; provider summaries label `inferred:<Role>` from deterministic agent-ID prefixes; Codex failure messages keep a 4k-char tail; the expected budget message is INFO; a writer that exits without output is distinguished from a timeout. | `MetaJudge.kt`, `ProviderArchiveSummary.kt`, `CodexChatGptLLMClient.kt`, `EvoMoliorRunService.kt` |
| CI | New `engine` job runs all nine provider-free engine suites, assembles the CLI distribution, and smoke-tests the launcher. Not yet exercised on GitHub runners. | `.github/workflows/build.yml` |

Every change has focused regression tests; see the roadmap entry for the verification run.

## Not changed — decisions or larger work

1. **Per-agent credit grants.** Each Builder received 37.5 credits and spent ~2.1. Sizing grants
   from observed spend would fund more workers inside the same ceiling, but increases provider
   calls; this is a product/budget decision.
2. **Mandatory-node replication.** `WorkerViewCompiler.replicationSlotAvailable` counts across
   groups, so a `NONE` replication mandatory node was offered to one group only. Whether
   competing groups must each produce mandatory nodes needs a semantic decision.
3. **Node portfolios reselect completed nodes** while one mandatory node stayed `NO_EVIDENCE` in
   every round.
4. **Further `EvoMoliorRunService` decomposition.** `runLoop` itself is now resolved (see the
   follow-up below); the 17,000-line service still mixes startup, rounds, and publication.
5. **Test temp leakage.** `.test-tmp` holds ~5,000 directories (16 GB); 1,027 `createTempDirectory`
   calls lack lifecycle cleanup. Nothing was deleted in this review.
6. **Council JSON-in-string contracts** (`claimLedgerSchemaJson`) still need an LLM repair when the
   escaped schema is malformed; a deterministic unescape would save two Council calls.
7. **Substrate fallback scores** recognise only legacy dimension IDs and score 0.0 otherwise.

## Follow-up — graders, run loop, and applications

**Graders start from the previous round.** `PriorRoundCriteriaBaseline` loads the newest
Critic-promoted typed methodology and criteria documents. Criteria agents receive it, bound to
the current handle matrix when the leased frontier is unchanged. In that mode they stage one
consolidated revision (kept/changed/new/retired) instead of a document per dimension. The
structured author edits the baseline methodology instead of authoring a new one. Stable rounds
(no rejection or failure pressure, frontier unchanged) run only a GraderBuilder. Round 0 and moved
frontiers still research from scratch. In the October 1 run shape this removes the ~7.5-minute
Researcher pass from stable rounds and cuts criteria-Critic reviews from ~20 per grader-round
toward one per role.

**Run loop decomposition.** `runLoop` now delegates to `executeRound`, `restoreArchivedRound`,
`awaitRoundWorkers`, `launchGraderCriteriaJob`, and `completeRunAfterRounds`. They share a
`RunLoopState` through delegated locals (`var x by state::x`), so the moved code is textually
unchanged and recovery always sees the latest publication context. Bytecode: `runLoop`
63,697 → 2,820 bytes; largest phase `executeRound` 27,223 bytes (42% of the JVM limit).

**Application improvements.**
- `evomolior template list|show|init` ships five best-fit application configs with deterministic
  output contracts.
- `minimumWordCount` stops silently condensed reports.
- RoundJudge prompts keep 8,000 criteria characters and always retain the authored test results
  (they were previously cut after 2,500 characters).
- Criteria digest entries hold 2,000 characters.
- `-grader-groups 1|2|3` (default 3) lets normal runs trade evaluation independence for grading
  cost. The single- and two-grader production paths have unit and envelope coverage but no live
  or end-to-end run yet.

## Best applications

EvoMolior's differentiator is not raw answer quality — matched benchmarks are ties or small
wins at 10–20× latency — but **auditable, evaluated work**: sealed tests, blind multi-grader
scoring, provenance-bound archives, honest accepted/provisional separation, and a substrate that
persists reviewed knowledge across rounds and runs. It fits work where a defensible process
matters more than speed or cost:

1. **Evidence-graded source and vendor landscapes / procurement pre-screens** (the geo-weather
   task): inventories with explicit verified/discovery/gap status per entry, URL hygiene, and a
   documented "what we could not verify" list. Needs reliable source acquisition and the writer
   fixes above.
2. **Systematic-review style screening** (MetaSyn tasks): criteria-driven inclusion/exclusion with
   a per-decision audit trail, where traceability is a compliance requirement.
3. **Evaluation and rubric engineering**: the grader civilization (authored tests, blind
   scoring, MetaJudge consensus) is the most exercised subsystem and can stand alone as an
   LLM-output adjudication or red-team grading service.
4. **Code and architecture audits with executable checks**: sandboxed tools, sealed tests, and
   review lineage suit change-risk reviews of real repositories (the Luna snapshot review).
5. **Standing intelligence watches**: explicit continuation lets a substrate accumulate reviewed
   knowledge across periodic runs (regulatory, competitor, or data-source monitoring).

Each of these now has a matching `evomolior template`. Poor fits: single-answer questions,
latency-sensitive interactive use, cheap high-volume generation, and small deterministic coding
tasks. Baseline-refining graders plus `-grader-groups 1` give a cheaper profile for routine work.
Reserve the default three-grader society for work whose acceptance must be defended.
