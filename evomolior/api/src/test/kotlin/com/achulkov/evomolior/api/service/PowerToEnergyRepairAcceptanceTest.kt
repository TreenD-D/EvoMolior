@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.api.service

import com.achulkov.evomolior.agents.tools.ExecuteToolTool
import com.achulkov.evomolior.api.grader.GraderRunner
import com.achulkov.evomolior.api.initializeTestWorkerAtlas
import com.achulkov.evomolior.competition.atlas.AtlasNodeCaptureAuthority
import com.achulkov.evomolior.competition.coordinator.GroupCoordinator
import com.achulkov.evomolior.competition.grader.GraderGroupManager
import com.achulkov.evomolior.competition.scoring.NodeCaptureResolutionRequest
import com.achulkov.evomolior.competition.scoring.NodeCaptureResolver
import com.achulkov.evomolior.core.atlas.AtlasAgentId
import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.NodeCaptureDecisionType
import com.achulkov.evomolior.core.atlas.NodeEvidenceCostSummary
import com.achulkov.evomolior.core.atlas.NodeEvidencePacket
import com.achulkov.evomolior.core.atlas.NodeGradeBundleVerdict
import com.achulkov.evomolior.core.atlas.NodeGraderResponse
import com.achulkov.evomolior.core.atlas.NodeGraderResponseVerdict
import com.achulkov.evomolior.core.atlas.NodeGradingAssignment
import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.atlas.NodeScoreVector
import com.achulkov.evomolior.core.atlas.PhysicalDimension
import com.achulkov.evomolior.core.atlas.SimulationRecordBindings
import com.achulkov.evomolior.core.atlas.ToolBehavior
import com.achulkov.evomolior.core.atlas.ToolCandidateWireCompilation
import com.achulkov.evomolior.core.atlas.ToolCandidateWireDraft
import com.achulkov.evomolior.core.atlas.ToolImplementationMode
import com.achulkov.evomolior.core.atlas.ToolLifecycleState
import com.achulkov.evomolior.core.atlas.ToolOpportunity
import com.achulkov.evomolior.core.atlas.ToolOpportunityBinding
import com.achulkov.evomolior.core.atlas.ToolOpportunityBindingCapability
import com.achulkov.evomolior.core.atlas.ToolOpportunityBindingCatalog
import com.achulkov.evomolior.core.atlas.ToolOpportunityCommitment
import com.achulkov.evomolior.core.atlas.ToolOpportunityType
import com.achulkov.evomolior.core.atlas.ToolProjectSkeletonCompiler
import com.achulkov.evomolior.core.atlas.ToolRuntimeKind
import com.achulkov.evomolior.core.atlas.ToolVersionId
import com.achulkov.evomolior.core.atlas.TypedField
import com.achulkov.evomolior.core.atlas.TypedFieldType
import com.achulkov.evomolior.core.budget.BudgetConfig
import com.achulkov.evomolior.core.budget.BudgetLedger
import com.achulkov.evomolior.core.contract.AgentExecutionPhase
import com.achulkov.evomolior.core.contract.AgentExitRecord
import com.achulkov.evomolior.core.contract.AgentLifecycleClassification
import com.achulkov.evomolior.core.contract.AgentLifecycleRegistration
import com.achulkov.evomolior.core.contract.CommitType
import com.achulkov.evomolior.core.contract.ContributionAuthorityKind
import com.achulkov.evomolior.core.contract.ContributionAuthorityRef
import com.achulkov.evomolior.core.contract.ContributionCommit
import com.achulkov.evomolior.core.contract.CriticDecisionRecord
import com.achulkov.evomolior.core.contract.CriticOutcome
import com.achulkov.evomolior.core.contract.CriticReview
import com.achulkov.evomolior.core.contract.PersistExitResult
import com.achulkov.evomolior.core.contract.WorkerToolCard
import com.achulkov.evomolior.core.model.GroupProfile
import com.achulkov.evomolior.core.model.GroupProfileType
import com.achulkov.evomolior.core.repair.AggregationSemantics
import com.achulkov.evomolior.core.repair.PublicToolFinding
import com.achulkov.evomolior.core.repair.QuantitySpec
import com.achulkov.evomolior.core.repair.TimeBasis
import com.achulkov.evomolior.core.repair.ToolPromotionStatus
import com.achulkov.evomolior.core.repair.ToolRepairAttemptRecord
import com.achulkov.evomolior.core.repair.ToolRepairCode
import com.achulkov.evomolior.core.repair.ToolRepairSource
import com.achulkov.evomolior.core.repair.ToolSemanticReview
import com.achulkov.evomolior.core.repair.ToolSemanticVerdict
import com.achulkov.evomolior.core.repair.ToolValidationStatus
import com.achulkov.evomolior.orchestration.lifecycle.SealedPromotionTestFixture
import com.achulkov.evomolior.orchestration.lifecycle.ToolPromotionRejectionAuthority
import com.achulkov.evomolior.orchestration.lifecycle.ToolPromotionResult
import com.achulkov.evomolior.orchestration.lifecycle.ToolPromotionService
import com.achulkov.evomolior.orchestration.lifecycle.ToolSubmissionValidationResult
import com.achulkov.evomolior.orchestration.lifecycle.ToolSubmissionValidator
import com.achulkov.evomolior.orchestration.metaplanner.MetaPlannerRunner
import com.achulkov.evomolior.orchestration.repair.PrivatePromotionFailure
import com.achulkov.evomolior.orchestration.repair.TypedToolRepairOrchestrator
import com.achulkov.evomolior.orchestration.tooltransport.ToolCandidateWireCompiler
import com.achulkov.evomolior.orchestration.tooltransport.ToolContractWorkspaceCompiler
import com.achulkov.evomolior.orchestration.tooltransport.ToolContractWorkspaceMaterializer
import com.achulkov.evomolior.sandbox.toolsandbox.ToolSubSandbox
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.atlas.AtlasTransactionRepository
import com.achulkov.evomolior.substrate.atlas.NodeCaptureRepository
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import com.achulkov.evomolior.substrate.repair.ToolRepairPersistenceRepository
import com.achulkov.evomolior.substrate.tools.ToolInfrastructureRepository
import com.achulkov.evomolior.substrate.worker.ContributionGraphRepository
import com.achulkov.evomolior.substrate.worker.WorkerExecutionRepository
import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class PowerToEnergyRepairAcceptanceTest {
    @Test
    fun `persisted delayed diagnostic enables promotion simulation grading and capture`() = runTest {
        val root = createTempDirectory("power-energy-acceptance-").toFile()
        val runId = "run-power-energy-acceptance"
        val groupId = "group-power-energy"
        val originNodeId = NodeId("node-power-energy")
        val reuseNodeId = NodeId("node-power-energy-reuse")
        val toolsRoot = File(root, "tools")
        val factory = SubstrateDatabaseFactory(root)
        val atlas = initializeTestWorkerAtlas(
            factory = factory,
            runId = runId,
            nodeIds = setOf(originNodeId, reuseNodeId),
            groupId = groupId
        )
        val commitment = commitment(
            runId = runId,
            atlasHash = atlas.atlasHash,
            originNodeId = originNodeId,
            reuseNodeId = reuseNodeId
        )
        val firstDatabase = factory.get(runId, groupId)
        val firstRepairRepository = ToolRepairPersistenceRepository(firstDatabase)
        val firstCandidate = compileCandidate(
            root = root,
            commitment = commitment,
            groupId = groupId,
            round = 1,
            source = incorrectSource(),
            expectedPublicEnergy = 3_000.0
        )
        val firstSubmission = submission(
            id = "submission-power-energy-first",
            agentId = "agent-power-energy-first",
            generation = 1,
            groupId = groupId,
            runId = runId,
            candidate = firstCandidate
        )
        val validator = ToolSubmissionValidator(
            runId = runId,
            groupId = groupId,
            toolsMountHostPath = toolsRoot.absolutePath,
            opportunityResolver = { commitment }
        )
        assertEquals(
            ToolSubmissionValidationResult.Valid,
            validator.validate(firstSubmission)
        )
        val privateFixture = SealedPromotionTestFixture(
            id = PRIVATE_FIXTURE_ID,
            input = Json.parseToJsonElement(
                """{"power_watts":1733.0,"duration_hours":2.5}"""
            ),
            expectedOutput = Json.parseToJsonElement(
                """{"energy_kwh":4.3325,"result_summary":"Converted power to energy.","uncertainty":"Deterministic arithmetic; input uncertainty is unchanged."}"""
            )
        )
        val firstPromotion = ToolPromotionService(
            database = firstDatabase,
            runId = runId,
            groupId = groupId,
            toolsMountHostPath = toolsRoot.absolutePath,
            opportunityResolver = { commitment },
            sealedPromotionTestResolver = { listOf(privateFixture) }
        ).promote(firstSubmission, "critic-power-energy-first")
        val rejected = assertIs<ToolPromotionResult.Rejected>(firstPromotion)
        assertEquals(ToolPromotionRejectionAuthority.PRIVATE_PROMOTION, rejected.authority)
        assertEquals(PrivatePromotionFailure.EXPECTED_OUTPUT_MISMATCH, rejected.privateFailure)
        assertFalse(rejected.toString().contains("1733"))
        assertFalse(rejected.toString().contains("4.3325"))
        assertFalse(firstCandidate.content.contains(PRIVATE_FIXTURE_ID))

        val typedDecision = TypedToolRepairOrchestrator().compileCriticDecision(
            submissionId = firstSubmission.id,
            criticId = "critic-power-energy-semantic",
            semanticReview = ToolSemanticReview(
                verdict = ToolSemanticVerdict.REJECT,
                publicFindings = listOf(
                    PublicToolFinding(
                        code = ToolRepairCode.POWER_TO_ENERGY_UNIT_CONVERSION,
                        affectedPublicField = "energy_kwh",
                        quantitySpec = commitment.opportunity.requiredOutputs
                            .single { it.name == "energy_kwh" }
                            .quantitySpec
                    )
                ),
                safeRepairCodes = setOf(ToolRepairCode.POWER_TO_ENERGY_UNIT_CONVERSION),
                privateRationaleCommitment = "d".repeat(64)
            ),
            commitment = commitment,
            candidateHash = firstCandidate.candidateHash,
            groupId = groupId,
            round = 1
        )
        assertEquals(CriticOutcome.REJECT, typedDecision.criticReview.outcome)
        val diagnostic = typedDecision.repairDiagnostics.single()
        assertEquals(ToolRepairCode.POWER_TO_ENERGY_UNIT_CONVERSION, diagnostic.code)
        assertEquals(ToolRepairSource.CRITIC_SEMANTIC_REVIEW, diagnostic.source)
        assertEquals(firstCandidate.candidateHash, diagnostic.candidateHash)
        firstRepairRepository.appendDiagnostic(diagnostic)
        firstRepairRepository.appendAttempt(
            ToolRepairAttemptRecord(
                attemptId = "attempt-power-energy-first",
                opportunityId = commitment.opportunity.opportunityId,
                candidateHash = firstCandidate.candidateHash,
                authorAgentId = firstSubmission.agentId,
                round = 1,
                consumedDiagnosticIds = emptyList(),
                preflightResult = ToolValidationStatus.PASSED,
                criticResult = ToolSemanticVerdict.REJECT,
                promotionResult = ToolPromotionStatus.REJECTED_PRIVATE_AUTHORITY
            ).withComputedHash()
        )
        assertTrue(firstRepairRepository.visibleDiagnostics(runId, groupId, "power-to-energy", 1)
            .isEmpty())
        val publicDiagnosticJson = Json.encodeToString(diagnostic)
        assertFalse(publicDiagnosticJson.contains("1733"))
        assertFalse(publicDiagnosticJson.contains("4.3325"))
        assertFalse(publicDiagnosticJson.contains(PRIVATE_FIXTURE_ID))
        factory.close(runId, groupId)

        val database = factory.get(runId, groupId)
        val repairRepository = ToolRepairPersistenceRepository(database)
        val visibleDiagnostic = repairRepository
            .visibleDiagnostics(runId, groupId, "power-to-energy", 2)
            .single()
        assertEquals(diagnostic, visibleDiagnostic)
        assertEquals(
            "attempt-power-energy-first",
            repairRepository.attempts("power-to-energy").single().attemptId
        )
        val correctedCandidate = compileCandidate(
            root = root,
            commitment = commitment,
            groupId = groupId,
            round = 2,
            source = correctedSource(),
            expectedPublicEnergy = 3.0,
            visibleDiagnosticId = visibleDiagnostic.diagnosticId,
            visibleDiagnostics = listOf(visibleDiagnostic)
        )
        assertTrue(correctedCandidate.content.contains("/ 1000.0"))
        assertFalse(correctedCandidate.content.contains(visibleDiagnostic.diagnosticId))
        assertNotEquals(firstCandidate.candidateHash, correctedCandidate.candidateHash)
        val correctedSubmission = submission(
            id = "submission-power-energy-corrected",
            agentId = "agent-power-energy-repair",
            generation = 2,
            groupId = groupId,
            runId = runId,
            candidate = correctedCandidate
        )
        assertEquals(
            ToolSubmissionValidationResult.Valid,
            ToolSubmissionValidator(
                runId = runId,
                groupId = groupId,
                toolsMountHostPath = toolsRoot.absolutePath,
                opportunityResolver = { commitment }
            ).validate(correctedSubmission)
        )
        val promoted = assertIs<ToolPromotionResult.Promoted>(
            ToolPromotionService(
                database = database,
                runId = runId,
                groupId = groupId,
                toolsMountHostPath = toolsRoot.absolutePath,
                opportunityResolver = { commitment },
                sealedPromotionTestResolver = { listOf(privateFixture) }
            ).promote(correctedSubmission, "critic-power-energy-repair")
        )
        val versionId = ToolVersionId(promoted.toolVersionId)
        repairRepository.appendAttempt(
            ToolRepairAttemptRecord(
                attemptId = "attempt-power-energy-corrected",
                opportunityId = commitment.opportunity.opportunityId,
                candidateHash = correctedCandidate.candidateHash,
                authorAgentId = correctedSubmission.agentId,
                round = 2,
                consumedDiagnosticIds = correctedCandidate.consumedRepairDiagnosticIds,
                preflightResult = ToolValidationStatus.PASSED,
                criticResult = ToolSemanticVerdict.PROMOTE,
                promotionResult = ToolPromotionStatus.PROMOTED,
                approachVector = correctedCandidate.repairApproachVector
            ).withComputedHash()
        )
        assertEquals(
            listOf(visibleDiagnostic.diagnosticId),
            repairRepository.attempts("power-to-energy").last().consumedDiagnosticIds
        )

        val promotedSubmission = correctedSubmission.copy(
            status = "promoted",
            criticAgentId = "critic-power-energy-repair",
            reviewedAtMs = 2L,
            promotedArtifactRef = "tool:${versionId.value}",
            promotedArtifactHash = AtlasHashing.hashText(correctedSubmission.content)
        )
        database.stagingDao().insert(promotedSubmission)
        ContributionGraphRepository(database).recordCriticDecision(
            CriticDecisionRecord(
                decisionId = "decision-power-energy-promotion",
                runId = runId,
                groupId = AtlasGroupId(groupId),
                round = 2,
                submissionId = promotedSubmission.id,
                submissionContentHash = AtlasHashing.hashText(promotedSubmission.content),
                resolvedReview = CriticReview(
                    submissionId = promotedSubmission.id,
                    outcome = CriticOutcome.PROMOTE,
                    criticAgentId = "critic-power-energy-repair",
                    initialTrustScore = 0.9
                ),
                resultAuthority = ContributionAuthorityRef(
                    kind = ContributionAuthorityKind.CANONICAL_ARTIFACT,
                    authorityId = "tool:${versionId.value}",
                    authorityHash = requireNotNull(promotedSubmission.promotedArtifactHash)
                ),
                decidedAt = AT
            ).withComputedHash()
        )

        val registry = requireNotNull(database.toolRegistryDao().getById(versionId.value))
        val laterAgentId = "agent-power-energy-later"
        assertNotEquals(promotedSubmission.agentId, laterAgentId)
        val toolRepository = ToolInfrastructureRepository(database)
        val workerRepository = WorkerExecutionRepository(database)
        val laterRegistration = AgentLifecycleRegistration(
            registrationId = "registration-power-energy-later",
            runId = runId,
            groupId = AtlasGroupId(groupId),
            agentId = AtlasAgentId(laterAgentId),
            role = "Researcher",
            generation = 3,
            classification = AgentLifecycleClassification.NON_DAEMON,
            registeredAt = AT
        ).withComputedHash()
        workerRepository.registerAgent(laterRegistration)
        val response = ExecuteToolTool(
            toolRegistryDao = database.toolRegistryDao(),
            toolSubSandbox = ToolSubSandbox(File(root, "later-workspace")),
            budgetLedger = BudgetLedger(
                agentId = laterAgentId,
                initialBudget = 100.0,
                floor = 1.0,
                config = BudgetConfig(
                    groupId,
                    GroupProfile.default(GroupProfileType.Factory)
                )
            ),
            runId = runId,
            groupId = groupId,
            toolsMountPath = toolsRoot.absolutePath,
            agentId = laterAgentId,
            assignedNodeId = reuseNodeId,
            availableToolCards = listOf(
                WorkerToolCard(
                    toolId = registry.id,
                    name = registry.name,
                    version = registry.version,
                    opportunityId = commitment.opportunity.opportunityId,
                    opportunityCommitmentHash = commitment.commitmentHash,
                    requiredForNodeId = reuseNodeId,
                    description = commitment.opportunity.repeatedQuestionPattern,
                    inputSchemaHash = AtlasHashing.hashText(registry.inputSchemaJson),
                    outputSchemaHash = AtlasHashing.hashText(registry.outputSchemaJson)
                )
            ),
            round = 3,
            infrastructureRepository = toolRepository,
            nowEpochMillis = { AT.toEpochMilliseconds() },
            executionIdFactory = { "execution-power-energy-later" }
        ).execute(
            ExecuteToolTool.Args(
                toolName = registry.name,
                toolVersion = registry.version,
                argsJson = """{"power_watts":2000.0,"duration_hours":1.5}"""
            )
        )
        assertTrue(response.contains("Status: SUCCESS"))
        assertTrue(response.contains("\"energy_kwh\":3.0"))
        val laterExecution = requireNotNull(
            toolRepository.execution("execution-power-energy-later")
        )
        val simulation = toolRepository.simulationsForRun(runId).single()
        assertEquals(laterExecution.executionId, simulation.executionId)
        assertEquals(laterAgentId, simulation.executedBy.value)

        val contribution = ContributionCommit(
            commitId = "contribution-power-energy-later",
            runId = runId,
            groupId = AtlasGroupId(groupId),
            round = 3,
            agentId = AtlasAgentId(laterAgentId),
            nodeId = reuseNodeId,
            commitType = CommitType.POSITIVE_RESULT,
            artifactIds = emptyList(),
            claimIds = emptyList(),
            toolRunIds = listOf(laterExecution.executionId),
            testsRun = emptyList(),
            changedStateSummary = "Executed the repaired promoted power-to-energy simulator.",
            evidenceSummary = "The persisted simulation output supports the reuse node.",
            confidence = 0.95,
            unresolvedRisks = emptyList(),
            successorRecommendations = emptyList(),
            costSummary = NodeEvidenceCostSummary(totalCredits = 2.0, toolExecutions = 1),
            dedupeHash = "",
            committedAt = AT
        ).let { it.copy(dedupeHash = AtlasHashing.hashContributionDedupe(it)) }
        workerRepository.commit(contribution)
        val laterExit = AgentExitRecord(
            exitId = "exit-power-energy-later",
            runId = runId,
            groupId = AtlasGroupId(groupId),
            agentId = AtlasAgentId(laterAgentId),
            nodeId = reuseNodeId,
            round = 3,
            finalPhase = AgentExecutionPhase.CONTRIBUTION_STAGED,
            commitId = contribution.commitId,
            commitType = contribution.commitType,
            acceptedContribution = true,
            acceptedStructuredDeadEnd = false,
            acceptedNoveltyProposal = false,
            degradedExecution = false,
            wasKilled = false,
            exitedAt = AT
        )
        assertIs<PersistExitResult.Inserted>(workerRepository.recordExit(laterExit))
        val packet = NodeEvidencePacket(
            packetId = "packet-power-energy-later",
            runId = runId,
            round = 3,
            groupId = AtlasGroupId(groupId),
            nodeId = reuseNodeId,
            atlasVersion = 1,
            atlasHash = atlas.atlasHash,
            nodePayloadHash = atlas.nodes.getValue(reuseNodeId).payloadHash,
            toolRunIds = listOf(laterExecution.executionId),
            provenanceRootHash = "provenance-power-energy",
            cost = requireNotNull(contribution.costSummary),
            sealedAtEpochMillis = AT.toEpochMilliseconds()
        ).withComputedHash()
        val captureRepository = NodeCaptureRepository(factory)
        captureRepository.appendEvidencePacket(packet)
        val node = atlas.nodes.getValue(reuseNodeId)
        val assignment = NodeGradingAssignment(
            assignmentId = "assignment-power-energy-later",
            packet = packet,
            criteriaHash = AtlasHashing.hashNodeGradingAuthority(node, emptyMap()),
            graderCriteriaFreezeHashes = emptyMap(),
            capturePolicy = node.capturePolicy,
            capturePolicyHash = AtlasHashing.hashCapturePolicy(node.capturePolicy),
            requiredQuorum = 2
        ).withComputedHash()
        val responses = setOf("grader-power-a", "grader-power-b").map { graderId ->
            NodeGraderResponse(
                responseId = "response-$graderId",
                assignmentId = assignment.assignmentId,
                assignmentHash = assignment.assignmentHash,
                graderId = graderId,
                scoreVector = NodeScoreVector(
                    correctness = 1.0,
                    evidenceQuality = 0.95,
                    reproducibility = 1.0,
                    robustness = 0.9,
                    integrationUtility = 1.0,
                    receiverRelevance = 0.9,
                    novelty = 0.7,
                    efficiency = 0.95,
                    hardFailure = false,
                    confidence = 0.95
                ),
                verdict = NodeGraderResponseVerdict.VALID,
                rationale = "Simulation ${simulation.simulationRunId} and execution " +
                    "${laterExecution.executionId} reproduce 3.0 kWh."
            ).withComputedHash()
        }
        assertTrue(responses.all { it.rationale.contains(simulation.simulationRunId) })
        val bundle = GraderRunner(
            openRouterApiKey = "test",
            substrateFactory = factory,
            localAgentRunner = { error("No provider call in deterministic aggregation") }
        ).aggregateNodeGradeBundles(
            assignments = listOf(assignment),
            expectedGraderIds = responses.mapTo(linkedSetOf(), NodeGraderResponse::graderId),
            responses = responses
        ).single()
        assertEquals(NodeGradeBundleVerdict.VALID_PASS, bundle.verdict)
        captureRepository.appendGradeBundle(bundle)
        val atlasRepository = AtlasTransactionRepository(factory)
        val resolution = NodeCaptureResolver().resolve(
            NodeCaptureResolutionRequest(
                atlas = atlas,
                currentState = atlasRepository.currentStates(runId, atlas.atlasId)
                    .getValue(reuseNodeId),
                round = 3,
                expectedGroupIds = setOf(AtlasGroupId(groupId)),
                bundles = listOf(bundle)
            )
        )
        assertEquals(NodeCaptureDecisionType.CAPTURED, resolution.decision.decisionType)
        AtlasNodeCaptureAuthority(
            runId = runId,
            atlasId = atlas.atlasId,
            atlasRepository = atlasRepository,
            captureRepository = captureRepository,
            now = { AT.toEpochMilliseconds() }
        ).commit(resolution)
        val capture = resolution.decision

        EvoMoliorRunService(
            lifecycleManagerProvider = { _, _, _, _, _ -> error("No lifecycle in focused test") },
            groupCoordinatorProvider = { groups, dimensions ->
                GroupCoordinator(groups, dimensions)
            },
            graderGroupManagerProvider = { dimensions -> GraderGroupManager(dimensions) },
            substrateFactory = factory,
            metaPlannerRunner = MetaPlannerRunner()
        ).appendToolReuseAttribution(
            runId = runId,
            round = 3,
            packet = packet,
            currentScore = requireNotNull(bundle.normalizedScore),
            priorScore = 0.0
        )

        assertTrue(packet.toolRunIds.contains(simulation.executionId))
        assertEquals(
            ToolLifecycleState.RESULT_REFERENCED_BY_NODE,
            toolRepository.lifecycle(versionId).last().state
        )
        assertEquals(packet.packetHash, toolRepository.reuseForVersion(versionId).single()
            .nodeEvidencePacketHash)
        assertEquals(
            requireNotNull(bundle.normalizedScore),
            toolRepository.fitnessForVersion(versionId).single()
                .nodeScoreDeltaAttributedToResults
        )
        assertEquals(
            capture.decisionHash,
            captureRepository.captureDecisionsForNode(runId, reuseNodeId).single().decisionHash
        )
        val metrics = toolRepository.metrics(runId, AtlasGroupId(groupId))
        assertEquals(1, metrics.simulationRunCount)
        assertEquals(1, metrics.evidenceBoundSimulationReuseCount)
        factory.close(runId, groupId)
        val reopenedDatabase = factory.get(runId, groupId)
        val persistedAttempts = ToolRepairPersistenceRepository(reopenedDatabase)
            .attempts("power-to-energy")
        assertEquals(
            listOf("attempt-power-energy-first", "attempt-power-energy-corrected"),
            persistedAttempts.map(ToolRepairAttemptRecord::attemptId)
        )
        assertEquals(
            listOf(visibleDiagnostic.diagnosticId),
            persistedAttempts.last().consumedDiagnosticIds
        )
        val reopenedTools = ToolInfrastructureRepository(reopenedDatabase)
        assertEquals(simulation, reopenedTools.simulationsForRun(runId).single())
        assertEquals(packet.packetHash, reopenedTools.reuseForVersion(versionId).single()
            .nodeEvidencePacketHash)
        val reopenedWorkers = WorkerExecutionRepository(reopenedDatabase)
        assertEquals(laterRegistration, reopenedWorkers.registrations(runId, groupId).single())
        assertEquals(laterExit, reopenedWorkers.exits(runId, groupId).single())
        factory.close(runId, groupId)
    }

    private fun commitment(
        runId: String,
        atlasHash: String,
        originNodeId: NodeId,
        reuseNodeId: NodeId
    ): ToolOpportunityCommitment {
        val power = PhysicalDimension(mapOf("power" to 1))
        val time = PhysicalDimension(mapOf("time" to 1))
        val energy = PhysicalDimension(mapOf("energy" to 1))
        return ToolOpportunityCommitment(
            runId = runId,
            atlasHash = atlasHash,
            nodeId = originNodeId,
            opportunity = ToolOpportunity(
                opportunityId = "power-to-energy",
                opportunityType = ToolOpportunityType.SIMULATION,
                repeatedQuestionPattern = "Convert constant power over hours to total energy.",
                requiredInputs = listOf(
                    TypedField(
                        "power_watts",
                        TypedFieldType.NUMBER,
                        "Constant input power.",
                        physicalDimension = power,
                        quantitySpec = QuantitySpec(
                            power,
                            "W",
                            1.0,
                            AggregationSemantics.INSTANTANEOUS
                        )
                    ),
                    TypedField(
                        "duration_hours",
                        TypedFieldType.NUMBER,
                        "Duration in hours.",
                        physicalDimension = time,
                        quantitySpec = QuantitySpec(
                            time,
                            "h",
                            3_600.0,
                            AggregationSemantics.INSTANTANEOUS
                        )
                    )
                ),
                requiredOutputs = listOf(
                    TypedField(
                        "energy_kwh",
                        TypedFieldType.NUMBER,
                        "Total energy in kilowatt-hours.",
                        physicalDimension = energy,
                        quantitySpec = QuantitySpec(
                            energy,
                            "kWh",
                            3_600_000.0,
                            AggregationSemantics.SUM_OVER_INTERVALS,
                            TimeBasis.HOUR
                        )
                    ),
                    TypedField(
                        "result_summary",
                        TypedFieldType.STRING,
                        "Bounded result summary."
                    ),
                    TypedField(
                        "uncertainty",
                        TypedFieldType.STRING,
                        "Explicit uncertainty statement."
                    )
                ),
                validationStrategy = "Compare exact dimensional reference calculations.",
                expectedReuseNodeIds = setOf(reuseNodeId),
                preferredImplementationMode = ToolImplementationMode.PYTHON,
                behavior = ToolBehavior.DETERMINISTIC,
                requiredCapabilities = setOf("python"),
                promotionTestIds = setOf(PRIVATE_FIXTURE_ID),
                maximumRuntimeMillis = 2_000,
                maximumOutputBytes = 4_096,
                assumptions = listOf("Power remains constant for the supplied duration."),
                simulationRecordBindings = SimulationRecordBindings(
                    resultSummaryOutputField = "result_summary",
                    uncertaintyOutputField = "uncertainty"
                )
            )
        ).withComputedHash()
    }

    private fun compileCandidate(
        root: File,
        commitment: ToolOpportunityCommitment,
        groupId: String,
        round: Int,
        source: String,
        expectedPublicEnergy: Double,
        visibleDiagnosticId: String? = null,
        visibleDiagnostics:
            List<com.achulkov.evomolior.core.repair.ToolRepairDiagnosticV1> = emptyList()
    ): com.achulkov.evomolior.core.atlas.CompiledToolCandidateWire {
        val skeleton = ToolProjectSkeletonCompiler.compile(commitment)
        val capability = ToolOpportunityBindingCapability(
            runId = commitment.runId,
            groupId = groupId,
            round = round,
            workerViewHash = AtlasHashing.hashText("worker-view-$round")
        )
        val binding = ToolOpportunityBinding(
            handle = "T1",
            nodeId = commitment.nodeId.value,
            opportunityId = commitment.opportunity.opportunityId,
            opportunityHash = commitment.commitmentHash,
            inputSchemaHash = AtlasHashing.hashText(skeleton.inputSchemaJson),
            outputSchemaHash = AtlasHashing.hashText(skeleton.outputSchemaJson),
            publicTestIds = emptyList(),
            runtimeKind = ToolRuntimeKind.PYTHON,
            projectSkeletonHash = skeleton.skeletonHash
        )
        val catalog = ToolOpportunityBindingCatalog(
            catalogId = "catalog-power-energy-$round",
            runId = commitment.runId,
            groupId = groupId,
            round = round,
            workerViewHash = capability.workerViewHash,
            bindings = listOf(binding)
        ).withComputedHash()
        val workspace = ToolContractWorkspaceCompiler.compile(
            catalog = catalog,
            capability = capability,
            commitment = commitment,
            skeleton = skeleton,
            publicTests = emptyList()
        )
        val workspaceRoot = File(root, "workspace-$round").toPath()
        ToolContractWorkspaceMaterializer.materialize(workspace, workspaceRoot)
        Files.writeString(workspaceRoot.resolve("src/main.py"), source)
        Files.writeString(
            workspaceRoot.resolve("tests/author-tests.json"),
            """{"cases":[{"input":{"power_watts":1500.0,"duration_hours":2.0},"expectedOutput":{"energy_kwh":$expectedPublicEnergy,"result_summary":"Converted power to energy.","uncertainty":"Deterministic arithmetic; input uncertainty is unchanged."}}]}"""
        )
        return assertIs<ToolCandidateWireCompilation.Compiled>(
            ToolCandidateWireCompiler(
                catalog = catalog,
                capability = capability,
                workspace = workspace,
                commitment = commitment,
                workspaceRoot = workspaceRoot,
                visibleRepairDiagnostics = visibleDiagnostics,
                repairApproachVector = visibleDiagnostics
                    .takeIf { it.isNotEmpty() }
                    ?.let {
                        com.achulkov.evomolior.core.repair.ApproachVector(
                            vectorId = "approach-$groupId-$round",
                            runId = commitment.runId,
                            groupId = groupId,
                            opportunityId = commitment.opportunity.opportunityId,
                            round = round,
                            lineageAttempt = 1,
                            strategy = com.achulkov.evomolior.core.repair.RepairStrategy
                                .DIMENSIONAL_ANALYSIS_FIRST
                        ).withComputedHash()
                    }
            ).compile(
                ToolCandidateWireDraft(
                    opportunityHandle = "T1",
                    sourceArtifactPath = "src/main.py",
                    entryPoint = "run_tool",
                    supplementalTestsPath = "tests/author-tests.json",
                    consumedRepairDiagnosticIds = listOfNotNull(visibleDiagnosticId)
                )
            )
        ).candidate
    }

    private fun submission(
        id: String,
        agentId: String,
        generation: Int,
        groupId: String,
        runId: String,
        candidate: com.achulkov.evomolior.core.atlas.CompiledToolCandidateWire
    ) = StagingSubmissionEntity(
        id = id,
        agentId = agentId,
        role = "Builder",
        type = "TOOL",
        content = candidate.content,
        wipTaskRef = candidate.wipTaskRef,
        budgetSignature = "budget-signature",
        generation = generation,
        groupId = groupId,
        runId = runId,
        submittedAtMs = generation.toLong(),
        toolCandidateAuthorityJson = """
            {
              "candidateHash":"${candidate.candidateHash}",
              "opportunityCommitmentHash":"${candidate.opportunityCommitmentHash}",
              "sourceHash":"${candidate.sourceHash}",
              "consumedRepairDiagnosticIds":${
                  candidate.consumedRepairDiagnosticIds.joinToString(
                      prefix = "[\"",
                      postfix = "\"]",
                      separator = "\",\""
                  ).replace("[\"\"]", "[]")
              },
              "repairApproachVector":${
                  candidate.repairApproachVector?.let(Json::encodeToString) ?: "null"
              }
            }
        """.trimIndent()
    )

    private fun incorrectSource(): String = """
        import json
        import sys

        value = json.load(sys.stdin)
        energy_kwh = value["power_watts"] * value["duration_hours"]
        sys.stdout.write(json.dumps({
            "energy_kwh": energy_kwh,
            "result_summary": "Converted power to energy.",
            "uncertainty": "Deterministic arithmetic; input uncertainty is unchanged."
        }, separators=(",", ":")))
    """.trimIndent()

    private fun correctedSource(): String = """
        import json
        import sys

        value = json.load(sys.stdin)
        energy_kwh = value["power_watts"] * value["duration_hours"] / 1000.0
        sys.stdout.write(json.dumps({
            "energy_kwh": energy_kwh,
            "result_summary": "Converted power to energy.",
            "uncertainty": "Deterministic arithmetic; input uncertainty is unchanged."
        }, separators=(",", ":")))
    """.trimIndent()

    private companion object {
        const val PRIVATE_FIXTURE_ID = "sealed-power-energy"
        val AT: Instant = Instant.parse("2026-07-24T12:00:00Z")
    }
}
