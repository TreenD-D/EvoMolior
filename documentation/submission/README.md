# EvoMolior Qwen Cloud Hackathon Submission Pack

This directory is the judge-facing source of truth for the Global AI Hackathon Series with Qwen
Cloud submission.

## Submission Identity

| Field | Value |
|---|---|
| Project | **EvoMolior** |
| Tagline | **EvoMolior turns Qwen Cloud models into auditable agent societies—short-lived specialists that collaborate, compete, and compound verified knowledge.** |
| Track | **Agent Society** |
| Primary user | Research, policy, diligence, and technical teams doing evidence-heavy synthesis |
| Core thesis | The agent is disposable; the reviewed substrate is not. |
| License | `AGPL-3.0-only` |

## Deliverables

| Deliverable | File |
|---|---|
| Copy-paste Devpost description | [`Devpost_Submission.md`](Devpost_Submission.md) |
| Layered architecture | [`EvoMolior_Architecture.html`](EvoMolior_Architecture.html), [`assets/evomolior-architecture.svg`](assets/evomolior-architecture.svg), PNG, and [`Architecture.md`](Architecture.md) |
| Project thumbnail | [`assets/evomolior-thumbnail.png`](assets/evomolior-thumbnail.png) |
| Three-minute demo script and shot list | [`Demo_Video_Script.md`](Demo_Video_Script.md) |
| Judge inspection and reproduction guide | [`Judge_Guide.md`](Judge_Guide.md) |
| Claims, metrics, and evidence boundaries | [`Evidence_and_Claims.md`](Evidence_and_Claims.md) |
| Alibaba Cloud deployment-proof runbook | [`Alibaba_Cloud_Deployment_Proof.md`](Alibaba_Cloud_Deployment_Proof.md) |
| Final human-action checklist | [`Submission_Checklist.md`](Submission_Checklist.md) |
| Pitch deck | [`EvoMolior_Pitch_Deck.pdf`](EvoMolior_Pitch_Deck.pdf) plus editable [`EvoMolior_Pitch_Deck.html`](EvoMolior_Pitch_Deck.html) source |

Generated image assets also include:

- `assets/evomolior-architecture.png`
- `assets/evomolior-thumbnail.svg`

Thumbnail concept: a dark 16:9 image with two glowing agent clusters feeding a persistent central
knowledge core, captioned **“Agents die. Knowledge compounds.”**

## Recommended Judge Path

1. Read the first two sections of [`Devpost_Submission.md`](Devpost_Submission.md).
2. Open the [layered HTML5 architecture deep dive](EvoMolior_Architecture.html), then use its
   full-resolution SVG when presenting.
3. Inspect the direct Qwen Cloud runtime:
   - [`LlmExecutorFactory.kt`](../../evomolior/agents/src/main/kotlin/com/achulkov/evomolior/agents/factory/LlmExecutorFactory.kt)
   - [`DashscopeToolCompatibleLLMClient.kt`](../../evomolior/agents/src/main/kotlin/com/achulkov/evomolior/agents/factory/DashscopeToolCompatibleLLMClient.kt)
   - [`QwenImageGenerationService.kt`](../../evomolior/api/src/main/kotlin/com/achulkov/evomolior/api/service/QwenImageGenerationService.kt)
4. Follow [`Judge_Guide.md`](Judge_Guide.md) for a local Qwen smoke run.
5. Review the conservative benchmark statement in
   [`Evidence_and_Claims.md`](Evidence_and_Claims.md).

## Current Evidence Boundary

The pending release includes the post-v72 architecture hardening in the dirty worktree:

- typed and compiled `GENERAL` tests in `sealed-tests-v3`;
- separate sealed Worker and Grader Atlas authority;
- exact node evidence, grading, capture, disclosure, and publication lineage;
- Section 18.2 typed metrics with explicit unavailable states;
- immutable acceptance distributions, admission checks, locks, and startup rejection artifacts;
- final-output validation inside the bounded retry boundary.

The successful short representative acceptance archive is retained as system-hardening evidence.
The full multi-round Appendix-B acceptance is being finalized in parallel. The separate new matched
comparison from remediation-plan step 16 is intentionally excluded from this submission sprint.
Judge-facing benchmark claims therefore use the retained Qwen Cloud comparisons listed in
[`Evidence_and_Claims.md`](Evidence_and_Claims.md), not an unfinished experiment.

## External Actions

Repository files cannot complete these account-level actions:

- deploy the backend to Alibaba Cloud and capture proof;
- make the repository public and verify GitHub detects the license;
- upload and publish the demo video;
- accept all Devpost teammate invitations;
- paste the final text and submit.

The exact handoff is in [`Submission_Checklist.md`](Submission_Checklist.md).
