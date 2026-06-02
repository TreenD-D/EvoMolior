# Run Input And Results Artifacts Plan

## Goal

Allow a run to start with one or more user-provided context artifacts, and make those artifacts
available to the groups during the run. At the end of the run, produce a downloadable results
bundle that includes the run outputs and the uploaded inputs for traceability.

This should work for:
- text documents
- images
- arbitrary files
- folders / collections of files

The implementation should use one clear contract for uploads:
- `RunConfig` keeps the run objective and normal run settings.
- a new `RunArtifactUploadRequest` is sent alongside run start for the uploaded context bundle.
- the API layer receives files as multipart uploads, then converts them into `RunArtifactUploadRequest`
  metadata plus persisted bytes before the engine starts.

The plan intentionally keeps the current run architecture intact: `RunConfig` starts the run,
`StartingField` remains the sealed orchestration snapshot, and the run meta-archive remains the
canonical persistence layer for audit and post-run retrieval.

## What Exists Today

- `RunConfig` starts a run from a single objective plus model overrides and scoring inputs.
- `EvoKammerRunService` seals the generated `StartingField` into the run meta-archive.
- `RunArchiveService` archives the `StartingField`, winning KB snapshot, dead ends, and legacy
  protocols after a run completes.
- Groups currently inherit the seeded run context through the normal genesis / substrate path,
  but there is no first-class notion of user-uploaded starting artifacts or downloadable result
  bundles.

## Proposed Model

Introduce a first-class `RunArtifact` concept that represents any user-supplied or system-generated
file attached to a run.

Suggested artifact metadata:
- `artifactId`
- `runId`
- `kind` (`context_input`, `seed_doc`, `image`, `folder_manifest`, `result_bundle`, `log_export`, etc.)
- `sourceName`
- `mimeType`
- `sizeBytes`
- `contentHash`
- `storagePath`
- `parentArtifactId` for folder members or derived artifacts
- `visibility` (`run_private`, `group_readonly`, `result_download`)
- `createdAtMs`

For directories, store a manifest artifact plus individual child artifacts. The manifest should
preserve folder structure so the UI and archive exporter can reconstruct it.

Recommended storage split:
- bytes live in a dedicated run-scoped artifact directory on disk, under the run storage root
  used by the substrate
- metadata lives in a new `RunArtifactEntity` table in the substrate database

Suggested schema fields for `RunArtifactEntity`:
- `artifactId`
- `runId`
- `groupId` nullable, because some artifacts are run-level and some are group-visible
- `kind`
- `sourceName`
- `relativePath`
- `mimeType`
- `sizeBytes`
- `contentHash`
- `storagePath`
- `parentArtifactId` nullable
- `visibility`
- `createdAtMs`
- `textPreview` nullable, for derived OCR/extracted text

## Run Start Flow

1. Extend the run-start request so callers can provide a list of attachments and/or a folder
   upload descriptor.
2. Store the raw artifacts before the run starts, in a run-scoped artifact store.
3. Generate a manifest that records the uploaded inputs, their hashes, and their derived text
   previews when available.
4. Thread the artifact manifest into `StartingField` as a dedicated `inputArtifacts` field so the
   run bootstrap snapshot and the archive refer to the same canonical list.
5. Seed group substrates with approved text-derived seed documents from the uploaded context.

Key design choice:
- Binary artifacts should remain preserved as binaries.
- Text-derived views should be generated where needed for agent consumption, but the raw artifact
  must remain available for audit and download.

## Group Access During The Run

Groups should read the starting artifacts through a dedicated read-only tool, not through the UI.

Recommended mechanism:
1. Add `ReadRunArtifactTool` to the agent tool registry when `AgentBootContext` contains run-level
   input artifacts.
2. Expose the tool from `LifecycleManager` and `RolePromptBuilder` so the system prompt explains
   that uploaded context is available read-only.
3. Make the tool return artifact metadata plus either raw text or a preview for binary inputs.
4. Keep raw binary download references available only for UI/archive retrieval, not as an agent
   write path.

This keeps group access explicit, testable, and isolated from the desktop app.

## Results Download Flow

