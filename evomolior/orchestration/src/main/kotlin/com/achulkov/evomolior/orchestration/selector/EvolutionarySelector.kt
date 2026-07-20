package com.achulkov.evomolior.orchestration.selector

import com.achulkov.evomolior.core.contract.RoleAllocation
import com.achulkov.evomolior.core.contract.RoleAllocationEntry
import com.achulkov.evomolior.core.contract.DeliverableSkeletonTreeSummary
import com.achulkov.evomolior.core.model.GroupProfile
import com.achulkov.evomolior.core.model.Role
import com.achulkov.evomolior.substrate.db.EvoMoliorDatabase
import com.achulkov.evomolior.substrate.db.entity.EventLogEntryEntity
import com.achulkov.evomolior.substrate.db.entity.EventTypes
import io.github.aakira.napier.Napier
import java.util.UUID

/**
 * Computes the [RoleAllocation] for the next generation based on substrate signals.
 *
 * Reads signals from the substrate to detect bottlenecks and adjusts role ratios dynamically.
 *
 * Hard constraints:
 * - Total budget across all agents stays within the caller-provided ceiling when the ceiling is
 *   positive
 * - At least one core worker is spawned while budget allows
 * - Outliers spawn on cadence and are preserved preferentially over ordinary Builders when budget
 *   pressure would otherwise prune novelty away
 *
 * @param db The group's Room database.
 * @param profile The group's profile (determines base budget and fleet size).
 * @param runId The run identifier.
 * @param groupId The group identifier.
 */
