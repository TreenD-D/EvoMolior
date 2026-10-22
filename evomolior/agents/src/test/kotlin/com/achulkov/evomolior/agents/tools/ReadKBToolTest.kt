@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.achulkov.evomolior.agents.tools

import com.achulkov.evomolior.core.atlas.AtlasGroupId
import com.achulkov.evomolior.core.atlas.AtlasHashing
import com.achulkov.evomolior.core.contract.ContributionAuthorityKind
import com.achulkov.evomolior.core.contract.ContributionAuthorityRef
import com.achulkov.evomolior.core.contract.ContributionRelation
import com.achulkov.evomolior.core.contract.CriticDecisionRecord
import com.achulkov.evomolior.core.contract.CriticOutcome
import com.achulkov.evomolior.core.contract.CriticReview
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.db.entity.KnowledgeBaseEntryEntity
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import com.achulkov.evomolior.substrate.worker.ContributionGraphRepository
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.time.Instant

class ReadKBToolTest {

    @Test
    fun `blank query lists bounded KB headers without loading full content`() = runTest {
        val runId = "run-read-kb-blank"
        val groupId = "group-0"
        val db = SubstrateDatabaseFactory(createTempDirectory("read-kb-test-").toFile())
            .get(runId, groupId)
        db.knowledgeBaseDao().insert(
            KnowledgeBaseEntryEntity(
                id = "kb-1",
                content = """
                    # Prediction-market scanners

                    FULL BODY DETAIL: separate venue liquidity from event-lag hypotheses.
                """.trimIndent(),
                sourceAgentId = "agent-researcher",
                generation = 1,
                domain = "prediction-markets",
                groupId = groupId,
                runId = runId,
                createdAtMs = 1_000L
            )
        )

        val result = ReadKBTool(
            knowledgeBaseDao = db.knowledgeBaseDao(),
            runId = runId,
            groupId = groupId
        ).execute(ReadKBTool.Args())

        assertContains(result, "Found 1 organized active KB headers")
        assertContains(result, "Prediction-market scanners")
        assertFalse(result.contains("FULL BODY DETAIL"))
    }

    @Test
    fun `entry ids load selected KB bodies`() = runTest {
        val runId = "run-read-kb-id"
        val groupId = "group-0"
        val db = SubstrateDatabaseFactory(createTempDirectory("read-kb-test-").toFile())
            .get(runId, groupId)
        db.knowledgeBaseDao().insert(
            KnowledgeBaseEntryEntity(
                id = "kb-selected",
                content = """
                    # Selected evidence

                    FULL BODY DETAIL: use this exact claim.
                """.trimIndent(),
                sourceAgentId = "agent-researcher",
                generation = 1,
                domain = "evidence",
                groupId = groupId,
                runId = runId,
                createdAtMs = 1_000L
            )
        )

        val result = ReadKBTool(
            knowledgeBaseDao = db.knowledgeBaseDao(),
            runId = runId,
            groupId = groupId
        ).execute(ReadKBTool.Args(entryIds = listOf("kb-selected")))

        assertContains(result, "Loaded 1 selected KB entry by ID")
        assertContains(result, "Selected evidence")
        assertContains(result, "FULL BODY DETAIL")
    }

    @Test
    fun `full body read emits immutable downstream lineage before returning content`() = runTest {
        val runId = "run-read-kb-lineage"
        val groupId = "group-0"
        val db = SubstrateDatabaseFactory(createTempDirectory("read-kb-lineage-").toFile())
            .get(runId, groupId)
        val content = "# Canonical evidence\n\nExact body."
        val submission = StagingSubmissionEntity(
            id = "submission-lineage",
            agentId = "author",
            role = "Researcher",
            type = "DOC",
            content = content,
            wipTaskRef = "node-1",
            budgetSignature = "budget",
            status = "promoted",
            promotedArtifactRef = "kb:kb-lineage",
            generation = 1,
            groupId = groupId,
            runId = runId,
            submittedAtMs = 1_000,
            reviewedAtMs = 1_100
        )
        db.stagingDao().insert(submission)
        db.knowledgeBaseDao().insert(
            KnowledgeBaseEntryEntity(
                id = "kb-lineage",
                content = content,
                sourceAgentId = "author",
                generation = 1,
                domain = "evidence",
                groupId = groupId,
                runId = runId,
                createdAtMs = 1_100
            )
        )
        val graph = ContributionGraphRepository(db)
        graph.recordCriticDecision(
            CriticDecisionRecord(
                decisionId = "decision-lineage",
                runId = runId,
                groupId = AtlasGroupId(groupId),
                round = 1,
                submissionId = submission.id,
                submissionContentHash = AtlasHashing.hashText(content),
                resolvedReview = CriticReview(
                    submissionId = submission.id,
                    outcome = CriticOutcome.PROMOTE,
                    criticAgentId = "critic",
                    initialTrustScore = 0.8
                ),
                resultAuthority = ContributionAuthorityRef(
                    kind = ContributionAuthorityKind.CANONICAL_ARTIFACT,
                    authorityId = "kb:kb-lineage",
                    authorityHash = AtlasHashing.hashText(content)
                ),
                decidedAt = Instant.fromEpochMilliseconds(1_100)
            ).withComputedHash()
        )

        val result = ReadKBTool(
            knowledgeBaseDao = db.knowledgeBaseDao(),
            runId = runId,
            groupId = groupId,
            agentId = "reader",
            round = 2,
            nodeId = "node-2",
            contributionGraphRepository = graph,
            useIdFactory = { "use-1" }
        ).execute(ReadKBTool.Args(entryIds = listOf("kb-lineage")))

        assertContains(result, "Exact body")
        val record = requireNotNull(graph.downstreamUse("downstream-read:use-1"))
        assertEquals("reader", record.agentId)
        assertEquals("node-2", record.nodeId)
        assertEquals(
            1,
            graph.forRun(runId, groupId)
                .count { it.relation == ContributionRelation.READ_DOWNSTREAM }
        )
    }

