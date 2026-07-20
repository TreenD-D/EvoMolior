@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.achulkov.evomolior.core.serialization

import com.achulkov.evomolior.core.atlas.CapturePolicy
import com.achulkov.evomolior.core.atlas.NodeDeliverableContract
import com.achulkov.evomolior.core.atlas.NodeKind
import com.achulkov.evomolior.core.atlas.ProofContract
import com.achulkov.evomolior.core.atlas.ReplicationPolicy
import com.achulkov.evomolior.core.atlas.RevealMode
import com.achulkov.evomolior.core.atlas.RewardSpec
import com.achulkov.evomolior.core.atlas.ToolOpportunity
import com.achulkov.evomolior.core.config.AcceptanceProfile
import com.achulkov.evomolior.core.council.AmbiguityBranch
import com.achulkov.evomolior.core.council.LocalLimitationNode
import com.achulkov.evomolior.core.council.LocalNodeKey
import com.achulkov.evomolior.core.council.NodeAtomicityAssessment
import com.achulkov.evomolior.core.council.ReceiverContractDraft
import com.achulkov.evomolior.core.council.TaskAnatomistOutput
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

@Serializable
private data class SmokeTaskAnatomistOutput(
    val receiverContract: ReceiverContractDraft,
    @Serializable(with = StringStringMapEntriesSerializer::class)
    val receiverObligations: Map<String, String>,
    val nodes: List<SmokeLocalLimitationNode>,
    val rootNodeKeys: Set<LocalNodeKey>,
    val atomicityReport: List<NodeAtomicityAssessment>,
    val ambiguityBranches: List<AmbiguityBranch> = emptyList()
)

@Serializable
private data class FullTaskAnatomistOutput(
    val receiverContract: ReceiverContractDraft,
    @Serializable(with = StringStringMapEntriesSerializer::class)
    val receiverObligations: Map<String, String>,
    val nodesWithoutToolOpportunity: List<OrdinalNodeWithoutToolOpportunity>,
    val nodesWithToolOpportunity: List<OrdinalNodeWithToolOpportunity>,
    val rootNodeKeys: Set<LocalNodeKey>,
    val atomicityReport: List<NodeAtomicityAssessment>,
    val ambiguityBranches: List<AmbiguityBranch> = emptyList()
)

@Serializable
private data class SmokeLocalLimitationNode(
    val key: LocalNodeKey,
    val kind: NodeKind,
    val layer: Int,
    val parentKeys: Set<LocalNodeKey> = emptySet(),
    val prerequisiteKeys: Set<LocalNodeKey> = emptySet(),
    val alternativeToKeys: Set<LocalNodeKey> = emptySet(),
    val receiverObligationKey: String,
    val limitationStatement: String,
    val capabilityTarget: String,
    val atomicQuestion: String,
    val estimatedEffort: Int,
    val proofContract: ProofContract,
    val deliverableContract: NodeDeliverableContract,
    val stageKey: String,
    val revealMode: RevealMode,
    val replicationPolicy: ReplicationPolicy,
    val capturePolicy: CapturePolicy,
    val rewardSpec: RewardSpec,
    @Serializable(with = NullOnlyToolOpportunitySerializer::class)
    val toolOpportunity: ToolOpportunity?
)

/**
 * Required smoke-wire field that accepts only JSON null.
 *
 * Its nullable primitive descriptor gives provider-schema binding a concrete required property;
 * [TaskAnatomistProviderSchemaContract.nullOnlyToolOpportunityFieldName] identifies that property
 * for replacement with the provider's native `{ "type": "null" }` schema. Runtime encoding and
 * decoding independently reject every non-null value.
 */
private object NullOnlyToolOpportunitySerializer : KSerializer<ToolOpportunity?> {
    override val descriptor: SerialDescriptor = String.serializer().nullable.descriptor

    override fun serialize(encoder: Encoder, value: ToolOpportunity?) {
        if (value != null) {
            throw SerializationException(
                "runtime-usability-smoke requires toolOpportunity to be null"
            )
        }
        encoder.encodeNull()
    }

    override fun deserialize(decoder: Decoder): ToolOpportunity? {
        if (decoder.decodeNotNullMark()) {
            throw SerializationException(
                "runtime-usability-smoke requires toolOpportunity to be null"
            )
        }
        decoder.decodeNull()
        return null
    }
}

