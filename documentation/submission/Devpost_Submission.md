# Devpost Submission Copy

## Project Name

**EvoMolior**

## Tagline

**EvoMolior turns Qwen Cloud models into auditable agent societies—short-lived specialists that collaborate, compete, and compound verified knowledge.**

## Track

**Agent Society**

## Short Description

EvoMolior is a Qwen Cloud-powered multi-agent runtime for evidence-heavy work. A Genesis Council
decomposes an objective into a sealed Node Atlas; competing groups of short-lived specialist agents
research, build, critique, and preserve knowledge in isolated persistent substrates. Separate grader
societies evaluate exact node evidence, progressive disclosure controls what each group learns next,
and a hash-linked publication pipeline produces an inspectable receiver artifact and replayable
archive.

## What, Who, And How

### What

EvoMolior turns a difficult objective and source pack into a structured, evidence-grounded
deliverable. It preserves not only the final answer, but also reviewed knowledge, rejected work,
dead ends, tool executions, scores, provenance, and explicit unavailable metrics.

### Who

It is designed for research, policy, technical strategy, diligence, and other teams where an answer
must be inspectable—not merely plausible.

### How

1. A **Genesis Council** converts the objective into typed roles, tests, disclosure stages, and a
   sealed Worker Node Atlas plus a separate Grader Atlas.
2. Multiple isolated societies receive redacted views and assign nodes to specialised agents such
   as Researchers, Builders, Critics, Archivists, Outliers, and Perspective Takers.
3. Agent contributions enter staging. Critic authority promotes, rejects, or arbitrates KB, KG,
   tool, and deliverable work.
4. Grader societies freeze criteria before reveal, evaluate exact evidence bundles, and feed
   node-level results into capture, rewards, and the next round.
5. Progressive disclosure reveals only authorized information. Outliers can propose shadow nodes,
   and promoted tools or simulations can influence later evidence.
6. Final synthesis must satisfy the publication contract and sealed tests before EvoMolior emits a
   receiver artifact, run metrics, replay, and a hash-verifiable archive.

## Inspiration

Most agent systems concentrate capability in one long-lived context window. When that agent exits,
its reasoning, mistakes, and useful intermediate work are difficult to recover.

EvoMolior starts from the opposite premise: **the agent is disposable; the substrate is not.**
Collective intelligence should live in reviewed state—knowledge, evidence, tools, tests, lineage,
and dead ends—rather than in the memory of one model invocation.

## What It Does Differently

- **Persistent civilization, mortal agents:** useful work survives individual agent termination.
- **Competition with controlled cooperation:** groups work independently while dispatch and
  progressive-disclosure authorities control cross-group information flow.
- **Separate grader societies:** grading has its own sealed Atlas, private criteria, exact bundle
  cardinality, and isolated scoring process.
- **Node-level accountability:** evidence, cost, grades, capture, downstream reuse, and final
  publication are linked to exact task nodes.
- **Fail-closed execution:** malformed Council output, non-executable tests, missing grader quorum,
  unsettled cost reservations, publication failures, and manifest drift stop the run instead of
  becoming hidden success.
- **Honest comparison:** the benchmark harness records wins, ties, failures, latency, and unavailable
  cost values instead of treating more agents or more artifacts as automatic improvement.

## How We Use Qwen Cloud

Qwen Cloud is a first-class runtime backend, not a hidden proxy:

- direct DashScope/OpenAI-compatible chat-completions integration through JetBrains Koog;
- supported Qwen Cloud model IDs include `qwen3.7-plus` and `qwen3.6-flash`;
- native function/tool calling with mandatory tool choice for tool-enabled agent turns;
- structured JSON output transport and schema-aware decoding;
- explicit ephemeral cache markers on long repeated system/user context;
- response token metadata captured into runtime observability;
- optional final-deliverable image generation with `qwen-image-2.0-pro`;
- maintained direct single-Qwen benchmark baselines for comparison with society runs.

Some configurations can also route `deepseek-v4-flash` through Qwen Cloud. That is a Qwen Cloud
service path, but it is not described as a Qwen model.

## Built With

Paste these into Devpost's **Built With** section:

- Qwen Cloud
- DashScope
- Qwen3.7-Plus
- Qwen3.6-Flash
- Qwen Image 2.0 Pro
- Alibaba Cloud
- Kotlin
- Kotlin Multiplatform
- JetBrains Koog
- Ktor
- Room
- SQLite
- Compose Multiplatform
- Docker
- Gradle

## Significant Updates During The Contest Period

Between May 26 and July 20, 2026, the project was substantially expanded and hardened:

- added the direct Qwen Cloud backend, Qwen-native model routing, tool-call compatibility, explicit
  cache markers, usage telemetry, and optional Qwen image generation;
- built the runnable CLI, benchmark harness, Docker image, authenticated remote dashboard, and
  persistent run archive;
- introduced the authoritative versioned Node Atlas and separate competitive Grader Atlas;
- added redacted worker views, exact leases, mortality records, contribution commits, and useful-exit
  telemetry;
