package com.achulkov.evomolior.orchestration.council

import com.achulkov.evomolior.core.atlas.ArtifactType
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.atlas.RevealMode
import com.achulkov.evomolior.core.atlas.ToolBehavior
import com.achulkov.evomolior.core.atlas.ToolImplementationMode
import com.achulkov.evomolior.core.atlas.ToolOpportunity
import com.achulkov.evomolior.core.atlas.ToolOpportunityType
import com.achulkov.evomolior.core.atlas.TypedField
import com.achulkov.evomolior.core.atlas.TypedFieldType
import com.achulkov.evomolior.core.atlas.TypedToolSchemaCompiler
import com.achulkov.evomolior.core.council.CouncilContractHashing
import com.achulkov.evomolior.core.council.CouncilRoleExecutionRequest
import com.achulkov.evomolior.core.council.CouncilRoleOutputBundle
import com.achulkov.evomolior.core.council.CouncilSynthesisWireAuthority
import com.achulkov.evomolior.core.council.DisclosureStageDraft
import com.achulkov.evomolior.core.council.EcologistOutput
import com.achulkov.evomolior.core.council.FieldCompileError
import com.achulkov.evomolior.core.council.FieldCompileErrorCode
import com.achulkov.evomolior.core.council.IncentiveDesignerOutput
import com.achulkov.evomolior.core.council.KgArchitectOutput
import com.achulkov.evomolior.core.council.LocalLimitationNode
import com.achulkov.evomolior.core.council.ModelEcologistOutput
import com.achulkov.evomolior.core.council.PromptArchitectOutput
import com.achulkov.evomolior.core.council.SeedCuratorOutput
import com.achulkov.evomolior.core.council.StartingFieldCompileResult
import com.achulkov.evomolior.core.council.SynthesisOutput
import com.achulkov.evomolior.core.council.TaskAnatomistOutput
import com.achulkov.evomolior.core.model.Role
import com.achulkov.evomolior.core.serialization.CouncilSynthesisContextualOutputSerializer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GenesisCouncilFocusedRepairTest {
    private val fixtures = StartingFieldCompilerTest()
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
    }

    @Test
    fun `synthesis-only compiler repair uses exact focused authority and compiles`() = runTest {
        val fixture = focusedToolRepairFixture("focused-synthesis-repair")
        val invalidTask = fixture.outputs.taskAnatomist.copy(rootNodeKeys = emptySet())
        val calls = ConcurrentHashMap<Role, Int>()
        val synthesisContexts = CopyOnWriteArrayList<String>()
        val council = GenesisCouncil(
            objective = "Objective",
            executionEnvelope = fixtures.envelope()
        )

        val result = council.runCompiled(
            runId = fixture.runId,
            agentRunner = { request ->
                val role = request.role
                val context = request.contextJson
                val count = calls.merge(role, 1, Int::plus)!!
                when (role) {
                    Role.CouncilTaskAnatomist -> if (count == 1) {
                        encode(TaskAnatomistOutput.serializer(), invalidTask)
                    } else {
                        encode(request, fixture.outputs)
                    }
                    Role.CouncilSynthesis -> {
                        synthesisContexts += context
                        if (count == 1) {
                            encode(
                                request.contextualSynthesisSerializer(),
                                fixture.invalidSynthesis
                            )
                        } else {
                            encode(
                                request.contextualSynthesisSerializer(),
                                fixture.repairedSynthesis
                            )
                        }
                    }
                    else -> encode(request, fixture.outputs)
                }
            },
            maxRetries = 2
        )

        assertIs<StartingFieldCompileResult.Valid>(result)
        assertEquals(2, calls[Role.CouncilSynthesis])
        assertEquals(2, calls[Role.CouncilTaskAnatomist])
        Role.entries.filter(Role::isCouncilRole)
            .filterNot {
                it == Role.CouncilTaskAnatomist || it == Role.CouncilSynthesis
            }
            .forEach { role -> assertEquals(2, calls[role], role.name) }

        val fullContext = json.parseToJsonElement(synthesisContexts.first()).jsonObject
        assertEquals(7, fullContext.getValue("dependencyOutputs").jsonObject.size)
        assertFalse("repairRequest" in fullContext)

        val focusedContext = json.parseToJsonElement(synthesisContexts.last()).jsonObject
        assertEquals("Objective", focusedContext.getValue("objective").jsonPrimitive.content)
        assertTrue("executionEnvelope" in focusedContext)
        assertFalse("dependencyOutputs" in focusedContext)
        assertFocusedRepairAuthority(focusedContext, fixture)
    }

    @Test
    fun `specialist closure repair consumes the shared compiler repair budget`() = runTest {
        val fixture = focusedToolRepairFixture("shared-closure-compiler-budget")
        val invalidTask = fixture.outputs.taskAnatomist.copy(rootNodeKeys = emptySet())
        val calls = ConcurrentHashMap<Role, Int>()
        val progress = mutableListOf<String>()
        val council = GenesisCouncil(
            objective = "Objective",
            executionEnvelope = fixtures.envelope()
        )

        val result = council.runCompiled(
            runId = fixture.runId,
            agentRunner = { request ->
                val count = calls.merge(request.role, 1, Int::plus)!!
                when (request.role) {
                    Role.CouncilTaskAnatomist -> encode(
                        TaskAnatomistOutput.serializer(),
                        if (count == 1) invalidTask else fixture.outputs.taskAnatomist
                    )
                    Role.CouncilSynthesis -> encode(
                        request.contextualSynthesisSerializer(),
                        fixture.invalidSynthesis
                    )
                    else -> encode(request, fixture.outputs)
                }
            },
            onProgress = progress::add,
            maxRetries = 1
        )

        assertIs<StartingFieldCompileResult.Invalid>(result)
        assertEquals(2, calls[Role.CouncilTaskAnatomist])
        Role.entries.filter(Role::isCouncilRole)
            .filterNot {
                it == Role.CouncilTaskAnatomist || it == Role.CouncilSynthesis
            }
            .forEach { role -> assertEquals(2, calls[role], role.name) }
        assertEquals(1, calls[Role.CouncilSynthesis])
        assertEquals(
            1,
            progress.count { it.startsWith("Council specialist closure repair 1/1:") }
        )
        assertTrue(progress.none { it.startsWith("Council compiler repair ") })
    }

    @Test
    fun `malformed focused synthesis repair retries with the same focused authority`() = runTest {
        val fixture = focusedToolRepairFixture("focused-synthesis-malformed-retry")
        val calls = ConcurrentHashMap<Role, Int>()
        val synthesisContexts = CopyOnWriteArrayList<String>()
        val malformedRepair = """{"atlasVersion":1}"""
        val council = GenesisCouncil(
            objective = "Objective",
            executionEnvelope = fixtures.envelope()
        )

        val result = council.runCompiled(
            runId = fixture.runId,
            agentRunner = { request ->
                val role = request.role
                val context = request.contextJson
                val count = calls.merge(role, 1, Int::plus)!!
                if (role == Role.CouncilSynthesis) {
                    synthesisContexts += context
                    when (count) {
                        1 -> encode(
                            request.contextualSynthesisSerializer(),
                            fixture.invalidSynthesis
                        )
                        2 -> malformedRepair
                        else -> encode(
                            request.contextualSynthesisSerializer(),
                            fixture.repairedSynthesis
                        )
                    }
                } else {
                    encode(request, fixture.outputs)
                }
            },
            maxRetries = 1
        )

        assertIs<StartingFieldCompileResult.Valid>(result)
        assertEquals(3, calls[Role.CouncilSynthesis])
        val firstFocused = json.parseToJsonElement(synthesisContexts[1]).jsonObject
        val retriedFocused = json.parseToJsonElement(synthesisContexts[2]).jsonObject
        assertFalse("dependencyOutputs" in firstFocused)
        assertFalse("dependencyOutputs" in retriedFocused)
        assertFocusedRepairAuthority(firstFocused, fixture)
        assertFocusedRepairAuthority(retriedFocused, fixture)

        val retryRequest = retriedFocused.getValue("repairRequest").jsonObject
        assertEquals(
            malformedRepair,
            retryRequest.getValue("invalidOutput").jsonPrimitive.content
        )
        assertEquals(
            CouncilSynthesisContextualOutputSerializer(fixture.wireAuthority)
                .encodeProviderWire(fixture.invalidSynthesis),
            retryRequest.getValue("previousOutput")
        )
        val errorCodes = retryRequest.getValue("errors").jsonArray.map {
            it.jsonObject.getValue("code").jsonPrimitive.content
        }
        assertEquals(
            fixture.expectedCompilerErrors.map { it.code.name } +
                FieldCompileErrorCode.MALFORMED_ROLE_OUTPUT.name,
            errorCodes
        )
    }

    @Test
    fun `v68 promotion identities remain object-only through focused replacement`() = runTest {
        val promotionIds = linkedSetOf(
            "cooling-simulator-boundary-test",
            "cooling-simulator-repeatability-test"
        )
        val fixture = focusedToolRepairFixture(
            runId = "v68-two-promotion-object-authority",
            promotionTestIds = promotionIds
        )
        val calls = ConcurrentHashMap<Role, Int>()
        val synthesisContexts = CopyOnWriteArrayList<String>()
        val council = GenesisCouncil(
            objective = "Objective",
            executionEnvelope = fixtures.envelope()
        )

        val result = council.runCompiled(
            runId = fixture.runId,
            agentRunner = { request ->
                val count = calls.merge(request.role, 1, Int::plus)!!
                if (request.role == Role.CouncilSynthesis) {
                    synthesisContexts += request.contextJson
                    encode(
                        request.contextualSynthesisSerializer(),
                        if (count == 1) {
                            fixture.invalidSynthesis
                        } else {
                            fixture.repairedSynthesis
                        }
                    )
                } else {
                    encode(request, fixture.outputs)
                }
            },
            maxRetries = 1
        )

        assertIs<StartingFieldCompileResult.Valid>(result)
        assertEquals(2, calls[Role.CouncilSynthesis])
        val focused = json.parseToJsonElement(synthesisContexts.last()).jsonObject
        assertFalse("dependencyOutputs" in focused)
        val authorities = focused.getValue("sealedTestAuthorities")
            .jsonObject
            .getValue("entries")
            .jsonArray
        assertEquals(
            promotionIds,
            authorities
                .filter {
                    it.jsonObject.getValue("kind").jsonPrimitive.content == "PROMOTION"
                }
                .mapTo(linkedSetOf()) {
                    it.jsonObject.getValue("testKey").jsonPrimitive.content
                }
        )
        val previousTests = focused.getValue("repairRequest")
            .jsonObject
            .getValue("previousOutput")
            .jsonObject
            .getValue("sealedTests")
            .jsonArray
        val promotionAuthorityKeys = authorities
            .filter {
                it.jsonObject.getValue("kind").jsonPrimitive.content == "PROMOTION"
            }
            .associate {
                it.jsonObject.getValue("testKey").jsonPrimitive.content to
                    it.jsonObject.getValue("authorityKey").jsonPrimitive.content
            }
        assertEquals(
            promotionAuthorityKeys.values.toSet(),
            previousTests
                .filter {
                    it.jsonObject.getValue("type").jsonPrimitive.content == "PROMOTION"
                }
                .mapTo(linkedSetOf()) {
                    it.jsonObject.getValue("authorityKey").jsonPrimitive.content
                }
        )
        assertTrue(
            previousTests
                .filter {
                    it.jsonObject.getValue("type").jsonPrimitive.content == "PROMOTION"
                }
                .all {
                    it.jsonObject.getValue("testDefinitionJson").jsonObject.keys ==
                        setOf("input", "expectedOutput")
                }
        )
    }

    private fun assertFocusedRepairAuthority(
        context: JsonObject,
        fixture: FocusedToolRepairFixture
    ) {
        val repairRequest = context.getValue("repairRequest").jsonObject
        assertEquals(
            Role.CouncilSynthesis.name,
            repairRequest.getValue("responsibleRole").jsonPrimitive.content
        )
        assertEquals(
            CouncilSynthesisContextualOutputSerializer(fixture.wireAuthority)
                .encodeProviderWire(fixture.invalidSynthesis),
            repairRequest.getValue("previousOutput")
        )

        val errors = repairRequest.getValue("errors").jsonArray
        val expectedErrors = fixture.expectedCompilerErrors.map { error ->
            json.encodeToJsonElement(FieldCompileError.serializer(), error)
        }
        assertEquals(expectedErrors, errors.take(expectedErrors.size))
        assertTrue(
            fixture.expectedCompilerErrors.all {
                fixture.relatedNode.key in it.relatedLocalNodeKeys
            }
        )

        val relatedNodes = repairRequest.getValue("relatedNodeContracts").jsonArray
        assertEquals(1, relatedNodes.size)
        assertEquals(
            json.encodeToJsonElement(
                LocalLimitationNode.serializer(),
                fixture.relatedNode
            ),
            relatedNodes.single()
        )

        val schemas = repairRequest.getValue("generatedToolSchemas").jsonArray
        assertEquals(1, schemas.size)
        val toolSchemas = schemas.single().jsonObject
        val opportunity = requireNotNull(fixture.relatedNode.toolOpportunity)
        assertEquals(
            fixture.relatedNode.key.value,
            toolSchemas.getValue("nodeKey").jsonPrimitive.content
        )
        assertEquals(
            opportunity.opportunityId,
            toolSchemas.getValue("opportunityId").jsonPrimitive.content
        )
        assertEquals(
            json.parseToJsonElement(TypedToolSchemaCompiler.compile(opportunity.requiredInputs)),
            toolSchemas.getValue("inputSchema")
        )
        assertEquals(
            json.parseToJsonElement(TypedToolSchemaCompiler.compile(opportunity.requiredOutputs)),
            toolSchemas.getValue("outputSchema")
        )
    }

    private fun focusedToolRepairFixture(
        runId: String,
        promotionTestIds: Set<String>? = null
    ): FocusedToolRepairFixture {
        val base = fixtures.outputs()
        val origin = base.taskAnatomist.nodes.first()
        val promotionOrigin = promotionTestIds?.let { ids ->
            origin.copy(
                proofContract = origin.proofContract.copy(executableTestIds = ids)
            )
        } ?: origin
        val downstream = base.taskAnatomist.nodes.last().copy(
            stageKey = "delivery",
            revealMode = RevealMode.STAGE_PASS
        )
        val opportunity = ToolOpportunity(
            opportunityId = "hourly-load-$runId",
            opportunityType = ToolOpportunityType.CALCULATION,
            repeatedQuestionPattern = "Calculate exactly 72 hourly shelter results.",
            requiredInputs = listOf(
                TypedField(
                    name = "scenario",
                    type = TypedFieldType.OBJECT,
                    description = "Bounded shelter scenario.",
                    objectFields = setOf(
                        TypedField(
                            name = "outdoor_temperatures",
                            type = TypedFieldType.ARRAY,
                            description = "Exactly 72 outdoor temperatures.",
                            itemType = TypedFieldType.NUMBER,
                            minimumItems = 72,
                            maximumItems = 72
                        )
                    )
                )
            ),
            requiredOutputs = listOf(
                TypedField(
                    name = "hourly_results",
                    type = TypedFieldType.ARRAY,
                    description = "Exactly 72 hourly results.",
                    itemType = TypedFieldType.NUMBER,
                    minimumItems = 72,
                    maximumItems = 72
                )
            ),
            validationStrategy = "Run the exact committed 72-hour fixture.",
            expectedReuseNodeIds = setOf(NodeId(downstream.key.value)),
            preferredImplementationMode = ToolImplementationMode.PYTHON,
            behavior = ToolBehavior.DETERMINISTIC,
            requiredCapabilities = setOf("shell"),
            promotionTestIds = promotionOrigin.proofContract.executableTestIds,
            maximumRuntimeMillis = 1_000,
            maximumOutputBytes = 16_384
        )
        val relatedNode = promotionOrigin.copy(toolOpportunity = opportunity)
        val stages = listOf(
            base.synthesis.stages.single().copy(
                nodeKeys = setOf(relatedNode.key),
                mandatoryNodeKeys = setOf(relatedNode.key)
            ),
            DisclosureStageDraft(
                stageKey = "delivery",
                title = "Delivery",
                nodeKeys = setOf(downstream.key),
                mandatoryNodeKeys = setOf(downstream.key),
                evidenceCoverageThreshold = 1.0,
                integrationReadinessThreshold = 1.0
            )
        )
        val validInputValues = List(72) { "20.0" }.joinToString(",")
        val validOutputValues = List(72) { "1.0" }.joinToString(",")
        val validDefinition =
            """{"input":{"scenario":{"outdoor_temperatures":[$validInputValues]}},"expectedOutput":{"hourly_results":[$validOutputValues]}}"""
        val promotionTemplate = base.synthesis.sealedTests.single {
            it.nodeKey == origin.key
        }
        val invalidArtifactType = ArtifactType.entries.first {
            it !in relatedNode.deliverableContract.acceptedArtifactTypes
        }
        fun synthesis(
            definition: String,
            acceptedArtifactTypes: Set<ArtifactType> =
                promotionTemplate.acceptedArtifactTypes
        ) = base.synthesis.copy(
            stages = stages,
            sealedTests = base.synthesis.sealedTests.filterNot {
                it.nodeKey == origin.key
            } + opportunity.promotionTestIds.sorted().map { testId ->
                promotionTemplate.copy(
                    testKey = testId,
                    nodeKey = relatedNode.key,
                    acceptedArtifactTypes = acceptedArtifactTypes,
                    testDefinitionJson = definition,
                    commitmentHash = CouncilContractHashing.sha256(definition)
                )
            }
        )
        val invalidSynthesis = synthesis(
            definition = validDefinition,
            acceptedArtifactTypes = setOf(invalidArtifactType)
        )
        val outputs = base.copy(
            taskAnatomist = base.taskAnatomist.copy(
                nodes = listOf(relatedNode, downstream)
            ),
            synthesis = invalidSynthesis
        )
        val expectedInvalid = assertIs<StartingFieldCompileResult.Invalid>(
            StartingFieldCompiler().compile(
                runId = runId,
                rawObjective = "Objective",
                envelope = fixtures.envelope(),
                outputs = outputs.projectDeterministicCompilerAuthority(
                    runId,
                    fixtures.envelope()
                )
            )
        )
        val expectedCompilerErrors = expectedInvalid.errors.filter {
            Role.CouncilSynthesis in it.responsibleCouncilRoles
        }
        assertTrue(expectedCompilerErrors.isNotEmpty())
        return FocusedToolRepairFixture(
            runId = runId,
            outputs = outputs,
            invalidSynthesis = invalidSynthesis,
            repairedSynthesis = synthesis(validDefinition),
            relatedNode = relatedNode,
            expectedCompilerErrors = expectedCompilerErrors,
            wireAuthority = CouncilSynthesisWireAuthority.from(outputs.taskAnatomist)
        )
    }

    private fun encode(
        request: CouncilRoleExecutionRequest,
        outputs: CouncilRoleOutputBundle
    ): String = when (request.role) {
        Role.CouncilTaskAnatomist ->
            encode(TaskAnatomistOutput.serializer(), outputs.taskAnatomist)
        Role.CouncilIncentiveDesigner ->
            encode(IncentiveDesignerOutput.serializer(), outputs.incentiveDesigner)
        Role.CouncilEcologist -> encode(EcologistOutput.serializer(), outputs.ecologist)
        Role.CouncilPromptArchitect ->
            encode(PromptArchitectOutput.serializer(), outputs.promptArchitect)
        Role.CouncilKGArchitect -> encode(KgArchitectOutput.serializer(), outputs.kgArchitect)
        Role.CouncilModelEcologist ->
            encode(ModelEcologistOutput.serializer(), outputs.modelEcologist)
        Role.CouncilSeedCurator -> encode(SeedCuratorOutput.serializer(), outputs.seedCurator)
        Role.CouncilSynthesis ->
            encode(request.contextualSynthesisSerializer(), outputs.synthesis)
        else -> error("${request.role} is not a Council role")
    }

    private fun CouncilRoleExecutionRequest.contextualSynthesisSerializer() =
        CouncilSynthesisContextualOutputSerializer(
            requireNotNull(synthesisWireAuthority)
        )

    private fun <T> encode(serializer: KSerializer<T>, value: T): String =
        json.encodeToString(serializer, value)

    private fun encode(
        serializer: CouncilSynthesisContextualOutputSerializer,
        value: SynthesisOutput
    ): String = serializer.encodeProviderWire(value).toString()

    private data class FocusedToolRepairFixture(
        val runId: String,
        val outputs: CouncilRoleOutputBundle,
        val invalidSynthesis: SynthesisOutput,
        val repairedSynthesis: SynthesisOutput,
        val relatedNode: LocalLimitationNode,
        val expectedCompilerErrors: List<FieldCompileError>,
        val wireAuthority: CouncilSynthesisWireAuthority
    )
}
