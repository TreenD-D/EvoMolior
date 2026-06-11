# EvoKammer Stuck Logs 3 Remediation Plan

## Goal

Fix the flow failure pattern visible in `documentation/logs/stuck_logs3.txt`:
- the critic / judge stages take far too long and stall the round lifecycle,
- some groups complete with zero meaningful work and spend almost no budget,
- the logs contain real runtime errors that are currently masked as normal completion,
- the grader group is active, but its progress is not visible enough to trust from the UI.

This plan is based on the log evidence, not on assumptions.

## Evidence Summary

### 1) Critic / judge is dominating wall clock

The post-round grader pipeline is taking minutes per round:
- criteria phase for `grader-0` took about 92s for `GraderBuilder` and 99s for `GraderResearcher`,
- round judging took about 301s for the `RoundJudge`,
- the judge then emitted all scores only near the end, which makes the whole phase look frozen until completion.

The log shows the grader pipeline is sequential in the expensive parts:
- criteria agents run one after another,
- judge work runs after the criteria review completes,
- the judge receives large canon snapshots and a large criteria context.

### 2) Some agents are completing with zero useful work

Several competing agents finish with:
- `spent=0.00`,
- `actions=0`,
- result text like `The previous assistant response was empty. Respond with either a tool call or a concise answer.`

That means the empty-response recovery path is allowing the agent to terminate without producing actual work. This is not a harmless success path for the main civilisation agents.

### 3) There is a hard prompt/tool contract mismatch

The main agent prompt advertises `read_run_artifact`, but the tool is only present when uploaded input artifacts exist in the boot context.

In `stuck_logs3.txt`, at least one Researcher tries to call `read_run_artifact({"listOnly":true})` and aborts with:
- `Tool with name 'read_run_artifact' not found in the tool registry`

This is a real runtime bug, not a model quirk.

### 4) Grader activity is present but not legible enough

The engine does emit grader events:
- `GraderRoundStarted`
- `GraderRoundCompleted`
- `EngineProgress` phases like `grader_criteria_start`, `grader_judge_start`, `grader_criteria_review`

But from the user perspective, the grader can still feel invisible because:
- the UI only shows the grader section after the first `GraderRoundStarted`,
- the most important sub-phase transitions live in generic progress events,
- there is no strong summary of whether the grader is in criteria, review, judging, or stalled state.

## Evidence Matrix

| Log signature / symptom | Call site | Broken invariant | Fix owner |
|---|---|---|---|
| `criteria-graderresearcher... completed in 99233ms` | `GraderRunner.runCriteriaPhase` → `GraderAgentFactory.createCriteria` | Criteria phase should be bounded and visibly progressing, not look frozen for >1 minute per agent | Runtime + grading architecture |
| `criteria-graderbuilder... completed in 92605ms` | `GraderRunner.runCriteriaPhase` → `GraderAgentFactory.createCriteria` | Second criteria stage should not require a full second giant prompt pass if the rubric is already available | Runtime + grading architecture |
| `round-judge... completed in 301156ms` | `GraderRunner.runJudgingPhase` → `GraderAgentFactory.createRoundJudge` | Judge should either finish within a predictable budget or emit explicit slow-path telemetry | Runtime + grading architecture |
| `Tool with name 'read_run_artifact' not found in the tool registry` | `EvoKammerAgentFactory.create` + `RolePromptBuilder.build` | Prompt must not advertise unavailable tools in the current agent context | Prompt debt + agent factory |
| `spent=0.00 remaining=... actions=0 result=The previous assistant response was empty...` | `EmptyAssistantResponseRecoveryProcessor` + agent run loop | Empty-response recovery must not count as healthy completion for task-bearing agents | Runtime policy |
| Grader active in logs but not obvious in UI | `EvoKammerViewModel`, `EvoKammerDashboardScreen`, `EvoKammerLogViewerScreen` | Grader phases must be surfaced as first-class state in both dashboard and log viewer | UI surfacing |

