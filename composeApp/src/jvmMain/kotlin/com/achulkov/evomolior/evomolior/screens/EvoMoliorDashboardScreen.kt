package com.achulkov.evomolior.evomolior.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.achulkov.evomolior.api.events.EvoMoliorEvent
import com.achulkov.evomolior.api.events.EvoMoliorEvent.EngineProgress
import com.achulkov.evomolior.api.service.EngineStatus
import com.achulkov.evomolior.api.service.GroupSummary
import com.achulkov.evomolior.evomolior.EvoMoliorRoute
import com.achulkov.evomolior.evomolior.LocalEvoMoliorNavigator
import com.achulkov.evomolior.evomolior.viewmodel.EvoMoliorViewModel
import com.achulkov.evomolior.evomolior.viewmodel.StubEvoMoliorEngineService
import java.util.Locale

/**
 * EvoMolior Dashboard — the main control panel screen.
 *
 * Shows:
 * - Engine status badge (IDLE / GENESIS / RUNNING / PAUSED / EVALUATING / COMPLETED)
 * - Group summary cards (budget, agent count, last score)
 * - Live event log (last 50 events)
 * - Quick action buttons (Start Run / Pause / Resume / Abort)
 */
@Composable
fun EvoMoliorDashboardScreen(
    viewModel: EvoMoliorViewModel = remember { EvoMoliorViewModel(StubEvoMoliorEngineService()) }
) {
    val navigate = LocalEvoMoliorNavigator.current
    val state by viewModel.engineState.collectAsState()
    val events by viewModel.recentEvents.collectAsState()
    val history by viewModel.gradingHistory.collectAsState()
    val availableRuns by viewModel.availableRuns.collectAsState()
    val graderGroupIds by viewModel.activeGraderGroupIds.collectAsState()

    Row(modifier = Modifier.fillMaxSize()) {
        // ── Left Panel: Status + Groups ──────────────────────────────────────
        Column(
            modifier = Modifier
                .weight(0.38f)
                .fillMaxHeight()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Status header
            Text(
                "EvoMolior",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Civilisational AI Control Panel",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(4.dp))

            // Status badge
            EngineStatusBadge(status = state.status)

            state.activeRunId?.let { runId ->
                Text(
                    "Run: ${runId.take(8)}…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                state.currentRound?.let { round ->
                    Text("Round $round", style = MaterialTheme.typography.bodySmall)
                }
            }

            Spacer(Modifier.height(4.dp))

            // Action buttons
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (state.status) {
                    EngineStatus.IDLE, EngineStatus.COMPLETED -> {
                        Button(onClick = { navigate(EvoMoliorRoute.RunSetup) }) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("New Run")
                        }
                        OutlinedButton(onClick = { navigate(EvoMoliorRoute.TestRunSetup) }) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Test Run")
                        }
                        if (state.status == EngineStatus.COMPLETED) {
                            OutlinedButton(
                                onClick = {
                                    navigate(
                                        EvoMoliorRoute.PostRunAnalysis(state.activeRunId ?: "")
                                    )
                                }
                            ) {
                                Text("View Analysis")
                            }
                        }
                    }
                    EngineStatus.RUNNING, EngineStatus.GENESIS -> {
                        OutlinedButton(onClick = { viewModel.pauseRun() }) {
                            Text("Pause")
                        }
                        Button(
                            onClick = { viewModel.abortRun() },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Abort")
                        }
                    }
                    EngineStatus.PAUSED -> {
                        Button(onClick = { viewModel.resumeRun() }) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Resume")
                        }
                        Button(
                            onClick = { viewModel.abortRun() },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("Abort")
                        }
                    }
                    else -> {}
                }
            }

            HorizontalDivider()

            // Group summary cards
            Text("Groups", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

            if (state.groupSummaries.isEmpty()) {
                Text(
                    "No active groups. Start a run to begin.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.groupSummaries) { summary ->
                        GroupSummaryCard(summary = summary) {
                            state.activeRunId?.let { navigate(EvoMoliorRoute.RunMonitor(it)) }
                        }
                    }
                }
            }

            // Grader groups section (shown once the first grader round has started)
            if (graderGroupIds.isNotEmpty()) {
                HorizontalDivider()
                Text(
                    "Grader Groups",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                val graderEvents = remember(events, graderGroupIds) {
                    events.filter { e ->
                        e is EvoMoliorEvent.GraderRoundStarted ||
                            e is EvoMoliorEvent.GraderRoundCompleted ||
                            (e is EngineProgress && e.phase.startsWith("grader_"))
                    }
                }
                graderGroupIds.forEach { graderId ->
                    val graderStatus = remember(graderEvents, graderId) {
                        buildGraderCardStatus(graderId = graderId, events = graderEvents)
                    }
                    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    "⚖️ $graderId",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    graderStatus.statusText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                graderStatus.lastPhase?.let { phase ->
                                    Text(
                                        "Last phase: ${graderPhaseLabel(phase.phase)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            Text(
                                "GRADER",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                }
            }

            // Grading history
            if (history.isNotEmpty()) {
                HorizontalDivider()
                Text("Grading History", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(history) { result ->
                        Text(
                            "Round ${result.roundIndex}: winner=${result.winnerId} " +
                                "(${result.scores.joinToString { "${it.groupId}=${String.format(Locale.US, "%.1f", it.weightedTotal)}" }})",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            val completedRuns = remember(availableRuns) { availableRuns.filter { it.isCompleted } }
            if (completedRuns.isNotEmpty()) {
                HorizontalDivider()
                Text("Completed Runs", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(completedRuns) { run ->
                        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    run.runId,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    "${run.groupIds.size} groups • completed run",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = { navigate(EvoMoliorRoute.PostRunAnalysis(run.runId)) }) {
                                        Text("Analysis")
                                    }
                                    TextButton(onClick = { navigate(EvoMoliorRoute.SubstrateBrowser(runId = run.runId)) }) {
                                        Text("Substrate")
                                    }
                                    TextButton(onClick = { navigate(EvoMoliorRoute.LogViewer(run.runId)) }) {
                                        Text("Logs")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        VerticalDivider()

        // ── Right Panel: Live Event Log ───────────────────────────────────────
        Column(
            modifier = Modifier
                .weight(0.62f)
                .fillMaxHeight()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Live Event Log", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "${events.size} events",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { navigate(EvoMoliorRoute.LogViewer()) }) {
                    Text("Full Log →")
                }
                state.activeRunId?.let {
                    TextButton(onClick = { navigate(EvoMoliorRoute.RunMonitor(it)) }) {
                        Text("Open Monitor →")
                    }
                }
                TextButton(onClick = { navigate(EvoMoliorRoute.SubstrateBrowser()) }) {
                    Text("Browse Substrate →")
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                    reverseLayout = false
                ) {
                    items(events.take(100)) { event ->
                        EventLogRow(event = event)
                    }
                }
            }
        }
    }
}

internal data class GraderCardStatus(
    val statusText: String,
    val lastPhase: EngineProgress? = null
)

internal fun buildGraderCardStatus(graderId: String, events: List<EvoMoliorEvent>): GraderCardStatus {
    val lastCompleted = events
        .filterIsInstance<EvoMoliorEvent.GraderRoundCompleted>()
        .firstOrNull { it.graderGroupId == graderId }
    val lastStarted = events
        .filterIsInstance<EvoMoliorEvent.GraderRoundStarted>()
        .firstOrNull { it.graderGroupId == graderId }
    val lastPhase = events
        .filterIsInstance<EngineProgress>()
        .filter { it.message.contains("[$graderId]") }
        .lastOrNull()

    val statusText = when {
        lastCompleted != null ->
            "R${lastCompleted.roundIndex} done — ${lastCompleted.totalScoresCollected} scores"
        lastPhase != null ->
            "${graderPhaseLabel(lastPhase.phase)} — ${lastPhase.message.removePrefix("[$graderId] ").take(70)}"
        lastStarted != null ->
            "R${lastStarted.roundIndex} evaluating…"
        else -> "pending"
    }

    return GraderCardStatus(statusText = statusText, lastPhase = lastPhase)
}

internal fun graderPhaseLabel(phase: String): String = when (phase) {
    "grader_criteria_start" -> "criteria start"
    "grader_criteria" -> "criteria"
    "grader_criteria_review" -> "criteria review"
    "grader_criteria_done" -> "criteria done"
    "grader_judge_start" -> "judge start"
    "grader_judge" -> "judge"
    "grader_judge_result" -> "judge result"
    "grader_judge_error" -> "judge error"
    "grader_error" -> "grader error"
    "grader_start" -> "grader start"
    "round_summary_result" -> "submission summary"
    else -> phase.removePrefix("grader_").replace('_', ' ')
}

@Composable
private fun EngineStatusBadge(status: EngineStatus) {
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
        color = color.copy(alpha = 0.15f)
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            color = color,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun GroupSummaryCard(summary: GroupSummary, onClick: () -> Unit) {
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(summary.groupId, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    summary.profileType,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column {
                    Text("Agents", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${summary.activeAgents}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                }
                Column {
                    Text("Budget", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(String.format(Locale.US, "%.0f", summary.remainingBudget), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                }
                summary.currentRoundScore?.let { score ->
                    Column {
                        Text("Score", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(String.format(Locale.US, "%.1f", score), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
internal fun EventLogRow(event: EvoMoliorEvent) {
    val (prefix, text) = when (event) {
        is EvoMoliorEvent.RunStarted -> "🚀" to "[RUN STARTED] ${event.runId.take(8)} — ${event.objective.take(60)}"
        is EvoMoliorEvent.RoundStarted -> "🔁" to "[ROUND ${event.roundIndex}] started"
        is EvoMoliorEvent.RoundCompleted -> "🏁" to "[ROUND ${event.result.roundIndex}] winner=${event.result.winnerId}"
        is EvoMoliorEvent.RunCompleted -> "✅" to "[RUN COMPLETED] winner=${event.winnerId ?: "none"}"
        is EvoMoliorEvent.RunAborted -> "❌" to "[ABORTED] ${event.reason}"
        is EvoMoliorEvent.AgentSpawned -> "🤖" to "[${event.groupId}] spawned ${event.role.name} (${event.agentId.take(6)})"
        is EvoMoliorEvent.AgentDied -> "💀" to "[${event.groupId}] agent ${event.agentId.take(6)} died: ${event.reason}"
        is EvoMoliorEvent.UsefulExitRateUpdated -> "📊" to
            "[${event.groupId}] useful exits ${event.usefulExitCount}/${event.ordinaryExitCount} " +
            "(${String.format(Locale.US, "%.1f", event.usefulExitRate * 100.0)}%)"
        is EvoMoliorEvent.AgentMessage -> {
            val icon = if (event.isToolCall) "🔧" else "💬"
            icon to "[${event.groupId}/${event.role.name}] ${event.content.take(120)}"
        }
        is EvoMoliorEvent.GroupBudgetChanged -> "💰" to "[${event.groupId}] budget=${String.format(Locale.US, "%.1f", event.remainingBudget)}"
        is EvoMoliorEvent.ResourceNodeClaimed -> "📦" to "[${event.groupId}] claimed node ${event.nodeId}"
        is EvoMoliorEvent.LeakSent -> {
            val corruptedTag = if (event.wasCorrupted) " [CORRUPTED]" else ""
            "🕳️" to "[${event.fromGroupId}→${event.toGroupId}] leak ${event.documentKey}$corruptedTag"
        }
        is EvoMoliorEvent.ConstitutionalViolation -> "⚠️" to "[${event.groupId}] VIOLATION law=${event.lawId}: ${event.description.take(80)}"
        is EvoMoliorEvent.InspectorFlag -> "🔍" to "[${event.groupId}] Inspector [${event.severity}]: ${event.description.take(80)}"
        is EvoMoliorEvent.StagingPromoted -> "⬆️" to "[${event.groupId}] staged ${event.submissionType} promoted"
        is EvoMoliorEvent.KGFactEmitted -> "🧠" to "[${event.groupId}] KG: ${event.subject} ${event.relation} ${event.`object`}"
        is EvoMoliorEvent.ToolRegistered -> "🔨" to "[${event.groupId}] tool '${event.toolName}' v${event.version} registered"
        is EvoMoliorEvent.DeadEndRecorded -> "🚫" to "[${event.groupId}] dead end: ${event.approach.take(60)}"
        is EvoMoliorEvent.LegacyProtocolExtracted -> "🧬" to "[${event.groupId}] legacy protocol extracted for ${event.role.name}"
        is EvoMoliorEvent.StageAdvanced -> "📈" to "[${event.groupId}] stage ${event.fromStage}→${event.toStage} (score=${String.format(Locale.US, "%.2f", event.checkinScore)})"
        // New engine visibility events
        is EvoMoliorEvent.EngineProgress -> {
            val icon = when (event.phase) {
                "spawning" -> "⚙️"
                "round_end", "round_start" -> "🔄"
                "round_wait" -> "⏳"
                "spawn_error" -> "❗"
                "shuru_unavailable" -> "🚫"
                "critic" -> "🔬"
                "final_answer" -> "🏁"
                else -> "📡"
            }
            icon to "[ENGINE] ${event.message.take(120)}"
        }
        is EvoMoliorEvent.AgentSpawnFailed -> "❗" to "[${event.groupId}] spawn FAILED ${event.role.name} (${event.agentId.take(6)}): ${event.error.take(80)}"
        is EvoMoliorEvent.CriticPipelineCompleted -> "🔬" to "[${event.groupId}] critic r${event.roundIndex}: +${event.promoted} promoted, -${event.rejected} rejected"
        is EvoMoliorEvent.WatchdogAlert -> "⚠️" to "[${event.groupId}] watchdog ${event.signalType}: ${event.details.take(80)}"
        is EvoMoliorEvent.ShuruStatus -> {
            val icon = if (event.available) "✅" else "❌"
            icon to "[SHURU] ${event.message.take(120)}"
        }
        is EvoMoliorEvent.GenesisCouncilCompleted -> {
            val summary = "${event.groupCount} groups, ${event.stageCount} stages" +
                if (event.usedDefault) " [DEFAULTS]" else ""
            "🌱" to "[GENESIS] $summary"
        }
        is EvoMoliorEvent.ObjectiveExpertBundleSelected ->
            "🎯" to "[EXPERTS] ${event.bundleName} selected"
        is EvoMoliorEvent.RunGraphPrepared ->
            "🕸️" to "[RUN GRAPH] prepared from ${event.sourceRunId.take(8)} (${event.edgeCount} edges)"
        is EvoMoliorEvent.RunGraphExpanded ->
            "🕸️" to "[RUN GRAPH] expanded from ${event.sourceRunId.take(8)} (${event.edgeCount} edges)"
        is EvoMoliorEvent.GraderRoundStarted ->
            "⚖️" to "[GRADER ${event.graderGroupId}] R${event.roundIndex} evaluating ${event.competingGroupIds.size} groups"
        is EvoMoliorEvent.GraderRoundCompleted ->
            "⚖️" to "[GRADER ${event.graderGroupId}] R${event.roundIndex} complete — ${event.totalScoresCollected} scores"
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(prefix, style = MaterialTheme.typography.bodySmall)
        Text(
            text,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp
            ),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2
        )
    }
}
