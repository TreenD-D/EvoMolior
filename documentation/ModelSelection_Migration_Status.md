# Model Selection Migration Status

Updated: 2026-05-26

## Completed

- Canonical model policy added in `evokammer/core/src/main/kotlin/com/achulkov/evokammer/core/config/ModelSelectionConfig.kt`.
- Expensive models are explicit opt-ins only.
- Cheap defaults now cover runtime roles, council, grader, critic pipeline, and leak transform.
- Shared resolver added in `evokammer/agents/src/main/kotlin/com/achulkov/evokammer/agents/factory/OpenRouterModelResolver.kt`.
- Shared OpenRouter executor helper added in `evokammer/agents/src/main/kotlin/com/achulkov/evokammer/agents/factory/OpenRouterExecutorFactory.kt` so the runtime uses the published client path directly.
- Runtime consumers now use the shared resolver:
  - `EvoKammerAgentFactory`
  - `CouncilRunner`
  - `GraderAgentFactory`
  - `CriticPipeline`
  - `DifferentialPrivacyTransform`
- Council prompt now advertises the cheaper defaults and treats Sonnet as opt-in only.
- WiP task references are now propagated into staging writes, KG fact emissions, and constraint emissions when an agent has an assigned task.
- Provider smoke test added for live OpenRouter chat completions, gated by `EVOKAMMER_ENABLE_OPENROUTER_SMOKE_TEST`.
- Default agent loop cap is now centralized in `BudgetConfig.maxIterations` and used by `EvoKammerAgentFactory`.
- Sealed StartingField archive canonicalization is verified at read-back time.
- Regression tests added:
  - `evokammer/core/src/test/kotlin/com/achulkov/evokammer/core/config/ModelSelectionConfigTest.kt`
  - `evokammer/competition/src/test/kotlin/com/achulkov/evokammer/competition/ModelSelectionConfigRuntimeDefaultsTest.kt`
  - `evokammer/agents/src/test/kotlin/com/achulkov/evokammer/agents/factory/OpenRouterModelResolverTest.kt`
  - `evokammer/agents/src/test/kotlin/com/achulkov/evokammer/agents/tools/WipTaskReferencePropagationTest.kt`
  - `evokammer/agents/src/test/kotlin/com/achulkov/evokammer/agents/factory/OpenRouterProviderSmokeTest.kt`

## Verified

- `:evokammer:core:compileKotlin`
- `:evokammer:agents:compileKotlin`
- `:evokammer:competition:compileKotlin`
- `:evokammer:orchestration:compileKotlin`
- `:evokammer:api:compileKotlin`
- `:evokammer:core:test`
- `:evokammer:competition:test`
- `:evokammer:agents:test`
- `:evokammer:orchestration:test`

## Remaining Remediation Work

- `:evokammer:api:test` currently fails in `GraderRunnerFailureTelemetryTest` because the test fixture reads back an empty failure-telemetry context. That does not block the Koog 1.0 migration, but the API test still needs a follow-up fix.

## Notes

- The canonical config file is in `core` so `agents`, `orchestration`, `competition`, and `api` can all read it without introducing circular dependencies.
- Any future model change should happen in `ModelSelectionConfig` first, then in `OpenRouterModelResolver` only if the OpenRouter catalogue remap itself changes.
