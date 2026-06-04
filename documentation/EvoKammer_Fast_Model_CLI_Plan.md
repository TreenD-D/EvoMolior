# EvoKammer Fast Model CLI Plan

## Goal

Add an ultra-fast console mode for EvoKammer runs that:

- is enabled with `-fast`
- constrains the whole run to only these OpenRouter model IDs:
  - `openai/gpt-oss-120b`
  - `qwen/qwen3.5-flash-02-23`
  - `z-ai/glm-4.7-flash`
  - `deepseek/deepseek-v4-flash`
- writes the full run logs into the CLI output directory
- supports a 5-6 round fast run from the console for MindVortex HTML5 article generation
- leaves the existing UI path intact

## Constraints

- Do not break the existing `:composeApp` desktop launcher.
- Do not introduce any model outside the approved fast set when `-fast` is active.
- Preserve the existing CLI installable command.

## Proposed Implementation

1. Add a fast model profile in `evokammer:core` that exposes:
   - approved fast model IDs
   - fast runtime role assignments
   - fast council / grader / critic / leak-transform model IDs
2. Teach the OpenRouter model resolver to support `qwen/qwen3.5-flash-02-23` and `z-ai/glm-4.7-flash`.
3. Add `-fast` and `-rounds N` to the CLI.
4. Make `-fast` imply debug log capture and merge the fast role assignments into the run config.
5. Ensure `RunConfig.modelOverrides` is actually respected by the engine:
   - merge overrides into `StartingField.modelAssignment`
   - override any conflicting model pools so pools cannot bypass a user-supplied fast assignment
6. Thread fast model overrides into the production runtime wiring:
   - council runner
   - grader runner
   - critic pipeline
   - differential privacy leak transform
7. Add tests for:
   - fast model profile generation
   - resolver support for the two new model IDs
   - CLI `-fast` parsing and the `-rounds` option
   - model override application in the engine
8. Execute a 5-round fast CLI run with a MindVortex HTML5 article prompt and collect:
   - CLI output directory contents
   - full log file
   - summary of any model/runtime issues discovered
9. Write a follow-up markdown plan describing any required fixes from the log analysis.

## Run Shape

Recommended fast run command shape:

```bash
./evokammer/cli/build/install/evokammer/bin/evokammer \
  -fast \
  -rounds 5 \
  -prompt "Create an HTML5 article for mindvortex.app" \
  -input /path/to/context-folder \
  -output /path/to/output-folder
```

## Verification

- `:evokammer:cli:test`
- `:evokammer:cli:installDist`
- a real CLI fast run once credentials are available
