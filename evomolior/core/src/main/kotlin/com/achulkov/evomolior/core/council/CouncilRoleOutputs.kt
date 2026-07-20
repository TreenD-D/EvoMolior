package com.achulkov.evomolior.core.council

import com.achulkov.evomolior.core.atlas.ArtifactType
import com.achulkov.evomolior.core.atlas.CapturePolicy
import com.achulkov.evomolior.core.atlas.EvidenceProfile
import com.achulkov.evomolior.core.atlas.NodeDeliverableContract
import com.achulkov.evomolior.core.atlas.NodeKind
import com.achulkov.evomolior.core.atlas.ProofContract
import com.achulkov.evomolior.core.atlas.ReplicationPolicy
import com.achulkov.evomolior.core.atlas.RevealMode
import com.achulkov.evomolior.core.atlas.RewardSpec
import com.achulkov.evomolior.core.atlas.ToolOpportunity
import com.achulkov.evomolior.core.contract.GroupTopologyKind
import com.achulkov.evomolior.core.contract.SeedKBDocument
import com.achulkov.evomolior.core.contract.SeedKGFact
import com.achulkov.evomolior.core.contract.SeedTool
import com.achulkov.evomolior.core.model.GroupProfileType
import com.achulkov.evomolior.core.model.Role
import com.achulkov.evomolior.core.model.ScoringDimension
import com.achulkov.evomolior.core.serialization.LocalNodeKeyStringSetMapEntriesSerializer
import com.achulkov.evomolior.core.serialization.NullableToolOpportunityStructuredOutputSerializer
import com.achulkov.evomolior.core.serialization.RoleStringMapEntriesSerializer
import com.achulkov.evomolior.core.serialization.StringDoubleMapEntriesSerializer
import com.achulkov.evomolior.core.serialization.StringStringMapEntriesSerializer
import kotlinx.serialization.Serializable

/**
 * Semantic node key emitted before deterministic runtime ID allocation.
 *
 * @param value Role-local semantic identity.
 */
@JvmInline
@Serializable
value class LocalNodeKey(val value: String)

/**
 * Draft receiver interpretation which may not alter the raw objective.
 *
 * @param receiverKey Semantic receiver key.
 * @param receiverDescription Intended receiver.
 * @param situation Receiver situation.
 * @param decisionOrUse Decision enabled by the result.
 * @param whyNeeded Receiver rationale.
 * @param successOutcomes Required outcomes.
 * @param unacceptableOutcomes Forbidden outcomes.
 * @param constraints Receiver constraints.
 * @param evidenceProfile Evidence requirements.
 */
@Serializable
data class ReceiverContractDraft(
    val receiverKey: String,
    val receiverDescription: String,
    val situation: String,
    val decisionOrUse: String,
    val whyNeeded: String,
    val successOutcomes: List<String>,
    val unacceptableOutcomes: List<String>,
    val constraints: List<String>,
    val evidenceProfile: EvidenceProfile
)

/**
 * Council-local limitation node using semantic references only.
 *
 * @param key Semantic local key.
 * @param kind Node kind.
 * @param layer Decomposition depth.
 * @param parentKeys Parent node keys.
 * @param prerequisiteKeys Prerequisite node keys.
 * @param alternativeToKeys Alternative node keys.
 * @param receiverObligationKey Receiver obligation key.
 * @param limitationStatement Limitation removed.
 * @param capabilityTarget Capability produced.
 * @param atomicQuestion Bounded question.
 * @param estimatedEffort Estimated effort units.
 * @param proofContract Proof requirements.
 * @param deliverableContract Artifact requirements.
 * @param stageKey Disclosure stage key.
 * @param revealMode Reveal trigger.
 * @param replicationPolicy Replication policy.
 * @param capturePolicy Capture policy.
 * @param rewardSpec Reward policy.
 * @param toolOpportunity Optional tool opportunity.
 */
@Serializable
data class LocalLimitationNode(
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
    @Serializable(with = NullableToolOpportunityStructuredOutputSerializer::class)
    val toolOpportunity: ToolOpportunity? = null
)

