# EvoKammer Recursive Self‑Improvement Plan (v4)

> Behaviour‑driven design for a recursive, hypothesis‑driven self‑improvement loop that keeps EvoKammer’s constitutional core and model set immutable while allowing structured evolution of tools, prompts, and heuristics.

---

## 0. Goals

- Allow EvoKammer to **discover, formalize, and test its own hypotheses** about improving its codebase, harness, and group behaviour.
- Ensure all improvements are **verifiable** via tests, metrics, or invariants, in line with current evidence that self‑improvement only works reliably where outcomes are executably checkable.[web:18][web:78]
- Keep the **Constitutional core and model set immutable**:
    - Only an initial, human‑chosen set of cheap models is available to all agents.
    - Any changes to model selection or API keys are strictly manual and out of scope for self‑improvement.
- Architect the Overseer as a **two‑part system**:
    - a deterministic **Overseer‑Kernel** (rule engine), and
    - a constrained **Overseer‑Agent** (LLM) that proposes hypotheses and experiment plans but never applies changes directly.

---

## 1. Literature Grounding

### 1.1 Recursive Introspection (RISE‑style methods)

Recent work on Recursive Introspection (RISE) shows that LLMs can be trained or configured to improve over multiple attempts by **analyzing their own previous failures** and using environment feedback to refine answers over several turns.[web:77] This transforms single‑turn generation into a multi‑step process where each step can correct earlier mistakes.

**Implications for EvoKammer**

- Self‑improvement should be **multi‑turn and feedback‑driven**:
    - Hypotheses are tested across multiple runs and generations, not via one‑shot patches.
    - Each experiment cycle is an opportunity for the system to introspect on what failed and propose refined variants.
- EvoKammer’s Hypothesis lifecycle (idea → formalized → under experiment → accepted/integrated or dead_end) maps naturally onto this pattern.

### 1.2 EvoSyn: Evolutionary Verifiable Synthesis

EvoSyn introduces an evolutionary framework that jointly synthesizes tasks, candidate solutions, and **verification artifacts**, then uses a consistency‑based evaluator and executable checks to keep only high‑quality instances.[web:78][web:81][web:88]

**Implications for EvoKammer**

- Self‑improvement should expand not only code and prompts but also **tests and harnesses** around fragile components.
- Each improvement experiment can synthesize new test cases or diagnostics that increase future verifiability, echoing EvoSyn’s evolutionary generation of verifiable data.

### 1.3 Harness‑First Self‑Improvement (AutoHarness, SIA)

Work on automatic harness synthesis shows that small, cheap models can outperform larger models by iteratively building a **code harness** that prevents illegal or nonsensical actions and, in some cases, synthesizing the full policy as code.[web:64][web:68][web:76][web:85]

**Implications for EvoKammer**

- Focus improvement on the **harness and scaffolding** (tools, glue, prompts, tests, selectors), not on base models, which remain fixed.
- EvoKammer self‑improvement primarily targets:
    - tools and helper libraries,
    - agent prompts and protocols,
    - configuration and orchestration heuristics,
    - new tests and verification harnesses.

### 1.4 Verifiability Constraint on Self‑Improvement

Analyses of self‑improving agents converge on a key point: self‑improvement works only where the system has **external, executable, non‑gameable evaluation signals**; unconstrained recursive loops tend to overfit or reward‑hack their own metrics.[web:18][web:79]

**Implications for EvoKammer**

- All hypotheses must specify **verifiers**: tests, metrics, or invariants that can falsify the hypothesis.
- Overseer‑Kernel must remain **non‑LLM and immutable**, and all patch acceptance decisions must be based on external checks (builds, tests, runtime metrics, constraint checks), not on agent opinions.

---

## 2. Mutability & Model‑Set Constraints

We partition the system into three mutability levels, consistent with EvoKammer’s constitutional design.[file:62]

### 2.1 Immutable Core (Including Model Set)

Cannot be modified by any self‑improvement process:

- `constitution/` (P1–P7 principles, isolation tiers, syscall‑level policies).[file:62]
- `watchdog/` and global cost ledger (stasis detection, generation ceilings, gate opening/closing). [file:62]
- `scoring_engine/` and `blind_tournament/` (sealed test suite execution, blind scoring and reveal). [file:62]
- Integration contract schemas (`contracts/`), such as Agent → Staging, Critic → Substrate, Watchdog signals. [file:62]
- Meta‑Archive schema and persistence layer.
- **Model Catalog and API Keys**:
    - A fixed, initial set of cheap models (e.g. small/fast LLMs) registered by humans.
    - Genesis Council’s Model Ecologist can only choose **routing within this set**, never extend or mutate it.[file:62]
    - API keys and provider settings are never exposed to agents and are not part of the self‑improvement scope.

