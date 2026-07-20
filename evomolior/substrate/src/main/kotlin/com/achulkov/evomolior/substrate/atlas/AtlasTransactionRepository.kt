package com.achulkov.evomolior.substrate.atlas

import com.achulkov.evomolior.core.atlas.AmendmentDecision
import com.achulkov.evomolior.core.atlas.AtlasAmendment
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.NodeAtlas
import com.achulkov.evomolior.core.atlas.NodeCaptureDecision
import com.achulkov.evomolior.core.atlas.NodeExecutionState
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.atlas.NodeYield
import com.achulkov.evomolior.core.atlas.NodeYieldApplication
import com.achulkov.evomolior.core.atlas.NodeYieldSchedule
import com.achulkov.evomolior.core.atlas.ReceiverContract
import com.achulkov.evomolior.core.atlas.GroupRevealPayload
import com.achulkov.evomolior.core.atlas.ShadowNodeLifecycleEvent
import com.achulkov.evomolior.core.atlas.ShadowNodeLifecycleValidator
import com.achulkov.evomolior.core.atlas.ShadowNodeState
import com.achulkov.evomolior.core.contract.toRevealedNodeCard
import com.achulkov.evomolior.core.grader.GraderAtlas
import com.achulkov.evomolior.core.grader.GraderAtlasValidation
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.db.entity.AtlasAmendmentEntity
import com.achulkov.evomolior.substrate.db.entity.AtlasCommitEntity
import com.achulkov.evomolior.substrate.db.entity.AtlasExecutionStateEventEntity
import com.achulkov.evomolior.substrate.db.entity.AtlasVersionEntity
import com.achulkov.evomolior.substrate.db.entity.GraderAtlasVersionEntity
import com.achulkov.evomolior.substrate.db.entity.NodeCaptureDecisionEntity
import com.achulkov.evomolior.substrate.db.entity.NodeYieldApplicationEntity
import com.achulkov.evomolior.substrate.db.entity.OutlierFitnessRecordEntity
import com.achulkov.evomolior.substrate.db.entity.ShadowNodeLifecycleEventEntity
import com.achulkov.evomolior.substrate.db.entity.WipTaskEntity
import kotlinx.serialization.KSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Sole mutation authority for sealed Atlas versions, node execution state, ownership, disclosure,
 * and legacy-compatible projections.
 *
 * Every successful operation appends a commit and all derived projections in one Room
 * transaction. Optimistic hashes reject stale writers before an ownership or disclosure update
 * can become authoritative.
 */