/**
 * Atomicity assessment for one Council-local node.
 *
 * @param nodeKey Assessed node.
 * @param isAtomic Whether it fits one bounded assignment.
 * @param estimatedEffort Estimated effort units.
 * @param rationale Assessment rationale.
 */
@Serializable
data class NodeAtomicityAssessment(
    val nodeKey: LocalNodeKey,
    val isAtomic: Boolean,
    val estimatedEffort: Int,
    val rationale: String
)

/**
 * One explicit interpretation branch for unresolved objective ambiguity.
 *
 * @param branchKey Semantic branch identity.
 * @param interpretation Candidate interpretation.
 * @param affectedNodeKeys Nodes governed by the branch.
 */
@Serializable
data class AmbiguityBranch(
    val branchKey: String,
    val interpretation: String,
    val affectedNodeKeys: Set<LocalNodeKey>
)

/**
 * Typed output of [Role.CouncilTaskAnatomist].
 *
 * @param receiverContract Draft receiver interpretation.
 * @param receiverObligations Stable obligations keyed semantically.
 * @param nodes Reverse limitation DAG nodes.
 * @param rootNodeKeys Root semantic nodes.
 * @param atomicityReport Per-node atomicity assessments.
 * @param ambiguityBranches Explicit unresolved interpretations.
 */
@Serializable
data class TaskAnatomistOutput(
    val receiverContract: ReceiverContractDraft,
    @Serializable(with = StringStringMapEntriesSerializer::class)
    val receiverObligations: Map<String, String>,
    val nodes: List<LocalLimitationNode>,
    val rootNodeKeys: Set<LocalNodeKey>,
    val atomicityReport: List<NodeAtomicityAssessment>,
    val ambiguityBranches: List<AmbiguityBranch> = emptyList()
)

/**
 * Node-specific incentive policy emitted by the Incentive Designer.
 *
 * @param nodeKey Governed semantic node.
 * @param capturePolicy Capture conditions.
 * @param rewardSpec Bounded reward.
 */
@Serializable
data class NodeIncentivePolicy(
    val nodeKey: LocalNodeKey,
    val capturePolicy: CapturePolicy,
    val rewardSpec: RewardSpec
)

/**
 * Typed output of [Role.CouncilIncentiveDesigner].
 *
 * @param nodePolicies Capture and reward policies.
 * @param actionCosts Runtime action costs.
 * @param scoringDimensions Typed scoring dimensions.
 * @param maximumRewardCreditsPerRound Aggregate round reward cap.
 * @param maximumSharePerGroup Anti-concentration ownership limit.
 * @param repeatOwnershipDiminishingFactor Authored multiplier for repeated rewarded ownership.
 * @param mandatoryFrontierBonus Authored proportional bonus for mandatory frontier capture.
 * @param independentValidationCredit Authored credit for independent reproduction.
 * @param graderRewardPolicy Grader incentive policy.
 */
@Serializable
data class IncentiveDesignerOutput(
    val nodePolicies: List<NodeIncentivePolicy>,
    @Serializable(with = StringDoubleMapEntriesSerializer::class)
    val actionCosts: Map<String, Double>,
    val scoringDimensions: List<ScoringDimension>,
    val maximumRewardCreditsPerRound: Double,
    val maximumSharePerGroup: Double,
    val repeatOwnershipDiminishingFactor: Double? = null,
    val mandatoryFrontierBonus: Double? = null,
    val independentValidationCredit: Double? = null,
    val graderRewardPolicy: String
)

/**
 * Executable group design using only envelope-supported values.
 *
 * @param groupKey Semantic group identity.
 * @param profile Runtime-supported profile.
 * @param modelFamilyPreference Optional preferred model family.
 */
@Serializable
data class GroupFieldDesign(
    val groupKey: String,
    val profile: GroupProfileType,
    val modelFamilyPreference: String? = null
)

/**
 * Typed output of [Role.CouncilEcologist].
 *
 * @param workerGroups Worker field groups.
 * @param graderGroups Grader field groups.
 * @param topologyKind Selected supported topology.
 * @param topologyOrder Deterministic group ordering.
 * @param checkinRounds Evaluation rounds.
 * @param leakIntervalRounds Leak cadence.
 * @param dispatchLagRounds Dispatch delay.
 */
