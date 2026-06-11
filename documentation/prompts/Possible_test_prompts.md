# EvoKammer Test Run Prompts — Novel HTML5 Article Output

> Small, focused runs (≤ 3 groups, ≤ 5 generations) whose sole deliverable is a **self-contained,
> richly designed HTML5 article** synthesising multiple research threads into something genuinely novel.
> All prompts are **constraint-framed**, as required by EvoKammer's Genesis Council input format.

***

## How These Prompts Work Inside EvoKammer

Each prompt below is the **raw objective string** fed to Genesis Council.  
Council then produces a StartingField automatically:

- Task Anatomist decomposes it into a knowledge node graph.
- KG Architect defines hard constraints (e.g. "no claim without ≥ 2 independent sources") and soft
  constraints (e.g. "prioritise synthesis over summary").
- Model Ecologist assigns cheap fast models to Builders / Researchers (DeepSeek V4 Flash,
  Gemini 2.0 Flash-Lite) and a slightly stronger model to Critics and Graders (GPT-OSS-120B or
  Gemini 2.0 Flash).
- Synthesis Agent locks a sealed judge test suite including: HTML5 validity, citation integrity,
  novelty scoring rubric, and readability checks.

The deliverable type is always declared as `html5_article` in the StartingField's
`deliverable_type` field. The Deliverable Skeleton Tree (DST) root node is `article`, with
children for `introduction`, `body_sections[]`, `novel_synthesis_section`, `conclusion`,
`references`.

***

## Prompt Variant 1 — Cognitive Architecture × Swarm Intelligence

### Category
Compounding research: cognitive science + multi-agent systems → applied framework

### Objective String (feed directly to Genesis Council)

```
DOMAIN: Cognitive architecture and distributed swarm intelligence.

PROBLEM SPACE: Current AI assistants treat cognition as a single-agent process. Research in
distributed cognition (Hutchins 1995), stigmergy-based problem solving (Dorigo et al.), and
modern multi-agent LLM systems (MoA, DMoA 2025) each study the same phenomenon from
disconnected angles. No unified operational framework exists that maps biological swarm
decision-making primitives onto practical multi-LLM orchestration patterns.

CONSTRAINTS:
  - Hard: Every claim about biological systems must be grounded by at least one peer-reviewed
    source stored in the group KB before it is promoted to the KG.
  - Hard: Every claim about LLM systems must reference a system or paper published after 2023.
  - Hard: The article must identify at least three places where the existing literature is
    contradictory or silent — these gaps become the novel contribution candidates.
  - Soft: Prefer synthesis over survey. Do not write an annotated bibliography. Write an article
    that could not have been written by reading a single source.
  - Soft: The novel synthesis section must propose at least one testable hypothesis that is not
    present in any cited source.

DELIVERABLE: A single self-contained HTML5 article. Estimated reading time 8–12 minutes.
The article must render correctly without external dependencies (inline CSS, no external JS).
Structure: abstract, 4–6 body sections (each compounding the previous), a synthesis section
containing the novel contribution, and a references section with full citations.

OUTPUT QUALITY BAR: A domain expert reading this article should learn something they could not
have learned from any single cited source.
```

### Suggested StartingField Hints (include in run config, not in Genesis Council input)

```yaml
deliverable_type: html5_article
max_groups: 2
max_generations: 4
budget_per_group_usd: 0.80
model_assignments:
  researcher: deepseek-v4-flash
  builder: gemini-2.0-flash-lite
  critic: gpt-oss-120b
  grader: gemini-2.0-flash
checkin_schedule: [generation_2, generation_4]
```

***

## Prompt Variant 2 — Evolutionary Pressure × Language Model Training Dynamics

### Category
Compounding research: evolutionary biology + ML training theory → novel analogy with testable predictions

### Objective String

