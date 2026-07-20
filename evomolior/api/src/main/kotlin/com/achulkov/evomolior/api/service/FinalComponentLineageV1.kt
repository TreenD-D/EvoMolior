package com.achulkov.evomolior.api.service

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal const val FINAL_COMPONENT_LINEAGE_V1_MARKER = "FINAL COMPONENT LINEAGE V1"
internal const val FINAL_COMPONENT_LINEAGE_V1_SCHEMA = "final-component-lineage-v1"

internal object FinalComponentLineageOrder {
    fun requireAfterDoc(
        docSubmittedAtMs: Long,
        lineageSubmittedAtMs: Long
    ) {
        require(lineageSubmittedAtMs > docSubmittedAtMs) {
            "Final component lineage PLAN_UPDATE must be strictly after the writer DOC"
        }
    }

    fun requireBeforeDeathNote(
        lineageSubmittedAtMs: Long,
        deathNoteSubmittedAtMs: Long
    ) {
        require(deathNoteSubmittedAtMs > lineageSubmittedAtMs) {
            "Final component lineage PLAN_UPDATE must be strictly before the writer Death Note"
        }
    }
}

@Serializable
private data class FinalComponentLineageV1Wire(
    val schemaVersion: String,
    val outputRelativePath: String,
    val componentNodeIds: Map<String, List<String>>
)

/**
 * Strict codec for the final writer's component-to-node selection record.
 *
 * The rendered deliverable is never parsed for causal lineage. The writer instead stages this
 * separate typed record after the final `DOC` and before its Death Note.
 */
internal object FinalComponentLineageV1Codec {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        isLenient = false
    }

    fun parse(
        content: String,
        expectedOutputRelativePath: String,
        requiredComponentIds: Set<String>,
        availableNodeIds: Set<String>
    ): Map<String, Set<String>> {
        require(requiredComponentIds.isNotEmpty()) {
            "Component lineage is required only when final components are declared"
        }
        val lines = content.trim().lines()
        require(lines.firstOrNull()?.trim() == FINAL_COMPONENT_LINEAGE_V1_MARKER) {
            "Final component lineage marker is missing"
        }
        val payload = lines.drop(1).joinToString("\n").trim()
        require(payload.isNotBlank()) { "Final component lineage JSON is missing" }
        val wire = json.decodeFromString<FinalComponentLineageV1Wire>(payload)
        require(wire.schemaVersion == FINAL_COMPONENT_LINEAGE_V1_SCHEMA) {
            "Unsupported final component lineage schema ${wire.schemaVersion}"
        }
        require(wire.outputRelativePath == expectedOutputRelativePath) {
            "Final component lineage output path does not match the final output contract"
        }
        require(wire.componentNodeIds.keys == requiredComponentIds) {
            val missing = requiredComponentIds - wire.componentNodeIds.keys
            val extra = wire.componentNodeIds.keys - requiredComponentIds
            "Final component lineage keys do not match the required components " +
                "(missing=${missing.sorted()}, extra=${extra.sorted()})"
        }
        return requiredComponentIds.sorted().associateWith { componentId ->
            val nodeIds = requireNotNull(wire.componentNodeIds[componentId])
            require(nodeIds.none(String::isBlank)) {
                "Final component $componentId contains a blank node ID"
            }
            require(nodeIds.distinct().size == nodeIds.size) {
                "Final component $componentId contains duplicate node IDs"
            }
            val unknown = nodeIds.toSet() - availableNodeIds
            require(unknown.isEmpty()) {
                "Final component $componentId references unknown accepted nodes: ${unknown.sorted()}"
            }
            nodeIds.toSortedSet()
        }
    }

    fun promptRequirements(
        outputRelativePath: String,
        requiredComponentIds: Set<String>,
        availableNodes: Map<String, String>
    ): String {
        require(requiredComponentIds.isNotEmpty()) {
            "Component-lineage prompt requires at least one final component"
        }
        val template = FinalComponentLineageV1Wire(
            schemaVersion = FINAL_COMPONENT_LINEAGE_V1_SCHEMA,
            outputRelativePath = outputRelativePath,
            componentNodeIds = requiredComponentIds.sorted().associateWith { emptyList() }
        )
        return buildString {
            appendLine("=== FINAL COMPONENT LINEAGE AUTHORITY ===")
            appendLine(
                "This mandatory sequence replaces any earlier direct DOC-to-Death-Note instruction: " +
                    "after the final DOC write succeeds, stage exactly one PLAN_UPDATE from this " +
                    "same writer, then write the Death Note."
            )
            appendLine("The PLAN_UPDATE content must begin with the exact marker:")
            appendLine(FINAL_COMPONENT_LINEAGE_V1_MARKER)
            appendLine("Immediately after the marker, write strict JSON with this exact shape:")
            appendLine(json.encodeToString(template))
            appendLine(
                "componentNodeIds must contain every required component key exactly once, with no " +
                    "missing or extra keys. Select only node IDs that materially support that " +
                    "component. An empty array is allowed and is more honest than invented lineage."
            )
            appendLine("Required component IDs:")
            requiredComponentIds.sorted().forEach { appendLine("- $it") }
            appendLine("Available currently accepted node IDs:")
            if (availableNodes.isEmpty()) {
                appendLine("- none")
            } else {
                availableNodes.toSortedMap().forEach { (nodeId, description) ->
                    appendLine("- $nodeId: ${description.ifBlank { "(no description)" }}")
                }
            }
            appendLine(
                "Do not infer node IDs from the rendered output, use artifact IDs as node IDs, or " +
                    "write the Death Note before this PLAN_UPDATE succeeds."
            )
        }.trim()
    }
}
