package com.achulkov.evomolior.substrate.atlas

import com.achulkov.evomolior.core.atlas.NodeAtlas
import com.achulkov.evomolior.core.contract.DeliverableSkeletonTree
import com.achulkov.evomolior.substrate.db.entity.AtlasProjectionEntity
import com.achulkov.evomolior.substrate.db.entity.WipTaskEntity
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Serializes complete immutable projection snapshots for one Atlas commit. */
internal class AtlasProjectionCodec(private val json: Json) {

    fun entities(
        atlas: NodeAtlas,
        commitSequence: Long,
        stateHash: String,
        projections: AtlasProjectionSet,
        timestamp: Long
    ): List<AtlasProjectionEntity> = buildList {
        projections.wip.forEach { projection ->
            add(entity(atlas, commitSequence, stateHash, AtlasProjectionType.WIP, projection.groupId, json.encodeToString(projection), timestamp))
        }
        projections.deliverables.forEach { tree ->
            add(entity(atlas, commitSequence, stateHash, AtlasProjectionType.DELIVERABLE, tree.groupId, json.encodeToString(tree), timestamp))
        }
        add(entity(atlas, commitSequence, stateHash, AtlasProjectionType.RESOURCE, atlas.runId, json.encodeToString(projections.resources), timestamp))
        add(entity(atlas, commitSequence, stateHash, AtlasProjectionType.DISCLOSURE, atlas.runId, json.encodeToString(projections.disclosure), timestamp))
        add(
            entity(
                atlas,
                commitSequence,
                stateHash,
                AtlasProjectionType.MIGRATION_MAPPING,
                atlas.runId,
                json.encodeToString(ListSerializer(LegacyNodeMapping.serializer()), projections.mappings),
                timestamp
            )
        )
    }

    fun wipEntities(projections: List<WipProjection>): List<WipTaskEntity> =
        projections.flatMap { projection ->
            projection.tasks.map { task ->
                WipTaskEntity(
                    nodeId = task.taskId,
                    runId = projection.runId,
                    groupId = projection.groupId,
                    description = task.description,
                    claimedByAgentId = task.claimedByAgentId,
                    status = task.status,
                    lastChangedGeneration = task.lastChangedGeneration,
                    abandonCount = task.abandonCount,
                    linkedNodeId = task.linkedNodeId,
                    dstNodeRef = task.deliverableNodeRef
                )
            }
        }

    fun decode(entities: List<AtlasProjectionEntity>): AtlasProjectionSet {
        val grouped = entities.groupBy(AtlasProjectionEntity::projectionType)
        return AtlasProjectionSet(
            wip = grouped[AtlasProjectionType.WIP.name].orEmpty()
                .map { json.decodeFromString(WipProjection.serializer(), it.payloadJson) },
            deliverables = grouped[AtlasProjectionType.DELIVERABLE.name].orEmpty()
                .map { json.decodeFromString(DeliverableSkeletonTree.serializer(), it.payloadJson) },
            resources = grouped.getValue(AtlasProjectionType.RESOURCE.name).single()
                .let { json.decodeFromString(ResourceProjection.serializer(), it.payloadJson) },
            disclosure = grouped.getValue(AtlasProjectionType.DISCLOSURE.name).single()
                .let { json.decodeFromString(DisclosureProjection.serializer(), it.payloadJson) },
            mappings = grouped.getValue(AtlasProjectionType.MIGRATION_MAPPING.name).single()
                .let {
                    json.decodeFromString(
                        ListSerializer(LegacyNodeMapping.serializer()),
                        it.payloadJson
                    )
                }
        )
    }

    private fun entity(
        atlas: NodeAtlas,
        commitSequence: Long,
        stateHash: String,
        type: AtlasProjectionType,
        scopeId: String,
        payload: String,
        timestamp: Long
    ) = AtlasProjectionEntity(
        projectionId = "${atlas.atlasId}:$commitSequence:${type.name}:$scopeId",
        atlasId = atlas.atlasId,
        runId = atlas.runId,
        atlasVersion = atlas.version,
        commitSequence = commitSequence,
        projectionType = type.name,
        scopeId = scopeId,
        payloadJson = payload,
        sourceAtlasHash = atlas.atlasHash,
        sourceStateHash = stateHash,
        createdAtMs = timestamp
    )
}
