package com.achulkov.evomolior.api.grader

import com.achulkov.evomolior.agents.factory.GraderAgentContext
import com.achulkov.evomolior.agents.factory.StructuredDimensionScoreRequest
import com.achulkov.evomolior.competition.grader.CanonSnapshotExporter
import com.achulkov.evomolior.competition.grader.GraderGroupManager
import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.config.LlmBackend
import com.achulkov.evomolior.core.config.ModelSelectionConfig
import com.achulkov.evomolior.core.grader.GraderCriteriaFreeze
import com.achulkov.evomolior.core.model.DimensionScore
import com.achulkov.evomolior.core.model.Role
import com.achulkov.evomolior.core.model.ScoringDimension
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import com.achulkov.evomolior.substrate.grader.GraderAuthorityRepository
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.reflect.full.callSuspend

/**
 * Verifies that the judging phase reads grader criteria from the grader substrate and
 * combines them with competing-group canon snapshots before the judge agent is launched.
 */
class GraderRunnerJudgingPhaseTest {

    @Test
    fun `text-only score parser accepts only validated grader json schema`() {
        val runner = GraderRunner(
            openRouterApiKey = "sk-test",
            substrateFactory = SubstrateDatabaseFactory(createTempDirectory("grader-parser-test").toFile()),
            localAgentRunner = { "" }
        )

        val output = """
            {
              "scores": [
                {"targetGroupId":"group-0","dimensionId":"correctness","rawScore":71.5,"rationale":"json score works"},
                {"targetGroupId":"group-0","dimensionId":"evidence_quality","rawScore":64,"rationale":"second json score works"},
                {"targetGroupId":"group-0","dimensionId":"implementation_feasibility","rawScore":88,"rationale":"third json score works"}
              ]
            }
        """.trimIndent()

        val scores = runner.parseTextOnlyGradingScores(
            output = output,
            allowedGroupIds = setOf("group-0"),
            allowedDimensionIds = setOf("correctness", "evidence_quality", "implementation_feasibility")
        ).associateBy { it.dimensionId }

        assertEquals(setOf("correctness", "evidence_quality", "implementation_feasibility"), scores.keys)
        assertEquals(71.5, scores.getValue("correctness").rawScore)
        assertEquals(64.0, scores.getValue("evidence_quality").rawScore)
        assertEquals(88.0, scores.getValue("implementation_feasibility").rawScore)
        assertContains(scores.getValue("evidence_quality").justification, "second json score works")
        assertContains(scores.getValue("implementation_feasibility").justification, "third json score works")
    }

    @Test
    fun `text-only score parser rejects out of range and duplicate coverage`() {
        val runner = GraderRunner(
            openRouterApiKey = "sk-test",
            substrateFactory = SubstrateDatabaseFactory(createTempDirectory("grader-parser-clamp-test").toFile()),
            localAgentRunner = { "" }
        )

        val outOfRangeScores = runner.parseTextOnlyGradingScores(
            output = """
                {"scores":[
                  {"targetGroupId":"group-0","dimensionId":"correctness","rawScore":120,"rationale":"above max"},
                  {"targetGroupId":"group-0","dimensionId":"evidence_quality","rawScore":45,"rationale":"normal score"}
                ]}
            """.trimIndent(),
            allowedGroupIds = setOf("group-0"),
            allowedDimensionIds = setOf("correctness", "evidence_quality")
        )
        val duplicateScores = runner.parseTextOnlyGradingScores(
            output = """
                {"scores":[
                  {"targetGroupId":"group-0","dimensionId":"correctness","rawScore":80,"rationale":"first"},
                  {"targetGroupId":"group-0","dimensionId":"correctness","rawScore":15,"rationale":"duplicate"},
                  {"targetGroupId":"group-0","dimensionId":"evidence_quality","rawScore":45,"rationale":"normal score"}
                ]}
            """.trimIndent(),
            allowedGroupIds = setOf("group-0"),
            allowedDimensionIds = setOf("correctness", "evidence_quality")
        )

        assertTrue(outOfRangeScores.isEmpty())
        assertTrue(duplicateScores.isEmpty())
    }

    @Test
    fun `text-only score parser rejects prose scores without json schema`() {
        val runner = GraderRunner(
            openRouterApiKey = "sk-test",
            substrateFactory = SubstrateDatabaseFactory(createTempDirectory("grader-parser-reject-prose-test").toFile()),
            localAgentRunner = { "" }
        )

        val scores = runner.parseTextOnlyGradingScores(
            output = "GRADING_SCORE targetGroupId=group-0 dimensionId=correctness rawScore=71.5 rationale=legacy prose",
            allowedGroupIds = setOf("group-0"),
            allowedDimensionIds = setOf("correctness")
        )

        assertTrue(scores.isEmpty())
    }

    @Test
    fun `text-only score parser rejects malformed json score schema`() {
        val runner = GraderRunner(
            openRouterApiKey = "sk-test",
            substrateFactory = SubstrateDatabaseFactory(createTempDirectory("grader-parser-reject-malformed-json-test").toFile()),
            localAgentRunner = { "" }
        )

        val scores = runner.parseTextOnlyGradingScores(
            output = """
                {"scores":[{"targetGroupId":"group-0","dimensionId":"correctness","rawScore":71.5,"rationale":"ok","extra":"not allowed"}]}
            """.trimIndent(),
            allowedGroupIds = setOf("group-0"),
            allowedDimensionIds = setOf("correctness")
        )

        assertTrue(scores.isEmpty())
    }

