package com.achulkov.evomolior.orchestration.council

import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.DisclosureStage
import com.achulkov.evomolior.core.atlas.LimitationNode
import com.achulkov.evomolior.core.atlas.NodeAtlas
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.atlas.ReceiverContract
import com.achulkov.evomolior.core.atlas.RevealPolicy
import com.achulkov.evomolior.core.atlas.YieldConstraintPolicy
import com.achulkov.evomolior.core.contract.DeliverableArtifactType
import com.achulkov.evomolior.core.contract.DeliverableSkeletonNode
import com.achulkov.evomolior.core.contract.DeliverableSkeletonNodeStatus
import com.achulkov.evomolior.core.contract.DeliverableSkeletonNodeType
import com.achulkov.evomolior.core.contract.DeliverableSkeletonTree
import com.achulkov.evomolior.core.contract.ResourceNodeSeed
import com.achulkov.evomolior.core.contract.ResourceYieldType
import com.achulkov.evomolior.core.contract.RunInputArtifact
import com.achulkov.evomolior.core.contract.StartingField
import com.achulkov.evomolior.core.council.CompiledSealedTestAuthorityV3
import com.achulkov.evomolior.core.council.CouncilContractHashing
import com.achulkov.evomolior.core.council.CouncilDeterministicIds
import com.achulkov.evomolior.core.council.CouncilRoleOutputBundle
import com.achulkov.evomolior.core.council.ExecutionEnvelope
import com.achulkov.evomolior.core.council.KgEntityTypeSpec
import com.achulkov.evomolior.core.council.KgRelationTypeSpec
import com.achulkov.evomolior.core.council.SealedTestSuiteV3Codec
import com.achulkov.evomolior.core.council.SealedTestSuiteV3CommitmentViewCodec
import com.achulkov.evomolior.core.model.Role
import kotlinx.serialization.Serializable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal data class CompiledArtifacts(
    val workerField: StartingField,
    val graderField: StartingField
)