@Serializable
private data class OrdinalNodeWithoutToolOpportunity(
    val ordinal: Int,
    val key: LocalNodeKey,
    val kind: NodeKind,
    val layer: Int,
    val parentKeys: Set<LocalNodeKey> = emptySet(),
    val prerequisiteKeys: Set<LocalNodeKey> = emptySet(),
    val alternativeToKeys: Set<LocalNodeKey> = emptySet(),
    val receiverObligationKey: String,
    val limitationStatement: String,
    val capabilityTarget: String,
    val atomicQuestion: String,
    val estimatedEffort: Int,
    val proofContract: ProofContract,
    val deliverableContract: NodeDeliverableContract,
    val stageKey: String,
    val revealMode: RevealMode,
    val replicationPolicy: ReplicationPolicy,
    val capturePolicy: CapturePolicy,
    val rewardSpec: RewardSpec,
    @Serializable(with = NullOnlyToolOpportunitySerializer::class)
    val toolOpportunity: ToolOpportunity?
)

@Serializable
private data class OrdinalNodeWithToolOpportunity(
    val ordinal: Int,
    val key: LocalNodeKey,
    val kind: NodeKind,
    val layer: Int,
    val parentKeys: Set<LocalNodeKey> = emptySet(),
    val prerequisiteKeys: Set<LocalNodeKey> = emptySet(),
    val alternativeToKeys: Set<LocalNodeKey> = emptySet(),
    val receiverObligationKey: String,
    val limitationStatement: String,
    val capabilityTarget: String,
    val atomicQuestion: String,
    val estimatedEffort: Int,
    val proofContract: ProofContract,
    val deliverableContract: NodeDeliverableContract,
    val stageKey: String,
    val revealMode: RevealMode,
    val replicationPolicy: ReplicationPolicy,
    val capturePolicy: CapturePolicy,
    val rewardSpec: RewardSpec,
    @Serializable(with = ToolOpportunityStructuredOutputSerializer::class)
    val toolOpportunity: ToolOpportunity
)

/**
 * Serializer-derived definition identities used to bind the Task Anatomist provider schema.
 *
 * @param outputDefinitionSerialName Active profile's output definition.
 * @param nodeWithoutToolDefinitionSerialName Null-only node definition.
 * @param nodeWithToolDefinitionSerialName Typed tool-opportunity node definition, or null for smoke.
 * @param nullOnlyToolOpportunityFieldName Required field to bind to native JSON null, or null for
 * full authority.
 */
data class TaskAnatomistProviderSchemaContract(
    val outputDefinitionSerialName: String,
    val nodeWithoutToolDefinitionSerialName: String,
    val nodeWithToolDefinitionSerialName: String?,
    val nullOnlyToolOpportunityFieldName: String?
)

/**
 * Acceptance-contextual provider serializer for Task Anatomist output.
 *
 * Smoke authority retains a single `nodes` list with a required null-only tool-opportunity field.
 * Appendix B authority uses disjoint ordinary and typed-tool partitions. Explicit
 * ordinals reconstruct the exact authored node order while rejecting ambiguous or incomplete
 * partitions. Persisted [TaskAnatomistOutput] and [LocalLimitationNode] remain unchanged.
 *
 * @param profile Explicit acceptance authority controlling the provider wire.
 */
