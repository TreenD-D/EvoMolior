package com.achulkov.evokammer.competition.resourcemap

/**
 * Computes budget yield bonuses from currently held resource nodes.
 *
 * The resource map tracks ownership; this helper folds the owned nodes into a
 * single multiplier that can be applied to a group's future spawn budget.
 *
 * @param resourceMap Shared mutable resource map for the run.
 */
class NodeYieldApplier(
    private val resourceMap: ResourceMap
) {

    /**
     * Returns the cumulative budget multiplier for [groupId] based on all held nodes.
     *
     * The multiplier starts at 1.0 and multiplies each owned node's [ResourceNode.yieldMultiplier].
     * Unclaimed groups receive a neutral multiplier of 1.0.
     *
     * @param groupId The group to inspect.
     * @return The multiplicative budget bonus.
     */
    fun budgetMultiplierFor(groupId: String): Double =
        resourceMap.claimedBy(groupId).fold(1.0) { acc, node ->
            acc * node.yieldMultiplier
        }
}
