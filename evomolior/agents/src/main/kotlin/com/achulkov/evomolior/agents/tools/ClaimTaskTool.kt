package com.achulkov.evomolior.agents.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.typeToken
import com.achulkov.evomolior.substrate.db.dao.WipTaskDao
import com.achulkov.evomolior.sandbox.enforcer.ActionEventType
import com.achulkov.evomolior.sandbox.enforcer.AgentAction
import com.achulkov.evomolior.sandbox.enforcer.ConstitutionalActionGate
import kotlinx.serialization.Serializable

/**
 * Koog tool: claim a WiP task from the task registry.
 *
 * Cost: 0 (always free). The claim is atomic — only one agent can hold a task at a time.
 *
 * @param wipTaskDao DAO for WiP task access.
 * @param runId The current run ID.
 * @param groupId The current group ID.
 * @param agentId The current agent ID.
 * @param generation The current generation number.
 */
class ClaimTaskTool(
    private val wipTaskDao: WipTaskDao,
    private val runId: String,
    private val groupId: String,
    private val agentId: String,
    private val generation: Int,
    private val actionGate: ConstitutionalActionGate = ConstitutionalActionGate.NONE
) : SimpleTool<ClaimTaskTool.Args>(
    argsType = typeToken<Args>(),
    name = "claim_task",
    description = """
        Claim a WiP (Work-in-Progress) task to work on.
        If nodeId is omitted, the oldest unclaimed task is returned.
        Cost: FREE.
    """.trimIndent()
) {
    @Serializable
    data class Args(
        val nodeId: String? = null
    )

    override suspend fun execute(args: Args): String {
        val task = if (args.nodeId != null) {
            wipTaskDao.getByNodeId(args.nodeId, runId, groupId)
                ?: return "Error: Task '${args.nodeId}' not found."
        } else {
            wipTaskDao.getNextUnclaimed(runId, groupId)
                ?: return "No unclaimed tasks available."
        }

        if (task.status != "unclaimed") {
            return "Task '${task.nodeId}' is already ${task.status}${task.claimedByAgentId?.let { " by agent $it" } ?: ""}."
        }

        actionGate.intercept(
            AgentAction(
                type = ActionEventType.WIP_TASK_CLAIM,
                agentId = agentId,
                detail = task.nodeId
            )
        )
        val claimed = wipTaskDao.claimTask(task.nodeId, runId, groupId, agentId, generation)
        return if (claimed > 0) {
            "Task claimed.\nTask ID: ${task.nodeId}\nDescription: ${task.description}"
        } else {
            "Failed to claim task '${task.nodeId}' — race condition, try again."
        }
    }
}
