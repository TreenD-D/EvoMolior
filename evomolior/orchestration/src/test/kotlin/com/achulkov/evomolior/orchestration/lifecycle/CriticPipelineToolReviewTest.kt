@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.orchestration.lifecycle

import com.achulkov.evomolior.core.atlas.NodeId
import com.achulkov.evomolior.core.atlas.PhysicalDimension
import com.achulkov.evomolior.core.atlas.ToolBehavior
import com.achulkov.evomolior.core.atlas.ToolImplementationMode
import com.achulkov.evomolior.core.atlas.ToolOpportunity
import com.achulkov.evomolior.core.atlas.ToolOpportunityCommitment
import com.achulkov.evomolior.core.atlas.ToolOpportunityType
import com.achulkov.evomolior.core.atlas.TypedField
import com.achulkov.evomolior.core.atlas.TypedFieldType
import com.achulkov.evomolior.core.atlas.TypedToolSchemaCompiler
import com.achulkov.evomolior.core.contract.GRADER_METHODOLOGY_DRAFT_SIGNATURE_PREFIX
import com.achulkov.evomolior.core.contract.CriticOutcome
import com.achulkov.evomolior.core.contract.CriticReview
import com.achulkov.evomolior.core.repair.AggregationSemantics
import com.achulkov.evomolior.core.repair.PublicToolFinding
import com.achulkov.evomolior.core.repair.QuantitySpec
import com.achulkov.evomolior.core.repair.TimeBasis
import com.achulkov.evomolior.core.repair.ToolPromotionStatus
import com.achulkov.evomolior.core.repair.ToolRepairCode
import com.achulkov.evomolior.core.repair.ToolRepairDisclosureClass
import com.achulkov.evomolior.core.repair.ToolSemanticReview
import com.achulkov.evomolior.core.repair.ToolSemanticVerdict
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import com.achulkov.evomolior.substrate.worker.ContributionGraphRepository
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Verifies bounded Critic rejection quorum for deterministically validated submissions. */
class CriticPipelineToolReviewTest {

