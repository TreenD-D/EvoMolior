package com.achulkov.evomolior.agents.tools

import com.achulkov.evomolior.core.budget.BudgetConfig
import com.achulkov.evomolior.core.budget.BudgetLedger
import com.achulkov.evomolior.core.atlas.ArtifactType
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.atlas.CapturePolicy
import com.achulkov.evomolior.core.atlas.DisclosureStage
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
import com.achulkov.evomolior.core.contract.GraderAutomatedTestDraft
import com.achulkov.evomolior.core.contract.GraderFailureModeDraft
import com.achulkov.evomolior.core.contract.GraderMethodologyBindingCatalog
import com.achulkov.evomolior.core.contract.GraderRequirementWireDraft
import com.achulkov.evomolior.core.contract.GraderRubricDraft
import com.achulkov.evomolior.core.grader.AdversarialCase
import com.achulkov.evomolior.core.grader.DisclosureSensitivity
import com.achulkov.evomolior.core.grader.FailureLevel
import com.achulkov.evomolior.core.grader.GraderAtlas
import com.achulkov.evomolior.core.grader.GraderAtlasNode
import com.achulkov.evomolior.core.grader.GraderNodeId
import com.achulkov.evomolior.core.grader.GraderNodeKind
import com.achulkov.evomolior.core.grader.GraderNodeStatus
import com.achulkov.evomolior.core.grader.GraderView
import com.achulkov.evomolior.core.grader.GraderViewNode
import com.achulkov.evomolior.core.model.GroupProfile
import com.achulkov.evomolior.core.model.GroupProfileType
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PublishGradingSheetToolTest {
    @Test
    fun `compiled grader atlas accepts complete typed requirement authority`() = runTest {
        val runId = "run-typed"
        val groupId = "grader-0"
        val agentId = "grader-builder-typed"
        val db = SubstrateDatabaseFactory(createTempDirectory("grader-sheet-typed-").toFile())
            .getGrader(runId, groupId)
        val fixture = typedFixture(db = db, runId = runId, groupId = groupId, agentId = agentId)

        val result = fixture.tool.execute(validTypedArgs(fixture.catalog))

        assertTrue(result.startsWith("Grading sheet draft published"))
        val entry = db.stagingDao().getByAgent(runId, groupId, agentId).single()
        assertTrue(entry.content.contains("\"requirements\""))
        assertTrue(entry.content.contains("\"requirementId\":\"requirement-1\""))
        assertTrue(entry.content.contains("\"mappedRequirementIds\":[\"requirement-1\"]"))
        assertTrue(entry.content.contains("\"graderAtlasNodeId\":\"frontier-1\""))
        assertTrue(entry.content.contains("\"mappedWorkerNodeIds\":[\"worker-1\"]"))
        assertFalse(entry.content.contains("\"graderNodeHandle\""))
    }

    @Test
    fun `compiled grader atlas rejects incomplete or unbound typed authority without mutation`() =
        runTest {
            val runId = "run-invalid-typed"
            val groupId = "grader-0"
            val agentId = "grader-builder-invalid-typed"
            val db = SubstrateDatabaseFactory(
                createTempDirectory("grader-sheet-invalid-typed-").toFile()
            ).getGrader(runId, groupId)
            val fixture = typedFixture(db, runId, groupId, agentId)
            val valid = validTypedArgs(fixture.catalog)
            val requirement = valid.requirements.single()
            val test = valid.automatedTests.single()
            val invalidCases = listOf(
                valid.copy(requirements = emptyList()),
                valid.copy(
                    requirements = listOf(
                        requirement.copy(graderNodeHandle = "g_unknown")
                    )
                ),
                valid.copy(
                    requirements = listOf(
                        requirement.copy(mappedWorkerNodeHandles = setOf("w_unknown"))
                    )
                ),
                valid.copy(
                    requirements = listOf(
                        requirement.copy(
                            mappedReceiverObligationRefs = setOf("unknown-receiver")
                        )
                    )
                ),
                valid.copy(automatedTests = emptyList()),
                valid.copy(
                    automatedTests = listOf(
                        test.copy(mappedRequirementIds = setOf("unknown-requirement"))
                    )
                ),
                valid.copy(
                    requirements = listOf(
                        requirement.copy(executableTestIds = listOf("unknown-test"))
                    )
                ),
                valid.copy(
                    requirements = listOf(
                        requirement.copy(maximumEvaluationCost = -0.01)
                    )
                ),
                valid.copy(automatedTests = listOf(test.copy(difficulty = 1.01)))
            )

            invalidCases.forEachIndexed { index, args ->
                val result = fixture.tool.execute(args)
                assertTrue(result.startsWith("Error:"), "case $index unexpectedly succeeded: $result")
                assertEquals(
                    0,
                    db.stagingDao().getTotalCountForAgent(runId, groupId, agentId),
                    "case $index mutated staging"
                )
            }
        }

    @Test
    fun `typed grader sheet validates and persists with wip provenance`() = runTest {
        val runId = "run-1"
        val groupId = "grader-0"
        val agentId = "grader-builder-1"
        val db = SubstrateDatabaseFactory(createTempDirectory("grader-sheet-tool-").toFile())
            .getGrader(runId, groupId)
        val config = BudgetConfig(groupId, GroupProfile.default(GroupProfileType.Library))
        val tool = PublishGradingSheetTool(
            stagingDao = db.stagingDao(),
            budgetLedger = BudgetLedger(agentId, 50.0, 1.0, config),
            runId = runId,
            graderGroupId = groupId,
            agentId = agentId,
            generation = 1,
            allowedDimensionIds = setOf("accuracy"),
            defaultWipTaskRef = "grader-wip-g1-graderbuilder-0"
        )

        val result = tool.execute(
            PublishGradingSheetTool.Args(
                rubricDimensions = listOf(
                    GraderRubricDraft("accuracy", "Require reproducible evidence.")
                ),
                automatedTests = listOf(
                    GraderAutomatedTestDraft(
                        testId = "evidence-marker",
                        description = "Requires an evidence section.",
                        expectedOutputPattern = "(?i)evidence"
                    )
                ),
                failureModes = listOf(
                    GraderFailureModeDraft("unsupported-claim", "Claims lack evidence.")
                )
            )
        )

        assertTrue(result.startsWith("Grading sheet draft published"))
        val entry = db.stagingDao().getByAgent(runId, groupId, agentId).single()
        assertEquals("pending", entry.status)
        assertEquals("grader-wip-g1-graderbuilder-0", entry.wipTaskRef)
        assertTrue(entry.budgetSignature.startsWith("GRADING_SHEET_DRAFT:"))
    }

    @Test
    fun `invalid automated test does not mutate grader staging`() = runTest {
        val runId = "run-1"
        val groupId = "grader-0"
        val agentId = "grader-builder-1"
        val db = SubstrateDatabaseFactory(createTempDirectory("grader-sheet-invalid-").toFile())
            .getGrader(runId, groupId)
        val config = BudgetConfig(groupId, GroupProfile.default(GroupProfileType.Library))
        val tool = PublishGradingSheetTool(
            stagingDao = db.stagingDao(),
            budgetLedger = BudgetLedger(agentId, 50.0, 1.0, config),
            runId = runId,
            graderGroupId = groupId,
            agentId = agentId,
            generation = 1,
            allowedDimensionIds = setOf("accuracy"),
            defaultWipTaskRef = null
        )

        val result = tool.execute(
            PublishGradingSheetTool.Args(
                rubricDimensions = listOf(
                    GraderRubricDraft("accuracy", "Require reproducible evidence.")
                ),
                automatedTests = listOf(
                    GraderAutomatedTestDraft("broken", "Invalid regex.", "[")
                ),
                failureModes = emptyList()
            )
        )

        assertTrue(result.contains("invalid RE2/J pattern"))
        assertTrue(result.contains("["))
        assertEquals(0, db.stagingDao().getTotalCountForAgent(runId, groupId, agentId))
    }

    @Test
    fun `typed mapping rejection returns only the rejected worker handle`() = runTest {
        val runId = "run-mapping-diagnostic"
        val groupId = "grader-0"
        val agentId = "grader-builder-mapping"
        val db = SubstrateDatabaseFactory(
            createTempDirectory("grader-sheet-mapping-").toFile()
        ).getGrader(runId, groupId)
        val fixture = typedFixture(db, runId, groupId, agentId)
        val valid = validTypedArgs(fixture.catalog)
        val result = fixture.tool.execute(
            valid.copy(
                requirements = listOf(
                    valid.requirements.single().copy(
                        mappedWorkerNodeHandles = setOf("w_wrong")
                    )
                )
            )
        )

        assertTrue(result.contains("unknown_worker_handle"))
        assertTrue(result.contains("value=w_wrong"))
        assertFalse(result.contains("worker-1"))
        assertEquals(0, db.stagingDao().getTotalCountForAgent(runId, groupId, agentId))
    }

    @Test
    fun `typed grader node rejection returns only the rejected grader handle`() = runTest {
        val runId = "run-grader-node-diagnostic"
        val groupId = "grader-0"
        val agentId = "grader-builder-node-diagnostic"
        val db = SubstrateDatabaseFactory(
            createTempDirectory("grader-sheet-node-diagnostic-").toFile()
        ).getGrader(runId, groupId)
        val fixture = typedFixture(db, runId, groupId, agentId)
        val valid = validTypedArgs(fixture.catalog)

        val result = fixture.tool.execute(
            valid.copy(
                requirements = listOf(
                    valid.requirements.single().copy(
                        graderNodeHandle = "g_unknown"
                    )
                )
            )
        )

        assertTrue(result.contains("unknown_grader_handle"))
        assertTrue(result.contains("value=g_unknown"))
        assertFalse(result.contains("frontier-1"))
        assertEquals(0, db.stagingDao().getTotalCountForAgent(runId, groupId, agentId))
    }

    @Test
    fun `incomplete coverage diagnostic names authorized grader rows`() = runTest {
        val runId = "run-worker-coverage-diagnostic"
        val groupId = "grader-0"
        val agentId = "grader-builder-coverage-diagnostic"
        val workerNodeIds = setOf(NodeId("worker-1"), NodeId("worker-2"))
        val db = SubstrateDatabaseFactory(
            createTempDirectory("grader-sheet-coverage-diagnostic-").toFile()
        ).getGrader(runId, groupId)
        val fixture = typedFixture(
            db = db,
            runId = runId,
            groupId = groupId,
            agentId = agentId,
            workerNodeIds = workerNodeIds
        )
        val valid = validTypedArgs(fixture.catalog)
        val workerHandles = fixture.catalog.nodeBindings.single().workerBindings
            .map { it.workerNodeHandle }
        val missingWorkerHandle = workerHandles.last()

        val result = fixture.tool.execute(
            valid.copy(
                requirements = listOf(valid.requirements.first())
            )
        )

        assertTrue(result.contains("incomplete_worker_coverage"))
        assertTrue(result.contains(fixture.catalog.nodeBindings.single().graderNodeHandle))
        assertTrue(result.contains(missingWorkerHandle))
        assertFalse(result.contains("worker-1"))
        assertFalse(result.contains("worker-2"))
        assertEquals(0, db.stagingDao().getTotalCountForAgent(runId, groupId, agentId))
    }

    @Test
    fun `typed grader sheet rejects malformed normalized failure levels`() = runTest {
        val runId = "run-malformed-level"
        val groupId = "grader-0"
        val agentId = "grader-builder-1"
        val db = SubstrateDatabaseFactory(createTempDirectory("grader-sheet-level-").toFile())
            .getGrader(runId, groupId)
        val fixture = typedFixture(db, runId, groupId, agentId)
        val valid = validTypedArgs(fixture.catalog)
        val malformed = valid.requirements.single().copy(
            failureLevels = listOf(
                FailureLevel(
                    level = 1,
                    description = "Observable failure.",
                    scoreCeiling = 50.0
                )
            )
        )

        val result = fixture.tool.execute(valid.copy(requirements = listOf(malformed)))

        assertTrue(result.contains("typed requirement"))
        assertEquals(0, db.stagingDao().getTotalCountForAgent(runId, groupId, agentId))
    }

    private data class TypedFixture(
        val tool: PublishGradingSheetTool,
        val catalog: GraderMethodologyBindingCatalog
    )

    private fun typedFixture(
        db: com.achulkov.evomolior.substrate.db.EvoMoliorDatabase,
        runId: String,
        groupId: String,
        agentId: String,
        workerNodeIds: Set<NodeId> = setOf(NodeId("worker-1"))
    ): TypedFixture {
        val config = BudgetConfig(groupId, GroupProfile.default(GroupProfileType.Library))
        val workerAtlas = typedWorkerAtlas(runId, workerNodeIds)
        val atlas = typedAtlas(runId, workerNodeIds, workerAtlas.atlasHash)
        val node = atlas.nodes.getValue(GraderNodeId("frontier-1"))
        val view = GraderView(
            runId = runId,
            graderAtlasHash = atlas.atlasHash,
            graderGroupId = groupId,
            round = 0,
            nodes = listOf(
                GraderViewNode(
                    nodeId = node.nodeId,
                    kind = node.kind,
                    title = node.title,
                    evaluationQuestion = node.evaluationQuestion,
                    receiverObligationRefs = node.receiverObligationRefs,
                    mappedWorkerNodeIds = node.mappedWorkerNodeIds,
                    status = GraderNodeStatus.LEASED,
                    leasedByThisGroup = true,
                    activeLeaseId = "lease-$groupId",
                    activeLeaseHash = "lease-hash-$groupId"
                )
            )
        )
        return TypedFixture(
            tool = PublishGradingSheetTool(
                stagingDao = db.stagingDao(),
                budgetLedger = BudgetLedger(agentId, 50.0, 1.0, config),
                runId = runId,
                graderGroupId = groupId,
                agentId = agentId,
                generation = 1,
                allowedDimensionIds = setOf("accuracy"),
                graderAtlas = atlas,
                graderView = view,
                workerAtlas = workerAtlas,
                defaultWipTaskRef = "grader-wip-typed"
            ),
            catalog = GraderMethodologyBindingCatalog.from(view, workerAtlas)
        )
    }

    private fun typedAtlas(
        runId: String,
        workerNodeIds: Set<NodeId> = setOf(NodeId("worker-1")),
        workerAtlasHash: String
    ): GraderAtlas {
        val nodeId = GraderNodeId("frontier-1")
        val node = GraderAtlasNode(
            nodeId = nodeId,
            kind = GraderNodeKind.EVIDENCE_AUTHENTICITY,
            title = "Evidence authenticity",
            evaluationQuestion = "Is every claim supported by authentic evidence?",
            receiverObligationRefs = setOf("receiver:evidence"),
            mappedWorkerNodeIds = workerNodeIds
        ).withComputedHash()
        return GraderAtlas(
            graderAtlasId = "grader-atlas-$runId",
            runId = runId,
            version = 1,
            workerAtlasHash = workerAtlasHash,
            rootNodeIds = setOf(nodeId),
            nodes = mapOf(nodeId to node)
        ).withComputedHash()
    }

    private fun typedWorkerAtlas(
        runId: String,
        workerNodeIds: Set<NodeId>
    ): NodeAtlas {
        val nodes = workerNodeIds.associateWith { nodeId ->
            LimitationNode(
                id = nodeId,
                kind = NodeKind.EVIDENCE,
                layer = 0,
                parentIds = emptySet(),
                prerequisiteIds = emptySet(),
                receiverWhyRef = "receiver:evidence",
                limitationStatement = "The exact local evidence claim is unresolved.",
                capabilityTarget = "One inspectable source-bound claim.",
                atomicQuestion = "Can this exact local evidence claim be established?",
                proofContract = ProofContract(
                    method = ProofMethod.SOURCE_CORROBORATION,
                    acceptanceCriteria = setOf("Provide one source-bound claim.")
                ),
                deliverableContract = NodeDeliverableContract(
                    acceptedArtifactTypes = setOf(ArtifactType.CLAIM_SET),
                    requiredFields = setOf("claim", "source")
                ),
                revealPolicy = RevealPolicy(
                    stageId = "stage-0",
                    mode = RevealMode.INITIAL
                ),
                replicationPolicy = ReplicationPolicy(),
                capturePolicy = CapturePolicy(minimumGrade = 0.6),
                rewardSpec = RewardSpec(
                    rewardClass = RewardClass.SMALL,
                    credits = 1.0
                )
            ).withComputedPayloadHash()
        }
        val stage = DisclosureStage(
            stageId = "stage-0",
            stageIndex = 0,
            title = "Foundations",
            nodeIds = workerNodeIds,
            mandatoryNodeIds = workerNodeIds,
            evidenceCoverageThreshold = 1.0,
            integrationReadinessThreshold = 1.0,
            revealPayloadHash = AtlasHashing.hashDisclosurePayload(
                stageId = "stage-0",
                nodeIds = workerNodeIds,
                nodes = nodes
            )
        )
        return NodeAtlas(
            atlasId = "worker-atlas-$runId",
            runId = runId,
            version = 1,
            receiverContractHash = "receiver-contract-hash",
            rootNodeIds = workerNodeIds,
            nodes = nodes,
            stageDefinitions = listOf(stage)
        ).withComputedHash()
    }

    private fun validTypedArgs(
        catalog: GraderMethodologyBindingCatalog
    ): PublishGradingSheetTool.Args {
        val row = catalog.nodeBindings.single()
        val requirements = row.workerBindings.mapIndexed { index, worker ->
            GraderRequirementWireDraft(
                requirementId = "requirement-${index + 1}",
                graderNodeHandle = row.graderNodeHandle,
                mappedWorkerNodeHandles = setOf(worker.workerNodeHandle),
                mappedReceiverObligationRefs = setOf("receiver:evidence"),
                criterion = "Every material claim cites an inspectable source.",
                observableEvidence = "Claim-to-source links in the promoted deliverable.",
                passThreshold = "All material claims are source-bound.",
                failureLevels = listOf(
                    FailureLevel(
                        level = 1,
                        description = "At least one material claim is unsupported.",
                        scoreCeiling = 0.5
                    )
                ),
                adversarialCases = listOf(
                    AdversarialCase(
                        caseId = "plausible-fabrication-$index",
                        setup = "Present a plausible claim without source lineage.",
                        expectedDetection = "The grader identifies the unsupported claim.",
                        boundedCost = 1.0
                    )
                ),
                executableTestIds = listOf("evidence-marker"),
                disclosureSensitivity = DisclosureSensitivity.GRADER_INTERNAL,
                rationale = "The receiver requires auditable evidence.",
                noveltyFingerprint = "evidence-authenticity-v${index + 1}",
                maximumEvaluationCost = 2.0
            )
        }
        return PublishGradingSheetTool.Args(
            rubricDimensions = listOf(
                GraderRubricDraft("accuracy", "Require reproducible evidence.")
            ),
            automatedTests = listOf(
                GraderAutomatedTestDraft(
                    testId = "evidence-marker",
                    description = "Requires an evidence section.",
                    expectedOutputPattern = "(?i)evidence",
                    mappedRequirementIds = requirements
                        .mapTo(linkedSetOf()) { it.requirementId },
                    inputSchemaHash = "snapshot-schema-v1",
                    expectedOutcomeHash = "expected-evidence-v1",
                    difficulty = 0.6
                )
            ),
            failureModes = listOf(
                GraderFailureModeDraft("unsupported-claim", "Claims lack evidence.")
            ),
            requirements = requirements
        )
    }
}
