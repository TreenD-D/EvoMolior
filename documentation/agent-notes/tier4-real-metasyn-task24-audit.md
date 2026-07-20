# Tier 4 Real MetaSyn Task 24 Tracked Audit

Date: 2026-07-04

## Why This File Exists

The detailed runtime artifacts for the real Tier 4 run are under `results/`, which is ignored by git.
This tracked note preserves the minimum audit evidence needed to understand the task, gold labels,
run outcome, and evaluation without relying on ignored files.

Tracked machine-readable audit artifacts:

- Gold labels: `documentation/agent-notes/tier4-real-metasyn-task24-gold.json`
- Extracted predictions: `documentation/agent-notes/tier4-real-metasyn-task24-predictions.json`
- Expected evaluation metrics: `documentation/agent-notes/tier4-real-metasyn-task24-evaluation.json`
- Reproducible evaluator: `documentation/agent-notes/evaluate_metasyn_candidate_subset.py`

## Dataset Evidence

- Dataset: `THUIR/MetaSyn`
- Dataset page: `https://huggingface.co/datasets/THUIR/MetaSyn`
- Dataset files observed through the Hugging Face API: `README.md`, `corpus.json`, `papers.json`,
  `test_ids.json`
- Dataset card summary: 442 meta-analyses and a 140,585-article retrieval corpus
- Official test split: `test_ids.json` contains task ID `24`
- Full corpus size observed from resolver headers: `corpus.json` has
  `x-linked-size: 2428710311` bytes

## Task 24 Metadata

- Topic: effectiveness of low-energy formula diets for weight loss in obese patients with and
  without type 2 diabetes
- Research question: compare weight losses following very low calorie diets (`<800 kcal/day`) or
  low-energy liquid-formula diets (`>800 kcal/day`) in obese patients with type 2 diabetes versus
  obese patients without type 2 diabetes
- Population: obese patients with and without type 2 diabetes mellitus
- Intervention: very low calorie diets or low-energy liquid-formula diets using total diet
  replacement
- Comparison: obese patients without type 2 diabetes mellitus receiving the same formula diet
  intervention
- Outcome: weight loss
- Gold effect direction: `Null`
- Gold statistical significance: `Non-significant`
- Gold effect size category: `Negligible`
- Gold conclusion summary: weight losses with liquid-formula diets were not significantly different
  between obese participants with and without T2DM (`WMD=-1.2 kg`, `95% CI: -4.1 to 1.6 kg`), based
  on five studies (`n=569`)

## Corpus-Matching Limitation

The official task row has:

- `study_count=5`
- `matched_ref_count=3`
- `match_rate=0.6`
- `matched_corpus_ids=[4565, 4566, 4567]`

There are no additional matched corpus IDs for task 24 in the available `papers.json` metadata. The
two remaining official included studies are available as extracted/raw reference titles only, not as
matched `corpus.json` records in this public task row.

## Candidate Subset Used

Gold included candidate corpus IDs:

- `4565` / PMID `22146094`: Less fat reduction per unit weight loss in type 2 diabetic compared
  with nondiabetic obese individuals completing a very-low-calorie diet program.
- `4566` / PMID `9472867`: An intensive weight loss programme in established type 2 diabetes and
  controls: effects on weight and atherosclerosis risk factors at 1 year.
- `4567` / PMID `25069603`: Very low calorie diet without aspartame in obese subjects: improved
  metabolic control after 4 weeks treatment.

Hard-negative candidate corpus IDs:

- `4558` / PMID `34578999`
- `4559` / PMID `34042299`
- `4568` / PMID `31373036`
- `4570` / PMID `21253001`
- `4571` / PMID `24112223`

## CLI Runs

First attempt:

- Output root: `results/tier4-real-metasyn-20260704-run/tier4-real-metasyn-task24-20260704`
- CLI exit: `0`
- Stage status: `COMPLETED`
- Published output content: `[no-progress completion: empty-assistant recovery]`
- Evaluation decision: failed attempt, not scored

Successful rerun:

- Output root:
  `results/tier4-real-metasyn-20260704-rerun/tier4-real-metasyn-task24-rerun-20260704`
- Command difference: used longer stage config and `-model Builder=deepseek/deepseek-v4-flash`
- Primary stage: `COMPLETED`, duration `80638 ms`
- Systematic-review-pack stage: `COMPLETED`, duration `319654 ms`
- Published deliverable:
  `results/tier4-real-metasyn-20260704-rerun/tier4-real-metasyn-task24-rerun-20260704/outputs/systematic_review_pack.md`
