package com.achulkov.evomolior.evomolior

import com.achulkov.evomolior.api.events.EvoMoliorEvent
import com.achulkov.evomolior.evomolior.debug.DebugLogEntry
import io.github.aakira.napier.LogLevel
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Utilities for exporting EvoMolior log content to the system clipboard or a text file.
 *
 * All functions are JVM-only (desktop target): they rely on AWT for clipboard access
 * and Swing for the native file-save dialog.
 */

/**
 * Converts an [EvoMoliorEvent] to a single-line plain-text log string for clipboard/file export.
 *
 * Produces full (un-truncated) content — unlike the in-UI rows which may abbreviate long fields.
 *
 * @return A human-readable log line prefixed with an emoji type indicator.
 */
fun EvoMoliorEvent.toLogLine(): String = when (this) {
    is EvoMoliorEvent.RunStarted ->
        "🚀 [RUN] ${runId.take(8)} — $objective"

    is EvoMoliorEvent.RoundStarted ->
        "🔁 [ROUND $roundIndex] started (run=${runId.take(8)})"

    is EvoMoliorEvent.RoundCompleted ->
        "🏁 [ROUND ${result.roundIndex}] winner=${result.winnerId} | " +
            result.scores.joinToString { "${it.groupId}=${String.format(Locale.US, "%.2f", it.weightedTotal)}" }

    is EvoMoliorEvent.RunCompleted ->
        "✅ [COMPLETED] winner=${winnerId ?: "none"} (run=${runId.take(8)})"

    is EvoMoliorEvent.RunAborted ->
        "❌ [ABORTED] $reason (run=${runId.take(8)})"

    is EvoMoliorEvent.AgentSpawned ->
        "🤖 [$groupId] +${role.name} (${agentId.take(8)})"

    is EvoMoliorEvent.AgentDied ->
        "💀 [$groupId] ✝ ${agentId.take(8)} — $reason"

    is EvoMoliorEvent.UsefulExitRateUpdated ->
        "📊 [$groupId] useful exits $usefulExitCount/$ordinaryExitCount " +
            "(${String.format(Locale.US, "%.1f", usefulExitRate * 100.0)}%)"

    is EvoMoliorEvent.AgentMessage ->
        "${if (isToolCall) "🔧" else "💬"} [$groupId/${role.name}] $content"

    is EvoMoliorEvent.GroupBudgetChanged ->
        "💰 [$groupId] budget → ${String.format(Locale.US, "%.1f", remainingBudget)}"

    is EvoMoliorEvent.ResourceNodeClaimed ->
        "📦 [$groupId] claimed $nodeId"

    is EvoMoliorEvent.LeakSent ->
        "${if (wasCorrupted) "⚠️" else "🕳️"} [$fromGroupId→$toGroupId] leak $documentKey${if (wasCorrupted) " [CORRUPTED]" else ""}"

    is EvoMoliorEvent.ConstitutionalViolation ->
        "⛔ [$groupId] VIOLATION law=$lawId: $description"

    is EvoMoliorEvent.InspectorFlag ->
        "🔍 [$groupId] Inspector [$severity]: $description"

    is EvoMoliorEvent.StagingPromoted ->
        "⬆️ [$groupId] staged '$submissionType' promoted (${submissionId.take(8)})"

    is EvoMoliorEvent.KGFactEmitted ->
        "🧠 [$groupId] KG: $subject $relation ${`object`} (conf=${String.format(Locale.US, "%.2f", confidence)})"

    is EvoMoliorEvent.ToolRegistered ->
        "🔨 [$groupId] tool '$toolName' v$version registered by ${authorAgentId.take(8)}"

    is EvoMoliorEvent.DeadEndRecorded ->
        "🚫 [$groupId] dead end: $approach — $reason"

    is EvoMoliorEvent.LegacyProtocolExtracted ->
        "🧬 [$groupId] legacy protocol extracted for ${role.name} (gen $fromGeneration→expires $expiresAtGeneration)"

    is EvoMoliorEvent.StageAdvanced ->
        "📈 [$groupId] stage $fromStage→$toStage (score=${String.format(Locale.US, "%.2f", checkinScore)})"

    is EvoMoliorEvent.AgentSpawnFailed ->
        "💥 [$groupId] SPAWN FAILED ${role.name} (${agentId.take(8)}): $error"

    is EvoMoliorEvent.CriticPipelineCompleted ->
        "🔬 [$groupId] critic R$roundIndex: +$promoted promoted, -$rejected rejected"

    is EvoMoliorEvent.EngineProgress ->
        if (phase.startsWith("grader_")) {
            "⚖️ [GRADER ${phase.uppercase()}] $message${progressFraction?.let { " (${String.format(Locale.US, "%.0f", it * 100)}%)" } ?: ""}"
        } else if (phase == "final_answer") {
            "🏁 [FINAL ANSWER] $message${progressFraction?.let { " (${String.format(Locale.US, "%.0f", it * 100)}%)" } ?: ""}"
        } else {
            "⚙️ [${phase.uppercase()}] $message${progressFraction?.let { " (${String.format(Locale.US, "%.0f", it * 100)}%)" } ?: ""}"
        }

    is EvoMoliorEvent.ShuruStatus ->
        "${if (available) "✅" else "⚠️"} [SHURU] ${if (available) "v$version" else "UNAVAILABLE"} — $message"

    is EvoMoliorEvent.WatchdogAlert ->
        "🚨 [$groupId] WATCHDOG ${signalType.uppercase()}: $details"

    is EvoMoliorEvent.GenesisCouncilCompleted -> buildString {
        append("🌱 [GENESIS] ${if (usedDefault) "⚠ DEFAULTS" else "council complete"} — ")
        append("$groupCount groups, $stageCount stages, ")
        append("$primordialKbCount KB seeds, $primordialFactCount KG seeds")
        if (modelAssignment.isNotEmpty()) {
            append(" | models: ${modelAssignment.entries.joinToString(" ") { "${it.key}→${it.value.substringAfterLast("/")}" }}")
        }
    }

    is EvoMoliorEvent.ObjectiveExpertBundleSelected ->
        "🎯 [EXPERTS] $bundleName selected for ${objective.take(96)}"

    is EvoMoliorEvent.RunGraphPrepared ->
        "🕸️ [RUN GRAPH] $sourceRunId → $runId, $edgeCount edges, depth $ancestryDepth, $routedContextCount routed context items"

    is EvoMoliorEvent.RunGraphExpanded ->
        "🕸️ [RUN GRAPH] expanded from $sourceRunId → $runId, $edgeCount edges, depth $ancestryDepth"

    is EvoMoliorEvent.GraderRoundStarted ->
        "⚖️ [GRADER $graderGroupId] R$roundIndex started — evaluating ${competingGroupIds.joinToString()}"

    is EvoMoliorEvent.GraderRoundCompleted ->
        "⚖️ [GRADER $graderGroupId] R$roundIndex complete — $totalScoresCollected scores collected " +
            "(${scoresPerGroup.size} groups)"
}

