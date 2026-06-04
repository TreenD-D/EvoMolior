# EvoKammer Console CLI Plan

## Goal

Add a second, terminal-first way to run EvoMolior/EvoKammer without breaking the existing Compose Desktop UI.
The new mode must be installable and runnable from a simple console command, keep a service/process alive for the duration of a run, and accept:

- `-prompt "string"` or `-promptfile /path/to/file`
- `-input /path/to/folder`
- `-output /path/to/folder`
- optional settings for debug logging, model overrides, and OpenRouter API key

## Non-Goals

- Do not remove or alter the current desktop launcher path.
- Do not change the Compose UI flow beyond any shared helpers required for the CLI.
- Do not invent a new remote protocol unless it is needed to reuse the existing engine service.

## Proposed Architecture

1. Add a new JVM-only CLI entry point in a dedicated module so the desktop app and terminal app stay independent.
2. Reuse `EvoKammerRunService` from `evokammer:api` so the CLI executes the same engine path as the UI.
3. Reuse the same input-artifact upload format the UI already uses, so the engine receives identical context data regardless of entry point.
4. Build a small argument parser that supports exactly one prompt source (`-prompt` or `-promptfile`) and required `-input` / `-output` folders.
5. Start the engine, wait for completion, and persist run outputs into the requested output folder while optionally mirroring debug logs there.
6. Package the module with Gradle application/distribution support so it can be installed and run as a normal console command.

## Implementation Steps

1. Create a shared helper for converting a folder tree into `RunInputArtifactUpload` objects.
2. Implement the CLI module with a `main()` function that:
   - parses command-line arguments,
   - reads prompt text,
   - loads all files from the input folder,
   - builds a `RunConfig`,
   - starts the run through the shared engine service,
   - writes run metadata and results to the output folder,
   - exits with a non-zero code on validation or runtime failure.
3. Add optional CLI switches for:
   - `--debug`
   - `--openrouter-api-key`
   - `--model role=model` overrides
   - an output log path inside the selected output directory
4. Wire the new module into `settings.gradle.kts` and Gradle build logic.
5. Add focused tests for:
   - prompt source validation,
   - folder-to-artifact conversion,
   - output bundle/log path creation,
   - model override parsing.
6. Verify the module builds and the existing desktop app still compiles.

## Requirement Trace

- "additional way of running this project" -> new console-first JVM entry point.
- "can be installed via console/terminal" -> Gradle application distribution with generated start scripts.
- "simple console command" -> one executable command with explicit flags.
- "maintaining a service running for execution" -> keep a headless process alive while the run executes and waits for completion.
- "-prompt / -promptfile" -> mutually exclusive prompt source parser.
- "-input folderpath" -> recursive folder ingestion into run context artifacts.
- "-output path" -> output directory for logs and run outputs.
- "debug logs / tweak models / set openrouter api key" -> optional CLI flags and env-var fallback for runtime settings.
- "Do not break current UI option" -> keep the existing Compose Desktop launcher untouched and reuse shared engine code only.

## Verification Plan

- Run unit tests for the new CLI utilities.
- Compile the new CLI module.
- Compile the existing desktop module to confirm no regression in the UI entry point.
- If possible, execute one minimal CLI run against the test/in-process engine wiring and confirm the output directory is populated.
