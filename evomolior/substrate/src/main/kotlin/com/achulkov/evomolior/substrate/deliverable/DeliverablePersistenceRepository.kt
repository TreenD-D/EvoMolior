@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.substrate.deliverable

import com.achulkov.evomolior.core.deliverable.DeliverableBlueprint
import com.achulkov.evomolior.core.deliverable.InterimDeliverableSnapshot
import com.achulkov.evomolior.core.deliverable.ReceiverDecisionPackage
import com.achulkov.evomolior.substrate.db.EvoMoliorDatabase
import com.achulkov.evomolior.substrate.db.entity.DecisionPackageEntity
import com.achulkov.evomolior.substrate.db.entity.DeliverableBlueprintEntity
import com.achulkov.evomolior.substrate.db.entity.InterimDeliverableSnapshotEntity
import kotlinx.serialization.json.Json

/** Durable append-only authority for blueprints, interim snapshots, and receiver packages. */
class DeliverablePersistenceRepository(
    database: EvoMoliorDatabase,
    private val json: Json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        classDiscriminator = "blockType"
    }
) {
    private val dao = database.completionPersistenceDao()

    /** Appends [blueprint] idempotently and rejects mutation of its stable identity. */
    suspend fun appendBlueprint(blueprint: DeliverableBlueprint): DeliverableBlueprint {
        require(blueprint.hasValidHash()) {
            "Deliverable blueprint hash does not match its exact content"
        }
        blueprint(blueprint.blueprintId)?.let { existing ->
            require(existing == blueprint) { "Deliverable blueprint identity is immutable" }
            return existing
        }
        dao.insertBlueprint(blueprint.toEntity())
        return requireNotNull(blueprint(blueprint.blueprintId)) {
            "Deliverable blueprint append conflicted with another immutable authority"
        }.also { persisted ->
            require(persisted == blueprint) { "Deliverable blueprint identity is immutable" }
        }
    }

    /** Returns one exact deliverable blueprint by [blueprintId]. */
    suspend fun blueprint(blueprintId: String): DeliverableBlueprint? =
        dao.blueprint(blueprintId)?.toBlueprint()

    /** Appends [snapshot] idempotently for its exact run/group/round authority. */
    suspend fun appendSnapshot(
        snapshot: InterimDeliverableSnapshot
    ): InterimDeliverableSnapshot {
        require(snapshot.hasValidHash()) {
            "Interim deliverable snapshot hash does not match its exact content"
        }
        snapshot(snapshot.runId, snapshot.groupId, snapshot.round)?.let { existing ->
            require(existing == snapshot) { "Interim deliverable snapshot identity is immutable" }
            return existing
        }
        dao.insertSnapshot(snapshot.toEntity())
        return requireNotNull(snapshot(snapshot.runId, snapshot.groupId, snapshot.round)) {
            "Interim snapshot append conflicted with another immutable authority"
        }.also { persisted ->
            require(persisted == snapshot) {
                "Interim deliverable snapshot identity is immutable"
            }
        }
    }

    /** Returns the exact snapshot for [runId], [groupId], and [round]. */
    suspend fun snapshot(
        runId: String,
        groupId: String,
        round: Int
    ): InterimDeliverableSnapshot? =
        dao.snapshot(runId, groupId, round)?.toSnapshot()

    /** Returns all exact snapshots for one group in round order. */
    suspend fun snapshots(runId: String, groupId: String): List<InterimDeliverableSnapshot> =
        dao.snapshots(runId, groupId).map { it.toSnapshot() }

    /** Appends [decisionPackage] idempotently and rejects mutation of its stable identity. */
    suspend fun appendDecisionPackage(
        decisionPackage: ReceiverDecisionPackage
    ): ReceiverDecisionPackage {
        require(decisionPackage.hasValidHashes()) {
            "Receiver decision package hashes do not match its exact content"
        }
        decisionPackage(decisionPackage.packageId)?.let { existing ->
            require(existing == decisionPackage) { "Decision package identity is immutable" }
            return existing
        }
        dao.insertDecisionPackage(decisionPackage.toEntity())
        return requireNotNull(decisionPackage(decisionPackage.packageId)) {
            "Decision package append conflicted with another immutable authority"
        }.also { persisted ->
            require(persisted == decisionPackage) { "Decision package identity is immutable" }
        }
    }

    /** Returns one exact receiver package by [packageId]. */
    suspend fun decisionPackage(packageId: String): ReceiverDecisionPackage? =
        dao.decisionPackage(packageId)?.toDecisionPackage()

    /** Returns all exact receiver packages for [runId] in deterministic creation order. */
    suspend fun decisionPackages(runId: String): List<ReceiverDecisionPackage> =
        dao.decisionPackages(runId).map { it.toDecisionPackage() }

    private fun DeliverableBlueprint.toEntity() = DeliverableBlueprintEntity(
        blueprintId = blueprintId,
        schemaVersion = schemaVersion,
        receiverContractHash = receiverContractHash,
        primaryFormat = primaryFormat.name,
        blueprintHash = blueprintHash,
        contentJson = json.encodeToString(DeliverableBlueprint.serializer(), this)
    )

    private fun DeliverableBlueprintEntity.toBlueprint(): DeliverableBlueprint =
        json.decodeFromString(DeliverableBlueprint.serializer(), contentJson).also { value ->
            require(
                value.hasValidHash() &&
                    value.blueprintId == blueprintId &&
                    value.schemaVersion == schemaVersion &&
                    value.receiverContractHash == receiverContractHash &&
                    value.primaryFormat.name == primaryFormat &&
                    value.blueprintHash == blueprintHash
            ) { "Persisted deliverable blueprint metadata does not match its exact content" }
        }

    private fun InterimDeliverableSnapshot.toEntity() = InterimDeliverableSnapshotEntity(
        snapshotHash = snapshotHash,
        schemaVersion = schemaVersion,
        runId = runId,
        groupId = groupId,
        round = round,
        blueprintHash = blueprintHash,
        contentJson = json.encodeToString(InterimDeliverableSnapshot.serializer(), this)
    )

    private fun InterimDeliverableSnapshotEntity.toSnapshot(): InterimDeliverableSnapshot =
        json.decodeFromString(InterimDeliverableSnapshot.serializer(), contentJson).also { value ->
            require(
                value.hasValidHash() &&
                    value.snapshotHash == snapshotHash &&
                    value.schemaVersion == schemaVersion &&
                    value.runId == runId &&
                    value.groupId == groupId &&
                    value.round == round &&
                    value.blueprintHash == blueprintHash
            ) { "Persisted interim snapshot metadata does not match its exact content" }
        }

    private fun ReceiverDecisionPackage.toEntity() = DecisionPackageEntity(
        packageId = packageId,
        schemaVersion = schemaVersion,
        runId = runId,
        outcomeClass = outcomeClass.name,
        receiverContractHash = receiverContractHash,
        blueprintHash = blueprintHash,
        packageHash = packageHash,
        generatedAtMs = generatedAt.toEpochMilliseconds(),
        contentJson = json.encodeToString(ReceiverDecisionPackage.serializer(), this)
    )

    private fun DecisionPackageEntity.toDecisionPackage(): ReceiverDecisionPackage =
        json.decodeFromString(ReceiverDecisionPackage.serializer(), contentJson).also { value ->
            require(
                value.hasValidHashes() &&
                    value.packageId == packageId &&
                    value.schemaVersion == schemaVersion &&
                    value.runId == runId &&
                    value.outcomeClass.name == outcomeClass &&
                    value.receiverContractHash == receiverContractHash &&
                    value.blueprintHash == blueprintHash &&
                    value.packageHash == packageHash &&
                    value.generatedAt.toEpochMilliseconds() == generatedAtMs
            ) { "Persisted decision package metadata does not match its exact content" }
        }
}