class TaskAnatomistContextualOutputSerializer(
    val profile: AcceptanceProfile
) : KSerializer<TaskAnatomistOutput> {
    init {
        require(profile != AcceptanceProfile.NORMAL) {
            "Normal Task Anatomist authority must use the unchanged domain serializer"
        }
    }

    private val smokeDelegate = SmokeTaskAnatomistOutput.serializer()
    private val fullDelegate = FullTaskAnatomistOutput.serializer()
    private val isSmoke = profile == AcceptanceProfile.RUNTIME_USABILITY_SMOKE
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
        explicitNulls = true
    }

    override val descriptor: SerialDescriptor =
        if (isSmoke) smokeDelegate.descriptor else fullDelegate.descriptor

    /**
     * Serializer-derived definition names for the active profile.
     */
    val providerSchemaContract: TaskAnatomistProviderSchemaContract =
        if (isSmoke) {
            TaskAnatomistProviderSchemaContract(
                outputDefinitionSerialName = smokeDelegate.descriptor.serialName,
                nodeWithoutToolDefinitionSerialName =
                    SmokeLocalLimitationNode.serializer().descriptor.serialName,
                nodeWithToolDefinitionSerialName = null,
                nullOnlyToolOpportunityFieldName = TOOL_OPPORTUNITY_FIELD
            )
        } else {
            TaskAnatomistProviderSchemaContract(
                outputDefinitionSerialName = fullDelegate.descriptor.serialName,
                nodeWithoutToolDefinitionSerialName =
                    OrdinalNodeWithoutToolOpportunity.serializer().descriptor.serialName,
                nodeWithToolDefinitionSerialName =
                    OrdinalNodeWithToolOpportunity.serializer().descriptor.serialName,
                nullOnlyToolOpportunityFieldName = TOOL_OPPORTUNITY_FIELD
            )
        }

    override fun serialize(encoder: Encoder, value: TaskAnatomistOutput) {
        if (isSmoke) {
            encoder.encodeSerializableValue(smokeDelegate, value.toSmokeOutput())
        } else {
            encoder.encodeSerializableValue(fullDelegate, value.toFullOutput())
        }
    }

    override fun deserialize(decoder: Decoder): TaskAnatomistOutput =
        if (isSmoke) {
            decoder.decodeSerializableValue(smokeDelegate).toDomainOutput()
        } else {
            decoder.decodeSerializableValue(fullDelegate).toDomainOutput()
        }

    /**
     * Encodes a domain value using the exact provider wire for this serializer's authority.
     *
     * @param value Domain Task Anatomist output.
     * @return Strict provider-facing JSON.
     */
    fun encodeProviderWire(value: TaskAnatomistOutput): JsonElement =
        json.encodeToJsonElement(this, value)

    /**
     * Strictly decodes provider wire using this serializer's active authority.
     *
     * @param element Parsed provider response.
     * @return Reconstructed persisted domain output.
     */
    fun decodeProviderWire(element: JsonElement): TaskAnatomistOutput =
        json.decodeFromJsonElement(this, element)

    companion object {
        private const val TOOL_OPPORTUNITY_FIELD = "toolOpportunity"
    }

    private fun TaskAnatomistOutput.toSmokeOutput(): SmokeTaskAnatomistOutput {
        nodes.forEachIndexed { index, node ->
            if (node.toolOpportunity != null) {
                throw SerializationException(
                    "nodes.$index.toolOpportunity: runtime-usability-smoke requires null"
                )
            }
        }
        return SmokeTaskAnatomistOutput(
            receiverContract = receiverContract,
            receiverObligations = receiverObligations,
            nodes = nodes.map(LocalLimitationNode::toSmokeNode),
            rootNodeKeys = rootNodeKeys,
            atomicityReport = atomicityReport,
            ambiguityBranches = ambiguityBranches
        )
    }

    private fun TaskAnatomistOutput.toFullOutput(): FullTaskAnatomistOutput {
        val withoutTools = mutableListOf<OrdinalNodeWithoutToolOpportunity>()
        val withTools = mutableListOf<OrdinalNodeWithToolOpportunity>()
        nodes.forEachIndexed { ordinal, node ->
            val opportunity = node.toolOpportunity
            if (opportunity == null) {
                withoutTools += node.toOrdinaryNode(ordinal)
            } else {
                withTools += node.toTypedToolNode(ordinal, opportunity)
            }
        }
        if (withTools.isEmpty()) {
            throw SerializationException(
                "nodesWithToolOpportunity: full provider wire requires a non-empty opportunity partition"
            )
        }
        return FullTaskAnatomistOutput(
            receiverContract = receiverContract,
            receiverObligations = receiverObligations,
            nodesWithoutToolOpportunity = withoutTools,
            nodesWithToolOpportunity = withTools,
            rootNodeKeys = rootNodeKeys,
            atomicityReport = atomicityReport,
            ambiguityBranches = ambiguityBranches
        )
    }

    private fun SmokeTaskAnatomistOutput.toDomainOutput(): TaskAnatomistOutput =
        TaskAnatomistOutput(
            receiverContract = receiverContract,
            receiverObligations = receiverObligations,
            nodes = nodes.map(SmokeLocalLimitationNode::toDomainNode),
            rootNodeKeys = rootNodeKeys,
            atomicityReport = atomicityReport,
            ambiguityBranches = ambiguityBranches
        )

    private fun FullTaskAnatomistOutput.toDomainOutput(): TaskAnatomistOutput {
        if (nodesWithToolOpportunity.isEmpty()) {
            throw SerializationException(
                "nodesWithToolOpportunity: full provider wire requires a non-empty opportunity partition"
            )
        }
        val partitionedNodes = nodesWithoutToolOpportunity.map { it.ordinal to it.toDomainNode() } +
            nodesWithToolOpportunity.map { it.ordinal to it.toDomainNode() }
        val nodeCount = partitionedNodes.size
        val nodesByOrdinal = arrayOfNulls<LocalLimitationNode>(nodeCount)
        partitionedNodes.forEach { (ordinal, node) ->
            if (ordinal !in 0 until nodeCount) {
                throw SerializationException(
                    "Node ordinal $ordinal is outside the required range 0..${nodeCount - 1}"
                )
            }
            if (nodesByOrdinal[ordinal] != null) {
                throw SerializationException("Duplicate node ordinal $ordinal")
            }
            nodesByOrdinal[ordinal] = node
        }
        val missingOrdinal = nodesByOrdinal.indexOfFirst { it == null }
        if (missingOrdinal >= 0) {
            throw SerializationException("Missing node ordinal $missingOrdinal")
        }
        return TaskAnatomistOutput(
            receiverContract = receiverContract,
            receiverObligations = receiverObligations,
            nodes = nodesByOrdinal.map { requireNotNull(it) },
            rootNodeKeys = rootNodeKeys,
            atomicityReport = atomicityReport,
            ambiguityBranches = ambiguityBranches
        )
    }
}

