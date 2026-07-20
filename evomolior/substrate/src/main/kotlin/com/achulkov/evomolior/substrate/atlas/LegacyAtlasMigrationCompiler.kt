@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.substrate.atlas

import com.achulkov.evomolior.core.atlas.ArtifactType
import com.achulkov.evomolior.core.atlas.AtlasAgentId
import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.CaptureMode
import com.achulkov.evomolior.core.atlas.CapturePolicy
import com.achulkov.evomolior.core.atlas.CaptureState
import com.achulkov.evomolior.core.atlas.DisclosureStage
import com.achulkov.evomolior.core.atlas.GradeVerdict
import com.achulkov.evomolior.core.atlas.LeaseState
import com.achulkov.evomolior.core.atlas.LimitationNode
import com.achulkov.evomolior.core.atlas.NodeAtlas
import com.achulkov.evomolior.core.atlas.NodeDeliverableContract
import com.achulkov.evomolior.core.atlas.NodeExecutionState
import com.achulkov.evomolior.core.atlas.NodeGrade
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.atlas.NodeKind
import com.achulkov.evomolior.core.atlas.NodeLease
import com.achulkov.evomolior.core.atlas.ProofContract
import com.achulkov.evomolior.core.atlas.ProofMethod
import com.achulkov.evomolior.core.atlas.ReplicationPolicy
import com.achulkov.evomolior.core.atlas.RevealMode
import com.achulkov.evomolior.core.atlas.RevealPolicy
import com.achulkov.evomolior.core.atlas.RewardClass
import com.achulkov.evomolior.core.atlas.RewardSpec
import com.achulkov.evomolior.core.contract.DeliverableArtifactType
import com.achulkov.evomolior.core.contract.DeliverableSkeletonNode
import com.achulkov.evomolior.core.contract.DeliverableSkeletonNodeStatus
import com.achulkov.evomolior.core.contract.DeliverableSkeletonNodeType
import kotlinx.datetime.Instant

/**
 * Compiles existing node systems into one sealed Atlas or returns typed migration errors.
 *
 * The compiler never invents a replacement for an explicit but unresolved reference. Legacy
 * nodes with no cross-reference receive a same-ID Atlas node, making the mapping auditable while
 * retaining all original projection data for round-trip compatibility.
 */
class LegacyAtlasMigrationCompiler {