    @Test
    fun `criteria phase preserves substantive text output when PLAN_UPDATE was not staged`() = runTest {
        val dataRoot = createTempDirectory("grader-criteria-recovery-test").toFile()
        val factory = SubstrateDatabaseFactory(dataRoot)
        val runId = "run-criteria-recovery"
        val graderGroupId = "grader-0"
        val dimensions = listOf(
            ScoringDimension(
                id = "evidence_fidelity",
                name = "Evidence Fidelity",
                description = "Reward candidate-level evidence traceability.",
                weight = 1.0
            ),
            ScoringDimension(
                id = "table_completeness",
                name = "Table Completeness",
                description = "Reward complete required CSV tables.",
                weight = 1.0
            )
        )
        val criteriaText = """
            Evidence Fidelity criteria:
            LOW: generic summary without candidate-level evidence or citations.
            MEDIUM: partial candidate evidence with some missing rationale.
            HIGH: every inclusion/exclusion links to uploaded-artifact evidence and a traceable rationale.
            Indicators: candidate IDs, outcome direction, population match, comparator match.
            Anti-pattern: polished synthesis that omits hard negatives or duplicates candidate rows.
            Failure mode: excluding a candidate because abstract wording is unfamiliar rather than because PICO fails.
            Tie-breaker: prefer more reproducible extraction notes.
            Hard-to-fake signal: a complete audit trail for each candidate decision.

            Table Completeness criteria:
            LOW: required tables are absent or omit known candidates.
            MEDIUM: tables exist but duplicate or omit rows.
            HIGH: all candidate IDs appear in the correct required tables with no duplicate rows.
        """.trimIndent()

        val runner = GraderRunner(
            openRouterApiKey = "sk-test",
            llmBackend = LlmBackend.CODEX_CHATGPT,
            substrateFactory = factory,
            localAgentRunner = { criteriaText },
            graderAgentTimeoutMs = 1_000L,
            graderModelOverride = ModelSelectionConfig.CODEX_CHATGPT_GPT56_LUNA_MODEL
        )

        runner.runCriteriaPhase(
            runId = runId,
            graderGroupIds = listOf(graderGroupId),
            roundIndex = 0,
            runObjective = "Produce a systematic review pack from uploaded candidates.",
            scoringDimensions = dimensions
        )

        val criteriaEntries = factory.getGrader(runId, graderGroupId)
            .stagingDao()
            .getAll(runId, graderGroupId)
            .filter { it.type == "PLAN_UPDATE" && it.role == Role.GraderResearcher.name }

        assertEquals(1, criteriaEntries.size)
        val recoveredCriteria = criteriaEntries.single()
        assertTrue(recoveredCriteria.id.startsWith("direct-graderresearcher"))
        assertTrue(recoveredCriteria.agentId.startsWith("criteria-graderresearcher-"))
        assertTrue(
            recoveredCriteria.budgetSignature.startsWith(
                "DIRECT_AGENT_OUTPUT:${recoveredCriteria.agentId}:"
            )
        )
        assertEquals(0.7, recoveredCriteria.initialTrustScore)
        assertFalse(recoveredCriteria.content.contains("RECOVERED GRADER CRITERIA"))
        assertContains(recoveredCriteria.content, "Evidence Fidelity criteria")
        assertContains(recoveredCriteria.content, "Table Completeness criteria")
        assertFalse(recoveredCriteria.content.contains("SYNTHETIC GRADER CRITERIA FALLBACK"))
    }

