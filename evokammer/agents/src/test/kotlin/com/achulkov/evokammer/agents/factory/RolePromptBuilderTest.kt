package com.achulkov.evokammer.agents.factory

import com.achulkov.evokammer.core.contract.AgentBootContext
import com.achulkov.evokammer.core.model.Role
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

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
}