    /**
     * Compiles [snapshot] into an initial sealed Atlas and its execution projections.
     *
     * @param snapshot Complete legacy state.
     * @param authoritativeAtlas Optional precompiled Atlas that must remain the exact authority.
     * @return Successful prepared commit or every deterministic migration error.
     */
    fun compile(
        snapshot: LegacyAtlasSnapshot,
        authoritativeAtlas: NodeAtlas? = null
    ): AtlasMigrationResult {
        if (authoritativeAtlas != null) {
            return compileAgainstAuthoritativeAtlas(snapshot, authoritativeAtlas)
        }
        val errors = validateRunIdentity(snapshot).toMutableList()
        val deliverableDefinitions = collectDeliverableDefinitions(snapshot, errors)
        val nodesById = deliverableDefinitions.mapValues { (_, definition) ->
            NodeDraft.fromDeliverable(definition.node)
        }.toMutableMap()

        val mappings = mutableListOf<LegacyNodeMapping>()
        snapshot.deliverableTrees.forEach { tree ->
            tree.nodes.forEach { node ->
                mappings += LegacyNodeMapping(
                    source = LegacyNodeSource.DELIVERABLE,
                    sourceNodeId = node.nodeId,
                    scopeId = tree.groupId,
                    atlasNodeId = NodeId(node.nodeId)
                )
            }
        }

        snapshot.wip.forEach { projection ->
            projection.tasks.forEach { task ->
                if (task.status !in VALID_WIP_STATUSES) {
                    errors += migrationError(
                        AtlasMigrationErrorCode.INVALID_WIP_STATUS,
                        LegacyNodeSource.WIP,
                        task.taskId,
                        projection.groupId,
                        "Unsupported WIP status '${task.status}'"
                    )
                    return@forEach
                }
                val atlasNodeId = resolveWipNode(task, nodesById, projection.groupId, errors)
                    ?: return@forEach
                mappings += LegacyNodeMapping(
                    source = LegacyNodeSource.WIP,
                    sourceNodeId = task.taskId,
                    scopeId = projection.groupId,
                    atlasNodeId = atlasNodeId
                )
            }
        }

        val resourcesByAtlasNode = mutableMapOf<NodeId, MutableList<ResourceProjectionNode>>()
        snapshot.resources.nodes.forEach { resource ->
            val atlasNodeId = resolveResourceNode(resource, nodesById, errors) ?: return@forEach
            mappings += LegacyNodeMapping(
                source = LegacyNodeSource.RESOURCE,
                sourceNodeId = resource.resourceNodeId,
                scopeId = snapshot.runId,
                atlasNodeId = atlasNodeId
            )
            resourcesByAtlasNode.getOrPut(atlasNodeId) { mutableListOf() } += resource
        }

        if (errors.isNotEmpty()) return AtlasMigrationResult.Failure(errors)

        val normalizedStages = normalizeStages(
            snapshot = snapshot,
            nodeIds = nodesById.keys,
            errors = errors
        )
        if (errors.isNotEmpty()) return AtlasMigrationResult.Failure(errors)

        val stageByNode = normalizedStages
            .flatMap { stage -> stage.nodeIds.map { nodeId -> nodeId to stage } }
            .toMap()
        val sealedNodes = nodesById.entries.associate { (rawNodeId, draft) ->
            val nodeId = NodeId(rawNodeId)
            val resources = resourcesByAtlasNode[nodeId].orEmpty()
            nodeId to draft.toNode(
                stage = stageByNode.getValue(nodeId),
                resources = resources
            ).withComputedPayloadHash()
        }
        val committedStages = normalizedStages.map { stage ->
            stage.copy(
                revealPayloadHash = AtlasHashing.hashDisclosurePayload(
                    stageId = stage.stageId,
                    nodeIds = stage.nodeIds,
                    nodes = sealedNodes
                )
            )
        }
        val rootNodeIds = sealedNodes.values
            .filter { it.parentIds.isEmpty() }
            .mapTo(linkedSetOf()) { it.id }
        val atlas = NodeAtlas(
            atlasId = snapshot.atlasId,
            runId = snapshot.runId,
            version = 1,
            receiverContractHash = AtlasHashing.hashReceiverContract(snapshot.receiverContract),
            rootNodeIds = rootNodeIds,
            nodes = sealedNodes,
            stageDefinitions = committedStages
        ).withComputedHash()
        val validation = atlas.validate()
        if (!validation.isValid) {
            return AtlasMigrationResult.Failure(
                validation.errors.map { failure ->
                    migrationError(
                        AtlasMigrationErrorCode.INVALID_ATLAS,
                        null,
                        failure.nodeId?.value,
                        null,
                        "${failure.code}: ${failure.message}"
                    )
                }
            )
        }

        val projections = AtlasProjectionSet(
            wip = snapshot.wip,
            deliverables = snapshot.deliverableTrees,
            resources = snapshot.resources,
            disclosure = snapshot.disclosure.copy(stages = committedStages),
            mappings = mappings.sortedWith(
                compareBy<LegacyNodeMapping>({ it.source.name }, { it.scopeId }, { it.sourceNodeId })
            )
        )
        return AtlasMigrationResult.Success(
            PreparedAtlasMigration(
                receiverContract = snapshot.receiverContract,
                atlas = atlas,
                executionStates = buildExecutionStates(
                    snapshot = snapshot,
                    atlas = atlas,
                    mappings = mappings
                ),
                projections = projections
            )
        )
    }

