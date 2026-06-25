# Research Synthesis Benchmark Hardening - 2026-06-24

## Purpose
This document preserves the hardening iteration and benchmark result outside `documentation/tmp`.

The previous benchmark showed EvoKammer fast CLI losing to a cheap single-agent baseline on a MindVortex ADHD external-cognition product strategy task. The user requested hardening, a more appropriate multi-group/multi-round configuration, stacked refinement, and a re-evaluation.

## Hardening Implemented
- Final deliverable prompts now permit a bounded evidence check before writing: at most two quick reads, including source-pack/uploaded-context verification.
- Final deliverable prompts now require source fidelity for research, strategy, benchmark, policy, medical-adjacent, legal-adjacent, and evidence-synthesis outputs.
- HTML final-output validation now rejects source-grounded objectives that publish without a visible `Source-grounded evidence notes` or `Evidence traceability` section.
- HTML final-output validation now rejects placeholder evidence markers such as `(Source)` or `Source: research_synthesis_source_pack.md` when source fidelity is required.
- Default CLI fast follow-up now explicitly says not to compress or shorten a satisfactory prior deliverable and to preserve evidence notes, tables, caveats, and inference labels.

## Code Paths Changed
- `evokammer/agents/src/main/kotlin/com/achulkov/evokammer/agents/factory/RolePromptBuilder.kt`
- `evokammer/api/src/main/kotlin/com/achulkov/evokammer/api/service/EvoKammerRunService.kt`
- `evokammer/cli/src/main/kotlin/com/achulkov/evokammer/cli/CliPipeline.kt`

Regression tests were added/updated in:

- `evokammer/agents/src/test/kotlin/com/achulkov/evokammer/agents/factory/RolePromptBuilderTest.kt`
- `evokammer/api/src/test/kotlin/com/achulkov/evokammer/api/service/EvoKammerRunServiceEndToEndFlowTest.kt`
- `evokammer/cli/src/test/kotlin/com/achulkov/evokammer/cli/CliArgumentsTest.kt`

## Live Runs
- Heavy run: `benchmark-evokammer-hardened-g3r5-20260624-110821`
  - Shape: `-fast -rounds 5 -group-count 3 -max-agents 3 -budget-override 260`
  - Result: completed, but final artifact still missed evidence traceability. Prompt-only hardening was insufficient.
- Stacked refinement before gate: `benchmark-evokammer-stacked-refine-20260624-115038`
  - Shape: `-fast -rounds 3 -group-count 3 -max-agents 3 -budget-override 180`
  - Result: completed, but published essentially the same source-weak artifact.
- Gated stacked refinement: `benchmark-evokammer-stacked-gated-20260624-123125`
  - Shape: `-fast -rounds 3 -group-count 3 -max-agents 3 -budget-override 180`
  - Result: completed with a much stronger primary artifact.
  - Best artifact: `documentation/tmp/research_synthesis_benchmark_hardening/runs/benchmark-evokammer-stacked-gated-20260624-123125/outputs/final-article.html`

## Best Artifact Metrics
Gated primary artifact:

- 43,242 bytes.
- About 3,414 visible words.
- 19 headings.
- 7 tables.
- Footer present.
- Source-grounded evidence notes present.
- Specific source labels present, including `Digital interventions review`, `External cognition design`, `Neurodivergent-aware productivity`, `AI cognitive offloading risk`, and `User pain signals`.
- `Inference` labels present.
- No `proven` claims detected.

The automatic fast-follow-up compressed this strong primary artifact down to about 1,165 visible words. That behavior prompted the follow-up prompt hardening noted above. The benchmark evaluation therefore used the primary gated artifact, not the compressed follow-up.

## Evaluation
- Single-agent baseline: `single-agent-openai_gpt-oss-120b-20260624-102108`
- Judge: `deepseek/deepseek-v4-flash`
- Evaluation artifact: `documentation/tmp/research_synthesis_benchmark_hardening/evaluations/latest-hardened-evaluation.json`
- Blind mapping:
  - Output A: single-agent baseline
  - Output B: hardened EvoKammer

| Dimension | Single-agent | Hardened EvoKammer |
|---|---:|---:|
| Evidence fidelity | 4 | 5 |
| Cross-domain synthesis | 4 | 5 |
| Novelty and usefulness | 3 | 4 |
| Operationalization | 4 | 5 |
| ADHD fit and ethics | 4 | 5 |
| Clarity and artifact quality | 4 | 5 |
| Total | 23 | 29 |

Judge winner: hardened EvoKammer.

## Tests
Passed targeted regression suite:

```sh
./gradlew --no-daemon --no-build-cache \
  :evokammer:agents:test --tests com.achulkov.evokammer.agents.factory.RolePromptBuilderTest \
  :evokammer:api:test --tests com.achulkov.evokammer.api.service.EvoKammerRunServiceEndToEndFlowTest.browser\ final\ writer\ retries\ source\ grounded\ html\ without\ evidence\ notes \
  --tests com.achulkov.evokammer.api.service.EvoKammerRunServiceEndToEndFlowTest.browser\ final\ writer\ retries\ html\ that\ violates\ objective\ minimum\ word\ count \
  :evokammer:cli:test --tests com.achulkov.evokammer.cli.CliArgumentsTest.fast\ browser\ run\ adds\ default\ follow\ up\ stage\ and\ aggregates\ published\ outputs
```

## Conclusion
After hardening and a more appropriate run shape, EvoKammer produced a better benchmark artifact than the cheap single-agent baseline. The decisive fix was not just increasing rounds/groups; it was enforcing source fidelity at final-output validation time. Prompt-only hardening and stacked refinement did not reliably fix the source-fidelity problem.

Remaining weak spots:

- Grader tool-call compliance is still weak; neutral fallback remains common.
- Follow-up compression is now prompt-hardened but not yet revalidated live after the final prompt change.
- One judge pass is useful directional evidence, not a statistically robust benchmark. Future comparisons should use multiple blind judges and a single-agent draft-critique-revise baseline.