    @Test
    fun `judging phase feeds grader criteria and competing snapshots into the round judge`() = runTest {
        val dataRoot = createTempDirectory("grader-judging-phase-test").toFile()
        val factory = SubstrateDatabaseFactory(dataRoot)
        val runId = "run-1"
        val graderGroupId = "grader-0"
        val competingGroupIds = listOf("group-0", "group-1")

        val graderDb = factory.getGrader(runId, graderGroupId)
        val competingDb0 = factory.get(runId, competingGroupIds[0])
        val competingDb1 = factory.get(runId, competingGroupIds[1])

        graderDb.stagingDao().insert(
            StagingSubmissionEntity(
                id = "grader-criteria-1",
                agentId = "criteria-agent",
                role = Role.GraderResearcher.name,
                type = "PLAN_UPDATE",
                content = "CRITERION-ALPHA: reward reproducible evidence and calibrated scoring.",
                budgetSignature = "budget:grader-criteria",
                status = "promoted",
                generation = 1,
                groupId = graderGroupId,
                runId = runId,
                submittedAtMs = 1_000L,
                reviewedAtMs = 1_500L
            )
        )

        competingDb0.stagingDao().insert(
            StagingSubmissionEntity(
                id = "group-0-snapshot-1",
                agentId = "agent-0",
                role = "Researcher",
                type = "DOC",
                content = "SNAPSHOT-0: group-0 has a promoted document that should appear in canon export.",
                budgetSignature = "budget:group-0",
                status = "promoted",
                generation = 1,
                groupId = competingGroupIds[0],
                runId = runId,
                submittedAtMs = 2_000L,
                reviewedAtMs = 2_100L
            )
        )

        competingDb1.stagingDao().insert(
            StagingSubmissionEntity(
                id = "group-1-snapshot-1",
                agentId = "agent-1",
                role = "Builder",
                type = "DOC",
                content = "SNAPSHOT-1: group-1 has a promoted document that should appear in canon export.",
                budgetSignature = "budget:group-1",
                status = "promoted",
                generation = 1,
                groupId = competingGroupIds[1],
                runId = runId,
                submittedAtMs = 3_000L,
                reviewedAtMs = 3_100L
            )
        )

        val scoringDimensions = listOf(
            ScoringDimension(
                id = "accuracy",
                name = "Accuracy",
                description = "Reward evidence-based evaluation.",
                weight = 1.0
            )
        )
        val graderGroupManager = GraderGroupManager(scoringDimensions)

        var capturedPromptText = ""
        var capturedRole: Role? = null
        val capturedTargetGroupIds = mutableListOf<String>()
        var capturedTaskPrompt: String? = null
        val runner = GraderRunner(
            openRouterApiKey = "sk-test",
            substrateFactory = factory,
            localAgentRunner = { context ->
                capturedRole = context.role
                capturedTargetGroupIds += context.targetGroupId
                capturedTaskPrompt = context.taskPrompt
                submitScoreThroughJudgeTool(
                    context = context,
                    targetGroupId = context.targetGroupId,
                    dimensionId = "accuracy",
                    rawScore = if (context.targetGroupId == competingGroupIds[0]) 88.0 else 72.0,
                    rationale = "Prompt contained the grader criteria and ${context.targetGroupId} snapshot."
                )
                ""
            },
            graderAgentTimeoutMs = 1_000L
        )

        runner.runJudgingPhase(
            runId = runId,
            graderGroupIds = listOf(graderGroupId),
            competingGroupIds = competingGroupIds,
            roundIndex = 0,
            runObjective = "evaluate the run objectively",
            scoringDimensions = scoringDimensions,
            graderGroupManager = graderGroupManager
        )

        assertEquals(Role.GraderCritic, capturedRole)
        assertEquals(competingGroupIds, capturedTargetGroupIds)
        assertNotNull(capturedTaskPrompt)
        assertContains(capturedTaskPrompt!!, "submit_grading_score")

        capturedPromptText = runner.buildRoundJudgePrompt(
            runObjective = "evaluate the run objectively",
            scoringDimensions = scoringDimensions,
            criteriaContext = "=== RECENT FAILURE TELEMETRY ===\nignored\n\n[Criteria by GraderResearcher]\nCRITERION-ALPHA: reward reproducible evidence and calibrated scoring.",
            allGroupSnapshots = mapOf(
                competingGroupIds[0] to "=== CANON SNAPSHOT: Group 'group-0' (Round 0) ===\nSNAPSHOT-0",
                competingGroupIds[1] to "=== CANON SNAPSHOT: Group 'group-1' (Round 0) ===\nSNAPSHOT-1"
            ),
            roundIndex = 0
        )

        assertContains(capturedPromptText, "CRITERION-ALPHA")
        assertContains(capturedPromptText, "SNAPSHOT-0")
        assertContains(capturedPromptText, "SNAPSHOT-1")
        assertContains(capturedPromptText, "=== GRADING CRITERIA (developed by criteria agents this round) ===")
        assertContains(capturedPromptText, "=== COMPETING GROUP CANON SNAPSHOTS (Round 0) ===")

        val roundScores = graderGroupManager.group(graderGroupId)
            .computeRoundScores(competingGroupIds, 0)
            .associateBy { it.groupId }

        assertEquals(2, roundScores.size)
        assertEquals(88.0, roundScores.getValue(competingGroupIds[0]).dimensionScores.single().rawScore)
        assertEquals(72.0, roundScores.getValue(competingGroupIds[1]).dimensionScores.single().rawScore)

        val graderRows = graderDb.stagingDao().getAll(runId, graderGroupId)
        val competingRows0 = competingDb0.stagingDao().getAll(runId, competingGroupIds[0])
        val competingRows1 = competingDb1.stagingDao().getAll(runId, competingGroupIds[1])

        assertTrue(graderRows.any { it.content.contains("CRITERION-ALPHA") })
        assertTrue(competingRows0.any { it.content.contains("SNAPSHOT-0") })
        assertTrue(competingRows1.any { it.content.contains("SNAPSHOT-1") })
    }

