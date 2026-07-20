package com.achulkov.evomolior.core.contract

import com.achulkov.evomolior.core.model.Role
import kotlinx.serialization.Serializable

/**
 * Role allocation output from the Evolutionary Selector.
 *
 * Consumed by the Lifecycle Manager to decide how many of each role to spawn in the
 * next generation. The total budget across all agents must not exceed [totalBudgetCeiling].
 *
 * @param runId Run this allocation belongs to.
 * @param groupId Group this allocation is for.
 * @param generation The generation this allocation is for.
 * @param entries List of role entries to spawn.
 * @param totalBudgetCeiling Maximum total budget allocatable across all agents in this generation.
 * @param rationale Human-readable summary of why this allocation was chosen (for audit logs).
 */
@Serializable
data class RoleAllocation(
    val runId: String,
    val groupId: String,
    val generation: Int,
    val entries: List<RoleAllocationEntry>,
    val totalBudgetCeiling: Double,
    val rationale: String
) {
    /** Convenience: total number of agents to spawn. */
    val totalAgentCount: Int get() = entries.sumOf { it.count }

    /** Convenience: total budget to allocate across all agents. */
    val totalBudget: Double get() = entries.sumOf { it.count * it.budgetPerAgent }
}

/**
 * A single role slot in the role allocation.
 *
 * @param role The role to spawn.
 * @param count Number of agents to spawn with this role.
 * @param budgetPerAgent Budget assigned to each agent of this role.
 * @param forceOutlier If true, this entry produces Outlier-class agents (no WiP claim, no Legacy Protocol).
 */
@Serializable
data class RoleAllocationEntry(
    val role: Role,
    val count: Int,
    val budgetPerAgent: Double,
    val forceOutlier: Boolean = false
)