    @Test
    fun `long bodies are paged with an explicit continuation offset`() = runTest {
        val runId = "run-read-kb-paging"
        val groupId = "group-0"
        val db = SubstrateDatabaseFactory(createTempDirectory("read-kb-paging-").toFile())
            .get(runId, groupId)
        val body = "# Long report\n\n" + "Inventory row with evidence. ".repeat(300) + "TAIL-MARKER"
        db.knowledgeBaseDao().insert(
            KnowledgeBaseEntryEntity(
                id = "kb-long",
                content = body,
                sourceAgentId = "author",
                generation = 1,
                domain = "evidence",
                groupId = groupId,
                runId = runId,
                createdAtMs = 1_000L
            )
        )
        val tool = ReadKBTool(knowledgeBaseDao = db.knowledgeBaseDao(), runId = runId, groupId = groupId)

        val index = tool.execute(ReadKBTool.Args())
        assertContains(index, "Length: ${body.length} chars")

        val first = tool.execute(ReadKBTool.Args(entryIds = listOf("kb-long")))
        assertFalse(first.contains("TAIL-MARKER"))
        assertContains(first, "offset=$KB_BODY_MAX_CHARS) to continue")

        val second = tool.execute(ReadKBTool.Args(entryIds = listOf("kb-long"), offset = KB_BODY_MAX_CHARS))
        assertContains(second, "Content (characters $KB_BODY_MAX_CHARS-${body.length} of ${body.length}):")
        assertContains(second, "TAIL-MARKER")
        assertFalse(second.contains("to continue"))
        assertEquals(body, body.substring(0, KB_BODY_MAX_CHARS) + body.substring(KB_BODY_MAX_CHARS))
    }

    @Test
    fun `entry without lineage anchor is still readable and simply not recorded`() = runTest {
        val runId = "run-read-kb-no-anchor"
        val groupId = "group-0"
        val db = SubstrateDatabaseFactory(createTempDirectory("read-kb-no-anchor-").toFile())
            .get(runId, groupId)
        db.knowledgeBaseDao().insert(
            KnowledgeBaseEntryEntity(
                id = "kb-rolled-up",
                content = "# Rolled-up evidence\n\nCanonical body without a Critic result record.",
                sourceAgentId = "system",
                generation = 1,
                domain = "evidence",
                groupId = groupId,
                runId = runId,
                createdAtMs = 1_000L
            )
        )
        val graph = ContributionGraphRepository(db)

        val result = ReadKBTool(
            knowledgeBaseDao = db.knowledgeBaseDao(),
            runId = runId,
            groupId = groupId,
            agentId = "archivist",
            round = 1,
            contributionGraphRepository = graph,
            useIdFactory = { "use-unanchored" }
        ).execute(ReadKBTool.Args(entryIds = listOf("kb-rolled-up")))

        // Previously this threw "KB read lacks immutable canonical authority" and the read failed.
        assertContains(result, "Canonical body without a Critic result record.")
        assertEquals(null, graph.downstreamUse("downstream-read:use-unanchored"))
    }

    @Test
    fun `blank query surfaces organized active headers and excludes archived history`() = runTest {
        val runId = "run-read-kb-organized"
        val groupId = "group-0"
        val db = SubstrateDatabaseFactory(createTempDirectory("read-kb-test-").toFile())
            .get(runId, groupId)
        db.knowledgeBaseDao().insertAll(
            listOf(
                KnowledgeBaseEntryEntity(
                    id = "kb-archived",
                    content = "# Archived stale note\n\nOld body.",
                    sourceAgentId = "agent-old",
                    generation = 0,
                    trustScore = 0.9,
                    domain = "evidence",
                    status = "archived",
                    groupId = groupId,
                    runId = runId,
                    createdAtMs = 100L
                ),
                KnowledgeBaseEntryEntity(
                    id = "kb-reused",
                    content = "# Reused older synthesis\n\nStill important.",
                    sourceAgentId = "agent-reused",
                    generation = 1,
                    trustScore = 0.6,
                    citationCount = 3,
                    domain = "evidence",
                    groupId = groupId,
                    runId = runId,
                    createdAtMs = 200L
                ),
                KnowledgeBaseEntryEntity(
                    id = "kb-novel",
                    content = "# Novel current finding\n\nFresh working-set entry.",
                    sourceAgentId = "agent-new",
                    generation = 4,
                    trustScore = 0.7,
                    domain = "evidence",
                    groupId = groupId,
                    runId = runId,
                    createdAtMs = 300L
                )
            )
        )

        val result = ReadKBTool(
            knowledgeBaseDao = db.knowledgeBaseDao(),
            runId = runId,
            groupId = groupId
        ).execute(ReadKBTool.Args())

        assertContains(result, "organized active KB headers")
        assertContains(result, "Reused older synthesis")
        assertContains(result, "Novel current finding")
        assertFalse(result.contains("Archived stale note"))
        assertTrue(result.indexOf("Reused older synthesis") < result.indexOf("Novel current finding"))
    }
}