- Published deliverable SHA-256:
  `9add605a219cdb5d8f46b761469118dd480ca5bd4c1a37a7022f8c5e11faafed`
- Input candidate file SHA-256:
  `327341b8f269fe6a68b76817f814b9084f8dbaeb9edaca4274b324e9ea1d1fc7`
- Runtime cost telemetry: `usd_cost=null`
- `cost_report.json`: `wall_clock_minutes=5.326716666666667`,
  `quality_score=11.891891891891893`, citation precision proxy `0.05`

## Scored Output

Predicted included corpus IDs:

- `4565`
- `4566`

Predicted excluded corpus IDs:

- `4567`
- `4558`
- `4559`
- `4568`
- `4570`
- `4571`

Metrics:

- Included-study recall over matched positive candidates: `2/3 = 0.6666666666666666`
- Included-study precision: `2/2 = 1.0`
- Hard-negative rejection accuracy: `5/5 = 1.0`
- Candidate-level accuracy: `7/8 = 0.875`
- False negatives: `4567`
- False positives: none
- Synthesis direction match: pass. EvoMolior concluded that VLCD therapy produced a similar
  magnitude of weight loss irrespective of diabetes status, matching the gold null/non-significant
  direction qualitatively.

## Reproducibility Check

Run:

```sh
python3 documentation/agent-notes/evaluate_metasyn_candidate_subset.py --pretty
```

When the ignored runtime artifacts are present locally, also verify file hashes:

```sh
python3 documentation/agent-notes/evaluate_metasyn_candidate_subset.py --verify-files --pretty
```

Expected computed metrics:

```json
{
  "candidate_level_accuracy": 0.875,
  "dataset": "THUIR/MetaSyn",
  "hard_negative_rejection_accuracy": 1.0,
  "hard_negative_true_negatives": [4558, 4559, 4568, 4570, 4571],
  "included_false_negatives": [4567],
  "included_false_positives": [],
  "included_precision": 1.0,
  "included_recall": 0.6666666666666666,
  "included_true_positives": [4565, 4566],
  "is_full_leaderboard_run": false,
  "run_id": "tier4-real-metasyn-task24-rerun-20260704",
  "scoring_scope": "candidate_subset_with_all_matched_positive_corpus_ids",
  "synthesis_direction_match": true,
  "task_id": 24
}
```

Verification command run during audit hardening:

```sh
python3 - <<'PY'
import json
from pathlib import Path
import importlib.util

script = Path("documentation/agent-notes/evaluate_metasyn_candidate_subset.py")
spec = importlib.util.spec_from_file_location("eval_task24", script)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)
gold = mod.load_json(Path("documentation/agent-notes/tier4-real-metasyn-task24-gold.json"))
pred = mod.load_json(Path("documentation/agent-notes/tier4-real-metasyn-task24-predictions.json"))
computed = mod.compute_metrics(gold, pred)
expected = json.load(open("documentation/agent-notes/tier4-real-metasyn-task24-evaluation.json"))
for key, value in computed.items():
    if expected.get(key) != value:
        raise SystemExit(f"mismatch {key}: expected {expected.get(key)!r} computed {value!r}")
print("evaluation-json-matches-computed-metrics")
PY
```

Final local artifact verification also passed with:

```sh
python3 - <<'PY'
import json
from pathlib import Path
import importlib.util

script = Path("documentation/agent-notes/evaluate_metasyn_candidate_subset.py")
spec = importlib.util.spec_from_file_location("eval_task24", script)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)
gold = mod.load_json(Path("documentation/agent-notes/tier4-real-metasyn-task24-gold.json"))
pred = mod.load_json(Path("documentation/agent-notes/tier4-real-metasyn-task24-predictions.json"))
mod.verify_file_hashes(pred)
computed = mod.compute_metrics(gold, pred)
expected = json.load(open("documentation/agent-notes/tier4-real-metasyn-task24-evaluation.json"))
for key, value in computed.items():
    if expected.get(key) != value:
        raise SystemExit(f"mismatch {key}: expected {expected.get(key)!r} computed {value!r}")
print("metrics-and-file-hashes-verified")
PY
```

## Interpretation

This is a real MetaSyn test-task run over official task metadata and official corpus candidate
abstracts. It is not a full official MetaSyn leaderboard run because EvoMolior did not retrieve over
the entire 140,585-article corpus and the public task row only exposes three matched corpus IDs for
the five official included studies.
