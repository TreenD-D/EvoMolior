@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.substrate.atlas

import com.achulkov.evomolior.core.atlas.ArtifactType
import com.achulkov.evomolior.core.atlas.AtlasAgentId
import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.CapturePolicy
import com.achulkov.evomolior.core.atlas.DisclosureStage
import com.achulkov.evomolior.core.atlas.EvidenceKind
import com.achulkov.evomolior.core.atlas.FinalOutputSpec
import com.achulkov.evomolior.core.atlas.LimitationNode
import com.achulkov.evomolior.core.atlas.NodeAtlas
import com.achulkov.evomolior.core.atlas.NodeDeliverableContract
import com.achulkov.evomolior.core.atlas.NodeEvidenceCostSummary
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.atlas.NodeKind
import com.achulkov.evomolior.core.atlas.ProofContract
import com.achulkov.evomolior.core.atlas.ProofMethod
import com.achulkov.evomolior.core.atlas.ReceiverContract
import com.achulkov.evomolior.core.atlas.ReplicationPolicy
import com.achulkov.evomolior.core.atlas.RevealMode
import com.achulkov.evomolior.core.atlas.RevealPolicy
import com.achulkov.evomolior.core.atlas.RewardClass
import com.achulkov.evomolior.core.atlas.RewardSpec
import com.achulkov.evomolior.core.atlas.EvidenceProfile
import com.achulkov.evomolior.core.atlas.ToolExecutionPurpose
import com.achulkov.evomolior.core.atlas.ToolExecutionRecord
import com.achulkov.evomolior.core.atlas.ToolExecutionResult
import com.achulkov.evomolior.core.atlas.ToolBehavior
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
import com.achulkov.evomolior.core.contract.CommitType
import com.achulkov.evomolior.core.contract.ContributionCommit
import com.achulkov.evomolior.core.contract.DegradedExecutionRecord
import com.achulkov.evomolior.core.contract.ContributionAuthorityKind
import com.achulkov.evomolior.core.contract.ContributionAuthorityRef
import com.achulkov.evomolior.core.contract.CriticDecisionRecord
import com.achulkov.evomolior.core.contract.CriticOutcome
import com.achulkov.evomolior.core.contract.CriticReview
import com.achulkov.evomolior.core.contract.ContributionRelation
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.db.entity.AtlasVersionEntity
import com.achulkov.evomolior.substrate.db.entity.KnowledgeBaseEntryEntity
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import com.achulkov.evomolior.substrate.db.entity.ToolExecutionRecordEntity
import com.achulkov.evomolior.substrate.db.entity.ToolRegistryEntryEntity
import com.achulkov.evomolior.substrate.tools.ToolInfrastructureRepository
import com.achulkov.evomolior.substrate.worker.WorkerExecutionRepository
import com.achulkov.evomolior.substrate.worker.ContributionGraphRepository
import com.achulkov.evomolior.core.contract.ProvisionalNovelHypothesis
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant

