@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.competition.coordinator.GroupCoordinator
import com.achulkov.evomolior.competition.grader.GraderGroupManager
import com.achulkov.evomolior.core.atlas.AtlasAgentId
import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.NodeEvidenceCostSummary
import com.achulkov.evomolior.core.atlas.NodeEvidencePacket
import com.achulkov.evomolior.core.atlas.NodeId
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
import com.achulkov.evomolior.core.atlas.ToolVersionId
import com.achulkov.evomolior.core.atlas.TypedField
import com.achulkov.evomolior.core.atlas.TypedFieldType
import com.achulkov.evomolior.core.contract.CommitType
import com.achulkov.evomolior.core.contract.ContributionAuthorityKind
import com.achulkov.evomolior.core.contract.ContributionAuthorityRef
import com.achulkov.evomolior.core.contract.ContributionCommit
import com.achulkov.evomolior.core.contract.ContributionRelation
import com.achulkov.evomolior.core.contract.CriticDecisionRecord
import com.achulkov.evomolior.core.contract.CriticOutcome
import com.achulkov.evomolior.core.contract.CriticReview
import com.achulkov.evomolior.orchestration.metaplanner.MetaPlannerRunner
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import com.achulkov.evomolior.substrate.db.entity.ToolRegistryEntryEntity
import com.achulkov.evomolior.substrate.tools.ToolInfrastructureRepository
import com.achulkov.evomolior.substrate.worker.ContributionGraphRepository
import com.achulkov.evomolior.substrate.worker.WorkerExecutionRepository
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Instant