    @Test
    fun `typed tool critic emits delayed safe diagnostic in the actual pipeline`() = runTest {
        val dataRoot = createTempDirectory("critic-pipeline-typed-repair-").toFile()
        val runId = "run-typed-repair"
        val groupId = "group-typed-repair"
        val nodeId = NodeId("node-power-energy")
        val database = SubstrateDatabaseFactory(dataRoot).get(runId, groupId)
        val power = PhysicalDimension(mapOf("power" to 1))
        val time = PhysicalDimension(mapOf("time" to 1))
        val energy = PhysicalDimension(mapOf("energy" to 1))
        val commitment = ToolOpportunityCommitment(
            runId = runId,
            atlasHash = "atlas-hash",
            nodeId = nodeId,
            opportunity = ToolOpportunity(
                opportunityId = "power-energy",
                opportunityType = ToolOpportunityType.CALCULATION,
                repeatedQuestionPattern = "Convert power over hours to energy.",
                requiredInputs = listOf(
                    TypedField(
                        "power_watts",
                        TypedFieldType.NUMBER,
                        "Power.",
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
                        "Duration.",
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
                        "Energy.",
                        physicalDimension = energy,
                        quantitySpec = QuantitySpec(
                            energy,
                            "kWh",
                            3_600_000.0,
                            AggregationSemantics.SUM_OVER_INTERVALS,
                            TimeBasis.HOUR
                        )
                    )
                ),
                validationStrategy = "Use public dimensional reference calculations.",
                expectedReuseNodeIds = setOf(NodeId("reuse")),
                preferredImplementationMode = ToolImplementationMode.SHELL,
                behavior = ToolBehavior.DETERMINISTIC,
                promotionTestIds = setOf("private-power-energy"),
                maximumRuntimeMillis = 1_000,
                maximumOutputBytes = 4_096
            )
        ).withComputedHash()
        val inputSchema = TypedToolSchemaCompiler.compile(commitment.opportunity.requiredInputs)
        val outputSchema = TypedToolSchemaCompiler.compile(commitment.opportunity.requiredOutputs)
        val candidateHash = "c".repeat(64)
        database.stagingDao().insert(
            StagingSubmissionEntity(
                id = "power-energy-candidate",
                agentId = "builder",
                role = "Builder",
                type = "TOOL",
                content = """
                    {
                      "opportunityId":"power-energy",
                      "name":"power-energy",
                      "domain":"calculation",
                      "language":"shell",
                      "inputSchema":$inputSchema,
                      "outputSchema":$outputSchema,
                      "code":"cat >/dev/null\necho '{\"energy_kwh\":1000.0}'",
                      "tests":[{
                        "testId":"private-power-energy",
                        "input":{"power_watts":1000.0,"duration_hours":1.0},
                        "expectedOutput":{"energy_kwh":1000.0}
                      }]
                    }
                """.trimIndent(),
                budgetSignature = "sig",
                wipTaskRef = nodeId.value,
                generation = 1,
                groupId = groupId,
                runId = runId,
                submittedAtMs = 1,
                toolCandidateAuthorityJson =
                    """{"candidateHash":"$candidateHash"}"""
            )
        )
        val service = ToolPromotionService(
            database = database,
            runId = runId,
            groupId = groupId,
            toolsMountHostPath = dataRoot.resolve("tools").absolutePath,
            opportunityResolver = { commitment },
            sealedPromotionTestResolver = { emptyList() }
        )
        var semanticCalls = 0
        val pipeline = CriticPipeline(
            db = database,
            runId = runId,
            groupId = groupId,
            generation = 1,
            openRouterApiKey = "sk-test",
            toolSemanticReviewRunner = { _, prompt, _ ->
                semanticCalls++
                assertFalse(prompt.contains("private-power-energy"))
                ToolSemanticReview(
                    verdict = ToolSemanticVerdict.REJECT,
                    publicFindings = listOf(
                        PublicToolFinding(
                            code = ToolRepairCode.POWER_TO_ENERGY_UNIT_CONVERSION,
                            affectedPublicField = "energy_kwh",
                            quantitySpec =
                                commitment.opportunity.requiredOutputs.single().quantitySpec
                        )
                    ),
                    safeRepairCodes =
                        setOf(ToolRepairCode.POWER_TO_ENERGY_UNIT_CONVERSION),
                    privateRationaleCommitment = "a".repeat(64)
                )
            },
            toolsMountHostPath = dataRoot.resolve("tools").absolutePath,
            toolPromotionService = service
        )

        val summary = pipeline.processPendingSubmissions()
        val diagnostic = pipeline.toolRepairDiagnosticsSnapshot().single()

        assertEquals(2, semanticCalls)
        assertEquals(1, summary.rejected)
        assertEquals(1, summary.arbitrated)
        assertEquals(ToolRepairCode.POWER_TO_ENERGY_UNIT_CONVERSION, diagnostic.code)
        assertEquals(candidateHash, diagnostic.candidateHash)
        assertEquals(2, diagnostic.visibleFromRound)
        assertFalse(diagnostic.toString().contains("private-power-energy"))
        assertFalse(diagnostic.toString().contains("1000.0"))
    }