    @Test
    fun `judging phase retries once when round judge emits no scores`() = runTest {
        val dataRoot = createTempDirectory("grader-judging-retry-test").toFile()
        val factory = SubstrateDatabaseFactory(dataRoot)
        val runId = "run-retry"
        val graderGroupId = "grader-0"
        val competingGroupId = "group-0"

        val graderDb = factory.getGrader(runId, graderGroupId)
        val competingDb = factory.get(runId, competingGroupId)

        graderDb.stagingDao().insert(
            StagingSubmissionEntity(
                id = "grader-criteria-retry-1",
                agentId = "criteria-agent",
                role = Role.GraderResearcher.name,
                type = "PLAN_UPDATE",
                content = "CRITERION-RETRY: score the concrete deliverable evidence.",
                budgetSignature = "budget:grader-criteria",
                status = "promoted",
                generation = 1,
                groupId = graderGroupId,
                runId = runId,
                submittedAtMs = 1_000L,
                reviewedAtMs = 1_500L
            )
        )
        competingDb.stagingDao().insert(
            StagingSubmissionEntity(
                id = "group-0-snapshot-retry-1",
                agentId = "agent-0",
                role = "Builder",
                type = "DOC",
                content = "SNAPSHOT-RETRY-0: group-0 promoted document.",
                budgetSignature = "budget:group-0",
                status = "promoted",
                generation = 1,
                groupId = competingGroupId,
                runId = runId,
                submittedAtMs = 2_000L,
                reviewedAtMs = 2_100L
            )
        )

        val scoringDimensions = listOf(
            ScoringDimension(
                id = "accuracy",
                name = "Accuracy",
                description = "Reward evidence-based evaluation.",
                weight = 1.0
            )
        )
        val graderGroupManager = GraderGroupManager(scoringDimensions)
        var attempts = 0
        var retryPrompt = ""

        val runner = GraderRunner(
            openRouterApiKey = "sk-test",
            substrateFactory = factory,
            localAgentRunner = { context ->
                attempts += 1
                if (attempts == 2) {
                    retryPrompt = context.systemPrompt
                    submitScoreThroughJudgeTool(
                        context = context,
                        targetGroupId = context.targetGroupId,
                        dimensionId = "accuracy",
                        rawScore = 83.0,
                        rationale = "Retry supplied the required score."
                    )
                }
                ""
            },
            graderAgentTimeoutMs = 1_000L
        )

        runner.runJudgingPhase(
            runId = runId,
            graderGroupIds = listOf(graderGroupId),
            competingGroupIds = listOf(competingGroupId),
            roundIndex = 0,
            runObjective = "evaluate the run objectively",
            scoringDimensions = scoringDimensions,
            graderGroupManager = graderGroupManager
        )

        assertEquals(2, attempts)
        assertContains(retryPrompt, "RETRY REQUIRED: MISSING GRADING SCORES")
        assertContains(retryPrompt, "accuracy")

        val roundScores = graderGroupManager.group(graderGroupId)
            .computeRoundScores(listOf(competingGroupId), 0)
            .single()

        assertEquals(83.0, roundScores.dimensionScores.single().rawScore)
    }

    @Test
    fun `judging phase leaves coverage incomplete when retry still emits no scores`() = runTest {
        val dataRoot = createTempDirectory("grader-judging-fallback-score-test").toFile()
        val factory = SubstrateDatabaseFactory(dataRoot)
        val runId = "run-fallback-score"
        val graderGroupId = "grader-0"
        val competingGroupId = "group-0"

        val graderDb = factory.getGrader(runId, graderGroupId)
        val competingDb = factory.get(runId, competingGroupId)

        graderDb.stagingDao().insert(
            StagingSubmissionEntity(
                id = "grader-criteria-fallback-score-1",
                agentId = "criteria-agent",
                role = Role.GraderResearcher.name,
                type = "PLAN_UPDATE",
                content = "CRITERION-FALLBACK: score the deliverable evidence.",
                budgetSignature = "budget:grader-criteria",
                status = "promoted",
                generation = 1,
                groupId = graderGroupId,
                runId = runId,
                submittedAtMs = 1_000L,
                reviewedAtMs = 1_500L
            )
        )
        competingDb.stagingDao().insert(
            StagingSubmissionEntity(
                id = "group-0-snapshot-fallback-score-1",
                agentId = "agent-0",
                role = "Builder",
                type = "DOC",
                content = """
                    # systematic_review_pack.md
                    included_studies.csv
                    excluded_hard_negatives.csv
                    exclusion_reasons.csv
                    evidence_table.csv
                    PMIDs 22146094 9472867 and candidate IDs 4565 4566 4567 4568 have abstract evidence and rationale.
                """.trimIndent(),
                budgetSignature = "budget:group-0",
                status = "promoted",
                generation = 1,
                groupId = competingGroupId,
                runId = runId,
                submittedAtMs = 2_000L,
                reviewedAtMs = 2_100L
            )
        )

        val scoringDimensions = listOf(
            ScoringDimension(
                id = "accuracy",
                name = "Accuracy",
                description = "Reward evidence-based evaluation.",
                weight = 1.0
            )
        )
        val graderGroupManager = GraderGroupManager(scoringDimensions)
        var attempts = 0

        val runner = GraderRunner(
            openRouterApiKey = "sk-test",
            substrateFactory = factory,
            localAgentRunner = {
                attempts += 1
                ""
            },
            graderAgentTimeoutMs = 1_000L,
            structuredDimensionScoreAuthor = { """{"scores":[]}""" }
        )

        runner.runJudgingPhase(
            runId = runId,
            graderGroupIds = listOf(graderGroupId),
            competingGroupIds = listOf(competingGroupId),
            roundIndex = 0,
            runObjective = "evaluate the run objectively",
            scoringDimensions = scoringDimensions,
            graderGroupManager = graderGroupManager
        )

        assertEquals(2, attempts)
        assertFalse(graderGroupManager.hasCompleteRoundScores(listOf(competingGroupId), 0))
    }

