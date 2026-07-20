package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.competition.disclosure.DisclosureStage
import com.achulkov.evomolior.competition.resourcemap.ResourceAtlasProjectionAdapter
import com.achulkov.evomolior.competition.resourcemap.ResourceNode
import com.achulkov.evomolior.core.atlas.DisclosureStage as AtlasDisclosureStage
import com.achulkov.evomolior.core.atlas.NodeAtlas
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.atlas.ReceiverContract
import com.achulkov.evomolior.substrate.DeliverableSkeletonTreeStore
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.atlas.AtlasMigrationError
import com.achulkov.evomolior.substrate.atlas.AtlasMigrationErrorCode
import com.achulkov.evomolior.substrate.atlas.AtlasMigrationResult
import com.achulkov.evomolior.substrate.atlas.DisclosureProjection
import com.achulkov.evomolior.substrate.atlas.LegacyAtlasMigrationCompiler
import com.achulkov.evomolior.substrate.atlas.LegacyAtlasSnapshot
import com.achulkov.evomolior.substrate.atlas.LegacyDisclosureStageProjection
import com.achulkov.evomolior.substrate.atlas.LegacyNodeSource
import com.achulkov.evomolior.substrate.atlas.WipProjection
import com.achulkov.evomolior.substrate.atlas.WipProjectionTask

/**
 * API migration boundary that snapshots the current WIP, deliverable, resource, and disclosure
 * consumers before delegating deterministic Atlas compilation to substrate.
 */
class LegacyNodeAtlasMigrationAdapter(
    private val substrateFactory: SubstrateDatabaseFactory,
    private val deliverableStore: DeliverableSkeletonTreeStore,
    private val compiler: LegacyAtlasMigrationCompiler = LegacyAtlasMigrationCompiler()
) {

    /**
     * Captures all legacy node systems and compiles one initial Atlas.
     *
     * @param authoritativeAtlas Optional Council-compiled Atlas that must be preserved exactly.
     */
    suspend fun compile(
        atlasId: String,
        runId: String,
        receiverContract: ReceiverContract,
        groupIds: List<String>,
        resourceNodes: List<ResourceNode>,
        disclosureStages: List<DisclosureStage>,
        authoritativeAtlas: NodeAtlas? = null
    ): AtlasMigrationResult {
        val trees = groupIds.mapNotNull { groupId ->
            deliverableStore.read(runId, groupId)
        }
        if (trees.size != groupIds.size) {
            val foundGroups = trees.mapTo(mutableSetOf()) { it.groupId }
            return AtlasMigrationResult.Failure(
                groupIds.filterNot(foundGroups::contains).map { groupId ->
                    AtlasMigrationError(
                        code = AtlasMigrationErrorCode.MISSING_NODE_MAPPING,
                        source = LegacyNodeSource.DELIVERABLE,
                        sourceNodeId = null,
                        scopeId = groupId,
                        message = "No seeded deliverable tree exists for $runId/$groupId"
                    )
                }
            )
        }
        val deliverableNodeIds = trees.flatMapTo(mutableSetOf()) { tree ->
            tree.nodes.map { it.nodeId }
        }
        val wip = groupIds.map { groupId ->
            val tasks = substrateFactory.get(runId, groupId).wipTaskDao().getAll(runId, groupId)
            WipProjection(
                runId = runId,
                groupId = groupId,
                tasks = tasks.map { task ->
                    val atlasNodeId = task.dstNodeRef
                        ?.takeIf(deliverableNodeIds::contains)
                        ?: task.linkedNodeId?.takeIf(deliverableNodeIds::contains)
                        ?: task.nodeId
                    WipProjectionTask(
                        taskId = task.nodeId,
                        atlasNodeId = NodeId(atlasNodeId),
                        description = task.description,
                        claimedByAgentId = task.claimedByAgentId,
                        status = task.status,
                        lastChangedGeneration = task.lastChangedGeneration,
                        abandonCount = task.abandonCount,
                        linkedNodeId = task.linkedNodeId,
                        deliverableNodeRef = task.dstNodeRef
                    )
                }
            )
        }
        val effectiveDisclosureStages = disclosureStages.ifEmpty {
            listOf(
                DisclosureStage(
                    stageIndex = 0,
                    title = "Initial",
                    constraintFramedContent = "",
                    goalFramedContent = "",
                    unlockCondition = "",
                    isInitial = true
                )
            )
        }
        val atlasStages = authoritativeAtlas?.stageDefinitions
            ?: buildAtlasStages(trees.first(), effectiveDisclosureStages)
        return compiler.compile(
            LegacyAtlasSnapshot(
                atlasId = atlasId,
                runId = runId,
                receiverContract = receiverContract,
                deliverableTrees = trees,
                wip = wip,
                resources = ResourceAtlasProjectionAdapter.toProjection(runId, resourceNodes),
                disclosure = DisclosureProjection(
                    runId = runId,
                    stages = atlasStages,
                    groupStageIndices = groupIds.associateWith { 0 },
                    groupLeakMultipliers = groupIds.associateWith { 1.0 },
                    legacyStages = effectiveDisclosureStages.map { stage ->
                        LegacyDisclosureStageProjection(
                            stageIndex = stage.stageIndex,
                            title = stage.title,
                            constraintFramedContent = stage.constraintFramedContent,
                            goalFramedContent = stage.goalFramedContent,
                            unlockCondition = stage.unlockCondition,
                            isInitial = stage.isInitial
                        )
                    }
                )
            ),
            authoritativeAtlas = authoritativeAtlas
        )
    }

    private fun buildAtlasStages(
        tree: com.achulkov.evomolior.core.contract.DeliverableSkeletonTree,
        legacyStages: List<DisclosureStage>
    ): List<AtlasDisclosureStage> {
        val stageCount = legacyStages.size.coerceAtLeast(1)
        val depths = mutableMapOf<String, Int>()
        fun depth(nodeId: String): Int = depths.getOrPut(nodeId) {
            val parentId = tree.nodes.firstOrNull { it.nodeId == nodeId }?.parentId
            if (parentId == null) 0 else depth(parentId) + 1
        }
        val nodesByStage = tree.nodes.groupBy { node ->
            depth(node.nodeId).coerceAtMost(stageCount - 1)
        }
        return (0 until stageCount).map { index ->
            val legacy = legacyStages.getOrNull(index)
            val nodeIds = nodesByStage[index].orEmpty().mapTo(linkedSetOf()) { NodeId(it.nodeId) }
            AtlasDisclosureStage(
                stageId = "stage-$index",
                stageIndex = index,
                title = legacy?.title ?: "Initial",
                nodeIds = nodeIds,
                mandatoryNodeIds = nodeIds,
                evidenceCoverageThreshold = if (index == 0) 0.0 else 0.6,
                integrationReadinessThreshold = if (index == 0) 0.0 else 0.6,
                revealPayloadHash = ""
            )
        }
    }
}
