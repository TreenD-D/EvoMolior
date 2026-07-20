package com.achulkov.evomolior.agents.factory

import ai.koog.prompt.executor.clients.openai.base.structure.OpenAIStandardJsonSchemaGenerator
import ai.koog.prompt.params.LLMParams
import com.achulkov.evomolior.core.council.EcologistOutput
import com.achulkov.evomolior.core.council.IncentiveDesignerOutput
import com.achulkov.evomolior.core.council.KgArchitectOutput
import com.achulkov.evomolior.core.council.ModelEcologistOutput
import com.achulkov.evomolior.core.council.PromptArchitectOutput
import com.achulkov.evomolior.core.council.SeedCuratorOutput
import com.achulkov.evomolior.core.council.TaskAnatomistOutput
import com.achulkov.evomolior.core.council.CouncilPromotionFixtureWireSchemaAuthority
import com.achulkov.evomolior.core.council.CouncilSealedTestWireKind
import com.achulkov.evomolior.core.council.CouncilSynthesisWireAuthority
import com.achulkov.evomolior.core.config.AcceptanceProfile
import com.achulkov.evomolior.core.model.Role
import com.achulkov.evomolior.core.serialization.CouncilSynthesisContextualOutputSerializer
import com.achulkov.evomolior.core.serialization.CouncilSynthesisProviderSchemaContract
import com.achulkov.evomolior.core.serialization.TaskAnatomistContextualOutputSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.ClassDiscriminatorMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Generates strict OpenAI-compatible schemas from the authoritative Council serializers.
 *
 * The provider schema constrains generation only. `CouncilRoleOutputCodec` remains the strict,
 * fail-closed authority for accepting a role response.
 */
internal object CouncilRoleSchemas {
    private val staticSchemas: Map<Role, LLMParams.Schema.JSON.Standard> by lazy {
        mapOf(
            Role.CouncilTaskAnatomist to schema(TaskAnatomistOutput.serializer()),
            Role.CouncilIncentiveDesigner to schema(IncentiveDesignerOutput.serializer()),
            Role.CouncilEcologist to schema(EcologistOutput.serializer()),
            Role.CouncilPromptArchitect to schema(PromptArchitectOutput.serializer()),
            Role.CouncilKGArchitect to schema(KgArchitectOutput.serializer()),
            Role.CouncilModelEcologist to schema(ModelEcologistOutput.serializer()),
            Role.CouncilSeedCurator to schema(SeedCuratorOutput.serializer())
        )
    }

    /**
     * Returns the exact typed output schema owned by [role].
     */
    fun forRole(
        role: Role,
        synthesisAuthority: CouncilSynthesisWireAuthority? = null,
        acceptanceProfile: AcceptanceProfile = AcceptanceProfile.NORMAL
    ): LLMParams.Schema.JSON.Standard {
        require(role.isCouncilRole) { "Role $role is not a Council role" }
        require(
            (role == Role.CouncilSynthesis) == (synthesisAuthority != null)
        ) {
            if (role == Role.CouncilSynthesis) {
                "Council Synthesis schema requires Task-derived sealed-test authority"
            } else {
                "Only Council Synthesis may receive sealed-test schema authority"
            }
        }
        return when {
            synthesisAuthority != null -> contextualSynthesisSchema(synthesisAuthority)
            role == Role.CouncilTaskAnatomist &&
                acceptanceProfile != AcceptanceProfile.NORMAL ->
                contextualTaskAnatomistSchema(acceptanceProfile)
            else -> staticSchemas.getValue(role)
        }
    }

    private fun <T> schema(serializer: KSerializer<T>): LLMParams.Schema.JSON.Standard =
        schema(
            name = serializer.descriptor.serialName.substringAfterLast('.'),
            serializer = serializer
        )

