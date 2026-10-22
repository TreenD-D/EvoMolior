@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.substrate.worker

import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.ToolExecutionPurpose
import com.achulkov.evomolior.core.atlas.ToolExecutionResult
import com.achulkov.evomolior.core.atlas.ToolLifecyclePolicy
import com.achulkov.evomolior.core.atlas.ToolLifecycleState
import com.achulkov.evomolior.core.contract.AgentExitRecord
import com.achulkov.evomolior.core.contract.AgentLifecycleRegistration
import com.achulkov.evomolior.core.contract.AgentLifecycleDuty
import com.achulkov.evomolior.core.contract.AgentRuntimeStateEvent
import com.achulkov.evomolior.core.contract.AgentRuntimeTransitionIntent
import com.achulkov.evomolior.core.contract.CommitType
import com.achulkov.evomolior.core.contract.ContributionCommit
import com.achulkov.evomolior.core.contract.DegradedExecutionRecord
import com.achulkov.evomolior.core.contract.NodePortfolio
import com.achulkov.evomolior.core.contract.PersistExitResult
import com.achulkov.evomolior.core.contract.TtlCheckpointEvent
import com.achulkov.evomolior.core.contract.TtlCheckpointKind
import com.achulkov.evomolior.core.contract.UsefulExitSummary
import com.achulkov.evomolior.substrate.db.EvoMoliorDatabase
import com.achulkov.evomolior.substrate.db.entity.AgentExitRecordEntity
import com.achulkov.evomolior.substrate.db.entity.AgentLifecycleRegistrationEntity
import com.achulkov.evomolior.substrate.db.entity.AgentRuntimeStateEventEntity
import com.achulkov.evomolior.substrate.db.entity.ContributionCommitEntity
import com.achulkov.evomolior.substrate.db.entity.DegradedExecutionRecordEntity
import com.achulkov.evomolior.substrate.db.entity.NodePortfolioEntity
import com.achulkov.evomolior.substrate.db.entity.TtlCheckpointEventEntity
import com.achulkov.evomolior.substrate.tools.ToolInfrastructureRepository
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/** Validates and persists immutable worker execution records. */
class WorkerExecutionRepository(
    private val database: EvoMoliorDatabase,
    private val json: Json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    }
) {
    private val dao = database.workerExecutionDao()

    /** Persists one exact NodePortfolio idempotently while enforcing immutable identity. */
    suspend fun appendNodePortfolio(portfolio: NodePortfolio): NodePortfolio {
        require(portfolio.portfolioHash.isNotBlank() && portfolio.hasValidHash()) {
            "NodePortfolio portfolioHash must be present and match its exact content"
        }
        val encoded = encode(NodePortfolio.serializer(), portfolio)
        dao.nodePortfolioById(portfolio.portfolioId)?.let { existing ->
            val decoded = decode(NodePortfolio.serializer(), existing.contentJson)
            require(decoded == portfolio) { "NodePortfolio identity is immutable" }
            return decoded
        }
        dao.insertNodePortfolio(
            NodePortfolioEntity(
                portfolioId = portfolio.portfolioId,
                runId = portfolio.runId,
                groupId = portfolio.groupId.value,
                round = portfolio.round,
                portfolioHash = portfolio.portfolioHash,
                contentJson = encoded
            )
        )
        val persisted = requireNotNull(dao.nodePortfolioById(portfolio.portfolioId)) {
            "NodePortfolio append did not produce a persisted authority record"
        }
        val decoded = decode(NodePortfolio.serializer(), persisted.contentJson)
        require(decoded == portfolio) { "NodePortfolio identity is immutable" }
        return decoded
    }

    /** Returns exact NodePortfolios for one run and group in round and identity order. */
    suspend fun nodePortfolios(runId: String, groupId: String): List<NodePortfolio> =
        dao.nodePortfolios(runId, groupId)
            .map { decode(NodePortfolio.serializer(), it.contentJson) }

    /** Persists one immutable agent lifecycle registration idempotently. */
    suspend fun registerAgent(
        registration: AgentLifecycleRegistration
    ): AgentLifecycleRegistration {
        require(registration.hasValidHash()) {
            "Agent lifecycle registration hash does not match its content"
        }
        dao.registrations(registration.runId, registration.groupId.value)
            .firstOrNull { it.agentId == registration.agentId.value }
            ?.let { existing ->
                val decoded = decodeRegistration(existing)
                require(decoded == registration) {
                    "Agent lifecycle registration identity is immutable"
                }
                return decoded
            }
        dao.insertRegistration(
            AgentLifecycleRegistrationEntity(
                registrationId = registration.registrationId,
                runId = registration.runId,
                groupId = registration.groupId.value,
                agentId = registration.agentId.value,
                role = registration.role,
                generation = registration.generation,
                classification = registration.classification.name,
                duty = registration.duty.name,
                workspaceId = registration.workspaceId,
                workspaceSourceHash = registration.workspaceSourceHash,
                registrationHash = registration.registrationHash,
                contentJson = encode(AgentLifecycleRegistration.serializer(), registration),
                registeredAtMs = registration.registeredAt.toEpochMilliseconds()
            )
        )
        return registration
    }

    /** Returns all immutable lifecycle registrations for one group run. */
    suspend fun registrations(
        runId: String,
        groupId: String
    ): List<AgentLifecycleRegistration> =
        dao.registrations(runId, groupId).map(::decodeRegistration)

    /** Returns all durable artifact-owner registrations for one group run. */
    suspend fun artifactOwnerRegistrations(
        runId: String,
        groupId: String
    ): List<AgentLifecycleRegistration> =
        dao.artifactOwnerRegistrations(runId, groupId).map {
            decodeRegistration(it)
        }

    /** Returns one exact immutable lifecycle registration, when present. */
    suspend fun registration(
        runId: String,
        groupId: String,
        agentId: String
    ): AgentLifecycleRegistration? =
        dao.registrationForAgent(runId, groupId, agentId)?.let { entity ->
            decodeRegistration(entity)
        }

    /** Persists one deduplicated contribution commitment. */
    suspend fun commit(contribution: ContributionCommit): ContributionCommit {
        require(contribution.confidence in 0.0..1.0) { "Contribution confidence must be in [0, 1]" }
        require(contribution.changedStateSummary.isNotBlank()) {
            "Contribution changedStateSummary must not be blank"
        }
        val references = contribution.artifactIds + contribution.claimIds +
            contribution.citedArtifactIds +
            contribution.toolRunIds + contribution.testsRun +
            contribution.referencedNoveltyProposalIds
        if (contribution.commitType == CommitType.NO_CONTRIBUTION) {
            require(references.isEmpty()) { "NO_CONTRIBUTION cannot claim durable references" }
        } else {
            require(references.isNotEmpty() || contribution.evidenceSummary.isNotBlank()) {
                "A potentially useful contribution requires a durable reference or evidence summary"
            }
        }
        if (contribution.commitType == CommitType.REUSABLE_TOOL) {
            validateReusableToolAuthority(contribution)
        }
        val expectedDedupeHash = AtlasHashing.hashContributionDedupe(contribution)
        require(AtlasHashing.constantTimeEquals(contribution.dedupeHash, expectedDedupeHash)) {
            "Contribution dedupe hash does not match its semantic content"
        }
        dao.contributionByDedupeHash(
            contribution.runId,
            contribution.groupId.value,
            contribution.dedupeHash
        )?.let { existing ->
            return decode(ContributionCommit.serializer(), existing.contentJson)
        }
        dao.insertContribution(
            ContributionCommitEntity(
                commitId = contribution.commitId,
                runId = contribution.runId,
                groupId = contribution.groupId.value,
                agentId = contribution.agentId.value,
                nodeId = contribution.nodeId?.value,
                round = contribution.round,
                commitType = contribution.commitType.name,
                dedupeHash = contribution.dedupeHash,
                contentJson = encode(ContributionCommit.serializer(), contribution),
                committedAtMs = contribution.committedAt.toEpochMilliseconds()
            )
        )
        return contribution
    }

    private suspend fun validateReusableToolAuthority(contribution: ContributionCommit) {
        val toolRepository = ToolInfrastructureRepository(database)
        contribution.toolRunIds.forEach { executionId ->
            val execution = requireNotNull(toolRepository.execution(executionId)) {
                "REUSABLE_TOOL references unknown tool execution $executionId"
            }
            require(
                execution.hasValidHash() &&
                    execution.executionId == executionId &&
                    execution.runId == contribution.runId &&
                    execution.groupId == contribution.groupId &&
                    execution.agentId == contribution.agentId &&
                    execution.round == contribution.round &&
                    execution.nodeId == contribution.nodeId &&
                    execution.purpose in REUSABLE_TOOL_EXECUTION_PURPOSES &&
                    execution.result == ToolExecutionResult.SUCCEEDED &&
                    execution.failure == null &&
                    execution.outputHash != null &&
                    execution.outputJson != null
            ) {
                "REUSABLE_TOOL execution $executionId lacks exact successful contribution authority"
            }
            val lifecycle = toolRepository.lifecycle(execution.toolVersionId)
            val firstLifecycle = lifecycle.firstOrNull()
            require(
                firstLifecycle != null &&
                    ToolLifecyclePolicy.allows(null, firstLifecycle) &&
                    firstLifecycle.runId == execution.runId &&
                    firstLifecycle.opportunityId == execution.opportunityId &&
                    firstLifecycle.toolVersionId == execution.toolVersionId &&
                    firstLifecycle.groupId == execution.groupId &&
                    lifecycle.all { it.hasValidHash() } &&
                    lifecycle.zipWithNext().all { (previous, next) ->
                        ToolLifecyclePolicy.allows(previous, next)
                    } &&
                    lifecycle.any { it.state == ToolLifecycleState.PROMOTED }
            ) {
                "REUSABLE_TOOL execution $executionId lacks valid promoted lifecycle authority"
            }
            if (execution.purpose == ToolExecutionPurpose.AGENT_USE) {
                require(lifecycle.any { event ->
                    event.state == ToolLifecycleState.EXECUTED_BY_LATER_AGENT &&
                        event.contextNodeId == execution.nodeId &&
                        executionId in event.authorityReferenceIds
                }) {
                    "REUSABLE_TOOL execution $executionId lacks later-agent lifecycle authority"
                }
            }
        }
        if (contribution.artifactIds.isEmpty()) {
            require(contribution.toolRunIds.isNotEmpty()) {
                "REUSABLE_TOOL requires one accepted TOOL submission or a successful tool execution"
            }
            return
        }
        require(contribution.artifactIds.size == 1) {
            "REUSABLE_TOOL authoring must reference exactly one TOOL submission"
        }
        val artifactId = contribution.artifactIds.single()
        val submission = requireNotNull(database.stagingDao().getById(artifactId)) {
            "REUSABLE_TOOL references unknown staging submission $artifactId"
        }
        require(
            submission.runId == contribution.runId &&
                submission.groupId == contribution.groupId.value &&
                submission.agentId == contribution.agentId.value
        ) {
            "REUSABLE_TOOL cannot claim another scope or agent's staging submission"
        }
        require(submission.type == "TOOL") {
            "REUSABLE_TOOL must reference a TOOL submission"
        }
        require(submission.status in REUSABLE_TOOL_COMMITTABLE_STATUSES) {
            buildString {
                append("REUSABLE_TOOL cannot claim ")
                append(submission.status)
                append(" TOOL submission ")
                append(artifactId)
                submission.rejectionReason?.takeIf(String::isNotBlank)?.let { reason ->
                    append(": ")
                    append(reason)
                }
            }
        }
        require(submission.wipTaskRef == contribution.nodeId?.value) {
            "REUSABLE_TOOL submission must reference the committed Atlas node"
        }
        require(submission.generation == contribution.round) {
            "REUSABLE_TOOL submission generation must equal the contribution round"
        }
    }

    /** Returns immutable commitments submitted by one agent. */
    suspend fun contributionsForAgent(
        runId: String,
        groupId: String,
        agentId: String
    ): List<ContributionCommit> =
        dao.contributionsForAgent(runId, groupId, agentId)
            .map { decode(ContributionCommit.serializer(), it.contentJson) }

    /** Returns immutable commitments for one Atlas node in round order. */
    suspend fun contributionsForNode(
        runId: String,
        groupId: String,
        nodeId: String
    ): List<ContributionCommit> =
        dao.contributionsForNode(runId, groupId, nodeId)
            .map { decode(ContributionCommit.serializer(), it.contentJson) }

    /** Returns every immutable group contribution authored in one round. */
    suspend fun contributionsForRound(
        runId: String,
        groupId: String,
        round: Int
    ): List<ContributionCommit> =
        dao.contributionsForRound(runId, groupId, round)
            .map { decode(ContributionCommit.serializer(), it.contentJson) }

    /** Persists a degraded-execution record that cannot count as a positive contribution. */
    suspend fun recordDegradedExecution(record: DegradedExecutionRecord) {
        dao.insertDegradedExecution(
            DegradedExecutionRecordEntity(
                recordId = record.recordId,
                runId = record.runId,
                groupId = record.groupId.value,
                agentId = record.agentId.value,
                nodeId = record.nodeId?.value,
                round = record.round,
                reasonCode = record.reasonCode,
                contentJson = encode(DegradedExecutionRecord.serializer(), record),
                occurredAtMs = record.occurredAt.toEpochMilliseconds()
            )
        )
    }

    /** Persists an ordered TTL checkpoint once; duplicate checkpoint attempts are idempotent. */
    suspend fun recordCheckpoint(event: TtlCheckpointEvent): Boolean {
        val expectedFraction = CHECKPOINT_FRACTIONS.getValue(event.checkpoint)
        require(event.elapsedFraction == expectedFraction) {
            "${event.checkpoint} must use elapsed fraction $expectedFraction"
        }
        require(event.remainingBudget >= 0.0) { "TTL checkpoint budget cannot be negative" }
        return dao.insertCheckpoint(
            TtlCheckpointEventEntity(
                eventId = event.eventId,
                runId = event.runId,
                groupId = event.groupId.value,
                agentId = event.agentId.value,
                round = event.round,
                checkpoint = event.checkpoint.name,
                elapsedFraction = event.elapsedFraction,
                remainingBudget = event.remainingBudget,
                phase = event.phase.name,
                contentJson = encode(TtlCheckpointEvent.serializer(), event),
                occurredAtMs = event.occurredAt.toEpochMilliseconds()
            )
        ) != -1L
    }

    /** Returns persisted TTL checkpoints in elapsed-fraction order. */
    suspend fun checkpointsForAgent(
        runId: String,
        groupId: String,
        agentId: String
    ): List<TtlCheckpointEvent> =
        dao.checkpointsForAgent(runId, groupId, agentId)
            .map { decode(TtlCheckpointEvent.serializer(), it.contentJson) }

    /**
     * Persists one immutable final exit classification exactly once.
     *
     * A repeated write for the same agent is accepted only when its canonical hash and decoded
     * content both match. This makes recovery retries safe while surfacing conflicting exit
     * classifications that would otherwise be hidden by SQLite's unique-agent constraint.
     *
     * @param record Immutable exit authority to append.
     * @return Typed inserted, already-persisted, or immutable-conflict outcome.
     */
    suspend fun recordExit(record: AgentExitRecord): PersistExitResult {
        val attemptedHash = AtlasHashing.hashAgentExitRecord(record)
        val existing = dao.exitForAgent(
            runId = record.runId,
            groupId = record.groupId.value,
            agentId = record.agentId.value
        )
        if (existing != null) {
            return classifyExitPersistence(record, attemptedHash, existing)
        }

        val insertedRowId = dao.insertExit(
            AgentExitRecordEntity(
                exitId = record.exitId,
                runId = record.runId,
                groupId = record.groupId.value,
                agentId = record.agentId.value,
                nodeId = record.nodeId?.value,
                round = record.round,
                finalPhase = record.finalPhase.name,
                commitId = record.commitId,
                commitType = record.commitType?.name,
                acceptedContribution = record.acceptedContribution,
                acceptedStructuredDeadEnd = record.acceptedStructuredDeadEnd,
                acceptedNoveltyProposal = record.acceptedNoveltyProposal,
                degradedExecution = record.degradedExecution,
                wasKilled = record.wasKilled,
                contentJson = encode(AgentExitRecord.serializer(), record),
                exitedAtMs = record.exitedAt.toEpochMilliseconds()
            )
        )
        val persisted = requireNotNull(
            dao.exitForAgent(
                runId = record.runId,
                groupId = record.groupId.value,
                agentId = record.agentId.value
            )
        ) {
            "Agent exit append did not produce a persisted authority record"
        }
        val persistedRecord = decode(AgentExitRecord.serializer(), persisted.contentJson)
        val persistedHash = AtlasHashing.hashAgentExitRecord(persistedRecord)
        return if (
            insertedRowId != -1L &&
            persistedRecord == record &&
            AtlasHashing.constantTimeEquals(attemptedHash, persistedHash)
        ) {
            PersistExitResult.Inserted(attemptedHash)
        } else {
            classifyExitPersistence(record, attemptedHash, persisted)
        }
    }

    private fun classifyExitPersistence(
        attempted: AgentExitRecord,
        attemptedHash: String,
        persistedEntity: AgentExitRecordEntity
    ): PersistExitResult {
        val persisted = decode(AgentExitRecord.serializer(), persistedEntity.contentJson)
        val persistedHash = AtlasHashing.hashAgentExitRecord(persisted)
        return if (
            persisted == attempted &&
            AtlasHashing.constantTimeEquals(attemptedHash, persistedHash)
        ) {
            PersistExitResult.AlreadyPersisted(attemptedHash)
        } else {
            PersistExitResult.Conflict(
                exitRecordHash = attemptedHash,
                persistedExitRecordHash = persistedHash
            )
        }
    }

    /**
     * Appends one exact runtime-state transition while enforcing its immutable revision chain.
     *
     * Exact event retries are idempotent. Reusing an event identity or lifecycle revision for
     * different content fails fast because either mutation would fork the durable audit authority.
     *
     * @param event Committed runtime-state transition.
     * @return The exact persisted transition.
     */
    suspend fun appendRuntimeStateEvent(
        event: AgentRuntimeStateEvent
    ): AgentRuntimeStateEvent {
        require(event.hasValidHash()) {
            "Agent runtime-state event hash does not match its exact content"
        }
        dao.runtimeStateEventById(event.eventId)?.let { existing ->
            val decoded = decodeRuntimeStateEvent(existing)
            require(decoded == event) { "Agent runtime-state event identity is immutable" }
            return decoded
        }

        val events = runtimeStateEvents(
            event.runId,
            event.groupId.value,
            event.agentId.value,
            event.generation
        )
        val previous = events.lastOrNull()
        requireArtifactWorkspaceAuthority(event, previous?.state)
        if (previous == null) {
            requireMatchingRegistration(event)
            require(event.revision == 1L && event.previousStateHash == null) {
                "The first runtime-state event must be revision 1 with no previous state hash"
            }
        } else {
            require(event.revision == previous.revision + 1L) {
                "Agent runtime-state event revision must extend the latest revision exactly once"
            }
            require(event.state.phase >= previous.state.phase) {
                "Agent runtime-state phase cannot move backward"
            }
            require(
                AtlasHashing.constantTimeEquals(
                    requireNotNull(event.previousStateHash),
                    previous.state.stateHash
                )
            ) {
                "Agent runtime-state event previous hash must commit to the prior state"
            }
        }

        dao.insertRuntimeStateEvent(event.toEntity())
        val persistedById = dao.runtimeStateEventById(event.eventId)
        if (persistedById != null) {
            val decoded = decodeRuntimeStateEvent(persistedById)
            require(decoded == event) { "Agent runtime-state event identity is immutable" }
            return decoded
        }
        val persistedRevision = requireNotNull(
            dao.runtimeStateEventForRevision(
                event.runId,
                event.groupId.value,
                event.agentId.value,
                event.generation,
                event.revision
            )
        ) {
            "Runtime-state event append did not produce a persisted authority record"
        }
        val decoded = decodeRuntimeStateEvent(persistedRevision)
        require(decoded == event) { "Agent runtime-state event revision is immutable" }
        return decoded
    }

    /**
     * Returns and validates one registered lifecycle's exact transition chain.
     *
     * @param runId Owning run identity.
     * @param groupId Owning group identity.
     * @param agentId Registered agent identity.
     * @param generation Registered lifecycle generation.
     * @return Validated transitions in ascending revision order.
     */
    suspend fun runtimeStateEvents(
        runId: String,
        groupId: String,
        agentId: String,
        generation: Int
    ): List<AgentRuntimeStateEvent> {
        val events = dao.runtimeStateEvents(runId, groupId, agentId, generation)
            .map(::decodeRuntimeStateEvent)
        if (events.isNotEmpty()) {
            requireMatchingRegistration(events.first())
        }
        events.forEachIndexed { index, event ->
            require(
                event.runId == runId &&
                    event.groupId.value == groupId &&
                    event.agentId.value == agentId &&
                    event.generation == generation &&
                    event.revision == index.toLong() + 1L &&
                    event.hasValidHash()
            ) {
                "Persisted runtime-state event chain contains invalid identity, revision, or hash"
            }
            val expectedPreviousHash = events.getOrNull(index - 1)?.state?.stateHash
            require(event.previousStateHash == expectedPreviousHash) {
                "Persisted runtime-state event chain is not contiguous"
            }
            val previousPhase = events.getOrNull(index - 1)?.state?.phase
            require(previousPhase == null || event.state.phase >= previousPhase) {
                "Persisted runtime-state event phase moved backward"
            }
        }
        return events
    }

    /**
     * Returns actual artifact-workspace execution evidence from the runtime authority.
     *
     * Registration and Builder allocation are intentionally absent from this result until the
     * runtime cell appends its explicit workspace-use transition.
     *
     * @param runId Owning run identity.
     * @param groupId Owning group identity.
     * @param agentId Registered agent identity.
     * @param generation Registered lifecycle generation.
     * @return Typed workspace-use transitions in revision order.
     */
    suspend fun artifactOwnerExecutionEvidence(
        runId: String,
        groupId: String,
        agentId: String,
        generation: Int
    ): List<AgentRuntimeStateEvent> {
        val registration = requireNotNull(registration(runId, groupId, agentId)) {
            "Artifact-owner execution evidence requires a lifecycle registration"
        }
        require(registration.duty == AgentLifecycleDuty.ARTIFACT_OWNER) {
            "Artifact-owner execution evidence requires the ArtifactOwner duty"
        }
        require(
            registration.workspaceId != null &&
                registration.workspaceSourceHash != null
        ) {
            "Artifact-owner execution evidence requires a registered workspace identity"
        }
        return runtimeStateEvents(runId, groupId, agentId, generation)
            .filter {
                it.intent == AgentRuntimeTransitionIntent.MARK_ARTIFACT_WORKSPACE_USED &&
                    it.state.artifactWorkspaceStarted &&
                    it.state.artifactWorkspaceId == registration.workspaceId &&
                    it.state.artifactWorkspaceSourceHash == registration.workspaceSourceHash
            }
    }

    /** Returns actual artifact-workspace evidence for every artifact owner in one group run. */
    suspend fun artifactOwnerExecutionEvidence(
        runId: String,
        groupId: String
    ): List<AgentRuntimeStateEvent> =
        artifactOwnerRegistrations(runId, groupId).flatMap { registration ->
            artifactOwnerExecutionEvidence(
                runId = runId,
                groupId = groupId,
                agentId = registration.agentId.value,
                generation = registration.generation
            )
        }

    /** Alias for callers that use the shorter evidence terminology. */
    suspend fun artifactOwnerEvidence(
        runId: String,
        groupId: String,
        agentId: String,
        generation: Int
    ): List<AgentRuntimeStateEvent> =
        artifactOwnerExecutionEvidence(runId, groupId, agentId, generation)

    private suspend fun requireMatchingRegistration(event: AgentRuntimeStateEvent) {
        val entity = requireNotNull(
            dao.registrationForAgent(
                event.runId,
                event.groupId.value,
                event.agentId.value
            )
        ) {
            "Agent runtime-state events require a matching lifecycle registration"
        }
        val registration = decode(
            AgentLifecycleRegistration.serializer(),
            entity.contentJson
        )
        require(
            registration.hasValidHash() &&
                registration.runId == event.runId &&
                registration.groupId == event.groupId &&
                registration.agentId == event.agentId &&
                registration.generation == event.generation &&
                registration.runId == entity.runId &&
                registration.groupId.value == entity.groupId &&
                registration.agentId.value == entity.agentId &&
                registration.generation == entity.generation &&
                registration.registrationHash == entity.registrationHash &&
                registration.duty.name == entity.duty &&
                registration.workspaceId == entity.workspaceId &&
                registration.workspaceSourceHash == entity.workspaceSourceHash
        ) {
            "Agent runtime-state event does not match its immutable lifecycle registration"
        }
    }

    private suspend fun requireArtifactWorkspaceAuthority(
        event: AgentRuntimeStateEvent,
        previousState: com.achulkov.evomolior.core.contract.AgentRuntimeState?
    ) {
        val started = event.state.artifactWorkspaceStarted
        val marked = event.intent == AgentRuntimeTransitionIntent.MARK_ARTIFACT_WORKSPACE_USED
        if (started) {
            require(previousState?.artifactWorkspaceStarted == true || marked) {
                "Artifact workspace evidence cannot appear outside its typed introduction"
            }
            if (previousState?.artifactWorkspaceStarted == true) {
                require(
                    previousState.artifactWorkspaceId == event.state.artifactWorkspaceId &&
                        previousState.artifactWorkspaceSourceHash ==
                        event.state.artifactWorkspaceSourceHash
                ) {
                    "Artifact workspace evidence lineage cannot change after introduction"
                }
            }
        } else {
            require(!marked) {
                "Artifact workspace state must be introduced by its typed runtime transition"
            }
        }
        if (!marked) return
        require(previousState?.artifactWorkspaceStarted != true) {
            "Artifact workspace evidence can be introduced only once"
        }

        val registration = requireNotNull(
            registration(event.runId, event.groupId.value, event.agentId.value)
        ) {
            "Artifact workspace execution requires a lifecycle registration"
        }
        require(
            registration.duty == AgentLifecycleDuty.ARTIFACT_OWNER &&
                registration.role.equals("Builder", ignoreCase = true) &&
                registration.workspaceId != null &&
                registration.workspaceSourceHash != null &&
                event.state.artifactWorkspaceId == registration.workspaceId &&
                event.state.artifactWorkspaceSourceHash == registration.workspaceSourceHash
        ) {
            "Artifact workspace execution lacks matching Builder ArtifactOwner authority"
        }
    }

    private fun AgentRuntimeStateEvent.toEntity() = AgentRuntimeStateEventEntity(
        eventId = eventId,
        runId = runId,
        groupId = groupId.value,
        agentId = agentId.value,
        generation = generation,
        revision = revision,
        intent = intent.name,
        previousStateHash = previousStateHash,
        stateHash = state.stateHash,
        eventHash = eventHash,
        contentJson = encode(AgentRuntimeStateEvent.serializer(), this),
        occurredAtMs = occurredAt.toEpochMilliseconds()
    )

    private fun decodeRuntimeStateEvent(
        entity: AgentRuntimeStateEventEntity
    ): AgentRuntimeStateEvent {
        val event = decode(AgentRuntimeStateEvent.serializer(), entity.contentJson)
        require(
            event.eventId == entity.eventId &&
                event.runId == entity.runId &&
                event.groupId.value == entity.groupId &&
                event.agentId.value == entity.agentId &&
                event.generation == entity.generation &&
                event.revision == entity.revision &&
                event.intent.name == entity.intent &&
                event.previousStateHash == entity.previousStateHash &&
                event.state.stateHash == entity.stateHash &&
                event.eventHash == entity.eventHash &&
                event.occurredAt.toEpochMilliseconds() == entity.occurredAtMs &&
                event.state.hasValidHash() &&
                event.hasValidHash()
        ) {
            "Persisted runtime-state event columns do not match their committed content"
        }
        return event
    }

    private fun decodeRegistration(
        entity: AgentLifecycleRegistrationEntity
    ): AgentLifecycleRegistration {
        val registration = decode(
            AgentLifecycleRegistration.serializer(),
            entity.contentJson
        )
        require(
            registration.hasValidHash() &&
                registration.registrationId == entity.registrationId &&
                registration.runId == entity.runId &&
                registration.groupId.value == entity.groupId &&
                registration.agentId.value == entity.agentId &&
                registration.generation == entity.generation &&
                registration.classification.name == entity.classification &&
                registration.duty.name == entity.duty &&
                registration.workspaceId == entity.workspaceId &&
                registration.workspaceSourceHash == entity.workspaceSourceHash &&
                registration.registrationHash == entity.registrationHash
        ) {
            "Persisted lifecycle registration columns do not match committed content"
        }
        return registration
    }

    /** Returns immutable final exit records for publication accounting. */
    suspend fun exits(runId: String, groupId: String? = null): List<AgentExitRecord> =
        dao.exits(runId, groupId).map {
            decode(AgentExitRecord.serializer(), it.contentJson)
        }

    /** Reports useful ordinary exits for one run or group. */
    suspend fun usefulExitSummary(runId: String, groupId: String? = null): UsefulExitSummary {
        val exits = dao.exits(runId, groupId)
            .map { decode(AgentExitRecord.serializer(), it.contentJson) }
        return UsefulExitSummary(
            runId = runId,
            groupId = groupId?.let(::AtlasGroupId),
            usefulExitCount = exits.count(AgentExitRecord::useful),
            ordinaryExitCount = exits.size
        )
    }

    private fun <T> encode(serializer: KSerializer<T>, value: T): String =
        json.encodeToString(serializer, value)

    private fun <T> decode(serializer: KSerializer<T>, value: String): T =
        json.decodeFromString(serializer, value)

    private companion object {
        val REUSABLE_TOOL_COMMITTABLE_STATUSES = setOf("pending", "promoted")
        val REUSABLE_TOOL_EXECUTION_PURPOSES = setOf(
            ToolExecutionPurpose.VALIDATION_TEST,
            ToolExecutionPurpose.AGENT_USE
        )
        val CHECKPOINT_FRACTIONS = mapOf(
            TtlCheckpointKind.WORK_WARNING to 0.65,
            TtlCheckpointKind.COMMIT_SELECTION_REQUIRED to 0.80,
            TtlCheckpointKind.BROAD_EXPLORATION_DISABLED to 0.90,
            TtlCheckpointKind.DEATH_NOTE_GRACE to 0.95,
            TtlCheckpointKind.TERMINATED to 1.0
        )
    }
}