@Serializable
data class EcologistOutput(
    val workerGroups: List<GroupFieldDesign>,
    val graderGroups: List<GroupFieldDesign>,
    val topologyKind: GroupTopologyKind,
    val topologyOrder: List<String>,
    val checkinRounds: List<Int>,
    val leakIntervalRounds: Int,
    val dispatchLagRounds: Int
)

/**
 * Worker-visible information boundary for one role.
 *
 * @param role Governed runtime role.
 * @param visibleFields Explicitly visible fields.
 * @param forbiddenFields Explicitly forbidden fields.
 */
@Serializable
data class RoleInformationBoundary(
    val role: Role,
    val visibleFields: Set<String>,
    val forbiddenFields: Set<String>
)

/**
 * Typed output of [Role.CouncilPromptArchitect].
 *
 * @param rolePrompts Runtime prompts by role.
 * @param informationBoundaries Worker disclosure boundaries.
 * @param outlierNoveltyMandate Explicit novelty mandate.
 * @param deathCommitSchemaJson Exact death-commit schema.
 * @param legacyProtocolTemplateJson Exact legacy template.
 */
@Serializable
data class PromptArchitectOutput(
    @Serializable(with = RoleStringMapEntriesSerializer::class)
    val rolePrompts: Map<Role, String>,
    val informationBoundaries: List<RoleInformationBoundary>,
    val outlierNoveltyMandate: String,
    val deathCommitSchemaJson: String,
    val legacyProtocolTemplateJson: String
)

/**
 * Typed KG entity definition.
 *
 * @param id Stable entity type ID.
 * @param requiredProperties Mandatory property IDs.
 */
@Serializable
data class KgEntityTypeSpec(val id: String, val requiredProperties: Set<String> = emptySet())

/**
 * Typed KG relation definition.
 *
 * @param id Stable relation ID.
 * @param sourceEntityTypeIds Permitted source entity types.
 * @param targetEntityTypeIds Permitted target entity types.
 */
@Serializable
data class KgRelationTypeSpec(
    val id: String,
    val sourceEntityTypeIds: Set<String>,
    val targetEntityTypeIds: Set<String>
)

/**
 * Typed output of [Role.CouncilKGArchitect].
 *
 * @param schemaVersion Typed KG schema version.
 * @param entityTypes Entity definitions.
 * @param relationTypes Relation definitions.
 * @param aliases Alias-to-canonical relation mapping.
 * @param claimLedgerSchemaJson Claim-ledger schema.
 * @param nodeMappings Semantic node-to-KG type mapping.
 */
@Serializable
data class KgArchitectOutput(
    val schemaVersion: String,
    val entityTypes: List<KgEntityTypeSpec>,
    val relationTypes: List<KgRelationTypeSpec>,
    @Serializable(with = StringStringMapEntriesSerializer::class)
    val aliases: Map<String, String>,
    val claimLedgerSchemaJson: String,
    @Serializable(with = LocalNodeKeyStringSetMapEntriesSerializer::class)
    val nodeMappings: Map<LocalNodeKey, Set<String>>
)

/**
 * Capability-based model assignment for one runtime role.
 *
 * @param role Assigned runtime role.
 * @param primaryModelId Primary envelope model.
 * @param fallbackModelIds Ordered envelope-supported fallbacks.
 * @param requiredCapabilities Capabilities required by the assignment.
 */
@Serializable
data class RoleModelAssignment(
    val role: Role,
    val primaryModelId: String,
    val fallbackModelIds: List<String> = emptyList(),
    val requiredCapabilities: Set<String> = emptySet()
)

/**
 * Typed output of [Role.CouncilModelEcologist].
 *
 * @param assignments Capability-based role assignments.
 * @param diversityRationale Cognitive diversity rationale.
 * @param degradedModePolicy Explicit fallback behavior.
 */
@Serializable
data class ModelEcologistOutput(
    val assignments: List<RoleModelAssignment>,
    val diversityRationale: String,
    val degradedModePolicy: String
)

/**
 * Seed calibration artifact committed before worker execution.
 *
 * @param artifactKey Semantic artifact key.
 * @param mediaType Artifact media type.
 * @param content Calibration content.
 * @param sha256 Content commitment.
 */