    private fun compileAgainstAuthoritativeAtlas(
        snapshot: LegacyAtlasSnapshot,
        atlas: NodeAtlas
    ): AtlasMigrationResult {
        val errors = validateRunIdentity(snapshot).toMutableList()
        validateAuthoritativeAtlas(snapshot, atlas, errors)

        val mappings = mutableListOf<LegacyNodeMapping>()
        val deliverableNodeIds = snapshot.deliverableTrees
            .flatMap { tree -> tree.nodes.map { NodeId(it.nodeId) } }
            .toSet()

        snapshot.deliverableTrees.forEach { tree ->
            val treeRootId = NodeId(tree.rootNodeId)
            when {
                treeRootId !in atlas.nodes -> errors += migrationError(
                    AtlasMigrationErrorCode.MISSING_NODE_MAPPING,
                    LegacyNodeSource.DELIVERABLE,
                    tree.rootNodeId,
                    tree.groupId,
                    "Deliverable tree root does not exist in the authoritative Atlas"
                )
                treeRootId !in atlas.rootNodeIds -> errors += migrationError(
                    AtlasMigrationErrorCode.INVALID_DEPENDENCY,
                    LegacyNodeSource.DELIVERABLE,
                    tree.rootNodeId,
                    tree.groupId,
                    "Deliverable tree root is not an authoritative Atlas root"
                )
            }
            tree.nodes.forEach { node ->
                val nodeId = NodeId(node.nodeId)
                val authoritativeNode = atlas.nodes[nodeId]
                if (authoritativeNode == null) {
                    errors += migrationError(
                        AtlasMigrationErrorCode.MISSING_NODE_MAPPING,
                        LegacyNodeSource.DELIVERABLE,
                        node.nodeId,
                        tree.groupId,
                        "Deliverable node does not exist in the authoritative Atlas"
                    )
                    return@forEach
                }
                // The legacy deliverable skeleton is a tree projection of the Atlas DAG. Council
                // compilation deterministically selects the lexicographically first parent.
                val selectedParent = authoritativeNode.parentIds.minByOrNull { it.value }
                if (node.parentId != selectedParent?.value) {
                    errors += migrationError(
                        AtlasMigrationErrorCode.INVALID_DEPENDENCY,
                        LegacyNodeSource.DELIVERABLE,
                        node.nodeId,
                        tree.groupId,
                        "Deliverable parent relationship disagrees with the authoritative Atlas"
                    )
                }
                val authoritativeChildren = atlas.nodes.values
                    .filter { child -> child.parentIds.minByOrNull { it.value } == nodeId }
                    .mapTo(linkedSetOf()) { it.id.value }
                if (node.children.toSet() != authoritativeChildren.intersect(deliverableNodeIds.mapTo(mutableSetOf()) { it.value })) {
                    errors += migrationError(
                        AtlasMigrationErrorCode.INVALID_DEPENDENCY,
                        LegacyNodeSource.DELIVERABLE,
                        node.nodeId,
                        tree.groupId,
                        "Deliverable child relationships disagree with the authoritative Atlas"
                    )
                }
                mappings += LegacyNodeMapping(
                    source = LegacyNodeSource.DELIVERABLE,
                    sourceNodeId = node.nodeId,
                    scopeId = tree.groupId,
                    atlasNodeId = nodeId
                )
            }
        }

        snapshot.wip.forEach { projection ->
            projection.tasks.forEach { task ->
                if (task.status !in VALID_WIP_STATUSES) {
                    errors += migrationError(
                        AtlasMigrationErrorCode.INVALID_WIP_STATUS,
                        LegacyNodeSource.WIP,
                        task.taskId,
                        projection.groupId,
                        "Unsupported WIP status '${task.status}'"
                    )
                }
                validateAuthoritativeMapping(
                    atlas = atlas,
                    atlasNodeId = task.atlasNodeId,
                    deliverableNodeRef = task.deliverableNodeRef,
                    source = LegacyNodeSource.WIP,
                    sourceNodeId = task.taskId,
                    scopeId = projection.groupId,
                    errors = errors
                )?.let(mappings::add)
            }
        }

        snapshot.resources.nodes.forEach { resource ->
            validateAuthoritativeMapping(
                atlas = atlas,
                atlasNodeId = resource.atlasNodeId,
                deliverableNodeRef = resource.deliverableNodeRef,
                source = LegacyNodeSource.RESOURCE,
                sourceNodeId = resource.resourceNodeId,
                scopeId = resource.floorGroupId ?: snapshot.runId,
                errors = errors
            )?.let(mappings::add)
        }

        validateAuthoritativeDisclosure(snapshot, atlas, errors)
        if (errors.isNotEmpty()) return AtlasMigrationResult.Failure(errors)

        val projections = AtlasProjectionSet(
            wip = snapshot.wip,
            deliverables = snapshot.deliverableTrees,
            resources = snapshot.resources,
            disclosure = snapshot.disclosure.copy(stages = atlas.stageDefinitions),
            mappings = mappings.sortedWith(
                compareBy<LegacyNodeMapping>({ it.source.name }, { it.scopeId }, { it.sourceNodeId })
            )
        )
        return AtlasMigrationResult.Success(
            PreparedAtlasMigration(
                receiverContract = snapshot.receiverContract,
                atlas = atlas,
                executionStates = buildExecutionStates(snapshot, atlas, mappings),
                projections = projections
            )
        )
    }

