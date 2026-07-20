package com.achulkov.evomolior.competition.coordinator
/**
 * Immutable ring-topology descriptor for multi-group runs.
 *
 * The order of [groupIds] defines adjacency:
 * `i -> (i - 1)` and `i -> (i + 1)` modulo N.
 *
 * @param groupIds Ordered, unique group identifiers.
 */
data class RingTopology(
    val groupIds: List<String>
) {
    init {
        require(groupIds.size >= 2) { "Ring topology requires at least 2 groups." }
        require(groupIds.distinct().size == groupIds.size) { "Ring topology group IDs must be unique." }
    }
    /**
     * Returns ring-adjacent neighbours for a group.
     *
     * @param groupId Group to query.
     * @return One neighbour for a two-group ring, otherwise two neighbours.
     */
    fun neighboursOf(groupId: String): List<String> {
        val idx = groupIds.indexOf(groupId)
        require(idx >= 0) { "Unknown groupId: $groupId" }
        val left = groupIds[(idx - 1 + groupIds.size) % groupIds.size]
        val right = groupIds[(idx + 1) % groupIds.size]
        return if (left == right) listOf(left) else listOf(left, right)
    }
}