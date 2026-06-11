# EvoKammer Self-Improvement Launch Plan

## Goal

Provide a separate, operator-facing command that launches a self-improvement run in an isolated
copy of the repository rather than the live checkout.

## Locked Decisions

- The launch command will be a separate CLI entrypoint/task, not a flag on the normal run path.
- The launcher will always execute inside a copied or detached workspace, never in-place.
- `git worktree` is the preferred isolation mechanism when the source is a Git checkout.
- A filesystem copy fallback is required so the launcher still works when worktree creation is
  unavailable.
- The launcher must preserve a durable manifest and logs for the copied workspace and the child
  command.

## Expected Behavior

1. Resolve and validate the source repository root.
2. Create an isolated workspace under a user-supplied output root.
3. Execute the requested self-improvement command inside the isolated workspace.
4. Capture stdout, stderr, exit code, and the workspace path in a manifest.
5. Leave the isolated workspace intact on failure unless the operator explicitly requests cleanup.

## Safety Constraints

- The source checkout is never modified directly by the launcher.
- The launcher refuses to create the isolated workspace inside the source tree.
- The launcher should be deterministic enough to test with a fake command executor.

## Follow-Up Verification

- Unit test the workspace-copy service and the command-launch wrapper.
- Confirm the launcher creates a distinct workspace path and writes a manifest before any live
  self-improvement run is attempted.
