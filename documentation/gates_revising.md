| Question                                     | Assessment                                                                                                                                              |
| -------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Are there too many gates?                    | **Yes, in one important sense:** too many gates currently act as both authority blockers and value sinks.                                               |
| Are the strict gates themselves unnecessary? | Mostly **no**. Hash, identity, grader-cardinality, sandbox, hidden-test, lifecycle, and archive gates protect the causal integrity of the civilization. |
| What should change?                          | A failed gate should usually deny **authority**, not erase **knowledge** or prevent a caveated receiver deliverable.                                    |
| Biggest non-fine-tuning opportunity          | Improve memory states, exploration economics, group differentiation, targeted repair, best-so-far preservation, and final synthesis.                    |
| Core principle                               | **A gate should be a valve, not a shredder.**                                                                                                           |

# Yes—the architecture is in danger of over-gating

The current system has accumulated a large number of fail-closed boundaries because almost every historical failure demonstrated that an apparently harmless approximation could corrupt a later authority edge:

* malformed grader output could alter the winner;
* incomplete grader data could become synthetic zeroes;
* generic artifacts could falsely close tool nodes;
* promotion tests could be mistaken for independent tool reuse;
* stale Atlas versions could affect capture;
* invalid work could unlock disclosure;
* a live agent could outlast publication;
* a finished ZIP could attest a state different from the state that was actually run.

Those are real problems. The strict gates are not arbitrary bureaucracy.

But the current implementation often conflates four separate questions:

1. **May this artifact mutate canonical state?**
2. **Is any part of it worth preserving?**
3. **Can another agent repair or reuse it?**
4. **Can the receiver safely act on it?**

At the moment, a single “no” frequently causes the artifact to lose value in all four dimensions.

That is the mistake.

The v21 run is a strong example. The runtime completed six rounds, launched all 36 RoundJudges, retained all round summaries, and exercised the worker, grader, disclosure, novelty, fitness, and Legacy machinery. Yet both groups failed mandatory tests, no tool reached active promotion, no scoring candidate existed, and recovery produced only an administrative non-acceptance notice—not a receiver-facing provisional result. 

The system was correct not to invent:

* an accepted winner;
* successful tool reuse;
* validated simulation influence;
* a passing decision package.

It was **not** optimal to conclude that the receiver should receive essentially nothing useful.

## The right distinction

```text
Failing a gate should mean:

“this artifact cannot exercise this authority”

not automatically:

“this artifact has no durable value”
```

---

# What the gates are actually for

## 1. Authority-integrity gates

These protect the causal state machine. They should remain hard and fail-closed.

Examples:

* exact Atlas/version/hash binding;
* immutable lifecycle exit identity;
* exact grader bundle cardinality;
* criteria frozen before worker reveal;
* no missing scores converted into zeroes;
* exact tool version and execution identity;
* independent later-agent reuse;
* hidden fixture confidentiality;
* cost reservations settled before publication;
* quiescent worker/grader processes;
* archive and manifest integrity.

The architecture explicitly depends on exact worker evidence packets, exact grader bundles, node-grade-based capture, and a quiescent publication barrier. Weakening these would make reported compounding impossible to trust. 

These gates answer:

> **May this event alter ownership, disclosure, fitness, canon, winner selection, or accepted publication?**

They should remain strict.

## 2. Structural interface gates

These protect typed protocols:

* schema validity;
* canonical number representation;
* valid relation IDs;
* valid tool inputs and outputs;
* executable test definitions;
* allowed file paths;
* bounded output;
* DSL type and dimensional validity.

These should be hard at the mutation boundary but strongly repair-oriented.

The current v21 tool loop shows the problem: many candidate tools failed schemas, canonical numbers, or hidden execution, while a useful Critic diagnosis about watts-to-kWh conversion was lost because the feedback system recognized only one hardcoded semantic category. 

A structural failure should normally become:

```text
REJECTED_FOR_PROMOTION
+ PRESERVED_FOR_REPAIR
+ TYPED_DIAGNOSTIC
+ RESPAWN_OR_SUCCESSOR_ELIGIBLE
```

