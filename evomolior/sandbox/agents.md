# engine:sandbox

## Purpose

The `sandbox` module contains optional Shuru VM lifecycle support, policy/enforcement components,
source-content sanitization, and the connected tool child-process runner.

It does **not** currently provide universal production agent isolation. Normal CLI, benchmark,
self-improvement, and desktop runtime composition executes ordinary agents in-process.

## Implemented Components

| Component | Production State |
|---|---|
| `ShuruVmManager`, `VmConfig`, `VmHandle` | Implemented alternate lifecycle branch. Normal production entry points do not select it. |
| `ConstitutionalEnforcer` | Connected to native Koog tool dispatch and typed side-effecting tool boundaries; decisions persist to the group event log. |
| `AllowlistProxy` | Connected inside `FetchSourceTool` as the role-aware narrowing layer over run-approved source hosts. |
| `SourceAccessPolicy`, `SourceAccessDecisionStore`, `ContextUrlAllowlistExtractor`, `PublicWebAllowlist` | Used by connected source-governance paths. |
| `ContentSanitiser` | Connected content-cleaning boundary for fetched source material. |
| `ToolSubSandbox` | Connected child-process execution with timeout and bounded output capture. |

## Shuru Branch

`LifecycleManager` uses Shuru only when no local runner is supplied. VM configuration can carry
the boot context, backend keys/profile, and filesystem mounts. This infrastructure must be tested
through a real supported production entry point before Tier 2 isolation can be called connected.

Do not claim checkpoint/restore, heartbeat IPC, DNS proxying, seccomp fork prevention, or universal
host-filesystem isolation unless the corresponding current source and production test prove it.
Those guarantees appeared in older module documentation but are not established by the present
implementation.

## Enforcement Boundaries

`ConstitutionalEnforcer` is invoked by the real native Koog tool boundary. Staging, source fetch,
tool subprocess, and message tools add typed metadata before their side effects. Focused
integration tests prove oversized staging and denied network actions cannot mutate storage or
invoke the fetcher.

Connected source governance is implemented through the source policy, approval store, sanitization,
`AllowlistProxy`, and agent source tools. `SourceAccessPolicy` remains the run-approval authority;
the proxy applies optional role restrictions. The Java HTTP client does not automatically follow
redirects because each destination requires a fresh policy decision.

`ToolSubSandbox` launches bounded child processes and captures structured results. Because normal
agents run in-process, describe it as a host subprocess boundary, not a nested process inside a
Shuru VM.

See
[`../../documentation/EvoMolior_Roadmap_and_Project_State.md`](../../documentation/EvoMolior_Roadmap_and_Project_State.md)
for current isolation gaps and closure criteria.