    @Test
    fun `structured repair appends only dimensions omitted by both interactive attempts`() = runTest {
        val dataRoot = createTempDirectory("grader-structured-dimension-repair-test").toFile()
        val factory = SubstrateDatabaseFactory(dataRoot)
        val runId = "run-structured-dimension-repair"
        val graderGroupId = "grader-0"
        val competingGroupId = "group-0"
        val graderDb = factory.getGrader(runId, graderGroupId)
        val competingDb = factory.get(runId, competingGroupId)

        graderDb.stagingDao().insert(
            StagingSubmissionEntity(
                id = "grader-criteria-structured-repair",
                agentId = "criteria-agent",
                role = Role.GraderResearcher.name,
                type = "PLAN_UPDATE",
                content = "Score accuracy and usability against durable receiver-facing evidence.",
                budgetSignature = "budget:grader-criteria",
                status = "promoted",
                generation = 1,
                groupId = graderGroupId,
                runId = runId,
                submittedAtMs = 1_000L,
                reviewedAtMs = 1_500L
            )
        )
        competingDb.stagingDao().insert(
            StagingSubmissionEntity(
                id = "group-0-structured-repair-snapshot",
                agentId = "agent-0",
                role = "Builder",
                type = "DOC",
                content = "A promoted receiver-facing report with traceable evidence.",
                budgetSignature = "budget:group-0",
                status = "promoted",
                generation = 1,
                groupId = competingGroupId,
                runId = runId,
                submittedAtMs = 2_000L,
                reviewedAtMs = 2_100L
            )
        )

        val targetSnapshot = CanonSnapshotExporter.exportForJudge(
            competingDb,
            runId,
            competingGroupId,
            0
        )
        val targetSnapshotHash = AtlasHashing.hashText(targetSnapshot)
        val targetGroupId = AtlasGroupId(competingGroupId)
        val freeze = GraderCriteriaFreeze(
            freezeId = "freeze-structured-dimension-repair",
            runId = runId,
            round = 0,
            graderGroupId = graderGroupId,
            graderAtlasHash = "grader-atlas-hash",
            requirementHashes = emptySet(),
            hiddenTestHashes = emptySet(),
            targetSnapshotHashes = mapOf(targetGroupId to targetSnapshotHash),
            sealedAtEpochMillis = 500L
        ).withComputedHash()
        GraderAuthorityRepository(factory).appendCriteriaFreeze(
            freeze,
            freeze.targetSnapshotHashes
        )

        val dimensions = listOf(
            ScoringDimension("accuracy", "Accuracy", "Evidence-based correctness.", 0.6),
            ScoringDimension("usability", "Usability", "Receiver utility.", 0.4)
        )
        val manager = GraderGroupManager(dimensions)
        var interactiveAttempts = 0
        val structuredRequests = mutableListOf<StructuredDimensionScoreRequest>()
        val runner = GraderRunner(
            openRouterApiKey = "sk-test",
            substrateFactory = factory,
            localAgentRunner = { context ->
                interactiveAttempts += 1
                if (interactiveAttempts == 1) {
                    submitScoreThroughJudgeTool(
                        context = context,
                        targetGroupId = competingGroupId,
                        dimensionId = "accuracy",
                        rawScore = 88.0,
                        rationale = "Interactive score that must remain unchanged."
                    )
                }
                ""
            },
            graderAgentTimeoutMs = 1_000L,
            structuredDimensionScoreAuthor = { request ->
                structuredRequests += request
                """
                    {"scores":[
                      {"dimensionId":"usability","rawScore":76.0,
                       "rationale":"The promoted report is usable but lacks an operator checklist."}
                    ]}
                """.trimIndent()
            }
        )

        runner.runJudgingPhase(
            runId = runId,
            graderGroupIds = listOf(graderGroupId),
            competingGroupIds = listOf(competingGroupId),
            roundIndex = 0,
            runObjective = "Evaluate the complete deliverable.",
            scoringDimensions = dimensions,
            graderGroupManager = manager
        )

        assertEquals(2, interactiveAttempts)
        val request = structuredRequests.single()
        assertEquals(graderGroupId, request.graderGroupId)
        assertEquals(competingGroupId, request.targetGroupId)
        assertEquals(freeze.freezeHash, request.criteriaFreezeHash)
        assertEquals(targetSnapshotHash, request.targetSnapshotHash)
        assertEquals(listOf("accuracy"), request.alreadyAuthoredScores.map { it.dimensionId })
        assertEquals(88.0, request.alreadyAuthoredScores.single().rawScore)
        assertEquals(listOf("usability"), request.missingDimensions.map { it.id })
        assertEquals(null, request.validationError)

        val scores = manager.group(graderGroupId)
            .computeRoundScores(listOf(competingGroupId), 0)
            .single()
            .dimensionScores
            .associateBy { it.dimensionId }
        assertEquals(88.0, scores.getValue("accuracy").rawScore)
        assertEquals(
            "Interactive score that must remain unchanged.",
            scores.getValue("accuracy").justification
        )
        assertEquals(76.0, scores.getValue("usability").rawScore)
    }

    @Test
    fun `structured repair rejects non-exact missing-dimension responses without mutation`() =
        runTest {
            val dimensions = listOf(
                ScoringDimension("accuracy", "Accuracy", "Accuracy.", 0.6),
                ScoringDimension("usability", "Usability", "Usability.", 0.4)
            )
            val existing = DimensionScore(
                dimensionId = "accuracy",
                rawScore = 88.0,
                justification = "Existing interactive authority."
            )
            val malformedResponses = mapOf(
                "unknown" to
                    """{"scores":[{"dimensionId":"unknown","rawScore":50.0,"rationale":"x"}]}""",
                "duplicate" to
                    """{"scores":[{"dimensionId":"usability","rawScore":50.0,"rationale":"x"},{"dimensionId":"usability","rawScore":51.0,"rationale":"y"}]}""",
                "already-authored" to
                    """{"scores":[{"dimensionId":"accuracy","rawScore":1.0,"rationale":"overwrite"}]}""",
                "missing" to """{"scores":[]}""",
                "out-of-range" to
                    """{"scores":[{"dimensionId":"usability","rawScore":101.0,"rationale":"x"}]}"""
            )

            malformedResponses.forEach { (caseName, response) ->
                val requests = mutableListOf<StructuredDimensionScoreRequest>()
                val runner = GraderRunner(
                    openRouterApiKey = "sk-test",
                    substrateFactory = SubstrateDatabaseFactory(
                        createTempDirectory("grader-structured-reject-$caseName").toFile()
                    ),
                    localAgentRunner = { "" },
                    structuredDimensionScoreAuthor = { request ->
                        requests += request
                        response
                    }
                )
                val collectedScores = mutableMapOf(
                    "group-0" to mutableListOf(existing)
                )

                runner.repairMissingDimensionScores(
                    runId = "run-reject-$caseName",
                    graderGroupId = "grader-0",
                    targetGroupId = "group-0",
                    runObjective = "Evaluate.",
                    criteriaFreezeHash = "freeze-hash",
                    targetSnapshotHash = "snapshot-hash",
                    criteriaContext = "Frozen criteria.",
                    targetSnapshot = "Target snapshot.",
                    scoringDimensions = dimensions,
                    collectedScores = collectedScores
                )

                assertEquals(listOf(existing), collectedScores.getValue("group-0"), caseName)
                assertEquals(2, requests.size, caseName)
                assertEquals(null, requests.first().validationError, caseName)
                assertNotNull(requests.last().validationError, caseName)
                assertEquals(
                    listOf("accuracy"),
                    requests.last().alreadyAuthoredScores.map { it.dimensionId },
                    caseName
                )
                assertEquals(
                    listOf("usability"),
                    requests.last().missingDimensions.map { it.id },
                    caseName
                )
            }
        }

