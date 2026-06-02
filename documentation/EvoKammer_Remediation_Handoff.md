# EvoKammer Remediation Handoff

## What was verified from the log
- `documentation/exec_logs.txt` showed three recurring runtime failures:
  - `Grok 4 Fast is deprecated`
  - `AIAgentStuckInTheNodeException` from `nodeSendToolResult` with `Assistant(parts=[], ...)`
  - `AIAgentMaxNumberOfIterationsReachedException` at the 25-step cap
- The log also showed the grader judge path and critic path were using deprecated / brittle behavior in real runs.

## Fixes already implemented
- `evokammer/core/src/main/kotlin/com/achulkov/evokammer/core/budget/BudgetConfig.kt`
  - Raised `maxIterations` from `25` to `40`.
  - Updated `BudgetConfigTest` accordingly.
- `evokammer/api/src/main/kotlin/com/achulkov/evokammer/api/service/RunLogSignatureScanner.kt`
  - Scans generated log artifacts for:
    - `Grok 4 Fast is deprecated`
    - `AIAgentStuckInTheNodeException`
    - `AIAgentMaxNumberOfIterationsReachedException`
    - `Unexpected response: no tool calls and no content`
- `evokammer/api/src/main/kotlin/com/achulkov/evokammer/api/service/RunLogSignatureScannerMain.kt`
  - CLI entrypoint for scanning a supplied log file path.
- `evokammer/api/build.gradle.kts`
  - Added Gradle task `:evokammer:api:scanRunLogSignatures -PrunLogPath=/path/to/log`.
- `.github/workflows/build.yml`
  - Added a `scan-run-logs` job that generates a CI log artifact from API test output and scans it with `scanRunLogSignatures`.
- `evokammer/agents/src/main/kotlin/com/achulkov/evokammer/agents/factory/EmptyAssistantResponseRecoveryProcessor.kt`
  - Normalizes empty assistant turns into a retryable text response.
- Wired the recovery processor into:
  - `EvoKammerAgentFactory`
  - `GraderAgentFactory`
  - `CriticReviewAgent`
  - `CouncilRunner`
  - `DifferentialPrivacyTransform`
- Added a shared blocking-error abort policy so tool validation / tool-call failures kill the agent immediately and preserve the group's remaining budget:
  - `evokammer/core/src/main/kotlin/com/achulkov/evokammer/core/contract/BlockingAgentAbortException.kt`
  - `evokammer/agents/src/main/kotlin/com/achulkov/evokammer/agents/factory/BlockingAgentFailurePolicy.kt`
  - `LifecycleManager` now treats blocking/runtime exceptions as killed exits and refunds the full agent allocation.
- Added regression coverage:
  - `EmptyAssistantResponseRecoveryProcessorTest`
  - `EmptyAssistantResponseRecoveryAgentIntegrationTest`
  - `RunLogSignatureScannerTest`
  - `LifecycleManagerTest` blocking-abort regression

## Verification run
- Passed:
  - `:evokammer:core:test --tests com.achulkov.evokammer.core.BudgetConfigTest`
  - `:evokammer:api:test --tests com.achulkov.evokammer.api.service.RunLogSignatureScannerTest --tests com.achulkov.evokammer.api.grader.GraderRunnerFailureTelemetryTest`
  - `:evokammer:agents:test --tests com.achulkov.evokammer.agents.factory.EmptyAssistantResponseRecoveryAgentIntegrationTest --tests com.achulkov.evokammer.agents.factory.EmptyAssistantResponseRecoveryProcessorTest --tests com.achulkov.evokammer.agents.factory.EvoKammerAgentFactoryTest --tests com.achulkov.evokammer.agents.factory.OpenRouterModelResolverTest --tests com.achulkov.evokammer.agents.factory.CriticReviewAgentTest`
  - `:evokammer:orchestration:test --tests com.achulkov.evokammer.orchestration.lifecycle.LifecycleManagerTest --tests com.achulkov.evokammer.orchestration.lifecycle.CriticPipelineSelfReviewTest --tests com.achulkov.evokammer.orchestration.lifecycle.CriticPipelineKgConsistencyTest`
  - `:evokammer:competition:test --tests com.achulkov.evokammer.competition.ModelSelectionConfigRuntimeDefaultsTest --tests com.achulkov.evokammer.competition.ScoringPipelineIntegrationTest --tests com.achulkov.evokammer.competition.leaks.LeakOracleTest`
  - `:evokammer:api:scanRunLogSignatures -PrunLogPath=<clean temp log>` passes
  - `:evokammer:api:scanRunLogSignatures -PrunLogPath=<bad temp log>` fails as intended
  - `:evokammer:competition:test --tests com.achulkov.evokammer.competition.leaks.DifferentialPrivacyTransformResponseRecoveryTest --tests com.achulkov.evokammer.competition.leaks.LeakOracleTest`
  - `:evokammer:competition:test`
  - `:evokammer:orchestration:test --tests com.achulkov.evokammer.orchestration.lifecycle.LifecycleManagerTest.blocking agent abort is treated as a kill and refunds the full group allocation`
  - `:evokammer:agents:test --tests com.achulkov.evokammer.agents.factory.CouncilRunnerResponseRecoveryTest --tests com.achulkov.evokammer.agents.factory.EmptyAssistantResponseRecoveryAgentIntegrationTest --tests com.achulkov.evokammer.agents.factory.EmptyAssistantResponseRecoveryProcessorTest`

