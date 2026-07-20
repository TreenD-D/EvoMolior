@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.substrate.tools

import com.achulkov.evomolior.core.atlas.SimulationRunRecord
import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.ToolExecutionPurpose
import com.achulkov.evomolior.core.atlas.ToolExecutionRecord
import com.achulkov.evomolior.core.atlas.ToolExecutionResult
import com.achulkov.evomolior.core.atlas.ToolFitnessRecord
import com.achulkov.evomolior.core.atlas.ToolInfrastructureMetrics
import com.achulkov.evomolior.core.atlas.ToolMetricRatio
import com.achulkov.evomolior.core.atlas.ToolLifecycleEvent
import com.achulkov.evomolior.core.atlas.ToolLifecyclePolicy
import com.achulkov.evomolior.core.atlas.ToolLifecycleState
import com.achulkov.evomolior.core.atlas.ToolOpportunityCommitment
import com.achulkov.evomolior.core.atlas.ToolReuseLineage
import com.achulkov.evomolior.core.atlas.ToolValidationExecutionAuthority
import com.achulkov.evomolior.core.atlas.ToolVersionId
import com.achulkov.evomolior.substrate.db.EvoMoliorDatabase
import com.achulkov.evomolior.substrate.db.entity.SimulationRunRecordEntity
import com.achulkov.evomolior.substrate.db.entity.ToolExecutionRecordEntity
import com.achulkov.evomolior.substrate.db.entity.ToolFitnessRecordEntity
import com.achulkov.evomolior.substrate.db.entity.ToolLifecycleEventEntity
import com.achulkov.evomolior.substrate.db.entity.ToolOpportunityCommitmentEntity
import com.achulkov.evomolior.substrate.db.entity.ToolReuseLineageEntity
import com.achulkov.evomolior.substrate.db.entity.ToolRegistryEntryEntity
import kotlinx.serialization.KSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Instant

/**
 * Append-only persistence authority for Atlas-bound tool and simulation infrastructure.
 *
 * Registry rows remain executable materialization. This repository governs immutable opportunity,
 * lifecycle, execution, evidence lineage, and delayed-fitness history.
 */
