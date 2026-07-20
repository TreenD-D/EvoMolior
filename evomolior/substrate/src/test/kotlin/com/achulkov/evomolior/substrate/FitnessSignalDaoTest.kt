package com.achulkov.evomolior.substrate

import com.achulkov.evomolior.substrate.db.entity.KnowledgeBaseEntryEntity
import com.achulkov.evomolior.substrate.db.entity.StagingSubmissionEntity
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies that DAO fitness signals represent reviewed agent work rather than runtime bookkeeping.
 */
class FitnessSignalDaoTest {

    @Test
    fun `fitness queries exclude death notes and synthetic runtime fallbacks`() = runTest {
        val runId = "run-fitness"
        val groupId = "group-fitness"
        val agentId = "agent-substantive"
        val db = SubstrateDatabaseFactory(
            createTempDirectory("fitness-signal-dao-test").toFile()
        ).get(runId, groupId)
        val stagingDao = db.stagingDao()

        listOf(
            submission(
                id = "promoted-doc",
                agentId = agentId,
                status = "promoted",
                type = "DOC",
                signature = "BUDGET:doc"
            ),
            submission(
                id = "rejected-fact",
                agentId = agentId,
                status = "rejected",
                type = "KG_FACT",
                signature = "BUDGET:fact"
            ),
            submission(
                id = "death-note",
                agentId = agentId,
                status = "promoted",
                type = "DEATH_NOTE",
                signature = "DEATH_NOTE:$agentId:1"
            ),
            submission(
                id = "text-only-death-note",
                agentId = agentId,
                status = "promoted",
                type = "DEATH_NOTE",
                signature = "TEXT_ONLY_BACKEND:$agentId:2"
            ),
            submission(
                id = "no-action",
                agentId = agentId,
                status = "promoted",
                type = "PLAN_UPDATE",
                signature = "NO_ACTION_FALLBACK:$agentId:3"
            ),
            submission(
                id = "contract-fallback",
                agentId = agentId,
                status = "rejected",
                type = "PLAN_UPDATE",
                signature = "SUBSTRATE_CONTRACT_FALLBACK:$agentId:4"
            ),
            submission(
                id = "pending-doc",
                agentId = agentId,
                status = "pending",
                type = "DOC",
                signature = "BUDGET:pending"
            ),
            submission(
                id = "synthetic-only",
                agentId = "agent-synthetic-only",
                status = "promoted",
                type = "PLAN_UPDATE",
                signature = "NO_ACTION_FALLBACK:agent-synthetic-only:5"
            )
        ).forEach { stagingDao.insert(it) }

        assertEquals(0.5, stagingDao.getAcceptanceRateForAgent(runId, groupId, agentId))
        assertEquals(1, stagingDao.getPromotedCountForAgent(runId, groupId, agentId))
        assertEquals(
            listOf(agentId),
            stagingDao.getDistinctAgentIds(runId, groupId, generation = 1)
        )
    }

    @Test
    fun `citation signal sums canonical non-death-note entries by author`() = runTest {
        val runId = "run-citations"
        val groupId = "group-citations"
        val agentId = "agent-author"
        val db = SubstrateDatabaseFactory(
            createTempDirectory("citation-signal-dao-test").toFile()
        ).get(runId, groupId)
        val knowledgeBaseDao = db.knowledgeBaseDao()

        knowledgeBaseDao.insert(kbEntry("active", agentId, citationCount = 3))
        knowledgeBaseDao.insert(
            kbEntry("archived", agentId, citationCount = 4).copy(status = "archived")
        )
        knowledgeBaseDao.insert(
            kbEntry("death-note", agentId, citationCount = 100).copy(isDeadEnd = true)
        )
        knowledgeBaseDao.insert(kbEntry("other-author", "other-agent", citationCount = 50))

        assertEquals(
            7L,
            knowledgeBaseDao.getCitationCountForAgent(runId, groupId, agentId)
        )
    }

    private fun submission(
        id: String,
        agentId: String,
        status: String,
        type: String,
        signature: String
    ) = StagingSubmissionEntity(
        id = id,
        agentId = agentId,
        role = "Researcher",
        type = type,
        content = "content-$id",
        budgetSignature = signature,
        status = status,
        generation = 1,
        groupId = "group-fitness",
        runId = "run-fitness",
        submittedAtMs = id.hashCode().toLong()
    )

    private fun kbEntry(
        id: String,
        sourceAgentId: String,
        citationCount: Int
    ) = KnowledgeBaseEntryEntity(
        id = id,
        content = "content-$id",
        sourceAgentId = sourceAgentId,
        generation = 1,
        citationCount = citationCount,
        domain = "test",
        groupId = "group-citations",
        runId = "run-citations",
        createdAtMs = id.hashCode().toLong()
    )
}