not merely `REJECTED`.

## 3. Epistemic and quality gates

These ask whether the result is:

* sufficiently supported;
* reproducible;
* robust;
* relevant;
* complete;
* better than alternatives;
* safe for receiver use.

These should usually be **graded and multi-state**, not binary.

A weak hypothesis may still be useful as:

* a provisional branch;
* a counterexample;
* an uncertainty record;
* a future experiment;
* a warning;
* a structured dead end;
* a source of tests;
* a component of a better answer.

## 4. Product-acceptance gates

Appendix B is an intentionally conjunctive test of the entire architecture. Its definition of done requires tool-native activity, tool reuse, simulation influence, node-to-final lineage, exact metrics, publication quiescence, and archive integrity together. 

That is appropriate for **proving the implementation**.

It should not become the default definition of a useful ordinary run.

A research task that does not benefit from a simulator should not fail as a product because no simulator was created. A short strategy task should not require an Outlier amendment to be adopted. A document audit may not need a reusable executable tool.

The Council should declare which mechanisms are required for the current objective.

---

# Introduce a mechanism-activation contract

```kotlin
@Serializable
data class MechanismActivationContract(
    val toolAuthoring: MechanismRequirement,
    val independentToolReuse: MechanismRequirement,
    val simulation: MechanismRequirement,
    val outlierAmendment: MechanismRequirement,
    val crossGroupLeak: MechanismRequirement,
    val nestedGroup: MechanismRequirement,
    val claimLedger: MechanismRequirement,
    val externalGrounding: MechanismRequirement,
)

enum class MechanismRequirement {
    REQUIRED,
    OPTIONAL_VALUE_ADD,
    NOT_APPLICABLE,
}
```

### In strict Appendix B mode

```text
tool authoring       = REQUIRED
independent reuse    = REQUIRED
simulation influence = REQUIRED
node-to-final lineage= REQUIRED
```

### In an ordinary research-synthesis run

```text
claim ledger         = REQUIRED
external grounding   = REQUIRED
tool authoring       = OPTIONAL_VALUE_ADD
simulation           = NOT_APPLICABLE or OPTIONAL
outlier adoption     = OPTIONAL_VALUE_ADD
```

The mechanism must still be measured. But `NOT_APPLICABLE` must not become zero or failure.

This single change would reduce a large class of false product-level rejection without weakening any authority boundary.

---

# Replace boolean rejection with value-preserving gate outcomes

Every substantive gate should return more than pass/fail.

```kotlin
@Serializable
data class GateOutcomeV2(
    val gateId: String,
    val subjectRef: String,

    val authorityEffect: AuthorityEffect,
    val epistemicState: EpistemicState,
    val retentionDisposition: RetentionDisposition,
    val reuseScope: ReuseScope,

    val diagnostics: List<TypedDiagnostic>,
    val salvageUnits: List<SalvageUnit>,
    val recommendedNextAction: NextAction?,
)

enum class AuthorityEffect {
    NONE,
    PROVISIONAL_ONLY,
    NODE_EVIDENCE,
    CANONICAL,
    RECEIVER_ACCEPTED,
}

enum class EpistemicState {
    UNASSESSED,
    STRUCTURALLY_INVALID,
    REPAIRABLE,
    PROVISIONAL,
    CONTESTED,
    FALSIFIED,
    VALIDATED,
    SUPERSEDED,
}

enum class RetentionDisposition {
    PRESERVE_FULL,
    PRESERVE_ATOMIZED,
    PRESERVE_DIAGNOSTIC_ONLY,
    QUARANTINE,
    SECRET_DROP,
}

enum class NextAction {
    REPAIR_SAME_AGENT,
    SPAWN_SUCCESSOR,
    INDEPENDENT_VALIDATE,
    CONTRADICTION_TEST,
    ARCHIVE_DEAD_END,
    DEFER,
}
```

## Example: invalid tool submission

