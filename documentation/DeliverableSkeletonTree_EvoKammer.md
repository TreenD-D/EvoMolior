# Design and Implementation Plan for a Deliverable Skeleton Tree in EvoKammer

## 1. Context and Motivation

EvoKammer currently has a rich substrate architecture (KB, KG, Tool Registry, Work-in-Progress Registry) and a sophisticated multi-group, multi-generation orchestration model. However, it lacks an explicit representation of the evolving **deliverable** itself (documents, tools, repos, multi-artifact bundles) as a first-class, structured, persistent object. This creates a gap between civilisational knowledge accumulation and the final artefacts produced at run end.[^1]

The Deliverable Skeleton Tree (DST) addresses this gap by introducing a persistent, graded, compact representation of the deliverable’s structure and state over time. It is distinct from the KB/KG, but tightly integrated with them.

This document specifies a concrete design and implementation roadmap for the DST, with emphasis on:

- Supporting **multiple deliverable types** (tools, repos, HTML bundles, documents)
- Allowing **groups to compound their deliverables across generations**
- Capturing **grader-driven criteria and increasing complexity** over rounds
- Keeping the DST **compact and context-portable**, so it can be fully loaded into an LLM context


## 2. Design Goals and Non-Goals

### 2.1 Design Goals

1. **Deliverable-first abstraction**: Represent the evolving final output as a structured tree, separate from substrate internals.
2. **Type-agnostic nodes**: A node can represent any unit of the deliverable (file, section, API, module, dataset, test suite).
3. **Multi-generation compounding**: Each node tracks generations, agent chains, and trust evolution.
4. **Grader-driven refinement**: Grading results and evolving rubrics directly update node-level metadata and `open_gaps`.
5. **Compactness**: The full DST must always fit into an LLM context window as a summarised tree.
6. **Isolation-compatible**: Respect existing Tier boundaries and Substrate isolation per group.[^1]

### 2.2 Non-Goals

1. **Replacing the KB/KG**: The DST is a thin structural layer referencing KB/KG artifacts; it does not store content.
2. **Implementing a full VCS**: Fine-grained diffing, branching, and merging are delegated to underlying tools (e.g., git); DST tracks semantic, not textual, versioning.
3. **Cross-group deliverable merging**: DST instances remain per-group; cross-group influence is via leaks and scoring, not direct sharing.


## 3. Conceptual Model

### 3.1 Core Concept: Deliverable Skeleton Tree

Per group, the DST is a **rooted tree** whose nodes correspond to semantic parts of the deliverable:

- For a repo: root → modules → files → export points
- For a document corpus: root → sections → subsections → figures/tables
- For a product: root → services → APIs → endpoints → tests

Each node encodes:

- Its **semantic role** (what part of the deliverable it is)
- Its **current artefact reference** (KB doc, tool, repo snapshot, etc.)
- Its **grade history** and **open gaps** from Graders and the Scoring Engine
- Its **state** (`IN_PROGRESS`, `COMPLETE`, `CONTESTED`, `STUB`, `DEAD_END`)

The DST is stored in the group’s Persistent Substrate alongside the KB/KG.[^1]


### 3.2 Node Schema

A minimal, technology-agnostic schema (e.g., stored as a table or document collection):

```json
{
  "node_id": "dst:section:medication-evidence",
  "group_id": "group:A",
  "parent_id": "dst:root",
  "children": ["dst:section:stimulants", "dst:section:non-stimulants"],

  "node_type": "document_section | source_file | tool | test_suite | api_spec | artifact_bundle | other",
  "title": "Medication evidence",
  "path_hint": "doc/adhd_treatment/medication.md#evidence",

  "status": "IN_PROGRESS | COMPLETE | CONTESTED | STUB | DEAD_END",

  "current_artifact_ref": "kb:doc:412",
  "artifact_version": 7,
  "artifact_type": "KB_DOC | TOOL | REPO_SNAPSHOT | TEST_SUITE | OTHER",

  "owning_generation": 5,
  "owning_agent_id": "researcher:gen5:a2f",

  "trust_score": 0.87,
  "grader_complexity_level": 3,

  "grading_history": [
    {
      "checkin_id": "ci:3",
      "dimension_scores": {"depth": 0.9, "accuracy": 0.85, "coverage": 0.7},
      "grader_ids": ["grader:G1", "grader:G2"],
      "rubric_version": "rubric:v3",
      "grader_notes": [
        "Lacks effect size quantification",
        "Adult longitudinal data not explicitly discussed"
      ],
      "passed_threshold": true
    }
  ],

  "open_gaps": [
    "Quantify effect sizes for stimulants vs non-stimulants",
    "Integrate adult longitudinal outcomes (>= 2 years)"
  ],

  "dead_end_refs": ["dead:22", "dead:31"],
  "citation_agent_chain": ["researcher:gen1:x1a", "critic:gen2:b3c", "researcher:gen5:a2f"],

  "created_at_generation": 1,
  "updated_at_generation": 5
}
```