    @Test
    fun `preflight valid tool requires two critic rejections`() = runTest {
        val dataRoot = createTempDirectory("critic-pipeline-tool-quorum-").toFile()
        val runId = "run-tool-quorum"
        val groupId = "group-tool-quorum"
        val database = SubstrateDatabaseFactory(dataRoot).get(runId, groupId)
        database.stagingDao().insert(
            StagingSubmissionEntity(
                id = "tool-submission",
                agentId = "agent-builder",
                role = "Builder",
                type = "TOOL",
                content = """
                    {
                      "name":"bounded-identity",
                      "domain":"calculation",
                      "language":"shell",
                      "inputSchema":{
                        "type":"object",
                        "additionalProperties":false,
                        "properties":{},
                        "required":[]
                      },
                      "outputSchema":{
                        "type":"object",
                        "additionalProperties":false,
                        "properties":{"ok":{"type":"boolean"}},
                        "required":["ok"]
                      },
                      "code":"cat >/dev/null\necho '{\"ok\":true}'",
                      "tests":[{"input":{},"expectedOutput":{"ok":true}}]
                    }
                """.trimIndent(),
                budgetSignature = "sig",
                wipTaskRef = "node-tool",
                generation = 1,
                groupId = groupId,
                runId = runId,
                submittedAtMs = 1_000L
            )
        )
        var calls = 0
        val disputeContexts = mutableListOf<String?>()
        val reviewerIds = mutableListOf<String>()
        val pipeline = CriticPipeline(
            db = database,
            runId = runId,
            groupId = groupId,
            generation = 1,
            openRouterApiKey = "sk-test",
            criticModel = "anthropic/claude-haiku-4.5",
            criticReviewRunner = { submission, _, disputeContext, criticId ->
                calls++
                disputeContexts += disputeContext
                reviewerIds += criticId
                if (calls == 1) {
                    CriticReview(
                        submissionId = submission.id,
                        outcome = CriticOutcome.REJECT,
                        criticAgentId = criticId,
                        rejectionReason = "Private fixtures are unavailable."
                    )
                } else {
                    CriticReview(
                        submissionId = submission.id,
                        outcome = CriticOutcome.PROMOTE,
                        criticAgentId = criticId,
                        initialTrustScore = 0.8
                    )
                }
            },
            toolsMountHostPath = dataRoot.resolve("tools").absolutePath,
            legacyDirectToolPromotionEnabled = true
        )

        val summary = pipeline.processPendingSubmissions()

        assertEquals(2, calls)
        assertEquals(1, summary.promoted)
        assertEquals(0, summary.rejected)
        assertEquals(1, summary.arbitrated)
        assertEquals(null, disputeContexts.first())
        assertEquals(2, reviewerIds.distinct().size)
        assertTrue(
            disputeContexts.last().orEmpty()
                .contains("deterministically preflight-valid TOOL")
        )
        assertFalse(disputeContexts.last().orEmpty().contains("Private fixtures are unavailable."))
        assertEquals("promoted", database.stagingDao().getById("tool-submission")?.status)
        assertNotNull(
            database.toolRegistryDao()
                .getLatestVersionByName(runId, groupId, "bounded-identity")
        )
    }

    @Test
    fun `preflight valid tool reject then dispute does not satisfy rejection quorum`() = runTest {
        val dataRoot = createTempDirectory("critic-pipeline-tool-dispute-").toFile()
        val runId = "run-tool-dispute"
        val groupId = "group-tool-dispute"
        val database = SubstrateDatabaseFactory(dataRoot).get(runId, groupId)
        database.stagingDao().insert(
            StagingSubmissionEntity(
                id = "tool-disputed-submission",
                agentId = "agent-builder",
                role = "Builder",
                type = "TOOL",
                content = """
                    {
                      "name":"bounded-identity",
                      "domain":"calculation",
                      "language":"shell",
                      "inputSchema":{
                        "type":"object",
                        "additionalProperties":false,
                        "properties":{},
                        "required":[]
                      },
                      "outputSchema":{
                        "type":"object",
                        "additionalProperties":false,
                        "properties":{"ok":{"type":"boolean"}},
                        "required":["ok"]
                      },
                      "code":"cat >/dev/null\necho '{\"ok\":true}'",
                      "tests":[{"input":{},"expectedOutput":{"ok":true}}]
                    }
                """.trimIndent(),
                budgetSignature = "sig",
                wipTaskRef = "node-tool",
                generation = 1,
                groupId = groupId,
                runId = runId,
                submittedAtMs = 1_000L
            )
        )
        var calls = 0
        val pipeline = CriticPipeline(
            db = database,
            runId = runId,
            groupId = groupId,
            generation = 1,
            openRouterApiKey = "sk-test",
            criticModel = "anthropic/claude-haiku-4.5",
            criticReviewRunner = { submission, _, _, criticId ->
                calls++
                if (calls == 1) {
                    CriticReview(
                        submissionId = submission.id,
                        outcome = CriticOutcome.REJECT,
                        criticAgentId = criticId,
                        rejectionReason = "One reviewer found a possible semantic defect."
                    )
                } else {
                    CriticReview(
                        submissionId = submission.id,
                        outcome = CriticOutcome.DISPUTE,
                        criticAgentId = criticId,
                        disputeReason = "The visible payload does not support a concrete rejection."
                    )
                }
            },
            toolsMountHostPath = dataRoot.resolve("tools").absolutePath,
            legacyDirectToolPromotionEnabled = true
        )

        val summary = pipeline.processPendingSubmissions()

        assertEquals(2, calls)
        assertEquals(1, summary.promoted)
        assertEquals(0, summary.rejected)
        assertEquals(1, summary.arbitrated)
        assertEquals(
            "promoted",
            database.stagingDao().getById("tool-disputed-submission")?.status
        )
        assertEquals(
            0.5,
            database.stagingDao().getById("tool-disputed-submission")?.initialTrustScore
        )
    }

