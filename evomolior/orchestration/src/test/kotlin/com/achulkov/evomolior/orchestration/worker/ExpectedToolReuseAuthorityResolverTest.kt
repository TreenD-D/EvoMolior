@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.orchestration.worker

import com.achulkov.evomolior.core.atlas.ArtifactType
import com.achulkov.evomolior.core.atlas.AtlasAgentId
import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.CapturePolicy
import com.achulkov.evomolior.core.atlas.DisclosureStage
import com.achulkov.evomolior.core.atlas.EvidenceKind
import com.achulkov.evomolior.core.atlas.LimitationNode
import com.achulkov.evomolior.core.atlas.NodeAtlas
import com.achulkov.evomolior.core.atlas.NodeDeliverableContract
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.atlas.NodeKind
import com.achulkov.evomolior.core.atlas.ProofContract
import com.achulkov.evomolior.core.atlas.ProofMethod
import com.achulkov.evomolior.core.atlas.ReplicationPolicy
import com.achulkov.evomolior.core.atlas.RevealMode
import com.achulkov.evomolior.core.atlas.RevealPolicy
import com.achulkov.evomolior.core.atlas.RewardClass
import com.achulkov.evomolior.core.atlas.RewardSpec
import com.achulkov.evomolior.core.atlas.ToolBehavior
import com.achulkov.evomolior.core.atlas.ToolExecutionPurpose
import com.achulkov.evomolior.core.atlas.ToolExecutionRecord
import com.achulkov.evomolior.core.atlas.ToolExecutionResult
import com.achulkov.evomolior.core.atlas.ToolImplementationMode
import com.achulkov.evomolior.core.atlas.ToolLifecycleEvent
import com.achulkov.evomolior.core.atlas.ToolLifecycleState
import com.achulkov.evomolior.core.atlas.ToolOpportunity
import com.achulkov.evomolior.core.atlas.ToolOpportunityCommitment
import com.achulkov.evomolior.core.atlas.ToolOpportunityType
import com.achulkov.evomolior.core.atlas.ToolValidationExecutionAuthority
import com.achulkov.evomolior.core.atlas.ToolVersionId
import com.achulkov.evomolior.core.atlas.TypedField
import com.achulkov.evomolior.core.atlas.TypedFieldType
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.db.entity.ToolRegistryEntryEntity
import com.achulkov.evomolior.substrate.tools.ToolInfrastructureRepository
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant

class ExpectedToolReuseAuthorityResolverTest {
    private val runId = "run-expected-reuse"
    private val groupId = AtlasGroupId("group-expected-reuse")
    private val originNodeId = NodeId("origin")
    private val validationNodeId = NodeId("validation")
    private val expectedNodeId = NodeId("expected")
    private val versionId = ToolVersionId("calculator-v1")
    private val at = Instant.parse("2026-07-15T00:00:00Z")

    @Test
    fun `only exact active promoted version is exposed and pending until successful expected use`() =
        runTest {
            val factory = SubstrateDatabaseFactory(
                createTempDirectory("expected-reuse-resolver-").toFile()
            )
            val database = factory.get(runId, groupId.value)
            val atlas = atlas()
            val repository = ToolInfrastructureRepository(database)
            val registryEntry = preparePromotedTool(repository, database, atlas)
            val resolver = ExpectedToolReuseAuthorityResolver()

            val pending = resolver.resolve(database, atlas, groupId)

            val validationCard =
                pending.toolCardsByNodeId.getValue(validationNodeId).single()
            assertEquals(registryEntry.id, validationCard.toolId)
            assertEquals(registryEntry.version, validationCard.version)
            assertEquals(opportunity().opportunityId, validationCard.opportunityId)
            assertEquals(validationNodeId, validationCard.requiredForNodeId)
            assertEquals(
                ToolExecutionPurpose.VALIDATION_TEST,
                validationCard.executionPurpose
            )
            val reuseCard = pending.toolCardsByNodeId.getValue(expectedNodeId).single()
            assertEquals(expectedNodeId, reuseCard.requiredForNodeId)
            assertEquals(ToolExecutionPurpose.AGENT_USE, reuseCard.executionPurpose)
            assertEquals(
                AtlasHashing.hashText(registryEntry.inputSchemaJson),
                validationCard.inputSchemaHash
            )
            assertEquals(setOf(expectedNodeId), pending.pendingNodeIds)

            val laterExecution = execution(
                id = "later-execution",
                nodeId = expectedNodeId,
                purpose = ToolExecutionPurpose.AGENT_USE,
                round = 2
            )
            repository.appendSuccessfulAgentExecution(
                value = laterExecution,
                lifecycleEventId = "event-later-execution",
                recordedAt = at
            )

            val satisfied = resolver.resolve(database, atlas, groupId)
            assertEquals(
                setOf(validationNodeId, expectedNodeId),
                satisfied.toolCardsByNodeId.keys
            )
            assertEquals(emptySet(), satisfied.pendingNodeIds)
        }