    private fun validateAuthoritativeAtlas(
        snapshot: LegacyAtlasSnapshot,
        atlas: NodeAtlas,
        errors: MutableList<AtlasMigrationError>
    ) {
        if (atlas.atlasId != snapshot.atlasId) {
            errors += migrationError(
                AtlasMigrationErrorCode.INVALID_ATLAS,
                null,
                null,
                snapshot.atlasId,
                "Authoritative atlasId '${atlas.atlasId}' does not match snapshot atlasId"
            )
        }
        if (atlas.runId != snapshot.runId) {
            errors += migrationError(
                AtlasMigrationErrorCode.RUN_ID_MISMATCH,
                null,
                null,
                atlas.atlasId,
                "Authoritative Atlas runId '${atlas.runId}' does not match snapshot runId"
            )
        }
        val receiverHash = AtlasHashing.hashReceiverContract(snapshot.receiverContract)
        if (!AtlasHashing.constantTimeEquals(atlas.receiverContractHash, receiverHash)) {
            errors += migrationError(
                AtlasMigrationErrorCode.INVALID_ATLAS,
                null,
                null,
                atlas.atlasId,
                "Authoritative Atlas receiver contract hash does not match the migration snapshot"
            )
        }
        errors += atlas.validate().errors.map { failure ->
            migrationError(
                AtlasMigrationErrorCode.INVALID_ATLAS,
                null,
                failure.nodeId?.value,
                atlas.atlasId,
                "${failure.code}: ${failure.message}"
            )
        }
    }

    private fun validateAuthoritativeMapping(
        atlas: NodeAtlas,
        atlasNodeId: NodeId,
        deliverableNodeRef: String?,
        source: LegacyNodeSource,
        sourceNodeId: String,
        scopeId: String,
        errors: MutableList<AtlasMigrationError>
    ): LegacyNodeMapping? {
        if (atlasNodeId !in atlas.nodes) {
            errors += migrationError(
                AtlasMigrationErrorCode.MISSING_NODE_MAPPING,
                source,
                sourceNodeId,
                scopeId,
                "Projection references node '${atlasNodeId.value}' outside the authoritative Atlas"
            )
            return null
        }
        val explicitDeliverable = deliverableNodeRef?.takeIf(String::isNotBlank)?.let(::NodeId)
        if (explicitDeliverable != null && explicitDeliverable != atlasNodeId) {
            errors += migrationError(
                AtlasMigrationErrorCode.AMBIGUOUS_NODE_MAPPING,
                source,
                sourceNodeId,
                scopeId,
                "Projection Atlas node and deliverable reference disagree"
            )
            return null
        }
        return LegacyNodeMapping(source, sourceNodeId, scopeId, atlasNodeId)
    }

    private fun validateAuthoritativeDisclosure(
        snapshot: LegacyAtlasSnapshot,
        atlas: NodeAtlas,
        errors: MutableList<AtlasMigrationError>
    ) {
        if (snapshot.disclosure.stages != atlas.stageDefinitions) {
            errors += migrationError(
                AtlasMigrationErrorCode.INVALID_DISCLOSURE_STAGE,
                null,
                null,
                snapshot.runId,
                "Disclosure projection stages disagree with the authoritative Atlas"
            )
        }
        val stageIndices = atlas.stageDefinitions.mapTo(mutableSetOf(), DisclosureStage::stageIndex)
        snapshot.disclosure.groupStageIndices.forEach { (groupId, stageIndex) ->
            if (stageIndex !in stageIndices) {
                errors += migrationError(
                    AtlasMigrationErrorCode.INVALID_DISCLOSURE_STAGE,
                    null,
                    null,
                    groupId,
                    "Disclosure projection references unknown stage index $stageIndex"
                )
            }
        }
    }