    @Test
    fun `judging phase parses text-only round judge score contract`() = runTest {
        val dataRoot = createTempDirectory("grader-judging-text-score-test").toFile()
        val factory = SubstrateDatabaseFactory(dataRoot)
        val runId = "run-text-score"
        val graderGroupId = "grader-0"
        val competingGroupId = "group-0"

        val graderDb = factory.getGrader(runId, graderGroupId)
        val competingDb = factory.get(runId, competingGroupId)

        graderDb.stagingDao().insert(
            StagingSubmissionEntity(
                id = "grader-criteria-text-score-1",
                agentId = "criteria-agent",
                role = Role.GraderResearcher.name,
                type = "PLAN_UPDATE",
                content = "CRITERION-TEXT: score the deliverable evidence.",
                budgetSignature = "budget:grader-criteria",
                status = "promoted",
                generation = 1,
                groupId = graderGroupId,
                runId = runId,
                submittedAtMs = 1_000L,
                reviewedAtMs = 1_500L
            )
        )
        competingDb.stagingDao().insert(
            StagingSubmissionEntity(
                id = "group-0-snapshot-text-score-1",
                agentId = "agent-0",
                role = "Builder",
                type = "DOC",
                content = "SNAPSHOT-TEXT-SCORE-0: group-0 promoted document.",
                budgetSignature = "budget:group-0",
                status = "promoted",
                generation = 1,
                groupId = competingGroupId,
                runId = runId,
                submittedAtMs = 2_000L,
                reviewedAtMs = 2_100L
            )
        )

        val scoringDimensions = listOf(
            ScoringDimension(
                id = "accuracy",
                name = "Accuracy",
                description = "Reward evidence-based evaluation.",
                weight = 1.0
            )
        )
        val graderGroupManager = GraderGroupManager(scoringDimensions)
        var attempts = 0

        val runner = GraderRunner(
            openRouterApiKey = "sk-test",
            substrateFactory = factory,
            localAgentRunner = {
                attempts += 1
                """
                {"scores":[{"targetGroupId":"group-0","dimensionId":"accuracy","rawScore":74.5,"rationale":"Promoted report exists, but no external validation was present."}]}
                """.trimIndent()
            },
            graderAgentTimeoutMs = 1_000L
        )

        runner.runJudgingPhase(
            runId = runId,
            graderGroupIds = listOf(graderGroupId),
            competingGroupIds = listOf(competingGroupId),
            roundIndex = 0,
            runObjective = "evaluate the run objectively",
            scoringDimensions = scoringDimensions,
            graderGroupManager = graderGroupManager
        )

        assertEquals(1, attempts)
        val score = graderGroupManager.group(graderGroupId)
            .computeRoundScores(listOf(competingGroupId), 0)
            .single()
            .dimensionScores
            .single()

        assertEquals("accuracy", score.dimensionId)
        assertEquals(74.5, score.rawScore)
        assertContains(score.justification, "Text-only RoundJudge score")
        assertContains(score.justification, "no external validation")
    }

