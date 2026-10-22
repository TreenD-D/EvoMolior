# ARC2 solver research implementation handoff

## Status and authority

This handoff distills the six-round EvoMolior run
`arc2-inference-novelty-20260810-r7`. The run completed all six archived
rounds, but strict accepted publication authority was unavailable. Treat the
report and this handoff as **provisional research guidance**, not solver
promotion, private-set evidence, or Kaggle submission authorization.

The recommended implementation order uses only supplied public-training and
diagnostic artifacts. Hidden/public-evaluation labels must remain outside
generation, routing, selection, and promotion.

## Recommended first experiment

Implement the **parent-union cross-checkpoint selector** (H1), with
**correlation-aware attempt-two selection** (H8) and the **finite telemetry
validator** (H12) as prerequisites.

This experiment dominates another training run because it is deterministic,
target-free, CPU-bounded, and directly separates:

1. **selection loss** — useful candidates already exist across archived
   checkpoints but are lost by scalar ranking; from
2. **candidate-generation loss** — the required programs are absent from the
   archived pools.

## Minimum implementation slice

1. **Fail-closed artifact validation**
   - Reject non-finite JSON numbers before candidate publication.
   - Preserve the original immutable tree and hashes; emit a separate finite
     diagnostic summary rather than rewriting evidence.
   - Add injected `NaN`/`Inf` tests and require zero false acceptance.

2. **Immutable parent inventory**
   - Read archived V2/V3 parent IDs, checkpoint hashes, AST ancestry,
     operation family, rank, residual signature, provenance, and verifier-call
     counts.
   - Do not use target values, evaluation labels, or post-freeze scores.

3. **Deterministic union selector**
   - Select at most seven unique parents.
   - Freeze at most 63 candidates.
   - Preserve at least one candidate from each materially distinct ancestry or
     operation niche before filling remaining slots by the predeclared score.
   - Record the complete selection rule and selected IDs before exact
     verification.

4. **Operation-conditioned typed edits**
   - Allocate color/enum edits from parent AST type and target-free residual
     signatures.
   - Keep the first experiment bounded; do not add a broad singleton sweep.

5. **Exact verification and attempt-two policy**
   - Verify demonstrations with the existing exact verifier.
   - Choose attempt 1 by the frozen primary policy.
   - Choose attempt 2 by marginal causal coverage: penalize shared AST
     ancestry, checkpoint parent, residual signature, operator family, and
     correlated intermediate-grid failures.
   - Emit exactly two shape-valid outputs per test input.

## Metrics and gates

| Measure | Required decision use |
|---|---|
| V3 retrieval recovery | Tests whether archived-parent selection was the dominant loss |
| Incremental real exact tasks | Primary solver-value metric |
| Exact tasks per verifier call | Search-efficiency metric |
| Pairwise failure correlation | Ensures attempt 2 adds causal coverage |
| Invalid-output rate | Must not increase |
| Provenance and finite-number audit | Must pass before any promotion |

**Kill the branch** if V3 retrieval is not recovered, verifier efficiency does
not improve, any target leakage occurs, telemetry is non-finite, or attempt-two
diversity reduces pass@2 union.

**Promote to the next research gate** only if retrieval is non-inferior, at
least two incremental real public-training tasks are solved, call efficiency
is non-inferior, invalid outputs do not increase, and the result replicates
under the frozen policy.

## Follow-on branch order

1. H2 — operation-conditioned edits.
2. H5 — counterexample-directed repair capped at two edits.
3. H4 + H7 — residual relational signatures and information-gain routing,
   compared with shuffled and scalar controls.
4. H3 + H9 — anti-unified schemas and depth-two typed composition.
5. H11 — one provenance-gated GPU trajectory only after inference-policy
   experiments pass.

## Source anchors

- `reports/arc2-program-prior-v2-full-20260810.md`
- `ARC2_SUBMISSION_CONTINUATION_20260809.md`
- `reports/arc2-program-prior-sweep-20260809.md`
- `reports/arc2-composition-a2-e04-20260807.md`
- `reports/arc2-residual-atlas-a2-e01-20260807.md`
- `reports/arc2-kaggle-corpus-specialists-20260809.md`
- `A2_2_INTERFACE_FREEZE.md`
- `evomolior_arc2_luna_hypothesis_register.json`

The machine-readable experiment definitions are in
`hypothesis-register.json`; the ranked execution table is in
`experiment-backlog.csv`.
