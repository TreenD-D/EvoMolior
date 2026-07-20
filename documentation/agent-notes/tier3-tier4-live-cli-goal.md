# Tier 3 And Tier 4 Live CLI Benchmark Goal Log

Started: 2026-07-03

## User Goal

Continue setting up and running Tier 3 and Tier 4 benchmarks with live EvoMolior CLI runs.

## Working Interpretation

- "Live CLI runs" means running through `evomolior/cli/build/install/evomolior/bin/evomolior` and
  the production runtime, not one-off scoring scripts.
- Tier 3 should use an official DeepResearch Bench prompt when feasible, with `report.md` plus the
  archived research-pack outputs.
- Tier 4 should use a MetaSyn/systematic-review reconstruction style task through the CLI. If the
  full official MetaSyn dataset is not directly runnable in this workspace, create a fixed-corpus
  MetaSyn-style smoke task with objective deliverables and score it deterministically after the live
  CLI run.

## Current Evidence

- Official DeepResearch Bench repo discovered:
  `https://github.com/Ayanami0730/deep_research_bench`
- Official prompt file:
  `https://raw.githubusercontent.com/Ayanami0730/deep_research_bench/main/data/prompt_data/query.jsonl`
- Official rubric file:
  `https://raw.githubusercontent.com/Ayanami0730/deep_research_bench/main/data/criteria_data/criteria.jsonl`
- The official prompt file has 100 prompts: 50 Chinese and 50 English.
- Selected official DeepResearch Bench query `id=62`, topic `Science & Technology`, language
  `English`: ion-trap quantum-computing scaling.
- Saved official query metadata:
  `results/tier3-official-deepresearchbench-20260703-input/official_deepresearchbench_query_62.json`.
- Official Tier 3 prompt/config:
  - `results/tier3-official-deepresearchbench-20260703-config/official_query_62_prompt.txt`
  - `results/tier3-official-deepresearchbench-20260703-config/official_query_62_pipeline.json`
- Successful official-prompt Tier 3 CLI output:
  `results/tier3-official-deepresearchbench-20260703-rerun/tier3-drb-q62-rerun-20260703`.
- Tier 4 fixed-corpus MetaSyn-style input/config:
  - `results/tier4-metasyn-live-20260703-input/metasyn_fixed_corpus.md`
  - `results/tier4-metasyn-live-20260703-config/metasyn_fixed_corpus_prompt.txt`
  - `results/tier4-metasyn-live-20260703-config/metasyn_fixed_corpus_pipeline.json`
- Successful Tier 4 CLI output:
  `results/tier4-metasyn-live-20260703/tier4-metasyn-smoke-20260703`.

## CLI Runs

Tier 3 first attempt:

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 17.0.12) \
evomolior/cli/build/install/evomolior/bin/evomolior run \
  -promptfile results/tier3-official-deepresearchbench-20260703-config/official_query_62_prompt.txt \
  -input results/tier3-official-deepresearchbench-20260703-input \
  -output results/tier3-official-deepresearchbench-20260703 \
  -pipeline-config results/tier3-official-deepresearchbench-20260703-config/official_query_62_pipeline.json \
  -llm-backend openrouter \
  -fast \
  -rounds 1 \
  -group-count 1 \
  -round-duration-minutes 2 \
  -budget-override 80 \
  -run-id tier3-drb-q62-20260703
```

Outcome: failed before the configured report stage because the root prompt triggered the default
browser `final-article.html` deliverable heuristic.

Tier 3 successful rerun:

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 17.0.12) \
evomolior/cli/build/install/evomolior/bin/evomolior run \
  -prompt "Pipeline driver for configured Tier 3 benchmark stage. Do not create a browser deliverable in this primary stage." \
  -input results/tier3-official-deepresearchbench-20260703-input \
  -output results/tier3-official-deepresearchbench-20260703-rerun \
  -pipeline-config results/tier3-official-deepresearchbench-20260703-config/official_query_62_pipeline.json \
  -llm-backend openrouter \
  -fast \
  -rounds 1 \
  -group-count 1 \
  -round-duration-minutes 2 \
  -budget-override 80 \
  -run-id tier3-drb-q62-rerun-20260703
```