    private fun <T> schema(
        name: String,
        serializer: KSerializer<T>
    ): LLMParams.Schema.JSON.Standard =
        CouncilStandardJsonSchemaGenerator.generate(
            json = schemaJson,
            name = name,
            serializer = serializer,
            descriptionOverrides = emptyMap()
        ).withoutGeneratorRootId(name)

    private fun contextualSynthesisSchema(
        authority: CouncilSynthesisWireAuthority
    ): LLMParams.Schema.JSON.Standard {
        val generated = schema(
            name = "SynthesisOutput",
            serializer = CouncilSynthesisContextualOutputSerializer(authority)
        )
        val contextual = generated.copy(
            schema = generated.schema.bindSynthesisAuthority(authority)
        )
        val schemaBytes = contextual.schema.toString().toByteArray(Charsets.UTF_8).size
        require(schemaBytes <= CODEX_OUTPUT_SCHEMA_MAX_BYTES) {
            "Contextual Council Synthesis schema exceeds " +
                "$CODEX_OUTPUT_SCHEMA_MAX_BYTES bytes for ${authority.entries.size} sealed tests"
        }
        return contextual
    }

    private fun contextualTaskAnatomistSchema(
        profile: AcceptanceProfile
    ): LLMParams.Schema.JSON.Standard {
        val serializer = TaskAnatomistContextualOutputSerializer(profile)
        val generated = schema(
            name = "TaskAnatomistOutput",
            serializer = serializer
        )
        val contextual = generated.copy(
            schema = generated.schema.bindTaskAnatomistProfile(serializer)
        )
        val schemaBytes = contextual.schema.toString().toByteArray(Charsets.UTF_8).size
        require(schemaBytes <= CODEX_OUTPUT_SCHEMA_MAX_BYTES) {
            "Contextual Council Task Anatomist schema exceeds " +
                "$CODEX_OUTPUT_SCHEMA_MAX_BYTES bytes for profile=${profile.id}"
        }
        return contextual
    }

    @OptIn(ExperimentalSerializationApi::class)
    private val schemaJson = Json {
        explicitNulls = true
        classDiscriminator = "type"
        classDiscriminatorMode = ClassDiscriminatorMode.POLYMORPHIC
    }
}

private fun JsonObject.bindTaskAnatomistProfile(
    serializer: TaskAnatomistContextualOutputSerializer
): JsonObject {
    val definitions = getValue("\$defs").jsonObject
    val contract = serializer.providerSchemaContract
    val outputFields = when (serializer.profile) {
        AcceptanceProfile.NORMAL -> error("Normal Task schema must remain static")
        AcceptanceProfile.RUNTIME_USABILITY_SMOKE -> TASK_ANATOMIST_SMOKE_FIELDS
        AcceptanceProfile.APPENDIX_B_FULL -> TASK_ANATOMIST_FULL_FIELDS
    }
    val rootOutput = requireProperties(outputFields, "root TaskAnatomistOutput")
    var outputDefinition = definitions.requireDefinition(
        contract.outputDefinitionSerialName
    ).requireProperties(outputFields, contract.outputDefinitionSerialName)
    val ordinaryFields = if (
        serializer.profile == AcceptanceProfile.RUNTIME_USABILITY_SMOKE
    ) {
        TASK_ANATOMIST_NODE_FIELDS
    } else {
        TASK_ANATOMIST_ORDINAL_NODE_FIELDS
    }
    var ordinaryDefinition = definitions.requireDefinition(
        contract.nodeWithoutToolDefinitionSerialName
    ).requireProperties(ordinaryFields, contract.nodeWithoutToolDefinitionSerialName)
    contract.nullOnlyToolOpportunityFieldName?.let { fieldName ->
        ordinaryDefinition = ordinaryDefinition.withProperty(
            fieldName,
            JsonObject(mapOf("type" to JsonPrimitive("null")))
        )
    }
    var updatedRoot = rootOutput
    if (serializer.profile == AcceptanceProfile.APPENDIX_B_FULL) {
        val typedDefinitionName = requireNotNull(
            contract.nodeWithToolDefinitionSerialName
        ) {
            "Full Task schema requires a typed opportunity-node definition"
        }
        definitions.requireDefinition(typedDefinitionName)
            .requireProperties(
                TASK_ANATOMIST_ORDINAL_NODE_FIELDS,
                typedDefinitionName
            )
        outputDefinition = outputDefinition.withMinimumItems(
            TASK_ANATOMIST_TYPED_NODES_FIELD,
            1
        )
        updatedRoot = updatedRoot.withMinimumItems(
            TASK_ANATOMIST_TYPED_NODES_FIELD,
            1
        )
    }
    val updatedDefinitions = JsonObject(
        definitions + mapOf(
            contract.outputDefinitionSerialName to outputDefinition,
            contract.nodeWithoutToolDefinitionSerialName to ordinaryDefinition
        )
    )
    return JsonObject(updatedRoot + ("\$defs" to updatedDefinitions))
        .pruneUnreachableDefinitions()
}

