# EvoMolior — Civilisational Multi-Agent Platform

EvoMolior is a Kotlin Multiplatform (KMP) application with an embedded **EvoKammer** engine — a multi-agent evolutionary system where groups of specialised AI agents compete across generations to build and refine a civilisational knowledge substrate.

---

## Architecture Overview

```
EvoMolior/
├── composeApp/                  # KMP app — Android, iOS & Desktop (JVM) UI
├── evokammer/                   # EvoKammer engine (JVM-only Gradle submodules)
│   ├── core/                    # Domain models, contracts, BudgetLedger
│   ├── substrate/               # Room persistence layer (entities, DAOs, DB)
│   ├── sandbox/                 # Shuru microVM management, ConstitutionalEnforcer
│   ├── agents/                  # Koog AIAgent integration, tools, AgentRunner
│   ├── orchestration/           # Lifecycle, Epigenetic, Selector, GenesisCouncil
│   ├── competition/             # GroupCoordinator, Scoring, LeakOracle, Grader
│   └── api/                     # EvoKammerEngineService, IPC server, RunArchiveService
├── designsystem/                # Shared design tokens and composables
├── libs/                        # Feature libraries (auth, subscription, etc.)
└── build-logic/                 # Convention plugins
```

---

## EvoKammer Engine

EvoKammer is an **evolutionary multi-agent system** built on [JetBrains Koog](https://github.com/JetBrains/koog) (v0.7.2). It runs on the Desktop JVM target and is accessible from the Compose Desktop control panel.

### Core Concepts

| Concept | Description |
|---|---|
| **Run** | A full civilisational experiment with N groups and G generations |
| **Group** | An isolated civilisation of specialised agents sharing a Knowledge Base |
| **Agent** | A Koog `AIAgent` with a role, budget, and access to EvoKammer tools |
| **Substrate** | Room SQLite database per group — KB, staging, tool registry, trust scores |
| **Generation** | One full iteration of agent spawn → work → critique → selection → epigenetics |
| **GenesisCouncil** | Council of agents that designs the StartingField (initial civilisation config) |
| **EpigeneticManager** | Extracts Legacy Protocols from top-20% agents for the next generation |
| **CrossoverEngine** | Merges multiple LegacyProtocols into a single optimised protocol |
| **BudgetLedger** | Per-agent token budget tracker — enforces Constitutional spending limits |
| **Shuru microVM** | macOS Apple Virtualization sandbox isolating each agent's tool execution |
| **ConstitutionalEnforcer** | Validates all agent actions against the run's Constitutional Laws |
| **LeakOracle** | Detects cross-group data leakage with differential-privacy transforms |
| **GraderGroup** | Sealed Grader civilisation that scores each group's submitted work |
| **MetaArchive** | Cross-run archive of winning KBs, dead ends, and legacy protocols |

### Agent Roles

EvoKammer agents are assigned one of 19 specialised roles (via the `Role` enum):

- **Council roles** (seed the run): `GENESIS_STRATEGIST`, `WORLDBUILDER`, `CHALLENGE_SETTER`, `CONSTRAINT_ARCHITECT`, `GRADER_PRIME`
- **Worker roles**: `RESEARCHER`, `ANALYST`, `SYNTHESIZER`, `IMPLEMENTER`, `CRITIC`
- **Coordination roles**: `COORDINATOR`, `ARCHIVIST`, `INSPECTOR`, `EVALUATOR`
- **Adversarial/specialist roles**: `DEVIL_ADVOCATE`, `META_PLANNER`, `LEAK_DETECTOR`, `MEMORY_GUARDIAN`, `CONSTITUTIONAL_GUARDIAN`

### Koog Tools

Each agent has access to a role-gated set of Koog tools:

| Tool | Description |
|---|---|
| `ReadKBTool` | Read Knowledge Base entries (semantic search) |
| `WriteKBTool` | Write a KB entry (goes through staging) |
| `ClaimTaskTool` | Claim a WIP task from the task registry |
| `ReadKGTool` | Query the Knowledge Graph |
| `SendMessageTool` | Send an asynchronous message to another agent |
| `EmitFactTool` | Submit a KG fact for Critic review |
| `EmitConstraintTool` | Submit a KG soft constraint for review |
| `DelegateSubTaskTool` | Spawn a sub-agent for a delegated sub-task |
| `WriteDeathNoteTool` | Record final insights before agent shutdown |
| `FetchWebTool` | Fetch and sanitise web content (sandbox-only) |

---

## Desktop Control Panel

The EvoKammer control panel is a **Compose Desktop (JVM)** window launched alongside the main app. It has 8 screens:

1. **Dashboard** — Run status, event feed, quick actions (start/stop/configure)
2. **Run Setup** — Configure objective, groups, generations, model, budget
3. **Run Monitor** — Live group panels with agent list, scores, and event log
4. **Agent Detail** — Per-agent messages, tool calls, budget breakdown, violations
5. **Log Viewer** — Filterable full run log with 6 category chips
6. **Scoring Dashboard** — Round scores, group rankings, grading breakdowns
7. **Substrate Browser** — Browse KB entries, KG facts, dead ends, trust scores
8. **Post-Run Analysis** — Final scores, round history, KB comparison, meta-archive

---

## Module: `evokammer:substrate`

Persistent storage using **Room** (AndroidX Room JVM driver with `BundledSQLiteDriver`).

Each (runId, groupId) pair gets its own SQLite database file at:
```
{dataRootDir}/evokammer/runs/{runId}/groups/{groupId}/substrate.db
```

### Entities

| Entity | Table | Purpose |
|---|---|---|
| `KnowledgeBaseEntryEntity` | `knowledge_base_entries` | KB documents with trust scores, versioning, dead-end flag |
| `KnowledgeGraphFactEntity` | `kg_facts` | KG subject-relation-object triples with confidence |
| `StagingSubmissionEntity` | `staging_submissions` | Submissions pending Critic review |
| `WipTaskEntity` | `wip_tasks` | Tasks claimable by agents |
| `EventLogEntryEntity` | `event_log` | Full event log for audit/replay |
| `ToolRegistryEntryEntity` | `tool_registry` | Tracked tool adoptions and usage |
| `LegacyProtocolEntity` | `legacy_protocols` | Epigenetic protocols from top-fitness agents |
| `RunConfigEntity` | `run_configs` | Serialised run configuration |
| `TrustScoreEntity` | `trust_scores` | Per-agent trust scores for KB entries |
| `DeadEndsEntity` | `dead_ends` | Proven-unproductive approaches (never decayed) |
| `KGSchemaEntity` | `kg_schema` | KG schema node/relationship definitions |
| `MetaArchiveEntryEntity` | `meta_archive` | Cross-run archive for Council learning |

---

## Module: `evokammer:competition`

Multi-group competitive evaluation pipeline:

- **`BlindSubmissionGate`** — Seals submissions before grading to prevent bias
- **`GraderGroup`** — Runs a separate sealed Grader civilisation
- **`ScoringEngine`** — Computes weighted `RoundScore` from dimension scores
- **`MetaJudge`** — AI agent that evaluates overall quality
- **`LeakOracle`** — Detects cross-group leakage (frequency analysis)
- **`DifferentialPrivacyTransform`** — AI-assisted privacy audit on detected leaks
- **`ProgressiveDisclosureEngine`** — Reveals scores progressively per protocol
- **`GroupCoordinator`** — Orchestrates the full competition round lifecycle
- **`DispatchRelay`** — Routes task assignments across groups

---

## Module: `evokammer:orchestration`

Manages the evolutionary run lifecycle:

- **`LifecycleManager`** — State machine for a full run (IDLE → GENESIS → RUNNING → EVALUATING → EVOLVING → COMPLETED)
- **`GenesisCouncil`** — Council agent pipeline that designs the `StartingField`
- **`CriticPipeline`** — Runs Critic agents against staging submissions
- **`Watchdog`** — Monitors agents for budget overruns and timeout
- **`EpigeneticManager`** — Triggers fitness scoring and legacy protocol extraction
- **`FitnessScorer`** — Calculates per-agent fitness scores from substrate metrics
- **`LegacyProtocolExtractor`** — Packages top-agent protocols for the next generation
- **`CrossoverEngine`** — Merges multiple protocols using weighted combination
- **`EvolutionarySelector`** — Selects agents for promotion/reproduction
- **`InspectorRunner`** — Runs INSPECTOR-role agents for audit and integrity checks
- **`MetaPlannerRunner`** — Runs META_PLANNER agents for strategic planning
- **`KGConsistencyChecker`** — Validates KG facts against the schema and laws
- **`NestedGroupRunner`** — Manages sub-civilisation delegation for complex tasks

---

## Getting Started

### Prerequisites

- **JDK 17+**
- **macOS** (required for Shuru microVM sandbox — uses Apple Virtualization.framework)
- **Android Studio** Hedgehog+ or IntelliJ IDEA with KMP plugin
- **OpenRouter API key** (for Koog agent LLM access)

### Run the Desktop App

```bash
./gradlew :composeApp:run
```

This launches both the main EvoMolior app and the EvoKammer control panel window.

### Build a distributable

```bash
./gradlew :composeApp:packageDmg        # macOS DMG
./gradlew :composeApp:packageMsi        # Windows MSI  
./gradlew :composeApp:packageDeb        # Linux DEB
```

### Configuration

Set the OpenRouter API key via the Run Setup screen in the Desktop control panel, or via environment variable:

```
OPENROUTER_API_KEY=<your-key>
```

The default LLM model for worker agents is `google/gemini-2.0-flash-001`. The Council and Critic use `anthropic/claude-sonnet-4-5` by default.

### Run Tests

```bash
# All evokammer module tests (unit + integration)
./gradlew :evokammer:core:test \
          :evokammer:substrate:test \
          :evokammer:competition:test \
          :evokammer:orchestration:test
```

---

## Android / iOS

The mobile platforms carry the original EvoMolior feature set (auth, subscription, etc.). The EvoKammer engine modules are **JVM-only** and do not run on Android or iOS.

To run on Android:
```bash
./gradlew :composeApp:assembleDebug
# or open in Android Studio and run the Android configuration
```

To run on iOS:
- Open `iosApp/iosApp.xcproject` in Xcode and run the standard configuration
- Or use the KMP plugin in Android Studio

---

## agents.md Files

Every meaningful module and subdirectory contains an `agents.md` file that describes:
- The module's purpose and responsibilities
- Key classes and their roles
- Dependencies on other modules
- Conventions for AI coding agents working in that module

Root `agents.md` → [agents.md](./agents.md)

---

## License

Private repository — © 2024–2026 Achulkov. All rights reserved.
