package com.achulkov.evokammer.orchestration.epigenetic

import com.achulkov.evokammer.core.model.Role
import io.github.aakira.napier.Napier
import kotlinx.serialization.Serializable

/**
 * Manages Epigenetic Modifications — transient per-group environmental constraints that
 * override base constitutional rules for a limited duration.
 *
 * Per the EvoKammer spec, the Orchestrator can inject epigenetic modifications to:
 * - Temporarily ban an action type for a group (e.g. no WEB_FETCH for 1 round)
 * - Force-enable a role type for next N spawns
 * - Apply a budget multiplier for N rounds
 *
 * Modifications are applied on top of the constitutional layer and expire automatically.
 */
class EpigeneticManager {

    private val modifications = mutableMapOf<String, MutableList<EpigeneticModification>>()

    /**
     * Applies a new [modification] to [groupId].
     */
    fun apply(groupId: String, modification: EpigeneticModification) {
        modifications.getOrPut(groupId) { mutableListOf() }.add(modification)
        Napier.i("EpigeneticManager: applied ${modification::class.simpleName} to group $groupId (duration=${modification.roundsRemaining})")
    }

    /**
     * Returns all active [EpigeneticModification]s for [groupId].
     */
    fun activeFor(groupId: String): List<EpigeneticModification> =
        modifications[groupId]?.filter { it.roundsRemaining > 0 } ?: emptyList()

    /**
     * Decrements the duration of all modifications by 1 round.
     * Removes expired modifications.
     */
    fun onRoundEnd() {
        for ((groupId, mods) in modifications) {
            mods.replaceAll { it.decrementRound() }
            val removed = mods.removeAll { it.roundsRemaining <= 0 }
            if (removed) Napier.d("EpigeneticManager: expired modifications removed for group $groupId")
        }
    }

    /**
     * Checks whether a [role] is force-enabled for the next spawn in [groupId].
     */
    fun isRoleForced(groupId: String, role: Role): Boolean =
        activeFor(groupId).any { it is EpigeneticModification.ForceRole && it.role == role }

    /**
     * Returns the effective budget multiplier for [groupId] from epigenetic modifications.
     * Multipliers stack multiplicatively.
     */
    fun budgetMultiplierFor(groupId: String): Double =
        activeFor(groupId)
            .filterIsInstance<EpigeneticModification.BudgetMultiplier>()
            .fold(1.0) { acc, mod -> acc * mod.multiplier }

    /**
     * Returns whether [actionType] is currently banned for [groupId].
     */
    fun isActionBanned(groupId: String, actionType: String): Boolean =
        activeFor(groupId).any { it is EpigeneticModification.BanAction && it.actionType == actionType }
}

/** A transient modification applied epigenetically to a group for N rounds. */
@Serializable
sealed class EpigeneticModification {
    abstract val roundsRemaining: Int
    abstract fun decrementRound(): EpigeneticModification

    /** Temporarily prohibits [actionType] for a group. */
    @Serializable
    data class BanAction(
        val actionType: String,
        override val roundsRemaining: Int
    ) : EpigeneticModification() {
        override fun decrementRound() = copy(roundsRemaining = roundsRemaining - 1)
    }

    /** Forces the next [count] agent spawns in the group to use [role]. */
    @Serializable
    data class ForceRole(
        val role: Role,
        val count: Int,
        override val roundsRemaining: Int
    ) : EpigeneticModification() {
        override fun decrementRound() = copy(roundsRemaining = roundsRemaining - 1)
    }

    /** Multiplies the group's action budget by [multiplier] for [roundsRemaining] rounds. */
    @Serializable
    data class BudgetMultiplier(
        val multiplier: Double,
        override val roundsRemaining: Int
    ) : EpigeneticModification() {
        override fun decrementRound() = copy(roundsRemaining = roundsRemaining - 1)
    }
}
