package com.achulkov.evomolior.agents.factory

import com.achulkov.evomolior.core.atlas.ArtifactType
import com.achulkov.evomolior.core.atlas.CapturePolicy
import com.achulkov.evomolior.core.atlas.EvidenceProfile
import com.achulkov.evomolior.core.atlas.NodeDeliverableContract
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.atlas.NodeKind
import com.achulkov.evomolior.core.atlas.ProofContract
import com.achulkov.evomolior.core.atlas.ProofMethod
import com.achulkov.evomolior.core.atlas.ReplicationPolicy
import com.achulkov.evomolior.core.atlas.RevealMode
import com.achulkov.evomolior.core.atlas.RewardClass
import com.achulkov.evomolior.core.atlas.RewardSpec
import com.achulkov.evomolior.core.atlas.ToolBehavior
import com.achulkov.evomolior.core.atlas.ToolImplementationMode
import com.achulkov.evomolior.core.atlas.ToolOpportunity
import com.achulkov.evomolior.core.atlas.ToolOpportunityType
import com.achulkov.evomolior.core.atlas.TypedField
import com.achulkov.evomolior.core.atlas.TypedFieldType
import com.achulkov.evomolior.core.config.AcceptanceProfile
import com.achulkov.evomolior.core.council.LocalLimitationNode
import com.achulkov.evomolior.core.council.LocalNodeKey
import com.achulkov.evomolior.core.council.NodeAtomicityAssessment
import com.achulkov.evomolior.core.council.QuantitativeToolFieldLocation
import com.achulkov.evomolior.core.council.QuantitativeToolFieldObligation
import com.achulkov.evomolior.core.council.QuantitativeToolFieldShape
import com.achulkov.evomolior.core.council.QuantitativeToolObligation
import com.achulkov.evomolior.core.council.ReceiverContractDraft
import com.achulkov.evomolior.core.council.TaskAnatomistOutput
import com.achulkov.evomolior.core.model.Role
import com.achulkov.evomolior.core.repair.AggregationSemantics
import com.achulkov.evomolior.core.repair.QuantitySpec
import com.achulkov.evomolior.core.repair.TimeBasis
import com.achulkov.evomolior.core.atlas.PhysicalDimension
import com.achulkov.evomolior.core.serialization.TaskAnatomistContextualOutputSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CouncilAcceptanceProfileSchemaTest {
    @Test
    fun `normal Task schema remains the unchanged nullable domain schema`() {
        val implicit = CouncilRoleSchemas.forRole(Role.CouncilTaskAnatomist)
        val explicit = CouncilRoleSchemas.forRole(
            role = Role.CouncilTaskAnatomist,
            acceptanceProfile = AcceptanceProfile.NORMAL
        )

        assertEquals(implicit, explicit)
        assertTrue("nodes" in explicit.schema.getValue("properties").jsonObject)
        assertFalse("nodesWithToolOpportunity" in explicit.schema.toString())
        assertFalse("\"acceptanceProfile\"" in explicit.schema.toString())
    }

    @Test
    fun `smoke Task schema accepts only null tool opportunity`() {
        val serializer = TaskAnatomistContextualOutputSerializer(
            AcceptanceProfile.RUNTIME_USABILITY_SMOKE
        )
        val schema = CouncilRoleSchemas.forRole(
            role = Role.CouncilTaskAnatomist,
            acceptanceProfile = AcceptanceProfile.RUNTIME_USABILITY_SMOKE
        )
        val document = CodexOutputSchemaDocument.from(schema)
        val valid = serializer.encodeProviderWire(task(listOf(node("ordinary")))).jsonObject

        assertEquals(emptyList(), validate(document, valid))
        val node = valid.getValue("nodes").jsonArray.single().jsonObject
        val invalid = JsonObject(
            valid + (
                "nodes" to JsonArray(
                    listOf(
                        JsonObject(
                            node + ("toolOpportunity" to JsonObject(emptyMap()))
                        )
                    )
                )
            )
        )
        assertTrue(validate(document, invalid).isNotEmpty())

        val definitions = schema.schema.getValue("\$defs").jsonObject
        val itemDefinition = schema.schema.getValue("properties")
            .jsonObject
            .getValue("nodes")
            .jsonObject
            .getValue("items")
            .jsonObject
            .resolveReference(definitions)
        assertEquals(
            "null",
            itemDefinition.getValue("properties")
                .jsonObject
                .getValue("toolOpportunity")
                .jsonObject
                .getValue("type")
                .jsonPrimitive
                .content
        )
    }

    @Test
    fun `full Task schema requires one typed opportunity partition without contains`() {
        val serializer = TaskAnatomistContextualOutputSerializer(
            AcceptanceProfile.APPENDIX_B_FULL
        )
        val schema = CouncilRoleSchemas.forRole(
            role = Role.CouncilTaskAnatomist,
            acceptanceProfile = AcceptanceProfile.APPENDIX_B_FULL
        )
        val document = CodexOutputSchemaDocument.from(schema)
        val valid = serializer.encodeProviderWire(
            task(
                listOf(
                    node("origin", opportunity()),
                    node("reuse")
                )
            )
        ).jsonObject

        assertEquals(emptyList(), validate(document, valid))
        val noOpportunity = JsonObject(
            valid + ("nodesWithToolOpportunity" to JsonArray(emptyList()))
        )
        assertTrue(validate(document, noOpportunity).isNotEmpty())

        val typedArray = schema.schema.getValue("properties")
            .jsonObject
            .getValue("nodesWithToolOpportunity")
            .jsonObject
        assertEquals(1, typedArray.getValue("minItems").jsonPrimitive.content.toInt())
        assertFalse(schema.schema.toString().contains("\"contains\""))
        assertFalse(schema.schema.toString().contains("\"minContains\""))

        val ordinary = valid.getValue("nodesWithoutToolOpportunity")
            .jsonArray
            .single()
            .jsonObject
        val typedOpportunity = valid.getValue("nodesWithToolOpportunity")
            .jsonArray
            .single()
            .jsonObject
            .getValue("toolOpportunity")
        val invalidOrdinary = JsonObject(
            valid + (
                "nodesWithoutToolOpportunity" to JsonArray(
                    listOf(
                        JsonObject(
                            ordinary + ("toolOpportunity" to typedOpportunity)
                        )
                    )
                )
            )
        )
        assertTrue(validate(document, invalidOrdinary).isNotEmpty())
    }

    @Test
    fun `full Task authority binds operator quantitative fields before Council admission`() {
        val obligation = quantitativeObligation()
        val serializer = TaskAnatomistContextualOutputSerializer(
            AcceptanceProfile.APPENDIX_B_FULL
        )
        val schema = CouncilRoleSchemas.forRole(
            role = Role.CouncilTaskAnatomist,
            acceptanceProfile = AcceptanceProfile.APPENDIX_B_FULL,
            quantitativeToolObligation = obligation
        )
        val document = CodexOutputSchemaDocument.from(schema)
        val valid = serializer.encodeProviderWire(
            task(
                listOf(
                    node("origin", quantitativeOpportunity()),
                    node("reuse")
                )
            )
        ).jsonObject

        assertEquals(emptyList(), validate(document, valid))
        val typedNode = valid.getValue("nodesWithToolOpportunity")
            .jsonArray
            .single()
            .jsonObject
        val opportunity = typedNode.getValue("toolOpportunity").jsonObject
        val fields = opportunity.getValue("requiredInputs").jsonArray
        fun responseWith(updatedOpportunity: JsonObject): JsonObject =
            JsonObject(
                valid + (
                    "nodesWithToolOpportunity" to JsonArray(
                        listOf(
                            JsonObject(
                                typedNode + (
                                    "toolOpportunity" to updatedOpportunity
                                )
                            )
                        )
                    )
                )
            )

        fun assertInvalid(updatedOpportunity: JsonObject) {
            assertTrue(validate(document, responseWith(updatedOpportunity)).isNotEmpty())
        }

        fun assertDuplicateRejectedByRuntime(updatedOpportunity: JsonObject, reason: String) {
            val response = responseWith(updatedOpportunity)
            // Codex omits uniqueItems at admission. The actual wire decoder must reject duplicates
            // before lists are normalized to sets or become a ToolOpportunity domain value.
            assertEquals(emptyList(), validate(document, response))
            val failure = assertFailsWith<IllegalArgumentException> {
                serializer.decodeProviderWire(response)
            }
            assertTrue(failure.message.orEmpty().contains(reason), failure.message)
        }

        val scalar = fields.first().jsonObject
        val series = fields.last().jsonObject
        assertInvalid(
            JsonObject(
                opportunity + (
                    "requiredInputs" to JsonArray(
                        listOf(
                            JsonObject(
                                scalar.filterKeys {
                                    it !in setOf("physicalDimension", "quantitySpec")
                                } + ("type" to JsonPrimitive("BOOLEAN"))
                            ),
                            series
                        )
                    )
                )
            )
        )
        val seriesItems = series.getValue("items").jsonObject
        assertInvalid(
            JsonObject(
                opportunity + (
                    "requiredInputs" to JsonArray(
                        listOf(
                            scalar,
                            JsonObject(
                                series + (
                                    "items" to JsonObject(
                                        seriesItems.filterKeys {
                                            it !in setOf(
                                                "physicalDimension",
                                                "quantitySpec"
                                            )
                                        } + ("type" to JsonPrimitive("BOOLEAN_ITEMS"))
                                    )
                                )
                            )
                        )
                    )
                )
            )
        )
        listOf("physicalDimension", "quantitySpec").forEach { omitted ->
            assertInvalid(
                JsonObject(
                    opportunity + (
                        "requiredInputs" to JsonArray(
                            listOf(
                                JsonObject(scalar.filterKeys { it != omitted }),
                                series
                            )
                        )
                    )
                )
            )
        }
        assertDuplicateRejectedByRuntime(
            JsonObject(
                opportunity + (
                    "requiredInputs" to JsonArray(
                        listOf(scalar, JsonObject(scalar))
                    )
                )
            ),
            "Tool opportunity input field names must be unique"
        )
        assertInvalid(
            JsonObject(
                opportunity + ("requiredInputs" to JsonArray(listOf(scalar)))
            )
        )
        assertDuplicateRejectedByRuntime(
            JsonObject(
                opportunity + (
                    "promotionTestIds" to JsonArray(
                        listOf(JsonPrimitive("origin-test"), JsonPrimitive("origin-test"))
                    )
                )
            ),
            "Duplicate tool-opportunity promotion test ID"
        )
        assertInvalid(
            JsonObject(
                opportunity + (
                    "promotionTestIds" to JsonArray(
                        listOf(JsonPrimitive("origin-test"))
                    )
                )
            )
        )
        assertInvalid(
            JsonObject(
                opportunity + (
                    "execution" to JsonObject(
                        opportunity.getValue("execution").jsonObject +
                            ("type" to JsonPrimitive("VALIDATION"))
                    )
                )
            )
        )
        assertInvalid(
            JsonObject(
                opportunity + (
                    "requiredInputs" to JsonArray(
                        listOf(
                            JsonObject(scalar + ("quantitySpec" to JsonNull)),
                            series
                        )
                    )
                )
            )
        )
    }

    private fun validate(
        document: CodexOutputSchemaDocument,
        response: JsonObject
    ): List<String> = CodexStructuredOutputValidator.validate(
        document,
        response.toString().toByteArray(Charsets.UTF_8)
    )

    private fun JsonObject.resolveReference(definitions: JsonObject): JsonObject {
        val reference = this["\$ref"]?.jsonPrimitive?.content ?: return this
        return definitions.getValue(reference.substringAfterLast('/')).jsonObject
    }

    private fun task(nodes: List<LocalLimitationNode>): TaskAnatomistOutput =
        TaskAnatomistOutput(
            receiverContract = ReceiverContractDraft(
                receiverKey = "operator",
                receiverDescription = "Operator",
                situation = "Decision",
                decisionOrUse = "Select an action",
                whyNeeded = "Reduce uncertainty",
                successOutcomes = listOf("Supported"),
                unacceptableOutcomes = listOf("Invented"),
                constraints = listOf("Auditable"),
                evidenceProfile = EvidenceProfile()
            ),
            receiverObligations = mapOf("supported" to "Support the decision"),
            nodes = nodes,
            rootNodeKeys = setOf(nodes.first().key),
            atomicityReport = nodes.map {
                NodeAtomicityAssessment(it.key, true, it.estimatedEffort, "Bounded")
            }
        )

    private fun node(
        key: String,
        toolOpportunity: ToolOpportunity? = null
    ): LocalLimitationNode = LocalLimitationNode(
        key = LocalNodeKey(key),
        kind = if (toolOpportunity == null) NodeKind.EVIDENCE else NodeKind.TOOL,
        layer = 0,
        receiverObligationKey = "supported",
        limitationStatement = "Evidence is missing.",
        capabilityTarget = "Evidence is established.",
        atomicQuestion = "What establishes the claim?",
        estimatedEffort = 1,
        proofContract = ProofContract(
            method = ProofMethod.EXECUTABLE_TEST,
            acceptanceCriteria = setOf("passes"),
            executableTestIds = setOf("$key-test")
        ),
        deliverableContract = NodeDeliverableContract(
            acceptedArtifactTypes = setOf(
                if (toolOpportunity == null) ArtifactType.EVIDENCE_TABLE else ArtifactType.TOOL
            )
        ),
        stageKey = if (key == "origin") "author" else "reuse",
        revealMode = RevealMode.INITIAL,
        replicationPolicy = ReplicationPolicy(),
        capturePolicy = CapturePolicy(minimumGrade = 0.7),
        rewardSpec = RewardSpec(RewardClass.SMALL, credits = 1.0),
        toolOpportunity = toolOpportunity
    )

    private fun opportunity(): ToolOpportunity = ToolOpportunity(
        opportunityId = "typed-opportunity",
        opportunityType = ToolOpportunityType.CALCULATION,
        repeatedQuestionPattern = "Calculate a bounded result.",
        requiredInputs = listOf(
            TypedField("value", TypedFieldType.NUMBER, "Input value.")
        ),
        requiredOutputs = listOf(
            TypedField("result", TypedFieldType.NUMBER, "Output value.")
        ),
        validationStrategy = "Run exact examples.",
        expectedReuseNodeIds = setOf(NodeId("reuse")),
        preferredImplementationMode = ToolImplementationMode.PYTHON,
        behavior = ToolBehavior.DETERMINISTIC,
        promotionTestIds = setOf("origin-test"),
        maximumRuntimeMillis = 1_000,
        maximumOutputBytes = 4_096
    )

    private fun quantitativeObligation(): QuantitativeToolObligation =
        QuantitativeToolObligation(
            horizonTimeBasis = TimeBasis.SECOND,
            horizonIntervalCount = 2,
            allowedOpportunityTypes = setOf(ToolOpportunityType.CALCULATION),
            requiredOpportunityId = "typed-opportunity",
            exactPromotionTestIds = setOf("origin-test", "origin-boundary-test"),
            maximumOpportunityRuntimeMillis = 1_000,
            requiredFields = listOf(
                QuantitativeToolFieldObligation(
                    roleId = "value",
                    fieldName = "value",
                    location = QuantitativeToolFieldLocation.INPUT,
                    shape = QuantitativeToolFieldShape.SCALAR,
                    quantitySpec = QuantitySpec(
                        dimension = PhysicalDimension(),
                        unitId = "1",
                        scaleToSi = 1.0,
                        aggregation = AggregationSemantics.INSTANTANEOUS
                    )
                ),
                QuantitativeToolFieldObligation(
                    roleId = "values",
                    fieldName = "values",
                    location = QuantitativeToolFieldLocation.INPUT,
                    shape = QuantitativeToolFieldShape.HORIZON_SERIES,
                    numericType = TypedFieldType.INTEGER,
                    quantitySpec = QuantitySpec(
                        dimension = PhysicalDimension(),
                        unitId = "1",
                        scaleToSi = 1.0,
                        aggregation = AggregationSemantics.SUM_OVER_INTERVALS,
                        timeBasis = TimeBasis.SECOND
                    )
                ),
                QuantitativeToolFieldObligation(
                    roleId = "result",
                    fieldName = "result",
                    location = QuantitativeToolFieldLocation.OUTPUT,
                    shape = QuantitativeToolFieldShape.SCALAR,
                    quantitySpec = QuantitySpec(
                        dimension = PhysicalDimension(),
                        unitId = "1",
                        scaleToSi = 1.0,
                        aggregation = AggregationSemantics.INSTANTANEOUS
                    )
                )
            )
        )

    private fun quantitativeOpportunity(): ToolOpportunity = opportunity().copy(
        requiredInputs = listOf(
            TypedField(
                name = "value",
                type = TypedFieldType.NUMBER,
                description = "Operator-required quantitative field role 'value'.",
                physicalDimension = PhysicalDimension(),
                quantitySpec = QuantitySpec(
                    dimension = PhysicalDimension(),
                    unitId = "1",
                    scaleToSi = 1.0,
                    aggregation = AggregationSemantics.INSTANTANEOUS
                )
            ),
            TypedField(
                name = "values",
                type = TypedFieldType.ARRAY,
                description = "Operator-required quantitative field role 'values'.",
                itemType = TypedFieldType.INTEGER,
                minimumItems = 2,
                maximumItems = 2,
                physicalDimension = PhysicalDimension(),
                quantitySpec = QuantitySpec(
                    dimension = PhysicalDimension(),
                    unitId = "1",
                    scaleToSi = 1.0,
                    aggregation = AggregationSemantics.SUM_OVER_INTERVALS,
                    timeBasis = TimeBasis.SECOND
                )
            )
        ),
        requiredOutputs = listOf(
            TypedField(
                name = "result",
                type = TypedFieldType.NUMBER,
                description = "Operator-required quantitative field role 'result'.",
                physicalDimension = PhysicalDimension(),
                quantitySpec = QuantitySpec(
                    dimension = PhysicalDimension(),
                    unitId = "1",
                    scaleToSi = 1.0,
                    aggregation = AggregationSemantics.INSTANTANEOUS
                )
            )
        ),
        promotionTestIds = setOf("origin-test", "origin-boundary-test")
    )
}
