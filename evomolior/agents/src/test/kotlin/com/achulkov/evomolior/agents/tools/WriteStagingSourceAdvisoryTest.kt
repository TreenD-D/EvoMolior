package com.achulkov.evomolior.agents.tools

import com.achulkov.evomolior.core.budget.BudgetConfig
import com.achulkov.evomolior.core.budget.BudgetLedger
import com.achulkov.evomolior.core.model.GroupProfile
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import com.achulkov.evomolior.substrate.db.dao.StagingDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WriteStagingSourceAdvisoryTest {
    @Test
    fun `DOC is persisted unchanged before advisory and charged only ordinary staging cost`() = runTest {
        withDatabase { dao ->
            val budget = ledger()
            val before = budget.balance
            val content = "Source quote: `exec \"\" \"\"`"
            val tool = tool(dao, budget) { candidate ->
                assertEquals(content, candidate)
                assertEquals(content, dao.getByAgent("run", "group", "agent").single().content)
                "Source advisory: submit one corrected DOC within the existing budget."
            }
            val response = tool.execute(WriteStagingTool.Args("DOC", content))
            assertTrue(response.startsWith("Submission accepted"))
            assertTrue(response.contains("Source advisory"))
            assertEquals("pending", dao.getByAgent("run", "group", "agent").single().status)
            val control = ledger()
            control.deduct(com.achulkov.evomolior.core.model.ActionType.WRITE_STAGING)
            assertEquals(before - control.balance, before - budget.balance)
        }
    }

    @Test
    fun `advisory failure cannot reject or rewrite persisted DOC`() = runTest {
        withDatabase { dao ->
            val tool = tool(dao) { error("source checker failed") }
            val response = tool.execute(WriteStagingTool.Args("DOC", "original"))
            assertTrue(response.startsWith("Submission accepted"))
            assertTrue(response.contains("advisory unavailable"))
            val stored = dao.getByAgent("run", "group", "agent").single()
            assertEquals("original", stored.content)
            assertEquals("pending", stored.status)
        }
    }

    @Test
    fun `non DOC submissions do not invoke report source check`() = runTest {
        withDatabase { dao ->
            var checked = false
            val tool = tool(dao) { checked = true; "unexpected" }
            val response = tool.execute(WriteStagingTool.Args("FACT", "fact"))
            assertTrue(response.startsWith("Submission accepted"))
            assertFalse(checked)
        }
    }

    @Test
    fun `checker cancellation propagates while the DOC remains durable`() = runTest {
        withDatabase { dao ->
            val tool = tool(dao) { throw CancellationException("stop") }
            assertFailsWith<CancellationException> {
                tool.execute(WriteStagingTool.Args("DOC", "durable"))
            }
            assertEquals("durable", dao.getByAgent("run", "group", "agent").single().content)
        }
    }

    @Test
    fun `advisory output is bounded without losing accepted submission identity`() = runTest {
        withDatabase { dao ->
            val response = tool(dao) { "x".repeat(20_000) }
                .execute(WriteStagingTool.Args("DOC", "report"))
            assertTrue(response.startsWith("Submission accepted (ID: "))
            assertTrue(response.length < 8_300)
        }
    }

    private suspend fun withDatabase(block: suspend (StagingDao) -> Unit) {
        val root = createTempDirectory("staging-source-advisory-").toFile()
        val db = SubstrateDatabaseFactory(root).get("run", "group")
        try {
            block(db.stagingDao())
        } finally {
            db.close()
            root.deleteRecursively()
        }
    }

    private fun tool(
        dao: StagingDao,
        ledger: BudgetLedger = ledger(),
        check: suspend (String) -> String?
    ) = WriteStagingTool(
        stagingDao = dao,
        budgetLedger = ledger,
        runId = "run",
        groupId = "group",
        agentId = "agent",
        role = "Builder",
        generation = 0,
        docSourceCheck = check
    )

    private fun ledger() = BudgetLedger(
        agentId = "agent",
        initialBudget = 100.0,
        floor = 1.0,
        config = BudgetConfig(groupId = "group", profile = GroupProfile.Library)
    )
}