**Rule:**

- Only humans can change this layer through a manual, audited process.
- Overseer‑Kernel automatically rejects any patch touching these paths or model definitions.

### 2.2 Semi‑Mutable Governance Layer

Changeable but heavily constrained:

- Budget Ledger weights (action costs, failure multipliers). [file:62]
- Evolutionary Selector heuristics (role allocation, outlier cadence and density). [file:62]
- Grader rubric configuration (dimension weights, some thresholds).
- Legacy Protocol ranking thresholds and decay rules. [file:62]

**Rules:**

- Only modified via **structured configuration files**, not arbitrary code edits.
- Hypotheses affecting this layer require:
    - convergence across multiple experiments (similar results in ≥ 2 runs),
    - clear, statistically meaningful improvements on key metrics,
    - often explicit human approval in early stages.

### 2.3 Fully Mutable Application / Harness Layer

The main target for self‑improvement:

- Tools and adapters (Builder tools, test generators, harnesses, analysers).
- Role prompts for all non‑constitutional roles (Researcher, Builder, Planner, Educator, Connector, Outlier, etc.). [file:62]
- Self‑improvement orchestration helpers (excluding Overseer‑Kernel itself).
- Additional tests and verification code (no weakening or deletion of core tests).
- Documentation, examples, developer ergonomics.

**Rules:**

- Freely modifiable by accepted hypotheses, subject to:
    - per‑run scope budgets (max files/lines changed),
    - build + test + invariants checks,
    - full rollback capability.

---

## 3. Hypothesis Model & Lifecycle

### 3.1 Hypothesis Object

Every self‑improvement idea is represented as a structured object:

```yaml
Hypothesis:
  id: HYP-xxx
  title: string
  origin:
    type: enum[internal_metrics, internal_logs, external_paper, hybrid]
    sources: [links to papers, logs, repo files]
  target_scope:
    paths: [glob patterns]
    mutability_level: enum[semi_mutable, fully_mutable]
  rationale: string
  expected_effect:
    metrics_up: [metric_name]
    metrics_down: [metric_name]
    invariants_hold: [invariant_name]
  experiment_plan_id: EXP-yyy
  status: enum[idea, formalized, under_experiment, accepted, integrated, rejected, dead_end]
  notes: string
```

### 3.2 Lifecycle States

- `idea`:
    - Rough suggestion, possibly noisy, often created by Overseer‑Agent.
- `formalized`:
    - Structure and scope are valid; verifiers exist; target paths are in allowed mutability levels.
- `under_experiment`:
    - One or more ExperimentPlans are active, using self‑improvement runs.
- `accepted`:
    - Experiments show consistent benefit according to predefined criteria.
- `integrated`:
    - Patches applied to baseline repo; hypothesis remains for provenance.
- `rejected`:
    - Experiments show no improvement or regressions.
- `dead_end`:
    - Rejected + recorded in an Overseer Dead Ends registry; future similar proposals are discouraged or require explicit override.

---

## 4. Task Decomposition & Hypothesis Families

To “split and widen” tasks, especially high‑level ones like improving group productivity, the system needs a **systematic decomposition and diversification** strategy.

### 4.1 From High‑Level Task to Hypothesis Families

Example high‑level objective:

> “Improve group productivity and outcome quality for code‑oriented EvoKammer runs.”

Overseer‑Agent, inspired by multi‑agent and organisational literature (e.g. hive‑like structures, Roman military hierarchies), proposes **multiple families of hypotheses** targeting different levels:[web:17][web:87]

1. **Structural hypotheses (macro):**
    - Add or modify roles, inspired by biological or historical patterns:
        - Bee‑nest style: workers, scouts, guards, foragers → map to Explorers, Deep Diggers, Critics, Gatekeepers.
        - Roman empire style: legions, cohorts, centurions → map to Group‑of‑Groups leadership, intermediate planners, frontline builders.
    - Change inter‑group topology or cross‑review patterns (e.g. more cross‑audit roles, structured escalation chains).

2. **Protocol hypotheses (meso):**
    - Adjust coordination rhythms: frequency of cross‑reviews, handoff protocols, progression gates.
    - Change how and when certain roles (e.g. Planner, Educator) intervene within a generation.

3. **Micro‑prompt hypotheses (micro):**
    - Fine‑grained tweaks to a single role’s initial prompt, each controlling a very narrow behaviour:
        - e.g. one hypothesis tweaks just how a Critic summarizes rejection reasons,
        - another affects how a Planner chooses which nodes to schedule first,
        - another only adjusts Outlier instructions for exploring off‑mainline directions.

