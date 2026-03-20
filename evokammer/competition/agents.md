# evokammer:competition — agents.md

## Purpose

The `competition` module manages everything related to multi-group dynamics: ring topology
coordination, intelligence leaks, formal dispatches, territorial resource nodes, blind scoring,
grader groups, and progressive task disclosure. It enforces Tier 1 (group) isolation from the
coordination layer — no group can access another group's substrate except through
explicitly defined channels.

## Package Structure

```
com.achulkov.evokammer.competition/
  coordinator/
    MultiGroupCoordinator.kt     -- Ring topology; routes leaks, dispatches, cross-reviews
    GroupRegistry.kt             -- Tracks all active groups and their state
    RingTopology.kt              -- Defines neighbour relationships between groups
  leaks/
    LeakOracle.kt                -- Samples KB, applies privacy transform, delivers to inbox
    DifferentialPrivacyTransform.kt  -- LLM-based summarisation with bounded detail
    IntelligenceInbox.kt         -- Per-group inbox; agents read via ReadKBTool (Leak domain)
  dispatch/
    DispatchRelay.kt             -- Holds formal dispatches for lag period, delivers to neighbour
    DispatchInbox.kt             -- Per-group formal dispatch inbox
    DispatchTrustScorer.kt       -- Tracks trust score per source group for dispatches
  resourcemap/
    ResourceMapEngine.kt         -- Manages knowledge node graph; claim/recapture
    KnowledgeNode.kt             -- Node with quality threshold and resource yield
    NodeClaimProcessor.kt        -- Validates artifact quality; registers/updates claims
    NodeYieldApplier.kt          -- Applies budget bonuses from held nodes
  scoring/
    ScoringEngine.kt             -- Runs deterministic tests + GradingSheet on submissions
    BlindSubmissionGate.kt       -- Holds submissions sealed until all groups submit
    CheckinScheduler.kt          -- Watches generation milestones; triggers gate open
    MetaJudge.kt                 -- Consensus detection; arbitrates contested GradingSheet dims
    FailureTelemetryRouter.kt    -- Routes anonymised failures to Grader Group KBs
  grader/
    GraderGroupManager.kt        -- Manages grader group lifecycles (separate EvoKammer instances)
    GradingSheetBuilder.kt       -- Grader agents build GradingSheet via this builder
    GradingSheetAggregator.kt    -- Combines GradingSheets from all Grader Groups
  disclosure/
    ProgressiveDisclosureEngine.kt -- Gates stage content release per group
    StageContent.kt              -- Pre-written stage disclosure content (constraint-framed)
  DI.kt
```

## Multi-Group Coordinator

### Ring Topology

For N groups, the ring is: `Group_0 → Group_1 → ... → Group_{N-1} → Group_0`

Each group has exactly two neighbours:
- Left neighbour: receives formal dispatches from this group
- Right neighbour: receives formal dispatches from this group after lag

Intelligence leaks are not directed (any group can receive a leak from any other group, as
determined by the Watchdog signal). Source group identity is always redacted in leak packets.

### Cross-Group Information Budget

The `MultiGroupCoordinator` tracks total cross-group information volume per group per generation.
If any group receives leaks + dispatches exceeding 15% of its active KB size, the Leak Oracle
frequency is throttled for that group (prevents monoculture via over-communication).

## Leak Oracle

### Sampling

1. Receives `INCREASE_LEAK_RATE` or `DELIVER_LEAK` signal from Watchdog
2. Selects a source group (not the recipient group)
3. Queries source group's KB for 3–5 random entries in a specified domain
4. Passes to `DifferentialPrivacyTransform`

### Differential Privacy Transform

Implemented as a Koog agent call with a specific prompt:

```
System: You are a summariser. Extract only conceptual insights from the provided text.
Rules:
- NEVER include: specific function names, class names, API endpoints, file paths,
  variable names, exact algorithms, or any proper nouns specific to the source
- ONLY include: general patterns, approaches, discovered relationships, conceptual insights
- Add minor paraphrasing (do not copy sentences verbatim)
- Drop 1-2 details randomly from each excerpt
- Keep summaries to 2-3 sentences per excerpt maximum
```

Output is the `LeakPacket.content`. Source group ID is NEVER included in the packet.

### Delivery

Leak packet appended to recipient group's `IntelligenceInbox` in their substrate DB.
Agents read the inbox via `ReadKBTool` when querying the `"intelligence_leaks"` domain.

## Dispatch Relay

Dispatches are voluntary, costly, and deliberate. Any agent can call `WriteStagingTool` with
`type = DISPATCH` — this goes to staging, Critic reviews, and on PROMOTE it enters the
`DispatchRelay` queue.

- Dispatch payload: domain summary, max 2000 tokens (size-limited by Critic)
- Lag: configurable (default 2 generations) — held in relay queue before delivery
- Delivery: appended to neighbour group's `DispatchInbox`
- `DispatchTrustScorer` tracks per-source trust: if receiving group's Critics repeatedly
  reject dispatches from a source, that source's `dispatchTrustScore` decreases

## Resource Map Engine

### Node Structure

Each node in the knowledge node graph has:
- `nodeId`: unique identifier
- `description`: what this subtopic is about
- `domain`: knowledge domain tag
- `qualityThreshold`: minimum artifact score to claim
- `resourceYield`: `BudgetBonus` applied to agents in this domain
- `floorNode`: boolean — if true, assigned permanently to one group, not capturable