```
DOMAIN: Evolutionary biology (selection pressure, fitness landscapes, genetic drift) and
language model training dynamics (loss landscapes, emergent capabilities, phase transitions).

PROBLEM SPACE: Biological evolution and gradient-based learning are both search processes over
high-dimensional fitness/loss landscapes. Research on evolutionary dynamics (Wright, Kauffman,
Eigen) and on LLM training phase transitions (Wei et al. 2022, emergent abilities literature
2023–2025) develops in parallel with little cross-pollination. Several findings in one field
predict phenomena already observed in the other but not yet explained by it.

CONSTRAINTS:
  - Hard: The article must identify at least two analogies that are not merely metaphorical —
    they must be structurally isomorphic (same mathematical relationship in both domains).
  - Hard: At least one of these isomorphisms must yield a novel prediction about LLM training
    that is not currently in the LLM literature but follows from established evolutionary theory.
  - Hard: All analogies must be falsifiable — the article must describe what evidence would
    break each analogy.
  - Hard: No analogy may be present verbatim in any cited source. If a source already makes the
    same analogy, that analogy is marked "established" and does not count toward the novel
    contribution.
  - Soft: Prioritise mathematical/structural depth over intuitive accessibility in body sections.
    The conclusion should translate findings back to accessible language.
  - Soft: The HTML output should use clean typographic hierarchy. Equations may be rendered as
    plain text (no MathJax dependency).

DELIVERABLE: A self-contained HTML5 article. Estimated reading time 10–15 minutes.
Structure: framing problem, domain A survey (evolutionary biology primitives), domain B survey
(LLM training phenomena), isomorphism table, novel synthesis section with predictions,
limitations and falsifiability, conclusion.

OUTPUT QUALITY BAR: At least one prediction in the article should be novel enough that a
researcher in either field, upon reading it, would say "we have not looked at it this way."
```

### Suggested StartingField Hints

```yaml
deliverable_type: html5_article
max_groups: 2
max_generations: 5
budget_per_group_usd: 1.20
model_assignments:
  researcher: deepseek-v4-flash
  builder: deepseek-v4-flash
  critic: gpt-oss-120b
  outlier: gemini-2.0-flash
  grader: gpt-oss-120b
checkin_schedule: [generation_2, generation_4]
outlier_cadence: every_2_generations
```

***

## Prompt Variant 3 — Regulatory Timescales × Emergent AI Behaviour

### Category
Compounding research: regulatory science + AI safety + complex systems → policy-facing analysis

### Objective String

```
DOMAIN: Regulatory theory (institutional response timescales, regulatory lag, adaptive governance)
and emergent AI system behaviour (capability jumps, multi-agent dynamics, unintended coordination).

PROBLEM SPACE: Regulatory frameworks are designed around systems whose behaviour changes slowly.
AI systems exhibit sudden emergent capability transitions (emergent abilities literature), and
multi-agent deployments can coordinate in ways not anticipated by their designers. The academic
governance literature (Collingridge dilemma, adaptive governance theory) describes exactly this
class of problem in other domains (nuclear, biotech) but has not been formally applied to LLM
deployment contexts.

CONSTRAINTS:
  - Hard: The article must analyse at least two historical non-AI regulatory cases where
    the Collingridge dilemma applied. These cases must be documented in the group KB with source
    citations before being promoted to the article.
  - Hard: The article must map at least two specific AI emergent-behaviour findings from 2023–2025
    onto the regulatory-theory concepts identified in the non-AI cases. The mapping must be
    explicit, not suggestive.
  - Hard: The novel contribution section must produce at least one concrete regulatory design
    recommendation that does not appear in existing AI governance literature. Agents must query
    the KB for existing governance proposals before declaring anything novel.
  - Hard: The article must not make policy prescriptions that contradict established legal norms
    in at least two major jurisdictions. Agents must verify jurisdiction compatibility using
    available research tools.
  - Soft: Tone should be analytical, not alarmist. The target reader is a senior policy advisor,
    not a general audience.
  - Soft: Include a visual summary table (HTML table, no external dependencies) mapping cases to
    concepts to recommendations.

DELIVERABLE: A self-contained HTML5 article. Estimated reading time 12–18 minutes.
Structure: problem framing, regulatory theory primer, AI emergent behaviour evidence, cross-domain
mapping, novel governance recommendation(s), limitations, conclusion.

OUTPUT QUALITY BAR: The governance recommendation must be specific enough that a policy team
could draft a consultation document from it.
```

### Suggested StartingField Hints

```yaml
deliverable_type: html5_article
max_groups: 3
max_generations: 5
budget_per_group_usd: 1.50
model_assignments:
  researcher: deepseek-v4-flash
  builder: gemini-2.0-flash-lite
  planner: gemini-2.0-flash
  critic: gpt-oss-120b
  grader: gpt-oss-120b
checkin_schedule: [generation_2, generation_4]
```

***

## Prompt Variant 4 — Attention Mechanisms × Human Visual Saliency

### Category
Compounding research: computational neuroscience + transformer architecture → design implications

### Objective String