Outcome: primary stage `COMPLETED`, report stage `COMPLETED`, `report.md` published.

Tier 4 completed run:

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 17.0.12) \
evomolior/cli/build/install/evomolior/bin/evomolior run \
  -prompt "Pipeline driver for configured Tier 4 systematic review screening stage." \
  -input results/tier4-metasyn-live-20260703-input \
  -output results/tier4-metasyn-live-20260703 \
  -pipeline-config results/tier4-metasyn-live-20260703-config/metasyn_fixed_corpus_pipeline.json \
  -llm-backend openrouter \
  -fast \
  -rounds 1 \
  -group-count 1 \
  -round-duration-minutes 1 \
  -budget-override 60 \
  -run-id tier4-metasyn-smoke-20260703
```

Outcome: primary stage `COMPLETED`, systematic-review-pack stage `COMPLETED`,
`systematic_review_pack.md` published. The primary stage logged OpenRouter connection resets but the
pipeline recovered and completed.

## Audit Results

Tier 3 official-prompt run:

- Pipeline summary:
  `results/tier3-official-deepresearchbench-20260703-rerun/tier3-drb-q62-rerun-20260703/pipeline-summary.json`.
- Report:
  `results/tier3-official-deepresearchbench-20260703-rerun/tier3-drb-q62-rerun-20260703/outputs/report.md`.
- Report stage archive:
  `results/tier3-official-deepresearchbench-20260703-rerun/tier3-drb-q62-rerun-20260703/stages/report/results.zip`.
- Report stage duration: `2028139 ms` / `33.80135` minutes.
- Archived claim ledger rows: `27`.
- Runtime cost telemetry: `usd_cost=null`.
- Required sections present: executive summary, technical landscape, comparison table, claim ledger,
  citation map, limitations, open questions.
- Caveat: this is an official prompt live CLI run, not an official leaderboard score. No live
  source retrieval or official rubric evaluator was wired; the report explicitly labels claims and
  citations as unverified/model-prior.

Tier 4 MetaSyn-style fixed-corpus run:

- Pipeline summary:
  `results/tier4-metasyn-live-20260703/tier4-metasyn-smoke-20260703/pipeline-summary.json`.
- Systematic-review pack:
  `results/tier4-metasyn-live-20260703/tier4-metasyn-smoke-20260703/outputs/systematic_review_pack.md`.
- Stage archive:
  `results/tier4-metasyn-live-20260703/tier4-metasyn-smoke-20260703/stages/systematic-review-pack/results.zip`.
- Systematic-review stage duration: `161634 ms` / `2.693` minutes.
- Runtime cost telemetry: `usd_cost=null`.
- Gold included studies: `PMID:10000001`, `PMID:10000004`.
- Gold hard negatives: `PMID:10000002`, `PMID:10000003`, `PMID:10000005`.
- Predicted included studies: `PMID:10000001`, `PMID:10000004`.
- Predicted excluded hard negatives: `PMID:10000002`, `PMID:10000003`, `PMID:10000005`.
- Included-study recall: `2/2 = 1.0`.
- Included-study precision: `2/2 = 1.0`.
- Hard-negative rejection accuracy: `3/3 = 1.0`.
- Criterion-specific screening was correct for the fixed corpus.
- Caveat: this is a fixed-corpus MetaSyn-style smoke, not an official MetaSyn dataset/leaderboard
  run. The public Hugging Face rows probe for `THUIR/MetaSyn` exposed only a minimal integer `text`
  field in this environment.

## Plan

- [x] Create durable goal log.
- [x] Select one bounded official DeepResearch Bench prompt and save it as a Tier 3 live input.
- [x] Run the Tier 3 prompt through the CLI with an explicit `report.md` output spec.
- [x] Set up a Tier 4 MetaSyn-style fixed-corpus screening task and expected gold labels.
- [x] Run the Tier 4 task through the CLI with systematic-review deliverables.
- [x] Score/audit Tier 3 and Tier 4 outputs.
- [x] Update benchmark ladder documentation with live run results.
- [x] Final audit and mark goal complete.
