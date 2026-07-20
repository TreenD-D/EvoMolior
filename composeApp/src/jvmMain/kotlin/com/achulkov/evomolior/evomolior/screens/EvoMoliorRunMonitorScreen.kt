package com.achulkov.evomolior.evomolior.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.achulkov.evomolior.api.events.EvoMoliorEvent
import com.achulkov.evomolior.api.service.EngineStatus
import com.achulkov.evomolior.evomolior.EvoMoliorRoute
import com.achulkov.evomolior.evomolior.LocalEvoMoliorNavigator
import com.achulkov.evomolior.evomolior.copyToClipboard
import com.achulkov.evomolior.evomolior.debug.DebugLogBridge
import com.achulkov.evomolior.evomolior.debug.DebugLogEntry
import com.achulkov.evomolior.evomolior.saveTextToFile
import com.achulkov.evomolior.evomolior.toLogLine
import com.achulkov.evomolior.evomolior.viewmodel.EvoMoliorViewModel
import com.achulkov.evomolior.evomolior.viewmodel.StubEvoMoliorEngineService
import io.github.aakira.napier.LogLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Run Monitor screen — full live view of a running EvoMolior civilisation.
 *
 * Shows:
 * - Run + round status bar at top
 * - Per-group tab panels with budget gauges + agent lists
 * - Full scrollable agent message stream (filtered by group)
 * - Scoring chart when grading results are available
 *
 * @param runId The ID of the run to monitor.
 */
