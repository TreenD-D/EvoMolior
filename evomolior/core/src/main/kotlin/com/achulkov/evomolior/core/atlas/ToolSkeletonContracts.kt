package com.achulkov.evomolior.core.atlas

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * One deterministic generated file in an atomic tool project skeleton.
 *
 * @param relativePath Safe project-relative path.
 * @param content Exact UTF-8 content.
 * @param executable Whether the generated file is intended to execute directly.
 */
@Serializable
data class ToolSkeletonFile(
    val relativePath: String,
    val content: String,
    val executable: Boolean = false
) {
    init {
        require(relativePath.isNotBlank() && !relativePath.startsWith("/") &&
            relativePath.split('/').none { it.isBlank() || it == "." || it == ".." }
        ) { "Tool skeleton path must be safe and relative" }
        require(content.isNotBlank()) { "Tool skeleton file content must not be blank" }
    }
}

/**
 * Deterministic bounded project generated from one sealed opportunity.
 *
 * @param skeletonId Stable skeleton identity.
 * @param opportunityCommitmentHash Exact Council opportunity provenance.
 * @param implementationMode Constrained implementation mode.
 * @param inputSchemaJson Generated canonical JSON input schema.
 * @param outputSchemaJson Generated canonical JSON output schema.
 * @param files Ordered generated files.
 * @param skeletonHash Hash excluding this field.
 */
@Serializable
data class ToolProjectSkeleton(
    val skeletonId: String,
    val opportunityCommitmentHash: String,
    val implementationMode: ToolImplementationMode,
    val inputSchemaJson: String,
    val outputSchemaJson: String,
    val files: List<ToolSkeletonFile>,
    val skeletonHash: String = ""
) {
    init {
        require(skeletonId.isNotBlank() && opportunityCommitmentHash.isNotBlank()) {
            "Tool skeleton identity and opportunity commitment must not be blank"
        }
        require(inputSchemaJson.isNotBlank() && outputSchemaJson.isNotBlank()) {
            "Tool skeleton schemas must not be blank"
        }
        require(files.isNotEmpty() && files.map(ToolSkeletonFile::relativePath).distinct().size ==
            files.size
        ) { "Tool skeleton requires unique generated files" }
        require(files.count { it.executable } == 1) {
            "Tool skeleton must expose exactly one atomic executable"
        }
    }

    /** Returns this skeleton with its deterministic self hash. */
    fun withComputedHash(): ToolProjectSkeleton =
        copy(skeletonHash = AtlasHashing.hashToolProjectSkeleton(this))

    /** Returns whether this skeleton carries its exact deterministic hash. */
    fun hasValidHash(): Boolean =
        AtlasHashing.constantTimeEquals(skeletonHash, AtlasHashing.hashToolProjectSkeleton(this))
}

/** Deterministically compiles Council typed fields into bounded JSON object schemas. */
object TypedToolSchemaCompiler {
    /**
     * Compiles [fields] into one deterministic object-schema value.
     *
     * @param fields Council-authored fields.
     * @return Exact bounded object schema.
     */
    fun compileSchema(fields: List<TypedField>): JsonObject {
        require(fields.isNotEmpty()) { "Typed tool schema requires at least one field" }
        val properties = JsonObject(fields.sortedBy(TypedField::name).associate { field ->
            field.name to compileField(field)
        })
        val required = fields.filter(TypedField::required).map(TypedField::name).sorted()
        return JsonObject(
            mapOf(
                "\$schema" to JsonPrimitive("https://json-schema.org/draft/2020-12/schema"),
                "additionalProperties" to JsonPrimitive(false),
                "properties" to properties,
                "required" to JsonArray(required.map(::JsonPrimitive)),
                "type" to JsonPrimitive("object")
            )
        )
    }

    /**
     * Compiles [fields] into canonical JSON Schema.
     *
     * @param fields Council-authored fields.
     * @return Canonical object-schema JSON.
     */
    fun compile(fields: List<TypedField>): String =
        AtlasHashing.canonicalJson(compileSchema(fields))

    private fun compileField(field: TypedField): JsonObject = JsonObject(buildMap {
        put("type", JsonPrimitive(field.type.schemaType))
        put("description", JsonPrimitive(field.description))
        when (field.type) {
            TypedFieldType.ARRAY -> {
                put("minItems", JsonPrimitive(requireNotNull(field.minimumItems)))
                put("maxItems", JsonPrimitive(requireNotNull(field.maximumItems)))
                put("items", compileArrayItems(field))
            }
            TypedFieldType.OBJECT -> putAll(compileObject(field.objectFields))
            else -> Unit
        }
        if (field.enumValues.isNotEmpty()) {
            put("enum", JsonArray(field.enumValues.sorted().map(::JsonPrimitive)))
        }
        if (field.validationRules.isNotEmpty()) {
            put(
                "x-validationRules",
                JsonArray(field.validationRules.sorted().map(::JsonPrimitive))
            )
        }
    })

