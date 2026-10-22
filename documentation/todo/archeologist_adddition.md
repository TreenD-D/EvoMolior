| Decision                                                                    | Recommendation                                                                                                                                                  |
| --------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Is “archive archaeology” useful?                                            | **Yes—potentially one of EvoMolior’s strongest compounding mechanisms.**                                                                                        |
| Should old artifacts be inserted directly into the new KB/KG/tool registry? | **No.** Import them as untrusted, provenance-bound heritage candidates.                                                                                         |
| Best position in the run                                                    | After the Council has produced a task/receiver model and preliminary Node Atlas, but before the final StartingField is sealed and worker substrates are seeded. |
| Agent placement                                                             | A separate **Archive Archaeologist** on a dedicated pre-run heritage plane—not a worker, grader, Outlier, or ordinary Council member.                           |
| Default authority                                                           | Read-only archive access; output is only a typed candidate manifest.                                                                                            |
| Promotion rule                                                              | Current-run agents must revalidate, reproduce, adapt, or independently use an imported candidate before it gains current-run authority.                         |
| Main danger                                                                 | Anchoring, stale facts, inherited bugs, hidden-test leakage, and self-reinforcing errors across runs.                                                           |
| Immediate implementation timing                                             | Design and index now; enable candidate import only after the current lifecycle/tool-repair blockers are stable.                                                 |

# Verdict

This is an excellent fit for EvoMolior.

The architecture already treats the substrate—not an individual agent—as the persistent civilization. It stores canonical documents, typed facts, tools, dead ends, Legacy protocols, event history, and run archives. Starting fields already contain primordial KB/KG/tools, continuation metadata, routed context, and feed-forward/run-graph metadata. 

The canonical roadmap also identifies exactly this missing boundary:

> explicit continuation from selected prior runs works, but new Genesis runs do not automatically learn from arbitrary prior tasks. 

So archive archaeology is not a foreign extension. It is the natural implementation of EvoMolior’s currently partial **cross-task learning** capability.

The important qualification is:

> **An old artifact should survive as a candidate for renewed use—not as automatically trusted knowledge.**

Otherwise the project will compound errors as efficiently as it compounds knowledge.

---

# 1. The right conceptual model: a museum, not inherited canon

A previous run may contain:

* a generally useful tool;
* a correct fact whose original problem was unrelated;
* a reusable research method;
* a useful grader requirement pattern;
* a known schema or unit-conversion failure;
* a dead end that prevents repeated wasted work;
* an Outlier proposal that was never tested;
* a good decision-table format;
* a successful role strategy;
* an incomplete but promising model or simulation;
* a receiver-facing artifact whose structure is reusable even if its content is not.

But its status in the old run does not automatically transfer.

An artifact accepted in run A was accepted under:

* run A’s objective;
* its sources and source dates;
* its Node Atlas;
* its tool schemas;
* its model versions;
* its grader criteria;
* its hidden tests;
* its environmental assumptions;
* its receiver requirements.

Run B has different authority.

Therefore the import model should be:

```text
Old canonical artifact
        ↓
Archived historical evidence
        ↓
Archaeology candidate
        ↓
Current-task applicability assessment
        ↓
Current-run revalidation / adaptation / reproduction
        ↓
Current-run staging and Critic authority
        ↓
Current-run evidence and grading
        ↓
Current-run canon or active tool
```

Not:

```text
Old canonical artifact
        ↓
New canonical artifact
```

A suitable metaphor is:

```text
Run-local canon = living law

Cross-run archive = museum collection

Archaeology output = curated exhibition proposal

Current-run validation = re-entry examination
```

---

# 2. Where it fits into the existing architecture

The best insertion point is **inside Genesis, but outside the normal Council society**.

## Recommended sequence

```text
Operator input and artifacts
        ↓
Council Task Anatomist
        ↓
ReceiverContractDraft
Preliminary task fingerprint
Preliminary limitation/node map
        ↓
Isolated Archive Archaeologist
        ↓
ArchaeologyCandidateManifest
        ↓
Remaining Council specialists
        ↓
Council Seed Curator reviews candidate manifest
        ↓
Council Synthesis compiles:
  - final Node Atlas
  - worker/grader fields
  - heritage candidate mappings
  - reveal stages
  - import policies
        ↓
HeritageImportCompiler
        ↓
sealed RunHeritageManifest
        ↓
StartingField final seal
        ↓
quarantined candidates seeded
        ↓
competitive rounds begin
```

## Why not run archaeology before the Council?

