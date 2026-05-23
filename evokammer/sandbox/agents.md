# evokammer:sandbox — agents.md

## Purpose

The `sandbox` module is the security and isolation layer. It manages Shuru microVM
lifecycle, enforces constitutional laws before any agent action executes, runs tools
in nested sub-processes, and sanitises web content before it enters any agent context.
This is the only module that invokes the Shuru CLI directly.

## Critical Security Principle

Every agent action passes through the `ConstitutionalEnforcer` BEFORE it reaches the VM.
If the enforcer blocks an action, it is logged and the agent receives an error — the action
never executes. This is the inviolable line: constitutional enforcement is infrastructure,
not guidance.

## Package Structure

```
com.achulkov.evokammer.sandbox/
  shuru/
    ShuruVmManager.kt            -- CLI wrapper: spawn, kill, signal, checkpoint, restore
    ShuruCliAdapter.kt           -- ProcessBuilder-based Shuru CLI invocation
    VmConfig.kt                  -- CPU, memory, disk, network config for a VM
    VmHandle.kt                  -- Handle to a live VM (processId, shuruVmId, groupId, agentId)
    FilesystemMountConfig.kt     -- /kb, /tools, /staging, /workspace mount specs
    NetworkAllowlistConfig.kt    -- Per-role allowed domains and rate limits
  enforcer/
    ConstitutionalEnforcer.kt    -- Pre-execution action interceptor
    BlockRule.kt                 -- ALWAYS_BLOCK rule definitions
    LogRule.kt                   -- ALWAYS_LOG rule definitions
    ActionInterceptor.kt         -- Intercepts Koog tool calls before execution
    ConstitutionalViolationException.kt
  network/
    AllowlistProxy.kt            -- DNS-level allowlist enforcement
    ContentSanitiser.kt          -- Web content injection detection and stripping
    WebFetchClient.kt            -- HTTP client routed through AllowlistProxy
    InjectionDetector.kt         -- Detects prompt injection patterns in web content
  toolsandbox/
    ToolSubSandbox.kt            -- Nested process execution inside agent VM
    IOCapture.kt                 -- stdin/stdout/stderr capture with size caps
    ExecutionResult.kt           -- stdout, stderr, exitCode, truncated flag
    ToolTimeout.kt               -- Hard timeout enforcement
  DI.kt
```

## Shuru MicroVM Manager

### VmConfig

```kotlin
data class VmConfig(
    val agentId: AgentId,
    val groupId: GroupId,
    val runId: RunId,
    val cpuCount: Int = 1,
    val memoryMb: Int = 512,
    val diskGb: Int = 2,
    val mounts: FilesystemMountConfig,
    val network: NetworkAllowlistConfig,
    val agentJarPath: String,          // path to AgentRunner JAR
    val bootContextJson: String,       // serialised AgentBootContext — injected as env var
    val openRouterApiKey: String,      // injected as OPENROUTER_API_KEY env var
    val groupProfile: String           // injected as EVOKAMMER_GROUP_PROFILE env var
)
```

**Boot injection contract**: the generated Shuru JSON places the boot context in
`boot.env.EVOKAMMER_BOOT_CONTEXT` (not in `boot.stdin`). This matches `AgentRunner`'s
expected env-var source. Using env vars instead of stdin prevents pipe-buffer issues
with large boot contexts and makes the injection contract explicit and testable.

See `VmConfigContractTest` for a unit test that verifies the generated JSON shape.

### Filesystem Mount Specification

| Mount Point | Host Path | VM Access | Purpose |
|---|---|---|---|
| `/kb` | `{runDir}/groups/{groupId}/kb/` | Read-only | Canonical KB documents |
| `/tools` | `{runDir}/groups/{groupId}/tools/` | Read-only | Tool Registry executables |
| `/staging` | `{runDir}/groups/{groupId}/staging/{agentId}/` | Write-only | Agent output |
| `/workspace` | ephemeral tmpfs | Read-write | Scratch space; wiped on VM kill |

No access to host filesystem beyond these mounts. No shared memory between agent VMs.

### Shuru CLI Commands (wrapped by ShuruCliAdapter)

```bash
shuru run --config vm_config.json --name {agentId}  # spawn
shuru kill {shuruVmId}                               # hard kill
shuru signal {shuruVmId} SIGTERM                     # graceful signal
shuru checkpoint {shuruVmId} --name {checkpointId}  # save state
shuru restore {shuruVmId} --checkpoint {checkpointId}
shuru status {shuruVmId}                             # returns JSON status
```

`ShuruVmManager` wraps these via `ProcessBuilder`, captures stdout/stderr, parses JSON responses.

### Port Forwarding

Agent process inside VM exposes a local HTTP server for IPC with the LifecycleManager:
- Budget deduction requests (agent calls tool → deduct → receive OK or FLOOR_REACHED)
- Staging write requests (proxied through the write-only /staging mount)
- Heartbeat: VM emits heartbeat every 10 seconds; if missed 3 consecutive → LifecycleManager kills VM

## Constitutional Enforcer

### Architecture

