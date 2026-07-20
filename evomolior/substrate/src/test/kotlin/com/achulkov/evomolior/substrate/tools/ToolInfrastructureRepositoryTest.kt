@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.substrate.tools

import com.achulkov.evomolior.core.atlas.AtlasAgentId
import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.atlas.SimulationRunRecord
import com.achulkov.evomolior.core.atlas.ToolExecutionPurpose
import com.achulkov.evomolior.core.atlas.ToolExecutionRecord
import com.achulkov.evomolior.core.atlas.ToolExecutionResult
import com.achulkov.evomolior.core.atlas.ToolFitnessRecord
import com.achulkov.evomolior.core.atlas.ToolBehavior
import com.achulkov.evomolior.core.atlas.SimulationRecordBindings
import com.achulkov.evomolior.core.atlas.ToolImplementationMode
import com.achulkov.evomolior.core.atlas.ToolLifecycleEvent
import com.achulkov.evomolior.core.atlas.ToolLifecycleState
import com.achulkov.evomolior.core.atlas.ToolOpportunity
import com.achulkov.evomolior.core.atlas.ToolOpportunityCommitment
import com.achulkov.evomolior.core.atlas.ToolOpportunityType
import com.achulkov.evomolior.core.atlas.ToolReuseLineage
import com.achulkov.evomolior.core.atlas.ToolValidationExecutionAuthority
import com.achulkov.evomolior.core.atlas.ToolVersionId
import com.achulkov.evomolior.core.atlas.ToolVersionTransitionDetails
import com.achulkov.evomolior.core.atlas.TypedField
import com.achulkov.evomolior.core.atlas.TypedFieldType
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.db.entity.ToolRegistryEntryEntity
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonPrimitive
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ToolInfrastructureRepositoryTest {
    private val runId = "run-tool"
    private val groupId = AtlasGroupId("group-tool")
    private val originNodeId = NodeId("node-origin")
    private val validationNodeId = NodeId("node-validation")
    private val laterNodeId = NodeId("node-later")
    private val toolVersionId = ToolVersionId("tool-version-1")
    private val at = Instant.parse("2026-01-01T00:00:00Z")

    @Test
    fun `exact lifecycle execution simulation reuse and delayed fitness remain append only`() =
        runTest {
            val database = createDatabase()
            try {
                val opportunity = opportunityCommitment().withComputedHash()
                val repository = ToolInfrastructureRepository(
                    database = database,
                    validationExecutionAuthorities = setOf(
                        ToolValidationExecutionAuthority(
                            runId = runId,
                            groupId = groupId,
                            toolVersionId = toolVersionId,
                            opportunityId = opportunity.opportunity.opportunityId,
                            opportunityCommitmentHash = opportunity.commitmentHash,
                            validationNodeId = validationNodeId
                        )
                    ),
                    now = { 100L }
                )

                assertEquals(opportunity, repository.appendOpportunity(opportunity))
                assertEquals(opportunity, repository.appendOpportunity(opportunity))

                var previous: ToolLifecycleEvent? = null
                suspend fun append(
                    state: ToolLifecycleState,
                    contextNodeId: NodeId = originNodeId,
                    references: Set<String> = emptySet()
                ): ToolLifecycleEvent {
                    val event = lifecycle(
                        state = state,
                        sequence = (previous?.sequence ?: -1) + 1,
                        previousHash = previous?.eventHash,
                        contextNodeId = contextNodeId,
                        references = references
                    ).withComputedHash()
                    repository.appendLifecycle(event)
                    previous = event
                    return event
                }

                append(ToolLifecycleState.TOOL_SPEC, references = setOf(opportunity.commitmentHash))
                append(ToolLifecycleState.IMPLEMENTED, references = setOf("content-hash"))
                append(ToolLifecycleState.STATIC_VALIDATED, references = setOf("static-report"))

                val promotionExecution = execution(
                    id = "promotion-execution",
                    nodeId = originNodeId,
                    purpose = ToolExecutionPurpose.PROMOTION_TEST,
                    round = 1
                ).withComputedHash()
                repository.appendExecution(promotionExecution)
                append(
                    ToolLifecycleState.SANDBOX_TESTED,
                    references = setOf(promotionExecution.executionId)
                )
                append(ToolLifecycleState.CRITIC_ACCEPTED, references = setOf("critic-review"))
                append(ToolLifecycleState.PROMOTED, references = setOf("registry-entry"))
                database.toolRegistryDao().insert(
                    ToolRegistryEntryEntity(
                        id = toolVersionId.value,
                        name = "tool-version",
                        version = 1,
                        authorAgentId = "agent-author",
                        inputSchemaJson = "{}",
                        outputSchemaJson = "{}",
                        domain = "test",
                        status = "active",
                        code = "echo",
                        codeLanguage = "shell",
                        generation = 1,
                        groupId = groupId.value,
                        runId = runId,
                        createdAtMs = at.toEpochMilliseconds()
                    )
                )

                val validationExecution = execution(
                    id = "validation-execution",
                    nodeId = validationNodeId,
                    purpose = ToolExecutionPurpose.VALIDATION_TEST
                ).withComputedHash()
                assertFailsWith<IllegalArgumentException> {
                    ToolInfrastructureRepository(database).appendExecution(validationExecution)
                }
                val unrelatedValidationExecution = execution(
                    id = "unrelated-validation-execution",
                    nodeId = NodeId("node-unrelated"),
                    purpose = ToolExecutionPurpose.VALIDATION_TEST
                ).withComputedHash()
                assertFailsWith<IllegalArgumentException> {
                    repository.appendExecution(unrelatedValidationExecution)
                }
                repository.appendExecution(validationExecution)
                assertEquals(
                    listOf(promotionExecution),
                    repository.executionsForGroupThroughRound(
                        runId = runId,
                        groupId = groupId.value,
                        maximumRound = 1
                    )
                )
                assertEquals(
                    emptyList(),
                    repository.lifecycle(toolVersionId).filter {
                        it.state == ToolLifecycleState.EXECUTED_BY_LATER_AGENT
                    }
                )

                val laterExecution = execution(
                    id = "later-execution",
                    nodeId = laterNodeId,
                    purpose = ToolExecutionPurpose.AGENT_USE
                ).withComputedHash()
                val concurrentExecution = execution(
                    id = "concurrent-execution",
                    nodeId = laterNodeId,
                    purpose = ToolExecutionPurpose.AGENT_USE
                ).withComputedHash()
                coroutineScope {
                    listOf(laterExecution, concurrentExecution).map { value ->
                        async {
                            repository.appendSuccessfulAgentExecution(
                                value = value,
                                lifecycleEventId = "lifecycle-${value.executionId}",
                                recordedAt = at
                            )
                        }
                    }.awaitAll()
                }
                assertEquals(laterExecution, repository.execution(laterExecution.executionId))
                assertEquals(
                    concurrentExecution,
                    repository.execution(concurrentExecution.executionId)
                )
                val executionEvents = repository.lifecycle(toolVersionId).filter {
                    it.state == ToolLifecycleState.EXECUTED_BY_LATER_AGENT
                }
                assertEquals(2, executionEvents.size)
                assertEquals(
                    setOf(laterExecution.executionId, concurrentExecution.executionId),
                    executionEvents.flatMapTo(linkedSetOf()) { it.authorityReferenceIds }
                )
                previous = repository.lifecycle(toolVersionId).last()
                assertEquals(null, repository.deriveAndAppendFitness(toolVersionId, 3))

                val simulation = SimulationRunRecord(
                    simulationRunId = "simulation-1",
                    executionId = laterExecution.executionId,
                    toolVersionId = toolVersionId,
                    inputHash = laterExecution.inputHash,
                    parameters = mapOf("iterations" to JsonPrimitive(100)),
                    assumptions = listOf("Observed rates remain stationary."),
                    randomSeed = 7L,
                    stochastic = true,
                    outputArtifactId = requireNotNull(laterExecution.outputArtifactId),
                    resultSummary = "The accepted interval remained stable.",
                    uncertainty = "95% interval is recorded in the artifact.",
                    validationRefs = listOf("promotion-test"),
                    executedBy = laterExecution.agentId
                ).withComputedHash()
                assertEquals(simulation, repository.appendSimulation(simulation))

                val reuse = ToolReuseLineage(
                    lineageId = "reuse-1",
                    runId = runId,
                    toolVersionId = toolVersionId,
                    executionId = laterExecution.executionId,
                    originNodeId = originNodeId,
                    reusedByAgentId = laterExecution.agentId,
                    reusedOnNodeId = laterNodeId,
                    contributionCommitId = "commit-later",
                    nodeEvidencePacketHash = "packet-hash",
                    nodeScoreBefore = 0.4,
                    nodeScoreAfter = 0.8,
                    avoidedRepeatedCost = 2.0,
                    attributedAt = at
                ).withComputedHash()
                repository.appendReuseAndReference(
                    value = reuse,
                    lifecycleEventId = "lifecycle-${reuse.lineageId}",
                    recordedAt = at
                )
                assertEquals(listOf(reuse), repository.reuseForVersion(toolVersionId))
                previous = repository.lifecycle(toolVersionId).last()

                val fitness = requireNotNull(
                    repository.deriveAndAppendFitness(toolVersionId, throughRound = 3)
                )
                // Independent worker validation is evidence authority, not promotion fitness.
                assertEquals(1, fitness.validationPasses)
                assertEquals(
                    setOf(concurrentExecution.executionId, laterExecution.executionId),
                    fitness.successfulExecutionIds
                )
                assertEquals(6.4, fitness.fitnessValue)
                assertEquals(listOf(fitness), repository.fitnessForVersion(toolVersionId))
                assertEquals(listOf(reuse), repository.reuseForVersion(toolVersionId))
                val metrics = repository.metrics(runId, groupId)
                assertEquals(1, metrics.opportunityCount)
                assertEquals(1, metrics.promotedVersionCount)
                assertEquals(2, metrics.successfulLaterExecutionCount)
                assertEquals(1, metrics.simulationRunCount)
                assertEquals(1, metrics.evidenceBoundReuseCount)
                assertEquals(1, metrics.evidenceBoundSimulationReuseCount)
                assertEquals(1, metrics.delayedFitnessVersionCount)
                assertEquals(1.0, metrics.promotionSuccessRate.value)
                assertEquals(0.5, metrics.evidenceBoundReuseRate.value)
                assertEquals(
                    setOf(
                        promotionExecution,
                        validationExecution,
                        concurrentExecution,
                        laterExecution
                    ),
                    repository.executions(toolVersionId).toSet()
                )

                assertFailsWith<IllegalArgumentException> {
                    repository.appendExecution(
                        laterExecution.copy(inputHash = "changed", recordHash = "")
                            .withComputedHash()
                    )
                }
            } finally {
                database.close()
            }
        }

    @Test
    fun `unknown unpromoted failed and mismatched execution authority fails closed`() = runTest {
        val database = createDatabase()
        try {
            val repository = ToolInfrastructureRepository(database)
            val unknown = execution(
                id = "unknown-execution",
                nodeId = laterNodeId,
                purpose = ToolExecutionPurpose.AGENT_USE
            ).withComputedHash()
            assertFailsWith<IllegalArgumentException> {
                repository.appendExecution(unknown)
            }

            val opportunity = opportunityCommitment().withComputedHash()
            repository.appendOpportunity(opportunity)
            repository.appendLifecycle(
                lifecycle(
                    state = ToolLifecycleState.TOOL_SPEC,
                    sequence = 0,
                    references = setOf(opportunity.commitmentHash)
                ).withComputedHash()
            )
            assertFailsWith<IllegalArgumentException> {
                repository.appendExecution(unknown)
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun `opportunity activation rejects stale zero-parent and stale explicit-parent writes`() =
        runTest {
            val database = createDatabase()
            try {
                val repository = ToolInfrastructureRepository(database, now = { 100L })
                repository.appendOpportunity(opportunityCommitment().withComputedHash())

                val first = preparePromotableVersion(
                    repository = repository,
                    versionId = ToolVersionId("first-v1"),
                    name = "first",
                    previousVersionId = null
                )
                repository.activatePromotedVersion(
                    event = first.promotedEvent,
                    registryEntry = first.registryEntry,
                    expectedPreviousActiveVersionId = null
                )

                val staleZeroParent = preparePromotableVersion(
                    repository = repository,
                    versionId = ToolVersionId("zero-parent-v1"),
                    name = "zero-parent",
                    previousVersionId = null
                )
                assertFailsWith<IllegalStateException> {
                    repository.activatePromotedVersion(
                        event = staleZeroParent.promotedEvent,
                        registryEntry = staleZeroParent.registryEntry,
                        expectedPreviousActiveVersionId = null
                    )
                }
                assertNull(database.toolRegistryDao().getById(staleZeroParent.registryEntry.id))
                assertEquals(
                    ToolLifecycleState.CRITIC_ACCEPTED,
                    repository.lifecycle(staleZeroParent.promotedEvent.toolVersionId).last().state
                )

                val rolledBackReplacement = preparePromotableVersion(
                    repository = repository,
                    versionId = ToolVersionId("rolled-back-v1"),
                    name = "rolled-back",
                    previousVersionId = first.registryEntry.id
                )
                val collidingParentTransition = parentTransition(
                    parent = first.promotedEvent,
                    childVersionId = rolledBackReplacement.promotedEvent.toolVersionId
                ).copy(
                    eventId = first.promotedEvent.eventId,
                    eventHash = ""
                ).withComputedHash()
                assertFails {
                    repository.activatePromotedVersion(
                        event = rolledBackReplacement.promotedEvent,
                        registryEntry = rolledBackReplacement.registryEntry,
                        expectedPreviousActiveVersionId = first.registryEntry.id,
                        parentTransitionEvent = collidingParentTransition
                    )
                }
                assertEquals(
                    "active",
                    database.toolRegistryDao().getById(first.registryEntry.id)?.status
                )
                assertNull(
                    database.toolRegistryDao().getById(rolledBackReplacement.registryEntry.id)
                )
                assertEquals(
                    ToolLifecycleState.CRITIC_ACCEPTED,
                    repository.lifecycle(rolledBackReplacement.promotedEvent.toolVersionId)
                        .last().state
                )
                assertEquals(
                    ToolLifecycleState.PROMOTED,
                    repository.lifecycle(first.promotedEvent.toolVersionId).last().state
                )

                val replacement = preparePromotableVersion(
                    repository = repository,
                    versionId = ToolVersionId("replacement-v1"),
                    name = "replacement",
                    previousVersionId = first.registryEntry.id
                )
                repository.activatePromotedVersion(
                    event = replacement.promotedEvent,
                    registryEntry = replacement.registryEntry,
                    expectedPreviousActiveVersionId = first.registryEntry.id,
                    parentTransitionEvent = parentTransition(
                        parent = first.promotedEvent,
                        childVersionId = replacement.promotedEvent.toolVersionId
                    )
                )

                val staleExplicitParent = preparePromotableVersion(
                    repository = repository,
                    versionId = ToolVersionId("stale-parent-v1"),
                    name = "stale-parent",
                    previousVersionId = first.registryEntry.id
                )
                assertFails {
                    repository.activatePromotedVersion(
                        event = staleExplicitParent.promotedEvent,
                        registryEntry = staleExplicitParent.registryEntry,
                        expectedPreviousActiveVersionId = first.registryEntry.id,
                        parentTransitionEvent = parentTransition(
                            parent = first.promotedEvent,
                            childVersionId = staleExplicitParent.promotedEvent.toolVersionId
                        )
                    )
                }
                assertNull(database.toolRegistryDao().getById(staleExplicitParent.registryEntry.id))
                assertEquals(
                    listOf(replacement.registryEntry.id),
                    repository.activeVersionsForOpportunity(
                        runId,
                        groupId.value,
                        opportunityCommitment().opportunity.opportunityId
                    ).map { it.id }
                )
                assertEquals(
                    "archived",
                    database.toolRegistryDao().getById(first.registryEntry.id)?.status
                )
            } finally {
                database.close()
            }
        }

    @Test
    fun `incomplete active opportunity lifecycle blocks initial activation`() = runTest {
        val database = createDatabase()
        try {
            val repository = ToolInfrastructureRepository(database, now = { 100L })
            val opportunity = opportunityCommitment().withComputedHash()
            repository.appendOpportunity(opportunity)
            val partialVersionId = ToolVersionId("partial-v1")
            repository.appendLifecycle(
                ToolLifecycleEvent(
                    eventId = "event-partial-spec",
                    runId = runId,
                    opportunityId = opportunity.opportunity.opportunityId,
                    toolVersionId = partialVersionId,
                    groupId = groupId,
                    originNodeId = originNodeId,
                    contextNodeId = originNodeId,
                    state = ToolLifecycleState.TOOL_SPEC,
                    sequence = 0,
                    authorityReferenceIds = setOf(opportunity.commitmentHash),
                    recordedAt = at
                ).withComputedHash()
            )
            database.toolRegistryDao().insert(
                ToolRegistryEntryEntity(
                    id = partialVersionId.value,
                    name = "partial",
                    version = 1,
                    authorAgentId = "agent-partial",
                    inputSchemaJson = "{}",
                    outputSchemaJson = "{}",
                    domain = "test",
                    status = "active",
                    code = "echo",
                    codeLanguage = "shell",
                    generation = 1,
                    groupId = groupId.value,
                    runId = runId,
                    createdAtMs = at.toEpochMilliseconds()
                )
            )
            val candidate = preparePromotableVersion(
                repository = repository,
                versionId = ToolVersionId("candidate-v1"),
                name = "candidate",
                previousVersionId = null
            )

            assertFailsWith<IllegalStateException> {
                repository.activatePromotedVersion(
                    event = candidate.promotedEvent,
                    registryEntry = candidate.registryEntry,
                    expectedPreviousActiveVersionId = null
                )
            }
            assertNull(database.toolRegistryDao().getById(candidate.registryEntry.id))
            assertEquals(
                listOf(partialVersionId.value),
                repository.activeVersionsForOpportunity(
                    runId,
                    groupId.value,
                    opportunity.opportunity.opportunityId
                ).map { it.id }
            )
        } finally {
            database.close()
        }
    }

    private fun createDatabase() =
        SubstrateDatabaseFactory(createTempDirectory("tool-infrastructure-").toFile())
            .get(runId, groupId.value)

    private suspend fun preparePromotableVersion(
        repository: ToolInfrastructureRepository,
        versionId: ToolVersionId,
        name: String,
        previousVersionId: String?
    ): PromotableVersion {
        var previous: ToolLifecycleEvent? = null
        suspend fun append(
            state: ToolLifecycleState,
            references: Set<String>
        ): ToolLifecycleEvent {
            val event = ToolLifecycleEvent(
                eventId = "event-${versionId.value}-${state.name}",
                runId = runId,
                opportunityId = opportunityCommitment().opportunity.opportunityId,
                toolVersionId = versionId,
                groupId = groupId,
                originNodeId = originNodeId,
                contextNodeId = originNodeId,
                state = state,
                sequence = (previous?.sequence ?: -1) + 1,
                previousEventHash = previous?.eventHash,
                authorityReferenceIds = references,
                recordedAt = at
            ).withComputedHash()
            repository.appendLifecycle(event)
            previous = event
            return event
        }

        append(
            ToolLifecycleState.TOOL_SPEC,
            setOf(opportunityCommitment().withComputedHash().commitmentHash)
        )
        append(ToolLifecycleState.IMPLEMENTED, setOf("implementation-${versionId.value}"))
        append(ToolLifecycleState.STATIC_VALIDATED, setOf("static-${versionId.value}"))
        val promotionExecution = execution(
            id = "promotion-${versionId.value}",
            nodeId = originNodeId,
            purpose = ToolExecutionPurpose.PROMOTION_TEST,
            round = 1
        ).copy(toolVersionId = versionId).withComputedHash()
        repository.appendExecution(promotionExecution)
        append(ToolLifecycleState.SANDBOX_TESTED, setOf(promotionExecution.executionId))
        append(ToolLifecycleState.CRITIC_ACCEPTED, setOf("critic-${versionId.value}"))

        val registryEntry = ToolRegistryEntryEntity(
            id = versionId.value,
            name = name,
            version = 1,
            authorAgentId = "agent-author",
            inputSchemaJson = "{}",
            outputSchemaJson = "{}",
            domain = "test",
            status = "active",
            code = "echo",
            codeLanguage = "shell",
            generation = 1,
            groupId = groupId.value,
            runId = runId,
            createdAtMs = at.toEpochMilliseconds(),
            previousVersionId = previousVersionId
        )
        val promotedEvent = ToolLifecycleEvent(
            eventId = "event-${versionId.value}-${ToolLifecycleState.PROMOTED.name}",
            runId = runId,
            opportunityId = opportunityCommitment().opportunity.opportunityId,
            toolVersionId = versionId,
            groupId = groupId,
            originNodeId = originNodeId,
            contextNodeId = originNodeId,
            state = ToolLifecycleState.PROMOTED,
            sequence = requireNotNull(previous).sequence + 1,
            previousEventHash = requireNotNull(previous).eventHash,
            authorityReferenceIds = setOf(versionId.value),
            recordedAt = at
        ).withComputedHash()
        return PromotableVersion(registryEntry, promotedEvent)
    }

    private fun parentTransition(
        parent: ToolLifecycleEvent,
        childVersionId: ToolVersionId
    ) = ToolLifecycleEvent(
        eventId = "event-${parent.toolVersionId.value}-maintained-${childVersionId.value}",
        runId = parent.runId,
        opportunityId = parent.opportunityId,
        toolVersionId = parent.toolVersionId,
        groupId = parent.groupId,
        originNodeId = parent.originNodeId,
        contextNodeId = parent.contextNodeId,
        state = ToolLifecycleState.MAINTAINED,
        sequence = parent.sequence + 1,
        previousEventHash = parent.eventHash,
        authorityReferenceIds = setOf(childVersionId.value),
        transitionDetails = ToolVersionTransitionDetails(
            childToolVersionId = childVersionId,
            maintenanceCost = 1.0,
            reason = "Atomic repository replacement test."
        ),
        recordedAt = at
    ).withComputedHash()

    private fun opportunityCommitment() = ToolOpportunityCommitment(
        runId = runId,
        atlasHash = "atlas-hash",
        nodeId = originNodeId,
        opportunity = ToolOpportunity(
            opportunityId = "opportunity-1",
            opportunityType = ToolOpportunityType.SIMULATION,
            repeatedQuestionPattern = "Simulate the same bounded scenario for each candidate.",
            requiredInputs = listOf(
                TypedField("iterations", TypedFieldType.INTEGER, "Simulation iteration count."),
                TypedField("randomSeed", TypedFieldType.INTEGER, "Explicit random seed.")
            ),
            requiredOutputs = listOf(
                TypedField(
                    name = "interval",
                    type = TypedFieldType.OBJECT,
                    description = "Result interval.",
                    objectFields = setOf(
                        TypedField("lower", TypedFieldType.NUMBER, "Lower interval bound."),
                        TypedField("upper", TypedFieldType.NUMBER, "Upper interval bound.")
                    )
                ),
                TypedField("summary", TypedFieldType.STRING, "Bounded result summary."),
                TypedField("uncertainty", TypedFieldType.STRING, "Explicit uncertainty.")
            ),
            validationStrategy = "Run committed deterministic and stochastic boundary cases.",
            expectedReuseNodeIds = setOf(laterNodeId),
            preferredImplementationMode = ToolImplementationMode.SIMULATION_DSL,
            behavior = ToolBehavior.STOCHASTIC,
            requiredCapabilities = setOf("shell"),
            promotionTestIds = setOf("promotion-test"),
            maximumRuntimeMillis = 2_000,
            maximumOutputBytes = 32_768,
            assumptions = listOf("Input distributions are explicit."),
            knownInvalidDomains = listOf("Unbounded iteration counts."),
            simulationRecordBindings = SimulationRecordBindings(
                resultSummaryOutputField = "summary",
                uncertaintyOutputField = "uncertainty",
                randomSeedInputField = "randomSeed"
            )
        )
    )

    private fun lifecycle(
        state: ToolLifecycleState,
        sequence: Long,
        previousHash: String? = null,
        contextNodeId: NodeId = originNodeId,
        references: Set<String> = emptySet()
    ) = ToolLifecycleEvent(
        eventId = "event-$sequence-${state.name}",
        runId = runId,
        opportunityId = "opportunity-1",
        toolVersionId = toolVersionId,
        groupId = groupId,
        originNodeId = originNodeId,
        contextNodeId = contextNodeId,
        state = state,
        sequence = sequence,
        previousEventHash = previousHash,
        authorityReferenceIds = references,
        recordedAt = at
    )

    private fun execution(
        id: String,
        nodeId: NodeId,
        purpose: ToolExecutionPurpose,
        round: Int = 3
    ) = ToolExecutionRecord(
        executionId = id,
        runId = runId,
        toolVersionId = toolVersionId,
        opportunityId = "opportunity-1",
        groupId = groupId,
        agentId = AtlasAgentId("agent-later"),
        round = round,
        nodeId = nodeId,
        purpose = purpose,
        inputHash = "input-hash-$id",
        outputHash = "output-hash-$id",
        outputJson = JsonPrimitive(id),
        outputArtifactId = "artifact-$id",
        startedAt = at,
        completedAt = at,
        durationMillis = 10,
        exitCode = 0,
        result = ToolExecutionResult.SUCCEEDED,
        sandboxRecordHash = "sandbox-hash-$id"
    )

    private data class PromotableVersion(
        val registryEntry: ToolRegistryEntryEntity,
        val promotedEvent: ToolLifecycleEvent
    )
}
