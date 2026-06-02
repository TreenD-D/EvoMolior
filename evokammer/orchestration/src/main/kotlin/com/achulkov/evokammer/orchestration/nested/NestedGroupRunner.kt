package com.achulkov.evokammer.orchestration.nested

import com.achulkov.evokammer.core.model.GroupProfile
import com.achulkov.evokammer.orchestration.lifecycle.LifecycleManager
import com.achulkov.evokammer.orchestration.selector.EvolutionarySelector
import com.achulkov.evokammer.sandbox.shuru.ShuruVmManager
import com.achulkov.evokammer.substrate.SubstrateDatabaseFactory
import com.achulkov.evokammer.substrate.db.entity.EventLogEntryEntity
import com.achulkov.evokammer.substrate.db.entity.EventTypes
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import java.util.UUID

/**
 * Runs a nested EvoKammer group instance inside an outer group's budget envelope.
 * Instantiated by the outer group's [com.achulkov.evokammer.orchestration.lifecycle.LifecycleManager].
 *
 * Lifecycle:
 * 1. Creates an isolated Room DB for the nested group (sibling directory).
 * 2. Runs a full multi-generation loop via [LifecycleManager] + [EvolutionarySelector].
 * 3. On completion (or budget exhaustion), extracts promoted artifacts.
 * 4. Returns a [NestedGroupResult] for rollup into the outer substrate.
 *
 * Budget enforcement:
 * - The outer group carves [NestedGroupConfig.budgetAllocation] from its pool.
 * - The inner instance gets [NestedGroupConfig.effectiveBudget] (minus safety margin).
 * - If the outer group's budget runs out, the nested run is terminated immediately.
 *
 * @param config Configuration for this nested group run.
 * @param shuruVmManager Shared VM manager.
 * @param substrateDatabaseFactory Factory for per-group DB access.
 * @param lifecycleManagerFactory Factory for creating LifecycleManager instances.
 */
@Suppress("unused") // instantiated dynamically by LifecycleManager when a group spawns a nested group
class NestedGroupRunner(
    private val config: NestedGroupConfig,
    @Suppress("unused") private val shuruVmManager: ShuruVmManager, // reserved for VM-level teardown of nested agents
    private val substrateDatabaseFactory: SubstrateDatabaseFactory,
    private val lifecycleManagerFactory: (
        runId: String,
        groupId: String,
        openRouterApiKey: String,
        agentJarPath: String
    ) -> LifecycleManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Runs the nested group to completion and returns the rollup result.
     *
     * This is a long-running suspend function — the caller should run it in a
     * background coroutine and wait for the result.
     */
    suspend fun run(): NestedGroupResult {
        Napier.i("NestedGroupRunner: starting ${config.nestedGroupId} under ${config.outerGroupId}")

        // Use a synthetic run ID scoped to this nested instance
        val nestedRunId = "${config.outerRunId}-nested-${config.nestedGroupId}"

        val db = substrateDatabaseFactory.get(nestedRunId, config.nestedGroupId)
        val profile = GroupProfile.default(config.profileType)

        val lifecycleManager = lifecycleManagerFactory(
            nestedRunId,
            config.nestedGroupId,
            config.openRouterApiKey,
            config.agentJarPath
        )

        var generationsCompleted = 0
        var budgetConsumed = 0.0
        var completedNormally = false
        var remainingBudget = config.effectiveBudget

        try {
            for (generation in 0 until config.maxGenerations) {
                if (remainingBudget <= 0) {
                    Napier.w("NestedGroupRunner: ${config.nestedGroupId} budget exhausted at generation $generation")
                    break
                }

                Napier.i("NestedGroupRunner: ${config.nestedGroupId} gen $generation (budget=$remainingBudget)")

                // Compute role allocation
                val selector = EvolutionarySelector(
                    db = db,
                    profile = profile,
                    runId = nestedRunId,
                    groupId = config.nestedGroupId
                )
                val budgetCeiling = remainingBudget / (config.maxGenerations - generation).coerceAtLeast(1)
                val allocation = selector.computeAllocation(generation, budgetCeiling)

                // Spawn generation — use model assignments from config (from StartingField.modelAssignment)
                lifecycleManager.spawnGeneration(
                    allocation = allocation,
                    stageDisclosure = config.objective,
                    modelAssignments = config.modelAssignment,
                    modelPools = config.modelPools,
                    rolePrompts = emptyMap(),
                    scope = scope
                )

                // Wait for a generation round (simple time-based for now)
                val roundDurationMs = estimateRoundDurationMs(budgetCeiling)
                delay(roundDurationMs)

                generationsCompleted++

                // Emit boundary event
                db.eventLogDao().insert(EventLogEntryEntity(
                    id = UUID.randomUUID().toString(),
                    timestampMs = System.currentTimeMillis(),
                    eventType = EventTypes.GENERATION_BOUNDARY,
                    agentId = null,
                    groupId = config.nestedGroupId,
                    runId = nestedRunId,
                    generation = generation,
                    payloadJson = """{"nestedGroupId":"${config.nestedGroupId}","generation":$generation}"""
                ))

                remainingBudget = (config.effectiveBudget - budgetConsumed).coerceAtLeast(0.0)
            }

            budgetConsumed = config.effectiveBudget - remainingBudget
            completedNormally = true
            Napier.i("NestedGroupRunner: ${config.nestedGroupId} completed normally. gens=$generationsCompleted consumed=$budgetConsumed")

        } catch (e: Exception) {
            Napier.e("NestedGroupRunner: ${config.nestedGroupId} error: ${e.message}", e)
            budgetConsumed = config.effectiveBudget
        } finally {
            lifecycleManager.terminateAll()
        }

        // Extract promoted artifacts for rollup
        val kbDocumentIds = db.knowledgeBaseDao()
            .search(nestedRunId, config.nestedGroupId, "", 500)
            .filter { it.status == "active" && !it.isDeadEnd }
            .map { it.id }
        val kgFactIds = db.knowledgeBaseDao()
            .search(nestedRunId, config.nestedGroupId, "", 500)
            .filter { it.status == "active" }
            .map { it.id }
        val promotedToolIds = db.toolRegistryDao()
            .getActive(nestedRunId, config.nestedGroupId, null)
            .map { it.id }

        return NestedGroupResult(
            nestedGroupId = config.nestedGroupId,
            outerGroupId = config.outerGroupId,
            generationsCompleted = generationsCompleted,
            kbDocumentIds = kbDocumentIds,
            kgFactIds = kgFactIds,
            promotedToolIds = promotedToolIds,
            budgetConsumed = budgetConsumed,
            completedNormally = completedNormally,
            summaryJson = buildSummaryJson(generationsCompleted, kbDocumentIds, kgFactIds)
        )
    }

    private fun estimateRoundDurationMs(budgetCeiling: Double): Long {
        // In production, this would be dynamic based on agent activity.
        // For now, use a simple proportional estimate (1 min per 10 budget units, min 30s).
        return ((budgetCeiling / 10.0) * 60_000L).toLong().coerceAtLeast(30_000L).coerceAtMost(300_000L)
    }

    private fun buildSummaryJson(gens: Int, kbIds: List<String>, kgIds: List<String>): String =
        """{"generationsCompleted":$gens,"kbDocumentCount":${kbIds.size},"kgFactCount":${kgIds.size}}"""
}