With only the raw prompt, retrieval will be too shallow. It may find lexical similarities without understanding:

* who the receiver is;
* which decision is being supported;
* what limitations must be overcome;
* which node kinds are expected;
* what evidence profile is required;
* whether tools or simulations are relevant.

The Task Anatomist can provide a much richer retrieval profile.

## Why not run archaeology after the run has started?

That would create several complications:

* candidate availability changes during competition;
* groups may receive historical advantages at different times;
* hidden-stage material might leak early;
* the StartingField and manifest would not attest the initial heritage state;
* benchmark reproducibility would weaken.

The archaeological selection should be frozen before round 0.

## Why not make it a normal Council role?

Because historical artifacts can anchor the Council.

The Council should first generate the task’s receiver model and limitation structure from the current problem. The archaeologist should not persuade the Task Anatomist that an old solution is the shape of the new problem.

It should be a separate auxiliary plane:

```text
PLANE: HERITAGE
ROLE: ArchiveArchaeologist
```

It should not participate in:

* worker fitness;
* grader fitness;
* capture;
* resource ownership;
* ordinary Legacy;
* Outlier novelty;
* group competition.

---

# 3. Add a task fingerprint designed for cross-run retrieval

Raw semantic similarity is insufficient. Two artifacts can be useful despite unrelated topics because they share a structural problem.

For example:

* a unit-conversion dead end from a cooling simulator may help a logistics energy calculator;
* a grader-cardinality repair from a policy task may help a software architecture evaluation;
* a source-provenance method from medical research may help legal or OSINT synthesis;
* a decision-matrix template from emergency planning may help product selection;
* a schema-normalization tool can transfer across almost any domain.

Use several retrieval dimensions.

```kotlin
@Serializable
data class TaskFingerprint(
    val rawObjectiveHash: String,

    val receiverClass: ReceiverClass,
    val decisionClass: DecisionClass,
    val domainTags: Set<String>,

    val expectedNodeKinds: Set<NodeKind>,
    val requiredCapabilities: Set<CapabilityId>,
    val evidenceProfile: EvidenceProfile,
    val toolOpportunityClasses: Set<ToolOpportunityClass>,

    val riskClasses: Set<RiskClass>,
    val temporalSensitivity: TemporalSensitivity,
    val geographicSensitivity: GeographicSensitivity,
    val confidentialityClass: ConfidentialityClass,

    val semanticEmbeddingRef: String?,
    val structuralFingerprintHash: String,
)
```

## Retrieval channels

The archaeologist should search through at least four channels independently:

| Channel                          | Finds                                                  |
| -------------------------------- | ------------------------------------------------------ |
| **Domain similarity**            | Facts, sources, prior research, domain tools           |
| **Receiver/decision similarity** | Decision-package structures and implementation plans   |
| **Structural similarity**        | Similar node types, proof contracts, workflows, tests  |
| **Failure/method similarity**    | Dead ends, repair strategies, tool and grader failures |

A fifth useful channel is **capability similarity**:

```text
extract structured claims
normalize units
validate schemas
compare alternatives
simulate scenarios
test sensitivity
resolve contradictions
generate implementation checklists
```

This prevents archaeology from becoming merely vector search over old prose.

---

# 4. Build a global archive catalog; do not query raw run databases live

The archaeologist should not wander through arbitrary SQLite files and ZIPs during a production run.

Instead, completed and rejected runs should be ingested into a global, read-only **Civilization Archive Catalog**.

The current architecture already produces run archives, manifests, databases, run-graph messages, and meta-archive records that can feed future runs.  The new service should extract reusable descriptors from those artifacts after a run terminates.

## Core descriptor

```kotlin
@Serializable
data class ArchivedArtifactDescriptor(
    val globalArtifactId: String,
    val sourceRunId: String,
    val sourceRunStatus: HistoricalRunStatus,

    val plane: ArtifactPlane,
    val artifactType: HistoricalArtifactType,
    val originalArtifactId: String,
    val contentHash: String,
    val schemaVersion: String,

    val domainTags: Set<String>,
    val receiverClasses: Set<ReceiverClass>,
    val nodeKinds: Set<NodeKind>,
    val capabilityTags: Set<CapabilityId>,
    val methodTags: Set<String>,
    val failureTags: Set<String>,

    val originalAuthority: HistoricalAuthority,
    val originalGrade: HistoricalGradeSummary?,
    val originalEvidenceRefs: List<HistoricalEvidenceRef>,
    val downstreamReuseCount: Int,
    val finalDeliverableUseCount: Int,

    val createdAt: Instant,
    val sourceFreshnessClass: FreshnessClass,
    val runtimeDependencies: List<DependencyDescriptor>,

    val visibility: ArchiveVisibility,
    val reusePolicy: ArchiveReusePolicy,
    val heritageDepth: Int,
    val originRunIds: Set<String>,

    val descriptorHash: String,
)
```

