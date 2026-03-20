# evokammer:substrate — agents.md

## Purpose

The `substrate` module owns all persistent storage for the EvoKammer system. It uses
Room/SQLite via the existing project Room setup. Each competing group and each Grader Group
gets a **separate Room database file** to enforce Tier 1 (group) isolation at the storage level.
The desktop control panel reads substrate data through the `api` module's query interfaces.

## Key Design Rules

- No business logic here — pure data persistence and typed retrieval
- Canonical KB/KG/Tools are **immutable after promotion** (new version = new row)
- Dead Ends Registry rows are **never decayed or archived**, always included in retrieval
- The `substrate` module never calls the `agents` or `orchestration` modules
- Reads are always unrestricted (no credential check at the DB layer — enforced by sandbox)
- All writes go through repository interfaces; no direct DAO calls from outside this module

## Package Structure

```
com.achulkov.evokammer.substrate/
  db/
    EvoKammerDatabase.kt         -- Room AppDatabase (per-group instance via builder)
    entity/
      KnowledgeBaseEntryEntity.kt
      KnowledgeGraphFactEntity.kt
      KGSchemaEntity.kt
      ToolRegistryEntryEntity.kt
      StagingSubmissionEntity.kt
      WipTaskEntity.kt
      EventLogEntryEntity.kt
      LegacyProtocolEntity.kt
      TrustScoreEntryEntity.kt
      DeadEndsEntryEntity.kt
      BudgetLedgerEntity.kt
      RunConfigEntity.kt
    dao/
      KnowledgeBaseDao.kt
      KnowledgeGraphDao.kt
      KGSchemaDao.kt
      ToolRegistryDao.kt
      StagingDao.kt
      WipTaskDao.kt
      EventLogDao.kt
      LegacyProtocolDao.kt
      TrustScoreDao.kt
      DeadEndsDao.kt
      BudgetLedgerDao.kt
      RunConfigDao.kt
  repository/
    KBRepository.kt
    KGRepository.kt
    ToolRegistryRepository.kt
    StagingRepository.kt
    WipTaskRepository.kt
    EventLogRepository.kt
    LegacyProtocolRepository.kt
    TrustScoreRepository.kt
    DeadEndsRepository.kt
    BudgetRepository.kt
    RunConfigRepository.kt
  SubstrateDatabaseFactory.kt    -- creates/provides DB instance per groupId + runId
  DI.kt                          -- Koin module definitions
```

## Entity Schemas

### KnowledgeBaseEntryEntity

| Column | Type | Notes |
|---|---|---|
| id | TEXT (PK) | UUID |
| version | INT | Increments on update |
| content | TEXT | Full document text |
| embedding | BLOB | Vector bytes (nullable until embedded) |
| sourceAgentId | TEXT | |
| generation | INT | Generation number when created |
| trustScore | REAL | 0.0–1.0, decays over generations |
| citationCount | INT | Incremented by future agent reads |
| domain | TEXT | Knowledge domain tag |
| status | TEXT | active | archived | dead_end |
| groupId | TEXT | Owning group |
| runId | TEXT | Owning run |
| createdAt | INT | Unix epoch ms |
| isDeadEnd | INT | 0/1; if 1, never decayed regardless of trust |

### KnowledgeGraphFactEntity

| Column | Type | Notes |
|---|---|---|
| id | TEXT (PK) | UUID |
| subject | TEXT | Entity ID or literal |
| relation | TEXT | Relation name from KG schema |
| object | TEXT | Entity ID or literal |
| confidence | REAL | 0.0–1.0 |
| sourceAgentId | TEXT | |
| generation | INT | |
| trustScore | REAL | |
| provenance | TEXT | JSON: source evidence references |
| status | TEXT | active | disputed | rejected |
| speculative | INT | 0/1; from outlier agents |
| groupId | TEXT | |
| runId | TEXT | |

### KGSchemaEntity

| Column | Type | Notes |
|---|---|---|
| id | TEXT (PK) | |
| runId | TEXT | |
| entityTypesJson | TEXT | JSON array of entity type definitions |
| relationTypesJson | TEXT | JSON array of relation type definitions |
| hardConstraintsJson | TEXT | JSON array — must never be violated |
| softConstraintsJson | TEXT | JSON array — reduce trust on violation |

### ToolRegistryEntryEntity

