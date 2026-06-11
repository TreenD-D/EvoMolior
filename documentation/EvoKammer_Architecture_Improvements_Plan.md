# Neural-Architecture-Inspired Topologies for EvoKammer

> Applying neural network and human team topologies to EvoKammer, where **each node is a small Evo run**, and evaluating options with implementation sketches, upsides/downsides, and verification signals.

---

## 0. Design Frame

We treat EvoKammer as a **graph of small Evo runs** ("run-nodes"):

- Each run-node has its own: StartingField, KB/KG, tools, agents, TTL, and deliverable slice.
- Edges between run-nodes correspond to **structured information flows** (Dispatches, Leaks, DST references, Meta-Archive pointers).
- We borrow from:
  - **Neural architectures** (feedforward, recurrent, attention, MoE, GNN, GAN)
  - **Human collective intelligence research** (team composition, communication patterns, coordination topologies).[web:103][web:109][web:112]

Goal: define several **candidate topologies** and concrete experiments that can show whether they provide leverage over a single frontier model agent for EvoKammer-style tasks.

---

## 1. Feedforward Layers of Evo Runs (MLP-equivalent)

### 1.1 Topology

- Run-nodes organised in **layers** L1 → L2 → L3.
- Each node in layer L takes as input:
  - the raw objective (residual channel), and
  - outputs from nodes in L-1.
- No feedback loops—purely acyclic.

Example for an HTML5 article task:

- L1: literature mining runs (different sub-domains).
- L2: cross-domain synthesis runs (each taking 2–3 L1 outputs).
- L3: final article synthesis run.

### 1.2 Implementation Sketch

- **StartingFields**:
  - L1: domain-specific research prompts and KB-seeding objectives.
  - L2: objectives that require combining 2+ L1 perspectives.
  - L3: objective that targets a specific deliverable (DST root → article).[file:62]
- **Information flow**:
  - L1 outputs are stored in Meta-Archive + tagged by domain.
  - L2 StartingField includes references to selected L1 outputs as read-only initial KB.
  - L3 sees only L2 outputs and the original StartingField.

### 1.3 Upsides

- Simple to reason about; aligns with existing DST idea: deeper layers = higher abstraction.
- Easy to bound cost: number of layers × per-run budget.
- Works well for problems with a natural *analysis → synthesis* progression.

### 1.4 Downsides

- **Error propagation**: early hallucinations (e.g., fake citations) propagate forward and get amplified, as seen in the DMCI-TWD article.[file:100]
- No built-in correction loop—similar to plain MLP vs. residual networks.

### 1.5 Verification Signals & Metrics

- **Layer-wise quality gain**:
  - For each layer, run independent human or frontier-model grading on a sample of node outputs.
  - Expectation: L2 > L1 and L3 > L2 on synthesis metrics.
- **Error inheritance rate**:
  - Trace specific incorrect claims from L1 through L3.
  - Metric: fraction of L3 claims that can be traced to erroneous L1 claims.
- **Comparative baseline**:
  - Single-run EvoKammer vs. 3-layer feedforward stack vs. single frontier agent.

---

## 2. Recurrent Self-Improvement Loop (RNN/LSTM-equivalent)

### 2.1 Topology

- Sequence of run-nodes R1, R2, ..., Rk on the **same objective**.
- Output of R_t is fed as KB seed into R_{t+1}.
- Optional: Meta-Archive acts as long-term state; each run has its own short-term KB.[file:62]

### 2.2 Implementation Sketch

- Use the already designed **self-improvement StartingField** for each R_t.
- After each run:
  - Orchestrator decides whether to accept patches (per Overseer-Kernel rules).[file:62]
  - Accepted changes become the new baseline repo + KB for R_{t+1}.
- Add explicit **forget gates**:
  - Age-based trust decay + hard culls of low-trust entries.
  - DST-level pruning of unproductive branches.

### 2.3 Upsides

- Directly implements recursive introspection: multiple attempts, each aware of previous failures.[web:77]
- Natural match to EvoKammer's self-improvement architecture.

### 2.4 Downsides

- High risk of **mode collapse**: converges to a local optimum and keeps making tiny tweaks.
- Without strong external supervision, may reinforce hallucinations (see fabricated stats in DMCI-TWD article).[file:100]

### 2.5 Verification Signals & Metrics

- **Improvement curve**:
  - Measure deliverable quality (grader score) vs. iteration t.
  - Expectation: improvement for first few steps, then plateau.
- **Diversity decay**:
  - Compare semantic diversity (embedding distance) across R_t outputs.
  - Monitor whether diversity collapses as t increases.
- **Correction rate**:
  - Track how many earlier errors are explicitly corrected in later runs.

---

## 3. Attention-Style Routing Between Runs (Transformer-equivalent)

### 3.1 Topology

- A set of run-nodes in a layer; each node attends to outputs of all previous nodes.
- **Attention weights** determine which prior outputs matter.
- No strict layering: "time" dimension is generation index; "heads" are different run roles.