    @Test
    fun `failed second tool review leaves submission pending without false rejection`() = runTest {
        val dataRoot = createTempDirectory("critic-pipeline-tool-second-failure-").toFile()
        val runId = "run-tool-second-failure"
        val groupId = "group-tool-second-failure"
        val database = SubstrateDatabaseFactory(dataRoot).get(runId, groupId)
        database.stagingDao().insert(
            StagingSubmissionEntity(
                id = "tool-second-failure",
                agentId = "agent-builder",
                role = "Builder",
                type = "TOOL",
                content = """
                    {
                      "name":"bounded-identity",
                      "domain":"calculation",
                      "language":"shell",
                      "inputSchema":{
                        "type":"object",
                        "additionalProperties":false,
                        "properties":{},
                        "required":[]
                      },
                      "outputSchema":{
                        "type":"object",
                        "additionalProperties":false,
                        "properties":{"ok":{"type":"boolean"}},
                        "required":["ok"]
                      },
                      "code":"cat >/dev/null\necho '{\"ok\":true}'",
                      "tests":[{"input":{},"expectedOutput":{"ok":true}}]
                    }
                """.trimIndent(),
                budgetSignature = "sig",
                wipTaskRef = "node-tool",
                generation = 1,
                groupId = groupId,
                runId = runId,
                submittedAtMs = 1_000L
            )
        )
        var calls = 0
        val pipeline = CriticPipeline(
            db = database,
            runId = runId,
            groupId = groupId,
            generation = 1,
            openRouterApiKey = "sk-test",
            criticReviewRunner = { submission, _, _, criticId ->
                calls++
                if (calls == 2) error("second reviewer unavailable")
                CriticReview(
                    submissionId = submission.id,
                    outcome = CriticOutcome.REJECT,
                    criticAgentId = criticId,
                    rejectionReason = "First reviewer rejected."
                )
            },
            toolsMountHostPath = dataRoot.resolve("tools").absolutePath
        )

        val summary = pipeline.processPendingSubmissions()

        assertEquals(2, calls)
        assertEquals(0, summary.promoted)
        assertEquals(0, summary.rejected)
        assertEquals(0, summary.arbitrated)
        assertEquals("pending", database.stagingDao().getById("tool-second-failure")?.status)
        assertTrue(
            ContributionGraphRepository(database)
                .criticDecisionsForRound(runId, groupId, 1)
                .isEmpty()
        )
    }