    private fun validateRunIdentity(snapshot: LegacyAtlasSnapshot): List<AtlasMigrationError> {
        val mismatches = buildList {
            snapshot.deliverableTrees.forEach { tree ->
                if (tree.runId != snapshot.runId) add("deliverable/${tree.groupId}" to tree.runId)
            }
            snapshot.wip.forEach { projection ->
                if (projection.runId != snapshot.runId) add("wip/${projection.groupId}" to projection.runId)
            }
            if (snapshot.resources.runId != snapshot.runId) add("resource" to snapshot.resources.runId)
            if (snapshot.disclosure.runId != snapshot.runId) add("disclosure" to snapshot.disclosure.runId)
        }
        return mismatches.map { (scope, actualRunId) ->
            migrationError(
                AtlasMigrationErrorCode.RUN_ID_MISMATCH,
                null,
                null,
                scope,
                "Expected runId ${snapshot.runId}, found $actualRunId"
            )
        }
    }

    private fun collectDeliverableDefinitions(
        snapshot: LegacyAtlasSnapshot,
        errors: MutableList<AtlasMigrationError>
    ): Map<String, DeliverableDefinition> {
        val definitions = linkedMapOf<String, DeliverableDefinition>()
        snapshot.deliverableTrees.forEach { tree ->
            tree.nodes.forEach { node ->
                val definition = DeliverableDefinition(node)
                val existing = definitions[node.nodeId]
                if (existing == null) {
                    definitions[node.nodeId] = definition
                } else if (!existing.sameStaticDefinition(definition)) {
                    errors += migrationError(
                        AtlasMigrationErrorCode.DUPLICATE_NODE_DEFINITION,
                        LegacyNodeSource.DELIVERABLE,
                        node.nodeId,
                        tree.groupId,
                        "Competing group trees disagree on the sealed definition"
                    )
                }
            }
        }
        definitions.values.forEach { definition ->
            val parentId = definition.node.parentId
            if (parentId != null && parentId !in definitions) {
                errors += migrationError(
                    AtlasMigrationErrorCode.INVALID_DEPENDENCY,
                    LegacyNodeSource.DELIVERABLE,
                    definition.node.nodeId,
                    null,
                    "Unknown parent '$parentId'"
                )
            }
            definition.node.children.filterNot(definitions::containsKey).forEach { childId ->
                errors += migrationError(
                    AtlasMigrationErrorCode.INVALID_DEPENDENCY,
                    LegacyNodeSource.DELIVERABLE,
                    definition.node.nodeId,
                    null,
                    "Unknown child '$childId'"
                )
            }
        }
        return definitions
    }

    private fun resolveWipNode(
        task: WipProjectionTask,
        nodes: MutableMap<String, NodeDraft>,
        groupId: String,
        errors: MutableList<AtlasMigrationError>
    ): NodeId? {
        val explicitRef = task.deliverableNodeRef?.takeIf(String::isNotBlank)
        if (explicitRef != null) {
            if (explicitRef !in nodes) {
                errors += migrationError(
                    AtlasMigrationErrorCode.MISSING_NODE_MAPPING,
                    LegacyNodeSource.WIP,
                    task.taskId,
                    groupId,
                    "Unknown deliverable node '$explicitRef'"
                )
                return null
            }
            return NodeId(explicitRef)
        }
        val linkedRef = task.linkedNodeId?.takeIf { it in nodes }
        if (linkedRef != null) return NodeId(linkedRef)
        nodes.putIfAbsent(task.taskId, NodeDraft.fromWip(task))
        return NodeId(task.taskId)
    }