    private fun compileArrayItems(field: TypedField): JsonObject =
        when (requireNotNull(field.itemType)) {
            TypedFieldType.OBJECT -> JsonObject(
                buildMap {
                    put("type", JsonPrimitive("object"))
                    putAll(compileObject(field.objectFields))
                }
            )
            else -> JsonObject(
                mapOf("type" to JsonPrimitive(requireNotNull(field.itemType).schemaType))
            )
        }

    private fun compileObject(fields: Collection<TypedField>): Map<String, JsonElement> = mapOf(
        "additionalProperties" to JsonPrimitive(false),
        "properties" to JsonObject(
            fields.sortedBy(TypedField::name).associate { field ->
                field.name to compileField(field)
            }
        ),
        "required" to JsonArray(
            fields.filter(TypedField::required)
                .map(TypedField::name)
                .sorted()
                .map(::JsonPrimitive)
        )
    )

    private val TypedFieldType.schemaType: String
        get() = when (this) {
            TypedFieldType.STRING -> "string"
            TypedFieldType.INTEGER -> "integer"
            TypedFieldType.NUMBER -> "number"
            TypedFieldType.BOOLEAN -> "boolean"
            TypedFieldType.JSON -> "object"
            TypedFieldType.ARRAY -> "array"
            TypedFieldType.OBJECT -> "object"
        }
}

/** Stable category for one bounded tool-schema validation failure. */
@Serializable
enum class TypedToolSchemaValidationCode {
    MISSING_TYPE,
    WRONG_TYPE,
    ENUM_MISMATCH,
    TOO_FEW_ITEMS,
    TOO_MANY_ITEMS,
    MISSING_ITEMS,
    MISSING_PROPERTY,
    ADDITIONAL_PROPERTY,
    INVALID_PROPERTY_SCHEMA
}

/**
 * One typed tool-schema validation failure.
 *
 * @param code Stable failure category.
 * @param path Exact JSON-value path rooted at `root`.
 * @param actualItemCount Actual array cardinality for a cardinality failure.
 * @param minimumItemCount Inclusive schema minimum for a cardinality failure.
 * @param maximumItemCount Inclusive schema maximum for a cardinality failure.
 */
@Serializable
data class TypedToolSchemaValidationFailure(
    val code: TypedToolSchemaValidationCode,
    val path: String,
    val actualItemCount: Long? = null,
    val minimumItemCount: Long? = null,
    val maximumItemCount: Long? = null
) {
    init {
        require(path.isNotBlank()) { "Tool schema validation path must not be blank" }
        require(actualItemCount == null || actualItemCount >= 0) {
            "Actual item count must not be negative"
        }
        require(minimumItemCount == null || minimumItemCount >= 0) {
            "Minimum item count must not be negative"
        }
        require(maximumItemCount == null || maximumItemCount >= 0) {
            "Maximum item count must not be negative"
        }
        if (code == TypedToolSchemaValidationCode.TOO_FEW_ITEMS) {
            require(actualItemCount != null && minimumItemCount != null) {
                "Too-few-items failures require actual and minimum counts"
            }
        }
        if (code == TypedToolSchemaValidationCode.TOO_MANY_ITEMS) {
            require(actualItemCount != null && maximumItemCount != null) {
                "Too-many-items failures require actual and maximum counts"
            }
        }
    }

    /** Returns the backward-compatible stable error code used at runtime boundaries. */
    val stableCode: String
        get() = when (code) {
            TypedToolSchemaValidationCode.MISSING_TYPE -> "missing_type_$path"
            TypedToolSchemaValidationCode.WRONG_TYPE -> "wrong_type_$path"
            TypedToolSchemaValidationCode.ENUM_MISMATCH -> "enum_mismatch_$path"
            TypedToolSchemaValidationCode.TOO_FEW_ITEMS -> "too_few_items_$path"
            TypedToolSchemaValidationCode.TOO_MANY_ITEMS -> "too_many_items_$path"
            TypedToolSchemaValidationCode.MISSING_ITEMS -> "missing_items_$path"
            TypedToolSchemaValidationCode.MISSING_PROPERTY -> "missing_$path"
            TypedToolSchemaValidationCode.ADDITIONAL_PROPERTY ->
                "additional_property_$path"
            TypedToolSchemaValidationCode.INVALID_PROPERTY_SCHEMA ->
                "invalid_property_schema"
        }
}