class NodeEvidencePacketSealerTest {
    private val runId = "run-sealer"
    private val groupId = AtlasGroupId("group-sealer")
    private val nodeId = NodeId("node-sealer")
    private val agentId = AtlasAgentId("agent-sealer")
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
    }

    @Test
    fun `sealer creates one deterministic packet from exact promotion and contribution authority`() =
        runTest {
            val harness = harness()
            try {
                val request = request(harness.atlas)

                val first = harness.sealer.seal(request)
                val second = harness.sealer.seal(request)

                assertEquals(first, second)
                assertEquals(listOf("submission-1"), first.acceptedArtifactIds)
                assertEquals(
                    listOf(first),
                    harness.captureRepository.evidencePacketsForNode(runId, nodeId)
                )
                assertEquals(
                    setOf(
                        ContributionRelation.AUTHORED,
                        ContributionRelation.STAGED_AS,
                        ContributionRelation.REVIEWED_AS,
                        ContributionRelation.PROMOTED_TO,
                        ContributionRelation.INCLUDED_IN_EVIDENCE
                    ),
                    harness.groupDatabase.contributionGraphDao()
                        .forRun(runId, groupId.value)
                        .mapTo(linkedSetOf()) { ContributionRelation.valueOf(it.relation) }
                )
            } finally {
                harness.close()
            }
        }

    @Test
    fun `sealer rejects non promoted degraded and tampered node evidence`() = runTest {
        val harness = harness()
        try {
            harness.groupDatabase.stagingDao().updateReview(
                id = "submission-1",
                status = "rejected",
                criticId = "critic-1",
                trustScore = null,
                reason = "rejected",
                reviewedAtMs = 2L
            )
            assertFailsWith<IllegalArgumentException> {
                harness.sealer.seal(request(harness.atlas))
            }

            harness.groupDatabase.stagingDao().updateReview(
                id = "submission-1",
                status = "promoted",
                criticId = "critic-1",
                trustScore = 0.8,
                reason = null,
                reviewedAtMs = 2L
            )
            WorkerExecutionRepository(harness.groupDatabase).recordDegradedExecution(
                DegradedExecutionRecord(
                    recordId = "degraded-1",
                    runId = runId,
                    groupId = groupId,
                    agentId = agentId,
                    nodeId = nodeId,
                    round = 1,
                    reasonCode = "NO_ACTION_FALLBACK",
                    detail = "Not grade-authoritative.",
                    artifactIds = listOf("submission-1"),
                    occurredAt = Instant.parse("2026-07-11T12:00:00Z")
                )
            )
            assertFailsWith<IllegalArgumentException> {
                harness.sealer.seal(request(harness.atlas))
            }
            assertFailsWith<IllegalArgumentException> {
                harness.sealer.seal(
                    request(harness.atlas).copy(nodePayloadHash = "tampered-payload")
                )
            }
        } finally {
            harness.close()
        }
    }

    @Test
    fun `sealer rejects a promoted provisional hypothesis as receiver evidence`() = runTest {
        val harness = harness()
        try {
            val hypothesis = ProvisionalNovelHypothesis(
                hypothesisId = "hypothesis-sealer-1",
                runId = runId,
                groupId = groupId,
                round = 1,
                agentId = agentId,
                title = "Provisional idea",
                idea = "A hidden relation may explain the observed output.",
                inferredFrom = "Observed in the staged artifact.",
                assumptions = listOf("The artifact is representative."),
                uncertainty = "The sample is small.",
                expectedImpact = "A focused test may improve the next attempt.",
                falsifiablePrediction = "A held-out counterexample breaks the relation.",
                minimalTestOrArtifact = "Run the relation check on a held-out case.",
                confidence = 0.25,
                provenanceRefs = listOf("submission:submission-1")
            ).withComputedHash()
            val existing = requireNotNull(harness.groupDatabase.stagingDao().getById("submission-1"))
            harness.groupDatabase.stagingDao().update(
                existing.copy(
                    type = "HYPOTHESIS",
                    content = json.encodeToString(hypothesis),
                    speculative = true,
                    promotedArtifactHash = AtlasHashing.hashText(json.encodeToString(hypothesis))
                )
            )

            assertFailsWith<IllegalArgumentException> {
                harness.sealer.seal(request(harness.atlas))
            }
        } finally {
            harness.close()
        }
    }

    @Test
    fun `sealer includes only persisted successful exact node tool execution`() = runTest {
        val harness = harness(
            toolRunId = "execution-1",
            persistToolRun = true,
            toolAuthorityTarget = ToolAuthorityTarget.EXPECTED_REUSE
        )
        try {
            val packet = harness.sealer.seal(
                request(harness.atlas).copy(toolRunIds = listOf("execution-1"))
            )
            assertEquals(listOf("execution-1"), packet.toolRunIds)
        } finally {
            harness.close()
        }

        val unknownHarness = harness(
            toolRunId = "missing-execution",
            toolAuthorityTarget = ToolAuthorityTarget.EXPECTED_REUSE
        )
        try {
            assertFailsWith<IllegalArgumentException> {
                unknownHarness.sealer.seal(
                    request(unknownHarness.atlas).copy(
                        toolRunIds = listOf("missing-execution")
                    )
                )
            }
        } finally {
            unknownHarness.close()
        }
    }

    @Test
    fun `expected reuse node fails closed until later independent execution is contribution bound`() =
        runTest {
            val missingHarness = harness(
                toolAuthorityTarget = ToolAuthorityTarget.EXPECTED_REUSE
            )
            try {
                assertFailsWith<IllegalArgumentException> {
                    missingHarness.sealer.seal(request(missingHarness.atlas))
                }
            } finally {
                missingHarness.close()
            }

            val satisfiedHarness = harness(
                toolRunId = "execution-expected",
                persistToolRun = true,
                toolAuthorityTarget = ToolAuthorityTarget.EXPECTED_REUSE
            )
            try {
                val packet = satisfiedHarness.sealer.seal(
                    request(satisfiedHarness.atlas).copy(
                        toolRunIds = listOf("execution-expected")
                    )
                )
                assertEquals(listOf("execution-expected"), packet.toolRunIds)
            } finally {
                satisfiedHarness.close()
            }
        }

    @Test
    fun `validation node requires exact validation execution without later-use lifecycle`() =
        runTest {
            val missingHarness = harness(
                toolAuthorityTarget = ToolAuthorityTarget.VALIDATION
            )
            try {
                assertFailsWith<IllegalArgumentException> {
                    missingHarness.sealer.seal(request(missingHarness.atlas))
                }
            } finally {
                missingHarness.close()
            }

            val satisfiedHarness = harness(
                toolRunId = "execution-validation",
                persistToolRun = true,
                toolAuthorityTarget = ToolAuthorityTarget.VALIDATION,
                toolExecutionPurpose = ToolExecutionPurpose.VALIDATION_TEST
            )
            try {
                val packet = satisfiedHarness.sealer.seal(
                    request(satisfiedHarness.atlas).copy(
                        toolRunIds = listOf("execution-validation")
                    )
                )
                assertEquals(listOf("execution-validation"), packet.toolRunIds)
                assertEquals(
                    emptyList(),
                    ToolInfrastructureRepository(satisfiedHarness.groupDatabase)
                        .lifecycle(ToolVersionId("expected-tool-v1"))
                        .filter { it.state == ToolLifecycleState.EXECUTED_BY_LATER_AGENT }
                )
            } finally {
                satisfiedHarness.close()
            }

            val substitutedHarness = harness(
                toolRunId = "execution-wrong-purpose",
                persistToolRun = true,
                toolAuthorityTarget = ToolAuthorityTarget.VALIDATION,
                toolExecutionPurpose = ToolExecutionPurpose.AGENT_USE
            )
            try {
                assertFailsWith<IllegalArgumentException> {
                    substitutedHarness.sealer.seal(
                        request(substitutedHarness.atlas).copy(
                            toolRunIds = listOf("execution-wrong-purpose")
                        )
                    )
                }
            } finally {
                substitutedHarness.close()
            }
        }

    @Test
    fun `tool authoring node rejects generic artifact and accepts exact promoted version`() =
        runTest {
            val genericHarness = harness(authoringTool = true)
            try {
                assertFailsWith<IllegalArgumentException> {
                    genericHarness.sealer.seal(request(genericHarness.atlas))
                }
            } finally {
                genericHarness.close()
            }

            val promotedHarness = harness(
                authoringTool = true,
                activeAuthoringTool = true
            )
            try {
                val packet = promotedHarness.sealer.seal(
                    request(promotedHarness.atlas)
                )
                assertEquals(listOf("submission-1"), packet.acceptedArtifactIds)
            } finally {
                promotedHarness.close()
            }
        }

    @Test
    fun `origin bound promoted and reused authority remains valid after Atlas amendment`() =
        runTest {
            val reuseHarness = harness(
                toolRunId = "execution-amended-reuse",
                persistToolRun = true,
                toolAuthorityTarget = ToolAuthorityTarget.EXPECTED_REUSE,
                amendedAtlas = true
            )
            try {
                val packet = reuseHarness.sealer.seal(
                    request(reuseHarness.atlas).copy(
                        toolRunIds = listOf("execution-amended-reuse")
                    )
                )
                assertEquals(listOf("execution-amended-reuse"), packet.toolRunIds)
            } finally {
                reuseHarness.close()
            }

            val authoringHarness = harness(
                authoringTool = true,
                activeAuthoringTool = true,
                amendedAtlas = true
            )
            try {
                val packet = authoringHarness.sealer.seal(request(authoringHarness.atlas))
                assertEquals(listOf("submission-1"), packet.acceptedArtifactIds)
            } finally {
                authoringHarness.close()
            }
        }

    private suspend fun harness(
        toolRunId: String? = null,
        persistToolRun: Boolean = false,
        toolAuthorityTarget: ToolAuthorityTarget = ToolAuthorityTarget.NONE,
        toolExecutionPurpose: ToolExecutionPurpose? = null,
        authoringTool: Boolean = false,
        activeAuthoringTool: Boolean = false,
        amendedAtlas: Boolean = false
    ): Harness {
        require(!activeAuthoringTool || authoringTool)
        require(!persistToolRun || toolAuthorityTarget != ToolAuthorityTarget.NONE)
        val root = createTempDirectory("node-evidence-sealer-").toFile()
        val factory = SubstrateDatabaseFactory(root)
        val originAtlas = atlas(toolAuthorityTarget, authoringTool)
        val atlas = if (amendedAtlas) {
            originAtlas.copy(
                version = 2,
                parentAtlasHash = originAtlas.atlasHash,
                amendmentIds = listOf("amendment-1"),
                atlasHash = ""
            ).withComputedHash()
        } else {
            originAtlas
        }
        factory.getAtlas(runId).atlasDao().insertVersion(
            AtlasVersionEntity(
                atlasId = originAtlas.atlasId,
                runId = runId,
                version = originAtlas.version,
                receiverContractHash = originAtlas.receiverContractHash,
                receiverContractJson = json.encodeToString(receiver()),
                atlasHash = originAtlas.atlasHash,
                parentAtlasHash = null,
                sealedAtlasJson = json.encodeToString(originAtlas),
                createdAtMs = 1L
            )
        )
        if (amendedAtlas) {
            factory.getAtlas(runId).atlasDao().insertVersion(
                AtlasVersionEntity(
                    atlasId = atlas.atlasId,
                    runId = runId,
                    version = atlas.version,
                    receiverContractHash = atlas.receiverContractHash,
                    receiverContractJson = json.encodeToString(receiver()),
                    atlasHash = atlas.atlasHash,
                    parentAtlasHash = originAtlas.atlasHash,
                    sealedAtlasJson = json.encodeToString(atlas),
                    createdAtMs = 2L
                )
            )
        }
        val groupDatabase = factory.get(runId, groupId.value)
        val submissionContent = if (activeAuthoringTool) {
            """{"name":"authored-tool"}"""
        } else {
            "Exact accepted evidence."
        }
        val promotedArtifactRef = if (activeAuthoringTool) {
            "tool:authored-tool-v1"
        } else {
            "kb:artifact-1"
        }
        groupDatabase.stagingDao().insert(
            StagingSubmissionEntity(
                id = "submission-1",
                agentId = agentId.value,
                role = "Researcher",
                type = if (activeAuthoringTool) "TOOL" else "DOC",
                content = submissionContent,
                wipTaskRef = nodeId.value,
                budgetSignature = "signed",
                status = "promoted",
                criticAgentId = "critic-1",
                initialTrustScore = 0.8,
                generation = 1,
                groupId = groupId.value,
                runId = runId,
                submittedAtMs = 1L,
                reviewedAtMs = 2L,
                promotedArtifactRef = promotedArtifactRef,
                promotedArtifactHash = AtlasHashing.hashText(submissionContent)
            )
        )
        if (!activeAuthoringTool) {
            groupDatabase.knowledgeBaseDao().insert(
                KnowledgeBaseEntryEntity(
                    id = "artifact-1",
                    content = submissionContent,
                    sourceAgentId = agentId.value,
                    generation = 1,
                    domain = "evidence",
                    groupId = groupId.value,
                    runId = runId,
                    createdAtMs = 2L
                )
            )
        }
        if (activeAuthoringTool) {
            prepareAuthoringAuthority(groupDatabase, originAtlas)
        }
        val graphRepository = ContributionGraphRepository(
            database = groupDatabase,
            atlasDatabase = factory.getAtlas(runId)
        )
        graphRepository.recordCriticDecision(
            CriticDecisionRecord(
                decisionId = "decision-1",
                runId = runId,
                groupId = groupId,
                round = 1,
                submissionId = "submission-1",
                submissionContentHash = AtlasHashing.hashText(submissionContent),
                resolvedReview = CriticReview(
                    submissionId = "submission-1",
                    outcome = CriticOutcome.PROMOTE,
                    criticAgentId = "critic-1",
                    initialTrustScore = 0.8
                ),
                resultAuthority = ContributionAuthorityRef(
                    kind = ContributionAuthorityKind.CANONICAL_ARTIFACT,
                    authorityId = promotedArtifactRef,
                    authorityHash = AtlasHashing.hashText(submissionContent)
                ),
                decidedAt = Instant.fromEpochMilliseconds(2L)
            ).withComputedHash()
        )
        if (toolAuthorityTarget != ToolAuthorityTarget.NONE) {
            preparePromotedToolAuthority(groupDatabase, originAtlas)
        }
        if (persistToolRun) {
            val executionPurpose = toolExecutionPurpose ?: when (toolAuthorityTarget) {
                ToolAuthorityTarget.EXPECTED_REUSE -> ToolExecutionPurpose.AGENT_USE
                ToolAuthorityTarget.VALIDATION -> ToolExecutionPurpose.VALIDATION_TEST
                ToolAuthorityTarget.NONE -> error("Tool execution requires an authority target")
            }
            val execution = ToolExecutionRecord(
                executionId = requireNotNull(toolRunId),
                runId = runId,
                toolVersionId = ToolVersionId("expected-tool-v1"),
                opportunityId = "opportunity-1",
                groupId = groupId,
                agentId = agentId,
                round = 1,
                nodeId = nodeId,
                purpose = executionPurpose,
                inputHash = "input-hash",
                outputHash = "output-hash",
                outputJson = kotlinx.serialization.json.JsonPrimitive("output"),
                outputArtifactId = "execution-output:execution-1",
                startedAt = Instant.parse("2026-07-11T10:00:00Z"),
                completedAt = Instant.parse("2026-07-11T10:00:01Z"),
                durationMillis = 1_000,
                exitCode = 0,
                result = ToolExecutionResult.SUCCEEDED,
                sandboxRecordHash = "sandbox-hash"
            ).withComputedHash()
            val persistedCommitment = requireNotNull(
                ToolInfrastructureRepository(groupDatabase).opportunity(execution.opportunityId)
            )
            val executionRepository = ToolInfrastructureRepository(
                database = groupDatabase,
                validationExecutionAuthorities = if (
                    executionPurpose == ToolExecutionPurpose.VALIDATION_TEST
                ) {
                    setOf(
                        ToolValidationExecutionAuthority(
                            runId = runId,
                            groupId = groupId,
                            toolVersionId = execution.toolVersionId,
                            opportunityId = execution.opportunityId,
                            opportunityCommitmentHash = persistedCommitment.commitmentHash,
                            validationNodeId = nodeId
                        )
                    )
                } else {
                    emptySet()
                }
            )
            when {
                toolAuthorityTarget == ToolAuthorityTarget.EXPECTED_REUSE &&
                    executionPurpose == ToolExecutionPurpose.AGENT_USE ->
                    executionRepository.appendSuccessfulAgentExecution(
                    value = execution,
                    lifecycleEventId = "execution-lifecycle-${execution.executionId}",
                    recordedAt = execution.completedAt
                )

                toolAuthorityTarget == ToolAuthorityTarget.VALIDATION &&
                    executionPurpose == ToolExecutionPurpose.VALIDATION_TEST ->
                    executionRepository.appendExecution(execution)

                else -> groupDatabase.toolInfrastructureDao().insertExecution(
                    ToolExecutionRecordEntity(
                        executionId = execution.executionId,
                        runId = execution.runId,
                        opportunityId = execution.opportunityId,
                        toolVersionId = execution.toolVersionId.value,
                        groupId = execution.groupId.value,
                        agentId = execution.agentId.value,
                        round = execution.round,
                        nodeId = execution.nodeId.value,
                        purpose = execution.purpose.name,
                        result = execution.result.name,
                        recordHash = execution.recordHash,
                        contentJson = json.encodeToString(execution),
                        createdAtMs = 1L
                    )
                )
            }
        }
        val persistedContribution =
            WorkerExecutionRepository(groupDatabase).commit(
                contribution(
                    toolRunId = toolRunId,
                    commitType = if (activeAuthoringTool) {
                        CommitType.REUSABLE_TOOL
                    } else {
                        CommitType.POSITIVE_RESULT
                    }
                )
            )
        graphRepository.recordContributionOrigin(persistedContribution)
        val captureRepository = NodeCaptureRepository(factory, now = { 3L })
        return Harness(
            root = root,
            factory = factory,
            groupDatabase = groupDatabase,
            atlas = atlas,
            captureRepository = captureRepository,
            sealer = NodeEvidencePacketSealer(factory, captureRepository)
        )
    }

    private fun request(atlas: NodeAtlas): SealNodeEvidenceRequest =
        SealNodeEvidenceRequest(
            runId = runId,
            round = 1,
            groupId = groupId,
            nodeId = nodeId,
            atlasVersion = atlas.version,
            atlasHash = atlas.atlasHash,
            nodePayloadHash = atlas.nodes.getValue(nodeId).payloadHash,
            acceptedArtifactIds = listOf("submission-1")
        )

    private fun contribution(
        toolRunId: String? = null,
        commitType: CommitType = CommitType.POSITIVE_RESULT
    ): ContributionCommit {
        val preliminary = ContributionCommit(
            commitId = "commit-1",
            runId = runId,
            groupId = groupId,
            round = 1,
            agentId = agentId,
            nodeId = nodeId,
            commitType = commitType,
            artifactIds = listOf("submission-1"),
            claimIds = emptyList(),
            toolRunIds = listOfNotNull(toolRunId),
            testsRun = emptyList(),
            changedStateSummary = "Produced exact accepted evidence.",
            evidenceSummary = "Critic-reviewed document.",
            confidence = 0.8,
            unresolvedRisks = emptyList(),
            successorRecommendations = emptyList(),
            costSummary = NodeEvidenceCostSummary(totalCredits = 2.0),
            dedupeHash = "",
            committedAt = Instant.parse("2026-07-11T11:00:00Z")
        )
        return preliminary.copy(
            dedupeHash = AtlasHashing.hashContributionDedupe(preliminary)
        )
    }

    private suspend fun preparePromotedToolAuthority(
        database: com.achulkov.evomolior.substrate.db.EvoMoliorDatabase,
        atlas: NodeAtlas
    ) {
        val originNodeId = NodeId("node-tool-origin")
        val opportunity = requireNotNull(atlas.nodes.getValue(originNodeId).toolOpportunity)
        val repository = ToolInfrastructureRepository(database)
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
                eventId = "event-${state.name}",
                runId = runId,
                opportunityId = opportunity.opportunityId,
                toolVersionId = ToolVersionId("expected-tool-v1"),
                groupId = groupId,
                originNodeId = originNodeId,
                contextNodeId = originNodeId,
                state = state,
                sequence = (previous?.sequence ?: -1L) + 1,
                previousEventHash = previous?.eventHash,
                authorityReferenceIds = references,
                recordedAt = Instant.parse("2026-07-11T09:00:00Z")
            ).withComputedHash()
            repository.appendLifecycle(event)
            previous = event
        }
        append(ToolLifecycleState.TOOL_SPEC, setOf(commitment.commitmentHash))
        append(ToolLifecycleState.IMPLEMENTED, setOf("implementation"))
        append(ToolLifecycleState.STATIC_VALIDATED, setOf("static-validation"))
        val promotionExecution = ToolExecutionRecord(
            executionId = "promotion-execution",
            runId = runId,
            toolVersionId = ToolVersionId("expected-tool-v1"),
            opportunityId = opportunity.opportunityId,
            groupId = groupId,
            agentId = AtlasAgentId("agent-tool-author"),
            round = 0,
            nodeId = originNodeId,
            purpose = ToolExecutionPurpose.PROMOTION_TEST,
            inputHash = "promotion-input",
            outputHash = "promotion-output",
            outputJson = kotlinx.serialization.json.JsonPrimitive("passed"),
            outputArtifactId = "promotion-output-artifact",
            startedAt = Instant.parse("2026-07-11T09:00:00Z"),
            completedAt = Instant.parse("2026-07-11T09:00:01Z"),
            durationMillis = 1_000,
            exitCode = 0,
            result = ToolExecutionResult.SUCCEEDED,
            sandboxRecordHash = "promotion-sandbox"
        ).withComputedHash()
        repository.appendExecution(promotionExecution)
        append(ToolLifecycleState.SANDBOX_TESTED, setOf(promotionExecution.executionId))
        append(ToolLifecycleState.CRITIC_ACCEPTED, setOf("critic-review"))
        append(ToolLifecycleState.PROMOTED, setOf("expected-tool-v1"))
        database.toolRegistryDao().insert(
            ToolRegistryEntryEntity(
                id = "expected-tool-v1",
                name = "expected-tool",
                version = 1,
                authorAgentId = "agent-tool-author",
                inputSchemaJson = "{}",
                outputSchemaJson = "{}",
                domain = "test",
                status = "active",
                code = "echo",
                codeLanguage = "shell",
                generation = 0,
                groupId = groupId.value,
                runId = runId,
                createdAtMs = 1L
            )
        )
    }

    private suspend fun prepareAuthoringAuthority(
        database: com.achulkov.evomolior.substrate.db.EvoMoliorDatabase,
        atlas: NodeAtlas
    ) {
        val opportunity = requireNotNull(atlas.nodes.getValue(nodeId).toolOpportunity)
        val repository = ToolInfrastructureRepository(database)
        val commitment = ToolOpportunityCommitment(
            runId = runId,
            atlasHash = atlas.atlasHash,
            nodeId = nodeId,
            opportunity = opportunity
        ).withComputedHash()
        repository.appendOpportunity(commitment)
        var previous: ToolLifecycleEvent? = null
        suspend fun append(state: ToolLifecycleState, references: Set<String>) {
            val event = ToolLifecycleEvent(
                eventId = "authoring-event-${state.name}",
                runId = runId,
                opportunityId = opportunity.opportunityId,
                toolVersionId = ToolVersionId("authored-tool-v1"),
                groupId = groupId,
                originNodeId = nodeId,
                contextNodeId = nodeId,
                state = state,
                sequence = (previous?.sequence ?: -1L) + 1,
                previousEventHash = previous?.eventHash,
                authorityReferenceIds = references,
                recordedAt = Instant.parse("2026-07-11T09:00:00Z")
            ).withComputedHash()
            repository.appendLifecycle(event)
            previous = event
        }
        append(ToolLifecycleState.TOOL_SPEC, setOf(commitment.commitmentHash))
        append(ToolLifecycleState.IMPLEMENTED, setOf("implementation"))
        append(
            ToolLifecycleState.STATIC_VALIDATED,
            opportunity.promotionTestIds
        )
        val promotionExecution = ToolExecutionRecord(
            executionId = "authoring-promotion-execution",
            runId = runId,
            toolVersionId = ToolVersionId("authored-tool-v1"),
            opportunityId = opportunity.opportunityId,
            groupId = groupId,
            agentId = AtlasAgentId("critic-1"),
            round = 1,
            nodeId = nodeId,
            purpose = ToolExecutionPurpose.PROMOTION_TEST,
            inputHash = "promotion-input",
            outputHash = "promotion-output",
            outputJson = kotlinx.serialization.json.JsonPrimitive("passed"),
            outputArtifactId = "promotion-output-artifact",
            startedAt = Instant.parse("2026-07-11T09:00:00Z"),
            completedAt = Instant.parse("2026-07-11T09:00:01Z"),
            durationMillis = 1_000,
            exitCode = 0,
            result = ToolExecutionResult.SUCCEEDED,
            sandboxRecordHash = "promotion-sandbox"
        ).withComputedHash()
        repository.appendExecution(promotionExecution)
        append(
            ToolLifecycleState.SANDBOX_TESTED,
            setOf(promotionExecution.executionId)
        )
        append(
            ToolLifecycleState.CRITIC_ACCEPTED,
            setOf("critic-1", "submission-1")
        )
        database.toolRegistryDao().insert(
            ToolRegistryEntryEntity(
                id = "authored-tool-v1",
                name = "authored-tool",
                version = 1,
                authorAgentId = agentId.value,
                inputSchemaJson = "{}",
                outputSchemaJson = "{}",
                domain = "test",
                status = "active",
                code = "echo",
                codeLanguage = "shell",
                generation = 1,
                groupId = groupId.value,
                runId = runId,
                createdAtMs = 1L
            )
        )
        append(ToolLifecycleState.PROMOTED, setOf("authored-tool-v1"))
    }

    private fun atlas(
        toolAuthorityTarget: ToolAuthorityTarget = ToolAuthorityTarget.NONE,
        authoringTool: Boolean = false
    ): NodeAtlas {
        if (authoringTool) {
            return authoringAtlas()
        }
        val receiver = receiver()
        val originId = NodeId("node-tool-origin")
        val validationTarget = toolAuthorityTarget == ToolAuthorityTarget.VALIDATION
        val node = LimitationNode(
            id = nodeId,
            kind = if (validationTarget) NodeKind.VALIDATION else NodeKind.EVIDENCE,
            layer = if (toolAuthorityTarget != ToolAuthorityTarget.NONE) 1 else 0,
            parentIds = if (toolAuthorityTarget != ToolAuthorityTarget.NONE) {
                setOf(originId)
            } else {
                emptySet()
            },
            prerequisiteIds = if (toolAuthorityTarget != ToolAuthorityTarget.NONE) {
                setOf(originId)
            } else {
                emptySet()
            },
            receiverWhyRef = "receiver-outcome",
            limitationStatement = "Evidence is missing.",
            capabilityTarget = "Establish exact evidence.",
            atomicQuestion = "What evidence resolves the limitation?",
            proofContract = ProofContract(
                method = if (validationTarget) {
                    ProofMethod.REPRODUCTION
                } else {
                    ProofMethod.SOURCE_CORROBORATION
                },
                acceptanceCriteria = setOf("Exact evidence"),
                executableTestIds = if (validationTarget) {
                    setOf("validation-case")
                } else {
                    emptySet()
                }
            ),
            deliverableContract = NodeDeliverableContract(
                acceptedArtifactTypes = setOf(
                    if (validationTarget) {
                        ArtifactType.VALIDATION_REPORT
                    } else {
                        ArtifactType.EVIDENCE_TABLE
                    }
                )
            ),
            revealPolicy = RevealPolicy(
                if (toolAuthorityTarget != ToolAuthorityTarget.NONE) "stage-1" else "stage-0",
                if (toolAuthorityTarget != ToolAuthorityTarget.NONE) {
                    RevealMode.STAGE_PASS
                } else {
                    RevealMode.INITIAL
                }
            ),
            replicationPolicy = ReplicationPolicy(),
            capturePolicy = CapturePolicy(minimumGrade = 0.7),
            rewardSpec = RewardSpec(RewardClass.SMALL, 1.0)
        ).withComputedPayloadHash()
        val validationExpectedReuseNodeId = NodeId("node-validation-expected-reuse")
        val originNode = if (toolAuthorityTarget != ToolAuthorityTarget.NONE) {
            LimitationNode(
                id = originId,
                kind = NodeKind.TOOL,
                layer = 0,
                parentIds = emptySet(),
                prerequisiteIds = emptySet(),
                receiverWhyRef = "receiver-tool",
                limitationStatement = "Repeated calculation is manual.",
                capabilityTarget = "Promote one exact calculator.",
                atomicQuestion = "What exact calculator removes repeated work?",
                proofContract = ProofContract(
                    method = ProofMethod.EXECUTABLE_TEST,
                    acceptanceCriteria = setOf("Exact promotion tests pass"),
                    requiredEvidenceKinds = setOf(EvidenceKind.EXPERIMENTAL)
                ),
                deliverableContract = NodeDeliverableContract(
                    acceptedArtifactTypes = setOf(ArtifactType.TOOL)
                ),
                revealPolicy = RevealPolicy("stage-0", RevealMode.INITIAL),
                replicationPolicy = ReplicationPolicy(),
                capturePolicy = CapturePolicy(minimumGrade = 0.7),
                rewardSpec = RewardSpec(RewardClass.SMALL, 1.0),
                toolOpportunity = ToolOpportunity(
                    opportunityId = "opportunity-1",
                    opportunityType = ToolOpportunityType.CALCULATION,
                    repeatedQuestionPattern = "Calculate one exact downstream value.",
                    requiredInputs = listOf(
                        TypedField("value", TypedFieldType.NUMBER, "Input value.")
                    ),
                    requiredOutputs = listOf(
                        TypedField("result", TypedFieldType.NUMBER, "Calculated result.")
                    ),
                    validationStrategy = "Run the exact committed case.",
                    expectedReuseNodeIds = setOf(
                        if (validationTarget) validationExpectedReuseNodeId else nodeId
                    ),
                    preferredImplementationMode = ToolImplementationMode.SHELL,
                    behavior = ToolBehavior.DETERMINISTIC,
                    promotionTestIds = setOf("exact-case"),
                    maximumRuntimeMillis = 1_000,
                    maximumOutputBytes = 4_096
                )
            ).withComputedPayloadHash()
        } else {
            null
        }
        val validationExpectedReuseNode = if (validationTarget) {
            LimitationNode(
                id = validationExpectedReuseNodeId,
                kind = NodeKind.EVIDENCE,
                layer = 2,
                parentIds = setOf(originId, nodeId),
                prerequisiteIds = setOf(originId, nodeId),
                receiverWhyRef = "receiver-later-reuse",
                limitationStatement = "Validated output has not been used downstream.",
                capabilityTarget = "Use the exact validated promoted calculator.",
                atomicQuestion = "What downstream result follows from the promoted calculator?",
                proofContract = ProofContract(
                    method = ProofMethod.EXECUTABLE_TEST,
                    acceptanceCriteria = setOf("Exact later execution")
                ),
                deliverableContract = NodeDeliverableContract(
                    acceptedArtifactTypes = setOf(ArtifactType.EVIDENCE_TABLE)
                ),
                revealPolicy = RevealPolicy("stage-2", RevealMode.STAGE_PASS),
                replicationPolicy = ReplicationPolicy(),
                capturePolicy = CapturePolicy(minimumGrade = 0.7),
                rewardSpec = RewardSpec(RewardClass.SMALL, 1.0)
            ).withComputedPayloadHash()
        } else {
            null
        }
        val nodes = listOfNotNull(originNode, node, validationExpectedReuseNode)
            .associateBy(LimitationNode::id)
        val initialNodeIds = originNode?.let { setOf(it.id) } ?: setOf(nodeId)
        val stages = if (originNode == null) {
            listOf(
                DisclosureStage(
                    stageId = "stage-0",
                    stageIndex = 0,
                    title = "Initial",
                    nodeIds = setOf(nodeId),
                    mandatoryNodeIds = setOf(nodeId),
                    evidenceCoverageThreshold = 0.8,
                    integrationReadinessThreshold = 0.7,
                    revealPayloadHash = AtlasHashing.hashDisclosurePayload(
                        "stage-0",
                        setOf(nodeId),
                        nodes
                    )
                )
            )
        } else if (validationExpectedReuseNode == null) {
            listOf(
                DisclosureStage(
                    stageId = "stage-0",
                    stageIndex = 0,
                    title = "Tool",
                    nodeIds = setOf(originNode.id),
                    mandatoryNodeIds = setOf(originNode.id),
                    evidenceCoverageThreshold = 0.8,
                    integrationReadinessThreshold = 0.7,
                    revealPayloadHash = AtlasHashing.hashDisclosurePayload(
                        "stage-0",
                        setOf(originNode.id),
                        nodes
                    )
                ),
                DisclosureStage(
                    stageId = "stage-1",
                    stageIndex = 1,
                    title = "Reuse",
                    nodeIds = setOf(nodeId),
                    mandatoryNodeIds = setOf(nodeId),
                    evidenceCoverageThreshold = 0.8,
                    integrationReadinessThreshold = 0.7,
                    revealPayloadHash = AtlasHashing.hashDisclosurePayload(
                        "stage-1",
                        setOf(nodeId),
                        nodes
                    )
                )
            )
        } else {
            listOf(
                DisclosureStage(
                    stageId = "stage-0",
                    stageIndex = 0,
                    title = "Tool",
                    nodeIds = setOf(originNode.id),
                    mandatoryNodeIds = setOf(originNode.id),
                    evidenceCoverageThreshold = 0.8,
                    integrationReadinessThreshold = 0.7,
                    revealPayloadHash = AtlasHashing.hashDisclosurePayload(
                        "stage-0",
                        setOf(originNode.id),
                        nodes
                    )
                ),
                DisclosureStage(
                    stageId = "stage-1",
                    stageIndex = 1,
                    title = "Validation",
                    nodeIds = setOf(nodeId),
                    mandatoryNodeIds = setOf(nodeId),
                    evidenceCoverageThreshold = 0.8,
                    integrationReadinessThreshold = 0.7,
                    revealPayloadHash = AtlasHashing.hashDisclosurePayload(
                        "stage-1",
                        setOf(nodeId),
                        nodes
                    )
                ),
                DisclosureStage(
                    stageId = "stage-2",
                    stageIndex = 2,
                    title = "Reuse",
                    nodeIds = setOf(validationExpectedReuseNode.id),
                    mandatoryNodeIds = setOf(validationExpectedReuseNode.id),
                    evidenceCoverageThreshold = 0.8,
                    integrationReadinessThreshold = 0.7,
                    revealPayloadHash = AtlasHashing.hashDisclosurePayload(
                        "stage-2",
                        setOf(validationExpectedReuseNode.id),
                        nodes
                    )
                )
            )
        }
        return NodeAtlas(
            atlasId = "atlas-sealer",
            runId = runId,
            version = 1,
            receiverContractHash = AtlasHashing.hashReceiverContract(receiver),
            rootNodeIds = initialNodeIds,
            nodes = nodes,
            stageDefinitions = stages
        ).withComputedHash()
    }

    private fun authoringAtlas(): NodeAtlas {
        val receiver = receiver()
        val laterNodeId = NodeId("node-authoring-later")
        val opportunity = ToolOpportunity(
            opportunityId = "opportunity-authoring",
            opportunityType = ToolOpportunityType.CALCULATION,
            repeatedQuestionPattern = "Calculate one exact downstream value.",
            requiredInputs = listOf(
                TypedField("value", TypedFieldType.NUMBER, "Input value.")
            ),
            requiredOutputs = listOf(
                TypedField("result", TypedFieldType.NUMBER, "Calculated result.")
            ),
            validationStrategy = "Run the exact committed case.",
            expectedReuseNodeIds = setOf(laterNodeId),
            preferredImplementationMode = ToolImplementationMode.SHELL,
            behavior = ToolBehavior.DETERMINISTIC,
            promotionTestIds = setOf("exact-case"),
            maximumRuntimeMillis = 1_000,
            maximumOutputBytes = 4_096
        )
        val toolNode = LimitationNode(
            id = nodeId,
            kind = NodeKind.TOOL,
            layer = 0,
            parentIds = emptySet(),
            prerequisiteIds = emptySet(),
            receiverWhyRef = "receiver-tool",
            limitationStatement = "Repeated calculation is manual.",
            capabilityTarget = "Promote one exact calculator.",
            atomicQuestion = "What exact calculator removes repeated work?",
            proofContract = ProofContract(
                method = ProofMethod.EXECUTABLE_TEST,
                acceptanceCriteria = setOf("Exact promotion tests pass"),
                requiredEvidenceKinds = setOf(EvidenceKind.EXPERIMENTAL)
            ),
            deliverableContract = NodeDeliverableContract(
                acceptedArtifactTypes = setOf(ArtifactType.TOOL)
            ),
            revealPolicy = RevealPolicy("stage-0", RevealMode.INITIAL),
            replicationPolicy = ReplicationPolicy(),
            capturePolicy = CapturePolicy(minimumGrade = 0.7),
            rewardSpec = RewardSpec(RewardClass.SMALL, 1.0),
            toolOpportunity = opportunity
        ).withComputedPayloadHash()
        val laterNode = LimitationNode(
            id = laterNodeId,
            kind = NodeKind.EVIDENCE,
            layer = 1,
            parentIds = setOf(nodeId),
            prerequisiteIds = setOf(nodeId),
            receiverWhyRef = "receiver-outcome",
            limitationStatement = "Downstream evidence is missing.",
            capabilityTarget = "Use the exact promoted calculator.",
            atomicQuestion = "What result follows from the promoted calculator?",
            proofContract = ProofContract(
                method = ProofMethod.EXECUTABLE_TEST,
                acceptanceCriteria = setOf("Exact later execution")
            ),
            deliverableContract = NodeDeliverableContract(
                acceptedArtifactTypes = setOf(ArtifactType.EVIDENCE_TABLE)
            ),
            revealPolicy = RevealPolicy("stage-1", RevealMode.STAGE_PASS),
            replicationPolicy = ReplicationPolicy(),
            capturePolicy = CapturePolicy(minimumGrade = 0.7),
            rewardSpec = RewardSpec(RewardClass.SMALL, 1.0)
        ).withComputedPayloadHash()
        val nodes = listOf(toolNode, laterNode).associateBy(LimitationNode::id)
        val stages = listOf(
            DisclosureStage(
                stageId = "stage-0",
                stageIndex = 0,
                title = "Tool",
                nodeIds = setOf(nodeId),
                mandatoryNodeIds = setOf(nodeId),
                evidenceCoverageThreshold = 0.8,
                integrationReadinessThreshold = 0.7,
                revealPayloadHash = AtlasHashing.hashDisclosurePayload(
                    "stage-0",
                    setOf(nodeId),
                    nodes
                )
            ),
            DisclosureStage(
                stageId = "stage-1",
                stageIndex = 1,
                title = "Reuse",
                nodeIds = setOf(laterNodeId),
                mandatoryNodeIds = setOf(laterNodeId),
                evidenceCoverageThreshold = 0.8,
                integrationReadinessThreshold = 0.7,
                revealPayloadHash = AtlasHashing.hashDisclosurePayload(
                    "stage-1",
                    setOf(laterNodeId),
                    nodes
                )
            )
        )
        return NodeAtlas(
            atlasId = "atlas-authoring-sealer",
            runId = runId,
            version = 1,
            receiverContractHash = AtlasHashing.hashReceiverContract(receiver),
            rootNodeIds = setOf(nodeId),
            nodes = nodes,
            stageDefinitions = stages
        ).withComputedHash()
    }

    private fun receiver(): ReceiverContract = ReceiverContract(
        rawObjective = "Resolve the limitation.",
        receiverId = "receiver-1",
        receiverDescription = "Operator",
        situation = "Decision",
        decisionOrUse = "Select evidence",
        whyNeeded = "Reduce uncertainty",
        successOutcomes = listOf("Verified"),
        unacceptableOutcomes = listOf("Unsupported"),
        constraints = listOf("Auditable"),
        finalDeliverableContract = FinalOutputSpec(
            relativePath = "result.json",
            mediaType = "application/json"
        ),
        evidenceProfile = EvidenceProfile(
            acceptedEvidenceKinds = setOf(EvidenceKind.PRIMARY_SOURCE)
        )
    )

    private data class Harness(
        val root: File,
        val factory: SubstrateDatabaseFactory,
        val groupDatabase: com.achulkov.evomolior.substrate.db.EvoMoliorDatabase,
        val atlas: NodeAtlas,
        val captureRepository: NodeCaptureRepository,
        val sealer: NodeEvidencePacketSealer
    ) {
        fun close() {
            groupDatabase.close()
            factory.getAtlas(atlas.runId).close()
            root.deleteRecursively()
        }
    }

    private enum class ToolAuthorityTarget {
        NONE,
        EXPECTED_REUSE,
        VALIDATION
    }
}
