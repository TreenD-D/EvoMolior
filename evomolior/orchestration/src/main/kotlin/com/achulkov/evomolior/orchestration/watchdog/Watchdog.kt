package com.achulkov.evomolior.orchestration.watchdog

import com.achulkov.evomolior.core.model.Role
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import io.github.aakira.napier.Napier
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
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
 * Per the EvoMolior spec §19:
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

    /** Emitted signals consumed by the orchestration layer (LifecycleManager / EvoMoliorRunService). */
    @Suppress("unused")
    val signals: SharedFlow<WatchdogSignal> = _signals.asSharedFlow()

    // Map of agentId -> last activity epoch millis
    private val agentLastActivity = mutableMapOf<String, Long>()
    private val agentGroupMap = mutableMapOf<String, String>()

    // Per-group budget tracking for BudgetCritical signals
    private val groupBudgets = ConcurrentHashMap<String, Double>()

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

    /** Stops the watchdog. Called by EvoMoliorRunService on run completion/abort. */
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

    /** Resets threshold-crossing state for a newly allocated generation budget. */
    fun beginGenerationBudget(groupId: String, totalBudget: Double) {
        require(totalBudget >= 0.0) { "Generation budget must be non-negative" }
        groupBudgets[groupId] = totalBudget
    }

    /**
     * Called by orchestration when a group's live generation budget is updated.
     * Triggers a [WatchdogSignal.BudgetCritical] when budget drops below [criticalFraction].
     *
     * @param groupId The group whose budget changed.
     * @param remainingBudget The current remaining budget.
     * @param totalBudget The total budget for the group (used to compute fraction).
     * @param generation Generation whose allocation is being observed.
     * @param criticalFraction Threshold at which to raise the critical signal (default: 10%).
     */
    suspend fun updateBudget(
        groupId: String,
        remainingBudget: Double,
        totalBudget: Double,
        generation: Int,
        criticalFraction: Double = 0.10
    ) {
        require(totalBudget >= 0.0) { "Generation budget must be non-negative" }
        require(criticalFraction in 0.0..1.0) { "Critical fraction must be between 0 and 1" }
        val previous = groupBudgets[groupId]
        groupBudgets[groupId] = remainingBudget
        if (totalBudget == 0.0) return
        val fraction = remainingBudget / totalBudget
        if (fraction <= criticalFraction && (previous == null || previous / totalBudget > criticalFraction)) {
            Napier.w("Watchdog: group $groupId budget CRITICAL — $remainingBudget / $totalBudget (${(fraction * 100).toInt()}%)")
            _signals.emit(
                WatchdogSignal.BudgetCritical(
                    groupId = groupId,
                    remainingBudget = remainingBudget,
                    generationBudget = totalBudget,
                    generation = generation
                )
            )
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
        suggestedRole: Role? = null,
        observationIds: Set<String> = emptySet()
    ) {
        Napier.w("Watchdog: flag raised group=$groupId severity=$severity: $description")
        _signals.emit(
            WatchdogSignal.InspectorFlag(
                groupId,
                severity,
                description,
                suggestedRole,
                observationIds
            )
        )
    }

    /**
     * B-5: Opens the check-in gate for the given round.
     *
     * Emits [WatchdogSignal.CheckInGateOpen] to signal that
     * `processDisclosureCheckIns` should now run. Called by `EvoMoliorRunService`
     * just before invoking the disclosure check-in step.
     *
     * @param roundIndex The round for which the gate is opened.
     */
    suspend fun openCheckInGate(roundIndex: Int) {
        Napier.d("Watchdog: check-in gate OPEN for round $roundIndex")
        _signals.emit(WatchdogSignal.CheckInGateOpen(roundIndex))
    }

    /**
     * B-5: Closes the check-in gate for the given round.
     *
     * Emits [WatchdogSignal.CheckInGateClosed] to signal that the disclosure
     * check-in window is now over. Called by `EvoMoliorRunService` immediately
     * after `processDisclosureCheckIns` completes.
     *
     * @param roundIndex The round for which the gate is closed.
     */
    suspend fun closeCheckInGate(roundIndex: Int) {
        Napier.d("Watchdog: check-in gate CLOSED for round $roundIndex")
        _signals.emit(WatchdogSignal.CheckInGateClosed(roundIndex))
    }

    /** Requests one bounded privacy-transformed leak delivery for each target group. */
    suspend fun requestLeakDelivery(targetGroupIds: List<String>, roundIndex: Int, domain: String? = null) {
        if (targetGroupIds.isEmpty()) return
        _signals.emit(
            WatchdogSignal.DeliverLeak(
                targetGroupIds = targetGroupIds.distinct(),
                roundIndex = roundIndex,
                domain = domain
            )
        )
    }

    /** Requests a leak-rate ceiling increase without delivering content immediately. */
    suspend fun requestLeakRateIncrease(
        targetGroupIds: List<String>,
        multiplier: Double = 1.5
    ) {
        require(multiplier >= 1.0) { "Leak-rate multiplier must be >= 1.0" }
        if (targetGroupIds.isEmpty()) return
        _signals.emit(WatchdogSignal.IncreaseLeakRate(targetGroupIds.distinct(), multiplier))
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
        val suggestedRole: Role?,
        val observationIds: Set<String> = emptySet()
    ) : WatchdogSignal()

    /** A group's live per-generation budget has dropped below its critical threshold. */
    data class BudgetCritical(
        val groupId: String,
        val remainingBudget: Double,
        val generationBudget: Double,
        val generation: Int
    ) : WatchdogSignal()

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

    /**
     * B-5: The check-in gate is now open for [roundIndex].
     *
     * `EvoMoliorRunService.processDisclosureCheckIns` listens for this signal
     * before running progressive-disclosure check-ins. Only submissions from the
     * current round are evaluated while the gate is open.
     */
    data class CheckInGateOpen(val roundIndex: Int) : WatchdogSignal()

    /**
     * B-5: The check-in gate is now closed for [roundIndex].
     *
     * Emitted by [Watchdog.closeCheckInGate] immediately after check-ins complete
     * so downstream consumers know the evaluation window is over.
     */
    data class CheckInGateClosed(val roundIndex: Int) : WatchdogSignal()

    /** Deliver at most one leak packet to each target using a deterministic neighbouring source. */
    data class DeliverLeak(
        val targetGroupIds: List<String>,
        val roundIndex: Int,
        val domain: String? = null
    ) : WatchdogSignal()

    /** Increase source leak ceilings for groups adjacent to the requested targets. */
    data class IncreaseLeakRate(
        val targetGroupIds: List<String>,
        val multiplier: Double
    ) : WatchdogSignal()
}
