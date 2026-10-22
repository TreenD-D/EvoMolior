package com.achulkov.evomolior.core.deliverable

import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** One public committed group snapshot eligible for non-accepted recovery. */
data class RecoverySourceCandidate(
    val groupId: String,
    val sourceSnapshotHash: String,
    val publicEvidenceRefs: Set<String>,
)

/**
 * Deterministic, neutral recovery source authority spanning every eligible group.
 *
 * Parallel [sourceGroupIds] and [sourceSnapshotHashes] preserve exact pairing. [sourceLabels]
 * contains only deterministic `NON_ACCEPTED_SOURCE_*` labels and conveys no rank.
 */
@Serializable
data class RecoverySourceSet(
    val schemaVersion: String = SCHEMA_VERSION,
    val runId: String,
    val sourceSnapshotHashes: List<String>,
    val sourceGroupIds: List<String>,
    val publicEvidenceRefs: List<String>,
    val sourceLabels: Map<String, String>,
    val exclusionReasons: Map<String, String>,
    val sourceSetHash: String = "",
) {
    init {
        require(schemaVersion == SCHEMA_VERSION && runId.isNotBlank()) {
            "Recovery source-set identity is invalid"
        }
        require(sourceGroupIds.isNotEmpty() && sourceGroupIds == sourceGroupIds.sorted()) {
            "Recovery source groups must be non-empty and sorted"
        }
        require(sourceGroupIds.distinct().size == sourceGroupIds.size) {
            "Recovery source groups must be unique"
        }
        require(
            sourceSnapshotHashes.size == sourceGroupIds.size &&
                sourceSnapshotHashes.all(String::isOutcomeSha256)
        ) { "Recovery source snapshots must pair exactly with source groups" }
        require(publicEvidenceRefs == publicEvidenceRefs.distinct().sorted()) {
            "Recovery public evidence references must be sorted and unique"
        }
        require(sourceLabels.keys.toList() == sourceGroupIds) {
            "Recovery source labels must follow sorted source-group order"
        }
        require(sourceGroupIds.mapIndexed { index, groupId ->
            sourceLabels[groupId] == neutralRecoveryLabel(index)
        }.all { it }) {
            "Recovery source labels must be deterministic and neutral"
        }
        require(exclusionReasons.keys.toList() == exclusionReasons.keys.sorted()) {
            "Recovery exclusion reasons must be sorted"
        }
        require(
            exclusionReasons.keys.none { it in sourceGroupIds } &&
                exclusionReasons.all { (groupId, reason) ->
                    groupId.isNotBlank() && reason.isNotBlank()
                }
        ) { "Eligible recovery sources cannot also be excluded" }
    }

    /** Returns this source set with its deterministic self-hash attached. */
    fun withComputedHash(): RecoverySourceSet =
        copy(sourceSetHash = hashRecoverySourceSet(this))

    /** Returns whether this authority commits every source, label, evidence ref, and exclusion. */
    fun hasValidHash(): Boolean = MessageDigest.isEqual(
        sourceSetHash.encodeToByteArray(),
        hashRecoverySourceSet(this).encodeToByteArray(),
    )

    companion object {
        /** Stable recovery source-set schema. */
        const val SCHEMA_VERSION: String = "recovery-source-set-v1"

        /**
         * Seals all eligible [candidates] in group-ID order with neutral labels.
         *
         * @param runId Owning run.
         * @param candidates Every eligible public committed snapshot.
         * @param exclusionReasons Public reasons for groups excluded before sealing.
         * @return Deterministic hashed recovery source set.
         */
        fun seal(
            runId: String,
            candidates: Collection<RecoverySourceCandidate>,
            exclusionReasons: Map<String, String> = emptyMap(),
        ): RecoverySourceSet {
            require(candidates.isNotEmpty()) { "Recovery requires at least one eligible source" }
            val ordered = candidates.sortedBy(RecoverySourceCandidate::groupId)
            require(ordered.distinctBy(RecoverySourceCandidate::groupId).size == ordered.size) {
                "Recovery candidates must have unique group IDs"
            }
            return RecoverySourceSet(
                runId = runId,
                sourceSnapshotHashes = ordered.map(RecoverySourceCandidate::sourceSnapshotHash),
                sourceGroupIds = ordered.map(RecoverySourceCandidate::groupId),
                publicEvidenceRefs = ordered
                    .flatMap { it.publicEvidenceRefs }
                    .distinct()
                    .sorted(),
                sourceLabels = linkedMapOf<String, String>().apply {
                    ordered.forEachIndexed { index, source ->
                        put(source.groupId, neutralRecoveryLabel(index))
                    }
                },
                exclusionReasons = exclusionReasons.toSortedMap(),
            ).withComputedHash()
        }
    }
}

private fun neutralRecoveryLabel(index: Int): String {
    require(index >= 0)
    var value = index
    val suffix = StringBuilder()
    do {
        suffix.append(('A'.code + value % 26).toChar())
        value = value / 26 - 1
    } while (value >= 0)
    return "NON_ACCEPTED_SOURCE_${suffix.reverse()}"
}

private val RECOVERY_SOURCE_JSON = Json {
    encodeDefaults = true
    explicitNulls = true
}

private fun hashRecoverySourceSet(value: RecoverySourceSet): String {
    val element = RECOVERY_SOURCE_JSON.encodeToJsonElement(
        RecoverySourceSet.serializer(),
        value,
    )
    val withoutHash = JsonObject(element.jsonObject.filterKeys { it != "sourceSetHash" })
    return MessageDigest.getInstance("SHA-256")
        .digest(canonicalRecoveryJson(withoutHash).encodeToByteArray())
        .joinToString("") { byte -> "%02x".format(byte) }
}

private fun canonicalRecoveryJson(element: JsonElement): String = when (element) {
    is JsonObject -> element.entries.sortedBy { it.key }.joinToString(
        prefix = "{",
        postfix = "}",
    ) { (key, value) ->
        Json.encodeToString(
            kotlinx.serialization.json.JsonPrimitive.serializer(),
            kotlinx.serialization.json.JsonPrimitive(key),
        ) + ":" + canonicalRecoveryJson(value)
    }
    is JsonArray -> element.joinToString(prefix = "[", postfix = "]") {
        canonicalRecoveryJson(it)
    }
    is kotlinx.serialization.json.JsonPrimitive -> element.toString()
}

private fun String.isOutcomeSha256(): Boolean = matches(Regex("[0-9a-f]{64}"))
