package com.achulkov.evomolior.evokammer

import com.achulkov.evokammer.api.events.EvoKammerEvent
import com.achulkov.evomolior.evokammer.debug.DebugLogEntry
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
 * Utilities for exporting EvoKammer log content to the system clipboard or a text file.
 *
 * All functions are JVM-only (desktop target): they rely on AWT for clipboard access
 * and Swing for the native file-save dialog.
 */

/**
 * Converts an [EvoKammerEvent] to a single-line plain-text log string for clipboard/file export.
 *
 * Produces full (un-truncated) content — unlike the in-UI rows which may abbreviate long fields.
 *
 * @return A human-readable log line prefixed with an emoji type indicator.
 */
fun EvoKammerEvent.toLogLine(): String = when (this) {
    is EvoKammerEvent.RunStarted ->
        "🚀 [RUN] ${runId.take(8)} — $objective"

    is EvoKammerEvent.RoundStarted ->
        "🔁 [ROUND $roundIndex] started (run=${runId.take(8)})"

    is EvoKammerEvent.RoundCompleted ->
        "🏁 [ROUND ${result.roundIndex}] winner=${result.winnerId} | " +
            result.scores.joinToString { "${it.groupId}=${String.format(Locale.US, "%.2f", it.weightedTotal)}" }

    is EvoKammerEvent.RunCompleted ->
        "✅ [COMPLETED] winner=${winnerId ?: "none"} (run=${runId.take(8)})"

    is EvoKammerEvent.RunAborted ->
        "❌ [ABORTED] $reason (run=${runId.take(8)})"

    is EvoKammerEvent.AgentSpawned ->
        "🤖 [$groupId] +${role.name} (${agentId.take(8)})"

    is EvoKammerEvent.AgentDied ->
        "💀 [$groupId] ✝ ${agentId.take(8)} — $reason"

    is EvoKammerEvent.AgentMessage ->
        "${if (isToolCall) "🔧" else "💬"} [$groupId/${role.name}] $content"

    is EvoKammerEvent.GroupBudgetChanged ->
        "💰 [$groupId] budget → ${String.format(Locale.US, "%.1f", remainingBudget)}"

    is EvoKammerEvent.ResourceNodeClaimed ->
        "📦 [$groupId] claimed $nodeId"

    is EvoKammerEvent.LeakSent ->
        "${if (wasCorrupted) "⚠️" else "🕳️"} [$fromGroupId→$toGroupId] leak $documentKey${if (wasCorrupted) " [CORRUPTED]" else ""}"

    is EvoKammerEvent.ConstitutionalViolation ->
        "⛔ [$groupId] VIOLATION law=$lawId: $description"

    is EvoKammerEvent.InspectorFlag ->
        "🔍 [$groupId] Inspector [$severity]: $description"

    is EvoKammerEvent.StagingPromoted ->
        "⬆️ [$groupId] staged '$submissionType' promoted (${submissionId.take(8)})"

    is EvoKammerEvent.KGFactEmitted ->
        "🧠 [$groupId] KG: $subject $relation ${`object`} (conf=${String.format(Locale.US, "%.2f", confidence)})"

    is EvoKammerEvent.ToolRegistered ->
        "🔨 [$groupId] tool '$toolName' v$version registered by ${authorAgentId.take(8)}"

    is EvoKammerEvent.DeadEndRecorded ->
        "🚫 [$groupId] dead end: $approach — $reason"

    is EvoKammerEvent.LegacyProtocolExtracted ->
        "🧬 [$groupId] legacy protocol extracted for ${role.name} (gen $fromGeneration→expires $expiresAtGeneration)"

    is EvoKammerEvent.StageAdvanced ->
        "📈 [$groupId] stage $fromStage→$toStage (score=${String.format(Locale.US, "%.2f", checkinScore)})"

    is EvoKammerEvent.AgentSpawnFailed ->
        "💥 [$groupId] SPAWN FAILED ${role.name} (${agentId.take(8)}): $error"

    is EvoKammerEvent.CriticPipelineCompleted ->
        "🔬 [$groupId] critic R$roundIndex: +$promoted promoted, -$rejected rejected"

    is EvoKammerEvent.EngineProgress ->
        if (phase.startsWith("grader_")) {
            "⚖️ [GRADER ${phase.uppercase()}] $message${progressFraction?.let { " (${String.format(Locale.US, "%.0f", it * 100)}%)" } ?: ""}"
        } else if (phase == "final_answer") {
            "🏁 [FINAL ANSWER] $message${progressFraction?.let { " (${String.format(Locale.US, "%.0f", it * 100)}%)" } ?: ""}"
        } else {
            "⚙️ [${phase.uppercase()}] $message${progressFraction?.let { " (${String.format(Locale.US, "%.0f", it * 100)}%)" } ?: ""}"
        }

    is EvoKammerEvent.ShuruStatus ->
        "${if (available) "✅" else "⚠️"} [SHURU] ${if (available) "v$version" else "UNAVAILABLE"} — $message"

    is EvoKammerEvent.WatchdogAlert ->
        "🚨 [$groupId] WATCHDOG ${signalType.uppercase()}: $details"

    is EvoKammerEvent.GenesisCouncilCompleted -> buildString {
        append("🌱 [GENESIS] ${if (usedDefault) "⚠ DEFAULTS" else "council complete"} — ")
        append("$groupCount groups, $stageCount stages, ")
        append("$primordialKbCount KB seeds, $primordialFactCount KG seeds")
        if (modelAssignment.isNotEmpty()) {
            append(" | models: ${modelAssignment.entries.joinToString(" ") { "${it.key}→${it.value.substringAfterLast("/")}" }}")
        }
    }

    is EvoKammerEvent.GraderRoundStarted ->
        "⚖️ [GRADER $graderGroupId] R$roundIndex started — evaluating ${competingGroupIds.joinToString()}"

    is EvoKammerEvent.GraderRoundCompleted ->
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
 * @param defaultFilename Suggested filename shown in the dialog (e.g. "evokammer-events.txt").
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
