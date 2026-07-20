package com.achulkov.evomolior.core.council

import com.achulkov.evomolior.core.atlas.ArtifactType
import com.achulkov.evomolior.core.atlas.CapturePolicy
import com.achulkov.evomolior.core.atlas.EvidenceKind
import com.achulkov.evomolior.core.atlas.EvidenceProfile
import com.achulkov.evomolior.core.atlas.FinalOutputSpec
import com.achulkov.evomolior.core.atlas.FormulaDslReferenceSemantics
import com.achulkov.evomolior.core.atlas.NodeDeliverableContract
import com.achulkov.evomolior.core.atlas.NodeAtlas
import com.achulkov.evomolior.core.grader.GraderAtlas
import com.achulkov.evomolior.core.atlas.NodeKind
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.atlas.ProofContract
import com.achulkov.evomolior.core.atlas.ProofMethod
import com.achulkov.evomolior.core.atlas.PhysicalDimension
import com.achulkov.evomolior.core.atlas.ReplicationPolicy
import com.achulkov.evomolior.core.atlas.RevealMode
import com.achulkov.evomolior.core.atlas.RewardClass
import com.achulkov.evomolior.core.atlas.RewardSpec
import com.achulkov.evomolior.core.atlas.SimulationRecordBindings
import com.achulkov.evomolior.core.atlas.ToolBehavior
import com.achulkov.evomolior.core.atlas.ToolDslExpression
import com.achulkov.evomolior.core.atlas.ToolDslKind
import com.achulkov.evomolior.core.atlas.ToolDslOutput
import com.achulkov.evomolior.core.atlas.ToolDslProgram
import com.achulkov.evomolior.core.atlas.ToolImplementationMode
import com.achulkov.evomolior.core.atlas.ToolOpportunity
import com.achulkov.evomolior.core.atlas.ToolOpportunityType
import com.achulkov.evomolior.core.atlas.TypedField
import com.achulkov.evomolior.core.atlas.TypedFieldType
import com.achulkov.evomolior.core.atlas.TypedToolSchemaCompiler
import com.achulkov.evomolior.core.contract.GroupTopologyKind
import com.achulkov.evomolior.core.contract.StartingField
import com.achulkov.evomolior.core.config.CouncilExecutionPurpose
import com.achulkov.evomolior.core.model.GroupProfileType
import com.achulkov.evomolior.core.model.Role
import com.achulkov.evomolior.core.model.ScoringDimension
import com.achulkov.evomolior.core.serialization.CouncilSynthesisStructuredOutputSerializer
import com.achulkov.evomolior.core.serialization.CouncilSynthesisContextualOutputSerializer
import com.achulkov.evomolior.core.serialization.CouncilSynthesisProviderWireNormalization
import com.achulkov.evomolior.core.serialization.ToolOpportunityStructuredOutputSerializer
import com.achulkov.evomolior.core.serialization.TypedFieldStructuredOutputSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CouncilContractsTest {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        classDiscriminator = "resultType"
    }
    private val structuredJson = Json {
        encodeDefaults = true
        explicitNulls = true
        classDiscriminator = "type"
        ignoreUnknownKeys = false
    }

    @Test
    fun `Council execution requests require authority exactly for Synthesis`() {
        val authority = CouncilSynthesisWireAuthority(emptyList())

        assertFailsWith<IllegalArgumentException> {
            CouncilRoleExecutionRequest(
                role = Role.CouncilSynthesis,
                contextJson = "{}"
            )
        }
        assertFailsWith<IllegalArgumentException> {
            CouncilRoleExecutionRequest(
                role = Role.CouncilTaskAnatomist,
                contextJson = "{}",
                synthesisWireAuthority = authority
            )
        }
        assertEquals(
            authority,
            CouncilRoleExecutionRequest(
                role = Role.CouncilSynthesis,
                contextJson = "{}",
                synthesisWireAuthority = authority
            ).synthesisWireAuthority
        )
    }

    @Test
    fun `execution envelope survives serialization and hashes sets deterministically`() {
        val first = envelope(reverseSets = false)
        val second = envelope(reverseSets = true)
        val acceptance = first.copy(
            councilExecutionPurpose = CouncilExecutionPurpose.APPENDIX_B_ACCEPTANCE
        )
        val standardJson = json.encodeToString(first)
        val acceptanceJson = json.encodeToString(acceptance)

        assertEquals(first, json.decodeFromString<ExecutionEnvelope>(standardJson))
        assertFalse("councilExecutionPurpose" in standardJson)
        assertContains(
            acceptanceJson,
            "\"councilExecutionPurpose\":\"APPENDIX_B_ACCEPTANCE\""
        )
        assertEquals(
            acceptance,
            json.decodeFromString<ExecutionEnvelope>(acceptanceJson)
        )
        assertEquals(
            CouncilContractHashing.hashExecutionEnvelope(first),
            CouncilContractHashing.hashExecutionEnvelope(second)
        )
        assertNotEquals(
            CouncilContractHashing.hashExecutionEnvelope(first),
            CouncilContractHashing.hashExecutionEnvelope(acceptance)
        )
    }

    @Test
    fun `all eight typed role outputs round trip as one bundle`() {
        val bundle = roleOutputBundle()
        val encoded = json.encodeToString(bundle)

        assertEquals(bundle, json.decodeFromString<CouncilRoleOutputBundle>(encoded))
        assertTrue(encoded.contains("\"taskAnatomist\""))
        assertTrue(encoded.contains("\"synthesis\""))
        assertContains(
            encoded,
            "\"receiverObligations\":[{\"key\":\"supported\",\"value\":\"Support claims\"}]"
        )
        assertContains(
            encoded,
            "\"actionCosts\":[{\"key\":\"READ\",\"value\":0.0}," +
                "{\"key\":\"WRITE\",\"value\":1.0}]"
        )
        assertContains(
            encoded,
            "\"rolePrompts\":[{\"key\":\"Researcher\",\"value\":\"Research.\"}]"
        )
        assertContains(
            encoded,
            "\"aliases\":[{\"key\":\"backs\",\"value\":\"supports\"}]"
        )
        assertContains(
            encoded,
            "\"nodeMappings\":[{\"key\":\"establish-evidence\"," +
                "\"value\":[\"claim\",\"evidence\"]}]"
        )
    }

    @Test
    fun `typed fields use a closed conditional wire union`() {
        val dimension = PhysicalDimension(mapOf("power" to 1))
        val fields = listOf(
            TypedField(
                name = "label",
                type = TypedFieldType.STRING,
                description = "Closed label.",
                enumValues = linkedSetOf("beta", "alpha"),
                validationRules = linkedSetOf("required", "bounded")
            ),
            TypedField(
                name = "count",
                type = TypedFieldType.INTEGER,
                description = "Measured count.",
                physicalDimension = PhysicalDimension(mapOf("count" to 1))
            ),
            TypedField(
                name = "demand",
                type = TypedFieldType.NUMBER,
                description = "Electrical demand.",
                physicalDimension = dimension
            ),
            TypedField("enabled", TypedFieldType.BOOLEAN, "Enable flag."),
            TypedField("payload", TypedFieldType.JSON, "Bounded JSON value."),
            TypedField(
                name = "record",
                type = TypedFieldType.OBJECT,
                description = "Bounded object.",
                objectFields = setOf(
                    TypedField("value", TypedFieldType.NUMBER, "Nested numeric value.")
                )
            ),
            TypedField(
                name = "samples",
                type = TypedFieldType.ARRAY,
                description = "Measured samples.",
                itemType = TypedFieldType.NUMBER,
                minimumItems = 1,
                maximumItems = 72,
                validationRules = linkedSetOf("non-empty", "finite"),
                physicalDimension = dimension
            )
        )

        fields.forEach { field ->
            val encoded = structuredJson.encodeToString(
                TypedFieldStructuredOutputSerializer,
                field
            )
            val objectValue = structuredJson.parseToJsonElement(encoded).jsonObject
            assertEquals(field.type.name, objectValue.getValue("type").jsonPrimitive.content)
            assertEquals(
                field,
                structuredJson.decodeFromString(TypedFieldStructuredOutputSerializer, encoded)
            )
            assertEquals(
                field.validationRules.sorted(),
                objectValue.getValue("validationRules")
                    .jsonArray
                    .map { it.jsonPrimitive.content }
            )
        }

        val stringField = structuredJson.encodeToJsonElement(
            TypedFieldStructuredOutputSerializer,
            fields.first()
        ).jsonObject
        assertEquals(
            listOf("alpha", "beta"),
            stringField.getValue("enumValues").jsonArray.map { it.jsonPrimitive.content }
        )
        assertFalse("itemType" in stringField)
        assertFalse("physicalDimension" in stringField)

        val numberField = structuredJson.encodeToJsonElement(
            TypedFieldStructuredOutputSerializer,
            fields[2]
        ).jsonObject
        assertFalse("itemType" in numberField)
        assertFalse("enumValues" in numberField)

        val arrayField = structuredJson.encodeToJsonElement(
            TypedFieldStructuredOutputSerializer,
            fields.last()
        ).jsonObject
        assertEquals(
            "NUMBER_ITEMS",
            arrayField.getValue("items").jsonObject.getValue("type").jsonPrimitive.content
        )
        assertEquals("1", arrayField.getValue("minimumItems").jsonPrimitive.content)
        assertEquals("72", arrayField.getValue("maximumItems").jsonPrimitive.content)
        assertFalse("enumValues" in arrayField)
    }

    @Test
    fun `typed field wire rejects missing array item types and duplicate set entries`() {
        assertFailsWith<SerializationException> {
            structuredJson.decodeFromString(
                TypedFieldStructuredOutputSerializer,
                """
                {
                  "type":"ARRAY",
                  "name":"samples",
                  "description":"Measured samples.",
                  "required":true,
                  "validationRules":[],
                  "physicalDimension":null
                }
                """.trimIndent()
            )
        }
        assertFailsWith<SerializationException> {
            structuredJson.decodeFromString(
                TypedFieldStructuredOutputSerializer,
                """
                {
                  "type":"BOOLEAN",
                  "name":"enabled",
                  "description":"Enable flag.",
                  "required":true,
                  "validationRules":["bounded","bounded"]
                }
                """.trimIndent()
            )
        }
    }

    @Test
    fun `tool opportunity wire closes implementation and simulation conditionals`() {
        val formula = formulaOpportunity(reverseSets = false)
        val python = typedFieldOpportunity(reverseSets = false)
        val deterministicSimulation = simulationOpportunity(stochastic = false)
        val stochasticSimulation = simulationOpportunity(stochastic = true)

        listOf(formula, python, deterministicSimulation, stochasticSimulation).forEach {
            val encoded = structuredJson.encodeToString(
                ToolOpportunityStructuredOutputSerializer,
                it
            )
            assertEquals(
                it,
                structuredJson.decodeFromString(
                    ToolOpportunityStructuredOutputSerializer,
                    encoded
                )
            )
        }

        val formulaJson = structuredJson.encodeToJsonElement(
            ToolOpportunityStructuredOutputSerializer,
            formula
        ).jsonObject
        assertTrue(
            setOf(
                "opportunityType",
                "preferredImplementationMode",
                "behavior",
                "simulationRecordBindings",
                "formulaDslReferenceSemantics",
                "formulaDslReferenceProgram"
            ).none(formulaJson::containsKey)
        )
        val formulaImplementation = formulaJson.getValue("implementation").jsonObject
        assertEquals(
            "FORMULA_DSL",
            formulaImplementation.getValue("type").jsonPrimitive.content
        )
        assertTrue("formulaDslReferenceSemantics" in formulaImplementation)
        assertTrue("formulaDslReferenceProgram" in formulaImplementation)
        assertEquals(
            "FORMULA",
            formulaImplementation.getValue("formulaDslReferenceProgram")
                .jsonObject
                .getValue("kind")
                .jsonPrimitive
                .content
        )
        val pythonImplementation = structuredJson.encodeToJsonElement(
            ToolOpportunityStructuredOutputSerializer,
            python
        ).jsonObject.getValue("implementation").jsonObject
        assertEquals(setOf("type"), pythonImplementation.keys)
        assertEquals(
            "PYTHON",
            pythonImplementation.getValue("type").jsonPrimitive.content
        )
        val domainJson = structuredJson.encodeToJsonElement(
            ToolOpportunity.serializer(),
            python
        ).jsonObject
        assertTrue("preferredImplementationMode" in domainJson)
        assertTrue("opportunityType" in domainJson)
        assertTrue("behavior" in domainJson)
        assertFalse("implementation" in domainJson)
        assertFalse("execution" in domainJson)

        val deterministicConfiguration = structuredJson.encodeToJsonElement(
            ToolOpportunityStructuredOutputSerializer,
            deterministicSimulation
        ).jsonObject
            .getValue("execution").jsonObject
            .getValue("simulationConfiguration").jsonObject
        assertEquals(
            "DETERMINISTIC",
            deterministicConfiguration.getValue("type").jsonPrimitive.content
        )
        assertFalse("randomSeedInputField" in deterministicConfiguration)

        val stochasticConfiguration = structuredJson.encodeToJsonElement(
            ToolOpportunityStructuredOutputSerializer,
            stochasticSimulation
        ).jsonObject
            .getValue("execution").jsonObject
            .getValue("simulationConfiguration").jsonObject
        assertEquals(
            "STOCHASTIC",
            stochasticConfiguration.getValue("type").jsonPrimitive.content
        )
        assertEquals(
            "random_seed",
            stochasticConfiguration.getValue("randomSeedInputField").jsonPrimitive.content
        )
    }

    @Test
    fun `tool opportunity wire rejects branch leakage missing commitments and duplicate sets`() {
        val pythonJson = structuredJson.encodeToJsonElement(
            ToolOpportunityStructuredOutputSerializer,
            typedFieldOpportunity(reverseSets = false)
        ).jsonObject
        val pythonImplementation = pythonJson.getValue("implementation").jsonObject
        val leakedImplementation = JsonObject(
            pythonImplementation + (
                "formulaDslReferenceSemantics" to
                    JsonPrimitive("DIMENSIONAL_ANALYSIS_V1")
                )
        )
        assertFailsWith<SerializationException> {
            structuredJson.decodeFromString(
                ToolOpportunityStructuredOutputSerializer,
                JsonObject(pythonJson + ("implementation" to leakedImplementation)).toString()
            )
        }

        val formulaJson = structuredJson.encodeToJsonElement(
            ToolOpportunityStructuredOutputSerializer,
            formulaOpportunity(reverseSets = false)
        ).jsonObject
        val formulaImplementation = formulaJson.getValue("implementation").jsonObject
        assertFailsWith<SerializationException> {
            structuredJson.decodeFromString(
                ToolOpportunityStructuredOutputSerializer,
                JsonObject(
                    formulaJson + (
                        "implementation" to
                            JsonObject(formulaImplementation - "formulaDslReferenceProgram")
                        )
                ).toString()
            )
        }

        val deterministicJson = structuredJson.encodeToJsonElement(
            ToolOpportunityStructuredOutputSerializer,
            simulationOpportunity(stochastic = false)
        ).jsonObject
        val deterministicExecution = deterministicJson.getValue("execution").jsonObject
        val deterministicConfiguration =
            deterministicExecution.getValue("simulationConfiguration").jsonObject
        assertFailsWith<SerializationException> {
            structuredJson.decodeFromString(
                ToolOpportunityStructuredOutputSerializer,
                JsonObject(
                    deterministicJson + (
                        "execution" to JsonObject(
                            deterministicExecution + (
                                "simulationConfiguration" to JsonObject(
                                    deterministicConfiguration + (
                                        "randomSeedInputField" to JsonPrimitive("random_seed")
                                        )
                                )
                                )
                        )
                        )
                ).toString()
            )
        }

        val duplicateReuseIds = JsonArray(
            pythonJson.getValue("expectedReuseNodeIds").jsonArray +
                pythonJson.getValue("expectedReuseNodeIds").jsonArray.first()
        )
        assertFailsWith<SerializationException> {
            structuredJson.decodeFromString(
                ToolOpportunityStructuredOutputSerializer,
                JsonObject(
                    pythonJson + ("expectedReuseNodeIds" to duplicateReuseIds)
                ).toString()
            )
        }
    }

    @Test
    fun `tool opportunity wire and role hash normalize sets and commit formula programs`() {
        val firstOpportunity = formulaOpportunity(reverseSets = false)
        val secondOpportunity = formulaOpportunity(reverseSets = true)
        assertEquals(
            structuredJson.encodeToString(
                ToolOpportunityStructuredOutputSerializer,
                firstOpportunity
            ),
            structuredJson.encodeToString(
                ToolOpportunityStructuredOutputSerializer,
                secondOpportunity
            )
        )

        val base = roleOutputBundle()
        val first = base.withToolOpportunity(firstOpportunity)
        val second = base.withToolOpportunity(secondOpportunity)
        val changed = base.withToolOpportunity(
            secondOpportunity.copy(
                formulaDslReferenceProgram = ToolDslProgram(
                    kind = ToolDslKind.FORMULA,
                    outputs = listOf(
                        ToolDslOutput(
                            field = "result",
                            expression = ToolDslExpression.Literal(JsonPrimitive(2))
                        )
                    )
                )
            )
        )
        assertEquals(
            CouncilContractHashing.hashRoleOutputs(first),
            CouncilContractHashing.hashRoleOutputs(second)
        )
        assertNotEquals(
            CouncilContractHashing.hashRoleOutputs(first),
            CouncilContractHashing.hashRoleOutputs(changed)
        )
    }

    @Test
    fun `role output hashing ignores semantic set and map insertion order`() {
        val first = roleOutputBundle(reverseSets = false)
        val second = roleOutputBundle(reverseSets = true)

        assertEquals(
            CouncilContractHashing.hashRoleOutputs(first),
            CouncilContractHashing.hashRoleOutputs(second)
        )
    }

    @Test
    fun `role output hashing commits conditional typed fields deterministically`() {
        val base = roleOutputBundle()
        val first = base.withToolOpportunity(typedFieldOpportunity(reverseSets = false))
        val second = base.withToolOpportunity(typedFieldOpportunity(reverseSets = true))
        val changed = base.withToolOpportunity(
            typedFieldOpportunity(reverseSets = true).copy(
                requiredInputs = typedFieldOpportunity(reverseSets = true).requiredInputs.map {
                    if (it.name == "samples") it.copy(maximumItems = 65) else it
                }
            )
        )

        assertEquals(
            CouncilContractHashing.hashRoleOutputs(first),
            CouncilContractHashing.hashRoleOutputs(second)
        )
        assertNotEquals(
            CouncilContractHashing.hashRoleOutputs(first),
            CouncilContractHashing.hashRoleOutputs(changed)
        )
    }

    @Test
    fun `synthesis provider wire tags test JSON without changing persisted domain shape`() {
        val base = roleOutputBundle().synthesis
        val definition =
            """{"input":{"integerValue":1,"numberValue":1.0},"expectedOutput":{"ok":true}}"""
        val synthesis = base.copy(
            sealedTests = base.sealedTests.map { test ->
                test.copy(
                    testDefinitionJson = definition,
                    commitmentHash = CouncilContractHashing.sha256(definition)
                )
            }
        )

        val persisted = json.encodeToJsonElement(
            SynthesisOutput.serializer(),
            synthesis
        ).jsonObject
        val persistedDefinition = persisted.getValue("sealedTests")
            .jsonArray
            .single()
            .jsonObject
            .getValue("testDefinitionJson")
            .jsonPrimitive
        assertTrue(persistedDefinition.isString)
        assertEquals(definition, persistedDefinition.content)

        val providerWire = structuredJson.encodeToJsonElement(
            CouncilSynthesisStructuredOutputSerializer,
            synthesis
        ).jsonObject
        val taggedDefinition = providerWire.getValue("sealedTests")
            .jsonArray
            .single()
            .jsonObject
            .getValue("testDefinitionJson")
            .jsonObject
        assertEquals("object", taggedDefinition.getValue("type").jsonPrimitive.content)

        val decoded = structuredJson.decodeFromJsonElement(
            CouncilSynthesisStructuredOutputSerializer,
            providerWire
        )
        assertEquals(
            CouncilContractHashing.canonicalJson(json.parseToJsonElement(definition)),
            decoded.sealedTests.single().testDefinitionJson
        )
    }

    @Test
    fun `contextual synthesis wire separates promotion fixtures from typed general assertions`() {
        val node = LocalNodeKey("wire-node")
        val definitions = linkedMapOf(
            "promotion-test" to """{"input":{"value":1},"expectedOutput":{"ok":true}}""",
            "general-contains" to GeneralTestDefinitionV1Codec.encode(
                GeneralTestDefinitionV1(
                    GeneralTestTargetV1.FinalOutputText,
                    GeneralAssertionV1.TextContains("evidence", false)
                )
            ),
            "general-regex" to GeneralTestDefinitionV1Codec.encode(
                GeneralTestDefinitionV1(
                    GeneralTestTargetV1.OutputTextFile("result.md"),
                    GeneralAssertionV1.TextMatchesRe2("evidence\\s+table")
                )
            ),
            "general-exists" to GeneralTestDefinitionV1Codec.encode(
                GeneralTestDefinitionV1(
                    GeneralTestTargetV1.OutputJsonFile("result.json"),
                    GeneralAssertionV1.JsonPointerExists("/status")
                )
            ),
            "general-equals" to GeneralTestDefinitionV1Codec.encode(
                GeneralTestDefinitionV1(
                    GeneralTestTargetV1.OutputJsonFile("result.json"),
                    GeneralAssertionV1.JsonPointerEqualsString("/status", "OK")
                )
            ),
            "general-range" to GeneralTestDefinitionV1Codec.encode(
                GeneralTestDefinitionV1(
                    GeneralTestTargetV1.OutputJsonFile("result.json"),
                    GeneralAssertionV1.JsonPointerNumberRange("/score", 0.0, 1.0)
                )
            )
        )
        val promotionSchema = CouncilPromotionFixtureWireSchemaAuthority.fromSchemas(
            inputSchema = TypedToolSchemaCompiler.compileSchema(
                listOf(
                    TypedField(
                        name = "value",
                        type = TypedFieldType.INTEGER,
                        description = "Exact promotion input."
                    )
                )
            ),
            outputSchema = TypedToolSchemaCompiler.compileSchema(
                listOf(
                    TypedField(
                        name = "ok",
                        type = TypedFieldType.BOOLEAN,
                        description = "Exact promotion output."
                    )
                )
            )
        )
        val authority = CouncilSynthesisWireAuthority(
            entries = definitions.keys.map { testKey ->
                val kind = if (testKey == "promotion-test") {
                    CouncilSealedTestWireKind.PROMOTION
                } else {
                    CouncilSealedTestWireKind.GENERAL
                }
                CouncilSealedTestWireAuthority(
                    authorityKey = "authority-$testKey",
                    testKey = testKey,
                    nodeKey = node,
                    kind = kind,
                    promotionSchemaKey = promotionSchema.schemaKey.takeIf {
                        kind == CouncilSealedTestWireKind.PROMOTION
                    }
                )
            }.sortedBy(CouncilSealedTestWireAuthority::authorityKey),
            promotionSchemas = listOf(promotionSchema)
        )
        val synthesis = SynthesisOutput(
            stages = listOf(
                DisclosureStageDraft(
                    stageKey = "stage-zero",
                    title = "Foundations",
                    nodeKeys = setOf(node),
                    mandatoryNodeKeys = setOf(node),
                    evidenceCoverageThreshold = 1.0,
                    integrationReadinessThreshold = 1.0
                )
            ),
            sealedTests = definitions.map { (testKey, definition) ->
                SealedTestDraft(
                    testKey = testKey,
                    nodeKey = node,
                    acceptedArtifactTypes = setOf(ArtifactType.EVIDENCE_TABLE),
                    testDefinitionJson = definition,
                    commitmentHash = CouncilContractHashing.sha256(definition)
                )
            },
            rubricSchemaVersion = "wire-v1"
        )
        val serializer = CouncilSynthesisContextualOutputSerializer(authority)
        val providerWire = serializer.encodeProviderWire(synthesis).jsonObject
        val testsByAuthority = providerWire.getValue("sealedTests")
            .jsonArray
            .associateBy { it.jsonObject.getValue("authorityKey").jsonPrimitive.content }

        val promotion = testsByAuthority.getValue("authority-promotion-test").jsonObject
        assertEquals("PROMOTION", promotion.getValue("type").jsonPrimitive.content)
        assertEquals(
            setOf("input", "expectedOutput"),
            promotion.getValue("testDefinitionJson").jsonObject.keys
        )
        assertEquals(
            mapOf(
                "authority-general-contains" to "TEXT_CONTAINS",
                "authority-general-regex" to "TEXT_MATCHES_RE2",
                "authority-general-exists" to "JSON_POINTER_EXISTS",
                "authority-general-equals" to "JSON_POINTER_EQUALS_STRING",
                "authority-general-range" to "JSON_POINTER_NUMBER_RANGE"
            ),
            testsByAuthority
                .filterKeys { it != "authority-promotion-test" }
                .mapValues { (_, test) ->
                    test.jsonObject
                        .getValue("testDefinitionJson")
                        .jsonObject
                        .getValue("assertion")
                        .jsonObject
                        .getValue("type")
                        .jsonPrimitive
                        .content
                }
        )

        val normalized = assertIs<CouncilSynthesisProviderWireNormalization.Success>(
            serializer.normalizeProviderWire(providerWire)
        ).element
        val decoded = structuredJson.decodeFromJsonElement(serializer, normalized)
        assertEquals(
            definitions.mapValues { (_, definition) ->
                CouncilContractHashing.canonicalJson(json.parseToJsonElement(definition))
            },
            decoded.sealedTests.associate { it.testKey to it.testDefinitionJson }
        )

        val repeated = synthesis.copy(
            sealedTests = synthesis.sealedTests.dropLast(1) + synthesis.sealedTests.first()
        )
        val failure = assertFailsWith<SerializationException> {
            structuredJson.encodeToJsonElement(serializer, repeated)
        }
        assertTrue(
            failure.message.orEmpty().contains(
                "every Task-derived sealed-test authority exactly once"
            )
        )

        listOf("null", "{}", "\"narrative\"", "[]").forEach { invalidDefinition ->
            val invalid = synthesis.copy(
                sealedTests = synthesis.sealedTests.map { test ->
                    if (test.testKey == "general-contains") {
                        test.copy(
                            testDefinitionJson = invalidDefinition,
                            commitmentHash =
                                CouncilContractHashing.sha256(invalidDefinition)
                        )
                    } else {
                        test
                    }
                }
            )
            val invalidFailure = assertFailsWith<SerializationException> {
                serializer.encodeProviderWire(invalid)
            }
            assertTrue(
                invalidFailure.message.orEmpty().contains(
                    "must use GeneralTestDefinitionV1"
                )
            )
        }
    }

    @Test
    fun `persisted synthesis output retains its complete golden JSON bytes`() {
        val definition = """{"input":{"value":1.0},"expectedOutput":{"ok":true}}"""
        val synthesis = SynthesisOutput(
            atlasVersion = 1,
            parentAtlasHash = null,
            stages = listOf(
                DisclosureStageDraft(
                    stageKey = "stage-zero",
                    title = "Foundations",
                    nodeKeys = setOf(LocalNodeKey("node-a")),
                    mandatoryNodeKeys = setOf(LocalNodeKey("node-a")),
                    evidenceCoverageThreshold = 0.75,
                    integrationReadinessThreshold = 0.5
                )
            ),
            sealedTests = listOf(
                SealedTestDraft(
                    testKey = "test-a",
                    nodeKey = LocalNodeKey("node-a"),
                    acceptedArtifactTypes = setOf(ArtifactType.EVIDENCE_TABLE),
                    testDefinitionJson = definition,
                    commitmentHash = "a".repeat(64)
                )
            ),
            workerFieldNotes = listOf("worker-note"),
            graderFieldNotes = listOf("grader-note"),
            rubricSchemaVersion = "node-atlas-v1",
            declaredChange = null
        )

        assertEquals(
            """{"atlasVersion":1,"parentAtlasHash":null,"stages":[{"stageKey":"stage-zero","title":"Foundations","nodeKeys":["node-a"],"mandatoryNodeKeys":["node-a"],"evidenceCoverageThreshold":0.75,"integrationReadinessThreshold":0.5}],"sealedTests":[{"testKey":"test-a","nodeKey":"node-a","acceptedArtifactTypes":["EVIDENCE_TABLE"],"testDefinitionJson":"{\"input\":{\"value\":1.0},\"expectedOutput\":{\"ok\":true}}","commitmentHash":"${"a".repeat(64)}"}],"workerFieldNotes":["worker-note"],"graderFieldNotes":["grader-note"],"rubricSchemaVersion":"node-atlas-v1","declaredChange":null}""",
            json.encodeToString(SynthesisOutput.serializer(), synthesis)
        )
    }

    @Test
    fun `role output hashing normalizes node mapping set values`() {
        val first = roleOutputBundle()
        val nodeKey = LocalNodeKey("establish-evidence")
        val second = first.copy(
            kgArchitect = first.kgArchitect.copy(
                nodeMappings = mapOf(nodeKey to linkedSetOf("evidence", "claim"))
            )
        )

        assertEquals(
            CouncilContractHashing.hashRoleOutputs(first),
            CouncilContractHashing.hashRoleOutputs(second)
        )
    }

    @Test
    fun `deterministic ids are stable namespaced and collision safe across part boundaries`() {
        val key = LocalNodeKey("evidence-source")

        assertEquals(
            CouncilDeterministicIds.nodeId("run-1", key),
            CouncilDeterministicIds.nodeId("run-1", key)
        )
        assertTrue(CouncilDeterministicIds.nodeId("run-1", key).startsWith("node_"))
        assertNotEquals(
            CouncilDeterministicIds.create("node", "ab", "c"),
            CouncilDeterministicIds.create("node", "a", "bc")
        )
    }

    @Test
    fun `starting field hash excludes its own seal but commits every other field`() {
        val field = StartingField.default("Preserve  exact bytes.", runId = "run-1")
        val sealA = fixtureSeal(workerHash = "a".repeat(64))
        val sealB = fixtureSeal(workerHash = "b".repeat(64))

        assertEquals(
            CouncilContractHashing.hashStartingField(field.copy(seal = sealA)),
            CouncilContractHashing.hashStartingField(field.copy(seal = sealB))
        )
        assertNotEquals(
            CouncilContractHashing.hashStartingField(field),
            CouncilContractHashing.hashStartingField(field.copy(objective = "Preserve exact bytes."))
        )
    }

    @Test
    fun `compile results and reports are serializable and report hashes are deterministic`() {
        val report = consistencyReport()
        val invalid: StartingFieldCompileResult = StartingFieldCompileResult.Invalid(
            errors = listOf(
                FieldCompileError(
                    code = FieldCompileErrorCode.UNKNOWN_MODEL,
                    path = ContractPath(listOf("modelEcologist", "assignments", "0")),
                    message = "Model is not in the envelope",
                    responsibleCouncilRoles = setOf(Role.CouncilModelEcologist)
                )
            ),
            responsibleCouncilRoles = setOf(Role.CouncilModelEcologist),
            declaredTestCount = 2,
            compiledTestCount = 1,
            definitionHashesByTestId = mapOf("test-a" to "a".repeat(64))
        )

        assertEquals(
            CouncilContractHashing.hashConsistencyReport(report),
            CouncilContractHashing.hashConsistencyReport(
                report.copy(findings = report.findings.toList())
            )
        )
        val readback = assertIs<StartingFieldCompileResult.Invalid>(
            json.decodeFromString<StartingFieldCompileResult>(json.encodeToString(invalid))
        )
        assertEquals(2, readback.declaredTestCount)
        assertEquals(1, readback.compiledTestCount)
        assertEquals("a".repeat(64), readback.definitionHashesByTestId["test-a"])

        val legacyPayload = JsonObject(
            json.encodeToJsonElement(StartingFieldCompileResult.serializer(), invalid)
                .jsonObject
                .filterKeys {
                it !in setOf(
                    "declaredTestCount",
                    "compiledTestCount",
                    "definitionHashesByTestId"
                )
            }
        )
        val legacyReadback = assertIs<StartingFieldCompileResult.Invalid>(
            json.decodeFromJsonElement(
                StartingFieldCompileResult.serializer(),
                legacyPayload
            )
        )
        assertEquals(0, legacyReadback.declaredTestCount)
        assertEquals(0, legacyReadback.compiledTestCount)
        assertTrue(legacyReadback.definitionHashesByTestId.isEmpty())
    }

    @Test
    fun `field seal records its canonicalization contract and verifies typed payloads`() {
        val worker = StartingField.default("Objective", runId = "run-1")
        val grader = StartingField.default("Grade Objective", runId = "grader-run-1")
        val atlas = NodeAtlas(
            atlasId = CouncilDeterministicIds.atlasId("run-1"),
            runId = "run-1",
            version = 1,
            receiverContractHash = "0".repeat(64),
            rootNodeIds = emptySet(),
            nodes = emptyMap(),
            stageDefinitions = emptyList(),
            atlasHash = "3".repeat(64)
        )
        val graderAtlas = GraderAtlas(
            graderAtlasId = "grader-atlas",
            runId = "run-1",
            version = 1,
            workerAtlasHash = atlas.atlasHash,
            rootNodeIds = emptySet(),
            nodes = emptyMap(),
            atlasHash = "4".repeat(64)
        )
        val envelope = envelope(reverseSets = false)
        val report = consistencyReport()
        val seal = CouncilFieldSealing.createSeal(
            worker,
            grader,
            atlas,
            graderAtlas,
            envelope,
            report
        )

        assertEquals("SHA-256", seal.hashAlgorithm)
        assertEquals("evomolior-canonical-json-v1", seal.canonicalizationVersion)
        assertTrue(
            CouncilFieldSealing.verifySeal(
                seal,
                worker.copy(seal = seal),
                grader.copy(seal = seal),
                atlas,
                graderAtlas,
                envelope,
                report
            )
        )
        assertTrue(
            !CouncilFieldSealing.verifySeal(
                seal,
                worker.copy(objective = "Changed"),
                grader,
                atlas,
                graderAtlas,
                envelope,
                report
            )
        )
    }

    private fun envelope(reverseSets: Boolean): ExecutionEnvelope = ExecutionEnvelope(
        requiredWorkerGroupCount = InclusiveIntRange(2, 4),
        requiredGraderGroupCount = InclusiveIntRange(1, 2),
        permittedProfiles = insertionSet(
            reverseSets,
            GroupProfileType.Library,
            GroupProfileType.Factory
        ),
        availableModels = listOf(
            ModelCapabilityCard(
                modelId = "provider/model",
                familyId = "family",
                capabilities = insertionSet(reverseSets, "structured-output", "tools"),
                structuredOutputReliability = 0.9,
                toolCallReliability = 0.8,
                codeBuildReliability = 0.7,
                longContextSynthesisReliability = 0.85
            )
        ),
        maxAgentsPerGroupPerRound = 5,
        maxRounds = 4,
        perGenerationCreditPolicy = GenerationCreditPolicy(100.0, 125.0, 0.1),
        cumulativeCostCap = 1_000.0,
        allowedTopologyKinds = insertionSet(
            reverseSets,
            GroupTopologyKind.RING,
            GroupTopologyKind.SMALL_WORLD
        ),
        sourceAccessPolicy = SourceAccessPolicySummary(
            networkAccessEnabled = true,
            allowedDomains = insertionSet(reverseSets, "official.example", "papers.example")
        ),
        sandboxCapabilities = SandboxCapabilitySummary(
            capabilityIds = insertionSet(reverseSets, "shell", "browser"),
            maximumToolRuntimeSeconds = 120,
            maximumWorkspaceBytes = 10_000_000
        ),
        outputSpec = FinalOutputSpec(
            relativePath = "result.json",
            mediaType = "application/json",
            requiredSections = insertionSet(reverseSets, "answer", "evidence")
        ),
        atomicNodeEffortBound = 3,
        operatorOverrides = if (reverseSets) {
            linkedMapOf("model" to "provider/model", "mode" to "strict")
        } else {
            linkedMapOf("mode" to "strict", "model" to "provider/model")
        }
    )

    private fun roleOutputBundle(reverseSets: Boolean = false): CouncilRoleOutputBundle {
        val nodeKey = LocalNodeKey("establish-evidence")
        val node = LocalLimitationNode(
            key = nodeKey,
            kind = NodeKind.EVIDENCE,
            layer = 0,
            receiverObligationKey = "supported",
            limitationStatement = "Evidence is missing.",
            capabilityTarget = "Evidence is established.",
            atomicQuestion = "Which primary source establishes the claim?",
            estimatedEffort = 2,
            proofContract = ProofContract(
                method = ProofMethod.SOURCE_CORROBORATION,
                acceptanceCriteria = insertionSet(reverseSets, "current", "primary"),
                requiredEvidenceKinds = setOf(EvidenceKind.PRIMARY_SOURCE)
            ),
            deliverableContract = NodeDeliverableContract(
                acceptedArtifactTypes = setOf(ArtifactType.EVIDENCE_TABLE)
            ),
            stageKey = "foundations",
            revealMode = RevealMode.INITIAL,
            replicationPolicy = ReplicationPolicy(),
            capturePolicy = CapturePolicy(minimumGrade = 0.7),
            rewardSpec = RewardSpec(RewardClass.SMALL, credits = 2.0)
        )
        return CouncilRoleOutputBundle(
            taskAnatomist = TaskAnatomistOutput(
                receiverContract = ReceiverContractDraft(
                    receiverKey = "operator",
                    receiverDescription = "Operator",
                    situation = "Decision",
                    decisionOrUse = "Select",
                    whyNeeded = "Reduce uncertainty",
                    successOutcomes = listOf("Supported"),
                    unacceptableOutcomes = listOf("Invented"),
                    constraints = listOf("Auditable"),
                    evidenceProfile = EvidenceProfile()
                ),
                receiverObligations = mapOf("supported" to "Support claims"),
                nodes = listOf(node),
                rootNodeKeys = setOf(nodeKey),
                atomicityReport = listOf(NodeAtomicityAssessment(nodeKey, true, 2, "Bounded"))
            ),
            incentiveDesigner = IncentiveDesignerOutput(
                nodePolicies = listOf(
                    NodeIncentivePolicy(nodeKey, node.capturePolicy, node.rewardSpec)
                ),
                actionCosts = linkedMapOf("READ" to 0.0, "WRITE" to 1.0),
                scoringDimensions = listOf(
                    ScoringDimension("correctness", "Correctness", "Correctness", 1.0)
                ),
                maximumRewardCreditsPerRound = 10.0,
                maximumSharePerGroup = 0.6,
                repeatOwnershipDiminishingFactor = 0.8,
                mandatoryFrontierBonus = 0.15,
                independentValidationCredit = 0.5,
                graderRewardPolicy = "No outcome-contingent reward"
            ),
            ecologist = EcologistOutput(
                workerGroups = listOf(GroupFieldDesign("worker-1", GroupProfileType.Library)),
                graderGroups = listOf(GroupFieldDesign("grader-1", GroupProfileType.Monastery)),
                topologyKind = GroupTopologyKind.RING,
                topologyOrder = listOf("worker-1"),
                checkinRounds = listOf(1),
                leakIntervalRounds = 1,
                dispatchLagRounds = 1
            ),
            promptArchitect = PromptArchitectOutput(
                rolePrompts = mapOf(Role.Researcher to "Research."),
                informationBoundaries = listOf(
                    RoleInformationBoundary(
                        Role.Researcher,
                        insertionSet(reverseSets, "stage", "node"),
                        setOf("futureStages")
                    )
                ),
                outlierNoveltyMandate = "Explore alternatives.",
                deathCommitSchemaJson = "{}",
                legacyProtocolTemplateJson = "{}"
            ),
            kgArchitect = KgArchitectOutput(
                schemaVersion = "1",
                entityTypes = listOf(KgEntityTypeSpec("claim")),
                relationTypes = listOf(KgRelationTypeSpec("supports", setOf("claim"), setOf("claim"))),
                aliases = linkedMapOf("backs" to "supports"),
                claimLedgerSchemaJson = "{}",
                nodeMappings = mapOf(
                    nodeKey to insertionSet(reverseSets, "claim", "evidence")
                )
            ),
            modelEcologist = ModelEcologistOutput(
                assignments = listOf(RoleModelAssignment(Role.Researcher, "provider/model")),
                diversityRationale = "Distinct task fit.",
                degradedModePolicy = "Fail if no declared fallback exists."
            ),
            seedCurator = SeedCuratorOutput(
                primordialKb = emptyList(),
                primordialKgFacts = emptyList(),
                primordialTools = emptyList(),
                calibrationArtifacts = emptyList(),
                knownDeadEnds = emptyList(),
                stageZeroContext = listOf("Objective"),
                foundingNorms = "Cite evidence."
            ),
            synthesis = SynthesisOutput(
                stages = listOf(
                    DisclosureStageDraft(
                        stageKey = "foundations",
                        title = "Foundations",
                        nodeKeys = setOf(nodeKey),
                        mandatoryNodeKeys = setOf(nodeKey),
                        evidenceCoverageThreshold = 0.8,
                        integrationReadinessThreshold = 0.6
                    )
                ),
                sealedTests = listOf(
                    SealedTestDraft(
                        testKey = "test-1",
                        nodeKey = nodeKey,
                        acceptedArtifactTypes = setOf(ArtifactType.EVIDENCE_TABLE),
                        testDefinitionJson = "{}",
                        commitmentHash = "6".repeat(64)
                    )
                ),
                rubricSchemaVersion = "1"
            )
        )
    }

    private fun fixtureSeal(workerHash: String) = StartingFieldSeal(
        schemaVersion = "1",
        workerFieldHash = workerHash,
        graderFieldHash = "2".repeat(64),
        atlasHash = "3".repeat(64),
        envelopeHash = "4".repeat(64),
        consistencyReportHash = "5".repeat(64)
    )

    private fun consistencyReport() = FieldConsistencyReport(
        compilerVersion = "2",
        checksPerformed = listOf("objective", "envelope"),
        findings = listOf(
            ConsistencyFinding(
                code = "checked",
                severity = ConsistencySeverity.INFO,
                path = ContractPath(listOf("workerField", "objective")),
                message = "Exact objective preserved"
            )
        ),
        workerFieldHash = "1".repeat(64),
        graderFieldHash = "2".repeat(64),
        atlasHash = "3".repeat(64),
        envelopeHash = "4".repeat(64),
        roleOutputBundleHash = "5".repeat(64)
    )

    private fun <T> insertionSet(reverse: Boolean, first: T, second: T): Set<T> =
        if (reverse) linkedSetOf(second, first) else linkedSetOf(first, second)

    private fun CouncilRoleOutputBundle.withToolOpportunity(
        opportunity: ToolOpportunity
    ): CouncilRoleOutputBundle = copy(
        taskAnatomist = taskAnatomist.copy(
            nodes = taskAnatomist.nodes.mapIndexed { index, node ->
                if (index == 0) node.copy(toolOpportunity = opportunity) else node
            }
        )
    )

    private fun typedFieldOpportunity(reverseSets: Boolean): ToolOpportunity = ToolOpportunity(
        opportunityId = "typed-field-contract",
        opportunityType = ToolOpportunityType.CALCULATION,
        repeatedQuestionPattern = "Calculate one bounded result from typed inputs.",
        requiredInputs = listOf(
            TypedField(
                name = "mode",
                type = TypedFieldType.STRING,
                description = "Closed calculation mode.",
                enumValues = insertionSet(reverseSets, "bounded", "conservative"),
                validationRules = insertionSet(reverseSets, "known", "non-blank")
            ),
            TypedField(
                name = "samples",
                type = TypedFieldType.ARRAY,
                description = "Finite numeric samples.",
                itemType = TypedFieldType.NUMBER,
                minimumItems = 1,
                maximumItems = 64,
                validationRules = insertionSet(reverseSets, "finite", "non-empty"),
                physicalDimension = PhysicalDimension()
            )
        ),
        requiredOutputs = listOf(
            TypedField(
                name = "result",
                type = TypedFieldType.NUMBER,
                description = "Calculated result.",
                physicalDimension = PhysicalDimension()
            )
        ),
        validationStrategy = "Run exact bounded examples.",
        expectedReuseNodeIds = setOf(NodeId("downstream-node")),
        preferredImplementationMode = ToolImplementationMode.PYTHON,
        behavior = ToolBehavior.DETERMINISTIC,
        promotionTestIds = setOf("typed-field-test"),
        maximumRuntimeMillis = 1_000,
        maximumOutputBytes = 4_096
    )

    private fun formulaOpportunity(reverseSets: Boolean): ToolOpportunity =
        typedFieldOpportunity(reverseSets).copy(
            opportunityType = ToolOpportunityType.FORMULA,
            expectedReuseNodeIds = insertionSet(
                reverseSets,
                NodeId("downstream-node"),
                NodeId("downstream-node-b")
            ),
            preferredImplementationMode = ToolImplementationMode.FORMULA_DSL,
            requiredCapabilities = insertionSet(reverseSets, "shell", "filesystem"),
            promotionTestIds = insertionSet(
                reverseSets,
                "typed-field-test",
                "typed-field-test-b"
            ),
            formulaDslReferenceSemantics =
                FormulaDslReferenceSemantics.DIMENSIONAL_ANALYSIS_V1,
            formulaDslReferenceProgram = ToolDslProgram(
                kind = ToolDslKind.FORMULA,
                outputs = listOf(
                    ToolDslOutput(
                        field = "result",
                        expression = ToolDslExpression.Literal(JsonPrimitive(1))
                    )
                )
            )
        )

    private fun simulationOpportunity(stochastic: Boolean): ToolOpportunity {
        val seedField = TypedField(
            name = "random_seed",
            type = TypedFieldType.INTEGER,
            description = "Reproducible simulation seed."
        )
        return ToolOpportunity(
            opportunityId = if (stochastic) {
                "stochastic-simulation"
            } else {
                "deterministic-simulation"
            },
            opportunityType = ToolOpportunityType.SIMULATION,
            repeatedQuestionPattern = "Run one bounded simulation scenario.",
            requiredInputs = listOf(
                TypedField(
                    name = "scenario",
                    type = TypedFieldType.OBJECT,
                    description = "Bounded simulation scenario.",
                    objectFields = setOf(
                        TypedField(
                            name = "iterations",
                            type = TypedFieldType.INTEGER,
                            description = "Bounded simulation iteration count."
                        )
                    )
                )
            ) + listOfNotNull(seedField.takeIf { stochastic }),
            requiredOutputs = listOf(
                TypedField(
                    name = "result_summary",
                    type = TypedFieldType.STRING,
                    description = "Bounded result summary."
                ),
                TypedField(
                    name = "uncertainty",
                    type = TypedFieldType.STRING,
                    description = "Explicit uncertainty."
                )
            ),
            validationStrategy = "Run exact bounded simulation cases.",
            expectedReuseNodeIds = setOf(NodeId("simulation-reuse")),
            preferredImplementationMode = ToolImplementationMode.SIMULATION_DSL,
            behavior = if (stochastic) {
                ToolBehavior.STOCHASTIC
            } else {
                ToolBehavior.DETERMINISTIC
            },
            promotionTestIds = setOf("simulation-test"),
            maximumRuntimeMillis = 2_000,
            maximumOutputBytes = 8_192,
            assumptions = listOf("The scenario contains all required model inputs."),
            simulationRecordBindings = SimulationRecordBindings(
                resultSummaryOutputField = "result_summary",
                uncertaintyOutputField = "uncertainty",
                randomSeedInputField = "random_seed".takeIf { stochastic }
            )
        )
    }
}
