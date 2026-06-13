package com.achulkov.evokammer.agents.factory

import com.achulkov.evokammer.core.contract.AgentBootContext
import com.achulkov.evokammer.core.model.Role
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies that ordinary agent prompts only include the constraint-framed task context.
 */
class RolePromptBuilderTest {

    @Test
    fun `ordinary agent prompt includes task context but no goal-framed section`() {
        val prompt = RolePromptBuilder.build(
            AgentBootContext(
                agentId = "agent-1",
                runId = "run-1",
                groupId = "group-0",
                role = Role.Researcher,
                rolePrompt = "You are a Researcher.",
                budgetToken = "budget-token",
                substrateDbPath = "/tmp/substrate.db",
                stagingMountPath = "/staging",
                wipTaskClaim = null,
                legacyProtocol = null,
                stageDisclosure = "Constraint: do not reveal the target objective.",
                modelAssignment = "openai/gpt-4o-mini",
                ipcBaseUrl = "http://127.0.0.1:10000",
                generation = 1
            )
        )

        assertContains(prompt, "=== CURRENT TASK CONTEXT ===")
        assertContains(prompt, "Constraint: do not reveal the target objective.")
        assertFalse(prompt.contains("goalFramedContent", ignoreCase = true))
        assertFalse(prompt.contains("=== GOAL", ignoreCase = true))
    }

    @Test
    fun `ordinary agent prompt never includes hidden goal wording`() {
        val hiddenGoal = "FIND THE TARGET OBJECT IN PHASE 2"
        val prompt = RolePromptBuilder.build(
            AgentBootContext(
                agentId = "agent-2",
                runId = "run-1",
                groupId = "group-0",
                role = Role.Builder,
                rolePrompt = "You are a Builder.",
                budgetToken = "budget-token",
                substrateDbPath = "/tmp/substrate.db",
                stagingMountPath = "/staging",
                wipTaskClaim = null,
                legacyProtocol = null,
                stageDisclosure = "Constraint: operate only on the disclosed stage constraints.",
                modelAssignment = "openai/gpt-4o-mini",
                ipcBaseUrl = "http://127.0.0.1:10000",
                generation = 1
            )
        )

        assertContains(prompt, "Constraint: operate only on the disclosed stage constraints.")
        assertFalse(prompt.contains(hiddenGoal, ignoreCase = true))
    }

    @Test
    fun `ordinary agent prompt does not leak goal-framed disclosure content`() {
        val hiddenGoal = "GOAL: identify the hidden artifact location"
        val prompt = RolePromptBuilder.build(
            AgentBootContext(
                agentId = "agent-3",
                runId = "run-1",
                groupId = "group-0",
                role = Role.Researcher,
                rolePrompt = "You are a Researcher.",
                budgetToken = "budget-token",
                substrateDbPath = "/tmp/substrate.db",
                stagingMountPath = "/staging",
                wipTaskClaim = null,
                legacyProtocol = null,
                stageDisclosure = "Constraint: only use disclosed stage constraints.",
                modelAssignment = "openai/gpt-4o-mini",
                ipcBaseUrl = "http://127.0.0.1:10000",
                generation = 1
            )
        )

        assertFalse(prompt.contains(hiddenGoal, ignoreCase = true))
        assertFalse(prompt.contains("GOAL:", ignoreCase = true))
        assertContains(prompt, "Constraint: only use disclosed stage constraints.")
    }

    @Test
    fun `round submission summary prompt requires fallback write even when substrate is sparse`() {
        val prompt = RolePromptBuilder.buildRoundSubmissionSummary(
            runObjective = "Create a standalone HTML article for mindvortex.app",
            roundIndex = 0,
            groupId = "group-0",
            attempt = 2
        )

        assertContains(prompt, "If your read tools return little or no evidence, you MUST still write the summary.")
        assertContains(prompt, "Do not stop after read-only tool calls.")
        assertContains(prompt, "If no prior HTML draft exists")
        assertContains(prompt, "=== RETRY CONTEXT ===")
        assertTrue(prompt.contains("attempt 2", ignoreCase = true))
    }

    @Test
    fun `final deliverable prompt requires publication grade html article`() {
        val prompt = RolePromptBuilder.buildFinalDeliverableFile(
            runObjective = "Create a standalone HTML article for mindvortex.app",
            roundCount = 10,
            groupId = "group-0",
            relativePath = "final-article.html",
            mediaType = "text/html",
            description = "Publication article"
        )

        assertContains(prompt, "embedded <style> rules")
        assertContains(prompt, "at least 6 substantive sections")
        assertContains(prompt, "FINAL publication artifact")
        assertContains(prompt, "roughly 1,200-2,000 words")
        assertContains(prompt, "CTA or next-step section")
    }

    @Test
    fun `final deliverable prompt supports generic non-html file outputs`() {
        val prompt = RolePromptBuilder.buildFinalDeliverableFile(
            runObjective = "Produce a machine-readable JSON content brief",
            roundCount = 1,
            groupId = "group-0",
            relativePath = "content-brief.json",
            mediaType = "application/json",
            description = "Structured content brief",
            attempt = 2
        )

        assertContains(prompt, "Relative path: content-brief.json")
        assertContains(prompt, "Media type: application/json")
        assertContains(prompt, "FULL file body only")
        assertContains(prompt, "do not restate them in the body")
        assertContains(prompt, "valid JSON")
        assertContains(prompt, "=== RETRY CONTEXT ===")
    }

    @Test
    fun `patch bundle prompt requires edited files and documentation`() {
        val prompt = RolePromptBuilder.buildPatchBundleFile(
            runObjective = "Produce a machine-readable patch bundle for the code changes",
            roundCount = 2,
            groupId = "group-0",
            relativePath = "self-improvement-patch-bundle.json",
            mediaType = "application/json",
            description = "Machine-readable patch bundle with edited files, diffs, and documentation.",
            attempt = 2
        )

        assertContains(prompt, "Patch Bundle Writer")
        assertContains(prompt, "`changedFiles`")
        assertContains(prompt, "`documentation`")
        assertContains(prompt, "unified diffs")
        assertContains(prompt, "=== RETRY CONTEXT ===")
        assertContains(prompt, "PATCH BUNDLE HINTS")
    }
}