Each family yields dozens of **micro‑hypotheses**, each targeting a tiny, controlled change, rather than a monolithic edit. This widens the exploration space while keeping each intervention small and testable.

### 4.2 Knowledge Node Graph as Decomposition Spine

Genesis Council already produces a **knowledge node graph** where each node is independently achievable within a single agent lifecycle.[file:62]

- For a productivity objective, Overseer‑Agent can:
    - Introduce meta‑nodes representing “productivity improvement levers” (role design, protocol design, micro‑prompt tuning).
    - Attach hypotheses to these meta‑nodes so that improvement work itself is decomposed into sub‑nodes with clear owners and verifiers.

### 4.3 Widening via Hypothesis Templates

Overseer‑Agent uses **hypothesis templates** for different families:

- Structural template: “Add role X with responsibilities Y, inspired by archetype Z, expected to improve metrics M.”
- Protocol template: “Change cadence/ordering of interaction between roles A and B in context C.”
- Micro‑prompt template: “Modify sentence S in role R’s prompt to emphasize behaviour B in situation Q.”

Each template expands into many concrete Hypotheses with small, disjoint diffs, allowing EvoKammer to run **A/B/C tests** across different combinations without over‑entangling changes.

---

## 5. Overseer Architecture: Kernel + Agent

### 5.1 Overseer‑Kernel (Deterministic, Immutable)

Responsibilities:

- Maintain:
    - `HypothesisRegistry` (all hypotheses + states).
    - `ExperimentRegistry` (ExperimentPlans and results).
    - `OverseerDeadEnds` (hypotheses that failed and must not be retried naïvely).
- Enforce:
    - Mutability boundaries (immutable vs semi‑mutable vs fully mutable).
    - Experiment budgets (run counts, generations, cost ceilings).
    - Metric dominance and invariant checks.
- Decide:
    - which hypotheses are ready for experimentation,
    - which are accepted vs rejected after experiments.

Properties:

- Pure rule engine; **no LLM calls**.
- Part of the immutable core; only humans may change its rules or code.
- Has read‑only access to Metric histories, Meta‑Archive, Dead Ends, and to the sealed judge test suites.[file:62]

### 5.2 Overseer‑Agent (Constrained LLM)

Responsibilities:

- Scan internal signals:
    - Generation Event Logs,[file:62]
    - failure telemetry (from Scoring Engine and Critics),[file:62]
    - Dead Ends Registry and Meta‑Archive.
- Scan external research:
    - self‑improvement, recursive introspection, evolutionary synthesis, harness design.[web:77][web:78][web:85]
- Propose:
    - new Hypotheses,
    - ExperimentPlans for existing Hypotheses,
    - PlanUpdates that refine the self‑improvement process itself.

Limitations:

- Read‑only on immutable core and Kernel rules.
- Cannot apply patches or modify code/config directly.
- All outputs must be **structured** (YAML/JSON) and pass Kernel’s validation.

---

## 6. Experiment Plans & Execution

### 6.1 ExperimentPlan Object

```yaml
ExperimentPlan:
  id: EXP-yyy
  hypothesis_id: HYP-xxx
  run_count: int
  generations_per_run: int
  variation_spec:
    config_changes: [...]
    tool_or_prompt_changes: [...]
  metrics:
    primary: [names]
    secondary: [names]
  acceptance_criteria:
    predicates: [boolean expressions over aggregated metrics]
  max_cost: float
```

### 6.2 Running Experiments via EvoKammer

1. Kernel selects a batch of `formalized` or `under_experiment` hypotheses.
2. For each, Kernel launches one or more self‑improvement runs with the ExperimentPlan’s variations:
    - EvoKammer uses a **self‑improvement StartingField** encoding:
        - the Hypothesis and ExperimentPlan,
        - the metrics and invariants as typed KG constraints,
        - a sealed `judge_test_suite` specific to this experiment.[file:62]
3. Each run yields:
    - `PatchBundle` candidates (JSON bundles containing edited files or diffs, documentation, and verification steps),
    - `RunReport` metrics (test results, cost, wall‑clock, constraint violations),
    - experiment‑local Dead Ends.
4. Kernel aggregates results, evaluates acceptance predicates, and updates Hypothesis status.

---

## 7. Patch Acceptance & Overseer Dead Ends

### 7.1 Acceptance Rules

A candidate patchset can be applied only if:

1. **Scope Check**
    - No patches touch immutable paths or model definitions.
    - For semi‑mutable configs, only allowed fields are changed.

