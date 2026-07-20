package com.achulkov.evomolior.core.atlas

import com.achulkov.evomolior.core.serialization.StringIntMapEntriesSerializer
import com.achulkov.evomolior.core.serialization.TypedFieldStructuredOutputSerializer
import kotlinx.serialization.Serializable

/** Stable identifier for a limitation node. */
@JvmInline
@Serializable
value class NodeId(val value: String)

/** Stable identifier for a worker group. */
@JvmInline
@Serializable
value class AtlasGroupId(val value: String)

/** Stable identifier for an agent recorded in Atlas lineage. */
@JvmInline
@Serializable
value class AtlasAgentId(val value: String)

/**
 * Immutable description of the receiver and the outcome expected from a run.
 *
 * @param rawObjective Exact, unnormalised operator input.
 * @param receiverId Stable receiver identifier.
 * @param receiverDescription Human-readable receiver description.
 * @param situation Situation in which the deliverable will be used.
 * @param decisionOrUse Decision or use enabled by the deliverable.
 * @param whyNeeded Receiver rationale.
 * @param successOutcomes Required successful outcomes.
 * @param unacceptableOutcomes Outcomes that make the deliverable unacceptable.
 * @param constraints Receiver and operator constraints.
 * @param finalDeliverableContract Required final output.
 * @param evidenceProfile Evidence quality requirements.
 */
@Serializable
data class ReceiverContract(
    val rawObjective: String,
    val receiverId: String,
    val receiverDescription: String,
    val situation: String,
    val decisionOrUse: String,
    val whyNeeded: String,
    val successOutcomes: List<String>,
    val unacceptableOutcomes: List<String>,
    val constraints: List<String>,
    val finalDeliverableContract: FinalOutputSpec,
    val evidenceProfile: EvidenceProfile
)

/**
 * Atlas-local final publication contract.
 *
 * @param relativePath Relative path of the output artifact.
 * @param mediaType Output MIME type.
 * @param description Receiver-facing description.
 * @param requiredSections Required logical sections, independent of order.
 * @param minimumEvidenceItems Minimum evidence items in the output.
 */
@Serializable
data class FinalOutputSpec(
    val relativePath: String,
    val mediaType: String,
    val description: String = "",
    val requiredSections: Set<String> = emptySet(),
    val minimumEvidenceItems: Int = 0
)

/**
 * Evidence requirements applying to the receiver contract.
 *
 * @param minimumIndependentSources Minimum independent sources per material claim.
 * @param acceptedEvidenceKinds Accepted evidence classes.
 * @param requiredSourceDomains Required source domains, independent of order.
 * @param requirePrimarySources Whether primary sources are mandatory when available.
 * @param requireReproducibility Whether evidence must include reproducibility material.
 * @param maximumEvidenceAgeDays Optional maximum evidence age.
 */
@Serializable
data class EvidenceProfile(
    val minimumIndependentSources: Int = 1,
    val acceptedEvidenceKinds: Set<EvidenceKind> = setOf(EvidenceKind.PRIMARY_SOURCE),
    val requiredSourceDomains: Set<String> = emptySet(),
    val requirePrimarySources: Boolean = true,
    val requireReproducibility: Boolean = false,
    val maximumEvidenceAgeDays: Int? = null
)

/** Evidence classes recognized by an [EvidenceProfile]. */
@Serializable
enum class EvidenceKind {
    PRIMARY_SOURCE,
    PEER_REVIEWED,
    OFFICIAL_DATA,
    EXPERIMENTAL,
    OBSERVATIONAL,
    EXPERT_JUDGMENT,
    SYNTHETIC
}

/**
 * Immutable, versioned source of truth for the limitation graph.
 *
 * @param atlasId Stable Atlas identity across versions.
 * @param runId Run owning this Atlas.
 * @param version Monotonically increasing version, starting at one.
 * @param receiverContractHash Hash of the exact receiver contract.
 * @param rootNodeIds Graph roots.
 * @param nodes Nodes keyed by their stable IDs.
 * @param stageDefinitions Ordered disclosure stages.
 * @param atlasHash Hash of this Atlas excluding this field.
 * @param parentAtlasHash Hash of the immediately preceding version.
 * @param amendmentIds Accepted amendment lineage.
 */
