package com.achulkov.evomolior.core.serialization

import com.achulkov.evomolior.core.atlas.ArtifactType
import com.achulkov.evomolior.core.atlas.CapturePolicy
import com.achulkov.evomolior.core.atlas.EvidenceKind
import com.achulkov.evomolior.core.atlas.EvidenceProfile
import com.achulkov.evomolior.core.atlas.NodeDeliverableContract
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.atlas.NodeKind
import com.achulkov.evomolior.core.atlas.PhysicalDimension
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
import com.achulkov.evomolior.core.council.ReceiverContractDraft
import com.achulkov.evomolior.core.council.TaskAnatomistOutput
import com.achulkov.evomolior.core.repair.AggregationSemantics
import com.achulkov.evomolior.core.repair.QuantitySpec
import com.achulkov.evomolior.core.repair.TimeBasis
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TaskAnatomistContextualOutputSerializerTest {
    @Test
    fun `contextual provider wire does not alter persisted task anatomist contracts`() {
        val output = output(listOf(node("ordinary"), node("typed", toolOpportunity())))
        val persistedJson = Json { explicitNulls = true }
        val wire = persistedJson.encodeToJsonElement(TaskAnatomistOutput.serializer(), output)
            .jsonObject

        assertTrue("nodes" in wire)
        assertFalse("nodesWithoutToolOpportunity" in wire)
        assertNotNull(
            wire.getValue("nodes").jsonArray.last().jsonObject["toolOpportunity"]
        )
        assertEquals(
            output,
            persistedJson.decodeFromJsonElement(TaskAnatomistOutput.serializer(), wire)
        )
    }

    @Test
    fun `persisted task wire retains default-null quantity compatibility`() {
        val output = output(listOf(node("typed", quantityToolOpportunity())))
        val persistedJson = Json {
            explicitNulls = false
            encodeDefaults = false
        }
        val wire = persistedJson.encodeToJsonElement(
            TaskAnatomistOutput.serializer(),
            output
        ).jsonObject
        val quantity = wire.getValue("nodes")
            .jsonArray
            .single()
            .jsonObject
            .getValue("toolOpportunity")
            .jsonObject
            .getValue("requiredInputs")
            .jsonArray
            .single()
            .jsonObject
            .getValue("quantitySpec")
            .jsonObject

        assertFalse("timeBasis" in quantity)
        assertEquals(
            output,
            persistedJson.decodeFromJsonElement(TaskAnatomistOutput.serializer(), wire)
        )
    }

    @Test
    fun `smoke wire keeps nodes and rejects every tool opportunity`() {
        val serializer = TaskAnatomistContextualOutputSerializer(
            AcceptanceProfile.RUNTIME_USABILITY_SMOKE
        )
        val output = output(listOf(node("ordinary")))
        val wire = serializer.encodeProviderWire(output).jsonObject

        assertTrue("nodes" in wire)
        assertFalse("nodesWithoutToolOpportunity" in wire)
        assertEquals(
            JsonNull,
            wire.getValue("nodes").jsonArray.single().jsonObject["toolOpportunity"]
        )
        assertEquals(output, serializer.decodeProviderWire(wire))
        assertFailsWith<SerializationException> {
            serializer.encodeProviderWire(output(listOf(node("typed", toolOpportunity()))))
        }
        assertRejected(
            serializer,
            JsonObject(
                wire + (
                    "nodes" to JsonArray(
                        listOf(
                            JsonObject(
                                wire.getValue("nodes").jsonArray.single().jsonObject +
                                    ("toolOpportunity" to JsonObject(emptyMap()))
                            )
                        )
                    )
                )
            )
        )
        assertEquals(null, serializer.providerSchemaContract.nodeWithToolDefinitionSerialName)
        assertEquals(
            "toolOpportunity",
            serializer.providerSchemaContract.nullOnlyToolOpportunityFieldName
        )
    }

    @Test
    fun `full wire partitions typed opportunities and reconstructs exact order`() {
        val output = output(
            listOf(
                node("first", toolOpportunity("first-tool")),
                node("second"),
                node("third", toolOpportunity("third-tool"))
            )
        )
        val serializer = TaskAnatomistContextualOutputSerializer(
            AcceptanceProfile.APPENDIX_B_FULL
        )
        val wire = serializer.encodeProviderWire(output).jsonObject
        val ordinary = wire.getValue("nodesWithoutToolOpportunity").jsonArray
        val typed = wire.getValue("nodesWithToolOpportunity").jsonArray

        assertEquals(listOf(1), ordinary.map { it.jsonObject.ordinal() })
        assertEquals(listOf(0, 2), typed.map { it.jsonObject.ordinal() })
        assertEquals(JsonNull, ordinary.single().jsonObject["toolOpportunity"])
        assertNotNull(typed.first().jsonObject["toolOpportunity"])
        assertEquals(output, serializer.decodeProviderWire(wire))
        assertNotNull(serializer.providerSchemaContract.nodeWithToolDefinitionSerialName)
        assertEquals(
            "toolOpportunity",
            serializer.providerSchemaContract.nullOnlyToolOpportunityFieldName
        )
        assertFailsWith<IllegalArgumentException> {
            TaskAnatomistContextualOutputSerializer(AcceptanceProfile.NORMAL)
        }
    }

    @Test
    fun `full wire rejects invalid partitions and ordinals`() {
        val serializer = TaskAnatomistContextualOutputSerializer(
            AcceptanceProfile.APPENDIX_B_FULL
        )
        assertFailsWith<SerializationException> {
            serializer.encodeProviderWire(output(listOf(node("ordinary"))))
        }

        val valid = serializer.encodeProviderWire(
            output(listOf(node("ordinary"), node("typed", toolOpportunity())))
        ).jsonObject
        val ordinary = valid.getValue("nodesWithoutToolOpportunity").jsonArray
        val typed = valid.getValue("nodesWithToolOpportunity").jsonArray

        assertRejected(serializer, valid.withTyped(JsonArray(emptyList())))
        assertRejected(
            serializer,
            valid.withTyped(
                JsonArray(
                    listOf(
                        JsonObject(typed.single().jsonObject + ("ordinal" to JsonPrimitive(0)))
                    )
                )
            )
        )
        assertRejected(
            serializer,
            valid.withTyped(
                JsonArray(
                    listOf(
                        JsonObject(typed.single().jsonObject + ("ordinal" to JsonPrimitive(2)))
                    )
                )
            )
        )
        assertRejected(
            serializer,
            valid.withTyped(
                JsonArray(
                    listOf(
                        JsonObject(typed.single().jsonObject + ("toolOpportunity" to JsonNull))
                    )
                )
            )
        )
        assertRejected(
            serializer,
            JsonObject(
                valid + (
                    "nodesWithoutToolOpportunity" to JsonArray(
                        listOf(
                            JsonObject(
                                ordinary.single().jsonObject +
                                    ("toolOpportunity" to typed.single().jsonObject.getValue(
                                        "toolOpportunity"
                                    ))
                            )
                        )
                    )
                )
            )
        )
    }

    @Test
    fun `full wire requires explicit nullable time basis inside quantity specs`() {
        val serializer = TaskAnatomistContextualOutputSerializer(
            AcceptanceProfile.APPENDIX_B_FULL
        )
        val output = output(listOf(node("typed", quantityToolOpportunity())))
        val wire = serializer.encodeProviderWire(output).jsonObject
        val typedNode = wire.getValue("nodesWithToolOpportunity")
            .jsonArray
            .single()
            .jsonObject
        val opportunity = typedNode.getValue("toolOpportunity").jsonObject
        val input = opportunity.getValue("requiredInputs").jsonArray.single().jsonObject
        val inputQuantity = input.getValue("quantitySpec").jsonObject
        val outputItems = opportunity.getValue("requiredOutputs")
            .jsonArray
            .single()
            .jsonObject
            .getValue("items")
            .jsonObject
        val outputQuantity = outputItems.getValue("quantitySpec").jsonObject

        assertEquals(JsonNull, inputQuantity.getValue("timeBasis"))
        assertEquals(
            TimeBasis.SECOND.name,
            outputQuantity.getValue("timeBasis").jsonPrimitive.content
        )
        assertEquals(output, serializer.decodeProviderWire(wire))

        val outputWithoutTimeBasis = JsonObject(
            outputQuantity - "timeBasis"
        )
        val outputItemsWithoutTimeBasis = JsonObject(
            outputItems + ("quantitySpec" to outputWithoutTimeBasis)
        )
        val outputField = opportunity.getValue("requiredOutputs")
            .jsonArray
            .single()
            .jsonObject
        val outputFieldWithoutTimeBasis = JsonObject(
            outputField + ("items" to outputItemsWithoutTimeBasis)
        )
        val opportunityWithoutTimeBasis = JsonObject(
            opportunity + (
                "requiredOutputs" to JsonArray(listOf(outputFieldWithoutTimeBasis))
            )
        )
        assertFailsWith<IllegalArgumentException> {
            serializer.decodeProviderWire(
                JsonObject(
                wire + (
                    "nodesWithToolOpportunity" to JsonArray(
                        listOf(
                            JsonObject(
                                typedNode + (
                                    "toolOpportunity" to opportunityWithoutTimeBasis
                                )
                            )
                        )
                    )
                )
            )
            )
        }
    }

    private fun assertRejected(
        serializer: TaskAnatomistContextualOutputSerializer,
        wire: JsonObject
    ) {
        assertFailsWith<SerializationException> {
            serializer.decodeProviderWire(wire)
        }
    }

    private fun JsonObject.withTyped(value: JsonArray): JsonObject =
        JsonObject(this + ("nodesWithToolOpportunity" to value))

    private fun JsonObject.ordinal(): Int = getValue("ordinal").jsonPrimitive.content.toInt()

    private fun output(nodes: List<LocalLimitationNode>): TaskAnatomistOutput =
        TaskAnatomistOutput(
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
            receiverObligations = linkedMapOf("supported" to "Support claims"),
            nodes = nodes,
            rootNodeKeys = nodes.mapTo(linkedSetOf(), LocalLimitationNode::key),
            atomicityReport = nodes.map {
                NodeAtomicityAssessment(it.key, true, it.estimatedEffort, "Bounded")
            }
        )

    private fun node(
        key: String,
        opportunity: ToolOpportunity? = null
    ): LocalLimitationNode = LocalLimitationNode(
        key = LocalNodeKey(key),
        kind = NodeKind.EVIDENCE,
        layer = 0,
        receiverObligationKey = "supported",
        limitationStatement = "Evidence is missing.",
        capabilityTarget = "Evidence is established.",
        atomicQuestion = "Which source establishes the claim?",
        estimatedEffort = 2,
        proofContract = ProofContract(
            method = ProofMethod.SOURCE_CORROBORATION,
            acceptanceCriteria = setOf("primary"),
            requiredEvidenceKinds = setOf(EvidenceKind.PRIMARY_SOURCE)
        ),
        deliverableContract = NodeDeliverableContract(
            acceptedArtifactTypes = setOf(ArtifactType.EVIDENCE_TABLE)
        ),
        stageKey = "foundations",
        revealMode = RevealMode.INITIAL,
        replicationPolicy = ReplicationPolicy(),
        capturePolicy = CapturePolicy(minimumGrade = 0.7),
        rewardSpec = RewardSpec(RewardClass.SMALL, credits = 2.0),
        toolOpportunity = opportunity
    )

    private fun toolOpportunity(id: String = "typed-tool"): ToolOpportunity = ToolOpportunity(
        opportunityId = id,
        opportunityType = ToolOpportunityType.CALCULATION,
        repeatedQuestionPattern = "Calculate one bounded result.",
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
        promotionTestIds = setOf("promotion"),
        maximumRuntimeMillis = 1_000,
        maximumOutputBytes = 4_096
    )

    private fun quantityToolOpportunity(): ToolOpportunity {
        val dimensionless = PhysicalDimension()
        return toolOpportunity("quantity-tool").copy(
            requiredInputs = listOf(
                TypedField(
                    name = "interval_count",
                    type = TypedFieldType.INTEGER,
                    description = "Number of bounded intervals.",
                    physicalDimension = dimensionless,
                    quantitySpec = QuantitySpec(
                        dimension = dimensionless,
                        unitId = "1",
                        scaleToSi = 1.0,
                        aggregation = AggregationSemantics.INSTANTANEOUS
                    )
                )
            ),
            requiredOutputs = listOf(
                TypedField(
                    name = "interval_rates",
                    type = TypedFieldType.ARRAY,
                    description = "One bounded rate per interval.",
                    itemType = TypedFieldType.NUMBER,
                    minimumItems = 2,
                    maximumItems = 2,
                    physicalDimension = dimensionless,
                    quantitySpec = QuantitySpec(
                        dimension = dimensionless,
                        unitId = "%",
                        scaleToSi = 0.01,
                        aggregation = AggregationSemantics.PER_INTERVAL_RATE,
                        timeBasis = TimeBasis.SECOND
                    )
                )
            )
        )
    }
}
