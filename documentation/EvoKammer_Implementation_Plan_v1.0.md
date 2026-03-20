# EvoKammer — Implementation Plan v1.0

> *A behaviour-driven implementation roadmap for the full EvoKammer civilisational multi-agent system.*
> *This document describes expected behaviours and integration contracts without prescribing implementation technology.*

---

## Table of Contents

1. [Guiding Principles](#1-guiding-principles)
2. [Separation & Isolation Model](#2-separation--isolation-model)
3. [Component Catalog & Expected Behaviours](#3-component-catalog--expected-behaviours)
4. [Integration Contracts](#4-integration-contracts)
5. [Implementation Phases](#5-implementation-phases)
6. [Acceptance Criteria per Phase](#6-acceptance-criteria-per-phase)
7. [Testing & Validation Strategy](#7-testing--validation-strategy)
8. [Risk Register](#8-risk-register)
9. [Operational Considerations](#9-operational-considerations)

---

## 1. Guiding Principles

These principles govern every implementation decision throughout the project. Any design that violates them requires explicit justification and approval.

### P1 — Substrate Independence
The Persistent Substrate (Knowledge Base, Knowledge Graph, Tool Registry) must be able to survive the complete restart of every agent, every group, and every orchestration layer. No information that matters may live only in a running process.

### P2 — Hard Isolation by Default
Every security, budget, and lifecycle boundary is enforced by the infrastructure layer — not by the agent's own behaviour. An agent that ignores its budget, tries to read another group's KB, or attempts to extend its own TTL must be stopped by the platform, not by prompting.

### P3 — Observability Without Coupling
Every component emits structured events to a central log. No component calls another directly to report its state. Monitoring and debugging reads from the log; it never modifies system state.

### P4 — Contracts Over Conventions
Every boundary between components is expressed as a typed contract (schema, API shape, event format). No component may depend on undocumented side effects of another. Contracts are versioned and backward-compatible.

### P5 — Staged Write, Free Read
Reads of any Substrate layer by any agent are always permitted. Writes of any kind go through a staging pipeline and require Critic approval before becoming canonical. This is a system-level invariant, not an agent-level guideline.

### P6 — Fail Closed
When any component is uncertain, unavailable, or behaving unexpectedly, the system defaults to doing less: kills the agent, rejects the staging submission, or declines the action. It never defaults to doing more or bypassing a gate.

### P7 — Stateless Agents
An agent's behaviour must be fully reproducible from: (a) its role prompt, (b) its budget ledger state, (c) the content of the canonical Substrate at the time of its retrieval call. Any internal state an agent maintains that is not externalised before death is considered lost. This is acceptable and expected.

---

## 2. Separation & Isolation Model

### 2.1 Isolation Tiers

The system has four hard isolation boundaries. Crossing any boundary without the correct protocol is a constitutional violation.

```text
Tier 0 — Global boundary
  The Watchdog, Council, and Meta-Planner live here.
  No agent from any group can write here directly.
  Only the Watchdog modifies the global state (e.g. triggering check-ins, emitting leaks).

Tier 1 — Group boundary
  Each competing group is fully isolated from others at the storage and network level.
  Group A's KB, KG, Tool Registry, Staging Area, and agent processes are invisible to Group B.
  The only legal cross-group data flows are:
    - Intelligence Leaks (emitted by the Watchdog Leak Oracle, never by groups directly)
    - Formal Dispatches (emitted by a group through the Dispatch Relay, received after lag)
    - Cross-review assignments (given by the Watchdog at check-in time only)

Tier 2 — Agent boundary
  Each agent runs in an isolated execution environment.
  An agent can read the group's canonical Substrate.
  An agent can write only to the group's Staging Area.
  An agent cannot read or write the Staging Area of another agent in the same group.
  An agent cannot communicate with agents in other groups directly.

Tier 3 — Tool sub-sandbox boundary
  When an agent executes a tool, the tool runs in a nested isolated process.
  The tool cannot read the agent's context window.
  The tool cannot write to Substrate directly.
  The tool's output is captured and returned to the agent; the agent decides whether to use it.
```

### 2.2 Data Flow Map

```text
[Genesis Council] ──writes once──► [StartingField]
                                          │
                    ┌─────────────────────┤
                    ▼                     ▼
             [Group Instance A]    [Grader Instance A]
                    │
        ┌───────────┼───────────────────────┐
        │           │                       │
  [Canonical   [Staging    [Agent       [Tool
   Substrate]   Area]       Sandbox]    Registry]
        ▲           │           │
        │    [Critic Review]    │
        │           │           │
        └───────────┘           │
                         [Tool Sub-Sandbox]
                                │
                         (output returned to agent)

Cross-group:
[Watchdog Leak Oracle] ──► [Group B Intelligence Inbox]
[Group A Dispatch Relay] ──► [Global Bulletin Board] ──► [Group B, after lag]
[Watchdog] ──► [Check-in Scoring Engine] ◄── [all groups submit simultaneously]
```

### 2.3 What Cannot Cross Any Boundary Without Transformation

- Raw KB contents (must pass through differential privacy transform before leaking)
- Agent context windows (never exported; Death Note is the only sanctioned export)
- Budget ledger state (agent-private; only post-mortem aggregate exported to log)
- Canonical KB of one group (no group can read another's canonical KB at any time)
- Tool Registry of one group (tools are group-local; cross-group tool sharing only via Dispatch)

---

## 3. Component Catalog & Expected Behaviours

### 3.1 Genesis Council

**Purpose**: Produce a complete, self-consistent StartingField configuration from a raw objective input, with no human intervention.

**Inputs**: A raw objective description in natural language (constraint-framed, not target-framed).

**Expected behaviours**:
- The Council fires once per task. It never restarts mid-task.
- Each Council role agent runs in parallel; the Synthesis Agent runs last, sequentially.
- The Task Anatomist must emit a knowledge node graph where every node is independently achievable within a single agent lifecycle's budget ceiling.
- The KG Architect must emit a typed schema consistent with the task domain; the schema must declare at least one hard constraint and at least one soft constraint.
- The Model Ecologist must assign at minimum two distinct model families across the set of roles; Grader Group roles must be assigned a different family than main competing group roles.
- The Synthesis Agent must run consistency checks and emit a `consistency_report` alongside the StartingField. A StartingField with a failing consistency check is held until the relevant Council role re-runs with the conflict as additional context.
- The sealed test suite (for check-in and final scoring) must be finalised and locked before any competing group spawns its first agent. It is written by the Synthesis Agent and never read by any agent.

**Outputs**: A validated `StartingField` object, a `consistency_report`, and a sealed and locked `judge_test_suite`.

**Isolation**: Council agents run in isolated environments as per Tier 2. Their staging pipeline is separate from all group staging pipelines. Council KB is transient — it is not carried forward into any group's Substrate.

---

### 3.2 Lifecycle Manager

**Purpose**: Enforce the birth, resource accounting, and hard death of every agent. The single authority over agent spawning and killing.

**Expected behaviours**:
- Reads the StartingField's `agent_blueprints` and `budget_ledger_config` at system start.
- Spawns agents based on the Evolutionary Selector's role allocation signal, not on request from agents.
- Injects into each new agent: role prompt, budget token, read credentials for group Substrate, write credentials for group Staging Area, WiP task claim (from Work-in-Progress Registry).
- If a Legacy Protocol exists for the agent's role and the fitness threshold was exceeded in the previous generation, injects the Legacy Protocol as a privileged context block.
- Monitors budget consumption in real time via the Budget Ledger. Initiates kill sequence when budget reaches the floor.
- Kill sequence is: (1) signal agent to begin Death Note; (2) wait up to a configured grace period; (3) hard kill regardless of whether Death Note is complete.
- Captures the agent's Staging Area output post-kill and routes it to the Critic queue and Post-Mortem Scorer.
- Never accepts an instruction from any agent to extend a lifecycle, increase a budget, or bypass a kill.
- Emits a structured lifecycle event to the Generation Event Log for every spawn and death.

**Sub-agent delegation**: When an agent spawns a sub-agent, the Lifecycle Manager validates that the delegated budget does not exceed the parent's remaining budget minus the floor. Sub-agents are registered under the parent's ID in the event log.

---

### 3.3 Budget Ledger

**Purpose**: Track resource consumption for every active agent with precision. Enforce the asymmetric cost model.

**Expected behaviours**:
- Every agent has exactly one Budget Ledger instance, allocated at spawn.
- Budget decrements are atomic — concurrent tool calls from the same agent cannot race past zero.
- Decrements applied per action type at rates defined in the StartingField `budget_ledger_config`.
- Reads always consume zero budget. This is enforced at the Ledger level, not left to the agent.
- A tool execution that fails consumes double the cost of a success. A retry of the same tool with identical arguments consumes quadruple. The Ledger detects retry identity via argument hashing.
- When balance reaches the floor, the Ledger emits a kill signal to the Lifecycle Manager and becomes read-only.
- Budget history is exported to the Generation Event Log on agent death (for post-mortem fitness scoring).

---

### 3.4 Persistent Substrate

**Purpose**: The civilisational memory layer. The only thing that survives agent death.

**Scope**: Each competing group has its own fully independent Substrate instance. Grader Groups each have their own. The Council has a transient Substrate that is discarded post-task. The Watchdog has a read-only view across all group Substrates (anonymised).

**Expected behaviours (per Substrate instance)**:

*Knowledge Base*:
- Stores versioned, validated, full-text documents with vector embeddings.
- Every document carries: version number, source agent ID, generation number, trust score, citation count.
- Documents are immutable after promotion from staging. A new version creates a new document; it does not overwrite.
- Documents with trust score below the decay threshold at generation N are archived, not deleted.
- The Dead Ends Registry is a first-class KB section: never decays, always returned in retrieval regardless of trust score.

*Knowledge Graph*:
- Stores typed fact quads: (subject, relation, object, metadata).
- Before a fact is committed, the KG Consistency Checker validates it against the schema constraints and against existing higher-trust facts.
- A constraint violation from a lower-trust source raises a dispute; the dispute goes to a KG-Critic agent for resolution.
- KG entries carry the same trust score mechanics as KB documents.
- Agents can query the KG via a pattern-matching interface (graph traversal with typed filters).

*Tool Registry*:
- Stores versioned, sandboxed, executable tools.
- Every tool entry carries: version, author agent, success rate (running average), failure log (last N failures), input/output schema, domain tag.
- Tools with success rate below threshold for N consecutive generations are flagged for Archivist review.
- Tools are never deleted; deprecated tools move to an archived state and remain inspectable.

*Staging Area*:
- Agent-private write space during lifecycle.
- After agent death, becomes readable only by the Lifecycle Manager and Critic agents.
- Staging submissions carry: type (doc/tool/fact/death_note), origin agent ID, origin role, generation, optional `speculative: true` flag (for outlier submissions).
- Critic review is the exclusive gate from Staging to canonical Substrate.

*Work-in-Progress Registry*:
- A task claim table: each entry is a subproblem node from the knowledge node graph.
- Claims are atomic: only one agent can hold a claim at a time.
- Claims are released on agent death (either completed or abandoned) and the release reason is logged.
- Unclaimed nodes are visible to all agents. Claimed nodes show as locked.

---

### 3.5 Critics & Validation Pipeline

**Purpose**: The sole authority on what enters the canonical Substrate. The system's immune system.

**Expected behaviours**:
- A Critic agent is spawned with a queue of staged submissions to review.
- A Critic operates on one submission at a time; it cannot approve its own staging submissions from a previous lifecycle.
- A Critic evaluates free-text documents for: factual consistency with canonical KB, hallucination signals, relevance to claimed WiP task, minimum quality threshold from the StartingField rubric.
- A Critic evaluates tools for: runnable code, minimum test coverage, input/output schema compliance, no constitutional violations (no network calls not declared, no filesystem access outside /workspace).
- A Critic evaluates KG facts for: schema type compliance, hard constraint satisfaction, conflict with higher-trust existing facts.
- A Critic produces one of three outcomes: PROMOTE, REJECT, or DISPUTE.
  - PROMOTE: item enters canonical Substrate.
  - REJECT: item is discarded with rejection reason written to the Generation Event Log.
  - DISPUTE: item is held; Lifecycle Manager spawns a specialised Arbitration Agent to resolve.
- Outlier-origin submissions undergo identical Critic evaluation but with a lower baseline trust score for promoted items.

---

### 3.6 Evolutionary Selector

**Purpose**: Dynamically adjust the composition of the agent population per generation based on Knowledge Base state and bottleneck signals.

**Expected behaviours**:
- Runs once per generation boundary (after the previous generation's Death Notes are committed).
- Reads: canonical KB density per domain, Staging Area backlog by type, tool failure rates, Plan Registry freshness, stasis signal from the Inspector.
- Emits a `role_allocation` directive to the Lifecycle Manager: how many agents of each role to spawn in the next generation, and at what budget level.
- Hard constraint: total allocated budget across all next-generation agents must not exceed the group's per-generation budget ceiling.
- Hard constraint: at least one Critic must be allocated every generation.
- Hard constraint: at least one Outlier must be allocated every 5th generation; if stasis is detected, one Outlier per 3 generations.
- Outlier agents are never assigned a WiP task by the Selector; their task claim is left open.
- Emits a record of its allocation decision to the Generation Event Log.

---

### 3.7 Inspector & Auditor

*Inspector*:
- Passive continuous process, not a lifecycle-bound agent.
- Reads the Generation Event Log in near-real time.
- Emits anomaly flags to the Watchdog when: agent budget burn rate is abnormal for role, staging acceptance rate drops below threshold, same WiP task is claimed and abandoned repeatedly, knowledge pollution pattern detected (identical low-quality submissions from multiple agents).
- Never modifies system state directly.

*Auditor*:
- Spawned by the Watchdog on high-severity Inspector flags.
- Has read access to full Generation Event Log (anonymised) and all Staging Areas (including in-flight).
- Has write access to a `system_flags` table consumed by the Lifecycle Manager and Evolutionary Selector.
- Cannot write to any canonical Substrate directly.
- Is itself lifecycle-bound: TTL and budget, same as any agent. Cannot extend its own lifecycle.
- Emits findings to the Generation Event Log on death.

---

### 3.8 Multi-Group Coordinator

**Purpose**: Manage the ring topology between competing groups and control cross-group information flows.

**Expected behaviours**:
- Maintains the inter-group topology defined by the StartingField.
- Manages the Formal Dispatch Relay: receives dispatch submissions from groups, holds them for the configured lag period, then delivers to the designated neighbour group's Dispatch Inbox.
- Manages the Leak Oracle: at configured intervals, samples KB content from a source group, applies the differential privacy transform (semantic summarisation with bounded detail), delivers to recipient group's Intelligence Inbox.
- Leak Oracle increases frequency for groups in stasis (as flagged by Watchdog).
- Maintains a per-group `trust_score_table` for Formal Dispatch sources; degrades trust when dispatch content is consistently rejected by receiving group Critics.
- Enforces migration rate ceiling: if cross-group information volume exceeds 15% of any group's active KB per generation, throttles the Leak Oracle for that group.
- The Coordinator never exposes group identities in leaked content; all source references in leaks are anonymised.

---

### 3.9 Resource Map Engine

**Purpose**: Manage the territorial knowledge node graph and enforce claim/recapture mechanics.

**Expected behaviours**:
- Maintains the knowledge node graph emitted by the Genesis Council.
- Tracks current holder per node and the quality score of the claiming artifact.
- A claim is registered when a group's Critic promotes an artifact meeting the node's minimum quality threshold.
- A recapture is registered when a different group's promoted artifact scores higher than the current holder's artifact for the same node.
- Resource yields (cost reductions, budget bonuses) from held nodes are applied by the Budget Ledger per agent spawn within that domain.
- The Resource Map Engine emits node state changes to the Generation Event Log.
- Guaranteed floor nodes per group are immutable — no recapture is possible on home territory nodes.

---

### 3.10 Grader Groups

**Purpose**: Produce the evaluation rubrics (GradingSheets) used to score main competing groups at check-ins.

**Expected behaviours**:
- Grader Groups run a complete EvoKammer civilisation cycle, independently of competing groups.
- Grader agents have no access to competing group KBs, Tool Registries, or identities.
- At check-in time, Grader Groups receive anonymised artifact bundles from the Scoring Engine.
- Each Grader Group produces a GradingSheet covering: automated test cases, rubric dimensions, proposed domain weights, known failure modes, and comparative pair rankings.
- At check-in evaluation, all Grader Group GradingSheets are submitted simultaneously.
- The Meta-Judge runs a Consensus Detection pass: dimensions all Graders agree on become consensus dimensions; others go to a cross-grader arbitration round.
- Consensus dimensions enter the final rubric directly; arbitrated dimensions enter after arbitration resolves.
- GradingSheet quality is itself scored by the Meta-Judge (internal consistency, test case validity, coverage breadth). This score influences Grader Group resource allocation in subsequent generations.
- Grader Groups receive anonymised failure telemetry from previous check-ins to inform GradingSheet improvement.

---

### 3.11 Scoring Engine & Blind Tournament

**Purpose**: Run objective, un-manipulable scoring at check-in and final submission.

**Expected behaviours**:
- The Scoring Engine is isolated from all agent systems; it cannot be queried or influenced by any agent.
- At each check-in gate: collects submissions from all groups, seals them, runs the deterministic test suite (from the sealed `judge_test_suite`) against each submission, runs the final GradingSheet rubric against each submission, collects cross-group audit scores.
- Scores are held in a sealed ledger until all groups have submitted. No partial score is visible to any group or agent before the full gate closes.
- Once all submissions are received, scores are disclosed simultaneously to all groups.
- Groups that pass the check-in threshold receive Stage N+1 disclosure from the Progressive Disclosure Engine.
- Groups below threshold receive no stage advance, and their Leak Oracle frequency is increased.
- Final submission scoring uses the same engine with the same test suite, plus any GradingSheet improvements from the last Grader Group generation.

---

### 3.12 Progressive Disclosure Engine

**Purpose**: Gate the release of global task information to groups based on Knowledge Base readiness and check-in results.

**Expected behaviours**:
- Stage 0 is active for all groups at genesis: domain context only, no goal.
- Stage N unlocks for a group when that group passes Check-in N.
- Each stage disclosure is group-local: Group A may be on Stage 3 while Group B is on Stage 1.
- Stage content is pre-written by the Genesis Council and held sealed until the unlock condition is met.
- Stage content is framed as constraints and observed problems, not as explicit targets.
- The Meta-Planner is the only agent with access to a goal-framed view of the task for progress assessment.

---

### 3.13 Epigenetic Inheritance Engine

**Purpose**: Extract, score, and inject Legacy Protocols from elite deceased agents into next-generation agents.

**Expected behaviours**:
- Post-mortem fitness scoring runs on all dead agents after each generation.
- Scoring requires forward citation signals that only stabilise 3–5 generations post-death; preliminary scores are calculated immediately, final scores updated retrospectively.
- When a final score exceeds the fitness threshold (top 20% of their role cohort), the agent's Death Note and action log are submitted to the Legacy Protocol Extractor.
- The Extractor produces a structured Legacy Protocol: reasoning posture, tool-use heuristics, failure map, KG habits.
- If two agents in the same role exceed the threshold, their protocols are submitted to the Crossover Engine, which produces a merged protocol.
- The Legacy Protocol is stored in the group's Legacy Protocol Store, tagged by role and generation.
- On the next spawn of the same role, the Lifecycle Manager injects the most recent applicable Legacy Protocol as a privileged context prefix.
- A Legacy Protocol expires after 2 generations regardless of lineage quality.
- Failure maps from all Legacy Protocols are extracted and promoted to the canonical Dead Ends Registry.
- Outlier agents never receive a Legacy Protocol under any circumstances.

---

### 3.14 Global Watchdog

**Purpose**: The deterministic rule engine that monitors global system health and triggers responses. Not an LLM; never makes judgment calls.

**Expected behaviours**:
- Continuously reads the Generation Event Log across all groups.
- Maintains threshold state for: per-group stasis counter, global cost accumulator, migration rate per group pair, group KG density vs KB density ratio.
- Triggers: Meta-Planner spawn on global stasis or cost ceiling approach; Auditor spawn on Inspector high-severity flag; Leak Oracle frequency increase on stasis; check-in gate opening when generation milestone is reached; final submission protocol when all groups pass final check-in or when the global generation ceiling is reached.
- The Watchdog never modifies Substrate content. It emits signals to other components; it does not act directly.
- The Watchdog is the only component that can open and close check-in gates.
- The Watchdog maintains a global cost ledger across all groups. If the total run cost exceeds the configured budget ceiling, it pauses all spawning and triggers the Meta-Planner.

---

### 3.15 Meta-Planner

**Purpose**: A mortal LLM agent with elevated read access, tasked with assessing global progress and rewriting the Plan Registry.

**Expected behaviours**:
- Spawned by the Watchdog. Has an extended but finite TTL and budget.
- Can read Generation Event Logs for all groups (anonymised by group ID).
- Can read the global Watchdog state.
- Can write to any group's Plan Registry via a privileged staging route (bypasses Critic for structure but not for KG constraint checks).
- Can write to the `system_flags` table consumed by the Lifecycle Manager.
- Cannot read any group's canonical KB directly.
- Cannot modify the Constitutional Laws.
- Cannot extend its own or any other agent's TTL.
- Dies on TTL expiry. A fresh Meta-Planner is spawned next time the Watchdog triggers, ensuring no persistent bias accumulation.

---

## 4. Integration Contracts

Every component pair communicates through a defined contract. Undocumented side effects are disallowed.

### 4.1 Agent → Staging Area

```text
Submission payload:
  - submission_id: uuid
  - agent_id: uuid
  - role: enum[researcher, builder, planner, critic, archivist, educator, connector, outlier, ...]
  - type: enum[document, tool, fact, death_note, plan_update, dispatch]
  - origin_flags: { speculative: bool, outlier: bool }
  - content: typed per submission type
  - wip_task_reference: node_id (required for doc and tool types)
  - budget_signature: signed token from Budget Ledger confirming deduction occurred
```

The Staging Area must reject any submission missing a valid budget signature.

### 4.2 Critic → Substrate

```text
Promotion record:
  - submission_id: uuid
  - critic_agent_id: uuid
  - outcome: enum[PROMOTE, REJECT, DISPUTE]
  - rationale: string (required for REJECT and DISPUTE)
  - initial_trust_score: float (0.0–1.0)
  - promoted_at_generation: int
```

### 4.3 Lifecycle Manager → Agent (at spawn)

```text
Agent boot context:
  - agent_id: uuid
  - role: enum[...]
  - role_prompt: string (from StartingField)
  - budget_token: signed token
  - substrate_read_credential: scoped read token for group KB/KG/Tools
  - staging_write_credential: scoped write token for group Staging Area
  - wip_task_claim: { node_id, node_description } or null (for outliers)
  - legacy_protocol: LegacyProtocol or null
  - stage_disclosure: string (current task stage for this group)
```

### 4.4 Watchdog → Other Components

```text
Watchdog signal:
  - signal_id: uuid
  - type: enum[SPAWN_META_PLANNER, SPAWN_AUDITOR, INCREASE_LEAK_RATE, OPEN_CHECKIN_GATE, CLOSE_CHECKIN_GATE, PAUSE_SPAWNING, TRIGGER_FINAL_SUBMISSION]
  - target_group_ids: list (or ALL)
  - metadata: {}
```

Watchdog signals are broadcast to a signal bus. Components subscribe to signals relevant to them.

### 4.5 Leak Oracle → Group Intelligence Inbox

```text
Leak packet:
  - leak_id: uuid
  - target_group_id: string
  - source_group_id: REDACTED (never exposed)
  - content: privacy-transformed semantic summary
  - source_domain: string (domain tag only, not source group)
  - generation_estimate: approximate (intentionally imprecise)
```

### 4.6 Scoring Engine → Groups (on gate close)

```text
CheckinResult:
  - checkin_id: uuid
  - group_id: string
  - overall_score: float
  - dimension_scores: { dimension_name: score }
  - test_pass_rate: float
  - passed_threshold: bool
  - failure_telemetry: [ { domain, failure_type, pattern_hash } ] (anonymised)
```

---

## 5. Implementation Phases

### Phase 0 — Foundation (Pre-agent)

**Goal**: Stand up all infrastructure that must exist before any agent runs.

Components built:
- Persistent Substrate (KB, KG, Staging Area, Tool Registry, Work-in-Progress Registry)
- Budget Ledger
- Generation Event Log
- Constitutional Law Enforcer (syscall-level isolation)
- Agent execution environment (microVM management, filesystem mounts, network allowlisting)
- Tool Sub-Sandbox (nested process isolation, I/O capture, hard timeout)
- Web content sanitisation proxy

**Exit condition**: A single agent can be spawned, run, read from the KB, write to Staging, execute a sandboxed tool, and die with its Death Note captured — all under budget enforcement and syscall isolation.

---

### Phase 1 — Single Group, Single Generation

**Goal**: Validate the full agent lifecycle loop within one group, one generation.

Components built/activated:
- Lifecycle Manager (spawn + kill only)
- Critic + Validation Pipeline
- Evolutionary Selector (simple static allocation for Phase 1)
- Inspector (passive log monitoring)
- Role prompts for: Researcher, Builder, Critic, Archivist

**Expected behaviour**: 3–5 agents run in parallel. Researchers read the seed KB, produce staged documents. Builders produce staged tools. Critics review. Promoted content enters canonical KB. All agents die on budget. Death Notes are committed.

**Exit condition**: After one generation, canonical KB has at least one validated document, one validated tool, and the Dead Ends Registry has at least one entry from a failed approach.

---

### Phase 2 — Multi-Generation, Single Group

**Goal**: Validate civilisational compounding — each generation builds on the last.

Components activated:
- Evolutionary Selector (full dynamic allocation)
- Legacy Protocol Extractor and Injector
- Epigenetic Inheritance Engine
- Post-Mortem Fitness Scorer
- Outlier agent role
- Planner, Educator, Connector roles

**Expected behaviour**: Over 5–10 generations, KB density grows measurably. Role ratios shift based on bottleneck signals. At least one Legacy Protocol is extracted and injected. An Outlier agent produces a submission that either gets promoted (seeding a new direction) or goes to Dead Ends.

**Exit condition**: Generation 10 agents demonstrably retrieve and build on Generation 3 outputs, as evidenced by citation chains in Death Notes.

---

### Phase 3 — Knowledge Graph Integration

**Goal**: Introduce the typed KG layer alongside the KB and validate constraint enforcement.

Components built/activated:
- KG Consistency Checker
- KG-Critic capability
- KG pattern query interface for agents
- KG fact emission by Researcher/Builder agents
- Trust score propagation across KG entries

**Expected behaviour**: Agents emit typed facts alongside text documents. KG-Critic rejects a constraint-violating fact at least once during a test run. The KG becomes queryable and returns typed results used by subsequent generation agents.

**Exit condition**: The KG contains at least 50 validated facts after 10 generations, with zero promoted hard-constraint violations.

---

### Phase 4 — Multi-Group Competition

**Goal**: Stand up multiple competing groups with complete isolation and the ring topology.

Components built/activated:
- Multi-Group Coordinator
- Formal Dispatch Relay
- Leak Oracle (raw, pre-privacy-transform)
- Resource Map Engine
- Per-group Substrate isolation at storage level

**Expected behaviour**: 3 groups run in parallel. Group A cannot read Group B's KB. Intelligence leaks arrive in recipient groups' Inboxes at correct intervals. A node capture and recapture event occurs at least once during a test run.

**Exit condition**: 3 groups complete 5 generations each with zero cross-contamination events (verified by log audit). At least one Formal Dispatch is sent and received after lag.

---

### Phase 5 — Differential Privacy Leak Transform

**Goal**: Replace raw leaks with semantically-preserving, detail-bounded summaries.

Components built/activated:
- Differential Privacy Transform layer in the Leak Oracle
- Trust scoring for Formal Dispatches

**Expected behaviour**: A leaked summary from Group A reaching Group B contains no group-specific identifiers, no verbatim code, and no exact API names. A human reviewer should not be able to determine the source group from the content.

**Exit condition**: 10 consecutive leaks pass a manual privacy audit: no source-traceable content.

---

### Phase 6 — Grader Groups & Blind Scoring

**Goal**: Stand up Grader Groups as fully independent civilisations and integrate them into the scoring pipeline.

Components built/activated:
- Grader Group instances (separate Substrate, separate agent population)
- GradingSheet schema and emission
- Meta-Judge Consensus Detection
- Cross-grader Arbitration
- Scoring Engine (deterministic test harness + GradingSheet rubric application)
- Blind submission gate (hold-until-all-submit)

**Expected behaviour**: At a simulated check-in, all competing groups submit. Scores are sealed. Grader Groups produce GradingSheets. Meta-Judge produces a consensus rubric. Score reveal happens simultaneously. Below-threshold groups receive no stage advance.

**Exit condition**: A full check-in cycle completes with correct blind sealing, correct reveal timing, and no score visible to any agent before gate closes.

---

### Phase 7 — Progressive Disclosure & Check-in Curriculum

**Goal**: Validate full multi-stage task disclosure gated by check-in performance.

Components built/activated:
- Progressive Disclosure Engine
- Check-in schedule enforcement in the Watchdog
- Stage content sealed and stored per the StartingField
- Failure telemetry routing from Scoring Engine to Grader Group KBs

**Expected behaviour**: Groups that pass Check-in 1 receive Stage 2 content. Groups that fail do not. Failure telemetry from Check-in 1 causes Grader Groups to add new test cases before Check-in 2.

**Exit condition**: At least two groups complete two check-in cycles with correct stage gating and measurably improved GradingSheets between rounds.

---

### Phase 8 — Genesis Council Automation

**Goal**: Replace the hand-crafted StartingField with a fully automated Genesis Council.

Components built/activated:
- All 8 Genesis Council roles (including KG Architect and Model Ecologist)
- Council Synthesis Agent with consistency checker
- Council transient Substrate (discarded post-output)
- Model assignment integration (different models per role at spawn)

**Expected behaviour**: A raw objective string is input. No human touches the system. Within the Council's budget, a valid, consistent StartingField is produced and validated. The system boots from the generated StartingField.

**Exit condition**: Three different raw objectives produce three valid, distinct StartingFields with zero manual intervention. Consistency reports show no unresolved conflicts.

---

### Phase 9 — Hierarchical Civilizations & Sub-Agent Delegation

**Goal**: Enable nested EvoKammer instances for large tasks and sub-agent delegation within lifecycles.

Components built/activated:
- Sub-agent spawning within lifecycle (budget slice delegation)
- Nested Group Instance Manager (outer → inner group lifecycle)
- Budget propagation across hierarchy levels
- Inner instance KB/KG rollup to outer group Substrate

**Expected behaviour**: A Builder agent spawns two sub-agents to handle test generation in parallel. Both sub-agents die when the parent dies. Their outputs roll into the parent's Death Note. An outer-level group successfully decomposes a task by running two inner instances.

**Exit condition**: A two-level hierarchy completes a task where inner instances produce tools and docs that an outer group composes into a final deliverable.

---

### Phase 10 — Full Scale Integration & Production Hardening

**Goal**: Everything running simultaneously on a realistic task with all components active.

Activities:
- Full end-to-end run: raw objective → Genesis Council → multi-group competition → grader groups → check-ins → final scoring → winner KB output.
- Chaos testing: kill random agents mid-lifecycle; verify no state corruption in Substrate.
- Budget ceiling simulation: trigger Watchdog cost ceiling; verify spawning pause and Meta-Planner response.
- Leak oracle audit: verify no source-traceable content over 50 leaks.
- Scoring seal audit: verify no score visible before gate close under 100 check-in simulations.

**Exit condition**: A full task completes end-to-end with no constitutional violations, no score seal breaches, and a measurably higher-quality final output than a baseline single-agent system on the same task.

---

## 6. Acceptance Criteria per Phase

| Phase | Acceptance Criteria |
|---|---|
| 0 | Single agent: spawns, reads KB, writes staging, executes tool, dies, Death Note captured |
| 1 | 3+ agents/generation; KB gains validated doc + tool + Dead Ends entry per generation |
| 2 | Citation chains across 5+ generations; ≥1 Legacy Protocol extracted and injected |
| 3 | 50+ KG facts after 10 generations; zero promoted hard-constraint violations |
| 4 | 3 groups, 5 generations; zero cross-contamination; ≥1 node capture event |
| 5 | 10 leaks pass manual privacy audit; no source-traceable content |
| 6 | Full check-in cycle: blind seal, simultaneous reveal, correct threshold gating |
| 7 | 2 groups, 2 check-ins; correct stage gating; GradingSheet improves between rounds |
| 8 | 3 different objectives → 3 valid StartingFields, zero manual intervention |
| 9 | Sub-agent delegation works; inner instance KB rolls up to outer group correctly |
| 10 | Full end-to-end task: no constitutional violations; output quality exceeds single-agent baseline |

---

## 7. Testing & Validation Strategy

### 7.1 Unit Behaviours

Each component is testable in isolation by mocking its integration contracts:
- Budget Ledger: verify atomic decrements, floor enforcement, retry detection.
- Critic: feed a set of staged submissions with known quality; verify promotion, rejection, dispute rates.
- Leak Oracle: verify output contains no source-traceable content (automated NLP fingerprinting check).
- Scoring Engine: feed known submissions with known test suite; verify deterministic scores.

### 7.2 Isolation Audits

Run after Phase 4 and after Phase 10:
- Attempt to read Group B's KB from within a Group A agent sandbox; verify constitutional block.
- Attempt to write to canonical Substrate directly from agent; verify block.
- Attempt to read sealed score before gate closes; verify block.
- Attempt to extend TTL from within agent context; verify block.

These are adversarial tests, not unit tests. They should be run by a dedicated test suite that simulates a maximally adversarial agent.

### 7.3 Regression Testing

After each phase, re-run the acceptance criteria of all previous phases. The system must not regress.

### 7.4 Performance Baselines

Track per phase and per production run:
- KB density (validated entries) per generation
- KG facts per generation
- Critic acceptance rate
- Tool success rate over time
- Budget efficiency (output quality / budget consumed) per role
- Wall-clock time per generation

These baselines allow detection of systemic degradation over time.

### 7.5 Qualitative Civilisational Health Metrics

After each full run, evaluate:
- Does the Dead Ends Registry grow meaningfully? (Are agents discovering and documenting new failures?)
- Do Legacy Protocols produce measurably more efficient agents? (Compare fitness scores of inheritor vs. non-inheritor agents of the same role.)
- Does the KG's citation graph show meaningful structure? (Not all facts in isolation — rich inter-connections.)
- Do competing groups diverge in approach? (Measure KB semantic distance between groups at generation 10.)

---

## 8. Risk Register

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Knowledge Base pollution (hallucinated facts promoted) | Medium | Critical | KG constraint checks; Critic ensemble; trust score decay; adversarial Critic testing |
| Constitutional law bypass via sophisticated prompt injection | Low | Critical | Syscall-level enforcement; web content sanitisation proxy; adversarial agent test suite |
| Monoculture collapse (all groups converge to same approach) | Medium | High | Ring topology; migration rate ceiling; Outlier agents; model diversity; asymmetric profiles |
| Score seal breach (group sees competitor score before gate closes) | Low | High | Scoring Engine fully isolated from agent network; signed blind seal protocol; audit log |
| Genesis Council produces inconsistent StartingField | Medium | High | Synthesis Agent consistency checker; automatic re-run on failure; max 3 re-runs before halt |
| Budget Ledger race condition (concurrent agent actions bypass zero) | Low | High | Atomic decrement implementation; floor safety margin; load testing under concurrent agents |
| Grader Group KB pollution mirrors main group failure modes | Medium | Medium | Grader Groups on different model family; Grader KBs are Substrate-isolated; failure telemetry anonymised |
| Meta-Planner accumulates persistent bias over multiple triggers | Medium | Medium | Hard TTL on each Meta-Planner; no persistent context between invocations; read-only access to full logs |
| Differential privacy leak transform strips too much signal | Medium | Medium | Calibrate with test leaks; measure receiving group's uptake rate; adjust transform strictness |
| Hierarchical instance budget exhaustion cascades upward | Low | Medium | Budget propagation has mandatory safety margin at each level; cascade kill is acceptable and expected |

---

## 9. Operational Considerations

### 9.1 Run Lifecycle

```text
1. Input: raw objective string
2. Genesis Council fires (estimated: 10–20 min, 6–9 council agents)
3. StartingField validated and sealed
4. Competing group instances initialised with isolated Substrates
5. Grader Group instances initialised
6. Generation loop begins (all groups in parallel)
7. Watchdog monitors continuously
8. Check-in gates open/close per generation schedule
9. Progressive Disclosure Engine gates stage advances
10. Final submission gate opens when all groups pass final check-in or ceiling reached
11. Scoring Engine runs final evaluation
12. Winner KB + KG archived as CompletedTaskArchive
13. All group KBs merged (anonymised) into Meta-Archive for Council learning
14. Run cost report emitted
```

### 9.2 Run Configuration

Every run requires at minimum:
- Raw objective string
- Global cost budget ceiling (hard cap in USD equivalent)
- Global generation ceiling (max generations before forced final submission)
- Model assignment overrides (optional; defaults from StartingField)

### 9.3 Meta-Archive & Cross-Task Learning

After each completed run, the following are persisted to the Meta-Archive:
- Winning group's canonical KB and KG
- All groups' Dead Ends Registries (merged, deduplicated)
- Genesis Council StartingField that was used
- Final Scoring Engine results and GradingSheets
- Summary fitness scores across roles

On subsequent runs, the Genesis Council's Prompt Architect, Incentive Designer, and Ecologist receive relevant past Meta-Archive entries as reference material. Over many runs, the Council's initialisation quality improves without any architectural change.

### 9.4 Observability

A read-only dashboard should expose:
- Per-group: KB density, KG fact count, current resource map holdings, generation number, estimated budget remaining
- Global: total cost, generation timeline, check-in status, Watchdog active signals
- Per-agent (post-mortem only): role, budget consumed, staging acceptance rate, fitness score (when finalised)

No dashboard exposure of: agent context windows, competitor group details, sealed check-in scores before gate closes, or raw LLM call contents.

---

*EvoKammer Implementation Plan v1.0 — generated 2026-03-19*
*Phase 10 represents full production readiness. Phase 1 represents a meaningful first POC.*