@Serializable
data class NodeAtlas(
    val atlasId: String,
    val runId: String,
    val version: Int,
    val receiverContractHash: String,
    val rootNodeIds: Set<NodeId>,
    val nodes: Map<NodeId, LimitationNode>,
    val stageDefinitions: List<DisclosureStage>,
    val atlasHash: String = "",
    val parentAtlasHash: String? = null,
    val amendmentIds: List<String> = emptyList()
) {
    /**
     * Returns a copy carrying the deterministic Atlas hash.
     *
     * @return Hashed Atlas copy.
     */
    fun withComputedHash(): NodeAtlas = copy(atlasHash = AtlasHashing.hashAtlas(this))

    /**
     * Verifies hashes, graph structure, and optional version lineage.
     *
     * @param parent Previous Atlas version, when this is not the first version.
     * @return Validation result containing every detected violation.
     */
    fun validate(parent: NodeAtlas? = null): AtlasValidationResult =
        AtlasValidator.validate(this, parent)
}

/**
 * One independently verifiable limitation or capability in the Atlas DAG.
 *
 * @param id Stable node ID.
 * @param kind Semantic node kind.
 * @param layer Disclosure/decomposition depth.
 * @param parentIds Reverse-decomposition parents.
 * @param prerequisiteIds Nodes that must complete first.
 * @param alternativeToIds Nodes this node can replace.
 * @param receiverWhyRef Receiver obligation reference.
 * @param limitationStatement Limitation removed by this node.
 * @param capabilityTarget Capability produced by successful work.
 * @param atomicQuestion Single bounded question assigned to an agent.
 * @param proofContract Observable proof requirements.
 * @param deliverableContract Accepted node artifact contract.
 * @param revealPolicy Disclosure policy.
 * @param replicationPolicy Independent replication policy.
 * @param capturePolicy Competitive capture policy.
 * @param rewardSpec Reward and yield policy.
 * @param toolOpportunity Optional reusable-tool opportunity.
 * @param payloadHash Hash of the separately committed hidden payload.
 * @param status Definition lifecycle status.
 */
@Serializable
data class LimitationNode(
    val id: NodeId,
    val kind: NodeKind,
    val layer: Int,
    val parentIds: Set<NodeId>,
    val prerequisiteIds: Set<NodeId>,
    val alternativeToIds: Set<NodeId> = emptySet(),
    val receiverWhyRef: String,
    val limitationStatement: String,
    val capabilityTarget: String,
    val atomicQuestion: String,
    val proofContract: ProofContract,
    val deliverableContract: NodeDeliverableContract,
    val revealPolicy: RevealPolicy,
    val replicationPolicy: ReplicationPolicy,
    val capturePolicy: CapturePolicy,
    val rewardSpec: RewardSpec,
    val toolOpportunity: ToolOpportunity? = null,
    val payloadHash: String = "",
    val status: NodeStatus = NodeStatus.SEALED
) {
    /**
     * Returns the hidden material committed by [payloadHash].
     *
     * @return Stable hidden-payload record.
     */
    fun hiddenPayload(): HiddenNodePayload = HiddenNodePayload(
        receiverWhyRef = receiverWhyRef,
        limitationStatement = limitationStatement,
        capabilityTarget = capabilityTarget,
        atomicQuestion = atomicQuestion,
        proofContract = proofContract,
        deliverableContract = deliverableContract,
        toolOpportunity = toolOpportunity
    )

    /**
     * Returns a copy carrying the deterministic hidden-payload hash.
     *
     * @return Hashed node copy.
     */
    fun withComputedPayloadHash(): LimitationNode =
        copy(payloadHash = AtlasHashing.hashHiddenPayload(hiddenPayload()))

    /**
     * Checks this node's hidden-payload commitment.
     *
     * @return `true` when [payloadHash] matches the current hidden fields.
     */
    fun hasValidPayloadHash(): Boolean =
        AtlasHashing.constantTimeEquals(payloadHash, AtlasHashing.hashHiddenPayload(hiddenPayload()))
}

