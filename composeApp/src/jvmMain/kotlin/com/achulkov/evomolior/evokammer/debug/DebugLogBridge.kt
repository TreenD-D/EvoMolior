package com.achulkov.evomolior.evokammer.debug

import io.github.aakira.napier.Antilog
import io.github.aakira.napier.LogLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Bridges Napier log calls into a Compose-observable [StateFlow] so the UI can display
 * engine debug logs in real-time without requiring stdout monitoring.
 *
 * ## Usage
 *
 * Install in `main.kt`:
 * ```kotlin
 * Napier.base(DebugLogBridge.antilog)
 * ```
 *
 * Toggle collection in the UI:
 * ```kotlin
 * DebugLogBridge.enabled = true
 * ```
 *
 * Observe in Compose:
 * ```kotlin
 * val logs by DebugLogBridge.logs.collectAsState()
 * ```
 *
 * The bridge stores at most [MAX_ENTRIES] entries in a ring buffer (oldest are dropped).
 */
object DebugLogBridge {

    /** Maximum number of log entries kept in memory. */
    private const val MAX_ENTRIES = 2_000

    /** Controls whether this bridge captures Napier log calls. Default: true (always on). */
    @Volatile
    var enabled: Boolean = true

    private val _logs = MutableStateFlow<List<DebugLogEntry>>(emptyList())

    /** Ordered list of captured log entries (most recent first). Observe in Compose via `collectAsState()`. */
    @Suppress("MemberVisibilityCanBePrivate") // consumed by RunMonitorScreen debug panel
    val logs: StateFlow<List<DebugLogEntry>> = _logs.asStateFlow()

    /** Clears all captured log entries. */
    @Suppress("unused") // called from the debug panel clear button
    fun clear() {
        _logs.value = emptyList()
    }

    /**
     * Napier [Antilog] implementation that forwards all log calls to the bridge.
     * Install once via `Napier.base(DebugLogBridge.antilog)`.
     */
    val antilog: Antilog = BridgeAntilog()

    private fun append(entry: DebugLogEntry) {
        val current = _logs.value.toMutableList()
        current.add(0, entry) // prepend so newest is at index 0
        if (current.size > MAX_ENTRIES) {
            current.subList(MAX_ENTRIES, current.size).clear()
        }
        _logs.value = current
    }

    private class BridgeAntilog : Antilog() {
        override fun performLog(
            priority: LogLevel,
            tag: String?,
            throwable: Throwable?,
            message: String?
        ) {
            if (!enabled) return
            val text = buildString {
                if (!message.isNullOrBlank()) append(message)
                if (throwable != null) {
                    if (isNotEmpty()) append("\n")
                    // Include the full stack trace so crash causes are visible in the debug panel.
                    // throwable.toString() only shows the class + message without frames.
                    // Cap at 2KB to keep the ring buffer memory bounded.
                    val trace = throwable.stackTraceToString().trim()
                    append(if (trace.length > 2_048) trace.take(2_048) + "\n…(truncated)" else trace)
                }
            }
            if (text.isBlank()) return
            append(
                DebugLogEntry(
                    timestamp = System.currentTimeMillis(),
                    level = priority,
                    tag = tag,
                    message = text
                )
            )
        }
    }
}

/**
 * A single captured log entry from the Napier bridge.
 *
 * @param timestamp Wall-clock time in millis when the log was emitted.
 * @param level Napier log level.
 * @param tag Optional tag (class name or custom tag from the caller).
 * @param message The formatted log message (may include a throwable summary on a new line).
 */
data class DebugLogEntry(
    val timestamp: Long,
    val level: LogLevel,
    val tag: String?,
    val message: String
)
