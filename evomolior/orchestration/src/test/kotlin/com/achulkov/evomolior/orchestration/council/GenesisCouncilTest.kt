package com.achulkov.evomolior.orchestration.council

import com.achulkov.evomolior.core.atlas.ArtifactType
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.atlas.ToolBehavior
import com.achulkov.evomolior.core.atlas.ToolImplementationMode
import com.achulkov.evomolior.core.atlas.ToolOpportunity
import com.achulkov.evomolior.core.atlas.ToolOpportunityType
import com.achulkov.evomolior.core.atlas.TypedField
import com.achulkov.evomolior.core.atlas.TypedFieldType
import com.achulkov.evomolior.core.atlas.TypedToolSchemaCompiler
import com.achulkov.evomolior.core.council.CouncilContractHashing
import com.achulkov.evomolior.core.council.CouncilPromotionFixtureWireSchemaAuthority
import com.achulkov.evomolior.core.council.CouncilRoleExecutionRequest
import com.achulkov.evomolior.core.council.CouncilRoleOutputBundle
import com.achulkov.evomolior.core.council.CouncilSealedTestWireAuthority
import com.achulkov.evomolior.core.council.CouncilSealedTestWireKind
import com.achulkov.evomolior.core.council.CouncilSynthesisWireAuthority
import com.achulkov.evomolior.core.council.EcologistOutput
import com.achulkov.evomolior.core.council.FieldCompileErrorCode
import com.achulkov.evomolior.core.council.GeneralAssertionV1
import com.achulkov.evomolior.core.council.GeneralTestDefinitionV1
import com.achulkov.evomolior.core.council.GeneralTestDefinitionV1Codec
import com.achulkov.evomolior.core.council.GeneralTestTargetV1
import com.achulkov.evomolior.core.council.IncentiveDesignerOutput
import com.achulkov.evomolior.core.council.KgArchitectOutput
import com.achulkov.evomolior.core.council.LocalNodeKey
import com.achulkov.evomolior.core.council.ModelEcologistOutput
import com.achulkov.evomolior.core.council.NodeAtomicityAssessment
import com.achulkov.evomolior.core.council.RoleModelAssignment
import com.achulkov.evomolior.core.council.PromptArchitectOutput
import com.achulkov.evomolior.core.council.SeedCuratorOutput
import com.achulkov.evomolior.core.council.SealedTestDraft
import com.achulkov.evomolior.core.council.StartingFieldCompileResult
import com.achulkov.evomolior.core.council.SynthesisOutput
import com.achulkov.evomolior.core.council.TaskAnatomistOutput
import com.achulkov.evomolior.core.config.AcceptanceProfile
import com.achulkov.evomolior.core.model.Role
import com.achulkov.evomolior.core.serialization.CouncilSynthesisContextualOutputSerializer
import com.achulkov.evomolior.core.serialization.TaskAnatomistContextualOutputSerializer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GenesisCouncilTest {
    private val fixtures = StartingFieldCompilerTest()
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
    }

    @Test
    fun `strict codec rejects fences prose and missing defaulted fields`() {
        val outputs = fixtures.outputs()
        val valid = json.encodeToString(
            EcologistOutput.serializer(),
            outputs.ecologist
        )
        assertIs<CouncilRoleDecodeResult.Success>(
            CouncilRoleOutputCodec.decode(Role.CouncilEcologist, valid)
        )
        listOf(
            "```json\n$valid\n```",
            "provider prose\n$valid",
            "$valid\nprovider prose",
            "$valid\n$valid"
        ).forEach { malformed ->
            assertIs<CouncilRoleDecodeResult.Failure>(
                CouncilRoleOutputCodec.decode(Role.CouncilEcologist, malformed)
            )
        }

        val task = json.encodeToJsonElement(
            TaskAnatomistOutput.serializer(),
            outputs.taskAnatomist
        ).jsonObject
        val missingFailure = assertIs<CouncilRoleDecodeResult.Failure>(
            CouncilRoleOutputCodec.decode(
                Role.CouncilTaskAnatomist,
                JsonObject(task - "ambiguityBranches").toString()
            )
        )
        assertEquals(
            "CouncilTaskAnatomist.ambiguityBranches",
            missingFailure.error.path.toString()
        )
        assertEquals("Required field is missing", missingFailure.error.message)

        val invalid = assertIs<CouncilRoleDecodeResult.Failure>(
            CouncilRoleOutputCodec.decode(
                Role.CouncilEcologist,
                """{"workerGroups":[],"unexpected":true}"""
            )
        )
        assertEquals(FieldCompileErrorCode.MALFORMED_ROLE_OUTPUT, invalid.error.code)
        assertEquals(
            "CouncilEcologist.unexpected",
            invalid.error.path.toString()
        )
        assertEquals(setOf(Role.CouncilEcologist), invalid.error.responsibleCouncilRoles)
    }

    @Test
    fun `strict codec enforces conditional typed field branches`() {
        val outputs = fixtures.outputs()
        val source = outputs.taskAnatomist.nodes.first()
        val target = outputs.taskAnatomist.nodes.last()
        val opportunity = ToolOpportunity(
            opportunityId = "typed-field-codec",
            opportunityType = ToolOpportunityType.CALCULATION,
            repeatedQuestionPattern = "Calculate one bounded result from numeric samples.",
            requiredInputs = listOf(
                TypedField(
                    name = "samples",
                    type = TypedFieldType.ARRAY,
                    description = "Finite numeric samples.",
                    itemType = TypedFieldType.NUMBER,
                    minimumItems = 1,
                    maximumItems = 64
                )
            ),
            requiredOutputs = listOf(
                TypedField(
                    name = "result",
                    type = TypedFieldType.NUMBER,
                    description = "Calculated result."
                )
            ),
            validationStrategy = "Run exact bounded examples.",
            expectedReuseNodeIds = setOf(NodeId(target.key.value)),
            preferredImplementationMode = ToolImplementationMode.PYTHON,
            behavior = ToolBehavior.DETERMINISTIC,
            promotionTestIds = setOf("typed-field-codec-test"),
            maximumRuntimeMillis = 1_000,
            maximumOutputBytes = 4_096
        )
        val anatomy = outputs.taskAnatomist.copy(
            nodes = outputs.taskAnatomist.nodes.mapIndexed { index, node ->
                if (index == 0) source.copy(toolOpportunity = opportunity) else node
            }
        )
        val encoded = json.encodeToJsonElement(
            TaskAnatomistOutput.serializer(),
            anatomy
        ).jsonObject

        assertIs<CouncilRoleDecodeResult.Success>(
            CouncilRoleOutputCodec.decode(Role.CouncilTaskAnatomist, encoded.toString())
        )

        val nodes = encoded.getValue("nodes").jsonArray.toMutableList()
        val sourceNode = nodes.first().jsonObject
        val tool = sourceNode.getValue("toolOpportunity").jsonObject
        val implementation = tool.getValue("implementation").jsonObject
        val leakedTool = JsonObject(
            tool + (
                "implementation" to JsonObject(
                    implementation + (
                        "formulaDslReferenceSemantics" to
                            JsonPrimitive("DIMENSIONAL_ANALYSIS_V1")
                        )
                )
                )
        )
        val leakedNodes = nodes.toMutableList()
        leakedNodes[0] = JsonObject(sourceNode + ("toolOpportunity" to leakedTool))
        val leaked = JsonObject(encoded + ("nodes" to JsonArray(leakedNodes)))
        val leakedFailure = assertIs<CouncilRoleDecodeResult.Failure>(
            CouncilRoleOutputCodec.decode(Role.CouncilTaskAnatomist, leaked.toString())
        )
        assertEquals(FieldCompileErrorCode.MALFORMED_ROLE_OUTPUT, leakedFailure.error.code)
        assertEquals(
            listOf(
                Role.CouncilTaskAnatomist.name,
                "nodes",
                "0",
                "toolOpportunity",
                "implementation",
                "formulaDslReferenceSemantics"
            ),
            leakedFailure.error.path.segments
        )
        assertEquals("Unknown field", leakedFailure.error.message)

        val missingImplementationType = JsonObject(implementation - "type")
        val missingTypeTool = JsonObject(
            tool + ("implementation" to missingImplementationType)
        )
        val missingTypeNodes = nodes.toMutableList()
        missingTypeNodes[0] = JsonObject(
            sourceNode + ("toolOpportunity" to missingTypeTool)
        )
        val missingType = JsonObject(encoded + ("nodes" to JsonArray(missingTypeNodes)))
        val missingTypeFailure = assertIs<CouncilRoleDecodeResult.Failure>(
            CouncilRoleOutputCodec.decode(Role.CouncilTaskAnatomist, missingType.toString())
        )
        assertEquals(
            listOf(
                Role.CouncilTaskAnatomist.name,
                "nodes",
                "0",
                "toolOpportunity",
                "implementation",
                "type"
            ),
            missingTypeFailure.error.path.segments
        )
        assertTrue(missingTypeFailure.error.message.contains("discriminator"))

        val invalidSimulationConfiguration = JsonObject(
            mapOf(
                "type" to JsonPrimitive("UNSUPPORTED"),
                "resultSummaryOutputField" to JsonPrimitive("result_summary"),
                "uncertaintyOutputField" to JsonPrimitive("uncertainty")
            )
        )
        val invalidSimulationExecution = JsonObject(
            mapOf(
                "type" to JsonPrimitive("SIMULATION"),
                "simulationConfiguration" to invalidSimulationConfiguration
            )
        )
        val invalidSimulationTool = JsonObject(
            tool + ("execution" to invalidSimulationExecution)
        )
        val invalidSimulationNodes = nodes.toMutableList()
        invalidSimulationNodes[0] = JsonObject(
            sourceNode + ("toolOpportunity" to invalidSimulationTool)
        )
        val invalidSimulation = JsonObject(
            encoded + ("nodes" to JsonArray(invalidSimulationNodes))
        )
        val invalidSimulationFailure = assertIs<CouncilRoleDecodeResult.Failure>(
            CouncilRoleOutputCodec.decode(
                Role.CouncilTaskAnatomist,
                invalidSimulation.toString()
            )
        )
        assertEquals(
            listOf(
                Role.CouncilTaskAnatomist.name,
                "nodes",
                "0",
                "toolOpportunity",
                "execution",
                "simulationConfiguration",
                "type"
            ),
            invalidSimulationFailure.error.path.segments
        )
        assertTrue(invalidSimulationFailure.error.message.contains("UNSUPPORTED"))

        val inputs = tool.getValue("requiredInputs").jsonArray.toMutableList()
        inputs[0] = JsonObject(inputs.first().jsonObject - "items")
        val malformedTool = JsonObject(tool + ("requiredInputs" to JsonArray(inputs)))
        nodes[0] = JsonObject(sourceNode + ("toolOpportunity" to malformedTool))
        val malformed = JsonObject(encoded + ("nodes" to JsonArray(nodes)))

        val failure = assertIs<CouncilRoleDecodeResult.Failure>(
            CouncilRoleOutputCodec.decode(Role.CouncilTaskAnatomist, malformed.toString())
        )
        assertEquals(FieldCompileErrorCode.MALFORMED_ROLE_OUTPUT, failure.error.code)
        assertEquals(
            listOf(
                Role.CouncilTaskAnatomist.name,
                "nodes",
                "0",
                "toolOpportunity",
                "requiredInputs",
                "0",
                "items"
            ),
            failure.error.path.segments
        )
        assertEquals("Required field is missing", failure.error.message)

        val opaqueInput = JsonObject(
            mapOf(
                "type" to JsonPrimitive("JSON"),
                "name" to JsonPrimitive("samples"),
                "description" to JsonPrimitive("Opaque samples."),
                "required" to JsonPrimitive(true),
                "validationRules" to JsonArray(emptyList())
            )
        )
        val opaqueInputs = tool.getValue("requiredInputs").jsonArray.toMutableList()
        opaqueInputs[0] = opaqueInput
        val opaqueTool = JsonObject(tool + ("requiredInputs" to JsonArray(opaqueInputs)))
        val opaqueNodes = encoded.getValue("nodes").jsonArray.toMutableList()
        opaqueNodes[0] = JsonObject(sourceNode + ("toolOpportunity" to opaqueTool))
        val opaque = JsonObject(encoded + ("nodes" to JsonArray(opaqueNodes)))
        val opaqueFailure = assertIs<CouncilRoleDecodeResult.Failure>(
            CouncilRoleOutputCodec.decode(Role.CouncilTaskAnatomist, opaque.toString())
        )
        assertEquals(
            listOf(
                Role.CouncilTaskAnatomist.name,
                "nodes",
                "0",
                "toolOpportunity",
                "requiredInputs",
                "0",
                "type"
            ),
            opaqueFailure.error.path.segments
        )
        assertTrue(opaqueFailure.error.message.contains("JSON"))
    }

    @Test
    fun `Task codec enforces smoke and full contextual provider wires`() {
        val outputs = fixtures.outputs()
        val smokeSerializer = TaskAnatomistContextualOutputSerializer(
            AcceptanceProfile.RUNTIME_USABILITY_SMOKE
        )
        val smokeWire = smokeSerializer.encodeProviderWire(outputs.taskAnatomist).jsonObject
        assertIs<CouncilRoleDecodeResult.Success>(
            CouncilRoleOutputCodec.decode(
                role = Role.CouncilTaskAnatomist,
                rawOutput = smokeWire.toString(),
                acceptanceProfile = AcceptanceProfile.RUNTIME_USABILITY_SMOKE
            )
        )
        val smokeNodes = smokeWire.getValue("nodes").jsonArray
        val invalidSmoke = JsonObject(
            smokeWire + (
                "nodes" to JsonArray(
                    listOf(
                        JsonObject(
                            smokeNodes.first().jsonObject +
                                ("toolOpportunity" to JsonObject(emptyMap()))
                        )
                    ) + smokeNodes.drop(1)
                )
            )
        )
        assertIs<CouncilRoleDecodeResult.Failure>(
            CouncilRoleOutputCodec.decode(
                role = Role.CouncilTaskAnatomist,
                rawOutput = invalidSmoke.toString(),
                acceptanceProfile = AcceptanceProfile.RUNTIME_USABILITY_SMOKE
            )
        )

        val origin = outputs.taskAnatomist.nodes.first()
        val target = outputs.taskAnatomist.nodes.last()
        val fullTask = outputs.taskAnatomist.copy(
            nodes = listOf(
                origin.copy(toolOpportunity = promotionOpportunity(origin, target)),
                target
            )
        )
        val fullSerializer = TaskAnatomistContextualOutputSerializer(
            AcceptanceProfile.APPENDIX_B_FULL
        )
        val fullWire = fullSerializer.encodeProviderWire(fullTask).jsonObject
        assertIs<CouncilRoleDecodeResult.Success>(
            CouncilRoleOutputCodec.decode(
                role = Role.CouncilTaskAnatomist,
                rawOutput = fullWire.toString(),
                acceptanceProfile = AcceptanceProfile.APPENDIX_B_FULL
            )
        )
        val emptyOpportunityPartition = JsonObject(
            fullWire + ("nodesWithToolOpportunity" to JsonArray(emptyList()))
        )
        assertIs<CouncilRoleDecodeResult.Failure>(
            CouncilRoleOutputCodec.decode(
                role = Role.CouncilTaskAnatomist,
                rawOutput = emptyOpportunityPartition.toString(),
                acceptanceProfile = AcceptanceProfile.APPENDIX_B_FULL
            )
        )
    }

    @Test
    fun `strict synthesis codec accepts typed GENERAL and rejects embedded JSON strings`() {
        val outputs = fixtures.outputs()
        val synthesis = outputs.synthesis
        val authority = CouncilSynthesisWireAuthority.from(outputs.taskAnatomist)
        val encoded = CouncilSynthesisContextualOutputSerializer(authority)
            .encodeProviderWire(synthesis)
            .jsonObject
        val missingAuthority = assertFailsWith<IllegalArgumentException> {
            CouncilRoleOutputCodec.decode(Role.CouncilSynthesis, encoded.toString())
        }
        assertTrue(
            missingAuthority.message.orEmpty().contains(
                "Task-derived sealed-test authority"
            )
        )
        val decoded = assertIs<CouncilRoleDecodeResult.Success>(
            CouncilRoleOutputCodec.decode(
                Role.CouncilSynthesis,
                encoded.toString(),
                authority
            )
        )
        val decodedSynthesis =
            assertIs<DecodedCouncilRoleOutput.Synthesis>(decoded.output).value
        assertEquals(
            synthesis.sealedTests.map { test ->
                CouncilContractHashing.canonicalJson(
                    json.parseToJsonElement(test.testDefinitionJson)
                )
            },
            decodedSynthesis.sealedTests.map { it.testDefinitionJson }
        )

        val tests = encoded.getValue("sealedTests").jsonArray.toMutableList()
        tests[0] = JsonObject(
            tests.first().jsonObject + ("testDefinitionJson" to JsonPrimitive("{}"))
        )
        val malformed = JsonObject(encoded + ("sealedTests" to JsonArray(tests)))
        val failure = assertIs<CouncilRoleDecodeResult.Failure>(
            CouncilRoleOutputCodec.decode(
                Role.CouncilSynthesis,
                malformed.toString(),
                authority
            )
        )
        assertEquals(
            listOf(
                Role.CouncilSynthesis.name,
                "sealedTests",
                "0",
                "testDefinitionJson"
            ),
            failure.error.path.segments
        )
        assertEquals("Expected a JSON object", failure.error.message)
    }

    @Test
    fun `strict synthesis codec rejects promotion authority under the general branch`() {
        val outputs = fixtures.outputs()
        val origin = outputs.taskAnatomist.nodes.first()
        val opportunity = promotionOpportunity(
            origin,
            outputs.taskAnatomist.nodes.last()
        )
        val task = outputs.taskAnatomist.copy(
            nodes = outputs.taskAnatomist.nodes.map { node ->
                if (node.key == origin.key) node.copy(toolOpportunity = opportunity) else node
            }
        )
        val authority = CouncilSynthesisWireAuthority.from(task)
        val promotion = authority.entries.single {
            it.testKey in opportunity.promotionTestIds
        }
        val generalDefinition = json.parseToJsonElement(
            GeneralTestDefinitionV1Codec.encode(
                GeneralTestDefinitionV1(
                    target = GeneralTestTargetV1.FinalOutputText,
                    assertion = GeneralAssertionV1.TextContains(
                        expected = "accepted",
                        caseSensitive = false
                    )
                )
            )
        )
        val validDefinition =
            """{"input":{"enabled":true},"expectedOutput":{"accepted":true}}"""
        val validSynthesis = outputs.synthesis.copy(
            sealedTests = outputs.synthesis.sealedTests.map { test ->
                if (test.testKey == promotion.testKey && test.nodeKey == promotion.nodeKey) {
                    test.copy(
                        testDefinitionJson = validDefinition,
                        commitmentHash = CouncilContractHashing.sha256(validDefinition)
                    )
                } else {
                    test
                }
            }
        )
        val validContextual = CouncilSynthesisContextualOutputSerializer(authority)
            .encodeProviderWire(validSynthesis)
            .jsonObject
        val tests = validContextual.getValue("sealedTests").jsonArray.toMutableList()
        val promotionIndex = tests.indexOfFirst {
            it.jsonObject.getValue("authorityKey").jsonPrimitive.content ==
                promotion.authorityKey
        }
        val promotionTest = tests[promotionIndex].jsonObject
        tests[promotionIndex] = JsonObject(
            promotionTest +
                mapOf(
                    "type" to JsonPrimitive("GENERAL"),
                    "testDefinitionJson" to generalDefinition
                )
        )

        val failure = assertIs<CouncilRoleDecodeResult.Failure>(
            CouncilRoleOutputCodec.decode(
                role = Role.CouncilSynthesis,
                rawOutput = JsonObject(
                    validContextual + ("sealedTests" to JsonArray(tests))
                ).toString(),
                synthesisAuthority = authority
            )
        )

        assertEquals(
            listOf(
                Role.CouncilSynthesis.name,
                "sealedTests",
                promotionIndex.toString(),
                "type"
            ),
            failure.error.path.segments
        )
        assertEquals(
            "Sealed-test authority requires PROMOTION wire shape",
            failure.error.message
        )
    }

    @Test
    fun `strict synthesis codec rejects all v69 promotion fixture wrong roots together`() {
        val outputs = fixtures.outputs()
        val origin = outputs.taskAnatomist.nodes.first()
        val opportunity = promotionOpportunity(
            origin,
            outputs.taskAnatomist.nodes.last()
        )
        val task = outputs.taskAnatomist.copy(
            nodes = outputs.taskAnatomist.nodes.map { node ->
                if (node.key == origin.key) node.copy(toolOpportunity = opportunity) else node
            }
        )
        val authority = CouncilSynthesisWireAuthority.from(task)
        val promotion = authority.entries.single {
            it.testKey in opportunity.promotionTestIds
        }
        val validDefinition =
            """{"input":{"enabled":true},"expectedOutput":{"accepted":true}}"""
        val synthesis = outputs.synthesis.copy(
            sealedTests = outputs.synthesis.sealedTests.map { test ->
                if (test.testKey == promotion.testKey && test.nodeKey == promotion.nodeKey) {
                    test.copy(
                        testDefinitionJson = validDefinition,
                        commitmentHash = CouncilContractHashing.sha256(validDefinition)
                    )
                } else {
                    test
                }
            }
        )
        val encoded = CouncilSynthesisContextualOutputSerializer(authority)
            .encodeProviderWire(synthesis)
            .jsonObject
        assertIs<CouncilRoleDecodeResult.Success>(
            CouncilRoleOutputCodec.decode(
                Role.CouncilSynthesis,
                encoded.toString(),
                authority
            )
        )

        val tests = encoded.getValue("sealedTests").jsonArray.toMutableList()
        val promotionIndex = tests.indexOfFirst {
            it.jsonObject.getValue("authorityKey").jsonPrimitive.content ==
                promotion.authorityKey
        }
        val promotionTest = tests[promotionIndex].jsonObject
        val definition = promotionTest.getValue("testDefinitionJson").jsonObject
        val wrongDefinition = JsonObject(
            definition + mapOf(
                "input" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "value" to JsonPrimitive("wrong-root")
                    )
                ),
                "expectedOutput" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("array"),
                        "values" to JsonArray(emptyList())
                    )
                )
            )
        )
        tests[promotionIndex] = JsonObject(
            promotionTest + ("testDefinitionJson" to wrongDefinition)
        )

        val failure = assertIs<CouncilRoleDecodeResult.Failure>(
            CouncilRoleOutputCodec.decode(
                role = Role.CouncilSynthesis,
                rawOutput = JsonObject(
                    encoded + ("sealedTests" to JsonArray(tests))
                ).toString(),
                synthesisAuthority = authority
            )
        )

        assertEquals(
            listOf(Role.CouncilSynthesis.name, "sealedTests"),
            failure.error.path.segments
        )
        assertTrue(
            failure.error.message.contains(
                "sealedTests.$promotionIndex.testDefinitionJson.input: wrong_type_root"
            )
        )
        assertTrue(
            failure.error.message.contains(
                "sealedTests.$promotionIndex.testDefinitionJson.expectedOutput: wrong_type_root"
            )
        )
    }

    @Test
    fun `strict synthesis codec matches nested exact promotion provider wire`() {
        val schema = CouncilPromotionFixtureWireSchemaAuthority.fromSchemas(
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
                                        description = "Optional label.",
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
        val nodeKey = LocalNodeKey("nested-promotion-node")
        val authority = CouncilSynthesisWireAuthority(
            entries = listOf(
                CouncilSealedTestWireAuthority(
                    authorityKey = "nested-promotion-authority",
                    testKey = "nested-promotion-test",
                    nodeKey = nodeKey,
                    kind = CouncilSealedTestWireKind.PROMOTION,
                    promotionSchemaKey = schema.schemaKey
                )
            ),
            promotionSchemas = listOf(schema)
        )
        val definition =
            """{"input":{"scenario":{"mode":"SAFE","samples":[{"reading":1.5},{"label":"A","reading":2.0}]}},"expectedOutput":{"status":"OK"}}"""
        val synthesis = fixtures.outputs().synthesis.copy(
            sealedTests = listOf(
                SealedTestDraft(
                    testKey = "nested-promotion-test",
                    nodeKey = nodeKey,
                    acceptedArtifactTypes = setOf(ArtifactType.EVIDENCE_TABLE),
                    testDefinitionJson = definition,
                    commitmentHash = CouncilContractHashing.sha256(definition)
                )
            )
        )
        val serializer = CouncilSynthesisContextualOutputSerializer(authority)
        val providerWire = serializer.encodeProviderWire(synthesis).jsonObject
        val valid = assertIs<CouncilRoleDecodeResult.Success>(
            CouncilRoleOutputCodec.decode(
                Role.CouncilSynthesis,
                providerWire.toString(),
                authority
            )
        )
        assertEquals(
            CouncilContractHashing.canonicalJson(json.parseToJsonElement(definition)),
            assertIs<DecodedCouncilRoleOutput.Synthesis>(valid.output)
                .value
                .sealedTests
                .single()
                .testDefinitionJson
        )

        val input = providerWire.promotionFixtureComponent("input")
        val inputEntries = input.getValue("entries").jsonObject
        assertEquals(
            "ABSENT",
            inputEntries.getValue("note")
                .jsonObject
                .getValue("state")
                .jsonPrimitive
                .content
        )
        val sampleValues = inputEntries.getValue("scenario")
            .jsonObject
            .getValue("entries")
            .jsonObject
            .getValue("samples")
            .jsonObject
            .getValue("values")
            .jsonArray
        assertEquals(
            "ABSENT",
            sampleValues.first()
                .jsonObject
                .getValue("entries")
                .jsonObject
                .getValue("label")
                .jsonObject
                .getValue("state")
                .jsonPrimitive
                .content
        )
        assertEquals(
            "PRESENT",
            sampleValues.last()
                .jsonObject
                .getValue("entries")
                .jsonObject
                .getValue("label")
                .jsonObject
                .getValue("state")
                .jsonPrimitive
                .content
        )

        fun failure(wire: JsonObject): CouncilRoleDecodeResult.Failure =
            assertIs(
                CouncilRoleOutputCodec.decode(
                    Role.CouncilSynthesis,
                    wire.toString(),
                    authority
                )
            )

        val genericBypass = providerWire.withPromotionFixtureComponent("input") { current ->
            JsonObject(current + ("entries" to JsonArray(emptyList())))
        }
        assertEquals(
            listOf(
                Role.CouncilSynthesis.name,
                "sealedTests",
                "0",
                "testDefinitionJson",
                "input",
                "entries"
            ),
            failure(genericBypass).error.path.segments
        )

        val missing = providerWire.withPromotionFixtureComponent("input") { current ->
            current.updateObject(listOf("entries", "scenario", "entries")) {
                JsonObject(it - "mode")
            }
        }
        assertTrue(failure(missing).error.message.contains("property is missing"))

        val additional = providerWire.withPromotionFixtureComponent("input") { current ->
            current.updateObject(listOf("entries", "scenario", "entries")) {
                JsonObject(it + ("rogue" to JsonPrimitive("unexpected")))
            }
        }
        assertTrue(failure(additional).error.message.contains("Additional promotion object"))

        val enumMismatch = providerWire.withPromotionFixtureComponent("input") { current ->
            current.updateObject(listOf("entries", "scenario", "entries", "mode")) {
                JsonObject(it + ("value" to JsonPrimitive("UNSAFE")))
            }
        }
        assertTrue(
            failure(enumMismatch).error.message.contains(
                "enum_mismatch_root.scenario.mode"
            )
        )

        val cardinality = providerWire.withPromotionFixtureComponent("input") { current ->
            current.updateObject(listOf("entries", "scenario", "entries", "samples")) {
                JsonObject(it + ("values" to JsonArray(emptyList())))
            }
        }
        assertTrue(
            failure(cardinality).error.message.contains(
                "too_few_items_root.scenario.samples"
            )
        )

        val malformedWrapper = providerWire.withPromotionFixtureComponent("input") { current ->
            current.updateObject(listOf("entries")) {
                JsonObject(
                    it + (
                        "note" to JsonObject(
                            mapOf("state" to JsonPrimitive("UNKNOWN"))
                        )
                    )
                )
            }
        }
        assertEquals(
            listOf(
                Role.CouncilSynthesis.name,
                "sealedTests",
                "0",
                "testDefinitionJson",
                "input",
                "entries",
                "note",
                "state"
            ),
            failure(malformedWrapper).error.path.segments
        )
    }

    @Test
    fun `strict synthesis codec rejects repeated authority with an omitted peer`() {
        val outputs = fixtures.outputs()
        val authority = CouncilSynthesisWireAuthority.from(outputs.taskAnatomist)
        val encoded = CouncilSynthesisContextualOutputSerializer(authority)
            .encodeProviderWire(outputs.synthesis)
            .jsonObject
        val tests = encoded.getValue("sealedTests").jsonArray.toMutableList()
        val repeatedAuthority = tests.first()
            .jsonObject
            .getValue("authorityKey")
        tests[1] = JsonObject(
            tests[1].jsonObject + ("authorityKey" to repeatedAuthority)
        )

        val failure = assertIs<CouncilRoleDecodeResult.Failure>(
            CouncilRoleOutputCodec.decode(
                role = Role.CouncilSynthesis,
                rawOutput = JsonObject(
                    encoded + ("sealedTests" to JsonArray(tests))
                ).toString(),
                synthesisAuthority = authority
            )
        )

        assertEquals(
            listOf(Role.CouncilSynthesis.name, "sealedTests", "1", "authorityKey"),
            failure.error.path.segments
        )
        assertEquals(
            "Task-derived sealed-test authority is repeated",
            failure.error.message
        )
    }

    @Test
    fun `malformed role retries only that role and compiles`() = runTest {
        val outputs = fixtures.outputs()
        val calls = ConcurrentHashMap<Role, Int>()
        val observedContexts = ConcurrentHashMap<Role, String>()
        val council = GenesisCouncil(
            objective = "Objective",
            executionEnvelope = fixtures.envelope()
        )

        val result = council.runCompiled(
            runId = "selective-parse-retry",
            agentRunner = { request ->
                val role = request.role
                val context = request.contextJson
                val count = calls.merge(role, 1, Int::plus)!!
                observedContexts[role] = context
                if (role == Role.CouncilEcologist && count == 1) {
                    """{"workerGroups":[],"unexpected":true}"""
                } else {
                    encode(request, outputs)
                }
            },
            maxRetries = 1
        )

        assertIs<StartingFieldCompileResult.Valid>(result)
        Role.entries.filter(Role::isCouncilRole).forEach { role ->
            assertEquals(if (role == Role.CouncilEcologist) 2 else 1, calls[role], role.name)
        }
        val anatomyContext = json.parseToJsonElement(
            observedContexts.getValue(Role.CouncilTaskAnatomist)
        ).jsonObject
        assertEquals("Objective", anatomyContext.getValue("objective").jsonPrimitive.content)
        assertTrue("executionEnvelope" in anatomyContext)
        val synthesisDependencies = json.parseToJsonElement(
            observedContexts.getValue(Role.CouncilSynthesis)
        ).jsonObject.getValue("dependencyOutputs").jsonObject
        assertEquals(7, synthesisDependencies.size)
        assertTrue(
            "sealedTestAuthorities" in json.parseToJsonElement(
                observedContexts.getValue(Role.CouncilSynthesis)
            ).jsonObject
        )
    }

    @Test
    fun `specialist closure retries only responsible role before first synthesis`() = runTest {
        val validOutputs = fixtures.outputs()
        val invalidModelOutput = validOutputs.modelEcologist.copy(
            assignments = validOutputs.modelEcologist.assignments.mapIndexed { index, assignment ->
                if (index == 0) assignment.copy(
                    fallbackModelIds = listOf("provider/unavailable")
                )
                else assignment
            }
        )
        val calls = ConcurrentHashMap<Role, Int>()
        val synthesisContexts = CopyOnWriteArrayList<String>()
        val progress = mutableListOf<String>()
        val council = GenesisCouncil(
            objective = "Objective",
            executionEnvelope = fixtures.envelope()
        )

        val result = council.runCompiled(
            runId = "selective-compiler-retry",
            agentRunner = { request ->
                val role = request.role
                val context = request.contextJson
                val count = calls.merge(role, 1, Int::plus)!!
                if (role == Role.CouncilSynthesis) synthesisContexts += context
                if (role == Role.CouncilModelEcologist && count == 1) {
                    json.encodeToString(ModelEcologistOutput.serializer(), invalidModelOutput)
                } else {
                    encode(request, validOutputs)
                }
            },
            onProgress = progress::add,
            maxRetries = 1
        )

        assertIs<StartingFieldCompileResult.Valid>(result)
        assertTrue(
            progress.any {
                it.startsWith("Council specialist closure repair 1/1:") &&
                    "CouncilModelEcologist [" in it &&
                    "provider/unavailable" in it
            }
        )
        assertTrue(
            progress.indexOfFirst {
                it.startsWith("Council specialist closure repair 1/1:")
            } < progress.indexOfFirst { it == "CouncilSynthesis starting" }
        )
        assertEquals(2, calls[Role.CouncilModelEcologist])
        assertEquals(1, calls[Role.CouncilSynthesis])
        Role.entries.filter(Role::isCouncilRole)
            .filterNot { it == Role.CouncilModelEcologist || it == Role.CouncilSynthesis }
            .forEach { role -> assertEquals(1, calls[role], role.name) }
        assertEquals(1, synthesisContexts.size)
        val repairedDependencies = json.parseToJsonElement(synthesisContexts.single()).jsonObject
            .getValue("dependencyOutputs")
            .jsonObject
        assertEquals(7, repairedDependencies.size)
        assertEquals(
            json.encodeToJsonElement(
                ModelEcologistOutput.serializer(),
                validOutputs.modelEcologist
            ),
            repairedDependencies.getValue(Role.CouncilModelEcologist.name)
        )
    }

    @Test
    fun `specialist repair refreshes synthesis errors and retains full current dependencies`() =
        runTest {
            val validOutputs = fixtures.outputs()
            val invalidKgOutput = validOutputs.kgArchitect.copy(schemaVersion = "incompatible")
            val calls = ConcurrentHashMap<Role, Int>()
            val synthesisContexts = CopyOnWriteArrayList<String>()
            val council = GenesisCouncil(
                objective = "Objective",
                executionEnvelope = fixtures.envelope()
            )

            val result = council.runCompiled(
                runId = "shared-specialist-synthesis-repair",
                agentRunner = { request ->
                    val role = request.role
                    val context = request.contextJson
                    val count = calls.merge(role, 1, Int::plus)!!
                    if (role == Role.CouncilSynthesis) synthesisContexts += context
                    if (role == Role.CouncilKGArchitect && count == 1) {
                        encode(KgArchitectOutput.serializer(), invalidKgOutput)
                    } else {
                        encode(request, validOutputs)
                    }
                },
                maxRetries = 1
            )

            assertIs<StartingFieldCompileResult.Valid>(result)
            assertEquals(2, calls[Role.CouncilKGArchitect])
            assertEquals(2, calls[Role.CouncilSynthesis])
            val repairedContext = json.parseToJsonElement(synthesisContexts.last()).jsonObject
            val dependencies = repairedContext.getValue("dependencyOutputs").jsonObject
            assertEquals(7, dependencies.size)
            assertEquals(
                json.encodeToJsonElement(
                    KgArchitectOutput.serializer(),
                    validOutputs.kgArchitect
                ),
                dependencies.getValue(Role.CouncilKGArchitect.name)
            )
            val repairRequest = repairedContext.getValue("repairRequest").jsonObject
            assertTrue(repairRequest.getValue("errors").jsonArray.isEmpty())
            assertTrue("previousOutput" in repairRequest)
        }

    @Test
    fun `task anatomy compiler repair refreshes every dependent specialist`() = runTest {
        val validOutputs = fixtures.outputs()
        val sourceNode = validOutputs.taskAnatomist.nodes.first()
        val transientNode = sourceNode.copy(
            key = LocalNodeKey("transient-node"),
            parentKeys = setOf(sourceNode.key),
            prerequisiteKeys = emptySet(),
            alternativeToKeys = emptySet(),
            proofContract = sourceNode.proofContract.copy(executableTestIds = emptySet()),
            toolOpportunity = null
        )
        val invalidTask = validOutputs.taskAnatomist.copy(
            nodes = validOutputs.taskAnatomist.nodes + transientNode,
            atomicityReport = validOutputs.taskAnatomist.atomicityReport +
                NodeAtomicityAssessment(
                    nodeKey = transientNode.key,
                    isAtomic = true,
                    estimatedEffort = transientNode.estimatedEffort,
                    rationale = "Transient dependency-refresh fixture."
                )
        )
        val calls = ConcurrentHashMap<Role, Int>()
        val repairContexts = ConcurrentHashMap<Role, String>()
        val council = GenesisCouncil(
            objective = "Objective",
            executionEnvelope = fixtures.envelope()
        )

        val result = council.runCompiled(
            runId = "task-dependency-repair",
            agentRunner = { request ->
                val role = request.role
                val context = request.contextJson
                val count = calls.merge(role, 1, Int::plus)!!
                if (count > 1) repairContexts[role] = context
                if (role == Role.CouncilTaskAnatomist && count == 1) {
                    json.encodeToString(TaskAnatomistOutput.serializer(), invalidTask)
                } else {
                    encode(request, validOutputs)
                }
            },
            maxRetries = 1
        )

        assertIs<StartingFieldCompileResult.Valid>(result)
        Role.entries.filter(Role::isCouncilRole)
            .filterNot { it == Role.CouncilSynthesis }
            .forEach { role ->
            assertEquals(2, calls[role], role.name)
        }
        assertEquals(1, calls[Role.CouncilSynthesis])
        Role.entries.filter(Role::isCouncilRole)
            .filterNot {
                it == Role.CouncilTaskAnatomist || it == Role.CouncilSynthesis
            }
            .forEach { role ->
                val repairContext = json.parseToJsonElement(
                    repairContexts.getValue(role)
                ).jsonObject
                assertEquals(
                    json.encodeToJsonElement(
                        TaskAnatomistOutput.serializer(),
                        validOutputs.taskAnatomist
                    ),
                    repairContext.getValue("dependencyOutputs")
                        .jsonObject
                        .getValue(Role.CouncilTaskAnatomist.name)
                )
                val repairRequest = repairContext.getValue("repairRequest").jsonObject
                assertTrue(
                    repairRequest.getValue("errors").jsonArray.isEmpty(),
                    "$role received stale errors after Task Anatomist repair"
                )
            }
    }

    @Test
    fun `task anatomy refresh preserves each dependent specialists own closure errors`() = runTest {
        val validOutputs = fixtures.outputs()
        val invalidTask = validOutputs.taskAnatomist.copy(rootNodeKeys = emptySet())
        val invalidModel = validOutputs.modelEcologist.copy(
            assignments = validOutputs.modelEcologist.assignments.mapIndexed { index, assignment ->
                if (index == 0) {
                    assignment.copy(fallbackModelIds = listOf("provider/unavailable"))
                } else {
                    assignment
                }
            }
        )
        val calls = ConcurrentHashMap<Role, Int>()
        val modelRepairContexts = CopyOnWriteArrayList<String>()
        val council = GenesisCouncil(
            objective = "Objective",
            executionEnvelope = fixtures.envelope()
        )

        val result = council.runCompiled(
            runId = "task-dependent-errors-preserved",
            agentRunner = { request ->
                val count = calls.merge(request.role, 1, Int::plus)!!
                if (request.role == Role.CouncilModelEcologist && count > 1) {
                    modelRepairContexts += request.contextJson
                }
                when {
                    request.role == Role.CouncilTaskAnatomist && count == 1 ->
                        encode(TaskAnatomistOutput.serializer(), invalidTask)
                    request.role == Role.CouncilModelEcologist && count == 1 ->
                        encode(ModelEcologistOutput.serializer(), invalidModel)
                    else -> encode(request, validOutputs)
                }
            },
            maxRetries = 1
        )

        assertIs<StartingFieldCompileResult.Valid>(result)
        assertEquals(1, modelRepairContexts.size)
        val repairRequest = json.parseToJsonElement(modelRepairContexts.single()).jsonObject
            .getValue("repairRequest")
            .jsonObject
        assertEquals(
            json.encodeToJsonElement(ModelEcologistOutput.serializer(), invalidModel),
            repairRequest.getValue("previousOutput")
        )
        val errors = repairRequest.getValue("errors").jsonArray
        assertTrue(errors.isNotEmpty())
        assertTrue(
            errors.any {
                it.jsonObject.getValue("path").jsonObject
                    .getValue("segments").jsonArray
                    .first().jsonPrimitive.content == "modelEcologist"
            }
        )
    }

    @Test
    fun `task anatomy compiler repair preserves fresh dependent compiler errors`() = runTest {
        val validOutputs = fixtures.outputs()
        val invalidSynthesis = validOutputs.synthesis.copy(
            stages = validOutputs.synthesis.stages.map { stage ->
                stage.copy(nodeKeys = emptySet(), mandatoryNodeKeys = emptySet())
            },
            rubricSchemaVersion = "incompatible"
        )
        val calls = ConcurrentHashMap<Role, Int>()
        val kgRepairContexts = CopyOnWriteArrayList<String>()
        val council = GenesisCouncil(
            objective = "Objective",
            executionEnvelope = fixtures.envelope()
        )

        val result = council.runCompiled(
            runId = "task-dependent-compiler-errors-preserved",
            agentRunner = { request ->
                val count = calls.merge(request.role, 1, Int::plus)!!
                if (request.role == Role.CouncilKGArchitect && count > 1) {
                    kgRepairContexts += request.contextJson
                }
                if (request.role == Role.CouncilSynthesis && count == 1) {
                    CouncilSynthesisContextualOutputSerializer(
                        requireNotNull(request.synthesisWireAuthority)
                    ).encodeProviderWire(invalidSynthesis).toString()
                } else {
                    encode(request, validOutputs)
                }
            },
            maxRetries = 1
        )

        assertIs<StartingFieldCompileResult.Valid>(result)
        assertEquals(1, kgRepairContexts.size)
        val repairRequest = json.parseToJsonElement(kgRepairContexts.single()).jsonObject
            .getValue("repairRequest")
            .jsonObject
        assertEquals(
            json.encodeToJsonElement(KgArchitectOutput.serializer(), validOutputs.kgArchitect),
            repairRequest.getValue("previousOutput")
        )
        assertTrue(
            repairRequest.getValue("errors").jsonArray.any {
                it.jsonObject.getValue("path").jsonObject
                    .getValue("segments").jsonArray
                    .map { segment -> segment.jsonPrimitive.content } ==
                    listOf("synthesis", "rubricSchemaVersion")
            }
        )
    }

    @Test
    fun `runtime model projection removes Council roles and fills singleton model assignments`() {
        val modelId = "provider/worker"
        val envelope = fixtures.envelope().copy(
            availableModels = listOf(fixtures.envelope().availableModels.first()),
            operatorOverrides = mapOf("model.Researcher" to modelId)
        )
        val outputs = fixtures.outputs().copy(
            modelEcologist = fixtures.outputs().modelEcologist.copy(
                assignments = listOf(
                    RoleModelAssignment(Role.Researcher, modelId),
                    RoleModelAssignment(Role.CouncilModelEcologist, modelId)
                )
            )
        )

        val projected = outputs.projectDeterministicCompilerAuthority("projection-run", envelope)
        val assignments = projected.modelEcologist.assignments.associateBy { it.role }

        assertEquals(
            outputs.promptArchitect.rolePrompts.keys,
            assignments.keys
        )
        assertTrue(assignments.keys.none(Role::isCouncilRole))
        assertTrue(assignments.values.all { it.primaryModelId == modelId })
        assertTrue(
            projected.synthesis.sealedTests.all {
                it.commitmentHash == CouncilContractHashing.sha256(it.testDefinitionJson)
            }
        )
    }

    @Test
    fun `persistent malformed output fails closed without default field`() = runTest {
        val outputs = fixtures.outputs()
        val calls = ConcurrentHashMap<Role, Int>()
        val progress = mutableListOf<String>()
        val council = GenesisCouncil(
            objective = "Objective",
            executionEnvelope = fixtures.envelope()
        )

        val result = council.runCompiled(
            runId = "malformed-abort",
            agentRunner = { request ->
                val role = request.role
                calls.merge(role, 1, Int::plus)
                if (role == Role.CouncilTaskAnatomist) "{}" else encode(request, outputs)
            },
            onProgress = progress::add,
            maxRetries = 1
        )

        val invalid = assertIs<StartingFieldCompileResult.Invalid>(result)
        assertEquals(setOf(Role.CouncilTaskAnatomist), invalid.responsibleCouncilRoles)
        assertTrue(invalid.errors.all { it.code == FieldCompileErrorCode.MALFORMED_ROLE_OUTPUT })
        assertTrue(progress.any { "CouncilTaskAnatomist output invalid:" in it })
        assertEquals(2, calls[Role.CouncilTaskAnatomist])
        assertEquals(1, calls.size)
    }

    @Test
    fun `default shared repair budget remains five fail-closed closure cycles`() = runTest {
        val outputs = fixtures.outputs()
        val invalidTask = outputs.taskAnatomist.copy(rootNodeKeys = emptySet())
        val calls = ConcurrentHashMap<Role, Int>()
        val progress = mutableListOf<String>()
        val council = GenesisCouncil(
            objective = "Objective",
            executionEnvelope = fixtures.envelope()
        )

        val result = council.runCompiled(
            runId = "default-five-cycle-budget",
            agentRunner = { request ->
                val role = request.role
                calls.merge(role, 1, Int::plus)
                if (role == Role.CouncilTaskAnatomist) {
                    json.encodeToString(TaskAnatomistOutput.serializer(), invalidTask)
                } else {
                    encode(request, outputs)
                }
            },
            onProgress = progress::add
        )

        assertIs<StartingFieldCompileResult.Invalid>(result)
        Role.entries.filter(Role::isCouncilRole)
            .filterNot { it == Role.CouncilSynthesis }
            .forEach { role ->
            assertEquals(6, calls[role], role.name)
        }
        assertEquals(0, calls[Role.CouncilSynthesis] ?: 0)
        val repairEvents =
            progress.filter { it.startsWith("Council specialist closure repair ") }
        assertEquals(5, repairEvents.size)
        (1..5).forEach { index ->
            assertTrue(
                repairEvents[index - 1]
                    .startsWith("Council specialist closure repair $index/5:")
            )
        }
    }

    private fun encode(
        request: CouncilRoleExecutionRequest,
        outputs: CouncilRoleOutputBundle
    ): String = when (request.role) {
        Role.CouncilTaskAnatomist -> encode(TaskAnatomistOutput.serializer(), outputs.taskAnatomist)
        Role.CouncilIncentiveDesigner -> encode(
            IncentiveDesignerOutput.serializer(),
            outputs.incentiveDesigner
        )
        Role.CouncilEcologist -> encode(EcologistOutput.serializer(), outputs.ecologist)
        Role.CouncilPromptArchitect -> encode(
            PromptArchitectOutput.serializer(),
            outputs.promptArchitect
        )
        Role.CouncilKGArchitect -> encode(KgArchitectOutput.serializer(), outputs.kgArchitect)
        Role.CouncilModelEcologist -> encode(
            ModelEcologistOutput.serializer(),
            outputs.modelEcologist
        )
        Role.CouncilSeedCurator -> encode(
            SeedCuratorOutput.serializer(),
            outputs.seedCurator
        )
        Role.CouncilSynthesis ->
            CouncilSynthesisContextualOutputSerializer(
                requireNotNull(request.synthesisWireAuthority)
            ).encodeProviderWire(outputs.synthesis).toString()
        else -> error("${request.role} is not a Council role")
    }

    private fun <T> encode(serializer: KSerializer<T>, value: T): String =
        json.encodeToString(serializer, value)

    private fun promotionOpportunity(
        origin: com.achulkov.evomolior.core.council.LocalLimitationNode,
        downstream: com.achulkov.evomolior.core.council.LocalLimitationNode
    ): ToolOpportunity = ToolOpportunity(
        opportunityId = "strict-promotion-wire",
        opportunityType = ToolOpportunityType.CALCULATION,
        repeatedQuestionPattern = "Evaluate one deterministic promotion fixture.",
        requiredInputs = listOf(
            TypedField("enabled", TypedFieldType.BOOLEAN, "Input flag.")
        ),
        requiredOutputs = listOf(
            TypedField("accepted", TypedFieldType.BOOLEAN, "Output flag.")
        ),
        validationStrategy = "Execute the exact committed fixture.",
        expectedReuseNodeIds = setOf(NodeId(downstream.key.value)),
        preferredImplementationMode = ToolImplementationMode.PYTHON,
        behavior = ToolBehavior.DETERMINISTIC,
        promotionTestIds = origin.proofContract.executableTestIds,
        maximumRuntimeMillis = 1_000,
        maximumOutputBytes = 4_096
    )

    private fun JsonObject.promotionFixtureComponent(name: String): JsonObject =
        getValue("sealedTests")
            .jsonArray
            .single()
            .jsonObject
            .getValue("testDefinitionJson")
            .jsonObject
            .getValue(name)
            .jsonObject

    private fun JsonObject.withPromotionFixtureComponent(
        name: String,
        transform: (JsonObject) -> JsonObject
    ): JsonObject {
        val tests = getValue("sealedTests").jsonArray
        val test = tests.single().jsonObject
        val definition = test.getValue("testDefinitionJson").jsonObject
        val updatedTest = JsonObject(
            test + (
                "testDefinitionJson" to JsonObject(
                    definition + (name to transform(definition.getValue(name).jsonObject))
                )
            )
        )
        return JsonObject(this + ("sealedTests" to JsonArray(listOf(updatedTest))))
    }

    private fun JsonObject.updateObject(
        path: List<String>,
        transform: (JsonObject) -> JsonObject
    ): JsonObject {
        if (path.isEmpty()) return transform(this)
        val name = path.first()
        val updated = getValue(name).jsonObject.updateObject(path.drop(1), transform)
        return JsonObject(this + (name to updated))
    }
}