/**
 * Material hidden until a node's reveal policy is satisfied.
 *
 * @param receiverWhyRef Receiver obligation reference.
 * @param limitationStatement Hidden limitation statement.
 * @param capabilityTarget Hidden capability target.
 * @param atomicQuestion Hidden atomic question.
 * @param proofContract Hidden proof details.
 * @param deliverableContract Hidden artifact relationship.
 * @param toolOpportunity Hidden reusable-tool opportunity.
 */
@Serializable
data class HiddenNodePayload(
    val receiverWhyRef: String,
    val limitationStatement: String,
    val capabilityTarget: String,
    val atomicQuestion: String,
    val proofContract: ProofContract,
    val deliverableContract: NodeDeliverableContract,
    val toolOpportunity: ToolOpportunity? = null
)

/** Semantic role of a node in the limitation graph. */
@Serializable
enum class NodeKind {
    KNOWLEDGE, EVIDENCE, HYPOTHESIS, MODEL, TOOL, SIMULATION, EXPERIMENT,
    VALIDATION, INTEGRATION, DECISION, DELIVERY
}

/** Immutable definition lifecycle status of an Atlas node. */
@Serializable
enum class NodeStatus {
    DRAFT, SEALED, REVEALED, DEPRECATED
}

/**
 * Observable conditions used to grade a node.
 *
 * @param method Primary proof method.
 * @param acceptanceCriteria Order-independent acceptance criteria.
 * @param requiredEvidenceKinds Required evidence classes.
 * @param suggestedSourceDomains Source domains that may reduce search cost after reveal.
 * @param executableTestIds Tests committed before worker reveal.
 * @param failureIsUseful Whether a well-supported negative result can satisfy the node.
 */
@Serializable
data class ProofContract(
    val method: ProofMethod,
    val acceptanceCriteria: Set<String>,
    val requiredEvidenceKinds: Set<EvidenceKind> = emptySet(),
    val suggestedSourceDomains: Set<String> = emptySet(),
    val executableTestIds: Set<String> = emptySet(),
    val failureIsUseful: Boolean = true
)

/** Supported node proof methods. */
@Serializable
enum class ProofMethod {
    SOURCE_CORROBORATION, REPRODUCTION, EXECUTABLE_TEST, MEASUREMENT, EXPERIMENT,
    SIMULATION, FORMAL_ANALYSIS, ADVERSARIAL_REVIEW, RECEIVER_VALIDATION
}

/**
 * Artifact contract for a node contribution.
 *
 * @param acceptedArtifactTypes Accepted artifact types.
 * @param requiredFields Required logical fields.
 * @param mediaTypes Accepted MIME types.
 * @param schemaJson Optional exact schema.
 * @param finalDeliverableRelationship Relationship to the receiver-facing final output.
 */
@Serializable
data class NodeDeliverableContract(
    val acceptedArtifactTypes: Set<ArtifactType>,
    val requiredFields: Set<String> = emptySet(),
    val mediaTypes: Set<String> = emptySet(),
    val schemaJson: String? = null,
    val finalDeliverableRelationship: String = ""
)

/** Artifact types producible by node work. */
@Serializable
enum class ArtifactType {
    CLAIM_SET, EVIDENCE_TABLE, DATASET, HYPOTHESIS, MODEL, SOURCE_CODE, TOOL,
    SIMULATION_RESULT, EXPERIMENT_REPORT, VALIDATION_REPORT, DECISION_RECORD,
    DELIVERABLE_FRAGMENT, FINAL_DELIVERABLE
}

/**
 * Conditions under which a node payload is disclosed.
 *
 * @param stageId Disclosure stage owning the payload.
 * @param mode Reveal trigger.
 * @param prerequisiteNodeIds Nodes whose completion can trigger reveal.
 * @param minimumRound Earliest round eligible for reveal.
 */
@Serializable
data class RevealPolicy(
    val stageId: String,
    val mode: RevealMode = RevealMode.STAGE_PASS,
    val prerequisiteNodeIds: Set<NodeId> = emptySet(),
    val minimumRound: Int = 0
)

