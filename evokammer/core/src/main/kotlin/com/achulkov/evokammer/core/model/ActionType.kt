package com.achulkov.evokammer.core.model

import kotlinx.serialization.Serializable

/**
 * All budget-costed action types in the EvoKammer system.
 *
 * Costs are defaults only — the actual cost per action is computed by [BudgetConfig]
 * which applies per-group asymmetric cost multipliers. LLM_CALL is proportional to
 * output token count and has no fixed default.
 *
 * @see BudgetConfig for per-group multipliers
 */
@Serializable
enum class ActionType(
    /** Default cost in abstract budget units before group multiplier is applied. */
    val defaultCost: Double,
    /** Human-readable description for event logs. */
    val description: String
) {
    /** Read from the Knowledge Base. Always free. */
    READ_KB(0.0, "Read KB document"),

    /** Read from the Knowledge Graph (pattern query without traversal). Always free. */
    READ_KG(0.0, "Read KG fact"),

    /** Semantic or keyword search over the Knowledge Base. Light cost. */
    SEARCH_KB(0.1, "Search KB"),

    /** Typed pattern traversal query on the Knowledge Graph. */
    QUERY_PATTERN_KG(0.5, "Pattern query on KG"),

    /** Submit a document, tool, fact, or death note to the Staging Area. */
    WRITE_STAGING(1.0, "Write to Staging"),

    /** Execute a tool from the Tool Registry in the tool sub-sandbox (success). */
    TOOL_EXEC_SUCCESS(2.0, "Tool execution (success)"),

    /** Execute a tool from the Tool Registry in the tool sub-sandbox (failure). */
    TOOL_EXEC_FAILURE(4.0, "Tool execution (failure)"),

    /**
     * Execute the same tool with the same arguments as a previous call in this lifecycle.
     * Quadruple penalty to discourage thrashing.
     */
    TOOL_EXEC_RETRY(8.0, "Tool execution (retry — same args)"),

    /** Send a message to another agent on the inter-agent message bus. */
    INTER_AGENT_MESSAGE(0.5, "Inter-agent message"),

    /**
     * LLM API call. Cost is proportional to output tokens; this default is per-token.
     * Actual cost = outputTokens * defaultCost * groupMultiplier.
     */
    LLM_CALL(0.001, "LLM call (per output token)"),

    /** Propose a typed KG fact to the Staging Area for Checker + Critic review. */
    EMIT_FACT(1.0, "Emit KG fact"),

    /** Propose a new soft KG constraint to the Staging Area for Checker + Critic review. */
    EMIT_CONSTRAINT(2.0, "Emit KG constraint"),

    /**
     * Write the Death Note on agent termination. Always zero cost — guaranteed to execute
     * even when budget is at floor. Uses a reserved budget slice.
     */
    WRITE_DEATH_NOTE(0.0, "Write Death Note"),

    /**
     * Validate a batch of staged facts against the KG schema. Zero cost per batch.
     * Encourages agents to check before submitting.
     */
    CHECK_CONSISTENCY(0.0, "Check KG consistency"),

    /** Claim a WiP task node from the WiP registry. Free. */
    CLAIM_TASK(0.0, "Claim WiP task"),

    /** Web content fetch (routed through AllowlistProxy + ContentSanitiser). */
    WEB_FETCH(0.1, "Web fetch");

    /** True if this action type always has zero cost regardless of group multiplier. */
    val isAlwaysFree: Boolean
        get() = defaultCost == 0.0
}
