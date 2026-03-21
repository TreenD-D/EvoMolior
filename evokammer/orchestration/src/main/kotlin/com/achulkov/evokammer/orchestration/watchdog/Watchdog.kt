package com.achulkov.evokammer.orchestration.watchdog

import com.achulkov.evokammer.core.model.Role
import com.achulkov.evokammer.substrate.SubstrateDatabaseFactory
import io.github.aakira.napier.Napier
import java.util.Locale
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
 * Per the EvoKammer spec §19:
 * - Runs continuously (no TTL) outside the round lifecycle
 * - Checks each agent's last-activity timestamp at [checkIntervalMs] intervals
 * - Emits [WatchdogSignal]s when stasis, budget critical, or KG anomalies are detected
 * - KG-aware checks: KG-density ratio and KG inconsistency rate
 *
 * @param checkIntervalMs How often the watchdog polls (default: 30s).
 * @param stasisThresholdMs How long an agent can be idle before a stasis flag is raised (default: 5min).
 * @param kgDensityMinRatio Minimum KG-facts-to-KB-entries ratio before emitting [WatchdogSignal.KgAnomaly].
 *   A ratio below this means agents are writing KB docs but not extracting structured KG facts.
 * @param kgInconsistencyMaxRatio Maximum ratio of conflicting KG facts before raising a KG anomaly.
 * @param substrateDatabaseFactory Optional factory for KG-aware checks. If null, KG checks are skipped.
 * @param runId  Current run identifier (required for KG checks).
 */
class Watchdog(
    private val checkIntervalMs: Long = 30_000L,
    private val stasisThresholdMs: Long = 300_000L,
    private val kgDensityMinRatio: Double = 0.2,
    private val kgInconsistencyMaxRatio: Double = 0.15,
    private val substrateDatabaseFactory: SubstrateDatabaseFactory? = null,
    private val runId: String? = null
) {

    private val _signals = MutableSharedFlow<WatchdogSignal>(extraBufferCapacity = 256)

    /** Emitted signals consumed by the orchestration layer (LifecycleManager / EvoKammerRunService). */
    @Suppress("unused")
    val signals: SharedFlow<WatchdogSignal> = _signals.asSharedFlow()

    // Map of agentId -> last activity epoch millis
    private val agentLastActivity = mutableMapOf<String, Long>()
    private val agentGroupMap = mutableMapOf<String, String>()

    // Per-group budget tracking for BudgetCritical signals
    private val groupBudgets = mutableMapOf<String, Double>()

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

    /** Stops the watchdog. Called by EvoKammerRunService on run completion/abort. */
    @Suppress("unused")
    fun stop() {
        watchJob?.cancel()
        watchJob = null
        Napier.i("Watchdog: stopped")
    }

    /** Called by agent runners to heartbeat activity. */
    @Suppress("unused")
    fun heartbeat(agentId: String, groupId: String) {
        agentLastActivity[agentId] = System.currentTimeMillis()
        agentGroupMap[agentId] = groupId
    }

    /** Called when an agent dies; removes it from monitoring. */
    @Suppress("unused")
    fun onAgentDied(agentId: String) {
        agentLastActivity.remove(agentId)
        agentGroupMap.remove(agentId)
    }

    /**
     * Called by the LifecycleManager when a group's remaining budget is updated.
     * Triggers a [WatchdogSignal.BudgetCritical] when budget drops below [criticalFraction].
     *
     * @param groupId The group whose budget changed.
     * @param remainingBudget The current remaining budget.
     * @param totalBudget The total budget for the group (used to compute fraction).
     * @param criticalFraction Threshold at which to raise the critical signal (default: 10%).
     */
    @Suppress("unused")
    suspend fun updateBudget(
        groupId: String,
        remainingBudget: Double,
        totalBudget: Double,
        criticalFraction: Double = 0.10
    ) {
        val previous = groupBudgets[groupId]
        groupBudgets[groupId] = remainingBudget
        val fraction = if (totalBudget > 0) remainingBudget / totalBudget else 0.0
        if (fraction < criticalFraction && (previous == null || previous / totalBudget >= criticalFraction)) {
            Napier.w("Watchdog: group $groupId budget CRITICAL — $remainingBudget / $totalBudget (${(fraction * 100).toInt()}%)")
            _signals.emit(WatchdogSignal.BudgetCritical(groupId, remainingBudget))
        }
    }

    private suspend fun runCheck() {
        checkAgentStasis()
        checkKgAnomalies()
    }

    private suspend fun checkAgentStasis() {
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

    /**
     * Checks KG health metrics across all active groups:
     * - **KG density**: ratio of KG facts to KB entries. Low ratio means agents are not extracting structured knowledge.
     * - **KG inconsistency**: ratio of conflicting facts to total facts. High ratio means the KG is unreliable.
     *
     * Requires [substrateDatabaseFactory] and [runId] to be provided.
     */
    private suspend fun checkKgAnomalies() {
        val factory = substrateDatabaseFactory ?: return
        val rid = runId ?: return

        val activeGroups = agentGroupMap.values.toSet()
        for (groupId in activeGroups) {
            try {
                val db = factory.get(rid, groupId)
                val kbCount = db.knowledgeBaseDao().countActive(rid, groupId).toLong()
                val kgCount = db.knowledgeGraphFactDao().countActive(rid, groupId)
                val conflictCount = db.knowledgeGraphFactDao().countConflicts(rid, groupId)

                if (kbCount > 10) {
                    // KG density check — is the KG growing relative to the KB?
                    val densityRatio = kgCount.toDouble() / kbCount
                    if (densityRatio < kgDensityMinRatio) {
                        Napier.w("Watchdog: group $groupId KG density low ($kgCount facts / $kbCount KB entries = ${String.format(Locale.US, "%.2f", densityRatio)})")
                        _signals.emit(
                            WatchdogSignal.KgAnomaly(
                                groupId = groupId,
                                anomalyType = "LOW_KG_DENSITY",
                                details = "KG facts: $kgCount, KB entries: $kbCount, ratio: ${String.format(Locale.US, "%.3f", densityRatio)} < threshold $kgDensityMinRatio"
                            )
                        )
                    }
                }

                if (kgCount > 5) {
                    // Inconsistency rate check
                    val inconsistencyRatio = conflictCount.toDouble() / kgCount
                    if (inconsistencyRatio > kgInconsistencyMaxRatio) {
                        Napier.w("Watchdog: group $groupId KG inconsistency high ($conflictCount/$kgCount = ${String.format(Locale.US, "%.2f", inconsistencyRatio)})")
                        _signals.emit(
                            WatchdogSignal.KgAnomaly(
                                groupId = groupId,
                                anomalyType = "HIGH_KG_INCONSISTENCY",
                                details = "Conflicting facts: $conflictCount, total: $kgCount, rate: ${String.format(Locale.US, "%.3f", inconsistencyRatio)} > threshold $kgInconsistencyMaxRatio"
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Napier.w("Watchdog: KG check failed for group $groupId: ${e.message}")
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

/** Severity levels for Watchdog flags raised by Inspector agents. */
@Suppress("unused")
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

    /**
     * KG health anomaly detected for a group.
     *
     * @param anomalyType One of: "LOW_KG_DENSITY", "HIGH_KG_INCONSISTENCY"
     * @param details Human-readable breakdown of the anomaly metrics.
     */
    data class KgAnomaly(
        val groupId: String,
        val anomalyType: String,
        val details: String
    ) : WatchdogSignal()
}
