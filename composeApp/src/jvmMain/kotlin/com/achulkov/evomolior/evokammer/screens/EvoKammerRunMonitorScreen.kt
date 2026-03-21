package com.achulkov.evomolior.evokammer.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.achulkov.evokammer.api.events.EvoKammerEvent
import com.achulkov.evokammer.api.service.EngineStatus
import com.achulkov.evomolior.evokammer.EvoKammerRoute
import com.achulkov.evomolior.evokammer.LocalEvoKammerNavigator
import com.achulkov.evomolior.evokammer.viewmodel.EvoKammerViewModel
import com.achulkov.evomolior.evokammer.viewmodel.StubEvoKammerEngineService
import kotlinx.coroutines.flow.collectLatest

/**
 * Run Monitor screen — full live view of a running EvoKammer civilisation.
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
fun EvoKammerRunMonitorScreen(
    runId: String,
    viewModel: EvoKammerViewModel = remember { EvoKammerViewModel(StubEvoKammerEngineService()) }
) {
    val navigate = LocalEvoKammerNavigator.current
    val state by viewModel.engineState.collectAsState()
    val events by viewModel.recentEvents.collectAsState()
    val gradingHistory by viewModel.gradingHistory.collectAsState()

    var selectedGroupId by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

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
                IconButton(onClick = { navigate(EvoKammerRoute.Dashboard) }) {
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
                                Text("${String.format("%.0f", group.remainingBudget)} budget", style = MaterialTheme.typography.labelSmall)
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
                                                "#${score.rank} — ${String.format("%.1f", score.weightedTotal)}",
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

            // ── Right panel: live message stream ──────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val filteredEvents = remember(events, selectedGroupId) {
                    if (selectedGroupId == null) events
                    else events.filter { event ->
                        when (event) {
                            is EvoKammerEvent.AgentMessage -> event.groupId == selectedGroupId
                            is EvoKammerEvent.AgentSpawned -> event.groupId == selectedGroupId
                            is EvoKammerEvent.AgentDied -> event.groupId == selectedGroupId
                            is EvoKammerEvent.GroupBudgetChanged -> event.groupId == selectedGroupId
                            is EvoKammerEvent.ConstitutionalViolation -> event.groupId == selectedGroupId
                            is EvoKammerEvent.InspectorFlag -> event.groupId == selectedGroupId
                            is EvoKammerEvent.ResourceNodeClaimed -> event.groupId == selectedGroupId
                            else -> true // show run-level events always
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                }

                val listState = rememberLazyListState()
                LaunchedEffect(filteredEvents.size) {
                    if (filteredEvents.isNotEmpty()) listState.animateScrollToItem(0)
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
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
        }
    }
    } // end Scaffold
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
