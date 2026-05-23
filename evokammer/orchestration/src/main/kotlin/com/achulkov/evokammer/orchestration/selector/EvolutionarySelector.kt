package com.achulkov.evokammer.orchestration.selector

import com.achulkov.evokammer.core.contract.RoleAllocation
import com.achulkov.evokammer.core.contract.RoleAllocationEntry
import com.achulkov.evokammer.core.model.GroupProfile
import com.achulkov.evokammer.core.model.Role
import com.achulkov.evokammer.substrate.db.EvoKammerDatabase
import com.achulkov.evokammer.substrate.db.entity.EventLogEntryEntity
import com.achulkov.evokammer.substrate.db.entity.EventTypes
import io.github.aakira.napier.Napier
import java.util.UUID

/**
 * Computes the [RoleAllocation] for the next generation based on substrate signals.
 *
 * Reads signals from the substrate to detect bottlenecks and adjusts role ratios dynamically.
 *
 * Hard constraints:
 * - At least 1 Critic per generation
 * - At least 1 Outlier every 5 generations (every 3rd if stasis detected)
 * - Total budget across all agents <= [GroupProfile.spawnBudget] * maxFleetSize
 * - At least 1 Researcher and 1 Builder every generation (Phase 1+)
 *
 * @param db The group's Room database.
 * @param profile The group's profile (determines base budget and fleet size).
 * @param runId The run identifier.
 * @param groupId The group identifier.
 */