private fun JsonObject.bindSynthesisAuthority(
    authority: CouncilSynthesisWireAuthority
): JsonObject {
    val definitions = getValue("\$defs").jsonObject
    val contract = CouncilSynthesisContextualOutputSerializer.providerSchemaContract
    val branchNames = mapOf(
        CouncilSealedTestWireKind.PROMOTION to contract.promotionDefinitionSerialName,
        CouncilSealedTestWireKind.GENERAL to contract.generalDefinitionSerialName
    )
    val rootOutput = requireProperties(
        SYNTHESIS_FIELDS,
        "root ${contract.outputDefinitionSerialName}"
    )
    val outputDefinition = definitions.requireDefinition(
        contract.outputDefinitionSerialName
    ).requireProperties(SYNTHESIS_FIELDS, contract.outputDefinitionSerialName)
    val unionBinding = rootOutput.sealedTestUnion(definitions)
    val unionDefinition = unionBinding.schema.requireExactBranches(
        branchNames.values.toSet(),
        unionBinding.referenceName ?: "inline sealedTests.items"
    )
    val promotionDefinition = definitions.requireDefinition(
        contract.promotionDefinitionSerialName
    ).requireSealedTestBranch(
        CouncilSealedTestWireKind.PROMOTION,
        contract.promotionDefinitionSerialName
    )
    val generalDefinition = definitions.requireDefinition(
        contract.generalDefinitionSerialName
    ).requireSealedTestBranch(
        CouncilSealedTestWireKind.GENERAL,
        contract.generalDefinitionSerialName
    )
    val promotionBranches = authority.entries
        .filter { it.kind == CouncilSealedTestWireKind.PROMOTION }
        .groupBy { requireNotNull(it.promotionSchemaKey) }
        .toSortedMap()
        .map { (schemaKey, entries) ->
            promotionDefinition
                .withAuthorityEnum(entries.map { JsonPrimitive(it.authorityKey) })
                .withPromotionFixtureSchema(
                    schemaAuthority = authority.promotionSchemaFor(entries.first()),
                    definitions = definitions,
                    contract = contract
                )
        }
    val generalKeys = authority.entries
        .filter { it.kind == CouncilSealedTestWireKind.GENERAL }
        .map { JsonPrimitive(it.authorityKey) }
    val boundBranches = buildList {
        addAll(promotionBranches)
        if (generalKeys.isNotEmpty()) {
            add(generalDefinition.withAuthorityEnum(generalKeys))
        }
    }
    val boundUnionDefinition = if (boundBranches.isEmpty()) {
        unionDefinition
    } else {
        unionDefinition.withBranches(boundBranches)
    }
    val definitionUpdates = mutableMapOf<String, JsonElement>(
        contract.outputDefinitionSerialName to
            outputDefinition.withExactSealedTestContract(
                count = authority.entries.size,
                inlineItems = boundUnionDefinition.takeIf {
                    unionBinding.referenceName == null
                }
            )
    )
    unionBinding.referenceName?.let { referenceName ->
        definitionUpdates[referenceName] = boundUnionDefinition
    }
    val updatedDefinitions = JsonObject(
        definitions + definitionUpdates
    )
    val updatedRoot = rootOutput.withExactSealedTestContract(
        count = authority.entries.size,
        inlineItems = boundUnionDefinition.takeIf {
            unionBinding.referenceName == null
        }
    )
    return JsonObject(updatedRoot + ("\$defs" to updatedDefinitions))
        .pruneUnreachableDefinitions()
}