    @Test
    fun `deterministically invalid tool is rejected without model arbitration`() = runTest {
        val dataRoot = createTempDirectory("critic-pipeline-invalid-tool-").toFile()
        val runId = "run-invalid-tool"
        val groupId = "group-invalid-tool"
        val database = SubstrateDatabaseFactory(dataRoot).get(runId, groupId)
        database.stagingDao().insert(
            StagingSubmissionEntity(
                id = "invalid-tool-submission",
                agentId = "agent-builder",
                role = "Builder",
                type = "TOOL",
                content = """{"name":"missing-contracts"}""",
                budgetSignature = "sig",
                wipTaskRef = "node-tool",
                generation = 1,
                groupId = groupId,
                runId = runId,
                submittedAtMs = 1_000L
            )
        )
        var calls = 0
        val pipeline = CriticPipeline(
            db = database,
            runId = runId,
            groupId = groupId,
            generation = 1,
            openRouterApiKey = "sk-test",
            criticModel = "anthropic/claude-haiku-4.5",
            criticReviewRunner = { submission, _, _, criticId ->
                calls++
                CriticReview(
                    submissionId = submission.id,
                    outcome = CriticOutcome.PROMOTE,
                    criticAgentId = criticId,
                    initialTrustScore = 0.9
                )
            },
            toolsMountHostPath = dataRoot.resolve("tools").absolutePath
        )

        val summary = pipeline.processPendingSubmissions()

        assertEquals(0, calls)
        assertEquals(0, summary.promoted)
        assertEquals(1, summary.rejected)
        assertEquals(0, summary.arbitrated)
        assertEquals(
            "rejected",
            database.stagingDao().getById("invalid-tool-submission")?.status
        )
    }

    @Test
    fun `post critic tool promotion failure persists exact safe reason`() = runTest {
        val dataRoot = createTempDirectory("critic-pipeline-tool-promotion-reason-").toFile()
        val runId = "run-tool-promotion-reason"
        val groupId = "group-tool-promotion-reason"
        val nodeId = NodeId("node-tool")
        val database = SubstrateDatabaseFactory(dataRoot).get(runId, groupId)
        val commitment = ToolOpportunityCommitment(
            runId = runId,
            atlasHash = "atlas-hash",
            nodeId = nodeId,
            opportunity = ToolOpportunity(
                opportunityId = "normalize-opportunity",
                opportunityType = ToolOpportunityType.TRANSFORMATION,
                repeatedQuestionPattern = "Normalize bounded values.",
                requiredInputs = listOf(
                    TypedField("value", TypedFieldType.NUMBER, "Input value.")
                ),
                requiredOutputs = listOf(
                    TypedField("normalized", TypedFieldType.NUMBER, "Normalized value.")
                ),
                validationStrategy = "Run the committed private boundary.",
                expectedReuseNodeIds = setOf(NodeId("node-later")),
                preferredImplementationMode = ToolImplementationMode.SHELL,
                behavior = ToolBehavior.DETERMINISTIC,
                promotionTestIds = setOf("identity-test"),
                maximumRuntimeMillis = 1_000,
                maximumOutputBytes = 4_096
            )
        ).withComputedHash()
        val inputSchema = TypedToolSchemaCompiler.compile(
            commitment.opportunity.requiredInputs
        )
        val outputSchema = TypedToolSchemaCompiler.compile(
            commitment.opportunity.requiredOutputs
        )
        database.stagingDao().insert(
            StagingSubmissionEntity(
                id = "tool-private-mismatch",
                agentId = "agent-builder",
                role = "Builder",
                type = "TOOL",
                content = """
                    {
                      "opportunityId":"normalize-opportunity",
                      "name":"bounded-normalizer",
                      "domain":"calculation",
                      "language":"shell",
                      "inputSchema":$inputSchema,
                      "outputSchema":$outputSchema,
                      "code":"input=${'$'}(cat)\ncase \"${'$'}input\" in *987654*) echo '{\"normalized\":654321.0}' ;; *) echo '{\"normalized\":1.0}' ;; esac",
                      "tests":[{
                        "testId":"identity-test",
                        "input":{"value":1.0},
                        "expectedOutput":{"normalized":1.0}
                      }]
                    }
                """.trimIndent(),
                budgetSignature = "sig",
                wipTaskRef = nodeId.value,
                generation = 1,
                groupId = groupId,
                runId = runId,
                submittedAtMs = 1_000L
            )
        )
        val promotionService = ToolPromotionService(
            database = database,
            runId = runId,
            groupId = groupId,
            toolsMountHostPath = dataRoot.resolve("tools").absolutePath,
            opportunityResolver = { commitment },
            sealedPromotionTestResolver = {
                listOf(
                    SealedPromotionTestFixture(
                        id = "identity-test",
                        input = Json.parseToJsonElement("""{"value":987654}"""),
                        expectedOutput = Json.parseToJsonElement(
                            """{"normalized":123456.0}"""
                        )
                    )
                )
            }
        )
        val pipeline = CriticPipeline(
            db = database,
            runId = runId,
            groupId = groupId,
            generation = 1,
            openRouterApiKey = "sk-test",
            criticReviewRunner = { submission, _, _, criticId ->
                CriticReview(
                    submissionId = submission.id,
                    outcome = CriticOutcome.PROMOTE,
                    criticAgentId = criticId,
                    initialTrustScore = 0.9
                )
            },
            toolsMountHostPath = dataRoot.resolve("tools").absolutePath,
            toolPromotionService = promotionService,
            legacyDirectToolPromotionEnabled = false
        )

        val summary = pipeline.processPendingSubmissions()

        assertEquals(0, summary.promoted)
        assertEquals(1, summary.rejected)
        val reviewed = assertNotNull(
            database.stagingDao().getById("tool-private-mismatch")
        )
        assertEquals("rejected", reviewed.status)
        assertEquals(
            "tool_promotion_private_test_failed",
            reviewed.rejectionReason
        )
        assertFalse(reviewed.rejectionReason.orEmpty().contains("987654"))
        assertFalse(reviewed.rejectionReason.orEmpty().contains("123456"))
        assertFalse(reviewed.rejectionReason.orEmpty().contains("654321"))
        val structured = assertNotNull(
            pipeline.toolPromotionOutcomesSnapshot()["tool-private-mismatch"]
        )
        assertEquals(ToolPromotionStatus.REJECTED_PRIVATE_AUTHORITY, structured.status)
        assertEquals(
            ToolRepairDisclosureClass.PRIVATE_FAILURE_ONLY,
            structured.disclosureClass
        )
        assertEquals(
            null,
            database.toolRegistryDao().getLatestVersionByName(
                runId,
                groupId,
                "bounded-normalizer"
            )
        )
    }

