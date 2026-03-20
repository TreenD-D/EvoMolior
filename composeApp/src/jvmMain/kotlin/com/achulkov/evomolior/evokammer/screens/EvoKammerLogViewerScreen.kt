package com.achulkov.evomolior.evokammer.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
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
import com.achulkov.evomolior.evokammer.EvoKammerRoute
import com.achulkov.evomolior.evokammer.LocalEvoKammerNavigator
import com.achulkov.evomolior.evokammer.viewmodel.EvoKammerViewModel
import com.achulkov.evomolior.evokammer.viewmodel.StubEvoKammerEngineService

private enum class LogFilter {
    ALL, AGENTS, BUDGET, LEAKS, VIOLATIONS, ROUNDS
}

/**
 * Full-page log viewer with per-category filter chips.
 *
 * Shows the same events as the Dashboard mini-log but:
 * - Full width with horizontal scroll for long lines
 * - Filter chips to narrow by category
 * - Jump-to-bottom button
 * - Live auto-scroll toggle
 */
@Composable
fun EvoKammerLogViewerScreen(
    viewModel: EvoKammerViewModel = remember { EvoKammerViewModel(StubEvoKammerEngineService()) }
) {
    val navigate = LocalEvoKammerNavigator.current
    val events by viewModel.recentEvents.collectAsState()
    var activeFilter by remember { mutableStateOf(LogFilter.ALL) }
    var autoScroll by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()

    val filtered = remember(events, activeFilter) {
        when (activeFilter) {
            LogFilter.ALL -> events
            LogFilter.AGENTS -> events.filterIsInstance<EvoKammerEvent.AgentSpawned>() +
                events.filterIsInstance<EvoKammerEvent.AgentDied>() +
                events.filterIsInstance<EvoKammerEvent.AgentMessage>()
            LogFilter.BUDGET -> events.filterIsInstance<EvoKammerEvent.GroupBudgetChanged>()
            LogFilter.LEAKS -> events.filterIsInstance<EvoKammerEvent.LeakSent>()
            LogFilter.VIOLATIONS -> events.filterIsInstance<EvoKammerEvent.ConstitutionalViolation>() +
                events.filterIsInstance<EvoKammerEvent.InspectorFlag>()
            LogFilter.ROUNDS -> events.filterIsInstance<EvoKammerEvent.RoundStarted>() +
                events.filterIsInstance<EvoKammerEvent.RoundCompleted>() +
                events.filterIsInstance<EvoKammerEvent.RunStarted>() +
                events.filterIsInstance<EvoKammerEvent.RunCompleted>() +
                events.filterIsInstance<EvoKammerEvent.RunAborted>()
        }
    }

    LaunchedEffect(filtered.size, autoScroll) {
        if (autoScroll && filtered.isNotEmpty()) {
            listState.animateScrollToItem(0)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // ── Toolbar ──────────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(onClick = { navigate(EvoKammerRoute.Dashboard) }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                "Event Log",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.weight(1f))
            Text(
                "${filtered.size} / ${events.size} events",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = autoScroll, onCheckedChange = { autoScroll = it })
                Spacer(Modifier.width(4.dp))
                Text("Auto-scroll", style = MaterialTheme.typography.bodySmall)
            }
        }

        // ── Filter chips ─────────────────────────────────────────────────────
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            LogFilter.entries.forEach { filter ->
                FilterChip(
                    selected = activeFilter == filter,
                    onClick = { activeFilter = filter },
                    label = { Text(filter.name.lowercase().replaceFirstChar { it.uppercase() }) },
                    leadingIcon = if (activeFilter == filter) ({
                        Icon(Icons.Default.Clear, contentDescription = null, modifier = Modifier.size(16.dp))
                    }) else null
                )
            }
        }

        HorizontalDivider()

        // ── Log entries ──────────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (filtered.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No events yet${if (activeFilter != LogFilter.ALL) " matching filter '${activeFilter.name}'" else ""}.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(filtered) { event ->
                        LogViewerRow(event)
                    }
                }
            }
        }
    }
}