/** Supported node reveal triggers. */
@Serializable
enum class RevealMode {
    INITIAL, STAGE_PASS, PREREQUISITES_COMPLETE, ACCEPTED_AMENDMENT, FINAL_DELIVERY
}

/**
 * Independent replication requirements for a node.
 *
 * @param mode Replication strategy.
 * @param requiredCount Number of independent accepted attempts.
 * @param blind Whether replicators are blind to earlier artifacts.
 */
@Serializable
data class ReplicationPolicy(
    val mode: ReplicationMode = ReplicationMode.NONE,
    val requiredCount: Int = 1,
    val blind: Boolean = false
)

/** Supported node replication strategies. */
@Serializable
enum class ReplicationMode {
    NONE, OPTIONAL, REQUIRED, ADVERSARIAL
}

/**
 * Competitive ownership rules for a node.
 *
 * @param mode Capture strategy.
 * @param minimumGrade Minimum normalized grade required for capture.
 * @param recaptureMargin Grade improvement required to displace an owner.
 * @param nearTieMargin Optional normalized-score distance that creates a typed contest or share.
 * @param maximumConsecutiveRounds Maximum uninterrupted ownership duration.
 */
@Serializable
data class CapturePolicy(
    val mode: CaptureMode = CaptureMode.GRADE_AUTHORITY,
    val minimumGrade: Double,
    val recaptureMargin: Double = 0.0,
    val nearTieMargin: Double? = null,
    val maximumConsecutiveRounds: Int? = null
)

/** Supported node capture strategies. */
@Serializable
enum class CaptureMode {
    NONE, GRADE_AUTHORITY, SHARED, FIRST_VALIDATED
}

/**
 * Reward attached to successful node work.
 *
 * @param rewardClass Public reward category.
 * @param credits Credit yield.
 * @param capabilityYields Capability tokens yielded by capture.
 * @param firstCompletionBonus Optional first validated completion bonus.
 * @param congestionLevel Public congestion indicator used by worker portfolio selection.
 */
@Serializable
data class RewardSpec(
    val rewardClass: RewardClass,
    val credits: Double,
    val capabilityYields: Set<String> = emptySet(),
    val firstCompletionBonus: Double = 0.0,
    val congestionLevel: Int = 0
)

/** Public reward magnitude classification. */
@Serializable
enum class RewardClass {
    NONE, SMALL, MEDIUM, LARGE, CRITICAL
}

/** Repeated work class for which the Council invites reusable infrastructure. */
@Serializable
enum class ToolOpportunityType {
    CALCULATION,
    EXTRACTION,
    CONSISTENCY_CHECK,
    SIMULATION,
    TRANSFORMATION,
    VALIDATION,
    FORMULA,
    RULE
}

/** Runtime-neutral field types supported by generated tool schemas. */
@Serializable
enum class TypedFieldType {
    STRING,
    INTEGER,
    NUMBER,
    BOOLEAN,
    JSON,
    ARRAY,
    OBJECT
}

/**
 * Canonical physical-dimension exponent vector.
 *
 * An empty vector is dimensionless. Base-dimension names are Council-defined stable identifiers;
 * derived dimensions are represented by combining their integer exponents.
 *
 * @param exponents Non-zero exponents keyed by base-dimension identifier.
 */
@Serializable
data class PhysicalDimension(
    @Serializable(with = StringIntMapEntriesSerializer::class)
    val exponents: Map<String, Int> = emptyMap()
) {
    init {
        require(exponents.keys.all { it.matches(DIMENSION_NAME) }) {
            "Physical dimension names must be stable identifiers"
        }
        require(exponents.values.none { it == 0 }) {
            "Physical dimension vectors must omit zero exponents"
        }
    }

    private companion object {
        val DIMENSION_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
    }
}