class EvolutionarySelector(
    private val db: EvoKammerDatabase,
    private val profile: GroupProfile,
    private val runId: String,
    private val groupId: String
) {
    /**
     * Computes the role allocation for [generation].
     *
     * @param generation The generation to allocate for.
     * @param globalBudgetCeiling The maximum total budget for this group this generation.
     * @param stasisDetected Whether the Watchdog/Inspector has flagged a stasis condition.
     * @return The computed [RoleAllocation].
     */
    suspend fun computeAllocation(
        generation: Int,
        globalBudgetCeiling: Double,
        stasisDetected: Boolean = false
    ): RoleAllocation {
        val signals = gatherSignals(generation)
        Napier.d { "[$groupId] Signals for gen $generation: $signals" }

        val entries = buildAllocationEntries(generation, signals, stasisDetected, globalBudgetCeiling)
        val rationale = buildRationale(signals, entries, stasisDetected)

        val allocation = RoleAllocation(
            runId = runId,
            groupId = groupId,
            generation = generation,
            entries = entries,
            totalBudgetCeiling = globalBudgetCeiling,
            rationale = rationale
        )

        // A-2: Write a structured SELECTOR_DECISION event so post-mortem analysis can query
        // allocation history without relying solely on Napier log strings.
        runCatching {
            val roleCountsJson = entries.joinToString(",") {
                """{"role":"${it.role.name}","count":${it.count},"budget":${it.budgetPerAgent}}"""
            }
            db.eventLogDao().insert(
                EventLogEntryEntity(
                    id = UUID.randomUUID().toString(),
                    timestampMs = System.currentTimeMillis(),
                    eventType = EventTypes.SELECTOR_DECISION,
                    groupId = groupId,
                    runId = runId,
                    generation = generation,
                    payloadJson = buildString {
                        val escapedRationale = rationale.replace("\\", "\\\\").replace("\"", "\\\"")
                        append("""{"generation":$generation,"stasisDetected":$stasisDetected,""")
                        append(""""budgetCeiling":$globalBudgetCeiling,"kbSize":${signals.kbSize},""")
                        append(""""stagingBacklog":${signals.stagingBacklog},"toolFailureRate":${signals.toolFailureRate},""")
                        append(""""unclaimedTasks":${signals.unclaimedTasks},"noRecentPromotions":${signals.noRecentPromotions},""")
                        append(""""roleCounts":[$roleCountsJson],"rationale":"$escapedRationale"}""")
                    }
                )
            )
        }.onFailure { e -> Napier.w(e) { "[$groupId] Failed to log SELECTOR_DECISION event for gen $generation" } }

        return allocation
    }

    private suspend fun gatherSignals(generation: Int): SelectionSignals {
        // KB density by domain
        val kbCount = try { db.knowledgeBaseDao().countActive(runId, groupId) } catch (e: Exception) { 0 }

        // Staging backlog
        val stagingBacklog = try { db.stagingDao().countPending(runId, groupId) } catch (e: Exception) { 0 }

        // Tool failure rate
        val toolFailureRate = try { db.toolRegistryDao().getAverageFailureRate(runId, groupId) ?: 0.0 } catch (e: Exception) { 0.0 }

        // Unclaimed tasks
        val unclaimedTasks = try { db.wipTaskDao().countUnclaimed(runId, groupId) } catch (e: Exception) { 0 }

        // Recent promotions (stasis proxy)
        val recentPromotions = try {
            db.stagingDao().countPendingByType(runId, groupId, "DOC") == 0 &&
                    db.stagingDao().countPendingByType(runId, groupId, "TOOL") == 0
        } catch (e: Exception) { false }

        return SelectionSignals(
            kbSize = kbCount,
            stagingBacklog = stagingBacklog,
            toolFailureRate = toolFailureRate,
            unclaimedTasks = unclaimedTasks,
            noRecentPromotions = recentPromotions,
            generation = generation
        )
    }

    private fun buildAllocationEntries(
        generation: Int,
        signals: SelectionSignals,
        stasisDetected: Boolean,
        budgetCeiling: Double
    ): List<RoleAllocationEntry> {
        val maxFleet = profile.maxFleetSize
        val budgetPerAgent = profile.spawnBudget
        val entries = mutableListOf<RoleAllocationEntry>()

        // Must-have: Critic
        entries.add(RoleAllocationEntry(Role.Critic, 1, budgetPerAgent))

        // Must-have: Researcher
        entries.add(RoleAllocationEntry(Role.Researcher, 1, budgetPerAgent))

        // Must-have: Builder (if tool failure rate is high, add more)
        val builderCount = if (signals.toolFailureRate > 0.3) 2 else 1
        entries.add(RoleAllocationEntry(Role.Builder, builderCount, budgetPerAgent))

        // Outlier cadence: every 5 gens (every 3 if stasis)
        val outlierCadence = if (stasisDetected) 3 else 5
        val shouldAddOutlier = generation % outlierCadence == 0
        if (shouldAddOutlier) {
            entries.add(RoleAllocationEntry(Role.Outlier, 1, budgetPerAgent, forceOutlier = true))
        }

        // Dynamic roles based on signals
        val remainingSlots = maxFleet - entries.sumOf { it.count }
        if (remainingSlots > 0) {
            // High staging backlog → extra Critic
            if (signals.stagingBacklog > 5 && remainingSlots >= 1) {
                entries.add(RoleAllocationEntry(Role.Critic, 1, budgetPerAgent))
            }

            // Many unclaimed tasks → extra Researcher or Builder
            if (signals.unclaimedTasks > 3 && remainingSlots >= 1) {
                entries.add(RoleAllocationEntry(Role.Researcher, 1, budgetPerAgent))
            }

            // KB growing → add Archivist (Phase 2+)
            if (signals.kbSize > 10 && generation >= 3 && remainingSlots >= 1) {
                entries.add(RoleAllocationEntry(Role.Archivist, 1, budgetPerAgent))
            }

            // Phase 2+ roles: Planner (once per few generations)
            if (generation >= 2 && generation % 3 == 0 && remainingSlots >= 1) {
                entries.add(RoleAllocationEntry(Role.Planner, 1, budgetPerAgent * 1.5))
            }
        }

        // Validate total budget doesn't exceed ceiling
        val totalBudget = entries.sumOf { it.count * it.budgetPerAgent }
        return if (totalBudget <= budgetCeiling) {
            entries
        } else {
            // Scale down by removing non-critical roles until budget fits
            pruneToFitBudget(entries, budgetCeiling)
        }
    }

    private fun pruneToFitBudget(
        entries: MutableList<RoleAllocationEntry>,
        budgetCeiling: Double
    ): List<RoleAllocationEntry> {
        val mutable = entries.toMutableList()
        while (mutable.sumOf { it.count * it.budgetPerAgent } > budgetCeiling && mutable.size > 2) {
            // Remove the last non-critical entry
            val removable = mutable.indexOfLast { it.role != Role.Critic && it.role != Role.Researcher }
            if (removable >= 0) mutable.removeAt(removable) else break
        }
        return mutable
    }

    private fun buildRationale(
        signals: SelectionSignals,
        entries: List<RoleAllocationEntry>,
        stasisDetected: Boolean
    ): String = buildString {
        append("Gen ${signals.generation}: ")
        append("KB=${ signals.kbSize} entries, ")
        append("backlog=${signals.stagingBacklog}, ")
        append("toolFailRate=${String.format("%.1f%%", signals.toolFailureRate * 100)}, ")
        append("unclaimedTasks=${signals.unclaimedTasks}")
        if (stasisDetected) append(", STASIS_DETECTED")
        append(" → allocating: ")
        append(entries.joinToString(", ") { "${it.count}x${it.role.name}" })
    }

    private data class SelectionSignals(
        val kbSize: Int,
        val stagingBacklog: Int,
        val toolFailureRate: Double,
        val unclaimedTasks: Int,
        val noRecentPromotions: Boolean,
        val generation: Int
    )
}
