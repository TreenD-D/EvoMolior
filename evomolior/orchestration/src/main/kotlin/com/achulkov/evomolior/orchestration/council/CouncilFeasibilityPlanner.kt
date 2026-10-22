package com.achulkov.evomolior.orchestration.council

import com.achulkov.evomolior.core.council.CouncilFeasibilityReport
import com.achulkov.evomolior.core.council.CouncilFeasibilityStatus
import com.achulkov.evomolior.core.council.CouncilRoleOutputBundle
import com.achulkov.evomolior.core.council.ExecutionEnvelope
import com.achulkov.evomolior.core.council.LocalNodeKey
import com.achulkov.evomolior.core.council.creditAffordableWorkerSlots

/** Necessary scheduling bounds; evaluated only after strict field compilation succeeds. */
internal object CouncilFeasibilityPlanner {
    fun assess(
        outputs: CouncilRoleOutputBundle,
        envelope: ExecutionEnvelope,
        replansUsed: Int
    ): CouncilFeasibilityReport {
        val byKey = outputs.taskAnatomist.nodes.associateBy { it.key }
        // Optional branches are not obligations merely because the Council described them.
        val required = outputs.synthesis.stages.flatMapTo(linkedSetOf()) { it.mandatoryNodeKeys }
        val frontier = ArrayDeque(required)
        while (frontier.isNotEmpty()) {
            byKey.getValue(frontier.removeFirst()).prerequisiteKeys.forEach { prerequisite ->
                if (required.add(prerequisite)) frontier.addLast(prerequisite)
            }
        }
        val nodes = required.map(byKey::getValue)
        val remainingPrerequisites = nodes.associate { it.key to it.prerequisiteKeys.size }.toMutableMap()
        val dependents = mutableMapOf<LocalNodeKey, MutableList<LocalNodeKey>>()
        nodes.forEach { node ->
            node.prerequisiteKeys.forEach { prerequisite ->
                dependents.getOrPut(prerequisite, ::mutableListOf).add(node.key)
            }
        }
        val depths = mutableMapOf<LocalNodeKey, Int>()
        val ready = ArrayDeque(nodes.filter { it.prerequisiteKeys.isEmpty() }.map { it.key })
        var visited = 0
        while (ready.isNotEmpty()) {
            val key = ready.removeFirst()
            visited += 1
            val depth = depths.getOrPut(key) { 1 }
            dependents[key].orEmpty().forEach { dependent ->
                depths[dependent] = maxOf(depths[dependent] ?: 1, depth + 1)
                val remaining = remainingPrerequisites.getValue(dependent) - 1
                remainingPrerequisites[dependent] = remaining
                if (remaining == 0) ready.addLast(dependent)
            }
        }
        check(visited == nodes.size) { "Feasibility requires a validated prerequisite DAG" }
        val waves = depths.values.maxOrNull() ?: 0
        // Credit-affordable slots, not the nominal ceiling: a plan that only fits with workers
        // the budget cannot fund is not feasible.
        val slots = envelope.creditAffordableWorkerSlots
        val groupRounds = if (slots > 0) {
            ((nodes.size.toLong() + slots - 1) / slots).toInt()
        } else if (nodes.isEmpty()) 0 else Int.MAX_VALUE
        val rounds = maxOf(waves, groupRounds)
        val reasons = buildList {
            if (waves > envelope.maxRounds) {
                add("Prerequisite execution requires at least $waves rounds; only ${envelope.maxRounds} allowed.")
            }
            if (groupRounds > envelope.maxRounds) {
                add("One candidate group's ${nodes.size} node grades require at least $groupRounds rounds " +
                    "with $slots worker slots per round; other groups' evidence cannot replace its own.")
                if (slots < envelope.maxAgentsPerGroupPerRound) {
                    add("Only $slots of ${envelope.maxAgentsPerGroupPerRound} nominal worker slots per " +
                        "group-round are affordable within the run's credit allocation.")
                }
            }
        }
        return CouncilFeasibilityReport(
            status = if (reasons.isEmpty()) CouncilFeasibilityStatus.FITS_SCHEDULING_BOUNDS
                else CouncilFeasibilityStatus.PROVISIONAL_ONLY,
            dependencyWaveLowerBound = waves,
            perGroupNodeRoundLowerBound = groupRounds,
            minimumRoundsLowerBound = rounds,
            availableRounds = envelope.maxRounds,
            slotsPerGroupPerRound = slots,
            workerGroupCount = outputs.ecologist.workerGroups.size,
            replansUsed = replansUsed,
            reasons = reasons
        )
    }
}