Key design choices:

- `current_artifact_ref` is **the only pointer** to content; no heavy content is stored in the DST.
- `grading_history` holds only recent entries (e.g., last 3 check-ins) to limit size; older entries are archived to KB as separate grader reports.
- `open_gaps` is the primary **agent-facing worklist** for that node.
- `grader_complexity_level` tracks how sophisticated the rubric has become for this node, so the system can adapt agent roles/budgets accordingly.


## 4. Lifecycle and Data Flows

### 4.1 Genesis Council → Initial DST Bootstrap

The Genesis Council currently emits a **knowledge node graph** for the WiP Registry. Extend it to emit a **Deliverable Skeleton Template**:[^1]

- A tree of node templates, each with:
  - `node_id` (deterministic, e.g., path-based)
  - `node_type`
  - `title`
  - `path_hint`
  - `initial_status: STUB`
  - `initial_expected_complexity_level` (from domain design)

The group’s DST is initialised from this template at run start. No content refs are attached yet; all nodes are `STUB` and `trust_score = 0`.

### 4.2 Agents Working on Nodes

Agents continue to work primarily via the Staging Area and KB as defined. The DST is integrated via two mechanisms:[^1]

1. **Task Claim Augmentation**: WiP Registry entries gain an optional `dst_node_ref` field. When the Evolutionary Selector assigns a Researcher/Builder task, it can choose a specific DST node based on `open_gaps`, `status`, and `trust_score`.

2. **Staging Submission Extension**: Staging payloads (already including `wip_task_reference`) gain:

```text
  - dst_node_ref: node_id | null
```

The Critic enforces that:

- If `dst_node_ref` is set, the submission must be relevant to that node’s `node_type` (e.g., a document for a `document_section`, a tool for a `tool` node).

### 4.3 Critic → DST Updates

Critic outcomes are currently `PROMOTE`, `REJECT`, `DISPUTE` and drive promotion into KB/KG/Tool Registry. Extend the Critic pipeline so that on `PROMOTE` with non-null `dst_node_ref`:[^1]

1. Look up the DST node.
2. Compare the **proposed artifact’s quality** to the existing `current_artifact_ref` (if any), using:
   - Trust score of the staged artifact
   - Generation number (prefer newer if similar trust)
3. If better, update DST node:
   - `current_artifact_ref`
   - `artifact_version` (increment)
   - `owning_generation`, `owning_agent_id`
   - `trust_score`
   - `status` → `IN_PROGRESS` or `COMPLETE` depending on threshold crossed

If the Critic explicitly marks a submission as a **documented failure path** (e.g., a Dead End), it updates `dead_end_refs` instead of `current_artifact_ref` and can set `status` to `DEAD_END` if the node itself is unresolvable.

### 4.4 Graders and Scoring Engine → DST Grading Pass

The Scoring Engine currently outputs a `CheckinResult` per group, including `overall_score`, `dimension_scores`, and `failure_telemetry`.[^1]

Extend this to produce a **DST Grading Pass**:

1. At each check-in, the Grader Groups receive anonymised **artifact bundles**. Extend the bundle schema to include **DST node annotations**, so graders can attribute feedback to parts of the deliverable.
2. Graders produce GradingSheets that may include node-level signals (e.g. “auth module tests insufficient”). Extend GradingSheet format to allow optional `dst_node_ref` per rubric dimension.
3. When the Scoring Engine processes GradingSheets and failure telemetry, it synthesises:

```json
{
  "checkin_id": "ci:3",
  "group_id": "group:A",
  "node_updates": [
    {
      "dst_node_ref": "dst:module:auth",
      "dimension_scores": {"correctness": 0.7, "coverage": 0.4},
      "grader_notes": ["Missing negative-path tests", "No rate-limit handling"],
      "rubric_version": "rubric:v3"
    },
    ...
  ]
}
```

4. A **DST Update Service** applies this to each DST node:
   - Append to `grading_history` (truncated to last N entries)
   - Recompute `open_gaps` based on `grader_notes` (e.g., transform notes into structured gaps, possibly via a small LLM tool)
   - Update `grader_complexity_level` based on rubric complexity and historical trend

This closes the loop between Graders and the evolving deliverable structure.

### 4.5 Evolutionary Selector → DST-Aware Role Allocation

