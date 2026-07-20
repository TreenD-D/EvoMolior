package com.achulkov.evomolior.substrate.worker

import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.contract.CausalFitnessRecord
import com.achulkov.evomolior.core.contract.ContributionAuthorityKind
import com.achulkov.evomolior.core.contract.ContributionAuthorityRef
import com.achulkov.evomolior.core.contract.FitnessPlane
import com.achulkov.evomolior.substrate.db.EvoMoliorDatabase
import com.achulkov.evomolior.substrate.db.entity.CausalFitnessRecordEntity
import kotlinx.serialization.json.Json

/** Validates and persists plane-separated fitness against exact graph and domain authorities. */
class CausalFitnessRepository(
    private val database: EvoMoliorDatabase,
    private val json: Json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    },
    private val now: () -> Long = System::currentTimeMillis
) {
    private val dao = database.causalFitnessDao()

    /** Appends one immutable causal fitness record or returns its identical prior instance. */
    suspend fun append(record: CausalFitnessRecord): CausalFitnessRecord {
        require(record.hasValidHash()) { "Causal fitness record hash is invalid" }
        record.components.flatMap { it.contributionEdgeIds }.distinct().forEach { edgeId ->
            val edge = requireNotNull(database.contributionGraphDao().byId(edgeId)) {
                "Causal fitness references unknown contribution edge $edgeId"
            }
            require(edge.runId == record.runId && edge.groupId == record.groupId.value) {
                "Causal fitness edge belongs to a different run or group"
            }
        }
        record.components.flatMap { it.authorityRefs }.distinct().forEach { authority ->
            validateExternalAuthority(record, authority)
        }
        dao.byId(record.recordId)?.let { existing ->
            require(AtlasHashing.constantTimeEquals(existing.recordHash, record.recordHash)) {
                "Causal fitness record identity is immutable"
            }
            return decode(existing)
        }
        dao.insert(record.toEntity())
        val persisted = requireNotNull(dao.byId(record.recordId)) {
            "Causal fitness record ${record.recordId} was not persisted"
        }
        require(AtlasHashing.constantTimeEquals(persisted.recordHash, record.recordHash)) {
            "Causal fitness hash conflicts with an existing record"
        }
        return decode(persisted)
    }

    /** Returns one exact causal fitness record. */
    suspend fun byId(recordId: String): CausalFitnessRecord? =
        dao.byId(recordId)?.let(::decode)

    /** Returns one round's records ranked only within the requested plane. */
    suspend fun forPlane(
        runId: String,
        groupId: String,
        round: Int,
        plane: FitnessPlane
    ): List<CausalFitnessRecord> =
        dao.forPlane(runId, groupId, round, plane.name).map(::decode)

    /** Returns the immutable fitness history for one agent without mixing planes. */
    suspend fun forAgent(
        runId: String,
        groupId: String,
        agentId: String
    ): List<CausalFitnessRecord> =
        dao.forAgent(runId, groupId, agentId).map(::decode)

    private suspend fun validateExternalAuthority(
        record: CausalFitnessRecord,
        authority: ContributionAuthorityRef
    ) {
        val entityHash = when (authority.kind) {
            ContributionAuthorityKind.TOOL_FITNESS ->
                database.toolInfrastructureDao().fitness(authority.authorityId)?.let {
                    require(it.runId == record.runId) {
                        "Tool fitness belongs to a different run"
                    }
                    it.recordHash
                }

            ContributionAuthorityKind.OUTLIER_FITNESS ->
                database.outlierNoveltyDao().fitnessById(authority.authorityId)?.let {
                    require(it.agentId == record.agentId) {
                        "Outlier fitness belongs to a different agent"
                    }
                    it.recordHash
                }

            ContributionAuthorityKind.GRADER_FITNESS ->
                database.graderAuthorityDao().fitnessById(authority.authorityId)?.let {
                    require(it.runId == record.runId && it.graderGroupId == record.groupId.value) {
                        "Grader fitness belongs to a different run or group"
                    }
                    it.recordHash
                }

            ContributionAuthorityKind.CONTROL_INTERVENTION ->
                database.contributionGraphDao()
                    .controlInterventionById(authority.authorityId)
                    ?.let {
                        require(it.runId == record.runId && it.groupId == record.groupId.value) {
                            "Control intervention belongs to a different run or group"
                        }
                        require(it.controllerAgentId == record.agentId) {
                            "Control intervention belongs to a different controller"
                        }
                        it.recordHash
                    }

            ContributionAuthorityKind.SUMMARY_VALIDATION ->
                database.contributionGraphDao()
                    .summaryValidationById(authority.authorityId)
                    ?.let {
                        require(it.runId == record.runId && it.groupId == record.groupId.value) {
                            "Summary validation belongs to a different run or group"
                        }
                        require(it.summarizerAgentId == record.agentId) {
                            "Summary validation belongs to a different summarizer"
                        }
                        it.recordHash
                    }

            else -> error(
                "Fitness external authority ${authority.kind.name} is not connected"
            )
        }
        val expectedHash = requireNotNull(entityHash) {
            "Unknown fitness authority ${authority.kind.name}:${authority.authorityId}"
        }
        val authorityHash = authority.authorityHash
        require(
            authorityHash != null &&
                AtlasHashing.constantTimeEquals(authorityHash, expectedHash)
        ) {
            "Fitness authority hash does not match"
        }
    }

    private fun CausalFitnessRecord.toEntity() = CausalFitnessRecordEntity(
        recordId = recordId,
        runId = runId,
        groupId = groupId.value,
        round = round,
        agentId = agentId,
        role = role.name,
        plane = plane.name,
        total = total,
        legacyEligible = legacyEligible,
        recordHash = recordHash,
        contentJson = json.encodeToString(CausalFitnessRecord.serializer(), this),
        createdAtMs = now()
    )

    private fun decode(entity: CausalFitnessRecordEntity): CausalFitnessRecord =
        json.decodeFromString(CausalFitnessRecord.serializer(), entity.contentJson)
}
