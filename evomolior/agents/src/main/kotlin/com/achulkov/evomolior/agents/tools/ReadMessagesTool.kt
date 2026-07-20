package com.achulkov.evomolior.agents.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.typeToken
import com.achulkov.evomolior.substrate.db.dao.EventLogDao
import com.achulkov.evomolior.substrate.db.entity.EventLogEntryEntity
import com.achulkov.evomolior.substrate.db.entity.EventTypes
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Koog tool: read intra-group messages addressed to this agent or broadcast to the group.
 *
 * This completes EvoMolior's custom A2A path by making [SendMessageTool] output available to
 * later tool calls. It is intentionally group-local and backed by the append-only event log.
 */
class ReadMessagesTool(
    private val eventLogDao: EventLogDao,
    private val runId: String,
    private val groupId: String,
    private val agentId: String
) : SimpleTool<ReadMessagesTool.Args>(
    argsType = typeToken<Args>(),
    name = "read_messages",
    description = """
        Read recent messages sent to you or broadcast inside your group.
        Returns JSON with sender, recipient, subject, message, generation, and timestamp.
        Reads are free.
    """.trimIndent()
) {
    @Serializable
    data class Args(
        val includeBroadcast: Boolean = true,
        val maxResults: Int = 20,
        val sinceGeneration: Int? = null
    )

    override suspend fun execute(args: Args): String {
        val messages = eventLogDao.queryFiltered(
            runId = runId,
            groupId = groupId,
            agentId = null,
            eventType = EventTypes.INTER_AGENT_MESSAGE,
            minGeneration = args.sinceGeneration,
            maxGeneration = null,
            limit = 500,
            offset = 0
        ).asSequence()
            .mapNotNull(::decodeMessage)
            .filter { message ->
                message.to == agentId || (args.includeBroadcast && message.to == BROADCAST_RECIPIENT)
            }
            .take(args.maxResults.coerceIn(1, 100))
            .toList()

        return buildJsonArray {
            messages.forEach { message ->
                add(
                    buildJsonObject {
                        put("eventId", message.eventId)
                        put("from", message.from)
                        put("to", message.to)
                        put("subject", message.subject)
                        put("message", message.message)
                        put("generation", message.generation)
                        put("timestampMs", message.timestampMs)
                    }
                )
            }
        }.toString()
    }

    private fun decodeMessage(event: EventLogEntryEntity): MessageRow? =
        runCatching {
            val obj = json.parseToJsonElement(event.payloadJson).jsonObject
            MessageRow(
                eventId = event.id,
                from = obj["from"]?.jsonPrimitive?.contentOrNull ?: event.agentId.orEmpty(),
                to = obj["to"]?.jsonPrimitive?.contentOrNull ?: "",
                subject = obj["subject"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                message = obj["message"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                generation = event.generation,
                timestampMs = event.timestampMs
            )
        }.getOrNull()

    private data class MessageRow(
        val eventId: String,
        val from: String,
        val to: String,
        val subject: String,
        val message: String,
        val generation: Int,
        val timestampMs: Long
    )

    private companion object {
        const val BROADCAST_RECIPIENT = "broadcast"
        val json = Json { ignoreUnknownKeys = true }
    }
}
