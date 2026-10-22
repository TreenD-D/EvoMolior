package com.achulkov.evomolior.agents.factory

import com.achulkov.evomolior.sandbox.enforcer.ConstitutionalActionGate
import com.achulkov.evomolior.sandbox.enforcer.ConstitutionalEnforcer
import com.achulkov.evomolior.substrate.db.EvoMoliorDatabase
import com.achulkov.evomolior.substrate.db.entity.EventLogEntryEntity
import com.achulkov.evomolior.substrate.db.entity.EventTypes
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

internal fun databaseConstitutionalActionGate(
    enforcer: ConstitutionalEnforcer,
    db: EvoMoliorDatabase,
    runId: String,
    groupId: String,
    agentId: String,
    generation: Int
): ConstitutionalActionGate = ConstitutionalActionGate { action ->
    enforcer.intercept(action, agentId, groupId) { event ->
        val ruleName = when (val rule = event.rule) {
            is Enum<*> -> rule.name
            else -> rule.toString()
        }
        val payload = buildJsonObject {
            put("rule", ruleName)
            put("actionType", event.action.type.name)
            put("detail", event.action.detail)
            put("blocked", event.blocked)
            event.action.allowed?.let { put("allowed", it) }
            event.action.inToolSandbox?.let { put("inToolSandbox", it) }
            put("sizeBytes", event.action.sizeBytes)
        }
        db.eventLogDao().insert(
            EventLogEntryEntity(
                id = UUID.randomUUID().toString(),
                timestampMs = System.currentTimeMillis(),
                eventType = if (event.blocked) {
                    EventTypes.CONSTITUTIONAL_VIOLATION
                } else {
                    EventTypes.CONSTITUTIONAL_AUDIT
                },
                agentId = agentId,
                groupId = groupId,
                runId = runId,
                generation = generation,
                payloadJson = payload.toString()
            )
        )
    }
}
