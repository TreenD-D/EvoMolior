package com.achulkov.evomolior.orchestration.acceptance

import com.achulkov.evomolior.core.contract.CriticOutcome
import com.achulkov.evomolior.core.contract.CriticReview
import com.achulkov.evomolior.orchestration.lifecycle.CriticPipeline
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Phase3KnowledgeGraphAcceptanceTest {

    @Test
    fun `ten generations promote fifty facts with no hard constraint violations`() = runTest {
        val dataRoot = createTempDirectory("phase-3-kg-acceptance-").toFile()
        val substrateFactory = SubstrateDatabaseFactory(dataRoot)
        val runId = "run-phase-3-acceptance"
        val groupId = "group-0"
        val db = substrateFactory.get(runId, groupId)
        val toolsRoot = File(dataRoot, "tools")
        val schema = """
            {
              "relationTypes": ["SUPPORTS", "REFINES", "CAUSES"],
              "entityTypes": ["Concept", "Evidence"],
              "hardConstraints": [
                {
                  "id": "HC-NO-CAUSES",
                  "description": "CAUSES edges are forbidden in this evidence graph",
                  "expression": "RELATION:CAUSES"
                }
              ],
              "softConstraints": []
            }
        """.trimIndent()
        var generationOneFactId: String? = null

        for (generation in 1..10) {
            repeat(5) { index ->
                val provenance = if (generation == 10 && index == 0) {
                    "PROVENANCE: ${requireNotNull(generationOneFactId)}"
                } else {
                    ""
                }
                db.stagingDao().insert(
                    factSubmission(
                        id = "valid-$generation-$index",
                        runId = runId,
                        groupId = groupId,
                        generation = generation,
                        relation = if (index % 2 == 0) "SUPPORTS" else "REFINES",
                        contentSuffix = provenance
                    )
                )
            }
            db.stagingDao().insert(
                factSubmission(
                    id = "hard-violation-$generation",
                    runId = runId,
                    groupId = groupId,
                    generation = generation,
                    relation = "CAUSES"
                )
            )

            val summary = CriticPipeline(
                db = db,
                runId = runId,
                groupId = groupId,
                generation = generation,
                openRouterApiKey = "sk-test",
                kgSchema = schema,
                criticReviewRunner = { staged, _, _, criticId ->
                    CriticReview(
                        submissionId = staged.id,
                        outcome = CriticOutcome.PROMOTE,
                        criticAgentId = criticId,
                        initialTrustScore = 0.9
                    )
                },
                toolsMountHostPath = toolsRoot.absolutePath
            ).processPendingSubmissions()

            val generationRows = db.stagingDao().getAll(runId, groupId)
                .filter { it.generation == generation }
            assertEquals(
                5,
                summary.promoted,
                generationRows.joinToString { "${it.id}:${it.status}:${it.rejectionReason}" }
            )
            assertEquals(
                1,
                summary.rejected,
                generationRows.joinToString { "${it.id}:${it.status}:${it.rejectionReason}" }
            )
            if (generation == 1) {
                generationOneFactId = db.knowledgeGraphFactDao()
                    .getByGeneration(runId, groupId, 1)
                    .first()
                    .id
            }
        }

        val facts = db.knowledgeGraphFactDao()
            .queryByTriplePattern(runId, groupId, "%", "%", "%", 100)
        assertEquals(50, facts.size)
        assertEquals((1..10).toSet(), facts.map { it.generation }.toSet())
        assertTrue(facts.none { it.relation == "CAUSES" })
        val generationTenReuse = facts.single {
            it.generation == 10 && it.subject == "Concept-10-0:Concept"
        }
        assertTrue(generationTenReuse.provenanceJson.contains(assertNotNull(generationOneFactId)))

        val rejectedViolations = db.stagingDao().getAll(runId, groupId)
            .filter { it.id.startsWith("hard-violation-") }
        assertEquals(10, rejectedViolations.size)
        assertTrue(rejectedViolations.all { it.status == "rejected" })
        assertTrue(rejectedViolations.all { it.rejectionReason?.contains("hard constraint") == true })
    }

    private fun factSubmission(
        id: String,
        runId: String,
        groupId: String,
        generation: Int,
        relation: String,
        contentSuffix: String = ""
    ): StagingSubmissionEntity = StagingSubmissionEntity(
        id = id,
        runId = runId,
        groupId = groupId,
        agentId = "researcher-generation-$generation",
        role = "Researcher",
        type = "KG_FACT",
        content = buildString {
            appendLine("SUBJECT: Concept-$generation-${id.substringAfterLast("-")}:Concept")
            appendLine("RELATION: $relation")
            appendLine("OBJECT: Evidence-$generation-${id.substringAfterLast("-")}:Evidence")
            appendLine("CONFIDENCE: 0.9")
            appendLine("DOMAIN: acceptance")
            if (contentSuffix.isNotBlank()) append(contentSuffix)
        },
        generation = generation,
        budgetSignature = "budget:generation-$generation",
        wipTaskRef = "dst:root",
        status = "pending",
        submittedAtMs = generation * 1_000L,
        reviewedAtMs = null
    )
}
