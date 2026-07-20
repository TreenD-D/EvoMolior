# Tier 4 Audit Robustness Goal Log

Started: 2026-07-04

## User Goal

Review the audit and apply required fixes/improvements to make it more robust.

## Locked Interpretation

- The audit under review is the real MetaSyn task 24 Tier 4 audit:
  `documentation/agent-notes/tier4-real-metasyn-task24-audit.md`.
- Robustness means the result should be reproducible and less dependent on manual prose:
  - gold labels and predicted labels should be machine-checkable from tracked files;
  - metrics should be recomputable from a script;
  - limitations should be explicit and verifiable;
  - documentation should distinguish official task metadata, candidate-subset scoring, and full
    leaderboard scoring.
- Do not hide the false negative on `4567`; preserve the scored result honestly.

## Plan

- [x] Review the existing tracked audit for ambiguity, missing evidence, and unverifiable claims.
- [x] Add tracked, machine-readable gold/prediction/evaluation artifacts outside ignored `results/`.
- [x] Add a reproducible evaluator script and run it.
- [x] Update audit and benchmark docs to point to reproducible artifacts and clarify limitations.
- [x] Stage tracked changes.
- [x] Verify goal evidence and mark complete.

## Robustness Improvements Applied

- Added tracked gold labels:
  `documentation/agent-notes/tier4-real-metasyn-task24-gold.json`
- Added tracked extracted predictions:
  `documentation/agent-notes/tier4-real-metasyn-task24-predictions.json`
- Added tracked expected metrics:
  `documentation/agent-notes/tier4-real-metasyn-task24-evaluation.json`
- Added a reproducible evaluator:
  `documentation/agent-notes/evaluate_metasyn_candidate_subset.py`
- Added SHA-256 pins for the ignored input and output files and an optional evaluator
  `--verify-files` mode for local artifact verification.
- Updated the tracked audit:
  `documentation/agent-notes/tier4-real-metasyn-task24-audit.md`
- Updated the benchmark ladder to distinguish:
  - earlier dataset-server row limitation;
  - later file-level Hugging Face access;
  - candidate-subset real task scoring;
  - non-leaderboard/full-corpus caveat.

## Verification

Commands run:

```sh
python3 documentation/agent-notes/evaluate_metasyn_candidate_subset.py --pretty
python3 documentation/agent-notes/evaluate_metasyn_candidate_subset.py --verify-files --pretty
python3 -m json.tool documentation/agent-notes/tier4-real-metasyn-task24-gold.json
python3 -m json.tool documentation/agent-notes/tier4-real-metasyn-task24-predictions.json
python3 -m json.tool documentation/agent-notes/tier4-real-metasyn-task24-evaluation.json
```

The evaluator produced:

- Included-study recall: `0.6666666666666666`
- Included-study precision: `1.0`
- Hard-negative rejection accuracy: `1.0`
- Candidate-level accuracy: `0.875`
- Synthesis direction match: `true`

The computed metrics were compared against
`documentation/agent-notes/tier4-real-metasyn-task24-evaluation.json` and matched.

Final verification also ran the evaluator's file-hash mode against the local ignored artifacts and
printed `metrics-and-file-hashes-verified`.