2. **Tests & Invariants**
    - Clean rebuild in a fresh environment.
    - All relevant tests in the sealed judge suite pass.[file:62]
    - All invariants hold, including:
        - no KG hard‑constraint violations,
        - no scoring seal breaches,
        - no cross‑group contamination.

3. **Metric Dominance**
    - No regressions on critical metrics (correctness, safety, constitutional guarantees).
    - At least one target metric improves beyond a configured threshold (e.g. cost per success, wall‑clock per generation), potentially inspired by RISE and EvoSyn findings.[web:77][web:78]

4. **Change Budget**
    - Patchset stays within per‑run file and line limits.

If all checks pass, Kernel:

- Marks the Hypothesis `accepted`, then `integrated`.
- Applies the patchset to the baseline repo, tags the new version, and triggers a general regression run.

Patch bundles should be emitted as machine-readable JSON so downstream tooling can either apply the edited files directly or reconstruct a diff from the bundle contents. The bundle should include at least `summary`, `changedFiles`, `documentation`, and `verification`.

### 7.2 Rejection & Dead Ends

If a Hypothesis fails acceptance after N experiments, or clearly degrades key metrics:

- Status becomes `rejected`, then `dead_end`.
- Kernel records:
    - minimal counterexamples,
    - metrics and key reasons for failure,
    - possibly a small synthetic test (EvoSyn‑style) that demonstrates the failure mode.[web:78][web:83]
- Entries go to both:
    - `OverseerDeadEnds` (hypothesis‑level memory), and
    - EvoKammer’s canonical Dead Ends Registry for agent‑level retrieval.[file:62]

Overseer‑Agent must check `OverseerDeadEnds` and either avoid re‑proposing similar hypotheses or justify why a new attempt is materially different.

---

## 8. Recursive Loop & Plan Self‑Improvement

### 8.1 Outer Loop

For each outer self‑improvement cycle:

1. Kernel selects a mix of hypotheses across families:
    - structural (roles/topologies),
    - protocol (coordination),
    - micro‑prompt and tool changes.
2. Schedules ExperimentPlans under cost and run limits.
3. Integrates accepted patches, marking Hypotheses as `integrated`.
4. Sends rejected ones to Dead Ends.
5. Periodically, invites Overseer‑Agent to propose **PlanUpdates** that adjust:
    - which metrics matter most,
    - how hypotheses are prioritised,
    - which experiment templates should be used.

PlanUpdates are just Hypotheses targeted at semi‑mutable configs; they go through the same experimental and acceptance process.

### 8.2 Stopping Conditions & Safety

The loop is bounded by:

- Per‑hypothesis limits:
    - max number of ExperimentPlans and runs,
    - minimum effect size threshold.
- Global safety valves:
    - global cost or generation ceilings (enforced by Watchdog),[file:62]
    - halting if core metrics degrade across recent versions.

Manual controls:

- Humans can pause all experiments, manually promote/demote hypotheses, and seed new hypotheses that the Overseer‑Agent can then expand.

---

## 9. Rollout Phases

1. **Phase A — Safe Harness Space**
    - Allow hypotheses only over tools, prompts, docs, and test additions.
    - Use simple metrics: test pass rate, cost/run, basic productivity indicators.

2. **Phase B — Governance Configs**
    - Carefully allow hypotheses modifying selector and budget configs.
    - Require convergence, stronger metrics, and sometimes human co‑approval.

3. **Phase C — Plan Self‑Improvement**
    - Allow Overseer‑Agent to propose PlanUpdates about which hypotheses to prefer and how experiments should be designed.
    - Evaluate as normal hypotheses targeting semi‑mutable configs.

4. **Phase D — Hardening & Expansion**
    - Add chaos testing (kill runs mid‑experiment, verify no corruption).
    - Potentially expand experiment scope, but never into the immutable core or model set.


---

## 10. Summary

This plan makes EvoKammer’s self‑improvement loop **hypothesis‑driven, literature‑grounded, and tightly bounded by verifiability**. High‑level goals like “improve group productivity” are decomposed into families of structural, protocol, and micro‑prompt hypotheses, which are then tested through carefully budgeted experiments. A deterministic Overseer‑Kernel decides what gets integrated based on external tests and metrics, while a constrained Overseer‑Agent mines internal logs and external papers (RISE, EvoSyn, harness synthesis) to propose new options.[web:77][web:78][web:64][web:85][web:18][file:62] The constitutional core and model set remain immutable and cheap‑model‑only, ensuring the system can evolve its harness and strategies without ever being able to rewrite its fundamental safety guarantees or upgrade itself to more powerful base models without human intervention.