### 3.2 Implementation Sketch

- Define a **routing agent** per run that:
  - reads summaries/metadata of prior runs’ outputs (vector embeddings + trust scores),
  - selects top-k prior artifacts as **context** for the current run.
- Attention score approximations:
  - similarity in embedding space (KB vector search),
  - trust score weighting (like attention logits),
  - optional cheap model to compute query–key compatibility.[web:110][web:111]

### 3.3 Upsides

- Proven powerful in DMoA and MoA research: adaptive collaboration of multiple agents beats single frontier models.[web:108][web:110]
- Can implement **cross-domain reasoning** easily: runs in one domain attend to another.

### 3.4 Downsides

- Implementation complexity: need a scalable and interpretable routing/attention mechanism.
- Risk of attention collapse: if trust scores are miscalibrated, the system over-attends to a few early runs.

### 3.5 Verification Signals & Metrics

- **Ablation study**:
  - Run with and without attention routing. Compare deliverable scores.
- **Attention diversity**:
  - Distribution of attention weights over prior runs.
  - Healthy pattern: multiple prior runs contribute; unhealthy: one run dominates.
- **Attribution trail**:
  - For each final claim, trace which prior runs contributed evidence.

---

## 4. MoE / MoA with LLM Router (Mixture-of-Experts-equivalent)

### 4.1 Topology

- A pool of **expert run-nodes**, each specialised:
  - domain (regulatory, math, org science, etc.),
  - function (literature survey, DST design, code harness, etc.).
- A **router** decides which experts to activate for a given sub-task.

### 4.2 Implementation Sketch

- Predefine a library of **StartingField templates** as experts.
- Implement router options:
  1. **Rule-based router**: uses task metadata (domain tags, difficulty) + simple thresholds.
  2. **LLM router** (LLMoE-style): a cheap model reads the task description and selects experts.[web:102][web:105]
  3. **DMoA-style differentiable router**: treat routing as a learned policy optimised via implicit rewards (grader scores, cost efficiency).[web:110][web:101]
- Each expert run-node is itself a small EvoKammer run.

### 4.3 Upsides

- Strong empirical backing: MoA/DMoA with open models matching or beating GPT-4 Omni/GPT-5.2.
- Cost-efficient: only a subset of experts is active per task.
- Clean mental model: EvoKammer becomes a **catalogue of expert civilisations**.

### 4.4 Downsides

- Requires building and maintaining a high-quality **expert library**.
- Learned routers can overfit to training tasks; rule-based routers may underperform on edge cases.

### 4.5 Verification Signals & Metrics

- **Expert activation entropy**:
  - Measure how often each expert is called.
  - Healthy system: not too uniform, not dominated by one expert.
- **Routing accuracy / regret**:
  - For some tasks, run multiple experts in parallel and simulate alternative routing decisions.
  - Check whether router-assigned expert actually outperforms others.
- **Cost vs performance frontier**:
  - Compare MoE/MoA cluster vs. single best expert vs. single frontier model.

---

## 5. Graph of Evo Runs (GNN-equivalent)

### 5.1 Topology

- Run-nodes are vertices in a graph; edges represent knowledge flows.
- Edge weights correspond to **trust, similarity, or complementary expertise**.
- Multiple message-passing rounds: each node aggregates messages from neighbours, then runs another generation.

### 5.2 Implementation Sketch

- Start from EvoKammer's **ring topology** and Resource Map / Leak system.[file:62]
- Generalise to:
  - dynamic graph: edges added/removed based on run performance, similarity, or task stage,
  - per-edge policies: some edges carry only Dead Ends, others only positive protocols.
- Message passing:
  - Each run summarises its KB deltas and DST node updates into a `message`.
  - Neighbour runs ingest messages via a **message-to-KB** adapter tool.

### 5.3 Upsides

- Closely aligned with EvoKammer's intended competitive civilisation metaphor.[file:62]
- Supports rich structures: cliques, hubs, small-world networks.[web:107]

### 5.4 Downsides

- Harder to debug: feedback loops and cycles can create non-obvious failure modes.
- Need careful isolation: cross-run leakage must respect constitutional boundaries.[file:62]

### 5.5 Verification Signals & Metrics

- **Graph topology vs. performance**:
  - Test ring vs. star vs. small-world configurations.
  - Measure which topology yields best quality/cost trade-off.
- **Edge pruning effect**:
  - Remove low-trust edges and see if performance improves (less noise).
- **Community detection**:
  - Do high-performing subgraphs emerge? Use clustering on run performance and message traffic.

---

## 6. Adversarial Generative / Discriminative Runs (GAN-equivalent)

### 6.1 Topology

- Two families of run-nodes:
  - **Generators**: propose deliverables or substrate changes.
  - **Discriminators**: specialised critic/grader runs that try to detect hallucinations, fake citations, weak analogies.
- They co-evolve via iterative rounds.

### 6.2 Implementation Sketch