class ToolReuseAttributionAcceptanceTest {
    @Test
    fun `sealed later-node execution produces reuse lifecycle and delayed fitness`() = runTest {
        val runId = "run-reuse-attribution"
        val groupId = AtlasGroupId("group-reuse")
        val originNodeId = NodeId("node-origin")
        val laterNodeId = NodeId("node-later")
        val versionId = ToolVersionId("calculator-v1")
        val agentId = AtlasAgentId("agent-later")
        val at = Instant.parse("2026-01-01T00:00:00Z")
        val factory = SubstrateDatabaseFactory(
            createTempDirectory("tool-reuse-attribution-").toFile()
        )
        val database = factory.get(runId, groupId.value)
        val repository = ToolInfrastructureRepository(database)
        val opportunity = ToolOpportunityCommitment(
            runId = runId,
            atlasHash = "atlas-hash",
            nodeId = originNodeId,
            opportunity = ToolOpportunity(
                opportunityId = "opportunity-calculate",
                opportunityType = ToolOpportunityType.CALCULATION,
                repeatedQuestionPattern = "Calculate one repeated normalized value.",
                requiredInputs = listOf(
                    TypedField("value", TypedFieldType.NUMBER, "Input value.")
                ),
                requiredOutputs = listOf(
                    TypedField("result", TypedFieldType.NUMBER, "Calculated result.")
                ),
                validationStrategy = "Run exact committed values.",
                expectedReuseNodeIds = setOf(laterNodeId),
                preferredImplementationMode = ToolImplementationMode.SHELL,
                behavior = ToolBehavior.DETERMINISTIC,
                promotionTestIds = setOf("identity-test"),
                maximumRuntimeMillis = 1_000,
                maximumOutputBytes = 4_096,
                assumptions = listOf("Input is finite.")
            )
        ).withComputedHash()
        repository.appendOpportunity(opportunity)

        var previous: ToolLifecycleEvent? = null
        suspend fun append(state: ToolLifecycleState, references: Set<String>) {
            val event = ToolLifecycleEvent(
                eventId = "event-${state.name}",
                runId = runId,
                opportunityId = opportunity.opportunity.opportunityId,
                toolVersionId = versionId,
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
        append(ToolLifecycleState.TOOL_SPEC, setOf(opportunity.commitmentHash))
        append(ToolLifecycleState.IMPLEMENTED, setOf("implementation"))
        append(ToolLifecycleState.STATIC_VALIDATED, setOf("static-validation"))
        val promotionExecution = execution(
            id = "promotion-execution",
            runId = runId,
            versionId = versionId,
            opportunityId = opportunity.opportunity.opportunityId,
            groupId = groupId,
            agentId = AtlasAgentId("critic"),
            nodeId = originNodeId,
            purpose = ToolExecutionPurpose.PROMOTION_TEST,
            round = 0,
            at = at
        )
        repository.appendExecution(promotionExecution)
        append(ToolLifecycleState.SANDBOX_TESTED, setOf(promotionExecution.executionId))
        append(ToolLifecycleState.CRITIC_ACCEPTED, setOf("critic-review"))
        append(ToolLifecycleState.PROMOTED, setOf(versionId.value))
        val toolSubmission = StagingSubmissionEntity(
            id = "tool-submission",
            agentId = "agent-author",
            role = "Builder",
            type = "TOOL",
            content = "exact tool source",
            wipTaskRef = originNodeId.value,
            budgetSignature = "budget-signature",
            generation = 0,
            groupId = groupId.value,
            runId = runId,
            submittedAtMs = at.toEpochMilliseconds()
        )
        database.stagingDao().insert(toolSubmission)
        database.toolRegistryDao().insert(
            ToolRegistryEntryEntity(
                id = versionId.value,
                name = "calculator",
                version = 1,
                authorAgentId = toolSubmission.agentId,
                inputSchemaJson = "{}",
                outputSchemaJson = "{}",
                domain = "test",
                status = "active",
                code = "echo",
                codeLanguage = "shell",
                generation = 0,
                groupId = groupId.value,
                runId = runId,
                createdAtMs = at.toEpochMilliseconds()
            )
        )
        ContributionGraphRepository(database).recordCriticDecision(
            CriticDecisionRecord(
                decisionId = "tool-critic-decision",
                runId = runId,
                groupId = groupId,
                round = 0,
                submissionId = toolSubmission.id,
                submissionContentHash = AtlasHashing.hashText(toolSubmission.content),
                resolvedReview = CriticReview(
                    submissionId = toolSubmission.id,
                    outcome = CriticOutcome.PROMOTE,
                    criticAgentId = "critic",
                    initialTrustScore = 0.9
                ),
                resultAuthority = ContributionAuthorityRef(
                    kind = ContributionAuthorityKind.CANONICAL_ARTIFACT,
                    authorityId = "tool:${versionId.value}",
                    authorityHash = AtlasHashing.hashText(toolSubmission.content)
                ),
                decidedAt = at
            ).withComputedHash()
        )

        val laterExecution = execution(
            id = "later-execution",
            runId = runId,
            versionId = versionId,
            opportunityId = opportunity.opportunity.opportunityId,
            groupId = groupId,
            agentId = agentId,
            nodeId = laterNodeId,
            purpose = ToolExecutionPurpose.AGENT_USE,
            round = 2,
            at = at
        )
        repository.appendSuccessfulAgentExecution(
            laterExecution,
            lifecycleEventId = "later-execution-lifecycle",
            recordedAt = at
        )
        val contribution = ContributionCommit(
            commitId = "contribution-later",
            runId = runId,
            groupId = groupId,
            round = 2,
            agentId = agentId,
            nodeId = laterNodeId,
            commitType = CommitType.POSITIVE_RESULT,
            artifactIds = emptyList(),
            claimIds = emptyList(),
            toolRunIds = listOf(laterExecution.executionId),
            testsRun = emptyList(),
            changedStateSummary = "Executed the promoted exact version.",
            evidenceSummary = "The bounded output supports the later node.",
            confidence = 0.9,
            unresolvedRisks = emptyList(),
            successorRecommendations = emptyList(),
            costSummary = NodeEvidenceCostSummary(
                totalCredits = 2.0,
                toolExecutions = 1
            ),
            dedupeHash = "",
            committedAt = at
        ).let { it.copy(dedupeHash = AtlasHashing.hashContributionDedupe(it)) }
        WorkerExecutionRepository(database).commit(contribution)
        val packet = NodeEvidencePacket(
            packetId = "packet-later",
            runId = runId,
            round = 2,
            groupId = groupId,
            nodeId = laterNodeId,
            atlasVersion = 1,
            atlasHash = "atlas-hash",
            nodePayloadHash = "node-payload-hash",
            toolRunIds = listOf(laterExecution.executionId),
            provenanceRootHash = "provenance-root",
            cost = requireNotNull(contribution.costSummary),
            sealedAtEpochMillis = at.toEpochMilliseconds()
        ).withComputedHash()
        val service = EvoMoliorRunService(
            lifecycleManagerProvider = { _, _, _, _, _ -> error("No lifecycle in focused test") },
            groupCoordinatorProvider = { groups, dimensions -> GroupCoordinator(groups, dimensions) },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = factory,
            metaPlannerRunner = MetaPlannerRunner()
        )

        service.appendToolReuseAttribution(
            runId = runId,
            round = 2,
            packet = packet,
            currentScore = 0.8,
            priorScore = 0.5
        )

        val reuse = repository.reuseForVersion(versionId).single()
        assertEquals(laterExecution.executionId, reuse.executionId)
        assertEquals(contribution.commitId, reuse.contributionCommitId)
        assertEquals(packet.packetHash, reuse.nodeEvidencePacketHash)
        assertEquals(
            ToolLifecycleState.RESULT_REFERENCED_BY_NODE,
            repository.lifecycle(versionId).last().state
        )
        val downstreamExecutionEdge = ContributionGraphRepository(database)
            .forRun(runId, groupId.value)
            .single { it.relation == ContributionRelation.EXECUTED_DOWNSTREAM }
        assertEquals("tool:${versionId.value}", downstreamExecutionEdge.source.authorityId)
        assertEquals(laterExecution.executionId, downstreamExecutionEdge.target.authorityId)
        val fitness = repository.fitnessForVersion(versionId).single()
        assertEquals(setOf(reuse.lineageId), fitness.reuseLineageIds)
        assertEquals(0.3, fitness.nodeScoreDeltaAttributedToResults, 1e-9)
    }

    private fun execution(
        id: String,
        runId: String,
        versionId: ToolVersionId,
        opportunityId: String,
        groupId: AtlasGroupId,
        agentId: AtlasAgentId,
        nodeId: NodeId,
        purpose: ToolExecutionPurpose,
        round: Int,
        at: Instant
    ) = ToolExecutionRecord(
        executionId = id,
        runId = runId,
        toolVersionId = versionId,
        opportunityId = opportunityId,
        groupId = groupId,
        agentId = agentId,
        round = round,
        nodeId = nodeId,
        purpose = purpose,
        inputHash = "input-$id",
        outputHash = "output-$id",
        outputJson = JsonPrimitive(id),
        outputArtifactId = "artifact-$id",
        startedAt = at,
        completedAt = at,
        durationMillis = 0,
        exitCode = 0,
        result = ToolExecutionResult.SUCCEEDED,
        sandboxRecordHash = "sandbox-$id"
    ).withComputedHash()
}