private data class GeneratedSealedTestUnion(
    val referenceName: String?,
    val schema: JsonObject
)

private fun JsonObject.sealedTestUnion(definitions: JsonObject): GeneratedSealedTestUnion {
    val items = getValue("properties")
        .jsonObject
        .getValue("sealedTests")
        .jsonObject
        .getValue("items")
        .jsonObject
    val referenceName = items["\$ref"]?.jsonPrimitive?.content?.substringAfterLast('/')
    return GeneratedSealedTestUnion(
        referenceName = referenceName,
        schema = referenceName?.let { definitions.requireDefinition(it) } ?: items
    )
}

private fun JsonObject.requireDefinition(serialName: String): JsonObject =
    (this[serialName] as? JsonObject)
        ?: error("Generated Council schema is missing definition '$serialName'")

private fun JsonObject.requireProperties(
    expected: Set<String>,
    serialName: String
): JsonObject {
    val actual = (this["properties"] as? JsonObject)?.keys
        ?: error("Generated Council schema definition '$serialName' has no properties")
    require(actual == expected) {
        "Generated Council schema definition '$serialName' changed properties: $actual"
    }
    return this
}

private fun JsonObject.requireSealedTestBranch(
    expectedKind: CouncilSealedTestWireKind,
    serialName: String
): JsonObject {
    requireProperties(SEALED_TEST_FIELDS, serialName)
    val actualKind = getValue("properties")
        .jsonObject
        .getValue("type")
        .jsonObject
        .getValue("enum")
        .jsonArray
        .single()
        .jsonPrimitive
        .content
    require(actualKind == expectedKind.name) {
        "Generated Council schema definition '$serialName' has discriminator '$actualKind'"
    }
    return this
}

private fun JsonObject.requireExactBranches(
    expectedDefinitionNames: Set<String>,
    unionIdentity: String
): JsonObject {
    val references = getValue("anyOf").jsonArray.map { branch ->
        branch.jsonObject.getValue("\$ref").jsonPrimitive.content.substringAfterLast('/')
    }
    require(
        references.size == expectedDefinitionNames.size &&
            references.toSet() == expectedDefinitionNames
    ) {
        "Generated Council sealed-test union '$unionIdentity' changed branches: $references"
    }
    return this
}

private fun JsonObject.withAuthorityEnum(values: List<JsonPrimitive>): JsonObject {
    require(values.isNotEmpty()) { "Council authority enum must not be empty" }
    val properties = getValue("properties").jsonObject
    val authoritySchema = JsonObject(
        properties.getValue("authorityKey").jsonObject +
            ("enum" to JsonArray(values))
    )
    return JsonObject(
        this + (
            "properties" to JsonObject(
                properties + ("authorityKey" to authoritySchema)
            )
        )
    )
}

private fun JsonObject.withBranches(branches: List<JsonObject>): JsonObject {
    require(branches.isNotEmpty()) {
        "Council Synthesis requires at least one sealed-test wire branch"
    }
    return JsonObject(this + ("anyOf" to JsonArray(branches)))
}

