# Codex GPT Backend Tier 3/4 Benchmark Runs - 2026-07-09

Goal: repeat the Codex GPT backend benchmark process for Tier 3 and Tier 4 benchmark families: launch live Codex/ChatGPT mini-model runs, verify `gpt-5.4-mini` and low reasoning, evaluate produced artifacts/results, update benchmark comparison docs, and fix concrete issues found.

User intent: after Codex mini Tier 0/1 slices completed and were added to the benchmark ladder, run the same style of evidence pass for higher-tier benchmarks.

Constraints/invariants:
- Backend: `codex-chatgpt` / `CODEX_CHATGPT`.
- Model: `gpt-5.4-mini` for all roles/stages.
- Reasoning: Codex CLI adapter uses `model_reasoning_effort="low"`.
- Codex is text-only compatibility mode; do not expect Koog tool calls.
- Do not call `codex login` or device auth; only status/preflight is allowed.
- Do not print tokens.
- Use existing benchmark definitions/artifacts where possible; do not invent new scoring standards.

Initial plan:
1. Locate existing Tier 3 and Tier 4 benchmark commands/evaluation artifacts.
2. Select the smallest live Codex-compatible Tier 3 and Tier 4 slices.
3. Run them with `codex-chatgpt`, evaluate outputs using existing evaluators or documented manual checks.
4. Fix concrete runtime/prompting/reporting issues found.
5. Add Codex Tier 3/4 rows to `documentation/benchmarks/EvoMolior_Benchmark_Ladder.md` and record all evidence here.

## Final clean runs

Both final evidence runs were executed inline, not through the daemon, so the Codex temp-directory
override was definitely present in the runtime environment:

```sh
EVOMOLIOR_DISABLE_DAEMON=1 \
EVOMOLIOR_CODEX_TMPDIR=/Volumes/CrucialX9_1TB/AStudioProjects/EvoMolior/.evomolior-codex-tmp \
evomolior run ...
```

### Tier 3 - DeepResearch Bench prompt 62

- Run root: `results/tier3-official-deepresearchbench-codex-final-20260709/tier3-drb-q62-codex-final-20260709`
- Pipeline run id: `tier3-drb-q62-codex-final-20260709`
- Stages: primary `COMPLETED` in `359580` ms; report `COMPLETED` in `177311` ms.
- Published output: `outputs/report.md`
- Output hash: `ea16756401c80b4a7fcb37d62e89769a6dc72f565a403add1bf4c941ac837055`
- Output size: `19764` bytes, `372` lines.
- Artifact audit: all required sections present: executive summary, technical landscape, comparison table, claim ledger, citation map, limitations, and open questions.
- Claim ledger rows: `10`.
- Caveat handling: report explicitly states the run had no retrieved papers / live source artifacts beyond the benchmark prompt and labels unsupported prior-knowledge claims as unverified.
- Model audit: no `deepseek`, `qwen/qwen`, or `qwen3` hits in the final run logs; Genesis logs assign every role to `gpt-5.4-mini`.
- Health caveat: final logs still include two `RUN FAILED` entries from cancelled Builder jobs during primary-stage final-deliverable retries. The log sequence shows attempts 1 and 2 timing out, being killed via `run_termination`, and a third Builder completing successfully. Treat these as a recovered convergence/final-writer retry artifact for this run, not as a fatal benchmark failure. Both pipeline stages completed and `report.md` was published. There were no `No space left` or `ABORTED` hits.
- Evaluation artifact: `documentation/agent-notes/tier3-deepresearchbench-q62-codex-final-20260709-evaluation.json`.

This is an artifact-completeness result only. The official DeepResearch Bench rubric evaluator is not
wired in this workspace, so there is no official quality score.

### Tier 4 - MetaSyn task 24 candidate subset

- Run root: `results/tier4-real-metasyn-task24-codex-simple-clean-20260709/tier4-real-metasyn-task24-codex-simple-clean-20260709`
- Pipeline run id: `tier4-real-metasyn-task24-codex-simple-clean-20260709`
- Stages: primary `COMPLETED` in `71313` ms; systematic-review-pack `COMPLETED` in `167452` ms.
- Published output: `outputs/systematic_review_pack.md`
- Output hash: `9e6593a25e0262014b287d11d4dd92b4ac2793c55f5e9da9ca44871ea1e38a20`
- Parsed included corpus IDs: `4565`, `4566`.
- Parsed excluded corpus IDs: `4558`, `4559`, `4567`, `4568`, `4570`, `4571`.
- Deterministic candidate-subset metrics: included-study recall `0.6666666666666666`, included-study precision `1.0`, hard-negative rejection accuracy `1.0`, candidate-level accuracy `0.875`, synthesis direction match `true`.
- Error pattern: false negative `4567`, no false positives.
- Model audit: no `deepseek`, `qwen/qwen`, `qwen3`, `RUN FAILED`, `ABORTED`, or `No space left` hits in the final clean run logs; Genesis logs assign every role to `gpt-5.4-mini`.
- Prediction artifact: `documentation/agent-notes/tier4-real-metasyn-task24-codex-simple-clean-20260709-predictions.json`.
- Evaluation artifact: `documentation/agent-notes/tier4-real-metasyn-task24-codex-simple-clean-20260709-evaluation.json`.

This is a candidate-subset reconstruction using the uploaded task-24 candidates, not a full MetaSyn
leaderboard run.

## Issues found and fixed

- Codex text-only runs could not inspect uploaded artifacts through Koog tools. Fixed by embedding
  bounded uploaded artifact text into Codex/text-only run objectives.
- Codex temp files initially used the system temp volume and large runs hit `No space left on device`.
  Fixed by adding `EVOMOLIOR_CODEX_TMPDIR` support in the Codex adapter and rerunning large tasks
  with the temp directory on the project volume.
- Codex model overrides initially covered only the default runtime assignment map, allowing objective
  routing roles such as Inspector/Educator/Connector to retain Qwen/OpenRouter model ids in Genesis
  logs. Fixed by adding a complete `Role.entries` assignment map to `gpt-5.4-mini` and wiring normal
  CLI and benchmark paths to use it for `codex-chatgpt`.

Verification after these code changes:

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 17.0.12) ./gradlew --no-daemon --no-configuration-cache \
  :engine:core:compileKotlin \
  :engine:agents:compileKotlin \
  :engine:cli:compileKotlin \
  :engine:cli:test --tests '*CliArgumentsTest*' \
  :engine:agents:test --tests '*LlmExecutorFactoryTest*' \
  :engine:cli:installDist
```

Result: `BUILD SUCCESSFUL`.
