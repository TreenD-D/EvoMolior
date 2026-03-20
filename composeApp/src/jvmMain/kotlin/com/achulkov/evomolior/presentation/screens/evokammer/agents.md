# composeApp — EvoKammer Control Panel Screens agents.md

## Purpose

This directory contains all **JVM-only** Compose screens and ViewModels for the EvoKammer
control panel. The desktop app acts as a remote viewer and commander for the EvoKammer engine
process — it does not run any agent orchestration itself.

All code in this directory is placed in `jvmMain` source set. It MUST NOT be added to
`commonMain`, `androidMain`, or any other source set — EvoKammer UI is desktop-only.

## Package: `com.achulkov.evomolior.presentation.screens.evokammer`

## Screen Catalogue

### 1. `dashboard/` — EvoKammer Dashboard (entry point)

- **File**: `EvoKammerDashboardScreen.kt` + `EvoKammerDashboardViewModel.kt`
- **Purpose**: Main entry screen listing all runs (past + active). Create new run button.
- **Data**: `List<RunSummary>` via `EvoKammerService.observeRuns()` — live Flow
- **Actions**: Navigate to RunConfig (new run), tap existing run → navigate to LiveMonitor or PostRunAnalysis
- **State**: `EvoKammerDashboardUiState(runs, isConnected, connectionError)`

### 2. `config/` — Run Configuration

- **File**: `RunConfigScreen.kt` + `RunConfigViewModel.kt`
- **Purpose**: Configure and launch a new EvoKammer run.
- **Fields**:
  - Objective text input (multi-line, large)
  - Global budget ceiling (number input, USD equivalent)
  - Generation ceiling (integer input)
  - Group count selector (2-6)
  - Group profile assignments (one dropdown per group: Library / Factory / Commune / Monastery / Mercenary)
  - Model overrides table (role → OpenRouter model string, optional)
  - Check-in generation milestones (multi-value number input, e.g. "10, 25, 50")
  - Leak interval and dispatch lag (integer inputs)
  - Grader group count (1-3)
  - OpenRouter API key (secure text field, stored in platform secure storage)
- **Actions**: Validate → Start Run → navigate to LiveMonitor with new runId
- **State**: `RunConfigUiState(fields, validationErrors, isSubmitting)`

### 3. `monitor/` — Live Run Monitor

- **File**: `LiveMonitorScreen.kt` + `LiveMonitorViewModel.kt`
- **Purpose**: Real-time dashboard for an active run. Updates pushed via event stream.
- **Sections**:
  - **Run Overview**: current generation, total cost, check-in schedule, next check-in countdown
  - **Group Cards** (one per group): KB density chart, KG fact count, resource map node holdings, active agent count, remaining budget, current stage
  - **Active Agents Panel**: live table of agents (role, status, budget %, WiP task, last action)
  - **Event Feed**: scrolling live event log (last 100 events, color-coded by type)
  - **Resource Map**: node graph visualisation showing claim status per group (color-coded)
- **Data**: `EvoKammerService.observeGroupStates(runId)` + `observeAgentStates()` + `observeEvents()`
- **Actions**: Pause/Resume run, Force Check-in, Terminate run, Navigate to Substrate Browser
- **State**: `LiveMonitorUiState(groupStates, agentStates, recentEvents, runStatus, checkInStatus)`

### 4. `logs/` — Event Log Viewer

- **File**: `EventLogScreen.kt` + `EventLogViewModel.kt`
- **Purpose**: Filterable, searchable structured event log for a run.
- **Filters**: by group, by agent, by event type, by generation range
- **Display**: Table with columns: timestamp, generation, group, agent, event type, payload summary
- **Data**: `EvoKammerService.observeEvents(runId, filter)` (paginated for past events)
- **State**: `EventLogUiState(events, filter, isLoading)`

### 5. `substrate/` — Substrate Browser

- **File**: `SubstrateBrowserScreen.kt` + `SubstrateBrowserViewModel.kt`
- **Purpose**: Browse a group's canonical substrate: KB documents, KG facts, Tool Registry,
  Dead Ends, Legacy Protocols.
- **Sections** (tab-based):
  - **KB**: searchable list of documents with trust score, generation, citation count badges
  - **KG**: facts table filterable by subject/relation/object; with constraint violation indicators
  - **Tools**: tool cards with success rate sparklines, version history
  - **Dead Ends**: list of documented failure approaches (never decayed — always shown)
  - **Legacy Protocols**: list by role and generation, expandable detail
