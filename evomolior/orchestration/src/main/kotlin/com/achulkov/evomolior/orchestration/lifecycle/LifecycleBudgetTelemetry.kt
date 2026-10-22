package com.achulkov.evomolior.orchestration.lifecycle

/**
 * Immutable accounting observation of actual lifecycle allocations in one generation.
 *
 * Returned credits are unused balances of settled agents, not permission to allocate again.
 * Policy refunds are deliberately separate from ledger consumption: refunding a failed worker
 * does not erase the actions it performed. This type changes neither ledger nor grant policy.
 *
 * @param generation Generation whose registered allocations are observed.
 * @param allocatedCredits Total allocated to live and settled agents.
 * @param liveAllocatedCredits Original allocations of agents still owned by the lifecycle.
 * @param liveCredits Current balances of those live agents.
 * @param spentCredits Ledger consumption across live and settled agents.
 * @param returnedCredits Unused balances released by fully settled agents.
 * @param liveAgentCount Agents still owned, including executions awaiting strict cleanup.
 * @param revision Monotonically increasing observation revision within the lifecycle manager.
 */
data class LifecycleBudgetTelemetry(
    val generation: Int,
    val allocatedCredits: Double,
    val liveAllocatedCredits: Double,
    val liveCredits: Double,
    val spentCredits: Double,
    val returnedCredits: Double,
    val liveAgentCount: Int,
    val revision: Long = 0L
)