## Artifact types

```kotlin
enum class HistoricalArtifactType {
    CLAIM,
    KG_FACT,
    DOCUMENT_FRAGMENT,
    FULL_DELIVERABLE,
    TOOL,
    TOOL_TEST,
    SIMULATION_MODEL,
    SIMULATION_RESULT,
    DEAD_END,
    ASSUMPTION,
    CONTRADICTION,
    LEGACY_PROTOCOL,
    STRATEGY_CARD,
    GRADER_METHODOLOGY,
    PUBLIC_TEST_PATTERN,
    OUTLIER_PROPOSAL,
    DECISION_TEMPLATE,
}
```

## Archive visibility

```kotlin
enum class ArchiveVisibility {
    PUBLIC_REUSABLE,
    INTERNAL_REUSABLE,
    WORKER_ONLY_REUSABLE,
    GRADER_ONLY_REUSABLE,
    RUN_SECRET,
    NON_REUSABLE,
}
```

The indexer must prevent:

* hidden tests entering worker heritage;
* private grader rationale entering worker context;
* raw personal or confidential source data being reused;
* API keys, paths, environment data, or private URLs entering descriptors;
* rejected raw model output being treated as validated knowledge.

---

# 5. The Archive Archaeologist contract

The archaeologist should receive a strict read-only view.

```kotlin
@Serializable
data class ArchaeologistView(
    val runId: String,
    val objectiveHash: String,
    val receiverDraft: RedactedReceiverContract,
    val taskFingerprint: TaskFingerprint,
    val preliminaryNodeCards: List<ArchaeologyNodeCard>,

    val allowedArtifactTypes: Set<HistoricalArtifactType>,
    val maximumSourceRuns: Int,
    val maximumCandidates: Int,
    val maximumContentBytes: Long,

    val archiveSnapshotHash: String,
)
```

It must not receive:

* current grader hidden tests;
* future-stage hidden payloads beyond the allowed Council view;
* worker group identities;
* model-generated worker outputs;
* resource ownership;
* current group economics;
* ordinary WIP tasks.

## Output

```kotlin
@Serializable
data class ArchaeologyCandidateManifestDraft(
    val archiveSnapshotHash: String,
    val taskFingerprintHash: String,

    val selectedCandidates: List<ArchaeologyCandidateSelection>,
    val rejectedCandidateRefs: List<RejectedCandidateRef>,
    val coverageGaps: List<ArchaeologyCoverageGap>,

    val noUsefulArtifactFound: Boolean,
    val reportHash: String,
)

@Serializable
data class ArchaeologyCandidateSelection(
    val globalArtifactId: String,
    val sourceRunId: String,
    val sourceArtifactHash: String,

    val candidateType: HistoricalArtifactType,
    val mappedPreliminaryNodeKeys: Set<String>,
    val capabilityMatch: Set<CapabilityId>,

    val relevanceRationale: String,
    val expectedUse: ExpectedHeritageUse,

    val knownRisks: List<HeritageRisk>,
    val requiredRevalidation: List<RevalidationRequirement>,
    val suggestedRevealStage: Int,

    val selectionConfidence: Double,
)
```

The archaeologist should be allowed to return:

```text
no useful candidate found
```

That should count as a valid, possibly optimal outcome.

---

# 6. Add a sealed Run Heritage Manifest

The Council Synthesis phase maps preliminary selections to exact final Node Atlas IDs.

```kotlin
@Serializable
data class RunHeritageManifest(
    val runId: String,
    val archiveSnapshotHash: String,
    val taskFingerprintHash: String,
    val atlasHash: String,

    val candidates: List<HeritageCandidateCommitment>,
    val distributionPolicy: HeritageDistributionPolicy,

    val createdByAgentId: String,
    val archaeologyReportHash: String,
    val compilerReportHash: String,

    val manifestHash: String,
)
```

## Candidate commitment

```kotlin
@Serializable
data class HeritageCandidateCommitment(
    val heritageCandidateId: String,
    val globalArtifactId: String,
    val sourceRunId: String,
    val sourceArtifactHash: String,

    val candidateType: HistoricalArtifactType,
    val mappedNodeIds: Set<NodeId>,
    val revealStageId: String,

    val importState: HeritageImportState,
    val trustCeiling: Double,
    val requiredValidationKind: ValidationKind,

    val workerVisibility: WorkerHeritageVisibility,
    val graderVisibility: GraderHeritageVisibility,

    val commitmentHash: String,
)
```