The Evolutionary Selector currently reads KB density, staging backlog, tool failure rates, plan registry freshness, and stasis signals.[^1]

Add DST-derived signals:

- Count of nodes per `status` (`STUB`, `IN_PROGRESS`, `COMPLETE`, `CONTESTED`, `DEAD_END`)
- Distribution of `trust_score` across nodes
- Nodes with non-empty `open_gaps`
- Nodes with low scores on critical rubric dimensions (e.g., security, tests)

Use these to:

- Priority-boost agents targeting nodes with:
  - High importance (from Genesis Council or rubric weights)
  - Low trust_score
  - Rich `open_gaps`
- Dynamically adjust role mix, e.g.:
  - Increase Builder/Test roles when many `source_file` or `test_suite` nodes have low coverage
  - Increase Planner/Educator roles when rubric complexity has outpaced current protocols

### 4.6 Final Output Synthesis

At final submission:

1. The winning group’s **DST is the canonical blueprint** of the deliverable.
2. A **Final Synthesiser agent** is given:
   - The entire DST (compact summary view)
   - For each top-level node, the `current_artifact_ref`
   - Optionally, a limited number of underlying artifacts (e.g., key KB docs, top tools)
3. The agent walks the tree and assembles the final output:
   - For a repo: generate a coherent README, architecture overview, and link/file structure from the DST
   - For a document corpus: render sections in delivered order, incorporating grader-informed `open_gaps` resolution
4. Provenance and grading history are embedded as metadata (e.g., machine-readable JSON sidecar, or data attributes in HTML) so future runs or human reviewers can trace which parts required multiple generations.


## 5. Managing Complexity and Evolving Grader Criteria

The user requirement emphasises **increasing complexity and deeper criteria** produced by graders, which should discover and enhance the deliverable node tree.

### 5.1 Grader-Driven Tree Refinement

Grader Groups may discover that the initial skeleton is too coarse or too fine-grained. Extend GradingSheets to allow:

- **Proposed DST refinements**:
  - `SPLIT_NODE(dst_node_ref, child_specs)` → e.g., split `dst:section:methods` into `sampling`, `analysis`, `limitations`
  - `MERGE_NODES([dst_node_refs], new_spec)` → collapse overly granular nodes
  - `RETYPE_NODE(dst_node_ref, new_type)` → e.g., treat something as `artifact_bundle` instead of `document_section`

These proposals go through the **Meta-Judge** and, if accepted, are applied by a **DST Refiner** component:

- Tree structure is updated
- Children and artifacts re-attached
- WiP Registry and task claims are remapped as needed (respecting atomic claim semantics)[^1]

### 5.2 Complexity Levels and Rubric Evolution

Represent rubric depth per node via `grader_complexity_level` and possibly per-dimension scores:

- Start with level 1 (basic: existence/validity).
- As Graders introduce more nuanced criteria (e.g., performance, security, maintainability), increase complexity levels for affected nodes.

Agents can then:

- Adjust their behaviour depending on node complexity level.
- Prefer injecting **Legacy Protocols** tailored to high-complexity nodes (e.g., security-critical modules) when spawning next-generation agents.[^1]

### 5.3 DST-Aware Dead Ends

The Dead Ends Registry in the KB is first-class and never decays. Integrate it with DST:[^1]

- When a Dead End is promoted, link it to the relevant `dst_node_ref`.
- Nodes can be marked `DEAD_END` when Graders and Meta-Planner concur that a design direction is fundamentally flawed.
- Future generations seeing a node with `DEAD_END` status can:
  - Avoid revisiting the same approach.
  - Consider a structural refactor (e.g., splitting/rewriting the module) rather than incremental fixes.


## 6. Data Structures and Storage

### 6.1 Storage Model

Use the same storage backend as the KB/KG for simplicity, but separate logical namespace:

- Table/collection: `deliverable_skeleton_nodes`
- Indexes:
  - `group_id + node_id` (PK)
  - `group_id + parent_id` (tree traversal)
  - `group_id + status`
  - `group_id + trust_score` (range queries)
  - `group_id + has_open_gaps` (boolean)

### 6.2 Compact Representations

To keep the DST context-portable:

- Store full nodes in DB, but expose to agents **pruned views**:
  - Limit `grading_history` to last 3 entries.
  - Limit `open_gaps` to the top K prioritized items (e.g., by severity or rubric weight).
  - Compress `citation_agent_chain` to a small set of representative agents (e.g., earliest, most impactful, latest).
- Provide two representations:
  - **Full DST** for offline analysis and logs.
  - **Summarised DST View** (tree + key fields only) for agent contexts.


