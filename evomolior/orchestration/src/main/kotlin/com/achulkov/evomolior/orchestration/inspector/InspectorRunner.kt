package com.achulkov.evomolior.orchestration.inspector

import com.achulkov.evomolior.orchestration.watchdog.Watchdog
import com.achulkov.evomolior.orchestration.watchdog.WatchdogSeverity
import com.achulkov.evomolior.core.model.Role
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
 * Per the EvoMolior spec:
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
    private val seenObservationIds = mutableMapOf<String, MutableSet<String>>()

    /**
     * Starts an Inspector loop for [groupId].
     *
     * The loop reads typed append-only observations from [eventLogReader].
     */
    fun startFor(
        groupId: String,
        scope: CoroutineScope,
        eventLogReader: suspend () -> List<InspectorObservation>
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
        seenObservationIds.remove(groupId)
        Napier.i("InspectorRunner: stopped for group $groupId")
    }

    /** Stops all Inspector loops. */
    fun stopAll() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        seenObservationIds.clear()
        Napier.i("InspectorRunner: all inspectors stopped")
    }

    private suspend fun analyseEvents(groupId: String, events: List<InspectorObservation>) {
        if (events.isEmpty()) return
        val seen = seenObservationIds.getOrPut(groupId) { linkedSetOf() }
        val uniqueEvents = events.distinctBy { it.eventId }
        val newEvents = uniqueEvents.filter { seen.add(it.eventId) }
        if (newEvents.isEmpty()) return

        // A blocked action proves enforcement worked; only confirmed incidents enter the threshold.
        val violationCount = uniqueEvents.count {
            it.kind == InspectorObservationKind.CONSTITUTIONAL_ENFORCEMENT &&
                it.outcome == InspectorObservationOutcome.CONFIRMED_VIOLATION
        }
        val hasNewViolation = newEvents.any {
            it.kind == InspectorObservationKind.CONSTITUTIONAL_ENFORCEMENT &&
                it.outcome == InspectorObservationOutcome.CONFIRMED_VIOLATION
        }
        if (hasNewViolation && violationCount > uniqueEvents.size * 0.2) {
            watchdog.raiseFlag(
                groupId = groupId,
                severity = WatchdogSeverity.HIGH,
                description = "Inspector: $violationCount confirmed constitutional violations in " +
                    "${uniqueEvents.size} unique recent events",
                suggestedRole = com.achulkov.evomolior.core.model.Role.Auditor,
                observationIds = uniqueEvents.filter {
                    it.kind == InspectorObservationKind.CONSTITUTIONAL_ENFORCEMENT &&
                        it.outcome == InspectorObservationOutcome.CONFIRMED_VIOLATION
                }.mapTo(linkedSetOf(), InspectorObservation::eventId)
            )
        }

        // Heuristic: budget spike (many expensive actions in short window)
        val expensiveCount = uniqueEvents.count { it.kind == InspectorObservationKind.EXPENSIVE_ACTION }
        if (
            newEvents.any { it.kind == InspectorObservationKind.EXPENSIVE_ACTION } &&
            expensiveCount > uniqueEvents.size * 0.6
        ) {
            watchdog.raiseFlag(
                groupId = groupId,
                severity = WatchdogSeverity.MEDIUM,
                description = "Inspector: high spending rate ($expensiveCount/${uniqueEvents.size} expensive actions)",
                suggestedRole = null,
                observationIds = uniqueEvents.filter {
                    it.kind == InspectorObservationKind.EXPENSIVE_ACTION
                }.mapTo(linkedSetOf(), InspectorObservation::eventId)
            )
        }

        val turnTakingEvents = newEvents.count { it.kind == InspectorObservationKind.TURN_TAKING }
        if (turnTakingEvents > 0) {
            watchdog.raiseFlag(
                groupId = groupId,
                severity = WatchdogSeverity.MEDIUM,
                description = "Inspector: turn-taking imbalance detected in $turnTakingEvents recent flag(s)",
                suggestedRole = Role.PerspectiveTaker,
                observationIds = newEvents.filter {
                    it.kind == InspectorObservationKind.TURN_TAKING
                }.mapTo(linkedSetOf(), InspectorObservation::eventId)
            )
        }
    }
}