    @Test
    fun `repository separates validation from expected reuse execution authority`() = runTest {
        val factory = SubstrateDatabaseFactory(
            createTempDirectory("expected-reuse-repository-").toFile()
        )
        val database = factory.get(runId, groupId.value)
        val atlas = atlas()
        val commitment = ToolOpportunityCommitment(
            runId = runId,
            atlasHash = atlas.atlasHash,
            nodeId = originNodeId,
            opportunity = opportunity()
        ).withComputedHash()
        val repository = ToolInfrastructureRepository(
            database = database,
            validationExecutionAuthorities = setOf(
                ToolValidationExecutionAuthority(
                    runId = runId,
                    groupId = groupId,
                    toolVersionId = versionId,
                    opportunityId = commitment.opportunity.opportunityId,
                    opportunityCommitmentHash = commitment.commitmentHash,
                    validationNodeId = validationNodeId
                )
            )
        )
        preparePromotedTool(repository, database, atlas)

        repository.appendExecution(
            execution(
                id = "validation-execution",
                nodeId = validationNodeId,
                purpose = ToolExecutionPurpose.VALIDATION_TEST,
                round = 2
            )
        )
        assertFailsWith<IllegalArgumentException> {
            repository.appendExecution(
                execution(
                    id = "validation-on-reuse-node",
                    nodeId = expectedNodeId,
                    purpose = ToolExecutionPurpose.VALIDATION_TEST,
                    round = 2
                )
            )
        }
        assertFailsWith<IllegalArgumentException> {
            repository.appendExecution(
                execution(
                    id = "agent-use-on-validation-node",
                    nodeId = validationNodeId,
                    purpose = ToolExecutionPurpose.AGENT_USE,
                    round = 2
                )
            )
        }
    }

    @Test
    fun `corrupt duplicate active opportunity authority remains fail closed`() = runTest {
        val factory = SubstrateDatabaseFactory(
            createTempDirectory("expected-reuse-duplicate-authority-").toFile()
        )
        val database = factory.get(runId, groupId.value)
        val atlas = atlas()
        val repository = ToolInfrastructureRepository(database)
        preparePromotedTool(repository, database, atlas)
        preparePromotedTool(
            repository = repository,
            database = database,
            atlas = atlas,
            promotedVersionId = ToolVersionId("calculator-alternative-v1"),
            toolName = "calculator-alternative"
        )

        assertFailsWith<IllegalArgumentException> {
            ExpectedToolReuseAuthorityResolver().resolve(database, atlas, groupId)
        }
    }