/**
 * Converts a [DebugLogEntry] to a plain-text log line with timestamp and level prefix.
 *
 * @return A formatted log line suitable for export, e.g. `14:23:01.456 E tag: message text`.
 */
fun DebugLogEntry.toLogLine(): String {
    val timeStr = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(timestamp))
    val levelLabel = when (level) {
        LogLevel.ERROR -> "E"
        LogLevel.WARNING -> "W"
        LogLevel.INFO -> "I"
        LogLevel.DEBUG -> "D"
        LogLevel.VERBOSE -> "V"
        LogLevel.ASSERT -> "A"
    }
    val tagPrefix = tag?.let { "[$it] " } ?: ""
    return "$timeStr $levelLabel $tagPrefix$message"
}

/**
 * Copies [text] to the system clipboard using AWT.
 *
 * @param text The plain-text content to place on the clipboard.
 */
fun copyToClipboard(text: String) {
    val clipboard = Toolkit.getDefaultToolkit().systemClipboard
    clipboard.setContents(StringSelection(text), null)
}

/**
 * Opens a native Swing file-save dialog and writes [content] to the chosen file.
 *
 * A ".txt" extension is appended automatically if the user omits it.
 *
 * @param content The text content to write.
 * @param defaultFilename Suggested filename shown in the dialog (e.g. "evomolior-events.txt").
 */
fun saveTextToFile(content: String, defaultFilename: String) {
    val chooser = JFileChooser()
    chooser.selectedFile = File(defaultFilename)
    chooser.fileFilter = FileNameExtensionFilter("Text files (*.txt)", "txt")
    chooser.dialogTitle = "Export Log"
    if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
        var file = chooser.selectedFile
        if (!file.name.endsWith(".txt", ignoreCase = true)) {
            file = File(file.absolutePath + ".txt")
        }
        file.writeText(content)
    }
}