## Root Causes

### A) Grader pipeline is too heavy for the current architecture

The current grader architecture combines:
- large prompts,
- large canon snapshots,
- a multi-stage sequential pipeline,
- a high-cost model,
- and a long timeout budget.

That combination is enough to make the judge feel stuck even when it is technically making progress.

Baseline to capture before changing this:
- criteria agent wall-clock duration
- judge wall-clock duration
- time between `GraderRoundStarted` and `GraderRoundCompleted`
- time between `grader_criteria_start` and `grader_criteria_done`
- time between `grader_judge_start` and `grader_judge` / `GraderRoundCompleted`

### B) Empty-response recovery is too permissive for task-bearing agents

The recovery processor is useful for model/provider flakiness, but for production agents it currently allows a no-progress completion path.

That hides the difference between:
- a real but short successful response,
- and a dead-end response that should have been retried or failed.

### C) The prompt surface advertises capabilities that are not always available

The `RolePromptBuilder` tool reminder tells agents to use `read_run_artifact`, but the agent factory only registers that tool when uploaded artifacts exist.

This creates a mismatch between:
- the textual contract,
- the actual tool registry,
- and the agent's reasoning path.

Chosen invariant for the next fix:
- `read_run_artifact` must either be registered for every task-bearing agent that is told about it, or the prompt must not advertise it in that agent context.
- Prefer the safer implementation: if the prompt mentions `read_run_artifact`, register a usable tool even when there are no uploaded artifacts, and make it return an explicit empty/none message rather than a registry error.

### D) Grader observability is underpowered

The grader is doing work, but the dashboard does not make the active phase obvious enough to explain:
- which grader phase is running,
- whether it is alive,
- how long the current phase has been running,
- and whether it has produced any scores yet.

## Fix Plan

### Phase 1: Stop the visible failures

1. Make `read_run_artifact` availability consistent.
   - Register `ReadRunArtifactTool` even when there are no uploaded artifacts, and make the tool return an explicit empty/none message.
   - Do not keep the current hybrid state where the prompt advertises the tool but the registry sometimes does not contain it.
   - Add a regression for the no-upload-artifacts case so the tool remains callable and does not fail at lookup time.

2. Make empty-response recovery bounded instead of silently successful for task-bearing agents.
   - Keep the recovery processor for provider/model flakiness.
   - Add a second-level rule for production agents: if the agent finishes after an empty-response recovery without any real action, treat it as a failed attempt, not a normal completion.
   - Apply this more strictly to Researcher / Builder / Critic / grader roles than to purely conversational council roles.

3. Add a hard “no-progress” signal to the logs.
   - If an agent ends with `actions=0` and no staging / fact / score output, emit an explicit warning that the agent made no productive progress.
   - This should be visible in the UI and in the log export, not buried in a debug line.

### Phase 2: Make the grader pipeline visible and bounded

4. Expose grader phase state explicitly in the UI.
   - Add a clearer grader status model: `criteria`, `criteria_review`, `judge`, `complete`, `failed`, `stalled`.
   - Surface phase duration and score count on the grader card.
   - Show a live “last progress” line for `grader-0` instead of only start/complete summaries.
   - Wire the same phase labels into both the dashboard card and the log viewer so `grader_criteria_*`, `grader_judge_*`, `GraderRoundStarted`, and `GraderRoundCompleted` are visible in both places.

5. Add phase-level timeout and heartbeat instrumentation.
   - Emit periodic heartbeats while criteria and judge are running.
   - Fail or retry when a phase exceeds a clearly defined budget.
   - The current minute-scale silence makes the run look broken even when it is still executing.

6. Reduce the judge prompt surface.
   - Trim criteria context aggressively.
   - Keep only the latest rubric blocks needed for judging.
   - Split or summarise canon snapshots before they reach the judge.

### Phase 3: Reduce grader latency at the architecture level

