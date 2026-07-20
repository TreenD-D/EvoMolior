package com.achulkov.evomolior.core.contract

import com.achulkov.evomolior.core.council.CouncilFieldSealing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Lightweight version inspection performed before deserializing persisted StartingField bytes.
 *
 * Historical runs remain immutable evidence. They are never decoded with defaults or inferred
 * into the current authority contract.
 */
object StartingFieldWireContract {
    /** Exact wire version introduced by the v62 structural tool-contract revision. */
    const val CURRENT_VERSION = "evomolior-starting-field-v62"

    /**
     * Inspects [encoded] without invoking the full StartingField serializer.
     *
     * @param encoded Persisted StartingField JSON.
     * @return A typed compatibility result suitable for startup and archive diagnostics.
     */
    fun inspect(encoded: String): StartingFieldWireInspection {
        val root = runCatching {
            Json.parseToJsonElement(encoded) as? JsonObject
        }.getOrElse { error ->
            return StartingFieldWireInspection.Malformed(error.message.orEmpty())
        } ?: return StartingFieldWireInspection.Malformed("root must be a JSON object")
        val version = (root["wireVersion"] as? JsonPrimitive)?.contentOrNull
        if (version != CURRENT_VERSION) {
            return StartingFieldWireInspection.Incompatible(version)
        }
        val seal = root["seal"]
        if (seal == null || seal is JsonNull) return StartingFieldWireInspection.Current
        val sealObject = seal as? JsonObject
            ?: return StartingFieldWireInspection.Malformed("seal must be an object or null")
        val sealVersion = (sealObject["schemaVersion"] as? JsonPrimitive)?.contentOrNull
        return if (sealVersion == CouncilFieldSealing.SEAL_SCHEMA_VERSION) {
            StartingFieldWireInspection.Current
        } else {
            StartingFieldWireInspection.IncompatibleSeal(sealVersion)
        }
    }

    /**
     * Returns a stable diagnostic when [encoded] is not current authority.
     *
     * @param encoded Persisted StartingField JSON.
     * @param subject Human-readable location of the bytes.
     * @return Null for current bytes, otherwise an explicit incompatibility or malformed message.
     */
    fun validationError(encoded: String, subject: String): String? =
        when (val inspection = inspect(encoded)) {
            StartingFieldWireInspection.Current -> null
            is StartingFieldWireInspection.Incompatible -> {
                val actual = inspection.actualVersion ?: "missing (pre-v62)"
                "$subject has incompatible StartingField wireVersion=$actual; " +
                    "current=${CURRENT_VERSION}. Historical runs remain audit evidence and " +
                    "cannot be resumed, continued, or rebuilt as current authority."
            }
            is StartingFieldWireInspection.IncompatibleSeal -> {
                val actual = inspection.actualVersion ?: "missing"
                "$subject has incompatible StartingField seal.schemaVersion=$actual; " +
                    "current=${CouncilFieldSealing.SEAL_SCHEMA_VERSION}. Restamped or stale " +
                    "sealed fields cannot become current authority."
            }
            is StartingFieldWireInspection.Malformed ->
                "$subject is not valid StartingField JSON: ${inspection.reason}"
        }
}

/** Result of inspecting serialized StartingField version authority. */
sealed interface StartingFieldWireInspection {
    /** The bytes explicitly declare the current wire contract. */
    data object Current : StartingFieldWireInspection

    /**
     * The bytes are valid JSON but do not declare current authority.
     *
     * @param actualVersion Declared version, or null for pre-v62 bytes.
     */
    data class Incompatible(
        val actualVersion: String?
    ) : StartingFieldWireInspection

    /**
     * The top-level wire is current but its non-null cryptographic seal is stale or incomplete.
     *
     * @param actualVersion Declared seal schema version, or null when it is missing.
     */
    data class IncompatibleSeal(
        val actualVersion: String?
    ) : StartingFieldWireInspection

    /**
     * The bytes are not a StartingField JSON object.
     *
     * @param reason Compact parser diagnostic.
     */
    data class Malformed(
        val reason: String
    ) : StartingFieldWireInspection
}
