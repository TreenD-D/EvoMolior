package com.achulkov.evomolior.core.deliverable

import java.security.MessageDigest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Canonical JSON and SHA-256 operations for semantic deliverable contracts. */
object DeliverableHashing {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        classDiscriminator = "blockType"
    }

    /** Returns the canonical semantic JSON for [value]. */
    fun encodePackage(value: ReceiverDecisionPackage): String =
        canonicalJson(json.encodeToJsonElement(ReceiverDecisionPackage.serializer(), value))

    /** Decodes a strict semantic package JSON document. */
    fun decodePackage(value: String): ReceiverDecisionPackage =
        json.decodeFromString(ReceiverDecisionPackage.serializer(), value)

    /** Hashes [blueprint] while excluding its self-referential hash. */
    fun hashBlueprint(blueprint: DeliverableBlueprint): String =
        hashWithoutField(
            DeliverableBlueprint.serializer(),
            blueprint,
            "blueprintHash"
        )

    /** Hashes [component] while excluding its self-referential hash. */
    fun hashComponent(component: DeliverableComponent): String =
        hashWithoutField(
            DeliverableComponent.serializer(),
            component,
            "contentHash"
        )

    /** Hashes [decisionPackage] while excluding its self-referential hash. */
    fun hashPackage(decisionPackage: ReceiverDecisionPackage): String =
        hashWithoutField(
            ReceiverDecisionPackage.serializer(),
            decisionPackage,
            "packageHash"
        )

    /** Hashes [snapshot] while excluding its self-referential hash. */
    fun hashSnapshot(snapshot: InterimDeliverableSnapshot): String =
        hashWithoutField(
            InterimDeliverableSnapshot.serializer(),
            snapshot,
            "snapshotHash"
        )

    /** Hashes exact UTF-8 [value]. */
    fun hashText(value: String): String = sha256(value)

    /** Compares two hashes without content-dependent early exit. */
    fun constantTimeEquals(expected: String, actual: String): Boolean =
        MessageDigest.isEqual(expected.encodeToByteArray(), actual.encodeToByteArray())

    /** Produces canonical JSON with sorted object keys and normalized set-valued fields. */
    fun canonicalJson(element: JsonElement): String = canonicalize(element).toString()

    private fun <T> hashWithoutField(
        serializer: KSerializer<T>,
        value: T,
        field: String,
    ): String {
        val element = json.encodeToJsonElement(serializer, value)
        return sha256(canonicalJson(JsonObject(element.jsonObject.filterKeys { it != field })))
    }

    private fun canonicalize(element: JsonElement, fieldName: String? = null): JsonElement =
        when (element) {
            is JsonObject -> JsonObject(
                element.entries
                    .sortedBy { it.key }
                    .associate { (key, value) -> key to canonicalize(value, key) }
            )
            is JsonArray -> {
                val values = element.map { canonicalize(it) }
                JsonArray(
                    if (fieldName in SET_FIELDS) values.sortedBy(JsonElement::toString) else values
                )
            }
            is JsonPrimitive -> element
        }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.encodeToByteArray())
            .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private val SET_FIELDS = setOf(
        "companionFormats",
        "sourceNodeIds",
        "provenanceHashes",
        "evidenceIds",
        "evidencePacketIds",
        "toolExecutionIds",
        "simulationExecutionIds",
        "missingEvidenceIds",
        "sourceAuthorityIds",
    )
}