    @Test
    fun `judging phase retries malformed text-only coverage and accepts only complete retry`() = runTest {
        val dataRoot = createTempDirectory("grader-judging-malformed-retry-test").toFile()
        val factory = SubstrateDatabaseFactory(dataRoot)
        val runId = "run-malformed-retry"
        val graderGroupId = "grader-0"
        val competingGroupId = "group-0"

        factory.getGrader(runId, graderGroupId).stagingDao().insert(
            StagingSubmissionEntity(
                id = "grader-criteria-malformed-retry",
                agentId = "criteria-agent",
                role = Role.GraderResearcher.name,
                type = "PLAN_UPDATE",
                content = "Score every required dimension from durable evidence.",
                budgetSignature = "budget:grader-criteria",
                status = "promoted",
                generation = 1,
                groupId = graderGroupId,
                runId = runId,
                submittedAtMs = 1_000L,
                reviewedAtMs = 1_500L
            )
        )
        factory.get(runId, competingGroupId).stagingDao().insert(
            StagingSubmissionEntity(
                id = "group-0-malformed-retry-snapshot",
                agentId = "agent-0",
                role = "Builder",
                type = "DOC",
                content = "A promoted deliverable with auditable evidence.",
                budgetSignature = "budget:group-0",
                status = "promoted",
                generation = 1,
                groupId = competingGroupId,
                runId = runId,
                submittedAtMs = 2_000L,
                reviewedAtMs = 2_100L
            )
        )

        val dimensions = listOf(
            ScoringDimension("accuracy", "Accuracy", "Evidence-based correctness.", 0.6),
            ScoringDimension("usability", "Usability", "Practical utility.", 0.4)
        )
        val manager = GraderGroupManager(dimensions)
        var attempts = 0
        var retryPrompt = ""
        val runner = GraderRunner(
            openRouterApiKey = "sk-test",
            llmBackend = LlmBackend.CODEX_CHATGPT,
            substrateFactory = factory,
            localAgentRunner = { context ->
                attempts += 1
                if (attempts == 1) {
                    """{"scores":[{"targetGroupId":"group-0","dimensionId":"accuracy","rawScore":99,"rationale":"partial must not become authoritative"}]}"""
                } else {
                    retryPrompt = context.systemPrompt
                    """
                        {"scores":[
                          {"targetGroupId":"group-0","dimensionId":"accuracy","rawScore":81,"rationale":"complete retry accuracy"},
                          {"targetGroupId":"group-0","dimensionId":"usability","rawScore":73,"rationale":"complete retry usability"}
                        ]}
                    """.trimIndent()
                }
            },
            graderAgentTimeoutMs = 1_000L,
            graderModelOverride = ModelSelectionConfig.CODEX_CHATGPT_GPT56_LUNA_MODEL
        )

        runner.runJudgingPhase(
            runId = runId,
            graderGroupIds = listOf(graderGroupId),
            competingGroupIds = listOf(competingGroupId),
            roundIndex = 0,
            runObjective = "Evaluate the complete deliverable.",
            scoringDimensions = dimensions,
            graderGroupManager = manager
        )

        assertEquals(2, attempts)
        assertContains(retryPrompt, "RETRY REQUIRED: MISSING GRADING SCORES")
        assertContains(retryPrompt, "accuracy, usability")
        val scores = manager.group(graderGroupId)
            .computeRoundScores(listOf(competingGroupId), 0)
            .single()
            .dimensionScores
            .associateBy { it.dimensionId }
        assertEquals(81.0, scores.getValue("accuracy").rawScore)
        assertEquals(73.0, scores.getValue("usability").rawScore)
    }

    @Test
    fun `judging phase retries only missing dimensions after partial score submission`() = runTest {
        val dataRoot = createTempDirectory("grader-judging-partial-retry-test").toFile()
        val factory = SubstrateDatabaseFactory(dataRoot)
        val runId = "run-partial-retry"
        val graderGroupId = "grader-0"
        val competingGroupId = "group-0"

        val graderDb = factory.getGrader(runId, graderGroupId)
        val competingDb = factory.get(runId, competingGroupId)

        graderDb.stagingDao().insert(
            StagingSubmissionEntity(
                id = "grader-criteria-partial-retry-1",
                agentId = "criteria-agent",
                role = Role.GraderResearcher.name,
                type = "PLAN_UPDATE",
                content = "CRITERION-PARTIAL: score accuracy and usability separately.",
                budgetSignature = "budget:grader-criteria",
                status = "promoted",
                generation = 1,
                groupId = graderGroupId,
                runId = runId,
                submittedAtMs = 1_000L,
                reviewedAtMs = 1_500L
            )
        )
        competingDb.stagingDao().insert(
            StagingSubmissionEntity(
                id = "group-0-snapshot-partial-retry-1",
                agentId = "agent-0",
                role = "Builder",
                type = "DOC",
                content = "SNAPSHOT-PARTIAL-RETRY-0: group-0 promoted document.",
                budgetSignature = "budget:group-0",
                status = "promoted",
                generation = 1,
                groupId = competingGroupId,
                runId = runId,
                submittedAtMs = 2_000L,
                reviewedAtMs = 2_100L
            )
        )

        val scoringDimensions = listOf(
            ScoringDimension(
                id = "accuracy",
                name = "Accuracy",
                description = "Reward evidence-based evaluation.",
                weight = 0.6
            ),
            ScoringDimension(
                id = "usability",
                name = "Usability",
                description = "Reward usable deliverables.",
                weight = 0.4
            )
        )
        val graderGroupManager = GraderGroupManager(scoringDimensions)
        var attempts = 0
        var retryPrompt = ""

        val runner = GraderRunner(
            openRouterApiKey = "sk-test",
            substrateFactory = factory,
            localAgentRunner = { context ->
                attempts += 1
                if (attempts == 1) {
                    submitScoreThroughJudgeTool(
                        context = context,
                        targetGroupId = context.targetGroupId,
                        dimensionId = "accuracy",
                        rawScore = 88.0,
                        rationale = "First attempt only scored accuracy."
                    )
                } else {
                    retryPrompt = context.systemPrompt
                    submitScoreThroughJudgeTool(
                        context = context,
                        targetGroupId = context.targetGroupId,
                        dimensionId = "usability",
                        rawScore = 76.0,
                        rationale = "Retry supplied the missing usability score."
                    )
                }
                ""
            },
            graderAgentTimeoutMs = 1_000L
        )

        runner.runJudgingPhase(
            runId = runId,
            graderGroupIds = listOf(graderGroupId),
            competingGroupIds = listOf(competingGroupId),
            roundIndex = 0,
            runObjective = "evaluate the run objectively",
            scoringDimensions = scoringDimensions,
            graderGroupManager = graderGroupManager
        )

        assertEquals(2, attempts)
        assertContains(retryPrompt, "RETRY REQUIRED: MISSING GRADING SCORES")
        assertContains(retryPrompt, "usability")
        assertFalse(retryPrompt.contains("accuracy, usability"))

        val scoresByDimension = graderGroupManager.group(graderGroupId)
            .computeRoundScores(listOf(competingGroupId), 0)
            .single()
            .dimensionScores
            .associateBy { it.dimensionId }

        assertEquals(88.0, scoresByDimension.getValue("accuracy").rawScore)
        assertEquals(76.0, scoresByDimension.getValue("usability").rawScore)
    }

