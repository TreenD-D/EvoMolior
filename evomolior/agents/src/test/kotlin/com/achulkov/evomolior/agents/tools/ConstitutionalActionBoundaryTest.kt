package com.achulkov.evomolior.agents.tools

import com.achulkov.evomolior.core.budget.BudgetConfig
import com.achulkov.evomolior.core.budget.BudgetLedger
import com.achulkov.evomolior.core.model.GroupProfile
import com.achulkov.evomolior.core.model.GroupProfileType
import com.achulkov.evomolior.sandbox.enforcer.ActionEventType
import com.achulkov.evomolior.sandbox.enforcer.AgentAction
import com.achulkov.evomolior.sandbox.enforcer.ConstitutionalActionGate
import com.achulkov.evomolior.sandbox.enforcer.ConstitutionalEnforcer
import com.achulkov.evomolior.sandbox.enforcer.ConstitutionalEvent
import com.achulkov.evomolior.sandbox.enforcer.ConstitutionalViolationException
import com.achulkov.evomolior.substrate.SubstrateDatabaseFactory
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ConstitutionalActionBoundaryTest {
    @Test
    fun `oversized staging action is persisted and blocked before substrate mutation`() = runTest {
        val runId = "run-constitutional"
        val groupId = "group-0"
        val agentId = "agent-0"
        val db = SubstrateDatabaseFactory(createTempDirectory("constitutional-action-").toFile())
            .get(runId, groupId)
        val events = mutableListOf<ConstitutionalEvent>()
        val enforcer = ConstitutionalEnforcer()
        val gate = ConstitutionalActionGate { action ->
            enforcer.intercept(action, agentId, groupId) { event -> events.add(event) }
        }
        val config = BudgetConfig(
            groupId = groupId,
            profile = GroupProfile.default(GroupProfileType.Library)
        )
        val tool = WriteStagingTool(
            stagingDao = db.stagingDao(),
            budgetLedger = BudgetLedger(agentId, 100.0, 1.0, config),
            runId = runId,
            groupId = groupId,
            agentId = agentId,
            role = "Researcher",
            generation = 1,
            actionGate = gate
        )

        assertFailsWith<ConstitutionalViolationException> {
            tool.execute(
                WriteStagingTool.Args(
                    type = "DOC",
                    content = "x".repeat(256_001)
                )
            )
        }

        assertEquals(0, db.stagingDao().getTotalCountForAgent(runId, groupId, agentId))
        assertTrue(events.single().blocked)
        assertEquals(ActionEventType.STAGING_WRITE, events.single().action.type)
    }

    @Test
    fun `denied network action is blocked before fetcher invocation`() = runTest {
        var fetched = false
        val enforcer = ConstitutionalEnforcer()
        val gate = ConstitutionalActionGate { action ->
            enforcer.intercept(action, "agent-0", "group-0")
        }
        val tool = FetchSourceTool(
            runId = "run-constitutional",
            groupId = "group-0",
            agentId = "agent-0",
            role = "Researcher",
            generation = 1,
            runObjective = "Research approved evidence.",
            currentAllowedHosts = emptySet(),
            auditFilePath = null,
            fetcher = SourceFetcher {
                fetched = true
                FetchedSource(200, "text/plain", "unexpected")
            },
            actionGate = gate
        )

        assertFailsWith<ConstitutionalViolationException> {
            tool.execute(
                FetchSourceTool.Args(
                    url = "https://unapproved.example/source",
                    purpose = "Verify external evidence for the current objective."
                )
            )
        }

        assertTrue(!fetched)
    }
}
