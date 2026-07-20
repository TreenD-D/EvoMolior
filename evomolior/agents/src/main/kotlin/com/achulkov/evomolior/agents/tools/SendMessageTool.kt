package com.achulkov.evomolior.agents.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.typeToken
import com.achulkov.evomolior.core.budget.BudgetDeductResult
import com.achulkov.evomolior.core.budget.BudgetLedger
import com.achulkov.evomolior.core.model.ActionType
import com.achulkov.evomolior.substrate.db.dao.EventLogDao
import com.achulkov.evomolior.substrate.db.entity.EventLogEntryEntity
import com.achulkov.evomolior.substrate.db.entity.EventTypes
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID
import com.achulkov.evomolior.sandbox.enforcer.ActionEventType
import com.achulkov.evomolior.sandbox.enforcer.AgentAction
import com.achulkov.evomolior.sandbox.enforcer.ConstitutionalActionGate

/**
 * Koog tool: send a message to another agent in the same group.
 *
 * Messages are persisted as EVENT_LOG entries with eventType="INTER_AGENT_MESSAGE".
 * The recipient agent can read them via [ReadMessagesTool].
 *
 * Cost: 0.5 budget units per message.
 *
 * @param budgetLedger Agent budget ledger.
 * @param eventLogDao   DAO for persisting the message event.
 * @param runId         The current run ID.
 * @param groupId       The current group ID.
 * @param agentId       The sending agent's ID.
 * @param generation    Current generation number.
 */
class SendMessageTool(
    private val budgetLedger: BudgetLedger,
    private val eventLogDao: EventLogDao,
    private val runId: String,
    private val groupId: String,
    private val agentId: String,
    private val generation: Int,
    private val actionGate: ConstitutionalActionGate = ConstitutionalActionGate.NONE
) : SimpleTool<SendMessageTool.Args>(
    argsType = typeToken<Args>(),
    name = "send_message",
    description = """
        Send a message to another agent in your group.
        Messages are indexed in the event log and can be read by the recipient.
        Use this to coordinate on WiP tasks, share discoveries, or request assistance.
        Cost: 0.5 budget units.
    """.trimIndent()
) {
    @Serializable
    data class Args(
        /** Target agent ID. Use "broadcast" to send to all agents in the group. */
        val recipientAgentId: String,
        /** The message content (max 2000 characters). */
        val message: String,
        /** Optional subject/topic tag for the message. */
        val subject: String = ""
    )

    override suspend fun execute(args: Args): String {
        if (args.message.isBlank()) {
            return "Error: Message content cannot be empty."
        }
        actionGate.intercept(
            AgentAction(
                type = ActionEventType.INTER_AGENT_MESSAGE,
                agentId = agentId,
                detail = args.recipientAgentId
            )
        )
        val deductResult = budgetLedger.deduct(ActionType.INTER_AGENT_MESSAGE)
        if (deductResult == BudgetDeductResult.BELOW_FLOOR) {
            return "Error: Budget exhausted. Cannot send message."
        }
        val trimmedMessage = args.message.take(2000)

        val payload = buildString {
            append("""{"from":"$agentId","to":"${args.recipientAgentId}","subject":"${args.subject}","message":""")
            append(Json.encodeToString(kotlinx.serialization.json.JsonPrimitive(trimmedMessage)))
            append("}")
        }

        val entity = EventLogEntryEntity(
            id = UUID.randomUUID().toString(),
            runId = runId,
            groupId = groupId,
            agentId = agentId,
            eventType = EventTypes.INTER_AGENT_MESSAGE,
            payloadJson = payload,
            generation = generation,
            timestampMs = System.currentTimeMillis()
        )
        eventLogDao.insert(entity)

        val recipient = if (args.recipientAgentId == "broadcast") "all agents" else args.recipientAgentId
        val floorWarning = if (deductResult == BudgetDeductResult.FLOOR_REACHED) {
            " WARNING: budget floor reached."
        } else ""
        return "Message sent to $recipient. Subject: \"${args.subject.take(100)}\". " +
            "Message logged as event ${entity.id}.$floorWarning"
    }
}
