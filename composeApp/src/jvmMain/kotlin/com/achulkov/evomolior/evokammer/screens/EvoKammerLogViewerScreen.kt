package com.achulkov.evomolior.evokammer.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
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
import com.achulkov.evomolior.evokammer.copyToClipboard
import com.achulkov.evomolior.evokammer.saveTextToFile
import com.achulkov.evomolior.evokammer.toLogLine
import com.achulkov.evomolior.evokammer.viewmodel.EvoKammerViewModel
import com.achulkov.evomolior.evokammer.viewmodel.StubEvoKammerEngineService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

private enum class LogFilter {
    ALL, AGENTS, BUDGET, LEAKS, VIOLATIONS, ROUNDS, GRADERS, ENGINE
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
    viewModel: EvoKammerViewModel = remember { EvoKammerViewModel(StubEvoKammerEngineService()) },
    archivedRunId: String? = null
) {
    val navigate = LocalEvoKammerNavigator.current
    val events by viewModel.recentEvents.collectAsState()
    val archivedLogText by viewModel.archivedLogText.collectAsState()
    var activeFilter by remember { mutableStateOf(LogFilter.ALL) }
    var autoScroll by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(archivedRunId) {
        if (archivedRunId != null) {
            viewModel.loadArchivedLog(archivedRunId)
        } else {
            viewModel.clearArchivedLog()
        }
    }

    if (archivedRunId != null) {
        ArchivedLogViewer(
            archivedRunId = archivedRunId,
            archivedLogText = archivedLogText,
            onBack = { navigate(EvoKammerRoute.Dashboard) }
        )
        return
    }

    val filtered = remember(events, activeFilter) {
        when (activeFilter) {
            LogFilter.ALL -> events
            LogFilter.AGENTS -> events.filterIsInstance<EvoKammerEvent.AgentSpawned>() +
                events.filterIsInstance<EvoKammerEvent.AgentDied>() +
                events.filterIsInstance<EvoKammerEvent.AgentMessage>() +
                events.filterIsInstance<EvoKammerEvent.AgentSpawnFailed>()
            LogFilter.BUDGET -> events.filterIsInstance<EvoKammerEvent.GroupBudgetChanged>()
            LogFilter.LEAKS -> events.filterIsInstance<EvoKammerEvent.LeakSent>()
            LogFilter.VIOLATIONS -> events.filterIsInstance<EvoKammerEvent.ConstitutionalViolation>() +
                events.filterIsInstance<EvoKammerEvent.InspectorFlag>() +
                events.filterIsInstance<EvoKammerEvent.WatchdogAlert>()
            LogFilter.ROUNDS -> events.filterIsInstance<EvoKammerEvent.RoundStarted>() +
                events.filterIsInstance<EvoKammerEvent.RoundCompleted>() +
                events.filterIsInstance<EvoKammerEvent.RunStarted>() +
                events.filterIsInstance<EvoKammerEvent.RunCompleted>() +
                events.filterIsInstance<EvoKammerEvent.RunAborted>() +
                events.filterIsInstance<EvoKammerEvent.CriticPipelineCompleted>() +
                events.filterIsInstance<EvoKammerEvent.GenesisCouncilCompleted>()
            LogFilter.GRADERS -> events.filterIsInstance<EvoKammerEvent.GraderRoundStarted>() +
                events.filterIsInstance<EvoKammerEvent.GraderRoundCompleted>() +
                events.filterIsInstance<EvoKammerEvent.EngineProgress>().filter { it.phase.startsWith("grader_") }
            // Engine-level lifecycle, Shuru VM status, and generic progress events
            LogFilter.ENGINE -> events.filterIsInstance<EvoKammerEvent.EngineProgress>() +
                events.filterIsInstance<EvoKammerEvent.ShuruStatus>()
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
            // ── Copy / export actions ─────────────────────────────────────────
            OutlinedButton(
                onClick = {
                    val text = filtered.joinToString("\n") { it.toLogLine() }
                    copyToClipboard(text)
                },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text("Copy All", style = MaterialTheme.typography.labelMedium)
            }
            OutlinedButton(
                onClick = {
                    scope.launch {
                        val text = kotlinx.coroutines.withContext(Dispatchers.IO) {
                            filtered.joinToString("\n") { it.toLogLine() }
                        }
                        saveTextToFile(text, "evokammer-events.txt")
                    }
                },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text("Export…", style = MaterialTheme.typography.labelMedium)
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
                // SelectionContainer makes all Text children selectable / copy-able
                SelectionContainer(modifier = Modifier.fillMaxSize()) {
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
}

@Composable
private fun ArchivedLogViewer(
    archivedRunId: String,
    archivedLogText: String?,
    onBack: () -> Unit
) {
    val lines = remember(archivedLogText) {
        archivedLogText
            ?.lineSequence()
            ?.filter { it.isNotBlank() }
            ?.toList()
            ?: emptyList()
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("Archived Run Log", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "Run: ${archivedRunId}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedButton(
                onClick = {
                    val text = archivedLogText.orEmpty()
                    copyToClipboard(text)
                }
            ) {
                Text("Copy All")
            }
            OutlinedButton(
                onClick = {
                    val text = archivedLogText.orEmpty()
                    saveTextToFile(text, "evokammer-archived-${archivedRunId.take(8)}.txt")
                }
            ) {
                Text("Export…")
            }
        }

        if (archivedLogText.isNullOrBlank()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No archived log file found for this run.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Text(
                "${lines.size} lines",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            HorizontalDivider()
            SelectionContainer {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(lines) { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        )
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
                event.result.scores.joinToString { "${it.groupId}=${String.format(Locale.US, "%.2f", it.weightedTotal)}" },
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
            "💰", "[${event.groupId}] budget → ${String.format(Locale.US, "%.1f", event.remainingBudget)}",
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
            "🧠", "[${event.groupId}] KG: ${event.subject} ${event.relation} ${event.`object`} (conf=${String.format(Locale.US, "%.2f", event.confidence)})",
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
            "📈", "[${event.groupId}] stage ${event.fromStage}→${event.toStage} (score=${String.format(Locale.US, "%.2f", event.checkinScore)})",
            MaterialTheme.colorScheme.secondary
        )

        is EvoKammerEvent.AgentSpawnFailed -> Triple(
            "💥",
            "[${event.groupId}] SPAWN FAILED ${event.role.name} (${event.agentId.take(8)}): ${event.error}",
            MaterialTheme.colorScheme.error
        )
        is EvoKammerEvent.CriticPipelineCompleted -> Triple(
            "🔬",
            "[${event.groupId}] critic R${event.roundIndex}: +${event.promoted} promoted, -${event.rejected} rejected",
            MaterialTheme.colorScheme.secondary
        )
        is EvoKammerEvent.EngineProgress -> {
            val icon = when {
                event.phase.startsWith("grader_") -> "⚖️"
                event.phase == "final_answer" -> "🏁"
                else -> "⚙️"
            }
            val color = when {
                event.phase.startsWith("grader_") -> MaterialTheme.colorScheme.primary
                event.phase == "final_answer" -> MaterialTheme.colorScheme.secondary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Triple(
                icon,
                if (event.phase.startsWith("grader_")) {
                    "[GRADER ${event.phase.uppercase()}] ${event.message}" +
                        (event.progressFraction?.let { " (${String.format(Locale.US, "%.0f", it * 100)}%)" } ?: "")
                } else if (event.phase == "final_answer") {
                    "[FINAL ANSWER] ${event.message}" +
                        (event.progressFraction?.let { " (${String.format(Locale.US, "%.0f", it * 100)}%)" } ?: "")
                } else {
                    "[${event.phase.uppercase()}] ${event.message}" +
                        (event.progressFraction?.let { " (${String.format(Locale.US, "%.0f", it * 100)}%)" } ?: "")
                },
                color
            )
        }
        is EvoKammerEvent.ShuruStatus -> Triple(
            if (event.available) "✅" else "⚠️",
            "[SHURU] ${if (event.available) "v${event.version}" else "UNAVAILABLE"} — ${event.message}",
            if (event.available) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
        is EvoKammerEvent.WatchdogAlert -> Triple(
            "🚨",
            "[${event.groupId}] WATCHDOG ${event.signalType.uppercase()}: ${event.details}",
            MaterialTheme.colorScheme.error
        )
        is EvoKammerEvent.GenesisCouncilCompleted -> Triple(
            "🌱",
            buildString {
                append("[GENESIS] ${if (event.usedDefault) "⚠ DEFAULTS" else "council complete"} — ")
                append("${event.groupCount} groups, ${event.stageCount} stages, ")
                append("${event.primordialKbCount} KB seeds, ${event.primordialFactCount} KG seeds | ")
                if (event.modelAssignment.isNotEmpty()) {
                    append("models: ${event.modelAssignment.entries.joinToString(" ") { "${it.key}→${it.value.substringAfterLast("/")}" }}")
                } else append("models: defaults")
            },
            if (event.usedDefault) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.primary
        )
        is EvoKammerEvent.GraderRoundStarted -> Triple(
            "🧑‍⚖️",
            "[GRADER ${event.graderGroupId}] R${event.roundIndex} evaluation started → " +
                event.competingGroupIds.joinToString(", "),
            MaterialTheme.colorScheme.tertiary
        )
        is EvoKammerEvent.GraderRoundCompleted -> Triple(
            "📋",
            buildString {
                append("[GRADER ${event.graderGroupId}] R${event.roundIndex} complete — ")
                append("${event.totalScoresCollected} scores collected | ")
                append(
                    event.scoresPerGroup.entries.joinToString("  ") { (gId, summary) ->
                        "${gId.substringAfterLast("-")}: $summary"
                    }
                )
            },
            MaterialTheme.colorScheme.primary
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