At run completion, create a downloadable results package that includes:
- the sealed `StartingField`
- uploaded input artifacts and their manifest
- run summary / final status
- winning KB snapshot
- dead ends export
- legacy protocols
- grading sheets / scoring outputs
- any generated run logs or event summaries that are safe to include

Suggested format:
- a single ZIP archive produced by `RunArchiveService`
- a run-archive database record for machine-readable retrieval
- optional per-artifact download endpoints for large binaries

Recommended endpoint contract:
- `GET /runs/{runId}/results/download` returns the ZIP as an attachment
- the archive is assembled from the persisted run artifact directory plus the meta-archive tables
- failed or partial runs should still emit a bundle if the run reached startup, so users can inspect
  the uploaded inputs and any partial outputs

Recommended ZIP layout:
- `manifest.json` at the root
- `starting-field.json`
- `inputs/` for uploaded context artifacts
- `outputs/` for final KB / dead ends / grading exports
- `logs/` for safe run summaries and event exports
- `hashes.txt` or equivalent integrity index

The ZIP should be deterministic where possible:
- stable folder layout
- manifest JSON at the root
- content hashes for integrity
- human-readable index file

## Data Flow And Ownership

- UI / API layer owns ingestion of uploaded attachments.
- Orchestration owns validation, visibility rules, and thread-through to bootstrap context.
- Substrate or a dedicated artifact store owns persistence.
- Archive service owns final packaging for download.

The important invariant is that the run should never depend on transient desktop state after
startup. Once a run begins, every artifact needed for replay or download must already be persisted.

## Implementation Phases

### Phase 1: Upload And Persistence
- Add `RunArtifactUploadRequest` to `RunConfig.kt` or the adjacent API model layer.
- Add multipart handling in `EvoKammerIpcServer.kt`.
- Persist uploaded artifact bytes to the run-scoped directory and metadata to substrate.
- Create a manifest object and hash each artifact.
- Add tests for text files, images, and folder manifests.

### Phase 2: Bootstrap Integration
- Thread the artifact manifest into `StartingField.inputArtifacts`.
- Materialise approved text files into the group KB at startup.
- Register `ReadRunArtifactTool` in `LifecycleManager` tool setup.
- Add tests proving groups can access the approved starting docs after spawn.

### Phase 3: Results Packaging
- Extend `RunArchiveService.archiveRun(...)` to produce the final ZIP bundle.
- Include uploaded inputs and run outputs in the archive.
- Provide a stable manifest so the UI can show what is inside the download.
- Add tests that verify the archive contains the expected entries.

### Phase 4: UI And API Surfaces
- Add upload controls to the run setup screen.
- Show uploaded context docs before the run starts.
- Add a download action that hits `GET /runs/{runId}/results/download`.
- Add UI state for upload progress and archive readiness.

## Acceptance Criteria

- A run can be started with a text document upload and the groups can read it during generation 0.
- A run can be started with an image upload and the system stores the raw binary plus a safe text
  preview.
- A folder upload preserves its relative paths in the manifest and the final ZIP.
- The run archive download contains the sealed `StartingField`, uploaded inputs, and final outputs.
- The ZIP download works for completed runs and for runs that fail after startup, as long as the
  inputs were persisted.
- Hashes in the manifest match the stored artifact bytes.
- The read-only artifact path is available to agents and not writable from the run itself.

## Edge Cases And Constraints

- Large files need size limits and clear failure messages.
- Folder uploads must preserve structure and not flatten names.
- Image support should not assume every agent can consume images directly; derive a safe preview.
- Uploaded artifacts must not bypass sandbox policy or become executable code.
- The download bundle should not leak private API keys or ephemeral runtime secrets.
- If a run fails before completion, the archive should still include the uploaded inputs and any
  partial outputs that are safe to persist.

## Open Questions

- Should uploaded context artifacts become part of `StartingField`, or remain a separate run input
  bundle referenced by `StartingField`?
- Should groups receive the raw binary artifact, a derived text preview, or both?
- Should results download be one archive per run, or separate exports for inputs and outputs?
- Should folder uploads be accepted recursively through the API, or flattened in the UI before
  submission?

## Recommended Direction

Use a separate run artifact bundle with a manifest, then reference it from the run bootstrap
context and the final archive. This keeps `StartingField` focused on orchestration state while
making uploads and downloads first-class and auditable.