### Claim/Recapture

1. Agent promotes an artifact for a WiP task linked to a node
2. `NodeClaimProcessor` asks `ScoringEngine` to score the artifact
3. If `score >= node.qualityThreshold`:
   - If node has no current holder → group claims node
   - If node has a holder and `score > currentHolder.score` → recapture
4. `NodeYieldApplier` applies the budget bonus to the claiming group's `BudgetConfig`
5. `ResourceMapEngine` emits a `NODE_CLAIM` or `NODE_RECAPTURE` event

Floor nodes (home territory) cannot be recaptured. Every group has at least 3 floor nodes.

## Scoring Engine

### Architecture

The `ScoringEngine` is completely isolated from all agent-accessible surfaces:
- No Koin provider exposes it to agents
- It is only invoked by the Watchdog via signal (on gate close) or by the `ResourceMapEngine`
- It reads from a sealed, encrypted test suite produced by the Genesis Council

### Scoring Dimensions

| Dimension | Weight | Measurement Method |
|---|---|---|
| Functional correctness | 40% | Deterministic test pass rate |
| Edge case coverage | 20% | Edge case test pass rate |
| Performance | 15% | Execution time vs baseline |
| KB quality | 10% | Citation density, internal consistency |
| Tool reliability | 10% | Tool success rate from registry logs |
| Cross-group audit | 5% | Competitor Critic score (anonymised) |

Exact weights are not disclosed to agents (only category names). Reveal at post-mortem.

### Blind Gate Protocol

1. `CheckinScheduler` detects generation milestone → emits `OPEN_CHECKIN_GATE` to Watchdog
2. Watchdog signals all groups to submit current deliverable
3. `BlindSubmissionGate` seals each submission with a signed timestamp
4. Gate waits for all groups to submit (timeout: configurable per-group waiting window)
5. `ScoringEngine.scoreAll()` runs against all sealed submissions simultaneously
6. Scores stored in sealed ledger — no read access until all scores computed
7. Watchdog emits `CLOSE_CHECKIN_GATE` → scores released simultaneously to all groups
8. `ProgressiveDisclosureEngine` unlocks Stage N+1 for groups above threshold
9. `FailureTelemetryRouter` sends anonymised failures to Grader Group KBs

## Grader Groups

### Architecture

Grader Groups are separate EvoKammer engine instances with their own:
- Room DB (isolated from all competing groups)
- Agent populations (Researcher, Builder, Critic variants)
- Budget and generation cycle

The `GraderGroupManager` runs these instances in parallel with the main competition.

Grader Group agents know:
- The global task structure
- The deliverable categories and their schemas
- The list of anonymised submitted artifacts (no group identity)

Grader Group agents do NOT know:
- Which group produced which artifact
- Each other Grader Group's criteria (until simultaneous submission)
- Internal KB of any competing group

### GradingSheet Schema

```kotlin
data class GradingSheet(
    val graderId: String,
    val domain: String,
    val proposedWeight: Float,
    val automatedTests: List<TestCase>,
    val rubricDimensions: List<RubricDimension>,
    val knownFailureModes: List<FailureMode>,
    val comparativePairs: List<ComparisonResult>
)

data class TestCase(val input: String, val expectedOutput: String, val edgeCaseClass: String, val severity: String)
data class RubricDimension(val name: String, val description: String, val scoringScale: String, val antiPatterns: List<String>)
data class FailureMode(val pattern: String, val detectionMethod: String, val disqualifying: Boolean)
data class ComparisonResult(val artifactAId: String, val artifactBId: String, val winner: String, val reasoning: String)
```

### Meta-Judge

1. All Grader Group GradingSheets submitted simultaneously (blind to each other)
2. `MetaJudge` detects consensus: dimensions where all graders agree → enter final rubric
3. Contested dimensions → cross-grader arbitration round (each grader evaluates the other's contested criteria)
4. Arbitration output → add to rubric with arbitration note
5. GradingSheet quality scored by Meta-Judge (internal consistency, test validity, coverage)
6. Quality score → influences Grader Group resource allocation next generation

## Progressive Disclosure Engine

### Stage Gating

- Stage 0 active for all groups at genesis
- Stage N unlocks for a group on passing Check-in N
- Per-group stage counter stored in `RunConfigEntity`
- Stage content is constraint-framed (problems to observe, not goals to hit)
- Pre-written by Genesis Council's Prompt Architect, sealed before competition starts
- Meta-Planner is the only consumer of goal-framed view

### Failed Groups

Groups below threshold do not advance stages. They receive:
- Increased Leak Oracle frequency (catch-up mechanic)
- No Stage N+1 content
- Continued competition participation (elimination is optional per run config)

## Failure Telemetry

After each check-in, `FailureTelemetryRouter` extracts anonymised failure patterns:

```json
{
  "domain": "http_client_implementation",
  "failure_type": "edge_case_timeout",
  "artifact_signature": "pattern_hash_only",
  "test_description": "request to slow server exceeds timeout"
}
```

These are written to each Grader Group's KB as `type = FAILURE_TELEMETRY`.
Grader agents read them and update their GradingSheets in subsequent generations.