The `ConstitutionalEnforcer` is installed as an interceptor around every Koog tool call.
Koog's `AIAgent` tool execution pipeline is intercepted before the tool's `execute()` is called.

```
AIAgent.run() 
  → LLM produces ToolCall
  → ConstitutionalEnforcer.intercept(toolCall)
      → evaluate each BlockRule
      → if ANY rule matches: throw ConstitutionalViolationException, log ALWAYS_BLOCK event
      → evaluate each LogRule  
      → if ANY rule matches: log ALWAYS_LOG event, continue
  → tool.execute() (if not blocked)
```

### ALWAYS_BLOCK Rules

```kotlin
sealed class BlockRule {
    object DirectKBWrite : BlockRule()           // any write bypassing staging
    object CrossAgentWorkspaceRead : BlockRule() // reading another agent's /staging or /workspace
    object UnallowedNetworkEgress : BlockRule()  // domain not in role's allowlist
    object ForkOutsideToolSandbox : BlockRule()  // process fork in agent process space
    object EnvVarRead : BlockRule()              // reading environment variables
    object StagingQuotaExceeded : BlockRule()    // staging write > size quota
    object DirectKGWrite : BlockRule()           // KG write bypassing Checker + Critic
    object KGConstraintModification : BlockRule() // agent trying to modify KG schema constraints
    object TTLExtensionAttempt : BlockRule()     // any TTL or budget extension attempt
}
```

### ALWAYS_LOG Rules

```kotlin
sealed class LogRule {
    object ToolCallLogged : LogRule()           // every tool call with args
    object WebRequestLogged : LogRule()         // every outbound HTTP + response size
    object InterAgentMessageLogged : LogRule()  // every message send
    object StagingWriteLogged : LogRule()       // every staging submission
    object LLMCallLogged : LogRule()            // every LLM call with token count
    object BudgetDeductionLogged : LogRule()    // every budget deduction
}
```

## Network Allowlist Proxy

### Per-Role Allowlist

| Role | Allowed Domains |
|---|---|
| Researcher | configurable domain list (default: Wikipedia, arXiv, Hacker News, MDN, docs sites) |
| Builder | package registries (read-only), docs sites |
| Planner | none |
| Critic | none |
| Archivist | none |
| Outlier | broader configurable list (Researcher list + additional) |
| Council | broader list for research during initialization |

All outbound HTTP is routed through `AllowlistProxy`:
- DNS lookup checked against allowlist before connection
- Rate limited (configurable per role, default 10 req/min)
- All requests logged (URL + response size, no content — content logged separately after sanitization)

### Content Sanitiser

Runs on ALL web-fetched content before it reaches any agent context:

1. Strip HTML comments and script tags
2. Run `InjectionDetector.detect(content)` — flags patterns like:
   - "ignore previous instructions"
   - "your new task is"
   - "system prompt"
   - Markdown instruction-like structures (numbered commands, imperative sentences)
3. If injection detected: replace with `[CONTENT_SANITISED: potential injection detected]`
4. Truncate to `maxContentTokens` (default 4000 tokens) with summary request appended
5. Return sanitised, truncated content

## Tool Sub-Sandbox

### Architecture

When an agent calls `ExecuteToolTool`, the tool runs in a nested child process inside the agent VM:

```
[Agent VM process]
  → ExecuteToolTool.execute(toolName, argsJson)
    → ToolSubSandbox.run(toolExecutable, argsJson)
      → ProcessBuilder(toolExecutable).start()
        stdin  ← serialised argsJson
        stdout → IOCapture (size-capped at 50KB)
        stderr → IOCapture (last 1KB for error logging)
        timeout → ToolTimeout.enforce(30_000ms default)
```

### IOCapture

- Reads stdout until process exits or `maxOutputBytes` (50KB) reached
- If size cap hit: truncates and appends `[OUTPUT_TRUNCATED: N bytes total]`
- Stderr: captures last 1KB for failure logging
- Returns `ExecutionResult(stdout, stderr, exitCode, truncated)`

### Tool Sub-Sandbox Security Properties

- Child process has NO outbound network access (only the parent agent VM has network via allowlist)
- Child process reads only from `/workspace/{toolName}/` (ephemeral sub-directory)
- Child process cannot write to `/kb`, `/tools`, or `/staging`
- Child process cannot access agent's context window or memory
- If child process tries to fork: SIGKILL immediately (enforced by seccomp profile inside VM)

### Execution Result Interpretation

- Exit code 0 + stdout → `ToolExecSuccess` (cost: 2)
- Exit code != 0 OR empty stdout → `ToolExecFailure` (cost: 4)
- Same tool + args hash in same lifecycle → `ToolExecRetry` (cost: 8)
- Timeout → `ToolExecFailure` with `[TIMEOUT]` in stderr

## DI Module

```kotlin
val sandboxModule = module {
    single { ShuruCliAdapter() }
    single { ShuruVmManager(get()) }
    single { ConstitutionalEnforcer() }
    factory { ContentSanitiser() }
    factory { AllowlistProxy(get()) }
    factory { (config: VmConfig) -> ToolSubSandbox(config) }
}
```