class EvolutionarySelector(
    private val db: EvoMoliorDatabase,
    private val profile: GroupProfile,
    private val runId: String,
    private val groupId: String,
    private val deliverableTreeSummary: DeliverableSkeletonTreeSummary? = null
) {
    /**
     * Computes the role allocation for [generation].
     *
     * @param generation The generation to allocate for.
     * @param globalBudgetCeiling The maximum total budget for this group this generation.
     * @param stasisDetected Whether the Watchdog/Inspector has flagged a stasis condition.
     * @param formalDispatchDue Whether this multi-group generation needs a Connector-authored
     *   formal dispatch contribution.
     * @return The computed [RoleAllocation].
     */
    suspend fun computeAllocation(
        generation: Int,
        globalBudgetCeiling: Double,
        stasisDetected: Boolean = false,
        formalDispatchDue: Boolean = false
    ): RoleAllocation {
        val signals = gatherSignals(generation)
        Napier.d { "[$groupId] Signals for gen $generation: $signals" }

        val entries = buildAllocationEntries(
            generation = generation,
            signals = signals,
            stasisDetected = stasisDetected,
            budgetCeiling = globalBudgetCeiling,
            formalDispatchDue = formalDispatchDue
        )
        val boundedEntries = enforceBoundedHeterogeneity(entries, globalBudgetCeiling)
        val turnTakingBalancedEntries = enforceTurnTakingBalance(boundedEntries, globalBudgetCeiling)
        val rationale = buildRationale(signals, turnTakingBalancedEntries, stasisDetected)
        val diversityMetrics = roleDiversityMetrics(turnTakingBalancedEntries)

        val allocation = RoleAllocation(
            runId = runId,
            groupId = groupId,
            generation = generation,
            entries = turnTakingBalancedEntries,
            totalBudgetCeiling = globalBudgetCeiling,
            rationale = rationale
        )

        // A-2: Write a structured SELECTOR_DECISION event so post-mortem analysis can query
        // allocation history without relying solely on Napier log strings.
        runCatching {
            val roleCountsJson = turnTakingBalancedEntries.joinToString(",") {
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
                        append(""""deliverableOpenGaps":${signals.deliverableOpenGaps},"deliverableContested":${signals.deliverableContested},""")
                        append(""""roleDiversityEntropy":${diversityMetrics?.entropy ?: 0.0},"roleDiversityNormalized":${diversityMetrics?.normalizedEntropy ?: 0.0},""")
                        append(""""distinctRoleCount":${diversityMetrics?.distinctRoleCount ?: 0},"outlierShare":${diversityMetrics?.outlierShare ?: 0.0},""")
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

        // Plan freshness: stale or absent PLAN_UPDATEs should bias toward Planner allocation.
        val planIsStale = try {
            val latestPlan = db.planRegistryDao().getLatest(runId, groupId, limit = 1).firstOrNull()
            latestPlan == null || (generation - latestPlan.generation) >= 2
        } catch (e: Exception) {
            true
        }

        // Allocation for generation N evaluates substantive progress completed in generation N-1.
        val noRecentPromotions = try {
            db.stagingDao().countSubstantivePromotedByGeneration(
                runId = runId,
                groupId = groupId,
                generation = generation - 1
            ) == 0
        } catch (e: Exception) { false }

        return SelectionSignals(
            kbSize = kbCount,
            stagingBacklog = stagingBacklog,
            toolFailureRate = toolFailureRate,
            unclaimedTasks = unclaimedTasks,
            planIsStale = planIsStale,
            noRecentPromotions = noRecentPromotions,
            deliverableOpenGaps = deliverableTreeSummary?.openGapCount ?: 0,
            deliverableContested = deliverableTreeSummary?.contestedNodeCount ?: 0,
            deliverableDeadEnds = deliverableTreeSummary?.deadEndCount ?: 0,
            deliverableAverageTrust = deliverableTreeSummary?.averageTrustScore ?: 0.0,
            deliverableMaxComplexity = deliverableTreeSummary?.maxComplexityLevel ?: 0,
            generation = generation
        )
    }

    private fun buildAllocationEntries(
        generation: Int,
        signals: SelectionSignals,
        stasisDetected: Boolean,
        budgetCeiling: Double,
        formalDispatchDue: Boolean
    ): List<RoleAllocationEntry> {
        if (budgetCeiling <= 0.0) return emptyList()
        if (budgetCeiling < profile.spawnBudget) {
            return listOf(
                RoleAllocationEntry(
                    role = Role.Researcher,
                    count = 1,
                    budgetPerAgent = budgetCeiling
                )
            )
        }
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
        if (maxFleet - entries.sumOf { it.count } > 0) {
            // Formal dispatches require an explicitly typed Connector output. Without a
            // scheduled Connector, the relay exists but no text-only production agent can use it.
            if (formalDispatchDue && maxFleet - entries.sumOf { it.count } > 0) {
                entries.add(RoleAllocationEntry(Role.Connector, 1, budgetPerAgent))
            }

            // High staging backlog → extra Critic
            if (
                (signals.stagingBacklog > 5 || signals.deliverableContested > 0) &&
                maxFleet - entries.sumOf { it.count } > 0
            ) {
                entries.add(RoleAllocationEntry(Role.Critic, 1, budgetPerAgent))
            }

            // Many unclaimed tasks → extra Researcher or Builder
            if (
                (signals.unclaimedTasks > 3 || signals.deliverableOpenGaps > 0) &&
                maxFleet - entries.sumOf { it.count } > 0
            ) {
                entries.add(RoleAllocationEntry(Role.Researcher, 1, budgetPerAgent))
            }

            // KB growing → add Archivist (Phase 2+)
            if (
                (signals.kbSize > 10 || signals.deliverableDeadEnds > 0) &&
                generation >= 3 &&
                maxFleet - entries.sumOf { it.count } > 0
            ) {
                entries.add(RoleAllocationEntry(Role.Archivist, 1, budgetPerAgent))
            }

            // Phase 2+ roles: Planner (once per few generations)
            if (
                maxFleet - entries.sumOf { it.count } > 0 &&
                (signals.planIsStale ||
                    (deliverableTreeSummary != null && signals.deliverableAverageTrust < 0.4) ||
                    (generation >= 2 && generation % 3 == 0))
            ) {
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
        if (budgetCeiling <= 0.0) return emptyList()
        val mutable = entries.toMutableList()
        while (mutable.sumOf { it.count * it.budgetPerAgent } > budgetCeiling && mutable.isNotEmpty()) {
            val removable = mutable.indexOfFirst { it.role == Role.Builder && mutable.any { entry -> entry.role == Role.Outlier } }
                .takeIf { it >= 0 }
                ?: mutable.indexOfLast { it.role != Role.Critic && it.role != Role.Researcher }
                    .takeIf { it >= 0 }
                ?: mutable.lastIndex
            if (removable >= 0) mutable.removeAt(removable) else break
        }
        return mutable
    }

    internal fun enforceBoundedHeterogeneity(
        entries: List<RoleAllocationEntry>,
        budgetCeiling: Double,
        maxOutliers: Int = 1,
        minDistinctRoles: Int = 3
    ): List<RoleAllocationEntry> {
        if (budgetCeiling <= 0.0) return emptyList()
        val normalized = entries.toMutableList()

        val outlierIndexes = normalized.withIndex()
            .filter { it.value.role == Role.Outlier }
            .map { it.index }
        if (outlierIndexes.size > maxOutliers) {
            outlierIndexes.drop(maxOutliers).reversed().forEach { normalized.removeAt(it) }
        }

        val distinctRoles = normalized.map { it.role }.toSet()
        if (distinctRoles.size < minDistinctRoles && normalized.sumOf { it.count * it.budgetPerAgent } + profile.spawnBudget <= budgetCeiling) {
            if (Role.Planner !in distinctRoles) {
                normalized.add(RoleAllocationEntry(Role.Planner, 1, profile.spawnBudget * 1.5))
            } else if (Role.Archivist !in distinctRoles) {
                normalized.add(RoleAllocationEntry(Role.Archivist, 1, profile.spawnBudget))
            }
        }

        return normalized
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
        if (signals.deliverableOpenGaps > 0 || signals.deliverableContested > 0 || signals.deliverableDeadEnds > 0) {
            append(", deliverableGaps=${signals.deliverableOpenGaps}, deliverableContested=${signals.deliverableContested}, deliverableDeadEnds=${signals.deliverableDeadEnds}")
        }
        if (stasisDetected) append(", STASIS_DETECTED")
        append(" → allocating: ")
        append(entries.joinToString(", ") { "${it.count}x${it.role.name}" })
    }

    internal data class RoleDiversityMetrics(
        val entropy: Double,
        val normalizedEntropy: Double,
        val distinctRoleCount: Int,
        val outlierShare: Double
    )

    internal fun roleDiversityMetrics(entries: List<RoleAllocationEntry>): RoleDiversityMetrics? {
        if (entries.isEmpty()) return null
        val countsByRole = entries
            .groupBy { it.role }
            .mapValues { (_, roleEntries) -> roleEntries.sumOf { it.count } }
            .filterValues { it > 0 }
        val total = countsByRole.values.sum().toDouble()
        if (total <= 0.0) return null

        val shares = countsByRole.values.map { it / total }
        val entropy = -shares.sumOf { share -> share * (kotlin.math.ln(share) / kotlin.math.ln(2.0)) }
        val maxEntropy = kotlin.math.ln(shares.size.toDouble()) / kotlin.math.ln(2.0)
        val normalizedEntropy = if (maxEntropy > 0.0) entropy / maxEntropy else 1.0
        val outlierShare = countsByRole[Role.Outlier]?.div(total) ?: 0.0

        return RoleDiversityMetrics(
            entropy = entropy,
            normalizedEntropy = normalizedEntropy,
            distinctRoleCount = countsByRole.size,
            outlierShare = outlierShare
        )
    }

    internal fun enforceTurnTakingBalance(
        entries: List<RoleAllocationEntry>,
        budgetCeiling: Double,
        maxDominantShare: Double = 0.55
    ): List<RoleAllocationEntry> {
        if (budgetCeiling <= 0.0) return emptyList()
        val mutable = entries.toMutableList()
        if (mutable.size < 2) return mutable

        while (true) {
            val budgetsByRole = mutable
                .groupBy { it.role }
                .mapValues { (_, roleEntries) -> roleEntries.sumOf { entry -> entry.count * entry.budgetPerAgent } }
                .filterValues { it > 0.0 }
            val totalBudget = budgetsByRole.values.sum()
            if (totalBudget <= 0.0) return mutable

            val dominantEntry = budgetsByRole.maxBy { it.value }
            val dominantShare = dominantEntry.value / totalBudget
            if (dominantShare <= maxDominantShare) return mutable

            val dominantIndex = mutable.indexOfLast { it.role == dominantEntry.key && it.count > 0 }
            if (dominantIndex < 0) return mutable

            val replacementRole = listOf(Role.Planner, Role.Archivist, Role.Connector, Role.Educator)
                .firstOrNull { candidate -> candidate != dominantEntry.key }
                ?: return mutable

            val source = mutable[dominantIndex]
            val replacement = RoleAllocationEntry(
                role = replacementRole,
                count = 1,
                budgetPerAgent = source.budgetPerAgent
            )
            if (source.count <= 1) {
                mutable.removeAt(dominantIndex)
            } else {
                mutable[dominantIndex] = source.copy(count = source.count - 1)
            }
            mutable.add(replacement)

            if (mutable.sumOf { it.count * it.budgetPerAgent } > budgetCeiling) {
                return mutable
            }
        }
    }

    private data class SelectionSignals(
        val kbSize: Int,
        val stagingBacklog: Int,
        val toolFailureRate: Double,
        val unclaimedTasks: Int,
        val planIsStale: Boolean,
        val noRecentPromotions: Boolean,
        val deliverableOpenGaps: Int,
        val deliverableContested: Int,
        val deliverableDeadEnds: Int,
        val deliverableAverageTrust: Double,
        val deliverableMaxComplexity: Int,
        val generation: Int
    )
}