- **Data**: `EvoKammerService.queryKB()`, `queryKG()`, `getTools()`, `getDeadEnds()`, `getLegacyProtocols()`
- **State**: `SubstrateBrowserUiState(activeTab, kbEntries, kgFacts, tools, deadEnds, legacyProtocols, groupId, query)`

### 6. `scoring/` — Scoring Dashboard

- **File**: `ScoringDashboardScreen.kt` + `ScoringDashboardViewModel.kt`
- **Purpose**: View check-in results, GradingSheets, and the leaderboard.
- **Sections**:
  - **Check-in Timeline**: horizontal timeline showing check-ins (sealed/revealed/upcoming)
  - **Leaderboard**: ranking table per check-in (revealed only — sealed shown as locked)
  - **GradingSheet Viewer**: per grader group, per check-in; expandable rubric dimensions and test cases
  - **Score Breakdown**: per group, per dimension, bar chart
- **Data**: `EvoKammerService.getScoringResults(runId)`
- **State**: `ScoringDashboardUiState(checkins, leaderboard, selectedCheckin, gradingSheets)`

### 7. `analysis/` — Post-Run Analysis

- **File**: `PostRunAnalysisScreen.kt` + `PostRunAnalysisViewModel.kt`
- **Purpose**: Final analysis after a completed run.
- **Sections**:
  - **Run Summary**: winner group, final scores, total cost, total generations, wall-clock time
  - **KB Comparison**: side-by-side KB density + domain coverage across groups
  - **Dead Ends Meta-Archive**: merged Dead Ends from all groups (civilisational negative knowledge)
  - **Meta-Archive**: list of meta-archive entries from this run (seeded for future Council learning)
  - **Legacy Protocol Archive**: all extracted protocols from the run, role-grouped
- **Data**: `EvoKammerService.getScoringResults()` + `getDeadEnds()` + `getMetaArchive()`
- **State**: `PostRunAnalysisUiState(runSummary, kbComparison, mergedDeadEnds, metaArchive)`

## Navigation Flow

```
AppNavigation (commonMain) → EvoKammerDashboardRoute (JVM only)
  EvoKammerDashboard
    → new run → RunConfig → start → LiveMonitor
    → existing run (active) → LiveMonitor
    → existing run (completed) → PostRunAnalysis

  LiveMonitor
    → open substrate → SubstrateBrowser (group selector)
    → open logs → EventLog
    → check-in results → ScoringDashboard

  ScoringDashboard → SubstrateBrowser (for artifact detail)
  PostRunAnalysis  → SubstrateBrowser (for winner KB inspection)
```

## ViewModel Pattern

All ViewModels follow the existing project MVVM pattern:
- Extend `androidx.lifecycle.ViewModel`
- Expose `UiState` as `StateFlow<ScreenUiState>`
- Use `viewModelScope` for coroutine launches
- Inject `EvoKammerService` via Koin
- No business logic in ViewModels — delegate all queries to the service

## Engine Connection Status

The `EvoKammerConnectionManager` (in a shared JVM utility class) manages the Ktor WebSocket
connection to the engine. It exposes:
- `connectionState: StateFlow<ConnectionState>` (Disconnected, Connecting, Connected, Error)
- Auto-reconnect with exponential backoff
- Heartbeat detection (engine must send heartbeat every 10s)

All screen ViewModels inject this manager and show a connection banner when disconnected.

## UI Conventions

- **All user-visible strings** must be Compose resources (in `commonMain/composeResources`) — no hardcoded strings except in `@Preview` functions
- Use existing `designsystem` components where possible (cards, buttons, text, theme)
- **JVM-only** custom components (charts, graphs, tables) placed in `jvmMain` locally
- Resource node graph visualisation: use a simple force-directed layout or grid layout initially
- KG facts table: sortable, filterable by each column
- Trust scores displayed as colored badge (red < 0.3, yellow 0.3-0.7, green > 0.7)
- Generation counter: always visible in monitor screen header

## Koin DI

```kotlin
// in jvmMain DI file (evokammerPresentationModule)
val evokammerPresentationModule = module {
    viewModel { EvoKammerDashboardViewModel(get()) }
    viewModel { RunConfigViewModel(get(), get()) }
    viewModel { (runId: String) -> LiveMonitorViewModel(runId, get()) }
    viewModel { (runId: String) -> EventLogViewModel(runId, get()) }
    viewModel { (runId: String, groupId: String) -> SubstrateBrowserViewModel(runId, groupId, get()) }
    viewModel { (runId: String) -> ScoringDashboardViewModel(runId, get()) }
    viewModel { (runId: String) -> PostRunAnalysisViewModel(runId, get()) }
}
```
