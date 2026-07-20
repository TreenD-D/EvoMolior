package com.achulkov.evomolior.substrate.grader

import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.grader.EvaluationIndependenceProfile
import com.achulkov.evomolior.core.grader.GraderAtlas
import com.achulkov.evomolior.core.grader.GraderAtlasValidation
import com.achulkov.evomolior.core.grader.GraderCriteriaFreeze
import com.achulkov.evomolior.core.grader.GraderFitnessRecord
import com.achulkov.evomolior.core.grader.GraderNodeExecutionState
import com.achulkov.evomolior.core.grader.GraderNodeId
import com.achulkov.evomolior.core.grader.GraderNodeLease
import com.achulkov.evomolior.core.grader.GraderScoreBundle
import com.achulkov.evomolior.core.grader.GradingRequirement
import com.achulkov.evomolior.core.grader.GradingRequirementId
import com.achulkov.evomolior.core.grader.HiddenTestDefinition
import com.achulkov.evomolior.core.grader.HiddenTestId
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.db.entity.EvaluationIndependenceProfileEntity
import com.achulkov.evomolior.substrate.db.entity.GraderAtlasVersionEntity
import com.achulkov.evomolior.substrate.db.entity.GraderCriteriaFreezeEntity
import com.achulkov.evomolior.substrate.db.entity.GraderFitnessRecordEntity
import com.achulkov.evomolior.substrate.db.entity.GraderNodeLeaseEntity
import com.achulkov.evomolior.substrate.db.entity.GraderNodeStateEventEntity
import com.achulkov.evomolior.substrate.db.entity.GraderScoreBundleEntity
import com.achulkov.evomolior.substrate.db.entity.GradingRequirementEntity
import com.achulkov.evomolior.substrate.db.entity.HiddenTestDefinitionEntity
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * Append-only persistence and validation boundary for competitive grader authority.
 *
 * @param databaseFactory Run-global Atlas database factory.
 * @param now Wall-clock source used only for persistence ordering.
 */