    @Test
    fun `round judge can execute submit_grading_score from its live tool registry`() = runTest {
        val dataRoot = createTempDirectory("grader-judging-live-tool-test").toFile()
        val factory = SubstrateDatabaseFactory(dataRoot)
        val runId = "run-live-tool"
        val graderGroupId = "grader-0"
        val competingGroupId = "group-0"
        val graderDb = factory.getGrader(runId, graderGroupId)
        val competingDb = factory.get(runId, competingGroupId)

        graderDb.stagingDao().insert(
            StagingSubmissionEntity(
                id = "grader-criteria-live-1",
                agentId = "criteria-agent",
                role = Role.GraderResearcher.name,
                type = "PLAN_UPDATE",
                content = "CRITERION-LIVE: reward reproducible evidence and calibrated scoring.",
                budgetSignature = "budget:grader-criteria",
                status = "promoted",
                generation = 1,
                groupId = graderGroupId,
                runId = runId,
                submittedAtMs = 1_000L,
                reviewedAtMs = 1_500L
            )
        )
        competingDb.stagingDao().insert(
            StagingSubmissionEntity(
                id = "group-0-snapshot-live-1",
                agentId = "agent-0",
                role = "Builder",
                type = "DOC",
                content = "SNAPSHOT-LIVE-0: group-0 promoted document.",
                budgetSignature = "budget:group-0",
                status = "promoted",
                generation = 1,
                groupId = competingGroupId,
                runId = runId,
                submittedAtMs = 2_000L,
                reviewedAtMs = 2_100L
            )
        )

        val scoringDimensions = listOf(
            ScoringDimension(
                id = "accuracy",
                name = "Accuracy",
                description = "Reward evidence-based evaluation.",
                weight = 1.0
            )
        )
        val graderGroupManager = GraderGroupManager(scoringDimensions)
        var capturedToolName = ""

        val runner = GraderRunner(
            openRouterApiKey = "sk-test",
            substrateFactory = factory,
            localAgentRunner = { context ->
                val agent = context.javaClass.getMethod("getAgent").invoke(context)
                val toolRegistry = agent.javaClass.getMethod("getToolRegistry").invoke(agent)
                val getTools = toolRegistry.javaClass.methods.first { it.name == "getTools" }
                val tools = getTools.invoke(toolRegistry) as List<*>
                val submitTool = tools.single { it?.javaClass?.simpleName == "SubmitGradingScoreTool" }!!
                capturedToolName = submitTool.javaClass.simpleName

                val argsClass = submitTool.javaClass.declaredClasses.single { it.simpleName == "Args" }
                val args = argsClass.getDeclaredConstructor(
                    String::class.java,
                    String::class.java,
                    Double::class.javaPrimitiveType,
                    String::class.java
                ).newInstance(
                    competingGroupId,
                    "accuracy",
                    91.0,
                    "Live RoundJudge tool invocation."
                )

                submitTool::class.members.first { it.name == "execute" }
                    .callSuspend(submitTool, args)
                ""
            },
            graderAgentTimeoutMs = 1_000L
        )

        runner.runJudgingPhase(
            runId = runId,
            graderGroupIds = listOf(graderGroupId),
            competingGroupIds = listOf(competingGroupId),
            roundIndex = 0,
            runObjective = "evaluate the run objectively",
            scoringDimensions = scoringDimensions,
            graderGroupManager = graderGroupManager
        )

        assertEquals("SubmitGradingScoreTool", capturedToolName)
        val roundScores = graderGroupManager.group(graderGroupId)
            .computeRoundScores(listOf(competingGroupId), 0)
            .single()
        assertEquals(91.0, roundScores.dimensionScores.single().rawScore)
        assertTrue(roundScores.dimensionScores.single().justification.contains("Live RoundJudge"))
    }

    private suspend fun submitScoreThroughJudgeTool(
        context: GraderAgentContext,
        targetGroupId: String,
        dimensionId: String,
        rawScore: Double,
        rationale: String
    ) {
        val agent = context.javaClass.getMethod("getAgent").invoke(context)
        val toolRegistry = agent.javaClass.getMethod("getToolRegistry").invoke(agent)
        val getTools = toolRegistry.javaClass.methods.first { it.name == "getTools" }
        val tools = getTools.invoke(toolRegistry) as List<*>
        val submitTool = tools.single { it?.javaClass?.simpleName == "SubmitGradingScoreTool" }!!

        val argsClass = submitTool.javaClass.declaredClasses.single { it.simpleName == "Args" }
        val args = argsClass.getDeclaredConstructor(
            String::class.java,
            String::class.java,
            Double::class.javaPrimitiveType,
            String::class.java
        ).newInstance(
            targetGroupId,
            dimensionId,
            rawScore,
            rationale
        )

        submitTool::class.members.first { it.name == "execute" }
            .callSuspend(submitTool, args)
    }

}