/**
 * One field in a Council-authored tool input or output contract.
 *
 * @param name Stable schema field name.
 * @param type Runtime-neutral field type.
 * @param description Human-readable semantic meaning.
 * @param required Whether the field must be present.
 * @param itemType Element type for an array field.
 * @param minimumItems Inclusive lower cardinality bound for an array field.
 * @param maximumItems Inclusive upper cardinality bound for an array field.
 * @param objectFields Closed nested properties for an object field or object-valued array item.
 * @param enumValues Optional closed set of accepted string values.
 * @param validationRules Deterministic validation commitments.
 * @param physicalDimension Physical dimension of a numeric value or numeric array item. An empty
 * vector is explicitly dimensionless; null preserves legacy/direct opportunity compatibility.
 */
@Serializable(with = TypedFieldStructuredOutputSerializer::class)
data class TypedField(
    val name: String,
    val type: TypedFieldType,
    val description: String,
    val required: Boolean = true,
    val itemType: TypedFieldType? = null,
    val minimumItems: Int? = null,
    val maximumItems: Int? = null,
    val objectFields: Set<TypedField> = emptySet(),
    val enumValues: Set<String> = emptySet(),
    val validationRules: Set<String> = emptySet(),
    val physicalDimension: PhysicalDimension? = null
) {
    init {
        require(name.matches(FIELD_NAME)) { "Typed field name must be a JSON-compatible identifier" }
        require(description.isNotBlank()) { "Typed field description must not be blank" }
        require((type == TypedFieldType.ARRAY) == (itemType != null)) {
            "Exactly array fields must declare itemType"
        }
        require(itemType != TypedFieldType.ARRAY) { "Nested arrays require an explicit JSON field" }
        require(
            (type == TypedFieldType.ARRAY) ==
                (minimumItems != null && maximumItems != null)
        ) {
            "Exactly array fields must declare minimumItems and maximumItems"
        }
        if (type == TypedFieldType.ARRAY) {
            val minimum = requireNotNull(minimumItems)
            val maximum = requireNotNull(maximumItems)
            require(
                minimum >= 0 &&
                    maximum >= minimum &&
                    maximum <= MAXIMUM_ITEM_COUNT
            ) {
                "Array bounds must be ordered within 0..$MAXIMUM_ITEM_COUNT"
            }
        }
        val ownsObjectFields =
            type == TypedFieldType.OBJECT ||
                (type == TypedFieldType.ARRAY && itemType == TypedFieldType.OBJECT)
        require(ownsObjectFields == objectFields.isNotEmpty()) {
            "Exactly object fields and object-valued arrays must declare objectFields"
        }
        require(objectFields.map(TypedField::name).distinct().size == objectFields.size) {
            "Nested object field names must be unique"
        }
        require(enumValues.none(String::isBlank)) { "Typed field enum values must not be blank" }
        require(validationRules.none(String::isBlank)) {
            "Typed field validation rules must not be blank"
        }
        require(enumValues.isEmpty() || type == TypedFieldType.STRING) {
            "Enum values are supported only for string fields"
        }
        require(
            physicalDimension == null ||
                type == TypedFieldType.INTEGER ||
                type == TypedFieldType.NUMBER ||
                (type == TypedFieldType.ARRAY &&
                    (itemType == TypedFieldType.INTEGER || itemType == TypedFieldType.NUMBER))
        ) {
            "Physical dimensions are supported only for numeric fields and numeric array items"
        }
    }

    private companion object {
        const val MAXIMUM_ITEM_COUNT = 10_000
        val FIELD_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
    }
}

/** Council-supported implementation forms for one atomic generated tool. */
@Serializable
enum class ToolImplementationMode {
    SHELL,
    PYTHON,
    FORMULA_DSL,
    RULE_DSL,
    SIMULATION_DSL
}

/** Whether repeated executions are deterministic or require explicit stochastic seed authority. */
@Serializable
enum class ToolBehavior {
    DETERMINISTIC,
    STOCHASTIC
}

/** Closed FORMULA_DSL reference semantics that a Council may commit into an Atlas. */
@Serializable
enum class FormulaDslReferenceSemantics {
    /** Version one static typing and physical-dimension inference semantics. */
    DIMENSIONAL_ANALYSIS_V1
}