internal object StartingFieldArtifacts {
    fun buildReceiverContract(
        rawObjective: String,
        envelope: ExecutionEnvelope,
        outputs: CouncilRoleOutputBundle
    ): ReceiverContract {
        val draft = outputs.taskAnatomist.receiverContract
        return ReceiverContract(
            rawObjective = rawObjective,
            receiverId = CouncilDeterministicIds.create("receiver", draft.receiverKey),
            receiverDescription = draft.receiverDescription,
            situation = draft.situation,
            decisionOrUse = draft.decisionOrUse,
            whyNeeded = draft.whyNeeded,
            successOutcomes = draft.successOutcomes,
            unacceptableOutcomes = draft.unacceptableOutcomes,
            constraints = draft.constraints,
            finalDeliverableContract = envelope.outputSpec,
            evidenceProfile = draft.evidenceProfile
        )
    }

    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
    }

    fun buildAtlas(
        runId: String,
        receiver: ReceiverContract,
        outputs: CouncilRoleOutputBundle
    ): NodeAtlas {
        val incentivePolicies = outputs.incentiveDesigner.nodePolicies.associateBy { it.nodeKey }
        val ids = outputs.taskAnatomist.nodes.associate { local ->
            local.key to NodeId(CouncilDeterministicIds.nodeId(runId, local.key))
        }
        val stageIds = outputs.synthesis.stages.associate { stage ->
            stage.stageKey to CouncilDeterministicIds.create("stage", runId, stage.stageKey)
        }
        val nodes = outputs.taskAnatomist.nodes.associate { local ->
            val id = ids.getValue(local.key)
            val incentivePolicy = incentivePolicies.getValue(local.key)
            id to LimitationNode(
                id = id,
                kind = local.kind,
                layer = local.layer,
                parentIds = local.parentKeys.mapTo(linkedSetOf(), ids::getValue),
                prerequisiteIds = local.prerequisiteKeys.mapTo(linkedSetOf(), ids::getValue),
                alternativeToIds = local.alternativeToKeys.mapTo(linkedSetOf(), ids::getValue),
                receiverWhyRef = local.receiverObligationKey,
                limitationStatement = local.limitationStatement,
                capabilityTarget = local.capabilityTarget,
                atomicQuestion = local.atomicQuestion,
                proofContract = local.proofContract,
                deliverableContract = local.deliverableContract,
                revealPolicy = RevealPolicy(
                    stageId = stageIds.getValue(local.stageKey),
                    mode = local.revealMode,
                    prerequisiteNodeIds = local.prerequisiteKeys.mapTo(linkedSetOf(), ids::getValue),
                    minimumRound = outputs.synthesis.stages.indexOfFirst { it.stageKey == local.stageKey }
                ),
                replicationPolicy = local.replicationPolicy,
                capturePolicy = incentivePolicy.capturePolicy,
                rewardSpec = incentivePolicy.rewardSpec,
                toolOpportunity = local.toolOpportunity
            ).withComputedPayloadHash()
        }
        val stages = outputs.synthesis.stages.mapIndexed { index, draft ->
            val stageId = stageIds.getValue(draft.stageKey)
            val nodeIds = draft.nodeKeys.mapTo(linkedSetOf(), ids::getValue)
            DisclosureStage(
                stageId = stageId,
                stageIndex = index,
                title = draft.title,
                nodeIds = nodeIds,
                mandatoryNodeIds = draft.mandatoryNodeKeys.mapTo(linkedSetOf(), ids::getValue),
                evidenceCoverageThreshold = draft.evidenceCoverageThreshold,
                integrationReadinessThreshold = draft.integrationReadinessThreshold,
                revealPayloadHash = AtlasHashing.hashDisclosurePayload(stageId, nodeIds, nodes)
            )
        }
        return NodeAtlas(
            atlasId = CouncilDeterministicIds.atlasId(runId),
            runId = runId,
            version = outputs.synthesis.atlasVersion,
            receiverContractHash = AtlasHashing.hashReceiverContract(receiver),
            rootNodeIds = outputs.taskAnatomist.rootNodeKeys.mapTo(linkedSetOf(), ids::getValue),
            nodes = nodes,
            stageDefinitions = stages,
            parentAtlasHash = outputs.synthesis.parentAtlasHash,
            amendmentIds = outputs.synthesis.declaredChange?.let { listOf(it.declarationId) }.orEmpty()
        ).withComputedHash()
    }

    fun build(
        runId: String,
        rawObjective: String,
        envelope: ExecutionEnvelope,
        outputs: CouncilRoleOutputBundle,
        atlas: NodeAtlas,
        sealedTests: CompiledSealedTestAuthorityV3,
        inputArtifacts: List<RunInputArtifact> = emptyList()
    ): CompiledArtifacts {
        sealedTests.requireValid()
        val kgSchema = canonicalEncode(
            TypedKgSchema.serializer(),
            TypedKgSchema(
                schemaVersion = outputs.kgArchitect.schemaVersion,
                rubricSchemaVersion = outputs.synthesis.rubricSchemaVersion,
                entityTypes = outputs.kgArchitect.entityTypes,
                relationTypes = outputs.kgArchitect.relationTypes,
                aliases = outputs.kgArchitect.aliases,
                claimLedgerSchemaJson = outputs.kgArchitect.claimLedgerSchemaJson,
                nodeMappings = outputs.kgArchitect.nodeMappings.mapKeys {
                    CouncilDeterministicIds.nodeId(runId, it.key)
                }
            )
        )
        val workerNodeGraph = buildNodeGraph(outputs, atlas, revealAll = false)
        val graderNodeGraph = buildNodeGraph(outputs, atlas, revealAll = true)
        val workerTree = buildDeliverableTree(runId, rawObjective, outputs, atlas, revealAll = false)
        val graderTree = buildDeliverableTree(runId, rawObjective, outputs, atlas, revealAll = true)
        val workerResourceNodes = buildResourceNodes(outputs, atlas, revealAll = false)
        val graderResourceNodes = buildResourceNodes(outputs, atlas, revealAll = true)
        val graderSealedTests = SealedTestSuiteV3Codec.encode(
            sealedTests.privateSuite,
            sealedTests.validationContext
        )
        val workerSealedTests = SealedTestSuiteV3CommitmentViewCodec.encode(
            sealedTests.workerCommitmentView
        )
        val workerStageContents = buildStageContents(outputs, atlas, revealAll = false)
        val graderStageContents = buildStageContents(outputs, atlas, revealAll = true)
        val workerGroupIds = outputs.ecologist.workerGroups.associate { group ->
            group.groupKey to CouncilDeterministicIds.groupId(runId, "worker", group.groupKey)
        }
        val graderGroupIds = outputs.ecologist.graderGroups.associate { group ->
            group.groupKey to CouncilDeterministicIds.groupId(runId, "grader", group.groupKey)
        }
        val workerPrompts = outputs.promptArchitect.rolePrompts
            .filterKeys { !it.isGraderRole }
            .mapKeys { it.key.name }
        val graderPrompts = outputs.promptArchitect.rolePrompts
            .filterKeys(Role::isGraderRole)
            .mapKeys { it.key.name }
        val workerAssignments = outputs.modelEcologist.assignments
            .filter { !it.role.isGraderRole }
            .associate { it.role.name to it.primaryModelId }
        val graderAssignments = outputs.modelEcologist.assignments
            .filter { it.role.isGraderRole }
            .associate { it.role.name to it.primaryModelId }
        val workerPools = outputs.modelEcologist.assignments
            .filter { !it.role.isGraderRole }
            .associate {
                it.role.name to (listOf(it.primaryModelId) + it.fallbackModelIds)
            }
        val graderPools = outputs.modelEcologist.assignments
            .filter { it.role.isGraderRole }
            .associate {
            it.role.name to (listOf(it.primaryModelId) + it.fallbackModelIds)
        }
        val incentive = outputs.incentiveDesigner
        val yieldConstraintPolicy = YieldConstraintPolicy(
            policyId = CouncilDeterministicIds.create("yield-policy", runId),
            policyHash = "",
            perGroupRoundCap =
                incentive.maximumRewardCreditsPerRound * incentive.maximumSharePerGroup,
            totalRoundCap = incentive.maximumRewardCreditsPerRound,
            maximumActiveYieldFraction = incentive.maximumSharePerGroup,
            repeatOwnershipDiminishingFactor = requireNotNull(
                incentive.repeatOwnershipDiminishingFactor
            ),
            mandatoryFrontierBonus = requireNotNull(incentive.mandatoryFrontierBonus),
            independentValidationCredit = requireNotNull(incentive.independentValidationCredit)
        ).withComputedHash()
        val common = StartingField(
            runId = runId,
            objective = rawObjective,
            globalBudgetCeiling = envelope.cumulativeCostCap
                ?: envelope.perGenerationCreditPolicy.maximumCredits * envelope.maxRounds *
                outputs.ecologist.workerGroups.size,
            generationCeiling = envelope.maxRounds,
            groupCount = outputs.ecologist.workerGroups.size,
            groupProfiles = outputs.ecologist.workerGroups.associate {
                workerGroupIds.getValue(it.groupKey) to it.profile
            },
            ringTopology = outputs.ecologist.topologyOrder.map(workerGroupIds::getValue),
            topologyKind = outputs.ecologist.topologyKind,
            checkinMilestones = outputs.ecologist.checkinRounds,
            leakIntervalGenerations = outputs.ecologist.leakIntervalRounds,
            dispatchLagGenerations = outputs.ecologist.dispatchLagRounds,
            graderGroupCount = outputs.ecologist.graderGroups.size,
            rolePrompts = workerPrompts,
            modelAssignment = workerAssignments,
            modelPools = workerPools,
            kgSchema = kgSchema,
            actionCosts = outputs.incentiveDesigner.actionCosts,
            scoreNormalization = outputs.incentiveDesigner.scoringDimensions.associate {
                it.id to it.maxScore
            },
            yieldConstraintPolicy = yieldConstraintPolicy,
            nodeGraph = workerNodeGraph,
            resourceNodes = workerResourceNodes,
            deliverableSkeletonTreeJson = json.encodeToString(workerTree),
            primordialKb = outputs.seedCurator.primordialKb,
            primordialKgFacts = outputs.seedCurator.primordialKgFacts,
            primordialTools = outputs.seedCurator.primordialTools,
            inputArtifacts = inputArtifacts,
            deathNoteSchema = outputs.promptArchitect.deathCommitSchemaJson,
            legacyProtocolTemplate = outputs.promptArchitect.legacyProtocolTemplateJson,
            sealedTestSuiteJson = workerSealedTests,
            sealedTestSuiteHash = CouncilContractHashing.sha256(workerSealedTests),
            norms = outputs.seedCurator.foundingNorms,
            stageContents = workerStageContents,
            consistencyReport = canonicalEncode(
                FieldNotes.serializer(),
                FieldNotes(
                    workerNotes = outputs.synthesis.workerFieldNotes,
                    graderNotes = emptyList(),
                    knownDeadEnds = outputs.seedCurator.knownDeadEnds
                )
            )
        )
        val graderGroups = outputs.ecologist.graderGroups
        val grader = common.copy(
            runId = CouncilDeterministicIds.create("grader-field", runId),
            globalBudgetCeiling = envelope.cumulativeCostCap
                ?: envelope.perGenerationCreditPolicy.maximumCredits * envelope.maxRounds *
                graderGroups.size,
            groupCount = graderGroups.size,
            groupProfiles = graderGroups.associate {
                graderGroupIds.getValue(it.groupKey) to it.profile
            },
            ringTopology = graderGroups.map { graderGroupIds.getValue(it.groupKey) },
            graderGroupCount = graderGroups.size,
            rolePrompts = graderPrompts,
            modelAssignment = graderAssignments,
            modelPools = graderPools,
            nodeGraph = graderNodeGraph,
            resourceNodes = graderResourceNodes,
            deliverableSkeletonTreeJson = json.encodeToString(graderTree),
            primordialKb = outputs.seedCurator.calibrationArtifacts.map { artifact ->
                com.achulkov.evomolior.core.contract.SeedKBDocument(
                    id = artifact.artifactKey,
                    content = artifact.content,
                    domain = "grader-calibration"
                )
            },
            primordialKgFacts = emptyList(),
            primordialTools = emptyList(),
            sealedTestSuiteJson = graderSealedTests,
            sealedTestSuiteHash = CouncilContractHashing.sha256(graderSealedTests),
            norms = listOf(
                outputs.seedCurator.foundingNorms,
                outputs.incentiveDesigner.graderRewardPolicy
            ).joinToString("\n"),
            stageContents = graderStageContents,
            consistencyReport = canonicalEncode(
                FieldNotes.serializer(),
                FieldNotes(
                    workerNotes = emptyList(),
                    graderNotes = outputs.synthesis.graderFieldNotes,
                    knownDeadEnds = emptyList()
                )
            )
        )
        return CompiledArtifacts(common, grader)
    }

    private fun buildNodeGraph(
        outputs: CouncilRoleOutputBundle,
        atlas: NodeAtlas,
        revealAll: Boolean
    ): String = canonicalEncode(
        CompiledNodeGraph.serializer(),
        CompiledNodeGraph(
            schemaVersion = NODE_GRAPH_SCHEMA_VERSION,
            rubricSchemaVersion = outputs.synthesis.rubricSchemaVersion,
            atlasId = atlas.atlasId,
            atlasHash = atlas.atlasHash,
            rootNodeIds = atlas.rootNodeIds.map { it.value }.sorted(),
            nodes = atlas.nodes.values.sortedBy { it.id.value }.map { node ->
                CompiledNodeEntry(
                    nodeId = node.id.value,
                    parentNodeIds = node.parentIds.map { it.value }.sorted(),
                    prerequisiteNodeIds = node.prerequisiteIds.map { it.value }.sorted(),
                    stageId = node.revealPolicy.stageId,
                    payloadHash = node.payloadHash,
                    description = if (revealAll || atlas.isInitiallyVisible(node.id)) {
                        node.capabilityTarget
                    } else {
                        SEALED_NODE_LABEL
                    }
                )
            }
        )
    )

    private fun buildStageContents(
        outputs: CouncilRoleOutputBundle,
        atlas: NodeAtlas,
        revealAll: Boolean
    ): List<String> = atlas.stageDefinitions.mapIndexed { index, stage ->
        if (!revealAll && index > 0) {
            canonicalEncode(
                GeneratedStageCommitment.serializer(),
                GeneratedStageCommitment(
                    schemaVersion = STAGE_CONTENT_SCHEMA_VERSION,
                    stageId = stage.stageId,
                    stageIndex = index,
                    title = SEALED_STAGE_LABEL,
                    nodePayloadHashes = stage.nodeIds.associate { id ->
                        id.value to atlas.nodes.getValue(id).payloadHash
                    },
                    revealPayloadHash = stage.revealPayloadHash
                )
            )
        } else canonicalEncode(
            GeneratedStageContent.serializer(),
            GeneratedStageContent(
                schemaVersion = STAGE_CONTENT_SCHEMA_VERSION,
                stageId = stage.stageId,
                stageIndex = index,
                title = stage.title,
                context = if (index == 0) outputs.seedCurator.stageZeroContext else emptyList(),
                nodes = stage.nodeIds.sortedBy { it.value }.map { id ->
                    val node = atlas.nodes.getValue(id)
                    StageNodeContent(
                        nodeId = id.value,
                        kind = node.kind.name,
                        receiverObligationRef = node.receiverWhyRef,
                        limitationStatement = node.limitationStatement,
                        capabilityTarget = node.capabilityTarget,
                        atomicQuestion = node.atomicQuestion,
                        payloadHash = node.payloadHash
                    )
                }
            )
        )
    }

    private fun buildDeliverableTree(
        runId: String,
        rawObjective: String,
        outputs: CouncilRoleOutputBundle,
        atlas: NodeAtlas,
        revealAll: Boolean
    ): DeliverableSkeletonTree {
        val selectedParentByNode = atlas.nodes.values.associate { node ->
            node.id.value to node.parentIds.minByOrNull { it.value }?.value
        }
        val childrenByParent = selectedParentByNode.entries
            .mapNotNull { (nodeId, parentId) -> parentId?.let { it to nodeId } }
            .groupBy({ it.first }, { it.second })
        val primaryRootId = atlas.rootNodeIds.minByOrNull { it.value }?.value
            ?: error("Compiled Atlas must contain a root node")
        val nodes = atlas.nodes.values.sortedBy { it.id.value }.map { node ->
            val isRoot = node.id in atlas.rootNodeIds
            DeliverableSkeletonNode(
                nodeId = node.id.value,
                parentId = selectedParentByNode[node.id.value],
                children = childrenByParent[node.id.value].orEmpty().sorted(),
                nodeType = if (isRoot) {
                    DeliverableSkeletonNodeType.ROOT
                } else {
                    node.kind.toDeliverableType()
                },
                title = when {
                    node.id.value == primaryRootId -> rawObjective
                    revealAll || atlas.isInitiallyVisible(node.id) -> node.capabilityTarget
                    else -> SEALED_NODE_LABEL
                },
                pathHint = if (revealAll || atlas.isInitiallyVisible(node.id)) {
                    node.deliverableContract.finalDeliverableRelationship.ifBlank { null }
                } else {
                    null
                },
                status = DeliverableSkeletonNodeStatus.STUB,
                artifactType = DeliverableArtifactType.OTHER,
                owningRole = DeliverableSkeletonTree.defaultOwningRoleForNodeType(node.kind.toDeliverableType()),
                graderComplexityLevel = outputs.taskAnatomist.nodes
                    .first { CouncilDeterministicIds.nodeId(runId, it.key) == node.id.value }
                    .estimatedEffort
            )
        }
        return DeliverableSkeletonTree(
            runId = runId,
            groupId = FIELD_TEMPLATE_GROUP_ID,
            rootNodeId = primaryRootId,
            nodes = nodes,
            createdAtMs = 0L,
            updatedAtMs = 0L
        )
    }

    private fun buildResourceNodes(
        outputs: CouncilRoleOutputBundle,
        atlas: NodeAtlas,
        revealAll: Boolean
    ): List<ResourceNodeSeed> {
        val dimensions = outputs.incentiveDesigner.scoringDimensions.map { it.id }.sorted()
        val rewardCap = outputs.incentiveDesigner.maximumRewardCreditsPerRound
        return atlas.nodes.values.sortedBy { it.id.value }.map { node ->
            ResourceNodeSeed(
                nodeId = CouncilDeterministicIds.create("resource", atlas.runId, node.id.value),
                name = if (revealAll || atlas.isInitiallyVisible(node.id)) {
                    node.capabilityTarget
                } else {
                    SEALED_NODE_LABEL
                },
                deliverableNodeRef = node.id.value,
                scoringDimensionIds = dimensions,
                resourceType = node.kind.toResourceType(),
                qualityThreshold = node.capturePolicy.minimumGrade,
                yieldMultiplier = 1.0 + if (rewardCap == 0.0) {
                    0.0
                } else {
                    ((node.rewardSpec.credits + node.rewardSpec.firstCompletionBonus) / rewardCap)
                        .coerceIn(0.0, 1.0)
                }
            )
        }
    }

    private fun com.achulkov.evomolior.core.atlas.NodeKind.toDeliverableType() = when (this) {
        com.achulkov.evomolior.core.atlas.NodeKind.TOOL -> DeliverableSkeletonNodeType.TOOL
        com.achulkov.evomolior.core.atlas.NodeKind.EXPERIMENT,
        com.achulkov.evomolior.core.atlas.NodeKind.VALIDATION -> DeliverableSkeletonNodeType.TEST_SUITE
        com.achulkov.evomolior.core.atlas.NodeKind.DELIVERY -> DeliverableSkeletonNodeType.ARTIFACT_BUNDLE
        else -> DeliverableSkeletonNodeType.DOCUMENT_SECTION
    }

    private fun com.achulkov.evomolior.core.atlas.NodeKind.toResourceType() = when (this) {
        com.achulkov.evomolior.core.atlas.NodeKind.KNOWLEDGE,
        com.achulkov.evomolior.core.atlas.NodeKind.EVIDENCE,
        com.achulkov.evomolior.core.atlas.NodeKind.HYPOTHESIS -> ResourceYieldType.WEB_CACHE
        com.achulkov.evomolior.core.atlas.NodeKind.MODEL,
        com.achulkov.evomolior.core.atlas.NodeKind.TOOL -> ResourceYieldType.TOOL_CACHE
        com.achulkov.evomolior.core.atlas.NodeKind.SIMULATION,
        com.achulkov.evomolior.core.atlas.NodeKind.EXPERIMENT -> ResourceYieldType.GPU_BURST
        com.achulkov.evomolior.core.atlas.NodeKind.VALIDATION -> ResourceYieldType.STAGING_SHORTCUT
        com.achulkov.evomolior.core.atlas.NodeKind.INTEGRATION,
        com.achulkov.evomolior.core.atlas.NodeKind.DECISION,
        com.achulkov.evomolior.core.atlas.NodeKind.DELIVERY -> ResourceYieldType.GOSSIP_RELAY
    }

    private fun <T> canonicalEncode(serializer: KSerializer<T>, value: T): String =
        CouncilContractHashing.canonicalJson(json.encodeToJsonElement(serializer, value))

    private fun NodeAtlas.isInitiallyVisible(nodeId: NodeId): Boolean =
        stageDefinitions.firstOrNull()?.nodeIds?.contains(nodeId) == true

    private const val NODE_GRAPH_SCHEMA_VERSION = "node-graph-v1"
    private const val STAGE_CONTENT_SCHEMA_VERSION = "stage-content-v1"
    private const val FIELD_TEMPLATE_GROUP_ID = "council-template"
    private const val SEALED_NODE_LABEL = "Sealed node"
    private const val SEALED_STAGE_LABEL = "Sealed stage"
}