    private suspend fun preparePromotedTool(
        repository: ToolInfrastructureRepository,
        database: com.achulkov.evomolior.substrate.db.EvoMoliorDatabase,
        atlas: NodeAtlas,
        promotedVersionId: ToolVersionId = versionId,
        toolName: String = "calculator"
    ): ToolRegistryEntryEntity {
        val opportunity = opportunity()
        val commitment = ToolOpportunityCommitment(
            runId = runId,
            atlasHash = atlas.atlasHash,
            nodeId = originNodeId,
            opportunity = opportunity
        ).withComputedHash()
        repository.appendOpportunity(commitment)

        var previous: ToolLifecycleEvent? = null
        suspend fun append(state: ToolLifecycleState, references: Set<String>) {
            val event = ToolLifecycleEvent(
                eventId = "event-${promotedVersionId.value}-${state.name}",
                runId = runId,
                opportunityId = opportunity.opportunityId,
                toolVersionId = promotedVersionId,
                groupId = groupId,
                originNodeId = originNodeId,
                contextNodeId = originNodeId,
                state = state,
                sequence = (previous?.sequence ?: -1L) + 1,
                previousEventHash = previous?.eventHash,
                authorityReferenceIds = references,
                recordedAt = at
            ).withComputedHash()
            repository.appendLifecycle(event)
            previous = event
        }
        append(ToolLifecycleState.TOOL_SPEC, setOf(commitment.commitmentHash))
        append(ToolLifecycleState.IMPLEMENTED, setOf("implementation"))
        append(ToolLifecycleState.STATIC_VALIDATED, setOf("static-validation"))
        val promotionExecution = execution(
            id = "promotion-execution-${promotedVersionId.value}",
            nodeId = originNodeId,
            purpose = ToolExecutionPurpose.PROMOTION_TEST,
            round = 0,
            toolVersionId = promotedVersionId
        )
        repository.appendExecution(promotionExecution)
        append(ToolLifecycleState.SANDBOX_TESTED, setOf(promotionExecution.executionId))
        append(ToolLifecycleState.CRITIC_ACCEPTED, setOf("critic-review"))
        append(ToolLifecycleState.PROMOTED, setOf(promotedVersionId.value))

        val registryEntry = ToolRegistryEntryEntity(
            id = promotedVersionId.value,
            name = toolName,
            version = 1,
            authorAgentId = "agent-author",
            inputSchemaJson = """{"type":"object","required":["value"]}""",
            outputSchemaJson = """{"type":"object","required":["result"]}""",
            domain = "calculation",
            code = "echo",
            codeLanguage = "shell",
            generation = 0,
            groupId = groupId.value,
            runId = runId,
            createdAtMs = at.toEpochMilliseconds()
        )
        database.toolRegistryDao().insert(registryEntry)
        return registryEntry
    }

    private fun execution(
        id: String,
        nodeId: NodeId,
        purpose: ToolExecutionPurpose,
        round: Int,
        toolVersionId: ToolVersionId = versionId
    ) = ToolExecutionRecord(
        executionId = id,
        runId = runId,
        toolVersionId = toolVersionId,
        opportunityId = opportunity().opportunityId,
        groupId = groupId,
        agentId = AtlasAgentId(
            if (purpose == ToolExecutionPurpose.PROMOTION_TEST) "agent-author" else "agent-later"
        ),
        round = round,
        nodeId = nodeId,
        purpose = purpose,
        inputHash = "input-$id",
        outputHash = "output-$id",
        outputJson = JsonPrimitive(id),
        outputArtifactId = "output-artifact-$id",
        startedAt = at,
        completedAt = at,
        durationMillis = 0,
        exitCode = 0,
        result = ToolExecutionResult.SUCCEEDED,
        sandboxRecordHash = "sandbox-$id"
    ).withComputedHash()

    private fun opportunity() = ToolOpportunity(
        opportunityId = "opportunity-calculator",
        opportunityType = ToolOpportunityType.CALCULATION,
        repeatedQuestionPattern = "Calculate one exact downstream value.",
        requiredInputs = listOf(
            TypedField("value", TypedFieldType.NUMBER, "Input value.")
        ),
        requiredOutputs = listOf(
            TypedField("result", TypedFieldType.NUMBER, "Calculated result.")
        ),
        validationStrategy = "Run the committed exact case.",
        expectedReuseNodeIds = setOf(expectedNodeId),
        preferredImplementationMode = ToolImplementationMode.SHELL,
        behavior = ToolBehavior.DETERMINISTIC,
        promotionTestIds = setOf("exact-case"),
        maximumRuntimeMillis = 1_000,
        maximumOutputBytes = 4_096
    )