```text
authorityEffect       = NONE
epistemicState        = REPAIRABLE
retentionDisposition  = PRESERVE_ATOMIZED
salvageUnits           = [algorithm idea, assumptions, schemas, public tests]
nextAction             = SPAWN_SUCCESSOR
```

## Example: unsupported but plausible research claim

```text
authorityEffect       = PROVISIONAL_ONLY
epistemicState        = PROVISIONAL
retentionDisposition  = PRESERVE_FULL
nextAction             = INDEPENDENT_VALIDATE
```

## Example: hidden-test failure proving the approach is wrong

```text
authorityEffect       = NONE
epistemicState        = FALSIFIED
retentionDisposition  = PRESERVE_ATOMIZED
salvageUnits           = [failure case, counterexample, invalid domain]
nextAction             = ARCHIVE_DEAD_END
```

## Example: malformed grader bundle

```text
authorityEffect       = NONE
epistemicState        = STRUCTURALLY_INVALID
retentionDisposition  = PRESERVE_DIAGNOSTIC_ONLY
nextAction             = SPAWN_SUCCESSOR
```

The hard gate remains hard. The knowledge does not disappear.

---

# Add a multi-tier substrate instead of canonical-or-rejected

The architecture already has KB, KG, tools, staging, Death Notes, dead ends, Legacy protocols, and archives.  The missing capability is a coherent epistemic ladder across them.

```text
RAW CAPTURE
    ↓ parsing / atomization
REPAIRABLE WORK
    ↓
PROVISIONAL KNOWLEDGE
    ↓ evidence or independent reproduction
VALIDATED KNOWLEDGE
    ↓ Critic/canon authority
CANONICAL KNOWLEDGE
    ↓ receiver integration
DECISION-GRADE KNOWLEDGE
```

Side branches:

```text
FALSIFIED
CONTESTED
SUPERSEDED
QUARANTINED
DEAD END
```

## Proposed stores

### Canonical claim ledger

Accepted, source-bound claims.

### Hypothesis registry

Plausible but unresolved explanations and design directions.

### Contradiction ledger

Pairs or sets of claims that cannot currently coexist, including the evidence needed to resolve them.

### Assumption registry

Explicit assumptions, their domains, sensitivity, and which artifacts rely on them.

### Repair queue

Typed structural or semantic problems bound to exact node, opportunity, version, and prior attempt.

### Negative-result archive

Failed methods, counterexamples, invalid parameter domains, and reproduction conditions.

### Procedural memory

Validated methods for:

* finding sources;
* writing valid tools;
* repairing schemas;
* generating grader methodology;
* checking dimensional consistency;
* producing receiver packages.

This last category is especially important. It provides real behavioral compounding without changing model weights.

---

# Atomize submissions before rejecting them

An artifact should not be evaluated only as one monolith.

A rejected report can still contain:

* valid source references;
* correct claims;
* a useful taxonomy;
* a novel hypothesis;
* an invalid calculation;
* a strong test case;
* an actionable risk.

A rejected tool can still contain:

* a useful algorithm;
* valid input/output schemas;
* good tests;
* a semantic error;
* a reusable data transformation.

Introduce an atomization pass:

```kotlin
data class ArtifactAtoms(
    val claimAtoms: List<ClaimDraft>,
    val evidenceAtoms: List<EvidenceRef>,
    val hypothesisAtoms: List<HypothesisDraft>,
    val assumptionAtoms: List<AssumptionDraft>,
    val testAtoms: List<TestDraft>,
    val methodAtoms: List<MethodDraft>,
    val riskAtoms: List<RiskDraft>,
    val toolFragments: List<ToolFragment>,
)
```

Each atom receives its own status and lineage.

This is probably the highest-leverage compounding improvement after lifecycle robustness.

---

# Improve compounding without fine-tuning

## 1. Preserve a Pareto archive, not one winner per node

A single scalar score destroys useful differences.

For each node, retain a bounded Pareto frontier across:

* correctness;
* evidence quality;
* novelty;
* robustness;
* cost;
* simplicity;
* receiver relevance;
* integration utility.

Example:

