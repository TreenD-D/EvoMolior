# Benchmark Ladder Documentation And Tier 3 Goal Log

Started: 2026-07-03

## User Goal

Document the full benchmark ladder separately in project documentation, including completed benchmark
task stats, then proceed with Tier 3 benchmarks by running 1-2 CLI tasks and reporting their outcomes
in the same documentation.

## Locked Plan

- Create a standalone project documentation file for the benchmark ladder.
- Include the ladder text and benchmark use/score contracts for Tiers 0-4.
- Add completed stats from existing CLI reports for Tier 0, Tier 1, and Tier 2.
- Run 1-2 Tier 3 long-form deep-research style CLI tasks through the production runtime.
- Archive Tier 3 prompts/input fixtures/results under `results/`.
- Add Tier 3 outcome stats to the benchmark ladder doc.
- Verify relevant build/tests if code changes are needed. Prefer documentation-only edits unless
  the Tier 3 run exposes a necessary runtime fix.

## Evidence Sources To Use

- Tier 0 FRAMES report:
  `results/tier0-cli-qwen-frames-20260703-snapshot-rank-name-prompt/tier0-benchmark-report.json`
- Tier 1 LitQA2 report:
  `results/tier1-litqa2-cli-smoke-20260703-3task/tier0-benchmark-report.json`
- Tier 1 ScholarQA report:
  `results/tier1-scholarqa-jsonl-smoke-20260703-fixed-corpus/tier0-benchmark-report.json`
- Tier 1 ScholarQABench report:
  `results/tier1-scholarqabench-qasa-smoke-20260703/tier0-benchmark-report.json`
- Tier 2 AutoResearchBench report:
  `results/tier2-autoresearchbench-smoke-20260703-costed/tier0-benchmark-report.json`
- Tier 2 ArxivDIGESTables-style report:
  `results/tier2-astabench-arxivdigestables-smoke-20260703-final/tier0-benchmark-report.json`

## Todo

- [x] Create durable goal log.
- [x] Prepare Tier 3 fixed-corpus input fixtures and prompts.
- [x] Run 1-2 Tier 3 CLI tasks.
- [x] Audit Tier 3 result bundles and outcome metrics.
- [x] Create standalone benchmark ladder documentation with completed stats.
- [x] Final audit and mark goal complete.

## Completed Artifacts

- Standalone benchmark ladder doc:
  `documentation/benchmarks/EvoMolior_Benchmark_Ladder.md`
- Tier 3 Transformer prompt/config/input:
  - `results/tier3-deepresearch-smoke-20260703-config/transformer_scaling_prompt.txt`
  - `results/tier3-deepresearch-smoke-20260703-config/transformer_scaling_pipeline.json`
  - `results/tier3-deepresearch-smoke-20260703-input/transformer_scaling_sources.md`
- Tier 3 clinical RAG prompt/config/input:
  - `results/tier3-deepresearch-smoke-20260703-config/clinical_rag_prompt.txt`
  - `results/tier3-deepresearch-smoke-20260703-config/clinical_rag_pipeline.json`
  - `results/tier3-deepresearch-clinical-only-input/clinical_rag_sources.md`
- Tier 3 Transformer output:
  `results/tier3-deepresearch-transformer-20260703/tier3-transformer-20260703/outputs/report.md`
- Tier 3 clinical RAG output:
  `results/tier3-deepresearch-clinical-20260703/tier3-clinical-20260703/outputs/report.md`

## Final Evidence

- Existing Tier 0/Tier 1/Tier 2 benchmark report stats were extracted from the report JSON files
  listed above and recorded in `documentation/benchmarks/EvoMolior_Benchmark_Ladder.md`.
- Tier 3 Transformer run:
  - Pipeline status: primary `COMPLETED`, report stage `COMPLETED`.
  - Required report-pack files present in `stages/report/results.zip`: `report.md`,
    `final_answer.md`, `claim_ledger.jsonl`, `evidence_table.csv`, `citation_map.json`,
    `cost_report.json`.
  - Manual audit: required sections present, 4/4 semantic claim targets covered, caveats recorded
    for unsupported specificity and `arXij:2009.06732` typo.
- Tier 3 clinical RAG run:
  - Pipeline status: primary `COMPLETED`, report stage `COMPLETED`.
  - Required report-pack files present in `stages/report/results.zip`: `report.md`,
    `final_answer.md`, `claim_ledger.jsonl`, `evidence_table.csv`, `citation_map.json`,
    `cost_report.json`.
  - Manual audit: required sections present, 4/4 semantic claim targets covered, caveats recorded
    for author-generated synthesis.
- Documentation audit command checked required sections/paths and file existence:
  - `documentation/benchmarks/EvoMolior_Benchmark_Ladder.md` exists.
  - Both Tier 3 `outputs/report.md` files exist.
  - Both Tier 3 `stages/report/results.zip` files exist.
  - Required text markers for Tiers 0-4 and key deliverables are present.