## Lifecycle

```text
DISCOVERED_IN_ARCHIVE
        ↓
SELECTED_BY_ARCHAEOLOGIST
        ↓
COMPILED_INTO_HERITAGE_MANIFEST
        ↓
IMPORTED_TO_QUARANTINE
        ↓
REVEALED_FOR_CURRENT_NODE
        ↓
CLAIMED_FOR_REVALIDATION
        ↓
CURRENT_RUN_REVALIDATED
        ↓
PROMOTED_CURRENT_RUN
```

Alternative terminal states:

```text
NOT_APPLICABLE
STALE
CONTRADICTED
SCHEMA_INCOMPATIBLE
RUNTIME_INCOMPATIBLE
REJECTED_CURRENT_RUN
SUPERSEDED
```

---

# 7. Do not “pre-seed canon”; pre-seed candidate cards

The current StartingField already has primordial KB, KG, and tools.  It would be tempting to insert archaeological artifacts there.

That would be dangerous.

Use separate candidate collections:

```text
primordial KB      = current-run Council-authorized seed material
heritage candidates = archived material awaiting current-run validation
```

Suggested stores:

```text
HeritageCandidateEntity
HeritageContentBlobEntity
HeritageNodeMappingEntity
HeritageValidationLeaseEntity
HeritageValidationResultEntity
CrossRunLineageEntity
```

## Worker view

A worker should receive a compact card only when:

* the mapped node is revealed;
* the candidate’s reveal stage has arrived;
* the artifact’s visibility permits it;
* the group has a valid validation or reuse lease.

```kotlin
@Serializable
data class HeritageCandidateCard(
    val heritageCandidateId: String,
    val candidateType: HistoricalArtifactType,

    val historicalPurpose: String,
    val potentiallyReusableCapability: String,
    val knownFailureOrLimitation: String?,
    val sourceRunStatus: HistoricalRunStatus,

    val currentNodeId: NodeId,
    val requiredCurrentValidation: ValidationKind,

    val contentRef: String?,
    val contentHash: String,
    val cardHash: String,
)
```

The card must say explicitly:

```text
This is historical material, not current-run canon.
Its old acceptance or rejection status does not transfer.
```

---

# 8. Different artifact types require different re-entry rules

## 8.1 Historical tools

Tools are among the most valuable cross-run artifacts, but also among the riskiest.

An old tool must not become active merely because it passed in its original run.

Required pipeline:

```text
Archived tool
  → schema compatibility check
  → dependency and runtime compatibility check
  → source/static validation
  → sandbox replay of historical public tests
  → current-opportunity schema adaptation if necessary
  → current-run promotion tests
  → independent current-run execution
  → active current-run version
```

Use a new lifecycle source:

```kotlin
enum class ToolOrigin {
    CURRENT_RUN_AUTHORED,
    ARCHIVE_IMPORTED_UNCHANGED,
    ARCHIVE_ADAPTED,
    ARCHIVE_FORKED,
}
```

An imported tool should get a new current-run version ID, even when the bytes are unchanged.

```text
global archived identity: tool-global-hash
current run identity: run-b/tool-import-v1
```

Credit should be split:

* original authoring lineage;
* archaeologist selection value;
* current validator credit;
* current adapter/fork credit;
* later-use credit.

## 8.2 Facts and claims

Historical claims require:

* source availability;
* source hash or archived span;
* freshness assessment;
* current-context applicability;
* current KG schema mapping;
* contradiction search;
* current node relevance.

Possible statuses:

```text
HISTORICALLY_SUPPORTED
CURRENTLY_REVALIDATED
STALE_SOURCE
CURRENTLY_CONTRADICTED
CONTEXT_DEPENDENT
UNVERIFIABLE_ARCHIVE_ONLY
```

A historical source may still be useful even when the claim is stale—for example, as evidence of an earlier state.

## 8.3 Dead ends

Dead ends may create more value than successful artifacts because they prevent repeated expensive mistakes.

But they must include applicability conditions.

```kotlin
@Serializable
data class HistoricalDeadEndCard(
    val attemptedCapability: String,
    val method: String,
    val observedFailure: String,

    val failureClass: String,
    val applicabilityConditions: List<String>,
    val retryJustificationConditions: List<String>,

    val originalEvidenceRefs: List<String>,
    val confidence: Double,
)
```