| Column | Type | Notes |
|---|---|---|
| id | TEXT (PK) | |
| name | TEXT | |
| version | INT | |
| authorAgentId | TEXT | |
| successRate | REAL | Running average |
| failureLogJson | TEXT | JSON array, last N failures |
| inputSchemaJson | TEXT | |
| outputSchemaJson | TEXT | |
| domain | TEXT | |
| status | TEXT | active | deprecated | archived |
| code | TEXT | Tool implementation (Kotlin script or shell) |
| generation | INT | |
| groupId | TEXT | |
| runId | TEXT | |

### StagingSubmissionEntity

| Column | Type | Notes |
|---|---|---|
| id | TEXT (PK) | |
| agentId | TEXT | |
| role | TEXT | |
| type | TEXT | doc \| tool \| fact \| death_note \| plan_update \| dispatch |
| content | TEXT | JSON-serialised payload |
| wipTaskRef | TEXT | Nullable |
| speculative | INT | 0/1 |
| outlier | INT | 0/1 |
| budgetSignature | TEXT | Signed token — required |
| status | TEXT | pending \| promoted \| rejected \| disputed |
| rejectionReason | TEXT | Nullable |
| criticAgentId | TEXT | Nullable — set on review |
| generation | INT | |
| groupId | TEXT | |
| runId | TEXT | |

### WipTaskEntity

| Column | Type | Notes |
|---|---|---|
| nodeId | TEXT (PK composite with runId) | |
| runId | TEXT | |
| description | TEXT | |
| claimedByAgentId | TEXT | Nullable |
| status | TEXT | unclaimed \| claimed \| completed \| abandoned |
| generation | INT | Set on last status change |
| groupId | TEXT | |

### EventLogEntryEntity

| Column | Type | Notes |
|---|---|---|
| id | TEXT (PK) | |
| timestamp | INT | Unix epoch ms |
| eventType | TEXT | Enum string (see core EventType) |
| agentId | TEXT | Nullable |
| groupId | TEXT | Nullable |
| runId | TEXT | |
| generation | INT | |
| payloadJson | TEXT | Event-specific JSON |

### LegacyProtocolEntity

| Column | Type | Notes |
|---|---|---|
| id | TEXT (PK) | |
| role | TEXT | |
| generation | INT | Generation extracted from |
| expiresAtGeneration | INT | generation + 2 |
| reasoningPosture | TEXT | Free text summary |
| toolHeuristicsJson | TEXT | JSON structured hints |
| failureMapJson | TEXT | JSON structured failure patterns |
| kgHabitsJson | TEXT | JSON KG emission habits |
| sourceAgentIds | TEXT | Comma-separated source agent IDs |
| groupId | TEXT | |
| runId | TEXT | |

### BudgetLedgerEntity

| Column | Type | Notes |
|---|---|---|
| agentId | TEXT (PK) | |
| runId | TEXT | |
| groupId | TEXT | |
| initialBudget | REAL | |
| currentBalance | REAL | |
| floor | REAL | Kill signal threshold |
| actionLogJson | TEXT | JSON array of deductions |
| status | TEXT | active \| exhausted \| killed |

## Trust Score Decay Model

- Every `N` generations (configurable, default 5), agents with `trustScore < decayThreshold`
  (default 0.3) are moved to `status = archived`
- Dead Ends entries (`isDeadEnd = 1`) are exempt from decay — always active
- Each promotion starts at `initialTrustScore` defined by the Critic
- Citations increment trust by `+0.05` per citing agent (capped at 1.0)
- A rejected fact from the same source decrements trust by `0.1`

## KB Versioning Semantics

- When an agent updates an existing document, it submits a new staging entry with a reference
  to the prior KB entry ID
- On promotion, a new row is inserted with `version = previous.version + 1`
- The prior row is marked `status = archived` (not deleted)
- All versions remain queryable for audit; only the highest active version is returned by default

## SubstrateDatabaseFactory

Creates a Room database instance per `(runId, groupId)` pair. The DB file path follows:
`{appDataDir}/evokammer/runs/{runId}/groups/{groupId}/substrate.db`

This enforces storage-level Tier 1 isolation: Group A's DB file is physically inaccessible
to Group B's agent VMs (enforced by Shuru filesystem mounts).

## Koin DI

```kotlin
// in DI.kt
val substrateDatabaseModule = module {
    single { SubstrateDatabaseFactory(get()) }
    factory { (runId: String, groupId: String) ->
        get<SubstrateDatabaseFactory>().create(runId, groupId)
    }
    // Individual repositories provided per-group via scoped Koin scope or factory
}
```
