# engine:agents

## Purpose

The `agents` module owns Koog-facing factories, role prompts, backend adapters, agent tools,
in-process agent execution, one-level sub-agent delegation, and the Death Note protocol.

Normal production execution is in-process because runtime entry points supply a local agent
runner. This module can produce boot contracts usable by the alternate Shuru lifecycle branch, but
ordinary runs do not currently execute inside Shuru.

## Main Areas

| Area | Key Types |
|---|---|
| Agent creation | `EvoMoliorAgentFactory`, role prompt builders, model/backend resolvers. |
| Execution | `AgentRunner`, local runner integration, `DeathNoteProtocol`. |
| Sub-agent delegation | `DelegateSubTaskTool`, `SubAgentCoordinator`; one level only and budget bounded. |
| Substrate reads | KB list/read/search, KG query, task and context tools. |
| Staged writes | documents, KG facts/constraints, tools, Death Notes, messages, grading output. |
| External sources | source approval, fetch, search, URL/content governance tools. |
| Tool execution | `ExecuteToolTool` through the connected `ToolSubSandbox` child-process boundary. |

The production tool registry is assembled in `EvoMoliorAgentFactory`. Tool availability is
role-gated and backend-dependent. Codex/ChatGPT uses Codex code mode with an isolated filesystem
RPC bridge to the same agent-scoped typed registry; it does not use Koog-native function calls.

## Security and Budget Reality

- Every native Koog tool call passes through `ConstitutionalEnforcer` before dispatch and is
  persisted through the group event log.
- Side-effecting tools supply typed staging-size, network, process, and message metadata before
  mutation. This avoids deriving security decisions from rendered Koog argument strings.
- `FetchSourceTool` composes run approval with role-aware `AllowlistProxy` restrictions immediately
  before fetch. The HTTP client never follows redirects itself; the tool re-runs that policy,
  audit, and constitutional interception for each redirect hop (at most five) before any request
  reaches it, and reports blocked destinations so they can be approved explicitly.
- Native grader agents and RoundJudge use the same structured constitutional tool/action boundary;
  their decisions are written only to the isolated grader event log.
- Criteria agents, target graders, and RoundJudge list and execute promoted tools only from their
  grader-local registry and materialized grader tools directory, enabling cross-generation grader
  tool compounding without crossing into competing-group registries.
- Tool execution uses a bounded host child process. It must not be described as necessarily
  running inside an agent VM while normal agents execute in-process.
- Per-agent ledgers are real. Run-wide cumulative spend enforcement is not provided by the agent
  module.

## Lifecycle

`LifecycleManager` builds `AgentBootContext` with identity, role, budget, generation, model,
StartingField/stage context, optional WiP and Legacy context, and substrate paths. The runner
executes the agent, preserves staged outputs, and ensures a written or fallback Death Note. Sub-
agents cannot delegate recursively.

Current phase status and validation gaps are maintained in
[`../../documentation/EvoMolior_Roadmap_and_Project_State.md`](../../documentation/EvoMolior_Roadmap_and_Project_State.md).
