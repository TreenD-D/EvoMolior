package com.achulkov.evokammer.orchestration.inspector

import com.achulkov.evokammer.orchestration.watchdog.Watchdog
import com.achulkov.evokammer.orchestration.watchdog.WatchdogSeverity
import com.achulkov.evokammer.core.model.Role
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Runs a continuous passive Inspector agent loop for each group.
 *
 * The Inspector reads the event log (from the substrate) at [checkIntervalMs] intervals,
 * looks for anomaly patterns (repeated constitutional violations, unusual spending spikes,
 * knowledge plagiarism flags), and raises Watchdog flags.
 *
 * Per the EvoKammer spec:
 * - Inspector has no TTL and never dies unless the run ends
 * - Inspector cannot write to the KB, staging, or WiP task list
 * - Inspector emits anomaly reports to the Watchdog only
 *
 * @param watchdog The [Watchdog] that receives Inspector flags.
 * @param checkIntervalMs Interval between log scans (default: 60s).
 */
class InspectorRunner(
    private val watchdog: Watchdog,
    private val checkIntervalMs: Long = 60_000L
) {

    private val jobs = mutableMapOf<String, Job>()

    /**
     * Starts an Inspector loop for [groupId].
     *
     * The loop reads from [eventLogReader] — a suspend function returning recent events
     * as plain strings (passed in to avoid a direct substrate dependency).
     */
    fun startFor(
        groupId: String,
        scope: CoroutineScope,
        eventLogReader: suspend () -> List<String>
    ) {
        if (jobs.containsKey(groupId)) {
            Napier.w("InspectorRunner: already running for group $groupId — skipping")
            return
        }
        jobs[groupId] = scope.launch(Dispatchers.Default) {
            Napier.i("InspectorRunner: started for group $groupId")
            while (isActive) {
                delay(checkIntervalMs)
                try {
                    val events = eventLogReader()
                    analyseEvents(groupId, events)
                } catch (e: Exception) {
                    Napier.e("InspectorRunner: error reading events for $groupId", e)
                }
            }
        }
    }

    /** Stops the Inspector loop for [groupId]. */
    fun stopFor(groupId: String) {
        jobs.remove(groupId)?.cancel()
        Napier.i("InspectorRunner: stopped for group $groupId")
    }

    /** Stops all Inspector loops. */
    fun stopAll() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        Napier.i("InspectorRunner: all inspectors stopped")
    }

    private suspend fun analyseEvents(groupId: String, events: List<String>) {
        if (events.isEmpty()) return

        // Heuristic: if >20% of recent events are CONSTITUTIONAL_VIOLATION, raise HIGH flag
        val violationCount = events.count { it.contains("CONSTITUTIONAL_VIOLATION") }
        if (violationCount > events.size * 0.2) {
            watchdog.raiseFlag(
                groupId = groupId,
                severity = WatchdogSeverity.HIGH,
                description = "Inspector: $violationCount constitutional violations in ${events.size} recent events",
                suggestedRole = com.achulkov.evokammer.core.model.Role.Auditor
            )
        }

        // Heuristic: budget spike (many expensive actions in short window)
        val expensiveCount = events.count { it.contains("LLM_CALL") || it.contains("WEB_FETCH") }
        if (expensiveCount > events.size * 0.6) {
            watchdog.raiseFlag(
                groupId = groupId,
                severity = WatchdogSeverity.MEDIUM,
                description = "Inspector: high spending rate ($expensiveCount/${events.size} expensive actions)",
                suggestedRole = null
            )
        }

        val turnTakingEvents = events.count { it.contains("turn_taking", ignoreCase = true) }
        if (turnTakingEvents > 0) {
            watchdog.raiseFlag(
                groupId = groupId,
                severity = WatchdogSeverity.MEDIUM,
                description = "Inspector: turn-taking imbalance detected in $turnTakingEvents recent flag(s)",
                suggestedRole = Role.PerspectiveTaker
            )
        }
    }
}