private fun JsonObject.withPromotionFixtureSchema(
    schemaAuthority: CouncilPromotionFixtureWireSchemaAuthority,
    definitions: JsonObject,
    contract: CouncilSynthesisProviderSchemaContract
): JsonObject {
    val properties = getValue("properties").jsonObject
    val fixtureSchema = properties.getValue("testDefinitionJson")
        .jsonObject
        .resolveReference(definitions)
        .requireProperties(
            expected = PROMOTION_TEST_DEFINITION_FIELDS,
            serialName = "promotion test definition"
        )
    val fixtureProperties = fixtureSchema.getValue("properties").jsonObject
    val compiler = TaggedToolFixtureProviderSchemaCompiler(definitions, contract)
    val exactFixtureSchema = JsonObject(
        fixtureSchema + (
            "properties" to JsonObject(
                fixtureProperties + mapOf(
                    "input" to compiler.compile(schemaAuthority.inputSchema),
                    "expectedOutput" to compiler.compile(schemaAuthority.outputSchema)
                )
            )
        )
    )
    return JsonObject(
        this + (
            "properties" to JsonObject(
                properties + ("testDefinitionJson" to exactFixtureSchema)
            )
        )
    )
}

private class TaggedToolFixtureProviderSchemaCompiler(
    private val definitions: JsonObject,
    private val contract: CouncilSynthesisProviderSchemaContract
) {
    fun compile(schema: JsonObject): JsonObject = when (schema.requiredType()) {
        "string" -> primitive(
            schema,
            contract.taggedStringDefinitionSerialName,
            "string"
        )
        "integer" -> primitive(
            schema,
            contract.taggedIntegerDefinitionSerialName,
            "integer"
        )
        "number" -> primitive(
            schema,
            contract.taggedNumberDefinitionSerialName,
            "number"
        )
        "boolean" -> primitive(
            schema,
            contract.taggedBooleanDefinitionSerialName,
            "boolean"
        )
        "array" -> array(schema)
        "object" -> objectValue(schema)
        else -> error("Unsupported Task-derived tool schema type '${schema.requiredType()}'")
    }

    private fun primitive(
        schema: JsonObject,
        definitionName: String,
        expectedType: String
    ): JsonObject {
        val template = definitions.requireDefinition(definitionName)
            .requireTaggedBranch(
                expectedType = expectedType,
                expectedFields = TAGGED_VALUE_FIELDS,
                serialName = definitionName
            )
        return template.withProperty(
            name = "value",
            value = schema.providerPrimitiveSchema()
        )
    }

    private fun array(schema: JsonObject): JsonObject {
        val definitionName = contract.taggedArrayDefinitionSerialName
        val template = definitions.requireDefinition(definitionName)
            .requireTaggedBranch(
                expectedType = "array",
                expectedFields = TAGGED_ARRAY_FIELDS,
                serialName = definitionName
            )
        val properties = template.getValue("properties").jsonObject
        val values = properties.getValue("values").jsonObject
        val itemSchema = schema["items"] as? JsonObject
            ?: error("Task-derived array schema is missing items")
        val boundValues = JsonObject(
            values + mapOf(
                "minItems" to schema.getValue("minItems"),
                "maxItems" to schema.getValue("maxItems"),
                "items" to compile(itemSchema)
            )
        )
        return template.withProperty("values", boundValues)
    }

    private fun objectValue(schema: JsonObject): JsonObject {
        require(schema["additionalProperties"]?.jsonPrimitive?.content == "false") {
            "Task-derived object schema must reject additional properties"
        }
        val definitionName = contract.taggedObjectDefinitionSerialName
        val template = definitions.requireDefinition(definitionName)
            .requireTaggedBranch(
                expectedType = "object",
                expectedFields = TAGGED_OBJECT_FIELDS,
                serialName = definitionName
            )
        val properties = schema["properties"] as? JsonObject
            ?: error("Task-derived object schema is missing properties")
        require(properties.isNotEmpty()) {
            "Task-derived object schema must contain at least one property"
        }
        val required = (schema["required"] as? JsonArray)
            ?.mapTo(linkedSetOf()) { it.jsonPrimitive.content }
            ?: error("Task-derived object schema is missing required")
        require(required.all(properties::containsKey)) {
            "Task-derived object schema requires an unknown property"
        }
        val entryProperties = JsonObject(
            properties.entries.sortedBy { it.key }.associate { (name, definition) ->
                val valueSchema = compile(definition.jsonObject)
                name to if (name in required) {
                    valueSchema
                } else {
                    optionalProperty(valueSchema)
                }
            }
        )
        val boundEntries = strictObject(entryProperties)
        return template.withProperty("entries", boundEntries)
    }

    private fun optionalProperty(valueSchema: JsonObject): JsonObject =
        JsonObject(
            mapOf(
                "anyOf" to JsonArray(
                    listOf(
                        strictObject(
                            JsonObject(
                                mapOf(
                                    "state" to enumString(OPTIONAL_STATE_ABSENT)
                                )
                            )
                        ),
                        strictObject(
                            JsonObject(
                                mapOf(
                                    "state" to enumString(OPTIONAL_STATE_PRESENT),
                                    "value" to valueSchema
                                )
                            )
                        )
                    )
                )
            )
        )

    private fun strictObject(properties: JsonObject): JsonObject =
        JsonObject(
            mapOf(
                "type" to JsonPrimitive("object"),
                "properties" to properties,
                "required" to JsonArray(properties.keys.map(::JsonPrimitive)),
                "additionalProperties" to JsonPrimitive(false)
            )
        )

    private fun enumString(value: String): JsonObject =
        JsonObject(
            mapOf(
                "type" to JsonPrimitive("string"),
                "enum" to JsonArray(listOf(JsonPrimitive(value)))
            )
        )

    private fun JsonObject.providerPrimitiveSchema(): JsonObject {
        val permittedFields = setOf("type", "description", "enum")
        return JsonObject(filterKeys(permittedFields::contains))
    }
}

