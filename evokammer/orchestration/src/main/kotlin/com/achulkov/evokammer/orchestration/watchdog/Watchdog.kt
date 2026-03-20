package com.achulkov.evokammer.orchestration.watchdog

import com.achulkov.evokammer.core.model.Role
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The Watchdog monitors all active agents and groups for anomalies and stasis.
 *
 * Per the EvoKammer spec:
 * - Runs continuously (no TTL) outside the round lifecycle
 * - Checks each agent's last-activity timestamp at [checkIntervalMs] intervals
 * - Emits [WatchdogSignal]s when stasis (inactivity > threshold) or budget overruns are detected
 * - Triggers Inspector/Auditor/MetaPlanner spawns via [WatchdogSignal]s
 *
 * @param checkIntervalMs How often the watchdog polls (default: 30s).
 * @param stasisThresholdMs How long an agent can be idle before a stasis flag is raised (default: 5min).
 */
class Watchdog(
    private val checkIntervalMs: Long = 30_000L,
    private val stasisThresholdMs: Long = 300_000L
) {

    private val _signals = MutableSharedFlow<WatchdogSignal>(extraBufferCapacity = 256)

    /** Emitted signals consumed by the [com.achulkov.evokammer.orchestration.lifecycle.LifecycleManager]. */
    val signals: SharedFlow<WatchdogSignal> = _signals.asSharedFlow()

    // Map of agentId -> last activity epoch millis
    private val agentLastActivity = mutableMapOf<String, Long>()
    private val agentGroupMap = mutableMapOf<String, String>()

    private var watchJob: Job? = null

    /** Starts the watchdog monitoring loop. */
    fun start(scope: CoroutineScope) {
        watchJob = scope.launch(Dispatchers.Default) {
            Napier.i("Watchdog: started (interval=${checkIntervalMs}ms stasis=${stasisThresholdMs}ms)")
            while (isActive) {
                delay(checkIntervalMs)
                runCheck()
            }
        }
    }

    /** Stops the watchdog. */
    fun stop() {
        watchJob?.cancel()
        watchJob = null
        Napier.i("Watchdog: stopped")
    }

    /** Called by agent runners to heartbeat activity. */
    fun heartbeat(agentId: String, groupId: String) {
        agentLastActivity[agentId] = System.currentTimeMillis()
        agentGroupMap[agentId] = groupId
    }

    /** Called when an agent dies; removes it from monitoring. */
    fun onAgentDied(agentId: String) {
        agentLastActivity.remove(agentId)
        agentGroupMap.remove(agentId)
    }

    private suspend fun runCheck() {
        val now = System.currentTimeMillis()
        for ((agentId, lastActive) in agentLastActivity.toMap()) {
            val idle = now - lastActive
            if (idle > stasisThresholdMs) {
                val groupId = agentGroupMap[agentId] ?: "unknown"
                Napier.w("Watchdog: agent $agentId (group=$groupId) stale for ${idle}ms")
                _signals.emit(WatchdogSignal.AgentStasis(agentId, groupId, idle))
            }
        }
    }

    /** Manually trigger an inspector flag (called by Inspector agent tools). */
    suspend fun raiseFlag(
        groupId: String,
        severity: WatchdogSeverity,
        description: String,
        suggestedRole: Role? = null
    ) {
        Napier.w("Watchdog: flag raised group=$groupId severity=$severity: $description")
        _signals.emit(
            WatchdogSignal.InspectorFlag(groupId, severity, description, suggestedRole)
        )
    }
}

/** Severity levels for Watchdog flags. */
enum class WatchdogSeverity { LOW, MEDIUM, HIGH, CRITICAL }

/** Signals emitted by the [Watchdog]. */
sealed class WatchdogSignal {
    /** An agent has been idle longer than the stasis threshold. */
    data class AgentStasis(val agentId: String, val groupId: String, val idleMs: Long) : WatchdogSignal()

    /** Inspector agent raised a flag requiring Auditor or MetaPlanner intervention. */
    data class InspectorFlag(
        val groupId: String,
        val severity: WatchdogSeverity,
        val description: String,
        val suggestedRole: Role?
    ) : WatchdogSignal()

    /** A group's budget has dropped below a critical threshold. */
    data class BudgetCritical(val groupId: String, val remainingBudget: Double) : WatchdogSignal()
}