/**
 * Atlas-authored bindings from typed simulation fields to immutable run-record authority.
 *
 * @param resultSummaryOutputField Required string output containing the bounded result summary.
 * @param uncertaintyOutputField Required string output containing explicit uncertainty.
 * @param randomSeedInputField Required integer input for stochastic simulations and absent for
 * deterministic simulations.
 */
@Serializable
data class SimulationRecordBindings(
    val resultSummaryOutputField: String,
    val uncertaintyOutputField: String,
    val randomSeedInputField: String? = null
) {
    init {
        require(resultSummaryOutputField.matches(BINDING_FIELD_NAME)) {
            "Simulation result-summary binding must be a JSON-compatible field name"
        }
        require(uncertaintyOutputField.matches(BINDING_FIELD_NAME)) {
            "Simulation uncertainty binding must be a JSON-compatible field name"
        }
        require(randomSeedInputField == null || randomSeedInputField.matches(BINDING_FIELD_NAME)) {
            "Simulation random-seed binding must be a JSON-compatible field name"
        }
        require(resultSummaryOutputField != uncertaintyOutputField) {
            "Simulation summary and uncertainty require distinct output fields"
        }
    }

    private companion object {
        val BINDING_FIELD_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
    }
}

/**
 * Opportunity to replace repeated manual work with reusable Atlas-bound infrastructure.
 *
 * @param opportunityId Stable Council opportunity ID.
 * @param opportunityType Repeated work class.
 * @param repeatedQuestionPattern Work pattern the tool must automate.
 * @param requiredInputs Typed input schema.
 * @param requiredOutputs Typed output schema.
 * @param validationStrategy Observable promotion-validation strategy.
 * @param expectedReuseNodeIds Atlas nodes expected to consume the exact promoted version.
 * @param preferredImplementationMode Constrained implementation form.
 * @param behavior Deterministic or explicitly stochastic execution behavior.
 * @param requiredCapabilities Required sandbox capabilities.
 * @param promotionTestIds Exact precommitted tests required before promotion.
 * @param maximumRuntimeMillis Hard execution timeout.
 * @param maximumOutputBytes Hard output bound.
 * @param assumptions Declared operating assumptions.
 * @param knownInvalidDomains Inputs or domains for which the implementation is invalid.
 * @param simulationRecordBindings Typed simulation evidence bindings, only for simulation
 * opportunities.
 * @param formulaDslReferenceSemantics Council commitment to FORMULA_DSL evaluation semantics.
 * Null preserves legacy/direct opportunity compatibility before orchestration sealing checks.
 * @param formulaDslReferenceProgram Optional Council-authored executable reference formula. Null
 * preserves legacy/direct opportunity compatibility before orchestration sealing checks.
 */