    @Test
    fun `publication valid typed methodology split verdict promotes at reduced trust`() = runTest {
        val dataRoot = createTempDirectory("critic-pipeline-methodology-split-").toFile()
        val runId = "run-methodology-split"
        val groupId = "group-methodology-split"
        val database = SubstrateDatabaseFactory(dataRoot).get(runId, groupId)
        database.stagingDao().insert(
            methodologySubmission(
                id = "typed-methodology-split",
                runId = runId,
                groupId = groupId
            )
        )
        var calls = 0
        val disputeContexts = mutableListOf<String?>()
        val reviewerIds = mutableListOf<String>()
        val pipeline = CriticPipeline(
            db = database,
            runId = runId,
            groupId = groupId,
            generation = 1,
            openRouterApiKey = "sk-test",
            criticModel = "anthropic/claude-haiku-4.5",
            criticReviewRunner = { submission, _, disputeContext, criticId ->
                calls++
                disputeContexts += disputeContext
                reviewerIds += criticId
                if (calls == 1) {
                    CriticReview(
                        submissionId = submission.id,
                        outcome = CriticOutcome.REJECT,
                        criticAgentId = criticId,
                        rejectionReason = "The mandatory test is not sufficiently receiver-specific."
                    )
                } else {
                    CriticReview(
                        submissionId = submission.id,
                        outcome = CriticOutcome.PROMOTE,
                        criticAgentId = criticId,
                        initialTrustScore = 0.9
                    )
                }
            },
            toolsMountHostPath = dataRoot.resolve("tools").absolutePath
        )

        val summary = pipeline.processPendingSubmissions()

        assertEquals(2, calls)
        assertEquals(1, summary.promoted)
        assertEquals(0, summary.rejected)
        assertEquals(1, summary.arbitrated)
        assertEquals(null, disputeContexts.first())
        assertEquals(2, reviewerIds.distinct().size)
        assertTrue(
            disputeContexts.last().orEmpty().contains(
                "publication-valid typed grader methodology"
            )
        )
        assertFalse(
            disputeContexts.last().orEmpty().contains(
                "The mandatory test is not sufficiently receiver-specific."
            )
        )
        val reviewed = assertNotNull(database.stagingDao().getById("typed-methodology-split"))
        assertEquals("promoted", reviewed.status)
        assertEquals(0.5, reviewed.initialTrustScore)
        val decision = ContributionGraphRepository(database)
            .criticDecisionsForRound(runId, groupId, 1)
            .single()
        assertEquals(reviewerIds.toSet(), decision.reviewerAgentIds)
        assertTrue(decision.hasValidHash())
    }