private fun LocalLimitationNode.toSmokeNode(): SmokeLocalLimitationNode =
    SmokeLocalLimitationNode(
        key, kind, layer, parentKeys, prerequisiteKeys, alternativeToKeys,
        receiverObligationKey, limitationStatement, capabilityTarget, atomicQuestion,
        estimatedEffort, proofContract, deliverableContract, stageKey, revealMode,
        replicationPolicy, capturePolicy, rewardSpec, null
    )

private fun LocalLimitationNode.toOrdinaryNode(
    ordinal: Int
): OrdinalNodeWithoutToolOpportunity = OrdinalNodeWithoutToolOpportunity(
    ordinal, key, kind, layer, parentKeys, prerequisiteKeys, alternativeToKeys,
    receiverObligationKey, limitationStatement, capabilityTarget, atomicQuestion,
    estimatedEffort, proofContract, deliverableContract, stageKey, revealMode,
    replicationPolicy, capturePolicy, rewardSpec, null
)

private fun LocalLimitationNode.toTypedToolNode(
    ordinal: Int,
    opportunity: ToolOpportunity
): OrdinalNodeWithToolOpportunity = OrdinalNodeWithToolOpportunity(
    ordinal, key, kind, layer, parentKeys, prerequisiteKeys, alternativeToKeys,
    receiverObligationKey, limitationStatement, capabilityTarget, atomicQuestion,
    estimatedEffort, proofContract, deliverableContract, stageKey, revealMode,
    replicationPolicy, capturePolicy, rewardSpec, opportunity
)

private fun SmokeLocalLimitationNode.toDomainNode(): LocalLimitationNode =
    LocalLimitationNode(
        key, kind, layer, parentKeys, prerequisiteKeys, alternativeToKeys,
        receiverObligationKey, limitationStatement, capabilityTarget, atomicQuestion,
        estimatedEffort, proofContract, deliverableContract, stageKey, revealMode,
        replicationPolicy, capturePolicy, rewardSpec
    )

private fun OrdinalNodeWithoutToolOpportunity.toDomainNode(): LocalLimitationNode =
    LocalLimitationNode(
        key, kind, layer, parentKeys, prerequisiteKeys, alternativeToKeys,
        receiverObligationKey, limitationStatement, capabilityTarget, atomicQuestion,
        estimatedEffort, proofContract, deliverableContract, stageKey, revealMode,
        replicationPolicy, capturePolicy, rewardSpec
    )

private fun OrdinalNodeWithToolOpportunity.toDomainNode(): LocalLimitationNode =
    LocalLimitationNode(
        key, kind, layer, parentKeys, prerequisiteKeys, alternativeToKeys,
        receiverObligationKey, limitationStatement, capabilityTarget, atomicQuestion,
        estimatedEffort, proofContract, deliverableContract, stageKey, revealMode,
        replicationPolicy, capturePolicy, rewardSpec, toolOpportunity
    )