```text
Candidate A: strongest evidence, expensive
Candidate B: cheaper, simpler, somewhat less complete
Candidate C: most novel, still unvalidated
Candidate D: strongest adversarial robustness
```

Do not collapse those into one winner immediately.

```kotlin
data class NodeParetoArchive(
    val nodeId: NodeId,
    val entries: List<ParetoEntry>,
    val maximumEntries: Int,
    val dominatedArchiveRefs: List<String>,
)
```

The final integrator may need different candidates depending on later constraints.

## 2. Persist best-so-far snapshots across rounds

Late rounds can regress. A final-round candidate should not overwrite an earlier superior one merely because it is newer.

Persist:

```text
best evidence candidate per node
best robust candidate per node
best low-cost candidate per node
best integrated group candidate
best receiver-facing candidate
```

Every new result competes against the archive.

The final publication stage should choose from all eligible historical snapshots, not only the last round.

## 3. Separate declarative, procedural, instrumental, and negative memory

```text
Declarative memory   = claims, facts, evidence
Procedural memory    = validated methods and repair playbooks
Instrumental memory  = tools, tests, simulations, transformations
Negative memory      = dead ends, failed assumptions, counterexamples
Episodic memory      = what occurred in a specific round/run
```

Successor-agent retrieval should combine these intentionally.

A Builder needs:

* tool contract;
* prior candidate failures;
* semantic repair codes;
* relevant procedural playbook;
* active test cases.

It does not need a generic round summary.

## 4. Make every failure produce an information-gain record

A negative result should state:

```text
what was attempted
which assumption it tested
what observation occurred
which branches are now less plausible
what remains possible
what the next discriminating test should be
```

Reward the **search space removed**, not just positive artifacts.

## 5. Convert repeated success into validated strategy cards

Without fine-tuning, EvoMolior can still create learned-like specialization through dynamically retrieved procedural cards.

```kotlin
data class ValidatedStrategyCard(
    val strategyId: String,
    val role: Role,
    val applicableNodeKinds: Set<NodeKind>,
    val preconditions: List<String>,
    val orderedProcedure: List<String>,
    val knownFailureModes: List<String>,
    val requiredTools: List<String>,
    val validationEvidenceRefs: List<String>,
    val measuredUplift: Double?,
)
```

Only cards associated with measurable improvement should enter Legacy.

This is stronger than copying an agent’s prose posture. It is explicit, testable procedural inheritance.

---

# Increase exploration diversity without different model families

The current action plan correctly notes that the original groups had 100% task-category overlap and that same-family prompts do not establish independent cognition. 

Model diversity is only one source of diversity. You can create substantial **search-process diversity**.

## 1. Assign generic exploration strategies, not task conclusions

Each group receives a sealed `ExplorationStrategyCard` selected from a generic catalog.

```kotlin
data class ExplorationStrategyCard(
    val evidenceMode: EvidenceMode,
    val representationMode: RepresentationMode,
    val searchMode: SearchMode,
    val riskPosture: RiskPosture,
    val abstractionLevel: AbstractionLevel,
    val timeHorizon: TimeHorizon,
    val toolBias: ToolBias,
    val sourceDiversityTarget: Double,
    val contradictionBudget: Double,
)
```

Possible axes:

| Axis                    | Variants                                                              |
| ----------------------- | --------------------------------------------------------------------- |
| Evidence mode           | empirical, mechanistic, source-synthesis, simulation-first            |
| Representation          | causal graph, quantitative model, scenario tree, requirements graph   |
| Search mode             | breadth-first, depth-first, falsification-first, simplification-first |
| Risk posture            | conservative, balanced, tail-risk-focused                             |
| Abstraction             | component, system, ecosystem                                          |
| Time horizon            | immediate, medium-term, long-term                                     |
| Tool bias               | tool-first, evidence-first, manual-first                              |
| Stakeholder orientation | operator, affected user, adversary, maintainer                        |

These are task-agnostic epistemic strategies. They do not reveal a desired solution.

## 2. Use constrained inference differentiation