A dead end is a warning, not a prohibition.

A new agent may override it by recording:

```text
why current conditions differ
what new evidence or tool exists
which assumption no longer holds
```

That avoids fossilizing historical mistakes.

## 8.4 Legacy protocols and strategy cards

These may be highly transferable, especially when they encode procedure rather than conclusions.

Examples:

* inspect schemas before coding;
* use dimensional analysis before formula implementation;
* resolve opaque IDs through handles instead of transcription;
* run a sensitivity test for every decision-relevant input;
* freeze grader criteria before evidence reveal;
* preserve best-so-far candidate before late repair.

Cross-run strategy eligibility should be conditioned on:

```text
role
node kind
task capability
tool set
model capability
prompt/schema version
environment
original measured uplift
```

The imported protocol starts as experimental.

```text
ARCHIVED_PROTOCOL
  → COMPATIBILITY_CHECKED
  → EXPERIMENTALLY_ASSIGNED
  → SUCCESSOR_UPLIFT_MEASURED
  → CURRENT_LEGACY_PROMOTED
```

## 8.5 Grader methodologies

Keep this on a separate grader-only archaeology path.

A worker archaeologist must never import:

* hidden tests;
* private expected values;
* grader-only requirement mappings;
* undisclosed failure triggers.

Reusable grader artifacts can include:

* public requirement templates;
* calibration procedures;
* safe regex strategies;
* generic failure taxonomies;
* test-design methods.

The exact hidden tests from an old run should remain historical private evidence.

## 8.6 Full prior deliverables

Full prior deliverables are powerful anchoring sources.

Default policy:

* do not reveal them in round 0;
* atomize them into claims, methods, assumptions, risks, and structure candidates;
* allow full-document access only to later Researcher/Connector/Integrator roles;
* label them as precedents, never as templates to copy;
* preserve original receiver and context.

---

# 9. Preserve progressive disclosure

The archaeologist is privileged like the Council, but workers are not.

If an archived artifact matches a stage-3 delivery node, it must not be shown during stage 0.

The Heritage Manifest binds each candidate to:

* mapped node IDs;
* earliest reveal stage;
* visibility plane;
* content commitment.

```text
Stage 0 worker:
  sees historical dead end about a relevant foundational calculation

Stage 1 worker:
  may see an old tool interface

Stage 2 worker:
  may see prior trade-off assumptions

Stage 3 integrator:
  may inspect the complete historical receiver package
```

A historical artifact that independently reveals a hidden future limitation must be treated like an early Outlier anticipation:

* its commitment can exist;
* its content remains sealed;
* its existence should not reveal the future node’s meaning.

---

# 10. Preserve worker-group competition and diversity

Automatically copying every historical artifact into every group would produce convergence.

Use a sealed heritage pool plus explicit distribution policy.

```kotlin
enum class HeritageDistributionPolicy {
    COMMON_DESCRIPTOR_POOL,
    COMMON_FULL_POOL,
    PROFILE_MATCHED_DIVERSE,
    DISJOINT_EXPLORATION,
    CONTROLLED_ABLATION,
}
```

## Recommended default: `COMMON_DESCRIPTOR_POOL`

Every group sees the same small descriptors:

```text
A historical tool may help this node.
A dead end exists for a similar method.
A validated procedural strategy is available.
```

Groups choose whether to spend:

* retrieval budget;
* validation effort;
* a node lease;
* tool compute.

This keeps the starting opportunity fair while allowing different strategic responses.

## Normal diversity mode

`PROFILE_MATCHED_DIVERSE` can provide different candidates to different group profiles:

* Library receives claims, sources, and evidence methods;
* Factory receives tools and executable tests;
* Monastery receives deep dead ends and conceptual models;
* Commune receives integration and communication strategies.

This should be used only when the distribution policy is sealed and reported.

## Benchmark mode

Cross-run archaeology must be:

* disabled; or
* identically frozen across all experiment arms.

Otherwise the single-agent, one-group, and full-society comparison is not matched.

---

# 11. Add heritage-specific fitness without corrupting worker fitness

The archaeologist should not win worker Legacy or node capture.

Create a separate Heritage plane.

```kotlin
data class HeritageSelectionFitness(
    val archaeologistAgentId: String,
    val selectedCandidateCount: Int,
    val currentRunPromotions: Int,
    val successfulCurrentRunUses: Int,
    val avoidedRepeatedFailures: Int,
    val downstreamFinalReferences: Int,
    val staleOrMisleadingSelections: Int,
    val retrievalCost: CostSummary,
)
```