@Serializable
private data class TypedKgSchema(
    val schemaVersion: String,
    val rubricSchemaVersion: String,
    val entityTypes: List<KgEntityTypeSpec>,
    val relationTypes: List<KgRelationTypeSpec>,
    val aliases: Map<String, String>,
    val claimLedgerSchemaJson: String,
    val nodeMappings: Map<String, Set<String>>
)

@Serializable
private data class CompiledNodeGraph(
    val schemaVersion: String,
    val rubricSchemaVersion: String,
    val atlasId: String,
    val atlasHash: String,
    val rootNodeIds: List<String>,
    val nodes: List<CompiledNodeEntry>
)

@Serializable
private data class CompiledNodeEntry(
    val nodeId: String,
    val parentNodeIds: List<String>,
    val prerequisiteNodeIds: List<String>,
    val stageId: String,
    val payloadHash: String,
    val description: String
)

@Serializable
private data class GeneratedStageContent(
    val schemaVersion: String,
    val stageId: String,
    val stageIndex: Int,
    val title: String,
    val context: List<String>,
    val nodes: List<StageNodeContent>
)

@Serializable
private data class GeneratedStageCommitment(
    val schemaVersion: String,
    val stageId: String,
    val stageIndex: Int,
    val title: String,
    val nodePayloadHashes: Map<String, String>,
    val revealPayloadHash: String
)

@Serializable
private data class StageNodeContent(
    val nodeId: String,
    val kind: String,
    val receiverObligationRef: String,
    val limitationStatement: String,
    val capabilityTarget: String,
    val atomicQuestion: String,
    val payloadHash: String
)

@Serializable
private data class FieldNotes(
    val workerNotes: List<String>,
    val graderNotes: List<String>,
    val knownDeadEnds: List<String>
)
