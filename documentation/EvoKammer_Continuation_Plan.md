# EvoKammer Continuation Plan

## Goal

Allow a completed run to be used as the starting point for a fresh run, with:
- a new run ID,
- the previous run's sealed `StartingField` as the baseline,
- editable launch settings in the setup UI,
- optional new context uploads merged into the carried-forward inputs,
- and preserved auditability of where the continuation came from.

## Chosen Semantics

- Continuation creates a new run ID.
- The source run remains immutable.
- The new run reuses the source run's archived `StartingField` as its seed.
- User edits on the new launch form override the carried-forward baseline where safe.
- Uploaded run context artifacts are copied into the new run's artifact store so the agent-side
  read-only artifact tool still works.

## Persistence Notes

- The source run is identified by a new optional field on `RunConfig`.
- The derived `StartingField` should record its provenance so the archive makes the relationship
  explicit.
- The engine should fail fast if the source run cannot be found or its sealed `StartingField`
  cannot be decoded.

## UI Notes

- The normal run setup screen should expose a continuation picker backed by the discovered run list.
- The test-run setup screen should expose the same picker so fast replay from a prior run is easy.
- A continuation source should be optional; blank means a normal fresh run.

## Verification Targets

- A new run can start from a completed run's archived `StartingField`.
- The carried-forward run still exposes its input artifacts to agents.
- New uploads are merged into the continuation run.
- The source run is not mutated.
- The new run remains inspectable in the post-run archive and results bundle.