@Composable
fun EvoMoliorRunMonitorScreen(
    runId: String,
    viewModel: EvoMoliorViewModel = remember { EvoMoliorViewModel(StubEvoMoliorEngineService()) }
) {
    val navigate = LocalEvoMoliorNavigator.current
    val state by viewModel.engineState.collectAsState()
    val events by viewModel.recentEvents.collectAsState()
    val gradingHistory by viewModel.gradingHistory.collectAsState()

    var selectedGroupId by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Debug log panel state — default to visible since bridge is always-on
    var debugLogsEnabled by remember { mutableStateOf(true) }
    val debugLogs by DebugLogBridge.logs.collectAsState()

    // NOTE: DebugLogBridge.enabled is always true (set at startup); the toggle only
    // controls the visibility of the debug panel in the UI, not capture of logs.

    // Auto-select first group when groups load
    LaunchedEffect(state.groupSummaries) {
        if (selectedGroupId == null && state.groupSummaries.isNotEmpty()) {
            selectedGroupId = state.groupSummaries.first().groupId
        }
    }

    // Collect and display errors from the engine via snackbar
    LaunchedEffect(viewModel) {
        viewModel.error.collectLatest { errorMessage ->
            snackbarHostState.showSnackbar(
                message = errorMessage,
                duration = SnackbarDuration.Long
            )
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
    Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
        // ── Top status bar ────────────────────────────────────────────────────
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            tonalElevation = 2.dp
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                IconButton(onClick = { navigate(EvoMoliorRoute.Dashboard) }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Text(
                    "Run: ${runId.take(8)}…",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.width(8.dp))
                RunStatusChip(status = state.status)
                state.currentRound?.let { round ->
                    Text("│ Round $round", style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.weight(1f))

                // ── Debug logs toggle ────────────────────────────────────────
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(
                        Icons.Filled.Info,
                        contentDescription = "Debug logs",
                        modifier = Modifier.size(16.dp),
                        tint = if (debugLogsEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Debug",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (debugLogsEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Switch(
                        checked = debugLogsEnabled,
                        onCheckedChange = { debugLogsEnabled = it },
                        modifier = Modifier.height(20.dp)
                    )
                }

                Spacer(Modifier.width(8.dp))

                TextButton(
                    onClick = {
                        navigate(
                            EvoMoliorRoute.SubstrateBrowser(
                                runId = runId,
                                groupId = selectedGroupId
                            )
                        )
                    }
                ) {
                    Text("Substrate →")
                }
                when (state.status) {
                    EngineStatus.RUNNING, EngineStatus.GENESIS -> {
                        OutlinedButton(onClick = { viewModel.pauseRun() }) { Text("Pause") }
                        Button(
                            onClick = { viewModel.abortRun() },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) { Text("Abort") }
                    }
                    EngineStatus.PAUSED -> {
                        Button(onClick = { viewModel.resumeRun() }) { Text("Resume") }
                    }
                    else -> {}
                }
            }
        }

        Row(modifier = Modifier.fillMaxSize()) {
            // ── Left panel: group tabs + budget + grading ─────────────────────
            Column(
                modifier = Modifier
                    .width(280.dp)
                    .fillMaxHeight()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Groups", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)

                state.groupSummaries.forEach { group ->
                    val isSelected = selectedGroupId == group.groupId
                    ElevatedCard(
                        onClick = { selectedGroupId = group.groupId },
                        modifier = Modifier.fillMaxWidth(),
                        elevation = CardDefaults.elevatedCardElevation(
                            defaultElevation = if (isSelected) 6.dp else 2.dp
                        ),
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = if (isSelected)
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(group.groupId.take(12), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                                Text(group.profileType, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                            LinearProgressIndicator(
                                progress = { (group.remainingBudget / 10000.0).toFloat().coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("${group.activeAgents} agents", style = MaterialTheme.typography.labelSmall)
                                Text("${String.format(Locale.US, "%.0f", group.remainingBudget)} budget", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }

                if (state.groupSummaries.isEmpty()) {
                    Text("Waiting for run to start…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                if (gradingHistory.isNotEmpty()) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Text("Grading Results", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(gradingHistory) { result ->
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Text("Round ${result.roundIndex}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                    result.scores.forEach { score ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(score.groupId.take(10), style = MaterialTheme.typography.labelSmall)
                                            Text(
                                                "#${score.rank} — ${String.format(Locale.US, "%.1f", score.weightedTotal)}",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = if (score.rank == 1) FontWeight.Bold else FontWeight.Normal,
                                                color = if (score.rank == 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            VerticalDivider()

            // ── Right panel: live message stream + optional debug log ─────────
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val scope = rememberCoroutineScope()
            val filteredEvents = remember(events, selectedGroupId) {
                    if (selectedGroupId == null) events
                    else events.filter { event ->
                        when (event) {
                            is EvoMoliorEvent.AgentMessage -> event.groupId == selectedGroupId
                            is EvoMoliorEvent.AgentSpawned -> event.groupId == selectedGroupId
                            is EvoMoliorEvent.AgentDied -> event.groupId == selectedGroupId
                            is EvoMoliorEvent.GroupBudgetChanged -> event.groupId == selectedGroupId
                            is EvoMoliorEvent.ConstitutionalViolation -> event.groupId == selectedGroupId
                            is EvoMoliorEvent.InspectorFlag -> event.groupId == selectedGroupId
                            is EvoMoliorEvent.ResourceNodeClaimed -> event.groupId == selectedGroupId
                            is EvoMoliorEvent.WatchdogAlert -> event.groupId == selectedGroupId
                            is EvoMoliorEvent.AgentSpawnFailed -> event.groupId == selectedGroupId
                            else -> true // show run-level events always
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Agent Stream ${selectedGroupId?.let { "— $it" } ?: "(all)"}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "${filteredEvents.size} events",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.weight(1f))
                    // ── Copy / export actions ────────────────────────────────
                    OutlinedButton(
                        onClick = {
                            val text = filteredEvents.joinToString("\n") { it.toLogLine() }
                            copyToClipboard(text)
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text("Copy All", style = MaterialTheme.typography.labelSmall)
                    }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                // Build text on IO thread to keep the work off the EDT,
                                // but show JFileChooser on the Main (EDT) thread after.
                                val text = kotlinx.coroutines.withContext(Dispatchers.IO) {
                                    filteredEvents.joinToString("\n") { it.toLogLine() }
                                }
                                val groupTag = selectedGroupId?.take(8) ?: "all"
                                saveTextToFile(text, "evomolior-stream-$groupTag.txt")
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text("Export…", style = MaterialTheme.typography.labelSmall)
                    }
                }

                val eventListWeight = if (debugLogsEnabled) 0.55f else 1f

                val listState = rememberLazyListState()
                LaunchedEffect(filteredEvents.size) {
                    if (filteredEvents.isNotEmpty()) listState.animateScrollToItem(0)
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(eventListWeight)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    SelectionContainer(modifier = Modifier.fillMaxSize()) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize().padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            items(filteredEvents) { event ->
                                EventLogRow(event = event)
                            }
                        }
                    }
                }

                // ── Debug log panel (shown when toggle is ON) ─────────────────
                if (debugLogsEnabled) {
                    HorizontalDivider()
                    DebugLogPanel(
                        logs = debugLogs,
                        modifier = Modifier.fillMaxWidth().weight(0.45f)
                    )
                }
            }
        }
    }
    } // end Scaffold
}

@Composable
private fun DebugLogPanel(
    logs: List<DebugLogEntry>,
    modifier: Modifier = Modifier
) {
    var levelFilter by remember { mutableStateOf<LogLevel?>(null) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // Header with filter chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(Icons.Filled.Info, contentDescription = null, modifier = Modifier.size(14.dp))
            Text("Debug Logs", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text("(${logs.size})", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))

            // Level filter chips
            listOf(null to "ALL", LogLevel.ERROR to "ERROR", LogLevel.WARNING to "WARN", LogLevel.INFO to "INFO").forEach { (level, label) ->
                val selected = levelFilter == level
                FilterChip(
                    selected = selected,
                    onClick = { levelFilter = level },
                    label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.height(28.dp)
                )
            }

            // Copy + Export buttons
            val scope = rememberCoroutineScope()
            // Copy All — copies currently visible logs to clipboard for easy pasting
            TextButton(
                onClick = {
                    val filteredSnapshot = if (levelFilter == null) logs else logs.filter { it.level == levelFilter }
                    val text = filteredSnapshot.joinToString("\n") { it.toLogLine() }
                    copyToClipboard(text)
                },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text("Copy All", style = MaterialTheme.typography.labelSmall)
            }
            // Export to file
            TextButton(
                onClick = {
                    val filteredSnapshot = if (levelFilter == null) logs else logs.filter { it.level == levelFilter }
                    scope.launch {
                        val text = kotlinx.coroutines.withContext(Dispatchers.IO) {
                            filteredSnapshot.joinToString("\n") { it.toLogLine() }
                        }
                        saveTextToFile(text, "evomolior-debug.txt")
                    }
                },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text("Export…", style = MaterialTheme.typography.labelSmall)
            }
            // Clear button
            TextButton(
                onClick = { DebugLogBridge.clear() },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text("Clear", style = MaterialTheme.typography.labelSmall)
            }
        }

        val filtered = remember(logs, levelFilter) {
            if (levelFilter == null) logs
            else logs.filter { it.level == levelFilter }
        }

        val debugListState = rememberLazyListState()
        LaunchedEffect(filtered.size) {
            if (filtered.isNotEmpty()) debugListState.animateScrollToItem(0)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF1A1A2E))
        ) {
            // Note: SelectionContainer does NOT work reliably with LazyColumn (items are
            // recycled, selection state is lost). Each row instead wraps its text content
            // in an individual SelectionContainer for per-row selection. Use "Copy All"
            // or "Export…" to copy the full log.
            LazyColumn(
                state = debugListState,
                modifier = Modifier.fillMaxSize().padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                items(filtered) { entry ->
                    DebugLogRow(entry)
                }
            }
        }
    }
}

@Composable
private fun DebugLogRow(entry: DebugLogEntry) {
    val timeStr = remember(entry.timestamp) {
        SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(entry.timestamp))
    }
    val (levelLabel, levelColor) = when (entry.level) {
        LogLevel.ERROR -> "E" to Color(0xFFFF6B6B)
        LogLevel.WARNING -> "W" to Color(0xFFFFD93D)
        LogLevel.INFO -> "I" to Color(0xFF6BCB77)
        LogLevel.DEBUG -> "D" to Color(0xFF9E9E9E)
        LogLevel.VERBOSE -> "V" to Color(0xFF757575)
        LogLevel.ASSERT -> "A" to Color(0xFFFF6B6B)
    }
    val tagText = entry.tag?.let { "[$it] " } ?: ""
    // Wrap in SelectionContainer so each row's text is independently selectable via mouse drag.
    // Full message is shown (no maxLines truncation) so nothing is hidden from selection.
    SelectionContainer {
        Text(
            text = "$timeStr $levelLabel $tagText${entry.message}",
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 10.sp),
            color = levelColor.copy(alpha = 0.9f),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun RunStatusChip(status: EngineStatus) {
    val (label, color) = when (status) {
        EngineStatus.IDLE -> "IDLE" to MaterialTheme.colorScheme.outline
        EngineStatus.GENESIS -> "GENESIS" to MaterialTheme.colorScheme.tertiary
        EngineStatus.RUNNING -> "RUNNING" to MaterialTheme.colorScheme.primary
        EngineStatus.PAUSED -> "PAUSED" to MaterialTheme.colorScheme.secondary
        EngineStatus.EVALUATING -> "EVALUATING" to MaterialTheme.colorScheme.tertiary
        EngineStatus.COMPLETED -> "COMPLETED" to MaterialTheme.colorScheme.secondary
    }
    Surface(
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.2f)
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Bold
        )
    }
}