## 7. API and Integration Contracts

### 7.1 Agent → DST

Agents never write to DST directly. They:

- Read **Summarised DST Views** filtered by:
  - Assigned `dst_node_ref`
  - Node types relevant to role
- Attach `dst_node_ref` to staging submissions.

### 7.2 Critic → DST

Critic pipeline API addition (internal service call):

```text
updateDstNodeOnPromotion(
  group_id,
  dst_node_ref,
  artifact_ref,
  artifact_type,
  trust_score,
  owning_generation,
  owning_agent_id
)
```

This call is **idempotent** and compares existing vs new `trust_score` to decide whether to update.

### 7.3 Scoring Engine / graders → DST

Scoring Engine emits a DST grading event per check-in:

```text
DstGradingEvent {
  checkin_id,
  group_id,
  node_updates: [DstNodeUpdate]
}

DstNodeUpdate {
  dst_node_ref,
  dimension_scores,
  grader_notes,
  rubric_version,
  grader_ids
}
```

DST Update Service:

- Appends to `grading_history`.
- Computes `open_gaps` via simple rules or a small LLM-based summariser.
- Updates `grader_complexity_level`.

### 7.4 Rubric & DST Co-evolution

Meta-Judge may issue **rubric-level signals** that reference DST node types, enabling global patterns like:

- “All `api_spec` nodes now require latency SLOs.”
- “All `tool` nodes must include integrated tests.”

These signals can be stored in a `dst_rubric_policies` table, which the DST Update Service uses when updating nodes.


## 8. Implementation Phasing

### Phase 2.5 — Initial DST Skeleton

Insert between current Phase 2 and Phase 3 in the EvoKammer implementation plan.[^1]

**Goal**: Introduce per-group DST with static skeleton and Critic-driven updates.

**Work items**:

- Extend Genesis Council to emit Deliverable Skeleton Template.
- Implement `deliverable_skeleton_nodes` storage and CRUD.
- Add `dst_node_ref` to WiP Registry entries and Staging submissions.
- Extend Critic pipeline to update DST on `PROMOTE`.
- Provide Summarised DST View API for agents.

**Exit criteria**:

- After 5 generations for a single group, the DST has non-STUB nodes with `current_artifact_ref` and `trust_score > 0`.
- A prototype Final Synthesiser can reconstruct a coherent deliverable outline purely from the DST and referenced artifacts.

### Phase 4.5 — DST Grading Integration

After multi-group competition (Phase 4), before full DP leaks (Phase 5).[^1]

**Work items**:

- Extend GradingSheet schema with optional `dst_node_ref` per dimension.
- Implement DstGradingEvent emission from Scoring Engine.
- Build DST Update Service to apply grading events.
- Add DST-aware signals to Evolutionary Selector.

**Exit criteria**:

- After 2 check-ins, multiple DST nodes per group have non-empty `grading_history` and `open_gaps`.
- Agents are observed claiming WiP tasks guided by DST `open_gaps`, not just KB density.

### Phase 7.5 — DST/Grader Co-Evolution

Between Progressive Disclosure (Phase 7) and Genesis Council automation (Phase 8).[^1]

**Work items**:

- Extend GradingSheets to propose DST refinements (split/merge/retype).
- Implement Meta-Judge-mediated DST Refiner.
- Implement `grader_complexity_level` and rubric-based policies.

**Exit criteria**:

- At least one run where Graders propose and the system accepts a DST split/merge that measurably improves final deliverable quality.
- Nodes with higher complexity levels exhibit richer Legacy Protocols and more specialised agents.


## 9. Risks and Mitigations

### 9.1 DST Bloat

**Risk**: DST becomes too large to fit into context.

**Mitigations**:

- Hard limits on `grading_history`, `open_gaps`, and `citation_agent_chain` per node.
- Summarised DST View for context usage.
- Aggressive archiving of old history to KB.

### 9.2 Inconsistent Node References

**Risk**: Structural edits (split/merge) break `dst_node_ref` references in WiP Registry, Dead Ends, or grader telemetry.

**Mitigations**:

- Use deterministic `node_id` and maintain a mapping log for migrations.
- All structural changes go through DST Refiner, which updates all references transactionally.

### 9.3 Overfitting to Grader Preferences

**Risk**: DST becomes too tightly tailored to current Grader Groups’ biases.

**Mitigations**:

- Use multiple independent Grader Groups with a Meta-Judge consensus step (already planned).[^1]
- Limit structural changes to cases with strong, multi-grader agreement.
- Keep an audit trail of structural changes and their effect on final scores.

### 9.4 Agent Complexity Overload

**Risk**: Agents struggle to use DST effectively if it exposes too much detail.

