# ARC-AGI-2 Kaggle solver: inference mechanisms, novel hypotheses, and research program

Produce a rigorous, standalone HTML5 research report for the operator of the
ARC-AGI-2 Kaggle solver whose plans, state records, experiment reports,
hypothesis registers, audits, and prior EvoMolior reports are supplied as run
artifacts.

This is a six-round EvoMolior research run with exactly four competing worker
groups and a ceiling of five agents per group. The Genesis Council should
construct four genuinely different approach vectors and allocate four or five
complementary agents per group in each round whenever the bounded selector
permits it. Useful role mixes include researcher, planner, builder, critic,
outlier, archivist, auditor, connector, and perspective-taker. Do not make the
four groups paraphrase one another.

## Research target

Infer the highest-leverage next mechanisms for improving the current ARC2
solver and its Kaggle-safe inference path. Go beyond summarizing the already
selected Prompt V2 plus typed retrieval/mutation continuation. Diagnose what
the evidence says the system is missing, generate novel but executable ideas,
falsify weak ideas, and design an ordered experimental program.

The report must:

1. Reconstruct the current authoritative state as of August 9, 2026. Resolve
   conflicts among documents by date, explicit authority, immutable hashes,
   and terminal evidence. Separate completed evidence, active decisions,
   blocked work, superseded plans, and open questions.
2. Explain the finite-retrieval and prompt-truncation ceiling, the significance
   of the verified-real/synthetic gap, and what the long sequence of negative
   bounded-mining results implies about the remaining task distribution.
3. Identify representation, inference, search, repair, routing, composition,
   object/relation, portfolio, and Kaggle-runtime bottlenecks. Distinguish
   model capability bottlenecks from codec, candidate-space, verifier,
   orchestration, and evidence-quality bottlenecks.
4. Propose at least twelve falsifiable solver hypotheses. At least six must be
   meaningfully novel relative to the supplied hypothesis registers and prior
   loop history, not renamed versions of tested failures.
5. For every proposed hypothesis, provide:
   - mechanism and why it could work;
   - exact relationship to source evidence and prior negative evidence;
   - minimum implementation slice;
   - leakage-safe dataset/evaluation boundary;
   - primary metric and compute/call budget;
   - falsifier, kill gate, promotion gate, and expected information value;
   - interaction with exact verification and the exactly-two-attempt contract.
6. Rank a portfolio of experiments. Include a smallest decisive experiment,
   a 24-hour plan, a seven-day plan, and a longer-horizon branch. Prefer
   experiments that distinguish competing causal explanations over broad
   expensive sweeps.
7. Design a concrete offline Kaggle integration path. Preserve immutable model
   revisions, local-files-only execution, content-addressed packages, exact
   verifier authority, deterministic provenance, sealed public evaluation,
   and exactly two outputs per test input.
8. Analyze attempt-2 selection as marginal causal coverage rather than a
   second scalar-ranked candidate. Include diversity and correlation controls.
9. State what must not be claimed from the available evidence, including
   private-set performance and promotion readiness.
10. End with a crisp recommended decision: the next experiment to execute,
    why it dominates alternatives, prerequisites, stop conditions, and the
    evidence that would change the decision.

## Required analytical lenses

Ensure the four competing societies collectively cover these distinct lenses:

- **Typed mechanism synthesis:** lossless codec, typed edits, bounded
  composition, anti-unification, abstraction, and executable program priors.
- **Residual and representation inference:** object/relation/topology/scene
  structure, counterexample-driven repair, latent decomposition, and what can
  be learned from exact near-misses without leaking targets.
- **Search economics and society design:** routing, uncertainty, expected
  information gain, niche preservation, verifier-call allocation, trace
  quality, and model-vs-deterministic work partitioning.
- **Portfolio and Kaggle systems:** pass@2 marginal coverage, causal diversity,
  offline packaging, runtime budgets, fail-closed controls, and reproducible
  promotion ladders.

## Evidence discipline

- Use only supplied run artifacts. Do not fetch new external sources.
- Cite important claims with exact relative artifact paths and, where present,
  experiment IDs, task counts, metric values, dates, hashes, or checklist
  states.
- Label important statements as **Observed**, **Inferred**, or **Speculative**.
- Treat public-training diagnostics as research evidence, never as a private
  competition score.
- Do not inspect or infer hidden/public-evaluation labels.
- Preserve negative evidence. Do not recommend another broad hand-designed
  singleton sweep unless a new meta-hypothesis explains why it differs.
- Do not claim that training completion, artifact validity, or executable rate
  implies solver promotion.

## HTML5 receiver contract

Return one complete standalone HTML5 document, beginning with `<!doctype
html>`, encoded as UTF-8, with no Markdown fence and no external asset
dependency. It should be publication-ready and readable on desktop and mobile.
Use semantic landmarks, accessible contrast, a navigable table of contents,
responsive tables/cards, print styles, and lightweight built-in interaction
such as hypothesis filtering or expandable evidence details.

The HTML must include:

- executive decision;
- evidence/status map;
- plateau and causal diagnosis;
- hypothesis portfolio;
- ranked experiment backlog;
- Kaggle-safe architecture/integration sequence;
- pass@2 portfolio policy;
- risk and non-claims register;
- 24-hour, seven-day, and longer-horizon roadmap;
- source traceability appendix;
- run-method appendix explaining the four societies and six-round synthesis.

Embed two valid machine-readable JSON payloads in non-executing script tags:

```html
<script type="application/json" id="hypothesis-register">
{"schema_version":"arc2-hypothesis-register-v1","hypotheses":[...]}
</script>
<script type="application/json" id="evidence-index">
{"schema_version":"arc2-evidence-index-v1","sources":[...]}
</script>
```

Each hypothesis object must include at least: `id`, `title`, `novelty_class`,
`mechanism`, `evidence_paths`, `minimum_slice`, `metric`, `budget`,
`falsifier`, `kill_gate`, `promotion_gate`, `priority`, and
`expected_information_value`. Keep the JSON valid: no comments, trailing
commas, HTML entities inside JSON strings, or unescaped newlines.
