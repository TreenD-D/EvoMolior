package com.achulkov.evomolior.core.contract

import kotlinx.serialization.Serializable

/**
 * Topology kind for inter-group routing and coordination.
 */
@Serializable
enum class GroupTopologyKind {
    RING,
    STAR,
    SMALL_WORLD
}