**Mitigations**:

- Role-specific DST views (e.g., Builders see only `source_file` and `test_suite` nodes).
- Use Planner/Educator roles to summarise DST `open_gaps` into actionable playbooks.
- Inject DST-aware Legacy Protocols into specialised roles.


## 10. Summary

The Deliverable Skeleton Tree introduces a critical missing layer in EvoKammer: a compact, structured, graded representation of the evolving deliverable, separate from but integrated with the KB/KG. Implementing DST will:

- Allow each group to compound work on complex, multi-artifact deliverables across generations.
- Make grader feedback and rubric evolution directly actionable at the part-of-deliverable level.
- Provide a context-portable skeleton that final synthesis agents can use to faithfully reflect multi-generation, multi-group work.

The implementation can be phased incrementally, starting with a static skeleton and Critic updates, then integrating grading, and finally enabling dynamic DST refinement driven by evolving grader criteria.

---

## References

1. [EvoKammer_Implementation_Plan_v1.0-2.md](https://ppl-ai-file-upload.s3.amazonaws.com/web/direct-files/attachments/130272311/3d38f1ff-62fa-423b-9e83-d0f47698281c/EvoKammer_Implementation_Plan_v1.0-2.md?AWSAccessKeyId=ASIA2F3EMEYEXPXQVSH5&Signature=6SmAukJqALqTKap3YJo1AU355Vs%3D&x-amz-security-token=IQoJb3JpZ2luX2VjEK3%2F%2F%2F%2F%2F%2F%2F%2F%2F%2FwEaCXVzLWVhc3QtMSJHMEUCIHnJ0dngFlyjYvqBZYaNcb8MTg7ineeYZW%2FWyQ8Qu2h2AiEAtwkdoJA2%2Bc8eMoZYLtHKybiNu3WIrtJDEGWhErI94vcq8wQIdRABGgw2OTk3NTMzMDk3MDUiDDvoyQC%2FP%2BYD0nK4KirQBKAHGQB001CyQUCOmJKTYZfaYBS5L37DheZUPU548Gj4LeI9tb1EFxxiqEE6BBaVylm6uvxBzZUgVlRMk%2BJH8fngFvW5lTFY1z89VnfHIF%2F8NnWCHBdtZn%2FIfK3XtlqapIHTvwy7vAg%2FoKS16KTNP82ZLmLbGN62UkCuttkOan%2Ff%2FD1iyIgadU4GWiYwjTp29LgmMFJwOqK0KTgsB0mapXHzJr61p9uSTjiMe3us6KhZ8dncxza%2F3uruquBS4mCiWsA6zn1wCTXAAGhpbwBPBmUvdI5D45d0DA5JlSdlojojfcu8jEzurFI5bnZ0Iay9RkEkmxSYe%2F37EQJnQYloCjsF72QG0duQv5fcq8W%2BvepTuG8tqrei0JQxY2Aj88ItN%2B3cVuFQiC0kdoSHBf5nUwqgTIPMwVp6l5J%2BfrkIkJjHHBfQrnO5RaFnIHpp17gfz9WsQlo4MLXVoToNBVRNgrCALNkJ6ucSZSSjtW6l6%2BdiaUoANtpoO756lhGwlWBWWrDxVo8lmeO2sg18bcfLQygdMvlhF20Wgvm1GsKNR96x7TMvL%2FwWtWrkB2eYto85D5FtIm0Zh%2FXFr5z%2BeSPJZMbOcNwbMFE9atJUTCptLYFfpf2%2FxWCyO7kTYrvTneSe9gy0K55qOaVpgCPfI%2F53naZlWYQY2HeavWc4PfPiWWRA0f10Zgg62KV1z5%2FYSmZMGPSVx5S3VcFoaHMuV2EhbXqdUQ0xIkTxBJ%2B9%2FBEGBhkkmnF6tUW5EcSyDj3lneWxvzfOL549NEYmUBxaaU%2FcOO4whdqM0QY6mAEX%2F2240sc8InEsS9zcToQUcPLhoIl0yKELGjiFwtFgzTkVyho2EOmtdHLkqySQJTF0eKApKNrpvB804q1NyT0Hz2JtKwWQDRPVVMaZJLsTd%2BwWJSJrbf99tdWoo0cJHnSGTLyJVH128qUPNXwv8ml%2FeP8CsMTd%2BdC1PeBy66yhQ2lESdxBnTOQ8b8hJJNoLA%2FJNC7UizP7UQ%3D%3D&Expires=1780693720) - A behaviour-driven implementation roadmap for the full EvoKammer civilisational multi-agent system. ...

