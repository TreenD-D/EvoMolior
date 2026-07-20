# EvoMolior Public Benchmark Evidence

Last reviewed: July 20, 2026.

This is the public, path-safe benchmark ledger. The complete internal benchmark ladder, raw run
outputs, evaluator records, and answer-bearing reports remain available locally under ignored
development/result directories; they are intentionally excluded from the public repository.

Machine-readable public evidence:
[`../submission/evidence/qwen-frames-comparison.json`](../submission/evidence/qwen-frames-comparison.json).

Judge-facing claim boundaries:
[`../submission/Evidence_and_Claims.md`](../submission/Evidence_and_Claims.md).

## Executive Readout

The retained public evidence supports **two narrow Qwen Cloud FRAMES comparisons**:

| Comparison | EvoMolior society | Direct Qwen baseline | Public conclusion |
|---|---:|---:|---|
| FRAMES `19..21` | `2/3` strict exact; evidence-path coverage `1.0`; `501.7 s` wall clock | `1/3` strict exact; evidence-path coverage `1.0`; `26.8 s` wall clock | Society produced one additional exact answer on a three-task sample and was about `18.7×` slower |
| Hard FRAMES `537` | `1/1` strict exact; evidence-path coverage `1.0`; `499.1 s` wall clock | `0/1` strict exact; evidence-path coverage `1.0`; `14.4 s` wall clock | Society won one high-source-fan-out case; sample size is one and latency is much worse |

Provider cost is unavailable in the retained source reports. Do not infer a cost advantage.

## Additional Retained Findings

The following are useful context but are not machine-readable public comparison artifacts:

| Comparison | Result | Claim boundary |
|---|---|---|
| Hard FRAMES `444` | `0/1` society and `0/1` direct Qwen | Tie |
| MetaSyn task-24 candidate subset | Recall `0.6667`, precision `1.0`, accuracy `0.875` in both arms | Objective tie; society much slower |
| MetaSyn task-419 candidate subset | Recall `0.5`, precision `1.0`, accuracy `0.8571` in both arms | Objective tie |
| DeepResearch Bench prompt `62` | Required report present in both arms | Artifact-completeness evidence only; no official rubric score |

These summaries are retained in
[`../submission/Evidence_and_Claims.md`](../submission/Evidence_and_Claims.md) with their limitations.

## Evidence Deliberately Excluded From Public Claims

- **LitQA2 is not used as a same-model Qwen win.** The verified society report used an OpenRouter
  mixed-model backend while the baseline used Qwen Cloud.
- **Codex/ChatGPT runs are backend-compatibility evidence**, not same-model Qwen comparisons.
- **Raw `results/` reports are not public evidence.** They include local filesystem paths and, in
  some cases, benchmark questions, gold answers, or model response text.
- **Internal `documentation/agent-notes/` evaluations are not public artifacts.** The public JSON
  carries source-report hashes without exposing private report contents.
- **Older duplicate reruns are omitted.** A newer run does not become stronger evidence merely
  because it has a later timestamp; only the retained post-fix comparison authority is summarized.

## Safe Public Claim

> EvoMolior is a Qwen Cloud agent-society runtime that makes multi-agent work auditable. On two
> small Qwen Cloud FRAMES comparisons it achieved higher strict exact-answer accuracy than a direct
> Qwen 3.7 Plus agent, while research-synthesis comparisons tied and the society was materially
> slower.

## Claims This Evidence Does Not Support

- broad superiority over a strong single agent;
- lower cost or better cost efficiency;
- lower latency;
- an official DeepResearch Bench score;
- a same-model Qwen LitQA2 win;
- universal gains from adding more agents or rounds;
- factual correctness guaranteed by publication validation.

## Public Evidence Hygiene

The public benchmark artifact intentionally contains no:

- absolute local paths;
- benchmark questions or gold answers;
- model response text;
- API keys or provider credentials;
- raw databases, logs, or result bundles.

Full run bytes remain intact locally and are excluded through `.gitignore`; public-release cleanup
must untrack any historical result artifacts without deleting those local files.