    @Test
    fun `publication valid typed methodology requires two concrete rejections`() = runTest {
        val dataRoot = createTempDirectory("critic-pipeline-methodology-reject-").toFile()
        val runId = "run-methodology-reject"
        val groupId = "group-methodology-reject"
        val database = SubstrateDatabaseFactory(dataRoot).get(runId, groupId)
        database.stagingDao().insert(
            methodologySubmission(
                id = "typed-methodology-reject",
                runId = runId,
                groupId = groupId
            )
        )
        var calls = 0
        val pipeline = CriticPipeline(
            db = database,
            runId = runId,
            groupId = groupId,
            generation = 1,
            openRouterApiKey = "sk-test",
            criticModel = "anthropic/claude-haiku-4.5",
            criticReviewRunner = { submission, _, _, criticId ->
                calls++
                CriticReview(
                    submissionId = submission.id,
                    outcome = CriticOutcome.REJECT,
                    criticAgentId = criticId,
                    rejectionReason = if (calls == 1) {
                        "The mandatory test has no receiver-bound assertion."
                    } else {
                        "The failure mode is not falsifiable from the canonical snapshot."
                    }
                )
            },
            toolsMountHostPath = dataRoot.resolve("tools").absolutePath
        )

        val summary = pipeline.processPendingSubmissions()

        assertEquals(2, calls)
        assertEquals(0, summary.promoted)
        assertEquals(1, summary.rejected)
        assertEquals(1, summary.arbitrated)
        val reviewed = assertNotNull(database.stagingDao().getById("typed-methodology-reject"))
        assertEquals("rejected", reviewed.status)
        assertTrue(
            reviewed.rejectionReason.orEmpty().contains(
                "The mandatory test has no receiver-bound assertion."
            )
        )
    }

    @Test
    fun `ordinary plan update retains single review rejection`() = runTest {
        val dataRoot = createTempDirectory("critic-pipeline-plan-update-").toFile()
        val runId = "run-plan-update"
        val groupId = "group-plan-update"
        val database = SubstrateDatabaseFactory(dataRoot).get(runId, groupId)
        database.stagingDao().insert(
            methodologySubmission(
                id = "ordinary-plan-update",
                runId = runId,
                groupId = groupId,
                budgetSignature = "ordinary-budget-signature"
            )
        )
        var calls = 0
        val pipeline = CriticPipeline(
            db = database,
            runId = runId,
            groupId = groupId,
            generation = 1,
            openRouterApiKey = "sk-test",
            criticModel = "anthropic/claude-haiku-4.5",
            criticReviewRunner = { submission, _, _, criticId ->
                calls++
                CriticReview(
                    submissionId = submission.id,
                    outcome = CriticOutcome.REJECT,
                    criticAgentId = criticId,
                    rejectionReason = "Ordinary plan update rejected."
                )
            },
            toolsMountHostPath = dataRoot.resolve("tools").absolutePath
        )

        val summary = pipeline.processPendingSubmissions()

        assertEquals(1, calls)
        assertEquals(0, summary.promoted)
        assertEquals(1, summary.rejected)
        assertEquals(0, summary.arbitrated)
        assertEquals(
            "rejected",
            database.stagingDao().getById("ordinary-plan-update")?.status
        )
    }