    private fun resolveResourceNode(
        resource: ResourceProjectionNode,
        nodes: MutableMap<String, NodeDraft>,
        errors: MutableList<AtlasMigrationError>
    ): NodeId? {
        val deliverableRef = resource.deliverableNodeRef?.takeIf(String::isNotBlank)
        if (deliverableRef != null) {
            if (deliverableRef !in nodes) {
                errors += migrationError(
                    AtlasMigrationErrorCode.MISSING_NODE_MAPPING,
                    LegacyNodeSource.RESOURCE,
                    resource.resourceNodeId,
                    resource.floorGroupId,
                    "Unknown deliverable node '$deliverableRef'"
                )
                return null
            }
            return NodeId(deliverableRef)
        }
        nodes.putIfAbsent(resource.resourceNodeId, NodeDraft.fromResource(resource))
        return NodeId(resource.resourceNodeId)
    }

    private fun normalizeStages(
        snapshot: LegacyAtlasSnapshot,
        nodeIds: Set<String>,
        errors: MutableList<AtlasMigrationError>
    ): List<DisclosureStage> {
        val input = snapshot.disclosure.stages
        if (input.isEmpty()) {
            val ids = nodeIds.mapTo(linkedSetOf(), ::NodeId)
            return listOf(
                DisclosureStage(
                    stageId = "stage-0",
                    stageIndex = 0,
                    title = "Initial",
                    nodeIds = ids,
                    mandatoryNodeIds = ids,
                    evidenceCoverageThreshold = 0.0,
                    integrationReadinessThreshold = 0.0,
                    revealPayloadHash = ""
                )
            )
        }
        input.forEach { stage ->
            stage.nodeIds.filterNot { it.value in nodeIds }.forEach { unknown ->
                errors += migrationError(
                    AtlasMigrationErrorCode.INVALID_DISCLOSURE_STAGE,
                    null,
                    unknown.value,
                    stage.stageId,
                    "Disclosure stage references an unknown node"
                )
            }
        }
        if (errors.isNotEmpty()) return emptyList()

        val assigned = input.flatMapTo(mutableSetOf()) { it.nodeIds }
        val unassigned = nodeIds.map(::NodeId).filterNot(assigned::contains).toSet()
        return input.mapIndexed { index, stage ->
            val stageNodes = if (index == 0) stage.nodeIds + unassigned else stage.nodeIds
            stage.copy(
                stageIndex = index,
                nodeIds = stageNodes,
                mandatoryNodeIds = stage.mandatoryNodeIds.intersect(stageNodes),
                revealPayloadHash = ""
            )
        }
    }