    private fun atlas(): NodeAtlas {
        val origin = node(
            id = originNodeId,
            stageId = "stage-0",
            layer = 0,
            prerequisites = emptySet(),
            toolOpportunity = opportunity()
        )
        val expected = node(
            id = expectedNodeId,
            stageId = "stage-1",
            layer = 2,
            prerequisites = setOf(originNodeId, validationNodeId),
            toolOpportunity = null,
            kind = NodeKind.EVIDENCE
        )
        val validation = node(
            id = validationNodeId,
            stageId = "stage-1",
            layer = 1,
            prerequisites = setOf(originNodeId),
            toolOpportunity = null,
            kind = NodeKind.VALIDATION,
            proofMethod = ProofMethod.REPRODUCTION,
            executableTestIds = setOf("validation-case")
        )
        val nodes = linkedMapOf(
            origin.id to origin,
            validation.id to validation,
            expected.id to expected
        )
        return NodeAtlas(
            atlasId = "atlas-expected-reuse",
            runId = runId,
            version = 1,
            receiverContractHash = "receiver-contract-hash",
            rootNodeIds = setOf(originNodeId),
            nodes = nodes,
            stageDefinitions = listOf(
                stage("stage-0", 0, setOf(originNodeId), nodes),
                stage("stage-1", 1, setOf(validationNodeId, expectedNodeId), nodes)
            )
        ).withComputedHash()
    }

    private fun node(
        id: NodeId,
        stageId: String,
        layer: Int,
        prerequisites: Set<NodeId>,
        toolOpportunity: ToolOpportunity?,
        kind: NodeKind = if (toolOpportunity == null) NodeKind.EVIDENCE else NodeKind.TOOL,
        proofMethod: ProofMethod = ProofMethod.EXECUTABLE_TEST,
        executableTestIds: Set<String> = emptySet()
    ) = LimitationNode(
        id = id,
        kind = kind,
        layer = layer,
        parentIds = prerequisites,
        prerequisiteIds = prerequisites,
        receiverWhyRef = "receiver-$id",
        limitationStatement = "Resolve $id.",
        capabilityTarget = "Verified capability for $id.",
        atomicQuestion = "What resolves $id?",
        proofContract = ProofContract(
            method = proofMethod,
            acceptanceCriteria = setOf("Exact authority"),
            requiredEvidenceKinds = setOf(EvidenceKind.EXPERIMENTAL),
            executableTestIds = executableTestIds
        ),
        deliverableContract = NodeDeliverableContract(
            acceptedArtifactTypes = setOf(
                if (toolOpportunity == null) ArtifactType.EVIDENCE_TABLE else ArtifactType.TOOL
            )
        ),
        revealPolicy = RevealPolicy(
            stageId = stageId,
            mode = if (layer == 0) RevealMode.INITIAL else RevealMode.STAGE_PASS
        ),
        replicationPolicy = ReplicationPolicy(),
        capturePolicy = CapturePolicy(minimumGrade = 0.7),
        rewardSpec = RewardSpec(RewardClass.SMALL, 1.0),
        toolOpportunity = toolOpportunity
    ).withComputedPayloadHash()

    private fun stage(
        id: String,
        index: Int,
        nodeIds: Set<NodeId>,
        nodes: Map<NodeId, LimitationNode>
    ) = DisclosureStage(
        stageId = id,
        stageIndex = index,
        title = id,
        nodeIds = nodeIds,
        mandatoryNodeIds = nodeIds,
        evidenceCoverageThreshold = 0.8,
        integrationReadinessThreshold = 0.8,
        revealPayloadHash = AtlasHashing.hashDisclosurePayload(id, nodeIds, nodes)
    )
}