```
DOMAIN: Human visual attention and saliency (Itti & Koch fixation prediction, top-down vs
bottom-up attention in neuroscience) and transformer self-attention mechanisms (attention head
interpretability literature 2022–2025).

PROBLEM SPACE: Transformer self-attention is routinely described using visual attention metaphors,
but the actual computational properties of transformer heads have rarely been rigorously compared
against what neuroscience knows about human visual fixation. Saliency prediction research
(DeepGaze, SALICON) and attention head analysis (Voita et al., Clark et al., recent mechanistic
interpretability) run on parallel tracks. Bridging them could reveal whether transformer
architectures accidentally instantiate biological attention strategies, and what that implies for
the design of more interpretable or efficient attention variants.

CONSTRAINTS:
  - Hard: At least one claim must be based on a finding from mechanistic interpretability research
    published in 2024 or 2025 and stored in the KB with a valid source before being used.
  - Hard: The article must distinguish between metaphorical similarity and structural/computational
    similarity. Any metaphorical claim must be explicitly labelled as such.
  - Hard: The novel synthesis section must propose at least one concrete, testable architectural
    experiment (a change to attention head design or training procedure) motivated by a specific
    finding from visual neuroscience. The proposal must reference both the neuroscience finding
    and the current gap in the transformer literature.
  - Soft: Include at least one illustrative comparison (HTML table or structured text, no
    external images) contrasting biological and transformer attention properties side by side.
  - Soft: The article should be approachable by an ML practitioner without a neuroscience
    background; neuroscience concepts must be briefly defined on first use.

DELIVERABLE: A self-contained HTML5 article. Estimated reading time 8–12 minutes.
Structure: motivation, biological attention summary, transformer attention summary, comparative
analysis, novel architectural proposal, limitations and open questions, conclusion.

OUTPUT QUALITY BAR: The architectural proposal should be specific enough that an ML researcher
could design an ablation study from it.
```

### Suggested StartingField Hints

```yaml
deliverable_type: html5_article
max_groups: 2
max_generations: 4
budget_per_group_usd: 0.90
model_assignments:
  researcher: deepseek-v4-flash
  builder: gemini-2.0-flash-lite
  critic: gpt-oss-120b
  outlier: gemini-2.0-flash
  grader: gemini-2.0-flash
checkin_schedule: [generation_2, generation_4]
```

***

## Prompt Variant 5 — Metabolic Rate Scaling × LLM Inference Cost Scaling

### Category
Compounding research: allometric scaling laws in biology + empirical scaling laws in LLMs → predictive model

### Objective String

```
DOMAIN: Biological metabolic scaling (Kleiber's Law, West-Brown-Enquist metabolic theory,
quarter-power scaling) and empirical scaling laws in large language models (Hoffmann et al.
Chinchilla scaling, inference cost scaling, emergent capability thresholds).

PROBLEM SPACE: Kleiber's Law describes how metabolic rate scales as a 3/4 power of body mass
across 27 orders of magnitude of organism size — a deeply non-obvious regularity explained by
fractal resource distribution networks. LLM scaling research has identified power-law
relationships between model size, training compute, and capability, but the theoretical
underpinning of why these laws hold is contested. The metabolic scaling literature offers a
candidate mechanistic explanation (network geometry and resource transport constraints) that
has not been applied to LLM architecture.

CONSTRAINTS:
  - Hard: The mathematical form of Kleiber's Law and the Chinchilla scaling law must both be
    stated precisely in the article (plain text, no MathJax) with sources before the comparison
    is made.
  - Hard: Any claimed structural analogy between the two scaling regimes must identify a specific
    mechanism in each domain that produces the scaling exponent — not just the coincidence of
    similar exponents.
  - Hard: The novel section must generate at least one falsifiable prediction: a specific
    empirical test (e.g. an experiment with a specific model size range) that would either
    confirm or refute the analogy.
  - Hard: If the agents' research finds prior work making this analogy, that work must be
    cited and the article must be repositioned to either extend or refute it — not simply
    repeat it.
  - Soft: Include a side-by-side comparison table of scaling parameters from biology vs LLMs.
  - Soft: Prioritise precision over comprehensiveness. A sharp, narrow contribution is better
    than a broad, shallow survey.

DELIVERABLE: A self-contained HTML5 article. Estimated reading time 8–12 minutes.
Structure: problem statement, biological scaling review, LLM scaling review, structural
comparison, proposed mechanistic bridge, testable predictions, limitations, conclusion.

OUTPUT QUALITY BAR: A quantitative researcher should be able to immediately identify whether
the predicted scaling test has already been done, and if not, how to run it.
```

### Suggested StartingField Hints

```yaml
deliverable_type: html5_article
max_groups: 2
max_generations: 4
budget_per_group_usd: 0.80
model_assignments:
  researcher: deepseek-v4-flash
  builder: deepseek-v4-flash
  critic: gpt-oss-120b
  grader: gemini-2.0-flash
checkin_schedule: [generation_2, generation_4]
```

***

## Prompt Variant 6 — Institutional Memory × Multi-Agent Knowledge Substrates