    @Test
    fun `methodology signature does not grant quorum to another submission type`() = runTest {
        val dataRoot = createTempDirectory("critic-pipeline-prefixed-doc-").toFile()
        val runId = "run-prefixed-doc"
        val groupId = "group-prefixed-doc"
        val database = SubstrateDatabaseFactory(dataRoot).get(runId, groupId)
        database.stagingDao().insert(
            methodologySubmission(
                id = "prefixed-doc",
                runId = runId,
                groupId = groupId,
                type = "DOC",
                wipTaskRef = "node-doc"
            )
        )
        var calls = 0
        val pipeline = CriticPipeline(
            db = database,
            runId = runId,
            groupId = groupId,
            generation = 1,
            openRouterApiKey = "sk-test",
            criticModel = "anthropic/claude-haiku-4.5",
            criticReviewRunner = { submission, _, _, criticId ->
                calls++
                CriticReview(
                    submissionId = submission.id,
                    outcome = CriticOutcome.REJECT,
                    criticAgentId = criticId,
                    rejectionReason = "DOC rejection."
                )
            },
            toolsMountHostPath = dataRoot.resolve("tools").absolutePath
        )

        val summary = pipeline.processPendingSubmissions()

        assertEquals(1, calls)
        assertEquals(0, summary.promoted)
        assertEquals(1, summary.rejected)
        assertEquals(0, summary.arbitrated)
    }

    private fun methodologySubmission(
        id: String,
        runId: String,
        groupId: String,
        type: String = "PLAN_UPDATE",
        wipTaskRef: String? = null,
        budgetSignature: String =
            "${GRADER_METHODOLOGY_DRAFT_SIGNATURE_PREFIX}grader-builder:1"
    ): StagingSubmissionEntity = StagingSubmissionEntity(
        id = id,
        agentId = "grader-builder",
        role = "Builder",
        type = type,
        content = """
            {
              "rubricDimensions":[{
                "dimensionId":"receiver-value",
                "scoringGuidance":"Award only for receiver-visible evidence."
              }],
              "automatedTests":[{
                "testId":"receiver-obligation",
                "description":"Verify the canonical snapshot contains receiver-bound evidence.",
                "expectedOutputPattern":"receiver",
                "penaltyOnFailure":1.0,
                "isMandatory":true,
                "mappedRequirementIds":["receiver-requirement"],
                "inputSchemaHash":"input-contract-hash",
                "expectedOutcomeHash":"expected-outcome-hash",
                "difficulty":0.7
              }],
              "failureModes":[{
                "modeId":"unsupported-claim",
                "description":"Claims exceed observable canonical evidence.",
                "penaltyWeight":1.0
              }],
              "requirements":[{
                "requirementId":"receiver-requirement",
                "graderAtlasNodeId":"grader-node",
                "mappedWorkerNodeIds":["worker-node"],
                "mappedReceiverObligationRefs":["receiver-obligation"],
                "criterion":"Canonical evidence must satisfy the receiver obligation.",
                "observableEvidence":"Receiver-bound evidence in the canonical snapshot.",
                "passThreshold":"At least one exact receiver-bound evidence record.",
                "failureLevels":[{
                  "level":1,
                  "description":"No receiver-bound evidence is present.",
                  "scoreCeiling":0.0,
                  "critical":true
                }],
                "adversarialCases":[{
                  "caseId":"unsupported-prose",
                  "setup":"Provide unsupported prose without canonical evidence.",
                  "expectedDetection":"The mandatory test fails.",
                  "boundedCost":1.0
                }],
                "executableTestIds":["receiver-obligation"],
                "disclosureSensitivity":"GRADER_INTERNAL",
                "rationale":"The receiver requires observable evidence.",
                "noveltyFingerprint":"receiver-requirement-v1",
                "maximumEvaluationCost":2.0
              }]
            }
        """.trimIndent(),
        wipTaskRef = wipTaskRef,
        budgetSignature = budgetSignature,
        generation = 1,
        groupId = groupId,
        runId = runId,
        submittedAtMs = 1_000L
    )
}