A good archaeologist is not one that imports many artifacts.

Its value is:

```text
heritageFitness =
    useful promoted candidates
  + avoided repeated work
  + successful tool reuse
  + useful dead-end warnings
  + final-deliverable lineage
  - irrelevant imports
  - anchoring damage
  - stale claims
  - validation cost
```

Worker credit should distinguish:

| Contribution              | Credit                               |
| ------------------------- | ------------------------------------ |
| Selecting the artifact    | Archaeologist plane                  |
| Revalidating it           | Current worker                       |
| Correctly adapting it     | Current Builder                      |
| Finding it stale or wrong | Current validator, as negative value |
| Using it downstream       | Current executor/integrator          |
| Original creation         | Preserved historical lineage         |

---

# 12. Prevent recursive contamination and archive echo chambers

This is the most subtle risk.

Imagine:

1. Run A generates claim X.
2. Run B imports X and cites A.
3. Run C imports B’s version.
4. Run D sees X in three archives and concludes it has three independent confirmations.

That is false.

Every artifact requires transitive origin lineage.

```kotlin
data class CrossRunLineage(
    val artifactId: String,
    val directSourceRunId: String,
    val rootOriginRunIds: Set<String>,
    val importedFromArtifactIds: Set<String>,
    val currentRunNewEvidenceRefs: Set<String>,
    val heritageDepth: Int,
    val independentEvidenceCount: Int,
)
```

Rules:

* imported copies do not increase independent evidence count;
* each import preserves root origins;
* repeated unvalidated imports cause trust decay;
* a candidate with excessive heritage depth requires fresh evidence;
* the same underlying source cannot be counted multiple times;
* grader and worker archive lineages remain separated;
* a grader method imported from a lineage that generated the worker artifact cannot be treated as independent calibration.

Suggested cap:

```text
heritageDepth > 2
AND no new independent evidence
→ descriptor only; no promotion eligibility
```

---

# 13. Implementation by module

## `core`

Add:

```text
TaskFingerprint
ArchivedArtifactDescriptor
ArchaeologistView
ArchaeologyCandidateManifestDraft
RunHeritageManifest
HeritageCandidateCommitment
HeritageCandidateCard
CrossRunLineage
HeritageImportState
HeritageDistributionPolicy
RevalidationRequirement
```

## `substrate`

Create a separate global archive database:

```text
~/.evomolior/archive/catalog.db
```

Tables:

```text
ArchivedRunDescriptorEntity
ArchivedArtifactDescriptorEntity
ArchivedArtifactOriginEntity
ArchivedCapabilityTagEntity
ArchivedFailureTagEntity
ArchivedArtifactEmbeddingEntity
RunHeritageManifestEntity
HeritageCandidateEntity
HeritageValidationEntity
CrossRunLineageEntity
```

Do not place the global catalog inside a worker-group DB.

## `api`

Add:

```kotlin
interface ArchiveCatalogService {
    suspend fun ingestCompletedRun(runId: String): ArchiveIngestionResult
    suspend fun search(query: ArchiveSearchQuery): List<ArchivedArtifactDescriptor>
    suspend fun fetchCandidateContent(
        artifactId: String,
        accessToken: ArchiveAccessToken,
    ): ArchivedArtifactContent
}
```

Add:

```text
ArchaeologyRunService
HeritageImportCompiler
HeritageManifestVerifier
```

## `agents`

Add:

```text
ArchiveArchaeologistRunner
ArchiveArchaeologistPrompt
ArchiveSearchTool
ReadArchivedArtifactTool
SubmitArchaeologyManifestTool
```

The tool registry must be read-only.

The archaeologist receives no:

* `WriteStagingTool`;
* `EmitFactTool`;
* `ExecuteToolTool`;
* `SendMessageTool`;
* `ClaimTaskTool`;
* group substrate access.

## `orchestration`

Integrate after preliminary Task Anatomy:

```text
TaskAnatomist completes
→ build TaskFingerprint
→ run Archaeologist
→ compile candidate report
→ Seed Curator consumes safe report
→ Synthesis maps candidates to final Atlas
→ seal Heritage Manifest
```

## `competition`

Add:

* heritage candidate bidding;
* group distribution policy;
* import validation leases;
* heritage-derived node evidence lineage;
* no direct capture from historical status.

## `sandbox`

Archived tools are never executed directly from the global catalog.

They must be:

1. copied into a current-run quarantine workspace;
2. hash-verified;
3. statically checked;
4. executed through `ToolSubSandbox`;
5. promoted as a current-run version.