@Serializable
data class CalibrationArtifact(
    val artifactKey: String,
    val mediaType: String,
    val content: String,
    val sha256: String
)

/**
 * Typed output of [Role.CouncilSeedCurator].
 *
 * @param primordialKb Initial KB documents.
 * @param primordialKgFacts Initial KG facts.
 * @param primordialTools Initial tools.
 * @param calibrationArtifacts Grader calibration material.
 * @param knownDeadEnds Known unproductive directions.
 * @param stageZeroContext Initially disclosed context.
 * @param foundingNorms Worker coordination norms.
 */
@Serializable
data class SeedCuratorOutput(
    val primordialKb: List<SeedKBDocument>,
    val primordialKgFacts: List<SeedKGFact>,
    val primordialTools: List<SeedTool>,
    val calibrationArtifacts: List<CalibrationArtifact>,
    val knownDeadEnds: List<String>,
    val stageZeroContext: List<String>,
    val foundingNorms: String
)

/**
 * Semantic disclosure-stage draft emitted before deterministic ID allocation.
 *
 * @param stageKey Semantic stage identity.
 * @param title Worker-visible title.
 * @param nodeKeys Included nodes.
 * @param mandatoryNodeKeys Nodes required for stage completion.
 * @param evidenceCoverageThreshold Required evidence coverage.
 * @param integrationReadinessThreshold Required integration readiness.
 */
@Serializable
data class DisclosureStageDraft(
    val stageKey: String,
    val title: String,
    val nodeKeys: Set<LocalNodeKey>,
    val mandatoryNodeKeys: Set<LocalNodeKey>,
    val evidenceCoverageThreshold: Double,
    val integrationReadinessThreshold: Double
)

/**
 * Sealed-test draft tied to a semantic local node.
 *
 * @param testKey Semantic test identity.
 * @param nodeKey Tested node.
 * @param acceptedArtifactTypes Accepted artifact kinds.
 * @param testDefinitionJson Exact test definition.
 * @param commitmentHash Test-definition commitment.
 */
@Serializable
data class SealedTestDraft(
    val testKey: String,
    val nodeKey: LocalNodeKey,
    val acceptedArtifactTypes: Set<ArtifactType>,
    val testDefinitionJson: String,
    val commitmentHash: String
)

/**
 * Typed output of [Role.CouncilSynthesis].
 *
 * @param atlasVersion Requested Atlas version.
 * @param parentAtlasHash Required parent commitment for revisions.
 * @param stages Disclosure schedule.
 * @param sealedTests Precommitted tests.
 * @param workerFieldNotes Worker field synthesis notes.
 * @param graderFieldNotes Grader field synthesis notes.
 * @param rubricSchemaVersion Shared rubric schema version.
 * @param declaredChange Explicit material change declaration.
 */
@Serializable
data class SynthesisOutput(
    val atlasVersion: Int = 1,
    val parentAtlasHash: String? = null,
    val stages: List<DisclosureStageDraft>,
    val sealedTests: List<SealedTestDraft>,
    val workerFieldNotes: List<String> = emptyList(),
    val graderFieldNotes: List<String> = emptyList(),
    val rubricSchemaVersion: String,
    val declaredChange: FieldChangeDeclaration? = null
)

/**
 * Complete set of exactly eight typed Council role outputs.
 *
 * @param taskAnatomist Task decomposition output.
 * @param incentiveDesigner Incentive output.
 * @param ecologist Group ecology output.
 * @param promptArchitect Prompt and boundary output.
 * @param kgArchitect KG schema output.
 * @param modelEcologist Model assignment output.
 * @param seedCurator Primordial seed output.
 * @param synthesis Synthesis output.
 */
@Serializable
data class CouncilRoleOutputBundle(
    val taskAnatomist: TaskAnatomistOutput,
    val incentiveDesigner: IncentiveDesignerOutput,
    val ecologist: EcologistOutput,
    val promptArchitect: PromptArchitectOutput,
    val kgArchitect: KgArchitectOutput,
    val modelEcologist: ModelEcologistOutput,
    val seedCurator: SeedCuratorOutput,
    val synthesis: SynthesisOutput
)
