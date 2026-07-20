package com.achulkov.evomolior.orchestration.inspector

import com.achulkov.evomolior.substrate.db.entity.EventLogEntryEntity
import com.achulkov.evomolior.substrate.db.entity.EventTypes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Typed, identity-preserving event consumed by the passive Inspector.
 *
 * @param eventId Stable append-only event identity used for causal deduplication.
 * @param kind Inspector-relevant event category.
 * @param outcome Enforcement or observation outcome.
 * @param actionType Optional typed action name carried by constitutional events.
 */
data class InspectorObservation(
    val eventId: String,
    val kind: InspectorObservationKind,
    val outcome: InspectorObservationOutcome,
    val actionType: String? = null
) {
    companion object {
        /**
         * Converts one append-only substrate event without discarding its identity or outcome.
         *
         * @param event Persisted event-log entry.
         * @return Typed Inspector observation.
         */
        fun from(event: EventLogEntryEntity): InspectorObservation {
            val payload = runCatching { Json.parseToJsonElement(event.payloadJson).jsonObject }.getOrNull()
            val actionType = payload?.get("actionType")?.jsonPrimitive?.content
            val explicitOutcome = payload?.get("outcome")?.jsonPrimitive?.content
            val blocked = payload?.get("blocked")?.jsonPrimitive?.booleanOrNull
            val outcome = when {
                explicitOutcome == InspectorObservationOutcome.CONFIRMED_VIOLATION.name ->
                    InspectorObservationOutcome.CONFIRMED_VIOLATION
                blocked == true -> InspectorObservationOutcome.BLOCKED
                else -> InspectorObservationOutcome.OBSERVED
            }
            val kind = when {
                event.eventType == EventTypes.CONSTITUTIONAL_VIOLATION ->
                    InspectorObservationKind.CONSTITUTIONAL_ENFORCEMENT
                event.eventType == EventTypes.LLM_CALL ||
                    actionType == "LLM_CALL" ||
                    actionType == "WEB_FETCH" ->
                    InspectorObservationKind.EXPENSIVE_ACTION
                event.eventType == EventTypes.INSPECTOR_FLAG &&
                    event.payloadJson.contains("turn_taking", ignoreCase = true) ->
                    InspectorObservationKind.TURN_TAKING
                else -> InspectorObservationKind.OTHER
            }
            return InspectorObservation(event.id, kind, outcome, actionType)
        }
    }
}

/** Inspector-relevant event category. */
enum class InspectorObservationKind {
    CONSTITUTIONAL_ENFORCEMENT,
    EXPENSIVE_ACTION,
    TURN_TAKING,
    OTHER
}

/** Outcome of the observed enforcement boundary. */
enum class InspectorObservationOutcome {
    /** The prohibited action was prevented successfully. */
    BLOCKED,

    /** A violation was independently confirmed and requires incident handling. */
    CONFIRMED_VIOLATION,

    /** Ordinary observed activity, including constitutional logging rules. */
    OBSERVED
}