## CLI

Suggested options:

```text
--archive-archaeology off|safe|extended
--archive-max-runs 50
--archive-max-candidates 12
--archive-max-content-mb 5
--archive-min-original-trust 0.5
--archive-as-of 2026-07-25
--heritage-distribution common-descriptors
```

Default initially:

```text
--archive-archaeology off
```

Then, after qualification:

```text
safe
```

---

# 14. Implementation phases

## Phase 0 — Archive ingestion only

Build the catalog and index past archives.

No live run consumes it.

Exit condition:

* accepted, rejected, recovered, and aborted runs ingest correctly;
* hidden/private artifacts remain excluded;
* origin lineage is preserved;
* duplicate underlying artifacts collapse by content/provenance hash.

## Phase 1 — Read-only archaeology reports

Run an archaeologist after Task Anatomy and emit a report for the operator.

Do not seed candidates into workers yet.

Measure:

* relevance;
* false positives;
* missing useful artifacts;
* cost;
* source-run diversity.

## Phase 2 — Sealed heritage manifest

Compile selected candidates into the StartingField seal but keep them unavailable to workers.

This proves:

* determinism;
* replay;
* tamper resistance;
* visibility classification;
* progressive-disclosure mapping.

## Phase 3 — Candidate cards and manual validation

Allow groups to retrieve descriptor cards and explicitly claim validation nodes.

Artifacts remain quarantined.

Start with:

* dead ends;
* procedural strategy cards;
* public tool interfaces.

Do not start with raw facts or full deliverables.

## Phase 4 — Tool and claim re-entry

Enable:

* archived tool revalidation;
* archived claim freshness checks;
* current KG mapping;
* current-run Critic promotion.

## Phase 5 — Automatic safe archaeology

Enable the archaeologist by default only after matched evidence shows uplift.

Use task classes where cross-run reuse should help:

* architecture audits;
* repeated research synthesis;
* tool-building tasks;
* recurring benchmark design;
* self-improvement of EvoMolior itself.

---

# 15. Acceptance tests

## Security and isolation

1. Archaeologist cannot read grader-private hidden tests.
2. Worker archaeology cannot retrieve grader-only artifacts.
3. Archived API keys, URLs, file paths, and private source payloads are never exposed.
4. Archaeologist has no write access to current group substrates.
5. Foreign run artifacts cannot bypass current-run staging or Critic authority.
6. Archive catalog tampering invalidates the Heritage Manifest.
7. A historical active tool cannot execute before current-run revalidation.

## Authority

1. Old canonical status never becomes current canonical status automatically.
2. Imported artifacts cannot close a node without a current-run contribution.
3. Imported tools cannot count as later-agent reuse until current-run independent execution.
4. Old grades cannot influence current capture.
5. Historical claims do not increase evidence count without distinct current evidence.
6. Dead ends can be overridden only with typed justification and new evidence.
7. Grader methodologies remain plane-isolated.

## Progressive disclosure

1. Stage-2 heritage content cannot appear in stage-0 WorkerViews.
2. Hidden-node relevance does not reveal the hidden node description.
3. Candidate card hashes bind to the Heritage Manifest.
4. Atlas amendments caused by archaeological material are versioned and effective only at a sealed boundary.

## Reproducibility

1. Same task fingerprint, archive snapshot, model, seed, and policy produce the same candidate descriptor set or retain explicit model variance.
2. Resume reloads the exact same Heritage Manifest.
3. Imported content hashes match the source archive.
4. Cross-run lineage retains the original root sources.

---

# 16. How to prove it is useful

Run matched experiments:

```text
A: no archaeology
B: archaeology descriptors only
C: archaeology plus candidate revalidation
```

Freeze:

* current input;
* Council model;
* worker/grader models;
* execution envelope;
* Atlas;
* round horizon;
* output contract;
* archive snapshot;
* seeds.

Measure:

| Metric                            | Purpose                                              |
| --------------------------------- | ---------------------------------------------------- |
| `archaeology_selection_precision` | Selected candidates later judged relevant            |
| `heritage_promotion_rate`         | Candidates reaching current-run promotion            |
| `heritage_usage_rate`             | Promoted candidates actually used                    |
| `avoided_dead_end_rate`           | Repeated known failures prevented                    |
| `cross_run_tool_reuse`            | Imported tools successfully revalidated and executed |
| `time_to_first_valid_tool`        | Whether old tools accelerate infrastructure          |
| `accepted_nodes_per_cost`         | Overall efficiency                                   |
| `rounds_to_node_pass`             | Whether heritage accelerates progress                |
| `heritage_to_final_lineage`       | Candidates reaching receiver output                  |
| `stale_candidate_rate`            | Historical material invalid in current context       |
| `anchoring_penalty`               | Diversity reduction or missed alternatives           |
| `semantic_overlap_delta`          | Whether groups become too similar                    |
| `novelty_suppression_rate`        | Whether archaeology crowds out Outliers              |
| `independent_evidence_delta`      | Whether evidence truly increases                     |