Even with one model family:

* distinct random seeds;
* different temperatures and sampling profiles;
* separate retrieval queries;
* different source subsets;
* different tool availability;
* different context-ordering policies;
* different reasoning budgets;
* different decomposition depths;
* independent best-of-N only on selected high-value nodes;
* grammar-constrained versus open ideation passes.

Record each configuration as part of the agent phenotype even when no weights differ.

This is weaker than different base models, but materially stronger than changing only a role sentence.

## 3. Use behavioral niches and a MAP-Elites-style archive

Do not reward novelty as one scalar. Maintain useful solutions across behavioral niches.

Example descriptors:

```text
mechanistic ↔ empirical
incremental ↔ radical
simple ↔ comprehensive
low-cost ↔ high-capability
local ↔ systemic
constructive ↔ adversarial
quantitative ↔ qualitative
short-horizon ↔ long-horizon
```

Retain the best tested candidate in each occupied niche.

This prevents one dominant style from eliminating every alternative.

## 4. Make portfolio allocation optimize diversity explicitly

The current plan already proposes congestion penalties and frontier bonuses.  Strengthen the objective:

```text
portfolioUtility =
    expectedReceiverValue
  + expectedInformationGain
  + prerequisiteUnlockValue
  + uncertaintyReduction
  + underexploredNicheBonus
  + deliberateReplicationValue
  - semanticRedundancy
  - congestion
  - expectedCost
```

Do not allocate both groups to identical nodes unless the replication mode explicitly requests it.

## 5. Separate deliberate replication from accidental duplication

Useful patterns:

```text
PROPOSAL + ADVERSARIAL ATTACK
MODEL A + INDEPENDENT MODEL B
EMPIRICAL ESTIMATE + MECHANISTIC ESTIMATE
COMPLEX SOLUTION + MINIMAL SOLUTION
OPTIMISTIC SCENARIO + TAIL-RISK SCENARIO
```

The Connector then receives a contradiction-resolution node.

Replication becomes productive because the comparison itself is an artifact.

---

# Improve the Outlier system

The current Outlier and shadow-node architecture is conceptually strong: Outliers receive no ordinary WIP or Legacy and their proposals may become tested shadow nodes. 

But merely saying “be novel” creates randomness rather than useful novelty.

## Use generic novelty operators

An Outlier receives no task node, but may be assigned one randomly selected search operator:

* invert a dominant assumption;
* search for an extreme or boundary condition;
* transfer a mechanism from another domain;
* change the representation;
* remove one allegedly necessary component;
* construct a counterexample;
* introduce a missing stakeholder;
* change temporal or spatial scale;
* search for a hidden coupling;
* propose a measurement or tool not currently available;
* find a simpler path to the same receiver outcome.

This does not tell the Outlier what conclusion to produce. It changes the search direction.

## Score novelty only after downstream interaction

```text
novelty value =
    semantic distance
  × testability
  × receiver relevance
  × downstream adoption
  × measured improvement
```

Pure difference without usefulness receives little value.

## Maintain a novelty archive

Retain:

* accepted proposals;
* rejected proposals and reasons;
* proposals that anticipated hidden Atlas nodes;
* proposals adopted by another group;
* proposals that generated new tests;
* proposals that simplified or removed nodes.

This gives later Outliers a map of explored novelty space without giving them a prescribed answer.

---

# Improve cross-group information exchange

Too little exchange wastes discoveries. Too much exchange causes monoculture.

## Delay sharing early

Early rounds should preserve independence.

```text
round 0: almost no cross-group semantic sharing
round 1: share failures and unresolved contradictions
later rounds: share validated abstractions, tests, and tool interfaces
```

## Share information-gain packets, not raw documents

```kotlin
data class CrossGroupInsightPacket(
    val insightType: InsightType,
    val sourceNodeId: String,
    val claimOrFailureSummary: String,
    val evidenceCommitment: String,
    val confidence: Double,
    val contradictionRefs: List<String>,
    val recommendedIndependentCheck: String?,
)
```

Possible packets:

* validated finding;
* reproducible failure;
* contradiction;
* newly discovered test;
* tool interface;
* invalid assumption;
* uncertainty boundary.

Do not send the whole solution.

## Make leak cadence adaptive

Increase cross-pollination when:

* groups are far apart but both are stagnating;
* one group has a validated tool others could reuse;
* a contradiction requires independent resolution.

Reduce it when:

* semantic similarity is rising too quickly;
* both groups are converging before independent validation;
* one group is merely copying another.

---

# Improve role usefulness without adding more roles

The roles largely work. The next gain comes from better **handoffs**.

| Producer   | Consumer           | Required handoff                                                    |
| ---------- | ------------------ | ------------------------------------------------------------------- |
| Researcher | Builder            | Evidence parameters, uncertainty, invalid domains, required outputs |
| Builder    | Validator          | Versioned artifact, executable tests, assumptions, known weaknesses |
| Validator  | Builder successor  | Typed defect classes, not prose-only rejection                      |
| Critic     | Planner            | Node-specific repair priority and expected information gain         |
| Outlier    | Researcher/Builder | Falsifiable proposal and minimal test                               |
| Connector  | Final integrator   | Resolved contradictions and trade-off decision                      |
| Archivist  | all successors     | Retrieval index, not only narrative summary                         |
| Grader     | worker successor   | Redacted actionable requirement failures                            |

## Make Connector more important

A multi-agent system gains little from parallel outputs unless contradictions and complementary results are actively integrated.

Connector work should include:

* claim equivalence detection;
* contradiction mapping;
* assumption reconciliation;
* unit/ontology alignment;
* cross-node dependency discovery;
* comparison tables;
* synthesis of independent estimates;
* decision trade-offs.

It should receive explicit integration nodes after sufficient parallel evidence exists.

---

# Improve output quality

## 1. Use a structured receiver package as final semantic authority

Do not make the final writer reconstruct everything from round summaries.

```kotlin
data class ReceiverDecisionPackage(
    val executiveDecision: DecisionRecord?,
    val acceptedClaims: List<ClaimRef>,
    val provisionalClaims: List<ClaimRef>,
    val alternatives: List<AlternativeRecord>,
    val evidenceMatrix: List<EvidenceRow>,
    val assumptions: List<AssumptionRecord>,
    val contradictions: List<ContradictionRecord>,
    val risks: List<RiskRecord>,
    val uncertainties: List<UncertaintyRecord>,
    val toolAndSimulationResults: List<ExecutionResultRef>,
    val implementationPlan: List<ActionItem>,
    val missingEvidencePlan: List<NextEvidenceAction>,
    val provenance: PackageProvenance,
)
```

Markdown, HTML, JSON, and CSV are projections of this package.

## 2. Preserve accepted, provisional, and diagnostic deliverables

Every terminal run should produce one of:

```text
ACCEPTED_DECISION_PACKAGE
PROVISIONAL_DECISION_PACKAGE
DIAGNOSTIC_PACKAGE
```

The v21 report explicitly identifies the lack of a receiver-facing artifact when no scoring candidate exists as a product gap. 

A provisional package may say:

* no accepted winner;
* these conclusions are common across groups;
* these claims remain contested;
* these tests failed;
* these calculations are provisional;
* this evidence would resolve the uncertainty.

That is far more valuable than an administrative notice while remaining honest.

## 3. Allow civilization-level harvest after competition

A strong compromise:

* the winning group supplies the integration backbone;
* any globally captured or explicitly shareable node may be imported;
* private unpromoted group canon cannot be imported;
* attribution and fitness remain attached to the originating group and agents;
* contested alternatives remain visible rather than silently merged.

This preserves competition while producing the best receiver outcome from the civilization’s total validated work.

## 4. Add near-miss handling for non-safety quality gates

For hard contract or safety failures:

```text
exact threshold, fail closed
```

For epistemic quality close to threshold:

```text
NEAR_PASS / CONTESTED
→ focused repair or tie-break
→ provisional retention
```

