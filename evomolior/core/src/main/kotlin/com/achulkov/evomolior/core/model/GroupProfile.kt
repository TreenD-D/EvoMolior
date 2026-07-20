package com.achulkov.evomolior.core.model

import kotlinx.serialization.Serializable

/**
 * Predefined asymmetric cost profiles for competing groups.
 *
 * Each profile defines multipliers applied on top of [ActionType] default costs.
 * A multiplier of 1.0 means the default cost applies. Higher multipliers increase costs;
 * lower multipliers (< 1.0) discount them.
 *
 * Profiles create cognitive diversity: a Library group will invest heavily in knowledge
 * retrieval; a Factory group will amortise that cost by building many tools.
 */
@Serializable
enum class GroupProfileType {
    /** Optimised for knowledge retrieval and synthesis. Near-free web reads, expensive tools. */
    Library,

    /** Optimised for tool building. Expensive web reads, cheap tool execution. */
    Factory,

    /** Optimised for collective intelligence. Cheap inter-agent messages, large agent fleet. */
    Commune,

    /** Optimised for depth over breadth. Near-free reads, costly staging writes; tiny fleet. */
    Monastery,

    /** Adaptive profile. Expensive reads, but can convert between resource types. */
    Mercenary
}

/**
 * Concrete cost multiplier configuration derived from a [GroupProfileType].
 *
 * @param profileType The profile this config is derived from.
 * @param webFetchMultiplier Multiplier applied to WEB_FETCH cost.
 * @param toolExecMultiplier Multiplier applied to TOOL_EXEC_SUCCESS / TOOL_EXEC_FAILURE costs.
 * @param interAgentMessageMultiplier Multiplier applied to INTER_AGENT_MESSAGE cost.
 * @param writeStagingMultiplier Multiplier applied to WRITE_STAGING cost.
 * @param llmCallMultiplier Multiplier applied to LLM_CALL per-token cost.
 * @param spawnBudget Initial budget given to each agent spawned in this group.
 * @param maxFleetSize Maximum concurrent agents this group can have alive.
 * @param kgEmphasis 0.0–1.0; higher = Evolutionary Selector will push more KG-focused roles.
 * @param kbEmphasis 0.0–1.0; higher = Evolutionary Selector will push more KB-focused roles.
 */
@Serializable
data class GroupProfile(
    val profileType: GroupProfileType,
    val webFetchMultiplier: Double,
    val toolExecMultiplier: Double,
    val interAgentMessageMultiplier: Double,
    val writeStagingMultiplier: Double,
    val llmCallMultiplier: Double,
    val spawnBudget: Double,
    val maxFleetSize: Int,
    val kgEmphasis: Double,
    val kbEmphasis: Double
) {
    companion object {
        /** Pre-built profile for [GroupProfileType.Library]. */
        val Library = GroupProfile(
            profileType = GroupProfileType.Library,
            webFetchMultiplier = 0.2,
            toolExecMultiplier = 3.0,
            interAgentMessageMultiplier = 1.0,
            writeStagingMultiplier = 1.0,
            llmCallMultiplier = 1.0,
            spawnBudget = 50.0,
            maxFleetSize = 6,
            kgEmphasis = 0.4,
            kbEmphasis = 0.8
        )

        /** Pre-built profile for [GroupProfileType.Factory]. */
        val Factory = GroupProfile(
            profileType = GroupProfileType.Factory,
            webFetchMultiplier = 3.0,
            toolExecMultiplier = 0.5,
            interAgentMessageMultiplier = 1.0,
            writeStagingMultiplier = 1.0,
            llmCallMultiplier = 1.0,
            spawnBudget = 60.0,
            maxFleetSize = 5,
            kgEmphasis = 0.3,
            kbEmphasis = 0.4
        )

        /** Pre-built profile for [GroupProfileType.Commune]. */
        val Commune = GroupProfile(
            profileType = GroupProfileType.Commune,
            webFetchMultiplier = 1.0,
            toolExecMultiplier = 1.5,
            interAgentMessageMultiplier = 0.1,
            writeStagingMultiplier = 1.0,
            llmCallMultiplier = 0.9,
            spawnBudget = 30.0,
            maxFleetSize = 10,
            kgEmphasis = 0.5,
            kbEmphasis = 0.5
        )

        /** Pre-built profile for [GroupProfileType.Monastery]. */
        val Monastery = GroupProfile(
            profileType = GroupProfileType.Monastery,
            webFetchMultiplier = 0.1,
            toolExecMultiplier = 2.0,
            interAgentMessageMultiplier = 0.5,
            writeStagingMultiplier = 3.0,
            llmCallMultiplier = 1.5,
            spawnBudget = 80.0,
            maxFleetSize = 3,
            kgEmphasis = 0.7,
            kbEmphasis = 0.7
        )

        /** Pre-built profile for [GroupProfileType.Mercenary]. */
        val Mercenary = GroupProfile(
            profileType = GroupProfileType.Mercenary,
            webFetchMultiplier = 2.0,
            toolExecMultiplier = 1.5,
            interAgentMessageMultiplier = 1.5,
            writeStagingMultiplier = 1.2,
            llmCallMultiplier = 1.2,
            spawnBudget = 55.0,
            maxFleetSize = 5,
            kgEmphasis = 0.5,
            kbEmphasis = 0.5
        )

        /** Returns the default [GroupProfile] for the given [GroupProfileType]. */
        fun default(type: GroupProfileType): GroupProfile = when (type) {
            GroupProfileType.Library -> Library
            GroupProfileType.Factory -> Factory
            GroupProfileType.Commune -> Commune
            GroupProfileType.Monastery -> Monastery
            GroupProfileType.Mercenary -> Mercenary
        }
    }
}
