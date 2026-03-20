package com.achulkov.evokammer.agents.factory

import com.achulkov.evokammer.core.contract.AgentBootContext
import com.achulkov.evokammer.core.model.Role

/**
 * Assembles the system prompt for an agent based on its role, boot context, and Legacy Protocol.
 *
 * The prompt structure:
 * 1. Role system prompt (from StartingField rolePrompts)
 * 2. Stage disclosure (constraint-framed task description)
 * 3. WiP task assignment (if present)
 * 4. Legacy Protocol (if present — injected as privileged context block)
 * 5. General EvoKammer instructions (norms, tool usage, death note reminder)
 */
object RolePromptBuilder {

    /**
     * Builds the full system prompt for an agent.
     *
     * @param bootContext The agent's boot context containing all injected context.
     * @return The complete system prompt to inject into the Koog AIAgent.
     */
    fun build(bootContext: AgentBootContext): String = buildString {
        // 1. Role-specific system prompt
        appendLine(bootContext.rolePrompt)
        appendLine()

        // 2. Stage disclosure (constraint-framed task)
        appendLine("=== CURRENT TASK CONTEXT ===")
        appendLine(bootContext.stageDisclosure)
        appendLine()

        // 3. WiP task assignment
        val claim = bootContext.wipTaskClaim
        if (claim != null) {
            appendLine("=== YOUR ASSIGNED TASK ===")
            appendLine("Task ID: ${claim.nodeId}")
            appendLine("Task: ${claim.description}")
            appendLine()
        } else if (bootContext.role == Role.Outlier) {
            appendLine("=== YOUR ROLE: OUTLIER ===")
            appendLine("You are an Outlier. You have no assigned task.")
            appendLine("Explore unconventional directions. Question assumptions. Propose heterodox ideas.")
            appendLine("Do not follow established patterns — you exist to find what others miss.")
            appendLine()
        }

        // 4. Legacy Protocol (privileged context from previous generation's elite agents)
        bootContext.legacyProtocol?.let { protocol ->
            appendLine("=== INHERITED WISDOM (Legacy Protocol — Generation ${protocol.extractedFromGeneration}) ===")
            appendLine("This is distilled knowledge from elite ${protocol.role.name} agents of previous generations.")
            appendLine("Treat it as privileged guidance, not as strict rules.")
            appendLine()
            appendLine("Reasoning Posture:")
            appendLine(protocol.reasoningPosture)
            appendLine()
            appendLine("Tool Heuristics:")
            appendLine(protocol.toolHeuristics)
            appendLine()
            appendLine("Known Failure Patterns:")
            appendLine(protocol.failureMap)
            appendLine()
            if (protocol.kgHabits.isNotBlank()) {
                appendLine("KG Emission Habits:")
                appendLine(protocol.kgHabits)
                appendLine()
            }
        }

        // 5. Universal EvoKammer norms
        appendLine("=== OPERATING NORMS ===")
        appendLine(UNIVERSAL_NORMS)
        appendLine()
        appendLine("=== TOOLS REMINDER ===")
        appendLine(TOOLS_REMINDER)
    }

    private val UNIVERSAL_NORMS = """
        - You are an agent inside a civilisational multi-agent system.
        - Your work accumulates across generations — write for your successors, not just for yourself.
        - Dead Ends in the KB document failed approaches. Read them before proposing new directions.
        - All your outputs must go through staging (write_staging or emit_fact). Never assume direct access.
        - You MUST write a Death Note before you terminate — this is mandatory and preserves your learning.
        - Budget is finite. Avoid retrying the same tool with the same arguments.
        - Reads (read_kb, read_kg) are free. Writes and executions cost budget.
        - Prefer building on existing KB knowledge rather than rediscovering from scratch.
    """.trimIndent()

    private val TOOLS_REMINDER = """
        Available tools (most common):
        - read_kb(query, domain?, maxResults?): Search KB — FREE
        - write_staging(type, content, wipTaskRef?, speculative?): Submit work — costs 1
        - execute_tool(toolName, argsJson, toolVersion?): Run a tool — costs 2-8
        - claim_task(nodeId?): Claim a WiP task — FREE
        - write_death_note(summary, whatWorked, whatFailed, recommendations): MANDATORY on exit — FREE
        
        Always check the KB for Dead Ends before starting an approach.
        Always write your Death Note as your last action.
    """.trimIndent()
}