### Category
Compounding research: organisational science + multi-agent AI systems → design principles for persistent group memory

### Objective String

```
DOMAIN: Organisational memory and institutional knowledge (Stein 1995, transactive memory
systems, Wegner 1987, organisational forgetting literature) and multi-agent AI knowledge
substrates (vector KB design, Dead Ends Registries, KG trust-scoring, legacy protocol injection).

PROBLEM SPACE: Organisational science has decades of empirical study on how groups remember,
forget, and transmit knowledge across personnel turnover. Multi-agent AI systems are beginning
to implement persistent group substrates (knowledge bases, dead-ends registries, legacy
protocols), but they largely reinvent these mechanisms without drawing on the organisational
memory literature, which already has established failure modes and design principles.
Connecting these bodies of research could yield concrete improvements to substrate design.

CONSTRAINTS:
  - Hard: At least three specific, empirically-validated failure modes from the organisational
    memory literature must be identified and stored in the KB before the article claims they
    apply to AI systems.
  - Hard: For each organisational failure mode imported, the article must identify whether the
    corresponding multi-agent substrate mechanism addresses, partially addresses, or ignores it —
    with evidence.
  - Hard: The novel contribution must be a concrete design change or new mechanism for AI
    knowledge substrates, not a general principle. It must be specific enough to be implemented
    in a system like EvoKammer.
  - Soft: The article should be readable by both an organisational psychologist and an AI
    systems engineer — bridging jargon is expected and should be made explicit.
  - Soft: Where the two fields use different terms for the same construct, create a terminology
    mapping table (HTML table, inline).

DELIVERABLE: A self-contained HTML5 article. Estimated reading time 10–14 minutes.
Structure: framing, organisational memory review (failure modes and mechanisms), AI substrate
review, cross-mapping table, novel substrate design contribution, limitations, conclusion.

OUTPUT QUALITY BAR: An engineer reading the novel contribution section should be able to
open a ticket to implement it.
```

### Suggested StartingField Hints

```yaml
deliverable_type: html5_article
max_groups: 2
max_generations: 5
budget_per_group_usd: 1.00
model_assignments:
  researcher: deepseek-v4-flash
  builder: gemini-2.0-flash-lite
  planner: gemini-2.0-flash
  critic: gpt-oss-120b
  grader: gpt-oss-120b
checkin_schedule: [generation_2, generation_4]
```

***

## Common Grader Rubric for All Variants

These dimensions should be included in every Grader Group's GradingSheet for HTML5 article runs:

| Dimension | Weight | Hard Threshold |
|---|---|---|
| Novelty (contribution not present verbatim in cited sources) | 30% | Must be > 0 to pass |
| Research grounding (claims tied to KB-validated sources) | 25% | All hard-constraint claims sourced |
| Cross-domain synthesis depth (not a juxtaposition — a genuine bridge) | 20% | At least one structural analogy required |
| HTML5 validity and self-containedness (no external deps) | 10% | Automated check; must pass |
| Readability for target audience (sentence complexity, terminology definitions) | 10% | Subjective grader score |
| Falsifiability (novel claims can be tested or refuted) | 5% | At least one falsifiable claim required |

***

## Dead Ends Seed (Pre-load in KB before run)

To save budget and prevent agents from rediscovering common traps, pre-load these entries
into the Dead Ends Registry before each run:

1. **Pure survey articles**: An article that merely summarises each domain's literature without
   building a bridge between them. Critic should reject any draft that does not contain a
   synthesis section making claims absent from individual sources.

2. **Metaphor-only analogies**: Claiming two things are "similar" without specifying the shared
   mechanism or mathematical structure. Critic should challenge any analogy not backed by a
   structural description.

3. **Unfalsifiable novel claims**: Proposals that cannot, even in principle, be tested or
   refuted. Critic should require a falsifiability statement before promoting any claim tagged
   as novel.

4. **External dependency in HTML**: Including `<script src="...">`, `<link rel="stylesheet"
   href="...">` pointing to external hosts. Builder should be instructed to inline all styles
   and avoid runtime external calls.

***

## Notes on Small Run Configuration

- Keep `max_generations` at 3–5 to contain cost. These prompts are calibrated for this range.
- A 2-group run at 4 generations with the cheap model stack costs roughly $0.60–$1.50 total.
- The `outlier` role should be assigned at generation 3+ to introduce late-stage diversification
  without burning budget on early wild exploration.
- The `planner` role is optional for 4-generation runs; include it only if the knowledge node
  graph has > 8 nodes.
- Seal the judge test suite before spawning any agents. The HTML5 validity check and citation
  integrity check are automatable; include them as deterministic test cases in the sealed suite.