private fun JsonObject.requiredType(): String =
    (getValue("type") as? JsonPrimitive)
        ?.content
        ?: error("Task-derived tool schema type must be a primitive")

private fun JsonObject.requireTaggedBranch(
    expectedType: String,
    expectedFields: Set<String>,
    serialName: String
): JsonObject {
    requireProperties(expectedFields, serialName)
    val actualType = getValue("properties")
        .jsonObject
        .getValue("type")
        .jsonObject
        .getValue("enum")
        .jsonArray
        .single()
        .jsonPrimitive
        .content
    require(actualType == expectedType) {
        "Generated tagged JSON definition '$serialName' has discriminator '$actualType'"
    }
    return this
}

private fun JsonObject.withProperty(name: String, value: JsonObject): JsonObject {
    val properties = getValue("properties").jsonObject
    require(name in properties) { "Generated Council schema is missing property '$name'" }
    return JsonObject(
        this + ("properties" to JsonObject(properties + (name to value)))
    )
}

private fun JsonObject.resolveReference(definitions: JsonObject): JsonObject {
    val reference = this["\$ref"]?.jsonPrimitive?.content ?: return this
    require(keys == setOf("\$ref")) {
        "Generated Council schema reference must not have siblings"
    }
    return definitions.requireDefinition(reference.substringAfterLast('/'))
}

private fun JsonObject.withExactSealedTestContract(
    count: Int,
    inlineItems: JsonObject?
): JsonObject {
    val properties = getValue("properties").jsonObject
    val existingSealedTests = properties.getValue("sealedTests").jsonObject
    val sealedTests = JsonObject(
        existingSealedTests +
            mapOf(
                "minItems" to JsonPrimitive(count),
                "maxItems" to JsonPrimitive(count)
            ) +
            listOfNotNull(inlineItems?.let { "items" to it }).toMap()
    )
    return JsonObject(
        this + (
            "properties" to JsonObject(
                properties + ("sealedTests" to sealedTests)
            )
        )
    )
}