@Serializable
data class ToolOpportunity(
    val opportunityId: String,
    val opportunityType: ToolOpportunityType,
    val repeatedQuestionPattern: String,
    val requiredInputs: List<TypedField>,
    val requiredOutputs: List<TypedField>,
    val validationStrategy: String,
    val expectedReuseNodeIds: Set<NodeId>,
    val preferredImplementationMode: ToolImplementationMode,
    val behavior: ToolBehavior,
    val requiredCapabilities: Set<String> = emptySet(),
    val promotionTestIds: Set<String>,
    val maximumRuntimeMillis: Long,
    val maximumOutputBytes: Long,
    val assumptions: List<String> = emptyList(),
    val knownInvalidDomains: List<String> = emptyList(),
    val simulationRecordBindings: SimulationRecordBindings? = null,
    val formulaDslReferenceSemantics: FormulaDslReferenceSemantics? = null,
    val formulaDslReferenceProgram: ToolDslProgram? = null
) {
    init {
        require(opportunityId.isNotBlank()) { "Tool opportunity ID must not be blank" }
        require(repeatedQuestionPattern.isNotBlank()) {
            "Tool opportunity repeated question pattern must not be blank"
        }
        require(requiredInputs.isNotEmpty()) { "Tool opportunity requires typed inputs" }
        require(requiredOutputs.isNotEmpty()) { "Tool opportunity requires typed outputs" }
        require(requiredInputs.map(TypedField::name).distinct().size == requiredInputs.size) {
            "Tool opportunity input field names must be unique"
        }
        require(requiredOutputs.map(TypedField::name).distinct().size == requiredOutputs.size) {
            "Tool opportunity output field names must be unique"
        }
        require(validationStrategy.isNotBlank()) {
            "Tool opportunity validation strategy must not be blank"
        }
        require(expectedReuseNodeIds.isNotEmpty()) {
            "Tool opportunity requires at least one expected downstream reuse node"
        }
        require(requiredCapabilities.none(String::isBlank)) {
            "Tool opportunity capabilities must not be blank"
        }
        require(promotionTestIds.isNotEmpty() && promotionTestIds.none(String::isBlank)) {
            "Tool opportunity requires exact promotion tests"
        }
        require(maximumRuntimeMillis > 0) { "Tool opportunity runtime bound must be positive" }
        require(maximumOutputBytes > 0) { "Tool opportunity output bound must be positive" }
        require(assumptions.none(String::isBlank)) { "Tool opportunity assumptions must not be blank" }
        require(knownInvalidDomains.none(String::isBlank)) {
            "Tool opportunity invalid domains must not be blank"
        }
        require(
            (formulaDslReferenceSemantics == null && formulaDslReferenceProgram == null) ||
                preferredImplementationMode == ToolImplementationMode.FORMULA_DSL
        ) {
            "Only FORMULA_DSL opportunities may commit FORMULA_DSL reference semantics or programs"
        }
        require(
            formulaDslReferenceProgram == null || formulaDslReferenceSemantics != null
        ) {
            "A FORMULA_DSL reference program requires committed reference semantics"
        }
        require(
            formulaDslReferenceProgram == null ||
                formulaDslReferenceProgram.kind == ToolDslKind.FORMULA
        ) {
            "A FORMULA_DSL reference program must use the formula program kind"
        }
        if (opportunityType == ToolOpportunityType.SIMULATION) {
            val bindings = requireNotNull(simulationRecordBindings) {
                "Simulation opportunities require typed run-record bindings"
            }
            require(assumptions.isNotEmpty()) {
                "Simulation opportunities require explicit assumptions"
            }
            require(
                requiredOutputs.any {
                    it.name == bindings.resultSummaryOutputField &&
                        it.required &&
                        it.type == TypedFieldType.STRING
                }
            ) { "Simulation result-summary binding must reference a required string output" }
            require(
                requiredOutputs.any {
                    it.name == bindings.uncertaintyOutputField &&
                        it.required &&
                        it.type == TypedFieldType.STRING
                }
            ) { "Simulation uncertainty binding must reference a required string output" }
            when (behavior) {
                ToolBehavior.STOCHASTIC -> require(
                    requiredInputs.any {
                        it.name == bindings.randomSeedInputField &&
                            it.required &&
                            it.type == TypedFieldType.INTEGER
                    }
                ) { "Stochastic simulations require a bound required integer seed input" }
                ToolBehavior.DETERMINISTIC -> require(bindings.randomSeedInputField == null) {
                    "Deterministic simulations must not bind a random seed input"
                }
            }
        } else {
            require(simulationRecordBindings == null) {
                "Only simulation opportunities may declare simulation run-record bindings"
            }
        }
    }
}

/**
 * One ordered progressive-disclosure stage.
 *
 * @param stageId Stable stage ID.
 * @param stageIndex Zero-based order.
 * @param title Worker-visible title.
 * @param nodeIds Nodes belonging to the stage.
 * @param mandatoryNodeIds Nodes required to pass the stage.
 * @param evidenceCoverageThreshold Required evidence coverage.
 * @param integrationReadinessThreshold Required integration readiness.
 * @param revealPayloadHash Hash committing the stage's hidden payload set.
 */
@Serializable
data class DisclosureStage(
    val stageId: String,
    val stageIndex: Int,
    val title: String,
    val nodeIds: Set<NodeId>,
    val mandatoryNodeIds: Set<NodeId>,
    val evidenceCoverageThreshold: Double,
    val integrationReadinessThreshold: Double,
    val revealPayloadHash: String
)
