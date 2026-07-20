package com.achulkov.evomolior.orchestration.metaplanner

import com.achulkov.evomolior.core.model.Role
import io.github.aakira.napier.Napier

/**
 * Manages MetaPlanner agent spawning.
 *
 * Per the EvoMolior spec, a MetaPlanner is spawned by the Watchdog when:
 * - A group's WiP task list has had no completions for > N generations (stasis)
 * - The Inspector raises a HIGH/CRITICAL flag related to planning failures
 *
 * MetaPlanner has:
 * - Elevated read access to the full group KB and all WiP tasks
 * - Authority to clear and rewrite the Plan Registry
 * - A TTL (lives for 1 generation then dies)
 * - No access to staging or external web
 *
 * This runner decides WHEN to spawn a MetaPlanner; the actual Koog agent creation
 * is delegated to the caller via [spawner].
 *
 * @param stasisGenerationThreshold How many generation-cycles without WiP completion triggers a MetaPlanner spawn.
 */
class MetaPlannerRunner(
    private val stasisGenerationThreshold: Int = 10
) {

    // groupId -> generations-since-last-wip-completion
    private val wipCompletionCounters = mutableMapOf<String, Int>()

    /**
     * Called each generation when a WiP task is completed for [groupId].
     * Resets the stasis counter.
     */
    fun onWipCompleted(groupId: String) {
        wipCompletionCounters[groupId] = 0
        Napier.d("MetaPlannerRunner: WiP completion for $groupId — stasis counter reset")
    }

    /**
     * Called each generation for [groupId]. Increments stasis counter.
     *
     * If the threshold is exceeded, invokes [spawner] to spawn a MetaPlanner agent.
     *
     * @param groupId The group to check.
     * @param spawner Suspend lambda that spawns an agent with the given [Role].
     */
    suspend fun onGenerationTick(
        groupId: String,
        spawner: suspend (groupId: String, role: Role) -> Unit
    ) {
        val current = wipCompletionCounters.getOrDefault(groupId, 0) + 1
        wipCompletionCounters[groupId] = current

        if (current >= stasisGenerationThreshold) {
            Napier.w("MetaPlannerRunner: $groupId has had no WiP completions for $current generations — spawning MetaPlanner")
            wipCompletionCounters[groupId] = 0  // reset after spawning
            spawner(groupId, Role.MetaPlanner)
        }
    }
}