private fun JsonObject.withMinimumItems(
    propertyName: String,
    minimumItems: Int
): JsonObject {
    require(minimumItems > 0) { "Array minimum must be positive" }
    val properties = getValue("properties").jsonObject
    val existing = properties.getValue(propertyName).jsonObject
    require(existing["type"]?.jsonPrimitive?.content == "array") {
        "Generated Council schema property '$propertyName' must be an array"
    }
    val bounded = JsonObject(
        existing + ("minItems" to JsonPrimitive(minimumItems))
    )
    return JsonObject(
        this + (
            "properties" to JsonObject(
                properties + (propertyName to bounded)
            )
        )
    )
}

/**
 * Koog's OpenAI generator with Kotlin inline-value encoding and explicit rejection of raw maps.
 *
 * Provider-facing maps use serializer-owned deterministic entry arrays, so any newly introduced
 * raw map fails schema generation instead of reaching a live provider.
 */
private object CouncilStandardJsonSchemaGenerator : OpenAIStandardJsonSchemaGenerator() {
    @OptIn(ExperimentalSerializationApi::class)
    override fun process(context: GenerationContext): JsonObject {
        if (!context.descriptor.isInline) return super.process(context)
        require(context.descriptor.elementsCount == 1) {
            "Inline Council contract ${context.descriptor.serialName} must have one value"
        }
        return process(
            context.copy(descriptor = context.descriptor.getElementDescriptor(0))
        )
    }

    override fun processMap(context: GenerationContext): JsonObject =
        error(
            "Council structured-output map ${context.descriptor.serialName} must use " +
                "a deterministic entry-list serializer"
        )
}

private val SYNTHESIS_FIELDS = setOf(
    "atlasVersion",
    "parentAtlasHash",
    "stages",
    "sealedTests",
    "workerFieldNotes",
    "graderFieldNotes",
    "rubricSchemaVersion",
    "declaredChange"
)

private val TASK_ANATOMIST_SMOKE_FIELDS = setOf(
    "receiverContract",
    "receiverObligations",
    "nodes",
    "rootNodeKeys",
    "atomicityReport",
    "ambiguityBranches"
)

private val TASK_ANATOMIST_FULL_FIELDS = setOf(
    "receiverContract",
    "receiverObligations",
    "nodesWithoutToolOpportunity",
    "nodesWithToolOpportunity",
    "rootNodeKeys",
    "atomicityReport",
    "ambiguityBranches"
)

private val TASK_ANATOMIST_NODE_FIELDS = setOf(
    "key",
    "kind",
    "layer",
    "parentKeys",
    "prerequisiteKeys",
    "alternativeToKeys",
    "receiverObligationKey",
    "limitationStatement",
    "capabilityTarget",
    "atomicQuestion",
    "estimatedEffort",
    "proofContract",
    "deliverableContract",
    "stageKey",
    "revealMode",
    "replicationPolicy",
    "capturePolicy",
    "rewardSpec",
    "toolOpportunity"
)

private val TASK_ANATOMIST_ORDINAL_NODE_FIELDS =
    TASK_ANATOMIST_NODE_FIELDS + "ordinal"

private const val TASK_ANATOMIST_TYPED_NODES_FIELD = "nodesWithToolOpportunity"

private val SEALED_TEST_FIELDS = setOf(
    "type",
    "authorityKey",
    "acceptedArtifactTypes",
    "testDefinitionJson",
    "commitmentHash"
)

private val PROMOTION_TEST_DEFINITION_FIELDS = setOf("input", "expectedOutput")
private val TAGGED_VALUE_FIELDS = setOf("type", "value")
private val TAGGED_ARRAY_FIELDS = setOf("type", "values")
private val TAGGED_OBJECT_FIELDS = setOf("type", "entries")
private const val OPTIONAL_STATE_ABSENT = "ABSENT"
private const val OPTIONAL_STATE_PRESENT = "PRESENT"