7. Rework the criteria phase to be cheaper.
   - Criteria generation should not require two large sequential LLM calls if one can produce a usable rubric.
   - Consider merging `GraderResearcher` and `GraderBuilder` outputs into a single bounded pass.
   - If both roles stay, make the second role operate on a compressed rubric output rather than the full raw prompt again.

8. Rework the round judge to avoid a single long monolith prompt.
   - Consider per-dimension or batched scoring.
   - Consider a smaller or faster model for rubric application.
   - For dimensions that can be computed deterministically from the snapshot, do that outside the model.

9. Introduce a “slow judge” fallback policy.
   - If the judge is still running after a threshold, emit an explicit warning and switch to a narrower scoring path rather than letting the round appear stuck.

### Phase 4: Verify with targeted tests

10. Add a regression for the `read_run_artifact` contract.
    - Cover both cases:
      - uploaded artifacts exist,
      - no artifacts exist.
    - The tool/prompt behavior should match in both cases.
    - The no-upload-artifacts case must still allow the agent to call the tool without a registry error.

11. Add a regression for no-progress agent completion.
    - Prove that an empty-response recovery does not count as healthy completion for task-bearing agents.
    - The failure mode should be explicit in logs and test assertions.
    - Reproduce the zero-budget / zero-action completion path deterministically so the rule is not inferred from a live outage.

12. Add a regression for grader visibility.
    - The dashboard should show the grader as active during criteria and judging.
    - There should be a test for the grader status text changing as the phase advances.

13. Add a targeted integration replay for the current log pattern.
    - Replay a run with:
      - at least one uploaded run artifact,
      - a grader round,
      - and a task-bearing agent that would otherwise try `read_run_artifact`.
    - Verify the run no longer aborts on the missing tool and no longer appears frozen during grading.

## File Areas Likely To Change

- `evokammer/agents/src/main/kotlin/com/achulkov/evokammer/agents/factory/EvoKammerAgentFactory.kt`
- `evokammer/agents/src/main/kotlin/com/achulkov/evokammer/agents/factory/RolePromptBuilder.kt`
- `evokammer/agents/src/main/kotlin/com/achulkov/evokammer/agents/factory/EmptyAssistantResponseRecoveryProcessor.kt`
- `evokammer/api/src/main/kotlin/com/achulkov/evokammer/api/grader/GraderRunner.kt`
- `evokammer/api/src/main/kotlin/com/achulkov/evokammer/api/service/EvoKammerRunService.kt`
- `composeApp/src/jvmMain/kotlin/com/achulkov/evomolior/evokammer/viewmodel/EvoKammerViewModel.kt`
- `composeApp/src/jvmMain/kotlin/com/achulkov/evomolior/evokammer/screens/EvoKammerDashboardScreen.kt`

## Validation Gates

The plan is not done until all of these are true:
- no prompt/tool mismatch remains for `read_run_artifact`,
- no task-bearing agent can complete with `actions=0` and be treated as healthy,
- the grader status is visible in the UI while it is running,
- the grader phase duration is reduced or at least bounded with visible heartbeats,
- the full touched-module sweep passes again after the fixes.

## Parallelization Audit

The remaining bottlenecks are the places where the runtime was still queueing work that can be safely overlapped:
- `GraderRunner.runCriteriaPhase` can run different grader groups in parallel because each group writes to its own grader substrate and keeps its own `previousCriteria` chain.
- `GraderRunner.runJudgingPhase` can run different grader groups in parallel, and each judge can export competing-group canon snapshots in parallel before it launches the RoundJudge.
- The legacy `runGraderRound` path is still sequential by group and target, but it is now a compatibility path rather than the primary execution flow.

The parts that remain intentionally sequential are the ones with real intra-group dependencies:
- `GraderResearcher` before `GraderBuilder` because the latter refines the former's output.
- the RoundJudge agent after the criteria digest has been assembled.
