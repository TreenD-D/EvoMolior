package com.achulkov.evomolior.agents.factory

import ai.koog.prompt.params.LLMParams
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Removes Koog's human-readable root `$id`, which is not a valid Draft 2020-12 URI reference.
 */
internal fun LLMParams.Schema.JSON.Standard.withoutGeneratorRootId(
    expectedName: String
): LLMParams.Schema.JSON.Standard {
    require(schema["\$id"]?.jsonPrimitive?.content == expectedName) {
        "Generated Council schema root identity changed for '$expectedName'"
    }
    return copy(schema = JsonObject(schema - "\$id"))
}

/**
 * Drops generator definitions that cannot be reached from the transported schema root.
 */
internal fun JsonObject.pruneUnreachableDefinitions(): JsonObject {
    val definitions = getValue("\$defs").jsonObject
    val reachable = linkedSetOf<String>()
    fun visit(element: JsonElement) {
        when (element) {
            is JsonArray -> element.forEach(::visit)
            is JsonObject -> {
                element["\$ref"]?.jsonPrimitive?.content?.let { reference ->
                    require(reference.startsWith("#/\$defs/")) {
                        "Generated Council schema contains non-local reference '$reference'"
                    }
                    val definitionName = reference.removePrefix("#/\$defs/")
                    if (reachable.add(definitionName)) {
                        val definition = definitions[definitionName]?.jsonObject
                            ?: error("Generated Council schema is missing definition '$definitionName'")
                        visit(definition)
                    }
                }
                element.forEach { (name, child) ->
                    if (name != "\$defs") visit(child)
                }
            }
            else -> Unit
        }
    }
    val root = JsonObject(this - "\$defs")
    visit(root)
    return JsonObject(
        root + ("\$defs" to JsonObject(definitions.filterKeys(reachable::contains)))
    )
}
