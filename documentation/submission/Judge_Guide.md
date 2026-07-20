# Judge Guide

## Understand The Project In 90 Seconds

Start with the self-contained
[`EvoMolior_Architecture.html`](EvoMolior_Architecture.html) layered deep dive.

1. EvoMolior is a **multi-agent runtime**, not a chatbot.
2. Agents are intentionally short-lived; reviewed KB, KG, tools, evidence, tests, dead ends, and
   run lineage persist.
3. Multiple worker societies compete on the same sealed task.
4. Separate grader societies evaluate exact node evidence.
5. Progressive disclosure and capture determine what groups learn and own next.
6. Final publication is contract- and test-gated, then archived for replay.

## Fastest Code Inspection Path

| Question | File or package |
|---|---|
| Where is Qwen Cloud connected? | [`LlmExecutorFactory.kt`](../../evomolior/agents/src/main/kotlin/com/achulkov/evomolior/agents/factory/LlmExecutorFactory.kt) |
| How are Qwen tool calls and structured output handled? | [`DashscopeToolCompatibleLLMClient.kt`](../../evomolior/agents/src/main/kotlin/com/achulkov/evomolior/agents/factory/DashscopeToolCompatibleLLMClient.kt) |
| Where are model IDs defined? | [`ModelSelectionConfig.kt`](../../evomolior/core/src/main/kotlin/com/achulkov/evomolior/core/config/ModelSelectionConfig.kt) |
| Where is Qwen image generation implemented? | [`QwenImageGenerationService.kt`](../../evomolior/api/src/main/kotlin/com/achulkov/evomolior/api/service/QwenImageGenerationService.kt) |
| Where is the society orchestrated? | [`EvoMoliorRunService.kt`](../../evomolior/api/src/main/kotlin/com/achulkov/evomolior/api/service/EvoMoliorRunService.kt) |
| Where are Worker/Grader Atlases defined? | [`core/atlas`](../../evomolior/core/src/main/kotlin/com/achulkov/evomolior/core/atlas/) and [`core/grader`](../../evomolior/core/src/main/kotlin/com/achulkov/evomolior/core/grader/) |
| Where are sealed tests compiled/evaluated? | [`GeneralTestCompiler.kt`](../../evomolior/core/src/main/kotlin/com/achulkov/evomolior/core/council/GeneralTestCompiler.kt), [`SealedTestSuiteV3Compiler.kt`](../../evomolior/orchestration/src/main/kotlin/com/achulkov/evomolior/orchestration/council/SealedTestSuiteV3Compiler.kt), and [`SealedTestSuiteEvaluator.kt`](../../evomolior/api/src/main/kotlin/com/achulkov/evomolior/api/service/SealedTestSuiteEvaluator.kt) |
| Where is grading isolated? | [`ScoringWorkerProcess.kt`](../../evomolior/api/src/main/kotlin/com/achulkov/evomolior/api/service/ScoringWorkerProcess.kt) and [`ScoringWorkerServer.kt`](../../evomolior/api/src/main/kotlin/com/achulkov/evomolior/api/service/ScoringWorkerServer.kt) |
| Where is the remote UI? | [`cli/remote`](../../evomolior/cli/src/main/kotlin/com/achulkov/evomolior/cli/remote/) |
| Where are claims and caveats maintained? | [`EvoMolior_Benchmark_Ladder.md`](../benchmarks/EvoMolior_Benchmark_Ladder.md) |

## Build

Requirements:

- JDK 17
- Qwen Cloud API key for a live Qwen run

```bash
./gradlew :engine:cli:installDist
```

## Minimal Qwen Cloud Smoke

```bash
mkdir -p /tmp/evomolior-input /tmp/evomolior-output
echo 'Produce a concise, evidence-grounded decision memo.' \
  > /tmp/evomolior-input/instructions.txt

./evomolior/cli/build/install/evomolior/bin/evomolior run \
  -promptfile /tmp/evomolior-input/instructions.txt \
  -input /tmp/evomolior-input \
  -output /tmp/evomolior-output \
  -llm-backend qwen-cloud \
  -qwen-api-key "$QWEN_API_KEY" \
  -fast \
  -rounds 1 \
  -group-count 1 \
  -max-agents-per-group 2 \
  -run-id judge-qwen-smoke
```

Inspect:

```text
/tmp/evomolior-output/judge-qwen-smoke/
  summary.json
  results.zip
  outputs/
  debug-log.txt
```

## Remote Dashboard

```bash
EVOMOLIOR_DASHBOARD_USERNAME=judge \
EVOMOLIOR_DASHBOARD_PASSWORD='replace-with-a-long-random-password' \
./evomolior/cli/build/install/evomolior/bin/evomolior serve \
  --host 127.0.0.1 \
  --port 8080 \
  --data-dir /tmp/evomolior-dashboard \
  --cli-bin ./evomolior/cli/build/install/evomolior/bin/evomolior
```

The dashboard exposes bounded structural status, metrics, timelines, benchmark results, and safe
artifact previews. Raw prompts, model context, hidden grader state, and sealed competitor-private
details are not sent to the browser.

## What To Verify In A Run

- backend and exact model assignments;
- multiple role or group identities;
- staged, promoted, rejected, and graded work;
- Qwen tool calls or explicit no-tool paths;
- final receiver artifact;
- `run-metrics-v1.json` availability states;
- score replay and manifest verification;
- explicit limitations rather than fabricated values.

## Honest Evaluation Boundary

The maintained Qwen evidence shows small FRAMES wins and research-synthesis ties with materially
higher latency. See [`Evidence_and_Claims.md`](Evidence_and_Claims.md). EvoMolior's strongest
validated advantage is inspectable coordination and durable evidence—not a claim that adding agents
always improves accuracy.