Do not apply an epsilon to:

* hash checks;
* identity;
* schema validity;
* mandatory safety constraints;
* private test secrecy.

But a node graded `0.7975` against a quality threshold of `0.8`, with no hard failure, should generally trigger a targeted repair rather than erase its value.

---

# Recommended implementation order

## P0 — Stop losing value at gates

1. Introduce `GateOutcomeV2`.
2. Separate authority effect from retention disposition.
3. Add `REPAIRABLE`, `PROVISIONAL`, `CONTESTED`, `FALSIFIED`, and `SUPERSEDED` states.
4. Add typed salvage extraction for documents, claims, tests, and tools.
5. Add the mechanism-activation contract.
6. Add provisional and diagnostic receiver-package compilation.
7. Preserve best-so-far node and group candidates across rounds.

## P1 — Improve compounding

8. Add hypothesis, contradiction, assumption, repair, and procedural-memory stores.
9. Replace prose Legacy with validated strategy cards where possible.
10. Add node-level Pareto archives.
11. Add downstream-reuse credit for claims, tests, methods, tools, and negative results.
12. Add role-specific retrieval bundles instead of broad round summaries.
13. Make typed repair queues first-class and generation-visible.

## P1 — Improve diversity

14. Add generic `ExplorationStrategyCard`s for worker groups.
15. Add behavioral descriptors and a novelty/Pareto archive.
16. Add diversity and congestion terms to portfolio allocation.
17. Add explicit proposal/attack, model/validate, and complex/minimal replication modes.
18. Add generic novelty operators for Outliers.
19. Track semantic convergence and adapt leaks and dispatches.
20. Require Connector nodes when independent results conflict.

## P2 — Improve final quality and long-run learning

21. Add civilization-level validated-node harvest.
22. Add adaptive exploration/validation/integration budget ratios by round.
23. Add cross-run procedural continuation only for strategies with measured uplift.
24. Add gate-quality metrics and periodically remove unproductive gates.
25. Add selective stronger-model escalation only for critical stalled nodes.

---

# Measure whether the gates are helping

Gates themselves need evaluation.

| Metric                               | Meaning                                                         |
| ------------------------------------ | --------------------------------------------------------------- |
| `gate_rejection_rate`                | How much work each gate blocks                                  |
| `repair_success_rate`                | Fraction of rejected work later repaired successfully           |
| `salvage_rate`                       | Fraction yielding reusable claims, tests, methods, or dead ends |
| `false_reject_estimate`              | Human/calibration sample judged valuable despite rejection      |
| `time_to_promotion`                  | Rounds between first attempt and accepted authority             |
| `gate_cost_per_accepted_node`        | Gate and repair cost per successful node                        |
| `rejected_work_downstream_reuse`     | Whether non-promoted work still helps later agents              |
| `provisional_to_validated_rate`      | Epistemic compounding                                           |
| `novelty_adoption_rate`              | Useful novel vectors adopted or tested                          |
| `portfolio_semantic_overlap`         | Whether societies are exploring independently                   |
| `best_so_far_regression_count`       | Later rounds producing worse candidates                         |
| `receiver_usefulness_on_failed_runs` | Whether rejected runs still deliver actionable output           |

A gate that rejects frequently, provides no actionable diagnostic, has low repair success, and never prevents downstream defects is probably harmful bureaucracy.

A gate that catches corruption, creates precise repair information, and prevents false authority is valuable.

# My overall judgment

The architecture does not mainly suffer from “too many gates.”

It suffers from:

1. **too many binary gate outcomes;**
2. **too little salvage after rejection;**
3. **too little distinction between strict acceptance and ordinary usefulness;**
4. **too much convergence caused by shared model, context, tasks, and incentives;**
5. **too little preservation of alternative and best-so-far work;**
6. **too little procedural learning from successful and failed attempts.**

Keep the hard authority gates.

Redesign everything around them so that:

> **Rejected authority still becomes reusable civilization memory whenever safely possible.**

That would improve compounding and output quality more than adding another model, another role, or another layer of grading.
