package com.achulkov.evomolior.agents.factory

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.utils.time.KoogClock
import com.achulkov.evomolior.core.atlas.ToolBehavior
import com.achulkov.evomolior.core.atlas.ToolImplementationMode
import com.achulkov.evomolior.core.atlas.ToolOpportunityType
import com.achulkov.evomolior.core.atlas.TypedField
import com.achulkov.evomolior.core.atlas.TypedFieldType
import com.achulkov.evomolior.core.atlas.TypedToolSchemaCompiler
import com.achulkov.evomolior.core.council.CouncilPromotionFixtureWireSchemaAuthority
import com.achulkov.evomolior.core.council.CouncilRoleExecutionRequest
import com.achulkov.evomolior.core.council.CouncilSealedTestWireAuthority
import com.achulkov.evomolior.core.council.CouncilSealedTestWireKind
import com.achulkov.evomolior.core.council.CouncilSynthesisWireAuthority
import com.achulkov.evomolior.core.council.LocalNodeKey
import com.achulkov.evomolior.core.config.AcceptanceProfile
import com.achulkov.evomolior.core.model.Role
import com.achulkov.evomolior.core.repair.AggregationSemantics
import com.achulkov.evomolior.core.repair.TimeBasis
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CouncilRoleSchemasTest {
    @Test
    fun `every Council role has its exact typed output schema`() {
        val expectedNames = mapOf(
            Role.CouncilTaskAnatomist to "TaskAnatomistOutput",
            Role.CouncilIncentiveDesigner to "IncentiveDesignerOutput",
            Role.CouncilEcologist to "EcologistOutput",
            Role.CouncilPromptArchitect to "PromptArchitectOutput",
            Role.CouncilKGArchitect to "KgArchitectOutput",
            Role.CouncilModelEcologist to "ModelEcologistOutput",
            Role.CouncilSeedCurator to "SeedCuratorOutput",
            Role.CouncilSynthesis to "SynthesisOutput"
        )

        val schemas = expectedNames.mapValues { (role, expectedName) ->
            val schema = if (role == Role.CouncilSynthesis) {
                CouncilRoleSchemas.forRole(role, synthesisAuthority())
            } else {
                CouncilRoleSchemas.forRole(role)
            }
            schema.also {
                assertEquals(expectedName, schema.name)
                assertEquals(
                    "object",
                    schema.schema.getValue("type").jsonPrimitive.content
                )
                assertFalse("\$ref" in schema.schema)
                assertFalse("\$id" in schema.schema)
                assertProviderStrict(schema.schema)
                assertTrue(
                    schema.schema.toString().toByteArray(Charsets.UTF_8).size <=
                        CODEX_OUTPUT_SCHEMA_MAX_BYTES,
                    "$role schema exceeds the Codex invocation bound"
                )
            }
        }

        assertEquals(expectedNames.size, schemas.values.map { it.schema }.distinct().size)
    }

    @Test
    fun `task schema follows inline map and recursive DSL serialization`() {
        val schema = CouncilRoleSchemas.forRole(Role.CouncilTaskAnatomist).schema
        val definitions = schema.getValue("\$defs").jsonObject
        val taskDefinition = definitions.getValue(
            "com.achulkov.evomolior.core.council.TaskAnatomistOutput"
        ).jsonObject
        val taskProperties = taskDefinition.getValue("properties").jsonObject

        assertEquals(
            "array",
            taskProperties.getValue("receiverObligations")
                .jsonObject
                .getValue("type")
                .jsonPrimitive
                .content
        )
        assertTrue(
            taskProperties.getValue("receiverObligations")
                .jsonObject
                .getValue("items") is JsonObject
        )
        assertFalse("unexpected" in taskProperties)
        assertTrue(
            taskDefinition.getValue("required")
                .jsonArray
                .any { it.jsonPrimitive.content == "receiverContract" }
        )
        assertEquals(
            "false",
            taskDefinition.getValue("additionalProperties").jsonPrimitive.content
        )
        val evidenceDefinition = definitions.getValue(
            "com.achulkov.evomolior.core.atlas.EvidenceProfile"
        ).jsonObject
        assertEquals(
            evidenceDefinition.getValue("properties").jsonObject.keys,
            evidenceDefinition.getValue("required")
                .jsonArray
                .mapTo(linkedSetOf()) { it.jsonPrimitive.content }
        )
        assertTrue(
            evidenceDefinition.getValue("required")
                .jsonArray
                .any { it.jsonPrimitive.content == "minimumIndependentSources" }
        )

        val localNodeDefinition = definitions.getValue(
            "com.achulkov.evomolior.core.council.LocalLimitationNode"
        ).jsonObject
        assertEquals(
            "string",
            localNodeDefinition.getValue("properties")
                .jsonObject
                .getValue("key")
                .jsonObject
                .getValue("type")
                .jsonPrimitive
                .content
        )
        val physicalDimensionKeys = definitions.keys.filter { key ->
            key.removeSuffix("?") ==
                "com.achulkov.evomolior.core.atlas.PhysicalDimension"
        }
        assertTrue(physicalDimensionKeys.isNotEmpty())
        physicalDimensionKeys.forEach { physicalDimensionKey ->
            val physicalDimensionDefinition =
                definitions.getValue(physicalDimensionKey).jsonObject
            assertEquals(
                "array",
                physicalDimensionDefinition.getValue("properties")
                    .jsonObject
                    .getValue("exponents")
                    .jsonObject
                    .getValue("type")
                    .jsonPrimitive
                    .content
            )
        }

        val rendered = schema.toString()
        assertFalse(rendered.contains("\"oneOf\""))
        assertFalse(rendered.contains("\"const\""))
        assertTrue(rendered.contains("\"anyOf\""))
        assertTrue(rendered.contains("\"enum\":[\"binary\"]"))
        assertTrue(rendered.contains("\"enum\":[\"input\"]"))
        assertTrue(rendered.contains("\"enum\":[\"integer\"]"))

        val typedFieldBranches = definitions.values
            .map(JsonElement::jsonObject)
            .mapNotNull { definition ->
                val properties = definition["properties"] as? JsonObject ?: return@mapNotNull null
                if (
                    !setOf("name", "description", "required", "validationRules")
                        .all(properties::containsKey)
                ) {
                    return@mapNotNull null
                }
                val discriminator = properties["type"]
                    ?.jsonObject
                    ?.get("enum")
                    ?.jsonArray
                    ?.singleOrNull()
                    ?.jsonPrimitive
                    ?.content
                    ?: return@mapNotNull null
                discriminator.takeIf { it in TypedFieldType.entries.map(TypedFieldType::name) }
                    ?.let { it to definition }
            }
            .toMap()
        assertEquals(
            TypedFieldType.entries
                .filterNot { it == TypedFieldType.JSON }
                .mapTo(linkedSetOf(), TypedFieldType::name),
            typedFieldBranches.keys
        )

        typedFieldBranches.forEach { (type, definition) ->
            val properties = definition.getValue("properties").jsonObject
            assertEquals(type == TypedFieldType.STRING.name, "enumValues" in properties)
            assertEquals(
                type in setOf(
                    TypedFieldType.INTEGER.name,
                    TypedFieldType.NUMBER.name
                ),
                "physicalDimension" in properties
            )
            assertEquals(type == TypedFieldType.OBJECT.name, "objectFields" in properties)
            assertEquals(type == TypedFieldType.ARRAY.name, "minimumItems" in properties)
            assertEquals(type == TypedFieldType.ARRAY.name, "maximumItems" in properties)
            assertEquals(type == TypedFieldType.ARRAY.name, "items" in properties)
        }
        val arrayItemsSchema = typedFieldBranches
            .getValue(TypedFieldType.ARRAY.name)
            .getValue("properties")
            .jsonObject
            .getValue("items")
            .jsonObject
            .resolveReference(definitions)
        val arrayItemBranches = arrayItemsSchema.getValue("anyOf").jsonArray
            .map { it.jsonObject.resolveReference(definitions) }
            .associateBy { definition ->
                definition.getValue("properties")
                    .jsonObject
                    .getValue("type")
                    .jsonObject
                    .getValue("enum")
                    .jsonArray
                    .single()
                    .jsonPrimitive
                    .content
            }
        assertEquals(
            setOf(
                "STRING_ITEMS",
                "INTEGER_ITEMS",
                "NUMBER_ITEMS",
                "BOOLEAN_ITEMS",
                "OBJECT_ITEMS"
            ),
            arrayItemBranches.keys
        )
        arrayItemBranches.forEach { (type, definition) ->
            val properties = definition.getValue("properties").jsonObject
            assertEquals(
                type in setOf("INTEGER_ITEMS", "NUMBER_ITEMS"),
                "physicalDimension" in properties
            )
            assertEquals(type == "OBJECT_ITEMS", "objectFields" in properties)
        }
        assertQuantitySemanticsBound(schema)

        val structuredOpportunityDefinition = definitions.values
            .map(JsonElement::jsonObject)
            .single { definition ->
                val properties = definition["properties"] as? JsonObject
                    ?: return@single false
                setOf(
                    "opportunityId",
                    "implementation",
                    "execution",
                    "expectedReuseNodeIds"
                ).all(properties::containsKey)
            }
        val structuredOpportunityProperties =
            structuredOpportunityDefinition.getValue("properties").jsonObject
        assertTrue(
            setOf(
                "opportunityType",
                "preferredImplementationMode",
                "behavior",
                "simulationRecordBindings",
                "formulaDslReferenceSemantics",
                "formulaDslReferenceProgram"
            ).none(structuredOpportunityProperties::containsKey)
        )

        val implementationBranches = definitions.discriminatorBranches(
            discriminator = "type",
            expectedValues = ToolImplementationMode.entries.mapTo(linkedSetOf()) { it.name }
        )
        implementationBranches.forEach { (mode, definition) ->
            val properties = definition.getValue("properties").jsonObject
            val isFormula = mode == ToolImplementationMode.FORMULA_DSL.name
            assertEquals(isFormula, "formulaDslReferenceSemantics" in properties)
            assertEquals(isFormula, "formulaDslReferenceProgram" in properties)
        }
        val formulaImplementation = implementationBranches
            .getValue(ToolImplementationMode.FORMULA_DSL.name)
            .getValue("properties")
            .jsonObject
        val formulaProgram = formulaImplementation
            .getValue("formulaDslReferenceProgram")
            .jsonObject
            .resolveReference(definitions)
        val formulaKind = formulaProgram
            .getValue("properties")
            .jsonObject
            .getValue("kind")
            .jsonObject
            .resolveReference(definitions)
        assertEquals(
            setOf("FORMULA"),
            formulaKind.getValue("enum").jsonArray.mapTo(linkedSetOf()) {
                it.jsonPrimitive.content
            }
        )

        val executionBranches = definitions.discriminatorBranches(
            discriminator = "type",
            expectedValues = ToolOpportunityType.entries.mapTo(linkedSetOf()) { it.name }
        )
        executionBranches.forEach { (type, definition) ->
            val properties = definition.getValue("properties").jsonObject
            val isSimulation = type == ToolOpportunityType.SIMULATION.name
            assertEquals(!isSimulation, "behavior" in properties)
            assertEquals(isSimulation, "simulationConfiguration" in properties)
        }

        val simulationBranches = definitions.discriminatorBranches(
            discriminator = "type",
            expectedValues = ToolBehavior.entries.mapTo(linkedSetOf()) { it.name }
        )
        simulationBranches.forEach { (behavior, definition) ->
            val properties = definition.getValue("properties").jsonObject
            assertEquals(
                behavior == ToolBehavior.STOCHASTIC.name,
                "randomSeedInputField" in properties
            )
            assertTrue("resultSummaryOutputField" in properties)
            assertTrue("uncertaintyOutputField" in properties)
        }
    }

    @Test
    fun `appendix Task schema binds aggregation to exact quantity time basis`() {
        assertQuantitySemanticsBound(
            CouncilRoleSchemas.forRole(
                Role.CouncilTaskAnatomist,
                acceptanceProfile = AcceptanceProfile.APPENDIX_B_FULL
            ).schema
        )
    }

    @Test
    fun `Synthesis schema cannot be built without Task-derived authority`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            CouncilRoleSchemas.forRole(Role.CouncilSynthesis)
        }

        assertTrue(failure.message.orEmpty().contains("Task-derived sealed-test authority"))
    }

    @Test
    fun `contextual synthesis schema binds promotion identity to object-only fixtures`() {
        val authority = synthesisAuthority()
        val schema = CouncilRoleSchemas.forRole(Role.CouncilSynthesis, authority).schema
        assertProviderStrict(schema)
        val definitions = schema.getValue("\$defs").jsonObject
        val rootBranches = schema.getValue("properties")
            .jsonObject
            .getValue("sealedTests")
            .jsonObject
            .getValue("items")
            .jsonObject
            .resolveReference(definitions)
            .getValue("anyOf")
            .jsonArray
        assertTrue(
            rootBranches.any { branch ->
                branch.jsonObject
                    .getValue("properties")
                    .jsonObject
                    .getValue("authorityKey")
                    .jsonObject
                    .getValue("enum")
                    .jsonArray
                    .any { it.jsonPrimitive.content == "promotion-authority" }
            }
        )
        val synthesisDefinition = schema
        val sealedTests = synthesisDefinition.getValue("properties")
            .jsonObject
            .getValue("sealedTests")
            .jsonObject
        assertEquals("2", sealedTests.getValue("minItems").jsonPrimitive.content)
        assertEquals("2", sealedTests.getValue("maxItems").jsonPrimitive.content)
        val testUnion = sealedTests.getValue("items")
            .jsonObject
            .resolveReference(definitions)
        val branches = testUnion.getValue("anyOf").jsonArray
            .map { it.jsonObject.resolveReference(definitions) }
            .associateBy { branch ->
                branch.getValue("properties")
                    .jsonObject
                    .getValue("type")
                    .jsonObject
                    .getValue("enum")
                    .jsonArray
                    .single()
                    .jsonPrimitive
                    .content
            }
        assertEquals(setOf("PROMOTION", "GENERAL"), branches.keys)

        val promotion = branches.getValue("PROMOTION").getValue("properties").jsonObject
        val general = branches.getValue("GENERAL").getValue("properties").jsonObject
        assertEquals(
            listOf("promotion-authority"),
            promotion.getValue("authorityKey")
                .jsonObject
                .getValue("enum")
                .jsonArray
                .map { it.jsonPrimitive.content }
        )
        assertEquals(
            listOf("general-authority"),
            general.getValue("authorityKey")
                .jsonObject
                .getValue("enum")
                .jsonArray
                .map { it.jsonPrimitive.content }
        )

        val promotionDefinition = promotion.getValue("testDefinitionJson")
            .jsonObject
            .resolveReference(definitions)
        assertEquals(
            setOf("input", "expectedOutput"),
            promotionDefinition.getValue("properties").jsonObject.keys
        )
        val promotionInput = promotionDefinition.getValue("properties")
            .jsonObject
            .getValue("input")
            .jsonObject
            .resolveReference(definitions)
        assertEquals("object", promotionInput.taggedType())
        assertFalse("anyOf" in promotionInput)
        val inputEntries = promotionInput.getValue("properties")
            .jsonObject
            .getValue("entries")
            .jsonObject
        assertEquals(
            setOf("value"),
            inputEntries.getValue("properties").jsonObject.keys
        )
        assertEquals(
            "integer",
            inputEntries.getValue("properties")
                .jsonObject
                .getValue("value")
                .jsonObject
                .taggedType()
        )
        val promotionOutput = promotionDefinition.getValue("properties")
            .jsonObject
            .getValue("expectedOutput")
            .jsonObject
            .resolveReference(definitions)
        assertEquals("object", promotionOutput.taggedType())
        val outputEntry = promotionOutput.getValue("properties")
            .jsonObject
            .getValue("entries")
            .jsonObject
        assertEquals(
            "boolean",
            outputEntry.getValue("properties")
                .jsonObject
                .getValue("ok")
                .jsonObject
                .taggedType()
        )

        val generalDefinition = general.getValue("testDefinitionJson")
            .jsonObject
            .resolveReference(definitions)
        assertEquals(
            setOf("target", "assertion"),
            generalDefinition.getValue("properties").jsonObject.keys
        )
        val generalProperties = generalDefinition.getValue("properties").jsonObject
        val targetBranches = generalProperties.getValue("target")
            .jsonObject
            .resolveReference(definitions)
            .getValue("anyOf")
            .jsonArray
            .taggedBranchTypes(definitions)
        assertEquals(
            setOf("FINAL_OUTPUT_TEXT", "OUTPUT_TEXT_FILE", "OUTPUT_JSON_FILE"),
            targetBranches
        )
        val assertionBranches = generalProperties.getValue("assertion")
            .jsonObject
            .resolveReference(definitions)
            .getValue("anyOf")
            .jsonArray
            .taggedBranchTypes(definitions)
        assertEquals(
            setOf(
                "TEXT_CONTAINS",
                "TEXT_MATCHES_RE2",
                "JSON_POINTER_EXISTS",
                "JSON_POINTER_EQUALS_STRING",
                "JSON_POINTER_NUMBER_RANGE"
            ),
            assertionBranches
        )
        assertFalse(generalDefinition.toString().contains("\"entries\""))
        assertTrue(
            promotion.getValue("authorityKey").toString()
                .contains("promotion-authority")
        )
        assertFalse(
            general.getValue("authorityKey").toString()
                .contains("promotion-authority")
        )
    }

    @Test
    fun `generated Synthesis schema independently enforces typed GENERAL definitions`() {
        val authority = CouncilSynthesisWireAuthority(
            entries = listOf(
                CouncilSealedTestWireAuthority(
                    authorityKey = "general-authority",
                    testKey = "general-test",
                    nodeKey = LocalNodeKey("general-node"),
                    kind = CouncilSealedTestWireKind.GENERAL
                )
            ),
            promotionSchemas = emptyList()
        )
        val document = CodexOutputSchemaDocument.from(
            CouncilRoleSchemas.forRole(Role.CouncilSynthesis, authority)
        )
        val validDefinitions = listOf(
            buildJsonObject {
                put("target", buildJsonObject { put("type", "FINAL_OUTPUT_TEXT") })
                put(
                    "assertion",
                    buildJsonObject {
                        put("type", "TEXT_CONTAINS")
                        put("expected", "evidence")
                        put("caseSensitive", false)
                    }
                )
            },
            buildJsonObject {
                put(
                    "target",
                    buildJsonObject {
                        put("type", "OUTPUT_TEXT_FILE")
                        put("relativePath", "result.md")
                    }
                )
                put(
                    "assertion",
                    buildJsonObject {
                        put("type", "TEXT_MATCHES_RE2")
                        put("pattern", "evidence\\s+table")
                    }
                )
            },
            buildJsonObject {
                put(
                    "target",
                    buildJsonObject {
                        put("type", "OUTPUT_JSON_FILE")
                        put("relativePath", "result.json")
                    }
                )
                put(
                    "assertion",
                    buildJsonObject {
                        put("type", "JSON_POINTER_EXISTS")
                        put("pointer", "/status")
                    }
                )
            },
            buildJsonObject {
                put(
                    "target",
                    buildJsonObject {
                        put("type", "OUTPUT_JSON_FILE")
                        put("relativePath", "result.json")
                    }
                )
                put(
                    "assertion",
                    buildJsonObject {
                        put("type", "JSON_POINTER_EQUALS_STRING")
                        put("pointer", "/status")
                        put("expected", "OK")
                    }
                )
            },
            buildJsonObject {
                put(
                    "target",
                    buildJsonObject {
                        put("type", "OUTPUT_JSON_FILE")
                        put("relativePath", "result.json")
                    }
                )
                put(
                    "assertion",
                    buildJsonObject {
                        put("type", "JSON_POINTER_NUMBER_RANGE")
                        put("pointer", "/score")
                        put("minimumInclusive", 0.0)
                        put("maximumInclusive", 1.0)
                    }
                )
            }
        )
        validDefinitions.forEach { definition ->
            assertEquals(
                emptyList(),
                CodexStructuredOutputValidator.validate(
                    document,
                    generalSynthesisProviderResponse(definition)
                        .toString()
                        .toByteArray(Charsets.UTF_8)
                )
            )
        }

        val invalidDefinitions = listOf<JsonElement>(
            JsonNull,
            JsonPrimitive("narrative"),
            JsonArray(emptyList()),
            buildJsonObject {},
            buildJsonObject { put("type", "null") },
            buildJsonObject {
                put("target", buildJsonObject { put("type", "FINAL_OUTPUT_TEXT") })
                put("assertion", buildJsonObject { put("type", "UNKNOWN") })
            },
            JsonObject(validDefinitions.first().jsonObject + ("extra" to JsonPrimitive(true)))
        )
        invalidDefinitions.forEach { definition ->
            assertTrue(
                CodexStructuredOutputValidator.validate(
                    document,
                    generalSynthesisProviderResponse(definition)
                        .toString()
                        .toByteArray(Charsets.UTF_8)
                ).isNotEmpty(),
                "Generated schema accepted invalid GENERAL definition: $definition"
            )
        }
    }

    @Test
    fun `contextual synthesis schema keeps promotion authority keys in exact schema groups`() {
        val integerSchema = promotionFixtureSchema()
        val stringSchema = promotionFixtureSchema(
            inputType = TypedFieldType.STRING,
            outputType = TypedFieldType.NUMBER
        )
        val authority = CouncilSynthesisWireAuthority(
            entries = listOf(
                CouncilSealedTestWireAuthority(
                    authorityKey = "promotion-integer",
                    testKey = "promotion-integer-test",
                    nodeKey = LocalNodeKey("promotion-integer-node"),
                    kind = CouncilSealedTestWireKind.PROMOTION,
                    promotionSchemaKey = integerSchema.schemaKey
                ),
                CouncilSealedTestWireAuthority(
                    authorityKey = "promotion-integer-peer",
                    testKey = "promotion-integer-peer-test",
                    nodeKey = LocalNodeKey("promotion-integer-peer-node"),
                    kind = CouncilSealedTestWireKind.PROMOTION,
                    promotionSchemaKey = integerSchema.schemaKey
                ),
                CouncilSealedTestWireAuthority(
                    authorityKey = "promotion-string",
                    testKey = "promotion-string-test",
                    nodeKey = LocalNodeKey("promotion-string-node"),
                    kind = CouncilSealedTestWireKind.PROMOTION,
                    promotionSchemaKey = stringSchema.schemaKey
                )
            ),
            promotionSchemas = listOf(integerSchema, stringSchema)
                .sortedBy(CouncilPromotionFixtureWireSchemaAuthority::schemaKey)
        )

        val schema = CouncilRoleSchemas.forRole(Role.CouncilSynthesis, authority).schema
        assertProviderStrict(schema)
        val definitions = schema.getValue("\$defs").jsonObject
        val synthesisDefinition = schema
        val branches = synthesisDefinition.getValue("properties")
            .jsonObject
            .getValue("sealedTests")
            .jsonObject
            .getValue("items")
            .jsonObject
            .resolveReference(definitions)
            .getValue("anyOf")
            .jsonArray
            .map(JsonElement::jsonObject)
        val byAuthorities = branches.associateBy { branch ->
            branch.getValue("properties")
                .jsonObject
                .getValue("authorityKey")
                .jsonObject
                .getValue("enum")
                .jsonArray
                .mapTo(linkedSetOf()) { it.jsonPrimitive.content }
        }
        assertEquals(2, byAuthorities.size)
        assertEquals(
            setOf("promotion-integer", "promotion-integer-peer"),
            byAuthorities.keys.single { "promotion-integer" in it }
        )
        assertEquals(
            setOf("promotion-string"),
            byAuthorities.keys.single { "promotion-string" in it }
        )

        fun inputValueType(authorityKey: String): String {
            val fixture = byAuthorities.entries
                .single { authorityKey in it.key }
                .value
                .getValue("properties")
                .jsonObject
                .getValue("testDefinitionJson")
                .jsonObject
            val entry = fixture.getValue("properties")
                .jsonObject
                .getValue("input")
                .jsonObject
                .getValue("properties")
                .jsonObject
                .getValue("entries")
                .jsonObject
            return entry.getValue("properties")
                .jsonObject
                .getValue("value")
                .jsonObject
                .taggedType()
        }

        assertEquals("integer", inputValueType("promotion-integer"))
        assertEquals("integer", inputValueType("promotion-integer-peer"))
        assertEquals("string", inputValueType("promotion-string"))
    }

    @Test
    fun `contextual synthesis schema binds nested arrays optional properties and string enums`() {
        val promotionSchema = realisticPromotionFixtureSchema()
        val authority = CouncilSynthesisWireAuthority(
            entries = listOf(
                CouncilSealedTestWireAuthority(
                    authorityKey = "nested-promotion",
                    testKey = "nested-promotion-test",
                    nodeKey = LocalNodeKey("nested-promotion-node"),
                    kind = CouncilSealedTestWireKind.PROMOTION,
                    promotionSchemaKey = promotionSchema.schemaKey
                )
            ),
            promotionSchemas = listOf(promotionSchema)
        )

        val schema = CouncilRoleSchemas.forRole(Role.CouncilSynthesis, authority).schema
        assertProviderStrict(schema)
        val definitions = schema.getValue("\$defs").jsonObject
        val synthesisDefinition = schema
        val promotion = synthesisDefinition.getValue("properties")
            .jsonObject
            .getValue("sealedTests")
            .jsonObject
            .getValue("items")
            .jsonObject
            .resolveReference(definitions)
            .getValue("anyOf")
            .jsonArray
            .single()
            .jsonObject
        val input = promotion.getValue("properties")
            .jsonObject
            .getValue("testDefinitionJson")
            .jsonObject
            .getValue("properties")
            .jsonObject
            .getValue("input")
            .jsonObject
        val inputEntries = input.getValue("properties")
            .jsonObject
            .getValue("entries")
            .jsonObject
            .getValue("properties")
            .jsonObject
        assertEquals(setOf("note", "scenario"), inputEntries.keys)

        val optionalNote = inputEntries.getValue("note").jsonObject
        val optionalBranches = optionalNote.getValue("anyOf").jsonArray
            .map(JsonElement::jsonObject)
        assertEquals(
            setOf("ABSENT", "PRESENT"),
            optionalBranches.mapTo(linkedSetOf()) { branch ->
                branch.getValue("properties")
                    .jsonObject
                    .getValue("state")
                    .jsonObject
                    .getValue("enum")
                    .jsonArray
                    .single()
                    .jsonPrimitive
                    .content
            }
        )
        val presentNote = optionalBranches.single { branch ->
            "value" in branch.getValue("properties").jsonObject
        }.getValue("properties").jsonObject.getValue("value").jsonObject
        assertEquals("string", presentNote.taggedType())
        assertEquals(
            listOf("alpha", "beta"),
            presentNote.getValue("properties")
                .jsonObject
                .getValue("value")
                .jsonObject
                .getValue("enum")
                .jsonArray
                .map { it.jsonPrimitive.content }
        )

        val scenarioEntries = inputEntries.getValue("scenario")
            .jsonObject
            .getValue("properties")
            .jsonObject
            .getValue("entries")
            .jsonObject
            .getValue("properties")
            .jsonObject
        assertEquals(setOf("mode", "samples"), scenarioEntries.keys)
        val modeValue = scenarioEntries.getValue("mode")
            .jsonObject
            .getValue("properties")
            .jsonObject
            .getValue("value")
            .jsonObject
        assertEquals(
            listOf("FAST", "SAFE"),
            modeValue.getValue("enum").jsonArray.map { it.jsonPrimitive.content }
        )
        val sampleItemEntries = scenarioEntries.getValue("samples")
            .jsonObject
            .getValue("properties")
            .jsonObject
            .getValue("values")
            .jsonObject
            .getValue("items")
            .jsonObject
            .getValue("properties")
            .jsonObject
            .getValue("entries")
            .jsonObject
            .getValue("properties")
            .jsonObject
        assertEquals(setOf("label", "reading"), sampleItemEntries.keys)
        assertTrue("anyOf" in sampleItemEntries.getValue("label").jsonObject)
        assertEquals(
            "number",
            sampleItemEntries.getValue("reading").jsonObject.taggedType()
        )
    }

    @Test
    fun `exact contextual synthesis schema is materializable and independently rejects wrong roots`() {
        val promotionSchema = realisticPromotionFixtureSchema()
        val authority = CouncilSynthesisWireAuthority(
            entries = listOf(
                CouncilSealedTestWireAuthority(
                    authorityKey = "nested-promotion",
                    testKey = "nested-promotion-test",
                    nodeKey = LocalNodeKey("nested-promotion-node"),
                    kind = CouncilSealedTestWireKind.PROMOTION,
                    promotionSchemaKey = promotionSchema.schemaKey
                )
            ),
            promotionSchemas = listOf(promotionSchema)
        )
        val schema = CouncilRoleSchemas.forRole(Role.CouncilSynthesis, authority)
        val document = CodexOutputSchemaDocument.from(schema)
        document.capture(
            Path.of(
                System.getProperty("user.dir"),
                "build",
                "appendix-b-v71-contextual-synthesis-schema.json"
            )
        )
        val captureRoot = createTempDirectory("contextual-synthesis-schema-")
        try {
            val captured = captureRoot.resolve("synthesis-output-schema.json")
            document.capture(captured)

            assertContentEquals(document.bytes, Files.readAllBytes(captured))
            assertEquals(
                document.sha256,
                CodexStructuredOutputValidator.sha256(document.bytes)
            )
            assertEquals(
                emptyList(),
                CodexStructuredOutputValidator.validate(
                    document,
                    realisticSynthesisProviderResponse(wrongInputRoot = false)
                        .toString()
                        .toByteArray(Charsets.UTF_8)
                )
            )
            val violations = CodexStructuredOutputValidator.validate(
                document,
                realisticSynthesisProviderResponse(wrongInputRoot = true)
                    .toString()
                    .toByteArray(Charsets.UTF_8)
            )
            assertTrue(violations.isNotEmpty())
        } finally {
            captureRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `contextual synthesis schemas stay bounded and reject oversized authority sets`() {
        val bounded = CouncilRoleSchemas.forRole(
            Role.CouncilSynthesis,
            largeSynthesisAuthority(8_192)
        )
        assertTrue(
            bounded.schema.toString().toByteArray(Charsets.UTF_8).size <=
                CODEX_OUTPUT_SCHEMA_MAX_BYTES
        )

        val failure = assertFailsWith<IllegalArgumentException> {
            CouncilRoleSchemas.forRole(
                Role.CouncilSynthesis,
                largeSynthesisAuthority(16_384)
            )
        }
        assertTrue(failure.message.orEmpty().contains("exceeds"))
        assertTrue(failure.message.orEmpty().contains("16384 sealed tests"))
    }

    @Test
    fun `contextual synthesis schema rejects too many distinct promotion schema groups`() {
        val bounded = CouncilRoleSchemas.forRole(
            Role.CouncilSynthesis,
            distinctSchemaSynthesisAuthority(32)
        )
        assertTrue(
            bounded.schema.toString().toByteArray(Charsets.UTF_8).size <=
                CODEX_OUTPUT_SCHEMA_MAX_BYTES
        )

        val failure = assertFailsWith<IllegalArgumentException> {
            CouncilRoleSchemas.forRole(
                Role.CouncilSynthesis,
                distinctSchemaSynthesisAuthority(2_048)
            )
        }
        assertTrue(failure.message.orEmpty().contains("exceeds"))
        assertTrue(failure.message.orEmpty().contains("2048 sealed tests"))
    }

    @Test
    fun `CouncilRunner rejects Synthesis without Task-derived authority`() = runTest {
        val runner = CouncilRunner(
            openRouterApiKey = "sk-test",
            executionEnvelope = councilTestExecutionEnvelope()
        )

        val failure = assertFailsWith<IllegalArgumentException> {
            runner.runCouncilAgent(
                role = Role.CouncilSynthesis,
                contextJson = "{}"
            )
        }

        assertTrue(failure.message.orEmpty().contains("Task-derived wire authority"))
    }

    @Test
    fun `CouncilRunner forwards the role schema through Koog prompt params`() = runTest {
        var observedSchema: LLMParams.Schema? = null
        val runner = CouncilRunner(
            openRouterApiKey = "sk-test",
            executionEnvelope = councilTestExecutionEnvelope(),
            promptExecutor = object : PromptExecutor() {
                override suspend fun execute(
                    prompt: ai.koog.prompt.Prompt,
                    model: LLModel,
                    tools: List<ToolDescriptor>
                ): Message.Assistant {
                    observedSchema = prompt.params.schema
                    return Message.Assistant(
                        content = """{"status":"ok"}""",
                        metaInfo = ResponseMetaInfo.create(KoogClock.System)
                    )
                }

                override fun executeStreaming(
                    prompt: ai.koog.prompt.Prompt,
                    model: LLModel,
                    tools: List<ToolDescriptor>
                ): Flow<StreamFrame> = emptyFlow()

                override suspend fun moderate(
                    prompt: ai.koog.prompt.Prompt,
                    model: LLModel
                ): ModerationResult = error("not used")

                override fun close() = Unit
            }
        )

        val authority = synthesisAuthority()
        runner.runCouncilAgent(
            CouncilRoleExecutionRequest(
                role = Role.CouncilSynthesis,
                contextJson = """{"objective":"test"}""",
                synthesisWireAuthority = authority
            )
        )

        val schema = assertIs<LLMParams.Schema.JSON.Standard>(observedSchema)
        assertEquals(CouncilRoleSchemas.forRole(Role.CouncilSynthesis, authority), schema)
        assertNotEquals(
            CouncilRoleSchemas.forRole(Role.CouncilTaskAnatomist),
            schema
        )
    }

    @Test
    fun `CouncilRunner validates the contextual Synthesis schema before invoking its executor`() =
        runTest {
            var executorInvoked = false
            var validatedSchema: CodexOutputSchemaDocument? = null
            val authority = synthesisAuthority()
            val runner = CouncilRunner(
                openRouterApiKey = "sk-test",
                executionEnvelope = councilTestExecutionEnvelope(),
                promptExecutor = object : PromptExecutor() {
                    override suspend fun execute(
                        prompt: ai.koog.prompt.Prompt,
                        model: LLModel,
                        tools: List<ToolDescriptor>
                    ): Message.Assistant {
                        executorInvoked = true
                        error("executor must not run after schema admission failure")
                    }

                    override fun executeStreaming(
                        prompt: ai.koog.prompt.Prompt,
                        model: LLModel,
                        tools: List<ToolDescriptor>
                    ): Flow<StreamFrame> = emptyFlow()

                    override suspend fun moderate(
                        prompt: ai.koog.prompt.Prompt,
                        model: LLModel
                    ): ModerationResult = error("executor must not run")

                    override fun close() = Unit
                },
                structuredOutputSchemaValidator = { document ->
                    validatedSchema = document
                    listOf("synthetic schema compiler failure")
                }
            )

            val failure = assertFailsWith<CouncilRoleExecutionException> {
                runner.runCouncilAgent(
                    CouncilRoleExecutionRequest(
                        role = Role.CouncilSynthesis,
                        contextJson = """{"objective":"test"}""",
                        synthesisWireAuthority = authority
                    )
                )
            }

            assertFalse(executorInvoked)
            val expectedSchema = CodexOutputSchemaDocument.from(
                CouncilRoleSchemas.forRole(Role.CouncilSynthesis, authority)
            )
            val actualSchema = assertNotNull(validatedSchema)
            assertEquals(expectedSchema.name, actualSchema.name)
            assertEquals(expectedSchema.sha256, actualSchema.sha256)
            assertContentEquals(expectedSchema.bytes, actualSchema.bytes)
            assertTrue(failure.message.orEmpty().contains("Draft 2020-12"))
            assertTrue(failure.message.orEmpty().contains("synthetic schema compiler failure"))
        }

    @Test
    fun `CouncilRunner rejects request and envelope profile mismatch before provider work`() =
        runTest {
            var providerInvoked = false
            val runner = CouncilRunner(
                openRouterApiKey = "sk-test",
                executionEnvelope = councilTestExecutionEnvelope().copy(
                    acceptanceProfile = AcceptanceProfile.RUNTIME_USABILITY_SMOKE
                ),
                promptExecutor = object : PromptExecutor() {
                    override suspend fun execute(
                        prompt: ai.koog.prompt.Prompt,
                        model: LLModel,
                        tools: List<ToolDescriptor>
                    ): Message.Assistant {
                        providerInvoked = true
                        error("provider must not run")
                    }

                    override fun executeStreaming(
                        prompt: ai.koog.prompt.Prompt,
                        model: LLModel,
                        tools: List<ToolDescriptor>
                    ): Flow<StreamFrame> = emptyFlow()

                    override suspend fun moderate(
                        prompt: ai.koog.prompt.Prompt,
                        model: LLModel
                    ): ModerationResult = error("provider must not run")

                    override fun close() = Unit
                }
            )

            val failure = assertFailsWith<IllegalArgumentException> {
                runner.runCouncilAgent(
                    CouncilRoleExecutionRequest(
                        role = Role.CouncilTaskAnatomist,
                        contextJson = "{}"
                    )
                )
            }

            assertTrue(failure.message.orEmpty().contains("does not match envelope profile"))
            assertFalse(providerInvoked)
        }

    private fun assertProviderStrict(element: JsonElement, path: String = "$") {
        when (element) {
            is JsonArray -> element.forEachIndexed { index, child ->
                assertProviderStrict(child, "$path[$index]")
            }
            is JsonObject -> {
                val properties = element["properties"] as? JsonObject
                if (properties != null) {
                    val required = element["required"] as? JsonArray
                    assertTrue(required != null, "$path object is missing required")
                    val requiredNames = required.map { it.jsonPrimitive.content }
                    assertEquals(
                        properties.keys,
                        requiredNames.toSet(),
                        "$path required must contain every property exactly once"
                    )
                    assertEquals(
                        properties.size,
                        requiredNames.size,
                        "$path required contains duplicates"
                    )
                    assertEquals(
                        false,
                        element["additionalProperties"]?.jsonPrimitive?.booleanOrNull,
                        "$path object must reject additional properties"
                    )
                }
                assertFalse("oneOf" in element, "$path uses unsupported oneOf")
                assertFalse("const" in element, "$path uses unsupported const")
                if ("\$ref" in element) {
                    assertEquals(
                        setOf("\$ref"),
                        element.keys,
                        "$path must not place siblings beside a reference"
                    )
                }
                element.forEach { (key, child) ->
                    assertProviderStrict(child, "$path.$key")
                }
            }
            else -> Unit
        }
    }

    private fun JsonObject.resolveReference(definitions: JsonObject): JsonObject {
        val reference = this["\$ref"]?.jsonPrimitive?.content ?: return this
        return definitions.getValue(reference.substringAfterLast('/')).jsonObject
    }

    private fun assertQuantitySemanticsBound(schema: JsonObject) {
        val definitions = schema.getValue("\$defs").jsonObject
        val quantityDefinitions = definitions.values
            .map(JsonElement::jsonObject)
            .filter { definition ->
                definition["anyOf"]
                    ?.jsonArray
                    ?.all { branch ->
                        branch.jsonObject["properties"]
                            ?.jsonObject
                            ?.keys == QUANTITY_FIELDS
                    } == true
            }
        assertTrue(quantityDefinitions.isNotEmpty())
        quantityDefinitions.forEach { definition ->
            val branches = definition.getValue("anyOf")
                .jsonArray
                .associateBy { branch ->
                    branch.jsonObject
                        .getValue("properties")
                        .jsonObject
                        .getValue("aggregation")
                        .jsonObject
                        .getValue("enum")
                        .jsonArray
                        .single()
                        .jsonPrimitive
                        .content
                }
            assertEquals(
                AggregationSemantics.entries.mapTo(linkedSetOf(), AggregationSemantics::name),
                branches.keys
            )
            branches.forEach { (aggregation, branch) ->
                val branchObject = branch.jsonObject
                val properties = branchObject.getValue("properties").jsonObject
                assertEquals(
                    properties.keys,
                    branchObject.getValue("required")
                        .jsonArray
                        .mapTo(linkedSetOf()) { it.jsonPrimitive.content }
                )
                val timeBasis = properties.getValue("timeBasis").jsonObject
                if (
                    AggregationSemantics.valueOf(aggregation) in
                    INTERVAL_AGGREGATIONS
                ) {
                    assertEquals("string", timeBasis.getValue("type").jsonPrimitive.content)
                    assertEquals(
                        TimeBasis.entries.mapTo(linkedSetOf(), TimeBasis::name),
                        timeBasis.getValue("enum")
                            .jsonArray
                            .mapTo(linkedSetOf()) { it.jsonPrimitive.content }
                    )
                } else {
                    assertEquals("null", timeBasis.getValue("type").jsonPrimitive.content)
                }
            }
        }
    }

    private companion object {
        val QUANTITY_FIELDS = setOf(
            "dimension",
            "unitId",
            "scaleToSi",
            "aggregation",
            "timeBasis"
        )
        val INTERVAL_AGGREGATIONS = setOf(
            AggregationSemantics.PER_INTERVAL_RATE,
            AggregationSemantics.SUM_OVER_INTERVALS,
            AggregationSemantics.AVERAGE_OVER_INTERVALS
        )
    }

    private fun JsonObject.discriminatorBranches(
        discriminator: String,
        expectedValues: Set<String>
    ): Map<String, JsonObject> {
        val branches = values
            .map(JsonElement::jsonObject)
            .mapNotNull { definition ->
                val properties = definition["properties"] as? JsonObject ?: return@mapNotNull null
                val value = properties[discriminator]
                    ?.jsonObject
                    ?.get("enum")
                    ?.jsonArray
                    ?.singleOrNull()
                    ?.jsonPrimitive
                    ?.content
                    ?: return@mapNotNull null
                value.takeIf(expectedValues::contains)?.let { it to definition }
            }
        assertEquals(
            expectedValues,
            branches.mapTo(linkedSetOf()) { it.first },
            "Unexpected $discriminator branches"
        )
        assertEquals(
            expectedValues.size,
            branches.size,
            "Duplicate $discriminator branches"
        )
        return branches.toMap()
    }

    private fun JsonObject.isContextualSynthesisDefinition(): Boolean {
        val properties = this["properties"] as? JsonObject ?: return false
        return setOf(
            "atlasVersion",
            "parentAtlasHash",
            "stages",
            "sealedTests",
            "workerFieldNotes",
            "graderFieldNotes",
            "rubricSchemaVersion",
            "declaredChange"
        ) == properties.keys
    }

    private fun JsonArray.taggedBranchTypes(
        definitions: JsonObject
    ): Set<String> = map { it.jsonObject.resolveReference(definitions) }
        .mapTo(linkedSetOf()) { definition ->
            definition.getValue("properties")
                .jsonObject
                .getValue("type")
                .jsonObject
                .getValue("enum")
                .jsonArray
                .single()
                .jsonPrimitive
                .content
        }

    private fun JsonObject.taggedType(): String =
        getValue("properties")
            .jsonObject
            .getValue("type")
            .jsonObject
            .getValue("enum")
            .jsonArray
            .single()
            .jsonPrimitive
            .content

    private fun synthesisAuthority(): CouncilSynthesisWireAuthority {
        val promotionSchema = promotionFixtureSchema()
        return CouncilSynthesisWireAuthority(
            entries = listOf(
                CouncilSealedTestWireAuthority(
                    authorityKey = "general-authority",
                    testKey = "general-test",
                    nodeKey = LocalNodeKey("general-node"),
                    kind = CouncilSealedTestWireKind.GENERAL
                ),
                CouncilSealedTestWireAuthority(
                    authorityKey = "promotion-authority",
                    testKey = "promotion-test",
                    nodeKey = LocalNodeKey("promotion-node"),
                    kind = CouncilSealedTestWireKind.PROMOTION,
                    promotionSchemaKey = promotionSchema.schemaKey
                )
            ),
            promotionSchemas = listOf(promotionSchema)
        )
    }

    private fun largeSynthesisAuthority(count: Int): CouncilSynthesisWireAuthority {
        val promotionSchema = promotionFixtureSchema()
        return CouncilSynthesisWireAuthority(
            entries = (0 until count).map { index ->
                val identity = index.toString(16).padStart(64, '0')
                val kind = if (index % 2 == 0) {
                    CouncilSealedTestWireKind.PROMOTION
                } else {
                    CouncilSealedTestWireKind.GENERAL
                }
                CouncilSealedTestWireAuthority(
                    authorityKey = identity,
                    testKey = "test-$identity",
                    nodeKey = LocalNodeKey("node-$identity"),
                    kind = kind,
                    promotionSchemaKey = promotionSchema.schemaKey.takeIf {
                        kind == CouncilSealedTestWireKind.PROMOTION
                    }
                )
            },
            promotionSchemas = listOfNotNull(
                promotionSchema.takeIf { count > 0 }
            )
        )
    }

    private fun promotionFixtureSchema(
        inputType: TypedFieldType = TypedFieldType.INTEGER,
        outputType: TypedFieldType = TypedFieldType.BOOLEAN
    ): CouncilPromotionFixtureWireSchemaAuthority =
        CouncilPromotionFixtureWireSchemaAuthority.fromSchemas(
            inputSchema = TypedToolSchemaCompiler.compileSchema(
                listOf(
                    TypedField(
                        name = "value",
                        type = inputType,
                        description = "Exact promotion input."
                    )
                )
            ),
            outputSchema = TypedToolSchemaCompiler.compileSchema(
                listOf(
                    TypedField(
                        name = "ok",
                        type = outputType,
                        description = "Exact promotion output."
                    )
                )
            )
        )

    private fun realisticPromotionFixtureSchema():
        CouncilPromotionFixtureWireSchemaAuthority =
        CouncilPromotionFixtureWireSchemaAuthority.fromSchemas(
            inputSchema = TypedToolSchemaCompiler.compileSchema(
                listOf(
                    TypedField(
                        name = "scenario",
                        type = TypedFieldType.OBJECT,
                        description = "Nested scenario.",
                        objectFields = setOf(
                            TypedField(
                                name = "mode",
                                type = TypedFieldType.STRING,
                                description = "Execution mode.",
                                enumValues = setOf("SAFE", "FAST")
                            ),
                            TypedField(
                                name = "samples",
                                type = TypedFieldType.ARRAY,
                                description = "Bounded samples.",
                                itemType = TypedFieldType.OBJECT,
                                minimumItems = 1,
                                maximumItems = 2,
                                objectFields = setOf(
                                    TypedField(
                                        name = "reading",
                                        type = TypedFieldType.NUMBER,
                                        description = "Measured reading."
                                    ),
                                    TypedField(
                                        name = "label",
                                        type = TypedFieldType.STRING,
                                        description = "Optional sample label.",
                                        required = false,
                                        enumValues = setOf("A", "B")
                                    )
                                )
                            )
                        )
                    ),
                    TypedField(
                        name = "note",
                        type = TypedFieldType.STRING,
                        description = "Optional note.",
                        required = false,
                        enumValues = setOf("alpha", "beta")
                    )
                )
            ),
            outputSchema = TypedToolSchemaCompiler.compileSchema(
                listOf(
                    TypedField(
                        name = "status",
                        type = TypedFieldType.STRING,
                        description = "Result status.",
                        enumValues = setOf("OK", "REJECTED")
                    )
                )
            )
        )

    private fun realisticSynthesisProviderResponse(wrongInputRoot: Boolean): JsonObject {
        val input = if (wrongInputRoot) {
            taggedPrimitive(type = "string", value = JsonPrimitive("wrong-root"))
        } else {
            buildJsonObject {
                put("type", "object")
                put(
                    "entries",
                    buildJsonObject {
                        put(
                            "note",
                            buildJsonObject {
                                put("state", "ABSENT")
                            }
                        )
                        put(
                            "scenario",
                            buildJsonObject {
                                put("type", "object")
                                put(
                                    "entries",
                                    buildJsonObject {
                                        put(
                                            "mode",
                                            taggedPrimitive(
                                                type = "string",
                                                value = JsonPrimitive("FAST")
                                            )
                                        )
                                        put(
                                            "samples",
                                            buildJsonObject {
                                                put("type", "array")
                                                put(
                                                    "values",
                                                    JsonArray(
                                                        listOf(
                                                            buildJsonObject {
                                                                put("type", "object")
                                                                put(
                                                                    "entries",
                                                                    buildJsonObject {
                                                                        put(
                                                                            "label",
                                                                            buildJsonObject {
                                                                                put("state", "ABSENT")
                                                                            }
                                                                        )
                                                                        put(
                                                                            "reading",
                                                                            taggedPrimitive(
                                                                                type = "number",
                                                                                value = JsonPrimitive(1.5)
                                                                            )
                                                                        )
                                                                    }
                                                                )
                                                            }
                                                        )
                                                    )
                                                )
                                            }
                                        )
                                    }
                                )
                            }
                        )
                    }
                )
            }
        }
        return buildJsonObject {
            put("atlasVersion", 1)
            put("parentAtlasHash", JsonNull)
            put("stages", JsonArray(emptyList()))
            put(
                "sealedTests",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("type", "PROMOTION")
                            put("authorityKey", "nested-promotion")
                            put("acceptedArtifactTypes", JsonArray(emptyList()))
                            put(
                                "testDefinitionJson",
                                buildJsonObject {
                                    put("input", input)
                                    put(
                                        "expectedOutput",
                                        buildJsonObject {
                                            put("type", "object")
                                            put(
                                                "entries",
                                                buildJsonObject {
                                                    put(
                                                        "status",
                                                        taggedPrimitive(
                                                            type = "string",
                                                            value = JsonPrimitive("OK")
                                                        )
                                                    )
                                                }
                                            )
                                        }
                                    )
                                }
                            )
                            put("commitmentHash", "commitment")
                        }
                    )
                )
            )
            put("workerFieldNotes", JsonArray(emptyList()))
            put("graderFieldNotes", JsonArray(emptyList()))
            put("rubricSchemaVersion", "v1")
            put("declaredChange", JsonNull)
        }
    }

    private fun generalSynthesisProviderResponse(
        definition: JsonElement
    ): JsonObject = buildJsonObject {
        put("atlasVersion", 1)
        put("parentAtlasHash", JsonNull)
        put("stages", JsonArray(emptyList()))
        put(
            "sealedTests",
            JsonArray(
                listOf(
                    buildJsonObject {
                        put("type", "GENERAL")
                        put("authorityKey", "general-authority")
                        put("acceptedArtifactTypes", JsonArray(emptyList()))
                        put("testDefinitionJson", definition)
                        put("commitmentHash", "compiler-recomputed")
                    }
                )
            )
        )
        put("workerFieldNotes", JsonArray(emptyList()))
        put("graderFieldNotes", JsonArray(emptyList()))
        put("rubricSchemaVersion", "v1")
        put("declaredChange", JsonNull)
    }

    private fun taggedPrimitive(type: String, value: JsonPrimitive): JsonObject =
        buildJsonObject {
            put("type", type)
            put("value", value)
        }

    private fun distinctSchemaSynthesisAuthority(count: Int): CouncilSynthesisWireAuthority {
        val schemas = (0 until count).map { index ->
            CouncilPromotionFixtureWireSchemaAuthority.fromSchemas(
                inputSchema = TypedToolSchemaCompiler.compileSchema(
                    listOf(
                        TypedField(
                            name = "value",
                            type = TypedFieldType.STRING,
                            description = "Distinct input.",
                            enumValues = setOf("value-$index")
                        )
                    )
                ),
                outputSchema = TypedToolSchemaCompiler.compileSchema(
                    listOf(
                        TypedField(
                            name = "ok",
                            type = TypedFieldType.BOOLEAN,
                            description = "Exact output."
                        )
                    )
                )
            )
        }
        return CouncilSynthesisWireAuthority(
            entries = schemas.mapIndexed { index, schema ->
                val identity = index.toString(16).padStart(64, '0')
                CouncilSealedTestWireAuthority(
                    authorityKey = identity,
                    testKey = "test-$identity",
                    nodeKey = LocalNodeKey("node-$identity"),
                    kind = CouncilSealedTestWireKind.PROMOTION,
                    promotionSchemaKey = schema.schemaKey
                )
            },
            promotionSchemas = schemas.sortedBy(
                CouncilPromotionFixtureWireSchemaAuthority::schemaKey
            )
        )
    }

}