- connected exact node evidence, blind grading, capture, delayed yields, progressive disclosure,
  Outlier shadow nodes, Atlas amendments, tool promotion, simulation records, and later-agent reuse;
- added causal contribution/cost/publication lineage, deterministic score replay, run manifests,
  and a privacy-preserving observability catalog;
- replaced arbitrary executable-test JSON with typed `sealed-tests-v3`, a pure compiler, exact
  historical regressions, and pre-activation executability checks;
- added acceptance profiles, immutable runnable distributions, an exclusive acceptance lock,
  admission control, startup rejection artifacts, source/binary drift checks, and typed Section
  18.2 metric export;
- moved complete final-output validation inside the bounded writer retry loop so publication fails
  closed without wasting available correction attempts.

## Challenges

The hardest part was not spawning agents. It was defining authority.

We had to make exact decisions about which state is canonical, what workers are allowed to see,
when grader criteria freeze, how a test definition becomes one deterministic execution plan, how
tool use influences later evidence, and what must be true before a run may call itself complete.

Provider behavior added another layer: tool calls, structured output, thinking mode, long contexts,
and retries do not compose reliably unless the runtime treats them as explicit contracts. Several
failed acceptance runs became permanent negative evidence and drove typed, fail-closed fixes rather
than looser parsing.

## Accomplishments

- A real multi-group society with specialised mortal agents and durable substrate state.
- Competitive worker and grader Atlases with progressive disclosure and exact evidence authority.
- Role-gated tools, bounded subprocess execution, source approval, and constitutional interception.
- Replayable result bundles with final-output lineage, score replay, typed metrics, and manifest
  verification.
- A Qwen Cloud backend with tool-enabled execution, structured output, context cache markers, token
  metadata, and image generation.
- Narrow Qwen Cloud benchmark wins reported alongside ties and large latency penalties.
- A Dockerized authenticated dashboard ready for Alibaba Cloud hosting.

## Benchmark Evidence

The strongest defensible Qwen Cloud result is deliberately narrow:

- On FRAMES questions 19–21, the EvoMolior society scored **2/3 strict exact** versus **1/3** for a
  direct `qwen3.7-plus` single-agent baseline. Both had evidence-path coverage `1.0`.
- On hard FRAMES question 537, EvoMolior scored **1/1** versus **0/1** for the direct Qwen baseline.

These samples are small and the society was materially slower. On MetaSyn task-24 and task-419
candidate subsets, the society tied direct Qwen baselines on objective metrics. Provider-cost
telemetry is unavailable in those retained reports. We therefore claim **auditable orchestration,
artifact structure, and narrow demonstrated gains**, not broad superiority.

## What We Learned

- More agents do not guarantee a better answer.
- The useful unit of work is an evidence-bearing task node, not a model message.
- A failed or unavailable result can be valid evidence when represented explicitly.
- Grading must be isolated and frozen before evidence reveal.
- Structured output needs a typed compiler/evaluator ABI; “valid JSON” is not enough.
- Trustworthy agent systems need negative evidence, exact replay, and an honest unavailable state.

## What's Next

- Finish the full multi-round Appendix-B qualification and reconcile its final metrics.
- Run the deferred matched iterative-single-agent comparison on the accepted exact task authority.
- Expand Qwen same-model comparisons across larger benchmark samples.
- Add optional cumulative run-credit ceilings and mandatory claim ledgers for evidence-heavy
  profiles.
- Qualify additional grader model families while retaining explicit independence labels.
- Continue hardening the Alibaba Cloud deployment and operator experience.

## Current Limitations

- Small benchmark wins do not establish broad agent-society superiority.
- Research-synthesis comparisons currently tie direct Qwen baselines and take much longer.
- Normal agents run in-process; Shuru microVM execution is an optional alternate branch. Generated
  tools do execute in bounded subprocesses.
- Publication contracts validate shape, provenance, and sealed tests; they do not guarantee factual
  truth.
- Cross-task learning is explicit through continuation routing, not automatic across every run.
- The full post-v72 multi-round Appendix-B acceptance is being finalized. The new matched comparison
  planned after it is intentionally not claimed in this submission.

## Links To Paste Before Submission

| Field | Value |
|---|---|
| Repository | `https://github.com/TreenD-D/EvoMolior` |
| Live Alibaba Cloud deployment | `REPLACE_WITH_PUBLIC_OR_JUDGE_ACCESSIBLE_URL` |
| Demo video | `REPLACE_WITH_PUBLIC_YOUTUBE_OR_VIMEO_URL` |

Verify every link in a logged-out incognito window before submitting.

## Thumbnail

Use `documentation/submission/assets/evomolior-thumbnail.png`.

> A dark, high-contrast 16:9 graphic with the EvoMolior “E” at the center of a glowing persistent
> knowledge core. Teal research/build/critic agents feed it from the left, while purple
> outlier/grader/archivist agents feed it from the right. Large text reads: “Agents die. Knowledge
> compounds.”

## Slide Deck

Upload `documentation/submission/EvoMolior_Pitch_Deck.pdf`. The editable, self-contained source is
`documentation/submission/EvoMolior_Pitch_Deck.html`.