    private fun buildExecutionStates(
        snapshot: LegacyAtlasSnapshot,
        atlas: NodeAtlas,
        mappings: List<LegacyNodeMapping>
    ): List<NodeExecutionState> {
        val wipMapping = mappings
            .filter { it.source == LegacyNodeSource.WIP }
            .associateBy { it.scopeId to it.sourceNodeId }
        val resourceMapping = mappings
            .filter { it.source == LegacyNodeSource.RESOURCE }
            .associateBy { it.sourceNodeId }
        val deliverableMapping = mappings
            .filter { it.source == LegacyNodeSource.DELIVERABLE }
            .associateBy { it.scopeId to it.sourceNodeId }

        return atlas.nodes.keys.sortedBy(NodeId::value).map { nodeId ->
            val leases = snapshot.wip.flatMap { projection ->
                projection.tasks.mapNotNull { task ->
                    val mapping = wipMapping[projection.groupId to task.taskId]
                    if (mapping?.atlasNodeId != nodeId || task.status == "unclaimed") return@mapNotNull null
                    NodeLease(
                        leaseId = "legacy:${projection.groupId}:${task.taskId}",
                        nodeId = nodeId,
                        groupId = AtlasGroupId(projection.groupId),
                        agentId = task.claimedByAgentId?.let(::AtlasAgentId),
                        round = task.lastChangedGeneration,
                        expiresAt = Instant.fromEpochMilliseconds(0),
                        state = task.status.toLeaseState()
                    )
                }
            }
            val grades = snapshot.deliverableTrees.flatMap { tree ->
                tree.nodes.mapNotNull { node ->
                    val mapping = deliverableMapping[tree.groupId to node.nodeId]
                    if (mapping?.atlasNodeId != nodeId) return@mapNotNull null
                    node.gradingHistory.mapIndexed { index, grade ->
                        NodeGrade(
                            gradeId = "legacy:${tree.groupId}:${node.nodeId}:${grade.checkinId}:$index",
                            groupId = AtlasGroupId(tree.groupId),
                            evidencePacketId = "legacy:${grade.checkinId}",
                            normalizedScore = grade.dimensionScores.values
                                .takeIf { it.isNotEmpty() }
                                ?.average()
                                ?: 0.0,
                            verdict = if (grade.passedThreshold) {
                                GradeVerdict.VALID_PASS
                            } else {
                                GradeVerdict.VALID_FAIL
                            },
                            graderIds = grade.graderIds.toSet(),
                            round = node.updatedAtGeneration
                        )
                    }
                }.flatten()
            }
            val resources = snapshot.resources.nodes.filter { resource ->
                resourceMapping[resource.resourceNodeId]?.atlasNodeId == nodeId
            }
            val ownerIds = resources.mapNotNull { it.claimedByGroupId ?: it.floorGroupId }.distinct()
            val owner = ownerIds.singleOrNull()?.let(::AtlasGroupId)
            val stage = atlas.stageDefinitions.first { nodeId in it.nodeIds }
            val revealed = snapshot.disclosure.groupStageIndices
                .filterValues { stageIndex -> stageIndex >= stage.stageIndex }
                .keys
                .mapTo(linkedSetOf(), ::AtlasGroupId)

            NodeExecutionState(
                nodeId = nodeId,
                round = maxOf(
                    leases.maxOfOrNull(NodeLease::round) ?: 0,
                    grades.maxOfOrNull(NodeGrade::round) ?: 0
                ),
                revealedToGroups = revealed,
                leases = leases,
                evidencePacketIds = grades.map(NodeGrade::evidencePacketId).distinct(),
                grades = grades,
                ownerGroupId = owner,
                captureState = when {
                    ownerIds.size > 1 -> CaptureState.SHARED
                    owner != null -> CaptureState.CAPTURED
                    else -> CaptureState.UNOWNED
                }
            )
        }
    }

    private fun String.toLeaseState(): LeaseState = when (this) {
        "claimed" -> LeaseState.ACTIVE
        "completed" -> LeaseState.COMMITTED
        "abandoned" -> LeaseState.ABANDONED
        else -> LeaseState.EXPIRED
    }

    private data class DeliverableDefinition(val node: DeliverableSkeletonNode) {
        fun sameStaticDefinition(other: DeliverableDefinition): Boolean =
            node.nodeId == other.node.nodeId &&
                node.parentId == other.node.parentId &&
                node.children.toSet() == other.node.children.toSet() &&
                node.nodeType == other.node.nodeType &&
                node.title == other.node.title &&
                node.pathHint == other.node.pathHint &&
                node.artifactType == other.node.artifactType
    }

