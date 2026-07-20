package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.core.model.GroupProfile
import com.achulkov.evomolior.core.model.GroupProfileType
import com.achulkov.evomolior.orchestration.nested.NestedGroupConfig

internal const val NESTED_GROUP_OPEN_TASK_LIMIT = 3
internal const val NESTED_GROUP_MIN_TASK_COUNT = 2
internal const val NESTED_GROUP_MAX_GENERATIONS = 2
internal const val NESTED_GROUP_MIN_BUDGET = 10.0

internal data class NestedHierarchySpawnRequest(
    val runId: String,
    val outerGroupId: String,
    val round: Int,
    val runObjective: String,
    val profileType: GroupProfileType,
    val profile: GroupProfile,
    val remainingBudget: Double,
    val openTaskDescriptions: List<String>,
    val openRouterApiKey: String,
    val agentJarPath: String,
    val modelAssignment: Map<String, String>,
    val modelPools: Map<String, List<String>>
)

internal data class NestedHierarchySpawnPlan(
    val config: NestedGroupConfig,
    val progressMessage: String
)

/**
 * Derives a narrow nested-hierarchy spawn plan from a stasis signal plus the group's open WiP
 * backlog. This keeps the production trigger deterministic and directly testable.
 */
internal class NestedHierarchySpawnPlanner {

    fun plan(request: NestedHierarchySpawnRequest): NestedHierarchySpawnPlan? {
        if (request.openTaskDescriptions.size < NESTED_GROUP_MIN_TASK_COUNT) {
            return null
        }

        val budgetAllocation = minOf(
            maxOf(request.profile.spawnBudget, request.remainingBudget * 0.2),
            request.remainingBudget * 0.35
        )
        if (budgetAllocation < NESTED_GROUP_MIN_BUDGET) {
            return null
        }

        val nestedGroupId = "nested-${request.outerGroupId}-r${request.round + 1}"
        return NestedHierarchySpawnPlan(
            config = NestedGroupConfig(
                nestedGroupId = nestedGroupId,
                outerGroupId = request.outerGroupId,
                outerRunId = request.runId,
                objective = buildObjective(
                    runObjective = request.runObjective,
                    targetGroupId = request.outerGroupId,
                    openTasks = request.openTaskDescriptions.take(NESTED_GROUP_OPEN_TASK_LIMIT)
                ),
                budgetAllocation = budgetAllocation,
                maxGenerations = NESTED_GROUP_MAX_GENERATIONS,
                profileType = request.profileType,
                openRouterApiKey = request.openRouterApiKey,
                agentJarPath = request.agentJarPath,
                modelAssignment = request.modelAssignment,
                modelPools = request.modelPools
            ),
            progressMessage = "[${request.outerGroupId}] Spawning nested group $nestedGroupId after stasis with " +
                "${request.openTaskDescriptions.size} unresolved tasks"
        )
    }

    fun buildObjective(
        runObjective: String,
        targetGroupId: String,
        openTasks: List<String>
    ): String = buildString {
        appendLine("You are a nested inner EvoMolior instance supporting outer group $targetGroupId.")
        appendLine("Advance the parent objective by resolving this stalled sub-problem cluster.")
        appendLine()
        appendLine("Parent objective:")
        appendLine(runObjective)
        appendLine()
        appendLine("Priority unresolved tasks:")
        openTasks.forEachIndexed { index, task ->
            appendLine("${index + 1}. $task")
        }
        appendLine()
        appendLine("Produce reusable KB documents, KG facts, and tools that the outer group can absorb.")
    }
}