A useful archaeology system should not merely increase reuse.

It should improve:

```text
accepted value
per unit of retrieval + revalidation cost
```

while not causing a material collapse in exploration diversity.

---

# 17. Main risks and controls

| Risk                                              | Control                                                                                                  |
| ------------------------------------------------- | -------------------------------------------------------------------------------------------------------- |
| **Old facts are stale**                           | Freshness classes, current source verification, no automatic promotion                                   |
| **Old tools contain bugs**                        | Quarantine, schema checks, sandbox replay, current hidden tests                                          |
| **The system anchors on old solutions**           | Council Task Anatomy precedes archaeology; full artifacts delayed; Outliers see archive only selectively |
| **Groups become identical**                       | Common descriptors rather than common full content; profile-diverse distribution                         |
| **Hidden tests leak**                             | Strict plane visibility and separate worker/grader catalogs                                              |
| **Rejected work becomes misleading precedent**    | Preserve original run status and exact rejection reason                                                  |
| **Repeated imports create fake corroboration**    | Root-origin lineage and independent-evidence counting                                                    |
| **Archive grows without bound**                   | Deduplication, TTL, trust decay, usefulness-based retention                                              |
| **Archaeologist imports too much**                | Hard candidate cap and downstream usefulness fitness                                                     |
| **Old Legacy protocols fossilize behavior**       | Compatibility checks and controlled successor A/B validation                                             |
| **Cross-run learning masks current runtime bugs** | Keep disabled during Appendix B closure and matched infrastructure tests                                 |

---

# 18. How this relates to Outliers

Archaeology and Outliers are complementary, but can conflict.

```text
Archaeologist:
  What did past civilizations already discover?

Outlier:
  What are current and past civilizations still missing?
```

The Outlier should not normally receive the full archaeology report. It may receive:

* known archived assumptions;
* recurring dead-end classes;
* areas with high historical consensus;
* prohibited direct duplication.

This gives it something useful to challenge without giving it a specific answer.

An interesting Outlier reward is:

```text
historical-consensus break:
  proposal contradicts a frequently imported historical assumption
  and is later validated by current-run evidence
```

That prevents archaeology from becoming institutional dogma.

---

# 19. Recommended final architecture

```text
Operator objective and artifacts
        ↓
Genesis Task Anatomy
        ↓
Receiver draft + task fingerprint + preliminary limitation map
        ↓
┌─────────────────────────────────────────────┐
│ Isolated Heritage Plane                     │
│                                             │
│ Archive catalog → Archaeologist → candidates│
│ read-only, no groups, no graders, no canon  │
└─────────────────────────────────────────────┘
        ↓
Remaining Genesis Council
        ↓
Seed Curator + Synthesis
        ↓
Final Node Atlas
Worker field
Grader field
Run Heritage Manifest
        ↓
sealed activation
        ↓
Heritage candidates enter quarantine
        ↓
competitive worker groups
  - inspect candidate cards
  - revalidate or ignore
  - adapt or falsify
        ║
competitive grader groups
  - evaluate current-run evidence only
        ↓
round check-in
        ↓
current-run promotion, capture, disclosure, fitness
        ↓
cross-run lineage preserved in final manifest
        ↓
new run archived and re-indexed
```

This creates a genuine civilization-scale cycle:

```text
run produces knowledge
        ↓
archive preserves it
        ↓
archaeology reinterprets it
        ↓
new run tests it under new conditions
        ↓
validated inheritance survives
        ↓
invalid inheritance becomes a better dead end
```

# Final recommendation

Implement archive archaeology, but define its core invariant as:

> **The archaeologist may nominate history; only the current civilization may grant it authority.**

Start with the safest and most transferable material:

1. structured dead ends;
2. validated strategy cards;
3. public tool interfaces and tests;
4. reusable decision-package structures;
5. then tools and claims;
6. full historical deliverables last.

This feature should become its own post-robustness increment—something like **Increment 11: Versioned Cross-Run Heritage and Archive Archaeology**—rather than being folded into the current lifecycle/tool acceptance repair.