    private data class NodeDraft(
        val id: String,
        val title: String,
        val kind: NodeKind,
        val parentIds: Set<String>,
        val artifactTypes: Set<ArtifactType>
    ) {
        fun toNode(
            stage: DisclosureStage,
            resources: List<ResourceProjectionNode>
        ): LimitationNode {
            val minimumGrade = resources.maxOfOrNull(ResourceProjectionNode::qualityThreshold) ?: 0.0
            val rewardTypes = resources.mapTo(linkedSetOf(), ResourceProjectionNode::resourceType)
            val rewardCredits = resources.sumOf { ((it.yieldMultiplier - 1.0) * 100.0).coerceAtLeast(0.0) }
            return LimitationNode(
                id = NodeId(id),
                kind = kind,
                layer = stage.stageIndex,
                parentIds = parentIds.mapTo(linkedSetOf(), ::NodeId),
                prerequisiteIds = emptySet(),
                receiverWhyRef = "legacy:$id",
                limitationStatement = title,
                capabilityTarget = "Produce accepted evidence or an artifact for: $title",
                atomicQuestion = title,
                proofContract = ProofContract(
                    method = ProofMethod.RECEIVER_VALIDATION,
                    acceptanceCriteria = setOf("Observable artifact satisfies the migrated legacy contract"),
                    failureIsUseful = true
                ),
                deliverableContract = NodeDeliverableContract(
                    acceptedArtifactTypes = artifactTypes.ifEmpty { setOf(ArtifactType.DELIVERABLE_FRAGMENT) }
                ),
                revealPolicy = RevealPolicy(
                    stageId = stage.stageId,
                    mode = if (stage.stageIndex == 0) RevealMode.INITIAL else RevealMode.STAGE_PASS
                ),
                replicationPolicy = ReplicationPolicy(),
                capturePolicy = CapturePolicy(
                    mode = if (resources.isEmpty()) CaptureMode.NONE else CaptureMode.GRADE_AUTHORITY,
                    minimumGrade = minimumGrade
                ),
                rewardSpec = RewardSpec(
                    rewardClass = when {
                        rewardCredits <= 0.0 -> RewardClass.NONE
                        rewardCredits < 10.0 -> RewardClass.SMALL
                        rewardCredits < 25.0 -> RewardClass.MEDIUM
                        rewardCredits < 50.0 -> RewardClass.LARGE
                        else -> RewardClass.CRITICAL
                    },
                    credits = rewardCredits,
                    capabilityYields = rewardTypes
                )
            )
        }

        companion object {
            fun fromDeliverable(node: DeliverableSkeletonNode): NodeDraft = NodeDraft(
                id = node.nodeId,
                title = node.title.ifBlank { node.nodeId },
                kind = when (node.nodeType) {
                    DeliverableSkeletonNodeType.ROOT,
                    DeliverableSkeletonNodeType.ARTIFACT_BUNDLE -> NodeKind.INTEGRATION
                    DeliverableSkeletonNodeType.DOCUMENT_SECTION -> NodeKind.KNOWLEDGE
                    DeliverableSkeletonNodeType.SOURCE_FILE,
                    DeliverableSkeletonNodeType.API_SPEC -> NodeKind.MODEL
                    DeliverableSkeletonNodeType.TOOL -> NodeKind.TOOL
                    DeliverableSkeletonNodeType.TEST_SUITE -> NodeKind.VALIDATION
                    DeliverableSkeletonNodeType.DATASET -> NodeKind.EVIDENCE
                    DeliverableSkeletonNodeType.OTHER -> NodeKind.DELIVERY
                },
                parentIds = node.parentId?.let(::setOf).orEmpty(),
                artifactTypes = setOf(
                    when (node.artifactType) {
                        DeliverableArtifactType.KB_DOC -> ArtifactType.CLAIM_SET
                        DeliverableArtifactType.TOOL -> ArtifactType.TOOL
                        DeliverableArtifactType.REPO_SNAPSHOT -> ArtifactType.SOURCE_CODE
                        DeliverableArtifactType.TEST_SUITE -> ArtifactType.VALIDATION_REPORT
                        DeliverableArtifactType.OTHER -> ArtifactType.DELIVERABLE_FRAGMENT
                    }
                )
            )

            fun fromWip(task: WipProjectionTask): NodeDraft = NodeDraft(
                id = task.taskId,
                title = task.description.ifBlank { task.taskId },
                kind = NodeKind.KNOWLEDGE,
                parentIds = emptySet(),
                artifactTypes = setOf(ArtifactType.CLAIM_SET)
            )

            fun fromResource(resource: ResourceProjectionNode): NodeDraft = NodeDraft(
                id = resource.resourceNodeId,
                title = resource.name.ifBlank { resource.resourceNodeId },
                kind = NodeKind.TOOL,
                parentIds = emptySet(),
                artifactTypes = setOf(ArtifactType.TOOL)
            )
        }
    }

    private fun migrationError(
        code: AtlasMigrationErrorCode,
        source: LegacyNodeSource?,
        sourceNodeId: String?,
        scopeId: String?,
        message: String
    ) = AtlasMigrationError(code, source, sourceNodeId, scopeId, message)

    private companion object {
        val VALID_WIP_STATUSES = setOf("unclaimed", "claimed", "completed", "abandoned")
    }
}