class GraderAuthorityRepository(
    private val databaseFactory: SubstrateDatabaseFactory,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    }

    /**
     * Appends one validated Grader Atlas version.
     *
     * @param atlas Atlas version.
     * @return Persisted Atlas.
     */
    suspend fun appendAtlas(atlas: GraderAtlas): GraderAtlas {
        require(GraderAtlasValidation.validate(atlas).isEmpty()) {
            GraderAtlasValidation.validate(atlas).joinToString()
        }
        val database = databaseFactory.getAtlas(atlas.runId)
        val dao = database.graderAuthorityDao()
        dao.atlasVersion(atlas.graderAtlasId, atlas.version)?.let { persisted ->
            requireSameHash("Grader Atlas", atlas.atlasHash, persisted.atlasHash)
            return decode(GraderAtlas.serializer(), persisted.contentJson)
        }
        val previous = dao.latestAtlas(atlas.graderAtlasId)
        require(previous == null || atlas.version == previous.version + 1) {
            "Grader Atlas version must append exactly after the current version"
        }
        val entity = GraderAtlasVersionEntity(
            graderAtlasId = atlas.graderAtlasId,
            runId = atlas.runId,
            version = atlas.version,
            workerAtlasHash = atlas.workerAtlasHash,
            atlasHash = atlas.atlasHash,
            contentJson = encode(GraderAtlas.serializer(), atlas),
            createdAtMs = now()
        )
        dao.insertAtlasVersion(entity)
        return requireNotNull(dao.atlasVersion(atlas.graderAtlasId, atlas.version)) {
            "Grader Atlas insert did not persist ${atlas.graderAtlasId}:${atlas.version}"
        }.let { persisted ->
            requireSameHash("Grader Atlas", atlas.atlasHash, persisted.atlasHash)
            decode(GraderAtlas.serializer(), persisted.contentJson)
        }
    }

    /**
     * Appends one optimistic grader-node state revision.
     *
     * @param state State revision.
     * @return Persisted state.
     */
    suspend fun appendState(state: GraderNodeExecutionState): GraderNodeExecutionState {
        require(state.hasValidHash()) { "Grader node state hash is invalid" }
        require(state.graderGroupId.isNotBlank()) { "Grader node state requires a grader group" }
        val database = databaseFactory.getAtlas(state.runId)
        val dao = database.graderAuthorityDao()
        dao.stateByHash(state.stateHash)?.let {
            return decode(GraderNodeExecutionState.serializer(), it.contentJson)
        }
        val atlas = requireNotNull(dao.atlasByHash(state.graderAtlasHash)) {
            "Grader node state references an unknown Grader Atlas"
        }.let { decode(GraderAtlas.serializer(), it.contentJson) }
        require(state.nodeId in atlas.nodes) { "Grader node state references an unknown node" }
        val previous = dao.latestState(
            state.runId,
            state.graderAtlasHash,
            state.graderGroupId,
            state.nodeId.value
        )
        require(state.revision == (previous?.revision?.plus(1) ?: 0L)) {
            "Grader node state revision must append exactly after the current revision"
        }
        val eventId = "grader-state:${state.stateHash}"
        dao.insertStateEvent(
            GraderNodeStateEventEntity(
                eventId = eventId,
                runId = state.runId,
                graderAtlasHash = state.graderAtlasHash,
                graderGroupId = state.graderGroupId,
                nodeId = state.nodeId.value,
                revision = state.revision,
                status = state.status.name,
                stateHash = state.stateHash,
                contentJson = encode(GraderNodeExecutionState.serializer(), state),
                createdAtMs = now()
            )
        )
        return requireNotNull(dao.stateById(eventId)) {
            "Grader node state insert did not persist $eventId"
        }.let { persisted ->
            requireSameHash("grader node state", state.stateHash, persisted.stateHash)
            decode(GraderNodeExecutionState.serializer(), persisted.contentJson)
        }
    }

    /**
     * Appends one grader-node lease.
     *
     * @param lease Lease revision.
     * @return Persisted lease.
     */
    suspend fun appendLease(lease: GraderNodeLease): GraderNodeLease {
        require(lease.hasValidHash()) { "Grader node lease hash is invalid" }
        require(lease.expiresAtEpochMillis > lease.acquiredAtEpochMillis) {
            "Grader node lease expiry must follow acquisition"
        }
        val dao = databaseFactory.getAtlas(lease.runId).graderAuthorityDao()
        val atlas = requireNotNull(dao.atlasByHash(lease.graderAtlasHash)) {
            "Grader node lease references an unknown Grader Atlas"
        }.let { decode(GraderAtlas.serializer(), it.contentJson) }
        require(lease.nodeId in atlas.nodes) { "Grader node lease references an unknown node" }
        dao.insertLease(
            GraderNodeLeaseEntity(
                leaseId = lease.leaseId,
                runId = lease.runId,
                graderAtlasHash = lease.graderAtlasHash,
                nodeId = lease.nodeId.value,
                graderGroupId = lease.graderGroupId,
                expiresAtEpochMillis = lease.expiresAtEpochMillis,
                status = lease.status.name,
                leaseHash = lease.leaseHash,
                contentJson = encode(GraderNodeLease.serializer(), lease),
                createdAtMs = now()
            )
        )
        return requireNotNull(dao.leaseById(lease.leaseId)) {
            "Grader node lease insert did not persist ${lease.leaseId}"
        }.let { persisted ->
            requireSameHash("grader node lease", lease.leaseHash, persisted.leaseHash)
            decode(GraderNodeLease.serializer(), persisted.contentJson)
        }
    }

    /**
     * Atomically appends a mutually-referencing requirement and hidden-test batch.
     *
     * @param runId Owning run.
     * @param requirements Requirements authored by one grader group for one round.
     * @param hiddenTests Hidden tests referenced by the requirements.
     * @return Persisted requirements and tests.
     */
    suspend fun appendCriteriaBatch(
        runId: String,
        requirements: List<GradingRequirement>,
        hiddenTests: List<HiddenTestDefinition>
    ): Pair<List<GradingRequirement>, List<HiddenTestDefinition>> {
        require(requirements.isNotEmpty()) { "Criteria batch requires requirements" }
        val database = databaseFactory.getAtlas(runId)
        val dao = database.graderAuthorityDao()
        val atlasHashes = (requirements.map { it.graderAtlasHash } +
            hiddenTests.map { it.graderAtlasHash }).toSet()
        require(atlasHashes.size == 1) { "Criteria batch must use one Grader Atlas" }
        val atlas = requireNotNull(dao.atlasByHash(atlasHashes.single())) {
            "Criteria batch references an unknown Grader Atlas"
        }.let { decode(GraderAtlas.serializer(), it.contentJson) }
        val testsById = hiddenTests.associateBy(HiddenTestDefinition::testId)
        requirements.forEach { requirement ->
            require(GraderAtlasValidation.validate(requirement, atlas, testsById).isEmpty()) {
                GraderAtlasValidation.validate(requirement, atlas, testsById).joinToString()
            }
        }
        val requirementIds = requirements.mapTo(linkedSetOf()) { it.requirementId }
        hiddenTests.forEach { test ->
            require(test.hasValidHash()) { "Hidden test hash is invalid" }
            require(test.runId == runId && test.graderAtlasHash == atlas.atlasHash) {
                "Hidden test does not match its Grader Atlas authority"
            }
            require(test.mappedRequirementIds.isNotEmpty() &&
                requirementIds.containsAll(test.mappedRequirementIds)
            ) {
                "Hidden test references an unknown requirement"
            }
        }
        val createdAtMs = now()
        dao.insertCriteriaBatch(
            requirements.map { it.toEntity(createdAtMs) },
            hiddenTests.map { it.toEntity(createdAtMs) }
        )
        val persistedRequirements = requirements.map { requirement ->
            requireNotNull(dao.requirementById(requirement.requirementId.value)).let { persisted ->
                requireSameHash(
                    "grading requirement",
                    requirement.requirementHash,
                    persisted.requirementHash
                )
                decode(GradingRequirement.serializer(), persisted.contentJson)
            }
        }
        val persistedTests = hiddenTests.map { test ->
            requireNotNull(dao.hiddenTestById(test.testId.value)).let { persisted ->
                requireSameHash("hidden test", test.testHash, persisted.testHash)
                decode(HiddenTestDefinition.serializer(), persisted.contentJson)
            }
        }
        return persistedRequirements to persistedTests
    }

    /**
     * Freezes one grader group's exact criteria before worker reveal.
     *
     * @param freeze Proposed freeze.
     * @param expectedTargetSnapshotHashes Worker snapshots sealed by the runtime authority.
     * @return Persisted immutable freeze.
     */
    suspend fun appendCriteriaFreeze(
        freeze: GraderCriteriaFreeze,
        expectedTargetSnapshotHashes: Map<AtlasGroupId, String>
    ): GraderCriteriaFreeze {
        val dao = databaseFactory.getAtlas(freeze.runId).graderAuthorityDao()
        val requirements = dao.requirementsForRound(
            freeze.runId,
            freeze.round,
            freeze.graderGroupId
        ).map { decode(GradingRequirement.serializer(), it.contentJson) }
        val tests = dao.hiddenTestsForRound(
            freeze.runId,
            freeze.graderGroupId,
            freeze.round
        ).map { decode(HiddenTestDefinition.serializer(), it.contentJson) }
            .filter { it.testHash in freeze.hiddenTestHashes }
        require(
            GraderAtlasValidation.validate(
                freeze,
                requirements,
                tests,
                expectedTargetSnapshotHashes
            ).isEmpty()
        ) {
            GraderAtlasValidation.validate(
                freeze,
                requirements,
                tests,
                expectedTargetSnapshotHashes
            ).joinToString()
        }
        dao.insertCriteriaFreeze(
            GraderCriteriaFreezeEntity(
                freezeId = freeze.freezeId,
                runId = freeze.runId,
                round = freeze.round,
                graderGroupId = freeze.graderGroupId,
                graderAtlasHash = freeze.graderAtlasHash,
                freezeHash = freeze.freezeHash,
                contentJson = encode(GraderCriteriaFreeze.serializer(), freeze),
                createdAtMs = now()
            )
        )
        return requireNotNull(dao.freezeForRound(
            freeze.runId,
            freeze.round,
            freeze.graderGroupId
        )).let { persisted ->
            requireSameHash("criteria freeze", freeze.freezeHash, persisted.freezeHash)
            decode(GraderCriteriaFreeze.serializer(), persisted.contentJson)
        }
    }

    /**
     * Appends one exact grader score bundle after validating its frozen cardinality.
     *
     * @param bundle Bundle to append.
     * @param requiredNodeIds Exact required nodes.
     * @param requiredDimensionIds Exact required dimensions.
     * @param requiredRequirementIds Exact required requirements.
     * @return Persisted bundle.
     */
    suspend fun appendScoreBundle(
        bundle: GraderScoreBundle,
        requiredNodeIds: Set<NodeId>,
        requiredDimensionIds: Set<String>,
        requiredRequirementIds: Set<GradingRequirementId>
    ): GraderScoreBundle {
        val dao = databaseFactory.getAtlas(bundle.runId).graderAuthorityDao()
        val freeze = requireNotNull(dao.freezeByHash(bundle.criteriaFreezeHash)) {
            "Grader score bundle references an unknown criteria freeze"
        }.let { decode(GraderCriteriaFreeze.serializer(), it.contentJson) }
        require(
            GraderAtlasValidation.validate(
                bundle,
                freeze,
                requiredNodeIds,
                requiredDimensionIds,
                requiredRequirementIds
            ).isEmpty()
        ) {
            GraderAtlasValidation.validate(
                bundle,
                freeze,
                requiredNodeIds,
                requiredDimensionIds,
                requiredRequirementIds
            ).joinToString()
        }
        dao.insertScoreBundle(
            GraderScoreBundleEntity(
                bundleId = bundle.bundleId,
                runId = bundle.runId,
                round = bundle.round,
                graderGroupId = bundle.graderGroupId,
                targetGroupId = bundle.targetGroupId.value,
                criteriaFreezeHash = bundle.criteriaFreezeHash,
                targetSnapshotHash = bundle.targetSnapshotHash,
                status = bundle.status.name,
                payloadHash = bundle.payloadHash,
                contentJson = encode(GraderScoreBundle.serializer(), bundle),
                createdAtMs = now()
            )
        )
        return requireNotNull(dao.scoreBundleById(bundle.bundleId)).let { persisted ->
            requireSameHash("grader score bundle", bundle.payloadHash, persisted.payloadHash)
            decode(GraderScoreBundle.serializer(), persisted.contentJson)
        }
    }

    /** Appends one plane-separated grader-fitness record. */
    suspend fun appendFitnessRecord(record: GraderFitnessRecord): GraderFitnessRecord {
        require(record.hasValidHash()) {
            "Grader fitness record hash or deterministic-seed separation is invalid"
        }
        require(record.aggregateScore.isFinite() && record.aggregateScore in 0.0..1.0) {
            "Grader fitness aggregate must be normalized"
        }
        val qualityValues = with(record.quality) {
            listOf(
                breadth,
                depth,
                robustness,
                creativity,
                executability,
                calibration,
                discrimination,
                predictiveValidity,
                parsimony,
                actionability
            )
        }
        require(qualityValues.all { it.isFinite() && it in 0.0..1.0 }) {
            "Grader fitness quality values must be normalized"
        }
        val dao = databaseFactory.getAtlas(record.runId).graderAuthorityDao()
        dao.insertFitnessRecord(
            GraderFitnessRecordEntity(
                recordId = record.recordId,
                runId = record.runId,
                round = record.round,
                graderGroupId = record.graderGroupId,
                plane = record.plane.name,
                aggregateScore = record.aggregateScore,
                recordHash = record.recordHash,
                contentJson = encode(GraderFitnessRecord.serializer(), record),
                createdAtMs = now()
            )
        )
        return requireNotNull(dao.fitnessById(record.recordId)).let { persisted ->
            requireSameHash("grader fitness", record.recordHash, persisted.recordHash)
            decode(GraderFitnessRecord.serializer(), persisted.contentJson)
        }
    }

    /** Appends one run-level evaluation-independence profile. */
    suspend fun appendIndependenceProfile(
        runId: String,
        profile: EvaluationIndependenceProfile
    ): EvaluationIndependenceProfile {
        require(profile.hasValidHash()) { "Evaluation-independence profile hash is invalid" }
        val dao = databaseFactory.getAtlas(runId).graderAuthorityDao()
        val profileId = "evaluation-independence:${profile.profileHash}"
        dao.insertIndependenceProfile(
            EvaluationIndependenceProfileEntity(
                profileId = profileId,
                runId = runId,
                independenceLabel = profile.independenceLabel.name,
                profileHash = profile.profileHash,
                contentJson = encode(EvaluationIndependenceProfile.serializer(), profile),
                createdAtMs = now()
            )
        )
        return requireNotNull(dao.latestIndependenceProfile(runId)).let { persisted ->
            requireSameHash(
                "evaluation-independence profile",
                profile.profileHash,
                persisted.profileHash
            )
            decode(EvaluationIndependenceProfile.serializer(), persisted.contentJson)
        }
    }

    /** Returns exact score bundles for one round in deterministic cardinality order. */
    suspend fun scoreBundlesForRound(runId: String, round: Int): List<GraderScoreBundle> =
        databaseFactory.getAtlas(runId).graderAuthorityDao()
            .scoreBundlesForRound(runId, round)
            .map { decode(GraderScoreBundle.serializer(), it.contentJson) }

    /** Returns exact score bundles for one run in round and cardinality order. */
    suspend fun scoreBundlesForRun(runId: String): List<GraderScoreBundle> =
        databaseFactory.getAtlas(runId).graderAuthorityDao()
            .scoreBundlesForRun(runId)
            .map { decode(GraderScoreBundle.serializer(), it.contentJson) }

    /** Returns one grader group's immutable criteria freeze for a round. */
    suspend fun criteriaFreezeForRound(
        runId: String,
        round: Int,
        graderGroupId: String
    ): GraderCriteriaFreeze? =
        databaseFactory.getAtlas(runId).graderAuthorityDao()
            .freezeForRound(runId, round, graderGroupId)
            ?.let { decode(GraderCriteriaFreeze.serializer(), it.contentJson) }

    /** Returns typed requirements authored by one grader group for a round. */
    suspend fun requirementsForRound(
        runId: String,
        round: Int,
        graderGroupId: String
    ): List<GradingRequirement> =
        databaseFactory.getAtlas(runId).graderAuthorityDao()
            .requirementsForRound(runId, round, graderGroupId)
            .map { decode(GradingRequirement.serializer(), it.contentJson) }

    /** Returns all typed grading requirements for one run in round and group order. */
    suspend fun requirementsForRun(runId: String): List<GradingRequirement> =
        databaseFactory.getAtlas(runId).graderAuthorityDao()
            .requirementsForRun(runId)
            .map { decode(GradingRequirement.serializer(), it.contentJson) }

    /** Returns every immutable criteria freeze for one run. */
    suspend fun criteriaFreezesForRun(runId: String): List<GraderCriteriaFreeze> =
        databaseFactory.getAtlas(runId).graderAuthorityDao()
            .criteriaFreezesForRun(runId)
            .map { decode(GraderCriteriaFreeze.serializer(), it.contentJson) }

    /** Returns only hidden tests committed by one immutable criteria freeze. */
    suspend fun hiddenTestsForFreeze(
        freeze: GraderCriteriaFreeze
    ): List<HiddenTestDefinition> =
        databaseFactory.getAtlas(freeze.runId).graderAuthorityDao()
            .hiddenTestsByHashes(
                freeze.runId,
                freeze.graderGroupId,
                freeze.hiddenTestHashes
            )
            .map { decode(HiddenTestDefinition.serializer(), it.contentJson) }

    /** Returns the latest sealed Grader Atlas for one run. */
    suspend fun currentAtlas(runId: String): GraderAtlas? =
        databaseFactory.getAtlas(runId).graderAuthorityDao()
            .latestAtlasForRun(runId)
            ?.let { decode(GraderAtlas.serializer(), it.contentJson) }

    /** Returns the latest exact run-level evaluator-independence profile. */
    suspend fun currentIndependenceProfile(runId: String): EvaluationIndependenceProfile? =
        databaseFactory.getAtlas(runId).graderAuthorityDao()
            .latestIndependenceProfile(runId)
            ?.let { decode(EvaluationIndependenceProfile.serializer(), it.contentJson) }

    /** Returns grader fitness history in round and plane order. */
    suspend fun fitnessHistory(
        runId: String,
        graderGroupId: String
    ): List<GraderFitnessRecord> =
        databaseFactory.getAtlas(runId).graderAuthorityDao()
            .fitnessHistory(runId, graderGroupId)
            .map { decode(GraderFitnessRecord.serializer(), it.contentJson) }

    /** Returns all grader-fitness records for one run in round and plane order. */
    suspend fun fitnessHistoryForRun(runId: String): List<GraderFitnessRecord> =
        databaseFactory.getAtlas(runId).graderAuthorityDao()
            .fitnessHistoryForRun(runId)
            .map { decode(GraderFitnessRecord.serializer(), it.contentJson) }

    /** Returns the current state for one grader node. */
    suspend fun currentState(
        runId: String,
        graderAtlasHash: String,
        graderGroupId: String,
        nodeId: GraderNodeId
    ): GraderNodeExecutionState? =
        databaseFactory.getAtlas(runId).graderAuthorityDao()
            .latestState(runId, graderAtlasHash, graderGroupId, nodeId.value)
            ?.let { decode(GraderNodeExecutionState.serializer(), it.contentJson) }

    /**
     * Returns the most recently persisted state across grader groups.
     *
     * This compatibility projection is not suitable for traversal decisions; callers implementing
     * grader execution must use the group-scoped overload.
     */
    suspend fun currentState(
        runId: String,
        graderAtlasHash: String,
        nodeId: GraderNodeId
    ): GraderNodeExecutionState? =
        databaseFactory.getAtlas(runId).graderAuthorityDao()
            .latestStateAcrossGroups(runId, graderAtlasHash, nodeId.value)
            ?.let { decode(GraderNodeExecutionState.serializer(), it.contentJson) }

    /** Returns one immutable grader-node lease by identity. */
    suspend fun leaseById(runId: String, leaseId: String): GraderNodeLease? =
        databaseFactory.getAtlas(runId).graderAuthorityDao()
            .leaseById(leaseId)
            ?.let { decode(GraderNodeLease.serializer(), it.contentJson) }

    private fun GradingRequirement.toEntity(createdAtMs: Long) = GradingRequirementEntity(
        requirementId = requirementId.value,
        runId = runId,
        round = round,
        graderGroupId = graderGroupId,
        graderAtlasHash = graderAtlasHash,
        graderAtlasNodeId = graderAtlasNodeId.value,
        origin = origin.name,
        requirementHash = requirementHash,
        contentJson = encode(GradingRequirement.serializer(), this),
        createdAtMs = createdAtMs
    )

    private fun HiddenTestDefinition.toEntity(createdAtMs: Long) = HiddenTestDefinitionEntity(
        testId = testId.value,
        runId = runId,
        graderGroupId = graderGroupId,
        roundCreated = roundCreated,
        graderAtlasHash = graderAtlasHash,
        status = status.name,
        parentTestHash = parentTestHash,
        testHash = testHash,
        contentJson = encode(HiddenTestDefinition.serializer(), this),
        createdAtMs = createdAtMs
    )

    private fun <T> encode(serializer: KSerializer<T>, value: T): String =
        json.encodeToString(serializer, value)

    private fun <T> decode(serializer: KSerializer<T>, value: String): T =
        json.decodeFromString(serializer, value)

    private fun requireSameHash(label: String, expected: String, actual: String) {
        require(expected == actual) {
            "$label identity already exists with different immutable content"
        }
    }
}