class ToolInfrastructureRepository(
    database: EvoMoliorDatabase,
    private val json: Json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    },
    private val validationExecutionAuthorities: Set<ToolValidationExecutionAuthority> = emptySet(),
    private val now: () -> Long = System::currentTimeMillis
) {
    private val dao = database.toolInfrastructureDao()
    private val toolRegistryDao = database.toolRegistryDao()

    /** Persists exact Atlas provenance for a Council-authored opportunity. */
    suspend fun appendOpportunity(
        value: ToolOpportunityCommitment
    ): ToolOpportunityCommitment {
        require(value.hasValidHash()) { "Tool opportunity commitment hash is invalid" }
        dao.opportunity(value.opportunity.opportunityId)?.let { entity ->
            val existing = decode(ToolOpportunityCommitment.serializer(), entity.contentJson)
            require(existing == value) { "Tool opportunity identity is immutable" }
            return existing
        }
        dao.insertOpportunity(
            ToolOpportunityCommitmentEntity(
                opportunityId = value.opportunity.opportunityId,
                runId = value.runId,
                atlasHash = value.atlasHash,
                nodeId = value.nodeId.value,
                commitmentHash = value.commitmentHash,
                contentJson = encode(ToolOpportunityCommitment.serializer(), value),
                createdAtMs = now()
            )
        )
        return value
    }

    /** Returns one persisted Council opportunity commitment. */
    suspend fun opportunity(opportunityId: String): ToolOpportunityCommitment? =
        dao.opportunity(opportunityId)?.let {
            decode(ToolOpportunityCommitment.serializer(), it.contentJson)
        }

    /** Appends one legal, hash-linked lifecycle event. */
    suspend fun appendLifecycle(value: ToolLifecycleEvent): ToolLifecycleEvent {
        require(value.hasValidHash()) { "Tool lifecycle event hash is invalid" }
        dao.lifecycleEvent(value.eventId)?.let { entity ->
            val existing = decode(ToolLifecycleEvent.serializer(), entity.contentJson)
            require(existing == value) { "Tool lifecycle event identity is immutable" }
            return existing
        }
        val opportunity = requireNotNull(opportunity(value.opportunityId)) {
            "Tool lifecycle references an unknown opportunity"
        }
        require(
            opportunity.runId == value.runId &&
                opportunity.nodeId == value.originNodeId
        ) { "Tool lifecycle does not match its Atlas opportunity provenance" }
        val history = lifecycle(value.toolVersionId)
        require(ToolLifecyclePolicy.allows(history.lastOrNull(), value)) {
            "Illegal tool lifecycle transition to ${value.state}"
        }
        validateLifecycleAuthority(value)
        dao.insertLifecycle(
            ToolLifecycleEventEntity(
                eventId = value.eventId,
                runId = value.runId,
                opportunityId = value.opportunityId,
                toolVersionId = value.toolVersionId.value,
                groupId = value.groupId.value,
                originNodeId = value.originNodeId.value,
                contextNodeId = value.contextNodeId.value,
                state = value.state.name,
                sequence = value.sequence,
                eventHash = value.eventHash,
                contentJson = encode(ToolLifecycleEvent.serializer(), value),
                createdAtMs = now()
            )
        )
        return value
    }

    /** Returns one tool version's ordered lifecycle. */
    suspend fun lifecycle(toolVersionId: ToolVersionId): List<ToolLifecycleEvent> =
        dao.lifecycle(toolVersionId.value).map {
            decode(ToolLifecycleEvent.serializer(), it.contentJson)
        }

    /** Allocates the next immutable numeric version while preserving failed attempt histories. */
    suspend fun nextVersionNumber(toolName: String, latestRegistryVersion: Int?): Int {
        require(toolName.isNotBlank()) { "Tool name must not be blank" }
        val attemptedVersions = dao.lifecycleVersionCount("$toolName-v")
        return maxOf(latestRegistryVersion ?: 0, attemptedVersions) + 1
    }

    /**
     * Returns active registry versions carrying any lifecycle binding to one opportunity.
     *
     * Incomplete lifecycle rows are intentionally included so activation can fail closed rather
     * than treating malformed Atlas-bound registry state as an empty opportunity.
     */
    suspend fun activeVersionsForOpportunity(
        runId: String,
        groupId: String,
        opportunityId: String
    ): List<ToolRegistryEntryEntity> {
        require(runId.isNotBlank() && groupId.isNotBlank() && opportunityId.isNotBlank()) {
            "Opportunity-scoped active-version lookup requires exact non-blank identity"
        }
        return dao.activeVersionsForOpportunity(runId, groupId, opportunityId)
    }

    /**
     * Atomically activates one immutable registry version and appends its `PROMOTED` authority.
     *
     * @param event Legal next lifecycle event.
     * @param registryEntry Exact executable registry version sharing [event]'s identity.
     * @param expectedPreviousActiveVersionId Exact opportunity-scoped parent expected to remain
     * active when the transaction commits.
     * @param parentTransitionEvent Typed terminal lifecycle committed atomically for that parent.
     */
    suspend fun activatePromotedVersion(
        event: ToolLifecycleEvent,
        registryEntry: ToolRegistryEntryEntity,
        expectedPreviousActiveVersionId: String?,
        parentTransitionEvent: ToolLifecycleEvent? = null
    ): ToolLifecycleEvent {
        require(event.state == ToolLifecycleState.PROMOTED && event.hasValidHash()) {
            "Registry activation requires a valid PROMOTED lifecycle event"
        }
        require(
            registryEntry.id == event.toolVersionId.value &&
                registryEntry.runId == event.runId &&
                registryEntry.groupId == event.groupId.value &&
                registryEntry.status == "active" &&
                registryEntry.previousVersionId == expectedPreviousActiveVersionId
        ) { "Registry version does not match promoted lifecycle authority" }
        require(dao.lifecycleEvent(event.eventId) == null) {
            "Promoted lifecycle identity is already committed"
        }
        val history = lifecycle(event.toolVersionId)
        require(ToolLifecyclePolicy.allows(history.lastOrNull(), event)) {
            "Illegal PROMOTED lifecycle transition"
        }
        val opportunity = requireNotNull(opportunity(event.opportunityId)) {
            "Promoted version references an unknown opportunity"
        }
        require(
            opportunity.runId == event.runId &&
                opportunity.nodeId == event.originNodeId
        ) { "Promoted version does not match Atlas opportunity provenance" }
        val expectedPreviousParentEventHash = if (expectedPreviousActiveVersionId == null) {
            require(parentTransitionEvent == null) {
                "Initial activation cannot carry parent transition authority"
            }
            null
        } else {
            val transition = requireNotNull(parentTransitionEvent) {
                "Opportunity replacement requires typed parent lifecycle authority"
            }
            require(
                transition.state in setOf(
                    ToolLifecycleState.MAINTAINED,
                    ToolLifecycleState.FORKED
                ) &&
                    transition.hasValidHash() &&
                    transition.toolVersionId.value == expectedPreviousActiveVersionId &&
                    transition.runId == event.runId &&
                    transition.opportunityId == event.opportunityId &&
                    transition.groupId == event.groupId &&
                    transition.originNodeId == event.originNodeId &&
                    transition.transitionDetails?.childToolVersionId == event.toolVersionId
            ) { "Parent transition does not match promoted child authority" }
            val parentHistory = lifecycle(transition.toolVersionId)
            val previousParentEvent = requireNotNull(parentHistory.lastOrNull()) {
                "Opportunity replacement references an unknown parent lifecycle"
            }
            require(ToolLifecyclePolicy.allows(previousParentEvent, transition)) {
                "Illegal parent lifecycle transition during opportunity replacement"
            }
            previousParentEvent.eventHash
        }
        dao.activatePromotedVersion(
            opportunityId = event.opportunityId,
            expectedPreviousActiveVersionId = expectedPreviousActiveVersionId,
            expectedPreviousParentEventHash = expectedPreviousParentEventHash,
            nextVersion = registryEntry,
            promotedEvent = event.toEntity(now()),
            parentTransitionEvent = parentTransitionEvent?.toEntity(now())
        )
        return event
    }

    /** Atomically retires an active registry version while preserving all immutable history. */
    suspend fun retireVersion(event: ToolLifecycleEvent): ToolLifecycleEvent {
        require(
            event.state == ToolLifecycleState.RETIRED &&
                event.transitionDetails?.childToolVersionId == null &&
                event.hasValidHash()
        ) { "Registry retirement requires valid typed RETIRED authority" }
        val history = lifecycle(event.toolVersionId)
        val previous = requireNotNull(history.lastOrNull()) {
            "Retirement references an unknown tool version"
        }
        require(ToolLifecyclePolicy.allows(previous, event)) {
            "Illegal RETIRED lifecycle transition"
        }
        val opportunity = requireNotNull(opportunity(event.opportunityId)) {
            "Retirement references an unknown opportunity"
        }
        require(
            opportunity.runId == event.runId &&
                opportunity.nodeId == event.originNodeId
        ) { "Retirement does not match Atlas opportunity provenance" }
        dao.archiveVersionWithLifecycle(
            toolVersionId = event.toolVersionId.value,
            lifecycleEvent = event.toEntity(now()),
            expectedPreviousEventHash = previous.eventHash,
            expectedState = ToolLifecycleState.RETIRED.name
        )
        return event
    }

    /** Atomically archives a fork parent while preserving all immutable prior history. */
    suspend fun forkVersion(event: ToolLifecycleEvent): ToolLifecycleEvent {
        require(
            event.state == ToolLifecycleState.FORKED &&
                event.transitionDetails?.childToolVersionId != null &&
                event.hasValidHash()
        ) { "Registry fork requires valid typed FORKED authority" }
        val history = lifecycle(event.toolVersionId)
        val previous = requireNotNull(history.lastOrNull()) {
            "Fork references an unknown tool version"
        }
        require(ToolLifecyclePolicy.allows(previous, event)) {
            "Illegal FORKED lifecycle transition"
        }
        dao.archiveVersionWithLifecycle(
            toolVersionId = event.toolVersionId.value,
            lifecycleEvent = event.toEntity(now()),
            expectedPreviousEventHash = previous.eventHash,
            expectedState = ToolLifecycleState.FORKED.name
        )
        return event
    }

    /**
     * Persists one execution generated by the bounded boundary.
     *
     * Promotion tests require a statically validated version. Worker use requires a promoted
     * version; caller-supplied free-form run IDs never establish either condition.
     */
    suspend fun appendExecution(value: ToolExecutionRecord): ToolExecutionRecord {
        require(value.hasValidHash()) { "Tool execution record hash is invalid" }
        dao.execution(value.executionId)?.let { entity ->
            val existing = decode(ToolExecutionRecord.serializer(), entity.contentJson)
            require(existing == value) { "Tool execution identity is immutable" }
            return existing
        }
        validateExecutionAuthority(value, lifecycle(value.toolVersionId))
        dao.insertExecution(value.toEntity(now()))
        return value
    }

    /**
     * Atomically persists one successful worker execution and its later-use lifecycle transition.
     *
     * Concurrent executions optimistically retry against the latest hash-linked lifecycle tail;
     * no successful execution row can survive without its matching lifecycle event.
     */
    suspend fun appendSuccessfulAgentExecution(
        value: ToolExecutionRecord,
        lifecycleEventId: String,
        recordedAt: Instant
    ): ToolLifecycleEvent {
        require(value.hasValidHash() && value.result == ToolExecutionResult.SUCCEEDED &&
            value.purpose == ToolExecutionPurpose.AGENT_USE
        ) { "Atomic successful execution requires valid worker-use authority" }
        execution(value.executionId)?.let { existing ->
            require(existing == value) { "Tool execution identity is immutable" }
            return requireNotNull(
                lifecycle(value.toolVersionId).firstOrNull {
                    value.executionId in it.authorityReferenceIds
                }
            ) { "Existing successful execution lacks lifecycle authority" }
        }

        var lastFailure: Exception? = null
        repeat(ATOMIC_EXECUTION_RETRIES) {
            val history = lifecycle(value.toolVersionId)
            validateExecutionAuthority(value, history)
            val previous = requireNotNull(history.lastOrNull()) {
                "Successful execution lacks promoted lifecycle authority"
            }
            val event = ToolLifecycleEvent(
                eventId = lifecycleEventId,
                runId = value.runId,
                opportunityId = value.opportunityId,
                toolVersionId = value.toolVersionId,
                groupId = value.groupId,
                originNodeId = previous.originNodeId,
                contextNodeId = value.nodeId,
                state = ToolLifecycleState.EXECUTED_BY_LATER_AGENT,
                sequence = previous.sequence + 1,
                previousEventHash = previous.eventHash,
                authorityReferenceIds = setOf(value.executionId),
                recordedAt = recordedAt
            ).withComputedHash()
            require(ToolLifecyclePolicy.allows(previous, event)) {
                "Illegal later-agent lifecycle transition"
            }
            try {
                dao.insertSuccessfulExecutionAndLifecycle(
                    execution = value.toEntity(now()),
                    lifecycleEvent = event.toEntity(now()),
                    expectedPreviousEventHash = previous.eventHash
                )
                return event
            } catch (failure: Exception) {
                lastFailure = failure
                execution(value.executionId)?.let { existing ->
                    require(existing == value) { "Tool execution identity is immutable" }
                    return requireNotNull(
                        lifecycle(value.toolVersionId).firstOrNull {
                            value.executionId in it.authorityReferenceIds
                        }
                    ) { "Existing successful execution lacks lifecycle authority" }
                }
            }
        }
        throw IllegalStateException(
            "Successful execution could not commit against concurrent lifecycle updates",
            lastFailure
        )
    }

    /** Returns one exact persisted execution. */
    suspend fun execution(executionId: String): ToolExecutionRecord? =
        dao.execution(executionId)?.let {
            decode(ToolExecutionRecord.serializer(), it.contentJson)
        }

    /** Returns all exact executions for an immutable version. */
    suspend fun executions(toolVersionId: ToolVersionId): List<ToolExecutionRecord> =
        dao.executions(toolVersionId.value).map {
            decode(ToolExecutionRecord.serializer(), it.contentJson)
        }

    /** Returns exact bounded executions available to one group through an evaluation round. */
    suspend fun executionsForGroupThroughRound(
        runId: String,
        groupId: String,
        maximumRound: Int
    ): List<ToolExecutionRecord> {
        require(runId.isNotBlank() && groupId.isNotBlank() && maximumRound >= 0) {
            "Group execution lookup requires exact identity and a non-negative round"
        }
        return dao.executionsForGroupThroughRound(runId, groupId, maximumRound).map {
            decode(ToolExecutionRecord.serializer(), it.contentJson)
        }
    }

    /** Returns exact executions produced by one agent for one assigned node and round. */
    suspend fun executionsForAgentNode(
        runId: String,
        groupId: String,
        agentId: String,
        round: Int,
        nodeId: String
    ): List<ToolExecutionRecord> =
        dao.executionsForAgentNode(runId, groupId, agentId, round, nodeId).map {
            decode(ToolExecutionRecord.serializer(), it.contentJson)
        }

    /** Persists simulation authority only when backed by a matching successful execution. */
    suspend fun appendSimulation(value: SimulationRunRecord): SimulationRunRecord {
        require(value.hasValidHash()) { "Simulation record hash is invalid" }
        dao.simulation(value.simulationRunId)?.let { entity ->
            val existing = decode(SimulationRunRecord.serializer(), entity.contentJson)
            require(existing == value) { "Simulation run identity is immutable" }
            return existing
        }
        val execution = requireNotNull(execution(value.executionId)) {
            "Simulation references an unknown execution"
        }
        require(
            execution.result == ToolExecutionResult.SUCCEEDED &&
                execution.toolVersionId == value.toolVersionId &&
                execution.inputHash == value.inputHash &&
                execution.outputArtifactId == value.outputArtifactId &&
                execution.agentId == value.executedBy
        ) { "Simulation authority does not match its successful execution" }
        dao.insertSimulation(
            SimulationRunRecordEntity(
                simulationRunId = value.simulationRunId,
                executionId = value.executionId,
                toolVersionId = value.toolVersionId.value,
                recordHash = value.recordHash,
                contentJson = encode(SimulationRunRecord.serializer(), value),
                createdAtMs = now()
            )
        )
        return value
    }

    /** Returns one immutable simulation record by its boundary-generated identity. */
    suspend fun simulation(simulationRunId: String): SimulationRunRecord? =
        dao.simulation(simulationRunId)?.let {
            decode(SimulationRunRecord.serializer(), it.contentJson)
        }

    /** Returns all immutable simulation records for one run in execution order. */
    suspend fun simulationsForRun(runId: String): List<SimulationRunRecord> =
        dao.simulationsForRun(runId).map {
            decode(SimulationRunRecord.serializer(), it.contentJson)
        }

    /** Persists exact later-node reuse backed by one successful worker execution. */
    suspend fun appendReuse(value: ToolReuseLineage): ToolReuseLineage {
        require(value.hasValidHash()) { "Tool reuse lineage hash is invalid" }
        dao.reuse(value.lineageId)?.let { entity ->
            val existing = decode(ToolReuseLineage.serializer(), entity.contentJson)
            require(existing == value) { "Tool reuse lineage identity is immutable" }
            return existing
        }
        validateReuseAuthority(value, lifecycle(value.toolVersionId))
        dao.insertReuse(
            value.toEntity(now())
        )
        return value
    }

    /** Atomically appends evidence-bound reuse and its result-reference lifecycle transition. */
    suspend fun appendReuseAndReference(
        value: ToolReuseLineage,
        lifecycleEventId: String,
        recordedAt: Instant
    ): ToolLifecycleEvent {
        require(value.hasValidHash()) { "Tool reuse lineage hash is invalid" }
        dao.reuse(value.lineageId)?.let { entity ->
            val existing = decode(ToolReuseLineage.serializer(), entity.contentJson)
            require(existing == value) { "Tool reuse lineage identity is immutable" }
            return requireNotNull(
                lifecycle(value.toolVersionId).firstOrNull {
                    value.lineageId in it.authorityReferenceIds
                }
            ) { "Existing tool reuse lacks result-reference lifecycle authority" }
        }

        var lastFailure: Exception? = null
        val execution = requireNotNull(execution(value.executionId)) {
            "Tool reuse references an unknown execution"
        }
        repeat(ATOMIC_EXECUTION_RETRIES) {
            val history = lifecycle(value.toolVersionId)
            validateReuseAuthority(value, history)
            val previous = requireNotNull(history.lastOrNull()) {
                "Tool reuse lacks lifecycle authority"
            }
            val event = ToolLifecycleEvent(
                eventId = lifecycleEventId,
                runId = value.runId,
                opportunityId = execution.opportunityId,
                toolVersionId = value.toolVersionId,
                groupId = execution.groupId,
                originNodeId = value.originNodeId,
                contextNodeId = value.reusedOnNodeId,
                state = ToolLifecycleState.RESULT_REFERENCED_BY_NODE,
                sequence = previous.sequence + 1,
                previousEventHash = previous.eventHash,
                authorityReferenceIds = setOf(value.lineageId),
                recordedAt = recordedAt
            ).withComputedHash()
            require(ToolLifecyclePolicy.allows(previous, event)) {
                "Illegal result-reference lifecycle transition"
            }
            try {
                dao.insertReuseAndLifecycle(
                    reuse = value.toEntity(now()),
                    lifecycleEvent = event.toEntity(now()),
                    expectedPreviousEventHash = previous.eventHash
                )
                return event
            } catch (failure: Exception) {
                lastFailure = failure
                dao.reuse(value.lineageId)?.let { entity ->
                    val existing = decode(ToolReuseLineage.serializer(), entity.contentJson)
                    require(existing == value) { "Tool reuse lineage identity is immutable" }
                    return requireNotNull(
                        lifecycle(value.toolVersionId).firstOrNull {
                            value.lineageId in it.authorityReferenceIds
                        }
                    ) { "Existing tool reuse lacks result-reference lifecycle authority" }
                }
            }
        }
        throw IllegalStateException(
            "Tool reuse could not commit against concurrent lifecycle updates",
            lastFailure
        )
    }

    /** Returns all evidence-bound reuse authority for an immutable version. */
    suspend fun reuseForVersion(toolVersionId: ToolVersionId): List<ToolReuseLineage> =
        dao.reuseForVersion(toolVersionId.value).map {
            decode(ToolReuseLineage.serializer(), it.contentJson)
        }

    /** Persists delayed fitness only when every execution and reuse component is available. */
    suspend fun appendFitness(value: ToolFitnessRecord): ToolFitnessRecord {
        require(value.hasValidHash()) { "Tool fitness record hash is invalid" }
        dao.fitness(value.recordId)?.let { entity ->
            val existing = decode(ToolFitnessRecord.serializer(), entity.contentJson)
            require(existing == value) { "Tool fitness record identity is immutable" }
            return existing
        }
        val executions = executions(value.toolVersionId).associateBy { it.executionId }
        val reuse = reuseForVersion(value.toolVersionId).associateBy { it.lineageId }
        val selectedReuse = value.reuseLineageIds.map(reuse::getValue)
        require(value.successfulExecutionIds.all { id ->
            executions[id]?.result == ToolExecutionResult.SUCCEEDED
        }) { "Tool fitness references unavailable successful executions" }
        require(value.failureExecutionIds.all { id ->
            executions[id]?.result == ToolExecutionResult.FAILED
        }) { "Tool fitness references unavailable failed executions" }
        require(value.reuseLineageIds.all(reuse::containsKey)) {
            "Tool fitness references unavailable downstream reuse"
        }
        val authoritativeAgents = value.reuseLineageIds
            .mapTo(linkedSetOf()) { reuse.getValue(it).reusedByAgentId }
        require(authoritativeAgents == value.uniqueLaterAgentIds) {
            "Tool fitness later-agent count does not match reuse lineage"
        }
        require(
            value.validationPasses == executions.values.count {
                it.purpose in VALIDATION_EXECUTION_PURPOSES &&
                    it.result == ToolExecutionResult.SUCCEEDED &&
                    it.round <= value.throughRound
            } &&
                value.nodeScoreDeltaAttributedToResults ==
                selectedReuse.sumOf { it.nodeScoreAfter - it.nodeScoreBefore } &&
                value.avoidedRepeatedCost == selectedReuse.sumOf(ToolReuseLineage::avoidedRepeatedCost)
        ) { "Tool fitness aggregate components do not match exact authority" }
        dao.insertFitness(
            ToolFitnessRecordEntity(
                recordId = value.recordId,
                runId = value.runId,
                toolVersionId = value.toolVersionId.value,
                throughRound = value.throughRound,
                recordHash = value.recordHash,
                contentJson = encode(ToolFitnessRecord.serializer(), value),
                createdAtMs = now()
            )
        )
        return value
    }

    /**
     * Derives and appends full tool fitness from exact executions and evidence-bound reuse.
     *
     * Returns null before downstream reuse, preserving delayed-fitness semantics.
     */
    suspend fun deriveAndAppendFitness(
        toolVersionId: ToolVersionId,
        throughRound: Int
    ): ToolFitnessRecord? {
        require(throughRound >= 0) { "Tool fitness derivation round is invalid" }
        val history = lifecycle(toolVersionId)
        val first = requireNotNull(history.firstOrNull()) {
            "Tool fitness references an unknown version"
        }
        val executions = executions(toolVersionId).filter { it.round <= throughRound }
        val reuse = reuseForVersion(toolVersionId).filter { lineage ->
            executions.any { it.executionId == lineage.executionId }
        }
        if (reuse.isEmpty()) return null
        val successful = executions.filter {
            it.purpose == ToolExecutionPurpose.AGENT_USE &&
                it.result == ToolExecutionResult.SUCCEEDED
        }.mapTo(linkedSetOf(), ToolExecutionRecord::executionId)
        val failures = executions.filter {
            it.result == ToolExecutionResult.FAILED
        }.mapTo(linkedSetOf(), ToolExecutionRecord::executionId)
        val validationPasses = executions.count {
            it.purpose in VALIDATION_EXECUTION_PURPOSES &&
                it.result == ToolExecutionResult.SUCCEEDED
        }
        val scoreDelta = reuse.sumOf { it.nodeScoreAfter - it.nodeScoreBefore }
        val avoidedCost = reuse.sumOf(ToolReuseLineage::avoidedRepeatedCost)
        val maintenanceCost = history.sumOf {
            it.transitionDetails?.maintenanceCost ?: 0.0
        }
        val record = ToolFitnessRecord(
            recordId = "tool-fitness:${toolVersionId.value}:$throughRound",
            runId = first.runId,
            toolVersionId = toolVersionId,
            throughRound = throughRound,
            validationPasses = validationPasses,
            uniqueLaterAgentIds = reuse.mapTo(linkedSetOf(), ToolReuseLineage::reusedByAgentId),
            successfulExecutionIds = successful,
            reuseLineageIds = reuse.mapTo(linkedSetOf(), ToolReuseLineage::lineageId),
            nodeScoreDeltaAttributedToResults = scoreDelta,
            avoidedRepeatedCost = avoidedCost,
            failureExecutionIds = failures,
            maintenanceCost = maintenanceCost,
            fitnessValue = validationPasses +
                reuse.map(ToolReuseLineage::reusedByAgentId).distinct().size +
                successful.size +
                scoreDelta +
                avoidedCost -
                failures.size -
                maintenanceCost
        ).withComputedHash()
        return appendFitness(record)
    }

    /** Returns delayed-fitness history for an immutable version. */
    suspend fun fitnessForVersion(toolVersionId: ToolVersionId): List<ToolFitnessRecord> =
        dao.fitnessForVersion(toolVersionId.value).map {
            decode(ToolFitnessRecord.serializer(), it.contentJson)
        }

    /** Computes read-only exact metrics without granting mutation authority to dashboards. */
    suspend fun metrics(runId: String, groupId: AtlasGroupId): ToolInfrastructureMetrics {
        val attempts = dao.lifecycleStateCount(runId, ToolLifecycleState.TOOL_SPEC.name)
        val promoted = dao.lifecycleStateCount(runId, ToolLifecycleState.PROMOTED.name)
        val laterTotal = dao.executionCount(
            runId,
            ToolExecutionPurpose.AGENT_USE.name,
            result = null
        )
        val laterSuccess = dao.executionCount(
            runId,
            ToolExecutionPurpose.AGENT_USE.name,
            ToolExecutionResult.SUCCEEDED.name
        )
        val laterFailure = dao.executionCount(
            runId,
            ToolExecutionPurpose.AGENT_USE.name,
            ToolExecutionResult.FAILED.name
        )
        val promotionFailures = dao.executionCount(
            runId,
            ToolExecutionPurpose.PROMOTION_TEST.name,
            ToolExecutionResult.FAILED.name
        )
        val simulations = dao.simulationCount(runId)
        val reuse = dao.reuseCount(runId)
        val simulationReuse = dao.simulationReuseCount(runId)
        return ToolInfrastructureMetrics(
            runId = runId,
            groupId = groupId,
            opportunityCount = dao.opportunityCount(runId),
            promotionAttemptCount = attempts,
            promotedVersionCount = promoted,
            failedPromotionExecutionCount = promotionFailures,
            laterExecutionCount = laterTotal,
            successfulLaterExecutionCount = laterSuccess,
            failedLaterExecutionCount = laterFailure,
            simulationRunCount = simulations,
            evidenceBoundReuseCount = reuse,
            evidenceBoundSimulationReuseCount = simulationReuse,
            delayedFitnessVersionCount = dao.fitnessVersionCount(runId),
            promotionSuccessRate = ToolMetricRatio.of(promoted, attempts),
            laterExecutionSuccessRate = ToolMetricRatio.of(laterSuccess, laterTotal),
            evidenceBoundReuseRate = ToolMetricRatio.of(reuse, laterSuccess),
            simulationInfluenceRate = ToolMetricRatio.of(simulationReuse, simulations)
        )
    }

    private suspend fun validateLifecycleAuthority(value: ToolLifecycleEvent) {
        when (value.state) {
            ToolLifecycleState.SANDBOX_TESTED -> require(
                value.authorityReferenceIds.any { id ->
                    execution(id)?.let { execution ->
                        execution.toolVersionId == value.toolVersionId &&
                            execution.purpose == ToolExecutionPurpose.PROMOTION_TEST &&
                            execution.result == ToolExecutionResult.SUCCEEDED
                    } == true
                }
            ) { "SANDBOX_TESTED requires a successful persisted promotion execution" }
            ToolLifecycleState.EXECUTED_BY_LATER_AGENT -> require(
                value.authorityReferenceIds.any { id ->
                    execution(id)?.let { execution ->
                        execution.toolVersionId == value.toolVersionId &&
                            execution.purpose == ToolExecutionPurpose.AGENT_USE &&
                            execution.result == ToolExecutionResult.SUCCEEDED &&
                            execution.nodeId == value.contextNodeId
                    } == true
                }
            ) { "Later-agent lifecycle requires a matching persisted execution" }
            ToolLifecycleState.RESULT_REFERENCED_BY_NODE -> require(
                value.authorityReferenceIds.any { id -> dao.reuse(id) != null }
            ) { "Result-reference lifecycle requires persisted reuse lineage" }
            else -> Unit
        }
    }

    private suspend fun validateExecutionAuthority(
        value: ToolExecutionRecord,
        history: List<ToolLifecycleEvent>
    ) {
        require(history.isNotEmpty()) { "Tool execution references an unknown version" }
        val first = history.first()
        require(
            first.runId == value.runId &&
                first.opportunityId == value.opportunityId &&
                first.groupId == value.groupId
        ) { "Tool execution crosses run, opportunity, or group authority" }
        when (value.purpose) {
            ToolExecutionPurpose.PROMOTION_TEST -> {
                require(history.any { it.state == ToolLifecycleState.STATIC_VALIDATED }) {
                    "Promotion execution requires static validation"
                }
                require(value.nodeId == first.originNodeId) {
                    "Promotion execution must run against its authoring node"
                }
            }
            ToolExecutionPurpose.VALIDATION_TEST,
            ToolExecutionPurpose.AGENT_USE -> {
                require(history.any { it.state == ToolLifecycleState.PROMOTED }) {
                    "Worker execution requires a promoted exact tool version"
                }
                val opportunity = requireNotNull(opportunity(value.opportunityId)) {
                    "Worker execution requires sealed opportunity authority"
                }
                val registryEntry = requireNotNull(
                    toolRegistryDao.getById(value.toolVersionId.value)
                ) {
                    "Worker execution requires exact active registry authority"
                }
                val nodeAuthorized = when (value.purpose) {
                    ToolExecutionPurpose.VALIDATION_TEST ->
                        validationExecutionAuthorities.any { authority ->
                            authority.runId == value.runId &&
                                authority.groupId == value.groupId &&
                                authority.toolVersionId == value.toolVersionId &&
                                authority.opportunityId == value.opportunityId &&
                                authority.validationNodeId == value.nodeId &&
                                AtlasHashing.constantTimeEquals(
                                    authority.opportunityCommitmentHash,
                                    opportunity.commitmentHash
                                )
                        }
                    ToolExecutionPurpose.AGENT_USE ->
                        value.nodeId in opportunity.opportunity.expectedReuseNodeIds
                    ToolExecutionPurpose.PROMOTION_TEST -> false
                }
                require(
                    opportunity.hasValidHash() &&
                        nodeAuthorized &&
                        registryEntry.runId == value.runId &&
                        registryEntry.groupId == value.groupId.value &&
                        registryEntry.status == "active" &&
                        registryEntry.authorAgentId.isNotBlank() &&
                        registryEntry.authorAgentId != value.agentId.value &&
                        value.round > registryEntry.generation
                ) {
                    "Worker execution lacks exact later independent promoted-tool authority"
                }
            }
        }
    }

    private suspend fun validateReuseAuthority(
        value: ToolReuseLineage,
        history: List<ToolLifecycleEvent>
    ) {
        val execution = requireNotNull(execution(value.executionId)) {
            "Tool reuse references an unknown execution"
        }
        val origin = history.firstOrNull()
        require(
            execution.result == ToolExecutionResult.SUCCEEDED &&
                execution.purpose == ToolExecutionPurpose.AGENT_USE &&
                execution.toolVersionId == value.toolVersionId &&
                execution.runId == value.runId &&
                execution.nodeId == value.reusedOnNodeId &&
                execution.agentId == value.reusedByAgentId &&
                origin?.originNodeId == value.originNodeId
        ) { "Tool reuse does not match exact successful execution authority" }
    }

    private fun ToolExecutionRecord.toEntity(createdAtMs: Long) =
        ToolExecutionRecordEntity(
            executionId = executionId,
            runId = runId,
            opportunityId = opportunityId,
            toolVersionId = toolVersionId.value,
            groupId = groupId.value,
            agentId = agentId.value,
            round = round,
            nodeId = nodeId.value,
            purpose = purpose.name,
            result = result.name,
            recordHash = recordHash,
            contentJson = encode(ToolExecutionRecord.serializer(), this),
            createdAtMs = createdAtMs
        )

    private fun ToolLifecycleEvent.toEntity(createdAtMs: Long) =
        ToolLifecycleEventEntity(
            eventId = eventId,
            runId = runId,
            opportunityId = opportunityId,
            toolVersionId = toolVersionId.value,
            groupId = groupId.value,
            originNodeId = originNodeId.value,
            contextNodeId = contextNodeId.value,
            state = state.name,
            sequence = sequence,
            eventHash = eventHash,
            contentJson = encode(ToolLifecycleEvent.serializer(), this),
            createdAtMs = createdAtMs
        )

    private fun ToolReuseLineage.toEntity(createdAtMs: Long) =
        ToolReuseLineageEntity(
            lineageId = lineageId,
            runId = runId,
            toolVersionId = toolVersionId.value,
            executionId = executionId,
            originNodeId = originNodeId.value,
            reusedByAgentId = reusedByAgentId.value,
            reusedOnNodeId = reusedOnNodeId.value,
            contributionCommitId = contributionCommitId,
            nodeEvidencePacketHash = nodeEvidencePacketHash,
            lineageHash = lineageHash,
            contentJson = encode(ToolReuseLineage.serializer(), this),
            createdAtMs = createdAtMs
        )

    private fun <T> encode(serializer: KSerializer<T>, value: T): String =
        json.encodeToString(serializer, value)

    private fun <T> decode(serializer: KSerializer<T>, value: String): T =
        json.decodeFromString(serializer, value)

    private companion object {
        val VALIDATION_EXECUTION_PURPOSES = setOf(ToolExecutionPurpose.PROMOTION_TEST)
        const val ATOMIC_EXECUTION_RETRIES = 8
    }
}