class AtlasTransactionRepository(
    private val databaseFactory: SubstrateDatabaseFactory,
    private val json: Json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    },
    private val now: () -> Long = System::currentTimeMillis
) {
    private val projectionCodec = AtlasProjectionCodec(json)
    private val mutationMutex = Mutex()

    /**
     * Persists the first sealed version and migrated execution state.
     *
     * @param migration Validated initial migration.
     * @return The committed authority state.
     */
    suspend fun initialize(migration: PreparedAtlasMigration): AtlasCommitResult =
        mutationMutex.withLock {
        val atlas = migration.atlas
        require(atlas.version == 1) { "Initial Atlas version must be 1" }
        atlas.validate().requireValid()
        validateStateCoverage(atlas, migration.executionStates)
        validateProjections(atlas, migration.executionStates, migration.projections)

        val dao = databaseFactory.getAtlas(atlas.runId).atlasDao()
        if (dao.latestVersion(atlas.atlasId) != null) {
            throw AtlasTransactionException(
                AtlasTransactionErrorCode.ALREADY_INITIALIZED,
                "Atlas ${atlas.atlasId} is already initialized"
            )
        }
        val timestamp = now()
        val commitSequence = 1L
        val commitId = commitId(atlas.atlasId, commitSequence)
        val stateEvents = migration.executionStates.map { state ->
            stateEvent(
                atlas = atlas,
                commitSequence = commitSequence,
                stateRevision = 1L,
                previousStateHash = null,
                mutationType = AtlasMutationType.INITIALIZE.name,
                state = state,
                timestamp = timestamp
            )
        }
        val stateHash = AtlasHashing.hashExecutionStates(migration.executionStates)
        val projectionEntities = projectionCodec.entities(
            atlas = atlas,
            commitSequence = commitSequence,
            stateHash = stateHash,
            projections = migration.projections,
            timestamp = timestamp
        )
        dao.appendTransaction(
            commit = AtlasCommitEntity(
                commitId = commitId,
                atlasId = atlas.atlasId,
                runId = atlas.runId,
                commitSequence = commitSequence,
                atlasVersion = atlas.version,
                atlasHash = atlas.atlasHash,
                mutationType = AtlasMutationType.INITIALIZE.name,
                createdAtMs = timestamp
            ),
            version = versionEntity(
                atlas = atlas,
                receiverContractJson = json.encodeToString(migration.receiverContract),
                timestamp = timestamp
            ),
            amendment = null,
            captureDecision = null,
            yieldApplication = null,
            stateEvents = stateEvents,
            projections = projectionEntities,
            wipTasks = projectionCodec.wipEntities(migration.projections.wip)
        )
        return AtlasCommitResult(
            commitId = commitId,
            commitSequence = commitSequence,
            atlas = atlas,
            stateHashes = stateEvents.associate { NodeId(it.nodeId) to it.stateHash },
            projectionCount = projectionEntities.size
        )
    }

    /**
     * Appends one accepted amendment and the resulting sealed Atlas version.
     *
     * @param runId Run owning the Atlas.
     * @param amendment Accepted amendment metadata.
     * @param nextAtlas Sealed child version.
     * @param projections Complete projections for the child version.
     * @return The committed authority state.
     */
    internal suspend fun appendAmendment(
        runId: String,
        amendment: AtlasAmendment,
        nextAtlas: NodeAtlas,
        projections: AtlasProjectionSet
    ): AtlasCommitResult = mutationMutex.withLock {
        val dao = databaseFactory.getAtlas(runId).atlasDao()
        val prepared = prepareAmendment(
            runId = runId,
            amendment = amendment,
            nextAtlas = nextAtlas,
            projections = projections,
            timestamp = now()
        )
        dao.appendTransaction(
            commit = prepared.commit,
            version = prepared.version,
            amendment = prepared.amendment,
            captureDecision = null,
            yieldApplication = null,
            stateEvents = prepared.stateEvents,
            projections = prepared.projections,
            wipTasks = prepared.wipTasks
        )
        prepared.result
    }

    /**
     * Atomically appends a matched worker/Grader Atlas pair and accepted Outlier authority.
     *
     * @param request Complete round-boundary transition.
     * @return The committed worker Atlas authority state.
     */
    suspend fun appendAmendmentAuthorityTransition(
        request: AtlasAmendmentAuthorityTransitionRequest
    ): AtlasCommitResult = mutationMutex.withLock {
        val timestamp = now()
        val prepared = prepareAmendment(
            runId = request.runId,
            amendment = request.amendment,
            nextAtlas = request.nextAtlas,
            projections = request.projections,
            timestamp = timestamp
        )
        val database = databaseFactory.getAtlas(request.runId)
        val graderDao = database.graderAuthorityDao()
        val currentGraderEntity = graderDao.latestAtlasForRun(request.runId)
            ?: throw AtlasTransactionException(
                AtlasTransactionErrorCode.GRADER_ATLAS_MISMATCH,
                "Worker amendment requires an existing matched Grader Atlas"
            )
        val currentGrader = decode(
            GraderAtlas.serializer(),
            currentGraderEntity.contentJson
        )
        val graderErrors = GraderAtlasValidation.validateEvolution(
            currentGrader,
            request.nextGraderAtlas,
            prepared.parent,
            request.nextAtlas
        )
        if (graderErrors.isNotEmpty()) {
            throw AtlasTransactionException(
                AtlasTransactionErrorCode.GRADER_ATLAS_MISMATCH,
                graderErrors.joinToString()
            )
        }

        val lifecycleDao = database.outlierNoveltyDao()
        val lifecycleEvents = request.lifecycleEvents
        if (lifecycleEvents.size != 2 ||
            lifecycleEvents[0].toState != ShadowNodeState.TESTED ||
            lifecycleEvents[1].toState != ShadowNodeState.ACCEPTED_AMENDMENT ||
            lifecycleEvents[1].amendmentId != request.amendment.amendmentId
        ) {
            throw AtlasTransactionException(
                AtlasTransactionErrorCode.OUTLIER_LIFECYCLE_MISMATCH,
                "Accepted amendment transition requires ordered TESTED and ACCEPTED_AMENDMENT events"
            )
        }
        val previousLifecycleEntity = lifecycleDao.lifecycleForProposal(
            lifecycleEvents.first().proposalId
        ).lastOrNull() ?: throw AtlasTransactionException(
            AtlasTransactionErrorCode.OUTLIER_LIFECYCLE_MISMATCH,
            "Accepted amendment references an unknown Outlier lifecycle"
        )
        val previousLifecycle = decode(
            ShadowNodeLifecycleEvent.serializer(),
            previousLifecycleEntity.contentJson
        )
        var lifecycleCursor = previousLifecycle
        lifecycleEvents.forEach { event ->
            ShadowNodeLifecycleValidator.validateNext(lifecycleCursor, event)
            lifecycleCursor = event
        }
        if (previousLifecycle.toState != ShadowNodeState.SHADOW_NODE ||
            lifecycleEvents.any {
                it.round != request.amendment.effectiveRound - 1 ||
                    it.proposalId != request.fitnessRecord.proposalId ||
                    it.proposalHash != request.fitnessRecord.proposalHash ||
                    it.runId != request.runId
            } ||
            lifecycleEvents.first().evidenceArtifactIds.isEmpty()
        ) {
            throw AtlasTransactionException(
                AtlasTransactionErrorCode.OUTLIER_LIFECYCLE_MISMATCH,
                "Accepted amendment lifecycle does not match its round, proposal, or evidence"
            )
        }
        val fitness = request.fitnessRecord
        val proposal = lifecycleDao.proposalById(fitness.proposalId)?.let { entity ->
            decode(
                com.achulkov.evomolior.core.atlas.NoveltyVectorProposal.serializer(),
                entity.contentJson
            )
        } ?: throw AtlasTransactionException(
            AtlasTransactionErrorCode.OUTLIER_LIFECYCLE_MISMATCH,
            "Accepted amendment references an unknown Outlier proposal"
        )
        val lineageHashes = lifecycleDao.downstreamLineage(fitness.proposalId)
            .mapTo(linkedSetOf()) { entity ->
                decode(
                    com.achulkov.evomolior.core.atlas.OutlierDownstreamLineage.serializer(),
                    entity.contentJson
                ).lineageHash
            }
        if (!fitness.hasValidHash() ||
            proposal.runId != request.runId ||
            proposal.proposalHash != fitness.proposalHash ||
            proposal.proposerAgentId != fitness.agentId ||
            proposal.round != fitness.sourceRound ||
            fitness.agentId != request.amendment.proposedBy ||
            fitness.observationRound != request.amendment.effectiveRound - 1 ||
            !lineageHashes.containsAll(fitness.evidenceLineageHashes)
        ) {
            throw AtlasTransactionException(
                AtlasTransactionErrorCode.OUTLIER_LIFECYCLE_MISMATCH,
                "Outlier fitness does not match the accepted amendment authority"
            )
        }

        database.atlasDao().appendAmendmentAuthorityTransition(
            expectedWorkerAtlasHash = prepared.parent.atlasHash,
            expectedWorkerAtlasVersion = prepared.parent.version,
            expectedCommitSequence = prepared.previousCommitSequence,
            expectedGraderAtlasHash = currentGrader.atlasHash,
            expectedGraderAtlasVersion = currentGrader.version,
            expectedLifecycleEventHash = previousLifecycle.eventHash,
            commit = prepared.commit,
            version = prepared.version,
            amendment = prepared.amendment,
            stateEvents = prepared.stateEvents,
            projections = prepared.projections,
            wipTasks = prepared.wipTasks,
            graderAtlasVersion = GraderAtlasVersionEntity(
                graderAtlasId = request.nextGraderAtlas.graderAtlasId,
                runId = request.nextGraderAtlas.runId,
                version = request.nextGraderAtlas.version,
                workerAtlasHash = request.nextGraderAtlas.workerAtlasHash,
                atlasHash = request.nextGraderAtlas.atlasHash,
                contentJson = json.encodeToString(request.nextGraderAtlas),
                createdAtMs = timestamp
            ),
            lifecycleEvents = lifecycleEvents.map { event ->
                shadowNodeLifecycleEntity(event, timestamp)
            },
            fitnessRecord = OutlierFitnessRecordEntity(
                recordId = fitness.recordId,
                proposalId = fitness.proposalId,
                proposalHash = fitness.proposalHash,
                agentId = fitness.agentId.value,
                observationRound = fitness.observationRound,
                recordHash = fitness.recordHash,
                contentJson = json.encodeToString(fitness),
                createdAtMs = timestamp
            )
        )
        prepared.result
    }

    /**
     * Appends changed node execution states and all resulting projections.
     *
     * This is the only public write path for node ownership and disclosure reveal state.
     *
     * @param request Optimistically guarded execution mutation.
     * @return The committed authority state.
     */
    suspend fun commitExecution(request: AtlasExecutionCommitRequest): AtlasCommitResult {
        return commitExecution(request, captureDecision = null, yieldApplication = null)
    }

    /**
     * Atomically appends a valid capture decision and its exact execution-state mutation.
     *
     * @param request Capture decision plus optimistic Atlas execution mutation.
     * @return The committed authority state.
     */
    suspend fun commitCapture(request: AtlasCaptureCommitRequest): AtlasCommitResult {
        require(request.execution.mutationType == AtlasMutationType.CAPTURE.name) {
            "Capture commits must use the CAPTURE mutation type"
        }
        return commitExecution(
            request.execution,
            captureDecision = request.decision,
            yieldApplication = null
        )
    }

    /**
     * Atomically appends a due yield application and its exact execution-state history.
     *
     * @param request Yield application plus optimistic Atlas execution mutation.
     * @return The committed authority state.
     */
    suspend fun commitYield(request: AtlasYieldCommitRequest): AtlasCommitResult {
        require(request.execution.mutationType == AtlasMutationType.YIELD.name) {
            "Yield commits must use the YIELD mutation type"
        }
        return commitExecution(
            request.execution,
            captureDecision = null,
            yieldApplication = request.application
        )
    }

    private suspend fun commitExecution(
        request: AtlasExecutionCommitRequest,
        captureDecision: NodeCaptureDecision?,
        yieldApplication: NodeYieldApplication?
    ): AtlasCommitResult = mutationMutex.withLock {
        require(request.changedStates.isNotEmpty()) { "Execution commit must change at least one node" }
        val database = databaseFactory.getAtlas(request.runId)
        val dao = database.atlasDao()
        val atlasEntity = dao.latestVersion(request.atlasId)
            ?: throw AtlasTransactionException(
                AtlasTransactionErrorCode.NOT_INITIALIZED,
                "Atlas ${request.atlasId} is not initialized"
            )
        val atlas = decode(NodeAtlas.serializer(), atlasEntity.sealedAtlasJson)
        if (atlas.atlasHash != request.expectedAtlasHash) {
            throw AtlasTransactionException(
                AtlasTransactionErrorCode.STALE_ATLAS,
                "Expected Atlas hash ${request.expectedAtlasHash}, found ${atlas.atlasHash}"
            )
        }
        val currentEvents = dao.currentStates(request.atlasId)
        val currentStates = currentEvents.associate { event ->
            NodeId(event.nodeId) to decode(NodeExecutionState.serializer(), event.stateJson)
        }
        request.changedStates.forEach { state ->
            val currentEvent = currentEvents.firstOrNull { it.nodeId == state.nodeId.value }
                ?: throw AtlasTransactionException(
                    AtlasTransactionErrorCode.UNKNOWN_NODE,
                    "No execution state exists for ${state.nodeId.value}"
                )
            val expectedStateHash = request.expectedStateHashes[state.nodeId]
                ?: throw AtlasTransactionException(
                    AtlasTransactionErrorCode.MISSING_EXPECTED_STATE,
                    "Missing expected state hash for ${state.nodeId.value}"
                )
            if (currentEvent.stateHash != expectedStateHash) {
                throw AtlasTransactionException(
                    AtlasTransactionErrorCode.STALE_EXECUTION_STATE,
                    "Execution state changed for ${state.nodeId.value}"
                )
            }
        }
        val changedById = request.changedStates.associateBy(NodeExecutionState::nodeId)
        val nextStates = currentStates.mapValues { (nodeId, state) -> changedById[nodeId] ?: state }
        if (request.mutationType == AtlasMutationType.DISCLOSURE.name) {
            validateDisclosureMutation(atlas, currentStates, request.changedStates)
        } else if (request.mutationType in AtlasMutationType.entries.mapTo(hashSetOf()) { it.name }) {
            request.changedStates.forEach { next ->
                val previous = currentStates.getValue(next.nodeId)
                require(
                    next.revealedToGroups == previous.revealedToGroups &&
                        next.disclosureLineage == previous.disclosureLineage
                ) {
                    "Only DISCLOSURE transactions may mutate reveal state or lineage"
                }
            }
        }
        validateStateCoverage(atlas, nextStates.values)
        validateProjections(atlas, nextStates.values, request.projections)
        captureDecision?.let {
            validateCaptureDecision(
                decision = it,
                atlas = atlas,
                currentStates = currentStates,
                changedStates = request.changedStates,
                candidateBundlesExist = { bundleHash ->
                    database.nodeCaptureDao().gradeBundleByHash(bundleHash) != null
                }
            )
        }
        yieldApplication?.let {
            validateYieldApplication(
                application = it,
                currentStates = currentStates,
                changedStates = request.changedStates,
                scheduleByHash = { scheduleHash ->
                    database.nodeCaptureDao().yieldScheduleByHash(scheduleHash)
                        ?.let { entity ->
                            decode(NodeYieldSchedule.serializer(), entity.contentJson)
                        }
                },
                applicationExists = { scheduleId ->
                    database.nodeCaptureDao().yieldApplicationByScheduleId(scheduleId) != null
                }
            )
        }

        val latestCommit = requireNotNull(dao.latestCommit(request.atlasId))
        val commitSequence = latestCommit.commitSequence + 1
        val timestamp = now()
        val commitId = commitId(request.atlasId, commitSequence)
        val eventsByNode = currentEvents.associateBy { NodeId(it.nodeId) }
        val stateEvents = request.changedStates.map { state ->
            val previous = eventsByNode.getValue(state.nodeId)
            stateEvent(
                atlas = atlas,
                commitSequence = commitSequence,
                stateRevision = previous.stateRevision + 1,
                previousStateHash = previous.stateHash,
                mutationType = request.mutationType,
                state = state,
                timestamp = timestamp
            )
        }
        val statePlaneHash = AtlasHashing.hashExecutionStates(nextStates.values)
        val projectionEntities = projectionCodec.entities(
            atlas = atlas,
            commitSequence = commitSequence,
            stateHash = statePlaneHash,
            projections = request.projections,
            timestamp = timestamp
        )
        dao.appendTransaction(
            commit = AtlasCommitEntity(
                commitId = commitId,
                atlasId = atlas.atlasId,
                runId = atlas.runId,
                commitSequence = commitSequence,
                atlasVersion = atlas.version,
                atlasHash = atlas.atlasHash,
                mutationType = request.mutationType,
                createdAtMs = timestamp
            ),
            version = null,
            amendment = null,
            captureDecision = captureDecision?.let {
                captureDecisionEntity(it, timestamp)
            },
            yieldApplication = yieldApplication?.let {
                yieldApplicationEntity(it, timestamp)
            },
            stateEvents = stateEvents,
            projections = projectionEntities,
            wipTasks = projectionCodec.wipEntities(request.projections.wip)
        )
        return AtlasCommitResult(
            commitId = commitId,
            commitSequence = commitSequence,
            atlas = atlas,
            stateHashes = nextStates.mapValues { (_, state) -> AtlasHashing.hashExecutionState(state) },
            projectionCount = projectionEntities.size
        )
    }

    private fun validateDisclosureMutation(
        atlas: NodeAtlas,
        currentStates: Map<NodeId, NodeExecutionState>,
        changedStates: List<NodeExecutionState>
    ) {
        val appendedAuthorizations = linkedMapOf<String, com.achulkov.evomolior.core.atlas.DisclosureRevealAuthorization>()
        changedStates.forEach { next ->
            val previous = currentStates.getValue(next.nodeId)
            require(
                next.copy(
                    revealedToGroups = previous.revealedToGroups,
                    disclosureLineage = previous.disclosureLineage
                ) == previous
            ) {
                "Disclosure commits may only append reveal groups and disclosure lineage"
            }
            require(next.revealedToGroups.containsAll(previous.revealedToGroups)) {
                "Disclosure commits cannot revoke a group reveal"
            }
            require(
                next.disclosureLineage.take(previous.disclosureLineage.size) ==
                    previous.disclosureLineage
            ) {
                "Disclosure lineage is append-only"
            }
            next.disclosureLineage.drop(previous.disclosureLineage.size).forEach { entry ->
                val authorization = entry.authorization
                require(authorization.hasValidHash()) {
                    "Disclosure lineage contains an invalid authorization"
                }
                require(authorization.groupId in next.revealedToGroups) {
                    "Disclosure authorization group is not revealed on the node"
                }
                val stage = atlas.stageDefinitions.singleOrNull {
                    it.stageId == authorization.toStageId && next.nodeId in it.nodeIds
                }
                require(stage != null &&
                    AtlasHashing.constantTimeEquals(
                        stage.revealPayloadHash,
                        authorization.stagePayloadHash
                    )
                ) {
                    "Disclosure authorization does not match the node's sealed stage"
                }
                appendedAuthorizations[authorization.authorizationId] = authorization
            }
        }
        require(appendedAuthorizations.isNotEmpty()) {
            "Disclosure commits must append exact lineage"
        }
        appendedAuthorizations.values.forEach { authorization ->
            val targetStage = atlas.stageDefinitions.single { it.stageId == authorization.toStageId }
            val coveredNodes = changedStates.filter { state ->
                state.disclosureLineage.any {
                    it.authorization.authorizationId == authorization.authorizationId
                }
            }.mapTo(linkedSetOf(), NodeExecutionState::nodeId)
            require(coveredNodes == targetStage.nodeIds) {
                "Disclosure authorization must cover every node in its target stage exactly"
            }
            val replayedPayload = GroupRevealPayload(
                runId = atlas.runId,
                atlasId = atlas.atlasId,
                atlasVersion = atlas.version,
                atlasHash = atlas.atlasHash,
                groupId = authorization.groupId,
                round = authorization.round,
                stageId = targetStage.stageId,
                stageIndex = targetStage.stageIndex,
                stagePayloadHash = authorization.stagePayloadHash,
                revealedNodes = targetStage.nodeIds.sortedBy(NodeId::value)
                    .map { atlas.nodes.getValue(it).toRevealedNodeCard() }
            ).withComputedHash()
            require(replayedPayload.hasValidHashes(atlas) &&
                AtlasHashing.constantTimeEquals(
                    replayedPayload.payloadHash,
                    authorization.deliveredPayloadHash
                )
            ) {
                "Disclosure authorization delivered-payload commitment cannot be replayed"
            }
        }
    }

    private suspend fun validateYieldApplication(
        application: NodeYieldApplication,
        currentStates: Map<NodeId, NodeExecutionState>,
        changedStates: List<NodeExecutionState>,
        scheduleByHash: suspend (String) -> NodeYieldSchedule?,
        applicationExists: suspend (String) -> Boolean
    ) {
        require(application.hasValidHash()) { "Yield application hash is invalid" }
        val schedule = requireNotNull(scheduleByHash(application.scheduleHash)) {
            "Yield application references an unknown schedule"
        }
        require(!applicationExists(application.scheduleId)) {
            "Yield schedule ${application.scheduleId} has already been applied"
        }
        require(schedule.scheduleId == application.scheduleId &&
            schedule.runId == application.runId &&
            schedule.nodeId == application.nodeId &&
            schedule.groupId == application.groupId &&
            schedule.activationRound == application.round &&
            schedule.rewards == application.appliedRewards
        ) {
            "Yield application does not exactly match its due schedule"
        }
        require(changedStates.size == 1 && changedStates.single().nodeId == application.nodeId) {
            "A yield transaction must mutate exactly its scheduled node"
        }
        val previous = currentStates.getValue(application.nodeId)
        val next = changedStates.single()
        require(next.round == maxOf(previous.round, application.round) &&
            next.copy(yieldHistory = previous.yieldHistory) ==
            previous.copy(round = maxOf(previous.round, application.round))
        ) {
            "Yield application may only advance the round and append yield history"
        }
        val appended = next.yieldHistory.drop(previous.yieldHistory.size)
        require(next.yieldHistory.take(previous.yieldHistory.size) == previous.yieldHistory &&
            appended.isNotEmpty() &&
            appended.all { yield ->
                yield.groupId == application.groupId &&
                    yield.round == application.round &&
                    yield.scheduleId == schedule.scheduleId &&
                    yield.scheduleHash == schedule.scheduleHash &&
                    yield.captureDecisionHash == schedule.captureDecisionHash &&
                    yield.evidencePacketHash == schedule.evidencePacketHash &&
                    yield.scoreBundleHash == schedule.scoreBundleHash
            } &&
            appended.map(NodeYield::yieldId).distinct().size == appended.size
        ) {
            "Yield history does not preserve the exact schedule authority"
        }
    }

    private suspend fun validateCaptureDecision(
        decision: NodeCaptureDecision,
        atlas: NodeAtlas,
        currentStates: Map<NodeId, NodeExecutionState>,
        changedStates: List<NodeExecutionState>,
        candidateBundlesExist: suspend (String) -> Boolean
    ) {
        require(decision.hasValidHash()) { "Capture decision hash is invalid" }
        require(decision.runId == atlas.runId &&
            decision.atlasVersion == atlas.version &&
            decision.atlasHash == atlas.atlasHash
        ) {
            "Capture decision does not match the exact sealed Atlas"
        }
        val node = requireNotNull(atlas.nodes[decision.nodeId]) {
            "Capture decision references an unknown Atlas node"
        }
        require(node.payloadHash == decision.nodePayloadHash &&
            AtlasHashing.hashCapturePolicy(node.capturePolicy) == decision.capturePolicyHash
        ) {
            "Capture decision node or policy commitment is invalid"
        }
        require(changedStates.size == 1 && changedStates.single().nodeId == decision.nodeId) {
            "A capture transaction must mutate exactly its decided node"
        }
        val previous = currentStates.getValue(decision.nodeId)
        require(previous.ownerGroupId == decision.previousOwnerGroupId) {
            "Capture decision previous owner is stale"
        }
        val next = changedStates.single()
        require(next.ownerGroupId == decision.resultingOwnerGroupIds.singleOrNull() &&
            next.captureState == decision.resultingCaptureState
        ) {
            "Capture decision and resulting execution state disagree"
        }
        decision.candidateBundleHashes.forEach { bundleHash ->
            require(candidateBundlesExist(bundleHash)) {
                "Capture transaction references an unpersisted grade bundle $bundleHash"
            }
        }
        require(decision.authoritativeBundleHashes.all { bundleHash ->
            next.grades.any { grade -> grade.scoreBundleHash == bundleHash }
        }) {
            "Capture state omits an authoritative score bundle"
        }
    }

    private fun captureDecisionEntity(
        decision: NodeCaptureDecision,
        timestamp: Long
    ) = NodeCaptureDecisionEntity(
        decisionId = decision.decisionId,
        runId = decision.runId,
        round = decision.round,
        nodeId = decision.nodeId.value,
        atlasVersion = decision.atlasVersion,
        atlasHash = decision.atlasHash,
        nodePayloadHash = decision.nodePayloadHash,
        decisionType = decision.decisionType.name,
        resultingCaptureState = decision.resultingCaptureState.name,
        yieldEligible = decision.yieldEligible,
        decisionHash = decision.decisionHash,
        contentJson = json.encodeToString(decision),
        createdAtMs = timestamp
    )

    private fun yieldApplicationEntity(
        application: NodeYieldApplication,
        timestamp: Long
    ) = NodeYieldApplicationEntity(
        applicationId = application.applicationId,
        scheduleId = application.scheduleId,
        scheduleHash = application.scheduleHash,
        runId = application.runId,
        nodeId = application.nodeId.value,
        groupId = application.groupId.value,
        round = application.round,
        applicationHash = application.applicationHash,
        contentJson = json.encodeToString(application),
        createdAtMs = timestamp
    )

    /** Returns the latest sealed Atlas, or null when migration has not run. */
    suspend fun currentAtlas(runId: String, atlasId: String): NodeAtlas? =
        databaseFactory.getAtlas(runId).atlasDao().latestVersion(atlasId)
            ?.let { decode(NodeAtlas.serializer(), it.sealedAtlasJson) }

    /**
     * Returns the latest sealed worker Atlas for [runId], or null before Council initialization.
     *
     * A run has one worker `NodeAtlas`; the separately sealed grader Atlas is stored under its own
     * authority tables and cannot be returned by this lookup.
     */
    suspend fun currentAtlas(runId: String): NodeAtlas? =
        databaseFactory.getAtlas(runId).atlasDao().latestVersionForRun(runId)
            ?.let { decode(NodeAtlas.serializer(), it.sealedAtlasJson) }

    /** Returns the exact immutable receiver contract stored with the latest Atlas version. */
    suspend fun currentReceiverContract(runId: String, atlasId: String): ReceiverContract? =
        databaseFactory.getAtlas(runId).atlasDao().latestVersion(atlasId)
            ?.let { decode(ReceiverContract.serializer(), it.receiverContractJson) }

    /** Returns the current execution state keyed by Atlas node ID. */
    suspend fun currentStates(runId: String, atlasId: String): Map<NodeId, NodeExecutionState> =
        decodeStates(databaseFactory.getAtlas(runId).atlasDao().currentStates(atlasId))
            .associateBy(NodeExecutionState::nodeId)

    /** Returns every immutable sealed version in ascending order. */
    suspend fun versionHistory(runId: String, atlasId: String): List<NodeAtlas> =
        databaseFactory.getAtlas(runId).atlasDao().versionHistory(atlasId)
            .map { decode(NodeAtlas.serializer(), it.sealedAtlasJson) }

    /** Returns the complete projections emitted by the latest Atlas commit. */
    suspend fun currentProjections(runId: String, atlasId: String): AtlasProjectionSet? {
        val dao = databaseFactory.getAtlas(runId).atlasDao()
        val commit = dao.latestCommit(atlasId) ?: return null
        return projectionCodec.decode(dao.projectionsForCommit(atlasId, commit.commitSequence))
    }

    private suspend fun prepareAmendment(
        runId: String,
        amendment: AtlasAmendment,
        nextAtlas: NodeAtlas,
        projections: AtlasProjectionSet,
        timestamp: Long
    ): PreparedAmendmentCommit {
        if (amendment.graderDecision != AmendmentDecision.ACCEPTED) {
            throw AtlasTransactionException(
                AtlasTransactionErrorCode.AMENDMENT_NOT_ACCEPTED,
                "Only accepted amendments can create a sealed Atlas version"
            )
        }
        val dao = databaseFactory.getAtlas(runId).atlasDao()
        val parentEntity = dao.latestVersion(nextAtlas.atlasId)
            ?: throw AtlasTransactionException(
                AtlasTransactionErrorCode.NOT_INITIALIZED,
                "Atlas ${nextAtlas.atlasId} is not initialized"
            )
        val parent = decode(NodeAtlas.serializer(), parentEntity.sealedAtlasJson)
        nextAtlas.validate(parent).requireValid()
        if (nextAtlas.receiverContractHash != parent.receiverContractHash) {
            throw AtlasTransactionException(
                AtlasTransactionErrorCode.RECEIVER_CONTRACT_CHANGED,
                "Receiver contract hash is immutable across Atlas versions"
            )
        }
        if (amendment.parentAtlasHash != parent.atlasHash ||
            amendment.amendmentId !in nextAtlas.amendmentIds
        ) {
            throw AtlasTransactionException(
                AtlasTransactionErrorCode.INVALID_AMENDMENT_LINEAGE,
                "Amendment lineage does not match the sealed parent and child"
            )
        }
        require(
            nextAtlas.version == parent.version + 1 &&
                nextAtlas.amendmentIds == parent.amendmentIds + amendment.amendmentId
        ) {
            "Amendment must create exactly one next Atlas version"
        }
        require(parent.nodes.all { (nodeId, node) -> nextAtlas.nodes[nodeId] == node }) {
            "Amendments cannot mutate existing Atlas nodes or hidden payloads"
        }
        require(parent.rootNodeIds == nextAtlas.rootNodeIds) {
            "Amendments cannot replace the sealed Atlas roots"
        }
        val addedNodeIds = nextAtlas.nodes.keys - parent.nodes.keys
        require(
            addedNodeIds == amendment.addedNodes.mapTo(linkedSetOf()) { it.id } &&
                amendment.addedNodes.all { node ->
                    nextAtlas.nodes[node.id] == node &&
                        node.revealPolicy.mode ==
                        com.achulkov.evomolior.core.atlas.RevealMode.ACCEPTED_AMENDMENT &&
                        node.revealPolicy.minimumRound == amendment.effectiveRound
                }
        ) {
            "Amendment added-node authority does not match the child Atlas"
        }
        val currentStates = decodeStates(dao.currentStates(nextAtlas.atlasId))
        validateStateNodes(parent, currentStates)
        val addedStates = addedNodeIds.sortedBy(NodeId::value).map { nodeId ->
            val stage = nextAtlas.stageDefinitions.single { nodeId in it.nodeIds }
            NodeExecutionState(
                nodeId = nodeId,
                round = amendment.effectiveRound,
                revealedToGroups = projections.disclosure.groupStageIndices
                    .filterValues { stageIndex -> stageIndex >= stage.stageIndex }
                    .keys
                    .mapTo(linkedSetOf(), ::AtlasGroupId)
            )
        }
        val nextStates = currentStates + addedStates
        validateStateCoverage(nextAtlas, nextStates)
        validateProjections(nextAtlas, nextStates, projections)

        val latestCommit = requireNotNull(dao.latestCommit(nextAtlas.atlasId))
        val commitSequence = latestCommit.commitSequence + 1
        val commitId = commitId(nextAtlas.atlasId, commitSequence)
        val stateEvents = addedStates.map { state ->
            stateEvent(
                atlas = nextAtlas,
                commitSequence = commitSequence,
                stateRevision = 1L,
                previousStateHash = null,
                mutationType = AtlasMutationType.AMENDMENT.name,
                state = state,
                timestamp = timestamp
            )
        }
        val projectionEntities = projectionCodec.entities(
            atlas = nextAtlas,
            commitSequence = commitSequence,
            stateHash = AtlasHashing.hashExecutionStates(nextStates),
            projections = projections,
            timestamp = timestamp
        )
        return PreparedAmendmentCommit(
            parent = parent,
            previousCommitSequence = latestCommit.commitSequence,
            commit = AtlasCommitEntity(
                commitId = commitId,
                atlasId = nextAtlas.atlasId,
                runId = runId,
                commitSequence = commitSequence,
                atlasVersion = nextAtlas.version,
                atlasHash = nextAtlas.atlasHash,
                mutationType = AtlasMutationType.AMENDMENT.name,
                createdAtMs = timestamp
            ),
            version = versionEntity(
                atlas = nextAtlas,
                receiverContractJson = parentEntity.receiverContractJson,
                timestamp = timestamp
            ),
            amendment = AtlasAmendmentEntity(
                amendmentId = amendment.amendmentId,
                atlasId = nextAtlas.atlasId,
                runId = runId,
                fromVersion = parent.version,
                toVersion = nextAtlas.version,
                parentAtlasHash = parent.atlasHash,
                resultAtlasHash = nextAtlas.atlasHash,
                amendmentJson = json.encodeToString(amendment),
                createdAtMs = timestamp
            ),
            stateEvents = stateEvents,
            projections = projectionEntities,
            wipTasks = projectionCodec.wipEntities(projections.wip),
            result = AtlasCommitResult(
                commitId = commitId,
                commitSequence = commitSequence,
                atlas = nextAtlas,
                stateHashes = nextStates.associate {
                    it.nodeId to AtlasHashing.hashExecutionState(it)
                },
                projectionCount = projectionEntities.size
            )
        )
    }

    private fun validateStateCoverage(atlas: NodeAtlas, states: Collection<NodeExecutionState>) {
        val stateIds = states.map(NodeExecutionState::nodeId)
        if (stateIds.size != stateIds.distinct().size || stateIds.toSet() != atlas.nodes.keys) {
            throw AtlasTransactionException(
                AtlasTransactionErrorCode.INVALID_STATE_COVERAGE,
                "Execution state must contain exactly one snapshot for every Atlas node"
            )
        }
    }

    private fun validateStateNodes(atlas: NodeAtlas, states: Collection<NodeExecutionState>) {
        val unknown = states.map(NodeExecutionState::nodeId).filterNot(atlas.nodes::containsKey)
        if (unknown.isNotEmpty()) {
            throw AtlasTransactionException(
                AtlasTransactionErrorCode.UNKNOWN_NODE,
                "Execution state references unknown nodes: ${unknown.joinToString { it.value }}"
            )
        }
    }

    private fun validateProjections(
        atlas: NodeAtlas,
        states: Collection<NodeExecutionState>,
        projections: AtlasProjectionSet
    ) {
        val stateByNode = states.associateBy(NodeExecutionState::nodeId)
        val mappingKeys = projections.mappings.map { Triple(it.source, it.scopeId, it.sourceNodeId) }
        if (mappingKeys.size != mappingKeys.distinct().size) {
            throw AtlasTransactionException(
                AtlasTransactionErrorCode.INVALID_PROJECTION,
                "Every legacy source identity must map exactly once"
            )
        }
        projections.mappings.forEach { mapping ->
            if (mapping.atlasNodeId !in atlas.nodes) {
                throw AtlasTransactionException(
                    AtlasTransactionErrorCode.INVALID_PROJECTION,
                    "Projection maps ${mapping.sourceNodeId} to unknown node ${mapping.atlasNodeId.value}"
                )
            }
        }
        val mappingBySource = projections.mappings.associateBy {
            Triple(it.source, it.scopeId, it.sourceNodeId)
        }
        projections.wip.forEach { projection ->
            projection.tasks.forEach { task ->
                requireMapping(
                    mappingBySource,
                    LegacyNodeSource.WIP,
                    projection.groupId,
                    task.taskId
                )
            }
        }
        projections.deliverables.forEach { tree ->
            tree.nodes.forEach { node ->
                val atlasNodeId = requireMapping(
                    mappingBySource,
                    LegacyNodeSource.DELIVERABLE,
                    tree.groupId,
                    node.nodeId
                )
                val projectedGrades = node.gradingHistory.size
                val authoritativeGrades = stateByNode.getValue(atlasNodeId).grades
                    .count { it.groupId.value == tree.groupId }
                if (projectedGrades != authoritativeGrades) {
                    throw AtlasTransactionException(
                        AtlasTransactionErrorCode.INVALID_PROJECTION,
                        "Grade history drift for ${tree.groupId}/${node.nodeId}"
                    )
                }
            }
        }
        val resourceOwners = projections.resources.nodes.groupBy { resource ->
            requireMapping(
                mappingBySource,
                LegacyNodeSource.RESOURCE,
                atlas.runId,
                resource.resourceNodeId
            )
        }.mapValues { (_, resources) ->
            resources.mapNotNull { it.claimedByGroupId ?: it.floorGroupId }.toSet()
        }
        resourceOwners.forEach { (nodeId, owners) ->
            val state = stateByNode.getValue(nodeId)
            val valid = if (owners.size <= 1) {
                owners.singleOrNull() == state.ownerGroupId?.value
            } else {
                state.ownerGroupId == null &&
                    state.captureState == com.achulkov.evomolior.core.atlas.CaptureState.SHARED
            }
            if (!valid) {
                throw AtlasTransactionException(
                    AtlasTransactionErrorCode.INVALID_PROJECTION,
                    "Resource ownership drift for ${nodeId.value}"
                )
            }
        }
        atlas.nodes.keys.forEach { nodeId ->
            val stage = atlas.stageDefinitions.firstOrNull { nodeId in it.nodeIds }
                ?: throw AtlasTransactionException(
                    AtlasTransactionErrorCode.INVALID_PROJECTION,
                    "Node ${nodeId.value} has no disclosure stage"
                )
            val expectedGroups = projections.disclosure.groupStageIndices
                .filterValues { it >= stage.stageIndex }
                .keys
            val actualGroups = stateByNode.getValue(nodeId).revealedToGroups.map { it.value }.toSet()
            if (actualGroups != expectedGroups) {
                throw AtlasTransactionException(
                    AtlasTransactionErrorCode.INVALID_PROJECTION,
                    "Disclosure state drift for ${nodeId.value}"
                )
            }
        }
    }

    private fun requireMapping(
        mappings: Map<Triple<LegacyNodeSource, String, String>, LegacyNodeMapping>,
        source: LegacyNodeSource,
        scopeId: String,
        sourceNodeId: String
    ): NodeId = mappings[Triple(source, scopeId, sourceNodeId)]?.atlasNodeId
        ?: throw AtlasTransactionException(
            AtlasTransactionErrorCode.INVALID_PROJECTION,
            "Missing $source mapping for $scopeId/$sourceNodeId"
        )

    private fun stateEvent(
        atlas: NodeAtlas,
        commitSequence: Long,
        stateRevision: Long,
        previousStateHash: String?,
        mutationType: String,
        state: NodeExecutionState,
        timestamp: Long
    ): AtlasExecutionStateEventEntity {
        val stateHash = AtlasHashing.hashExecutionState(state)
        return AtlasExecutionStateEventEntity(
            eventId = "${atlas.atlasId}:${state.nodeId.value}:$stateRevision:$stateHash",
            atlasId = atlas.atlasId,
            runId = atlas.runId,
            nodeId = state.nodeId.value,
            stateRevision = stateRevision,
            commitSequence = commitSequence,
            previousStateHash = previousStateHash,
            stateHash = stateHash,
            stateJson = json.encodeToString(state),
            mutationType = mutationType,
            createdAtMs = timestamp
        )
    }

    private fun versionEntity(
        atlas: NodeAtlas,
        receiverContractJson: String,
        timestamp: Long
    ) = AtlasVersionEntity(
        atlasId = atlas.atlasId,
        runId = atlas.runId,
        version = atlas.version,
        receiverContractHash = atlas.receiverContractHash,
        receiverContractJson = receiverContractJson,
        atlasHash = atlas.atlasHash,
        parentAtlasHash = atlas.parentAtlasHash,
        sealedAtlasJson = json.encodeToString(atlas),
        createdAtMs = timestamp
    )

    private fun shadowNodeLifecycleEntity(
        event: ShadowNodeLifecycleEvent,
        timestamp: Long
    ) = ShadowNodeLifecycleEventEntity(
        eventId = event.eventId,
        proposalId = event.proposalId,
        proposalHash = event.proposalHash,
        runId = event.runId,
        sequence = event.sequence,
        toState = event.toState.name,
        round = event.round,
        eventHash = event.eventHash,
        contentJson = json.encodeToString(event),
        createdAtMs = timestamp
    )

    private fun decodeStates(events: List<AtlasExecutionStateEventEntity>): List<NodeExecutionState> =
        events.map { decode(NodeExecutionState.serializer(), it.stateJson) }

    private fun <T> decode(serializer: KSerializer<T>, payload: String): T =
        json.decodeFromString(serializer, payload)

    private fun commitId(atlasId: String, sequence: Long): String = "$atlasId:commit:$sequence"

    private data class PreparedAmendmentCommit(
        val parent: NodeAtlas,
        val previousCommitSequence: Long,
        val commit: AtlasCommitEntity,
        val version: AtlasVersionEntity,
        val amendment: AtlasAmendmentEntity,
        val stateEvents: List<AtlasExecutionStateEventEntity>,
        val projections: List<com.achulkov.evomolior.substrate.db.entity.AtlasProjectionEntity>,
        val wipTasks: List<WipTaskEntity>,
        val result: AtlasCommitResult
    )
}
