package com.achulkov.evomolior.api.events

import kotlinx.serialization.Serializable

/**
 * Credit accounting for one generation; none of these fields represent provider USD.
 *
 * @param generation Zero-based allocation generation.
 * @param allocatedCredits Total actual agent allocations.
 * @param liveAllocatedCredits Original grants for still-owned agents.
 * @param liveCredits Remaining credits of still-owned agents.
 * @param spentCredits Ledger consumption, including settled agents.
 * @param returnedCredits Settled unused balances, not permission to respawn.
 * @param liveAgentCount Agents still owned, including unsettled cleanup.
 * @param revision Monotonic lifecycle observation revision.
 */
@Serializable
data class GroupBudgetAccounting(
    val generation: Int,
    val allocatedCredits: Double,
    val liveAllocatedCredits: Double,
    val liveCredits: Double,
    val spentCredits: Double,
    val returnedCredits: Double,
    val liveAgentCount: Int,
    val revision: Long,
)