/** Validates runtime JSON against the bounded schema subset emitted by [TypedToolSchemaCompiler]. */
object TypedToolSchemaValidator {
    /**
     * Returns one typed failure or null when [value] satisfies [schema].
     *
     * @param value Runtime input or output.
     * @param schema Compiled object schema.
     */
    fun validationFailure(
        value: JsonElement,
        schema: JsonObject
    ): TypedToolSchemaValidationFailure? = validationFailure(value, schema, "root")

    /**
     * Returns a stable error code or null when [value] satisfies [schema].
     *
     * @param value Runtime input or output.
     * @param schema Compiled object schema.
     */
    fun validationError(value: JsonElement, schema: JsonObject): String? =
        validationFailure(value, schema)?.stableCode

    private fun validationFailure(
        value: JsonElement,
        schema: JsonObject,
        path: String
    ): TypedToolSchemaValidationFailure? {
        val type = (schema["type"] as? JsonPrimitive)?.contentOrNull
            ?: return failure(TypedToolSchemaValidationCode.MISSING_TYPE, path)
        val typeMatches = when (type) {
            "string" -> value is JsonPrimitive && value.isString
            "integer" -> value is JsonPrimitive && !value.isString && value.longOrNull != null
            "number" -> value is JsonPrimitive && !value.isString && value.doubleOrNull != null
            "boolean" ->
                value is JsonPrimitive && !value.isString && value.booleanOrNull != null
            "array" -> value is JsonArray
            "object" -> value is JsonObject
            else -> false
        }
        if (!typeMatches) return failure(TypedToolSchemaValidationCode.WRONG_TYPE, path)

        val enumValues = schema["enum"] as? JsonArray
        if (enumValues != null && value !in enumValues) {
            return failure(TypedToolSchemaValidationCode.ENUM_MISMATCH, path)
        }

        if (value is JsonArray) {
            val minimumItems = (schema["minItems"] as? JsonPrimitive)?.longOrNull
            val maximumItems = (schema["maxItems"] as? JsonPrimitive)?.longOrNull
            if (minimumItems != null && value.size.toLong() < minimumItems) {
                return failure(
                    code = TypedToolSchemaValidationCode.TOO_FEW_ITEMS,
                    path = path,
                    actualItemCount = value.size.toLong(),
                    minimumItemCount = minimumItems,
                    maximumItemCount = maximumItems
                )
            }
            if (maximumItems != null && value.size.toLong() > maximumItems) {
                return failure(
                    code = TypedToolSchemaValidationCode.TOO_MANY_ITEMS,
                    path = path,
                    actualItemCount = value.size.toLong(),
                    minimumItemCount = minimumItems,
                    maximumItemCount = maximumItems
                )
            }
            val items = schema["items"] as? JsonObject
                ?: return failure(TypedToolSchemaValidationCode.MISSING_ITEMS, path)
            value.forEachIndexed { index, item ->
                validationFailure(item, items, "$path[$index]")?.let { return it }
            }
            return null
        }

        if (value !is JsonObject) return null
        val properties = schema["properties"] as? JsonObject ?: return null
        val required = (schema["required"] as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
        required.firstOrNull { it !in value }?.let {
            return failure(TypedToolSchemaValidationCode.MISSING_PROPERTY, "$path.$it")
        }
        if (schema["additionalProperties"]?.jsonPrimitive?.booleanOrNull == false &&
            value.keys.any { it !in properties }
        ) {
            val unexpected = value.keys.first { it !in properties }
            return failure(
                TypedToolSchemaValidationCode.ADDITIONAL_PROPERTY,
                "$path.$unexpected"
            )
        }
        properties.forEach { (name, definitionElement) ->
            val field = value[name] ?: return@forEach
            val definition = definitionElement as? JsonObject
                ?: return failure(
                    TypedToolSchemaValidationCode.INVALID_PROPERTY_SCHEMA,
                    "$path.$name"
                )
            validationFailure(field, definition, "$path.$name")?.let { return it }
        }
        return null
    }

    private fun failure(
        code: TypedToolSchemaValidationCode,
        path: String,
        actualItemCount: Long? = null,
        minimumItemCount: Long? = null,
        maximumItemCount: Long? = null
    ) = TypedToolSchemaValidationFailure(
        code = code,
        path = path,
        actualItemCount = actualItemCount,
        minimumItemCount = minimumItemCount,
        maximumItemCount = maximumItemCount
    )
}