## Remaining open work
- The broader remediation plan still has other items outside this slice.
- If continuing, next useful steps are:
  - Audit any remaining AIAgent builder call sites for response processor coverage if new ones are added.
  - Continue the remaining remediation-plan items in `documentation/EvoKammer_Remediation_Plan.md`.

## Core Flow Recheck Status (2026-05-27)

The current core flow has been rechecked through the implementation path, not just via logs:
- Run setup now exposes run-context file upload in both the normal and test launch screens.
- Uploaded files are converted to `RunConfig.contextArtifacts`, materialized by `EvoKammerRunService`, seeded into the initial substrate KB, and sealed into the starting-field archive.
- The live monitor now mirrors `AgentSpawned` and `AgentDied` into the visible group summaries, so `activeAgents` no longer stays at 0 while agents are actually running.
- `RunLogSignatureScanner` now tracks OpenRouter `502` provider failures in addition to the earlier stuck-node / empty-response signatures.

Verified commands during the recheck:
- `./gradlew :composeApp:compileKotlinJvm`
- `./gradlew :composeApp:compileTestKotlinJvm`
- `./gradlew :composeApp:jvmTest --tests com.achulkov.evomolior.evokammer.viewmodel.EvoKammerViewModelActiveAgentCountTest`
- `./gradlew :evokammer:api:test --tests com.achulkov.evokammer.api.service.RunLogSignatureScannerTest --tests com.achulkov.evokammer.api.service.EvoKammerRunServiceEndToEndFlowTest`

Current caveat:
- The new `stuck_logs2.txt` still shows repeated OpenRouter `502` provider errors in round 0, so the runtime can still look stalled when the upstream provider is failing even though the UI counters and upload flow are correct.

## Latest Verification (2026-05-28)

After wiring the shared OpenRouter provider retry/abort policy through the remaining top-level run paths, the targeted regressions are green:
- `./gradlew :composeApp:compileKotlinJvm :composeApp:compileTestKotlinJvm :composeApp:jvmTest --tests com.achulkov.evomolior.evokammer.viewmodel.EvoKammerViewModelActiveAgentCountTest --tests com.achulkov.evomolior.util.RunInputArtifactPickerTest`
- `./gradlew :evokammer:api:test --tests com.achulkov.evokammer.api.service.RunLogSignatureScannerTest --tests com.achulkov.evokammer.api.service.EvoKammerRunServiceEndToEndFlowTest`
- `./gradlew :evokammer:agents:test --tests com.achulkov.evokammer.agents.factory.OpenRouterProviderFailurePolicyTest`

What this confirms:
- The UI launch flow still accepts run-context uploads and the monitor reflects spawn/death events.
- Uploaded context is materialized into the sealed `StartingField` archive and visible in the results bundle.
- Repeated OpenRouter `502` provider failures are now classified by the shared policy and exercised in unit coverage.
- The provider-failure replay is also covered at a real orchestration entry point via `CouncilRunnerProviderFailureReplayTest`.
- The Compose JVM smoke test harness is back in alignment after moving the Compose plugin/runtime line to `1.10.0-rc01`.

What still remains unverified in a live provider-failure replay:
- A real OpenRouter-backed run that actually hits the repeated `502` condition after the policy wiring, to confirm the live abort path is still behaving as intended outside tests.

## Next Live Replay Checklist

1. Launch the desktop control panel with `./gradlew :composeApp:jvmRun`.
2. Start the same kind of OpenRouter-backed test run that produced `documentation/logs/stuck_logs2.txt`, including a run-context upload so the first-round startup path is identical to the reported failure case.
3. If the run produces a log artifact, scan it with `./gradlew :evokammer:api:scanRunLogSignatures -PrunLogPath=/absolute/path/to/log`.
4. Confirm the repeated `502` case now ends as a blocking abort instead of leaving the round in a lingering stuck state.

## Replay Evidence

Verified deterministic replay command:
- `./gradlew :evokammer:agents:test --tests com.achulkov.evokammer.agents.factory.CouncilRunnerProviderFailureReplayTest --tests com.achulkov.evokammer.agents.factory.CouncilRunnerBlockingAbortTest --tests com.achulkov.evokammer.agents.factory.OpenRouterProviderFailurePolicyTest`

Verified full touched-module sweep:
- `./gradlew :composeApp:jvmTest :evokammer:api:test :evokammer:agents:test :evokammer:competition:test :evokammer:orchestration:test`
