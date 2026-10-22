package com.achulkov.evomolior.api.grader

import com.achulkov.evomolior.core.contract.GraderAutomatedTestDraft
import com.achulkov.evomolior.core.contract.GraderFailureModeDraft
import com.achulkov.evomolior.core.contract.GraderMethodologyDraft
import com.achulkov.evomolior.core.contract.GraderRubricDraft
import com.achulkov.evomolior.core.model.Role
import com.achulkov.evomolior.core.model.ScoringDimension
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.db.EvoMoliorDatabase
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Graders start each round from the previous round's accepted criteria and improve them.
 */
class GraderBaselineRefinementTest {
    private val runId = "run-grader-baseline"
    private val graderGroupId = "grader-0"
    private val dimensions = listOf(
        ScoringDimension(id = "accuracy", name = "Accuracy", description = "Evidence-backed accuracy.", weight = 1.0)
    )

    @Test
    fun `round zero authors criteria from scratch with a researcher and builder`() = runTest {
        val (db, runner, spawned) = fixture()

        runner.runCriteriaPhase(runId, listOf(graderGroupId), 0, "Evaluate sources.", dimensions)

        assertEquals(listOf(Role.GraderResearcher, Role.GraderBuilder), spawned.map { it.first })
        assertTrue(spawned.none { it.second.contains("ACCEPTED CRITERIA BASELINE") })
        assertNull(PriorRoundCriteriaBaseline.load(db, runId, graderGroupId, 0))
    }

    @Test
    fun `stable round revises the accepted baseline with a single builder`() = runTest {
        val (db, runner, spawned) = fixture()
        stageAcceptedRoundZero(db)

        runner.runCriteriaPhase(runId, listOf(graderGroupId), 1, "Evaluate sources.", dimensions)

        assertEquals(listOf(Role.GraderBuilder), spawned.map { it.first })
        val prompt = spawned.single().second
        assertTrue(prompt.contains("ACCEPTED CRITERIA BASELINE FROM THE PREVIOUS ROUND"), prompt)
        assertTrue(prompt.contains("BASELINE_DOC_MARKER"))
        assertTrue(prompt.contains("BASELINE_GUIDANCE_MARKER"))
        assertTrue(prompt.contains("CRITERIA REVISION — generation 2"))
    }

    @Test
    fun `failure telemetry brings the researcher back but still starts from the baseline`() = runTest {
        val (db, runner, spawned) = fixture()
        stageAcceptedRoundZero(db)
        db.stagingDao().insert(
            entry(
                id = "failure-1",
                role = "ScoringEngine",
                type = "FAILURE_TELEMETRY",
                content = "Check-in failed: discovery-only URLs were counted as evidence.",
                signature = "system:failure"
            )
        )

        runner.runCriteriaPhase(runId, listOf(graderGroupId), 1, "Evaluate sources.", dimensions)

        assertTrue(Role.GraderResearcher in spawned.map { it.first })
        assertTrue(spawned.all { it.second.contains("BASELINE_DOC_MARKER") })
    }

    @Test
    fun `loader takes the newest promoted methodology and criteria and ignores rejected or seed entries`() = runTest {
        val (db, _, _) = fixture()
        stageAcceptedRoundZero(db)
        db.stagingDao().insert(
            entry(id = "rejected-gen2", content = "REJECTED_MARKER", generation = 2, status = "rejected")
        )
        db.stagingDao().insert(
            entry(id = "seed-gen2", role = "SystemCriteriaSeed", content = "SEED_MARKER", generation = 2)
        )
        db.stagingDao().insert(entry(id = "doc-gen2", content = "NEWER_DOC_MARKER", generation = 2))

        val baseline = assertNotNull(PriorRoundCriteriaBaseline.load(db, runId, graderGroupId, 2))

        assertEquals(1, baseline.methodologyGeneration)
        assertEquals(2, baseline.documentsGeneration)
        assertEquals(listOf("doc-gen2"), baseline.criteriaDocuments.map { it.id })
        val rendered = baseline.render(boundMethodology = null, maxChars = 1_200)
        assertTrue(rendered.length <= 1_200)
        assertTrue(rendered.contains("BASELINE_GUIDANCE_MARKER"))
        assertTrue(rendered.contains("NEWER_DOC_MARKER"))
        assertTrue(!rendered.contains("REJECTED_MARKER") && !rendered.contains("SEED_MARKER"))
        // Criteria promoted in the current round are not part of its own baseline.
        assertEquals(1, PriorRoundCriteriaBaseline.load(db, runId, graderGroupId, 1)?.documentsGeneration)
    }

    private data class Fixture(
        val db: EvoMoliorDatabase,
        val runner: GraderRunner,
        val spawned: MutableList<Pair<Role, String>>
    )

    private fun fixture(): Fixture {
        val factory = SubstrateDatabaseFactory(createTempDirectory("grader-baseline-").toFile())
        val spawned = mutableListOf<Pair<Role, String>>()
        val runner = GraderRunner(
            openRouterApiKey = "sk-test",
            substrateFactory = factory,
            localAgentRunner = { context ->
                spawned += context.role to context.systemPrompt
                "Revised criteria for ${context.role}."
            },
            graderAgentTimeoutMs = 1_000L
        )
        return Fixture(factory.getGrader(runId, graderGroupId), runner, spawned)
    }

    private suspend fun stageAcceptedRoundZero(db: EvoMoliorDatabase) {
        val methodology = GraderMethodologyDraft(
            rubricDimensions = listOf(GraderRubricDraft("accuracy", "BASELINE_GUIDANCE_MARKER: cite inspected docs.")),
            automatedTests = listOf(
                GraderAutomatedTestDraft("test-access-date", "Requires access dates.", "accessed 20[0-9]{2}")
            ),
            failureModes = listOf(GraderFailureModeDraft("discovery-only", "Discovery URLs presented as evidence."))
        )
        db.stagingDao().insert(
            entry(
                id = "methodology-gen1",
                role = Role.GraderBuilder.name,
                content = Json.encodeToString(GraderMethodologyDraft.serializer(), methodology),
                signature = "GRADING_SHEET_DRAFT:builder-1:1"
            )
        )
        db.stagingDao().insert(entry(id = "doc-gen1", content = "BASELINE_DOC_MARKER: thresholds per dimension."))
    }

    private fun entry(
        id: String,
        role: String = Role.GraderResearcher.name,
        type: String = "PLAN_UPDATE",
        content: String,
        signature: String = "criteria-$id",
        generation: Int = 1,
        status: String = "promoted"
    ) = StagingSubmissionEntity(
        id = id,
        agentId = "agent-$id",
        role = role,
        type = type,
        content = content,
        budgetSignature = signature,
        status = status,
        generation = generation,
        groupId = graderGroupId,
        runId = runId,
        submittedAtMs = generation * 1_000L + id.length
    )
}
