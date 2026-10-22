# EvoMolior Benchmark Evidence

**Evidence review:** July 20, 2026.

This document records bounded benchmark observations and the rules for interpreting them. The
underlying run outputs, evaluator records, answer-bearing reports, and local paths remain excluded
from the public repository.

## Interpretation Rules

- Treat every result as a measurement of a specific task slice, runner shape, provider, and model
  assignment.
- Report ties, regressions, failures, latency, unavailable cost, and incomplete rubric coverage
  alongside successful outputs.
- Keep society and single-agent arms on the same frozen inputs and evaluation contract when making
  a comparison.
- Do not generalize a small slice into broad accuracy, cost, latency, or reliability superiority.
- A completed process is not by itself evidence of a correct or useful deliverable.

## Historical Observations

These retained comparisons are useful engineering evidence. They are not a product ranking and do
not establish that one provider or runner is generally superior.

| Task slice | Society arm | Single-agent or control arm | Observation |
|---|---:|---:|---|
| FRAMES `19..21` | `2/3` strict exact; evidence-path coverage `1.0`; `501.7 s` | Direct Qwen 3.7 Plus `1/3` strict exact; evidence-path coverage `1.0`; `26.8 s` | One additional exact answer on a three-task slice; society was about `18.7×` slower |
| Hard FRAMES `537` | `1/1` strict exact; evidence-path coverage `1.0` | Direct Qwen 3.7 Plus `0/1` strict exact; evidence-path coverage `1.0` | One-task case study; latency was materially higher |
| Hard FRAMES `444` | `0/1` strict exact | Direct Qwen 3.7 Plus `0/1` strict exact | Tie |
| MetaSyn task 24 candidate subset | Recall `0.6667`; precision `1.0`; accuracy `0.875` | Same objective metrics | Tie; society was much slower |
| MetaSyn task 419 candidate subset | Recall `0.5`; precision `1.0`; accuracy `0.8571` | Same objective metrics | Tie |
| DeepResearch Bench prompt `62` | Required report present | Required report present | Artifact-completeness observation only; no official rubric score |

The FRAMES rows used Qwen Cloud with `qwen3.7-plus` in both arms. That provider identity is part of
the experiment configuration, not a claim that Qwen is the required or default backend. Other
maintained runs use OpenRouter or local Codex/ChatGPT compatibility mode.

Provider cost was unavailable in the retained source reports. No cost advantage should be inferred.

## What The Evidence Does Not Establish

- broad superiority over a strong single agent;
- lower cost or better cost efficiency;
- lower latency;
- an official score for an incompletely wired benchmark rubric;
- universal gains from adding agents, groups, or rounds;
- factual correctness guaranteed by publication validation;
- independence between graders that share a provider or model family.

## Public Evidence Hygiene

Public benchmark documentation must not include:

- absolute local paths;
- benchmark questions or gold answers;
- model response text;
- API keys or provider credentials;
- raw databases, logs, or unrestricted result bundles.

Full run bytes remain local and should be preserved during documentation cleanup. The canonical
roadmap remains the authority for current implementation status.