@Composable
private fun LogViewerRow(event: EvoKammerEvent) {
    val (prefix, text, tint) = when (event) {
        is EvoKammerEvent.RunStarted -> Triple(
            "🚀", "[RUN] ${event.runId.take(8)} — ${event.objective.take(80)}",
            MaterialTheme.colorScheme.primary
        )
        is EvoKammerEvent.RoundStarted -> Triple(
            "🔁", "[ROUND ${event.roundIndex}] started",
            MaterialTheme.colorScheme.tertiary
        )
        is EvoKammerEvent.RoundCompleted -> Triple(
            "🏁", "[ROUND ${event.result.roundIndex}] winner=${event.result.winnerId} | " +
                event.result.scores.joinToString { "${it.groupId}=${String.format("%.2f", it.weightedTotal)}" },
            MaterialTheme.colorScheme.secondary
        )
        is EvoKammerEvent.RunCompleted -> Triple(
            "✅", "[COMPLETED] winner=${event.winnerId ?: "none"}",
            MaterialTheme.colorScheme.primary
        )
        is EvoKammerEvent.RunAborted -> Triple(
            "❌", "[ABORTED] ${event.reason}",
            MaterialTheme.colorScheme.error
        )
        is EvoKammerEvent.AgentSpawned -> Triple(
            "🤖", "[${event.groupId}] +${event.role.name} (${event.agentId.take(8)})",
            MaterialTheme.colorScheme.onSurface
        )
        is EvoKammerEvent.AgentDied -> Triple(
            "💀", "[${event.groupId}] ✝ ${event.agentId.take(8)} — ${event.reason}",
            MaterialTheme.colorScheme.onSurfaceVariant
        )
        is EvoKammerEvent.AgentMessage -> Triple(
            if (event.isToolCall) "🔧" else "💬",
            "[${event.groupId}/${event.role.name}] ${event.content}",
            MaterialTheme.colorScheme.onSurface
        )
        is EvoKammerEvent.GroupBudgetChanged -> Triple(
            "💰", "[${event.groupId}] budget → ${String.format("%.1f", event.remainingBudget)}",
            MaterialTheme.colorScheme.onSurfaceVariant
        )
        is EvoKammerEvent.ResourceNodeClaimed -> Triple(
            "📦", "[${event.groupId}] claimed ${event.nodeId}",
            MaterialTheme.colorScheme.onSurface
        )
        is EvoKammerEvent.LeakSent -> Triple(
            if (event.wasCorrupted) "⚠️" else "🕳️",
            "[${event.fromGroupId}→${event.toGroupId}] leak ${event.documentKey}" +
                if (event.wasCorrupted) " [CORRUPTED]" else "",
            if (event.wasCorrupted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary
        )
        is EvoKammerEvent.ConstitutionalViolation -> Triple(
            "⛔", "[${event.groupId}] VIOLATION law=${event.lawId}: ${event.description}",
            MaterialTheme.colorScheme.error
        )
        is EvoKammerEvent.InspectorFlag -> Triple(
            "🔍", "[${event.groupId}] Inspector [${event.severity}]: ${event.description}",
            if (event.severity == "HIGH") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
        )
        is EvoKammerEvent.StagingPromoted -> Triple(
            "⬆️", "[${event.groupId}] staged '${event.submissionType}' promoted (${event.submissionId.take(8)})",
            MaterialTheme.colorScheme.secondary
        )
        is EvoKammerEvent.KGFactEmitted -> Triple(
            "🧠", "[${event.groupId}] KG: ${event.subject} ${event.relation} ${event.`object`} (conf=${String.format("%.2f", event.confidence)})",
            MaterialTheme.colorScheme.tertiary
        )
        is EvoKammerEvent.ToolRegistered -> Triple(
            "🔨", "[${event.groupId}] tool '${event.toolName}' v${event.version} registered by ${event.authorAgentId.take(8)}",
            MaterialTheme.colorScheme.onSurface
        )
        is EvoKammerEvent.DeadEndRecorded -> Triple(
            "🚫", "[${event.groupId}] dead end: ${event.approach.take(80)} — ${event.reason.take(60)}",
            MaterialTheme.colorScheme.onSurfaceVariant
        )
        is EvoKammerEvent.LegacyProtocolExtracted -> Triple(
            "🧬", "[${event.groupId}] legacy protocol extracted for ${event.role.name} (gen ${event.fromGeneration}→expires ${event.expiresAtGeneration})",
            MaterialTheme.colorScheme.primary
        )
        is EvoKammerEvent.StageAdvanced -> Triple(
            "📈", "[${event.groupId}] stage ${event.fromStage}→${event.toStage} (score=${String.format("%.2f", event.checkinScore)})",
            MaterialTheme.colorScheme.secondary
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(prefix, style = MaterialTheme.typography.bodySmall)
        Text(
            text,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp
            ),
            color = tint
        )
    }
}
