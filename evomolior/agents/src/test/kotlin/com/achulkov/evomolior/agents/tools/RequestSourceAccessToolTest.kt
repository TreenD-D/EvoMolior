package com.achulkov.evomolior.agents.tools

import com.achulkov.evomolior.sandbox.network.SourceAccessDecisionStore
import com.achulkov.evomolior.sandbox.enforcer.ActionEventType
import com.achulkov.evomolior.sandbox.enforcer.AgentAction
import com.achulkov.evomolior.sandbox.enforcer.ConstitutionalActionGate
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RequestSourceAccessToolTest {
    @Test
    fun `allowed source request is audited and exposes approved host`() = runTest {
        val auditFile = createTempDirectory("source-access-tool-").toFile()
            .resolve(SourceAccessDecisionStore.FILE_NAME)
        val tool = RequestSourceAccessTool(
            runId = "run-source-access",
            groupId = "group-0",
            agentId = "agent-1",
            role = "Researcher",
            generation = 2,
            runObjective = "Improve Qwen DashScope integration.",
            currentAllowedHosts = emptySet(),
            auditFilePath = auditFile.absolutePath
        )

        val result = tool.execute(
            RequestSourceAccessTool.Args(
                url = "https://help.aliyun.com/document_detail/test",
                purpose = "Verify DashScope source details for the Qwen backend."
            )
        )

        assertTrue(result.startsWith("ALLOWED"))
        val records = SourceAccessDecisionStore(auditFile).readAll()
        assertEquals(1, records.size)
        assertTrue(records.single().allowed)
        assertEquals("help.aliyun.com", records.single().host)
        assertTrue("help.aliyun.com" in SourceAccessDecisionStore(auditFile).approvedHosts())
    }

    @Test
    fun `blocked source request is audited without approving host`() = runTest {
        val auditFile = createTempDirectory("source-access-tool-block-").toFile()
            .resolve(SourceAccessDecisionStore.FILE_NAME)
        val tool = RequestSourceAccessTool(
            runId = "run-source-access",
            groupId = "group-0",
            agentId = "agent-1",
            role = "Researcher",
            generation = 2,
            runObjective = "Improve Qwen DashScope integration.",
            currentAllowedHosts = emptySet(),
            auditFilePath = auditFile.absolutePath
        )

        val result = tool.execute(
            RequestSourceAccessTool.Args(
                url = "file:///etc/passwd",
                purpose = "Try to inspect a local file as a source."
            )
        )

        assertTrue(result.startsWith("BLOCKED"))
        val records = SourceAccessDecisionStore(auditFile).readAll()
        assertEquals(1, records.size)
        assertTrue(!records.single().allowed)
        assertTrue(SourceAccessDecisionStore(auditFile).approvedHosts().isEmpty())
    }

    @Test
    fun `wrapped koog value arguments are accepted`() = runTest {
        val auditFile = createTempDirectory("source-access-tool-wrapped-").toFile()
            .resolve(SourceAccessDecisionStore.FILE_NAME)
        val tool = RequestSourceAccessTool(
            runId = "run-source-access",
            groupId = "group-0",
            agentId = "agent-1",
            role = "Outlier",
            generation = 2,
            runObjective = "Find fast micro-SaaS and app product hypotheses.",
            currentAllowedHosts = emptySet(),
            auditFilePath = auditFile.absolutePath
        )

        val result = tool.execute(
            RequestSourceAccessTool.Args(
                value = RequestSourceAccessTool.ArgsValue(
                    url = "https://www.reddit.com/r/adhd/comments",
                    purpose = "Inspect ADHD productivity app complaint evidence."
                )
            )
        )

        assertTrue(result.startsWith("ALLOWED"))
        val records = SourceAccessDecisionStore(auditFile).readAll()
        assertEquals("www.reddit.com", records.single().host)
        assertTrue(records.single().allowed)
    }

    @Test
    fun `constitutional gate intercepts decision before audit persistence`() = runTest {
        val auditFile = createTempDirectory("source-access-tool-gate-").toFile()
            .resolve(SourceAccessDecisionStore.FILE_NAME)
        var observedAction: AgentAction? = null
        val tool = RequestSourceAccessTool(
            runId = "run-source-access",
            groupId = "group-0",
            agentId = "agent-1",
            role = "Researcher",
            generation = 2,
            runObjective = "Improve Qwen DashScope integration.",
            currentAllowedHosts = emptySet(),
            auditFilePath = auditFile.absolutePath,
            actionGate = ConstitutionalActionGate { action ->
                observedAction = action
                error("blocked by test gate")
            }
        )

        assertFailsWith<IllegalStateException> {
            tool.execute(
                RequestSourceAccessTool.Args(
                    url = "https://help.aliyun.com/document_detail/test",
                    purpose = "Verify DashScope source details for the Qwen backend."
                )
            )
        }

        assertEquals(
            AgentAction(
                type = ActionEventType.SOURCE_ACCESS_DECISION,
                agentId = "agent-1",
                detail = "https://help.aliyun.com/document_detail/test",
                allowed = true
            ),
            observedAction
        )
        assertTrue(SourceAccessDecisionStore(auditFile).readAll().isEmpty())
    }
}