- Generators: normal EvoKammer groups aimed at delivering DST nodes.
- Discriminators:
  - run on candidate outputs only,
  - have access to external tools (search, citation verifiers),
  - feed back **structured failure reasons** into Dead Ends + Legacy Protocols.
- Overseer-Kernel sets a **reward signal** based on discriminator performance: better discriminators get more budget.

### 6.3 Upsides

- Directly tackles observed weaknesses: hallucination and fake quantification in the DMCI-TWD article.[file:100]
- Exploits adversarial training ideas that have worked for code/math (verifiable domains).[web:18][web:113]

### 6.4 Downsides

- Training instability: too-strong discriminators can suppress exploration; too-weak ones give no value.
- Hard to define ground truth in open-ended conceptual domains.

### 6.5 Verification Signals & Metrics

- **Hallucination rate**:
  - Use a ground-truth subset where sources are known.
  - Measure decrease in hallucinations over generator generations.
- **Discriminator calibration**:
  - Discriminator confidence vs. actual correctness (Brier score).

---

## 7. Human Collective Intelligence Patterns as Constraints

Neural analogies aside, EvoKammer is fundamentally about **group intelligence**. Human group research provides key design hints.[web:103][web:109][web:112][web:115]

### 7.1 Equal Turn-Taking & Communication Volume

Findings:

- Collective intelligence is strongly predicted by **even distribution of conversational turn-taking**, not by average or max individual IQ.[web:109][web:106]
- Groups where a few members dominate do worse.

Implementation in EvoKammer:

- **Budget allocation constraints**:
  - Enforce that, within a run-node, no single agent role consumes > X% of total budget (e.g. Builders can't dominate Critics/Researchers).
- **Run-node communication quotas**:
  - In a GNN topology, ensure message volume per node is roughly balanced.
  - Penalise hubs that send but rarely receive, or vice versa.

Verification Signals:

- **Conversation entropy**:
  - Shannon entropy of token usage per agent/role.
  - Expect higher collective performance with higher entropy (more equal participation).

### 7.2 Social Sensitivity / Perspective Taking

Findings:

- Social sensitivity (ability to infer others' mental states) predicts group c-factor (collective intelligence) more than raw IQ; correlated with higher proportion of women in the group, but fundamentally about perspective-taking.[web:109][web:112]

Implementation in EvoKammer:

- Introduce **Perspective-Taker roles**:
  - Agents whose sole job is to read other agents' Death Notes and surface misalignments, blind spots, and disagreements.
- Add **"theory-of-mind" rubrics** to Grader groups:
  - Score whether deliverables correctly model stakeholder perspectives.

Verification Signals:

- **Perspective coverage metric**:
  - Count distinct stakeholder perspectives referenced in a deliverable.
  - Correlate with human-graded quality.

### 7.3 Moderate Diversity with Shared Norms

Findings:

- Teams with **moderate diversity of cognitive styles** outperform highly homogeneous and extremely heterogeneous ones; too much diversity without shared norms hurts coordination.[web:112][web:115]

Implementation in EvoKammer:

- Enforce **bounded heterogeneity** in each run-node:
  - Guarantee at least 2–3 different role types (Researcher, Builder, Outlier, Critic) but cap number of Outliers.
- Use **Legacy Protocols and Constitution** as "shared norms" to keep behaviour within guardrails.[file:62]

Verification Signals:

- **Diversity–performance curve**:
  - Vary number of role types per run-node and measure performance.
  - Expect an inverted-U shape: too few roles → low performance; too many roles → coordination loss.

### 7.4 Clear Task Decomposition & Role Clarity

Findings:

- Human teams perform best when tasks are clearly decomposed and each member knows "who is responsible for what", with coordination mechanisms explicit.[web:103][web:112]

Implementation in EvoKammer:

- Lean on **knowledge node graph + DST** for explicit task decomposition.[file:62]
- Ensure each node is assigned a primary **"owning" role** per run (e.g., Researcher for evidence-gathering nodes, Builder for design nodes).

Verification Signals:

- **Ownership clarity**:
  - Measure how many DST nodes have ambiguous or changing ownership.
  - Hypothesis: clearer ownership correlates with fewer conflicts and higher-quality outputs.

---

## 8. Recommended Experimental Roadmap

1. **Phase 1: Residual & MoE Baseline**
   - Implement residual injection of original StartingField into every run-node.
   - Stand up a small expert library and a rule-based router; compare with single expert and single frontier model.

2. **Phase 2: Human-Pattern Constraints**
   - Add budget/communication entropy constraints (equal participation),
   - Add Perspective-Taker agents and perspective coverage metrics.

3. **Phase 3: GNN & Attention Variants**
   - Expand from ring topology to simple alternative graphs (star, small-world),
   - Implement attention-style routing on top of the graph.

4. **Phase 4: Adversarial Critics**
   - Spin up discriminator-style runs specialised in fact-checking and analogy verification,
   - Use them to reduce hallucination and fake quantification rates.

At each stage, compare EvoKammer cluster performance against a carefully-instructed single frontier model on identical tasks, using shared grader rubrics and human spot checks.
