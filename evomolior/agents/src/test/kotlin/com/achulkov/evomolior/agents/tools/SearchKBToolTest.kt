package com.achulkov.evomolior.agents.tools

import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.db.entity.KnowledgeBaseEntryEntity
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class SearchKBToolTest {

    @Test
    fun `missing query decodes and lists bounded recent knowledge`() = runTest {
        val runId = "run-search-kb-missing-query"
        val groupId = "group-0"
        val db = SubstrateDatabaseFactory(createTempDirectory("search-kb-test-").toFile())
            .get(runId, groupId)
        db.knowledgeBaseDao().insert(
            KnowledgeBaseEntryEntity(
                id = "kb-1",
                content = "Systematic-review candidate 7016 needs explicit intervention-modality evidence.",
                sourceAgentId = "agent-researcher",
                generation = 1,
                domain = "screening",
                groupId = groupId,
                runId = runId,
                createdAtMs = 1_000L
            )
        )

        val decoded = Json.decodeFromString(SearchKBTool.Args.serializer(), """{"maxResults":5}""")
        assertEquals("", decoded.query)

        val result = SearchKBTool(
            knowledgeBaseDao = db.knowledgeBaseDao(),
            runId = runId,
            groupId = groupId
        ).execute(decoded)

        assertContains(result, "No semantic query supplied")
        assertContains(result, "7016")
    }

    @Test
    fun `blank query is bounded like read kb rather than embedding an empty query`() = runTest {
        val runId = "run-search-kb-blank-query"
        val groupId = "group-0"
        val db = SubstrateDatabaseFactory(createTempDirectory("search-kb-test-").toFile())
            .get(runId, groupId)
        db.knowledgeBaseDao().insertAll(
            (1..30).map { index ->
                KnowledgeBaseEntryEntity(
                    id = "kb-$index",
                    content = "Screening decision artifact $index",
                    sourceAgentId = "agent-researcher",
                    generation = index,
                    trustScore = index.toDouble(),
                    domain = "screening",
                    groupId = groupId,
                    runId = runId,
                    createdAtMs = index.toLong()
                )
            }
        )

        val result = SearchKBTool(
            knowledgeBaseDao = db.knowledgeBaseDao(),
            runId = runId,
            groupId = groupId
        ).execute(SearchKBTool.Args(query = "", maxResults = 100))

        assertContains(result, "No semantic query supplied; found 25 organized active KB headers")
        assertContains(result, "Screening decision artifact 30")
    }

    @Test
    fun `semantic search returns headers by default instead of full bodies`() = runTest {
        val runId = "run-search-kb-index-first"
        val groupId = "group-0"
        val db = SubstrateDatabaseFactory(createTempDirectory("search-kb-test-").toFile())
            .get(runId, groupId)
        db.knowledgeBaseDao().insert(
            KnowledgeBaseEntryEntity(
                id = "kb-secret",
                content = """
                    # Intervention modality

                    FULL BODY DETAIL: web delivery evidence is ambiguous.
                """.trimIndent(),
                sourceAgentId = "agent-researcher",
                generation = 1,
                domain = "screening",
                groupId = groupId,
                runId = runId,
                createdAtMs = 1_000L
            )
        )

        val result = SearchKBTool(
            knowledgeBaseDao = db.knowledgeBaseDao(),
            runId = runId,
            groupId = groupId
        ).execute(SearchKBTool.Args(query = "intervention web delivery"))

        assertContains(result, "KB headers")
        assertContains(result, "Intervention modality")
        kotlin.test.assertFalse(result.contains("FULL BODY DETAIL"))
    }
}
